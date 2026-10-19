# D1: FeignClientFactoryBean 深度源码分析

> 源码版本: Spring Cloud OpenFeign 4.1.3（Spring Cloud 2023.0.3，xhs-project 口径）
> 源码路径: `spring-cloud-openfeign-core/src/main/java/org/springframework/cloud/openfeign/FeignClientFactoryBean.java`
> 源码行数: 705 行
> 测试文件: `FeignClientFactoryTests.java` (163 行), `FeignClientFactoryBeanIntegrationTests.java` (173 行), `FeignBuilderCustomizerTests.java` (256 行)

## 一、类定位与职责

### 1.1 一句话定位

`FeignClientFactoryBean` 是每个 `@FeignClient` 接口背后的 **FactoryBean**，在 Spring 容器首次请求该 Bean 时，通过 `getObject()` 创建一个 JDK 动态代理对象。

### 1.2 接口实现

```java
public class FeignClientFactoryBean
    implements FactoryBean<Object>, InitializingBean, ApplicationContextAware, BeanFactoryAware {
```

| 接口 | 回调方法 | 触发时机 | 作用 |
|------|---------|---------|------|
| `FactoryBean<Object>` | `getObject()` | Spring 首次获取该 Bean 时 | 创建并返回 Feign 代理 |
| `FactoryBean<Object>` | `getObjectType()` | Spring 类型检查时 | 返回 `type` 字段（`@FeignClient` 接口的 Class） |
| `FactoryBean<Object>` | `isSingleton()` | Spring 作用域判断 | 返回 `true`（单例） |
| `InitializingBean` | `afterPropertiesSet()` | 属性注入完成后 | 校验 `contextId` 和 `name` 非空 |
| `ApplicationContextAware` | `setApplicationContext()` | Bean 初始化阶段 | 注入 `ApplicationContext`，同时设置 `beanFactory` |
| `BeanFactoryAware` | `setBeanFactory()` | Bean 初始化阶段 | 注入 `BeanFactory` |

### 1.3 Bean 生命周期完整时序

```
1. FeignClientsRegistrar.eagerlyRegisterFeignClientBeanDefinition()
   └── 创建 BeanDefinition，设置属性值 (url, name, contextId, type, fallback, ...)
       → addPropertyValue("url", ...)
       → addPropertyValue("name", ...)
       → addPropertyValue("fallback", ...)
       → setAutowireMode(AUTOWIRE_BY_TYPE)  ← 注意: 自动注入 byType

2. Spring 容器创建 FeignClientFactoryBean 实例
   └── new FeignClientFactoryBean()  ← 构造函数（仅日志）

3. Spring 注入属性 (从 BeanDefinition 的 PropertyValue 设置)
   └── setName(name)
   └── setContextId(contextId)
   └── setType(type)
   └── setUrl(url)
   └── setFallback(fallback)
   └── setFallbackFactory(fallbackFactory)
   └── setRefreshableClient(refreshableClient)

4. Spring Aware 回调 (顺序: BeanFactoryAware → ApplicationContextAware)
   └── setBeanFactory(beanFactory)     ← 先调用
   └── setApplicationContext(context)  ← 后调用，同时设置 beanFactory = context
       注意: setApplicationContext 会覆盖 setBeanFactory 设置的 beanFactory

5. InitializingBean.afterPropertiesSet()
   └── Assert.hasText(contextId, "Context id must be set")
   └── Assert.hasText(name, "Name must be set")

6. (首次获取 Bean 时) FactoryBean.getObject()
   └── getTarget()  ← 核心！创建 Feign 代理
```

**关键**: `setApplicationContext()` 会将 `beanFactory` 设置为 `applicationContext`（4.1.3:626-630，`beanFactory = context` 在 :629）。这意味着如果 `BeanFactoryAware.setBeanFactory()` 先被调用，`beanFactory` 会被 `ApplicationContext` 覆盖。由于 `ApplicationContext` 本身就是 `BeanFactory` 的超接口，这不影响功能，但解释了为什么 `beanFactory` 字段的赋值有两个来源。

### 1.4 核心字段

```java
// 实例字段 - 每个 @FeignClient 一份（4.1.3:88-120）
private Class<?> type;                    // @FeignClient 接口的 Class
private String name;                      // 服务名 (serviceId > name > value)
private String url;                       // 直连 URL (可选)
private String contextId;                 // 子容器标识 (contextId > name)
private String path;                      // 请求路径前缀
private boolean dismiss404;               // 404 是否视为正常响应
private boolean inheritParentContext = true;  // 是否继承父容器 Bean
private ApplicationContext applicationContext;
private BeanFactory beanFactory;
private Class<?> fallback = void.class;   // 降级实现类
private Class<?> fallbackFactory = void.class;  // 降级工厂类
private int readTimeoutMillis = new Request.Options().readTimeoutMillis();        // 读超时 (毫秒)
private int connectTimeoutMillis = new Request.Options().connectTimeoutMillis();  // 连接超时 (毫秒)
private boolean followRedirects = new Request.Options().isFollowRedirects();      // 是否跟随重定向
private boolean refreshableClient = false;  // 是否启用配置刷新
private final List<FeignBuilderCustomizer> additionalCustomizers = new ArrayList<>();
private String[] qualifiers = new String[] {};
```

**线程安全分析**:
- `additionalCustomizers`: `ArrayList`，非线程安全。但仅在 `getTarget()` 之前通过 `addCustomizer()` 添加，此时 Bean 尚未被并发访问。
- 其他实例字段: 非线程安全，但 `FeignClientFactoryBean` 是单例（`isSingleton()` 返回 `true`），且 `getTarget()` 通常只调用一次。如果 `refreshableClient=true`，`getTarget()` 可能被多次调用（刷新时），此时 `url` 字段会被修改（`getTarget()`，4.1.3:465-481），存在竞态条件。但 Spring 的 `@RefreshScope` 重建通常在单线程上下文中进行，实际风险低。

## 二、WARNING 注释根因分析

```java
/***********************************
 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some
 * lifecycle race condition.
 ***********************************/
```

### 2.1 为什么不能用 @Autowired?

