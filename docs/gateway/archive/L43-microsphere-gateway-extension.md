# （归档）L43 microsphere-gateway 扩展实践：SCG 端点级路由 + polygateway 对照实现

> 📦 归档说明（2026-10-05）：L43 正文已重写为纯 polygateway 版本（`L43-polygateway-extension.md`）；本文件保留初版"microsphere 扩展四式 + 对照实现"内容备查，不再作为课程正文维护。

> 用途：回答"**怎么在不改 SCG 源码的前提下扩展网关**"、"**一个真实的网关扩展案例长什么样**"、"**参考它的思路自研一个能写进简历的版本**"
> 对应课程：**L43**（microsphere-gateway 扩展实践）
> 素材来源：训练营 `microsphere-analysis/16-microsphere-gateway-analysis/`（8 篇）+ 源码规划
> 口径：
> - 小马哥侧：microsphere-gateway **0.2.17-SNAPSHOT**，**静态读源码**（本机未跑其运行时），28 个主 Java 文件
> - 自研侧：polygateway **1.0.0-SNAPSHOT**（Poly 系列工程 `poly/polygateway/`，作者 LoveEleve），**本机实跑**：JDK 17.0.20.1 / Spring Boot 3.2.5 / Spring 6.1.6 / Maven 3.9.10，2026-10-05，32 个测试全绿（含服务作用域排除）

---

## 0. 例子先行：一次请求在两条路线里分别发生什么

场景：`order-service`（Spring MVC，`server.servlet.context-path=/api`）有 3 个端点：

| 端点 pattern | 方法 | 说明 |
|---|---|---|
| `/orders/{id}` | GET | 查询订单 |
| `/orders` | POST（consumes=application/json） | 建订单 |
| `/internal/reconcile` | POST | 内部对账，**不想暴露给网关** |

网关地址 `http://localhost:8080`。请求：

```
GET http://localhost:8080/order-service/api/orders/42
```

**官方 `discovery.locator.enabled=true`（服务级自动路由）**：
- 网关为 order-service 生成一条规则 `/order-service/** → lb://order-service`，路径**原样透传**；
- 网关**不知道下游有哪些端点**——`/order-service/api/unknown` 也会被转发（由下游 404 决定），`/internal/reconcile` 同样会被转发（暴露）。

**microsphere `we://`（端点级路由）**：
1. 路由 `we://all` 承接请求，全局过滤器发现 scheme=we，从 URI 模板变量取出 `applicationName=order-service`；
2. 剥掉 `/{application}` 前缀，得到 `/api/orders/42`；
3. 与实例 metadata 里的**端点清单**逐一做六维匹配（pattern/method/params/headers/consumes/produces）→ 命中 `/orders/{id}` + GET；
4. 自己选一个实例 → 把目标 URI 改写为 `http://ip:port/api/orders/42` → 转发（**context-path 保留**）；
5. 未命中（如用 `text/plain` POST `/orders`）→ 不重写、直接放行。训练营分析记录其表现为"空响应/静默 200"（⚠️ 素材口径，本课未做 microsphere 运行时复现）。

**一句话：网关从"按服务名转发"升级为"按端点清单精确匹配"，清单由服务自己发布到注册中心 metadata。**

---

## 1. 为什么需要端点级路由（与官方两条路线对比）

| 方案 | 配置成本 | 控制粒度 | 短板 |
|---|---|---|---|
| **手写路由** `lb://xxx` + `Path=/xxx/**` | 每个服务维护一份 | 服务级 + 路径 | 配置重复、易漂移 |
| **`discovery.locator.enabled=true`** | **零配置** | **服务级** | 路径原样透传——网关不知道下游有哪些端点 |
| **`we://`（microsphere）/ 官方路由生成（polygateway）** | **零配置** | **端点级** | 只有发布端点 metadata 的服务才能被覆盖 |

**设计动机**：服务接口路径变更（`/v1/orders` → `/v2/orders`）时，网关无需改配置，但仍能端点级控制（按 method/consumes 排除内部接口）。

### 1.1 数据流

```
服务 A（provider）                          注册中心              网关
  收集所有 Controller/RouterFunction 端点      │                   │
  → 序列化为 WebEndpointMapping 列表            │                   │
  → 注册前写入实例 metadata ─────────────────►  │                   │
      (web.mappings = JSON)                    │ 实例列表(含metadata) ─► we://all / 路由生成
                                               │◄──────────────────  │ ① 剥 /{application} 前缀
                                               │                    │ ② 六维匹配/谓词匹配
                                               │                    │ ③ 选实例转发
```

**关键：网关不再需要知道下游有哪些接口——它从实例 metadata 里读出来。**

---

## 2. microsphere 的四个扩展点（源码级）

> 以下路径省略前缀 `microsphere-gateway/`（`/data/workspace/source-code/microsphere/microsphere-gateway/`）。
> **协议先记住两个常量**（`microsphere-spring-cloud-commons/.../InstanceConstants.java`）：
> `WEB_MAPPINGS_METADATA_NAME = "web.mappings"`、`WEB_CONTEXT_PATH_METADATA_NAME = "web.context-path"`；
> provider 侧由 microsphere-spring 的 `@EnableWebExtension`（webmvc/webflux 两个变体）自动收集并写入（本课不展开 provider 收集链，重点在**网关侧怎么用这份协议**）。

### 2.1 扩展点①：用 BeanDefinitionRegistryPostProcessor 换掉官方 Bean

`...-server-webflux/.../handler/FilteringWebHandlerBeanDefinitionRegistryPostProcessor.java:39`（license 头省略）：

```java
public class FilteringWebHandlerBeanDefinitionRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

    private BeanDefinitionRegistry registry;

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        this.registry = registry;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        BeanDefinitionRegistry registry = this.registry;
        String[] beanNames = beanFactory.getBeanNamesForType(FilteringWebHandler.class, false, false);
        for (String beanName : beanNames) {
            registry.removeBeanDefinition(beanName);
            registerBeanDefinition(registry, beanName, CachingFilteringWebHandler.class);
        }
    }
}
```

**原理解读**：
- `BeanDefinitionRegistryPostProcessor` 在所有普通 Bean 实例化**之前**执行（`postProcessBeanFactory` 阶段），此时把官方 `GatewayAutoConfiguration` 注册的 `filteringWebHandler` BeanDefinition **删掉，再以同一个 bean 名字注册 `CachingFilteringWebHandler`**；
- 所有 `@Autowired FilteringWebHandler` 的引用方（官方 `RoutePredicateHandlerMapping` 等）拿到的就是 microsphere 的子类，**无感知**；
- 这是"不改 SCG 源码"最锋利的一招，但也最脆弱：**强依赖官方 bean 名/类型**，官方重构即打穿。

### 2.2 扩展点②：CachingFilteringWebHandler——缓存合并后的过滤器链

`.../handler/CachingFilteringWebHandler.java:55`（关键方法）：

