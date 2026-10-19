# K11 — 组件深度拷打：Redisson

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：RLock 可重入（Hash+线程标识）、显式租期 vs Watchdog、Sentinel 模式、fail-closed、效率锁 vs 正确性锁
> 依据：xhs-project 代码（`RedissonConfig`/直连锁调用点/26 处 owner 校验）+ 45-Redisson深度拷打 + 01-分布式锁与Redlock + Redisson 3.31.0 源码语料

---

## 问题

Redisson 锁怎么用的？看门狗是什么？租期怎么定？Redis 挂了加锁会怎样？切主丢锁怎么办？为什么不用 RedLock？

---

## 面试可讲版（五段式）

### ① 原理层（RLock 源码核心）

**可重入锁的数据结构**：底层 **Hash**——`field = 客户端+线程标识`，`value = 重入次数`：
```
加锁 Lua（RedissonLock:220-224）：
  if (exists(KEY)==0 or hexists(KEY, 线程标识)==1) → hincrby(KEY, 线程标识, 1) + pexpire(KEY, 租期)
  → 同线程重入只加计数；不同线程 exists≠0 且 hexists=0 → 抢锁失败
解锁 Lua（:351-354）：
  hexists(KEY, 线程标识)==0 → return nil（防误放他人锁）
  hincrby -1：counter>0 → 重新 pexpire；counter==0 → del KEY + 发布解锁消息
```

**两个入口的语义差**：
```
lock(leaseTime)（:87）：阻塞直到拿到；leaseTime=-1 → 用 internalLockLeaseTime
tryLock(wait, lease, unit)（:231）：wait=最大等待；lease=持有租期
tryAcquireAsync（:188-193）：leaseTime == -1 ? internalLockLeaseTime（Watchdog）: leaseTime
```

**Watchdog（看门狗）**：
- **只有 `leaseTime=-1` 才启用**（tryLock(1, -1, SECONDS) / lock()）；后台任务按 `lockWatchdogTimeout / 3` 周期续期（`RedissonBaseLock.renewExpiration:127` / `scheduleExpirationRenewal:166`）
- `lockWatchdogTimeout` 默认 **30s**（Config.java:74）
- 边界：续期依赖**进程存活**——长 GC/被 kill 后锁按 TTL 释放；不解决时钟/调度问题

### ② 项目用法（关键差异点：直连锁 + 显式租期）

**业务代码 0 个 `@DistributedLock` 注解**——`DistributedLockAspect`（SpEL key、@Order(50)、watchdog、Redis 异常 fail-open）是**框架能力、未接入业务**（答辩不能说成项目事实）。真实用法是 `redissonClient.getLock(...)` 直连：

| 场景 | 调用 | 租期理由 |
|---|---|---|
| 支付创建/退款 | `tryLock(3, 10s)` | 短临界区；抢不到 → "系统繁忙"拒绝（`PaymentService:167/170`） |
| 用户资料/地址 | `tryLock(3, 10s)` | 防重复写；抢不到拒绝 |
| 缓存单飞刷新 | `tryLock(3或1, 10s)` | 抢不到走兜底/直查 DB |
| 库存分桶自愈重建 | `tryLock(0, 30s)` | 重建耗时可控 |
| SPU 缓存重建 | `tryLock(0, 300s)` | 按最坏时长给 |
| 热搜快照/索引重建 | `tryLock(0, 600s / 7200s)` | 长任务给足 |
| 5 个后台 Job | `tryLock(0, 4~50s)` | 多实例互斥，抢不到跳过本轮 |

**Sentinel 模式配置（`RedissonConfig`）**：
```
useSentinelServers：地址必须 redis:// 前缀；master/slave 连接池 2/16；idle/connect/timeout 10s
retryAttempts=5 / retryInterval=1000ms（覆盖切主窗口）；setCheckSentinelsList(false)（启动不依赖哨兵列表）
lockWatchdogTimeout=15s（项目调短；默认 30s）——只作用于 leaseTime=-1 模式
```

**P1-6 双层防御**（配置注释原文）：Redisson 层（re-试+15s watchdog）管"快速失败"；**应用层 DB 乐观锁（WHERE status/version）是最终防线**——锁失效时数据仍正确。

