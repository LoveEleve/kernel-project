# D6: LoadBalancerLifecycle 生命周期

> 源码版本: spring-cloud-commons **v4.1.4**（tag；运行时对齐 SC 2023.0.3；4.3.2/5.x 新特性以注记标出）
> 源码路径: `spring-cloud-commons/.../client/loadbalancer/` + `spring-cloud-loadbalancer/.../stats/`
> 覆盖文件: `LoadBalancerLifecycle.java`(64行)、`MicrometerStatsLoadBalancerLifecycle.java`(121行)、`LoadBalancerTags.java`(159行)、`LoadBalancerStatsAutoConfiguration.java`(45行)、`CompletionContext.java`(111行)、`TimedRequestContext.java`(31行)

---

## 一、接口定义：四阶段生命周期

`LoadBalancerLifecycle` 是 LoadBalancer 的**可观测性扩展点**——在负载均衡的关键节点插入回调，让外部观察者（指标采集、链路追踪、日志）感知请求的生命周期。

```java
public interface LoadBalancerLifecycle<RC, RES, T> {

    // ① 是否处理当前类型的请求
    default boolean supports(Class requestContextClass, Class responseClass, Class serverTypeClass) {
        return true;
    }

    // ② 负载均衡开始前（选择实例之前）
    void onStart(Request<RC> request);

    // ③ 选中实例后、发出实际请求前
    void onStartRequest(Request<RC> request, Response<T> lbResponse);

    // ④ 请求结束后（成功/失败/丢弃）
    void onComplete(CompletionContext<RES, T, RC> completionContext);
}
```

**三个泛型参数**（理解接口的关键）：

| 泛型 | 含义 | Micrometer 实现中的值 |
|------|------|----------------------|
| `RC` | Request 上下文类型 | `Object` |
| `RES` | 客户端响应类型（CompletionContext 的 clientResponse） | `Object` |
| `T` | 服务器类型（ServiceInstance） | `ServiceInstance` |

`MicrometerStatsLoadBalancerLifecycle implements LoadBalancerLifecycle<Object, Object, ServiceInstance>` —— 对上下文和响应类型不敏感（用 Object 通吃），只关心服务器类型是 `ServiceInstance`。

---

## 二、supports() — 类型过滤机制

```java
default boolean supports(Class requestContextClass, Class responseClass, Class serverTypeClass) {
    return true;
}
```

**默认实现返回 true**（所有请求都处理）。自定义 Lifecycle 可以通过它声明自己能处理哪些类型组合。

调用方（D5 的 `LoadBalancerLifecycleValidator.getSupportedLifecycleProcessors`）按 `supports()` 过滤：

```java
// D5 中 BlockingLoadBalancerClient 的调用
LoadBalancerLifecycleValidator.getSupportedLifecycleProcessors(
        loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
        DefaultRequestContext.class,   // requestContextClass
        Object.class,                  // responseClass（clientResponse）
        ServiceInstance.class);        // serverTypeClass
```

`MicrometerStatsLoadBalancerLifecycle` 的过滤逻辑：

```java
@Override
public boolean supports(Class requestContextClass, Class responseClass, Class serverTypeClass) {
    return ServiceInstance.class.isAssignableFrom(serverTypeClass);  // 只处理 ServiceInstance 类型的服务器
}
```

**`isAssignableFrom` 而非 `equals`**：`ServiceInstance.class.isAssignableFrom(X)` 表示 X 是 ServiceInstance 或其子类。这意味着将来出现 ServiceInstance 子类（如带扩展字段的实例），Micrometer 生命周期仍然生效。

**设计价值**：`LoadBalancerClientFactory.getInstances()` 从子上下文获取所有 `LoadBalancerLifecycle` Bean，每个 Lifecycle 通过 `supports()` 自报能力，调用方按需收集——这是**类型驱动的 SPI 过滤**，避免在调用方硬编码条件。

---

## 三、MicrometerStatsLoadBalancerLifecycle — 指标实现

**位置**: `stats/MicrometerStatsLoadBalancerLifecycle.java`（157 行）

这是 LoadBalancer 官方自带的 Micrometer 指标采集实现，也是理解四阶段回调的最佳案例。

### 3.1 字段与构造

