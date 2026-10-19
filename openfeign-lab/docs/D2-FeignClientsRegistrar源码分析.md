# D2: FeignClientsRegistrar 深度源码分析

> 源码版本: Spring Cloud OpenFeign 4.1.3（Spring Cloud 2023.0.3，xhs-project 口径）
> 源码路径: `spring-cloud-openfeign-core/src/main/java/org/springframework/cloud/openfeign/FeignClientsRegistrar.java`
> 源码行数: 503 行
> 测试文件: `FeignClientsRegistrarTests.java` (201 行), `FeignClientsRegistrarIntegrationTests.java` (95 行)

## 一、类定位与职责

### 1.1 一句话定位

`FeignClientsRegistrar` 是 `@EnableFeignClients` 的**执行者**--它在 Spring 容器启动早期扫描所有 `@FeignClient` 接口，为每个接口注册一个 `FeignClientFactoryBean` 的 BeanDefinition。

### 1.2 接口实现

```java
class FeignClientsRegistrar
    implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {
```

| 接口 | 回调方法 | 触发时机 | 作用 |
|------|---------|---------|------|
| `ImportBeanDefinitionRegistrar` | `registerBeanDefinitions()` | BeanDefinition 注册阶段（早于 Bean 实例化） | 扫描 `@FeignClient` 并注册 BeanDefinition |
| `ResourceLoaderAware` | `setResourceLoader()` | `registerBeanDefinitions()` 之前 | 获取 `ResourceLoader`，用于 classpath 扫描 |
| `EnvironmentAware` | `setEnvironment()` | `registerBeanDefinitions()` 之前 | 获取 `Environment`，用于占位符解析和属性读取 |

**关键**: `ImportBeanDefinitionRegistrar` 在 Spring 生命周期的极早期执行--此时 Bean 尚未实例化，只有 BeanDefinition 在注册。这意味着 `FeignClientsRegistrar` 不能使用 `@Autowired`（没有 Bean 可注入），只能操作 `BeanDefinitionRegistry`。

### 1.3 触发链路

```
@SpringBootApplication
  -> @EnableFeignClients
    -> @Import(FeignClientsRegistrar.class)
      -> Spring 处理 @Import:
        1. 创建 FeignClientsRegistrar 实例
        2. setResourceLoader()  ← ResourceLoaderAware
        3. setEnvironment()     ← EnvironmentAware
        4. registerBeanDefinitions()  ← ImportBeanDefinitionRegistrar
           ├── registerDefaultConfiguration()  // 注册默认配置
           └── registerFeignClients()          // 扫描并注册每个 @FeignClient
```

