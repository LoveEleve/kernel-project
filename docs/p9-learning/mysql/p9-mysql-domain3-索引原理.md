# p9-mysql-domain3 · 索引原理（P9 深度实验）

> MySQL 轨道域 3/9。对照 vol-mysql 04+05+06 章。
> 覆盖：B+Tree/数据页/聚簇vs二级/回表/索引维护/EXPLAIN/索引失效

## 实验环境

```
Docker: mysql-p9
测试表: p9.t_idx（10000 行，主键+idx_age+idx_name_age）
```

---

## 实验 1：B+Tree 与数据页

### InnoDB 页结构（16KB）

```
数据页（16KB）：
  页头（Header）：页号/类型/前后页指针
  页目录（Page Directory）：稀疏索引（快速定位行）
  记录区（Records）：行数据（有序存储）
  页尾（Trailer）：校验和

B+Tree 结构：
  非叶子节点：索引键+子页指针（不存数据）
  叶子节点：索引键+数据指针（聚簇索引存完整行）
  → 高度 3-4 层（千万级数据 3 层够）
  → 每次查找 = 3-4 次页读（BP 命中则内存）
```

### P9 追问链

- **Q：InnoDB 页多大？** → 16KB（innodb_page_size）
- **Q：B+Tree 高度？** → 3-4 层（千万级数据）；每层一次页读
- **Q：页目录干什么？** → 稀疏索引（快速定位页内行，不用遍历所有记录）

---

## 实验 2：聚簇索引 vs 二级索引（回表）

```
聚簇索引（主键索引）：
  叶子节点 = 完整行数据（数据按主键顺序存储）
  → 一个表一个聚簇索引

二级索引（非主键索引）：
  叶子节点 = 索引键 + 主键值（不是完整行）
  → 查询非索引列：二级索引→拿主键→回表查聚簇索引

回表：
  SELECT * FROM t WHERE age=25
  → 走 idx_age（二级索引）拿主键 id
  → 再走聚簇索引（id）拿完整行
  → 两次 B+Tree 查找（回表）

覆盖索引（避免回表）：
  SELECT age FROM t WHERE age=25
  → idx_age 已含 age（不用回表）
  → Extra: Using index（实测）
```

### 实测证据

```
覆盖索引：SELECT age WHERE age=25
  type=ref, key=idx_age, Extra=Using index  ← 覆盖索引，无回表

SELECT * WHERE age=25
  type=ref, key=idx_age, Extra=NULL  ← 需回表（SELECT * 含非索引列）
```

### P9 追问链

- **Q：聚簇和二级索引区别？** → 聚簇叶子=完整行；二级叶子=索引键+主键（需回表）
- **Q：什么是回表？** → 二级索引拿主键→再查聚簇索引（两次 B+Tree）
- **Q：怎么避免回表？** → 覆盖索引（SELECT 列都在索引里；Extra: Using index）

---

## 实验 3：EXPLAIN（索引使用，实测）

### 走索引 vs 全表扫描

```
走索引（age=25）：
  type=ref, key=idx_age, rows=1

全表扫描（name LIKE '%5%'）：
  type=ALL, key=NULL, Extra=Using where
```

### EXPLAIN 关键字段

| 字段 | 含义 | 关注 |
|---|---|---|
| **type** | 访问类型 | ref/range/index/ALL（ALL=全表扫描） |
| **key** | 实际使用的索引 | NULL=没走索引 |
| **rows** | 预估扫描行数 | 越大越差 |
| **Extra** | 额外信息 | Using index=覆盖索引；Using filesort=排序 |

### P9 追问链

- **Q：EXPLAIN 的 type？** → const/ref/range/index/ALL（ALL=全表扫描，差）
- **Q：Extra: Using index？** → 覆盖索引（不用回表）
- **Q：rows 怎么看？** → 预估扫描行数（越小越好）

---

## 实验 4：索引失效场景（实测）

| 场景 | EXPLAIN | 原因 |
|---|---|---|
| `name LIKE '%user'`（前缀通配） | type=ALL, key=NULL | B+Tree 按前缀排序，%开头无法定位 |
| `UPPER(name)='USER5'`（函数包裹） | type=ALL, key=NULL | 索引存原值，函数后值变了 |
| `age+1=26`（列参与计算） | type=ALL, key=NULL | 索引存 age，计算后值变了 |
| 隐式类型转换 | type=ALL | 字符串列用数字查（转换后值变了） |

### 修复：函数/计算移到右侧

```sql
-- 失效：WHERE UPPER(name)='USER5'
-- 修复：WHERE name='user5'（应用层转大写）或加函数索引
-- 失效：WHERE age+1=26
-- 修复：WHERE age=25（常量移到右侧）
```

