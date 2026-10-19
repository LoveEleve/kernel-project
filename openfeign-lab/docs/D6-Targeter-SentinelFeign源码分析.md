# D6: Targeter + SentinelFeign 深度源码分析

> 源码版本: Spring Cloud OpenFeign 4.1.3 + Spring Cloud Alibaba 2023.0.1.2（xhs-project 口径）
> 源码路径:
> - `spring-cloud-openfeign-core/.../Targeter.java` (30 行)
> - `spring-cloud-openfeign-core/.../DefaultTargeter.java` (33 行)
> - `spring-cloud-openfeign-core/.../FeignCircuitBreakerTargeter.java` (116 行)
> - `spring-cloud-openfeign-core/.../FeignCircuitBreaker.java` (101 行)
> - `spring-cloud-openfeign-core/.../FallbackFactory.java` (87 行)
> - `spring-cloud-starter-alibaba-sentinel/.../SentinelFeign.java` (187 行)
> - `spring-cloud-starter-alibaba-sentinel/.../SentinelFeignAutoConfiguration.java` (44 行)
> - `spring-cloud-starter-alibaba-sentinel/.../SentinelInvocationHandler.java` (178 行)
> 测试文件:
> - `CircuitBreakerTests.java` + `CircuitBreakerWithNoFallbackTests.java`
> - `SentinelFeignTests.java` (206 行)

## 一、角色定位: Targeter 是创建的最后一公里

`Targeter` 是 Feign 代理创建的最后一公里——将配置完成的 `Feign.Builder` + 目标地址 转换为 JDK 动态代理。

```
D1: FeignClientFactoryBean.getTarget()
  → feign() → Feign.Builder (已配置 Encoder、Decoder、Contract、Client、Interceptors...)
  → loadBalance() / resolveTarget() → Target.HardCodedTarget (目标地址)
  → targeter.target(builder, target) → JDK 动态代理  ←── 本篇焦点
```

## 二、Targeter 接口

```java
public interface Targeter {
    <T> T target(FeignClientFactoryBean factory, Feign.Builder feign,
                 FeignClientFactory context, Target.HardCodedTarget<T> target);
}
```

**四个参数**:

| 参数 | 类型 | 来源 | 作用 |
|------|------|------|------|
| `factory` | `FeignClientFactoryBean` | D1 中的 `this` | 读取 `fallback`/`fallbackFactory`/`contextId` |
| `feign` | `Feign.Builder` | D1 中 `feign(context)` 创建 | 已完全配置的 Builder |
| `context` | `FeignClientFactory` | `FeignAutoConfiguration` | 子容器，用于获取 fallback 实例 |
| `target` | `Target.HardCodedTarget<T>` | D1 中 `new HardCodedTarget<>(...)` 或 `resolveTarget()` | 目标地址信息 |

**两个实现**:

| 实现 | 选择条件 | 行为 |
|------|---------|------|
| `DefaultTargeter` | Circuit Breaker 未启用 | 直接 `feign.target(target)` |
| `FeignCircuitBreakerTargeter` | Circuit Breaker 启用 | 支持 fallback/fallbackFactory |

### 2.1 DefaultTargeter

```java
class DefaultTargeter implements Targeter {
    @Override
    public <T> T target(FeignClientFactoryBean factory, Feign.Builder feign,
            FeignClientFactory context, Target.HardCodedTarget<T> target) {
        return feign.target(target);  // 最简单的委托
    }
}
```

一行代码: `Feign.Builder.target(target)` 内部调用:
```
Feign.Builder.target(target)
  └── build().newInstance(target)
        └── ReflectiveFeign.newInstance(target)
              ├── Contract.parseAndValidateMetadata(type)  ← SpringMvcContract
              ├── InvocationHandlerFactory.create(target, dispatch)
              │     └── 默认: FeignInvocationHandler
              │     └── Sentinel: SentinelInvocationHandler (如果 Feign.Builder 是 SentinelFeign.Builder)
              └── Proxy.newProxyInstance(...)  ← JDK 动态代理
```

### 2.2 FeignCircuitBreakerTargeter

