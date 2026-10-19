# D3: FeignBlockingLoadBalancerClient 深度源码分析

> 源码版本: Spring Cloud OpenFeign 4.1.3（Spring Cloud 2023.0.3，xhs-project 口径）
> 源码路径: `spring-cloud-openfeign-core/.../loadbalancer/` 包 (12 个文件)
> 核心源码:
> - `FeignBlockingLoadBalancerClient.java` (165 行)
> - `RetryableFeignBlockingLoadBalancerClient.java` (281 行)
> - `LoadBalancerUtils.java` (95 行)
> - `FeignLoadBalancerAutoConfiguration.java` (67 行)
> - `DefaultFeignLoadBalancerConfiguration.java` (71 行)
> - `HttpClient5FeignLoadBalancerConfiguration.java` (82 行)
> - `OnRetryNotEnabledCondition.java` (55 行)
> - `LoadBalancerFeignRequestTransformer.java` (47 行)
> - `XForwardedHeadersTransformer.java` (63 行)
> 测试文件: `FeignBlockingLoadBalancerClientTests.java` (288 行)

## 一、类定位与职责

### 1.1 一句话定位

`FeignBlockingLoadBalancerClient` 是一个**装饰器**（Decorator），包装了真实的 HTTP `Client`，在请求执行前通过 `LoadBalancerClient` 选择一个服务实例，用实例的 host:port 替换原始 URL 中的服务名。

### 1.2 类声明

```java
@SuppressWarnings({ "unchecked", "rawtypes" })
public class FeignBlockingLoadBalancerClient implements Client {

    private static final Log LOG = LogFactory.getLog(FeignBlockingLoadBalancerClient.class);

    private final Client delegate;                           // 被包装的真实 HTTP Client
    private final LoadBalancerClient loadBalancerClient;     // 负载均衡客户端
    private final LoadBalancerClientFactory loadBalancerClientFactory; // LB 工厂（获取配置和生命周期）
    private final List<LoadBalancerFeignRequestTransformer> transformers; // 请求转换器列表
}
```

**四个依赖**:

| 依赖 | 类型 | 来源 | 作用 |
|------|------|------|------|
| `delegate` | `Client` | `Client.Default` / `ApacheHttp5Client` | 实际执行 HTTP 请求 |
| `loadBalancerClient` | `LoadBalancerClient` | `BlockingLoadBalancerClient` (Spring Cloud LoadBalancer) | 选择服务实例 + 重构 URI |
| `loadBalancerClientFactory` | `LoadBalancerClientFactory` | Spring Cloud LoadBalancer 自动配置 | 获取 `LoadBalancerProperties` + `LoadBalancerLifecycle` 实例 |
| `transformers` | `List<LoadBalancerFeignRequestTransformer>` | Spring 容器中所有此类型 Bean | 请求转换（如添加 X-Forwarded 头） |

### 1.3 装饰器模式

```
Feign 代理
  └── FeignBlockingLoadBalancerClient (装饰器)
        ├── 选择实例: loadBalancerClient.choose("nacos-lab")
        ├── 重构 URL: http://nacos-lab/path -> http://21.214.97.212:28001/path
        ├── 转换请求: transformers.transformRequest(request, instance)
        └── 委托执行: delegate.execute(newRequest, options)
              └── ApacheHttp5Client (被装饰者)
                    └── HTTP 请求发送
```

`FeignClientFactoryBean.getTarget()` 中，当无 URL 时走 `loadBalance()` 路径（D1 分析），从子容器获取 `Client` Bean。当 LoadBalancer 在 classpath 上时，这个 Bean 就是 `FeignBlockingLoadBalancerClient`。

当有 URL（直连）时，`getTarget()` 会调用 `getDelegate()` **unwrap** 装饰器，直接使用底层 `Client`（如 `ApacheHttp5Client`），跳过负载均衡。

## 二、核心方法: execute() 逐行分析

### 2.1 完整源码

```java
@Override
public Response execute(Request request, Request.Options options) throws IOException {
    // 1. 从请求 URL 提取服务名
    final URI originalUri = URI.create(request.url());
    String serviceId = originalUri.getHost();
    Assert.state(serviceId != null, "Request URI does not contain a valid hostname: " + originalUri);

    // 2. 获取 hint（负载均衡提示）
    String hint = getHint(serviceId);

    // 3. 构建 LoadBalancer 请求上下文
    DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(
            new RequestDataContext(buildRequestData(request), hint));

    // 4. 获取并通知生命周期处理器
    Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
        .getSupportedLifecycleProcessors(
                loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
                RequestDataContext.class, ResponseData.class, ServiceInstance.class);
    supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));

    // 5. 选择实例
    ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest);
    org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(
            instance);

    // 6a. 无可用实例 -> 返回 503
    if (instance == null) {
        String message = "Load balancer does not contain an instance for the service " + serviceId;
        if (LOG.isWarnEnabled()) {
            LOG.warn(message);
        }
        supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
            .onComplete(new CompletionContext<ResponseData, ServiceInstance, RequestDataContext>(
                    CompletionContext.Status.DISCARD, lbRequest, lbResponse)));
        return Response.builder()
            .request(request)
            .status(HttpStatus.SERVICE_UNAVAILABLE.value())
            .body(message, StandardCharsets.UTF_8)
            .build();
    }

    // 6b. 有实例 -> 重构 URL + 转换请求 + 委托执行
    String reconstructedUrl = loadBalancerClient.reconstructURI(instance, originalUri).toString();
    Request newRequest = buildRequest(request, reconstructedUrl, instance);
    return executeWithLoadBalancerLifecycleProcessing(delegate, options, newRequest, lbRequest, lbResponse,
            supportedLifecycleProcessors);
}
```

