# Z: Feign 面试题速答（收束篇）

> 定位：openfeign-lab 收束篇——把 A（生产使用）/B（扩展点）/D（核心原理）的结论面试化。
> 基线：**OpenFeign 4.1.3（Cloud 2023.0.3）+ SCA 2023.0.1.2**（xhs-project 口径）。
> 用法：先自测（盖住答案），答不出的回对应文档；证据与坐标见各篇。

---

## 一、速记卡（一页纸）

1. Feign = **声明式 HTTP 客户端**：接口 + 注解 → JDK 动态代理；核心 = 注解契约 + 编解码 + 负载均衡 + 熔断。
2. `@EnableFeignClients` → `FeignClientsRegistrar`（`ImportBeanDefinitionRegistrar`）扫描注册 `FeignClientFactoryBean`（**默认 Eager**）。
3. `FeignClientFactory`（`NamedContextFactory`）为每个客户端建**子容器**；`@FeignClient(configuration)` 的 Bean 只进该子容器。
4. 配置优先级（`defaultToProperties=true` 默认）：**clientName.\* > default.\* > configuration 类 > 框架默认**（后应用者胜）。
5. 编码：`SpringEncoder`（表单 → `SpringFormEncoder`；其余 → `HttpMessageConverter` 链）；解码：`OptionalDecoder → ResponseEntityDecoder → SpringDecoder`。
6. LB：`FeignBlockingLoadBalancerClient` 从 URL host 取 serviceId → `choose` → `reconstructURI`；**无实例返回 503**（不抛异常）。
7. 重试：Feign 层默认 `Retryer.NEVER_RETRY`；LB 层 `RetryableFeignBlockingLoadBalancerClient`（`RetryTemplate` + 可重试状态码 + 可换实例）。
8. 熔断两种集成：**Sentinel 换 `Feign.Builder`**（InvocationHandlerFactory 层，父容器）；**CircuitBreaker 换 `Targeter`**（子容器）。
9. fallback 实例从**子容器**取；`fallback` 固定实例（`FallbackFactory.Default`），`fallbackFactory` 可拿 `Throwable`。
10. 有 URL 直连：`getTarget()` 用 `getDelegate()` **unwrap** LB Client，跳过负载均衡（否则 choose("localhost") → 503）。

---

## 二、分组高频题

### A. 定位与声明式调用

**A1. Feign 是什么？**
一句话：声明式 HTTP 客户端——定义接口加注解，框架生成 JDK 动态代理，把方法调用翻译成 HTTP 请求。
展开：四块拼图 = 注解契约（`SpringMvcContract`）+ 编解码（`HttpMessageConverter` 体系）+ 负载均衡（Spring Cloud LoadBalancer）+ 容错（Sentinel/CircuitBreaker）。回链：A-01/D1。

**A2. `@FeignClient` 的 name/value/contextId 区别？**
`name`/`value`（互为别名）是**服务名**；`contextId` 是**子容器标识**，用于同一服务的多个客户端不同配置。
两套解析：`getName()` 优先级 `serviceId > name > value`；`getClientName()` 优先级 `contextId > value > name > serviceId`。回链：D2 §3.5。

**A3. 为什么要 contextId？**
同一服务可注册多个客户端、各自独立超时/拦截器/fallback。例：`@FeignClient(name="nacos-lab", contextId="nacos-lab-v1")` 与 `...-v2` 各占一个子容器。回链：A-08/D2 §6.4。

**A4. `@EnableFeignClients` 做了什么？**
`@Import(FeignClientsRegistrar.class)` → `registerBeanDefinitions()` → 注册 `default.{AppClass}` 全局配置 + 扫描每个 `@FeignClient` 注册 `FeignClientSpecification` 与 `FeignClientFactoryBean`。回链：D2 §2。

**A5. Eager 和 Lazy 注册模式？**
默认 **Eager**（`lazy-attributes-resolution=false`）：注册阶段解析属性，仅 `${...}`；`=true` 才 Lazy：首次 `getBean()` 时解析（`${...}` + SpEL `#{}`），factoryBean 存进 BeanDefinition attribute（供 SentinelFeign 取）。回链：D2 §2.4-2.6。

