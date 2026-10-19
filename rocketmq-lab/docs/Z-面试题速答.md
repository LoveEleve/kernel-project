# Z: RocketMQ 面试题速答（收束篇）

> 定位：rocketmq-lab 收束篇——把 A（生产使用）/B（扩展点）/D（核心原理）的结论面试化。
> 基线：**rocketmq-spring 2.3.0 + client 5.1.4**（xhs-project 口径）；源码坐标见各 D 篇。
> 题库锚点：手册 Ch22「消息中间件与可靠消费」12 题（278-289）；xhs 项目第 40 题「RocketMQ 深度拷打」。
> 用法：先自测（盖住答案），答不出的回对应文档。

---

## 一、速记卡（一页纸）

1. 四角色：**NameServer**（无状态 AP 路由，节点互不通信）/ **Broker**（Master brokerId=0 读写、Slave>0 冗余）/ Producer / Consumer。
2. 存储三件套：**CommitLog**（1GB 顺序追加，全 Topic 混部）→ 异步分发 **ConsumeQueue**（20B 定长：8B 偏移+4B 大小+8B tagsCode）→ **IndexFile**（Key 反查，500 万槽）。
3. 两个 msgId：**UNIQ_KEY**（Producer 生成，业务追踪）vs **offsetMsgId**（Broker 落盘生成，物理定位；事务消息靠它寻址半消息）。
4. 可靠性 2×2：刷盘 `flushDiskType`（默认 ASYNC_FLUSH，丢 500ms） × 复制 `brokerRole`（默认 ASYNC_MASTER，丢未同步）；两者都在 `MessageStoreConfig`。
5. Push 是**假推**：长轮询拉取（挂起 15s=`BROKER_SUSPEND_MAX_TIME_MILLIS`），5.x 另有 Pop（invisibleTime 60s，无队列绑定）。
6. 集群模式 = **at-least-once**：Rebalance 换 owner + offset 5s 批量持久化 → 重复窗口 ≤5s，消费必须幂等。
7. 消费失败：并发 `delayLevel = 3 + reconsumeTimes`（首次 10s），`retryMaxTimes=16` 次→`%DLQ%`；顺序**无限重试+毒丸堵队列**；广播**直接丢弃**。
8. 延时消息：写 `SCHEDULE_TOPIC_XXXX` 拦截重投，**固定 18 档**（1s~2h）；5.x 时间轮（`rmq_sys_wheel_timer`）才任意延时。
9. 顺序消息：`MessageQueueSelector` hash 路由 + 三层锁（Broker `LOCK_BATCH_MQ`=41 / 本地 `MessageQueueLock` / 队列消费锁）；全局顺序=单队列（牺牲并发）。
10. 事务消息：半消息（`RMQ_SYS_TRANS_HALF_TOPIC`，不可见）→ 本地事务 → 二阶段（还原 real topic + OP batch 标记）；未决时 Broker **30s 周期回查**，15 次上限（~7.5 分钟）后丢弃。
11. 消息删除只由**过期**驱动：`fileReservedTime=72h`，与是否消费无关；磁盘水位 75% 拒写 / 85% 强清 / 90% 告警。
12. 生产必配：`autoCreateTopicEnable=false`（防 Topic 元数据失控）；严格不丢 = SYNC_FLUSH + SYNC_MASTER + 幂等 + 72h 内消费完。

---

## 二、分组高频题

### A. 架构与路由

**A1. 为什么 RocketMQ 不用 ZooKeeper？**
NameServer 无状态、节点间**互不通信**，各自收 Broker 30s 全量重报；AP 取舍（路由可能短暂不一致，靠重报+客户端缓存+发送重试自愈）。ZK 的 CP（选主窗口不可路由）代价不值。回链：D2 §一。

**A2. Broker 注册和故障剔除的机制？**
注册即心跳：Broker 每 30s 向**所有** NameServer 全量重报（clamp [10s,60s]）。剔除两条路径：优雅关闭发 `UNREGISTER_BROKER`（104）秒级；宕机靠扫描（每 5s）+ 心跳超时 **120s** → 故障感知延迟最长约 2 分钟。回链：D2 §三/§四。

**A3. 客户端怎么找到 NameServer / 路由怎么刷新？**
静态配 `namesrvAddr` 或 HTTP 地址服务器（每 2min 拉取）；路由每 30s（`pollNameServerInterval`）刷新，客户端本地缓存。TBW102 回退：Topic 查不到且 Broker 开了自动创建 → 用 TBW102 模板路由。回链：D2 §五。

**A4. 为什么 Broker 宕机后 2 分钟内消息还能发？**
路由里还有死节点 → Producer 发送失败**重试换 Broker**（D3）兜底；Consumer 拉取失败由 Rebalance/重试掩盖。回链：D2 §六/D3 §4.3。

