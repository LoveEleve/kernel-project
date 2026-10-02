# Lesson 01: What Is Gateway — Official Positioning

> 课程索引：`../gateway-学习清单.md` → Stage 1 · Lesson 01
> 源码口径：官方文档树 SCG 4.2.2 ｜ 运行版本 SCG 4.1.5
> 状态：✅ 已讲（2026-09-22）
> **提问方式：直接说编号**，例如「1.1.2 ② 那句我没懂」「1.6 第二个坑再讲讲」

---

## 0. 阅读约定

每小节采用统一结构：

| 顺序 | 部分 | 作用 |
|---|---|---|
| ① | **核心结论** | 该段官方原话的技术要点，一句话概括 |
| ② | **机制说明** | 展开底层原理，含术语定义与对比 |
| ③ | **官方要点** | 把官方原话拆成可复述的 N 条 |
| ④ | **术语对照** | 官方英文 ↔ 中文术语，逐项对齐 |
| ⑤ | **结论** | 一句话收束 |
| — | 英文原文 + 中文翻译 | 存档，用于与官方文档核对 |

---

## 1.1 Official Statements (Verbatim)

### 1.1.1 What It Is — `docs/modules/ROOT/pages/intro.adoc`

**英文原文**

> This project provides an **API Gateway** built on top of the Spring Ecosystem, including: Spring 6, Spring Boot 3 and Project Reactor. Spring Cloud Gateway aims to provide a simple, yet effective way to **route to APIs** and provide **cross cutting concerns** to them such as: **security, monitoring/metrics, and resiliency**.
>
> There are two distinct flavors of Spring Cloud Gateway: **Server** and **Proxy Exchange**. Each flavor offers **WebFlux and MVC** compatibility.
> - The **Server** variant is a full-featured API gateway that can be standalone or embedded in a Spring Boot application.
> - The **Proxy Exchange** variant is exclusively for use in annotation based WebFlux or MVC applications and allows the use of a special `ProxyExchange` object as a parameter to a web handler method.

**中文翻译**

> 本项目提供一个 **API 网关**，它构建在 Spring 生态之上，包括 Spring 6、Spring Boot 3 和 Project Reactor。Spring Cloud Gateway 的目标是提供一种**简单而有效**的方式，把请求**路由到 API**，并为这些 API 提供**横切关注点**，例如：**安全、监控/指标、弹性**。
>
> Spring Cloud Gateway 有**两种截然不同的形态（flavor）**：**Server** 和 **Proxy Exchange**。每种形态都提供 **WebFlux 和 MVC** 两种兼容版本。
> - **Server** 形态是一个功能完整的 API 网关，可以独立部署，也可以嵌入 Spring Boot 应用。
> - **Proxy Exchange** 形态专门用于**注解式**的 WebFlux 或 MVC 应用，允许把一个特殊的 `ProxyExchange` 对象作为 web handler 方法的参数使用。

#### ① 核心结论

API Gateway 是微服务架构中的**边界组件（edge service）**，承担系统的**北向入口**职责：对外终结客户端请求，对内完成**路由分发**与**横切关注点**的集中处理。

#### ② 机制说明

**术语定义**：

- **边界组件 / 边缘服务（edge service）**：位于系统网络边界、直接面向外部客户端的组件，与"内部业务服务"相对。
- **北向入口（north-south entry）**：来自系统外部（客户端）的流量称为南北向流量；与之相对的是系统内部服务之间的东西向流量。
- **路由分发（routing）**：根据请求特征（路径、方法、Header 等）决定该请求由哪个下游服务处理。
- **横切关注点（cross-cutting concern）**：不属于单一业务功能、而是多个模块共同需要的关注点，如认证、限流、日志、事务。其"横切"含义指它跨越（cross-cut）多个业务模块的纵向切分。

**无网关 vs 有网关的架构差异**：

| 维度 | 客户端直连各服务 | 经网关统一入口 |
|---|---|---|
| 地址依赖 | 客户端与每个服务的 host/port/实例列表强耦合 | 客户端只依赖一个稳定入口地址 |
| 横切逻辑 | 每个服务各自实现（重复 N 份，且随时间版本漂移） | 在网关单点实现，下游只处理业务 |
| 实例伸缩 | 客户端需感知实例变更，或另加一层负载均衡 | 结合服务发现，实例变更对客户端透明 |
| 协议/版本演进 | 每个服务独立演进，客户端需逐一对齐 | 入口层可做协议转换与版本路由 |