```java
public class MicrometerStatsLoadBalancerLifecycle implements LoadBalancerLifecycle<Object, Object, ServiceInstance> {

    private final MeterRegistry meterRegistry;
    private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;
    // 每个实例的"进行中请求数"计数器（Gauge 的底层）
    private final ConcurrentHashMap<ServiceInstance, AtomicLong> activeRequestsPerInstance = new ConcurrentHashMap<>();

    public MicrometerStatsLoadBalancerLifecycle(MeterRegistry meterRegistry,
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) {
        this.meterRegistry = meterRegistry;
        this.loadBalancerFactory = loadBalancerFactory;
    }
    // ...
}
```

### 3.2 onStart — 空实现

```java
@Override
public void onStart(Request<Object> request) {
    // do nothing
}
```

选择实例前没有需要采集的指标（还没选实例，也没有耗时可记）——空实现。

### 3.3 onStartRequest — 记录开始时间 + 活跃请求数

```java
@Override
public void onStartRequest(Request<Object> request, Response<ServiceInstance> lbResponse) {
    // 1. 记录请求开始时间（用于后续计时）
    if (request != null && request.getContext() instanceof TimedRequestContext) {
        ((TimedRequestContext) request.getContext()).setRequestStartTime(System.nanoTime());
    }
    // 2. 无实例（EmptyResponse）→ 不统计
    if (lbResponse == null || !lbResponse.hasServer()) {
        return;
    }
    ServiceInstance serviceInstance = lbResponse.getServer();
    // 3. 每个实例维护一个活跃请求计数器 + 注册 Gauge
    AtomicLong activeRequestsCounter = activeRequestsPerInstance.computeIfAbsent(serviceInstance, instance -> {
        AtomicLong createdCounter = new AtomicLong();
        Gauge.builder("loadbalancer.requests.active", () -> createdCounter)
            .tags(buildServiceInstanceTags(serviceInstance))
            .register(meterRegistry);
        return createdCounter;
    });
    // 4. 活跃请求数 +1
    activeRequestsCounter.incrementAndGet();
}
```

**两个关键动作**：

1. **记录请求开始时间**——写入 `TimedRequestContext`（D5 分析过的上下文继承体系的顶层接口）。注意条件：上下文必须是 `TimedRequestContext`（`RequestDataContext` / `DefaultRequestContext` 都是）。开始时间存在**请求上下文里**而非实例字段——保证并发请求各自计时互不干扰。

2. **活跃请求 Gauge**——`loadbalancer.requests.active`，按实例维度（serviceId + instanceId + host + port 标签）。`Gauge.builder("loadbalancer.requests.active", () -> createdCounter)` 的**妙处**：Lambda 捕获 `createdCounter` 引用，后续 `incrementAndGet()/decrementAndGet()` 直接反映到 Gauge 值——Gauge 每次被 Prometheus 抓取时都读取最新值。

**隐患（map key 依赖 equals/hashCode）**：`activeRequestsPerInstance` 以 `ServiceInstance` 为 key——依赖具体实现的 `equals()/hashCode()`。若注册中心返回的实例对象未实现值相等（如 Nacos 的 `NacosServiceInstance`），每次注册中心刷新都会产生"新对象"，`computeIfAbsent` 会为同一实例重复注册 Gauge（同标签的 Gauge 在 Micrometer 中是幂等的，不会报错，但 map 会累积内存）。实际 Nacos 实现基于 host+port+serviceId 等字段实现了 equals，风险可控，但自定义 ServiceInstance 实现时需注意。

### 3.4 onComplete — 结束指标采集

