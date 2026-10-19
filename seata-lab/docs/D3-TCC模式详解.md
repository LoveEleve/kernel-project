# D3 — TCC 模式详解

> **定位**：D1/D2 讲了 AT（自动补偿），本篇讲 TCC（业务手动补偿）——性能最高但侵入性最强的分布式事务模式。
> **深度**：架构原理级——Try/Confirm/Cancel 三阶段 + fence 表 空回滚/防悬挂/幂等全场景。

---

## 一、TCC 一句话总结

**AT = Seata 帮你写反向 SQL。TCC = 你自己写三个方法（Try/Confirm/Cancel）。** AT 锁数据、TCC 不锁——用业务语义的"冻结/预留"替代数据库行锁，性能最高，但必须正确实现三个方法才能保证一致性。

---

## 二、Try/Confirm/Cancel 三阶段

每个 TCC 参与者需要三个方法，用 `@TwoPhaseBusinessAction` 标注（`tcc/.../api/TwoPhaseBusinessAction.java`）：

```java
@TwoPhaseBusinessAction(name = "orderTcc", commitMethod = "commit", rollbackMethod = "cancel")
public boolean prepareOrder(BusinessActionContext ctx,
        @BusinessActionContextParameter("orderId") Long orderId) {
    // Try: 预留资源—不真正扣减，只是"冻结"
    // e.g. UPDATE order SET status='FROZEN', frozen_amount=100 WHERE id=?
    return true;
}

public boolean commit(BusinessActionContext ctx) {
    // Confirm: 使用预留资源—真正扣减
    // e.g. UPDATE order SET status='PAID', frozen_amount=0 WHERE id=?
    return true;
}

public boolean cancel(BusinessActionContext ctx) {
    // Cancel: 释放预留资源—解冻返还
    // e.g. UPDATE order SET status='CANCELLED', frozen_amount=0 WHERE id=?
    return true;
}
```

| 阶段 | 谁调用 | 操作 | 是否持久化 a |
|---|---|---|---|
| **Try** | TM 发起全局事务→TC 调度到分支 | 预留资源（冻结/锁定） | **是**（数据已写入业务表） |
| **Confirm** | TC 二阶段 COMMIT | 使用预留的资源 | **是**（业务表状态变更） |
| **Cancel** | TC 二阶段 ROLLBACK | 释放预留的资源 | **是**（解冻恢复到原始状态） |

**注解参数**：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `name()` | （必填） | TCC 资源名——建议与 actionName 一致 |
| `commitMethod()` | `"commit"` | 提交方法名 |
| `rollbackMethod()` | `"rollback"` | 回滚方法名 |
| `useTCCFence()` | **false** | **是否启用防悬挂+幂等栅栏**——生产环境必开 |

**BusinessActionContext**：Try 开始前 Seata 注入上下文——包含 xid/branchId/actionName/业务参数（`@BusinessActionContextParameter` 注解的字段）。业务代码通过 ctx 获取这些信息——Confirm 和 Cancel 方法都需要 ctx 传入，作为幂等判断的键。

---

## 三、fence 表：防悬挂/空回滚/幂等的唯一正确方案

TCC 的三种异常场景靠**一张 fence 日志表 + 状态机**解决（`integration-tx-api/fence/store/`）：

| 场景 | 问题 | fence 解决方案 |
|---|---|---|
| **空回滚** | TC 下发 Cancel，但 Try 阶段网络超时还没执行——Cancel 先到了 | Cancel 在 fence 表中**插入一条空记录**（xid+branch_id+status=ROLLBACKED）。之后 Try 到达时查询 fence 表发现 xid 已存在→**拒绝执行**，防止错误的数据写入 |
| **防悬挂** | 同上（Cancel 先到）——Try 若执行就写入已回滚的数据 | 同上 fence 记录阻止 Try——业务表数据永不写入，一致 |
| **幂等** | Confirm/Cancel 被 TC 重试——同一 xid+branch_id 可能调用多次 | fence 表检查 xid+branch_id 的当前状态，若已经是 COMMITTED/ROLLBACKED→**跳过，不重复执行业务操作** |
| **Try 重复** | Try 网络超时重试 | fence 表检查 xid+branch_id 是否已存在→已存在则跳过（无论当前状态是什么——同一个 xid 的 Try 只能成功一次） |

**fence 表结构**（`script/client/tcc/db/mysql.sql`，表名 `tcc_fence_log`）：

```sql
CREATE TABLE IF NOT EXISTS `tcc_fence_log`
(
    `xid`           VARCHAR(128)  NOT NULL COMMENT 'global id',
    `branch_id`     BIGINT        NOT NULL COMMENT 'branch id',
    `action_name`   VARCHAR(64)   NOT NULL COMMENT 'action name',
    `status`        TINYINT       NOT NULL COMMENT 'status(tried:1;committed:2;rollbacked:3;suspended:4)',
    `gmt_create`    DATETIME(3)   NOT NULL COMMENT 'create time',
    `gmt_modified`  DATETIME(3)   NOT NULL COMMENT 'update time',
    PRIMARY KEY (`xid`, `branch_id`),
    KEY `idx_gmt_modified` (`gmt_modified`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

| 字段 | 用途 |
|---|---|
| xid | 全局事务 ID |
| branch_id | 分支事务 ID |
| action_name | TCC 资源名 |
| status | 状态：**1(TRIED)→2(COMMITTED)/3(ROLLBACKED)**；Cancel 空回滚时直接插入 **4(SUSPENDED)** |
| gmt_create / gmt_modified | 时间戳 |
| PRIMARY KEY `(xid, branch_id)` | 每分支只有一条，状态流转不可逆 |

**fence 工作流**（`prepareFence/commitFence/rollbackFence`，状态值从 DDL）：

```
Try:
  ① SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND branch_id=?
  ② 若 status=4(SUSPENDED)→拒绝（防悬挂，Cancel 已先到）
  ③ 若已存在任何记录→跳过（幂等）
  ④ INSERT status=1(TRIED)
  ⑤ 执行业务 Try

