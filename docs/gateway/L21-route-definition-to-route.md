# Lesson 21: RouteDefinition → Route（编译）— "定义是源码、Route 是编译产物"

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 21
> 前置：**L17 17.3.5②**（首过 `convertToRoute`）· L19（定义的来源）· L20（bind/工厂 —— 编译的"弹药"）
> 口径：把 `convertToRoute` 从"提过"升级为**专课**：**两级模型 + 谓词 `and()` 组合 + 过滤器编译排序 + Route 组装**；与 17.3.5② 重叠处回指，不重复

---

## 21.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **两级模型** | `RouteDefinition`（**配置态**·声明式）→ `Route`（**运行态**·可执行）—— "定义是源码、Route 是编译产物" |
| **②** | **谓词编译** | N 个谓词 → 逐个 `and()` → **合成一个** `AsyncPredicate`（短路语义） |
| **③** | **过滤器编译** | `default-filters` + 路由 filters → **`GatewayFilter` 列表**（**编译期**就排好序） |

> **为什么有这一课**：L19 讲"定义从哪来"、L20 讲"参数怎么变对象"；这一课把两者**组装成 Route** —— 之后 C/D 组（L23~L27）用到的全是这里的产物。

---

## 21.1 例子先行：同一个路由的两个视图

> **术语约定**：**定义视图** = `GET /actuator/gateway/routedefinitions`（yml / Actuator 写进去的那份）；**运行视图** = `GET /actuator/gateway/routes`（编译后的 Route）。其它课里出现过的"配置态 / 运行态"指同一对概念。

**定义视图**（`GET /actuator/gateway/routedefinitions` 的 `demo-provider`，实测）：

```json
{
  "id": "demo-provider",
  "predicates": [ { "name": "Path", "args": { "_genkey_0": "/provider/**" } } ],
  "filters": [
    { "name": "StripPrefix",       "args": { "_genkey_0": "1" } },
    { "name": "AddRequestHeader",  "args": { "_genkey_0": "X-Demo-Route",           "_genkey_1": "demo-provider" } },
    { "name": "AddResponseHeader", "args": { "_genkey_0": "X-Demo-Gateway-Version", "_genkey_1": "1.0" } },
    { "name": "DemoHeader",        "args": { "_genkey_0": "X-Demo-Factory",         "_genkey_1": "hello-from-my-factory" } }
  ],
  "uri": "http://localhost:8081",
  "metadata": {}, "order": 0
}
```

**运行视图（运行态 Route）**（**Route 同样可以从 actuator 看**：`GET /actuator/gateway/routes` —— 下面这版是现场 curl；18.7 的日志版 `Route.toString()` 是它的**单行全量版**，内容等价）：

```json
{
  "route_id": "demo-provider",
  "uri": "http://localhost:8081",
  "order": 0,
  "predicate": "Paths: [/provider/**], match trailing slash: true",
  "filters": [
    "[[StripPrefix parts = 1], order = 1]",
    "[[AddRequestHeader X-Demo-Route = 'demo-provider'], order = 2]",
    "[[AddResponseHeader X-Demo-Gateway-Version = '1.0'], order = 3]",
    "[…DemoHeader—lambda…, order = 4]"
  ]
}
```

**为什么 actuator 能看？** 它在 `serialize(Route)` 里只挑 6 个键（源码）：

```java
Map<String, Object> serialize(Route route) {
    r.put("route_id", route.getId());
    r.put("uri", route.getUri().toString());
    r.put("order", route.getOrder());
    r.put("predicate", route.getPredicate().toString());        // 谓词对象 → 字符串
    if (!CollectionUtils.isEmpty(route.getMetadata())) { r.put("metadata", route.getMetadata()); }
    for (GatewayFilter f : route.getFilters()) { filters.add(f.toString()); }   // 过滤器对象 → toString 列表
    r.put("filters", filters);
    return r;
}
```

（两版差异很小：actuator 版 `metadata` 为空时省略、`filters` 是 **toString 字符串列表**；日志版是单行全量 —— **内容等价，都能看到 order 1..4**。）

