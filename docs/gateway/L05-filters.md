# Lesson 05: Filters — 转发前后的加工动作

> 课程索引：`../gateway-学习清单.md` → Stage 2 · Lesson 05
> 版本口径：xhs-demo 实际运行的 SCG **4.1.5**
> 来源：官方文档 `spring-cloud-gateway/gatewayfilter-factories/*.adoc`（本地源码树）
> **提问方式**：直接说编号，如「5.3 没懂」

---

## 5.1 是什么

**过滤器 = 在"转发前"或"转发后"，对请求/响应做的加工动作。**

```
请求 ──→ [转发前：改请求] ──→ 下游服务 ──→ [转发后：改响应] ──→ 客户端
```

| 时机 | 能做什么 | 例子 |
|---|---|---|
| **转发前** | 改**请求** | 加请求头、改路径、加参数 |
| **转发后** | 改**响应** | 加响应头、改状态码 |

> 同一个过滤器可能只在一边生效：`StripPrefix` 只改请求；`SetStatus` 是**等下游响应返回后**才覆盖状态码。

---

## 5.2 只记这 8 个

> 官方文档描述了 **35 个**；**本版本实测装配 33 个**（差的是条件未满足的：Redis / 熔断 / OAuth2 / gRPC，见 L17 · 17.5.2）。全清单：`GET /actuator/gateway/routefilters`。以下 8 个覆盖日常 90% 场景。

| # | 过滤器 | 官方语法 | 作用 | 典型场景 |
|---|---|---|---|---|
| 1 | **StripPrefix** | `StripPrefix=2` | 去掉路径**前 N 段** | 下游不带 `/api` 前缀 |
| 2 | **PrefixPath** | `PrefixPath=/mypath` | 加路径前缀 | 下游需要 `/v1` 前缀 |
| 3 | **RewritePath** | `RewritePath=/red/?(?<segment>.*), /$\{segment}` | **正则**改写路径 | 路径转换（最灵活） |
| 4 | **AddRequestHeader** | `AddRequestHeader=X-Request-red, blue` | 加请求头 | 传身份/标记 |
| 5 | **AddResponseHeader** | `AddResponseHeader=X-Response-Red, Blue` | 加响应头 | 标记版本 / CORS |
| 6 | **SetStatus** | `SetStatus=UNAUTHORIZED` 或 `404` | 改响应状态码 | 强制返回某状态 |
| 7 | **RedirectTo** | `RedirectTo=302, https://acme.org` | 重定向（**300 系列**） | 旧路径迁移 |
| 8 | **RemoveRequestHeader** | `RemoveRequestHeader=X-Request-Foo` | 删请求头 | 去掉不安全/无用头 |

**官方原话摘录**：

> **StripPrefix**: The `parts` parameter indicates the **number of parts in the path** to strip from the request before sending it downstream.
>
> **PrefixPath**: This prefixes `/mypath` to the path of all matching requests.
>
> **RewritePath**: takes a path `regexp` parameter and a `replacement` parameter. This uses Java regular expressions for a flexible way to rewrite the request path.
>
> **SetStatus**: takes a single parameter, `status`. It must be a valid Spring `HttpStatus`. It may be the integer value `404` or the string representation: `NOT_FOUND`.
>
> **RedirectTo**: takes three parameters, `status`, `url`, and optionally `includeRequestParams`. The `status` parameter should be a **300 series** redirect HTTP code.

---

## 5.3 过滤器的两个参数（写过滤器必须先懂）

### ★ 掌握卡（只要记这些；下面 7 类清单是查阅级）

```java
Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain)   // ① 签名（能默写）
```

| # | 三个动作 | 写法 |
|---|---|---|
| 1 | **读请求** | `exchange.getRequest()...` |
| 2 | **存数据**（跨过滤器） | `exchange.getAttributes().put(k, v)`（**不用 ThreadLocal**） |
| 3 | **放行** | `chain.filter(exchange)` —— **不调用 = 短路返回**（鉴权就这么做） |

| # | 两条规则 | 含义 |
|---|---|---|
| 1 | **`then` = post 阶段** | `chain.filter(...).then(...)` 里的代码在**整条链（含转发）做完后**才执行 → 记耗时/改响应在这 |
| 2 | **改了要用新的** | `exchange.mutate()...build()` 后必须 `chain.filter(mutated)`，否则**白改** |

**一个坑**：**不要自己 `subscribe()`**（框架会订阅你返回的 `Mono`）。

