# D3: LoadBalancerClientConfiguration 自动装配

> 源码版本: spring-cloud-commons **v4.1.4**（tag；运行时对齐 SC 2023.0.3；4.3.2/5.x 新特性以注记标出）
> 源码路径: `spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/`
> 覆盖文件: `LoadBalancerClientConfiguration.java`(395行)、`LoadBalancerClientFactory.java`(111行)、`LoadBalancerClientSpecification.java`(90行)、`LoadBalancerAutoConfiguration.java`(80行)、`LoadBalancerEnvironmentPropertyUtils.java`(70行)、`LoadBalancerClientsProperties.java`(43行)、`LoadBalancerClientConfigurationRegistrar.java`(82行)

---

## 一、整体架构：三层上下文

Spring Cloud LoadBalancer 的装配体系由三层构成：

```
① 主应用上下文（Parent Context）
   └─ LoadBalancerAutoConfiguration        ← 自动配置入口
        ├─ LoadBalancerClientFactory       ← 工厂（NamedContextFactory）
        ├─ LoadBalancerZoneConfig          ← zone 配置
        └─ LoadBalancerEagerContextInitializer ← 预初始化

② 每个服务的子上下文（Child Context，每个 serviceId 一个）
   └─ LoadBalancerClientConfiguration     ← 默认配置（@ConditionalOnMissingBean 兜底）
        ├─ reactorServiceInstanceLoadBalancer  ← RoundRobinLoadBalancer
        ├─ ServiceInstanceListSupplier 系列    ← 按 configurations 条件选择
        └─ XForwardedHeadersTransformer

③ 每个服务的配置类（用户通过 @LoadBalancerClient 提供）
   └─ 用户自定义配置，覆盖子上下文的默认 Bean
```

`LoadBalancerClientFactory` 继承 `NamedContextFactory`——与 `FeignClientFactory`（openfeign-lab D2 分析过）是**同一个父类**的两兄弟。它们共享"每个客户端一个独立子上下文"的设计模式。

---

## 二、入口：LoadBalancerAutoConfiguration

**位置**: `config/LoadBalancerAutoConfiguration.java`（80 行，很短）

```java
@Configuration(proxyBeanMethods = false)
@LoadBalancerClients                                   // 扫描 @LoadBalancerClient 注解
@EnableConfigurationProperties({ LoadBalancerClientsProperties.class,
        LoadBalancerEagerLoadProperties.class })
@AutoConfigureBefore({ ReactorLoadBalancerClientAutoConfiguration.class,
        LoadBalancerBeanPostProcessorAutoConfiguration.class })
@ConditionalOnProperty(value = "spring.cloud.loadbalancer.enabled", havingValue = "true",
        matchIfMissing = true)                         // 默认开启
public class LoadBalancerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LoadBalancerZoneConfig zoneConfig(Environment environment) {
        return new LoadBalancerZoneConfig(environment.getProperty("spring.cloud.loadbalancer.zone"));
    }

    @Bean
    @ConditionalOnMissingBean
    public LoadBalancerClientFactory loadBalancerClientFactory(LoadBalancerClientsProperties properties,
            ObjectProvider<List<LoadBalancerClientSpecification>> configurations) {
        LoadBalancerClientFactory clientFactory = new LoadBalancerClientFactory(properties);
        clientFactory.setConfigurations(configurations.getIfAvailable(Collections::emptyList));
        return clientFactory;
    }

    @Bean
    public LoadBalancerEagerContextInitializer loadBalancerEagerContextInitializer(
            LoadBalancerClientFactory clientFactory, LoadBalancerEagerLoadProperties properties) {
        return new LoadBalancerEagerContextInitializer(clientFactory, properties.getClients());
    }
    // ...
}
```

关键点：