```java
public class CachingFilteringWebHandler extends FilteringWebHandler implements DisposableBean {

    static final GatewayFilter[] EMPTY_FILTER_ARRAY = new GatewayFilter[0];

    private volatile Map<String, GatewayFilter[]> routedGatewayFiltersCache = null;

    public CachingFilteringWebHandler(List<GlobalFilter> globalFilters) {
        super(globalFilters);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange) {
        Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);
        GatewayFilter[] routedGatewayFilters = getRoutedGatewayFilters(route);
        return new DefaultGatewayFilterChain(routedGatewayFilters).filter(exchange);
    }

    @EventListener(RefreshRoutesResultEvent.class)
    public void onRefreshRoutesResultEvent(RefreshRoutesResultEvent event) {
        if (matchesEvent(event)) {
            RouteLocator routeLocator = (RouteLocator) event.getSource();
            this.routedGatewayFiltersCache = buildRoutedGatewayFiltersCache(routeLocator);
        }
    }

    private Map<String, GatewayFilter[]> buildRoutedGatewayFiltersCache(RouteLocator routeLocator) {
        Map<String, GatewayFilter[]> routedGatewayFiltersCache = new HashMap<>();
        routeLocator.getRoutes().subscribe(route -> {
            String routeId = route.getId();
            GatewayFilter[] combinedGatewayFilters = combineGatewayFilters(route);
            routedGatewayFiltersCache.put(routeId, combinedGatewayFilters);
        }).dispose();
        return routedGatewayFiltersCache;
    }

    GatewayFilter[] getRoutedGatewayFilters(Route route) {
        Map<String, GatewayFilter[]> routedGatewayFiltersCache = this.routedGatewayFiltersCache;
        if (routedGatewayFiltersCache == null) {
            return EMPTY_FILTER_ARRAY;
        }
        return routedGatewayFiltersCache.getOrDefault(route.getId(), EMPTY_FILTER_ARRAY);
    }

    private List<GatewayFilter> globalFilters() {
        return getFieldValue(true, this, "globalFilters");   // 反射读父类 private 字段
    }
}
```

**动机**：官方 `FilteringWebHandler.handle` 每个请求都重新 `combine`（global + route filters）并排序；这里按 `routeId → GatewayFilter[]` 缓存，路由刷新事件（成功）时整体重建，请求路径只做一次 Map 查找。
**两个细节**：
- 官方 `DefaultGatewayFilterChain` 是 `FilteringWebHandler` 的内部私有类，子类复用不了 → microsphere 自建了 `DefaultGatewayFilterChain`；
- `globalFilters` 是父类 private 字段，只能反射取（`FieldUtils.getFieldValue`）——**扩展官方类的典型代价**。

### 2.3 扩展点③：WebEndpointMappingGlobalFilter——核心运行时改造

`.../filter/WebEndpointMappingGlobalFilter.java:107`，类声明与 order：

```java
public class WebEndpointMappingGlobalFilter implements GlobalFilter, SmartApplicationListener, ApplicationContextAware,
        EnvironmentAware, DisposableBean, Ordered {
    ...
    @Override
    public int getOrder() {
        return LOAD_BALANCER_CLIENT_FILTER_ORDER - 1;   // 10150 - 1 = 10149
    }
```

（`LOAD_BALANCER_CLIENT_FILTER_ORDER = 10150`，已用本机 `spring-cloud-gateway-server-4.1.5.jar` 的 `javap -constants` 验证。）

**`filter()` 全文（:134）**：

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    URI url = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
    if (isInvalidScheme(url)) {
        // NO Web-Endpoint scheme -> 非 we:// 请求直接放行（与 lb:// 混排的关键）
        return chain.filter(exchange);
    }

    Map<String, String> uriTemplateVariables = getUriTemplateVariables(exchange);
    String applicationName = uriTemplateVariables.get(APPLICATION_NAME_URI_TEMPLATE_VARIABLE_NAME);
    if (isBlank(applicationName)) {
        return chain.filter(exchange);
    }

    RequestMappingContext requestMappingContext = getMatchingRequestMappingContext(applicationName, exchange);

    if (requestMappingContext != null) {
        // 命中端点清单
        ServiceInstance serviceInstance = choose(applicationName);        // 自己选实例
        if (serviceInstance != null) {
            String uri = getUriString(serviceInstance);
            Map<String, Object> attributes = exchange.getAttributes();
            String rewritePath = (String) attributes.remove(WEB_ENDPOINT_REWRITE_PATH_ATTRIBUTE_NAME);
            URI targetURI = create(uri + rewritePath);                    // http://ip:port + 原始路径
            int id = requestMappingContext.id;
            ServerHttpRequest request = exchange.getRequest()
                    .mutate()
                    .header(ID_HEADER_NAME, valueOf(id)).build();         // microsphere_wem_id = 端点 id
            attributes.put(GATEWAY_REQUEST_URL_ATTR, targetURI);          // 改写目标 URL
            return chain.filter(exchange.mutate().request(request).build());
        }
    }
    return chain.filter(exchange);   // 未命中：放行（⚠️ 静默问题来源）
}
```

**==为什么 order 要在 LB 过滤器前一位==**：它提前用 `LoadBalancerClientFactory` 手动 `choose()`，把 `GATEWAY_REQUEST_URL_ATTR` 从 `we://all/...` 改写成 `http://ip:port/...`。官方的 `ReactiveLoadBalancerClientFilter` 只处理 `lb://` scheme，看到已改写后的 http URL 就跳过——**等于把官方 LB 过滤器"架空"在这个路由上**，选实例的活儿自己干了。

**匹配逻辑（:306）**：

```java
RequestMappingContext getMatchingRequestMappingContext(String applicationName, String routeId, ServerWebExchange exchange) {
    if (isExcludedRequest(routeId, exchange)) {
        return null;                                       // 请求级排除（来自路由 metadata 的 WebEndpointConfig）
    }
    Map<String, Collection<RequestMappingContext>> routedRequestMappingContexts = this.routedRequestMappingContextsCache;
    if (routedRequestMappingContexts == null) { return null; }
    Collection<RequestMappingContext> requestMappingContexts = routedRequestMappingContexts.get(routeId);
    if (isEmpty(requestMappingContexts)) { return null; }

    String path = exchange.getRequest().getPath().value();
    String rewritePath = substringAfter(path, SLASH_CHAR + applicationName);   // /order-service/api/orders/42 -> /api/orders/42
    exchange.getAttributes().put(WEB_ENDPOINT_REWRITE_PATH_ATTRIBUTE_NAME, rewritePath);
    ServerWebExchange newExchange = exchange.mutate().request(request.mutate().path(rewritePath).build()).build();

    List<RequestMappingContext> matchesRequestMappings = newArrayList(requestMappingContexts.size());
    for (RequestMappingContext requestMappingContext : requestMappingContexts) {
        if (matchesRequestMapping(newExchange, requestMappingContext)) {       // RequestMappingInfo.getMatchingCondition
            matchesRequestMappings.add(requestMappingContext);
        }
    }
    matchesRequestMappings.sort((v1, v2) -> v1.compareTo(v2, newExchange));    // 最具体者优先
    return first(matchesRequestMappings);
}
```

