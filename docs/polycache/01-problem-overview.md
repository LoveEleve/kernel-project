---
title: "01-问题与全景：多机房各写各的怎么同步"
series: "PolyCache 多机房复制系列"
previous: "README.md"
next: "02-无侵入拦截.md"
source_refs:
  - "poly/polycache/README.md（特性表+模块表+数据流）"
---

# 01-问题与全景：多机房各写各的怎么同步

## §0 它干了什么（例子先行）

两个机房 A/B，业务代码各自连本地 Redis（就近读，快）：

```java
// 机房 A 的应用——业务代码一行不改
RedisCommandCapture capture = RedisCommandCapture.builder()
        .source("zone-a")
        .listener(new KafkaRedisCommandProducer(producerFactory, "polycache-redis-replication"))
        .build();
capture.applyTo(config);                    // 装一次拦截

client.getBucket("user:1").set("alice");    // A 落地 → 自动被复制到 B
client.getMap("map:1").put("name", "bob");  // Redisson 内部走 EVAL，同样被复制
client.getBucket("user:1").get();           // 读命令——不复制
```

机房 A 的 `SET` 落地后，被机房 B 的消费者重放到 B 的本地 Redis；B 自己产生的写也按同路回流 A。

- **它干了什么**：同一份业务代码、多机房各写各的本地 Redis，**写命令自动、按序复制到其它机房，业务零改造**。
- **输入/输出**：输入=本地写命令；输出=对端机房最终一致的副本。
- **本篇回答**：为什么不用 Redis 原生复制？整体数据流长什么样？这套设计欠了什么账？

## §1 掌握卡（3 条）

1. **问题本质**：异地多活（多机房同时在线服务）下"就近写本地"+"跨机房最终一致"——不是缓存一致性小问题，是**复制协议**问题（时序、失败、回环、语义）。
2. **三条朴素路都死**：Redis 主从（跨机房 WAN 复制延迟/带宽/单向）/ 应用双写（业务改造+部分失败分叉）/ 改 Redisson 源码（不可升级）——死法 02~05 逐一兑现。
3. **PolyCache 的答案**：连接层拦截（无侵入）→ 写过滤 → Kafka 按 key 保序 → 语义重放 → 防回环——四个模块各占一环。

## §2 朴素答案与它们的死法

| 朴素路 | 第一直觉 | 具体死在哪 |
|---|---|---|
| Redis 原生主从复制 | 让 A 当 master、B 当 slave | 跨机房 WAN 复制延迟大且吃带宽；**B 是只读副本，B 机房写不进去**；master 单点写 |
| 应用层双写 | 业务代码同时写 A 和 B | 每处写都改造（**违背零侵入目标**）；一边成一边败 → 数据分叉；顺序靠不住 |
| 改 Redisson 源码 | 在命令执行处插桩（往框架代码里织入逻辑） | fork 维护、无法跟版本——**改框架比改业务更糟** |

共同教训（这是全系列的推导起点）：**拦截点必须在"框架已经把命令准备好、还没发出"的那一层**——不在业务 API（要改造），不在 Redis 服务端（要改 Redis）——在客户端的网络管线上。02 兑现。

## §3 整体数据流（四模块各占一环）

```
业务代码（零改造）
  → [polycache-redisson] NettyHook 装进每条连接管线
      命令编码前（值参数已由源 codec 编码）→ CommandCaptureHandler 看一眼：是写？→ [core] 查命令目录
  → [replicator] KafkaRedisCommandProducer：按 key 分区、单线程发 Kafka
  → Kafka topic（跨机房）
  → [replicator] Consumer 按分区顺序拉
  → [redisson] RedisCommandReplayer：ByteArrayCodec 原样重放 → 本地 Redis
      带 source 标记——本机房的写跳过（防回环）
```

| 模块 | 职责 | 篇 |
|---|---|---|
| polycache-core | 命令目录 + 写目录 + 复制消息模型（无客户端依赖） | 03 |
| polycache-redisson | 拦截（NettyHook）+ 语义重放 + 动态路由 | 02/05/06 |
| polycache-replicator | Kafka 编解码 + 异步生产 + 消费过滤 | 04 |
| polycache-spring-boot | 自动装配 + 属性 | 07 |

## §4 设计目标与欠账预览（负面空间先说）

README 特性表里藏着三条**不保证**（08 收口全账）：

- **至少一次，不是恰好一次**：消费端重放完成才提交偏移——崩溃窗口内可能重放两次（靠 Redis 命令幂等性兜，不靠框架）。
- **队列满就丢**：生产端单线程队列满时**丢弃并计数**，绝不阻塞 Netty 事件循环——保"应用不受影响"，欠"极端积压下可能丢复制"。
- **失败不复制也意味着不补**：重放重试 3 次仍失败 → 告警跳过——对端可能缺这条写，靠人工/对账。

这三条不是缺陷，是**用"应用无感"换来的账**——每一笔都对应 04/05 的一个设计分叉。

## §5 边界（一句话翻篇）

- 每个朴素路的死法细节 → 02~05 各篇开头兑现。
- Kafka 内部 → 不展开（概念在 RocketMQ 系列打过底）。
- 容量/部署 → 08。

## §6 Self-Test（3 题）

- Q1：三条朴素路各死在哪一句话？共同教训是什么？（拦截层选哪）
- Q2：四模块各占数据流哪一环？
- Q3：负面空间先答两条——"至少一次"和"队列满丢弃"各换回了什么？

## §7 衔接

- 本篇给全景；下篇 02 第一个设计问题：命令怎么在**业务零改造**的前提下被"看见"（NettyHook）。
