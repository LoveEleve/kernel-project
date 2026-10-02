# Lesson 18: WebFlux Request Pipeline & Gateway's Insertion Point — 请求从 Netty 到网关经过了什么

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 18（★v5.5 新增：纠正"WebFlux 不用学"的误判）
> 前置：L17（四组组件；**17.5.0 已讲"两个插头"**）· L09~L12（Reactor：Mono / 惰性 / 订阅）
> 口径：**讲机制不逐行**；本课只回答一个问题：**"一个请求从 TCP 字节到我们自己的过滤器，中间有哪些站？"**
> 版本：v2 重排版（2026-09-28）—— 大段讲解改为正文小节，`>` 只留给"一句话 / 警告"

---

## 18.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **一条管线（8 站）** | Netty → 翻译官 → **exchange 出生** → WebFilter 链 → **DispatcherHandler（三问）** → 网关插入点 → 网关过滤器链 |
| **②** | **三个策略名单** | `DispatcherHandler` 手里三份：`handlerMappings` / `handlerAdapters` / `resultHandlers`（按 order 排序，逐个问"第一个支持者胜"） |
| **③** | **两个坑** | `FilteringWebHandler` **重名**（spring-web 的 ≠ gateway 的）；`NO_OP_HANDLER` 短路（"返回空"≠"返回 NO_OP"） |

> **为什么要有这一课**：面试问"请求从 Netty 到你的过滤器经过什么" —— 只会说 `DispatcherHandler` 就是断档；而且 17.5.0 讲的"插头"，正是在这条管线的**第 6 站被问到、第 7 站被选中**的。

---

## 18.1 例子先行：跟着 `GET /provider/hello` 走一遍

你在浏览器敲下回车、请求进了 demo-gateway 的 8080 端口 —— 在你写的（或 L14 写的）过滤器之前，它其实已经穿过了一条 **8 站的流水线**：

| 站 | 谁（类） | 干什么（人话） | 关键产物 |
|---|---|---|---|
| **1** | **Netty EventLoop** | 收到 TCP 字节，解析成 HTTP | Netty 世界的请求/响应 |
| **2** | `ReactorHttpHandlerAdapter` | **翻译官**：Netty 类型 → Spring 类型 | `ReactorServerHttpRequest` 等 |
| **3** | **`HttpWebHandlerAdapter`** | **创建 exchange（★ 全课最重要）** | `DefaultServerWebExchange` |
| **4** | `ExceptionHandlingWebHandler` | 给整条链兜异常 | （装饰层） |
| **5** | **`FilteringWebHandler`(web) + `DefaultWebFilterChain`** | 跑 **WebFilter** 链 | 实测：`WeightCalculatorWebFilter` |
| **6** | **`DispatcherHandler`** | **开始干活**：问"谁管这请求？" | 问到 **`RoutePredicateHandlerMapping`** |
| **7** | **C 组 + `SimpleHandlerAdapter`** | 匹配路由、返回 handler、装配"调用者" | handler = **gateway `FilteringWebHandler`** |
| **8** | **gateway `FilteringWebHandler`** | 拼链 + 执行 | `NettyRoutingFilter` 转发出去 |
| （回程） | `NettyWriteResponseFilter` 等 | 响应原路返回 | 写回 Netty → 客户端 |

这张表里有**三个最容易漏的点**：

1. **exchange 在第 3 站出生** —— 从这站起所有站（含你的过滤器）**共用同一个 exchange**；这就是 L17 讲"差异数据放 exchange"的地基（18.2 附 B 会给源码）。
2. **"网关"要到第 6 站才出现** —— 前 5 站全是 WebFlux 的通用管线；网关只是"名单里的一个"，没有改管线。
3. **两套 Filter，两个世界**：**WebFilter**（第 5 站）所有请求都过、与路由无关；**GlobalFilter / GatewayFilter**（第 8 站）**匹配到路由之后**才拼进链。

上面第 3 点用一张"边界图"看更清楚（也回答"WebFilter 时还没进网关路由？"）：

```
请求进来（已进入"网关应用"）
  └─ WebFilter 链                    ← ★ 所有请求都过（含 /actuator/**、无路由的 404 请求）
       └─ DispatcherHandler："谁管？"
            ├─ /actuator/** → Actuator 处理（根本不走网关路由）
            ├─ 命中 Route   → 网关过滤器链（GlobalFilter/GatewayFilter）← ★ 只过"匹配到路由"的
            └─ 都没命中     → 404
```

日志实证（L18 实验）：`DemoWebFilter` 记到 `/actuator/health` 2 条、无路由的 `/web-filter-block`；而全局过滤器 `DemoGlobalFilter` 记到 actuator **0** 条。

#### 附：运行时对象速查（字段 → 值）—— 防混专用

**① WebHandler 套娃链（第 3~6 站的真身）**

```
HttpWebHandlerAdapter                       ← 最外层（implements HttpHandler）
 ├─ delegate = ExceptionHandlingWebHandler
 │    ├─ delegate = FilteringWebHandler(spring-web)
 │    │    ├─ chain = DefaultWebFilterChain      ← ★ 链表头节点
 │    │    │    ├─ currentFilter = demoWebFilter                      （order = MIN+100）
 │    │    │    ├─ chain ──→ { currentFilter = weightCalculatorWebFilter（order=10001）
 │    │    │    │             → 尾节点 { currentFilter = null, chain = null } }
 │    │    │    ├─ allFilters = [demoWebFilter, weightCalculatorWebFilter]   ← "几个 WebFilter"数这里
 │    │    │    └─ handler = DispatcherHandler    ← 链尾（是 WebHandler，**不是 WebFilter**）
 │    │    └─ getDelegate() = DispatcherHandler
 │    └─ exceptionHandlers = [CheckpointInsertingHandler,
 │                            errorWebExceptionHandler（DefaultErrorWebExceptionHandler）,
 │                            observationClosingWebExceptionHandler]
 └─ sessionManager / codecConfigurer / localeResolver = Boot 默认值
```

> ⚠️ 口径：**"几个 WebFilter" = `allFilters` 的长度**（原始 demo 1 个；+L18 实验后 = **2 个**）；`DispatcherHandler` 是链尾的 **WebHandler**，不算 WebFilter。

**② `DispatcherHandler` 三份名单的真实值**（启动时 `initStrategies` 收集 + 排序）：

`handlerMappings`（8 个，**按 order 升序** —— 这就是"被问顺序"）：

| 序 | bean | order | 管什么 |
|---|---|---|---|
| 1~3 | `controllerEndpointHandlerMapping` / `healthEndpointWebFluxHandlerMapping` / `webEndpointReactiveHandlerMapping` | **-100** | Actuator（三者同 order，相对顺序不保证） |
| 4 | `routerFunctionMapping` | **-1** | 函数式路由 |
| 5 | `requestMappingHandlerMapping` | **0** | `@Controller` |
| 6 | **`routePredicateHandlerMapping`** | **1** | **网关（本课主角）** |
| 6' | `welcomePageRouterFunctionMapping` | **1** | 欢迎页（同 order） |
| 7 | `resourceHandlerMapping` | **MAX-1** | 静态资源（最后） |

`handlerAdapters`（4 个）：`requestMappingHandlerAdapter` / `handlerFunctionAdapter` / **`simpleHandlerAdapter`**（认领网关 handler 的就是它）/ `webFluxWebSocketHandlerAdapter`

`resultHandlers`（4 个）：`responseBodyResultHandler` / `responseEntityResultHandler` / `serverResponseResultHandler` / `viewResolutionResultHandler`

**③ 网关侧核心对象（字段 → 值）**

| 对象（bean） | 核心字段 | 值 |
|---|---|---|
| `RoutePredicateHandlerMapping`（C 组） | `webHandler` | gateway `FilteringWebHandler`（单例） |
| | `routeLocator` | `CachingRouteLocator`（@Primary） |
| | `order` | `1` |
| | `managementPort / managementPortType` | `null / SAME`（demo 未配 management 端口） |
| gateway `FilteringWebHandler`（D 组） | `globalFilters` | **12 条**（18.7 ④ 的 16 条里"全局"那批） |
| | `routeFilterCacheEnabled / routeFilterMap` | `false / {}`（per-route 缓存默认关闭） |
| `CachingRouteLocator`（B 组最外层） | `order` / `delegate` | `0` / `CompositeRouteLocator` |
| | `cache` | `{"routes" → List<Signal<Route>>}`（取过之后） |
| `CompositeRouteLocator` | `delegates` | `[routeDefinitionRouteLocator]` |
| `RouteDefinitionRouteLocator`（B 组翻译器） | `predicates` / `gatewayFilterFactories` | `Map(15) / Map(33)` |
| | `routeDefinitionLocator` | `CompositeRouteDefinitionLocator`（A 组 @Primary） |

**④ 三种"链"别混**（这是最容易乱的地方）：

| 链 | 实现 | 数据结构 | demo 的值 |
|---|---|---|---|
| WebFilter 链（第 5 站） | `DefaultWebFilterChain` | **链表节点**：`currentFilter` + `chain`（下一个节点）；尾节点 `currentFilter=null` | `[weightCalculatorWebFilter]`（1 个） |
| 网关过滤器链（第 8 站） | `DefaultGatewayFilterChain` | **index 数组式**：`filters`(List) + `index`（0,1,2… 递归） | 16 条（12 全局 + 4 路由，见 18.7 ④） |
| 装饰器链（WebHandler 套娃） | `WebHandlerDecorator` 子类 | `delegate`（构造注入，`handle()` 原样转发） | 见 ① |

---

## 18.2 第 1~3 站：Netty → 翻译官 → **exchange 出生**

### 第 1~2 站：把"Netty 的话"翻译成"Spring 的话"

Netty 世界里请求叫 `HttpServerRequest`；Spring 世界里叫 `ServerHttpRequest` —— 两边类型不同、谁也用不了对方的，中间需要**翻译官** `ReactorHttpHandlerAdapter`（由 Boot 的 `NettyWebServer` 绑到 reactor-netty 上；你只需知道"Netty 一收到请求，回调的是它"）。

翻译官的全部工作：

