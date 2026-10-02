# Lesson 23: How a Route Is Matched — `RoutePredicateHandlerMapping.lookupRoute()`

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 23
> 前置：**L17 §17.5.1**（C 组首过：构造器四参数 / lookupRoute 三步）· **L18**（第 6 站 + 实测日志）· **L21 §21.3**（谓词 `and()` 组合）· **L22**（排序后的 Route 流从哪来）
> 口径：🔴 `filterWhen(...).next()` **只取第一条匹配**；命中后 put `GATEWAY_ROUTE_ATTR`，返回 `webHandler`（`FilteringWebHandler`）

**本课回答三个问题**：
① 一个请求是怎么"选中"某条路由的？（先中即停）
② 匹配阶段到底做了什么？为什么"谓词为假"和"完全没有路由"看起来都是 404？
③ 匹配时"顺手"留下的那些 exchange 属性，是给谁用的？

---

## 23.1 它干了什么：四个请求看懂"路由匹配"（可直接复制）

环境：demo-gateway(8080) + demo-provider(8081)。下面每个命令都可以直接执行（2026-10-02 实测，输出原样粘贴）。

### ① 命中：`GET /provider/hello`

```bash
curl -s --noproxy '*' -i http://127.0.0.1:8080/provider/hello
# HTTP/1.1 200 OK
# transfer-encoding: chunked
# X-Demo-WebFilter: hit-84
# Vary: Origin
# Vary: Access-Control-Request-Method
# Vary: Access-Control-Request-Headers
# Content-Type: application/json
# Date: Fri, 02 Oct 2026 03:53:03 GMT
# X-Demo-Gateway-Version: 1.0
#
# {"port":8081,"method":"GET","uri":"/hello","query":null,"headers":{"user-agent":"curl/8.5.0","accept":"*/*","x-demo-route":"demo-provider","x-demo-factory":"hello-from-my-factory","x-demo-trace-id":"57b4209cf2fa4a40a3f1673d5729dece","x-demo-from-gateway":"true","forwarded":"proto=http;host=\"127.0.0.1:8080\";for=\"127.0.0.1:48548\"","x-forwarded-for":"127.0.0.1","x-forwarded-proto":"http","x-forwarded-prefix":"/provider","x-forwarded-port":"8080","x-forwarded-host":"127.0.0.1:8080","host":"localhost:8081","content-length":"0"},"greeting":"hello world"}
```

两个"过滤器真的跑了"的铁证（先记住，L25/L27 会讲它们怎么进链）：
- 响应头 `X-Demo-Gateway-Version: 1.0` ← route filter `AddResponseHeader` 的 post 阶段写的
- 下游 echo 出来的 `x-demo-route: demo-provider`（`AddRequestHeader`）和 `x-demo-factory: hello-from-my-factory`（自定义 `DemoHeader` 过滤器，L14）

网关日志（`org.springframework.cloud.gateway: DEBUG`）：

```
Route matched: demo-provider
Mapping [Exchange: GET http://127.0.0.1:8080/provider/hello] to Route{id='demo-provider', uri=http://localhost:8081, order=0, predicate=Paths: [/provider/**], match trailing slash: true, gatewayFilters=[[[StripPrefix parts = 1], order = 1], [[AddRequestHeader X-Demo-Route = 'demo-provider'], order = 2], [[AddResponseHeader X-Demo-Gateway-Version = '1.0'], order = 3], [com.myxhs.demo.gateway.filter.DemoHeaderGatewayFilterFactory$$Lambda$1417/0x0000763718896270@57ddadb6, order = 4]], metadata={}}
```

**注意这行日志里的 `Route{...}`**：它不是 yml，而是 **L21 编译后的运行态 Route**（谓词字符串 + 过滤器对象 + order 1..4），来自 **L22 缓存快照**里的那一个对象。

### ② 同一个路由，谓词输入不同：`/predicate-lab/hello`

这条路由的谓词是 `Path=/predicate-lab/**` **并且** `DemoHeader=X-Demo-Env: gray`（两个谓词 `and` 组合，L21 §21.3）：

