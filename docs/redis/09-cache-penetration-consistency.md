# 09 缓存穿透、击穿、雪崩与一致性：四问四解（原理 + 项目实践）

> 定位：**原理解析线·工程实践**——每个问题按"根因 → 解法 → Redis 侧机制 → xhs-project 落地"展开；Redis 源码锚点集中在 `SET NX`/Lua/`EXPIRE`；面试速答见附录 A。
> 源码基线：Redis 7.2.11；实验证据：`redis-lab/evidence/10-cache-patterns-7.log`（本课新增 `scripts/10-cache-patterns.sh`）；项目锚点：`source-code/xhs-project`（行号已核对）。

---

## 一、先把四个问题分清

**实验里的四个片段**（§附录 B）：空值占位 `__CACHE_NULL__` 带 120s TTL 挡住"查不存在"；`SET NX` 让第二个客户端拿到 `(nil)`；非持有者释放锁被 Lua 拒绝（返回 0）；固定 TTL 的 8 个键 TTL 全是 59，随机偏移后散成 60~68。

| 问题 | 数据在哪 | 触发形态 | 解法关键词 |
|---|---|---|---|
| **穿透** | **DB 也没有** | 大量请求查不存在的 key | 空值缓存 / 布隆过滤器 / 限流 |
| **击穿** | DB 有，缓存**刚好过期** | 热点 key 过期瞬间并发重建 | 互斥锁 / 逻辑过期 / 请求合并 |
| **雪崩** | DB 有，**大批同时**失效 | 同时过期或缓存层整体故障 | TTL 随机 / 多级缓存 / 限流熔断 |
| **一致性** | DB 已更新，缓存还是旧值 | 更新顺序与并发读写 | Cache-Aside / 延迟双删 / CDC |

四个问题的根因各是一个词：**不存在、同一时刻、同时、顺序**。

---

## 二、穿透：查询不存在的数据

### 2.1 根因

缓存的语义是"查过就记住"，但"查不到"不会被记住——同一个不存在的 ID 每次都要去 DB 找一遍。恶意攻击者用不存在的 key 反复打，缓存形同虚设。

### 2.2 空值缓存（兜底）

把"不存在"也当成一种结果缓存起来，给一个**短 TTL**：

```
SET product:miss "__CACHE_NULL__" EX 120     → GET 返回占位符，TTL=120
```

项目实现（`CacheHelper.java`）：

- `NULL_PLACEHOLDER = "\u0000__CACHE_NULL__\u0000"`（`CacheHelper.java:46`）——用不可能出现在业务数据里的 NUL 包裹串做哨兵；
- DB 查不到时 `redisOperator.set(key, NULL_PLACEHOLDER, 2, TimeUnit.MINUTES)`（`CacheHelper.java:128`）——**空值只缓存 2 分钟**；
- 读路径 `isNullPlaceholder(cached)` 命中时直接返回 `null`（`CacheHelper.java:111-113`）。

**边界**：空值 TTL 是"攻击者尝试速度"与"内存占用"的平衡——攻击者 60 秒能试 10 万个 key，2 分钟 TTL 意味着最多沉淀 20 万个空值键；所以空值缓存要配**限流**（§2.4）。

### 2.3 布隆过滤器（预防）

布隆过滤器能回答"**一定不存在**"（存在则可能误判）——这正是穿透需要的语义：判断 key 不在 → 直接返回，连 Redis 都不查。

- 原理回顾（04）：位图 + 多个哈希函数，插入置位、查询看是否全置位；空间是"位级"的，代价是误判率与**不支持删除**；
- Redis 核心**没有**布隆命令——项目用 Redisson 的 `RBloomFilter`（底层 RedisBloom 模块或 Redisson 自实现位图）：

```java
spuBloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");
boolean isNewFilter = spuBloomFilter.tryInit(1_000_000L, 0.01);
```
（`SpuService.java:171-172`：100 万容量、1% 误判率）

读路径（`SpuService.java:511-513`）：

```java
if (bloomFilterReady.get()) {
    ...
    if (!spuBloomFilter.contains(spuId)) {
```

