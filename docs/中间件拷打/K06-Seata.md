# K06 — 组件深度拷打：Seata（分布式事务）

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：TC/TM/RM、AT（undo_log+全局锁）、TCC（空回滚/悬挂/幂等 fence）、SAGA/XA、选型（什么时候**不用** Seata）
> 依据：seata-lab（D1-D5 + REVIEW）**客户端 2.0.0 + TC 2.1.2** 语料 + xhs-project 自研 TCC Fence（参考 Seata 设计）

---

## 问题

分布式事务有哪几种解法？Seata 的 AT 模式怎么做到零侵入？TCC 的三个坑怎么解决？你们项目为什么（不）用 Seata？

---

## 面试可讲版（五段式）

### ① 核心模型（TC/TM/RM + XID）

```
TM（事务管理器，发起方）：@GlobalTransactional → 开启全局事务（XID）
TC（事务协调器，独立 Server）：维护全局/分支事务状态，下发 commit/rollback 决议
RM（资源管理器，各参与者）：代理数据源，执行分支事务，上报状态
XID：全局事务唯一标识，随调用链透传（HTTP/Dubbo 拦截器）
```

**四种模式全景**：AT（自动补偿，零侵入）/ TCC（手写三方法，最灵活）/ SAGA（长事务状态机）/ XA（数据库原生 2PC）。
`@GlobalTransactional` 默认值（2.0.0 真源）：`timeoutMills=60000`、`name=""`、`rollbackFor={}`、`noRollbackFor={}`、`propagation=REQUIRED`。

### ② AT 模式（源码核心）

**一句话**：一阶段**本地提交** + undo_log 记反向 SQL + 全局锁防脏写 → 二阶段 TC 决议后补偿（COMMIT 删 undo / ROLLBACK 执行反向 SQL）——本质是"先提交再补偿"的乐观模型。

**一阶段拦截链**（`DataSourceProxy → ConnectionProxy → ExecuteTemplate`）：
```
① 前镜像（SELECT 快照）→ ② 执行业务 SQL → ③ 后镜像
④ 对比生成反向 SQL（undo_log.rollback_info）
⑤ 获取全局锁 → ⑥ 写 undo_log → ⑦ 本地提交（释放本地行锁）
```

**锁键口径（易错点，已按 2.0.0 源码核实）**：
- **RM 本地锁键** = `表名:主键`（如 `account:1`；多行 `t_user:1_a,2_b`）
- **TC 侧全局行锁键** = `resourceId^^^tableName^^^pk`（`AbstractLocker.LOCK_SPLIT="^^^"`）
- 全局锁持有窗口：一阶段提交 → 二阶段决议释放（期间**其他全局事务**写同行被阻塞重试：默认 10ms × 30 次）

**三种 SQL 的差异**：UPDATE（前后镜像）；INSERT（beforeImage 空，反向 SQL=DELETE，需回查插入主键）；DELETE（afterImage 空，反向 SQL=INSERT，需先 SELECT FOR UPDATE）。

**二阶段**：COMMIT 异步删 undo_log；ROLLBACK 执行反向 SQL 后删 undo_log——**业务无感知**（零侵入的代价：全局锁 + undo_log 表）。

### ③ TCC 模式（三问题与 fence）

**与 AT 的本质区别**：AT 锁数据（全局行锁）；TCC **不锁数据**——用业务"冻结/预留"替代行锁（性能最高，但三方法必须正确实现）。

```
Try（预留：冻结 100）→ Confirm（确认：扣冻结）→ Cancel（取消：解冻）
@TwoPhaseBusinessAction(name, commitMethod, rollbackMethod)
```

**三个经典坑 → fence 表唯一正确解**（Seata 1.5+ `useTCCFence=true`，**生产必开**）：
| 场景 | 问题 | fence 方案 |
|---|---|---|
| **空回滚** | Cancel 比 Try 先到（Try 超时） | Cancel 先插一条 ROLLBACKED 记录；Try 到达时查到记录 → 拒绝执行 |
| **悬挂** | 空回滚后 Try 才到 → 写入已回滚数据 | 同上（fence 记录阻止 Try） |
| **幂等** | Confirm/Cancel 被 TC 重试 | 查 xid+branch_id 状态，已 COMMITTED/ROLLBACKED → 跳过业务 |

