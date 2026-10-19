# 开源精读 16 · 二代中枢：agent-broker + AgentOS——"心跳拉取 + 微内核"的换代

> 课次：S1（系统线第二站；第 13 课金句"控制面的厚度 = 数据面自治程度的反函数"在**我们自己两代系统之间**的再现）
> 对象：`docs-by-topic/15-agent-broker`（279 行）+ `16-AgentOS`（360 行）——**材料线**（无源码在盘），行号为材料行号
> 对照：一代 `vpc_agent`（28 文档：watcher/object/check 三件套 + ZK watch）；预告 S3（5.0 host-agent + framework）
> 纪律：全部断言落在材料文本。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **Broker（汇聚层）** | 二代中枢：上游订阅分区 ZK/CRS，落 **SQLite + 内存版本**；下游给母机 agent 提供**心跳式增量/全量同步** |
| **HostHeartbeat** | 母机 agent 的"心跳接口"——带版本号来问，broker 打包差异（**拉，不是推**） |
| **Extension 三层** | AgentOS 的资源处理范式：**Upstream（上游拉取）/ Res（本地资源存储）/ Downstream（下游下发）**——外加每 ext 可注册 **CheckJob** |
| **UniObj** | 统一资源对象（vpc-agent-pb 定义）——框架层不认得业务类型，只认得 UniObj |
| **worker group** | 进程内按协程分片 VPC——**第二级分片**（第一级是 broker 间分 partition） |
| **inspector** | 框架内建**对账子系统**（泛型 `Differ[T]` + `fix_less/fix_more/fix_update`）——对账从"手写"变"平台" |
| **PreGen / Partition ZK** | 二代的上游配置源——注意：**它仍是一代形态的 ZK 树**（"过渡代际"的关键） |

## ① 它干了什么——一个 VPC 变更的"反向旅行"

同一件事（一个 VPC 的配置变更到母机）在两代的走法：

- **一代**：nfv-proxy 消费 binlog → **推进**分区 ZK `/BINLOG` → vpc_agent **watch** 到 → 下发。**中心推 + 端侧 watch**。
- **二代**：分区 ZK/CRS 变更 → broker（AgentOS 驱动）的事件回调 → 该资源的 **ext 三件套**各就各位（Upstream 拉 / Res 存 + 版本 / Downstream 落 SQLite）→ 母机 vpc-agent **带着自己的版本号来"心跳"**（`HostHeartbeat`）→ broker 按版本差打包**增量或全量**回去；端侧若发现差异，还可**反向上送**（`Collect/UpdateHostDiff` → DiffRedis）。

**一句话**：**"中心推 + 端侧 watch" 换成了 "端侧心跳拉 + 中心记账"**——同一个分发问题，方向倒过来；这正是 AgentOS 微内核 + Broker 汇聚层这套换代的主叙事。

## ② 链路走查（材料行号）

### 跳 1：broker 的启动六步——换代的"现场录像"

`15 §4` 的启动流程就是换代动作的逐帧回放：

1. main → **`agentos.Run(broker.Application, b.Init, b.Start, WithLoadDrivers(...))`**——业务方只写 Application/Init/Start，壳全归框架（第 7 课"一核多壳"的镜像：**这里是"一框架多业务"**）；
2. Init：建 gRPC server 并注册 `HostHeartbeat` → 解析本机 Zone → `core.InitAgentOS`；
3. Start 六步里藏着三个"代际现场"：
   - **`PrepareEnv`：注册 SpecialWorker ext（recent_change_vpc）→ 关闭一代 zk 连接**——起来的第一件事是"和旧世界断开"；
   - **`SyncOnStart`：先同步最近变更 VPC → GlobalWorker `RemoteGet` 全量/增量补齐 → 全新启动时清表重建、传播全局版本号 → 为每个 worker 的每个 ext 构建 SQLite 缓存**——"resync/冷启动"第 N 次现身（本项目内叫 **SyncOnStart**）；
   - `SwitchOnEventProcess` 打开 **PreGen/NextGen zk** 事件回调 → `startTRPC`（Heartbeat / Sharding / CommonRpc 三个 service）→ 置 Ready + 周期上报心跳。

### 跳 2：ext 三件套——"跨代不变量"

`15 §3.9` 的设备配置 ext 是**四套同构三件套**（host / hbgw / pvgw / …），每套三件：

- **`composer.go`**——组装器：注册 `Res / Upstream / Downstream / CheckJob` **四样**（对账也是注册项！）；
- **`res.go`**——资源层：`SaveBinlog` 时**同步内存版本**；
- **`downstream.go`**——SQLite 下游驱动（binlog 应用与查询）。