`@EnableFeignClients` 的 `@Import(FeignClientsRegistrar.class)` 是入口:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import(FeignClientsRegistrar.class)
public @interface EnableFeignClients {
    String[] value() default {};              // 等价于 basePackages
    String[] basePackages() default {};       // 扫描包
    Class<?>[] basePackageClasses() default {}; // 通过类指定包
    Class<?>[] defaultConfiguration() default {}; // 默认配置类
    Class<?>[] clients() default {};          // 显式指定客户端（跳过扫描）
}
```

### 1.4 核心字段

```java
private ResourceLoader resourceLoader;  // classpath 资源加载器
private Environment environment;        // Spring 环境（属性源、占位符）
```

仅两个字段，均通过 Aware 接口注入。类本身是无状态的（所有状态通过方法参数传递）。

## 二、核心流程: registerBeanDefinitions()

### 2.1 入口

```java
@Override
public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
    registerDefaultConfiguration(metadata, registry);
    registerFeignClients(metadata, registry);
}
```

`metadata` 是标注 `@EnableFeignClients` 的类的元数据（如 `OpenfeignLabApplication.class`）。
`registry` 是 Spring 的 BeanDefinition 注册表。

两件事:
1. 注册 `@EnableFeignClients(defaultConfiguration = ...)` 指定的全局默认配置
2. 扫描并注册所有 `@FeignClient` 接口

### 2.2 registerDefaultConfiguration() - 全局默认配置

```java
private void registerDefaultConfiguration(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
    Map<String, Object> defaultAttrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName(), true);
    if (defaultAttrs != null && defaultAttrs.containsKey("defaultConfiguration")) {
        String name;
        if (metadata.hasEnclosingClass()) {
            name = "default." + metadata.getEnclosingClassName();
        } else {
            name = "default." + metadata.getClassName();
        }
        registerClientConfiguration(registry, name, "default", defaultAttrs.get("defaultConfiguration"));
    }
}
```

**作用**: 读取 `@EnableFeignClients(defaultConfiguration = MyDefaultConfig.class)` 中的配置类，包装为 `FeignClientSpecification` Bean。

**命名规则**:
- 普通类: `default.{className}` (如 `default.com.scalab.openfeign.OpenfeignLabApplication`)
- 内嵌类: `default.{enclosingClassName}` (使用外层类名)

**`hasEnclosingClass()` 的意义**: 当 `@EnableFeignClients` 标注在一个内部类上时，`metadata.getClassName()` 返回内部类的全限定名（包含 `$`），而 `metadata.getEnclosingClassName()` 返回外部类的全限定名。使用外部类名可以避免 `$` 符号在 Bean 名称中可能引发的问题。

**`registerClientConfiguration` 的三个参数**:

```java
registerClientConfiguration(registry, name, "default", defaultAttrs.get("defaultConfiguration"));
//                                   ↑      ↑           ↑
//                              Bean名   className    configuration Class[]
```

| 参数 | 值 | 含义 |
|------|-----|------|
| `name` | `default.{className}` | Bean 名称前缀 + FeignClientSpecification 的 name 字段 |
| `className` | `"default"` | 标识这是默认配置（非特定客户端） |
| `configuration` | `Class<?>[]` from annotation | 用户指定的配置类数组 |

### 2.3 registerFeignClients() - 扫描注册

```java
public void registerFeignClients(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
    LinkedHashSet<BeanDefinition> candidateComponents = new LinkedHashSet<>();
    Map<String, Object> attrs = metadata.getAnnotationAttributes(EnableFeignClients.class.getName());
    final Class<?>[] clients = attrs == null ? null : (Class<?>[]) attrs.get("clients");

    if (clients == null || clients.length == 0) {
        // 方式 A: 包扫描
        ClassPathScanningCandidateComponentProvider scanner = getScanner();
        scanner.setResourceLoader(this.resourceLoader);
        scanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));
        Set<String> basePackages = getBasePackages(metadata);
        for (String basePackage : basePackages) {
            candidateComponents.addAll(scanner.findCandidateComponents(basePackage));
        }
    } else {
        // 方式 B: 显式指定
        for (Class<?> clazz : clients) {
            candidateComponents.add(new AnnotatedGenericBeanDefinition(clazz));
        }
    }

    for (BeanDefinition candidateComponent : candidateComponents) {
        if (candidateComponent instanceof AnnotatedBeanDefinition beanDefinition) {
            AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
            Assert.isTrue(annotationMetadata.isInterface(), "@FeignClient can only be specified on an interface");

            Map<String, Object> attributes = annotationMetadata
                .getAnnotationAttributes(FeignClient.class.getCanonicalName());

            String name = getClientName(attributes);
            String className = annotationMetadata.getClassName();
            registerClientConfiguration(registry, name, className, attributes.get("configuration"));

            registerFeignClient(registry, annotationMetadata, attributes);
        }
    }
}
```

**两种扫描方式**:

| 方式 | 条件 | 实现 | 适用场景 |
|------|------|------|---------|
| A: 包扫描 | `clients` 属性为空（默认） | `ClassPathScanningCandidateComponentProvider` | 开发环境，自动发现 |
| B: 显式指定 | `@EnableFeignClients(clients = {X.class})` | `AnnotatedGenericBeanDefinition` | 测试环境，精确控制 |

**方式 A 的扫描器**: `getScanner()` 返回自定义的 `ClassPathScanningCandidateComponentProvider`，覆盖了 `isCandidateComponent()` 方法（详见第三章）。

**方式 B 的优势**: 跳过 classpath 扫描，启动更快，且避免意外扫描到不需要的 `@FeignClient`。测试中大量使用此方式。

**对每个 `@FeignClient` 的处理**:
1. 验证: 必须是接口（`Assert.isTrue(annotationMetadata.isInterface(), ...)`）
2. 获取 `name`（通过 `getClientName`）
3. 获取 `className`（接口的全限定名）
4. 注册 `FeignClientSpecification`（客户端专属配置）
5. 注册 `FeignClientFactoryBean`（客户端工厂）

**注意**: 步骤 4 和 5 注册的是**两个不同的 Bean**。`FeignClientSpecification` 是配置元数据，`FeignClientFactoryBean` 是创建代理的工厂。

### 2.4 registerFeignClient() - Eager vs Lazy

```java
private void registerFeignClient(BeanDefinitionRegistry registry, AnnotationMetadata annotationMetadata,
        Map<String, Object> attributes) {
    String className = annotationMetadata.getClassName();
    if (String.valueOf(false).equals(
            environment.getProperty("spring.cloud.openfeign.lazy-attributes-resolution", String.valueOf(false)))) {
        eagerlyRegisterFeignClientBeanDefinition(className, attributes, registry);
    } else {
        lazilyRegisterFeignClientBeanDefinition(className, attributes, registry);
    }
}
```

**条件解析**:

```
String.valueOf(false) = "false"
environment.getProperty("lazy-attributes-resolution", "false"):
  - 属性未设置 -> 返回默认值 "false"
  - 属性设为 "false" -> 返回 "false"
  - 属性设为 "true" -> 返回 "true"

"false".equals(result):
  - "false" -> true  -> Eager 分支
  - "true"  -> false -> Lazy 分支
```

| 属性值 | 条件结果 | 执行分支 |
|--------|---------|---------|
| 未设置（默认） | `"false".equals("false")` = `true` | **Eager** |
| `"false"` | `"false".equals("false")` = `true` | **Eager** |
| `"true"` | `"false".equals("true")` = `false` | Lazy |

**默认是 Eager**。属性名 `lazy-attributes-resolution` 容易误解--`true` 才启用 lazy，默认 `false` 是 eager。

### 2.5 eagerlyRegisterFeignClientBeanDefinition() - Eager 注册

```java
private void eagerlyRegisterFeignClientBeanDefinition(String className, Map<String, Object> attributes,
        BeanDefinitionRegistry registry) {
    validate(attributes);
    BeanDefinitionBuilder definition = BeanDefinitionBuilder.genericBeanDefinition(FeignClientFactoryBean.class);

    // 属性解析: 传 null 作为 beanFactory（仅支持 ${...} 占位符）
    definition.addPropertyValue("url", getUrl(null, attributes));
    definition.addPropertyValue("path", getPath(null, attributes));
    String name = getName(attributes);
    definition.addPropertyValue("name", name);
    String contextId = getContextId(null, attributes);
    definition.addPropertyValue("contextId", contextId);
    definition.addPropertyValue("type", className);
    definition.addPropertyValue("dismiss404", Boolean.parseBoolean(String.valueOf(attributes.get("dismiss404"))));

    // fallback 和 fallbackFactory
    Object fallback = attributes.get("fallback");
    if (fallback != null) {
        definition.addPropertyValue("fallback",
            (fallback instanceof Class ? fallback : ClassUtils.resolveClassName(fallback.toString(), null)));
    }
    Object fallbackFactory = attributes.get("fallbackFactory");
    if (fallbackFactory != null) {
        definition.addPropertyValue("fallbackFactory", fallbackFactory instanceof Class ? fallbackFactory
            : ClassUtils.resolveClassName(fallbackFactory.toString(), null));
    }
    definition.addPropertyValue("fallbackFactory", attributes.get("fallbackFactory"));

    definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
    definition.addPropertyValue("refreshableClient", isClientRefreshEnabled());

    // qualifiers
    String[] qualifiers = getQualifiers(attributes);
    if (ObjectUtils.isEmpty(qualifiers)) {
        qualifiers = new String[] { contextId + "FeignClient" };
    }
    definition.addPropertyValue("qualifiers", qualifiers);

    AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
    Class<?> type = ClassUtils.resolveClassName(className, null);
    beanDefinition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, type);
    boolean primary = (Boolean) attributes.get("primary");
    beanDefinition.setPrimary(primary);

    BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className, qualifiers);
    BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);

    // 可刷新 Bean
    registerRefreshableBeanDefinition(registry, contextId, Request.Options.class, OptionsFactoryBean.class);
    registerRefreshableBeanDefinition(registry, contextId, RefreshableUrl.class, RefreshableUrlFactoryBean.class);
}
```

**注意**：源码在条件块之后又**无条件**执行了一次 `definition.addPropertyValue("fallbackFactory", attributes.get("fallbackFactory"))`（4.1.3 与 4.3.2 同款），即 fallbackFactory 会被写两遍（第二遍可能覆盖解析后的值）——属源码冗余，阅读时不要以为是笔误。

**Eager 模式的特点**:
- 所有属性在 `registerBeanDefinitions()` 阶段立即解析（通过 `resolve(null, value)` -> `environment.resolvePlaceholders()`）
- 仅支持 `${...}` 占位符，不支持 SpEL `#{...}`
- 创建完整的 `BeanDefinition`，Spring 可直接实例化

