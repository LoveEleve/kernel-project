# L45 Gateway × Sentinel：功能重叠、选型与限流落地

> 用途：回答"网关要限流/熔断，用 SCG 自带还是 Sentinel"——功能重叠对照、选型判断、分层限流
> 对应课程：**L45**（Stage 9 生态打通 · 第二课，★ 回答"功能冲突"）
> 口径：SCG **4.1.5** / Spring Cloud 2023.0.3 / SCA **2023.0.1.2** / Sentinel **1.8.8** / Spring WebFlux **6.1.6** / Nacos **2.2.3**；实测 2026-10-07
> 实验环境：demo-gateway:8080 · demo-provider:8081（v1）· 本机 Nacos + Redis
> 生产对照：xhs-project `my-xhs-gateway`（starter + raw gateway-adapter + Nacos 规则数据源；本课接线与其一致）
> 前置：L36（RequestRateLimiter）/ L37（CircuitBreaker · Resilience4j）/ L44（Nacos 配置中心）

---

## 0. 例子先行：同一个请求，两道防线

大促时 `/api/order/**` 被打爆——既要防"全局过载"（入口限流），也要防"单用户刷接口"（参数级限流），还希望"下游变慢时自动快速失败"（熔断）。SCG 自带能覆盖一部分（L36 令牌桶 / L37 Resilience4j 熔断），Sentinel 也能覆盖一部分——**这就是"功能冲突"：重叠的地方必须二选一，否则两套规则两本账**。

本课在 demo 网关上接 Sentinel，跑通三条请求线：

```text
GET /sentinel-lab/echo/hi            ← 网关 route 级限流（资源 = route id "sentinel-lab"）→ 429 统一 JSON
GET /sentinel-lab/echo/hi  + X-User-Id ← 参数级限流（每个用户独立额度：A 用户被拦，B 用户照常）
GET /provider/echo/hi                ← 对照：gateway 无规则；provider 侧规则拦（"Blocked by Sentinel (flow limiting)"）
```

被拦时（本项目自定义 Handler）：

```json
{"code":429,"message":"请求过于频繁，请稍后再试","data":null}
```

---

## 1. 掌握卡（3 条）

1. **功能重叠对照**：限流/熔断两套都能做（选择取决于生态）；**"按值分桶"（userId/header）两边都能做**——Sentinel 的增量是内置解析 + `pattern` 过滤 + 本地统计（不算硬理由）；**API 分组、系统自适应是 SCG 没有现成能力的**；**集群精确限流是 SCG `RedisRateLimiter` 的优势项**（Sentinel 集群限流要 Token Server）。
2. **选型判断**：组织已有 Sentinel 体系（服务已在用）→ 网关也接 Sentinel，统一规则中心/控制台/埋点（xhs-project 生产即此）；只有网关要限流 → 用 SCG 自带（少依赖，RedisRateLimiter 还天然集群）。
3. **分层限流**：网关层（入口，防全局过载）vs 服务层（精细，保护单服务资源）。两层**资源名语义不同**：网关侧 = **route id**；服务侧 = **MVC 的 `BEST_MATCHING_PATTERN`**（`/echo/{msg}`，不是原始 URI）。

---

## 2. 功能重叠对照表（Gateway 自带 vs Sentinel）

