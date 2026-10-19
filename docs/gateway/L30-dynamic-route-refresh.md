# Lesson 30: How Dynamic Routes Refresh — `RefreshRoutesEvent` + `RouteRefreshListener`

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 30（**收官课**）
> 前置：**L22**（两级缓存 + 刷新时序首过）· **L17 §17.4.7/§17.4.8**（演示 / 事件广播）· **L8 §8.4**（行为层：写完必须 refresh）· **L27/L28**（在途请求、body 缓存）
> 口径：🔴 ① **刷新只换模型，不中断在途请求** ② `POST /actuator/gateway/refresh?metadata=group:g1` **局部刷新**的实现

**本课回答两个问题**：
① **主线**：路由改了，网关是怎么"生效"的？—— **事件触发 → 重编译一批新 Route → 原子替换缓存里的旧快照**；新请求拿新快照，在途请求攥着旧快照跑完。
② **分支**：不想全量重编译怎么办？—— **scoped 局部刷新**（`?metadata=group:g1`）：只重编译匹配组 + 保留其余。
（"局部 vs 全量"是第②问的**范围参数**；第①问才是本课的机制主线。）

---

## 30.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **刷新的本质** | 广播 `RefreshRoutesEvent` → `CachingRouteLocator` **重算 + 原子替换 cache**；在途请求继续用旧 Route/旧链，不受影响 |
| **②** | **两种刷新** | **全量**：重算全部路由；**局部（scoped）**：`?metadata=group:g1` → 只重算匹配 metadata 的 + 保留"不匹配的旧路由"拼合 |
| **③** | **触发源（5 个）** | Actuator `/refresh`、`HeartbeatEvent`/`ParentHeartbeatEvent`（心跳，去重）、`InstanceRegisteredEvent`、`RefreshScopeRefreshedEvent`、`ContextRefreshedEvent` |
| **④** | **结果通知** | `RefreshRoutesResultEvent`（成功/失败）由 `CachingRouteLocator` 发出 |

---

## 30.1 例子先行：三种触发方式，一个动作

```
① 手动：     POST /actuator/gateway/refresh                    （L8 §8.4 的日常操作）
② 注册中心： Nacos 实例表变化 → HeartbeatEvent → 去重后 reset()
③ 局部：     POST /actuator/gateway/refresh?metadata=group:g1  （只刷 group=g1 的路由）
                            ↓ 殊途同归
              publishEvent(new RefreshRoutesEvent(...))   → CachingRouteLocator 重算 cache
```

**实测**：全量刷新的可复制序列（含真实输出）在 **L22 §22.1**；本课再补两组现场实验：**scoped 局部刷新**（§30.4 末）与**在途请求穿越"删除 + 刷新"**（§30.5 末）。

---

## 30.2 触发源：`RouteRefreshListener` 全解

```java
public class RouteRefreshListener implements ApplicationListener<ApplicationEvent> {
    private final ApplicationEventPublisher publisher;
    private HeartbeatMonitor monitor = new HeartbeatMonitor();          // ★ 心跳去重器

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ContextRefreshedEvent) {
            ContextRefreshedEvent refreshedEvent = (ContextRefreshedEvent) event;
            boolean isManagementCtxt = WebServerApplicationContext
                .hasServerNamespace(refreshedEvent.getApplicationContext(), "management");
            boolean isLoadBalancerCtxt = refreshedEvent.getApplicationContext().getDisplayName() != null
                && refreshedEvent.getApplicationContext().getDisplayName().startsWith("LoadBalancerClientFactory-");
            if (!isManagementCtxt && !isLoadBalancerCtxt) {             // ★ 排除管理上下文/LB 子上下文
                reset();
            }
        }
        else if (event instanceof RefreshScopeRefreshedEvent || event instanceof InstanceRegisteredEvent) {
            reset();
        }
        else if (event instanceof ParentHeartbeatEvent) {               // 注册中心（父/引导上下文心跳）
            resetIfNeeded(((ParentHeartbeatEvent) event).getValue());
        }
        else if (event instanceof HeartbeatEvent) {                     // 注册中心（本上下文心跳）
            resetIfNeeded(((HeartbeatEvent) event).getValue());
        }
    }

    private void resetIfNeeded(Object value) {
        if (this.monitor.update(value)) {                               // ★ 值变了才触发（去重）
            reset();
        }
    }
    private void reset() {
        this.publisher.publishEvent(new RefreshRoutesEvent(this));      // 广播！
    }
}
```

