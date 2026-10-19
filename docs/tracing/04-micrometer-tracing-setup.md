---
title: "04-Micrometer-Tracing接入：Bean 从哪来"
series: "Tracing 链路追踪系列"
previous: "03-context-baggage-sampling.md"
next: "05-日志集成.md（待写）"
lab_refs:
  - "tracing-lab/docs/D8-自动配置-日志集成.md"
  - "tracing-lab/docs/D4-Observation-Tracing桥接.md"
  - "tracing-lab/docs/D5-Brave桥接层.md"
  - "tracing-lab/docs/D1-Tracer-Span生命周期.md（§二 Tracer 9 方法）"
---

# 04-Micrometer-Tracing接入：Bean 从哪来

## §0 它干了什么（例子先行）

一个 Boot 应用接 tracing 要几步？答案是：**加依赖，0 行代码**。

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
```

启动后发一个请求，看日志：

```
INFO [order-service,0af7651916cd43dd8448eb211c80319c,b7ad6b7169203331]
     └─ 应用名      └─ traceId（全链同号）              └─ spanId（本步编号）
```

- **它干了什么**：起步依赖把 Brave 拉上车，自动配置把 `Tracer` 等 Bean 装好，HTTP 请求自动建 span、自动传头、自动上报。
- **输入/输出**：输入=依赖 + 默认配置；输出=日志里带 traceId + Zipkin/SkyWalking 收到 span。
- **本篇回答**：`Tracer` Bean 从哪来？HTTP 的 span 谁建的？span 怎么到 Zipkin 的？

## §1 掌握卡（4 条）

1. **Bean 从哪来**：`BraveAutoConfiguration` 装配（`brave.Tracing` → `brave.Tracer` → `BraveTracer` 桥接），且必须先于 `MicrometerTracingAutoConfiguration`，否则回退 NOOP。
2. **业务代码只认 SPI**：`Tracer` 9 方法（建 3 / 管 3 / 查 2 / 工厂 1）；运行时全是 Brave 干活（12 个桥接类，`delegate` 纯委托）。
3. **HTTP 自动埋点**：3 个 ObservationHandler（Receiver extract / Sender inject / Default 兜底），按 Order 执行，注册条件是有 `Propagator` bean。
4. **导出与兜底**：span 结束走 `SpanHandler.end` 编排（过滤→变异→reporter 上报）；没配 reporter 有安全回退；日志里 traceId 是另一套机制（05 讲）。

## §2 装配链：3 个自动配置的分工

| 配置类 | 条件 | 作用 |
|------|------|------|
| `BraveAutoConfiguration` | classpath 有 `brave.Tracer` + `BraveTracer` | 创建 `brave.Tracing` + `brave.Tracer` + `BravePropagator` |
| `MicrometerTracingAutoConfiguration` | 存在 `Tracer` bean | 注册 3 个 ObservationHandler + `@SpanAspect` |
| `NoopTracerAutoConfiguration` | **不存在** `Tracer` bean | 返回 `Tracer.NOOP` 兜底 |

```java
// BraveAutoConfiguration（lab D8 §二）
@AutoConfiguration(before = { MicrometerTracingAutoConfiguration.class, NoopTracerAutoConfiguration.class })
@ConditionalOnClass({ brave.Tracer.class, BraveTracer.class })
public class BraveAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    brave.Tracing braveTracing(...) {
        Builder builder = Tracing.newBuilder().currentTraceContext(currentTraceContext)
                .traceId128Bit(true).supportsJoin(spanJoiningSupported)
                .propagationFactory(propagationFactory).sampler(sampler)
                .localServiceName(appName);   // ← 日志里那个应用名从这来
        spanHandlers.forEach(builder::addSpanHandler);
        ...
    }

    @Bean @ConditionalOnMissingBean
    brave.Tracer braveTracer(Tracing tracing) { ... }   // ← Brave 原生

    @Bean @ConditionalOnMissingBean(io.micrometer.tracing.Tracer.class)
    BraveTracer braveTracerBridge(...) { ... }          // ← SPI 桥接，业务代码拿到的是这个
}
```

**顺序为什么不能反？** `MicrometerTracingAutoConfiguration` 的条件是"有 `Tracer` bean"——Brave 的配置类必须先把 `BraveTracer` 建出来。反了就全员 NOOP，Handler 一个不注册，请求照跑但无 tracing（静默失效，排障时先查 Bean 在不在）。

条件注解直译：`@ConditionalOnBean(X)` = 有 X 才装，没有就跳过；`@ConditionalOnMissingBean` 反之——所以用户自己定义一个 `Tracer` Bean 就能覆盖默认的，这是 Spring Boot 的标准扩展点。

两个细节（呼应前文）：`BRAVE_BAGGAGE_MANAGER` 用无参构造（tagFields 为空）——和 03 说的"3.2.5 没有 tag-fields 概念"一致；`traceId128Bit(true)`——和 02 说的 128-bit 兼容一致。

## §3 Tracer：9 方法，四类

```java
// Tracer.java:69 — public interface Tracer extends BaggageManager
// 2.1 Span 创建（3 个）
Span nextSpan();                        // :153 — 从当前 scope 拿父，无则新 trace
Span nextSpan(@Nullable Span parent);   // :161 — 指定父
Span.Builder spanBuilder();             // :212 — 延迟构建（传播场景必需）

