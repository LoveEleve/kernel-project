# LB-04b 每服务子上下文与按服务配置（04 扩展）

> 用途：04 §5 的深挖——子上下文机制（NamedContextFactory）、自定义配置的装配与隔离污染、"按服务配 LB 策略"的完整用法
> 对应系列：**负载均衡（SCLB）专题 · 04 扩展**（使用篇）
> 口径：SCLB 4.1.4（`spring-cloud-commons` 仓库 `spring-cloud-loadbalancer` + `spring-cloud-context` 模块）
> 素材：04 §5 / L16（装配三要点）/ 源码逐行核对（本会话）
> 前置：04（五种介入、两条路径）；本篇只讲"装配与隔离"

---

## 0. 例子先行：两个服务，两种策略

```text
demo-provider  →  想按权重挑（机器大小不一）
order-service  →  想按同 zone 挑（跨机房延迟）

同一份代码、同一个进程里，怎么让"挑 demo-provider"和"挑 order-service"用不同策略？
```

SCLB 的答案：**每个服务一个独立子上下文**——各自装各自的策略 Bean，互不干扰：

```text
LoadBalancerClientFactory（工厂）
├── 子上下文 "demo-provider"： [Caching→Weighted→DiscoveryClient] + 轮询
├── 子上下文 "order-service"： [Caching→ZonePreference→DiscoveryClient] + 轮询
└── 子上下文 "other-service"： [Caching→DiscoveryClient] + 轮询（默认）
```

---

## 1. 掌握卡（4 条）

1. **隔离机制**：`LoadBalancerClientFactory`（继承 `NamedContextFactory`）**按服务名懒创建子上下文**；子上下文认父上下文（能用父的 Bean）+ 自带 `loadbalancer.client.name` 属性。
2. **装配三层优先级**：注解 `configuration=` > `clients.<serviceId>.*` 属性 > 全局默认。
3. **两条铁律**：① 自定义配置类**放主扫描路径之外**（否则污染）；② **配置写在"调用方"的 yml**（谁挑谁配置，网关也是调用方）。
4. **两阶段可分开配**（§7）：阶段一（supplier 链）走 `configurations` 预设或自定义 Bean；阶段二（算法）永远写 `@Bean ReactorServiceInstanceLoadBalancer`——两者自由组合、按服务独立。

---

## 2. 从零：什么是"上下文"

Spring 的"上下文"（ApplicationContext）= **一个装 Bean 的容器**。

"父子上下文"是 Spring 的老机制：**子容器能用父容器的 Bean，反过来不行**：

```text
父上下文（你的应用）
├── 你的 Bean：Controller / Service / RestTemplate ...
└── 子上下文（LB 专用，每个服务一个）
    ├── 自己的 Bean：supplier 链 / 轮询算法 ...
    └── 能看到父容器的 Bean（反过来父看不到子）
```

**为什么需要"每服务一个"**：如果所有服务共用一个容器，要区分策略只能堆 `@ConditionalOnProperty`——恶心。子上下文让每个服务有自己的策略 Bean 集合。

---

## 3. 工厂机制：NamedContextFactory 源码

```java
// LoadBalancerClientFactory.java:46-64
public class LoadBalancerClientFactory extends NamedContextFactory<LoadBalancerClientSpecification> {
    // :54 命名空间
    public static final String NAMESPACE = "loadbalancer";
    // :59 每个子上下文里会放一条属性：loadbalancer.client.name=<服务名>
    public static final String PROPERTY_NAME = NAMESPACE + ".client.name";
    // :64 默认配置类：每个子上下文都装它（默认轮询就从这来）
    super(LoadBalancerClientConfiguration.class, NAMESPACE, PROPERTY_NAME, new HashMap<>());
}
```

**懒创建**（`NamedContextFactory:119-127`）——第一次用到某个服务时才建它的子上下文：

```java
protected GenericApplicationContext getContext(String name) {
    if (!this.contexts.containsKey(name)) {
        synchronized (this.contexts) {
            if (!this.contexts.containsKey(name)) {
                this.contexts.put(name, createContext(name));
            }
        }
    }
    return this.contexts.get(name);
}
```

**创建时做三件事**（`buildContext:175-190`）：

```java
// ① 塞一条属性：loadbalancer.client.name=demo-provider
context.getEnvironment().getPropertySources().addFirst(
        new MapPropertySource(this.propertySourceName,
                Collections.singletonMap(this.propertyName, name)));

// ② 认父上下文："Uses Environment from parent as well as beans"
if (this.parent != null) {
    context.setParent(this.parent);
}

// ③ 注册 Bean：默认配置 + 该服务的自定义配置（registerBeans:143-159）
```

