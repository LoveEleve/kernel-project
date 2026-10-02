# Lesson 27: The Four Forwarding Steps（转发四步）— 最核心

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 27（**最核心**）
> 前置：**L16**（SCLB 自定义负载均衡）· **L18**（请求全链路）· **L21/L23**（Route 与命中）· **L25/L26**（链的装配与洋葱）
> 口径：🔴 **`lb://` 在第二步被改写成物理 URI**

**本课回答一个问题**：一个请求在链上（L25/L26）**真正"发出去、再回来"的是哪几步**？
—— 四步：**拼 URL（10000）→ 选实例 / 改写（10150）→ Netty 发出（MAX）→ 最外层 post 写回（-1）**；每步都是一个全局过滤器，顺序是硬编码常量。

---

## 27.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **四步各有分工** | ①`RouteToRequestUrlFilter`(10000) 拼 URL → ②`ReactiveLoadBalancerClientFilter`(10150) 选实例（**lb→物理**）→ ③`NettyRoutingFilter`(MAX) 真发请求 → ④`NettyWriteResponseFilter`(-1) 写回 |
| **②** | **顺序 = 硬编码常量** | 四个都是 `GlobalFilter + Ordered`（10000 / 10150 / `LOWEST_PRECEDENCE` / -1） |
| **③** | **互斥靠 `alreadyRouted`** | 所有"转发类"过滤器先查 `isAlreadyRouted`，第一个接手者 `setAlreadyRouted` 置位 |
| **④** | **转发家族（4.1.5 实况）** | Netty（默认）/ WebClientHttp（**存在但零装配**）/ Websocket / Forward（+ForwardPath 配合）；**没有** Function/StreamRoutingFilter（计划口径更正，见 27.6） |
| **⑤** | **WebClient 两种用法** | ① 当转发器（`WebClientHttpRoutingFilter`，默认不用）② 过滤器里调下游；⚠️ **xhs 网关零远程调用**（L41 审计） |

---

## 27.1 例子先行：`GET /provider/hello` 在链上依次遇到谁

demo 路由（`uri: http://localhost:8081`）；**与转发相关的过滤器**按 order 排序后（**摘录**——完整 order 表随时查 `/actuator/gateway/globalfilters`，见 L8 §8.7）：

```
order   过滤器                               本次扮演
 -1     NettyWriteResponseFilter            ④ 写回（最外层，post 最后跑）
  0     ForwardPathFilter                     （非 forward，放行）
  1     StripPrefix                           （route filter，pre 改 path）
  2     AddRequestHeader                      （route filter）
  3     AddResponseHeader                     （route filter，post）
  4     DemoHeader                            （L15 自定义）
10000   RouteToRequestUrlFilter              ① 拼出 http://localhost:8081/provider/hello
10150   ReactiveLoadBalancerClientFilter     ②（本例 uri 是 http:// 直接放行；lb:// 才有事）
MAX     NettyRoutingFilter                    ③ 真发请求（链尾前最后一步）
──────────────────────── 链尾 Mono.empty() ────────────────────────
（逆流） ④ NettyWriteResponseFilter 的 post：把下游响应体写回客户端
```

**读法**：pre 顺着 order 向下（转发在第③步发生），post 逆着回来（写回在第④步完成）。四步 = **一路走到 Netty 发出请求，然后等响应回头写**。

> 链上还有 `RemoveCachedBodyFilter`(MIN) / `AdaptCachedBodyGlobalFilter`(MIN+1000) / `GatewayMetricsFilter`(0) / `ForwardRoutingFilter`·`WebsocketRoutingFilter`(MAX 附近) 等全局过滤器——本例静默放行，不展开（完整清单见 L8 §8.7）。

**三组现场实验**（均可直接复制执行）：§27.3 `lb://` 改写 · §27.4 真转发（死端口） · §27.6 `forward:` 家族。

---

## 27.2 第①步：`RouteToRequestUrlFilter`（order 10000）— 拼 URL

