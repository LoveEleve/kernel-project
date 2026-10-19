# FEIGN-07 LB 与重试

> 用途：Feign 的"选实例"（承接 LB 系列）与"两层重试"（Feign Retryer × LB 层重试）——以及 xhs 的 `NEVER_RETRY` 立场
> 对应系列：**OpenFeign 专题 · 07**（使用篇第五篇）
> 口径：SC OpenFeign 4.1.3 / SCLB 4.1.4 / feign-core 13.3
> 素材：`openfeign-lab`（[D3 FeignBlockingLoadBalancerClient](../../openfeign-lab/docs/D3-FeignBlockingLoadBalancerClient源码分析.md) / [A-07 负载均衡](../../openfeign-lab/docs/A-07-负载均衡.md)）· xhs-project（`FeignSafeConfig`）· 源码（`FeignBlockingLoadBalancerClient` / `FeignClientFactoryBean` / `FeignClientsConfiguration`）
> 前置：05（超时）/ 06（fallback）；**建议先读** `docs/loadbalancer` 04/07/08（阻塞路径 / 摘除 / 重试两口味）

---

## 0. 例子先行：`@FeignClient(name = "my-xhs-order")` 的两次"接力"

```text
① 选实例：Feign 发出的请求 URL = "http://my-xhs-order/api/..."（host = 服务名）
   → 谁把它换成 192.168.x.x:8080？【FeignBlockingLoadBalancerClient】
      拦截 → choose（交给 SCLB）→ reconstructURI → 交给真正的 HTTP Client

② 重试：两台实例挂了一台，默认会不会换一台重试？
   → 要分两层看：Feign 自己的 Retryer（SC 默认 NEVER_RETRY）
                  vs LB 层重试（能"换实例"，需 spring-retry，LB-08 已讲）
```

本篇把"接力"拆开：§2 选实例、§3 两层重试。

---

## 1. 掌握卡（3 条）

1. **选实例发生在 `Client` 层**：`FeignBlockingLoadBalancerClient`（D3）——`serviceId = URL 的 host`（即 `@FeignClient` 的 name）交给 SCLB；**LB 的所有配置（supplier 链/算法/`clients.<svc>.*`）对它同样生效**（同一套 `LoadBalancerClientFactory`）。
2. **两层重试别混**：**Feign 层 `Retryer`**（feign-core 默认是 `Default`（**5 次**），但 **Spring Cloud 覆盖为 `NEVER_RETRY`**——`FeignClientsConfiguration:162`）；**LB 层重试**（`RetryableFeignBlockingLoadBalancerClient` + `spring.cloud.loadbalancer.retry.*`，能换实例）。xhs：**Feign 层全局 NEVER_RETRY**（`FeignSafeConfig`）。
3. **两个行为细节**：**无实例 → 返回 503 Response**（不是抛异常，走 ErrorDecoder）；**重试在 Client 内层、Sentinel fallback 在外层**——重试全部耗尽后 fallback 才被调用（一次）。

---

## 2. 选实例：`FeignBlockingLoadBalancerClient`（D3 展开）

`execute` 的骨架（源码节选）：

```java
public Response execute(Request request, Request.Options options) throws IOException {
    URI originalUri = URI.create(request.url());
    String serviceId = originalUri.getHost();                      // ★ @FeignClient 的 name
    String hint = getHint(serviceId);                              // ★ hint 也走 Feign 路径（LB-06）
    DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(
            new RequestDataContext(buildRequestData(request), hint));
    lifecycle.onStart(lbRequest);

    ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest);   // ★ 交给 SCLB

    if (instance == null) {                                        // ★ 无实例：不是异常！
        return Response.builder().status(503)
            .body("Load balancer does not contain an instance for the service " + serviceId, ...)
            .build();
    }
    String reconstructedUrl = loadBalancerClient.reconstructURI(instance, originalUri).toString();
    Request newRequest = buildRequest(request, reconstructedUrl, instance);   // host:port 换掉
    return executeWithLoadBalancerLifecycleProcessing(delegate, options, newRequest, ...);  // 真发
}
```

六步要点：

| # | 步骤 | 说明 |
|---|---|---|
| 1 | `serviceId = host` | `http://my-xhs-order/...` → `"my-xhs-order"` |
| 2 | `hint` | `getHint(serviceId)` 读 LB 配置 **`spring.cloud.loadbalancer.hint.<serviceId\|default>`**（缺省字面量 `"default"`）——Feign 路径的 hint 来自**配置**（网关路径才常用请求头，LB-06） |
| 3 | `choose(serviceId, lbRequest)` | 进 SCLB：supplier 链 + 算法（LB 系列 03/05 的全部逻辑） |
| 4 | **无实例 → 503 Response** | 不是异常；响应体关键词 `"Load balancer does not contain an instance"`（排障记号）——随后走 Decoder/ErrorDecoder |
| 5 | `reconstructURI` | 用实例 `host:port` 替换（`LoadBalancerUriTools`） |
| 6 | `delegate.execute` | 真正的 HTTP 客户端（HC5/OkHttp…）；LB transformers 可最后改请求（如 X-Forwarded，A-14） |

**`url=` 直连会绕过 LB**（源码注释原文 "not load balancing because we have a url"）：配了 `url` 后，FactoryBean 会把 `FeignBlockingLoadBalancerClient` **unwrap** 成 delegate——单点调试/直连场景用，别在生产误配。

---

## 3. 重试：两层 + 与超时/降级的次序

### 3.1 Feign 层：`Retryer`（默认 NEVER_RETRY 的真相）