### P9 追问链

- **Q：哪些场景索引失效？** → 前缀通配/函数包裹/列参与计算/隐式类型转换
- **Q：为什么失效？** → 索引存原值，函数/计算后值变了（B+Tree 无法定位）
- **Q：怎么修复？** → 函数/计算移到右侧（常量侧）或加函数索引

---

## 补充：索引维护（页分裂）

```
插入：
  页满 → 页分裂（新页+复制部分记录）
  → 随机主键（UUID）→ 频繁页分裂（性能差）
  → 自增主键 → 顺序插入（页尾追加，无分裂）

删除：
  标记删除（不是物理删除）
  → 页内空洞 → purge 线程清理（无事务引用时）

优化：
  自增主键（顺序写）
  批量插入（减少分裂）
  定期 OPTIMIZE TABLE（整理碎片）
```

### P9 追问链

- **Q：为什么用自增主键？** → 顺序插入（页尾追加，无页分裂）
- **Q：UUID 做主键有什么问题？** → 随机插入（频繁页分裂，性能差）
- **Q：删除后空间会回收吗？** → 标记删除（purge 线程清理；页内空洞需 OPTIMIZE）

---

## 补充：FULLTEXT/统计信息/Online DDL（了解级）

```
FULLTEXT：全文索引（倒排索引；MATCH...AGAINST）
统计信息：优化器用（innodb_stats_persistent 持久化）
Online DDL：ALGORITHM=INPLACE（不锁表）/INSTANT（8.0 即时加列）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **B+Tree/数据页** | 16KB 页/3-4 层高度/页目录稀疏索引 | 叙事 |
| **聚簇 vs 二级** | 聚簇叶子=完整行；二级叶子=索引键+主键（回表） | 叙事 |
| **回表** | 二级索引拿主键→查聚簇索引（两次 B+Tree） | 实验 2 |
| **覆盖索引** | SELECT 列在索引里（Using index，无回表） | 实测（Using index） |
| **EXPLAIN** | type=ALL 全表扫描；key=NULL 没走索引 | 实测（type=ref/ALL） |
| **索引失效** | 前缀通配/函数包裹/列计算/隐式类型转换 | 实测（4 场景 type=ALL） |
| 索引维护 | 自增主键顺序写（无分裂）；UUID 频繁分裂 | 补充（叙事） |
| 修复失效 | 函数/计算移到右侧（常量侧）或加函数索引 | 补充（叙事） |

---

## 补充 #1：索引下推（ICP，MySQL 5.6+）

```
ICP（Index Condition Pushdown）：
  优化器把 WHERE 条件下推到存储引擎（索引层过滤）
  → 减少回表次数（先在索引层过滤，再回表）

例：SELECT * FROM t WHERE name LIKE 'user5%' AND age=5
  无 ICP：走索引→回表→Server 层过滤 age=5
  有 ICP：走索引→存储引擎过滤 age=5→回表（减少回表）
  → Extra: Using index condition

EXPLAIN 实测：
  type=ref, key=idx_age, Extra=Using where
  （MySQL 8.0 可能不显示 ICP 标记，但机制生效）
```

### P9 追问链

- **Q：ICP 干什么？** → WHERE 下推到存储引擎（索引层过滤，减少回表）
- **Q：EXPLAIN 怎么看 ICP？** → Extra: Using index condition（MySQL 5.6+）

---

## 补充 #2：索引基数/区分度（索引选择基础）

```
索引基数（Cardinality）：索引列不同值的数量
  区分度 = 基数 / 总行数（0-1，越高越好）
  → 区分度高：走索引（如 id/邮箱）
  → 区分度低：优化器不走索引（如性别/状态）

SHOW INDEX 的 Cardinality 列：索引基数
  → 0 或很小 = 区分度低（统计信息未更新或列重复多）

优化器选择：
  区分度高 → 走索引（ref/range）
  区分度低 → 全表扫描（ALL）（索引不划算）
  → ANALYZE TABLE 更新统计信息
