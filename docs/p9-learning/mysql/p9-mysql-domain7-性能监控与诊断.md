# p9-mysql-domain7 · 性能监控与诊断（P9 深度实验）

> MySQL 轨道域 7/9。对照 vol-mysql 14+15 章。
> 覆盖：Performance Schema/sys Schema/锁监控/慢日志

## 实验环境

```
Docker: mysql-p9
```

---

## 实验 1：Performance Schema（实测）

```
events_statements_summary_by_digest: 71 条语句统计
```

### P_S 核心表

| 表 | 用途 |
|---|---|
| **events_statements_summary_by_digest** | SQL 语句统计（执行次数/耗时/扫描行） |
| **events_waits_summary_by_thread_by_event_name** | 等待事件（IO/锁/网络） |
| **data_locks** | 锁信息（8.0 替代 innodb_locks） |
| **data_lock_waits** | 锁等待 |
| **threads** | 线程（连接/后台线程） |

### P_S vs 慢日志

```
慢日志：只记"慢"的 SQL（超过 long_query_time）
P_S：记所有 SQL 的统计（含执行次数/扫描行/内存）
  → 慢日志发现"哪条慢"；P_S 发现"哪条频繁/扫描多"
```

### P9 追问链

- **Q：P_S 和慢日志区别？** → 慢日志=超时的 SQL；P_S=所有 SQL 统计（次数/耗时/扫描）
- **Q：怎么用 P_S 找慢 SQL？** → events_statements_summary_by_digest（按总耗时排序）
- **Q：P_S 开销？** → 默认开（可配置 instrument）；内存/锁开销可控

---

## 实验 2：sys Schema（实测）

```
sys.statements_with_full_table_scans（实测）：
  → 查询+全表扫描统计（no_index_used_pct）
  
sys.statements_with_runtimes_in_95th_percentile（实测）：
  → 最慢 5% 的 SQL（ALTER TABLE 16.79s/data_locks 12.77s）
```

### sys 核心视图

| 视图 | 用途 |
|---|---|
| **statements_with_full_table_scans** | 全表扫描的 SQL |
| **statements_with_runtimes_in_95th_percentile** | 最慢 5% SQL |
| **innodb_lock_waits** | InnoDB 锁等待 |
| **schema_index_statistics** | 索引使用统计 |
| **ps_check_lost_instrumentation** | P_S 配置检查 |

### P9 追问链

- **Q：sys Schema 是什么？** → P_S 的封装视图（易读的性能诊断接口）
- **Q：找慢 SQL 用什么？** → sys.statements_with_runtimes_in_95th_percentile（实测）
- **Q：找全表扫描？** → sys.statements_with_full_table_scans（实测）

---

## 实验 3：锁监控（实测）

```
performance_schema.data_locks（8.0 锁信息）
sys.innodb_lock_waits（锁等待）
```

### 锁监控排障

```sql
-- 当前锁
SELECT * FROM performance_schema.data_locks;
-- 锁等待
SELECT * FROM sys.innodb_lock_waits;
-- 事务
SELECT * FROM information_schema.innodb_trx;
-- 死锁日志
SHOW ENGINE INNODB STATUS;
```

### P9 追问链

- **Q：锁监控用什么？** → data_locks（锁）/innodb_lock_waits（等待）/innodb_trx（事务）
- **Q：8.0 锁表变了？** → innodb_locks/innodb_lock_waits 移除 → data_locks/data_lock_waits

---

## 实验 4：慢日志分析（实测）

```
sys.statements_with_runtimes_in_95th_percentile（实测）：
  ALTER TABLE t_lock ADD COLUMN c1 → 16.79s（最慢）
  SELECT data_locks → 12.77s
  DROP TABLE t_idx → 11.60s
```

### 慢日志分析流程

```
① 开启慢日志：slow_query_log=ON / long_query_time=1
② 收集：慢日志文件 / P_S digest
③ 分析：pt-query-digest / sys 视图
④ 定位：EXPLAIN 慢 SQL → 索引/优化
⑤ 修复：加索引/优化 SQL/调参数
```

### P9 追问链

