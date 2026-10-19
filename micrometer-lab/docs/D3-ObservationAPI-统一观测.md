# D3: Observation API——统一 metrics + tracing + logging

> **阅读顺序**: P0 必备——Micrometer 1.10+ 的核心抽象，Spring Boot 3 的默认观测模式
> **源码版本**: micrometer-observation + micrometer-core **v1.12.5**（tag；运行时对齐 Boot 3.2.5；1.13+ 新特性以注记标出）
> **核心文件**:
> - `Observation.java` (1490 行)——核心接口 + Context 内嵌类
> - `ObservationRegistry.java` (202 行)——Handler 注册中心（CopyOnWriteArrayList 线程安全）
> - `ObservationHandler.java`——Handler 接口（分发在 SimpleObservation：单列表 + 运行时 supportsContext）
> - `DefaultMeterObservationHandler.java` (93 行)——metrics bridge
> - `ObservationConvention.java`——命名与 Tag 的约定管理

---

## 一、为什么需要 Observation API——"两套 API"的终结

Spring Boot 2 时代，metrics 和 tracing 是两套独立 API：

```java
@Timed("order.create")          // ← Micrometer: metrics
@NewSpan("order.create")        // ← Sleuth: tracing
public void createOrder() { ... }
```

后果：注解重复、Tag 不一致（`order.create` 在 Prometheus 和 Zipkin 中是**两个不同的字符串**）、采样逻辑冲突。Observation 用一个抽象统一三者：

```java
@Observed(name = "order.create")   // ← 一个注解，同时生成 metrics + tracing + logging
public void createOrder() { ... }
```

**核心思想**：声明"我在观测一个操作"，而不是声明"我要创建一个 counter 和一个 span"。`ObservationHandler` 链负责将观测事件转化为具体的后端输出。

**与 OpenTelemetry 的关系**：Observation API 的设计明确借鉴了 OTel 的 Tracer/Meter API——`Observation.start()` ≈ `Tracer.spanBuilder().startSpan()`，`ObservationContext` ≈ `Span` + `Attributes`。但 Micrometer 的 Observation 更轻量——不强制 exporter 管道，直接对接 Micrometer 的 MeterRegistry + tracing bridge。

---

## 二、Observation 的生命周期——3 个事件 + 1 个作用域

```java
public interface Observation {
    Observation start();              // ← 事件 1: 观测开始
    Context getContext();             // ← 获取上下文（name, KeyValues, 元数据）
    Observation error(Throwable e);   // ← 事件 2: 记录错误（可选）
    void stop();                      // ← 事件 3: 观测结束
    Scope openScope();                // ← 创建 ThreadLocal 作用域
}
```

**一个完整的 Observation 生命周期**：

```
observation.start()
    → 所有 ObservationHandler.onStart(context) 被调用
    ├── DefaultMeterObservationHandler: 创建 Timer.Sample + LongTaskTimer.Sample
    ├── TracingObservationHandler: 创建 Span
    └── LoggingObservationHandler: 打印日志

observation.openScope()
    → ThreadLocal.set(observation)   ← 当前线程可获取 getCurrentObservation()

业务逻辑执行...

observation.error(e) [optional]
    → 所有 ObservationHandler.onError(context) 被调用

observation.stop()
    → 所有 ObservationHandler.onStop(context) 被调用
    ├── DefaultMeterObservationHandler: sample.stop(Timer) → 记录到 MeterRegistry
    └── TracingObservationHandler: end span → 发送到 Zipkin/Jaeger

scope.close()
    → ThreadLocal.remove()
```

**关键**：`start/stop/error` 三个事件**广播**到所有注册的 Handler。一个 Observation 同时产生三种输出——指标（Prometheus 可见）+ 追踪（Zipkin 可见）+ 日志（Kibana 可见）。

---

## 三、ObservationRegistry——线程安全 + 完整 Config API

### 3.1 Handler 注册的线程安全设计

```java
// ObservationRegistry.java——Config 内部类（L97-L103）
private final List<ObservationHandler<?>> observationHandlers = new CopyOnWriteArrayList<>();
private final List<ObservationPredicate> observationPredicates = new CopyOnWriteArrayList<>();
private final List<ObservationConvention<?>> observationConventions = new CopyOnWriteArrayList<>();
private final List<ObservationFilter> observationFilters = new CopyOnWriteArrayList<>();
```