| 能力 | SCG 自带 | Sentinel（网关适配器） | 结论 |
|---|---|---|---|
| **route 级 QPS 限流** | `RequestRateLimiter` + `RedisRateLimiter`（L36；Redis+Lua 令牌桶） | `GatewayFlowRule`（本地滑动窗口 LeapArray；`count`/`intervalSec`/`burst`/`controlBehavior`） | **重叠**：都能做；差异在依赖/集群性/参数 |
| **集群（多网关实例）限流** | ✅ 天然（Redis 共享令牌桶） | ⚠️ 默认**单机**；集群限流需 Token Server（sentinel-lab D9，未实测） | SCG 优势项 |
| **参数级 / 热点限流** | ⚠️ **可做**：`KeyResolver` 返回 userId → 每用户一个 Redis 桶（阈值路由级统一；解析/过滤要自己写） | ✅ 内置解析（header/IP/query/cookie）+ 每值独立阈值 + `pattern` **过滤**（只限匹配的值，不匹配直接放行） | **并非独占**：分桶两边都能做；Sentinel 更省事（实验 B 实测） |
| **API 分组限流** | ❌ 无直接对应（每路由各配） | ✅ `ApiDefinition` + `CUSTOM_API_NAME`：一组路径共享一个额度 | Sentinel 独有（源码可见 `adapter/gateway/common/api`；本课未实测） |
| **熔断** | `CircuitBreaker` 过滤器 + Resilience4j（L37；`statusCodes` 要显式配，5xx 默认不算失败） | `DegradeRule`（RT/异常比例/异常数） | **重叠**：两套并存=两本账，要二选一（实验 E 实测 RT 熔断） |
| **降级动作** | `fallbackUri`（forward/跳转，L37） | BlockHandler 统一响应（可自定 body/状态码，实验 A） | 概念不同：一个是"失败后去哪"，一个是"拒绝怎么答" |
| **系统自适应保护** | ❌ | ✅ `SystemRule`（load/CPU/RT 维度，Sentinel 核心机制） | Sentinel 独有（未实测） |
| **规则持久化 / 热更新** | yml 改完重启（或 Actuator 刷新内存态） | **Nacos 数据源**：持久化 + 推送热更新（实验 D；xhs 生产同款） | 有 Sentinel 体系时的关键优势 |
| **观测** | Actuator `gateway`/`metrics` | Sentinel Dashboard（本项目未部署）；`/actuator/sentinel` **看不到 gw-flow 规则**（实验 D 盲点） | 需额外设施 |

> **一句话**：**重叠区（限流/熔断）二选一；独有区（参数级/API 分组/系统自适应）是选 Sentinel 的理由；集群限流无 Sentinel 设施时选 SCG Redis 方案。**

---

## 3. 选型判断（含 xhs 生产实证）

**决策规则**：

```text
组织已有 Sentinel 体系（服务端在用、有 Dashboard/规则中心）？
├─ 是 → 网关接入 Sentinel（规则统一到一处；服务/网关同一套控制台与埋点）   ← xhs-project 生产路径
└─ 否 → 只有网关要限流？
        ├─ 是 → SCG 自带 RequestRateLimiter（少一个依赖；RedisRateLimiter 天然集群）
        └─ 需要参数级/API 分组/系统自适应 → Sentinel
```

**xhs-project 生产实证**（`my-xhs-gateway`）：

| 项 | 生产做法 |
|---|---|
| 依赖 | `spring-cloud-starter-alibaba-sentinel` + **raw** `sentinel-spring-cloud-gateway-adapter` + `sentinel-datasource-nacos`（没用 SCA 的 `spring-cloud-alibaba-sentinel-gateway` 模块） |
| 接线 | `RateLimitFilter` 手写 `GlobalFilter` 委托 `SentinelGatewayFilter`，order = `HIGHEST_PRECEDENCE + 2500`（**排在 HMAC 签名校验之后**） |
| BlockHandler | **两个都注册**（Gateway + WebFlux），统一 429 JSON（T-106 发现，见 §4.4） |
| 规则 | Nacos 数据源 `my-xhs-gateway-sentinel-flow.json`（namespace `my-xhs`，rule-type `gw-flow`）+ yml 路由 `metadata.rate-limit-qps` 本地兜底 + **30s 真空期**逻辑 |
| 服务侧 | payment/im/analytics 等也接 Sentinel（Dashboard `192.168.0.142:8858`）——**网关与服务同一规则体系** |

---

## 4. 实验 A：接入 + route 级限流（★ T-106 默认 BlockHandler 兼容坑）

### 4.0 先看懂：Sentinel 接入网关的四个零件

