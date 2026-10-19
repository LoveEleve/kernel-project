# Z: Spring Cloud LoadBalancer 面试题速答（收束篇）

> 定位：loadbalancer-lab 收束篇——把 A（官方功能）/D（核心原理）的结论面试化。
> 基线：**SC 2023.0.3 → spring-cloud-loadbalancer 4.1.4**（xhs-project 口径；4.3.2/5.x 特性一律标注）。
> 题库锚点：手册 Ch20「服务治理与发布」10 题（242-251）+ Ch21 SCA（252-277）。
> 用法：先自测（盖住答案），答不出的回对应文档。

---

## 一、速记卡（一页纸）

1. **三层接口**：`ReactiveLoadBalancer<T>`（commons，返回 `Publisher<Response<T>>`）→ `ReactorLoadBalancer<T>`（收窄为 `Mono`）→ `ReactorServiceInstanceLoadBalancer`（marker，T=ServiceInstance）。
2. **Response 表达"无实例"**：`hasServer()`/`getServer()`——无实例返回 `EmptyResponse`，**不抛异常**（调用方自行判 hasServer）。
3. **RoundRobin**：`AtomicInteger position`（起始 `Random.nextInt(1000)` 随机化）；`incrementAndGet() & Integer.MAX_VALUE` 防溢出负数；单实例短路。
4. **Random**：`ThreadLocalRandom`，无 position 字段，无单实例短路。
5. **Supplier = 装饰器链**：14 个 Supplier；`DelegatingServiceInstanceListSupplier` 四接口委托（Supplier/SelectedInstanceCallback/InitializingBean/DisposableBean）。
6. **Builder**：`baseCreator`（最内层）+ `creators[]`（按序包装）→ `build()`；20 个 `withXxx()`（15 唯一名）。
7. **链顺序**：网络拉取（DiscoveryClient）→ Caching/HealthCheck（无请求语义，靠内）→ 请求级过滤（Hint/Sticky/RetryAware/Subset/Zone）→ Weighted 展开。
8. **HealthCheck**：后台订阅（`afterPropertiesSet` 主动 subscribe）+ `repeatWhen`/`switchMap`/`replay(1).refCount(1)`；超时/异常都判死；`updateResultsList` 控制增量/一次性。
9. **自动配置三层**：`LoadBalancerAutoConfiguration`（commons：拦截器）→ `LoadBalancerClientFactory`（per-client 子上下文）→ `LoadBalancerClientConfiguration`（8 个 Condition 决定链）。
10. **阻塞桥接**：`BlockingLoadBalancerClient.execute()` → `choose().block()` → `LoadBalancerUriTools.reconstructURI()`（host:port 替换，含 encoded 处理）；**无实例返回 null**。
11. **拦截器家族**：`LoadBalancerInterceptor`（RestTemplate）/`RetryLoadBalancerInterceptor`（重试，需 spring-retry）/`DeferringLoadBalancerInterceptor`（RestClient 延迟解析）。
12. **Lifecycle**：`LoadBalancerLifecycle`（onStartRequest/onComplete/onStartRequest）——`MicrometerStatsLoadBalancerLifecycle` 出指标（LoadBalancerTags 标签体系），DISCARD/FAILED 按需建指标。

---

## 二、分组高频题

### A. 接口与算法

**A1. 三层接口为什么要这么分？**
commons 层 `ReactiveLoadBalancer` 不绑 Reactor（Publisher）；loadbalancer 模块用 `ReactorLoadBalancer` 收窄为 Mono；marker 接口表达"选的实例是 ServiceInstance"。回链：D1 §一。

**A2. `Response<T>` 为什么不是直接返回实例？**
可表达"无可用实例"（EmptyResponse.hasServer()=false），调用方不用 null/异常判断。回链：D1 §三。

**A3. RoundRobin 的 position 细节？**
起始随机 seed（防启动倾斜）+ `& Integer.MAX_VALUE`（防溢出负数 IndexOutOfBounds）+ 单实例短路（省原子操作）。回链：D1 §4.1/4.3。

**A4. `SelectedInstanceCallback` 是干什么的？**
LB 选中实例后沿装饰器链向下回调（`Delegating` 层层传递）；`SameInstancePreference` 重写它来缓存选中实例。回链：D1 §4.4。

