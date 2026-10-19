# XXL-JOB 分布式任务调度系列

> 素材：`docs/中间件拷打/K13-XXL-Job.md`（五段式+陷阱 8 条+项目证据）+ xhs **28 个 Job 类** + 源码
> 源码：`code/spring/xxl-job`
> xhs 实锤：`XxlJobConfig` + 支付/库存/优惠券/购物车/Feed 五域 28 Job（补偿/对账/超时/Outbox 四类模式）
> 钩子：Seata 07 的 `TccTimeoutJob` 用 `@Scheduled`——01 推导起点

## 一、总表（7 篇）

| # | 文章 | 类型 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [为什么不用 @Scheduled](01-why-not-scheduled.md) | 理论 | 单机定时器四宗罪；调度框架解决什么 | xhs 双轨实证+K13 |
| 02 | [执行模型与注册发现](02-executor-model.md) | 使用 | JobHandler/调度中心/执行器三角色；注册 | K13① |
| 03 | [路由与分片](03-routing-sharding.md) | 使用 | 路由策略；分片广播怎么并行扫表 | K13① |
| 04 | [失败治理](04-failure-governance.md) | 生产 | 失败重试/超时/阻塞策略/告警 | K13①+陷阱 |
| 05 | [调度中心原理](05-scheduler-internals.md) | 原理 | 调度线程池/CRON/通讯模型 | 源码+K13① |
| 06 | [xhs 生产对照：四类任务模式](06-xhs-production.md) | 生产 | 28 Job 四类模式；真实事故三段 | K13②③④ |
| 07 | [面试收口：五段式与陷阱](07-interview-wrapup.md) | 收口 | 五段式回链；陷阱 8 条；终检 | K13 |

## 二、学习路径

```text
动机：01
模型：02 → 03
治理：04
原理：05
生产：06
收口：07
```

## 三、前置要求

- Spring `@Scheduled` 基础（01 直接对比）。
- RocketMQ 07（补偿 Job 与 MQ 重试的分工——xhs 双层重试）。
- Seata 07（TccTimeoutJob 场景回顾）。

## 四、掌握分级

- **必须掌握**：三角色；四类 Job 模式（补偿/对账/超时/Outbox 发送）；路由与分片；失败重试与阻塞策略。
- **理解思想**：调度中心与执行器分离（为什么分离）；调度线程池隔离；为什么对账 Job 必须"全量对"而补偿 Job 可以"分片对"。
- **知道即可**：HTTP/RPC 通讯细节；日志存储；Admin 端权限体系。

## 五、边界

- Cron 表达式语法 → 翻篇（通用知识）。
- xxl-job 二次开发（自定义路由策略等）→ 用时查官方文档。
- 与 Quartz/ElasticJob 对比 → 01/07 各一句，不展开。
