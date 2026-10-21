# PolyGateway 吃透指南（源码地图 + 全链路 + 动手路径）

> 对象：`/data/workspace/source-code/poly/polygateway`（4 模块 / 16 主类 / 42 测试，2026-10 全绿）
> 代码：https://github.com/LoveEleve/poly-project/tree/main/polygateway
> 用法：按 §7 的路径学；每节末尾的一句话总结就是"能讲出来的版本"。

---

## 1. 它解决什么问题（先看例子）

**官方 Spring Cloud Gateway 的两条路由路线都有缺口**：
- 手写 `lb://order-service` + `Path=/order-service/**`：每个服务都要在网关维护一份，服务改路径网关跟着改；
- 开 `discovery.locator`：自动给每个服务生成 `lb://` 路由，但**只到服务级**——路径原样透传，网关不知道下游有哪些接口，`/order-service/internal/reconcile`（内部对账接口）也会被原样转发出去。

**PolyGateway 的做法**：服务启动时把自己的 HTTP 接口清单写进注册中心元数据，网关据此**自动生成接口级路由**：

```
订单服务两个接口：
  GET  /orders/{id}          （对外）
  POST /internal/reconcile   （内部，不想暴露）

接入后网关零配置：
  GET  /order-service/api/orders/42   → 200  转发到 http://实例:8080/api/orders/42
  POST /order-service/api/internal/reconcile → 404（excludes 规则，根本没有生成这条路由）
  POST /order-service/api/orders (Content-Type: text/plain) → 404（媒体类型不匹配）
```

一句话总结：**"配置零维护"与"接口级精确控制"不再是二选一**——接口清单由服务自己发布，网关自动变精确。

---

## 2. 全链路：两条数据流

### 2.1 Provider 侧（启动时发布接口清单）

```
Spring MVC 应用启动
  ① 注册前事件 InstancePreRegisteredEvent 触发
     PolyGatewayProviderListener → WebEndpointMetadataPublisher.publish()
  ② WebEndpointMappingsCollector 扫描所有 RequestMappingHandlerMapping
     每个 RequestMappingInfo → 六维 WebEndpointMapping（路径/方法/参数/请求头/Content-Type/Accept）
     过滤 org.springframework.boot.* 基础设施接口（actuator、error controller）
     按六维确定性排序 → 由六维内容哈希生成 id（同一接口跨实例一致，接口集不同也不漂移）
  ③ WebEndpointMappingCodec.encode() → JSON 数组
  ④ 写入实例 metadata：
     polygateway.web-endpoints = [{"patterns":["/orders/{id}"],"methods":["GET"],...}]
     polygateway.context-path   = /api   （来自 server.servlet.context-path）
  ⑤ 注册中心持有带接口清单的实例
```

### 2.2 Gateway 侧（每次请求的旅程）

以 `GET http://gateway/order-service/api/orders/42` 为例，下游 context-path 为 `/api`：

```
① SCG 的 RoutePredicateHandlerMapping 取路由列表
   （CachingRouteLocator 缓存，首次来自我们的 WebEndpointRouteDefinitionLocator）
② 逐条匹配谓词（顺序 = routeOrder 排序后的顺序）：
   Path=/order-service/api/orders/{id}   ✓（{id} 匹配 42）
   Method=GET                            ✓
   （Header/Query/Consumes/Produces 见 §4.1，未配就不生成）
③ URI = lb://order-service → ReactiveLoadBalancerClientFilter
   官方响应式负载均衡按 DiscoveryClient 选一个实例（不阻塞事件循环）
④ StripPrefix=1：剥掉第一段 /order-service
   /order-service/api/orders/42 → /api/orders/42
⑤ NettyRoutingFilter 转发 → http://实例:8080/api/orders/42
⑥ 下游 Spring MVC：context-path /api + Controller 映射 /orders/{id} → 命中，回 "GET /api/orders/42"
⑦ 无路由匹配时 → SCG 默认 404（我们的"隐藏接口"就是靠这个语义）
```

一句话总结：**网关做的事 = 把"接口清单"翻译成"标准路由"，之后全部交给 SCG 官方机制执行。**

