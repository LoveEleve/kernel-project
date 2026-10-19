# Redis 深度解析系列（10 篇）

> 定位：L2-2「Redis 单元」交付物——**源码解析线**（框架源码逐层深读）+ **原理解析线**（原理 + 生产 + 面试溢出）双线合体。
> 权威大纲：`docs/plan/learning-path/分布式/outlines/04-Redis/`（9 篇）。
> 源码基线：**Redis 7.2.11**（对齐生产 `redis:7-alpine`）+ **Redisson 3.31.0**；项目锚点：`source-code/xhs-project`。
> 实验环境：`redis-lab`（7/8 双实例容器）；每篇均可复现（脚本 + evidence 日志）。

---

## 一、系列总表（10 篇 ↔ 大纲 9 篇）

| # | 文章 | 核心内容 | 对应大纲 | 实验脚本 | 证据日志 |
|---|---|---|---|---|---|
| 01 | [线程模型：事件循环、命令执行与 IO 线程](01-architecture-thread-model.md) | `aeMain`/`processCommand`/`bio`/IO 线程 | 01 | `01`~`02` | `01-blocking-ping-7/8`、`02-bigkey-7` |
| 02 | [键空间与对象模型：dict、robj 与编码切换](02-data-structures.md) | 四层结构/渐进 rehash/编码切换/慢命令 | 02 | `03`~`04` | `03-encoding-7`、`04-slow-ops-7` |
| 03 | [底层数据结构：SDS、intset、listpack、quicklist、skiplist](03-basic-data-structures.md) | 五种结构内部 + 字节级走查 | 02 | （复用 02 证据） | — |
| 04 | [高级数据结构：Stream、Bitmap、HyperLogLog、Geo](04-advanced-data-structures.md) | rax+listpack/PEL、位寻址、稀疏-稠密、geohash | 03 | `05` | `05-advanced-7` |
| 05 | [持久化与复制：RDB、AOF 与主从复制](05-persistence-replication.md) | fork+COW/多部 AOF/PSYNC+backlog | 04 | `06` | `06-persistence-replication-7` |
| 06 | [哨兵与集群：Sentinel 与 Cluster](06-sentinel-cluster.md) | S_DOWN/O_DOWN/选举/状态机、槽/MOVED-ASK/Gossip | 05 | `07` | `07-sentinel-cluster-7` |
| 07 | [内存管理：淘汰、过期与碎片整理](07-memory-management.md) | 近似 LRU/LFU、惰性+定期过期、activedefrag | 06 | `08` | `08-memory-7` |
| 08 | [事务、发布订阅与客户端缓存](08-transaction-pubsub-tracking.md) | MULTI/WATCH、PubSub、tracking 失效推送 | 07 | `09` | `09-transaction-pubsub-tracking-7` |
| 09 | [缓存穿透、击穿、雪崩与一致性](09-cache-penetration-consistency.md) | 四问四解 + xhs-project 全景对照 | 08 | `10` | `10-cache-patterns-7` |
| 10 | [分布式锁与生产：从 SET NX 到 Redlock 争议](10-distributed-lock.md) | 五层锁/看门狗/可重入/Redlock/生产清单 | 09 | `11` | `11-distributed-lock-7` |

> 大纲 02/03（核心结构与高级结构）在交付时拆为 02、03、04 三篇——底层结构与高级结构各自独立成篇，编号顺延。

---

## 二、阅读路径

- **主线（推荐顺序）**：01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10
  - 01 是全局前提（单线程/事件循环），后续每篇都引用它；
  - 02→03→04 是数据结构递进（键空间 → 底层结构 → 高级结构）；
  - 05/06/07 解决"不挂/不丢/不撑"，08/09/10 是特性与实战。
- **面试冲刺**：每篇附录 A（速答）+ 09/10 的项目对照表 + 附录 C（源码坐标）。
- **按主题跳读**：高可用（05/06）、内存（07）、数据结构（02/03/04）、实战（08/09/10）。

---

## 三、复现实验

```bash
cd redis-lab && docker compose up -d
bash scripts/05-advanced.sh redis-lab-7          # 例：04 篇的实验
# 其余：scripts/06-persistence-replication.sh / 07-sentinel-cluster.sh /
#       08-memory-management.sh / 09-transaction-pubsub-tracking.sh /
#       10-cache-patterns.sh / 11-distributed-lock.sh
```

- 容器：`redis-lab-7`（7.2.11，对齐生产）、`redis-lab-8`（8.x 对照）；
- 证据：`redis-lab/evidence/`（每篇附录 B 引用对应日志）；
- 纪律：脚本默认 ≤30 万级规模、结束自动清场、`maxmemory 128MB` 上限（非预占）。

---

## 四、写作与核对约定（证据纪律）

1. **源码坐标**：写稿时从源码现场截取片段与行号；每篇完成后做一轮**深审**——结构体/函数端点/引用区间逐条回源，实验数字与 evidence 日志逐字对照；
2. **实验数字不凭记忆**：一律从 evidence 日志复制（05/08 两篇深审各抓到一处"凭记忆写错实验文案"，已修正）；
3. **项目锚点**：`xhs-project` 行号逐一核对（09 篇深审修正 11 处函数端点）；
4. **统一结构**：`一~N 节`（源码/原理主干）+ 附录 A 面试速答 / B 实验与复现 / C 源码坐标；
5. **代码展示**：引用片段逐字保留，节选以 `...` 标注。

---

## 五、项目锚点索引（xhs-project）

| 主题 | 类/文件 | 所在文章 |
|---|---|---|
| 缓存三防（空值/抖动/锁重建）、延迟双删 | `my-xhs-common/.../cache/CacheHelper.java` | 09 |
| 布隆过滤器（加载期降级） | `my-xhs-product/.../service/SpuService.java` | 09 |
| 滑窗限流（ZSet + Lua，fail-open） | `my-xhs-common/.../aspect/RateLimitAspect.java` | 09 |
| 分布式锁（Redisson + SpEL + 降级） | `my-xhs-common/.../annotation/DistributedLock.java`、`aspect/DistributedLockAspect.java` | 10 |
| 领券 Lua（同 slot hash tag） | `my-xhs-coupon/src/main/resources/lua/claim_coupon.lua` | 09 |
| 库存回补 Lua / 分桶 | `my-xhs-inventory/.../service/InventoryService.java` | 09 |
| 点赞正反索引（Lua 原子 + 对账） | `my-xhs-analytics/.../service/LikeService.java` + `lua/like_atomic.lua` | 09 |
| CDC 缓存失效（Canal/Binlog + MQ） | `my-xhs-inventory/.../consumer/InventoryCacheEvictConsumer.java`、`my-xhs-content/.../consumer/CacheEvictConsumer.java` | 09 |

---

## 六、状态与已知缺口

- ✅ 10 篇全部完成，且每篇均通过一轮逐条回源的深审（修正记录在各篇对话中）；
- ✅ 实验脚本 `01`~`11` 全部可复现，evidence 齐全；
- ⏳ 待补：Pipeline vs 逐条 RTT 对照实验（计划 `scripts/12-pipeline-vs-loop.sh`，README 已占位）；
- 📌 边界说明：Bloom 过滤器属 RedisBloom 模块（非核心），本系列在 04 讲位图基础、09 讲工程应用，不展开模块源码。