**注册的 Bean 数量**: 每个 `@FeignClient` 注册 3 个 Bean:
1. `FeignClientFactoryBean`（Bean 名 = 接口全限定名）
2. `Request.Options-{contextId}`（可刷新超时配置，仅 `refresh-enabled=true` 时）
3. `RefreshableUrl-{contextId}`（可刷新 URL，仅 `refresh-enabled=true` 时）

### 2.6 lazilyRegisterFeignClientBeanDefinition() - Lazy 注册

```java
private void lazilyRegisterFeignClientBeanDefinition(String className, Map<String, Object> attributes,
        BeanDefinitionRegistry registry) {
    ConfigurableBeanFactory beanFactory = registry instanceof ConfigurableBeanFactory
        ? (ConfigurableBeanFactory) registry : null;
    Class clazz = ClassUtils.resolveClassName(className, null);
    String contextId = getContextId(beanFactory, attributes);
    String name = getName(attributes);

    FeignClientFactoryBean factoryBean = new FeignClientFactoryBean();
    factoryBean.setBeanFactory(beanFactory);
    factoryBean.setName(name);
    factoryBean.setContextId(contextId);
    factoryBean.setType(clazz);
    factoryBean.setRefreshableClient(isClientRefreshEnabled());

    BeanDefinitionBuilder definition = BeanDefinitionBuilder.genericBeanDefinition(clazz, () -> {
        // Lambda 在首次 getBean() 时执行
        factoryBean.setUrl(getUrl(beanFactory, attributes));
        factoryBean.setPath(getPath(beanFactory, attributes));
        factoryBean.setDismiss404(Boolean.parseBoolean(String.valueOf(attributes.get("dismiss404"))));
        Object fallback = attributes.get("fallback");
        if (fallback != null) {
            factoryBean.setFallback(fallback instanceof Class ? (Class<?>) fallback
                : ClassUtils.resolveClassName(fallback.toString(), null));
        }
        Object fallbackFactory = attributes.get("fallbackFactory");
        if (fallbackFactory != null) {
            factoryBean.setFallbackFactory(fallbackFactory instanceof Class ? (Class<?>) fallbackFactory
                : ClassUtils.resolveClassName(fallbackFactory.toString(), null));
        }
        return factoryBean.getObject();
    });
    definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
    definition.setLazyInit(true);
    validate(attributes);

    AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();
    beanDefinition.setAttribute("feignClientsRegistrarFactoryBean", factoryBean);
    boolean primary = (Boolean) attributes.get("primary");
    beanDefinition.setPrimary(primary);

    String[] qualifiers = getQualifiers(attributes);
    if (ObjectUtils.isEmpty(qualifiers)) {
        qualifiers = new String[] { contextId + "FeignClient" };
    }

    BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className, qualifiers);
    BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);

    registerRefreshableBeanDefinition(registry, contextId, Request.Options.class, OptionsFactoryBean.class);
    registerRefreshableBeanDefinition(registry, contextId, RefreshableUrl.class, RefreshableUrlFactoryBean.class);
}
```

**Lazy 模式的特点**:
- `factoryBean` 实例在注册阶段创建，但属性在 Lambda 中延迟设置
- `getUrl(beanFactory, attributes)` 传入真实 `beanFactory`，支持 SpEL `#{...}`
- `definition.setLazyInit(true)` - Bean 本身也是懒加载
- `beanDefinition.setAttribute("feignClientsRegistrarFactoryBean", factoryBean)` - 将 factoryBean 存入 BeanDefinition 属性，供 SentinelFeign 在 `internalBuild()` 中通过 `def.getAttribute("feignClientsRegistrarFactoryBean")` 获取

**Eager vs Lazy 的关键差异**:

| 维度 | Eager | Lazy |
|------|-------|------|
| 属性解析时机 | `registerBeanDefinitions()` 阶段 | 首次 `getBean()` 时 |
| `resolve()` 的 beanFactory | `null` | 真实 `ConfigurableBeanFactory` |
| 占位符支持 | 仅 `${...}` | `${...}` + SpEL `#{...}` |
| BeanDefinition 类型 | `genericBeanDefinition(FeignClientFactoryBean.class)` | `genericBeanDefinition(clazz, lambda)` |
| Bean 初始化模式 | 默认（eager） | `setLazyInit(true)` |
| `validate()` 调用位置 | 属性设置之前 | Lambda 注册之后 |
| SentinelFeign 获取 factoryBean | `applicationContext.getBean("&" + className)` | `def.getAttribute("feignClientsRegistrarFactoryBean")` |

