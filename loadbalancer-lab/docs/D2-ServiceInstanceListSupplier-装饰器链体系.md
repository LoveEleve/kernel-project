# D2: ServiceInstanceListSupplier 装饰器链体系

> 源码版本: spring-cloud-commons **v4.1.4**（tag；运行时对齐 SC 2023.0.3；4.3.2/5.x 新特性以注记标出）
> 源码路径: `spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/`
> 覆盖文件: `ServiceInstanceListSupplier.java`(45行)、`DelegatingServiceInstanceListSupplier.java`(72行)、`ServiceInstanceListSupplierBuilder.java`(447行)、`DiscoveryClientServiceInstanceListSupplier.java`(117行)、`WeightedServiceInstanceListSupplier.java`(128行)、`SameInstancePreferenceServiceInstanceListSupplier.java`(101行)、`NoopServiceInstanceListSupplier.java`(49行)、`HealthCheckServiceInstanceListSupplier.java`(160行)、`ZonePreferenceServiceInstanceListSupplier.java`(107行)、`HintBasedServiceInstanceListSupplier.java`(103行)、`SubsetServiceInstanceListSupplier.java`(93行)、`RequestBasedStickySessionServiceInstanceListSupplier.java`(102行)、`RetryAwareServiceInstanceListSupplier.java`(84行)、`CachingServiceInstanceListSupplier.java`(88行)

---

## 一、接口设计：响应式的实例供应商

`ServiceInstanceListSupplier` 是整个 LoadBalancer 的**数据源头**。它的职责就一句话：给我当前可用的实例列表。

```java
public interface ServiceInstanceListSupplier extends Supplier<Flux<List<ServiceInstance>>> {
    String getServiceId();

    default Flux<List<ServiceInstance>> get(Request request) {
        return get();
    }

    static ServiceInstanceListSupplierBuilder builder() {
        return new ServiceInstanceListSupplierBuilder();
    }
}
```

### 1.1 继承 `Supplier<Flux<List<ServiceInstance>>>`

`Supplier<Flux<List<ServiceInstance>>>` 意味着：

- `Supplier` — 无参数的供应者模式，调用 `get()` 即获取
- `Flux<List<ServiceInstance>>` — 返回的不是单一列表，而是一个**可能持续推送**的响应式流。实例列表变化时，Flux 可以重新 emit

这种设计从接口层面允许实现类在后台监听服务注册表的变化（如 `ReactiveDiscoveryClient` 的持续流），主动向下游推送更新后的实例列表。但实际上 `DiscoveryClientServiceInstanceListSupplier` 的两个版本（阻塞和响应式）都用 `Flux.defer()` 在每次订阅时重新执行拉取逻辑，是"被动拉取"而非"主动推送"。

### 1.2 双 get() 方法

```java
Flux<List<ServiceInstance>> get();              // 继承自 Supplier
Flux<List<ServiceInstance>> get(Request request); // 携带请求上下文
```

两个方法构成了接口的双重契约：

- `get()` — 获取实例列表（无上下文）
- `get(Request)` — 获取实例列表，但可能根据请求内容（hint、header、cookie 等）调整列表

默认实现是 `get(Request)` 直接委托给 `get()`，即忽略请求上下文。需要请求上下文感知的 Supplier（如 `HintBasedServiceInstanceListSupplier`、`SameInstancePreferenceServiceInstanceListSupplier`）会重写 `get(Request)`。

### 1.3 静态 builder() 方法

接口上直接定义了 `builder()` 静态方法，返回 `ServiceInstanceListSupplierBuilder`。这是 Java 8 接口静态方法的典型用法——把构建逻辑放在 Builder 类中，接口只负责入口。

### 1.4 NoopServiceInstanceListSupplier — 空兜底

```java
public class NoopServiceInstanceListSupplier implements ServiceInstanceListSupplier {
    public String getServiceId() { return ""; }

    public Flux<List<ServiceInstance>> get() {
        return Flux.defer(() -> Flux.just(Collections.emptyList()));
    }

    public Flux<List<ServiceInstance>> get(Request request) {
        return Flux.defer(() -> Flux.just(Collections.emptyList()));
    }
}
```

`NoopServiceInstanceListSupplier` 是 D1 中 `RoundRobinLoadBalancer` 构造时的兜底——当 `ObjectProvider<ServiceInstanceListSupplier>` 无法提供任何 Supplier 时，使用它。它返回空列表和空字符串，保证 `choose()` 不会因 NPE 崩溃。

---

## 二、装饰器模式：DelegatingServiceInstanceListSupplier

整个 Supplier 体系基于**装饰器模式**构建。每个装饰器包装一个"delegate"，在 delegate 的基础上增加功能。这个模式与 Java I/O 的 `InputStream` 装饰器链完全相同。

```java
public abstract class DelegatingServiceInstanceListSupplier
        implements ServiceInstanceListSupplier, SelectedInstanceCallback, InitializingBean, DisposableBean {

    protected final ServiceInstanceListSupplier delegate;

    public DelegatingServiceInstanceListSupplier(ServiceInstanceListSupplier delegate) {
        Assert.notNull(delegate, "delegate may not be null");
        this.delegate = delegate;
    }

    public ServiceInstanceListSupplier getDelegate() {
        return delegate;
    }

    public String getServiceId() {
        return delegate.getServiceId();
    }
    // ...
}
```

### 2.1 四个 SPI 接口

`DelegatingServiceInstanceListSupplier` 同时实现了四个接口，每个接口代表一个 SPI 维度：

| 接口 | 方法 | 用途 |
|------|------|------|
| `ServiceInstanceListSupplier` | `get()` / `get(Request)` / `getServiceId()` | 实例列表供应 |
| `SelectedInstanceCallback` | `selectedServiceInstance(ServiceInstance)` | 接收 LoadBalancer 选中的实例 |
| `InitializingBean` | `afterPropertiesSet()` | 自身初始化完成后的回调 |
| `DisposableBean` | `destroy()` | Bean 销毁时的清理 |

所有方法的实现都是**向下委托**：

```java
// SelectedInstanceCallback：沿着装饰器链向下传播选中的实例
public void selectedServiceInstance(ServiceInstance serviceInstance) {
    if (delegate instanceof SelectedInstanceCallback callback) {
        callback.selectedServiceInstance(serviceInstance);
    }
}

// InitializingBean：向下传播初始化
public void afterPropertiesSet() throws Exception {
    if (delegate instanceof InitializingBean) {
        ((InitializingBean) delegate).afterPropertiesSet();
    }
}

// DisposableBean：向下传播销毁
public void destroy() throws Exception {
    if (delegate instanceof DisposableBean) {
        ((DisposableBean) delegate).destroy();
    }
}
```