```java
// spring-web：ReactorHttpHandlerAdapter
public Mono<Void> apply(HttpServerRequest reactorRequest, HttpServerResponse reactorResponse) {
    NettyDataBufferFactory bufferFactory = new NettyDataBufferFactory(reactorResponse.alloc());
    try {
        ReactorServerHttpRequest request = new ReactorServerHttpRequest(reactorRequest, bufferFactory);
        ServerHttpResponse response = new ReactorServerHttpResponse(reactorResponse, bufferFactory);
        ...
        return this.httpHandler.handle(request, response)      // ★ 转完就交给下一站
                .doOnError(ex -> logger.trace(...))            // 只打日志
                .doOnSuccess(aVoid -> logger.trace(...));      // 不影响流程
    } ...
}
```

三件事，逐行看：

| 行 | 干了什么 | 为什么 |
|---|---|---|
| `new NettyDataBufferFactory(...)` | 数据缓冲的翻译（Netty `ByteBuf` ↔ Spring `DataBuffer`） | 两边内存抽象不同，读写 body 要用 Spring 的统一接口 |
| `new ReactorServerHttpRequest/Response(...)` | 请求/响应的翻译（**包装/适配器模式**） | 让下游只认 Spring 的接口，不认 Netty |
| `this.httpHandler.handle(request, response)` | 交给下一站（第 3 站） | —— |

一句话：这一站只做"**类型翻译**"—— 换 Tomcat / Undertow，换的就是这一层，网关完全无感。

#### 附 A：这个"翻译官"到底是什么 —— 适配器模式（包一层，不是拷贝）

世界上其实是**三层类型**，每两层之间有一次转换：

| 层 | Netty 栈（本课） | Tomcat 栈（对照） |
|---|---|---|
| ① 解析层内部 | Netty 原生 `HttpObject` 等（Netty 私有） | Coyote 的 `org.apache.coyote.Request`（Tomcat 私有） |
| ② 中间门面 | **reactor-netty** 的 `HttpServerRequest`（库自己定义） | **Servlet 规范** 的 `HttpServletRequest`（`RequestFacade` 实现） |
| ③ Spring 抽象 | `ServerHttpRequest`（`ReactorServerHttpRequest` 包住 ②） | `ServerHttpRequest`（`ServletServerHttpRequest` 包住 ②） |
| **转换点在哪** | **Spring**：`ReactorHttpHandlerAdapter` | **Tomcat 内部**：`CoyoteAdapter.service()` |

"包一层"的证据（两边都是"抱着对方"）：

```java
// Spring 侧：ReactorServerHttpRequest 抱着 reactor-netty 的请求
class ReactorServerHttpRequest extends AbstractServerHttpRequest {
    private final HttpServerRequest request;           // ← reactor-netty 的类型
}
// Tomcat 侧同款：Catalina 的 Request 抱着 Coyote 的请求
public class Request implements HttpServletRequest {
    protected org.apache.coyote.Request coyoteRequest; // ← Tomcat 私有类型
}
```

**为什么要包**：`ServerHttpRequest` 是**传输无关**的 —— 换 Netty / Undertow / Servlet 只是换适配器，业务代码不动（代价是多一层方法转发）。Servlet 侧 Spring 也有同款桥 `ServletServerHttpRequest`（解析 `@RequestBody` 的 `AbstractMessageConverterMethodArgumentResolver` 里就在用）。
（reactor-netty 内部怎么再包 Netty：**翻篇**，见 18.8 口径。）

### 第 3 站：`HttpWebHandlerAdapter` —— **exchange 在这里出生**

Netty 给的是"请求、响应"两个独立对象；但 WebFlux 想让整条链有一个**统一的工作台**：请求 + 响应 + **属性袋** + session + 编解码器全挂一起 —— 这就是 `ServerWebExchange`。它就在这一站被 new 出来：

```java
// 类声明：它是 HttpHandler（最外层），同时是 WebHandlerDecorator（包着下游）
public class HttpWebHandlerAdapter extends WebHandlerDecorator implements HttpHandler {

    @Override
    public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
        ...（forwarded header 处理，可选，省略）
        ServerWebExchange exchange = createExchange(request, response);   // ★ 出生！

        ServerRequestObservationContext observationContext = new ServerRequestObservationContext(
                exchange.getRequest(), exchange.getResponse(), exchange.getAttributes());
        exchange.getAttributes().put(                                          // ← 第一个进"属性袋"的东西
                ServerRequestObservationContext.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observationContext);

        return getDelegate().handle(exchange)              // ← 交给下游（装饰链的下一层）
                .doOnSuccess(aVoid -> logResponse(exchange))
                .onErrorResume(ex -> handleUnresolvedError(exchange, observationContext, ex))
                .tap(() -> new ObservationSignalListener(observationContext))
                .then(exchange.cleanupMultipart())          // 收尾①：清 multipart 临时文件
                .then(Mono.defer(response::setComplete));   // 收尾②：响应"完成"信号统一在这发
    }
```

| # | 关键行 | 讲解 |
|---|---|---|
| 1 | `createExchange(request, response)` | **exchange 在这里 new 出来**（见下） |
| 2 | `exchange.getAttributes().put(...OBSERVATION...)` | 第一个放进"属性袋"的东西是**观测上下文**（附 B 细讲） |
| 3 | `getDelegate().handle(exchange)` | **交给下游**：`WebHandlerDecorator` 的语义就是"原样转给 delegate" |
| 4 | `.then(response::setComplete)` | **响应不是哪一站"自己收尾"** —— 整条链跑完后，完成信号在这一站的回调里统一发出 |

```java
// createExchange：exchange 的"出生证"
protected ServerWebExchange createExchange(ServerHttpRequest request, ServerHttpResponse response) {
    return new DefaultServerWebExchange(request, response, this.sessionManager,
            getCodecConfigurer(), getLocaleContextResolver(), this.applicationContext);
}
```

| 参数 | 是什么 | 用在哪 |
|---|---|---|
| `request` / `response` | 上游翻译官给的 | 全链路读写请求/响应 |
| `sessionManager` | WebSession 管理器 | **懒创建**：用到 session 时才建 |
| `codecConfigurer` | 编解码器（`ServerCodecConfigurer`） | 读写 body（JSON 等） |
| `localeContextResolver` | 区域解析器 | 按 `Accept-Language` 解析语言 |
| `applicationContext` | 应用上下文 | 需要拿 bean / 发事件时用 |

> **一句话**：**一个请求 = 一个 exchange**；它在这里出生，然后被整条链共享 —— 这就是 C 组放 Route、D 组取 Route 的地基。

#### 附 B：第一个进属性袋的东西 —— 观测上下文（`ServerRequestObservationContext`）

**它是什么**：Spring 6 **Observation API**（micrometer-observation）里"**一次 HTTP 请求的观测记录本**"：

```java
public class ServerRequestObservationContext extends RequestReplyReceiverContext<ServerHttpRequest, ServerHttpResponse> {
    public static final String CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE = ServerRequestObservationContext.class.getName();
    private final Map<String, Object> attributes;      // ← 属性袋的只读视图（构造第 3 参数）
    private String pathPattern;
    private boolean connectionAborted;
}
```

**为什么放进属性袋**（官方 javadoc 原话）：*"Web Frameworks can fetch the current context as a request attribute and **contribute additional information to it**"* —— 让整条链（WebFilter / HandlerMapping / 网关过滤器）都能往"这次请求的记录本"里**补料**（状态码 / 异常 / 路径模式），最后由 convention 收集成 metrics / tracing 的标签。

取用方式（静态工具，从属性袋里捞）：

```java
ServerRequestObservationContext.findCurrent(exchange.getAttributes())
        .ifPresent(context -> context.setPathPattern(bestPattern.toString()));
// ↑ Spring 自己的 HandlerMapping 就在用：把 /orders/{id} 这样的"路径模式"写进去
//    （这就是 metrics 里 uri 标签是模板而非 /orders/123 的原因；RequestMappingInfoHandlerMapping:150 / AbstractUrlHandlerMapping:173）
```

**反直觉点：这里只"创建 + 存档"，观测还没开始。** start/stop 在 `ObservationSignalListener`（挂在响应式信号上）：

```java
@Override
public void doFirst() throws Throwable {
    this.observation.start();          // ★ 订阅时才 start（L09 的惰性！）
}
...
private void doOnTerminate(ServerRequestObservationContext context) {
    ServerHttpResponse response = context.getResponse();
    if (response != null) {
        if (response.isCommitted()) { this.observation.stop(); }
        else { response.beforeCommit(() -> { this.observation.stop(); return Mono.empty(); }); }  // ★ 提交前 stop
    }
}
```

另外 `doOnComplete` 会从**属性袋**里读异常（`HANDLED_WEB_EXCEPTION`）记入观测 —— **又是"属性袋当共享黑板"的用法**。

##### 它到底"观测"到了什么 —— 实测（`metrics` 已在 `application.yml` 开启）

发 2×`/provider/hello`(200) + 1×`/lb/hello`(200) + 1×`/no-such-route`(404) 后：

```bash
curl --noproxy '*' -s http://localhost:8080/actuator/metrics/http.server.requests | python3 -m json.tool
# → COUNT=6, TOTAL_TIME=4.84s, MAX=1.89s
#   availableTags: method / status / uri / outcome / exception / error
```

这份 JSON 逐项读：

| 字段 | 值 | 人话 |
|---|---|---|
| `name` | `http.server.requests` | 账本名："HTTP 服务端请求"这本账 |
| `baseUnit` | seconds | 记的是**耗时**（秒） |
| `measurements[COUNT]` | 6 | **一共记了 6 次请求**（4 业务 + 2 次健康检查 —— **所有** HTTP 请求都记，不只走路由的） |
| `measurements[TOTAL_TIME]` | 4.84 | 6 次**加起来** 4.84s（平均值没给，自己算 4.84/6≈0.81s） |
| `measurements[MAX]` | 1.89 | **最慢那一次** 1.89s |
| `availableTags` | method/status/uri/outcome/exception/error | **"可以按哪些维度切片"的目录**（列的是已出现过的取值，不是明细） |

**下钻（drill-down）= 加 `?tag=key:value`**（metrics 最常用的姿势）：

