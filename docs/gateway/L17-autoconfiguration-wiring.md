# Lesson 17: What Auto-Configuration Wires — 网关启动时到底装配了什么

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 17（**源码第一课**）
> 口径：**讲机制，不逐行**；本文所有 bean 清单均为**实测输出**（`/actuator/beans`）
> 前置：L14/L15（自己写过工厂）· L16（`ServiceInstanceSupplier` 是"选实例"入口）
> **提问方式**：直接说编号，如「17.4 没懂」

---

## 17.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **四组组件** | `GatewayAutoConfiguration` 把一切归纳成 **A 来源 / B 模型 / C 匹配 / D 执行** 四组 |
| **②** | **B 组装配链** | `CachingRouteLocator(CompositeRouteLocator(List<RouteLocator>))` —— "缓存套合并，合并所有 Locator" |

> **为什么第 17 课先看"装配"**：后面所有源码课（L19 来源链 / L25 链装配 / L23 匹配 / L27 转发）里的类，
> **都是这一课装配出来的 bean**。先知道"谁把谁 new 出来"，后面才好找。

---

## 17.1 例子先行：启动后到底有多少 bean？

网关起来之后数一数（实测）：

```bash
curl -s --noproxy '*' http://localhost:8080/actuator/beans | python3 -c "
import sys,json
d=json.load(sys.stdin); n=0
for k,v in d.get('contexts',{}).items(): n += len(v.get('beans',{}))
print('bean 总数:', n)"
# → bean 总数: 453
```

**453 个** —— 但**先别被吓到**：其中 **Gateway 自己的只有 106 个**（`org.springframework.cloud.gateway.*` 开头的），
其余都是 Spring Boot / WebFlux / Nacos 等框架的 bean（不用管）。

再看 Gateway 自己的自动配置类有几个 `@Bean`：

```bash
grep -c '@Bean' .../GatewayAutoConfiguration.java
# → 91
```

**`GatewayAutoConfiguration` 一个类就有 91 个 `@Bean`** —— 这是新手看源码最容易劝退的地方。

> **但别慌**：91 个 bean **不是 91 个知识点** —— 它们能归纳成**四组**，而且**组内是同一个套路**。

---

## 17.2 归纳：四组组件（★ 掌握点①）

**先说清楚一件事**（否则你会以为这是官方术语）：

> **"A/B/C/D 四组" 不是官方分类** —— 官方是按**包**组织的（`route/` / `handler/` / `filter/` / `config/`）。
> 这是**我为了"能记住、能张口讲"做的一层归纳**，依据是**一次请求的生命周期**。

```
路由定义（哪来？） → Route（能跑的） → 匹配（走哪条？） → 执行（怎么加工/转发？）
    A 来源              B 模型           C 匹配            D 执行
```

**为什么值得这样归纳**：91 个 `@Bean` 平铺着看记不住；但只要脑子里有这条**请求链路**，
每看到一个 bean 都能**归位** —— 面试讲源码时也是这么展开的（不是背 bean，是讲链路）。

| 组 | 角色 | 干什么 | 关键 bean（实测） |
|---|---|---|---|
| **A 来源** | `RouteDefinitionLocator` 家族 | **路由定义从哪来** | `propertiesRouteDefinitionLocator`（yml）<br>`inMemoryRouteDefinitionRepository`（Actuator 写）<br>**`routeDefinitionLocator` → `CompositeRouteDefinitionLocator`**（合并，`@Primary`） |
| **B 模型** | `RouteLocator` 家族 | **定义 → 可运行的 Route** | `routeDefinitionRouteLocator` → `RouteDefinitionRouteLocator`<br>**`cachedCompositeRouteLocator` → `CachingRouteLocator`**（`@Primary`） |
| **C 匹配** | 匹配器 | **请求进来找路由** | `routePredicateHandlerMapping` → `RoutePredicateHandlerMapping`<br>+ **15 个** `*RoutePredicateFactory`（**14 内置 + 1 自定义** `demoHeader`） |
| **D 执行** | 过滤器链 | **链的装配与执行 + 转发** | `filteringWebHandler` → `FilteringWebHandler`<br>+ **33 个** `*GatewayFilterFactory`<br>+ 一批 GlobalFilter（`routeToRequestUrlFilter` / `routingFilter`(NettyRoutingFilter) / `nettyWriteResponseFilter` / `gatewayLoadBalancerClientFilter`…） |

**一句话**：
> **A 是"原料"（路由从哪来）→ B 是"成品"（能跑的 Route）→ C 是"门卫"（选哪条）→ D 是"流水线"（怎么加工/转发）**。

> ⚠️ **用这个归纳时的纪律**：如果面试官问"Gateway 的组件分几类"，
> **先答官方的**（`RouteDefinitionLocator` / `RouteLocator` / `HandlerMapping` / `WebHandler` 四类接口），
> 再补一句"我习惯按请求链路归纳成 来源→模型→匹配→执行"。**别把我的教学框架当官方答案**。

---

### 17.2.5 Spring Boot 自动装配最小包（★ 读懂本节的前置知识）

**问题**：`GatewayAutoConfiguration` 里那 91 个 `@Bean`，**是谁在什么时候调用的**？
我们既没 `new` 它、也没写 `@Import` —— 它怎么就生效了？

#### ① 入口：`@SpringBootApplication` 里的第三个注解

```java
@SpringBootApplication
  = @SpringBootConfiguration
  + @ComponentScan              // 扫【你自己包的】@Component
  + @EnableAutoConfiguration    // ★ 自动装配入口（内部 @Import(AutoConfigurationImportSelector)）
```

**两条路别混**（这是最常见的混淆）：

| | **组件扫描** | **自动装配** |
|---|---|---|
| 谁写的 bean | **你**（`@Component`/`@Bean`） | **框架**（登记在 jar 的清单里） |
| 怎么被发现 | 扫**你的包** | 读 **`.imports` 清单** |
| Gateway 里的例子 | `DemoGlobalFilter`（L13）、`DemoHeaderGatewayFilterFactory`（L14） | **`GatewayAutoConfiguration`**（本文） |

#### ② 清单：`.imports` 文件（Boot 3）

```bash
unzip -p spring-cloud-gateway-server-4.1.5.jar \
  'META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports'
# → 11 行（就是 17.5.3 那张表）
```
（Boot 2.x 时代这个清单在 `spring.factories` 里，键名 `EnableAutoConfiguration`）

#### ③ 条件：清单里的类**不一定都会装**（★ 核心机制）

每个自动配置类/内部类上都有"条件"，不满足就**跳过**：

| 条件注解 | 含义 | 本文 / Gateway 的真实例子 |
|---|---|---|
| **`@ConditionalOnClass`** | classpath 上有这些类才装 | `GatewayAutoConfiguration` 上有 **`@ConditionalOnClass(DispatcherHandler.class)`**（WebFlux 在才装）<br>`TokenRelayConfiguration` 需要 Spring Security OAuth2 → 这就是 **17.5.4 里 `tokenrelay` 没装配**的原因 |
| **`@ConditionalOnMissingBean`** | 容器里**没有**才装 | `propertiesRouteDefinitionLocator` → **你注册自己的实现，它就让位**（= 你能"接管"，17.3 讲） |
| **`@ConditionalOnProperty`** | 属性开关匹配才装 | `@ConditionalOnProperty(name = "spring.cloud.gateway.enabled", matchIfMissing = true)`<br>→ **`spring.cloud.gateway.enabled=false` 可一键关掉整个网关自动配置** |
| `@ConditionalOnWebApplication` | 应用类型匹配才装 | `GatewayClassPathWarningAutoConfiguration` 里 `type = SERVLET` → **检测到 Servlet 环境就打"不兼容"警告**（L02 的硬约束就是它） |
| `@ConditionalOnEnabledFilter` | Gateway **自定义**条件 | `TokenRelay` 受 `tokenrelay.enabled` 控制（L05 提过"过滤器开关"） |

> **一句话**：**`.imports` 决定"候选名单"，`@Conditional*` 决定"谁能上场"**。
> 17.5.4 的"为什么文档 35 个、实际只装 33 个"——答案全在这一节。

#### ④ 顺序：`@AutoConfigureBefore/After`（决定"条件判断的时机"）

**直接看 `GatewayAutoConfiguration` 的类头**（一条注解都不多）：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.gateway.enabled", matchIfMissing = true)
@EnableConfigurationProperties
@AutoConfigureBefore({ HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class })   // ← 在 WebFlux【之前】
@AutoConfigureAfter({ GatewayReactiveLoadBalancerClientAutoConfiguration.class,                // ← 在 LB 配置【之后】
                      GatewayClassPathWarningAutoConfiguration.class })
@ConditionalOnClass(DispatcherHandler.class)
public class GatewayAutoConfiguration { ... }
```

**顺序为什么重要**：装配顺序决定"**`@ConditionalOnMissingBean` 检查时，前面注册的 bean 是否已经在场**"。
所以 `GatewayAutoConfiguration` 要排在 LB 配置**之后**（才能看到它注册的 bean）。

> ⚠️ **注意方向**：这里是 **`@AutoConfigureBefore(WebFluxAutoConfiguration)`**（在 WebFlux **之前**）——
> **顺序不按直觉，以源码声明为准**。

#### ⑤ 属性绑定：`@ConfigurationProperties`

```java
@ConfigurationProperties(GatewayProperties.PREFIX)     // PREFIX = "spring.cloud.gateway"
public class GatewayProperties { ... }
```
→ yml 里的 `spring.cloud.gateway.routes[...]` / `httpclient.*` 就是**这样绑到对象**的
（绑定细节 → **L20**；本章只需知道"**yml → 对象**靠它"）

**面试答法（30 秒）**：
> "Gateway 的自动装配走 Spring Boot 标准机制：`@EnableAutoConfiguration` 读 jar 里的 **`.imports` 清单**，
> 逐个用 **`@Conditional*`** 判断要不要装 —— 比如 `GatewayAutoConfiguration` 上有 `@ConditionalOnClass(DispatcherHandler)`、
> `@ConditionalOnProperty(spring.cloud.gateway.enabled)`；顺序用 `@AutoConfigureBefore/After` 声明。
> 因此我们可以通过**注册自己的 bean**（`@ConditionalOnMissingBean` 生效）或**关开关**来接管/关闭它。"

---

## 17.3 A 组：路由来源 —— **这三个类到底干什么**（源码）

### 17.3.0 先理清接口家族（**"来源"这个词就是它们**）

```java
// ① 只读来源
public interface RouteDefinitionLocator {
    Flux<RouteDefinition> getRouteDefinitions();     // ← 唯一方法：吐出一堆"路由定义"
}

// ② 可写（给 Actuator 动态增删用）
public interface RouteDefinitionWriter {
    Mono<Void> save(Mono<RouteDefinition> route);
    Mono<Void> delete(Mono<String> routeId);
}

// ③ 可读 + 可写 = 仓库
public interface RouteDefinitionRepository extends RouteDefinitionLocator, RouteDefinitionWriter { }
```

**命名就是语义**：

| 名字 | 能干什么 | 谁用 |
|---|---|---|
| `RouteDefinitionLocator`（**定位器/来源**） | **只读**：产出定义 | yml / 服务发现 / Redis … |
| `RouteDefinitionWriter`（**写入器**） | 写：`save` / `delete` | Actuator 端点 |
| `RouteDefinitionRepository`（**仓库**） | **读写都有** | 内存实现 / Redis 实现（L49） |

> **一句话**：**"来源" = 一堆 `RouteDefinitionLocator` bean；它们合起来回答"网关有哪些路由定义"**。

### 17.3.1 `PropertiesRouteDefinitionLocator` —— **它不解析 yml！**

这是最容易误解的一个。看它的**全文**（就这么短）：

```java
public class PropertiesRouteDefinitionLocator implements RouteDefinitionLocator {

    private final GatewayProperties properties;                 // ← ① 已经绑好的配置对象

    public PropertiesRouteDefinitionLocator(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(this.properties.getRoutes())   // ② 把 List<RouteDefinition> 转成 Flux
                   .filter(RouteDefinition::isEnabled);         // ③ 过滤掉 enabled=false 的路由
    }
}
```

**它到底干什么**：**把"已经绑好的 `List<RouteDefinition>`"吐出来，仅此而已。**

**❓那它为什么不用 Map 存？** —— **因为它不需要：`GatewayProperties.routes` 本身就是存储**（一个 List）。
它只是个 **"List → Flux" 的薄适配器**（对比 17.3.2 的内存 repo —— 那个因为要"按 id 增删"才需要 Map）。

**⚠️ 更深的意图：接口层面的"读写分离"** ——
`PropertiesRouteDefinitionLocator` **只实现 `RouteDefinitionLocator`（只读）**，
**在类型系统层面就不允许写它** → 所以 **L08 的"yml 路由删不掉"不是"忘了实现"，是"接口就没给"** ✅

**那 yml 是谁解析的？** —— **不是它，是 Spring Boot 的 `@ConfigurationProperties`**：

```
application.yml
  spring.cloud.gateway.routes[0].id / uri / predicates / filters ...
        │
        ▼  Spring Boot 的属性绑定（@ConfigurationProperties("spring.cloud.gateway") → GatewayProperties）
   GatewayProperties.routes : List<RouteDefinition>        ← 已经是对象了！
        │
        ▼  PropertiesRouteDefinitionLocator.getRouteDefinitions()（本类：只是"转成 Flux"）
   Flux<RouteDefinition>
```

> ⚠️ **所以**：`propertiesRouteDefinitionLocator` 里**没有 yml 解析逻辑** ——
> yml → 对象 是**绑定**（L17 · 17.2.5 ⑤），不是"这个类去解析"。
> **这也是为什么改 yml 后"The route definition 变了但运行态没变"**：配置绑定与运行态缓存是**两件事**（L30）。

### 17.3.2 `InMemoryRouteDefinitionRepository` —— Actuator 的"写入口"

```java
public class InMemoryRouteDefinitionRepository implements RouteDefinitionRepository {
    private final Map<String, RouteDefinition> routes =
            Collections.synchronizedMap(new LinkedHashMap<>());          // ← 就是一个 Map

    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(routes.values());                       // 读
    }
    public Mono<Void> save(Mono<RouteDefinition> route) {
        return route.flatMap(r -> { ...; routes.put(r.getId(), r); ... });   // 写（put = 新增或覆盖）
    }
    public Mono<Void> delete(Mono<String> routeId) { ... routes.remove(id) ... }   // 删
}
```

**它到底干什么**：**一个内存 Map**，装 Actuator **动态加/删**的路由。
**它是 A 组里唯一的"可写"来源** → 所以 `POST /actuator/gateway/routes/{id}` 写的是它。

**❓这个 Map 默认是空的吗？——✅ 是空的**（`new InMemoryRouteDefinitionRepository()` → 空 Map）。证据：

| 检查 | 结果 |
|---|---|
| 谁在写它（全模块 grep） | **只有 `AbstractGatewayControllerEndpoint:242/259`（Actuator 的 POST routes）** |
| 有"启动时把 yml 拷进 Map"的代码吗 | ❌ **没有** |
| **实测反证**（L08 做过） | **`DELETE` 一条 yml 路由 → 404** —— 若 yml 在 Map 里，删除应该成功 |

```
启动后：
  Map（内存 repo）        = {}                ← 空！
  GatewayProperties.routes = [yml 的路由 …]    ← yml 在这（另一个来源）
  两者在 Composite 里合并 → 你看到"路由都在"，但存储是两份
