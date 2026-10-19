# D5: BlockingLoadBalancerClient 阻塞客户端

> 源码版本: spring-cloud-commons **v4.1.4**（tag；运行时对齐 SC 2023.0.3；4.3.2/5.x 新特性以注记标出）
> 源码路径: `spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/`
> 覆盖文件: `blocking/client/BlockingLoadBalancerClient.java`(177行)、`config/BlockingLoadBalancerClientAutoConfiguration.java`(95行)、`LoadBalancerRequestAdapter.java`(46行)、`LoadBalancerUriTools.java`(118行)、`LoadBalancerLifecycleValidator.java`(49行)、`LoadBalancerClient.java`(70行)、`ServiceInstanceChooser.java`(47行)、`HttpRequestLoadBalancerRequest.java`(31行)、`CompletionContext.java`(111行)、`ResponseData.java`(131行)、`DefaultRequestContext.java`(76行)、`RequestDataContext.java`(49行)、`DefaultRequest.java`(73行)

---

## 一、定位：阻塞世界的入口

Spring Cloud LoadBalancer 的**内核是响应式的**（`ReactorLoadBalancer.choose()` 返回 `Mono`），但大部分应用（RestTemplate、Feign）是**阻塞编程模型**。`BlockingLoadBalancerClient` 就是两者的桥接层：

```
阻塞调用方（@LoadBalanced RestTemplate / Feign）
    │
    ▼
LoadBalancerInterceptor → loadBalancer.execute(serviceId, request)
    │
    ▼
BlockingLoadBalancerClient   ← 本文主角（阻塞 API）
    │  Mono.from(...).block()  ← 桥接点：阻塞等待响应式结果
    ▼
ReactorLoadBalancer.choose()   ← 响应式内核
    │
    ▼
ServiceInstanceListSupplier 链 → 实例选择
```

它是 `LoadBalancerClient` 接口（`org.springframework.cloud.client.loadbalancer`）的**默认实现**。

```java
@SuppressWarnings({ "unchecked", "rawtypes" })
public class BlockingLoadBalancerClient implements LoadBalancerClient {

    private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerClientFactory;

    public BlockingLoadBalancerClient(ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerClientFactory) {
        this.loadBalancerClientFactory = loadBalancerClientFactory;
    }
    // ...
}
```

只依赖 `ReactiveLoadBalancer.Factory`（即 D3 的 `LoadBalancerClientFactory`）——通过它获取 LoadBalancer 实例、属性和子上下文 Bean。

---

## 二、自动配置：BlockingLoadBalancerClientAutoConfiguration

**位置**: `config/BlockingLoadBalancerClientAutoConfiguration.java`（95 行）

```java
@Configuration(proxyBeanMethods = false)
@LoadBalancerClients
@AutoConfigureAfter(LoadBalancerAutoConfiguration.class)          // 晚于 SCL 自动配置
@AutoConfigureBefore({ org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration.class })
@Conditional(BlockingRestClassesPresentCondition.class)          // 阻塞 Rest 类存在（RestTemplate/RestClient）
@ConditionalOnProperty(value = "spring.cloud.loadbalancer.enabled", havingValue = "true", matchIfMissing = true)
public class BlockingLoadBalancerClientAutoConfiguration {

    @Bean
    @ConditionalOnBean(LoadBalancerClientFactory.class)
    @ConditionalOnMissingBean
    public LoadBalancerClient blockingLoadBalancerClient(LoadBalancerClientFactory loadBalancerClientFactory) {
        return new BlockingLoadBalancerClient(loadBalancerClientFactory);
    }

    @Bean
    @ConditionalOnBean(LoadBalancerClientFactory.class)
    @ConditionalOnMissingBean(LoadBalancerServiceInstanceCookieTransformer.class)
    public LoadBalancerServiceInstanceCookieTransformer loadBalancerServiceInstanceCookieTransformer(
            LoadBalancerClientFactory loadBalancerClientFactory) {
        return new LoadBalancerServiceInstanceCookieTransformer(loadBalancerClientFactory);
    }

    @Bean
    @ConditionalOnMissingBean(XForwardedHeadersTransformer.class)
    @ConditionalOnBean(LoadBalancerClientFactory.class)
    public XForwardedHeadersTransformer xForwarderHeadersTransformer(
            LoadBalancerClientFactory loadBalancerClientFactory) {
        return new XForwardedHeadersTransformer(loadBalancerClientFactory);
    }

    @Configuration
    @ConditionalOnClass(RetryTemplate.class)
    @EnableConfigurationProperties(LoadBalancerClientsProperties.class)
    protected static class BlockingLoadBalancerRetryConfig {

        @Bean
        @ConditionalOnMissingBean
        LoadBalancedRetryFactory loadBalancedRetryFactory(
                ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) {
            return new BlockingLoadBalancedRetryFactory(loadBalancerFactory);
        }
    }
}
```

