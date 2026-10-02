# Lesson 24: Predicate Family Source（谓词家族）— "会一个就会全部"

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 24（★v5.4 新增）
> 前置：**L20**（工厂机制 5 步链 / shortcut 归一化）· **L21 §21.3**（谓词编译与 `and()`）· **L23**（匹配时记账 `GATEWAY_PREDICATE_ROUTE_ATTR` —— 本课回收）· **L15**（自己写工厂：`L15-custom-predicate-factory.md`）
> 口径：🔴 所有谓词工厂的**统一结构**（`shortcutFieldOrder()` + `Config` + `apply`）—— **会一个就会全部**（不逐个逐行）

**本课回答三个问题**：
① 一行 yml 谓词（`Path=/provider/**`）是怎么变成"每请求可执行的判断"的？
② 所谓"统一结构"到底统一在哪 —— 为什么说"会一个就会全部"？
③ 家族里最特殊的 `Weight` 特殊在哪？（附真实 8:2 分流实验）

---

## 24.1 例子先行：一个谓词的完整一生（真实链路）

### ① yml 写的一行，存下来长什么样？

```bash
curl -s --noproxy '*' http://127.0.0.1:8080/actuator/gateway/routedefinitions \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(json.dumps([x['predicates'] for x in d if x['id'] in ('demo-provider','gray-v2')], indent=2, ensure_ascii=False))"
# [
#   [ { "name": "Path", "args": { "_genkey_0": "/provider/**" } } ],                    ← demo-provider
#   [ { "name": "Path",   "args": { "_genkey_0": "/gray/**" } },                        ← gray-v2
#     { "name": "Weight", "args": { "_genkey_0": "gray", "_genkey_1": "8" } } ]
# ]
```

`Path=/provider/**` 在定义库里的真身是 **`_genkey_0`（位置参数）**——所谓 shortcut 短写，存下来就是"第 0 个参数"。

### ② 归一化：`_genkey_0` 怎么对到字段名？（源码真身）

`ShortcutConfigurable.normalizeKey`（全仓库通用的那把钥匙）：

```java
static String normalizeKey(String key, int entryIdx, ShortcutConfigurable argHints, Map<String, String> args) {
    // RoutePredicateFactory has name hints and this has a fake key name
    // replace with the matching key hint
    if (key.startsWith(NameUtils.GENERATED_NAME_PREFIX) && !argHints.shortcutFieldOrder().isEmpty()
            && entryIdx < args.size() && entryIdx < argHints.shortcutFieldOrder().size()) {
        key = argHints.shortcutFieldOrder().get(entryIdx);      // ★ _genkey_N → shortcutFieldOrder()[N]
    }
    return key;
}
```

所以"短写怎么排"完全由 `shortcutFieldOrder()` 决定，`ShortcutType` 只是三种**搬运方式**：

| ShortcutType | 规则（源码） | 谁在用 |
|---|---|---|
| `DEFAULT` | `_genkey_N` 逐个 → `shortcutFieldOrder[N]`（默认） | 大部分工厂 |
| `GATHER_LIST` | `shortcutFieldOrder` 必须**只有 1 个**名字；所有值收成一个 List | `Method` / `RemoteAddr` / `Host` |
| `GATHER_LIST_TAIL_FLAG` | 必须**2 个**名字；**若最后一个是 `true`/`false`/null** 则剥出来当 flag，其余收成 List；否则全部当 List（flag 用默认值） | `Path`（`patterns` + `matchTrailingSlash`） |

（`Path=/a/**,/b/**` → `patterns=[/a/**,/b/**]`、flag 默认 true；`Path=/a/**,false` → `patterns=[/a/**]`、flag=false。）

### ③ 绑定 + 编译 + 每请求执行

`ConfigurationService`（每个工厂都走它）：

```java
// ConfigurableBuilder.normalizeProperties()：先按 ShortcutType 归一化
return this.configurable.shortcutType().normalize(this.properties, this.configurable, ...);

// doBind()：把前缀当 Binder 的"绑定名"（Weight 用 "weight"，见 24.5）
T bound = bindOrCreate(bindable, this.normalizedProperties, this.configurable.shortcutFieldPrefix(), ...);

// bind()：绑定之后，把【归一化后的 map】交给 eventFunction（FilterArgsEvent / PredicateArgsEvent）
ApplicationEvent applicationEvent = this.eventFunction.apply(bound, this.normalizedProperties);
this.service.publisher.publishEvent(applicationEvent);
```