```java
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    if (route == null) { return chain.filter(exchange); }
    URI uri = exchange.getRequest().getURI();                       // 原请求：/provider/hello?x=1
    boolean encoded = containsEncodedParts(uri);
    URI routeUri = route.getUri();                                  // 路由 uri：http://localhost:8081
    if (hasAnotherScheme(routeUri)) {                               // ★ 双 scheme：如 lb:ws://…
        exchange.getAttributes().put(GATEWAY_SCHEME_PREFIX_ATTR, routeUri.getScheme());  // 存 "lb"
        routeUri = URI.create(routeUri.getSchemeSpecificPart());    // 后半段（ws://…）继续用
    }
    if ("lb".equalsIgnoreCase(routeUri.getScheme()) && routeUri.getHost() == null) {
        throw new IllegalStateException("Invalid host: " + routeUri.toString());   // 主机名非法（如带下划线）
    }
    URI mergedUrl = UriComponentsBuilder.fromUri(uri)
        .scheme(routeUri.getScheme())          // ★ 只覆盖 scheme/host/port
        .host(routeUri.getHost())
        .port(routeUri.getPort())
        .build(encoded).toUri();               // path/query 保留原请求！
    exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, mergedUrl);
    return chain.filter(exchange);
}
```

- **合成规则**：`原请求 path/query` + `路由 uri 的 scheme/host/port` → `GATEWAY_REQUEST_URL_ATTR`
  - `http://localhost:8081` + `/provider/hello` → `http://localhost:8081/provider/hello`
  - `lb://demo-provider` + `/provider/hello` → `lb://demo-provider/provider/hello`（**仍是 lb，第②步改**）
- **`GATEWAY_SCHEME_PREFIX_ATTR`**：给 `lb:ws://…` 这种"双 scheme"留底（第②步/Websocket 用）
- 这一步**只写属性、不发请求**——真正的"发"在很后面（第③步）

---

## 27.3 第②步：`ReactiveLoadBalancerClientFilter`（order 10150）— ★ `lb://` 改写

```java
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    URI url = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
    String schemePrefix = exchange.getAttribute(GATEWAY_SCHEME_PREFIX_ATTR);
    if (url == null || (!"lb".equals(url.getScheme()) && !"lb".equals(schemePrefix))) {
        return chain.filter(exchange);                       // 不是 lb:// → 放行
    }
    addOriginalRequestUrl(exchange, url);                    // 记原 URL（诊断/日志）
    URI requestUri = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
    String serviceId = requestUri.getHost();                 // ★ serviceId = host 段（demo-provider）
    ... // lifecycle 观测处理器
    DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(new RequestDataContext(...));
    return choose(lbRequest, serviceId, supportedLifecycleProcessors).doOnNext(response -> {
        if (!response.hasServer()) {
            throw NotFoundException.create(properties.isUse404(), "Unable to find instance for " + url.getHost());
        }
        ServiceInstance retrievedInstance = response.getServer();
        URI uri = exchange.getRequest().getURI();
        String overrideScheme = retrievedInstance.isSecure() ? "https" : "http";   // 实例安全→https
        if (schemePrefix != null) {
            overrideScheme = url.getScheme();                // ★ lb:ws://… → 用回 "ws"
        }
        DelegatingServiceInstance serviceInstance = new DelegatingServiceInstance(retrievedInstance, overrideScheme);
        URI requestUrl = reconstructURI(serviceInstance, uri);         // http://192.168.x.x:8081/provider/hello
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);            // ★ 覆盖！lb → 物理
        exchange.getAttributes().put(GATEWAY_LOADBALANCER_RESPONSE_ATTR, response);    // 实例记录（观测）
        supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStartRequest(lbRequest, response));
    })
        .then(chain.filter(exchange))                        // 改完继续走链
        .doOnError(...).doOnSuccess(...);                    // lifecycle 收尾
}
// choose()：clientFactory.getInstance(serviceId, ReactorServiceInstanceLoadBalancer.class) → loadBalancer.choose(...)
```

四个要点：

