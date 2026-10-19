# 开源精读 17 · vpc-broker 内核——5.0 配置分发的"热核"

> 课次：S2a（系统线第三站；5.0 体系的分发中枢骨架）
> 对象：`docs-by-topic/36-vpc-broker`（636 行，材料线；internal 281 文件 / 13 子域）+ gen3 深料 12 份（`test-good/02-vpc5-gen3/`，第 18 课展开）
> 坐标：S1（第 16 课）看完"二代→过渡代际"的心跳拉取；本课看 **5.0 的替代品**——broker 直连 agent 的流式分发；S3（预告）看端侧 host-agent。
> 纪律：断言落材料文本；深料引文进入第 18 课。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **binlog** | 5.0 的上游变更流（Kafka 主题）；一条变更 = 一个 `(ConfigKey, version, payload)` |
| **ConfigManager** | broker 内的"账本"：**自研 BTree 按版本区间索引 binlog**，支持 `minVersion` 过滤（"给我比这新的"） |
| **ConfigKey / Topic** | 一条配置的键 → 订阅主题；Dispatcher 的 `routeMap` 是 **Topic → Sessions 反向索引** |
| **Session** | broker ↔ agent 的会话：**七态**（INIT→HANDSHAKE→ACTIVE→INACTIVE→EXPIRED/TOO_SLOW/TERMINATED）+ 有界广播队列 |
| **`unackedEvicted`** | 队列满时的淘汰语义：**把还没被 ACK 的最旧条目丢掉并标记**——背压的"丢老保新" |
| **两阶段订阅** | DataSyncer 的接缝方案：**Kafka 先行（先返回 cached binlog）→ Fusion 初始拉取**（全量快照） |
| **ACK 记账** | Tracker：每条 binlog 的"分发→确认"全程记录（`dispatch_cost / total_cost`、SlowestSession、FailureTracker） |
| **ctrlSetReplica** | 六类引导元数据之一：**fail-closed 语义**（副本不明时宁可拒绝，不可错路由） |

## ① 它干了什么——一条路由变更的"全程旅行"

第 16 课的二代是"端侧心跳来拉"；5.0 的 broker 是**中心流式推**。一条路由变更从进到出：

1. 变更进 **Kafka binlog** → `upstream/DataSyncer`（Kafka 消费管线）→ 变成 `BinlogBatch`；
2. **ConfigManager（syncer）**：`ConfigHandler` 对单个 ConfigKey 做三件事——**版本推进、snapshot 对齐、cached binlog 注入**；落下**自研 BTree（版本区间索引）**；binlog 载荷由 `internal/binlog` 持久化到 LevelDB（见跳 6）；
3. **Dispatcher**：`messageCh → dispatchLoop → Worker Pool`（默认 **16 worker，按 SessionID hash 保序**）——`routeMap` 查出这条 Topic 的订阅会话们，**扇出**；
4. **Session**：进**有界广播队列**（`PushMany`；容量满 → 淘汰最旧未确认条目并标记 `unackedEvicted`）→ 经 **TRPC stream** 推给 agent；
5. **agent ACK** 回来 → **Tracker.OnAck**：这条 binlog 的"分发给谁、多久确认"记账完毕（慢会话进 SlowestSession 榜，失败进 FailureTracker）。

**一句话**：**二代 broker 是"账房"（等端侧来问），5.0 broker 是"自来水厂"（按订阅关系持续加压送水）**——而"水表"（Tracker 的 ACK 记账）装在了每一滴水上。

## ② 链路走查（材料行号）

### 跳 1：北向——两条管线与一个"接缝"

`36 §3.7.14`（upstream，52 文件）——**两阶段订阅**是 5.0 全量/增量问题的解法：