```bash
curl -s --noproxy '*' ".../http.server.requests?tag=uri:UNKNOWN"
# → COUNT=3, TOTAL=2.87s, MAX=1.23s   ←"网关处理的那 3 个请求"的小计
```

各标签实测值：`status = 200 / 404`｜`method = GET`｜`outcome = SUCCESS / CLIENT_ERROR / UNKNOWN`｜`exception / error = NoResourceFoundException / none`（**异常也进标签**：404 无路由 → 资源映射抛的）。
（更早一次测试在 **Nacos 未启动**时还见过 `504 + ResponseStatusException`。）

**★ 实测发现：网关自己处理的请求，`uri` 标签是 `UNKNOWN`**（按 uri 切片：`UNKNOWN`=3 = 2×provider+1×lb；`/**`=1 = 无路由走资源映射；`/actuator/health`=2）。
原因：convention 的 uri 优先用 **`pathPattern`**，而 **C 组没有 `setPathPattern`** —— 只有注解式（`RequestMappingInfoHandlerMapping`）和 URL 式（`AbstractUrlHandlerMapping`）HandlerMapping 才写它。
想让网关的 uri 标签有值：自定义 `WebFilter` 里 `findCurrent(...).ifPresent(ctx -> ctx.setPathPattern(路由 id / 谓词模式))`。

边界：metrics **天生是聚合的**（只有汇总，没有单条明细）；要"一次请求一条"用 tracing / 日志。

> ⚠️ **本机 shell 设了 `http_proxy`** —— curl 必须加 `--noproxy '*'`，否则 `localhost` 被送去代理 → **502 / 空响应**（`-s` 会藏掉错误）。一劳永逸：`export NO_PROXY="localhost,127.0.0.1,::1"`。

##### 三个"Context"别混（和 L12 的 Reactor Context 什么关系）

| 名字 | 是什么 | 层级 | 存取方式 | 在哪课 |
|---|---|---|---|---|
| `ApplicationContext` | Spring 容器（bean 的家） | 应用级 | 注入 / 查找 bean | Boot 基础 |
| **Reactor `Context`** | **响应式链级上下文**（随订阅流动的只读元数据，不可变） | **链级（每次订阅）** | `contextWrite` / `deferContextual` | **L12** |
| **`ServerRequestObservationContext`** | **观测的记录本**（普通 Java 对象） | 对象级（一次请求一个） | 属性袋 `findCurrent()` | 本课 |

它们**不是同一层东西**，但有两个桥串起来：

```java
// 桥①：Observation → Reactor Context（spring-web：让 tracing 跨线程时能恢复）
@Override
public Context addToContext(Context originalContext) {
    return originalContext.put(ObservationThreadLocalAccessor.KEY, this.observation);
}
// 桥②：Reactor Context → exchange 属性袋（gateway C 组：让非响应式代码也能拿到 Reactor Context）
return Mono.deferContextual(contextView -> {
    exchange.getAttributes().put(GATEWAY_REACTOR_CONTEXT_ATTR, contextView);
    ...
});
```

一次请求里，两个"容器"各自装什么（时间线）：

```
[第 3 站] HttpWebHandlerAdapter.handle()
   ├─ 记录本 observationContext → 塞进【exchange 属性袋】        ← 公告板：同步可见、可变
   └─ 观测对象 Observation      → 塞进【Reactor Context】(桥①)   ← 运单：跨线程传播、不可变
[处理中]
   ├─ 谁想补料：findCurrent(公告板) → setPathPattern / setError
   └─ 切线程：运单带着 Observation 走 → tracing 的 ThreadLocal 被恢复（trace 不断链）
[结束]
   ├─ 从公告板读异常（HANDLED_WEB_EXCEPTION）→ 记入观测
   └─ stop() → 归档成 metrics + span
```

> **一句话**：**Reactor Context 是"随流传播的运单袋"；ObservationContext 是"这次请求的工作记录单"** —— 观测**不属于** Reactor Context，只是搭了它这趟车（为了跨线程恢复 trace）。

---

## 18.3 第 4~5 站：WebFilter 链 —— 以及一个大坑（重名）

### 这层是干什么的

有些**横切逻辑**必须在"**知道走哪条路由之前**"就生效 —— 比如观测、session、权重计算。它们对所有请求生效、与路由无关，这就是 **WebFilter**（WebFlux 世界的"全局过滤器"）。

### 管线是在哪拼的：`WebHttpHandlerBuilder.build()`（三行看懂洋葱）

```java
// spring-web：WebHttpHandlerBuilder.build()
public HttpHandler build() {
    WebHandler decorated = new FilteringWebHandler(this.webHandler, this.filters);   // ① 串 WebFilter 链
    decorated = new ExceptionHandlingWebHandler(decorated, this.exceptionHandlers);  // ② 包异常兜底
    HttpWebHandlerAdapter adapted = new HttpWebHandlerAdapter(decorated);            // ③ 包最外层（第 3 站那层）
    ...
    return (this.httpHandlerDecorator != null ? this.httpHandlerDecorator.apply(adapted) : adapted);
}
```

这三行就是一个"从里往外"的洋葱：

```
HttpWebHandlerAdapter              ← 最外层（离 Netty 最近）：exchange 出生 + 全链收尾
  └─ ExceptionHandlingWebHandler      ← 兜住所有内层的异常
       └─ FilteringWebHandler(web)     ← 跑 WebFilter 链
            └─ WebFilter₁ → WebFilter₂ → … → DispatcherHandler   ← 链尾！
```

**为什么是这个顺序**：异常兜底要能兜住**所有**下游（含 WebFilter 和 DispatcherHandler）→ 放外面；exchange 必须先出生才能传给里面每个人 → 放最外面。

它从哪来（Boot）：`WebHttpHandlerBuilder.applicationContext(ctx)` 会**按类型自动收集**上下文里的 `WebFilter` / `WebExceptionHandler` bean，`this.webHandler` = **`webHandler` bean**（实测 = `DispatcherHandler`）—— **这就是"网关一个 bean 都不用改这里"的原因**：你只要定义 WebFilter bean，它自动进链。

### WebFilter 链本体（代码 + 逐行）

```java
// ⚠️ spring-web 的！org.springframework.web.server.handler.FilteringWebHandler（记包名）
public class FilteringWebHandler extends WebHandlerDecorator {
    private final DefaultWebFilterChain chain;

    public FilteringWebHandler(WebHandler handler, List<WebFilter> filters) {
        super(handler);                                          // handler = 链尾（DispatcherHandler）
        this.chain = new DefaultWebFilterChain(handler, filters);
    }
    @Override
    public Mono<Void> handle(ServerWebExchange exchange) {
        return this.chain.filter(exchange);                      // 开跑
    }
}
// DefaultWebFilterChain.filter
public Mono<Void> filter(ServerWebExchange exchange) {
    return Mono.defer(() ->                                  // 惰性：订阅才执行
            this.currentFilter != null && this.chain != null ?
                    invokeFilter(this.currentFilter, this.chain, exchange) :   // 当前过滤器拿"下一棒"
                    this.handler.handle(exchange));                            // 链尾 → DispatcherHandler
}
```

每个 WebFilter 收到的是"**剩余链**"，自己决定**何时**调 `chain.filter(exchange)` —— 洋葱；链走完最后交给**链尾 `DispatcherHandler`**。这个模式和 D 组的 `DefaultGatewayFilterChain`（17.5.2）**一模一样**。

**怎么把自定义 WebFilter 接进管线？**（一个 bean 就行，框架零改动）

Boot 构建管线时**按类型收集 + 按 order 排序**：

```java
// WebHttpHandlerBuilder.applicationContext(context)
List<WebFilter> webFilters = context
        .getBeanProvider(WebFilter.class)
        .orderedStream()          // ← Ordered / @Order 决定在 WebFilter 链里的位置
        .toList();
builder.filters(filters -> filters.addAll(webFilters));
```

```java
// 自定义示例：路由匹配之前的横切逻辑
@Bean
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public WebFilter myWebFilter() {
    return (exchange, chain) -> {
        exchange.getAttributes().put("my.traceId", UUID.randomUUID().toString());  // 前置
        return chain.filter(exchange);      // 放行（想拦截就不调 chain，自己写响应）
    };
}
```

验证：① `/actuator/beans` 能看到这个 bean；② 断点 `DefaultWebFilterChain` 构造处看 `allFilters` 列表。
（进阶：注册 `WebHttpHandlerBuilderCustomizer` bean 可在构建时增删 filters / exceptionHandlers。）

### ⚠️ 大坑：两个 `FilteringWebHandler`

| | **spring-web 的** | **gateway 的** |
|---|---|---|
| 全名 | `org.springframework.web.server.handler.FilteringWebHandler` | `org.springframework.cloud.gateway.handler.FilteringWebHandler` |
| 跑什么 | **WebFilter 链**（所有请求、与路由无关） | **网关过滤器链**（匹配路由后：GlobalFilter + GatewayFilter） |
| 何时跑 | `DispatcherHandler` **之前**（第 5 站） | `DispatcherHandler` **之后**（第 8 站，作为 handler 被调） |
| demo 实测 | 包着 `weightCalculatorWebFilter` | 包着 12 个全局过滤器 + 33 个工厂编出的路由过滤器 |

> 面试时永远**说全名（带包名）**，并把"跑 WebFilter vs 跑网关过滤器"一句话对比出来。

### `weightCalculatorWebFilter`：它到底在算什么"权重"？

`weightCalculatorWebFilter`（`WeightCalculatorWebFilter`；原始 demo 唯一的 WebFilter，加 L18 实验后共 **2 个**）服务的是 **`Weight` 谓词** —— **按权重分流（灰度 / AB / 金丝雀发布）**，**不是**负载均衡的实例权重（那是 SCLB 的事，见 L16）。它是个"**路由级抽签器**"。

配置长这样（两条路由同组 8:2 分流）：

```yaml
- id: gray-v2
  uri: http://v2.example
  predicates: [ Path=/api/**, "Weight=group1, 8" ]   # 80% 流量
- id: gray-v1
  uri: http://v1.example
  predicates: [ Path=/api/**, "Weight=group1, 2" ]   # 20% 流量
```