然后 `factory.applyAsync(config)` → `apply(config)`（**编译期**：parse/转换/组装）→ 返回 `GatewayPredicate`；每请求只跑它的 `test()`（**运行期**）。
编译产物长什么样，L23 的日志已经给过现场：`predicate=Paths: [/provider/**], ...`、`predicate=(Paths... && DemoHeader...)`、`predicate=(Paths... && RemoteAddrs: [127.0.0.1])`。

**一条链**：`yml 短写 → _genkey args → 归一化(shortcutFieldOrder) → Binder → Config → apply(编译一次) → GatewayPredicate → test(每请求)`。
下面四个代表，就是这条链在四个方向的变体。

---

## 24.2 家族的统一骨架

```java
@FunctionalInterface
public interface RoutePredicateFactory<C> extends ShortcutConfigurable, Configurable<C> {

    default Predicate<ServerWebExchange> apply(Consumer<C> consumer) {  // Java DSL 便捷版
        C config = newConfig(); consumer.accept(config); beforeApply(config); return apply(config);
    }
    default AsyncPredicate<ServerWebExchange> applyAsync(C config) {
        return toAsyncPredicate(apply(config));                          // 默认包装（L23 §23.2 看过）
    }
    Predicate<ServerWebExchange> apply(C config);                        // ★ 统一入口

    default void beforeApply(C config) { }                               // 钩子（Weight 用它，见 24.5）
    default String name() { return NameUtils.normalizeRoutePredicateName(getClass()); }
}

public abstract class AbstractRoutePredicateFactory<C> extends AbstractConfigurable<C>
        implements RoutePredicateFactory<C> { /* 只是给你继承的壳 */ }
```

**三件套 = `Config`（args 的类型视图）+ `shortcutFieldOrder()`（yml 短写怎么排）+ `apply(config)`（干活）**。
内置家族全员（14 个工厂，`handler/predicate/`；第 15 个是我们 L15 的 `demoHeader`）——按"加工物"分组：

| 组 | 成员 | "加工物"（apply 里做什么） | test 里做什么 |
|---|---|---|---|
| 路径 | `Path` | 编译期 parse 成 `PathPattern` | 请求 path 对 pattern 匹配 |
| 主机 | `Host` | **不预加工**（常驻 `AntPathMatcher(".")`） | 取 `Host` 头与 pattern 匹配（命中同样写 URI 变量） |
| 字段比较 | `Method` `Header` `Query` `Cookie` | 存字段/正则 | 取请求对应字段比一比 |
| 时间 | `After` `Before` `Between` | 存 `ZonedDateTime` / 区间 | `ZonedDateTime.now()` 比时间 |
| 网络 | `RemoteAddr` `XForwardedRemoteAddr` | 转 `IpSubnetFilterRule` 列表 | 解析客户端地址 → 逐条 CIDR 匹配 |
| 特殊 | `Weight` | 无（数据来自外部） | 比对"本请求被选中谁" |
| 特殊 | `ReadBody` | 缓存策略 | **真异步**读 body（重写 `applyAsync`） |
| 特殊 | `CloudFoundryRouteService` | header 检查（极少用） | 一句带过 |

---

## 24.3 代表①：`MethodRoutePredicateFactory`（最小对照）＋实验

```java
public class MethodRoutePredicateFactory extends AbstractRoutePredicateFactory<MethodRoutePredicateFactory.Config> {
    public static final String METHODS_KEY = "methods";
    public MethodRoutePredicateFactory() { super(Config.class); }

    @Override
    public List<String> shortcutFieldOrder() { return Arrays.asList(METHODS_KEY); }
    @Override
    public ShortcutType shortcutType() { return ShortcutType.GATHER_LIST; }      // Method=GET,POST → methods=[GET,POST]

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        return new GatewayPredicate() {
            @Override
            public boolean test(ServerWebExchange exchange) {
                HttpMethod requestMethod = exchange.getRequest().getMethod();
                return stream(config.getMethods()).anyMatch(httpMethod -> httpMethod == requestMethod);   // ★ 枚举引用比较
            }
            @Override
            public String toString() { return String.format("Methods: %s", Arrays.toString(config.getMethods())); }
        };
    }
    public static class Config {
        private HttpMethod[] methods;   /* getter/setter 略 */
    }
}
```

