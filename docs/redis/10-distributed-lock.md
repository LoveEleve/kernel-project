# 10 分布式锁与生产：从 SET NX 到 Redlock 争议（源码 + 项目实践）

> 定位：**原理解析 + 工程实践**·系列终篇——分布式锁的五层进化（`SET NX PX` → 看门狗 → 可重入 → Redlock → 争议），后接生产配置/排查/内存清单；Redisson 源码锚点（3.31.0）+ xhs-project 实践；面试速答见附录 A。
> 基线：Redis 7.2.11 + Redisson 3.31.0；实验证据：`redis-lab/evidence/11-distributed-lock-7.log`（本课新增 `scripts/11-distributed-lock.sh`）。

---

## 一、例子先行：并发扣库存

两个请求同时扣同一个 SKU 的库存：都读到 10、都减 1、都写回 9——超卖。**单机**用 `synchronized` 就行；**多实例**就需要一把所有实例都能看到的锁——Redis 锁。

本文按"五层进化"展开，每一层解决上一层的一个具体缺陷；实验里的五组数字（§附录 B）就是这五层的验证。

| 层 | 方案 | 解决什么 | 遗留问题 |
|---|---|---|---|
| 1 | `SET NX PX` | 原子获取 + 自动过期 | 租约 vs 业务时长 |
| 2 | 看门狗续期 | 业务没完、锁自动续 | 客户端挂/GC 停 → 仍会丢锁 |
| 3 | 可重入（Hash） | 同一线程嵌套不自杀 | — |
| 4 | Redlock（多实例） | 单实例宕机可用性 | 多数派≠强一致 |
| 5 | 争议/fencing | 正确性边界 | 场景选型 |

---

## 二、第一层：`SET NX PX`——原子获取 + 自动过期

### 2.1 为什么不是 `SETNX` + `EXPIRE`

两条命令之间宕机 → 锁没有过期时间 → **永锁**（outline 标题的事故）。必须一条命令同时"不存在才设 + 设过期"。

### 2.2 源码：SET 的 NX/PX

`SET key value NX PX 10000` 在 `setGenericCommand` 里一次完成（`t_string.c:84-106`，节选）：

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

拿不到锁时返回 `nil`；拿到时设置键并附过期。过期时间还会**重写成 `PXAT` 绝对时间戳**再传播（`t_string.c:118-126`），保证 AOF/从节点重放时语义不变：

```c
        /* Propagate as SET Key Value PXAT millisecond-timestamp if there is
         * EX/PX/EXAT flag. */
        if (!(flags & OBJ_PXAT)) {
            robj *milliseconds_obj = createStringObjectFromLongLong(milliseconds);
            rewriteClientCommandVector(c, 5, shared.set, key, val, shared.pxat, milliseconds_obj);
            decrRefCount(milliseconds_obj);
        }
```

**释放必须 Lua 比较再删**（get==token 才 del；09 已详述，实验里非持有者释放返回 0）。

### 2.3 实验：租约死结

```
A 拿锁（PX 800ms）→ OK
A 还在执行业务（1s），锁 PTTL=-2（已过期）
B 此时拿锁 → OK          ← 互斥被打破
```

**锁的租约不可能恰好等于业务时长**：设短了业务没完锁就丢（如上）；设长了客户端宕机后锁长期不释放。第一层的死结，只能靠"续期"解。

---

## 三、第二层：看门狗（Watchdog）——业务没完，锁自动续

### 3.1 Redisson 的机制

项目注解里一句话说清了（`DistributedLock.java`）：`leaseTime = -1 表示启用 Watchdog 自动续期（默认 30 秒锁，每 10 秒续期）`。

源码链路（Redisson 3.31.0）：

1. **默认租约 = 看门狗超时**（`RedissonBaseLock.java:112-117`）：

```java
    public RedissonBaseLock(CommandAsyncExecutor commandExecutor, String name) {
        super(commandExecutor, name);
        this.id = getServiceManager().getId();
        this.internalLockLeaseTime = getServiceManager().getCfg().getLockWatchdogTimeout();
        this.entryName = id + ":" + name;
    }
```

默认值 `lockWatchdogTimeout = 30 * 1000`（`Config.java:74`）。

2. **加锁成功后启动续期任务**（`RedissonLock.java:163-186` 节选）：

```java
        if (leaseTime > 0) {
            acquiredFuture = tryLockInnerAsync(waitTime, leaseTime, unit, threadId, RedisCommands.EVAL_NULL_BOOLEAN);
        } else {
            acquiredFuture = tryLockInnerAsync(waitTime, internalLockLeaseTime,
                    TimeUnit.MILLISECONDS, threadId, RedisCommands.EVAL_NULL_BOOLEAN);
        }
        ...
            if (acquired) {
                if (leaseTime > 0) {
                    internalLockLeaseTime = unit.toMillis(leaseTime);
                } else {
                    scheduleExpirationRenewal(threadId);
                }
            }
```