### 2.2 步骤详解

#### 步骤 1: 提取服务名

```java
final URI originalUri = URI.create(request.url());
String serviceId = originalUri.getHost();
Assert.state(serviceId != null, "Request URI does not contain a valid hostname: " + originalUri);
```

`request.url()` 的格式由 `FeignClientFactoryBean.getTarget()` 设置为 `http://{name}{cleanPath()}`（D1 分支 A），如 `http://nacos-lab/discovery/status`。

`URI.create("http://nacos-lab/discovery/status").getHost()` 返回 `"nacos-lab"`。

**测试验证**:
```java
@Test
void shouldExtractServiceIdFromRequestUrl() throws IOException {
    Request request = testRequest();  // url = "http://test/path"
    feignBlockingLoadBalancerClient.execute(request, new Request.Options());
    verify(loadBalancerClient).choose(eq("test"), any());  // 验证 serviceId = "test"
}

@Test
void shouldThrowExceptionIfNoServiceId() {
    Request request = testRequest("");  // url = "http:///path" (空 host)
    assertThatIllegalStateException()
        .isThrownBy(() -> feignBlockingLoadBalancerClient.execute(request, new Request.Options()))
        .withMessage("Request URI does not contain a valid hostname: http:///path");
}
```

#### 步骤 2: 获取 hint

```java
private String getHint(String serviceId) {
    LoadBalancerProperties properties = loadBalancerClientFactory.getProperties(serviceId);
    String defaultHint = properties.getHint().getOrDefault("default", "default");
    String hintPropertyValue = properties.getHint().get(serviceId);
    return hintPropertyValue != null ? hintPropertyValue : defaultHint;
}
```

**hint 的作用**: Spring Cloud LoadBalancer 的 hint 机制允许为不同服务指定负载均衡提示，用于区域路由、灰度发布等高级策略。

**优先级**: `spring.cloud.loadbalancer.hint.{serviceId}` > `spring.cloud.loadbalancer.hint.default` > `"default"`

**测试验证**:
```java
// 在 shouldExecuteLoadBalancerLifecycleCallbacks 测试中:
String callbackTestHint = "callbackTestHint";
loadBalancerProperties.getHint().put("test", callbackTestHint);
// 验证 hint 被正确传递到 LoadBalancerLifecycle.onStart()
```

#### 步骤 3: 构建 LoadBalancer 请求上下文

```java
DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(
        new RequestDataContext(buildRequestData(request), hint));
```

`buildRequestData()` 在 `LoadBalancerUtils` 中:

```java
static RequestData buildRequestData(Request request) {
    HttpHeaders requestHeaders = new HttpHeaders();
    request.headers().forEach((key, value) -> requestHeaders.put(key, new ArrayList<>(value)));
    return new RequestData(HttpMethod.valueOf(request.httpMethod().name()), URI.create(request.url()),
            requestHeaders, null, new HashMap<>());
}
```

将 Feign `Request` 转换为 Spring Cloud 的 `RequestData`（包含 HTTP 方法、URI、Header）。`RequestDataContext` 包装 `RequestData` + `hint`，传递给 LoadBalancer 用于实例选择决策。

#### 步骤 4: 生命周期回调 - onStart

```java
Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
    .getSupportedLifecycleProcessors(
            loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
            RequestDataContext.class, ResponseData.class, ServiceInstance.class);
supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
```

从 `LoadBalancerClientFactory` 获取该服务所有 `LoadBalancerLifecycle` Bean，筛选支持 `RequestDataContext`/`ResponseData`/`ServiceInstance` 类型的处理器，调用 `onStart()`。

**`LoadBalancerLifecycle` 的四个回调**:

| 回调 | 调用时机 | 参数 |
|------|---------|------|
| `onStart(request)` | 实例选择前 | `DefaultRequest<RequestDataContext>` |
| `onStartRequest(request, response)` | 实例选择后、请求发送前 | 请求 + 选中的实例 |
| `onComplete(completionContext)` | 请求完成（成功/失败/丢弃） | `CompletionContext`（含状态和响应） |
| (无 `onError`) | - | 异常通过 `onComplete(FAILED)` 传递 |

**测试验证**:
```java
@Test
void shouldExecuteLoadBalancerLifecycleCallbacks() throws IOException {
    // 设置两个 Lifecycle: TestLoadBalancerLifecycle + AnotherLoadBalancerLifecycle
    Map<String, LoadBalancerLifecycle> loadBalancerLifecycleBeans = new HashMap<>();
    loadBalancerLifecycleBeans.put("loadBalancerLifecycle", new TestLoadBalancerLifecycle());
    loadBalancerLifecycleBeans.put("anotherLoadBalancerLifecycle", new AnotherLoadBalancerLifecycle());
    when(loadBalancerClientFactory.getInstances("test", LoadBalancerLifecycle.class))
        .thenReturn(loadBalancerLifecycleBeans);

    feignBlockingLoadBalancerClient.execute(request, options);

    // 验证 onStart 被调用，且 hint 被正确传递
    assertThat(lifecycleLogRequests).extracting(lbRequest -> lbRequest.getContext().getHint())
        .contains(callbackTestHint);
    // 验证 onStartRequest 被调用
    assertThat(lifecycleLogStartedRequests).extracting(lbRequest -> lbRequest.getContext().getHint())
        .contains(callbackTestHint);
    // 验证 onComplete 被调用，且包含 HTTP 200 响应
    assertThat(anotherLifecycleLogRequests)
        .extracting(completionContext -> completionContext.getClientResponse().getHttpStatus())
        .contains(HttpStatus.OK);
}
```