**三点读法**：

1. **心跳必须去重**：注册中心心跳**周期性**到达（Nacos 默认几秒一次），如果每次都重算路由 → 持续重编译。`HeartbeatMonitor.update(value)` 只在**心跳的 value 变化**（代表实例表真的变了）且非 null 时返回 true
   （⚠️ **真实反例**：Alibaba 的 `NacosDiscoveryHeartBeatPublisher`——只在开了 gateway discovery locator 时激活——心跳值就是**自增序号**，于是"每次都变"→ 每 30s 触发一次全量刷新；实测见 **L22 §22.1**）
2. **为什么要排除 management / LoadBalancerClientFactory 上下文**：Actuator 管理端口是另一个子上下文、SCLB 每服务还有子上下文——它们的 `ContextRefreshedEvent` 不代表网关路由要刷新（否则启动期会重复触发 N 次）
3. **装配条件**（`GatewayAutoConfiguration:269-274`）：classpath 有 `HeartbeatMonitor`（即引了服务发现的客户端）、且 `spring.cloud.gateway.route-refresh-listener.enabled` 未关（`matchIfMissing = true` → **默认开**）

---

## 30.3 全量刷新：`CachingRouteLocator.onApplicationEvent`

```java
@Override
public void onApplicationEvent(RefreshRoutesEvent event) {
    try {
        if (this.cache.containsKey(CACHE_KEY) && event.isScoped()) {
            ... // ★ 局部刷新分支（30.4）
        }
        else {
            final Mono<List<Route>> allRoutes = fetch().collect(Collectors.toList());   // 重算全部（含 sort）
            allRoutes.subscribe(list -> updateCache(Flux.fromIterable(list)), this::handleRefreshError);
        }
    }
    catch (Throwable e) { handleRefreshError(e); }
}

private synchronized void updateCache(Flux<Route> routes) {
    routes.materialize()
        .collect(Collectors.toList())
        .subscribe(this::publishRefreshEvent, this::handleRefreshError);
}
private void publishRefreshEvent(List<Signal<Route>> signals) {
    cache.put(CACHE_KEY, signals);                                              // ★ 原子替换整个缓存值
    applicationEventPublisher.publishEvent(new RefreshRoutesResultEvent(this)); // ★ 结果通知
}
private void handleRefreshError(Throwable throwable) {
    log.error("Refresh routes error !!!", throwable);
    applicationEventPublisher.publishEvent(new RefreshRoutesResultEvent(this, throwable));  // 失败也通知
}
```

- `fetch()` = `delegate.getRoutes().sort(...)`：**整条编译链重跑一遍**（A 组来源 → B 组编译，L21/L22）
- `updateCache` 的 `synchronized` + `materialize().collectList()`：并发刷新时排队 + 把懒流**物化成快照**再换（L22 的"Why 先 materialize"）
- `cache.put` 之后：新请求命中新快照；**已经拿到旧 Route 的在途请求不受影响**（30.5）

---

## 30.4 局部刷新（scoped）：`?metadata=group:g1` 的实现 ★

> **白话版**：**全量刷新** = 把所有路由**全部重新编译一遍**、整份快照换新（`POST /refresh` 不带参数）；**局部刷新** = **只重编译 `metadata` 匹配的那一组**，其余路由**沿用旧快照**、直接拼进结果（`POST /refresh?metadata=group:g1`）。就像"整本书重印" vs "只重印第 3 章、其余拿旧版装订"。它不是"只刷一条"，粒度是**一组**。
> ⚠️ **关键**：它**不会自动"找出变化"**——是你用 `metadata` "**点名**"告诉它刷哪一组；没点到的组保持旧对象不变（**改了也不会生效**，得等全量）。