```java
class FeignCircuitBreakerTargeter implements Targeter {

    private final CircuitBreakerFactory circuitBreakerFactory;
    private final boolean circuitBreakerGroupEnabled;
    private final CircuitBreakerNameResolver circuitBreakerNameResolver;

    @Override
    public <T> T target(FeignClientFactoryBean factory, Feign.Builder feign,
            FeignClientFactory context, Target.HardCodedTarget<T> target) {
        // 如果 Feign.Builder 不是 FeignCircuitBreaker.Builder → 不包装，直接委托
        if (!(feign instanceof FeignCircuitBreaker.Builder builder)) {
            return feign.target(target);
        }
        String name = !StringUtils.hasText(factory.getContextId())
            ? factory.getName() : factory.getContextId();
        Class<?> fallback = factory.getFallback();
        if (fallback != void.class) {
            return targetWithFallback(name, context, target, builder, fallback);
        }
        Class<?> fallbackFactory = factory.getFallbackFactory();
        if (fallbackFactory != void.class) {
            return targetWithFallbackFactory(name, context, target, builder, fallbackFactory);
        }
        return builder(name, builder).target(target);
    }
}
```

**三路分支**:

| 分支 | 条件 | 调用 |
|------|------|------|
| 无 fallback | `fallback == void.class && fallbackFactory == void.class` | `builder(...).target(target)` |
| fallback | `fallback != void.class` | `builder(...).target(target, fallbackInstance)` |
| fallbackFactory | `fallbackFactory != void.class` | `builder(...).target(target, fallbackFactory)` |

**`getFromContext()` 校验**:

```java
private <T> T getFromContext(String fallbackMechanism, String feignClientName, FeignClientFactory context,
        Class<?> beanType, Class<T> targetType) {
    Object fallbackInstance = context.getInstance(feignClientName, beanType);
    if (fallbackInstance == null) {
        throw new IllegalStateException(
                String.format("No " + fallbackMechanism + " instance of type %s found for feign client %s",
                        beanType, feignClientName));
    }
    if (fallbackInstance instanceof FactoryBean<?> factoryBean) {
        try {
            fallbackInstance = factoryBean.getObject();
        }
        catch (Exception e) {
            throw new IllegalStateException(fallbackMechanism + " create fail", e);
        }
        if (!targetType.isAssignableFrom(fallbackInstance.getClass())) {
            throw new IllegalStateException(String.format("Incompatible " + fallbackMechanism
                    + " instance. Fallback/fallbackFactory of type %s is not assignable to %s for feign client %s",
                    fallbackInstance.getClass(), targetType, feignClientName));
        }

    }
    else {
        if (!targetType.isAssignableFrom(beanType)) {
            throw new IllegalStateException(String.format("Incompatible " + fallbackMechanism
                    + " instance. Fallback/fallbackFactory of type %s is not assignable to %s for feign client %s",
                    beanType, targetType, feignClientName));
        }
    }
    return (T) fallbackInstance;
}
```

校验:
1. fallback 实例必须存在于子容器
2. 如果是 `FactoryBean`，获取实际对象后校验类型
3. fallback 类型必须可赋值给 target 接口

## 三、FeignCircuitBreaker.Builder

```java
public static final class Builder extends Feign.Builder {

    private CircuitBreakerFactory circuitBreakerFactory;
    private String feignClientName;
    private boolean circuitBreakerGroupEnabled;
    private CircuitBreakerNameResolver circuitBreakerNameResolver;

    public <T> T target(Target<T> target, T fallback) {
        return build(fallback != null ? new FallbackFactory.Default<>(fallback) : null).newInstance(target);
    }

    public <T> T target(Target<T> target, FallbackFactory<? extends T> fallbackFactory) {
        return build(fallbackFactory).newInstance(target);
    }

    public Feign build(final FallbackFactory<?> nullableFallbackFactory) {
        super.invocationHandlerFactory((target, dispatch) ->
            new FeignCircuitBreakerInvocationHandler(
                circuitBreakerFactory, feignClientName, target, dispatch,
                nullableFallbackFactory, circuitBreakerGroupEnabled, circuitBreakerNameResolver));
        return super.build();
    }
}
```

**与 SentinelFeign.Builder 的关键差异**:

| 维度 | FeignCircuitBreaker.Builder | SentinelFeign.Builder |
|------|---------------------------|----------------------|
| 替换方式 | `FeignCircuitBreakerTargeter.target()` 中调用 | `internalBuild()` 中替换 `InvocationHandlerFactory` |
| fallback 读取 | 从 `FeignClientFactoryBean` 读取（参数传入） | 从 `FeignClientFactoryBean` 读取（beanDefinition 获取） |
| InvocationHandler | `FeignCircuitBreakerInvocationHandler` | `SentinelInvocationHandler` |
| fallback 包装 | `FallbackFactory.Default` | `FallbackFactory.Default` 或用户传入的 Factory |

