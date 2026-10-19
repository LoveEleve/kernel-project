# Lesson 36: Rate Limiting — `RequestRateLimiter` + `RedisRateLimiter`（Redis+Lua 令牌桶）

> 课程索引：`../gateway-学习清单.md` → **Stage 7**（内置能力补全）· Lesson 36
> 前置：**L27 §27.8**（RateLimiter 工厂结构预览）· **L14**（三段式：写自定义 bean 的思路）· **L24 §24.5**（Weight 的"事件通道"模式 —— 本课会再遇到同款）
> 口径：🔴 ① **令牌桶 + Lua 的原子性**（为什么不用 Java 实现：多实例下要共享计数）② **`KeyResolver` 决定限流粒度**（按用户 / 按 IP / 按接口）
> 版本口径：demo 跑的是 **SCG 4.1.5**（Spring Cloud 2023.0.3），本课源码引用以该版本为准。
> 本地源码树是更新版本，实质差异有两处：① Redis key 加了 `routeId` 命名空间（见 36.3② 末尾）；
> ② fail-open 内层日志从 debug 改成 error（见 36.3②）。下文引用处均已标出版本，别处不再重复。

**一句话（先说人话）**：请求到达业务之前，网关先到 Redis 查一个"账本"——账上有令牌就放行，没有就直接回 429，
业务一行代码不用改。本课一条主线跑到底：`GET /rl-lab/echo/hi` 并发 6 发，两发 200、四发 429。

---

## 36.1 它干了什么：一条路由，两组输出

前置（缺一不可）：本机 Redis 6379 在跑、provider 8081 在跑、gateway 8080 在跑。
开关已落盘：`demo-gateway/.../filter/RateLimitConfig.java`（`pathKeyResolver` 主用 + `userPathKeyResolver` 生产沉淀，见 36.6②）+
`application.yml` 的 `rate-limit-lab` 路由（`/rl-lab/**`，`replenishRate=2、burstCapacity=2`，下游 `http://localhost:8081`）：

```yaml
- id: rate-limit-lab
  uri: http://localhost:8081
  predicates:
    - Path=/rl-lab/**
  filters:
    - StripPrefix=1
    - name: RequestRateLimiter
      args:
        redis-rate-limiter.replenishRate: 2
        redis-rate-limiter.burstCapacity: 2
        key-resolver: "#{@pathKeyResolver}"
```

顺序打 5 次（每次请求间隔里，令牌按 `rate=2/s` 补充，桶慢慢回满）：

```bash
for i in 1 2 3 4 5; do
  curl -s --noproxy '*' -i -m 5 "http://127.0.0.1:8080/rl-lab/echo/hi" | grep -E "^HTTP|X-RateLimit-Remaining"
done
```

实测（2026-10-03）：`200(Remaining:1) → 200(0) → 200(1) → 200(0) → 429(0)`。
注意允许和拒绝**都带** `X-RateLimit-*` 头（一共 4 个：`Remaining/Replenish-Rate/Burst-Capacity/Requested-Tokens`）——
这是后面 36.3① 源码里"先写头、再判断放行"对应的行为。

并行一次打穿（先 `sleep 3` 让桶回满，6 发并发）：

```bash
sleep 3; for i in 1 2 3 4 5 6; do
  curl -s --noproxy '*' -o /dev/null -w "%{http_code} " "http://127.0.0.1:8080/rl-lab/echo/hi-burst" &
done; wait
```

实测（2026-10-03）：`200 200 429 429 429 429`（顺序不定：放行的两发正好等于 `burstCapacity=2`，其余四发 429）。

账本长这样（TTL=2s，闲置自过期，查完不用清）：

```bash
redis-cli -p 6379 KEYS 'request_rate_limiter.*'
# request_rate_limiter.{/echo/hi-burst}.tokens / .timestamp
```

先记住两个怪现象，后面都会解释：① key 里是**剥掉 `/rl-lab` 前缀后**的 path；② key 里**没有路由名**
（4.1.5 的行为，新版才有 `routeId` 命名空间）。

---

## 36.2 为什么长这样：两个设计决策