**最后一行是关键差异**: SentinelFeign 的 `internalBuild()` 需要获取 `FeignClientFactoryBean` 来读取 `fallback`/`fallbackFactory`:
```java
// SentinelFeign.Builder.internalBuild()
if (isLazyInit) {
    feignClientFactoryBean = (FeignClientFactoryBean) def.getAttribute("feignClientsRegistrarFactoryBean");
} else {
    feignClientFactoryBean = (FeignClientFactoryBean) applicationContext.getBean("&" + target.type().getName());
}
```
Lazy 模式从 BeanDefinition 的 attribute 获取（避免循环依赖），Eager 模式从 ApplicationContext 获取（此时 Bean 已注册）。

## 三、辅助方法深度分析

### 3.1 包扫描器 getScanner()

```java
protected ClassPathScanningCandidateComponentProvider getScanner() {
    return new ClassPathScanningCandidateComponentProvider(false, this.environment) {
        @Override
        protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
            boolean isCandidate = false;
            if (beanDefinition.getMetadata().isIndependent()) {
                if (!beanDefinition.getMetadata().isAnnotation()) {
                    isCandidate = true;
                }
            }
            return isCandidate;
        }
    };
}
```

**为什么要覆盖 `isCandidateComponent()`?**

默认的 `ClassPathScanningCandidateComponentProvider`（`useDefaultFilters=true`）只接受**具体类**（非接口、非抽象类）。但 `@FeignClient` 标注在**接口**上，默认行为会过滤掉接口。

覆盖后的逻辑:
- `isIndependent()`: 排除内部类（非静态嵌套类不可独立实例化）
- `!isAnnotation()`: 排除注解类型
- **不检查 `isConcrete()`**: 允许接口通过

加上 `addIncludeFilter(new AnnotationTypeFilter(FeignClient.class))`，最终扫描结果: 所有标注了 `@FeignClient` 的独立接口。

### 3.2 基础包解析 getBasePackages()

```java
protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
    Map<String, Object> attributes = importingClassMetadata
        .getAnnotationAttributes(EnableFeignClients.class.getCanonicalName());

    Set<String> basePackages = new HashSet<>();
    for (String pkg : (String[]) attributes.get("value")) {
        if (StringUtils.hasText(pkg)) {
            basePackages.add(pkg);
        }
    }
    for (String pkg : (String[]) attributes.get("basePackages")) {
        if (StringUtils.hasText(pkg)) {
            basePackages.add(pkg);
        }
    }
    for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
        basePackages.add(ClassUtils.getPackageName(clazz));
    }

    if (basePackages.isEmpty()) {
        basePackages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
    }
    return basePackages;
}
```

**优先级**: `value` -> `basePackages` -> `basePackageClasses` -> `@EnableFeignClients` 所在类的包

**注意**: 这些属性不是互斥的，而是**叠加**的。如果同时指定了 `value` 和 `basePackages`，两个集合都会被扫描。

**默认行为**: 如果都不指定，扫描 `@EnableFeignClients` 所在类的包。这就是为什么 `@SpringBootApplication` + `@EnableFeignClients` 通常能扫描到同包下的 `@FeignClient`。

### 3.3 客户端配置注册 registerClientConfiguration()

```java
private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name, Object className,
        Object configuration) {
    BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(FeignClientSpecification.class);
    builder.addConstructorArgValue(name);
    builder.addConstructorArgValue(className);
    builder.addConstructorArgValue(configuration);
    registry.registerBeanDefinition(name + "." + FeignClientSpecification.class.getSimpleName(),
            builder.getBeanDefinition());
}
```

**`FeignClientSpecification` 的三个构造参数**:

```java
public FeignClientSpecification(String name, String className, Class<?>[] configuration) {
    this.name = name;
    this.className = className;
    this.configuration = configuration;
}
```

| 参数 | 默认配置调用 | 客户端配置调用 | 作用 |
|------|------------|-------------|------|
| `name` | `default.{className}` | `getClientName(attributes)` | Bean 名称前缀 + 子容器标识 |
| `className` | `"default"` | 接口全限定名 | 标识来源（默认 vs 特定客户端） |
| `configuration` | `defaultConfiguration` 属性 | `configuration` 属性 | 用户自定义配置类数组 |

**Bean 名称**: `{name}.FeignClientSpecification`，如 `nacos-lab.FeignClientSpecification`。

**被谁消费?** `FeignAutoConfiguration.feignContext()` 创建 `FeignClientFactory` 时:
```java
@Autowired(required = false)
private List<FeignClientSpecification> configurations = new ArrayList<>();

@Bean
public FeignClientFactory feignContext() {
    FeignClientFactory context = new FeignClientFactory();
    context.setConfigurations(this.configurations);  // 注入所有 Specification
    return context;
}
```

`FeignClientFactory`（继承 `NamedContextFactory`）根据 `Specification.name` 为每个客户端创建独立子容器，加载 `Specification.configuration` 中的配置类。

### 3.4 属性解析器 resolve()

```java
private String resolve(ConfigurableBeanFactory beanFactory, String value) {
    if (StringUtils.hasText(value)) {
        if (beanFactory == null) {
            // Eager 模式: 仅支持 ${...}
            return this.environment.resolvePlaceholders(value);
        }
        // Lazy 模式: 支持 ${...} + #{...}
        BeanExpressionResolver resolver = beanFactory.getBeanExpressionResolver();
        String resolved = beanFactory.resolveEmbeddedValue(value);  // 先解析 ${...}
        if (resolver == null) {
            return resolved;
        }
        Object evaluateValue = resolver.evaluate(resolved, new BeanExpressionContext(beanFactory, null));  // 再解析 #{...}
        if (evaluateValue != null) {
            return String.valueOf(evaluateValue);
        }
        return null;
    }
    return value;
}
```

**两种模式对 `resolve()` 的调用范围**:

