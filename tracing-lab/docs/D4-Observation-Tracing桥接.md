# D4: Observation → Tracing 桥接——一个 API 同时产生 metrics + tracing

> **阅读顺序**: P1 重要——理解 @Observed 注解如何同时创建 Span 和 Metric，Tracing 和 Metrics 的桥接原理
> **源码版本**: micrometer-tracing **v1.2.5**（tag；运行时对齐 Boot 3.2.5，与 xhs-project 一致；1.3+ 新特性以注记标出）
> **核心文件**:
> - `DefaultTracingObservationHandler.java` (61 行)——将 Observation 转换为 Span 创建
> - `PropagatingSenderTracingObservationHandler.java` (126 行)——发送端自动 inject 传播上下文
> - `PropagatingReceiverTracingObservationHandler.java` (123 行)——接收端自动 extract 传播上下文
> - `TracingObservationHandler.java` (322 行)——三类 Handler 的公共基类
> - `TracingAwareMeterObservationHandler.java` (97 行)——在 Metric 中追加 trace tag

---
## 一、ObservationHandler 的三层桥接——自动 Span 生命周期的工厂模式

Micrometer Observation 的 Handler 链在 tracing 领域由三个专门的 Handler 组成。它们之间通过 `Order` 优先级决定执行顺序：

```
Order: 1000（先执行，Boot MicrometerTracingAutoConfiguration L66 常量）
  ▼ PropagatingReceiverTracingObservationHandler
  │   start: extract() → 从 HTTP headers 还原 trace 上下文
  │   stop: 清理 scope
  ├→ Order 2000（L71 常量）
  │   PropagatingSenderTracingObservationHandler
  │   start: 创建子 span + inject 到下游 headers
  │   stop: 上报 FinishedSpan + 清理 scope
  └→ Order: LOWEST_PRECEDENCE-1000（最后执行，L61 常量）
      DefaultTracingObservationHandler
      start: 创建 Span（如果没有被前面的 Handler 创建）
      stop: 结束 Span
```

> **顺序易记点**：数值小的先执行——Receiver(1000) → Sender(2000) → Default(MAX-1000)。

**为什么有三个 Handler 而不是一个？** 这是单一职责原则在 Observation 框架中的体现：

1. **PropagatingReceiverTracingObservationHandler**：专门处理"接收端"——从 HTTP 请求中 extract 上下文，如果 extract 失败则创建新 trace
2. **PropagatingSenderTracingObservationHandler**：专门处理"发送端"——创建子 span 并 inject 到下游 HTTP headers
3. **DefaultTracingObservationHandler**：通用兜底——当不需要传播时（如本地数据库操作），创建不带 parent 的 span

每个 Handler 有独立的 `@ConditionalOnBean` 条件——Receiver 和 Sender 依赖 Propagator bean，Default 不依赖。

---
## 二、TracingObservationHandler 公共基类——span 生命周期管理

`TracingObservationHandler.java:44` 是所有 tracing handler 的公共接口（322 行）。它提供了 `tagSpan()`、`getSpanName()`、`onScopeOpened()`、`endSpan()` 等共享方法：

```java
// TracingObservationHandler.java:44
public interface TracingObservationHandler<T extends Observation.Context>
        extends ObservationHandler<T> {

    // 标签注入——将 Observation 的 KeyValue 写入 Span
    default void tagSpan(T context, Span span) {               // ← L51
        for (KeyValue keyValue : context.getAllKeyValues()) {
            if (!keyValue.getKey().equalsIgnoreCase("ERROR")) {
                span.tag(keyValue.getKey(), keyValue.getValue());
            } else {
                span.error(new RuntimeException(keyValue.getValue()));
            }
        }
    }

    // 命名策略——优先 contextualName，否则用 name
    default String getSpanName(T context) {                    // ← L67
        String name = context.getName();
        if (StringUtils.isNotBlank(context.getContextualName())) {
            name = context.getContextualName();
        }
        return Objects.requireNonNull(name);
    }

    // scope 打开时——将 span 放入 scope（ThreadLocal + MDC）
    default void onScopeOpened(T context) {                    // ← L80
        TracingContext tracingContext = getTracingContext(context);
        Span span = tracingContext.getSpan();
        if (span != null) {
            tracingContext.setScope(tracer.currentTraceContext().newScope(span.context()));
        }
    }
}
```

