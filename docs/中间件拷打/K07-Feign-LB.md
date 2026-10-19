# K07 — 组件深度拷打：Feign + LoadBalancer

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：声明式客户端、子容器、编解码链、配置优先级、装饰器链、RoundRobin、无实例 503、重试边界
> 依据：openfeign-lab（A/B/D1-D7 + Z）**OpenFeign 4.1.3** + loadbalancer-lab（00/A/D1-D7 + Z）**SC LB 4.1.4** + xhs-project 实战

---

## 问题

Feign 怎么把接口变成 HTTP 调用？配置为什么不生效？负载均衡怎么选实例？没有实例会怎样？重试能不能开？

---

## 面试可讲版（五段式）

### ① Feign 核心链路（源码核心）

**四块拼图**：注解契约（`SpringMvcContract`）+ 编解码（`HttpMessageConverter` 体系）+ 负载均衡（SC LoadBalancer）+ 容错（Sentinel/CircuitBreaker）。

```
@EnableFeignClients → FeignClientsRegistrar（ImportBeanDefinitionRegistrar）
  → 扫描注册：FeignClientSpecification（配置）+ FeignClientFactoryBean（工厂）
  → 默认 Eager：注册期解析属性（仅 ${}）；lazy-attributes-resolution=true 才延迟
FeignClientFactory（NamedContextFactory）→ 每个客户端一个【子容器】
  代理链：JDK 代理 → InvocationHandler（Feign/Sentinel/CircuitBreaker 三选一）
        → SynchronousMethodHandler → encode → RequestInterceptor → Client.execute → decode
```

**编解码链**：`SpringEncoder`（表单→`SpringFormEncoder`；其余走转换器链）→ `OptionalDecoder → ResponseEntityDecoder → SpringDecoder`。注意 `encode()` 会清 Header 后用转换器产物替换——**拦截器在 encode 之后**执行（所以拦截器加的 Header 不会被清）。

**配置优先级（`defaultToProperties=true`，后应用者胜）**：
```
clientName.* > default.* > @FeignClient(configuration) 的 Bean > 框架默认
（4.x 属性前缀是 spring.cloud.openfeign.*，不是旧 feign.*）
```

### ② LoadBalancer 装饰器链（源码核心）

**三层接口**：`ReactiveLoadBalancer`（commons，Publisher）→ `ReactorLoadBalancer`（收窄 Mono）→ `ReactorServiceInstanceLoadBalancer`（marker）。

**Response 表达"无实例"**：`EmptyResponse.hasServer()=false`——**不抛异常**（调用方判 hasServer）。

**Supplier = 装饰器链（14 个）**：构建 = baseCreator + creators 依次包装；链顺序（由内到外）：网络拉取 → Caching/HealthCheck → 请求级过滤（Hint/Sticky/Subset/Zone）→ **Weighted 展开**（权重→实例副本展开）。

**RoundRobin 细节（常考）**：`AtomicInteger position`（起始 `Random.nextInt(1000)` 防启动倾斜）+ `incrementAndGet() & Integer.MAX_VALUE`（防溢出负数）+ 单实例短路。

**HealthCheck**：后台主动订阅（不依赖请求触发）+ `replay(1).refCount(1)` 共享流；超时/异常都判死；Caching 空列表会写缓存但视为未命中（服务恢复有延迟窗口）。

### ③ Feign × LB 集成（面试关键）

```
FeignBlockingLoadBalancerClient.execute：
  URI.getHost() 取 serviceId → LoadBalancerClient.choose(serviceId) → reconstructURI 替换 host:port → delegate.execute
  无实例 → 返回 503 Response（不抛异常，让 ErrorDecoder/fallback 处理）
```

**两个易错点**：
- **有 URL 直连要 unwrap**：`getTarget()` 用 `getDelegate()` 取出底层 Client 跳过 LB（否则 `choose("localhost")` → 503）
- **重试分两层**：Feign 层 `Retryer`（默认 NEVER_RETRY）+ LB 层 `RetryableFeignBlockingLoadBalancerClient`（`RetryTemplate` + 可重试状态码 + **可换实例**）——两层都要显式配置

### ④ 生产口径（xhs 实战）

