# D3 — Producer 发送机制

> **定位**：D1 概念、D2 路由已知，本篇回答"一条消息从 producer.send() 到 Broker 收下，中间发生了什么"。
> **深度**：架构原理级——发送链路 + 三种方式 + 队列选择双策略 + 重试语义 + SendResult 歧义。
> **源码依据**：rocketmq-all-5.1.4（`code/spring/rocketmq-5.1.4`，与运行时对齐），所有数字与类名经 grep 验证，见文末验证锚点。

---

## 一、发送链路全景

```
producer.send(msg)
   ▼
① Validators.checkMessage        — null/topic 合法/非禁发 Topic/body 非空/≤4MB
   ▼
② tryToFindTopicPublishInfo      — 本地缓存 → NameServer 拉取 → TBW102 回退(D2)
   ▼
③ 队列选择 selectOneMessageQueue  — 默认轮询; 开启 sendLatencyFaultEnable 走故障规避
   ▼
④ sendKernelImpl                 — 组装 SEND_MESSAGE_V2(310)/SEND_BATCH_MESSAGE(320)
   ▼                                请求, SendMessageHook 前后插桩
Netty 长连接 → Broker 10911
   ▼
⑤ Broker SendMessageProcessor    — 校验 → 写 CommitLog → 分发 ConsumeQueue/IndexFile(D4)
   ▼
⑥ SendResult { sendStatus, msgId(offsetMsgId), offsetMsgId, messageQueue }
```

`DefaultMQProducer` 只是门面，真正的状态在 `DefaultMQProducerImpl` + 进程级共享的 `MQClientInstance`（同 clientId 复用连接与线程池，见 D1 §3.10）。

---

## 二、三种发送方式

| 维度 | `send()` 同步 | `sendAsync()` 异步 | `sendOneway()` 单向 |
|---|---|---|---|
| 返回 | 阻塞等回执，得 SendResult | 立即返回，SendCallback 回调 | 立即返回，无回执 |
| 默认超时 | `sendMsgTimeout=3000ms` | 同 | 无意义 |
| 客户端重试 | **最多 1+2=3 次**（`retryTimesWhenSendFailed=2`） | 失败回调内重试 **2 次**（`retryTimesWhenSendAsyncFailed=2`） | **不重试** |
| 可靠性 | 最高 | 高 | 可能丢，无感知 |
| 吞吐 | 低 | 高 | 最高 |
| 适用 | 订单/支付等关键消息 | 高吞吐可异步确认 | 日志采集等可丢场景 |

同步重试的实现（`DefaultMQProducerImpl.java:714`）：`timesTotal = SYNC ? 1 + retryTimesWhenSendFailed : 1`——**异步和 oneway 不进这个重试循环**（异步的重试次数作为参数下沉到 remoting 层，失败回调里重试，见锚点 :995-1010）。

**超时是总预算制**：重试循环里每次都用 `timeout - costTime` 做剩余预算，累计耗时超 `sendMsgTimeout` 立即抛 `RemotingTooMuchRequestException`（:733-740，抛出点 :824）——3 次重试共享 3s 预算，不是每次 3s。

**不是所有异常都重试**（:758-805）：

| 异常 | 行为 |
|---|---|
| `MQClientException`（客户端侧，如序列化） | 重试（continue） |
| `RemotingException`（网络层） | 重试 + 更新故障规避记录 |
| `MQBrokerException`（Broker 业务错误） | **仅响应码在 retryResponseCodes 白名单才重试**（如 TOPIC_NOT_EXIST/FLUSH_DISK_TIMEOUT 类），否则直接抛 |
| `InterruptedException` | **不重试**，立即退出循环 |

**Producer 注册约束**：同一 `MQClientInstance`（同 clientId）内同一个 producerGroup 只能注册一次（`MQClientInstance.java:1015-1024`，putIfAbsent 冲突仅 warn+false）——同 JVM 内不要建两个同组 Producer。

**客户端心跳**：Producer/Consumer 都每 30s 向所有 Broker 发 HeartbeatData（`heartbeatBrokerInterval`，`ClientConfig.java:59`）——Broker 侧据此维护在线客户端视图（Consumer 侧的 Rebalance 输入，D5）。