1. **判定**：`url.scheme == "lb"` 或 `schemePrefix == "lb"`（`lb:ws://` 那种）才动手，否则原样放行
2. **serviceId = host 段**：`lb://demo-provider/...` → `demo-provider`
3. **改写**：从负载均衡器选出一个 `ServiceInstance` → `reconstructURI` 重建 → **覆盖** `GATEWAY_REQUEST_URL_ATTR`（`lb://` 变 `http://ip:port/...`）——**这就是"lb:// 在第二步被改写"**；第③步的 Netty 只认 `http/https`
4. **选不到实例** → `NotFoundException`（`use404` 可配）→ 503/404；`GATEWAY_LOADBALANCER_RESPONSE_ATTR` 存实例（指标/lifecycle 用）

（负载均衡**内部**——chooser、hint、35s 缓存——在 **L16** 已讲，这里只看"改写发生的位置"。）

### 现场实验：`lb://` 第二步改写的直接证据（2026-10-02 实测）

```bash
# ① 改写前：路由 uri 就是 lb://demo-provider
curl -s --noproxy '*' http://127.0.0.1:8080/actuator/gateway/routes/demo-lb | python3 -m json.tool
# {
#     "predicate": "Paths: [/lb/**], match trailing slash: true",
#     "route_id": "demo-lb",
#     "filters": [ "[[StripPrefix parts = 1], order = 1]" ],
#     "uri": "lb://demo-provider",
#     "order": 0
# }

# ② 打一发：请求真的到了"物理实例"（看服务端 echo 出来的 Host 头）
curl -s --noproxy '*' -i http://127.0.0.1:8080/lb/hello
# HTTP/1.1 200 OK
# transfer-encoding: chunked
# X-Demo-WebFilter: hit-156
# Vary: Origin
# Vary: Access-Control-Request-Method
# Vary: Access-Control-Request-Headers
# Content-Type: application/json
# Date: Fri, 02 Oct 2026 06:28:06 GMT
#
# {"port":8081,"method":"GET","uri":"/hello","query":null,"headers":{"user-agent":"curl/8.5.0","accept":"*/*","x-demo-trace-id":"6f88e4ee14464099bd131534b07396da","x-demo-from-gateway":"true","forwarded":"proto=http;host=\"127.0.0.1:8080\";for=\"127.0.0.1:44014\"","x-forwarded-for":"127.0.0.1","x-forwarded-proto":"http","x-forwarded-prefix":"/lb","x-forwarded-port":"8080","x-forwarded-host":"127.0.0.1:8080","host":"192.168.13.128:8081","content-length":"0"},"greeting":"hello world"}
```

**怎么读这两个证据**：
- 响应体里 **`"host":"192.168.13.128:8081"`** —— 这是**下游实例收到的 Host 头**（服务端 echo 出来），它是 **Nacos 注册的物理 IP**，而不是路由里写的任何东西
- 对比 L23 §23.1 的静态路由（`uri: http://localhost:8081`）：那里的 `host` 是 `localhost:8081`；**同一个 provider，两个 Host，差别只来自"路由 uri 被谁改写成了什么"**
- 同一请求的日志里，Route 的 uri 仍是 `lb://demo-provider`：

```
Mapping [Exchange: GET http://127.0.0.1:8080/lb/hello] to Route{id='demo-lb', uri=lb://demo-provider, ...}
```

→ `lb://demo-provider` 变成了 `http://192.168.13.128:8081` —— 发生位置就是本节第②步（`reconstructURI` 重建 + 覆盖 `GATEWAY_REQUEST_URL_ATTR`，源码见上）。

---

## 27.4 第③步：`NettyRoutingFilter`（order `LOWEST_PRECEDENCE`）— 真转发