**Feign 安全三件套（xhs 口径）**：
1. **全局 NEVER_RETRY**（`FeignSafeConfig`）——理由（代码注释原文）："下单接口重试 → 创建两个订单 → **资损事故**"；幂等接口要重试在单个 Client 覆盖
2. **自定义 ErrorDecoder**：HTTP 错误 → `RemoteException`（带目标服务/路径/状态码，定位调用链）
3. **统一增强**（`FeignUnifiedConfig`）：`R<T>` 自动解包 + `BizException` 还原 + 统一拦截器（染色/Token）

**内部调用安全（`FeignInternalCallInterceptor`）**：
- 全局注入 `X-Internal-Call` 头（token 来自环境变量 `INTERNAL_TOKEN`，**默认空 = fail-closed**——宁可链路不可用，不泄露默认令牌）
- T-017 修复：拦截器可能被 Feign 子容器 `new` 实例化（@Value 不注入）→ 字段默认值直接读环境变量（双路径生效）

**自研 LB（`LeastConnectionsLoadBalancer`）**：
- 最少活跃请求 + **预热**（新实例 60s 内权重 1%→100% 爬升，防冷启动打垮）+ 权重衰减（响应慢降权）
- 诚实边界（代码注释）：`markRequestStart/End` 当前未接线 → activeRequests 恒 0 时**退化为权重选择**（仍优于轮询）
- Zone 亲和：`ZonePreferenceServiceInstanceListSupplier`（优先同 zone，无匹配静默返回全部——"优先而非强制"）

**超时口径**：`spring.cloud.openfeign.client.config.default.connect-timeout=500`（个别服务 3000）/ `read-timeout=2000`（个别 3000）——实测 18/19 服务为 500/2000；网关侧 500/2000。

### ⑤ 拷打追问（面试官视角）

1. **"@FeignClient(configuration) 的 Bean 为什么全局配置里不生效？"** 它在**子容器**里——每个客户端独立容器；要全局生效用 `default.*` 属性或 `@EnableFeignClients(defaultConfiguration)`。
2. **"配置改了不生效？"** 优先级链（clientName > default > configuration > 框架默认，后应用者胜）+ 前缀错误（4.x `spring.cloud.openfeign.*`）+ 超时被 `refresh-enabled` 的 `OptionsFactoryBean` 接管。
3. **"Feign 的重试在哪一层？"** 两层：Feign `Retryer`（默认 NEVER_RETRY）+ LB 层 `RetryableFeignBlockingLoadBalancerClient`（需 spring-retry）；xhs 全局关 Feign 层——**非幂等接口重试=资损**。
4. **"没有实例会怎样？"** Feign×LB：503 Response（不抛异常）；纯 `BlockingLoadBalancerClient`：返回 null——调用方要判。
5. **"轮询怎么实现的？"** `AtomicInteger position` + 随机起始 + `& MAX_VALUE` 防溢出；Weighted 是"展开副本"（权重 3 → 实例出现 3 次）再轮询。
6. **"健康检查是请求触发的吗？"** 不是——后台订阅（`afterPropertiesSet` 主动 subscribe）；这也是它必须靠近装饰器链内层的原因（无请求语义）。
7. **"怎么让流量避开慢实例？"** 内置没有"最少连接"——xhs 自研 LeastConnections（最少活跃+预热+降权）；通用做法是 Weighted/Hint + 指标观测。
8. **"fallback 和 ErrorDecoder 什么关系？"** 兜底层次不同：ErrorDecoder 把错误转成异常（业务可读）；fallback（Sentinel/CircuitBreaker 集成）在代理层拦截异常返回兜底值——可共存（先异常后兜底）。

### ⑥ 话术

> "Feign 是声明式 HTTP 客户端：注册期扫 @FeignClient 建子容器，代理链里 encode→拦截器→LB Client→decode；配置优先级 clientName > default > configuration，4.x 前缀是 spring.cloud.openfeign。负载均衡是装饰器链，我们自研了 LeastConnectionsLoadBalancer（最少活跃+60s 预热+降权），替换默认轮询；没实例返回 503 交给 ErrorDecoder。生产上全局关 Feign 重试——非幂等重试会资损，重试要开就在 LB 层按可重试状态码换实例；内部调用用 X-Internal-Call 头 fail-closed 校验。"

---

