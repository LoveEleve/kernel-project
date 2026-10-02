# Gateway 架构全景（面试用一页图）

> 用途：**面试时能从头讲到尾** —— 对象有哪些 → 请求怎么流 → 关键机制在哪。
> 定位：这是 **Stage 5（L17~L30）的产出物**，随源码课逐步校验/补全。
> 当前状态：**骨架已定**（📌 标记 = 待 Stage 5 源码课确认细节）。
> 配套：`../gateway-学习清单.md` · 每节课文见 `docs/gateway/`

---

## 一、先建立这个分界：两个阶段

| 阶段 | 什么时候 | 干什么 | 产物 |
|---|---|---|---|
| **装配期** | **启动时** | `GatewayAutoConfiguration` 装配 **四组**组件 | 一堆 Bean |
| **运行期** | **每次请求** | 匹配 → 过滤 → 转发 | 响应 |

> **面试第一句**："Gateway 的组件可以分成**装配期的四组**（来源 / 模型 / 匹配 / 执行）和**运行期的一条链**。"

---

## 二、装配期：四组组件（对象清单）

### A 组 · 路由来源（`RouteDefinitionLocator` 家族）—— "路由定义从哪来"

| 对象 | 来源 | 说明 |
|---|---|---|
| `PropertiesRouteDefinitionLocator` | **yml**（`spring.cloud.gateway.routes`） | 只读（在 `config/` 包） |
| `InMemoryRouteDefinitionRepository` | **Actuator 写入**（POST/DELETE） | 可读写；**内存**（重启丢，L08） |
| `DiscoveryClientRouteDefinitionLocator` | **注册中心** | 服务级自动路由（可选，L16/L46） |
| `RedisRouteDefinitionRepository` | **Redis** | 多实例共享（可选，L49） |
| **`CompositeRouteDefinitionLocator`** | — | **把上面几个合并成一份定义流** |

### B 组 · 路由模型（`RouteLocator` 家族）—— "定义 → 可运行的 Route"

| 对象 | 职责 |
|---|---|
| **`RouteDefinitionRouteLocator`** | `RouteDefinition` → **`Route`**（编译谓词/过滤器，L21） |
| `CompositeRouteLocator` | 合并多个 `RouteLocator` |
| **`CachingRouteLocator`** | **缓存运行态**（L30）；内部包着 `CompositeRouteLocator` |

### C 组 · 匹配

| 对象 | 职责 |
|---|---|
| **`RoutePredicateHandlerMapping`** | 请求进来找路由（`lookupRoute`） |
| `AsyncPredicate` | 谓词的**异步 AND 组合**（`predicate.and(found)`） |

### D 组 · 执行

| 对象 | 职责 |
|---|---|
| **`FilteringWebHandler`** | **链的装配与执行**（WebHandler） |
| `GlobalFilter` / `GatewayFilter` | **两类过滤器**（L05） |
| **`GatewayFilterAdapter`** | **把 `GlobalFilter` 包成 `GatewayFilter`** ← 合并的关键（内部类） |
| `OrderedGatewayFilter` | 带 order 的包装 |
| **`DefaultGatewayFilterChain`** | **链式递归执行**（内部类，index 递增） |

---

## 三、运行期：一次请求的流转

```
客户端
 │
 ▼ ① Netty Server（reactor-netty）              ← EventLoop，非阻塞（L00·0.4）
 ▼ ② WebFilter 链（WebFlux 层）                 ← CORS / Security 在这层（L07）
 ▼ ③ DispatcherHandler（WebFlux）
 ▼ ④ RoutePredicateHandlerMapping.lookupRoute()
 │      ├─ 无匹配 → 空 Mono → 404
 │      └─ 匹配 → put(GATEWAY_ROUTE_ATTR, route) → 返回 webHandler
 ▼ ⑤ FilteringWebHandler.handle()               ← 取 route，装配过滤器链
 ▼ ⑥ DefaultGatewayFilterChain 递归执行：
 │        RouteToRequestUrlFilter        （order 10000）拼 URL
 │        ReactiveLoadBalancerClientFilter（10150）lb:// → 物理 URI
 │        NettyRoutingFilter              （MAX）连接池 + 超时 → 出站
 │        NettyWriteResponseFilter        （-1）写回响应
 ▼ ⑦ 下游服务
 ▼ ⑧ 响应写回客户端
```

> order 实测值来自 `/actuator/gateway/globalfilters`（L08）。

---

## 四、三张"面试能说出口"的图