| 模式 | 属性 | beanFactory 参数 | 能力 |
|------|------|-----------------|------|
| Eager | `name` / `url` / `path` / `contextId` | 全部 `null` | 仅 `${...}` |
| Lazy | `name` | `null` | 仅 `${...}` |
| Lazy | `url` / `path` / `contextId` | 传入真实 `beanFactory` | `${...}` + `#{...}` SpEL |

**注意**: 即使 Lazy 模式，`name` 属性也不支持 SpEL——lazy 方法调用的是 `getName(attributes)`（4.1.3 中它委托给 `getName(null, attributes)`，`resolve` 收到 `null` beanFactory）。这是因为 `name` 在 Eager/Lazy 两种模式下都需要提前解析（用于 `FeignClientSpecification` 的 Bean 命名）。

**SpEL 场景示例**:
```java
@FeignClient(name = "my-service", url = "#{@myUrlProvider.getUrl()}")
```
Lazy 模式下，`url` 中的 `#{@myUrlProvider.getUrl()}` 通过 `BeanExpressionResolver` 解析，调用 `myUrlProvider` Bean 的 `getUrl()` 方法。Eager 模式下不支持此语法。

### 3.5 名称解析

#### getName(Map) - 实例方法

```java
/* for testing */ String getName(Map<String, Object> attributes) {
    return getName(null, attributes);
}

String getName(ConfigurableBeanFactory beanFactory, Map<String, Object> attributes) {
    String name = (String) attributes.get("serviceId");
    if (!StringUtils.hasText(name)) {
        name = (String) attributes.get("name");
    }
    if (!StringUtils.hasText(name)) {
        name = (String) attributes.get("value");
    }
    name = resolve(beanFactory, name);
    return getName(name);  // 调用静态方法验证 hostname
}
```

**优先级**: `serviceId` -> `name` -> `value`

`serviceId` 是已废弃的属性（早期版本使用），保留是为了向后兼容。推荐使用 `name` 或 `value`（`value` 是 `name` 的别名）。

#### getName(String) - 静态方法

```java
static String getName(String name) {
    if (!StringUtils.hasText(name)) {
        return "";
    }

    String host = null;
    try {
        String url;
        if (!name.startsWith("http://") && !name.startsWith("https://")) {
            url = "http://" + name;
        } else {
            url = name;
        }
        host = new URI(url).getHost();
    } catch (URISyntaxException ignored) {
    }
    Assert.state(host != null, "Service id not legal hostname (" + name + ")");
    return name;
}
```

**验证逻辑**: 将 `name` 构造为 URL（添加 `http://` 前缀），通过 `URI.getHost()` 提取 host。如果 host 为 null（如 name 包含非法字符），抛出 `IllegalStateException`。

**合法 vs 非法名称**:

| name | 构造的 URL | 测试预期 | 说明 |
|------|-----------|---------|------|
| `nacos-lab` | `http://nacos-lab` | ✅ 合法 | 连字符合法 |
| `good-name` | `http://good-name` | ✅ 合法 | 同上 |
| `goodname` | `http://goodname` | ✅ 合法 | 纯字母 |
| `bad_hostname` | `http://bad_hostname` | ❌ 抛异常 | 下划线在 DNS hostname 中非法 |
| `http://bad_hostname` | `http://bad_hostname` | ❌ 抛异常 | 同上 |
| `http_bad_hostname` | `http://http_bad_hostname` | ❌ 抛异常 | 同上 |

`URI.getHost()` 的行为依赖于 Java 版本对 RFC 的实现。在某些 Java 版本中，包含下划线的 host 会被解析为 `null`（因为下划线违反 RFC 952 DNS hostname 规范），从而触发 `Assert.state(host != null)` 抛出 `IllegalStateException`。测试用例 `badName()` / `badNameHttpPrefix()` / `badNameHttpsPrefix()` / `badNameStartsWithHttp()` 均验证此行为。

#### getClientName(Map) - 客户端标识

```java
private String getClientName(Map<String, Object> client) {
    if (client == null) {
        return null;
    }
    String value = (String) client.get("contextId");
    if (!StringUtils.hasText(value)) {
        value = (String) client.get("value");
    }
    if (!StringUtils.hasText(value)) {
        value = (String) client.get("name");
    }
    if (!StringUtils.hasText(value)) {
        value = (String) client.get("serviceId");
    }
    if (StringUtils.hasText(value)) {
        return value;
    }
    throw new IllegalStateException(
            "Either 'name' or 'value' must be provided in @" + FeignClient.class.getSimpleName());
}
```

**优先级**: `contextId` -> `value` -> `name` -> `serviceId`

**与 `getName()` 的区别**:

| 方法 | 优先级 | 用途 | 解析占位符 |
|------|--------|------|-----------|
| `getName(attributes)` | `serviceId` -> `name` -> `value` | 服务发现和负载均衡的 service name | ✅ `resolve(null, name)` |
| `getClientName(attributes)` | `contextId` -> `value` -> `name` -> `serviceId` | `FeignClientFactory` 子容器命名 + Bean 名称 | ❌ 不解析 |

**设计差异的原因**:
- `getName()` 优先 `serviceId`（向后兼容旧版，旧版用 `serviceId` 指定服务名）
- `getClientName()` 优先 `contextId`（新设计，`contextId` 允许同一服务名有不同配置的客户端）
- `getClientName()` 不解析占位符（因为用于 Bean 名称，需要稳定标识）

### 3.6 URL 规范化 getUrl()

```java
static String getUrl(String url) {
    if (StringUtils.hasText(url) && !(url.startsWith("#{") && url.contains("}"))) {
        // 跳过 SpEL 表达式 #{...}
        if (!url.contains("://")) {
            url = "http://" + url;  // 添加协议前缀
        }
        try {
            new URL(url);  // 验证 URL 格式（4.1.3 用 URL；4.3.2 改为 URI）
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(url + " is malformed", e);
        }
    }
    return url;
}
```