工程细节（值得抄）：**加载期降级**——`bloomFilterReady` 在异步加载完成前保持 false，期间跳过布隆（穿透到 DB 但功能正确）；加载失败也只是"少了优化"不影响可用（`SpuService.java:144`、`205-241`）。

### 2.4 限流兜底（第二道防线）

布隆过滤了"不可能存在的 key"，但挡不住"合法范围内的恶意高频"。项目用 ZSet 滑动窗口限流（`RateLimitAspect.java:60-69`）：

```lua
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
local count = redis.call('ZCARD', KEYS[1])
if count < tonumber(ARGV[2]) then
    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[5])
    redis.call('EXPIRE', KEYS[1], ARGV[4])
    return 1
else
    return 0
end
```

"清窗口 → 计数 → 未超限则记录"三步在**一段 Lua 里原子完成**（否则并发下计数不准）；member 用 `时间戳:UUID` 保证唯一（`RateLimitAspect.java:79`）。

### 2.5 小结

穿透的完整防线：**布隆（过滤不存在）→ 空值缓存（记住不存在）→ 限流（限制尝试速度）**——三层各挡一类流量。

---

## 三、击穿：热点 key 过期瞬间

### 3.1 根因

某个热点 key 在 TTL 到期的一瞬间，所有并发请求同时 miss → 同时查 DB → DB 连接被打爆。与穿透的区别：**数据在 DB 里，只是缓存暂时没有**。

### 3.2 互斥锁：只让一个请求去重建

Redis 侧核心就一条命令：`SET key value NX PX ttl`——"不存在才设置 + 毫秒级过期"。源码里 `NX` 的判定（`t_string.c:84-106`，节选）：

```c
    found = (lookupKeyWrite(c->db,key) != NULL);

    if ((flags & OBJ_SET_NX && found) ||
        (flags & OBJ_SET_XX && !found))
    {
        if (!(flags & OBJ_SET_GET)) {
            addReply(c, abort_reply ? abort_reply : shared.null[c->resp]);
        }
        return;
    }
```

实验（§附录 B）：A `SET lock:hot tokenA NX PX 3000 → OK`；B 同样的命令 → `(nil)`（拿不到锁，去读旧缓存或等待重试）。

**释放锁必须用 Lua 比较再删**（防误删别人的锁）：

```lua
if redis.call('get',KEYS[1])==ARGV[1] then
    return redis.call('del',KEYS[1])
else
    return 0
end
```

实验：非持有者 `tokenB` 释放 → **0**（拒绝，锁还在）；持有者 `tokenA` → **1**（释放成功）。get 与 del 若分两条命令，中间可能过期换主，就会删掉别人的锁——**Lua 的原子性（脚本在单线程上一次执行完，入口 `evalGenericCommand`，`eval.c:475`）是正确性的前提**。

项目实现（`CacheHelper.getWithCacheAsideLock`，`CacheHelper.java:158-240`）：Redisson `tryLock(3, 10, SECONDS)`（等待 3s、租约 10s）→ **双重检查**（拿到锁后再查一次缓存，可能别的线程已回填）→ 查 DB 回填 → finally 释放；拿不到锁的线程 `jitter` 退避后重试读缓存（`CacheHelper.java:209`）。更外层的 `DistributedLockAspect` 还统一处理了"Redis 不可用降级放行"与 Watchdog 兜底（`DistributedLockAspect.java:61-92`）。

### 3.3 逻辑过期（不阻塞的替代）

缓存**不设物理 TTL**，value 里多存一个 `expire_time` 字段：

- 读：`expire_time` 未到 → 直接返回；已到 → **返回旧值**，同时异步（或拿锁）触发重建；
- 特点：请求永远不阻塞在"重建"上，代价是窗口内读到旧值；
- 适合"能容忍短暂旧数据"的热点（如排行榜）；不适合强一致场景。

### 3.4 请求合并（SingleFlight）

同一进程内把"同 key 的并发请求"合并成一个真实查询、其余等结果（Go 的 singleflight / Java 侧可用 `CompletableFuture` + `ConcurrentHashMap` 实现）。适合**单机极高并发**；多实例下仍需 Redis 锁兜底。

