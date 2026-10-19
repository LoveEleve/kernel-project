# p9-mysql-domain6 · 复制与高可用（P9 深度实验）

> MySQL 轨道域 6/9。对照 vol-mysql 09+10+11+12 章。
> 覆盖：binlog/GTID/主从复制/并行复制/故障切换/备份恢复

## 实验环境

```
Docker: mysql-p9（单实例，复制部分为叙事+参数实测）
```

---

## 实验 1：Binlog 状态（实测）

```
Log_name      File_size   Encrypted
binlog.000001 2997942     No
binlog.000002 1807        No
```

### Binlog 机制

```
binlog：Server 层逻辑日志（记录 SQL/行变更）
  → 追加写（不覆盖，用于复制/PITR）
  → 三种格式：ROW/STATEMENT/MIXED（域4 已讲）
  → 配合 redo log：两阶段提交（保证一致）

binlog 事件类型：
  Query Event（SQL 语句）
  Rows Event（行变更，ROW 格式）
  XID Event（事务提交）
  Format Description Event（binlog 头）
```

### P9 追问链

- **Q：binlog 和 redo log 区别？** → binlog=Server 逻辑（复制/PITR，追加写）；redo=InnoDB 物理（恢复，循环写）
- **Q：binlog 怎么保证不丢？** → sync_binlog=1（每次提交刷盘）；两阶段提交
- **Q：PITR 是什么？** → Point-in-Time Recovery（基于 binlog 恢复到任意时间点）

---

## 实验 2：GTID（实测）

```
gtid_mode = OFF（本机未开启）
gtid_executed = (空)
```

### GTID 机制

```
GTID（Global Transaction ID）：全局事务 ID
  每个事务有唯一 ID（source_uuid:transaction_id）
  → 复制进度不依赖 binlog 文件名+位置
  → 故障切换：自动找一致点（不用配 binlog pos）

gtid_mode：
  OFF：不用 GTID
  ON：启用 GTID（主从都开）
  OFF_PERMISSIVE/ON_PERMISSIVE：过渡模式
```

### P9 追问链

- **Q：GTID 干什么？** → 全局事务 ID（复制进度不依赖 binlog 位置；故障切换自动找一致点）
- **Q：GTID vs 传统复制？** → GTID=自动定位（无需配 pos）；传统=binlog 文件名+位置（易错）
- **Q：生产建议？** → 开启 GTID（8.0 推荐；故障切换简单）

---

## 实验 3：主从复制流程（叙事）

```
① 主库：写操作产生 binlog
② Dump 线程：主库 Dump Thread 发送 binlog 给从库
③ 从库：I/O Thread 接收 binlog → 写 Relay Log
④ 从库：SQL Thread 重放 Relay Log（应用变更）
⑤ 从库：更新复制位点（binlog pos/GTID）

线程模型：
  主库：Dump Thread（每从库一个）
  从库：I/O Thread（接收）+ SQL Thread（重放）
```

### P9 追问链

- **Q：主从复制流程？** → 主库 binlog→Dump Thread→从库 I/O Thread→Relay Log→SQL Thread 重放
- **Q：主从延迟原因？** → 单线程重放（旧版）/大事务/从库性能差
- **Q：怎么监控延迟？** → Seconds_Behind_Master / SHOW REPLICA STATUS

---

## 实验 4：并行复制（实测）

```
replica_parallel_type = LOGICAL_CLOCK   ← 逻辑时钟（按提交时间并行）
replica_parallel_workers = 4            ← 4 个并行重放线程
```

### 并行复制演进

```
MySQL 5.6：基于库的并行（database 多库并行）
MySQL 5.7：基于组提交的并行（LOGICAL_CLOCK，事务按提交组并行）
MySQL 8.0：优化 LOGICAL_CLOCK（更多场景并行）

并行条件：
  事务无依赖（不同行/无外键/无锁冲突）
  → LOGICAL_CLOCK：同时提交的事务可并行重放
```

### P9 追问链