**逐项对照**（编译到底改了什么）：

| 维度 | 定义视图 | 运行视图 | 编译做了什么 |
|---|---|---|---|
| 谓词 | `[{"name":"Path","args":{_genkey_0}}]` —— **字符串列表** | `predicate=Paths: [/provider/**], ...` —— **一个对象** | 查工厂 → bind → apply → **and 合成一个** |
| 过滤器 | 4 个 `{name, args}` | 4 条过滤器（**带 order 1..4**，内部是对象、视图里是 toString） | 查工厂 → bind → apply → **编译期编号+排序** |
| uri | `"http://localhost:8081"`（字符串） | `URI` 对象 | **不是编译期改的**：字符串→`URI` 在**定义绑定/反序列化期**就完成了（yml 走 Binder 转换器；Actuator 走 Jackson；文本构造走 `URI.create`）—— 编译只是原样搬运 `getUri()` |
| id/order/metadata | 同左 | 同左 | `Route.async` **原样搬运** |

---

## 21.2 编译总图：三行代码 + 上下文

```java
// RouteDefinitionRouteLocator
private Route convertToRoute(RouteDefinition routeDefinition) {                    // ★ 全场就这三行
    AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);   // ① 谓词编译
    List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);                    // ② 过滤器编译
    return Route.async(routeDefinition).asyncPredicate(predicate)                        // ③ 组装
            .replaceFilters(gatewayFilters).build();
}
```

**什么时候编**（回指 19.x 的"编译期"）：

```java
public Flux<Route> getRoutes() {
    return getRoutes(this.routeDefinitionLocator.getRouteDefinitions());   // A 组拉定义
}
private Flux<Route> getRoutes(Flux<RouteDefinition> routeDefinitions) {
    Flux<Route> routes = routeDefinitions.map(this::convertToRoute);       // ★ 逐条编译
    if (!gatewayProperties.isFailOnRouteDefinitionError()) {
        routes = routes.onErrorContinue((error, obj) -> {                  // ← 容错开关
            logger.warn("RouteDefinition id " + ((RouteDefinition) obj).getId()
                    + " will be ignored. Definition has invalid configs, " + error.getMessage());
        });
    }
    return routes;
}
```

- 触发点：**启动时 + 每次 refresh**（产物缓存进 `CachingRouteLocator` —— L17 17.4）
- `failOnRouteDefinitionError`（默认 true）：false 时单条定义编译失败**只警告、跳过该条**

图：

```
                 ┌─ combinePredicates → 【1 个】AsyncPredicate ──┐
RouteDefinition ─┼─ getFilters        → 【N 个】GatewayFilter ───┼─► Route.async(..)…build() ─► Route
（配置态）        └─ id/uri/order/metadata（原样搬运）───────────┘                              （运行态）
```

---

## 21.3 谓词编译：N 个谓词 → **1 个** `AsyncPredicate`（and 串联）

```java
private AsyncPredicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
    List<PredicateDefinition> predicates = routeDefinition.getPredicates();
    if (predicates == null || predicates.isEmpty()) {
        // this is a very rare case, but possible, just match all
        return AsyncPredicate.from(exchange -> true);              // ★ 没谓词 = 匹配所有
    }
    AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));
    for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
        AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
        predicate = predicate.and(found);                          // ★ 逐个 and 串起来
    }
    return predicate;
}
```

逐行：
- **空谓词 → `exchange -> true`**（匹配所有；yml 里不写 predicates 就会走到这）
- 第 1 个先编译成 `predicate`；其余的**从左到右** `and()` 串上去
- `lookup`（编译期"查工厂 + bind + apply"）已在 **L20 §20.3/§20.7** 讲过，这里只看"组合"

`and()` 的真身（`AsyncPredicate` 接口的默认方法 + 实现）：

```java
// AsyncPredicate 接口
default AsyncPredicate<T> and(AsyncPredicate<? super T> other) {
    return new AndAsyncPredicate<>(this, other);
}

// AndAsyncPredicate.apply：左假即假（短路）
@Override
public Publisher<Boolean> apply(T t) {
    return Mono.from(left.apply(t)).flatMap(result ->
            !result ? Mono.just(false)              // 左 false → 直接 false，右【不执行】
                     : Mono.from(right.apply(t)));  // 左 true  → 才执行右
}
```