四个 Bean + 一个重试配置：

| Bean | 作用 |
|------|------|
| `blockingLoadBalancerClient` | 本文主角 |
| `loadBalancerServiceInstanceCookieTransformer` | D2 4.8 分析的粘性 cookie 写入（请求头） |
| `xForwarderHeadersTransformer` | X-Forwarded 头处理（阻塞版） |
| `loadBalancedRetryFactory`（条件） | 阻塞重试工厂，需要 `RetryTemplate` classpath |

**激活条件链**：
1. `@Conditional(BlockingRestClassesPresentCondition)` — 必须有阻塞 Rest 类（`RestTemplate` 或 `RestClient` 在 classpath）
2. `@ConditionalOnProperty(spring.cloud.loadbalancer.enabled, matchIfMissing=true)` — 默认开启
3. `@AutoConfigureAfter(LoadBalancerAutoConfiguration)` — 依赖 `LoadBalancerClientFactory`（D3 分析过）

**注意两个同名 `LoadBalancerAutoConfiguration`**：

| 全限定名 | 模块 | 职责 |
|----------|------|------|
| `org.springframework.cloud.loadbalancer.config.LoadBalancerAutoConfiguration` | spring-cloud-loadbalancer | D3 分析：创建 `LoadBalancerClientFactory`（本类 `@AutoConfigureAfter` 的对象） |
| `org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration` | spring-cloud-commons | **@LoadBalanced 拦截器注入**（D7 主题）：创建 `LoadBalancerInterceptor`、`@LoadBalanced RestTemplate` 修饰（本类 `@AutoConfigureBefore` 的对象） |

同样，`XForwardedHeadersTransformer` 也有两个同名类：`core` 包（Reactive，D3 的 `ReactiveSupportConfiguration` 创建）和 `blocking` 包（本类创建）——分别处理响应式和阻塞场景的 X-Forwarded 头。

---

## 三、核心流程：execute(serviceId, request)

`LoadBalancerClient` 接口的核心方法（注意 `choose` 系列定义在父接口 `ServiceInstanceChooser`，`LoadBalancerClient` 继承之）：

```java
public interface ServiceInstanceChooser {
    ServiceInstance choose(String serviceId);
    <T> ServiceInstance choose(String serviceId, Request<T> request);
}

public interface LoadBalancerClient extends ServiceInstanceChooser {
    <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException;
    <T> T execute(String serviceId, ServiceInstance serviceInstance, LoadBalancerRequest<T> request) throws IOException;
    URI reconstructURI(ServiceInstance instance, URI original);
}
```

### 3.1 execute(serviceId, request) — 完整流程

```java
@Override
public <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException {
    String hint = getHint(serviceId);
    // 1. 把 LoadBalancerRequest 适配为 Request（携带上下文）
    LoadBalancerRequestAdapter<T, TimedRequestContext> lbRequest = new LoadBalancerRequestAdapter<>(request,
            buildRequestContext(request, hint));
    // 2. 获取支持的生命周期处理器（按类型过滤）
    Set<LoadBalancerLifecycle> supportedLifecycleProcessors = getSupportedLifecycleProcessors(serviceId);
    // 3. 生命周期：onStart
    supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
    // 4. 选择实例
    ServiceInstance serviceInstance = choose(serviceId, lbRequest);
    if (serviceInstance == null) {
        // 5a. 无实例 → onComplete(DISCARD) + 抛异常
        supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
            .onComplete(new CompletionContext<>(CompletionContext.Status.DISCARD, lbRequest, new EmptyResponse())));
        throw new IllegalStateException("No instances available for " + serviceId);
    }
    // 5b. 有实例 → 委托给另一个 execute 重载
    return execute(serviceId, serviceInstance, lbRequest);
}
```