> ① 这条属性就是 `@ConditionalOnProperty("loadbalancer.client.name")` 能生效的原因——**只有子上下文里才有这条属性**，所以那个 Bean 只在子上下文实例化（xhs 的 LB Bean 就是这么写的，09 §2）。

**registerBeans 注册什么**（`NamedContextFactory:143-159`）：

```java
public void registerBeans(String name, GenericApplicationContext context) {
    // ① 该服务名对应的配置（@LoadBalancerClient 注册进来的）
    if (this.configurations.containsKey(name)) {
        for (Class<?> configuration : this.configurations.get(name).getConfiguration()) {
            registry.register(configuration);
        }
    }
    // ② "default." 前缀的配置（@LoadBalancerClients(defaultConfiguration=…) 注册的）
    for (Map.Entry<String, C> entry : this.configurations.entrySet()) {
        if (entry.getKey().startsWith("default.")) {
            for (Class<?> configuration : entry.getValue().getConfiguration()) {
                registry.register(configuration);
            }
        }
    }
    // ③ 兜底：默认配置类（LoadBalancerClientConfiguration）
    registry.register(PropertyPlaceholderAutoConfiguration.class, this.defaultConfigType);
}
```

---

## 4. 自定义配置怎么进子上下文（三种方式 + 优先级）

```java
// 自定义配置类（注意：不能放在主应用扫描的包路径下！见 §5）
@Configuration(proxyBeanMethods = false)
public class MyLbConfig {
    @Bean
    ServiceInstanceListSupplier mySupplier(ConfigurableApplicationContext context) {
        return ServiceInstanceListSupplier.builder()
                .withDiscoveryClient().withCaching().build(context);
    }
}
```

```java
// 方式①：注解挂（最高优先级）
@LoadBalancerClient(name = "demo-provider", configuration = MyLbConfig.class)   // 只给这个服务
// 或
@LoadBalancerClients(defaultConfiguration = MyLbConfig.class)                  // 给所有服务

// 方式②：属性挂（次优先级）
// spring.cloud.loadbalancer.clients.demo-provider.configurations=weighted

// 方式③：全局属性（最低）
// spring.cloud.loadbalancer.configurations=weighted
```

注册链（`LoadBalancerClientConfigurationRegistrar:57-79`）：注解 → Registrar 读 `configuration()` → `registerClientConfiguration` 注册进工厂 → 子上下文创建时由 `registerBeans` 实例化。`@LoadBalancerClients(defaultConfiguration=…)` 会以 `"default.<类名>"` 为 key 注册。

---

## 5. 隔离污染隐患（本篇最值钱的一节）

**错法**：`MyLbConfig` 放在 `@SpringBootApplication` 能扫到的包里，还标了 `@Configuration`：

```text
主应用扫描 → 发现 MyLbConfig → 在主上下文里也实例化一份！
后果 1：主上下文多了一堆本该只在子上下文里的 Bean（如 supplier）
后果 2：粒度失效——想用 @LoadBalancerClients(defaultConfiguration=…) 控制"哪些服务生效"就说不清了
后果 3：调试时 actuator/beans 里看到重复 Bean，来源分不清
```

**正法**（L16 的"装配三要点"第一条）：

```text
MyLbConfig 放到"主扫描路径之外"的包（如 com.example.lb.config）
——它只被 @LoadBalancerClient(configuration=…) 引用，
   由 Registrar 注册进工厂，子上下文创建时才实例化
```

一句话：**自定义配置类要么"不被扫描"，要么就会被主上下文捡走**——这是"为什么我的 LB 配置影响了所有服务/没生效"的标准答案。

---

## 6. 按服务配置：谁挑，谁配置

### 6.1 机制：每个服务一份"属性包"

```java
// LoadBalancerClientsProperties.java:34-40（属性类）
@ConfigurationProperties("spring.cloud.loadbalancer")
public class LoadBalancerClientsProperties extends LoadBalancerProperties {
    private final Map<String, LoadBalancerProperties> clients = new HashMap<>();  // ← 每个服务一份
}
```

```java
// LoadBalancerClientFactory.getProperties:85-97（取属性）
if (serviceId == null || !properties.getClients().containsKey(serviceId)) {
    return properties;                          // 没单独配 → 用全局默认
}
// "specifics are overlayed on top of defaults"——单独配置叠加在默认之上
return properties.getClients().get(serviceId);  // 单独配了 → 用自己那份
```