**职责二分**：官方将其职责明确切分为两个动词 —— `route`（转发）与 `provide cross cutting concerns`（加工）。这是理解 Gateway 一切设计的主轴。

#### ③ 官方要点（3 条）

**要点 1：它是一个架构角色，而非一个转发组件**

官方使用名词 **API Gateway**（架构模式名），而非动词 proxy。其定位是"系统承担入口职责的那一层"，而不是"一个具备转发能力的库"。

**要点 2：职责二分 —— 转发 + 横切关注点**

| 职责 | 官方原词 | 含义 | xhs-project 落点 |
|---|---|---|---|
| 转发 | **route to APIs** | 按条件将请求送达正确的下游 | 16 条路由（`Path=/api/xxx/**` → `lb://my-xhs-xxx`） |
| 加工 | **cross cutting concerns** | 集中实现跨模块公共能力 | 8 个过滤器 |

官方显式列举的横切关注点仅**三类**：

| 官方类别 | 含义 | xhs 网关的过滤器（真实 order） |
|---|---|---|
| **security** | 身份认证、完整性校验、防重放 | `GatewayAuthFilter`(+1000, JWT)、`HmacSignatureFilter`(+1500, HMAC+nonce)、`BodyCacheFilter`(最先, 为签名校验缓存请求体) |
| **monitoring/metrics** | 日志、指标、链路追踪 | `RequestLogFilter`(+100, TraceId+出入站日志)、`GatewayMetricsConfig`(MeterRegistry) |
| **resiliency** | 限流、熔断、降级（弹性） | `RateLimitFilter`(+2500, Sentinel) |
| （xhs 扩展） | 灰度、版本路由 | `TrafficColoringFilter`(+1200)、`GrayRouteFilter`(+3000)、`ApiVersionFilter`(+3100) |

> ⚠️ 官方只列举三类。xhs 的"灰度/版本"属于**项目扩展**，不是官方定位的组成部分，面试时不可表述为"官方要求"。

**要点 3：两种形态（flavor）**

```
Spring Cloud Gateway
├── Server 形态（完整网关：可独立部署，也可嵌入 Spring Boot 应用）
│     ├── WebFlux 版  spring-cloud-starter-gateway        ← 主流，xhs 采用
│     └── MVC 版      spring-cloud-starter-gateway-mvc    ← 4.2.0 引入，Servlet 栈
└── Proxy Exchange 形态（仅用于注解式 WebFlux/MVC 应用，作为 handler 方法参数）
```

- **Server**：一个完整的网关服务 → xhs 的 `my-xhs-gateway` 属于此类
- **Proxy Exchange**：不是网关服务，仅提供在业务 handler 中"就地代理"的能力 → 与 xhs 无关

#### ④ 术语对照

| 官方英文 | 中文术语 |
|---|---|
| `provides an API Gateway` | 提供 API 网关（架构角色） |
| `built on top of the Spring Ecosystem` | 构建于 Spring 生态之上（非独立技术栈，详见 1.1.2） |
| `route to APIs` | 路由转发 |
| `cross cutting concerns` | 横切关注点 |
| `security` / `monitoring/metrics` / `resiliency` | 安全 / 监控与指标 / 弹性 |
| `two distinct flavors` | 两种形态（非"两个版本"） |
| `Server variant` | Server 形态：完整网关服务 |
| `Proxy Exchange variant` | Proxy Exchange 形态：就地代理能力 |
| `standalone or embedded` | 独立部署 / 嵌入宿主应用 |

#### ⑤ 结论

> **API Gateway = 边界组件：对外终结客户端请求，对内完成路由转发与横切关注点集中处理。**

---

### 1.1.2 Hard Constraints — `.../spring-cloud-gateway/starter.adoc`

**英文原文**

> Spring Cloud Gateway is built on Spring Boot, **Spring WebFlux**, and **Project Reactor**. As a consequence, many of the familiar **synchronous libraries** (Spring Data and Spring Security, for example) and patterns you know **may not apply**.
>
> Spring Cloud Gateway **requires the Netty runtime** provided by Spring Boot and Spring Webflux. It **does not work in a traditional Servlet Container or when built as a WAR**.