这种设计意味着：**请求（获取实例/选中回调/初始化/销毁）从外层装饰器进入，层层传递到最内层的数据源**。

### 2.2 装饰器链的构建

调用从外层进入，层层委托到最内层；数据从最内层返回，层层转换到最外层。

```
调用方向 ──────────────────────────────────────────────►
┌────────────┐    ┌──────────────┐    ┌──────────────────┐
│  Weighted  │ →  │ HealthCheck  │ →  │ DiscoveryClient  │ → Nacos
│  展开权重   │ ←  │  过滤不健康   │ ←  │   拉取实例列表   │
└────────────┘    └──────────────┘    └──────────────────┘
◄────────────────────────────────────────────── 返回方向
```

每个装饰器的 `get()` 实现大体遵循这个模式：

```java
public Flux<List<ServiceInstance>> get() {
    return delegate.get()           // 1. 从内层获取原始列表
        .map(this::transform)       // 2. 对列表做转换（过滤/展开/排序）
}
```

---

## 三、ServiceInstanceListSupplierBuilder 流式构建器

Builder 类（469 行）是构建整条装饰器链的入口。它的设计有两个关键抽象：

### 3.1 Creator 和 DelegateCreator

```java
// 创建最内层（数据源）
public interface Creator extends Function<ConfigurableApplicationContext, ServiceInstanceListSupplier> {}

// 在现有基础上包装一层装饰器
public interface DelegateCreator extends
        BiFunction<ConfigurableApplicationContext, ServiceInstanceListSupplier, ServiceInstanceListSupplier> {}
```

Builder 内部维护两个列表：

```java
private @Nullable Creator baseCreator;               // 最内层的数据源工厂
private final List<DelegateCreator> creators = new ArrayList<>();  // 装饰器工厂（有序）
```

### 3.2 build() 方法

```java
public ServiceInstanceListSupplier build(ConfigurableApplicationContext context) {
    Assert.notNull(baseCreator, "A baseCreator must not be null");

    ServiceInstanceListSupplier supplier = baseCreator.apply(context); // 创建最内层

    for (DelegateCreator creator : creators) {        // 按 add 顺序，外层包装内层
        supplier = creator.apply(context, supplier);
    }

    return supplier;
}
```

构建顺序：

```
baseCreator (最内层：DiscoveryClient)
  → creator[0] (第 1 层装饰器)
    → creator[1] (第 2 层装饰器)
      → ...
        → creator[n] (最外层装饰器)
```

每个 `withXxx()` 方法向 `creators` 列表添加一个 `DelegateCreator`。

### 3.3 withXxx() 方法族

Builder 提供 20 个 `withXxx()` 方法（含重载，15 个唯一方法名；4.1.4 精确计数），按功能分组：

| 方法 | 创建的装饰器 | 依赖的 Spring Bean |
|------|-------------|-------------------|
| `withBlockingDiscoveryClient()` | `DiscoveryClientServiceInstanceListSupplier` | `DiscoveryClient` |
| `withDiscoveryClient()` | `DiscoveryClientServiceInstanceListSupplier` | `ReactiveDiscoveryClient` |
| `withBase(Supplier)` | 用户提供的 Supplier | 无 |
| `withWeighted()` | `WeightedServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withWeighted(WeightFunction)` | `WeightedServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withHealthChecks()` | `HealthCheckServiceInstanceListSupplier` | `WebClient.Builder` + `LoadBalancerClientFactory` |
| `withHealthChecks(WebClient)` | `HealthCheckServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withBlockingHealthChecks()` | `HealthCheckServiceInstanceListSupplier` | `RestTemplate` + `LoadBalancerClientFactory` |
| `withBlockingHealthChecks(RestTemplate)` | `HealthCheckServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withBlockingHealthChecks(RestClient)` | `HealthCheckServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withBlockingRestClientHealthChecks()` | `HealthCheckServiceInstanceListSupplier` | `RestClient` + `LoadBalancerClientFactory` |
| `withSameInstancePreference()` | `SameInstancePreferenceServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withZonePreference()` | `ZonePreferenceServiceInstanceListSupplier` | `LoadBalancerZoneConfig` + `LoadBalancerClientFactory` |
| `withZonePreference(String)` | `ZonePreferenceServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withRequestBasedStickySession()` | `RequestBasedStickySessionServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withCaching()` | `CachingServiceInstanceListSupplier` | `LoadBalancerCacheManager`（可选） |
| `withRetryAwareness()` | `RetryAwareServiceInstanceListSupplier` | 无 |
| `withHints()` | `HintBasedServiceInstanceListSupplier` | `LoadBalancerClientFactory` |
| `withSubset()` | `SubsetServiceInstanceListSupplier` | `PropertyResolver` + `LoadBalancerClientFactory` |
| `with(DelegateCreator)` | 用户自定义的装饰器 | 由用户代码决定 |

> ⚠️ **勘误（2026-08-01 A-13 验证时发现）**：本表原有 `withReactiveApiVersioning()` /
> `withBlockingApiVersioning()` 两行，已用 `git show v4.1.4:.../ServiceInstanceListSupplierBuilder.java`
> 核实**本 lab 运行时 spring-cloud-commons 4.1.4 版本没有这两个 Builder 方法**（sca-lab 原基线 4.3.2 同样没有）——
> ApiVersion 支持由 PR #1582（commit `77895a52`）引入，首次出现在 v5.0.0-RC1，
> 比本项目依赖版本晚一个大版本，当前技术栈下不存在，详见 D3 5.3 勘误。

### 3.4 构建示例

```java
// 一个完整的构建示例（仅用于演示，实际链由 LoadBalancerClientConfiguration 按条件组装）
ServiceInstanceListSupplier supplier = ServiceInstanceListSupplier.builder()
    .withBlockingDiscoveryClient()        // 最内层：DiscoveryClient
    .withSameInstancePreference()          // 同实例优先
    .withZonePreference()                  // 同区域优先
    .withCaching()                         // 缓存
    .withHealthChecks()                    // 健康检查（最外层）
    .build(context);
```

调用链数据流向：

```
LoadBalancer.choose()
  ↓
HealthCheckServiceInstanceListSupplier.get()    ← 只返回健康的实例
  ↓ delegate
CachingServiceInstanceListSupplier.get()        ← 缓存结果
  ↓ delegate
ZonePreferenceServiceInstanceListSupplier.get() ← 按区域过滤
  ↓ delegate
SameInstancePreferenceServiceInstanceListSupplier.get()  ← 尽量返回之前选中的实例
  ↓ delegate
DiscoveryClientServiceInstanceListSupplier.get() ← 从注册中心拉取
```

