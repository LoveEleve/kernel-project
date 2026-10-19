# Lesson 37: Circuit Breaker & Retry — `CircuitBreaker`（Resilience4j）/ `Retry`

> 课程索引：`../gateway-学习清单.md` → **Stage 7**（内置能力补全）· Lesson 37
> 前置：**L06**（超时与路由 metadata——本课的"超时"基础）· **L36**（限流：治理家族 + 事件通道 + 空 key 双开关的"两个 Config"模式）· **L26**（洋葱模型：过滤器顺序）
> 口径：🔴 ① **断路器三态（closed / open / half-open）与状态转移** ② **重试必须幂等**（非幂等接口重试 = 重复下单）③ **治理四件套怎么配合**（限流 → 重试 → 熔断 → 超时，从最外到最内；超时值怎么定见 37.6）
> 架构问题：熔断放网关还是放服务？（第 37.7 节）
> 版本口径：demo 跑的是 **SCG 4.1.5 / spring-cloud-circuitbreaker 3.1.2 / resilience4j 2.1.0**；本课源码与实测均以此为基准。
> 概念深挖：[`circuit-breaker-concepts.md`](circuit-breaker-concepts.md)（状态机与统计算法 / 阈值与自适应 / 重试进阶（预算·抖动·对冲）/ 状态放哪 / 业界对照 / 分层）；**附录 A = Resilience4j 最小上手（零基础可先看）**。

**一句话（先说人话）**：下游偶发失败时，网关给"幂等安全的请求"几次重试机会；下游持续失败时，网关把该下游的调用断开（熔断）——后续请求**根本不去碰下游**，直接快速失败或走兜底响应，过一会儿再放少量"探测请求"通过（半开），确认恢复后重新放行。业务代码一行不用改。

**本课路线图**（建议按序读；想先补 R4j 基础，看专题附录 A）：

1. **37.1 先看现象**——4 条 lab 路由跑出 5 组实测（默认 1s 超时 / 三态全流程 / GET-POST 重试不对称 / 重试放大超时 / 5xx 默认不算失败）；
2. **37.2 三态骨架**——断路器在什么状态间切换；
3. **37.3 / 37.4 源码**——`CircuitBreaker` 过滤器（含隐藏的 TimeLimiter）、`Retry` 过滤器；
4. **37.5 / 37.6 生产约束**——重试必须幂等；治理四件套（限流 → 重试 → 熔断 → 超时）的配合；
5. **37.7–37.9**——网关 vs 服务、坑表、面试问答。

> 与专题的分工：本课 = "网关里怎么用 + 源码怎么走 + 坑在哪"；[`circuit-breaker-concepts.md`](circuit-breaker-concepts.md) = "更深的原理（窗口算法 / 阈值 / 重试预算 / 业界对照）"。两篇可交叉读，内容不重复。

---

## 37.1 它干了什么：四条实验路由 + 五组实测

这一节先不解释原理——**先让 4 条路由跑出 5 种现象，把"熔断/重试长什么样"看进眼里**（① 默认 1s 超时、② 三态全流程、③ GET/POST 重试不对称、④ 重试放大超时、⑤ 5xx 默认不算失败），后面几节再逐个拆开。

实验前置（都在 demo 里已落盘）：

- `demo-gateway/pom.xml`：加 `spring-cloud-starter-circuitbreaker-reactor-resilience4j`（不加这个依赖，yml 里的 `CircuitBreaker` 过滤器会报 "Unable to find GatewayFilterFactory"）；
- `demo-gateway/.../filter/FallbackController.java`：`/fallback` 兜底端点（从 exchange 属性里取原始异常）；
- `demo-provider/.../FailureController.java`：故障注入端点 `/status/{code}`（GET/POST）与 `/flaky`（同一 key 前 N 次 500、之后 200）；
- `application.yml`：4 条 lab 路由 + resilience4j 实例参数 + actuator 暴露。

四条路由（yml 原文）。先扫一眼差异，每条对应后面一组实验：

- `cb-lab`：配了 `fallback` + `statusCodes` → 实验 B（三态全流程）
- `cb-nofallback`：裸配 → 实验 A（默认 1s 超时）、实验 E（statusCodes 坑）
- `retry-lab`：只重试 GET → 实验 C（重试不对称）
- `retry-timeout-lab`：带 500ms 路由超时 → 实验 D（重试放大）

