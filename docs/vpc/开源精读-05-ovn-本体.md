# 开源精读 05 · OVN 本体：northd / ovn-controller——"kube-ovn 之下"的两跳编译

> 课次：开源精读路线第 5 课（链①②的收尾：补上第 1 课"kube-ovn 只写 NB"的下半场）
> 对象：`refs/ovn`（commit `8559856`）
> 对照：我们的一代链（nfv-proxy → 分区 ZK → vpc_agent）；第 1 课的"撒手"结论在这里兑现
> 纪律：路径与行号来自实仓核验。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **NB / SB** | NB = 期望（逻辑网络定义）；SB = 运行时（逻辑转发图 + 端口绑定 + 机箱状态） |
| **northd** | 中心进程（全集群 1 个实例组）：把 NB 翻译成 SB |
| **ovn-controller** | 每节点进程：watch SB，**只挑与自己相关的**，编译成本机 OpenFlow 下给 OVS |
| **lflow（logical flow）** | SB 里的逻辑流："匹配条件（含 inport/datapath）→ 动作"——数据面行为的全局描述 |
| **Chassis / Port_Binding** | SB 里"机器"与"端口落在哪台机器"的事实 |
| **I-P engine** | Incremental Processing Engine：OVN 两个进程共用的"增量计算图"框架 |

## ① 它干了什么——第 1 课那条 LSP 的下半场

第 1 课说到：kube-ovn controller 在 NB 里建出 LSP/LRP 后**撒手了**。撒手之后，一条子网真正"通"起来还差两跳编译：

1. **第一跳（中心，northd）**：watch NB 变化 → 把逻辑网络翻译成 SB 的行——`Logical_Flow`（"谁从哪进来、匹配什么、干什么"的全局逻辑流）+ `Port_Binding`（哪个端口绑在哪台 chassis）。**一份全局视图，全集群共用。**
2. **第二跳（每节点，ovn-controller）**：watch SB → 现场挑出"与我相关"的部分（我 chassis 上的 Port_Binding；全局 lflow）→ 编译成本机 OpenFlow → `ofctrl_put` 下给 OVS。**每台机器只算自己的那份。**

两跳的真实体量（本仓行数）：`northd.c` **22613 行**、`ovn-controller.c` **9320**、`binding.c` **4032**、`lflow.c` **3399**、`ofctrl.c` **3216**——"两跳各自都是一个中型系统"。

## ② 链路走查（file:line 级）

### 跳 1：共用框架——I-P engine 的自我说明

`lib/inc-proc-eng.h:20` 起（原文）：

```c
/* The Incremental Processing Engine is a framework for incrementally
 * processing changes from different inputs. A example use case is
 * ovn-controller.  To compute desired states (e.g. openflow rules) based on
 * many inputs (e.g.  south-bound DB tables, local OVSDB interfaces, etc.), it
 * is straightforward to recompute everything when there is any change in any
 * inputs, but it is inefficient when the size of the input data becomes large.
 * Instead, tracking the changes and update the desired states based on what's
 * changed is more efficient and scalable. ...
```

回退语义也写在接口注释里（`:322-325`）：

```c
/* Add an input (dependency) for <node>, with corresponding change_handler,
 * which can be NULL. If the change_handler is NULL, the engine will not
 * be able to process the change incrementally, and will fall back to call
 * the run method to recompute. */
```

**"增量处理不了就回退全量重算"——这个模式本课程第四次出现了**（见 ③Q2 的横向表）。

### 跳 2：northd——依赖图上的翻译

`northd/inc-proc-northd.c`：引擎节点不是散装函数，而是**显式依赖图**（节选）：

```
engine_add_input(&en_global_config, &en_nb_nb_global, ...)        // :209
engine_add_input(&en_global_config, &en_nb_logical_switch, ...)   // :211
engine_add_input(&en_global_config, &en_sb_chassis, ...)          // :215
...
engine_add_input(&en_northd_output, &en_acl_id, ...)              // :507
engine_add_input(&en_northd_output, &en_sync_from_sb, NULL)       // :509 —— NULL=该输入无增量处理器，回退全量
```

翻译主体在 `northd/northd.c:20961`（调用点 `:21224`），签名本身就是"翻译"的定义——输入全是 NB 侧模型、输出是逻辑流表：

```c
build_lswitch_and_lrouter_flows(
    const struct ovn_datapaths *ls_datapaths,
    const struct ovn_datapaths *lr_datapaths,
    struct lflow_table *lflows,
    ...
```

### 跳 3：SB 的数据模型——"全局逻辑 + 事实"三张表

`ovn-sb.ovsschema`（本仓根目录）：`"Chassis"@28`（机器）、`"Logical_Flow"@97`（全局逻辑流）、`"Port_Binding"@218`（端口→机器的绑定，refTable 互指 Chassis@255）。**northd 产出、ovn-controller 消费的全部协议面，就是这三类行。**

### 跳 4：ovn-controller——过滤 + 编译 + 下发