---

## 四、关键实现精读

### 4.1 DiscoveryClientServiceInstanceListSupplier — 数据源

这是整条链的**最内层**，负责从服务注册中心拉取实例列表。它**不继承 `DelegatingServiceInstanceListSupplier`**，而是直接实现 `ServiceInstanceListSupplier`。

两种构造函数分别对应阻塞和响应式 DiscoveryClient：

```java
// 阻塞 DiscoveryClient（阻塞式 RestTemplate 调用）
public DiscoveryClientServiceInstanceListSupplier(DiscoveryClient delegate, Environment environment) {
    // ...
    this.serviceInstances = Flux.defer(() ->
        Mono.fromCallable(() -> delegate.getInstances(serviceId))  // 阻塞调用包装为 Mono
    )
    .timeout(timeout, Flux.defer(() -> { ... }))   // 超时降级为空列表
    .onErrorResume(error -> { ... });              // 异常降级为空列表
}

// 响应式 ReactiveDiscoveryClient
public DiscoveryClientServiceInstanceListSupplier(ReactiveDiscoveryClient delegate, Environment environment) {
    // ...
    this.serviceInstances = Flux
        .defer(() -> delegate.getInstances(serviceId)
            .collectList()
            .flux()
            .timeout(timeout, Flux.defer(() -> {
                logTimeout();
                return Flux.just(new ArrayList<>());
            }))
            .onErrorResume(error -> {
                logException(error);
                return Flux.just(new ArrayList<>());
            }));
}
```

几个关键设计：

**`Flux.defer()`** — 延迟创建 Flux，确保每次订阅时都重新执行。因为 `DiscoveryClient.getInstances()` 每次调用返回的是当前时刻的快照，不是持续推送的流。`defer` 使得每次 Flux 被订阅时都重新调用 `getInstances()`，从而拿到最新数据。

**超时降级** — `timeout(timeout, Flux.defer(() -> Flux.just(new ArrayList<>())))`。当服务发现调用超时时，不抛异常，而是返回空列表+日志警告。这比抛异常更安全——LoadBalancer 会返回 `EmptyResponse`，调用方能得到"无可用实例"的明确信号。

**异常降级** — `.onErrorResume(error -> { logException(error); return Flux.just(new ArrayList<>()); })`。任何异常都被捕获并降级为空列表，不影响 LoadBalancer 的执行流。

**`resolveTimeout()`** — 从 Environment 读取 `spring.cloud.loadbalancer.service-discovery.timeout`，默认 30 秒。

### 4.2 WeightedServiceInstanceListSupplier — 权重展开

D4 会详细分析，这里只说明它在装饰器链中的角色。

```java
public Flux<List<ServiceInstance>> get() {
    return delegate.get().map(this::expandByWeight);
}
```

`expandByWeight()` 对每个实例调用 `weightFunction.apply(instance)` 获取权值，然后构建 `LazyWeightedServiceInstanceList`（惰性展开列表）。默认的 `metadataWeightFunction` 从 `metadata.weight` 字段读取权值，无配置则默认为 1。

值得注意的是 `get(Request)` 的实现：

```java
public Flux<List<ServiceInstance>> get(Request request) {
    if (callGetWithRequestOnDelegates) {
        return delegate.get(request).map(this::expandByWeight);
    }
    return get();
}
```

`callGetWithRequestOnDelegates` 控制是否把 Request 传递给内层 delegate。权重展开本身不依赖请求上下文，所以可以选择不传递 Request，直接调用 `get()`（无参版本可能走缓存）。

### 4.3 SameInstancePreferenceServiceInstanceListSupplier — 同实例优先

这是 "尽量选择之前选中的实例" 的实现。

```java
public class SameInstancePreferenceServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier
        implements SelectedInstanceCallback {

    private ServiceInstance previouslyReturnedInstance;

    public Flux<List<ServiceInstance>> get() {
        return delegate.get().map(this::filteredBySameInstancePreference);
    }

    private List<ServiceInstance> filteredBySameInstancePreference(List<ServiceInstance> serviceInstances) {
        if (previouslyReturnedInstance != null && serviceInstances.contains(previouslyReturnedInstance)) {
            return Collections.singletonList(previouslyReturnedInstance);  // 只返回之前选中的实例
        }
        previouslyReturnedInstance = null;  // 之前选中的实例已不可用，清空记录
        return serviceInstances;
    }

    public void selectedServiceInstance(ServiceInstance serviceInstance) {
        super.selectedServiceInstance(serviceInstance);  // 向下传播
        if (previouslyReturnedInstance == null || !previouslyReturnedInstance.equals(serviceInstance)) {
            previouslyReturnedInstance = serviceInstance;  // 记录新选中的实例
        }
    }
}
```

关键行为：

- 当 `previouslyReturnedInstance` 在当前实例列表中 → 只返回这一个实例（LoadBalancer 选择时必然选它）
- 当之前选中的实例已不在列表中 → 清空记录，返回全部实例
- `selectedServiceInstance()` 回调更新记录，并通过 `super.selectedServiceInstance()` 向下传播

这实现了 **"同实例亲和性"**——只要之前选中的实例还活着，就一直选它。比 "粘性会话" 更轻量（不需要 cookie/header 传递）。

### 4.4 HealthCheckServiceInstanceListSupplier — 健康检查（最复杂的装饰器）

这是整条链中**实现最复杂**的装饰器。它不仅做数据转换，还启动了一个**后台定时任务**，周期性 ping 所有实例的健康端点，只把存活的实例暴露给下游。

#### 4.4.1 核心字段与构造函数

```java
public class HealthCheckServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier
        implements InitializingBean, DisposableBean {

    private final LoadBalancerProperties.HealthCheck healthCheck;   // 健康检查配置
    private final String defaultHealthCheckPath;                     // 默认健康检查路径
    private final Flux<List<ServiceInstance>> aliveInstancesReplay; // 复用的存活实例流
    private @Nullable Disposable healthCheckDisposable;              // 定时任务的 Disposable
    private final BiFunction<ServiceInstance, String, Mono<Boolean>> aliveFunction; // 健康检查函数

    public HealthCheckServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerClientFactory,
            BiFunction<ServiceInstance, String, Mono<Boolean>> aliveFunction) {
        super(delegate);
        LoadBalancerProperties properties = loadBalancerClientFactory.getProperties(getServiceId());
        this.healthCheck = (properties == null) ? new LoadBalancerProperties.HealthCheck()
                : properties.getHealthCheck();
        defaultHealthCheckPath = healthCheck.getPath().getOrDefault("default", "/actuator/health");
        this.aliveFunction = aliveFunction;
        // ... aliveInstancesReplay 的构建见下
    }
}
```