- **`@LoadBalancerClients`** — 触发 `LoadBalancerClientConfigurationRegistrar` 扫描所有 `@LoadBalancerClient` 注解（见第四节）
- **`@AutoConfigureBefore`** — 必须先于 `ReactorLoadBalancerClientAutoConfiguration`（WebClient 集成）和 `LoadBalancerBeanPostProcessorAutoConfiguration`（@LoadBalanced 注入），因为后者依赖 `LoadBalancerClientFactory`
- **`LoadBalancerClientFactory` 的创建** — 用 `ObjectProvider<List<LoadBalancerClientSpecification>>` 懒加载配置列表（`getIfAvailable(Collections::emptyList)` 兜底为空列表），同样是为了打破循环依赖
- **`LoadBalancerEagerContextInitializer`** — 对 `spring.cloud.loadbalancer.eager-load.clients` 指定的客户端**提前创建子上下文**（默认懒加载，首次调用才创建）

---

## 三、工厂：LoadBalancerClientFactory

**位置**: `support/LoadBalancerClientFactory.java`（111 行）

```java
public class LoadBalancerClientFactory extends NamedContextFactory<LoadBalancerClientSpecification>
        implements ReactiveLoadBalancer.Factory<ServiceInstance> {

    public static final String NAMESPACE = "loadbalancer";
    public static final String PROPERTY_NAME = NAMESPACE + ".client.name";  // "loadbalancer.client.name"

    private final LoadBalancerClientsProperties properties;

    public LoadBalancerClientFactory(LoadBalancerClientsProperties properties) {
        super(LoadBalancerClientConfiguration.class, NAMESPACE, PROPERTY_NAME, new HashMap<>());
        this.properties = properties;
    }

    @Override
    public ReactiveLoadBalancer<ServiceInstance> getInstance(String serviceId) {
        return getInstance(serviceId, ReactorServiceInstanceLoadBalancer.class);
    }

    @Override
    public LoadBalancerProperties getProperties(String serviceId) {
        if (serviceId == null || !properties.getClients().containsKey(serviceId)) {
            return properties;   // 无客户端专属配置 → 返回默认配置
        }
        return properties.getClients().get(serviceId);   // 客户端专属配置（覆盖默认）
    }
}
```

### 3.1 NamedContextFactory 的三参数构造

`super(LoadBalancerClientConfiguration.class, NAMESPACE, PROPERTY_NAME, ...)` 的三个参数是核心：

| 参数 | 值 | 作用 |
|------|-----|------|
| 默认配置类 | `LoadBalancerClientConfiguration.class` | 每个子上下文的**默认配置**（@ConditionalOnMissingBean 兜底） |
| 命名空间 | `"loadbalancer"` | 子上下文的配置前缀（`spring.cloud.loadbalancer.*`） |
| 属性名 | `"loadbalancer.client.name"` | 子上下文中标识"当前客户端名"的属性 key |

### 3.2 子上下文的创建与销毁

`NamedContextFactory` 内部用 `ConcurrentHashMap<String, GenericApplicationContext>` 缓存子上下文，`getContext(name)` 用**双重检查锁**保证并发安全：

```java
protected GenericApplicationContext getContext(String name) {
    if (!this.contexts.containsKey(name)) {
        synchronized (this.contexts) {
            if (!this.contexts.containsKey(name)) {          // 二次检查
                this.contexts.put(name, createContext(name));
            }
        }
    }
    return this.contexts.get(name);
}
```

`createContext(name)` 的流程：

```
createContext(serviceId)
  ├─ buildContext(serviceId):
  │     ├─ 创建 GenericApplicationContext（AnnotationConfigApplicationContext）
  │     ├─ 设置类加载器（继承父上下文的）
  │     ├─ 添加 MapPropertySource：loadbalancer.client.name = serviceId  ← 标识当前客户端
  │     ├─ setParent(主应用上下文)   ← 继承父上下文 Bean + Environment
  │     └─ 设置 displayName = "LoadBalancerClientFactory-<serviceId>"
  ├─ registerConfigurations(serviceId):   ← 注册默认配置类 + 该客户端 Specification
  └─ refresh()   ← 触发 Bean 创建
```

**每个 serviceId 一个独立子上下文**带来的隔离能力：

- 不同服务的 LoadBalancer 配置互不影响（超时、Supplier 链、策略各不相同）
- 子上下文**总是**设置父上下文（`context.setParent(this.parent)`，NamedContextFactory 无开关控制），继承父上下文的 Bean 和 Environment；子上下文注册的 Bean 优先
- 子上下文销毁时（`destroy()`）逐个 `close()` 并清空缓存

### 3.3 getProperties 的覆盖逻辑

