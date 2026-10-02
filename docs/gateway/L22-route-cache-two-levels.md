# Lesson 22: Route Cache（两级缓存）— "定义缓存"与"运行缓存"

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 22（★v5.4 新增）
> 前置：**L17 §17.4**（`CachingRouteLocator` 首过：字段 / 最外层 / 为什么）· **L17 §17.4.7 / §17.4.8**（刷新演示 / 事件广播）· **L19**（定义来源链）· **L21**（编译过程：定义 → Route）
> 口径：核心 1 条 —— **"定义缓存"与"运行缓存"是两个层次；刷新时各自如何被替换**（**L30 的前置**）

**本课回答三个问题**：
① 改完一条路由，为什么 `/routes` 不动、`/routedefinitions` 动了？
② 这个"挡人的缓存"到底怎么实现的？为什么叫"两级"？
③ 刷新时它怎么被换掉、为什么不影响正在处理的请求？

---

## 22.1 它干了什么：改一条路由，为什么 `/routes` 不动？

先看实测（L19 做过，当时只讲了"两个视图"，没讲机制）：

环境：demo-gateway(8080) + demo-provider(8081)。下面每一步都**可以直接复制执行**（2026-10-02 实测，输出原样粘贴）：

```bash
GW=http://127.0.0.1:8080/actuator/gateway
```

**① 先看家底**（routes = 8 条 yml 静态路由 + 1 条发现自动路由 = 9）

```bash
curl -s --noproxy '*' $GW/routes | python3 -c "import json,sys;print('routes 条数:',len(json.load(sys.stdin)))"
# routes 条数: 9
```

**② 写一条新路由（201 Created）**

```bash
curl -s --noproxy '*' -i -X POST $GW/routes/tmp-l22 \
  -H 'Content-Type: application/json' \
  -d '{"id":"tmp-l22","uri":"http://127.0.0.1:8081",
       "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l22/**"}}]}'
# HTTP/1.1 201 Created
# X-Demo-WebFilter: hit-63     ← demo 自己的 WebFilter 加的头（L18），与路由无关
# Location: /routes/tmp-l22
# content-length: 0
```

**③ 立刻看两个视图：定义变了、运行没变**

```bash
curl -s --noproxy '*' $GW/routedefinitions | python3 -c "import json,sys; d=json.load(sys.stdin); print('定义条数:',len(d),'| 含 tmp-l22:',any(x['id']=='tmp-l22' for x in d))"
# 定义条数: 10 | 含 tmp-l22: True

curl -s --noproxy '*' $GW/routes | python3 -c "import json,sys; d=json.load(sys.stdin); print('运行条数:',len(d),'| 含 tmp-l22:',any(x['route_id']=='tmp-l22' for x in d))"
# 运行条数: 9 | 含 tmp-l22: False        ← ★ 被 CachingRouteLocator 的旧快照挡住

curl -s --noproxy '*' -i $GW/routes/tmp-l22 | head -4
# HTTP/1.1 404 Not Found                ← ★ 更直观：这条路由的"运行态"还没出生
# X-Demo-WebFilter: hit-66
# content-length: 0
```

**④ 显式刷新（+ 小小等待），再看：出现了**

```bash
curl -s --noproxy '*' -i -X POST $GW/refresh
# HTTP/1.1 200 OK

sleep 0.3    # ⚠️ refresh 只负责"广播事件"；重算与换快照是【异步】的，紧接着查会撞上极小 race（实测踩到过）

curl -s --noproxy '*' $GW/routes | python3 -c "import json,sys; d=json.load(sys.stdin); print('运行条数:',len(d),'| 含 tmp-l22:',any(x['route_id']=='tmp-l22' for x in d))"
# 运行条数: 10 | 含 tmp-l22: True

curl -s --noproxy '*' $GW/routes/tmp-l22
# {"predicate":"Paths: [/tmp-l22/**], match trailing slash: true","route_id":"tmp-l22","filters":[],"uri":"http://127.0.0.1:8081","order":0}
```

**⑤ 清理**

```bash
curl -s --noproxy '*' -o /dev/null -w 'DELETE -> %{http_code}\n' -X DELETE $GW/routes/tmp-l22
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST $GW/refresh
# DELETE -> 200
# refresh -> 200
```

