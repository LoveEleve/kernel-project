---
title: "01-什么是指标与Micrometer"
series: "Micrometer 指标系列"
previous: "README.md"
next: "02-MeterRegistry.md（待写）"
lab_refs:
  - "micrometer-lab/docs/D1-MeterRegistry-多后端路由.md（§一已读）"
cross_refs:
  - "Tracing 07（三件叠加：指标是唯一还没专列的那件）"
---

# 01-什么是指标与Micrometer

## §0 它干了什么（例子先行）

数一下"创建订单"接口被调了多少次，还想知道按区域拆开：

```java
registry.counter("orders.created",
        Tags.of("region", "us-east", "api", "v2", "method", "GET")).increment();
// Prometheus 抓取后变成：
// orders_created_total{region="us-east", api="v2", method="GET"} 42
```

- **它干了什么**：业务代码只管"打一个带标签的数"；后面选存储、选格式、选暴露方式全是框架的事。
- **输入/输出**：输入=指标名+标签+增量；输出=各监控后端里可聚合查询的时间序列。
- **本篇回答**：指标世界的核心词汇？四类型怎么选？Micrometer 和已封卷的 Tracing 什么关系？

## §1 掌握卡（4 条）

1. **三件核心词汇**：**Meter**（一个被计量的东西）/ **Tag**（维度键值——`region=us-east`，**一等公民**）/ **Registry**（Meter 的工厂+容器，按后端实现）。
2. **四类型**：**Counter**（只增不减的计数）/ **Gauge**（可增可减的瞬时值——连接池活跃数）/ **Timer**（耗时+次数）/ **DistributionSummary**（分布摘要——订单金额分布）——选型看"数据形状"，03 细讲。
3. **为什么是 Micrometer 不是 Dropwizard**（D1 §一两缺陷）：老框架**没有原生 Tag**（维度硬编码进名字，Prometheus 无法 `sum by(region)`）；**Registry 与 Reporter 紧耦合**（pull/push 双模式难共存）——Micrometer=Tag 一等公民 + **每后端独立 Registry 的门面**。
4. **与 Tracing 的分工**（三件叠加补全）：指标答"错没错、快不快"（聚合看板/告警）；trace 答"慢在哪一跳"（01 系列）；日志答"当时打了什么"（05 系列）——**发现→定位→定因**的老故事，本系列补"发现"的专列。

## §2 一个 Counter 的完整旅程（概念链预览）

```
业务 increment() → Registry 里的 Counter 对象（Tag 拼成唯一 Id）
  → 后端各自存：Prometheus 暴露 /actuator/prometheus（pull）
               JMX MBean（pull；JMX 里可被管理组件查询的对象）/ Datadog 定时推送（push）
  → PromQL（Prometheus 查询语言）/Grafana 查询聚合
```

**pull vs push 预告**（06 深讲）：Prometheus 是 pull（等它来抓）；Datadog 类是 push（我们定期推）——同一份计数，两种搬运节奏，Registry 家族为此分两条继承线。

## §3 为什么需要"门面"（D1 的核心洞察一句话）

同一个 `registry.counter(...)` 要同时服务 Prometheus+JMX+日志聚合——**门面接一次调用，按后端分发成 N 个真实 Meter**——这是 Micrometer 架构的第一原理（02 拆模板方法）。

## §4 边界（一句话翻篇）

- Registry 内部模板方法 → 02。
- Binder/类型细节 → 03。
- Grafana/PromQL → 边界外（README 已声明）。

## §5 Self-Test（3 题）

- Q1：Tag 一等公民解决了 Dropwizard 的什么病？
- Q2：四类型各举一个 xhs 场景（订单计数/池活跃数/接口耗时/金额分布）。
- Q3：指标/trace/日志三件各回答什么问题？

## §6 衔接

- 概念层收口；下篇 02 原理：MeterRegistry 怎么做"接一次、发 N 家"（模板方法与多后端路由）。
