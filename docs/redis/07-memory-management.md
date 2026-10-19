# 07 内存管理：淘汰、过期与碎片整理（源码解析）

> 定位：**源码解析与原理**·核心点版——内存的三条回收路径：淘汰（近似 LRU/LFU）、过期（惰性 + 定期抽样）、碎片整理（jemalloc + activedefrag）；事件循环与 serverCron/beforeSleep 的执行时机见 01，本文不重复；面试速答见附录 A。
> 源码基线：Redis 7.2.11；实验证据：`redis-lab/evidence/08-memory-7.log`（本课新增 `scripts/08-memory-management.sh`）。

---

## 一、源码地图

| 文件 | 职责 | 本文引用点 |
|---|---|---|
| `evict.c` | 淘汰策略、近似 LRU/LFU、淘汰池 | `performEvictions`、`evictionPoolPopulate`、`LFULogIncr`、`estimateObjectIdleTime` |
| `db.c` | 访问触摸与过期入口 | `lookupKey`（更新 LRU/LFU）、`expireIfNeeded` |
| `expire.c` | 主动过期循环 | `activeExpireCycle`、`activeExpireCycleTryExpire`、常量 |
| `defrag.c` | 碎片整理 | `computeDefragCycles`、`activeDefragCycle` |
| `zmalloc.c` / `server.c` | 内存统计 | `zmalloc_get_rss`、`mem_fragmentation_ratio` |
| `config.c` / `server.h` | 策略与默认值 | 8 种策略、`maxmemory-samples`、`activedefrag` |

---

## 二、内存全景：maxmemory 与三类回收

**先看实验里的三个瞬间**（§附录 B）：`maxmemory 4mb` 下写 8000 个 1KB 键，`evicted_keys=5092`；切到 `noeviction` 再写，返回 **"OOM command not allowed when used memory > 'maxmemory'."**；flush 之后 `used_memory=1.15MB` 而 `used_memory_rss=7.33MB`、碎片率 **6.55**。

**"超没超"怎么算**（`getMaxmemoryState`，`evict.c:397-433`，节选）：

```c
    mem_reported = zmalloc_used_memory();
    ...
    /* Remove the size of slaves output buffers and AOF buffer from the
     * count of used memory. */
    mem_used = mem_reported;
    size_t overhead = freeMemoryGetNotCountedMemory();
    mem_used = (mem_used > overhead) ? mem_used-overhead : 0;
    ...
    /* Compute how much memory we need to free. */
    mem_tofree = mem_used - server.maxmemory;
```

注意：**AOF 缓冲与复制输出缓冲不算数**（`freeMemoryGetNotCountedMemory` 注释解释了原因：把 DEL 自身产生的缓冲也算进去会导致"越淘汰越要淘汰"的反馈循环）。

**什么时候淘汰**：每条命令执行前（`server.c:3989`）：

```c
    if (server.maxmemory && !isInsideYieldingLongCommand()) {
        int out_of_memory = (performEvictions() == EVICT_FAIL);
```

所以 **淘汰是命令执行路径上的同步动作**——这也解释了"淘汰会加延迟"。

| 路径 | 回收什么 | 触发时机 | 精确性 |
|---|---|---|---|
| 淘汰（evict） | 达到 maxmemory 时按策略删键 | 每条命令前 | 近似（采样） |
| 过期（expire） | 到期的键 | 访问时（惰性）+ 周期抽样（定期） | 最终一致（延迟删除） |
| 碎片整理（defrag） | 已释放但未归还的页 | 碎片率超阈值时（默认关） | 渐进（少量多次） |

---

## 三、淘汰：8 种策略 + 近似 LRU/LFU

### 3.1 策略矩阵

策略用"标志位组合"定义（`server.h:564-577`）：