**URI 语义（先把这点钉死）**：路由的 `uri` = **下游服务地址**；客户端请求的是 `http://网关:8080/api/xxx`。
合并规则（`RouteToRequestUrlFilter` 源码）：**请求的 path/query 保留** + **路由 uri 的 scheme/host/port 换上**：

```java
URI mergedUrl = UriComponentsBuilder.fromUri(exchange.getRequest().getURI())  // 请求的 path（/api/xxx）
        .scheme(routeUri.getScheme())
        .host(routeUri.getHost())
        .port(routeUri.getPort())
        .build(encoded).toUri();          // → http://svc-v2:8080/api/xxx
```

→ **80% 打到 `http://svc-v2:8080/...`、20% 打到 `http://svc-v1:8080/...`**（有 `StripPrefix` 时路径先被改写；路由 uri 自带的 path 不参与合并）。

**两个"权重"别混（demo 实验最容易误解的点）**

- **本课的 `Weight` 谓词 = 路由层**：决定请求走**哪条路由**；生产里两条路由的 `uri` 指向**不同的服务/版本**（灰度就是这么切的）
- **SCLB 的实例权重 = 实例层**：`lb://service` 里选**哪个实例**（L16 的 Gray/Zone、Nacos 实例权重）

| | `Weight` 谓词（本课） | LB 实例权重（L16） |
|---|---|---|
| 层级 | **路由**（走哪条 Route） | **实例**（同服务选哪个实例） |
| 执行者 | `WeightCalculatorWebFilter` + `Weight` 谓词 | `ReactiveLoadBalancerClientFilter` + LoadBalancer |
| 配置 | `Weight=group, n`（路由谓词里） | `lb://service` + LB 配置 / Nacos 实例权重 |
| 场景 | 灰度 / AB（版本切换） | 加权轮询 / 压测 / 同区优先 |

二者可以叠加：`uri: lb://service` + `Weight` 谓词 → **先选路由，再选实例**。

> ⚠️ 实验里两条路由都写 `http://localhost:8081` 只是为了**方便统计**（响应头 `X-Gray` 数比例）；生产里应换成**不同 uri**（如 `http://svc-v2:8080` / `http://svc-v1:8080`）。

五个步骤（为什么它**必须**是 WebFilter，看第 3 步）：

| # | 时机 | 做什么 |
|---|---|---|
| 1 | 编译期 / 删改 | 它监听**四种事件**（`SmartApplicationListener`）：yml 编译的 `PredicateArgsEvent`、Java DSL 的 `WeightDefinedEvent`、`RouteDeletedEvent`、`RefreshRoutesEvent` → 记账"**组 → {routeId → weight}**"（存进滤波器字段 `groupWeights`，值类型是内部类 `GroupWeightConfig`） |
| 2 | 记账时 | weight 归一化成累计区间 `ranges` + 区间→routeId 的 `rangeIndexes` |
| 3 | **请求时（匹配前）** | `filter()`：每组抽一次随机数 `r = ThreadLocalRandom.current().nextDouble()`，落在哪个区间 → **把本组选中的 routeId 写进 `WEIGHT_ATTR`**（exchange 属性；字面值 `org.springframework.cloud.gateway.support.ServerWebExchangeUtils.routeWeight`） |
| 4 | 匹配时 | `Weight` 谓词的 `test()` 只"**对答案**"：`routeId.equals(weights.get(group))`（routeId 来自 `GATEWAY_PREDICATE_ROUTE_ATTR` —— C 组正在测哪条） |
| 5 | 结果 | 同组里只有"被抽中"的那条路由谓词为 true → **分流完成** |

**关键理解**：**"选出 routeId"必须在路由匹配之前完成** —— `Weight` 谓词只做"比较"（拿"当前正在测试的路由 id"和 exchange 里记录的 id 比），不能"现场选"；所以"选"只能由 WebFilter 提前做（order = `WEIGHT_CALC_FILTER_ORDER` = **10001**）。

**机制（精确版）：请求时选出 routeId 写进 exchange；谓词拿"当前路由 id"与它比较**

`WeightCalculatorWebFilter` 做两件事：

**① 记账**（编译期 / DSL，事件驱动）——把 `WeightConfig` 记成"组名单"：

```java
// addWeightConfig(WeightConfig weightConfig)   ← 参数：每路由的 WeightConfig（只有 group/routeId/weight）
GroupWeightConfig config = ...;                 // ← 局部变量 config 是【组级】的 GroupWeightConfig（不是 WeightConfig！）
config.weights.put(weightConfig.getRouteId(), weightConfig.getWeight());   // 组名单 weights = {gray-v2: 8, gray-v1: 2}
// …归一化 + 切区间…
groupWeights.put(group, config);                // ← 最后存进【滤波器字段】groupWeights
```

**三个名字的归属（别混）**：

| 名字 | 是什么 | 定义在哪 | 关键字段 |
|---|---|---|---|
| `WeightConfig` | **每路由**的绑定对象（`Weight=gray, 8` 一行 → 一个） | `org...support.WeightConfig` | `group` / `routeId` / `weight` |
| `GroupWeightConfig` | **每组**的聚合（一个组一份） | `WeightCalculatorWebFilter` 的**内部类** | `weights`（组名单）/ `normalizedWeights` / `rangeIndexes` / `ranges` |
| `groupWeights` | **滤波器实例的字段**：`Map<组名, GroupWeightConfig>` | `WeightCalculatorWebFilter` 的**字段** | —— |

**② 请求时选 routeId，写进 exchange 属性**：

```java
// filter()：
Map<String, String> weights = getWeights(exchange);        // 从 exchange 取/建 Map（key = WEIGHT_ATTR）
...
String routeId = config.rangeIndexes.get(i);               // 随机数落段 → 该段对应的 routeId
weights.put(group, routeId);                               // ← 就是这里：把 routeId 放进 exchange
```

| 问题 | 答案 |
|---|---|
| 放的是 route 的 id 吗？ | ✅ 是 **routeId 字符串**（不是 `Route` 对象、不是 weight 数值） |
| 放到哪？ | exchange 属性 **`WEIGHT_ATTR`**（字面值 `...ServerWebExchangeUtils.routeWeight`） |
| 放成什么结构？ | `Map<String,String>`：`{"gray" → "gray-v2"}`（组名 → 被选中的 routeId） |

**③ 匹配时比较**（`WeightRoutePredicateFactory`）：

```java
Map<String, String> weights = exchange.getAttributeOrDefault(WEIGHT_ATTR, Collections.emptyMap());
String routeId = exchange.getAttribute(GATEWAY_PREDICATE_ROUTE_ATTR);   // ← 当前正在被测试的路由 id（C 组逐条测试时写入）
String group = config.getGroup();
if (weights.containsKey(group)) {
    return routeId.equals(weights.get(group));      // 当前路由 == exchange 里记录的 routeId？
}
return false;
```

**所以"为什么匹配前就能做"**：它只算了"**这次选哪个 routeId**"（依据是启动时就备好的组名单 + 一个随机数，都与请求无关），**没有做任何路由匹配**；匹配照常在 C 组进行，谓词只是多加一个条件：`当前测试的 routeId == exchange 里记录的 routeId`。

**`WeightConfig` 是什么？** —— `Weight=gray, 8` 这行配置**绑定后的对象**（17.3.5 "查工厂 + bind + apply" 管道的产物）：

```java
public class WeightConfig {                 // org.springframework.cloud.gateway.support
    public static final String CONFIG_PREFIX = "weight";
    @NotEmpty private String group;         // 例：gray
    private String routeId;                 // 例：gray-v2
    @Min(0) private int weight;             // 例：8
}
```

两个来源（都汇到 `addWeightConfig`）：

- **yml**：路由编译 → `PredicateArgsEvent(routeId, args)` → 滤波器 `handle()`：
  ```java
  WeightConfig config = new WeightConfig(event.getRouteId());   // routeId 来自事件
  configurationService.with(config).name(WeightConfig.CONFIG_PREFIX).normalizedProperties(args).bind();  // group/weight 绑进去
  addWeightConfig(config);
  ```
- **Java DSL**：`PredicateSpec.weight(group, weight)`：
  ```java
  applyAsync(c -> c.setGroup(group).setRouteId(routeBuilder.getId()).setWeight(weight))
  // → beforeApply(config) → 发 WeightDefinedEvent → addWeightConfig
  ```

**为什么不能让谓词"现场选"（反例）**：同组两条路由的谓词是**各自独立被调用**的 ——
如果各选各的：gray-v2 独立选一次（80% 选中）、gray-v1 再独立选一次（20% 选中）→ **可能两个都选中（~16%）、也可能两个都不选中（~16%）**，8:2 就崩了。
正确语义：**一个请求、同组只选一次、结果共享** → 只能提前选好放进 exchange。

**全字段数据流**：

```
yml: Weight=gray, 8（路由 id = gray-v2）
  → WeightConfig{group=gray, routeId=gray-v2, weight=8}
  → 组名单 = WeightCalculatorWebFilter.groupWeights["gray"].weights = {gray-v2: 8, gray-v1: 2}
  → 归一化 normalizedWeights = {gray-v2: 0.8, gray-v1: 0.2}
  → 区间 ranges=[0.0, 0.8, 1.0] + rangeIndexes={0: gray-v2, 1: gray-v1}

请求时：r=0.37 → 落 [0.0, 0.8) → exchange[WEIGHT_ATTR] = {"gray": "gray-v2"}

匹配时：
  gray-v2 的 Weight 谓词："gray-v2"(当前路由) == "gray-v2"(exchange 记录的) → true
  gray-v1 的 Weight 谓词："gray-v1"(当前路由) == "gray-v2"(exchange 记录的) → false
```

**"归一化 + 区间"是什么意思？（8:2 为例）**

权重数字（8、2）只是"相对比例"，要能用"一个 0~1 的随机数"来选，需要两步换算：

1. **归一化**：每个权重 ÷ 总和 → 变成 0~1 的占比
   `8/10 = 0.8`（v2）、`2/10 = 0.2`（v1）—— 加起来正好 1.0
2. **切成区间**：把 0~1 这条线段按占比切段