**失败语义（代码事实）**：
```
抢锁失败 → 拒绝（支付/用户抛"系统繁忙"）或跳过（Job）
Redis 异常 → tryLock 抛异常 → 业务失败【fail-closed】——宁可暂时不可用，不冒险重复执行
解锁 owner 校验：主代码 26 处 `isHeldByCurrentThread()`（业务直连 25 处 + 切面 1 处；变量名各异 locked/acquired/loadLocked）——统一语义"held 才 unlock"（防误放）
```

### ③ 坑（主动承认）

- **切主 2.3s（RV19）**：窗口内锁可能双持/丢失——**接受**（正确性不押锁上，见 ④）；Redisson `retry 5×1s` 覆盖重连
- **显式租期边界**：租期必须覆盖任务最坏时长（否则提前释放 → 双执行）——兜底靠幂等/唯一键；长 GC/kill 后锁按 TTL 释放，下游兜底必须永远在位
- **RV30 对账类锁**改随机 token + CAS（不靠 Redisson 锁身份判断）
- **文档纠偏**：曾把"Watchdog 续期 + 降级放行"写成项目事实——实际是未接入的切面能力（简历必须对得上代码的实例）
- **观测缺口（主动承认）**：锁失败率/持有时长**未做专门指标**，以业务异常/日志体现

### ④ 兜底分层表（这段就是答案：锁只降冲突，不保正确）

| 层 | 机制 | 证据 |
|---|---|---|
| **效率锁** | Redisson 直连锁（显式租期 10s~7200s）防重复执行；抢不到拒绝/跳过、Redis 故障 fail-closed | `PaymentService:167`、`InventoryService:657`、`CacheHelper:179`、5 个 Job |
| **唯一约束** | uk_order_event_seq、uk_claim_no、outbox uk、uk_user_sku——重复写直接冲突 | 各模块 Mapper/DDL |
| **乐观锁** | `UPDATE ... WHERE status=<期望值>`（订单状态机/券核销/支付单） | OrderEventService/CouponService |
| **Fence 状态机** | `t_tcc_fence` status 1→2/3（`UPDATE ... AND status=1`）；Cancel 先插 3 防空回滚 | TccFenceService |
| **版本控制/幂等/对账** | external_gte / MessageIdempotentHelper / 对账 Job | K03/K10/dist-consistency |

### ⑤ 拷打追问（面试官视角）

1. **"Redisson 锁 vs SETNX 手写？"** 可重入、自动续期（可选）、owner Lua 安全解锁、等待唤醒（发布订阅）、公平/读写锁；手写全要自己实现且易错（解锁不校验 owner = 误放）。
2. **"`leaseTime=-1` 的坑？"** Watchdog 只在进程活着时续期；长 GC/被 kill 后锁按 TTL 释放；且 watchog 依赖后台调度。
3. **"抢锁失败和 Redis 异常为什么处理不同？"** 本项目其实一致——都失败（拒绝/跳过/异常）；区别只是"预期内的竞争" vs "依赖故障"的文案入口。
4. **"锁粒度怎么定？"** 按业务键（`myxhs:lock:payment:pay:{orderId}`），冲突范围最小化 + 防热 key；不用全局锁。
5. **"为什么不用 RedLock？"** Kleppmann 论证：时钟假设 + 缺 fencing token；本项目锁是**效率用途**，正确性由 DB 约束保证——多主运维不值。
6. **"切主丢锁会超卖吗？"** 不会——锁丢了还有唯一约束/乐观锁/fence/对账四层兜底（分层表）。
7. **"怎么观测锁？"** 理想：失败率/等待时长/持有时长指标；本项目以业务异常+日志体现——**主动承认的缺口**。
8. **"Watchdog 你们启用了吗？"** 没有——显式租期更可预测；`lockWatchdogTimeout=15s` 只作用于 leaseTime=-1 模式（陷阱题：说"用了 watchdog"但答不出哪些方法就是背串了）。

### ⑥ 话术

> "Redisson 在我们项目不是注解切面，而是关键路径直连 + 显式租期：支付/退款 10 秒、库存重建 30 秒、SPU 300 秒、索引重建 2 小时，后台 Job 抢不到就跳过。抢锁失败拒绝或跳过，Redis 挂了直接失败——fail-closed，宁可暂时不可用不冒险重复执行。看门狗没启用：显式租期更可预测，代价是必须覆盖最坏时长，兜底靠唯一键和幂等。切主 2.3 秒会丢锁，但正确性从不押在锁上——唯一约束、乐观锁、TCC fence、对账四层都在。不做 RedLock，效率和正确性分开看。"

