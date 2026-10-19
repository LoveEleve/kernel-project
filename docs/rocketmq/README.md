# RocketMQ 系列

> 基线：**rocketmq-spring 2.3.0 + rocketmq-client 5.1.4**（= xhs-project）
> 素材：`rocketmq-lab/docs/`（D1~D9 + Z 八组 A~H，已审校）
> 源码：`code/spring/rocketmq-5.1.4`（tag `rocketmq-all-5.1.4`）+ `code/spring/extra/rocketmq-spring-2.3.0`
> 覆盖对照：《RocketMQ 进阶之路》16 章 + 学习计划 L5-4（消息中间件内核）

## 一、总表（8 篇）

| # | 文章 | 类型 | 核心内容 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [为什么用 MQ](01-why-mq.md) | 理论 | 削峰/解耦；代价（重复/顺序/积压/最终一致）；组件全景；xhs 十几个业务 topic | D1 |
| 02 | [生产发送：三种模型与队列选择](02-producer-send.md) | 原理 | 同步/异步/单向；队列选择；SendResult 歧义；Hook | D3 |
| 03 | [存储内核：CommitLog三件套](03-broker-storage.md) | 原理 | CommitLog/ConsumeQueue/Index；刷盘双模式；HA；恢复；清理 | D4 |
| 04 | [消费原理：长轮询与重平衡](04-consumer-rebalance.md) | 原理 | Push 长轮询；Rebalance；ProcessQueue；失败三分支；offset；Pop | D2/D5 |
| 05 | [顺序消息与广播](05-ordered-broadcast.md) | 原理 | 单队列有序；生产/消费顺序实现；缺陷；广播一句 | D7 |
| 06 | [事务消息](06-transaction.md) | 原理 | 半消息；回查；失败推演；Outbox 对比 | D6 |
| 07 | [延迟+重试/DLQ/位点](07-delay-retry-dlq.md) | 原理 | 延迟实现；重试主题；DLQ；消费位点 | D7/D5 |
| 08 | [xhs 生产对照收口](08-xhs-production.md) | 生产 | Template/Container 用法；ORDERLY/DLQ 治理；幂等；17 topics；Z 八组 | D8/D9/Z |

## 二、学习路径

```text
入门：01 → 02
内核：03 → 04
特性：05 → 06 → 07
收口：08
```

## 三、前置要求

- OpenFeign 系列（同步调用的代价，本系列对比用）。
- Tracing 06/07（`MqTraceHelper` 消息属性透传，本系列展开 carrier 细节）。
- Boot 自动配置基础（04 系列讲过条件注解，D8/D9 直接用）。

## 四、掌握分级

- **必须掌握**：组件关系（NameServer/Broker/Producer/Consumer）；Topic/Queue；三种发送；消费位点；重试/DLQ；自研 trace 在 MQ 的透传。
- **理解思想**：刷盘/复制矩阵；长轮询；重平衡；事务半消息；顺序边界。
- **知道即可**：Proxy/Controller（5.x 新架构，xhs 不用）；SQL92 过滤细节；双活。

## 五、边界

- Kafka 对比只在 08（01 仅一句"Queue≈partition"平移，L5-4 另有 Kafka 条目）。
- Seata/Outbox 只在 06 对比出现（不展开）。
- metrics/可观测只在 08 出现（ tracing 07 已讲）。
