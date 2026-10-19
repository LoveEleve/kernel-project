---
title: "02-AT模式：undo_log与全局锁"
series: "Seata 分布式事务系列"
previous: "01-why-distributed-tx.md"
next: "03-TCC模式.md（待写）"
lab_refs:
  - "seata-lab/docs/D2-AT模式详解.md"
---

# 02-AT模式：undo_log与全局锁

## §0 它干了什么（例子先行）

余额 1000 的账户要扣 100（跨库事务里的一步）：

```
业务代码：  UPDATE account SET balance = balance - 100 WHERE id = 1
Seata 悄悄多做 4 步：
  前镜像：SELECT → {balance=1000}
  业务SQL：执行 → {balance=900}
  后镜像：SELECT → {balance=900}
  反向SQL：UPDATE balance = balance + 100（备份，等回滚用）
  + 锁 account:1（全局锁）+ 写 undo_log → 本地提交

之后：全局成功 → 删 undo_log 完事；失败 → 执行反向SQL，900→1000
```

- **它干了什么**：业务 SQL 顺手拍"前后两张照片"，回滚时按照片反着改。
- **输入/输出**：输入=一条 DML；输出=本地已提交 + 一条可回滚的 undo_log。
- **本篇回答**：镜像怎么生成？全局锁防什么？二阶段怎么补偿？什么场景不能用？

## §1 掌握卡（5 条）

1. **AT = 一阶段本地提交 + 反向 SQL + 全局锁 → 二阶段自动清理/补偿**。和经典 2PC 的区别：一阶段就提交（不等 TC），本地锁立即释放——"先提交再补偿"的乐观模型。
2. **镜像三种**：UPDATE 双向 / INSERT（before 空，反向=DELETE）/ DELETE（after 空，反向=INSERT，且要先 FOR UPDATE 锁行）。
3. **全局锁接力本地锁**：本地锁一阶段提交就放，全局锁管到二阶段——只挡全局事务的写，普通读不挡。
4. **回滚前校验后镜像**：对得上才执行反向 SQL；对不上=有人绕过全局锁改过数据（脏写：未经协调方同意就动了共享行）→ **停回滚，人工介入**。
5. **AT 五禁**：DDL / 触发器存储过程 / 无 WHERE 精准定位的批量 / 非事务存储（Redis）/ 读未提交——没镜像就没回滚。

## §2 一阶段：7 步拦截

```
业务代码 UPDATE（DML=INSERT/UPDATE/DELETE 这类改数据的语句）→ DataSourceProxy → ExecuteTemplate 拦截：
① 前镜像 SELECT   ② 执行业务SQL   ③ 后镜像 SELECT
④ 对比→反向SQL（UPDATE balance+100，带主键 WHERE 精准回滚）
⑤ 拿全局锁（RM 本地键 表:主键，TC 行锁 resource^^^table^^^pk）
⑥ 写 undo_log（rollback_info = 反向SQL+双镜像+表结构）
⑦ 本地提交——数据已入库，本地锁释放
```

三种 SQL 分发三个 Executor（`Insert/Update/DeleteExecutor`）；SQL 文本靠 `SQLRecognizer` 解析，表主键列靠 `TableMetaCache`（缓存表结构信息——主键列/列类型，miss 时查 JDBC）——**反向 SQL 靠主键，不是全表重放**，所以 rollback_info 必须带主键。

## §3 全局锁：窗口期的写隔离

```
本地锁：一阶段开始 ──▶ 一阶段提交(释放)
全局锁：一阶段提交时获取 ──▶ 二阶段决议后释放   ← 窗口期就靠它
```

窗口里事务 B 想改同一行 → 申请全局锁 → 等待（`LockRetryController` 重试，超限本地回滚全局失败）。三个要点：

- **普通 SELECT 不挡**：一阶段提交后数据对非全局事务可读（AT 要求至少 READ_COMMITTED）。
- `SELECT FOR UPDATE` 被 Seata 转成全局锁请求（兼容 JPA/Hibernate 的锁用法）。
- **`@GlobalLock` 轻量版**：只读场景只要隔离不要回滚 → 不注册事务、不写 undo，只在读时申请锁——省掉注册/undo/决议全流程。

## §4 二阶段：清理 vs 补偿

| | 全局提交 | 全局回滚 |
|---|---|---|
| 动作 | 异步删 undo_log + 放全局锁（数据一阶段已落） | 读 rollback_info → 校验后镜像 → 执行反向 SQL → 删 undo 放锁 |
| 校验 | 无 | **后镜像与当前数据不符 → 停回滚报错，人工介入** |

**镜像校验 = AT 的一致性守门员**：防的是"非全局事务绕过全局锁偷改数据"——改了就说明有人破坏隔离，Seata 不敢自动补，交给人。

## §5 undo_log 表

7 字段（`branch_id` / `xid` / `rollback_info`（BLOB=二进制大字段，存镜像 JSON）/ `log_status` / 时间两列），唯一键 `(xid, branch_id)`。清理两路：COMMIT 异步删 + 定时清 7 天过期；**回滚失败的不删**（留证人工处理）。

## §6 连接代理树

`DataSource → ConnectionProxy → PreparedStatementProxy → ExecuteTemplate`——每个 ConnectionProxy = 一个 RM 分支（branch_id 连接粒度），一全局事务可多分支。这就是 01 说的"零侵入"：包一层 DataSource，业务代码无感。

## §7 边界（一句话翻篇）

- SQL 解析器族/镜像 JSON 全字段 → `lab D2 §二` 用时查。
- 锁表结构/TC 侧实现 → 深挖时看源码。
- TCC 三防为什么不需要镜像 → 03。
- undo_log 与 RocketMQ 事务消息的 OP 标记对比 → 06。

## §8 Self-Test（4 题）

- Q1：AT 一阶段和经典 2PC 一阶段的区别？（提交时机 + 本地锁）
- Q2：INSERT 和 DELETE 的反向 SQL 各是什么？为什么 DELETE 要先 FOR UPDATE？
- Q3：本地锁和全局锁各管到什么时候？普通读会被挡吗？
- Q4：回滚时后镜像对不上说明什么？Seata 怎么处理？

## §9 衔接

- 本篇回答默认模式；下篇 03 回答 TCC（自己写三阶段 + fence 三防——什么时候必须不用 AT）。
