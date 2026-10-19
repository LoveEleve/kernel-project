# 开源精读 11 · OVS 本体：SB 之下的最后一级——"两段流表"的祖师爷

> 课次：第二批第 2 课（补齐第 5 课"ovn-controller 的 OpenFlow 去了哪"）
> 对象：`refs/ovs`（commit `c78f687`）
> 对照：第 5 课 OVN（它的上游）、第 9 课 Katran（"一步到位 vs 两段"的反面参照）、我们 vpcko（route-based 的另一条路）
> 纪律：行号实仓核验；**仓库演化实证随课标注**（这是第三条代差样本——前两条：/dev/vrouter ioctl→genetlink、`head`截断）。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **OpenFlow** | ovs-vswitchd 的"慢路径描述语言"（可通配/带优先级/可堆叠） |
| **ofproto** | OVS 用户态里的 OpenFlow 层（7525 行的 `ofproto-dpif.c` 是核心） |
| **dpif** | datapath 接口层：一套语义，多个执行后端（见跳 3） |
| **xlate（翻译）** | 把"报文 + OpenFlow 世界"翻译成"具体动作序列"的编译器 |
| **upcall** | 快路径没见过的包，"上送"到用户态请翻译 |
| **microflow** | 翻译产物装进快路径的"精确流"——后续同类包直转 |
| **OOT** | out-of-tree（树外内核模块）——OVS 的历史包袱名词 |

## ① 它干了什么——第 5 课那条 lflow 的最后一跳

ovn-controller 把 SB 编译成 OpenFlow 后发给 ovs-vswitchd，此后：

1. OpenFlow 流装进 **ofproto**——这是"**慢路径**"的描述：可以通配、可以堆叠、（相对）不介意数量；
2. 首包到达，datapath 里查无此流 → **upcall 上送**用户态；
3. 用户态用 **`xlate_actions`**（`ofproto/ofproto-dpif-xlate.c:8308`）把"报文 + OpenFlow 世界"**翻译成具体动作序列**（含 tunnel/CT/端口动作）；
4. 动作序列落成 **microflow**（**快路径**）→ 同类包此后在 datapath 直转，不再上送。

**一句话**：**"慢路径描述所有可能性，快路径只装见过的样子"**——这是 vswitch/LB 一族的祖先设计；第 9 课 Katran 是"跳过慢路径、只做推断式装配"的激进现代化。

## ② 链路走查（file:line 级）

### 跳 1：仓库地图 + 一条演化实证

- **本仓（用户态）**：`ofproto/`（OpenFlow 层）、`vswitchd/`（守护与配置：`bridge.c` 5395 行）、`lib/`（**dpif 家族**与公共设施）、`ovsdb/`、`ipsec/`、`python/`；
- **`datapath/` 不在本仓**（`ls` 实证）——内核 datapath 归 upstream Linux 内核（NEWS 原文佐证：主线已移除 "**old OOT kernel module**" 的旧隧道实现——连"树外模块"这个词都进了历史）。**第三条"认知 vs 当代代码"的代差样本**：你以为 OVS 仓库自带内核模块——那是老版本的事。

### 跳 2：两段流表与 upcall——"净核之间那口井"

`lib/dpif.c:1472`：`dpif_upcall_type_to_string`；`:1501-1514` 注释原文：

> multiple upcall handlers can read upcalls simultaneously … each port can have **multiple Netlink sockets, one per upcall handler**.

——upcall 不是应急通道，是**一等公民**：可以多 handler 并排"打水"（对照：Katran 把慢路径压缩到"首包 HOLD + agent 决定"的极简版——同为"首包从事慢路径"，OVS 造了一整套泵站）。

### 跳 3：三个 datapath 后端——"一份语义，三套执行"

`lib/` 下的 dpif 家族就是 OVS 的"多壳"证据：

- `dpif-netlink.c`——**内核 datapath**（netlink 通道，对接 upstream 内核模块）；
- `dpif-netdev.c`（**9394 行**）+ `dpif-netdev-*` 一族（dpcls 分类器、pmd 线程、perf）——**全用户态 datapath**（DPDK/纯 userspace）；
- `dpif-offload*.c`（tc / DPDK offload）——**硬件卸载**：把流下沉到网卡执行。

与 tf-vrouter"一核多壳"（第 7 课）异曲同工：**语义同源（ofproto），执行面三选**。

### 跳 4：体量——"vswitch 是一个小型操作系统"

`ofproto/ofproto-dpif.c` **7525 行**：`struct dpif_backer`（后端聚合）、**CT zone 配置**（连 conntrack 的 zone/超时/限额管理都在 OVS 里：`ct_zone_config_init`@223 一带）——第 5 课"两跳编译"的终点是这么一个体量的用户态系统。

## ③ 设计推导（问题树）