其中 `RequestMappingContext.buildRequestMappingInfo` 把端点六维直接映射成 Spring MVC/WebFlux 的同款 `RequestMappingInfo`（`paths/methods/params/headers/consumes/produces`）——**借 Spring 自己的路由匹配语义做端点匹配**，excludes 也用同一套表达（`isExcludedRequest`）。

**缓存刷新触发事件（`supportsEventType` :175）**：`ContextRefreshedEvent`、`RefreshRoutesResultEvent`（成功）、`EnvironmentChangeEvent`（仅 `spring.cloud.gateway.routes.*` 变更）、`ServiceInstancesChangedEvent`。四者之外的变化**不会**触发重扫。

### 2.4 扩展点④：把官方心跳路由刷新"静默拦截"

`.../event/DisabledHeartbeatEventRouteRefreshListenerInterceptor.java:39`：

```java
public class DisabledHeartbeatEventRouteRefreshListenerInterceptor implements ApplicationListenerInterceptor {

    private static final Class<RouteRefreshListener> INTERCEPTED_CLASS = RouteRefreshListener.class;

    @Override
    public void intercept(ApplicationListener<?> applicationListener, ApplicationEvent event, ApplicationListenerInterceptorChain chain) {
        Class<?> listenerClass = applicationListener.getClass();
        Class<?> eventClass = event.getClass();
        if (INTERCEPTED_CLASS.equals(listenerClass) && matchesHeartbeatEvent(eventClass)) {
            logger.trace("The ApplicationListener[{}] with event[{}] is disabled", listenerClass, eventClass);
            return;                                  // 官方 RouteRefreshListener 的心跳刷新被直接丢弃
        }
        chain.intercept(applicationListener, event);
    }

    private boolean matchesHeartbeatEvent(Class<?> eventClass) {
        return HeartbeatEvent.class.equals(eventClass) || ParentHeartbeatEvent.class.equals(eventClass);
    }
}
```

官方 `RouteRefreshListener` 监听心跳事件、在**心跳值变化**时触发路由刷新；microsphere 用 `ApplicationListenerInterceptor`（microsphere-spring 的监听器拦截机制）把它拦掉——意图是控制自己的刷新节奏。**副作用：官方那条"心跳变化 → 路由刷新"的能力也没了**（这是 G1 的拼图之一）。

### 2.5 G1 根因（源码定位）：为什么滚动发布会断链

`refresh()` 里对每条 we 路由执行 `buildRequestMappingContexts(routeUri)`（:272）：

```java
private Collection<RequestMappingContext> buildRequestMappingContexts(URI routeUri) {
    Collection<String> subscribedServices = getSubscribedServices(routeUri);
    Collection<RequestMappingContext> requestMappingContexts = new LinkedList<>();
    // TODO support ZonePreferenceFilter
    for (String subscribedService : subscribedServices) {
        ServiceInstance sampleServiceInstance = choose(subscribedService);   // ⚠️ 只"采样"一个实例
        Collection<WebEndpointMapping> webEndpointMappings = getWebEndpointMappings(sampleServiceInstance);
        for (WebEndpointMapping webEndpointMapping : webEndpointMappings) {
            requestMappingContexts.add(new RequestMappingContext(webEndpointMapping));
        }
    }
    return requestMappingContexts;
}
```

**G1 不是"缓存 TTL 问题"，是两个设计选择叠加**：

1. **采样单实例**：对每个服务只用 LB `choose()` 抽**一个**实例读 `web.mappings`——滚动发布中新旧实例 metadata 不同时，抽到旧实例 = 新端点缺失，抽到新实例 = 旧端点缺失（**不是并集**），每次刷新的结果都不可预期；
2. **刷新触发面窄**：metadata 变化本身不发布 §2.3 那四类事件（`ServiceInstancesChangedEvent` 发不发取决于注册中心实现），而官方心跳刷新又在 §2.4 被拦掉。

**后果**：服务改了 API 路径后，网关端点缓存不更新 → 新接口"未命中 → 静默放行/空响应"（⚠️ 训练营口径），排障只能重启网关或手动刷新。

---

## 3. polygateway：官方路由生成路线（同协议、不同实现）

> 位置：Poly 系列工程 `poly/polygateway/`（本机 `/data/workspace/source-code/poly/polygateway/`），4 模块 15 个主 Java 文件 + 32 个测试。
> 一句话：**复用同款"端点元数据"协议，但不在运行时链上做文章——把端点清单在生成期翻译成官方 `RouteDefinition`**。

### 3.1 模块结构

```
polygateway/
├── polygateway-core/        # 4 文件：WebEndpointMapping(模型) / Codec(JSON) / WebEndpointExclusion / PolyGatewayConstants
├── polygateway-gateway/     # 5 文件：RouteDefinitionLocator / RefreshListener / GatewayProperties / Consumes+Produces 谓词
├── polygateway-provider/    # 3 文件：Collector(采集) / MetadataPublisher(发布) / ProviderListener
└── polygateway-spring-boot/ # 3 文件：两个 AutoConfiguration + ProviderProperties（Spring Boot 3 自动配置入口）
```

**与 microsphere 的本质区别**：

| | microsphere | polygateway |
|---|---|---|
| 扩展层 | **运行期**：换 `FilteringWebHandler`、塞 GlobalFilter、自选实例、重写 URL | **生成期**：`RouteDefinitionLocator` 产出官方路由，运行时链零改动 |
| 匹配器 | 自己的 `RequestMappingInfo` 匹配 + 排除 | 官方谓词（Path/Method/Header/Query/Consumes/Produces）+ 官方 `RoutePredicateHandlerMapping` |
| 侵入性 | 依赖官方私有字段/bean 名，升级敏感 | 只用官方公开扩展点（`RouteDefinitionLocator`、`RoutePredicateFactory`），升级稳健 |

### 3.2 provider 侧：采集（Collector）

`polygateway-provider/.../WebEndpointMappingsCollector.java`（关键方法）：

```java
public List<WebEndpointMapping> collect(ApplicationContext context) {
    List<WebEndpointMapping> mappings = new ArrayList<>();
    for (RequestMappingHandlerMapping handlerMapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
        collect(handlerMapping, mappings);
    }
    mappings.sort(mappingComparator());   // 按六维确定排序
    assignIds(mappings);                  // 位置即 id：同一端点集永远得到同一组 id
    return mappings;
}

private void collect(RequestMappingHandlerMapping handlerMapping, List<WebEndpointMapping> mappings) {
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
        HandlerMethod handlerMethod = entry.getValue();
        if (!isIncludedController(handlerMethod.getBeanType().getName())) {
            continue;
        }
        mappings.add(toMapping(entry.getKey()));
    }
}

protected boolean isIncludedController(String controllerClassName) {
    return !controllerClassName.startsWith("org.springframework.boot.");   // 跳过 actuator/error 等基础设施端点
}
```

