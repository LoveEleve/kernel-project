---
title: "01-为什么用MQ：削峰与解耦"
series: "RocketMQ 系列"
previous: "README.md"
next: "02-生产发送.md（待写）"
lab_refs:
  - "rocketmq-lab/docs/D1-核心概念与整体架构.md"
source_refs:
  - "xhs-project 各服务 topic 订阅（ORDER/CART/COUPON/PAY/NOTIFY/FEED/SOCIAL 等十几个）"
---

# 01-为什么用MQ：削峰与解耦

## §0 它干了什么（例子先行）

下单流程要调库存、积分、支付、通知四个服务。同步写法：

```
用户请求 → Order → 库存 → 积分 → 支付 → 通知 → 返回
              每一步等下一步：任一变慢，线程/连接全占着
              10 万请求/秒进来 → 线程池耗尽 → 级联雪崩
```

消息化之后：

```
用户请求 → Order 写事件 → 返回（毫秒级）
              ORDER_TRANSACTION_TOPIC → 库存/积分/支付/通知各按自己能力消费
              入口速率和处理速率解耦：洪峰变积压，下游不崩
```

- **它干了什么**：把"同步等"变成"写事件即返"，下游按能力消费——**时间换稳定**。
- **输入/输出**：输入=瞬时洪峰；输出=队列积压 + 可控等待（不是更快，是不崩）。
- **xhs 对照**：十几个业务 topic（ORDER/CART/COUPON/PAY_RESULT/NOTIFICATION/FEED/SOCIAL…），下单、优惠券、通知全走 MQ。

## §1 掌握卡（3 条）

1. **MQ = 入口速率与处理速率解耦**：同步调用把下游延迟全叠加到入口；消息化后入口只写事件，消费速率由下游自己定。
2. **代价四件套**：重复（至少一次）/顺序（只分区内）/积压（水库水位）/最终一致（不是实时一致）——用 MQ 就得认这四笔账。
3. **RocketMQ 定位**：commitlog + consumequeue 结构；强项事务/延迟/顺序消息；xhs 选它不选 Kafka 的原因 08 收（L5-4 落点）。

## §2 组件全景（5.x 六角色）

```
              NameServer 集群（无状态，路由注册）
              ▲注册（30s）  ▲查询（30s 刷新）
    Broker Master ◀── Producer / Consumer ──▶（gRPC/Remoting）
    brokerId=0 复制
    Broker Slave（brokerId>0，冗余）
    + Proxy（5.x 无状态接入，8081）+ Controller（Raft 选主，默认关）
```

| 组件 | 职责 | 展开 |
|---|---|---|
| NameServer | 路由注册中心，无状态、AP（CAP 的可用+分区容忍，取舍后果 04 展开） | 04（并入消费前讲路由） |
| Broker | 存储节点；Master 读写，Slave 冗余 | 03 |
| Producer/Consumer | 生产/消费 | 02/04 |
| Proxy/Controller | 5.x 新增；xhs 不用（remoting 直连） | 08 一句 |

lab/生产环境：NameServer + 单 Master Broker；`rocketmq-spring 2.3.0` 默认 remoting 客户端（Remoting = RocketMQ 私有 TCP 协议；v5 客户端可改走 gRPC 经 Proxy），**不经 Proxy**——xhs 同款（starter 同为 2.3.0，无 proxy 配置）。

## §3 核心概念（建档，02~04 展开）

- **Message**：原子单元（topic + properties + body，默认 4MB 上限）；两个 ID 别混（`UNIQ_KEY` 生产者生，`offsetMsgId` Broker 生）。
- **Topic**：逻辑集合，只管路由不管存。生产必须关 `autoCreateTopicEnable`（自动建 topic 是事故源头）。
- **MessageQueue**：物理分片 `(topic,broker,queueId)` ≈ Kafka partition；队列数 = 消费并发上限 = 顺序边界。默认读写 16（5.x），自动建 8。
- **存储三件套**：CommitLog（全量顺序写）+ ConsumeQueue（20 字节/条索引）+ IndexFile（Key 反查）——写一次，异步分发生成后两者。
- **两开关**：刷盘（SYNC/ASYNC）× 复制（SYNC/ASYNC），默认双异步（最快，可丢）；金融级双同步。
- **Tag**：二级分类；Broker 按 hash 粗筛，Consumer 按原文精筛（两条路径都有）。

## §4 xhs 十几个 topic 一览（订阅侧实证）

```
订单：ORDER_TRANSACTION_TOPIC / ORDER_CLOSE_TOPIC / ORDER_COMPENSATION_TOPIC
交易：PAY_RESULT_TOPIC / REFUND_RESULT_TOPIC / CART_TOPIC
营销：COUPON_CLAIM_TOPIC
库存：INVENTORY_TOPIC / INVENTORY_FAILED_TOPIC / INVENTORY_CACHE_TOPIC
内容：FEED_TOPIC / NOTE_INDEX_TOPIC / PRODUCT_INDEX_TOPIC / RECOMMEND_BEHAVIOR_TOPIC
社交：SOCIAL_TOPIC
通知：NOTIFICATION_TOPIC
```

发送侧另有 `CACHE_EVICT_TOPIC` 等。完整 17 个的精确数 08 对（L5-4 口径）。

## §5 和 Feign 的对比（同步 vs 异步）

| 维度 | Feign（同步） | MQ（异步） |
|---|---|---|
| 返回时机 | 下游全完才返 | 写事件即返 |
| 失败 | 直接抛，调用方重试 | 重试主题 + DLQ（死信队列，07 讲），下游自愈 |
| 顺序 | 天然（调用序） | 只单队列内（05 讲） |
| 一致 | 强（分布式事务难） | 最终一致（06 讲事务消息） |
| 上下文透传 | HTTP header（OpenFeign 04） | 消息属性（Tracing 06） |

## §6 边界（这里一句话翻篇）

- Kafka/RabbitMQ 设计哲学对比 → 08 一句（L5-4 另有 Kafka 条目）。
- NameServer 六张表/AP 推演 → 04。
- 存储/消费/事务/延迟实现 → 02~07 各篇。

## §7 Self-Test（3 题）

- Q1：MQ 削峰"削"的是什么？（从入口/处理速率解耦答，不是变快）
- Q2：用 MQ 认哪四笔账？各举一例。
- Q3：Queue 数同时决定哪两个上限？（消费并发 + 顺序边界）

## §8 衔接

- 本篇回答"为什么用"；下篇 02 回答"怎么发"（三种发送模型 + 队列选择 + SendResult 歧义）。