> ⚠️ **环境提示（很重要）**：这段序列要**连续执行**（实测全程 < 0.7s）。本 demo 开着
> `spring.cloud.gateway.discovery.locator.enabled=true`，会激活 Alibaba 的 `NacosDiscoveryHeartBeatPublisher`
> （字节码实证：三个装配条件 **OR** —— `spring.cloud.nacos.discovery.heart-beat.enabled=true` / **gateway discovery locator** / Spring Boot Admin bean，任一成立即启动；所以 `heart-beat.enabled=false` 关不掉开了 locator 的网关）——每 `watchDelay`（默认 30s；网关日志节奏精确 30s）
> 发一次 `HeartbeatEvent`，**值 = 自增序号**（`AtomicLong.getAndIncrement()`）→ `HeartbeatMonitor` 每次都判"新值"
> → `RouteRefreshListener` 每 30s 触发一次**全量自动刷新**。手慢会让你看到"没 refresh 它也变了"的假象；
> 做干净实验就把 locator 关掉，或抓在两次心跳之间。

同一个现象换个角度说：写入只改了"来源"，两个视图的差异就是"快照还未换" —— 把两个视图**背后走的链**画出来，答案就在图里：

同一条新路由，一个视图立刻变、另一个必须 refresh。把两个视图**背后走的链**画出来，答案就在图里：

```
GET /routedefinitions ──► A 组 CompositeRouteDefinitionLocator
                             ├─► propertiesRouteDefinitionLocator（yml）
                             ├─► RedisRouteDefinitionRepository（★ 本 demo：Actuator 写的就是它；未开时是 InMemory）
                             └─► …（L19 的四来源）
                          （没缓存时：每次问都直连来源 → 实时）
                               ↑
                     若【手动装上】CachingRouteDefinitionLocator，就插在这一层：
                     第一次问来源并记住，之后直接给"上次的定义" —— 直到 refresh

GET /routes ──► B 组 CachingRouteLocator ★（默认装配）
                    │  手里有一份"Route 快照"（cache）
                    ├─ 快照命中 → 直接重放，不碰来源、不编译
                    └─ 快照未命中 → 才回源 + 重编译（L21）
```

- `POST` 新路由写进的是**来源**（本 demo 为 `RedisRouteDefinitionRepository`；未开 Redis 定义库时是 InMemory）；`/routedefinitions` 直连来源 → 立刻 10 条
- `/routes` 问的是 `CachingRouteLocator` 手里的**快照**；没人通知它重算 → 还是 9 条（单条详情甚至 404）
- `POST /refresh` 广播 `RefreshRoutesEvent` → `CachingRouteLocator` **异步**重算快照 → `/routes` 变 10 条、单条 404→200（"异步"见 22.1 的 `sleep 0.3` 说明；L17 §17.4.7 演示过）

**所以它是什么（一句话）**：网关把"编译好的路由"缓存在内存里反复用；**本课讲这个缓存怎么做**。
而"两级"指的是：**原料（`RouteDefinition`）与成品（`Route`）各有一个同款的可选缓存包装器** —— 出厂默认**只装了成品层**。

---

## 22.2 为什么需要缓存：贵的是"编译"，不是"读"

不缓存会怎样？看 B 组编译链的入口（`RouteDefinitionRouteLocator`）：

```java
public Flux<Route> getRoutes() {
    return getRoutes(this.routeDefinitionLocator.getRouteDefinitions());   // 冷流，每次订阅重新跑
}
private Flux<Route> getRoutes(Flux<RouteDefinition> routeDefinitions) {
    Flux<Route> routes = routeDefinitions.map(this::convertToRoute);       // ★ 每条定义都过 convertToRoute
    ...
}
```

`convertToRoute` 就是 L21 讲的**编译**：查谓词/过滤器工厂 → 绑参数（L20 的五步链）→ `apply` 生成对象 → 谓词 `and()` 组合 → 过滤器排序。**单独的编译不贵，贵的是"每个请求都来一遍"**：

> C 组匹配（L23）里，每个请求都会 `routeLocator.getRoutes()` 一次（要逐条测谓词）。
> 如果 B 组没有缓存 → 每个请求把**全部路由重编译一遍**。