## 四、FallbackFactory

```java
public interface FallbackFactory<T> {
    T create(Throwable cause);
}
```

**`Default` 内部类**:

```java
final class Default<T> implements FallbackFactory<T> {

    final T constant;  // 不变的 fallback 实例

    public Default(T constant) {
        this.constant = checkNotNull(constant, "fallback");
    }

    @Override
    public T create(Throwable cause) {
        // 每次调用返回同一个实例，忽略 cause
        return constant;
    }
}
```

**两种使用方式**:

| 方式 | 注解属性 | 获取异常原因 | 每次创建新实例 |
|------|---------|:----------:|:------------:|
| `fallback = MyFallback.class` | `@FeignClient(fallback = ...)` | ❌ | ❌（`Default` 包装为单例） |
| `fallbackFactory = MyFactory.class` | `@FeignClient(fallbackFactory = ...)` | ✅ `create(Throwable)` | ✅ 每次异常时调用 `create()` |

## 五、SentinelFeign — 替换 InvocationHandlerFactory

### 5.1 触发条件

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ SphU.class, Feign.class })
public class SentinelFeignAutoConfiguration {

    @Bean
    @Scope("prototype")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "feign.sentinel.enabled")
    public Feign.Builder feignSentinelBuilder() {
        return SentinelFeign.builder();
    }
}
```

**三个条件**:
1. `SphU.class` 在 classpath 上（Sentinel 核心类）
2. `Feign.class` 在 classpath 上
3. `feign.sentinel.enabled=true`

**`@Scope("prototype")`**: 每次 `get(context, Feign.Builder.class)` 创建新实例。

### 5.2 SentinelFeign.Builder - internalBuild()

```java
public static final class Builder extends Feign.Builder implements ApplicationContextAware {

    private Contract contract = new Contract.Default();
    private ApplicationContext applicationContext;
    private FeignClientFactory feignClientFactory;

    @Override
    public Feign internalBuild() {
        super.invocationHandlerFactory(new InvocationHandlerFactory() {
            @Override
            public InvocationHandler create(Target target,
                    Map<Method, MethodHandler> dispatch) {
                GenericApplicationContext gctx = (GenericApplicationContext) Builder.this.applicationContext;
                BeanDefinition def = gctx.getBeanDefinition(target.type().getName());
                FeignClientFactoryBean feignClientFactoryBean;

                // If you need the attributes to be resolved lazily, set the property value to true.
                Boolean isLazyInit = applicationContext.getEnvironment()
                        .getProperty(FEIGN_LAZY_ATTR_RESOLUTION, Boolean.class, false);
                if (isLazyInit) {
                    /*
                     * Due to the change of the initialization sequence,
                     * BeanFactory.getBean will cause a circular dependency. So
                     * FeignClientFactoryBean can only be obtained from BeanDefinition
                     */
                    feignClientFactoryBean = (FeignClientFactoryBean) def
                            .getAttribute("feignClientsRegistrarFactoryBean");
                }
                else {
                    feignClientFactoryBean = (FeignClientFactoryBean) applicationContext
                            .getBean("&" + target.type().getName());
                }
                Class fallback = feignClientFactoryBean.getFallback();
                Class fallbackFactory = feignClientFactoryBean.getFallbackFactory();
                String beanName = feignClientFactoryBean.getContextId();
                if (!StringUtils.hasText(beanName)) {
                    beanName = (String) getFieldValue(feignClientFactoryBean, "name");
                }

                Object fallbackInstance;
                FallbackFactory fallbackFactoryInstance;
                // check fallback and fallbackFactory properties
                if (void.class != fallback) {
                    fallbackInstance = getFromContext(beanName, "fallback", fallback,
                            target.type());
                    return new SentinelInvocationHandler(target, dispatch,
                            new FallbackFactory.Default(fallbackInstance));
                }
                if (void.class != fallbackFactory) {
                    fallbackFactoryInstance = (FallbackFactory) getFromContext(
                            beanName, "fallbackFactory", fallbackFactory,
                            FallbackFactory.class);
                    return new SentinelInvocationHandler(target, dispatch,
                            fallbackFactoryInstance);
                }

                return new SentinelInvocationHandler(target, dispatch);
            }
        });
        super.contract(new SentinelContractHolder(contract));
        return super.internalBuild();
    }
}
```

**Eager vs Lazy 获取 FeignClientFactoryBean**:

| 模式 | 条件 | 获取方式 | 原因 |
|------|------|---------|------|
| Eager (默认) | `isLazyInit=false` | `applicationContext.getBean("&" + className)` | 直接从容器获取 FactoryBean |
| Lazy | `isLazyInit=true` | `def.getAttribute("feignClientsRegistrarFactoryBean")` | 从 BeanDefinition 属性获取（避免循环依赖） |

**`getFieldValue()` 反射回退**: 当 `contextId` 为空时，通过 `ReflectionUtils.findField()` + `setAccessible(true)` 读取 `FeignClientFactoryBean.name` 字段。这是因为在早期版本的 `@FeignClient` 中，`contextId` 属性不存在，只有 `name`。

**SentinelContractHolder**: 存储方法元数据到 `METADATA_MAP`，供 `SentinelInvocationHandler` 查询资源名。

### 5.3 SentinelInvocationHandler

```java
public class SentinelInvocationHandler implements InvocationHandler {