三个关键点：

1. **`aliveFunction`（BiFunction）** — 健康检查的实际执行者。签名 `(ServiceInstance, String healthCheckPath) -> Mono<Boolean>`。`BiFunction` 抽象了 HTTP 客户端的差异——`WebClient`（响应式）、`RestTemplate`（阻塞）、`RestClient`（阻塞）都可以实现它，Builder 的 `withHealthChecks()` 系列方法传入不同的实现。这就是**策略模式**。

2. **`healthCheck`（LoadBalancerProperties.HealthCheck）** — 从 `LoadBalancerClientFactory.getProperties(serviceId)` 读取的配置对象。`getProperties()` 返回 null 时（极端情况），用默认配置兜底。

3. **`defaultHealthCheckPath`** — 默认健康检查路径 `/actuator/health`，可以通过 `spring.cloud.loadbalancer.health-check.path.default` 配置覆盖（注意：`getOrDefault("default", ...)` 是**按服务 ID 维度**的 map，`default` 是特殊 key）。

#### 4.4.2 aliveInstancesReplay — 流的核心构建

构造函数中最核心的部分是 `aliveInstancesReplay` 的构建：

```java
Repeat<Object> aliveInstancesReplayRepeat = Repeat
    .onlyIf(repeatContext -> this.healthCheck.getRefetchInstances())  // 是否周期重新拉取实例
    .fixedBackoff(healthCheck.getRefetchInstancesInterval());         // 重新拉取的间隔

Flux<List<ServiceInstance>> aliveInstancesFlux = Flux.defer(delegate)   // 1. 从 delegate 拉取实例列表
    .repeatWhen(aliveInstancesReplayRepeat)                             // 2. 按配置周期重复拉取
    .switchMap(serviceInstances -> healthCheckFlux(serviceInstances)    // 3. 对每批实例做健康检查
        .map(List::copyOf));                                            // 4. 结果转为不可变列表

aliveInstancesReplay = aliveInstancesFlux.delaySubscription(healthCheck.getInitialDelay()) // 延迟启动
    .replay(1)      // 5. 缓存最近一次结果
    .refCount(1);   // 6. 引用计数：1 个订阅者时启动，0 个时停止
```

流水线拆解：

```
Flux.defer(delegate)        拉取实例列表（每次订阅重新拉）
    → repeatWhen(Repeat)    按 refetchInstancesInterval 周期重复拉取
    → switchMap(健康检查)    对每批实例并发做健康检查，切换为存活实例流
    → delaySubscription     延迟 initialDelay 启动
    → replay(1).refCount(1) 共享流：缓存最近 1 次结果，多个订阅者共享
```

**`switchMap` 的作用**：每次 delegate 发出新的实例列表，就丢弃上一个健康检查流程，切换到新列表的健康检查。保证永远基于最新的实例列表做检查。

**`replay(1).refCount(1)` 的作用**：
- `replay(1)` — 把最近一次健康检查结果缓存下来，后续订阅者立即拿到缓存
- `refCount(1)` — 引用计数。第一个订阅者接入时启动底层流，最后一个订阅者断开时停止。`afterPropertiesSet()` 里主动 `subscribe()` 一次，让健康检查**在 Bean 初始化后立即开始运行**，不依赖请求触发。

#### 4.4.3 healthCheckFlux — 并发健康检查

```java
protected Flux<List<ServiceInstance>> healthCheckFlux(List<ServiceInstance> instances) {
    Repeat<Object> healthCheckFluxRepeat = Repeat.onlyIf(repeatContext -> healthCheck.getRepeatHealthCheck())
        .fixedBackoff(healthCheck.getInterval());

    return Flux.defer(() -> {
        List<Mono<ServiceInstance>> checks = new ArrayList<>(instances.size());
        for (ServiceInstance instance : instances) {
            Mono<ServiceInstance> alive = isAlive(instance)
                .onErrorResume(error -> {           // 检查异常 → 视为不存活
                    return Mono.empty();
                })
                .timeout(healthCheck.getInterval(),  // 检查超时 → 视为不存活
                    Mono.defer(() -> Mono.empty()))
                .handle((isHealthy, sink) -> {       // 只有存活才 emit 实例
                    if (isHealthy) {
                        sink.next(instance);
                    }
                });
            checks.add(alive);
        }

        List<ServiceInstance> result = new ArrayList<>();
        if (healthCheck.isUpdateResultsList()) {
            // 模式 A：增量更新（默认）
            return Flux.merge(checks).map(alive -> {
                result.add(alive);
                return result;                       // 每个存活实例到达时发出当前累积列表
            }).defaultIfEmpty(result);
        }
        // 模式 B：一次性收集
        return Flux.merge(checks).collectList();
    }).repeatWhen(healthCheckFluxRepeat);
}
```

**并发检查**：`Flux.merge(checks)` 并发执行所有实例的健康检查（Mono 在订阅时并行）。对 N 个实例，健康检查总耗时 ≈ 单个检查的最大耗时，而非 N 倍。

**三种"判死"手段**：
1. `onErrorResume` — 检查抛异常（连接拒绝、超时等）→ `Mono.empty()`（不 emit 任何值）
2. `timeout(interval)` — 检查超过 `interval` 未返回 → `Mono.empty()`
3. `handle()` — 即使返回了 `isHealthy=false`，也不 emit

**两种结果聚合模式**（由 `updateResultsList` 配置控制，默认 true）：
- `updateResultsList=true`（默认）：`Flux.merge(checks).map(...)` 在每个存活实例到达时**增量**发出当前累积列表。下游可以尽早拿到部分结果，不用等全部检查完成
- `updateResultsList=false`：`collectList()` 等**全部**检查完成才发出完整列表

**`repeatWhen(healthCheckFluxRepeat)`** — 按 `interval` 周期重复整轮健康检查，`repeatHealthCheck=false` 时只查一次。

#### 4.4.4 isAlive 与 updatedServiceInstance