```

### P9 追问链

- **Q：什么是索引基数？** → 索引列不同值数量（Cardinality）
- **Q：区分度低怎么办？** → 优化器不走索引（全表扫描）；考虑联合索引/覆盖索引
- **Q：统计信息不准怎么办？** → ANALYZE TABLE 更新

---

## 补充考点速查（#1/#2）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **索引下推 ICP** | WHERE 下推存储引擎（索引层过滤，减少回表）；Using index condition | #1（实测+叙事） |
| **索引基数/区分度** | 基数/行数（越高越好）；低则优化器不走索引 | #2（叙事） |

---

## 补充 #3：B+Tree vs B-Tree vs Hash（P9 经典题）

| | B-Tree | B+Tree | Hash |
|---|---|---|---|
| 数据位置 | 所有节点 | **只在叶子节点** | 桶 |
| 叶子链表 | 无 | **有（双向链表）** | 无 |
| 范围查询 | 中序遍历（慢） | **叶子链表（快）** | 不支持 |
| 排序 | 中序遍历 | **叶子有序（快）** | 不支持 |
| 精确查找 | O(log n) | O(log n) | O(1) |

### MySQL 为什么用 B+Tree

```
① 数据只在叶子 → 非叶子节点存更多键（树更矮，IO 更少）
② 叶子链表 → 范围查询/ORDER BY 快（叶子顺序连接）
③ 有序 → 排序/分组/范围扫描高效
④ Hash 不支持范围查询（MySQL 需要 ORDER BY/范围）

对比：
  Hash：精确查找 O(1)，但不支持范围/排序
  B-Tree：数据分散各节点（树更高）
  B+Tree：数据只在叶子（树更矮）+叶子链表（范围快）
```

### P9 追问链

- **Q：为什么用 B+Tree 不用 B-Tree？** → 数据只在叶子（树更矮）+叶子链表（范围查询快）
- **Q：为什么不用 Hash？** → Hash 不支持范围查询/ORDER BY（MySQL 需要）
- **Q：B+Tree 高度？** → 3-4 层（千万级数据）；每层一次页读（16KB）

---

## 补充 #4：B+Tree 叶子链表（范围查询原理）

```
B+Tree 叶子节点：双向链表（有序连接）
  → 范围查询：定位起始叶子→沿链表扫描→到终止叶子
  → ORDER BY：叶子已有序（不用额外排序）
  → LIMIT：顺序读叶子（不用全扫）

例：SELECT * FROM t WHERE age BETWEEN 20 AND 30 ORDER BY age
  → 定位 age=20 的叶子→沿链表读→age>30 停止
  → 不用回表排序（叶子有序）
```

### P9 追问链

- **Q：范围查询为什么快？** → 叶子链表（顺序扫描，不用回树）
- **Q：ORDER BY 为什么快？** → 叶子已有序（避免 filesort）

---

## 补充 #5：联合索引 B+Tree 排序（最左前缀原理）

```
联合索引 (name, age) 的 B+Tree 排序：
  先按 name 排序；name 相同再按 age 排序

  (user1, 20)
  (user1, 30)  ← name 相同，按 age
  (user2, 10)
  (user2, 25)

最左前缀原则：
  WHERE name='user1' → 命中（用 name 排序）
  WHERE name='user1' AND age=20 → 命中（用 name+age）
  WHERE age=20 → 不命中（跳过 name，无法用 B+Tree 定位）
  WHERE name LIKE 'user%' → 命中（前缀匹配）
  WHERE name LIKE '%ser1%' → 不命中（前缀不匹配）
```

### P9 追问链

- **Q：联合索引怎么排序？** → 先第一列排序；相同再第二列（依次类推）
- **Q：最左前缀原理？** → B+Tree 按列顺序排序，跳过前列无法定位
- **Q：(a,b,c) 索引哪些查询能用？** → a / a,b / a,b,c 能用；b / b,c / c 不能用

---

## 补充考点速查（#3-#5）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **B+Tree vs B-Tree vs Hash** | B+Tree 数据只在叶子+叶子链表（范围快）；Hash 不支持范围 | #3（对比表） |
| **叶子链表** | 双向链表有序；范围查询/ORDER BY 快 | #4（叙事） |
| **联合索引排序** | 先第一列再第二列；最左前缀原理 | #5（叙事） |

## 相关文件

- Docker: mysql-p9
- 实测: EXPLAIN（type=ref/ALL/Using index）
- 测试表: p9.t_idx（10000 行）

## 参考来源（权威核对）

```
官方文档：
  MySQL Reference Manual 8.0：
  → EXPLAIN 输出格式（dev.mysql.com/doc/refman/8.0/en/explain-output.html）
  → 索引优化（index-optimization.html）
  → 索引失效场景（column-index-optimizations.html）
  → EXPLAIN FORMAT=TREE（8.0 新特性）

经典书籍：
  《高性能 MySQL》第三版 → 索引优化/EXPLAIN
  《MySQL 技术内幕：InnoDB 存储引擎》（姜承尧）→ B+Tree/页结构

权威来源核对：
  EXPLAIN type 值（官方 explain-output 文档）
  索引失效场景（官方 index-optimization 文档）
  B+Tree vs B-Tree（InnoDB 架构文档）
```