### ① Actuator 入口：把 query 参数变成"带 metadata 的事件"

```java
@PostMapping("/refresh")
public Mono<Void> refresh(@RequestParam(value = "metadata", required = false) List<String> byMetadata) {
    publishRefreshEvent(byMetadata);
    return Mono.empty();
}
private void publishRefreshEvent(List<String> byMetadata) {
    RefreshRoutesEvent event;
    if (!CollectionUtils.isEmpty(byMetadata)) {
        event = new RefreshRoutesEvent(this, convertToMap(byMetadata));    // ★ scoped 事件：{"group": "g1"}
    }
    else {
        event = new RefreshRoutesEvent(this);                              // 全量事件
    }
    this.publisher.publishEvent(event);
}
private Map<String, Object> convertToMap(List<String> byMetadata) {         // ["group:g1"] → {group=g1}
    return byMetadata.stream()
        .map(keyValueStr -> keyValueStr.split(":"))
        .collect(Collectors.toMap(kv -> kv[0], kv -> kv.length > 1 ? kv[1] : null));
}
```

```java
// RefreshRoutesEvent
public boolean isScoped() { return !CollectionUtils.isEmpty(getMetadata()); }   // 有 metadata = scoped
```

### ② 匹配规则：`RouteLocator.getRoutesByMetadata` / `matchMetadata`

```java
default Flux<Route> getRoutesByMetadata(Map<String, Object> metadata) {
    return getRoutes().filter(route -> matchMetadata(route.getMetadata(), metadata));
}
static boolean matchMetadata(Map<String, Object> toCheck, Map<String, Object> expectedMetadata) {
    if (CollectionUtils.isEmpty(expectedMetadata)) { return true; }             // 空 → 全匹配
    return toCheck != null && expectedMetadata.entrySet().stream()
        .allMatch(kv -> toCheck.containsKey(kv.getKey()) && toCheck.get(kv.getKey()).equals(kv.getValue()));
    // "路由的 metadata 必须包含 expected 的每个 k/v"（子集语义）
}
```

→ **约定**：路由的 yml 里写 `metadata: { group: g1 }`，`?metadata=group:g1` 才会选中它。

### ③ `CachingRouteLocator` 的 scoped 分支：新 scoped + 旧的非 scoped

```java
if (this.cache.containsKey(CACHE_KEY) && event.isScoped()) {
    final Mono<List<Route>> scopedRoutes = fetch(event.getMetadata()).collect(Collectors.toList())
        .onErrorResume(s -> Mono.just(List.of()));                      // ★ 失败兜底：空列表（不伤其它路由）
    scopedRoutes.subscribe(scopedRoutesList -> {
        updateCache(Flux.concat(Flux.fromIterable(scopedRoutesList), getNonScopedRoutes(event))
            .sort(AnnotationAwareOrderComparator.INSTANCE));            // ★ 拼合 + 重排
    }, this::handleRefreshError);
}
// getNonScopedRoutes：把"旧缓存里不匹配 scoped metadata 的路由"原样保留
private Flux<Route> getNonScopedRoutes(RefreshRoutesEvent scopedEvent) {
    return this.getRoutes()
        .filter(route -> !RouteLocator.matchMetadata(route.getMetadata(), scopedEvent.getMetadata()));
}
```

**效果**：只有 `group=g1` 的路由被重编译；其它路由**沿用旧对象**（省编译、影响面小）——适合注册中心按分组推送的场景。

**两个边界**（源码事实）：
- **前提**：`cache.containsKey(CACHE_KEY)` —— 首次还未缓存过 → 退回全量
- **失败**：scoped fetch 出错 → 当空列表处理（旧的非 scoped 仍在；scoped 组内旧路由被"清掉"，下次刷新再来）

### 现场实验：scoped 局部刷新（2026-10-02 实测）

