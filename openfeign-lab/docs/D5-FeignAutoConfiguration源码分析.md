# D5: FeignAutoConfiguration + FeignClientsConfiguration 深度源码分析

> 源码版本: Spring Cloud OpenFeign 4.1.3（Spring Cloud 2023.0.3，xhs-project 口径）
> 源码路径:
> - `spring-cloud-openfeign-core/.../FeignAutoConfiguration.java` (425 行)
> - `spring-cloud-openfeign-core/.../FeignClientsConfiguration.java` (261 行)
> - `spring-cloud-openfeign-core/.../FeignClientFactory.java` (80 行)
> - `spring-cloud-openfeign-core/.../FeignCircuitBreakerDisabledConditions.java` (40 行)
> 测试文件: `FeignAutoConfigurationTests.java` (140 行)

## 一、类定位与职责

### 1.1 两个核心配置类

Feign 的自动配置由两个关键类组成:

| 类 | 源码行数 | 职责 | 容器范围 |
|------|---------|------|---------|
| `FeignAutoConfiguration` | 425 行 | 全局 Bean(FeignClientFactory、Targeter、HTTP Client) | 父容器 |
| `FeignClientsConfiguration` | 261 行 | 每个 Feign 客户端的默认 Bean(Encoder/Decoder/Contract/Builder) | 子容器(每个客户端一份) |

### 1.2 FeignAutoConfiguration 类声明

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Feign.class)
@EnableConfigurationProperties({ FeignClientProperties.class, FeignHttpClientProperties.class,
        FeignEncoderProperties.class })
public class FeignAutoConfiguration {
```

**版本差异**: 4.3.2 在此处新增了 `FeignOAuth2Properties.class`（并把 OAuth2 拦截器的 `clientRegistrationId` 从 `@Value` 改为属性类绑定，见 §2.8）。

**依赖条件**: `feign.Feign` class 必须在 classpath 上（Spring Cloud OpenFeign 的基本前提）。

**绑定的配置属性**:

| 属性类 | 前缀 | 源码行 | 说明 |
|--------|------|--------|------|
| `FeignClientProperties` | `spring.cloud.openfeign.client` | `FeignClientProperties.java:49` | 客户端配置(YAML 中的 `client.config.*`) |
| `FeignHttpClientProperties` | `spring.cloud.openfeign.httpclient` | - | HTTP 客户端连接池配置 |
| `FeignEncoderProperties` | `spring.cloud.openfeign.encoder` | - | 编码器配置(charset-from-content-type 等) |

> 4.1.3 没有 `FeignOAuth2Properties`（4.3.2 新增，见 §2.8 版本差异）。

### 1.3 FeignClientsConfiguration 类声明

```java
@Configuration(proxyBeanMethods = false)
public class FeignClientsConfiguration {
```

**没有条件注解** -- 它作为 `FeignClientFactory`（`NamedContextFactory`）的**默认配置类**，在每个 Feign 客户端子容器中自动加载:

```java
// FeignClientFactory 构造函数
public FeignClientFactory() {
    super(FeignClientsConfiguration.class,   // 默认配置类
          "spring.cloud.openfeign",           // 属性前缀
          "spring.cloud.openfeign.client.name", // 子容器名称属性
          applicationContextInitializers);
}
```

`NamedContextFactory` 为每个客户端名称创建一个 `AnnotationConfigApplicationContext`（子容器），加载 `FeignClientsConfiguration` 作为默认配置。

### 1.4 FeignClientFactory - NamedContextFactory

```java
public class FeignClientFactory extends NamedContextFactory<FeignClientSpecification> {

    public FeignClientFactory() {
        this(new HashMap<>());
    }

