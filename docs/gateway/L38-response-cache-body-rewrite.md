# Lesson 38: Response Cache & Body Rewrite — `LocalResponseCache` / `ModifyRequestBody` / `ModifyResponseBody`

> 课程索引：`../gateway-学习清单.md` → **Stage 7**（内置能力补全）· Lesson 38
> 前置：**L06**（超时与路由 metadata）· **L28**（body 只能读一次——body 改写的前置）· **L36/L37**（治理过滤器家族：开关/双 Config/事件通道的意识）
> 口径：🔴 **什么响应能缓存**（`Cache-Control` 语义、本地缓存 vs 分布式缓存的一致性）
> 架构问题：网关做缓存 vs CDN vs 服务内缓存，各自适用什么场景？
> 版本口径：demo 跑的是 **SCG 4.1.5 + Caffeine**；本课源码与实测均以此为基准。官方文档（本地新版源码树）与 4.1.5 实现有出入处已标注。

**一句话（先说人话）**：网关可以在**自己的内存里**缓存下游的 GET 响应——命中时直接返回，下游一次都不用打；同时它还能**读写请求/响应 body**（路由级做加工：加字段、脱敏、换内容类型）。

**本课路线图**（建议按序读）：

1. **38.1 先看现象**——缓存命中 / TTL 过期 / 五种"不缓存"条件 / no-cache 策略 / 大小上限 / body 改写（9 组实测）；
2. **38.2 什么能缓存**——请求侧、响应侧、状态码、Vary 的完整规则表；
3. **38.3 源码**——`LocalResponseCache` 家族：开关/装配 / 全局 vs 路由 / Key 生成 / Size 权重 / no-cache 策略；
4. **38.4 源码**——body 改写：为什么 `ModifyRequestBody` **只能 Java DSL**；
5. **38.5 生产与架构**——本地缓存的边界、网关 vs CDN vs 服务内、安全与选型；
6. **38.6–38.9**——坑表 / 面试问答 / Self-Test / Open Questions。

---

## 38.1 它干了什么：两条 lab + 九组实测

实验前置（已落盘）：

- `demo-gateway/pom.xml`：加 `spring-boot-starter-cache` + `caffeine`（官方文档明确要求这两个）；
- `demo-provider/.../CacheLabController.java`：`GET /cache/stamp`（每次调用 `seq+1`，用来识别缓存命中；`?cc=` 控制响应 Cache-Control、`?vary=` 控制 Vary、`?pad=N` 撑大 body）与 `POST /body/echo`（回显 JSON body）；
- `application.yml`：开关 + 三条 lab 路由（缓存两条在 yml，改写一条在 Java DSL）；
- `demo-gateway/.../filter/RewriteRouteConfig.java`：Java DSL 定义 `rewrite-lab`（原因见 38.4）。

关键开关（**默认是关的，不配不生效**）：

```yaml
spring:
  cloud:
    gateway:
      filter:
        local-response-cache:
          enabled: true          # ★ 必须显式开；开启后才会注册 LocalResponseCache 过滤器
      global-filter:
        local-response-cache:
          enabled: false         # 关掉"全局缓存"（默认开启，会缓存所有路由的响应）
```

缓存路由（TTL=10s 便于观察过期；第二条加 200B 大小上限）：

```yaml
        - id: cache-lab
          uri: http://localhost:8081
          predicates:
            - Path=/cache-lab/**
          filters:
            - StripPrefix=1
            - name: LocalResponseCache
              args:
                timeToLive: 10s
        - id: cache-small-lab
          uri: http://localhost:8081
          predicates:
            - Path=/cache-small-lab/**
          filters:
            - StripPrefix=1
            - name: LocalResponseCache
              args:
                timeToLive: 10s
                size: 200B
```

### 实验 1：缓存命中 + `max-age` 自动重算

```bash
curl -s "http://127.0.0.1:8080/cache-lab/cache/stamp"          # ① 首次
curl -s -i "http://127.0.0.1:8080/cache-lab/cache/stamp" | head -14   # ② 第二次
```

实测：