**处理逻辑**（4.1.3）:
1. 跳过 SpEL 表达式（`#{...}` 不做处理，留给 Lazy 模式的 `BeanExpressionResolver`）
2. 添加 `http://` 前缀（如果没有协议）
3. 用 `new URL(url)` 验证格式（非法抛 `IllegalArgumentException`）

**版本差异**: 4.3.2 把 `URL` 换成 `URI`，并新增"移除尾部 `/`"逻辑（对应 4.3.2 新增的 `testRemoveTrailingSlashFromUrl` 测试）；4.1.3 不做尾斜杠处理。

**测试验证**: 4.1.3 的 `FeignClientsRegistrarTests` 中**没有** URL 用例（`goodUrl`/`badUrl`/`testRemoveTrailingSlashFromUrl` 是 4.3.2 随 URI 改造新增）。

### 3.7 路径规范化 getPath()

```java
static String getPath(String path) {
    if (StringUtils.hasText(path)) {
        path = path.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;  // 添加前导斜杠
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);  // 移除尾部斜杠
        }
    }
    return path;
}
```

### 3.8 validate() - fallback 校验

```java
private void validate(Map<String, Object> attributes) {
    AnnotationAttributes annotation = AnnotationAttributes.fromMap(attributes);
    validateFallback(annotation.getClass("fallback"));
    validateFallbackFactory(annotation.getClass("fallbackFactory"));
}

static void validateFallback(final Class clazz) {
    Assert.isTrue(!clazz.isInterface(), "Fallback class must implement the interface annotated by @FeignClient");
}

static void validateFallbackFactory(final Class clazz) {
    Assert.isTrue(!clazz.isInterface(), "Fallback factory must produce instances "
        + "of fallback classes that implement the interface annotated by @FeignClient");
}
```

**校验**: `fallback` 和 `fallbackFactory` 必须是**具体类**（非接口）。因为它们需要被实例化。

**测试验证**:
```java
@FeignClient(name = "fallbackTestClient", url = "http://localhost:8080/", fallback = FallbackClient.class)
protected interface FallbackClient { ... }  // FallbackClient 本身是接口 -> 抛异常

@Test void testFallback() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new AnnotationConfigApplicationContext(FallbackTestConfig.class));
}
```

### 3.9 registerRefreshableBeanDefinition()

```java
private void registerRefreshableBeanDefinition(BeanDefinitionRegistry registry, String contextId,
        Class<?> beanType, Class<?> factoryBeanType) {
    if (isClientRefreshEnabled()) {
        String beanName = beanType.getCanonicalName() + "-" + contextId;
        BeanDefinitionBuilder definitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(factoryBeanType);
        definitionBuilder.setScope("refresh");
        definitionBuilder.addPropertyValue("contextId", contextId);
        BeanDefinitionHolder definitionHolder = new BeanDefinitionHolder(definitionBuilder.getBeanDefinition(), beanName);
        definitionHolder = ScopedProxyUtils.createScopedProxy(definitionHolder, registry, true);
        BeanDefinitionReaderUtils.registerBeanDefinition(definitionHolder, registry);
    }
}

private boolean isClientRefreshEnabled() {
    return environment.getProperty("spring.cloud.openfeign.client.refresh-enabled", Boolean.class, false);
}
```

**触发条件**: `spring.cloud.openfeign.client.refresh-enabled=true`（默认 `false`）

**注册的 Bean**:
- `feign.Request.Options-{contextId}` -> `OptionsFactoryBean`（`@RefreshScope`）
- `org.springframework.cloud.openfeign.RefreshableUrl-{contextId}` -> `RefreshableUrlFactoryBean`（`@RefreshScope`）

**ScopedProxy**: 使用 `ScopedProxyUtils.createScopedProxy()` 创建代理，使得注入方获取到的是一个代理对象，每次调用时从 `@RefreshScope` 容器获取最新实例。

### 3.10 getQualifiers() - 限定符

```java
private String[] getQualifiers(Map<String, Object> client) {
    if (client == null) {
        return null;
    }
    List<String> qualifierList = new ArrayList<>(Arrays.asList((String[]) client.get("qualifiers")));
    qualifierList.removeIf(qualifier -> !StringUtils.hasText(qualifier));
    if (qualifierList.isEmpty() && getQualifier(client) != null) {
        qualifierList = Collections.singletonList(getQualifier(client));
    }
    return !qualifierList.isEmpty() ? qualifierList.toArray(new String[0]) : null;
}
```

**作用**: 从 `@FeignClient(qualifiers = {"q1", "q2"})` 读取限定符，用于 `@Qualifier("q1")` 注入。

**默认行为**: 如果没有指定 qualifiers，使用 `{contextId}FeignClient` 作为默认限定符。

**测试验证**:
```java
@FeignClient(name = "qualifiersClient", qualifiers = { "qualifier1", "qualifier2" })
// -> context.getBean("qualifier1") 可以获取到 Feign 代理
// -> context.getBean("qualifier3") 抛出 NoSuchBeanDefinitionException

@FeignClient(name = "noQualifiers")
// -> context.getBean("noQualifiersFeignClient") 可以获取

@FeignClient(name = "emptyQualifiersNoQualifier", qualifiers = {})
// -> context.getBean("emptyQualifiersNoQualifierFeignClient") 可以获取

@FeignClient(name = "whitespaceQualifiersNoQualifier", qualifiers = { " " })
// -> 空白字符串被 removeIf 过滤 -> 退回默认 -> "whitespaceQualifiersNoQualifierFeignClient"
```

## 四、完整流程图

