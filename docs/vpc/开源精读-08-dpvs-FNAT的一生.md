# 开源精读 08 · DPVS：FNAT 的一生——"DPDK-LVS"的四堵墙与三块补丁

> 课次：开源精读路线第 8 课（数据面·第二课）
> 对象：`refs/dpvs`（commit `4582d20`，iQIYI 开源）
> 对照：我们的 LBNAT / CLB——**边界声明**：本课程材料里 LBNAT 只有"一代网关角色"级描述（配置类型/场景卡），**本课不做对下表精读**，只做"DPVS 自身"的深潜 + 问题留白。
> 纪律：路径与行号来自实仓核验。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **VIP / RS** | 虚拟服务地址 / 真实服务器（后端） |
| **FNAT** | Full NAT：目的（VIP→RS）与源（Client→DPVS local）**双向全改**；另有 DR（只改 MAC）/SNAT / DNAT 反代模式 |
| **session table** | 会话表：五元组级的转发状态（正向 + 反向两条） |
| **TOA / UOA / ProxyProtocol** | FNAT 下"client 地址保真"的三套补丁 |
| **RSS / RETA** | 网卡多队列散列与其重定向表——把"同一条流固定到同一个核" |
| **share-nothing** | 关键数据 per-CPU、无锁——DPVS 的性能哲学（README 原词） |

## ① 它干了什么——一个 TCP 连接的一生

Client 连 `VIP:port`：

1. 网卡按 **RSS** 把这包散到某个队列 → 固定绑定的那个 **lcore**（CPU 核）收包（DPDK 轮询，无中断）；
2. 查**本核的** session table（`caddr/vaddr/laddr/daddr` 四地址 + 端口）——未命中；
3. 调度器选 RS（如 **MH = Maglev Hash**）＋ `sa_pool`（`src/sa_pool.c:311 sa_pool_hash`——按目的地址哈希挑一个地址）挑一个 **DPVS local 地址**当新源；
4. **装会话**——**一个 `dp_vs_conn` 结构 + 两个方向的 tuple 条目**（`INBOUND=0 / OUTBOUND=1`，`conn.h:33-35`；INBOUND 记 caddr/vaddr、OUTBOUND 记 laddr/daddr，访问器 `tuplehash_in/out@179-180`）→ 按连接上挂的 xmit 函数（**`dp_vs_xmit_fnat`**）转发：**目的改 RS、源改 local**——client 地址在网络层丢了；
5. RS 回包 → 命中反向会话 → 改回 VIP/Client → 回客户端；
6. 若 RS 需要知道真实 client IP：**TOA/UOA/ProxyProtocol** 三选一补上（见跳 7）。

## ② 链路走查（file:line 级）

### 跳 1：自述——"DPDK-LVS"的四堵墙

`README.md` 开头（原文）：

> `DPVS` is a high performance **Layer-4 load balancer** based on DPDK. It derives from Linux Virtual Server [LVS] and its modification alibaba/LVS.
> Notes: The name `DPVS` comes from "**DPDK-LVS**".

技术清单（同一文件）：Kernel by-pass / **"Share-nothing, per-CPU for key data (lockless)"** / RX Steering and CPU affinity / Batching TX/RX / Zero Copy / Polling instead of interrupt。

### 跳 2：会话表——四地址全量记账

`include/ipvs/conn.h` 的 `struct dp_vs_conn`：

```c
struct dp_vs_conn {
    ...
    union inet_addr   caddr;   /* Client address */
    union inet_addr   vaddr;   /* Virtual address */
    union inet_addr   laddr;   /* director Local address */
    union inet_addr   daddr;   /* Destination (RS) address */
    uint16_t                cport;
    uint16_t                vport;
    uint16_t                lport;
    uint16_t                dport;
    ...
    struct conn_tuple_hash  tuplehash[DPVS_CONN_DIR_MAX];  /* 双向 tuple */
    struct dpvs_timer       timer;
    struct timeval          timeout;
    lcoreid_t               lcore;      /* 属于哪个核 */
    struct dp_vs_dest       *dest;      /* real server */
    ...
};
```

**FNAT 的"全改"落地就是这四地址全程记账**：一条连接查表命中，两个方向都能还原。超时按 TCP 状态逐状态配置（`ip_vs_proto_tcp.c:45` 的 `tcp_timeouts[]`；`:1489` 起的默认表：S_NONE **2** / SYN_SENT 3 / SYN_RECV 30 / **ESTABLISHED 90** / FIN_WAIT·TIME_WAIT 7（秒）——**连接态的耐心最长，中间态的耐心最短**）。

### 跳 3：FNAT 不是一个模式，是一族函数

连接建立时按转发模式挂上 xmit 函数指针（`ip_vs_conn.c:377-378`）：

```c
        conn->packet_xmit = dp_vs_xmit_fnat;
        conn->packet_out_xmit = dp_vs_out_xmit_fnat;
```