## 生产陷阱（Feign/LB 7 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **非幂等接口重试** | Feign 默认 NEVER_RETRY 但有人手动开——下单/支付类必关（xhs 全局关） |
| 2 | **超时没配或太大** | 默认连接 10s/读 60s——慢下游拖死调用方线程；按服务配（500/2000 为主，个别 3000） |
| 3 | **配置不生效** | 子容器 vs 全局 + 优先级链 + 前缀（4.x）——先查三层 |
| 4 | **有 URL 直连 503** | 没 unwrap，`choose("localhost")` 找不到实例 |
| 5 | **拦截器 Header 被清** | encode 清 Header——拦截器必须在 encode 之后（框架已保证） |
| 6 | **Caching 空列表窗口** | 服务恢复后仍拿空列表（缓存未过期）——TTL 35s，敏感场景缩短 |
| 7 | **自研 LB 未接线退化为权重** | 活跃数没埋点就是 0——要么接线（Decoder/AOP），要么接受权重语义 |

---

## 发散追问地图（横向）

- **对比**：RestTemplate+LB 拦截器（`LoadBalancerInterceptor`）、RestClient（`DeferringLoadBalancerInterceptor`）、WebClient（响应式）、Dubbo（RPC 直连）——选型维度
- **客户端实现**：默认 JDK HttpURLConnection → Apache HttpClient5 / OkHttp（连接池/HTTP2）
- **LB 深入**：Hint（灰度）、Sticky Session（cookie `sc-lb-instance-id`）、Subset（确定性分组）、RetryAware（避开上次失败实例）
- **可观测**：`LoadBalancerLifecycle` → Micrometer 指标（LoadBalancerTags）；调用链（traceId 透传）
- **容错体系**：Sentinel 适配（Feign.Builder 替换）、CircuitBreaker 集成（Targeter 层）、超时/重试/隔离的组合拳（xhs：规则+超时+最小连接 LB）

---

## 面试官评分点

**高级开发级**：Feign 代理链讲得清；配置优先级；LB 选实例流程；无实例 503；重试边界（非幂等不能重试）。
**架构师加分**：子容器隔离设计（多客户端不同配置）；装饰器链的构建与顺序语义；两层重试的职责划分；自研 LB 的取舍与诚实降级；超时/重试/隔离组合拳而非单点。
**危险信号**：说不出 Feign 和 RestTemplate 的本质区别；全局开重试不知道资损风险；配置不生效只会重启试；以为 LB 内置最少连接；不知道子容器。

---

## 本项目真实证据

**xhs-project**：
- `FeignSafeConfig`：NEVER_RETRY（"下单重试→资损"注释）+ `RemoteCallErrorDecoder`
- `FeignUnifiedConfig`：R<T> 解包 + BizException 还原 + 统一拦截器
- `FeignInternalCallInterceptor`：X-Internal-Call（INTERNAL_TOKEN 环境变量、fail-closed、T-017 双路径修复）
- `FeignTraceInterceptorConfig`：traceId 透传（自研，非 micrometer-tracing）
- `LeastConnectionsLoadBalancer`（+`LeastConnectionsLifecycle`/Config）：最少活跃+预热 60s（1% 起步）+降权+诚实降级注释
- `ZonePreferenceServiceInstanceListSupplier`（zone 亲和，"优先非强制"）
- yml：connect 500（个别 3000）/ read 2000（个别 3000）；网关 500/2000

**本 lab**：
- openfeign-lab（OpenFeign 4.1.3）：A-01~A-09 + B 扩展点 + D1-D7（注册/工厂/编解码/Targeter/配置优先级）+ Z
- loadbalancer-lab（SC LB 4.1.4）：00/A + D1-D7（接口/装饰器链/自动装配/Weighted/阻塞桥接/Lifecycle/拦截器）+ Z
- 两 lab REVIEW：版本重定基（4.3.2→4.1.4 等）+ 编造清除（ApiVersion 落差段重写等）

---

## 版本与来源

OpenFeign **4.1.3**（Cloud 2023.0.3）+ spring-cloud-loadbalancer **4.1.4**（SC 2023.0.3）；源码语料 `code/spring/spring-cloud-openfeign` + `spring-cloud-commons-4.1.4`；xhs-project 配置与自研 LB 代码。

## 真实性说明

Feign/LB 机制为 4.1.3/4.1.4 源码核验结论（两 lab 逐篇审过，含行数与编造清除）；xhs 用法（重试关闭理由/内部调用头/自研 LB/超时值）为代码事实。
