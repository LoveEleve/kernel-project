# p9-mysql-domain9 · 存储引擎对比与分布式（P9 深度实验）

> MySQL 轨道域 9/9（收官）。对照 vol-mysql 17+18+20 章。
> 覆盖：LSM vs B+Tree/CDC/HTAP/CAP/分库分表/选型

---

## 一、LSM vs B+Tree vs COW（存储引擎对比）

### 三种存储结构

| | B+Tree（InnoDB） | LSM（RocksDB） | COW（Btrfs/ZFS） |
|---|---|---|---|
| 写路径 | 原地更新（找页→改） | 追加（写 memtable→flush SSTable） | 写新块+原子更新指针 |
| 读路径 | B+Tree 查找（3-4 层） | 查多层（memtable→L0→L1...） | COW 快照 |
| 写放大 | 低（原地改） | 高（compaction 重写） | 中（写新块） |
| 读放大 | 低（树查找） | 高（多层查找） | 低 |
| 空间放大 | 低 | 中（SSTable 冗余） | 高（COW 快照） |
| 适用 | 读多（OLTP） | 写多（日志/时序） | 快照/一致性 |

### P9 追问链

- **Q：LSM 和 B+Tree 区别？** → LSM=追加写（写快，compaction）；B+Tree=原地改（读快）
- **Q：LSM 写放大怎么产生？** → compaction 重写 SSTable（写放大）
- **Q：什么时候用 LSM？** → 写多（日志/时序/KV 存储如 RocksDB）

---

## 二、CDC 与 HTAP（P9 趋势）

### CDC（Change Data Capture）

```
CDC：捕获数据变更（INSERT/UPDATE/DELETE）
  → 读 binlog（ROW 格式）→ 解析变更 → 同步到下游
  → 工具：Debezium/Canal/MaxWell
  → 用途：数据同步/缓存更新/搜索索引/数据仓库

流程：MySQL binlog → CDC 工具解析 → Kafka → 下游（ES/数仓/缓存）
```

### HTAP（Hybrid Transactional/Analytical Processing）

```
HTAP：混合事务+分析（一个库同时支持 OLTP+OLAP）
  → 传统：OLTP（MySQL）+ ETL + OLAP（数仓）分离
  → HTAP：实时分析（TiDB/OceanBase/Doris）

实现：
  列存+行存混合（TiDB 列存/TiFlash）
  实时同步（CDC/复制到分析引擎）
```

### P9 追问链

- **Q：CDC 用在哪？** → 数据同步/缓存更新/搜索索引/数仓（binlog→Kafka→下游）
- **Q：HTAP 是什么？** → 混合事务+分析（一个库同时支持 OLTP+OLAP）
- **Q：CDC 工具？** → Debezium/Canal/MaxWell（读 binlog 解析变更）

---

## 三、CAP 定理（P9 必问）

```
CAP 三者最多同时满足两个：
  C（Consistency）：一致性（所有节点看到相同数据）
  A（Availability）：可用性（每个请求都有响应）
  P（Partition tolerance）：分区容忍（网络分区时继续工作）

分布式系统必选 P（网络分区必然发生）：
  CP：牺牲可用性（如 ZooKeeper/etcd：分区时拒绝写）
  AP：牺牲一致性（如 Cassandra/DynamoDB：分区时允许不一致）
  CA：单机/传统数据库（无分区时满足）
```

### P9 追问链

- **Q：CAP 什么关系？** → 最多满足两个；分布式必选 P（分区必然）
- **Q：MySQL 是 CA？** → 单机 CA；主从复制时是 CP（同步复制保证一致，牺牲可用）
- **Q：CP vs AP？** → CP=分区时拒绝（ZK/etcd）；AP=分区时继续但可能不一致（Cassandra）

---

## 四、分库分表（P9 高频）

### 垂直拆分 vs 水平拆分

