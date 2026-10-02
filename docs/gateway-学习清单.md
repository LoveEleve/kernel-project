# Spring Cloud Gateway Learning Checklist v5.5

> 用途：学一课勾一课。**一次只学一课**，不贪多。
> 🎯 **最终产出**：`architecture-map.md` —— 面试时能讲出"有哪些对象 + 请求怎么流转"。
> 版本口径：以 **xhs-demo 实际运行的 SCG 4.1.5** 为准（Spring Cloud 2023.0.3 锁定）。
> ⚠️ 参考规划基于 4.2.2 / 2025.0.0，**机制一致但行号会漂**：按类名找，不按行号找。
>
> ## 🎯 v4 定位修正（2026-09-24 用户指定）
>
> | 维度 | 要求 |
> |---|---|
> | **面试方向** | **偏架构**（选型 / 边界 / 治理 / 高可用），**源码抓主干即可** |
> | **xhs-project** | 作为面试项目讲解 —— 不仅要能讲实现，还要能答"**哪里不满足生产**"（不足 / 遗漏 / 错误） |
> | **microsphere-gateway** | 纳入规划，作为"**SCG 扩展实践**"案例 |
>
> **v4 修正的 v3 缺口**：v3 的规划围着 xhs-project 转（xhs 用 Sentinel 限流、Feign 层降级），导致
> **Gateway 自带的一批能力被错误排除** —— 限流 / 熔断 / 重试 / 缓存 / 安全 / TLS / HTTP2 / 选型对比。
> 对架构面试这是硬伤（面试官问"网关怎么限流"，不能只答"我们用了 Sentinel"）→ **已补为 Stage 7**。
>
> **历史修正记录**：
> - v1：把 WebFlux/Reactor 当"前置理论"放最前 → 错
> - v2：Reactor 排在 Stage 4 → 仍错
> - v3：Reactor 提前到 Stage 3 ✓

---

## 0. 三层阅读约定（每课都按这个尺度）

| 层 | 内容 | 你要做的 |
|---|---|---|
| **结论** | 每课开头的"**要掌握的 N 条**" | **读懂 + 能复述** |
| **推导** | 中间的机制说明、实验 | 读懂即可，**不记** |
| **查阅** | 行号、API 细节、边缘场景 | **跳过**，用时回来查** |

> 🚫 **v4 硬规矩（2026-09-24 用户指定）**：**概念课（L01~L16）不出现源码**。
> 源码统一在 **Stage 5（L17~L30）** 讲，且只讲主干（能说清主链即可，不逐行）。
> 原因：概念阶段掺源码会打乱认知节奏（"我没学过源码，也不知道你什么时候讲过"）。 |

> ⚠️ 若某课**没有"要掌握的 N 条"开头**，说明我漏了，直接指出。
> 🆕 **Self-Test 由我直接给出参考答案**（2026-09-25 用户指定："不要等我来回答"）—— 写在每课 `x.16.1 参考答案`。

---

## 1. 课程正文索引

| 文件 | 内容 | 状态 |
|---|---|---|
| `docs/gateway/L00-prerequisites.md` | **Lesson 00** 前置最小包（0.1 ~ 0.10） | ✅ 已讲 |
| `docs/gateway/L01-what-is-gateway.md` | **Lesson 01** 网关是什么（官方定位） | ✅ 已讲 |
| `docs/gateway/L05-filters.md` | **Lesson 05** 过滤器（8 个 + 洋葱模型 + 路由/全局过滤器辨析 + Java DSL） | ✅ 已讲 |
| `docs/gateway/L06-timeout-and-route-metadata.md` | **Lesson 06** 超时与路由 metadata（两层+单位坑 / 值怎么定 / metadata vs args / 框架只读 3 key） | ✅ 已讲 |
| `docs/gateway/L07-cors.md` | **Lesson 07** CORS（处理在 HandlerMapping 层 / 两种配置 / 预检坑 + 解药，含 6 组实测） | ✅ 已讲 |
| `docs/gateway/L08-actuator-routes-ops.md` | **Lesson 08** Actuator（两视图 / 写完必须 refresh / 增删边界 / globalfilters order 表，含实测） | ✅ 已讲 |
| `docs/gateway/L09-reactive-streams.md` | **Lesson 09** Reactor 介绍 + 契约与模型（**9.1~9.4 = 大纲第 1 项：Reactor 基本介绍**；9.5~9.17 = 4 接口/信号/惰性/背压/vs CF/子项目/Lab） | ✅ 已讲 |
| `docs/gateway/L10-reactor-operators.md` | **Lesson 10** Reactor 算子（12 个速查表 / map vs flatMap / then vs doOnXxx / 兜底 / 两个坑） | ✅ 已讲 |
| `docs/gateway/L11-backpressure-and-scheduling.md` | **Lesson 11** 背压与调度（4 种背压策略 / `Schedulers` 三种 / `publishOn` vs `subscribeOn` / `ParallelFlux`，含 4 实验 + 实测） | ✅ 已写 |
| `docs/gateway/L12-context-sinks-debug.md` | **Lesson 12** Context / Sinks / 调试（Context 三规则 + vs attributes 分工 / Sinks 三种口味 / log·checkpoint，含 3 实验 + 实测） | ✅ 已写 |
| `docs/gateway/L14-custom-filter-factory.md` | **Lesson 14** 自定义 `GatewayFilterFactory`（三段式 / 命名规则 / `shortcutFieldOrder` 必写 / 与 GlobalFilter 取舍，含破坏性实测） | ✅ 已写 |
| `docs/gateway/L15-custom-predicate-factory.md` | **Lesson 15** 自定义 `RoutePredicateFactory`（谓词管匹配 / 同三段式 / 谓词禁阻塞，含 4 用例实测） | ✅ 已写 |
| `docs/gateway/L17-autoconfiguration-wiring.md` | **Lesson 17** 自动装配（91 个 @Bean 归纳成四组 / B 组装配链 / `/actuator/beans` 实测 / 网关不注册 Nacos 的事故） | ✅ 已写 |
| `docs/gateway/L18-webflux-request-pipeline.md` | **Lesson 18** WebFlux 请求管线（8 站 / 三份名单 / 重名坑 / `NO_OP`；含日志逐行 + 断点地图 + 双实测） | ✅ 已写 |
| `docs/gateway/L19-route-definition-sources.md` | **Lesson 19** 来源链（两接口家族 / 四来源 / Composite / "yml 删不掉"真相；含 discovery+Redis 实测） | ✅ 已写 |
| `docs/gateway/L20-factories-yml-to-object.md` | **Lesson 20** 工厂机制（5 步绑定链 / `ShortcutType` / 最后一跳；支撑 L14/L15） | ✅ 已写（**参考级**） |
| `docs/gateway/L21-route-definition-to-route.md` | **Lesson 21** Definition→Route 编译（两级模型 / `and` 组合 / 编译期排序；已深度 review） | ✅ 已写 |
| `docs/gateway/L22-route-cache-two-levels.md` | **Lesson 22** 两级缓存（定义 vs 运行 / 刷新时序 / 实测 curl 序列；已深度 review×2） | ✅ 已写 |
| `docs/gateway/L23-route-matching.md` | **Lesson 23** 路由匹配（`lookupRoute` / 四组真实匹配实验 / `filterWhen` 并发度实证） | ✅ 已写 |
| `docs/gateway/L24-predicate-family-source.md` | **Lesson 24** 谓词家族（三件套 + 四代表 + 三组真实实验：Method / Weight 8:2 / RemoteAddr） | ✅ 已写 |
| `docs/gateway/L25-filter-chain-assembly.md` | **Lesson 25** 过滤器链装配（`loadFilters` / `i+1` / `routeFilterMap`） | ✅ 已写 |
| `docs/gateway/L26-filter-chain-execution.md` | **Lesson 26** 链执行（洋葱时序 / `defer` / 重试重放） | ✅ 已写 |
| `docs/gateway/L27-forwarding-four-steps.md` | **Lesson 27** 转发四步（最核心；含三组实测） | ✅ 已写 |
| `docs/gateway/L28-request-body-once.md` | **Lesson 28** body 只能读一次（缓存全链 / 谓词支线） | ✅ 已写 |
| `docs/gateway/L29-sticky-session.md` | **Lesson 29** 粘性会话（写/读两个半边 / 默认关） | ✅ 已写 |
| `docs/gateway/L30-dynamic-route-refresh.md` | **Lesson 30** 动态刷新（触发源 5 种 / scoped / 在途不中断；含两组实测） | ✅ 已写 |
| `docs/gateway/L16-custom-loadbalancer.md` | **Lesson 16** 自定义负载均衡（`lb://` 链路 / SCLB 依赖坑 / 染色 vs 选实例 / **生产做法含 microsphere 实战 + Gateway 两坑**，含三模式实测 + 演示脚本） | ✅ 已写（582 行） |
| `docs/gateway/infra-nacos.md` | **基础设施笔记** 本地 Nacos 部署（2.2.3 standalone + 4 个坑 + 复用命令） | ✅ 已部署运行 |
| `docs/gateway/architecture-map.md` | **★ Stage 5 产出物** 架构全景（四组组件 / 请求流转 / 三张面试图 / 速记卡） | 📌 随 L17~L30 校验 |
| `docs/gateway/production-gateway-capabilities.md` | **L40/L45 素材** 生产级网关能力全景（大厂对比 + 8 类能力 + 性能 + Sentinel 选型） | ✅ 已写 |
| `docs/gateway/L43-microsphere-gateway-extension.md` | **L43 素材** microsphere-gateway 端点级路由扩展（含简历价值判断） | ✅ 已写 |
| `docs/gateway/concept-index.md` | 概念索引（标注 前向/后向/外部） | 持续维护 |

---

## 2. Gateway 基座与学习边界（回答"该学什么"）

实测依赖树（xhs-demo，SCG **4.1.5**）：

| 层 | 组件 | 版本 | 要不要专门学 | 在哪学 |
|:--:|---|---|---|---|
| ⑧ | `spring-cloud-gateway-server` | 4.1.5 | ✅ **主角** | 全部 Lesson |
| ⑦ | `spring-cloud-commons` | 4.1.4 | 🟡 只用 LoadBalancer / Discovery 抽象 | L16 |
| ⑥ | `spring-boot` | 3.2.5 | 🟡 只用自动配置 | L17 |
| ⑤ | `spring-webflux` | 6.1.6 | ❌ **不用专门学** | Gateway 只用 3 个扩展点 |
| ④ | `reactor-core` | 3.6.5 | 🔴 **必须补** | **L10**（约 10 个算子） |
| ③ | `reactor-netty-http` | 1.1.18 | ❌ **不用专门学** | 仅 L34 连接池用一点 |
| ② | `netty` | 4.1.109 | ❌ 已入门 | — |
| ① | OS 内核（epoll） | — | ❌ | 原理线「网络 / OS内核」 |