---

## 三、发送前校验（Validators.checkMessage）

发送前客户端强制校验 6 项（`Validators.java:62-82`），任一不过直接抛 `MQClientException`，**根本不会发到网络**：

1. msg 非 null
2. topic 合法（字符集 + ≤127 字符）
3. topic **不在禁发名单**——`RMQ_SYS_SCHEDULE_TOPIC` / `RMQ_SYS_TRANS_HALF_TOPIC` 禁止直接发（`TopicValidator.java:59-60`）：延时和事务消息必须通过 delayLevel/事务 API 进入，不能手写系统 Topic
4. body 非 null
5. body 长度 > 0
6. body ≤ `maxMessageSize`（默认 4MB）

---

## 四、队列选择：两种策略

### 4.1 默认：轮询

`TopicPublishInfo.selectOneMessageQueue`（`TopicPublishInfo.java:32,75-76`）：`ThreadLocalIndex` 线程内自增取模——同一线程连续发送会**均匀打散到所有可写队列**。

### 4.2 可选：故障延迟规避（MQFaultStrategy）

`sendLatencyFaultEnable=true`（默认 false）开启后（`MQFaultStrategy.java:137-155`）：

1. 按 Broker 历史延迟分级——`latencyMax = {50, 100, 550, 1800, 3000, 5000, 15000}` ms 七档（:29）
2. 超档的 Broker 标记"不可用"对应时长——`notAvailableDuration = {0, 0, 2s, 5s, 6s, 10s, 30s}`（:30）
3. 选择顺序：优先"可用且不慢"的队列 → 退而求其次"可达"的 → 兜底轮询

**意义**：单 Broker 慢（GC/IO 抖动）时自动隔离，避免把流量继续打给病节点。默认关闭的原因是它引入不确定性——消息不再均匀分布。

### 4.3 重试时换 Broker

同步重试的队列选择带 `lastBrokerName` 参数：上次发哪个 Broker 失败了，下次**避开它**（`MQFaultStrategy` 的 brokerFilter，`DefaultMQProducerImpl.java:719-723`）。这就是 D2"Broker 宕机后路由还没剔除"期间的客户端兜底。

---

## 五、SendResult 四状态与"SEND_OK 歧义"

| SendStatus | 含义 | 业务该怎么看 |
|---|---|---|
| `SEND_OK` | Broker 已接收**并按当前配置完成可靠性动作** | 异步刷盘下 = 只进了 PageCache，**不等于落盘** |
| `FLUSH_DISK_TIMEOUT` | 同步刷盘超时 | 消息可能已写可能没写——**不确定** |
| `FLUSH_SLAVE_TIMEOUT` | 同步复制 Slave 超时 | 同上，**不确定** |
| `SLAVE_NOT_AVAILABLE` | 配置了同步复制但 Slave 不可用 | 同上，**不确定** |

**面试关键点**：后三种状态消息可能已实际写入，若业务盲目重发会产生重复——这正是"RocketMQ at-least-once、消费必须幂等"（D1 §3.13）在发送侧的镜像：**发送侧也可能重复，两端幂等缺一不可**。oneway 则连这个信号都没有。

---

## 六、批量发送

`send(Collection<Message>)` 打包为 `MessageBatch` 一次 RPC（`SEND_BATCH_MESSAGE=320`）。四个硬约束（`MessageBatch.java:42-62`）：

1. **同 Topic**——不同 Topic 直接抛异常
2. **不支持延时消息**（delayTimeLevel > 0 抛异常）
3. **不支持 %RETRY% Topic**
4. **waitStoreMsgOK 必须一致**

外加总大小同样受 4MB 限制。批量的本质是"多条消息编码进一个 body"，Broker 侧按一条存储、消费端再拆开（`PullAPIWrapper` 的 inner batch 解码，D5）。

---

## 七、两个容易忽略的发送细节

**消息压缩**：单条消息 body ≥ **4KB**（`compressMsgBodyOverHowmuch`）时客户端自动 zip 压缩并置 sysFlag 的 COMPRESSED_FLAG 位（`DefaultMQProducerImpl.java:1067-1079`）——这就是 D1 sysFlag 表里的"压缩"位的来源。**批量消息不压缩**（MessageBatch 直接 return false）。