**中文翻译**

> Spring Cloud Gateway 构建在 Spring Boot、**Spring WebFlux** 和 **Project Reactor** 之上。因此，你熟悉的许多**同步库**（例如 Spring Data 和 Spring Security）和模式**可能不再适用**。
>
> Spring Cloud Gateway **需要** Spring Boot 和 Spring WebFlux 提供的 **Netty 运行时**。它**无法运行在传统 Servlet 容器中，也不能构建为 WAR**。

#### ① 核心结论

Gateway 运行在 **Reactor Netty** 之上，采用**事件循环（EventLoop）+ 非阻塞 IO** 的线程模型。该模型决定了：**不可执行阻塞操作**，且**不能部署于 Servlet 容器**。

#### ② 机制说明

**两种线程模型的对比**（这是理解全部约束的根源）：

| 维度 | Servlet 容器（Tomcat） | Reactor Netty（Gateway） |
|---|---|---|
| 线程模型 | thread-per-request（一请求一线程） | EventLoop（事件循环） |
| 线程数量级 | 数百（线程池，可弹性扩） | 约等于 CPU 核数 |
| 阻塞一个请求的代价 | 仅占用该请求所在的一个线程 | **阻塞该 EventLoop 线程，其上承载的所有连接一并停滞** |
| IO 模型 | 阻塞式 IO | 非阻塞 IO + 事件驱动 |
| 编程范式 | 命令式（同步调用） | 响应式（`Mono` / `Flux`） |

**为什么"同步库不适用"**：

EventLoop 线程数极少（约 CPU 核数），因此**单个阻塞调用的影响被放大**：在 thread-per-request 模型下阻塞只损失一个线程；在 EventLoop 模型下，阻塞会使其承载的整批连接停滞。

Spring Data、MyBatis、Spring Security 的常规用法均为阻塞式（同步等待 IO 返回），直接在 Gateway 中调用会阻塞事件循环。**这不是"不推荐"，而是会引发系统级故障。**

**为什么"不能跑在 Servlet 容器 / 不能打 WAR"**：

- Servlet 容器（Tomcat / Jetty）的运行时模型是 thread-per-request，与 WebFlux 的事件驱动模型不兼容。
- WAR 是 Java Web 应用的传统打包形式，部署目标即外部 Servlet 容器。因此打 WAR 等同于运行在 Servlet 容器中。
- 结论：**Gateway 只能以可执行 jar（内嵌 Reactor Netty）形式运行。**

#### ③ 官方要点（2 条）

**要点 1：技术底座三件套（缺一不可）**

| 组件 | 职责 |
|---|---|
| Spring Boot | 自动配置装配（组件注册与属性绑定） |
| Spring WebFlux | 响应式 Web 框架（对应同步版本为 Spring MVC） |
| Project Reactor | 响应式编程库（提供 `Mono` / `Flux` 抽象） |

推论：基于同步阻塞模型的库与编程模式，在 Gateway 中不适用。

**要点 2：运行时必须是 Netty**

| 术语 | 含义 |
|---|---|
| `Netty runtime` | 底层 HTTP 服务端 / 客户端实现为 Netty（非阻塞、事件驱动） |
| `traditional Servlet Container` | Tomcat / Jetty 等基于 thread-per-request 的容器 |
| `built as a WAR` | 打成 WAR 包部署至外部容器（等价于运行在 Servlet 容器） |

#### ④ xhs-project 实证

`my-xhs-gateway/pom.xml` 仅引入 `spring-cloud-starter-gateway`，**未引入** `spring-boot-starter-web` → 确认运行于 Netty。

但其 `application.yml` 中配置了一组 Tomcat 参数：

```yaml
server:
  port: 19000
  shutdown: graceful
  tomcat:                        # ← 无效配置
    threads:
      max: 300                   # Netty 不解析 server.tomcat.*
    max-connections: 8192
    accept-count: 100
    keep-alive-timeout: 60000
```

**该配置段整体无效**：`server.tomcat.*` 仅由 `TomcatServletWebServerFactory` 读取并绑定；Gateway 使用 Netty，该绑定路径不会被触发，配置被静默忽略。

