# Lesson 25: How the Filter Chain Is Assembled — `FilteringWebHandler`

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 25
> 前置：**L05**（过滤器总览：Global vs route）· **L17 §17.5.2**（D 组首过：handle 三步）· **L21 §21.4**（路由 filters 编译期已排序 —— 本课回收"order 数字哪来的"）· **L23**（handler 就是它）
> 口径：🔴 ① `getCombinedFilters()` 把 GlobalFilter + 路由 filter **合并成一条链**，按 `order` 排序 ② `GatewayFilterAdapter`（把 GlobalFilter 包成 GatewayFilter——**合并的关键**）+ `OrderedGatewayFilter`（order 包装）【缓存】`routeFilterMap` + 监听 `RefreshRoutesEvent` 清缓存

**本课回答一个问题**：一个请求**匹配到某条路由之后**（L23），它**真正要跑的那条过滤器链是怎么拼出来的**？
网关有**两族过滤器**（全局的 `GlobalFilter` / 路由的 yml filter），它们在这里被"**合流**"成同一条链：
**适配器统一接口 → 按 `order` 全局混排 → 交给 L26 执行**。

> **一句话主旨**：L23 解决"**选哪条路由**"；本课解决"**这条路由真正跑的链长什么样**"——它是"全局过滤器 + 该路由的过滤器"**按 order 重排**出来的一条链。你在 yml 里写的顺序（编译成 `i+1`）就是它在这条链里的位置坐标；而排障"我的过滤器为什么在 X 之前/之后跑"，答案全在本课。

---

## 25.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **两族过滤器"进链前"统一** | `GlobalFilter` 在**构造期**被 `GatewayFilterAdapter` 包成 `GatewayFilter`；路由 filters 在 L21 **编译期**已包好 |
| **②** | **合并 + 排序** | `getAllFilters` = `globals` + `route.filters` → `AnnotationAwareOrderComparator.sort` |
| **③** | **排序的兜底** | 路由 filter 没声明 order → **`i+1`（定义位置）**；global 没 order → 视为最低优先级（排最后） |
| **④** | **`routeFilterMap` 缓存** | 默认**关**（`spring.cloud.gateway.route-filter-cache-enabled`）；开了必须监听 `RefreshRoutesEvent` 清 |

---

## 25.1 例子先行：`/routes` 里的 `order = 1..4` 哪来的？全局过滤器去哪了？

两个"眼见为实"的坑：

**坑 1**：`/routes` 输出里 `filters` 是 4 条、`order = 1..4`——这些数字**不是**你在 yml 里写的：

```java
// RouteDefinitionRouteLocator.loadGatewayFilters（L21 §21.4 的下半段）
GatewayFilter gatewayFilter = factory.apply(configuration);
if (gatewayFilter instanceof Ordered) {
    ordered.add(gatewayFilter);
}
else {
    ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));   // ★ 工厂不声明 order → 用"定义位置 i+1"
}
```

`StripPrefix`/`AddRequestHeader` 等工厂**自己不实现 `Ordered`** → 拿到的 order 就是 1、2、3、4（定义顺序）。**`/routes` 里的 `order = 1..4` 是这里发的。**

**坑 2**：`/routes` 里**看不到全局过滤器**（`NettyRoutingFilter`、`ForwardRoutingFilter`…）——因为 `Route` 对象只装"路由自己的 filters"（L21 两级模型）；全局过滤器活在 `FilteringWebHandler.globalFilters` 字段里。

想看"真正的一条链"？**一条 debug 日志看全**（2026-10-02 实测，请求 `GET /provider/hello`；原样粘贴、未删减）：

