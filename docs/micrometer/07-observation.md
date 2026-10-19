---
title: "07-原理三：Observation统一观测"
series: "Micrometer 指标系列"
previous: "06-step-push.md"
next: "08-面试收口.md（待写）"
lab_refs:
  - "micrometer-lab/docs/D3-ObservationAPI-统一观测.md（§一§二§三核心段已读）"
cross_refs:
  - "Tracing 04（三 Handler 装配——同一 API 的 tracing 侧；本篇收 metrics 侧与全局）"
---

# 07-原理三：Observation统一观测

## §0 它干了什么（例子先行）

Boot 2 时代的双注解之痛（D3 §一原话场景）：

```java
@Timed("order.create")     // Micrometer：指标
@NewSpan("order.create")   // Sleuth：链路
public void createOrder() { ... }
// 注解重复、两个"order.create"字符串各管各、采样逻辑打架
```

Observation 一个注解收编：

```java
@Observed(name = "order.create")
public void createOrder() { ... }
// Handler 链分发：Timer（Prometheus）+ Span（Zipkin）+ 日志（Kibana）三路同源产出
```

- **它干了什么**：声明"我在观测一个操作"，而不是"我要建一个 counter 和一个 span"——**输出形态交给 Handler 链**。
- **本篇回答**：生命周期长什么样？一次事件怎么广播给多路 Handler？和 Tracing 系列是什么关系？

## §1 掌握卡（4 条）

1. **3 事件 + 1 作用域**：`start`/`error`(可选)/`stop` 三事件**广播**给所有 Handler；`openScope` 把 Observation 挂 ThreadLocal（当前线程可取）——scope close 即摘除。
2. **Handler 各司其职**（D3 §二流程图）：`DefaultMeterObservationHandler`（start 存 Timer.Sample，stop 落 MeterRegistry——**02/03 的 Registry 在此被喂**）/ `TracingObservationHandler` 族（建 Span——**Tracing 04 的三 Handler 在此列队**）/ `LoggingObservationHandler`（日志）。
3. **Registry 的并发设计**（D3 §三）：Handler/谓词/约定/过滤四张表全是 **CopyOnWriteArrayList**——**写少读多**（每次事件都遍历、注册极少）：遍历无锁，注册付复制代价。
4. **借鉴 OTel 但更轻**（D3 §一尾）：`start≈spanBuilder.start`、Context≈Span+Attributes——但**不强制 exporter（输出管道组件）**，直接对接 MeterRegistry+tracing bridge（1.12.5 世界里 bridge=Tracing 系列）。

## §2 一次请求的三路产出（01 三件叠加的 API 级统一）

```
@Observed 请求进来 → start 广播
  MeterHandler：Timer 开表
  TracingHandler：Span start（cross-process 头传播=Tracing 02）
  Logging：进作用域
stop 广播
  Meter：Timer 落 Registry → Prometheus（本系列 05）
  Tracing：span end → Zipkin/SW（Tracing 系列）
  日志：MDC 摘除（Tracing 05）
```

**同一个"操作"只声明一次**——"发现/定位/定因"三件在 API 层就长在一起，01 §1-4 的分工表是运维视角，这里是代码视角。

## §3 边界（一句话翻篇）

- Handler supportsContext 运行时判定/Event 自定义事件 → D3 §四§六。
- ObservationConvention/谓词/过滤 Config API 全表 → D3 §三。
- Exemplars（指标挂 traceId）→ Tracing 07 边界已指路，本系列不展开。

## §4 Self-Test（3 题）

- Q1：双 API 时代三个具体病？@Observed 怎么一次治？
- Q2：三事件广播给谁？Timer.Sample 在哪个 Handler 手里？
- Q3：为什么四张表用 CopyOnWriteArrayList？

## §5 衔接

- 原理线全部收口（02/03/07）；下篇 08 面试收口：八篇回链 + 高频坑 + 终检。