**决策一：账本放哪？** 网关通常多实例部署，每个实例自己在内存里计数，限 10 QPS 起 3 个实例就变成 30 QPS
（超卖 × 实例数）。所以计数必须放**外部共享存储**（Redis），而"读桶 → 算 → 写桶"三步必须**一次原子完成**，
否则多实例并发会超发——Redis 执行 Lua 脚本是单线程原子的，一个脚本里算完，天然并发安全。
`{}` hash tag 是顺带的：保证 `tokens`/`timestamp` 两个 key 落在同一个 Redis Cluster 槽位，否则跨槽会失败。

**决策二：按什么分桶？** 同一套 `replenishRate=10`，"每个登录用户各 10 QPS"和"整站共用 10 QPS"是完全不同的限流。
SCG 把这个选择权交给了 `KeyResolver`：它返回的字符串直接拼进 Redis key，**key 不同 = 桶不同**。
换个 resolver 就是另一种粒度（细表见 36.4）。

**为什么是令牌桶，不是"每秒清零"的计数器？** 固定窗口有个硬伤叫**边界突刺**：
第 1 秒最后 10ms 打满 100 发、第 2 秒头 10ms 再打满 100 发，10ms 内实际过了 200 发。
令牌桶换思路：桶里放令牌、按固定速率往里加（`replenishRate` 颗/秒），请求先取令牌（默认 1 颗，`requestedTokens`），
取到才放行；桶有上限（`burstCapacity`），平时攒下的令牌允许突刺一阵，但长期平均速率被令牌补充速率约束。
一句话分工：**`replenishRate` 管长期平均，`burstCapacity` 管能突发多少**（源码约束 `burst >= replenishRate`）。

> 更深的概念（算法谱系 / Little's Law / 分层放置 / 内置 vs Sentinel 取舍）已独立成专题 →
> [`rate-limiting-concepts.md`](rate-limiting-concepts.md)，本课只讲网关内的实现。

---

## 36.3 怎么做：一次请求的旅程 + 三段代码

```
请求进入
  → ① KeyResolver.resolve(exchange) 取"限流 key"      （没取到 → EMPTY_KEY → 默认 403，见 36.4）
  → ② 拿 routeId 取本路由的配额                       （⚠️ 4.1.5 里 routeId 只选 Config，不隔离账本）
  → ③ limiter.isAllowed(routeId, key)                  （Redis 里原子跑 Lua：取令牌）
  → ④ 把 X-RateLimit-* 头写进响应                      （允许/拒绝都有，空 key 分支除外）
  → ⑤ 允许 → chain.filter 继续；拒绝 → 429 setComplete
```

### ① 过滤器（`RequestRateLimiterGatewayFilterFactory`）：两个"开关"别混

```java
public GatewayFilter apply(Config config) {
    KeyResolver resolver = getOrDefault(config.keyResolver, defaultKeyResolver);
    RateLimiter<Object> limiter = getOrDefault(config.rateLimiter, defaultRateLimiter);
    boolean denyEmpty = getOrDefault(config.denyEmptyKey, this.denyEmptyKey);   // bean 级默认 true
    HttpStatusHolder emptyKeyStatus = HttpStatusHolder
        .parse(getOrDefault(config.emptyKeyStatus, this.emptyKeyStatusCode));   // bean 级默认 FORBIDDEN
    return (exchange, chain) -> resolver.resolve(exchange).defaultIfEmpty(EMPTY_KEY).flatMap(key -> {
        if (EMPTY_KEY.equals(key)) {                       // 没拿到 key
            if (denyEmpty) { setResponseStatus(exchange, emptyKeyStatus); return exchange.getResponse().setComplete(); }
            return chain.filter(exchange);                 // 关掉 deny → 直接放行（不限流）
        }
        String routeId = config.getRouteId();
        if (routeId == null) { routeId = exchange.getAttribute(GATEWAY_ROUTE_ATTR).getId(); }
        return limiter.isAllowed(routeId, key).flatMap(response -> {     // 问限流器
            for (Map.Entry<String, String> header : response.getHeaders().entrySet()) {
                exchange.getResponse().getHeaders().add(header.getKey(), header.getValue());   // 先写头
            }
            if (response.isAllowed()) { return chain.filter(exchange); }  // 再判断
            setResponseStatus(exchange, config.getStatusCode());         // 拒绝：默认 429
            return exchange.getResponse().setComplete();
        });
    });
}
public static class Config implements HasRouteId {
    private KeyResolver keyResolver;                   // key-resolver: "#{@bean}"
    private RateLimiter rateLimiter;                   // 想换限流器时用
    private HttpStatus statusCode = HttpStatus.TOO_MANY_REQUESTS;   // 超限 → 429
    private Boolean denyEmptyKey;  private String emptyKeyStatus;    // 空 key → 默认 403
    private String routeId;
}
```