#### 步骤 5: 选择实例

```java
ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest);
```

委托给 `BlockingLoadBalancerClient.choose()`:
1. 从 `LoadBalancerClientFactory` 获取该服务的 `ServiceInstanceListSupplier`（实例列表提供者）
2. 通过 `ReactorLoadBalancer`（默认 `RoundRobinLoadBalancer`）选择一个实例
3. 返回 `ServiceInstance`（包含 host、port、metadata）

**返回 null 的场景**:
- Nacos 中没有该服务的注册实例
- 所有实例都不健康
- LoadBalancer 过滤掉了所有实例（如 zone 不匹配）

#### 步骤 6a: 无实例 -> 返回 503

```java
if (instance == null) {
    String message = "Load balancer does not contain an instance for the service " + serviceId;
    LOG.warn(message);
    supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
        .onComplete(new CompletionContext<...>(CompletionContext.Status.DISCARD, lbRequest, lbResponse)));
    return Response.builder()
        .request(request)
        .status(HttpStatus.SERVICE_UNAVAILABLE.value())  // 503
        .body(message, StandardCharsets.UTF_8)
        .build();
}
```

**关键设计**: 不抛异常，而是返回 HTTP 503 Response。这使得 Feign 的 `ErrorDecoder` 可以处理此情况，而不是直接传播 `IOException`。

**`CompletionContext.Status.DISCARD`**: 表示请求被丢弃（未发送到任何实例），与 `SUCCESS` 和 `FAILED` 区分。

**测试验证**:
```java
@Test
void shouldRespondWithServiceUnavailableIfInstanceNotFound() throws IOException {
    Request request = testRequest();
    // loadBalancerClient.choose() 默认返回 null (mock)
    Response response = feignBlockingLoadBalancerClient.execute(request, new Request.Options());
    assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(read(response)).isEqualTo("Load balancer does not contain an instance for the service test");
}
```

#### 步骤 6b: 有实例 -> 重构 URL + 转换 + 执行

```java
String reconstructedUrl = loadBalancerClient.reconstructURI(instance, originalUri).toString();
Request newRequest = buildRequest(request, reconstructedUrl, instance);
return executeWithLoadBalancerLifecycleProcessing(delegate, options, newRequest, lbRequest, lbResponse,
        supportedLifecycleProcessors);
```

**URL 重构**: `http://nacos-lab/discovery/status` -> `http://21.214.97.212:28001/discovery/status`

**`buildRequest(request, reconstructedUrl, instance)`**:

```java
protected Request buildRequest(Request request, String reconstructedUrl) {
    return Request.create(request.httpMethod(), reconstructedUrl, request.headers(), request.body(),
            request.charset(), request.requestTemplate());
}

protected Request buildRequest(Request request, String reconstructedUrl, ServiceInstance instance) {
    Request newRequest = buildRequest(request, reconstructedUrl);
    if (transformers != null) {
        for (LoadBalancerFeignRequestTransformer transformer : transformers) {
            newRequest = transformer.transformRequest(newRequest, instance);
        }
    }
    return newRequest;
}
```

先用重构后的 URL 创建新 Request，然后遍历所有 `LoadBalancerFeignRequestTransformer` 进行转换。

**测试验证**:
```java
@Test
void shouldPassCorrectRequestToDelegate() throws IOException {
    Request request = testRequest();
    Request.Options options = new Request.Options();
    String url = "http://127.0.0.1/path";
    ServiceInstance serviceInstance = new DefaultServiceInstance("test-1", "test", "test-host", 8888, false);
    when(loadBalancerClient.choose(eq("test"), any())).thenReturn(serviceInstance);
    when(loadBalancerClient.reconstructURI(serviceInstance, URI.create("http://test/path")))
        .thenReturn(URI.create(url));

    feignBlockingLoadBalancerClient.execute(request, options);

    ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
    verify(delegate, times(1)).execute(captor.capture(), eq(options));
    Request actualRequest = captor.getValue();
    assertThat(actualRequest.url()).isEqualTo(url);
    // 验证 transformer 添加了 X-InstanceId 和 X-ServiceId 头
    assertThat(actualRequest.headers()).containsEntry("X-ServiceId", Collections.singletonList("test"));
    assertThat(actualRequest.headers()).containsEntry("X-InstanceId", Collections.singletonList("test-1"));
}
```

### 2.3 LoadBalancerUtils.executeWithLoadBalancerLifecycleProcessing()

```java
static Response executeWithLoadBalancerLifecycleProcessing(Client feignClient, Request.Options options,
        Request feignRequest, org.springframework.cloud.client.loadbalancer.Request lbRequest,
        org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse,
        Set<LoadBalancerLifecycle> supportedLifecycleProcessors, boolean loadBalanced) throws IOException {
    // 1. 通知: 请求开始
    supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStartRequest(lbRequest, lbResponse));
    try {
        // 2. 委托执行
        Response response = feignClient.execute(feignRequest, options);
        if (loadBalanced) {
            // 3a. 成功 -> 通知 onComplete(SUCCESS)
            supportedLifecycleProcessors
                .forEach(lifecycle -> lifecycle.onComplete(new CompletionContext<>(CompletionContext.Status.SUCCESS,
                        lbRequest, lbResponse, buildResponseData(response))));
        }
        return response;
    }
    catch (Exception exception) {
        if (loadBalanced) {
            // 3b. 失败 -> 通知 onComplete(FAILED)
            supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onComplete(
                    new CompletionContext<>(CompletionContext.Status.FAILED, exception, lbRequest, lbResponse)));
        }
        throw exception;
    }
}
```