`CachingRouteLocator` 把这条链变成：**编译一次 → 存快照 → 每请求重放快照**。

那"原料"（`RouteDefinition`）为什么不也缓存？因为**来源本身便宜**：

| 来源 | 读取成本 |
|---|---|
| yml（`propertiesRouteDefinitionLocator`） | 内存里的 `List` |
| Actuator 内存库（`InMemoryRouteDefinitionRepository`） | `ConcurrentHashMap` |
| 注册中心来源 | 客户端自己有缓存（L19） |

**缓存要放在贵的那一层** —— 成品（Route）的生成是编译，贵；原料的读取本来就便宜。这是"默认只装运行缓存、定义缓存留作自助优化"的根本原因（装配细节见 22.5）。

---

## 22.3 主角源码全解：`CachingRouteLocator`（怎么做）

它是怎么装到 B 组最外层的（`GatewayAutoConfiguration:260-266`）：

```java
@Bean
@Primary
@ConditionalOnMissingBean(name = "cachedCompositeRouteLocator")
public RouteLocator cachedCompositeRouteLocator(List<RouteLocator> routeLocators) {
    return new CachingRouteLocator(new CompositeRouteLocator(Flux.fromIterable(routeLocators)));
}
```

类全文：

```java
public class CachingRouteLocator
        implements Ordered, RouteLocator, ApplicationListener<RefreshRoutesEvent>, ApplicationEventPublisherAware {

    private static final Log log = LogFactory.getLog(CachingRouteLocator.class);
    private static final String CACHE_KEY = "routes";                       // ① 缓存 key（缓存 Map 里的 key）
    private final RouteLocator delegate;                                    // ② 被包装的 B 组链（CompositeRouteLocator）
    private final Flux<Route> routes;                                       // ③ ★ 门面：对外永远返回这个 Flux
    private final Map<String, List> cache = new ConcurrentHashMap<>();      // ④ 缓存容器
    private ApplicationEventPublisher applicationEventPublisher;            // ⑤ 发"刷新结果"事件用
    public CachingRouteLocator(RouteLocator delegate) {
        this.delegate = delegate;
        routes = CacheFlux.lookup(cache, CACHE_KEY, Route.class)            // ★ 装配缓存（订阅时才干活，见 22.4）
                .onCacheMissResume(this::fetch);                            // ★ 未命中 → 用 fetch() 回源
    }

    private Flux<Route> fetch() {
        return this.delegate.getRoutes().sort(AnnotationAwareOrderComparator.INSTANCE);   // ★ 回源 + 排序（order！）
    }
    private Flux<Route> fetch(Map<String, Object> metadata) {               // scoped 用（22.6 / L30）
        return this.delegate.getRoutesByMetadata(metadata).sort(AnnotationAwareOrderComparator.INSTANCE);
    }

    @Override
    public Flux<Route> getRoutes() {
        return this.routes;                                                 // 每次调用都返回同一个 Flux 对象
    }

    public Flux<Route> refresh() {                                          // 公开 API（main 里无调用者，见 22.12）
        this.cache.remove(CACHE_KEY);
        return this.routes;
    }

    @Override
    public void onApplicationEvent(RefreshRoutesEvent event) {              // ★ 刷新入口（22.6）
        try {
            if (this.cache.containsKey(CACHE_KEY) && event.isScoped()) {
                // 局部刷新：只重算匹配 metadata 的 + 保留不匹配的旧路由（L30 详解）
                final Mono<List<Route>> scopedRoutes = fetch(event.getMetadata()).collect(Collectors.toList())
                    .onErrorResume(s -> Mono.just(List.of()));
                scopedRoutes.subscribe(scopedRoutesList -> {
                    updateCache(Flux.concat(Flux.fromIterable(scopedRoutesList), getNonScopedRoutes(event))
                        .sort(AnnotationAwareOrderComparator.INSTANCE));
                }, this::handleRefreshError);
            }
            else {
                // 全量刷新：整条编译链重跑
                final Mono<List<Route>> allRoutes = fetch().collect(Collectors.toList());
                allRoutes.subscribe(list -> updateCache(Flux.fromIterable(list)), this::handleRefreshError);
            }
        }
        catch (Throwable e) {
            handleRefreshError(e);
        }
    }

    private synchronized void updateCache(Flux<Route> routes) {             // ★ 重算 + 换快照
        routes.materialize()                                                // 信号 → Signal 对象
            .collect(Collectors.toList())                                   // → List<Signal<Route>>
            .subscribe(this::publishRefreshEvent, this::handleRefreshError);
    }

    private void publishRefreshEvent(List<Signal<Route>> signals) {
        cache.put(CACHE_KEY, signals);                                      // ★ 原子换账本（ConcurrentHashMap.put）
        applicationEventPublisher.publishEvent(new RefreshRoutesResultEvent(this));    // 成功通知
    }

    private Flux<Route> getNonScopedRoutes(RefreshRoutesEvent scopedEvent) {
        return this.getRoutes()
            .filter(route -> !RouteLocator.matchMetadata(route.getMetadata(), scopedEvent.getMetadata()));
    }

    private void handleRefreshError(Throwable throwable) {
        if (log.isErrorEnabled()) {
            log.error("Refresh routes error !!!", throwable);
        }
        applicationEventPublisher.publishEvent(new RefreshRoutesResultEvent(this, throwable));   // 失败通知
    }

    @Override
    public int getOrder() {
        return 0;                                                           // 事件回调顺序（22.12 的 OQ 主语）
    }
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }
}
```