3. **每 `leaseTime/3`（10 秒）续一次**（`RedissonBaseLock.java:127-160` 节选）：

```java
        Timeout task = getServiceManager().newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                ...
                CompletionStage<Boolean> future = renewExpirationAsync(threadId);
                future.whenComplete((res, e) -> {
                    ...
                    if (res) {
                        // reschedule itself
                        renewExpiration();
                    } else {
                        cancelExpirationRenewal(null, null);
                    }
                });
            }
        }, internalLockLeaseTime / 3, TimeUnit.MILLISECONDS);
```

4. **续期本身也是一段 Lua**（`RedissonBaseLock.java:183-193`）——锁还在（`hexists`）才续：

```java
        return evalWriteSyncedAsync(getRawName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
                        "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                        "return 1; " +
                        "end; " +
                        "return 0;",
                Collections.singletonList(getRawName()),
                internalLockLeaseTime, getLockName(threadId));
```

### 3.2 实验

```
业务执行 3s 中持续续期 → PTTL=891ms（没有过期）
停止续期 1.3s 后       → PTTL=-2（已过期）
```

### 3.3 边界

- **看门狗随客户端心跳**：客户端宕机/长时间 GC/网络分区 → 续期停止 → 锁在 30 秒内过期，**不会永锁**——这是"最终释放"的保证，但也意味着"客户端自认为还持有锁"与"锁已过期"之间存在窗口（§六争议的根源）；
- 看门狗只对 `leaseTime=-1` 生效；显式租约 = 放弃自动续期；
- 续期任务按 `entryName`（`id + ":" + name`）登记在 `EXPIRATION_RENEWAL_MAP`，释放时取消（`RedissonBaseLock.java:106`）。

---

## 四、第三层：可重入——同一线程不阻塞自己

### 4.1 结构：Hash 计数

Redisson 把锁存成 Hash：`key = 锁名`、`field = uuid:threadId`、`value = 重入次数`。

**加锁 Lua**（`RedissonLock.java:218-228`）：

```java
    <T> RFuture<T> tryLockInnerAsync(long waitTime, long leaseTime, TimeUnit unit, long threadId, RedisStrictCommand<T> command) {
        return evalWriteSyncedAsync(getRawName(), LongCodec.INSTANCE, command,
                "if ((redis.call('exists', KEYS[1]) == 0) " +
                            "or (redis.call('hexists', KEYS[1], ARGV[2]) == 1)) then " +
                        "redis.call('hincrby', KEYS[1], ARGV[2], 1); " +
                        "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                        "return nil; " +
                    "end; " +
                    "return redis.call('pttl', KEYS[1]);",
                Collections.singletonList(getRawName()), unit.toMillis(leaseTime), getLockName(threadId));
    }
```

语义：锁不存在 **或** 是本线程持有 → `hincrby +1`、续租约、成功；否则返回剩余 PTTL（客户端据此等待）。

**解锁 Lua**（`RedissonLock.java:344-368`，节选；省略 unlock-latch 前缀）：

```java
                              "if (redis.call('hexists', KEYS[1], ARGV[3]) == 0) then " +
                                  "return nil;" +
                              "end; " +
                              "local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1); " +
                              "if (counter > 0) then " +
                                  "redis.call('pexpire', KEYS[1], ARGV[2]); " +
                                  ...
                                  "return 0; " +
                              "else " +
                                  "redis.call('del', KEYS[1]); " +
                                  "redis.call(ARGV[4], KEYS[2], ARGV[1]); " +
                                  ...
                                  "return 1; " +
                              "end; ",
```

语义：计数 -1；仍 >0 → 续租约、返回 0（锁保留）；减到 0 → 删锁 + **publish 解锁消息**（等待者订阅后立刻重试，不用傻等 PTTL）。

### 4.2 实验（Hash 结构模拟）

```
第1次获取 → count=1  PTTL=29923
同线程第2次获取 → count=2
释放1次 → count=1（锁保留）
释放第2次 → count=0；DEL → 1（已删）
```

### 4.3 边界

- 必须**同一线程**释放（field 带 threadId；跨线程 unlock 会失败/抛错）；
- 计数泄漏（异常路径忘了 unlock）由看门狗最终过期兜底。

---

## 五、第四层：Redlock——多实例多数派

### 5.1 算法

N 个**独立** Redis master（无主从关系）：客户端依次 `SET NX PX` 尝试；**多数派成功且总耗时 < 租约**才算拿到锁；否则释放所有已获取的。少数实例宕机不影响互斥性。

### 5.2 Redisson 实现