```c
#define MAXMEMORY_FLAG_LRU (1<<0)
#define MAXMEMORY_FLAG_LFU (1<<1)
#define MAXMEMORY_FLAG_ALLKEYS (1<<2)
...
#define MAXMEMORY_VOLATILE_LRU ((0<<8)|MAXMEMORY_FLAG_LRU)
#define MAXMEMORY_VOLATILE_LFU ((1<<8)|MAXMEMORY_FLAG_LFU)
#define MAXMEMORY_VOLATILE_TTL (2<<8)
#define MAXMEMORY_VOLATILE_RANDOM (3<<8)
#define MAXMEMORY_ALLKEYS_LRU ((4<<8)|MAXMEMORY_FLAG_LRU|MAXMEMORY_FLAG_ALLKEYS)
#define MAXMEMORY_ALLKEYS_LFU ((5<<8)|MAXMEMORY_FLAG_LFU|MAXMEMORY_FLAG_ALLKEYS)
#define MAXMEMORY_ALLKEYS_RANDOM ((6<<8)|MAXMEMORY_FLAG_ALLKEYS)
#define MAXMEMORY_NO_EVICTION (7<<8)
```

8 种策略 = **候选集**（`allkeys` 全部键 / `volatile` 仅带 TTL 的键）× **算法**（LRU / LFU / random / TTL）。`volatile` 的坑：**如果一个键都没设 TTL，候选集为空，行为等同 noeviction**。

### 3.2 近似 LRU：24 位时钟 + 采样 + 淘汰池

**时间戳存在对象里**（`server.h:893-895`）：

```c
#define LRU_BITS 24
#define LRU_CLOCK_MAX ((1<<LRU_BITS)-1) /* Max value of obj->lru */
#define LRU_CLOCK_RESOLUTION 1000 /* LRU clock resolution in ms */
```

`redisObject.lru` 只有 24 位——存不下完整时间，只存**秒级时钟**（`evict.c:69-99`，节选）：

```c
unsigned int getLRUClock(void) {
    return (mstime()/LRU_CLOCK_RESOLUTION) & LRU_CLOCK_MAX;
}
...
unsigned int LRU_CLOCK(void) {
    unsigned int lruclock;
    if (1000/server.hz <= LRU_CLOCK_RESOLUTION) {
        lruclock = server.lruclock;
    } else {
        lruclock = getLRUClock();
    }
    return lruclock;
}
...
unsigned long long estimateObjectIdleTime(robj *o) {
    unsigned long long lruclock = LRU_CLOCK();
    if (lruclock >= o->lru) {
        return (lruclock - o->lru) * LRU_CLOCK_RESOLUTION;
    } else {
        return (lruclock + (LRU_CLOCK_MAX - o->lru)) *
                    LRU_CLOCK_RESOLUTION;
    }
}
```

`server.lruclock` 由 serverCron 定期刷新（`server.c:1326`）；**访问时"贴时间戳"**（`lookupKey`，`db.c:113-125`，节选）：

```c
    if (val) {
        /* Update the access time for the ageing algorithm.
         * Don't do it if we have a saving child, as this will trigger
         * a copy on write madness. */
        ...
        if (!hasActiveChildProcess() && !(flags & LOOKUP_NOTOUCH)){
            if (server.maxmemory_policy & MAXMEMORY_FLAG_LFU) {
                updateLFU(val);
            } else {
                val->lru = LRU_CLOCK();
            }
        }
```

**有子进程（fork）时不触摸**——否则每次读都触发 COW 复制页。

**采样与淘汰池**（`evictionPoolPopulate`，`evict.c:146-241`，节选）——这是"近似"的来源：

```c
void evictionPoolPopulate(int dbid, dict *sampledict, dict *keydict, struct evictionPoolEntry *pool) {
    int j, k, count;
    dictEntry *samples[server.maxmemory_samples];

    count = dictGetSomeKeys(sampledict,samples,server.maxmemory_samples);
    for (j = 0; j < count; j++) {
        ...
        if (server.maxmemory_policy & MAXMEMORY_FLAG_LRU) {
            idle = estimateObjectIdleTime(o);
        } else if (server.maxmemory_policy & MAXMEMORY_FLAG_LFU) {
            /* ... we want to evict keys with lower frequency
             * first. So inside the pool we put objects using the inverted
             * frequency subtracting the actual frequency to the maximum
             * frequency of 255. */
            idle = 255-LFUDecrAndReturn(o);
        } else if (server.maxmemory_policy == MAXMEMORY_VOLATILE_TTL) {
            /* In this case the sooner the expire the better. */
            idle = ULLONG_MAX - (long)dictGetVal(de);
        }
```

