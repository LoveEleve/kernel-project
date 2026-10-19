---
title: "02-生产发送：三种模型与队列选择"
series: "RocketMQ 系列"
previous: "01-why-mq.md"
next: "03-存储内核.md（待写）"
lab_refs:
  - "rocketmq-lab/docs/D3-Producer-发送机制.md"
---

# 02-生产发送：三种模型与队列选择

## §0 它干了什么（例子先行）

xhs 视角下三种模型各用在哪（举例，非逐行实证）：

```
关键消息同步发（如订单链路，阻塞等回执，3s 预算，失败换 Broker 再试；事务消息走专用 API，06 展开）
发券：asyncSend(COUPON_CLAIM_TOPIC) — 立即返回，回调里确认，高吞吐
日志：oneway 上报 — 发出去就不管，可丢
```

- **它干了什么**：按"丢不丢得起"选模型；选完队列、发出去、拿回执（或不拿）。
- **输入/输出**：输入=Message（topic+body+properties）；输出=SendResult（或回调，或无）。
- **本篇回答**：三种模型差在哪？超时怎么算？SEND_OK 等于落盘吗？队列怎么选？

## §1 掌握卡（4 条）

1. **三种模型**：同步（等回执，最多 3 次）/异步（回调确认，回调内重试 2 次）/单向（无回执，不重试，可丢）。
2. **超时是总预算**：3s 是 3 次重试共享的，不是每次 3s；`Interrupted` 不重试，Broker 业务错只白名单重试。
3. **SEND_OK ≠ 落盘**：异步刷盘下只代表进了 PageCache；三个超时/不可用状态是"不确定"——重发必重复，幂等是两端义务。
4. **队列选择**：默认轮询打散；可选故障规避（7 档延迟隔离，默认关）；重试必换 Broker。

## §2 链路全景（6 步）

```
producer.send(msg)
  ① Validators.checkMessage — 6 项校验（§4），不过直接抛，不上网
  ② tryToFindTopicPublishInfo — 本地缓存 → NameServer 拉 → TBW102 回退（自动建 topic 的模板，01 说过生产必关）
  ③ selectOneMessageQueue — 轮询 / 故障规避（§5）
  ④ sendKernelImpl — 组装请求（310/320），Hook 前后插桩（§8）
  Netty 长连接 → Broker 10911
  ⑤ Broker SendMessageProcessor — 校验 → 写 CommitLog → 分发（03 讲）
  ⑥ SendResult{sendStatus, msgId, offsetMsgId, messageQueue}
```

`DefaultMQProducer` 只是门面（Facade：对外一个类，活全在实现类）；状态在 `DefaultMQProducerImpl` + 进程共享的 `MQClientInstance`（同 clientId 复用连接线程池）。

## §3 三种发送

| 维度 | `send()` 同步 | `sendAsync()` 异步 | `sendOneway()` 单向 |
|---|---|---|---|
| 返回 | 阻塞等回执 | 立即返回，回调确认 | 立即返回，无回执 |
| 默认超时 | `sendMsgTimeout=3000ms` | 同 | 无意义 |
| 客户端重试 | **1+2=3 次** | 失败回调内 **2 次** | **不重试** |
| 适用 | 订单/支付关键消息 | 高吞吐可异步确认 | 日志采集可丢场景 |

三句关键：

- **同步重试**（`DefaultMQProducerImpl.java:714`）：`timesTotal = 1 + retryTimesWhenSendFailed`——异步/oneway 不进这个循环（异步重试下沉到 remoting 回调）。
- **超时总预算**（:733-740）：每次用 `timeout - costTime` 做剩余预算——3 次共享 3s，超了抛 `RemotingTooMuchRequestException`。
- **不是所有异常都重试**（:758-805）：客户端错/网络错重试；Broker 业务错只白名单码重试；`Interrupted` 直接出循环。

附带两约束：同 clientId 同 producerGroup 只注册一次（`MQClientInstance.java:1015-1024`，putIfAbsent）；Producer/Consumer 每 30s 向所有 Broker 心跳（`ClientConfig.java:59`，Broker 维护在线视图——04 重平衡的输入）。

## §4 发送前校验（6 项，不过不上网）

`Validators.java:62-82`：msg 非 null → topic 合法（≤127 字符）→ **不在禁发名单** → body 非 null/非空/≤4MB。

禁发名单（`TopicValidator.java:59-60`）：`RMQ_SYS_SCHEDULE_TOPIC` / `RMQ_SYS_TRANS_HALF_TOPIC`——延时和事务必须走专用 API，不能手写系统 Topic（06/07 讲为什么）。

## §5 队列选择

**默认轮询**（`TopicPublishInfo.java:32,75-76`）：`ThreadLocalIndex` 线程内自增取模——同线程连续发，均匀打散。

**可选故障规避**（`sendLatencyFaultEnable=true`，默认 false）：按 Broker 历史延迟 7 档分级（50→15000ms），超档隔离 0s→30s（`MQFaultStrategy.java:29-30`）。单 Broker 慢（GC/IO 抖动）自动隔离——代价是分布不再均匀，所以默认关。

**重试换 Broker**（:719-723）：同步重试带 `lastBrokerName`，下次避开上次失败的 Broker——NameServer 剔除宕机 Broker 之前的客户端兜底（04 讲剔除延迟）。

## §6 SendResult：SEND_OK 的歧义

| SendStatus | 含义 | 业务视角 |
|---|---|---|
| `SEND_OK` | 按当前配置完成可靠性动作 | 异步刷盘下 = 只进 PageCache，**≠落盘** |
| `FLUSH_DISK_TIMEOUT` | 同步刷盘超时 | 可能写了可能没写——**不确定** |
| `FLUSH_SLAVE_TIMEOUT` | 同步复制超时 | 同上 |
| `SLAVE_NOT_AVAILABLE` | Slave 不可用 | 同上 |

后三种重发必重复——"at-least-once（至少一次：可能重复）、消费必须幂等"在发送侧的镜像：**发送侧也可能重复，两端幂等缺一不可**。oneway 连这个信号都没有。

## §7 批量 + 两个细节

批量（`MessageBatch.java:42-62`，一次 RPC 320）：同 Topic / 无延时 / 无 RETRY / `waitStoreMsgOK` 一致 + 总 4MB。本质是多条编码进一个 body，Broker 按一条存、消费端拆开。

- **压缩**：单条 ≥4KB 自动 zip（sysFlag 置位）；**批量不压缩**。
- **VIP 通道**：Broker 10909 端口（主−2）；默认关，自建集群不开。

## §8 Hook 与 MqTraceHelper 的区别（一句话，防混）

`SendMessageHook`（发送前后插桩，`lab D3 §八`）是**客户端内部**扩展点（消息轨迹用它）；`MqTraceHelper`（Tracing 06）是**业务层**透传（Template 发之前写头）。两层各干各的，别混。

## §9 边界（这里一句话翻篇）

- Broker 收到后写盘分发 → 03。
- NameServer 路由拉取/TBW102 → 04。
- 延时/事务专用 API → 06/07。

## §10 Self-Test（4 题）

- Q1：三种模型的重试次数各是？异步重试发生在哪？
- Q2：3s 超时是每次还是总共？`Interrupted` 重试吗？
- Q3：SEND_OK 在异步刷盘下代表什么？后三种状态重发会怎样？
- Q4：默认队列选择是什么？重试为什么能避开坏 Broker？

## §11 衔接

- 本篇回答"怎么发"；下篇 03 回答"Broker 收到后存哪"（CommitLog 三件套 + 刷盘 + HA）。
