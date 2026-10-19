# p9-mysql-domain2 · 事务与并发控制（P9 深度实验）

> MySQL 轨道域 2/9。对照 vol-mysql 07+08+19 章。
> 覆盖：ACID/MVCC/行锁/普通读vs当前读/死锁/MDL

## 实验环境

```
Docker: mysql-p9（mysql:8.0）
隔离级别: REPEATABLE-READ（实测）
```

---

## 实验 1：事务隔离级别（实测）

```
@@transaction_isolation = REPEATABLE-READ（MySQL 默认）
```

### 四种隔离级别

| 级别 | 脏读 | 不可重复读 | 幻读 | 实现 |
|---|---|---|---|---|
| READ UNCOMMITTED | ✓ | ✓ | ✓ | 几乎不加锁 |
| **READ COMMITTED** | ✗ | ✓ | ✓ | 每次读创建新快照 |
| **REPEATABLE READ**（默认） | ✗ | ✗ | ✗（InnoDB 用间隙锁防幻读） | 事务开始创建快照 |
| SERIALIZABLE | ✗ | ✗ | ✗ | 读加共享锁 |

### P9 追问链

- **Q：MySQL 默认隔离级别？** → REPEATABLE-READ（InnoDB 用 MVCC+间隙锁）
- **Q：RC 和 RR 区别？** → RC=每次读新快照（不可重复读）；RR=事务级快照（可重复读）
- **Q：RR 怎么防幻读？** → InnoDB 用间隙锁（Next-Key Lock）锁范围

---

## 实验 2：MVCC 快照读（普通读不加锁）

### 机制

```
MVCC（Multi-Version Concurrency Control）：
  每行有隐藏列：DB_TRX_ID（最后修改事务 ID）/DB_ROLL_PTR（undo 指针）
  快照读（普通 SELECT）：
    读取事务快照（低水位/高水位）内的最新版本
    → 不加锁（读写不阻塞）
  当前读（SELECT FOR UPDATE/UPDATE/DELETE）：
    读最新已提交版本 + 加锁

undo log：旧版本链（回滚/MVCC 快照）
  → 修改时：旧版本写入 undo log → DB_ROLL_PTR 指向 undo
  → 快照读：沿 undo 链找事务快照内的版本
```

### P9 追问链

- **Q：MVCC 怎么实现？** → 隐藏列（DB_TRX_ID/DB_ROLL_PTR）+ undo 版本链 + 事务快照
- **Q：快照读为什么不加锁？** → 读旧版本（undo 链），不阻塞写
- **Q：undo log 什么时候清理？** → purge 线程（无快照引用时回收）

---

## 实验 3：行锁（SELECT FOR UPDATE，实测）

```
performance_schema.data_locks（实测）：
  TABLE: IX（表级意向排他锁）
  RECORD: X,REC_NOT_GAP（行级排他锁，锁记录不锁间隙）
  LOCK_DATA: 1（锁 id=1 的行）
```

### 锁类型

```
表锁：IX/IS（意向锁，不阻塞行锁）/X/S（表级排他/共享）
行锁：X（排他，FOR UPDATE/UPDATE/DELETE）/S（共享，SELECT LOCK IN SHARE MODE）
  → 行锁加在索引上（不是数据行）
  → 无索引：全表扫描→锁所有行（退化为表锁）

间隙锁（Gap Lock）：
  RR 级别：锁索引间隙（防幻读）
  → 锁 id=1 和 id=3 之间的间隙（防止插入 id=2）

Next-Key Lock：行锁+间隙锁（锁记录+前面的间隙）
```

### P9 追问链

- **Q：行锁加在哪？** → 索引上（不是数据行）；无索引退化为表锁
- **Q：间隙锁干什么？** → RR 级别防幻读（锁范围防插入）
- **Q：意向锁干什么？** → 表级标记（IX/IS），行锁前先加意向（快速判断表是否有行锁）

---

## 实验 4：普通读 vs 当前读

```
快照读（普通 SELECT）：
  MVCC 读旧版本（不加锁）
  → 读写不阻塞（并发高）

当前读（SELECT FOR UPDATE / UPDATE / DELETE）：
  读最新已提交版本 + 加锁（X 锁）
  → 读写阻塞（必须等锁）

例：
  事务A UPDATE id=1（加 X 锁）
  事务B SELECT id=1（快照读，不阻塞，读旧版本）
  事务B SELECT id=1 FOR UPDATE（当前读，阻塞等锁）
```

### P9 追问链

- **Q：普通读和当前读区别？** → 快照读=MVCC 不加锁；当前读=最新版本+加锁
- **Q：什么时候用当前读？** → 需要最新版本+防并发修改（如余额扣减）

