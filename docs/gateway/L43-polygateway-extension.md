# L43 polygateway：SCG 端点级路由扩展实践

> 用途：回答"**怎么给 Spring Cloud Gateway 增加"端点级路由"能力**"、"**一个生产可用的网关扩展项目长什么样**"
> 对应课程：**L43**
> 项目：**Poly 系列工程 `poly/polygateway/`**（本机 `/data/workspace/source-code/poly/polygateway/`），4 模块 15 个主 Java 文件 + 32 个测试
> 口径：JDK 17.0.20.1 / Spring Boot 3.2.5 / Spring Cloud 2023.0.3（SCG 4.1.5）/ SCA 2023.0.1.2 / Nacos 2.2.3；**32 个进程内测试全绿 + 真实 Nacos 环境实测**（2026-10-05，实验室 `polygateway-lab/`）

---

## 0. 例子先行：一次请求发生了什么

场景（真实实验室拓扑）：`order-service` 是普通 Spring MVC 应用（`server.servlet.context-path=/api`），有 5 个端点：

| 端点 | 方法 | 说明 |
|---|---|---|
| `/orders/{id}` | GET | 查订单 |
| `/orders/special` | GET | 特殊订单（字面路径） |
| `/orders/{id}/lines` | GET | 订单明细 |
| `/orders` | POST（consumes=application/json） | 建订单 |
| `/internal/reconcile` | POST | 内部对账，**不暴露给网关** |

请求：`GET http://localhost:8180/order-service/api/orders/42`

**官方两条路线只能做到服务级**：
- 手写路由：每个服务/每条接口手工维护 `Path` 规则；
- `discovery.locator.enabled=true`：自动生成 `/order-service/** → lb://order-service`——**网关并不知道下游有哪些端点**，`/order-service/api/unknown` 也会被转发（由下游 404 决定），`/internal/reconcile` 同样被暴露。

**polygateway 的完整流程**：

1. **服务启动**：`WebEndpointMappingsCollector` 从 `RequestMappingHandlerMapping` 采集 5 个端点 → `WebEndpointMappingCodec` 序列化为 JSON → 在**实例注册前**写入实例 metadata：
   `polygateway.web-endpoints=[{kind,id,patterns,methods,params,headers,consumes,produces}...]`、`polygateway.context-path=/api`；
2. **网关生成路由**：`WebEndpointRouteDefinitionLocator` 从注册中心读**全实例并集**，为每个端点生成一条**官方 `RouteDefinition`**：
   `id=polygateway:order-service:4`、`uri=lb://order-service`、`Path=/order-service/api/orders/{id}`、`Method=GET`、`StripPrefix=1`、order 按"具体度"排序；
3. **请求进来**：官方 `RoutePredicateHandlerMapping` + 官方谓词链匹配（Path/Method/Consumes…）→ `lb://` 由**官方响应式 LoadBalancer** 选实例（非阻塞）→ 转发；下游收到 `/api/orders/42`（**context-path 保留**）；
4. **未命中**（如 `text/plain` POST `/orders`、或被 excludes 排除的端点）→ **不生成路由 → 404**，语义明确。

**一句话：网关从"按服务名转发"升级为"按端点清单精确匹配"；端点清单由服务自己发布到注册中心，网关零配置。**

---

## 1. 为什么需要端点级路由（与官方两条路线对比）

| 方案 | 配置成本 | 控制粒度 | 短板 |
|---|---|---|---|
| **手写路由** `lb://xxx` + `Path=/xxx/**` | 每个服务维护一份 | 服务级 + 路径 | 配置重复、易漂移 |
| **`discovery.locator.enabled=true`** | **零配置** | **服务级** | 路径原样透传——网关不知道下游有哪些端点，内部接口也会被暴露 |
| **polygateway（端点级）** | **零配置**（服务自发布） | **端点级** | 只覆盖发布端点 metadata 的服务；业务服务需要引入 SDK 依赖 |