**能默写这个最小例子 = 过关**：

```java
@Component
public class MyFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        exchange.getAttributes().put("start", start);

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header("X-From", "gateway"))
                .build();

        return chain.filter(mutated)
                .then(Mono.fromRunnable(() -> {
                    long cost = System.currentTimeMillis() - start;
                    System.out.println("cost=" + cost + "ms");
                }));
    }

    @Override
    public int getOrder() { return 100; }
}
```

> **以下为查阅级**（理解用，不用记）—— `ServerWebExchange` 的完整能力、Servlet 对照、异步差异。

---

### 参数 1：`ServerWebExchange exchange` —— "一次请求-响应的上下文"

**Spring WebFlux 的类型**（不是 Gateway 独创）。一个 `exchange` = **一次请求 + 它对应的响应**，贯穿整条过滤器链。

**"一次请求的上下文应该有什么"—— 7 类，它全收进一个对象**：

| 类别 | 该有什么 | 对应方法 |
|---|---|---|
| **① 输入** | 方法 / URL / 路径 / 查询参数 / 头 / Cookie / 客户端 IP / TLS / **请求体** | `getRequest()` → `ServerHttpRequest` |
| **② 输出** | 状态码 / 响应头 / 响应体 | `getResponse()` → `ServerHttpResponse` |
| **③ 状态**（贯穿全链） | 请求级**共享数据** | `getAttributes()` |
| **④ 身份 / 会话** | 登录用户 / 会话 | `getPrincipal()` / `getSession()` |
| **⑤ 内容解析** | 表单 / 文件上传 | `getFormData()` / `getMultipartData()` |
| **⑥ 框架集成** | Spring 容器 / 国际化 / 条件请求 / URL 改写 | `getApplicationContext()` / `getLocaleContext()` / `checkNotModified()` / `transformUrl()` |
| **⑦ 派生 / 日志** | 不可变派生 / 日志前缀 | `mutate()` / `getLogPrefix()` + `LOG_ID_ATTRIBUTE` |

**① 输入（最常用）**：

```java
exchange.getRequest().getMethod()         // GET / POST
exchange.getRequest().getURI()            // 完整 URI
exchange.getRequest().getPath()           // 路径（含 contextPath）
exchange.getRequest().getQueryParams()    // 查询参数（MultiValueMap）
exchange.getRequest().getHeaders()        // 请求头
exchange.getRequest().getCookies()        // Cookie
exchange.getRequest().getRemoteAddress()  // 客户端地址（真实 IP 相关，L47）
exchange.getRequest().getSslInfo()        // TLS 信息
exchange.getRequest().getBody()           // ★ Flux<DataBuffer> —— 流，只能读一次（L28）
```

**② 输出**：

```java
exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);  // 状态码
exchange.getResponse().getHeaders().add("X-Foo", "bar");        // 响应头
exchange.getResponse().writeWith(flux);                         // 写 body
exchange.getResponse().setComplete();                           // 结束（短路用）
```

**③ `attributes`（★ 最重要）** —— 三类内容：

| 装什么 | 例子 |
|---|---|
| **你自己的** | `traceId`、`startNanos`（见 `DemoGlobalFilter`） |
| **框架的** | `GATEWAY_ROUTE_ATTR`（命中的 Route，L23）<br>`GATEWAY_REQUEST_URL_ATTR`（转发 URL，L27）<br>`GATEWAY_ORIGINAL_REQUEST_URL_ATTR`（原始 URL） |
| **跨过滤器通信** | 它存在的理由（Servlet 里对应 `request.setAttribute`） |

**⑥ `getApplicationContext()`** = **Servlet 的 `ServletContext` 在 WebFlux 的对应物**：

| 你要什么级 | 从哪拿 |
|---|---|
| **请求级**数据 | `exchange.getAttributes()` |
| **应用级**（容器 / bean） | `exchange.getApplicationContext()`（更推荐**构造注入**） |

**⑦ `mutate()`** = 不可变语义（每个过滤器派生自己的副本，避免互相踩）。

**⚠️ 不可变模式的经典坑**：

```java
// ❌ 改了但传的是原对象 → 改动不生效
exchange.getRequest().mutate().header("X-Foo", "bar").build();   // 白改
return chain.filter(exchange);                                   // ← 原对象

// ✅ 改了要用【新的】
ServerWebExchange mutated = exchange.mutate()
        .request(r -> r.header("X-Foo", "bar"))
        .build();
return chain.filter(mutated);
```

