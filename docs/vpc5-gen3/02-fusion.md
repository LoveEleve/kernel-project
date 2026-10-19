---
title: "02-fusion 深挖：六模块 / 任务队列（8 Lua + 三段式）/ 快照结构 / 选举"
series: "VPC 5.0（Gen3）架构系列"
previous: "01-write-side-api-server.md"
next: "03-broker.md"
source_refs:
  - "vpc/test-good/02-vpc5-gen3/vpc-fusion-position.md（536 行全读：六模块/协作/故障矩阵/Gen2 对比）"
  - "vpc/test-good/02-vpc5-gen3/vpc-fusion-task-queue.md（768 行全读：Leader-Worker/8 Lua/Lease+Heartbeat+Recovery/Ownership/限频）"
  - "vpc/test-good/02-vpc5-gen3/vpc-fusion-snapshot-structure.md（969 行全读：LevelDB 缓存/ResSnapshot/分片/版本/压缩/HTTP API）"
  - "vpc/test-good/02-vpc5-gen3/bugfix_disorder.md（215 行全读：50 commit/16 工程问题/演进）"
  - "vpc/test-good/02-vpc5-gen3/vpc-fusion-core-fixes.md（574 行全读：5 commit 逐行）"
  - "vpc/test-good/02-vpc5-gen3/vpc-fusion-accompanying-fixes.md（606 行全读：7 附带 bug/测试）"
  - "vpc/test-good/02-vpc5-gen3/failure-scenarios.md（781 行全读：10 场景×时间线/7 故障点/全局单点/组合故障）"
  - "vpc/docs/vpc-fusion-Lua脚本-完整推断实现.md（298 行全读：8 脚本推断稿——材料自标'推断非源码'）"
  - "vpc/interview/vpc5-面试文档/专题-快照结构深挖、专题-对账机制深挖、专题-初始化与清理模块、专题-消息管道深挖（4 篇全读：对账口径/四态/27.8h/+40s）"
  - "vpc/interview/vpc5-面试文档/专题-故障场景手册、专题-分片迁移与测试质量、专题-分布式深水区（3 篇全读：选举 Lua/Fencing/背压/冷启动/降级分级）"
  - "vpc/docs-by-topic/38-vpc-fusion.md（关键段全读：两阶段 init/存储分层/降级内存）"
---

# 02-fusion 深挖：六模块 / 任务队列（8 Lua + 三段式）/ 快照结构 / 选举

> vpc-fusion = Gen3 的**配置中转站**（替代 Gen2 nfv-proxy，职责更大）：中转 + 快照 + 对账 + 清理。
> 标注：【实证】= 材料有据；【推导】= 按约束推导；【开放】= 材料不足/口径冲突。

## §0 它干了什么（例子先行）

一条 vmRTable binlog 在 fusion 里的旅程：

```
① message/binlog（Kafka 消费者）：消费 binlog → 写 MySQL t_generic_config
   → 存 binlog protobuf 到 Redis 聚合集合（BizTypeBinlogPb）→ 通知 snapshot Leader
② snapshot Leader（Redis 选举）：扫变更 → 发布任务到 Redis 队列
   task:data:{snapshot}:task-001 = {configName: vmRTable, scopeId: vpc-gu5knkob, latestVersion: 1236}
③ snapshot Worker（20 并发）：原子 claim（Lua）→ 增量合并：
   loadFromSnapshot(旧版 shard) → mergeBinlog（INSERT/UPDATE→SaveConfig；DELETE→DeleteConfig）
   → 未变 shard cloneSnapshot（只改版本号）→ 组装 ResSnapshot → 智能压缩（默认 SNAPPY）
   → 写 t_generic_snapshot（MySQL+Redis 双写）→ 更新版本列表 → 删 LevelDB 临时目录
④ 发 Kafka SnapshotReadyNotification → broker 拉新快照
```

**输入**：Kafka binlog。**输出**：`t_generic_snapshot`（+版本列表）+ 就绪通知。

## §1 掌握卡（8 条）

