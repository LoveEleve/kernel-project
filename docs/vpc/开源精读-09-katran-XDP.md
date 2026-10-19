# 开源精读 09 · Katran：XDP 上的 L4LB——把状态搬进 map，把内核让开

> 课次：开源精读路线第 9 课（数据面·收官：内核 / DPDK / XDP 三形态的最后一块）
> 对象：`refs/katran`（commit `bed5e56`，Meta 开源）
> 对照：第 8 课 DPVS（DPDK-LVS）、IPVS（内核 LVS）；我们的数据面（vpcko / DPDK 通道）
> 纪律：路径与行号来自实仓核验；"IPVS→XDP"的完整官博叙事不在本仓，结论从代码与 README 推导（标注处不越界）。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **XDP** | eBPF 的"最早挂钩子"：包刚出网卡（driver mode）或刚进栈（generic mode）就被 BPF 程序处理——**内核网络栈还没碰它** |
| **DSR** | Direct Server Return：回包**不经过 LB**，real 直接回 client——LB 只管半边 |
| **IPIP / GUE** | 两种封装；Katran 转发 = 把原包塞进一个 IPIP 外层（"packet in packet"） |
| **ch ring** | 一致性哈希环（consistent hash rings）——放在 BPF map 里的扁平数组 |
| **per-CPU LRU** | 会话表：每 CPU 一张 LRU_HASH，无锁 |
| **on a stick** | 单口进、单口出（发的封装包从收包的同一口回去） |

## ① 它干了什么——一个连接的三个包

**首包（新会话）**：包到达网卡 → XDP 钩子（driver mode：先于内核栈）→ `balancer_ingress` → `process_packet`：VIP 命中 → 查会话表（miss）→ 5 元组哈希 → **查 ch_rings 选 real** → **把 flow→real 写进本 CPU 的 LRU 表** → IPIP 封装（外层源地址按流特异化，见跳 5）→ `XDP_TX` 从原口发回。

**后续包**：会话表命中 → 同一 real → 封装直发（**不重算哈希**）。

**回包**：**不经过 Katran**（DSR！）——real 直接回 client；L7 侧解封装用 `katran/decap/`（RS 侧程序，XDP/tc 两形态）。

## ② 链路走查（file:line 级）

### 跳 1：官方七步 + 三条硬约束

`README.md`（七步流程，原文节选）：

> 1. katran receives packet
> 2. Checks if the destination of the packet is configured as a VIP …
> 3. … checking if it saw packet from the same session before …
> 4. If it's a new session - from 5 tuples in the packet, calculate a hash value.
> 5. Using this hash value - pick a real server.
> 6. **Update session table** with this lookup information …
> 7. **Encapsulate** packet in another IP packet and send to the real.

三条硬约束（同文件）：**"katran works only in DSR mode"**；网络必须 L3（"we are 'offloading' routing decision … to first routing device by unconditionally sending all packets from katran there"）；**"load balancer on a stick"**（单口进出）。不支持分片/IP options，包上限 3.5k。

### 跳 2：XDP 入口——stats 先行、两协议分派

`katran/lib/bpf/balancer.bpf.c:1160`：

```c
int balancer_ingress(struct xdp_md* ctx) {
  void* data = (void*)(long)ctx->data;
  void* data_end = (void*)(long)ctx->data_end;
  struct ethhdr* eth = data;
  ...
  if (data + nh_off > data_end) {
        // bogus packet, len less than minimum ethernet frame size
    return XDP_DROP;
  }
  stats_key = MAX_VIPS + XDP_TOTAL_CNTR;
  data_stats = bpf_map_lookup_elem(&stats, &stats_key);
  ...
  data_stats->v1 += 1;
  data_stats->v2 += data_len;
  ...
  if (eth_proto == BE_ETH_P_IP) {
    action = process_packet(ctx, nh_off, false);
  } else if (eth_proto == BE_ETH_P_IPV6) {
    action = process_packet(ctx, nh_off, true);
  } else {
        // pass to tcp/ip stack
    action = XDP_PASS;
  }
```