```
Sorted gatewayFilterFactories: [[GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.RemoveCachedBodyFilter@6fced25c}, order = -2147483648], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.AdaptCachedBodyGlobalFilter@75d97e18}, order = -2147482648], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.NettyWriteResponseFilter@4bbf20d1}, order = -1], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.ForwardPathFilter@299fc582}, order = 0], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.GatewayMetricsFilter@2a425cf5}, order = 0], [[StripPrefix parts = 1], order = 1], [[AddRequestHeader X-Demo-Route = 'demo-provider'], order = 2], [[AddResponseHeader X-Demo-Gateway-Version = '1.0'], order = 3], [com.myxhs.demo.gateway.filter.DemoHeaderGatewayFilterFactory$$Lambda$1417/0x0000763718896270@8a04164, order = 4], [GatewayFilterAdapter{delegate=com.myxhs.demo.gateway.filter.DemoGlobalFilter@156324b}, order = 100], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter@3cf4dec7}, order = 10000], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter@38a38ed4}, order = 10150], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.LoadBalancerServiceInstanceCookieFilter@1e29a81a}, order = 10151], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.WebsocketRoutingFilter@305aaedf}, order = 2147483646], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.NettyRoutingFilter@7b4b8199}, order = 2147483647], [GatewayFilterAdapter{delegate=org.springframework.cloud.gateway.filter.ForwardRoutingFilter@1b94c789}, order = 2147483647]]
```

（想自己看：`grep 'Sorted gatewayFilterFactories' /tmp/gw-debug.log | tail -1`。）

**这一行里全是你学过的**：
- 前两个 `GatewayFilterAdapter{delegate=…}`：**GlobalFilter 被适配器包好**（`RemoveCachedBodyFilter` MIN、`AdaptCachedBodyGlobalFilter` MIN+1000）
- 中间 `[[StripPrefix parts = 1], order = 1]` → `order = 4`：**这条路由自己的 4 个 filters**（`i+1` 就是 L21 编译时发的，L23 §23.1 的 `/routes` 输出同源）
- 再往后又是全局：`DemoGlobalFilter(100)`（自定义）→ `RouteToRequestUrl(10000)` → `LB(10150)` → `Cookie(10151)` → `Websocket(MAX-1)` → `Netty/Forward(MAX)`
- **两类东西被排进了同一条链**：路由 filters 恰好插在两个 `order=0` 的全局之间、`order=100` 之前——**order 全局生效**
- 两组同 order 的现场：`ForwardPathFilter(0)`/`GatewayMetricsFilter(0)`、`NettyRoutingFilter(MAX)`/`ForwardRoutingFilter(MAX)` ——相邻次序由稳定排序保持

---

> 🔎 **先辨析：`GlobalFilter` 与 `GatewayFilter` 到底什么关系？**
>
> **同**：都是"链上的一个加工步骤"，方法签名**一模一样**：`filter(exchange, chain)`——不是两种能力，是**两种来源**。
> **不同**（"从哪来 / 管多大 / 何时出现"）：
>
> | | `GlobalFilter` | `GatewayFilter` |
> |---|---|---|
> | 从哪来 | Spring **Bean**（代码里注册） | **yml** `filters:`（属于某条路由） |
> | 管多大 | **所有路由**自动生效 | 只对它挂的那条路由生效 |
> | 何时出现 | 启动时就绪（本来就是 bean） | **路由编译时**由工厂 `apply` 造出（L21） |
> | 能写 yml 吗 | ❌（代码 / 属性开关） | ✅ |
> | 例子 | `NettyRoutingFilter`、`RouteToRequestUrlFilter`、鉴权/观测 | `StripPrefix`、`AddRequestHeader`（`/routes` 里那 4 条） |
>
> **"包装"的准确说法**：启动构造 `FilteringWebHandler` 时，每个 `GlobalFilter` 被 `GatewayFilterAdapter` **套一层壳**（壳里只有纯委托 `delegate.filter(exchange, chain)`）。目的：让两族能用**同一种类型**躺进同一个 `List<GatewayFilter>`，交给同一条链执行。
> 所以不是"GlobalFilter 变成了 GatewayFilter"（身份没变、可被 `getDecoratedClass()` 穿透），而是"**给它加了个转接头**"——上面日志里满屏的 `GatewayFilterAdapter{delegate=...}, order = N` 就是它们。
>
> ⚠️ 别混淆第三者：`WebFilter`（Spring WebFlux 层，如 demo 的 `DemoWebFilter`）比这两族**更靠外**，在路由匹配之前执行（L05 / L18）。
>
> **存储 vs 合流（记住这条时间线）**：全局的常驻在 `FilteringWebHandler.globalFilters` 字段（启动期套壳）；路由的在 `Route.getFilters()`（编译期产物）；**两边的"合并 + 排序"是每个请求在 `handle()` 里现做的**，合出的 `List` 交给**每请求新建**的 `DefaultGatewayFilterChain`（L26）——过滤器实例是长期的，"链"是每请求的。

