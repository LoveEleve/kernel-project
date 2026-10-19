# K02 — 组件深度拷打：Redis

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：单线程/IO 多路复用、SDS/ziplist/skiplist、AOF/RDB、Sentinel/Cluster、缓存一致性、分布式锁
> 依据：geektime《Redis核心技术与实战》（41 讲）+《Redis源码剖析与实战》（49 章）+ xhs 41/01/45 + `xhs-demo/docs/redis/` 系列 + redis-lab

---

## 问题

Redis 怎么用的？为什么单线程还这么快？持久化怎么配？主从/哨兵/集群怎么选？缓存和 DB 怎么保持一致？分布式锁怎么实现？

---

## 面试可讲版（五段式）

### ① 数据结构与单线程模型（源码核心）

**为什么单线程**：多线程共享数据结构的并发控制（锁）开销 + 易错——Redis 直接单线程。**但"单线程"不绝对**：网络 IO + 键值读写单线程；持久化（BGSAVE/BGREWRITEAOF）、异步删除（UNLINK）、集群同步是额外线程；**6.0 的多线程只做网络 IO 读写**，命令执行仍单线程。

**为什么快**：内存操作 + 高效数据结构 + **IO 多路复用**（epoll 非阻塞：accept/recv 不阻塞单线程）——三者的综合结果，不是"单线程"本身。

**数据结构与编码**（源码剖析线）：

| 结构 | 底层编码 | 关键点 |
|---|---|---|
| String | **SDS**（len+alloc+flags+buf） | 二进制安全/O(1) 长度/预分配 |
| Hash | hashtable / **ziplist→listpack** | 小对象压缩编码省内存 |
| List | **quicklist**（ziplist 链表）→ listpack | 大 list 分片 |
| ZSet | skiplist + hashtable | **范围查询+点查询双支持**（跳表 O(logN)） |
| Set | intset / hashtable | 整数集合压缩 |

**阻塞点**（单线程的代价）：大 key 操作（HGETALL 百万元素）、复杂度高命令（KEYS）、AOF fsync 相关、删除大 key（用 UNLINK）——**慢操作 = 全客户端排队**。

### ② 持久化与内存

| 机制 | 要点 | 丢数据窗口 |
|---|---|---|
| **AOF** | appendonly；`everysec`（后台线程每秒 fsync）/always（每条）/no | everysec 最多丢 1s |
| **RDB** | 快照（fork + COW）；save 900 1 等触发 | 两次快照之间 |
| **混合持久化**（4.0+） | AOF 重写时前半 RDB 后半增量 AOF | —— |

**AOF 重写**：fork 子进程（COW 不影响主进程）重写紧凑版；重写期间新命令进 AOF 重写缓冲区。

**内存**：`maxmemory` + 淘汰策略——`noeviction`（写报错，**业务数据不能静默淘汰**）vs `allkeys-lru/volatile-lru`（缓存场景）；LRU 是**近似**（采样淘汰）。**过期删除**：惰性删除（访问时查）+ 定期删除（抽样）——不是到期立即删。

### ③ 高可用：主从 → 哨兵 → 集群

- **主从复制**：`PSYNC` 全量（fork RDB + repl buffer）+ 增量（`repl_backlog` + offset）——断线重连靠 backlog 续传
- **哨兵**：主观下线（sdown，单哨兵超时）→ 客观下线（odown，quorum 数）→ **哨兵 Leader 选举** → 故障转移（选新主：优先级/offset/runid）；**脑裂**：旧主分区继续写 → 恢复后数据丢失——缓解 `min-replicas-to-write`（xhs 未配，是边界，靠幂等/版本兜底）
- **Cluster**：**16384 slot** + gossip；多 key 必须同 slot（**hash tag `{userId}`**）；迁移（migrating/importing）；`CROSSSLOT` 错误是 Lua 写法的硬约束

**选型**（xhs 口径）：数据量未到 + 语义约束（Cluster 全面约束 Lua）→ **Sentinel**；**Cluster-ready 的实际做法是"单 Key 化 + 非原子部分幂等/对账兜底"**——点赞已改单 Key Lua（`like_forward_atomic`，代码注释"Redis Cluster 兼容，不跨 slot"；反向索引非原子靠对账修复，`LikeService:81-86/93-99`）；库存预扣仍是 **4 Key 脚本**（`List.of(totalKey, predeductKey, bucketKey, INDEX_KEY)`，跨 slot——这是暂不上 Cluster 的现实约束之一）——**迁移成本未完全前置**。

### ④ 缓存一致性与分布式锁（xhs 实战）

**缓存一致性（Cache Aside）**：
- 先更新 DB 再删缓存（推荐）；**延迟双删**（更新后延时再删，覆盖并发读回填）
- **逻辑过期**（value 带过期时间，异步重建）——xhs 商品缓存用（回链 27 题）
- **Canal 订阅 binlog → 失效缓存**（xhs 库存口径，回链 02/50 题）——比"业务代码双写"可靠
- 异常三件套：**穿透**（空值缓存 + 布隆）、**击穿**（互斥重建/单飞）、**雪崩**（过期时间打散 + 多级）

**分布式锁**：
- 基础：`SET key val NX PX ttl`（原子加锁）；释放用 **Lua（GET 比对 + DEL）**——防误删他人锁
- **看门狗续期**（Redisson）：业务未完成自动续 TTL；**Redlock 争议**（Kleppmann：时钟/GC 下不安全 vs antirez：够用）——xhs 用 Redisson 锁 + **DB 兜底**（正确性不押锁上）