```java
public LoadBalancerProperties getProperties(String serviceId) {
    if (serviceId == null || !properties.getClients().containsKey(serviceId)) {
        return properties;                    // 无专属配置 → 默认
    }
    return properties.getClients().get(serviceId);  // 专属配置覆盖默认
}
```

`LoadBalancerClientsProperties` 继承 `LoadBalancerProperties`，并增加 `clients: Map<String, LoadBalancerProperties>`。**专属配置是"叠加在默认之上"的**——`properties.getClients().get(serviceId)` 返回的对象已经包含了默认值（源码注释明确说明）。

配置层级：
```
spring.cloud.loadbalancer.*                        ← 默认（所有客户端）
spring.cloud.loadbalancer.clients.<serviceId>.*    ← 专属（覆盖默认）
```

### 3.4 getInstance 的返回类型

`getInstance(serviceId)` 委托给 `getInstance(serviceId, ReactorServiceInstanceLoadBalancer.class)`——从子上下文获取 `ReactorServiceInstanceLoadBalancer` 类型的 Bean。默认实现是 `RoundRobinLoadBalancer`（由 `LoadBalancerClientConfiguration` 的 `reactorServiceInstanceLoadBalancer` @Bean 提供，见第五节）。

---

## 四、注解扫描：LoadBalancerClientConfigurationRegistrar

**位置**: `annotation/LoadBalancerClientConfigurationRegistrar.java`（82 行）

与 openfeign-lab 的 `FeignClientsRegistrar` 同模式——`ImportBeanDefinitionRegistrar`：

```java
public class LoadBalancerClientConfigurationRegistrar implements ImportBeanDefinitionRegistrar {

    private static String getClientName(Map<String, Object> client) {
        if (client == null) {
            return null;
        }
        String value = (String) client.get("value");
        if (!StringUtils.hasText(value)) {
            value = (String) client.get("name");
        }
        if (StringUtils.hasText(value)) {
            return value;
        }
        throw new IllegalStateException("Either 'name' or 'value' must be provided in @LoadBalancerClient");
    }

    private static void registerClientConfiguration(BeanDefinitionRegistry registry, Object name,
            Object configuration) {
        BeanDefinitionBuilder builder = BeanDefinitionBuilder
            .genericBeanDefinition(LoadBalancerClientSpecification.class);
        builder.addConstructorArgValue(name);
        builder.addConstructorArgValue(configuration);
        registry.registerBeanDefinition(name + ".LoadBalancerClientSpecification", builder.getBeanDefinition());
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(LoadBalancerClients.class.getName());
        if (attrs != null && attrs.containsKey("value")) {
            AnnotationAttributes[] clients = (AnnotationAttributes[]) attrs.get("value");
            for (AnnotationAttributes client : clients) {
                registerClientConfiguration(registry, getClientName(client), client.get("configuration"));
            }
        }
        if (attrs != null && attrs.containsKey("defaultConfiguration")) {
            // @LoadBalancerClients(defaultConfiguration=...) 注册为全局默认配置
            String name;
            if (metadata.hasEnclosingClass()) {
                name = "default." + metadata.getEnclosingClassName();
            } else {
                name = "default." + metadata.getClassName();
            }
            registerClientConfiguration(registry, name, attrs.get("defaultConfiguration"));
        }
        Map<String, Object> client = metadata.getAnnotationAttributes(LoadBalancerClient.class.getName());
        String name = getClientName(client);
        if (name != null) {
            // 单独使用 @LoadBalancerClient(name=..., configuration=...) 时
            registerClientConfiguration(registry, name, client.get("configuration"));
        }
    }
}
```

**三个注册入口**：

1. `@LoadBalancerClients({@LoadBalancerClient(...), ...})` 的 `value` 数组 → 每个客户端一个 Specification
2. `@LoadBalancerClients(defaultConfiguration = ...)` → 注册为 **`default.<ClassName>`** 的 Specification（全局默认配置，适用于所有客户端）
3. 单独的 `@LoadBalancerClient(name, configuration)` → 注册为 `{name}.LoadBalancerClientSpecification`