---

## 25.2 源码全解：`FilteringWebHandler`

```java
public class FilteringWebHandler implements WebHandler, ApplicationListener<RefreshRoutesEvent> {   // ★ 还听刷新事件

    protected static final Log logger = LogFactory.getLog(FilteringWebHandler.class);
    private final List<GatewayFilter> globalFilters;                       // ① 注意：已经是 GatewayFilter（构造期就包好了）
    private final ConcurrentHashMap<Route, List<GatewayFilter>> routeFilterMap = new ConcurrentHashMap();
    private final boolean routeFilterCacheEnabled;                         // ④ 缓存开关（默认 false）

    @Deprecated
    public FilteringWebHandler(List<GlobalFilter> globalFilters) {
        this(globalFilters, false);
    }
    public FilteringWebHandler(List<GlobalFilter> globalFilters, boolean routeFilterCacheEnabled) {
        this.globalFilters = loadFilters(globalFilters);                   // ★ 合流第一步：在这里
        this.routeFilterCacheEnabled = routeFilterCacheEnabled;
    }

    private static List<GatewayFilter> loadFilters(List<GlobalFilter> filters) {
        return filters.stream().map(filter -> {
            GatewayFilterAdapter gatewayFilter = new GatewayFilterAdapter(filter);   // ★ 包成 GatewayFilter
            if (filter instanceof Ordered) {
                int order = ((Ordered) filter).getOrder();
                return new OrderedGatewayFilter(gatewayFilter, order);               // 有 order → 再包一层
            }
            else {
                Order order = AnnotationUtils.findAnnotation(filter.getClass(), Order.class);
                if (order != null) {
                    return new OrderedGatewayFilter(gatewayFilter, order.value());   // @Order 注解兜底
                }
            }
            return gatewayFilter;                                                    // 裸适配器（视为最低优先级）
        }).collect(Collectors.toList());
    }

    @Override
    public void onApplicationEvent(RefreshRoutesEvent event) {
        if (this.routeFilterCacheEnabled) {
            routeFilterMap.clear();                                                   // ④ 开关开着才清
        }
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange) {
        Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);              // 取 L23 的交接数据
        List<GatewayFilter> combined = getCombinedFilters(route);                     // ② 合流 + 排序
        if (logger.isDebugEnabled()) {
            logger.debug("Sorted gatewayFilterFactories: " + combined);
        }
        return new DefaultGatewayFilterChain(combined).filter(exchange);              // ③ 起链（L26）
    }

    protected List<GatewayFilter> getCombinedFilters(Route route) {
        if (this.routeFilterCacheEnabled) {
            return routeFilterMap.computeIfAbsent(route, this::getAllFilters);        // ④ 缓存版
        }
        else {
            return getAllFilters(route);                                              // 默认：每次现算（便宜）
        }
    }

    protected List<GatewayFilter> getAllFilters(Route route) {
        List<GatewayFilter> gatewayFilters = route.getFilters();
        List<GatewayFilter> combined = new ArrayList<>(this.globalFilters);           // 先放全局
        combined.addAll(gatewayFilters);                                              // 再接路由
        AnnotationAwareOrderComparator.sort(combined);                                // ★ 统一排序
        return combined;
    }
}
```