```
① {"seq":1,...}                        ← 打到 provider，缓存写入
② HTTP/1.1 200 OK
   Cache-Control: max-age=9           ← ★ 网关加上的，且随剩余 TTL 递减（TTL=10s，已过约 1s）
   {"seq":1,...}                      ← ★ 同一条（命中缓存，provider 零命中）
```

两个要点：

- 下游响应**压根没有** `Cache-Control` 头，但网关照样缓存了（见 38.2：这是"负面清单"模式）；
- 命中时 `max-age` 不是原样返回，而是**按剩余时间重算**（`TTL - 已缓存秒数`）——源码 `SetMaxAgeHeaderAfterCacheExchangeMutator`。

### 实验 2：TTL 过期

```bash
sleep 11; curl -s ".../cache-lab/cache/stamp"
# {"seq":2,...}   ← 过期后打回 provider，新值入缓存
```

### 实验 3：三种"不缓存"（响应侧；本组合并 3 个用例：no-store / private / Vary:*）

```bash
# no-store（响应头）：
curl -s ".../cache-lab/cache/stamp?cc=no-store"   # {"seq":3,...}
curl -s ".../cache-lab/cache/stamp?cc=no-store"   # {"seq":4,...}  ← 没缓存，每次都打 provider

# private：
# {"seq":5} → {"seq":6}  同上

# Vary: *
# {"seq":7} → {"seq":8}  同上
```

### 实验 4：`no-cache` 请求头 + 默认 SKIP 策略（本课最绕的一组）

```bash
U=".../cache-lab/cache/stamp?k=e6"
① 建立缓存: {"seq":9}        ← 写缓存
② 命中:     {"seq":9}        ← 读缓存
③ -H 'Cache-Control: no-cache': {"seq":10}   ← 回源拿新的（缓存读取被绕过）
④ 再普通请求: {"seq":9}      ← ★ 还是旧值！缓存"没有被更新"
```

结论：`no-cache` 的语义是"**客户端要新鲜值**"（回源），但默认策略 `SKIP_UPDATE_CACHE_ENTRY` 表示**只让这个客户端拿到新的，不更新缓存**——其他客户端继续吃旧值，直到 TTL 到期。
想改成"回源的同时顺手更新缓存"，配 `spring.cloud.gateway.filter.local-response-cache.request.no-cache-strategy: UPDATE_CACHE_ENTRY`。

### 实验 5：大小上限 `size: 200B`

```bash
S=".../cache-small-lab/cache/stamp"
① 小 body 第一次: {"seq":11}
② 小 body 第二次: {"seq":11}       ← 命中（body < 200B）
③ ?pad=500 第一次: {"seq":12,"pad":"xxx..."}   ← body > 200B
④ ?pad=500 第二次: {"seq":13,...}              ← 没被缓存（权重超上限，不入缓存）
```

### 实验 6：缓存 key 默认包含 `Authorization`

```bash
A=".../cache-lab/cache/stamp?who=e8"
① userA 第一次: {"seq":14}
② userA 第二次: {"seq":14}    ← 命中
③ userB 第一次: {"seq":15}    ← key 不同（Authorization 变了）→ 打 provider
④ userA 再来:   {"seq":14}    ← 又是 userA 的那条
```

也就是说：**默认缓存是"按凭证隔离"的**（key 生成器把 `Authorization` 头和 Cookies 都算进去，源码见 38.3）——个性化响应默认不会被串号，但缓存条目也会被用户维度放大。