其中 `defaultConfiguration` 是**全局默认配置**的入口——和 `spring.cloud.loadbalancer.*` 属性（所有客户端共享）对应，`default.<ClassName>` 这个名字与 `spring.cloud.loadbalancer.clients.<serviceId>` 的默认配置语义对齐。

核心机制：

- `@LoadBalancerClients`（含 `value` 数组 + `defaultConfiguration`）和 `@LoadBalancerClient`（单个）触发此 Registrar
- 每个客户端注册一个 **`LoadBalancerClientSpecification`** BeanDefinition，Bean 名 `{name}.LoadBalancerClientSpecification`；全局默认配置 Bean 名 `default.{ClassName}`
- `LoadBalancerClientSpecification` 持有 `name` + `configuration`（用户配置类数组）

`LoadBalancerClientSpecification` 是实现 `NamedContextFactory.Specification` 的简单 POJO：

```java
public class LoadBalancerClientSpecification implements NamedContextFactory.Specification {
    private String name;
    private Class<?>[] configuration;   // 用户配置类（可多个）
    // getter/setter/equals/hashCode
}
```

**用户配置的注入方式**：

```java
// 方式一：单个客户端（注解在任意配置类/主类上）
@LoadBalancerClient(name = "orders-service", configuration = OrdersLoadBalancerConfig.class)

// 方式二：多个客户端
@LoadBalancerClients({
    @LoadBalancerClient(name = "orders-service", configuration = ConfigA.class),
    @LoadBalancerClient(name = "users-service", configuration = ConfigB.class)
})

// 方式三：全局默认配置（所有客户端共享，等价于属性的 spring.cloud.loadbalancer.* 层）
@LoadBalancerClients(defaultConfiguration = GlobalLoadBalancerConfig.class)
```

用户配置类覆盖子上下文的默认 Bean（如自定义 `ServiceInstanceListSupplier`、`ReactorServiceInstanceLoadBalancer`）。

---

## 五、默认配置：LoadBalancerClientConfiguration

**位置**: `annotation/LoadBalancerClientConfiguration.java`（473 行）

这是每个服务子上下文的**默认配置类**。整类用 `@ConditionalOnDiscoveryEnabled` 修饰，内部按发现客户端类型拆成两个静态内部配置类。

### 5.1 顶层 Bean

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnDiscoveryEnabled
public class LoadBalancerClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(Environment environment,
            LoadBalancerClientFactory loadBalancerClientFactory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new RoundRobinLoadBalancer(
                loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
    }
    // ... 两个内部配置类
}
```

> ⚠️ **勘误（2026-08-01）**：本节早期记录曾包含 `loadBalancerApiVersionParser` Bean（返回
> `SemanticApiVersionParser`），已用 `git show v4.1.4:.../LoadBalancerClientConfiguration.java`
> 核实**本 lab 运行时 4.1.4 版本没有这个 Bean、没有 `ApiVersionParser` 相关 import**（sca-lab 原基线 4.3.2 同样没有）——
> 与 5.3 节的 `api-version` 矩阵条目是同一处版本记录错误（详见 5.3 勘误）。

**默认 LoadBalancer = RoundRobinLoadBalancer**。注意它从 `LoadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class)` 获取 Supplier——懒加载，打破构造循环依赖（D1 分析过的模式）。

**`getLazyProvider`** 与 D1 中的 `ObjectProvider` 是同一模式的不同实现——`NamedContextFactory.getLazyProvider()` 返回一个延迟解析的 `ObjectProvider`。

### 5.2 双支撑配置：Reactive / Blocking

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnReactiveDiscoveryEnabled     // 有响应式发现客户端
@Order(REACTIVE_SERVICE_INSTANCE_SUPPLIER_ORDER)   // 193827465
public static class ReactiveSupportConfiguration {
    // @ConditionalOnBean(ReactiveDiscoveryClient.class) 守卫的 8 个 Supplier @Bean
    // + 1 个 XForwardedHeadersTransformer
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnBlockingDiscoveryEnabled     // 有阻塞发现客户端
@Order(REACTIVE_SERVICE_INSTANCE_SUPPLIER_ORDER + 1)   // 193827466
public static class BlockingSupportConfiguration {
    // @ConditionalOnBean(DiscoveryClient.class) 守卫的 9 个 Supplier @Bean
}
```