```
0.0            0.8         1.0
 |──── v2 0.8 ────|── v1 0.2 ──|
   随机数落 [0.0, 0.8) → 选中 gray-v2
   随机数落 [0.8, 1.0) → 选中 gray-v1
```

3. **生成随机数**：`r = random(0,1)` 落在哪段就是哪条路由（r=0.37 → v2；r=0.85 → v1）

源码就是这个意思：

```java
// ① 归一化 + 切区间（addWeightConfig）
Double nomalizedWeight = weight / (double) weightsSum;   // 8/10 = 0.8
config.ranges.add(0.0);
for (...每一段...) config.ranges.add(previousRange + currentWeight);   // → [0.0, 0.8, 1.0]

// ② 选 routeId：随机数落在哪段（filter）
double r = randomFunction.apply(exchange);
for (int i = 0; i < ranges.size() - 1; i++) {
    if (r >= ranges.get(i) && r < ranges.get(i + 1)) {
        weights.put(group, config.rangeIndexes.get(i));   // 该段的 routeId
        break;
    }
}
```

（多条通用：5:3:2 → 归一化 0.5/0.3/0.2 → 区间 `[0.0, 0.5, 0.8, 1.0]`。）

（它分的是"**请求 → 组内哪条路由**"；uri 若是 `lb://...`，"选后端实例"是后面 LB 的事，与 Weight 无关。）

**demo 里为什么无害**：所有路由都没配 `Weight` 谓词 → `groupWeights` 为空 → `filter()` 循环空转，什么都不做。

### 实测（2026-09-29）：自定义 WebFilter + Weight 分流

新增两个实验件：`DemoWebFilter`（`demo-gateway/.../filter/DemoWebFilter.java`）+ yml 两条 `gray` 路由（8:2）。前置：provider(8081) 在跑。

**实验 1：自定义 WebFilter 的三件事**

```java
@Component
public class DemoWebFilter implements WebFilter, Ordered {
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        log.info("[DemoWebFilter] 匹配前生效: {}", path);                       // ② 所有请求可见
        if (path.contains("/web-filter-block")) {                              // ③ 短路
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();    // 不调 chain → 路由匹配不执行
        }
        exchange.getResponse().getHeaders().add("X-Demo-WebFilter", "hit");    // ① 匹配前跑过
        return chain.filter(exchange);
    }
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 100; }
}
```

| 测试 | 请求 | 结果 |
|---|---|---|
| ① 匹配前生效 | `GET /provider/hello` | `200` + 响应头 `X-Demo-WebFilter: hit-205` |
| ② 所有请求可见 | `GET /actuator/health` | `200` + 同样有 `X-Demo-WebFilter`（**连 Actuator 都过 WebFilter**） |
| ③ 短路 | `GET /web-filter-block` | **`403`**（日志："短路拦截……没走路由匹配"）—— 不调 `chain.filter` 就到此为止 |

日志佐证：`DemoWebFilter` 一共打 **406 条**（每个请求一条，含健康检查）。

**实验 2：Weight 分流（8:2）**

```yaml
- id: gray-v2
  uri: http://localhost:8081
  predicates: [ Path=/gray/**, "Weight=gray, 8" ]
  filters: [ StripPrefix=1, "AddResponseHeader=X-Gray, v2" ]
- id: gray-v1
  uri: http://localhost:8081
  predicates: [ Path=/gray/**, "Weight=gray, 2" ]
  filters: [ StripPrefix=1, "AddResponseHeader=X-Gray, v1" ]
```

（实验简化：两条 uri 都指 8081 —— 靠 `X-Gray` 响应头数比例；**生产里应换成不同 uri**，见前面《两个"权重"别混》。）

**连打 200 次 `GET /gray/hello`**（按响应头统计）：

```
163  X-Gray: v2   （81.5%）
 37  X-Gray: v1   （18.5%）     ← 约等于配置的 8:2 ✅
```

日志对照：`Route matched: gray-v2` / `gray-v1` 的条数同样按比例分布 → **抽签在匹配前完成、谓词只对答案**（就是上面第 3~4 步）。

（可选进阶：把 `gray-v1` 的 `uri` 改成 `http://localhost:8082`（不存在）→ 会看到约 **20% 请求 500 / 80% 200** —— 直观证明"分流真的把请求送去了不同目的地"。）

**生产里通常不止一个 WebFilter**（demo 干净 → 只剩 SCG 自带的权重那个）：

| 谁 | WebFilter | 说明 |
|---|---|---|
| **Spring Security** | `WebFilterChainProxy` | **整个安全过滤链就是一个 WebFilter**（生产最常见；Boot 的 `ReactiveManagementWebSecurityAutoConfiguration` 就是 `@ConditionalOnClass(WebFilterChainProxy.class)`） |
| Boot Actuator | `HttpExchangesWebFilter` | `/actuator/httpexchanges` 的请求记录器（`HttpExchangesAutoConfiguration`，开启记录时注册） |
| SCG 自带 | `WeightCalculatorWebFilter` | **SCG 唯一一个**（路由相关的扩展都留给了 GlobalFilter） |
| （旧版）Spring Framework | `ServerHttpObservationFilter` | 6.1 弃用 —— 观测已内置进 `HttpWebHandlerAdapter`（所以 demo 里没有它） |

**什么时候该用 WebFilter（vs GlobalFilter）**：

| 需求 | 用 WebFilter？ | 说明 / 更常见的选择 |
|---|---|---|
| 认证鉴权 | ✅（安全框架就这么干） | 直接用 Spring Security（别手写） |
| **路由匹配前**就要拦截 / 改写（黑名单、Host 重写） | ✅ 语义正确 | GlobalFilter 在链首 order 也能干，但"没匹配到路由"时无能为力 |
| 按路由 / 按 order 参与过滤链 | ❌（WebFilter 里**看不到 Route**） | GlobalFilter / GatewayFilter（网关正统扩展点） |
| 访问日志 / 审计 | 可以 | 更常见是 GlobalFilter（能带 routeId）或观测 |
| 请求 ID / trace 传播 | 可以 | 观测 / tracing 桥已内置，一般不用自己写 |

> ⚠️ **坑**：WebFilter **看得到所有 HTTP 请求**（包括 `/actuator/**`）—— 因为它跑在 `DispatcherHandler` 之前；自定义时要自己判路径。GlobalFilter 则只在"匹配到路由"后执行。

> **WebFilter vs GlobalFilter 的取舍（一句话）**：要"知道路由之前就生效"→ WebFilter；要"按路由 / 按 order 参与过滤链"→ GlobalFilter。

---

## 18.4 第 6 站：`DispatcherHandler` —— 三份名单，三次"逐个问"

### 先讲它是谁

前 5 站都是"翻译、装饰"，到这一站才**开始真正干活**：它是 WebFlux 的**总调度**（`webHandler` bean，实测 = `DispatcherHandler`）。它的工作只有一个问句：**"这个请求归谁管？"**

### 三个策略名单（源码 + 要点）

```java
public class DispatcherHandler implements WebHandler, ApplicationContextAware {

    @Nullable
    private List<HandlerMapping> handlerMappings;         // ① 谁管这个请求？（demo 实测 8 个）

    @Nullable
    private List<HandlerAdapter> handlerAdapters;         // ② 拿到 handler 后，谁会调它？

    @Nullable
    private List<HandlerResultHandler> resultHandlers;    // ③ 处理结果怎么写回响应？
```

三个要点：**为什么是 List** —— 每个问题对应一个策略接口，所有实现的 bean 都收进来（网关就是靠"按类型收集"插进来的）；**为什么排序** —— order 决定被问的先后；**为什么 `@Nullable`** —— 官方 javadoc：`setApplicationContext` 之前可能是 null（所以 `handle()` 开头有守卫①）。填充时机是**启动期**：

```java
@Override
public void setApplicationContext(ApplicationContext applicationContext) {
    initStrategies(applicationContext);   // ← 三份名单在这一刻定型
}
// initStrategies：按类型收 + 排序（关键三行）
Map<String, HandlerMapping> mappingBeans = BeanFactoryUtils.beansOfTypeIncludingAncestors(
        context, HandlerMapping.class, true, false);
AnnotationAwareOrderComparator.sort(mappings);            // ★ order 决定"先问谁"
this.handlerMappings = Collections.unmodifiableList(mappings);
```

### `handle()`：两个"守卫" + 6 行主流程

```java
// DispatcherHandler.handle()
if (this.handlerMappings == null) {                              // 守卫①：还没初始化（正常启动不会发生）
    return createNotFoundError();
}
if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {       // 守卫②：CORS 预检单独走（L07）
    return handlePreFlight(exchange);
}
return Flux.fromIterable(this.handlerMappings)
        .concatMap(mapping -> mapping.getHandler(exchange))       // 逐个问 mapping："你能为这请求给出 handler 吗？"（非空 = "归我管"）
        .next()                                                   // 第一个"非空应答"的赢 → 取消上游
        .switchIfEmpty(createNotFoundError())                     // 8 个全没人管 → 404
        .onErrorResume(ex -> handleResultMono(exchange, Mono.error(ex)))  // 出错 → 也交给 resultHandlers 统一出响应
        .flatMap(handler -> handleRequestWith(exchange, handler));        // 有 handler → 进入"调"的阶段
```

用 `GET /provider/hello` 把"逐个问"走一遍（**真实顺序 = order 升序**，值见 18.1 附②）：

| 被问顺序 | mapping（order） | 应答 |
|---|---|---|
| 1~3 | 三个 Actuator mapping（**-100**） | 空（不是它们的路径） |
| 4 | `routerFunctionMapping`（**-1**） | 空（没有函数式路由） |
| 5 | `requestMappingHandlerMapping`（**0**） | 空（没有 `@Controller`） |
| 6 | **`routePredicateHandlerMapping`（1）** | **命中！返回 `FilteringWebHandler`** |
| —— | （`.next()` 已取消上游） | `resourceHandlerMapping`（MAX-1）**没轮到** |

**三层"找"别混（对齐 MVC 的心智模型）** —— `getHandler` 返回的确实是 **handler**（你没理解错），只是"逐个问"问的是 **HandlerMapping**，不是 Route：

