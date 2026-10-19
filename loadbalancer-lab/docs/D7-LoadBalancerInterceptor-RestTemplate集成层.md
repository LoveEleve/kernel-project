# D7: LoadBalancerInterceptor — @LoadBalanced RestTemplate 集成层

> 源码版本: spring-cloud-commons **v4.1.4**（tag；运行时对齐 SC 2023.0.3；4.3.2/5.x 新特性以注记标出）
> 源码路径: `spring-cloud-commons/.../client/loadbalancer/`
> 覆盖文件: `LoadBalancerInterceptor.java`(58行)、`RetryLoadBalancerInterceptor.java`(169行)、`LoadBalancerRequestFactory.java`(58行)、`BlockingLoadBalancerRequest.java`(79行)、`ServiceRequestWrapper.java`(46行)、`LoadBalancerAutoConfiguration.java`(239行)、`BlockingLoadBalancerInterceptor.java`(30行)、`DeferringLoadBalancerInterceptor.java`(68行)、`LoadBalancerRequestTransformer.java`(39行)、`LoadBalanced.java`(40行)、`RetryableRequestContext.java`(83行)、`LoadBalancedRetryFactory.java`、`LoadBalancedRetryPolicy.java`、`LoadBalancedRetryContext.java`(89行)、`InterceptorRetryPolicy.java`(122行)、`LoadBalancedRecoveryCallback.java`(58行)、`ClientHttpResponseStatusCodeException.java`

---

## 一、整体架构：@LoadBalanced 是怎么生效的

`@LoadBalanced` 注解本身**什么都不做**——它只是一个标记（qualifier）。真正的工作由 `LoadBalancerAutoConfiguration` 完成：

```java
public @interface LoadBalanced {
}
```

```
① @LoadBalanced 标记 RestTemplate Bean
      ↓
② LoadBalancerAutoConfiguration 自动装配
      ↓
③ @Autowired(required = false) List<RestTemplate> restTemplates  ← 收集所有 @LoadBalanced RestTemplate
      ↓
④ RestTemplateCustomizer 给每个 RestTemplate 追加拦截器
      ↓
⑤ LoadBalancerInterceptor / RetryLoadBalancerInterceptor 拦截请求
      ↓
⑥ 从 URI host 提取服务名 → LoadBalancerClient.execute() → 选择实例 → 重构 URI → 发请求
```

**核心机制**：`@LoadBalanced` + `@Autowired List<RestTemplate>` 的**限定符匹配**——Spring 的 `@Qualifier` 派生注解机制。`@LoadBalanced` 是 `@Qualifier` 的元注解：

```java
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Qualifier
public @interface LoadBalanced {
}
```

所以 `@Autowired @LoadBalanced List<RestTemplate>` 只收集标记了 `@LoadBalanced` 的 RestTemplate Bean——这是 Spring 限定符的经典用法（`@Qualifier` 支持在注入点按注解过滤）。

---

## 二、LoadBalancerAutoConfiguration — 注入机制

**位置**: `LoadBalancerAutoConfiguration.java`（239 行，spring-cloud-commons 包，注意与 D3 的 spring-cloud-loadbalancer 包同名类区分）

```java
@AutoConfiguration
@Conditional(BlockingRestClassesPresentCondition.class)       // RestTemplate/RestClient 存在
@ConditionalOnBean(LoadBalancerClient.class)                   // 有 LoadBalancerClient（D5）
@EnableConfigurationProperties(LoadBalancerClientsProperties.class)
public class LoadBalancerAutoConfiguration {

    @LoadBalanced
    @Autowired(required = false)
    private List<RestTemplate> restTemplates = Collections.emptyList();   // 收集 @LoadBalanced RestTemplate

    @Autowired(required = false)
    private List<LoadBalancerRequestTransformer> transformers = Collections.emptyList();

    @Bean
    public SmartInitializingSingleton loadBalancedRestTemplateInitializerDeprecated(
            ObjectProvider<List<RestTemplateCustomizer>> restTemplateCustomizers) {
        return () -> restTemplateCustomizers.ifAvailable(customizers -> {
            for (RestTemplate restTemplate : restTemplates) {
                for (RestTemplateCustomizer customizer : customizers) {
                    customizer.customize(restTemplate);       // 给每个 @LoadBalanced RestTemplate 应用所有 Customizer
                }
            }
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public LoadBalancerRequestFactory loadBalancerRequestFactory(LoadBalancerClient loadBalancerClient) {
        return new LoadBalancerRequestFactory(loadBalancerClient, transformers);
    }
    // ...
}
```