**现场实验**：demo 里 `cors-lab2` 路由的谓词是 `Path=/cors-lab2/**` **且** `Method=GET`：

```bash
# GET → 200（两个谓词都真）
curl -s --noproxy '*' -i http://127.0.0.1:8080/cors-lab2/hello
# HTTP/1.1 200 OK
# X-Demo-WebFilter: hit-128
# ...
# {"port":8081,...,"x-forwarded-prefix":"/cors-lab2",...}

# POST → 404（Path 真、Method 假 → 整条路由不选）
curl -s --noproxy '*' -i -X POST http://127.0.0.1:8080/cors-lab2/hello
# HTTP/1.1 404 Not Found
# Content-Length: 131
# {"timestamp":"2026-10-02T04:00:03.631+00:00","path":"/cors-lab2/hello","status":404,"error":"Not Found","requestId":"22d3723a-129"}
```

**这就是"最小谓词工厂"**：三件套齐、`test` 一行比较。会了它 + Path 的"加工"手法，Header/Query/Cookie/Host/After/Before/Between **全是同一个模子**。

---

## 24.4 代表②：`PathRoutePredicateFactory`（最典型的"编译加工"）

```java
public Predicate<ServerWebExchange> apply(Config config) {
    final ArrayList<PathPattern> pathPatterns = new ArrayList<>();
    synchronized (this.pathPatternParser) {
        pathPatternParser.setMatchOptionalTrailingSeparator(config.isMatchTrailingSlash());
        config.getPatterns().forEach(pattern -> pathPatterns.add(pathPatternParser.parse(pattern)));  // ★ 编译期 parse
    }
    return new GatewayPredicate() {
        @Override
        public boolean test(ServerWebExchange exchange) {
            PathContainer path = (PathContainer) exchange.getAttributes()
                    .computeIfAbsent(GATEWAY_PREDICATE_PATH_CONTAINER_ATTR,                     // ★ 每请求只 parse 一次
                            s -> parsePath(exchange.getRequest().getURI().getRawPath()));
            PathPattern match = null;
            for (PathPattern pathPattern : pathPatterns) {                                     // 逐个 pattern 匹配
                if (pathPattern.matches(path)) { match = pathPattern; break; }
            }
            if (match != null) {
                traceMatch("Pattern", match.getPatternString(), path, true);
                PathMatchInfo pathMatchInfo = match.matchAndExtract(path);
                putUriTemplateVariables(exchange, pathMatchInfo.getUriVariables());             // ① 路径变量
                exchange.getAttributes().put(GATEWAY_PREDICATE_MATCHED_PATH_ATTR, match.getPatternString()); // ②
                String routeId = (String) exchange.getAttributes().get(GATEWAY_PREDICATE_ROUTE_ATTR);        // ③ L23 记账
                if (routeId != null) {
                    exchange.getAttributes().put(GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR, routeId);
                }
                return true;
            }
            traceMatch("Pattern", config.getPatterns(), path, false);
            return false;
        }
        @Override
        public Object getConfig() { return config; }
        @Override
        public String toString() {
            return String.format("Paths: %s, match trailing slash: %b", config.getPatterns(), config.isMatchTrailingSlash());
        }
    };
}
```

三个要点：

1. **两次 parse 的层次**：pattern 在 `apply`（编译期，每路由一次）；请求 path 在 `test` 首次触发时（每请求一次，多个 Path 谓词经 `GATEWAY_PREDICATE_PATH_CONTAINER_ATTR` **共享**同一个 PathContainer）
2. **命中不只返回 true**：还写 3 个属性（URI 模板变量 / 命中的模式串 / 路由 id —— 最后两个给 metrics，L23 §23.6）
3. `toString()` 的产物正是 `/routes` 里的 `Paths: [/provider/**], match trailing slash: true`（`/routes` 输出的来源；L21 §21.1 实测、L23 §23.1 日志）