    private final Target<?> target;
    private final Map<Method, MethodHandler> dispatch;
    private FallbackFactory fallbackFactory;
    private Map<Method, Method> fallbackMethodMap;

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args)
            throws Throwable {
        if ("equals".equals(method.getName())) {
            try {
                Object otherHandler = args.length > 0 && args[0] != null
                        ? Proxy.getInvocationHandler(args[0])
                        : null;
                return equals(otherHandler);
            }
            catch (IllegalArgumentException e) {
                return false;
            }
        }
        else if ("hashCode".equals(method.getName())) {
            return hashCode();
        }
        else if ("toString".equals(method.getName())) {
            return toString();
        }

        Object result;
        MethodHandler methodHandler = this.dispatch.get(method);
        // only handle by HardCodedTarget
        if (target instanceof Target.HardCodedTarget hardCodedTarget) {
            MethodMetadata methodMetadata = SentinelContractHolder.METADATA_MAP
                    .get(hardCodedTarget.type().getName()
                            + Feign.configKey(hardCodedTarget.type(), method));
            // resource default is HttpMethod:protocol://url
            if (methodMetadata == null) {
                result = methodHandler.invoke(args);
            }
            else {
                String resourceName = methodMetadata.template().method().toUpperCase()
                        + ":" + hardCodedTarget.url() + methodMetadata.template().path();
                Entry entry = null;
                try {
                    ContextUtil.enter(resourceName);
                    entry = SphU.entry(resourceName, EntryType.OUT, 1, args);
                    result = methodHandler.invoke(args);
                }
                catch (Throwable ex) {
                    // fallback handle
                    if (!BlockException.isBlockException(ex)) {
                        Tracer.traceEntry(ex, entry);
                    }
                    if (fallbackFactory != null) {
                        try {
                            Object fallbackResult = fallbackMethodMap.get(method)
                                    .invoke(fallbackFactory.create(ex), args);
                            return fallbackResult;
                        }
                        catch (IllegalAccessException e) {
                            // shouldn't happen as method is public due to being an
                            // interface
                            throw new AssertionError(e);
                        }
                        catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                    else {
                        // throw exception if fallbackFactory is null
                        throw ex;
                    }
                }
                finally {
                    if (entry != null) {
                        entry.exit(1, args);
                    }
                    ContextUtil.exit();
                }
            }
        }
        else {
            // other target type using default strategy
            result = methodHandler.invoke(args);
        }

        return result;
    }
}
```

**核心流程**:

```
SentinelInvocationHandler.invoke()
  │
  ├── Object 方法 (equals/hashCode/toString) → 直接处理
  │
  ├── 非 HardCodedTarget → 直接委托 (不保护)
  │
  └── HardCodedTarget
        │
        ├── 查询 SentinelContractHolder.METADATA_MAP
        │     → key: "{className}{Feign.configKey(method)}"
        │     → 如: "SentinelTestClient#ping()"
        │
        ├── [无 metadata] → 直接委托 (无资源保护)
        │
        └── [有 metadata]
              ├── 构造资源名: "GET:http://sentinel-test/feign/sentinel/ping"
              ├── ContextUtil.enter(resourceName)  ← 进入 Sentinel 上下文
              ├── SphU.entry(resourceName, OUT, 1, args)  ← 创建 Entry
              │     ├── [正常] → methodHandler.invoke(args) → 返回结果
              │     ├── [BlockException] → fallbackFactory.create(ex)
              │     └── [其他异常] → Tracer.traceEntry() + fallbackFactory.create(ex)
              │                       → 无 fallback: throw ex
              └── entry.exit() + ContextUtil.exit()  ← 清理上下文