主循环（`controller/ovn-controller.c`）：`while (!exit_args.exiting)@8188` 里调用 `engine_run(recompute_allowed)@8455`——其中 `recompute_allowed = (ovnsb_idl_txn && !ofctrl_has_backlog())`：**全量重算也要有预算**，ofctrl 有积压时不允许强制重算（代码注释吐槽：OVSDB change tracker 跨迭代保不住变更，所以要先尽量增量消化）。三个关键引擎节点：

- **绑定**（`controller/binding.c`）：`binding_run@2253`——把 SB 的 Port_Binding 与**本机 OVS 接口事实**对上（谁在本机、谁被 claim）——注意函数第一行先校验 `chassis_rec`：**本机没注册成 chassis，整个绑定环节直接闲置**（"谁在本机"之前，先确认"我是谁"）；增量变更入口 `binding_handle_port_binding_changes@3296`；
- **逻辑流处理**（`controller/lflow.c`）：`consider_lflow_for_added_as_ips@619`（外层负责 dp_group 与单机的分派；内层 `__`@428 处理单个 datapath）——看着不起眼的函数名，暴露了增量引擎最难的题型：**lflow 引用了地址集（AS），当 AS 内容变化时，引用它的 lflow 要被"重新考虑"**（引用关系的变更传播，不能只盯被改的那一行）。而紧挨着的 `as_update_can_be_handled@656`（注释@653："Check if an address set update can be handled **without reprocessing the lflow**"）是反向的功夫——**能便宜处理的就不重算**：变更传播 + 快速路径判断，正是增量引擎的两大真功夫；
- **下发**（`controller/ofctrl.c`）：`ofctrl_put(struct ovn_desired_flow_table *lflow_table, ...)@2756`——把"期望流表"变成 OpenFlow 消息发给 OVS。注意参数名：**desired_flow_table**——又是"期望态 → 实态"的那个词。

### 跳 5：与 kube-ovn 的边界（第 1 课结论兑现）

kube-ovn **只写 NB**；从 NB 往后（northd、SB、ovn-controller、OpenFlow）全部是 OVN 原生实现。所以第 1 课那句"kube-ovn 没有参与这一跳"到这里完成了闭环：它参与的是"生成意图"，不参与"两跳编译"。kube-ovn daemon 在节点侧只做 OVN 之外的补充（gateway、FDB 之类——第 1 课 `runSubnetWorker`→`requestFdbSync` 的伏笔）。

## ③ 设计推导（问题树）

**Q1：为什么必须两跳，不能一跳？**
第一跳解决"**全局算一次**"：逻辑网络的全貌（跨节点连通性、ACL、路由）在中心算，1 份视图服务 N 个节点（1×N 聚合）。第二跳解决"**本地事实只有本地知道**"：每台机器的 OVS 接口、tunnel 端点、localnet 接入——中心不可能掌握 N 份实时本地状态，必须由节点把"全局逻辑 × 本地事实"现场编译（N×1 过滤）。**分工线 = "全局逻辑"中心算，"本地事实"节点算。**
反方案：若所有节点都从 NB 全量自算 → N 倍全局算力 + 每节点处理全量事件；若 northd 直接生成每节点 OpenFlow → 需要中心收集全部本地状态。两跳是这两个反方案的中间解。

**Q2："增量 + 全量兜底"为什么又双叒是它？**（四见横向表——本课程的回收时刻）

| 系统 | 增量机制 | 全量兜底 |
|---|---|---|
| calico felix（第 2 课） | calc graph delta | full resync（`int_dataplane.go:2320`） |
| etcd（第 3 课） | watch unsynced 补课 | 压缩边界（`ErrCompacted`）→ 客户端快照 |
| 我们一代（对照地图） | /BINLOG 增量 + Inspector 对账 | CKV 快照重拉 + 报告制对账 |
| OVN I-P engine（本课） | change_handler 逐节点增量 | `change_handler = NULL` → run 全量重算（`inc-proc-eng.h:322-325`） |

**四个独立系统、四种代码、同一个答案**——因为问题是同一个：*增量保延迟，全量保正确；增量路径可以快，但必须假设它可能错/漏*。这就是为什么它值得被记成"第一性答案"而不是四个实现细节。

**Q3：SB 为什么是"集中一份 + 客户端过滤"，而不是像我们一样分区？**
OVN 的选择：SB 是 **OVSDB 集群**上的一份库（Raft 实现在 OVS 仓：`refs/ovs/ovsdb/raft.c`，5350 行），**所有节点 watch 全库**，用"增量引擎 + 按需过滤"把读放大压住（每节点只算与自己相关的输出）。我们的选择：**分区 ZK 树**——把"过滤"提前到了分发结构里（按 region/zone/资源切分区，进程各盯一区）。
两种哲学的代价不同：中心式把代价压在 SB 的复制与全量 watch 上（OVN 还敢把全库推给每个节点，是因为 lflow 天然按 datapath 可切、且 I-P 引擎让"无关变更"几乎零成本）；分区式把代价压在"分区路由 + 跨区一致性"上（我们为此交了分区 ZK 运维、CKV 快照、对账三笔工程账）。**规模到千万级对象时，OVN 的中心式未必扛得住——这正是我们当年另起炉灶的原因之一。**

