# 开源精读 07 · tf-vrouter 四形态：一份 dp-core，四张皮

> 课次：开源精读路线第 7 课（链③数据面；接第 6 课"三链"的最后一跳）
> 对象：`refs/tf-vrouter`（commit `432a8e0`）
> 对照：我们的数据面双通道（vpcko 内核 + DPDK/PAL 用户态）；材料：`vpc/docs-by-topic/01 附录 A`（内核模块对照行@3012）
> 纪律：路径与行号来自实仓核验。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **dp-core** | 可移植的转发核心（C）：flow 表、接口、隧道、VRF——**四形态共享同一份** |
| **四形态** | `linux/`（内核模块）、`dpdk/`（DPDK 用户态，43 项）、`uvrouter`（host 抽象上的用户态应用）、`freebsd/` |
| **flow entry** | 每流状态：key(5 元组) + action + 反向流索引 + 统计——**有状态转发**的核心 |
| **HOLD** | 首包动作：不认识就"扣住"上报 agent 决策——首包慢路径 |
| **uvhost** | TF 自研的 user-space vhost server（与 qemu 的 vhost client 对接）——DPDK 形态的 VM 接入 |
| **genetlink** | 用户态↔vrouter 的 netlink 通道（第 6 课代差实锤：全形态统一） |

## ① 它干了什么——一个 TCP 流的"首包与后续包"

**首包**（源 VM 发出，vrouter 没见过这条流）：flow 查找未命中 → 命中动作 **`HOLD`**（`VR_FLOW_ACTION_HOLD = 0x1`）→ 包被扣进 **hold 队列**（`fe_hold_list`）→ 上报 agent（经 **pkt0** 通道）→ agent 算好 VRF/隧道封装（MPLS 标签）/下一跳/动作 → **装表**（新的 flow entry，同时登记**反向流** `fe_rflow`）→ 被扣的包被"放行"重放。

> 为什么首包系统不会被打爆？`include/vr_flow.h:221` 起的注释写得明明白白：

```c
/*
 * Limit the number of outstanding flows in hold state. The flow rate can
 * be much more than what agent can handle. In such cases, to make sure that
 *
 * . pkt0 is not overrun
 * . too many packets are not cached in flow table
 * . too many entries in hold state which will get serviced slowly (of the
 * order of seconds)
 * . and thus starving the table of new entries
 *
 * we limit the number of entries that are in hold state. …
```

**首包慢路径是秒级的**（"serviced slowly (of the order of seconds)"）——所以持有量必须有上限；这段话同时是 pkt0 作为"上报通道"的文本证据。

**后续包**：直接命中 flow entry → 按 `fe_action` 快速转发（`FORWARD`/`NAT`/`DROP`——`vr_flow.h:12-15`）；流老化前一直是数据面短路。**"首包问控制面，后续包数据面自己扛"**——这就是 flow-based forwarding。

## ② 链路走查（file:line 级）

### 跳 1：四形态的文件学

```
tf-vrouter/
├── dp-core/     ← 共享核心（vr_flow.c 3395 行 / vr_interface.c 5501 行 / vr_datapath.c 1023 行）
├── linux/       ← 壳1：内核模块（6 个 .c + SConscript：vrouter_mod.c 2789 行 / vr_genetlink.c / vhost_dev.c / …）
├── dpdk/        ← 壳2：43 项的大子系统（ethdev / lcore / uvhost 全套 / representor / GRO·GSO·offloads / virtio）
├── uvrouter/    ← 壳3：用户态应用（uvrouter.c——"vrouter application"，跑在 host/ 抽象上）
├── host/        ← 可移植宿主抽象层（vr_host_interface.c / vr_host_packet.c / vr_host_message.c / ulinux.c…）
└── freebsd/     ← 壳4
```

**一份 dp-core + 四种宿主适配**——这是全部设计的出发点。

### 跳 2：flow entry——有状态转发的全部秘密

`include/vr_flow.h:402`：

```c
struct vr_flow_entry {
    vr_hentry_t fe_hentry;
    ...
    struct vr_flow fe_key;            /* 流 key（proto/ports/ip/nh_id；union 支持 v4/v6） */
    uint8_t fe_gen_id;                /* 代际 id——失效/重装流的"版本号" */
    ...
    struct vr_flow_queue *fe_hold_list;  /* 首包扣留队列 */
    ...
    int fe_rflow;                     /* 反向流索引——双向流成对存在 */
    unsigned short fe_action;         /* DROP/HOLD/FORWARD/NAT */
    unsigned short fe_vrf;
    unsigned short fe_dvrf;           /* 源 VRF / 目的 VRF */
    ...
    uint32_t fe_src_nh_index;
    struct vr_flow_stats fe_stats;    /* 每流统计 */
    int8_t fe_ecmp_nh_index;          /* ECMP 下一跳 */
    ...
```

