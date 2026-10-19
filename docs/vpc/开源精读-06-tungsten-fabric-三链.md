# 开源精读 06 · Tungsten Fabric 三链：config → control-node → vRouter

> 课次：开源精读路线第 6 课（重型收官：整栈最接近的对照系统）
> 对象：`refs/tf-controller`（commit `6a67439`）+ `refs/tf-vrouter`（commit `432a8e0`）
> 材料对照：`vpc/docs-by-topic/01 附录 A`（TF 逐项对照表——"TF 无 Saga/Outbox""TF 用 BGP 对接物理交换机"等原话都在那里）
> 纪律：路径与行号来自实仓核验；**C++ 巨仓只读三链关键接口，不漫游全仓**（路线纪律）。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **IF-MAP** | 一种"图"协议：用 **Identifiers（实体）** 和 **relationship（关系）** 描述网络配置状态——本课第一性对照点：**图 vs 我们的树** |
| **config / schema-transformer** | 声明入口（REST）+ 把用户模型转成 IF-MAP 数据模型的翻译层 |
| **control-node** | 中心控制器（C++）：消费配置图 + 跑 BGP 计算 + 通过 XMPP 对 agent 下发 |
| **vRouter / agent** | 边缘：contrail-vrouter-agent（用户态）+ vrouter（内核 dp-core / DPDK / uvrouter 三形态数据面） |
| **VRF / MPLS-over-GRE** | TF 的隔离与封装做法——对照我们的"GRE key = VpcId" |

## ① 它干了什么——一条 VM 网卡加入 VPC 的全链

1. 声明进 **config-api** → **schema-transformer** 转成 IF-MAP 图（VM/虚拟网络/端口等**实体 + 关系**）→ 图的更新发布出去；
2. **control-node** 消费图更新 → `BgpIfmapConfigManager` 驱动 BGP 计算（路由目标/RD 等）→ 变更**入队**；
3. 对**相关**的 agent：`IFMapExporter` + **`IFMapGraphWalker` 沿着关系走图**，选出**这个 agent 需要的子图**——和路由更新一起，编码进**同一条 XMPP 通道**下发；
4. 边缘 agent 收到 → 建 VRF/接口/路由表 → 通过 **generic netlink** 编程 vrouter → 数据面 flow 表就位。

**一句话**：TF 用"图"做配置模型、用"选择性下发子图"做分发——这是和"ZK 树/平铺 KV"并列的第三种分发世界观。

## ② 链路走查（file:line 级）

### 链 A：config——声明与图

`src/config/` 目录即架构：`api-server`（REST 入口）、**`schema-transformer`**（用户模型 → IF-MAP 模型的翻译器）、`device-manager`、`svc-monitor`。材料侧对位（`01 附录 A`）："API 网关 vs Config API Server (REST) + Schema Transformer 校验"。

### 链 B：control-node——图的心脏

`src/ifmap/README.md` 自述（三段原文，本课最值钱的引文）：

> IF-MAP is used to convey the configuration state of the network as a **graph** describing entities (Identifiers in IF-MAP speak) and their relationships.
>
> The control-node listens to a single IF-MAP server and uses that information to populate its database. It then **selectivly updates** [sic，原文拼写] the compute-node agents that are connected to it by sending them only the **subset of the IF-MAP graph** that these agents need. IF-MAP information is encoded in the **same XMPP channel used to send routing information**.
>
> Agents subscribe to information that is relevant to the "virtual-machines" that are executing on that specific compute-node. The **IFMapExporter** class running in the control-node is then responsible to determine the subgraph of information that needs to be transmitted to that agent. That is **achived** [sic，原文拼写] by the **IFMapGraphWalker** class. Just as with the BGP engine, entries are first **enqueued** for transmission and then encoded when the receiver is ready to receive the message.

> （引文出处：`src/ifmap/README.md:1-2` / `:4-6` / `:8-11`，逐行核过；两处 [sic] 为原文拼写，未改动——**引文纪律：不改原文**。）

装配线在 `src/control-node/main.cc`：`CreateXmppServer@87` / `new XmppServer(...)@105`；`IFMapServer ifmap_server(&config_db, &config_graph, ...)@283` + `IFMap_Initialize@293`；配置驱动的桥在 `factory.cc:9`（`BgpIfmapConfigManager` 注册——**图更新 → BGP 计算的入口**）。

"图能力"的代码面：`src/ifmap/` 全目录——`ifmap_graph_walker.cc`（走图）、`ifmap_exporter.cc`（子图导出）、`ifmap_dependency_tracker.cc`（依赖跟踪）、`ifmap_update_queue/sender.cc`（入队/发送）、`ifmap_xmpp.cc`（IF-MAP↔XMPP 桥）。

### 链 B2：XMPP——自研消息总线

`src/xmpp/` 不是引库，是**全套自实现**：`xmpp_channel / xmpp_connection / xmpp_state_machine / xmpp_session / xmpp_lifetime / xmpp_server / xmpp_client / xmpp_channel_mux …`——把 XMPP 当"可靠消息总线"用（配置图 + 路由共用）。