    public FeignClientFactory(
            Map<String, ApplicationContextInitializer<GenericApplicationContext>> applicationContextInitializers) {
        super(FeignClientsConfiguration.class, "spring.cloud.openfeign", "spring.cloud.openfeign.client.name",
                applicationContextInitializers);
    }
}
```

**继承关系**: `FeignClientFactory` -> `NamedContextFactory<FeignClientSpecification>` -> Spring Cloud Commons

**核心方法**:

| 方法 | 作用 |
|------|------|
| `getContext(name)` | 获取或创建名为 `name` 的子容器（继承自 `NamedContextFactory`） |
| `getInstance(name, type)` | 从子容器获取 Bean（先子容器，再父容器） |
| `getInstance(name, beanName, type)` | 按名称从子容器获取 Bean |
| `getInstanceWithoutAncestors(name, type)` | 仅从子容器获取 Bean（不查父容器） |
| `getInstancesWithoutAncestors(name, type)` | 仅从子容器获取所有 Bean |
| `setConfigurations(specifications)` | 设置客户端配置规格列表 |

**`getInstance` vs `getInstanceWithoutAncestors` 的区别**:
- `getInstance(name, type)`: 先在子容器查找，找不到则在父容器（主容器）查找
- `getInstanceWithoutAncestors(name, type)`: 仅在子容器查找，不查父容器

这个区别在 `FeignClientFactoryBean.getInheritedAwareOptional()` 中使用:
```java
// D1 分析: inheritParentContext=true 时用 getInstance (继承父容器)
// inheritParentContext=false 时用 getInstanceWithoutAncestors (仅子容器)
```

## 二、FeignAutoConfiguration - 全局自动配置

### 2.1 FeignClientFactory Bean 创建

```java
@Autowired(required = false)
private List<FeignClientSpecification> configurations = new ArrayList<>();

@Bean
public FeignClientFactory feignContext() {
    FeignClientFactory context = new FeignClientFactory();
    context.setConfigurations(this.configurations);
    return context;
}
```

**`configurations` 的来源**: `FeignClientsRegistrar.registerClientConfiguration()` 注册的所有 `FeignClientSpecification` Bean（见 D2 分析）。包括:
- `default.{AppClass}.FeignClientSpecification` -- `@EnableFeignClients(defaultConfiguration=...)` 的全局配置
- `{clientName}.FeignClientSpecification` -- 每个 `@FeignClient(configuration=...)` 的客户端配置

`setConfigurations()` 将这些规格传递给 `NamedContextFactory`，后者在创建子容器时加载对应的配置类。

### 2.2 AOT 支持

```java
@Bean
static FeignChildContextInitializer feignChildContextInitializer(GenericApplicationContext parentContext,
        FeignClientFactory feignClientFactory) {
    return new FeignChildContextInitializer(parentContext, feignClientFactory);
}

@Bean
static FeignClientBeanFactoryInitializationAotProcessor feignClientBeanFactoryInitializationCodeGenerator(
        GenericApplicationContext applicationContext, FeignClientFactory feignClientFactory) {
    return new FeignClientBeanFactoryInitializationAotProcessor(applicationContext, feignClientFactory);
}
```

两个 `static` Bean 方法（Spring AOT 友好；类在 `org.springframework.cloud.openfeign.aot` 包）:
- `FeignChildContextInitializer`（`BeanRegistrationAotProcessor`）: AOT 阶段为每个 Feign 子容器生成初始化代码（`ApplicationContextAotGenerator.processAheadOfTime`）
- `FeignClientBeanFactoryInitializationAotProcessor`（`BeanFactoryInitializationAotProcessor`）: 为 GraalVM native image 生成 Feign 客户端的 BeanDefinition 注册代码；它也是 `FeignClientSpecification.className` 的消费方（4.1.3:91，见 D2 §6.3）

### 2.3 FeignHints - RuntimeHintsRegistrar

```java
class FeignHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        if (!ClassUtils.isPresent("feign.Feign", classLoader)) {
            return;
        }
        hints.reflection()
            .registerTypes(
                Set.of(TypeReference.of(FeignClientFactoryBean.class),
                        TypeReference.of(ResponseInterceptor.Chain.class),
                        TypeReference.of(Capability.class)),
                hint -> hint.withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.DECLARED_FIELDS));
    }
}
```

为 GraalVM native image 注册反射提示:
- `FeignClientFactoryBean`: 需要反射访问构造器、方法、字段（因为通过 `BeanDefinition` 手动注册）
- `ResponseInterceptor.Chain`: Feign 内部接口，需要反射创建
- `Capability`: SPI 接口，需要反射实例化

### 2.4 缓存能力

```java
@Bean
@ConditionalOnProperty(value = "spring.cloud.openfeign.cache.enabled", matchIfMissing = true)
@ConditionalOnBean(CacheInterceptor.class)
public Capability cachingCapability(CacheInterceptor cacheInterceptor) {
    return new CachingCapability(cacheInterceptor);
}
```

**两个条件**:
1. `spring.cloud.openfeign.cache.enabled` 未设置或 `true`（默认启用）
2. `CacheInterceptor` Bean 存在（Spring Cache 在 classpath 上且启用）

`CachingCapability` 实现了 Feign 的 `Capability` SPI，为 Feign 客户端添加缓存拦截器。

### 2.5 Jackson 配置

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ Module.class, Page.class, Sort.class })
@ConditionalOnProperty(value = "spring.cloud.openfeign.autoconfiguration.jackson.enabled",
        havingValue = "true", matchIfMissing = true)
protected static class FeignJacksonConfiguration {
    @Bean @ConditionalOnMissingBean(PageJacksonModule.class)
    public PageJacksonModule pageJacksonModule() { return new PageJacksonModule(); }

    @Bean @ConditionalOnMissingBean(SortJacksonModule.class)
    public SortJacksonModule sortModule() { return new SortJacksonModule(); }
}
```

为 Spring Data `Page<T>` 和 `Sort` 提供 Jackson 反序列化支持。当 Feign 客户端方法返回 `Page<Invoice>` 时，`PageJacksonModule` 注册自定义反序列化器，将 JSON 映射为 `Page` 对象。

### 2.6 Targeter - 代理创建策略

#### FeignCircuitBreakerDisabledConditions

```java
class FeignCircuitBreakerDisabledConditions extends AnyNestedCondition {

    FeignCircuitBreakerDisabledConditions() {
        super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnMissingClass("org.springframework.cloud.client.circuitbreaker.CircuitBreaker")
    static class CircuitBreakerClassMissing { }

    @ConditionalOnProperty(value = "spring.cloud.openfeign.circuitbreaker.enabled",
            havingValue = "false", matchIfMissing = true)
    static class CircuitBreakerDisabled { }
}
```

`AnyNestedCondition`（OR 逻辑）-- 满足以下**任一**条件即为 true:
1. `CircuitBreaker` class 不在 classpath 上（未引入 spring-cloud-circuitbreaker）
2. `circuitbreaker.enabled` 未设置（默认 `false`）或显式设为 `false`

#### Targeter 选择完整链路