```yaml
        # A) 带 fallback 的熔断：statusCodes 把 500 也算失败（不配的话 5xx 不算失败！）
        #    打法：/cb-lab/status/500 连打 4 发 → 熔断打开 → /cb-lab/hello 直接走 fallback（不碰 provider）
        - id: cb-lab
          uri: http://localhost:8081
          predicates:
            - Path=/cb-lab/**
          filters:
            - StripPrefix=1
            - name: CircuitBreaker
              args:
                name: cb-lab
                fallbackUri: forward:/fallback
                statusCodes:
                  - 500
        # B) 不带 fallback：看超时/短路时网关直接回什么码（默认 TimeLimiter=1s；本 lab 已把本路由调成 3s，见下方 timelimiter 段；熔断打开后 → 503）
        - id: cb-nofallback
          uri: http://localhost:8081
          predicates:
            - Path=/cb-nofallback/**
          filters:
            - StripPrefix=1
            - name: CircuitBreaker
              args:
                name: cb-nofallback
        # C) 重试：默认只重试 GET + 5xx 系列（POST 不重试——幂等保护）
        - id: retry-lab
          uri: http://localhost:8081
          predicates:
            - Path=/retry-lab/**
          filters:
            - StripPrefix=1
            - name: Retry
              args:
                retries: 3
                series: SERVER_ERROR
                methods: GET
        # D) 重试 × 超时：路由级 response-timeout=500ms + Retry（默认异常含网关 TimeoutException）
        - id: retry-timeout-lab
          uri: http://localhost:8081
          predicates:
            - Path=/retry-timeout-lab/**
          filters:
            - StripPrefix=1
            - name: Retry
              args:
                retries: 3
                methods: GET
          metadata:
            response-timeout: 500
```

熔断器实例参数（yml 原文；不配的话是 resilience4j 默认值"滑动窗口 100 次、失败率 50%、打开后等 60s"，实验里根本看不到状态变化）：

```yaml
resilience4j:
  circuitbreaker:
    instances:
      cb-lab:
        sliding-window-size: 4
        minimum-number-of-calls: 4
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
        permitted-number-of-calls-in-half-open-state: 1
        automatic-transition-from-open-to-half-open-enabled: true
      cb-nofallback:
        sliding-window-size: 4
        minimum-number-of-calls: 4
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
        permitted-number-of-calls-in-half-open-state: 1
        automatic-transition-from-open-to-half-open-enabled: true
  # 默认 TimeLimiter 超时只有 1s（请求慢于 1s 直接 504，见实验 A）；
  # 这里把 cb-nofallback 单独调成 3s；想验证"默认 1s"，注释掉这段再打 /cb-nofallback/slow?ms=2000
  timelimiter:
    instances:
      cb-nofallback:
        timeout-duration: 3s
```

观测端点：`management.endpoints.web.exposure.include` 里加了 `circuitbreakers,circuitbreakerevents`
（Resilience4j Spring Boot 3 自带，随 starter 传递进来）。

### 实验 A：熔断器自带一个"1 秒超时"（TimeLimiter）

```bash
curl -s -o /dev/null -w "code=%{http_code} time=%{time_total}s\n" \
  "http://127.0.0.1:8080/cb-nofallback/slow?ms=3000"
# 实测：code=504 time=1.100246s   ← ★ 下游要睡 3s，网关 1.1s 就回了 504
```

下游 `/slow?ms=3000` 要 3 秒，但请求 1.1 秒就被切成 **504 Gateway Timeout**。
（这发 504@1.1s 是在调 TimeLimiter 之前打的；照最终 yml 复现时，先把 `timelimiter.instances.cb-nofallback` 段注释掉。）
原因：`CircuitBreaker` 过滤器背后有一个 **TimeLimiter，默认超时 1 秒**（源码见 37.3），它比路由的全局
`response-timeout: 10s` 先到。这大概是本课最容易踩的坑：**配了熔断，慢接口会莫名其妙 504**。

把 `cb-nofallback` 的 TimeLimiter 按实例调成 3s（上面 yml 已落盘）后：

```bash
curl -s -o /dev/null -w "ms=2000 -> code=%{http_code} time=%{time_total}s\n" ".../cb-nofallback/slow?ms=2000"
curl -s -o /dev/null -w "ms=4000 -> code=%{http_code} time=%{time_total}s\n" ".../cb-nofallback/slow?ms=4000"
# 实测：ms=2000 -> code=200 time=2.275864s   ← 2s 的慢请求不再被切
#       ms=4000 -> code=504 time=3.067009s   ← 4s 的请求在 3s 被切
```

### 实验 B：三态全流程（closed → open → half-open → closed）

用 `statusCodes: [500]` 把"下游返回 500"也算作失败（不配的话 5xx 根本不算失败，见实验 E）。
先连打 4 发 500（窗口=4、最少 4 次才评估、失败率 50%）：

```bash
for i in 1 2 3 4; do curl -s "http://127.0.0.1:8080/cb-lab/status/500"; echo; done
# 每发都返回 fallback（HTTP 200）：
# {"fallback":true,"path":"/fallback","cause":"...CircuitBreakerStatusCodeException: 500 INTERNAL_SERVER_ERROR"}
```