**三条结论**：

1. **不要"学框架"，要"补算子"** —— 唯一必须补的是 **Reactor 的 `Mono`/`Flux` 算子**（L10）
2. `spring-webflux` 完整范围含 `DispatcherHandler`/`HandlerAdapter`/`ResultHandler`/codecs/multipart/session/CORS/security…… **Gateway 只碰 3 个扩展点**（`HandlerMapping` / `WebHandler` / `ServerWebExchange`），学完 90% 闲置
3. `reactor-netty` 只是 Netty 的响应式外壳，Gateway 仅在**连接池配置**处用到

### 请求全链路图（一个请求走过哪些"地方"→ 哪课讲）

> 用途：**规划完整性自检** —— 每个环节都必须有课覆盖，**空缺就是规划漏洞**（用户 2026-09-25 提出）。

**【装配期】启动时（`GatewayAutoConfiguration` → L17）**

```
① 来源链：yml / 内存 / 注册中心 / Redis → CompositeRouteDefinitionLocator  → L19
② 工厂机制：yml 参数 → Config 对象（_genkey_ / shortcutFieldOrder / Binder）→ L20
③ 编译：RouteDefinition → Route（谓词 and + 过滤器）                       → L21
④ 两级缓存：CachingRouteDefinitionLocator / CachingRouteLocator            → L22
```

**【运行期】一次请求**

```
客户端
 │
 ▼ ⓪ 入站连接（TCP/TLS 握手：客户端 → 网关）              → ⚠️ 无专课（靠前置 0.4.x 概念）
 ▼ ① Netty Server（reactor-netty）+ EventLoop             → L00·0.4 / L35（阻塞 fatal）
 ▼ ② WebFilter 链（WebFlux 层）                           → L07（CORS）/ L39（Security）
 ▼ ③ DispatcherHandler（WebFlux）
 ▼ ④ 路由匹配（RoutePredicateHandlerMapping）             → L04 / L23
 │      └─ 谓词工厂（Path/Method/Weight/... 结构）         → L24
 ▼ ⑤ 过滤器链
 │      ├─ 装配（FilteringWebHandler：Global+Route 合并）  → L05 / L25
 │      └─ 跑链（DefaultGatewayFilterChain 递归）          → L26
 ▼ ⑥ 转发四步                                             → L27
      ├─ ⑥a RouteToRequestUrlFilter（拼 URL）             → L27
      ├─ ⑥b ReactiveLoadBalancerClientFilter（lb://）     → L27 / L46（SCLB）
      ├─ ⑥c NettyRoutingFilter（HttpClient）
      │      ├─ 【连接池】ConnectionProvider（按目标分池）   → ✅ **L34**
      │      ├─ 3 个超时（acquire / connect / response）    → ✅ L06
      │      ├─ TLS / 代理 / wiretap                      → L39（TLS）；⚠️ 代理 / wiretap 无专课
      │      └─ 出站连接（Netty Client）
      └─ ⑥d NettyWriteResponseFilter（响应写回）           → L27
 ▼ ⑦ 响应返回客户端（响应过滤链反向）                       → L27 / L38（Body 改写）
```

**覆盖审计（2026-09-25）**：

| 环节 | 状态 |
|---|---|
| 【装配】来源链 | ✅ **L19** |
| 【装配】工厂机制 | ✅ **L20** |
| 【装配】定义→Route | ✅ L21 |
| 【装配】两级缓存 | ✅ **L22** |
| ⓪ 入站连接 | ⚠️ 薄（无专课） |
| ① Netty Server / EventLoop | ✅ L00·0.4 + L35 |
| ② WebFilter 层 | ⚠️ 薄（只有 L39 Security） |
| ③ 路由匹配 | ✅ L04 + L23 + **L24**（谓词家族） |
| ④ 过滤器链 | ✅ L05 + L25 + **L26**（跑链） |
| ⑤a 拼 URL | ✅ L27 |
| ⑤b 负载均衡 | ✅ L27 + L46 |
| ⑤c 连接池 | ✅ **L34** |
| ⑤c 超时 | ✅ L06 |
| ⑤c TLS | ✅ L39 |
| ⑤c 代理 / wiretap | ⚠️ 薄（无专课） |
| ⑥ 响应写回 | ✅ L27 + L38 |
| ⑦ 返回客户端 | ✅ L27 |

**3 个薄环节**：⓪ 入站连接 / ② WebFilter 层 / ⑤c 代理+wiretap —— 补课与否待定。

> 术语查不到时 → `docs/gateway/L00-prerequisites.md` 的 **0.10 术语补遗**

---

## 3. Official Docs Map (24 pages, must-read only)

| 页面 | 讲什么 | 优先级 |
|---|---|---|
| `intro.adoc` | **官方定位原文**（是什么/两大 flavor） | 🔴 L01 |
| `spring-cloud-gateway/glossary.adoc` | **官方词汇表**（Route/Predicate/Filter 定义） | 🔴 L01 |
| `spring-cloud-gateway/how-it-works.adoc` | 执行模型（pre → proxy → post） | 🔴 L01 |
| `spring-cloud-gateway/starter.adoc` | 依赖引入 + **硬约束**（Netty / 不能 WAR） | 🔴 L01 |
| `spring-cloud-gateway/configuration.adoc` | 路由配置方式总览 | 🟡 L03 |
| `spring-cloud-gateway/fluent-java-routes-api.adoc` | Java DSL | 🟡 L03 |
| `spring-cloud-gateway/request-predicates-factories.adoc` | 全部谓词 | 🟡 L04 |
| `spring-cloud-gateway/gatewayfilter-factories.adoc` | 全部过滤器 | 🟡 L05 |
| `spring-cloud-gateway/http-timeouts-configuration.adoc` | 超时（全局 / 路由级） | 🟡 L06 |
| `spring-cloud-gateway/cors-configuration.adoc` | CORS | 🟡 L07 |
| `spring-cloud-gateway/actuator-api.adoc` | Actuator 运维端点 | 🟡 L08 |
| `spring-cloud-gateway/global-filters.adoc` | GlobalFilter 有哪些 | 🟡 L13 |
| `spring-cloud-gateway/developer-guide.adoc` | **怎么写自定义过滤器/谓词** | 🔴 L14~L15 |
| `spring-cloud-gateway/http-client.adoc` | 底层 HttpClient / 连接池 | 🟡 L34 |
| `spring-cloud-gateway/troubleshooting.adoc` | 官方排错清单 | 🟡 遇到再看 |
| `spring-cloud-gateway-proxy-exchange.adoc` | Proxy Exchange flavor | ✗ 翻篇 |

> 位置：`{源码树}/docs/modules/ROOT/pages/`

---

## 4. Glossary

