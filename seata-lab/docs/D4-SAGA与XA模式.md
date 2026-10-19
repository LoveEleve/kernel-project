# D4 — SAGA 与 XA 模式

> **定位**：AT（自动补偿）和 TCC（手动补偿）之外的两模式——SAGA 解决长事务，XA 解决强一致。
> **深度**：架构原理级——状态机引擎 + 原生 2PC，不需要逐行分析。

---

## 一、SAGA 模式

### 1.1 场景：为什么 AT/TCC 都搞不定长事务

AT 和 TCC 都要求所有分支在一阶段内完成——锁/资源在整个全局事务期间被持有：

- AT 全局锁在一阶段到二阶段之间不释放
- TCC Try 冻结的资源在 Confirm/Cancel 前不释放

**如果业务时间跨度是分钟/小时级**（如订单→调度→维修→结算），锁持续持有会导致大量超时+回滚。SAGA 模式解决这个问题——**每步提交后立即释放资源，失败时执行反向补偿**。

### 1.2 状态机引擎

SAGA 把一次长事务定义为一个**状态机**（`saga/seata-saga-engine/`）——每个状态 = 一个服务操作（正向），每个状态都附带一个补偿操作（反向）：

```
正向: [下单] → [扣库存] → [调度] → [维修] → [结算]
                   │                  │
                   ▼                  ▼
反向:             [补库存]          [取消调度]
```

**引擎工作方式**：
1. 正向依次执行——每步调用后本地提交（释放资源）
2. 任一步骤失败→引擎从**失败节点的前一个节点开始反向执行补偿**
3. 补偿执行完毕后状态机终止为失败

**JSON 状态机定义**（Seata 用 StateLang DSL）——JSON 文件定义所有状态和转换规则，引擎运行时解析执行。

| 对比 | AT/TCC | SAGA |
|---|---|---|
| 事务时长 | 秒级 | 分钟/小时级 |
| 锁/资源持有 | 全程 | 每步提交立即释放 |
| 失败恢复 | 自动回滚（undo/fence） | 人工编排的反向补偿 |
| 复杂度 | AT 最低 → TCC 高 | 极高（状态机 + DSL + 每个节点的补偿） |

### 1.3 状态机 JSON 示例

```json
{
  "Name": "orderFlow",
  "StartState": "PlaceOrder",
  "States": {
    "PlaceOrder": {
      "Type": "ServiceTask",
      "ServiceName": "orderService.create",
      "Next": "DeductInventory",
      "CompensateState": "CancelOrder"
    },
    "DeductInventory": {
      "Type": "ServiceTask",
      "ServiceName": "inventoryService.deduct",
      "Next": "CreatePayment",
      "CompensateState": "CompensateInventory"
    },
    "CreatePayment": {
      "Type": "ServiceTask",
      "ServiceName": "paymentService.create",
      "Next": "Success",
      "CompensateState": "RefundPayment"
    }
  }
}
```

每个正向 State 配一个 `CompensateState`（反向补偿）——引擎按 JSON 定义依次执行，任何节点失败时**回退到前一个节点的 CompensateState**。补偿链必须**幂等**——同一个 SAGA 实例的补偿可能被重复调用。

### 1.4 本 lab 不做 SAGA 实操

SAGA 的 saga-engine-store 引入额外 DB 依赖且单模块下缺乏长事务演示场景。J架构原理 + JSON 定义了了解即可。生产实践由中间件团队预编排 SAGA 流。

---

## 二、XA 模式

### 2.1 依赖数据库原生 2PC

XA 使用数据库**原生分布式事务协议**（`javax.transaction.xa.XAResource`），Seata 不复写补偿逻辑，仅做 TC 协调：

```
App → DataSourceProxyXA → SeataXAResource → XAResource (DB)
  ↓
XA START → 业务 SQL1, SQL2, ...
  ↓
XA END → XA PREPARE (准备提交——此时数据库已经校验完，锁仍持有)
  ↓
TC 收集各分支 PREPARE 结果:
  全部 OK → XA COMMIT (各分支独立执行——锁释放)
  任一 NOT OK → XA ROLLBACK (各分支回滚——锁释放)
```

**与 AT 的本质区别**：
- AT：一阶段提交后释放本地锁 → 全局锁保护窗口期 → 二阶段异步清理
- XA：PREPARE 阶段**锁持续持有**直到 TC 下发 COMMIT/ROLLBACK → 强一致但不允许失败窗口

**两个实现要点**（官方文档，面试常问）：

| 要点 | 说明 |
|---|---|
| **分支注册时机** | XA start 需要 Xid，而 BranchId 由 TC 在分支注册时生成——所以 XA 模式必须**先注册分支、再 XA start**（AT 模式是本地事务提交前才注册）。官方指出这是将来可优化方向 |
| **数据源代理** | 优先从普通 DataSource 创建 XAConnection（保持透明化编程模型）；但驱动兼容性有风险（Oracle 明显，Druid issue #3707）——因此也支持直接代理 XADataSource |

```
业务 app
  → DataSourceProxyXA（拦截 JDBC 连接，注册 XA 分支）
  → SeataXAResource（包装 DB XA 连接）
  → DB 执行 XA START → 业务 SQL → XA END → XA PREPARE
  → TC 收集各分支的 prepare 结果
  → 所有分支 prepare OK → TC 下发 XA COMMIT
  → 任一分支 prepare FAIL → TC 下发 XA ROLLBACK
```

| 对比 | AT | XA |
|---|---|---|
| 补偿方式 | Seata 自动反向 SQL | 数据库原生 2PC（prepare→commit/rollback） |
| 一致性 | 最终一致 | **强一致** |
| 锁持有 | 一阶段提交后释放本地锁 | **全事务周期持有锁** |
| DB 依赖 | 任何支持事务的 DB（MySQL/PG/Oracle） | **需要 DB 支持 XA 协议**（如 InnoDB XA） |
| 性能 | 中 | **低**（锁持有时间长→竞争→超时） |

### 2.2 使用限制

- XA 要求**全局事务的每个分支（数据库连接）都支持 XA 协议**（大部分现代 DB 都支持）
- 事务过程中**不可混合 XA 和非 XA 分支**——全 XA 或全非 XA
- `DataSourceProxyXA` 替代 `DataSourceProxy`——Spring 配置中只需换一个类名

---

## 三、本篇要点

1. **SAGA = 长事务的状态机引擎**——每步提交释放资源，失败反向补偿
2. **XA = 数据库原生 2PC**——强一致但全周期持锁，性能最低
3. **选型梯度**：短事务 AT → 高并发无锁 TCC → 长事务 SAGA → 强一致 XA
