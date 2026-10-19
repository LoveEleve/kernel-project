# K — 组件深度拷打：RocketMQ

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：CommitLog/ConsumeQueue、刷盘与复制、顺序/事务/延时、重试与 DLQ、Rebalance、堆积治理
> 源码基线：rocketmq-all-5.1.4（`code/spring/rocketmq-5.1.4`）+ 本 lab D1-D9 锚点

---

## 问题

讲讲 RocketMQ 的架构和存储模型？顺序/事务/延时消息分别怎么实现？消息不丢怎么保证？堆积和死信怎么治理？

---

## 面试可讲版（五段式）

### ① 架构与存储（源码核心）

**四角色**：NameServer（无状态 AP 路由，节点互不通信）/ Broker（Master brokerId=0 读写、Slave>0 冗余）/ Producer / Consumer。

**存储三件套**（为什么快 = 一次顺序写 + 异步旁路）：
- **CommitLog**：消息本体，1GB/文件顺序追加（mmap + PageCache）——所有 Topic 混部，**写路径只有这一次 IO**
- **ConsumeQueue**：每队列 20B 定长索引（8B CommitLog 偏移 + 4B 大小 + 8B tagsCode）——`第 N 条 = N×20B` 直接定位
- **IndexFile**：Key → 消息哈希索引（500 万槽 + 2000 万条目）
- **异步分发**：ReputMessageService 单线程扫新写入 → 分发 ConsumeQueue/IndexFile（CommitLog 有、索引短暂滞后的窗口由此而来）

**可靠性 2×2**（`MessageStoreConfig`）：刷盘 `flushDiskType`（默认 ASYNC_FLUSH，丢 500ms）× 复制 `brokerRole`（默认 ASYNC_MASTER，丢未同步）——**默认组合=性能最高、可丢**。

### ② 消息类型（各自的内核机制）

| 类型 | 实现核心 |
|---|---|
| **顺序** | 发送端 `MessageQueueSelector` hash 到同队列；消费端三层锁（Broker `LOCK_BATCH_MQ`=41 / 本地 `MessageQueueLock` / 队列消费锁）；**单队列有序，全局顺序=1 队列** |
| **延时** | 写入时拦截改 topic=`SCHEDULE_TOPIC_XXXX`、queueId=delayLevel-1；到期 `DeliverDelayedMessageTimerTask` 还原重投——**固定 18 档**（1s~2h）；5.x 时间轮（`rmq_sys_wheel_timer`）才任意延时 |
| **事务** | 半消息（`RMQ_SYS_TRANS_HALF_TOPIC`，不可见）→ 本地事务 → 二阶段（COMMIT 还原真实 topic + OP batch 标记 / ROLLBACK 仅标记 / UNKNOWN 等回查）；Broker **30s 周期回查**（needDiscard ≥15 次 ≈7.5min 上限），checkImmunityTime 默认 6s |
| **重试/DLQ** | 消费失败 → `%RETRY%` 队列：`delayLevel = 3 + reconsumeTimes`（首次 10s），`retryMaxTimes=16` → `%DLQ%`；顺序消费**无限重试+毒丸堵队列**（默认 Integer.MAX_VALUE） |

### ③ 可靠性与幂等（生产口径）

- **发送侧**：SEND_OK ≠ 落盘（异步刷盘只进 PageCache）；`FLUSH_DISK_TIMEOUT` 等"不确定"状态重发必重复——**发送侧也要幂等**
- **Broker 侧**：严格不丢 = SYNC_FLUSH + SYNC_MASTER（性能代价）；`autoCreateTopicEnable=false`（生产必关）
- **消费侧**：at-least-once（Rebalance 换 owner + offset 5s 批量持久化 → 重复窗口 ≤5s）——**SETNX+DB 唯一键幂等**（本 lab dist-consistency D1）
- **兜底**：Outbox 补发（Broker 挂了消息不丢）+ 对账（dist-consistency D2/D3）

### ④ 拷打追问（面试官视角）