每个分支的结局只有三种：`XDP_TX`（处理完原口发回）/ `XDP_PASS`（给内核）/ `XDP_DROP`——**一个 1213 行的文件就是整个转发面**（入口 `balancer_ingress` 只做分派，大头在 `process_packet@715`）。

### 跳 3：选路核心——LPM 特例、ch ring 哈希、会话写入

`balancer.bpf.c:87` 的 `get_packet_dst`：先处理一个特例——**按客户端源 IP 的 LPM 路由**（有则跳过哈希）：

```c
#ifdef LPM_SRC_LOOKUP
  if ((vip_info->flags & F_SRC_ROUTING) && !under_flood) {
    ...
    lpm_val = bpf_map_lookup_elem(&lpm_src_v4, &lpm_key_v4);   // 源 IP 前缀→real 位
    if (lpm_val) { src_found = true; key = *lpm_val; }
  }
#endif
```

没有特例（`!src_found`）才走**哈希选路**（`:133` 起）：

```c
    hash = get_packet_hash(pckt, hash_16bytes) % RING_SIZE;
    key = RING_SIZE * (vip_info->vip_num) + hash;

    real_pos = bpf_map_lookup_elem(&ch_rings, &key);
    ...
```

哈希环就是**一张扁平数组 `ch_rings`，每个 VIP 切一段**。选完 real 后写会话（`:166` 起）——条件本身全是学问：

```c
  if (lru_map && !(vip_info->flags & F_LRU_BYPASS) && !under_flood &&
      !(pckt->flags & F_RST_SET)) {
    if (pckt->flow.proto == IPPROTO_UDP) {
      new_dst_lru.atime = cur_time;
    }
    new_dst_lru.pos = key;
    bpf_map_update_elem(lru_map, &pckt->flow, &new_dst_lru, BPF_ANY);
  }
```

**RST 不进表**（否则攻击者用 RST 污染会话）、洪泛时不写（`under_flood`）、VIP 可整段旁路（`F_LRU_BYPASS`）。

### 跳 4：数据结构——会话表挂在 CPU 上

`katran/lib/bpf/balancer_maps.h:64`：

```c
// map which contains cpu core to lru mapping
struct {
  __uint(type, BPF_MAP_TYPE_ARRAY_OF_MAPS);
  ...
  __array(
      values,
      struct {
        __uint(type, BPF_MAP_TYPE_LRU_HASH);
        __type(key, struct flow_key);
        __type(value, struct real_pos_lru);
        __uint(max_entries, DEFAULT_LRU_SIZE);
      });
} lru_mapping SEC(".maps");
```

**array-of-maps：外层按 CPU 核索引，每核一张独立 LRU_HASH**——这就是"lockless"的物理形态（另有 `global_lru_maps@215` 共享表）。哈希环呢（`:80`）：

```c
// map which contains all vip to real mappings
struct {
  __uint(type, BPF_MAP_TYPE_ARRAY);
  __type(key, __u32);
  __type(value, __u32);
  __uint(max_entries, CH_RINGS_SIZE);
} ch_rings SEC(".maps");
```

一张**扁平 ARRAY**：`key = RING_SIZE * vip_num + hash`（跳 3 的寻址公式）——"每 VIP 一段环"用一个数组搞定。

### 跳 5：封装与 RSS-friendly 的深意

封装实现在 `pckt_encap.h`（`balancer.bpf.c:22` include）；对"本来就是封装包"的流量另有两条路径：`process_encaped_ipip_pckt@306` / `process_encaped_gue_pckt@436`（调用点 `:777/786/807`），封装失败有专门计数（`:1138 encap_fail`）。

而 README 对"外层源地址"的一段话值得整段读：