（字段逐个的"首过"在 L17 §17.4.5；"为什么放最外层"在 L17 §17.4.6。本课重点在"缓存怎么工作"。）

**读这段代码的钥匙：`routes` 字段不是"数据"，是"说明书"。**
构造器里**什么都没算** —— 只是装配了一个"**订阅时才查缓存**"的 Flux；真正干活发生在每次 `getRoutes()` 被订阅时（也就是每个请求的匹配步骤，L23）。
"订阅时查缓存"这个魔法是谁提供的？→ 下一节：`CacheFlux`。

---

## 22.4 发动机拆开看：`CacheFlux` 到底做了什么 ★

出处先说清（上一版没讲、但很重要）：`CacheFlux` **不是 SCG 的类**，是 **Reactor 官方扩展包 reactor-extra** 里的通用小工具（`reactor.cache.CacheFlux`）。SCG 的 pom 里显式依赖它（`spring-cloud-gateway-server/pom.xml:86-89`），全仓库**只有这两个缓存类**用它；
（冷知识：该类在 reactor-extra 3.5.1 里**已被标 `@Deprecated`**，但 SCG 4.1.5 仍在用。）

核心方法 `lookup(cacheMap, key, valueClass)`（源码去注释）：

```java
public static <KEY, VALUE> FluxCacheBuilderMapMiss<VALUE> lookup(
        Map<KEY, ? super List> cacheMap, KEY key, Class<VALUE> valueClass) {
    return otherSupplier ->                              // otherSupplier = onCacheMissResume(this::fetch)
            Flux.defer(() -> {                           // ★ 每次订阅时才执行
                Object fromCache = cacheMap.get(key);
                if (fromCache == null) {                 // 未命中：回源
                    return otherSupplier.get()
                            .materialize()               // ① 把 onNext / onError / onComplete 变成 Signal 对象
                            .collectList()               // ② 收成一个 List<Signal>
                            .doOnNext(signals -> cacheMap.put(key, signals))   // ③ 顺手存入缓存
                            .flatMapIterable(Function.identity())
                            .dematerialize();            // ④ 还原成信号放行（本次请求正常返回）
                }
                else if (fromCache instanceof List) {    // 命中：重放
                    List<Signal<VALUE>> fromCacheSignals = (List<Signal<VALUE>>) fromCache;
                    return Flux.fromIterable(fromCacheSignals)
                               .dematerialize();
                }
                else {
                    return Flux.error(new IllegalArgumentException("Content of cache for key " + key + " is not a List"));
                }
            });
}
```

四步人话版：

