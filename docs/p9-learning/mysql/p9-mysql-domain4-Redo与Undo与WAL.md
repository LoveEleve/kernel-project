# p9-mysql-domain4 · Redo/Undo/WAL 与崩溃恢复（P9 深度实验）

> MySQL 轨道域 4/9。对照 vol-mysql 02+03+19 章。
> 覆盖：redo log/WAL/LSN/Group Commit/Undo/ARIES 崩溃恢复

## 实验环境

```
Docker: mysql-p9
```

---

## 实验 1：Redo Log 状态（SHOW ENGINE INNODB STATUS 实测）

```
Log capacity                 104857600   ← redo log 容量 100MB
Log capacity used            104857600   ← 已用（写满）
Log sequence number          29674540    ← LSN（当前日志位置）
Log buffer assigned up to    29674540    ← 日志缓冲已分配
Log buffer completed up to   29674540    ← 日志缓冲已完成
Log written up to            29674540    ← 已写入磁盘
Log flushed up to            29674540    ← 已刷盘
```

### Redo Log 机制（WAL）

```
WAL（Write-Ahead Logging）：
  先写 redo log（顺序写），再写数据页（随机写）
  → 崩溃恢复：重放 redo log（恢复已提交修改）
  → 好处：顺序写快（vs 随机写数据页）

Redo Log 写入流程：
  ① 事务修改 → 生成 redo log 记录 → 写入 Log Buffer
  ② Log Buffer → 磁盘（刷盘，innodb_flush_log_at_trx_commit 控制）
  ③ Checkpoint：脏页刷盘后，redo log 可覆盖（LSN 记录位置）

Redo Log 特点：
  循环写（固定大小，100MB 默认）
  物理日志（记录"页 X 偏移 Y 改成 Z"）
  只追加（顺序写，快）
```

### P9 追问链

- **Q：WAL 是什么？** → 先写 redo log 再写数据页（顺序写快，崩溃可恢复）
- **Q：redo log 循环写怎么工作？** → 固定大小，写满 checkpoint 后覆盖（LSN 记录位置）
- **Q：redo log 记录什么？** → 物理日志（页号+偏移+新值），不是 SQL

---

## 实验 2：LSN（日志序列号，实测）

```
Log sequence number          29674540    ← 当前 LSN（redo log 写到哪）
Last checkpoint at           29674540    ← 检查点位置（脏页刷到这里）
```

### LSN 机制

```
LSN（Log Sequence Number）：单调递增的日志位置
  → Log sequence number：当前写入位置
  → Last checkpoint：脏页刷盘位置（之前的 redo 可覆盖）
  → 差值 = 未刷盘的脏页量（越大越危险）

checkpoint：
  脏页刷盘 → 更新 Last checkpoint → redo log 空间可复用
  → 频繁 checkpoint：刷盘开销大
  → 太少 checkpoint：redo log 写满阻塞（必须刷脏页）
```

### P9 追问链

- **Q：LSN 是什么？** → 单调递增的日志位置（当前写入/检查点）
- **Q：checkpoint 干什么？** → 脏页刷盘后更新 LSN，redo log 空间可复用
- **Q：LSN 差值说明？** → Log seq - Last checkpoint = 未刷盘脏页量（越大越危险）

---

## 实验 3：Group Commit（组提交，实测）

```
binlog_group_commit_sync_delay = 0        ← 组提交延迟（微秒）
binlog_group_commit_sync_no_delay_count = 0
sync_binlog = 1                           ← 每次提交刷 binlog（最安全）
```

### Group Commit 机制

```
问题：每个事务提交都 fsync redo+binlog（慢）
解法：Group Commit（组提交）
  多个事务的提交合并为一次 fsync
  → 提交线程排队 → 攒一批 → 一次刷盘
  → 大幅提升写入 TPS

sync_binlog：
  0：由 OS 决定刷盘时机（最快，可能丢）
  1：每次提交刷 binlog（最安全，默认）
  N：每 N 次提交刷一次
```

### P9 追问链

- **Q：Group Commit 干什么？** → 多事务提交合并一次 fsync（提升写入 TPS）
- **Q：sync_binlog=1 什么含义？** → 每次提交刷 binlog（最安全）
- **Q：两阶段提交？** → redo prepare → binlog 写入 → redo commit（保证 redo 和 binlog 一致）