4 发之后熔断器状态（`/actuator/circuitbreakers`）：

```
state=OPEN  bufferedCalls=4  failedCalls=4  failureRate=100.0%
```

**OPEN 窗口内**再请求（同一秒）：

```bash
curl -s "http://127.0.0.1:8080/cb-lab/echo/while-open2"
# {"fallback":true,...,"cause":"io.github.resilience4j.circuitbreaker.CallNotPermittedException:
#   CircuitBreaker 'cb-lab' is OPEN and does not permit further calls"}
# provider 日志中 while-open2 出现 0 次 ★ 请求根本没打到下游
```

**等 6 秒**（> `wait-duration-in-open-state: 5s`）再打一发（这会成为半开窗口里唯一被放行的探测请求）：

```bash
sleep 6
curl -s -o /dev/null -w "probe2 code=%{http_code}\n" "http://127.0.0.1:8080/cb-lab/echo/probe2"
# probe2 code=200（真实响应）；provider 收到 probe2: 1 次；state=CLOSED ★ 恢复正常
```

状态转移事件（`/actuator/circuitbreakerevents?name=cb-lab`，字段 `type` + `stateTransition`）：

```
STATE_TRANSITION CLOSED_TO_OPEN
STATE_TRANSITION OPEN_TO_HALF_OPEN
STATE_TRANSITION HALF_OPEN_TO_CLOSED
```

### 实验 C：重试——第 3 次成功 + GET/POST 的不对称

provider `/flaky?fail=2`：同一 key 前 2 次返回 500，第 3 次开始 200。先重置计数，再让网关打一次：

```bash
curl -s "http://127.0.0.1:8080/retry-lab/flaky/reset?key=l37b"          # {"reset":"l37b"}
curl -s "http://127.0.0.1:8080/retry-lab/flaky?fail=2&key=l37b"
# {"port":8081,...,"attempt":3,"ok":true}    ★ 客户端拿到的是第 3 次尝试的成功响应
```

provider 侧日志（三次尝试，重试真的发生了）：

```
[provider] /flaky key=l37b fail=2 attempt=1 -> 500
[provider] /flaky key=l37b fail=2 attempt=2 -> 500
[provider] /flaky key=l37b fail=2 attempt=3 -> 200
```

同一个 500，GET 和 POST 的待遇不同（Retry 默认 `methods=[GET]`）：

```bash
一次 GET  /retry-lab/status/500 → provider 命中 4 次（1 次初始 + 3 次重试）
一次 POST /retry-lab/status/500 → provider 命中 1 次（不在 methods 里 → 不重试）
```

### 实验 D：重试 × 超时——超时被放大 4 倍

路由 `retry-timeout-lab`：`response-timeout=500ms`（路由级）+ `Retry(retries=3)`。
下游 `/slow?ms=3000` 每次尝试都超时，默认重试异常里含网关的 `TimeoutException`：

```bash
curl -s -o /dev/null -w "code=%{http_code} time=%{time_total}s\n" \
  "http://127.0.0.1:8080/retry-timeout-lab/slow?ms=3000"
# 实测：code=504 time=2.024172s
```

**单次超时 0.5s，客户端却等了 2.02s ≈ 4 × 0.5s**——1 次初始 + 3 次重试，每次都把超时走满。
这就是"重试放大故障"的现场：**重试的代价是对下游的额外流量、对用户的额外等待**。

### 实验 E（坑）：不配 `statusCodes`，5xx 根本不算失败

`cb-nofallback` 没配 `statusCodes`，连打 4 发 500：

```bash
for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code} " ".../cb-nofallback/status/500"; done
# 500 500 500 500
state=CLOSED buffered=4 failed=0   ← ★ 4 次 500 一次都没被记为失败，熔断器状态没有任何变化
```

原因见 37.3：下游返回 500 时 `chain.filter()` 是**正常完成**的（响应只是"状态码是 500"），
只有"抛异常"才会被 Resilience4j 记为失败——所以要靠 `statusCodes` 配置把指定状态码转成异常。

---

## 37.2 概念：断路器三态

```
                失败率 ≥ 阈值（窗口内调用数 ≥ 最少样本数）
   ┌─────────┐ ──────────────────────────────────────► ┌────────┐
   │ CLOSED  │                                         │  OPEN  │
   │（放行） │ ◄────────────────────────────────────── │（拒绝）│
   └─────────┘        半开探测成功（达到要求次数）        └────────┘
        ▲                                                    │
        │                                             等 waitDuration
        │                                                    ▼
        │              ┌───────────────┐
        └───────────── │   HALF_OPEN   │  只放行"许可数"内的探测请求
          探测成功      │   （试探）     │  探测失败 → 直接回 OPEN
                       └───────────────┘
```