---

## 24.5 代表③：`WeightRoutePredicateFactory`（唯一"外援"型）＋实验

**认知难点**：Weight 的判断依据（"本次请求该选哪条"）**不在谓词自己手里**。随机数不能放在 `test` 里掷——那会"每测一条路由都重掷"，永远对不上。正确姿势：**在更早的地方掷一次，存进 exchange，谓词只比对**。

```
WeightCalculatorWebFilter（是个 WebFilter！order=10001，比 handler mapping 更早）
   ① 维护"权重账本" groupWeights（yml / Java DSL 两路数据汇入）
   ② filter(exchange)：对每个 group 掷一次 → 选中 routeId → 存进 exchange 属性 WEIGHT_ATTR
        ↓（之后才轮到 C 组匹配）
Weight 谓词 test()：读 WEIGHT_ATTR 的答案 vs 当前 routeId 比对 → true/false
```

### 数据是怎么汇进账本的（两路，源码级）

**通道一（yml）**：`RouteDefinitionRouteLocator:230` 在绑定谓词时挂 `eventFunction`，把**归一化后的 args** 广播出去（24.1 ③ 的 `ApplicationEvent`）：

```java
.eventFunction((bound, properties) -> new PredicateArgsEvent(this, route.getId(), properties))
```

对 `Weight=gray, 8`：`shortcutFieldOrder()` = `["weight.group", "weight.weight"]`（常量 `GROUP_KEY`/`WEIGHT_KEY` 就是带前缀的）→ 归一化后 map = `{"weight.group":"gray", "weight.weight":"8"}`。
`WeightCalculatorWebFilter.handle(PredicateArgsEvent)` 检查 `key.startsWith("weight.")`，然后把这份 **已归一化** 的 map 用 `.normalizedProperties(args)`（= "别再归一化了"）绑成 `WeightConfig(routeId)` → `addWeightConfig`。

> 顺带修正一个因果：`shortcutFieldPrefix()` **不是**归一化用的——归一化靠 `shortcutFieldOrder` 的字段名；前缀在 `ConfigurationService.doBind()` 里当 **Binder 的绑定名前缀**（Weight 用 `"weight"`，这样 `weight.*` 键能绑进 Config 字段）。

**通道二（Java DSL）**：`beforeApply` 钩子：

```java
@Override
public void beforeApply(WeightConfig config) {
    if (publisher != null) {
        publisher.publishEvent(new WeightDefinedEvent(this, config));    // ★ 同一个账本入口
    }
}
```

### 账本与每请求选择（源码）

`addWeightConfig` 是 **copy-on-write**：累计权重和 → 归一化 → 生成**累计区间** `ranges=[0, w1, w1+w2, ...]` + `rangeIndexes`；整体替换（源码注释：避免 filter 执行期的并发问题）。
每请求在 `WeightCalculatorWebFilter.filter` 里掷一次：

```java
double r = randomFunction.apply(exchange);       // 默认 ThreadLocalRandom.current().nextDouble()
for (int i = 0; i < ranges.size() - 1; i++) {
    if (r >= ranges.get(i) && r < ranges.get(i + 1)) {
        String routeId = config.rangeIndexes.get(i);
        weights.put(group, routeId);   // → WEIGHT_ATTR
        break;
    }
}
```

谓词的 `test` 只剩一行比对（L23 记账在此回收）：

```java
Map<String, String> weights = exchange.getAttributeOrDefault(WEIGHT_ATTR, Collections.emptyMap());
String routeId = exchange.getAttribute(GATEWAY_PREDICATE_ROUTE_ATTR);     // ← L23 lookupRoute 每测一条就写
String chosenRoute = weights.get(config.getGroup());
return routeId.equals(chosenRoute);
```

### 现场实验：`/gray/**` 的 8:2 分流（40 次）