Commit:
  ① UPDATE tcc_fence_log SET status=2(COMMITTED)
      WHERE xid=? AND branch_id=? AND status=1(TRIED)
    → 更新行数为 0 → 已 committed/rollbacked→跳过（幂等）
  ② 执行业务 Commit

Cancel:
  ① 空回滚路径: INSERT status=4(SUSPENDED)
      → 唯一约束冲突→说明 Try 已记录(1)→走正常取消路径
  ② 正常路径: UPDATE SET status=3(ROLLBACKED)
      WHERE xid=? AND branch_id=? AND status=1(TRIED)
  ③ 执行业务 Cancel
```

**四种 status 的完整状态机**：

```
          Cancel先到
  [不存在] ──▶ SUSPENDED(4) ──▶ Try到达→拒绝(防悬挂)
     
          Try先到
  [不存在] ──▶ TRIED(1) ──▶ COMMITTED(2)
                         └──▶ ROLLBACKED(3)
  
SUSPENDED→Try 被拒绝→不回退至 TRIED（状态单项）
TRIED→COMMITTED/ROLLBACKED 不回退（状态单项）
```

**关键防悬挂机制**：Cancel 的 INSERT + 唯一约束——如果 Try 先执行（INSERT 成功），Cancel 的 INSERT 会因唯一约束冲突→转为 UPDATE→正常取消路径。如果 Try 没执行（xid 不存在），Cancel 的 INSERT 成功→记录为回滚状态→后续 Try 的查询检测到 xid 已存在→拒绝执行→防悬挂成功。

---

## 四、TCC 资源注册与 RPC 模型

`TCCResourceManager`（`tcc/.../TCCResourceManager.java`）在 Spring 启动时扫描 `@TwoPhaseBusinessAction` 注解→向 TC 注册 TCC 资源（resourceId = 方法全限定名）。分支执行时：

```
TM → TC → 调度分支到对应 RM 的 TCCResource
           ↓
      使用反射调用 try/commit/cancel 方法
           ↓
      fence handler 先执行栅栏逻辑
           ↓
      反射执行业务代码
           ↓
      结果回报 TC
```

业务方法的入参由 `BusinessActionContext` 自动填充——`@BusinessActionContextParameter` 标注的字段在 Confirm/Cancel 中通过 `ctx.getActionContext("paramName")` 或自动注入可用。

---

## 五、TCC vs AT 对比（何时用哪个）

| 维度 | AT | TCC |
|---|---|---|
| 侵入性 | 极低（undo_log 表 + DataSourceProxy） | 高（每个参与方写三方法） |
| 锁机制 | 全局锁（SELECT FOR UPDATE） | 无锁——靠业务语义隔离 |
| 性能 | 中（全局锁竞争） | **最高**（无锁，并发度 = DB 本身） |
| 实现复杂度 | 零（Seata 全自动） | 每个接口都要写 fence 逻辑 |
| 一致性保障 | 前后镜像校验 | fence 表状态机 |
| 适用 | 大多数微服务 CRUD | 高并发扣库存/抢券/秒杀——需要无锁竞速 |

---

## 六、本 lab 实例：一个 TCC 扣库存的完整实现（A 类验证预告）

```java
@TwoPhaseBusinessAction(name = "inventoryTcc", useTCCFence = true)
public boolean tryDeduct(BusinessActionContext ctx,
        @BusinessActionContextParameter("skuId") Long skuId,
        @BusinessActionContextParameter("qty") int qty) {
    // 冻结库存——不真正扣减
    jdbcTemplate.update("UPDATE inventory SET frozen = frozen + ? WHERE sku_id = ?", qty, skuId);
    return true;
}

public boolean commitDeduct(BusinessActionContext ctx) {
    // 真正扣减——冻结转已扣
    Long skuId = (Long) ctx.getActionContext("skuId");
    int qty = (int) ctx.getActionContext("qty");
    jdbcTemplate.update("UPDATE inventory SET stock = stock - ?, frozen = frozen - ? WHERE sku_id = ?", qty, qty, skuId);
    return true;
}

public boolean cancelDeduct(BusinessActionContext ctx) {
    // 解冻——归还冻结库存
    Long skuId = (Long) ctx.getActionContext("skuId");
    int qty = (int) ctx.getActionContext("qty");
    jdbcTemplate.update("UPDATE inventory SET frozen = frozen - ? WHERE sku_id = ?", qty, skuId);
    return true;
}
```

（A 类验证时实现 + fence 表建表脚本 + curl 端点测试）

---

## 七、本篇要点

1. **TCC = Try（冻结）→ Confirm（真提交）/ Cancel（解冻）** — 每次都是满足幂等的数据库操作
2. **fence 表是唯一的防悬挂/空回滚/幂等的正确解决方案** — 不是"最佳实践"，是唯一方案
3. **空回滚 = Cancel 插入 fence 空记录 → Try 到达时被 fence 拦截不执行**
4. **幂等 = xid+branch_id 唯一约束 + status 检查** — COMMITTED 后不能回退
5. **Cancel 的 INSERT + 唯一约束** = 防悬挂的核心机制——Try 和 Cancel 谁先到都得靠 fence 判断
6. **useTCCFence=true 是生产环境强制性选项** — 不开启意味着没有空回滚/防悬挂/幂等任何保护