`CopyOnWriteArrayList` 是关键——Handler 注册（写）频率低，遍历（读）频率高（每次 start/stop/error）。COW 保证遍历不需要锁，注册时复制整个数组——适合"写少读多"的场景。

### 3.2 ObservationConfig 完整 API

```java
registry.observationConfig()
    .observationHandler(new DefaultMeterObservationHandler(meterRegistry))  // ← 注册 Handler
    .observationConvention(new GlobalObservationConvention<>() { ... })     // ← 全局命名约定
    .observationPredicate((name, context) -> !name.startsWith("actuator"))  // ← 决定是否开始观测
    .observationFilter(context -> {                                         // ← 修改 Context
        return context.put(KeyValue.of("tenant", "prod"));
    });
```

执行顺序：predicate（开始前）→ filter（修改 Context）→ handler.onStart（记录指标/span）。

---

## 四、Handler 分发——单列表 + 运行时 supportsContext（1.12.5）

**1.12.5 没有 `ObservationHandlerComposite`**——不存在"Meter/Tracing/Logging 三个 Composite 分派"的设计。真实机制在 `SimpleObservation`：

```java
// SimpleObservation.java L94-L97（start 时）
Collection<ObservationHandler<?>> handlers = registry.observationConfig().getObservationHandlers();
for (ObservationHandler<?> handler : handlers) {
    if (handler.supportsContext(context)) {   // ← 运行时逐个检查
        handler.onStart(context);
    }
}
// L268（stop 时反向遍历）
this.handlers.descendingIterator().forEachRemaining(handler -> handler.onStop(context));
```

所有 Handler 在**同一个 CopyOnWriteArrayList**（`Config.observationHandlers`，L97）——`supportsContext` 是**每次事件发生时**的运行时过滤，不是"注册时分派"。Handler 之间的隔离靠各自 `supportsContext` 的类型判断（如 `MeterObservationHandler` 只接 `Observation.Context`）。

## 五、DefaultMeterObservationHandler——Timer.Sample 的存储与恢复

```java
// DefaultMeterObservationHandler.java（93 行）L50-L71
@Override
public void onStart(Observation.Context context) {
    LongTaskTimer.Sample longTaskSample = LongTaskTimer.builder(context.getName() + ".active")
        .tags(createTags(context))
        .register(meterRegistry)
        .start();                                     // ← LTT 总是创建（1.12.5 无开关）
    context.put(LongTaskTimer.Sample.class, longTaskSample);

    Timer.Sample sample = Timer.start(meterRegistry); // ← 记录开始时间
    context.put(Timer.Sample.class, sample);          // ← 存入 Context（key-value store）
}

@Override
public void onStop(Observation.Context context) {
    List<Tag> tags = createTags(context);             // ← 低基数 KeyValue → Tag
    tags.add(Tag.of("error", getErrorValue(context)));// ← error tag：异常类名 / "none"
    Timer.Sample sample = context.getRequired(Timer.Sample.class);
    sample.stop(Timer.builder(context.getName()).tags(tags).register(this.meterRegistry));
    context.getRequired(LongTaskTimer.Sample.class).stop();
}

@Override
public void onEvent(Observation.Event event, Observation.Context context) {
    Counter.builder(context.getName() + "." + event.getName())   // ← 自定义事件 → Counter
        .tags(createTags(context))
        .register(meterRegistry)
        .increment();
}
```

**为什么 LongTaskTimer 在 onStart 创建而不是 onStop？** LongTaskTimer 跟踪正在运行中的长任务（如正在处理的 HTTP 请求）——它的 `activeTasks` 计数需要在任务运行时可见，不能在结束后才创建。Gauge 式指标（瞬时值）和 Counter 式指标（累计值）的创建时机完全不同。

**1.12.5 细节**：LTT 指标名是 `context.getName() + ".active"`，**总是创建**（1.13+ 才有 `shouldCreateLongTaskTimer` 开关）；`onEvent` 把自定义事件转为 Counter（`<name>.<event>`）——这是 Observation Event → metrics 的落地。

## 六、Observation.Event——自定义事件机制

除 `start/stop/error` 3 个固定事件外，Observation 支持自定义事件：

```java
Observation observation = Observation.start("order.create", registry);
try (Scope scope = observation.openScope()) {
    doValidation();
    observation.event(Event.of("validated"));          // ← 自定义事件

    doPayment();
    observation.event(Event.of("payment.processed",    // ← 带自定义 KeyValue
        "amount", String.valueOf(order.getAmount())));

} catch (Exception e) {
    observation.error(e);
} finally {
    observation.stop();
}
```