`deny-empty-key`（空 key 怎么办，默认拒绝 403）和 `statusCode`（超限返回什么，默认 429）是两件事，
配错一个就会看到"为什么是 403 不是 429"（36.4 有完整故事）。

### ② 限流器（`RedisRateLimiter`）：配额从哪来，出错往哪倒

```java
static List<String> getKeys(String id) {
    // use `{}` around keys to use Redis Key hash tags
    // this allows for using redis cluster

    // Make a unique key per user.
    String prefix = "request_rate_limiter.{" + id;

    // You need two Redis keys for Token Bucket.
    String tokenKey = prefix + "}.tokens";
    String timestampKey = prefix + "}.timestamp";
    return Arrays.asList(tokenKey, timestampKey);   // 桶状态就这两个 key
}

public Mono<Response> isAllowed(String routeId, String id) {
    Config routeConfig = loadConfiguration(routeId);   // ★ 在 try 之外：找不到配额直接抛，不会被 fail-open 接住
    int replenishRate = routeConfig.getReplenishRate();
    int burstCapacity = routeConfig.getBurstCapacity();
    int requestedTokens = routeConfig.getRequestedTokens();
    try {
        List<String> keys = getKeys(id);   // ★ 只拿 key 拼 Redis key（routeId 只用来取 Config）
        List<String> scriptArgs = Arrays.asList(replenishRate + "", burstCapacity + "", "", requestedTokens + "");
        Flux<List<Long>> flux = this.redisTemplate.execute(this.script, keys, scriptArgs);   // ★ 原子执行 Lua
        return flux.onErrorResume(throwable -> {       // ★ 第一层兜底：Redis 出错 → {1,-1} = 放行
            if (log.isDebugEnabled()) {                // ★ 4.1.5 这里是 debug（新版才改 error）——告警别指望日志
                log.debug("Error calling rate limiter lua", throwable);
            }
            return Flux.just(Arrays.asList(1L, -1L));
        }).reduce(...).map(results -> {
            boolean allowed = results.get(0) == 1L;
            return new Response(allowed, getHeaders(routeConfig, results.get(1)));
        });
    } catch (Exception e) {                            // ★ 第二层兜底：同步异常 → 也放行
        /*
         * We don't want a hard dependency on Redis to allow traffic. Make sure to set
         * an alert so you know if this is happening too much. Stripe's observed
         * failure rate is 0.01%.
         */
        log.error("Error determining if user allowed from redis", e);
    }
    return Mono.just(new Response(true, getHeaders(routeConfig, -1L)));
}
```

三件事要记住：

- **配额从哪来**：路由编译时，`RouteDefinitionRouteLocator` 把 filter 参数包成 `FilterArgsEvent` 发出来，
  `AbstractRateLimiter.onApplicationEvent` 只收带 `redis-rate-limiter.` 前缀的，绑成这条路由专属的 Config，
  请求时 `loadConfiguration(routeId)` 取回来（和 L24 Weight 同款"事件通道"）。装配方是
  `GatewayRedisAutoConfiguration`：`redisRequestRateLimiterScript`（加载 Lua）+ `redisRateLimiter`
 （`@ConditionalOnMissingBean`），有 `ReactiveRedisTemplate` 时生效（`spring.cloud.gateway.redis.enabled` 默认开）。