| 术语 | 一句话人话 |
|---|---|
| **网关（Gateway）** | 所有请求的统一入口，负责转发 + 在转发前后做加工 |
| **反向代理** | 客户端以为在跟一个服务器说话，其实是它在替你转发（详情见 L00 · 0.1） |
| **Route（路由）** | 一条转发规则："什么样的请求 → 转发到哪里" |
| **Predicate（谓词）** | 路由的"匹配条件"，如 Path=/api/user/** |
| **Filter（过滤器）** | 请求转发前/后要做的加工动作，如加请求头 |
| **RouteDefinition** | yml 里写的路由（**配置模型**，还没变成可用的路由） |
| **Route** | 运行时真正用来匹配的路由（**运行模型**） |
| **RouteLocator** | 路由的"来源"，负责提供所有可用路由 |
| **Exchange** | 一次请求-响应的完整上下文（WebFlux 里的 `ServerWebExchange`） |
| **Mono / Flux** | Reactor 的"未来会有一个值 / 一串值"（类似 CompletableFuture） |
| **WebFlux** | Spring 的响应式 Web 框架，与 MVC 并列（详情见 L00 · 0.6） |
| **lb://** | 表示"这个地址去服务注册中心查"，如 `lb://my-xhs-user` |
| **Channel** | Netty 对"一条连接"的本地表示（详情见 L00 · 0.4.3） |
| **Reactor Netty** | Netty 的响应式外壳，提供 `HttpServer`/`HttpClient`（详情见 L00 · 0.4.4） |
| **网关线程** | 业务线程 4 种，只有 `reactor-http-epoll-*` 处理请求（详情见 L00 · 0.4.5） |

---

## 5. Learning Path

### Stage 0 · Prerequisites（前置最小包）
> 📄 正文：`docs/gateway/L00-prerequisites.md`（0.1 ~ 0.10）
> 🔴 **要掌握的 4 条**（0.4.0）：①两边 IO 都是非阻塞 ②等待由内核负责 ③Channel 绑 EventLoop ④Gateway 是双面的
- [x] 0.1 反向代理 —— MDN + Tomcat proxy-howto
- [x] 0.2 Servlet / Servlet 容器 —— Jakarta Servlet Javadoc + Tomcat 架构
- [x] 0.3 阻塞 vs 非阻塞 IO —— WebFlux《Define Reactive》
- [x] 0.3.1 Reactive Streams —— OpenJDK `java.util.concurrent.Flow`（4 接口 / 7 方法）
- [x] 0.4 EventLoop + 「阻塞」的准确含义 —— Tomcat 官方 `requires a thread for the duration of that request`
- [x] 0.4.2 等待时谁在干活 —— 内核负责等待（`selector.select`），回调仍在 EventLoop
- [x] 0.4.3 Channel 归属 —— `ss -tnp` 实测两条 TCP 连接，网关各持一端
- [x] 0.4.4 Reactor Netty —— WebFlux 与 Netty 之间的传输层
- [x] 0.4.5 网关进程的线程清单 —— 业务线程 4 种（只有 `reactor-http-epoll-*` 处理请求）；一次请求全程单线程；16 线程"一次全起"根因 = `ColocatedEventLoopGroup` 构造器
- [x] 0.4.6 `Mono` 的底层 —— `ChannelFuture` + `addListener`（`FutureMono.doSubscribe` 源码实证）；colocation 保证"发起线程 = 回调线程"
- [x] 0.4.7 实战案例 —— RV33 vs RV34：阻塞式 Redis 卡在 EventLoop（含修复 + 提交 `0607103b`）
- [x] 0.4.8 boundedElastic 完整拆解 —— 槽位弹性（0~160 个单线程槽位）/ 引用计数空闲判定 / Worker 串行语义
- [x] 0.5.1 Mono 与 Flux —— 0..1 / 0..N、惰性（订阅才执行）、Gateway 里的 `Mono<Void>`
- [x] 0.5 命令式 vs 响应式 —— WebFlux《Concurrency Model》
- [x] 0.6 Spring WebFlux —— 定义 / 面试答法 / 3 条实测特征
- [x] 0.10 术语补遗 —— 11 个术语收口

> ✅ **v5.5 已补（对齐外部大纲 15~19 项）**
> - **0.4.4 ⑦ 基本使用**：`HttpServer` / `HttpClient` 最小例子（+ **⑧ 附录**：`TcpServer`/`TcpClient` 层次关系）
>   —— demo：`reactor-lab` **Ex01 / Ex02**（已跑通）
> - **0.6.4 基本使用**：WebFlux 最小例子（不引 Spring Boot）—— demo：`reactor-lab` **Ex03**（已跑通）
> - **0.6.5 划界已改判**：原写"`DispatcherHandler` 不用学" → **请求管线见 L18**

### Stage 1 · Observation（不改代码，先看现象）
> 📄 正文：`docs/gateway/L01-what-is-gateway.md`
- [x] **L01 What Is Gateway** —— 官方定位三段原文 + 逐句拆解 + 两大 flavor
      🔴 **要掌握的 4 条**（1.1.0）：①两半职责（route + cross-cutting）②三类横切（security/metrics/resiliency）③两种 flavor ④硬约束（Netty、不能 WAR）
- [x] **L02 Run It — Direct vs Through Gateway**
      【动手】起 `demo-provider` + `demo-gateway`，两次 curl 对比 ✅ 已做（对比了直连/走网关的日志与响应）
      【工程门槛】会看启动日志确认路由加载成功；知道端口冲突怎么改
      🔴 **要掌握的 1 条**：走网关后，路径被改写、header 变多

### Stage 2 · Configuration（改 yml 就能玩）
- [x] **L03 Route Four Elements**（id / uri / predicates / filters）
      【动手】新增一条路由，把 `/hello/**` 转发到 provider ✅ 已做
      🔴 **要掌握的 1 条**：`uri` 写死 vs `lb://` 的区别 ✅ 已达成
      【补充已讲】路由概念（一条转发规则）+ 路由表（`/actuator/gateway/routes` vs `/routedefinitions`）
- [x] **L04 Predicates**（先记 5 个：Path / Method / Header / Host / Weight；⚠️ 官方实际 **14 种**）
      【动手】加 `Method`、`Header` 谓词，观察不匹配时返回什么
      🔴 **要掌握的 1 条**：多个谓词是 **AND** 关系 ✅ 已达成
      【已讲】路由粒度（按业务域）、Weight 的 group、matchTrailingSlash、"按路径/方法分流"
- [x] **L05 Filters**（先记 8 个：StripPrefix / PrefixPath / RewritePath / AddRequestHeader / AddResponseHeader / SetStatus / RedirectTo / RemoveRequestHeader）
      📄 **正文**：`docs/gateway/L05-filters.md`
      【动手】加 `AddRequestHeader` / `RewritePath` / `SetStatus`，看下游收到什么
      🔴 **要掌握的 1 条**：`StripPrefix=1` 剥掉的是"第一段路径"（**段数**，不是字符数）
      【已移出】`Retry` → **L37**（与熔断一起讲）
      【v5 补】**配置绑定体系**：`ConfigurationService` + `ShortcutConfigurable` —— 解释 yml 的**短路语法**（`Path=/user/**` 简写）与完整 Map 写法如何统一绑定
      【v5 补·过滤器第三种】**`WebFilter`（Spring WebFlux 层）≠ Gateway 的 Filter**：
      执行顺序 `客户端 → WebFilter 链 → RoutePredicateHandlerMapping → FilteringWebHandler(Gateway Filters) → 转发`
      WebFilter **在路由匹配之前**（能拦到未匹配路由的请求）；Spring Security 就在这层 → 见 L39
- [x] **L06 Timeouts & Route Metadata**（★v5 二次修正：只讲两个具体东西，删抽象框架）
      📄 **正文**：`docs/gateway/L06-timeout-and-route-metadata.md`
      🔴 **要掌握的 4 条**：
      ① **超时两层 + 单位坑**：全局 `response-timeout` = **Duration**（`5s`）｜路由级 `metadata.response-timeout` = **毫秒**（`500`）；
         写错 → **静默回退全局值**（不报错）；路由级 `-1` = 禁用全局超时
      ② **超时值怎么定**：铁律 **网关超时 < 上游超时**（否则网关 504 而上游继续白干）；全局安全值 / 慢接口单独调大 / 流式调大或禁用
      ③ **metadata 是什么**：**路由级自由 kv**（不属于任何过滤器）—— `args` 喂组件、`metadata` 是路由扩展位
      ④ **框架原生只读 3 个 key**：`response-timeout` / `connect-timeout` / `cors`；**其它 key 必须自己写过滤器读，否则死配置**
      【已移出】"死配置 4 种"审计框架 → **L41**（L06 只留指针）
      【别混淆】路由 metadata（Route 对象上）≠ 实例 metadata（注册中心实例上；L42 zone / L43 we:// 读的是后者）
      【精确补充】配置 = **2 个位置 / 3 种**：① 全局配置值 ② 全局默认过滤器（`default-filters`，**叠加**语义，不是覆盖）③ 路由级；判断口诀见 6.6
- [x] **L07 CORS**（★v5 加强：覆盖"处理位置 / 两种配置 / 预检 / 演示"）
      📄 **正文**：`docs/gateway/L07-cors.md`（含 A/B/C/D 共 6 组实测数据）
      🔴 **要掌握的 3 条**：
      ① **CORS 处理发生在 WebFlux 层**（`CorsWebFilter` + `spring-web` 的 `DefaultCorsProcessor`）—— **不在 Gateway 的过滤器链里**
      ② **两种配置**：全局 `spring.cloud.gateway.globalcors` vs **路由级 `metadata.cors`**（L06 说的第 3 个原生 key）
      ③ WebFlux 下必须用 `reactive` 包的 `CorsConfigurationSource`（用成 Servlet 包的会**静默不生效**）
      【动手】发一个带 `Origin` 头的请求看响应头；再发 `OPTIONS` 预检请求
      【要能讲清】预检（`OPTIONS`）为什么存在、哪些请求会触发
      【★ 架构答法】**同源 > CORS** —— 前端用 `/api` 相对路径 + 代理 = 同源 = 不需要 CORS
      【★ nginx 对比】nginx **不懂 CORS 语义**（只会无脑加头 + 短路）vs Gateway **内建规范**（会 403）；
         **只在最外层处理一次**；两层都配 → 重复头 → 浏览器报错
      【掌握边界】3 条 + 架构答法 + nginx 对比；`DefaultCorsProcessor` 源码、CORS 头细节、预检完整规则 → **查阅级**
- [x] **L08 Actuator — Inspect & Dynamically Change Routes**
      【端点清单】`/actuator/gateway/` 下共 12 个（源码 `AbstractGatewayControllerEndpoint` + `GatewayControllerEndpoint`）：
      | 端点 | 方法 | 作用 |
      |---|---|---|
      | `/routes` | GET | **运行态**路由表（Route 对象） |
      | `/routedefinitions` | GET | **配置态**路由定义（RouteDefinition 文本） |
      | `/routes/{id}` | GET | 单条路由（运行态） |
      | `/routes/{id}/combinedfilters` | GET | ⚠️ **名字叫"合并"，实际只返回路由级 filter** —— 官方源码有 `// TODO: missing global filters`（未合并全局过滤器） |
      | `/routes` `/routes/{id}` | POST | 新增 / 更新路由 |
      | `/routes/{id}` | DELETE | 删除路由 |
      | `/refresh` | POST | 刷新路由 |
      | `/globalfilters` | GET | 全局过滤器清单 |
      | `/routefilters` | GET | 路由过滤器工厂清单 |
      | `/routepredicates` | GET | 谓词工厂清单 |
      📄 **正文**：`docs/gateway/L08-actuator-routes-ops.md`（实测：状态码 / 两视图 JSON 对照 / 增删边界 / order 表）
      【动手】curl 上面每个 GET；再动态增删一条
      🔴 **要掌握的 4 条**：
      ① **两个视图**：**配置态** `/routedefinitions`（yml 形态，含 `_genkey_0`）vs **运行态** `/routes`（编译后：predicate 一行串 + filters 串列表）
         —— 两者对不上 = 大概率**没 refresh**（排障第一反应）
      ② **写完不生效**：`POST /routes/{id}` 返回 201 **但不发布刷新事件**；`CachingRouteLocator` **只监听 `RefreshRoutesEvent`**
         → **必须再调 `POST /actuator/gateway/refresh`**（删除同理）
      ③ **增删边界**（实测）：只能动**内存 repo** 里的路由 —— Actuator 加的 `DELETE` **200**；**yml 定义的 `DELETE` → 404**；
         `POST` 是 `put`（**同名覆盖**）
      ④ **内存 repo 的代价**：**重启丢** + **多实例不一致** → 生产走配置中心 / Redis（L44 / L49）
      【顺带福利】`/globalfilters` 返回 **order 表**（实测：`RemoveCachedBodyFilter`=-2147483648 / `NettyWriteResponseFilter`=-1 /
         `RouteToRequestUrlFilter`=10000 / `NoLoadBalancerClientFilter`=10150 / `NettyRoutingFilter`=MAX）
         —— 面试问"转发四步 order"就用它查（不用背）
      【已验证坑】`/routes/{id}/combinedfilters` 源码有 `// TODO: missing global filters` → 实测只返回 `route.toString()`，**不是真的"合并"**
      【已定论】`InMemoryRouteDefinitionRepository` 由 **Spring 自动创建**（`@ConditionalOnMissingBean`）→ **不用自己定义**就能 POST/DELETE
      【架构问题】生产该不该开这些端点？（运维便利 vs 安全风险；怎么折中）

### Stage 3 · Reactive Foundation（★v5.5 重构：**1 课 → 4 课**，补全基础）
> 依据：2026-09-25 用户指出"**响应式基础太薄、要从 0 开始**"（对照外部课程大纲 21 项，我们原先只覆盖 3 项）。
> 口径：**讲清模型与契约，不逐行源码**。
> **大纲覆盖对照**：1~5（框架介绍/子项目/生产者消费者/Subscription）→ **L09**；6（背压）→ **L11**；7（Flux vs Mono）→ **L09**；
> 8/9（map/flatMap、publishOn/subscribeOn）→ **L10/L11**；10（just/defer）→ L10；11（vs CompletableFuture）→ **L09**；
> 12（ParallelFlux）→ **L11**；13（Sinks）/14（Context）→ **L12**。
> （15~18 Reactor-Netty 的**基本使用** → 前置 `L00-prerequisites.md` 0.4.4；**底层原理**不学；19~21 WebFlux 使用 → 0.6，
> WebFlux **请求管线**作为源码课 **L18**；WebFlux/DispatcherHandler **逐行源码**不学）

- [x] **L09 Reactive Streams & Reactor Model**（契约与模型）★新增
      📄 **正文**：`docs/gateway/L09-reactive-streams.md`（**9.1~9.4 = 大纲第 1 项「Reactor 基本介绍」**：官方定位 / 例子先行 / 3 个价值 / 生态位置图 / 7 个核心词；9.5~9.17 = 契约与模型 + 子项目 + Lab）
      🔴 **要掌握的 5 条**：
      ① **Reactive Streams = 4 个接口的规范**：`Publisher` / `Subscriber` / `Subscription` / `Processor`
      ② **信号流**：`onSubscribe` → `onNext*` → (`onComplete` | `onError`)，**终态只能有一个**
      ③ **订阅才发生**（惰性）：返回 `Publisher` ≠ 执行；`just` 立即求值 vs `fromCallable`/`defer` 延迟
      ④ **背压的本体是 `request(n)`**（消费者驱动，拉模型）—— 规范存在的根本原因
      ⑤ **`Flux` = 0..N，`Mono` = 0..1，`Mono<Void>` = 只关心"完成"**；**vs `CompletableFuture`** 的 6 个差异（惰性/背压/取消/多次/组合/线程）
      【子项目一览】reactor-core（全程用）/ reactor-netty（传输层）/ reactor-test（`StepVerifier`）/ 其余不碰

- [x] **L10 Reactor Operators**（算子，12 个）—— 原 L09
      📄 **正文**：`docs/gateway/L10-reactor-operators.md`（**从你亲手写的 `DemoGlobalFilter` 逐行拆解**）
      `Mono`/`Flux` 创建、`map`、`flatMap`、`then`、`doOnNext`、`doOnSuccess`、`doOnError`、`fromCallable`、`defaultIfEmpty`、`publishOn`
      🔴 **要掌握的 5 条**：
      ① **惰性 + 框架替你订阅**：返回 Mono 不执行；**不要自己 subscribe**（会脱离链）
      ② **`map` vs `flatMap`**：`map` 同步转换 / `flatMap` 异步展平（`T→Mono<R>`，不套娃）
      ③ **`then`**：等前面完成、**丢弃值**接下一个 → 这就是**洋葱模型的 post 阶段**（记耗时的写法）
      ④ **`publishOn` vs `subscribeOn`**：谁在哪个线程跑 → **阻塞必须配 `boundedElastic`**（L35 的根据）
      ⑤ **`just` vs `defer`/`fromCallable`**：`just` **立即求值**（最容易踩）、`defer` 订阅时才求值
      【学会标准】能读懂 `chain.filter(exchange).then(Mono.fromRunnable(...))` 并说清 pre/post 两阶段
      【不必学】`Schedulers` 内部实现、`zip`/`merge`/`window` 组合算子、Reactor 源码

- [x] **L11 Backpressure & Scheduling**（背压与调度）★新增
      📄 **正文**：`docs/gateway/L11-backpressure-and-scheduling.md`（含 4 个实验 + 实测数据 + Self-Test 参考答案）
      🔴 **要掌握的 3 条**：
      ① **背压策略**：`BUFFER` / `DROP` / `LATEST` / `ERROR`（`onBackpressureBuffer/Drop/Latest`）—— 生产者快了怎么办
      ② **`Schedulers` 三种**：`parallel`（CPU 密集）/ `boundedElastic`（**阻塞逃生舱**）/ `single`（串行）
      ③ **`publishOn` vs `subscribeOn`** 的精确语义 + **`ParallelFlux`**（`parallel()` + `runOn()`，何时用）
      【Gateway 视角】网关的转发是"一进一出 + 流式直通" → 背压几乎不触发；**真正要背压的场景是 SSE/大文件/`Flux` 扇出**

- [x] **L12 Engineering: Context / Sinks / Debug**（工程化三件套）★新增
      📄 **正文**：`docs/gateway/L12-context-sinks-debug.md`（含 3 实验 + 实测 + Self-Test 参考答案）
      🔴 **要掌握的 3 条**：
      ① **`Context`**（响应式上下文）：**跨链传递数据**，替代"想用 ThreadLocal"的场景；写法 `contextWrite` / 读 `deferContextual`；
         **与 `exchange.getAttributes()` 的分工**（一个是 Reactor 链级、一个是请求级）
      ② **`Sinks`**：手工往流里推数据（`Sinks.many()` / `Sinks.one()`）—— 什么时候需要（把回调式 API 变成流）
      ③ **调试**：`log()` / `Hooks.onOperatorDebug()` / `checkpoint()`（响应式的堆栈为什么难读）

### Stage 4 · Coding（写 Java）
- [x] **L13 Custom GlobalFilter** —— `demo-gateway/.../filter/DemoGlobalFilter.java` 已完成
      🔴 **要掌握的 1 条**：请求级数据用 `exchange.getAttributes()`，不是 ThreadLocal
- [x] **L14 Custom GatewayFilterFactory** ★分水岭
      📄 **正文**：`docs/gateway/L14-custom-filter-factory.md`（含完整代码 + yml + **实测** + 破坏性实测）
      【已完成】`demo-gateway/.../filter/DemoHeaderGatewayFilterFactory.java` + yml 配置 `- DemoHeader=X-Demo-Factory, hello-from-my-factory`（实测：下游 echo 收到 `x-demo-factory`）
      🔴 **要掌握的 3 条**：
      ① **三段式**：`Factory`（造）→ `Config`（收参数）→ `GatewayFilter`（干活）
      ② **命名规则**：类名去掉 `GatewayFilterFactory` = yml 名；类必须是 **Spring Bean**
      ③ **`shortcutFieldOrder()` 必写**：不写 → 占位 key 不映射 → Config 为 null → **请求 HTTP 500（NPE）**（破坏性实测）
      【选择】GlobalFilter（全路由）vs GatewayFilterFactory（按路由配参数）
- [x] **L15 Custom RoutePredicateFactory**
      📄 **正文**：`docs/gateway/L15-custom-predicate-factory.md`（含完整代码 + yml + 4 用例实测 + 破坏性对照说明）
      【已完成】`demo-gateway/.../predicate/DemoHeaderRoutePredicateFactory.java` + 路由 `demo-predicate-lab`（实测：带头 200 / 不带头 404 / 值不对 404）
      🔴 **要掌握的 3 条**：
      ① **三段式同 L14**，差别只有：基类 `AbstractRoutePredicateFactory` + `apply()` 返回 **`Predicate<ServerWebExchange>`**
      ② **谓词管"匹配"、过滤器管"加工"**；谓词 false → 路由不匹配（可能 404）；多个谓词 **AND**
      ③ ⚠️ **谓词里不能阻塞/远程调用**（匹配阶段跑在 EventLoop 上，每请求都要跑）
- [x] **L16 Custom LoadBalancer (Gray / Zone)**
      📄 **正文**：`docs/gateway/L16-custom-loadbalancer.md`（讲解式：现象→链路→坑→动手→实测→概念→**生产做法**）
      🔧 **演示脚本**：`bash demo-gateway/scripts/l16-demo.sh`（依次跑 none/gray/zone 三模式）
      【A 组三个类（17.3）】**`PropertiesRouteDefinitionLocator` 不解析 yml**（只是把 `GatewayProperties.routes` 转 Flux，
         yml→对象靠 `@ConfigurationProperties`）/ `InMemoryRouteDefinitionRepository` = Actuator 写入口（内存 Map）/
         `CompositeRouteDefinitionLocator` = 合并所有来源 + **给没 id 的补随机 id**（用 boundedElastic）
         —— 顺带给出 **L08"yml 路由删不掉"的源码级解释**
      【"两个视图"的机制（17.3.3）】**Composite 是"无状态视图"（字段只有 delegates，无 Map）→ 每次调用实时问来源**；
         内存 repo 每次读自己 Map 的当前值 → **配置态（/routedefinitions）实时**；
         而**运行态（/routes）由 `CachingRouteLocator` 提供（有缓存）→ 必须 refresh**
         （实测：POST 后 配置态 7 / 运行态 6 → refresh → 运行态 7 → 实际请求 200）
      【"编译"的现场（17.3.5）】**B 组拿到的是 `@Primary` 的 `CompositeRouteDefinitionLocator`**（实测反证：Actuator 加的路由 refresh 后可见）；
         `convertToRoute` = 谓词 `lookup()`（查工厂→bind→**applyAsync**）+ 过滤器 `getFilters()`（**default-filters 先 + 路由后 → sort(order)**）；
         ⭐ **`apply()` 就是 L14/L15 你亲手写的那个方法**（自定义/内置在同一循环）
      【A 组"存储"真相（17.3）】**内存 Map 默认是空的**（无代码从 yml 拷贝；唯一写入者 = Actuator:242/259）；
         **"读写分离"是接口设计**（Locator 只读 / Writer 只写 / Repository 读写）→ **"yml 路由删不掉"是设计不是 bug**；
         要动态改 yml → 自实现可写 Repository 接管（L49）
      【GatewayProperties 结构（17.3.6）】`@ConfigurationProperties("spring.cloud.gateway")`；
         可绑属性共 5 个（routes / default-filters / streaming-media-types / fail-on-route-definition-error / enabled，
           查 `spring-configuration-metadata.json`）；`routes` 元素是 `RouteDefinition`（id/uri/predicates/filters/metadata/order/enabled）；
         ⚠️ `httpclient.*` 绑到**另一个类** `HttpClientProperties`（L06）
      【16.3.1 线程】`get()` 拉实例**不能**阻塞 EventLoop → SCA 的 `NacosReactiveDiscoveryClient` 内部 `subscribeOn(boundedElastic)`；
         SCLB `withDiscoveryClient()` 响应式优先、否则 boundedElastic；+ 三层本地缓存（Nacos 客户端推送 / SCA ServiceCache / SCLB 35s）
      【生产做法（16.9，686 行）】
         · 16.9.1 **内置供应商逐个讲机制**（`withZonePreference`/`withHealthChecks`(默认 `/actuator/health`、后台探活)/
           `withWeighted`(把实例按权重复制多份)/`withCaching`(35s)/`withSameInstancePreference`）
         · 16.9.2 microsphere 同款结构 + **"有内置为什么还要扩展"4 个理由**（自动探测 zone / 容量保护 / 动态性 / 多活配套）
         · 16.9.3 **动态切换=配置中心+事件监听**（`ZoneContextChangedListener` 监听 `EnvironmentChangeEvent`）
         · 16.9.4 **★ LB 的 zone 从哪来**（LB 是**库**，zone=**本进程所在机房**；两个 zone 的含义区分）
         · 16.9.5 Gateway 两坑（zone 手动配 / 同区全挂不 fallback）
      【Gateway 两坑】① zone 需**手动配置**（Gateway 常与业务不在一部署单元）② **同区全挂不 fallback**（要配降级）
      【已完成】`GrayInstanceListSupplier` + `ZonePreferredInstanceListSupplier` + `DemoLoadBalancerConfiguration`
      （实测：none=轮询 / gray=全 v2 / zone=全 shanghai）
      🔴 **要掌握的 3 条**：
      ① **`lb://` 完整链路**：`ReactiveLoadBalancerClientFilter` → `ServiceInstanceListSupplier`（实例清单）→ SCLB 选一个 → **改写 URI**
         ⚠️ **`spring-cloud-starter-gateway` 不含 SCLB** → 缺了 `lb://` 直接 **503**（`NoLoadBalancerClientFilter` 是标志）
      ② **`ServiceInstanceListSupplier` = "选实例"入口**（装饰器链：discovery → 自定义过滤 → caching）
      ③ **"染色"与"选实例"是两件事**：染色=provider 注册时上报 metadata；选实例=消费端按 metadata 过滤
         —— **只染色不选实例 = 标签白打**（xhs 项目正踩此坑）

### Stage 5 · Source Code（读主干）★v5.4 重构：**7 课 → 13 课**
> ⚠️ **口径（2026-09-25 用户确认）**：**讲机制，不逐行** —— 每个类讲清"**解决什么问题 + 关键方法 + 数据结构**"。
> 不钻牛角尖（如 `Path` 谓词讲 3 分钟，不是 30 分钟）。
> 📄 **产出物**：`docs/gateway/architecture-map.md`（四组对象 / 请求流转 / 三张面试图 / 速记卡 —— **要能在面试里说出来**）
> 🔍 **核心类覆盖审计**（改规划前先查此表"有没有漏"）
>
> | 层 | 核心类 | 课 |
> |---|---|---|
> | 装配 | `GatewayAutoConfiguration` | L17 |
> | **来源** | `RouteDefinitionLocator` / `RouteDefinitionRepository` / `PropertiesRouteDefinitionLocator` / `InMemoryRouteDefinitionRepository` / `CompositeRouteDefinitionLocator` | **L19** |
> | **工厂** | `ConfigurationService` / `ShortcutConfigurable` / `RoutePredicateFactory` / `GatewayFilterFactory` | **L20** |
> | 模型 | `RouteDefinition` → `Route` / `RouteDefinitionRouteLocator` | L21 |
> | **缓存** | `CachingRouteDefinitionLocator` / `CachingRouteLocator` / `CompositeRouteLocator` | **L22** |
> | 匹配 | `RoutePredicateHandlerMapping` / `AsyncPredicate` | L23 |
> | **谓词家族** | `PathRoutePredicateFactory` / `MethodRoutePredicateFactory` / `WeightRoutePredicateFactory` / `RemoteAddrRoutePredicateFactory`（代表） | **L24** |
> | 执行·装配 | `FilteringWebHandler` / `GatewayFilterAdapter` / `OrderedGatewayFilter` | L25 |
> | **执行·跑链** | `DefaultGatewayFilterChain` | **L26** |
> | 转发 | `RouteToRequestUrlFilter` / `ReactiveLoadBalancerClientFilter` / `NettyRoutingFilter` / `NettyWriteResponseFilter` | L27 |
> | body | `AdaptCachedBodyGlobalFilter` / `RemoveCachedBodyFilter` | L28 |
> | **会话保持** | `LoadBalancerServiceInstanceCookieFilter` | **L29** |
> | 刷新 | `RefreshRoutesEvent` / `RouteRefreshListener` | L30 |
> | 可选 | `DiscoveryClientRouteDefinitionLocator` / `RedisRouteDefinitionRepository` | L16 / L46 |
>
> ⏭️ 查阅级（不设课）：`RouteDefinitionMetrics` / `RefreshRoutesResultEvent` / `RouteDeletedEvent` / `FilterArgsEvent` / `PredicateArgsEvent` / `WeightDefinedEvent` / `ForwardPathFilter` / `RequestSizeGatewayFilterFactory`
> 🔗 **CORS 源码**（`CorsGatewayFilterApplicationListener` / `DefaultCorsProcessor`）挂在 **L07**；**限流/熔断源码**挂在 **L36 / L37**

- [x] **L17 What Auto-Configuration Wires** —— `GatewayAutoConfiguration`
      📄 **正文**：`docs/gateway/L17-autoconfiguration-wiring.md`（讲解式 + **`/actuator/beans` 实测 453 个 bean**）
      🔴 **要掌握的 2 条**：
      ① 装配出**四组**组件：**A 来源**（`RouteDefinitionLocator` 家族）/ **B 模型**（`RouteLocator` 家族）/
         **C 匹配**（`RoutePredicateHandlerMapping` + 15 谓词工厂 = 14 内置 + 1 自定义）/ **D 执行**（`FilteringWebHandler` + 33 过滤器工厂 = 32 内置 + 1 自定义）
      ② B 组的装配链：`CachingRouteLocator(CompositeRouteLocator(List<RouteLocator>))`
         —— 缓存是"运行态快照"（→ 改完必须 refresh 的根因）
      【实测切入】`GatewayAutoConfiguration` **91 个 @Bean** → 归纳成四组（附 `@ConditionalOnMissingBean` / `@Primary` 的"可接管"含义）
      【★ 补齐规划缺口】**17.2.5 Spring Boot 自动装配最小包**（基座表第 78 行早就写了"spring-boot 只用自动配置 → L17"，之前漏讲）：
         `.imports` 清单 / `@Conditional*` 家族（OnClass·OnMissingBean·OnProperty·OnWebApplication·OnEnabledFilter）/
         `@AutoConfigureBefore/After`（Gateway 是 **Before WebFlux**）/ `@ConfigurationProperties`
      【实测事故】网关**注册自己** → 启动依赖 Nacos（`Client not connected...UNHEALTHY` 启动失败）
         → 修复 `spring.cloud.nacos.discovery.register-enabled: false`（网关是边缘组件，不需被发现）

- [x] **L18 WebFlux Request Pipeline & Gateway's Insertion Point** ★新增（★v5.5：纠正"WebFlux 不用学"的误判）
      📄 **正文**：`docs/gateway/L18-webflux-request-pipeline.md`（8 站管线 / 三份名单 / 两个 `FilteringWebHandler` 重名坑 / `NO_OP`；含日志逐行 + 断点地图 + 自定义 WebFilter/Weight 分流实测）
      【为什么有这一课】请求**必经** WebFlux 管线才能到达 Gateway —— 面试问"请求从 Netty 到你的过滤器经过什么"，缺这段就是断档
      【类】`HttpWebHandlerAdapter`（创建 exchange）/ `WebFilter` 链 / **`DispatcherHandler`**（3 个策略：`handlerMappings` / `handlerAdapters` / `resultHandlers`）/ `WebHttpHandlerBuilder`
      🔴 **要掌握的 2 条**：
      ① **请求怎么到达 Gateway**：`Netty Server → HttpWebHandlerAdapter.createExchange() → WebFilter 链 → DispatcherHandler → 遍历 HandlerMapping → RoutePredicateHandlerMapping`
      ② **Gateway 的插入点**：`RoutePredicateHandlerMapping`（`AbstractHandlerMapping` 子类，order=1）+ `FilteringWebHandler`（`WebHandler`）
      【顺带】`NO_OP_HANDLER` 语义（CORS 预检短路 L07 / 无路由时）

- [x] **L19 Route Definition Sources（来源链）★新增** —— `RouteDefinitionLocator` 家族
      📄 **正文**：`docs/gateway/L19-route-definition-sources.md`（两接口家族 / 四来源 / Composite 合并 / "yml 删不掉"真相；含 discovery+Redis 双来源实测）
      【类】`RouteDefinitionLocator`（只读接口）/ `RouteDefinitionRepository`（可写接口）/
      `PropertiesRouteDefinitionLocator`（**yml**）/ `InMemoryRouteDefinitionRepository`（**Actuator 写**）/
      `CompositeRouteDefinitionLocator`（**合并**）
      🔴 **要掌握的 2 条**：
      ① **四个来源**（yml / 内存 / 注册中心 / Redis）怎么被 `Composite` 合并成**一份定义流**
      ② 这解释了 L08 的疑问：**为什么 yml 路由 Actuator 删不掉**（Actuator 只写**内存那一份**）

- [x] **L20 Factories: yml → Object（工厂机制 ★新增）** —— `ConfigurationService` + `ShortcutConfigurable`
      📄 **正文**：`docs/gateway/L20-factories-yml-to-object.md`（5 步绑定链 / `ShortcutType` / `apply` 最后一跳）｜**参考级**：支撑 L14/L15 的三段式，面试直接问的是三段式本身
      【类】`RoutePredicateFactory` / `GatewayFilterFactory`（接口族）/ `ConfigurationService` / `ShortcutConfigurable` / `NameUtils`
      🔴 **要掌握的 2 条**：
      ① **绑定链**：短路语法 → `_genkey_0` 占位 → `shortcutFieldOrder()` 替换 → `ShortcutType` 归一化 → `Binder` 绑定
         （= L06 配置绑定体系的**源码侧**）
      ② 工厂的**最后一跳是 `apply(Config)`** —— L14/L15"自定义过滤器/谓词的三段式"就源自这里

- [x] **L21 RouteDefinition → Route** —— `RouteDefinitionRouteLocator.convertToRoute()`
      📄 **正文**：`docs/gateway/L21-route-definition-to-route.md`（两级模型 / 谓词 `and` 组合 / 过滤器编译排序；“两个视图”对照；已深度 review）
      🔴 **要掌握的 1 条**：谓词逐个 **`and()`** 组合成 `AsyncPredicate`；过滤器编译成 `GatewayFilter` 列表
      【两级模型】`RouteDefinition`（配置态）→ `Route`（运行态）—— "**定义是源码、Route 是编译产物**"

- [x] **L22 Route Cache（两级缓存）★新增** —— `CachingRouteDefinitionLocator` / `CachingRouteLocator`
      📄 **正文**：`docs/gateway/L22-route-cache-two-levels.md`（**讲解化重写**：例子先行 → 为什么缓存 → 主角源码逐行 → `CacheFlux` 发动机全解（reactor-extra 出处+@Deprecated）→ 双坑装配；**22.1 含真实可复现 curl 序列**（2026-10-02 实测 + "refresh 异步"与"环境 30s 自动刷新"两坑）；已深度 review×2）
      🔴 **要掌握的 1 条**：**定义缓存 vs 运行缓存是两个层次**；刷新时各自如何被替换（L30 的前置）

- [x] **L23 How a Route Is Matched** —— `RoutePredicateHandlerMapping.lookupRoute()`
      📄 **正文**：`docs/gateway/L23-route-matching.md`（**讲解化重写**：四组真实匹配实验（命中/谓词假/无路由/order 影子路由，完整 curl+响应原样粘贴）+ `lookupRoute` 源码逐行 + `filterWhen`/`.next()` 语义（javap 实证并发度 256 + `MonoNext` 源码）；已深度 review×2）
      🔴 **要掌握的 1 条**：`filterWhen(...).next()` **只取第一条匹配**；
      命中后 put `GATEWAY_ROUTE_ATTR` 到 exchange，并返回 `webHandler`（`FilteringWebHandler`）

- [x] **L24 Predicate Family Source（谓词家族）★新增** —— **选代表讲结构，不逐个逐行**
      📄 **正文**：`docs/gateway/L24-predicate-family-source.md`（**讲解化重写**：真实链路（`_genkey`→归一化→绑定源码）+ 三组实测（Method 200/404、Weight 8:2=33:7、RemoteAddr 黑白名单）+ Weight 双通道实证（yml 归一化 OQ 销案）；已深度 review×2；**定位：了解型**（要会仅 2 条：三件套 / Weight 数据源））
      【代表】`PathRoutePredicateFactory` / `MethodRoutePredicateFactory` / `WeightRoutePredicateFactory` / `RemoteAddrRoutePredicateFactory`
      🔴 **要掌握的 1 条**：所有谓词工厂的**统一结构**（`shortcutFieldOrder()` + `Config` + `apply`）—— **会一个就会全部**

- [x] **L25 How the Filter Chain Is Assembled** —— `FilteringWebHandler`
      📄 **正文**：`docs/gateway/L25-filter-chain-assembly.md`（256 行；loadFilters 三态包装 / i+1 出处 / routeFilterMap 语义；含**真实 combined 链日志**（一条看全：适配器 + 路由 filters + 排序）；**定位：实用型（中轻）**）
      🔴 **要掌握的 2 条**：
      ① `getCombinedFilters()` 把 GlobalFilter + 路由 filter **合并成一条链**，按 `order` 排序
      ② `GatewayFilterAdapter`（**把 GlobalFilter 包成 GatewayFilter** —— 合并的关键）+ `OrderedGatewayFilter`（order 包装）
      【缓存】`routeFilterMap` + 监听 `RefreshRoutesEvent` 清缓存

- [x] **L26 Chain Execution ★新增** —— `DefaultGatewayFilterChain`（`FilteringWebHandler` 内部类）
      📄 **正文**：`docs/gateway/L26-filter-chain-execution.md`（洋葱时序 + defer/短路/错误/重试 + 单例约束；含 **`DemoGlobalFilter` 真实 pre/post 日志**；定位：机制型（中轻））
      🔴 **要掌握的 1 条**：**链式递归**（index 递增）如何实现"**洋葱模型**"（pre / post 两阶段）

- [x] **L27 The Four Forwarding Steps**（最核心）
      📄 **正文**：`docs/gateway/L27-forwarding-four-steps.md`（四步源码全解 + alreadyRouted 互斥 + 三大过滤器工厂结构；**三组实测**：`lb://` 改写（Host 变物理 IP `192.168.13.128:8081`）/ 死端口 500（`Connection refused`）/ `forward:` 本机派发）
      `RouteToRequestUrlFilter` → `ReactiveLoadBalancerClientFilter` → `NettyRoutingFilter` → `NettyWriteResponseFilter`
      🔴 **要掌握的 1 条**：`lb://` 在**第二步**被改写成物理 URI
      【转发家族 6 种】`NettyRoutingFilter` / `WebClientHttpRoutingFilter` / `WebsocketRoutingFilter` / `ForwardRoutingFilter` / `FunctionRoutingFilter` / `StreamRoutingFilter`
         ⚠️ **4.1.5 实况更正**：无 `FunctionRoutingFilter`/`StreamRoutingFilter`（全仓库搜索为空）；实为 5 转发类（含 ForwardPath 配合）+ 2 配套 —— 见正文 27.6
      【WebClient 两种用法】① 当路由转发器（`WebClientHttpRoutingFilter`，一般不首选）② **在过滤器里调下游**（认证/风控/聚合；`@LoadBalanced WebClient` 自带 LB）
         ⚠️ 对照：**xhs 网关不用 WebClient**（JWT 本地验签，请求路径**零远程调用**）→ 写进 L41 审计
      【过滤器家族源码（选代表）】`RetryGatewayFilterFactory` / `RequestRateLimiterGatewayFilterFactory` / `SpringCloudCircuitBreakerFilterFactory` 的工厂结构 → 与 L36 / L37 呼应

- [x] **L28 Why the Request Body Can Be Read Only Once**
      📄 **正文**：`docs/gateway/L28-request-body-once.md`（一次性流本质 / 缓存全链 / 谓词支线；**二次读行为源码实证**（`Rejecting additional inbound receiver` / 空完成）；定位：机制型（轻））
      🔴 **要掌握的 1 条**：`AdaptCachedBodyGlobalFilter` 缓存的原因（流只能消费一次）

- [x] **L29 Sticky Session ★新增** —— `LoadBalancerServiceInstanceCookieFilter`
      📄 **正文**：`docs/gateway/L29-sticky-session.md`（176 行；写/读两个半边 + 默认关 + SCLB supplier）
      🔴 **要掌握的 1 条**：会话保持怎么用 **cookie** 把同一客户端钉到同一实例（官方专节实为 Spring Cloud Commons/LoadBalancer 文档"Request-based Sticky Session"；SCG 侧无专节 —— 已在正文标注）

- [x] **L30 How Dynamic Routes Refresh** —— `CachingRouteLocator` + `RefreshRoutesEvent` + `RouteRefreshListener`
      📄 **正文**：`docs/gateway/L30-dynamic-route-refresh.md`（触发源 5 种 / 全量 vs scoped / 在途不中断 / 监听者全景；**含两组实测**：scoped 局部刷新（含"删掉的 scoped 路由"语义）/ 在途请求穿越"删除 + 刷新"）
      🔴 **要掌握的 2 条**：
      ① **刷新只换模型，不中断在途请求**
      ② `POST /actuator/gateway/refresh?metadata=group:g1` **局部刷新**的实现

### Stage 6 · Production（xhs-project 真实踩过的坑）
- [ ] **L31 Real Client IP — X-Forwarded-For**
      【证据】`TrafficColoringFilter.java:74,110-116`（【修复C3】【P0-7】防客户端伪造绕过反作弊）
      🔴 **要掌握的 1 条**：XFF 是"追加"而非"覆盖"
- [ ] **L32 Identity Header Injection & Anti-Spoofing**
      【证据】`GatewayAuthFilter.java:144`：用 `set()` 覆盖而非 `header()` 追加
      🔴 **要掌握的 1 条**：网关注入的 `X-User-Id` 必须覆盖客户端传来的同名头
- [ ] **L33 Long-Lived Connections (SSE / WebSocket)** ⭐
      【证据】`xhs-ai` 路由 `response-timeout: 1860000`（31 分钟）；IM/通知的 ticket 鉴权
      🔴 **要掌握的 2 条**：①SSE 需按路由调大超时 ②EventSource/WebSocket 无法带自定义头 → ticket 两步鉴权
- [ ] **L34 Connection Pool Lifecycle**
      【证据】xhs 修复 T-048：`max-life-time` 必须 < 下游 `keep-alive-timeout`
      🔴 **要掌握的 1 条**：否则复用被对端关闭的连接 → `PrematureClose`(500)
- [ ] **L35 Blocking Is Fatal + Observability**
      🔴 **要掌握的 1 条**：filter 里做阻塞 IO 会卡死 EventLoop（L00 · 0.4 的推论）
      📌 **素材已就绪**：`docs/gateway/L00-prerequisites.md` **0.4.7** —— xhs 真实案例 RV33（`GatewayAuthFilter` 改对）vs RV34（`HmacSignatureFilter` 漏了，已修复并提交 `0607103b`）；
      含「判断口诀」（看客户端类名是否 `Reactive*` 开头）与修复模式 `Mono.fromCallable(...).subscribeOn(boundedElastic())`

---

### Stage 7 · Gateway 内置能力补全（★v4 新增，架构面试必问）
> 为什么单独一个 Stage：v3 把这些能力错误排除了（只看 xhs 用了什么）。**架构面试必问，不能缺。**
> （★v5.4：Stage 5 扩为 13 课后，本 Stage 课号已顺移为 L36~L40）

- [ ] **L36 Rate Limiting** —— `RequestRateLimiter` / `RedisRateLimiter`
      【是什么】Gateway 内置的限流过滤器，基于 Redis + Lua 的令牌桶
      🔴 **要掌握的 2 条**：
      ① **令牌桶 + Lua 的原子性**（为什么不用 Java 实现：多实例下要共享计数）
      ② **`KeyResolver` 决定限流粒度**（按用户 / 按 IP / 按接口）
      【架构问题】限流放网关 vs 放服务？Gateway 内置限流 vs Sentinel 的区别与取舍？
- [ ] **L37 Circuit Breaker & Retry** —— `CircuitBreaker`（Resilience4j）/ `Retry`
      🔴 **要掌握的 3 条**：
      ① 断路器三态（closed / open / half-open）与状态转移
      ② **重试必须幂等**（非幂等接口重试 = 重复下单）
      ③ **治理四件套怎么配合**：超时 → 重试 → 熔断 → 限流（顺序与参数怎么定）
         【v5 补】**超时值怎么定**（从 L06 移入）：全局给安全值（5s）、慢接口单独调大、长连接特别大；**铁律：网关超时 < 上游客户端超时**
      【架构问题】熔断放网关还是放服务？（网关熔断保护的是"下游"还是"网关自己"）
- [ ] **L38 Response Cache & Body Rewrite** —— `LocalResponseCache` / `ModifyRequestBody` / `ModifyResponseBody`
      🔴 **要掌握的 1 条**：什么响应能缓存（`Cache-Control` 语义、本地缓存 vs 分布式缓存的一致性）
      【v5 补】深入：`ResponseCacheManager` / 键生成（`KeyGenerator`）/ 大小权重（`ResponseCacheSizeWeigher`）
      【架构问题】网关做缓存 vs CDN vs 服务内缓存，各自适用什么场景？
- [ ] **L39 Security & Transport** —— Spring Security 集成 / TLS / HTTP2
      🔴 **要掌握的 2 条**：
      ① 网关做认证的两种模式（**token 校验** vs **OAuth2 客户端**）
      ② **HTTPS 在哪里终止**（Nginx / 网关 / 服务）与证书管理
      【v5 补】**安全头**：`SecureHeadersGatewayFilterFactory`（X-Frame-Options / HSTS / X-Content-Type-Options 等）
- [ ] **L40 Gateway 选型与边界**（★ 架构面试核心）
      🔴 **要掌握的 3 条**：
      ① **选型**：Spring Cloud Gateway vs Nginx vs APISIX/Kong —— 各自擅长什么、怎么选
      ② **边界**：什么**不该**放网关（业务逻辑、大文件传输、长事务、**XSS 过滤**）
         【v5 补·XSS 架构判断】网关**不该**做 XSS 过滤，3 个理由：
         1) 转义是**渲染层**的事（后端返回 JSON，不渲染 HTML）
         2) 网关做要**缓存完整请求体**（破坏流式；大 body 直接爆内存）
         3) **误杀**：合法内容含 `<script>` 字样会被拦
         → 要能答"**为什么不在网关做**"，比"怎么写 `XssFilter`"更有价值
      ③ **职责划分**：认证 / 限流 / 灰度 / 日志 —— 哪些必须放网关、哪些可以下放

### Stage 8 · 架构视角：xhs 网关审计 + microsphere 扩展（★v4 新增）

- [ ] **L41 xhs 网关生产就绪度审计**（★ 面试"你的项目哪里没做好"的标准答案）
      【产出】**不足 / 遗漏 / 错误** 三类清单，每条含「现状 → 风险 → 怎么改」
      【审计框架 · 死配置 4 种】（L06 移入，共同特征：**都不报错**）
      | # | 类型 | 判断方法 | xhs 实例 |
      |---|---|---|---|
      | 1 | **位置不对** | 这个 key 在这层有消费者吗？ | `server.tomcat.*`、`spring.mvc.pathmatch.matching-strategy` |
      | 2 | **框架没读** | 非 3 个原生 key 的 metadata 谁读？ | `rate-limit-qps`（若无过滤器读则死） |
      | 3 | **单位写错** | 单位对得上吗？（Duration vs 毫秒） | `metadata.response-timeout` 若写 `5s` 会静默回退 |
      | 4 | **没刷新** | 改完 refresh/重启了吗？ | 动态路由需 `POST /actuator/gateway/refresh` |
      【已知项（待补全核实）】
      - ❌ **错误**：`server.tomcat.*` 死配置（网关跑在 Netty 上，配置无人读）
      - ❌ **错误**：路由 metadata 里的超时/限流**不生效**（只是设计规范）→ ⚠️ **L06 实测修正：这半句是错的！**
        `response-timeout`/`connect-timeout` 是 **SCG 原生支持、自动生效**（实测 504 @0.52s）；
        只有 `rate-limit-qps`（自定义 key）才需要自己写过滤器读 → **xhs 的注释错了一半，会导致排障误判**
      - ❌ **错误**：`HmacSignatureFilter` 阻塞 EventLoop（已修，RV34，提交 `0607103b`）
      - ⚠️ **遗漏**：灰度只打标签、实例过滤未实现（`GrayRouteFilter` 空转）
      - ⚠️ **遗漏**：HMAC 默认关闭（安全增强形同虚设）
      - ⚠️ **遗漏**：网关无熔断 / 重试 / 响应缓存
      - ⚠️ **遗漏**：单实例部署（网关是单点，无高可用）
      - ⚠️ **不足**：无 TLS（内网 HTTP）
      - ⚠️ **不足**：覆盖率未采集、CI 只跑单测
      【面试答法】每条都要能答出"为什么当时没做 / 要做得先做什么"
- [ ] **L42 网关高可用与容量**（架构必问）
      🔴 **要掌握的 3 条**：
      ① **网关是无状态的吗**？什么会让它变成有状态（本地缓存 / 会话 / 限流计数）
      ② **多实例下**：token 校验、限流、灰度怎么保证一致
      ③ **容量怎么估**：瓶颈在哪（连接池 / 线程 / GC）、怎么压测、怎么扩容
      【v5 补·zone 多活现状（已核实）】xhs **自研了 42 个 zone 文件**（未引 microsphere 依赖）：
      `ZoneLocator` SPI + `CompositeZoneLocator` + `EnvVarZoneLocator`/`FileZoneLocator`/`IpRangeZoneLocator`（env/文件/CIDR 三种自动发现）
      + `ZonePreferenceFilter`（10 步过滤）+ LoadBalancer 集成 + `DynamicDataSource` + Redis 拦截器链
      + `propagation`（X-Zone 跨服务传播）+ `admin/ZoneAdminController`（运行时热切）
      【仍缺】`ZoneContextChangedEvent` / `ZoneContextChangedListener`（配置中心驱动的自动切换）/ `ZoneAttachmentHandler`（区域信息注册 Nacos）
      【简历口径】✅ 可写"自研 ZoneLocator 自动发现"；❌ 不能写"用了 microsphere"（没引依赖）；✅ 可写"参考 microsphere 设计自研"
- [ ] **L43 microsphere-gateway：SCG 扩展实践**
      📄 **素材已就绪**：`docs/gateway/L43-microsphere-gateway-extension.md`（含数据流图 / 28 文件结构 / 缺口表 / 简历判断）
      🔴 **要掌握的 3 条**：
      ① **`we://` 端点级路由** vs 官方两条路线（手写 `lb://` / `discovery.locator` 自动路由，两者都只到"服务级"）
         —— microsphere 从实例 metadata 读端点集，做到**零配置 + 端点级控制**
      ② **不改 SCG 源码的扩展姿势**：`BeanDefinitionRegistryPostProcessor` 替换 `FilteringWebHandler` / 自定义 `GlobalFilter` / WebFlux+MVC **双栈适配**
      ③ ⚠️ **它的已知硬伤 G1**：滚动发布时端点缓存不更新 → 新接口**静默返回 200** —— 这是自研/集成的机会点
      【简历判断】microsphere 是**小马哥的项目，不能直接写**；能写的是"**集成**"或"**参考设计自研**"（详见素材 §7）

### Stage 9 · Gateway 生态打通（Integration）（★v5 新增，用户指定"和其他组件打通"）
> v5 依据：训练营 `GATEWAY-PLAN.md`（GW-1~GW-9，9 域）+ 官方文档缺口扫描
> 核心：**Gateway 从来不是孤立的** —— 它靠 Nacos 拿实例、靠 SCLB 选实例、靠 Sentinel/Redis 做治理

- [ ] **L44 Gateway × Nacos**（服务发现 + 配置中心）
      🔴 **要掌握的 3 条**：
      ① **`DiscoveryClientRouteDefinitionLocator`**：`spring.cloud.gateway.discovery.locator.enabled=true`
         → **从注册中心自动生成路由**（`/serviceId/**` → `lb://serviceId`，自动 RewritePath）
         → 与手写路由的**选型**：自动生成（零配置，但路径被服务名绑定）vs 手写（路径自由，如 `/api/xxx/**`）
      ② Nacos 配置中心 → 路由动态刷新（`RefreshRoutesEvent` 链路）
      ③ Nacos 挂了网关会怎样（本地快照 / 降级）
      【交叉引用】`15-Spring-Cloud-Gateway源码分析规划.md` G-7 + 训练营 GW-4
- [ ] **L45 Gateway × Sentinel**（★ 回答"功能冲突"）
      🔴 **要掌握的 3 条**：
      ① **功能重叠对照表**：限流/熔断/降级 —— Gateway 自带 vs Sentinel 各自覆盖什么
      ② **选型判断**：已有 Sentinel 体系 → 用 Sentinel（统一规则中心）；只有网关要限流 → 用自带（少依赖）
      ③ **分层限流**：网关限流（入口层，防全局过载）vs 服务限流（精细层，保护单服务资源）
      【落地】`sentinel-spring-cloud-gateway-adapter` 的 `SentinelGatewayFilter` + `GatewayFlowRule`
      【v5 补】**自定义 `BlockRequestHandler`**：被限流时返回什么（默认 429 纯文本；生产要统一错误体 `{code,msg}` + 正确 Content-Type）
- [ ] **L46 Gateway × SCLB**（负载均衡深入）
      🔴 **要掌握的 3 条**：
      ① `lb://` 背后的 `ReactiveLoadBalancerClientFilter` 如何消费 SCLB 抽象
      ② 负载均衡策略（轮询/随机/权重/同 zone 优先/一致性哈希）怎么选
      ③ 健康检查与实例摘除（网关怎么感知实例下线）
      【交叉引用】训练营 GW-4 + SCC-PLAN
- [ ] **L47 Gateway × Redis**（三类用途）
      🔴 **要掌握的 3 条**：
      ① 限流：`RedisRateLimiter` 的令牌桶 + Lua 原子性
      ② **多实例共享路由**：`RedisRouteDefinitionRepository`（`spring.cloud.gateway.redis-route-definition-repository.enabled`）★ 生产必知
      ③ 认证：token 黑名单 / nonce 防重放（配合自定义过滤器）
- [ ] **L48 头传播与真实 IP**（★ 官方 `httpheadersfilters.adoc`，v5 补）
      🔴 **要掌握的 3 条**：
      ① **三个内置头过滤器**：`ForwardedHeadersFilter`（标准）/ `XForwardedHeadersFilter`（`X-Forwarded-*`，10 个开关）/ `RemoveHopByHopHeadersFilter`（移除 Connection/TE/Transfer-Encoding 等）
      ② **hop-by-hop 头为什么必须移除**（否则代理语义出错）
      ③ **`TrustedProxies` 与 `XForwardedRemoteAddressResolver`**：怎么防"客户端伪造 XFF 绕过限流/风控"
      【关联】L31（真实 IP）的基础就是这一课

### Stage 10 · 生产运维（Ops）（★v5 新增）
> v5 依据：官方 `sharing-routes.adoc` / `reactor-netty-access-logs.adoc` / `troubleshooting.adoc`

- [ ] **L49 多实例部署与一致性**
      🔴 **要掌握的 3 条**：
      ① **共享路由**：`RedisRouteDefinitionRepository`（`InMemoryRouteDefinitionRepository` 只活在单实例内存里，多实例必须换）
      ② **优雅停机**：摘注册 → 排空在途请求 → 关闭（`server.shutdown: graceful` + 前置摘流）
      ③ 多实例下哪些东西必须共享（路由 / 限流计数 / token 黑名单）
- [ ] **L50 排错手册**（官方 troubleshooting + 实战）
      🔴 **要掌握的 3 条**：
      ① **6 个关键 logger**：`org.springframework.cloud.gateway` / `org.springframework.http.server.reactive` / `org.springframework.web.reactive` / `org.springframework.boot.autoconfigure.web` / `reactor.netty` / `redisratelimiter`
      ② **wiretap**：`spring.cloud.gateway.httpclient.wiretap=true`（打印收发字节，调协议问题神器）
      ③ **Reactor Netty 访问日志**：`-Dreactor.netty.http.server.accessLogEnabled=true`（⚠️ **必须是 JVM 系统属性**，不是 Spring 配置）
      【常见故障】404（路由没匹配）/ 504（超时）/ 502（下游连不上）/ PrematureClose（连接池，L34）
      【v5.2 实战事故】⚠️ **不要覆盖正在运行进程的 jar**：后台 `mvn package` 覆盖了运行中的 jar →
         `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`（JVM **懒加载类**找不到）→ 应用**半死**（请求挂住不返回）
         → 正确做法：**先停进程 → 替换 → 再启动**

---

## 6. ❌ Out of Scope

| 内容 | 理由 |
|---|---|
| `spring-cloud-gateway-server-mvc`（Servlet 版，90 文件） | 4.2.0 才诞生；xhs 用的 4.1.5 **根本没这个模块** |
| Proxy Exchange（`spring-cloud-gateway-webflux`/`-mvc`） | 另一个边缘 flavor，与 xhs 无关 |
| AOT & Native Image | 与本项目无关 |
| 16 个谓词、39 个 filter 工厂全背 | 记住 5 + 8 个，其余用时查 |
| Reactor 高级算子（`zip`/`merge`/`window`/背压算子） | L10 只需 10 个基础算子 |
| `Schedulers` 内部实现 | 知道有 `boundedElastic` 这个逃生舱即可 |

> **v4 移出黑名单**（原 v3 错误排除）：
> - ~~`RedisRateLimiter` 源码~~ → **L36**（限流是网关核心能力，面试必问）
> - ~~`Retry` / `CircuitBreaker` 过滤器~~ → **L37**（同上）
> - 原因：v3 只按"xhs 用了什么"取舍，漏掉了"Gateway 本身有什么"

---

## 7. Appendix: Lab Tracker

| 课 | xhs-demo 实验 | 状态 |
|---|---|---|
| L02 | 静态路由 `/provider/**` → `http://localhost:8081` | [x] 已跑通 |
| L05 | `StripPrefix` + `AddRequestHeader` + `AddResponseHeader` | [x] 已跑通 |
| L13 | `DemoGlobalFilter` 注 TraceId、记耗时、改请求/响应 | [x] 已跑通 |
| L00 · 0.4.3 | `ss -tnp` 抓两条 TCP 连接（网关各持一端） | [x] 已跑通 |
| L03 | 新增 `/hello/**` 路由 | [ ] 待做 |
| L09 | **`reactor-lab` 模块**（新）：Ex01 手写 Publisher 信号顺序 / Ex02 信号轨迹 / Ex03 惰性 / Ex04 `Mono<Void>` / Ex05 背压 + **5 个 StepVerifier 断言** | [x] 已跑通 |
| L10 | **`reactor-lab` l10/**：Ex01 map vs flatMap（含"套娃"反例）/ Ex02 then vs doOnXxx / Ex03 过滤与兜底 + **6 个 StepVerifier 断言** | [x] 已跑通 |
| L11 | **`reactor-lab` l11/**：Ex01 背压策略（默认报错/BUFFER 收全/DROP 只收 1/LATEST 收 [0,4]）/ Ex02 Schedulers 线程名与规模 / Ex03 publishOn vs subscribeOn / Ex04 ParallelFlux（1277ms → 326ms）+ **4 个断言** | [x] 已跑通 |
| L12 | **`reactor-lab` l12/**：Ex01 Context（写读/跨线程/桥接 attributes）/ Ex02 Sinks（三种 multicast 口味 + EmitResult）/ Ex03 log + checkpoint 路标 + **6 个断言** | [x] 已跑通 |
| L00 | **`reactor-lab` l00/**：Ex01 HttpServer+HttpClient / Ex02 TcpServer+TcpClient / Ex03 WebFlux 最小使用 | [x] 已跑通 |
| L14 | **已完成**：`DemoHeaderGatewayFilterFactory` + yml `- DemoHeader=...` → 下游收到 `x-demo-factory: hello-from-my-factory`（含破坏性实测：去掉 shortcutFieldOrder → 500） | [x] 已跑通 |
| L15 | **已完成**：`DemoHeaderRoutePredicateFactory` + 路由 `demo-predicate-lab` → 带头 200 / 不带头 404（4 用例实测） | [x] 已跑通 |
| L16-a | **`uri: lb://demo-provider` + Nacos 已打通**：provider 注册（metadata zone=shanghai/version=v1）→ 网关 `lb://` 转发 200；`/globalfilters` 出现 `ReactiveLoadBalancerClientFilter`（10150） | [x] 已跑通 |
| L16-b | **已完成**：第二实例（8082, `zone=beijing,version=v2`）+ `GrayInstanceListSupplier`/`ZonePreferredInstanceListSupplier` → 实测 none=轮询 / gray=全8082 / zone=全8081 | [x] 已跑通 |
| **手写 lab** | **手写最小网关**（`HandlerMapping` + `WebHandler` + 过滤器链 + 转发，约 50 行）—— ★ 面试素材 | [ ] 待做 |
| L07 | CORS 6 组实测 + **配置已落盘**（`demo-gateway/.../application.yml` 的 `globalcors` + `cors-lab`/`cors-lab2` 路由）| [x] 已跑通 |
| L08 | Actuator 实测：两视图对照 / POST 201 但运行态无 / refresh 后有 / DELETE yml 路由 404 / globalfilters order 表 | [x] 已跑通 |

> 跑实验提醒：本机有 `http_proxy` 环境变量，curl 访问 localhost 要加 `--noproxy '*'`。

---

## 8. 版本历史

| 版本 | 主要问题 | 修正 |
|---|---|---|
| v1 | 把 WebFlux/Reactor 当"前置理论"放最前；术语裸奔；无学会标准 | 作废 |
| v2 | 顺序改为"现象→配置→代码→源码"，但 **Reactor 排在 Stage 4** | 部分修正 |
| v3 | — | **Reactor 提前到 Stage 3**（写代码之前）；每课标注"要掌握的 N 条"；补基座边界表；补三类阅读约定 |
| **v4** | **v3 只按"xhs 用了什么"取舍，漏掉了"Gateway 本身有什么"** —— 限流/熔断/重试/缓存/安全/TLS/选型被错误排除；且未覆盖"项目不足"与"扩展实践" | **定位改架构优先**（源码抓主干）；新增 **Stage 7**（L36~L40 内置能力补全）+ **Stage 8**（L41~L43 项目审计 / 高可用容量 / microsphere 扩展）；Out of Scope 移回 2 条 |
| **v5** | **缺"与其他组件打通"**（Nacos/SCLB/Sentinel/Redis）；漏了官方文档 5 处（头传播/服务发现自动路由/共享路由/访问日志/排错） | 对标训练营 `GATEWAY-PLAN.md`（9 域）+ 官方 23 页文档扫描；新增 **Stage 9 生态打通**（L44~L48）+ **Stage 10 生产运维**（L49~L50）；补进 L04/L05/L27/L38/L39 共 5 处 |
| **v5.1** | **用户提供的外部课程大纲（116 讲）查漏**：发现 CORS 被低估（大纲 4 讲 vs 我们 1 课）、缺"请求全链路"完整性自检 | ① 新增 **§2 请求全链路图**（7 环节 → 课号 + 覆盖审计）；② **L07 CORS 加强**（处理位置在 WebFlux 层 / `metadata.cors` / 预检）；③ **L10 补 3 点**（`publishOn`/`subscribeOn`、`defer`/`just`、背压）；④ **L27 补 WebClient 两种用法**（+xhs 零远程调用对照）；⑤ **L40 补 XSS 架构判断**；⑥ **L45 补 `BlockRequestHandler`**；⑦ **L05 补 WebFilter 辨析**；⑧ Lab Tracker 加**手写最小网关** |
| **v5.5** | **用户第二次指出基础不足**："从 0 开始"的要求没满足 —— 对照外部课程大纲响应式 21 项，我们只覆盖了 3 项（8/9/10 算子）；且**误判"WebFlux/DispatcherHandler 不用学"**（它就在请求链路上，不学 = 断档） | ① **Stage 3 从 1 课 → 4 课**（L09 契约与模型 / L10 算子 / L11 背压与调度 / L12 Context·Sinks·调试），补全大纲 1~14；② **Stage 5 插入 L18 WebFlux 请求管线**（`HttpWebHandlerAdapter` → `WebFilter` → `DispatcherHandler` → Gateway 插入点）；③ Reactor-Netty 15~18 的**基本使用**进前置 0.4.4、**底层原理不学**；④ Stage 4~10 编号顺移（总 **50 课**） |
| **v5.4** | **用户指出"源码覆盖太薄、不是为对象而对象"** —— 对照其 47 项源码大纲逐项核对，发现**工厂机制（`GatewayFilterFactory`/`RoutePredicateFactory` 执行过程）/ 谓词家族 / 来源链 / 两级缓存 / 链执行 / sticky session** 均无课；"架构地图"不能替代源码本体 | **Stage 5 从 7 课 → 13 课**（L17~L30；口径仍为"讲机制不逐行"）；Stage 6~10 编号顺移 +6（L31~L50，总 46 课）；同步更新全链路图与覆盖审计表 |
| **v5.3** | **用户质疑"Stage 5 核心源码是否都规划了"** —— 逐类审计后发现真缺口：**路由来源链**（`RouteDefinitionLocator` 家族 5 类）与**过滤执行骨架**（`GatewayFilterAdapter` / `DefaultGatewayFilterChain` / `OrderedGatewayFilter`）**无课覆盖** | ① 新增 **Stage 5 核心类覆盖审计表**（防止再漏）；② **L17 扩**为"四组组件"；③ **L21 扩**补"来源链"（解释 L08 的 404）；④ **L25 扩**补"执行骨架"；⑤ 新增产出物 **`docs/gateway/architecture-map.md`**（整体架构/请求流转/对象清单/速记卡） |
| **v5.2** | **L06 两次返工**：① 初版主题不清（"超时+metadata"两点罗列）→ 改"配置体系"又太抽象 → 用户指出"感觉没学到东西" | **定为"两个具体东西"**：超时（4 个 + 单位坑 + 值怎么定）+ 路由 metadata（框架只读 3 key）；**"死配置 4 种"审计框架移到 L41**；补"2 个位置 / 3 种配置"模型 + 连接池（分池 / 三种连接辨析 / `max-connections` per-pool 推论） |