自定义事件通过 `ObservationHandler.onEvent(context)` 处理后端——Tracing Handler 可以在 span 上添加 annotation，Logging Handler 打印日志。这给了框架作者强大的**细粒度观测能力**——不局限于"开始/结束/出错"。

---

## 七、Context 的层次结构与父-子嵌套

### 7.1 Context 类型体系（1.12.5 真实结构）

```
Observation.Context（基类——KeyValue store + parent + error）
    ├── micrometer-observation 内置 transport 族（observation/transport/ 包）:
    │     ReceiverContext / SenderContext / ResponseContext
    │     RequestReplyReceiverContext / RequestReplySenderContext
    └── 各集成模块自定义子类（HTTP/Kafka/gRPC…按需定义）
```

**版本/边界注意**：`ServerHttpObservationContext`/`ClientHttpObservationContext`/`KafkaReceiverContext` 等"框架级 Context"**不在 micrometer-observation**——HTTP 观测上下文在 Spring Framework（`org.springframework.http.server.observation.ServerHttpObservationContext`）与各 binder（OkHttp/Jersey/Apache）中定义；micrometer-observation 只提供通用的 Receiver/Sender 抽象。每个 Context 子类型携带领域特定元数据——Convention 通过泛型 `<T extends Context>` 绑定到具体类型。

### 7.2 父-子嵌套 Observation

一个 HTTP 请求可能包含多个子操作：

```java
Observation parent = Observation.createNotStarted("http.request", registry);
parent.start();
try (Scope parentScope = parent.openScope()) {
    // 子 Observation 自动关联父 Observation
    Observation child = Observation.createNotStarted("db.query", registry)
        .parentObservation(parent)          // ← 显式设置父 Observation
        .start();
    try (Scope childScope = child.openScope()) {
        jdbc.query("SELECT ...");
    } finally {
        child.stop();
    }
} finally {
    parent.stop();
}
```

**Tracing 的含义**：父 Observation 的 Span 成为子 Observation 的 Span 的 parent——形成完整的调用链。Context 内部持有 `parent` 引用——在子 observation 创建时传递 trace context。

---

## 八、ObservationConvention + GlobalObservationConvention——命名约定

### 8.1 Convention 接口

```java
public interface ObservationConvention<T extends Observation.Context> {
    String getName();                                          // ← 观测名称
    String getContextualName(T context);                       // ← 动态名称（如 "GET /api/users"）
    KeyValues getLowCardinalityKeyValues(T context);           // ← 低基数 KeyValue（< 100 种值）
    KeyValues getHighCardinalityKeyValues(T context);          // ← 高基数 KeyValue
}
```

**设计意图**：一个 Observation 的 name 和 KeyValues 不应该散落在调用代码中——应该集中在一个 Convention 中。这样同一个"HTTP 请求"的 Observation 在不同服务中有一致的命名和 tag 结构。

### 8.2 DefaultObservationConvention 体系（1.12.5 真实清单）

micrometer-core 提供的开箱即用 Convention（按集成）：

| Convention | 所在集成 | 适用 |
|------|------|------|
| `DefaultHttpClientObservationConvention`（java11 源码集） | `binder/jdk` | JDK HttpClient |
| `DefaultOkHttpObservationConvention` | `binder/okhttp3` | OkHttp |
| `DefaultApacheHttpClientObservationConvention` | `binder/httpcomponents/hc5` | Apache HttpClient 5 |
| `DefaultJerseyObservationConvention` | `binder/jersey/server` | Jersey Server |

> **注意**：`DefaultHttpServerObservationConvention`/`DefaultKafkaConsumerObservationConvention` 等**不在 micrometer-core**——HTTP Server 的约定在 Spring Framework（`DefaultServerRequestObservationConvention`），Kafka 的在 Spring Kafka 集成中。

### 8.3 命名优先级

```
ObservationConvention.getName()          ← 类型级最高
    ↓ fallback
GlobalObservationConvention.getName()    ← 全局兜底
    ↓ fallback
创建时指定的 name                        ← 代码级最低
```

---

## 九、KeyValue vs Tag——为什么 Observation 不用 Meter.Tag

Observation 使用 `KeyValue` 而非 `Meter.Tag`。**设计动机**：