**`@Order` 的作用**：两个配置类的 Bean 定义顺序。当**响应式和阻塞 DiscoveryClient 同时存在**时（不常见但可能），`Order` 保证响应式配置先注册。`@ConditionalOnMissingBean` 确保最终只有一个 Supplier 生效。

### 5.3 Supplier @Bean 矩阵

每个内部配置类提供一套 Supplier @Bean，用 `@Conditional` 系列注解按需激活：

| 条件值（`spring.cloud.loadbalancer.configurations`） | Reactive 版本 | Blocking 版本 | 构建的链 |
|:---:|---|---|---|
| `default`（默认） | `withDiscoveryClient().withCaching()` | `withBlockingDiscoveryClient().withCaching()` | [Discovery → Caching] |
| `zone-preference` | `...withZonePreference()` | `...withZonePreference()` | [Discovery → Caching → Zone] |
| `health-check`（WebClient） | `withDiscoveryClient().withHealthChecks()` | — | [Discovery → HealthCheck] |
| `health-check`（RestTemplate） | — | `withBlockingDiscoveryClient().withBlockingHealthChecks()` | [Discovery → HealthCheck] |
| `health-check`（RestClient） | — | `withBlockingDiscoveryClient().withBlockingRestClientHealthChecks()` | [Discovery → HealthCheck] |
| `request-based-sticky-session` | `...withRequestBasedStickySession()` | `...withRequestBasedStickySession()` | [Discovery → Caching → StickySession] |
| `same-instance-preference` | `...withSameInstancePreference()` | `...withSameInstancePreference()` | [Discovery → Caching → SameInstance] |
| `weighted` | `...withWeighted()` | `...withWeighted()` | [Discovery → Caching → Weighted] |
| `subset` | `...withSubset()` | `...withSubset()` | [Discovery → Caching → Subset] |

> ⚠️ **勘误（2026-08-01 A-13 验证时发现）**：早期记录曾把 `api-version` 列入本矩阵，但用
> `git show v4.3.2:.../LoadBalancerClientConfiguration.java` 核实**本 lab 运行时
> spring-cloud-commons 4.1.4 版本完全没有 ApiVersion 相关代码**（sca-lab 原基线 4.3.2 同样没有）。该功能由 PR #1582
> （commit `77895a52`）引入，首次出现在 **v5.0.0-RC1**（对应 Spring Boot 4.x /
> Spring Cloud 2025.1+），比本项目依赖版本晚一个大版本。**本 lab 当前技术栈下
> `configurations=api-version` 不可用**——之前查阅的是本地 clone 仓库的 main 分支
> （已领先到 5.0.x），而非当时项目实际使用的 4.3.2 tag（本 lab 现为 4.1.4），记录时未做版本核对。

**注意**：

- `health-check` 是唯一一个**有多个 Bean 变体**的条件：Reactive 版要求 `ReactiveDiscoveryClient` + `WebClient.Builder` 同时存在；Blocking 版有 RestTemplate 和 RestClient 两个变体（`@ConditionalOnBean({DiscoveryClient.class, RestTemplate.class})` / `{DiscoveryClient.class, RestClient.class}`），谁满足谁生效
- 注意 `health-check` 的链**没有 withCaching()**（源码如此——健康检查结果本身带缓存语义，见 D2 的 `aliveInstancesReplay`），与其他条件不同
- 总计数：Reactive 支撑配置 8 个 Supplier @Bean + 1 个 `XForwardedHeadersTransformer`；Blocking 支撑配置 9 个 Supplier @Bean

**使用方式**：
```yaml
spring:
  cloud:
    loadbalancer:
      configurations: zone-preference    # 全局
      # 或按客户端覆盖:
      clients:
        orders-service:
          configurations: weighted
```

### 5.4 条件注解的实现

每个 Supplier @Bean 用三层条件控制：

```java
@Bean
@ConditionalOnBean(DiscoveryClient.class)          // ① 必须有 DiscoveryClient
@ConditionalOnMissingBean                          // ② 用户未自定义 Supplier
@Conditional(ZonePreferenceConfigurationCondition.class)  // ③ configurations=zone-preference
public ServiceInstanceListSupplier zonePreferenceDiscoveryClientServiceInstanceListSupplier(
        ConfigurableApplicationContext context) {
    return ServiceInstanceListSupplier.builder()
        .withBlockingDiscoveryClient()
        .withCaching()
        .withZonePreference()
        .build(context);
}
```

