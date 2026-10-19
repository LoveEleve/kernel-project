# Micrometer 指标系列

> 基线：**Boot 3.2.5 / micrometer 1.12.5**（与 xhs-project 一致；simpleclient 0.16.0 旧版 Prometheus）
> 素材：`micrometer-lab/docs/`（D1~D8 八篇，已审校）
> 源码：`code/spring/micrometer-1.12.5` + `micrometer-tracing-1.2.5`（Observation 桥对照）
> 定位：L5-3 可观测性的**指标半边**（Tracing 系列已封卷——本系列补"三件叠加"里最后的专列）

## 一、总表（8 篇：概念 → 原理 → 使用 → 核心源码 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是指标与 Micrometer](01-what-is-metrics.md) | **概念** | What：Meter/Tag/Registry；指标四类型；与 Tracing 分工 | D1 前置+全景 |
| 02 | [原理一：MeterRegistry 与多后端路由](02-meter-registry.md) | **原理** | 模板方法；register 流程；Composite；Clock | D1 |
| 03 | [原理二：类型体系与 MeterBinder](03-types-binder.md) | **原理** | 四类型怎么用；32 Binder 全景；JVM GC 实战 | D2 |
| 04 | [使用：注解与过滤链](04-annotations-filter.md) | **使用** | @Timed/@Counted AOP；MeterFilter 三层 | D4/D6 |
| 05 | [使用：Prometheus 接入与命名](05-prometheus-naming.md) | **使用** | 新旧两套；双 Registry；scrape 链；命名 _total | D5 |
| 06 | [核心源码：StepRegistry 与 Push 模式](06-step-push.md) | **核心源码** | push vs pull；StepValue CAS；三继承链 | D7/D8 |
| 07 | [原理三：Observation 统一观测](07-observation.md) | **原理** | 生命周期 3+1；Handler 分发；与 Tracing 的桥 | D3+tracing 04 |
| 08 | [面试收口：要点回链与陷阱](08-interview-wrapup.md) | **面试** | 八篇要点回链；高频坑；终检 | D1~D8 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03
使用：04 → 05
源码：06
桥接：07
收口：08
```

## 三、前置要求

- Tracing 系列 04（ObservationHandler 同一 API 的 tracing 侧）。
- ES 系列 04（Prometheus 存储/查询不在本系列，指标只管暴露）。

## 四、掌握分级

- **必须掌握**：Meter/Tag/Registry 三件；四类型选型；MeterFilter；@Timed 参数；Prometheus 命名与 scrape；push vs pull。
- **理解思想**：模板方法族（多后端一致性）；Step 聚合（push 后端的步进统计）；Observation 与 Meter 的统一入口。
- **知道即可**：Composite 嵌套；Dropwizard 桥；forMeters（1.16 新 API）；SLO 计算细节。

## 五、边界

- Grafana 看板/PromQL → 运维翻篇（指标到 `_total` 为止）。
- Exemplars（指标挂 traceId）→ Tracing 07 边界已指路，本系列不展开。
- OpenTelemetry 指标协议 → 对照视角，不展开。