Sentinel 核心只会做一件事：**把一个"资源"包起来做统计和判定**——规则说不行就直接拒绝。所以"接入 Gateway"= 在网关的请求链路上找位置埋点，把请求变成 Sentinel 能统计的"资源"。就四个零件：

| 零件 | 干什么 | 本项目对应 |
|---|---|---|
| ① **埋点 Filter** | 请求经过时 `entry`/`exit`（进入统计、离开记账） | `SentinelGatewayFilter`（GlobalFilter，资源=**route id**）+ starter 自带的 `SentinelWebFluxFilter`（资源=URL 路径） |
| ② **资源名** | 统计和规则挂在谁头上 | 网关侧 = **route id**（如 `sentinel-lab`）；另有 URL 路径 / API 组名两个维度 |
| ③ **规则** | 阈值与行为从哪来 | `GatewayRuleManager`（内存）；来源 = 代码（实验 A/B）/ Nacos（实验 D）/ Dashboard |
| ④ **拒绝响应** | 被拦后回什么 | `BlockException` → `WebExceptionHandler` 接住 → `BlockRequestHandler` 回调决定响应（本项目自定义 429 统一 JSON） |

一条请求的完整路线（以实验 A 为例）：

```text
GET /sentinel-lab/echo/hi
  → [WebFilter]    SentinelWebFluxFilter    entry(资源=/sentinel-lab/echo/hi)
    → [GlobalFilter] SentinelGatewayFilter  entry(资源=sentinel-lab)  ★ 规则挂这
      → 转发 provider → 200
被拦时：BlockException → WebExceptionHandler → BlockHandler → 429 统一 JSON
```

两个接线事实（后面 §4.2/§4.5 展开）：

- **starter 只自动装配"通用 WebFlux"那套**（`SentinelWebFluxFilter` + 异常处理器）；而认识 route id 的 `SentinelGatewayFilter` 来自 raw 适配器，**没有自动装配，要手动注册 bean**——xhs 生产也是手动接线（SCA 另有自动装配模块 `spring-cloud-alibaba-sentinel-gateway`，本项目与生产都没用）；
- 网关里**两套适配器并存**（URL 级 + route 级）→ 有**两个 BlockHandler 回调**要注册（§4.4 的 T-106 就出在这）。

### 4.1 接线（与 xhs 生产一致：starter + raw gateway-adapter）

**pom**（3 个依赖 + 1 处排除）：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
    <exclusions>
        <!-- Sentinel starter 自带 spring-cloud-circuitbreaker-sentinel，与 resilience4j 抢
             ReactiveCircuitBreakerFactory（见 §4.5）→ 排除，熔断继续走 SCG 原生（L37） -->
        <exclusion>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-circuitbreaker-sentinel</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-spring-cloud-gateway-adapter</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
</dependency>
```

**配置类**（`com.myxhs.demo.gateway.sentinel.SentinelGatewayConfig`，完整）：

```java
package com.myxhs.demo.gateway.sentinel;