```
circuitbreaker.enabled 未设置或 false (默认)?
├─ 是 -> FeignCircuitBreakerDisabledConditions = true
│        -> DefaultFeignTargeterConfiguration 激活
│        -> DefaultTargeter
│
└─ 否 (circuitbreaker.enabled = true)
     -> CircuitBreakerPresentFeignTargeterConfiguration 激活
     -> CircuitBreakerFactory 存在?
         ├─ 是 -> FeignCircuitBreakerTargeter
         └─ 否 -> DefaultTargeter (回退)
```

**DefaultFeignTargeterConfiguration** (circuit breaker 禁用时):

```java
@Configuration(proxyBeanMethods = false)
@Conditional(FeignCircuitBreakerDisabledConditions.class)
protected static class DefaultFeignTargeterConfiguration {
    @Bean @ConditionalOnMissingBean
    public Targeter feignTargeter() {
        return new DefaultTargeter();
    }
}
```

**CircuitBreakerPresentFeignTargeterConfiguration** (circuit breaker 启用时):

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(CircuitBreaker.class)
@ConditionalOnProperty(value = "spring.cloud.openfeign.circuitbreaker.enabled", havingValue = "true")
protected static class CircuitBreakerPresentFeignTargeterConfiguration {

    // 回退 1: 无 CircuitBreakerFactory -> DefaultTargeter
    @Bean @ConditionalOnMissingBean(CircuitBreakerFactory.class)
    public Targeter defaultFeignTargeter() {
        return new DefaultTargeter();
    }

    // CircuitBreakerNameResolver: alphanumeric (默认)
    @Bean @ConditionalOnMissingBean(CircuitBreakerNameResolver.class)
    @ConditionalOnProperty(value = "spring.cloud.openfeign.circuitbreaker.alphanumeric-ids.enabled",
            havingValue = "false")
    public CircuitBreakerNameResolver circuitBreakerNameResolver() {
        return new DefaultCircuitBreakerNameResolver();
    }

    // CircuitBreakerNameResolver: alphanumeric (默认)
    @Bean @ConditionalOnMissingBean(CircuitBreakerNameResolver.class)
    @ConditionalOnProperty(value = "spring.cloud.openfeign.circuitbreaker.alphanumeric-ids.enabled",
            havingValue = "true", matchIfMissing = true)
    public CircuitBreakerNameResolver alphanumericCircuitBreakerNameResolver() {
        return new AlphanumericCircuitBreakerNameResolver();
    }

    // 主路径: 有 CircuitBreakerFactory -> FeignCircuitBreakerTargeter
    @SuppressWarnings("rawtypes")
    @Bean @ConditionalOnMissingBean @ConditionalOnBean(CircuitBreakerFactory.class)
    public Targeter circuitBreakerFeignTargeter(CircuitBreakerFactory circuitBreakerFactory,
            @Value("${spring.cloud.openfeign.circuitbreaker.group.enabled:false}") boolean circuitBreakerGroupEnabled,
            CircuitBreakerNameResolver circuitBreakerNameResolver) {
        return new FeignCircuitBreakerTargeter(circuitBreakerFactory, circuitBreakerGroupEnabled,
                circuitBreakerNameResolver);
    }
}
```

**CircuitBreakerNameResolver 两个实现**:

```java
static class DefaultCircuitBreakerNameResolver implements CircuitBreakerNameResolver {
    @Override
    public String resolveCircuitBreakerName(String feignClientName, Target<?> target, Method method) {
        return Feign.configKey(target.type(), method);  // 如 "NacosLabClient#health()"
    }
}

static class AlphanumericCircuitBreakerNameResolver extends DefaultCircuitBreakerNameResolver {
    @Override
    public String resolveCircuitBreakerName(String feignClientName, Target<?> target, Method method) {
        return super.resolveCircuitBreakerName(feignClientName, target, method)
            .replaceAll("[^a-zA-Z0-9]", "");  // 移除非字母数字: "NacosLabClienthealth"
    }
}
```

| 实现 | 条件 | 名称格式 | 示例 |
|------|------|---------|------|
| `DefaultCircuitBreakerNameResolver` | `alphanumeric-ids.enabled=false` | `Class#method()` | `NacosLabClient#health()` |
| `AlphanumericCircuitBreakerNameResolver` | `alphanumeric-ids.enabled=true`(默认) | 纯字母数字 | `NacosLabClienthealth` |

默认使用 `Alphanumeric` 因为某些 CircuitBreaker 实现（如 Resilience4j）不支持特殊字符的名称。

### 2.7 HTTP 客户端

**FeignAutoConfiguration 中的内部配置类**（非负载均衡场景）:

```java
// FeignAutoConfiguration 内部类
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ApacheHttp5Client.class)
@ConditionalOnMissingBean(CloseableHttpClient.class)
@ConditionalOnProperty(value = "spring.cloud.openfeign.httpclient.hc5.enabled",
        havingValue = "true", matchIfMissing = true)
@Import(org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration.class)  // 导入外部配置类
protected static class HttpClient5FeignConfiguration {
    @Bean @ConditionalOnMissingBean(Client.class)
    public Client feignClient(org.apache.hc.client5.http.impl.classic.CloseableHttpClient httpClient5) {
        return new ApacheHttp5Client(httpClient5);
    }
}
```

**注意**: 这里的 `HttpClient5FeignConfiguration` 是 `FeignAutoConfiguration` 的**内部静态类**，它的 `@Import(HttpClient5FeignConfiguration.class)` 导入的是 `org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration`（**外部类**），后者负责创建 `CloseableHttpClient` Bean。