```java
protected Mono<Boolean> isAlive(ServiceInstance serviceInstance) {
    boolean containsService = healthCheck.getPath().containsKey(serviceInstance.getServiceId());
    String healthCheckPropertyValue = healthCheck.getPath().get(serviceInstance.getServiceId());
    if (containsService && !StringUtils.hasText(healthCheckPropertyValue)) {
        return Mono.just(true);    // 显式配置了空路径 → 跳过健康检查，直接视为存活
    }
    String healthCheckPath = healthCheckPropertyValue != null ? healthCheckPropertyValue : defaultHealthCheckPath;
    return aliveFunction.apply(updatedServiceInstance(serviceInstance), healthCheckPath);
}

private ServiceInstance updatedServiceInstance(ServiceInstance serviceInstance) {
    Integer healthCheckPort = healthCheck.getPort();
    if (serviceInstance instanceof DefaultServiceInstance && healthCheckPort != null) {
        return new DefaultServiceInstance(serviceInstance.getInstanceId(), serviceInstance.getServiceId(),
                serviceInstance.getHost(), healthCheckPort, serviceInstance.isSecure(),
                serviceInstance.getMetadata());
    }
    return serviceInstance;
}
```

**`isAlive` 的三个分支**：
1. `healthCheck.path[serviceId]` 配置存在且为空字符串 → 该服务**跳过健康检查**，直接返回存活（可用于不想做健康检查的服务）
2. 配置了 `healthCheck.path[serviceId]` → 使用该路径
3. 未配置 → 使用 `defaultHealthCheckPath`（`/actuator/health`）

**`updatedServiceInstance`** — 如果配置了 `healthCheck.port`，健康检查请求发送到**指定的管理端口**（而非业务端口），这是生产环境的常见需求（业务端口和管理端口分离）。

#### 4.4.5 生命周期：afterPropertiesSet 与 destroy

```java
@Override
public void afterPropertiesSet() {
    Disposable healthCheckDisposable = this.healthCheckDisposable;
    if (healthCheckDisposable != null) {
        healthCheckDisposable.dispose();
    }
    this.healthCheckDisposable = aliveInstancesReplay.subscribe();  // 启动后台健康检查
}

@Override
public void destroy() {
    Disposable healthCheckDisposable = this.healthCheckDisposable;
    if (healthCheckDisposable != null) {
        healthCheckDisposable.dispose();                            // 停止后台健康检查
    }
}
```

`InitializingBean.afterPropertiesSet()` 在 Spring 完成 Bean 依赖注入后调用——**订阅 `aliveInstancesReplay` 启动健康检查循环**。`DisposableBean.destroy()` 在应用关闭时释放订阅。

#### 4.4.6 关键配置项汇总（spring.cloud.loadbalancer.health-check.*）

| 配置 | 默认值 | 作用 |
|------|--------|------|
| `initialDelay` | `0s` | 健康检查启动延迟 |
| `interval` | `25s` | 健康检查重复间隔 |
| `refetchInstancesInterval` | `25s` | 重新拉取实例列表的间隔 |
| `refetchInstances` | `false` | 是否周期性重新拉取实例（配合不持续的 delegate 流） |
| `repeatHealthCheck` | `true` | 是否重复执行健康检查（refetch 场景可设为 false） |
| `path.<serviceId>` | — | 每个服务的健康检查路径；`path.default` 为全局默认 |
| `port` | — | 健康检查端口（管理端口） |
| `updateResultsList` | `true` | 增量 vs 一次性返回结果 |

### 4.5 ZonePreferenceServiceInstanceListSupplier — 同区域优先

区域路由的核心。它的职责：**如果当前实例设置了 zone，且实例列表中存在同 zone 的实例，只返回同 zone 的实例；否则返回全部**。

```java
public class ZonePreferenceServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private static final String ZONE_KEY = "zone";

    private final LoadBalancerZoneConfig zoneConfig;

    private @Nullable String zone;   // 懒加载：第一次 get() 时才从配置读取

    @Override
    public Flux<List<ServiceInstance>> get() {
        return getDelegate().get().map(this::filteredByZone);
    }

    private List<ServiceInstance> filteredByZone(List<ServiceInstance> serviceInstances) {
        if (zone == null) {
            zone = zoneConfig.getZone();   // 从 spring.cloud.loadbalancer.zone 读取
        }
        if (zone != null) {
            List<ServiceInstance> filteredInstances = new ArrayList<>();
            for (ServiceInstance serviceInstance : serviceInstances) {
                String instanceZone = getZone(serviceInstance);   // 从实例 metadata.zone 读取
                if (zone.equalsIgnoreCase(instanceZone)) {
                    filteredInstances.add(serviceInstance);
                }
            }
            if (!filteredInstances.isEmpty()) {
                return filteredInstances;
            }
        }
        // 无 zone 配置，或没有同 zone 实例 → 返回全部
        return serviceInstances;
    }

    private @Nullable String getZone(ServiceInstance serviceInstance) {
        Map<String, String> metadata = serviceInstance.getMetadata();
        if (metadata != null) {
            return metadata.get(ZONE_KEY);   // 实例的 zone 来自注册中心 metadata
        }
        return null;
    }
}
```

**关键点**：

- **本端 zone 来源**：`spring.cloud.loadbalancer.zone` 配置（通过 `LoadBalancerZoneConfig` 注入）。`zone` 字段懒加载——第一次 `get()` 才读取，之后缓存
- **实例 zone 来源**：实例注册到注册中心时写在 `metadata.zone` 里（如 Nacos 控制台配置）
- **比较规则**：`equalsIgnoreCase`（大小写不敏感）
- **降级策略**：找不到同 zone 实例时**静默返回全部**——区域路由是"优先"而非"强制"
- `get(Request)` 同样受 `callGetWithRequestOnDelegates` 控制（见 4.10）

### 4.6 HintBasedServiceInstanceListSupplier — 提示路由

基于 hint（提示）的过滤。**hint 从请求上下文提取，实例是否匹配看 metadata.hint**。这是"请求级"的路由控制——每次请求可以携带不同的 hint，路由到不同实例（如灰度发布）。