### B. 代理创建链

**B1. 一次 `stub.method()` 内部走了哪些对象？**
JDK 代理 → InvocationHandler（`FeignInvocationHandler` / `SentinelInvocationHandler` / `FeignCircuitBreakerInvocationHandler`）→ `SynchronousMethodHandler.invoke()` → `buildTemplateFromArgs.create()`（内部 `encoder.encode`）→ `RequestInterceptor.apply` → `Client.execute`（LB 装饰器）→ `decoder.decode`。回链：D4 §6/D3 §7。

**B2. Targeter 是什么？**
代理创建的"最后一公里"：`DefaultTargeter`（直接 `feign.target`）或 `FeignCircuitBreakerTargeter`（有 fallback/fallbackFactory 时包装）。回链：D6 §2。

**B3. `FeignClientFactoryBean` 为什么不能用 `@Autowired`？**
类头 WARNING：生命周期竞争会 NPE。它由 Registrar 手动注册，所有属性走 `BeanDefinition.addPropertyValue()` + setter；`beanFactory`/`applicationContext` 走 Aware 回调。回链：D1 §2。

### C. 编码与解码

**C1. `SpringEncoder` 怎么选转换器？**
表单类 Content-Type（multipart/form-data|mixed|related、x-www-form-urlencoded）→ `SpringFormEncoder`；其余遍历转换器列表，第一个 `canWrite()` 为 true 的胜出，`write` 到 `FeignOutputMessage`。回链：D4 §2。

**C2. 为什么 `encode()` 会清空 Header？**
`request.headers(null)` + 用转换器产出的 Header 替换（转换器可能补 charset）。**拦截器在 encode 之后执行**，所以 A-05 的 `Content-Encoding: gzip` 不会被清掉。回链：D4 §2.3/§6。

**C3. charset 的三种决策？**
`charsetFromContentType=true` 且有 charset → 用 Content-Type 的；二进制/`ByteArrayHttpMessageConverter`/protobuf → `null`；否则 `UTF-8`。回链：D4 §2.3。