---

## 实验 4：Undo Log

```
Undo Log：旧版本数据（回滚/MVCC）
  → 修改时：旧值写入 undo → DB_ROLL_PTR 指向 undo
  → 回滚：沿 undo 链反向操作
  → MVCC：快照读沿 undo 链找旧版本
  → purge：无引用的 undo 清理（后台线程）

Undo 表空间：独立文件（innodb_undo_tablespaces）
  → 8.0 默认开启（undo 不与数据混合）
```

### P9 追问链

- **Q：undo log 干什么？** → 回滚（反向操作）+ MVCC（旧版本链）
- **Q：undo 什么时候清理？** → purge 线程（无事务快照引用时）
- **Q：redo vs undo？** → redo=物理（崩溃恢复）；undo=逻辑（回滚/MVCC）

---

## 补充：ARIES 崩溃恢复（P9 了解级）

```
ARIES（Algorithms for Recovery and Isolation Exploiting Semantics）：
  MySQL 崩溃恢复算法

三阶段：
  ① Analysis：分析 redo log（找未刷盘脏页/活动事务）
  ② Redo：重放 redo log（恢复到崩溃前状态）
  ③ Undo：回滚未提交事务（沿 undo 链）

恢复流程：
  崩溃 → 启动 → Analysis（扫描 redo）→ Redo（重放）→ Undo（回滚）
  → 数据回到一致状态（已提交的在，未提交的回滚）
```

### P9 追问链

- **Q：ARIES 是什么？** → MySQL 崩溃恢复算法（Analysis→Redo→Undo）
- **Q：崩溃恢复流程？** → Analysis（找脏页/事务）→Redo（重放）→Undo（回滚未提交）

---

## 补充：redo vs binlog（两阶段提交）

```
redo log：InnoDB 物理日志（崩溃恢复，循环写）
binlog：Server 逻辑日志（复制，追加写）

两阶段提交（保证一致性）：
  ① redo prepare（InnoDB 写 redo，锁定资源）
  ② binlog 写入（Server 写 binlog）
  ③ redo commit（InnoDB 提交）
  → 崩溃时：redo prepare 但 binlog 未写→回滚；binlog 已写但 redo commit 未→回放 binlog
```

---

## 补充 #1：Mini-Transaction（MTR，redo 最小单元）

### 机制

```
Mini-Transaction（MTR）：redo log 的最小原子操作单元
  → 一个物理操作（如"更新一个字节"）= 一个 MTR
  → MTR 内的操作是原子的（要么全写 redo，要么不写）
  → 一个高层操作（如 UPDATE 一行）= 多个 MTR 组成

例：UPDATE t SET a=1 WHERE id=1
  → MTR①：找到 B+Tree 页（物理读）
  → MTR②：修改页中 a 字段（物理写 redo）
  → MTR③：更新页目录/LSN（物理写 redo）
  → 每个 MTR 独立写 redo log（原子性保证）

为什么需要 MTR：
  redo log 恢复时按 MTR 重放（保证物理操作原子）
  → 崩溃在 MTR 中间→只重放完整 MTR（不重放半截）
```

### P9 追问链

- **Q：MTR 是什么？** → redo log 最小原子单元（一个物理操作）
- **Q：MTR 和事务关系？** → 一个事务=多个 MTR（每物理操作一个 MTR）
- **Q：崩溃恢复怎么保证原子？** → 按 MTR 重放（半截 MTR 不重放）

---

## 补充 #2：checkpoint 触发机制

### checkpoint 策略

```
checkpoint 触发条件：
  ① redo log 剩余空间 < 阈值（innodb_log_checkpoint 占比）
  ② 定时（innodb_log_checkpoint_interval）
  ③ 停机/切换日志文件

checkpoint 做什么：
  ① 刷脏页到磁盘（把最新修改落盘）
  ② 更新 Last checkpoint LSN（记录已刷盘位置）
  ③ 释放 redo log 空间（之前的可覆盖）

checkpoint 阻塞问题：
  redo log 写满→强制 checkpoint→刷大量脏页→STW 阻塞
  → 生产：监控 redo log 剩余空间（SHOW ENGINE INNODB STATUS）
  → 优化：增大 redo log（innodb_log_file_size）/ 调整刷脏策略
```