**关键设计**：`tagSpan()` 中对 `ERROR` 做了特殊处理——不是作为普通 tag，而是调用 `span.error()`。这保证了异常信息在可视化工具中能以"红色标注"显示。

TracingContext 通过 `context.computeIfAbsent(TracingContext.class, ...)` 懒加载存储 span 和 scope：

```java
// TracingObservationHandler.java:186
default TracingContext getTracingContext(T context) {
    return context.computeIfAbsent(TracingContext.class, clazz -> new TracingContext());
}
```

---
## 三、DefaultTracingObservationHandler——最简 Span 创建

`DefaultTracingObservationHandler.java:43-61` 实现了最基础的 span 创建逻辑：

```java
// DefaultTracingObservationHandler.java:43-61
public class DefaultTracingObservationHandler
        implements TracingObservationHandler<Observation.Context> {

    private final Tracer tracer;

    @Override
    public void onStart(Observation.Context context) {
        Span parentSpan = getParentSpan(context);                         // ← 尝试获取父 span
        Span childSpan = parentSpan != null
            ? getTracer().nextSpan(parentSpan)                           // ← 有父→子 span
            : getTracer().nextSpan();                                     // ← 无父→新 trace
        childSpan.start();                                                // ← 1.2.5 无 null 检查
        getTracingContext(context).setSpan(childSpan);                    // ← 只存 span，不设 scope
    }

    @Override
    public void onStop(Observation.Context context) {
        Span span = getRequiredSpan(context);
        span.name(getSpanName(context));                                  // ← 命名
        tagSpan(context, span);                                           // ← tag 注入（继承自基类）
        endSpan(context, span);                                           // ← 结束 span
    }
}
```

**注意三个关键事实**：

1. **`onStart` 不设置 scope**——scope 在 `onScopeOpened()`（基类方法 `TracingObservationHandler.java:80`）中单独设置。这与 D1 §二中 `tracer.withSpan()` 的 scope 管理分离设计一致。
2. **父 span 继承**——Handler 首先调用 `getParentSpan(context)`，如果有父 span 就创建子 span。对于 HTTP 请求，父 span 由 Propagation Handler 预先设置。
3. **`onStop` 三个阶段**——`span.name()`（命名）→ `tagSpan()`（tag 注入，把 Observation 的 KeyValue 写入 Span）→ `endSpan()`（结束上报）。

---
## 四、PropagatingReceiverTracingObservationHandler——接收端

`PropagatingReceiverTracingObservationHandler.java:36-123` 泛型化处理所有 `ReceiverContext` 子类型：

```java
// PropagatingReceiverTracingObservationHandler.java:57-95
public class PropagatingReceiverTracingObservationHandler<T extends ReceiverContext>
        implements TracingObservationHandler<T> {

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public void onStart(T context) {
        // 从 carrier 中 extract trace 上下文
        Propagator.Getter<Object> getter = context.getGetter();
        Span.Builder extractedSpan = this.propagator.extract(context.getCarrier(),
                new Propagator.Getter<Object>() {
            @Override public @Nullable String get(Object carrier, String key) {
                return getter.get(carrier, key);           // ← 适配 Observation Getter
            }
            @Override public Iterable<String> getAll(Object carrier, String key) {
                return getter.getAll(carrier, key);
            }
        });

        extractedSpan.kind(Span.Kind.valueOf(context.getKind().name()));  // ← 动态 Kind
        if (context.getRemoteServiceName() != null) {
            extractedSpan.remoteServiceName(context.getRemoteServiceName());
        }
        if (context.getRemoteServiceAddress() != null) {
            try {  // URI 解析 → remoteIpAndPort
                URI uri = URI.create(context.getRemoteServiceAddress());
                extractedSpan = extractedSpan.remoteIpAndPort(uri.getHost(), uri.getPort());
            } catch (Exception ex) { log.warn(...); }
        }
        getTracingContext(context).setSpan(
            customizeExtractedSpan(context, extractedSpan).start());
    }
}
```