| 状态 | 请求怎么处理 | 什么时候转移 |
|---|---|---|
| **CLOSED** | 全部放行，统计成功/失败 | 窗口内样本 ≥ `minimum-number-of-calls` 且失败率 ≥ `failure-rate-threshold` → **OPEN** |
| **OPEN** | 全部拒绝（快速失败，不打下游） | 等满 `wait-duration-in-open-state` → **HALF_OPEN** |
| **HALF_OPEN** | 只放行 `permitted-number-of-calls-in-half-open-state` 个探测请求 | 探测全部成功 → **CLOSED**；有失败 → 回 **OPEN** |

实验 B 的参数（窗口 4 / 最少 4 / 阈值 50% / 打开 5s / 半开 1 个 / 自动转半开）就是为了把三态压缩到几秒内看完；
生产参数见 37.6。

判定全是 Resilience4j 的标准字段（`resilience4j.circuitbreaker.instances.<name>.*`）：
`sliding-window-type`（COUNT_BASED / TIME_BASED）、`sliding-window-size`、`minimum-number-of-calls`、
`failure-rate-threshold`、`slow-call-duration-threshold`/`slow-call-rate-threshold`（把"慢"也当失败）、
`wait-duration-in-open-state`、`permitted-number-of-calls-in-half-open-state`、
`automatic-transition-from-open-to-half-open-enabled`、`record-exceptions`/`ignore-exceptions`。

---

## 37.3 怎么做（上）：CircuitBreaker 过滤器源码

### ① 过滤器本体：`SpringCloudCircuitBreakerFilterFactory`（4.1.5）

```java
public GatewayFilter apply(Config config) {
    ReactiveCircuitBreaker cb = reactiveCircuitBreakerFactory.create(config.getId());
    Set<HttpStatus> statuses = config.getStatusCodes().stream().map(HttpStatusHolder::parse)
            .filter(statusHolder -> statusHolder.getHttpStatus() != null).map(HttpStatusHolder::getHttpStatus)
            .collect(Collectors.toSet());

    return new GatewayFilter() {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return cb.run(chain.filter(exchange).doOnSuccess(v -> {
                if (statuses.contains(exchange.getResponse().getStatusCode())) {   // ★ 把配置的 5xx 转成异常
                    HttpStatusCode status = exchange.getResponse().getStatusCode();
                    throw new CircuitBreakerStatusCodeException(status);
                }
            }), t -> {
                if (config.getFallbackUri() == null) {
                    return Mono.error(t);                                          // 没配 fallback：继续抛
                }
                exchange.getResponse().setStatusCode(null);
                ... // 把请求 URI 改写成 fallback 路径，reset(exchange)，交给网关自己的 DispatcherHandler
                return handle(getDispatcherHandler(), exchange.mutate().request(request).build());
            }).onErrorResume(t -> handleErrorWithoutFallback(t, config.isResumeWithoutError()));
        }
    };
}

public static class Config implements HasRouteId {
    private String name;              // 熔断器名（= Resilience4j 实例名）；不写默认用 routeId
    private URI fallbackUri;          // 兜底地址；demo 用 forward:/fallback
    private Set<String> statusCodes = new HashSet<>();   // 哪些响应码算失败（默认空！）
    private boolean resumeWithoutError = false;          // 出错也不报、直接空响应（慎用）
    private String routeId;
    public String getId() {
        if (!StringUtils.hasText(name) && StringUtils.hasText(routeId)) { return routeId; }
        return name;
    }
}
```

四个要点：

- **`statusCodes` 默认空**——这是实验 E 的根源。要按 HTTP 码熔断，必须显式配（`500`、`503`…）；
- **`name` 默认取 routeId**——也就意味着：**同名 = 同状态**。多条路由配同一个 `name`，它们共用一个熔断器；
- **fallback 的转发**：`fallbackUri: forward:/fallback`，由网关自己的 `DispatcherHandler` 处理
  （所以 `/fallback` 可以是网关里的一个 `@RestController`）；原始异常放在
  `ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR`，`FallbackController` 就是从这里取 `cause` 的；
- **`resumeWithoutError: true`** 时连 fallback 都不要，直接返回空响应（相当于吞掉错误）。

### ② 错误映射：`SpringCloudCircuitBreakerResilience4JFilterFactory`（4.1.5 全文核心）

```java
@Override
protected Mono<Void> handleErrorWithoutFallback(Throwable t, boolean resumeWithoutError) {
    if (java.util.concurrent.TimeoutException.class.isInstance(t)) {
        return Mono.error(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, t.getMessage(), t));   // 504
    }
    if (CallNotPermittedException.class.isInstance(t)) {
        return Mono.error(new ServiceUnavailableException());                                            // 503
    }
    if (resumeWithoutError) { return Mono.empty(); }
    return Mono.error(t);
}
```