- **fail-open 两层保险**：内层 `onErrorResume → {1,-1}`、外层 `catch → Response(true)`。
  **Redis 故障时宁可放行**（可用性优先），Stripe 注释原话要求配套告警；但 4.1.5 内层只打 debug 日志，
  线上真正可观测的信号是响应头 `X-RateLimit-Remaining: -1`。
- **`Config` 的约束（会咬人）**：`replenishRate`（`@Min(1)`，必填）、`burstCapacity`（默认 1，
  `setBurstCapacity` 里 `Assert.isTrue(burstCapacity >= replenishRate)` → **桶容量不能小于回流速**，
  配反了路由编译期就炸）、`requestedTokens`（默认 1）。

> 版本差异（只讲这一次）：本地源码树（新版）已改成 `getKeys(id, routeId)` →
> `request_rate_limiter.{routeId.id}.tokens`，相同 key 跨路由不再共桶。升级 SCG 后行为会变，读源码以运行时版本为准。

### ③ Lua（`request_rate_limiter.lua`）：令牌桶的数学就这几行——令牌补充 → 容量截断 → 令牌扣除

```lua
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local fill_time = capacity / rate
local ttl = math.floor(fill_time * 2)                  -- 桶空闲多久可销毁（本例 2/2 → 2s；一般公式）

-- for testing, it should use redis system time in production
if now == nil then
  now = redis.call('TIME')[1]                          -- ★ 生产走这条：Java 传的是 ""，tonumber("")=nil
end

local last_tokens = tonumber(redis.call("get", tokens_key))
if last_tokens == nil then
  last_tokens = capacity                               -- 桶不存在 = 装满
end

local last_refreshed = tonumber(redis.call("get", timestamp_key))
if last_refreshed == nil then
  last_refreshed = 0
end

local delta = math.max(0, now - last_refreshed)
local filled_tokens = math.min(capacity, last_tokens + (delta * rate))   -- ★ 按时间补充 + 容量截断
local allowed = filled_tokens >= requested
local new_tokens = filled_tokens
local allowed_num = 0
if allowed then
  new_tokens = filled_tokens - requested               -- ★ 令牌扣除（仅放行时）
  allowed_num = 1
end

if ttl > 0 then
  redis.call("setex", tokens_key, ttl, new_tokens)     -- 写回桶
  redis.call("setex", timestamp_key, ttl, now)
end
return { allowed_num, new_tokens }
```

不用经过网关，直接调真正的脚本就能验证这段数学（`rate=2、capacity=2、requested=1`）：

```bash
SCRIPT=.../spring-cloud-gateway-server/src/main/resources/META-INF/scripts/request_rate_limiter.lua
K1='request_rate_limiter.{demo-l36.user1}.tokens'
K2='request_rate_limiter.{demo-l36.user1}.timestamp'
redis-cli -p 6379 DEL "$K1" "$K2"

echo "调用1 (now=1000): $(redis-cli -p 6379 --eval $SCRIPT "$K1" "$K2" , 2 2 1000 1)"
echo "调用2 (now=1000): $(redis-cli -p 6379 --eval $SCRIPT "$K1" "$K2" , 2 2 1000 1)"
echo "调用3 (now=1000): $(redis-cli -p 6379 --eval $SCRIPT "$K1" "$K2" , 2 2 1000 1)"
echo "调用4 (now=1001): $(redis-cli -p 6379 --eval $SCRIPT "$K1" "$K2" , 2 2 1001 1)"
redis-cli -p 6379 GET "$K1"; redis-cli -p 6379 TTL "$K1"
```

实测输出（每个 Lua 表返回两个数：`allowed` / `tokens_left`）：

```
调用1 (now=1000): 1/1   ← 允许，剩 1
调用2 (now=1000): 1/0   ← 允许，剩 0
调用3 (now=1000): 0/0   ← ★ 拒绝（桶空了）
调用4 (now=1001): 1/1   ← ★ 过 1 秒：delta=1 × rate=2 → 补充 2 颗，扣除 1 颗剩 1
tokens=1  timestamp=1001  ttl=2
```