```java
public static final int ORDER = Ordered.LOWEST_PRECEDENCE;      // Integer.MAX_VALUE：链尾前最后一站

public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);   // 第②步后的物理 URL
    String scheme = requestUrl.getScheme();
    if (isAlreadyRouted(exchange) || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
        return chain.filter(exchange);                          // ★ 已被别家转发过 / 非 http(s) → 放行
    }
    setAlreadyRouted(exchange);                                 // ★ 置位：转发家族互斥
    ServerHttpRequest request = exchange.getRequest();
    final HttpMethod method = HttpMethod.valueOf(request.getMethod().name());
    final String url = requestUrl.toASCIIString();
    HttpHeaders filtered = filterRequest(getHeadersFilters(), exchange);        // HttpHeadersFilter 家族
    ...
    Flux<HttpClientResponse> responseFlux = getHttpClientMono(route, exchange)
        .flatMapMany(httpClient -> httpClient.headers(headers -> {
            headers.add(httpHeaders);
            headers.remove(HttpHeaders.HOST);
            if (preserveHost) { headers.add(HttpHeaders.HOST, request.getHeaders().getFirst(HttpHeaders.HOST)); }
        }).request(method).uri(url).send((req, nettyOutbound) ->                 // ★ 发请求 + 流式发 body
            nettyOutbound.send(request.getBody().map(this::getByteBuf)))
        .responseConnection((res, connection) -> {                               // ★ 拿到响应头/连接
            // Defer committing the response until all route filters have run
            // Put client response as ServerWebExchange attribute and write response later NettyWriteResponseFilter
            exchange.getAttributes().put(CLIENT_RESPONSE_ATTR, res);
            exchange.getAttributes().put(CLIENT_RESPONSE_CONN_ATTR, connection);
            ... // setResponseStatus(res, response) + 过滤响应头（HttpHeadersFilter RESPONSE 阶段）
            return Mono.just(res);
        }));
    Duration responseTimeout = getResponseTimeout(route);        // 路由 metadata `response-timeout` → 否则全局默认
    if (responseTimeout != null) {
        responseFlux = responseFlux.timeout(responseTimeout, Mono.defer(() -> Mono.error(
                new TimeoutException("Response took longer than timeout: " + responseTimeout))))
            .onErrorMap(TimeoutException.class, th ->
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, th.getMessage(), th));   // 504
    }
    return responseFlux.then(chain.filter(exchange));            // 转发完成 → 继续（很快到链尾 → 逆流到写回）
}
```

要点：
- **只发不写**：拿到响应后**故意不写 body**——把 `CLIENT_RESPONSE_ATTR`（响应头）和 `CLIENT_RESPONSE_CONN_ATTR`（连接）放进 exchange，原文注释："**Defer committing the response until all route filters have run**"→ 让链上后面的响应类过滤器还能改；写回交给第④步
- **超时两级**：路由 metadata `response-timeout` → 否则 `HttpClientProperties` 全局默认；超时 → **504 GATEWAY_TIMEOUT**（路由级超时配置见 **L06**；xhs 的 SSE 31 分钟就是在这层调的，L33）
- **连接超时**：metadata `connect-timeout` → `httpClient.option(CONNECT_TIMEOUT_MILLIS, …)`
- **body 流式**：`request.getBody().map(getByteBuf)` 直接流给下游（**不预先读全**——与 L28 的"body 只能读一次"直接相关）
- **Netty 在这里是"客户端"**：`httpClient.request(method).uri(url).send(...)` 是 reactor-netty 的 **outbound client**——它把 `GATEWAY_REQUEST_URL_ATTR` 里的 `host:port` 当作目标发起 **TCP connect**（这个地址要么是路由写死的，要么是第②步 LB 改写来的）。本例下游 `demo-provider` 是 `spring-boot-starter-web`（**Tomcat**）→ 这是 **Netty 客户端 → Tomcat 服务器**的跨栈调用；网关只认 "host:port + HTTP"，不关心下游是什么服务器

### 现场实验：Netty 确实连了（把路由指到没人监听的端口）

```bash
# 临时路由：uri 指向死端口 9999
curl -s --noproxy '*' -o /dev/null -w 'POST -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/routes/tmp-l27-dead \
  -H 'Content-Type: application/json' -d '{
 "id":"tmp-l27-dead","uri":"http://127.0.0.1:9999",
 "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l27dead/**"}}],
 "filters":[{"name":"StripPrefix","args":{"_genkey_0":"1"}}]}'
# POST -> 201
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/refresh
sleep 1

curl -s --noproxy '*' -i http://127.0.0.1:8080/tmp-l27dead/hello
# HTTP/1.1 500 Internal Server Error
# X-Demo-WebFilter: hit-177
# Vary: Origin
# Vary: Access-Control-Request-Method
# Vary: Access-Control-Request-Headers
# Content-Type: application/json
# Content-Length: 145
#
# {"timestamp":"2026-10-02T06:30:10.696+00:00","path":"/tmp-l27dead/hello","status":500,,"error":"Internal Server Error","requestId":"c4a311c8-177"}
```

