---
title: "03-路由与分片：派给谁、怎么并行"
series: "XXL-JOB 分布式任务调度系列"
previous: "02-executor-model.md"
next: "04-失败治理.md（待写）"
lab_refs:
  - "docs/中间件拷打/K13-XXL-Job.md（①概念表+②分片 vs 锁+追问4）"
source_refs:
  - "xhs cart/CartReconcileJob.java:82-100（SETNX+token+600s+Lua 比对删除，全文已核）"
---

# 03-路由与分片：派给谁、怎么并行

## §0 它干了什么（例子先行）

组里 3 台执行器，`cartReconcileJob`（每天 4 点对账）怎么跑才不打架？

```
路由 FIRST：Admin 永远派给注册表第一台 → 再加任务内 Redis 锁兜底
  SETNX myxhs:lock:cart:reconcile + UUID token + 600s TTL
  抢到才跑；finally 里 Lua 脚本"值==我的 token 才删"（RV30：不误删别人的锁）
结果：名义上 3 台，实际同时最多 1 台在对账——"锁把执行串起来"
```

- **它干了什么**：路由解决"派给哪台"；锁/分片解决"多台怎么办"。
- **本篇回答**：路由策略有哪些？分片广播怎么并行？xhs 为什么全用 FIRST+锁、分片零使用？

## §1 掌握卡（3 条）

1. **路由策略**：FIRST/LAST（首尾）/轮询/随机/一致性哈希——**xhs 统一 FIRST**（K13①：配合任务内分布式锁）；标准还有 FAILOVER（先探测存活再派发）。
2. **多实例互斥两族**：**锁族**（全量跑，抢锁者独占——xhs 24 任务全在这族）vs **分片族**（`SHARDING_BROADCAST`：每台领分片、并行扫表）——K13 原话：**"分片是把数据切开，锁是把执行串起来"**。
3. **xhs 零分片的理由**（K13②+追问4）：对账数据量小 + 要**全局一致视图**——分片怕"半成功合并"（3 台完成 2 台，差异视图不全）；锁+单实例全量更简单。

## §2 xhs 锁实现逐行（CartReconcileJob :82-100 实锤）

```java
String lockKey = "myxhs:lock:cart:reconcile";
String token = UUID.randomUUID().toString();
Boolean locked = stringRedisTemplate.opsForValue()
        .setIfAbsent(lockKey, token, 600, TimeUnit.SECONDS);   // 抢锁：SETNX+TTL
if (locked == null || !locked) { log.info("已有实例执行中，跳过"); return; }
try { doReconcile(); ... }
finally {
    // RV30：Lua 比对删除——"值==我的 token 才删"，防误删他人重获的锁
    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
}
```

三个设计点：**token 比对**（锁不是"谁都能删"的公共品）/ **600s TTL**（持锁者宕机锁自动过期——不永久卡死）/ **管理端点复用同一把锁**（`reconcileUserWithLock`——手动触发和定时任务不互踩）。（RV30=项目内这把锁实现的修订代号。）

## §3 分片广播怎么工作（xhs 不用，但要会答）

```
任务配置 SHARDING_BROADCAST → Admin 派给组内每台
每台执行时：getShardIndex()/getShardTotal() → 只处理 shardIndex 分片的数据
典型用法：用户表按 userId % total == index 扫——3 台各扫 1/3
```

**适用边界**（K13 口径反推）：大表并行扫描（分钟级扫不完）；数据可按分片键切开；各分片独立、**不需要全局合并视图**。

**与锁族的取舍表**：

| | 锁+FIRST（xhs） | 分片广播 |
|---|---|---|
| 并行度 | 1 | =实例数 |
| 视图 | 全局 | 分片局部（合并要额外设计） |
| 复杂度 | 锁 30 行 | 分片键设计+半成功处理 |
| 适合 | 小数据量对账/补偿 | 大表扫描/离线计算 |

## §4 阻塞策略（同任务重入怎么办：重入=上一轮没结束、下轮又到点；K13①）

上次还没跑完、下轮又到点：`SERIAL_EXECUTION`（排队，xhs 统一）/ `DISCARD_LATER`（丢弃后来的）/ `COVER_EARLY`（杀旧的跑新的）。**对账任务选排队**——杀旧的会留下半份差异视图。

## §5 边界（一句话翻篇）

- 一致性哈希/FAILOVER 细节 → 用时查官方文档（xhs 未用）。
- 锁的 Lua 完整源码 → 上面已贴；水位线（watermark=时间戳门槛，跳过太新的数据避免重复处理）逻辑 → 06。
- 为什么 FIRST 还要锁：FIRST 只是"通常派第一台"——注册表顺序会变（实例重启），锁才是正确性保证——面试追问防线。

## §6 Self-Test（4 题）

- Q1：路由策略 xhs 用哪个？为什么 FIRST 还要加锁？
- Q2：锁三件套（token/TTL/Lua 比对）各防什么？
- Q3：分片广播适合什么场景？xhs 为什么零使用？
- Q4：对账任务为什么选 SERIAL 而不是 COVER_EARLY？

## §7 衔接

- 本篇回答"派给谁"；下篇 04 失败治理：跑挂了怎么办（重试/misfire/告警/日报口径——K13 三事故主场）。
