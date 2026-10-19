---
title: "03-原理二：消费与重平衡"
series: "Kafka 核心与对照系列"
previous: "02-storage-replication.md"
next: "04-生产者与幂等.md（待写）"
source_refs:
  - "kafka 源码已核：GroupCoordinator.scala (core/coordinator/group) + Group.java (coordinator-common) + MemberMetadata.scala (static membership)"
  - "RocketMQ 系列 03/04 已封卷（对照：消费进度归属 + 重平衡）"
---

# 03-原理二：消费与重平衡

## §0 它干了什么（例子先行）

10 个消费实例、3 个 Partition——怎么分？

```
① 每实例 = 消费组的一个 member（实例数 > 分区数 → 多余实例空闲）
② GroupCoordinator（运行在 broker 上）：聚合 member 的 join 请求 → 分配分区 → 回传
③ 分配算法由客户端 ConsumerGroup 指定（Range/RoundRobin/Sticky/CooperativeSticky）
```

突然实例掉了一个——**重平衡（rebalance）**：所有实例暂停消费、重新算分配。**Kafka 集群里最"疼"的事件**：每次 rebalance 一次全组停几秒→几十秒。

- **它干了干什么**：**消费组的成员管理与分区再分配**——"谁吃哪个分区"的活人调度。
- **本篇回答**：Coordinator 怎么管组？重平衡为什么疼？static membership 怎么把疼减轻一点？和 RocketMQ 的推/拉差异使成因不同？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **GroupCoordinator** | broker 侧的"组管理员"——收 join/leave/heartbeat，触发 rebalance |
| **Join/Sync 协议** | 两段：EagerRebalance（全停→全重算）/ IncrementalRebalance（CooperativeSticky，只动需要动的） |
| **Heartbeat / session timeout** | 心跳保活：超时 broker 认为实例死 → 触发 rebalance |
| **Static Membership** | 实例重启时用同一 `group.instance.id` 重新 join → **不触发重平衡**（MemberMetadata.scala：staticMemberId） |
| **Offset 提交** | 消费组在 `__consumer_offsets` 里 commit 进度——与 01 §1 的"书签"是同一事实 |

## §2 重平衡为何疼（逐步推导）

```
① 某 member 失联 → broker 等 session timeout（默认 10s）才判定 dead
② 判定 dead → 整组进入 rebalancing 状态（所有 member 停消费、等重新 join）
③ 全 group 等 heartbeat/timeout 才重新稳定 → 窗口期从几秒到分钟
④ Eager：所有分区先 revoke（即使没动）→ 读停；CooperativeSticky 才真正 narrowing 到"只动死的那个分区"
```

**→ 重平衡的本质**：以"组稳定"为前提的成员管理——任何成员变动都"牵一发而动全身"。02 系列 RocketMQ 04 的 Rebalance（clustering）也是同款，但 RocketMQ Broker 侧 push→pull 协议下 rebalance 在 broker、Kafka 由 GroupCoordinator 全权管理——**触发面比 RMQ 更集中**。

## §3 用法与调优（工程必修）

| 配置 | 默认 | 说明 |
|---|---|---|
| `max.poll.interval.ms` | 5min | 两次 poll 最大间隔——超时实例被踢；**挖（业务处理超时）**——务必在异步处理里维持心跳（常见坑） |
| `session.timeout.ms` | 45s（broker 45s） | 心跳断开判定；不宜过短 |
| `heartbeat.interval.ms` | session/3 | 心跳频率；别扎堆 |
| `partition.assignment.strategy` | Range,+Sticky/CooperativeSticky | **CooperativeSticky = 增量 rebalance**——强烈建议 |
| `group.instance.id` | - | 静态成员——实例 identity，重启不 rebalance |

## §4 与 RocketMQ 的对照（01/03 回收）

| 维度 | Kafka | RocketMQ |
|---|---|---|
| 进度存储 | `__consumer_offsets` 主题（消费者/组维度） | Broker 消费进度（5s 上报） |
| 重平衡位置 | GroupCoordinator（broker 侧，集中） | Broker push/pull 协作 |
| 协议语义 | Eager (全停) / CooperativeSticky (增量) | Rebalance 全组重算 |
| 心跳 | 客户端超时上报 + broker 判死 | broker push 超时踢 |
| 书签推进 | auto commit / manual committed | consumeSuccess queueOver（回传进度） |

## §5 掌握卡（2 条）

1. **重平衡频次** = 成员稳定度的函数；`max.poll.interval.ms` 超时是最常见诱因——**异步处理切记维持 poll/heartbeat**。
2. **CooperativeSticky + 静态成员**是把"停几秒→几十秒"缩到最小的两把钥匙——新项目这两项应默认配齐。

## §6 边界（一句话翻篇）

- Rebalance 协议内部（JoinGroup/SyncGroup 二轮）→ 翻篇。
- Exactly-once（事务性 `sendOffsetsToTransaction`）→ 04。
- Schema Registry/serde → 05。

## §7 Self-Test（3 题）

- Q1：`max.poll.interval.ms` 为什么超时？异步消费怎么避免？（heartbeat 维持）
- Q2：Eager vs CooperativeSticky 重平衡差异？
- Q3：Static Membership 解决什么？如何配？（group.instance.id）

## §8 衔接

- 消费与重平衡收口；下篇 04 生产者与幂等：acks/batch/幂等生产者/事务。