```bash
# 2a) 不带灰度头 → 谓词为假 → 没有路由命中 → 404
curl -s --noproxy '*' -i http://127.0.0.1:8080/predicate-lab/hello
# HTTP/1.1 404 Not Found
# X-Demo-WebFilter: hit-85
# Vary: Origin
# Vary: Access-Control-Request-Method
# Vary: Access-Control-Request-Headers
# Content-Type: application/json
# Content-Length: 134
#
# {"timestamp":"2026-10-02T03:53:03.205+00:00","path":"/predicate-lab/hello","status":404,"error":"Not Found","requestId":"4006cb5e-85"}

# 2b) 带上灰度头 → 谓词为真 → 命中 → 200
curl -s --noproxy '*' -i -H 'X-Demo-Env: gray' http://127.0.0.1:8080/predicate-lab/hello
# HTTP/1.1 200 OK
# transfer-encoding: chunked
# X-Demo-WebFilter: hit-86
# Vary: Origin
# Vary: Access-Control-Request-Method
# Vary: Access-Control-Request-Headers
# Content-Type: application/json
# Date: Fri, 02 Oct 2026 03:53:03 GMT
#
# {"port":8081,"method":"GET","uri":"/hello","query":null,"headers":{"user-agent":"curl/8.5.0","accept":"*/*","x-demo-env":"gray","x-demo-trace-id":"b551720da2c945509691e0b7621006ad","x-demo-from-gateway":"true","forwarded":"proto=http;host=\"127.0.0.1:8080\";for=\"127.0.0.1:48564\"","x-forwarded-for":"127.0.0.1","x-forwarded-proto":"http","x-forwarded-prefix":"/predicate-lab","x-forwarded-port":"8080","x-forwarded-host":"127.0.0.1:8080","host":"localhost:8081","content-length":"0"},"greeting":"hello world"}
```

两次请求的**唯一差别**是 `X-Demo-Env`——路由选不选中，就取决于谓词这一个判断。日志里的谓词是"组合形态"（`&&`）：

```
Route matched: demo-predicate-lab
Mapping [Exchange: GET http://127.0.0.1:8080/predicate-lab/hello] to Route{id='demo-predicate-lab', uri=http://localhost:8081, order=0, predicate=(Paths: [/predicate-lab/**], match trailing slash: true && DemoHeaderRoutePredicateFactory$$Lambda$1419/0x0000763718896c10), gatewayFilters=[[[StripPrefix parts = 1], order = 1]], metadata={}}
```

### ③ 谁都不匹配：`GET /nope`

```bash
curl -s --noproxy '*' -i http://127.0.0.1:8080/nope
# HTTP/1.1 404 Not Found
# X-Demo-WebFilter: hit-87
# Vary: Origin
# Vary: Access-Control-Request-Method
# Vary: Access-Control-Request-Headers
# Content-Type: application/json
# Content-Length: 119
#
# {"timestamp":"2026-10-02T03:53:03.417+00:00","path":"/nope","status":404,"error":"Not Found","requestId":"42ba976c-87"}
```

②a 和 ③ 的**表象一样（404）**，但原因分两层：②a 是"有一条路由，谓词把它否了"；③ 是"列表全试完，没有任何一条点头"。最终都由 `switchIfEmpty` 走进"没 handler"的路径（23.5）；404 的 JSON 响应体（含 `requestId`）由 WebFlux 默认错误处理生成（L18 的错误链）。

### ④ 两条都匹配时谁赢？—— order 小的赢（先中即停）

临时加一条 `order=-1` 的影子路由（同样匹配 `/provider/**`，但换一个响应头标记），看谁抢到这个请求：