- `Meter.Tag` 是 Meter 层的概念——最终会出现在 Prometheus label 中，受 Prometheus 命名规则限制（`[a-zA-Z_:][a-zA-Z0-9_:]*`）
- `KeyValue` 是 Observation 层的概念——更抽象，Key 可以是任意字符串（如 `http.method`、`error.type`），不受后端限制
- 当 Observation → Meter 转换时（`DefaultMeterObservationHandler`），KeyValue 被转换为 `Meter.Tag`——转换在 bridge 层发生，不是 Observation 层

**ObservationDocumentation**——类型安全的约定文档：

```java
enum MyObservation implements ObservationDocumentation {
    ORDER_CREATE {
        @Override public String getName() { return "order.create"; }
        @Override public KeyValues getLowCardinalityKeyValues() {
            return KeyValues.of(KeyValue.of("outcome", "success"));
        }
    }
}
```

编译时强制所有 Observation 有明确 name 和 KeyValues——不会出现"改名了但忘了改 Convention"的人为失误。

---

## 十、Scope 与 ThreadLocal——跨线程传递

```java
Observation observation = Observation.start("order.process", registry);
try (Scope scope = observation.openScope()) {
    Observation current = registry.getCurrentObservation();  // ← ThreadLocal.get()
    doWork();
}  // scope.close() → ThreadLocal.remove()
```

`openScope()` 使用 `ThreadLocal`——线程本地。新线程中调用 `registry.getCurrentObservation()` 返回 `null`。Micrometer Observation 本身不解决跨线程传播——这由两个上层机制处理：
1. **Micrometer Tracing** 的 `ContextPropagation`——包装 `Observation` 的 Context 到 `ThreadLocal` 写入机制
2. **Reactor** 的 `Hooks.enableAutomaticContextPropagation()`——自动在 Reactive pipeline 中传递 Context

---

## 十一、编程式 API——不只是 @Observed 注解

```java
// 基础模式
Observation observation = Observation.start("http.request", registry);
try (Scope scope = observation.openScope()) {
    doWork();
} catch (Exception e) {
    observation.error(e);
    throw e;
} finally {
    observation.stop();
}

// 便捷 lambda 包装
observation.observe(() -> doWork());                          // ← Scope + error + stop 自动
observation.observeChecked(() -> doCheckedWork());            // ← 支持 checked exception

// 创建但不启动（延迟启动）
Observation observation = Observation.createNotStarted("order.create", registry)
    .contextualName("order #" + orderId)                     // ← 动态名称
    .lowCardinalityKeyValue("outcome", "success")            // ← 静态 KeyValue
    .highCardinalityKeyValue("orderId", orderId)             // ← 高基数 KeyValue
    .start();                                                 // ← 显式启动
```

`observe()` lambda 包装内部是 `start() → openScope() → try-finally { stop() }` 的完整生命周期——推荐在简单场景使用。

---

## 十二、三层过滤对比——Predicate vs Filter vs MeterFilter

| 过滤层 | 作用于 | 执行时机 | 用途 |
|------|------|------|------|
| `ObservationPredicate` | Observation | `start()` **之前** | 决定是否开始这个观测（不创建 Timer/Span） |
| `ObservationFilter` | Observation.Context | `start()` 过程中 | 修改 Context（添加/修改 KeyValue） |
| `MeterFilter` | Meter | `registerMeterIfNecessary` | 决定是否注册这个 Meter（D1 分析过） |

三层过滤对应不同的抽象层次——Observation 层决定"要不要观测这个操作"（粗粒度），Context 层修改观测的元数据（添加统一标签），Meter 层决定"要不要注册这个具体的 Counter/Timer"（细粒度）。三层独立——ObservationPredicate 拒绝的观测不会走到 MeterFilter（因为根本没创建 Meter）。

---

## 十三、Low vs High Cardinality KeyValues

```java
getLowCardinalityKeyValues(context)   // ← method, status, outcome（值 < 100 种）
getHighCardinalityKeyValues(context)  // ← uri, userId, traceId（值无限或极高基数）
```

**Prometheus 的代价差异**：低基数 KeyValue 的每个值创建独立 time series（存储 O(N)）。高基数 KeyValue **也可能创建独立 time series**，但如果 userId 有 10000 种可能——10000 个独立的 time series → Prometheus 内存爆炸。

