# D2 — Seata AT 模式详解

> **定位**：D1 给了四种模式全景，本篇深挖 AT（自动补偿）——Seata 的核心模式，只需加张 undo_log 表 + DataSourceProxy 包装即可实现零侵入分布式事务。
> **深度**：架构原理级——一阶段/二阶段的完整数据流 + 全局锁机制 + 三种 SQL 类型的处理差异。

---

## 一、AT 模式一句话总结

**一阶段本地提交 + undo_log 记录反向 SQL + 全局锁防脏写 → 二阶段 TC 决议后自动补偿（COMMIT 删 undo / ROLLBACK 执行反向 SQL）**。

和传统 2PC 的区别：一阶段就**提交**（不等 TC 决议），但提交前先锁定被修改的行（全局锁），提交后释放本地锁——二阶段只是清理工作。本质是"先提交再补偿"的乐观模型。

---

## 二、一阶段：DataSourceProxy → ExecuteTemplate → undo_log

### 2.1 拦截链

```
业务代码: jdbcTemplate.update("UPDATE account SET balance = balance - 100 WHERE id = 1")
   ▼
Seata DataSourceProxy → ConnectionProxy → PreparedStatementProxy
   ▼
ExecuteTemplate.execute() 拦截所有 DML:
   ▼
① 生成前镜像(beforeImage): SELECT * FROM account WHERE id = 1  → {balance=1000}
② 执行业务 SQL: UPDATE account SET balance = balance - 100 → {balance=900}
③ 生成后镜像(afterImage): SELECT * FROM account WHERE id = 1  → {balance=900}
④ 对比前后镜像 → 生成反向 SQL: UPDATE account SET balance = balance + 100
⑤ 锁检查：获取全局锁（RM 本地锁键 = `表名:主键`，如 `account:1`；TC 侧行锁 = `resourceId^^^tableName^^^pk`，`AbstractLocker.LOCK_SPLIT="^^^"`）
⑥ undolog 写入: INSERT INTO undo_log (xid, branch_id, rollback_info) VALUES (...)
   其中 rollback_info = JSON{反向SQL + before/after images + 表结构}
⑦ 本地事务提交——数据已写入 DB，本地锁释放
```

### 2.2 三种 SQL 类型的不同处理

`ExecuteTemplate` 按 SQL 类型分发到不同的 Executor（`rm-datasource/exec/`）：

| SQL 类型 | Executor | 处理差异 |
|---|---|---|
| `UPDATE` | `UpdateExecutor` | 标准的 beforeImage→执行→afterImage |
| `INSERT` | `InsertExecutor` | beforeImage=空，反向 SQL = DELETE；需从插入后的行获取主键 |
| `DELETE` | `DeleteExecutor` | afterImage=空，反向 SQL = INSERT；注意：delete 时需先 SELECT FOR UPDATE 加锁 |

**主键策略**：反向 SQL 用主键 WHERE 精准回滚（不是全表)——这也是为什么 undo_log 的 rollback_info 必须包含主键列。主键信息由 `SQLRecognizer` 解析 SQL 文本 + `TableMetaCache` 缓存 JDBC 元数据获取。

### 2.3 SQL 解析与表元数据

在构造镜像前需要知道两件事：**SQL 是什么类型**、**表的主键和列结构是什么**。

- **SQL 识别**：`SQLRecognizerFactory` 根据 SQL 文本解析为 `SQLRecognizer` 子类（`MySQLUpdateRecognizer` / `MySQLInsertRecognizer` / `MySQLDeleteRecognizer`），从中提取表名、WHERE 条件、操作类型
- **表元数据缓存**：`TableMetaCache`（`AbstractTableMetaCache` → 按 DB 类型有 MySQL/Oracle 实现）缓存每张表的主键列、所有列名和类型——每次执行 DML 时从缓存获取，缓存 miss 时通过 JDBC 元数据刷新（`getTableMeta`）

---

## 三、全局锁：一阶段与二阶段之间的写隔离

在一阶段提交后、TC 下发二阶段决议前的窗口期，**其他全局事务不能修改同一行数据**。

```
事务A (AT 模式): 
  阶段1: UPDATE account SET balance = 900 WHERE id = 1 → 获取全局锁（锁键 account:1）
  本地提交完成 → 本地锁释放
  等待 TC 二阶段决议...
  
事务B (AT 模式, 同时):
  阶段1: UPDATE account SET balance = 800 WHERE id = 1 → 申请全局锁（锁键 account:1）
  → 事务A 尚未释放全局锁 → 事务B 等待（LockRetryController 重试）
```

**全局锁 vs 本地锁的时序**：

```
本地锁(local DB row lock):  一阶段开始 ──▶ 一阶段提交(释放)    ──▶ (已释放)
全局锁(global lock):        一阶段提交时获取 ──▶ 二阶段决议(COMMIT/ROLLBACK释放)
                             △ 锁竞争窗口: 此期间其他全局事务的写被阻塞
```

**锁重试策略**（`LockRetryController`）：

- 默认**全局锁重试间隔**：`lockRetryInterval`（少量 ms）
- 默认**最大重试次数**：`lockRetryTimes`——超时后本地事务回滚，全局事务标记为失败
- `SELECT FOR UPDATE` 自动被 Seata 转换为全局锁请求——兼容 JPA/Hibernate 乐观锁。

**不会被全局锁阻塞的读**：非全局事务的 `SELECT`（不加 `FOR UPDATE`）可以**直接读已提交数据**——一阶段提交后数据对普通读可见。只有全局事务的 DML 才需要获取全局锁。