```java
@Override
public Flux<List<ServiceInstance>> get(Request request) {
    return delegate.get(request).map(instances -> filteredByHint(instances, getHint(request.getContext())));
}

private @Nullable String getHint(@Nullable Object requestContext) {
    if (requestContext == null) {
        return null;
    }
    String hint = null;
    if (requestContext instanceof RequestDataContext) {
        hint = getHintFromHeader((RequestDataContext) requestContext);   // 1. 从 HTTP Header 提取
    }
    if (!StringUtils.hasText(hint) && requestContext instanceof HintRequestContext) {
        hint = ((HintRequestContext) requestContext).getHint();          // 2. 从 HintRequestContext 提取
    }
    return hint;
}

private @Nullable String getHintFromHeader(RequestDataContext context) {
    if (context.getClientRequest() != null) {
        HttpHeaders headers = context.getClientRequest().getHeaders();
        if (headers != null) {
            return headers.getFirst(properties.getHintHeaderName());     // 默认 Header: X-SC-LB-Hint
        }
    }
    return null;
}

private List<ServiceInstance> filteredByHint(List<ServiceInstance> instances, @Nullable String hint) {
    if (!StringUtils.hasText(hint)) {
        return instances;
    }
    List<ServiceInstance> filteredInstances = new ArrayList<>();
    for (ServiceInstance serviceInstance : instances) {
        if (serviceInstance.getMetadata() != null
                && serviceInstance.getMetadata().getOrDefault("hint", "").equals(hint)) {
            filteredInstances.add(serviceInstance);
        }
    }
    if (!filteredInstances.isEmpty()) {
        return filteredInstances;
    }
    return instances;   // 无匹配实例 → 返回全部（降级）
}
```

**hint 提取的两个来源（按优先级）**：

1. **HTTP Header**（`RequestDataContext` 路径）— 请求头 `X-SC-LB-Hint`（可通过 `spring.cloud.loadbalancer.hint-header-name` 覆盖）。RestTemplate/WebClient 集成时走这条路径
2. **`HintRequestContext`** — 编程式传入的 hint 对象。第一个来源没取到时兜底

**关键点**：

- `get()` 无参版本**直接返回 delegate 的结果**，不做过滤——hint 是请求级的，无请求就没有 hint
- 实例匹配：`metadata.hint == 请求 hint`，**精确匹配**
- 降级策略：无匹配实例 → 返回全部（与 ZonePreference 一致，"优先"而非"强制"）
- 典型场景：灰度发布——请求带 `X-SC-LB-Hint: gray`，只有 metadata.hint=gray 的实例（灰度池）被选中

### 4.7 SubsetServiceInstanceListSupplier — 确定性子集选择

解决**大集群问题**：当服务有几百个实例时，每个客户端只与其中一部分实例建立连接（避免连接风暴），且每个客户端选择的子集是**确定性的**（同一客户端每次选择同一子集）。

```java
@Override
public Flux<List<ServiceInstance>> get(Request request) {
    return delegate.get(request).map(instances -> {
        if (instances.size() <= size) {
            return instances;    // 实例数 <= 子集大小 → 全部返回
        }

        instances = new ArrayList<>(instances);

        int instanceId = this.instanceId.hashCode() & Integer.MAX_VALUE;  // 客户端 ID 哈希
        int count = instances.size() / size;                              // 桶数
        int round = instanceId / count;                                   // 轮次（防抖动）

        Random random = new Random(round);                                // 种子 = round（确定性）
        Collections.shuffle(instances, random);                           // 按种子洗牌

        int bucket = instanceId % count;                                  // 分配到的桶
        int start = bucket * size;
        return instances.subList(start, start + size);                    // 取桶内子集
    });
}
```

这是 Google SRE 书中 **deterministic subsetting 算法**的实现：

```
instanceId（客户端标识）→ hashCode → 分成 count 个桶
                                     ↓
                     取 instanceId % count 对应的桶
                                     ↓
                     桶内 start = bucket * size 处取 size 个实例
```

**无参 get() 的转发**：Subset 的无参 `get()` 会构造一个匿名空 `Request` 再调用 `get(request)`——因为它必须走 `get(Request)` 路径才能拿到实例列表并做子集选择（源码如此实现，而非直接 `delegate.get()`）。

**确定性**：`Random(round)` 使用 `round` 作为种子，`round = instanceId / count`。同一个客户端的 `instanceId` 不变 → `round` 不变 → 洗牌结果不变 → 每次选中的子集相同。**这是它和随机子集（每次不同）的本质区别**。

**防抖动设计**：当实例列表变化导致 `count` 变化时，`round` 也变化，子集会平稳过渡，避免大量客户端同时切换子集。

**关键点**：

- `instanceId` 来源：`spring.cloud.loadbalancer.subset.instance-id` 配置；未配置时用 `IdUtils.getDefaultInstanceId()`（基于 hostname、端口等生成）
- `size` 来源：`spring.cloud.loadbalancer.subset.size`（默认 100）
- 注意：`size` 是**客户端子集上限**，不是集群目标大小

### 4.8 RequestBasedStickySessionServiceInstanceListSupplier — 请求粘性会话

基于 **cookie** 的粘性会话：请求带 `sc-lb-instance-id` cookie → 优先路由到 cookie 指定的实例。

```java
@Override
public Flux<List<ServiceInstance>> get(Request request) {
    String instanceIdCookieName = "sc-lb-instance-id";   // 默认值（LoadBalancerProperties.StickySession.instanceIdCookieName 字段默认）
    if (properties != null) {
        instanceIdCookieName = properties.getStickySession().getInstanceIdCookieName();  // 可配置
    }
    Object context = request.getContext();
    if ((context instanceof RequestDataContext)) {
        RequestData clientRequest = ((RequestDataContext) context).getClientRequest();
        MultiValueMap<String, String> cookies = null;
        if (clientRequest != null) {
            cookies = clientRequest.getCookies();
        }
        if (cookies == null) {
            return delegate.get(request);   // 无 cookie → 正常路由
        }
        String cookie = cookies.getFirst(instanceIdCookieName);
        if (cookie != null) {
            return delegate.get(request).map(serviceInstances -> selectInstance(serviceInstances, cookie));
        }
        return delegate.get(request);       // cookie 不存在 → 正常路由
    }
    return delegate.get(request);           // 非 HTTP 请求 → 正常路由
}

private List<ServiceInstance> selectInstance(List<ServiceInstance> serviceInstances, String cookie) {
    for (ServiceInstance serviceInstance : serviceInstances) {
        if (cookie.equals(serviceInstance.getInstanceId())) {
            return Collections.singletonList(serviceInstance);   // 命中 cookie 指定的实例
        }
    }
    return serviceInstances;   // 实例不存在 → 返回全部（降级，重新路由）
}
```

**执行流程**：

```
请求到达
  │
  ├─ 上下文不是 RequestDataContext → 正常路由（非 HTTP 场景）
  ├─ 无 cookie 表 / 无 sc-lb-instance-id cookie → 正常路由
  ├─ cookie 命中实例列表 → 只返回该实例（粘住）
  └─ cookie 指定的实例已下线 → 返回全部（重新路由，下次请求会带新 cookie）
```