- **Kafka 先行**：返回 **cached binlog**（先让增量可用）（动机见 ③ Q1 推演）；
- **Fusion 初始拉取**：再去 vpc-fusion 拉全量快照——两者在 ConfigManager 里**对齐**；
- 组织结构：`DataSyncer` 接口（`StartKafkaWatch / StartFusionWatch / UnwatchConfig`）+ per-ConfigKey 订阅状态机（`Subscription`，kafka/fusion 两阶段推进）+ **PriorityQueue**（待处理订阅优先级）+ franz-go 消费封装；**endpoint/凭据由 coreCtrlSet 元数据动态提供**（配置也是元数据下发的——"车同轨"再现身）。

### 跳 2：ConfigManager——"账本"的三件套与 BTree

`36 §3.7.12`（syncer，17 文件；注意：**它不叫 ConfigManager 目录，叫 syncer**）：

- 接口六方法：`ProcessBinlogs / ProcessFusionData / Watch / Unwatch / GetFullConfig / FailPendingWatch`——**前两个收数据、后四个管订阅**；
- `handler.go`：单个 ConfigKey 的处理器——**版本推进、snapshot 对齐、cached binlog 注入、消息产生**（"接缝"就在这一步缝合）；
- **`btree.go`：自研 BTree——binlog 版本区间索引，支撑 `minVersion` 过滤查询**（订阅者只说"把比我版本新的给我"）；
- 全量+增量合并（`manager.go`）+ **保留窗口/清理间隔**（`config.go`）——binlog 不是永久账。

> 为什么自研 BTree（而不是 map/全扫）？订阅过滤的查询是"**版本 ≥ minVersion 的区间扫描**"——BTree 按区间索引天然适配；对照开源线：第 3 课 **etcd 按 revision 的有序索引与 watch（可指定起始 revision 重放）**——**"版本号是通用语"第 N 次现身**，而"按版本查区间"要的正是**有序索引**。

### 跳 3：Dispatcher——扇出引擎（36 §3.7.5）

- `dispatcher.go`：`WatchAsync / Unwatch / GetResource`；**`messageCh → dispatchLoop → Worker Pool`（默认 16，按 SessionID hash 保序）**——扇出；
- `routemap.go`：**routeMap = Topic → Sessions 反向索引**（并发安全的订阅/取消/遍历）；
- `upstream.go`：`configManagerClient`——对 ConfigManager Watch 回调通道的封装（**接缝在 Dispatcher 侧还有个适配器**）；
- `STRUCTURE.md`：**背压 / shutdown 设计决策**（模块自带设计文档）；`consts.go`：worker 数、channel 容量。

### 跳 4：Session——七态机与有界队列（36 §3.7.11）

- **状态机（`STRUCTURE.md`）**：`INIT → HANDSHAKE → ACTIVE → INACTIVE → EXPIRED / TOO_SLOW / TERMINATED`——**七态，其中 TOO_SLOW 是"慢消费者"专态**；
- `manager.go`：`Create/Handshake/Get/Remove` + 会话表 + **TTL 过期扫描**；
- `session.go`：`EnqueueBinlog / EnqueueWatchResult`；**慢消费者 TOO_SLOW 回调**；
- `queue.go`：`BinlogQueue`——**有界广播队列**（`PushMany`；**容量满 → 淘汰并标记 `unackedEvicted`**）；
- `result_queue.go`：`WatchResultQueue`（Watch 单播结果队列）；`subscription.go`：会话级 `SubscribedKeys`。

### 跳 5：Tracker——"水表"（36 §3.7.13）

- `tracker.go`：`OnDispatch / OnAck / OnSessionDestroy / Close`——**分发记账的四个事件口**；
- `full_tracker.go`：每条 binlog 的**分发 → ACK 全程记录** + **SlowestSession** 维护；
- `failure_tracker.go`：失败/超时未确认 binlog 的记录与输出；`store_memory / store_logging`：内存/日志双实现（结构化输出 `dispatch_cost / total_cost`）；
- `version_set.go`：**VersionSet——会话已确认版本集合**；`STRUCTURE.md`：**request_id / request_time / config_id 的传播路径与 ACK 汇总语义**。