- 每次**随机抽 `maxmemory_samples` 个键**（默认 5，`config.c:3168`）；
- 换算成"该淘汰程度" `idle`（LRU 用空闲时长、LFU 用 `255-频率`、TTL 用"越早过期越大"）；
- 塞进一个 **16 槽的有序池**（`EVPOOL_SIZE 16`，`evict.c:54-61`）——池跨多次调用保留，所以"采样 + 池"共同逼近真实 LRU。

**淘汰执行**（`performEvictions`，`evict.c:538-757`，节选）：

```c
    if (server.maxmemory_policy == MAXMEMORY_NO_EVICTION) {
        result = EVICT_FAIL;  /* We need to free memory, but policy forbids. */
        goto update_metrics;
    }
    ...
            while (bestkey == NULL) {
                ...
                for (i = 0; i < server.dbnum; i++) {
                    db = server.db+i;
                    dict = (server.maxmemory_policy & MAXMEMORY_FLAG_ALLKEYS) ?
                            db->dict : db->expires;
                    if ((keys = dictSize(dict)) != 0) {
                        evictionPoolPopulate(i, dict, db->dict, pool);
                        ...
        /* Finally remove the selected key. */
        if (bestkey) {
            ...
            dbGenericDelete(db,keyobj,server.lazyfree_lazy_eviction,DB_FLAG_KEY_EVICTED);
            ...
            server.stat_evictedkeys++;
            signalModifiedKey(NULL,db,keyobj);
            notifyKeyspaceEvent(NOTIFY_EVICTED, "evicted",
                keyobj, db->id);
            propagateDeletion(db,keyobj,server.lazyfree_lazy_eviction);
```

要点：遍历所有 db 采样（`volatile` 从 `db->expires` 抽）→ 从池里取"最该淘汰"的键 → 删除并**传播 DEL**（主从/AOF 一致）+ `evicted_keys` 计数 + `evicted` 事件；`lazyfree-lazy-eviction` 可让删除走后台线程。

### 3.3 LFU：对数计数器 + 时间衰减

LFU 把 `lru` 的 24 位拆成"16 位时间 + 8 位频率"（`evict.c:260-326`，节选）：

```c
unsigned long LFUGetTimeInMinutes(void) {
    return (server.unixtime/60) & 65535;
}

unsigned long LFUTimeElapsed(unsigned long ldt) {
    unsigned long now = LFUGetTimeInMinutes();
    if (now >= ldt) return now-ldt;
    return 65535-ldt+now;
}

/* Logarithmically increment a counter. ... Saturate it at 255. */
uint8_t LFULogIncr(uint8_t counter) {
    if (counter == 255) return 255;
    double r = (double)rand()/RAND_MAX;
    double baseval = counter - LFU_INIT_VAL;
    if (baseval < 0) baseval = 0;
    double p = 1.0/(baseval*server.lfu_log_factor+1);
    if (r < p) counter++;
    return counter;
}

unsigned long LFUDecrAndReturn(robj *o) {
    unsigned long ldt = o->lru >> 8;
    unsigned long counter = o->lru & 255;
    unsigned long num_periods = server.lfu_decay_time ? LFUTimeElapsed(ldt) / server.lfu_decay_time : 0;
    if (num_periods)
        counter = (num_periods > counter) ? 0 : counter - num_periods;
    return counter;
}
```

- **对数增长**：频率越大越难 +1（`p = 1/(baseval*lfu_log_factor+1)`，默认 `lfu-log-factor=10`）——8 位计数器能表达 0~255 的巨大频率范围（Morris 计数器思路）；
- **时间衰减**：每 `lfu-decay-time` 分钟（默认 1）减一次，防止"曾经很热"的键永生；
- 新键从 `LFU_INIT_VAL=5` 起步（`server.h:3409`）——给新键一个"不被立刻淘汰"的缓冲；
- 访问时（`updateLFU`，`db.c:55-59`）：

```c
void updateLFU(robj *val) {
    unsigned long counter = LFUDecrAndReturn(val);
    counter = LFULogIncr(counter);
    val->lru = (LFUGetTimeInMinutes()<<8) | counter;
}
```

### 3.4 实验佐证

