---
title: "01-什么是Kafka：日志模型"
series: "Kafka 核心与对照系列"
previous: "README.md"
next: "02-存储与复制.md（待写）"
source_refs:
  - "kafka 源码结构已核：clients（客户端）/core（broker 主体）/coordinator-common（消费组协调）"
cross_refs:
  - "RocketMQ 系列（01 术语/03 存储/04 消费——本篇逐块对照）"
---

# 01-什么是Kafka：日志模型

## §0 它干了什么（例子先行）

一条消息进 Kafka 的旅程（和 RocketMQ 对照着看）：

```
Producer → topic=orders, key=user_10001
  → 按 key 哈希进 Partition 2（topic 的物理分片）
  → 追加到 Partition 2 的"日志尾部"——得到编号 offset=8801
Consumer（消费组内某实例）→ 拉取 Partition 2 从自己的"书签"开始读
  → 读过的书签（offset）自己提交回 Kafka 的 __consumer_offsets 主题
```

- **它干了干什么**：**把消息存成"只追加的日志"**——每条消息一个递增编号（offset）；**消费者维护自己的书签**，而不是 broker 记"谁读到哪"。
- **本篇回答**：Kafka 四件套词汇是什么？和 RocketMQ 的术语怎么一一对照？

## §1 概念铺垫（What：四件套 + 对照表）

| 词 | 一句话 |
|---|---|
| **Topic** | 一类消息的逻辑集合（同 RocketMQ） |
| **Partition** | Topic 的物理分片——**每分区一条独立日志**（≈ RocketMQ 的 Queue） |
| **Offset** | 分区内消息的**递增编号**——读写的定位符（≈ RocketMQ 的 queueOffset） |
| **Consumer Group** | 消费组——**组内实例瓜分分区**，一个分区同一时刻只归组内一个实例（≈ RocketMQ 集群消费） |
| **Broker/Controller** | 节点与"主控"——分区有 leader/follower 副本（02 讲） |

**术语对照表**（本系列反复用）：

| 概念 | Kafka | RocketMQ | 差异要点 |
|---|---|---|---|
| 物理分片 | Partition | MessageQueue | Kafka 每分区**独立日志文件组**；RocketMQ 全 Topic **混写一个 CommitLog** |
| 进度 | **消费者自己提交**到 `__consumer_offsets` | Broker 存消费进度（每 5s 上报） | 谁记书签的架构差异（03 展开） |
| 消费模式 | **纯 Pull**（长轮询参数在客户端调） | Push（实为长轮询伪装） | Kafka 把背压交给消费者 |
| 顺序 | 分区内有序 | 单队列有序 | 同款边界 |
| 副本 | 分区级 leader/follower（ISR） | Master/Slave 整机级 | 复制粒度不同（02 展开） |

## §2 为什么"日志+书签"是更彻底的解耦（推导）

**朴素答案**：消息队列就该"broker 管投递、消费者收完 ack"。

**But**：broker 记每个消费者的进度=broker 状态膨胀；消费者挂久了进度怎么算？重放怎么重放？

**Therefore**：**把日志当真相、书签当消费者私事**——broker 只管追加（immutable log）；读进度是消费者自己提交的 offset。**副产品**：重放=书签往回拨；新消费组从最早/最新读=一行配置。差异在**进度归属**：Kafka 书签由消费者提交到 `__consumer_offsets`；RocketMQ 进度存 broker（04 系列"每 5s 上报"），其 `ConsumeFromWhere` 起点策略只对新组生效一次、之后全看已存进度。

**Cost**：消费者得管好"书签提交时机"（重复/丢失语义全在这——03/04 展开）。

## §3 掌握卡（2 条）

1. **四件套**：Topic→Partition（独立日志）→Offset（分区内编号）→Consumer Group（瓜分分区）——**offset 是消费者书签不是 broker 台账**。
2. **与 RocketMQ 的第一分界**：RocketMQ"一个 CommitLog 混写+ConsumeQueue 索引"；Kafka"每分区独立日志段"——**存储模型之别决定了后续一切差异**（02 深挖）。

## §4 边界（一句话翻篇）

- 分区数/副本数配置 → 02/06。
- 日志压缩（compact）→ 知道存在。
- KRaft（元数据自管，去 ZK）→ 06 一句。

## §5 Self-Test（3 题）

- Q1：Kafka 四件套各是什么？哪个和 RocketMQ 的 Queue 对应？
- Q2：消费进度存在哪？和 RocketMQ 谁记书签的差异？
- Q3："日志即真相"带来什么副产品？（重放/新组起点）

## §6 衔接

- 模型收口；下篇 02 存储与复制：日志段/稀疏索引 + ISR/HW/LEO——分区级复制的机制。