### B. Supplier 装饰器链

**B1. `get()` 和 `get(Request)` 的区别？**
`get(Request)` 携带请求上下文（hint/cookie/重试信息）；默认实现委托 `get()`；请求感知装饰器（Hint/Sticky/RetryAware/Subset）重写它。回链：D2 §1.2。

**B2. 装饰器链的构建与数据流向？**
构建：base → creators 依次包装（外→内）；数据：外层 get() → delegate.get() → 内层数据源 → 逐层转换返回。回链：D2 §3.2。

**B3. `callGetWithRequestOnDelegates` 控制什么？**
Weighted/SameInstance/Zone 是否把 Request 传给内层。默认 true；若链含 Caching/HealthCheck（不重写 get(Request)），源码建议它们靠近内层。回链：D2 §4.11。

**B4. HealthCheck 如何后台运行？**
`afterPropertiesSet()` 主动 subscribe `aliveInstancesReplay`（replay(1).refCount(1) 共享流）→ 不依赖请求触发；`destroy()` 释放。回链：D2 §4.4.5。

**B5. SameInstancePreference vs RequestBasedStickySession？**
前者：内存态（上次选中的实例），进程内亲和；后者：cookie（`sc-lb-instance-id`），跨请求/跨进程。回链：D2 §4.8。

**B6. Subset 的"确定性"？**
`Random(round)` 用 `round = instanceId/count` 作种子——同客户端子集稳定；count 变化时 round 平滑过渡（防抖动）。回链：D2 §4.7。

**B7. Caching 的空列表陷阱？**
空列表会写缓存（无条件 put），但 lookup 时视为未命中——服务恢复要等下次 delegate 拉取。TTL 默认 35s / capacity 256。回链：D2 §4.10。

**B8. Hint/Zone 的降级策略？**
"优先而非强制"——无匹配实例时静默返回全部（不抛异常）。回链：D2 §4.5/§4.6。

### C. 自动装配

**C1. LoadBalancerClientFactory 是什么？**
`NamedContextFactory` 子类——per-client 子上下文；`getInstance(serviceId)` 拿 LB、`getInstances` 跨子上下文取 Bean。回链：D3 §三。

**C2. 链怎么被组装的？**
`LoadBalancerClientConfiguration` 的 8 个 Condition（Default/ZonePreference/HealthCheck/Weighted/Subset/SameInstancePreference/RequestBasedStickySession/AvoidPreviousInstance+Retry）按配置组合 @Bean。回链：D3 §五。

**C3. `@LoadBalancerClient(configuration=...)` 怎么注册？**
`LoadBalancerClientConfigurationRegistrar`（ImportBeanDefinitionRegistrar）扫描注解 → 注册 per-client 子上下文配置。回链：D3 §四。

### D. 阻塞与集成

**D1. BlockingLoadBalancerClient 的桥接？**
`execute()` → `choose(request).block()`（Mono→阻塞）→ 无实例返回 null；有实例 → `reconstructURI` → `request.apply(instance)`。回链：D5 §三。

**D2. URI 重构做了什么？**
`LoadBalancerUriTools.reconstructURI`：用选中实例的 scheme/host/port 替换原 URI（服务名→真实地址），处理 encoded 部分。回链：D5 §五。

**D3. 三个拦截器的分工？**
`LoadBalancerInterceptor`：RestTemplate 无重试路径；`RetryLoadBalancerInterceptor`：重试路径（spring-retry 在 classpath + RetryTemplate）；`DeferringLoadBalancerInterceptor`：RestClient 延迟解析（请求时才知道是否 LB）。回链：D7。

**D4. 重试怎么换实例？**
`RetryAwareServiceInstanceListSupplier` 从 `RetryableRequestContext` 取上次失败实例并剔除；配合 `maxRetriesOnNextServiceInstance`。回链：D2 §4.9。

### E. Lifecycle 与指标

**E1. LoadBalancerLifecycle 的三个回调？**
`onStartRequest`（带 RequestData）/`onComplete`（带 CompletionContext：SUCCESS/DISCARD/FAILED）/`onStartRequest`（无请求数据的场景）。回链：D6。

**E2. 指标标签体系？**
`LoadBalancerTags`（serviceId/instanceId/instance host/port 等）+ `MicrometerStatsLoadBalancerLifecycle`；`discard`/`failed` 指标按需创建。回链：D6 §3.2。