这构成官方"不能运行于 Servlet 容器"的直接实证：**配置存在，但无消费者**。

> 调整 Netty 的线程数 / 连接数需使用 `reactor.netty.*` 或 `spring.cloud.gateway.httpclient.*`，而非 `server.tomcat.*`。

#### ⑤ 术语对照

| 官方英文 | 中文术语 |
|---|---|
| `built on Spring Boot, Spring WebFlux, Project Reactor` | 技术底座：Boot + WebFlux + Reactor |
| `synchronous libraries ... may not apply` | 同步（阻塞）库不适用 |
| `requires the Netty runtime` | 依赖 Netty 运行时 |
| `does not work in a traditional Servlet Container` | 不能部署于传统 Servlet 容器 |
| `when built as a WAR` | 不能以 WAR 形式构建部署 |

#### ⑥ 结论

> **事件循环 + 非阻塞 IO → 禁止阻塞调用、不能部署于 Servlet 容器；xhs 网关 yml 中的 `server.tomcat.*` 即无效配置。**

---

### 1.1.3 Execution Model — `.../spring-cloud-gateway/how-it-works.adoc`

**英文原文**

> Clients make requests to Spring Cloud Gateway. If the Gateway Handler Mapping determines that a request matches a route, it is sent to the Gateway Web Handler. This handler runs the request through a filter chain that is specific to the request. The reason the filters are divided by the dotted line is that filters can run logic **both before and after** the proxy request is sent.
>
> All "`pre`" filter logic is executed. Then the proxy request is made. After the proxy request is made, the "`post`" filter logic is run.

**中文翻译**

> 客户端向 Spring Cloud Gateway 发起请求。如果网关的 **Handler Mapping** 判定请求匹配某条路由，请求就会被交给 **Gateway Web Handler**。这个 handler 会让请求穿过一条**专属于该请求**的过滤器链。图中过滤器被虚线分开，是因为过滤器**在代理请求发出之前和之后都能执行逻辑**。
>
> 所有 `pre` 过滤器的逻辑先执行；然后发起代理请求；代理请求发出之后，再执行 `post` 过滤器的逻辑。

#### ① 核心结论

过滤器链采用**责任链模式（Chain of Responsibility）**，过滤器对每次请求在代理转发前后各执行一次（pre / post），即**环绕（around）语义**。

#### ② 机制说明

**执行序列**：

```
① 入站（pre）：按 order 升序执行各过滤器的前置逻辑
② 代理请求：向下游服务发起真正的 HTTP 请求
③ 出站（post）：按 order 降序执行各过滤器的后置逻辑
```

入站与出站方向相反，构成**洋葱模型（onion model）**：进入时逐层深入，返回时逐层退出。

**与 AOP 的对应关系**：该模型与 Spring AOP 的**环绕通知（@Around）**同构：

| 责任链概念 | AOP 对应 |
|---|---|
| `chain.filter(exchange)` | `proceed()`（放行，执行后续链路） |
| `chain.filter(...)` 之前的代码 | 前置通知（before） |
| `.then(...)` 中的代码 | 后置通知（after） |
| 整段 `filter()` 方法 | 环绕通知（around） |

因此，**每个过滤器都可以选择只做 pre、只做 post、或两者都做**。

#### ③ 官方要点（3 步）

**第 1 步：路由匹配** —— `Gateway Handler Mapping` 判定请求匹配哪条路由

`HandlerMapping` 是 Spring WebFlux 的核心扩展点，职责为"依据请求定位处理器"。Gateway 通过实现该扩展点接入（即 `RoutePredicateHandlerMapping`，见 Lesson 18 / 23）。匹配依据为**谓词（Predicate）**（Lesson 04）。

**第 2 步：组装过滤链** —— `filter chain that is specific to the request`

注意限定语 `specific to the request`：**过滤器链是请求级而非全局唯一的**。其构成为"路由级 filter + 全局 GlobalFilter"的合并结果（Lesson 17）。

**第 3 步：三阶段执行** —— pre → proxy request → post

`pre` 全部执行完毕后才发起代理请求；代理请求返回后才执行 `post`。

#### ④ 术语对照

