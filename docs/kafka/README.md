# Kafka 核心与对照系列

> 语料：**kafka 源码**（clients/core/coordinator-common/connect 全量——已核）
> 对照基线：**RocketMQ 系列**（已封卷——本系列大量"对照 RocketMQ"的视角）
> 实锤：Polycache 的 Kafka 传输用法（Polycache 04 已封卷——producer/consumer 代码在册）；xhs 业务侧**不用 Kafka**（L5-4 落点题：为什么选 RocketMQ 不选 Kafka）
> 诚实边界：**无 lab、无拷打文档**——本系列以"源码+对照+概念"为主，实验以概念推演替代

## 一、总表（7 篇：概念 → 原理 → 使用 → 对照 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 Kafka：日志模型](01-log-model.md) | **概念** | Topic/Partition/Offset/消费组 What；与 RocketMQ 术语对照 | 源码结构+RocketMQ 系列 |
| 02 | [原理一：存储与复制](02-storage-replication.md) | **原理** | 日志段/稀疏索引；ISR/HW/LEO | storage/core 源码 |
| 03 | [原理二：消费与重平衡](03-consumer-rebalance.md) | **原理** | 消费组/GroupCoordinator/重平衡/static membership | coordinator-common |
| 04 | [原理三：生产者与幂等](04-producer-idempotence.md) | **原理** | acks/batch/幂等生产者/事务 | clients 源码 |
| 05 | [使用：spring-kafka](05-spring-kafka.md) | **使用** | KafkaTemplate/@KafkaListener；Polycache 用法兑现 | polycache 代码 |
| 06 | [深水：Kafka vs RocketMQ](06-kafka-vs-rocketmq.md) | **深水** | 架构差异全景；xhs 为什么选 RocketMQ | 两系列对照 |
| 07 | [面试收口](07-interview-wrapup.md) | **面试** | 六篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03 → 04
使用：05
对照：06
收口：07
```

## 三、前置要求

- RocketMQ 系列（对照基准——术语/存储/消费/事务全在册）。
- Polycache 04（Kafka 作传输的实战语境）。

## 四、掌握分级

- **必须掌握**：分区日志/offset 模型；ISR 与 HW；消费组重平衡；acks 与幂等；与 RocketMQ 的架构差异。
- **理解思想**：日志即真相（offset 是消费者自己维护的"书签"）；ISR 是"同步副本集合"的动态协商。
- **知道即可**：KRaft（去 ZooKeeper）；Connect/Streams 生态；日志压缩。

## 五、边界

- ZooKeeper/KRaft 元数据管理细节 → 知道即可（ZK 系列已封卷）。
- Kafka Streams/Connect → 非本线。
- 部署运维（磁盘/网络调优）→ 翻篇。
