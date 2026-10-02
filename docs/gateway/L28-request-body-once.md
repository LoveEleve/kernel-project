# Lesson 28: Why the Request Body Can Be Read Only Once

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 28
> 前置：**L11**（背压：`DataBuffer` 是流）· **L23 §23.3⑥**（没命中时 `clearCachedRequestBody` 的伏笔）· **L27**（Netty 流式发 body；Retry/CircuitBreaker 的 `enableBodyCaching` 钩子）
> 口径：🔴 `AdaptCachedBodyGlobalFilter` 缓存的原因（**流只能消费一次**）

**本课回答一个问题**：请求体明明就在连接里，**为什么"读第二次"做不到**？网关怎么解决？
—— body 是**一次性流**：消费即释放；对同一连接的入站数据，**第二个订阅者要么被拒、要么拿到空**（源码实证见 28.2）。解法：**谁要读第二次谁提前"申报"，最前端的过滤器把 body 抄进内存**，后续从内存重放。

---

## 28.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **根本原因** | 请求 body 是**一次性流**（`Flux<DataBuffer>`，消费即释放）——读第二次技术上做不到 |
| **②** | **解法** | 在**最早时刻**把 body join 成一个 `DataBuffer` 存进 exchange 属性（`CACHED_REQUEST_BODY_ATTR`），后续从内存重放 |
| **③** | **成对的两个过滤器** | `AdaptCachedBodyGlobalFilter`（order=MIN+1000，**最前端**）负责缓存；`RemoveCachedBodyFilter`（order=MIN，**最外层**）在 `doFinally` 清理（释放池化内存） |
| **④** | **谁触发缓存** | `enableBodyCaching(routeId)` → `EnableBodyCachingEvent` → `routesToCache`；调用者：**Retry**（必调）、**CircuitBreaker**（有 fallback 时）、**ReadBody 谓词**（支线） |

---

## 28.1 例子先行：一个 POST 请求的"两次读"事故

两个真实场景（都来自源码里的调用方）：

```
场景 A：路由带了 Retry（L27 §27.8）
  POST /provider/echo body=xxx
  第 1 次转发：Netty 把 body 流式发给下游（读了）
  下游 500 → Retry 重试 → 第 2 次转发又要读 body ❌ 流已经消费掉了

场景 B：路由谓词里写了 ReadBody（先读 body 判断）
  匹配阶段：ReadBody 谓词把 body 读出来做判断（读了）
  转发阶段：Netty 又要读 body 发给下游 ❌ 读不到了
```

`ReadBodyRoutePredicateFactory` 源码注释的原话：

> "We can only read the body from the request once, once that happens if we try to read the body again an exception will be thrown."（**ReadBodyRoutePredicateFactory.java:70-74**）

于是网关的方案：**谁需要"第二次读"，谁提前声明；网关在最前端把 body 缓存下来。**

---

## 28.2 为什么"只能读一次"是物理事实（不是 bug）

- 请求 body 表示成 `Flux<DataBuffer>`：数据**从 Netty 连接里到一块读一块**（L11 背压：`request(n)` 要多少给多少）
- `DataBuffer` 是**池化内存**：被消费后 `release()` 归池；你不 release，它就泄漏；你 release 第二次读就没了
- 对同一条连接**订阅两次 body = 两个消费者**。Reactor Netty 1.1.18 的实际行为（`reactor.netty.channel.FluxReceive#startReceiver` 源码）：
  - 数据**还没消费完**时第二个订阅者 → `IllegalStateException: Rejecting additional inbound receiver. State=…`
  - 数据**已消费完**时第二个订阅者 → **空完成**（不是异常，但拿不到任何数据）
  - 总之：**第二次要么报错、要么空** —— 这就是"必须缓存"的直接原因

所以"缓存"不是锦上添花，而是**流式模型下做重放/预读的唯一正确姿势**。

---

## 28.3 缓存机制全链路（谁、在什么时候、做什么）

### ① 触发：`enableBodyCaching(routeId)`（各工厂主动申报）

```java
// AbstractGatewayFilterFactory:47-52
protected void enableBodyCaching(String routeId) {
    if (routeId != null && getPublisher() != null) {
        getPublisher().publishEvent(new EnableBodyCachingEvent(this, routeId));   // 广播"这条路由要缓存 body"
    }
}
```

调用方（全仓库核对过的"三处半"）：
- **Retry**：`apply(routeId, repeat, retry)` 开头无条件调（要重放，必然需要）
- **CircuitBreaker**：`if (config.getFallbackUri() != null)` 才调（fallback 也可能重读 body）
- **ReadBody 谓词 / `CacheRequestBodyGatewayFilterFactory`**：不走这个事件，走自己的支线（28.4）