- `maxmemory 4mb` + 8000×1KB：`evicted_keys=5092`、`used_memory` 稳定在 ~4.19MB；
- `noeviction`：`SET → "OOM command not allowed when used memory > 'maxmemory'."`；
- LFU：`hot` 访问 200 次 → `OBJECT FREQ=10`（初始 5）；再灌 8000 个冷键触发淘汰后，`hot` 仍在、频率保持——被频繁访问的键抗淘汰。

### 3.5 原理与边界

1. **近似是有意的**：全量 LRU 链表要每个键两个指针（内存不可接受）；采样+池在常数内存下逼近；
2. `maxmemory-samples` 越大越接近精确、CPU 越高（默认 5）；`maxmemory-policy noeviction` 时 `performEvictions` 直接返回失败，写命令收到 OOM；
3. **volatile 候选为空**就是 noeviction（第一节的坑）；
4. 淘汰会**传播 DEL** 并触发 `evicted` 事件；大对象可选 `lazyfree-lazy-eviction` 异步释放。

---

## 四、过期：惰性删除 + 定期抽样

**先破除一个误解**（实验）：2000 个 `EX 2` 的键**全程没有任何访问**，3 秒后 `DBSIZE=0`、`expired_keys +2000`——**过期不等于删除，但 Redis 会主动去找它们**。

### 4.1 惰性删除：访问到才删

`lookupKey` 每次都先问 `expireIfNeeded`（`db.c:1775-1818`，节选）：

```c
int expireIfNeeded(redisDb *db, robj *key, int flags) {
    if (server.lazy_expire_disabled) return 0;
    if (!keyIsExpired(db,key)) return 0;

    /* If we are running in the context of a replica, instead of
     * evicting the expired key from the database, we return ASAP:
     * the replica key expiration is controlled by the master that will
     * send us synthesized DEL operations for expired keys. ...
```

- 主节点：真删，并**传播 DEL**（`deleteExpiredKeyAndPropagate`）保证 AOF/从节点一致；
- 从节点：**不主动删**，等主节点的 DEL；但读的时候按逻辑过期返回（保证读到的"已过期键"不会突兀地存在）——过期在 Redis 里是"主驱动的"。

### 4.2 定期删除：抽样扫描

常量（`expire.c:109-112`）：

```c
#define ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP 20 /* Keys for each DB loop. */
#define ACTIVE_EXPIRE_CYCLE_FAST_DURATION 1000 /* Microseconds. */
#define ACTIVE_EXPIRE_CYCLE_SLOW_TIME_PERC 25 /* Max % of CPU to use. */
#define ACTIVE_EXPIRE_CYCLE_ACCEPTABLE_STALE 10 /* % of stale keys after which
                                                   we do extra efforts. */
```

`activeExpireCycle`（`expire.c:142` 起）先按 `active-expire-effort`（默认 1，可到 10）放大参数：

```c
    unsigned long
    effort = server.active_expire_effort-1, /* Rescale from 0 to 9. */
    config_keys_per_loop = ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP +
                           ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP/4*effort,
    ...
    config_cycle_acceptable_stale = ACTIVE_EXPIRE_CYCLE_ACCEPTABLE_STALE-
                                    effort;
```

主循环对每个 db **抽样扫描**（`dictScan` + `expireScanCallback`），并且**只要"抽样中过期比例"仍高就继续**：

```c
        } while (data.sampled == 0 ||
                 (data.expired * 100 / data.sampled) > config_cycle_acceptable_stale);
```

时间预算：慢循环 = `hz`（默认 10）下的 25% CPU（`timelimit = config_cycle_slow_time_perc*1000000/server.hz/100`）；**快循环**在 beforeSleep 里跑，上限 1ms 且倾向不重复。两个入口：

```c
server.c:1054   activeExpireCycle(ACTIVE_EXPIRE_CYCLE_SLOW);   /* databasesCron */
server.c:1678   activeExpireCycle(ACTIVE_EXPIRE_CYCLE_FAST);   /* beforeSleep   */
```

### 4.3 原理与边界

1. **延迟删除**：过期键在被清掉前仍占内存；从"逻辑过期"到"物理删除"之间有窗口；
2. **抽样式**清理意味着"删除速度与写入速度赛跑"：过期键产生太快、抽样跟不上时，内存会短暂保留一批死键（`stat_expired_stale_perc` 就是给这个用的）；
3. **从节点不自己删**：主从数据一致性优先，代价是从节点可能短暂保留已过期键（读时逻辑隐藏）；
4. `LOOKUP_NOTOUCH`/`LOOKUP_NOEXPIRE` 等标志能豁免触摸与删除（如 DEBUG/复制场景）。