```

**实用推论**：想让 yml 路由也能被 Actuator 改 → **自己实现一个"可写"的 `RouteDefinitionRepository` 接管**
（`@ConditionalOnMissingBean(RouteDefinitionRepository.class)` 生效），把它写回配置中心 —— 这是 **L49** 的主题。

> 🔗 **回到 L08 的那个疑问**：**"为什么 yml 定义的路由 Actuator 删不掉？"**
> —— 因为 `DELETE` 找的是**这个 Map**，而 yml 路由在 `PropertiesRouteDefinitionLocator` 里（**只读**）→ Map 里没有 → 404 ✅（L08 的实测现象，这里给出了源码级解释）

### 17.3.3 `CompositeRouteDefinitionLocator` —— "把所有来源合成一条流"

```java
public Flux<RouteDefinition> getRouteDefinitions() {
    return this.delegates                                            // List<RouteDefinitionLocator>
            .flatMapSequential(RouteDefinitionLocator::getRouteDefinitions)   // ★① 合成（保序）
            .flatMap(rd -> rd.getId() == null                              // ★② 没有 id 的
                    ? randomId().map(id -> { rd.setId(id); return rd; })   //    自动补一个随机 id
                    : Mono.just(rd));
}
```

**它到底干什么**：**把 N 个来源合并成 1 条 `Flux<RouteDefinition>`**，顺手做两件小事：

| # | 做什么 | 为什么要 |
|---|---|---|
| **①** | `flatMapSequential` 而非 `merge` | **保持顺序**（来源顺序 = `List` 注入顺序） |
| **②** | **给没有 id 的 RouteDefinition 补随机 id** | 路由必须能标识；`randomId()` 里用了 `publishOn(Schedulers.boundedElastic())` —— **还记得 L16 我们查"谁用了 boundedElastic"吗？这就是其中一处** ✅ |

#### ★ 它自己**没有 Map** —— 那运行时怎么"看到"新加的路由？

**关键：它是"视图"（无状态），不是"仓库"（有存储）**。看它的字段（就 3 个）：

```java
private static final Log log = ...;
private final Flux<RouteDefinitionLocator> delegates;      // ← 只持有"来源列表"
private final IdGenerator idGenerator;
```

**每次被调用都"实时问"所有来源**：

```java
public Flux<RouteDefinition> getRouteDefinitions() {
    return this.delegates
            .flatMapSequential(RouteDefinitionLocator::getRouteDefinitions)   // ★ 每个来源【现问一遍】
            .flatMap(...);
}
```

**存储永远在来源自己那里**：

| 来源 | 存储在哪 |
|---|---|
| `PropertiesRouteDefinitionLocator` | `GatewayProperties.routes`（List） |
| `InMemoryRouteDefinitionRepository` | **它自己那个 Map** ← Actuator 写这里 |
| （Redis 来源，L49） | Redis |

→ **所以**：Actuator `POST` 改的是**内存 repo 的 Map**；下一次问 Composite，它会去问内存 repo → **读到 Map 的新值** ✅

#### ★ 那为什么"运行态"看不到？（两个视图的缓存策略不同）

| 视图 | 端点 | 谁提供 | 缓存 |
|---|---|---|---|
| **配置态** | `/routedefinitions` | **Composite（实时问来源）** | ❌ **无缓存** → **一改就看到** |
| **运行态** | `/routes` | **`CachingRouteLocator`**（B 组最后一层） | ✅ **有缓存** → **要 refresh** |

**现场实测**（2026-09-28，网关运行中）：

```
① POST /routes/live-demo（写内存 Map） → 201
② 立刻查（不 refresh）：
     配置态 = 7 条，含 live-demo ✅     ← Composite 实时，看到了
     运行态 = 6 条，不含 live-demo ❌   ← CachingRouteLocator 缓存，挡住
③ POST /refresh → 运行态 = 7 条，含 live-demo ✅
④ 实际请求（带 StripPrefix 的路由）：curl /live2/hello → {"port":8081,...}  ✅ 动态路由真的生效
⑤ 清理：DELETE + refresh → 6/6
```

> **一句话**：**"配置态实时、运行态要 refresh"** 的机制差异 =
> **`CompositeRouteDefinitionLocator`（无状态视图，每次现问）** vs **`CachingRouteLocator`（有缓存，refresh 才重建）**。

**它凭什么知道有哪些来源**？—— 构造参数是 `List<RouteDefinitionLocator>`（17.3.4 的装配）：
```
CompositeRouteDefinitionLocator(Flux.fromIterable(routeDefinitionLocators))
                                        ↑ Spring 注入了【容器里所有】RouteDefinitionLocator
```

### 17.3.4 装配源码 + 谁能接管

```java
@Bean @ConditionalOnMissingBean
PropertiesRouteDefinitionLocator propertiesRouteDefinitionLocator(GatewayProperties properties) {
    return new PropertiesRouteDefinitionLocator(properties);            // yml 来源
}

@Bean @ConditionalOnMissingBean(RouteDefinitionRepository.class)
InMemoryRouteDefinitionRepository inMemoryRouteDefinitionRepository() {
    return new InMemoryRouteDefinitionRepository();                      // 内存（Actuator 写入口）
}

@Bean @Primary
RouteDefinitionLocator routeDefinitionLocator(List<RouteDefinitionLocator> routeDefinitionLocators) {
    return new CompositeRouteDefinitionLocator(Flux.fromIterable(routeDefinitionLocators));  // ★ 合并
}
```

| # | 要点 | 含义 |
|---|---|---|
| 1 | ③ 的参数 `List<RouteDefinitionLocator>` | **加来源 = 注册一个 bean**（服务发现 `GatewayDiscoveryClientAutoConfiguration`、Redis（L49）都是这么加的） |
| 2 | `@Primary` | 注入方（B 组）**优先拿到"合并版"** |
| 3 | `@ConditionalOnMissingBean` | **你注册自己的实现，框架就让位**（Spring Boot 自动装配的通用姿势，见 17.2.5 ③） |

### 17.3.5 谁调用它们？（A 组 → B 组的接口）

#### ① 先确认"B 组拿到的到底是哪个 locator"

```java
// A 组：@Primary 的那个（bean 名字就叫 routeDefinitionLocator）
@Bean @Primary
RouteDefinitionLocator routeDefinitionLocator(List<RouteDefinitionLocator> routeDefinitionLocators) {
    return new CompositeRouteDefinitionLocator(Flux.fromIterable(routeDefinitionLocators));
}

// B 组：参数按【类型】注入 → 因为有 @Primary，拿到的是上面那个 Composite
@Bean
RouteLocator routeDefinitionRouteLocator(..., RouteDefinitionLocator routeDefinitionLocator, ...) {
    return new RouteDefinitionRouteLocator(routeDefinitionLocator, ...);   // ← 存进字段
}
```

**✅ 是的**：`RouteDefinitionRouteLocator.routeDefinitionLocator` 这个字段里装的**就是 `CompositeRouteDefinitionLocator`**
（不是 Properties、也不是 InMemory 单独一个）。

**实测反证**（L08 的数据）：Actuator `POST` 加的路由进的是**内存 repo**，
而 `POST /refresh` 后**运行态能看到它** → 说明 B 组读的是**合并后的** Composite ✅
（如果它只读 `PropertiesRouteDefinitionLocator`，Actuator 加的路由永远看不到）

#### ② 编译的"弹药"从哪来：构造函数把 **List 变成 Map**（登记）

`RouteDefinitionRouteLocator` 的参数里，**两个 List 是"容器里该类型的全部 bean"**：

```java
@Bean
public RouteLocator routeDefinitionRouteLocator(GatewayProperties properties,
        List<GatewayFilterFactory> gatewayFilters, List<RoutePredicateFactory> predicates,
        RouteDefinitionLocator routeDefinitionLocator, ConfigurationService configurationService) {
    return new RouteDefinitionRouteLocator(routeDefinitionLocator, predicates, gatewayFilters, properties,
            configurationService);
}
```

`List<X>` 参数的注入语义 = "**这个类型的全部 bean**" → **33 个过滤器工厂 + 15 个谓词工厂一次全进来**
（含自定义的 `demoHeader` ×2；`/actuator/beans` 的依赖列表可以实测）。

**构造函数做的事**（把它们"登记"成"名字 → 工厂"的 Map）：

```java
public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
        List<RoutePredicateFactory> predicates, List<GatewayFilterFactory> gatewayFilterFactories,
        GatewayProperties gatewayProperties, ConfigurationService configurationService) {
    this.routeDefinitionLocator = routeDefinitionLocator;
    this.configurationService = configurationService;
    initFactories(predicates);
    gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
    this.gatewayProperties = gatewayProperties;
}

private void initFactories(List<RoutePredicateFactory> predicates) {
    predicates.forEach(factory -> {
        String key = factory.name();
        if (this.predicates.containsKey(key)) {
            this.logger.warn("A RoutePredicateFactory named " + key + " already exists, class: "
                    + this.predicates.get(key) + ". It will be overwritten.");
        }
        this.predicates.put(key, factory);
        if (logger.isInfoEnabled()) {
            logger.info("Loaded RoutePredicateFactory [" + key + "]");
        }
    });
}
```

字段：

```java
private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();
private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();
```

**为什么必须转 Map**：编译时全是"**按名字查表**"（yml 里写的就是字符串名字）：

```java
this.predicates.get(predicate.getName());              // 谓词查表（④ 的 lookup）
this.gatewayFilterFactories.get(definition.getName()); // 过滤器查表（④ 的 loadGatewayFilters）
```

| # | 细节 |
|---|---|
| 1 | **map 的 key = `factory.name()`**（默认 = 类名去掉 `RoutePredicateFactory`/`GatewayFilterFactory` 后缀）→ **"yml 能写哪些名字"的清单就是这张 Map**（L15 的名字规律在这里落地；自定义 `demoHeader` 自动可用） |
| 2 | 谓词**重名会 WARN 并覆盖**（后注册的赢）；过滤器是静默 put 覆盖 |
| 3 | 谓词 Map 是 `LinkedHashMap`（保序），过滤器 Map 是 `HashMap`；启动日志为每个谓词工厂打一行 `Loaded RoutePredicateFactory [...]` |

#### ③ `configurationService`：把"name + args"变成 **Config 对象**的绑定服务

Bean（名字就叫 `gatewayConfigurationService`）：

```java
@Bean
public ConfigurationService gatewayConfigurationService(BeanFactory beanFactory,
        @Qualifier("webFluxConversionService") ObjectProvider<ConversionService> conversionService,
        ObjectProvider<Validator> validator) {
    return new ConfigurationService(beanFactory, conversionService, validator);
}
```

**两处调用点**（编译期，谓词/过滤器对称）：

```java
// ① 谓词（④ 的 lookup 里）
Object config = this.configurationService.with(factory)
        .name(predicate.getName())
        .properties(predicate.getArgs())
        .eventFunction((bound, properties) -> new PredicateArgsEvent(
                RouteDefinitionRouteLocator.this, route.getId(), properties))
        .bind();
return factory.applyAsync(config);
```

```java
// ② 过滤器（④ 的 loadGatewayFilters 里）
Object configuration = this.configurationService.with(factory)
        .name(definition.getName())
        .properties(definition.getArgs())
        .eventFunction((bound, properties) -> new FilterArgsEvent(
                RouteDefinitionRouteLocator.this, id, (Map<String, Object>) properties))
        .bind();