`FeignClientFactoryBean` 不是通过 Spring 组件扫描（`@Component`）注册的，而是通过 `FeignClientsRegistrar` 手动创建 `BeanDefinition` 并注册的：

```java
// FeignClientsRegistrar.eagerlyRegisterFeignClientBeanDefinition()
BeanDefinitionBuilder definition = BeanDefinitionBuilder.genericBeanDefinition(FeignClientFactoryBean.class);
definition.addPropertyValue("url", getUrl(null, attributes));
definition.addPropertyValue("name", name);
// ...
definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
```

`AUTOWIRE_BY_TYPE` 意味着 Spring 会尝试按类型自动注入 `@Autowired` 字段。WARNING 注释提到"lifecycle race condition"导致 NPE，根因可推断如下：

1. **FeignClientFactoryBean 是 FactoryBean**：Spring 在处理 FactoryBean 时，`getObject()` 可能在其他 Bean 依赖该 Feign 客户端时被提前调用。虽然 `@Autowired` 字段注入在 `populateBean()` 阶段完成（早于 `getObject()`），但如果 `@Autowired` 注入的是 `FeignClientFactory` 等 Bean，可能触发循环依赖或提前初始化问题。

2. **ApplicationContext 和 BeanFactory 通过 Aware 接口注入**：这两个字段通过 `ApplicationContextAware` 和 `BeanFactoryAware` 接口注入，发生在 `initializeBean()` 阶段。使用 Aware 接口而非 `@Autowired` 可以确保注入时机在 Spring 的标准生命周期内，避免 `@Autowired` 的后处理器（`AutowiredAnnotationBeanPostProcessor`）与 FactoryBean 的 `getObject()` 之间的时序竞争。

3. **实际表现是 NPE**：当某个 Bean 依赖 Feign 客户端时，Spring 会在依赖解析阶段调用 `FactoryBean.getObject()` 来获取代理对象。如果此时 `@Autowired` 字段尚未注入完成（或注入触发了循环依赖导致提前暴露未完成的实例），`getObject()` 中使用的 `beanFactory` 或 `applicationContext` 就是 `null`，导致 NPE。

> 注：以上是对 WARNING 注释的推断分析，源码注释本身只说"lifecycle race condition"未详细展开。核心结论是：所有属性通过 `BeanDefinition.addPropertyValue()` + setter 注入，不使用 `@Autowired`。

### 2.2 正确的注入方式

所有属性通过 `BeanDefinition.addPropertyValue()` + setter 方法注入。这些属性在 `populateBean()` 阶段设置，早于 `initializeBean()` 阶段的 Aware 回调和 `afterPropertiesSet()`。

```
populateBean()          ← 设置 url, name, contextId, type, fallback...
  ↓
initializeBean()
  ├── Aware 回调        ← setBeanFactory(), setApplicationContext()
  ├── afterPropertiesSet() ← 校验 contextId, name
  └── (后续) getObject()  ← 此时所有字段已就绪
```

## 三、核心方法: getTarget() 完整调用链

### 3.1 方法签名与源码

```java
@SuppressWarnings("unchecked")
<T> T getTarget() {
    FeignClientFactory feignClientFactory = beanFactory != null ? beanFactory.getBean(FeignClientFactory.class)
            : applicationContext.getBean(FeignClientFactory.class);
    Feign.Builder builder = feign(feignClientFactory);
    // 分支 A: 无 URL 且配置中也没有 URL -> 走服务发现
    if (!StringUtils.hasText(url) && !isUrlAvailableInConfig(contextId)) {

        if (LOG.isInfoEnabled()) {
            LOG.info("For '" + name + "' URL not provided. Will try picking an instance via load-balancing.");
        }
        if (!name.startsWith("http://") && !name.startsWith("https://")) {
            url = "http://" + name;
        }
        else {
            url = name;
        }
        url += cleanPath();
        return (T) loadBalance(builder, feignClientFactory, new HardCodedTarget<>(type, name, url));
    }
    // 分支 B: 有 URL -> 直连
    if (StringUtils.hasText(url) && !url.startsWith("http://") && !url.startsWith("https://")) {
        url = "http://" + url;
    }
    Client client = getOptional(feignClientFactory, Client.class);
    if (client != null) {
        if (client instanceof FeignBlockingLoadBalancerClient) {
            // not load balancing because we have a url,
            // but Spring Cloud LoadBalancer is on the classpath, so unwrap
            client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
        }
        if (client instanceof RetryableFeignBlockingLoadBalancerClient) {
            // not load balancing because we have a url,
            // but Spring Cloud LoadBalancer is on the classpath, so unwrap
            client = ((RetryableFeignBlockingLoadBalancerClient) client).getDelegate();
        }
        builder.client(client);
    }

    applyBuildCustomizers(feignClientFactory, builder);

    Targeter targeter = get(feignClientFactory, Targeter.class);
    return targeter.target(this, builder, feignClientFactory, resolveTarget(feignClientFactory, contextId, url));
}
```

### 3.2 分支 A vs 分支 B 对比

| 维度 | 分支 A (无 URL) | 分支 B (有 URL) |
|------|----------------|-----------------|
| 触发条件 | `!hasText(url) && !isUrlAvailableInConfig(contextId)` | 有 url 或配置中有 url |
| URL 来源 | `http://{name}{cleanPath()}` | `@FeignClient(url=...)` 或 YAML 配置 |
| Client 获取 | `loadBalance()` -> `getOptional(context, Client.class)` | `getOptional(feignClientFactory, Client.class)` |
| Client 处理 | 直接使用 LB Client | **unwrap** LB Client 取 delegate |
| Target 构建 | `new HardCodedTarget<>(type, name, url)` | `resolveTarget()` (支持 3 种来源) |

### 3.3 为什么分支 B 要 unwrap LB Client?

当 `spring-cloud-starter-loadbalancer` 在 classpath 上时，`FeignLoadBalancerAutoConfiguration` 会自动创建 `FeignBlockingLoadBalancerClient` 作为 `Client` Bean（包装了 `Client.Default` 或 `ApacheHttp5Client`）。