- `RedissonMultiLock`（`RedissonMultiLock.java:39`）：默认 `failedLocksLimit=0`——**全部**获取成功才算（`failedLocksLimit()` 返回 0 时失败即整体释放重试，`108-128`）；
- `RedissonRedLock`（`RedissonRedLock.java:31-52`）：**已标记 `@Deprecated`**，`failedLocksLimit = locks.size() - minLocksAmount()`，其中 `minLocksAmount = locks.size()/2 + 1`——即"多数派"。

### 5.3 实验（3 实例）

```
正常（3 实例）: 获取成功实例数=3/3
挂 1 台（6380）: 获取成功实例数=2/3（仍达多数派）
挂 2 台: 获取成功实例数=1/3（<2，失败并释放已获取的）
```

### 5.4 边界

多数派只保证"**最多一个客户端能同时拿到多数派**"，**不保证**锁到期后没有两个客户端同时认为"自己持有锁"（GC 暂停/时钟跳跃场景）——这正是下一节争议的靶心。

---

## 六、第五层：Redlock 争议——Kleppmann vs antirez

| 观点 | 内容 |
|---|---|
| **Kleppmann（不安全）** | Redlock 依赖两个假设：**时钟单调**、**进程不会长时间暂停**。GC 暂停数十秒、NTP 时钟跳跃、网络分区都能让"锁提前过期 + 客户端不知情"，两个客户端同时进入临界区；且 Redis 锁**没有 fencing token**，存储层无法拒绝过期锁的写 |
| **antirez（可用）** | 时钟假设在工程上可控（分步检测、边界检查）；"锁被暂停破坏"需要存储层配合——**fencing token**（单调递增的锁版本号，存储层拒绝旧 token 的写） |
| **共识** | 不做 fencing 的 Redlock 不能当"正确性锁"；**效率型锁**（缓存刷新、定时任务单点执行）单实例 + 看门狗足够；**正确性锁**（金额、对账）用 DB 行锁/唯一约束/乐观锁版本号，或 ZooKeeper 顺序临时节点 |

**场景决策表**：

| 场景 | 选择 |
|---|---|
| 定时任务防重复执行 | 单 Redis 锁（看门狗）即可 |
| 缓存重建互斥（09） | 单 Redis 锁 + 双重检查 |
| 库存/金额强互斥 | DB 行锁/乐观锁（fencing 思想） |
| 跨机房强一致 | ZK/etcd 类共识系统 |

---

## 七、项目实践：DistributedLockAspect

**注解**（`DistributedLock.java`）：`lockType`（MUTEX/READ/WRITE/FAIR，枚举在 65 行）、SpEL `key`、`waitTime=3`、`leaseTime=-1`（看门狗）、`prefix="lock"`。

**切面**（`DistributedLockAspect.java`）：

- 取锁：`leaseTime=-1 → lock.tryLock(waitTime, unit)`（启用看门狗）；否则显式租约（`56-64`）；
- **Redis 不可用降级放行**（`68-76`）——与限流 fail-open 同一哲学：可用性优先，日志告警；
- 释放：`finally` 中 `lock.isHeldByCurrentThread() → unlock()`，失败交给 Watchdog 兜底（`83-95`）；
- 锁类型映射（`101-115`）：MUTEX→`getLock`、FAIR→`getFairLock`、READ/WRITE→`getReadWriteLock`。

**配置**：各模块 yml 配 Redisson 连接（如 `my-xhs-notification/src/main/resources/application.yml:126`），**未显式设置 `lockWatchdogTimeout` → 默认 30s**（`Config.java:74`）。

---

## 八、生产配置、排查与内存（清单）

### 8.1 必配清单（承 07/08）

| 配置 | 作用 | 参考 |
|---|---|---|
| `maxmemory` + `maxmemory-policy` | 防 OOM，淘汰策略成对配 | 07 |
| `slowlog-log-slower-than` | 抓慢命令 | 08 |
| `lazyfree-lazy-eviction/expire` | 大 key 异步释放 | 07 |
| `cluster-require-full-coverage` | 集群完整性取舍 | 06 |
| `appendfsync` / `save` | 持久化档位 | 05 |

### 8.2 排查工具链

- `redis-benchmark -t set,get -c 50 -n 1000000`：先测本机 QPS 上限，线上 >80% 扩容；
- `SLOWLOG GET`：>10ms 的命令（08 已演示）；
- `MEMORY USAGE key` / `redis-cli --bigkeys`：找大 key；
- `LATENCY DOCTOR` / `INFO commandstats`：延迟源与命令频率；
- **锁专项**：`SCAN 0 MATCH lock:* COUNT 1000` + `PTTL`——**发现没有 TTL 的锁 key 就是事故**（永锁）；看门狗续期日志（Redisson `Can't update lock ... expiration`）；锁等待时长/获取失败率做监控指标。

### 8.3 内存优化三招（承 02/03/04）