**设计动机**：服务接口路径变更（`/v1/orders` → `/v2/orders`）时，网关无需改配置；同时能端点级控制暴露面（按 method/路径排除内部接口），未命中明确 404。

---

## 2. 设计总览

### 2.1 数据流

```text
服务 A（provider）                          注册中心              网关
  启动时收集所有 Controller 端点（六维）        │                   │
  → 序列化为 WebEndpointMapping JSON          │                   │
  → 注册前写入实例 metadata ────────────────► │                   │
      (polygateway.web-endpoints)             │ 实例列表(含metadata) ─► RouteDefinitionLocator
                                              │◄──────────────────  │ ① 全实例并集
                                              │                    │ ② 每端点一条官方路由
                                              │                    │ ③ 心跳强制重算
```

### 2.2 模块结构（4 模块 15 主文件）

| 模块 | 文件数 | 职责 |
|---|---|---|
| `polygateway-core` | 4 | `WebEndpointMapping`（六维模型）/ `WebEndpointMappingCodec`（JSON 协议）/ `WebEndpointExclusion`（排除规则）/ `PolyGatewayConstants` |
| `polygateway-provider` | 3 | `WebEndpointMappingsCollector`（采集）/ `WebEndpointMetadataPublisher`（发布）/ `PolyGatewayProviderListener`（注册前触发） |
| `polygateway-gateway` | 5 | `WebEndpointRouteDefinitionLocator`（生成路由）/ `PolyGatewayRouteRefreshListener`（心跳刷新）/ `PolyGatewayGatewayProperties` / `Consumes`+`Produces` 谓词工厂 |
| `polygateway-spring-boot` | 3 | provider / gateway 两个 `@AutoConfiguration` + 配置属性 |

### 2.3 关键设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 扩展层 | **生成期翻译**（实现 `RouteDefinitionLocator`），不做运行期自定义 filter | 官方运行时链零改动；路由对 `actuator/gateway/routes`、调试器、SCG 自身机制**完全可见**；只依赖官方公开 SPI，升级稳健 |
| 目标 URI | `lb://serviceId` | 复用官方响应式 LoadBalancer（非阻塞），不自己选实例 |
| 匹配器 | 官方谓词 + 自研 `Consumes`/`Produces` | 官方内置谓词缺这两个（4.1.5 jar 实测枚举）；其余五维直接用官方 |
| 实例 metadata 读取 | **全实例并集**（`LinkedHashSet.addAll`） | 滚动发布/金丝雀期间，只在部分实例上出现的端点也不丢 |
| 路由刷新 | **每次心跳强制 `RefreshRoutesEvent`** | 不依赖"心跳值变化"启发式；metadata 变更（新端点、上下线）下一拍即生效 |
| metadata 发布时机 | **注册前**（`InstancePreRegisteredEvent`） | 实例一可见，网关就能读到端点，避免"先看到空实例" |
| 排除 | 生成期跳过 + 可按服务限定（`services`） | 被排除端点**路由表里根本不存在**；服务间同名端点策略可以不同 |
| 路由 id | `polygateway:{serviceId}:{endpointId}`，endpointId 由采集排序确定 | 同一端点集永远得到同一组 id，路由不抖动 |

---

## 3. provider 侧：采集与发布（源码）

### 3.1 六维协议模型

`WebEndpointMapping` 描述一个端点的六维可达条件：`patterns / methods / params / headers / consumes / produces`（外加 `id` 与 `kind`）。`WebEndpointMappingCodec` 用 Jackson 把它序列化成 JSON 数组——这就是实例 metadata 里的协议内容（schema-free，字段即维度）。

### 3.2 采集：WebEndpointMappingsCollector

`polygateway-provider/.../WebEndpointMappingsCollector.java`：

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

- 数据源是 **`RequestMappingHandlerMapping`**：Spring MVC 已解析好的六维条件直接可取，不需要自己解析注解；
- **确定性排序 + 位置 id**：id 只取决于端点集内容 → 网关路由 id 稳定，滚动发布不抖动。