| 事实 | 值 | 来源 |
|---|---|---|
| feign-core 默认 | `Retryer.Default`（100ms 起、最大 1s、**最多 5 次**） | feign-core `Retryer:40` |
| **Spring Cloud 覆盖** | **`Retryer.NEVER_RETRY`** | `FeignClientsConfiguration:162-163` |
| xhs 再显式封一层 | 全局 `NEVER_RETRY` | `FeignSafeConfig`（§4） |

即：**"Feign 默认会重试 5 次"是 feign-core 的历史口径；Spring Cloud 场景下默认是不重试**——要开就在自己的 `Feign.Builder`/配置里显式提供 `Retryer`（xhs 反过来：显式钉死 NEVER_RETRY）。

### 3.2 LB 层重试（能换实例的那个）

`RetryableFeignBlockingLoadBalancerClient`（开启时替换 §2 的 Client）：`retryTemplate.execute(...)` 包住整段"选实例+发送"——**重试发生在 Client 内部**。开关/参数/两口味全部见 **LB-08**（`spring.cloud.loadbalancer.retry.*`、`spring-retry` 前置、换台 vs 原台）。

### 3.3 次序与放大（串起来）

```text
SentinelInvocationHandler（06：fallback 外层）
  └─ RetryableFeignBlockingLoadBalancerClient（重试循环，Client 内层）
       └─ choose → 发送 → 失败 → （可重试则换实例再来）…
     重试全部耗尽 → 异常冒泡到外层 → fallback 只触发一次

单请求最坏耗时 ≈ read-timeout × (1 + 重试次数)   （05 的乘法矩阵）
```

| 层 | 机制 | 默认 | xhs |
|---|---|---|---|
| Feign Retryer | 同请求原样重发（不懂换实例） | NEVER_RETRY | **全局 NEVER_RETRY** |
| LB 层重试 | 可换实例/原实例（RetryAware） | enabled、next=1、5xx 需显式配；**需 spring-retry 依赖**（LB-08） | **未引入 spring-retry → 未启用**（唯一显式立场是 Feign 层 NEVER_RETRY） |
| 网关层 Retry filter | L37（重试在外、熔断在内） | 关 | 网关按路由配 |

---

## 4. 生产对照：xhs 的重试立场（`FeignSafeConfig`）

```java
@Configuration
@ConditionalOnClass(name = "feign.Retryer")
public class FeignSafeConfig {

    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;        // ★ 全局关闭 Feign 重试
    }
    //（还有自定义 ErrorDecoder —— 08 展开）
}
```

其注释原文给出了理由与替代路径：

> "非幂等接口重试会导致重复操作（如重复下单）……幂等接口（如查询）即使不重试也不会有问题（客户端可以手动重试）；**如果某些幂等接口确实需要重试，在该 FeignClient 上单独配置 Retryer**。"

另注：该类的 javadoc 里还写着 `feign.client.config.default.*` 旧前缀（实际生效的是 `spring.cloud.openfeign.client.config.*`——03 已核，属 stale 注释）。

顺带一个生产细节：`my-xhs-home` 关掉了 LB 缓存（`spring.cloud.loadbalancer.cache.enabled: false`）——为更快感知实例变化（摘除窗口从 35s 缩到近实时），代价是注册中心压力上升（LB-07 的 trade-off）。

---

## 5. 坑清单

| # | 坑 | 说明 |
|---|---|---|
| 1 | 两层重试双开 | Feign Retryer × LB 重试 = 乘法放大（写操作灾难）；只留一层（xhs：Feign 层关） |
| 2 | "Feign 默认重试 5 次"的历史印象 | SC 已覆盖为 NEVER_RETRY；自己 new `Feign.Builder` 时注意别把 `Default` 带回来 |
| 3 | 无实例被当异常排查 | 它是 **503 Response**（关键词 "Load balancer does not contain an instance"）→ 走 ErrorDecoder；先查注册中心/名单，不是查网络 |
| 4 | `url=` 直连绕 LB | 源码显式 unwrap；生产误配 = 负载均衡静默失效 |
| 5 | 5xx 默认不触发 LB 重试 | `retryable-status-codes` 默认空（LB-08） |
| 6 | 重试 × 超时 | read-timeout × (1+retries)（05 矩阵），改超时先看重试配置 |

---

## 6. Self-Test

1. `@FeignClient(name=...)` 的请求，URL 里的服务名是在哪一步、被谁换成真实地址的？
2. "Feign 默认会重试 5 次"这句话在 Spring Cloud 里还成立吗？为什么？
3. 无实例时发生什么？（返回什么、去哪查）
4. 一次调用里，重试和 fallback 的先后次序？fallback 会被触发几次？

---

## 7. 相关与衔接

- **08 进阶**：`FeignSafeConfig` 的自定义 `ErrorDecoder`（4xx/5xx → BizException/RemoteException）；`FeignUnifiedConfig` 的 Decoder 增强；
- **10 生产与收口**：三层重试的排障；
- `docs/loadbalancer`：[04 阻塞路径](../loadbalancer/04-sclb-architecture.md) / [07 摘除](../loadbalancer/07-instance-churn.md) / [08 重试两口味](../loadbalancer/08-retry-sticky-hash.md)；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[D3 FeignBlockingLoadBalancerClient](../../openfeign-lab/docs/D3-FeignBlockingLoadBalancerClient源码分析.md) / [A-07 负载均衡](../../openfeign-lab/docs/A-07-负载均衡.md)；
- 网关课程：[L37](../gateway/L37-circuit-breaker-retry.md)（网关层重试）。