**⚠️ 与 Servlet 的一个关键差异**：`getSession()` / `getPrincipal()` 在 WebFlux 里返回 **`Mono`**（异步，可能涉及 IO），Servlet 里是**同步**返回 → 用法要用 `flatMap`。

**`attributes` 是过滤器之间的"共享文件袋"**：

```java
// 过滤器 A（pre）：放
exchange.getAttributes().put("demo.startNanos", System.nanoTime());

// 过滤器 B（post）：取
long start = (long) exchange.getAttributes().get("demo.startNanos");
```

框架自己也往里放东西（后面课会见到）：
- `GATEWAY_ROUTE_ATTR` —— 命中的 `Route`（匹配阶段放入，L23）
- `GATEWAY_REQUEST_URL_ATTR` —— 转发用的 URL（L27）

### 参数 2：`GatewayFilterChain chain` —— "我后面还有谁"

**Gateway 的类型**（`org.springframework.cloud.gateway.filter`）。它只有**一个用途**：

```java
chain.filter(exchange);   // 把请求交给【链上的下一个过滤器】
```

| 关键点 | 说明 |
|---|---|
| 返回值 | `Mono<Void>` —— 表示"**从我后面开始，直到整条链结束**" |
| **不调用会怎样** | 请求**停在你这里**（短路）—— 后面的过滤器、转发**都不会执行**（这正是"鉴权不通过直接返回"的实现方式） |
| 谁在"后面" | 按 `order` 排在**你之后**的过滤器（**`order` 小的先执行**） |

**"不调用 = 短路"就是鉴权的写法**（一定要会）：

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    if (!authorized(exchange)) {
        // ① 改状态码
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        // ② 结束响应【并且不再调 chain.filter】—— 请求到此为止
        return exchange.getResponse().setComplete();
    }
    // ③ 放行：交给链上的下一个
    return chain.filter(exchange);
}
```

> ⚠️ 注意 `setComplete()` 返回的就是 `Mono<Void>` —— **直接 return 它**，不要 `subscribe()`（L10 的坑）。

**这就是"洋葱模型"的来源**：

```
你的过滤器 (order=100)   : pre 代码 → chain.filter() ─┐
  下一个 (order=10000)   : pre 代码 → chain.filter() ─┤
    ...                                              │
    NettyRoutingFilter (MAX)  ← 真正转发（最内层）     │
  下一个 的 .then() 执行（post） ←────────────────────┤
你的 .then() 执行（post） ←───────────────────────────┘
```

> **所以** `chain.filter(exchange).then(...)` 里的 `.then(...)` 会在**整条链（含转发）全部完成后**才执行 —— 这正是 **L10 讲的"post 阶段"**。

### 一张图串起来

```
一次请求
   ↓
ServerWebExchange     ← "文件袋"：请求 + 响应 + attributes，贯穿全链
   ↓
FilteringWebHandler → DefaultGatewayFilterChain（index 指向当前位置，L26）
   ↓
每个过滤器：filter(exchange, chain)
     ├─ 读 / 改 exchange（**改了必须 mutate 后用新的**）
     └─ chain.filter(...) → 交给下一个（或短路返回）