**`loadBalanced` 参数**: 在 `FeignBlockingLoadBalancerClient` 中始终为 `true`（7 参数重载调用 8 参数版本，传 `true`）。在 `RetryableFeignBlockingLoadBalancerClient` 中可能为 `false`（当实例为 null 时使用原始请求，不触发生命周期回调）。

**`buildResponseData()`**: 将 Feign `Response` 转换为 Spring Cloud 的 `ResponseData`（包含状态码、响应头），供 `LoadBalancerLifecycle.onComplete()` 使用。

## 三、buildRequest() 和 LoadBalancerFeignRequestTransformer

### 3.1 两层 buildRequest

| 方法 | 参数 | 作用 |
|------|------|------|
| `buildRequest(request, url)` | 不含 instance | 仅替换 URL，不执行 transformer |
| `buildRequest(request, url, instance)` | 含 instance | 替换 URL + 遍历 transformer 链 |

`buildRequest(request, url)` 是 `protected`，可被子类（如 `RetryableFeignBlockingLoadBalancerClient`）覆盖。

### 3.2 LoadBalancerFeignRequestTransformer 接口

```java
@Order(LoadBalancerFeignRequestTransformer.DEFAULT_ORDER)
public interface LoadBalancerFeignRequestTransformer {

    int DEFAULT_ORDER = 0;

    Request transformRequest(Request request, ServiceInstance instance);
}
```

**`@Order(DEFAULT_ORDER)`**: 默认顺序为 0，可通过 `@Order(1)` 等自定义顺序。

**内置实现**: `XForwardedHeadersTransformer`

```java
public class XForwardedHeadersTransformer implements LoadBalancerFeignRequestTransformer {

    private final ReactiveLoadBalancer.Factory<ServiceInstance> factory;

    @Override
    public Request transformRequest(Request request, ServiceInstance instance) {
        if (instance == null) {
            return request;
        }
        LoadBalancerProperties.XForwarded xForwarded = factory.getProperties(instance.getServiceId()).getXForwarded();
        if (xForwarded.isEnabled()) {
            Map<String, Collection<String>> headers = new HashMap<>(request.headers());
            URI uri = URI.create(request.url());
            String xForwardedHost = uri.getHost();
            String xForwardedProto = uri.getScheme();
            headers.put("X-Forwarded-Host", Collections.singleton(xForwardedHost));
            headers.put("X-Forwarded-Proto", Collections.singleton(xForwardedProto));
            request = Request.create(request.httpMethod(), request.url(), headers, request.body(), request.charset(),
                    request.requestTemplate());
        }
        return request;
    }
}
```

当 `spring.cloud.loadbalancer.x-forwarded.enabled=true` 时，自动添加 `X-Forwarded-Host` 和 `X-Forwarded-Proto` 头，用于服务端获取原始请求信息。

**测试中的自定义 Transformer**:
```java
// 测试中使用两个 transformer，验证链式调用
private static class InstanceIdTransformer implements LoadBalancerFeignRequestTransformer {
    @Override
    public Request transformRequest(Request request, ServiceInstance instance) {
        Map<String, Collection<String>> headers = new HashMap<>(request.headers());
        headers.put("X-InstanceId", Collections.singletonList(instance.getInstanceId()));
        return Request.create(...);
    }
}

private static class ServiceIdTransformer implements LoadBalancerFeignRequestTransformer {
    @Override
    public Request transformRequest(Request request, ServiceInstance instance) {
        headers.put("X-ServiceId", Collections.singletonList(instance.getServiceId()));
        return Request.create(...);
    }
}
```

测试验证两个 transformer 都被执行，且 headers 被正确添加。

## 四、RetryableFeignBlockingLoadBalancerClient

### 4.1 与 FeignBlockingLoadBalancerClient 的关系

`RetryableFeignBlockingLoadBalancerClient` 是 `FeignBlockingLoadBalancerClient` 的**重试版本**，当 Spring Retry 在 classpath 上且 `spring.cloud.loadbalancer.retry.enabled` 未设为 `false` 时启用。

```java
public class RetryableFeignBlockingLoadBalancerClient implements Client {
    private final Client delegate;
    private final LoadBalancerClient loadBalancerClient;
    private final LoadBalancedRetryFactory loadBalancedRetryFactory;  // 额外依赖
    private final LoadBalancerClientFactory loadBalancerClientFactory;
    private final List<LoadBalancerFeignRequestTransformer> transformers;
}
```

### 4.2 重试机制

