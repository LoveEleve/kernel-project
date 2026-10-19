# D8 — rocketmq-spring 集成层

> **定位**：rocketmq-spring-boot-starter 2.3.0 如何在 Spring Boot 里封装 RocketMQ 的生产与消费——把 D1~D7 的原生 API 变成 Spring Bean + 注解 + Template。
> **深度**：架构原理级——Template 发送链路、Listener 容器生命周期、Auto-config 织入。所有方法签名与注解字段经 `git grep` 验证。
> **源码依据**：rocketmq-spring-all-2.3.0（`code/spring/extra/rocketmq-spring-2.3.0`，与运行时对齐）。

---

## 一、一句话概括

```
rocketmq-spring-boot-starter 2.3.0
  │
  └─ RocketMQAutoConfiguration: 读 spring.rocketmq.* → 创建 DefaultMQProducer/DefaultLitePullConsumer
     │
     ├─ RocketMQTemplate: 生产者——封装原生 producer.send() 为 sendSync/sendAsync/sendOneway,
     │   自动 convert payload → Message。Transactional 转发给 TransactionMQProducer。
     │
     └─ DefaultRocketMQListenerContainer: 消费者——每个 @RocketMQMessageListener 注解
         生成一个容器：创建 DefaultMQPushConsumer → subscribe(topic, selectorExpression) →
         registerMessageListener(RocketMQListener) → start() → 消费事件驱动。
```

---

## 二、RocketMQTemplate（生产者）

一个 Bean 管理所有消息发送。

### 2.1 常用方法（:474-676）

| 方法 | 同步/异步 | 说明 |
|---|---|---|
| `syncSend(destination, message)` | 同步 | 阻塞等 SendResult，默认超时 3s |
| `syncSend(destination, message, timeout)` | 同步 | 指定超时 |
| `asyncSend(destination, message, sendCallback)` | 异步 | 回调 `onSuccess / onException` |
| `sendOneway(destination, message)` | 单向 | 不关心结果（日志/监控事件） |
| `syncSendOrderly(destination, message, hashKey)` | 同步顺序 | `hashKey` → hash 路由选队列 |
| `sendMessageInTransaction(destination, message, arg)` | 事务 | 转发给 TransactionMQProducer（依赖 producer 为 TransactionMQProducer，见 §四） |
| `syncSendDelayTimeSeconds(destination, payload, delayTime)` | 延时 | 秒级延时（5.x 下走时间轮 `TIMER_DELAY_SEC`，:547） |
| `syncSendDelayTimeMills / syncSendDeliverTimeMills` | 延时 | 毫秒级、ISO 时刻延时方案，传 `TIMER_DELIVER_MS` |

所有不需要显式构造 `Message` 对象的调用——Template 用 `MessageConverter` 把 payload(任意对象) + headers → `TransportMessage/org.apache.rocketmq.common.message.MessageEx`。

### 2.2 底层 Producer 绑定（:163-173）

```java
// RocketMQAutoConfiguration.java:163-173
@Bean
public RocketMQTemplate rocketMQTemplate(RocketMQMessageConverter converter) {
    RocketMQTemplate tpl = new RocketMQTemplate();
    tpl.setProducer(producer);   // application.yml 的 spring.rocketmq.producer.* → DefaultMQProducer
    tpl.setConsumer(consumer);   // 可选 LitePullConsumer
    tpl.setMessageConverter(converter);
    return tpl;
}
```

多个 Template 的场景：自行声明 `RocketMQTemplate` Bean 并绑定各自的 Producer（2.3.0 无 `@RocketMQTemplate` 注解）。

### 2.3 MessageConverter 链

Template 的 `doConvertMessage` 调用 `RocketMQMessageConverter` 处理 payload→Message 转换：

1. 若 payload 已是 `Message` / `org.apache.rocketmq.common.message.Message` → 直传，不转换
2. 否则调用 Spring `MessageConverter`（默认 Jackson JSON）序列化对象 → `message.body` + headers

**自定义转换**：实现 `MessageConverter` 接口并注入 Template 即可。**两种格式不能混用在同一 ConsumerGroup 里**——下游消费端反序列化格式必须匹配。

### 2.4 Reply 消息（请求-响应模式）

RocketMQ 支持 `sendAndReceive` 同步请求-响应（RPC over MQ），Template 封装：

```java
T result = rocketMQTemplate.sendAndReceive(topic, requestMsg, OrderReply.class, timeout);
// 内部: producer.request(msg, timeout) → 消费者通过 @RocketMQMessageListener(replyTimeout=3000)
//   完成后把结果自动发回 request 指定的 reply topic
```

消费者的 reply topic 是自动生成的（`DefaultRocketMQListenerContainer` 内如果返回值不为 null，用一个 `AsyncSendCallback` 把 Consumer 收到的消息处理结束后的结果发回 request 指定的应答 Topic）。失败 :472,479 打日志（`Consumer replies message failed`）后丢失 request——不保证可靠。

---

## 三、DefaultRocketMQListenerContainer（消费者）

每个 `@RocketMQMessageListener` 注解生成一个独立消费者容器。

### 3.1 生命周期

```
① Spring 容器创建:  new DefaultRocketMQListenerContainer()
   解析注解参数(:263 setSelectorExpression)

② afterPropertiesSet 时:
   - initRocketMQPushConsumer()
     → 创建 DefaultMQPushConsumer(consumerGroup)  (:611)
     → subscription = consumer.subscribe(topic, selectorExpression)
     → consumer.registerMessageListener(listener)

   - Bean 配置阶段:
     consumer.setConsumeThreadMax(默认64, :645)
     consumer.setMaxReconsumeTimes(默认-1, :648)
     consumer.setMessageModel(见 annotation 的 messageModel 枚举)

③ start() 启动 listener container ⟶ consumer.start() ⟶ 拉取循环 + 分发到监听器

④ destroy() 关闭:
     consumer.shutdown()
```

