# L3-03 — 优惠券域：单 Lua 原子领券与退券语义

> **定位**：L3 业务域链路第 3 篇（P0 缺口：31-优惠券）。四段式：**时序 → 决策 → 组件映射 → 拷打**。
> **依据**：xhs-project 优惠券代码（CouponService/Lua/Jobs）+ 31-优惠券 + RV30 修正记录。

---

## 一、它干了什么：领券/退券双链路时序

**领券链（claimCoupon）**：
```
① 模板校验（缓存读；状态/有效期；T-121 validStart 过滤对齐）
② Lua 原子执行（claim_coupon.lua）：查库存→查限领→DECR stock→INCR claimed
   返回码：1 成功 / -1 领完 / -2 限领 / -3 未初始化
③ Outbox 先行：claimNo（UUID）写 Outbox 表（唯一键幂等）
④ MQ 同步发送落库（~2ms）→ 成功：markOutboxSent；失败：rollbackRedisStock + markOutboxCancelled
   （失败必须作废 Outbox——否则"Redis 回滚可再领 + 补发又给券"=超发）
⑤ 补发兜底：CouponOutboxSenderJob 扫未发送补发（唯一键防重）
```

**退券链（returnCoupon，RV30 现行语义）**：
```
① 查用户券（归属校验）→ ② 乐观锁恢复状态未使用（WHERE status=1，影响 0 行=幂等返回）
③ 只恢复用户券状态 —— 不回补模板 stock/remain、不减少 claimed
```

---

## 二、设计决策

| 决策 | 为什么 |
|---|---|
| **单 Lua 四步原子** | 查库存/查限领/扣减/计数无中间态——热点抢券无超发（返回值即原因码） |
| **hash tag `{templateId}`** | 双 KEY（stock + claimed:userId）Cluster 下必须同 slot，否则 CROSSSLOT（M15 修复） |
| **同步 MQ 而非异步** | 异步失败=Redis 已扣、DB 永不落（永久不一致）；同步失败立即回滚（~2ms 可接受） |
| **Outbox + 作废语义** | 极端失败（回滚后）还有补发；但回滚场景必须 markOutboxCancelled——"补发"与"回滚"互斥 |
| **退券不回补（RV30）** | 核销本来不扣 remain；回补→`remain+已发>total` 超发敞口 + "领→用→退→再领"绕限领；守恒口径：total = remain + 已领未用 + 已用 + 已退（不可再发） |
| **删除旧补偿链** | RV30 前的"退券 Redis 补偿链"（INCR stock/DECR claimed）与现行语义冲突 → 2026-09-23 整链删除（生产者零调用确认） |
| **限领防绕** | claimed 只增不减（退券不回退 perUserLimit 计数） |

---

## 三、组件映射（回链 K 系列）

| 链路步骤 | 组件 | 回链 |
|---|---|---|
| 原子领券 | Redis Lua（K02） | K02（脚本原子/hash tag slot） |
| 落库一致性 | MQ 同步 + Outbox（K01/K06） | K01（发送语义）+ dist-consistency D2 |
| 核销/退券幂等 | 乐观锁 + 唯一键（K17） | K17（条件 UPDATE） |
| 过期/补发/对账 | XXL-Job 三任务（K13） | K13（CouponExpireJob/OutboxSender/Reconcile） |
| 下单联动核销 | 订单落库后同步核销（L3-01） | L3-01（核销失败取消订单） |

---

## 四、拷打问题

1. **"为什么同步 MQ 不担心性能？"** Lua 0.1ms + 同步 2ms；抢券 QPS 经限流；一致性优先于这点延迟。
2. **"Outbox 和事务消息区别？"** Outbox=本地表+定时补发（自实现）；事务消息=Broker 回查；目的相同（本地事务与消息原子化），机制不同。
3. **"退券为什么不回补 remain？"** 核销不扣 remain（状态变化而已）；回补双算 → 超发敞口 + 绕限领；用户仍持有券，claimed 不回退。
4. **"热点券抢购怎么扛？"** Lua 原子 + 库存预热 + 限流 + 同步返回；更极端可分段库存（未实现，诚实说）。
5. **"return_coupon.lua 还用来干嘛？"** 只用于**领券失败回滚**（撤销本次领取）；退券路径已不用（RV30 删除旧链）。
6. **"方法注释说退券回补库存？"** **注释漂移观察**：`returnCoupon` 方法级 javadoc 仍写旧语义（回补），与 RV30 内联注释矛盾——**以 RV30 内联注释 + 实际代码（无回补调用）为准**。

---

## 五、话术

> "领券是一个 Lua 原子完成查库存、限领、扣减、计数，Key 用 templateId 做 hash tag 保 Cluster 同 slot。落库走同步 MQ——异步失败会 Redis 扣了 DB 不落；同步失败立即回滚 Redis，极端还有 Outbox 表加补发任务。退券我们改过语义：只恢复用户券状态，不回补库存不减已领次数，否则 remain 加已发超 total 造成超发，还能领用退再领绕限领；旧的退券补偿链已经整链删了。"

---

## 六、回链索引

| 主题 | 文档 |
|---|---|
| Lua 原子/hash tag | K02 |
| MQ 发送/Outbox | K01 + dist-consistency D2 |
| 乐观锁幂等 | K17 |
| Job 三任务 | K13 |
| 下单核销联动 | L3-01 |
| xhs 章节 | 31-优惠券 |