**装配来源**（bean 是这么 new 出来的）：

```java
// GatewayAutoConfiguration:276-280
@Bean
@ConditionalOnMissingBean
public FilteringWebHandler filteringWebHandler(List<GlobalFilter> globalFilters, GatewayProperties properties) {
    return new FilteringWebHandler(globalFilters, properties.isRouteFilterCacheEnabled());
}
// GatewayProperties:72-74 → private boolean routeFilterCacheEnabled = false;（javadoc: "defaults to false"）
```

**要点回读**：
1. **构造期** `loadFilters` 就把"GlobalFilter 族"统一成了 `GatewayFilter`（顺序三态：`Ordered` 接口 → `@Order` 注解 → 裸适配器）——这就是"合并"能成立的前提：**两族本来就是两种接口**
2. **请求期** `getAllFilters` 把两边合并再**排一次**（路由侧编译期已排过，但两族合流后必须重新统排）
3. `handle` 的三步在 L17 §17.5.2 首过；本课的价值在把"合流"拆开

---

## 25.3 "合流"的两个零件：`GatewayFilterAdapter` 与 `OrderedGatewayFilter`

**适配器**（本类内部类，把 GlobalFilter 扳成 GatewayFilter）：

```java
private static class GatewayFilterAdapter implements GatewayFilter, DecoratingProxy {   // ★ 还实现了 DecoratingProxy
    private final GlobalFilter delegate;
    GatewayFilterAdapter(GlobalFilter delegate) { this.delegate = delegate; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return this.delegate.filter(exchange, chain);                                    // 纯委托
    }
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("GatewayFilterAdapter{");
        sb.append("delegate=").append(delegate);
        sb.append('}');
        return sb.toString();
    }
    @Override
    public Class<?> getDecoratedClass() { return delegate.getClass(); }                  // 让注解工具穿透到原类
}
```

- 两个接口都是"一个方法的过滤器"，签名都是 `filter(exchange, chain)` —— **适配器就是纯委托**（零逻辑）
- `DecoratingProxy.getDecoratedClass()` 让框架的注解/类型工具（`ClassUtils.getUserClass` 等）能**穿透适配器看到原 `GlobalFilter` 类**（本类 `loadFilters` 找 `@Order` 用的是原始 filter，不依赖它）

**order 包装器**（独立类，`filter/OrderedGatewayFilter.java`）：

```java
public class OrderedGatewayFilter implements GatewayFilter, Ordered {
    private final GatewayFilter delegate;
    private final int order;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return this.delegate.filter(exchange, chain);                    // 纯委托
    }
    @Override
    public int getOrder() { return this.order; }
    @Override
    public String toString() {
        return new StringBuilder("[").append(delegate).append(", order = ").append(order).append("]").toString();   // ★ /routes 与 debug 日志里 [..., order = N] 的格式出处
    }
}
```

**`[delegate, order = N]` 就是你在 `/routes` 和 debug 日志里见过的方括号格式**——凡是出现 `order = N` 的过滤器，外面都套了它：

| 谁在用它套 | 套谁 | order 值 |
|---|---|---|
| `FilteringWebHandler.loadFilters` | 带 `Ordered`/`@Order` 的全局过滤器适配器 | 过滤器的 order/注解值 |
| `RouteDefinitionRouteLocator.loadGatewayFilters` | 路由 filter 且工厂不声明 order | **`i+1`（定义位置；`/routes` 里的 1..4）** |

**排序细节**：`combined` 先加 globals、后加 route filters，再稳定排序（`List.sort` 保证稳定性）→ **order 相同时，全局过滤器在前**。

---

## 25.4 `routeFilterMap` 缓存（项目里第三处"跟刷新绑定"的缓存）