- 端点来源是 **`RequestMappingHandlerMapping`**：Spring MVC 自己解析好的 `RequestMappingInfo`（pattern/methods/params/headers/consumes/produces 六维直接可取），不用另起解析器；
- **确定性排序 + 位置 id**：`id` 只取决于端点集内容 → 网关路由 id（`polygateway:{serviceId}:{id}`）稳定，滚动发布不抖动。

### 3.3 provider 侧：注册前发布（Publisher + Listener）

`polygateway-provider/.../WebEndpointMetadataPublisher.java`：

```java
public void publish(ServiceInstance serviceInstance) {
    List<WebEndpointMapping> mappings = collector.collect(context);
    String endpoints = WebEndpointMappingCodec.encode(mappings);          // JSON
    Map<String, String> metadata = serviceInstance.getMetadata();
    putMetadata(metadata, PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME, endpoints);   // polygateway.web-endpoints

    String contextPath = context.getEnvironment().getProperty(CONTEXT_PATH_PROPERTY_NAME); // server.servlet.context-path
    if (StringUtils.hasText(contextPath)) {
        putMetadata(metadata, PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME, contextPath); // polygateway.context-path
    }
}
```

`PolyGatewayProviderListener` 挂在 **`InstancePreRegisteredEvent`** 上：

```java
public class PolyGatewayProviderListener implements ApplicationListener<InstancePreRegisteredEvent> {
    private final WebEndpointMetadataPublisher publisher;
    @Override
    public void onApplicationEvent(InstancePreRegisteredEvent event) {
        publisher.publish(event.getRegistration());   // 注册前写 metadata：实例一可见，网关就能读到端点
    }
}
```

**顺序很关键**：metadata 必须在实例注册**之前**写入，否则网关可能先看到一个没有端点信息的空实例。

### 3.4 gateway 侧：路由生成（WebEndpointRouteDefinitionLocator 核心）

`polygateway-gateway/.../WebEndpointRouteDefinitionLocator.java`：

```java
@Override
public Flux<RouteDefinition> getRouteDefinitions() {
    return Flux.fromIterable(loadRouteDefinitions());
}

public List<RouteDefinition> loadServiceRoutes(String serviceId) {
    List<ServiceInstance> serviceInstances = discoveryClient.getInstances(serviceId);
    if (CollectionUtils.isEmpty(serviceInstances)) { return List.of(); }
    Set<WebEndpointMapping> endpoints = new LinkedHashSet<>();      // 去重
    String contextPath = null;
    for (ServiceInstance serviceInstance : serviceInstances) {
        Map<String, String> metadata = serviceInstance.getMetadata();
        String endpointsJSON = metadata.get(PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME);
        if (StringUtils.hasText(endpointsJSON)) {
            try {
                endpoints.addAll(WebEndpointMappingCodec.decode(endpointsJSON));   // ⚠️ 全实例并集
            } catch (RuntimeException e) { logger.warn(...); }
        }
        if (contextPath == null) {
            contextPath = normalizeContextPath(metadata.get(PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME));
        }
    }
    ...
    for (WebEndpointMapping endpoint : endpoints) {
        if (isExcluded(endpoint)) { continue; }
        routeDefinitions.add(createRouteDefinition(serviceId, contextPath, endpoint));
    }
    return routeDefinitions;
}

protected RouteDefinition createRouteDefinition(String serviceId, String contextPath, WebEndpointMapping endpoint) {
    RouteDefinition routeDefinition = new RouteDefinition();
    routeDefinition.setId(PolyGatewayConstants.ROUTE_ID_PREFIX + serviceId + ":" + endpoint.getId());  // polygateway:order-service:1
    routeDefinition.setUri(URI.create("lb://" + serviceId));        // 官方响应式 LB，非阻塞
    routeDefinition.setOrder(routeOrder(endpoint));                 // 越具体越优先
    routeDefinition.setPredicates(createPredicates(serviceId, contextPath, endpoint));
    routeDefinition.setFilters(List.of(new FilterDefinition("StripPrefix=1")));   // 只剥 /{serviceId}
    return routeDefinition;
}

private List<String> paths(String serviceId, String contextPath, WebEndpointMapping endpoint) {
    String prefix = "/" + serviceId + (contextPath == null ? "" : contextPath);
    List<String> paths = new ArrayList<>(endpoint.getPatterns().length);
    for (String pattern : endpoint.getPatterns()) {
        paths.add(prefix + (pattern.startsWith("/") ? pattern : "/" + pattern));
    }
    return paths;
}
```

**要点**：
- 路径 = `/{serviceId}{contextPath}{pattern}`：每个服务天然占一个前缀，**服务间不会路由冲突**；`StripPrefix=1` 只剥服务名，**context-path 原样保留**转发给下游；
- **path 前缀 + 官方谓词**承载六维：Path、Method，以及 `Header`/`Query`（`addConditionPredicate`）、`Consumes`/`Produces`（§3.6 的自研谓词）；
- **全实例并集**（`LinkedHashSet.addAll`）：即使某端点只在金丝雀实例上（滚动发布中途），也照样生成路由——microsphere §2.5 的"采样单实例"问题在这里被结构性消除；
- 排除规则 `WebEndpointExclusion.matches`：Ant 风格 patterns **必填**（不填视为无规则）+ 其余维度匹配（方法交集、params/headers 须被声明、consumes/produces 取交集）；
- 字面值转义：`regexp = Pattern.quote(value)` 让官方 Header/Query 谓词精确匹配（`1.0` 不会匹配 `1x0`）；
- 表达不了的条件（`!debug`、`name!=value`）**跳过并打日志**，端点保持可达（fidelity 取舍，见 §3.7）。

**route order 公式**（越具体越优先）：

```java
protected int routeOrder(WebEndpointMapping endpoint) {
    int literalTokens = 0, variableTokens = 0, wildcards = 0;
    for (String pattern : endpoint.getPatterns()) {
        for (String token : pattern.split("/")) {
            if (token.isEmpty()) continue;
            if (token.contains("**")) wildcards++;
            else if (token.contains("{")) variableTokens++;
            else literalTokens++;
        }
    }
    return -(literalTokens * 2) - variableTokens + (wildcards * 2);
}
// /orders/{id} -> -3；/orders -> -2：数值小者（更具体）先匹配
```

### 3.5 G1 修复：每次心跳强制刷新路由

`polygateway-gateway/.../PolyGatewayRouteRefreshListener.java`（**全文**：G1 修复的答案就是这 46 行）：

```java
public class PolyGatewayRouteRefreshListener implements ApplicationListener<HeartbeatEvent> {

    private final ApplicationEventPublisher eventPublisher;

    public PolyGatewayRouteRefreshListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onApplicationEvent(HeartbeatEvent event) {
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));   // 每个心跳 -> 强制路由重算
    }
}
```