```
第 1 层：DispatcherHandler 遍历 handlerMappings（8 个）
          问每个 mapping："你能为这请求给出 handler 吗？"
            空    → "不是我的"，继续问下一个
            非空  → 应答，停止（.next()）
第 2 层：轮到 RoutePredicateHandlerMapping.getHandler() —— 这里才做【Route 匹配】
          lookupRoute：逐条谓词 → 命中 Route → Route 放进 exchange（数据）→ 返回 FilteringWebHandler（handler）
第 3 层：DispatcherHandler 拿到 handler 后，遍历 handlerAdapters
          问每个 adapter："你 supports 这个 handler 吗？" → handle()
```

对照 MVC（结构完全同形）：

| 步骤 | MVC（DispatcherServlet） | WebFlux（DispatcherHandler） |
|---|---|---|
| ① 找谁来处理 | `for (m : handlerMappings) { chain = m.getHandler(request); if (chain != null) return chain; }` | `Flux.fromIterable(handlerMappings).concatMap(m -> m.getHandler(exchange)).next()` |
| mapping 返回 | `HandlerExecutionChain`（内含 `HandlerMethod`） | `Object handler`（`HandlerMethod` / `HandlerFunction` / **`FilteringWebHandler`**…） |
| ② 谁调 handler | `for (a : handlerAdapters) if (a.supports(handler)) a.handle(...)` | 同（`supports(handler)` → `handle(exchange, handler)`） |
| 网关特有点 | —— | **Route 不是 handler**：它在第 2 层（mapping 内部）被匹配后放进 exchange；返回的 handler 永远是 `FilteringWebHandler` 单例 |

#### `lookupRoute` 核心：匹配阶段只用【谓词】，不碰【过滤器】

> ⚠️ **别和 B 组的 `lookup` 混**：`RouteDefinitionRouteLocator.lookup(routeDefinition, predicateDefinition)` 是**编译期查工厂**；
> 这里的 `lookupRoute(exchange)` 是**运行期查路由**（每请求跑）。对照表见 L20 §20.1。

```java
protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
    return this.routeLocator.getRoutes()                        // ① 从 B 组（缓存）拿全部编译好的 Route
        .filterWhen(route -> {
            // add the current route we are testing
            exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, route.getId());  // ② 记账"正在测哪条"（Weight 等谓词要读它）
            try {
                return route.getPredicate().apply(exchange);    // ③ 只用【谓词】：这条 Route 匹配当前请求吗？
            } catch (Exception e) {
                logger.error("Error applying predicate for route: " + route.getId(), e);
            }
            return Mono.just(false);
        })
        .next()                                                  // ④ 第一条匹配的赢（取消上游）
        .map(route -> { ...; validateRoute(route, exchange); return route; });
}
```

匹配成功后（`getHandlerInternal` 里）：

```java
exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);   // Route（含 filters）当【数据】放进 exchange
return webHandler;                                     // 返回【handler】= FilteringWebHandler 单例
```

**为什么"Route 里既有谓词又有过滤器"却"匹配后还不跑过滤器"？—— 两阶段各用一半**：

| 阶段 | 谁 | 用 Route 的什么 | 产物 |
|---|---|---|---|
| **匹配**（C 组） | `lookupRoute` | **只用 `route.getPredicate()`** | 命中 Route → 放进 exchange |
| **执行**（D 组） | `FilteringWebHandler.handle` | **只用 `route.getFilters()`** | 合并排序 → 链式执行 |

（编译后的 Route 形如：`Route{id, uri, order, predicate=AsyncPredicate, gatewayFilters=[...]}` —— 两样都在里面，但被**两个阶段分开使用**；route.uri 则留给更后面的 `RouteToRequestUrlFilter`。）

**追问：返回的 `FilteringWebHandler` 里"带"着那条 Route 吗？它是单例还是每请求一个？**

**① 不带 Route。** 它的字段只有这些（都与"当前请求"无关）：

```java
public class FilteringWebHandler implements WebHandler, ApplicationListener<RefreshRoutesEvent> {
    private final List<GatewayFilter> globalFilters;                             // 12 个全局过滤器（共享）
    private final ConcurrentHashMap<Route, List<GatewayFilter>> routeFilterMap;  // per-route 缓存（默认关闭，空）
    private final boolean routeFilterCacheEnabled;
    // ← 没有"当前 Route"这种字段！
}
```

Route 是**每请求从 exchange 取**的：

```java
public Mono<Void> handle(ServerWebExchange exchange) {
    Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);   // ← 从 exchange 取（不是从自己字段）
    ...
}
```

**② 是单例（全应用就一个实例）。** Bean 启动时只 `new` 一次；实测证据：日志里 **4 个请求的 hash 完全相同**：

```
[xxxxx-x] Mapped to org.springframework.cloud.gateway.handler.FilteringWebHandler@3eae2a54
（uniq -c 输出：4 ...@3eae2a54）
```

**③ "每请求一个"的其实是这三样**（全在 handle 的调用路径上）：

| 每请求对象 | 在哪 | 什么时候创建 |
|---|---|---|
| `Route`（数据） | exchange 属性 `GATEWAY_ROUTE_ATTR` | C 组匹配后放入 |
| `combined` 过滤器列表 | `handle()` 局部变量 | 每请求 `[12 全局] + [route.getFilters()]` + 排序 |
| `DefaultGatewayFilterChain`（链） | `handle()` 里 `new` | 每请求拼链时 |

（唯一和 Route 沾边的字段是 `routeFilterMap` —— 那是"多条 Route 的编译缓存"，默认关闭；开了以后刷新时清空。）

### ② adapters / ③ resultHandlers（同一模式）

```java
// ② 谁 supports(handler) 谁负责调
for (HandlerAdapter adapter : this.handlerAdapters) {
    if (adapter.supports(handler)) {
        Mono<HandlerResult> resultMono = adapter.handle(exchange, handler);
        return handleResultMono(exchange, resultMono);
    }
}
// ③ 谁 supports(result) 谁写响应
for (HandlerResultHandler resultHandler : this.resultHandlers) {
    if (resultHandler.supports(handlerResult)) {
        return resultHandler.handleResult(exchange, handlerResult).checkpoint(description);
    }
}
```

> **面试口径**：`DispatcherHandler` = **三份按 order 排好的名单 + 三次"逐个问，第一个支持者胜"**。网关插进来的：名单①多了 `RoutePredicateHandlerMapping`；名单②里 `SimpleHandlerAdapter` 负责调它返回的 handler。

---

## 18.5 第 7~8 站：Gateway 的插入点

第 6 站问到网关后，剩下就是 17.5 讲过的故事，这里只对齐"站位"：

```
第 6 站：RoutePredicateHandlerMapping.getHandler()
           └─ 匹配路由（逐条谓词）→ Route 放 exchange → return FilteringWebHandler（单例）
第 7 站：DispatcherHandler 遍历 adapters
           └─ SimpleHandlerAdapter.supports(WebHandler)=true → webHandler.handle(exchange)
第 8 站：gateway FilteringWebHandler.handle()
           └─ [12 全局] + [route.getFilters()] → 排序 → 链式执行 → NettyRoutingFilter 转发
```

详细机制（C 组怎么逐条跑谓词、D 组怎么合并排序、exchange 里放什么）→ **17.5.0 / 17.5.1 / 17.5.2**，本课不重复。

**第 8 站的详细讲解在哪（分工地图 —— 不是漏讲，是拆课）**：

| 内容 | 去哪看 | 状态 |
|---|---|---|
| `FilteringWebHandler` 合并 / 排序 / 拼链（代码级首过） | **L17 文档 17.5.2** | ✅ 已讲 |
| 合并排序后的 **16 条链**（实测逐条） | 本课 **18.7 ④** | ✅ 已讲 |
| 拼链专课：`getCombinedFilters` / `GatewayFilterAdapter` / `OrderedGatewayFilter` / `routeFilterMap` | **L25 How the Filter Chain Is Assembled** | ⏳ 规划中 |
| 跑链专课：`DefaultGatewayFilterChain` 的 index 递归（洋葱模型） | **L26 Chain Execution** | ⏳ 规划中 |
| 转发专课：`RouteToRequestUrlFilter` → `ReactiveLoadBalancerClientFilter` → `NettyRoutingFilter` → `NettyWriteResponseFilter` | **L27 The Four Forwarding Steps** | ⏳ 规划中 |

> 本节只要记住：**Gateway 没有改 WebFlux 管线一根毫毛** —— 只是往两类 bean 名单里各放了 1 个（mapping 1 个 + handler 1 个）。

### 追问：`HttpWebHandlerAdapter.getDelegate()` 到底是谁？是 gateway 的 `FilteringWebHandler` 吗？

**不是。** delegate 链（启动时装配出来的"套娃"）是：

```
HttpWebHandlerAdapter                              ← 第 3 站（exchange 出生）
  └─ delegate = ExceptionHandlingWebHandler            （第 4 站：兜异常）
       └─ delegate = FilteringWebHandler(spring-web)    ← ⚠️ 同名不同类！跑 WebFilter 链
            └─ 链尾 = DispatcherHandler                 （第 6 站：总调度）
                 ├─ 名单① handlerMappings ── 含 RoutePredicateHandlerMapping（C 组）
                 │        └─ 它的 webHandler 字段 = FilteringWebHandler(gateway)  ← D 组
                 └─ 名单② handlerAdapters ── 含 SimpleHandlerAdapter
```

**调用路径（带类名+方法签名版 —— 防止"图看着怪"）**：

| # | 类#方法 | 关键代码（一行） |
|---|---|---|
| 1 | `HttpWebHandlerAdapter#handle(ServerHttpRequest, ServerHttpResponse)` | `return getDelegate().handle(exchange)…` |
| 2 | `WebHandlerDecorator#handle`（`ExceptionHandlingWebHandler` 继承它） | `return this.delegate.handle(exchange);` ← 装饰器就这一句 |
| 3 | `FilteringWebHandler`(spring-web)`#handle` | `return this.chain.filter(exchange);` |
| 4 | `DefaultWebFilterChain#filter` | 链走完：`this.handler.handle(exchange)` → `DispatcherHandler` |
| 5 | `DispatcherHandler#handle` | `mapping.getHandler(exchange)` |
| 6 | `AbstractHandlerMapping#getHandler` → 回调 `RoutePredicateHandlerMapping#getHandlerInternal` | `return webHandler;`（C 组：Route 进 exchange） |
| 7 | `DispatcherHandler#handleRequestWith` | `adapter.handle(exchange, handler)` |
| 8 | `SimpleHandlerAdapter#handle` | `webHandler.handle(exchange)` |
| 9 | `FilteringWebHandler`(gateway)`#handle` | 合并排序 → `new DefaultGatewayFilterChain(combined).filter(exchange)`（D 组） |