1. **六模块**：initconfig / message(含 binlog) / snapshot / reconcile / cleanconfig / vpcapi；其中 **4 个 Leader-Worker**（前四个中的 init/snapshot/reconcile/clean）+ 2 个消费者/客户端。
2. **4 个独立选举域**：init_zone / snapshot-build-leader / reconcile / clean-config-leader——Redis SET NX EX（TTL 15s、续约 5s），**各自独立、不共享 Leader**。
3. **任务队列 = Redis 4 种 Key + 8 个 Lua 脚本**：Hash Tag `{queueName}` 保证同队列跨 key 原子（Cluster 同 slot）。
4. **三段式可靠性**：Lease（30s）→ Heartbeat（10s=Lease/3）→ Recovery（1min 扫过期）——替代旧实现"卡死 processing 永不恢复"。
5. **Ownership 三校验**（状态+WorkerID+Lease）防旧 Worker 越权提交；**限频**（atomic+sleep）防 Leader 压爆下游。
6. **LevelDB 只是临时 merge 缓存**（构建完 os.RemoveAll）——最终快照在 `t_generic_snapshot`（MySQL+Redis 双写）。
7. **ResSnapshot = 4 种 ConfigType × 4 种 payload**（BASIC/LEVEL/MAP_BASIC/MAP_LEVEL）；分片**动态均衡分配**（threshold 10000/片），增量只重建变化 shard（cloneSnapshot）。
8. **对账三差异**（Missing→补齐 / Redundant→清理 / Mismatch→重建）——Gen2 没有系统化对账，这是 fusion 的增量价值。
9. **修复全景**：bugfix/disorder = 16 工程问题 × 50 commit × 9657 行（7 周）——5 commit 核心演进（限频→Lease→空指针→TxPipeline→Lua）+ 7 附带 bug（LevelDB 复活/自指死循环/continue 反转/缓存即真相）——详见 §8。

## §2 定位与六模块

**定位**：上游发 Kafka binlog → fusion 消费生成快照 → broker 直读存储推送 agent（当前生产该 binlog 由 sdn_oss 的 VPC5.0 适配通道产出；目标态为 vpc-api-server——勘误见 00 §3）。为什么上游不能直接推：① 不认识母机（订阅关系在 agent 侧）② 配置需要快照（重启恢复）③ 需要对账（6 跳链路防漂移）。

| 模块 | 类型 | 职责 |
|---|---|---|
| initconfig | Leader-Worker | **两阶段**：Phase1 全局配置串行直拉（Schema/CtrlSet/Topic/ConsumerGroup）+ Phase2 业务配置按 VPC 分片并行；置 INITIALIZED → 触发 0 号快照 |
| message/binlog | Kafka 消费者 | 消费 binlog → 写 MySQL + Redis 聚合 → 通知 snapshot |
| snapshot | Leader-Worker | 构建快照（触发：1min 定时 / 数量 1000 / 周期 600s / 手动） |
| reconcile | Leader-Worker | 对账（全量每天 + 变动 5min）→ Missing/Redundant/Mismatch |
| cleanconfig | Leader-Worker | 清理已下线资源（定时 1h + DELETE 事件）→ 删快照/配置/状态/缓存/ResourceObj |
| vpcapi | HTTP 客户端 | 封装 api-server 调用（被 initconfig/reconcile 复用） |

**4 选举域参数**（position 口径）：Worker 并发 20/20/3/10；Worker 超时 5/2/10/10 分钟。
【口径差异】选举 Key 前缀两说：position 表 = `leader:election:{role}`（称来自 `leader_vote_dao_impl.go:15`）；task-queue 文档 = `fusion:leader:{role}`；failure-scenarios 注明"面试统一用 `leader:election:{role}`"（引 抗追问压力测试）——**采 `leader:election:` 前缀，差异保留登记**。

## §3 任务队列深挖（bugfix/disorder 主战场）

**为什么需要**：200 万 VPC 串行 = 55 小时 → Leader 只发布、多 Worker 并行（snapshot 默认并发 20）。

**Redis 4 种 Key**（以 snapshot 为例）：

```
task:queue:{snapshot}          # List（RPUSH/LPOP）
task:priority:{snapshot}       # ZSet（可选，高分优先）
task:data:{snapshot}:task-001  # String（PSETEX，24h TTL）
task:processing:{snapshot}     # ZSet，score = leaseExpireAt
```