### P9 追问链

- **Q：checkpoint 什么时候触发？** → redo log 剩余不足/定时/停机
- **Q：checkpoint 做什么？** → 刷脏页+更新 LSN+释放 redo 空间
- **Q：redo log 写满会怎样？** → 强制 checkpoint（刷脏页）→ 可能 STW 阻塞
- **Q：怎么避免？** → 监控 redo 剩余空间；增大 innodb_log_file_size

---

## 补充 #3：WAL 通用视角（跨系统对比）

### WAL 是通用模式

```
WAL（Write-Ahead Logging）：先写日志再写数据（通用模式）
  不是 MySQL 专属，是存储/数据库/分布式系统的通用设计

各系统 WAL 实现：
  MySQL InnoDB：Redo Log（物理日志，循环写）
  PostgreSQL：WAL（类似，支持流复制）
  LevelDB/RocksDB：Write-Ahead Log + MemTable + SSTable
  Raft/Paxos：Log（分布式共识日志）
```

### WAL 对比

| 系统 | WAL 名称 | 特点 |
|---|---|---|
| **MySQL InnoDB** | Redo Log | 物理日志/循环写/两阶段提交 |
| **PostgreSQL** | WAL | 类似 InnoDB；支持流复制 |
| **LevelDB/RocksDB** | WAL + MemTable | WAL 先写→MemTable→flush SSTable（LSM） |
| **Raft/Paxos** | Log | 分布式共识日志（多数派确认） |

### WAL 放大问题

```
WAL 放大（Write Amplification）：
  写一次数据 → 写 WAL + 写数据页（2 倍写入）
  LSM 额外放大：compaction 重写（多次写入）

优化：
  批量提交（Group Commit）
  合并小写（Group Commit/事务合并）
  SSD（随机写性能好，WAL 顺序写收益降低）
```

### P9 追问链

- **Q：WAL 是 MySQL 专属吗？** → 不是（PostgreSQL/LevelDB/Raft 都用 WAL 模式）
- **Q：各系统 WAL 区别？** → MySQL=物理循环写；PG=类似+流复制；LevelDB=WAL+MemTable；Raft=共识日志
- **Q：WAL 放大是什么？** → 写一次数据写 WAL+数据页（2 倍写入）；LSM 额外 compaction 放大

---

## 补充考点速查（#1-#3）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Mini-Transaction** | redo 最小原子单元；一个事务=多个 MTR；按 MTR 重放恢复 | #1（叙事） |
| **checkpoint 触发** | redo log 不足/定时/停机；刷脏页+更新 LSN+释放空间 | #2（叙事） |
| **WAL 通用视角** | MySQL/PG/LevelDB/Raft 都用 WAL；物理 vs 逻辑 vs 共识日志 | #3（对比表） |
| WAL 放大 | 写一次数据写 WAL+数据页（2 倍）；LSM 额外 compaction | #3（叙事） |

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **WAL** | 先写 redo log 再写数据页（顺序写快，崩溃可恢复） | 实验 1（LOG 状态实测） |
| **Redo Log** | 物理日志（页+偏移+新值）；循环写（100MB 默认） | 实测（Log capacity=100MB） |
| **LSN** | 单调递增日志位置；Log seq - checkpoint = 未刷盘脏页 | 实测（LSN=29674540） |
| **Group Commit** | 多事务合并一次 fsync（提升 TPS）；sync_binlog=1 | 实测（sync_binlog=1） |
| **Undo Log** | 回滚（反向操作）+MVCC（旧版本链）；purge 清理 | 叙事 |
| **redo vs binlog** | redo=InnoDB 物理（恢复）；binlog=Server 逻辑（复制） | 补充（两阶段提交） |
| **ARIES** | Analysis→Redo→Undo（崩溃恢复三阶段） | 补充（叙事） |
| 两阶段提交 | redo prepare→binlog 写入→redo commit（保证一致） | 补充 |

---

## 补充 #1：Binlog 格式（ROW/STATEMENT/MIXED，P9 经典题）

### 三种格式

| 格式 | 内容 | 优点 | 缺点 |
|---|---|---|---|
| **STATEMENT** | 记录 SQL 语句 | 日志小 | 非确定函数不一致（NOW()/RAND()） |
| **ROW** | 记录行变更（前后值） | 精确（主从一致） | 日志大（大批量 DML 大） |
| **MIXED** | 默认 STATEMENT，不安全时转 ROW | 折中 | 复杂 |