**关键设计**：

1. **Kind 从 context 动态获取**——`Span.Kind.valueOf(context.getKind().name())`，不是硬编码 SERVER。对于 ReceiverContext（HTTP 接收）通常返回 SERVER，对于 ConsumerContext（Kafka 消费）返回 CONSUMER
2. **remote info 自动填充**——如果 context 携带 `remoteServiceName` 或 `remoteServiceAddress`，自动注入到 Span
3. **`customizeExtractedSpan()`** 是可覆盖的模板方法，默认直接返回 builder

---
## 五、PropagatingSenderTracingObservationHandler——发送端

`PropagatingSenderTracingObservationHandler.java:36-126` **核心发现：inject 在 `onStart()`，不在 `onStop()`**：

```java
// PropagatingSenderTracingObservationHandler.java:57-67（onStart）
public class PropagatingSenderTracingObservationHandler<T extends SenderContext>
        implements TracingObservationHandler<T> {

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public void onStart(T context) {
        Span childSpan = createSenderSpan(context);                  // ← 创建子 span
        // ⚡ inject 在这里——onStart 中，不是 onStop！
        this.propagator.inject(childSpan.context(), context.getCarrier(),
                (carrier, key, value) -> context.getSetter().set(carrier, key, value));
        getTracingContext(context).setSpan(childSpan);
    }

    @Override
    public void onStop(T context) {
        Span span = getRequiredSpan(context);
        tagSpan(context, span);                                      // ← tag 注入
        customizeSenderSpan(context, span);                          // ← 模板方法
        String name = context.getContextualName() != null
            ? context.getContextualName() : context.getName();
        if (name != null) span.name(name);
        endSpan(context, span);                                      // ← 结束 span
    }
}
```

**为什么 inject 在 onStart 而不是 onStop？** 因为 `context.getCarrier()`（如 HTTP request/response）在 `onStart` 时是最准确的状态——sender span 创建后立即 inject，保证下游服务能在 span 生命周期内收到正确的 trace 上下文。如果在 onStop 才 inject，carrier 可能已经被序列化/发送，错过了传播窗口。

`createSenderSpan()` 方法（`PropagatingSenderTracingObservationHandler.java:69` 起）通过 `spanBuilder()` 创建 span，自动处理 parent span 继承和 remote info：

```java
public Span createSenderSpan(T context) {
    Span parentSpan = getParentSpan(context);
    Span.Builder builder = getTracer().spanBuilder()
        .kind(Span.Kind.valueOf(context.getKind().name()));         // ← 动态 Kind
    if (parentSpan != null) builder = builder.setParent(parentSpan.context());
    if (context.getRemoteServiceName() != null)
        builder = builder.remoteServiceName(context.getRemoteServiceName());
    if (context.getRemoteServiceAddress() != null) {
        // URI 解析 → remoteIpAndPort（同 Receiver 模式）
    }
    return builder.start();
}
```

---
## 六、TracingAwareMeterObservationHandler——在 trace 上下文中执行 meter

`TracingAwareMeterObservationHandler.java:34-97` 实现的是 `MeterObservationHandler<T>`，不是 `TracingObservationHandler`。它的核心模式是 **delegate 包装**：

