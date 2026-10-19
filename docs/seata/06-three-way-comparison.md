---
title: "06-三方案对比：Seata vs 事务消息 vs Outbox"
series: "Seata 分布式事务系列"
previous: "05-integration.md"
next: "07-xhs生产对照.md（待写）"
lab_refs:
  - "seata-lab/docs/D1-分布式事务核心概念.md（§七）"
cross_refs:
  - "RocketMQ 系列 06-transaction.md（半消息/回查/三方案表）"
  - "dist-theory/04-saga-reliable-message.md（理论线：可靠消息最终一致）"
---

# 06-三方案对比：Seata vs 事务消息 vs Outbox

## §0 它干了什么（例子先行）

同一个业务"下单"，三种"不一致风险"，三种药：

```
风险A：订单写了，发通知的消息没发出去 → 丢事件
  → RocketMQ 事务消息（半消息：本地事务和可见性绑定）

风险B：订单库扣了，支付库没扣（两个独立 DB）→ 资金漂移
  → Seata AT（@GlobalTransactional 跨库原子）

风险C：流程跑几小时（订单→调度→维修）→ 全程持锁扛不住
  → SAGA（每步提交，失败反补）
```

- **它干了什么**：把"分布式一致"拆成三个不同问题——每个问题有专属工具，不是谁替代谁。
- **输入/输出**：输入=风险类型；输出=选型。
- **本篇定位**：兑现 RocketMQ 06 埋的三方案表 + D1 §七的三场景——零新概念，纯收口。

## §1 掌握卡（3 条）

1. **问题域先分清**：本地事务+发消息（消息与本地事务的原子）→ 事务消息；跨 DB 多服务 → Seata；长流程 → SAGA——**先问"不一致风险长什么样"，再选工具**。
2. **Seata AT = 跨库分布式事务的标准答案**：一个注解包裹多个 DataSourceProxy，TC 协调——侵入最低的跨 DB 方案（02）。
3. **事务消息 = 本地事务的"消息外挂"**：半消息保证"要么都发要么都不发"，**不跨 DB**（RocketMQ 06 §1 的两难第一行）——和 Seata 是互补不是竞争。

## §2 三问题三工具（RocketMQ 06 表兑现）

| 方案 | 消息/状态存哪 | 补偿谁发起 | 治什么 |
|---|---|---|---|
| **RocketMQ 事务消息** | Broker HALF topic | Broker 回查 Producer | 本地事务 + 发消息的原子 |
| **Seata AT** | TC + 各库 undo_log | TC 协调各 RM | 跨 DB / 跨服务事务 |
| **Outbox** | 业务 DB 的 outbox 表 | 看门狗轮询（定时扫表转发） | 同事务消息，但跨消息系统 |

防重机制也不同：事务消息靠 OP 标记（RocketMQ 06 §4），Seata 靠 undo_log/分支状态，Outbox 靠 DB 唯一约束——**都是"记录留痕防重复"，载体不同**（和 03 fence 表同根思路）。

## §3 D1 §七三场景

```
场景1：订单扣库存（单库）+ 发 MQ 通知
  → 事务消息即可（本地事务原子，不需要跨库）

场景2：订单扣库存 + 支付扣款（两个独立 DB）
  → Seata AT：一个 @GlobalTransactional 包两个 DataSourceProxy

场景3：订单扣库存 + 支付扣款 + 发 MQ
  → 组合：Seata 管 DB 部分，事务消息管发 MQ——
    两者独立域（xid 管 DB 事务，半消息管消息可见性），互不冲突
```

**组合的边界**：Seata 不包 MQ 发送（AT 管不到 RocketMQ 的 HALF topic），事务消息不管跨库（半消息只绑一个本地事务）——**各自守域，拼起来全覆盖**。

## §4 什么时候不用 Seata（选型反面）

- 只有一个 DB + 本地事务就够 → 连消息都不发，`@Transactional` 搞定（别为分布式而分布式）。
- 跨系统但只要最终一致、且对方是 MQ 消费者 → 事务消息（02 说的 AT"最终一致"和它同级，但事务消息更轻）。
- 要跨团队多语言、不想引 TC 进程 → Outbox（不依赖 TC，看门狗自建——但要自己建表+跑轮询进程）。

## §5 边界（一句话翻篇）

- 理论推导（2PC/补偿/可靠消息分类）→ `dist-theory/03-2pc-tcc` + `04-saga-reliable-message`（不重讲）。
- 半消息/回查细节 → RocketMQ 系列 06。
- Seata 的 TC 集群部署/高可用 → 用时查官方文档。

## §6 Self-Test（3 题）

- Q1：三问题（丢事件/跨库漂移/长流程）各用什么工具？
- Q2：为什么 Seata 和事务消息能组合？（独立域：xid vs 半消息）
- Q3：Outbox 和事务消息治同一个问题，差别在哪？（回查者：看门狗进程 vs Broker；防重：唯一约束 vs OP 标记）

## §7 衔接

- 对照线收口；下篇 07 xhs 生产对照（TccFenceService 三防实锤 + 生产陷阱 7 条）。