### ⑤ 拷打追问（面试官视角）

1. **"单线程怎么处理那么多并发？"** IO 多路复用（epoll）+ 非阻塞；命令执行单线程避免锁——瓶颈在**命令本身耗时**（慢命令阻塞全局）。
2. **"AOF everysec 丢 1 秒能接受吗？"** 分数据：计数/购物车可容忍；订单等强数据在 MySQL（Redis 只是加速）；关键操作不等 AOF（回链 xhs 41）。
3. **"noeviction 满了会怎样？"** 写命令报错（OOM command not allowed）——**宁可写失败不静默丢**；有异常处理+告警。
4. **"哨兵脑裂怎么办？"** 少数派旧主继续写→双写；缓解：客户端写失败重试新主 + 业务幂等/版本号 + `min-replicas-to-write`（xhs 未配，边界）。
5. **"主从复制为什么有延迟？"** 异步复制 + 全量 fork/传输窗口；读写分离场景读到旧值——**强一致读走主**（回链 21 题）。
6. **"为什么用 Lua 不用分布式锁？"** 锁解决"互斥"（效率），Lua 解决"多 key 原子"（正确性）——库存/券必须 Lua（锁+多步仍可能中间失败）。
7. **"缓存和 DB 怎么保证一致？"** 先 DB 后删缓存 + 延迟双删/Canal 失效 + **对账兜底**（dist-consistency D3）——"最终一致"没有零差异承诺。
8. **"Redis 挂了会怎样？"** 分场景降级：锁→fail-closed（拒绝），限流→放行（保护性），缓存→直查 DB（限流配合），幂等→降级放行 + DB 唯一键兜底。

### ⑥ 话术

> "Redis 是 Sentinel 主从：AOF everysec + RDB 兜底，maxmemory 512M 用 noeviction——业务数据宁可写失败也不静默淘汰。不用 Cluster 是规模与语义驱动：数据量没到、slot 会全面约束 Lua；Cluster 兼容的实际做法是单 Key 化加幂等兜底——点赞已改单 Key Lua，库存多 Key 脚本的 slot 约束是暂不上 Cluster 的现实原因之一。切主演练过 2.3 秒完成、458 个状态键无损；异步复制会丢最后写入，所以正确性不押缓存。缓存一致性是"先 DB 后删缓存 + Canal 失效 + 对账兜底"；锁用 Redisson 但关键正确性靠 DB 兜底。"

---

## 发散追问地图（横向）

- **持久化**：RDB fork COW 细节、AOF rewrite 缓冲区、混合持久化。
- **高可用**：Sentinel 选举细节、Cluster 迁移/gossip、Codis 对比（35 讲）。
- **性能**：pipeline、慢查询、6.0 多线程 IO、CPU 架构影响（17 讲）、变慢排查（18/19 讲）。
- **内存**：碎片（activedefrag）、缓冲区（21 讲）、内存占用排查（20 讲）。
- **场景**：秒杀（36 讲）、数据倾斜（37 讲）、NVM（40 讲）。

---

## 面试官评分点

**高级开发级**：能讲清单线程+多路复用、AOF/RDB 取舍、哨兵切换、缓存三异常与锁的正确写法。
**架构师加分**：Sentinel vs Cluster 的规模/语义取舍；Cluster-ready 单 Key 化改造（点赞实证）与多 Key 脚本（库存 4 Key）的迁移成本边界；"正确性不押缓存"的边界设计；延迟双删/Canal/对账的分层组合。
**危险信号**：以为 Redis 绝对单线程（6.0 多线程 IO 不知道）；业务数据用 LRU 淘汰；释放锁不校验持有者；多 key Lua 不管 slot；以为切主不丢数据。

---

## 本项目真实证据

**xhs-project**：
- 配置：AOF everysec + `save 900 1/300 10` + `maxmemory 512mb + noeviction`（业务/缓存分实例）；Sentinel 26379（down-after=5000/failover-timeout=30000）；
- `RedisKeyConstants`（`myxhs:` 前缀分域）；**23 个 Lua**（库存分桶/购物车/券/社交）——单 Key 化示例 `like_forward_atomic`（注释"Cluster 兼容，不跨 slot"）；库存预扣 4 Key（跨 slot）；
- **RV19 切主演练**：2.3s 完成、458 状态键无损；`redis-zone-drill-20260918.md`（ReadFrom 读副本/回主）；双主 LWW 仿真；
- 库存分桶自动扩容（热点+大 key 合并解法）；`RedisHighMemoryUsage`/`RedisHighConnections` 告警。

**本线**：
- `xhs-demo/docs/redis/` 系列（10 篇：架构/数据结构/持久化/哨兵集群/内存/缓存/锁）；
- redis-lab（单线程模型/大 key/锁实验）；dist-consistency-lab D1（SETNX 幂等 + 降级放行）。

**geektime 依据**：核心技术与实战 03（IO 模型）/04-05（持久化）/06-09（主从哨兵集群）/23-27（缓存）/29-30（原子/锁）/33（脑裂）；源码剖析 01-06（架构/SDS/Hash/ziplist/quicklist/listpack/skiplist）。

---

## 版本与来源

geektime《Redis核心技术与实战》（蒋德钧）+《Redis源码剖析与实战》；xhs 41/01/45 题与部署笔记；`docs/redis/` 系列。

## 真实性说明

机制为标准知识（geektime 书源逐章对照）；xhs 配置/演练数据/23 Lua 均为项目事实（部署笔记与演练报告）。