```java
@Override
public void onComplete(CompletionContext<Object, ServiceInstance, Object> completionContext) {
    // 1. 取出选中实例
    ServiceInstance serviceInstance = null;
    Response<ServiceInstance> loadBalancerResponse = completionContext.getLoadBalancerResponse();
    if (loadBalancerResponse != null) {
        serviceInstance = loadBalancerResponse.getServer();
    }
    // 2. 获取属性（构建标签用）
    LoadBalancerProperties properties = serviceInstance != null
            ? loadBalancerFactory.getProperties(serviceInstance.getServiceId())
            : loadBalancerFactory.getProperties(null);
    //      ↑ serviceInstance 为 null（DISCARD 场景）时，getProperties(null) 返回默认配置
    //        （D3 分析过：getProperties 对无专属配置的 serviceId 返回 properties 默认对象）
    LoadBalancerTags loadBalancerTags = new LoadBalancerTags(properties);
    long requestFinishedTimestamp = System.nanoTime();

    // 3. DISCARD：丢弃计数，直接返回
    if (CompletionContext.Status.DISCARD.equals(completionContext.status())) {
        Counter.builder("loadbalancer.requests.discard")
            .tags(loadBalancerTags.buildDiscardedRequestTags(completionContext))
            .register(meterRegistry)
            .increment();
        return;
    }

    // 4. 活跃请求数 -1（正常结束）
    AtomicLong activeRequestsCounter = activeRequestsPerInstance.get(serviceInstance);
    if (activeRequestsCounter != null) {
        activeRequestsCounter.decrementAndGet();
    }

    // 5. 计时：FAILED → failed Timer；其他 → success Timer
    Request<Object> lbRequest = completionContext.getLoadBalancerRequest();
    if (lbRequest == null) {
        return;
    }
    Object loadBalancerRequestContext = lbRequest.getContext();
    if (requestHasBeenTimed(loadBalancerRequestContext)) {
        if (CompletionContext.Status.FAILED.equals(completionContext.status())) {
            Timer.builder("loadbalancer.requests.failed")
                .tags(loadBalancerTags.buildFailedRequestTags(completionContext))
                .register(meterRegistry)
                .record(requestFinishedTimestamp
                        - ((TimedRequestContext) loadBalancerRequestContext).getRequestStartTime(),
                        TimeUnit.NANOSECONDS);
            return;
        }
        Timer.builder("loadbalancer.requests.success")
            .tags(loadBalancerTags.buildSuccessRequestTags(completionContext))
            .register(meterRegistry)
            .record(requestFinishedTimestamp
                    - ((TimedRequestContext) loadBalancerRequestContext).getRequestStartTime(),
                    TimeUnit.NANOSECONDS);
    }
}

private boolean requestHasBeenTimed(Object loadBalancerRequestContext) {
    return loadBalancerRequestContext instanceof TimedRequestContext
            && (((TimedRequestContext) loadBalancerRequestContext).getRequestStartTime() != 0L);
}
```

**分支逻辑**：

```
onComplete(context)
  │
  ├─ status == DISCARD → loadbalancer.requests.discard Counter +1 → return
  │
  ├─ 活跃请求数 -1（DISCARD 不递减，因为 onStartRequest 时也没递增——无实例时 hasServer()=false 直接 return 了）
  │
  └─ requestHasBeenTimed?
        ├─ 否（上下文非 TimedRequestContext 或未记录开始时间）→ 不计时
        └─ 是
              ├─ status == FAILED → loadbalancer.requests.failed Timer.record(耗时)
              └─ 其他（SUCCESS）→ loadbalancer.requests.success Timer.record(耗时)
```

**细节**：
- 耗时 = `onComplete 时间 - onStartRequest 记录的开始时间`（纳秒）
- `requestHasBeenTimed` 双重检查：上下文类型 + 开始时间非 0（防止 onStartRequest 没跑过就 onComplete 的异常路径）
- DISCARD 时活跃计数不减——因为 DISCARD 发生在 choose 失败（无实例），onStartRequest 从未执行，计数从未递增，**对称性正确**

---

## 四、LoadBalancerTags — 指标标签体系

**位置**: `stats/LoadBalancerTags.java`（194 行，包级私有）

负责构建所有指标的标签。三类标签构建器对应三种指标：

### 4.1 实例标签（所有指标共用）

```java
static Iterable<Tag> buildServiceInstanceTags(ServiceInstance serviceInstance) {
    return Tags.of(valueOrUnknown("serviceId", serviceInstance.getServiceId()),
            valueOrUnknown("serviceInstance.instanceId", serviceInstance.getInstanceId()),
            valueOrUnknown("serviceInstance.host", serviceInstance.getHost()),
            valueOrUnknown("serviceInstance.port", String.valueOf(serviceInstance.getPort())));
}
```

四个维度：`serviceId`、`serviceInstance.instanceId`、`serviceInstance.host`、`serviceInstance.port`。前三个是 String 可 null，统一替换为 `UNKNOWN`；`port` 是 int 永远非 null（`String.valueOf(0)` 会得到 `"0"`）——所以 port 标签不会出现 UNKNOWN。

### 4.2 成功标签（loadbalancer.requests.success）

```java
Iterable<Tag> buildSuccessRequestTags(CompletionContext<Object, ServiceInstance, Object> completionContext) {
    // 实例标签 + method + uri + outcome + status
    // method/uri 来自 ResponseData.getRequestData()（HTTP 方法 + 路径）
    // outcome 来自状态码分类（Actuator Outcome 逻辑）：SUCCESS/CLIENT_ERROR/SERVER_ERROR/...
    // status 是 HTTP 状态码
}
```