```bash
# 加影子路由（order=-1 → 排序后排在 demo-provider(order=0) 前面）
curl -s --noproxy '*' -i -X POST http://127.0.0.1:8080/actuator/gateway/routes/tmp-l23-shadow \
  -H 'Content-Type: application/json' -d '{
 "id":"tmp-l23-shadow","uri":"http://127.0.0.1:8081","order":-1,
 "predicates":[{"name":"Path","args":{"_genkey_0":"/provider/**"}}],
 "filters":[{"name":"StripPrefix","args":{"_genkey_0":"1"}},
            {"name":"AddResponseHeader","args":{"_genkey_0":"X-L23-Shadow","_genkey_1":"yes"}}]}'
# HTTP/1.1 201 Created
# Location: /routes/tmp-l23-shadow

curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/refresh
sleep 0.3    # refresh 异步换快照（L22 §22.1）

# 再打同一个请求：看响应头就知道谁赢了
curl -s --noproxy '*' -i http://127.0.0.1:8080/provider/hello | grep -iE '^HTTP|X-L23-Shadow|X-Demo-Gateway-Version'
# HTTP/1.1 200 OK
# X-L23-Shadow: yes                 ← ★ 影子赢了（demo-provider 的过滤器没跑，
#                                      所以 X-Demo-Gateway-Version 消失了）
```

日志里也只有影子的那行（赢家才打）：

```
Route matched: tmp-l23-shadow
Mapping [Exchange: GET http://127.0.0.1:8080/provider/hello] to Route{id='tmp-l23-shadow', uri=http://127.0.0.1:8081, order=-1, predicate=Paths: [/provider/**], match trailing slash: true, gatewayFilters=[[[StripPrefix parts = 1], order = 1], [[AddResponseHeader X-L23-Shadow = 'yes'], order = 2]], metadata={}}
```

清理并验证恢复：

```bash
curl -s --noproxy '*' -o /dev/null -w 'delete -> %{http_code}\n' -X DELETE http://127.0.0.1:8080/actuator/gateway/routes/tmp-l23-shadow
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/refresh
sleep 0.3
curl -s --noproxy '*' -i http://127.0.0.1:8080/provider/hello | grep -iE '^HTTP|X-L23-Shadow|X-Demo-Gateway-Version'
# HTTP/1.1 200 OK
# X-Demo-Gateway-Version: 1.0        ← demo-provider 又赢回来了
```

**四个实验合起来就是"匹配"的全部**：拿一份**排好序**的 Route 列表（order 小的在前），**逐条问它的谓词**，**第一个点头的就定下来**；一个都不点头 → 404。

---

## 23.2 它在链路里的位置（为什么是它）

匹配是 C 组的活。C 组**不是**网关自定义的接口，而是 WebFlux 的插头 —— `HandlerMapping`：

- `DispatcherHandler` 收到请求后，按 order 问一圈 `HandlerMapping`："这个请求归谁？"
- `RoutePredicateHandlerMapping`（`AbstractHandlerMapping` 子类，**默认 order=1**，可配 `spring.cloud.gateway.handler-mapping.order`）就是网关交出的答卷
- 它的 `getHandlerInternal` 返回的"handler"不是某个方法，而是 **D 组的 `FilteringWebHandler`（单例）** —— 具体哪个路由，通过 exchange 属性传递（数据与处理器分离）

（名单细节、`SimpleHandlerAdapter` 怎么调它 → **L18 第 6 站**；构造器四个参数（managementPort/order/CORS/routeLocator）→ **L17 §17.5.1**，本课不重复。）

---

## 23.3 源码全解：`lookupRoute` ★

```java
protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
    return this.routeLocator.getRoutes().filterWhen(route -> {
        // add the current route we are testing
        exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, route.getId());    // ① 记账：正在测谁
        try {
            return route.getPredicate().apply(exchange);                               // ② 跑谓词（异步签名）
        }
        catch (Exception e) {
            logger.error("Error applying predicate for route: " + route.getId(), e);   // ③ 失败安全
        }
        return Mono.just(false);
    })
        .next()                                                                        // ④ ★ 只要第一条
        .map(route -> {
            if (logger.isDebugEnabled()) {
                logger.debug("Route matched: " + route.getId());                       // ⑤ 日志里那行
            }
            validateRoute(route, exchange);                                            // ⑥ 空钩子
            return route;
        });
}
```