### 链 C：agent + vRouter——边缘消费与编程

agent 入口 `src/vnsw/agent/contrail/main.cc`；对上游的通道类是 `controller/controller_peer.h:46` 的 `AgentXmppChannel`：

```cpp
class AgentXmppChannel {                                            // :46
    ...
    virtual bool SendUpdate(const uint8_t *msg, size_t msgsize);    // :54 —— 上行（VM 路由）
    ...
    static bool IsBgpPeerActive(const Agent *agent, AgentXmppChannel *peer);  // :66
```

**vRouter（数据面）**：用户态↔内核接口在 `linux/vr_genetlink.c`：

```c
 * vr_genetlink.c -- generic netlink stuff needed for vnsw     // :2
static int netlink_trans_request(struct sk_buff *, struct genl_info *);  // :27
static struct genl_ops vrouter_genl_ops[] = { ... };            // :30
```

数据面的核心结构在 `dp-core/vr_flow.c`：`void *vr_flow_table; void *vr_oflow_table;`（`@53-54`），桶查找经 `vr_htable`（`@69-71`）。三形态目录：`dp-core`（内核）/ `dpdk` / `uvrouter`（用户态）+ `freebsd`。

### ⚠️ 代差对照（本课方法论彩蛋）

`01 附录 A` 写 TF 内核接口是 "**/dev/vrouter ioctl**"——但本仓**全仓** grep（`--include=*.c --include=*.h`）**零命中**；全部走 generic netlink。引用面**精确核数：13 个 C/H 文件 + Makefile**（[⚠️ 见审查记录：上一稿写"8 个文件"是 `head -8` 截断当全集的产物]）：

```
linux/vr_genetlink.c  linux/vrouter_mod.c  dp-core/vrouter.c
include/vr_genetlink.h  include/vr_proto.h
freebsd/vr_bsd_transport.c
utils/{nl_util, vr_util, vif, dpdkconf, flow, qosmap}.c
dpdk/vr_dpdk_netlink.c          ← 连 DPDK 形态也走 netlink
```

**材料记的是老一代接口，代码已是当代**——而且"全形态统一 genetlink"（内核 / FreeBSD / DPDK / 工具链）比上一稿说的更彻底——跨代材料对代码必须逐条 grep（本课程 R3/R4 反复吃的教训，在此再示范一次）。

## ③ 设计推导（问题树）

**Q1：为什么用"图"而不是树/平铺？**
看 `IFMapGraphWalker` 的存在就懂了：**"给每个 agent 选出它需要的子图"是图上的原生操作**——从 VM 实体出发，沿关系走到它挂在的端口/VN/策略，切出子图。配置世界天然是"实体 + 关系"网。对照我们的 ZK **路径树**（层级只承载命名，实体间关系靠客户端约定：rtable 与 rtb 两棵树的一致性、agentos 的 watch 泄漏都源于此）和 etcd 的**平铺 KV**（关系靠前缀约定）。**图 / 树 / 平铺——三种世界观，三种"选谁下发"的代价**：图付出软件复杂度换选择精度；树付出结构约束换简单；平铺付出约定纪律换灵活。

**Q2：为什么配置与路由**共用一条 XMPP 通道**？**
README 原文直说。收益：一条长连接、一套会话/背压（enqueue→receiver ready→encode）；代价：**两类流量耦合**（配置洪峰会挤占路由更新）——我们用"Kafka 增量 + ZK 信号 + 快照"分离通道，是反向取舍。

**Q3：附录 A 记的最大差异——"TF 无 Saga/Outbox"（`01:3022`）意味着什么？**
TF 的 config"直接写 DB + 推 IF-MAP"——**它把一致性责任全押在"推"的可靠性和事后对账上**（TF 的 Introspect 对账 ≈ 我们的 Inspector / vpc_zk_differ）；我们用 Saga+Outbox 把"失败半途"的成本**显式化**（步骤可回滚 + 发件箱保投递）。两条路线各有债：TF 债在"推丢了怎么办"的观测面，我们债在"补偿链的复杂度"。**这是两套系统灵魂层面的对照**。

**Q4：BGP 与 VRF——TF 的两张我们没有的牌。**
BGP 对接物理交换机（附录 A @3021："本系统不做"）：TF 想扎进运营商/IDC 网络，我们做纯 overlay + 自研隧道；VRF + MPLS-over-GRE/VXLAN（附录 A 对照行 `vpc/docs-by-topic/01:3012`）：隔离靠 VRF 表（不同虚拟网络可重叠 IP/MAC）、封装靠 MPLS 标签——对照我们"**GRE key = VpcId**"的一招鲜（同一目标、不同抽象层）。

**Q5：三链里谁最值得深潜？**
按"和我们的差"排序：① **IF-MAP 图模型**（图 vs 树的取舍——第 1 个小时就改变世界观）；② **IFMapExporter/GraphWalker**（"子图选择"的分发算法——可对照我们 agent-broker 的过滤）；③ XMPP 自研总线（工程细节，价值排后）；vRouter dp-core 的 flow 表（对照 vpcko 的下一个大专题）。