---

## 3. 代码地图：16 个主类

| 模块 | 类 | 一句话职责 | 关键方法 |
|---|---|---|---|
| core | `WebEndpointMapping` | 六维接口模型 + 由维度计算的稳定 id | Builder、`stableId()` |
| core | `WebEndpointMappingCodec` | 接口清单的 JSON 编解码（跨进程协议） | `encode(list)` / `decode(json)` |
| core | `WebEndpointExclusion` | 排除规则（六维 AND + services 域） | `matches(serviceId, mapping)` |
| core | `PolyGatewayConstants` | 元数据键 / 路由 id 前缀常量 | `WEB_ENDPOINTS_METADATA_NAME` 等 |
| provider | `WebEndpointMappingsCollector` | 扫描 MVC HandlerMapping → 六维模型，排序 + 内容稳定 id | `collect(context)` |
| provider | `WebEndpointMetadataPublisher` | 注册前把清单 + context-path 写进实例 metadata | `publish(serviceInstance)` |
| provider | `PolyGatewayProviderListener` | 监听注册前事件触发发布 | `onApplicationEvent()` |
| gateway | `WebEndpointRouteDefinitionLocator` | **核心**：读全实例元数据 → 排序 → 生成 lb:// 路由 | `loadRouteDefinitions()` / `createRouteDefinition()` |
| gateway | `PolyGatewayFilterRule` | 按服务/路径/方法给路由追加官方过滤器（限流/重试等） | `matches()` + locator 的 `applyFilterRules()` |
| gateway | `ConsumesRoutePredicateFactory` | Content-Type 匹配谓词（官方没有） | `apply(config)` |
| gateway | `ProducesRoutePredicateFactory` | Accept 匹配谓词（Accept 为空 = 通配） | `apply(config)` |
| gateway | `PolyGatewayRouteRefreshListener` | 心跳 → 强制重算路由 | `onApplicationEvent(HeartbeatEvent)` |
| gateway | `PolyGatewayGatewayProperties` | `polygateway.gateway.*` 配置（enabled/excludes/filter-rules） | `@ConfigurationProperties` |
| boot | `PolyGatewayProviderAutoConfiguration` | Provider 侧条件装配（SERVLET Web + 开关） | 3 个 @Bean |
| boot | `PolyGatewayGatewayAutoConfiguration` | Gateway 侧条件装配（有 DiscoveryClient + 开关） | 4 个 @Bean |
| boot | `PolyGatewayProviderProperties` | `polygateway.provider.enabled` | — |
| boot | `META-INF/spring/...AutoConfiguration.imports` | 两个自动配置类的注册 | Boot 3 标准机制 |

测试分布：core 16 / provider 5 / gateway 16 / spring-boot 5（全绿）。

一句话总结：**core 只有模型和协议、provider 只管"发"、gateway 只管"生成路由"、boot 只管"装配"——四层单向依赖，没有一处互相越界。**

---

## 4. 关键机制深挖

### 4.1 六维 → SCG 谓词的映射（核心转换表）

| 端点维度 | 生成的谓词 | 编码细节 |
|---|---|---|
| patterns | `Path=/{service}{ctx}/{p1},{p2}` | 拼服务前缀；多 pattern 用逗号（GATHER_LIST 快捷式） |
| methods | `Method=GET,POST` | 空则不生成（任意方法都通） |
| params `name=value` | `Query` 谓词，args: `param=name, regexp=Pattern.quote(value)` | **字面值转义**：`1.0` 不会误匹配 `1x0` |
| params `name`（仅存在） | `Query` 谓词，只有 `param=name` | 存在即匹配 |
| headers 同 params | `Header` 谓词，args: `header=name, regexp=...` | 同上 |
| `!name` / `name!=value` | **跳过** + debug 日志 | 官方谓词无法表达取反，宁可松不可错 |
| consumes | `Consumes=`（自研） | Content-Type 兼容匹配；端点声明了才生成 |
| produces | `Produces=`（自研） | Accept 兼容匹配；**Accept 为空 = 全通配** |

