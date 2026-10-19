# Seata 分布式事务系列

> 基线：**Seata 客户端 2.0.0 + TC 2.1.2**（AT 端到端已验证）
> 素材：`seata-lab/docs/`（D1~D5，已审校）+ `docs/中间件拷打/K06-Seata.md`（面试收口）
> 源码：`code/spring/seata-2.0.0`
> 理论线（前置）：`dist-theory/03-distributed-transaction-2pc-tcc.md` + `dist-theory/04-saga-reliable-message.md`
> xhs 实锤：`TccFenceService`（栅栏三防）/ `InventoryTccService` / `TccTimeoutJob`

## 一、总表（8 篇）

| # | 文章 | 类型 | 核心内容 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [为什么需要分布式事务](01-why-distributed-tx.md) | 理论 | 两阶段问题；TC/TM/RM 三角色；`@GlobalTransactional` 唯一入口 | D1 |
| 02 | [AT 模式：undo_log 与全局锁](02-at-mode.md) | 原理 | 一阶段双写；全局锁写隔离；二阶段补偿；限制 | D2 |
| 03 | [TCC 模式：三阶段与 fence](03-tcc-mode.md) | 原理 | Try/Confirm/Cancel；fence 三防（悬挂/空回滚/幂等）；TCC vs AT | D3 |
| 04 | [SAGA、XA 与四模式选型](04-saga-xa-selection.md) | 原理 | SAGA 补偿链；XA 两阶段；何时不用 Seata | D4/D1 |
| 05 | [集成层：Scanner 与数据源代理](05-integration.md) | 使用 | GlobalTransactionScanner；DataSourceProxy 装配；配置速查 | D5 |
| 06 | [三方案对比：Seata vs 事务消息 vs Outbox](06-three-way-comparison.md) | 对照 | 分工边界；dist-theory 理论线回链；RocketMQ 06 钩子兑现 | D1/Dist/RocketMQ |
| 07 | [xhs 生产对照：自研 TCC 与 fence 实锤](07-xhs-production.md) | 生产 | TccFenceService 三防；扣库存 TCC；超时 Job；生产陷阱 7 条 | xhs 3 类 + K06 |
| 08 | [面试收口：五段式与拷打](08-interview-wrapup.md) | 收口 | K06 五段式；拷打追问；终检 | K06 |

## 二、学习路径

```text
理论：01
核心：02 → 03
全景：04
接入：05
对照：06
生产：07
收口：08
```

## 三、前置要求

- dist-theory 03（2PC/TCC）与 04（Saga/可靠消息）——理论线，本系列讲实现与源码。
- RocketMQ 06（事务消息）——三方案对比的一角。
- Boot 自动配置基础（Tracing 04 讲过条件注解）。

## 四、掌握分级

- **必须掌握**：三角色 + XID；AT 一阶段 undo_log + 二阶段补偿 + 全局锁；TCC 三防；四模式选型；`@GlobalTransactional` 是唯一业务感知点。
- **理解思想**：连接代理树；全局锁与本地锁的关系；fence 与幂等表同源（RocketMQ 06 说过 Outbox 靠唯一约束）。
- **知道即可**：XA 协议细节；SAGA 状态机；TC 集群部署。

## 五、边界

- 理论推导（2PC 为什么阻塞、CAP 权衡）只引用不重讲 → dist-theory 03/04。
- RocketMQ 内部（HALF/回查）只对比不展开 → RocketMQ 06。
- 三者选型只在 06 收，Kafka/其他 MQ 对比 → L5-4 条目。