---

## 生产陷阱（Redisson 7 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **锁不设租期/租期过短** | 死锁或提前释放双执行——显式租期覆盖最坏时长 + 下游幂等 |
| 2 | **解锁不校验 owner** | 误放他人锁——`isHeldByCurrentThread()`（Redisson Lua 已防，手写必踩） |
| 3 | **把锁当正确性保证** | 切主/GC 都可能失锁——唯一约束/乐观锁兜底 |
| 4 | **以为用了 Watchdog** | 只有 leaseTime=-1 才启用——显式租期场景根本没续期 |
| 5 | **Sentinel 地址缺 redis://** | Redisson 启动报错——前缀 + checkSentinelsList(false) |
| 6 | **锁 key 粒度太粗** | 全局锁变瓶颈/热 key——业务键最小化 |
| 7 | **无锁观测** | 锁竞争劣化不可见——失败率/持有时长埋点（本项目缺口） |

---

## 发散追问地图（横向）

- **Redisson 能力全景**：可重入/公平/读写锁、信号量、CountDownLatch、布隆过滤器、限流器、RedLock
- **分布式锁谱系**：SETNX 手写 → Redisson → RedLock → ZK 临时顺序节点（fencing token）→ etcd revision
- **核心争论**：Kleppmann vs antirez（时钟假设/fencing）；效率锁 vs 正确性锁
- **连接层**：Sentinel/Cluster/单机模式配置；重试与超时；连接池
- **工程治理**：租期设计、超时预算、死锁排查（Redis 里看 Hash 持有者）、锁指标
- **替代思路**：无锁化（唯一约束先行/条件更新/事件溯源）

---

## 面试官评分点

**高级开发级**：RLock 可重入原理（Hash+线程标识+Lua）；租期与 Watchdog 关系；切主丢锁的边界。
**架构师加分**：**显式租期 vs Watchdog 的取舍**；fail-closed 的业务理由；效率锁/正确性锁分离 + 四层兜底表；"未接入的框架能力"与"项目事实"分开讲（诚信）；RedLock 不用的完整论证。
**危险信号**：说项目用了 Watchdog 却答不出方法（本项目正确答案是"未启用"）；把锁当正确性保证；解锁不校验 owner；锁不设租期。

---

## 本项目真实证据

**xhs-project**：
- `RedissonConfig`：Sentinel 模式（pool 2/16、retry 5×1000ms、checkSentinelsList(false)、watchdog 15s）+ P1-6 双层防御注释 + 不用 RedLock 论证（注释全文）
- 直连锁：`PaymentService:167/170（tryLock(3,10s)）`、`PaymentService:567/570（退款）`、`UserService`/`UserAddressService`/`CacheHelper:179`/`InventoryService:657`/`SpuService`/`IndexRebuildJob`/`HotSearchService` + 5 Job
- 26 处 `isHeldByCurrentThread()`（主代码：业务 25 + 切面 1）；`DistributedLockAspect`（未接入；`@DistributedLock` 唯一出现是 `SpELParser:14` 的 javadoc 示例）；`MessageIdempotentHelper`（真实）
- RV19 切主 2.3s / RV30 token+CAS

**源码语料（Redisson 3.31.0）**：
- `RedissonLock`：lock:87 / tryLock:231 / tryAcquireAsync:188 / 加锁 Lua:220-224 / 解锁 Lua:351-354
- `RedissonBaseLock`：renewExpiration:127 / scheduleExpirationRenewal:166
- `Config.lockWatchdogTimeout` 默认 30s（:74）

**课程对照**：xhs 45-Redisson深度拷打 + 01-分布式锁与Redlock（分层表）+ 决策台账 C1。

---

## 版本与来源

Redisson **3.27.0**（xhs-project 使用）；源码语料 **3.31.0**（`code/spring/redisson`，行号以 3.31.0 为准）；官方文档；xhs 代码与 RV19/RV30 记录。

## 真实性说明

锁机制为源码核验（Lua/续期/watchdog 默认值）；xhs 用法（直连锁/显式租期/fail-closed/0 注解/26 处校验/观测缺口）为代码事实；"未接入切面"与"观测缺口"均主动标注。