**和 L04 对上**：yml 里同一路由写多个谓词 = **AND**；顺序 = 定义顺序；**短路**（前面假，后面的谓词不会被执行 —— 所以把"便宜的谓词"放前面是有意义的实践建议）。

---

## 21.4 过滤器编译：`default-filters` + 路由 filters → **有序列表**

```java
private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
    List<GatewayFilter> filters = new ArrayList<>();
    // TODO: support option to apply defaults after route specific filters?
    if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
        filters.addAll(loadGatewayFilters(routeDefinition.getId(),
                new ArrayList<>(this.gatewayProperties.getDefaultFilters())));   // ① 先加 default-filters
    }
    final List<FilterDefinition> definitionFilters = routeDefinition.getFilters();
    if (!CollectionUtils.isEmpty(definitionFilters)) {
        filters.addAll(loadGatewayFilters(routeDefinition.getId(), definitionFilters));  // ② 再加路由自己的
    }
    AnnotationAwareOrderComparator.sort(filters);                                // ③ 编译期就排序
    return filters;
}
```

三个要点：

1. **`default-filters` 是"编译期 merge"**（不是运行期逐请求判断）—— 所以每条路由的 filters 里**都有一份**（L05 的疑问在这收口）
2. **`loadGatewayFilters`**（单条过滤器 → 对象）的骨架：查工厂 → `configurationService...bind()`（**L20 的 5 步链**）→ `HasRouteId` 处理 → `factory.apply(config)` → 包 order：
   ```java
   Object configuration = this.configurationService.with(factory)
           .name(definition.getName()).properties(definition.getArgs())
           .eventFunction(...FilterArgsEvent...).bind();
   if (configuration instanceof HasRouteId) { ((HasRouteId) configuration).setRouteId(id); }
   GatewayFilter gatewayFilter = factory.apply(configuration);
   if (gatewayFilter instanceof Ordered) { ordered.add(gatewayFilter); }
   else { ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1)); }   // ★ 编译期编号：1,2,3,4…
   ```
3. **和 18.7 的 16 条链日志对上**：`[[StripPrefix parts = 1], order = 1] … [DemoHeader, order = 4]` —— 这里的 **order=1..4 就是编译期 `i+1` 给的**（随后与 12 个全局过滤器 merge 排序，是 D 组 / L25 的事）

---

## 21.5 组装：`Route.async(...)` → `.build()`

```java
// Route（route 包）
public static AsyncBuilder async(RouteDefinition routeDefinition) {
    return new AsyncBuilder().id(routeDefinition.getId())
            .uri(routeDefinition.getUri())
            .order(routeDefinition.getOrder())
            .metadata(routeDefinition.getMetadata());     // ★ 只搬这四个；谓词/过滤器由编译单独喂
}

public Route build() {
    Assert.notNull(this.id, "id can not be null");
    Assert.notNull(this.uri, "uri can not be null");
    AsyncPredicate<ServerWebExchange> predicate = getPredicate();
    Assert.notNull(predicate, "predicate can not be null");     // ★ 三道必填校验
    return new Route(this.id, this.uri, this.order, predicate, this.gatewayFilters, this.metadata);
}
```

- **Route 的六个字段**：`id / uri / order / predicate / gatewayFilters / metadata` —— 这张清单请背下来：**后面 C 组、D 组、转发、观测全都围着它转**（18.1 的"差异数据"就是它）
- `replaceFilters(...)`：**替换**（不是追加）—— 因为搬运时没带 filters，是编译结果整体喂进来
- 产物去向：`CachingRouteLocator` 缓存（17.4）；**L22 将专讲"两级缓存"**

---

## 21.6 两级模型对照表（含"怎么看 / 怎么变"）