---

## 五、碎片：ratio 与 activedefrag

**它是什么**：分配器把内存借给 Redis 又收回后，**不一定还给操作系统**（jemalloc 按 size class 管理）；`mem_fragmentation_ratio = used_memory_rss / used_memory`（INFO 输出，`server.c:5671`）。实验里 flush 后的 **6.55** 就是"数据没了、RSS 还没还"。

**自动整理**（默认关闭，`config.c:3085`）——先算该不该干（`computeDefragCycles`，`defrag.c:893-919`，节选）：

```c
void computeDefragCycles(void) {
    size_t frag_bytes;
    float frag_pct = getAllocatorFragmentation(&frag_bytes);
    /* If we're not already running, and below the threshold, exit. */
    if (!server.active_defrag_running) {
        if(frag_pct < server.active_defrag_threshold_lower || frag_bytes < server.active_defrag_ignore_bytes)
            return;
    }

    /* Calculate the adaptive aggressiveness of the defrag */
    int cpu_pct = INTERPOLATE(frag_pct,
            server.active_defrag_threshold_lower,
            server.active_defrag_threshold_upper,
            server.active_defrag_cycle_min,
            server.active_defrag_cycle_max);
```

即：碎片率 ≥ `active-defrag-threshold-lower`（默认 10%）且可回收字节 ≥ `active-defrag-ignore-bytes`（默认 100MB）才启动；碎片率越高，允许的 CPU 占用从 `cycle-min`（1%）插值到 `cycle-max`（25%）。整理本体（`activeDefragCycle`，`defrag.c:924` 起）和过期一样**增量执行**（时间预算、`run_with_period(1000)` 每秒评估一次），且 **fork 期间直接跳过**（`if (hasActiveChildProcess()) return;`——边整理边 COW 只会更糟）。

**实验与默认值**：

| 观测 | 结果 |
|---|---|
| `used_memory / rss` | 1.15MB / 7.33MB → `mem_fragmentation_ratio 6.55`、`allocator_frag_ratio 6.52` |
| 配置 | `activedefrag=no`、threshold-lower=10、threshold-upper=100、ignore-bytes=100MB |
| `MEMORY DOCTOR` | "this instance is empty or is using very little memory..."（数据太少时无法诊断） |

**边界**：碎片整理是"用 CPU 换空间"（默认关，需显式开启）；`ignore-bytes` 防小数据瞎整理；已有 fork 时跳过；RSS 高≠内存泄漏（先看 `allocator_frag_ratio` 与 `MEMORY DOCTOR`）。

---

## 六、原理总结

1. **三条路径各管一段生命周期**：淘汰管"到上限时删谁"、过期管"到时间删谁"、碎片整理管"删了之后把页还回去"（§二表）；
2. **所有选择都是近似**：LRU 用 24 位时钟+采样+16 槽池；LFU 用对数计数器+衰减；过期用抽样扫描；碎片整理渐进执行——**在常数/低 CPU 成本下逼近理想算法**（§三~§五）；
3. **同步成本存在且可测**：淘汰在命令路径上同步执行（`performEvictions` 在 `processCommand` 前）、过期时间预算按 CPU 百分比控制、defrag 也限 CPU——都体现"单线程下不能长阻塞"（承 01）；
4. **复制语义优先**：过期由主节点驱动、从节点等 DEL；淘汰 DEL 也走传播——**内存回收不能破坏主从一致**（§4.1）；
5. **运维三件事**：`maxmemory`+`maxmemory-policy` 必须成对配置（否则 noeviction 写不进去）；过期清理有延迟（不要把"过期时间"当"删除时间"用）；碎片默认不整理（看 ratio、可开 activedefrag，改配置前先看内存构成）。

---

## 附录 A：面试速答（附带）

