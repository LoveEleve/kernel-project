# p9-mysql-domain5 · 查询优化（P9 深度实验）

> MySQL 轨道域 5/9。对照 vol-mysql 13+14 章。
> 覆盖：优化器/EXPLAIN/慢查询/子查询优化/执行计划

## 实验环境

```
Docker: mysql-p9
测试表: p9.t_idx（10000 行）
慢查询: slow_query_log=ON, long_query_time=0（实测）
```

---

## 实验 1：优化器代价估算（EXPLAIN FORMAT=TREE 实测）

```
EXPLAIN FORMAT=TREE SELECT * FROM t_idx WHERE age=25
→ Index lookup on t_idx using idx_age (age=25)  (cost=0.35 rows=1)
```

### 优化器工作原理

```
优化器目标：选代价最小的执行计划
  代价 = IO 成本 + CPU 成本
  → 索引查找：IO 少（B+Tree 定位）→ 代价低
  → 全表扫描：IO 多（读所有页）→ 代价高

优化器决策：
  统计信息（行数/基数/采样）→ 估算各计划代价 → 选最小
  → 统计信息不准 → 选错索引（ANALYZE TABLE 更新）
```

### P9 追问链

- **Q：优化器怎么选索引？** → 基于统计信息估算代价（IO+CPU），选最小
- **Q：统计信息不准怎么办？** → ANALYZE TABLE 更新；或 FORCE INDEX 强制
- **Q：EXPLAIN FORMAT=TREE？** → 8.0 格式（树形展示执行计划，含代价）

---

## 实验 2：EXPLAIN 深入（全字段）

### 关键字段（域3 已讲 type/key/Extra）

```
id：查询编号（子查询层级）
select_type：SIMPLE/PRIMARY/SUBQUERY/DERIVED
table：访问的表
type：访问类型（const/ref/range/index/ALL）
possible_keys：可能使用的索引
key：实际使用的索引
rows：预估扫描行数
filtered：过滤后行数百分比
Extra：额外信息（Using index/Using filesort/Using temporary）
```

### P9 追问链

- **Q：EXPLAIN 的 Extra 有哪些？** → Using index（覆盖索引）/Using filesort（排序）/Using temporary（临时表）
- **Q：Using temporary 说明？** → 需要临时表（如 GROUP BY/DISTINCT 无法用索引）

---

## 实验 3：慢查询日志（实测）

```
slow_query_log = ON（实测开启）
long_query_time = 0（超过 0 秒就记录）
slow_query_log_file = /var/lib/mysql/...-slow.log
```

### 慢查询分析

```sql
-- 开启
SET GLOBAL slow_query_log=ON;
SET GLOBAL long_query_time=1;  -- 超过 1 秒记录

-- 分析工具
mysqldumpslow -s t -t 10  # 最慢 10 条
pt-query-digest slow.log  # Percona 分析
```

### P9 追问链

- **Q：慢查询日志怎么开？** → SET GLOBAL slow_query_log=ON; long_query_time=1
- **Q：怎么分析慢查询？** → mysqldumpslow / pt-query-digest
- **Q：long_query_time 生产设多少？** → 1 秒（一般）；严格场景 0.5 秒

---

## 补充：子查询优化（EXISTS vs JOIN）

### MySQL 子查询优化

```
MySQL 优化器对子查询优化有限：
  EXISTS：相关子查询（对外层每行执行一次）
  IN（子查询）：MySQL 8.0 改写为 JOIN（半连接 semi-join）
  JOIN：通常最优（优化器成熟）

优化策略：
  ① EXISTS → JOIN（通常更快）
  ② IN（子查询）→ JOIN（8.0 自动改写）
  ③ 相关子查询 → 改写为 JOIN/派生表
```

### P9 追问链

- **Q：EXISTS vs JOIN？** → JOIN 通常更快（优化器成熟）；EXISTS 相关子查询逐行执行
- **Q：IN（子查询）怎么优化？** → MySQL 8.0 改写为 semi-join（JOIN）
- **Q：子查询优化策略？** → EXISTS→JOIN；相关子查询改写为 JOIN/派生表