**内部类 vs 外部类的关系**:
- 外部类 `clientconfig.HttpClient5FeignConfiguration`: 创建 `CloseableHttpClient`（连接池配置）
- 内部类 `FeignAutoConfiguration.HttpClient5FeignConfiguration`: 用 `CloseableHttpClient` 创建 `ApacheHttp5Client`（Feign Client 适配）

**三个条件同时满足**:
1. `feign.hc5.ApacheHttp5Client` class 存在
2. 没有自定义 `CloseableHttpClient` Bean
3. `spring.cloud.openfeign.httpclient.hc5.enabled` 未设置或 `true`（默认启用）

**JDK HttpClient**（需显式启用）:

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ Http2Client.class, HttpClient.class })
@ConditionalOnMissingBean(HttpClient.class)
@ConditionalOnProperty("spring.cloud.openfeign.http2client.enabled")
@Import(org.springframework.cloud.openfeign.clientconfig.Http2ClientFeignConfiguration.class)
protected static class Http2ClientFeignConfiguration {
    @Bean @ConditionalOnMissingBean(Client.class)
    public Client feignClient(HttpClient httpClient) {
        return new Http2Client(httpClient);
    }
}
```

需要 `spring.cloud.openfeign.http2client.enabled=true` 显式启用。使用 JDK 11+ 的 `java.net.http.HttpClient`，支持 HTTP/2。

**注意**: 以上是 `FeignAutoConfiguration` 中的配置，仅在**无 LoadBalancer** 时生效。当 LoadBalancer 在 classpath 上时，`FeignLoadBalancerAutoConfiguration` 会包装这些 Client（见 D3 分析）。

### 2.8 OAuth2 支持

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OAuth2AuthorizedClientManager.class)
@ConditionalOnProperty("spring.cloud.openfeign.oauth2.enabled")
protected static class Oauth2FeignConfiguration {

    @Bean
    @ConditionalOnBean({ OAuth2AuthorizedClientService.class, ClientRegistrationRepository.class })
    @ConditionalOnMissingBean
    OAuth2AuthorizedClientManager feignOAuth2AuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService oAuth2AuthorizedClientService) {
        return new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository,
                oAuth2AuthorizedClientService);

    }

    @Bean
    @ConditionalOnBean(OAuth2AuthorizedClientManager.class)
    public OAuth2AccessTokenInterceptor defaultOAuth2AccessTokenInterceptor(
            @Value("${spring.cloud.openfeign.oauth2.clientRegistrationId:}") String clientRegistrationId,
            OAuth2AuthorizedClientManager oAuth2AuthorizedClientManager) {
        return new OAuth2AccessTokenInterceptor(clientRegistrationId, oAuth2AuthorizedClientManager);
    }

}
```

**版本差异**: 4.3.2 把 `@Value("...clientRegistrationId:")` 换成 `FeignOAuth2Properties oAuth2Properties` 参数（属性类绑定）。

在 `spring.cloud.openfeign.oauth2.enabled=true` 时:
1. 创建 `OAuth2AuthorizedClientManager`（管理 OAuth2 令牌）
2. 创建 `OAuth2AccessTokenInterceptor`（`RequestInterceptor`，自动在 Feign 请求中添加 `Authorization: Bearer {token}` 头）

## 三、FeignClientsConfiguration - 子容器默认配置

每个 Feign 客户端子容器都会加载 `FeignClientsConfiguration`，提供以下默认 Bean:

### 3.1 编解码器

```java
@Autowired
private ObjectFactory<HttpMessageConverters> messageConverters;

@Bean
@ConditionalOnMissingBean
public Decoder feignDecoder(ObjectProvider<HttpMessageConverterCustomizer> customizers) {
    return new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(messageConverters, customizers)));
}

@Bean
@ConditionalOnMissingBean
@ConditionalOnMissingClass("org.springframework.data.domain.Pageable")
public Encoder feignEncoder(ObjectProvider<AbstractFormWriter> formWriterProvider,
        ObjectProvider<HttpMessageConverterCustomizer> customizers) {
    return springEncoder(formWriterProvider, encoderProperties, customizers);
}
```

**版本差异**: 4.3.2 引入了 `FeignHttpMessageConverters`（包装 converters + customizers）并改变上述签名；4.1.3 直接传 `messageConverters`（`ObjectFactory<HttpMessageConverters>`）+ `customizers`。

**Decoder 三层包装链**:

```
OptionalDecoder          -> 支持 Optional<T> 返回类型, 404 返回 Optional.empty()
  └── ResponseEntityDecoder -> 支持 ResponseEntity<T> 返回类型, 保留状态码和响应头
      └── SpringDecoder        -> 委托给 messageConverters（ObjectFactory<HttpMessageConverters>）+ customizers 反序列化
```

**Encoder 链**:

```java
private Encoder springEncoder(ObjectProvider<AbstractFormWriter> formWriterProvider,
        FeignEncoderProperties encoderProperties, ObjectProvider<HttpMessageConverterCustomizer> customizers) {
    AbstractFormWriter formWriter = formWriterProvider.getIfAvailable();

    if (formWriter != null) {
        return new SpringEncoder(new SpringPojoFormEncoder(formWriter), messageConverters, encoderProperties,
                customizers);
    }
    else {
        return new SpringEncoder(new SpringFormEncoder(), messageConverters, encoderProperties, customizers);
    }
}
```