### 2.1 关键机制：SmartInitializingSingleton

`loadBalancedRestTemplateInitializerDeprecated` 返回 `SmartInitializingSingleton`——Spring 在**所有单例 Bean 创建完成后**调用其 `afterSingletonsInstantiated()`。此时：

1. 所有 `@LoadBalanced RestTemplate` 已创建（`@Autowired List<RestTemplate>` 注入完成）
2. 所有 `RestTemplateCustomizer` Bean 已注册（`ObjectProvider<List<RestTemplateCustomizer>>` 懒获取）

执行时机保证：**Customizer 一定在 RestTemplate 首次使用前执行**。

### 2.2 拦截器选择：两套配置类

`LoadBalancerAutoConfiguration` 按重试是否启用，注入**不同的拦截器**：

```java
// ① 无重试路径：RetryTemplate 缺失 或 retry.enabled=false
@Configuration(proxyBeanMethods = false)
@Conditional(RetryMissingOrDisabledCondition.class)
static class LoadBalancerInterceptorConfig {

    @Bean
    public LoadBalancerInterceptor loadBalancerInterceptor(LoadBalancerClient loadBalancerClient,
            LoadBalancerRequestFactory requestFactory) {
        return new LoadBalancerInterceptor(loadBalancerClient, requestFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestTemplateCustomizer restTemplateCustomizer(LoadBalancerInterceptor loadBalancerInterceptor) {
        return restTemplate -> {
            List<ClientHttpRequestInterceptor> list = new ArrayList<>(restTemplate.getInterceptors());
            list.add(loadBalancerInterceptor);                  // 把拦截器追加到 RestTemplate
            restTemplate.setInterceptors(list);
        };
    }
}

// ② 重试路径：RetryTemplate 存在 且 retry.enabled 默认开启
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RetryTemplate.class)
@ConditionalOnBean(ReactiveLoadBalancer.Factory.class)
@ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", matchIfMissing = true)
public static class RetryInterceptorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RetryLoadBalancerInterceptor loadBalancerInterceptor(LoadBalancerClient loadBalancerClient,
            LoadBalancerRequestFactory requestFactory, LoadBalancedRetryFactory loadBalancedRetryFactory,
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) {
        return new RetryLoadBalancerInterceptor(loadBalancerClient, requestFactory, loadBalancedRetryFactory,
                loadBalancerFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public RestTemplateCustomizer restTemplateCustomizer(RetryLoadBalancerInterceptor loadBalancerInterceptor) {
        // 同样：把 RetryLoadBalancerInterceptor 追加到 RestTemplate
    }
}
```

**`RetryMissingOrDisabledCondition`（AnyNestedCondition）**——两个条件任一满足即启用无重试拦截器：

```java
private static class RetryMissingOrDisabledCondition extends AnyNestedCondition {
    @ConditionalOnMissingClass("org.springframework.retry.support.RetryTemplate")
    static class RetryTemplateMissing {}          // ① spring-retry 不在 classpath

    @ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", havingValue = "false")
    static class RetryDisabled {}                 // ② 显式关闭重试
}
```

**选择总结**：

