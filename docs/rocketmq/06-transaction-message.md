---
title: "06-事务消息：半消息与回查"
series: "RocketMQ 系列"
previous: "05-ordered-broadcast.md"
next: "07-延迟重试DLQ.md（待写）"
lab_refs:
  - "rocketmq-lab/docs/D6-事务消息.md"
source_refs:
  - "xhs order OrderTransactionListener.java:20-71（6 步注释 + 三表同事务）"
---

# 06-事务消息：半消息与回查

## §0 它干了什么（例子先行）

下单要原子完成"DB 建订单 + 发库存扣减消息"：

```
① 发半消息（HALF topic，消费者看不见）→ ② 本地事务（t_order+t_order_item+t_local_message 同一 DB 事务）
  → ③ 成功 COMMIT（还原到真实 topic，库存消费）/ 失败 ROLLBACK（永不可见）
  → ④ Producer 挂了没上报？30s 后 Broker 回查组内其他实例
```

xhs 注释原话：先写 DB 再发普通消息，DB 成功但发送失败→订单建了库存没扣→**超卖**。事务消息把两者绑成原子。

- **它干了什么**：预发不可见半消息，本地事务定其生死，未决回查兜底。
- **输入/输出**：输入=本地事务结果；输出=消息可见/不可见。
- **本篇回答**：半消息存哪？回查怎么筛？极端失败丢吗？和 Outbox 什么关系？

## §1 掌握卡（4 条）

1. **两难**：先 DB 后发（发送失败丢事件）vs 先发后 DB（回滚留脏数据）——半消息破局。
2. **半消息机制**：改写进 `RMQ_SYS_TRANS_HALF_TOPIC`（原 topic 藏属性里），COMMIT 时还原回真实 topic，ROLLBACK 只写 OP 标记。
3. **回查三过滤**：超 15 次丢 / 超保留期跳 / 免疫 6s 内不查；OP 批量合流（高吞吐不压垮 Broker）。
4. **只保一半**：保证"本地事务与消息可见"一致，**不保消费成功**——下游另靠幂等（02 回顾）。

## §2 时序 6 步

```
① sendMessageInTransaction（打 PREPARED 标记）→ ② Broker prepareMessage：
   queueId 清零 + topic 改写 HALF（原 REAL_TOPIC/REAL_QID 进属性）→ CommitLog → HALF 索引
③ Producer 执行本地事务 → commit/rollback/unknown
④ EndTransaction：COMMIT 还原真实 topic + 写 OP；ROLLBACK 只写 OP；UNKNOWN 不动
⑤ 回查：30s 扫 HALF（每次 1 条）→ 三过滤 → 组内任一在线实例 checkLocalTransaction
⑥ 15 次（~7.5 分）还未决 → 丢弃 + 记 CHECK_MAX_TIME_TOPIC
```

`offsetMsgId` 在此的真用途：**唯一寻址半消息**（EndTransaction 头里传 CommitLog 偏移）——01 说"别混用"的下半句。

## §3 失败推演

| 场景 | 结果 |
|---|---|
| 半消息写失败 | 本地事务不执行，无残留 |
| 本地事务失败 | ROLLBACK，永不可见 |
| 上报前 Producer 宕机 | 30s 回查组内其他实例，查到提交则还原——**不丢** |
| 组全宕 | 30s 一轮，最多 15 次，超限丢 + 记录 |
| Broker 宕 | 半消息在 CommitLog，重启回查继续——**不丢**（03 §7） |
| 还原后消费失败 | 走重试/DLQ，与事务无关（04 §5） |

## §4 回查三过滤 + OP 合流

- **needDiscard/needSkip**：≥15 次 / 超保留期（CommitLog 可能已删，03 §8 回顾）。
- **免疫期**：默认 6s（`transactionTimeOut`，可配）——别抢在 Producer 上报前回查。
- **OP 判定**：已决跳过，未决才查。
- **OP 批量**：`TransactionalOpBatchService` 攒多条一次刷——高吞吐每秒上千决策，逐条写压垮 Broker。

## §5 Outbox 对比 + xhs 落点

| 方案 | 存哪 | 回查谁 | 适用 |
|---|---|---|---|
| RocketMQ 事务 | Broker HALF topic | Broker 查 Producer | 单 DB 事务+发消息 |
| Outbox | 业务 DB 表 | 看门狗轮询 | 跨消息系统 |
| Seata AT | TC + undo_log | TC 协调 | 多 DB 全局事务 |

本质：**Broker 内嵌 outbox**（半消息=outbox 行，回查=看门狗）。xhs 落点：本地事务三表同库（订单+明细+本地消息表——表都建了，双保险）+ 消费扣库存 + 回查。面试答：小团队单栈用事务消息，跨团队多语言用 Outbox（`lab Z G2` 原话思路）。

## §6 边界（这里一句话翻篇）

- spring 桥（`RocketMQTransactionListener`）→ 08。
- Seata/Outbox 展开 → 非本系列。
- 禁发 HALF 系统 Topic（02 §4 回顾）：必须走事务 API。

## §7 Self-Test（3 题）

- Q1：先 DB 后发 / 先发后 DB 各丢哪边？半消息怎么破？
- Q2：COMMIT 和 ROLLBACK 在 Broker 侧各写了什么？
- Q3：回查三过滤是？15 次后去哪？事务消息保消费成功吗？

## §8 衔接

- 本篇回答"原子发消息"；下篇 07 回答"延迟到 + 失败去哪"（延时/重试/DLQ/位点）。