**六步流程**：

```
① 构建请求上下文（hint + RequestData/DefaultRequestContext）
② 收集生命周期处理器
③ onStart(lbRequest)
④ choose(serviceId) → ServiceInstance
      ├─ null → onComplete(DISCARD) + IllegalStateException("No instances available for ...")
      └─ 有值 → execute(serviceId, serviceInstance, lbRequest)
```

### 3.2 buildRequestContext — 上下文构建

hint 的来源（`getHint` 方法）：

```java
private String getHint(String serviceId) {
    LoadBalancerProperties properties = loadBalancerClientFactory.getProperties(serviceId);
    String defaultHint = properties.getHint().getOrDefault("default", "default");  // 全局默认 hint
    String hintPropertyValue = properties.getHint().get(serviceId);                // 客户端专属 hint
    return hintPropertyValue != null ? hintPropertyValue : defaultHint;
}
```

hint 配置层级：`spring.cloud.loadbalancer.hint.<serviceId>` 优先，否则 `spring.cloud.loadbalancer.hint.default`，最后兜底字符串 `"default"`。hint 会随上下文传给 `HintBasedServiceInstanceListSupplier`（D2 4.6）。

```java
private <T> TimedRequestContext buildRequestContext(LoadBalancerRequest<T> delegate, String hint) {
    if (delegate instanceof HttpRequestLoadBalancerRequest) {
        // HTTP 请求（RestTemplate/Feign）→ 包装为 RequestData
        HttpRequest request = ((HttpRequestLoadBalancerRequest) delegate).getHttpRequest();
        if (request != null) {
            RequestData requestData = new RequestData(request);
            return new RequestDataContext(requestData, hint);
        }
    }
    // 非 HTTP 请求 → 默认上下文
    return new DefaultRequestContext(delegate, hint);
}
```

**关键分支**：请求是否携带 HTTP 信息（`HttpRequestLoadBalancerRequest`）：
- **是**（RestTemplate/Feign 场景）→ `RequestDataContext`（含 `RequestData`：HTTP 方法、URI、头、cookie）——供 `HintBasedServiceInstanceListSupplier`、`RequestBasedStickySessionServiceInstanceListSupplier` 等请求感知装饰器使用（D2 4.6/4.8）
- **否** → `DefaultRequestContext`（携带 delegate 引用 + hint）

**上下文继承体系**（理解 `TimedRequestContext` 返回类型的钥匙）：

```
TimedRequestContext（接口：getRequestStartTime/setRequestStartTime）
    ↑
HintRequestContext（实现 TimedRequestContext；字段：hint 默认 "default"、requestStartTime）
    ↑
DefaultRequestContext（extends HintRequestContext；增加：clientRequest 字段 = delegate）
    ↑
RequestDataContext（extends DefaultRequestContext；增加：requestData 字段 = RequestData）
```

`buildRequestContext` 返回 `TimedRequestContext` 正是因为两个分支（`RequestDataContext` / `DefaultRequestContext`）都是 `HintRequestContext` 的后代，都实现了 `TimedRequestContext`。

### 3.3 execute(serviceId, serviceInstance, request) — 实际执行

```java
@Override
public <T> T execute(String serviceId, ServiceInstance serviceInstance, LoadBalancerRequest<T> request)
        throws IOException {
    if (serviceInstance == null) {
        throw new IllegalArgumentException("Service Instance cannot be null, serviceId: " + serviceId);
    }
    DefaultResponse defaultResponse = new DefaultResponse(serviceInstance);
    Set<LoadBalancerLifecycle> supportedLifecycleProcessors = getSupportedLifecycleProcessors(serviceId);
    Request lbRequest = request instanceof Request ? (Request) request : new DefaultRequest<>();
    //      ↑ 防御性判断：3.1 传入的 LoadBalancerRequestAdapter extends DefaultRequest implements Request，
    //        恒为 true；此分支保护直接调用本重载且 request 不是 Request 的情况
    // 生命周期：onStartRequest（选中实例后）
    supportedLifecycleProcessors
        .forEach(lifecycle -> lifecycle.onStartRequest(lbRequest, new DefaultResponse(serviceInstance)));
    try {
        T response = request.apply(serviceInstance);   // ← 真正发出 HTTP 请求
        Object clientResponse = getClientResponse(response);
        // 生命周期：onComplete(SUCCESS)
        supportedLifecycleProcessors
            .forEach(lifecycle -> lifecycle.onComplete(new CompletionContext<>(CompletionContext.Status.SUCCESS,
                    lbRequest, defaultResponse, clientResponse)));
        return response;
    } catch (IOException iOException) {
        // 生命周期：onComplete(FAILED, IOException)
        supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onComplete(
                new CompletionContext<>(CompletionContext.Status.FAILED, iOException, lbRequest, defaultResponse)));
        throw iOException;
    } catch (Exception exception) {
        // 生命周期：onComplete(FAILED, Exception) + 重抛运行时异常
        supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onComplete(
                new CompletionContext<>(CompletionContext.Status.FAILED, exception, lbRequest, defaultResponse)));
        ReflectionUtils.rethrowRuntimeException(exception);
    }
    return null;   // 理论上不可达（rethrowRuntimeException 一定抛）
}
```