**VIP channel**：Broker 主端口（10911）之外还有一个 VIP 端口（主端口−2 = 10909）。客户端 `vipChannelEnabled` 默认 **false**（系统属性 `com.rocketmq.sendMessageWithVIPChannel`，`ClientConfig.java:37,69`）——开启后发送走 VIP 端口。云厂商用它做隔离通道，自建集群通常不开。

## 八、Hook 扩展点

`SendMessageHook.sendMessageBefore / sendMessageAfter`（`DefaultMQProducerImpl.java:938,1032`）在发送前后插桩——消息轨迹（`enableMsgTrace`）就是用它实现的。B02 会自定义实现。

---

## 九、本篇要点（面试视角）

1. **同步发送默认最多试 3 次**（1+retryTimesWhenSendFailed=2），重试**换 Broker** 不换队列重发
2. **异步不重投主循环**——重试在失败回调里，默认 2 次；oneway 完全不重试
3. **SEND_OK ≠ 落盘**——异步刷盘下只代表进了 PageCache；三个 TIMEOUT/NOT_AVAILABLE 状态是"不确定"，重发必重复 → 幂等是两端义务
4. **禁发名单**：SCHEDULE/HALF 系统 Topic 客户端直接拦下，延时/事务必须走专用 API
5. **队列选择默认轮询**，故障规避（7 档延迟分级 + 最长 30s 隔离）默认关
6. **批量四约束**：同 Topic、无延时、无 RETRY、waitStoreMsgOK 一致

---

## 十、验证锚点

| 声明 | 源码位置 |
|---|---|
| sendMsgTimeout=3000 / retryTimesWhenSendFailed=2 / retryTimesWhenSendAsyncFailed=2 | `client/.../producer/DefaultMQProducer.java:103,115,122` |
| 同步 timesTotal=1+retry，异步/oneway=1 | `client/.../impl/producer/DefaultMQProducerImpl.java:714` |
| 异步重试下沉到 remoting 层回调（retryTimesWhenSendAsyncFailed 传入 sendMessage） | `DefaultMQProducerImpl.java:995-1010` |
| 重试避开上次 Broker（lastBrokerName/brokerFilter） | `DefaultMQProducerImpl.java:719-723` / `client/.../latency/MQFaultStrategy.java:137-155` |
| 超时总预算制（timeout-costTime） | `DefaultMQProducerImpl.java:733-740,824` |
| 压缩阈值 4KB + 批量不压缩 | `client/.../producer/DefaultMQProducer.java:108` / `DefaultMQProducerImpl.java:1067-1079` |
| VIP channel 默认关（系统属性开关） | `client/.../ClientConfig.java:37,69` |
| 重试异常分类（MQBrokerException 白名单/Interrupted 不重试） | `DefaultMQProducerImpl.java:758-805` |
| 同 clientId 同 group 只能注册一次 | `client/.../factory/MQClientInstance.java:1015-1024` |
| 客户端心跳 30s（heartbeatBrokerInterval） | `client/.../ClientConfig.java:59` |
| checkMessage 6 项校验 | `client/.../Validators.java:62-82` |
| 禁发 SCHEDULE/HALF Topic | `common/.../topic/TopicValidator.java:59-64,142` |
| 轮询 ThreadLocalIndex | `client/.../impl/producer/TopicPublishInfo.java:32,75-76` |
| latencyMax 7 档 / notAvailableDuration | `client/.../latency/MQFaultStrategy.java:29-30` |
| sendLatencyFaultEnable 默认 false | `MQFaultStrategy.java:27` / `DefaultMQProducer.java:1289` |
| SendStatus 四值 | `client/.../producer/SendStatus.java:20-23` |
| SEND_MESSAGE_V2=310 / SEND_BATCH_MESSAGE=320 | `remoting/.../protocol/RequestCode.java:167,193` |
| 批量四约束（同Topic/无延时/无RETRY/同waitStoreMsgOK） | `common/.../message/MessageBatch.java:42-62` |
| SendMessageHook 插桩点 | `DefaultMQProducerImpl.java:957,1067,1074` |