**Q1：为什么"两段流表"能活二十年？**
慢路径（OpenFlow）负责"**描述一切可能**"——新协议/新动作只改翻译器；快路径（microflow）负责"**只装见过的**"——热流零翻译。代价：upcall 尖峰（新流风暴时用户态被打爆）、两段一致性（内核/用户态同步）、两级表管理。**Katran（第 9 课）是光谱另一端**：砍掉慢路径，换极简与极致 PPS，代价是能力窄（DSR-only）。两个设计并存，因为问题不同。

**Q2：upcall 的工程化程度说明什么？**
`dpif.c` 注释：upcall 可多 handler 并排、每端口多 Netlink socket——**"首包慢"被当作常态负载设计**。对照：Katran 用 hold 队列限流面对它（"served slowly (of the order of seconds)"是明说预期）；我们一代 agent 是"上报-决策-装表"。三个系统三种态度：**泵站（OVS）/ 限流盒（Katran）/ 一问一装（我们）**——选哪种看"新流速率 vs 翻译成本"。

**Q3：三个后端（netlink/netdev/offload）的谱系？**
与 tf-vrouter 的"一核多壳"（第 7 课）、DPVS 的"内核 vs DPDK"（第 8 课）同谱：**控制语义共享，数据面按性能/环境分档**。offload 档是新颖处——**表项化的模型连硬件都能容纳**（表项即流，可下沉到网卡 ASIC）：这正是"两段流表"长寿的又一证据。

**Q4：我们系统的位置？**
我们**不用 OVS**：kube-ovn 用它（那是它的底座）；一代网关/母机走 bridge/自研；vpcko 走 route-based（无流表、无 upcall）。取舍一目了然：**"要不要两段流表"= "通配描述复杂云网络 + 热流快转" vs "行为可推演 + 无首包特殊路径"**——我们要后者（可推演性对多租户平台是刚性）。

**Q5：仓库演化方法论（第三条代差样本）？**
ioctl→genetlink（第 6 课）、`head` 截断（第 6 课修正自伤）、现在：**datapath/ 移出仓库**。规律固化：**"老材料描述的旧形态"与"当代代码"的差异是常态**——读任何系统前先问：这份印象是哪个版本的？

## ④ 对照卡

| 维度 | OVS | Katran（第 9 课） | 我们 vpcko | tf-vrouter（第 7 课） |
|---|---|---|---|---|
| 慢路径 | **OpenFlow + xlate 完整翻译** | HOLD→agent（极简） | 无（无流表） | 首包问 agent |
| 快路径 | microflow（netlink/netdev/offload **三后端**） | ch ring 直转 | LPM 路由 | flow entry |
| 首包态度 | **泵站**（多 handler 常态） | 限流盒（秒级明说） | 无首包特殊路径 | HOLD + 重放 |
| 状态 | CT zone（连 conntrack 都在） | per-CPU LRU | 无 | flow 全字段 |
| 一句话 | **慢描述一切，快只装见过的** | 一步到位 | 可推演优先 | 状态在流里 |

## ⑤ 验收题

1. "慢路径描述所有可能性，快路径只装见过的样子"——两边各付什么代价？Katran 砍掉慢路径换来了什么、失去了什么？
2. upcall 的多 handler/多 socket 说明 OVS 把它当什么级别的负载？对照 Katran 的 hold 限流与我们的"上报-决策"，三种态度的适用条件各是什么？
3. dpif 家族三个后端分别是什么？为什么"表项化"的模型能容纳硬件卸载？
4. 我们不用 OVS 的原因是什么？若给"纯 overlay 云网络"选 vswitch，你会怎么权衡"可推演性 vs 灵活性"？
5. 第三条代差样本（datapath/ 消失）给你的"读老材料"方法补了什么纪律？

---

*本课行号清单（供追问对质）：`lib/dpif.c` upcall 类型@1472、upcall 多 handler 注释@1501-1514；`ofproto/ofproto-dpif-xlate.c` xlate_actions@8308；`ofproto/ofproto-dpif.c`（7525 行）ct_zone_config_init@223 一带；`lib/dpif-netdev.c`（9394 行）+ dpif-netdev-* 一族；`lib/dpif-netlink.c`；`lib/dpif-offload*.c`（tc/DPDK/dummy）；`vswitchd/bridge.c`（5395 行）；`NEWS`（OOT 旧隧道移除）；目录学：datapath/ 不在本仓（ls + find 双实证）。*

*审查记录（首轮深审）：**0 修正——首批以来的第一次全绿**。复核项：dpif.c upcall 类型@1472 与多 handler 注释@1501-1514（逐字对）、xlate_actions@8308、三后端文件名（netlink/netdev/offload*）、四体量（7525/5395/9394/2128）、ct_zone 三函数@223/225/226、NEWS "old OOT kernel module" 原文、datapath/ 全仓 find 零命中——全部命中。（0 修正不等于没审：本课引码密度低、且大部分锚点来自"文件名级 + 计数级"证据，天然不易错。）*

*审查记录（二次深审）：0 修正课无"修正自身"可审——抽查三锚（datapath 段、xlate@8308、NEWS OOT 引文）在位；R2 对全绿课的意义是**确认没有"欠修"**（本课亦无尾差风险）。*