**URI 的处理细节**（`getPath`）：

```java
private String getPath(RequestData requestData) {
    if (!properties.getStats().isIncludePath()) {
        return UNKNOWN;    // stats.include-path=false 时不记录路径（高基数问题）
    }
    // 优先用 URI 模板（WebClient/RestClient 的 uriTemplate 属性），避免真实路径的高基数
    Optional<Object> uriTemplateValue = ...  // 查找 WebClient.uriTemplate / RestClient.uriTemplate 属性
    return uriTemplateValue.map(...).orElseGet(() -> requestData.getUrl().getPath());
}
```

**高基数防护**：如果请求路径是 `/orders/123` 这种动态路径，指标会爆炸（每个订单 ID 一个时间序列）。方案：
1. `spring.cloud.loadbalancer.stats.micrometer.include-path=false` 直接不记录路径
2. 优先使用 **URI 模板**（`/orders/{id}`）——从 `WebClient.uriTemplate` / `RestClient.uriTemplate` 请求属性中读取，模板路径的基数可控

`outcome` 分类逻辑复制自 Actuator 的 `Outcome` 类（源码注释说明"为避免引入 Actuator 依赖"）：

```java
public static String forStatus(int status) {
    if (status >= 100 && status < 200) return "INFORMATIONAL";
    if (status >= 200 && status < 300) return "SUCCESS";
    if (status >= 300 && status < 400) return "REDIRECTION";
    if (status >= 400 && status < 500) return "CLIENT_ERROR";
    if (status >= 500 && status < 600) return "SERVER_ERROR";
    return UNKNOWN;
}
```

### 4.3 失败标签（loadbalancer.requests.failed）

```java
Iterable<Tag> buildFailedRequestTags(CompletionContext<Object, ServiceInstance, Object> completionContext) {
    // 实例标签 + exception（异常类简单名）+ method + uri
    // exception: 无异常时 "None"
}
```

### 4.4 丢弃标签（loadbalancer.requests.discard）

```java
Iterable<Tag> buildDiscardedRequestTags(CompletionContext<Object, ServiceInstance, Object> completionContext) {
    // 注意：丢弃请求没有实例（choose 失败），所以用 method + uri + serviceId（来自 RequestData 的 URL host）
}
```

**DISCARD 标签的差异**：没有实例标签（实例不存在），改用 `serviceId`——从请求 URL 的 host 提取（`requestData.getUrl().getHost()`）。**注意语义**：负载均衡前请求 URL 的 host 就是服务名（`LoadBalancerInterceptor` 正是从 URI host 提取 serviceId 的），所以这里的 serviceId 标签实际是"请求目标服务名"。

**类型细节**：`buildDiscardedRequestTags` 中 `RequestData requestData = requestDataContext.getClientRequest()` 能编译，依赖 `RequestDataContext` **重写了 `getClientRequest()`**（返回类型收窄为 `RequestData`，父类 `DefaultRequestContext` 返回 `Object`）。这与 D5 的 `buildRequestContext` 构造 `RequestDataContext(requestData, hint)` 时把 RequestData 存入 clientRequest 字段相呼应。

---

## 五、指标清单汇总

| 指标名 | 类型 | 触发时机 | 标签 |
|--------|------|---------|------|
| `loadbalancer.requests.active` | Gauge | onStartRequest（+1）/ onComplete 的 SUCCESS+FAILED（-1） | serviceId, instanceId, host, port |
| `loadbalancer.requests.success` | Timer | onComplete（SUCCESS） | 实例 + method, uri, outcome, status |
| `loadbalancer.requests.failed` | Timer | onComplete（FAILED） | 实例 + exception, method, uri |
| `loadbalancer.requests.discard` | Counter | onComplete（DISCARD） | method, uri, serviceId |

---

## 六、自动配置：LoadBalancerStatsAutoConfiguration