**生命周期在请求执行前后的完整闭环**：

```
onStart(lbRequest)          ← 选择前（3.1 中）
    ↓
choose()                    ← 选择实例
    ↓
onStartRequest(lbRequest, response)  ← 选中实例后、发请求前
    ↓
request.apply(serviceInstance)       ← 真正发 HTTP 请求
    ↓
onComplete(SUCCESS | FAILED, ...)    ← 请求结束（成功/失败/丢弃）
```

**三个完成状态**（`CompletionContext.Status`）：

| 状态 | 触发场景 |
|------|---------|
| `DISCARD` | 无实例可用（3.1 步骤 5a） |
| `SUCCESS` | `request.apply()` 正常返回 |
| `FAILED` | 抛 IOException 或任意异常 |

**异常处理策略**：
- `IOException` → 记录 FAILED 后原样抛出
- 其他 `Exception` → 记录 FAILED 后 `ReflectionUtils.rethrowRuntimeException()`。该方法按类型分流：`RuntimeException` 原样抛出、`Error` 原样抛出、**checked exception 包装为 `UndeclaredThrowableException`**（RuntimeException 子类）——`execute()` 方法签名只声明 `throws IOException`，checked exception 无法直接抛出，必须包装

`getClientResponse()` 把 `ClientHttpResponse` 转为 `ResponseData`（携带状态码/头），供生命周期处理器做指标采集：

```java
private <T> Object getClientResponse(T response) {
    ClientHttpResponse clientHttpResponse = null;
    if (response instanceof ClientHttpResponse) {
        clientHttpResponse = (ClientHttpResponse) response;   // RestTemplate 的请求回调返回 ClientHttpResponse
    }
    if (clientHttpResponse != null) {
        try {
            return new ResponseData(clientHttpResponse, null);  // 包装为 ResponseData（状态码+头）
        } catch (IOException ignored) {
        }
    }
    return response;   // 非 ClientHttpResponse（如 WebClient 场景）或转换失败 → 原样返回
}
```

---

## 四、choose() — Mono.block() 桥接

```java
@Override
public ServiceInstance choose(String serviceId) {
    return choose(serviceId, REQUEST);   // REQUEST = ReactiveLoadBalancer.REQUEST（默认空请求）
}

@Override
public <T> ServiceInstance choose(String serviceId, Request<T> request) {
    ReactiveLoadBalancer<ServiceInstance> loadBalancer = loadBalancerClientFactory.getInstance(serviceId);
    if (loadBalancer == null) {
        return null;   // 子上下文不存在 → 返回 null
    }
    Response<ServiceInstance> loadBalancerResponse = Mono.from(loadBalancer.choose(request)).block();
    if (loadBalancerResponse == null) {
        return null;
    }
    return loadBalancerResponse.getServer();   // 注意：可能返回 null（EmptyResponse）
}
```

**桥接细节**：

1. `loadBalancerClientFactory.getInstance(serviceId)` — D3 分析过：从子上下文获取 `ReactorServiceInstanceLoadBalancer` Bean，**懒创建子上下文**（首次调用）
2. `loadBalancer.choose(request)` 返回 `Mono<Response<ServiceInstance>>`
3. `Mono.from(...).block()` — **阻塞当前线程**等待响应式结果。这是整个桥接的核心——把响应式内核的异步结果同步化