> **本课最大的发现（跨代不变量）**：一代 vpc_agent 的资源处理是"领域 × **watcher / object / check** 三件套"（28 §3.9.3），二代 AgentOS 是"**upstream / res / downstream** + CheckJob"（16 §1）——**三件套的模式没变，变的是载体**（ZK watcher → 框架 Extension）与**驱动方向**（watch 事件 → 心跳请求/回调）。"换代"换的是机制，不是心智模型。

### 跳 3：心跳与两级分片（15 §3.5 / §3.6）

- `endpoint/host/heartbeat.go`：**`HostEndpoint.Heartbeat`**——校验 Ready 后**按请求版本**打包 vpc 资源 / host_config / common 的**增量或全量**响应（**一个接口同时承担"增量同步"与"全量兜底"**——"增量+全量"方程第 N 次）；
- `endpoint/comsvc/endpoint.go`：`CommonEndpoint`——`SyncDevConf` 下发设备配置；**`Collect/UpdateHostDiff` 接收母机上送的对账差异并写 DiffRedis**（跳 5 的双向对账）；
- `sharding/broker_partition.go`：`ShardingManager` 单例——监听 ZK 树 **`/TVPC/AGENT_BROKER/WORKER`** 构建 **partition → broker（hostname:port, zone, healthy）拓扑**；经 **CRS 记录/检查 broker 心跳存活**；支持测试模式的固定分片（PartIds）。
- **两级分片**：broker 间分 **partition**（拓扑登记在 ZK、存活判定在 CRS）；进程内 **worker group 分 VPC**（16 §1）——**分片拓扑也是被下发的配置**（与业务数据同等待遇）。

### 跳 4：AgentOS 微内核——"三层 + 两件一等公民"（16 §1 / §3.11）

- **三层 Extension**：`upstream / res / downstream`；资源对象统一为 **UniObj**（框架不认得业务类型）；
- **两件一等公民**：**worker group（进程内分片）** 与 **inspector（对账子系统）**——
  - `InspectScheduler`：全量/增量（**top idx、最近变化**两种触发）对账编排 + worker 协程池 + 修复执行（**`fix_less / fix_more / fix_update`**）；
  - `RegistCheckJobs`：扫描全部 composer 注册的 **CheckJob** 加入对账框架；`checkJobWrapper` 管命名/间隔/并发；
  - **泛型 `Differ[T]`**：对单个 ext 的对象集做差异计算，输出 diff idx 序列——**对账算法被泛型化**；
  - `com_check_job_func.go`：`IgnoreCustomFix`（空修复）/ `DefaultFix`（more/less 直接修复）——**每个 ext 的修复策略可插拔**。
- 配置面：toml `[agentos]` 段——**`WorkerNum`、`inspect` 对账间隔**、http、driver zk、sqlite、redis：**"对账间隔"是框架级配置项**。

### 跳 5：对账的双向化（15 §3.5）

`UpdateHostDiff` → DiffRedis：**端侧发现差异，反向上送中心**——一代对账是"中心算、中心断"（differ/account），二代是**双向**：中心有中心的 CheckJob，端侧也能"报案"。两句闭环：**Res 的版本号让"谁旧"可判；DiffRedis 让"端侧的意见"有地方放**。

## ③ 设计推导（问题树）

**Q1："推 + watch" 换成 "心跳拉 + 记账"，各付什么？**
一代的税：重注册 30-40 分钟（第 4 课 §12.2）、发布窗口保护、watch 风暴。二代把中心从"**广播站**"改成"**账房**"：SQLite + 内存版本 = 账本，端侧按自己节奏、按版本差要数据。同族独立收敛：第 2 课的 Typha 也是"中心持账、端侧按需"——**我们自己的二代与 calico 在同一个方向上各走各的**。

**Q2：两级分片各解决什么？**
partition 分片 = **横向扩展**（broker 水平扩）；worker group = **进程内并行与隔离**（多核 + 故障域）。拓扑登记在 ZK、存活心跳在 CRS——分片体系本身也吃了一遍"配置树 + 心跳"的架构饭。

**Q3：为什么冷启动是"清表重建 + 全局版本号"？**
SQLite 是**缓存不是真相**——"清表重建"= 对缓存的不信任（便宜且彻底）；**版本号是缓存与真相之间的坐标系**（同构：etcd revision / CKV 版本 / OVN seq——"版本号"是跨系统通用语）。这也是"增量+全量"方程第 N 次现身：**SyncOnStart = 全量，事件回调 = 增量**。