四件事：

### ① 记账 `GATEWAY_PREDICATE_ROUTE_ATTR` —— 谓词工厂的"自报家门"

每测一条路由，先把它 id 写进去。**这不是日志字段，谓词工厂自己在读**（main 全仓库核对，消费方只有两处）：

| 消费方 | 怎么用 |
|---|---|
| `WeightRoutePredicateFactory:91` | `String routeId = exchange.getAttribute(GATEWAY_PREDICATE_ROUTE_ATTR);` —— Weight 谓词要拿"当前测的是哪条"去和 `WeightCalculatorWebFilter` 随机选中的 routeId 比对（L24 §24.5） |
| `PathRoutePredicateFactory:110` | 命中后把它转存到 `GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR`（给 metrics 用，见 23.6） |

### ② `route.getPredicate().apply(exchange)` —— 签名是"异步"的，但多数是同步的

`Route.getPredicate()` 返回 `AsyncPredicate<ServerWebExchange>`，`apply` 返回 `Publisher<Boolean>`（L21 §21.3）。但**绝大多数谓词实际是同步的**：

```java
// AsyncPredicate.from(predicate) → DefaultAsyncPredicate
public Publisher<Boolean> apply(T t) {
    return Mono.just(delegate.test(t));      // test() 立刻执行；异常同步抛出 → try/catch 接得住（例外见 ③）
}
```

（`RoutePredicateFactory.applyAsync` 默认 = `toAsyncPredicate(apply(config))` → `ServerWebExchangeUtils:283` → `AsyncPredicate.from(...)`；只有 `ReadBodyRoutePredicateFactory` 之类**真正重写** `applyAsync` 返回异步 Mono。）

### ③ 失败安全的边界：只罩"立即执行"的异常

catch → `logger.error` → `Mono.just(false)` → 继续测下一条。但注意**这个 try/catch 罩不住所有人**：

- **单谓词路由**：`test()` 在 `apply` 里立即执行 → 抛出必被 catch ✅
- **多谓词路由**（`and()` 组合）：`AndAsyncPredicate.apply = Mono.from(left.apply(t)).flatMap(r -> … Mono.from(right.apply(t)))` —— **最左谓词**仍立即执行（受保护）；**从第二个起在 flatMap 回调里执行**，若同步抛出，Reactor 会把它转成 **onError 信号**（不转 false、不记这条日志）冒到匹配链
- **真异步谓词**（ReadBody）：错误以 onError 冒泡，同理

### ④ `.next()`：本课 🔴（原理见 23.4）

---

## 23.4 `filterWhen` 与 `.next()`：为什么是"先中即停"，不是"最优匹配"

- `filterWhen` = "**异步条件的 filter**"：对每个元素跑一个 `Publisher<Boolean>`，true 留下、false 丢掉
- `.next()` 的 Reactor 源码（本机 3.6.5）：

```java
// Flux.next()
public final Mono<T> next() { return Mono.onAssembly(new MonoNext<>(this)); }

// MonoNext.onNext()（收到第一个元素时）
public void onNext(T t) {
    ...
    s.cancel();          // ★ 先取消上游
    actual.onNext(t);    // ★ 再发出这个元素
}
```

**"取第一个"的字面实现就是：先掐掉上游，再发出去。** 对同步谓词（默认情形）：逐条评估，第一条 true 的瞬间上游被取消 → 后面的路由**不再被测试**（短路）。

一个容易吃惊的点（javap 实查本地 `reactor-core-3.6.5.jar`）：`Flux.filterWhen(Function)` 默认并发度 = `Queues.SMALL_BUFFER_SIZE`（默认 **256**，`-Dreactor.bufferSize.small` 可改）。含义：

- **同步谓词**：实际就是顺序短路（上一条算完才轮到下一条）
- **异步谓词**（如 ReadBody）：最多 256 条路由的谓词评估可能**已在途/并发** —— 但 `filterWhen` **保证输出顺序** → **顺序上第一条命中的仍然胜出**，语义不变