- **Hash Tag**：`{snapshot}` → 同 slot（`CRC16("snapshot")%16384`）→ Lua 跨 key 原子（否则 CROSSSLOT）；按 queueName 而非 vpcId——同队列原子 + 不同队列并行。旧格式兼容：读双 key、写只用新。**（bugfix/disorder 分支设计；master 无 Hash Tag——见 §9 分支态注记）**
- **8 个 Lua 脚本**：publishTask / claimFromQueue / discardQueueHead / claimFromPriority / discardPriorityHead / saveTaskToProcessing / finishTask / requeueTask。
- **脚本设计深一层**（Lua 推断稿，材料自标"基于文档语义推断、非生产源码"）：① **Ownership 校验必须在 Lua 内**——GET 实时读+条件判断+原子改，消除"Go 读快照→Lua 写"的竞态窗口（finishTask 三重校验在脚本内）；② requeueTask 也带 lease 校验——防 Recovery 抢还在正常处理的慢任务；③ saveTaskToProcessing 带 workerID 校验（心跳续租不被抢）；④ claim 的 ARGV 传新 taskData=把 status 改 processing + 写 workerID + 刷 lease。
- **claimFromQueueScript**（最核心）：`LINDEX 检查队头 == 目标 → LPOP → PSETEX data → ZADD processing(score=leaseExpireAt)`；失败返回 0（队头被抢），Go 侧 3 次重试 + 数据消失则 discard 队头。
- **FetchTask 从 BLPop 改 LIndex+轮询**：BLPop 只能监听一个 key（新旧兼容要两个）/ 会删队头（claim 失败即丢）/ 阻塞期间无法做别的；代价 200ms 轮询延迟。

**旧实现三个致命问题**（bugfix/disorder 修掉）：

| 问题 | 机制 | 后果 |
|---|---|---|
| 非原子丢任务 | BLPop 取出后 GET data 失败（TTL 清）| 任务既不在 queue 也不在 data → 永久丢失 |
| 非原子重复消费 | BLPop+GET+ZADD 窗口 + Recovery 误判 | 两 Worker 同处理一任务 |
| 无 Lease 卡死 | Worker 崩溃后任务卡 processing | 该 VPC 配置**永不更新**直到人工清理 |

**三段式**：Lease 30s → Heartbeat 10s（LeaseTTL/3，一次失败还有 2 次机会）→ Recovery 1min（ZRANGEBYSCORE 扫过期 → RetryCount++ → requeue）。**崩溃恢复最坏界 ≈90s（30s 租约过期 + 60s 扫描）**。为什么不用 ZK：Gen3 完全去 ZK；Redis ZSet 的 score 天然支持时间范围查询。

**为什么任务队列不用 Kafka**（技术组件题库补遗）：Kafka 是**顺序流**（分区独占消费）——不支持"任意 Worker 抢单 + 单任务租约 + 优先级 + 延迟重入队"这四件事。框架定位差异：**Redis 是效率层（锁/队列），正确性在 DB/对账**。

**选举续约也要 Lua**（分布式深水区补遗）：`SetNX + JSON(NodeID/ElectedAt/ExpireAt)` + Lua 续约（**校验 node_id——防"给别人的锁续约"→脑裂**）；分区双主靠**幂等 + Recovery Requeue + 对账**兜底——"AP 系统的分区后果不是'出错'，是'多做一遍'"。**Fencing Token 未做**（pragmatic：TTL 秒级 + 快照/对账幂等 + workerID 校验 + 对账兜底）；要加 = epoch INCR + Lua 校验。选举参数两版本（10s/3.3s vs 15s/5s）——面试口径"秒级 TTL + TTL/3 续租"，精确值以仓库为准。

**Ownership**：Complete/Fail/Retry/Ack 前必查（status==processing + WorkerID 匹配 + Lease 未过期）——防"旧 Worker 复活越权"。

**限频**：Leader 单 goroutine，`atomic.Int32` 计数 + 达阈值（100/批）sleep（5s/10s）——发布失败不计数。

## §4 快照结构深挖

**先纠误**：LevelDB **不是**最终存储——只是合并 binlog 的临时缓存（`Release()` = Close + `os.RemoveAll`）；最终在 `t_generic_snapshot`（cache-client MySQL+Redis 双写），版本列表在 `t_generic_snapshot_version`。

**LevelDB 布局**：

