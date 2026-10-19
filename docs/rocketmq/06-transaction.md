---
title: "06-事务消息：半消息与回查"
series: "RocketMQ 系列"
previous: "05-ordered-broadcast.md"
next: "07-延迟重试DLQ.md（待写）"
lab_refs:
  - "rocketmq-lab/docs/D6-事务消息.md"
source_refs:
  - "xhs order OrderTransactionListener.java:20-71（6 步注释 + 本地三表同事务）"
---

# 06-事务消息：半消息与回查

## §0 它干了什么（例子先行）

下单：订单入库 + 通知库存扣减，必须同生死：

```
先写 DB 再发消息：DB 成了，发失败 → 订单有了库存没扣 → 超卖
先发消息再写 DB：发了，DB 回滚 → 库存扣了订单没有 → 脏数据
事务消息：发半消息（不可见）→ 本地事务（订单+明细+本地消息表同 DB 事务）
  → 成功 COMMIT（可见）/ 失败 ROLLBACK（删）/ 没上报 30s 后 Broker 回查
```

- **它干了什么**：预发不可见半消息，本地事务结果决定可见性；未决靠回查兜底。
- **输入/输出**：输入=半消息 + 本地事务；输出=可见消息（或删除）。
- **xhs 对照**：`OrderTransactionListener` 注释把 6 步写全了（本篇 §6 展开）。

## §1 掌握卡（4 条）

1. **两难**：先 DB 后发（丢事件）vs 先发后 DB（脏数据）——半消息把"发"拆成"预发 + 定可见"。
2. **半消息不可见**：改写进 `RMQ_SYS_TRANS_HALF_TOPIC`，原 topic/queueId 藏属性里；COMMIT 才还原回真实 topic。
3. **回查三数**：30s 一轮 / 免疫 6s / 最多 15 次（~7.5 分钟）超限丢。
4. **保证边界**：保"本地事务与可见性的最终一致"，**不保消费成功**（消费失败走重试/DLQ，与事务无关）。

## §2 完整时序（6 步）

```
① sendMessageInTransaction：打 PREPARED 标记 → Broker
② Broker prepareMessage：queueId 清零 → topic 改写 HALF → 原信息藏 REAL_TOPIC/REAL_QID
   → 落盘 → 分发到 HALF 索引（无订阅者，不可见）
③ Producer 收半消息成功 → executeLocalTransaction：commit / rollback / unknown
④ EndTransaction 上报：
   COMMIT → 取半消息 → 还原真实 topic 重写（走正常分发）+ OP 记一笔
   ROLLBACK → 只写 OP 标记"已处理"，不再还原
   UNKNOWN → 什么都不干，等回查
⑤ 回查：CheckService 每 30s 扫 HALF → 过滤（§4）→ 向同组任一在线实例 checkLocalTransaction
```

**寻址关键**：半消息的 `commitLogOffset` 随 EndTransaction 头传给 Broker——01 说的 `offsetMsgId` 在此处的真正用途：**唯一寻址半消息**。

## §3 失败推演

| 场景 | 结果 |
|---|---|
| 半消息写失败 | 本地事务不执行，无残留 |
| 本地事务失败 | 主动 ROLLBACK，永不可见 |
| 上报前 Producer 宕机 | 30s 回查组内他实例，查到提交则还原——**不丢** |
| 全组宕机 | 30s 一轮，最多 15 次，超限丢 + 记 CHECK_MAX_TIME |
| Broker 宕机 | 半消息在 CommitLog，重启回查继续——**不丢**（03 §7） |
| 还原后消费失败 | 走重试/DLQ——与事务无关 |

## §4 回查机制

过滤（按源码顺序）：超 15 次/超保留期丢 → 免疫 6s 内不查（给上报留缓冲）→ 有 OP 标记跳过 → 回查。OP 写入批量合流（高吞吐上千决策/半秒，逐条写压垮 Broker）。重启后以 HALF 索引恢复，无需额外索引。

## §5 三方案对比

| 方案 | 存哪 | 回查谁 | 适用 |
|---|---|---|---|
| RocketMQ 事务 | Broker HALF topic | Broker 查 Producer | 单表事务+发消息 |
| Outbox | 业务 DB 表 | 看门狗轮询 | 跨消息系统 |
| Seata AT | TC + undo_log | TC 协调 | 多 DB 全局事务 |

（TC=事务协调器，undo_log=回滚日志；Seata 展开非本系列。）

一句话：事务消息 = **Broker 内嵌 outbox**（半消息即 outbox 行，回查即看门狗——看门狗=定时轮询扫表的补偿进程；防重靠 OP 标记，Outbox 靠 DB 唯一约束）。

## §6 xhs 实证

`OrderTransactionListener.java:20-35` 注释（6 步 + 为什么不用先写后发）：本地事务 = `t_order + t_order_item + t_local_message` **同一 DB 事务**（:61-70）；下游 Inventory 预扣库存。注释原话："DB 成功但发消息失败 → 订单创建了但库存没扣 → 超卖"——这就是 §1 两难的第一行。

## §7 边界（这里一句话翻篇）

- spring 侧 `RocketMQTransactionListener` 桥 → 08（D8 §四）。
- 事务存储（preparedTransactionOffset 字段）→ 用时查 `lab D6`。
- Seata/Outbox 展开 → 非本系列。

## §8 Self-Test（3 题）

- Q1：两难的两行各丢什么？半消息机制解决哪一行？
- Q2：回查三数是？UNKNOWN 和宕机分别走哪条？
- Q3：事务消息保什么、不保什么？（可见性 vs 消费成功）

## §9 衔接

- 本篇回答"原子发"；下篇 07 回答"延迟发 + 失败后去哪"（延时/重试/DLQ/位点）。