```bash
GW=http://127.0.0.1:8080/actuator/gateway

# ① 加两条路由（只写定义！）：A=group:g1、B=group:g2
curl -s --noproxy '*' -o /dev/null -w 'POST A -> %{http_code}\n' -X POST $GW/routes/tmp-l30-a \
  -H 'Content-Type: application/json' -d '{"id":"tmp-l30-a","uri":"http://127.0.0.1:8081",
  "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l30a/**"}}],"metadata":{"group":"g1"}}'
# POST A -> 201
curl -s --noproxy '*' -o /dev/null -w 'POST B -> %{http_code}\n' -X POST $GW/routes/tmp-l30-b \
  -H 'Content-Type: application/json' -d '{"id":"tmp-l30-b","uri":"http://127.0.0.1:8081",
  "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l30b/**"}}],"metadata":{"group":"g2"}}'
# POST B -> 201

# ② 只刷 group=g1
curl -s --noproxy '*' -o /dev/null -w 'scoped g1 -> %{http_code}\n' -X POST "$GW/refresh?metadata=group:g1"; sleep 1
curl -s --noproxy '*' $GW/routes | python3 -c "import json,sys;d=json.load(sys.stdin);print('count:',len(d),'| tmp-l30 ids:',[x['route_id'] for x in d if 'tmp-l30' in x['route_id']])"
# count: 10 | tmp-l30 ids: ['tmp-l30-a']        ← ★ 只有 g1 的被编译进快照；B 不在

# ③ 全量刷新：B 这时才出现
curl -s --noproxy '*' -o /dev/null -w 'full -> %{http_code}\n' -X POST $GW/refresh; sleep 1
# count: 11 | tmp-l30 ids: ['tmp-l30-b', 'tmp-l30-a']

# ④ 删除 A + scoped g1：验证"被删的 scoped 路由"会消失
curl -s --noproxy '*' -o /dev/null -w 'DELETE A -> %{http_code}\n' -X DELETE $GW/routes/tmp-l30-a
curl -s --noproxy '*' -o /dev/null -w 'scoped g1 -> %{http_code}\n' -X POST "$GW/refresh?metadata=group:g1"; sleep 1
# count: 10 | tmp-l30 ids: ['tmp-l30-b']        ← ★ A 没了、B 还在
```

网关日志同口径（编译发生在哪一轮一目了然）：

```
15:36:04.938 RouteDefinition tmp-l30-a applying ...        ← scoped g1 只编译了 A
15:36:05.900 RouteDefinition tmp-l30-b applying ...        ← 全量刷新才编译 B
15:36:05.903 RouteDefinition tmp-l30-a applying ...
```

（清理：`DELETE tmp-l30-b` + 全量 refresh。）

---

## 30.5 为什么"刷新只换模型，不中断在途请求"

```
请求 A（在途）：
  handle() 时已从 cache 取到旧 Route 列表 → exchange.GATEWAY_ROUTE_ATTR = 旧 Route
  → new DefaultGatewayFilterChain(旧列表合并出的链) → 正在跑（可能已转发到 Netty）

刷新（任何触发源）：
  cache.put(CACHE_KEY, 新信号快照)      ← 只是【换了一个引用】，不碰旧对象

请求 B（刷新后到达）：
  CacheFlux.lookup(cache, ...) → 拿【新快照】→ 用新 Route / 新链
```

三个支撑点（都来自前面课程）：
1. **每请求独立**：chain 每请求 `new`（L26 §26.5）、Route 引用放在 exchange 属性（L23），没有"全局可变状态"被刷新动作修改
2. **换的是快照引用**：`cache.put` 原子替换 ConcurrentHashMap 的 value；旧列表/旧 Route 只要还有请求引用就不会被回收（引用计数归 JVM 管）
3. **新旧不共享可变状态**：Route 及其谓词/过滤器对象是**编译期不可变产物**（L21）；刷新只是生成**一批新对象**

**类比/术语**：电视台换节目单——正在播的节目不掐断，下一档才按新单子走。这个模式常被称为 **不可变快照 + 原子引用替换**（immutable snapshot swap）——**神似 COW（写时复制）**，但注意：不是 `CopyOnWriteArrayList` 那种"对同一集合写时自动复制"，而是"手工构建**新快照** → 原子换 Map 里的引用"。

> 对照 L22：**运行缓存**是"重算 + 覆盖"（本课）；**定义缓存**（若装配）是"清空 + 下次懒查"——替换方式不同，但都不动在途请求。