**与 SameInstancePreference 的区别**：

| 维度 | SameInstancePreference | RequestBasedStickySession |
|------|----------------------|--------------------------|
| 粘性依据 | LoadBalancer 上次选中的实例（内存态） | 请求 cookie（跨客户端、跨进程） |
| 状态存储 | 本地字段 `previouslyReturnedInstance` | cookie（客户端持有） |
| 适用场景 | 同一客户端进程内多次调用 | 浏览器/客户端跨请求、跨进程 |
| 配置 | 无 | `sticky-session.instance-id-cookie-name`、`sticky-session.add-service-instance-cookie` |

**写入 cookie 的机制**（`LoadBalancerServiceInstanceCookieTransformer`，实现 `LoadBalancerRequestTransformer`）：配置 `spring.cloud.loadbalancer.sticky-session.add-service-instance-cookie=true` 时，`BlockingLoadBalancerRequest` 在**选中实例后、发出请求前**调用 `transformRequest()`，把 `sc-lb-instance-id=<选中实例的 instanceId>` 追加到**请求的 Cookie 头**。后续同一客户端的请求带着 cookie 回来，`RequestBasedStickySessionServiceInstanceListSupplier` 据此粘住同一实例。

### 4.9 RetryAwareServiceInstanceListSupplier — 重试感知

重试时**避免再次选择上次失败的实例**。

```java
@Override
public Flux<List<ServiceInstance>> get(Request request) {
    if (!(request.getContext() instanceof RetryableRequestContext context)) {
        return delegate.get(request);   // 非重试请求 → 正常路由
    }
    ServiceInstance previousServiceInstance = context.getPreviousServiceInstance();
    if (previousServiceInstance == null) {
        return delegate.get(request);   // 无上次实例 → 正常路由
    }
    return delegate.get(request).map(instances -> filteredByPreviousInstance(instances, previousServiceInstance));
}

private List<ServiceInstance> filteredByPreviousInstance(List<ServiceInstance> instances,
        ServiceInstance previousServiceInstance) {
    List<ServiceInstance> filteredInstances = new ArrayList<>(instances);
    if (previousServiceInstance != null) {
        filteredInstances.remove(previousServiceInstance);   // 剔除上次失败的实例
    }
    if (filteredInstances.size() > 0) {
        return filteredInstances;
    }
    // 剔除后为空 → 返回全部（降级，避免无实例可用）
    return instances;
}
```

**关键点**：

- 只在**重试请求**（上下文是 `RetryableRequestContext`）时生效——由 `BlockingLoadBalancedRetryPolicy` / `RetryableLoadBalancerExchangeFilterFunction` 在重试时注入 `previousServiceInstance`
- 把上次失败的实例从候选列表中剔除，让 LoadBalancer 选到**另一个**实例
- 剔除后列表为空 → 返回全部（不能没有可用实例）
- 这是"重试换实例"的核心机制：配合 `maxRetriesOnNextServiceInstance` 使用

### 4.10 CachingServiceInstanceListSupplier — 响应式缓存

基于 reactor-cache 的 `CacheFlux`，把 delegate 的实例列表缓存起来，减少对注册中心的调用频率。

```java
public class CachingServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    public static final String SERVICE_INSTANCE_CACHE_NAME =
            CachingServiceInstanceListSupplier.class.getSimpleName() + "Cache";  // "CachingServiceInstanceListSupplierCache"

    private final Flux<List<ServiceInstance>> serviceInstances;

    @SuppressWarnings("unchecked")
    public CachingServiceInstanceListSupplier(ServiceInstanceListSupplier delegate, CacheManager cacheManager) {
        super(delegate);
        this.serviceInstances = CacheFlux.lookup(key -> {
            Cache cache = cacheManager.getCache(SERVICE_INSTANCE_CACHE_NAME);
            if (cache == null) {
                return Mono.empty();   // 缓存不存在 → 视为未命中
            }
            List<ServiceInstance> list = cache.get(key, List.class);
            if (list == null || list.isEmpty()) {
                return Mono.empty();   // 缓存为空 → 视为未命中
            }
            return Flux.just(list).materialize().collectList();  // 命中 → 返回缓存值
        }, delegate.getServiceId())                              // key = serviceId
            .onCacheMissResume(delegate.get().take(1))           // 未命中 → 从 delegate 拉取（只取 1 次）
            .andWriteWith((key, signals) -> Flux.fromIterable(signals)
                .dematerialize()
                .doOnNext(instances -> {
                    Cache cache = cacheManager.getCache(SERVICE_INSTANCE_CACHE_NAME);
                    if (cache != null) {
                        cache.put(key, instances);               // 拉取结果写回缓存
                    }
                }).then());
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return serviceInstances;
    }
}
```

**CacheFlux 三段式**：

```
CacheFlux.lookup(读取缓存, key=serviceId)
    → onCacheMissResume(delegate.get().take(1))    未命中时从 delegate 拉取
    → andWriteWith(写回缓存)                        拉取结果写回
```

**关键点**：

- **缓存介质**：Spring `CacheManager`（默认 Caffeine，见 `CaffeineBasedLoadBalancerCacheManager`），缓存名 `CachingServiceInstanceListSupplierCache`
- **缓存 key**：`serviceId`
- **`take(1)`**：delegate 的 Flux 可能持续推送，缓存场景只取**第一次**结果
- **TTL**：由 `LoadBalancerCacheProperties` 控制（`spring.cloud.loadbalancer.cache.ttl`，默认 35s；另有 `capacity` 默认 256）
- **空列表的处理**：空列表**会写入缓存**（`andWriteWith` 无条件 `cache.put`），但 **lookup 时 `list.isEmpty()` 视为未命中**（`Mono.empty()`）——即空结果不会被当作命中返回，每次都会重新拉取。注意副作用：空列表写入会**覆盖之前的非空缓存**，服务恢复时仍需等待下一次 delegate 拉取才有数据

### 4.11 callGetWithRequestOnDelegates — 请求传递开关

在 `Weighted`、`SameInstancePreference`、`ZonePreference` 三个装饰器中反复出现的同一个模式：

```java
@Override
public Flux<List<ServiceInstance>> get(Request request) {
    if (callGetWithRequestOnDelegates) {
        return delegate.get(request).map(this::transform);   // 传递 Request 给内层
    }
    return get();                                            // 不传递，走无参路径
}
```

**它控制什么**：这三个装饰器在收到 `get(Request)` 时，是否把 Request 继续传给内层 delegate。

