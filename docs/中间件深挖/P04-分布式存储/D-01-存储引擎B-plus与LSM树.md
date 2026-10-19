# P04-分布式存储 D-01 — 存储引擎：B+树 vs LSM 树

> **定位**：P04 第 1 篇（理论地基层）。三层齐：**例子 + 原理（10 维对比）+ 落点（已审素材交叉）**。
> **依据**：经典存储引擎知识 + 本系列已审素材（K17 InnoDB / K03 ES 段 / D-04 Bigtable memtable-SSTable）。
> **质量标准**：完整对比 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：同一个写入的两种命运

```sql
INSERT INTO t VALUES (10001, '小红书');   -- 假设主键 10001
```

```
【B+ 树（InnoDB）】定位主键所在的叶子页 → 页不在内存则读入（随机 IO）→ 页内插入排序
  → 页满则分裂（新页 + 父节点指针调整）→ 脏页后台刷盘（checkpoint 推进 redo）
【LSM（RocksDB/HBase 式）】WAL 追加 → memtable 内存插入（跳表有序）→ 直接返回
  → memtable 满则冻结 → 后台刷成不可变 SSTable → 定期 compaction 合并多层
```

**一句话**：B+ 是 **"原地更新（随机写换读快）"**；LSM 是 **"顺序追加 + 后台整理（写快，读时合并）"**——写放大的位置不同。

---

## 二、原理：10 维度对比

| # | 维度 | B+ 树 | LSM 树 |
|---|---|---|---|
| 1 | 写路径 | 定位页→原地改（随机写） | WAL 追加+memtable（顺序写） |
| 2 | 写放大 | 低（改 1 页，页分裂时高） | 中（WAL+flush+多层 compaction 重复写） |
| 3 | 读放大 | 低（O(log N) 定位 + 页缓存） | 高（memtable + L0 全扫 + 各层二分 + Bloom 过滤） |
| 4 | 点查 | 快（索引直达） | 慢（多层查找；Bloom 剪枝救命） |
| 5 | 范围查 | 极快（叶子链表顺序扫） | 快（各层有序归并；compaction 后接近 B+） |
| 6 | 空间放大 | 低（页内碎片 + 分裂预留） | 高（多版本/墓碑待合并；compaction 回收） |
| 7 | 崩溃恢复 | redo 重放脏页（checkpoint 缩短） | WAL 重放 memtable（快，内存量小） |
| 8 | 并发写 | 页锁/latch（热点页争用） | 内存结构锁（轻）+ 后台 compaction 抢 IO |
| 9 | 典型代表 | InnoDB/MySQL（K17）、MongoDB（WiredTiger B+变体） | RocksDB/LevelDB、HBase、Cassandra、Bigtable（D-04） |
| 10 | 适用负载 | 读多写少、点查/事务（OLTP） | 写多、追加型、时序/日志（写密集） |

### 补充：ES 的段是什么流派？

```
Lucene segment ≈ LSM 思想（不可变 + 后台 merge），但 ES 段合并用 TieredMergePolicy
（同层合并），与 RocksDB 的 leveled universal 有异曲同工——"不可变 + 批量整理"是通用解
（回链 P05-ES D-02）
```

---

## 三、怎么做 / 选型决策

```
读多写少 + 事务（订单/支付）→ B+（InnoDB）：点查快、事务成熟
写多 + 追加（日志/时序/消息位点）→ LSM（RocksDB）：顺序写吞吐
搜索（倒排）→ 专用结构（FST + 倒排，不在二选一里——D-01/P05 有独立答案）
混合 → 分层（热 memtable + 冷 SSTable；或 B+ 存索引 + LSM 存日志，视系统而定）
```

---

## 四、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么 |
|---|---|---|
| **B+ 原地更新** | 全追加 | 读路径 O(log N) 直达；代价是随机写（SSD 时代可接受） |
| **LSM 推迟合并** | 写时即整理 | 写延迟极低；代价是读放大+空间放大（compaction 还债） |
| **WAL 先行（两者共有）** | 直接改结构 | 崩溃恢复的唯一真相来源（redo/txnlog/commitlog 同构） |
| **不可变文件（LSM/SSTable/segment）** | 可变文件 | 免锁读 + 快照/GC语义简单（D-04、P05-ES D-02 同哲学） |

---

## 五、面试 Q&A（含面试官意图）

**Q1："B+树和 LSM 适用场景？"**（意图：选型能力）
读多点查事务 → B+（InnoDB）；写多追加 → LSM（RocksDB）；搜索 → 倒排（专用）。

**Q2："LSM 读为什么慢？怎么优化？"**（意图：读放大）
多层查找 → Bloom 跳过不存在 + block cache + compaction 减少层数。

**Q3："写放大/读放大/空间放大？"**（意图：三放大）
写：LSM 高（多层重写）vs B+ 低；读：LSM 高（多层合并）vs B+ 低；空间：LSM 高（待合并版本）。

**Q4："MySQL 为什么选 B+？"**（意图：场景匹配）
OLTP 读多+事务+点查——B+ 的随机读 O(log N) 和成熟事务正是解。

---

## 六、章节依赖

- **上游**：K17（InnoDB 实证）、K03/P05-ES（段/合并实证）、D-04（Bigtable memtable/SSTable/compaction）
- **下游**：D-02（分区复制：引擎之上的分布层）；D-06 OceanBase（LSM 引擎 + 分布式事务的结合）
- **横链**：P02（CommitLog/PartitionLog：追加日志哲学）；RocksDB（备查：column family/WAL/compaction 选项）