1. 紧凑编码：小 Hash/List 走 listpack（`hash-max-listpack-entries` 等）；
2. 共享整数：0-9999 复用对象（02）；
3. 位图替多个 bool：10 个开关 → 1 个 bit 位（04），数量级省内存。

---

## 九、原理总结

1. **五层递进解决五个具体问题**：非原子 → `SET NX PX`；租约死结 → 看门狗；嵌套自杀 → 可重入；单点可用性 → Redlock；正确性边界 → fencing/选型（§二~§六）；
2. **锁的原子性全靠 Lua**：加锁（Hash 计数 + 过期）、续期（hexists 才 pexpire）、释放（比较删除）——每处都在"读-判断-写"之间不能被打断（§三/§四）；
3. **看门狗是"最终释放"而非"永久持有"**：客户端活着就续、挂了就到期——它缩小了死锁窗口，但没有消除"锁过期而客户端不知"的窗口（§3.3/§六）；
4. **Redlock 的共识**：多数派保证互斥的可用性，fencing token 才保证正确性；**效率型用单实例锁，正确性用存储层约束**（§五/§六）；
5. **生产是配置+排查+内存的组合**：没有"改一个参数就稳"的 Redis（§八）。

---

## 附录 A：面试速答（附带）

> 分布式锁按需求分五层：最基础是 `SET key value NX PX ttl` 一条命令原子获取+过期（`SETNX`+`EXPIRE` 两命令会在中间宕机时永锁）；但它有租约死结——锁过期业务还没完，别人就能拿锁，所以 Redisson 用看门狗：`leaseTime=-1` 时默认 30 秒租约、后台每 10 秒（租约/3）用 Lua 续期（hexists 才 pexpire），客户端挂了就停止续期、锁最终过期；可重入用 Hash 结构（field=uuid:threadId，value=重入计数），加锁 Lua"不存在或本线程持有→hincrby+1"，解锁 Lua"hincrby -1，>0 保留、=0 删锁并 publish 唤醒等待者"；多实例用 Redlock：N 个独立 master 多数派获取+总耗时小于租约，Redisson 的 MultiLock 默认要求全拿、RedissonRedLock（已废弃）要求多数派。争议上，Kleppmann 说 Redlock 依赖时钟单调和进程不暂停，GC/时钟跳跃会破坏互斥且没有 fencing token；antirez 回应配合 fencing token 就安全；结论是效率型锁（缓存刷新/定时任务）单实例+看门狗够用，正确性锁（金额/对账）要用 DB 行锁/唯一约束/版本号。生产上锁 key 必须有 TTL（扫 `lock:*` 发现没 TTL 就是事故），再配 slowlog/bigkeys/MEMORY USAGE/LATENCY DOCTOR 排查。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/11-distributed-lock.sh redis-lab-7   # 输出见 evidence/11-distributed-lock-7.log
```

| 观测点 | 结果 |
|---|---|
| 租约死结 | A `PX 800` 拿锁；1s 后 PTTL=-2，B 拿锁成功（互斥被打破） |
| 看门狗 | 续期 3s 后 PTTL=891ms；停止续期 1.3s 后 PTTL=-2 |
| 可重入 | Hash 计数 1→2→1→0，归零后 DEL=1 |
| Redlock | 3/3 → 挂 1 台 2/3 → 挂 2 台 1/3（失败） |

## 附录 C：源码坐标（Redis + Redisson + 项目）

| 内容 | 坐标 |
|---|---|
| `SET NX` 判定 / `PXAT` 传播 | `t_string.c:84-106`、`t_string.c:118-126`；`eval.c:475`（Lua 入口） |
| Redisson：租约默认值 | `org/redisson/config/Config.java:74`（`lockWatchdogTimeout=30*1000`） |
| Redisson：看门狗（登记/续期/续期 Lua） | `RedissonBaseLock.java:106`、`112-117`、`123-125`、`127-160`、`166-180`、`183-193` |
| Redisson：加锁/解锁 Lua | `RedissonLock.java:218-228`、`344-368`；租约分支 `163-186` |
| Redisson：MultiLock / RedLock | `RedissonMultiLock.java:39`、`53-128`、`300-302`；`RedissonRedLock.java:31-52`（`@Deprecated`） |
| 项目：锁注解（类型/看门狗说明） | `my-xhs-common/.../annotation/DistributedLock.java`（LockType 枚举 `65`） |
| 项目：锁切面（取锁/降级/释放/映射） | `my-xhs-common/.../aspect/DistributedLockAspect.java:48-96`、`101-115` |
| 项目：Redisson 配置 | `my-xhs-notification/src/main/resources/application.yml:126`（未显式配 watchdog → 默认 30s） |
| 生产清单/排查（前文） | 07（内存）、08（SLOWLOG）、06（集群）、05（持久化） |
