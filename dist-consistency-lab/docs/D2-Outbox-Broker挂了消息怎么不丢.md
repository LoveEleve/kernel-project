# D2 — Outbox：Broker 挂了，订单消息怎么不丢？

> **定位**：一致性模式第 2 篇。**可靠投递**——"本地事务成功 ⇔ 消息最终发出"的最终一致方案。
> **对照**：xhs-project 券/库存 Outbox + `LocalMessageRetryJob`（本 lab A02 的最小复刻）。

---

## 一、它干了什么：一个例子

下单成功要通知下游（发 MQ），但代码是两步：

```java
orderService.create(order);                 // ① 本地事务提交
rocketMQTemplate.send("ORDER_CREATED", msg); // ② 发消息——如果这里 Broker 挂了/超时？
```

结果：**订单已创建，消息永远发不出去**——下游（库存/积分/通知）不知道这笔订单。
反过来（先发消息后提交）也一样危险：消息发了但本地事务回滚 → 下游处理了不存在的订单。

**Outbox 的解法**：把"发消息"变成**本地事务的一部分**：

```java
@Transactional
public void createOrder(Order order) {
    orderMapper.insert(order);                                        // ① 业务表
    outboxMapper.insert("order-created", order.getId(), payload);     // ② 消息表（同一事务！）
}
// 事务提交 = 消息"必然会被发出"（由补发任务保证）
```

**一句话**：Outbox = **本地消息表**——业务表与消息表同库同事务（原子），后台任务扫描未发送的消息补发（可靠）。

---

## 二、为什么需要它：对比三种"发消息"

| 方案 | 原子性 | 可靠投递 | 依赖 |
|---|---|---|---|
| 业务事务 + 直接发 MQ | ❌（两步） | ❌（Broker 挂了就丢） | MQ |
| **事务消息**（RocketMQ） | ✅（半消息+回查） | ✅ | MQ 支持事务消息 |
| **Outbox** | ✅（同库同事务） | ✅（补发任务） | 只需 DB |
| **Outbox**（本 lab） | ✅ | ✅ | DB + 调度 |

**选型**：已有 RocketMQ 且场景简单 → 事务消息；跨 MQ/不想依赖 MQ 特性/要审计 → Outbox（xhs-project 两者都有，回链 RocketMQ D6 §五）。

---

## 三、怎么做：本 lab 实现（对齐 xhs-project）

### 3.1 写入：业务表 + outbox 同事务（A02 `/a02/place-order`）

```java
@Transactional
public void placeOrder(String orderId, long amount) {
    jdbc.update("UPDATE account SET balance = balance - ? WHERE id = 1", amount);   // 业务
    jdbc.update("INSERT INTO outbox(biz_type, biz_id, payload, status, retry_count, next_retry_time) "
              + "VALUES ('order-created', ?, ?, 0, 0, CURRENT_TIMESTAMP)", orderId, payload);  // 消息
}
// 事务提交后尝试即时投递（失败不抛——留给补发；xhs 中 syncSend 超时同理）
try { broker.send(topic, payload); markSent(id); } catch (Exception e) { /* 留在 outbox */ }
```

### 3.2 补发：扫描 → 投递 → 退避 → 死信（A02 `/a02/flush-outbox`）

```java
// 扫描待发送/失败且到期的记录
SELECT * FROM outbox WHERE status IN (0,2) AND next_retry_time <= NOW() ORDER BY next_retry_time LIMIT 50;
for (row : rows) {
    try { broker.send(topic, row.payload); status = 1; }        // 成功 → 已发送
    catch (e) {
        retryCount++;
        if (retryCount >= MAX_RETRIES) status = 3;              // 死信（终态，告警/人工）
        else { status = 2; next_retry_time = NOW() + backoff; } // 指数退避（D4）
    }
}
```

**实测链路**（本 lab 验证过）：Broker 故障注入 → 下单（DB 事务成功、即时投递失败）→ 补发失败（退避 ~33s）→ 恢复 Broker → 补发成功 → **消息不丢**。

### 3.3 status 状态机

```
0 待发送 ──投递成功──▶ 1 已发送（终态，可定期清理）
   │
   └─投递失败──▶ 2 失败（等 next_retry_time）──重试耗尽──▶ 3 死信（终态）
```

---

## 四、生产细节（xhs-project 的工程化）

| 细节 | xhs 做法 | 为什么 |
|---|---|---|
| **3s cutoff** | 扫描 `created_at < now-3s` 的记录 | 避开"刚插入、即时投递还在进行中"的记录（防并发重复发） |
| **多实例防重** | XXL-Job 单实例调度 / Redisson 锁 | 补发任务不能多实例并发扫同一批 |
| **批量** | LIMIT 200/50 分批 | 防一次扫太多拖垮 DB |
| **清理** | 已发送记录保留 7 天后删除（SETNX 日切标记） | 控制表增长；失败记录保留待人工 |
| **死信指标** | `myxhs.local_message.dead_letter.count` Gauge | 死信必须可监控可告警 |

---

## 五、坑与边界

| 坑 | 说明 |
|---|---|
| **at-least-once** | 补发可能重复（已发成功但标记丢失）——**消费端必须幂等**（D1） |
| 消息堆积窗口 | Broker 挂 1 小时 → outbox 积压 1 小时的消息，恢复后批量补发要限速 |
| 顺序性 | 同一业务键的消息补发可能乱序（xhs 用版本号抗乱序，回链 RocketMQ D7/xhs 第 07 题） |
| 表增长 | 无清理机制的表最终拖垮 DB——清理是必选项 |

---

## 六、xhs-project 对照

| 本 lab | xhs-project |
|---|---|
| `outbox` 表 + A02 | `t_coupon_outbox` / `t_inventory_outbox`（业务库同事务写入） |
| 补发 + 退避 | `CouponOutboxSenderJob`（5s 周期 + Redisson 锁）/ `InventoryOutboxSenderJob` |
| 死信 + 指标 | `LocalMessageRetryJob`（30s 周期 + 指数退避 30s→480s + 死信 Gauge） |
| 清理 | `deleteSentBefore(now-7d)` 每小时（RV32） |

---

## 七、值不值得深挖 & 面试问答

**值不值得**：🔴 必问——"MQ 挂了消息怎么办/你们怎么保证消息不丢"的标准答案；Outbox vs 事务消息的对比要能张口就来。

**Q1：Outbox 和事务消息的区别？**
Outbox：业务库同事务写消息表 + 应用侧补发（不依赖 MQ 特性，支持任意 MQ）；事务消息：Broker 内嵌 outbox（半消息+回查，依赖 RocketMQ）。回链 RocketMQ D6。

**Q2：Outbox 补发会不会重复发？**
会（at-least-once）——补发前已发成功的消息可能因标记丢失重发；消费端幂等兜底。

**Q3：为什么扫描要 3s cutoff？**
即时投递与补发任务可能并发——cutoff 让"刚写入"的记录不被补发抢走，避免同一消息双发。

**Q4：Outbox 的表越来越大怎么办？**
已发送（status=1）定期清理（保留审计窗口如 7 天）；失败/死信保留待人工处理。