两个自研谓词的存在理由：SCG 官方谓词清单里**没有** Content-Type/Accept 维度——这是"六维"里唯二必须自己实现的。工厂类名去掉 `RoutePredicateFactory` 后缀即为谓词名（SCG 命名约定），所以配置里直接写 `Consumes=application/json`。

### 4.2 路径变换公式（含边界）

```
生成：routePath = "/" + serviceId + contextPath + endpointPattern
      （contextPath 归一化：补前导斜杠、去尾斜杠，"/" 或空 → 不拼）
转发：StripPrefix=1 只剥 /{serviceId} 一层，contextPath 保留在转发路径里
```

推演（下游 context-path=`/api`）：
| 请求 | 生成的 Path 谓词 | StripPrefix 后转发 |
|---|---|---|
| `/order-service/api/orders/42` | `/order-service/api/orders/{id}` | `/api/orders/42` ✓ 正好是下游完整路径 |
| `/order-service/api/orders` (POST) | `/order-service/api/orders` | `/api/orders` ✓ |
| 下游无 context-path 时 | `/order-service/orders/{id}` | `/orders/{id}` ✓ |

两个服务都有 `/health` 不会冲突：路由分别是 `/svc-a/health` 和 `/svc-b/health`，**服务名前缀就是天然命名空间**。

一句话总结：**服务名前缀进 Path、StripPrefix 只剥它、context-path 全程保留——三行规则讲完整个路径变换。**

### 4.3 特异性排序（已验证真生效）

```
routeOrder = -(字面段数 × 2) - 变量段数 + (通配段数 × 2)
```
| 端点 | 组成 | order | 优先级 |
|---|---|---|---|
| `/orders/special` | 2 字面 | -4 | 最先 |
| `/orders/{id}` | 1 字面 1 变量 | -3 | 中 |
| `/orders/**` | 1 字面 1 通配 | 0 | 最后 |

**为什么生效**（读 SCG 字节码验证）：`RouteDefinition.order` → `Route implements Ordered#getOrder()` → `CachingRouteLocator` 用 `AnnotationAwareOrderComparator` 对路由 Flux 排序 → 越特异越先匹配。`/orders/special` 与 `/orders/{id}` 同时匹配 `/orders/special` 请求时，精确路由先赢。

### 4.4 排除规则：为什么在生成期做

`WebEndpointExclusion.matches(serviceId, endpoint)` 的 AND 组合：
- patterns（**必填**，Ant 匹配端点路径；不填 = 永不命中，防误伤全量）
- services（精确匹配服务名；空 = 所有服务——E2E 用两个服务证明了"排除 order-service 的 internal，audit-service 的同名接口照常路由"）
- methods（交集；端点未声明方法 = 任意方法 → 命中，宁多排不漏排）
- params/headers（须端点**显式声明**才命中）
- consumes/produces（交集）

**生成期 vs 请求期**：命中规则的接口**不生成路由** → 请求直接 404，零运行时开销、无隐藏转发面；规则变更后在**下一次心跳**重算时生效。

### 4.5 全实例并集（金丝雀友好）

`loadServiceRoutes` 遍历服务的**全部实例**，元数据 JSON 解码进 `LinkedHashSet`（`WebEndpointMapping` 的 equals 按六维算）：金丝雀实例独有 `/canary/orders` 也能生成路由；同一接口跨实例的 id 由内容哈希保证一致，不会因实例接口集不同而漂移；某实例元数据损坏只 warn 跳过不拖垮整体。context-path 取第一个非空的。

### 4.6 filterRules（给路由挂官方过滤器）

```yaml
polygateway.gateway:
  filter-rules:
    - services: order-service      # 空 = 所有服务
      patterns: "/orders/**"       # 空 = 所有路径
      methods: POST                # 空 = 所有方法
      filters:
        - AddResponseHeader=X-Filter-Rule,applied      # FilterDefinition 快捷串
        - name: RequestRateLimiter                     # 也可显式写法
          args:
            redis-rate-limiter.replenishRate: 10
```
匹配的端点路由在 `StripPrefix=1` **之后**追加这些 filter；E2E 断言了"只有 order-service 的 /orders/** 路由带响应头，audit-service 没有"。