### 3.5 边界

1. **锁超时 vs 重建时长**：`PX` 要大于重建耗时，否则锁提前过期 → 又并发；Redisson 的 Watchdog 用续期解决；
2. **双重检查是必须的**：等锁期间别人可能已回填；
3. **锁粒度**：项目用 `lock:cache:<key>`（`CacheHelper.java:177`）——按 key 而非全局，避免无关热点互相阻塞。

---

## 四、雪崩：同时过期 / 整层故障

### 4.1 根因

"同时"：整点批量设置的缓存同时到期，或 Redis 集群故障导致**所有**缓存同时不可用——两种形态都让 DB 瞬间承接全量流量。

### 4.2 TTL 随机偏移（离散时间）

回填时给 TTL 加随机抖动（`CacheHelper.java:125-126`）：

```java
long randomOffset = ThreadLocalRandom.current().nextLong(0, timeoutSeconds / 6 + 1);
redisOperator.set(key, dbResult, timeoutSeconds + randomOffset, TimeUnit.SECONDS);
```

实验对照：8 个键固定 `EX 60` → TTL 全为 59（同秒到期）；`60 + rand(0..10)` → TTL 散布在 60~68。**让"同时"变成"一段时间内陆续"**。

### 4.3 多级缓存与降级

- **空间换离散**：本地缓存（Caffeine）挡第一层，Redis 第二层，DB 最后；Redis 故障时本地缓存仍能顶一段时间（数据可能旧）；
- **降级路径**：项目的 `getWithCacheAside` 在 Redis 不可用时**直接查 DB 且不回填**（`CacheHelper.java:104-110`）——不回填是为了"Redis 恢复前不再往它身上加压"。

### 4.4 限流与熔断保护 DB

Redis 不可用时，限流本身也会失效——项目的选择是 **fail-open**（`RateLimitAspect.java:96-105`）：记指标、放行请求，"保证核心业务可用"；同时配合熔断/降级（如只读降级、排队）保护 DB。**这不是摆烂，是明确取舍：先保可用，再谈限流。**

### 4.5 边界

- 随机 TTL 解决"同时过期"，但解决不了"Redis 整体宕机"——后者靠多级缓存 + 熔断 + 容量冗余；
- 抖动范围别太大（`timeout/6` 是项目选择）：太大等于平均 TTL 变长、内存占用上升。

---

## 五、缓存一致性

### 5.1 Cache-Aside：最常用的读写路径

项目 `getWithCacheAside`（`CacheHelper.java:101-135`）：

1. 查缓存 → 命中返回（空值哨兵返回 `null`）；
2. miss → 查 DB；
3. DB 有 → 回填缓存（TTL+抖动）；DB 无 → 回填空值（2 分钟）。

写路径：**更新 DB → 删缓存**（不是更新缓存）——删除比更新安全：避免并发写把旧值写进缓存。

### 5.2 顺序之争：先删缓存还是先写 DB？

| 方案 | 并发反例 |
|---|---|
| 先删缓存，再写 DB | 删完后、写库前，读线程 miss 读**旧值**回填 → 写库完成，缓存里是旧值（长期） |
| **先写 DB，再删缓存** | 写库后、删缓存前，读线程读到**旧缓存**返回（短暂）；删完即恢复 |

后者窗口更小（删缓存很快），是主流选择。但极端并发下仍有风险：删缓存失败、主从延迟导致读线程从**从库**读到旧值回填。

### 5.3 延迟双删与 CDC

**延迟双删**（`CacheHelper.delayDoubleDelete`，`CacheHelper.java:295-357`）：

1. 第一次删（DB 更新后立即）；
2. **延迟 500ms** 再删一次（覆盖"更新 DB → 第一次删"之间并发读回填的旧值）。

500ms 的推导写在注释里：主从同步延迟（~200ms）+ 业务读耗时（~100ms）+ 安全余量（~200ms）（`CacheHelper.java:307-311`）；删缓存失败时发 MQ 兜底（`CACHE_EVICT_TOPIC`，`CacheHelper.java:337-352`），由消费者异步重试删除（`CacheEvictConsumer.java:27-66`）。

