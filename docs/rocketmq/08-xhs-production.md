---
title: "08-xhs生产对照收口"
series: "RocketMQ 系列"
previous: "07-delay-retry-dlq.md"
next: "（本系列完）"
lab_refs:
  - "rocketmq-lab/docs/D8-rocketmq-spring-集成.md"
  - "rocketmq-lab/docs/D9-自动配置与5.x-Proxy.md"
  - "rocketmq-lab/docs/Z-面试题速答.md（八组 A~H + 陷阱 15 连）"
source_refs:
  - "xhs 17 个 *_TOPIC（grep 全量实证，= L5-4 口径）"
  - "common/mq/MessageIdempotentHelper.java:36-78（SETNX+TTL+removeMark+降级放行）"
---

# 08-xhs生产对照收口

## §0 它干了什么（例子先行）

本篇零新概念，干三件事：

```
① xhs 怎么用（17 topics 用法对照 + 三道治理防线）
② spring 集成怎么接（Template/Listener/事务两个 Bean）
③ 面试怎么答（Z 八组回链 + 陷阱 15 连）
```

- **输入**：01~07 全部内容；**输出**：会用、会治、会答。

## §1 掌握卡（3 条）

1. **接入三件**：`RocketMQTemplate`（sync/async/oneway/顺序/延时/事务一个 Bean 全包）+ `@RocketMQMessageListener`（一注解一容器）+ 事务要**两个 Bean**（坑）。
2. **xhs 三道防线**：幂等（Redis SETNX+TTL+DB 唯一键）/ 乱序无害化（版本号，不全靠 ORDERLY）/ DLQ 三级治理（可丢·可重放·必须处理，归档≠删除）。
3. **收口数字**：17 个业务 topic；4.x remoting 不用 Proxy；starter 2.3.0。

## §2 spring 集成（D8）

```
starter 2.3.0 → RocketMQAutoConfiguration 读 spring.rocketmq.*
  → RocketMQTemplate（生产）：syncSend/asyncSend/sendOneway/syncSendOrderly/
    sendMessageInTransaction/syncSendDelayTimeSeconds（5.x 时间轮）
  → DefaultRocketMQListenerContainer（消费）：每 @RocketMQMessageListener
    一个容器 = 一个 DefaultMQPushConsumer → subscribe → registerListener → start
```

三句：payload 任意对象走 `MessageConverter`（默认 Jackson JSON）**同组格式必须一致**；请求-响应 `sendAndReceive` 有丢结果风险（失败仅打日志，不保证可靠）；线程池默认 20（max 64），`maxReconsumeTimes=-1`（并发沿用 Broker 16 次）。

**事务两个 Bean 坑（Z G2）**：`TransactionMQProducer` 会触发 starter `@ConditionalOnMissingBean` 跳过自动装配——必须手动提供 `@Primary RocketMQTemplate`。

## §3 xhs 三道防线

**① 幂等**（`MessageIdempotentHelper:36-78`）：Redis `SETNX(msg:idempotent:…) + TTL`（SETNX=set if not exist，键不存在才设成功——首个处理者赢；TTL=过期时间，24h 后可再处理），失败 `removeMark` 放行重投；**Redis 挂了降级放行**（保可用）+ 告警日志；DB 唯一键垫底。配合 02/06 结论：**发送端和消费端都可能重复，幂等是两端义务**。

**② 乱序无害化（Z H2）**：不全用 ORDERLY——顺序牺牲并发。xhs 用**版本号**抗乱序（ES 拒旧版本覆盖 + 幂等 24h TTL 兜底），乱序变无害，这是架构选择（05 §4 回顾）。

**③ 堆积/ DLQ 治理（Z H3）**：消费 lag（积压延迟，跟不上产生的速度）报警 + DLQ 积压规则；按"可丢 / 可重放 / 必须处理"三级；**DLQ 归档≠删除**（xhs 教训）。07 说"DLQ 不是终点是人工队列"——治理规则在此落地。72h 物理删（03 §8）是这条治理线的死线。

## §4 17 个 topic 对照（grep 全量实证，= L5-4）