分支 B 有明确 URL，不需要负载均衡。如果不 unwrap，`FeignBlockingLoadBalancerClient` 会尝试从 URL 的 host 提取 serviceId 进行实例选择，但 URL 是 `http://localhost:28001` 这样的具体地址，`getHost()` 返回 `localhost`，Nacos 中没有 `localhost` 服务，导致 503 错误。

unwrap 后获得真实的 HTTP Client（如 `ApacheHttp5Client`），直接发送 HTTP 请求。

### 3.4 resolveTarget() - 三种 URL 来源

```java
private <T> HardCodedTarget<T> resolveTarget(FeignClientFactory context, String contextId, String url) {
    // 来源 1: @FeignClient(url = "...") 或分支 A 构造的 http://{name}
    if (StringUtils.hasText(url)) {
        return new HardCodedTarget(type, name, url + cleanPath());
    }

    // 来源 2: RefreshableUrl (运行时动态刷新)
    if (refreshableClient) {
        RefreshableUrl refreshableUrl = context.getInstance(contextId,
            RefreshableUrl.class.getCanonicalName() + "-" + contextId, RefreshableUrl.class);
        if (Objects.nonNull(refreshableUrl) && StringUtils.hasText(refreshableUrl.getUrl())) {
            return new RefreshableHardCodedTarget<>(type, name, refreshableUrl, cleanPath());
        }
    }

    // 来源 3: YAML 配置 spring.cloud.openfeign.client.config.{name}.url
    FeignClientProperties.FeignClientConfiguration config = findConfigByKey(contextId);
    if (Objects.isNull(config) || !StringUtils.hasText(config.getUrl())) {
        throw new IllegalStateException(
            "Provide Feign client URL either in @FeignClient() or in config properties.");
    }
    return new PropertyBasedTarget(type, name, config, cleanPath());
}
```

| 来源 | Target 类型 | 触发条件 | URL 可变性 |
|------|------------|---------|-----------|
| 注解 | `HardCodedTarget` | `@FeignClient(url="...")` 或无 URL 走服务发现 | 不可变 |
| 刷新 | `RefreshableHardCodedTarget` | `refreshableClient=true` + `RefreshableUrl` Bean | 运行时可刷新 |
| 配置 | `PropertyBasedTarget` | YAML `client.config.{name}.url` | 配置刷新时可变 |

## 四、feign() - Feign.Builder 的创建

### 4.1 方法源码

```java
protected Feign.Builder feign(FeignClientFactory context) {
    FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
    Logger logger = loggerFactory.create(type);

    Feign.Builder builder = get(context, Feign.Builder.class)
            .logger(logger)
            .encoder(get(context, Encoder.class))
            .decoder(get(context, Decoder.class))
            .contract(get(context, Contract.class));

    configureFeign(context, builder);
    return builder;
}
```

### 4.2 Feign.Builder 的来源 - 三方竞争机制

`get(context, Feign.Builder.class)` 从子容器获取 `Feign.Builder` Bean。该 Bean 的创建由三个配置类竞争：

| 配置类 | 条件 | 创建的 Builder | Bean 名 |
|--------|------|---------------|---------|
| `SentinelFeignAutoConfiguration` | `feign.sentinel.enabled=true` | `SentinelFeign.builder()` | `feignSentinelBuilder` |
| `CircuitBreakerPresentFeignBuilderConfiguration` | `circuitbreaker.enabled=true` + `CircuitBreakerFactory` 存在 | `FeignCircuitBreaker.builder()` | `circuitBreakerFeignBuilder` |
| `DefaultFeignBuilderConfiguration` | 熔断未启用 | `Feign.builder().retryer(retryer)` | `feignBuilder` |

**竞争机制**: 三个 Bean 都标注 `@Scope("prototype")` + `@ConditionalOnMissingBean`。

1. 如果 `feign.sentinel.enabled=true`：`SentinelFeignAutoConfiguration` 在**父容器**中注册 `feignSentinelBuilder` Bean。`FeignClientsConfiguration` 的配置类在**子容器**中加载时，`@ConditionalOnMissingBean(Feign.Builder.class)` 会检查父容器（子容器继承父容器），发现已存在 -> 跳过创建。因此 Sentinel 的 Builder 胜出。

2. 如果 `circuitbreaker.enabled=true` 但 Sentinel 未启用：`CircuitBreakerPresentFeignBuilderConfiguration` 在子容器中创建 `FeignCircuitBreaker.builder()`，`DefaultFeignBuilderConfiguration` 的 `@ConditionalOnMissingBean` 检测到已存在 -> 跳过。

3. 如果都未启用：`DefaultFeignBuilderConfiguration` 的 `Feign.builder()` 胜出。

**注意**: 三个 Bean 都是 `@Scope("prototype")`，每次 `get(context, Feign.Builder.class)` 都创建新实例。这是必要的，因为 `Feign.Builder` 是有状态的 Builder 模式，配置完成后调用 `target()` 生成代理，不能复用。

### 4.3 四个核心组件的来源

```java
Feign.Builder builder = get(context, Feign.Builder.class)  // 子容器 (FeignClientsConfiguration)
    .logger(logger)                                          // FeignLoggerFactory.create(type)
    .encoder(get(context, Encoder.class))                    // SpringEncoder
    .decoder(get(context, Decoder.class))                    // OptionalDecoder(ResponseEntityDecoder(SpringDecoder))
    .contract(get(context, Contract.class));                 // SpringMvcContract
```

| 组件 | 默认实现 | 来源 |
|------|---------|------|
| `Feign.Builder` | `Feign.builder()` / `SentinelFeign.builder()` / `FeignCircuitBreaker.builder()` | `FeignClientsConfiguration` 内部配置类 |
| `Encoder` | `SpringEncoder` (包装 `SpringFormEncoder`) | `FeignClientsConfiguration.feignEncoder()` |
| `Decoder` | `OptionalDecoder(ResponseEntityDecoder(SpringDecoder))` | `FeignClientsConfiguration.feignDecoder()` |
| `Contract` | `SpringMvcContract` | `FeignClientsConfiguration.feignContract()` |
| `FeignLoggerFactory` | `DefaultFeignLoggerFactory` | `FeignClientsConfiguration.feignLoggerFactory()` |