1. **`Flux.defer`**：**每次订阅都重新查一遍缓存** —— 这正是"刷新后下一个请求立刻生效"的原因（下一个订阅发现 key 已换成新快照）
2. **未命中**：订阅 `fetch()`（真实回源 + 编译）→ `materialize` 把这段流的**完整信号序列**（值、完成、错误）转成对象 → `collectList` 收成 `List<Signal<Route>>` → **存进 map** → 再 `dematerialize` 还原，把本次结果正常放行
3. **命中**：`Flux.fromIterable(signals).dematerialize()` —— **只重放信号，不碰来源、不编译**
4. **为什么缓存 `List<Signal>` 而不是 `List<Route>`**：流不只有"值"，还有"完成 / 错误"两个信号；`materialize`/`dematerialize` 保证**重放出来的流与原始流形状完全一致**（含错误）。`Signal` 就是"信号的对象形态"

> 于是 `CachingRouteLocator` 的"缓存"本质可以一句话说清：
> **一个 Map（key=`"routes"`）+ 一个通用工具（CacheFlux）**；缓存内容是上一次编译结果的**信号快照**，换缓存就是 `cache.put(key, 新快照)`。

---

## 22.5 配角与"为什么没默认装配"：`CachingRouteDefinitionLocator`

配角源码 —— 和主角"同一模板抄两遍"：

```java
public class CachingRouteDefinitionLocator
        implements RouteDefinitionLocator, ApplicationListener<RefreshRoutesEvent> {

    private static final String CACHE_KEY = "routeDefs";
    private final RouteDefinitionLocator delegate;
    private final Flux<RouteDefinition> routeDefinitions;
    private final Map<String, List> cache = new ConcurrentHashMap<>();

    public CachingRouteDefinitionLocator(RouteDefinitionLocator delegate) {
        this.delegate = delegate;
        routeDefinitions = CacheFlux.lookup(cache, CACHE_KEY, RouteDefinition.class).onCacheMissResume(this::fetch);
    }
    private Flux<RouteDefinition> fetch() { return this.delegate.getRouteDefinitions(); }   // 注意：不排序
    @Override
    public Flux<RouteDefinition> getRouteDefinitions() { return this.routeDefinitions; }
    public Flux<RouteDefinition> refresh() { this.cache.clear(); return this.routeDefinitions; }
    @Override
    public void onApplicationEvent(RefreshRoutesEvent event) {
        fetch().materialize().collect(Collectors.toList())
                .doOnNext(routes -> cache.put(CACHE_KEY, routes)).subscribe();
    }
}
```

和主角的差异（"学一得二"小结）：

| 点 | 定义缓存 | 运行缓存 | 说明 |
|---|---|---|---|
| 缓存 key | `"routeDefs"` | `"routes"` | 对称命名 |
| `fetch()` | 直接委托来源 | **多一步 `sort`** | order 决定匹配优先级（L23） |
| 接口 | 仅 `ApplicationListener` | `+ Ordered(0)`、`+ 事件发布` | 运行缓存"有身份" |
| 刷新动作 | 重算 + `put` / `clear()` | 重算 + `put` / scoped 分支 | 主角多"局部刷新" |

**为什么它没被默认装配**（事实，全仓库核过）：
- `GatewayAutoConfiguration` 里**没有它的 `@Bean`**；除测试外**无任何引用**；连 `refresh()` 也没有调用者

**为什么这样设计**（推测，标注）：见 22.2 —— 原料便宜、收益低。**另外它还不好装**，有两个源码级的坑：

1. `Composite` **按类型全收**（L19 §19.6）：直接注册一个 `CachingRouteDefinitionLocator` 包住某个已有来源 → **原来源 + 包装器都被收进 Composite → 定义重复**
2. 再加一个 `@Primary` 也不行：框架的 `routeDefinitionLocator` 已经占了 `@Primary`（`GatewayAutoConfiguration:239-243`）→ 同类 bean 出现**两个 primary** → 注入解析直接抛 `NoUniqueBeanDefinitionException`

**想用的话（✅ 正确姿势）**：给"自己新加的贵来源"在注册时就包上缓存（只暴露包装后的一个 bean）：

```java
// ❌ 不要：① 再注册一个 @Primary（冲突）  ② 包住框架已有来源（Composite 全收 → 重复）

// ✅ 干净姿势：自己的来源自己包
@Bean
public RouteDefinitionLocator myRemoteRouteDefinitionLocator(RemoteClient client) {
    return new CachingRouteDefinitionLocator(new MyRemoteRouteDefinitionLocator(client));
}
```