- **Q：并行复制怎么工作？** → LOGICAL_CLOCK（同时提交的事务并行重放）；4 workers
- **Q：什么时候不能并行？** → 事务有依赖（同表同锁/外键）
- **Q：主从延迟怎么优化？** → 并行复制（多线程）/提升从库性能/减少大事务

---

## 补充：故障切换（P9 高频）

```
主从切换场景：
  ① 主库宕机 → 从库提升为主
  ② 选主：哪个从库数据最完整（binlog pos/GTID）
  ③ 切换：从库 CHANGE MASTER TO（指向新主）

方案：
  手动切换：STOP SLAVE; CHANGE MASTER TO...
  自动切换：MHA/Orchestrator/Group Replication
  半自动：Orchestrator（自动选主+切换）

GTID 优势：自动找一致点（不用配 binlog pos）
```

### P9 追问链

- **Q：故障切换怎么选主？** → GTID 自动找一致点；或比 binlog pos（最完整）
- **Q：MHA/Orchestrator？** → MHA=自动选主+补数据；Orchestrator=拓扑管理+自动切换
- **Q：Group Replication？** → 多主/单主协议层一致（域6 补充）

---

## 补充：备份恢复（P9 高频）

```
备份方式：
  逻辑备份：mysqldump（SQL 语句；慢但灵活）
  物理备份：Percona XtraBackup（文件拷贝；快）
  快照：FLUSH TABLES WITH READ LOCK + 文件拷贝

mysqldump（实测可用）：
  mysqldump -u root -p dbname > backup.sql
  → 恢复：mysql -u root -p dbname < backup.sql
  → --single-transaction：一致性备份（不锁表）

生产建议：
  全量：XtraBackup（快）/mysqldump（小库）
  增量：binlog（PITR）
  定期恢复演练（验证备份可用）
```

### P9 追问链

- **Q：mysqldump 和 XtraBackup 区别？** → mysqldump=逻辑（慢但灵活）；XtraBackup=物理（快）
- **Q：--single-transaction 干什么？** → 一致性备份（不锁表，用 MVCC 快照）
- **Q：怎么保证备份可用？** → 定期恢复演练（验证备份完整）

---

## 补充：Group Replication（了解级）

```
Group Replication：MySQL 官方多主方案
  单主模式：一个主+多个从（自动选主）
  多主模式：多个主（冲突检测）
  → 基于 Paxos 协议（组内一致）
  → 故障切换：自动（组内投票）

vs 传统主从：
  传统：异步复制（主库不等从库确认）
  Group Replication：半同步/组复制（组内一致才提交）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Binlog** | Server 逻辑日志（复制/PITR）；追加写；ROW 默认 | 实测（binlog.000001/2） |
| **GTID** | 全局事务 ID（复制进度不依赖 pos；故障切换自动找一致点） | 实测（gtid_mode=OFF） |
| **主从流程** | 主 binlog→Dump→从 I/O→Relay Log→SQL 重放 | 叙事 |
| **并行复制** | LOGICAL_CLOCK（同时提交并行）；4 workers | 实测（replica_parallel_workers=4） |
| **故障切换** | GTID 自动找一致点；MHA/Orchestrator 自动切换 | 补充（叙事） |
| **备份** | mysqldump 逻辑/XtraBackup 物理；--single-transaction 一致性 | 实测（mysqldump 可用） |
| 主从延迟 | 单线程重放/大事务/从库差；并行复制优化 | 叙事 |
| Group Replication | 多主/单主；Paxos 组内一致；自动选主 | 补充（叙事） |

---

## 补充 #1：半同步复制（P9 对比题）

### 三种复制模式

| 模式 | 主库确认时机 | 数据安全 | 性能 |
|---|---|---|---|
| **异步** | 写 binlog 即返回（不等从库） | 可能丢（主宕从未收） | 最高 |
| **半同步** | 等至少 1 个从库收到 binlog | 不丢（至少 1 副本） | 中（等 1 个 ACK） |
| **组复制** | 组内多数派确认 | 不丢（组内一致） | 较低（Paxos） |

### 半同步原理

```
① 主库：事务提交 → 写 binlog → 等从库 ACK
② 从库：I/O Thread 收到 binlog → 返回 ACK
③ 主库：收到 ACK → 提交事务（返回客户端）