```

### 对照：Servlet(MVC) 与 WebFlux(Gateway) 的对象模型（★ 帮助迁移已有知识）

| Servlet / MVC | WebFlux / Gateway | 说明 |
|---|---|---|
| `HttpServletRequest` + `HttpServletResponse`（**两个对象**） | **合成一个 `ServerWebExchange`** | ★ 最大区别：请求与响应在 WebFlux 里是**一个对象的两半**（`getRequest()` / `getResponse()`） |
| `request.setAttribute(k,v)` | `exchange.getAttributes().put(k,v)` | 请求级数据袋（语义一致） |
| 可以用 `ThreadLocal`（一请求一线程） | **不能用**（EventLoop 线程复用） | 只能用 `attributes`（L00·0.4） |
| `ServletContext`（应用级，Servlet 规范） | **无对应物**（WebFlux 无 Servlet 规范）→ 用 Spring `ApplicationContext` | |
| 过滤器签名 `doFilter(req, resp, chain)` | `filter(exchange, chain)` | 参数从"两个"变成"一个 + 链" |
| `chain.doFilter(req, resp)` | `chain.filter(exchange)` | 放行；**不调用 = 短路**（语义一致） |

---

## 5.4 🔴 要掌握的 1 条：`StripPrefix=N` 剥掉的是"前 N **段**路径"

**不是"前 N 个字符"！**

```
StripPrefix=1    /provider/echo/abc   →   /echo/abc      （剥 1 段）
StripPrefix=2    /provider/echo/abc   →   /abc           （剥 2 段）
```

**"段（part）" = 用 `/` 分开的一段。**

> ⚠️ 这是最容易搞错的点：`StripPrefix=1` 不会把 `/provider` 变成 `/rovider`。

---

## 5.5 三种"改路径"的区别（最容易混）

| 过滤器 | 语义 | 例子 |
|---|---|---|
| `StripPrefix=N` | **去掉**前 N 段 | `/provider/echo/abc` → `/echo/abc` |
| `PrefixPath=/v1` | 在**最前面加** | `/echo/abc` → `/v1/echo/abc` |
| `RewritePath=正则,替换` | **正则替换**（最灵活） | `/red/abc` → `/abc` |

**`RewritePath` 语法拆解**（官方示例）：

```yaml
- RewritePath=/red/?(?<segment>.*), /$\{segment}
#                └── 正则（命名捕获组 segment）┘  └─ 替换（用 segment 的值）─┘
# 效果：/red/abc → /abc   （与 StripPrefix=1 等价）
```

**什么时候用 `RewritePath` 而不是 `StripPrefix`**：路径转换不是"简单去前缀"时，例如
- `/old-name/**` → `/new-name/**`（改名）
- `/api/v2/user/**` → `/user/**`（去两段但保留中间的 v2）

---

## 5.6 动手（5 分钟）

**实验 1：体会 `RewritePath` ≡ `StripPrefix`**

```yaml
- id: demo-rewrite-lab
  uri: http://localhost:8081
  predicates:
    - Path=/rw/**
  filters:
    - RewritePath=/rw/(?<seg>.*), /${seg}
```

```bash
curl -s --noproxy '*' http://localhost:8080/rw/echo/abc
# 应与 /provider/echo/abc 结果相同 → 证明 RewritePath 与 StripPrefix=1 等价
```

**实验 2：`SetStatus` 改响应码**

```yaml
- id: demo-setstatus-lab
  uri: http://localhost:8081
  predicates:
    - Path=/forced/**
  filters:
    - StripPrefix=1
    - SetStatus=401
```

```bash
curl -s -o /dev/null -w 'HTTP=%{http_code}\n' --noproxy '*' http://localhost:8080/forced/echo/abc
# 预期 401（但响应体还是 provider 返回的 JSON）
```

---

## 5.7 架构视角

### 5.6.1 先澄清一个概念混淆

**"路由过滤器 vs 全局过滤器"的区分维度是「作用范围」，不是「yml vs 代码」。**

| | 路由过滤器（`GatewayFilter`） | 全局过滤器（`GlobalFilter`） |
|---|---|---|
| **作用范围** | **只对绑定的那条路由** | **所有路由** |
| yml 能配吗 | ✅（通过"工厂名"引用） | ❌（yml 里没有它的位置） |
| **代码能写吗** | ✅ **能，两种方式** | ✅（只能代码写） |

### 5.6.2 路由过滤器的两种代码写法

**方式 A：自定义 `GatewayFilterFactory`**（写一次，yml 里复用）

```java
@Component
public class MyAuthFilterFactory extends AbstractGatewayFilterFactory<MyAuthFilterFactory.Config> {

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // 加工逻辑
            return chain.filter(exchange);
        };
    }

    public static class Config { /* 可配参数 */ }
}
```

写完后 yml 里即可使用：`filters: - MyAuth=xxx` → **L14 讲**

**方式 B：Java DSL**（纯代码构建路由 + 过滤器）

```java
@Bean
public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
    return builder.routes()
        .route(r -> r.host("**.abc.org").and().path("/image/png")
            .filters(f -> f.addResponseHeader("X-TestHeader", "foobar"))
            .uri("http://httpbin.org:80"))
        .build();
}
```

> 官方补充：Java DSL 支持 `and()` / `or()` / `negate()` 组合谓词，**比 yml 更灵活**（yml 只能 AND）。

### 5.6.3 Java DSL vs yml（选型）

**Java DSL 的独特能力（yml 做不到的）**

| # | 能力 | 说明 |
|---|---|---|
| 1 | **复杂谓词组合** | yml **只能 AND**；Java DSL 支持 `and()` / `or()` / `negate()` |
| 2 | **引用 Spring Bean** | `filters(f -> f.filter(myFilterBean))` —— 直接把 bean 塞进去 |
| 3 | **程序化生成路由** | 从数据库/配置批量生成 N 条路由（yml 做不到） |
| 4 | **类型安全** | 编译期就能发现错误 |

**关键权衡**

| | **yml** | **Java DSL** |
|---|---|---|
| 改路由 | 改配置（**可热更新**） | 改代码 + **重新部署** |
| 谓词组合 | 只能 AND | ✅ `and/or/negate` |
| 引用 bean | ❌（只能 `#{@beanName}` 表达式） | ✅ |
| 程序化生成 | ❌ | ✅ |
| 类型安全 | ❌ 运行期才发现 | ✅ 编译期 |
| 运维可读性 | ✅ 一眼看懂 | ❌ 得看代码 |

