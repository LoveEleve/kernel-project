# D5 — Consumer 消费机制

> **定位**：Push 长轮询循环 / Rebalance / offset / 并发 vs 顺序 vs 广播的失败处理。所有机制都是 D1~D4 的自然延伸。
> **深度**：架构原理级——每条数字、每个分支经 grep 验证。
> **源码依据**：rocketmq-all-5.1.4（`code/spring/rocketmq-5.1.4`，与运行时对齐），见文末验证锚点。

---

## 一、Push 单线程长轮询循环

```
DefaultMQPushConsumer (门面)
   │
   └─ MQClientInstance (JVM 单例, 同 clientId 共享)
        ├─ RebalanceService (每 20s + 触发式)  ← §二
        │     ↓ 计算本实例分配到的 Queue 集合
        │     ↓ updateProcessQueueTableInRebalance
        │     ↓ 新 Queue → 投 PullRequest 到 messageRequestQueue
        │
        └─ PullMessageService (单线程 take 循环)
              ↓ pullKernelImpl(queue, offset, 32, SUSPEND=15s)
              │  BROKER_SUSPEND_MAX_TIME_MILLIS=15s
              │  长轮询: Broker 无消息→挂起 15s→到期返回空→重拉
              │         : 有消息→即时返回
              │         : Reput dispatch 完成后 notifyMessageArriving→唤醒
              ↓
           拉到消息列表(最多 pullBatchSize=32, ≤ pullBatchSizeInBytes=256KB)
              ↓
           putMessage → ProcessQueue.msgTreeMap (TreeMap offset→MessageExt)
              ↓
           submitConsumeRequest → 消费线程池(20 线程)
              ↓
           MessageListener.consumeMessage(msgs)  回调业务
              ↓
           成功: offset 更新(内存) → 每 5s batch 持久化到 Broker
           失败: RECONSUME_LATER → sendMessageBack → %RETRY% topic(并发) / 本地 suspend(顺序)
              (细节见 §四)
```

**关键配置（DefaultMQPushConsumer）**：

| 参数 | 默认 | 作用 |
|---|---|---|
| `pullBatchSize` | **32** 条 | 单次拉取最大消息数 (:234) |
| `pullBatchSizeInBytes` | **256KB** | 单次拉取最大字节数 (:236) |
| `consumeMessageBatchMaxSize` | **1** 条 | 单次回调最大消息数——设为>1 触发批消费 (:229) |
| `pullInterval` | **0** | 拉取间隔——0=不等待连续拉 |

---

## 二、Rebalance：队列分配与锁模型

### 2.1 触发时机与执行方式

触发源三项：
- **主动**：实例上线/下线(Broker 心跳列表变化) → Broker 通知 Consumer 做 Rebalance
- **被动**：Topic 路由变更(队列数变化) → MQClientInstance 定时拉取发现
- **定时**：`RebalanceService` 每 **20s** 周期(`waitInterval=20000`)全量执行一次

执行方式：**每个 Consumer 实例独立计算**——获取该 Topic 的全量 Queue 列表 + 同 Group 的全体 clientId 列表，排序后过分配算法。实例间不协商。整个 Rebalance 结果就是一组"我该吃哪些队列"的 Decision。

### 2.2 三种模式下的分配差异

| | 集群(默认) | 广播 | 顺序 |
|---|---|---|---|
| 分配算法 | 6 种可选(平均/轮询/哈希/机房/配置/Sticky)，默认为 `AllocateMessageQueueAveragely` | 每个实例**拿全量** | 同集群，但需 extra：`lockAll()` 向 Broker 申请全局锁 |
| queue 范围 | `topicSubscribeInfoTable` 的子集(按实例数分摊) | 整个 subscribe info | 同集群 |
| 本地维护 | 每种分配到的 queue → `ProcessQueue` 对象 | 同(但全量) | 同 |
| 顺序额外 | — | — | `lockAll()` → Broker `LOCK_BATCH_MQ`(RequestCode=41) 申请全局锁 → 等待 lockTimeout 到期未续租→释放 |

**锁模型(顺序消费特有)**：Consumer 调用 `lockAll()`(RebalanceImpl:187) → Broker 锁表记录 `{clientId→{queue set→timestamp}}`，锁的有效期 = `lockMaxLiveTime`。Consumer 后台每 20s 续锁。若实例宕机锁到期，下个 20s 周期另一个实例可抢到锁。