```java
@Override
public Response execute(Request request, Request.Options options) throws IOException {
    final URI originalUri = URI.create(request.url());
    String serviceId = originalUri.getHost();
    Assert.state(serviceId != null, "Request URI does not contain a valid hostname: " + originalUri);
    final LoadBalancedRetryPolicy retryPolicy = loadBalancedRetryFactory.createRetryPolicy(serviceId,
            loadBalancerClient);
    RetryTemplate retryTemplate = buildRetryTemplate(serviceId, request, retryPolicy);
    return retryTemplate.execute(context -> {
        Request feignRequest = null;
        ServiceInstance retrievedServiceInstance = null;
        Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
                .getSupportedLifecycleProcessors(
                        loadBalancerClientFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
                        RetryableRequestContext.class, ResponseData.class, ServiceInstance.class);
        String hint = getHint(serviceId);
        DefaultRequest<RetryableRequestContext> lbRequest = new DefaultRequest<>(
                new RetryableRequestContext(null, buildRequestData(request), hint));
        // On retries the policy will choose the server and set it in the context
        // and extract the server and update the request being made
        if (context instanceof LoadBalancedRetryContext lbContext) {
            retrievedServiceInstance = lbContext.getServiceInstance();
            if (retrievedServiceInstance == null) {
                ...
                ServiceInstance previousServiceInstance = lbContext.getPreviousServiceInstance();
                lbRequest.getContext().setPreviousServiceInstance(previousServiceInstance);
                supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
                retrievedServiceInstance = loadBalancerClient.choose(serviceId, lbRequest);
                ...
                lbContext.setServiceInstance(retrievedServiceInstance);
            }

            if (retrievedServiceInstance == null) {
                ...
                org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(
                        retrievedServiceInstance);
                supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
                        .onComplete(new CompletionContext<ResponseData, ServiceInstance, RetryableRequestContext>(
                                CompletionContext.Status.DISCARD, lbRequest, lbResponse)));
                feignRequest = request;
            }
            else {
                ...
                String reconstructedUrl = loadBalancerClient.reconstructURI(retrievedServiceInstance, originalUri)
                        .toString();
                feignRequest = buildRequest(request, reconstructedUrl, retrievedServiceInstance);
            }
        }
        org.springframework.cloud.client.loadbalancer.Response<ServiceInstance> lbResponse = new DefaultResponse(
                retrievedServiceInstance);
        LoadBalancerProperties loadBalancerProperties = loadBalancerClientFactory.getProperties(serviceId);
        Response response = LoadBalancerUtils.executeWithLoadBalancerLifecycleProcessing(delegate, options,
                feignRequest, lbRequest, lbResponse, supportedLifecycleProcessors,
                retrievedServiceInstance != null);
        int responseStatus = response.status();
        if (retryPolicy != null && retryPolicy.retryableStatusCode(responseStatus)) {
            ...
            byte[] byteArray = response.body() == null ? new byte[] {}
                    : StreamUtils.copyToByteArray(response.body().asInputStream());
            response.close();
            throw new LoadBalancerResponseStatusCodeException(serviceId, response, byteArray,
                    URI.create(request.url()));
        }
        return response;
    }, new LoadBalancedRecoveryCallback<Response, Response>() {
        @Override
        protected Response createResponse(Response response, URI uri) {
            return response;
        }
    });
}
```

**与 FeignBlockingLoadBalancerClient 的关键差异**:

| 维度 | FeignBlockingLoadBalancerClient | RetryableFeignBlockingLoadBalancerClient |
|------|--------------------------------|----------------------------------------|
| 重试 | 无 | `RetryTemplate` 包装 |
| 实例选择 | 每次 `execute()` 调用一次 | 首次选择，重试时可能换实例 |
| 可重试状态码 | 不处理 | `retryPolicy.retryableStatusCode()` 检查 |
| 上下文类型 | `RequestDataContext` | `RetryableRequestContext`（含 `previousServiceInstance`） |
| 无实例处理 | 返回 503 | 使用原始请求执行（`feignRequest = request`） |
| Lifecycle 类型 | `RequestDataContext` | `RetryableRequestContext` |

### 4.3 RetryTemplate 构建

```java
private RetryTemplate buildRetryTemplate(String serviceId, Request request, LoadBalancedRetryPolicy retryPolicy) {
    RetryTemplate retryTemplate = new RetryTemplate();

    // BackOff 策略
    BackOffPolicy backOffPolicy = this.loadBalancedRetryFactory.createBackOffPolicy(serviceId);
    retryTemplate.setBackOffPolicy(backOffPolicy == null ? new NoBackOffPolicy() : backOffPolicy);

    // Retry Listener
    RetryListener[] retryListeners = this.loadBalancedRetryFactory.createRetryListeners(serviceId);
    if (retryListeners != null && retryListeners.length != 0) {
        retryTemplate.setListeners(retryListeners);
    }

    // Retry 策略
    retryTemplate.setRetryPolicy(
            !loadBalancerClientFactory.getProperties(serviceId).getRetry().isEnabled() || retryPolicy == null
                    ? new NeverRetryPolicy() : new InterceptorRetryPolicy(toHttpRequest(request), retryPolicy,
                            loadBalancerClient, serviceId));
    return retryTemplate;
}
```

- `NeverRetryPolicy`: 当 `spring.cloud.loadbalancer.retry.enabled=false` 时，不重试
- `InterceptorRetryPolicy`: 根据 `LoadBalancedRetryPolicy` 判断是否重试（可重试状态码、重试次数等）
- `NoBackOffPolicy`: 默认无退避（不等待），可通过 `LoadBalancedRetryFactory.createBackOffPolicy()` 自定义

## 五、自动配置: FeignLoadBalancerAutoConfiguration

### 5.1 类声明

