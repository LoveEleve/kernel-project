---
title: "03-broker：无状态扇出 / 三向合并 / tRPC Stream 协议 / Session 7 态"
series: "VPC 5.0（Gen3）架构系列"
previous: "02-fusion.md"
next: "04-host-agent.md"
source_refs:
  - "vpc/test-good/02-vpc5-gen3/vpc-broker-consumption.md（475 行全读：ReplicaRouter/三棵 BTree/三向合并/16-Worker/ACK）"
  - "vpc/test-good/02-vpc5-gen3/trpc-stream-protocol.md（682 行全读：四阶段/7 态/稀疏 ACK/断线重连/错误码）"
  - "vpc/docs-by-topic/36-vpc-broker.md（头部+关键行：三 service 确认 / 订阅链路 / Get object_id 过滤）"
---

# 03-broker：无状态扇出 / 三向合并 / tRPC Stream 协议 / Session 7 态

> vpc-broker = Gen3 的**无状态分发层**：从 fusion 拉快照+binlog → BTree 版本索引 → tRPC 双向流推给 agent；管 Session（订阅/ACK/超时/重连）。
> 标注：【实证】/【推导】/【开放】/【外部】。

## §0 它干了什么（例子先行）

agent 重启后从 broker 拿配置的完整旅程：

```
① agent：DiscoverService.ListBrokers（种子 LB VIP）→ 真实 broker 列表（就近优选）
② BrokerConn.connect → 建 tRPC 双向流 → Handshake（带旧 sessionID）
   ├─ session 兼容 → 增量恢复（跳过已同步 binlog）
   └─ INVALID_SESSION → 空 sessionID 重握手 → 全量恢复
③ Watch（topics: rtable version=0 全量 / eni version=1234 增量）
   broker：FusionWatcher 拉 snapshot+binlog → 三向合并 → ConfigManager
   → Dispatcher → Session → sendLoop → WatchResp（snapshot+binlogs）
④ Poll 每 1s（HIGH/MEDIUM 分级，长轮询 10s/30s）→ PollResp 增量
⑤ ACK 每 3s 稀疏确认（start_version+end_version+not_ack+failure）
⑥ agent：WatchRouter → Extension.OnConfigChange → LevelDB → DPDK
```

## §1 掌握卡（7 条）

1. **broker 无状态**（可水平扩缩）：状态都在 fusion 存储 + Session（内存）+ agent 的 LocalVersion（LevelDB）。
2. **ReplicaRouter**：per-configKey 路由到 fusion replica；失败隔离 5 分钟 + 确定性 round-robin 切换（单 replica 不隔离）。
3. **三棵 BTree**（度 16）：binlogTree（已对齐、最终下发）/ unsyncedBinlogTree（Kafka 侧待对齐）/ fusionBinlogTree（fusion 侧待对齐）。
4. **三向合并**：snapshot + fusionBinlog + kafkaBinlog 做**版本连续性检查**（max+1 ≥ Kafka 最小版本）——防空洞、防重复。
5. **Dispatcher 16-Worker**：dispatchLoop 单协程 FIFO（**全量先于增量**）；同一 Session 按 hashSessionID 固定 Worker；channel 满阻塞**绝不丢**。
6. **tRPC Stream 四阶段**：Handshake（建/恢复会话）/ Watch（全量+增量订阅）/ Poll（长轮询增量）/ ACK（稀疏确认）；**sendLoop 单写入点**（并发 Send 会交错 HTTP/2 帧）。
7. **Session 7 态**：Init→Handshake→Active↔Inactive→Expired/TooSlow→Terminated；**TOO_SLOW（broker 主动踢）/ DataLoss（agent 发现）** 都导致重新 Watch 全量。

## §2 broker 定位与拉数

- **为什么需要**：fusion 不认识母机、agent 不认识 fusion——broker 是"订阅 fusion 快照、推送给 agent"的中间人。
- **发现 fusion**（不经 Polaris）：distributeset 元数据 → CDB/CRS 连接信息 → `FusionReplicaInfo` → per-set SDK（CacheClient = Redis + DB 二级缓存）。
- **发现 broker**（36 补遗）：`DiscoverService.ListBrokers` 按 **ZoneId 分组 + roles/states OR 过滤**——agent 的"就近优选"由服务端分组实现。
- **拉数主流程**：`GetLatestSnapshot`（Redis 元信息 + CDB 分片）→ `ListBinlog(version, maxSize)`（Redis）。
- **ReplicaRouter 故障切换**：SDK 失败 → 隔离当前 replica 5 分钟 → 下一个 replica（确定性 round-robin）→ 仅当有备选才隔离。