**两个生产事故模式**：
1. **频繁 Rebalance**：集群内实例反复上下线 → 每 20s 全量重分配一次 → 队列持续换 owner → offset 批量持久化窗口(5s)内的消息全部重复消费
2. **广播'不影响'的副作用**：广播的 `updateProcessQueueTableInRebalance(topic, mqSet)` 拿 `topicSubscribeInfoTable` 的全量为 mqSet(不按实例分摊)——每次 Rebalance 队列全集不变，只是刷新 offset 位置

---

## 三、ProcessQueue：消息缓存的 TreeMap

`ProcessQueue`(每个分配到的队列一个实例) 维护两个 TreeMap：

| TreeMap | 用途 |
|---|---|
| `msgTreeMap` (TreeMap<Long, MessageExt>) | 主缓存：以 `queueOffset` 为 key，**所有拉取到但尚未消费的消息** |
| `consumingMsgOrderlyTreeMap` (TreeMap<Long, MessageExt>) | **仅顺序消费模式使用**：已提交给 Listener 处理但未完成的消息('consuming set') |

**核心操作**：
- `putMessage`(`:136-143`)：拉取到的消息 `msgTreeMap.put(queueOffset, msg)`——如果 key 已存在，根据异常配置看是否用新消息覆盖旧消息(默认抛异常)
- `takeMessages`(并发消费)：按 `consumeMessageBatchMaxSize` 从 `msgTreeMap` 头部取，清理对应的 TreeMap 条目
- `takeMessages`(顺序消费)：从 `msgTreeMap` 取一条 → 移入 `consumingMsgOrderlyTreeMap`——直到消费成功才从 consuming 集删除
- `makeMessageToConsumeAgain`(`rollback`)：消费失败 → `consumingMsgOrderlyTreeMap` 的条目回到 `msgTreeMap` 头部——不丢消息
- `cleanExpiredMsg`：对老旧未消费消息加防护（过期后后台清理）

**"拉取>消费"的流量控制**：`pullThresholdForQueue`=1000、`pullThresholdSizeForQueue`=100MB、`consumeConcurrentlyMaxSpan`=2000——这三个阈值都是对 ProcessQueue 中仍在 TreeMap 的消息行/大小/offset 跨度做限制。超了阈值就暂停拉取，等消费推进。

---

## 四、失败处理：三分支

| 维度 | 并发(默认) | 顺序 | 广播 |
|---|---|---|---|
| 回调方法 | `consumeMessage(msgs)` → 插桩 `ConsumeConcurrentlyContext` | `consumeMessage(msgs)` → 插桩 `ConsumeOrderlyContext` | 同并发(取决于设置) |
| 消费线程池 | 共享 20 线程池 | 单线程(同队列串行) | 共享 20 线程池 |
| 回调返回 | `CONSUME_SUCCESS` / `RECONSUME_LATER` | 没有 return value——异常/挂起即是重试 | 同并发 |
| 失败行为 | 单条: sendMessageBack → delayLevel = 3 + reconsumeTimes(首次重试=level3=10s), 循环递增。最多 **16** 次→DLQ | 整批: ProcessQueue rollback + 队列**挂起** 5s → 醒来后重新消费(无限循环，默认 Integer.MAX_VALUE) | 单条: log warn + **直接丢弃** |

**并发批量消费失败语义**：`consumeMessageBatchMaxSize > 1` 时一批多条一起回调。业务返回 `RECONSUME_LATER` 意味着**整批**每条消息都 `sendMessageBack`——即使批内一半都是 `CONSUME_SUCCESS` 语义，代码不区分。幂等判断应按条做。

**并发重试延时表映射**：各级 `delayLevel` 对应延时：

```
level 3→10s  4→30s  5→1m  6→2m ... 16→30m  17→1h  18→2h
```

默认 `messageDelayLevel` 共 **18 级**（1s~2h，`MessageStoreConfig:219`）——重试从 level 3 起跳（第 n 次重试 = level n+2），最多到 level 18（第 16 次重试，即 `retryMaxTimes=16` 上限）。

**顺序毒丸堵队列**：顺序消费失败时不返回 `RECONSUME_LATER` 而是**挂起后重试**（默认 **1s**，`suspendCurrentQueueTimeMillis=1000`，`DefaultMQPushConsumer:261`；返回 Context 可覆写）。一条消息永远失败 → 队列永远挂起 → 更新 `delayLevel` 递增直到达到 `maxReconsumeTimes`(默认 Integer.MAX_VALUE)。生产级必须为顺序消费者做超时兜底+异常表人工干预。