**列表的"顺序"从哪来**？`routeLocator.getRoutes()` 来自 `CachingRouteLocator`，它的 `fetch()` 会按 `AnnotationAwareOrderComparator` **排序**（L22 §22.3）——`order` 就是在这里决定"谁先被测"。所以 23.1 实验④里 `order=-1` 的影子路由赢了：它排在前面，第一个点头，后面（`demo-provider`）根本没轮到。

**它不是"选最优"**：没有打分、没有优先级计算，就是"排序 + 第一个 true"。

---

## 23.5 命中之后：`getHandlerInternal` 的交接与"记账本"

```java
protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
    if (managementPortType == DIFFERENT && ...) { return Mono.empty(); }             // 管理端口不抢（L17 已讲）
    exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());      // ① 谁在处理（诊断）
    return Mono.deferContextual(contextView -> {
        exchange.getAttributes().put(GATEWAY_REACTOR_CONTEXT_ATTR, contextView);     // ② 存 Reactor 上下文
        return lookupRoute(exchange)
            .map(r -> {
                exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);       // ③ 擦掉"测试中"状态
                if (logger.isDebugEnabled()) {
                    logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + r);
                }
                exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);                 // ④ ★ 命中结果入账
                return webHandler;                                                   // ⑤ ★ 交出 D 组处理器
            })
            .switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
                exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
                ServerWebExchangeUtils.clearCachedRequestBody(exchange);             // ⑥ 顺手清 body 缓存（L28 伏笔）
                if (logger.isTraceEnabled()) {
                    logger.trace("No RouteDefinition found for [" + getExchangeDesc(exchange) + "]");  // ⑦ 404 路径
                }
            })));
    });
}
```

三个 attribute 的"去向表"（main 全仓库核对）：

| attribute | 写入 | 消费方 |
|---|---|---|
| `GATEWAY_HANDLER_MAPPER_ATTR` | ① | **main 中暂无消费方**（诊断字段） |
| `GATEWAY_REACTOR_CONTEXT_ATTR` | ② | `ObservedRequestHttpHeadersFilter:94`（观测/头传播用 contextView） |
| `GATEWAY_PREDICATE_ROUTE_ATTR` | `lookupRoute` 每测一条覆盖一次 | `WeightRoutePredicateFactory:91`、`PathRoutePredicateFactory:110`（③ 之后被擦掉） |

**404 的完整由来**：没有匹配 → `switchIfEmpty`（擦记账 + 清 body 缓存，没有 handler 返回）→ WebFlux 的 `DispatcherHandler` 发现没有 mapping → 404（响应形态见 23.1 ②a/③ 实测）。

---

## 23.6 Path 谓词的"顺手记账"（匹配 → metrics 的连接线）

`PathRoutePredicateFactory` 命中分支里做了三件"顺手事"（L24 §24.3 会再看它，这里只看交接）：

```java
putUriTemplateVariables(exchange, pathMatchInfo.getUriVariables());                           // ① 路径变量入账（RewritePath 等用）
exchange.getAttributes().put(GATEWAY_PREDICATE_MATCHED_PATH_ATTR, match.getPatternString());  // ② 命中的模式串
String routeId = exchange.getAttributes().get(GATEWAY_PREDICATE_ROUTE_ATTR);                  // ③ 读 23.3 ① 的记账
if (routeId != null) {
    exchange.getAttributes().put(GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR, routeId);
}
```

②③ 的消费方是 `GatewayPathTagsProvider`（Micrometer metrics tags）—— "匹配到的路径模板 + 路由 id"会作为指标 tag。**匹配阶段不只做判断，还在给观测链路备料。**

---

## 23.7 怎么自己验证