> **RSS friendly encapsulation**: katran is uses ipip encapsulation … instead of using the same source for every ipip packet, katran **crafts a special one**, in such a way, that **different flows will have different outer (ipip) source IP, but packets in same flow will always have the same.**

**为什么值得专门设计**：封装包到达 L7 LB 侧，它按**外层五元组**做 RSS——如果所有封装包同源，全部砸向 L7 的同一队列；按流特异化外层源，L7 的多队列才分得匀。**一个反直觉的小设计，决定的是上一层能不能水平扩。**

### 跳 6：工程味的真相——代码被验证器塑形

`process_packet`（`:715`）里 `INLINE_DECAP_IPIP` 分支有一段长注释，开头即：

> This is to workaround a verifier issue for 5.2. … 5.2 verifier does not handle register copy states properly while 5.6 handles properly.

往后是逐条指令级别的分析（"llvm12 may generate the following simplified code sequence…"）。**eBPF 的数据面代码不只被业务需求写，也被内核验证器写**——这是三形态里 XDP 独有的工程日常。

## ③ 设计推导（问题树）

**Q1：XDP 凭什么比 IPVS 路径短、比 DPDK 省核？**
对比三条路：IPVS 要穿 netfilter 钩子链 → 路由 → conntrack；DPVS 把整栈 bypass 到用户态轮询（要独占核+DPDK 依赖）；XDP 在 driver mode 下**包刚出 DMA 就执行**——省掉 sk_buff 分配、省掉协议栈、省掉系统调用与中断。状态全部 map 化后无锁，README 原话："Performance scales linearly with a number of NIC's RX queues"。代价见 Q2 的反面。

**Q2："从 IPVS 走向 XDP"的三条可迁移结论**（诚实标注：Meta 官博不在本仓，以下从 README+代码推导）：
① **状态与逻辑分离**——会话/环/real 全在 map，程序只承载逻辑；升级换的是"数据+程序挂载"，不再动内核模块；② **单次线性 pass**——一钩到底，没有"每层各扫一遍"；③ **per-CPU 无锁**——per-CPU LRU、扁平环数组、per-CPU stats，三件套互相成全。
而"快"的代价是"窄"：仅 DSR、要求 L3 拓扑、不分片、不碰 IP options、包长上限——**README 把这些限制列在同一页，和特性并列**（诚实文档的范本）。

**Q3：DSR 的复式记账——LB 为什么可以随便重启？**
README failure 段（原文）："different L4 lbs are consistent in real server selection, **even w/o explicit state sharing amongst each other**. This feature allows us to restart/drain single L4 lb w/o affecting TCP sessions, going to the L7 lbs."
因果链：**回包不走 LB**（DSR）+ **选路纯哈希**（同 5 元组→同 real，任何 LB 实例算出同一答案）→ 单台 LB 的会话表只是"缓存的加速",不是"权威状态"。代价清单：real 必须解封装（`decap/`）、拓扑 L3、健康检查与探活另有安排（`healthchecking.bpf.c` 一族）。

**Q4：会话写入的四个条件在防什么？**
`lru_map` 存在（功能开关）→ `!F_LRU_BYPASS`（VIP 级旁路）→ `!under_flood`（洪泛期不写表，防击穿）→ `!F_RST_SET`（**RST 不进表**，防会话污染）。**"写状态"的每个前提都是一类攻击或故障的教训固化。**

**Q5：三形态各把"状态"放在哪？**（见下方总表——数据面批次的收口问题）

## ④ 对照卡：数据面三形态收官（+ 我们）