### B. Producer 发送

**B1. 三种发送方式怎么选？**
`send`（同步，等回执，1+2=3 次重试）；`sendAsync`（回调，失败回调内重试 2 次）；`sendOneway`（不管结果，不重试）。可靠性递减、吞吐递增。回链：D3 §二。

**B2. 超时是"每次 3s"还是"总共 3s"？**
**总预算制**：`timeout - costTime` 逐次扣减，3 次重试共享 `sendMsgTimeout=3000ms`，超了抛 `RemotingTooMuchRequestException`。回链：D3 §二。

**B3. SEND_OK 代表落盘吗？**
不。异步刷盘下 SEND_OK = 只进了 PageCache；`FLUSH_DISK_TIMEOUT / FLUSH_SLAVE_TIMEOUT / SLAVE_NOT_AVAILABLE` 是"不确定"（可能已写）——重发必重复，**发送侧和消费侧都要幂等**。回链：D3 §五。

**B4. 队列怎么选？故障规避是什么？**
默认轮询（`ThreadLocalIndex` 线程内自增取模）；`sendLatencyFaultEnable=true` 开启故障规避：按 Broker 历史延迟 7 档（50ms~15s）隔离病节点，默认**关**（引入不确定性）。重试带 `lastBrokerName` 避开上次失败的 Broker。回链：D3 §四。

**B5. 批量发送的约束？**
同 Topic、不支持延时、不支持 %RETRY%、waitStoreMsgOK 一致、总大小 ≤4MB；Broker 按一条存储，消费端拆开。回链：D3 §六。

### C. Broker 存储

**C1. 为什么 RocketMQ 快？**
写路径只有一次顺序 IO（CommitLog 追加 + mmap PageCache）；索引分发、刷盘、复制全部异步旁路；读路径 ConsumeQueue 条目小且连续，两次定位基本命中 PageCache。回链：D4 §一/§六。

**C2. 消息怎么读回来？offset 指什么？**
Consumer 按 `(topic, queueId, consumeOffset)` 读 ConsumeQueue 第 N 条（N×20B 直接定位）→ 拿 CommitLog 物理偏移读消息体。**消费 offset = ConsumeQueue 逻辑序号**，不是 CommitLog 物理偏移（后者在条目前 8B）。回链：D4 §六。

**C3. 宕机后怎么恢复？**
abort 文件判断退出方式：正常退出从倒数第 3 个 MappedFile 校验；异常退出定位最后一条完整消息（CRC），半截丢弃。恢复以 **ConsumeQueue 已分发位点**为基准对账，超出部分重新分发（天然幂等）。回链：D4 §七。

**C4. 消息什么时候被删？**
只按过期（`fileReservedTime=72h` + `deleteWhen="04"` 凌晨 4 点触发），与消费与否无关——**积压超 72h 的消息被物理删除，消费者永远读不到**。回链：D4 §八。

**C5. 主从复制与 Controller？**
Slave 主动连 Master 拉取增量 CommitLog；SYNC_MASTER 等 Slave 确认才返回。Slave 默认**不可读**（`slaveReadEnable=false`），核心价值是冗余；5.x Controller 模式（默认关）才有自动切主。回链：D4 §五/D1 §3.9。

### D. Consumer 消费

**D1. Push 和 Pull 的区别？**
Push = 长轮询 Pull 的封装（Broker 从不主动推）；Pull（`DefaultLitePullConsumer`）应用自控；Pop（5.x）无队列绑定 + invisibleTime，免 Rebalance 风暴。回链：D5 §一/§六。

**D2. Rebalance 什么时候触发？重复消费哪来的？**
触发：实例上下线、队列数变化、每 20s 定时兜底。重复消费三来源：**换 owner**（队列被收回）、**offset 5s 批量持久化**（重复窗口 ≤5s）、超时重投。回链：D5 §二/§五。

**D3. 消费失败怎么办？（三种模式）**
并发：`sendMessageBack` → `%RETRY%` 队列，delayLevel=3+reconsumeTimes 递增退避，16 次后进 DLQ；顺序：原地挂起重试（默认 1s；无限重试，默认 Integer.MAX_VALUE），毒丸堵队列；广播：log warn + **直接丢弃**。回链：D5 §四。

**D4. 消费积压/流控怎么防？**
ProcessQueue 三个闸门：`pullThresholdForQueue=1000` 条 / `pullThresholdSizeForQueue=100MB` / `consumeConcurrentlyMaxSpan=2000`——超阈值暂停拉取。回链：D5 §三。

**D5. offset 存哪？ConsumeFromWhere 能改吗？**
集群存 Broker（5s 批量上报），广播存本地文件。`ConsumeFromWhere` **只对新组生效一次**——已有 offset 后改它无效，要运维命令重置。回链：D5 §五。