### 4.7 心跳刷新链（6 步）

```
注册中心心跳 → HeartbeatEvent
  → PolyGatewayRouteRefreshListener 发布 RefreshRoutesEvent（无条件，不玩"心跳值变没变"的启发式）
  → CachingRouteLocator 清缓存、重新调用所有 RouteDefinitionLocator
  → 我们的 locator 重新 discoveryClient.getInstances()（无内部缓存，每次现算）
  → 新接口清单 → 新路由 → 新端点立即可访问
```
E2E 验证：往元数据追加 `/orders/{id}/lines` → 发一次心跳 → 请求 200。
（反面：如果某个注册中心不发心跳，需要自己发 `HeartbeatEvent`/`RefreshRoutesEvent`——README 已注明。）

---

## 5. 与 Spring Cloud Gateway 的关系（接入点与取舍）

**为什么用 `RouteDefinitionLocator` 而不是写 GlobalFilter**：

| 维度 | GlobalFilter 方案 | RouteDefinitionLocator 方案（我们） |
|---|---|---|
| 实例选择 | 自己选（容易写成阻塞调用） | **官方响应式负载均衡**，事件循环零阻塞 |
| 匹配 | 自己实现匹配引擎 | **官方谓词**（Path/Method/Header/Query + 自研 Consumes/Produces） |
| 可观测 | 黑盒 | `/actuator/gateway/routes` 直接看到每条路由与谓词 |
| 可组合 | 自成体系 | 与 yaml 静态路由、其它 RouteDefinitionLocator 天然合并 |
| 代价 | 每请求跑匹配逻辑 | 路由数量 = 接口数量（中小规模无压力）；规则变更要等心跳刷新 |

**依赖策略**（两个 optional 的讲究）：
- `polygateway-gateway` 把 `spring-cloud-starter-gateway` 声明为 **optional**：只想用 Provider 侧的 MVC 服务不被拖进网关栈（谁用网关模块谁自己引 SCG——网关应用本来就有）；
- 但 `spring-cloud-starter-gateway` 自己又把 `spring-cloud-starter-loadbalancer` 声明成 optional：**不显式引它，`lb://` 路由全部 503**——已在 pom 里显式声明 + README 快速开始写明。

---

## 6. 工程陷阱簿（本项目实测踩过/修过的）

1. **loadbalancer 缺失 → 503**：SCG starter 对它 optional，必须自己声明（gateway pom 有注释为证）。
2. **多注册中心 → LB 拿到空列表 → 503**：Spring 会同时装配 simple / reactive / composite DiscoveryClient，LB 的 reactive 路径可能拿到空的 composite——E2E 里排除了 4 个自动配置；**生产要求只启用一个注册中心客户端**（README 已写）。
3. **端点条件当正则用会误匹配**：`X-Version=1.0` 的点号 → `Pattern.quote` 转义（单测钉住）。
4. **取反条件 `!x`、`x!=v`** 官方谓词无法表达 → 跳过 + debug 日志（宁松勿错）。
5. **Spring 双条件兼容**：`PathPatternsRequestCondition` / `PatternsRequestCondition` 双分支取路径（Spring 版本差异），测试里还要 `MockServletContext` 才能起 MVC 上下文。
6. **官方 `discovery.locator` 会重复路由**：E2E 里关掉 `spring.cloud.gateway.discovery.locator.enabled`。
7. **聚合与项目 pom 的 parent 关系**：五项目根 pom 无 parent，所以聚合 pom 可以直接叠加（不会顶掉各自的 parent）。

---

## 7. 动手学习路径（从跑起来到改起来）

**步骤 0 — 跑起来**
```bash
cd /data/workspace/source-code/poly
mvn -f polygateway/pom.xml test          # 42 个测试，全绿
```