| 条件 | 注入的拦截器 |
|------|-------------|
| 无 spring-retry 依赖 或 `retry.enabled=false` | `LoadBalancerInterceptor`（无重试） |
| 有 spring-retry 且 `retry.enabled` 默认开启 | `RetryLoadBalancerInterceptor`（重试） |

两个配置类的 `loadBalancerInterceptor` @Bean **方法名相同**——这是刻意的：两个条件互斥，只有一个生效；`@ConditionalOnMissingBean` 保证即使两个都意外激活，也只有一个 Bean。

### 2.3 两种注入路径：Customizer vs Deferring

`LoadBalancerAutoConfiguration` 里有**两条平行的注入路径**：

| 路径 | 适用场景 | 机制 |
|------|---------|------|
| `RestTemplateCustomizer` | 普通 `@LoadBalanced RestTemplate` | `SmartInitializingSingleton` 阶段把 `LoadBalancerInterceptor`/`RetryLoadBalancerInterceptor` 直接追加到 RestTemplate 的拦截器链 |
| `DeferringLoadBalancerInterceptor` | `RestClient.Builder` / `RestTemplateBuilder` / HttpServiceClient（**Builder 模式懒创建**） | BeanPostProcessor 把延迟代理注入 Builder；代理在首次请求时经 `ObjectProvider` 解析真正的拦截器并缓存 |

`DeferringLoadBalancerInterceptorConfig`（以及 RestTemplateBuilder/HttpClient 两个变体）注册：

```java
@Bean
@ConditionalOnMissingBean
public static DeferringLoadBalancerInterceptor deferringLoadBalancerInterceptor(
        ObjectProvider<BlockingLoadBalancerInterceptor> loadBalancerInterceptorObjectProvider) {
    return new DeferringLoadBalancerInterceptor(loadBalancerInterceptorObjectProvider);
}

@Bean
@ConditionalOnBean(DeferringLoadBalancerInterceptor.class)
@ConditionalOnMissingBean(LoadBalancerRestClientBuilderBeanPostProcessor.class)
static LoadBalancerRestClientBuilderBeanPostProcessor<DeferringLoadBalancerInterceptor> lbRestClientPostProcessor(
        ObjectProvider<DeferringLoadBalancerInterceptor> loadBalancerInterceptorProvider,
        ApplicationContext context) {
    return new LoadBalancerRestClientBuilderBeanPostProcessor<>(loadBalancerInterceptorProvider, context);
}
```

`LoadBalancerRestClientBuilderBeanPostProcessor` 是 `BeanPostProcessor`——在 `RestClient.Builder` / `RestTemplateBuilder` Bean 创建时，把 `DeferringLoadBalancerInterceptor` 注入到 Builder 的拦截器列表。注意：**注入的是 Deferring 本身，不是替换它**——Deferring 在首次请求时才通过 `ObjectProvider` 解析真正的拦截器并缓存（见第六节）。

---

## 三、LoadBalancerInterceptor — 无重试路径

**位置**: `LoadBalancerInterceptor.java`（58 行）

```java
public class LoadBalancerInterceptor implements BlockingLoadBalancerInterceptor {

    private final LoadBalancerClient loadBalancer;
    private final LoadBalancerRequestFactory requestFactory;

    public LoadBalancerInterceptor(LoadBalancerClient loadBalancer, LoadBalancerRequestFactory requestFactory) {
        this.loadBalancer = loadBalancer;
        this.requestFactory = requestFactory;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        URI originalUri = request.getURI();
        String serviceName = originalUri.getHost();   // ← 服务名就藏在 URI 的 host 里！
        Assert.state(serviceName != null, "Request URI does not contain a valid hostname: " + originalUri);
        return loadBalancer.execute(serviceName, requestFactory.createRequest(request, body, execution));
    }
}
```

**极简实现，两个关键动作**：

1. **提取服务名**——`originalUri.getHost()`。`http://orders-service/api` 的 host 是 `orders-service`。这就是服务名的"协议"：**调用方用服务名当主机名写 URI**，拦截器提取后交给 LoadBalancer。

