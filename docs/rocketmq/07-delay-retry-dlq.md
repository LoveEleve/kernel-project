---
title: "07-延迟重试DLQ：时间换确定"
series: "RocketMQ 系列"
previous: "06-transaction.md"
next: "08-xhs生产对照收口.md（待写）"
lab_refs:
  - "rocketmq-lab/docs/D7-延时顺序广播.md（§一）"
  - "rocketmq-lab/docs/D5-Consumer-消费机制.md（§四§五，04 已讲）"
source_refs:
  - "xhs order OrderService.java:91（close.delay-level 默认 16）"
  - "xhs order OrderCloseConsumer.java:17（30 分钟后到达）"
---

# 07-延迟重试DLQ：时间换确定

## §0 它干了什么（例子先行）

下单未付，30 分钟后自动关单：

```
下单成功 → 发延时消息（delay-level=16 → 30m）→ 30 分钟后投递 → 关单消费
支付回调处理失败 → 重试（10s→30s→1m…递增）→ 16 次还失败 → DLQ（人工看）
```

- **它干了什么**：延时（到期再投）+ 重试（失败再试）+ DLQ（试尽存档）——拿时间换确定性。
- **输入/输出**：输入=level/重试策略；输出=到期投递/最终 DLQ。
- **本篇回答**：延时怎么存？精度多少？重试去哪？DLQ 是什么？

## §1 掌握卡（4 条）

1. **延时 = 存储侧拦截重投**：发送时改写进 SCHEDULE 系统 topic，定时任务到期还原回真实 topic——消费端无感。
2. **18 级固定 + 时间轮**：level 1~18（1s~2h）；精度 ~100ms（不是毫秒级）；5.x 时间轮秒级任意（xhs 不用，08 一句）。
3. **重试→DLQ**：并发 16 次延时递增→DLQ；顺序挂起无限；广播丢弃（04 §5 回顾）。
4. **位点回顾**：内存+5s 上报；积压超 72h 物理删（03 §8）——重试期间位点不推，堆积报警看这里。

## §2 延时机制

```
发送（delayLevel=级别 1~18，如 3）→ putMessage 前拦截：topic→SCHEDULE，queueId→级别-1
  → 真实信息藏属性 → 落盘 → SCHEDULE 索引
  → 每级一个定时任务扫：到期（storedTime+levelMs≤now）→ 还原 → 重走 putMessage
  → 未到期 → 100ms 后重查
```

18 级表（`MessageStoreConfig.java:219`）：1=1s 2=5s **3=10s** 4=30s 5=1m…16=30m 17=1h 18=2h。面试常考换算：**queueId = level-1**。

- **进度持久化**：每级 offset 存 `delayOffset.json`，重启接着来，不重投已到期。
- **精度**：无到期 100ms 重查——最差晚 100ms，**别拿它做毫秒级定时**。
- **积压**：同级堆积百万级，到期投递逐条走 putMessage——"延时"上再叠加排队延迟。拆 level 或换时间轮。

xhs 版（源码注释实证）：`order.close.delay-level:16`——注释原话"默认16=30min，测试设5=1min"（`OrderService.java:91,1058`）+ `syncSend(ORDER_CLOSE_TOPIC, …, delayLevel)` 发送 + `OrderCloseConsumer` 消费（"30 分钟后到达"）——超时关单标准用法。

## §3 时间轮一句（5.x）

`TimerMessageStore` + `rmq_sys_wheel_timer`：按时间槽批量投递，O(1)/条，秒级任意精度，1 个线程。xhs 在用 18 级（2.3.0 remoting），时间轮 08 一句。

## §4 重试 → DLQ（04 回顾 + 落点）

并发：`RECONSUME_LATER` → `%RETRY%` 主题 → level 递增（3=10s…18=2h）→ 16 次 → **DLQ**（死信队列：试尽的消息存档，人工介入）。DLQ 不是终点，是**人工队列**——08 讲 xhs 治理。

xhs 另有 `RetryBackoffUtils`（common/mq）：指数退避（每次翻倍：30s→60s→120s…封顶 480s）+ ±20% 随机抖动（jitter=随机加减一小段，防几十个 Job 同一秒齐步重试）——本地消息重试 Job 用（`LocalMessageRetryJob:160`）。Broker 重试管 MQ 投递，它管本地表扫——两层重试别混。

## §5 边界（这里一句话翻篇）

- 定时常量全表/空轮询优化 → `lab D7 §一`。
- DLQ 治理/幂等表 → 08。
- 位点运维命令 → 04 回顾。

## §6 Self-Test（3 题）

- Q1：延时消息存哪？到期谁还原？消费端要改吗？（不改）
- Q2：level16 是多久？queueId 和 level 什么关系？精度多少？
- Q3：并发失败 16 次后去哪？DLQ 是终点吗？两层重试（Broker vs 本地表）各管什么？

## §7 衔接

- 特性线收口（05→06→07）；下篇 08 生产对照（Template/Container/17 topics/治理/收口）。