---

## 实验 5：死锁（实测）

### 死锁日志（SHOW ENGINE INNODB STATUS）

```
*** (1) TRANSACTION: ... SELECT * FROM t_lock WHERE id=1 FOR UPDATE
  HOLDS: RECORD LOCKS ... lock_mode X ... id=2
  WAITING FOR: RECORD LOCKS ... lock_mode X ... id=1

*** (2) TRANSACTION: ... SELECT * FROM t_lock WHERE id=2 FOR UPDATE
  （持 id=1，等 id=2 → 循环等待）
```

### 死锁排查

```
① SHOW ENGINE INNODB STATUS（LATEST DETECTED DEADLOCK）
② information_schema.innodb_trx（活跃事务）
③ performance_schema.data_locks（锁信息）
④ sys.innodb_lock_waits（锁等待）
```

### P9 追问链

- **Q：死锁怎么排查？** → SHOW ENGINE INNODB STATUS 看死锁日志（持锁/等锁/回滚哪个）
- **Q：MySQL 死锁怎么处理？** → 自动回滚代价小的事务（undo 少的）
- **Q：怎么预防死锁？** → 统一加锁顺序/减少事务持有锁时间/用 RC 隔离级别（无间隙锁）

---

## 实验 6：MDL（元数据锁）

```
MDL（Metadata Lock）：DDL 期间锁表结构
  DML（SELECT/UPDATE）加 MDL 读锁
  DDL（ALTER/DROP）加 MDL 写锁
  → DDL 等所有 DML 完成；DML 等 DDL 完成
  → 长事务阻塞 DDL（MDL 等待队列→后续 DML 全阻塞）

排障：
  SELECT * FROM performance_schema.metadata_locks WHERE OBJECT_NAME='table'
  SELECT * FROM sys.schema_table_lock_waits（8.0）
```

### P9 追问链

- **Q：MDL 是什么？** → 元数据锁（DDL 期间锁表结构）
- **Q：长事务为什么阻塞 DDL？** → DDL 等 MDL 写锁；MDL 写锁等所有 MDL 读锁（长事务持读锁）
- **Q：MDL 阻塞怎么排查？** → performance_schema.metadata_locks / sys.schema_table_lock_waits

---

## 补充：ACID（P9 基础）

```
A（原子性）：undo log（回滚）
C（一致性）：约束+事务边界
I（隔离性）：锁+MVCC
D（持久性）：redo log（WAL）
```

## 补充：并发控制理论（ARIES/OCC/TSO/SSI）

```
ARIES：MySQL 用（redo+undo+checkpoint，崩溃恢复）
OCC（乐观并发控制）：冲突检测在提交时（TiDB/CockroachDB）
TSO（时间戳排序）：全局时间戳排序（Spanner）
SSI（可快照隔离）：PostgreSQL 默认
→ MySQL InnoDB = ARIES + MVCC + 锁
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **隔离级别** | MySQL 默认 RR；RC=每次读新快照；RR=事务级快照+间隙锁 | 实测（REPEATABLE-READ） |
| **MVCC** | 隐藏列（DB_TRX_ID/DB_ROLL_PTR）+undo 版本链+事务快照 | 叙事 |
| **快照读 vs 当前读** | 快照读=MVCC 不加锁；当前读=最新+X 锁 | 实验 4 |
| **行锁** | 加在索引上；无索引退化表锁；RR 有间隙锁 | 实测（data_locks X,REC_NOT_GAP） |
| **死锁** | SHOW ENGINE INNODB STATUS 排查；自动回滚代价小的 | 实测（死锁日志） |
| **MDL** | DDL 期间锁表结构；长事务阻塞 DDL | 叙事（metadata_locks） |
| ACID 实现 | A=undo/C=约束/I=锁+MVCC/D=redo | 补充 |
| 并发控制理论 | MySQL=ARIES+MVCC+锁；TiDB=OCC | 补充 |

---

## 补充 #1：间隙锁/Next-Key Lock（RR 防幻读，P9 高频）

### 三种行锁

```
Record Lock：锁单行（id=1）
Gap Lock：锁间隙（id=1 和 id=3 之间，不含边界）
Next-Key Lock：Record + Gap（锁记录+前面的间隙）

RR 防幻读：
  快照读：MVCC 读旧版本（不加锁）
  当前读（UPDATE/SELECT FOR UPDATE）：加 Next-Key Lock（锁范围）
  → 幻读：其他事务 INSERT 新行到范围内被阻塞

例：UPDATE t SET val=1 WHERE id=1
  → 加 Next-Key Lock（锁 id=1 记录+前面间隙）
  → 其他事务 INSERT id=0 被阻塞（间隙锁）