- `SpringFormEncoder`: 处理 `multipart/form-data` 和 `application/x-www-form-urlencoded`
- `SpringEncoder`: 委托给 `HttpMessageConverter` 序列化其他类型
- `SpringPojoFormEncoder`: 自定义 form writer 的扩展

**`@ConditionalOnMissingClass("org.springframework.data.domain.Pageable")`**: 当 Spring Data 不在 classpath 时使用基础 Encoder。如果 Spring Data 在 classpath 上，由 `feignEncoderPageable` 提供 `PageableSpringEncoder`（见 3.5）。

### 3.2 Contract

```java
@Bean
@ConditionalOnMissingBean
public Contract feignContract(ConversionService feignConversionService) {
    boolean decodeSlash = feignClientProperties == null || feignClientProperties.isDecodeSlash();
    return new SpringMvcContract(parameterProcessors, feignConversionService, decodeSlash);
}

@Bean
public FormattingConversionService feignConversionService() {
    FormattingConversionService conversionService = new DefaultFormattingConversionService();
    for (FeignFormatterRegistrar feignFormatterRegistrar : feignFormatterRegistrars) {
        feignFormatterRegistrar.registerFormatters(conversionService);
    }
    return conversionService;
}
```

`SpringMvcContract` 将 Spring MVC 注解映射为 Feign 方法元数据:
- `@RequestMapping` / `@GetMapping` / `@PostMapping` -> HTTP 方法和路径
- `@PathVariable` -> URL 路径变量
- `@RequestParam` -> 查询参数
- `@RequestHeader` -> 请求头
- `@RequestBody` -> 请求体

`feignConversionService` 提供参数类型转换（如 `String` -> `LocalDate`），支持通过 `FeignFormatterRegistrar` 扩展。

### 3.3 Feign.Builder - 三方竞争

**默认 Builder** (circuit breaker 禁用时):

```java
@Configuration(proxyBeanMethods = false)
@Conditional(FeignCircuitBreakerDisabledConditions.class)
protected static class DefaultFeignBuilderConfiguration {
    @Bean @Scope("prototype") @ConditionalOnMissingBean
    public Feign.Builder feignBuilder(Retryer retryer) {
        return Feign.builder().retryer(retryer);
    }
}
```

**熔断 Builder** (circuit breaker 启用时):

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(CircuitBreaker.class)
@ConditionalOnProperty("spring.cloud.openfeign.circuitbreaker.enabled")
protected static class CircuitBreakerPresentFeignBuilderConfiguration {

    // 回退: 无 CircuitBreakerFactory -> 默认 Builder
    @Bean @Scope("prototype")
    @ConditionalOnMissingBean({ Feign.Builder.class, CircuitBreakerFactory.class })
    public Feign.Builder defaultFeignBuilder(Retryer retryer) {
        return Feign.builder().retryer(retryer);
    }

    // 主路径: 有 CircuitBreakerFactory -> 熔断 Builder
    @Bean @Scope("prototype") @ConditionalOnMissingBean
    @ConditionalOnBean(CircuitBreakerFactory.class)
    public Feign.Builder circuitBreakerFeignBuilder() {
        return FeignCircuitBreaker.builder();
    }
}
```

**三方竞争机制**:

| 配置类 | 位置 | 条件 | 创建的 Builder |
|--------|------|------|---------------|
| `SentinelFeignAutoConfiguration` | **父容器** | `feign.sentinel.enabled=true` | `SentinelFeign.builder()` |
| `CircuitBreakerPresentFeignBuilderConfiguration` | **子容器** | `circuitbreaker.enabled=true` + `CircuitBreakerFactory` 存在 | `FeignCircuitBreaker.builder()` |
| `DefaultFeignBuilderConfiguration` | **子容器** | circuit breaker 禁用（默认） | `Feign.builder()` |

**竞争逻辑**:
1. `SentinelFeignAutoConfiguration` 在**父容器**注册 `Feign.Builder` Bean
2. `FeignClientsConfiguration` 的内部配置在**子容器**注册 `Feign.Builder` Bean
3. 子容器的 `@ConditionalOnMissingBean` 会检查**父容器**（子容器继承父容器）
4. 如果父容器已有 `Feign.Builder`（Sentinel），子容器跳过创建

**优先级**: Sentinel > CircuitBreaker > 默认

**`@Scope("prototype")`**: 每次获取创建新实例。`FeignClientFactoryBean.feign()` 调用 `get(context, Feign.Builder.class)` 时获取新的 Builder，配置完成后调用 `target()` 生成代理，不能复用。

### 3.4 其他默认 Bean

```java
@Bean @ConditionalOnMissingBean
public Retryer feignRetryer() {
    return Retryer.NEVER_RETRY;  // 默认不重试
}

@Bean @ConditionalOnMissingBean(FeignLoggerFactory.class)
public FeignLoggerFactory feignLoggerFactory() {
    return new DefaultFeignLoggerFactory(logger);
}