### 图 1 · 路由的两级模型（★ 最常被问）

```
yml / Actuator / 注册中心 / Redis
        ↓（RouteDefinitionLocator 家族 + Composite 合并）
   RouteDefinition            ← 配置态（/actuator/gateway/routedefinitions）
        ↓（RouteDefinitionRouteLocator 编译：谓词 AND + 过滤器）
      Route                   ← 运行态（/actuator/gateway/routes）
        ↓（CachingRouteLocator 缓存）
    匹配 & 转发
```

> **一句话**：**定义是"源码"，Route 是"编译产物"，`CachingRouteLocator` 是"缓存"** —— 所以改完**必须 refresh**。

### 图 2 · 过滤器的两类与合并（★ 第二常问）

```
GlobalFilter（全局：所有路由都生效） ──┐
                                      ├─ 合并 → 按 order 排序 → 【一条链】
GatewayFilter（路由级：routes[].filters）──┘
      ↑
  GatewayFilterAdapter：把 GlobalFilter 包成 GatewayFilter（统一类型）
  OrderedGatewayFilter：带 order 的包装
```

> **执行顺序由 `order` 决定** —— 两类混排成**一条链**，不是两条链。

### 图 3 · 转发四步（L27）

```
① RouteToRequestUrlFilter          拼出完整 URL（此时仍保留 lb://）
② ReactiveLoadBalancerClientFilter lb:// → http://10.0.0.1:8081（选实例 + 改写）
③ NettyRoutingFilter               连接池借连接 → 发出 → 等响应（超时在这）
④ NettyWriteResponseFilter         把下游响应写回客户端
```

---

## 五、速记卡（面试前 5 分钟看这个）

| 面试问题 | 一句话答案 |
|---|---|
| 路由定义从哪来？ | **4 个来源**（yml / 内存 / 注册中心 / Redis）+ `CompositeRouteDefinitionLocator` 合并 |
| 定义怎么变成路由？ | `RouteDefinitionRouteLocator` **编译**（谓词逐个 `and` + 过滤器） |
| 请求怎么找到路由？ | `RoutePredicateHandlerMapping.lookupRoute` —— **只取第一个匹配** |
| 过滤器怎么串起来？ | `FilteringWebHandler` 合并两类 → 按 `order` → `DefaultGatewayFilterChain` 递归 |
| `lb://` 在哪被改写？ | **第 2 步** `ReactiveLoadBalancerClientFilter` |
| 改完路由为什么不生效？ | 运行态是 `CachingRouteLocator` 的**缓存**，要 `RefreshRoutesEvent` |
| 为什么 yml 路由删不掉？ | Actuator 只写 `InMemoryRouteDefinitionRepository`；yml 走 `PropertiesRouteDefinitionLocator`（**两个来源**） |
| 为什么过滤器里阻塞会出事？ | 整个链路跑在 EventLoop 上（L35） |
| CORS 在哪处理？ | **WebFlux 的 HandlerMapping 层**，不在过滤器链（L07） |

---

## 六、Stage 5 课程 ↔ 本图对照（13 课，L17~L30）

| 课 | 要确认/补充的点 |
|---|---|
| **L17 装配** | 四组组件的**确切 Bean 名与装配顺序**（`GatewayAutoConfiguration`） |
| **L19 来源链** | 四个来源如何被 `CompositeRouteDefinitionLocator` 合并（含顺序/优先级） |
| **L20 工厂机制** | `_genkey_` → `shortcutFieldOrder` → `ShortcutType` → `Binder` 的**完整绑定链** |
| **L21 定义→Route** | 谓词 `and()` 组合；过滤器编译细节 |
| **L22 两级缓存** | 定义缓存 vs 运行缓存；刷新时替换顺序 |
| **L23 匹配** | `lookupRoute` 的 `filterWhen(...).next()` 语义 |
| **L24 谓词家族** | 选 4 个代表（Path / Method / Weight / RemoteAddr）讲**统一结构** |
| **L25 链装配** | `getCombinedFilters` + `GatewayFilterAdapter` + `OrderedGatewayFilter` |
| **L26 跑链** | `DefaultGatewayFilterChain` 的**链式递归**（洋葱模型） |
| **L27 转发四步** | 四步之间的**数据传递**（`exchange.getAttributes()` 存了什么） |
| **L28 body** | 缓存与失效的**触发时机** |
| **L29 sticky session** | cookie → 实例的钉住机制 |
| **L30 刷新** | **只换缓存不中断在途请求**的机制 + `?metadata=` 局部刷新 |