条件类实现 `Condition` 接口，核心逻辑委托给 `LoadBalancerEnvironmentPropertyUtils`：

```java
static class DefaultConfigurationCondition implements Condition {
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return LoadBalancerEnvironmentPropertyUtils.equalToOrMissingForClientOrDefault(
                context.getEnvironment(), "configurations", "default");
    }
}
```

`LoadBalancerEnvironmentPropertyUtils` 是配置解析的核心工具，支持**客户端级覆盖默认**：

```java
public static boolean equalToForClientOrDefault(Environment environment, String propertySuffix,
        String expectedPropertyValue) {
    String defaultValue = getDefaultPropertyValue(environment, propertySuffix);   // spring.cloud.loadbalancer.<suffix>
    String clientValue = getClientPropertyValue(environment, propertySuffix);     // spring.cloud.loadbalancer.clients.<clientName>.<suffix>
    if (clientValue != null && clientValue.equalsIgnoreCase(expectedPropertyValue)) {
        return true;    // 客户端配置匹配
    }
    return clientValue == null && defaultValue != null
            && defaultValue.equalsIgnoreCase(expectedPropertyValue);  // 客户端未配置 → 默认配置匹配
}

private static String getClientPropertyValue(Environment environment, String propertySuffix) {
    return environment.getProperty("spring.cloud.loadbalancer.clients."
            + LoadBalancerClientFactory.getName(environment) + "." + propertySuffix);
    //              ↑ 子上下文中 loadbalancer.client.name 属性标识当前客户端名
}
```

**`equalToForClientOrDefault` 与 `equalToOrMissingForClientOrDefault` 的区别**：
- `equalToForClientOrDefault`：必须**明确配置**为期望值才匹配（用于 zone-preference 等非默认策略）
- `equalToOrMissingForClientOrDefault`：**未配置也算匹配**（用于 default 策略——不配置 configurations 时默认生效）

### 5.5 重试配置：RetryAware 包装

两个重试配置类负责**给已存在的 Supplier 链套上 RetryAware 装饰器**：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnBlockingDiscoveryEnabled
@ConditionalOnClass(RetryTemplate.class)                       // 阻塞重试需要 spring-retry
@Conditional(BlockingOnAvoidPreviousInstanceAndRetryEnabledCondition.class)
@AutoConfigureAfter(BlockingSupportConfiguration.class)        // 必须晚于 Supplier 创建
@ConditionalOnBean(ServiceInstanceListSupplier.class)
public static class BlockingRetryConfiguration {

    @Bean
    @ConditionalOnBean(DiscoveryClient.class)
    @Primary                                                // ← 覆盖已存在的 Supplier Bean
    public ServiceInstanceListSupplier retryAwareDiscoveryClientServiceInstanceListSupplier(
            ServiceInstanceListSupplier delegate) {         // ← 注入原 Supplier 作为 delegate
        return new RetryAwareServiceInstanceListSupplier(delegate);
    }
}
```

**`@Primary` + 同名方法参数注入**是这里的关键技巧：
1. 原 Supplier Bean 已存在（`@ConditionalOnBean(ServiceInstanceListSupplier.class)` 守卫）
2. 新 Bean 用 `@Primary` 标记，成为**默认选择**
3. 新 Bean 的构造参数 `delegate` 注入**原来的 Supplier**，实现包装

激活条件 `BlockingOnAvoidPreviousInstanceAndRetryEnabledCondition` 是 `AllNestedConditions`（嵌套条件全部满足）：

```java
static final class BlockingOnAvoidPreviousInstanceAndRetryEnabledCondition extends AllNestedConditions {
    private BlockingOnAvoidPreviousInstanceAndRetryEnabledCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(value = "spring.cloud.loadbalancer.retry.enabled", havingValue = "true",
            matchIfMissing = true)                    // ① 重试开关（默认开）
    static class LoadBalancerRetryEnabled {}

    @Conditional(AvoidPreviousInstanceEnabledCondition.class)   // ② avoid-previous-instance（默认开）
    static class AvoidPreviousInstanceEnabled {}
}