```java
protected List<GatewayFilter> getCombinedFilters(Route route) {
    if (this.routeFilterCacheEnabled) {
        return routeFilterMap.computeIfAbsent(route, this::getAllFilters);   // key = Route 对象本身！
    }
    return getAllFilters(route);
}
```

三个事实 + 一个推测：

- **事实**：默认**关**（`GatewayProperties.routeFilterCacheEnabled = false`；属性 `spring.cloud.gateway.route-filter-cache-enabled`）
- **事实**：key 是 **`Route` 对象**——同一 Route 实例的第二次请求才命中；refresh 后 Route 列表被整体替换（L22），**新 Route 对象天然不会命中旧缓存**
- **事实**：开了之后，本类自己监听 `RefreshRoutesEvent` → `routeFilterMap.clear()`（防御性：若某自定义 `RouteLocator` 复用/改写了 `Route` 对象，缓存不会还留着旧链）
- **推测**（为什么默认关）：合流本身很便宜（一次 ArrayList + 小列表排序），缓存收益有限，却多一层内存和"陈旧"风险 —— 官方的选择是**默认不赚这个便宜**

**与 L22 的两级缓存对照**：`CachingRouteLocator` 缓存"**Route 列表**"（原料→成品）；这里缓存"**每条 Route 合并后的链**"（成品→上桌）——都在 `RefreshRoutesEvent` 上清/换。

---

## 25.5 装配全图（三个时期三处装配）

```
【编译期】L20/L21：yml filter → loadGatewayFilters（bind → apply → instanceof Ordered ? : OrderedGatewayFilter(i+1)）→ Route.getFilters()
【启动期】本课：@Bean GlobalFilter 列表 → FilteringWebHandler 构造器 loadFilters（Adapter [+ Ordered/@Order]）→ globalFilters 字段
【请求期】本课：handle → getCombinedFilters = globalFilters + route.getFilters() → sort → DefaultGatewayFilterChain（L26 执行）
```

---

## 25.6 掌握边界（值不值得深挖）

> 本课定位：**实用型（中轻）**——读一遍 + 会看那行 `Sorted gatewayFilterFactories` 日志即可；源码细节（适配器的接口、缓存内部）了解即可，用到再回看。

| 内容 | 口径 |
|---|---|
| `handle` 三步本身 | ❌ L17 §17.5.2 已讲，不重复 |
| `DecoratingProxy` 的消费方全查 | ❌ 知道"给注解工具穿透用"即可 |
| **GlobalFilter → GatewayFilter 的包装时机与三态** | ✅ 要会（"两族怎么合流"是 D 组核心问题） |
| **`i+1` / `order = N` 的 toString 格式** | ✅ 要会（能读 `/routes` 和 debug 日志） |
| **`routeFilterMap` 开关 + 刷新语义** | ✅ 要会（与 L22 组"缓存家族"） |
| 同 order 的稳定序 | ⚪ 知道"稳定排序、globals 在前"即可 |

---

## 25.7 Self-Test

1. `GlobalFilter` 在什么时机变成 `GatewayFilter`？包了几层（有 order / 无 order）？
2. 一条链的排序在哪个方法里做、对哪个列表做？两个过滤器 order 相同时谁在前？
3. `/routes` 为什么不显示全局过滤器？想看"真正的链"看什么？
4. `[[StripPrefix parts = 1], order = 1]` 是谁的 `toString()`？`order = 1` 从哪来？
5. `routeFilterMap` 的 key 是什么？为什么默认关？开了为什么必须监听 `RefreshRoutesEvent`？
6. 为什么说 `GatewayFilterAdapter` 是"合并的关键"？（提示：两族的接口）

---

## 25.8 Open Questions

- [ ] `DecoratingProxy` 的具体消费方（框架哪些代码靠它取注解）—— 未全查。
- [ ] `route-filter-cache-enabled=true` 的实际收益 —— 未实测（可作可选实验：高 QPS 下对比）。
- [ ] 同 order 稳定序是否被官方文档承诺 —— 代码事实（稳定排序）推断；文档未查。