**位置**: `config/LoadBalancerStatsAutoConfiguration.java`（48 行）

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry.class)                                        // Micrometer 在 classpath
@ConditionalOnProperty(value = "spring.cloud.loadbalancer.stats.micrometer.enabled", havingValue = "true")
public class LoadBalancerStatsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public MicrometerStatsLoadBalancerLifecycle micrometerStatsLifecycle(MeterRegistry meterRegistry,
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) {
        return new MicrometerStatsLoadBalancerLifecycle(meterRegistry, loadBalancerFactory);
    }
}
```

**激活条件**（三重）：
1. `MeterRegistry` 类在 classpath（`@ConditionalOnClass`）
2. `MeterRegistry` Bean 存在（`@ConditionalOnBean`）
3. **`spring.cloud.loadbalancer.stats.micrometer.enabled=true` 显式开启**（`@ConditionalOnProperty`，无 matchIfMissing——**默认关闭**）

默认关闭的原因：指标采集有性能开销，且 `include-path` 默认 true 有高基数风险，需要用户显式开启并知情。

**注意**：这个 Bean 注册在**主应用上下文**（不是子上下文），但 D5 中 `LoadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class)` 是从**子上下文**获取——子上下文继承父上下文的 Bean（D3 分析过 setParent），所以能拿到。

---

## 七、完整执行时序（RestTemplate 场景）

```
LoadBalancerInterceptor
    │
    ▼
BlockingLoadBalancerClient.execute(serviceId, request)
    │
    ├─ 收集支持的生命周期处理器（supports 过滤）
    │
    ├─ onStart(lbRequest)                          ← Micrometer: 空实现
    │
    ├─ choose(serviceId) → ServiceInstance
    │
    ├─ onStartRequest(lbRequest, response)         ← Micrometer:
    │      │                                         1. TimedRequestContext.setRequestStartTime(nanoTime)
    │      │                                         2. loadbalancer.requests.active +1（Gauge）
    │
    ├─ request.apply(instance)                     ← 真正发 HTTP 请求
    │
    └─ onComplete(completionContext)               ← Micrometer:
           │                                         active -1
           ├─ DISCARD → discard Counter +1（无实例场景）
           ├─ SUCCESS → success Timer.record(耗时)
           └─ FAILED  → failed Timer.record(耗时)
```

---

## 八、自定义 Lifecycle 示例

```java
@Component
public class LoggingLoadBalancerLifecycle
        implements LoadBalancerLifecycle<Object, Object, ServiceInstance> {

    private static final Logger log = LoggerFactory.getLogger(LoggingLoadBalancerLifecycle.class);

    @Override
    public boolean supports(Class requestContextClass, Class responseClass, Class serverTypeClass) {
        return ServiceInstance.class.isAssignableFrom(serverTypeClass);
    }

    @Override
    public void onStart(Request<Object> request) {
        log.info("LB start: {}", request);
    }

    @Override
    public void onStartRequest(Request<Object> request, Response<ServiceInstance> lbResponse) {
        if (lbResponse.hasServer()) {
            log.info("LB selected: {}", lbResponse.getServer().getInstanceId());
        }
    }

    @Override
    public void onComplete(CompletionContext<Object, ServiceInstance, Object> completionContext) {
        log.info("LB complete: status={}", completionContext.status());
    }
}
```

注册后自动被 `LoadBalancerLifecycleValidator` 收集（supports 过滤通过即可），无需额外配置。

---

## 九、面试考点

1. **LoadBalancerLifecycle 的四个回调顺序？**
   `onStart`（选择前）→ `onStartRequest`（选中后、发请求前）→ `onComplete`（结束后）。`supports` 用于过滤，不算回调。

2. **supports() 的类型过滤如何工作？**
   `LoadBalancerLifecycleValidator` 按 `lifecycle.supports(requestContextClass, responseClass, serverTypeClass)` 过滤子上下文的所有 Lifecycle Bean。Micrometer 实现用 `ServiceInstance.class.isAssignableFrom(serverTypeClass)` 只处理 ServiceInstance 类型。

3. **如何给 LoadBalancer 做指标采集？**
   `spring.cloud.loadbalancer.stats.micrometer.enabled=true` 开启 `MicrometerStatsLoadBalancerLifecycle`，产生 4 个指标：active（Gauge）、success/failed（Timer）、discard（Counter）。

4. **活跃请求 Gauge 如何实现？**
   `ConcurrentHashMap<ServiceInstance, AtomicLong>` 按实例存计数器，`Gauge.builder(name, () -> counter)` 用 Lambda 引用——Prometheus 抓取时读取 AtomicLong 最新值。

5. **URI 高基数问题如何防护？**
   `include-path=false` 不记录路径；优先用 WebClient/RestClient 的 uriTemplate 属性（模板路径 `/orders/{id}`），避免动态路径产生海量时间序列。

6. **与 Filter/Interceptor 的区别？**
   Filter 在 Servlet 容器层、Interceptor 在 MVC 层、LoadBalancerLifecycle 在**负载均衡选择**层——三者观察的粒度不同。LoadBalancerLifecycle 能看到"选了哪个实例"这个关键信息。