```
key   = config:{shardId}:{identifier}
identifier = is_identifier 字段按 field_number 顺序 | 拼接
  eni:      uniq_vpc_id|uniq_eni_id → config:0:vpc-gu5knkob|eni-bmc37aa3
  vmRTable: uniq_vpc_id|vm_ip       → config:0:vpc-gu5knkob|10.0.1.9
  service:  uniq_vpc_id|proto|vip|vport
value = 单个业务对象 protobuf（不是整个 ResSnapshot）
目录  = {basePath}/snapshot/{configName}/{scopeId|timestamp}
扫描  = BytesPrefix("config:{shardId}:")
```

**ResSnapshot 结构**：version / config_name / resource_scope / owner / oneof payload {object | object_map}；**4 ConfigType 对应 4 种构造**：

| ConfigType | payload | 例子 |
|---|---|---|
| BASIC | Object.Payload（单 Any） | 单例配置 |
| LEVEL | Object.ItemList（Any 列表） | 列表配置 |
| MAP_BASIC | ObjectMap[key].Payload | eni（key=uniq_eni_id） |
| MAP_LEVEL | ObjectMap[key].ItemList | vmRTable（key=uniq_vpc_id） |

**分片**：**动态均衡分配**（非 hash）——`t_shard_counter` 按 config_count 升序找未满（threshold **10000**/片），全满则 max+1，无记录则 0。好处：自动均衡/扩容不动老片/可控单片大小；动因：Redis 单 value 限制 + 并行构建 + 增量效率 + broker 按需拉。

**版本管理**：SnapshotVersion{version/timestamp/total_size/sharding_keys}；保留 `MaxSnapshotCount = configMeta.SnapshotCount`（eni=10）——超限删旧快照 + 删 `≤oldVersion` 的 binlog；**每种配置独立版本号**（VM 变更不触发 subnet 重建）。

**触发**：`NOT(时间<SnapshotPeriod AND 变更<SnapshotBinlogCount)`（定时 1min / 数量 1000 / 周期 600s / 手动；动态配置，600s 是测试默认值）。**4 级队列**：High（手动）/ NormalGlobal / NormalVpc / NormalHost / NormalOwner。**分布式锁**：`snapshot:{configName}:{scopeId}`（TTL 10min）防并发构建。

**0 号 vs 增量**：0 号 = loadConfigFullData 全量写 LevelDB；增量 = 未加载 shard 从旧快照 loadFromSnapshot → mergeBinlog（INSERT/UPDATE→SaveConfig；**DELETE→DeleteConfig（db.Delete，非 Put(nil)——bugfix/disorder commit 430b5ab；master 尚未合入）**）→ **cloneSnapshot**（未变 shard 读旧版改版本号写回）。

**压缩**：NONE/GZIP/ZSTD/SNAPPY；智能压缩（<256B 不压）；**默认 SNAPPY**（`snapshot_config.go:24`，速度优先）。VPC 3345906：30 条 ≈12KB → SNAPPY 5-7KB / GZIP 3-5KB。

**HTTP API（broker 消费面）**：`/snapshot/versions`（版本列表）/ `/snapshot`（version=-1 取最新，按 sharding_keys 逐片读 + 合并）/ `/snapshot/build` / `/snapshot/rebuild`（skipVersions 修复污染）。

## §5 对账、清理与生命周期