> **每一层都是"同接口（`WebHandler`）不同实现"的装饰器；`getDelegate()` = "我被包着的那一层"**。

### 四组 × 生命周期（"谁什么时候干活"）

| 组 | 什么时候干活 | 每请求吗 |
|---|---|---|
| A 来源 | bean **启动时创建**；数据可运行时被写（Actuator）；**被 B 拉取** | ❌ |
| B 模型 | **启动编译一次 + 每次 refresh 重编译**（产出 Route 缓存） | ❌ |
| C 匹配 | **每请求**：逐条谓词、Route 进 exchange、返回 handler | ✅ |
| D 执行 | **每请求**：合并排序拼链 + 跑链（转发 / 回程） | ✅ |

> 更准确的记法：**A/B 是"启动 + 刷新期"的编译活；C/D 是"每请求"的运行活**。`HttpWebHandlerAdapter` 是"最外层收件台"，**不是 D 组干活的地方**（D 组在第 8 站）。

---

## 18.6 `NO_OP_HANDLER`：用一个 CORS 预检把它讲透

浏览器发 `OPTIONS /provider/hello`（**CORS 预检**）—— 这个请求**会走到第 6 站**，但**永远到不了你的过滤器**：

```java
// spring-webflux：AbstractHandlerMapping
private static final WebHandler NO_OP_HANDLER = exchange -> Mono.empty();

@Override
public Mono<Object> getHandler(ServerWebExchange exchange) {
    return getHandlerInternal(exchange).map(handler -> {
        ...
        if (hasCorsConfigurationSource(handler) || CorsUtils.isPreFlightRequest(request)) {
            ...
            if (!this.corsProcessor.process(config, exchange) || CorsUtils.isPreFlightRequest(request)) {
                return NO_OP_HANDLER;      // ★ 预检 / CORS 被拒：返回"空操作 handler"
            }
        }
        return handler;
    });
}
```

把它想成一张"假条"：`NO_OP_HANDLER` = 一个什么都不做的 handler（`exchange -> Mono.empty()`）。第 6 站发现是 CORS 预检时，`corsProcessor.process(...)` **把该写的响应头写完**，然后返回它 —— 含义是"**这请求已经处理完了，谁都别再管**"：DispatcherHandler 看它非空 → **停止遍历**；`SimpleHandlerAdapter` 认领 → 调 `handle()` → 什么都不做；响应其实早就写好了。结果：预检在**第 6 站**就被消化，**网关完全不知道它存在过**（L07 的"CORS 预检不走路由"）。

| 返回 | 含义 | 后果 |
|---|---|---|
| `Mono.empty()`（`getHandlerInternal` 为空） | **"不是我管的"** | DispatcherHandler **继续问下一个 mapping**；8 个都不管 → **404** |
| **`NO_OP_HANDLER`**（非空！） | **"处理完了，到此为止"** | 停止遍历 → adapter 调它 → 什么都不做（响应已写完） |

> **"返回空 = 继续问；返回 NO_OP = 到此为止。"**

---

## 18.7 怎么自己验证

### ① 三份名单（实测：mappings 8 个）

```bash
GW=http://localhost:8080/actuator
curl --noproxy '*' -s $GW/beans | python3 -c "
import json,sys
beans={}
for k,v in json.load(sys.stdin).get('contexts',{}).items(): beans.update(v.get('beans',{}))
for name,b in sorted(beans.items()):
    t=b.get('type','')
    if t.split('.')[-1].endswith(('HandlerMapping','HandlerAdapter','HandlerResultHandler')):
        print(f'  {name:45s} → {t.split(\".\")[-1]}')"
```

### ② DEBUG 日志（已在 `application.yml` 开启）

```yaml
logging:
  level:
    org.springframework.web: DEBUG                      # AbstractHandlerMapping: "Mapped to ..."
    org.springframework.cloud.gateway: DEBUG           # C 组: "Mapping [...] to Route{...}"
```

### ③ 断点调试地图（8 站，从外到内）

行号口径：**gateway 类 = 4.1.5 源码**（与项目一致，可直接用）；**spring-web / webflux = 本机源码树 6.2.9**，项目实际是 Boot 3.2.x（Spring FW 6.1.x）—— 行号可能 ±几行，**按方法名断**。

推荐打法：**先只打"最小三连"**（跑通一个请求，再按需加断点 —— 12 个全打会一个请求停十几次）：
① `RoutePredicateHandlerMapping#getHandlerInternal` @ gateway:79（进网关）→ ② gateway `FilteringWebHandler#handle` @ gateway:104（看 route）→ ③ `NettyRoutingFilter#filter` @ gateway:111（真转发）。

| 站 | 断点（类#方法 @ 行） | 停一次看什么 | 条件建议（过滤噪音） |
|---|---|---|---|
| 2 | `ReactorHttpHandlerAdapter#apply` @ spring-web:55 | Netty 的原始请求对象进来（reactor-netty 类型） | `reactorRequest.uri().contains("/provider")` |
| 3 | `HttpWebHandlerAdapter#handle` @ spring-web:274（关注 **287 行 `createExchange` 之后**）；`createExchange` @ 306 | **watch `exchange`**：属性袋此刻只有观测上下文 | `request.getURI().getPath().startsWith("/provider")` |
| 5 | `DefaultWebFilterChain#filter` @ spring-web:105；`WeightCalculatorWebFilter#filter` @ gateway:254 | WebFilter 链的每一跳（demo 里只有权重这一个） | —— |
| 6 | `DispatcherHandler#handle` @ webflux:142 | **watch `this.handlerMappings`**：三份名单之①（8 个，已按 order 排序） | —— |
| 6* | **Logpoint**：`AbstractHandlerMapping#getHandler` @ webflux:186，消息 = `getClass().getSimpleName()` | **完整录下"逐个问 8 个 mapping"的顺序**（不停下来，看 Console） | —— |
| 6.5 | `RoutePredicateHandlerMapping#getHandlerInternal` @ gateway:79；`lookupRoute` @ gateway:130（可在 `.filterWhen` lambda 打 logpoint `route.getId()`）；`return webHandler` @ gateway:99 | 命中那一刻：watch `exchange.getAttributes().get("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRoute")`（`GATEWAY_ROUTE_ATTR` 的字面值 = 类全名 + `.gatewayRoute`） | `exchange.getRequest().getPath().value().startsWith("/provider")` |
| 7 | `SimpleHandlerAdapter#handle` @ webflux:43；`DispatcherHandler#handleRequestWith` @ webflux:200（watch `this.handlerAdapters`） | 谁认领了 gateway 的 handler（`handler.getClass()`） | —— |
| 8 | gateway `FilteringWebHandler#handle` @ gateway:104（watch `route`）；`getAllFilters` @ gateway:124（watch `combined`）；`DefaultGatewayFilterChain#filter` @ gateway:153 | Route 从 exchange 取出 → 合并排序 → 逐跳执行 | —— |
| 8* | `RouteToRequestUrlFilter#filter` @ gateway:63；`NettyRoutingFilter#filter` @ gateway:111（**真转发**）；`NettyWriteResponseFilter#filter` @ gateway:65 | 转发与回程 | —— |
| 🥚 | `AbstractHandlerMapping#getHandler` @ webflux:186，**条件**：`org.springframework.web.cors.reactive.CorsUtils.isPreFlightRequest(exchange.getRequest())` | 复现 **`NO_OP_HANDLER` 短路**（18.6，L07） | 发带 `Origin` + `Access-Control-Request-Method` 的 OPTIONS |

起 Debug 两种方式：

```bash
# ① IDEA：Debug 启动 DemoGatewayApplication（需要 sources：Settings → Maven → 勾 "Download Sources"）
# ② jar + 远程调试：
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar demo-gateway/target/demo-gateway-1.0-SNAPSHOT.jar
#    IDEA 里用 "Remote JVM Debug" 连 5005
```

反应式调试提醒：断点打在"订阅时"执行的 lambda 上（一般能直接命中）；路径条件统一用 `/provider` 过滤噪音。

### ④ 实测日志样本与逐行讲解（2026-09-28）

**先看懂一行日志的"骨架"**：

```
2026-09-28T20:05:04.304+08:00 DEBUG 708723 --- [demo-gateway] [or-http-epoll-3] o.s.c.g.h.RoutePredicateHandlerMapping : Route matched: demo-provider
└─① 时间戳（带时区）          └② 级别 └③PID  └④ ---  └⑤ 应用名     └⑥ 线程名          └⑦ Logger（自动缩写）                    └⑧ 消息
```

| # | 字段 | 含义 / 本例说明 |
|---|---|---|
| ① | 时间戳 | 带时区（+08:00） |
| ② | 级别 | `DEBUG`（能看到它，全因配置里那两行） |
| ③ | PID | JVM 进程号（和 `pgrep` 对得上） |
| ⑤ | 应用名 | `spring.application.name` = demo-gateway |
| ⑥ | **线程名** | `or-http-epoll-3` = **reactor-netty 的事件循环线程**（`or`=Reactor、`epoll`=Linux IO 多路复用、`-3`=第 3 号）—— **整条请求链就跑在这一条线程上**（呼应 L00 的 EventLoop） |
| ⑦ | Logger | **缩写规则**：`o.s.c.g.h.X` → `org.s**pring.cloud.gateway.h**andler.X` |

**第 1 行 —— 路由命中**

```
2026-09-28T20:14:50.321+08:00 DEBUG 708723 --- [demo-gateway] [or-http-epoll-6] o.s.c.g.h.RoutePredicateHandlerMapping   : Route matched: demo-provider
```