Micrometer 将两者分开——下游系统可以**选择只接受低基数 KeyValue**。Prometheus 通常只使用低基数（`http_server_requests_seconds_count{method="GET",status="200"}`），高基数留给 Tracing（Zipkin 按 traceId 索引，天然支持万亿级高基数）。

---

## 十四、@Observed vs @Timed——两条路径的对比

| 维度 | @Observed | @Timed |
|------|------|------|
| **处理接口** | `ObservedAspect`（micrometer-observation）→ ObservationHandler 链 | `TimedAspect`（micrometer-core）→ 直接写 Meter |
| **指标产物** | Timer + LongTaskTimer（by DefaultMeterObservationHandler） | Timer（by TimedAspect.recordBuilder()） |
| **Tracing 支持** | 原生——TracingObservationHandler 自动创建 Span | 无——@Timed 只关注 metrics |
| **直方图控制** | 依赖 Convention | @Timed 注解直接控制 percentiles/histogram/SLO |
| **Convention** | ObservationConvention 集中管理命名 | 无——name/tags 在注解上指定 |
| **适用场景** | 框架级观测（Spring Web/WebFlux/Kafka 内置） | 业务级观测（自定义方法指标） |

**两者不互斥**——@Observed 用于框架自动发起的观测（HTTP 请求、消息消费），@Timed 用于业务代码的手动打点。它们是 Micrometer 观测体系的两个层次。

---

## 十五、跨框架对比

| 维度 | Micrometer Observation | OpenTelemetry Tracer/Meter | Brave SpanHandler |
|------|------|------|------|
| **统一模型** | 一个 API 同时产出 metrics + tracing + logging | Tracer 和 Meter 是两个独立 Provider（但共享 Context） | Span 只产出 tracing |
| **Handler 链** | `ObservationHandler.onStart/onStop/onError/onEvent` | SpanProcessor（onStart/onEnd）+ MetricReader（collect） | FinishedSpanHandler |
| **Context** | `Observation.Context`（key-value store + parent ref） | `Context`（immutable key-value store + Span ref） | `TraceContext`（parent/span 关联） |
| **命名约定** | `ObservationConvention`（类型安全泛型） | `View`（metric name/tags 重命名 + 聚合） | 无 |
| **Spring Boot 集成** | Spring Boot 3 默认观测模式 | 需额外引入 `opentelemetry-spring-boot-starter` | Sleuth 3.x（已迁移到 Micrometer Tracing） |

---

## 十六、面试核心考点

### Q1: Observation API 为什么需要统一 metrics + tracing？

Spring Boot 2 时代两套独立的注解/API——导致命名不一致、Tag 重复、采样逻辑冲突。Observation 用一个"我在观测一个操作"的抽象统一三者——Handler 链自动生成 metrics + tracing + logging。

### Q2: ObservationHandler 如何共享数据？

通过 `Observation.Context`——onStart 时把 `Timer.Sample` 存入 Context（`context.put(key, value)`），onStop 时取出（`context.get(key)`）。Context 是 event-driven 的 key-value store——Handler 之间通过 Context 传递中间状态。

### Q3: Low Cardinality 和 High Cardinality KeyValues 有什么区别？

低基数（method、status）→ 每个值创建 Prometheus time series，可控（< 100 组合）。高基数（uri、userId）→ 值无限——Prometheus 内存爆炸。分离后下游可选择只接受低基数，高基数留给 Tracing。

### Q4（陷阱）: 如果在 `openScope()` 内启动新线程，新线程能获取当前 Observation 吗？

不能。`openScope()` 使用 `ThreadLocal`——只对当前线程有效。跨线程传播需要 Micrometer Tracing 的 `ContextPropagation` 或 Reactor `Hooks.enableAutomaticContextPropagation()`。

### Q5: @Observed 和 @Timed 有什么不同？应该用哪个？

@Observed 走 ObservationHandler 链——自动产出 metrics + tracing + logging。@Timed 只产出 Timer——但支持更细粒度直方图控制（percentiles/histogram/SLO）。框架级观测用 @Observed，业务级手动打点用 @Timed。

### Q6（陷阱）: ObservationFilter 修改 context 后，后续 Handler 能看到修改吗？Meter 层能看到吗？

能——所有 Handler 共享同一个 `Observation.Context` 实例。ObservationFilter 修改 KeyValue → 后续 Handler（DefaultMeterObservationHandler）创建 Timer 时使用修改后的 KeyValue → MeterFilter 阶段（D1 分析过）还能再次修改。这是三层过滤器链的完整路径。