```

**资源名格式**: `{HTTP_METHOD}:{target.url}{path}`
- `GET:http://sentinel-test/feign/sentinel/ping`
- `POST:http://sentinel-test/feign/sentinel/toggle`

**fallbackMethodMap**: 在构造时由 `toFallbackMethod(dispatch)` 创建——遍历 `dispatch.keySet()`，对每个接口方法 `setAccessible(true)` 后**自映射**（method → method，`LinkedHashMap` 保序），供 fallback 时反射调用**同名方法**（`fallbackMethodMap.get(method).invoke(fallbackFactory.create(ex), args)`）。

## 六、Sentinel vs CircuitBreaker 集成方式对比

| 维度 | Sentinel | Spring Cloud CircuitBreaker |
|------|---------|---------------------------|
| **替换层面** | `InvocationHandlerFactory`（Feign.Builder.internalBuild()） | `Targeter.target()` |
| **关键类** | `SentinelFeign.Builder` → `SentinelInvocationHandler` | `FeignCircuitBreaker.Builder` → `FeignCircuitBreakerTargeter` |
| **触发条件** | `feign.sentinel.enabled=true` | `circuitbreaker.enabled=true` + `CircuitBreakerFactory` |
| **Bean 位置** | 父容器 (`SentinelFeignAutoConfiguration`) | 子容器 (`FeignClientsConfiguration`) |
| **Builder 替换** | `@ConditionalOnMissingBean` ——父容器先注册，子容器跳过 | `@ConditionalOnMissingBean`——子容器内部竞争 |
| **fallback 读取** | `def.getAttribute()` / `getBean("&")` | `factory.getFallback()`（参数传入） |
| **是否替换 Targeter** | ❌（同层，不影响 Targeter 选择） | ✅（由 `FeignCircuitBreakerTargeter` 处理） |
| **同时启用** | 可能——Sentinel 的 Builder 胜出，但 Targeter 仍可能是 `FeignCircuitBreakerTargeter` |

**同时启用的场景**:
- `feign.sentinel.enabled=true` → `SentinelFeign.Builder` 胜出（覆盖 `FeignCircuitBreaker.Builder`）
- `circuitbreaker.enabled=true` + `CircuitBreakerFactory` 存在 → `FeignCircuitBreakerTargeter` 胜出
- 最终: `FeignCircuitBreakerTargeter.target()` 被调用，但 `feign !instanceof FeignCircuitBreaker.Builder` → 跳过包装，直接 `feign.target(target)` → 由 `SentinelInvocationHandler` 保护

## 七、与 FeignClientFactoryBean 的交互

```
FeignClientFactoryBean.getTarget()
  │
  ├── feign() → feign(context)
  │     ├── get(context, Feign.Builder.class)
  │     │     ├── [sentinel.enabled] SentinelFeign.builder()  ← 父容器
  │     │     ├── [circuitbreaker.enabled] FeignCircuitBreaker.builder()  ← 子容器
  │     │     └── [默认] Feign.builder()  ← 子容器
  │     ├── configureFeign() → 配置 Encoder/Decoder/Contract/Interceptors
  │     │     └── FeignCircuitBreakerTargeter.builder() 配置:
  │     │           ├── circuitBreakerFactory(...)
  │     │           ├── feignClientName(...)
  │     │           ├── circuitBreakerGroupEnabled(...)
  │     │           └── circuitBreakerNameResolver(...)
  │     └── return builder
  │
  ├── loadBalance() / resolveTarget() → target
  │
  └── targeter.target(this, builder, context, target)
        │
        ├── [DefaultTargeter] → feign.target(target)
        │     └── build().newInstance(target)
        │           └── [Feign.Builder] → FeignInvocationHandler
        │           └── [SentinelFeign.Builder] → SentinelInvocationHandler (已替换)
        │           └── [FeignCircuitBreaker.Builder] → FeignCircuitBreakerInvocationHandler
        │
        └── [FeignCircuitBreakerTargeter]
              ├── feign !instanceof FeignCircuitBreaker.Builder → feign.target(target)
              └── feign instanceof FeignCircuitBreaker.Builder
                    ├── 无 fallback → builder(...).target(target)
                    ├── fallback → builder(...).target(target, fallbackInstance)
                    └── fallbackFactory → builder(...).target(target, fallbackFactory)
```