```
@EnableFeignClients
    │
    ▼
FeignClientsRegistrar.registerBeanDefinitions()
    │
    ├── registerDefaultConfiguration()
    │       └── 创建 default.{AppClass}.FeignClientSpecification Bean
    │           (name=default.xxx, className="default", configuration=用户配置类)
    │
    └── registerFeignClients()
            │
            ├── 扫描 @FeignClient 接口
            │       ├── 方式 A: 包扫描 (ClassPathScanningCandidateComponentProvider)
            │       │   └── isCandidateComponent: isIndependent && !isAnnotation
            │       │   └── IncludeFilter: AnnotationTypeFilter(FeignClient.class)
            │       └── 方式 B: 显式指定 (clients 属性)
            │
            └── 对每个 @FeignClient:
                    │
                    ├── 验证是接口 (Assert.isTrue(isInterface))
                    ├── getClientName(attributes) -> name (contextId > value > name > serviceId)
                    ├── getName(attributes) -> service name (serviceId > name > value)
                    │
                    ├── registerClientConfiguration()
                    │       └── 创建 {name}.FeignClientSpecification Bean
                    │           (name=clientName, className=接口全限定名, configuration=客户端配置类)
                    │
                    └── registerFeignClient()
                            │
                            ├── lazy-attributes-resolution 未设置或 false (默认) → Eager
                            │   └── eagerlyRegisterFeignClientBeanDefinition()
                            │       ├── validate(attributes) -> 检查 fallback/fallbackFactory 非接口
                            │       ├── resolve(null, url/path/name/contextId) -> 仅 ${...}
                            │       ├── BeanDefinitionBuilder.genericBeanDefinition(FeignClientFactoryBean.class)
                            │       ├── addPropertyValue(url, path, name, contextId, type, fallback, ...)
                            │       ├── setAutowireMode(AUTOWIRE_BY_TYPE)
                            │       ├── 注册 BeanDefinition (beanName = 接口全限定名)
                            │       └── registerRefreshableBeanDefinition() (如果 refresh-enabled)
                            │
                            └── lazy-attributes-resolution = true → Lazy
                                └── lazilyRegisterFeignClientBeanDefinition()
                                    ├── 创建 FeignClientFactoryBean 实例（仅设置 name/contextId/type）
                                    ├── BeanDefinitionBuilder.genericBeanDefinition(clazz, lambda)
                                    │   └── Lambda: resolve(beanFactory, url/path) -> ${...} + #{...}
                                    ├── setLazyInit(true)
                                    ├── setAttribute("feignClientsRegistrarFactoryBean", factoryBean)
                                    ├── 注册 BeanDefinition
                                    └── registerRefreshableBeanDefinition() (如果 refresh-enabled)
```

## 五、测试分析

### 5.1 FeignClientsRegistrarTests (220 行)

#### 名称验证测试

```java
@Test
void goodName() {
    String name = testGetName("good-name");
    assertThat(name).as("name was wrong").isEqualTo("good-name");
}

@Test
void badName() {
    assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> testGetName("bad_hostname"));
}
```

验证 `getName(String)` 静态方法的 hostname 校验。合法名称返回原值，非法名称抛 `IllegalStateException`。

#### URL 验证测试（4.1.3 无）

4.1.3 测试类没有 URL 用例；`goodUrl`/`badUrl`/`testRemoveTrailingSlashFromUrl` 是 4.3.2 随 `getUrl` 的 URI 改造新增（详见 §3.6 版本差异）。

#### Fallback 验证测试

```java
@FeignClient(name = "fallbackTestClient", url = "http://localhost:8080/", fallback = FallbackClient.class)
protected interface FallbackClient { ... }  // fallback 指向接口本身

@Test void testFallback() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new AnnotationConfigApplicationContext(FallbackTestConfig.class));
}
```

验证 `validateFallback()`: fallback 类不能是接口。

#### Null URL 测试

```java
@FeignClient(name = "nullUrlFeignClient", url = "${test.url:#{null}}", path = "${test.path:#{null}}")
protected interface NullUrlFeignClient { }

@Test
@DisabledForJreRange(min = JRE.JAVA_16)
void shouldResolveNullUrl() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.register(NullUrlFeignClientTestConfig.class);
    context.refresh();

    Object feignClientBean = context.getBean(NullUrlFeignClient.class);

    Object invocationHandlerLambda = ReflectionTestUtils.getField(feignClientBean, "h");
    Target.HardCodedTarget<NullUrlFeignClient> target = (Target.HardCodedTarget<NullUrlFeignClient>) ReflectionTestUtils
            .getField(invocationHandlerLambda, "arg$3");
    assertThat(target.name()).isEqualTo("nullUrlFeignClient");
    assertThat(target.url()).isEqualTo("http://nullUrlFeignClient");
}
```

验证 SpEL `#{null}` 解析: `url` 解析为 null -> `getTarget()` 走分支 A（服务发现）-> URL 自动构造为 `http://{name}`。

#### Null Name 测试

```java
@FeignClient(name = "${test.name:#{null}}")
protected interface NullExpressionNameFeignClient { }

@Test void shouldResolveAndValidateNullName() {
    assertThatIllegalStateException().isThrownBy(() -> {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(NullExpressionNameFeignClientTestConfig.class);
        context.refresh();
    });
}
```

验证 name 解析为 null 时抛出异常: `getName("")` -> `getName(name)` -> `host = null` -> `Assert.state(host != null)` -> `IllegalStateException`。

### 5.2 FeignClientsRegistrarIntegrationTests (95 行)

#### Qualifiers 测试

```java
@FeignClient(name = "qualifiersClient", qualifiers = { "qualifier1", "qualifier2" })
@FeignClient(name = "noQualifiers")
@FeignClient(name = "emptyQualifiersNoQualifier", qualifiers = {})
@FeignClient(name = "whitespaceQualifiersNoQualifier", qualifiers = { " " })

@Test void shouldUseQualifiersIfPresent() {
    assertThat(context.getBean("qualifier1")).isNotNull();
    assertThat(context.getBean("qualifier2")).isNotNull();
    assertThatExceptionOfType(NoSuchBeanDefinitionException.class).isThrownBy(() -> context.getBean("qualifier3"));
}

@Test void shouldUseDefaultQualifierWhenNonePresent() {
    assertThat(context.getBean("noQualifiersFeignClient")).isNotNull();
}
```

验证 `getQualifiers()` 的逻辑:
- 有 qualifiers -> 用指定的
- 无 qualifiers -> 用默认 `{contextId}FeignClient`
- 空 qualifiers 数组 -> 退回默认
- 空白字符串 qualifiers -> 过滤后退回默认