- **实验**：23.1 的四组命令（含影子路由）直接可跑；跑完记得 DELETE + refresh + `sleep 0.3`（L22 §22.1 的异步提示）。
- **断点**（对着 23.3 的源码打）：
  - `RoutePredicateHandlerMapping#lookupRoute` 的 `filterWhen` lambda —— 看"正在测谁、谓词返回什么"
  - `.map` / `switchIfEmpty` —— 看**命中交接**与**404 路径**的分叉
  - `WeightCalculatorWebFilter#filter`（L24）—— 顺带看 `WEIGHT_ATTR` 是怎么被预计算的
- **日志**：demo 已开 `org.springframework.cloud.gateway: DEBUG` → `Route matched: xxx`（赢家）+ `Mapping [Exchange: ...] to Route{...}`（完整运行态对象，23.1 已贴）。
- ⚠️ 环境噪声：Nacos 心跳每 30s 自动刷新（L22 §22.1）——只影响"路由表快照的刷新时机"，**不影响单请求匹配**；影子实验里手动 refresh 足够。

---

## 23.8 常见理解偏差澄清（复习先读）

> ① **匹配只跑谓词**（`route.getPredicate()`），**不碰过滤器**；过滤器是命中之后（D 组）的事。两个阶段用的都是 L21 编译好的那一个 Route 对象，但消费的字段不同。
> ② **"先中即停"依赖列表已排好序**（order，来自 `CachingRouteLocator.fetch()` 的排序）——不是"最优匹配"。order 决定的是**所有匹配成功的候选之间**的先后（**不要求谓词相同**；只要两条都匹配，排在前面的赢）；"order 小的赢"有一个前提：**它自己得匹配**，否则被跳过、轮到下一条。order 相同则保持稳定序。
> ③ **404 = 没有任何 Route 命中**（`switchIfEmpty`），不是某条路由内部报错；而"谓词为假"和"没有路由"在响应上长得一样（23.1 ②a/③）。
> ④ **匹配期间在"记账"**（`GATEWAY_PREDICATE_ROUTE_ATTR` 等），谓词工厂（Weight/Path）与 metrics 消费者在用；命中/未命中后都会被擦掉。

---

## 23.9 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| managementPort 的具体判断逻辑 | ❌ L17 §17.5.1 已讲，不重复 |
| `filterWhen` 并发度 256 的边角 | ⚪ 知道"顺序保证 + 同步即短路"即可（数字为 3.6.5 实测，不必背） |
| **记账字段（3 个 attr）与消费方** | ✅ 要会（"匹配后路由信息放哪/给谁用"是高频面试题） |
| **`.next()` 短路语义 + 命中交接** | ✅ **本课唯一 🔴** |
| `validateRoute` | ❌ 空钩子，一句翻篇 |

---

## 23.10 Self-Test

1. 23.1 实验④里，为什么 `order=-1` 的影子路由能"抢走" `/provider/hello`？响应中少了哪个头、为什么能证明 `demo-provider` 没被使用？
2. `lookupRoute` 里哪一行决定"只取第一条匹配"？Reactor 层面它是怎么实现的（说出 `MonoNext` 的两步）？
3. 命中后 Route 放在哪里、handler 返回什么？
4. 为什么 Weight/Path 谓词工厂能知道"现在测的是哪条路由"？
5. "谓词为假"与"没有任何路由"的 404，在代码路径上分别是哪一段走向 `switchIfEmpty`？
6. `filterWhen` 明明是"异步过滤"，为什么平时看起来是"顺序短路"？（提示：同步谓词 + 输出顺序保证）
7. 列表的顺序是谁排的、用什么排？`order` 相同会怎样？

---

## 23.11 Open Questions

- [ ] `GATEWAY_HANDLER_MAPPER_ATTR` 在 main 中无消费方 —— 纯诊断字段，还是给自定义 `ErrorWebExceptionHandler` 的约定？未深究。
- [ ] 异步谓词（`ReadBodyRoutePredicateFactory`）在 `filterWhen` 并发下的错误传播 —— 未实测（L28 可能触及）。
- [ ] `.next()` 取消上游时，已在途的异步谓词订阅是否全部被取消 —— `MonoNext` 源码显示会 `s.cancel()`，但并发度>1 时内层订阅的取消传播未实测。