**Q4：lflow 的"重新考虑"难在哪？**
`consider_lflow_for_added_as_ips` 揭示的题型：**产物的正确性依赖"引用闭包"**——改一行 Port_Binding 不只是改一行；改一个地址集也不只是改一个集合，而是"所有把它写进 match 的 lflow"。增量引擎必须知道"谁引用了谁"（或保守地重算整段）。这就是 ②跳4 里那个不起眼函数名背后的工程深渊——**增量系统的真正难点从来不是"怎么算增量"，而是"怎么知道该重算什么"**。

**Q5：kube-ovn 既然撒手，为什么还要在节点上留 daemon？**
因为 OVN 管的是"逻辑网络→OpenFlow"的**通用**编译，管不了 K8s 侧的具体接线：pod veth、CNI、gateway 主机上的外部策略、FDB 维护……**通用控制面 + 场景代理**的分层，和我们"OVN 在场时的理想形态"（若一代有 OVN，vpc_agent 也能瘦成 kube-ovn daemon 那样）是同构的。

## ④ 对照卡

| 维度 | OVN（northd + ovn-controller） | 我们一代 | 差异根因 |
|---|---|---|---|
| 意图→分发 | NB →（northd）→ SB →（每节点编译） | sdn_oss →（nfv-proxy）→ 分区 ZK/CKV →（vpc_agent） | 有没有一个"通用两跳编译"层 |
| 分发载体 | 集中 SB（Raft 一份）+ 客户端过滤 | 分区 ZK 树（结构预分区） | 中心式 vs 分区式两种哲学（Q3） |
| 增量框架 | I-P engine（显式依赖图 + 回退） | 自研 /BINLOG + 快照 + 对账 | 同一个"增量+全量"方程 |
| 节点侧定位 | 通用编译器（lflow→OF） | 全功能 agent（watch+state+netlink+对账） | 通用层是否在场 |
| 输出物 | OpenFlow（期望流表） | netlink/DPDK 指令 | — |

## ⑤ 验收题

1. 两跳分工线是什么？各举一个"如果并成一跳会付出什么代价"的反方案。
2. I-P engine 的"回退全量"触发条件是什么（代码级）？它和 felix full resync、etcd ErrCompacted 是同一件事吗——同在哪、不同在哪？
3. "集中 SB + 客户端过滤"与"分区 ZK 树"各自的代价是什么？为什么千万级对象时前者的压力会暴露？
4. `consider_lflow_for_added_as_ips` 揭示的增量难题是什么？"谁引用谁"这类信息在 I-P engine 里靠什么表达（提示：跳 2 的 `engine_add_input` 图）？
5. kube-ovn 只写 NB 却仍保留节点 daemon——残留职责和"通用层在场时的瘦身形态"分别是什么？

---

*本课行号清单（供追问对质）：`lib/inc-proc-eng.h` 框架注释@20、回退语义@322-325；`northd/inc-proc-northd.c` 依赖图@209-230/507-520；`northd/northd.c` build_lswitch_and_lrouter_flows@20961（调用@21224）；`ovn-sb.ovsschema` Chassis@28、Logical_Flow@97、Port_Binding@218；`controller/ovn-controller.c` 主循环@8188、engine_run@8455；`controller/binding.c` binding_run@2253、binding_handle_port_binding_changes@3296；`controller/lflow.c` consider_lflow_for_added_as_ips@619（内层 __@428）、as_update_can_be_handled@656（调用@735）；`controller/ofctrl.c` ofctrl_put@2756。*

*审查记录（首轮深审）：修正 1 处引用精度——`consider_lflow_for_added_as_ips` 的 @428 实为**内层 `__` 变体**（外层在 @619，按 datapath 包/组逐个处理是它俩的分工），已改并在**同一区域挖到一条加分料**：`as_update_can_be_handled@656`（注释@653"without reprocessing the lflow"）——**"能便宜处理的就不重算"是增量引擎的另一半功夫**（变更传播 + 快速路径），补入正文。其余引用（inc-proc-eng.h@20/322-325、inc-proc-northd.c@209-230/507-520、northd.c@20961/21224、schema@28/97/218、ovn-controller.c@8188/8455、binding.c@2253/3296、ofctrl.c@2756、行数七项）逐条 grep 命中。*

*审查记录（二次深审）：① R1 修正自查通过（外层@619 / 内层@428 / 快速路径@656 / 调用@735）；② 修正一处表述——"内层按 datapath 组逐个处理"不精（**外层分派 dp_group、内层处理单个 datapath**），已改；③ 两处加分细节入课——`binding_run` 首行 `chassis_rec` 校验（"我是谁"先于"谁在本机"）、`recompute_allowed = ovnsb_idl_txn && !ofctrl_has_backlog()`（**全量重算的预算控制**）；④ "SB 是 Raft 库"补跨仓实据（`refs/ovs/ovsdb/raft.c`，5350 行）。*