// 2.2 Scope 管理（3 个）
Tracer.SpanInScope withSpan(@Nullable Span span);  // :180 — 传 null = 清除当前 scope
ScopedSpan startScopedSpan(String name);           // :202 — 建+挂一步（单向操作）
CurrentTraceContext currentTraceContext();         // :224 — 拿底层 ThreadLocal 管理器

// 2.3 查询（2 个）
SpanCustomizer currentSpanCustomizer(); // :231 — 给拦截器/切面用的"只改不控"口
Span currentSpan();                     // :238 — 查当前 span（拿 traceId 就靠它）

// 2.4 Builder 工厂（1 个）
TraceContext.Builder traceContextBuilder(); // :218 — 造身份证，测试/手动传播用
```

三句解释：

- **`spanBuilder()` 为什么存在？** extract 之后才知道 parent/name——先拿 Builder 收集配置，齐了再 `start()`。不用 Builder 就得先建"半成品 span"再改，有"未完成对象外泄"的风险（02 §5 同款道理）。
- **`withSpan(null)` 是清除**：请求处理完清 scope/MDC 就靠它，不是建新东西。
- **`currentSpanCustomizer()` 给谁用？** 拦截器/切面（切面 = 不改业务代码、自动在方法前后加逻辑的机制，如 `@SpanAspect`）——`postHandle` 里拿不到业务代码的 Span 引用，但能 `tag("http.status","200")`。它只有 tag/event/name，没有 start/end——**能改不能杀**，防止下游误杀 span。

## §4 桥接层：12 个 `delegate` 包装

SPI = Micrometer 定的一套接口规范（`Tracer`/`Span`/`TraceContext`…），Brave 是其中一种实现。Brave 的 API 是 final class（final = 不能被继承）而不是接口——没法"实现接口顺便继承"，只能一个 SPI 接口包一个 Brave 对象（`lab D5 §一`）：

```
SPI (micrometer-tracing)     Bridge (bridge-brave)      Brave Native
Tracer               ←── BraveTracer               ──→ brave.Tracer
Span                 ←── BraveSpan                 ──→ brave.Span
TraceContext         ←── BraveTraceContext         ──→ brave.TraceContext
CurrentTraceContext  ←── BraveCurrentTraceContext  ──→ brave.CurrentTraceContext
...共 12 个（+ Propagator/Baggage/FinishedSpan 等）
```

核心就一个模式：持有 `delegate`，方法纯转发。如 `BraveTracer` 持有 Brave 原生 `tracer` + `BaggageManager` + `CurrentTraceContext`；连 `close()` 都是一行委托（Brave 负责 MDC 清理）。

**桥接的代价**：`BraveBaggageManager` 需要反向引用 `BraveTracer`（Baggage 的 lifecycle 绑在 Span 上，只有 Tracer 能摸到 Scope）——SPI 里 Baggage 独立于 Tracer，Brave 实现里却绑在一起，这个循环回引就是"抽象与实现错位"的税。

## §5 HTTP 自动埋点：3 个 Handler

```java
// MicrometerTracingAutoConfiguration.java:147（lab D8 §三）
@Bean @ConditionalOnMissingBean
DefaultTracingObservationHandler defaultTracingObservationHandler(Tracer tracer) { ... }

