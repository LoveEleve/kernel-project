---
title: "05-日志集成：MDC 与 traceId"
series: "Tracing 链路追踪系列"
previous: "04-micrometer-tracing-setup.md"
next: "06-xhs自研trace全景对照.md（待写）"
lab_refs:
  - "tracing-lab/docs/D8-自动配置-日志集成.md（§五）"
source_refs:
  - "xhs-project 网关 logback-spring.xml:29,46,68（三处 SW TraceIdPatternLogbackLayout；:18 T-099 注释）"
  - "xhs-project payment logback-spring.xml:19-20,96-98（%X{traceId} + Logstash MDC keys）"
---

# 05-日志集成：MDC 与 traceId

## §0 它干了什么（例子先行）

同一条下单请求，payment 服务打出一行日志：

```
2026-10-09 10:01:02.345 [http-nio-8080-exec-3] [0af7651916cd43dd8448eb211c80319c] INFO OrderService - 创建订单成功
                                                        └─ 和 traceparent 里的是同一个 TTT
```

网关的同一条请求，日志里还多一段：`%tid` 输出的 SW 侧编号。两个号并存，各查各的：自研号查业务日志，SW 号查 SW UI。

- **它干了什么**：span 进出 scope 时，把 traceId/spanId 写进 MDC（03 说的"备注栏"）；日志格式从 MDC 取出来打印。
- **输入/输出**：输入=scope 生命周期；输出=每行日志自动带 traceId——业务代码一行不用加。
- **本篇回答**：号是谁写进 MDC 的？格式谁配的？xhs 的两套日志（标准 MDC + SW layout）各是什么？

## §1 掌握卡（3 条）

1. **标准链**：scope 打开 → `BraveScopeDecorator` 写 MDC（traceId/spanId）→ pattern 里 `%X{traceId}` 取出打印 → scope 关闭清 MDC（不清就串到下个请求）。
2. **Boot 零配置**：`LogCorrelationEnvironmentPostProcessor` 自动往 `logging.pattern.level` 追加格式；`%X{traceId:-}` 里 `:-` 是"没有就输出 `-`"，保证没 trace 的日志不断行。
3. **xhs 两套**：业务服务用 `%X{traceId}`（自研 traceId 进 MDC）+ JSON 日志收录 MDC；网关叠加 SW 的 `TraceIdPatternLogbackLayout`（`%tid` 输出 SW 侧号）；`apm-toolkit-logback` 依赖在 16 个服务 pom 里（01 点过 payment/im/analytics/content/notification 五个）。

## §2 机制：号是怎么进日志的

```java
// LogCorrelationEnvironmentPostProcessor（lab D8 §五）
public void postProcessEnvironment(...) {
    // 默认追加 %X{traceId:-}%X{spanId:-} 到 logging.pattern.level
    environment.getPropertySources().addLast(new TracingLogCorrelationPropertySource());
}
```

默认追加的格式：`%clr(%5p) %clr([${spring.application.name:},%X{traceId:-},%X{spanId:-}]){faint}`——正是 04 §0 那个 `[order-service,TTT,spanA]` 的来源。

- **谁写 MDC**：`BraveScopeDecorator`——scope 打开写，scope 关闭清。和 03 的 scope 栈是同一套生命周期：压栈即写，出栈即清。
- **`%X{}` 是什么**：Logback 的 MDC 占位符（读 `MDC.get("key")`）；`:-` 是默认值语法——线程里没 trace（如启动日志），输出 `-` 而不是空着让人猜。

## §3 清理：不清 MDC 会串号

线程池的线程是复用的：请求 A 的 traceId 留在 MDC 里没清，请求 B 落到同一个线程，日志就顶着 A 的号——**串号**。04 讲的 `withSpan(null)`（请求完清 scope）同时就是清 MDC。所以"请求处理完清 scope"不是洁癖，是正确性要求；03 的"过度链接"陷阱反过来就是"忘了清"的陷阱。

## §4 xhs 对照：两套日志并存

业务服务（payment 为例，`logback-spring.xml:19-20`）：

```xml
<property name="LOG_PATTERN"
          value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-}]  %-5level %logger{36} - %msg%n"/>
```

JSON 输出（`:96-98`）把 MDC 直接收录（`LogstashEncoder` = 把日志打成 JSON 格式，方便日志系统采集）：

```xml
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>traceId</includeMdcKeyName>
    <includeMdcKeyName>spanId</includeMdcKeyName>
```

网关叠加 SW（`logback-spring.xml:29/46/68` 三处，外加 `:18` 的 T-099 注释说明来意）：

```xml
<layout class="org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdPatternLogbackLayout">
```

- `%X{traceId}` = 自研/MDC 里的号（查业务日志用）；
- `%tid` = SW 侧的号（查 SW UI 用）。
- 记住 01 的结论：两边互不知——SW 号对不上自研号是**正常的**，不是 bug（06 会讲 xhs 为什么容忍）。

## §5 Baggage 进日志（一句回顾）

03 讲过：remote baggage 自动进 MDC——租户 `tenant-id` 不用业务代码传参，日志里 `%X{tenant-id}` 直接可搜。这是"Baggage 入 MDC，但不入 span 存储"的完整含义：**日志可搜，Zipkin 不可搜**。

## §6 边界（这里一句话翻篇）

- `LogstashEncoder` 全字段、日志采样（日志量太大采样丢）→ 07。
- SW 的 `GRPCLogClientAppender`（日志直传 SW 后端）→ 07。
- 自定义 pattern、彩色/滚动等 logback 写法 → 翻篇（通用知识，非 tracing 范畴）。

## §7 Self-Test（3 题）

- Q1：traceId 是谁、在什么时候写进 MDC 的？谁负责清？
- Q2：`%X{traceId:-}` 里 `:-` 是干什么的？没有它会怎样？
- Q3：网关日志里两个号（`%X{traceId}` 和 `%tid`）各是什么？对不上是 bug 吗？

## §8 衔接

- 使用线收口：04（Bean 与埋点）→ 05（日志）。
- 下篇 06（xhs 自研全景）：`TraceContextHolder`、6 头、`MqTraceHelper`、线程池透传——回答"为什么自研、和标准差在哪"。