### 现场实验：在途请求穿越一次"删除 + 刷新"（2026-10-02 实测）

```bash
APP=http://127.0.0.1:8080; GW=$APP/actuator/gateway; BASE=/tmp-l30slow

# ① 准备：一条指向 provider 的路由（/slow 会 sleep 4s）；确认已在运行视图
curl -s --noproxy '*' -o /dev/null -w 'POST -> %{http_code}\n' -X POST $GW/routes/tmp-l30-slow \
  -H 'Content-Type: application/json' -d "{\"id\":\"tmp-l30-slow\",\"uri\":\"http://127.0.0.1:8081\",
  \"predicates\":[{\"name\":\"Path\",\"args\":{\"_genkey_0\":\"$BASE/**\"}}],
  \"filters\":[{\"name\":\"StripPrefix\",\"args\":{\"_genkey_0\":\"1\"}}]}"
# POST -> 201
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST $GW/refresh; sleep 1.5
curl -s --noproxy '*' -o /dev/null -w '运行视图核对 -> %{http_code}\n' $GW/routes/tmp-l30-slow
# 运行视图核对 -> 200

# ② 在途请求（下游 sleep 4s）——启动 0.8s 后，把路由【删掉】并【全量刷新】
curl -s --noproxy '*' -o /tmp/slow.out -w '%{http_code} time=%{time_total}s\n' "$APP$BASE/slow?ms=4000" > /tmp/slow.meta &
sleep 0.8
curl -s --noproxy '*' -o /dev/null -w 'DELETE -> %{http_code}\n' -X DELETE $GW/routes/tmp-l30-slow
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST $GW/refresh
wait
cat /tmp/slow.meta; cat /tmp/slow.out
# 200 time=4.097s
# {"sleptMs":4000,"port":8081}          ← ★ 在途请求毫发无伤：200 + 正确 body
```

对照数据（同一台机器）：

| 请求 | 结果 |
|---|---|
| 在途请求（途中删除 + 刷新） | `200`，两次实测 `4.10s` / `5.78s`（**5.78s 是偶发抖动**，与刷新无稳定关联）|
| 基线（无干扰经网关） | `4.03s / 4.26s` |
| 直连 provider | `4.00s` |
| **刷新后的新请求** | **404**（路由已删，新快照里没有它）|

日志生命线：`Route matched: tmp-l30-slow` 出现在在途请求开始时；删除 + 刷新期间**没有任何针对该请求的中断/错误**——它攥着自己的旧 Route 跑完了全程。

---

## 30.6 刷新链路全景（事件 → 各监听者）

`RefreshRoutesEvent` 的监听者（main 全仓库核对）：

| 监听者 | 反应 |
|---|---|
| `CachingRouteLocator` | **重算 Route 缓存**（本课主角） |
| `WeightCalculatorWebFilter` | 强制"再编译一遍"（首次 `blockLast()`、后续 `subscribe()`）→ 权重账本随新路由更新（L24 §24.5） |
| `FilteringWebHandler` | `routeFilterMap.clear()`（**开了** per-route 过滤器缓存才清，L25 §25.4） |
| `CachingRouteDefinitionLocator` | 重算定义缓存（**手动装配才有**，L22） |
| `RouteDefinitionMetrics` | 统计路由定义数量（刷新 gauge） |
| `CorsGatewayFilterApplicationListener` | 刷新后重算各路由 CORS 配置并写回 `RoutePredicateHandlerMapping` |
| （发布结果） | `RefreshRoutesResultEvent`：`isSuccess()` / `getThrowable()` —— main 中**只有发布者**，消费方留给外部观测/测试 |

```
触发源(5)  ──► RefreshRoutesEvent ─┬─► CachingRouteLocator    （换运行快照）──► RefreshRoutesResultEvent
                                   ├─► WeightCalculatorWebFilter（账本随动）
                                   ├─► FilteringWebHandler       （清过滤器缓存·可选）
                                   └─► …（上表）
```

---

## 30.7 怎么自己验证