1. **"为什么不用 ZooKeeper？"** NameServer 无状态、节点互不通信（去中心化）；代价是路由短暂不一致（AP）——30s 重报+客户端缓存+发送重试自愈。
2. **"CommitLog 顺序写，消费怎么定位？"** ConsumeQueue 条目指向 CommitLog 物理偏移——先读索引（20B）再按偏移取本体；消费 offset 是**逻辑序号**不是物理偏移。
3. **"同步刷盘和同步复制怎么选？"** 刷盘管"单机断电不丢"（SYNC_FLUSH）；复制管"主机宕机不丢"（SYNC_MASTER）；都开=最稳最慢；xhs 生产用 SYNC_FLUSH + ASYNC 复制（RPO>0，演练验证过）。
4. **"半消息对消费者可见吗？"** 不可见（HALF topic 无订阅者）；COMMIT 后还原到真实 topic 才可见；ROLLBACK 只写 OP 标记（半消息物理上还在，靠 OP 跳过）。
5. **"回查为什么可能查不到？"** 本地事务未提交/DB 不可用/超时——Producer 返回 UNKNOWN 让 Broker 下轮再查（xhs `OrderTransactionListener` 口径：查询异常返回 UNKNOWN）；15 次上限后丢弃+告警。
6. **"重试会不会放大？"** 会——3+reconsumeTimes 退避 + 16 次上限 + DLQ；毒丸消息要有内容校验/配额，避免无限重试打爆（顺序消费尤其危险）。
7. **"同一个 group 加机器会怎样？"** Rebalance 重分配（20s 周期+触发式），期间队列换 owner + offset 提交窗口 → 重复消费——幂等必须覆盖。
8. **"怎么保证消费不丢？"** offset 提交时机（消费成功后）+ 业务幂等 + 对账；极端情况靠 MQ 保留期重放（72h 内）。

### ⑤ 话术

> "RocketMQ 四角色：NameServer 无状态路由，Broker 存储，Producer/Consumer。存储是 CommitLog 顺序写 + ConsumeQueue 20B 索引 + IndexFile 按 key 查；写一次顺序 IO、索引异步分发是性能来源。消息类型我们用了普通、延时、事务：关单用 delayLevel=16（30 分钟），下单用事务消息、回查异常返回 UNKNOWN。可靠性上 Broker 开 SYNC_FLUSH、关自动建 Topic；消费重试两档（3 次/5 次），幂等靠 Redis SETNX 加 DB 唯一键；顺序消息没用（业务用版本号抗乱序）。堆积和死信有 26 组监控和三级治理，Outbox 补发兜底 Broker 不可用。"

---

## Kafka vs RocketMQ 双轨对比（对齐 P02 规划的 10 主题）

> 参照：`docs/tmp-question/10-中间件/plan/P02-消息队列核心/PLAN.md`（Kafka 与 RocketMQ 是两种设计范式）。

| # | 主题 | Kafka | RocketMQ | 核心对比点 |
|---|---|---|---|---|
| 1 | 存储模型 | Partition 分段日志（.log/.index/.timeindex） | CommitLog 顺序写 + ConsumeQueue 索引 | Kafka 用文件系统能力；RocketMQ 用 mmap+索引 |
| 2 | 零拷贝 | sendfile（`transferTo`） | mmap（`MappedByteBuffer`） | OS 零拷贝 vs 堆外内存映射 |
| 3 | 复制机制 | ISR + High Watermark | 主从复制（DLedger=Raft 可选） | ISR 允许滞后副本；Raft 要多数派 |
| 4 | 消费者模型 | Coordinator 协调 Rebalance | Broker 端管理消费进度 | 客户端主动分配 vs Broker 分配 |
| 5 | 事务消息 | 幂等生产者 + 事务 Coordinator | 半消息 + 回查 | 通用分布式事务 vs MQ 特化 |
| 6 | 延迟消息 | 无原生（需自研/时间窗） | 原生 18 档 + 5.x 时间轮 | RocketMQ 更贴业务 |
| 7 | 顺序消息 | 单分区内有序（Key→分区） | MessageQueueSelector + 队列锁 | 实现不同、原理相通 |
| 8 | 元数据 | ZK（3.x 起 KRaft 去 ZK） | 自研 NameServer（无状态） | 轻量 vs 功能全 |
| 9 | 消息过滤 | 拉取后客户端过滤 | Broker 端 Tag/SQL92 过滤 | RocketMQ 省网络 |
| 10 | 消费进度 | HW 保证一致性 | ConsumeQueue offset 独立管理 | 设计哲学差异 |

**对比答题主线**（"RocketMQ 和 Kafka 的区别"）：从**存储模型切入**（日志模型 vs CommitLog 共用）→ 引出复制/消费模型/事务的差异——展示 tradeoff 思维而非 API 罗列。

**为什么 RocketMQ 的 CommitLog 所有 Topic 共用？** 顺序写 = 最快写路径；所有 Topic 混部顺序刷盘，读时靠 ConsumeQueue 索引定位——把"写放大"换成了"读定位"。