- **reconcile（验证路径，不干扰执行者）**：LoadLeft（vpcapi 拉 API 期望态）vs LoadRight（快照+binlog）→ **两阶段 Diff**（阶段 1 左对右找 Missing+Mismatch；阶段 2 右对左找 Redundant——不合并，方向性不同）→ 流式比对防 OOM。
- **三类差异的处理**（interview 口径）：**Missing** → 报告，等下一周期自然恢复，**>2 周期升级告警**；**Redundant** → **先告警不自动删**（T0 DB 刚写入、T1 对账读到 → 误判 → 自动删=删合法配置——"宁可延迟，不可误删"）；**Mismatch** → 报告字段差异 + 下周期复查。修复走主路径 / cleanconfig 显式规则。
- **规模计算**：单 VPC ~61ms（读左 50ms + 读右 10ms + 比对 1ms）；10 Worker ≈164 VPC/s → ~3.4h；实际 3-9h（瓶颈 = Publish 限频 10 tasks/s）。
- **不影响生产四道防范**：低峰期（凌晨 2-4 点）/ 独立连接池限流 / 优先读 LevelDB / 增量间隔 60s。
- **降噪三件套**：多次确认（连续 N 周期）/ DiffHistoryStore 差异历史 / 数量阈值。
- **cleanconfig（执行者）**：定时 1h + DELETE 事件 → **六连删**（快照 / 版本 / 配置 / 状态 / Redis 缓存 / ResourceObj）+ 通知 api-server——"对账负责发现、cleanconfig 负责执行"。
- **消息管道（增量入口）**：Poll → 写 MySQL → 写 LevelDB → 按阈值触发快照 → CommitAsync（处理完才确认）；幂等 = version+scopeId + `ON DUPLICATE KEY UPDATE`；**攒批 1000 触发快照**；挂了 = "沉默的致命伤"（四类指标全中：新 VM 不通 / ACL 不生效 / 销毁后仍转发 / 跨 VPC 断）；恢复 = 积压消除 + **~40s**（快照 ~30s + 通知 ~5s + 下发 ~5s）。
- **initconfig**：InitState 四态（UNINITIALIZED → INITIALIZING → INITIALIZED → SNAPSHOT_AVAILABLE）；首启限频 200 万/100×5s ≈ **27.8h**（一次性事件，可接受）。
- 【口径差异】position 表述"对账触发补齐/清理/重建"；interview 口径"对账只报告、修复走主路径/显式规则"——**标【开放】**（倾向 interview 口径，与 Gen2 系列 16 章'对账=验证路径'一致）。
- **数据生命周期串讲**：创世纪（initconfig）→ 日常（message → snapshot）→ 监视（reconcile）→ 收尸（cleanconfig）。
- **LevelDB 降级**（38 组件补遗）：健康检查失败 → 快照/对账 DAO 降级**内存实现**（mem DAO）。
- **冷启动 vs Crash Recovery 判定**（分布式深水区补遗）：看 ConfigState 状态字段（MySQL 持久化）——UNKNOWN→冷启动全量；INITIALIZED/SNAPSHOT_AVAILABLE→crash recovery（看 processing lease）；INITIALIZING→看 task lease，过期则重初始化。"判断依据是持久化状态机，不是本地文件在不在。"
- **降级分级**（同补遗）：MySQL/Kafka = **P0 fail-stop**（失败比假成功安全）；Redis = P1 部分降级（binlog 消费继续、快照/对账暂停——"快了变慢了"）；组件（Leader/Worker/Broker/Agent）= 自动恢复。

## §6 故障矩阵（5 场景 × 4 承诺）

| 故障 | 创建即用 | 规则即生效 | 销毁即停止 | 业务不中断 |
|---|---|---|---|---|
| message/binlog 挂 | ❌ | ❌ | ❌ | ❌ |
| snapshot 挂 | ❌ | ❌ | ❌ | ❌ |
| 任务队列不可靠 | ⚠️ 延迟 | ⚠️ 延迟 | ⚠️ 延迟 | ⚠️ 间歇 |
| 快照合并 bug（DeleteConfig） | ❌ | ❌ | ❌ 复活 | ❌ 残留 |
| 初始化死循环 | ❌ 全停 | ❌ | ❌ | ❌ |

**结论**：fusion 是配置到数据面的唯一中转通道——它不可靠，4 类业务指标全受影响。

**故障全景（10 场景推演，failure-scenarios 补遗）**：

```
7 故障点：api-server / Kafka / fusion 进程 / Redis(任务队列) / MySQL / broker / tRPC / agent / DPDK
全局单点 3：Kafka（变更全停）/ Redis（任务队列停）/ MySQL（配置读写停）
```

| 故障 | 自动恢复 | 恢复时间 | 备注 |
|---|---|---|---|
| broker 挂 | agent 指数退避重连（1/2/4/8/10s cap）+ ReWatch 增量 | **5-10s** | 多副本；全挂则 seed fallback |
| fusion 进程挂 | watchdog（独立进程+systemd 两层）无限重启 | 1-2min | 重启→选举→Recovery |
| Redis 挂 | 双层健康检查 3×5s→NotReady；Leader TTL 15s 失效；恢复后连续 3 次成功+选举+Recovery | 故障+**2-3min** | 5min 不恢复需人工 |
| Kafka 积压 | 消费追平→阈值触发快照→通知→下发 | 积压消除+**40s** | 排查看 consumer lag |
| 网络分区 | TCP keepalive 30-60s 检测→重连→session Expired/TOO_SLOW→**全量恢复** | 1-2min | MaxPendingVersions=1000 驱逐未 ACK |
| MySQL 挂 | offset 不 commit→Kafka 重投；CDB 主备切换 | 故障+1-2min | |
| api-server 挂 | 多副本秒级切换；已有 VM 不受影响 | 秒级 | |
| DPDK 挂 | agent 重启→从 broker 拉全量重建 | 30s-几分钟 | |