| 维度 | IPVS（内核 LVS） | DPVS（第 8 课） | Katran（本课） | 我们（vpcko / DPDK 通道） |
|---|---|---|---|---|
| 挂点 | netfilter 钩子 | 用户态轮询（bypass） | **XDP（driver 最早钩子）** | 内核模块 + 用户态 PAL |
| 状态归属 | 内核 conn 表 | 用户态 per-lcore conn | **BPF map（per-CPU LRU + ch ring）** | 路由/LPM（route-based，状态在别处） |
| 并发模型 | per-CPU 化 | share-nothing per-lcore | per-CPU maps 无锁 | RCU / netlink |
| 转发模式 | FNAT/DR/SNAT | FNAT/DR/Tunnel/DNAT | **仅 DSR + IPIP/GUE** | GRE + LPM |
| 控制面 | ipvsadm（sockopt） | dpip/keepalived（sockopt） | C++ BpfLoader + gRPC/thrift | agent（genetlink） |
| "快"的账单 | 受栈深限制 | 独占核 + DPDK 依赖 | verifier 约束 + 功能窄 | — |
| 一句话 | 经典语义 | 把经典搬到用户态 | **把状态搬到 map、把内核让开** | — |

## ⑤ 验收题

1. XDP driver mode 至少省掉哪三件事（netfilter/sk_buff/中断/系统调用……）？省掉哪一件对 PPS 影响最大、为什么？
2. `ch_rings` 为什么是一个扁平 ARRAY（寻址公式 `RING_SIZE * vip_num + hash`）而不是"每 VIP 一个 map"？
3. 会话写入的四个条件（lru_map / F_LRU_BYPASS / under_flood / F_RST_SET）各防什么？
4. 完整说出"LB 重启不影响存量 TCP"的因果链；并列出它的代价清单（real/decap、拓扑、探活）。
5. 三形态把状态分别放在哪？如果今天设计云 LB，控制面抄谁、数据面抄谁——用本课三条可迁移结论作答。

---

*本课行号清单（供追问对质）：`katran/lib/bpf/balancer.bpf.c`（1213 行）— balancer_ingress@1160、process_packet@715、get_packet_dst@87（调用@1095）、connection_table_lookup@177（调用@295/1060）、ch_rings 寻址@143-146、LPM_SRC 块@104 起、LRU 写入@166 起、已封装处理@306/436（调用@777/786/807）、encap-fail@1138、pckt_encap include@22；`katran/lib/bpf/balancer_maps.h` — lru_mapping@63-78、ch_rings@80-87、global_lru_maps@215；`README.md`（七步/DSR-only/L3/on-a-stick/RSS-friendly/failure 段/特性三连）；`katran/decap/`（XdpDecap.cpp + bpf/ + tc_bpf/）；`katran/lib/bpf/healthchecking.bpf.c`。*

*审查记录（首轮深审）：修正 4 处——① **体量口径混淆**："一个 1200 行的函数"实为"**1213 行的文件**"（balancer_ingress 仅 1160-1213 的分派壳，process_packet@715 才是大头）；② `F_LRU_BYPASS` 会话写入块实为 **@166 起**（原写 161，估读）；③ `LPM_SRC_LOOKUP` 实为 **@104 起**（原写 102）；④ balancer_maps 注释@63（原从 64 起算）。顺带补 connection_table_lookup 调用点@295/1060。其余引用（ingress@1160、get_packet_dst@87、ch_rings@146、maps/README/decap 各处）逐条 grep 命中。*

*审查记录（二次深审）：① R1 四处修正自查通过（1213 行/文件口径、@166、@104 起、@63）；② **引文保真回修**——ingress 引码里我把两行原文注释替换成了中文（`// bogus packet…` → "// 不合法包直接丢"、`// pass to tcp/ip stack` → "// 非 IP…"）并额外加注"// 包计数"；已复原原文两注释、删去自加注释（第四种引文病：**替换而非转述**，与"静默纠错"同族）；③ 顺序对账：会话查表@1060 **先于** 哈希选路@1095（叙事顺序正确，补调用点@1095）；④ 边界诚实：RSS-friendly 源的**代码落点**未在 `katran/lib` 以 `ipip_src` 之名定位——课文保持 README 级引用，不越界。*