**CDC（更彻底）**：Canal 监听 MySQL Binlog → MQ → 缓存服务删除。项目的库存模块就是这套（`InventoryCacheEvictConsumer.java:19-31` 注释：`Canal 方案：监听 MySQL Binlog → 解析变更 → 主动删除 Redis 缓存，保证数据库变更后缓存一定被清除`）——**不依赖业务代码记得删缓存**，是"最终一致"的工程化形态。

### 5.4 读己之写

写主读从时，复制是异步的（05）——用户刚下单就查库存，可能读到从库旧值。手段：**写后读主**（关键路径强制走主库）、或 `WAIT numreplicas timeout` 等从确认（`replication.c:3532` `waitCommand`）。

### 5.5 边界

1. **没有 100% 一致的缓存**：所有方案都是"缩小不一致窗口"；最终一致性靠**过期兜底 + 对账修复**（项目多处体现：点赞反向索引失败靠对账，`LikeService.java:89-97`）；
2. 一致性要求高的路径：直接读 DB / 读主，别让缓存参与；
3. **删缓存必须可重试**：本地重试 3 次（`deleteAfterUpdate`，`CacheHelper.java:258-292`）+ MQ 兜底 + CDC 三重保障。

---

## 六、项目全景对照

| 问题 | 项目实现 | 位置 | 要点 |
|---|---|---|---|
| 穿透 | 空值缓存 | `CacheHelper.java:46/128` | NUL 哨兵 + 2 分钟 TTL |
| 穿透 | 布隆过滤器 | `SpuService.java:134/171-172/511-513` | Redisson RBloomFilter（100 万/1%），加载期降级 |
| 穿透/雪崩 | 限流 | `RateLimitAspect.java:60-69` | ZSet 滑窗 Lua，fail-open |
| 击穿 | 分布式锁重建 | `CacheHelper.java:158-240` | tryLock + 双重检查 + jitter 重试 |
| 击穿/原子性 | 领券 Lua | `claim_coupon.lua:26-48` | 查库存+限领+扣减+计数 一次原子；`{templateId}` 同 slot |
| 原子性 | 库存回补 Lua | `InventoryService.java:818-819` | `INCRBY` total+桶 一次原子 |
| 雪崩 | TTL 随机 | `CacheHelper.java:125-126` | timeout + rand(0, timeout/6) |
| 雪崩 | 降级 | `CacheHelper.java:104-110` | Redis 不可用 → 查 DB 不回填 |
| 一致性 | 延迟双删 | `CacheHelper.java:295-357` | 两次删 + 500ms 推导 + MQ 兜底 |
| 一致性 | CDC | `InventoryCacheEvictConsumer.java:19-31` | Canal + Binlog + MQ 删缓存 |
| 幂等/对账 | 点赞正反索引 | `LikeService.java:71-97` | 正向 Lua 原子 + 反向索引对账修复 |

---

## 七、原理总结

1. **四问四解、根因各一词**：穿透=不存在（布隆/空值/限流）、击穿=同一时刻（锁/逻辑过期/合并）、雪崩=同时（随机 TTL/多级/熔断）、一致性=顺序（先写后删/双删/CDC）（§一）；
2. **Redis 侧的核心武器只有两个**：`SET NX PX`（互斥）与 **Lua 原子**（比较删除、滑窗、领券、库存）——所有模式都建立在这两块之上（§二~§五）；
3. **工程实现必须带降级**：布隆加载期跳过、限流 fail-open、Redis 不可用查 DB 不回填、锁获取失败退避重试——**可用性优先**是这些代码的统一取向（§2.3/4.3/4.4）；
4. **一致性靠"缩窗口 + 兜底"**：延迟双删缩窗口、MQ 重试补失败、CDC 摆脱业务依赖、对账修残留——没有一劳永逸的一致（§五）；
5. **面试落点**：能讲清"穿透 vs 击穿"、"先写后删为什么优于先删后写"、`SET NX` 释放为什么必须 Lua、随机 TTL 的取值依据（`timeout/6`）——就是本文的全部。