没配 fallback 时的对外表现（源码 + 实测）：

- **TimeLimiter 超时 → 504**：抛 `ResponseStatusException(GATEWAY_TIMEOUT)`（实验 A 实测 1.1s / 3.1s）；
- **熔断打开短路 → 503**：抛出的 `ServiceUnavailableException` 带
  `@ResponseStatus(SERVICE_UNAVAILABLE, reason = "Upstream service is temporarily unavailable")`，
  经 WebFlux 的响应状态处理映射为 503 —— 实测：cb-nofallback 打开后 `/cb-nofallback/hello` → `HTTP/1.1 503 Service Unavailable`；
- 其他异常原样抛给全局错误处理。

### ③ 底层：`ReactiveResilience4JCircuitBreaker.run()`（circuitbreaker 3.1.2）

```java
@Override
public <T> Mono<T> run(Mono<T> toRun, Function<Throwable, Mono<T>> fallback) {
    Tuple2<CircuitBreaker, Optional<TimeLimiter>> tuple = buildCircuitBreakerAndTimeLimiter();
    Mono<T> toReturn = toRun.transform(CircuitBreakerOperator.of(tuple.getT1()));   // ★ 用 Resilience4j 算子记账
    if (tuple.getT2().isPresent()) {                                                // ★ TimeLimiter 在这
        final Duration timeoutDuration = tuple.getT2().get().getTimeLimiterConfig().getTimeoutDuration();
        toReturn = toReturn.timeout(timeoutDuration)
                .doOnError(TimeoutException.class,
                        t -> tuple.getT1().onError(timeoutDuration.toMillis(), TimeUnit.MILLISECONDS, t));
    }
    if (fallback != null) { toReturn = toReturn.onErrorResume(fallback); }
    return toReturn;
}
```

- 记账靠 **`CircuitBreakerOperator`**：它把"错误信号"记为失败、"正常完成"记为成功——**HTTP 状态码它看不见**（实验 E）；
- 超时不是用 `TimeLimiterOperator`，而是 **Reactor 的 `.timeout()` + 手动 `onError` 记账**，
  超时时长来自 TimeLimiter 配置；**默认 1 秒**（resilience4j `TimeLimiterConfig.ofDefaults()`，实验 A 实测 1.1s）；
- `buildCircuitBreakerAndTimeLimiter()` 每次调用都执行，但 `circuitBreakerRegistry.circuitBreaker(id, ...)`
  **按名缓存实例**——所以刷新路由也不会重置熔断状态。

### ④ 装配与配置从哪来

```java
// GatewayResilience4JCircuitBreakerAutoConfiguration：有 ReactiveResilience4JCircuitBreakerFactory 才建过滤器工厂
@Bean
@ConditionalOnBean(ReactiveResilience4JCircuitBreakerFactory.class)
@ConditionalOnEnabledFilter
public SpringCloudCircuitBreakerResilience4JFilterFactory springCloudCircuitBreakerResilience4JFilterFactory(...)
```

```java
// ReactiveResilience4JAutoConfiguration（circuitbreaker 3.1.2）：工厂 + 配置属性
@Bean
@ConditionalOnMissingBean(ReactiveCircuitBreakerFactory.class)
public ReactiveResilience4JCircuitBreakerFactory reactiveResilience4JCircuitBreakerFactory(
        CircuitBreakerRegistry circuitBreakerRegistry, TimeLimiterRegistry timeLimiterRegistry,
        Resilience4JConfigurationProperties resilience4JConfigurationProperties) { ... }
```

于是配置有三层命名空间：

| 配置 | 作用 |
|---|---|
| `resilience4j.circuitbreaker.instances.<name>.*` | 熔断器参数（窗口/阈值/等待/半开…）——标准 Resilience4j 配置，**按 `name` 生效** |
| `resilience4j.timelimiter.instances.<name>.timeout-duration` | 每个熔断器的超时（不配默认 **1s**） |
| `spring.cloud.circuitbreaker.resilience4j.disable-time-limiter=true` | 全局关掉 TimeLimiter（超时完全交给路由 `response-timeout`） |
| `spring.cloud.circuitbreaker.resilience4j.disable-thread-pool` 等 | 非响应式场景的线程池开关；**响应式链路不用线程池**（超时是 Reactor `.timeout()`，还在事件循环上） |

---

## 37.4 怎么做（下）：Retry 过滤器源码

`RetryGatewayFilterFactory`（4.1.5）**不需要额外依赖**（Gateway 内置，`GatewayAutoConfiguration` 里有 bean）。
它底层用的是老牌 reactor-extra 的 `reactor.retry.Retry / Repeat`（本地新版源码树也还是它，未迁移）。

### 配置默认值（源码原文，很关键）