为什么生产时间必须是 Redis 的 TIME？Java 侧第三个参数写死传 `""`
（`isAllowed` 里 `Arrays.asList(replenishRate+"", burstCapacity+"", "", requestedTokens+"")`），
`tonumber("")` 得 nil，正好落进 `if now == nil` 分支。**多网关实例各用本地时钟会 skew，
账本的时间标尺必须由 Redis 统一**——上面传 `now=1000/1001` 是借了脚本留的测试后门，
生产实际走 `TIME[1]`（秒部分，秒级精度）。

---

## 36.4 `KeyResolver`：限流"粒度"由它决定

```java
@FunctionalInterface
public interface KeyResolver {
    Mono<String> resolve(ServerWebExchange exchange);
}
```

假设都是 `replenishRate=10`：`principalNameKeyResolver` 是**每个登录用户**各自 10 QPS（张三打满不影响李四）；
IP 版是**每个来源 IP**各自 10 QPS；path 版是**每个接口**各自 10 QPS；常量 `"global"` 是**整站**共用一个 10 QPS 的桶。
底层原因就一句：key 拼进 Redis key（4.1.5 是 `request_rate_limiter.{key}.tokens`），**key 不同 = 桶不同**：

| 想要 | KeyResolver 写法 | 效果 |
|---|---|---|
| 按**用户** | `exchange.getPrincipal().flatMap(p -> Mono.justOrEmpty(p.getName()))`（内置 `principalNameKeyResolver` 原文） | 每个登录用户独立配额；**无认证时为空 → 403**（见下） |
| 按 **IP** | `Mono.just(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress())` | 防刷；⚠️ 注意 XFF 要配合可信代理（只认代理追加的 XFF，本系列暂未单开一课）；`getRemoteAddress()` 可能为空（如单测直调），生产加空判断 |
| 按**接口** | `Mono.just(exchange.getRequest().getPath().value())`（本课 `pathKeyResolver`） | 保护单个热点接口 |
| **全局** | `Mono.just("global")` | 整站一个桶（慎用） |

**为什么默认会 403 而不是 429？** `principalNameKeyResolver` 全文只有一句
`exchange.getPrincipal().flatMap(p -> Mono.justOrEmpty(p.getName()))`——取的是已认证用户，
demo 无认证 → 空 Mono → `defaultIfEmpty(EMPTY_KEY)` → `denyEmptyKey` 默认 true → 403。
（最早用动态路由 `tmp-l36-rl` + 默认 resolver 实测过：`HTTP/1.1 403`，空 key 分支直接 `setComplete`，
连 Redis 都不碰。）把它换成 `"deny-empty-key":"false"`，同一请求变 200——空 key 直接放行、**完全不限流**，
这是最容易踩的坑：关掉的是"空 key 拒绝"，不是"限流放宽"。

**两个实测抓到的分桶细节**（36.1 账本的两个怪现象的答案）：

- **相同 key 跨路由共桶**：4.1.5 的 key 不含 routeId，两条路由都用 path 限流、前缀剥完又撞 path，就会互相挤占。
  设计多路由限流时要么让 key 自带区分度（如拼路由名前缀），要么升级 SCG 吃 routeId 命名空间。
- **resolver 看到的是过滤后的 path**：`StripPrefix`（order=1，先跑）会 `request.mutate().path(newPath)` 改写请求，
  `RequestRateLimiter`（order=2，后跑）才调 resolver——`GET /rl-lab/echo/hi-burst` 落进 Redis 的是
  `request_rate_limiter.{/echo/hi-burst}.tokens`。`/actuator/gateway/routes/rate-limit-lab` 可查两过滤器的 order。
  所以"按接口限流"实际按**剥掉前缀后的 path**分桶，排过滤器顺序时要意识到这一点。

---

## 36.5 有什么坑：七种"没配对"的下场