日志里的直接原因（第③步 Netty **真去连了**）：

```
io.netty.channel.AbstractChannel$AnnotatedConnectException: finishConnect(..) failed: Connection refused: /127.0.0.1:9999
```

（"没人监听"= `ss -tlnp` 里没有 9999：地址合法、但没有进程 bind，TCP 握手被内核用 RST 拒绝。`finishConnect` 失败发生在 **connect 阶段**——只有真的发起连接才会出现这个异常；连接成功时反而没有日志，正常路径很安静。顺带演示了"错误逆流"：连接失败 → onError 一路冒回 → WebFlux 错误处理 → 500。清理：`DELETE /routes/tmp-l27-dead` + refresh。）

---

## 27.5 第④步：`NettyWriteResponseFilter`（order -1）— 最外层 post 写回

```java
public static final int WRITE_RESPONSE_FILTER_ORDER = -1;       // 排序最靠前 → 链的"最外圈"

public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    // NOTICE: nothing in "pre" filter stage as CLIENT_RESPONSE_CONN_ATTR is not added
    // until the NettyRoutingFilter is run
    return chain.filter(exchange)                                // ★ pre 为空：先让整条链跑
            .then(Mono.defer(() -> {                             // ★ post：链跑完才写
                Connection connection = exchange.getAttribute(CLIENT_RESPONSE_CONN_ATTR);
                if (connection == null) { return Mono.empty(); }
                ServerHttpResponse response = exchange.getResponse();
                final Flux<DataBuffer> body = connection.inbound().receive().retain()
                        .map(byteBuf -> wrap(byteBuf, response));    // 下游响应字节流
                ...
                return (isStreamingMediaType(contentType)
                        ? response.writeAndFlushWith(body.map(Flux::just))   // SSE/流式：逐块 flush
                        : response.writeWith(body));                         // 普通：写全
            }))
            .doFinally(signalType -> {
                if (signalType == SignalType.CANCEL || signalType == SignalType.ON_ERROR) {
                    cleanup(exchange);                           // 半途取消/出错 → dispose 连接
                }
            });
}
```

- **order -1 的用意**：排序最靠前 = 链的**最外圈**（L26 的洋葱）→ 它的 post **最后执行** = "所有过滤器都跑完（含响应类过滤器的 post）才把 body 写走"
- **对 L26 OQ 的回收**：第 26 课留的"`NettyWriteResponseFilter` 如何利用最外层 post"——**答案就是 order=-1 + `then(Mono.defer(...))`**，且注释明说 pre 无事可做
- 巧思：`CLIENT_RESPONSE_ATTR` 是响应头（第③步已放好，后面过滤器可改）；**真正的 body 直到这里才从 Netty 连接里读出来写走**

---

## 27.6 转发家族（4.1.5 实况表）+ 互斥机制

> **本节在讲什么**：前四节是"http(s) 请求交给 Netty 转发"这一条**主线**；本节退后一步——**"转发"其实是一族按 `scheme` 分派的过滤器**（http(s) / ws / forward 各有人接手），外加一条互斥规则（`alreadyRouted`）保证不重复转发；顺便交代 4.1.5 到底有哪几"家"。

| 类 | order | 接手条件 | 去向 | 默认装配 |
|---|---|---|---|---|
| `NettyRoutingFilter` | `LOWEST_PRECEDENCE` (MAX) | `http/https` 且未转发 | reactor-netty `HttpClient` | ✅（`@ConditionalOnEnabledGlobalFilter`）|
| `WebClientHttpRoutingFilter` | `LOWEST_PRECEDENCE` | `http/https` 且未转发 | `WebClient` | ❌ **全仓库零引用**（要用得自己装配）|
| `WebsocketRoutingFilter` | `MAX - 1` | `ws/wss`（或 http+`Upgrade: WebSocket` 转 ws）| `WebSocketClient` 代理 | ✅ |
| `ForwardRoutingFilter` | `LOWEST_PRECEDENCE` | `forward:` | 本地 `DispatcherHandler`（本机 WebFlux handler）| ✅ |
| `ForwardPathFilter` | **0** | `forward:` | 先把请求 path 改成路由 path，配合 ForwardRouting | ✅ |
| （`RouteToRequestUrlFilter` | 10000 | —— 拼 URL，属"前戏"）| | ✅ |
| （`NettyWriteResponseFilter` | -1 | 写回，属"后事"）| | ✅（`@ConditionalOnEnabledGlobalFilter(NettyRoutingFilter.class)`，跟 Netty 开关绑定）|