demo 里 `gray-v2`/`gray-v1` 两条路由：`Path=/gray/**` + `Weight=gray, 8` / `Weight=gray, 2`，各自加响应头 `X-Gray: v2` / `v1`。

```bash
for i in $(seq 1 40); do
  curl -s --noproxy '*' -D - -o /dev/null http://127.0.0.1:8080/gray/hello | grep -i '^X-Gray'
done | sort | uniq -c
#       7 X-Gray: v1
#      33 X-Gray: v2
```

同期网关日志里"赢家"统计（`Route matched: gray-v2/v1`）**同口径**：

```
      7 gray-v1
     33 gray-v2
```

33:7 ≈ 8:2（40 次的小样本波动，正常）。**随机数只掷一次、账本提前算好、谓词只比对**——这就是 Weight 的全部秘密。

**配套事件**：`RouteDeletedEvent`（actuator 删路由）→ `removeWeightConfig`；`RefreshRoutesEvent` → 强制"再编译一遍路由"（首次 `blockLast()` 启动期快速失败 gh-1574，后续 `subscribe()`）——因为权重账本只在"编译/绑定"时更新，刷新必须触发重编译。

---

## 24.6 代表④：`RemoteAddrRoutePredicateFactory`（可插拔解析器）＋实验

```java
public Predicate<ServerWebExchange> apply(Config config) {
    List<IpSubnetFilterRule> sources = convert(config.sources);          // ★ 编译期：ip/cidr → 规则对象
    return new GatewayPredicate() {
        @Override
        public boolean test(ServerWebExchange exchange) {
            InetSocketAddress remoteAddress = config.remoteAddressResolver.resolve(exchange);  // ★ 钩子
            if (remoteAddress != null && remoteAddress.getAddress() != null) {
                String hostAddress = remoteAddress.getAddress().getHostAddress();
                for (IpSubnetFilterRule source : sources) {
                    if (source.matches(remoteAddress)) { return true; }
                }
            }
            return false;
        }
        @Override
        public String toString() { return String.format("RemoteAddrs: %s", config.getSources()); }
    };
}
```

**现场实验**（临时加一条路由，白名单换一下看黑白）：

```bash
# D1：白名单 = 127.0.0.1 → 从本机访问（TCP 对端就是 127.0.0.1）→ 200
curl -s --noproxy '*' -o /dev/null -w 'POST -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/routes/tmp-l24-ra \
  -H 'Content-Type: application/json' -d '{
 "id":"tmp-l24-ra","uri":"http://127.0.0.1:8081",
 "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l24/**"}},
               {"name":"RemoteAddr","args":{"_genkey_0":"127.0.0.1"}}],
 "filters":[{"name":"StripPrefix","args":{"_genkey_0":"1"}}]}'
# POST -> 201
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/refresh
sleep 0.5                                    # refresh 异步换快照（L22 §22.1）
curl -s --noproxy '*' -i http://127.0.0.1:8080/tmp-l24/hello
# HTTP/1.1 200 OK
# transfer-encoding: chunked
# X-Demo-WebFilter: hit-147
# Vary: Origin
# Vary: Access-Control-Request-Method
# Vary: Access-Control-Request-Headers
# Content-Type: application/json
# Date: Fri, 02 Oct 2026 04:02:28 GMT
#
# {"port":8081,"method":"GET","uri":"/hello","query":null,"headers":{"user-agent":"curl/8.5.0","accept":"*/*","x-demo-trace-id":"bf7585f910504c9a9bd7def560c2053d","x-demo-from-gateway":"true","forwarded":"proto=http;host=\"127.0.0.1:8080\";for=\"127.0.0.1:50424\"","x-forwarded-for":"127.0.0.1","x-forwarded-proto":"http","x-forwarded-prefix":"/tmp-l24","x-forwarded-port":"8080","x-forwarded-host":"127.0.0.1:8080","host":"127.0.0.1:8081","content-length":"0"},"greeting":"hello world"}

# D2：把白名单换成 10.0.0.0/8（重新 POST 同 id 覆盖）→ 本机不在网段 → 404
# （同一条命令，只把 "127.0.0.1" 换成 "10.0.0.0/8"）
curl -s --noproxy '*' -o /dev/null -w 'POST -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/routes/tmp-l24-ra \
  -H 'Content-Type: application/json' -d '{
 "id":"tmp-l24-ra","uri":"http://127.0.0.1:8081",
 "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l24/**"}},
               {"name":"RemoteAddr","args":{"_genkey_0":"10.0.0.0/8"}}],
 "filters":[{"name":"StripPrefix","args":{"_genkey_0":"1"}}]}'
# POST -> 201
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/refresh
sleep 0.5
curl -s --noproxy '*' -i http://127.0.0.1:8080/tmp-l24/hello
# HTTP/1.1 404 Not Found
# X-Demo-WebFilter: hit-150
# Vary: Origin
# Vary: Access-Control-Request-Method
# Vary: Access-Control-Request-Headers
# Content-Type: application/json
# Content-Length: 129
#
# {"timestamp":"2026-10-02T04:02:29.070+00:00","path":"/tmp-l24/hello","status":404,"error":"Not Found","requestId":"26162696-150"}

# 清理
curl -s --noproxy '*' -o /dev/null -w 'delete -> %{http_code}\n' -X DELETE http://127.0.0.1:8080/actuator/gateway/routes/tmp-l24-ra
curl -s --noproxy '*' -o /dev/null -w 'refresh -> %{http_code}\n' -X POST http://127.0.0.1:8080/actuator/gateway/refresh
# delete -> 200
# refresh -> 200
```