| 配法 | 现象 | 原因 |
|---|---|---|
| 过滤器都没挂 | 无限流、无 429、无 Redis 调用，连错都不报 | 链里没有 `RequestRateLimiter`，bean 只是躺着（demo 开开关之前就是这状态） |
| 挂了名、没给 `redis-rate-limiter.*` | 500 | `loadConfiguration` 在 try 之外抛 `IllegalArgumentException("No Configuration found for route … or defaultFilters")`；Spring 构造器不设 `defaultConfig`，兜底退化成"本路由 → defaultFilters → 抛" |
| 用默认 resolver、无认证 | 403（不是 429） | 空 key + `denyEmptyKey` 默认 true；关掉则变 200 且不限流 |
| `burstCapacity < replenishRate` | 路由编译期炸（`Assert`） | `setBurstCapacity` 强约束；先想 rate 再想 burst |
| 加第二个 `KeyResolver` bean、无 `@Primary` | 网关起不来（`NoUniqueBeanDefinition`） | SCG 工厂构造器要单例注入；主用 bean 标 `@Primary`（本 demo 实测踩过，`RateLimitConfig` 有注释） |
| 全局和路由**两边都挂名** | 一次请求扣两颗、头写两遍 | 合并只是 `addAll` + 排序、不去重，两边各跑一遍 Lua（源码见 `RouteDefinitionRouteLocator#getFilters`） |
| 把 yml 的 `default-filters` 当成兜底链的 `"defaultFilters"` | 两边都没给参照样 500 | 4.1.5 里 yml 全局参数落在**每条路由名下**（见下），`"defaultFilters"` 分支只留给代码手动 `put`/DSL，yml 配不进去 |
| Redis 挂了 | 全部放行，`Remaining:-1` | fail-open 两层兜底；4.1.5 内层只打 debug 日志，告警靠头或 metrics，别靠日志 |

### 全局写法（`default-filters`）：对每条路由生效，参数可被路由覆盖

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - name: RequestRateLimiter
          args:
            redis-rate-limiter.replenishRate: 10
            redis-rate-limiter.burstCapacity: 20
            key-resolver: "#{@pathKeyResolver}"
```

三件事（4.1.5 源码级行为）：① 对**所有**路由生效（含以后新增的）；② 两次绑定用**同一个路由 id**发事件，
带 `redis-rate-limiter.` 前缀的参数"后写覆盖先写"——路由单独配参可覆盖基线；③ 基线和个性**二选一挂名**
（见上表"两边都挂名"一行）。本 demo 刻意用路由级：切全局会把 L07/L14/L16/L18 的实验路由一起限速。

断点姿势：`RequestRateLimiterGatewayFilterFactory#apply` 的 lambda（看 key/EMPTY_KEY）→
`RedisRateLimiter#isAllowed`（看 scriptArgs）→ `redis-cli MONITOR`（实时看 `EVALSHA` 调用）。

---

## 36.6 生产要掌握的六件事（demo 和生产的区别全在这里）

### ① 网关是第一道，不是唯一一道

网关限流管的是**入口配额**（防洪）：粒度粗（按用户/IP/接口），目标是"别让下游被冲垮"。
服务内还要第二道：按**业务资源**限（比如"某 SKU 每秒最多卖 50 件"——网关看不到库存，配不出来）。
只配网关：业务级超卖防不住；只配服务内：洪峰已经打进来了，网关和线程池先遭殃。
生产结论：两层都要，职责不同，不互相替代（和 Sentinel 的分工见 36.7 第 7 问）。

### ② 粒度要会组合，单维度不够用

36.4 的四种是单维度，生产最常用的是组合——"每个用户在每个接口上各 N QPS"：

```java
@Bean
@Primary   // ★ 必加：SCG 工厂构造器要单例注入，两个 KeyResolver bean 无 @Primary 直接起不来（NoUniqueBeanDefinition，实测踩过）
public KeyResolver userPathKeyResolver() {
    return exchange -> exchange.getPrincipal()
        .map(Principal::getName)
        .map(user -> user + ":" + exchange.getRequest().getPath().value())
        .defaultIfEmpty("anonymous:" + exchange.getRequest().getPath().value());
}
```