| 官方英文 | 中文术语 |
|---|---|
| `Gateway Handler Mapping` | 网关的处理器映射（负责路由匹配） |
| `determines that a request matches a route` | 判定请求与路由匹配 |
| `Gateway Web Handler` | 网关的 Web 处理器（过滤器链执行者） |
| `filter chain specific to the request` | 请求级过滤器链 |
| `the dotted line` | 图中 pre / post 的分界虚线 |
| `pre` / `post` | 前置（转发前）/ 后置（转发后） |
| `the proxy request is made` | 代理请求发起（转发动作本身） |

#### ⑤ 代码对照

`demo-gateway/src/main/java/com/myxhs/demo/gateway/filter/DemoGlobalFilter.java`：

```java
return chain.filter(mutated)          // ← 此行之前 = pre 段
        .then(Mono.fromRunnable(() -> {
            // ← 此处 = post 段
            log.info("[gateway] <-- status={} cost={}ms", status, costMs);
        }));
```

`chain.filter(...)` 即入站与出站的分界点：

- 其**上方**代码 = pre 逻辑
- 其 `.then(...)` **内部**代码 = post 逻辑

#### ⑥ 结论

> **过滤器链是责任链 + 环绕语义；`chain.filter(...)` 为 pre/post 分界点。**

---

## 1.2 (Merged)

> 原「Line-by-Line Breakdown」的逐句拆解**已并入 1.1.1 / 1.1.2 / 1.1.3**（避免重复）。
> 其中的 xhs 过滤器对应表见 1.1.1 要点 2。保留编号以防引用失效。

---

## 1.3 Two Flavors

```
Spring Cloud Gateway
├── Server 变体（完整网关：可独立部署，也可嵌入 Spring Boot 应用）
│     ├── WebFlux 版  spring-cloud-starter-gateway        ← 主流，xhs 用的这个
│     └── MVC 版      spring-cloud-starter-gateway-mvc    ← 4.2.0 才加，Servlet 栈
└── Proxy Exchange 变体（只能用在注解式 WebFlux/MVC 应用里，作为 handler 方法的一个参数）
```

- **Server** = 一个"网关服务"，自己就是应用 → xhs 的 `my-xhs-gateway` 是这种
- **Proxy Exchange** = 不是网关服务，只是让你在某个 Controller 方法里"顺便代理一下" → 与 xhs 无关，翻篇

### 1.3.1 主流程度对比（实测）

官方对 Proxy Exchange 的定性原文：

> **WARNING**: The following describes an **alternative style** gateway. **None of the Spring Cloud Gateway Server documentation applies to what follows.**

| 维度 | **Server** | Proxy Exchange |
|---|---|---|
| 官方定性 | 完整 API 网关 | **alternative style**（官方自述为替代风格） |
| 主源码量 | **229** 个 Java 文件（`spring-cloud-gateway-server`） | **4 + 4** 个（`-webflux` / `-mvc`），约 1/30 |
| Starter | `spring-cloud-starter-gateway` ✅ | ❌ 无 starter，需手动引入模块 |
| 官方文档 | **62 页**（24 页正文 + 38 页过滤器） | **1 页 / 80 行** |
| 生态适配 | Sentinel 网关适配、Nacos discovery locator、全部教程 | 基本没有 |
| 使用方式 | 配 yml / Java DSL，运行独立服务 | 在自有 `@RestController` 方法中注入 `ProxyExchange` 参数 |

Proxy Exchange 的官方示例（注意它就是一个普通 Controller 方法）：

```java
@RestController
@SpringBootApplication
public class GatewaySampleApplication {

    @Value("${remote.home}")
    private URI home;

    @GetMapping("/test")
    public ResponseEntity<?> proxy(ProxyExchange<byte[]> proxy) throws Exception {
        return proxy.uri(home.toString() + "/image/png").get();   // 就地代理
    }
}
```

**它不是网关，而是一个工具类**：在普通 Controller 中注入 `ProxyExchange` 参数即可对单个方法做转发；没有路由配置、没有过滤器链、没有全局入口。

### 1.3.2 结论

> **Server 是压倒性主流。** 业界语境中的"Spring Cloud Gateway"、面试中的"网关"、xhs-project 的网关，默认均指 Server 形态。Proxy Exchange 仅适用于"已有注解式应用、只需在个别接口就地转发"的边缘场景。