这些 Bean 都从**子容器**（`FeignClientFactory` 为每个客户端创建的 `AnnotationConfigApplicationContext`）获取，实现了配置隔离。

## 五、配置体系: 三阶段合并机制

### 5.1 configureFeign() - 配置入口

```java
protected void configureFeign(FeignClientFactory context, Feign.Builder builder) {
    FeignClientProperties properties = beanFactory != null ? beanFactory.getBean(FeignClientProperties.class)
            : applicationContext.getBean(FeignClientProperties.class);

    FeignClientConfigurer feignClientConfigurer = getOptional(context, FeignClientConfigurer.class);
    setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());

    if (properties != null && inheritParentContext) {
        if (properties.isDefaultToProperties()) {
            // 模式 1: Bean 优先, Properties 覆盖 (默认)
            configureUsingConfiguration(context, builder);
            configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()),
                    properties.getConfig().get(contextId), builder);
        }
        else {
            // 模式 2: Properties 优先, Bean 覆盖
            configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()),
                    properties.getConfig().get(contextId), builder);
            configureUsingConfiguration(context, builder);
        }
        configureDefaultRequestElements(properties.getConfig().get(properties.getDefaultConfig()),
                properties.getConfig().get(contextId), builder);
    }
    else {
        // 模式 3: 仅 Bean 配置
        configureUsingConfiguration(context, builder);
    }
}
```

### 5.2 三种配置模式

由 `FeignClientProperties.defaultToProperties`（默认 `true`）和 `inheritParentContext` 控制：

| 模式 | 条件 | 执行顺序 | 最终效果 |
|------|------|---------|---------|
| Bean 优先 | `defaultToProperties=true` (默认) + `inheritParentContext=true` | Bean -> YAML | YAML 覆盖 Bean |
| YAML 优先 | `defaultToProperties=false` + `inheritParentContext=true` | YAML -> Bean | Bean 覆盖 YAML |
| 仅 Bean | `inheritParentContext=false` | Bean only | 不读 YAML |

### 5.3 configureUsingConfiguration() - 从子容器获取 Bean

```java
protected void configureUsingConfiguration(FeignClientFactory context, Feign.Builder builder) {
    Logger.Level level = getInheritedAwareOptional(context, Logger.Level.class);
    if (level != null) {
        builder.logLevel(level);
    }
    Retryer retryer = getInheritedAwareOptional(context, Retryer.class);
    if (retryer != null) {
        builder.retryer(retryer);
    }
    ErrorDecoder errorDecoder = getInheritedAwareOptional(context, ErrorDecoder.class);
    if (errorDecoder != null) {
        builder.errorDecoder(errorDecoder);
    }
    else {
        FeignErrorDecoderFactory errorDecoderFactory = getOptional(context, FeignErrorDecoderFactory.class);
        if (errorDecoderFactory != null) {
            ErrorDecoder factoryErrorDecoder = errorDecoderFactory.create(type);
            builder.errorDecoder(factoryErrorDecoder);
        }
    }
    Request.Options options = getInheritedAwareOptional(context, Request.Options.class);
    if (options == null) {
        options = getOptionsByName(context, contextId);  // 从 OptionsFactoryBean 获取
    }

    if (options != null) {
        builder.options(options);
        readTimeoutMillis = options.readTimeoutMillis();
        connectTimeoutMillis = options.connectTimeoutMillis();
        followRedirects = options.isFollowRedirects();
    }
    Map<String, RequestInterceptor> requestInterceptors = getInheritedAwareInstances(context,
            RequestInterceptor.class);
    if (requestInterceptors != null) {
        List<RequestInterceptor> interceptors = new ArrayList<>(requestInterceptors.values());
        AnnotationAwareOrderComparator.sort(interceptors);
        builder.requestInterceptors(interceptors);
    }
    ResponseInterceptor responseInterceptor = getInheritedAwareOptional(context, ResponseInterceptor.class);
    if (responseInterceptor != null) {
        builder.responseInterceptor(responseInterceptor);
    }
    QueryMapEncoder queryMapEncoder = getInheritedAwareOptional(context, QueryMapEncoder.class);
    if (queryMapEncoder != null) {
        builder.queryMapEncoder(queryMapEncoder);
    }
    if (dismiss404) {
        builder.dismiss404();
    }
    ExceptionPropagationPolicy exceptionPropagationPolicy = getInheritedAwareOptional(context,
            ExceptionPropagationPolicy.class);
    if (exceptionPropagationPolicy != null) {
        builder.exceptionPropagationPolicy(exceptionPropagationPolicy);
    }

    Map<String, Capability> capabilities = getInheritedAwareInstances(context, Capability.class);
    if (capabilities != null) {
        capabilities.values().stream().sorted(AnnotationAwareOrderComparator.INSTANCE)
                .forEach(builder::addCapability);
    }
}
```

**`getInheritedAwareOptional/Instances` 的行为**:

```java
protected <T> T getInheritedAwareOptional(FeignClientFactory context, Class<T> type) {
    if (inheritParentContext) {
        return getOptional(context, type);              // 子容器 + 父容器
    } else {
        return context.getInstanceWithoutAncestors(contextId, type);  // 仅子容器
    }
}
```

当 `inheritParentContext=true`（默认）时，先在子容器查找，找不到则到父容器查找。这意味着 `@FeignClient(configuration = MyConfig.class)` 中定义的 Bean 优先于全局 `@Configuration` 中的 Bean。

### 5.4 configureUsingProperties() - 从 YAML 读取