> Redis 内存回收有三块：淘汰、过期、碎片整理。淘汰有 8 种策略，两个维度——候选集是 allkeys 还是 volatile（只挑带 TTL 的键），算法是 LRU/LFU/random/TTL；LRU 是近似的，对象里只有 24 位时钟（LRU_BITS），淘汰时随机抽 maxmemory-samples（默认 5）个键、按空闲时间塞进 16 槽的淘汰池，取最该走的删；LFU 是"16 位时间 + 8 位对数频率"，频率对数增长、按 lfu-decay-time 衰减，新键从 5 起步，所以热键抗淘汰、冷键容易被清；noeviction 时内存满写命令直接报 OOM。过期不是定时器：惰性删除是访问时检查过期就删并传播 DEL；定期删除是 activeExpireCycle 按 hz 频率抽样扫 expires 字典，过期比例高就继续、有 CPU 时间预算（默认 25%），快循环在 beforeSleep、慢循环在 databasesCron；从节点不主动删过期键，等主节点的 DEL 保证一致。碎片是"分配器没把页还给 OS"，mem_fragmentation_ratio = rss/used；activedefrag 默认关，碎片率超过 threshold-lower 且可回收超过 ignore-bytes 才启动，CPU 占用在 cycle-min/max 之间插值，fork 期间跳过。三者的共同点是：**都在常数成本下做近似，且都不能破坏单线程与主从一致**。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/08-memory-management.sh redis-lab-7   # 输出见 evidence/08-memory-7.log
```

| 观测点 | 结果 |
|---|---|
| LRU 淘汰 | `maxmemory 4mb` + 8000×1KB → `evicted_keys=5092`、`used_memory≈4.19MB`（上限内） |
| noeviction | 写入返回 `OOM command not allowed when used memory > 'maxmemory'.` |
| LFU 热键 | `hot` 访问 200 次 → `OBJECT FREQ=10`；灌 8000 冷键后 `hot` 仍在、FREQ 保持 |
| 惰性过期 | `SET tmp v EX 1` → 1.2s 后 `GET` 为 nil（未主动删，访问触发） |
| 定期过期 | 2000 个 `EX 2` 键零访问，3s 后 `DBSIZE=0`、`expired_keys +2000` |
| 碎片 | flush 后 `used_memory 1.15MB / rss 7.33MB` → `mem_fragmentation_ratio 6.55`；`activedefrag=no` |

## 附录 C：源码坐标系（本文引用）

| 内容 | 坐标 |
|---|---|
| 8 种策略标志 | `server.h:564-577` |
| LRU 位宽/时钟精度 | `server.h:893-895`；`server.c:1326`（lruclock 刷新） |
| LRU 时钟与空闲时间 | `evict.c:69-78`、`evict.c:80-90`、`evict.c:92-99` |
| 淘汰池结构 | `evict.c:54-61`（`EVPOOL_SIZE 16`） |
| 采样与池填充 `evictionPoolPopulate` | `evict.c:146-241` |
| 淘汰执行 `performEvictions` | `evict.c:538-757`（删除/统计 `670-682`）；调用点 `server.c:3989` |
| 超限判定 `getMaxmemoryState` | `evict.c:397-433` |
| LFU 实现 | `evict.c:283-296`（时间）、`299-307`（对数增长）、`319-326`（衰减）；`db.c:55-59`（`updateLFU`） |
| LFU 常量/默认 | `server.h:3409`（`LFU_INIT_VAL 5`）；`config.c:3164-3165`（log-factor/decay） |
| 访问触摸 `lookupKey` | `db.c:88-140` |
| 惰性删除 `expireIfNeeded` | `db.c:1775-1818` |
| 主动过期常量/循环 | `expire.c:109-112`、`expire.c:142-324`（effort `146-155`、停止条件 `296-308`）；调用点 `server.c:1054`（慢）、`1678`（快） |
| 单键过期尝试 | `expire.c:54-67` |
| 碎片整理 | `defrag.c:893-919`（`computeDefragCycles`）、`924` 起（`activeDefragCycle`） |
| defrag 配置默认 | `config.c:3085`（activedefrag=no）、`3160-3163`（cycle/threshold）、`3223`（ignore-bytes 100MB） |
| 碎片率输出 | `server.c:5671`（`mem_fragmentation_ratio`） |
| 采样默认 | `config.c:3168`（`maxmemory-samples 5`） |
| 内存上限默认 | `config.c:3214`（`maxmemory 0`） |
| effort/hz 默认 | `config.c:3182-3183`（active-expire-effort 1）、`server.h:114`（hz 10） |