```

**`.bind()` 内部干三步**（`AbstractBuilder.bind()`）：

```java
public T bind() {
    validate();
    Assert.hasText(this.name, "name may not be empty");
    Assert.isTrue(this.properties != null || this.normalizedProperties != null,
            "properties and normalizedProperties both may not be null");
    if (this.normalizedProperties == null) {
        this.normalizedProperties = normalizeProperties();       // ★ ① 归一化（简写 / SpEL）
    }
    T bound = doBind();                                          // ★ ② 绑定（Spring Boot Binder）
    if (this.eventFunction != null && this.service.publisher != null) {
        ApplicationEvent applicationEvent = this.eventFunction.apply(bound, this.normalizedProperties);
        this.service.publisher.publishEvent(applicationEvent);   // ★ ③ 发 Args 事件
    }
    return bound;
}
```

**绑定细节**（`ConfigurableBuilder` 的两个关键覆盖 + 最终的 `bindOrCreate`）：

```java
// ConfigurableBuilder（with(factory) 走这个）
@Override
protected Map<String, Object> normalizeProperties() {
    if (this.service.beanFactory != null) {
        return this.configurable.shortcutType()
            .normalize(this.properties, this.configurable, this.service.parser, this.service.beanFactory);
    }
    return super.normalizeProperties();
}
@Override
protected T doBind() {
    Bindable<T> bindable = Bindable.of(this.configurable.getConfigClass());
    T bound = bindOrCreate(bindable, this.normalizedProperties, this.configurable.shortcutFieldPrefix(),
            this.service.validator.get(), this.service.conversionService.get());
    return bound;
}
```

```java
static <T> T bindOrCreate(Bindable<T> bindable, Map<String, Object> properties,
        String configurationPropertyName, Validator validator, ConversionService conversionService) {
    // see ConfigurationPropertiesBinder from spring boot for this definition.
    BindHandler handler = new IgnoreTopLevelConverterNotFoundBindHandler();
    if (validator != null) { // TODO: list of validators?
        handler = new ValidationBindHandler(handler, validator);
    }
    List<ConfigurationPropertySource> propertySources = Collections
        .singletonList(new MapConfigurationPropertySource(properties));
    return new Binder(propertySources, null, conversionService).bindOrCreate(configurationPropertyName, bindable,
            handler);
}
```

| 能力 | 做什么 | 关键源码 |
|---|---|---|
| **① 简写归一化** | yml `- Path=/foo/**` 的 args 是 `{_genkey_0: "/foo/**"}` → **按 `shortcutFieldOrder()` 把 `_genkey_0` 换成真实字段名**；值支持 **SpEL**（`#{...}`，带 BeanFactory 上下文） | `shortcutType().normalize(properties, configurable, parser, beanFactory)`（`ShortcutConfigurable`） |
| **② 类型绑定** | `Map<String,String>` → **类型化 Config 对象**（String→`Duration`/`URI`/`Boolean`…），带校验 | `new Binder(propertySources, null, conversionService).bindOrCreate(name, bindable, handler)`（+`ValidationBindHandler`） |
| **③ Args 事件** | bind 完发 `PredicateArgsEvent` / `FilterArgsEvent` | `eventFunction.apply(...)` → `service.publisher.publishEvent(...)` |

**Args 事件谁在听（实测）**：

| 监听者 | 干什么 |
|---|---|
| `WeightCalculatorWebFilter` | 听 `PredicateArgsEvent`（Weight 谓词参数要单独收着） |
| **`AbstractRateLimiter`** | 听 `FilterArgsEvent` —— 而且**"反向"用 ConfigurationService 再 bind 一次**（`with(instance)` 模式） |

```java
@Override
public void onApplicationEvent(FilterArgsEvent event) {
    Map<String, Object> args = event.getArgs();
    if (args.isEmpty() || !hasRelevantKey(args)) {
        return;
    }
    String routeId = event.getRouteId();
    C routeConfig = newConfig();
    if (this.configurationService != null) {
        this.configurationService.with(routeConfig)        // ← instance 模式（不是 with(factory)）
            .name(this.configurationPropertyName)
            .normalizedProperties(args)
            .bind();
    }
    ...
}
```

> **一句话**：**构造函数 = "登记处"**（List→Map，为按名查工厂做准备）；
> **`configurationService` = "绑定器"**（简写+SpEL → 类型绑定 → 发事件），谓词/过滤器共用同一套"字符串→对象"的管道。
> 它 `implements ApplicationEventPublisherAware` —— 又是 17.4.9 讲的"业务类只拿 `ApplicationContext` 当发布者"。

#### ④ "编译成 Route" 是什么意思（★ 本节核心）

先看两个模型的差别（**这是 Framework 里最"值钱"的一层抽象**）：

| | **`RouteDefinition`（定义）** | **`Route`（运行时）** |
|---|---|---|
| 形态 | **声明式**：`uri` 是**字符串**；`predicates`/`filters` 是 **`{name, args}` 列表** | **可执行**：`uri` 是 `URI`；`predicate` 是**一个 `AsyncPredicate` 对象**；`filters` 是**一批 `GatewayFilter` 对象** |
| 谁产出 | **A 组**（来源） | **B 组**（`convertToRoute`） |
| 存哪 | yml / Actuator JSON / Nacos / Redis | 内存（`CachingRouteLocator` 的缓存） |
| 用途 | **存储 / 传输** | **实际匹配 + 执行**（C 组匹配、D 组过滤） |

**"编译"就是 `convertToRoute` 那三行**：

```java
private Route convertToRoute(RouteDefinition routeDefinition) {
    AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);  // ① 谓词编译
    List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);                 // ② 过滤器编译
    return Route.async(routeDefinition).asyncPredicate(predicate)                     // ③ 组装成 Route
            .replaceFilters(gatewayFilters).build();
}
```

**① 谓词编译**：`combinePredicates` → 逐个 `lookup()` → **按名字查工厂 + 绑定参数 + 调 `applyAsync()`**：

```java
private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {
    RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());   // ← 按名字查【14 个工厂 bean】
    if (factory == null) throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name ...");
    Object config = this.configurationService.with(factory)                             // ← 绑定参数 → Config 对象（③ 的 bind）
            .name(predicate.getName()).properties(predicate.getArgs()).bind();
    return factory.applyAsync(config);                                                  // ★ 调工厂！→ 得到对象
}
// 多个谓词：predicate = predicate.and(found) 逐个串联（= L04 的"AND"）→ 最终是【一个】AsyncPredicate
```

**② 过滤器编译**：`getFilters()` = **默认过滤器 + 路由过滤器 → 排序**：

```java
private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
    List<GatewayFilter> filters = new ArrayList<>();
    if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {          // ① 先加【default-filters】
        filters.addAll(loadGatewayFilters(routeDefinition.getId(), new ArrayList<>(...)));
    }
    if (!CollectionUtils.isEmpty(routeDefinition.getFilters())) {         // ② 再加【路由自己的 filters】
        filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
    }
    AnnotationAwareOrderComparator.sort(filters);                         // ③ 按 order 排序
    return filters;
}
```

> **看到这里，"名字+参数"→"对象"的全过程就通了**：
> - 谓词：`Path=/lb/**` → 查 `PathRoutePredicateFactory` → `bind(args)` → **`applyAsync(config)`** → `AsyncPredicate` 对象
> - 过滤器：`StripPrefix=1` → 查 `StripPrefixGatewayFilterFactory` → `bind(args)` → **`apply(config)`** → `GatewayFilter` 对象
>
> ⭐ **这两个 `apply()` 就是 L14/L15 你亲手写过的那个方法** ——
> 你写的 `DemoHeaderGatewayFilterFactory.apply()` 与内置工厂的 `apply()`，在**这同一个循环里**被调用。
>
> ⭐ **这里也解释了 L05 的"`default-filters` 对所有路由生效"**：
> 它不是"运行时判断"，而是**编译每条路由时被 merge 进列表**（①）→ 所以每条路由的 `filters` 里都有一份。

**③ 组装**：`Route.async(routeDefinition).asyncPredicate(...).replaceFilters(...).build()`
→ 产出 `Route`，交给 `CachingRouteLocator` 缓存（17.4），再被 C 组匹配、D 组执行。

#### ⑤ 完整链路（一句话 + 图）

```java
// RouteDefinitionRouteLocator（B 组）:105-110
public Flux<Route> getRoutes() {
    return getRoutes(this.routeDefinitionLocator.getRouteDefinitions());   // ← 调 A 组（Composite）
}
private Flux<Route> getRoutes(Flux<RouteDefinition> routeDefinitions) {
    Flux<Route> routes = routeDefinitions.map(this::convertToRoute);       // ← 编译成 Route（本节 ④）
    ...
}
```

> **A 组（多个来源 → Composite 合并）→ B 组 `RouteDefinitionRouteLocator` 调 `getRouteDefinitions()`
> → 逐条 `convertToRoute`（查工厂 + bind + apply）→ `Route` → `CachingRouteLocator` 缓存（17.4）**。

**实测的 A 组 bean**：
```
propertiesRouteDefinitionLocator   → PropertiesRouteDefinitionLocator       ← yml
inMemoryRouteDefinitionRepository  → InMemoryRouteDefinitionRepository      ← Actuator 写
routeDefinitionLocator             → CompositeRouteDefinitionLocator        ← 合并（@Primary）★ B 组拿的是它
+ GatewayDiscoveryClientAutoConfiguration$ReactiveDiscoveryClientRouteDefinitionLocatorConfiguration  ← 服务发现自动路由（L44）
```

---

### 17.3.6 `GatewayProperties` 的结构（yml 到底绑到哪）

**先回答"只去 `GatewayProperties` 直接读取是吗"**：✅ **是** ——
`properties.getRoutes()` 就是**直接读字段**（没有二次解析、没有转换、没有 IO）。

**`GatewayProperties` 的完整结构**（源码字段）：

```java
@ConfigurationProperties(GatewayProperties.PREFIX)          // PREFIX = "spring.cloud.gateway"
@Validated
public class GatewayProperties {

    @NotNull @Valid
    private List<RouteDefinition> routes = new ArrayList<>();   // ★ 17.3.1 读的就是它

    private List<FilterDefinition> defaultFilters = new ArrayList<>();   // 全局默认过滤器（L05/L25 读）
    private List<MediaType> streamingMediaTypes = [ TEXT_EVENT_STREAM, ... ];  // 流式类型（SSE/gRPC）
    private boolean failOnRouteDefinitionError = true;         // 定义出错是否 fail-fast
    private boolean routeFilterCacheEnabled = false;           // 过滤器缓存开关（内部字段）
}
```

**⚠️ 哪些字段真的能从 yml 绑？—— 查元数据文件**（和 17.5.3 的 `.imports` 一样的手艺）：

```bash
unzip -p spring-cloud-gateway-server-4.1.5.jar 'META-INF/spring-configuration-metadata.json' \
  | python3 -c "import sys,json;[print(' ',p['name']) for p in json.load(sys.stdin)['properties'] if p['name'].startswith('spring.cloud.gateway.') and p['name'].count('.')<=3]"
```
```
spring.cloud.gateway.default-filters
spring.cloud.gateway.enabled                     ← 17.2.5 ③ 的总开关
spring.cloud.gateway.fail-on-route-definition-error
spring.cloud.gateway.routes
spring.cloud.gateway.streaming-media-types
```
→ **这就是"绑到 `GatewayProperties` 的全部属性"**（`route-filter-cache-enabled` 不在其中 —— 它是内部字段，yml 绑不上）。

**`RouteDefinition` 的结构**（`routes` 列表里的元素）：

```java
public class RouteDefinition {
    private String id;
    private URI uri;
    private List<PredicateDefinition> predicates;      // name + args（L06 的 _genkey_ 就在 args 里）
    private List<FilterDefinition> filters;
    private Map<String, Object> metadata;
    private int order;
    private boolean enabled = true;                    // ← 17.3.1 过滤的就是它
}
```

**yml ↔ 字段的对应**（"绑定"长什么样）：

```yaml
spring:
  cloud:
    gateway:                        # ← PREFIX：GatewayProperties
      routes:                       # → routes: List<RouteDefinition>
        - id: demo-lb               #     → RouteDefinition.id
          uri: lb://demo-provider   #     → RouteDefinition.uri
          predicates:               #     → RouteDefinition.predicates
            - Path=/lb/**
          filters:                  #     → RouteDefinition.filters
            - StripPrefix=1
          metadata:                 #     → RouteDefinition.metadata
            response-timeout: 500
          enabled: true             #     → RouteDefinition.enabled（false → A 组会把它过滤掉）
      default-filters:              # → defaultFilters
        - AddResponseHeader=X-Gw,1
```

**⚠️ 一个容易搞错的点**：**不是 `spring.cloud.gateway.*` 下所有东西都绑到 `GatewayProperties`**：

| yml 前缀 | 绑到哪个对象 | 谁在用 |
|---|---|---|
| `spring.cloud.gateway.routes` / `default-filters` / `streaming-media-types` … | **`GatewayProperties`** | 本课（**A 组读 `routes`；`default-filters` 是 B 组读的** —— `RouteDefinitionRouteLocator:185`） |
| `spring.cloud.gateway.httpclient.*` | **`HttpClientProperties`（另一个类！）** | L06 超时 / L30 连接池 |
| `spring.cloud.gateway.globalcors.*` | `GlobalCorsProperties` | L07 |
| `spring.cloud.gateway.metrics.*` | GatewayMetricsProperties | L35 |

> **所以"yml → 对象"这一步是"一个前缀一个类"** —— `PREFIX` 常量就是那个类的"门牌号"。

---

## 17.4 ★ B 组：装配链（掌握点②）

### 17.4.0 先区分两族名字（**最容易混的地方**）

| 名字 | 哪组 | 产出 |
|---|---|---|
| `Route**Definition**Locator` | **A 组**（定义层） | `Flux<RouteDefinition>` |
| `RouteLocator`（**不带 Definition**） | **B 组**（运行层） | `Flux<Route>` |

> **规律**：名字带 `Definition` → A 组（"**定义**从哪来"）；不带 → B 组（"**可运行的路由**从哪来"）。

### 17.4.1 B 组干什么？—— **翻译 + 汇总 + 缓存**（三个类各干一件事）

**先问"为什么"**：A 组已经产出了 `RouteDefinition`，为什么还要 B 组？

| | A 组产物 | 运行时需要的东西 |
|---|---|---|
| 形态 | `RouteDefinition` —— **声明式**（`{name, args}`、uri 是**字符串**） | `Route` —— **可执行对象**（`AsyncPredicate` 对象 + `GatewayFilter` 对象 + `URI`） |
| 适合 | **存储 / 传输**（yml / JSON / Nacos） | **匹配 + 执行** |

```
A 组: RouteDefinition（声明）
          │
          ▼  ★ B 组：翻译 + 汇总 + 缓存
B 组: Route（可执行）
          │
          ▼  交给 C 组匹配
```

| 类 | 职责 | 一句话 |
|---|---|---|
| **`RouteDefinitionRouteLocator`** | **翻译** | `RouteDefinition → Route`（= 17.3.5 的 `convertToRoute`：查工厂 + bind + apply） |
| **`CompositeRouteLocator`** | **汇总** | 把**多个** `RouteLocator` 合成一条流 |
| **`CachingRouteLocator`** | **缓存** | 缓存最终结果（= L08"改完要 refresh"的那个缓存） |

### 17.4.2 两族的"桥"：`RouteDefinitionRouteLocator`

```java
public class RouteDefinitionRouteLocator implements RouteLocator {      // ① 实现【B 组】接口 → 产出 Route
    private final RouteDefinitionLocator routeDefinitionLocator;        // ② 持有【A 组】接口 → 消费 RouteDefinition
}
```

**它一边"是" B 组的实现、一边"消费" A 组** —— 所以：
- A 组输出 `RouteDefinition` → **翻译器** → B 组输出 `Route` ✅

**数据流（一张图）**：

```
【A 组：RouteDefinitionLocator 家族】          产出 Flux<RouteDefinition>
   ├─ PropertiesRouteDefinitionLocator（yml）
   ├─ InMemoryRouteDefinitionRepository（Actuator）
   └─ CompositeRouteDefinitionLocator（合并，@Primary）
                        │  Flux<RouteDefinition>
                        ▼
【B 组：RouteLocator 家族】                    产出 Flux<Route>
   ├─ RouteDefinitionRouteLocator ← ★翻译★（两族之桥）
   ├─ CompositeRouteLocator       ← ★汇总★（List<RouteLocator>：yml 路线 + 你的 Java DSL）
   └─ CachingRouteLocator         ← ★缓存★（@Primary，C 组拿到的是它）
                        │  Flux<Route>
                        ▼
【C 组】RoutePredicateHandlerMapping：拿 Route 去匹配
```

> **Java DSL 的汇合点**：你写的 `@Bean RouteLocator`（`RouteLocatorBuilder.routes()...build()`）也是 `RouteLocator`，
> 被 `List<RouteLocator>` 收集后，和 yml 路线**在汇总器里平等汇合** ✅（这就是 L13 说的"两条路"）

### 17.4.3 装配源码 + 三种 Composite/装饰器要点

```java
@Bean
RouteLocator routeDefinitionRouteLocator(GatewayProperties properties,
        List<GatewayFilterFactory> gatewayFilters, List<RoutePredicateFactory> predicates,
        RouteDefinitionLocator routeDefinitionLocator, ConfigurationService configurationService) {
    return new RouteDefinitionRouteLocator(routeDefinitionLocator, predicates, gatewayFilters,
            properties, configurationService);                       // ① 翻译器
}

@Bean @Primary @ConditionalOnMissingBean(name = "cachedCompositeRouteLocator")
RouteLocator cachedCompositeRouteLocator(List<RouteLocator> routeLocators) {
    return new CachingRouteLocator(                                  // ③ 最外层：缓存
            new CompositeRouteLocator(Flux.fromIterable(routeLocators)));  // ② 中间层：汇总
}
```

| # | 要点 | 对应哪课 |
|---|---|---|
| ① 参数 `List<GatewayFilterFactory>` / `List<RoutePredicateFactory>` | **你自定义的工厂（L14/L15）就是这样被收集的** | L14/L15 |
| ② 参数 `List<RouteLocator>` | **Java DSL 与 yml 两条路在这里汇合** | L13 |
| ③ `CachingRouteLocator` 是"运行态快照" | **改完必须 refresh** 的根因 | L08 / L30 |
| `@ConditionalOnMissingBean(name="cachedCompositeRouteLocator")` | **整条链你都能接管**（换自己的实现） | 17.2.5 ③ |

### 17.4.4 "最外层"是什么意思？—— **装饰器链**，不是链表

```java
new CachingRouteLocator(                                    // ← "最外层"（@Primary，C 组拿到的就是它）
        new CompositeRouteLocator(Flux.fromIterable(routeLocators)));   // ← 内层，被 new 出来持有
```

**"最外层"的判据**：**谁被 `@Primary` 标注 + 被注入给别人**，谁就是最外层。

| 组 | 最外层的 bean | 注入方拿到的就是它 |
|---|---|---|
| A 组 | `CompositeRouteDefinitionLocator`（`@Primary`） | B 组注入 `RouteDefinitionLocator` |
| B 组 | `CachingRouteLocator`（`@Primary`） | **C 组**注入 `RouteLocator` |

**像链表，但准确说是"装饰器（包装）链"**：

| | 链表 | 这里 |
|---|---|---|
| 节点类型 | **同一类型** | **不同类型**：缓存器 / 汇总器 / 翻译器 |
| 每层干什么 | 只是"走下一个" | **各自加行为**（缓存、汇总、翻译） |
| 怎么持有 | `next` 指针 | **`delegate` 字段（构造注入）** |
| **共同点** | ✅ 都实现同一接口 | ✅ **都 `implements RouteLocator`**（所以能互相包） |

> 🔗 **同一个模式你在 L16 见过**：`DelegatingServiceInstanceListSupplier`（supplier 的装饰器链）—— 同一思想。

**运行时证据**（`/actuator/beans` 的 `dependencies`，实测）：

```
cachedCompositeRouteLocator (CachingRouteLocator)
   └─依赖→ routeDefinitionRouteLocator (RouteDefinitionRouteLocator)
              ├─依赖→ gatewayProperties
              ├─依赖→ 33 个 GatewayFilterFactory（★ 含 demoHeaderGatewayFilterFactory —— L14 你写的）
              ├─依赖→ 15 个 RoutePredicateFactory（★ 含 demoHeaderRoutePredicateFactory —— L15 你写的）
              └─依赖→ routeDefinitionLocator (CompositeRouteDefinitionLocator)
                         ├─依赖→ propertiesRouteDefinitionLocator → gatewayProperties
                         └─依赖→ inMemoryRouteDefinitionRepository（依赖为空 = 空 Map ✅）
```

### 17.4.5 ★ `CachingRouteLocator` 的 5 个字段（本节核心类）

```java
public class CachingRouteLocator implements Ordered, RouteLocator,
        ApplicationListener<RefreshRoutesEvent>, ApplicationEventPublisherAware {

    private static final String CACHE_KEY = "routes";                     // ①
    private final RouteLocator delegate;                                  // ② 被包装的（Composite）
    private final Flux<Route> routes;                                     // ③ ★ 核心字段
    private final Map<String, List> cache = new ConcurrentHashMap<>();    // ④ 缓存容器
    private ApplicationEventPublisher applicationEventPublisher;          // ⑤ 发事件用

    public CachingRouteLocator(RouteLocator delegate) {
        this.delegate = delegate;
        routes = CacheFlux.lookup(cache, CACHE_KEY, Route.class)          // ★ 每次订阅 → 查 cache
                .onCacheMissResume(this::fetch);                          // ★ 未命中 → 才 fetch
    }
    private Flux<Route> fetch() {
        return this.delegate.getRoutes().sort(AnnotationAwareOrderComparator.INSTANCE);  // ★ 委托 + 排序
    }
    public Flux<Route> getRoutes() { return this.routes; }                // ★ 永远返回同一个字段
}
```

| 字段 | 是什么 | 为什么这么设计 |
|---|---|---|
| `delegate` | 被包装的 `CompositeRouteLocator` | 缓存未命中时委托它 |
| **`routes`** ★ | **一个"每次订阅时查 cache"的 Flux**（构造时**只创建一次**） | `getRoutes()` 永远返回**同一对象**；**内容**每次订阅时由缓存决定 |
| `cache` | `ConcurrentHashMap<String, List>` | **value 是 `List<Signal<Route>>`，不是 `List<Route>`！** |
| `CACHE_KEY` | `"routes"` | `CacheFlux` 是"Map + key"式设计（可扩展多 key） |
| `applicationEventPublisher` | 发 `RefreshRoutesResultEvent` | 刷新完成通知（可观测） |

**两个精彩细节（面试可讲）**：

| # | 细节 | 意义 |
|---|---|---|
| **①** | `getRoutes()` 返回**同一个 Flux 对象**，但**内容会变** | 因为 `CacheFlux.lookup(cache, ...)` **每次订阅都查 cache 当前值** → 清缓存后**同一个对象**给出新内容（不是"替换 Flux"） |
| **②** | 缓存里存的是 **`List<Signal<Route>>`**（信号） | `updateCache()` 里用 `.materialize()` 把 **onNext/onComplete/onError** 全部物化 → **重放时连错误/完成状态都一致** |

**刷新机制**：

```java
public void onApplicationEvent(RefreshRoutesEvent event) {
    if (cache.containsKey(CACHE_KEY) && event.isScoped()) {
        // ① 局部刷新（?metadata=group:g1）：只重新 fetch 匹配该 metadata 的 + 保留其他旧路由
        ...updateCache(Flux.concat(scopedRoutes, getNonScopedRoutes(event))...);
    } else {
        // ② 全量刷新：重新 fetch 全部 → updateCache
    }
}
private void publishRefreshEvent(List<Signal<Route>> signals) {
    cache.put(CACHE_KEY, signals);                                              // ★ 写缓存
    applicationEventPublisher.publishEvent(new RefreshRoutesResultEvent(this)); // ★ 通知
}
```

> **注意**：刷新**不是"清空等下次"**，而是**主动 fetch 并 updateCache**（刷新是"有成本的主动动作"）。

**谁触发刷新**（`RouteRefreshListener`）—— **L44 的伏笔**：

| 事件 | 场景 |
|---|---|
| `ContextRefreshedEvent` | 上下文启动/刷新 |
| **`RefreshScopeRefreshedEvent`** | **配置刷新**（Nacos 推配置） |
| **`InstanceRegisteredEvent`** | 实例注册 |
| **`HeartbeatEvent` / `ParentHeartbeatEvent`** | **注册中心心跳**（实例变化） |

→ **服务实例变化 / 配置变化会自动触发路由刷新**（不需要人工 `/refresh`）✅

**小发现**：`CachingRouteLocator.refresh()` 这个 public 方法在仓库里**没有内部调用者**（实际刷新走 `onApplicationEvent`）—— 更像留给外部的 API。

### 17.4.6 为什么"缓存放最外层"？

| | **A 组产物** | **B 组产物** |
|---|---|---|
| 产出一次的成本 | **便宜**：读 `GatewayProperties.routes`（内存 List）/ 读 Map | **贵**：**编译**（查工厂 + bind + `apply()`）+ 排序 |
| 调用频率 | 低（配置变更时） | **高（每个请求匹配都要 `getRoutes()`）** |
| 要不要缓存 | ❌ 不需要（便宜，且要求实时） | ✅ **需要** |

> **设计规律**：**缓存放在"贵的那一层"** —— A 组保持无状态实时，B 组最外层加 `CachingRouteLocator` ✅
> **收益**：命中缓存时，**连 A 组都不会被调用**（调用链在缓存处就停了）。

**实测的 B 组 bean**：
```
routeDefinitionRouteLocator   → RouteDefinitionRouteLocator
cachedCompositeRouteLocator   → CachingRouteLocator            ← @Primary
```

---

### 17.4.7 "刷新"是什么意思？—— 现场演示 + 准确定义

**先看事实**（在跑着的网关上实测：`/routedefinitions` 读**原始定义**、`/routes` 读**编译成品**）：

| 时刻 | `/routedefinitions`（**定义**=原料） | `/routes`（**编译好的成品**） |
|---|---|---|
| 初始 | 6 条 | 6 条 |
| POST 加一条定义（**不刷新**） | **7 条** ✅ 立刻有 | **6 条** ❌ **还是没有！** |
| POST `/refresh` 之后 | 7 条 | **7 条** ✅ 有了 |

**为什么两个列表不一致？** 两个端点读的是**不同的东西**（`GatewayControllerEndpoint`）：

```java
@GetMapping("/routedefinitions")
public Flux<RouteDefinition> routesdef() {
    return this.routeDefinitionLocator.getRouteDefinitions();     // ← A 组：读"定义"（实时）
}

@GetMapping("/routes")
public Flux<Map<String, Object>> routes() {
    return this.routeLocator.getRoutes().map(this::serialize);    // ← B 组：读"编译成品"（缓存！）
}
```

路由有**两种形态**：
- **定义**（`RouteDefinition`）= 图纸/原料（yml / Actuator / Nacos 提供）
- **可执行路由**（`Route`）= 按图纸编译好的**成品**（谓词/过滤器都是编译后的对象）

启动时把定义**编译**成成品（≈ 查 33 个过滤器工厂 + bind + apply，很贵），成品存在 `CachingRouteLocator.cache` 里。

> **一句话："刷新" = 让成品缓存作废、重新编译一遍。**
>
> | 是不是？ | 说明 |
> |---|---|
> | ❌ 不是重启 | JVM 不重启、bean 不重建，只重算路由 |
> | ❌ 不是"重新读文件" | 读文件只是副产品，重点是**重算成品**（重走整条 B 组链） |
> | ✅ 是"重编译 + 换缓存" | `fetch()` → `cache.put("routes", 新成品)` |

**为什么必须要有它**：编译**贵** → 所以要**缓存**（17.4.6）；但原料会变 → 缓存**不会自己变** → 需要一个显式的"作废 + 重算"动作 = **refresh**（这就是 L08 "改完要 refresh" 的根因）。

**复现命令**（demo 网关，注意跑完清理）：

```bash
GW=http://localhost:8080/actuator/gateway
# 1) 加一条定义（只写原料，不触发编译）
curl --noproxy '*' -s -X POST $GW/routes/refresh-demo -H 'Content-Type: application/json' \
  -d '{"id":"refresh-demo","uri":"http://localhost:8081","predicates":[{"name":"Path","args":{"_genkey_0":"/refresh-demo/**"}}],"filters":[]}'
# 2) 立刻对比两边：定义里有、成品里没有
curl --noproxy '*' -s $GW/routes            | python3 -c "import json,sys;print('成品:', len(json.load(sys.stdin)))"
curl --noproxy '*' -s $GW/routedefinitions  | python3 -c "import json,sys;print('定义:', len(json.load(sys.stdin)))"
# 3) 刷新（★ 就是这一下）
curl --noproxy '*' -s -X POST $GW/refresh
# 4) 再看：成品也有了
curl --noproxy '*' -s $GW/routes            | python3 -c "import json,sys;print('成品:', len(json.load(sys.stdin)))"
# 5) 清理
curl --noproxy '*' -s -X DELETE $GW/routes/refresh-demo && curl -s -X POST $GW/refresh
```

### 17.4.8 事件机制：其实就是一个"广播"

**先看最朴素的样子**（Spring 通用模式，非网关）：

```java
// ① 事件 = 一条消息（谁都能定义）
class OrderPaidEvent extends ApplicationEvent {
    public OrderPaidEvent(Object source) { super(source); }
}
// ② 发布 = 喊一声（不用知道谁在听）
applicationEventPublisher.publishEvent(new OrderPaidEvent(this));
// ③ 监听 = 谁感兴趣谁登记，只收自己关心的类型
class SmsListener implements ApplicationListener<OrderPaidEvent> {
    @Override
    public void onApplicationEvent(OrderPaidEvent event) { 发短信(); }   // ④ 收到就干活
}
```

| 特点 | 说明 |
|---|---|
| **解耦** | 发布者代码里**没有监听者的影子**；监听者也不知道谁发的 |
| **一对多** | 一条消息可以有很多监听者 |
| **同步** | `publishEvent` 那行会**依次调完所有监听者**才返回（默认同一线程） |

**网关里就是把这个模式套在"刷新"上**：

**① 事件类** —— `RefreshRoutesEvent`：

```java
public class RefreshRoutesEvent extends ApplicationEvent {
    private final Map<String, Object> metadata;
    public RefreshRoutesEvent(Object source) { this(source, null); }
    public RefreshRoutesEvent(Object source, Map<String, Object> metadata) {
        super(source);
        this.metadata = metadata;
    }
    public boolean isScoped() { return !CollectionUtils.isEmpty(getMetadata()); }
    public Map<String, Object> getMetadata() { return metadata; }
}
```

**② 发布者之一：手动**（Actuator 端点）：

```java
@PostMapping("/refresh")
public Mono<Void> refresh(@RequestParam(value = "metadata", required = false) List<String> byMetadata) {
    publishRefreshEvent(byMetadata);
    return Mono.empty();
}
private void publishRefreshEvent(List<String> byMetadata) {
    RefreshRoutesEvent event;
    if (!CollectionUtils.isEmpty(byMetadata)) {
        event = new RefreshRoutesEvent(this, convertToMap(byMetadata));   // 局部刷新
    } else {
        event = new RefreshRoutesEvent(this);                              // 全量刷新
    }
    this.publisher.publishEvent(event);                                    // ★ 喊一声
}
```

**② 发布者之二：自动** —— `RouteRefreshListener`（由 `GatewayAutoConfiguration:272` 注册为 bean）：

```java
public class RouteRefreshListener implements ApplicationListener<ApplicationEvent> {
    private final ApplicationEventPublisher publisher;
    private HeartbeatMonitor monitor = new HeartbeatMonitor();

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ContextRefreshedEvent) { ...; reset(); }          // 启动/上下文刷新
        else if (event instanceof RefreshScopeRefreshedEvent                  // ★ Nacos 推配置
                || event instanceof InstanceRegisteredEvent) { reset(); }     //   实例注册
        else if (event instanceof ParentHeartbeatEvent) { resetIfNeeded(e.getValue()); }
        else if (event instanceof HeartbeatEvent) { resetIfNeeded(e.getValue()); }  // ★ 注册中心心跳
    }
    private void resetIfNeeded(Object value) {
        if (this.monitor.update(value)) { reset(); }   // 去重：心跳值没变化就不重刷
    }
    private void reset() {
        this.publisher.publishEvent(new RefreshRoutesEvent(this));   // ★ 也喊一声，和手动同一条消息
    }
}
```

**③ 监听者**（**不止一个** —— 实测有 4 个活着的监听者，`CachingRouteLocator` 是主角）：

`CachingRouteLocator.onApplicationEvent` → `fetch()`（重编译）→ `updateCache()` → `cache.put("routes", 新成品)`

| 监听者（实测活着的 bean） | 收到刷新事件干什么 |
|---|---|
| **`cachedCompositeRouteLocator`（`CachingRouteLocator`）** | ★ **重编译 + 更新路由缓存**（本节主角） |
| `filteringWebHandler`（`FilteringWebHandler`） | 清 `routeFilterMap`（per-route 过滤器缓存，默认关闭；见 17.5.2） |
| `routeDefinitionMetrics`（`RouteDefinitionMetrics`） | 刷新"路由定义"指标 |
| `corsGatewayFilterApplicationListener` | 把**路由级 CORS 配置**重灌进 `RoutePredicateHandlerMapping`（L07 相关） |

> 八卦一笔：`CachingRouteDefinitionLocator` 这个类也存在（也监听刷新），但**全仓库没有装配它**（只有测试引用）—— "备着没用上"。

**全景图**：

```
【触发源】
  手动: POST /actuator/gateway/refresh ───────────┐
  自动: RouteRefreshListener ◄── 这些 Spring 事件  │
        ├─ ContextRefreshedEvent（启动）           │  全都转成
        ├─ RefreshScopeRefreshedEvent（Nacos 配置）│  同一条消息
        ├─ InstanceRegisteredEvent（实例注册）     │
        └─ HeartbeatEvent（注册中心心跳）          │
                                                  ▼
                          ★ RefreshRoutesEvent（广播）★
                                                  │
                                                  ▼
                    CachingRouteLocator.onApplicationEvent（主监听者；共 4 个，见上表）
                                    │
                        fetch() 重编译 + updateCache()
                                    │
                                    ▼
                      cache.put("routes", 新成品)  ← 刷新完成
                                    │
                                    ▼
                  RefreshRoutesResultEvent（"刷完/刷失败"的结果消息）
                  仓库里只有测试在听 → 生产代码的扩展点（可监听做监控）
```

**手动 vs 自动**：

| 场景 | 消息从哪来 | 要手动吗 |
|---|---|---|
| **Actuator 加/删路由**（17.4.7 演示） | 你 POST `/refresh` | ✅ **手动** |
| 应用启动 / 上下文刷新 | `ContextRefreshedEvent` | ❌ 自动 |
| **Nacos 推配置** | `RefreshScopeRefreshedEvent` | ❌ 自动 |
| **服务实例上下线** | `HeartbeatEvent` | ❌ 自动 |
| 实例注册 | `InstanceRegisteredEvent` | ❌ 自动 |

> 一句话记忆：**"外部世界"的变化（注册中心/配置中心）自动刷；"你自己运行时改的"（Actuator 加路由）才要手动刷。**

### 17.4.9 两个常见追问（事件机制的骨架）

**追问 1：发布者是不是持有所有监听器？**

经典 Observer 模式（subject 持有 observer 列表）的直觉**对** —— 但 Spring 把**"持有列表 + 回调"的职责从业务发布者手里拿走了**，交给了框架（Spring Framework 6.2.9 源码）：

```
业务发布者（Actuator / CachingRouteLocator / RouteRefreshListener）
    │  只持有 ApplicationEventPublisher（其实就是 ApplicationContext）
    │  publishEvent(event)
    ▼
ApplicationContext（AbstractApplicationContext implements ApplicationEventPublisher）
    │  this.applicationEventMulticaster.multicastEvent(applicationEvent, eventType);   ← 转发
    ▼
ApplicationEventMulticaster（默认 SimpleApplicationEventMulticaster）★ 真正的"持有者"
    │  for (ApplicationListener<?> listener : getApplicationListeners(event, type))
    │      invokeListener(listener, event);
    ▼
listener.onApplicationEvent(event)     ← 回调
```

```java
// ① 业务 bean 拿到的"发布者"就是 ApplicationContext 本身
//    （ApplicationContextAwareProcessor:100-102）
if (bean instanceof ApplicationEventPublisherAware applicationEventPublisherAware) {
    applicationEventPublisherAware.setApplicationEventPublisher(this.applicationContext);
}
```

```java
// ② publishEvent 只是"转发给广播器"（AbstractApplicationContext:420+）
if (this.earlyApplicationEvents != null) {
    this.earlyApplicationEvents.add(applicationEvent);
}
else if (this.applicationEventMulticaster != null) {
    this.applicationEventMulticaster.multicastEvent(applicationEvent, eventType);   // ★ 转发
}
```

```java
// ③ 广播器才持有监听器列表并逐个回调（SimpleApplicationEventMulticaster:142-158）
public void multicastEvent(ApplicationEvent event, @Nullable ResolvableType eventType) {
    ResolvableType type = (eventType != null ? eventType : ResolvableType.forInstance(event));
    Executor executor = getTaskExecutor();
    for (ApplicationListener<?> listener : getApplicationListeners(event, type)) {   // ★ 取所有匹配的
        if (executor != null && listener.supportsAsyncExecution()) {
            executor.execute(() -> invokeListener(listener, event));
        }
        else {
            invokeListener(listener, event);                                          // ★ 回调
        }
    }
}
```

```java
// ④ 回调的终点（SimpleApplicationEventMulticaster:183-186）
private void doInvokeListener(ApplicationListener listener, ApplicationEvent event) {
    try {
        listener.onApplicationEvent(event);      // ★ 就是它
    }
    ...
}
```

```java
// ⑤ 监听器怎么进的列表：启动时按 bean 登记（AbstractApplicationContext:910-921）
protected void registerListeners() {
    // Register statically specified listeners first.
    for (ApplicationListener<?> listener : getApplicationListeners()) {
        getApplicationEventMulticaster().addApplicationListener(listener);
    }
    // 把所有 ApplicationListener 类型的 bean 按名字登记（懒加载）
    String[] listenerBeanNames = getBeanNamesForType(ApplicationListener.class, true, false);
    for (String listenerBeanName : listenerBeanNames) {
        getApplicationEventMulticaster().addApplicationListenerBean(listenerBeanName);
    }
    ...
}
```

```java
// ⑥ 广播器从哪来：context 启动时创建并注册为单例（AbstractApplicationContext:849-860）
protected void initApplicationEventMulticaster() {
    ConfigurableListableBeanFactory beanFactory = getBeanFactory();
    if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
        this.applicationEventMulticaster =
                beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
    }
    else {
        this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory);
        beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, this.applicationEventMulticaster);
    }
}
```

> **结论**：模式没变（还是 Observer），只是"**持有者**"被独立出来了：
> **业务发布者 →（交给）→ context →（转发）→ 广播器（持有列表）→ 回调监听者**。
> 业务代码两头都不持有，只认识"消息"。

**追问 2：`CachingRouteLocator` 怎么又是发布者、又是监听者？**

是的，**两个角色都在**，但**不是一个回路**：

| 角色 | 收/发 | 事件类型 | 干什么 |
|---|---|---|---|
| **监听者** | 收 | `RefreshRoutesEvent` | 重编译 + 更新缓存 |
| **发布者** | 发 | **`RefreshRoutesResultEvent`**（**另一个类！**） | 通知"刷完了/刷失败了" |

**不会死循环**：它监听 `RefreshRoutesEvent`、发布 `RefreshRoutesResultEvent` —— **两个不同的事件类型**，没有人把后者变回前者。

`RefreshRoutesResultEvent` 定义（仓库 `event/RefreshRoutesResultEvent.java`）：

```java
public class RefreshRoutesResultEvent extends ApplicationEvent {
    private Throwable throwable;
    public RefreshRoutesResultEvent(Object source, Throwable throwable) {
        super(source);
        this.throwable = throwable;
    }
    public RefreshRoutesResultEvent(Object source) { super(source); }
    public Throwable getThrowable() { return throwable; }
    public boolean isSuccess() { return throwable == null; }
}
```

**谁在听它？** 全仓库（非测试）**没有监听者** —— 它是**给外部/监控的扩展点**；测试里 `CachingRouteLocatorTests` 用它来"等刷新完成"（因为刷新是异步的）。

> 这是常见模式：**"完成通知事件"** —— 干完活再发一条"结果消息"，谁想观测谁监听。

---

## 17.5 C 组 / D 组：匹配与执行（实测数字 → 说明什么）

| 组 | 实测 | **这些数字说明什么**（比数字重要） |
|---|---|---|
| **C 匹配** | `routePredicateHandlerMapping` → `RoutePredicateHandlerMapping`<br>**15 个** `*RoutePredicateFactory` | **L04 的"官方 14 种谓词"落在这里 = 14 个内置 bean**；<br>第 15 个是 ⭐ **我们自定义的 `demoHeader`（L15）** —— **自定义的和内置的在装配期是平等的 bean**<br>→ **你 yml 里能写哪些谓词，取决于装配了哪些工厂 bean**；请求时**怎么匹配**？**见 17.5.1** |
| **D 执行** | `filteringWebHandler` → `FilteringWebHandler`<br>**33 个** `*GatewayFilterFactory` | **= 32 个内置 + 1 个自定义（`demoHeader`）**。<br>⭐ **一个自洽证据**：L14 之前我们实测过是 **32**，加了自定义工厂正好变 **33** ✅<br>→ **怎么执行**？**见 17.5.2**；为什么内置只有 32（文档 35 页）？**见 17.5.4** |
| | GlobalFilter 一批：`routeToRequestUrlFilter` / `routingFilter`(=`NettyRoutingFilter`) / `nettyWriteResponseFilter` / `gatewayLoadBalancerClientFilter` / `adaptCachedBodyGlobalFilter` / `removeCachedBodyFilter` … | **L08 的 `/actuator/gateway/globalfilters` 就是列它们**（名字+order）<br>→ 那张 order 表的**来源就是这里装配的 bean** |

> **一个可以马上用的推论**：你想"加一个谓词/过滤器"，本质上不是"写代码"，
> 而是**往这两个 bean 清单里加一个 bean** —— 这正是 L14/L15 做的事 ✅

### 17.5.0 C/D 组的"接口"从哪来？—— 来自 WebFlux 的两个插头

**先破一个容易有的错觉**：A 组、B 组都是"**网关自己定义接口** + 一族实现"（有 `@Primary`、多个候选）；
但 C/D 组**没有网关自定义的接口** —— 它们是"**实现 WebFlux 的接口**"（网关这边各只有一个实现）：

| 组 | 接口 | 谁定义的接口 | 网关的类 | 为什么这样 |
|---|---|---|---|---|
| A | `RouteDefinitionLocator` | **网关** | Properties / InMemory / Composite | 是网关的**扩展点**（Nacos/Actuator/Java DSL 都要实现它）→ 需要"家族" |
| B | `RouteLocator` | **网关** | RouteDefinition / Composite / Caching | 要可装饰、可替换 → 需要"家族" |
| C | **`HandlerMapping`** | **WebFlux** | `RoutePredicateHandlerMapping` | 接入 WebFlux 的**实现**，一个就够 |
| D | **`WebHandler`** | **WebFlux** | `FilteringWebHandler` | 同上 |

**证据一：整个 `handler` 包只有这 2 个类**（其余是 `predicate/` 子包）：

```
org.springframework.cloud.gateway.handler/
├── AsyncPredicate.java
├── FilteringWebHandler.java          ← D 组的类
├── RoutePredicateHandlerMapping.java ← C 组的类
└── predicate/                        ← 15 个谓词工厂
```

**证据二：两个"extends / implements"**：

```java
// C：extends WebFlux 自带的抽象基类
import org.springframework.web.reactive.handler.AbstractHandlerMapping;

public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {
```

```java
// D：implements WebFlux 的接口
public class FilteringWebHandler implements WebHandler, ApplicationListener<RefreshRoutesEvent> {
```

**证据三：两个接口本体都只有一个方法**（所以"一个实现"就够）：

```java
package org.springframework.web.reactive;
public interface HandlerMapping {
    Mono<Object> getHandler(ServerWebExchange exchange);   // ← C 组实现的就是它
}
```

```java
package org.springframework.web.server;
public interface WebHandler {
    Mono<Void> handle(ServerWebExchange exchange);         // ← D 组实现的就是它
}
```

> **为什么这样分工**：网关真正需要扩展的是"**谓词**"和"**过滤器**"（所以有 15/33 个工厂家族）；
> 而"匹配器 / 执行器"这两个角色**各一个实现就够** —— 扩展点不在"换 HandlerMapping"，而在"加谓词 / 加过滤器"。
> （呼应 17.2 的纪律：官方四类接口 = `RouteDefinitionLocator` / `RouteLocator` / `HandlerMapping` / `WebHandler` —— **后两个是 WebFlux 的**。）

**一个漂亮的对称（两次"排序 + `.next()`"）**：

```
【WebFlux 问 HandlerMapping】（DispatcherHandler.handle）
    Flux.fromIterable(handlerMappings)              ← 所有 HandlerMapping bean
        .concatMap(m -> m.getHandler(exchange))     ← 逐个问（列表已按 order 排序）
        .next()                                     ← 第一个应答的赢 → C 组（order=1）
                 │
                 ▼
【C 组问 Route】（17.5.1）
    routeLocator.getRoutes()                        ← 所有路由（已按 order 排序）
        .filterWhen(route -> route.getPredicate().apply(exchange))  ← 逐条试
        .next()                                     ← 第一条匹配的赢
```

WebFlux 侧的源码（`DispatcherHandler.handle`）：

```java
public Mono<Void> handle(ServerWebExchange exchange) {
    if (this.handlerMappings == null) {
        return createNotFoundError();
    }
    ...
    return Flux.fromIterable(this.handlerMappings)
            .concatMap(mapping -> mapping.getHandler(exchange))
            .next()
            .switchIfEmpty(createNotFoundError())
            .onErrorResume(ex -> handleResultMono(exchange, Mono.error(ex)))
            .flatMap(handler -> handleRequestWith(exchange, handler));
}
```

排序证据（`DispatcherHandler.initStrategies`）：

```java
Map<String, HandlerMapping> mappingBeans = BeanFactoryUtils.beansOfTypeIncludingAncestors(
        context, HandlerMapping.class, true, false);
ArrayList<HandlerMapping> mappings = new ArrayList<>(mappingBeans.values());
AnnotationAwareOrderComparator.sort(mappings);        // ★ 按 order 排序
this.handlerMappings = Collections.unmodifiableList(mappings);
```

> 所以**同一个模式出现了两次**："**把候选排好序 → 逐个问 → 第一个应答者赢**" ——
> 网关在 WebFlux 面前是"应答者"，在路由面前是"提问者"。

**附：`HandlerMapping` 到底干啥？—— 和 Spring MVC 的对照**

**一句话**：`HandlerMapping` = "**请求 → 处理器**"的**解析器**（找谁来处理），**只负责"找"**：

```
一次请求进来（WebFlux 三大件）：

DispatcherHandler（前门 / 总调度，WebFlux 自带）
   │  ① 问一圈 HandlerMapping（按 order）："这请求归谁？"
   ▼
HandlerMapping.getHandler(exchange)          ← C 组在这里应答，返回 FilteringWebHandler
   │  ② 找到 handler 后，找能调它的 HandlerAdapter
   ▼
HandlerAdapter.supports(handler) → handle(exchange, handler)
   │     （支持 WebHandler 的是 SimpleHandlerAdapter → 调 FilteringWebHandler.handle）
   ▼
HandlerResultHandler.handleResult(...)       ← ③ 把结果写成 HTTP 响应
```

| 组件 | 接口（就一两个方法） | 职责 | 网关里的实现 |
|---|---|---|---|
| HandlerMapping | `Mono<Object> getHandler(exchange)` | **找**谁来处理 | `RoutePredicateHandlerMapping`（C 组） |
| HandlerAdapter | `supports(handler)` / `Mono<HandlerResult> handle(exchange, handler)` | **调** handler | `SimpleHandlerAdapter`（见下） |
| HandlerResultHandler | `supports(result)` / `Mono<Void> handleResult(...)` | **写**响应 | （`ResponseBodyResultHandler` 等，WebFlux 自带） |

**handler 到底是什么？（接口为什么返回 `Object`）**

"handler" 就是**一个能处理请求的对象**，但**它的类型因 HandlerMapping 而异**：

| 场景 | handler 具体是什么 | 每个请求一样吗 |
|---|---|---|
| MVC `@Controller` | **`HandlerMethod`**（= **bean 实例 + `Method`** 的封装 —— "**哪个对象的哪个方法**"） | ❌ 每个 URL 不同 |
| WebFlux `@Controller` | `HandlerMethod`（reactive 包，同名不同包） | ❌ |
| WebFlux 函数式路由 | `HandlerFunction<?>`（本来就是个函数） | ❌ |
| 静态资源 | `ResourceWebHandler`（一个 WebHandler） | ✅ |
| **网关（C 组）** | **`FilteringWebHandler` —— 单例，所有请求都是同一个对象！** | ✅ |

```java
// HandlerMethod 的字段（spring-web）：就是"bean + method"的封装
public class HandlerMethod extends AnnotatedMethod {
    private final Object bean;              // ← 哪个对象（如 OrderController 实例）
    private final BeanFactory beanFactory;
}
// 父类 AnnotatedMethod（spring-core）里：
private final Method method;                // ← 哪个方法（如 getOrder(Long)）
```

接口返回 `Object` 的原因：**handler 类型五花八门** —— 由 `HandlerAdapter` 用 `supports()` 认领 + 强转调用（见下面 `SimpleHandlerAdapter`）。

**网关的 handler 直给答案**（没有 `@Controller`、没有 `HandlerMethod` —— 就是**一个单例对象**）：

```
① Spring 启动：造出【唯一一个】FilteringWebHandler bean
   @Bean
   public FilteringWebHandler filteringWebHandler(List<GlobalFilter> globalFilters, GatewayProperties properties) {
       return new FilteringWebHandler(globalFilters, properties.isRouteFilterCacheEnabled());
   }

② C 组的构造函数把它存进字段（同一个单例引用）
   @Bean
   public RoutePredicateHandlerMapping routePredicateHandlerMapping(FilteringWebHandler webHandler,
           RouteLocator routeLocator, GlobalCorsProperties globalCorsProperties, Environment environment) {
       return new RoutePredicateHandlerMapping(webHandler, routeLocator, globalCorsProperties, environment);
   }
   // C 组内部：
   private final FilteringWebHandler webHandler;

③ 每个请求匹配成功后：Route 放 exchange（数据）、return webHandler（handler）—— 永远同一个对象
   .map((Function<Route, ?>) r -> {
       exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);   // ← 每请求的差异（数据）
       return webHandler;                                     // ← 永远同一个 handler（对象）
   })

④ DispatcherHandler 拿到它 → SimpleHandlerAdapter.supports()=true → webHandler.handle(exchange) → D 组拼链执行
```

> **一句话**：**网关的 handler = "负责拼过滤器链、执行转发的那一个单例对象"**（`FilteringWebHandler`）；
> **"这次走哪条路由"不是 handler，是 exchange 里的数据**（`GATEWAY_ROUTE_ATTR`）—— `Route` ≠ handler，别混。
> 对照 MVC："哪个 Controller 的哪个方法"是 handler **本身**（每请求不同）；网关"每请求不同"的部分**全在 exchange 里**。

> **边界（不是所有请求都会拿到它）**：
> ① **不匹配任何路由**的请求 → C 组返回空 → 不由网关处理（名单上其他 mapping 接走，或 404）；
> ② **management 端口**（与 server 端口不同时）的请求 → 直接返回空（构造里 `managementPortType` 判断）。
> 只有"**走到网关映射 + 匹配上路由**"的请求，handler 才是这个单例。

> **"差异数据"具体是什么**（= 命中的那个 `Route` 对象，实测 `toString`）：
>
> ```
> Route{id='demo-provider', uri=http://localhost:8081, order=0,
>       predicate=Paths: [/provider/**], match trailing slash: true,
>       gatewayFilters=[[[StripPrefix parts = 1], order = 1],
>                       [[AddRequestHeader X-Demo-Route = 'demo-provider'], order = 2],
>                       [[AddResponseHeader X-Demo-Gateway-Version = '1.0'], order = 3],
>                       [DemoHeader..., order = 4]],
>       metadata={}}
> ```
>
> | 字段 | 谁在用 |
> |---|---|
> | `id` | metrics/观测标签（`GatewayRouteTagsProvider`）、限流 key、日志 |
> | `uri` | **算转发目标**：`RouteToRequestUrlFilter` 拿 `route.getUri()` 拼目标 URL（`lb://` 再交给负载均衡过滤器） |
> | `gatewayFilters`（4 条，已编译） | **D 组拼链**：`route.getFilters()` 合并进链 |
> | `metadata` | 路由级 CORS 等 |
>
> ```java
> // RouteToRequestUrlFilter:64-71（下游怎么消费这个"差异数据"）
> Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
> ...
> URI routeUri = route.getUri();          // ← 用它算目标地址
> ```

> ★ **一个关键推论（网关 vs MVC 最容易忽略的区别）**：
> MVC 里"**handler 本身就携带了差异**"（每个 URL 注册一个不同的 `HandlerMethod`）；
> 网关里 handler **永远是同一个单例** `FilteringWebHandler` —— 那"这次走哪条路由"的差异放哪了？
> **放在 exchange 的属性里**（`GATEWAY_ROUTE_ATTR`）→ 这就是 D 组必须从
> `exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR)` 取 Route 的原因（17.5.2）✅

**C→D 的"最后一公里"就藏在这**（`DispatcherHandler.handleRequestWith` + `SimpleHandlerAdapter`）：

```java
private Mono<Void> handleRequestWith(ServerWebExchange exchange, Object handler) {
    ...
    if (this.handlerAdapters != null) {
        for (HandlerAdapter adapter : this.handlerAdapters) {
            if (adapter.supports(handler)) {
                Mono<HandlerResult> resultMono = adapter.handle(exchange, handler);
                return handleResultMono(exchange, resultMono);
            }
        }
    }
    return Mono.error(new IllegalStateException("No HandlerAdapter: " + handler));
}
```

```java
public class SimpleHandlerAdapter implements HandlerAdapter {
    @Override
    public boolean supports(Object handler) {
        return WebHandler.class.isAssignableFrom(handler.getClass());   // ★ FilteringWebHandler 是 WebHandler
    }
    @Override
    public Mono<HandlerResult> handle(ServerWebExchange exchange, Object handler) {
        WebHandler webHandler = (WebHandler) handler;
        Mono<Void> mono = webHandler.handle(exchange);                  // ★ 调 D 组的 handle()
        return mono.then(Mono.empty());
    }
}
```

**和 Spring MVC 的对照**（你记得没错：**同名、不同包**）：

| | Spring MVC | Spring WebFlux |
|---|---|---|
| 接口全名 | `org.springframework.web.**servlet**.HandlerMapping` | `org.springframework.web.**reactive**.HandlerMapping` |
| 前端控制器 | `DispatcherServlet` | `DispatcherHandler` |
| 请求对象 | `HttpServletRequest` | `ServerWebExchange` |
| 返回值 | `HandlerExecutionChain`（同步） | `Mono<Object>`（**异步**，可空） |
| 典型实现 | `RequestMappingHandlerMapping`（@Controller） | `RequestMappingHandlerMapping`（同名不同包）/ `RouterFunctionMapping` / **`RoutePredicateHandlerMapping`** |
| 网关用哪套 | ❌（不能跑在 Servlet 容器，L01/L02） | ✅ |

**循环逻辑一模一样**（同步版 vs 反应式版）：

```java
// MVC：DispatcherServlet.getHandler
for (HandlerMapping mapping : this.handlerMappings) {
    HandlerExecutionChain handler = mapping.getHandler(request);
    if (handler != null) {
        return handler;
    }
}
return null;
```

```java
// WebFlux：DispatcherHandler.handle
return Flux.fromIterable(this.handlerMappings)
        .concatMap(mapping -> mapping.getHandler(exchange))
        .next()
        .switchIfEmpty(createNotFoundError())
        ...
```

**你记的 MVC"注册"机制完全正确** —— 补全整条链（`AbstractHandlerMethodMapping`，spring-webmvc）：

```java
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {
```

```java
// ① 启动时（InitializingBean）：afterPropertiesSet → 扫描注册
@Override
public void afterPropertiesSet() {
    initHandlerMethods();
}
protected void initHandlerMethods() {
    for (String beanName : getCandidateBeanNames()) {          // ② 所有 bean（getBeanNamesForType(Object.class)）
        if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
            processCandidateBean(beanName);                    // ③ 逐个过筛
        }
    }
    handlerMethodsInitialized(getHandlerMethods());
}
```

```java
protected void processCandidateBean(String beanName) {
    Class<?> beanType = null;
    try {
        beanType = obtainApplicationContext().getType(beanName);
    }
    ...
    if (beanType != null && isHandler(beanType)) {             // ④ 带 @Controller 的类
        detectHandlerMethods(beanName);                        // ⑤ 扫这个类的所有方法
    }
}
// RequestMappingHandlerMapping 的实现：
protected boolean isHandler(Class<?> beanType) {
    return AnnotatedElementUtils.hasAnnotation(beanType, Controller.class);
}
```

```java
protected void detectHandlerMethods(Object handler) {
    ...
    Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
            (MethodIntrospector.MetadataLookup<T>) method -> {
                ...
                return getMappingForMethod(method, userType);  // ⑥ 读 @RequestMapping → RequestMappingInfo
            });
    ...
    methods.forEach((method, mapping) -> {
        Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
        registerHandlerMethod(handler, invocableMethod, mapping);   // ⑦ 注册
    });
}
```

```java
// ⑧ 路由表真身（MappingRegistry）：
class MappingRegistry {
    private final Map<T, MappingRegistration<T>> registry = new HashMap<>();         // mapping → 注册项（含 HandlerMethod）
    private final MultiValueMap<String, T> pathLookup = new LinkedMultiValueMap<>(); // 路径 → mapping（按路径查）
    ...
    public void register(T mapping, Object handler, Method method) {
        HandlerMethod handlerMethod = createHandlerMethod(handler, method);
        ...
        this.registry.put(mapping,
                new MappingRegistration<>(mapping, handlerMethod, directPaths, name, corsConfig != null));
    }
}
```

**运行时**：`getHandlerInternal(request)` → 按路径查表（`lookupHandlerMethod` → `getMappingsByDirectPath`）→ 拿到 `HandlerMethod`：

```java
protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
    String lookupPath = initLookupPath(request);
    this.mappingRegistry.acquireReadLock();
    try {
        HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
        return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null);
    }
    ...
}
```

然后由 `AbstractHandlerMapping.getHandler` 把它包成 **`HandlerExecutionChain`**（加上拦截器链）返回：

```java
public final HandlerExecutionChain getHandler(HttpServletRequest request) throws Exception {
    Object handler = getHandlerInternal(request);
    if (handler == null) {
        handler = getDefaultHandler();
    }
    if (handler == null) {
        return null;
    }
    ...
}
```

**所以"两种风格"的对照是**：

| | **注解式**（MVC / WebFlux 的 `@Controller`） | **网关式**（C 组） |
|---|---|---|
| 路由表何时建 | **启动时**（InitializingBean 扫全 bean → `@Controller` 的方法进 `registry`） | **不建表** —— 路由来自 B 组（`RouteLocator`），**请求时现取** |
| 为什么 | 注解是静态的，建表后查得快 | 路由会变（yml/Actuator/Nacos + refresh）→ 现算最省心 |
| 怎么找 | 按 URL 查 `registry` / `pathLookup` | 逐条跑谓词（`filterWhen` + `.next()`） |
| **handler** | **每个 URL 一个 `HandlerMethod`**（差异就在 handler 里） | **固定一个单例 `FilteringWebHandler`**（差异在 exchange 属性） |

> **值不值得深挖**：Gateway 视角到 17.5.0 为止；"DispatcherHandler + 三大件"是 WebFlux 主干（要深挖就开 WebFlux 源码线）。
> MVC 版只需记住：**同名不同包、阻塞 vs 反应式**。

### 17.5.1 ★ C 组怎么匹配：一次请求怎么选中路由（`lookupRoute`）

**它是什么**：WebFlux 处理请求时，会**按顺序问一堆 HandlerMapping：这个请求归谁？** ——
`RoutePredicateHandlerMapping`（`AbstractHandlerMapping` 的子类，**order=1**）就是网关交出的答卷：

```java
public RoutePredicateHandlerMapping(FilteringWebHandler webHandler, RouteLocator routeLocator,
        GlobalCorsProperties globalCorsProperties, Environment environment) {
    this.webHandler = webHandler;
    this.routeLocator = routeLocator;
    this.managementPort = getPortProperty(environment, "management.server.");
    this.managementPortType = getManagementPortType(environment);
    setOrder(environment.getProperty(GatewayProperties.PREFIX + ".handler-mapping.order", Integer.class, 1));
    setCorsConfigurations(globalCorsProperties.getCorsConfigurations());   // ← CORS 初值（L07）
}
```

**四个参数分别干什么**（不然这段构造函数就是"天书"）：

| 参数 | 来源 bean | 构造里干什么 | 最终用在哪 |
|---|---|---|---|
| `FilteringWebHandler webHandler` | `filteringWebHandler`（D 组那个**单例**） | `this.webHandler = webHandler` | 匹配成功后 `return webHandler` —— **它就是"handler"本身** |
| `RouteLocator routeLocator` | `@Primary` 的 `RouteLocator` = `CachingRouteLocator`（B 组） | `this.routeLocator = routeLocator` | `lookupRoute()` 里 `getRoutes()` 拿路由**逐条跑谓词** |
| `GlobalCorsProperties globalCorsProperties` | `globalCorsProperties`（绑 yml `spring.cloud.gateway.globalcors.*`） | **不存字段**：直接灌进父类 `setCorsConfigurations(...)` | CORS **预检请求**（OPTIONS）走它；刷新时被 `CorsGatewayFilterApplicationListener` 重灌（含路由级 `cors` metadata） |
| `Environment environment` | Spring 环境（自动注入） | 读 3 个配置（见下） | ① management 端口判断 ② order |

`Environment` 在构造里被读的 3 个配置：

| # | 读什么 | 存到哪 | 干什么用 |
|---|---|---|---|
| ① | `management.server.port` | `this.managementPort` | 和 `server.port` 一起判断"端口类型" |
| ② | `server.port` | `this.managementPortType`（`SAME`/`DISABLED`/`DIFFERENT`） | `getHandlerInternal` 开头：**DIFFERENT 时，management 端口收到的请求直接返回空**（交给 Actuator 自己的端点，不走网关路由） |
| ③ | `spring.cloud.gateway.handler-mapping.order`（默认 **1**） | `setOrder(...)`（父类字段） | `DispatcherHandler` 按 order 排序逐个问 HandlerMapping → 决定"网关排第几" |

```java
private ManagementPortType getManagementPortType(Environment environment) {
    Integer serverPort = getPortProperty(environment, "server.");
    if (this.managementPort != null && this.managementPort < 0) {
        return DISABLED;
    }
    return ((this.managementPort == null || (serverPort == null && this.managementPort.equals(8080))
            || (this.managementPort != 0 && this.managementPort.equals(serverPort))) ? SAME : DIFFERENT);
}
private static Integer getPortProperty(Environment environment, String prefix) {
    return environment.getProperty(prefix + "port", Integer.class);
}
```

**具体例子**：

```
server.port=8080，management.server.port 不配   → SAME      → 8080 的所有请求都进网关路由
server.port=8080，management.server.port=9090  → DIFFERENT → 9090 的 /actuator/** 由 Actuator 端点处理（C 组返回空）
                                                             8080 的请求照常走网关
```

**核心逻辑（3 步，就这些）**：

```java
// ① getHandlerInternal 的"净核心"（去掉工程细节后）
protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
    return lookupRoute(exchange)                     // 找
            .map(route -> {
                exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);   // 记
                return webHandler;                                     // 交
            });
}

// ② lookupRoute 的"净核心"
protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
    return this.routeLocator.getRoutes()                                   // 路由来自 B 组
            .filterWhen(route -> route.getPredicate().apply(exchange))     // 逐条跑谓词
            .next();                                                       // 第一条匹配即停
}
```

**逐行讲解**（跟着 `GET /provider/hello` 走一遍；假设 B 组缓存 6 条路由、第 1 条 `demo-provider` 匹配）：

**先贴调用点：`getHandler` 到底在哪被调**（它不在 C 组代码里！）：

```java
// DispatcherHandler.handle（WebFlux）:149-152
return Flux.fromIterable(this.handlerMappings)
        .concatMap(mapping -> mapping.getHandler(exchange))     // ★ 就是这里：逐个 mapping 调 getHandler
        .next()
        .switchIfEmpty(createNotFoundError())
```
（`handlePreFlight` 里也有一处同样的调用，:217-218）

**C 组重写了哪些方法**（注意：**没有 `getHandler`**）：

```
构造 / getHandlerInternal / getCorsConfiguration / lookupRoute / validateRoute / getSimpleName
```

**完整链条（带行号）**：

```
DispatcherHandler.handle:150
   └─ mapping.getHandler(exchange)                ① 调【接口】方法（mapping 静态类型 = HandlerMapping 接口）
        └─ AbstractHandlerMapping.getHandler:186  ② 父类【模板方法】（C 组没重写 → 继承到的）
             ├─ getHandlerInternal:79             ③ ★ 回调【C 组重写】的"找"（净核心①）
             │       └─ lookupRoute:130           ④ 净核心②
             └─ CORS 处理 → return handler → 交还 DispatcherHandler
```

```java
// 父类里把"找"声明成抽象方法 —— 逼子类实现：
protected abstract Mono<?> getHandlerInternal(ServerWebExchange exchange);
```

> 为什么 C 组没写 `getHandler` 却能被调到？—— 父类定了骨架（"先找、再包 CORS"），
> 把"找"声明成 **abstract** 留给子类；运行时是"**框架调你**"：
> `mapping.getHandler` → 父类模板方法 → 你的 `getHandlerInternal` —— **模板方法 + 控制反转**。

**⚠️ 两个"找"，别混**（"网关只有一个实现" ≠ "名单上只有一个"）：

| 层级 | 谁在找 | **找什么** | 候选有几个 | 判定方式 |
|---|---|---|---|---|
| **第 1 层** | `DispatcherHandler` | **哪个 HandlerMapping 管这个请求** | **实测 8 个**（见下） | 逐个 `getHandler()`，返回非空者赢 |
| **第 2 层** | C 组 `lookupRoute` | **哪条 `Route` 匹配** | 路由条数（demo 实测 6 条） | 逐条谓词，第一条 `true` 赢 |

> "网关只有一个实现" 指的是：**网关对 `HandlerMapping` 接口只贡献 1 个**（`RoutePredicateHandlerMapping`）；
> 但 `DispatcherHandler` 的名单是**整个应用**的 —— demo 实测 **8 个**：

| # | bean | 类型 | 管什么 |
|---|---|---|---|
| 1 | `requestMappingHandlerMapping` | `RequestMappingHandlerMapping` | `@Controller` 注解路由（WebFlux 自带） |
| 2 | **`routePredicateHandlerMapping`** | **`RoutePredicateHandlerMapping`** | **网关路由（本课主角，order=1，很靠前）** |
| 3 | `routerFunctionMapping` | `RouterFunctionMapping` | 函数式路由 |
| 4 | `welcomePageRouterFunctionMapping` | `RouterFunctionMapping` | 欢迎页（index.html） |
| 5 | `resourceHandlerMapping` | `SimpleUrlHandlerMapping` | 静态资源 |
| 6 | `webEndpointReactiveHandlerMapping` | `WebFluxEndpointHandlerMapping` | Actuator Web 端点（`/actuator/**`） |
| 7 | `controllerEndpointHandlerMapping` | `ControllerEndpointHandlerMapping` | Actuator 控制端点 |
| 8 | `healthEndpointWebFluxHandlerMapping` | `AdditionalHealthEndpointPathsWebFluxHandlerMapping` | Actuator 健康路径 |

> 每个 mapping 对"不是我的"请求**返回空**（如没有 `@Controller` 时 `requestMappingHandlerMapping` 对每个请求都返回空）—— 所以必须逐个问。
>
> 🕳️ **实测口径的坑**：用"类名含 `HandlerMapping`"过滤 bean 会**漏掉** `RouterFunctionMapping`
> （名字里没有"HandlerMapping"）、**误收** `SimpleUrlHandlerMappingGlobalCorsAutoConfiguration`
> （名字里有，但它是给资源映射补 CORS 的自动配置类，见 17.5.3 第 6 行）—— 列清单时小心。

| 请求 | 第 1 层"谁接" | 第 2 层"哪条" |
|---|---|---|
| `/actuator/health` | `healthEndpointWebFluxHandlerMapping` | ——（不进网关） |
| `/actuator/beans` | `webEndpointReactiveHandlerMapping` | —— |
| **`/provider/hello`** | **`routePredicateHandlerMapping`（网关，order=1）** | 逐条谓词 → `demo-provider` |
| `/not-exist` | 8 个都说"不是我的" | → **404** |





**先看"它是被谁调的"**（模板方法 —— 这解释了为什么 C 组只需要实现"找"）：

```java
// 父类 AbstractHandlerMapping（WebFlux）：模板方法
public Mono<Object> getHandler(ServerWebExchange exchange) {
    return getHandlerInternal(exchange).map(handler -> {
        ...
        if (hasCorsConfigurationSource(handler) || CorsUtils.isPreFlightRequest(request)) {
            ... // CORS 处理（父类兜底 —— 就是构造里 setCorsConfigurations 的用武之地）
        }
        return handler;
    });
}
```

| 行 | 它干了什么（人话） | 为什么这样写 |
|---|---|---|
| `lookupRoute(exchange)` | 把"找"委托出去 | "找"独立成一段，便于复用/覆盖 |
| `this.routeLocator.getRoutes()` | 向 **B 组**拿走全部**编译好的** Route（谓词已是对象，不是 yml 字符串） | 平时读缓存 → 每请求拿得起（17.4） |
| `.filterWhen(route -> route.getPredicate().apply(exchange))` | **逐条按顺序**问谓词："这个请求匹配你吗？"（返回 `Mono<Boolean>`） | 谓词可能**异步**（如 `ReadBody` 要读请求体）→ 用 `filterWhen` 而不是普通 `filter` |
| `.next()` | **只取第一条**匹配的，并**取消上游**（第 2~6 条不再测试） | "第一条匹配即停" → **order 小者先赢**（B 组 fetch 时已排序） |
| `.map(route -> { ... })` | 等 Route 出来后，做"**记**"和"**交**" | `map` = 对 Mono 的结果做变换（**输入 Route → 输出 handler**） |
| `put(GATEWAY_ROUTE_ATTR, route)` | **把选中的 Route 塞进 exchange 的属性袋** | handler 是**单例**、携带不了"这次是哪条路由" → 差异必须放 exchange |
| `return webHandler` | 交出**固定的 D 组单例**当处理器 | "数据进、处理者出" —— 这就是 C→D 的交接 |
| （全不匹配） | 流为空 → `map` 不执行 → 空 → `DispatcherHandler.switchIfEmpty` → **404** | |

> 具体走一遍：`demo-provider` 的路径谓词收到 exchange → 看 `/provider/hello` 是否匹配 `/provider/**` → `true` ✅
> → `.next()` 取它、不再问其余 5 条 → 属性袋记下它 → 返回 `webHandler` → 交给 DispatcherHandler。

> **读源码的方法**：先问"这段代码的**交付物**是什么" —— C 组的交付物只有两件：
> **① 选中的 `Route`（放进 exchange）**、**② handler 对象（`return webHandler`）**。
> 其余全是围绕交付物的工程处理 👇

**完整源码里"其它行"是什么**（不是核心，逐个一句话）：

| 完整源码里的这段 | 是什么 | 分类 |
|---|---|---|
| `managementPortType == DIFFERENT && … return Mono.empty()` | Actuator 端口上的请求不归网关管 | 工程细节 |
| `put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName())` | 记"哪个 HandlerMapping 处理的"（观测用） | 记账 |
| `Mono.deferContextual(... GATEWAY_REACTOR_CONTEXT_ATTR ...)` | 把 Reactor Context 存档（tracing 用） | 记账 |
| `put/remove(GATEWAY_PREDICATE_ROUTE_ATTR, ...)` | 记"正在试哪条路由"（谓词报错时定位用） | 记账 |
| `try/catch + logger.error + Mono.just(false)` | 某条谓词炸了 → 当"不匹配"继续，不拖垮整体 | 健壮性 |
| `logger.debug / logger.trace(...)` | 日志 | 噪音 |
| `switchIfEmpty(... clearCachedRequestBody ...)` | 没匹到时的收尾（404 交给 WebFlux） | 收尾 |
| `validateRoute(route, exchange)` | 空实现（留给子类扩展） | 扩展点 |

**四个关键点**：

| # | 关键点 | 说明 |
|---|---|---|
| ① | 路由哪来的 | `this.routeLocator.getRoutes()` = **B 组（`CachingRouteLocator`）** → C 组站在 B 组肩上（B 组缓存旧了，C 组就匹配旧路由） |
| ② | 怎么选 | 逐条 `route.getPredicate().apply(exchange)`（**编译好的 `AsyncPredicate`，请求时不查工厂**）→ `.next()` = **第一条匹配即取** |
| ③ | 顺序即优先级 | `filterWhen` 按**源顺序**逐个测试（Reactor 3.6 文档原话："Test publishers are generated and subscribed to **in sequence**"），B 组 `fetch()` 又已按 `order` 排序 → **order 小者先匹配** |
| ④ | 匹配上的产物 | `Route` 放进 `GATEWAY_ROUTE_ATTR`（exchange 属性）；**handler 返回 `FilteringWebHandler`**（D 组） |

**匹配不上** → `switchIfEmpty` → WebFlux 找不到 handler → **404**。
匹配是**每个请求**都做的 → 谓词实现要快。

> 🧪 自验：请求不存在的路径 → 404；`GATEWAY_ROUTE_ATTR` 这个属性后面被一堆组件读（D 组的过滤器、`NettyRoutingFilter`、观测打 tag ……）——**C 组的"匹配结果"是整条执行链的共享上下文**。

### 17.5.2 ★ D 组怎么执行：过滤器链怎么拼（`FilteringWebHandler`）

**它是什么**：C 组匹配成功后**返回的 handler**。它 `implements WebHandler` —— WebFlux 把 `ServerWebExchange` 交给它，它**拼一条过滤器链**跑完。
（**WebFlux 侧是谁调它？** → 17.5.0 附里的 `SimpleHandlerAdapter`）

**核心逻辑（3 件事）**：

```java
public Mono<Void> handle(ServerWebExchange exchange) {
    Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);  // ① 取 C 组放下的 Route（数据）
    List<GatewayFilter> combined = getCombinedFilters(route);         // ② [全局...] + [路由...] → 排序
    return new DefaultGatewayFilterChain(combined).filter(exchange);  // ③ 链式执行
}
```

**逐行讲解**：

- `exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR)` —— 从属性袋里**取出 C 组放下的 Route**；
  取不到直接抛异常（"required"是个**断言**：能进到 D 组，说明 C 组必然已放过）—— 这是**接 C 组的力**。
- `getCombinedFilters(route)` —— 把这次请求要过的过滤器**凑齐**：`[全局 12 条] + [route.getFilters()]` → 按 order 排序
  （全局的每次都有份；路由的是"这条路由专属" → 两类**合并成一条有序链**）。
- `new DefaultGatewayFilterChain(combined).filter(exchange)` —— 包成链并**开始跑**：
  链内 index 递归，每个 filter 拿到"下一棒"、自己决定何时调下一个 → **洋葱模型**（17.5.2 下面的 `DefaultGatewayFilterChain`）。

> 其余（`loadFilters` 包装、`routeFilterMap` 缓存、`onApplicationEvent` 监听刷新）= 工程细节，见下面的完整类。

```java
public class FilteringWebHandler implements WebHandler, ApplicationListener<RefreshRoutesEvent> {
    private final List<GatewayFilter> globalFilters;                                        // ★ 构造期定死
    private final ConcurrentHashMap<Route, List<GatewayFilter>> routeFilterMap = new ConcurrentHashMap();
    private final boolean routeFilterCacheEnabled;

    public FilteringWebHandler(List<GlobalFilter> globalFilters, boolean routeFilterCacheEnabled) {
        this.globalFilters = loadFilters(globalFilters);            // ★ 全局过滤器包装
        this.routeFilterCacheEnabled = routeFilterCacheEnabled;
    }

    private static List<GatewayFilter> loadFilters(List<GlobalFilter> filters) {
        return filters.stream().map(filter -> {
            GatewayFilterAdapter gatewayFilter = new GatewayFilterAdapter(filter);
            if (filter instanceof Ordered) {                        // 带 order → OrderedGatewayFilter
                int order = ((Ordered) filter).getOrder();
                return new OrderedGatewayFilter(gatewayFilter, order);
            }
            else {
                Order order = AnnotationUtils.findAnnotation(filter.getClass(), Order.class);
                if (order != null) {
                    return new OrderedGatewayFilter(gatewayFilter, order.value());
                }
            }
            return gatewayFilter;
        }).collect(Collectors.toList());
    }

    @Override
    public void onApplicationEvent(RefreshRoutesEvent event) {             // ← 它也是刷新监听者！
        if (this.routeFilterCacheEnabled) {
            routeFilterMap.clear();
        }
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange) {
        Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);   // ★ 从 C 组手里拿
        List<GatewayFilter> combined = getCombinedFilters(route);          // ★ 合并（见下）
        if (logger.isDebugEnabled()) {
            logger.debug("Sorted gatewayFilterFactories: " + combined);
        }
        return new DefaultGatewayFilterChain(combined).filter(exchange);   // ★ 执行
    }

    protected List<GatewayFilter> getCombinedFilters(Route route) {
        if (this.routeFilterCacheEnabled) {
            return routeFilterMap.computeIfAbsent(route, this::getAllFilters);
        }
        else {
            return getAllFilters(route);
        }
    }

    protected List<GatewayFilter> getAllFilters(Route route) {
        List<GatewayFilter> gatewayFilters = route.getFilters();
        List<GatewayFilter> combined = new ArrayList<>(this.globalFilters);
        combined.addAll(gatewayFilters);                                   // ★ 全局 + 路由
        AnnotationAwareOrderComparator.sort(combined);                     // ★ 按 order 排序
        return combined;
    }
}
```

**"洋葱模型"就藏在这个内部类里**（index 递归）：

```java
private static class DefaultGatewayFilterChain implements GatewayFilterChain {
    private final int index;
    private final List<GatewayFilter> filters;

    DefaultGatewayFilterChain(List<GatewayFilter> filters) {
        this.filters = filters;
        this.index = 0;
    }

    private DefaultGatewayFilterChain(DefaultGatewayFilterChain parent, int index) {
        this.filters = parent.getFilters();
        this.index = index;
    }

    public List<GatewayFilter> getFilters() {
        return filters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange) {
        return Mono.defer(() -> {
            if (this.index < filters.size()) {
                GatewayFilter filter = filters.get(this.index);
                DefaultGatewayFilterChain chain = new DefaultGatewayFilterChain(this, this.index + 1);
                return filter.filter(exchange, chain);     // ★ 每个 filter 拿到"下一棒"
            }
            else {
                return Mono.empty(); // complete            // ★ 链尾
            }
        });
    }
}
```

**四个关键点**：

| # | 关键点 | 说明 |
|---|---|---|
| ① | 全局过滤器哪来 | 构造参数 `List<GlobalFilter>` —— **就是 `/actuator/gateway/globalfilters` 列的那 12 条**（实测） |
| ② | 路由过滤器哪来 | `route.getFilters()` —— **编译期（B 组）用 33 个 `*GatewayFilterFactory` 造出来的** |
| ③ | 合并 + 排序 | `[全局...] + [路由...]` → `AnnotationAwareOrderComparator.sort` → **一条链**（order 全局生效） |
| ④ | 执行 | `DefaultGatewayFilterChain`：一层层 `filter(exchange, chain)` → **这就是 L10 的洋葱模型** |

**顺带两个点**：

- 它**也监听 `RefreshRoutesEvent`**（17.4.8 说"监听者不止一个"的原因）：因为它有个 per-route 合并缓存 `routeFilterMap`（`spring.cloud.gateway.route-filter-cache-enabled`，**默认 false**）—— 开了才缓存，刷新时清空。
- `/routes/{id}/combinedfilters` **不真合并**（源码留着 `// TODO: missing global filters`）—— 想验证"合并结果"别用它。

### 17.5.3 另外还有 10 个自动配置类 —— 以及"**怎么发现它们**"

**先学"怎么发现"**（这是个可迁移的手艺）：Spring Boot 3 的自动配置类**登记在一个清单文件里**，不用猜、不用扫包：

```bash
unzip -p spring-cloud-gateway-server-4.1.5-sources.jar \
  'META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports'
```

输出就是**全部自动配置类**（我们的 4.1.5 里是 **11 行**，我第一版只数了 10 —— 漏的那个在下面标了 ⭐）：

| # | 自动配置类 | 作用 | 你在哪课见过它 |
|---|---|---|---|
| 1 | **`GatewayAutoConfiguration`** | **主体**：四组组件（本文） | **L17** |
| 2 | `GatewayClassPathWarningAutoConfiguration` | **硬约束检查**：误引 `spring-boot-starter-web` 时启动告警 | L01/L02（"不能跑在 Servlet 容器"） |
| 3 | `discovery/GatewayDiscoveryClientAutoConfiguration` | **服务发现自动路由**（`discovery.locator.enabled`） | L13 / L44 |
| 4 | **`GatewayReactiveLoadBalancerClientAutoConfiguration`** | **有 SCLB 时**：装配 `ReactiveLoadBalancerClientFilter` | **L16**（`/globalfilters` 里 order=10150 那个） |
| 5 | **`GatewayNoLoadBalancerClientAutoConfiguration`** | **无 SCLB 时**：装配占位的 `NoLoadBalancerClientFilter` | **L16 的坑**（503 的标志） |
| 6 | **`SimpleUrlHandlerMappingGlobalCorsAutoConfiguration`** | **CORS 预检解药**（`add-to-simple-url-handler-mapping`） | **L07** |
| 7 | `GatewayMetricsAutoConfiguration` | 指标：`GatewayMetricsFilter` | L10（order 表里 order=0 那个） |
| 8 | `GatewayRedisAutoConfiguration` | Redis（限流 `RequestRateLimiter` 的依赖） | L36 |
| 9 | `GatewayResilience4JCircuitBreakerAutoConfiguration` | 熔断（Resilience4J） | L37 |
| 10 | `LocalResponseCacheAutoConfiguration` | 响应缓存 | L38 |
| **11** ⭐ | **`GatewayReactiveOAuth2AutoConfiguration`** | **OAuth2**（`TokenRelay` 过滤器） | **本课 17.5.4 的答案**：`tokenrelay` 为什么没装配 = 没引 OAuth2 |

**用法**：以后看到"某个过滤器/行为哪来的"，**先在那 11 个类里找** ——
比如 `NoLoadBalancerClientFilter` 就是 `GatewayNoLoadBalancerClientAutoConfiguration` 的产物。

> **顺带一个 Boot 3 的冷知识**：这个清单文件在 Boot 2.x 时代叫 `spring.factories`（我们现在还能在 jar 里看到它，是给老版本兼容用的），
> **3.x 起换成了 `AutoConfiguration.imports`**。两条都读过，面试聊"自动配置演进"就有料。

### 17.5.4 两组数字的对照（★ 别被"官方文档说 N 个"忽悠）

**"官方文档描述 35 页" ≠ "你的进程里装配 33 个"** —— 差集是**有意义**的（实测）：

> 口径说明：`docs/.../gatewayfilter-factories/` 目录共 38 个文件 = **35 个 `*-factory.adoc`**
> + 3 个非 factory 文档（`default-filters` / `fallback-headers` / `local-cache-response-filter`）。
> 我们按 **35 个 factory 页** 与实测的 33 个 bean 对比。

| 方向 | 具体 | **为什么**（条件是什么） |
|---|---|---|
| 文档有、**没装配**（4 个） | `requestratelimiter` | 需要 **Redis**（`GatewayRedisAutoConfiguration` 的条件之一） |
| | `circuitbreaker` | 需要**熔断实现**（Resilience4J，`GatewayResilience4JCircuitBreakerAutoConfiguration`） |
| | `tokenrelay` | 需要 **Spring Security OAuth2**（`TokenRelayConfiguration` 的 `@ConditionalOnClass({OAuth2AuthorizedClient, SecurityWebFilterChain, SecurityProperties})`；<br>配套的 11 号自动配置 `GatewayReactiveOAuth2AutoConfiguration` 也**因此没生效** ⭐） |
| | `jsontogrpc` | 需要 **gRPC** 依赖 |
| 实测有、文档页里没有（2 个） | **`demoHeader`**（L14 我们自己写的！） | 自定义的 |
| | `requestheadertorequesturi` | 文档页名不同（对我们的版本） |

> **看到 `demoHeader` 混在 33 个工厂里了吗** —— 这就是最好的证据：
> **你写的工厂（L14）和内置的工厂，在装配期是平等的一批 bean**（都被 `List<GatewayFilterFactory>` 收集）。
>
> **另外一条隐含信息**：`circuitbreaker` / `requestratelimiter` **没装配** ≠ "不能用" ——
> 而是**这个 demo 没引相应依赖**（这点在 L36/L37 做限流/熔断实验时要注意：**先加依赖**）。

### 17.5.5 不在四组里的"基础设施 bean"

91 个 `@Bean` 里，除了四组，还有一类**基础设施**（不用记，知道有即可）：
配置属性（`gatewayProperties` / `httpClientProperties` / `secureHeadersProperties`）、
类型转换器（`stringToZonedDateTimeConverter` / `keyValueConverter`）、
`HttpClientFactory`、`routeLocatorBuilder`（Java DSL 入口）、
Actuator 端点（`gatewayControllerEndpoint`）等。

---

## 17.6 怎么自己验证（一条命令）

```bash
# ① 暴露 beans 端点（yml 里 management.endpoints.web.exposure.include 加 beans；或启动时命令行覆盖）
java -jar demo-gateway.jar --management.endpoints.web.exposure.include=health,info,gateway,beans

# ② 抓四组
curl -s --noproxy '*' http://localhost:8080/actuator/beans > /tmp/beans.json
python3 - <<'PY'
import json
d=json.load(open("/tmp/beans.json")); beans={}
for k,v in d.get("contexts",{}).items(): beans.update(v.get("beans",{}))
print("bean 总数:", len(beans))
for n,b in sorted(beans.items()):
    t=b.get("type","").split(".")[-1]
    if t in ("CompositeRouteDefinitionLocator","PropertiesRouteDefinitionLocator",
             "InMemoryRouteDefinitionRepository","RouteDefinitionRouteLocator",
             "CachingRouteLocator","RoutePredicateHandlerMapping","FilteringWebHandler"):
        print(f"  {n:48s} → {t}")
PY
```

---

## 17.7 顺带：实测踩到的一个坑（网关注册自己 → 启动依赖 Nacos）

做本课实测时网关**启动失败**，日志根因：
```
Caused by: com.alibaba.nacos.api.exception.NacosException:
    Client not connected, current status:UNHEALTHY
  at ... NacosServiceRegistry.register ...
```
**原因**：我们给网关加了 `nacos-discovery` 且**没关自我注册** → 网关启动时要**先注册自己**，
那一刻 Nacos 的 **gRPC 连接没建起来** → 注册失败 → **网关启动失败**（连 Netty 都已绑定后才挂）。

**修复**（也是生产最佳实践）：
```yaml
spring:
  cloud:
    nacos:
      discovery:
        register-enabled: false     # ★ 网关不注册自己
```

**为什么是正确做法**（呼应 L16 之前讨论的"网关要不要接入注册中心"）：
| 能力 | 要不要 | 为什么 |
|---|---|---|
| **服务发现**（拉下游实例） | ✅ **要** | `lb://` 的基础 |
| **服务注册**（把自己注册给别人发现） | ❌ **不要** | 网关是**边缘组件**，调用方是外部客户端（走 DNS/SLB）→ **不需要被发现**；注册了反而**让启动依赖 Nacos 可用性** |

> ⚠️ **实测教训**：这个坑很隐蔽 —— 平时 Nacos 正常时一切安好，**Nacos 抖一下网关就起不来**。

---

## 17.8 掌握边界

| 内容 | 判断 |
|---|---|
| **四组组件**（A 来源 / B 模型 / C 匹配 / D 执行） | ✅ **必须记**（后面所有源码课的骨架） |
| **Spring Boot 自动装配最小包**（`.imports` + `@Conditional*` + 顺序 + `@ConfigurationProperties`） | ✅ **必须记**（17.2.5；读音 17.3~17.5 的前提） |
| **B 组装配链**（`CachingRouteLocator(CompositeRouteLocator(...))`） | ✅ **必须记**（能画图） |
| `@ConditionalOnMissingBean` / `@Primary` 的**可接管**含义 | ✅ 记 |
| "91 个 @Bean 归纳成四组"的**归纳能力** | ✅ 记（面试谈源码时的展开方式） |
| `@ConditionalOnMissingBean` 等条件的**逐条细节** | ⏭️ 用时查 |
| 91 个 bean 的**逐个**作用 | ⏭️ 按四组找，别逐个背 |

---

## 17.9 Self-Test

1. `GatewayAutoConfiguration` 的 91 个 `@Bean` 可以归纳成哪四组？各组的目标是什么？
2. A 组里 `routeDefinitionLocator(...)` 的参数为什么是 `List<RouteDefinitionLocator>`？`@Primary` 起什么作用？
3. 写出 B 组的装配链，并说明**每一层**的作用。
4. 你自己写的 `GatewayFilterFactory` 是在哪一步被收集进来的？
5. `CachingRouteLocator` 与"改了路由要 refresh"是什么关系？
6. 网关为什么不建议注册到 Nacos？我们的实测事故是什么？
7. `GatewayAutoConfiguration` 是**自动装配**还是**组件扫描**进来的？两者怎么区分？
8. `.imports` 清单和 `@Conditional*` 各自决定什么？举一个"清单里有但没装"的例子。
9. **`PropertiesRouteDefinitionLocator` 会解析 yml 吗？** 那 yml 是谁解析成 `RouteDefinition` 的？
10. `RouteDefinitionLocator` / `RouteDefinitionRepository` / `RouteDefinitionWriter` 三者什么区别？Actuator 动态加的路由存在哪？
11. `GatewayProperties` 里能绑的 yml 属性有哪些（怎么查）？`routes` 之外，`default-filters` 是谁在读？
12. **`InMemoryRouteDefinitionRepository` 的 Map 默认是空的吗？** yml 里的路由为什么不在里面？
13. 为什么 `PropertiesRouteDefinitionLocator` **只实现只读接口**？"yml 路由删不掉"是 bug 吗？
14. **"`RouteDefinition` 编译成 `Route`"具体做了什么？** 编译时"查工厂 + bind + apply"这一步，和你 L14/L15 写的哪个方法对上了？
15. `default-filters` 为什么对**所有路由**生效？（从"编译"的角度解释）
16. **`CompositeRouteDefinitionLocator` 自己没有 Map，为什么 Actuator 加的路由能"立刻"在配置态看到、但运行态要 refresh？**

### 17.9.1 参考答案

<details open>

**1. 四组**
- **A 来源**：`RouteDefinitionLocator` 家族（yml / 内存 / 服务发现 / … → `Composite` 合并）
- **B 模型**：`RouteLocator` 家族（定义 → Route；合并 + 缓存）
- **C 匹配**：`RoutePredicateHandlerMapping` + 谓词工厂
- **D 执行**：`FilteringWebHandler` + 过滤器工厂 + GlobalFilter（链装配/执行/转发）

**2. A 组的 `List` 与 `@Primary`**
参数是 `List<RouteDefinitionLocator>` → Spring 注入**容器里所有**该类型 bean →
**加来源只需注册 bean**；`@Primary` 让注入方**优先拿到"合并版"**（`CompositeRouteDefinitionLocator`）。

**3. B 组装配链**
`RouteDefinition → （RouteDefinitionRouteLocator 编译）→ Route → （CompositeRouteLocator 合并所有 RouteLocator）→ （CachingRouteLocator 缓存）`
—— 缓存是"运行态快照"，所以**改完必须 refresh**（L08/L30）。

**4. 你的工厂在哪被收集**
`routeDefinitionRouteLocator(..., List<GatewayFilterFactory>, List<RoutePredicateFactory>, ...)`
—— 你 `@Component` 的工厂就是被这个 `List` 收集进来的（再传给 `RouteDefinitionRouteLocator` 去"编译"路由）。

**5. `CachingRouteLocator` 与 refresh**
它就是运行态缓存；`RefreshRoutesEvent`（`POST /actuator/gateway/refresh`）触发它重建 →
**这就是"POST 路由后必须 refresh 才生效"的根因**。

**7. 自动装配 vs 组件扫描**
`GatewayAutoConfiguration` 是**自动装配**（登记在 jar 的 `.imports` 清单，由 `@EnableAutoConfiguration` 加载）；
组件扫描扫的是**你自己包的** `@Component`（如 `DemoGlobalFilter`）。
**区分**：框架提供的、你没写过 `@Component` 的 → 自动装配。

**8. `.imports` 与 `@Conditional*` 的分工**
- `.imports` = **候选名单**（有哪些自动配置类）
- `@Conditional*` = **谁能上场**（条件不满足就跳过）
例：`tokenrelay` 在清单/文档里有，但需要 Spring Security OAuth2 的类 → `@ConditionalOnClass` 不满足 → **没装配**。

**9. `PropertiesRouteDefinitionLocator` 不解析 yml**
它只做"把已绑好的 `List<RouteDefinition>` 转成 Flux + 过滤 `enabled=false`"。
**yml → 对象是 Spring Boot 的 `@ConfigurationProperties` 绑定**（`GatewayProperties`），不是这个类。

**10. 三个接口的区别**
- `RouteDefinitionLocator`：**只读**（`getRouteDefinitions()`）
- `RouteDefinitionWriter`：**只写**（`save`/`delete`）
- `RouteDefinitionRepository`：**读写都有**（前两者的结合）
Actuator 动态加的路由存在 **`InMemoryRouteDefinitionRepository` 的内存 Map** 里；
**yml 路由删不掉** = yml 走的是只读的 `PropertiesRouteDefinitionLocator`，不在那个 Map 里（L08 的 404 由此解释）。

**11. `GatewayProperties` 的可绑属性**
查 `META-INF/spring-configuration-metadata.json`：`spring.cloud.gateway.routes` / `default-filters` /
`streaming-media-types` / `fail-on-route-definition-error` / `enabled`（共 5 个）。
**`default-filters` 不是 A 组读的** —— 是 **B 组的 `RouteDefinitionRouteLocator`（:185）** 读的（A 组只读 `routes`）。

**12. 内存 Map 默认是空的**
✅ 空。**没有任何代码把 yml 拷进它** —— 唯一的写入者是 Actuator（`AbstractGatewayControllerEndpoint:242/259`）。
yml 路由在 **`GatewayProperties.routes`**（另一个来源），两者在 `Composite` 里合并后才"看起来在一起"。
反证：L08 实测 **DELETE yml 路由 → 404**（若在 Map 里应成功）。

**13. 只读接口是设计决定，不是 bug**
`RouteDefinitionLocator`（只读）/ `RouteDefinitionWriter`（只写）/ `RouteDefinitionRepository`（读写）——
**yml 来源只实现只读接口 → 类型系统层面就不允许写它**。
要动态改路由 → 用**可写的 Repository**（内存 / Redis（L49））或**自己实现一个接管**。

**14. "编译成 Route"**
把**声明式**翻译成**可执行对象**：
- 谓词：`{name: Path, args:{...}}` → 查 `PathRoutePredicateFactory` → `bind(args)` 得 Config → **`applyAsync(config)`** → `AsyncPredicate` 对象（多个用 `.and()` 串）
- 过滤器：`{name: StripPrefix, args:{...}}` → 查 `StripPrefixGatewayFilterFactory` → `bind(args)` → **`apply(config)`** → `GatewayFilter` 对象
- 组装：`Route.async(def).asyncPredicate(...).replaceFilters(...).build()`
**`apply()` 就是 L14/L15 你自己写的那个方法**（自定义的和内置的在同一个循环里被调用）。

**15. `default-filters` 为什么全局生效**
因为它在**编译每条路由时被 merge 进 filters 列表**（`getFilters()` 先加 default-filters，再加路由自己的，然后按 order 排序）
—— 不是"运行时逐条判断"，而是**每条路由的编译产物里都自带一份**。

**16. 为什么配置态实时、运行态要 refresh**
**Composite 是"视图"不是"仓库"**：它没有 Map，每次被调用都去**实时问** `delegates` 里的每个来源；
而 `InMemoryRouteDefinitionRepository.getRouteDefinitions()` 每次都读**它自己 Map 的当前值**
→ 所以 Actuator `POST`（Map.put）后，**下一次**问 Composite 就能看到（配置态实时）。
**运行态**则由 **`CachingRouteLocator`（有缓存）**提供 → 必须 `RefreshRoutesEvent`（`/refresh`）才重建 ✅

**6. 网关不注册 Nacos 的原因**
网关是**边缘组件**（调用方走 DNS/SLB），不需要被发现；注册自己会让**启动依赖 Nacos 可用性**。
**实测事故**：`Client not connected, current status:UNHEALTHY` → 网关启动失败。
修复：`spring.cloud.nacos.discovery.register-enabled: false`。

</details>

---

## 17.10 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「17.4 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