### 跳 6：binlog 生命周期（36 §3.7.2）

- `interfaces.go`：`BinlogStorageInterface` + LevelDB 抽象；`storage.go`：`SaveBinlog / LoadBinlog / DeletePayload / 带重试保存`；
- `managed_binlog.go`：**ManagedBinlog——引用计数、过期清理、offload**（转储）：**"还有人要吗"（引用）与"老了没"（过期）双条件回收**。

### 跳 7：元数据引导——七阶段状态机（36 §3.7.8）

- 六类引导元数据（§1.1）：`vpcBroker / coreCtrlSet / resourceCtrlSet / resourceTopic / configTopic / ctrlSetReplica`——经 `api_source.go` 用共享 apiserver.Client **分页拉取**；
- **七阶段（`bootstrap_state.go`）**：`BOOTSTRAP_LOADING → VALIDATING → GENERATION_APPLYING → DATA_READY → SET_RECONCILING → WATCH_ALIGNING → READY`；
- **`ctrlSetReplica` fail-closed 语义**（`STRUCTURE.md`）——副本视图没对齐，**宁可拒绝不可错路由**；
- `manager.go` 聚合**四个子管理器**：brokerinfo / setbinding / topicmapping / distributeset（对外提供路由/归属/blacklist/Set 查询）。

## ③ 设计推导（问题树）

**Q1：为什么 16 worker 按 SessionID hash 保序？**
两个需求相乘：单会话内 binlog **必须保序**（版本链不能乱），全局范围**要并发**。hash 同一会话 → 恒落同一 worker——与 Kafka "同 partition 保序"、DPVS per-flow hash 同构。**"全局并行 + 局部保序"是分发系统的通用形状**。

**Q2：为什么队列有界 + 丢最老 + `unackedEvicted` 标记？**
无限队列 = OOM；满时两条路：阻塞（慢会话拖垮上游）或丢。选了"丢最老未确认 + **显式标记**"——**`unackedEvicted` 是可观测语义，不是静默丢弃**：agent 能据此知道"我漏了"，走重同步（VersionSet 比对）。同族：Kafka consumer lag、eBPF ringbuf 的丢弃计数。

**Q3：为什么 TOO_SLOW 是独立状态而不是错误？**
它是**流控信号**：慢消费者被单独标记/熔断，而不是拖垮分发器——**故障隔离**。EXPIRED / TERMINATED / INACTIVE 的区分同理：**不同死法不同语义，决定 agent 的重连策略**。

**Q4：为什么 Tracker 要 Full + Failure 双轨、维护 SlowestSession？**
分发系统里"每个消费者跟得上吗"就是核心 SLO：Full 轨 = 全量审计（dispatch_cost / total_cost）；Failure 轨 = 失败账本；SlowestSession = **隐性瓶颈探测器**。**可观测性不是后加的，是分发环里的一等公民**（对照第 15 课对账三层谱系——一个向外对账、一个向内记账）。

**Q5：为什么引导要七阶段 + fail-closed？**
"数据未就位就不能对外服务"被拆成**分阶段就绪门槛**（比单个 Ready 布尔精细得多：SET_RECONCILING、WATCH_ALIGNING 各是一道闸）；fail-closed 是**失败方向的选择**：`ctrlSetReplica` 没对齐时拒绝服务——因为**错路由比拒服务危险得多**（同族：分布式共识的 CP 本性——失多数派宁可不可写；第 16 课 agentos 的 Ready 校验）。

**Q6：为什么 binlog 要"引用计数 + offload"？**
一条 binlog 可能被多个会话队列各持一份引用 → 引用计数归零 + 过期双条件才可清理；offload 把冷数据转储。**分发热线上的 GC 要同时回答"还有人要吗"与"老了没"**（同族：第 15 课 cos_bak 的归档/删除双期）。

## ④ 对照卡：分发系统的四种形状