状态值（真源逐字）：`STATUS_TRIED=1 / COMMITTED=2 / ROLLBACKED=3 / SUSPENDED=4`；表 `tcc_fence_log`（PK xid+branch_id）。

### ④ SAGA/XA 与选型（什么时候不用 Seata）

**SAGA**：长事务状态机（每步配补偿），适合编排型业务流程；**XA**：数据库原生 2PC（Seata 代理 XADataSource）——要点：分支注册必须在 XA start 之前（BranchId 由注册生成）、数据源代理兼容性有坑（支持直接代理 XADataSource；Druid 有已知 issue）。

**xhs 选型（不用 Seata 的理由，项目文档原文）**：
| 维度 | 事务消息（✅ 选定） | TCC | Seata AT |
|---|---|---|---|
| 一致性 | 最终一致 | 强一致 | 最终一致 |
| 性能 | 高（异步） | 中（3 接口） | 中（**全局锁瓶颈**） |
| 侵入性 | 中（Listener） | 高（Try/Confirm/Cancel） | 低 |
| 场景 | 核心链路（下单） | 资金类（转账） | 非核心链路 |

**但 xhs 借了 TCC 的"内功"**：自研 `TccFenceService`（设计参考 Seata TCC Fence）用于库存预扣——**不用 Seata 框架，用 Seata 的解法**。

### ⑤ 拷打追问（面试官视角）

1. **"AT 一阶段提交了，二阶段回滚怎么办？"** 靠 undo_log 反向 SQL 补偿（此时数据已被其他事务读过——所以需要全局锁防脏写；回滚是"补偿"不是"撤销"）。
2. **"全局锁锁什么？"** 锁的是"行"（TC 侧 resourceId^^^table^^^pk）；本地行锁一阶段就释放——全局锁活到二阶段决议。
3. **"TCC 空回滚怎么判？"** Cancel 先插 ROLLBACKED fence 记录；Try 查到记录 → 拒绝（同时防悬挂）。
4. **"Confirm 和 Cancel 都到了怎么办？"** fence 状态机保证互斥：Confirm 只能 1→2、Cancel 只能 1→3（乐观锁 UPDATE ... AND status=1）——已 Confirm 收到 Cancel = 非法（告警）。
5. **"AT 和 TCC 怎么选？"** 无侵入/一般业务 → AT；资金/高并发/要强一致 → TCC；长流程 → SAGA；数据库原生 → XA。
6. **"TC 挂了怎么办？"** TC 集群 + 高可用；事务状态持久化（file/db/redis 存储模式）；超时事务由 TC 驱动回滚。
7. **"为什么不用 Seata？"**（xhs 口径）下单链路要异步解耦 → 事务消息性能最好；TCC 全链路侵入太大；AT 全局锁有性能瓶颈——只在库存预扣借了 TCC fence 的机制。
8. **"分布式事务和最终一致什么关系？"** 强一致（TCC/AT 的锁）vs 最终一致（事务消息/SAGA/对账）——多数业务选最终一致 + 幂等 + 对账兜底（dist-consistency 四模式）。

### ⑥ 话术

> "分布式事务分强一致和最终一致两条线：强一致是 Seata 的 AT/TCC/XA，最终一致是事务消息/SAGA/对账。Seata 里 AT 是一阶段提交+undo_log 反向补偿+全局锁防脏写，零侵入但有全局锁瓶颈；TCC 是手写三方法，靠 fence 表解决空回滚/悬挂/幂等。我们项目**没有引入 Seata**：下单用 RocketMQ 事务消息（异步解耦性能最好），库存预扣自研了 TccFenceService——表结构、状态机、三态返回都参考 Seata 的 fence 设计，还加了 xid 维度的冻结明细表解决聚合冻结的超时判断问题。"

---