**互斥机制**（为什么"家族"不会重复转发）：每个转发类过滤器开头都是同一句话——

```java
if (isAlreadyRouted(exchange) || !<我负责的 scheme>) { return chain.filter(exchange); }
setAlreadyRouted(exchange);   // 谁先接手谁置位；后来的看到已转发就跳过
```

**⚠️ 计划口径更正**：学习清单里的"转发家族 6 种"（`FunctionRoutingFilter` / `StreamRoutingFilter`）**在 4.1.5 全仓库不存在**（stdout 为空的双重核实）——实况是 **5 个转发类（含 ForwardPath）+ 2 个配套**。清单上的两个名字疑似来自其他版本/文档，挂 27.11 待查。

### 现场实验：`forward:` 家族走"本机派发"（2026-10-02 实测）

用一条临时路由 `uri: forward:/actuator/health` 验证 ForwardPathFilter + ForwardRoutingFilter 的行为：

```bash
curl -s --noproxy '*' -o /dev/null -w 'POST -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/routes/tmp-l27-fwd \
  -H 'Content-Type: application/json' -d '{
 "id":"tmp-l27-fwd","uri":"forward:/actuator/health",
 "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l27fwd/**"}}]}'
# POST -> 201
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/refresh
sleep 1

curl -s --noproxy '*' http://127.0.0.1:8080/actuator/gateway/routes/tmp-l27-fwd | python3 -m json.tool
# {
#     "predicate": "Paths: [/tmp-l27fwd/**], match trailing slash: true",
#     "route_id": "tmp-l27-fwd",
#     "filters": [],
#     "uri": "forward:/actuator/health",
#     "order": 0
# }

curl -s --noproxy '*' -i http://127.0.0.1:8080/tmp-l27fwd/xxx
# HTTP/1.1 200 OK
# X-Demo-WebFilter: hit-171
# Content-Type: application/vnd.spring-boot.actuator.v3+json
# Content-Length: 15
#
# {"status":"UP"}
```

看 `Content-Type: application/vnd.spring-boot.actuator.v3+json` + `{"status":"UP"}`：请求被**本机 DispatcherHandler** 派发给了 actuator 的 health 端点（请求 path 先被 ForwardPathFilter 改成路由 uri 的 path）——**没有走任何网络转发**。清理：`DELETE /routes/tmp-l27-fwd` + refresh。

---

## 27.7 WebClient 的两种用法（含 xhs 对照）

> **本节在讲什么**：支线——① 想**换一种转发器**？`WebClientHttpRoutingFilter` 存在但本版没装；② 更常见的实战用法：在过滤器里**用 WebClient 调下游**（认证/风控/聚合）。最后是 xhs 的选择：**两种都不用**（零远程调用）。

| 用法 | 怎么用 | 何时选 |
|---|---|---|
| ① **当路由转发器** | 装配 `WebClientHttpRoutingFilter`（类存在、不默认装；配好 `WebClient` 后手动 `@Bean`）| 想用 WebClient 替换 Netty 发请求时（少见）|
| ② **在过滤器里调下游** | 自定义过滤器中注入 `WebClient`（`@LoadBalanced WebClient` 自带负载均衡，L16 提过 `LoadBalancerExchangeFilterFunction`）| 认证/风控/聚合等"顺路调一个服务" |

⚠️ **xhs 网关的实际口径**：**两种都不用**——JWT 本地验签，**请求路径零远程调用**（真实证据与审计写进 **L41**）。这也是网关保持低延迟的关键设计。

---

## 27.8 附：过滤器家族形状预告（Retry / RateLimiter / CircuitBreaker）

