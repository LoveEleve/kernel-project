---
title: "05-使用：spring-kafka"
series: "Kafka 核心与对照系列"
previous: "04-producer-idempotence.md"
next: "06-kafka-vs-rocketmq.md（待写）"
source_refs:
  - "polycache 实锤：KafkaRedisCommandProducer（KafkaTemplate:65/LinkedBlockingQueue:120/offer:132/send:169）；KafkaRedisCommandConsumer（KafkaMessageListenerContainer:60,76/DefaultErrorHandler+FixedBackOff:78）"
---

# 05-使用：spring-kafka

## §0 它干了什么（例子先行）

Polycache 的"Redis 变更事件→跨实例复制"就是 spring-kafka 写的（源码实锤）：

```java
// 生产端：Redis 事件监听器 → 异步发 Kafka（带背压队列）
public class KafkaRedisCommandProducer implements RedisCommandListener, AutoCloseable {
    private final KafkaTemplate<String, RedisCommandMessage> kafkaTemplate;  // :65
    private final LinkedBlockingQueue<RedisCommandMessage> sendingQueue;     // :120 queueCapacity
    if (!sendingQueue.offer(message)) { ... }   // :132 队满拒绝——背压
}
// 消费端：编程式容器（不用注解）+ 失败重试 3 次×1s 后放弃
private final KafkaMessageListenerContainer<...> container;                  // :60
container.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3))); // :78
```

- **它干了干什么**：spring-kafka = **Kafka 客户端的 Spring 封装**——`KafkaTemplate`（发送）+ `KafkaMessageListenerContainer`/`@KafkaListener`（消费）+ `DefaultErrorHandler`（重试）三件套。
- **本篇回答**：注解式/编程式两种消费怎么选？Polycache 这套写法给生产什么启示？

## §1 概念铺垫（What：三件套）

| 词 | 一句话 |
|---|---|
| **KafkaTemplate** | 发送门面——`send(topic,k,v)` 返回 `CompletableFuture`（04 的异步三段在此封装） |
| **@KafkaListener** | 注解式消费——容器工厂自动生成 container（**主流路径**） |
| **KafkaMessageListenerContainer** | 编程式容器——手动 new ContainerProperties+容器（Polycache 选它：**不依赖 Spring 容器管理生命周期**，自实现 AutoCloseable） |
| **DefaultErrorHandler** | 失败重试——`FixedBackOff(delay, maxAttempts)`；耗尽后走 DeadLetterPublishingRecoverer（死信）或跳过 |

## §2 两种消费写法对照

```java
// A. 注解式（90% 场景）
@KafkaListener(topics = "orders", groupId = "g1",
               containerFactory = "kafkaListenerContainerFactory")
void on(ConsumerRecord<String, OrderEvent> r) { ... }

// B. 编程式（Polycache 用法）：容器自建、生命周期自管
ContainerProperties cp = new ContainerProperties("topic");
KafkaMessageListenerContainer<K,V> c = new KafkaMessageListenerContainer<>(cf, cp);
c.start();   // AutoCloseable 里 stop()
```

**选型**：常规业务→注解式（配置集中在 containerFactory）；**嵌入式组件/自包含库**→编程式（Polycache 是库不是应用——不能假设宿主有 Spring 管理）。

## §3 Polycache 用法给生产的三个启示（兑现）

| 启示 | 实锤 |
|---|---|
| **发送侧背压**：应用内先入有界队列，队满拒绝而不是无限堆积内存 | `LinkedBlockingQueue(queueCapacity)` + `offer` :120/:132——**orm-pool 08"快借快还"同款哲学** |
| **消费失败分级**：重试 3 次×1s 后放弃——**别无限重试卡住分区** | `DefaultErrorHandler(FixedBackOff(1000L, 3))` :78 |
| **库形态不注解**：封装给别人的组件用编程式容器 | KafkaMessageListenerContainer :60 |

**与 RocketMQ 08 的 Template/Container 对照**：两边"发送门面+容器+错误处理"三件套结构同构——**换 MQ 换的主要是 API 语义，架构形状不变**。

## §4 工程参数速查

| 参数 | 说明 |
|---|---|
| `spring.kafka.consumer.group-id` / `auto-offset-reset` | 组与新组起点（01 §2） |
| `enable-auto-commit` | **默认 false（spring-kafka）**——容器代提交，语义安全 |
| `max-poll-records` | 单次 poll 拉几条——影响 03 的 max.poll.interval |
| `concurrency` | **一个 listener 开几个消费线程**（=分区数上限） |
| `acks` / `idempotence` | 透传 04 的参数 |

## §5 掌握卡（2 条）

1. **三件套**：KafkaTemplate 发送 / @KafkaListener（注解）或 Container（编程式）消费 / DefaultErrorHandler 分级重试——**与 RocketMQ Template/Container 同构**。
2. **Polycache 三启示**：有界队列背压、重试有上限、库用编程式——**组件化封装的通用答案**。

## §6 边界（一句话翻篇）

- 序列化（JsonDeserializer/Schema Registry）→ 用时查。
- 批量消费/BatchListener → 同族，翻篇。
- 死信主题（DLT）配置细节 → RocketMQ 07 的 DLQ 对照自推。

## §7 Self-Test（3 题）

- Q1：注解式与编程式怎么选？Polycache 为什么用编程式？
- Q2：FixedBackOff(1000L, 3) 语义？为什么重试要有上限？
- Q3：发送侧背压怎么做？（有界队列+offer 拒绝）

## §8 衔接

- 使用线收口；下篇 06 深水：Kafka vs RocketMQ 架构差异全景——xhs 为什么选 RocketMQ 不选 Kafka（L5-4 落点题）。