（测试里的用法正是这个姿势的缩影：`new CachingRouteDefinitionLocator(stub)`——包住"自己完全控制的来源"。想整体包住框架的 Composite 要接管同名 bean / 移除原 bean，生产少见——见 22.12。）

---

## 22.6 刷新时两级如何联动（收口 —— L30 前置）

**全量刷新**（`POST /actuator/gateway/refresh` 或注册中心心跳触发的 `RefreshRoutesEvent`）：

```
RefreshRoutesEvent（广播）
   ├─【若装了】CachingRouteDefinitionLocator.onApplicationEvent
   │        └─ fetch() 重查来源 → cache["routeDefs"] ← 新定义列表（覆盖）
   │
   └─ CachingRouteLocator.onApplicationEvent
            └─ fetch() → delegate.getRoutes()
                    └─ B 组重编译（L21）→ A 组 getRouteDefinitions()
                            └─（若装了定义缓存）先读 cache["routeDefs"]
            → updateCache → cache["routes"] ← 新 Route 快照（覆盖）
            → publishEvent(RefreshRoutesResultEvent)   （成功/失败都发）
```

**为什么是"主动重算"而不是"只清空、等下次请求"**：主动重算把编译成本放在**事件线程**（refresh 那一刻），下一个请求直接拿新快照；若只清空，则"第一个请求"要付全部编译成本（延迟尖刺）。SCG 选择了前者（`updateCache`），同时保留 `refresh()`（清空式）作为公开 API。

**运行缓存的两个分支**：全量（上面这条）与 **scoped 局部刷新**（`?metadata=group:g1`：只重算匹配 metadata 的路由 + 保留"不匹配的旧路由"拼合）——实现细节见 **L30**，本课只要知道"另一条分支存在、也走 `updateCache`"。

> ⚠️ **一个值得深挖的问题（OQ）**：两级都装时，**监听器回调顺序**是否保证"定义缓存先刷新、运行缓存后重编译"？
> 若 Route 先重编译、定义缓存还没刷新 → 会读到**旧定义**编译一版，直到下次刷新才修正。
> 两个类一个实现 `Ordered`（运行缓存 order=0）、一个不实现 —— 顺序语义值得验证（挂 22.12）。

---

## 22.7 顺带：项目里还有哪些"缓存"（一句话级别）

| 缓存 | 在哪层 | 默认 | 详情 |
|---|---|---|---|
| `CachingRouteLocator` | B 组（**本课主角**） | ✅ | 本课 + L17 §17.4 |
| `CachingRouteDefinitionLocator` | A 组（本课配角） | ❌ | 本课 |
| `routeFilterMap`（per-route 合并过滤器缓存） | D 组 `FilteringWebHandler` | ❌（`route-filter-cache-enabled`） | L25 §25.4 |
| SCLB 实例列表缓存 | 负载均衡层 | ✅（Caffeine，默认 **35s**） | L16 |

（都算"性能缓存"，但**只有前两个**跟"**刷新语义**"绑定——因为它们的失效都挂在 `RefreshRoutesEvent` 上。）

---

## 22.8 怎么自己验证

**完整可复制序列（含本轮实测输出）在 §22.1** —— 这里只留断点与加深实验。

断点建议（对着 22.3 的源码打）：
- `CachingRouteLocator#onApplicationEvent` / `#updateCache` / `#publishRefreshEvent` —— 看"重算 → `cache.put` 换快照"的瞬间
- `CacheFlux` 的 lambda（22.4）—— 看每次请求是"命中重放"还是"回源缓存"
- `CachingRouteDefinitionLocator#onApplicationEvent` —— 只有手动装配后才有意义
- ⚠️ 断点前先处理"环境噪声"：按 22.1 提示关掉 `spring.cloud.gateway.discovery.locator.enabled`（或抓在两次心跳之间），否则每 30s 一次自动刷新会打断观察
- （可选实验）按 22.5 ✅ 姿势**把"自定义来源"包上定义缓存** → 观察 `/routedefinitions` 是否也变成"要 refresh 才变"

---

## 22.9 常见理解偏差澄清（复习先读）