超时退化：
  等 ACK 超时（rpl_semi_sync_master_timeout，默认 1 秒）
  → 退化为异步复制（不等 ACK）
  → 从库追上后恢复半同步
```

### P9 追问链

- **Q：半同步和异步区别？** → 半同步等至少 1 个从库 ACK（不丢）；异步不等（可能丢）
- **Q：半同步超时怎么办？** → 退化为异步（1 秒超时）；追上后恢复
- **Q：生产建议？** → 金融用半同步/组复制；一般业务异步+监控延迟

---

## 补充 #2：复制监控（P9 生产）

```
监控指标：
  Seconds_Behind_Master：主从延迟（秒）★ 核心
  Slave_IO_Running/Slave_SQL_Running：复制线程状态
  Relay_Log_Space：Relay Log 堆积量
  Retrieved_Gtid_Set：已接收 GTID（8.0）
  Executed_Gtid_Set：已执行 GTID

监控命令：
  SHOW REPLICA STATUS\G（8.0）
  SHOW SLAVE STATUS\G（旧版）
  → Seconds_Behind_Master=NULL：复制断了
  → Slave_SQL_Running=No：SQL Thread 停了（错误）
```

### P9 追问链

- **Q：怎么监控主从延迟？** → Seconds_Behind_Master（SHOW REPLICA STATUS）
- **Q：Seconds_Behind_Master=NULL？** → 复制断了（I/O Thread 没连上）
- **Q：Slave_SQL_Running=No？** → SQL Thread 停了（重放错误，需排查）

---

## 补充 #3：大事务问题（P9 生产常见）

```
大事务（如批量 UPDATE 百万行）的问题：
  ① 主从延迟：从库重放大事务（单事务阻塞后续）
  ② 故障切换：大事务未完成→切换后数据不一致
  ③ 锁持有：长事务持锁（阻塞其他事务）
  ④ undo 堆积：大事务 undo 多（purge 压力）

优化：
  批量拆小（每次 1000-10000 行）
  避免大事务 DDL（Online DDL）
  监控：SHOW ENGINE INNODB STATUS 的 ACTIVE 事务
```

### P9 追问链

- **Q：大事务有什么问题？** → 主从延迟/切换不一致/锁持有/undo 堆积
- **Q：怎么优化？** → 批量拆小/避免大事务 DDL/监控长事务
- **Q：怎么发现大事务？** → SHOW ENGINE INNODB STATUS（ACTIVE 1 hour 的事务）

---

## 补充考点速查（#1-#3）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **半同步复制** | 等至少 1 个从库 ACK（不丢）；超时退化异步 | #1（对比表） |
| **复制监控** | Seconds_Behind_Master（延迟）/SQL_Running（线程状态） | #2（叙事） |
| **大事务** | 主从延迟/切换不一致；批量拆小/监控长事务 | #3（叙事） |

## 相关文件

- Docker: mysql-p9
- 实测: SHOW BINARY LOGS/gtid_mode/replica_parallel_workers=4/mysqldump

## 参考来源（权威核对）

```
官方文档：
  MySQL Reference Manual 8.0：
  → 复制（dev.mysql.com/doc/refman/8.0/en/replication.html）
  → GTID（gtid-replication.html）
  → 半同步复制（replication-semisync.html）
  → Group Replication（group-replication.html）
  → 备份恢复（backup-and-recovery.html）

经典书籍：
  《高性能 MySQL》第三版 → 复制/备份/高可用

权威来源核对：
  gtid_mode 默认 OFF（官方确认）
  replica_parallel_workers 默认 4（8.0 官方）
  半同步 rpl_semi_sync_master_timeout 默认 1000ms（官方）
```
