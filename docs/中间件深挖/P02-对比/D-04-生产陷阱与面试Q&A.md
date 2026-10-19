# P02-对比 D-04 — 生产陷阱与面试 Q&A（收官篇）

> **定位**：P02 对比系列第 4 篇（收束）：**10 个生产陷阱 + 6 个面试题（含追问链）**。
> **用法**：陷阱当"生产手册"查；面试题当"自测脚本"背——每题都能回指具体深挖篇。
> **源码基线**：Kafka **4.0.0** / RocketMQ **5.1.4**

---

## 一、10 个生产陷阱

### Kafka（5 个）

| # | 陷阱 | 场景 | 根因 | 修复 | 深挖 |
|---|---|---|---|---|---|
| K1 | **Rebalance 风暴** | 消费者 GC 停顿 → 心跳超时 → 被踢 → 重入组循环 | `max.poll.interval.ms`（5min）< 单批处理时间；session/heartbeat 配比不当 | 调大 max.poll.interval / 调小 max.poll.records / CooperativeSticky / 静态成员（group.instance.id）/ KIP-848 | D-02 |
| K2 | **消息丢失（acks=0/1）** | Leader 宕机，消息未同步到 Follower | acks=1 只等本地写；acks=0 连响应都不等 | acks=all + min.insync.replicas=2 + unclean=false（三件套） | D-01/D-03 |
| K3 | **消息重复（at-least-once）** | 处理完未提交 offset 就宕机，重启重放 | 提交位移与业务处理非原子 | 幂等生产者（默认开）+ **业务幂等** + 事务 read_committed | D-01/D-04 |
| K4 | **__consumer_offsets 无限增长** | 大量废弃消费组 + 高频提交，compact 跟不上 | 位移日志清理滞后（offsets.retention.minutes 默认 **7 天**） | 清理无用组 + 调 retention + 监控 topic 大小 | 本文件 |
| K5 | **磁盘写满** | retention 配宽 + 消费积压 → 全盘只读 | retention.ms/bytes 未按容量规划 | 合理 retention + 分区/磁盘均衡 + 水位告警 | D-03 |

### RocketMQ（5 个）

| # | 陷阱 | 场景 | 根因 | 修复 | 深挖 |
|---|---|---|---|---|---|
| R1 | **消息丢失（异步刷盘）** | ASYNC_FLUSH + ASYNC_MASTER，Broker 宕机丢页缓存/未复制数据 | 默认两维都"异步"（性能优先） | SYNC_FLUSH + SYNC_MASTER（或 DLedger 多数派） | 对比 D-02 |
| R2 | **消费失败无限重试** | 业务异常 → 重试队列 → 一直重试 → 堆积 | 未设置重试上限/异常分类 | maxReconsumeTimes + 死信队列处理 + 消费幂等 | RQ D-05 |
| R3 | **NameServer 路由延迟** | 新建 Topic/Broker 变更后，客户端 30s 内仍用旧路由 | NameServer 无推送（定时拉取 30s）；autoCreateTopicEnable 误开掩盖问题 | 预创建 Topic + 发送重试兜底 + 多 NS + 监控注册间隔 | RQ D-02 |
| R4 | **顺序消息单线程瓶颈** | 一个队列一条慢消息卡住全队列 | ConsumeMessageOrderlyService 对队列加锁串行消费 | 拆细队列 + 异步化非保序操作 + 只对必需链路保序 | RQ D-05 |
| R5 | **事务消息超时回查性能退化** | 大量未决半消息 → 回查扫描 + 发送变慢 | 回查实现慢（查全表）/ 事务比例过高 | 回查只读走索引 + 控制事务消息比例 + 监控 HALF/CHECK_MAX 堆积 | 对比 D-03 |

---

## 二、6 个面试题（意图 + 主线 + 追问链）

### Q1 "Kafka 如何保证消息不丢失？"（意图：端到端可靠性）

**主线（三段式）**：
```
Producer：acks=all + retries（delivery.timeout 兜底）+ 幂等
Broker：  RF≥3 + min.insync.replicas=2 + unclean.leader.election.enable=false
Consumer：先处理后提交（enable.auto.commit=false）+ 业务幂等
```
**追问链**：acks=all 就够吗？→ 不够（min.insync=1 时 ISR 缩到 1 仍丢）→ 还要 unclean=false（防落后副本当选）。消费端呢？→ 重复而非丢失（at-least-once），靠幂等。

### Q2 "RocketMQ 和 Kafka 的区别？"（意图：对比思维，别只背 API）