**⚠️ 一个重要的架构区别：动态路由**

| | yml 定义的路由 | Java DSL 定义的路由 |
|---|---|---|
| 能否配合 Nacos 动态刷新 | ✅ **能**（改配置 → `RefreshRoutesEvent` → 生效） | ❌ **默认不能**（`@Bean` 创建后固定） |
| 生效方式 | 热更新（不重启） | 重启 / 重新部署 |

**这是选型的第一问**：**这个路由需要"线上动态改"吗？**
- 需要 → **必须用 yml**（或 `RouteDefinitionLocator` 的其他实现：Nacos / Redis / Actuator）
- 不需要 → 两者都行

**什么时候真的会用 Java DSL**

| 场景 | 为什么 |
|---|---|
| 路由需要**程序化生成**（按租户/环境批量） | yml 手写不现实 |
| 需要**引用 bean**（自定义过滤器实例、动态 KeyResolver） | DSL 更直接 |
| 需要 **or/not 谓词** | yml 只能 AND |
| **测试 / 小型项目**（不想维护 yml） | 代码即配置 |
| **框架 / 平台层**（对外提供路由能力） | 如 L43 的 polygateway（端点级路由） |

### 5.6.4 判断标准（修正版）

| 需求 | 用什么 | 为什么 |
|---|---|---|
| **所有路由**都要做（鉴权/日志/TraceId） | **全局过滤器** | 避免每条路由重复配 |
| **只有某条路由**要做（StripPrefix） | **路由过滤器** | 只影响该路由 |
| 某条路由要做 **+ 需要可配置参数** | **路由过滤器 + 自定义 Factory** | 代码写逻辑，yml 传参 |
| 路由多、需要**复杂谓词组合**（or/not） | **Java DSL** | yml 只能 AND |

⚠️ **过滤器不该放业务逻辑**（网关的边界，L40 展开）—— 比如"下单后发短信"绝不能放过滤器。

---

## 5.8 过滤器全景（几种 / 实现哪个接口 / 怎么注册）

### 5.7.1 只有 2 种 Filter（按接口分）

| 接口 | 名字 | 作用范围 |
|---|---|---|
| **`GatewayFilter`** | **路由过滤器** | **只对绑定它的那条路由** |
| **`GlobalFilter`** | **全局过滤器** | **所有路由** |

**判断标准只有一个**：**"这条逻辑对每条路由都成立吗？"** → 是 = Global；否 = 路由级。

### 5.7.2 为什么容易看乱：两个正交维度

| 维度 | 取值 |
|---|---|
| **谁写的** | 内置（官方）/ 自定义（你） |
| **怎么配** | yml / 代码 |

**2 种接口 × 2 个维度 = 看起来很多，但接口只有 2 种。**

### 5.7.3 ⚠️ `GatewayFilterFactory` 不是第三种 Filter

```
GatewayFilter        = 产品（真正的过滤器对象）
GatewayFilterFactory = 生产产品的工厂
```

**为什么需要工厂**：yml 里写的是**文本**（如 `StripPrefix=1`），需要一个类把文本**解析成参数、创建出过滤器对象** —— 这个类就是 Factory。

> 官方内置的过滤器（**本版本实测装配 33 个**）**本身就是用 Factory 模式写的**。你写自定义 Factory，等于和它们并列（L14 的 `demoHeader` 就混在同一个 bean 清单里）。

### 5.7.4 完整分类图