### E. 事务消息

**E1. 事务消息完整流程？**
半消息（改写 topic=HALF，real topic/queueId 存属性）→ 本地事务 → 二阶段：COMMIT 还原到真实 topic + OP batch 标记 / ROLLBACK 仅标记 / UNKNOWN 等回查。回链：D6 §二。

**E2. 回查机制？**
Broker 每 30s 扫 HALF topic：needDiscard/needSkip（回查 ≥15 次 / 超保留期）→ checkImmunityTime（默认 6s 免疫窗）→ OP 判定（已决跳过）→ 向 ProducerGroup 任一在线实例 `checkLocalTransaction`。回链：D6 §四。

**E3. 回查为什么可能查不到？UNKNOWN 怎么处理？**
本地事务未提交/DB 不可用/超时；Producer 返回 UNKNOWN → Broker 下轮再查（有限次后丢弃+告警）。**查询异常应返回 UNKNOWN 让 Broker 再来**（xhs-project `OrderTransactionListener` 口径）。回链：D6 §三。

**E4. 事务消息 vs Outbox vs Seata？**
事务消息=Broker 内嵌 outbox（半消息+回查）；Outbox=业务 DB 表+看门狗（支持任意 MQ）；Seata AT=多 DB 跨表全局事务。单 DB+发消息用事务消息；跨库用 Seata；组合使用。回链：D6 §五。

**E5. OP batch 是什么？为什么需要？**
一条半消息完成 commit/rollback 后由 `TransactionalOpBatchService` **批量合并**写 OP topic——高吞吐下每秒上千条事务决策，单条写会压垮 Broker。回链：D6 §4.2。

### F. 延时 / 顺序 / 广播

**F1. 延时消息怎么实现的？有哪些档位？**
Broker 存储侧拦截：改写 topic=`SCHEDULE_TOPIC_XXXX`、queueId=delayLevel-1，到期由 `DeliverDelayedMessageTimerTask` 还原重投。**18 档**：1s/5s/10s/30s/1m/2m/3m/4m/5m/6m/7m/8m/9m/10m/20m/30m/1h/2h（level 16=30m）。精度 ≈100ms（扫描间隔），**不是任意延时**（5.x 时间轮才行）。回链：D7 §一。

**F2. 顺序消息怎么保证？**
发送侧 `MessageQueueSelector` 同业务键 hash 到同队列；消费侧三层锁：Broker 锁（`LOCK_BATCH_MQ`=41）、本地 `MessageQueueLock`、队列消费锁。**全局顺序=1 队列**（消费并发=1）。回链：D7 §二。

**F3. 顺序消息的毒丸问题？**
单条消息永远失败 → 队列挂起无限重试 → **堵死整条队列**。生产必须：业务 callback try-catch 降级（异常表+跳过）+ 单队列停滞监控。回链：D7 §2.3。

**F4. 广播和集群的本质差异？**
广播：每实例**全量**队列、offset 本地文件、失败**直接丢弃**、扩容不影响现有实例；集群：队列分摊、offset 存 Broker、失败重试进 DLQ。回链：D7 §三。

### G. Spring 集成与 5.x Proxy

**G1. rocketmq-spring 的消费模型？**
每个 `@RocketMQMessageListener` 一个 `DefaultRocketMQListenerContainer`（独立 `DefaultMQPushConsumer`）；注解默认 `consumeThreadMax=64`、`consumeThreadNumber=20`、`maxReconsumeTimes=-1`（并发沿用 Broker 16 次）。同组订阅必须一致。回链：D8 §三。

**G2. Spring 事务消息的坑？**
需要 `TransactionMQProducer` + `RocketMQLocalTransactionListener` **两个 Bean**；`TransactionMQProducer extends DefaultMQProducer` 会触发 starter 的 `@ConditionalOnMissingBean` 跳过 auto-config → 必须手动提供 `@Primary RocketMQTemplate`（A06 踩坑）。回链：D8 §四。

**G3. 5.x Proxy 是什么？与旧客户端的关系？**
Proxy = 无状态 gRPC 接入层（默认 8081）+ 兼容 remoting；LOCAL/CLUSTER（默认）两模式；客户端只连 Proxy，路由上移。v5 starter（`rocketmq-v5-client-spring-boot-starter`）与 2.x starter **不兼容**——endpoints 配置、gRPC 协议，迁移是架构重构不是配置替换。回链：D9 §二。

### H. 生产治理与对照（xhs-project）

**H1. 你们怎么防重复消费？**
`MessageIdempotentHelper`：Redis `SETNX(bizType:bizId)` + TTL，失败 `removeMark` 允许重投；配合 DB 唯一键（四层防线）。回链：D5 §四 + xhs 第 06 题。