@Bean @ConditionalOnMissingBean(FeignClientConfigurer.class)
public FeignClientConfigurer feignClientConfigurer() {
    return new FeignClientConfigurer() {
    };  // 空实现，所有方法返回默认值
}
```

- `Retryer.NEVER_RETRY`: Feign 默认不重试。可通过 `@Bean Retryer` 或 YAML `retryer: xxx` 覆盖
- `DefaultFeignLoggerFactory`: 创建 SLF4J Logger
- `FeignClientConfigurer`: 空实现，`inheritParentConfiguration()` 返回 `true`，`primary()` 返回 `true`

### 3.5 Spring Data 分页支持

```java
@Bean
@ConditionalOnClass(name = "org.springframework.data.domain.Pageable")
@ConditionalOnMissingBean
public Encoder feignEncoderPageable(ObjectProvider<AbstractFormWriter> formWriterProvider,
        ObjectProvider<HttpMessageConverterCustomizer> customizers) {
    PageableSpringEncoder encoder = new PageableSpringEncoder(
            springEncoder(formWriterProvider, encoderProperties, customizers));

    if (springDataWebProperties != null) {
        encoder.setPageParameter(springDataWebProperties.getPageable().getPageParameter());
        encoder.setSizeParameter(springDataWebProperties.getPageable().getSizeParameter());
        encoder.setSortParameter(springDataWebProperties.getSort().getSortParameter());
    }
    return encoder;
}

@Bean
@ConditionalOnClass(name = "org.springframework.data.domain.Pageable")
@ConditionalOnMissingBean
public QueryMapEncoder feignQueryMapEncoderPageable() {
    PageableSpringQueryMapEncoder queryMapEncoder = new PageableSpringQueryMapEncoder();
    if (springDataWebProperties != null) {
        queryMapEncoder.setPageParameter(springDataWebProperties.getPageable().getPageParameter());
        queryMapEncoder.setSizeParameter(springDataWebProperties.getPageable().getSizeParameter());
        queryMapEncoder.setSortParameter(springDataWebProperties.getSort().getSortParameter());
    }
    return queryMapEncoder;
}
```

当 Spring Data `Pageable` 在 classpath 上时:
- `feignEncoder`（`@ConditionalOnMissingClass Pageable`）不生效，由 `feignEncoderPageable` 提供 Encoder
- `PageableSpringEncoder` 包装 `SpringEncoder`，自动将 `Pageable` 参数转换为 `page`/`size`/`sort` 查询参数
- 参数名称从 `springDataWebProperties`（`SpringDataWebProperties`）读取（可自定义）

> 版本差异: 4.3.2 才把这些 Bean 抽成独立的 `SpringDataConfiguration` 嵌套类并使用 `DataWebProperties`；4.1.3 二者均在主类中，字段为 `SpringDataWebProperties`。

### 3.6 Micrometer 指标

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.openfeign.micrometer.enabled", matchIfMissing = true)
@ConditionalOnClass({ MicrometerObservationCapability.class, MicrometerCapability.class, MeterRegistry.class })
@Conditional(FeignClientMicrometerEnabledCondition.class)
protected static class MicrometerConfiguration {

    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean(type = "io.micrometer.observation.ObservationRegistry")
    public MicrometerObservationCapability micrometerObservationCapability(ObservationRegistry registry) {
        return new MicrometerObservationCapability(registry);
    }

    @Bean
    @ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnMissingBean({ MicrometerCapability.class, MicrometerObservationCapability.class })
    public MicrometerCapability micrometerCapability(MeterRegistry registry) {
        return new MicrometerCapability(registry);
    }
}
```

**优先级**: `MicrometerObservationCapability`（Observation API）优先于 `MicrometerCapability`（传统 Metrics API）。两者通过 `@ConditionalOnMissingBean` 互斥。

**`FeignClientMicrometerEnabledCondition`**: 检查 YAML 中 `spring.cloud.openfeign.client.config.{name}.micrometer.enabled` 是否为 `true`（默认），允许客户端级别禁用 Micrometer。

## 四、容器架构

```
Spring 主容器 (ApplicationContext)
  │
  ├── FeignAutoConfiguration (父容器)
  │     ├── FeignClientFactory (NamedContextFactory)
  │     │     └── setConfigurations(FeignClientSpecification 列表)
  │     ├── FeignChildContextInitializer (AOT)
  │     ├── FeignClientBeanFactoryInitializationAotProcessor (AOT)
  │     ├── CachingCapability (如果 CacheInterceptor 存在)
  │     ├── PageJacksonModule / SortJacksonModule
  │     ├── Targeter
  │     │     ├── DefaultTargeter (circuit breaker 禁用)
  │     │     └── FeignCircuitBreakerTargeter (circuit breaker 启用 + CircuitBreakerFactory)
  │     ├── Client
  │     │     ├── ApacheHttp5Client (feign-hc5, 默认)
  │     │     └── Http2Client (JDK HttpClient, 需显式启用)
  │     └── OAuth2AccessTokenInterceptor (如果 oauth2.enabled)
  │
  ├── FeignClientsRegistrar (D2)
  │     └── 注册每个 @FeignClient 的 BeanDefinition
  │
  └── FeignClientFactory (NamedContextFactory)
        │
        ├── 子容器: "nacos-lab"
        │     └── FeignClientsConfiguration (默认配置)
        │           ├── SpringEncoder / SpringDecoder (D4)
        │           ├── SpringMvcContract
        │           ├── Feign.Builder (prototype, D1)
        │           ├── Retryer (NEVER_RETRY)
        │           ├── FeignLoggerFactory
        │           ├── FeignClientConfigurer
        │           ├── feignEncoderPageable (可选: PageableSpringEncoder)
        │           └── MicrometerConfiguration (可选: ObservationCapability)
        │
        ├── 子容器: "nacos-lab-direct"
        │     └── FeignClientsConfiguration (同上, 独立实例)
        │
        └── 子容器: "sentinel-test"
              └── FeignClientsConfiguration (同上, 独立实例)
```