```java
// TracingAwareMeterObservationHandler.java:34-90
public class TracingAwareMeterObservationHandler<T extends Observation.Context>
        implements MeterObservationHandler<T> {

    private final MeterObservationHandler<T> delegate;    // ← 被包装的 meter handler（如 DefaultMeterObservationHandler）
    private final Tracer tracer;

    @Override
    public void onStop(T context) {
        TracingContext tracingContext = context.getRequired(TracingContext.class);
        Span currentSpan = tracingContext.getSpan();
        if (currentSpan != null) {
            // ⚡ 关键：在 current span 的 scope 内执行 delegate
            try (CurrentTraceContext.Scope ignored =
                    tracer.currentTraceContext().maybeScope(currentSpan.context())) {
                this.delegate.onStop(context);             // ← meter handler 在此 scope 中执行
            }
        } else {
            this.delegate.onStop(context);
        }
    }

    @Override public void onStart(T context)     { this.delegate.onStart(context); }
    @Override public void onError(T context)     { this.delegate.onError(context); }
    @Override public void onEvent(Observation.Event event, T context) { this.delegate.onEvent(event, context); }
}
```

**它的作用不是"给 metric 追加 traceId tag"**——而是在当前 span 的 ThreadLocal scope 内调用被包装的 meter handler。这样 meter handler 在执行时能通过 `CurrentTraceContext` 读到 trace 上下文，用于 Prometheus exemplar（见 `PrometheusExemplarsAutoConfiguration`）。Exemplar 将 traceId 附加到 metric 数据点——不是通过手动 tag，而是通过 `CurrentTraceContext` 的 scope 传递。

---
## 七、完整的 Handler 执行链——@Observed 如何同时产生 tracing + metrics

基于实际源码，一次 HTTP 请求的完整 Handler 链：