> ① **改定义 ≠ 改路由**：动态修改改的是 `RouteDefinition`（定义库）；要么手动 `POST /refresh`、要么等注册中心心跳自动刷新（如果开了，本 demo 就是 30s），才会**重新编译**出一份新的 Route 快照。匹配和转发用的始终是 Route。
> ② **"编译"不是 JVM 编译**：= 查工厂 + 参数绑定（字符串 → Config）+ `apply` 生成对象 + 谓词 `and()` 组合 + 过滤器排序组装。单次不贵；贵在"不缓存时按 **每请求 × 全量路由** 放大"（外加反复分配对象的 GC 压力）。缓存命中后，每请求只做"重放 `List<Signal>` + 跑谓词 `test`"。
> ③ **"在途请求继续跑旧快照"不是特殊机制**：Route / 谓词 / 过滤器是**编译后不可变产物**；刷新只是把 Map 里指向快照的**引用**换掉（`cache.put`）——手里已攥着旧 Route 引用的在途请求照常跑完，新请求订阅时才会 `cache.get` 到新快照。**神似 COW（写时复制）**，更准确的说法是"**不可变快照 + 原子引用替换**"；完整时序见 **L30 §30.5**。

---

## 22.10 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| `CacheFlux` 的**逐行实现** | ⚪ 本课已拆到"四步"；更细的信号边界不用背 |
| 手动装配定义缓存的完整姿势 | ❌ 用到再研究（22.5 给了示意与坑） |
| **两级（定义 vs 运行）的划分与位置** | ✅ **要会**（本课唯一 🔴） |
| **"订阅时查缓存 + 主动重算覆盖"的机制** | ✅ 要会（解释"为什么 refresh 后立刻生效"） |
| **CacheFlux 三件事（查/存/重放）+ 为什么存 Signal** | ✅ 要会（面试"网关缓存怎么设计的"一答一个准） |
| 两个类的差异（key/fetch/接口） | ✅ 要会（"同一模板抄两遍"） |

---

## 22.11 Self-Test

1. 改完路由为什么 `/routes` 不动？`POST /refresh` 后为什么就动了？（按 22.1 的链条答）
2. 为什么缓存放"运行层"（Route）而不是"定义层"？"原料便宜"的论据有哪些？
3. `CachingRouteLocator` 构造器里 `routes = CacheFlux.lookup(...)` 这行**什么时候真正干活**？每次订阅它会做哪两件事（命中 / 未命中）？
4. 为什么缓存的是 `List<Signal<Route>>` 而不是 `List<Route>`？`materialize` / `dematerialize` 各把什么变成什么？
5. `fetch()` 里那个 `sort` 为什么必须有？谁在用排序后的顺序？
6. 刷新发生时 `cache.put` 换掉的是什么？为什么在途请求不受影响、新请求立刻用新的？
7. 定义缓存为什么默认没装？"装配的两个坑"分别是什么？正确姿势怎么写？
8. `refresh()` 与 `onApplicationEvent` 的刷新有什么不同？为什么前者没有内部调用者？（开放题）

---

## 22.12 Open Questions

- [ ] **两级都装时的监听器顺序**：定义缓存（未实现 `Ordered`）与运行缓存（`Ordered`=0）谁先回调？
      若 Route 先重编译会读到旧定义 —— 需要实测/源码验证（这个顺序问题本身可能就是"定义缓存不默认装配"的佐证之一）。
- [ ] **reactor-extra 的 `CacheFlux` 已标 `@Deprecated`** —— SCG 未来会不会换实现（自研缓存 / `CacheMono`）？值得留意。
- [ ] 手动装配 `CachingRouteDefinitionLocator`（按 22.5 ✅ 姿势，包住自定义来源）后：
      `/routedefinitions` 是否也变成"要 refresh 才变"？—— 未实测（可作 L22 的可选实验）。
- [ ] `refresh()` 方法（两个缓存都有）**全仓库无内部调用者** —— 设计意图是什么？（公开 API / 历史遗留 / 给用户调）—— 未解。
- [ ] scoped 刷新（`?metadata=`）从 Actuator 到 `getNonScopedRoutes` 的完整行为 —— 未实测，留 **L30** 专课。
- [ ] `refresh` 返回 200 后"换快照完成"的精确时刻（异步）—— 目前靠 `sleep` 规避；理论上可监听 `RefreshRoutesResultEvent` 当完成信号，未验证。