```java
protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration config,
        Feign.Builder builder) {
    if (config == null) {
        return;
    }

    if (config.getLoggerLevel() != null) {
        builder.logLevel(config.getLoggerLevel());
    }

    if (!refreshableClient) {
        connectTimeoutMillis = config.getConnectTimeout() != null ? config.getConnectTimeout()
                : connectTimeoutMillis;
        readTimeoutMillis = config.getReadTimeout() != null ? config.getReadTimeout() : readTimeoutMillis;
        followRedirects = config.isFollowRedirects() != null ? config.isFollowRedirects() : followRedirects;

        builder.options(new Request.Options(connectTimeoutMillis, TimeUnit.MILLISECONDS, readTimeoutMillis,
                TimeUnit.MILLISECONDS, followRedirects));
    }

    if (config.getRetryer() != null) {
        Retryer retryer = getOrInstantiate(config.getRetryer());
        builder.retryer(retryer);
    }

    if (config.getErrorDecoder() != null) {
        ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
        builder.errorDecoder(errorDecoder);
    }

    if (config.getRequestInterceptors() != null && !config.getRequestInterceptors().isEmpty()) {
        // this will add request interceptor to builder, not replace existing
        for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
            RequestInterceptor interceptor = getOrInstantiate(bean);
            builder.requestInterceptor(interceptor);
        }
    }

    if (config.getResponseInterceptor() != null) {
        builder.responseInterceptor(getOrInstantiate(config.getResponseInterceptor()));
    }

    if (Objects.nonNull(config.getEncoder())) {
        builder.encoder(getOrInstantiate(config.getEncoder()));
    }

    if (Objects.nonNull(config.getDecoder())) {
        builder.decoder(getOrInstantiate(config.getDecoder()));
    }

    if (Objects.nonNull(config.getContract())) {
        builder.contract(getOrInstantiate(config.getContract()));
    }

    if (Objects.nonNull(config.getExceptionPropagationPolicy())) {
        builder.exceptionPropagationPolicy(config.getExceptionPropagationPolicy());
    }

    if (config.getCapabilities() != null) {
        config.getCapabilities().stream().map(this::getOrInstantiate).forEach(builder::addCapability);
    }

    if (config.getQueryMapEncoder() != null) {
        builder.queryMapEncoder(getOrInstantiate(config.getQueryMapEncoder()));
    }
}
```

**关键**: 超时配置（connectTimeout/readTimeout/followRedirects）在 `refreshableClient=true` 时**跳过**。这是因为可刷新客户端的超时由 `OptionsFactoryBean` 管理，不能在 `configureUsingProperties` 中硬编码。

### 5.5 双参数重载 - baseConfig + clientConfig 合并

```java
protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration baseConfig,
        FeignClientProperties.FeignClientConfiguration finalConfig, Feign.Builder builder) {
    configureUsingProperties(baseConfig, builder);
    configureUsingProperties(finalConfig, builder);
    Boolean dismiss404 = finalConfig != null && finalConfig.getDismiss404() != null ? finalConfig.getDismiss404()
            : (baseConfig != null && baseConfig.getDismiss404() != null ? baseConfig.getDismiss404() : null);
    if (dismiss404 != null) {
        if (dismiss404) {
            builder.dismiss404();
        }
    }
}
```

### 5.6 配置合并矩阵

| 属性 | Bean 配置 (configureUsingConfiguration) | YAML 配置 (configureUsingProperties) | 合并方式 |
|------|---------------------------------------|--------------------------------------|---------|
| `logLevel` | `builder.logLevel()` | `builder.logLevel()` | 后执行者覆盖 |
| `retryer` | `builder.retryer()` | `builder.retryer()` | 后执行者覆盖 |
| `errorDecoder` | `builder.errorDecoder()` | `builder.errorDecoder()` | 后执行者覆盖 |
| `connectTimeout` | `builder.options()` | `builder.options()` (除非 refreshableClient) | 后执行者覆盖 |
| `readTimeout` | 同上 | 同上 | 同上 |
| `requestInterceptors` | `builder.requestInterceptors()` (替换) | `builder.requestInterceptor()` (追加) | **追加**而非替换 |
| `responseInterceptor` | `builder.responseInterceptor()` | `builder.responseInterceptor()` | 后执行者覆盖 |
| `encoder` | `builder.encoder()` | `builder.encoder()` (如果非 null) | 后执行者覆盖 |
| `decoder` | `builder.decoder()` | `builder.decoder()` (如果非 null) | 后执行者覆盖 |
| `contract` | `builder.contract()` | `builder.contract()` (如果非 null) | 后执行者覆盖 |
| `dismiss404` | `builder.dismiss404()` (如果 true) | 特殊合并逻辑 | finalConfig > baseConfig |
| `capabilities` | `builder.addCapability()` (追加) | `builder.addCapability()` (追加) | **双方追加** |
| `defaultRequestHeaders` | 不在此方法 | `configureDefaultRequestElements()` | 单独阶段 |
| `defaultQueryParameters` | 不在此方法 | `configureDefaultRequestElements()` | 单独阶段 |

**重要发现**: `requestInterceptors` 在 Bean 配置中是 `builder.requestInterceptors(list)`（**替换**全部），在 YAML 配置中是 `builder.requestInterceptor(one)`（**追加**单个）。这意味着如果 `defaultToProperties=true`（默认），Bean 的 interceptors 先设置，然后 YAML 的 interceptors 追加进去。

## 六、refreshableClient 机制

### 6.1 触发条件

```java
// FeignClientsRegistrar
private boolean isClientRefreshEnabled() {
    return environment.getProperty("spring.cloud.openfeign.client.refresh-enabled", Boolean.class, false);
}
```

`spring.cloud.openfeign.client.refresh-enabled=true` 时启用。

### 6.2 注册的可刷新 Bean

```java
// eagerlyRegisterFeignClientBeanDefinition()
registerRefreshableBeanDefinition(registry, contextId, Request.Options.class, OptionsFactoryBean.class);
registerRefreshableBeanDefinition(registry, contextId, RefreshableUrl.class, RefreshableUrlFactoryBean.class);
```

每个 Feign 客户端额外注册两个 `@RefreshScope` Bean:
- `Request.Options-{contextId}` (由 `OptionsFactoryBean` 创建)
- `RefreshableUrl-{contextId}` (由 `RefreshableUrlFactoryBean` 创建)

### 6.3 OptionsFactoryBean - 可刷新超时配置

```java
public class OptionsFactoryBean implements FactoryBean<Request.Options>, ApplicationContextAware {
    private String contextId;
    private Request.Options options;

    @Override
    public Request.Options getObject() {
        if (options != null) {
            return options;
        }

        options = new Request.Options();  // 默认值
        FeignClientProperties properties = applicationContext.getBean(FeignClientProperties.class);
        // 先用 default 配置覆盖默认值
        options = createOptionsWithApplicableValues(properties.getConfig().get(properties.getDefaultConfig()), options);
        // 再用 client-specific 配置覆盖
        options = createOptionsWithApplicableValues(properties.getConfig().get(contextId), options);
        return options;
    }
}
```

