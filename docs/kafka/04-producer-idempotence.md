---
title: "04-原理三：生产者与幂等"
series: "Kafka 核心与对照系列"
previous: "03-consumer-rebalance.md"
next: "05-spring-kafka.md（待写）"
source_refs:
  - "kafka clients 源码已核：ProducerConfig（ACKS_CONFIG:123/ENABLE_IDEMPOTENCE:334/TRANSACTIONAL_ID:351）；internals RecordAccumulator/Sender:80/TransactionManager"
---

# 04-原理三：生产者与幂等

## §0 它干了什么（例子先行）

两条 Kafka "不丢不重"的配置（工程最常拷贝的两行）：

```properties
enable.idempotence=true          # 幂等生产者：重试不产生重复
acks=all                         # 幂等前提——ProducerConfig:339 强制 acks=all
transactional.id=orders-tx-1     # 事务：把"写 Kafka + 提交 offset"绑成原子
```

- **它干了干什么**：producer.send() 之后**不直接发网络**——先进 RecordAccumulator 攒批，Sender 线程批量刷；幂等/事务在"重试会重复"和"跨分区原子"两个坑上兜底。
- **本篇回答**：攒批怎么工作？幂等靠什么原理？事务能绑什么、不能绑什么？

## §1 概念铺垫（What：三条流水线）

| 词 | 一句话 |
|---|---|
| **RecordAccumulator** | 按 (topic,partition) 攒批——一个 ProducerBatch（默认 16KB 或 linger.ms 到期） |
| **Sender 线程** | 生产者内部 IO 线程——**send() 是异步的**（返回 Future，回调在 IO 线程触发） |
| **幂等生产者** | `enable.idempotence=true`：broker 给每个 (producerId, partition) 记录序号——**重试的重复按序号去重** |
| **事务** | `transactional.id` 固定身份 + `beginTransaction/commitTransaction`——**跨分区原子写**（也能把消费 offset 绑进来） |

**攒批的收益**（推导）：单条消息一次网络往返 → 攒批后一次发 N 条 + 压缩一次——**吞吐大头在攒批**（`batch.size`/`linger.ms` 一对调优参数：攒得越大越省 RT、延迟越高——又一个两难）。

## §2 幂等与事务的边界（面试主问）

```
幂等（enable.idempotence）：解决"同一批重试导致的重复"
  → 粒度：单分区单调序号；防不了"逻辑重复"（如业务重发）
事务（transactional.id）：解决"多分区写要么全成要么全不成"
  → 事务消息：produce + sendOffsetsToTransaction（把读到的 offset 也提交进事务）
  → ⚠️ 边界：事务只能包"Kafka 内部状态"——**包不了 MySQL**（跨系统=RocketMQ 06 的事务消息那套，或本地消息表）
```

**vs RocketMQ 对照**（06 系列回收）：

| 维度 | Kafka | RocketMQ |
|---|---|---|
| 发送语义 | acks + 幂等 + 事务（Kafka 内） | 发送重试 + **半消息事务**（跨 DB） |
| 事务范围 | **Kafka 内跨分区原子** | **跨系统（DB+消息）**——06 系列两阶段回查 |
| 重试语义 | 客户端自动重试 + **broker 序号去重**（幂等） | 客户端同样自动重试，但**无 broker 序号去重**——重复靠消费端/业务幂等兜底 |

## §3 工程参数速查

| 参数 | 作用 |
|---|---|
| `acks` | 0/1/all——02 §1 已给与 ISR 关系 |
| `batch.size` / `linger.ms` | 攒批大小/等待时间（16KB/0ms 默认） |
| `compression.type` | lz4/zstd——攒批后压缩 |
| `max.in.flight.requests.per.connection` | 在途请求数——**>1 时只有幂等才保序** |
| `retries` / `delivery.timeout.ms` | 重试与总超时（幂等默认开重试） |

## §4 掌握卡（2 条）

1. **send 异步三段**：RecordAccumulator 攒批 → Sender 线程批量发 → 回调——**吞吐调优=攒批调优**（batch.size/linger.ms/compression）。
2. **幂等 vs 事务分工**：幂等防"重试重复"（单分区序号）；事务保"跨分区原子"（Kafka 内）——**跨 MySQL 不在事务能力圈**（那是 RocketMQ 半消息的领地）。

## §5 边界（一句话翻篇）

- 事务状态机/ProducerId 分配细节 → 用时查源码（TransactionManager.java）。
- 事务超时（transaction.timeout.ms）→ 知道默认 60s 即可。
- 事务消费隔离级别（read_committed）→ 05 使用篇一句。

## §6 Self-Test（3 题）

- Q1：send() 返回 Future 后消息在哪？攒批参数对是哪两个？
- Q2：幂等为什么要求 acks=all？防的是什么重复、防不了什么？
- Q3：事务能包 MySQL 吗？跨系统一致性用什么？（RocketMQ 半消息/本地消息表）

## §7 衔接

- 生产端收口；下篇 05 spring-kafka：使用线——KafkaTemplate/@KafkaListener + Polycache 用法兑现。