四个动作（`include/vr_flow.h:12-15`）：

```c
#define VR_FLOW_ACTION_DROP         0x0
#define VR_FLOW_ACTION_HOLD         0x1
#define VR_FLOW_ACTION_FORWARD      0x2
#define VR_FLOW_ACTION_NAT          0x3
```

看一眼字段表就懂它的世界观：**NAT/镜像(`fe_mirror_id`)/ECMP/TCP 状态/双向配对/统计全是"流"的属性**——TF 把分布式防火墙、SNAT、LB 都做进了流粒度。表操作在 `dp-core/vr_flow.c`：取流 `vr_flow_get_entry@369`、分配新流 `vr_flow_get_free_entry@567`、反向流源信息 `vr_flow_get_rflow_src_info@307`；接口路径上还有一个查找样例 `vif_fat_flow_lookup@vr_interface.c:5288`。

### 跳 3：linux 壳——模块与通道

`linux/vrouter_mod.c`：`module_init(vrouter_linux_init)@2785`；netlink 通道接线：`extern int vr_genetlink_init(void)@91`、`vr_genetlink_init()@2442`（第 6 课已核：全形态统一 genetlink，零 ioctl）。

### 跳 4：dpdk 壳——自研 uvhost

`dpdk/vr_uvhost.h:2-3` 原文："header for **user-space vhost server** that peers with the vhost client inside qemu (version 2.1 and later)"。即：DPDK 形态不用现成 vhost 库，**自研了一套 vhost server（uvhost 家族 8 文件）**对接 qemu，另有 representor（SR-IOV）、tapdev、ringdev、GRO/GSO/offloads、lcore/PMD 管理——"dpdk 形态本身就是一个小型数据面发行版"。

### 跳 5：uvrouter 壳——host 抽象上的应用

`uvrouter/uvrouter.c` 头注释："uvrouter.c -- vrouter application"，依赖 `host/vr_host*.h`——即**把 dp-core 跑在"host 抽象层"（`host/` 目录：packet/interface/message/ulinux 等）之上**的用户态形态：不碰内核、不碰 DPDK，用作开发/测试/旁路。这就是为什么要有 `host/` 这一层——**第三种宿主**。

## ③ 设计推导（问题树）

**Q1：为什么要"一核多壳"？**
dp-core 承载**语义**（流表、VRF、隧道、转发决策），宿主壳只处理**机制**（怎么收包、怎么登记接口、怎么和 agent 说话）。收益：四形态的转发语义**永远一致**（同一个 bug 一份修）；代价：C 语言里的抽象税——dp-core 里到处是 `vr_packet`/`vr_interface` 这类"薄抽象"，复杂特性要反复 cross-platform。对照我们：vpcko（内核，C）与 DPDK 通道（用户态 PAL 进程）是**两套实现**——语义一致性靠"两边都对"保证，属于另一种工程哲学（解耦 vs 复用）。

**Q2：为什么选 flow-based（有状态）而不是 route-based（无状态）？**
TF 的目标是把 **NAT / 安全组 / 镜像 / LB 全塞进转发路径**——这些天然需要"记住这条流的上下文"，所以状态必须落在数据面。代价：流表内存与规模（千万流）、首包慢路径延迟、**流表逐出与失效管理**（`vr_flow_evict_flow@423` / `vr_flow_mark_evict@761`——"evict"是代码原词；`fe_gen_id` 是失效协议的一部分）。我们的模型：**无状态 LPM 转发**（GRE + 路由表），状态（ACL/防重放）在别处解决——代价是"进不了流水线的功能要多一跳"。**同一张网络，两种"状态放哪"的答案。**

**Q3：为什么每条流要记反向索引 `fe_rflow`？**
回包必须走对称路径（同一 VRF/隧道/镜像策略），且**反向流不能自己查表推**（ECMP 场景下正反推导可能不一致）——所以装表时成对登记。顺带：统计成对（会话级视角）、NAT 回程翻译都需要它。工程细节见 `vr_flow_get_rflow_src_info@307`（"若反向流是 ECMP，靠它选回程"）。

**Q4：为什么 DPDK 形态要自研 uvhost？**
性能路径上 vhost 是 VM 流量的必经口：把 vhost 从 qemu 侧接管到 vrouter 进程内（共享内存环形队列直连 PMD），省掉内核/协议栈绕行。自研（而不是引库）是为了把 vhost 的 ring 直接接进自己的转发循环。对照我们：DPDK 通道是 vpc_agent ↔ PAL 进程（TCP+protobuf）——**我们走到了"进程通道"，TF 走到了"进程内 uvhost"**；两者对 VM 接入的位置不同（我们在 vbr/veth 层分流）。