2. **委托 LoadBalancer**——`loadBalancer.execute(serviceName, request)`。这里走的是 D5 的 `BlockingLoadBalancerClient.execute(serviceId, request)` 完整流程（构建上下文 → onStart → choose → onStartRequest → apply → onComplete）。

`BlockingLoadBalancerInterceptor` 接口只是 `ClientHttpRequestInterceptor` 的标记子接口：

```java
public interface BlockingLoadBalancerInterceptor extends ClientHttpRequestInterceptor {
}
```

---

## 四、BlockingLoadBalancerRequest + ServiceRequestWrapper — 请求执行

### 4.1 LoadBalancerRequestFactory — 请求工厂

**位置**: `LoadBalancerRequestFactory.java`（58 行）

```java
public class LoadBalancerRequestFactory {

    private final LoadBalancerClient loadBalancer;
    private final List<LoadBalancerRequestTransformer> transformers;

    public LoadBalancerRequest<ClientHttpResponse> createRequest(final HttpRequest request, final byte[] body,
            final ClientHttpRequestExecution execution) {
        return new BlockingLoadBalancerRequest(loadBalancer, transformers,
                new BlockingLoadBalancerRequest.ClientHttpRequestData(request, body, execution));
    }
}
```

把拦截器收到的 `HttpRequest + body + execution` 封装为 `LoadBalancerRequest`（D5 的 `LoadBalancerClient.execute()` 需要）。

### 4.2 BlockingLoadBalancerRequest — 请求执行核心

**位置**: `BlockingLoadBalancerRequest.java`（79 行，包级私有）

```java
class BlockingLoadBalancerRequest implements HttpRequestLoadBalancerRequest<ClientHttpResponse> {

    private final LoadBalancerClient loadBalancer;
    private final List<LoadBalancerRequestTransformer> transformers;
    private final ClientHttpRequestData clientHttpRequestData;

    @Override
    public ClientHttpResponse apply(ServiceInstance instance) throws Exception {
        // 1. 包装请求：URI 替换为选中实例的 host:port（懒替换）
        HttpRequest serviceRequest = new ServiceRequestWrapper(clientHttpRequestData.request, instance, loadBalancer);
        // 2. 应用请求变换器（粘性 cookie 写入等）
        if (this.transformers != null) {
            for (LoadBalancerRequestTransformer transformer : this.transformers) {
                serviceRequest = transformer.transformRequest(serviceRequest, instance);
            }
        }
        // 3. 真正执行 HTTP 请求
        return clientHttpRequestData.execution.execute(serviceRequest, clientHttpRequestData.body);
    }

    @Override
    public HttpRequest getHttpRequest() {
        return clientHttpRequestData.request;
    }
}
```

**为什么 D5 的 `buildRequestContext` 能识别它是 HTTP 请求**——`BlockingLoadBalancerRequest implements HttpRequestLoadBalancerRequest`，`getHttpRequest()` 返回原始 request，D5 据此构造 `RequestDataContext`（D5 3.2 分析过）。

### 4.3 ServiceRequestWrapper — 懒 URI 替换

**位置**: `ServiceRequestWrapper.java`（46 行）

```java
public class ServiceRequestWrapper extends HttpRequestWrapper {

    private final ServiceInstance instance;
    private final LoadBalancerClient loadBalancer;

    public ServiceRequestWrapper(HttpRequest request, ServiceInstance instance, LoadBalancerClient loadBalancer) {
        super(request);
        this.instance = instance;
        this.loadBalancer = loadBalancer;
    }

    @Override
    public URI getURI() {
        URI uri = this.loadBalancer.reconstructURI(this.instance, getRequest().getURI());  // 懒替换
        return uri;
    }
}
```

**懒替换模式**：包装时**不立即**替换 URI（`getURI()` 被调用时才触发 `reconstructURI`）。这有两个好处：