### 实验 7：请求/响应 body 改写（Java DSL 路由）

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"name":"x"}' "http://127.0.0.1:8080/rewrite-lab/body/echo"
# {"port":8081,"contentType":"application/json",
#  "received":{"name":"x","addedBy":"gateway-request-rewrite"},   ← 请求体已被改写（下游看到的）
#  "rewrittenBy":"gateway-response-rewrite"}                     ← 响应体也被改写（客户端看到的）
```

---

## 38.2 什么能缓存：一张规则表（本课 🔴）

四条"正面条件"（必须全满足）：

| 侧 | 条件 | 源码位置 |
|---|---|---|
| 请求 | 方法 = **GET** | `ResponseCacheManager.isRequestCacheable` |
| 请求 | **没有请求体**（`Content-Length <= 0`） | 同上 |
| 响应 | 状态码 ∈ **200 / 206 / 301** | `isResponseCacheable` + `statusesToCache` |
| 响应 | **`Vary` 不含 `*`** | `isVaryWildcard` |

两条"负面清单"（**出现就拒绝，不出现就允许**——注意方向）：

| 位置 | 出现这些值 → 不缓存 |
|---|---|
| 请求 `Cache-Control` | `private` / `no-store` |
| 响应 `Cache-Control` | `private` / `no-store` |

**最容易误判的一点**：它不要求响应有 `Cache-Control: max-age=...` 这类"正面声明"——**没有 Cache-Control 的 200 GET 响应，默认就会被缓存**（实验 1 实测）。它是"负面清单"哲学：除非你明确说 `no-store`/`private`，否则就缓存。所以**不该缓存的接口一定要显式声明**。

`no-cache`（请求头）是另一条线：它不改变"能不能缓存"，而是"**这次要不要绕过缓存去回源**"；回源后要不要更新缓存由 `request.no-cache-strategy` 决定（默认不更新）。

`max-age` 由网关**重算**：写入时 = TTL；命中时 = `TTL - 已缓存秒数`；剩余为 0（快过期）时会追加 `no-cache,must-revalidate`（源码 `SetCacheDirectivesByMaxAgeAfterCacheExchangeMutator`）。

---

## 38.3 源码：`LocalResponseCache` 家族

### ① 开关与装配（为什么"默认是关的"）

```java
// LocalResponseCacheGatewayFilterFactory（4.1.5，类上的条件）
@ConditionalOnProperty(value = "spring.cloud.gateway.filter.local-response-cache.enabled", havingValue = "true")
public class LocalResponseCacheGatewayFilterFactory extends AbstractGatewayFilterFactory<...> { ... }
```

```java
// LocalResponseCacheAutoConfiguration
@ConditionalOnClass({ Weigher.class, Caffeine.class, CaffeineCacheManager.class })   // ★ 需要 Caffeine
@ConditionalOnEnabledFilter(LocalResponseCacheGatewayFilterFactory.class)
public class LocalResponseCacheAutoConfiguration {
    @Bean   // 全局缓存过滤器：local-response-cache.enabled=true 且 global-filter...!=false 时创建
    @Conditional(LocalResponseCacheAutoConfiguration.OnGlobalLocalResponseCacheCondition.class)
    public GlobalLocalResponseCacheGatewayFilter globalLocalResponseCacheGatewayFilter(...) { ... }

    @Bean(name = "gatewayCacheManager")  // 全局缓存管理器
    public CacheManager gatewayCacheManager(LocalResponseCacheProperties cacheProperties) { ... }

    @Bean   // 路由级过滤器工厂
    public LocalResponseCacheGatewayFilterFactory localResponseCacheGatewayFilterFactory(...) { ... }
}
```

三个可操作的含义：

1. **不配 `enabled=true` → 过滤器工厂 bean 不存在**，路由里写 `LocalResponseCache` 会直接报 "Unable to find GatewayFilterFactory"；
2. **开启后默认附带一个"全局缓存过滤器"**（`OnGlobalLocalResponseCachePropertyEnabled`，`matchIfMissing=true`）：**没挂路由过滤器的路由也会被缓存**——本 lab 用 `global-filter.local-response-cache.enabled: false` 关掉它，只让显式挂载的路由被缓存；
3. 缓存名：路由级 = `<routeId>-cache`（每条路由一个 Caffeine cache）；全局 = `response-cache`。

### ② 过滤器流程：读缓存 → 命中直接回 / 未命中包一层装饰器

```java
// ResponseCacheGatewayFilter（核心）
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    if (responseCacheManager.isRequestCacheable(exchange.getRequest())) {
        exchange.getAttributes().put(LOCAL_RESPONSE_CACHE_FILTER_APPLIED, true);
        return filterWithCache(exchange, chain);           // 走缓存逻辑
    }
    return chain.filter(exchange);                         // 请求不满足条件 → 直接放行
}