**刷新流程**:
1. Nacos 配置变更 -> Spring `@RefreshScope` 销毁旧 Bean
2. 下次 `getOptionsByName()` 调用时 -> `OptionsFactoryBean.getObject()` 重新执行
3. 从最新的 `FeignClientProperties` 读取超时配置 -> 创建新 `Request.Options`
4. `configureUsingConfiguration()` 获取新的 Options -> 设置到 Builder

### 6.4 refreshableClient 与 configureUsingProperties 的互斥

在 `configureUsingProperties()` 中：
```java
if (!refreshableClient) {
    // 从 YAML 设置超时
    connectTimeoutMillis = config.getConnectTimeout() != null ? ...;
    builder.options(new Request.Options(...));
}
```

当 `refreshableClient=true` 时，超时配置不从 YAML 读取，而是通过 `OptionsFactoryBean` 动态获取。这避免了配置刷新时的冲突。

## 七、loadBalance() - 负载均衡路径

```java
protected <T> T loadBalance(Feign.Builder builder, FeignClientFactory context, HardCodedTarget<T> target) {
    Client client = getOptional(context, Client.class);
    if (client != null) {
        builder.client(client);
        applyBuildCustomizers(context, builder);
        Targeter targeter = get(context, Targeter.class);
        return targeter.target(this, builder, context, target);
    }
    throw new IllegalStateException(
        "No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-loadbalancer?");
}
```

与分支 B 的对比：

| 步骤 | 分支 A (loadBalance) | 分支 B (直连) |
|------|---------------------|--------------|
| 获取 Client | `getOptional(context, Client.class)` | 同左 |
| Client 处理 | 直接使用 (FeignBlockingLoadBalancerClient) | **unwrap** 取 delegate |
| applyBuildCustomizers | ✅ | ✅ |
| Targeter | `get(context, Targeter.class)` | 同左 |
| Target | `HardCodedTarget` (传入) | `resolveTarget()` (新创建) |


## 八、applyBuildCustomizers() - 自定义构建器

```java
private void applyBuildCustomizers(FeignClientFactory context, Feign.Builder builder) {
    Map<String, FeignBuilderCustomizer> customizerMap = context.getInstances(contextId, FeignBuilderCustomizer.class);
    if (customizerMap != null) {
        customizerMap.values().stream()
            .sorted(AnnotationAwareOrderComparator.INSTANCE)
            .forEach(feignBuilderCustomizer -> feignBuilderCustomizer.customize(builder));
    }
    additionalCustomizers.forEach(customizer -> customizer.customize(builder));
}
```

两批 customizer:
1. **子容器中的 `FeignBuilderCustomizer` Bean**: 按 `@Order` 排序后执行
2. **`additionalCustomizers`**: 通过 `addCustomizer()` API 添加的（测试用）

测试验证（`FeignBuilderCustomizerTests`）:
```java
// 两个 customizer 都设置 logLevel，后执行的覆盖
@Bean @Order(1) FeignBuilderCustomizer feignBuilderCustomizer() {
    return builder -> builder.logLevel(Logger.Level.HEADERS);
}
@Bean @Order(2) FeignBuilderCustomizer feignBuilderCustomizer1() {
    return builder -> builder.logLevel(Logger.Level.FULL);
}
// 测试结果: logLevel = FULL (Order(2) 后执行，覆盖 Order(1))
```

## 九、getOrInstantiate() - Bean 获取与回退

```java
private <T> T getOrInstantiate(Class<T> tClass) {
    try {
        return beanFactory != null ? beanFactory.getBean(tClass) : applicationContext.getBean(tClass);
    } catch (NoSuchBeanDefinitionException e) {
        return BeanUtils.instantiateClass(tClass);
    }
}
```

YAML 中配置的类（如 `encoder: com.xxx.MyEncoder`）的处理:
1. 先从 Spring 容器获取 Bean（支持 `@Bean` 注册的带依赖注入的实例）
2. 如果不是 Bean -> `BeanUtils.instantiateClass(tClass)` 直接反射实例化（无依赖注入）

**这意味着**: YAML 中配置的 `encoder`/`decoder`/`contract` 等类，如果需要 `@Autowired` 依赖，必须注册为 Spring Bean。直接写类名只会调用无参构造函数。

## 十、fallback / fallbackFactory 全链路

### 10.1 属性流转

```
@FeignClient(fallback = MyFallback.class)
  ↓
FeignClientsRegistrar.eagerlyRegisterFeignClientBeanDefinition()
  ↓
BeanDefinition.addPropertyValue("fallback", MyFallback.class)
  ↓
FeignClientFactoryBean.setFallback(MyFallback.class)
  ↓
FeignClientFactoryBean.getFallback()  ← Targeter / SentinelFeign 读取
```

### 10.2 使用方

**SentinelFeign.Builder.internalBuild()**（SCA 2023.0.1.2）:
```java
Class fallback = feignClientFactoryBean.getFallback();
if (void.class != fallback) {
    fallbackInstance = getFromContext(beanName, "fallback", fallback,
            target.type());
    return new SentinelInvocationHandler(target, dispatch,
            new FallbackFactory.Default(fallbackInstance));
}
```

**FeignCircuitBreakerTargeter.target()**:
```java
Class<?> fallback = factory.getFallback();
if (fallback != void.class) {
    return targetWithFallback(name, context, target, builder, fallback);
}
```

两者都从 `FeignClientFactoryBean` 读取 fallback 类，然后从 `FeignClientFactory` 子容器获取实例。

### 10.3 getFromContext 的校验