### ② 登记：`AdaptCachedBodyGlobalFilter` 记下要缓存的路由

```java
private ConcurrentMap<String, Boolean> routesToCache = new ConcurrentHashMap<>();

@Override
public void onApplicationEvent(EnableBodyCachingEvent event) {
    this.routesToCache.putIfAbsent(event.getRouteId(), true);      // 登记（不是马上缓存！）
}
```

### ③ 执行（每请求）：`AdaptCachedBodyGlobalFilter.filter` —— 链上最早段

```java
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    // 分支一：谓词阶段留下的"装饰请求"（见 28.4）
    ServerHttpRequest cachedRequest = exchange.getAttributeOrDefault(CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR, null);
    if (cachedRequest != null) {
        exchange.getAttributes().remove(CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR);
        return chain.filter(exchange.mutate().request(cachedRequest).build());
    }
    // 分支二：需要缓存吗？（已有 body / 路由没申报 → 直接过）
    DataBuffer body = exchange.getAttributeOrDefault(CACHED_REQUEST_BODY_ATTR, null);
    Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    if (body != null || !this.routesToCache.containsKey(route.getId())) {
        return chain.filter(exchange);
    }
    // 分支三：缓存！→ 后续过滤器拿到"body 可重放的装饰请求"
    return ServerWebExchangeUtils.cacheRequestBody(exchange, (serverHttpRequest) -> {
        if (serverHttpRequest == exchange.getRequest()) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange.mutate().request(serverHttpRequest).build());
    });
}

@Override
public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1000; }     // 链上最前端（仅次于清理过滤器）
```

`cacheRequestBody` 内部（`ServerWebExchangeUtils:364-387`，私有实现）：

```java
return DataBufferUtils.join(exchange.getRequest().getBody())            // ★ join：流 → 单块内存
    .defaultIfEmpty(factory.wrap(EMPTY_BYTES))
    .map(dataBuffer -> decorate(exchange, dataBuffer, cacheDecoratedRequest))
    .switchIfEmpty(Mono.just(exchange.getRequest()))
    .flatMap(function);
// decorate(): 
//   ① exchange.attributes.put(CACHED_REQUEST_BODY_ATTR, dataBuffer)      ← 存起来
//   ② 返回 ServerHttpRequestDecorator：getBody() 从这块内存 retainedSlice 重放（可读多次）
//   ③ cacheDecoratedRequest=true 时，还把 decorator 顺便存进 CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR
```

### ④ 清理：`RemoveCachedBodyFilter`（order = MIN，最外层）

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return chain.filter(exchange).doFinally(s -> ServerWebExchangeUtils.clearCachedRequestBody(exchange));
}
@Override
public int getOrder() { return HIGHEST_PRECEDENCE; }         // 排序最前 = 最外层 → doFinally 最后执行
```

```java
// clearCachedRequestBody（ServerWebExchangeUtils:389-404）
Object attribute = exchange.getAttributes().remove(CACHED_REQUEST_BODY_ATTR);
if (attribute instanceof PooledDataBuffer) {
    // release 到零（池化内存归还），注释：release() counts down until zero, will never be infinite loop
}
```

**为什么必须清**：缓存的是**池化 DataBuffer**——不清 = 泄漏（尤其高 QPS + 大 body）。`doFinally` 保证**成功/失败/取消**三种结局都会清。L23 §23.3⑥ 的"没命中路由时顺手清"也是同一个动机，这里闭合成环。

---

## 28.4 支线：谓词阶段怎么缓存？（"装饰请求"分支的由来）

**约束**：Spring WebFlux 的谓词阶段**不能 mutate exchange**（此时不是它的地盘）。但 ReadBody 谓词要想"读完 body 后，后面转发还能读"，必须把缓存挂到 exchange 上——挂什么？

```java
// ReadBodyRoutePredicateFactory.applyAsync 内（简化）
Object cachedBody = exchange.getAttribute(CACHE_REQUEST_BODY_OBJECT_KEY);
if (cachedBody != null) {                    // 第二次跑谓词：直接用缓存对象（多路由复用）
    return Mono.just(config.predicate.test(cachedBody));
}
return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange,       // ★ cacheDecoratedRequest = true
    (serverHttpRequest) -> ServerRequest.create(exchange.mutate().request(serverHttpRequest).build(), messageReaders)
        .bodyToMono(inClass)
        .doOnNext(objectValue -> exchange.getAttributes().put(CACHE_REQUEST_BODY_OBJECT_KEY, objectValue))
        .map(objectValue -> config.getPredicate().test(objectValue)));