组合故障：Redis+Kafka（叠加）；网络分区+broker（seed fallback 兜底，否则人工）；**MySQL+Redis 同挂=系统完全不可用（紧急人工）**。

## §7 与 Gen2 nfv-proxy 对比

| 维度 | Gen2 nfv-proxy | Gen3 vpc-fusion |
|---|---|---|
| 语言/调度 | Python 2.7 单线程 merge_pipe | Go Leader-Worker 多 Worker 并行 |
| 任务可靠性 | CKV pending/running 两阶段 | Lease+Heartbeat+Recovery+Ownership+限频+8 Lua |
| 快照 | CKV（保留 4 版、50000/页、无压缩、全量重写） | t_generic_snapshot（保留 10、10000/片动态、智能压缩、cloneSnapshot 增量） |
| 配置类型 | 32 种 MERGE_CLASS 硬编码 | 元数据驱动（105 口径，4 ConfigType 通用构造） |
| 对账/清理 | 无 / 无 | 独立 reconcile / cleanconfig |
| 下游 | agent 直连 ZK+CKV | broker 中转 + tRPC Stream |

## §8 修复全景：bugfix/disorder（16 工程问题 × 50 commit）

【实证】bugfix_disorder / core-fixes / accompanying-fixes 三篇全读（1395 行）：

**分支事实**：`vpc5.0/vpc-fusion` 分支 `bugfix/disorder`——**196 文件 / +9657/-2375 / 50 commit / 2026-04-02~05-19（7 周）**；作者 leoleochen 49 + Jinning Wu 1。
**"disorder" 的真正含义**：不是"消息乱序"（代码与 commit message 中从未出现该词）——是**分布式可靠性 bug 导致的"状态紊乱"集合**（丢任务/重复消费/卡死/快照污染/死循环）。

**核心修复演进（5 commit / 3 天）**：

```
Commit 1（4/10 12:13）任务限频（atomic+sleep）
  → 发现 Worker 崩溃后任务卡死
Commit 2（4/10 15:24）Lease+Heartbeat+Ownership（+923/-330，最大 commit）
  → 发现 C1 引入空指针（:= 变量遮蔽 + configState 首次为 nil）
Commit 3（4/10 15:48）修空指针（8 行）
  → 发现 TxPipeline 在 Redis Cluster 跨 slot EXECABORT
Commit 4（4/10 17:10）TxPipeline→Pipeline（临时方案，失去原子性）
  → 3 天后彻底解决
Commit 5（4/13 14:35）8 Lua + Hash Tag + Legacy 兼容 + 能力探测 + 双层健康检查（+744/-249）
```

> 演进本身就是方法论：**"发现问题 → 临时修复 → 彻底修复"**——不是一开始就设计 Lua 方案，而是实践中逐步暴露 Redis Cluster 的兼容性边界（EXECABORT / 跨 slot / Proxy 不支持 Lua）。

**16 个工程问题（完整表）**：