```java
// SentinelFeign（SCA 2023.0.1.2）
private Object getFromContext(String name, String type,
        Class fallbackType, Class targetType) {
    Object fallbackInstance = feignClientFactory.getInstance(name,
            fallbackType);
    if (fallbackInstance == null) {
        throw new IllegalStateException(String.format(
                "No %s instance of type %s found for feign client %s",
                type, fallbackType, name));
    }
    // when fallback is a FactoryBean, should determine the type of instance
    if (fallbackInstance instanceof FactoryBean<?> factoryBean) {
        try {
            fallbackInstance = factoryBean.getObject();
        }
        catch (Exception e) {
            throw new IllegalStateException(type + " create fail", e);
        }
        fallbackType = fallbackInstance.getClass();
    }

    if (!targetType.isAssignableFrom(fallbackType)) {
        throw new IllegalStateException(String.format(
                "Incompatible %s instance. Fallback/fallbackFactory of type %s is not assignable to %s for feign client %s",
                type, fallbackType, targetType, name));
    }
    return fallbackInstance;
}
```

校验:
1. fallback 实例必须存在于子容器
2. 如果是 FactoryBean，获取实际对象
3. fallback 类型必须可赋值给 target 接口

## 十一、测试分析

### 11.1 FeignClientFactoryTests

```java
@Test
public void testChildContexts() {
    AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
    parent.refresh();
    FeignClientFactory context = new FeignClientFactory();
    context.setApplicationContext(parent);
    context.setConfigurations(
            Arrays.asList(getSpec("foo", null, FooConfig.class), getSpec("bar", null, BarConfig.class)));

    Foo foo = context.getInstance("foo", Foo.class);
    assertThat(foo).as("foo was null").isNotNull();

    Bar bar = context.getInstance("bar", Bar.class);
    assertThat(bar).as("bar was null").isNotNull();

    Bar foobar = context.getInstance("foo", Bar.class);
    assertThat(foobar).as("bar was not null").isNull();
}
```

验证了 `NamedContextFactory` 的**子容器隔离**: `foo` 子容器看不到 `bar` 子容器的 Bean。

### 11.2 shouldRedirectToDelegateWhenUrlSet

```java
@Test
@DisabledForJreRange(min = JRE.JAVA_16)  // 反射限制
public void shouldRedirectToDelegateWhenUrlSet() {
    new ApplicationContextRunner().withUserConfiguration(TestConfig.class).run(this::defaultClientUsed);
}

@SuppressWarnings({ "unchecked", "ConstantConditions" })
private void defaultClientUsed(AssertableApplicationContext context) {
    Proxy target = context.getBean(FeignClientFactoryBean.class).getTarget();
    Object invocationHandler = ReflectionTestUtils.getField(target, "h");
    Map<Method, InvocationHandlerFactory.MethodHandler> dispatch = (Map<Method, InvocationHandlerFactory.MethodHandler>) ReflectionTestUtils
            .getField(invocationHandler, "dispatch");
    Method key = new ArrayList<>(dispatch.keySet()).get(0);
    Object client = ReflectionTestUtils.getField(dispatch.get(key), "client");
    assertThat(client).isInstanceOf(Client.Default.class);
}
```

验证了当 URL 设置时（分支 B），`getTarget()` 创建的代理内部使用 `Client.Default`（unwrap 后的底层 Client）。

`@DisabledForJreRange(min = JRE.JAVA_16)`: JDK 16+ 默认禁止深度反射访问 JDK 内部类，需要 `--add-opens`。

### 11.3 FeignClientFactoryBeanIntegrationTests

```java
@Test
void shouldProcessDefaultRequestHeadersPerClient() {
    assertThat(testClientA.headers())
        .contains(entry("x-custom-header-2", List.of("2 from default")),
                  entry("x-custom-header", List.of("from client A")));
    assertThat(testClientB.headers())
        .contains(entry("x-custom-header-2", List.of("2 from default")),
                  entry("x-custom-header", List.of("from client B")));
}
```

验证了 `configureDefaultRequestElements()` 的合并逻辑:
- `x-custom-header-2` 来自 `default` 配置（两个客户端共享）
- `x-custom-header` 来自各自客户端配置（`testClientA` vs `testClientB`）

```java
@Test
void shouldProcessDismiss404PerClient() {
    // testClientA: dismiss404=false -> 404 抛异常
    assertThatExceptionOfType(FeignException.FeignClientException.class)
        .isThrownBy(() -> testClientA.test404());
    // testClientB: dismiss404=true -> 404 正常返回
    assertThatCode(() -> {
        ResponseEntity<String> response404 = testClientB.test404();
        assertThat(response404.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }).doesNotThrowAnyException();
}
```

验证了 `dismiss404` 的客户端级配置生效。

### 11.4 FeignBuilderCustomizerTests

```java
@Test
void testBuildCustomizerOrderedWithAdditional() {
    // 子容器 customizer: @Order(1) HEADERS, @Order(2) FULL, dismiss404
    // additionalCustomizer: BASIC, doNotCloseAfterDecode
    clientFactoryBean.addCustomizer(builder -> builder.logLevel(Logger.Level.BASIC));
    clientFactoryBean.addCustomizer(Feign.Builder::doNotCloseAfterDecode);
    clientFactoryBean.getTarget();

    // 结果: BASIC 覆盖 FULL (additional 后执行)
    assertFeignBuilderField(builder, "logLevel", Logger.Level.BASIC);
    assertFeignBuilderField(builder, "dismiss404", true);
    assertFeignBuilderField(builder, "closeAfterDecode", false);
}
```

验证了 `additionalCustomizers` 在子容器 customizer **之后**执行，覆盖之前的设置。

## 十二、完整调用链汇总