## §3 BTree 版本索引

```go
ConfigHandler { binlogTree / unsyncedBinlogTree / fusionBinlogTree }   // 三棵
btree.NewG(16, byVersionAsc)   // Google btree 泛型，度=16
```

- **为什么 BTree 不用 map**：按版本范围查询（version > N）+ 批量删除（≤ threshold）——O(log n) vs O(n)。
- **范围查询**：`Ascend` 跳过 ≤ filterVersion。
- **旧 binlog 清理**：`threshold = snapshot.Version - BinlogRetentionWindow` → `DeleteWhile(≤ threshold)`。

## §4 三向合并

**问题**：新 Session 接入时 snapshot 是历史版本 N，而 broker 自己消费的 Kafka binlog（近 10 分钟缓存）可能**跨越 N**——直接发会重复，只发 snapshot 会遗漏。

**三来源**：Fusion Snapshot（全量镜像 N）/ Fusion Binlog（同次 API 返回，> N）/ Kafka Cached + 实时（unsynced）。

**对齐条件（三选一）**：① snapshot 版本=0 ② Kafka 侧无未对齐 binlog ③ **版本连续**：`max(fusionBinlogMax, snapshotVersion) + 1 ≥ firstUnsyncedVersion`。

**对齐完成**：迁 `> snapshotVersion` 的 binlog 到 binlogTree → 清临时树 → 广播唤醒 → dispatchPendingSyncs。
**InitFailed 自恢复**：3 次重试仍不对齐 → 不删 handler/watch state，给订阅者发 WatchError，**保留 pending**——后续新版本到达可自动对齐。

## §5 Dispatcher 16-Worker 分发

```
ConfigManager → messageCh（buffer 1000）
  → dispatchLoop（单协程 FIFO）：routeWatchResult 单播 / routeBinlogs 广播 / routeWatchError
  → 16-Worker Pool（各 channel buffer 5000）
  → Session.EnqueueWatchResult / EnqueueBinlog → EventLoopWorker → sendLoop → stream.Send
```

- **单协程 dispatchLoop 的意义**：FIFO——WatchResult 一定先于 Binlog（**全量先于增量**）。
- **hashSessionID**（maphash）：同一 Session 固定同一 Worker——事件有序。
- **阻塞写入**：channel 满时阻塞——"宁可慢不可丢"（可能影响其他 Session 分发）。

## §6 tRPC Stream 协议

**三个 proto 文件**：syncer.proto（SyncService：Sync 双向流 / Get / HealthCheck）+ discover.proto（DiscoverService.ListBrokers）+ reporter.proto（ReportService 上报）——注意 syncer.proto 本身只定义 Sync+Discover 两个 service【实证，trpc 文档注】。（36 补遗：**Get 支持 object_id 过滤**；**订阅建立链路** = Watch → Dispatcher.WatchAsync → StartKafkaWatch（Kafka 订阅 + cached binlog）→ ConfigHandler 注入缓存并注册 pending → StartFusionWatch（初始 snapshot，失败切副本重试）。）

**四阶段**：

| 阶段 | Agent → Broker | Broker 处理 |
|---|---|---|
| Handshake | session_id（空=新建/非空=恢复）+ session_key（`agentIP_proc0`） | 空→新建（清同 key 旧会话）；非空→校验（不合法 INVALID_SESSION）→恢复 |
| Watch | topics（config_name+scope+version；0=全量） | FusionWatcher 拉数 → 三向合并 → 经 Dispatcher 推 WatchResp |
| Poll | queues（QueueClass+LocalVersion+BatchSize 100+timeout） | 长轮询（无数据挂到 timeout；有数据立即返回） |
| ACK | ClientAck（start/end + not_ack + failure） | UpdateAckedVersion + EvictBefore（not_ack 不驱逐；failure→重新 Watch） |