### 3.3 发布：注册前写入 metadata

`WebEndpointMetadataPublisher.java`：

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
        publisher.publish(event.getRegistration());   // 注册前写 metadata
    }
}
```

**顺序很关键**：metadata 必须在实例注册**之前**写入，否则网关可能先看到一个没有端点信息的空实例。

### 3.4 provider 自动配置（要点）

`PolyGatewayProviderAutoConfiguration`：`@ConditionalOnClass({RequestMappingHandlerMapping, InstancePreRegisteredEvent})` + `@ConditionalOnWebApplication(SERVLET)`（**目前只支持 Spring MVC**）+ `polygateway.provider.enabled`（默认 true）；注册 Collector / Publisher / Listener 三个 Bean。

---

## 4. gateway 侧：路由生成与刷新（源码）

### 4.1 核心：WebEndpointRouteDefinitionLocator

`polygateway-gateway/.../WebEndpointRouteDefinitionLocator.java`：

```java
public List<RouteDefinition> loadServiceRoutes(String serviceId) {
    List<ServiceInstance> serviceInstances = discoveryClient.getInstances(serviceId);
    if (CollectionUtils.isEmpty(serviceInstances)) {
        return List.of();
    }
    Set<WebEndpointMapping> endpoints = new LinkedHashSet<>();      // 去重
    String contextPath = null;
    for (ServiceInstance serviceInstance : serviceInstances) {
        Map<String, String> metadata = serviceInstance.getMetadata();
        String endpointsJSON = metadata.get(PolyGatewayConstants.WEB_ENDPOINTS_METADATA_NAME);
        if (StringUtils.hasText(endpointsJSON)) {
            try {
                endpoints.addAll(WebEndpointMappingCodec.decode(endpointsJSON));   // ⚠️ 全实例并集
            } catch (RuntimeException e) {
                logger.warn("The web endpoints metadata of the service instance [{}] can't be decoded : {}",
                        serviceInstance.getInstanceId(), e.getMessage());
            }
        }
        if (contextPath == null) {
            contextPath = normalizeContextPath(metadata.get(PolyGatewayConstants.CONTEXT_PATH_METADATA_NAME));
        }
    }
    ...
    for (WebEndpointMapping endpoint : endpoints) {
        if (isExcluded(serviceId, endpoint)) { continue; }
        routeDefinitions.add(createRouteDefinition(serviceId, contextPath, endpoint));
    }
    return routeDefinitions;
}