| # | 问题 | 根因 | 解法 |
|---|---|---|---|
| 1 | 非原子操作 | LPOP+GET+ZADD 三步非原子 | 8 个 Lua 脚本 |
| 2 | 任务丢失 | BLPop 取出后 data 被 TTL 清 | discardFetchedQueueHead 显式丢弃 |
| 3 | 重复消费 | 两 Worker 同拿 taskID | claimFromQueueScript 原子 claim |
| 4 | 卡死 processing | 无 lease，崩溃永不恢复 | Lease+Heartbeat+Recovery |
| 5 | Cluster 跨 slot | key 无 hash tag | `{queueName}` hash tag |
| 6 | 发布兼容 | 新格式发布瞬间旧任务丢失 | Legacy key 双写双读 |
| 7 | LevelDB 误用 | Put(key,nil) 不是删除 | db.Delete(key) |
| 8 | 初始化死循环 | OBJ 检查误用到 resourceObj 自身 | 跳过 resourceObj |
| 9 | 全局配置被跳过 | continue 写成 skip-process | 改为 if not GLOBAL |
| 10 | 任务洪泛 | Leader 一次发几千任务 | atomic + sleep 限频 |
| 11 | 健康检查不充分 | 只查 IsWritable | +IsTaskQueueCapable 双层 |
| 12 | 优雅停机丢任务 | ctx cancel 后终态写不了 | detached context 5s 超时 |
| 13 | 消息无限重试 | RetryIfFailed=true 无上限 | MaxRetryTimes 限制 |
| 14 | 阈值混用 | 重试次数与 broker 切换共用常量 | 拆为两个参数 |
| 15 | ResourceObj DB 压力 | 高频 MySQL 读 | 走 Redis（缓存即真相） |
| 16 | Redis 故障降级丢数据 | 降级 MySQL 会漏 Redis 变更 | 直接停止不降级 |

**附带 7 bug 精要**（B-G 组，独立生产 bug）：

- **LevelDB DELETE 复活**（commit 430b5ab，28 文件）：`Put(key,nil)` 只写空 value、key 仍在——遍历时"已删除配置复活"装回快照；同 commit 拆 **ConfigModel / InternalConfigModel 双接口**（内部配置无 `GetObjectId()`，防 panic）；补测试断言"删除后遍历必须为空"（此前只测了 Get 返回 nil，没测遍历——这是漏网根因）。
- **resourceObj 自指死循环**：检查者自身就是被检查对象（HR 检查"谁已入职"时发现自己没记录→删自己→再发任务）；`if configName != resourceObj` 跳过。
- **全局配置 continue 反转**：本意 `should-skip-check`（跳过 SET 校验）写成 `should-skip-process`（跳过整个处理）→ 全局配置永不回调 + 反复发 init task。
- **缓存即真相**（ResourceObj）：读写频率极高（四个 Leader 每分钟扫）→ 统一走 cache.Manager（写完立即可读，不等 DB binlog）；DAO 层 `validateConfigName` 防御——5 个写方法拒绝直接写 resourceObj（强制走缓存入口）。
- **限频参数**：init 每 100 个 sleep **5s**；reconcile 每 100 个 sleep **10s**（对账更重）。
- **14 个运维接口 + Web UI + 分表分页**：next_token = `tableIndex-startId`（当前表查满才推进下一张），兼容旧反向 token。
- **测试 +2900 行**：关键 `TestDistributedTask_007_LeaseFailover`——双 Worker + 取消 context（模拟崩溃）→ 验证 Recovery 接管同一任务。

**面试数据点**：9657 行 / 50 commit / 16 问题 / 8 Lua / 30s+10s / 7 周 / 200 万 VPC。

## §9 口径与开放

1. 选举 Key 前缀：`leader:election:` vs `fusion:leader:`——【开放】（倾向 `leader:election:`）。
2. **分支态注记（重要）**：DeleteConfig 修复（430b5ab）、Hash Tag、8 个 Lua 脚本、Lease/Heartbeat/Recovery 三段式、ownership 三重校验——均为 **bugfix/disorder 分支**产物，**master 未合入**；**master 现状（生产）**= Pipeline 为主 + 仅 3 个 Lua（优先级取出 / 锁释放 / 锁续约）+ 无 Hash Tag + Requeue 三步非原子 + CompleteTask 仅校验 taskID（concurrency-atomicity / fault-tolerance 口径）。本篇 §3 描述的是分支修复后的目标态（面试口径："我在 bugfix/disorder 上做的修复"）。
3. 选举参数：**裁定 10s/3.3s/30ms**（capacity-model / partition-splitbrain / state-machine 三源均引码 `leader_vote_service.go:303-313`）；15s/5s 记为部署覆盖口径【开放保留】。
4. 快照任务队列"4 级"（文档标题）vs 实列 5 个队列名（High + 4 Normal）——按名单引用。
5. Worker 并发/超时数字为 position 口径（20/20/3/10 并发；5/2/10/10 分钟超时）。

## §10 钩子

- broker 侧（三向合并/tRPC/Session）→ **03**；host-agent 消费 → **04**；bugfix/disorder 全量 16 问题 → **06**。