- 官方 `RouteRefreshListener` 只在**心跳值变化**时刷新（启发式）；polygateway 不依赖启发式——**每次心跳都发 `RefreshRoutesEvent`**，`WebEndpointRouteDefinitionLocator` 重新从当前 discovery 状态读全实例并集 → metadata 变更（新端点、上下线实例）下一拍即生效；
- 与 microsphere §2.5 的 G1 正好构成"**问题 → 修复**"的对照素材；
- 代价：每次心跳全量重建路由列表（中小规模可接受；大规模需要增量/节流，属后续优化）。

### 3.6 官方没有的谓词：Consumes / Produces

本机 `spring-cloud-gateway-server-4.1.5.jar` 内置谓词清单（`jar tf` 枚举）：`After/Before/Between/Cookie/Header/Host/Method/Path/Query/ReadBody/RemoteAddr/Weight/XForwardedRemoteAddr`——**没有 Consumes/Produces**。端点六维要完整落到官方谓词上，只能自己写。`ConsumesRoutePredicateFactory`（`Produces` 同构）：

```java
public class ConsumesRoutePredicateFactory extends AbstractRoutePredicateFactory<ConsumesRoutePredicateFactory.Config> {

    @Override
    public List<String> shortcutFieldOrder() { return List.of("mediaTypes"); }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        List<MediaType> mediaTypes = parseMediaTypes(config.getMediaTypes());
        return exchange -> {
            MediaType contentType = exchange.getRequest().getHeaders().getContentType();
            return contentType != null && mediaTypes.stream().anyMatch(mediaType -> mediaType.isCompatibleWith(contentType));
        };
    }

    public static class Config {
        private List<String> mediaTypes = new ArrayList<>();
        public List<String> getMediaTypes() { return mediaTypes; }
        public void setMediaTypes(List<String> mediaTypes) { this.mediaTypes = mediaTypes; }
    }
}
```

Spring 按 bean 名（去掉 `RoutePredicateFactory` 后缀）发现谓词：bean 名 `consumeRoutePredicateFactory`/`producesRoutePredicateFactory` → YAML/`PredicateDefinition` 里写 `Consumes=...`/`Produces=...` 即可。

### 3.7 自动配置与开关

`polygateway-spring-boot` 两个 `@AutoConfiguration`（Boot 3 新入口）：

```java
// gateway 侧
@AutoConfiguration
@ConditionalOnClass({RouteDefinitionLocator.class, DiscoveryClient.class})
@ConditionalOnProperty(prefix = "polygateway.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PolyGatewayGatewayProperties.class)
public class PolyGatewayGatewayAutoConfiguration {
    @Bean @ConditionalOnMissingBean @ConditionalOnBean(DiscoveryClient.class)
    public WebEndpointRouteDefinitionLocator webEndpointRouteDefinitionLocator(DiscoveryClient discoveryClient,
                                                                               PolyGatewayGatewayProperties properties) { ... }

    @Bean @ConditionalOnMissingBean
    public ConsumesRoutePredicateFactory consumesRoutePredicateFactory() { return new ConsumesRoutePredicateFactory(); }

    @Bean @ConditionalOnMissingBean
    public ProducesRoutePredicateFactory producesRoutePredicateFactory() { return new ProducesRoutePredicateFactory(); }

    @Bean @ConditionalOnMissingBean
    public PolyGatewayRouteRefreshListener polyGatewayRouteRefreshListener(ApplicationEventPublisher eventPublisher) { ... }
}

// provider 侧
@AutoConfiguration
@ConditionalOnClass({RequestMappingHandlerMapping.class, InstancePreRegisteredEvent.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)   // ⚠️ 仅 Spring MVC
@ConditionalOnProperty(prefix = "polygateway.provider", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PolyGatewayProviderAutoConfiguration {
    @Bean @ConditionalOnMissingBean public WebEndpointMappingsCollector webEndpointMappingsCollector() { ... }
    @Bean @ConditionalOnMissingBean public WebEndpointMetadataPublisher webEndpointMetadataPublisher(...) { ... }
    @Bean @ConditionalOnMissingBean public PolyGatewayProviderListener polyGatewayProviderListener(...) { ... }
}
```

### 3.8 设计取舍（README 记录）

| 取舍 | 内容 |
|---|---|
| **一个生效的 DiscoveryClient** | 多个注册中心客户端并存时 LB 拿到的实例列表不可预期（测试中曾拿到空列表）；生产单注册中心天然满足 |
| **端点级排除 = 整端点粒度** | 规则命中端点 → 该端点**所有**路由都不生成（与 microsphere 的"请求级"排除语义略不同，已在 README 注明）；规则可用 `services` 限定到指定服务，不填 = 所有服务 |
| **字面值转义** | 条件值 → 谓词参数时 `Pattern.quote`；`!`/`!=` 条件官方谓词表达不了，跳过并打日志 |
| **刷新依赖心跳** | 无心跳发布的注册中心实现需自行发 `HeartbeatEvent`（或 `RefreshRoutesEvent`） |
| **Provider 仅 Spring MVC** | WebFlux 业务应用收集在 Roadmap（microsphere 是 webmvc/webflux 双栈） |
| **未命中 = 404** | 无路由 → SCG 默认 404（对 microsphere"静默 200"的语义改进，见 §4 实测） |

---

## 4. 实测记录（2026-10-05，polygateway）

命令（cwd `poly/polygateway/`）：

```bash
JAVA_HOME=/env/javaEnv/jdk17 mvn -B test
```

结果：`BUILD SUCCESS`，**32 tests / 0 failures / 0 errors**，总耗时 1:21 min（服务作用域排除落地后的复跑）。

| 模块 | 测试数 | 代表测试 |
|---|---|---|
| core | 15 | `WebEndpointExclusionTest`(8) / `WebEndpointMappingCodecTest`(4) / `WebEndpointMappingTest`(3) |
| provider | 4 | `WebEndpointMappingsCollectorTest`(2) / `WebEndpointMetadataPublisherTest`(2) |
| gateway | 9 | `WebEndpointRouteDefinitionLocatorTest`(6) / `RoutePredicateFactoriesTest`(2) / `PolyGatewayRouteRefreshListenerTest`(1) |
| spring-boot | 4 | `PolyGatewayAutoConfigurationTest`(3) / **`PolyGatewayGatewayE2ETest`(1)** |

**E2E 用桩注册中心 + 真实 HTTP 下游 + `WebTestClient` 验证全链路**（`PolyGatewayGatewayE2ETest.java:90`，摘录）：

