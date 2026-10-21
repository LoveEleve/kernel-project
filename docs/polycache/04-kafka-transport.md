---
title: "04-Kafka传输：保序、背压与失败不复制"
series: "PolyCache 多机房复制系列"
previous: "03-write-filter-eval.md"
next: "05-语义重放.md"
source_refs:
  - "polycache-replicator .../producer/KafkaRedisCommandProducer.java（222 行，全文已读）"
  - "polycache-core .../command/RedisCommandMessage.java（key/keyBytes/source/timestamp/args）"
  - "polycache-redisson .../capture/RedisCommandCapture.java:311-317（promise 成功才发布）"
---

# 04-Kafka传输：保序、背压与失败不复制

## §0 它干了什么（例子先行）

一条 `SET user:1 alice` 被捕获后的旅程：

```
Netty 事件循环线程：onRedisCommand → 塞进 65536 容量的队列（塞不进就丢，绝不等）
专职线程 polycache-kafka-sender：从队列取 → kafkaTemplate.send(topic, "user:1", msg)
Kafka：key="user:1" → 固定分区 → 分区内保序
```

- **它干了什么**：把捕获的消息**异步、按 key 保序、失败不阻塞**地送进 Kafka。
- **本篇回答**：为什么不直接在监听器里 send？顺序靠什么？满了/失败了各怎么办？

## §1 掌握卡（4 条）

1. **监听器在事件循环上跑**——所以 `onRedisCommand` 只做一件事：`offer` 入队（非阻塞）；发送全交给专职守护线程 `polycache-kafka-sender`（守护线程=JVM 退出时不等它）。**在事件循环上做 Kafka send（等元数据——topic/分区分布信息）= 所有连接一起卡**。
2. **保序两件套**：单线程发送（出队顺序=入队顺序）+ Kafka **按 key 分区**（`partitionKey`：key 为字符串直接用，二进制 key Base64）——**同一个 Redis key 的命令永远同一分区，分区内有序**。
3. **队列满就丢**：`offer` 失败 → 丢弃 + `droppedMessageCount` 计数 + warn——绝不阻塞事件循环（旁路纪律，02 §4 同款）。
4. **失败不复制**在捕获侧兑现：`publishOnCompletion(promise)`——**命令执行成功（promise 无异常）才发布**；失败（如 WRONGTYPE）不进 Kafka，不污染对端。

## §2 为什么不直接 send（推导）

**朴素答案**：`listener = msg -> kafkaTemplate.send(...)`——五行走完。

**But**：RedisCommandListener 在 **Netty 事件循环线程**上被调用（Handler 的 `write()` 里）。Kafka send 首次要拉元数据、内部还要网络往返——同步等它，**这条连接上的所有命令全堵**；Kafka 抖一下，Redis 业务全线变慢。复制系统把主业务拖死，本末倒置。

**Therefore**：监听器只 `offer`（阻塞队列的非阻塞入队，O(1)）；专职单线程 `sendLoop`（poll 100ms 循环）做真正的 send——**事件循环只欠一个队列操作的时间**。

**Cost**：多一条线程 + 一份 65536 条的队列内存——用这点资源换"事件循环永不被复制拖累"。

## §3 顺序靠什么（两件套缺一不可）

- **单线程发送**：多线程消费同一队列，出队顺序就乱了——所以只有一个 sender。
- **Kafka key 分区**：`send(topic, partitionKey(message), message)`——`user:1` 的 SET→INCR→DEL 永远落同一分区；消费端按分区顺序拉（05 按分区提交偏移）。

**但保序是"分区内"的**：不同 key 之间无全局序（`user:1` 和 `user:2` 谁先到对端无所谓——它们不冲突，这正是按 key 分区的意义）。

## §4 三个失败点各怎么办

| 失败点 | 行为 | 出处 |
|---|---|---|
| 队列满（消费太慢） | offer 失败→丢+计数+warn | :132-136 |
| send 抛异常（同步段） | 丢+计数+warn | :181-184 |
| send 异步失败 | **仅 warn，无应用层重试**（依赖 Kafka producer 自身 retries 配置） | :172-175 |
| Redis 命令执行失败 | **根本不发布**（成功才复制；如 WRONGTYPE=类型不匹配类错误） | Capture :311-317 |

## §5 负面空间（本篇两笔）

1. **丢弃窗口**：对端消费持续跟不上时，新写命令被丢——对端落后且**不会自动补齐**（没有"落后时全量同步"路径；这是 Redis 原生复制有、PolyCache 没有的能力——08 收口对照）。
2. **异步 send 失败不重试**：告警后即弃——依赖 Kafka producer 的内部重试参数兜底；Kafka 侧彻底失败的写=这条复制永久缺失（人工/对账补）。

## §6 关闭语义（顺带）

`close()`：停收 → **把队列排干**（循环条件 `running || !queue.isEmpty()`）→ join 5s → 仍活就 interrupt；自建的 ProducerFactory 销毁（外部传入的不碰——所有权边界）。

## §7 边界（一句话翻篇）

- 编解码（RedisCommandMessageCodec）→ 05 传输线一起。
- source 标记的防回环用法 → 07。
- Kafka producer 参数调优（acks/retries）→ 部署时查。

## §8 Self-Test（4 题）

- Q1：为什么监听器不能直接 send？死法是什么？
- Q2：保序两件套各是什么？为什么不同 key 不需要全局序？
- Q3：队列满的取舍换回了什么？（事件循环无感 ↔ 复制丢失）
- Q4：哪类 Redis 命令失败根本不进 Kafka？（执行失败——promise 路径）

## §9 衔接

- 本篇回答"怎么送进 Kafka"；下篇 05 回答"对端怎么原样重放"（Consumer + Replayer + 宽松解码）。