```

### P9 追问链

- **Q：RR 怎么防幻读？** → 当前读加 Next-Key Lock（Record+Gap），锁范围防插入
- **Q：Next-Key Lock 锁什么？** → 记录+前面的间隙（左开右闭）
- **Q：RC 有间隙锁吗？** → 没有（RC 只加 Record Lock，所以 RC 有幻读）

---

## 补充 #2：死锁预防（P9 中频）

### 死锁检测

```
MySQL 用等待图（Wait-for Graph）检测：
  事务等锁 → 建边（等待关系）
  → 检测环（循环等待）→ 死锁
  → 自动回滚代价小的事务（undo 少的）

参数：
  innodb_deadlock_detect=ON（默认开启检测）
  innodb_lock_wait_timeout=50（等锁超时，默认 50 秒）
```

### 预防策略

```
① 统一加锁顺序（破坏循环等待）
   所有事务按 id 升序 UPDATE（不会交叉持锁）

② 减少事务持有锁时间（缩短事务/批量拆小）

③ 用 RC 隔离级别（无间隙锁，死锁概率低）

④ SELECT FOR UPDATE 加 ORDER BY（统一顺序）

⑤ 应用层重试（死锁时自动重试）
```

### P9 追问链

- **Q：MySQL 怎么检测死锁？** → 等待图检测环；自动回滚代价小的事务
- **Q：怎么预防死锁？** → 统一加锁顺序/缩短事务/用 RC/应用层重试
- **Q：innodb_lock_wait_timeout？** → 等锁超时（默认 50 秒，超时报错）

---

## 补充 #3：XA 分布式事务（P9 中频）

```
XA：分布式事务规范（两阶段提交 2PC）
  ① prepare：所有参与者准备（写 redo+undo，锁定资源）
  ② commit/rollback：协调者通知所有参与者提交/回滚

MySQL XA：
  XA START 'xid' ... XA END 'xid' ... XA PREPARE 'xid' ... XA COMMIT 'xid'
  → 跨数据库分布式事务

问题：
  ① prepare 后协调者宕机 → 参与者悬挂（阻塞等通知）
  ② 性能差（两阶段+锁持有时间长）
  → 生产用 TCC/SAGA 等柔性事务替代
```

### P9 追问链

- **Q：XA 是什么？** → 分布式事务（两阶段提交：prepare→commit/rollback）
- **Q：XA 有什么问题？** → 协调者宕机悬挂；性能差（两阶段+长锁）
- **Q：生产用什么替代？** → TCC/SAGA（柔性事务，最终一致性）

---

## 补充 #4：乐观锁/悲观锁（应用层，P9 中频）

```
悲观锁：数据库锁（SELECT FOR UPDATE）
  → 适合写多冲突多（如库存扣减）
  → 缺点：锁持有时间长，并发低

乐观锁：版本号（version 字段）
  UPDATE t SET val=val-1, version=version+1 
  WHERE id=1 AND version=版本号
  → 适合读多写少（冲突少）
  → 冲突时：更新 0 行→应用层重试

对比：
  悲观锁：数据库层（SELECT FOR UPDATE）
  乐观锁：应用层（version 字段+CAS 更新）
```

### P9 追问链

- **Q：乐观锁怎么实现？** → version 字段（UPDATE WHERE version=旧值，影响行数=0 则重试）
- **Q：乐观 vs 悲观？** → 写多用悲观（DB 锁）；读多用乐观（version CAS）
- **Q：乐观锁冲突了怎么办？** → 更新 0 行→应用层重试（或返回冲突提示）

---

## 补充考点速查（#1-#4）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **间隙锁/Next-Key** | Record+Gap（RR 防幻读）；RC 无间隙锁 | #1（叙事） |
| **死锁预防** | 统一加锁顺序/缩短事务/RC/重试；等待图检测 | #2（叙事） |
| **XA** | 分布式事务 2PC；协调者宕机悬挂；TCC/SAGA 替代 | #3（叙事） |
| **乐观/悲观锁** | 悲观=DB 锁（写多）；乐观=version CAS（读多） | #4（叙事） |

---

## 补充 #5：Read View（MVCC 快照的具体实现，P9 高频）

### Read View 结构

```
Read View（读视图）：事务快照的具体实现
  m_ids：生成 Read View 时活跃（未提交）的事务 ID 列表
  min_trx_id：活跃事务中最小的事务 ID
  max_trx_id：下一个要分配的事务 ID
  creator_trx_id：创建该 Read View 的事务 ID