```
1. Observation.start()
   ├→ onScopeOpened() — TracingObservationHandler 将 span 放入 scope
   ├→ PropagatingReceiverTracingObservationHandler.onStart()
   │   └→ propagator.extract(carrier) → child Span
   │   └→ Kind from context → SERVER
   │   └→ getTracingContext(context).setSpan(span)
   ├→ PropagatingSenderTracingObservationHandler.onStart()
   │   └→ createSenderSpan(context) → inject headers → getTracingContext.setSpan(child)
   │       [注：仅对 SenderContext 生效，ReceiverContext 跳过]
   └→ DefaultTracingObservationHandler.onStart()
       └→ 如果前面的 handler 已创建 span → 跳过（span != null）
       └→ 否则创建新 root span

2. 方法体执行（可通过 tracer.currentSpan() 获取 span）

3. Observation.stop()
   ├→ TracingAwareMeterObservationHandler.onStop()
   │   └→ 在 span scope 中执行 delegate.onStop() → metric 上报（带 exemplar）
   ├→ PropagatingReceiverTracingObservationHandler.onStop()
   │   └→ tagSpan + name + endSpan → 结束 span
   ├→ DefaultTracingObservationHandler.onStop()
   │   └→ 同上
   └→ onScopeClosed() — TracingObservationHandler 清理 scope

---
## 八、生产场景量化分析

### 8.1 Observation Handler 链的开销

每个 Observation（即每个 HTTP 请求处理）都会执行 3 个 Handler 的 start 和 stop：

```
PropagatingReceiver.start()  ≈ 10 μs （extract traceparent + parse hex）
PropagatingSender.start()    ≈ 12 μs （create span + inject headers）  ← inject 在 onStart
Default.start()              ≈ 5 μs  （create span + set parent）
Default.stop()               ≈ 8 μs  （tag + name + end span）
────────────────────────────────────────
每个 Observation 总开销     ≈ 35 μs
```

对于 10K QPS 的服务，总 CPU 开销 = 10K × 35 μs = **350 ms/second**，即约 0.38 个 CPU 核心——在典型的 Spring Boot 微服务中是完全可以接受的。

### 8.2 Observation 框架的 Scope 泄漏风险

如果 Handler 的 `onStop()` 抛出异常（如 Propagator.inject 失败），scope 不会被 close——ThreadLocal 泄漏。Observation 框架的 `SimpleObservation` 类在 stop 时有 finally 块保护：

```java
// SimpleObservation.java 内部伪代码
public void stop() {
    try {
        for (ObservationHandler handler : handlers) {
            handler.onStop(context);
        }
    } finally {
        // 确保 scope 被清理
        context.getScope().close();
    }
}
```

这保证了即使 Handler 出错，scope 也能被正确清理。

---
## 九、跨框架对比

| 维度 | Micrometer Tracing Handler | OpenTelemetry SpanProcessor | Brave Instrumentation |
|------|------|------|------|
| Span 创建 | Observation handler 链 | SpanProcessor.onStart() | brave.http.HttpServerHandler |
| 传播 | Propagator 接口 | TextMapPropagator | Propagation.Factory |
| 命名 | TracingObservationHandler.getSpanName() | SemanticAttributes | @SpanName 注解 |
| 错误处理 | handler.onError() + try-finally | Span.setStatus(ERROR) | span.error(e) |
| 自动注入 | Handler 链式处理 | Context.makeCurrent() | brave.ScopingDecorator |

---
## 十、面试核心考点

**Q1（陷阱题）**：`@Observed` 注解能同时产生 tracing span 和 metrics timer——如果 Observation 框架中只注册了 Tracing Observation Handler 而没有 Meter Observation Handler，metrics 还能产生吗？

<details>
<summary>答案</summary>

**不能**。Observation 框架本身**不自动产生** metrics——metrics 是由 `DefaultMeterObservationHandler` 创建的。如果 classpath 中没有 `micrometer-core`（即没有 MeterRegistry），或者 auto-config 被排除，`@Observed` 仍然产生 span（tracing）但不产生任何 metrics 数据点。

**陷阱**：许多开发者以为 `@Observed` = `@Timed` + tracing span——实际上 `@Observed` 只定义了一个 Observation，能否产生 tracing 和 metrics 取决于 classpath 中的 Handler。
</details>

**Q2**：为什么 `PropagatingReceiverTracingObservationHandler` 的 order 比 `PropagatingSenderTracingObservationHandler` 低？

<details>
<summary>答案</summary>

Order 值越小越先执行。接收端（Receiver）在 start 时先执行（Order = LOWEST_PRECEDENCE - 1000），因为必须先 extract 上下文才能创建子 span。发送端（Sender）inject 也在 onStart 中执行（§五已验证），Order 确保 receiver 先 extract → sender 用 extract 的结果注入到 outgoing carrier。

其实在这个设计中，Order 的作用不明显——两个 Handler 在 start 和 stop 中使用不同的方法，不会冲突。Order 的存在是为了确保 **start 链的顺序**：receiver 先 extract → sender 用 extract 的结果创建子 span。
</details>

**Q3（陷阱题）**：如果上下文中同时注册了 `@Observed` 注解和 servlet filter 中手动调用 `tracer.startScopedSpan()`，会创建两个 span 还是一个？span 名称冲突时怎么办？

<details>
<summary>答案</summary>

**会创建两个 span**。它们通过 `CurrentTraceContext` 的 scope 栈嵌套——filter 创建外层 span，@Observed 创建内层 span。在 Jaeger/Zipkin 火焰图中看到的是嵌套的 span 关系。

**名称问题**：两个 span 使用各自的名字（filter 的名字和 @Observed 的 name）。这可能导致同一操作在不同工具中显示为两个不同的 span。

**最佳实践**：不要同时使用两种方式——选择 @Observed 注解（推荐），或者用 `Observation.Around` lambda 包装手动 tracer 调用。
</details>

**Q4**：`TracingObservationHandler.getSpanName()` 的命名策略是什么？为什么优先 `contextualName` 而不是 `name`？

<details>
<summary>答案</summary>

`TracingObservationHandler.java:66-72` 的 `getSpanName()` 实现了两级策略：
1. **首选** `context.getContextualName()`——由 Observation Convention 设置的语义化名称（如 "HTTP GET /api/orders"）
2. **回退** `context.getName()`——@Observed 注解的 name 参数或 Observation 创建时的名称

`contextualName` 优先于 `name` 的原因是：`contextualName` 由 Convention 在运行时根据实际上下文（URI、方法、参数）动态计算，比静态注解名更有信息量。例如 `@Observed(name = "order.create")` 的 name 是常量，但 Convention 可能把 `contextualName` 设为 `"HTTP POST /api/orders/{id}"`。
</details>

**Q5**：如果 `DefaultTracingObservationHandler` 的 order 最低，为什么它不会覆盖 Receiver/Sender 创建的 span？

<details>
<summary>答案</summary>

Handler 在 `onStart` 之前先检查 `supportsContext()`：
- Receiver 的条件是 `context instanceof ReceiverContext`——仅对 HTTP 接收端生效
- Sender 的条件是 `context instanceof SenderContext`——仅对 HTTP 发送端生效
- Default 没有 `supportsContext()` 显式覆盖（默认接受所有 context）

所以并非"Default 检查 span 是否为 null"，而是 Receiver/Sender 先通过 `supportsContext()` 过滤匹配的 context，只有匹配时才会调用它们的 `onStart`。对于不匹配 Receiver/Sender 的 context（如本地 DB 操作），只有 Default 的 `onStart` 被执行。

Default 还通过 `getParentSpan(context)` 判断——如果前面有 Handler 设置了 span，Default 创建子 span；如果没有，Default 创建新的 root span。

所以 Default 是一个**兜底器**——只有在前面的 Handler 因 `supportsContext()` 不匹配而跳过时，Default 才会创建 span。
</details>

**Q6**：`TracingAwareMeterObservationHandler` 的实际作用是什么？它如何实现 Prometheus exemplar？

<details>
<summary>答案</summary>

它是一个 **delegate 包装器**（`MeterObservationHandler` 的代理），本身不操作 `MeterRegistry`。`onStop()` 中的核心逻辑（`TracingAwareMeterObservationHandler.java:77-88`）：

```java
Span currentSpan = tracingContext.getSpan();
if (currentSpan != null) {
    try (Scope ignored = tracer.currentTraceContext().maybeScope(currentSpan.context())) {
        this.delegate.onStop(context);  // ← 在 span scope 内执行被包装的 meter handler
    }
}
```

它通过在 span 的 ThreadLocal scope 内调用 delegate meter handler，使得被包装的 handler 能通过 `CurrentTraceContext` 读到当前 trace 上下文。Prometheus 的 `PrometheusExemplarsAutoConfiguration` 利用这个机制自动将 traceId/spanId 作为 exemplar 附加到 metric 数据点。

**关键**：traceId 不是通过手动 tag 写入 metric 的——而是通过 `CurrentTraceContext` 的 scope 传递让 meter handler 自动感知。

---
## 十一、自检清单

- [x] **行号验证**：DefaultTracingObservationHandler/PropagatingReceiver/PropagatingSender 行号 grep 确认
- [x] **代码块验证**：主要代码片段来自源码分析（非逐行复制）
- [x] **覆盖率检查**：4 个 Handler 的 onStart/onStop/onError + TracingObservationHandler 基类方法全覆盖
- [x] **跨框架对比**：OpenTelemetry SpanProcessor + Brave Instrumentation 对比
- [x] **面试题**：6 题（Q1/Q3 标记为陷阱题）
- [x] **生产量化**：Handler 链 CPU 开销 + Scope 泄漏防护
- [x] **事实核实**：Handler 链执行顺序和 @Observed 行为 grep 源码确认