import com.alibaba.cloud.sentinel.datasource.converter.JsonConverter;
import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.spring.webflux.callback.WebFluxCallbackManager;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Configuration
public class SentinelGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelGatewayConfig.class);

    /** 统一限流响应体（与网关其余错误体格式保持一致） */
    private static final String BLOCK_BODY =
            "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}";

    @Value("${demo.sentinel.custom-block-handler:true}")
    private boolean customBlockHandler;

    /** route 级 Sentinel 入口（raw 适配器需手动注册为 GlobalFilter bean） */
    @Bean
    public SentinelGatewayFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    @PostConstruct
    public void initBlockHandlers() {
        if (!customBlockHandler) {
            log.warn("[L45-Sentinel] 自定义 BlockHandler 已禁用 —— 将复现 1.8.8 默认 Handler 的 Spring 6 兼容问题");
            return;
        }
        // Gateway 适配器（route 级资源被限流时走这里）
        GatewayCallbackManager.setBlockHandler((exchange, t) -> blockResponse(exchange, t, "gateway"));
        // WebFlux 适配器（starter 自动装配的 SentinelWebFluxFilter，URL 级资源被限流时走这里）
        // ★ T-106：只注册 GatewayCallbackManager 不够 —— 实际生效的 WebExceptionHandler 是 WebFlux 那个
        WebFluxCallbackManager.setBlockHandler((exchange, t) -> blockResponse(exchange, t, "webflux"));
        log.info("[L45-Sentinel] 自定义 BlockHandler 已注册（Gateway + WebFlux 双回调）");
    }

    private Mono<ServerResponse> blockResponse(ServerWebExchange exchange, Throwable t, String source) {
        log.info("[L45-Sentinel][{}] 请求被限流: resource={}, path={}",
                source, t.getClass().getSimpleName(), exchange.getRequest().getURI().getPath());
        return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Mono.just(BLOCK_BODY.getBytes(StandardCharsets.UTF_8)), byte[].class);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initFlowRules(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String nacosAddr = env.getProperty("spring.cloud.sentinel.datasource.flow.nacos.server-addr");
        if (nacosAddr != null && !nacosAddr.isEmpty()) {
            log.info("[L45-Sentinel] Nacos 规则数据源已配置({})，跳过本地规则，等待异步推送", nacosAddr);
            return;
        }
        Set<GatewayFlowRule> rules = new HashSet<>();
        // 本地兜底示例：参数级限流（实验 B；实验 A 用的是 route 级 setCount(2).setIntervalSec(1)）
        rules.add(new GatewayFlowRule("sentinel-lab")
                .setCount(1)
                .setIntervalSec(10)
                .setParamItem(new GatewayParamFlowItem()
                        .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_HEADER)
                        .setFieldName("X-User-Id")));
        GatewayRuleManager.loadRules(rules);
        log.info("[L45-Sentinel] 本地限流规则已加载: {}", rules);
    }
}
```

**路由**（application.yml，指向 demo-provider）：

```yaml
- id: sentinel-lab                       # ← 资源名就是 route id
  uri: http://localhost:8081
  predicates:
    - Path=/sentinel-lab/**
  filters:
    - StripPrefix=1
```

### 4.2 重要事实：网关里**两套** Sentinel 适配器同时在跑

```text
请求 → [WebFilter]  SentinelWebFluxFilter（starter 自动装配；资源 = URL 路径，如 /sentinel-lab/echo/hi）
      → [GlobalFilter] SentinelGatewayFilter（本类手动注册；资源 = route id，如 sentinel-lab）★规则挂这
      → 下游 provider

BlockException → WebExceptionHandler（starter 的 SentinelBlockExceptionHandler, @Order(-2)）
              → WebFluxCallbackManager → 自定义 Handler   ← 实测生效路径
```

本项目**没有**注册网关适配器自带的 `SentinelGatewayBlockExceptionHandler`（xhs 生产也没有，除非引入 SCA 的 `spring-cloud-alibaba-sentinel-gateway` 模块）——所以 BlockException 实际由 WebFlux 的异常处理器接住，走 **WebFluxCallbackManager**。这就是 xhs T-106 注释"**仅注册 GatewayCallbackManager 不够**"的原因；稳妥做法是**两个回调都注册**（本类如此）。

### 4.3 复现 T-106：默认 BlockHandler 在 Spring 6.1.6 上会"悬挂"

把开关置 false（保持 Sentinel 1.8.8 默认 Handler），route 级规则 `count=2/s`，4 连发：

```text
req1: http=200 time=2.040s
req2: http=200 time=0.022s
req3: http=200 time=0.019s
req4: http=000 time=8.002s   ← 被限流后请求悬挂（curl max-time 8s 放弃）
```

网关日志（根因）：

```text
java.lang.NoSuchMethodError: 'org.springframework.web.reactive.function.server.ServerResponse$BodyBuilder
    org.springframework.web.reactive.function.server.ServerResponse.status(org.springframework.http.HttpStatus)'
```

**机制（字节码核实）**：1.8.8 的 `DefaultBlockRequestHandler` 调用 `ServerResponse.status(HttpStatus)` 和 `syncBody(...)`；而 Spring WebFlux 6.1.6 里：

- `ServerResponse.status` 只剩 `status(HttpStatusCode)` / `status(int)`（`HttpStatus` 重载不存在 → `NoSuchMethodError`）；
- `BodyBuilder.syncBody` 已移除。

两个默认 Handler（gateway 版 + webflux 版）都有同样问题 → **不自定义 BlockHandler 就一定踩**（生产记录：请求悬挂 30s 超时；xhs 于 2026-08-15 回归发现，记为 T-106）。

### 4.4 修复：自定义 BlockHandler（双回调）→ 统一 429 JSON

开关置 true，重放：

```text
req1: http=200 time=0.169s
req2: http=200 time=0.021s
req3: http=429 time=0.046s   {"code":429,"message":"请求过于频繁，请稍后再试","data":null}
req4: http=429 time=0.011s   {"code":429,"message":"请求过于频繁，请稍后再试","data":null}
```

日志（**实证实际生效的是 WebFlux 回调**）：

```text
[L45-Sentinel][webflux] 请求被限流: resource=ParamFlowException, path=/sentinel-lab/echo/hi
```

> 细节：网关规则底层复用 **ParamFlow 检查器**（`GatewayFlowSlot` → `ParamFlowChecker.passCheck`），所以 route 级限流被拦时异常类型是 `ParamFlowException`（不是 `FlowException`）——`resource=ParamFlowException` 就是它。

### 4.5 接入即踩：Sentinel starter 把 SCG 的 CircuitBreaker 过滤器"挤没了"

只加依赖、不排除时，网关启动失败：

```text
java.lang.IllegalArgumentException: Unable to find GatewayFilterFactory with name CircuitBreaker
```

`--debug` 条件报告：

```text
GatewayResilience4JCircuitBreakerAutoConfiguration#springCloudCircuitBreakerResilience4JFilterFactory:
  Did not match:
    - @ConditionalOnBean (types: ...ReactiveResilience4JCircuitBreakerFactory...) did not find any beans
```

根因（两边自动配置都用 `@ConditionalOnMissingBean` 抢注，谁先谁赢）：

| 自动配置（Sentinel starter 引入） | 工厂 Bean 条件（字节码核实） |
|---|---|
| `com.alibaba.cloud...ReactiveSentinelCircuitBreakerAutoConfiguration` | `@Bean @ConditionalOnMissingBean(ReactiveCircuitBreakerFactory.class)` |
| `org.springframework.cloud...ReactiveResilience4JAutoConfiguration` | `@Bean @ConditionalOnMissingBean(ReactiveCircuitBreakerFactory.class)` |

`com.alibaba.cloud.*` 的自动配置排在 `org.springframework.cloud.*` 之前，**Sentinel 版先注册** → resilience4j 版退让 → 网关的 `GatewayResilience4JCircuitBreakerAutoConfiguration`（`@ConditionalOnBean(ReactiveResilience4JCircuitBreakerFactory.class)`）不装配 → yml 里的 `CircuitBreaker` 过滤器无工厂可用。

**修复**：exclusion 排除 `com.alibaba.cloud:spring-cloud-circuitbreaker-sentinel`（本项目熔断走 L37 的 resilience4j；若要用 Sentinel 熔断则反向排除，但不能两边都要）。

> 备注：SCA 另有官方集成模块 `spring-cloud-alibaba-sentinel-gateway`（自动注册 `SentinelGatewayFilter` + 异常处理器 + `spring.cloud.sentinel.scg.fallback.*` 配置，支持自定义 `BlockRequestHandler` bean）。本项目与 xhs 生产都选择 **raw 适配器 + 手动接线**（可控制 filter order，与其它 GlobalFilter 的先后关系明确）。

---

## 5. 实验 B：参数级限流（Sentinel 独有维度）

规则（`X-User-Id` header 每用户 1 次/10s）：

```java
rules.add(new GatewayFlowRule("sentinel-lab")
        .setCount(1)
        .setIntervalSec(10)
        .setParamItem(new GatewayParamFlowItem()
                .setParseStrategy(SentinelGatewayConstants.PARSE_STRATEGY_HEADER)
                .setFieldName("X-User-Id")));
```

实测（同路由、同路径，只换 header 值）：

```text
X-User-Id: user-a → 200   （第 1 次）
X-User-Id: user-a → 429   （同用户超额度）
X-User-Id: user-b → 200   （★ 换用户，独立额度）
X-User-Id: user-b → 429
```

对照 L36：**"按用户分桶限流" Redis 方案同样做得到**（`KeyResolver` 返回 `X-User-Id`，每个用户一个桶、rate=1/s）——两者差别只在细节：① Sentinel 的解析器内置（header/query/cookie/IP），SCG 要写 KeyResolver；② Sentinel 支持 `pattern` 过滤（只对匹配的值限流、不匹配放行）；③ Redis 每用户一个桶（高基数有存储/往返成本）、Sentinel 本地统计。**所以"按用户限流"本身不构成选 Sentinel 的理由**——选它是为"分组/系统级"能力（§2/§3）。

---

## 6. 实验 C：分层限流（网关层 vs 服务层）

provider 接入（pom + yml + 配置类）：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

```yaml
spring:
  cloud:
    sentinel:
      eager: true
      transport:
        port: 8720        # 与网关的 8719 错开（同机双应用）
```

```java
@Configuration
public class SentinelProviderConfig {
    private static final Logger log = LoggerFactory.getLogger(SentinelProviderConfig.class);

    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();
        // 资源名 = MVC 的 BEST_MATCHING_PATTERN（/echo/{msg}），不是原始 URI（/echo/hi）！
        rules.add(new FlowRule("/echo/{msg}")
                .setCount(1)
                .setGrade(RuleConstant.FLOW_GRADE_QPS));
        FlowRuleManager.loadRules(rules);
        log.info("[L45-Provider] 服务层限流规则已加载: {}", rules);
    }
}
```

实测（`/echo/{msg}` QPS=1）：

| # | 请求 | 结果 | 说明 |
|---|---|---|---|
| C1 | 直连 provider 2 连发 | 200 → 429 `Blocked by Sentinel (flow limiting)`（纯文本、无 content-type） | 服务层默认 Handler（SCA `DefaultBlockExceptionHandler`） |
| C2 | 经网关 `/provider/**`（网关无规则）2 连发 | 200 → **同样的 provider 429** | 请求**已到达** provider 才被拦 |
| C3 | 经网关 `/sentinel-lab/**`（网关参数规则）2 连发 | 200 → **429 统一 JSON**；provider 到达数 +1（第 2 发没到） | 网关层拦截，请求**没到** provider |

**结论**：网关层挡在入口（保护所有下游、省掉下游解析成本）；服务层挡在 Controller 前（保护单服务资源，参数/业务维度更精细）。两层资源名不同——网关侧是 **route id**，服务侧是 **MVC pattern**（`/echo/{msg}`），配错资源名规则不生效是常见坑。

---

## 7. 实验 D：Nacos 规则数据源（持久化 + 热更新，复刻 xhs 生产）

**yml**（两个数据源：gw-flow + degrade）：

```yaml
spring:
  cloud:
    sentinel:
      eager: true
      datasource:
        flow:
          nacos:
            server-addr: localhost:8848
            group-id: DEFAULT_GROUP
            data-id: demo-gateway-sentinel-flow.json
            data-type: json
            rule-type: gw-flow
        degrade:
          nacos:
            server-addr: localhost:8848
            group-id: DEFAULT_GROUP
            data-id: demo-gateway-sentinel-degrade.json
            data-type: json
            rule-type: degrade
```

**gw-flow 的 converter 要手动注册**（SCA 已知问题：标准类型 `flow/degrade/system/authority/param-flow` 的 converter 由 `SentinelAutoConfiguration` 自动注册，**gw-flow 没有**；不加则数据源创建失败）：

```java
@Bean("sentinel-json-gw-flow-converter")
public Converter<String, GatewayFlowRule> sentinelGwFlowConverter(ObjectMapper objectMapper) {
    return new JsonConverter<>(objectMapper, GatewayFlowRule.class);
}
```

**发布规则**（Nacos OpenAPI）：

```bash
curl -X POST "http://localhost:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=demo-gateway-sentinel-flow.json" \
  --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode 'content=[{"resource":"sentinel-lab","resourceMode":0,"grade":1,"count":2.0,"intervalSec":1,"controlBehavior":0,"burst":0}]' \
  --data-urlencode "type=json"
```

**启动装载**（网关日志）：

```text
[L45-Sentinel] Nacos 规则数据源已配置(localhost:8848)，跳过本地规则，等待异步推送
[notify-ok] dataId=demo-gateway-sentinel-flow.json, group=DEFAULT_GROUP ... listener=NacosDataSource$1
```

**实测**：

```text
D1（count=2/s）：3 连发 /sentinel-lab/hello → 200 / 200 / 429（统一 JSON）
D2（热更新 count=2→1，不重启）：Nacos [data-received]→[notify-ok]；
    2 连发 → 200 / 429（行为立即变化）
```

**机制**：SCA `RuleType.GW_FLOW("gw-flow", ...)` → `GatewayRuleManager.register2Property(dataSource.getProperty())`——Nacos 推送 → converter 反序列化为 `Set<GatewayFlowRule>` → 注册进 `GatewayRuleManager`。

**两个坑**：

1. **本地 `loadRules` 会覆盖 Nacos 推送**：若在 `@PostConstruct` 加载本地规则，Nacos 异步推送到达后可能被覆盖（或反之）——用 `ApplicationReadyEvent` + "配置了 Nacos 数据源就跳过本地规则"（xhs 生产还有 30s 真空期兜底）；
2. **观测盲点**：`/actuator/sentinel` 能看到数据源配置（dataId/ruleType），但它的 `rules` 视图**不包含 gw-flow 规则**——要可视化得上 Dashboard。

---

## 8. 实验 E：熔断（Sentinel DegradeRule 在网关）

degrade 数据源 + 规则 JSON（RT > 300ms 熔断，窗口 10s，最少 2 样本）：

```json
[{"resource":"sentinel-lab","grade":0,"count":300,"timeWindow":10,
  "minRequestAmount":2,"statIntervalMs":1000,"slowRatioThreshold":1.0}]
```

实测（期间把 flow 规则置空以隔离）：

```text
E1：4 并发 /sentinel-lab/slow?ms=600 → 全部 200（每个 ~0.79s，样本进入 RT 统计）
E2：立刻探测 /sentinel-lab/hello   → 429 @0.042s / 429 @0.013s（瞬时拒绝，未打下游）
    日志：resource=DegradeException
E3：等 10s 窗口后                  → 200 @0.022s（半开恢复通行）
```

**与 L37（SCG 原生 CircuitBreaker）对照**：

- SCG 的熔断要显式配 `statusCodes` 才把 5xx 计为失败（L37 坑）；Sentinel 用 RT/异常比例/异常数三档；
- Sentinel 的 `DegradeException` 与 `ParamFlowException` 都走同一个 BlockHandler——本项目统一回 429 JSON；生产可按异常类型分流（限流 429 / 熔断 503 / 降级兜底 body）；
- 两套熔断**不能同时接**（规则不互通、统计各算各的），要按 §3 决策二选一。

---

## 9. 坑与边界

| # | 坑 | 说明 |
|---|---|---|
| 1 | **默认 BlockHandler 与 Spring 6 不兼容（T-106）** | 1.8.8 编译于 Spring 5.x：`ServerResponse.status(HttpStatus)`/`syncBody` 在 WebFlux 6.1.6 已移除 → 限流触发即 `NoSuchMethodError`、请求悬挂。**必须自定义** |
| 2 | **双适配器 / 双 CallbackManager** | 网关=WebFlux 应用，starter 的 URL 级适配器 + raw gateway 的 route 级适配器同时在跑；实际生效的是 WebFlux 异常处理器 → **两个回调都要注册**（xhs 同款） |
| 3 | **Sentinel starter ↔ resilience4j 工厂冲突** | 两者 `ReactiveCircuitBreakerFactory` 均 `@ConditionalOnMissingBean`，Sentinel 自动配置先注册 → SCG `CircuitBreaker` 过滤器消失、启动失败。按需 exclusion（本项目排 Sentinel 的） |
| 4 | **gw-flow converter 不自动注册** | `SentinelDataSourceHandler` 按名找 `sentinel-json-gw-flow-converter`；标准类型自动注册、gw-flow 没有 → 手动提供同名 bean（xhs 生产同款） |
| 5 | **资源名语义** | 网关侧 = **route id**；服务侧 = **MVC `BEST_MATCHING_PATTERN`**（`/echo/{msg}`）。配错资源名 = 规则不生效 |
| 6 | **本地 loadRules vs Nacos 异步推送** | 别在 `@PostConstruct` 抢加载：`ApplicationReadyEvent` + "有 Nacos 数据源就跳过本地"（xhs 另有 30s 真空期兜底） |
| 7 | **单机 vs 集群** | Sentinel 默认**单机**统计（每实例独立额度）；要集群限流需 Token Server。SCG `RedisRateLimiter` 天然集群共享 |
| 8 | **观测盲点** | `/actuator/sentinel` 看不到 gw-flow 规则；没 Dashboard 时只能靠日志/行为验证 |
| 9 | **服务层默认响应没有 content-type** | SCA WebMVC 默认 `DefaultBlockExceptionHandler` 返回 429 纯文本 `Blocked by Sentinel (flow limiting)`；要统一错误体需自定义 `BlockExceptionHandler` bean |
| 10 | **（顺带修复的旧坑）** | demo-gateway 的 `register-enabled: false` 原来误缩进在 `spring.cloud.nacos.config` 下（无效），网关实际仍在注册自己 → Nacos gRPC 抖动时启动失败；已移到 `spring.cloud.nacos.discovery` 下 |

---

## 10. Self-Test

1. SCG 自带与 Sentinel 的功能重叠区是哪些？各自独有的是什么？
2. 什么情况下选 Sentinel 而不是 SCG 自带限流？（给出决策树）
3. 网关 route 级限流与服务层限流的"资源名"分别是什么？为什么容易配错？
4. T-106 是什么？为什么默认 BlockHandler 会在 Spring 6.1.6 上悬挂？怎么修？
5. 为什么自定义 BlockHandler 要同时注册 Gateway 和 WebFlux 两个回调？实际生效的是哪个？
6. 引入 Sentinel starter 后 SCG 的 `CircuitBreaker` 过滤器为什么消失？怎么修？

---

## 11. 相关与衔接

- **L36**（RequestRateLimiter：Redis 令牌桶、集群共享）——限流对照的 SCG 侧
- **L37**（CircuitBreaker/Resilience4j：fallbackUri、statusCodes 坑）——熔断对照的 SCG 侧
- **L44**（Nacos 配置中心动态路由）——本文实验 D 的规则数据源同理：配置中心不只是路由，也能做规则中心
- **L46**（Gateway × SCLB 深入）/ **L47**（Gateway × Redis：RedisRateLimiter 与 `RedisRouteDefinitionRepository`）
- **sentinel-lab**（Sentinel 源码深挖 D1~D13：LeapArray/Slot 链/流控/熔断/数据源）——本文只用到网关适配器层，机制细节看那条线
- 生产参考：`xhs-project/my-xhs-gateway` 的 `RateLimitFilter` + `GatewayConfig` + `application.yml`