```java
public static class RetryConfig implements HasRouteId {
    private int retries = 3;                                                    // 重试次数（不含首次）
    private List<Series> series = toList(Series.SERVER_ERROR);                  // 默认重试 5xx 系列
    private List<HttpStatus> statuses = new ArrayList<>();                      // 默认空（要精确到码就配它）
    private List<HttpMethod> methods = toList(HttpMethod.GET);                  // ★ 默认只重试 GET
    private List<Class<? extends Throwable>> exceptions =
            toList(IOException.class, TimeoutException.class);                  // SCG 的 TimeoutException（网关超时）
    private BackoffConfig backoff;                                              // 默认 null；配了走指数退避
}
```

一句话：**默认值是"保守重试"**——只重试 GET、只重试 5xx（或 IO/超时异常）、退避默认关闭（配了是 5ms 起、×2）。
POST 默认不重试是刻意的幂等保护（见 37.5），不是遗漏。

### 两条重试通道 + 重试之间的"复位"

```java
Repeat<ServerWebExchange> statusCodeRepeat = ...   // 看响应状态码：statuses 命中 或 series 命中，且 method 可重试
Retry<ServerWebExchange> exceptionRetry = ...      // 看异常：exceptions 命中（含 cause），且 method 可重试
...
publisher = ((Mono<Void>) publisher).retryWhen(...);   // 先异常重试
publisher = ((Mono<Void>) publisher).repeatWhen(...);  // 再状态码重试（repeat 必须在外层）
```

重试前会 `reset(exchange)`（断开上一次的下游响应连接、移除已写入的响应头、清掉"已路由"标记），
并用 `RETRY_ITERATION_KEY` 在 exchange 属性里数次数（`超过 retries 就停`）。另外如果路由有 id，会发 `EnableBodyCachingEvent`
让请求体可重复读——这样带 body 的重试（POST/PUT）在技术上可行，但**幂等责任在配置的人**。

### 怎么配（lab 原文）

```yaml
- name: Retry
  args:
    retries: 3
    series: SERVER_ERROR        # 也可写 statuses: [500, 502] 精确到码
    methods: GET                # 只重试 GET；全量重试要 methods: GET,POST,...（慎）
    exceptions: java.io.IOException, org.springframework.cloud.gateway.support.TimeoutException   # 按需覆盖（这一行就是默认值）
    backoff:                    # 可选：指数退避
      firstBackoff: 50ms
      maxBackoff: 500ms
      factor: 2
      basedOnPreviousValue: false
```

注意两个 `TimeoutException` 不是同一个东西：路由 `response-timeout` 抛的是
`org.springframework.cloud.gateway.support.TimeoutException`（在默认重试异常里，实验 D 靠它触发重试）；
熔断器 TimeLimiter 超时抛的是 `java.util.concurrent.TimeoutException`，会被转成 504，**不在**默认重试异常里——
所以"熔断器超时"默认不会被 Retry 重试。

---

## 37.5 重试必须幂等（本课 🔴②）

实验 C 里 GET 被重试 3 次无感，因为它**幂等**：查询执行 4 次和执行 1 次，结果一样。
把同样的逻辑放到"创建"接口呢？

```
用户点"提交订单" → 网关转发给订单服务 → 订单服务处理成功、写库成功、响应在回程路上丢了
                → 网关认为失败 → 重试一次 → 订单服务又创建了一单 → 用户被扣两次钱
```

**失败的边界不等于业务的边界**。所以生产的规则是：

- **默认只自动重试最安全的 GET**（`methods=[GET]`；PUT/DELETE 虽然也幂等，但默认同样不放开——要放开得显式配）；
- 要放开 PUT/DELETE（幂等但非安全）或 POST，**必须先确认业务幂等**：
  要么接口天然幂等（PUT 全量覆盖、带唯一幂等键 INSERT），要么服务端有**幂等键/去重表**兜底；
- **只重试"瞬时故障"**：连接失败、超时、502/503/504。
  **4xx 不要重试**（请求本身错了，重试还是错）；默认 `series=SERVER_ERROR` 已经帮你挡掉了；
- **重试次数要小、间隔要退避**：`retries: 3` + 指数退避是常见起点；实验 D 说明重试会把"超时等待"乘 4，
  次数再大一点用户等不起、下游也被多打几倍流量。

---

## 37.6 治理四件套怎么配合：限流 → 重试 → 熔断 → 超时（从最外到最内）

### 顺序：在 SCG 里就是 yml 里 filter 的声明顺序

`RouteDefinitionRouteLocator` 给没有显式 order 的过滤器按声明顺序编号（源码原文）：

```java
GatewayFilter gatewayFilter = factory.apply(configuration);
if (gatewayFilter instanceof Ordered) {
    ordered.add(gatewayFilter);
}
else {
    ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
}
```