## 生产陷阱（Seata/分布式事务 7 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **AT 全局锁热点** | 同一行被多全局事务竞争——热点行别用 AT；或改 TCC/异步 |
| 2 | **TCC 不开 fence** | 空回滚/悬挂/幂等全靠它——`useTCCFence=true` 生产必开 |
| 3 | **TCC 三方法不幂等** | 网络重试必然发生——fence 状态机 + 业务幂等键 |
| 4 | **空回滚当成异常** | Cancel 先到是正常场景——必须"插记录+跳过业务"，不是报错 |
| 5 | **undo_log 表忘建/清理** | AT 模式每个库都要 undo_log；历史数据要定期清理 |
| 6 | **本地锁键理解错** | RM 本地锁 `表名:主键` vs TC 全局锁 `resourceId^^^tableName^^^pk`——排障时别找错地方 |
| 7 | **无脑上强一致** | 大多数业务最终一致更合适——幂等+对账兜底（对账修复率、差异告警） |

---

## 发散追问地图（横向）

- **对比**：2PC/3PC（阻塞/协调者单点）、本地消息表（Outbox）、事务消息（RocketMQ 半消息）、TCC、SAGA、对账——一张"一致性手段谱系"
- **Seata 内部**：TC 存储模式（file/db/redis）；TC 集群与事务分组（tx-service-group）；TM/RM 与 TC 通信（Netty）
- **fence 深入**：状态机与乐观锁；xhs 的 `t_tcc_freeze_detail`（xid 维度冻结）改进
- **业务结合**：库存预扣（冻结→确认/解冻）；资金转账（TCC 经典场景）；订单-优惠券-积分联动
- **兜底体系**：补偿 Job（XXL-Job）、对账（dist-consistency D3）、Outbox 重试退避

---

## 面试官评分点

**高级开发级**：TC/TM/RM 讲得清；AT 一阶段/二阶段数据流；TCC 三坑与 fence 方案；知道项目用了什么方案及理由。
**架构师加分**：AT"先提交再补偿"的乐观模型本质；全局锁 vs 本地锁的时序窗口；fence 状态机防并发双 Confirm/Cancel（乐观锁）；选型权衡（性能/侵入/一致性）而不是背方案；"不用 Seata"的理由（事务消息+自研 fence）与覆盖边界。
**危险信号**：AT 说成"一阶段不提交"；TCC 不谈空回滚/悬挂；以为 Seata 是银弹（全局锁热点）；项目用了什么说不清；把本地锁键与全局锁键搞混。

---

## 本项目真实证据

**xhs-project（不用 Seata，借其解法）**：
- `TccFenceService`（my-xhs-common/tcc）：三态返回（TryFenceResult: FIRST/DUPLICATE/SUSPENDED；Confirm: EXECUTE/SKIP_DUPLICATE/REJECTED；Cancel: EXECUTE/SKIP/REJECTED_CONFIRMED）；乐观锁状态转换（`UPDATE ... WHERE status=1`）
- `t_tcc_fence` DDL（sql/mysql-inventory-init.sql:22-30，PK xid+branch_id，status 1/2/3）+ `t_tcc_freeze_detail`（xid 维度冻结明细，解决聚合 freezing_stock 超时判断缺陷）
- 使用方：`InventoryTccService`（tryDeductStock 等，先 fence 后业务）
- 选型文档：`docs/test-1/.../22-distributed-transaction`（事务消息 vs TCC vs Seata AT 对比表 + "TCC 侵入太大/AT 全局锁瓶颈"原文）
- 兜底：LocalMessageRetryJob / 补偿 Job / dist-consistency 四模式

**seata-lab（客户端 2.0.0 + TC 2.1.2）**：
- D1-D5（核心概念/AT/TCC/SAGA-XA/集成层）+ REVIEW（锁键口径修正、XA"三种变形"编造清除、fence 四态逐字核实、配置默认值核实）
- 环境：TC `21.130.247.89:27091` / MySQL / 端口 28041（AT 端到端已验证）

---

## 版本与来源

Seata **客户端 2.0.0 + TC 2.1.2**（源码语料 `code/spring/seata-2.0.0` + seata-lab）；官方文档；xhs-project TCC fence 自研代码与选型文档。

## 真实性说明

Seata 机制为 2.0.0 源码核验结论（锁键/fence 状态/默认值逐字核实；"XA 三种变形"等编造内容已在 REVIEW 中清除）；xhs 用法（TccFenceService/t_tcc_fence/选型理由）为代码与文档事实。