1. 变换器（transformers）可以在替换前修改请求（如粘性 cookie 写入需要先于 URI 替换）
2. 实际 HTTP 执行器（`ClientHttpRequestExecution.execute()`）调用 `getURI()` 时才完成替换——时机精确

---

## 五、RetryLoadBalancerInterceptor — 重试路径

**位置**: `RetryLoadBalancerInterceptor.java`（171 行）

这是 `LoadBalancerInterceptor` 的重试增强版。核心思路：用 Spring Retry 的 `RetryTemplate` 包裹执行，重试时**重新选择实例**（或复用上次实例），并通知 `RetryAwareServiceInstanceListSupplier`（D2 4.9）避免选到上次失败的实例。

### 5.1 整体结构

```java
public class RetryLoadBalancerInterceptor implements BlockingLoadBalancerInterceptor {

    private final LoadBalancerClient loadBalancer;
    private final LoadBalancerRequestFactory requestFactory;
    private final LoadBalancedRetryFactory lbRetryFactory;
    private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;

    @Override
    public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
            final ClientHttpRequestExecution execution) throws IOException {
        final URI originalUri = request.getURI();
        final String serviceName = originalUri.getHost();
        Assert.state(serviceName != null, "Request URI does not contain a valid hostname: " + originalUri);
        final LoadBalancedRetryPolicy retryPolicy = lbRetryFactory.createRetryPolicy(serviceName, loadBalancer);
        RetryTemplate template = createRetryTemplate(serviceName, request, retryPolicy);
        return template.execute(context -> { ... }, new LoadBalancedRecoveryCallback<>() { ... });
    }
}
```

**三段式**：
1. `lbRetryFactory.createRetryPolicy(serviceName, loadBalancer)` — 创建重试策略（`BlockingLoadBalancedRetryPolicy`）
2. `createRetryTemplate(...)` — 组装 RetryTemplate（BackOff、监听器、策略）
3. `template.execute(retryCallback, recoveryCallback)` — 执行，重试回调内每次尝试都重新选择/复用实例

### 5.2 createRetryTemplate — RetryTemplate 组装

```java
private RetryTemplate createRetryTemplate(String serviceName, HttpRequest request,
        @Nullable LoadBalancedRetryPolicy retryPolicy) {
    RetryTemplate template = new RetryTemplate();
    BackOffPolicy backOffPolicy = lbRetryFactory.createBackOffPolicy(serviceName);
    template.setBackOffPolicy(backOffPolicy == null ? new NoBackOffPolicy() : backOffPolicy);
    template.setThrowLastExceptionOnExhausted(true);   // 重试耗尽时抛最后一次异常（而非第一次）
    RetryListener[] retryListeners = lbRetryFactory.createRetryListeners(serviceName);
    if (retryListeners != null && retryListeners.length != 0) {
        template.setListeners(retryListeners);
    }
    LoadBalancerProperties properties = loadBalancerFactory.getProperties(serviceName);
    boolean retryEnabled = properties == null || properties.getRetry().isEnabled();
    template.setRetryPolicy(!retryEnabled || retryPolicy == null ? new NeverRetryPolicy()
            : new InterceptorRetryPolicy(request, retryPolicy, loadBalancer, serviceName));
    return template;
}
```

**关键配置**：
- `setThrowLastExceptionOnExhausted(true)` — 重试耗尽时抛**最后一次**异常（对重试场景更合理——最后一次异常更接近当前服务状态）
- 重试策略：`retry.enabled=false` 或 policy 为 null → `NeverRetryPolicy`（等效于不重试）；否则 `InterceptorRetryPolicy`
- BackOff：`lbRetryFactory.createBackOffPolicy()`，默认无退避

### 5.3 retryCallback — 每次尝试的执行逻辑