static class AvoidPreviousInstanceEnabledCondition implements Condition {
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return LoadBalancerEnvironmentPropertyUtils.trueOrMissingForClientOrDefault(
                context.getEnvironment(), "retry.avoid-previous-instance");
    }
}
```

注意阻塞版和响应式版的条件差异：
- **Blocking**：`retry.enabled` 用 `matchIfMissing=true`（默认开启），且需要 `RetryTemplate` classpath
- **Reactive**：`retry.enabled` 用 `havingValue="true"` 无 `matchIfMissing`（必须显式开启），需要 `RetrySpec`（reactor-extra）

---

## 六、配置覆盖优先级（完整）

从高到低：

```
① 用户配置类（@LoadBalancerClient configuration= / defaultConfiguration=）
      │  子上下文中注册的 @Bean，@ConditionalOnMissingBean 不覆盖它们；
      │  优先级最高——直接替换框架默认的 Supplier、LoadBalancer 等
② spring.cloud.loadbalancer.clients.<serviceId>.*
      │  客户端专属属性（叠加在默认之上，未覆盖的字段继承默认值）
③ spring.cloud.loadbalancer.*
      │  默认属性（所有客户端共享）
④ LoadBalancerClientConfiguration 默认 Bean
      │  @ConditionalOnMissingBean 兜底（RoundRobinLoadBalancer + 默认 Supplier 链）
```

**属性 vs Bean 的两条平行线**：②③ 是**属性**层（通过 `LoadBalancerClientFactory.getProperties()` 读取），①④ 是 **Bean** 层（子上下文的 @Bean 定义）。属性影响 Bean 的行为参数（超时、TTL、路径等），Bean 决定功能组件（Supplier 链、LB 策略）。

**示例**：为 `orders-service` 单独启用 zone-preference 并自定义 Supplier 链

```yaml
spring:
  cloud:
    loadbalancer:
      configurations: default              # 全局默认
      clients:
        orders-service:
          configurations: zone-preference  # 仅该服务启用区域优先
```

---

## 七、与 FeignClientFactory 的对比

`LoadBalancerClientFactory` 与 `FeignClientFactory`（openfeign-lab D2）都继承 `NamedContextFactory`：

| 维度 | LoadBalancerClientFactory | FeignClientFactory |
|------|--------------------------|-------------------|
| 默认配置类 | `LoadBalancerClientConfiguration` | `FeignClientsConfiguration` |
| Specification | `LoadBalancerClientSpecification` | `FeignClientSpecification` |
| 命名空间 | `loadbalancer` | `feign` |
| 客户端名属性 | `loadbalancer.client.name` | `feign.client.name` |
| 触发注解 | `@LoadBalancerClient(s)` | `@FeignClient`（由 `@EnableFeignClients` 扫描） |
| 客户端标识来源 | 服务名（serviceId） | Feign 接口名 / contextId |

---

## 八、面试考点

1. **每个服务为什么有独立的子上下文？**
   配置隔离（超时、Supplier 链、策略互不影响）+ 懒加载（首次调用才创建）。代价是上下文创建开销和内存。

2. **LoadBalancerClientFactory 如何工作？**
   `NamedContextFactory` 用 `ConcurrentHashMap` 缓存子上下文；`getInstance(serviceId, type)` 从子上下文获取 Bean；`getProperties(serviceId)` 返回默认或客户端专属配置。

3. **如何切换负载均衡策略？**
   `spring.cloud.loadbalancer.configurations=zone-preference|weighted|subset|...` 切换 Supplier 链；自定义策略则提供 `@Bean ReactorServiceInstanceLoadBalancer`。

4. **@Primary + 参数注入的包装技巧？**
   `BlockingRetryConfiguration` 用 `@Primary` 覆盖已存在的 Supplier Bean，并通过构造参数注入原 Supplier 作为 delegate——不修改原 Bean 定义，实现装饰器包装。

5. **条件注解的三种类型？**
   `@ConditionalOnBean`（类型存在）、`@ConditionalOnMissingBean`（用户未自定义）、`@Conditional(CustomCondition)`（配置值匹配），三者 AND 组合。