**H2. 为什么不全用顺序消息？**
顺序依赖队列会牺牲并发；xhs 业务用**版本号**抗乱序（搜索侧 ES `ExternalGte` 拒绝旧版本覆盖；消费侧 `MessageIdempotentHelper` 24h TTL 兜底）——乱序无害化是架构选择。回链：D7 §2.3 + xhs 第 07 题。

**H3. 消息堆积怎么办？**
消费 lag 指标 + DLQ 积压规则；按"可丢/可重放/必须处理"三级治理；DLQ 归档≠删除（xhs 教训）。回链：D5 §四 + xhs 第 15 题。

**H4. RocketMQ vs Kafka vs RabbitMQ？（对照）**
Kafka：分区独立日志、吞吐最高、生态流处理；RabbitMQ：灵活路由、ACK 模型、吞吐中；RocketMQ：CommitLog 索引分离、顺序/事务/延时特性全、Java 生态。回链：D1 §3.3 + xhs 第 40 题。

---

## 三、陷阱速查（15 连）

| # | 反直觉点 | 正解 |
|---|---|---|
| 1 | Push 是 Broker 主动推 | **假推**——长轮询拉取（挂起 15s） |
| 2 | 集群模式消息只消费一次 | **at-least-once**（Rebalance/offset 窗口可重复） |
| 3 | Slave 可分担读 | `slaveReadEnable=false` 默认**不可读**，Slave 只做冗余 |
| 4 | 延时消息任意时长 | remoting 固定 **18 档**；5.x 时间轮才任意 |
| 5 | level 16 = 2h | level 16=**30m**；18=2h |
| 6 | 重试 16 次是 delayLevel=16 | `retryMaxTimes=16`（并发次数）；delayLevel=3+reconsumeTimes |
| 7 | 消息消费后删除 | 只按**过期**删（72h），与消费无关 |
| 8 | SEND_OK = 已落盘 | 异步刷盘只进 PageCache |
| 9 | ConsumeFromWhere 随时可改 | **只对新组生效一次** |
| 10 | offset = CommitLog 物理偏移 | offset = ConsumeQueue 逻辑序号 |
| 11 | 半消息消费者可见 | **不可见**（HALF topic 无订阅者） |
| 12 | 回查无限重试 | 15 次上限（~7.5min）后丢弃+告警 |
| 13 | 队列默认 8 | TopicConfig **16**（5.x）；autoCreate 模板才是 8 |
| 14 | 顺序消息保证全局有序 | 单队列有序；全局=1 队列（并发=1） |
| 15 | 广播失败会重试 | 广播失败**直接丢弃**（log warn） |

---

## 四、文档索引（回链）

| 主题 | 文档 |
|---|---|
| 概念全景 / 双 msgId / 存储三件套 / 可靠性开关 | D1 |
| NameServer 六表 / 注册 / 剔除 / 路由查询 | D2 |
| 三种发送 / 重试语义 / 队列选择 / 发送歧义 | D3 |
| CommitLog 格式 / 分发 / 刷盘 / 复制 / 恢复 / 清理 | D4 |
| 长轮询 / Rebalance / ProcessQueue / 失败处理 / Pop | D5 |
| 半消息 / 回查 / OP batch / 方案对比 | D6 |
| 18 级延时 / 时间轮 / 三层锁 / 广播全分 | D7 |
| Template / Listener 容器 / 事务桥 | D8 |
| 自动配置绑定 / Proxy / 双 starter | D9 |
| 生产使用端点（A01-A10）与扩展点（B01-B05） | a/ / b/ 源码 |
| 评审记录 | REVIEW-2026-10-05 |

---

## 五、一页纸收束

> **架构**：NameServer（AP 无状态路由）+ Broker（CommitLog 1GB 顺序写 + ConsumeQueue 20B 索引 + IndexFile）+ Producer/Consumer；注册即心跳（30s），故障剔除 120s+5s。
> **发送**：sync/async/oneway；总预算重试（1+2 次）；SEND_OK≠落盘；批量四约束。
> **存储**：一次顺序 IO + 异步分发；刷盘/复制双开关默认双双异步（丢 500ms/未同步）；72h 过期删；恢复按 ConsumeQueue 位点对账。
> **消费**：假推（长轮询 15s）+ Rebalance 20s；失败 3+reconsumeTimes→16 次→DLQ（顺序无限/广播丢弃）；offset 5s 批量上报。
> **高级**：事务=半消息+30s 回查（15 次上限）；延时=18 档拦截重投；顺序=hash+三层锁（毒丸堵队列）；广播=全分+本地 offset。
> **生产**：关 autoCreateTopicEnable；SYNC_FLUSH+SYNC_MASTER 保不丢；幂等+对账兜底（xhs：SETNX+版本号+DLQ 三级治理）。