**谁受影响**：只有内层是**请求感知装饰器**（`HintBased`、`RequestBasedStickySession`、`RetryAware`、`Subset`，它们都重写了 `get(Request)` 并从 Request 提取 hint/cookie/重试上下文）时，Request 的传递才有意义。例如链 `[Zone → Hint → Discovery]`：
- `callGetWithRequestOnDelegates=true`（默认）：`Zone.get(request)` → `Hint.get(request)` → 提取 hint 过滤 ✓
- `callGetWithRequestOnDelegates=false`：`Zone.get(request)` → `Zone.get()` → `Hint.get()` → **无 request，hint 过滤失效** ✗

**为什么 Caching 和 HealthCheck 被特别提到**（源码注释原文）：

> with the exclusion of CachingServiceInstanceListSupplier and HealthCheckServiceInstanceListSupplier, which should be placed in the instance supplier hierarchy directly after the supplier performing instance retrieval over the network, before any request-based filtering is done

这两个 Supplier **没有重写 `get(Request)`**，继承接口默认实现 `get(Request) { return get(); }`——Request 在它们这里**必然被丢弃**。因此源码注释建议它们的位置：紧跟在网络拉取（DiscoveryClient）之后、请求级过滤之前，即靠近内层。这样外层传下来的 Request 在到达它们时被丢弃，不会影响更内层的语义。

**配置**：`spring.cloud.loadbalancer.call-get-with-request-on-delegates`，默认 `true`。生产建议：如果链中同时包含请求感知装饰器和 Caching/HealthCheck，保持默认即可——请求过滤发生在 Caching 外层，缓存按 serviceId 共享不受影响。

---

## 五、完整的装饰器链

从 LoadBalancer 的 `choose()` 到底层 DiscoveryClient 的完整调用路径：

```
choose(Request)
  │
  └─ ServiceInstanceListSupplier.get(Request)         ← 最外层 Supplier
       │
       ├─ HealthCheckServiceInstanceListSupplier       ← 过滤不健康实例（后台定时检查）
       │   └─ delegate.get()
       │
       ├─ CachingServiceInstanceListSupplier           ← 缓存（CacheFlux）
       │   └─ delegate.get()
       │
       ├─ SameInstancePreferenceServiceInstanceListSupplier  ← 同实例优先（内存态）
       │   └─ delegate.get()
       │
       ├─ RequestBasedStickySessionServiceInstanceListSupplier  ← cookie 粘性会话
       │   └─ delegate.get()
       │
       ├─ ZonePreferenceServiceInstanceListSupplier    ← 同区域优先
       │   └─ delegate.get()
       │
       ├─ SubsetServiceInstanceListSupplier            ← 确定性子集（大集群）
       │   └─ delegate.get()
       │
       ├─ WeightedServiceInstanceListSupplier          ← 权重展开
       │   └─ delegate.get()
       │
       ├─ HintBasedServiceInstanceListSupplier         ← hint 过滤（请求级）
       │   └─ delegate.get()
       │
       ├─ RetryAwareServiceInstanceListSupplier        ← 重试时剔除上次失败的实例
       │   └─ delegate.get()
       │
       └─ DiscoveryClientServiceInstanceListSupplier   ← 数据源（最内层）
            └─ DiscoveryClient.getInstances()
```

> 注意：链中的位置是有讲究的。**请求级过滤**（Hint、StickySession、SameInstancePreference）应放在**缓存/健康检查之后**——否则请求上下文会穿透缓存。**数据源级转换**（Weighted、Subset）放在靠内层的位置，先缩小/展开再过滤。上图是"所有可能的层"，实际链由 `LoadBalancerClientConfiguration` 按需组合，通常只有 3-5 层。

**这个链不是所有 Supplier 都叠加的**——实际链由 `LoadBalancerClientConfiguration` 按条件组合。它提供了多个 `@Bean` 方法（如 `zonePreferenceDiscoveryClient...`、`healthCheckDiscoveryClient...`、`weightedServiceInstanceListSupplier...`、`sameInstancePreferenceServiceInstanceListSupplier...`），每个通过自定义 `ConfigurationCondition`（如 `ZonePreferenceConfigurationCondition`、`WeightedConfigurationCondition`、`SameInstancePreferenceConfigurationCondition` 等）+ `@ConditionalOnBean` / `@ConditionalOnMissingBean` 控制激活条件。生产中使用哪个链取决于注册中心类型（Eureka/Nacos/Consul）、是否需要健康检查/权重/区域路由等。

---

## 六、面试考点

1. **ServiceInstanceListSupplier 为什么返回 `Flux<List<ServiceInstance>>` 而非 `List<ServiceInstance>`？**
   `Flux` 提供了异步能力：1）`DiscoveryClient` 是阻塞调用，需要包装为异步；2）接口层面允许实现类返回持续流（如 `ReactiveDiscoveryClient`）。但默认实现 `DiscoveryClientServiceInstanceListSupplier` 是"订阅时拉取"而非"持续推送"。

2. **get() 和 get(Request) 的区别？**
   `get(Request)` 传递请求上下文（hint、cookie 等），允许 Supplier 根据请求内容调整行为。默认实现委托给 `get()`。HintBased / StickySession / SameInstancePreference 重写 `get(Request)`。

3. **装饰器链的构建顺序和数据流向？**
   构建：base → decorator[0] → decorator[1] → ...（外层包装内层）。数据流：最外层 get() → delegate.get() → ... → 最内层。

4. **HealthCheckServiceInstanceListSupplier 如何实现后台健康检查？**
   `Flux.defer(delegate)` 拉取实例 → `repeatWhen` 周期重复 → `switchMap` 对每批实例并发做健康检查（`Flux.merge` + `onErrorResume`/`timeout` 判死）→ `replay(1).refCount(1)` 共享结果 → `afterPropertiesSet()` 主动订阅启动循环，`destroy()` 释放。

5. **SubsetServiceInstanceListSupplier 的确定性体现在哪里？**
   `Random(round)` 用 `round = instanceId / count` 作为种子。同一客户端 instanceId 不变 → round 不变 → 洗牌结果不变 → 每次选中相同子集。count 变化时 round 平滑过渡，避免客户端集体切换子集（防抖动）。

6. **Hint 路由和 Zone 路由的降级策略？**
   都是"优先而非强制"：找到匹配实例就用，找不到就静默返回全部实例，不抛异常。

7. **callGetWithRequestOnDelegates 的作用？**
   控制是否把 Request 传递给内层 delegate。默认 true；如果链中有 Caching/HealthCheck 且位于请求感知装饰器之后，可设 false 避免缓存被请求上下文穿透。