## 八、测试分析

### 8.1 FeignAutoConfigurationTests (已在 D5 分析)

Targeter 的选择测试已验证:
- `circuitbreaker.enabled=false` → `DefaultTargeter`
- `circuitbreaker.enabled=true` + `CircuitBreakerFactory` → `FeignCircuitBreakerTargeter`

### 8.2 SentinelFeignTests (206 行)

```java
@RunWith(SpringRunner.class)
@SpringBootTest(classes = { SentinelFeignTests.TestConfig.class },
        properties = { "feign.sentinel.enabled=true" })
public class SentinelFeignTests {

    @Autowired
    private EchoService echoService;   // fallback = EchoServiceFallback

    @Autowired
    private FooService fooService;     // fallbackFactory = CustomFallbackFactory

    @Autowired
    private BarService barService;     // 无 fallback

    @Autowired
    private BazService bazService;     // 无 fallback (通过 BazClient 继承)

    @Before
    public void setUp() {
        FlowRule rule1 = new FlowRule();
        rule1.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule1.setCount(0);
        rule1.setResource("GET:http://test-service/echo/{str}");
        ...
        FlowRuleManager.loadRules(Arrays.asList(rule1, rule2, rule3, rule4));
    }

    @Test
    public void contextLoads() throws Exception {
        assertThat(echoService).isNotNull();
        assertThat(fooService).isNotNull();
    }

    @Test
    public void testFeignClient() {
        assertThat(echoService.echo("test")).isEqualTo("echo fallback");
        assertThat(fooService.echo("test")).isEqualTo("foo fallback");

        assertThatThrownBy(() -> {
            barService.bar();
        }).isInstanceOf(Exception.class);

        assertThatThrownBy(() -> {
            bazService.baz();
        }).isInstanceOf(Exception.class);

        assertThat(fooService.toString()).isNotEqualTo(echoService.toString());
        assertThat(fooService.hashCode()).isNotEqualTo(echoService.hashCode());
        assertThat(echoService.equals(fooService)).isEqualTo(Boolean.FALSE);
    }
}
```

**测试用例汇总**:

| 测试方法 | 验证内容 |
|---------|---------|
| `contextLoads` | Bean 存在性检查 |
| `testFeignClient` fallback (EchoService) | `@FeignClient(fallback = EchoServiceFallback.class)` -> 阻断时返回 "echo fallback" |
| `testFeignClient` fallbackFactory (FooService) | `@FeignClient(fallbackFactory = CustomFallbackFactory.class)` -> 阻断时返回 "foo fallback" |
| `testFeignClient` 无 fallback (BarService) | 无 fallback 配置 -> 阻断时抛 Exception |
| `testFeignClient` 无 fallback (BazService) | 通过继承接口 -> 阻断时抛 Exception |
| `testFeignClient` Object 方法 | toString/hashCode/equals 委托给 Target |

**测试中的 Feign 客户端配置**:

| 客户端 | name | fallback | 预期阻断行为 |
|--------|------|----------|-------------|
| `EchoService` | `test-service` | `EchoServiceFallback` (返回 "echo fallback") | 降级返回 |
| `FooService` | `foo-service` | `CustomFallbackFactory` (返回 "foo fallback") | 降级返回 |
| `BarService` | `bar-service` | 无 | 抛异常 |
| `BazService` (BazClient) | `baz-service` | 无 | 抛异常 |

## 九、关键设计决策

### 9.1 为什么 Sentinel 替换 InvocationHandlerFactory 而非 Targeter?

`InvocationHandlerFactory` 是 Feign `Builder` 的一部分，在 `build()` 和 `newInstance()` 之间调用。替换它可以在更底层拦截所有方法调用:

- Sentinel 需要在每次方法调用前检查流控规则（`SphU.entry()`）
- `InvocationHandlerFactory` 创建的 `InvocationHandler` 可以完全控制方法调用流程
- Targeter 仅在代理创建时执行一次，不适合运行时保护

### 9.2 为什么 CircuitBreaker 替换 Targeter?