---

## 补充：执行计划稳定性

```
执行计划漂移：
  统计信息更新/数据分布变化 → 优化器选不同计划 → 性能抖动

稳定方案：
  ① FORCE INDEX（强制索引）
  ② 固定执行计划（8.0 optimizer_switch）
  ③ 定期 ANALYZE TABLE（更新统计信息）
  ④ 直方图（8.0，手动统计信息）
```

### P9 追问链

- **Q：执行计划漂移怎么办？** → FORCE INDEX / 固定 optimizer_switch / ANALYZE TABLE
- **Q：直方图干什么？** → 手动提供列分布统计（优化器更准）

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **优化器代价** | IO+CPU 成本估算，选最小；统计信息驱动 | 实测（cost=0.35 rows=1） |
| **EXPLAIN FORMAT=TREE** | 8.0 树形执行计划（含代价） | 实测（Index lookup cost=0.35） |
| **慢查询日志** | slow_query_log=ON/long_query_time=1；pt-query-digest | 实测（ON/long_query_time=0） |
| **子查询优化** | EXISTS→JOIN；IN（子查询）8.0 改写 semi-join | 补充（叙事） |
| **执行计划漂移** | FORCE INDEX/固定 optimizer_switch/ANALYZE TABLE | 补充（叙事） |
| Extra 字段 | Using index（覆盖）/Using filesort/Using temporary | 补充（叙事） |

---

## 补充 #1：联接算法（P9 高频，官方 EXPLAIN 文档）

### 三种联接算法

| 算法 | 原理 | 适用 |
|---|---|---|
| **Nested Loop（嵌套循环）** | 外层每行→内层查找 | 小表驱动大表+索引 |
| **Block Nested Loop** | 内层批量读入 buffer（无索引时） | 无索引联接（慢） |
| **Hash Join**（8.0.18+） | 小表建哈希表→大表探测 | 等值联接+无索引 |

### EXPLAIN 中的联接

```
type=eq_ref：唯一索引联接（最快）
type=ref：非唯一索引联接
type=ALL：全表扫描联接（最差）
Extra: Using join buffer（Block NL/Hash Join）
```

### P9 追问链

- **Q：Hash Join 和 Nested Loop 区别？** → Hash=小表建哈希表（O(n+m)）；NL=嵌套循环（O(n×m)）
- **Q：什么时候用 Hash Join？** → 等值联接+无索引（8.0.18+自动选择）
- **Q：Block Nested Loop 是什么？** → 无索引时内层批量读入 buffer（比朴素 NL 快）

---

## 参考来源（权威核对）

```
官方文档：
  MySQL Reference Manual 8.0：
  → EXPLAIN（dev.mysql.com/doc/refman/8.0/en/explain.html）
  → 优化器（optimizer-optimization.html）
  → EXPLAIN FORMAT=TREE（8.0 新特性）
  → EXPLAIN ANALYZE（8.0.18+）
  → 慢查询日志（slow-query-log.html）
  → 联接算法（explain-join-algorithms.html）

经典书籍：
  《高性能 MySQL》第三版 → EXPLAIN/优化/慢查询
  《MySQL 45 讲》（极客时间/林晓斌）→ 优化器/EXPLAIN

权威博客：
  Percona 博客 → 优化器/性能调优实践

权威来源核对：
  EXPLAIN type 值（官方 explain-output 文档）
  联接算法（官方 explain-join-algorithms：Nested Loop/Hash Join/Block NL）
  EXPLAIN ANALYZE（官方 8.0.18+ 文档）
  slow_query_log 默认 OFF/long_query_time 默认 10 秒（官方确认）
```

## 相关文件

- Docker: mysql-p9
- 实测: EXPLAIN FORMAT=TREE（cost=0.35）/slow_query_log=ON
- 参考: MySQL 官方 EXPLAIN 文档/优化器文档