@Bean @ConditionalOnBean(Propagator.class) @ConditionalOnMissingBean
PropagatingSenderTracingObservationHandler senderHandler(...) { ... }

@Bean @ConditionalOnBean(Propagator.class) @ConditionalOnMissingBean
PropagatingReceiverTracingObservationHandler receiverHandler(...) { ... }
```

执行顺序（Order 小先行）：

```
Receiver(1000): start 时 extract() 从请求头还原上下文；stop 时清 scope
  → Sender(2000): start 建子 span + inject 写下游头；stop 上报 + 清 scope
    → Default(LOWEST-1000): 兜底——前面没建才建（如本地 DB 操作这种不传播的）
```

- **Sender/Receiver 的注册条件是"有 Propagator bean"**：没传播协议就 inject/extract 不了，这俩就是废柴——只剩 Default。
- **`tagSpan()` 对 ERROR 特殊处理**：不写普通 tag，走 `span.error()`——异常在可视化工具里红色标注就靠它。

所以"0 行代码自动有 span"的真相：Observation = Micrometer 对"一次操作"的抽象（一次 HTTP 请求、一次 DB 访问都是一次 Observation），MVC 自动为每次请求创建一个——这三个 Handler 接住它，建 span、传头、清场全包了。

## §6 导出：end() 之后去哪了

span `end()` 后进 `CompositeSpanHandler.end()` 编排（03 §7 提过，`lab D7 §二`）：

```
Cause.FINISHED? → SpanExportingPredicate 过滤 → SpanFilter 变异 → SpanReporter.report 上报
```

配了 Zipkin reporter 就去 Zipkin，配了 SkyWalking 就走 SW 的 export（07 讲三件叠加时展开）。**没配任何 reporter？** 有安全回退（Noop auto-config）——不报错，只是 span 哪也不去。

## §7 与 SkyWalking 共存：互不知

01 讲过：SW agent 走字节码，Brave 走手动埋点——两套并行，互不知对方存在。本篇补一句：自动配置只管 Brave 这套；SW agent  attach 上就有 SW 数据，不 attach 就没有——两边开关独立，01 的"三件叠加现状"就是这么来的。

## §8 边界（这里一句话翻篇）

- `BravePropagationConfigurations`（传播工厂按 produce/consume 选 W3C/B3）→ 用时查 `lab D8 §六`。
- `@SpanAspect`（注解式埋点）→ 翻篇，有需要再讲。
- 启动时间量化、配置变更风险 → 07。
- 日志里 `[app,traceId,spanId]` 的完整机制 → **下篇 05**。

## §9 Self-Test（4 题）

- Q1：`MicrometerTracingAutoConfiguration` 的条件是"有 Tracer bean"——如果顺序反了会发生什么？（从静默失效答）
- Q2：`spanBuilder()` 解决的是什么问题？不用它会有什么风险？
- Q3：Sender/Receiver Handler 的注册条件是什么？为什么？
- Q4：`currentSpanCustomizer()` 没有 start/end，为什么这是个优点？

## §10 衔接

- 上篇 03 收了理论线；本篇回答"Bean 从哪来、HTTP 谁埋点、span 去哪了"。
- 下篇 05（日志集成）：`[app,traceId,spanId]` 怎么打出来的、MDC 机制、SW logback 插件对照。