```
垂直拆分（按列）：
  大表拆成多表（如用户表：基础信息+扩展信息）
  → 减少行宽（热点列单独表）
  → 跨表 JOIN

水平拆分（按行）：
  一个表拆成多个（如 user_0 ~ user_31）
  → 分片键（sharding key）决定分片
  → 解决单表数据量过大（如 1 亿行）
```

### 分库分表问题

```
分布式 ID：雪花算法/号段模式/UUID（有序性）
跨分片查询：中间件聚合（如 ShardingSphere）
分布式事务：XA/TCC/SAGA（跨库一致性）
扩容：翻倍扩容（2→4 分片，数据迁移）
```

### P9 追问链

- **Q：垂直 vs 水平拆分？** → 垂直=按列（大表拆多表）；水平=按行（多表分片）
- **Q：分库分表带来什么问题？** → 分布式 ID/跨分片查询/分布式事务/扩容
- **Q：什么时候分库分表？** → 单表 >1 亿行/单库写入瓶颈（先考虑归档/归区）

---

## 五、数据库选型（P9 决策框架）

### 选型决策框架

```
选型不是找"最快"，是把业务放进决策框架：
  ① 业务语义：事务要求（强一致/最终一致）？
  ② 数据规模：单机够吗？（分库分表/分布式）
  ③ 读写比：读多（加缓存/读写分离）？写多（LSM/分布式）？
  ④ 故障模型：能丢多少数据？（CP vs AP）
  ⑤ 团队能力：运维复杂度（分布式 DB 运维重）
```

### 常见选型

| 场景 | 选型 | 理由 |
|---|---|---|
| 通用 OLTP | MySQL | 生态成熟/团队熟悉 |
| 超大规模 OLTP | TiDB/OceanBase | 分布式/水平扩展 |
| 写多（日志/时序） | RocksDB/InfluxDB | LSM 写优 |
| 读多+缓存 | MySQL+Redis | 缓存扛读 |
| HTAP | TiDB/TiFlash/Doris | 实时分析 |
| 简单 KV | Redis/RocksDB | O(1) |

### P9 追问链

- **Q：怎么选数据库？** → 业务语义/数据规模/读写比/故障模型/团队能力（决策框架）
- **Q：什么时候上分布式 DB？** → 单机到瓶颈（分库分表也扛不住/需要跨地域）
- **Q：MySQL 不够怎么办？** → 先优化（索引/缓存/读写分离）→ 分库分表 → 分布式 DB

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **LSM vs B+Tree** | LSM=追加写（写快，compaction）；B+Tree=原地改（读快） | 一（对比表） |
| **CDC** | 捕获 binlog 变更（Debezium/Canal）；同步到下游 | 二（叙事） |
| **HTAP** | 混合事务+分析（TiDB/Doris 实时分析） | 二（叙事） |
| **CAP** | 最多满足两个；分布式必选 P；CP=分区拒绝/AP=分区继续 | 三（叙事） |
| **分库分表** | 垂直=按列/水平=按行；问题=分布式ID/跨分片查询/事务 | 四（叙事） |
| **选型决策** | 业务语义/规模/读写比/故障模型/团队能力（框架） | 五（叙事） |

## 参考来源

```
官方/经典：
  《高性能 MySQL》第三版 → 选型/调优
  《分布式数据库原理与架构》→ 分库分表/CAP
  TiDB/CockroachDB 官方文档 → HTAP/分布式事务
  Percona 博客 → MySQL 生产实践
```

## 相关文件

- 概念级（对比表+选型决策框架，无独立实验）

## 补充参考来源

```
经典书籍：
  《高性能 MySQL》第三版 → 选型
  《分布式数据库原理与架构》→ CAP/分库分表
  《数据密集型应用系统设计》（DDIA）→ 分布式/复制/CAP

权威来源：
  CAP 定理原始论文（Gilbert & Lynch, 2002）
  TiDB/OceanBase 官方文档 → HTAP/分布式
```