**广播丢弃的正当性**：广播的语义是"每个实例需收到消息做本地动作"——是内存缓存/配置刷新/临时本地数据库等无副作用操作。有 DB 写入需求的应用必须用集群 + 同 Consumer 处理去重，不能依赖广播。

---

## 五、offset 管理与起点

**集群 offset**：内存放 (`RemoteBrokerOffsetStore.mOffsetTable`) → 每个回调消费成功后更新→**每 5s**(`persistConsumerOffsetInterval=5000`) 批量上报 Broker。**如果有消息卡住未上报 offset 且恰好 Rebalance 换 owner→新 owner 从 5s 前的 offset 起拉→重复消费区间≤5s**。

**广播 offset**：本地文件 `$HOME/.rocketmq_offsets/{clientId}/{groupName}/offsets.json`(`LocalFileOffsetStore`)。

**消费起点**：新 ConsumerGroup 的 `ConsumeFromWhere`(D1 §3.7) 生效**仅一次**——首次启动时按策略设置 offset 位置；后续均从已记录的 offset 开始。已存在的 offset 无法靠 `ConsumeFromWhere` 重置——需要运维命令(admin 工具)或控制台重新设置。

---

## 六、Pop 模式（5.x 新模型）

为 Serverless/弹性场景设计(`PopMessageProcessor`)——**无队列绑定**：

- Consumer → `popAsync(queue, invisibleTime=60s)` → Broker 返回消息并设置不可见时间
- 不可见期间内消费成功 → ack；期间未 ack → 消息**自动重现**(无需 Rebalance)
- 经典 Pull 模型用 `BROKER_SUSPEND_MAX_TIME_MILLIS=15s`；Pop 用 `invisibleTime`，合法性区间 [**5s**, **300s**]
- **差异**：Pop 的队列不附属于任一个实例——弹性扩缩容不会引起传统 Rebalance 风暴

---

## 七、本篇要点

1. **Push=单线程长轮询 + 20 线程消费**——Broker 挂起 15s 不占 CPU，有消息即时返回
2. **ProcessQueue 三个闸门**：1000 条/100MB/2000 跨度——防客户端被拉爆
3. **Rebalance 各实例独立计算**——20s 定时 + Queue 变化触发——**换 owner + offset 5s 批量持久化 = 最多 5s 的重复窗口**
4. **顺序毒丸堵队列**——生产必须超时+人工补偿，不依赖无限重试
5. **广播=全分+本地 offset+失败丢弃**——适合无副作用操作
6. **Pop 无队列绑定+60s 不可见时间**——为弹性而生，免去 Rebalance 风暴

---

## 八、验证锚点

| 声明 | 源码位置 |
|---|---|
| PullMessageService 单线程 take 循环 | `client/.../consumer/PullMessageService.java:31,131` |
| 长轮询挂起 15s | `client/.../impl/consumer/DefaultMQPushConsumerImpl.java:113,482` |
| 拉取批次 32 条/256KB | `client/.../consumer/DefaultMQPushConsumer.java:234,237` |
| 消费线程池 20/20 | `DefaultMQPushConsumer.java:161,166` |
| 三道流控闸(1000/100MB/2000) | `DefaultMQPushConsumer.java:176,182,197` |
| Rebalance 定时 20s | `client/.../consumer/RebalanceService.java:25-27` |
| ProcessQueue 双 TreeMap | `client/.../impl/consumer/ProcessQueue.java:48,55` |
| takeMessages + putMessage | `ProcessQueue.java:131,303` |
| 顺序 Broker 锁 LOCK_BATCH_MQ | `broker/.../processor/AdminBrokerProcessor.java:240-242` |
| 顺序消费本地 MessageQueueLock + lockAll | `ConsumeMessageOrderlyService.java:63,218,224` |
| 并发消费失败 delayLevel=3+reconsumeTimes | `DefaultMQPushConsumerImpl.java:776` |
| broadcast 丢弃 | `ConsumeMessageConcurrentlyService.java:272-275` |
| pop invisibleTime 默认 60s [5s,300s] | `DefaultMQPushConsumerImpl.java:137-138,603-605` |
| offset 5s 持久化 | `client/.../ClientConfig.java:63` |
| offset 内存存储(mOffsetTable) | `client/.../consumer/store/RemoteBrokerOffsetStore.java` |