**步骤 1 — 读全链路 E2E（一个测试覆盖 9 个场景）**
`polygateway-spring-boot/src/test/.../PolyGatewayGatewayE2ETest.java`，按行读，每个场景对照本文 §2/§4：
1. GET 路径变量 → 200 + 下游收到 `/api/orders/42`（Path+Method+StripPrefix）
2. filter-rule → 响应头 `X-Filter-Rule: applied`
3. POST + Content-Type 匹配 → 200 + 下游 `/api/orders`
4. 同一接口 Content-Type 不匹配 → 404
5. 双媒体类型接口 → JSON 和 text/plain 都 200（GATHER_LIST）
6. excludes（order-service 的 /internal/** POST）→ 404
7. **audit-service 的同名接口 → 200 且无响应头**（services 域证明）
8. 未知路径 → 404
9. 元数据加接口 + 心跳 → 新路径 200（刷新证明）

**步骤 2 — 改代码的四个实验**
- 实验 A：在 E2E 的 `endpoints` 列表里加一个 `GET /orders/{id}/refund`，写断言看它何时可路由；
- 实验 B：把 filter-rules 的 `AddResponseHeader` 换成 `RequestRateLimiter`（或 `Retry`），观察限流行为；
- 实验 C：写一个最小 Spring MVC 应用（一个 Controller + `polygateway.provider`），启动后对 `InstancePreRegisteredEvent` 打断点，看 metadata JSON 是怎么产生的；
- 实验 D：改一个 `excludes` 配置后不发心跳 → 路由不更新；手动 `applicationEventPublisher.publishEvent(new HeartbeatEvent(...))` → 生效（理解 §4.7）。

**步骤 3 — 源码阅读顺序**
`core/`（模型与协议，1 小时）→ `provider/`（收集与发布，1 小时）→ `gateway/WebEndpointRouteDefinitionLocator`（核心，2 小时，对照 §4）→ `gateway/predicate/` + `PolyGatewayFilterRule` → `spring/boot/`（条件装配，30 分钟）→ 回到 E2E 对号入座。

---

## 8. 理解自测（能口头答出 = 真吃透）

1. `GET /order-service/api/orders/42` 从网关到下游 URL 的完整变换链是什么？
2. 为什么路由 URI 必须是 `lb://`？写成 `http://host:port` 会失去什么？
3. `StripPrefix=1` 为什么是 1？服务有 context-path 时会不会被剥掉？
4. 两个服务都有 `/health`，路由会冲突吗？
5. 排除规则为什么放生成期而不是请求期？代价是什么？（答：零运行时开销、404 语义清晰；代价是规则变更要等一次心跳才生效）
6. `routeOrder` 的负数排序是怎么被 SCG 真正应用的？（答：Route implements Ordered → CachingRouteLocator 排序）
7. 金丝雀实例独有的接口能被路由吗？实例 metadata 坏了会怎样？
8. 端点声明了 `@RequestMapping(params="debug=true")`，排除规则 `params: debug=true` 什么时候命中、什么时候不命中？
9. `Accept` 头为空时 Produces 谓词的语义？（答：通配全通过，符合 HTTP 规范）
10. 心跳 30 秒一次，这期间新发布的接口能访问吗？怎么立即生效？
11. 一个端点声明了两个媒体类型（consumes json + text），为什么两种 POST 都能通？（答：Consumes= 快捷参数 GATHER_LIST → 谓词配置是列表，任一兼容即匹配）
12. 简历里"零阻塞""路由可视化""依赖精简"三句话分别对应哪段代码？

## 9. 简历 bullet ↔ 代码 对照（讲项目时的索引）

| 简历原话 | 对应实现 | 证据（测试） |
|---|---|---|
| 端点自动发现（零接入、排除管理端点、跨实例一致） | `WebEndpointMappingsCollector` + `WebEndpointMetadataPublisher` + `PolyGatewayProviderListener` | provider 5 测试 + E2E 场景① |
| 端点级路由（官方谓词+响应式负载均衡+零阻塞+隐藏接口 404+限流重试） | `WebEndpointRouteDefinitionLocator` + 两个自研谓词 + `PolyGatewayFilterRule` | E2E 场景①-⑧ |
| 实时生效（心跳即刷新） | `PolyGatewayRouteRefreshListener` | E2E 场景⑨ |
| 依赖精简（SCG optional / loadbalancer 补声明） | `polygateway-gateway/pom.xml` + README 快速开始 | 无（构建验证） |