**Q5：四形态里谁最值得学？**
按"和我们的差"：① **flow entry 的世界观**（Q2——"状态放哪"的整个取舍谱系）；② **uvhost/ring 的接线**（DPDK 形态的性能接口，对照 PAL）；③ host 抽象层（工程美学，价值排后）。内核壳（linux/）反而最不新——我们的 vpcko 同层同语言。

## ④ 对照卡

| 维度 | tf-vrouter | 我们（vpcko + DPDK 通道） | 差异根因 |
|---|---|---|---|
| 代码组织 | **一核多壳**（dp-core + host 抽象 × 4 宿主） | **两套实现**（内核模块 + 用户态 PAL） | 复用 vs 解耦 |
| 转发模型 | **flow-based**：首包 HOLD→agent→装表；后续直转 | **route-based**：无状态 LPM + 集中策略 | NAT/镜像进流水线 vs 功能分层 |
| 流状态 | flow entry（双向/统计/ECMP/NAT/TCP 位） | 无流表（ACL/防重放在别处） | "状态放哪"（Q2） |
| 用户态通道 | 全形态 **genetlink**（+DPDK 内在环） | 内核=genetlink；DPDK=TCP+protobuf（PAL） | 同源不同代 |
| VM 接入 | **uvhost**（自研 vhost server，ring 直连） | vbr/veth（母机侧）；DPDK 走 PAL | 性能接口位置不同 |
| 首包处理 | HOLD + hold 队列 + 重放 | 无（转发不依赖 agent） | 慢路径归谁承担 |

## ⑤ 验收题

1. "一核多壳"与"两套实现"，分别在什么条件下更优？（提示：语义漂移的代价 vs 抽象税的代价）
2. flow-based 转发的三个代价（内存/首包/老化）里，哪个在千万级流表时最先爆？`fe_gen_id` 在缓解哪个？
3. 为什么反向流要显式登记而不能"正着推"？ECMP 场景下推错的后果是什么？
4. uvhost 把什么"搬进了 vrouter 进程"？这与我们把 DPDK 通道做成独立 PAL 进程相比，各自把复杂度放在了哪？
5. 用一句话回答：TF 的"状态在流里" vs 我们的"状态在流外"——如果是你设计今天的新数据面，会怎么选？为什么？

---

*本课行号清单（供追问对质）：`include/vr_flow.h` 动作@12-15、key 结构@181（+字段宏@190-197）、hold 限流注释@221 起（pkt0@225）、flow entry@402+；`dp-core/vr_flow.c` rflow 源信息@307、get_entry@369、evict@423 / mark_evict@744/761、reset_evict@484、get_free_entry@567；`dp-core/vr_interface.c` fat flow 查找@5288；`linux/vrouter_mod.c` genetlink extern@91、init@2442、module_init@2785；`dpdk/vr_uvhost.h` 自述@2-3；目录学：linux/（6 .c + SConscript）、dpdk/（43 项）、host/、uvrouter/uvrouter.c、freebsd/；行数：vr_flow.c 3395 / vr_interface.c 5501 / vr_datapath.c 1023 / vrouter_mod.c 2789。*

*审查记录（首轮深审）：修正 4 处——① 计数对账：`linux/` 实为 7 项（6 .c + SConscript，原写"6 个文件"）、`dpdk/` 实为 **43 项**（原"约 35 项"又是目测）；② **机制措辞纠错**：首包上报通道原写"经 genetlink"不精——`vr_flow.h:221` 注释原文点名 **pkt0**（"pkt0 is not overrun"），且揭示"首包慢路径**秒级** + hold 持有量限流"的关键设计，已引注原文入课；③ `fe_key` 断言修正——key 含 **nh_id**（结构@181 + 宏@190-197），"5 元组 + VRF"不准确（VRF 是流命中的上下文——查表在 per-vif 表上发生，不在 key 里）；④ 顺带补 key 字段宏行号。*

*审查记录（二次深审）：① R1 修正自查通过（43 项 / 6 .c / pkt0 引文 / fe_key 注释 / 残字已清）；② **术语对齐**——"流老化"改用代码原词 **evict（逐出）**，并补锚点（`vr_flow_evict_flow@423`、`__vr_flow_mark_evict@744`、`vr_flow_mark_evict@761`、`vr_flow_reset_evict@484`）；③ pkt0 的**构造代码**未在 dp-core 中另行定位（grep 仅见调试串）——课文"文本证据"措辞准确，不升级为代码断言。*