```
Filter（只有 2 种接口）
│
├── GatewayFilter（路由级：只对绑定它的路由生效）
│     ├── 内置 33 个（本版本）→  yml 里写名字直接用
│     │       例：StripPrefix=1 / AddRequestHeader=X-A, b
│     └── 自定义      →  ① 写一个 XXXGatewayFilterFactory（工厂）
│                        ② yml 里写 `XXX=参数` 用
│                        （工厂不是第三种 Filter，是"生产过滤器的类"）
│
└── GlobalFilter（全局：对所有路由生效）
      ├── 内置多个（框架自己用：NettyRoutingFilter / ReactiveLoadBalancerClientFilter /
      │              NettyWriteResponseFilter / GatewayMetricsFilter / WebsocketRoutingFilter ...）
      │    ⚠️ 官方文档列出 8 个；代码里实际有 16 个类实现 GlobalFilter
      └── 自定义      →  实现 GlobalFilter 接口 + @Component
                         例：鉴权、日志、TraceId
```

### 5.7.5 三个高频疑问（官方依据）

**Q1：自定义路由过滤器，实现哪个接口？**

→ **继承 `GatewayFilterFactory`**（通常是 `AbstractGatewayFilterFactory`）。
它的 `apply(config)` 方法**返回** `GatewayFilter`。

```java
@Component
public class MyGatewayFilterFactory
        extends AbstractGatewayFilterFactory<MyGatewayFilterFactory.Config> {

    @Override
    public GatewayFilter apply(Config config) {          // ★ 返回 GatewayFilter
        return (exchange, chain) -> chain.filter(exchange);
    }

    public static class Config { /* yml 可传的参数 */ }
}
```

| 场景 | 你写什么 |
|---|---|
| 要在 **yml** 里配 | **`GatewayFilterFactory`**（继承抽象类 + `@Component`） |
| 只在 **Java DSL** 里用 | 直接写 `GatewayFilter`（lambda 即可，不需要工厂） |

**Q2：全局过滤器实现 `GlobalFilter`，对吗？**

→ **对。** 实现 `GlobalFilter`，通常同时实现 `Ordered` 控制顺序。

```java
@Component
public class MyGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() { return 100; }
}
```

**Q3：都要注册为 Spring 组件吗？**

→ **都要。** 框架靠"注入 `List<Xxx>`"来收集：
- `List<GlobalFilter>` → 交给 `FilteringWebHandler`
- `List<GatewayFilterFactory>` → 交给 `RoutePredicateHandlerMapping`

**不注册 → 框架收集不到 → 不生效。**

注册方式：`@Component`（最常用）或 `@Bean`。

### 5.7.6 一张表收尾

| 你想做的事 | 接口 / 类 | 怎么写 |
|---|---|---|
| 所有路由都要（鉴权/日志/TraceId） | `GlobalFilter` | 实现接口 + `@Component` |
| 只有某条路由要（路径改写） | 用**内置** `GatewayFilter` | yml 配：`StripPrefix=1` |
| 只有某条路由要 **+ 参数化/复用** | 写 `GatewayFilterFactory` | 继承抽象类 + `@Component`，yml 传参 |

---

## 5.9 结论

> **过滤器 = 转发前后的加工动作；有的改请求（转发前），有的改响应（转发后）。**
> **常用 8 个：StripPrefix / PrefixPath / RewritePath / AddRequestHeader / AddResponseHeader / SetStatus / RedirectTo / RemoveRequestHeader。**
> **`StripPrefix=N` 剥掉的是"前 N 段路径"（段数，不是字符数）。**
> **Filter 只有 2 种接口：`GatewayFilter`（路由级）/ `GlobalFilter`（全局）；`GatewayFilterFactory` 是工厂不是第三种。**

---

## 5.10 Self-Test

1. `StripPrefix=1` 作用于 `/api/v1/user/123`，下游收到什么路径？`StripPrefix=2` 呢？
2. `PrefixPath` 和 `RewritePath` 都能改路径，区别是什么？
3. 一个"所有路由都要加 TraceId"的需求，应该用路由过滤器还是全局过滤器？为什么？
4. `SetStatus=401` 之后，响应体还是下游返回的内容吗？
5. Filter 有几种接口？`GatewayFilterFactory` 是第三种 Filter 吗？它和 `GatewayFilter` 是什么关系？
6. 自定义一个"能在 yml 里配置"的过滤器，要写什么类？要注册成 Spring Bean 吗？为什么？

---

## 5.11 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「5.4 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