**注意 `block()` 无超时**：`Mono.block()` 无参版本**无限等待**——如果 `choose()` 的 Mono 永不完成（如注册中心长时间无响应），调用线程会一直阻塞。实际不会发生，因为 `DiscoveryClientServiceInstanceListSupplier`（D2 4.1）内部有 `timeout()` 降级（默认 30s，超时返回空列表），`choose()` 的 Mono 最终总会完成。但自定义 LoadBalancer/Supplier 时需自行保证这一点。
4. 返回 `loadBalancerResponse.getServer()`：
   - 正常选择 → `DefaultResponse.getServer()` = 实例
   - 无实例（Supplier 返回空）→ `EmptyResponse.getServer()` = **null**

**注意 `choose()` 返回 null 的两种情形**：
- 子上下文不存在（`loadBalancer == null`）
- 实例列表为空（`EmptyResponse`）

调用方（3.1 的 execute）通过 `serviceInstance == null` 统一判断，走 DISCARD 分支。

---

## 五、URI 重构：LoadBalancerUriTools

**位置**: `LoadBalancerUriTools.java`（125 行，纯静态工具类）

`reconstructURI` 是 `LoadBalancerClient` 接口的另一个核心方法——把请求 URI 中的服务名替换为选中实例的 host:port：

```java
public static URI reconstructURI(ServiceInstance serviceInstance, URI original) {
    if (serviceInstance == null) {
        throw new IllegalArgumentException("Service Instance cannot be null.");
    }
    return doReconstructURI(serviceInstance, original);
}

private static URI doReconstructURI(ServiceInstance serviceInstance, URI original) {
    String host = serviceInstance.getHost();
    String scheme = Optional.ofNullable(serviceInstance.getScheme())
        .orElse(computeScheme(original, serviceInstance));
    int port = computePort(serviceInstance.getPort(), scheme);

    // 如果 host/port/scheme 都与原 URI 相同 → 直接返回原 URI（零开销优化）
    if (Objects.equals(host, original.getHost()) && port == original.getPort()
            && Objects.equals(scheme, original.getScheme())) {
        return original;
    }

    boolean encoded = containsEncodedParts(original);
    return UriComponentsBuilder.fromUri(original).scheme(scheme).host(host).port(port).build(encoded).toUri();
}
```

### 5.1 scheme 计算

```java
private static String computeScheme(URI original, ServiceInstance serviceInstance) {
    String originalOrDefault = Optional.ofNullable(original.getScheme()).orElse(DEFAULT_SCHEME);  // 默认 http
    if (serviceInstance.isSecure() && INSECURE_SCHEME_MAPPINGS.containsKey(originalOrDefault)) {
        return INSECURE_SCHEME_MAPPINGS.get(originalOrDefault);   // http→https, ws→wss
    }
    return originalOrDefault;
}
```

**协议升级**：实例标记为 secure 时，`http` 自动升级为 `https`、`ws` 升级为 `wss`（`INSECURE_SCHEME_MAPPINGS` 静态映射）。

### 5.2 port 计算

```java
private static int computePort(int port, String scheme) {
    if (port >= 0) {
        return port;                       // 实例显式端口
    }
    if (Objects.equals(scheme, DEFAULT_SECURE_SCHEME)) {
        return 443;                        // https 无端口 → 443
    }
    return 80;                             // http 无端口 → 80
}
```

### 5.3 编码处理

```java
private static boolean containsEncodedParts(URI uri) {
    // 检查 rawQuery / rawPath / rawFragment 是否含 %（编码标志）
    boolean encoded = (uri.getRawQuery() != null && uri.getRawQuery().contains(PERCENTAGE_SIGN))
            || (uri.getRawPath() != null && uri.getRawPath().contains(PERCENTAGE_SIGN))
            || (uri.getRawFragment() != null && uri.getRawFragment().contains(PERCENTAGE_SIGN));
    // 验证是否"完全编码"：build(true) 成功 → 已完全编码；抛异常 → 部分编码视为未编码
    if (encoded) {
        try {
            UriComponentsBuilder.fromUri(uri).build(true);
            return true;
        } catch (IllegalArgumentException ignore) {
        }
        return false;
    }
    return false;
}
```

URI 可能包含编码部分（如 `%20`），重构时用 `build(encoded)` 决定是否二次编码，避免 `%` 被双重编码成 `%25`。这个逻辑借鉴自 Spring Cloud Gateway 的 `ServerWebExchangeUtils`（源码注释引用了）。