| | **`RouteDefinition`（配置态）** | **`Route`（运行态）** |
|---|---|---|
| 形态 | uri 字符串；predicates/filters 是 **`{name, args}` 列表** | uri 是 `URI`；predicate 是 **`AsyncPredicate` 对象**；filters 是**对象列表（带 order）** |
| 谁产出 | **A 组**（L19） | **B 组 `convertToRoute`**（本课） |
| 存哪 | yml / 内存 / Redis（L19） | `CachingRouteLocator` 缓存（L17 17.4） |
| 怎么查看 | `GET /actuator/gateway/routedefinitions` | `GET /actuator/gateway/routes` |
| 怎么"变" | 改定义（Actuator/配置中心）**+ refresh** | 重编译（= 再跑一遍本课） |
| 谁消费 | B 组（编译输入） | **C 组匹配 / D 组执行**（L23~L27） |

---

## 21.7 怎么自己验证

```bash
GW=http://localhost:8080/actuator/gateway
# ① 两个视图对照（定义 vs 运行态）
curl -s --noproxy '*' $GW/routedefinitions | python3 -m json.tool | head -40
curl -s --noproxy '*' $GW/routes           | python3 -m json.tool | head -40

# ② 改了定义不 refresh → /routes 不变（编译产物没重算）—— 回指 19.7
```

断点建议（配合 18.7 的调试方式）：
- `RouteDefinitionRouteLocator#convertToRoute` —— 看三行的输入输出
- `RouteDefinitionRouteLocator#combinePredicates` 的 `and` 循环 —— 看 N 个谓词怎么串成一条
- `RouteDefinitionRouteLocator#loadGatewayFilters` —— 看 `i + 1` 的编号在编译期落下

日志钩子（启动/刷新期 DEBUG）：
- `RouteDefinition matched: demo-provider` —— 来自编译完成处（`getRoutes` 的 map，源码 `logger.debug`）
- `RouteDefinition demo-provider applying filter {...} to StripPrefix` —— 来自 `loadGatewayFilters`（L18 18.7 的"其他日志"表里列了这条）

---

## 21.8 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| `Route.Builder` 的其它 builder 细节（filter() 追加等） | ❌ 用到再看 |
| 谓词/过滤器的工厂实现（Path 里怎么建 PathPattern…） | ❌ L24 谓词家族 / L25 过滤器装配 |
| **两级模型（定义 vs Route 的形态差异）** | ✅ **要会**（面试"网关路由在内存里长什么样"） |
| **谓词 `and()` 合成一个对象 + 短路** | ✅ 要会（和 L04 的 AND 语义对上） |
| **过滤器编译期排序 + `default-filters` merge** | ✅ 要会（L05 疑问的源码答案） |
| **Route 六字段清单** | ✅ 要会（后面所有课的公共词汇） |

---

## 21.9 Self-Test

1. `convertToRoute` 三行分别做什么？产物是什么？
2. 多个谓词在编译后是"多个对象"还是"一个对象"？怎么组合的？短发生在哪一侧？
3. `default-filters` 是编译期还是运行期生效？证据在哪（看哪条日志/哪个 order）？
4. 路由过滤器被编译成对象时，order `1..4` 是谁、什么时候给的？
5. （对照）`/routedefinitions` 和 `/routes` 两个视图的主要差异有哪三处？
6. `Route` 的六个字段是什么？哪个字段是 C 组每请求要"跑"的东西？

---

## 21.10 Open Questions

- [ ] `failOnRouteDefinitionError=false` 时，一条坏定义的**实际行为**（跳过 + WARN + 其它路由正常）—— 未实测（可造一条 args 故意写坏的路由试）。
- [ ] `and()` 链的**求值顺序**对"贵谓词"（ReadBody 等）的性能影响 —— 未实测；建议实践：便宜的谓词放前面。
- [ ] `Route.async(routeDefinition)` 搬运的 `metadata` 是**引用共享**（同 Map）还是拷贝？改定义会不会影响已编译 Route —— 未验证。
- [ ] 用 **Logpoint** 录一次 `combinePredicates` 的 and 循环（比如 `Path + Method + Header` 三谓词路由），看合成结构与短路顺序 —— 未实测（可与 19.x 的实验串起来做）。