```bash
GW=http://localhost:8080/actuator/gateway
# ① 全量：加一条路由 → 不刷看不到 → 刷后可见（L8 §8.4 的复现）
# ② 局部：加一条带 metadata 的路由（或已有 demo 路由配置 metadata: {group: g1}）
curl -s --noproxy '*' -X POST $GW/routes/tmp-l30 -H 'Content-Type: application/json' -d '{
  "id":"tmp-l30","uri":"http://localhost:8081",
  "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l30/**"}}],
  "metadata":{"group":"g1"}}'
#   先普通刷新（或无刷新）观察，再：
curl -s --noproxy '*' -X POST "$GW/refresh?metadata=group:g1"     # 只刷 group=g1
curl -s --noproxy '*' $GW/routes | python3 -m json.tool | grep route_id
# ③ 断点：RouteRefreshListener#onApplicationEvent → CachingRouteLocator#onApplicationEvent
#          （看 isScoped 分支选择）、publishRefreshEvent（cache.put 那一刻）
# ④ 清理：DELETE tmp-l30 + POST /refresh
```

---

## 30.8 掌握边界（值不值得深挖）

> 本课定位：**Stage 5 收官**——两个 🔴 都要会；源码细节用到再查。两组现场实验见 §30.4 / §30.5 末。

| 内容 | 口径 |
|---|---|
| 心跳事件的产生（Nacos 客户端侧） | ❌ 归服务发现课（L44 生态打通） |
| `RefreshRoutesResultEvent` 的消费 | ⚪ main 无消费方；知道"成功/失败通知"即可 |
| **两种刷新（全量 / scoped）的实现差异** | ✅ **本课核心 🔴** |
| **"只换模型不中断在途"的机制** | ✅ 要会（面试常问） |
| 触发源 5 种 + 心跳去重 | ✅ 要会（解释"注册中心改了为什么网关会自己变"） |
| `RouteRefreshListener` 排除子上下文的细节 | ⚪ 知道"防重复触发"即可 |

---

## 30.9 Self-Test

1. 刷新有哪 5 个触发源？注册中心心跳为什么需要 `HeartbeatMonitor` 去重？
2. 全量刷新时 `CachingRouteLocator` 做了哪几步？（fetch → materialize → put → result event）
3. `POST /refresh?metadata=group:g1` 从 HTTP 参数到"scoped 事件"经过哪几段代码？
4. `matchMetadata` 的匹配规则是什么？yml 里路由怎么写才会被 scoped 刷新命中？
5. scoped 分支为什么要把"新 scoped 路由"和"旧非 scoped 路由"**拼起来**？直接只放新 scoped 会怎样？
6. 凭什么说"刷新不中断在途请求"？（答出每请求独立 + 快照引用替换 + 不可变产物）
7. `RefreshRoutesResultEvent` 在什么时机发？成功与失败怎么区分？

---

## 30.10 Open Questions

- [x] scoped 刷新对"**被删除的 scoped 路由**"的行为 —— **§30.4 实验已实测**：删除 `group=g1` 的路由后再 `POST /refresh?metadata=group:g1`，它从 `/routes` 消失（scoped fetch 返回空 + 非 scoped 保留里不含它）→ **删除生效**。
- [ ] `matchMetadata` 的 `equals` 对非字符串 metadata（数字/布尔）的类型匹配 —— yml 解析后的类型差异可能踩坑，未实测。
- [ ] `RefreshRoutesResultEvent` 在真实项目中的消费（自研观测/发布钩子）—— main 无消费方，待 L49/L50 生产课回看。
- [ ] `HeartbeatMonitor` 的去重判定（值 equal 则不触发）在跨实例滚动发布下的灵敏度 —— 未细究。

---

> 🏁 **Stage 5 收官**：从 L17（装配全景）到 L30（动态刷新），主干源码链闭合：
> **定义 → 编译 → 缓存 → 匹配 → 链装配 → 链执行 → 转发四步 → body → 粘性 → 刷新**。
> 下一站 **Stage 6**（L31 起）：进入 xhs-project 的真实生产坑（XFF/身份头/SSE/连接池…）。