```java
@ConditionalOnClass(Feign.class)
@ConditionalOnBean({ LoadBalancerClient.class, LoadBalancerClientFactory.class })
@AutoConfigureBefore(FeignAutoConfiguration.class)
@AutoConfigureAfter({ BlockingLoadBalancerClientAutoConfiguration.class, LoadBalancerAutoConfiguration.class })
@EnableConfigurationProperties(FeignHttpClientProperties.class)
@Configuration(proxyBeanMethods = false)
// Order is important here, last should be the default, first should be optional
@Import({ OkHttpFeignLoadBalancerConfiguration.class, HttpClient5FeignLoadBalancerConfiguration.class,
        Http2ClientFeignLoadBalancerConfiguration.class, DefaultFeignLoadBalancerConfiguration.class })
public class FeignLoadBalancerAutoConfiguration {

    @Bean
    @ConditionalOnBean(LoadBalancerClientFactory.class)
    @ConditionalOnMissingBean(XForwardedHeadersTransformer.class)
    public XForwardedHeadersTransformer xForwarderHeadersFeignTransformer(LoadBalancerClientFactory factory) {
        return new XForwardedHeadersTransformer(factory);
    }
}
```

**条件**:
1. `Feign.class` 在 classpath 上
2. `LoadBalancerClient` 和 `LoadBalancerClientFactory` Bean 存在（spring-cloud-loadbalancer 已自动配置）

**`@AutoConfigureBefore(FeignAutoConfiguration.class)`**: LB 配置先于 Feign 核心配置执行，确保 `FeignAutoConfiguration` 中的 `Client` Bean 创建时，LB 包装已就绪。

**`@Import` 顺序**（源码注释: "Order is important here, last should be the default, first should be optional"）: OkHttp -> HC5 -> Http2 -> Default。后导入的 `@ConditionalOnMissingBean(Client.class)` 确保只有一个 `Client` Bean 生效。

### 5.2 四个导入的配置类

| 配置类 | delegate | 条件 |
|--------|---------|------|
| `OkHttpFeignLoadBalancerConfiguration` | `feign.okhttp.OkHttpClient` | `feign-okhttp` 在 classpath + **`okhttp.enabled` 显式开启**（无 matchIfMissing） |
| `HttpClient5FeignLoadBalancerConfiguration` | `ApacheHttp5Client` | `feign-hc5` 在 classpath + `hc5.enabled` 默认 true |
| `Http2ClientFeignLoadBalancerConfiguration` | `Http2Client` | `http2client.enabled=true` |
| `DefaultFeignLoadBalancerConfiguration` | `Client.Default` (HttpURLConnection) | 回退 |

每个配置类创建两个 Bean:
- `feignClient` (无重试): `FeignBlockingLoadBalancerClient`
- `feignRetryClient` (有重试): `RetryableFeignBlockingLoadBalancerClient`

### 5.3 DefaultFeignLoadBalancerConfiguration

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoadBalancerClientsProperties.class)
class DefaultFeignLoadBalancerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OnRetryNotEnabledCondition.class)
    public Client feignClient(LoadBalancerClient loadBalancerClient,
            LoadBalancerClientFactory loadBalancerClientFactory,
            List<LoadBalancerFeignRequestTransformer> transformers) {
        return new FeignBlockingLoadBalancerClient(new Client.Default(null, null), loadBalancerClient,
                loadBalancerClientFactory, transformers);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.retry.support.RetryTemplate")
    @ConditionalOnBean(LoadBalancedRetryFactory.class)
    @ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", havingValue = "true", matchIfMissing = true)
    public Client feignRetryClient(LoadBalancerClient loadBalancerClient,
            LoadBalancedRetryFactory loadBalancedRetryFactory, LoadBalancerClientFactory loadBalancerClientFactory,
            List<LoadBalancerFeignRequestTransformer> transformers) {
        return new RetryableFeignBlockingLoadBalancerClient(new Client.Default(null, null), loadBalancerClient,
                loadBalancedRetryFactory, loadBalancerClientFactory, transformers);
    }
}
```

**`feignClient` vs `feignRetryClient` 竞争**:
- `feignClient`: `@Conditional(OnRetryNotEnabledCondition.class)` -- 重试**未**启用时
- `feignRetryClient`: 需要四个条件同时满足（`RetryTemplate` class + `LoadBalancedRetryFactory` Bean + `retry.enabled` + `@ConditionalOnMissingBean`）

两者通过 `@ConditionalOnMissingBean` 互斥，只有一个胜出。

### 5.4 OnRetryNotEnabledCondition

```java
public class OnRetryNotEnabledCondition extends AnyNestedCondition {

    public OnRetryNotEnabledCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnMissingClass("org.springframework.retry.support.RetryTemplate")
    static class OnNoRetryTemplateCondition { }

    @ConditionalOnMissingBean(LoadBalancedRetryFactory.class)
    static class OnRetryFactoryCondition { }

    @ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", havingValue = "false")
    static class OnLoadBalancerRetryEnabledCondition { }
}
```

`AnyNestedCondition`（OR 逻辑）-- 满足以下**任一**条件即为 true（重试未启用）:
1. `RetryTemplate` class 不在 classpath 上
2. 没有 `LoadBalancedRetryFactory` Bean
3. `spring.cloud.loadbalancer.retry.enabled=false`

### 5.5 HttpClient5FeignLoadBalancerConfiguration

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ApacheHttp5Client.class)
@ConditionalOnBean({ LoadBalancerClient.class, LoadBalancerClientFactory.class })
@ConditionalOnProperty(value = "spring.cloud.openfeign.httpclient.hc5.enabled", havingValue = "true", matchIfMissing = true)
@Import(HttpClient5FeignConfiguration.class)
@EnableConfigurationProperties(LoadBalancerClientsProperties.class)
class HttpClient5FeignLoadBalancerConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OnRetryNotEnabledCondition.class)
    public Client feignClient(LoadBalancerClient loadBalancerClient, HttpClient httpClient5,
            LoadBalancerClientFactory loadBalancerClientFactory,
            List<LoadBalancerFeignRequestTransformer> transformers) {
        Client delegate = new ApacheHttp5Client(httpClient5);
        return new FeignBlockingLoadBalancerClient(delegate, loadBalancerClient, loadBalancerClientFactory,
                transformers);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.retry.support.RetryTemplate")
    @ConditionalOnBean(LoadBalancedRetryFactory.class)
    @ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", havingValue = "true", matchIfMissing = true)
    public Client feignRetryClient(...) {
        Client delegate = new ApacheHttp5Client(httpClient5);
        return new RetryableFeignBlockingLoadBalancerClient(delegate, loadBalancerClient, loadBalancedRetryFactory,
                loadBalancerClientFactory, transformers);
    }
}
```