## 五、测试分析

### 5.1 FeignAutoConfigurationTests (159 行)

```java
private final ApplicationContextRunner runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class))
    .withPropertyValues("spring.cloud.openfeign.httpclient.hc5.enabled=false");  // 禁用 HC5
```

测试基线: 禁用 HC5（避免需要 feign-hc5 依赖），仅测试 `FeignAutoConfiguration` 本身。

#### Targeter 选择测试

```java
@Test
void shouldInstantiateDefaultTargeterWhenFeignCircuitBreakerIsDisabled() {
    runner.withPropertyValues("spring.cloud.openfeign.circuitbreaker.enabled=false")
        .run(ctx -> assertOnlyOneTargeterPresent(ctx, DefaultTargeter.class));
}

@Test
void shouldInstantiateFeignCircuitBreakerTargeterWhenEnabled() {
    runner.withBean(CircuitBreakerFactory.class, () -> mock(CircuitBreakerFactory.class))
        .withPropertyValues("spring.cloud.openfeign.circuitbreaker.enabled=true")
        .run(ctx -> {
            assertOnlyOneTargeterPresent(ctx, FeignCircuitBreakerTargeter.class);
            assertThatFeignCircuitBreakerTargeterHasGroupEnabledPropertyWithValue(ctx, false);
            assertThatFeignCircuitBreakerTargeterHasSameCircuitBreakerNameResolver(ctx,
                    AlphanumericCircuitBreakerNameResolver.class);
        });
}
```

验证:
- `circuitbreaker.enabled=false` -> `DefaultTargeter`
- `circuitbreaker.enabled=true` + `CircuitBreakerFactory` 存在 -> `FeignCircuitBreakerTargeter`
- 默认 `CircuitBreakerNameResolver` 是 `AlphanumericCircuitBreakerNameResolver`
- `group.enabled` 默认 `false`

#### Group Enabled 测试

```java
@Test
void shouldInstantiateFeignCircuitBreakerTargeterWithEnabledGroup() {
    runner.withBean(CircuitBreakerFactory.class, () -> mock(CircuitBreakerFactory.class))
        .withPropertyValues("spring.cloud.openfeign.circuitbreaker.enabled=true")
        .withPropertyValues("spring.cloud.openfeign.circuitbreaker.group.enabled=true")
        .run(ctx -> {
            assertOnlyOneTargeterPresent(ctx, FeignCircuitBreakerTargeter.class);
            assertThatFeignCircuitBreakerTargeterHasGroupEnabledPropertyWithValue(ctx, true);
        });
}
```

#### 自定义 CircuitBreakerNameResolver 测试

```java
@Test
void shouldInstantiateFeignCircuitBreakerTargeterWhenEnabledWithCustomCircuitBreakerNameResolver() {
    runner.withBean(CircuitBreakerFactory.class, () -> mock(CircuitBreakerFactory.class))
        .withBean(CircuitBreakerNameResolver.class, CustomCircuitBreakerNameResolver::new)
        .withPropertyValues("spring.cloud.openfeign.circuitbreaker.enabled=true")
        .run(ctx -> {
            assertOnlyOneTargeterPresent(ctx, FeignCircuitBreakerTargeter.class);
            assertThatFeignCircuitBreakerTargeterHasSameCircuitBreakerNameResolver(ctx,
                    CustomCircuitBreakerNameResolver.class);
        });
}
```

验证 `@ConditionalOnMissingBean` 生效: 自定义 `CircuitBreakerNameResolver` 覆盖默认的 `AlphanumericCircuitBreakerNameResolver`。

#### OAuth2 测试

```java
@Test
void shouldInstantiateFeignOAuth2FeignRequestInterceptorWithoutInterceptors() {
    runner.withPropertyValues("spring.cloud.openfeign.oauth2.enabled=true",
            "spring.cloud.openfeign.oauth2.clientRegistrationId=feign-client")
            .withBean(OAuth2AuthorizedClientService.class, () -> mock(OAuth2AuthorizedClientService.class))
            .withBean(ClientRegistrationRepository.class, () -> mock(ClientRegistrationRepository.class))
            .run(ctx -> {
                assertOauth2AccessTokenInterceptorExists(ctx);
                assertThatOauth2AccessTokenInterceptorHasSpecifiedIdsPropertyWithValue(ctx, "feign-client");
            });
}
```

验证: `clientRegistrationId` 属性绑定到 `OAuth2AccessTokenInterceptor.clientRegistrationId` 字段。

> 版本差异: 4.3.2 新增了 `WithDashCaseProperty`（`client-registration-id`）用例与对应属性类绑定；4.1.3 仅有 camelCase 用例（`@Value` 占位符绑定）。

## 六、关键设计决策

### 6.1 为什么用 NamedContextFactory?

`NamedContextFactory` 为每个 Feign 客户端创建隔离的 Spring 子容器，实现:
- **配置隔离**: 每个客户端的 `Encoder`/`Decoder`/`Contract` 互不影响
- **Bean 覆盖**: 子容器可以覆盖 `FeignClientsConfiguration` 中的默认 Bean
- **继承控制**: `inheritParentContext` 控制是否可见父容器 Bean（见 D1 分析）

### 6.2 Feign.Builder 的 prototype scope

