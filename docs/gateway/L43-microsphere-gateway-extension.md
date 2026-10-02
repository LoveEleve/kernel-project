# microsphere-gateway：SCG 的"端点级路由"扩展（L43 素材）

> 用途：回答"**怎么在不改 SCG 源码的前提下扩展网关**"、"**一个真实的网关扩展案例长什么样**"
> 对应课程：**L43**（microsphere-gateway 扩展实践）
> 素材来源：训练营 `microsphere-analysis/16-microsphere-gateway-analysis/`（8 篇）+ 源码规划
> ⚠️ 版本：microsphere-gateway **0.2.17-SNAPSHOT**，28 个主 Java 文件

---

## 1. 一句话定位

> **microsphere-gateway 是 Spring Cloud Gateway 的「端点级路由」扩展**：
> 下游服务启动时把**自身全部 HTTP 端点**序列化进注册中心实例 metadata，网关用一条 `we://` 自定义 scheme 的路由**自动发现、精确匹配并转发** —— 把官方"服务名 + 路径前缀"级的自动路由推进到**端点级**。

---

## 2. 核心机制（数据流）

```
服务 A（provider）                          注册中心              网关
  @EnableWebExtension                          │                   │
  → 收集所有 Controller/RouterFunction 端点     │                   │
  → 序列化为 WebEndpointMapping 列表            │                   │
  → attachMetadata 写入实例 metadata ────────►  │                   │
      (web.mappings = URL编码JSON)              │                   │
                                               │ 实例列表(含metadata) ─► we://all 路由
                                               │◄──────────────────  │ ① 剥 /{application} 前缀
                                               │                    │ ② RequestMappingInfo 匹配
                                               │                    │ ③ 选实例转发
```

**关键**：网关**不再需要知道下游有哪些接口** —— 它从实例 metadata 里**读出来**。

---

## 3. 与官方两条路线的对比（为什么需要它）

官方 SCG 有两条路，各有短板：

| 方案 | 配置成本 | 控制粒度 | 短板 |
|---|---|---|---|
| **手写路由** `lb://xxx` + `Path=/xxx/**` | 每个服务维护一份 | 服务级 + 路径 | 配置重复、易漂移 |
| **`discovery.locator.enabled=true`** | **零配置** | **服务级** | 路径原样透传 —— **网关不知道下游有哪些端点**，`/user-service/a/b` 和 `/user-service/x/y` 一律转发 |
| **`we://`（microsphere）** | **零配置** | **端点级** | 只有发布 `web.mappings` metadata 的服务才能被覆盖 |

**`we://` 拿到的两者兼得**：
- 配置零维护（服务自己发布端点）
- 端点级可控（可按 method / consumes **排除内部接口**）

**设计动机**：服务接口路径变更（`/v1/orders` → `/v2/orders`）时，网关无需改配置，但仍能端点级控制。

---

## 4. 模块结构（28 文件 = 8 + 13 + 7）

```
microsphere-gateway/  (0.2.17-SNAPSHOT)
├── microsphere-gateway-parent/          # 父 POM
├── microsphere-gateway-dependencies/    # BOM
│
├── microsphere-spring-cloud-gateway-commons        # 8 文件：纯共享层，零网关依赖
│   ├── annotation/   ConditionalOnMicrosphereGatewayEnabled
│   │                 ConditionalOnMicrosphereWebEndpointMappingEnabled
│   ├── config/       ConfigUtils / WebEndpointConfig /
│   │                 WebEndpointConfigurationPropertiesBindListener
│   └── constants/    CommonConstants / CommonsPropertyConstants / RouteConstants
│
├── ...-server-webflux                              # 13 文件：响应式网关（SCG WebFlux）
│   ├── autoconfigure/  GatewayAutoConfiguration / WebEndpointMappingGatewayAutoConfiguration
│   ├── context/        WebEndpointApplicationContextInitializer
│   ├── event/          DisabledHeartbeatEventRouteRefreshListenerInterceptor
│   │                   PropagatingRefreshRoutesEventApplicationListener
│   ├── filter/         DefaultGatewayFilterChain / WebEndpointMappingGlobalFilter
│   ├── handler/        CachingFilteringWebHandler / FilteringWebHandlerBeanDefinitionRegistryPostProcessor
│   └── util/           GatewayUtils
│
└── ...-server-webmvc                               # 7 文件：Servlet 网关（SCG Server MVC）
    ├── autoconfigure/  WebEndpointMappingGatewayServerMvcAutoConfiguration
    ├── context/        WebEndpointApplicationContextInitializer
    └── filter/         WebEndpointMappingHandlerSupplier / WebEndpointMappingHandlerFilterFunction
```

**值得学习的架构点**：
| 点 | 说明 |
|---|---|
| **双栈适配** | WebFlux（`GlobalFilter`）与 WebMVC（`HandlerFilterFunction`）**两套实现**，共享 commons 层 |
| **BeanDefinition 后处理** | `FilteringWebHandlerBeanDefinitionRegistryPostProcessor` —— **替换 SCG 官方的 `FilteringWebHandler`** 为自定义版（不改源码的扩展姿势） |
| **过滤器链缓存** | `CachingFilteringWebHandler` —— 缓存合并后的过滤器数组，路由刷新时重建 |
| **事件拦截** | `DisabledHeartbeatEventRouteRefreshListenerInterceptor` —— 拦截心跳事件触发的路由刷新 |
| **配置绑定** | `WebEndpointConfig` + `BindListener` —— 支持 excludes 热更新 |