**注释**：一个默认 `consumerGroup` 在本 JVM 内只创建一次对象——`MQClientInstance` 的下游共享发生在首次 `start()` 时，不是容器对象级。

### 3.2 @RocketMQMessageListener 常用参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `consumerGroup` | （必填） | 消费组名 |
| `topic` | （必填） | 订阅的 Topic |
| `selectorExpression` | `"*"` | Tag 过滤表达式(如 `"tag1||tag2"`)；或 SQL92 |
| `consumeThreadMax` | **64** | 最大消费线程池上限 |
| `consumeThreadNumber` | **20** | 核心消费线程数 |
| `maxReconsumeTimes` | **-1**（无限） | 并发消费最大重试次数；-1 沿用 Broker 默认 16 次 |
| `messageModel` | `CLUSTERING` | 消费模式(集群/广播) |
| `enableMsgTrace` | `false` | 是否开启消息轨迹 |
| `accessKey / secretKey` | 空（占位 `${rocketmq.consumer.access-key:}`） | ACL 鉴权 |

**禁止**：同 `consumerGroup` 的两个 @RocketMQMessageListener 订阅不同的 `selectorExpression`/`topic`（回 RE 到 D1 §3.8 的约束：同组订阅必须一致）。

### 3.3 错误处理

Listener 方法里触发异常时容器**吞掉异常，不传播**——消息自动进入%RETRY%队列（并发消费）或本地重试（顺序消费），行为同 D5 §四。Listener 自己 catch 后 `return` 是控制重试路径的唯一方法。**需要主动监控**：消费错误的唯一迹象是日志的 `error("Consumer replies message failed...")` 输出 + 消息进入 DLQ（D5），没有 Spring 异常调度回调。

---

## 四、RocketMQTransactionListener（事务消息桥）

`@RocketMQTransactionListener` 注解的 Bean 实现 `RocketMQLocalTransactionListener`（:21-24）两个方法：

```java
RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg);
RocketMQLocalTransactionState checkLocalTransaction(Message msg);
```

业务在 `executeLocalTransaction` 里执行本地事务，`checkLocalTransaction` 由 Broker 回查时调用（D6 §二）。事务 Producer 不是 `DefaultMQProducer`，而是 `TransactionMQProducer`——请在配置中注入特定 Bean 名，否则 Template 默认走的 `sendMessageInTransaction` 找不到 Producer 会空指针。这也是本 lab A06 验证时的关键检查点。

---

## 五、Auto-configuration 织入

**生效前提**：`spring.rocketmq.name-server` 配置不为空（条件 :53 + 断言 :95），同时 `rocketmq-spring-boot-starter` 在 classpath

**Bean 创建顺序**（最简模型）：

| Bean | 类型 | 场景 |
|---|---|---|
| `defaultMQProducer` | `DefaultMQProducer` | 暴露给 Template，group 从 `spring.rocketmq.producer.group` 取，"lazy" 模式 |
| `defaultLitePullConsumer` | `DefaultLitePullConsumer` | 用于有 `consumer` 配置需求的服务 |
| `rocketMQTemplate` | `RocketMQTemplate` | 绑定上述 producer/consumer 为默认 producer |
| `listenerContainer(每个@RocketMQMessageListener)` | `DefaultRocketMQListenerContainer` | `RocketMQMessageListenerContainerRegistrar` 扫描生成（`ListenerContainerConfiguration:31`） |

**等价于 yaml 示例**：
```yaml
rocketmq:
  name-server: 21.130.247.89:9876
  producer:
    group: rocketmq-lab-producer
```

实验室里接 D3 §一 的 DefaultMQProducerImpl 即可；Consumer 则用 listener 注解。

---

## 六、本篇要点

1. Template 给方法参数做对象转换+序列化→原生 Producer，**不需要手动 new Message 对象**
2. 每个 @RocketMQMessageListener = 一个独立的消费者容器；consumerGroup 决定 Rebalance 归属
3. `maxReconsumeTimes=-1` 的含义：并发走 Broker `retryMaxTimes=16`；顺序无限重试
4. **事务消息必须搭配 TransactionMQProducer + RocketMQLocalTransactionListener 两个 Bean**——不能只放 listener 忽略 Producer
5. **同组订阅必须一致**——D1 约束在 Spring 下依然生效，改 selectorExpression 需要换组名

---

## 七、验证锚点

| 声明 | 源码位置 |
|---|---|
| RocketMQTemplate 类 62-697 行 | `rocketmq-spring/.../core/RocketMQTemplate.java:62` |
| syncSend/asyncSend/syncSendOrderly/sendMessageInTransaction 方法签名 | `RocketMQTemplate.java:474-697` |
| DefaultRocketMQListenerContainer 生命周期(start/destroy) | `rocketmq-spring/.../support/DefaultRocketMQListenerContainer.java:71,317,337` |
| initRocketMQPushConsumer() | `DefaultRocketMQListenerContainer.java:611` |
| @RocketMQMessageListener 参数清单 | `rocketmq-spring/.../annotation/RocketMQMessageListener.java:45-128` |
| RocketMQAutoConfiguration 织入模板 | `rocketmq-spring/.../autoconfigure/RocketMQAutoConfiguration.java:58,163-173` |
| RocketMQLocalTransactionListener 接口 | `rocketmq-spring/.../core/RocketMQLocalTransactionListener.java:21-24` |