protected RouteDefinition createRouteDefinition(String serviceId, String contextPath, WebEndpointMapping endpoint) {
    RouteDefinition routeDefinition = new RouteDefinition();
    routeDefinition.setId(PolyGatewayConstants.ROUTE_ID_PREFIX + serviceId + ":" + endpoint.getId());  // polygateway:order-service:1
    routeDefinition.setUri(URI.create("lb://" + serviceId));       // 官方响应式 LB，非阻塞
    routeDefinition.setOrder(routeOrder(endpoint));                // 越具体越优先
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
- 路径 = `/{serviceId}{contextPath}{pattern}`：每个服务天然占一个前缀，**服务间不会路由冲突**；`StripPrefix=1` 只剥服务名，**context-path 与端点路径原样转发**给下游；
- 官方谓词承载六维：`Path`、`Method`，以及 `Header`/`Query`（`addConditionPredicate`）、`Consumes`/`Produces`（§4.2 自研）；字面值用 `Pattern.quote` 转义，`!debug` / `name!=value` 这类否定条件官方谓词表达不了 → 跳过并打日志（端点保持可达）；
- **全实例并集**：金丝雀实例独有的端点也生成路由；
- **route order 公式**（越具体越优先）：

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
// /orders/{id} -> -3；/orders/special -> -4：数值小者（更具体）先匹配
```

### 4.2 官方缺的谓词：Consumes / Produces

本机 `spring-cloud-gateway-server-4.1.5.jar` 内置谓词（`jar tf` 实测枚举）：`After/Before/Between/Cookie/Header/Host/Method/Path/Query/ReadBody/RemoteAddr/Weight/XForwardedRemoteAddr`——**没有 Consumes/Produces**。端点六维要完整落成官方谓词，只能自己写。`ConsumesRoutePredicateFactory`（`Produces` 同构）：

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

Spring 按 bean 名（去掉 `RoutePredicateFactory` 后缀）发现谓词：`Consumes` / `Produces` 即可写进 `PredicateDefinition`。

### 4.3 刷新：每次心跳强制重算

`PolyGatewayRouteRefreshListener.java`（全文）：

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

- 官方 `RouteRefreshListener` 只在**心跳值变化**时刷新（启发式）；polygateway 不依赖启发式——**每次心跳都发 `RefreshRoutesEvent`**，`RouteDefinitionLocator` 重新从当前 discovery 状态读全实例并集 → 新端点/上下线下一拍生效（实测见 §5.3）；
- 代价：每次心跳全量重建路由列表（中小规模可接受；大规模需要增量/节流，属后续优化）。

### 4.4 排除：生成期 + 服务作用域

`WebEndpointExclusion` 在**生成期**对端点声明的六维匹配（命中即不生成路由）；2026-10-05 起支持 **`services` 维度**（精确匹配，不填 = 所有服务）：

```java
public boolean matches(String serviceId, WebEndpointMapping mapping) {
    if (isEmpty(patterns)) {
        return false;               // 无 patterns 视为无规则
    }
    return servicesMatch(serviceId)
            && anyPatternMatches(mapping.getPatterns())
            && methodsMatch(mapping.getMethods())
            && declaredMatch(mapping.getParams(), params)
            && declaredMatch(mapping.getHeaders(), headers)
            && intersects(mapping.getConsumes(), consumes)
            && intersects(mapping.getProduces(), produces);
}

private boolean servicesMatch(String serviceId) {
    if (isEmpty(services)) {
        return true;                // 不填 = 所有服务
    }
    return serviceId != null && contains(services, serviceId);
}
```

```properties
# 只排除 pay-service 的内部端点；order-service 的同名端点不受影响
polygateway.gateway.excludes[0].services=pay-service
polygateway.gateway.excludes[0].patterns=/internal/**
polygateway.gateway.excludes[0].methods=POST
```

### 4.5 gateway 自动配置（要点）

`PolyGatewayGatewayAutoConfiguration`：`@ConditionalOnClass({RouteDefinitionLocator, DiscoveryClient})` + `polygateway.gateway.enabled`（默认 true）；注册 Locator / `Consumes`+`Produces` 谓词 / RefreshListener 四个 Bean。网关应用须显式引入 `spring-cloud-starter-loadbalancer`（`lb://` 路由使用）。

---

## 5. 测试与实测证据

### 5.1 进程内测试：32 个（全绿）

命令（cwd `poly/polygateway/`）：`JAVA_HOME=/env/javaEnv/jdk17 mvn -B test`

| 模块 | 测试数 | 代表测试 |
|---|---|---|
| core | 15 | `WebEndpointExclusionTest`(8) / `WebEndpointMappingCodecTest`(4) / `WebEndpointMappingTest`(3) |
| provider | 4 | `WebEndpointMappingsCollectorTest`(2) / `WebEndpointMetadataPublisherTest`(2) |
| gateway | 9 | `WebEndpointRouteDefinitionLocatorTest`(6) / `RoutePredicateFactoriesTest`(2) / `PolyGatewayRouteRefreshListenerTest`(1) |
| spring-boot | 4 | `PolyGatewayAutoConfigurationTest`(3) / **`PolyGatewayGatewayE2ETest`(1)** |

### 5.2 全链路 E2E（桩注册中心 + 真实 HTTP 下游 + WebTestClient）

`PolyGatewayGatewayE2ETest` 关键断言（两个服务：order-service 被按服务排除，audit-service 同名端点保留）：

```java
assertEquals("/api/orders", downstream.lastPathOf("POST"));                  // StripPrefix 后保留 context-path /api
webClient.post().uri("/order-service/api/orders")
        .contentType(MediaType.TEXT_PLAIN).bodyValue("x")
        .exchange().expectStatus().isNotFound();                            // Consumes 不匹配 -> 404
webClient.post().uri("/order-service/api/internal/reconcile")
        .exchange().expectStatus().isNotFound();                            // excludes（按服务限定）命中 -> 404
webClient.post().uri("/audit-service/api/internal/reconcile")
        .exchange().expectStatus().isOk();                                  // 同名端点：规则限定 order-service，audit 保留
// 刷新证明：metadata 里新增端点后，发一个心跳即可路由
discoveryClient.setInstances(SERVICE_ID, List.of(instance(SERVICE_ID, downstream.port(), "/api", refreshed)));
eventPublisher.publishEvent(new HeartbeatEvent(this, "refreshed"));
awaitUntil(() -> "GET /api/orders/42/lines".equals(...));
```

### 5.3 真实环境实测（Nacos + 真实应用）

实验室：`polygateway-lab/`（xhs-demo 下，独立 POM）；拓扑：lab-provider:8181（MVC + context-path `/api`）+ lab-audit:8182（**同一 jar 换服务名**）+ lab-gateway:8180（SCG + Nacos + LB + polygateway）；真实 Nacos 2.2.3。

| # | 请求（经 8180） | 实测 | 证明点 |
|---|---|---|---|
| 1 | `GET /lab-provider/api/orders/42` | 200 `GET /api/orders/42` | 端点级路由 + `StripPrefix=1` + context-path 保留 |
| 2 | `GET /lab-provider/api/orders/special` | 200 | **更具体路由优先**（`/orders/special` 胜 `/orders/{id}`） |
| 3 | `POST /lab-provider/api/orders`（json） | 200 `POST /api/orders` | `Consumes` 谓词 |
| 4 | `POST /lab-provider/api/orders`（text/plain） | **404** | Consumes 不匹配 → 无路由 |
| 5 | `POST /lab-provider/api/internal/reconcile` | **404** | **服务作用域排除**命中 lab-provider |
| 6 | `POST /lab-audit/api/internal/reconcile` | 200 `POST /api/internal/reconcile` | **同名端点在其他服务保留** |
| 7 | `GET /lab-provider/api/unknown` | 404 | 无路由 → 404 |

- **心跳刷新真实成立**：gateway 日志周期出现 `Loaded 9 endpoint route definition(s)`（`9 = 5 + 5 − 1`，减掉被服务作用域排除的那条）；
- **Nacos metadata 实见**：`polygateway.web-endpoints`（六维 JSON）+ `polygateway.context-path=/api`。

### 5.4 真实环境暴露并修复的两个集成问题（in-process 测试覆盖不到）

1. **SCG 传递依赖穿透**：`polygateway-gateway` 把 `spring-cloud-starter-gateway` 声明为非 optional → MVC provider 加 `polygateway-spring-boot` 即启动失败（`Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway`）。修复：两个模块的 SCG/LoadBalancer 均改 `<optional>true</optional>`；provider fat-jar 中 SCG 依赖数 1→0、体积 52MB→33MB（gateway fat-jar 保留）；
2. **SCA 2023 的 HeartbeatEvent 是条件创建**：`NacosDiscoveryHeartBeatPublisher` 三选一（`spring.cloud.nacos.discovery.heart-beat.enabled=true` / `discovery.locator.enabled=true` / Spring Boot Admin）。polygateway 用户通常会关 locator（避免官方服务级路由）→ 心跳不发布 → **刷新静默失效**；须显式打开 `heart-beat.enabled`（lab 已配置）。

> 运行/停止与复现细节：`polygateway-lab/README.md`。

---

## 6. 边界与已知限制（如实）

**排除语义是"整端点"粒度**（生成期跳过），有两个真实限制：

1. **多方法端点不能只排一个方法**：一个 mapping 声明 `methods={GET,POST,DELETE}` 时，规则 `methods=DELETE` 会把**整条路由**干掉；只声明 `{GET,POST}` 时规则 `DELETE` 完全不命中（交集为空）；
2. **端点未声明的 header/param 不能作排除条件**：`declaredMatch` 要求规则值是端点声明值的子集——做不了"拦截所有带 `X-Internal` 头的请求"这类**请求特征防火墙**。

> 需要请求级拦截时：在路由生成之后另挂 guard filter / 自定义 predicate，**不要塞进路由生成**（生成期只做暴露策略，语义才可控）。

**其他边界**：

| 边界 | 说明 |
|---|---|
| 单 DiscoveryClient | 多注册中心客户端并存时 LB 拿到的实例列表不可预期（README 已注明）；生产单注册中心天然满足 |
| metadata 长度 | 端点数多时实例 metadata 会变大（协议本身无压缩） |
| provider 仅 Spring MVC | WebFlux 业务应用收集在 Roadmap（自动配置有 `SERVLET` 条件） |
| 无 zone 感知 | 区域路由交给官方 LoadBalancer 配置（LB 是官方扩展点），本项目不重复实现 |
| 刷新依赖心跳 | 无心跳发布的注册中心实现需自行发 `HeartbeatEvent`；**Nacos/SCA 2023 须显式 `spring.cloud.nacos.discovery.heart-beat.enabled=true`** |
| 未命中语义 | 无路由 = **404**（明确；不会静默放行） |

---

## 7. 面试与简历怎么讲

### 7.1 30 秒项目陈述

> "我在网关旁做了一套**端点级路由**扩展（polygateway）：业务服务启动时把 Spring MVC 端点（路径/方法/参数/头/consumes/produces 六维）发布到注册中心实例 metadata；网关用 `RouteDefinitionLocator` 把它们翻译成**官方路由**（`lb://` + 官方谓词 + `StripPrefix=1`），做到零配置、精确到端点。
> 设计上重点是三点：**全实例并集**（滚动发布中途端点不丢）、**每次心跳强制重算**（新端点下一拍生效）、**排除可按服务限定**（内部接口路由表里根本不存在，404 明确）。
> 32 个进程内测试（含全链路 E2E）+ 真实 Nacos 端到端实测；实测过程中还发现并修复了两个只有真实环境才暴露的集成问题。"

### 7.2 高频追问与答法

| 追问 | 答法 |
|---|---|
| **为什么用 `RouteDefinitionLocator` 而不是自定义 `GlobalFilter`？** | 生成期翻译成官方路由：运行时链零改动，官方谓词/LB/`actuator`/调试全部复用，升级稳健；运行期 filter 要自己匹配、自己选实例，等于绕过官方 LB，官方一重构就打穿 |
| **路由按服务批量生成，性能怎样？** | SCG 本就是按 order 逐个谓词匹配，我们把更具体的端点排前面；路由条数随端点线性增长，后台服务规模可控；更大规模可以按前缀分组索引，是后续优化点 |
| **滚动发布中途，新老实例 metadata 不一致怎么办？** | 读 metadata 是**全实例并集**，只在金丝雀实例上的端点也生成路由 |
| **新端点多久生效？** | 心跳周期（Nacos 默认约 30s）：每次心跳强制 `RefreshRoutesEvent` 重算，不依赖"心跳值变化"启发式 |
| **官方没有 Consumes/Produces 谓词怎么办？** | 自研两个 `RoutePredicateFactory`（bean 名自动发现）；内置谓词清单用 `jar tf` 实测确认过缺口 |
| **内部接口怎么不暴露？** | 生成期排除：命中规则的端点**没有路由**（404）；规则可按 `services` 限定；请求级特征拦截另挂 guard filter |
| **Zone/多活怎么做？** | 不做，交给 LoadBalancer 配置（官方扩展点）；网关侧不重复造 |

### 7.3 可写进简历的亮点

- 端点级路由：**零配置 + 六维精确匹配 + 404 语义**（对比官方 `discovery.locator` 只到服务级、手写路由要维护）
- **滚动发布正确性**：全实例并集 + 每次心跳强制刷新（含真实 Nacos 实测 `Loaded 9` 证据）
- **官方 SPI 扩展，运行时零侵入**：`RouteDefinitionLocator` + `RoutePredicateFactory` + 事件监听
- **服务作用域排除**：同名端点跨服务差异化策略（配置 `excludes[n].services`）
- **测试与真实环境闭环**：32 测试（含全链路 E2E）+ 真实验证中发现并修复 2 个集成问题（SCG 传递依赖、Nacos 心跳条件）

---

## 8. Self-Test

1. polygateway 为什么选择"生成期翻译成 `RouteDefinition`"而不是运行期自定义 `GlobalFilter`？两种姿势各自的代价是什么？
2. 读实例 metadata 为什么必须**全实例并集**？只读一个实例会出什么问题？
3. 为什么"每次心跳强制重算"比"等心跳值变化"更适合端点路由？Nacos/SCA 下要配什么？
4. 生成的 route path 为什么是 `/{serviceId}{contextPath}{pattern}`，而 filter 只需要 `StripPrefix=1`？
5. 官方谓词缺哪个/哪些？怎么补？`services` 维度解决什么问题？

<details>
<summary>参考答案</summary>

1. 生成期翻译：官方运行时链零改动，谓词/LB/actuator/调试全部复用，只依赖官方公开 SPI（升级稳健）；代价是路由条数随端点线性增长、否定条件受官方谓词表达力限制。运行期 filter：灵活但自己匹配、自己选实例（绕过官方 LB），依赖框架内部行为，官方一重构容易打穿。
2. 滚动发布/金丝雀期间，新老实例的 metadata 不同；只读一个实例会随机拿到旧集合 → 新端点缺失（或旧端点缺失），且每次刷新结果不可预期。并集保证任何实例暴露过的端点都有路由。
3. 端点集变化不一定改变"心跳值"，等变化可能永远不刷新；每次心跳强制发 `RefreshRoutesEvent` 保证 metadata 变更下一拍生效。Nacos/SCA 2023 下须显式 `spring.cloud.nacos.discovery.heart-beat.enabled=true`，否则 HeartbeatEvent 发布器根本不会创建。
4. `/{serviceId}` 前缀让服务间路由天然隔离、可直接 `lb://serviceId`；`StripPrefix=1` 只剥这一级，contextPath 与端点路径原样传给下游。
5. 官方 4.1.5 内置谓词没有 `Consumes`/`Produces`（`jar tf` 枚举确认）；自研两个 `RoutePredicateFactory`，Spring 按 bean 名（去掉后缀）自动发现。`services` 维度让排除规则只作用于指定服务——同名端点在不同服务可以有不同暴露策略。

</details>

---

## 9. 关联

- **L19**（路由来源链：`RouteDefinitionLocator` 家族正是本项目的扩展点）/ **L21**（RouteDefinition → Route）/ **L23**（谓词匹配）/ **L25**（过滤器链）
- **L44**（Gateway × Nacos：官方"服务级"自动路由，正好与本文"端点级"对比）/ **L45**（Gateway × Sentinel）/ **L49**（多实例部署：滚动发布与心跳刷新相关）
- 项目：`source-code/poly/polygateway/`（README 含配置表与"刷新依赖心跳"的 Nacos 配置说明）
- 真实环境实验室：`source-code/xhs-demo/polygateway-lab/`（拓扑/运行/停止/矩阵/两个集成问题，见其 README）