**关键设计**：客户端地址从哪来 → 交给 `RemoteAddressResolver`（Config 字段、可替换）：

```java
public interface RemoteAddressResolver {
    default InetSocketAddress resolve(ServerWebExchange exchange) {
        return exchange.getRequest().getRemoteAddress();                 // 默认：TCP 对端地址
    }
}
// 替换实现：XForwardedRemoteAddressResolver——取 X-Forwarded-For，从右往左"信任 n 跳"
```

> ⚠️ **安全红线**：XFF 头**客户端可伪造**。`trustAll()` 用在"反作弊/风控/IP 白名单"上等于把门钥匙交出去——**L31 展开**（真实项目 [修复C3] 就栽在这）。
> 想直接用现成的：家族里有 **`XForwardedRemoteAddr`** 谓词，源码上就是 `RemoteAddr` + `XForwardedRemoteAddressResolver.maxTrustedIndex(1)`（**默认信任最右一跳**），yml 可直接写。

---

## 24.7 "会一个就会全部"：真要写一个时抄谁

| 你要做的 | 抄哪个代表 | 换掉的点 |
|---|---|---|
| 匹配"某个字段" | `Method` | Config 字段 + test 的取数 |
| 匹配"pattern"（带编译加工） | `Path` | 加工物（PathPattern → 你的 Pattern 类型） |
| 匹配"网络/时间" | `RemoteAddr` | 加工物 + 解析钩子 |
| "外部决定的匹配"（如灰度/权重） | `Weight` | 数据源（事件通道 + 每请求预计算） |
| 真异步谓词 | `ReadBody` | 重写 `applyAsync` 返回异步 Mono（错误不走 L23 的 try/catch） |
| 自己写一个 | L15 的 `demoHeader` | 三件套 + `@Component`（自动进家族=自动可写进 yml，L17 §17.5） |

---

## 24.8 怎么自己验证

- **实验索引**：24.3（Method 200/404）、24.5（Weight 8:2）、24.6（RemoteAddr 黑白名单）；yml 存 `_genkey` 的现场在 24.1 ①。
- **断点**：
  - `MethodRoutePredicateFactory#apply` 里的 `test`（L24 最简入口）→ 再看 `PathRoutePredicateFactory#apply` 的两次 parse
  - `WeightCalculatorWebFilter#filter` —— 看 `WEIGHT_ATTR` 被写入的那一刻（每个请求只掷一次）
  - `ShortcutConfigurable.normalizeKey` —— 看 `_genkey_0` 变成字段名（一次绑定时很值钱）
- **练习**：按 L15 三段式写一个自己的谓词工厂（`@Component`）→ 直接写进 yml → 从 `/routedefinitions` 里看到它（和内置谓词**完全平权**）。

---