```
Spring 容器首次获取 Feign 客户端 Bean
  │
  ├── FactoryBean.getObject()
  │     └── getTarget()
  │           │
  │           ├── feignClientFactory = beanFactory.getBean(FeignClientFactory.class)
  │           │
  │           ├── feign(feignClientFactory)
  │           │     ├── get(context, FeignLoggerFactory.class) → DefaultFeignLoggerFactory
  │           │     ├── get(context, Feign.Builder.class)     → Feign.builder() / SentinelFeign.builder()
  │           │     ├── get(context, Encoder.class)           → SpringEncoder
  │           │     ├── get(context, Decoder.class)           → OptionalDecoder(ResponseEntityDecoder(SpringDecoder))
  │           │     ├── get(context, Contract.class)          → SpringMvcContract
  │           │     │
  │           │     └── configureFeign(context, builder)
  │           │           ├── properties = beanFactory.getBean(FeignClientProperties.class)
  │           │           ├── feignClientConfigurer = getOptional(context, FeignClientConfigurer.class)
  │           │           ├── setInheritParentContext(configurer.inheritParentConfiguration())
  │           │           │
  │           │           ├── [defaultToProperties=true, 默认]
  │           │           │   ├── configureUsingConfiguration(context, builder)
  │           │           │   │     ├── getInheritedAwareOptional(Logger.Level)    → builder.logLevel()
  │           │           │   │     ├── getInheritedAwareOptional(Retryer)         → builder.retryer()
  │           │           │   │     ├── getInheritedAwareOptional(ErrorDecoder)    → builder.errorDecoder()
  │           │           │   │     ├── getInheritedAwareOptional(Request.Options) → builder.options()
  │           │           │   │     ├── getInheritedAwareInstances(RequestInterceptor) → builder.requestInterceptors()
  │           │           │   │     ├── getInheritedAwareOptional(ResponseInterceptor) → builder.responseInterceptor()
  │           │           │   │     ├── getInheritedAwareOptional(QueryMapEncoder) → builder.queryMapEncoder()
  │           │           │   │     ├── dismiss404 → builder.dismiss404()
  │           │           │   │     ├── getInheritedAwareOptional(ExceptionPropagationPolicy)
  │           │           │   │     └── getInheritedAwareInstances(Capability)     → builder.addCapability()
  │           │           │   │
  │           │           │   ├── configureUsingProperties(baseConfig, clientConfig, builder)
  │           │           │   │     ├── configureUsingProperties(baseConfig, builder)  ← default 配置
  │           │           │   │     ├── configureUsingProperties(clientConfig, builder) ← client 配置 (覆盖)
  │           │           │   │     └── dismiss404 合并: finalConfig > baseConfig
  │           │           │   │
  │           │           │   └── configureDefaultRequestElements(baseConfig, clientConfig, builder)
  │           │           │         ├── addDefaultRequestHeaders() → RequestInterceptor (仅缺失时添加)
  │           │           │         └── addDefaultQueryParams()    → RequestInterceptor (仅缺失时添加)
  │           │           │
  │           │           └── return builder
  │           │
  │           ├── [无 URL] loadBalance(builder, context, HardCodedTarget)
  │           │     ├── client = getOptional(context, Client.class)  → FeignBlockingLoadBalancerClient
  │           │     ├── builder.client(client)
  │           │     ├── applyBuildCustomizers(context, builder)
  │           │     │     ├── 子容器 FeignBuilderCustomizer Bean (按 @Order 排序)
  │           │     │     └── additionalCustomizers (API 添加)
  │           │     ├── targeter = get(context, Targeter.class)  → DefaultTargeter / FeignCircuitBreakerTargeter
  │           │     └── targeter.target(this, builder, context, target)
  │           │           └── feign.target(target) / FeignCircuitBreaker.target(target, fallback)
  │           │                 └── ReflectiveFeign.newInstance(target)
  │           │                       ├── Contract.parseAndValidateMetadata(type)
  │           │                       ├── InvocationHandlerFactory.create(target, dispatch)
  │           │                       │     └── SentinelInvocationHandler / FeignInvocationHandler
  │           │                       └── Proxy.newProxyInstance(...)
  │           │
  │           └── [有 URL]
  │                 ├── client = getOptional(context, Client.class)
  │                 ├── [unwrap] FeignBlockingLoadBalancerClient → getDelegate() → ApacheHttp5Client
  │                 ├── builder.client(client)
  │                 ├── applyBuildCustomizers(context, builder)
  │                 ├── targeter = get(context, Targeter.class)
  │                 └── targeter.target(this, builder, context, resolveTarget(context, contextId, url))
  │                       └── resolveTarget()
  │                             ├── [有 url] new HardCodedTarget(type, name, url + cleanPath())
  │                             ├── [refreshable] new RefreshableHardCodedTarget(type, name, refreshableUrl, cleanPath())
  │                             └── [配置 url] new PropertyBasedTarget(type, name, config, cleanPath())
  │
  └── 返回 JDK 动态代理
```

## 十三、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **FactoryBean** | `FeignClientFactoryBean` 本身 | 延迟创建 Feign 代理 |
| **Builder** | `Feign.Builder` | 链式配置构建 Feign 实例 |
| **Decorator** | `FeignBlockingLoadBalancerClient` 包装 `Client` | 增加负载均衡能力 |
| **Strategy** | `Targeter` 接口 (`DefaultTargeter` / `FeignCircuitBreakerTargeter`) | 不同的代理创建策略 |
| **Template Method** | `feign()` -> `configureFeign()` -> `configureUsingConfiguration/Properties` | 配置流程骨架 |
| **NamedContextFactory** | `FeignClientFactory` | 每个客户端独立子容器 |
| **Adapter** | `FeignOutputMessage` / `FeignResponseAdapter` | Feign ↔ Spring HTTP 适配 |
| **Chain of Responsibility** | `RequestInterceptor` 链 | 请求预处理 |
| **Observer** | `LoadBalancerLifecycle` | 负载均衡生命周期事件 |

## 十四、本 Lab 中的实际映射

| Lab 场景 | 对应源码路径 |
|---------|-------------|
| A-01 `@FeignClient(name="nacos-lab")` | `getTarget()` 分支 A -> `loadBalance()` |
| A-03 `NacosLabDirectClient(url=...)` | `getTarget()` 分支 B -> unwrap LB Client |
| A-05 `spring.cloud.openfeign.compression.request.enabled` | `configureUsingConfiguration()` -> `RequestInterceptor` Bean |
| A-06 `feign.sentinel.enabled=true` | `feign()` -> `get(context, Feign.Builder.class)` -> `SentinelFeign.builder()` |
| A-07 负载均衡 20 次调用 | `loadBalance()` -> `FeignBlockingLoadBalancerClient.execute()` |
| A-08 `@FeignClient(name="nacos-lab-v1")` | 独立子容器 `nacos-lab-v1` + 独立 `Request.Options` |
| A-08 `VersionInterceptor` | `configureUsingConfiguration()` -> `getInheritedAwareInstances(RequestInterceptor.class)` |