每个 L4 协议都要各自处理"改了地址之后"的脏活——SCTP/ICMP 都有自己的 fnat handler 家族（如 `ip_vs_proto_icmp.c:64` 注释："For ICMPv6 messages in SNAT/DNAT/**FULLNAT**, checksum should be recalculate"）。**"支持 FNAT"在代码里等于"N 个协议的校验和/状态各自重写一遍"。**

### 跳 4：share-nothing——per-lcore 的表与 RSS 的绑定

`ip_vs_conn.c:1005` 注释（原文）："dp_vs_conn_tab[] for current lcore will be looked up"——**每个核只看自己的会话表**；配套 `netif.c` 的 RSS 配置（`:75` RETA 表尺寸、`:3739` 打开 IP/TCP/UDP 的 RSS 哈希）把"同一条流"钉到同一个核：**表无锁、timer 无锁、统计无锁**。

### 跳 5：控制面——sockopt 而不是 netlink

`src/ctrl.c` 自述："sockopt process msg"（`:1153` 起）；`dpvs_sockopts_name[]@45` + `sockopts_get()` 分发器 @1166——走 **UNIX socket + 自研 sockopt 协议**（与 LVS 的 ipvsadm sockopt 同血脉）。四个入口（README）：`dpip` / `ipvsadm` / **keepalived 配置** / `dpvs-agent` RESTful API；`tools/healthcheck` 管 RS 健康检查。

### 跳 6：Maglev 的注释自带论文

`src/ipvs/ip_vs_mh.c` 头注释（原文节选）：

> The mh algorithm is to assign a preference list of all the lookup table positions to each destination and populate the table with the most-preferred position of destinations. … The algorithm is detailed in: [3.4 Consistent Hasing] https://www.usenix.org/.../nsdi16-paper-eisenbud.pdf

——**预置偏好列表 + 查表选目的地**；直接引 Google Maglev 的 NSDI16 论文。调度器家族同目录齐活：RR/WRR/WLC/FO(`ip_vs_fo.c`)/Conhash(`libconhash`)/MH。

### 跳 7：FNAT 的代价与三块补丁（doc 实锤）

`doc/client-address-conservation-in-fullnat.md`（首段原文）：

> The original client addresses are substituted with DPVS's local addresses in Fullnat forwarding mode so that auxiliary means is required to pass them to realservers. Three solutions … **TOA**, **UOA**, and **Proxy Protocol** …

- **TOA**：client 地址塞进 TCP option（opcode 254），RS 侧装 `toa.ko` 解析——**代价给内核模块**；
- **UOA**：UDP 版（IP option 模式 / 私有 L4 协议 248）——*"not all kinds of network switches or routers support … Be aware of your network restrictions"*——**代价给网络设施**；
- **Proxy Protocol**：haproxy 草案协议（v1 文本仅 TCP，v2 二进制支持 TCP/UDP），RS 应用必须连接建立后立刻解析——**代价给应用**，但"becoming a **defato** standard" [sic，原文拼写]。

## ③ 设计推导（问题树）

**Q1：为什么云上 FNAT 成为默认答案？**
DR 要求 RS 与 LB **同二层**（改 MAC 直达），SNAT 要求把 VIP 配到 RS 的回环并小心翼翼地处理 ARP/ICMP——两者都把 RS 的**网络位置**绑死在 LB 上。云环境 RS 可能在任何子网/容器里 → **只有 FNAT 把拓扑完全解耦**（RS 只需自己能路由到 DPVS local 段）。代价就是跳 7：client 地址在 L3/L4 丢了，于是"保真"成了一门专门的手艺（TOA/UOA/PP）。

**Q2：share-nothing per-CPU 的三条代价？**
① 表容量 × 核数（内存）；② 核间不均——一个 10G 大流只能压一个核（RSS 只保证"同流同核"，不保证"均匀"）；③ 无锁的边界条件（rs 上下线、表 dump 要跨核协调——`__lcore_conn_table_dump@1445` 这类函数就是为此存在）。**RSS 选择是"性能换弹性"的交换**——和 50 节点以上就要 Typha 是同一类工程判断。

**Q3：Maglev 为什么比"取模"稳？**
取模：RS 数变化 → **几乎所有 key 重映射**（缓存全崩）；Maglev：给每个 RS 一条"偏好位置序列"，表里放"最优先且没被占"的位置——**单个 RS 变化只扰动它自己的份额**。查表 O(1)、分布均匀、扰动小——Google 用它扛 FB 级 LB，DPVS 抄得理直气壮（注释直接给了论文链接）。

**Q4：为什么控制面不用 netlink？**
控制面速率（每秒几条配置）与数据面速率（每秒千万包）差 6 个数量级——**只要通道够用、语义顺手的就行**：UNIX socket + sockopt 与 LVS/ipvsadm 的血统一致（连 `ipvsadm` 工具名都保留了），keepalived 生态直接复用。**不为"先进"付迁移税。**