```
订单交易 7：ORDER_TRANSACTION / ORDER_CLOSE / ORDER_COMPENSATION /
           PAY_RESULT / REFUND_RESULT / CART / COUPON_CLAIM
库存 3：  INVENTORY / INVENTORY_FAILED / INVENTORY_CACHE
内容推荐 4：FEED / NOTE_INDEX / PRODUCT_INDEX / RECOMMEND_BEHAVIOR
社交通知 2：SOCIAL / NOTIFICATION
运维 1：  CACHE_EVICT
```

用法对照本系列：关单=延时（07）、订单→库存=事务（06）、购物车=顺序（05）、库存失败=重试/DLQ（07）、缓存刷=广播（05）。分组与 01 §4 一致（01 为 订单3+交易4，此处合并为"订单交易 7"）。

## §5 Proxy 一句（D9/Z G3，xhs 不用）

5.x Proxy=无状态 gRPC 接入（8081）+ 兼容 remoting，LOCAL（内嵌 Broker）/CLUSTER（默认）两模式；v5 starter 与 2.x **不兼容**（endpoints+gRPC，迁移是重构不是替换）。xhs 走 2.3.0 remoting 直连——用到 v5 再说。

## §6 Z 八组回链

- **A 路由/B 发送/C 存储/D 消费** → 04/02/03/04（NameServer 六表、三种发送、三件套、长轮询重平衡）。
- **E 事务/F 延时顺序广播** → 06/07+05。
- **G Spring+Proxy** → 本篇 §2/§5。
- **H 治理对照** → 本篇 §3/§4（幂等/乱序/堆积/MQ 选型）。

**陷阱 15 连**（转述 `lab Z §三`）：

| # | 反直觉 | 正解 | 回链 |
|---|---|---|---|
| 1 | Push 是推 | 假推，长轮询挂 15s | 04 |
| 2 | 集群只消费一次 | at-least-once 可重复 | 04/08§3 |
| 3 | Slave 分担读 | 默认不可读，纯冗余 | Z 陷阱（03 未展开） |
| 4 | 延时任意时长 | remoting 固定 18 档 | 07 |
| 5 | level16=2h | =**30m**；18 才 2h | 07 |
| 6 | 16 次=level16 | 次数是次数，level=3+重试数 | 07 |
| 7 | 消费后删除 | 只按 72h 过期删 | 03 |
| 8 | SEND_OK=落盘 | 只进 PageCache | 02 |
| 9 | ConsumeFromWhere 随时改 | 只对新组生效一次 | 04 |
| 10 | offset=物理偏移 | =ConsumeQueue 序号 | 03 |
| 11 | 半消息可见 | HALF 无订阅者 | 06 |
| 12 | 回查无限 | 15 次上限后丢 | 06 |
| 13 | 队列默认 8 | TopicConfig 16，8 是自动建模板 | 01 |
| 14 | 顺序=全局有序 | 单队列有序 | 05 |
| 15 | 广播失败重试 | 直接丢弃 | 05 |

## §7 边界（一句话翻篇）

- Reply/类过滤/`sendAndReceive` 细节、`@RocketMQTemplate` 注解 → `lab D8` 用时查。
- Proxy 部署/VIP/Controller → 本系列只指路（§5/03 边界）。
- Kafka 选型对比 → `lab Z H4`，L5-4 另有条目。

## §8 终检（8 题，过 6 算过关）

1. 两难两行 + 半消息破哪行（06）
2. SEND_OK 在哪条链下≠落盘（02）
3. offset 是什么的序号；72h 与消费有没有关系（03）
4. 长轮询 15s 谁挂谁；Rebalance 谁计算（04）
5. 顺序三件套 + 分区键取错的现象（05）
6. level16=？重试 16 次后去哪（07）
7. xhs 三道防线各治什么（08§3）
8. 17 topic 里哪个用延时、哪个用事务（08§4）

## §9 衔接

- RocketMQ 系列 8 篇完，README 总表全挂链接；tracing 欠的"MQ carrier"债已还（02 Hook vs MqTraceHelper 分层）。
- 下一专题由你定。