（`i` 是过滤器在列表里的下标：第 1 个 → order=1，第 2 个 → order=2……）
再看 L26 的洋葱模型：**order 小的在外层**。所以 yml 列表里靠前的先执行、包住后面的。
推荐组合（按这个顺序写；参数省略，只示意顺序）：

```yaml
filters:
  - StripPrefix=1
  - name: RequestRateLimiter     # 最外：入口配额，一次请求扣一次（重试不额外扣）
  - name: Retry                  # 次外：包住"每一次下游调用"
  - name: CircuitBreaker         # 内层：每次尝试都会被熔断器记账
# 超时：路由 metadata.response-timeout 或熔断器的 TimeLimiter（在更内层）
```

每条的理由：

| 层 | 放这里的原因 | 反例会怎样 |
|---|---|---|
| 限流（最外） | 用户的 1 次请求 = 1 个令牌；被限流直接 429，不做无谓重试 | 放重试里层：重试会多耗令牌、把配额打爆 |
| 重试（次外） | 每次尝试都经过熔断器，**熔断器看到真实的调用量**；熔断打开后剩余重试快速失败（503/fallback），不再打下游 | 熔断在重试外层：熔断器只看到"最终结果"，4 次尝试只记 1 次失败，下游被打 4 倍却迟迟不熔断 |
| 熔断（内层） | 统计每一次真实下游调用；连续失败则打开断路器 | 熔断在外、重试在内：重试风暴绕过熔断统计 |
| 超时（最内） | **每次尝试独立超时**；失败交给重试处理 | 超时在外：多次重试共享一个总超时，第一次就没时间了 |

（顺序不是教条：如果你要"熔断器只按最终结果计数"，把 CB 放外层也成立——但要在团队里写清楚语义。关键是**别默认**。）

### 超时值怎么定（L06 移入 + 本课补充）

- **路由级**：`metadata.response-timeout`（毫秒）——全局给安全值（如 5s），**慢接口单独调大**，长连接/流式特别大；
  实验 D 用的 `response-timeout: 500` 就是路由级；
- **熔断器级**：TimeLimiter 默认 **1s**（实验 A）——它不是"全局超时"，而是**熔断器自己的保护线**；
  两者都在时取**先到**的那个。配了熔断就记得对齐它：要么调
  `resilience4j.timelimiter.instances.<name>.timeout-duration`，要么 `disable-time-limiter=true` 只留路由超时；
- **铁律：网关超时 < 上游客户端（调用方）超时**——调用方的超时必须比网关的长，
  这样调用方拿到的是**网关给的 504 / fallback**，而不是自己先超时留下"未知状态"；
- 联动：**超时 × 重试 = 最坏耗时**（实验 D：0.5s × 4 ≈ 2s），定超时和重试时要一起算总账。

### 参数怎么定

| 参数 | 起点 | 依据 |
|---|---|---|
| 重试次数 | 3（+ 指数退避） | 瞬时故障恢复概率、最坏耗时预算 |
| 熔断窗口/阈值 | 看流量：低流量用 COUNT（如 20 次/50%），高流量用 TIME | 太少样本容易误熔断 |
| 打开时长 | 5s ~ 60s | 给下游恢复时间；太短会反复冲击半死服务 |
| 超时 | 下游 p99 × 1.5 ~ 2 | 压测/监控 P99 |
| 限流阈值 | 下游安全水位 | L36 §36.6③ |

一条经验：**先监控、后阈值**。没有 P99/错误率等数据支撑的熔断参数都是猜测。

---

## 37.7 熔断放网关还是放服务？（架构问题）

- **网关熔断**保护的是**调用链的入口**：按"路由/服务"粒度，防止某个下游把整个入口拖垮——它是粗粒度、面向"可用性"的；
  注意它是**按熔断器名聚合**的：demo 里 `name` = 路由 id，同一条路由下多个实例的失败率合在一起算
  （一个坏实例可能被好实例稀释）；
- **服务内熔断**（Resilience4j/Sentinel 的服务端用法）保护的是**业务依赖**：按"依赖的方法/资源"粒度，能带业务降级语义
  （如"推荐服务失败就返回热门列表"）；
- **两层都要**：网关挡住"整体不可用"快速失败，服务内做"细粒度降级"。网关换不来业务级的兜底数据；
  服务内也替代不了网关对入口的统一保护。和 L36"限流放网关还是服务"是同一个答案。

---

## 37.8 坑表：七种"配错了"的下场