- **Q：慢日志分析流程？** → 开启→收集→分析（pt-query-digest）→定位（EXPLAIN）→修复
- **Q：pt-query-digest 干什么？** → 聚合分析慢日志（按耗时/频率排序）
- **Q：生产慢日志策略？** → long_query_time=1；定期分析；P_S 补充（所有 SQL 统计）

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Performance Schema** | 所有 SQL 统计（次数/耗时/扫描行）；events_statements_summary_by_digest | 实测（71 条语句） |
| **sys Schema** | P_S 封装视图；找慢 SQL/全表扫描/锁等待 | 实测（95th percentile 视图） |
| **P_S vs 慢日志** | 慢日志=超时 SQL；P_S=所有 SQL 统计 | 实测（对比） |
| **锁监控** | data_locks（锁）/innodb_lock_waits（等待）/innodb_trx | 实测（data_locks） |
| **慢日志分析** | 开启→收集→pt-query-digest→EXPLAIN→修复 | 实测（95th percentile） |
| sys 视图 | statements_with_full_table_scans / 95th_percentile | 实测 |

---

## 补充实操 #1：慢 SQL 实战（P_S 排行分析）

### 实测（P_S 按耗时排行）

```
sql_text                                    execs  total_sec  max_sec
ALTER TABLE t_lock ADD COLUMN c1            1      16.792     16.792  ← 最慢
SELECT * FROM performance_schema.data_locks 1      12.769     12.769
DROP TABLE IF EXISTS t_idx                  1      11.603     11.603
CREATE TABLE t_idx                          1      9.223      9.223
CREATE SCHEMA IF NOT EXISTS p9              1      6.358      6.358
```

### 分析流程

```sql
-- ① P_S 找慢 SQL（按总耗时）
SELECT LEFT(DIGEST_TEXT,50), COUNT_STAR, 
  ROUND(SUM_TIMER_WAIT/1e12,3) as total_sec
FROM performance_schema.events_statements_summary_by_digest
ORDER BY SUM_TIMER_WAIT DESC LIMIT 10;

-- ② sys 视图找最慢 5%
SELECT * FROM sys.statements_with_runtimes_in_95th_percentile;

-- ③ EXPLAIN 慢 SQL（定位原因）
EXPLAIN ANALYZE SELECT ...;
```

---

## 补充实操 #2：EXPLAIN ANALYZE（8.0 实际执行）

### EXPLAIN vs EXPLAIN ANALYZE

```
EXPLAIN（预估）：
  → Index lookup on t_idx using idx_age (age=25)  (cost=0.35 rows=1)
  → cost=预估代价，rows=预估行数（基于统计信息）

EXPLAIN ANALYZE（实际执行）★ 8.0：
  → Index lookup ... (cost=0.35 rows=1) (actual time=0.0192..0.0192 rows=0 loops=1)
  → actual time=实际耗时，rows=实际行数，loops=循环次数
  → 预估 vs 实际对比（发现统计信息偏差）
```

### P9 追问链

- **Q：EXPLAIN 和 EXPLAIN ANALYZE 区别？** → EXPLAIN=预估（统计信息）；ANALYZE=实际执行（真实耗时/行数）
- **Q：什么时候用 ANALYZE？** → 预估和实际偏差大时（统计信息不准）；验证优化效果

---

## 补充实操 #3：锁等待实战

```sql
-- 事务1：持锁
BEGIN;
SELECT * FROM t_lock WHERE id=1 FOR UPDATE;
SELECT SLEEP(5);
COMMIT;

-- 事务2：等锁（被阻塞）
BEGIN;
SELECT * FROM t_lock WHERE id=1 FOR UPDATE;  -- 阻塞等锁

-- 排查：查锁等待视图
SELECT * FROM sys.innodb_lock_waits;
SELECT * FROM performance_schema.data_locks;
```

---

## 参考来源（权威核对）

```
官方文档：
  MySQL Reference Manual：
  → Performance Schema（dev.mysql.com/doc/refman/8.0/en/performance-schema.html）
  → sys Schema（sys schema 视图列表）
  → EXPLAIN ANALYZE（8.0 新特性）

经典书籍：
  《高性能 MySQL》第三版 → 监控/调优
  Percona 博客 → 生产监控实践
```

## 相关文件

- Docker: mysql-p9
- 实测: P_S 慢 SQL 排行（ALTER 16.79s）/EXPLAIN ANALYZE（actual time）/data_locks

## 补充参考来源

```
官方文档：
  → Performance Schema（dev.mysql.com/doc/refman/8.0/en/performance-schema.html）
  → sys Schema（sys-schema-objects.html）
  → EXPLAIN ANALYZE（8.0.18+）
```