**为什么 RocketMQ 不用 Kafka 的 ISR？** ISR 粒度细（副本级跟踪）但语义复杂；RocketMQ 选择主从+刷盘策略的简单组合（强一致场景上 DLedger/Raft）——**简单可运维**优先。

---

## 设计哲学（反面假设表——"为什么不用另一种设计"）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **CommitLog 共用而非每 Topic 一个日志** | 每 Topic 独立文件 | 所有 Topic 混部顺序写 → 磁盘 IO 最大化；读靠索引 |
| **NameServer 无状态** | 像 ZK/Nacos 有状态（选举） | 无状态=不需要选举、任意重启；数据来自 Broker 30s 重报 |
| **延时消息 18 档而非任意时间** | 用户任意延迟 | 固定档位的调度开销可预测；任意时间需要持久化定时器（太重）→ 5.x 才用时间轮补齐 |
| **事务消息"半消息+回查"而非 2PC** | 先 prepare 再 commit | 避免 prepare 长期占资源；半消息对消费者不可见 + 回查兜底最终一致 |

---

## 生产陷阱（RocketMQ 5 条，对齐 P02）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **异步刷盘丢消息**（宕机丢 500ms） | 核心业务 SYNC_FLUSH 或 DLedger 集群 |
| 2 | **消费失败无限重试** | `maxReconsumeTimes` + 进 DLQ；毒丸识别 |
| 3 | **NameServer 路由延迟**（30s 拉取窗口） | 对路由敏感缩短间隔；发送重试兜底 |
| 4 | **顺序消费单线程瓶颈** | 拆细队列 + 非顺序依赖异步化；监控单队列停滞 |
| 5 | **事务消息回查压力** | 控制事务消息比例 + 回查逻辑优化（缓存状态） |

---

## 发散追问地图（横向）

- **对比**：Kafka（分区独立日志/ISR/吞吐）、Pulsar（存算分离）、RabbitMQ（路由/ACK）——选型维度。
- **存储**：CommitLog 文件回收/过期清理（72h）/磁盘水位（75% 拒写/85% 强清/90% 告警）。
- **高可用**：DLedger/Raft 自动切主（Controller 模式）；主从切换丢消息窗口。
- **消费**：push 假推（长轮询 15s）/pull/pop（5.x 无状态）；Rebalance 6 种算法；广播模式。
- **治理**：消息轨迹/死信 topic/按 msgId·key 查询；消费 lag 监控。

---

## 面试官评分点

**高级开发级**：能讲清存储三件套 + 三类消息用法 + 重试/DLQ 机制；知道自己项目的档位/次数。
**架构师加分**：UNKNOWN 回查语义；不追队列顺序而用版本号的取舍；SYNC_FLUSH/复制与 RPO 的关系；Rebalance 重复窗口与幂等的关系；Outbox 与事务消息的选型。
**危险信号**：说不清 CommitLog/ConsumeQueue；以为延时任意时长；顺序消息不谈并发取舍；重试无上限/无 DLQ；以为 SEND_OK=落盘。

---

## 本项目真实证据

**xhs-project**（27 个 `@RocketMQMessageListener`）：
- `maxReconsumeTimes` 18 组×3 / 8 组×5（按业务容忍分级）；`OrderTransactionListener:23-132`（execute/check/UNKNOWN）；`OrderService:90,307,947`（delay-level 16）；broker 加固（SYNC_FLUSH / autoCreateTopicEnable=false，DEPLOY-NOTES §二）；DLQ 26 组三级治理（第 15 题）。
- `MessageIdempotentHelper:36-81`（SETNX/removeMark）；`LocalMessageRetryJob`（退避 30s→480s + 死信 Gauge）。

**本 lab**（rocketmq-lab，5.1.4 基线）：
- D1-D9 源码锚点全部经 5.1.4 grep 核验（审校修 128 处）；Z 篇 15 陷阱；A/B 类端点（sca-lab 实测：发送/消费/顺序/延时/事务/重试 DLQ）。

---

## 版本与来源

rocketmq-all-5.1.4（tag，运行时对齐 xhs-project 的 client 5.1.4 / starter 2.3.0）；官方文档（存储/事务/延时/重试）；xhs 使用代码与部署笔记。

## 真实性说明

架构原理为标准知识（源码锚点已核验）；xhs 用法（消费者数/档位/重试次数/UNKNOWN/加固）均为代码与部署笔记事实。