```java
// 六项断言（E2E）
assertEquals("/api/orders", downstream.lastPathOf("POST"));                  // StripPrefix 后保留 context-path /api
webClient.post().uri("/order-service/api/orders")
        .contentType(MediaType.TEXT_PLAIN).bodyValue("x")
        .exchange().expectStatus().isNotFound();                            // Consumes 不匹配 -> 404（非静默放行）
webClient.post().uri("/order-service/api/internal/reconcile")
        .exchange().expectStatus().isNotFound();                            // excludes（按服务限定）命中 -> 404
webClient.post().uri("/audit-service/api/internal/reconcile")
        .exchange().expectStatus().isOk();                                  // 同名端点：规则限定 order-service，audit 保留
assertEquals("/api/internal/reconcile", downstream.lastPathOf("POST"));     // 且真的到达了下游
webClient.get().uri("/order-service/api/unknown")
        .exchange().expectStatus().isNotFound();                            // 无路由 -> 404
// 刷新证明：metadata 里新增端点后，发一个心跳即可路由
discoveryClient.setInstances(SERVICE_ID, List.of(instance(SERVICE_ID, downstream.port(), "/api", refreshed)));
eventPublisher.publishEvent(new HeartbeatEvent(this, "refreshed"));
awaitUntil(() -> "GET /api/orders/42/lines".equals(
        webClient.get().uri("/order-service/api/orders/42/lines").exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody()));
```

运行日志同时给出两组直接证据：

```
i.g.l.p.g.WebEndpointRouteDefinitionLocator - The endpoint [WebEndpointMapping{kind=MVC, id=3,
    patterns=[/internal/reconcile], methods=[POST], ...}] of the service [order-service] is excluded from the routes
i.g.l.p.g.WebEndpointRouteDefinitionLocator - Loaded 3 endpoint route definition(s)     <-- order 2 条（3 端点 - 1 被按服务排除）+ audit 1 条
i.g.l.p.g.WebEndpointRouteDefinitionLocator - Loaded 4 endpoint route definition(s)     <-- 心跳刷新后 order-service 新端点生效 +1
```

**结论（本课实测口径）**：端点级路由、六维匹配（Path/Method/Consumes）、`excludes`（含**按服务作用域**：同名端点 order-service 被排除 / audit-service 保留）、context-path 保留、**心跳刷新修 G1（路由数 3→4、新端点 200）**、未命中 404 语义，全部有运行时证据。

### 4.1 真实环境实测（2026-10-05 补测：真实 Nacos + 真实应用）

> 实验室：`polygateway-lab/`（xhs-demo 下，独立 POM，不进课程 reactor）；版本口径：JDK 17 / Boot 3.2.5 / Cloud 2023.0.3 / SCA 2023.0.1.2 / Nacos 2.2.3 / polygateway 1.0.0-SNAPSHOT。

**拓扑与配置要点**

```text
lab-provider (8181, context-path=/api)  ─┐
lab-audit    (8182, 同一 jar 换服务名)    ─┤ 注册 Nacos（实例 metadata 带
                                          │   polygateway.web-endpoints / polygateway.context-path）
                                          ↓
                                    Nacos 2.2.3 (8848)
                                          ↓
lab-gateway  (8180, SCG + Nacos + LB + polygateway)
  ├─ spring.cloud.gateway.discovery.locator.enabled=false   # 关官方服务级路由
  ├─ spring.cloud.nacos.discovery.heart-beat.enabled=true    # ★ 见发现 2
  └─ excludes: services=lab-provider + patterns=/internal/** + methods=POST
```

**复现（运行 / 停止）**

```bash
# 前置：polygateway 未发布，先安装到本地仓库；再构建 lab
JAVA_HOME=/env/javaEnv/jdk17 mvn -f source-code/poly/polygateway/pom.xml install
JAVA_HOME=/env/javaEnv/jdk17 mvn -f source-code/xhs-demo/polygateway-lab/pom.xml package -DskipTests

# 启动（setsid 防会话清理杀进程；Nacos docker 需已运行）
setsid java -jar lab-provider/target/lab-provider-1.0-SNAPSHOT.jar &
setsid java -jar lab-provider/target/lab-provider-1.0-SNAPSHOT.jar --spring.application.name=lab-audit --server.port=8182 &
setsid java -jar lab-gateway/target/lab-gateway-1.0-SNAPSHOT.jar &

# 停止
pkill -f "[l]ab-provider-1.0-SNAPSHOT.jar"
pkill -f "[l]ab-gateway-1.0-SNAPSHOT.jar"
```

> 本机 shell 有 `http_proxy`，curl 访问 localhost 必须加 `--noproxy '*'`。

**实测矩阵（7/7）**

| # | 请求（经 8180） | 实测 | 证明点 |
|---|---|---|---|
| 1 | `GET /lab-provider/api/orders/42` | 200 `GET /api/orders/42` | 端点级路由 + `StripPrefix=1` + context-path 保留 |
| 2 | `GET /lab-provider/api/orders/special` | 200 | **更具体路由优先**（`/orders/special` 胜 `/orders/{id}`） |
| 3 | `POST /lab-provider/api/orders`（json） | 200 `POST /api/orders` | `Consumes` 谓词（自研补官方缺口） |
| 4 | `POST /lab-provider/api/orders`（text/plain） | **404** | Consumes 不匹配 → 无路由 |
| 5 | `POST /lab-provider/api/internal/reconcile` | **404** | **服务作用域排除**命中 lab-provider |
| 6 | `POST /lab-audit/api/internal/reconcile` | 200 `POST /api/internal/reconcile` | **同名端点在其他服务保留** |
| 7 | `GET /lab-provider/api/unknown` | 404 | 无路由 → 404 |

- **心跳刷新（G1 修复）真实成立**：gateway 日志周期出现 `Loaded 9 endpoint route definition(s)`（`9 = 5 + 5 − 1`，减掉被服务作用域排除的那条）；
- **Nacos metadata 实见**：`polygateway.web-endpoints`（六维 JSON）+ `polygateway.context-path=/api`。

**真实环境暴露并修复的两个集成问题（in-process 测试覆盖不到）**：

1. **SCG 传递依赖穿透**：`polygateway-gateway` 把 `spring-cloud-starter-gateway` 声明为非 optional → MVC provider 加 `polygateway-spring-boot` 即启动失败（`Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway`）。修复：`polygateway-gateway` / `polygateway-spring-boot` 的 SCG/LoadBalancer 均改 `<optional>true</optional>`；provider fat-jar 中 SCG 依赖数 1→0、体积 52MB→33MB（gateway fat-jar 保留）；
2. **SCA 2023 的 HeartbeatEvent 是条件创建**：`NacosDiscoveryHeartBeatPublisher` 三选一（`spring.cloud.nacos.discovery.heart-beat.enabled=true` / `discovery.locator.enabled=true` / Spring Boot Admin 的 `InstanceDiscoveryListener`）。polygateway 用户通常会关 locator → 心跳不发布 → **刷新静默失效**；须显式打开 `heart-beat.enabled`（lab 已配置；类内属性名可用 `javap`/`strings` 从 `NacosDiscoveryHeartBeatConfiguration$...Condition` 反查）。