- **谁打的**：`RoutePredicateHandlerMapping.lookupRoute`（gateway:130 附近的 `logger.debug("Route matched: " + route.getId())`）
- **什么时候**：逐条谓词测试、`.next()` 取出命中路由**之后**
- **它回答**：这次请求选中了哪条路由 → `demo-provider`

**第 2 行 —— 命中的 Route 全文（"差异数据"裸奔现场）**

```
2026-09-28T20:14:50.322+08:00 DEBUG 708723 --- [demo-gateway] [or-http-epoll-6] o.s.c.g.h.RoutePredicateHandlerMapping   : Mapping [Exchange: GET http://localhost:8080/provider/hello] to Route{id='demo-provider', uri=http://localhost:8081, order=0, predicate=Paths: [/provider/**], match trailing slash: true, gatewayFilters=[[[StripPrefix parts = 1], order = 1], [[AddRequestHeader X-Demo-Route = 'demo-provider'], order = 2], [[AddResponseHeader X-Demo-Gateway-Version = '1.0'], order = 3], [com.myxhs.demo.gateway.filter.DemoHeaderGatewayFilterFactory$$Lambda$1075/0x00007072085c1eb0@1d7a15, order = 4]], metadata={}}
```

- **谁打的**：C 组 `getHandlerInternal`（gateway:96-99）：`logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + r)`
- **逐字段解剖**（对照 17.5.0 的"差异数据"表）：

| 片段 | 讲什么 |
|---|---|
| `id='demo-provider'` | 路由 id（观测标签 / 限流 key 用它） |
| `uri=http://localhost:8081` | 目标地址（`RouteToRequestUrlFilter` 拿它算目标 URL） |
| `order=0` | 路由顺序（决定被"逐条谓词"测试的次序） |
| `predicate=Paths: [/provider/**], match trailing slash: true` | **已编译的谓词对象 toString**（不是 yml 字符串了） |
| `gatewayFilters=[...4 条...]` | **已编译的 4 条路由过滤器**；第 4 条是 L14 自定义工厂的 lambda（看包名 `com.myxhs.demo`） |
| `metadata={}` | 路由元数据（L07 的 `cors-lab` 这里会有 `{cors={...}}`） |

**第 3 行 —— handler 交接（并实证"单例"）**

```
2026-09-28T20:14:50.322+08:00 DEBUG 708723 --- [demo-gateway] [or-http-epoll-6] o.s.c.g.h.RoutePredicateHandlerMapping   : [f43d7d5d-5] Mapped to org.springframework.cloud.gateway.handler.FilteringWebHandler@3eae2a54
```

- **谁打的**：框架 `AbstractHandlerMapping.getHandler`（webflux:186-190）：`logger.debug(exchange.getLogPrefix() + "Mapped to " + handler)`
- `[f43d7d5d-5]` = **exchange 的 logPrefix（trace id）**：同一请求的全部日志都用它串 → `grep 'f43d7d5d-5'` 能还原完整轨迹
- `@3eae2a54` = 对象身份哈希 → **连打 4 个请求这个值不变**（实测 `uniq -c`：`4 ...@3eae2a54`）→ **"handler 是单例"被日志实证** ✅

**第 4 行 —— 合并排序后的完整过滤器链（本课最有价值的一行）**

```
2026-09-28T20:14:50.341+08:00 DEBUG 708723 --- [demo-gateway] [or-http-epoll-6] o.s.c.g.handler.FilteringWebHandler      : Sorted gatewayFilterFactories: [[GatewayFilterAdapter{delegate=...RemoveCachedBodyFilter...}, order = -2147483648], ... 【共 16 条】 ...]
```

- **谁打的**：D 组 `FilteringWebHandler.handle`（gateway:104-108）：`logger.debug("Sorted gatewayFilterFactories: " + combined)`
- **一句话**：**"这次请求会按什么顺序过哪些过滤器"，这一行全交代了**（12 全局 + 4 路由 = 16 条）

**逐条讲解（按执行顺序）**：

| # | 过滤器 | order | 类型 | 干什么 | 哪课 |
|---|---|---|---|---|---|
| 1 | `RemoveCachedBodyFilter` | `HIGHEST_PRECEDENCE`（-2147483648） | 全局 | 请求结束清 body 缓存 | L28 规划 |
| 2 | `AdaptCachedBodyGlobalFilter` | `HIGHEST_PRECEDENCE + 1000` | 全局 | 让 body/form 可被多次读（缓存） | L28 规划 |
| 3 | `NettyWriteResponseFilter` | `-1`（`WRITE_RESPONSE_FILTER_ORDER`） | 全局 | 把下游响应写回客户端（回程） | L27 |
| 4 | `ForwardPathFilter` | `0` | 全局 | `forward:` 协议 | 边缘 |
| 5 | `GatewayMetricsFilter` | `WRITE_RESPONSE_FILTER_ORDER + 1`（=0） | 全局 | 打 metrics | L35 |
| 6 | `[StripPrefix parts = 1]` | **1** | **路由** | 去掉 `/provider` | L05 |
| 7 | `[AddRequestHeader X-Demo-Route = 'demo-provider']` | **2** | **路由** | 加请求头 | L05 |
| 8 | `[AddResponseHeader X-Demo-Gateway-Version = '1.0']` | **3** | **路由** | 加响应头 | L05 |
| 9 | `DemoHeader$$Lambda`（L14 自定义） | **4** | **路由** | 你 L14 写的 | L14 |
| 10 | `DemoGlobalFilter`（自定义） | **100** | 全局 | 你 L13 写的 | L13 |
| 11 | `RouteToRequestUrlFilter` | `10000` | 全局 | `route.uri` → 目标 URL | L27 |
| 12 | `ReactiveLoadBalancerClientFilter` | `10150` | 全局 | `lb://` 选实例 | L16 |
| 13 | `LoadBalancerServiceInstanceCookieFilter` | `10151`（+1） | 全局 | sticky cookie | L29 |
| 14 | `WebsocketRoutingFilter` | `LOWEST_PRECEDENCE - 1` | 全局 | `ws://` 转发 | 边缘 |
| 15 | **`NettyRoutingFilter`** | `LOWEST_PRECEDENCE`（2147483647） | 全局 | **真转发（http/https）** | L27 |
| 16 | `ForwardRoutingFilter` | `LOWEST_PRECEDENCE` | 全局 | `forward:` 转发器 | 边缘 |

**从这行日志能看出 3 个"设计意图"**：

1. **"全局过滤器"不是"前面一批"** —— 它们**穿插**在路由过滤器周围：`DemoGlobalFilter`@100 排在路由过滤器@4 **之后**。（对照 17.5.2：`[全局...] + [路由...]` 之后 `sort()`，日志看到的就是排序结果）
2. **区间分工**：`< 0` 前置（body 缓存、回程准备）｜`0 ~ 9999` **业务区**（路由过滤器 + 自定义全局，**留给你插**）｜`≥ 10000` **转发区**（定地址 → 选实例 → 真转发，`NettyRoutingFilter` 必须最后）
3. **路由过滤器的 order `1..4` 从哪来**：**编译期自动编号** —— `loadGatewayFilters` 对非 Ordered 的过滤器按位置 `i + 1`（17.3.5）。所以 yml 不写 order，也是 1、2、3、4 顺着排。

注意：**被问但"返回空"的 mapping 不会打日志**（`Mapped to` 只在"有人应答"时打）—— 想看"8 个 mapping 逐个被问"用断点/Logpoint（③ 的 6\*）。

**其他值得知道的日志**：

| 时机 | 日志 | 出处 |
|---|---|---|
| 启动（INFO） | `Loaded RoutePredicateFactory [Path]` ×15 | `initFactories`（17.3.5 ②） |
| 刷新（DEBUG） | `RouteDefinition demo-provider applying filter {...} to StripPrefix` | B 组编译（`loadGatewayFilters`） |
| 更细（TRACE） | `RouteToRequestUrlFilter start` 等 | 把 `org.springframework.cloud.gateway` 改 `TRACE` —— 很吵，按需 |

---

## 18.8 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| Netty / reactor-netty 内部（EventLoop、ByteBuf、`HttpServer` 细节） | ❌ 一句话翻篇（L00 的 0.4 已知"事件循环"即可） |
| WebFlux 其余部分（注解式 `@Controller`、`WebSession` 实现、编解码细节） | ❌ 不在网关链路上 |
| **8 站管线 + 三个策略名单 + 两个插入点 + 重名坑 + `NO_OP` 语义** | ✅ **要掌握**（面试"请求怎么到达你的过滤器"就按 18.1 讲） |
| WebFilter vs GlobalFilter / GatewayFilter 的分层 | ✅ 记住"匹配前 vs 匹配后" |

---

## 18.9 Self-Test

1. 请求从 Netty 到网关过滤器，按顺序说出 **8 站**（每站一句话）。
2. exchange 在哪一站出生？为什么 17.x 讲的"差异数据放 exchange"依赖它？
3. `DispatcherHandler` 的三份名单分别是什么？"谁上"的判定规则各是什么？
4. `FilteringWebHandler` 有几个？各自的包名、跑什么、什么时候跑？
5. **"返回空"和"返回 `NO_OP_HANDLER`"** 的区别？各发生在什么场景、后果是什么？
6. （综合）WebFilter 链与网关过滤器链分别在哪个阶段？为什么权重计算必须放在 WebFilter？

---

## 18.10 Open Questions

- [~] **日志实测（部分完成 2026-09-28）**：DEBUG 已开、关键 4 行已录（样本见 18.7 ④）；剩余：用 **Logpoint** 录"8 个 mapping 谁先返回空"（空应答不打日志）。
- [x] ~~`WeightCalculatorWebFilter` 的 order~~：= `WEIGHT_CALC_FILTER_ORDER` = **10001**（已核）；`DemoWebFilter` = `MIN + 100`（L18 实验）
- [ ] `NO_OP_HANDLER` 返回时**响应状态码**是什么？（CORS 预检正常应该是 200 —— 与 L07 对齐一下，未实测）
- [ ] 第 3 站的 `DefaultServerWebExchange` 里，除 `attributes` 外还有哪些"每请求一份"的东西？（session / locale / codec 已知，未展开）