- **错误码**：QueueExpired(3)/InvalidSession(4)/RateLimited(5)/DataLoss(6)/SessionTooSlow(7)/PollAborted(12)。
- **稀疏 ACK**：100 条 binlog 只需 1 个 ACK 消息（区间 + 例外列表）。
- **三级队列**：HIGH=30（控制信令，1s）/MEDIUM=20（普通配置，1s）/LOW=10（按需）——**不是"高优先级阻塞低优先级"**：每次只发一个 QueueClass 的 Poll，broker 串行处理（独立请求）。
- **分批传输**：大快照 `is_complete + partition_index/count` 分批。
- **sendLoop 单写入点**：并发 `stream.Send` 会交错 HTTP/2 帧 → panic；sendCh 串行化。

## §7 Session 7 态与踢出机制

```
Init → Handshake → Active ⇄ Inactive；Active → Expired（心跳超时 GC）/ TooSlow（队列溢出未 ACK）→ Terminated
转换用 CAS（只有从 Active 转换才成功——防终态被覆盖）
```

- **Session 粒度版本号**：`localVersionSeq` 从 1 强连续递增（每条入队 binlog 分配）——Poll 过滤与 ACK 驱逐的基准。
- **TOO_SLOW**：队列满驱逐且被驱逐 entry 未 ACK（`unackedEvicted`）→ MarkTooSlow → 发 TOO_SLOW → agent 重建 Session + Watch。
- **DataLoss**：agent Poll 时 `local_version < 队列 minVersion`（`ErrVersionOutOfSync`）→ 重新 Watch 全量。

## §8 断线重连（三层不丢保证）

| 层 | 保证 |
|---|---|
| broker binlogQueue | 保留未 ACK 数据（环形队列 + ackedVersion） |
| agent LocalVersion | 持久化 LevelDB（per-broker：High/Medium Version + SessionID） |
| 重连 ReWatch | 携带 LocalVersion → broker 从该版本之后推送 |

- **重连**：指数退避 1/2/4/8/10s（上限 10s，最多 5 次）→ Handshake 带旧 sessionID → 兼容则增量、INVALID_SESSION 则全量。
- **ReWatch 调度**：按 (configType, setID) 去重 + 3 worker 并发（避免队头阻塞）。
- **QuickRestartMode**：启动期从 versionstore 注入 LocalVersion 到 Watch → **秒级增量启动**。
- **Seed Fallback**：全 broker 不可达 → 种子 LB VIP 重新 ListBrokers（打破"刷新 broker 需 broker 推送"循环）+ 节流 + singleflight。

## §9 与 Gen2 ZK Watch 对比

| 维度 | Gen2（ZK Watch） | Gen3（tRPC Stream） |
|---|---|---|
| 推送模式 | 一次性 Watch（触发后重注册） | 持久订阅 + 主动 Push |
| 全量/增量 | 每次 Watch 返回完整 znode | snapshot + binlog（is_complete 分批） |
| ACK | 无（收到即丢） | 稀疏 ACK（区间 + notAck + failure） |
| 重连恢复 | 重新 Watch 全量 | LocalVersion 增量恢复 |
| 顺序 | zxid 全序、client 收到无保证 | FIFO + localVersion 强连续 |
| 优先级/背压 | 无 / 无 | 三级队列 / MaxPendingVersions=1000 |

## §10 外部对照（【外部】）

**tRPC Stream ≈ Envoy xDS**（业界配置分发的标准协议）：
- Handshake/Watch ≈ xDS 连接与订阅；WatchResp(snapshot+binlog) ≈ DiscoveryResponse；**LocalVersion ≈ version_info**；**稀疏 ACK ≈ xDS ACK/NACK**（但 xDS ACK 按响应逐条，fusion 按版本区间——粒度不同）。
- **INVALID_SESSION → 全量重订阅 ≈ xDS 增量失败退回 SotW**（State-of-the-World）。
- 差异点：xDS 是**推模式 + 每次全量响应**（Delta xDS 才增量）；fusion 是"快照+增量"混合 + broker 持队列做背压——**比 xDS 多了 broker 层缓冲与 ACK 驱逐**。
- 另有 **SQS visibility timeout / Temporal** 映射见 09 §1（任务队列侧）。

## §11 钩子

- host-agent 侧消费与 Extension 分发 → **04**；SET 分片（agent 如何选 broker）→ **05**；broker 挂/分区场景 → 02 §6 + 06。
