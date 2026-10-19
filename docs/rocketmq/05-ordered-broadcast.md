---
title: "05-顺序消息与广播"
series: "RocketMQ 系列"
previous: "04-consumer-rebalance.md"
next: "06-事务消息.md（待写）"
lab_refs:
  - "rocketmq-lab/docs/D7-延时顺序广播.md（§二~§四）"
source_refs:
  - "xhs cart CartService.java:744（userId 分区键 + asyncSendOrderly）"
  - "xhs cart CartSyncConsumer.java:40,58（ORDERLY + 屏障兜底）"
---

# 05-顺序消息与广播

## §0 它干了什么（例子先行）

购物车"加购→删除→清空"三连，必须按顺序吃：

```
发送：asyncSendOrderly(topic, msg, userId) — 同 userId hash 进同一队列
消费：ConsumeMode.ORDERLY — 同队列串行，清空一定在删除后
兜底：时间戳 CAS（比对时间戳：新才写，旧丢弃）+ clearBarrier（清空带时间水位线，来晚的旧动作丢弃）
```

- **它干了什么**：同业务键锁进同队列，消费端串行——顺序只保单队列内。
- **输入/输出**：输入=带分区键的消息；输出=同键有序的回调。
- **反例**：分区键取错（该取 orderId 取成 userId）→ 同订单动作打散多队列→顺序失效且**无报错**。

## §1 掌握卡（4 条）

1. **顺序 = 单队列内有序**：发送 hash 进同队 + 消费三层锁 + 串行吃——三件缺一即乱。
2. **全局 vs 分区**：全局（Topic 1 队列，全员闲置只剩 1 家干活）vs 分区（N 队列 + hash，同键有序、异键并发——xhs 用的）。
3. **缺陷三件套**：扩容改队列数打乱 hash / 吞吐=单队列吞吐 / 毒丸堵死队列（生产 try-catch+异常表+停滞报警）。
4. **广播 = 全分**：每实例拿全量 + 位点存本地 + 失败丢弃——配无副作用场景（配置/缓存），最大价值是扩容不掀 Rebalance 风暴。

## §2 发送侧：hash 进同队

```java
producer.send(msg, (mqs, msg, arg) -> {
    long orderId = (Long) arg;
    return mqs.get((int) (orderId % mqs.size()));  // hash(arg) % 队数（hash=散列：同键永远同余，进同队）
}, orderId);
```

xhs 版（`CartService.java:744`）：`asyncSendOrderly(CART_TOPIC+":"+action, trace包装的消息, String.valueOf(userId))`——以 **userId 为分区键**，顺带 `MqTraceHelper` 透传（Tracing 06 的活）。发送失败只打日志、不影响购物车主流程（看得出 MQ 在这里是旁路通知，不是主链）。

**坑**：arg 取错键→打散→无报错。Code Review 看顺序消息，第一眼看分区键取的对不对。

## §3 消费侧：三层锁

Broker 锁（`LOCK_BATCH_MQ`，队列→owner+时间戳）+ 本地 MessageQueueLock + 消费锁。续租 20s；Rebalance 抢占时旧 owner 让路。**连续锁代价**：持有期间队列分不走——消费者假死（心跳还在）则队列空等，必须监控。

## §4 全局 vs 分区 vs 毒丸

- 全局：Topic 1 写 1 读 + `MessageListenerOrderly`——多实例只 1 家干活，其余闲置。
- 分区：N 队列 + selector + N 消费者——最常用。
- 毒丸：永远失败的消息堵死整队（默认无限重试）。生产做法：callback 全 try-catch，异常记表 + 跳过（commit），不停；单队列停滞超阈值报警。

## §5 广播

```java
case BROADCASTING:
    mqSet = topicSubscribeInfoTable.get(topic);  // 全量，不分摊
```

| | 集群 | 广播 |
|---|---|---|
| 分配 | 实例分摊 | 每家全量 |
| 位点 | Broker 5s 上报 | 本地文件（`~/.rocketmq_offsets/…`） |
| 新实例 | 分走部分 | 拿全量，不影响旧家 |
| 失败 | 重试→DLQ | **丢弃**（一行 warn） |
| 配哪 | 任务分摊 | 配置刷新/缓存/内存同步 |

本地文件丢了（换机器）= 从头吃。**要写 DB 别用广播**，用集群+去重。

## §6 适用三句（`lab D7 §四`）

- 顺序：订单状态流转、binlog 回放——并发要求高的别用（吞吐≈单队）。
- 广播：配置推送、缓存刷新——任务只执行一次的别用。
- 延时：订单超时取消——等发令枪的（等条件满足才发的，如付成功才扣库存）用事务（07/06）。

## §7 边界（这里一句话翻篇）

- 延时实现 → 07；`MessageQueueSelector` 接口全参 → 用时查 `lab D7 §2.1`。
- 锁表字段/续租报文 → 04 回顾 + `lab D5`。

## §8 Self-Test（3 题）

- Q1：顺序三件套是？缺一件会怎样？
- Q2：分区键取错有什么现象？（无报错打散）怎么 Review 发现？
- Q3：广播和集群五个维度差异？广播文件丢了会怎样？

## §9 衔接

- 本篇回答"顺序/广播"；下篇 06 回答"事务"（半消息 + 回查 + Outbox 对比）。