### 5.3 子层级扫描测试

```java
@EnableFeignClients(clients = {
    org.springframework.cloud.openfeign.feignclientsregistrar.TopLevelClient.class,
    org.springframework.cloud.openfeign.feignclientsregistrar.sub.SubLevelClient.class
})
protected static class TopLevelSubLevelTestConfig { }

@Test
void shouldPassSubLevelFeignClient() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    ((DefaultListableBeanFactory) context.getBeanFactory()).setAllowBeanDefinitionOverriding(false);
    context.register(TopLevelSubLevelTestConfig.class);
    assertThatCode(context::refresh)
            .as("Case https://github.com/spring-cloud/spring-cloud-openfeign/issues/331 should be solved")
            .doesNotThrowAnyException();
}
```

验证不同包下的 `@FeignClient` 不会冲突（issue #331 修复）。

## 六、关键设计决策

### 6.1 为什么用 ImportBeanDefinitionRegistrar 而不是 @Bean?

`ImportBeanDefinitionRegistrar` 在 Spring 容器生命周期的早期（BeanDefinition 注册阶段）执行，此时:
- `@Bean` 方法尚未解析（`@Configuration` 类尚未实例化）
- 可以手动构造 `BeanDefinition` + 注册属性
- 可以按每个 `@FeignClient` 动态生成 BeanDefinition

而 `@Bean` 方法只能在配置类中声明固定的 Bean，无法动态扫描和注册。

### 6.2 为什么默认 Eager?

属性名 `lazy-attributes-resolution` 容易产生误解--`false`（默认）反而是 Eager。

**Eager 模式优势**:
- 在 `registerBeanDefinitions()` 阶段直接创建 `FeignClientFactoryBean` 的 `BeanDefinition`
- 提前解析 `url`/`path`（通过 `environment.resolvePlaceholders()`）
- 可被 Spring AOT 提前处理（GraalVM native image 兼容）
- 适合大部分场景

**Lazy 模式优势**:
- 将属性解析推迟到 `factoryBean.getObject()` 调用时
- 可使用 `BeanExpressionResolver` 解析 SpEL 表达式（`#{...}`）
- 适用于 `url` 中包含 SpEL 的场景

**为什么 Eager 是默认**（推断）: 与 AOT/native 兼容性有关——Eager 模式在注册阶段即可拿到完整 BeanDefinition；源码与配置元数据（`additional-spring-configuration-metadata.json:84` 仅描述"Switches @FeignClient attributes resolution mode to lazy"）未直接说明原因，此处为推断。

### 6.3 FeignClientSpecification 的 className 参数

```java
registerClientConfiguration(registry, name, className, attributes.get("configuration"));
//                                   ↑      ↑                    ↑
//                              name    className          configuration
```

`className` 在两个调用场景中值不同:
- 默认配置: `"default"` - 标识这是全局默认配置
- 客户端配置: 接口全限定名 - 标识来源客户端

`className` 不参与 Bean 命名或子容器创建（`name` 才是子容器标识）；它的消费方在 AOT——`FeignClientBeanFactoryInitializationAotProcessor` 用 `FeignClientSpecification::getClassName` 定位 Feign 客户端 BeanDefinition（4.1.3:91）。

### 6.4 为什么 getName() 和 getClientName() 优先级不同?

- `getName()`: `serviceId` -> `name` -> `value` - 优先 `serviceId`（向后兼容旧版）
- `getClientName()`: `contextId` -> `value` -> `name` -> `serviceId` - 优先 `contextId`（新设计）

`contextId` 是后引入的属性，允许同一服务有多个 Feign 客户端（不同配置）。例如:
```java
@FeignClient(name = "nacos-lab", contextId = "nacos-lab-v1")
@FeignClient(name = "nacos-lab", contextId = "nacos-lab-v2")
```
两个客户端调用同一个服务，但有不同的超时/拦截器配置。`getClientName()` 返回 `nacos-lab-v1`/`nacos-lab-v2`（用于子容器隔离），`getName()` 都返回 `nacos-lab`（用于服务发现）。

## 七、与 D1 (FeignClientFactoryBean) 的衔接

```
D2: FeignClientsRegistrar (注册阶段)
  │
  ├── registerClientConfiguration()
  │     └── 创建 FeignClientSpecification Bean
  │         -> 被 FeignAutoConfiguration.feignContext() 收集
  │         -> FeignClientFactory.setConfigurations(specifications)
  │
  └── eagerlyRegisterFeignClientBeanDefinition() / lazilyRegisterFeignClientBeanDefinition()
        └── 创建 FeignClientFactoryBean BeanDefinition
            -> Spring 容器实例化 FeignClientFactoryBean
            -> setBeanFactory() / setApplicationContext()
            -> afterPropertiesSet() (校验 contextId, name)
            -> getObject() -> getTarget()  ← D1 分析的核心方法
```

## 八、本 Lab 中的实际映射

| Lab 场景 | 对应源码路径 |
|---------|-------------|
| A-01 `@EnableFeignClients` | `@Import(FeignClientsRegistrar.class)` -> `registerBeanDefinitions()` |
| A-01 `@FeignClient(name="nacos-lab")` | `registerFeignClients()` -> 包扫描 -> `registerFeignClient()` -> Eager 注册 |
| A-03 `@FeignClient(url="http://localhost:28001")` | `eagerlyRegisterFeignClientBeanDefinition()` -> `getUrl(null, attributes)` 解析 URL |
| A-06 `@FeignClient(fallbackFactory=...)` | `validate(attributes)` -> `validateFallbackFactory()` 校验非接口 |
| A-08 `@FeignClient(name="nacos-lab-v1", contextId="nacos-lab-v1")` | `getClientName()` 返回 `nacos-lab-v1`（子容器隔离）, `getName()` 返回 `nacos-lab`（服务发现） |
| A-08 `@EnableFeignClients(clients={...})` | 方式 B: 显式指定，跳过包扫描 |