> 操作手册完整版：`polygateway-lab/README.md`；本节为课程化沉淀（结论 + 证据 + 两个发现）。

---

## 5. 两条路线总对比

| 维度 | microsphere-gateway | polygateway |
|---|---|---|
| 扩展手段 | BDRPP 换官方 Bean + GlobalFilter + 监听器拦截 | `RouteDefinitionLocator` + `RoutePredicateFactory` + 事件监听 |
| 侵入性 | 高：反射官方 private 字段、依赖官方 bean 名；官方升级敏感 | 低：只用官方公开 SPI；升级稳健 |
| 匹配机制 | 自建 `RequestMappingInfo`（复用 Spring MVC 匹配语义） | 官方谓词链（含自研 Consumes/Produces 补缺） |
| 实例 metadata 读取 | **采样单实例**（`choose()` 一个） | **全实例并集**（`LinkedHashSet.addAll`） |
| 刷新触发 | 4 类事件；官方心跳刷新被拦截 | **每次心跳 → `RefreshRoutesEvent`** |
| G1（滚动发布端点不更新） | ⚠️ 存在（采样 + 刷新面窄） | ✅ 修复（并集 + 心跳强制刷新，E2E 证明） |
| 未命中语义 | 静默放行（训练营口径，未复现） | 404（E2E 实测） |
| excludes 粒度 | 请求级·请求期（详见 §5.1） | 整端点·生成期·可按服务限定（详见 §5.1） |
| 六维保真度 | 高（negation 原生支持） | 高，但 `!`/`!=` 条件跳过 |
| 栈支持 | WebFlux + WebMVC 双栈 | gateway=WebFlux，provider=Spring MVC（WebFlux Roadmap） |
| 配置开关 | `microsphere.gateway.*`（BOM/注解） | `polygateway.gateway.enabled` / `polygateway.provider.enabled`（默认 true） |
| 测试 | 训练营源码分析口径（未列测试数） | 32 个进程内测试（含全链路 E2E） |
| 学习价值 | 学"**怎么突破官方边界做扩展**"（换 Bean、上反射、拦截事件） | 学"**怎么用官方扩展点做生产级实现**"（SPI、协议、确定性、修复缺口） |

> 表里"excludes 粒度"一行最容易被误读成"polygateway 少一个能力"——两者差的不是粒度高低，而是**评估时机**，拆解见 §5.1。

### 5.1 排除语义：不是粒度高低，是评估时机不同（为什么 polygateway 不追请求级）

| 子维度 | microsphere | polygateway |
|---|---|---|
| 评估时机 | **请求期**：每个请求拿真实 exchange 逐条匹配（`WebEndpointMappingGlobalFilter.isExcludedRequest`） | **生成期**：只对端点声明的六维匹配（`WebEndpointExclusion.matches`，javadoc：*applied when the gateway generates the endpoint routes*） |
| 匹配对象 | 真实请求的 path/method/header/param（含**实际值**） | 端点**声明的**条件；`params`/`headers` 要求"规则值全部被端点声明"（`declaredMatch`，精确相等） |
| 作用域 | **按路由**：excludes 挂在路由 metadata（`WebEndpointConfig`），可按服务/服务组配置 | **全局默认 + 可按服务限定**：`excludes[n].services`（精确匹配，不填 = 所有服务；2026-10-05 支持） |
| 命中后果 | 匹配 → 过滤器放行 → 静默空响应 | 端点不生成路由 → **404**（语义明确） |

服务作用域维度的配置形态（2026-10-05 新增，`services` 精确匹配，不填 = 所有服务）：

```properties
# 只排除 pay-service 的内部端点；order-service 的同名端点不受影响
polygateway.gateway.excludes[0].services=pay-service
polygateway.gateway.excludes[0].patterns=/internal/**
polygateway.gateway.excludes[0].methods=POST
```

**polygateway 的两个真实限制**（如实写，不粉饰）：

1. **多方法端点不能只排一个方法**：`methodsMatch` 是"交集即命中"——端点声明 `methods={GET,POST,DELETE}` 时，规则 `methods=DELETE` 会把**整条路由**干掉；端点只声明 `{GET,POST}` 时，规则 `DELETE` 完全不命中（交集为空）。一个 handler method 上挂多个 HTTP 方法声明时无法拆分；
2. **端点未声明的 header/param 不能作排除条件**：`declaredMatch` 要求规则值是端点声明值的子集（精确相等）——做不了"拦截所有带 `X-Internal` 头的请求"这类**请求特征防火墙**。

**为什么这是合理取舍而不是"缺功能"**：microsphere 的请求级排除是**运行时匹配架构的副产品**（排除与匹配共用 `RequestMappingInfo` 机制），本质是"请求防火墙"；polygateway 的生成期排除是"**端点暴露策略**"——被排除的端点**路由表里根本不存在**，与"官方谓词生成"路线自洽，结果确定（404）。选型判断：

| 需求 | 用哪种语义 |
|---|---|
| "内部接口不上网关"（暴露策略） | polygateway 生成期排除（更安全：路由不存在，没有被绕过的匹配路径） |
| "带某请求头/参数才拦截""按请求特征灰度放行"（防火墙） | microsphere 式请求级；polygateway 若要做，应另挂 guard filter / 自定义 predicate，**不要塞进路由生成** |

**面试口径**：被追问"排除粒度"时，承认上面 2 条限制（服务作用域已支持）+ 解释"评估时机不同导致语义不同"，再补一句"要做请求级拦截，我会在路由生成之后挂 guard filter，保持生成期只做暴露策略"——比硬说"我们支持得更细"得分高。

---

## 6. 简历与面试怎么讲

### 6.1 诚实结论

**microsphere-gateway 是"小马哥的项目"，不是你的**——直接写进简历 = 造假。

**能写的三种情况**：

| 情况 | 能不能写 | 怎么写 |
|---|---|---|
| 只是"学习过" | ❌ **不能写** | — |
| **把它集成到自己的项目** | ✅ 能写 | "网关引入 microsphere 生态，实现端点级路由（服务自动发布端点元数据，网关零配置精确匹配）" |
| **参考它的设计自研了类似能力** | ✅ 能写（**最推荐**） | "参考端点元数据方案自研了 xxx（解决 G1 滚动发布缓存问题）"——**polygateway 就是这条路的实物证据** |

### 6.2 最值钱的"自研点"（面试可讲）

| 自研点 | 价值 | microsphere 现状 | polygateway 现状 |
|---|---|---|---|
| 修复 G1（滚动发布端点缓存不更新） | 有对比、有说服力 | 采样单实例 + 刷新面窄 | ✅ 并集 + 心跳刷新（32 测试含 E2E） |
| 静默 200 → 明确 404 | 排障友好 | 未命中放行 | ✅ 未命中 404 |
| metadata 长度优化 | 大端点集场景 | G11 缺口 | 未做（同缺口） |
| Zone 感知路由 | 补 TODO | 只有 `// TODO support ZonePreferenceFilter` | 未做 |
| WebFlux provider 收集 | 双栈覆盖 | ✅ 已支持 | Roadmap |