> **本节在讲什么**：这节**不是转发内容**，而是站在本课末尾往下一阶段看一眼——"过滤器家族"里三个常见形状（重试 / 限流 / 熔断）的共同结构：**围绕 `chain.filter` 加一层策略**。细节留给 L36/L37，这里只认骨架。

**先分清两层（不然会以为"过滤器就是用 apply"）**：这三个类都是**工厂**（`*GatewayFilterFactory`），入口是 `apply(Config)`——它**返回**一个 `GatewayFilter`；"围绕 `chain.filter` 加策略"的代码写在**返回的那个过滤器**的 `filter(exchange, chain)` 方法体里（下面每个代码块里的 `return (exchange, chain) -> {...}` / `return new GatewayFilter() {...}` 才是过滤器本体）。`apply` 在**编译期**跑一次（L14 三段式 / L21），`filter` 每请求跑（L26）。

三个都是"**围绕 `chain.filter` 加一层策略**"的同构工厂（Retry/CircuitBreaker 是"包住下游"，RateLimiter 是"关卡式放行/拒绝"；细节课在 **L36/L37**，这里只认结构）：

**① `RetryGatewayFilterFactory`（重放型）**

```java
// apply(RetryConfig)：把配置编译成两个谓词器
Repeat<ServerWebExchange> statusCodeRepeat;   // 状态码类重试（series/statuses）
Retry<ServerWebExchange> retry;               // 异常类重试（exceptions/timeout/backoff/jitter）

// apply(routeId, repeat, retry)：真正的过滤器
public GatewayFilter apply(String routeId, Repeat<ServerWebExchange> repeat, Retry<ServerWebExchange> retry) {
    enableBodyCaching(routeId);                                  // ★ 重试要重放读 body → 需要 body 缓存（L28）
    return (exchange, chain) -> {
        Publisher<Void> publisher = chain.filter(exchange)
            .doOnSuccess(aVoid -> updateIteration(exchange))
            .doOnError(throwable -> updateIteration(exchange));   // 计数（maxIterations）
        if (retry != null) {
            // retryWhen returns a Mono<Void>
            // retry needs to go before repeat
            publisher = ((Mono<Void>) publisher).retryWhen(Retry.withThrowable(retry.withApplicationContext(exchange)));
        }
        if (repeat != null) {
            // repeatWhen returns a Flux<Void> so this needs to be last
            publisher = ((Mono<Void>) publisher).repeatWhen(repeat.withApplicationContext(exchange));
        }
        return Mono.fromDirect(publisher);
    };
}
// RetryConfig 默认：retries=3、series=[SERVER_ERROR]、methods=[GET]、exceptions=[IOException, TimeoutException]
```

→ "重放整条下游链"（L26 §26.4③）的直接证据；注意 `retryWhen` 必须包在 `repeatWhen` 里面。

**② `RequestRateLimiterGatewayFilterFactory`（限额型）**

```java
public GatewayFilter apply(Config config) {
    KeyResolver resolver = getOrDefault(config.keyResolver, defaultKeyResolver);      // key 从哪来（用户/IP/…）
    RateLimiter<Object> limiter = getOrDefault(config.rateLimiter, defaultRateLimiter); // Redis 令牌桶等
    boolean denyEmpty = getOrDefault(config.denyEmptyKey, this.denyEmptyKey);         // 默认 true
    ...
    return (exchange, chain) -> resolver.resolve(exchange).defaultIfEmpty(EMPTY_KEY).flatMap(key -> {
        if (EMPTY_KEY.equals(key)) {
            if (denyEmpty) { setResponseStatus(exchange, emptyKeyStatus); return exchange.getResponse().setComplete(); }
            return chain.filter(exchange);                            // 放行
        }
        String routeId = ...;
        return limiter.isAllowed(routeId, key).flatMap(response -> {
            for (Map.Entry<String, String> header : response.getHeaders().entrySet()) {   // X-RateLimit-* 头
                exchange.getResponse().getHeaders().add(header.getKey(), header.getValue());
            }
            if (response.isAllowed()) { return chain.filter(exchange); }
            setResponseStatus(exchange, config.getStatusCode());       // 默认 429 TOO_MANY_REQUESTS
            return exchange.getResponse().setComplete();
        });
    });
}
```