**Q5："DPDK-LVS"这个名字说明了什么？**
**继承**：转发模型（FNAT/DR/SNAT 模式、调度器家族、会话表、sockopt 控制面）全部来自 LVS 20 年的沉淀；**重写**：数据面从内核搬到用户态 DPDK（bypass/轮询/零拷贝/批处理），换来 10 倍级包处理能力与更可控的部署（不绑内核版本）。这就是 2016-2019 年"DPDK 重写路由转发"浪潮的标准样本——**先把经典设计的语义吃透，再用新技术把性能墙撞开**。

## ④ 对照卡

| 维度 | DPVS | LVS（内核版） | 我们 LBNAT/CLB（**本课留白**） |
|---|---|---|---|
| 数据面位置 | 用户态 DPDK（bypass） | 内核 netfilter 钩子 | ？——材料未覆盖（待勘） |
| 转发模式 | FNAT/DR/Tunnel/DNAT 反代 + NAT64 + SNAT | 同类 | LBNAT = "LB NAT 网关"角色（材料级） |
| 客户端地址 | FNAT 需 TOA/UOA/PP 补 | 同 | ？——值得对着问：我们靠什么保真 |
| 调度器 | RR/WRR/WLC/FO/Conhash/**MH(Maglev)** | 同家族 | CLB 的一致性哈希策略（材料级） |
| 控制面 | dpip/ipvsadm/keepalived/agent | ipvsadm | 云 API（sdn_oss LBNAT 配置族） |
| 无锁模型 | share-nothing per-lcore | per-CPU 变体 | ？ |

> **本课留下的三个问题**（对照 LBNAT/CLB 时逐个去材料里找答案）：① 我们的 LB 用哪种转发模式、client 地址怎么保真？② 会话表在多实例间怎么共享/隔离？③ 健康检查与配置收敛走什么通道？

## ⑤ 验收题

1. 云上为什么 FNAT 是默认？DR/SNAT 各自的前提（二层同网/回环配 VIP）在云里为什么不成立？
2. TOA/UOA/PP 三块补丁分别把代价推给谁（内核模块/网络设施/应用）？各自"最坏情况"是什么？
3. share-nothing 的三个代价里，哪个在超大流量下最先暴露？`__lcore_conn_table_dump` 这类跨核函数在解决什么？
4. 用两句话解释 Maglev 的"preference list"为什么能把 RS 变更的扰动压小。
5. "DPDK-LVS"这个名字给"用新技术重写老系统"提供了一个什么模板？（提示：先学语义，再换引擎）

---

*本课行号清单（供追问对质）：`README.md`（Introduction 与技术清单）；`include/ipvs/conn.h:85` `dp_vs_conn`（四地址 + tuplehash）；`include/ipvs/conn.h` 方向枚举@33-35、tuplehash 访问器@179-180；`src/ipvs/ip_vs_conn.c` xmit 指针@377-378、per-lcore 注释@1005、表 dump@1445/1538；`src/sa_pool.c` sa_pool_hash@311；`src/ipvs/ip_vs_proto_tcp.c` tcp_timeouts@45、默认表@1489 起（S_NONE 2 / SYN_SENT 3 / SYN_RECV 30 / ESTABLISHED 90 / FIN_WAIT·TIME_WAIT 7）；`src/ipvs/ip_vs_proto_icmp.c` FULLNAT 注释@64；`src/netif.c` RETA@75/3154、rss_value@3739；`src/ctrl.c` sockopts_name@45、sockopt msg@1153/1166；`src/ipvs/ip_vs_mh.c` 头注释（NSDI16 引用，"Hasing"为原文拼写）；`doc/client-address-conservation-in-fullnat.md`（TOA/UOA/PP）；`tools/`（dpip/ipvsadm/keepalived/healthcheck/dpvs-agent）。*

*审查记录（首轮深审）：修正 3 处引文/语义精度——① conn 结构引码里 `cport…dport` 被压缩成"伪单行"（`uint16_t cport; vport; lport; dport;`），已还原为原文四行；② `S_NONE` 称"空闲态"不精——它是**兜底态**，且默认表还有 ESTABLISHED 90s / SYN_RECV 30s 等值，一并引全（附"连接态耐心最长"观察）；③ 原文 "defato"（拼写错误）被我静默改对为 "de-facto"——已复原加 [sic]（第 6 课教训的应用；同段 "Hasing" 原文拼写本轮确认未被改动）。另补 conn.h 结构行号 @85。*

*审查记录（二次深审）：① R1 修正自查通过（cport 四行 / S_NONE 表 / defato [sic] / conn.h@85）；② **机制精化**——"装正反两条会话"改为更准确的"**一个 `dp_vs_conn` + 两个方向 tuple 条目**"（INBOUND/OUTBOUND 枚举@33-35、访问器@179-180 实锤；对账函数里 INBOUND 用 caddr/vaddr、OUTBOUND 用 laddr/daddr——`ip_vs_conn.c:423-426`）；③ 补 `sa_pool_hash@311`（FNAT 选源的哈希函数）入正文与清单；④ **编辑事故**：本轮落盘曾把首轮审查记录误覆盖（编辑串范围之误），已恢复——**审查产物的编辑风险同样要自审**（本课新增教训）。*