**`clients.<服务名>.xxx` 覆盖全局 `xxx`**——服务名一填，就"分家"了。

### 6.2 配置写在"调用方"的 yml 里（不是被调方）

```text
配置位置 = 发起调用的那个进程的 yml
```

| 场景 | 配置写在哪 | 控制什么 |
|---|---|---|
| 服务 A（Feign）调 demo-provider | **服务 A 的 yml** | A 挑 demo-provider 的行为 |
| 网关（lb:// 路由）调 demo-provider | **网关的 yml** | 网关挑 demo-provider 的行为 |
| demo-provider 自己的 yml | —— | 只管"我怎么注册"（metadata：zone/weight/version，02 篇） |

**demo-provider 管不了别人怎么挑它**——只能通过注册时的 metadata 影响。

### 6.3 网关也是调用方（同样能配）

```yaml
# 网关的 application.yml
spring:
  cloud:
    loadbalancer:
      clients:
        demo-provider:
          configurations: zone-preference    # 网关挑 demo-provider 时：同 zone 优先
        order-service:
          configurations: weighted           # 网关挑 order-service 时：按权重
```

网关内部用的就是同一套 SCLB（`ReactiveLoadBalancerClientFilter` 里的 `clientFactory` 就是 `LoadBalancerClientFactory`），`clients.<服务名>.*` 照常生效。

### 6.4 最反直觉的一点：同一服务，不同调用方可以不同挑法

```text
网关的 yml：    clients.demo-provider.configurations = zone-preference
服务 A 的 yml：  clients.demo-provider.configurations = weighted
```

**同时生效、互不冲突**——因为客户端 LB 是**装在每个调用方进程里的**（01 篇根定义）：

```text
demo-provider 实例群 [8081, 8082, 8083]
       ↑                    ↑
   网关挑它              服务A挑它
   (zone-preference)     (weighted)
   ——网关 yml 配的      ——服务A yml 配的
```

---

## 7. 阶段一与阶段二：可以分开配（预设管链，@Bean 管算法）

**先掰正一个常见分类错误**：

```text
阶段一（supplier 链，List→List，改集合）：caching / weighted / zone-preference / health-check / hint / subset / 粘性
阶段二（算法，List→实例，挑一个）：轮询 / 随机 / 最少连接 / 一致性哈希 / NacosLoadBalancer
```

**最少连接、随机、一致性哈希都是阶段二（算法）**——不在 supplier 链里。

**两者在子上下文里独立装配**，所以可以自由组合：

### 7.1 机制（源码）

**阶段一按服务配**——每个预设 supplier Bean 挂着条件（`LoadBalancerClientConfiguration`）：

```java
static class ZonePreferenceConfigurationCondition implements Condition {      // :335
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return LoadBalancerEnvironmentPropertyUtils.equalToForClientOrDefault(
                context.getEnvironment(), "configurations", "zone-preference");
        // 检查 clients.<serviceId>.configurations 或全局 configurations 是不是 "zone-preference"
    }
}
// WeightedConfigurationCondition:375（判断 :380）/ HealthCheckConfigurationCondition:345——同款
```

**阶段二按服务配**——默认轮询 Bean 挂着 `@ConditionalOnMissingBean`（`LoadBalancerClientConfiguration:69-77`）：

```java
@Bean
@ConditionalOnMissingBean                                    // 你没提供自己的 → 才用它
public ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(
        Environment environment, LoadBalancerClientFactory loadBalancerClientFactory) {
    String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
    return new RoundRobinLoadBalancer(
            loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
}
```

你提供自己的 `ReactorServiceInstanceLoadBalancer` Bean → **默认轮询不创建，用你的**。

### 7.2 完整例子：order-service（zone + 最少连接）

```yaml
# 调用方的 yml
spring:
  cloud:
    loadbalancer:
      clients:
        order-service:
          configurations: zone-preference      # ← 阶段一：同 zone 过滤
```

```java
// 算法配置类（放主扫描路径之外！§5 铁律）
@Configuration(proxyBeanMethods = false)
public class OrderLbConfig {
    @Bean
    public ReactorServiceInstanceLoadBalancer orderLb(Environment environment,
                                                      LoadBalancerClientFactory factory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);  // order-service
        return new LeastConnectionsLoadBalancer(
                factory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
        // ← 阶段二：最少连接（xhs 自研实现，09 篇）
    }
}

// 挂到 order-service 的子上下文
@LoadBalancerClient(name = "order-service", configuration = OrderLbConfig.class)
```