private Mono<Void> filterWithCache(ServerWebExchange exchange, GatewayFilterChain chain) {
    final String metadataKey = responseCacheManager.resolveMetadataKey(exchange);
    Optional<CachedResponse> cached = getCachedResponse(exchange, metadataKey);   // 读缓存
    if (cached.isPresent()) {
        return responseCacheManager.processFromCache(exchange, metadataKey, cached.get());  // ★ 命中：直接写回
    }
    // 未命中：把 response 换成装饰器，下游响应边写边缓存
    return chain.filter(exchange.mutate().response(new CachingResponseDecorator(metadataKey, exchange)).build());
}
```

- 命中路径：`processFromCache` 把缓存的**状态码/头/body** 写回，并跑一组后处理器（`SetResponseHeaders...` / `SetStatusCode...` / `SetMaxAgeHeader...` / `SetCacheDirectivesByMaxAge...`）；
- 未命中路径：`CachingResponseDecorator.writeWith` 里边读 body 边攒进 `CachedResponse`，`doOnComplete` 时落缓存（并写元数据 key）；
- 顺序说明：数值越小越靠外——路由级缓存过滤器 `WRITE_RESPONSE_FILTER_ORDER - 3`（外）、全局级 `-2`、
  `ModifyResponseBody` `-1`（内）。两者同挂一条路由时（未命中）：响应先经内层**改写**、再被外层**缓存**
  ——**缓存里存的是改写后的 body**；命中时缓存直接短路，改写不再执行。

### ③ 缓存 key：默认就带了 URI + Authorization + Cookies + Vary

```java
// CacheKeyGenerator（4.1.5）
static final List<KeyValueGenerator> DEFAULT_KEY_VALUE_GENERATORS = List.of(
        new UriKeyValueGenerator(),                                  // 完整 URI（含 query！）
        new HeaderKeyValueGenerator(HttpHeaders.AUTHORIZATION, ";"), // Authorization
        new CookiesKeyValueGenerator(";"));                          // Cookies