**@GlobalLock：只读操作的轻量锁**（`integration-tx-api/.../annotation/GlobalLock.java`）：

```java
// 只读 + 需与其他全局事务的写保持隔离时的注解
@GlobalLock(lockRetryInterval = 100, lockRetryTimes = 30)
public Account query(int id) {
    return jdbcTemplate.queryForObject("SELECT * FROM account WHERE id = ?", ...);
}
```

与 `@GlobalTransactional` 的区别：`@GlobalLock` **不注册全局事务，不记 undo_log——仅在读取时申请全局锁**。适用场景：查询操作需要"读已提交且不被全局事务中间状态污染"的，但不需要回滚能力（只要锁防脏写的并发隔离）。性能远优于开启一个完整 AT 事务——省去 TM→TC 注册/undo_log 写入/二阶段决议的全流程。

---

## 四、二阶段：TC 决议后的自动补偿

### 4.1 全局提交（ALL SUCCESS）

```
TC → RM: COMMIT
  RM: 异步删除 undo_log（DELETE FROM undo_log WHERE xid=? AND branch_id=?）
  RM: 释放全局锁
  → 完成。本地数据已在一阶段提交——不需要再写。
```

### 4.2 全局回滚（ANY FAILURE）

```
TC → RM: ROLLBACK
  RM: UndoLogManager.undo(dataSourceProxy, xid, branchId)
    → 读取 rollback_info → 解析反向 SQL + 前后镜像
    → 校验后镜像与当前数据是否一致（防数据被篡改）
    → 一致 → 执行反向 SQL（UPDATE account SET balance = balance + 100）
    → 不一致 → 触发脏写检测告警报错
  RM: 删除 undo_log、释放全局锁
```

**前后镜像校验 = AT 的一致性保障**：如果二阶段回滚时发现当前数据与 `afterImage` 不符——说明有非全局事务"跳过"了全局锁直接修改了数据。Seata 此时会**停止回滚、报错并需要人工介入**（默认行为，可配置）。

---

## 五、undo_log 表结构再确认

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT AUTO_INCREMENT | 主键 |
| branch_id | BIGINT | 分支事务 ID |
| xid | VARCHAR(100) | 全局事务 ID |
| rollback_info | LONGBLOB | JSON 序列化的反向补偿信息 |
| log_status | INT | 0=进行中 / 1=已完成（用于清理） |
| log_created | DATETIME | 记录创建时间 |
| log_modified | DATETIME | 记录修改时间 |

唯一约束：`UNIQUE KEY ux_undo_log (xid, branch_id)`

---

## 六、AT 模式下的连接代理链（ConnectionsProxy 树）

AT 模式下，对连接的包装生成一个代理树——每一次 getConnection/createStatement/PreparedStatement 都经过 Seata 代理层：

```
业务 DataSource
  → wrap → DataSourceProxy (持有原始 DataSource)
    → getConnection → ConnectionProxy (持有原始 JDBC Connection)
      → prepareStatement → PreparedStatementProxy
        → execute → ExecuteTemplate.execute()
          → 镜像生成 / undo / 锁 / 执行真实 SQL
```

每个 ConnectionProxy 是每个 RM 分支的根——TC 记录 branch_id = connection 粒度的一条分支。一条全局事务可以有多个分支（每个服务内的连接各自注册一个分支）。

---

## 七、AT 模式的限制（不能使用的场景）

AT 依赖镜像生成 + 反向 SQL——以下操作**不能**在 `@GlobalTransactional` 内执行：

| 操作 | 原因 |
|---|---|
| **DDL**（CREATE/ALTER/DROP） | 镜像生成是针对 DML 的——表结构变了无法生成反向 SQL |
| **触发器/存储过程** | Seata 无法自动生成其反向操作（内部可能有多个隐式 SQL） |
| **批量 SQL**（一条语句改多行但无 WHERE 精准定位） | 反向 SQL 需要主键精准回滚——批量更新无法确定每行的主键 |
| **非事务性操作** | Redis/MongoDB 的写——没有 JDBC 拦截层 |
| **读未提交隔离级别** | AT 要求一阶段提交的数据对其他事务的读可见——至少 READ_COMMITTED |

若在 AT 事务中执行了不支持的 SQL，`ExecuteTemplate` 捕获异常后整个全局事务回滚。

---

## 八、undo_log 清理

undo_log 不会自动无限堆积——两种清理路径：

1. **正常清理**：二阶段 COMMIT 时异步删除（`UndoLogManager.batchDeleteUndoLog`）
2. **定时清理**：Seata Server 有定时任务清理过期 undo_log（`log_status=1` 且 `log_created` 超过保留时间），默认保留 7 天

异常情况：二阶段回滚失败（前后镜像不一致）→ undo_log 保留 + 人工介入——此时不会自动删除，需手动处理异常数据后手动删除 undo_log 记录。

---

## 九、本篇要点

1. **AT = 一阶段提交 + undo_log 记录反向 SQL** — 比经典 2PC 快，因为本地提交后立即释放 DB 行锁
2. **全局锁在一阶段到二阶段之间防脏写** — 非全局事务的读不受影响，只有全局事务的写被锁
3. **前后镜像校验**：回滚时比较当前数据与一阶段后镜像 —— 若不一致→人工介入
4. **三种 DML 处理不同**：UPDATE 双向镜像 / INSERT before=空 / DELETE after=空
5. **反向 SQL 靠主键精准回滚**，不是全表重放——undo_log 必须记录主键
6. **AT 禁用在非事务性操作上**：DDL、批量执行、存储过程——所有这些无法生成镜像，Seata 会报错并强制回滚