（已落盘：`demo-gateway/.../filter/RateLimitConfig.java`，暂无路由引用，仅作生产写法沉淀。
路由里不写 `key-resolver` 时，工厂回退到注入的默认 bean——本 demo 即 `@Primary` 的 `pathKeyResolver`
（框架自带的 `principalNameKeyResolver` 因 `@ConditionalOnMissingBean` 已让位）。）
为什么必须组合：只按用户限，单个用户刷单个热点接口仍能打满它；只按接口限，一个刷子能挤占全站配额。
组合 key 让刷子只能耗自己的桶。分工记忆：**用户防刷单、IP 防 DDoS、接口防热点、global 整站兜底**（global 慎用：
一个被刷全站陪葬）。无认证服务用 `anonymous:` 前缀桶兜底，误伤面等价于按接口限流，可控。

### ③ 参数从压测来，不能拍脑袋

`replenishRate` = **下游压测出的安全水位反推**：比如库存服务单机 500 QPS、3 台，网关限 1200（留余量，
别顶着 1500 配，超时重试会放大流量）。`burstCapacity` = **吃正常突发**：整点秒杀前 1 秒天然有 3 倍毛刺，
burst 太小等于误杀正常用户，太大等于攻击直达下游。上线后看 **429 误杀率**调：正常用户抱怨多就放 burst，
下游一抖就收 rate。还有一条容易漏的：`L = λ × W`——限 QPS 必须和**超时、并发上限**一起配，
时延涨 10 倍、QPS 不变，并发数涨 10 倍，线程池先爆（展开见概念专题 Little's Law 一节）。

### ④ Redis 挂了要有预案，这是生产和 demo 的最大区别

SCG 默认 fail-open（放行，保可用性），代价是攻击期间 Redis 一挂等于裸奔。生产三选一，必须显式定：

- fail-open + 告警：SCG 默认选择。告警靠 **`X-RateLimit-Remaining: -1` 头或 Redis 监控**，
  **别靠日志**——4.1.5 内层只打 debug，日志里看不见（36.3②）。
- fail-close：自定义 `RateLimiter`，Redis 异常返回拒绝。金融级用，但 Redis 抖动会误杀全站，决定前想清楚。
- 本地降级：Redis 失联时切单机限流（Guava/Sentinel 单机版）顶一阵，多实例超卖 ×N 也认了——先活着。

以及 Redis 自身的高可用：哨兵或集群（`{}` hash tag 就是为集群准备的，36.3②），别让账本成为单点。

### ⑤ 可观测是限流的一半

四个 `X-RateLimit-*` 头是**给调用方看的**（剩多少、配额多少，客户端按 replenish 估算退避）。
**给自己看的**是三样：Redis `MONITOR`（确认 Lua 真正在跑）、网关 metrics、**429 占比大盘**
（分路由、分 key 看：429 突增 = 被刷或配错，429 归零 = 开关掉了或 key 全空）。限流配了没大盘，等于盲飞。

### ⑥ 网关限流 ≠ 安全

它只管"量"，不管"坏"：恶意 IP 要**封禁**（黑名单/WAF），不在限流职责内。还有一个攻击角度：
XFF 伪造——`X-Forwarded-For` 是客户端自报的，攻击者每次换一个 XFF 就等于换一个桶，
按 IP 限流形同虚设。所以按 IP 限必须配合**可信代理**（只认代理追加的 XFF，本系列暂未单开一课），否则等于没限。

---

## 36.7 面试八问（一句话答案 + 追问弹药）

1. **"令牌桶和漏桶/滑动窗口的区别？"**
   一句话：令牌桶**允许突发**（攒令牌），漏桶**恒定输出**（整形），滑动窗口是计数器的改良。
   追问弹药：固定窗口**边界突刺**（两秒交界 10ms 内过 200 发，36.2）。
2. **"为什么计数放 Redis、为什么用 Lua？"**
   一句话：多实例**共享** + "读-算-写"**原子**，Java 实现多实例超卖 ×N。
   追问弹药：TIME 统一时钟（本地时钟 skew，36.3③）、hash tag 同槽（36.3②）。