与 `DefaultFeignLoadBalancerConfiguration` 的唯一区别: delegate 是 `ApacheHttp5Client` 而非 `Client.Default`。

## 六、getDelegate() - unwrap 机制

```java
// Visible for Sleuth instrumentation
public Client getDelegate() {
    return delegate;
}
```

`getDelegate()` 返回被包装的底层 `Client`。在 `FeignClientFactoryBean.getTarget()` 中（D1 分支 B），当有 URL 时调用:

```java
if (client instanceof FeignBlockingLoadBalancerClient) {
    client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
}
if (client instanceof RetryableFeignBlockingLoadBalancerClient) {
    client = ((RetryableFeignBlockingLoadBalancerClient) client).getDelegate();
}
```

**为什么不直接用 `FeignBlockingLoadBalancerClient`?** 因为有 URL 时不需要负载均衡。`FeignBlockingLoadBalancerClient.execute()` 会从 URL 的 host 提取 serviceId（如 `localhost`），然后 `loadBalancerClient.choose("localhost")` 找不到服务，返回 503。

**注释 "Visible for Sleuth instrumentation"**: `getDelegate()` 也被 Spring Cloud Sleuth（分布式追踪）使用，用于在底层 Client 上添加追踪拦截器。

## 七、完整调用链

```
Feign 代理.method()
  │
  ├── SynchronousMethodHandler.invoke()
  │     ├── SpringEncoder.encode() (D4)
  │     ├── RequestInterceptors.apply() (A-02/A-05/A-08)
  │     └── Client.execute(request, options)
  │           │
  │           └── FeignBlockingLoadBalancerClient.execute()
  │                 │
  │                 ├── 1. URI.create(url) -> getHost() -> serviceId
  │                 ├── 2. getHint(serviceId)
  │                 ├── 3. buildRequestData(request) -> RequestDataContext
  │                 ├── 4. LoadBalancerLifecycle.onStart(lbRequest)
  │                 ├── 5. loadBalancerClient.choose(serviceId, lbRequest)
  │                 │     └── BlockingLoadBalancerClient.choose()
  │                 │           ├── ServiceInstanceListSupplier.get() -> 实例列表
  │                 │           └── RoundRobinLoadBalancer.choose() -> 轮询选择
  │                 │
  │                 ├── [instance == null]
  │                 │     └── 返回 503 + LoadBalancerLifecycle.onComplete(DISCARD)
  │                 │
  │                 └── [instance != null]
  │                       ├── 6. reconstructURI(instance, originalUri)
  │                       │     http://nacos-lab/path -> http://21.214.97.212:28001/path
  │                       ├── 7. buildRequest(request, newUrl, instance)
  │                       │     └── transformers.transformRequest() (XForwarded + 自定义)
  │                       └── 8. LoadBalancerUtils.executeWithLoadBalancerLifecycleProcessing()
  │                             ├── LoadBalancerLifecycle.onStartRequest()
  │                             ├── delegate.execute(newRequest, options)
  │                             │     └── ApacheHttp5Client.execute()
  │                             │           └── HTTP 请求发送
  │                             ├── [成功] LoadBalancerLifecycle.onComplete(SUCCESS)
  │                             └── [失败] LoadBalancerLifecycle.onComplete(FAILED) + throw
  │
  └── SpringDecoder.decode() (D4)
```

## 八、测试分析

### 8.1 FeignBlockingLoadBalancerClientTests (288 行)

测试使用 Mockito mock `Client`、`BlockingLoadBalancerClient`、`LoadBalancerClientFactory`，仅测试 `FeignBlockingLoadBalancerClient` 自身逻辑。

#### 测试用例汇总

| 测试方法 | 验证内容 |
|---------|---------|
| `shouldExtractServiceIdFromRequestUrl` | 从 URL 正确提取 serviceId |
| `shouldThrowExceptionIfNoServiceId` | 空 host 抛 `IllegalStateException` |
| `shouldRespondWithServiceUnavailableIfInstanceNotFound` | 无实例返回 503 |
| `shouldPassCorrectRequestToDelegate` | 重构 URL + transformer 添加头 + 传递给 delegate |
| `shouldExecuteLoadBalancerLifecycleCallbacks` | 生命周期回调正确触发（onStart/onStartRequest/onComplete） |

#### 测试中的 Transformer 验证

```java
private final List<LoadBalancerFeignRequestTransformer> transformers = Arrays.asList(
        new InstanceIdTransformer(),    // 添加 X-InstanceId 头
        new ServiceIdTransformer());    // 添加 X-ServiceId 头

// 验证两个 transformer 都被执行
assertThat(actualRequest.headers()).containsEntry("X-ServiceId", Collections.singletonList("test"));
assertThat(actualRequest.headers()).containsEntry("X-InstanceId", Collections.singletonList("test-1"));
```