| 配法 | 现象 | 原因 |
|---|---|---|
| 不配 `statusCodes` | 下游一直 500，熔断器也不会打开（实验 E） | 5xx 是"正常完成"，`CircuitBreakerOperator` 只记异常 |
| 配了熔断、没管 TimeLimiter | 慢接口 1s 被切 504（实验 A） | TimeLimiter 默认 1s，早于路由 `response-timeout` |
| `retries` 调大 + 超时 | 用户等待/下游流量成倍放大（实验 D） | 最坏耗时 ≈ 超时 × (retries+1) |
| `methods` 放开到 POST | 重复下单/重复支付 | 默认 `[GET]` 是幂等保护，不是缺陷 |
| 多条路由用同一个 `name` | 状态互相牵连：一条路由的失败把另一条也"熔"了 | 熔断器按名共享（默认 name=routeId） |
| 线上没暴露 actuator | 熔断状态不可观测，开了/没开全靠猜 | `circuitbreakers` / `circuitbreakerevents` 要加进 exposure |
| 忘记加 starter 依赖 | 路由编译报 `Unable to find GatewayFilterFactory with name CircuitBreaker` | 过滤器工厂 bean 来自 `GatewayResilience4JCircuitBreakerAutoConfiguration` |

观测姿势：`/actuator/circuitbreakers`（状态+计数）、`/actuator/circuitbreakerevents`（转移事件/失败原因/duration）。

---

## 37.9 面试问答（一句话答案 + 追问弹药）

1. **"断路器三态怎么转？"**
   一句话：CLOSED 统计失败率 → 超阈 OPEN（全拒）→ 等 waitDuration HALF_OPEN（放少量探测）→ 成功回 CLOSED、失败回 OPEN。
   弹药：判定要求"样本数 ≥ minimum-number-of-calls"（实验 B 参数：窗口 4/最少 4/50%/5s/半开 1）。
2. **"熔断和重试什么关系？"**
   一句话：重试救**偶发**失败（放大流量换成功率），熔断防**持续**失败（快速失败换可用性）；两者配合、顺序有讲究。
3. **"为什么重试必须幂等？"**
   一句话："失败"可能是响应丢失而非业务失败，重试非幂等写 = 重复下单；默认只重试 GET。
   弹药：实验 C（GET 4 次命中 vs POST 1 次）；幂等键兜底。
4. **"治理四件套顺序？"**
   一句话：限流 → 重试 → 熔断 → 超时（每层超时独立）；限流扣一次、重试算真实调用、熔断看得见每次尝试。
   弹药：SCG 里就是 yml 声明顺序（order=i+1，源码）；超时 × 重试 = 最坏耗时（实验 D）。
5. **"网关熔断和服务内熔断怎么分工？"**
   一句话：网关按路由/服务粗粒度保入口，服务内按依赖方法细粒度做业务降级；两层都要。
6. **"熔断器的超时和路由的超时冲突吗？"**
   一句话：都生效、先到先算；熔断自带 TimeLimiter 默认 1s，不显式配就会"莫名 504"。

---

## 37.10 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| Resilience4j 全部字段（slow-call、TIME_BASED 窗口…） | ⚪ 用到再查；本课掌握窗口/阈值/等待/半开四个 |
| reactor-extra（`reactor.retry.*`）内部实现 | ❌ 知道"Retry 是它实现的"即可 |
| **三态 + 转移条件** | ✅ **本课 🔴①** |
| **重试幂等与默认值** | ✅ **本课 🔴②** |
| **四件套顺序 + 超时联动** | ✅ **本课 🔴③**（架构面试高频） |
| `FallbackHeaders` 过滤器 | ❌ 知道同自动配置提供、需要时再查 |

---

## 37.11 Self-Test

1. 画出断路器三态，标出每一条转移的触发条件（用本课 lab 的参数）。
2. 为什么没配 `statusCodes` 时，下游一直 500 熔断器也不打开？源码里哪一行决定了这件事？
3. 熔断器自带的默认超时是多少？怎么调/怎么关？它和路由 `response-timeout` 谁先生效？
4. Retry 的四个默认值（retries/series/methods/exceptions）是什么？为什么默认只有 GET？
5. 实验 D 里客户端等了 2.02s，怎么算出来的？生产上怎么避免"重试把超时放大"？
6. 四件套为什么推荐"限流 → 重试 → 熔断"，而不是"熔断 → 重试"？后者的语义是什么？
7. （架构）网关熔断和服务内熔断各保护什么？为什么不能只留一层？

---

## 37.12 Open Questions

- [ ] `Retry` 在新版 SCG 里仍用 reactor-extra（本地源码树确认）；若未来迁移到 reactor-core retry，配置语义是否变化——升级时留意。
- [ ] 网关熔断按"服务名"聚合，多实例下被好实例稀释的问题：是否值得按实例做熔断（或用 LB 权重/离群实例剔除）——与 L16/L42 并线。
- [ ] `FallbackHeaders` 与 fallback 端点的组合用法（把异常头透传给下游 fallback 服务）——需要再实验。