### 7.3 完整例子：user-service（weighted + 随机）

```yaml
spring:
  cloud:
    loadbalancer:
      clients:
        user-service:
          configurations: weighted              # ← 阶段一：权重展开
```

```java
@Configuration(proxyBeanMethods = false)
public class UserLbConfig {
    @Bean
    public ReactorServiceInstanceLoadBalancer userLb(Environment environment,
                                                     LoadBalancerClientFactory factory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new RandomLoadBalancer(
                factory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
        // ← 阶段二：随机（官方内置类：构造器 RandomLoadBalancer:52，03 篇"有类无预设"的那个）
    }
}

@LoadBalancerClient(name = "user-service", configuration = UserLbConfig.class)
```

### 7.4 组合效果（三个子上下文同时运行）

```text
order-service 子上下文：[Caching→ZonePreference→DiscoveryClient] + LeastConnections
user-service  子上下文：[Caching→Weighted→DiscoveryClient]      + Random
其他服务       子上下文：[Caching→DiscoveryClient]               + RoundRobin（默认）
```

### 7.5 两条边界

1. `configurations` 属性**只认识预设名**（weighted/zone-preference/…）——**自研 supplier 链**（如"hint+weighted 叠加"）属性表达不了，要走 Builder 手搭 + `@LoadBalancerClient(configuration=…)`（05 §2 叠加示例）；
2. **算法（阶段二）永远写 @Bean**——没有属性形式；阶段一则可以"预设属性"或"自定义 Bean"二选一。

---

## 8. 实战：三个服务三种策略（完整 yaml）

```yaml
spring:
  cloud:
    loadbalancer:
      # 全局默认：轮询（什么都不用写，默认就是）

      clients:
        # demo-provider：按权重
        demo-provider:
          configurations: weighted

        # order-service：同 zone 优先 + 缓存调短
        order-service:
          configurations: zone-preference
          cache:
            ttl: 5s                        # 这个服务名单变得快，缓存缩短

        # payment-service：健康检查 + 重试
        payment-service:
          configurations: health-check
          retry:
            enabled: true
            max-retries-on-next-service-instance: 2
```

能按服务分的不止"策略"（`clients.<服务名>` 下是一整套 `LoadBalancerProperties`）：

| 属性组 | 例子 |
|---|---|
| `configurations` | 预设选哪个（weighted / zone-preference / …） |
| `cache.ttl` | 名单缓存时长（默认 35s） |
| `health-check.path/interval` | 健康检查探测路径/间隔 |
| `retry.*` | 重试开关/次数/状态码 |
| `hint.*` | 灰度标签配置 |

**什么时候真的会用**：① 异构服务混部（订单要权重、支付要 zone）；② 敏感服务特殊待遇（核心服务缓存调短）；③ 灰度试点（只给一个服务开 hint）；④ xhs 的实际选择：`@LoadBalancerClients(defaultConfiguration=…)` 全服务统一装——**有粒度能力，但生产选了统一**（简单优先）。

---

## 9. Self-Test

1. 子上下文是什么时候创建的？创建时做哪三件事？
2. `loadbalancer.client.name` 这条属性有什么用？谁的代码依赖它？
3. 自定义 LB 配置类为什么不能放在主扫描路径下？错放会发生什么？
4. 三层配置优先级从高到低？"配置不生效"先查什么？
5. "网关挑 demo-provider 用 zone、服务 A 挑 demo-provider 用权重"——为什么可以同时生效？
6. order-service 要"同 zone 过滤 + 最少连接"：阶段一和阶段二各怎么配？（提示：一个走属性、一个走 @Bean；最少连接属于哪个阶段？）

---

## 10. 相关与衔接

- **04 主体**：五种介入、两条路径、旅程追踪；
- **05 §5**：L16 自定义链的装配三要点（本篇 §5 的来源）；
- **09 §2**：xhs 的 LB Bean 如何利用 `loadbalancer.client.name` 条件；
- **06**：`clients.<serviceId>.*` 下的 hint/weight 配置细节；
- 源码坐标：`LoadBalancerClientFactory`（support/）、`NamedContextFactory`（spring-cloud-context）、`LoadBalancerClientsProperties`（spring-cloud-commons）、`LoadBalancerClientConfigurationRegistrar`（annotation/）。