**主线（从存储模型切入）**：
```
Kafka：日志即存储（每分区独立 segment）→ ISR+HW 复制 → 消费者组 Rebalance → 事务（协调器/EOS）
RocketMQ：CommitLog 全局顺序写 + ConsumeQueue 索引 → 刷盘×主从/DLedger → Broker 管进度 → 半消息+回查
```
**追问链**：为什么 RMQ 共用 CommitLog？→ 写永远顺序（吞吐）；索引挪到读侧。消费者模型差异？→ Kafka 客户端分配 vs RMQ Broker 负载。

### Q3 "Kafka 为什么快？"（意图：IO 原理）

**主线**：顺序写磁盘 + sendfile 零拷贝 + PageCache + 批量压缩 + 分区并行 + 稀疏索引。
**追问链**：零拷贝原理？→ `FileChannel.transferTo`（DMA 直接到网卡，免用户态拷贝，锚：PlaintextTransportLayer:213）。为什么不做 fsync？→ 副本兜底（单机持久化毁吞吐）。

### Q4 "RocketMQ 的 CommitLog 为什么所有 Topic 共用？"（意图：存储设计）

**主线**：顺序写 = 最快写路径；所有 Topic 混写一条日志 → 磁盘 IO 最大化；读时用 ConsumeQueue（20B 定长）定位物理偏移。
**追问链**：那随机读怎么办？→ ConsumeQueue 定长 O(1) + page cache 命中"最近写入"。过期删除粒度？→ 全局文件级（受最慢 Topic 影响）——生产要盯积压。

### Q5 "为什么用 ISR 而不是 Raft？"（意图：共识 tradeoff）

**主线**：ISR=动态"跟得上"集合（落后 30s 踢出，追上加回），确认粒度细（每条消息），可用性优先；Raft=固定多数派，一致性优先。
**追问链**：那可靠性怎么办？→ min.insync.replicas 兜底 + unclean=false。RocketMQ 呢？→ 默认主从（非 Raft），要自动切主才上 DLedger（Raft 多数派）。

### Q6 "如何保证消息顺序？"（意图：顺序消息实现）

**主线**：
```
Kafka：同 key → 同分区（客户端分区器）+ 单分区有序 + 幂等/单 in-flight 防重试乱序
RocketMQ：MessageQueueSelector 选队列 + MessageListenerOrderly 队列锁串行
```
**追问链**：重试会乱序吗？→ Kafka：关幂等+max.in.flight>1 会（保持幂等默认）；RMQ：顺序消费重试走本地重试队列（不换队列）。

---

## 三、系列自检清单（来自 P02 规划）

- [ ] 能手画 Kafka 一条消息 Produce→Consume 全生命周期（类 + 磁盘文件）
- [ ] 能手画 RocketMQ CommitLog→ConsumeQueue 双文件读写流程
- [ ] 能解释 ISR 的 HW/LEO 推进 + 异常场景数据丢失/不一致案例
- [ ] 能写出 Rebalance 完整协议交互（JoinGroup→SyncGroup→Heartbeat→LeaveGroup）
- [ ] 能对比 Kafka EOS 与 RMQ 事务消息差异 + 各自适用场景
- [ ] 能解释 `acks=all + min.insync.replicas=2` 为什么还不够（还需 unclean=false）

---

## 四、验证锚点（本篇新增）

| 声明 | 位置 |
|---|---|
| offsets.retention.minutes 默认 10080（7 天） | `group-coordinator/.../GroupCoordinatorConfig.java:118` |
| RocketMQ 顺序消费（队列锁） | `client/.../impl/consumer/ConsumeMessageOrderlyService.java:53` |
| RocketMQ 消费重试默认（-1 → Broker 16 次） | `client/.../consumer/DefaultMQPushConsumer.java:256`；Broker 侧默认 `remoting/.../protocol/subscription/SubscriptionGroupConfig.java:38`（retryMaxTimes=16） |
| Kafka 零拷贝 | `clients/.../common/network/PlaintextTransportLayer.java:213` |

> 其余锚点见各篇文末：P02-Kafka D-01~D-04 / P02-RocketMQ D-01~D-05 / P02-对比 D-01~D-03。

---

## 五、P02 系列完成度

```
docs/中间件深挖/
├── P02-Kafka/     D-01 生产者全链路 / D-02 消费者群组与Rebalance / D-03 日志存储与ISR复制 / D-04 事务与Exactly-Once  ✅
├── P02-RocketMQ/  D-01 CommitLog存储设计 / D-02 NameServer路由发现 / D-03 事务消息半消息回查 / D-04 5.x新特性 / D-05 消费端启动流程  ✅
└── P02-对比/      D-01 存储模型 / D-02 消息可靠性 / D-03 事务消息 / D-04 生产陷阱与面试Q&A  ✅
```