---

## 5. 适用场景与边界（生产注意）

### 5.1 什么时候该用 `we://`

| 维度 | 适合 | 不适合 |
|---|---|---|
| 服务形态 | **microsphere 全生态**（服务都发布 `web.mappings`） | 非生态/第三方服务（必须 `lb://` 混排） |
| 端点控制 | 需要 excludes（按 method/consumes 排除内部接口） | 只需服务级转发（官方 discovery.locator 更简单） |
| 端点规模 | 中小（几十端点/服务） | 大端点集（metadata 长度墙） |
| 变更频率 | 低频 | **高频滚动发布（⚠️ G1 断链）** |
| 错误语义 | 可接受"未命中即空响应" | 需要明确 5xx/404 语义 |

**混排实践**：同一网关内 `we://all`（生态服务）与 `lb://`（非生态服务）**可共存** —— 过滤器对非 `we` scheme 直接放行。

### 5.2 已知缺口（生产使用前必须知道）

| # | 缺口 | 后果 |
|---|---|---|
| **G1** | **滚动发布端点缓存不更新** | 服务改了 API 路径后，网关端点缓存不更新 → 新接口"未命中 → **静默 200**"，排障只能重启网关或手动刷新。**这是生产使用前必须解决的第一个问题** |
| G11 | metadata 长度墙 | 端点数多时实例 metadata 过大 |
| — | **静默 200** | 未命中不返回 404，而是空响应 —— 排障困难 |
| — | 同区域耗尽不 fallback | 启用 zone 后，同区域实例耗尽即失败（与 L42 的容灾缺口一致） |

---

## 6. ⚠️ 一个常见误解：microsphere-gateway **不做 Zone 路由**

**交接文档曾写"16 做网关层 Zone 路由"，源码验证证明不成立**：
- 主代码 `grep -ri zone` 只命中**两处 TODO 注释**：
  - `WebEndpointMappingGlobalFilter.java:275` → `// TODO support ZonePreferenceFilter`
  - `WebEndpointMappingHandlerFilterFunction.java:164` → `// TODO support ZonePreferenceFilter`
- git 全历史（352 提交）中除测试配置的 Eureka `defaultZone` 外，**没有任何 Zone 路由实现**

**网关层 Zone 优先的真实来源**：**microsphere-multiactive（17）的全局 LoadBalancer 配置**
- `CustomizedLoadBalancerAutoConfiguration`（`@LoadBalancerClients(defaultConfiguration=...)`）对**包括网关在内的所有 LB 客户端**生效
- 4 个门控条件：引入 multiactive-spring-cloud + `microsphere.spring.cloud.loadbalancer.customized=true` + `spring.cloud.loadbalancer.configurations=optimized-zone-preference` + `ZoneContext.preferenceEnabled=true`

---

## 7. 对简历的价值判断

### 7.1 诚实结论

**microsphere-gateway 是"小马哥的项目"，不是你的** —— 直接写进简历 = 造假。

**能写的三种情况**：

| 情况 | 能不能写 | 怎么写 |
|---|---|---|
| 只是"学习过" | ❌ **不能写** | — |
| **把它集成到自己的项目** | ✅ 能写 | "网关引入 microsphere 生态，实现端点级路由（服务自动发布端点元数据，网关零配置精确匹配）" |
| **参考它的设计自研了类似能力** | ✅ 能写（**最推荐**） | "参考 microsphere 的端点元数据方案，自研了 xxx（解决 G1 滚动发布缓存问题）" |

### 7.2 最值钱的"自研点"（面试可讲）

如果要在 xhs 网关上做扩展，**这几个点是 microsphere 的缺口，正好是自研机会**：

| 自研点 | 价值 | 难度 |
|---|---|---|
| **修复 G1（滚动发布端点缓存不更新）** | 解决 microsphere 的已知硬伤 → 有对比、有说服力 | 中（监听 `ServiceInstancesChangedEvent` 或调整心跳拦截） |
| **静默 200 → 明确 404** | 排障友好性 | 低 |
| **metadata 长度优化**（G11） | 大端点集场景 | 中 |
| **we:// 的 Zone 感知**（microsphere 只留了 TODO） | 补它的缺口 | 中 |

### 7.3 面试答法模板

> "我们的网关参考了 microsphere 的**端点级路由**思路 —— 官方 SCG 的自动路由只到**服务级**（路径原样透传），手写路由又要每服务维护。microsphere 的方案是**服务把端点元数据发布到注册中心，网关自动匹配**，做到了零配置 + 端点级控制。
> 但它的实现有个硬伤：**滚动发布时端点缓存不更新**（新接口会静默返回 200）。我们在集成时**补了这个缺口**：监听实例变更事件主动刷新端点缓存。
> 这个改动让我们既拿到了端点级路由的收益，又避免了它的已知问题。"

---

## 8. 关联

- **L43**（microsphere-gateway 扩展实践）
- **L44**（Gateway × Nacos：`DiscoveryClientRouteDefinitionLocator` 是官方"服务级自动路由"，正好与本文的"端点级"对比）
- **L45**（Gateway × Sentinel）
- **L49**（多实例部署：G1 缺口与滚动发布相关）
- 素材：`docs/plan/framework/source-analysis/关联项目/microsphere-gateway源码分析规划.md`
- 训练营：`microsphere-analysis/16-microsphere-gateway-analysis/`（8 篇）+ `17-microsphere-multiactive-analysis/`