**C4. 解码链为什么三层？**
`OptionalDecoder`（`Optional<T>` 且 404/**204** → empty）→ `ResponseEntityDecoder`（`ResponseEntity`/`HttpEntity` 保留状态码与头）→ `SpringDecoder`（`HttpMessageConverterExtractor` 反序列化）。回链：D4 §4。

**C5. 404 什么时候抛异常、什么时候返回空？**
`dismiss404`（4.1.3 无 `decode404`）决定 404 是否视为正常响应（走解码而非抛错）；`Optional<T>` 返回类型在 404/204 返回 `Optional.empty()`；`ResponseEntity` 则保留 404 状态码。回链：D4 §4.2/D7。

### D. 配置体系

**D1. 配置优先级规则？**
`defaultToProperties=true`（默认）时，**后应用者胜**：`clientName.* > default.* > @FeignClient(configuration) 的 @Bean > 框架默认`；`=false` 时 configuration 覆盖属性。回链：D7/D1 §5。

**D2. 属性前缀是什么？**
4.x 是 **`spring.cloud.openfeign.*`**（`feign.*` 是 2.x/3.x 旧前缀；仅 SCA 的 `feign.sentinel.enabled` 保留 `feign.` 前缀）。回链：D5/D7。

**D3. 超时怎么配、怎么生效？**
`spring.cloud.openfeign.client.config.<name>.connectTimeout/readTimeout`；`refresh-enabled=true` 时由 `OptionsFactoryBean`（@RefreshScope）管理、`configureUsingProperties` 跳过超时（避免冲突）。回链：D1 §6/D7。

**D4. `@FeignClient(configuration)` 的 Bean 在哪个容器？**
在客户端**子容器**（`FeignClientFactory` 创建的 `AnnotationConfigApplicationContext`）；`getInheritedAware*` 按 `inheritParentContext` 决定是否回看父容器。回链：D5 §1/D1 §5.3。

### E. 负载均衡与重试

**E1. Feign 怎么从服务名找到实例？**
`FeignBlockingLoadBalancerClient.execute`：`URI.getHost()` 取 serviceId → `LoadBalancerClient.choose(serviceId, lbRequest)` → `reconstructURI(instance, originalUri)` 替换 host:port → `delegate.execute`。回链：D3 §2。

**E2. 没有可用实例会怎样？**
**返回 503 Response**（`SERVICE_UNAVAILABLE`）而不是抛异常——让 `ErrorDecoder`/fallback 能处理；同时生命周期回调 `onComplete(DISCARD)`。回链：D3 §2.2。

**E3. 为什么有 URL 时要 unwrap？**
直连 URL 的 host 是 `localhost` 之类，`choose("localhost")` 找不到服务 → 503。`getTarget()` 用 `getDelegate()` 取出底层 Client（如 `ApacheHttp5Client`）跳过 LB。回链：D1 §3.3/D3 §6。

**E4. `LoadBalancerFeignRequestTransformer` 是干嘛的？**
LB 层请求转换链（重构 URL 后执行）：内置 `XForwardedHeadersTransformer`（`x-forwarded.enabled` 时加 `X-Forwarded-Host/Proto`），可自定义加实例标识等。回链：D3 §3。

**E5. Feign 自带重试吗？**
Feign 层默认 `Retryer.NEVER_RETRY`；**LB 层**有 `RetryableFeignBlockingLoadBalancerClient`（`RetryTemplate` + `retryableStatusCode`，重试可换实例：`previousServiceInstance`），由 `spring.cloud.loadbalancer.retry.enabled`（默认 true）与 spring-retry 依赖决定。回链：D3 §4/D5 §3.4。

### F. 熔断与降级

**F1. Sentinel 怎么集成进 Feign？**
SCA 的 `SentinelFeignAutoConfiguration` 在**父容器**注册 `feignSentinelBuilder`（prototype）→ 子容器 `@ConditionalOnMissingBean` 跳过自己的 Builder → `SentinelFeign.Builder.internalBuild()` 替换 `InvocationHandlerFactory` → `SentinelInvocationHandler`（`SphU.entry` 流控 + fallback）。回链：D6 §5。

**F2. CircuitBreaker 怎么集成？**
`FeignCircuitBreakerTargeter`（条件：`circuitbreaker.enabled=true` + `CircuitBreakerFactory`）在 `target()` 里包装：`FeignCircuitBreakerInvocationHandler`。回链：D6 §2.2/§3。

**F3. fallback 和 fallbackFactory 的区别？**
`fallback=类`：固定实例，`FallbackFactory.Default` 包装、每次返回同一对象、拿不到异常；`fallbackFactory=类`：`create(Throwable)` 可拿异常、每次可新建。回链：D6 §4。

**F4. fallback 实例从哪来？**
从**子容器**取（`context.getInstance(beanName, beanType)`）；若是 `FactoryBean` 再 `getObject()`；类型不可赋值给接口则抛 `Incompatible ...`。回链：D6 §2.2。

**F5. Sentinel 和 CircuitBreaker 同时启用会怎样？**
Sentinel Builder 胜出（父容器覆盖子容器），但 Targeter 可能仍是 `FeignCircuitBreakerTargeter`；它发现 `feign !instanceof FeignCircuitBreaker.Builder` → 跳过包装 → 最终由 `SentinelInvocationHandler` 保护。回链：D6 §6。

### G. 生产与排障

**G1. 日志怎么开？**
`spring.cloud.openfeign.client.config.<name>.loggerLevel`（NONE/BASIC/HEADERS/FULL）+ 对应 Logger 级别（Feign 日志走 `feign.Logger`，需把 logger 调到 DEBUG）。回链：D7/D1 §4。

**G2. 常见坑有哪些？**
① `name` 含非法字符（下划线）→ `getName` 校验抛异常；② 直连不 unwrap → 503；③ encode 清 Header 导致误判拦截器失效（其实顺序在后）；④ 超时叠加（Feign 与底层 HTTP client）；⑤ 重试要幂等。回链：D2 §3.5/D3 §6/D4 §6。

**G3. 怎么切换底层 HTTP 客户端？**
`spring.cloud.openfeign.httpclient.hc5.enabled`（默认 true）/`http2client.enabled`（显式）/`okhttp.enabled`（显式）；LB 场景对应 `*FeignLoadBalancerConfiguration` 四个配置类（OkHttp 需显式开启）。回链：A-04/D3 §5。

**G4. Feign vs RestClient？**
Feign 声明式 + 自带 LB/熔断/重试集成；RestClient 编程式、Spring 原生、无代理开销。两者都运行时解码、都有编译期类型约束。回链：A-09。

**G5. Feign vs Dubbo vs gRPC？（对照题）**
Feign：HTTP/1.1+JSON，Spring Cloud 生态客户端；Dubbo：TCP+治理（路由/容错/标签），Triple 兼容 gRPC；gRPC：HTTP/2+protobuf、跨语言、流式。回链：A-09/Dubbo 系列/gRPC 系列。

---

## 三、陷阱速查（15 连）

| # | 反直觉点 | 正解 |
|---|---|---|
| 1 | `getName` 和 `getClientName` 优先级一样 | 前者 `serviceId>name>value`；后者 `contextId>value>name>serviceId` |
| 2 | 默认 Lazy 解析属性 | 默认 **Eager**（`lazy-attributes-resolution=false`） |
| 3 | `defaultToProperties=true` 时 configuration 类优先 | **属性覆盖 configuration**（后应用者胜） |
| 4 | encode 清 Header 会把拦截器头也清掉 | 拦截器在 encode **之后**执行，不受影响 |
| 5 | Feign 默认有重试 | Feign 层 `NEVER_RETRY`；重试在 LB 层（Retryable...） |
| 6 | 无实例抛异常 | 返回 **503 Response** |
| 7 | 直连也走 LB | 直连必须 `getDelegate()` unwrap |
| 8 | fallback 可以是接口 | 必须是**具体类**（`validateFallback` 校验） |
| 9 | fallback 实例从父容器取 | 从**子容器**取（`context.getInstance`） |
| 10 | Sentinel 通过 Targeter 集成 | 通过**替换 Feign.Builder**（InvocationHandlerFactory 层） |
| 11 | CircuitBreaker 通过 Builder 集成 | 通过 **Targeter**（`FeignCircuitBreakerTargeter`） |
| 12 | 重试一直打同一实例 | 可换实例（`previousServiceInstance`） |
| 13 | Sentinel 属性是 `spring.cloud.openfeign.*` | 是 **`feign.sentinel.enabled`**（SCA 前缀） |
| 14 | 4.x 还用 `feign.*` 前缀 | 4.x 是 **`spring.cloud.openfeign.*`** |
| 15 | `Optional` 404 抛异常 | 404/**204** 返回 `Optional.empty()` |

---

## 四、文档索引（回链）

| 主题 | 文档 |
|---|---|
| 声明式调用 / 拦截器 / 超时 / 客户端切换 / 压缩 / 熔断 / LB / 多版本 / RestClient | A-01 ~ A-09 |
| 自定义扩展点（Contract/Encoder/Decoder/ErrorDecoder/InvocationHandlerFactory） | B-02~B-05 |
| 注册机制（Registrar） | D1→D2 |
| FactoryBean / 代理创建 | D1 |
| 子容器 / 自动配置 / 编码解码 | D5 / D4 |
| LB Client / Targeter / SentinelFeign | D3 / D6 |
| 配置优先级 | D7 |
| 评审与修复记录（4.1.3 基线、编造段清除） | REVIEW-2026-10-05 |

---

## 五、一页纸收束

> **注册**：`@EnableFeignClients` → Registrar 扫 `@FeignClient` → FactoryBean（Eager 默认）；每个 client 一个子容器。
> **创建**：`getTarget()` → `feign()`（Builder + Encoder/Decoder/Contract）→ Targeter → JDK 代理。
> **调用**：encode（转换器链）→ 拦截器 → Client（LB 装饰器：choose/reconstruct/transformer）→ decode（三层包装）。
> **配置**：`spring.cloud.openfeign.*`；优先级 clientName > default > configuration > 框架默认。
> **容错**：LB 无实例 503；重试在 LB 层；Sentinel 换 Builder、CircuitBreaker 换 Targeter；fallback 从子容器取。