---

## 1.4 Hard Constraint: Why WebFlux Matters

官方那句硬约束（1.1.2）是整个 Gateway 设计的**钥匙**，由此推出 4 条后面反复出现的结论：

| # | 推论 | 会在哪一课变成坑 |
|---|---|---|
| 1 | 没有 Servlet 的"一请求一线程"模型 | 0.4.6 / Lesson 35（不能用 ThreadLocal） |
| 2 | 不能阻塞（阻塞会卡死 EventLoop） | Lesson 35 |
| 3 | 请求体是**流**，读一次就没了 | Lesson 28（请求体缓存） |
| 4 | 引入 `spring-boot-starter-web` 会冲突 | Lesson 02（起服务时） |

---

## 1.5 Official Glossary

> **Route**: The basic building block of the gateway. It is defined by an ID, a destination URI, a collection of predicates, and a collection of filters. **A route is matched if the aggregate predicate is true.**
>
> **Predicate**: This is a Java 8 Function Predicate. The input type is a Spring Framework `ServerWebExchange`. This lets you **match on anything from the HTTP request**, such as headers or parameters.
>
> **Filter**: These are instances of `GatewayFilter` that have been constructed with a specific factory. Here, you can **modify requests and responses before or after sending the downstream request**.

三个要点：

1. **Route = ID + 目标 URI + 谓词集合 + 过滤器集合** ← 这就是"路由四要素"的官方出处（Lesson 03）
2. 谓词是 **AND 聚合**的（`aggregate predicate is true`）→ 多个谓词全真才匹配（Lesson 04）
3. 过滤器**在转发前后都能改请求/响应** → pre/post（Lesson 05）

---

## 1.6 Two Official Pitfalls

> **NOTE**: URIs defined in routes **without a port** get default port values of **80 and 443** for the HTTP and HTTPS URIs, respectively.
>
> **WARNING**: **Any path defined on a route URI will be ignored.**

第二条非常反直觉：

```yaml
- id: demo
  uri: http://localhost:8081/abc     # ← 这个 /abc 会被直接丢掉！
  predicates:
    - Path=/provider/**
```

**想改路径只能用过滤器**（`StripPrefix` / `RewritePath` / `PrefixPath`）。这正是 xhs-demo 里必须写 `StripPrefix=1` 的原因，也是 Lesson 05 的核心。

---

## 1.7 Mapping to xhs-project

| 官方概念 | xhs-project 的实现 |
|---|---|
| API Gateway（唯一入口） | `my-xhs-gateway`，端口 19000 |
| route to APIs | 16 条路由 → 15 个业务服务 |
| security | `GatewayAuthFilter` + `HmacSignatureFilter` + `BodyCacheFilter` |
| monitoring/metrics | `RequestLogFilter`（TraceId 全链路）+ `GatewayMetricsConfig` |
| resiliency | `RateLimitFilter`（Sentinel，规则托管 Nacos） |
| （扩展）灰度/版本 | `TrafficColoringFilter` + `GrayRouteFilter` + `ApiVersionFilter` |
| Server 变体 / WebFlux 版 | ✅ 就是它 |
| Proxy Exchange / MVC 版 | ❌ 不用 |

---

## 1.8 Self-Test

1. API Gateway 作为"边界组件"，职责二分是哪两半？
2. 官方列举的**三类**横切关注点是什么？各对应 xhs-project 的哪些过滤器？
3. 两种 flavor 分别是什么？xhs 采用哪一种？
4. EventLoop 模型下"阻塞调用"的后果，与 thread-per-request 模型有何本质区别？
5. `chain.filter(...)` 在责任链 / 环绕语义中对应什么？

---

## 1.9 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「1.1.2 ② 没懂」），我会就地补进正文。

| # | 疑问 | 状态 |
|---|---|---|
| | | |

---

## Appendix: Official Docs Referenced

```
{源码树}/docs/modules/ROOT/pages/
├── intro.adoc                                    ← 1.1.1
├── spring-cloud-gateway/starter.adoc             ← 1.1.2
├── spring-cloud-gateway/how-it-works.adoc        ← 1.1.3
└── spring-cloud-gateway/glossary.adoc            ← 1.5
```
