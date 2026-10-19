---
title: "03-TCC模式：三阶段与fence"
series: "Seata 分布式事务系列"
previous: "02-at-mode.md"
next: "04-SAGA与XA.md（待写）"
lab_refs:
  - "seata-lab/docs/D3-TCC模式详解.md"
source_refs:
  - "xhs common/tcc/TccFenceService.java:14-16（三防注释）——07 展开"
---

# 03-TCC模式：三阶段与fence

## §0 它干了什么（例子先行）

还是扣库存，但这次自己写三个方法（而不是让 Seata 生成反向 SQL）：

```
Try（预留）：  UPDATE sku SET frozen = frozen+100 WHERE id=9  ← 冻结，不真扣
Confirm（确认）：UPDATE sku SET stock = stock-100, frozen=frozen-100  ← 真扣
Cancel（取消）：UPDATE sku SET frozen = frozen-100  ← 解冻，等于没发生
```

- **它干了什么**：业务自己定义"预留—确认/释放"三步；Seata 只负责按全局结果调度这三个方法。
- **输入/输出**：输入=全局提交/回滚；输出=三方法之一被执行。
- **与 AT 的分工**：AT = Seata 帮你写反向 SQL（锁行）；TCC = 你自己写三个方法（无锁，靠业务语义隔离）。
- **本篇回答**：三阶段各干什么？Cancel 先到怎么办（fence 三防）？何时不用 AT 用 TCC？

## §1 掌握卡（4 条）

1. **三阶段全是持久化操作**：Try 预留（冻结/锁定）、Confirm 用掉预留、Cancel 释放预留——都写业务表，都落盘。
2. **`@TwoPhaseBusinessAction` 三方法**：`commitMethod`/`rollbackMethod` 指名；`BusinessActionContext` 带 xid/branchId/业务参数。
3. **fence 状态机三防**：防悬挂（Cancel 先到→拒 Try）/ 空回滚（Try 没来→Cancel 记录占位）/ 幂等（重试查状态跳过）——一张 `tcc_fence_log` 表（fence=栅栏：一条状态记录当关卡，拦住迟到/重复的操作），`(xid, branch_id)` 主键，状态单向。
4. **TCC vs AT**：侵入高但无锁、并发度=DB 本身——高并发扣库存/抢券用 TCC；普通 CRUD（增删改查）用 AT（04 讲全谱选型）。

## §2 三阶段与注解

```java
@TwoPhaseBusinessAction(name="orderTcc", commitMethod="commit", rollbackMethod="cancel")
public boolean prepareOrder(BusinessActionContext ctx,
        @BusinessActionContextParameter("orderId") Long orderId) { /* Try: 冻结 */ }
public boolean commit(BusinessActionContext ctx) { /* Confirm: 真扣 */ }
public boolean cancel(BusinessActionContext ctx) { /* Cancel: 解冻 */ }
```

| 阶段 | 谁调 | 干什么 | 持久化？ |
|---|---|---|---|
| Try | 全局事务调度到分支 | 预留（冻结/锁定） | 是 |
| Confirm | TC 二阶段 COMMIT | 用预留 | 是 |
| Cancel | TC 二阶段 ROLLBACK | 释放预留 | 是 |

注解参数：`name` 必填；commit/rollback 默认名 `commit`/`rollback`；**`useTCCFence` 默认 false，生产必开**（下节）。

## §3 fence：一张表治三种异常

| 异常 | 长什么样 | fence 解法 |
|---|---|---|
| **空回滚** | Try 网络超时没执行，Cancel 先到 | Cancel 插一条占位记录（status=4 SUSPENDED）——回滚记号先立着 |
| **防悬挂** | 同上，且 Try 迟到会把已回滚的库存又冻结 | Try 查表发现 xid 已有记录 → **拒绝执行** |
| **幂等** | Confirm/Cancel 被 TC 重试多次 | 查状态：已 COMMITTED/ROLLBACKED → 跳过 |

**防悬挂的精髓 = Cancel 的 INSERT + 唯一约束**：Try 先来则 INSERT 冲突→走正常取消；Try 没来则 INSERT 成功→迟到的 Try 被拒。

状态机单向：`不存在 →(Cancel) SUSPENDED / →(Try) TRIED → COMMITTED | ROLLBACKED`——不回退。表主键 `(xid, branch_id)`（和 02 的 undo_log 唯一键同款思路：**每分支一条记录**）。

Try/Commit/Cancel 的 fence 工作流（先查状态再执行业务，UPDATE 行数为 0 即跳过）——xhs 的 `TccFenceService` 注释把三防写全了（07 展开对照）。

## §4 注册与调度

`TCCResourceManager` 启动时扫注解 → 向 TC 注册资源（方法全限定名）。分支执行：TC 调度 → 反射调三方法（反射=运行时按方法名字符串调用代码，不需要编译期知道）→ **fence handler 先跑栅栏逻辑** → 再跑业务代码 → 回报 TC。入参靠 `BusinessActionContext` 自动填充（`ctx.getActionContext("paramName")`）。

## §5 TCC vs AT

| 维度 | AT | TCC |
|---|---|---|
| 侵入 | 极低（一张 undo 表+代理） | 高（每参与方写三方法） |
| 锁 | 全局锁 | **无锁**——业务语义隔离 |
| 性能 | 中 | **最高**（并发=DB 本身） |
| 一致性守门 | 前后镜像校验 | fence 状态机 |
| 适用 | 大多数 CRUD（增删改查） | 高并发抢库存/抢券/秒杀 |

选型直觉：**能用 AT 先 AT；锁竞争打不动了、或非 JDBC 资源也要参与事务（02 说过 Redis 写进不了 AT——TCC 的三方法可以包任意资源）再上 TCC**（04 收全谱）。

## §6 边界（一句话翻篇）

- fence 表 DDL 全字段/状态值 DDL → `lab D3 §三` 用时查。
- lab 扣库存完整实现 → `lab D3 §六`。
- 与 Seata AT 的 undo_log 表对比 → 06。

## §7 Self-Test（4 题）

- Q1：AT 和 TCC 各靠什么"防脏"？（全局锁 vs 业务预留）
- Q2：三异常（空回滚/悬挂/幂等）各是什么现象？fence 怎么治？
- Q3：`useTCCFence` 默认值？不打开会怎样？
- Q4：Cancel 先到、Try 后到，完整走过 fence 的哪几步？

## §8 衔接

- 本篇回答"业务自己写三阶段"；下篇 04 回答 SAGA/XA 与四模式选型全景。