### MySQL 8.0 默认

```
binlog_format = ROW（8.0 默认）
  → 复制精确（主从数据一致）
  → CDC/数据订阅方便（行变更清晰）

什么时候用 STATEMENT：
  日志空间紧张（如大量 INSERT...SELECT）
  但非确定函数（NOW/RAND/UUID）会导致主从不一致
```

### P9 追问链

- **Q：binlog 三种格式？** → STATEMENT（SQL）/ROW（行变更）/MIXED（混合）
- **Q：MySQL 8.0 默认？** → ROW（精确，主从一致）
- **Q：ROW 比 STATEMENT 大多少？** → 大批量 DML 可能大很多（每行记录前后值）；一般场景可接受

---

## 补充 #2：Redo Log 物理格式

```
Redo Log 记录格式：
  日志类型（1 字节）：MLOG_1BYTE/2BYTE/4BYTE/8BYTE/RECORD 等
  页号（4 字节）：space_id + page_no
  偏移（2 字节）：页内偏移
  数据：具体修改内容

例：UPDATE 一行的某个字段
  → 记录：页号=X，偏移=Y，新值=Z（物理修改）

为什么是物理日志：
  不记录 SQL（"UPDATE t SET a=1"），只记录"页 X 偏移 Y 改成 1"
  → 恢复时直接重放（不需要理解 SQL 语义）
  → 比逻辑日志快（直接写页）
```

### P9 追问链

- **Q：redo log 记录什么？** → 物理格式：页号+偏移+新值（不是 SQL）
- **Q：为什么物理日志比逻辑日志快？** → 直接重放修改（不用理解 SQL 语义）

---

## 补充 #3：崩溃恢复具体场景（prepare/commit）

```
两阶段提交的崩溃场景：

场景①：redo prepare 后，binlog 写入前崩溃
  → 恢复：回滚（redo prepare 但 binlog 未写→事务不完整→回滚）

场景②：binlog 写入后，redo commit 前崩溃
  → 恢复：回放 binlog（binlog 已写→事务应提交→用 binlog 恢复）

场景③：redo commit 后崩溃
  → 恢复：无需处理（事务已完整提交）

关键：binlog 是"分界线"
  binlog 写入前崩溃→回滚
  binlog 写入后崩溃→提交（回放 binlog）
```

### P9 追问链

- **Q：prepare 后崩溃怎么办？** → 回滚（binlog 未写→事务不完整）
- **Q：binlog 写入后崩溃？** → 提交（回放 binlog，事务完整）
- **Q：为什么 binlog 是分界线？** → binlog 写入=事务"决定提交"（之前回滚，之后提交）

## 相关文件

- Docker: mysql-p9
- 实测: SHOW ENGINE INNODB STATUS（LOG/LSN）/sync_binlog=1

## 参考来源（权威核对）

```
官方文档：
  MySQL Reference Manual 8.0：
  → Redo Log（dev.mysql.com/doc/refman/8.0/en/innodb-redo-binlog.html）
  → 崩溃恢复（innodb-recovery.html）
  → 两阶段提交（binary-logging.html）
  → Binlog 格式（binary-log-types.html）

经典论文：
  ARIES（Mohan et al., 1992）：
  "ARIES: A Transaction Recovery Method Supporting Fine-Granularity Locking
   and Partial Rollbacks Using Write-Ahead Logging"
  → ACM Transactions on Database Systems (TODS), Vol. 17, No. 1, March 1992
  → 崩溃恢复三阶段（Analysis/Redo/Undo）的奠基论文
  → MySQL InnoDB 崩溃恢复基于 ARIES 算法

经典书籍：
  《MySQL 技术内幕：InnoDB 存储引擎》（姜承尧）→ Redo/Undo/恢复
  《高性能 MySQL》第三版 → 备份恢复/PITR

权威来源核对：
  Redo Log 循环写/100MB 默认（官方 innodb-redo-binlog 文档）
  sync_binlog=1 默认（官方 binary-logging 文档）
  Binlog ROW 默认（8.0，官方 binary-log-types）
  两阶段提交流程（官方 binary-logging 文档）
```