### F. 生产与对照（xhs-project）

**F1. xhs-project 的 LB 是什么？**
**自研 `LeastConnectionsLoadBalancer`**（实现 `ReactorServiceInstanceLoadBalancer`）——按实例"在途请求数"选择；配 `LeastConnectionsLifecycle`（统计 in-flight）+ `ZoneLoadBalancerConfig`（同 zone 优先）。回链：D1 §一 + xhs 源码。

**F2. 为什么自研最少连接？**
默认 RoundRobin 在实例性能不均/慢实例场景会持续打慢节点；最少连接按实时负载分配——配合 Feign 调用形成"慢实例自动降权"。回链：D2 §4.11 + xhs 源码。

**F3. Feign 用的是哪条 LB 路径？**
`FeignBlockingLoadBalancerClient`（openfeign-lab D3）→ `BlockingLoadBalancerClient.execute()`——与 RestTemplate 同一条阻塞桥接路径。回链：D5 + openfeign-lab。

---

## 三、陷阱速查（15 连）

| # | 反直觉点 | 正解 |
|---|---|---|
| 1 | RoundRobin position 从 0 开始 | 随机 seed（`Random.nextInt(1000)`） |
| 2 | 溢出后取模没问题 | 必须 `& Integer.MAX_VALUE` 清符号位 |
| 3 | Random 也有单实例短路 | 只有 RoundRobin 有 |
| 4 | 无可用实例抛异常 | 返回 `EmptyResponse`/null（不抛） |
| 5 | HealthCheck 靠请求触发 | `afterPropertiesSet` 主动订阅（后台跑） |
| 6 | 健康检查超时会抛异常 | 超时/异常都判死（Mono.empty） |
| 7 | 空列表不写缓存 | 会写（但 lookup 视为未命中） |
| 8 | Hint 找不到实例就报错 | 静默返回全部（优先非强制） |
| 9 | Subset 每次随机 | `Random(round)` 确定性 |
| 10 | RetryAware 剔除到空列表 | 剔除后为空返回全部 |
| 11 | Caching 能缓存请求上下文 | 不重写 get(Request)，Request 必丢 |
| 12 | 所有装饰器都传 Request | `callGetWithRequestOnDelegates` 可控 |
| 13 | 服务名直接发请求 | 必须先 `reconstructURI` 换成 host:port |
| 14 | ApiVersion 在 4.1.4 可用 | **不可用**（v5.0.0-RC1 才有） |
| 15 | xhs 用框架 LB | xhs 是**自研** `LeastConnectionsLoadBalancer` |

---

## 四、文档索引（回链）

| 主题 | 文档 |
|---|---|
| 接口体系 / RoundRobin / Random / Response | D1 |
| Supplier 装饰器链（14 个）/ Builder / HealthCheck | D2 |
| 自动装配（Factory/Registrar/Condition 矩阵） | D3 |
| Weighted 权重展开 | D4 |
| BlockingLoadBalancerClient / URI 重构 / Lifecycle 收集 | D5 |
| LoadBalancerLifecycle / MicrometerStats / Tags | D6 |
| Interceptor 家族（RestTemplate/Retry/RestClient） | D7 |
| 官方功能清单（A-01~A-18） | A-类官方功能 |
| 评审记录 | REVIEW-2026-10-05 |

---

## 五、一页纸收束

> **接口**：Reactive→Reactor→ReactorServiceInstance 三层；Response 表达无实例；RoundRobin（随机 seed + 溢出保护）/Random（ThreadLocalRandom）。
> **Supplier**：14 个装饰器；Builder 20 个 withXxx；链序 = 网络 → Caching/Health → 请求级过滤 → Weighted。
> **装配**：LoadBalancerAutoConfiguration → per-client 子上下文（Factory）→ 8 Condition 组链。
> **阻塞**：BlockingLoadBalancerClient（choose().block() + reconstructURI）；无实例返回 null。
> **集成**：LoadBalancerInterceptor/Retry/Deferring；Feign 走同一条阻塞路径。
> **对照**：xhs-project 自研 LeastConnectionsLoadBalancer（最少连接 + zone 优先 + Lifecycle 统计）。