`FeignCircuitBreaker.Builder` 与 `SentinelFeign.Builder` 设计不同:
- `SentinelFeign.Builder` 在 `internalBuild()` 中替换 `InvocationHandlerFactory`
- `FeignCircuitBreaker.Builder` 需要额外的方法: `target(target, fallback)`, `target(target, fallbackFactory)`
- 这些额外方法不在标准的 `Feign.Builder` 中，需要通过 `FeignCircuitBreakerTargeter` 调用

### 9.3 为什么 Sentinel 在父容器注册?

`SentinelFeignAutoConfiguration` 在**父容器**注册 `Feign.Builder` Bean，子容器的 `@ConditionalOnMissingBean` 会检测到父容器的 Bean 并跳过创建。这使得 Sentinel 的 Builder 可以覆盖所有子容器的 Builder。

### 9.4 FallbackFactory.Default 的设计

`FallbackFactory.Default` 包装一个固定的 fallback 实例，每次 `create()` 返回同一个实例:
- 优点: 避免频繁创建 fallback 对象
- 缺点: 无法根据异常类型选择不同的降级策略
- 适用场景: `@FeignClient(fallback = Xxx.class)` 方式

## 十、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Strategy** | `Targeter` 接口 (`DefaultTargeter` / `FeignCircuitBreakerTargeter`) | 不同的代理创建策略 |
| **Proxy** | `SentinelInvocationHandler` (JDK 动态代理) | 在方法调用前后插入 Sentinel 检查 |
| **Proxy** | `FeignCircuitBreakerInvocationHandler` | 在方法调用前后插入 CircuitBreaker 检查 |
| **Factory** | `FallbackFactory<T>` + `FallbackFactory.Default<T>` | 创建降级实例 |
| **Template Method** | `Feign.Builder.internalBuild()` (SentinelFeign 覆盖) | 自定义 Builder 构建流程 |
| **Adapter** | `InvocationHandlerFactory` (SentinelFeign 替换) | 适配不同的 InvocationHandler |

## 十一、与 D1/D2/D5 的衔接

```
D2: FeignClientsRegistrar
  └── registerFeignClient()
        └── eagerlyRegisterFeignClientBeanDefinition()
              ├── addPropertyValue("fallback", MyFallback.class)
              └── addPropertyValue("fallbackFactory", MyFactory.class)
        ↓
D5: SentinelFeignAutoConfiguration (父容器)
  └── @Bean @Scope("prototype") Feign.Builder feignSentinelBuilder()
        → SentinelFeign.builder()
        ↓
D5: DefaultFeignBuilderConfiguration (子容器 @ConditionalOnMissingBean 跳过)
  └── Feign.builder()  ← 被 Sentinel 覆盖
        ↓
D1: FeignClientFactoryBean.getTarget()
  ├── feign() → get(context, Feign.Builder.class) → SentinelFeign.builder()
  ├── SentinelFeign.Builder.internalBuild()
  │     └── 替换 InvocationHandlerFactory → SentinelInvocationHandler
  └── targeter.target(this, builder, context, target)
        └── DefaultTargeter / FeignCircuitBreakerTargeter
              └── feign.target(target)
                    └── SentinelInvocationHandler (在 build() 中已注册)
```

## 十二、本 Lab 中的实际映射

| Lab 场景 | 对应源码路径 |
|---------|-------------|
| A-06 `feign.sentinel.enabled=true` | `SentinelFeignAutoConfiguration` → `SentinelFeign.builder()` |
| A-06 `@FeignClient(fallbackFactory = SentinelTestClientFallbackFactory.class)` | `SentinelFeign.Builder.internalBuild()` → `fallbackFactory != void.class` → `SentinelInvocationHandler(..., fallbackFactoryInstance)` |
| A-06 `SentinelTestClientFallbackFactory.create(Throwable)` | `SentinelInvocationHandler.invoke()` catch → `fallbackFactory.create(ex)` → `fallbackMethodMap.get(method).invoke(fallbackResult, args)` |
| A-06 健康调用返回 pong | `SentinelInvocationHandler.invoke()` → `SphU.entry()` → `methodHandler.invoke(args)` → 正常返回 |
| A-06 故障时 fallback | `SentinelInvocationHandler.invoke()` → `SphU.entry()` → 抛异常 → `Tracer.traceEntry()` → `fallbackFactory.create(ex)` |