```java
template.execute(context -> {
    // 1. 从重试上下文取已选实例（重试时复用）
    ServiceInstance serviceInstance = null;
    if (context instanceof LoadBalancedRetryContext lbContext) {
        serviceInstance = lbContext.getServiceInstance();
    }
    // 2. 收集生命周期处理器（注意类型：RetryableRequestContext, ResponseData, ServiceInstance）
    Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
        .getSupportedLifecycleProcessors(
                loadBalancerFactory.getInstances(serviceName, LoadBalancerLifecycle.class),
                RetryableRequestContext.class, ResponseData.class, ServiceInstance.class);
    String hint = getHint(serviceName);
    // 3. 无实例 → 重新选择
    if (serviceInstance == null) {
        ServiceInstance previousServiceInstance = null;
        if (context instanceof LoadBalancedRetryContext lbContext) {
            previousServiceInstance = lbContext.getPreviousServiceInstance();   // 上次失败的实例
        }
        DefaultRequest<RetryableRequestContext> lbRequest = new DefaultRequest<>(
                new RetryableRequestContext(previousServiceInstance, new RequestData(request), hint));
        supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
        serviceInstance = loadBalancer.choose(serviceName, lbRequest);   // 重新选择
        if (context instanceof LoadBalancedRetryContext lbContext) {
            lbContext.setServiceInstance(serviceInstance);   // 记住本次选择的实例
        }
        Response<ServiceInstance> lbResponse = new DefaultResponse(serviceInstance);
        if (serviceInstance == null) {
            // 4. 无可用实例 → DISCARD
            supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
                .onComplete(new CompletionContext<>(CompletionContext.Status.DISCARD, ..., lbResponse)));
        }
    }
    // 5. 执行请求（复用已选实例）
    LoadBalancerRequestAdapter<ClientHttpResponse, RetryableRequestContext> lbRequest = new LoadBalancerRequestAdapter<>(
            requestFactory.createRequest(request, body, execution),
            new RetryableRequestContext(null, new RequestData(request), hint));
    ServiceInstance finalServiceInstance = serviceInstance;
    ClientHttpResponse response = loadBalancer.execute(serviceName, finalServiceInstance, lbRequest);
    // 6. 状态码可重试 → 抛异常触发 RetryTemplate 重试
    int statusCode = response.getStatusCode().value();
    if (retryPolicy != null && retryPolicy.retryableStatusCode(statusCode)) {
        byte[] bodyCopy = StreamUtils.copyToByteArray(response.getBody());
        response.close();
        throw new ClientHttpResponseStatusCodeException(serviceName, response, bodyCopy);
    }
    return response;
}, new LoadBalancedRecoveryCallback<>() { ... });
```

**每次重试尝试的流程**：

```
第 1 次尝试（context 无实例）
  ├─ 重新选择实例（携带 previousServiceInstance=null）
  ├─ 记住选择 → LoadBalancedRetryContext.setServiceInstance()
  ├─ execute(serviceName, instance, request)
  ├─ 状态码可重试？ → 抛 ClientHttpResponseStatusCodeException
  └─ 正常 → 返回响应

第 2 次尝试（RetryTemplate 重试，context 有实例）
  ├─ 复用 LoadBalancedRetryContext 中的实例 ← 同实例重试
  ├─ execute(...)
  └─ 状态码可重试？ → 抛异常（再次触发）

第 3 次尝试（按策略决定是否换实例）
  ├─ 换实例时：previousServiceInstance 传给 RetryableRequestContext
  │     → RetryAwareServiceInstanceListSupplier 剔除上次失败的实例（D2 4.9）
  ├─ 重新选择
  └─ ...
```

**为什么 `instanceof LoadBalancedRetryContext` 恒为 true**：RetryTemplate 的 RetryContext 由 RetryPolicy 的 `open()` 方法创建——`InterceptorRetryPolicy.open()` 返回 `new LoadBalancedRetryContext(parent, request)`。所以 retryCallback 里的 `context instanceof LoadBalancedRetryContext` 判断是**确定成立**的（防御性写法），`getServiceInstance()`/`getPreviousServiceInstance()` 永远可访问。