#### 测试中的 Lifecycle 验证

```java
// 两个 Lifecycle Bean
loadBalancerLifecycleBeans.put("loadBalancerLifecycle", new TestLoadBalancerLifecycle());
loadBalancerLifecycleBeans.put("anotherLoadBalancerLifecycle", new AnotherLoadBalancerLifecycle());

// 验证:
// 1. onStart 被调用，hint 正确传递
assertThat(lifecycleLogRequests).extracting(lbRequest -> lbRequest.getContext().getHint())
    .contains(callbackTestHint);
// 2. onStartRequest 被调用
assertThat(lifecycleLogStartedRequests).extracting(lbRequest -> lbRequest.getContext().getHint())
    .contains(callbackTestHint);
// 3. onComplete 被调用，HTTP 200 状态码传递
assertThat(anotherLifecycleLogRequests)
    .extracting(completionContext -> completionContext.getClientResponse().getHttpStatus())
    .contains(HttpStatus.OK);
```

## 九、关键设计决策

### 9.1 为什么无实例返回 503 而非抛异常?

返回 HTTP 503 Response 而非 `IOException`:
- Feign 的 `ErrorDecoder` 可以处理 503，返回降级响应
- Sentinel 的 `FallbackFactory` 可以捕获 503 异常，返回 fallback
- 抛 `IOException` 会导致 Feign 直接传播异常，无法被 `ErrorDecoder` 处理

### 9.2 为什么有 URL 时要 unwrap?

`FeignBlockingLoadBalancerClient` 从 URL 的 host 提取 serviceId。当 URL 是 `http://localhost:28001/path` 时，`getHost()` 返回 `"localhost"`，`loadBalancerClient.choose("localhost")` 找不到服务，返回 503。

unwrap 后直接使用 `ApacheHttp5Client`，跳过负载均衡逻辑，直接发送 HTTP 请求。

### 9.3 为什么 transformer 用 List 而非单个?

```java
private final List<LoadBalancerFeignRequestTransformer> transformers;
```

多个 transformer 可以组合使用:
- `XForwardedHeadersTransformer`: 添加 X-Forwarded 头
- 自定义 transformer: 添加实例 ID 头、灰度标识等

transformer 按 `@Order` 排序后链式执行，每个 transformer 接收前一个的输出。

### 9.4 为什么有 Retryable 版本?

`FeignBlockingLoadBalancerClient` 不支持重试 -- 请求失败直接抛异常。

`RetryableFeignBlockingLoadBalancerClient` 使用 `RetryTemplate` 包装:
- 可重试状态码（如 503）自动重试
- 重试时可选择新实例（避免一直打到同一个故障实例）
- 可配置退避策略（BackOffPolicy）

但重试需要 `spring-retry` 依赖，非默认引入。

## 十、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Decorator** | `FeignBlockingLoadBalancerClient` 包装 `Client` | 在不修改原 Client 的前提下增加负载均衡能力 |
| **Strategy** | `LoadBalancerClient.choose()` | 可切换轮询/随机/自定义策略 |
| **Chain of Responsibility** | `LoadBalancerFeignRequestTransformer` 链 | 多个 transformer 按顺序处理请求 |
| **Observer** | `LoadBalancerLifecycle` 回调 | 请求生命周期事件通知 |
| **Template Method** | `buildRequest(request, url)` -> `buildRequest(request, url, instance)` | 子类可覆盖 |
| **Factory** | `FeignLoadBalancerAutoConfiguration` 的四个配置类 | 根据条件创建不同的 Client 实例 |

## 十一、与 D1/D5 的衔接

```
D5: FeignLoadBalancerAutoConfiguration
  └── @Import({OkHttp, HC5, Http2, Default}) 四个配置类
        └── 创建 FeignBlockingLoadBalancerClient Bean
            (delegate = ApacheHttp5Client / Client.Default)
        ↓
D5: FeignClientsConfiguration (子容器)
  └── Feign.Builder + Encoder + Decoder + Contract
        ↓
D1: FeignClientFactoryBean.getTarget()
  ├── [无 URL] loadBalance()
  │     ├── getOptional(context, Client.class) -> FeignBlockingLoadBalancerClient
  │     ├── builder.client(client)
  │     └── targeter.target() -> JDK 代理
  │
  └── [有 URL]
        ├── getOptional(context, Client.class) -> FeignBlockingLoadBalancerClient
        ├── getDelegate() -> ApacheHttp5Client (unwrap)
        └── builder.client(delegate)
```

## 十二、本 Lab 中的实际映射

| Lab 场景 | 对应源码路径 |
|---------|-------------|
| A-07 负载均衡 20 次调用 | `execute()` -> `loadBalancerClient.choose("nacos-lab")` -> 轮询选择实例 |
| A-07 端口分布 10:10 | `RoundRobinLoadBalancer` 交替选择 28001 和 28003 实例 |
| A-07 无实例时 503 | `instance == null` -> `Response.builder().status(503)` |
| A-03 `NacosLabDirectClient(url=...)` | D1 分支 B: `getDelegate()` unwrap -> 跳过 `FeignBlockingLoadBalancerClient` |
| A-08 `VersionInterceptor` | 不经过 `LoadBalancerFeignRequestTransformer`（那是 LB 层），而是 `RequestInterceptor`（Feign 层） |