## ④ 对照卡（整栈收官）

| 维度 | TF | 我们 | 差异根因 |
|---|---|---|---|
| 声明入口 | config-api + schema-transformer | sdn_oss 接口层（Saga 编排） | 图翻译 vs 事务编排 |
| 配置模型 | **IF-MAP 图**（实体+关系） | ZK 路径树 / MySQL 表 | 三种世界观（Q1） |
| 分发通道 | IF-MAP+路由**共用 XMPP** | Kafka 增量 / ZK 信号 / CKV 快照（分离） | 复用 vs 隔离 |
| 中心计算 | control-node（BGP 计算+子图选择） | sdn_oss/nfv-proxy（Saga+binlog 派生） | 无 Saga（附录 A） |
| 边缘 | agent + vrouter（内核/DPDK/uvrouter） | vpc_agent + vpcko/DPDK 通道 | 同构（都用 genetlink/双形态） |
| 隔离/封装 | VRF + MPLS-over-GRE/VXLAN | GRE key = VpcId | 抽象层不同 |
| 对账 | Introspect | Inspector / differ / 报告制 | 同题异构 |
| 多代共存 | 无（附录 A） | 三代灰度并存 | 我们的历史包袱也是独门能力 |

## ⑤ 验收题

1. "图 / 树 / 平铺"三种配置模型各自让"给某个 agent 选它要的那份"变简单还是变难？各付什么代价？
2. IFMapGraphWalker 和 agent-broker 的"过滤下发"是同一种东西吗——输入、算法、输出各有什么不同？
3. TF"无 Saga/Outbox"的两条债分别记在哪？（推丢了的观测面 / 谁保证最终一致）
4. 附录 A 的 "/dev/vrouter ioctl" 在本仓为什么 grep 不到？这给你的"看文档学系统"方法加了什么纪律？
5. VRF+MPLS 与 GRE key=VpcId 解决的是同一个问题吗？互换约束（谁做多租户云/谁做 IDC 互联）会怎样？

---

*本课行号清单（供追问对质）：`src/ifmap/README.md`（三段引文 :1-2 / :4-6 / :8-11）；`src/control-node/main.cc` XmppServer@87/105、IFMapServer@283、IFMap_Initialize@293；`src/control-node/factory.cc:9` BgpIfmapConfigManager；`src/vnsw/agent/controller/controller_peer.h` AgentXmppChannel@46、SendUpdate@54、IsBgpPeerActive@66；`tf-vrouter/linux/vr_genetlink.c` @2/27/30；`tf-vrouter/dp-core/vr_flow.c` vr_flow_table@53、vr_htable@69-71；`src/vnsw/agent/contrail/main.cc` main@76；材料：`vpc/docs-by-topic/01 附录 A`（MPLS-over-GRE/VXLAN + VRF 对照行@3012、BGP"本系统不做"@3021、无 Saga@2997/3022）。*

*审查记录（三轮深审）：本轮抓两处 + 补两处——① **看板重复行**（lesson 3 出现两行：✅ 与 ☐ 并存，已在《开源精读路线》删除 stale 行）；② **"8 个文件"系 `head -8` 截断当全集**——真实引用面 **13 个 C/H 文件 + Makefile**（含 `dpdk/vr_dpdk_netlink.c` 与 utils 六件），修正后"全形态统一 genetlink"结论更强；③ 补 `contrail/main.cc` main@76（agent 入口实锤）；④ 附录 A 原话行号补齐（@3012/@3021/@2997/3022）。*

*审查记录（首轮深审）：① **引文复原**——README 两处原文拼写被上一稿"顺手改对"（`selectivly`/`achived`），已复原并加 [sic]（引文纪律：不改原文），同时补全引文行号（:1-2/:4-6/:8-11）；② **归属纠正**——"VRF 让不同虚拟网络可以重叠 IP/MAC"在本地材料 grep 零命中（来源是用户对照资料，我误标成附录 A），改为可落证的附录 A 对照行（`01:3012`："MPLS-over-GRE/VXLAN + VRF"）+ 概念表述去引号；③ 对照卡 `MPLS-over-GRE/UDP` → `VXLAN`（对齐附录 A 用词）。其余引用（main.cc@87/105/283/293、factory.cc@9、controller_peer.h@46/54/66、vr_genetlink@2/27/30、vr_flow@53/69、ioctl 零命中）逐条 grep 命中。*

*审查记录（二次深审）：① R1 修正自查通过——两处 [sic] 与引文行号（:1-2/:4-6/:8-11）均在位，无残留 "selectively/achieved" 与 "MPLS-over-GRE/UDP"；② **自审抓出一处"证据范围不符"**——R1 说"本仓 grep 零命中"，但当时 grep 只搜了 `linux/ dp-core/` 两目录；本轮**全仓终审**（`. --include=*.c --include=*.h`）确认零命中属实，并顺带把 genetlink 引用面补成 **8 文件**（含 `freebsd/vr_bsd_transport.c`、`utils/nl_util.c`——"全形态统一 genetlink"的强证据入课）。*