**重试决策链**（`InterceptorRetryPolicy.canRetry`）：

```
canRetry(context)
  ├─ policy.retryableException(lastThrowable)?   ← LoadBalancedRetryPolicy 判断异常是否可重试
  │     └─ 否 → 不重试
  ├─ retryCount == 0 && serviceInstance == null?  ← 还没真正尝试过
  │     └─ 是 → 放行（首次尝试）
  └─ policy.canRetryNextServer(lbContext)         ← 委托 BlockingLoadBalancedRetryPolicy
        └─ 同实例重试次数 < maxRetriesOnSameServiceInstance → 同实例重试
        └─ 跨实例重试次数 < maxRetriesOnNextServiceInstance → 换实例重试
        └─ 否则 → 不重试
```

**同实例 vs 跨实例重试**：`BlockingLoadBalancedRetryPolicy`（D2 提到过）控制 `maxRetriesOnSameServiceInstance` / `maxRetriesOnNextServiceInstance`（`LoadBalancerProperties.Retry` 配置）。同实例重试次数耗尽后，下一次尝试重新选择实例（`serviceInstance == null` 分支），并把上次实例作为 `previousServiceInstance` 传给 `RetryableRequestContext`——`RetryAwareServiceInstanceListSupplier` 据此剔除它（D2 4.9）。

**生命周期类型差异**：注意这里收集处理器传的是 `RetryableRequestContext.class, ResponseData.class`——与 D5 的 `DefaultRequestContext.class, Object.class` 不同！所以 `MicrometerStatsLoadBalancerLifecycle.supports()` 只看 serverTypeClass（D6 分析过），两种场景都能通过。

---

## 六、DeferringLoadBalancerInterceptor — 延迟解析

`LoadBalancerAutoConfiguration` 中还有个 `DeferringLoadBalancerInterceptor`：

```java
@Bean
@ConditionalOnMissingBean
public static DeferringLoadBalancerInterceptor deferringLoadBalancerInterceptor(
        ObjectProvider<BlockingLoadBalancerInterceptor> loadBalancerInterceptorObjectProvider) {
    return new DeferringLoadBalancerInterceptor(loadBalancerInterceptorObjectProvider);
}
```

**作用**：`RestClient.Builder` / `RestTemplateBuilder` / HttpServiceClient 是**懒创建**的——Builder Bean 可能在真正的拦截器（`LoadBalancerInterceptor`/`RetryLoadBalancerInterceptor`，依赖 `LoadBalancerClient`，D5 自动配置）就绪前就被创建或调用。`DeferringLoadBalancerInterceptor` 直接实现 `ClientHttpRequestInterceptor`（不依赖 BlockingLoadBalancerInterceptor 类型），内部持有 `ObjectProvider<BlockingLoadBalancerInterceptor>`，在**首次请求时**解析真正的拦截器并缓存：

```java
public class DeferringLoadBalancerInterceptor implements ClientHttpRequestInterceptor {

    private final ObjectProvider<BlockingLoadBalancerInterceptor> loadBalancerInterceptorProvider;
    private @Nullable BlockingLoadBalancerInterceptor delegate;   // 缓存解析结果

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        tryResolveDelegate();          // 首次调用时解析，之后直接复用 delegate
        return delegate.intercept(request, body, execution);
    }

    private void tryResolveDelegate() {
        if (delegate == null) {
            delegate = loadBalancerInterceptorProvider.getIfAvailable();
            if (delegate == null) {
                throw new IllegalStateException("LoadBalancer interceptor not available.");
            }
        }
    }
}
```