→ 限流不拒绝时是"放行 + 补 `X-RateLimit-*` 头"；拒绝时不走链（L26 §26.4① 的短路）。

**③ `SpringCloudCircuitBreakerFilterFactory`（熔断型，抽象基类）**

```java
public GatewayFilter apply(Config config) {
    if (config.getFallbackUri() != null) { enableBodyCaching(config.getRouteId()); }   // ★ 又见 body 缓存钩子（L28）
    ReactiveCircuitBreaker cb = reactiveCircuitBreakerFactory.create(config.getId());
    ...
    return (exchange, chain) -> cb.run(
        chain.filter(exchange).doOnSuccess(v -> {                    // 正常走链后检查状态码
            if (statuses.contains(exchange.getResponse().getStatusCode())) {
                throw new CircuitBreakerStatusCodeException(...);    // 命中配置的状态码 → 当作"失败"
            }
        }),
        t -> {                                                       // 失败分支
            if (config.getFallbackUri() == null) { return Mono.error(t); }
            ... // 用 fallbackUri 构造新 URL（通常 forward:/fallback）
            exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);
            reset(exchange);                                          // 重置 exchange（防旧响应脏数据）
            return handle(getDispatcherHandler(), exchange.mutate().request(request).build());  // ★ 交给 DispatcherHandler（本地派发）
        })
        .onErrorResume(t -> handleErrorWithoutFallback(t, config.isResumeWithoutError()));
}
protected abstract Mono<Void> handleErrorWithoutFallback(Throwable t, boolean resumeWithoutError);  // 子类（Resilience4J 等）实现
```

→ 熔断的 fallback 走 **DispatcherHandler 本地派发**：请求被改成 fallbackUri 后交给本机 handler 体系（常规用法是指向本机端点的 `forward:/fallback`）；⚠️ 若该路径恰好命中某条路由，仍可能再次进入网关链路。

---

## 27.9 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| LB 内部（chooser/hint/缓存） | ❌ L16 已讲 |
| `HttpHeadersFilter` 家族逐个 | ❌ 知道"请求头过滤/响应头过滤两阶段"即可 |
| **四步顺序 + `lb://` 在第二步改写** | ✅ **本课唯一 🔴** |
| **`alreadyRouted` 互斥 + 家族实况** | ✅ 要会（面试问"为什么不会重复转发"） |
| Netty 的"延迟写回"设计 | ✅ 要会（和 L26 洋葱闭环） |
| Retry/RateLimiter/CircuitBreaker 内部细节 | ⚪ 结构即可，细节 L36/L37 |
| Websocket/Forward 代理细节 | ❌ 用到再看（L33 回来） |

---

## 27.10 Self-Test

1. 四步的顺序号与各自一句话职责？为什么 `NettyWriteResponseFilter` order=-1 却是"最后写"？
2. `lb://demo-provider/provider/hello` 在哪一步、被改成什么？`lb:ws://` 和 `lb://` 的处理差在哪？
3. 多个转发类过滤器如何保证只有一个真的发请求？
4. `NettyRoutingFilter` 为什么拿到响应后不直接写 body？（原文注释）真正的 body 何时写？
5. `response-timeout` 的两级来源？超时变成什么状态码？
6. WebClient 的两种用法各是什么？xhs 网关为什么都不用？
7. Retry 过滤器"重放整条下游链"依赖 chain 的什么性质？（L26 回收）它为什么要 `enableBodyCaching`？

---

## 27.11 Open Questions

- [ ] `FunctionRoutingFilter` / `StreamRoutingFilter` 在 4.1.5 不存在 —— 计划口径来自哪（官方文档/新版本）？待查。
- [ ] `WebClientHttpRoutingFilter` 的官方推荐装配方式（手动 `@Bean`？是否要排除 NettyRoutingFilter？）—— 未验证。
- [x] `ForwardPathFilter`(order 0) 与 `ForwardRoutingFilter`(MAX) 的分工 —— **27.6 已实测**（`forward:` 路由返回 200 `{"status":"UP"}`，本机派发、无网络转发）。
- [ ] `NotFoundException` 的 `use404` 配置与状态码细节 —— 未细看（L30/L44 可能涉及）。