```

- `cacheRequestBodyAndRequest` = `cacheRequestBody(exchange, true, fn)`：**除了缓存字节，还把"装饰过的请求"存进** `CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR`
- 之后 `AdaptCachedBodyGlobalFilter` 一进链就检查这个属性（28.3 分支一）→ **把装饰请求换上** → 整条链后续读 body 都改成"从内存重放"
- 两种缓存的区别：

| 属性 | 存什么 | 谁在写 | 谁在读 |
|---|---|---|---|
| `CACHED_REQUEST_BODY_ATTR` | 原始 **DataBuffer**（字节） | `cacheRequestBody` 的 `decorate` | 装饰请求的 `getBody()` |
| `CACHE_REQUEST_BODY_OBJECT_KEY` | **反序列化后的对象**（如 JSON→Object） | ReadBody 谓词 | 谓词自身（多路由复用判断结果） |
| `CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR` | 装饰请求本身 | `cacheDecoratedRequest=true` 时 | `AdaptCachedBodyGlobalFilter`（分支一） |

`CacheRequestBodyGatewayFilterFactory`（路由过滤器）是另一个"入口"：`ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange, ...)`（`CacheRequestBodyGatewayFilterFactory:78`）——同样是"申报式缓存"，可写进 yml。

---

## 28.5 怎么自己验证

```bash
# 场景 A：给路由加 Retry + POST 带 body
# 断点/日志点：
#   AdaptCachedBodyGlobalFilter#filter            → 看 routesToCache 命中、body join 那一刻
#   ServerWebExchangeUtils#clearCachedRequestBody → 看释放
# 实验对照：临时把 Retry 去掉 → Adapt 的"分支二"直接放行（CACHED_REQUEST_BODY_ATTR 为空）
```

（`RemoveCachedBodyFilter` 的 order = MIN 保证无论成败都清；可用 `doFinally` 断点确认取消场景。）

> 📌 **为什么这里没做现场实验**：要现场触发"二次读"，需要一个"**先收下 body、再失败**"的下游（才会重放/重读）；demo 的 provider 只有 GET 端点（无 body 消费），且运行中的网关没开 TRACE（缓存/拒绝日志都在 TRACE 级）。所以本课以**源码实证 + 断点验证**为准；上面的断点路径随时可在本地环境补做。

---

## 28.6 掌握边界（值不值得深挖）

> 本课定位：**机制型（轻）**——核心只有一条"body 是一次性流、必须提前缓存"；实现细节（三个属性 / 两个过滤器）了解即可。

| 内容 | 口径 |
|---|---|
| `DataBufferUtils.join` 的内存/背压细节 | ❌ L11 已讲流与背压，这里不重开 |
| `CacheRequestBodyGatewayFilterFactory` 完整用法 | ⚪ 知道它是"申报式缓存"的过滤器入口即可 |
| **"只能读一次"的物理原因** | ✅ **本课唯一 🔴** |
| **两个过滤器的成对设计（最前端缓存 / 最外层清理）** | ✅ 要会（时间点 + 清理动机） |
| 谓词阶段的"装饰请求"支线 | ✅ 要会（能解释 `CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR` 为什么存在） |
| Retry/CircuitBreaker 与它的关系 | ⚪ 记住"重放/fallback ⇒ 申报缓存"即可 |

---

## 28.7 Self-Test

1. body 为什么只能读一次？（答出"流 + 池化内存"两层）
2. 缓存在哪个属性里？谁在什么时机放入、谁在什么时候清掉？不清会怎样？
3. `AdaptCachedBodyGlobalFilter` 为什么是 `HIGHEST_PRECEDENCE + 1000`、`RemoveCachedBodyFilter` 为什么是 `HIGHEST_PRECEDENCE`？（答"最早缓存 / 最外层清"）
4. Retry 和 CircuitBreaker 各自在什么条件下申报 body 缓存？
5. `CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR` 分支解决的是什么问题？（谓词不能 mutate exchange）
6. `CACHE_REQUEST_BODY_OBJECT_KEY` 和 `CACHED_REQUEST_BODY_ATTR` 各存什么、各给谁用？

---

## 28.8 Open Questions

- [x] "二次读 body"的行为 —— **源码已核**（reactor-netty 1.1.18 `FluxReceive#startReceiver`）：未消费完 → `IllegalStateException: Rejecting additional inbound receiver. State=…`；已消费完 → 空完成。**两条路都拿不到原数据**（实测场景难构造，未实跑）。
- [ ] `RemoveCachedBodyFilter.doFinally` 与 Netty 连接 `dispose` 的相对时序 —— 未细究（与 L27 的 cleanup 相关）。
- [ ] `CacheRequestBodyGatewayFilterFactory` 与谓词支线并存时谁先生效 —— 未实测。