## 24.9 常见理解偏差澄清（复习先读）

> ① **"家族"是 bean 家族**：yml 里能写哪些谓词，取决于装配了哪些 `RoutePredicateFactory` bean（内置 14 + 你的自定义，L17 §17.5）；自定义 = 加个 `@Component`（L15）。
> ② **`apply` 是"编译期"、`test` 是"运行期"**：贵的加工（parse 路径、转 CIDR）在 `apply` 里每路由一次；`test` 每请求都跑但只做快判断。别把"贵"算到 test 上。
> ③ **shortcut 不是"语法糖魔法"**：就是 `_genkey_N → shortcutFieldOrder()[N]` 的机械映射（24.1 源码）；`ShortcutType` 只决定"多个值怎么搬"。
> ④ **Weight 的特殊在"数据来源"，不在"匹配"**：它自己只做 `lookup + equals`；随机选择发生在更早的 WebFilter（24.5）。
> ⑤ **谓词只决定"选不选"**，不执行过滤器；过滤器是命中之后 D 组的事（L23 §23.8 / L25）。

---

## 24.10 掌握边界（值不值得深挖）

> **本课定位：了解型**。读一遍知道"家族长什么样、为什么 Weight 特殊"即可；**逐个源码、shortcut 边界细节都不需要背**——真要写自定义谓词时，回看 §24.1 / §24.7 + L15 现查。

| 内容 | 口径 |
|---|---|
| **三件套 + `apply`(编译期) vs `test`(运行期)** | ✅ **要会**（唯一 🔴：解释"谓词为什么能便宜地每请求跑"） |
| **Weight 特殊在"数据来源"**（随机选择在更早的 WebFilter） | ✅ 要会（灰度/AB 的原理底子；L36/L37 会回来） |
| "家族 = bean 家族"（yml 能写什么取决于装配了哪些工厂） | ⚪ 了解（L17 §17.5 / L15） |
| shortcut 规则（`_genkey_i` → `shortcutFieldOrder[i]`） | ⚪ 了解；**边界（TAIL_FLAG 等）翻篇**，写工厂时看 L15 |
| 四代表的加工物差异（PathPattern / CIDR / 外部数据） | ⚪ 读 §24.2 的表即可，不背 |
| 14 个内置工厂逐个逐行 / `DecoratingProxy` | ❌ 翻篇，用时查源码 |
| XFF resolver 的信任跳数细节 | ❌ 翻篇（L31 专讲安全） |
| `CloudFoundryRouteService` | ❌ 一句话翻篇 |

---

## 24.11 Self-Test

1. 谓词工厂的"三件套"是什么？各自在什么时机被用？
2. `_genkey_0` 变成字段名的那一行源码在哪（函数名）？`GATHER_LIST_TAIL_FLAG` 在什么情况下才把最后一个值当 flag？
3. 24.3 实验里 POST `/cors-lab2/hello` 为什么是 404？（说清是哪个谓词否了它）
4. Path 谓词里的两次 parse 分别在哪、各执行多少次？
5. Weight 的随机数在哪掷？为什么不能放 `test` 里掷？`WEIGHT_ATTR` 是谁写的？
6. Weight 的两路数据通道怎么汇进同一本账？`shortcutFieldPrefix()` 在绑定里扮演什么角色？
7. RemoteAddr 的 `RemoteAddressResolver` 换成 `XForwardedRemoteAddressResolver.trustAll()` 有什么安全含义？现成的 `XForwardedRemoteAddr` 默认信任几跳？

---

## 24.12 Open Questions

- [ ] `WeightCalculatorWebFilter` 在 `RefreshRoutesEvent` 上的分支（首次 `blockLast()` vs 后续 `subscribe()`）—— gh-1574 场景未实测；与 L30 的刷新故事可并线验证。
- [ ] `XForwardedRemoteAddressResolver` 丢弃多 XFF 头的设计取舍 —— 与 L31 的防御策略一起看。
- [ ] `GATHER_LIST_TAIL_FLAG` 的 flag 只能放"尾部"（名字/源码断言还写着 `GATHER_LIST_HEAD`）—— 历史命名的原因未考证。