`Feign.Builder` Bean 声明为 `@Scope("prototype")`，每次获取时创建新实例。原因:
- `Feign.Builder` 是有状态的（Builder 模式，配置完成后调用 `build()` 或 `target()`）
- 每个 Feign 客户端需要独立的 Builder 实例
- Sentinel/CircuitBreaker 需要替换 Builder 实现

### 6.3 Sentinel Builder 在父容器注册

Sentinel 的 `Feign.Builder` 在**父容器**注册（通过 `SentinelFeignAutoConfiguration`），而非子容器。子容器的 `@ConditionalOnMissingBean` 检查时会查到父容器的 Bean，从而跳过创建。

这是 Sentinel 与 CircuitBreaker 集成方式的根本差异:
- Sentinel: 替换 `Feign.Builder`（`InvocationHandlerFactory` 层面）
- CircuitBreaker: 替换 `Targeter`（代理创建层面）

两者不冲突: 如果同时启用，Sentinel 的 Builder 胜出，但 Targeter 仍然可能是 `FeignCircuitBreakerTargeter`。

### 6.4 内部类 @Import 外部类的设计

`FeignAutoConfiguration.HttpClient5FeignConfiguration`（内部类）通过 `@Import` 导入 `clientconfig.HttpClient5FeignConfiguration`（外部类）:
- 内部类: 负责条件判断和创建 `Client` Bean
- 外部类: 负责创建 `CloseableHttpClient` Bean（连接池配置）

这种分离使得 `CloseableHttpClient` 的创建逻辑可被复用（如 `FeignLoadBalancerAutoConfiguration` 也导入同一个外部类）。

### 6.5 FeignCircuitBreakerDisabledConditions 使用 AnyNestedCondition

```java
class FeignCircuitBreakerDisabledConditions extends AnyNestedCondition {
    @ConditionalOnMissingClass("...CircuitBreaker")
    static class CircuitBreakerClassMissing { }

    @ConditionalOnProperty(value = "...circuitbreaker.enabled", havingValue = "false", matchIfMissing = true)
    static class CircuitBreakerDisabled { }
}
```

`AnyNestedCondition`（OR 逻辑）: 只要 CircuitBreaker class 不在 classpath **或** 属性未启用，就视为"禁用"。这确保:
- 未引入 circuitbreaker 依赖 -> 使用 `DefaultTargeter`
- 引入了但未启用 -> 使用 `DefaultTargeter`
- 引入且启用 -> 使用 `FeignCircuitBreakerTargeter`

## 七、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Factory** | `FeignClientFactory` (NamedContextFactory) | 为每个客户端创建子容器 |
| **Strategy** | `Targeter` 接口 (`DefaultTargeter` / `FeignCircuitBreakerTargeter`) | 不同的代理创建策略 |
| **Decorator** | `OptionalDecoder` -> `ResponseEntityDecoder` -> `SpringDecoder` | 层层包装增强解码能力 |
| **Decorator** | `PageableSpringEncoder` 包装 `SpringEncoder` | 增加分页参数处理 |
| **Builder** | `Feign.Builder` (prototype scope) | 链式配置构建 Feign 实例 |
| **SPI** | `Capability` 接口 (`CachingCapability`, `MicrometerObservationCapability`) | 可扩展的能力插拔 |
| **Condition** | `FeignCircuitBreakerDisabledConditions` (AnyNestedCondition) | OR 条件判断 |
| **Template Method** | `NamedContextFactory.getContext()` | 子类提供默认配置类 |

## 八、与 D1/D2/D3/D4 的衔接

```
D2: FeignClientsRegistrar
  └── 注册 FeignClientSpecification Bean
        ↓
D5: FeignAutoConfiguration.feignContext()
  └── FeignClientFactory.setConfigurations(specifications)
        ↓
D5: FeignClientsConfiguration (子容器默认配置)
  ├── SpringEncoder / SpringDecoder (D4)
  ├── SpringMvcContract
  ├── Feign.Builder (D1 使用)
  └── Retryer / Logger / ...
        ↓
D1: FeignClientFactoryBean.getTarget()
  ├── feign(context) -> get(Feign.Builder) -> get(Encoder/Decoder/Contract)
  ├── configureFeign() -> configureUsingConfiguration() -> get(RequestInterceptor)
  └── loadBalance() -> get(Client)
        ↓                    ↓
D3: FeignBlockingLoadBalancerClient  D6: Targeter.target()
```

## 九、本 Lab 中的实际映射

| Lab 场景 | 对应源码路径 |
|---------|-------------|
| A-01 `@EnableFeignClients` | `FeignClientsRegistrar` -> `FeignAutoConfiguration.feignContext()` 创建 `FeignClientFactory` |
| A-04 HTTP 客户端切换 | `FeignAutoConfiguration.HttpClient5FeignConfiguration` -> `spring.cloud.openfeign.httpclient.hc5.enabled` |
| A-05 压缩拦截器 | `FeignClientsConfiguration` 子容器中的 `RequestInterceptor` Bean -> `configureUsingConfiguration()` |
| A-06 Sentinel 集成 | `SentinelFeignAutoConfiguration`(父容器) 替换 `Feign.Builder` -> 子容器 `@ConditionalOnMissingBean` 跳过 |
| A-07 负载均衡 | `FeignClientFactory` 子容器中的 `Client` Bean -> `FeignBlockingLoadBalancerClient` |
| A-08 多版本 API | 每个版本独立子容器 (`nacos-lab-v1`, `nacos-lab-v2`) -> 独立 `FeignClientsConfiguration` 实例 |