### 6.3 面试答法模板

> "我们的网关参考了 microsphere 的**端点级路由**思路——官方 SCG 的自动路由只到**服务级**（路径原样透传），手写路由又要每服务维护。方案是**服务把端点元数据发布到注册中心，网关自动匹配**，零配置 + 端点级控制。
> 但它的实现有个硬伤：**滚动发布时端点缓存不更新**（新接口静默返回 200）。根因我们定位到两点：**读 metadata 只采样单个实例**、**刷新事件面窄且官方心跳刷新被拦截**。
> 我们自研了一版（polygateway）：**全实例并集 + 每次心跳强制 `RefreshRoutesEvent`**，并用桩注册中心 + 真实下游的 E2E 测试锁住行为（新增端点下一拍即生效、未命中明确 404、excludes 不生成路由）。
> 这个改动让我们既拿到端点级路由的收益，又避免了已知问题。"

---

## 7. 生产使用前的缺口清单

| # | 缺口 | 后果 | 谁有 |
|---|---|---|---|
| **G1** | 滚动发布端点缓存不更新 | 新接口未命中 → 静默 200，需重启网关/手动刷新 | microsphere；polygateway 已修 |
| G11 | metadata 长度墙 | 端点数多时实例 metadata 过大 | 两者均有（协议同源） |
| — | **静默 200** | 未命中不返回 404，排障困难 | microsphere；polygateway 为 404 |
| — | 同区域耗尽不 fallback | 启用 zone 后同区域实例耗尽即失败（与 L42 容灾缺口一致） | 两者均未做 zone 感知 |
| — | 否定条件表达 | `!debug` / `name!=value` 无法落进官方谓词 → 跳过 | polygateway；microsphere 用 RequestMappingInfo 原生支持 |
| — | provider 栈覆盖 | WebFlux 业务应用收集未支持 | polygateway（Roadmap）；microsphere 双栈 |

---

## 8. ⚠️ 常见误解：microsphere-gateway 不做 Zone 路由

**交接文档曾写"16 做网关层 Zone 路由"，源码验证证明不成立**：

- 主代码 `grep -ri zone` 只命中**两处 TODO 注释**：
  - `WebEndpointMappingGlobalFilter.java:275` → `// TODO support ZonePreferenceFilter`
  - `WebEndpointMappingHandlerFilterFunction.java:164` → `// TODO support ZonePreferenceFilter`
- git 全历史（352 提交）中除测试配置的 Eureka `defaultZone` 外，**没有任何 Zone 路由实现**

**网关层 Zone 优先的真实来源**：**microsphere-multiactive（17）的全局 LoadBalancer 配置**
`CustomizedLoadBalancerAutoConfiguration`（`@LoadBalancerClients(defaultConfiguration=...)`）对**包括网关在内的所有 LB 客户端**生效；4 个门控条件：引入 multiactive-spring-cloud + `microsphere.spring.cloud.loadbalancer.customized=true` + `spring.cloud.loadbalancer.configurations=optimized-zone-preference` + `ZoneContext.preferenceEnabled=true`。

**polygateway 侧**：同样不做 Zone（LB 交给官方，zone 策略由 LoadBalancer 配置决定）——两者在这点上都"不自己实现，依赖 LB 层"。

---

## 9. Self-Test

1. microsphere 用什么机制把官方 `FilteringWebHandler` 换成自己的子类？为什么这个时机（`BeanDefinitionRegistryPostProcessor`）能生效？
2. `WebEndpointMappingGlobalFilter` 的 order 为什么必须是 `LOAD_BALANCER_CLIENT_FILTER_ORDER - 1`（10149）？如果改成 LB 之后（10151）会发生什么？
3. G1 的根因有哪两个？polygateway 分别怎么修？
4. polygateway 生成的路由 path 为什么是 `/{serviceId}{contextPath}{pattern}` 而 filter 只用 `StripPrefix=1`？
5. 官方 SCG 4.1.5 为什么没有 Consumes/Produces 谓词？polygateway 怎么补的、怎么被发现的？

<details>
<summary>参考答案</summary>

1. `FilteringWebHandlerBeanDefinitionRegistryPostProcessor#postProcessBeanFactory`：按类型找到官方 BeanDefinition → `removeBeanDefinition` → 用同一 bean 名注册 `CachingFilteringWebHandler`。该后处理发生在普通 Bean 实例化之前，所以任何注入方拿到的都是替换后的实现。
2. 它要在官方 LB 过滤器**之前**手动 `choose()` 并把 `GATEWAY_REQUEST_URL_ATTR` 从 `we://` 改写成 `http://ip:port/...`；官方 LB 只处理 `lb://`，看到 http 就跳过。若排在 LB 之后，`lb://` 已被解析（或 we:// 无人处理），改写无从谈起。
3. 根因①读实例 metadata 只 `choose()` 采样单个实例（非并集）；根因②刷新依赖 4 类事件且官方心跳刷新被 `DisabledHeartbeatEventRouteRefreshListenerInterceptor` 拦截，metadata 变化不触发。polygateway：`LinkedHashSet.addAll` 全实例并集 + `该监听每个 HeartbeatEvent 都发 RefreshRoutesEvent` 强制重算。
4. `/{serviceId}` 前缀让服务间路由天然隔离、可直接 `lb://serviceId`；`StripPrefix=1` 只剥这一级，contextPath 与端点 pattern 原样传给下游（E2E 断言 `/api/orders`）。
5. 官方内置谓词只有 Path/Method/Host/RemoteAddr/Header/Query/Cookie/After/Before/Between/Weight/ReadBody/XForwardedRemoteAddr（jar 实测枚举），没有按请求 Content-Type/Accept 过滤的谓词。polygateway 写 `ConsumesRoutePredicateFactory`/`ProducesRoutePredicateFactory`，Spring 按 bean 名去掉 `RoutePredicateFactory` 后缀发现谓词名 `Consumes`/`Produces`。

</details>

---

## 10. 关联

- **L43**（microsphere-gateway 扩展实践）——本文
- **L44**（Gateway × Nacos：`DiscoveryClientRouteDefinitionLocator` 是官方"服务级自动路由"，正好与本文"端点级"对比）
- **L45**（Gateway × Sentinel）
- **L49**（多实例部署：G1 缺口与滚动发布相关）
- polygateway 项目：`/data/workspace/source-code/poly/polygateway/`（Poly 系列五工程之一，4 模块 / 15 主文件 / 32 测试；`mvn -B test` 全绿）
- 素材：`docs/plan/framework/source-analysis/关联项目/microsphere-gateway源码分析规划.md`
- 训练营：`microsphere-analysis/16-microsphere-gateway-analysis/`（8 篇）+ `17-microsphere-multiactive-analysis/`