| 维度 | etcd（第 3 课） | Typha（第 2 课） | agent-broker（第 16 课） | **vpc-broker（本课）** |
|---|---|---|---|---|
| 方向 | 服务端推送 watch | 中心持账 + 端侧拉 | 端侧心跳拉 + 中心记账 | **流式推（按订阅关系）** |
| 版本/断点 | revision + fromRev | 快照版本 | SQLite + 内存版本 | **BTree minVersion + VersionSet** |
| 保序 | revision 全序 | — | — | **SessionID hash → 16 worker** |
| 背压 | — | — | — | **有界队列 + unackedEvicted + TOO_SLOW** |
| 全量接缝 | snapshot + WAL | — | SyncOnStart 清表重建 | **Fusion snapshot 对齐 + cached binlog 注入** |

## ⑤ 验收题

1. 两阶段订阅为什么是"**先增量（cached binlog）后全量（Fusion）**"而不是反过来？"snapshot 对齐 + cached binlog 注入"各解决什么？
2. 16 worker 的 SessionID hash 保序与 Kafka partition 保序：同构点、差异点各是什么？
3. 队列满时"丢最老未确认 + `unackedEvicted` 标记"与"阻塞等待"：各自适用场景？为什么"显式标记"是必须的？
4. TOO_SLOW 触发后，站在 Dispatcher / Session / agent 三侧各会发生什么？
5. `ctrlSetReplica` 的 fail-closed 为什么把"拒服务"排在"错路由"之前？再举一个开源线"失败方向选择"的例子。

---

*本课材料行号清单（供追问对质）：`36` §1.1（用途：北向 Kafka/Fusion、南向 TRPC Sync/Discover/Report、六类引导元数据、16 worker hash 保序、全异步）；§3.7.2（binlog：BinlogStorageInterface / ManagedBinlog 引用计数·过期·offload）；§3.7.5（dispatcher：messageCh→dispatchLoop→Worker Pool 16 / routeMap Topic→Sessions / 背压·shutdown）；§3.7.8（metadata：六类 bootstrap / 七阶段 / ctrlSetReplica fail-closed / 四子管理器）；§3.7.11（session：七态机 / Manager TTL 扫描 / BinlogQueue unackedEvicted / WatchResultQueue / TOO_SLOW 回调）；§3.7.12（syncer：ConfigManagerInterface 六方法 / handler 版本推进·snapshot 对齐·cached binlog 注入 / 自研 BTree minVersion）；§3.7.13（tracker：OnDispatch/OnAck / FullTracker+SlowestSession / FailureTracker / VersionSet / request_id 传播）；§3.7.14（upstream：两阶段订阅 Kafka 先行→Fusion / Subscription 状态机 / PriorityQueue / coreCtrlSet 动态供凭据）。*

*审查记录（首轮深审）：修正 4 处——① 移除**未授课归属**（"第 3 课 etcd 失多数派拒写"：第 3 课仅授 ZAB/Raft 全序广播，无此断言；改为"共识 CP 本性（常识表述）"）；② 移除**未授课引用**（Kubernetes informer/DeltaFIFO：全课从未提及，删除该同族例）；③ **过度断言软化**（"先让增量缓冲可用"→"动机见 ③ Q1 推演"）；④ **事实链精化**（BTree 索引归 syncer、LevelDB 载荷归 `internal/binlog`，二者不混同）。逐字命中：§3.7.11 七态机与"PushMany…unackedEvicted"、§3.7.12 六方法/三件事/minVersion、§3.7.13 OnDispatch·OnAck 与 dispatch_cost·total_cost·VersionSet、§3.7.8 七阶段链与四子管理器、§3.7.14 两阶段订阅、§1.1 六类元数据与 16 worker hash 保序。*

*审查记录（二次深审）：R1 四处修正自查在位（@28 载荷归属、@41 软化、@105 CP 表述、@132 审查记录）；两处旧措辞残留均在审查回溯内（合法）——**零尾差、零新错**。*