### 5.4 调用方

`BlockingLoadBalancerClient.reconstructURI()` 只是薄包装：

```java
@Override
public URI reconstructURI(ServiceInstance serviceInstance, URI original) {
    return LoadBalancerUriTools.reconstructURI(serviceInstance, original);
}
```

真正的调用链（以 RestTemplate 为例）：`LoadBalancerInterceptor` 从 URI 提取 serviceId → `loadBalancer.execute()` → 选中实例后 `ServiceRequestWrapper.getURI()` 调用 `reconstructURI()` 完成替换。

---

## 六、生命周期处理器收集：LoadBalancerLifecycleValidator

```java
public static Set<LoadBalancerLifecycle> getSupportedLifecycleProcessors(
        Map<String, LoadBalancerLifecycle> lifecycleProcessors, Class requestContextClass,
        Class clientResponseClass, Class serverTypeClass) {
    if (lifecycleProcessors == null) {
        return new HashSet<>();
    }
    return lifecycleProcessors.values()
        .stream()
        .filter(lifecycle -> lifecycle.supports(requestContextClass, clientResponseClass, serverTypeClass))
        .collect(Collectors.toSet());
}
```

`BlockingLoadBalancerClient` 调用它时传入的泛型类型：

```java
LoadBalancerLifecycleValidator.getSupportedLifecycleProcessors(
        loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
        DefaultRequestContext.class,   // 请求上下文类型
        Object.class,                  // 客户端响应类型
        ServiceInstance.class);        // 服务器类型
```

**作用**：从子上下文的 `LoadBalancerLifecycle` Bean 中，过滤出**支持当前类型组合**的处理器——每个 Lifecycle 通过 `supports()` 方法声明自己能处理哪些类型。`MicrometerStatsLoadBalancerLifecycle` 就是通过这个机制被收集，实现 D6 要分析的指标采集。

---

## 七、完整调用链（RestTemplate 场景）

```
@LoadBalanced RestTemplate.getForObject("http://orders-service/api", ...)
    │
    ▼
LoadBalancerInterceptor.intercept(request)          ← 从 URI host 提取 "orders-service"
    │  loadBalancer.execute(serviceId, requestFactory.createRequest(...))
    ▼
BlockingLoadBalancerClient.execute(serviceId, request)
    │  ① 构建 RequestDataContext（hint + HTTP 请求数据）
    │  ② lifecycle.onStart()
    │  ③ choose(serviceId) → Mono.block() 桥接
    │        └─ ReactorLoadBalancer.choose() → Supplier 链 → ServiceInstance
    │  ④ lifecycle.onStartRequest()
    │  ⑤ request.apply(instance)
    │        └─ ServiceRequestWrapper.getURI() → LoadBalancerUriTools.reconstructURI()
    │              → http://10.0.0.5:8080/api（服务名替换为实例地址）
    │  ⑥ lifecycle.onComplete(SUCCESS/FAILED)
    ▼
返回响应
```

---

## 八、面试考点

1. **阻塞客户端如何桥接响应式内核？**
   `Mono.from(loadBalancer.choose(request)).block()`——阻塞当前线程等待响应式结果。桥接点是 `choose()` 方法。

2. **execute() 的两个重载有什么区别？**
   `execute(serviceId, request)` 内部先 choose 再委托给 `execute(serviceId, serviceInstance, request)`；后者假设实例已选好，只负责执行请求 + 生命周期闭环。拆分的意义：`RetryLoadBalancerInterceptor` 可以在重试时直接调用第二个重载（复用已选实例或重新选择）。

3. **choose() 返回 null 的两种情形？**
   子上下文不存在（`loadBalancer == null`）和实例列表为空（`EmptyResponse.getServer()`）。调用方统一判 null 走 DISCARD。

4. **LoadBalancerUriTools 的 scheme 升级规则？**
   实例 `isSecure()` 时，`http→https`、`ws→wss`（通过静态映射表）；默认 scheme 是 http。

5. **生命周期处理器如何被筛选？**
   `LoadBalancerLifecycleValidator.getSupportedLifecycleProcessors()` 按 `lifecycle.supports(requestContextClass, clientResponseClass, serverTypeClass)` 过滤，只保留能处理当前类型组合的处理器。