可见性判断（某版本的 DB_TRX_ID）：
  ① trx_id < min_trx_id → 可见（事务在快照前已提交）
  ② trx_id >= max_trx_id → 不可见（事务在快照后开始）
  ③ min_trx_id <= trx_id < max_trx_id：
     trx_id 在 m_ids 中 → 不可见（快照时未提交）
     trx_id 不在 m_ids 中 → 可见（快照时已提交）
```

### RC vs RR 的 Read View 创建时机

```
RC（READ COMMITTED）：
  每次 SELECT 创建新 Read View（每次读最新已提交版本）
  → 不可重复读（同一事务两次读结果可能不同）

RR（REPEATABLE READ）：
  事务第一次 SELECT 创建 Read View（事务级快照）
  → 后续 SELECT 复用同一 Read View
  → 可重复读（同一事务两次读结果相同）
```

### P9 追问链

- **Q：Read View 包含什么？** → m_ids（活跃事务）/min_trx_id/max_trx_id/creator_trx_id
- **Q：怎么判断版本可见？** → trx_id < min 或不在 m_ids → 可见；否则不可见
- **Q：RC 和 RR 的 Read View 区别？** → RC=每次读创建；RR=事务第一次读创建（复用）

---

## 补充 #6：插入意向锁（Insert Intention Lock）

```
插入意向锁：INSERT 时的特殊间隙锁
  → 事务 INSERT 时，先检查间隙锁是否冲突
  → 无冲突：插入意向锁（标记"我要插入到这个间隙"）
  → 有冲突：等待间隙锁释放

与间隙锁关系：
  间隙锁（Gap Lock）：锁间隙（阻止其他事务在该间隙操作）
  插入意向锁：INSERT 的特殊锁（在间隙锁允许时可并发插入）
  → 间隙锁之间兼容（多个事务可同时持有同一间隙的间隙锁）
  → 插入意向锁与间隙锁冲突（INSERT 要等间隙锁释放）
```

### P9 追问链

- **Q：插入意向锁是什么？** → INSERT 时的特殊间隙锁（标记插入意图）
- **Q：插入意向锁和间隙锁关系？** → 间隙锁之间兼容；插入意向锁与间隙锁冲突

---

## 补充 #7：自增锁模式（innodb_autoinc_lock_mode）

```
自增锁（AUTO-INC Lock）：保护自增 ID 分配
  模式 0：传统（所有 INSERT 都加自增锁）
  模式 1：连续（批量 INSERT 一次分配；简单 INSERT 无锁）★ 默认
  模式 2：交错（无锁，可能不连续；binlog 恢复可能乱序）

参数：innodb_autoinc_lock_mode（默认 1）
  → 模式 1 平衡：批量 INSERT 快；简单 INSERT 无锁
  → 主从复制注意：模式 2 可能主从自增 ID 不一致
```

### P9 追问链

- **Q：自增锁模式？** → 0=传统/1=连续（默认）/2=交错（无锁）
- **Q：模式 2 有什么问题？** → 自增 ID 可能不连续；binlog 恢复可能乱序
- **Q：什么时候用模式 2？** → 高并发 INSERT（可接受 ID 不连续）

---

## 补充考点速查（#5-#7）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Read View** | m_ids/min_trx_id/max_trx_id；可见性判断版本 | #5（结构） |
| RC vs RR Read View | RC=每次读创建；RR=事务第一次读创建（复用） | #5 |
| **插入意向锁** | INSERT 特殊间隙锁；与间隙锁冲突 | #6（叙事） |
| **自增锁模式** | 0=传统/1=连续（默认）/2=交错（无锁） | #7（叙事） |

## 相关文件

- Docker: mysql-p9
- 实测: REPEATABLE-READ/data_locks（X,REC_NOT_GAP）/死锁日志
- 测试表: p9.t_lock

## 参考来源（权威核对）

```
官方文档：
  MySQL Reference Manual 8.0：
  → 事务隔离级别（dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation.html）
  → InnoDB 锁（innodb-locking.html）
  → MVCC（innodb-multi-versioning.html）
  → Read View/可见性（innodb MVCC 文档）

经典论文：
  ARIES（Mohan et al., 1992）：
  "ARIES: A Transaction Recovery Method Supporting Fine-Granularity Locking
   and Partial Rollbacks Using Write-Ahead Logging"
  → 崩溃恢复/redo/undo 的奠基论文
  → ACM TODS, 1992

经典书籍：
  《MySQL 技术内幕：InnoDB 存储引擎》（姜承尧）→ MVCC/锁/事务
  《高性能 MySQL》第三版 → 隔离级别/锁优化

权威来源核对：
  REPEATABLE-READ 默认（官方确认）
  间隙锁/Next-Key Lock（官方 innodb-locking 文档）
  Read View 可见性规则（官方 MVCC 文档）
```