---

## 附录 A：面试速答（附带）

> 穿透是查根本不存在的数据：布隆过滤器说"一定不存在"就直接返回（Redisson RBloomFilter，项目里 100 万容量 1% 误判，加载期降级跳过）；兜底是缓存空值（哨兵串 + 短 TTL，项目 2 分钟）；再配 IP 限流（ZSet 滑窗 Lua）。击穿是热点 key 过期瞬间并发重建：用 SET NX PX 抢锁，只让一个请求查 DB 回填，其他人等锁或读旧值——释放锁必须 Lua 比较再删防误删；项目用 Redisson tryLock(3s,10s) + 双重检查 + jitter 重试；不阻塞的方案是逻辑过期（value 存 expire_time，返回旧值异步重建）。雪崩是大批同时失效或缓存层故障：TTL 加随机偏移（项目 timeout + rand(0, timeout/6)）、本地缓存多级降级、限流熔断保护 DB（项目 Redis 挂了 fail-open + 查 DB 不回填）。一致性用 Cache-Aside：写路径"更新 DB 再删缓存"（比先删后写窗口小）；延迟双删解决并发读回填旧值（项目 500ms = 主从延迟 200 + 读 100 + 余量 200，删失败发 MQ 兜底）；更彻底是 Canal 监听 Binlog 异步删缓存（项目库存模块）。最后记住：没有 100% 一致的缓存，只有缩窗口 + 过期兜底 + 对账。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/10-cache-patterns.sh redis-lab-7   # 输出见 evidence/10-cache-patterns-7.log
```

| 观测点 | 结果 |
|---|---|
| 空值缓存 | `SET product:miss __CACHE_NULL__ EX 120` → `GET=__CACHE_NULL__`、`TTL=120` |
| 互斥锁 | A `SET NX PX 3000 → OK`；B → `(nil)`；非持有者 Lua 释放 → `0`；持有者 → `1` |
| 领券 Lua | 库存 5/限领 2：`1, 1, -2`（达限领）；库存 1：`1, -1`（领完） |
| TTL 抖动 | 固定 `EX 60` → TTL 全 59；随机偏移 → `60 63 63 66 68 68 60 68` |

## 附录 C：源码坐标（Redis + 项目）

| 内容 | 坐标 |
|---|---|
| `SET` 的 NX/XX 判定 | `t_string.c:84-106`（NX 分支 `99-106`；`OBJ_SET_NX` `71`） |
| Lua 原子执行入口 | `eval.c:475`（`evalGenericCommand`；单线程不被打断，承 01） |
| `EXPIRE` 命令 | `expire.c:551`（`expireGenericCommand`） |
| `WAIT`（读己之写） | `replication.c:3532`（`waitCommand`） |
| 项目：CacheHelper（三防/空值/抖动/锁/双删） | `my-xhs-common/.../cache/CacheHelper.java:32`、`46`、`101-135`、`158-240`、`258-292`、`295-357` |
| 项目：布隆过滤器 | `my-xhs-product/.../service/SpuService.java:134`、`144`、`171-172`、`205-241`、`511-513` |
| 项目：滑窗限流 Lua | `my-xhs-common/.../aspect/RateLimitAspect.java:60-69`、`79`、`96-105` |
| 项目：领券 Lua | `my-xhs-coupon/src/main/resources/lua/claim_coupon.lua:26-48` |
| 项目：库存回补 Lua | `my-xhs-inventory/.../service/InventoryService.java:818-819` |
| 项目：点赞原子/对账 | `my-xhs-analytics/.../service/LikeService.java:71-97`、`128-138`；`lua/like_atomic.lua` |
| 项目：CDC/Canal | `my-xhs-inventory/.../consumer/InventoryCacheEvictConsumer.java:19-31`；`my-xhs-content/.../consumer/CacheEvictConsumer.java:27-66` |
| 项目：分布式锁 AOP | `my-xhs-common/.../aspect/DistributedLockAspect.java:61-92` |