这就是**延迟代理模式**：解决 Bean 创建顺序依赖，避免循环依赖。注意两点：1）解析是**一次性**的（缓存到 delegate 字段），不是每次请求都解析；2）真正的拦截器最终不可用时抛 `IllegalStateException`（而不是静默失败）。

---

## 七、完整时序（@LoadBalanced RestTemplate 场景）

```
RestTemplate.getForObject("http://orders-service/api", String.class)
    │
    ▼
[RestTemplate 拦截器链]  ← RestTemplateCustomizer 已把拦截器追加进来
    │  （LoadBalancerInterceptor 或 RetryLoadBalancerInterceptor）
    ▼
LoadBalancerInterceptor.intercept(request, body, execution)
    │  serviceName = request.getURI().getHost() = "orders-service"
    │  request = requestFactory.createRequest(...) → BlockingLoadBalancerRequest
    ▼
loadBalancer.execute("orders-service", lbRequest)        ← D5 流程
    │  ① buildRequestContext → RequestDataContext（HttpRequestLoadBalancerRequest 分支）
    │  ② lifecycle.onStart
    │  ③ choose → Mono.block() → 实例 A
    │  ④ lifecycle.onStartRequest
    │  ⑤ lbRequest.apply(instance A)
    │        ├─ new ServiceRequestWrapper(request, A, loadBalancer)  ← 懒 URI 替换
    │        ├─ transformer.transformRequest(...)  ← 粘性 cookie 写入等
    │        └─ execution.execute(serviceRequest, body)
    │             └─ serviceRequest.getURI() → reconstructURI → http://10.0.0.5:8080/api
    │  ⑥ lifecycle.onComplete(SUCCESS)
    ▼
返回响应
```

> RestClient.Builder / RestTemplateBuilder 场景的时序类似，差别仅在拦截器来源：Builder 在创建时被 `LoadBalancerRestClientBuilderBeanPostProcessor` 注入 `DeferringLoadBalancerInterceptor`（延迟代理），实际请求时由它委托给真正的 `LoadBalancerInterceptor`。

---

## 八、面试考点

1. **@LoadBalanced 如何工作？**
   它是 `@Qualifier` 的元注解。`LoadBalancerAutoConfiguration` 用 `@Autowired @LoadBalanced List<RestTemplate>` 收集所有标记的 RestTemplate，通过 `RestTemplateCustomizer` 在 `SmartInitializingSingleton` 阶段（所有单例创建完成后）把拦截器追加到 RestTemplate。

2. **服务名如何从 URI 提取？**
   `originalUri.getHost()`——调用方用服务名当主机名写 URI（`http://orders-service/api`），拦截器提取 host 作为 serviceId。

3. **LoadBalancerInterceptor 与 RetryLoadBalancerInterceptor 如何选择？**
   `retry.enabled`（默认开）+ spring-retry 存在 → 重试版本；否则无重试版本。两个配置类的 @Bean 方法名相同 + 条件互斥 + `@ConditionalOnMissingBean` 三重保障。

4. **重试如何换实例？**
   `LoadBalancedRetryContext` 记录已选实例（同实例重试复用）；换实例时把上次实例作为 `previousServiceInstance` 传给 `RetryableRequestContext`，`RetryAwareServiceInstanceListSupplier`（D2 4.9）剔除它，避免再次选中。

5. **ServiceRequestWrapper 的懒替换？**
   `getURI()` 被调用时才执行 `reconstructURI`。好处：变换器可以先修改请求（如粘性 cookie），实际执行器取 URI 时才完成 host:port 替换。

6. **DeferringLoadBalancerInterceptor 解决什么问题？**
   `RestClient.Builder` / `RestTemplateBuilder` / HttpServiceClient 是懒创建的，可能在真正的拦截器就绪前就被创建。延迟代理（`DeferringLoadBalancerInterceptor`）在**首次请求时**通过 `ObjectProvider` 一次性解析真正的拦截器并缓存，避免 Bean 创建顺序依赖和循环依赖。