// + 响应 Vary 指定的头（排序后追加）→ 拼成原始 key → MD5 → Base64
```

三个直接结论：

- **query 是 key 的一部分**：`?a=1` 和 `?a=2` 是两条缓存（实验里我们用不同 query 做隔离实验就是靠这点）;
- **默认按凭证/会话隔离**（实验 6）——个性化响应不会互相串；但命中率也被用户数摊薄；
- 响应 `Vary: *` → 直接放弃缓存；`Vary: X-Foo` → 把 `X-Foo` 的值加入 key。

### ④ TTL 与 Size（Caffeine）

```java
// LocalResponseCacheUtils.createCaffeine
caffeine.expireAfterWrite(ttlSeconds);                                  // TTL 到期即失效
if (cacheProperties.getSize() != null) {
    caffeine.maximumWeight(size.toBytes()).weigher(new ResponseCacheSizeWeigher());
}
```

- TTL 默认 **5 分钟**（`LocalResponseCacheProperties.DEFAULT_CACHE_TTL_MINUTES`），路由参数 `timeToLive` 可覆盖；
- `size` 是可选的**总权重上限**；`ResponseCacheSizeWeigher` 的权重 = 响应 `Content-Length`（拿不到就算 body 字节数）；**权重超过上限的条目不会被收进来**（实验 5）；
- 过期方式只有 `expireAfterWrite`：**没有主动失效接口**——TTL 是唯一的过期机制（这点在 38.5 会变成架构问题）。

### ⑤ `no-cache` 策略

`RequestOptions.noCacheStrategy` 两个值：`SKIP_UPDATE_CACHE_ENTRY`（默认，实验 4）与 `UPDATE_CACHE_ENTRY`（回源后顺手更新缓存）。

---

## 38.4 源码：body 改写（`ModifyRequestBody` / `ModifyResponseBody`）

### ① 为什么 `ModifyRequestBody` 只能 Java DSL

它的 `Config.inClass/outClass` 是 **`ParameterizedTypeReference`**（为了支持 `Map<String, Foo>` 这种泛型描述）：

```java
public static class Config {
    private ParameterizedTypeReference inClass;
    private ParameterizedTypeReference outClass;
    private String contentType;
    private RewriteFunction rewriteFunction;
}
```

yml 的字符串**没法绑定**到 `ParameterizedTypeReference`——我们实测直接启动失败：

```
Failed to bind properties under 'in-class' to org.springframework.core.ParameterizedTypeReference<?>:
No converter found capable of converting from type [java.lang.String] to type [ParameterizedTypeReference<?>]
```

官方文档因此写明：**"This filter can be configured only by using the Java DSL."**
所以本 lab 的改写路由写在 `RewriteRouteConfig`（Java DSL）：

```java
@Bean
@SuppressWarnings("unchecked")
public RouteLocator rewriteLabRoute(RouteLocatorBuilder builder) {
    return builder.routes()
        .route("rewrite-lab", r -> r.path("/rewrite-lab/**")
            .filters(f -> f.stripPrefix(1)
                .modifyRequestBody(Map.class, Map.class, (exchange, body) -> {   // 请求体改写
                    Map<String, Object> out = body == null ? new LinkedHashMap<>()
                            : new LinkedHashMap<>((Map<String, Object>) body);
                    out.put("addedBy", "gateway-request-rewrite");
                    return Mono.just(out);
                })
                .modifyResponseBody(Map.class, Map.class, (exchange, body) -> {  // 响应体改写
                    Map<String, Object> out = body == null ? new LinkedHashMap<>()
                            : new LinkedHashMap<>((Map<String, Object>) body);
                    out.put("rewrittenBy", "gateway-response-rewrite");
                    return Mono.just(out);
                }))
            .uri("http://localhost:8081"))
        .build();
}
```

### ② 请求体：读全量 → 改写 → 缓存重放给下游

```java
// ModifyRequestBodyGatewayFilterFactory.apply（节选）
Mono<?> modifiedBody = serverRequest.bodyToMono(inClass)
        .flatMap(originalBody -> config.getRewriteFunction().apply(exchange, originalBody))
        .switchIfEmpty(Mono.defer(() -> (Mono) config.getRewriteFunction().apply(exchange, null)));

BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, config.getOutClass());
CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);
return bodyInserter.insert(outputMessage, new BodyInserterContext())
        .then(Mono.defer(() -> {
            ServerHttpRequest decorator = decorate(exchange, headers, outputMessage);  // getBody() 读缓存
            return chain.filter(exchange.mutate().request(decorator).build());
        }))
```

- 原 body 被**完整读进来**（`bodyToMono`）→ 应用改写函数 → 写进 `CachedBodyOutputMessage` → 用装饰器让下游从缓存里读；
- 空 body 也会调用改写函数（参数为 `null`）——所以函数里要自己处理 `null`；
- **代价：流式没了**。大 body/流式场景（上传、SSE）会在这里被全量缓冲——和 L28"body 只能读一次"是一对：要么不碰 body，要么接受缓冲。

### ③ 响应体：装饰 response，解码/改写/编码、重算 content-length

```java
// ModifyResponseBodyGatewayFilterFactory.ModifiedServerHttpResponse.writeWith（节选）
Mono modifiedBody = extractBody(exchange, clientResponse, inClass)      // 处理 gzip 等 Content-Encoding
        .flatMap(originalBody -> config.getRewriteFunction().apply(exchange, originalBody))
        .switchIfEmpty(Mono.defer(() -> (Mono) config.getRewriteFunction().apply(exchange, null)));
BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, outClass);
...
messageBody = messageBody.doOnNext(data -> headers.setContentLength(data.readableByteCount()));  // 重算长度
if (StringUtils.hasText(config.newContentType)) {
    headers.set(HttpHeaders.CONTENT_TYPE, config.newContentType);      // 可换 Content-Type
}
```

- 过滤器顺序 `WRITE_RESPONSE_FILTER_ORDER - 1`（比缓存过滤器更靠内；见 38.3② 的顺序说明——缓存存的是改写后的结果，命中时改写不再执行）；
- 支持 `Content-Encoding` 的解码/编码（`GzipMessageBodyResolver` 等）；
- 同样**全量缓冲**：改写意味着内容长度可能变化，必须重算 `Content-Length`。

---

## 38.5 生产与架构：本地缓存的边界

### ① 本地缓存 = 每实例一份，天生不一致

`LocalResponseCache` 用的是**网关进程内的 Caffeine**：

- 3 个网关实例 = 3 份独立缓存，命中与否取决于负载均衡把请求发给谁；
- **没有跨实例失效**：实例 A 缓存了旧值，实例 B 没有——同一用户可能一会儿新一会儿旧；
- 唯一的过期机制是 TTL（`expireAfterWrite`）——**没有"下游数据变更 → 主动清缓存"的通道**。

因此它适合：**读多写少、能容忍 TTL 级不一致、且响应可由缓存头明确表达缓存语义**的接口（配置、字典、商品详情、静态聚合）。
不适合：强一致/交易类数据、个性化太强的内容（key 爆炸）、写后要立刻可见的场景。

### ② 网关 vs CDN vs 服务内缓存

| | 网关本地缓存（本课） | CDN / 边缘缓存 | 服务内缓存（Caffeine/Redis） |
|---|---|---|---|
| 位置 | 网关实例内存 | 边缘节点 | 应用进程/Redis |
| 共享范围 | 单实例 | 全局边缘 | 集群（Redis）或单实例（本地） |
| 失效控制 | 仅 TTL | 刷新 / purge API | 主动失效（消息/写时删） |
| 适合 | 内部接口、网关统一层 | 静态资源/大流量公开内容 | 业务数据、需要精细控制 |
| 代价 | 内存、不一致 | 部署/成本、动态内容弱 | 侵入业务代码 |

典型组合：**CDN 管静态、网关管"无状态可缓存 API"的快速兜底、服务内管业务数据**。网关缓存不是拿来替代它们的，而是"请求还没到服务之前，先省掉一次调用"。

### ③ 安全与正确性

- 默认 key 含 `Authorization`/Cookies → 个性化响应默认隔离（实验 6），但**别依赖它兜底敏感数据**：该 `no-store` 的必须显式配；
- 网关缓存基于"响应头语义"：下游返回 `private`/`no-store` 就不缓存；如果下游什么都不声明，**默认会被缓存**——所以要给"不可缓存"的接口补头，或者根本不在这些路由上挂 `LocalResponseCache`；
- 缓存的是完整响应体（内存），大响应/流式不要用本课方案。

---

## 38.6 坑表：七种"配错了"的下场

| 配法 | 现象 | 原因 |
|---|---|---|
| 不配 `filter.local-response-cache.enabled=true` | 路由编译报 `Unable to find GatewayFilterFactory with name LocalResponseCache` | 过滤器工厂 bean 根本不创建 |
| 只加 caffeine、没加 `spring-boot-starter-cache` | 自动配置不生效（无缓存） | `@ConditionalOnClass` 需要 `CaffeineCacheManager`；官方要求两个依赖 |
| 开了开关、没关全局过滤器 | 所有路由的 200 GET 响应都被缓存 | `global-filter.local-response-cache.enabled` 默认 true |
| 用 yml 配 `ModifyRequestBody` | 启动失败：`No converter found ... ParameterizedTypeReference` | 该过滤器只能 Java DSL |
| 下游接口没写 `no-store`/`private` | 不想缓存的响应也被缓存 | 负面清单模式：默认可缓存 |
| 用了 `no-cache` 请求头 | 自己拿到新值，别人还是旧值 | 默认 `SKIP_UPDATE_CACHE_ENTRY`，缓存未更新 |
| 大响应挂缓存 | 网关内存被吃、命中率低 | Caffeine 本地内存 + `size` 未设/设太大；超限条目直接不入缓存 |

补充（文档 vs 实现）：官方文档（本地新版）提到"已缓存 + `no-cache` 请求会返回 304 Not Modified"；**4.1.5 源码和实测都没有 304 逻辑**——是回源 200 且不更新缓存（实验 4）。另外文档说"只有原响应带 max-age 才重写"，实测是**没有也会被加上**（实验 1）。以 4.1.5 实现/实测为准。

---

## 38.7 面试问答（一句话答案 + 追问弹药）

1. **"网关能缓存吗？缓存什么？"**
   一句话：能——`LocalResponseCache` 用本机 Caffeine 缓存 **GET、无请求体、200/206/301、非 no-store/private、Vary≠\*** 的响应；命中直接返回。
   弹药：负面清单模式（没写 Cache-Control 也会被缓存）。
2. **"网关缓存和 CDN/服务内缓存怎么选？"**
   一句话：CDN 管静态与边缘；网关管"无状态可缓存 API"的统一兜底；服务内管业务数据与精细失效。
3. **"本地缓存的问题？"**
   一句话：每实例一份、无跨实例失效、仅 TTL 过期 → 只适合容忍短暂不一致的读。
4. **"缓存 key 怎么构成？"**
   一句话：URI（含 query）+ Authorization + Cookies + 响应 Vary 指定的头 → MD5/Base64。
   弹药：默认按凭证隔离（实验 6）。
5. **"`no-cache` 和 `no-store` 的区别？"**
   一句话：`no-store` = 禁止缓存（响应被拒）；`no-cache` = 允许缓存但每次要回源校验；SCG 默认回源后**不更新**缓存。
6. **"`ModifyRequestBody` 为什么不能用 yml？"**
   一句话：`inClass/outClass` 是 `ParameterizedTypeReference`，yml 字符串绑不上 → 只能 Java DSL。
7. **"body 改写有什么代价？"**
   一句话：请求/响应体被全量缓冲（流式丢失、大 body 吃内存），响应还要重算 Content-Length。

---

## 38.8 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| Caffeine 淘汰算法（W-TinyLFU）、`weigher` 内部 | ❌ 知道"本地缓存 + 权重上限"即可 |
| gzip 解码/编码的具体 Resolver | ❌ 知道"响应改写支持压缩解码"即可 |
| **什么能缓存的完整规则** | ✅ **本课 🔴**（负面清单 + 五种拒绝条件） |
| **本地缓存的一致性边界** | ✅ 要会（多实例、无失效、仅 TTL） |
| **body 改写的缓冲代价 + DSL 限制** | ✅ 要会（选型/排障用） |
| `no-cache` 两种策略、max-age 重算 | ⚪ 了解（用到再查） |

---

## 38.9 Self-Test

1. 列出缓存一个响应需要满足的**全部**条件（请求侧 + 响应侧 + 状态码 + Vary）。
2. 为什么"响应没有 Cache-Control"也会被缓存？不想被缓存应该怎么做？
3. 网关三个实例各有一份本地缓存，会带来哪些现象？怎么缓解？
4. 缓存 key 默认包含哪些东西？为什么按用户缓存的风险和收益各是什么？
5. `no-cache` 请求头 + 默认策略下，为什么"我拿到了新数据，别人还是旧数据"？怎么改成回源即更新？
6. `ModifyRequestBody` 为什么必须 Java DSL？用 yml 会发生什么？
7. body 改写为什么必须全量缓冲？对大文件/SSE 意味着什么？
8. （架构）给"商品详情接口"设计缓存：CDN / 网关本地 / 服务内 Redis 各放什么？

---

## 38.10 Open Questions

- [ ] 官方文档描述的 `no-cache → 304` 行为在 4.1.5 未实现；升级 SCG 时复核（若实现，会对客户端语义有影响）。
- [ ] `size` 权重的单位与真实内存的关系（Content-Length vs 实际堆占用）——需要压测估算网关缓存容量。
- [ ] 多实例一致性方案：本地缓存 + 广播失效（Redis Pub/Sub）在网关的可行性——与 L42（高可用）并线。
- [ ] `LocalResponseCache` 与 `Retry`/`CircuitBreaker` 的路由组合顺序实验（缓存命中可绕过熔断计数）——待验证。