3. **"粒度由谁决定？"**
   一句话：`KeyResolver`，key 不同桶不同；报出用户/IP/接口/**组合**四种，生产用组合。
   追问弹药：4.1.5 跨路由共桶、StripPrefix 改写 path（36.4 实测细节，面试官最爱）。
4. **"403 和 429 分别什么时候出？"**
   一句话：空 key + denyEmpty 默认 **403**；超限 **429**；关 deny 变 200 且不限流。
   追问弹药：这是两个开关，别混（36.3①）。
5. **"Redis 挂了怎么办？"**
   一句话：fail-open 两层兜底（`{1,-1}` + catch），Stripe 注释原话，可用性优先 + 必须配告警。
   追问弹药：4.1.5 内层 debug 日志，告警靠头/metrics（36.3②）；fail-close 定制（36.6④）。
6. **"阈值怎么定？"**
   一句话：下游压测水位反推 + burst 吃突发 + 上线看 429 误杀率调。
   追问弹药：Little's Law，限 QPS 必须和超时/并发一起配（36.6③）。
7. **"网关限流和 Sentinel 是什么关系？"**
   一句话：网关是入口粗粒度第一道，Sentinel 是服务内多维度（QPS/并发/慢调用/异常比例/热点参数/系统自适应）+ 熔断降级；生产都要，不是二选一。
8. **"10W QPS 怎么限？"**
   一句话：先算账（单 Redis Lua 几万 QPS，顶不住就配额分片/多级：Nginx 本地粗限 + 网关共享细限），再谈架构。别一上来背方案。

---

## 36.8 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| Lua 语法/Redis 脚本缓存（EVALSHA） | ❌ 知道"脚本原子执行"即可 |
| `requestedTokens`（一次扣多颗）的调参 | ⚪ 了解（批量接口/加权限流时才用） |
| **令牌桶 + 为什么必须 Redis/Lua** | ✅ **本课 🔴①**（面试高频） |
| **`KeyResolver` 决定粒度 + 空 key 语义** | ✅ **本课 🔴②**（403 坑必须知道） |
| **fail-open + 网关/服务/内置/Sentinel 取舍** | ✅ 要会（架构面试） |
| `RedisRateLimiter` 其它可配 header/前缀 | ❌ 用到再查 |

---

## 36.9 Self-Test

1. 令牌桶的两个参数（replenishRate / burstCapacity）在 Lua 里怎么用？令牌补充的公式是什么？
2. 为什么把计数放 Redis、把"读-算-写"塞进 Lua？不用 Lua 会出什么问题？（多实例并发场景）
3. 限流粒度由谁决定？给出"按用户/按 IP/按接口"三种 KeyResolver 写法。
4. 空 key 时默认发生什么？`deny-empty-key=false` 又是什么行为？（两种状态码：403 vs 429 分别对应哪种情况）
5. Redis 挂掉时内置限流会"放行"还是"拒绝"？线上可观测的信号是什么？（注意 4.1.5 的日志级别）
6. 什么场景该用 Gateway 内置限流、什么场景该上 Sentinel？限流该不该只放网关？
7. （概念）"限流"和"熔断"分别在控制什么、依据什么触发？各自失败时的表现是什么？
8. （概念）用 Little's Law 解释：为什么"限 200 QPS"与"限 100 并发"在 0.5s 时延下等价？时延涨到 5s 会怎样？该配什么兜底？
9. （概念）令牌桶与漏桶的区别？各自适合什么场景？固定窗口的"边界突刺"是什么？

---

## 36.10 Open Questions

- [ ] **首个 RateLimiter 路由的首次编译出现过 ~2.3s 停顿**（`applying RequestRateLimiter → matched` 之间，编译线程是 Lettuce 事件循环）——偶发一次，未复现定位；怀疑与事件循环上的阻塞调用有关。
- [ ] `requestedTokens` 的实战用法（按响应大小/权重扣不同颗数）与配置姿势 —— 未细究。
- [ ] fail-close 的可行改法（自定义 `RateLimiter` / 配置 Sentinel）—— 与 L37/L44 并线看。