**Q4：inspector 框架化意味着什么？**
一代的对账是各业务手写的（rtable_account / kernel_check / global_route_check…，28 §3.9.4）；二代升格为**平台能力**：CheckJob 注册 + **泛型 Differ** + 可插拔修复策略。同族演进（第 10 课 cilium 的 Regenerate 管线 / 第 1 课 kube-ovn 的 reconcile 收敛）里，"泛型化 + 策略插拔"这个组合是最彻底的。

**Q5："过渡代际"的本质？**
上游仍是 **PreGen/Partition ZK + CRS**（一代配置树形态），下游已换成**心跳 + 微内核**——**上下游异步换代**：先把网元侧换掉，配置源换新留给 5.0（api-server → fusion → broker）。工程上最稳的迁移顺序：**在稳定的上游之下，换掉整个下游**。

## ④ 对照卡：三代 agent 体系谱系

| 维度 | 一代 vpc_agent（28/14） | 二代 broker + AgentOS（15/16） | 三代 host-agent + framework（39/33，预告） |
|---|---|---|---|
| 上游 | 分区 ZK watch + CKV 快照 | **PreGen/Partition ZK + CRS 订阅** | fusion/broker（tRPC 流式 Watch/ReSync） |
| 驱动方向 | 中心推 + 端侧 watch | **端侧心跳拉 + 中心记账** | 流式推送 + 端侧 ReSync |
| 资源范式 | watcher / object / check | **upstream / res / downstream + CheckJob** | DataSyncer / Extension / DPlane |
| 本地存储 | state + LevelDB | **SQLite + 内存版本** | LevelDB / BTree（broker 侧） |
| 对账 | 各业务手写 check | **框架 inspector（泛型 Differ）** | Reconciler + Inspector（预告） |
| 框架 | agent_base | **agentos（微内核）** | vpc-agent-framework |

## ⑤ 验收题

1. "推 + watch" → "心跳拉 + 记账"：二代侧最贵的代价是什么？（提示：中心记账的存储与版本管理）
2. 两级分片（partition × worker group）分别解决横向扩展还是进程内并行？为什么分片拓扑要"登记在 ZK + 心跳在 CRS"？
3. SyncOnStart 的"清表重建 + 全局版本号"——为什么缓存可以整个扔掉？版本号扮演什么角色（举一个开源线的同构）？
4. inspector 从"业务手写"到"框架泛型化"——收益与代价？（提示：`Differ[T]` 的边界就是"对象集"语义）
5. "过渡代际"的迁移哲学：为什么"上游不动、先换下游"？结合第 13 课"控制面的厚度 = 数据面自治程度的反函数"，为 S2/S3（5.0 迁移）写一句预测。

---

*本课材料行号清单（供追问对质）：`15` §1（用途/技术栈：PreGen/Partition ZK + CRS → SQLite + HostHeartbeat）/ §3.5（heartbeat.go 按版本增量或全量 / CommonEndpoint 的 SyncDevConf 与 Collect/UpdateHostDiff→DiffRedis）/ §3.6（broker_partition.go：`/TVPC/AGENT_BROKER/WORKER` 拓扑 + CRS 存活）/ §3.9（device_config 四套同构三件套：composer 注册四样）/ §4（启动六步：agentos.Run / 关闭一代 zk / SyncOnStart 清表重建 / 三 service）；`16` §1（用途：三层 Extension / UniObj / worker group / toml 段含 WorkerNum 与 inspect 间隔）/ §3.11（inspector 五件：scheduler / check_job_register / check_job_wrapper / com_check_job_func / object_differ 泛型 Differ）。*

*审查记录（首轮深审）：修正 1 处引用措辞——"cilium Reconcile"改引已授课内容（"cilium 的 Regenerate 管线（第 10 课）/ kube-ovn 的 reconcile 收敛（第 1 课）"，可溯源）；**一处虚惊归档**：`rtable_account` 的我疑归属 §3.9.4 有误，核材料 602 行注记"已在 3.9.4 逐条描述"——**原引用正确**；逐字命中项：§3.9.3 三件套标题、"top idx、最近变化 / fix_less·fix_more·fix_update"@16:277、`Differ[T]`@16:281、§4 启动六步全链。*

*审查记录（二次深审）：R1 修正自查在位（@87 已改引 Regenerate/reconcile 可溯源表述；正文零残留——@115 的旧措辞仅存于审查回溯，合法）；材料课引文全为段落/行级引用——**零尾差、零新错**。*
