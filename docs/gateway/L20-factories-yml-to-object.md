# Lesson 20: Factories: yml → Object（工厂机制）— 一行 yml 怎么变成"活的"对象

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 20（★v5.4 新增：补"工厂机制无专课"）
> 前置：**L17 17.3.5**（"查工厂 + bind + apply"首过）· **L14/L15**（亲手写过两个工厂）· L06（yml 绑定体系）· L19（定义的来源）
> 口径：把"**查工厂 → bind → apply**"从"提过"升级为**完整专课**；与 17.3.5 重叠处只回指、不重复

---

## 20.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **绑定链 5 步** | 短路语法 → `_genkey_0` 占位 → `shortcutFieldOrder()` 替换 → `ShortcutType` 归一化 → `Binder` 绑定 |
| **②** | **最后一跳** | `factory.apply(config)`（过滤器）/ `applyAsync(config)`（谓词）—— **L14/L15 三段式的源头** |
| **③** | **接口族** | `RoutePredicateFactory` / `GatewayFilterFactory`（都 = `ShortcutConfigurable` + `Configurable`）+ 两个 Abstract 基类 + `NameUtils` 命名 |

> **一句话**：这一课回答的是 —— **"yml 里那行字符串，是怎么变成内存里一个能干活的对象的？"**

---

## 20.1 例子先行：`- Path=/foo/**` 的完整旅程

一条 yml：

```yaml
predicates:
  - Path=/foo/**
```

从这行到内存里一个 `AsyncPredicate`，一共 **5 大步**（每步下面各有一节）：

```
【A. 配置绑定期】（Spring Boot 干）
yml 行  ──绑定──►  PredicateDefinition{ name="Path", args={ "_genkey_0": "/foo/**" } }   ← §20.4
                        │
【B. 路由编译期】（B 组 RouteDefinitionRouteLocator.lookup 干）
                        ▼
① 按名字查工厂：  predicates.get("Path") → PathRoutePredicateFactory               ← §20.3 / 19.6
                        │
② configurationService.with(factory).name("Path").properties(args).bind()          ← §20.6
     ②a 归一化：GATHER_LIST_TAIL_FLAG → { "patterns": ["/foo/**"] }                ← §20.5
     ②b Binder 绑定：{"patterns": ["/foo/**"]} → PathConfig{patterns=[...]}
     ②c 发 Args 事件（观测/联动，17.3.5③）
                        │
③ 最后一跳：factory.applyAsync(config) → AsyncPredicate                            ← §20.7
                        │
                        ▼
              Route.predicate = 这个 AsyncPredicate（被 C 组每请求调用）
```

> 记住这条"**A 绑定 → B 编译**"的分界线：`PredicateDefinition`（字符串地图）是 **A 期产物**；工厂/bind/apply 是 **B 期动作**。

**⚠️ 别把两个 "lookup" 混了**（一个**编译期**、一个**运行期**，而且查的东西也不同）：

| 方法 | 谁的 | 何时 | **查什么** | 输入 → 输出 |
|---|---|---|---|---|
| `RouteDefinitionRouteLocator.lookup(routeDefinition, predicateDefinition)` | **B 组** | **编译期**（启动时 + **每次 refresh**） | **查工厂**：`predicates.get("Path")` | 定义 → `AsyncPredicate`（**编译产物**，之后固定） |
| `RoutePredicateHandlerMapping.lookupRoute(exchange)` | **C 组** | **运行期**（**每请求**） | **查路由**：逐条跑**已编译的** `predicate.apply(exchange)` | 请求 → `Mono<Route>` |

- **编译期**：把"名字+参数"变成对象（工厂 lookup 发生在这一步）—— 产出缓存在 `CachingRouteLocator`
- **运行期**：拿**现成的对象**去匹配请求（`lookupRoute` 发生在这一步）—— 每请求都跑，**不再查工厂**


---

## 20.2 接口族：谁是谁

**先弄清：这篇文章里的 "Config" 是什么？**

**它是一个普通类 —— "这个工厂能配哪些参数"的载体**（字段 = 参数，字段默认值 = 默认参数）：

```java
// 谓词例子：PathRoutePredicateFactory 的内部类
public static class Config {
    private List<String> patterns = new ArrayList<>();
    private boolean matchTrailingSlash = true;      // ← 默认值
}

// 过滤器例子：StripPrefixGatewayFilterFactory 的内部类
public static class Config {
    private int parts = 1;                          // ← 默认值
}
```

yml ↔ Config 的对应（经 bind 填充后）：

| yml | 绑出的 Config |
|---|---|
| `- Path=/foo/**, true` | `Config{patterns=["/foo/**"], matchTrailingSlash=true}` |
| `- StripPrefix=1` | `Config{parts=1}` |

**它的生命周期**（和 20.1 的 5 步链对位）：

```
工厂类里声明（多为静态内部类）
   → newConfig() 反射实例化（AbstractConfigurable）
   → bind() 把 yml 值填进字段（§20.5~20.6 —— 本课核心动作）
   → apply(config) 读字段，构造 Predicate / GatewayFilter（§20.7）
```

**⚠️ 别和 Spring 里其它"Config"混**：

| 名字 | 是什么 | 本课？ |
|---|---|---|
| `@Configuration` | Spring 配置类（`@Bean` 的家） | ❌ 完全无关 |
| `@ConfigurationProperties` | 把 yml 前缀绑成属性 bean（如 `GatewayProperties`） | ❌ 不是它（但底层同是 Binder 机制） |
| **工厂的 `Config` 内部类**（`PathRoutePredicateFactory.Config`） | **本课主角**：一个谓词/过滤器条目的参数载体 | ✅ |
| `Configurable<C>` / `getConfigClass()` | 工厂声明"我的 Config 类是哪个" | ✅ |

**两种摆放方式**（设计观察）：
- **内部类**（大多数：Path / StripPrefix）：只被自己工厂用
- **提取成公共类**：当**不止工厂要用**时 —— 最典型 `WeightConfig`（放 `support` 包：`WeightCalculatorWebFilter`/事件也要用它携带 `group/weight/routeId`，见 19.x）

> 回扣三段式：**L14/L15 你写的那个 `Config` 内部类，就是这里的 Config** —— `shortcutFieldOrder()` 里的字段名必须与 Config 字段对上（它俩就是 bind 的"键↔字段"对照表）。

---

两个接口长得几乎一样（一个管谓词、一个管过滤器）：

```java
// 谓词工厂（handler/predicate 包）
@FunctionalInterface
public interface RoutePredicateFactory<C> extends ShortcutConfigurable, Configurable<C> {
    Predicate<ServerWebExchange> apply(C config);                       // ★ 最后一跳（子类实现）
    default AsyncPredicate<ServerWebExchange> applyAsync(C config) {    // 默认：同步谓词转异步
        return toAsyncPredicate(apply(config));
    }
    default String name() {                                             // ★ 名字 = 类的函数
        return NameUtils.normalizeRoutePredicateName(getClass());
    }
    // 另有 apply(Consumer<C>) / applyAsync(Consumer<C>)：先 newConfig + consumer + beforeApply
}

// 过滤器工厂（filter/factory 包）
@FunctionalInterface
public interface GatewayFilterFactory<C> extends ShortcutConfigurable, Configurable<C> {
    GatewayFilter apply(C config);                                      // ★ 最后一跳
    default GatewayFilter apply(Consumer<C> consumer) { C config = newConfig(); consumer.accept(config); return apply(config); }
    default String name() {
        return NameUtils.normalizeFilterFactoryName(getClass());
    }
}
```

两个基座接口（组合进来了）：

```java
public interface Configurable<C> {
    Class<C> getConfigClass();     // 我的 Config 类是哪个
    C newConfig();                 // 造一个空 Config
}
public abstract class AbstractConfigurable<C> implements Configurable<C> {
    private Class<C> configClass;
    protected AbstractConfigurable(Class<C> configClass) { this.configClass = configClass; }
    @Override public C newConfig() { return BeanUtils.instantiateClass(this.configClass); }   // 反射 new
}
```

两个 Abstract 基类（你在 L14/L15 继承的就是它们）：

```java
public abstract class AbstractRoutePredicateFactory<C>
        extends AbstractConfigurable<C> implements RoutePredicateFactory<C> {
    public AbstractRoutePredicateFactory(Class<C> configClass) { super(configClass); }
}

public abstract class AbstractGatewayFilterFactory<C>
        extends AbstractConfigurable<C> implements GatewayFilterFactory<C>, ApplicationEventPublisherAware {
    public AbstractGatewayFilterFactory(Class<C> configClass) { super(configClass); }
    protected void enableBodyCaching(String routeId) { ... }   // 需要读 body 的过滤器用它（ReadBody 联动）
    ...
}
```

类图一眼：

```
ShortcutConfigurable ──┐
                       ├──► RoutePredicateFactory<C> ──► AbstractRoutePredicateFactory<C> ──► PathRoutePredicateFactory…
Configurable<C> ───────┤
                       └──► GatewayFilterFactory<C>  ──► AbstractGatewayFilterFactory<C>  ──► StripPrefixGatewayFilterFactory…
```

---

## 20.3 第①步：名字 —— `Path` 这个名字怎么来的（`NameUtils`）

yml 里写的是 `Path`，工厂类叫 `PathRoutePredicateFactory` —— 中间的翻译就是 `name()` 的默认实现：

```java
// NameUtils（support 包）
public static final String GENERATED_NAME_PREFIX = "_genkey_";

public static String normalizeRoutePredicateName(Class<? extends RoutePredicateFactory> clazz) {
    return removeGarbage(clazz.getSimpleName()
            .replace(RoutePredicateFactory.class.getSimpleName(), ""));   // 剥掉 "RoutePredicateFactory"
}
public static String normalizeFilterFactoryName(Class<? extends GatewayFilterFactory> clazz) {
    return removeGarbage(clazz.getSimpleName()
            .replace(GatewayFilterFactory.class.getSimpleName(), ""));    // 剥掉 "GatewayFilterFactory"
}
public static String generateName(int i) { return GENERATED_NAME_PREFIX + i; }   // _genkey_0 / _genkey_1 …
```

三个要点：

1. **命名规则 = "类名去掉后缀"** —— 所以 `PathRoutePredicateFactory → Path`、`StripPrefixGatewayFilterFactory → StripPrefix`、**你 L15 的 `DemoHeaderRoutePredicateFactory → DemoHeader`**（自动获得 yml 名字，不用注册任何映射）
2. 还有 **property 变体**（kebab-case）：`normalizeToCanonicalPropertyFormat` → `AddRequestHeader → add-request-header`（用于 `@ConfigurationProperties`/元数据，L06 风格的 key）
3. 与 19.6 的呼应：B 组的 `predicates` map 就是 **key = name()** 建的 —— 所以"yml 能写哪些名字"= "装了哪些工厂 bean"（17.5 的 15/33 个）

---

## 20.4 第②步：占位 —— `_genkey_0` 哪来的（`PredicateDefinition`）

**短路语法**（`- Path=/foo/**`）的解析发生在**配置绑定期**，代码就一个构造器：

```java
// PredicateDefinition（FilterDefinition 同款逻辑）
public PredicateDefinition(String text) {
    int eqIdx = text.indexOf('=');
    if (eqIdx <= 0) {
        throw new ValidationException("Unable to parse ... must be of the form name=value");   // 没有 '=' 直接报错
    }
    setName(text.substring(0, eqIdx));                              // name = "Path"
    String[] args = tokenizeToStringArray(text.substring(eqIdx + 1), ",");   // 逗号切多值
    for (int i = 0; i < args.length; i++) {
        this.args.put(NameUtils.generateName(i), args[i]);          // ★ _genkey_0 = "/foo/**"
    }
}
```

所以「`- Path=/foo/**`」绑出来的就是：

```java
PredicateDefinition{ name="Path", args={ "_genkey_0": "/foo/**" } }
```

**两种写法对照**（这就是"短路"与"完整"的区别）：

| yml 写法 | 绑出的 args | 谁负责对齐 |
|---|---|---|
| `- Path=/foo/**`（**短路**） | `{_genkey_0: "/foo/**"}` —— **只有值，没有键** | 工厂的 `shortcutFieldOrder()`（下一步） |
| `- name: Path` + `args: {patterns: /foo/**}` | `{patterns: "/foo/**"}` —— 键值齐全 | 不需要对齐 |

**为什么要占位**：短路语法只给值；**"第几个值对应哪个字段"这个知识只有工厂知道** —— 所以先用序号占位，等到了工厂那一跳再替换（下一步）。

---

## 20.5 第③④步：替换 + 归一化（`ShortcutConfigurable` 全解）

上一节的 `{_genkey_0: "/foo/**"}` 到这一步变成 `{patterns: ["/foo/**"]}`，靠两样东西：**`shortcutFieldOrder()`**（告诉框架字段顺序）+ **`ShortcutType`**（收值策略）。

### 20.5.1 替换：`normalizeKey`

```java
static String normalizeKey(String key, int entryIdx, ShortcutConfigurable argHints, Map<String, String> args) {
    if (key.startsWith(NameUtils.GENERATED_NAME_PREFIX)          // _genkey_ 开头
            && !argHints.shortcutFieldOrder().isEmpty()
            && entryIdx < args.size() && entryIdx < argHints.shortcutFieldOrder().size()) {
        key = argHints.shortcutFieldOrder().get(entryIdx);       // ★ 按序号换成真实字段名
    }
    return key;
}
```

所以工厂必须写 `shortcutFieldOrder()`（L15 强调过"必写"）—— 它就是**"第 N 个短路值 = 哪个字段"的对照表**。

### 20.5.2 三种收值策略：`ShortcutType`

```java
enum ShortcutType {
    DEFAULT                 // 逐个：_genkey_0 → 字段0、_genkey_1 → 字段1…（一对一）
    GATHER_LIST             // 收成一个 List 字段（shortcutFieldOrder 长度必须 = 1）
    GATHER_LIST_TAIL_FLAG   // 收成 List + "尾巴上的 true/false"单独当标志位（长度必须 = 2）
}
```

| 策略 | 语义 | 内置例子 |
|---|---|---|
| `DEFAULT` | 值 ↔ 字段 一对一 | 大多数（如 `- StripPrefix=1`） |
| `GATHER_LIST` | 所有值收进**一个 List 字段** | `AddRequestHeadersIfNotPresent`（`[keyValues]`）；`Host` / `Method` 等 |
| **`GATHER_LIST_TAIL_FLAG`** | 收 List + **末尾 true/false 当标志** | **`Path`**（`[patterns, matchTrailingSlash]`） |

**拿 `Path` 走一遍**（这就是大家最熟的那个谓词，也是刚才的错例纠正）：

```java
// PathRoutePredicateFactory
@Override public List<String> shortcutFieldOrder() {
    return Arrays.asList("patterns", MATCH_TRAILING_SLASH /* "matchTrailingSlash" */);
}
@Override public ShortcutType shortcutType() { return ShortcutType.GATHER_LIST_TAIL_FLAG; }
```

| yml | 归一化结果 |
|---|---|
| `- Path=/foo/**` | `{patterns: ["/foo/**"]}`（末尾不是 true/false → 全当 pattern） |
| `- Path=/foo/**, true` | `{patterns: ["/foo/**"], matchTrailingSlash: true}`（末尾是标志位） |

（还记得 18.7 实测日志里的 `predicate=Paths: [/provider/**], match trailing slash: true` 吗？`match trailing slash: true` 就是这里 `matchTrailingSlash` 的默认值。）

### 20.5.3 值侧：SpEL

`getValue` 让**每个值**都支持 SpEL：

```java
static Object getValue(SpelExpressionParser parser, BeanFactory beanFactory, String entryValue) {
    ...
    if (rawValue.startsWith("#{") && entryValue.endsWith("}")) {     // 形如 #{...} 才当 SpEL
        GatewayEvaluationContext context = new GatewayEvaluationContext(beanFactory);
        return parser.parseExpression(entryValue, new TemplateParserContext()).getValue(context);
    }
    return entryValue;                                              // 普通字符串原样
}
```

（`GatewayEvaluationContext` 默认带**限制性属性访问器** `spring.cloud.gateway.restrictive-property-accessor.enabled=true` —— 防 SpEL 从 Bean 乱读属性；还有 `shortcutFieldPrefix()`：给绑定名加前缀，默认空串，**`Weight` 用了 `"weight"`**——对应 `WeightConfig.CONFIG_PREFIX`。）

---

## 20.6 第⑤步：绑定 —— `{patterns: [...]}` 变成 `PathConfig`

这一跳的完整机制（`ConfigurationService.with(factory)...bind()` 的三步：**normalize → Binder → 事件**）在 **17.3.5 ③** 已经讲过：见《`configurationService`：把"name + args"变成 Config 对象》。

本节只补三个"上次没展开"的点：

1. **Binder 的"名字"从哪来**：`bindOrCreate(bindable, normalizedProperties, configurable.shortcutFieldPrefix(), validator, conversionService)` —— 用 `shortcutFieldPrefix()` 当绑定名（默认 `""`，`Weight` 是 `"weight"`）
2. **校验自动生效**：Config 类上的 JSR-303（如 `WeightConfig` 的 `@NotEmpty group` / `@Min(0) weight`）由 `ValidationBindHandler` 在绑定时就校验 —— yml 写错直接报错，不会等到运行期
3. **类型转换**：`{"patterns": ["/foo/**"]}` 里是字符串，绑成 `PathConfig.patterns`（`List<String>`）时由 `Binder` + `ConversionService` 处理（`webFluxConversionService`，17.3.5 ③ 的 bean 参数）

---

## 20.7 最后一跳：`apply(config)` / `applyAsync(config)`（回扣 L14/L15）

config 到手后，就是工厂自己的事了：

```java
// 谓词：默认把"同步谓词"转成异步谓词
default AsyncPredicate<ServerWebExchange> applyAsync(C config) {
    return toAsyncPredicate(apply(config));     // toAsyncPredicate = ServerWebExchangeUtils 的工具
}

// 过滤器：直接产出 GatewayFilter
GatewayFilter apply(C config);
```

三个"周边入口"（写自定义工厂时会用到）：

| 入口 | 场景 | 关键行为 |
|---|---|---|
| `apply(Consumer<C>)` / `applyAsync(Consumer<C>)` | **Java DSL**（`.path(...)` 这种） | `newConfig() → consumer → beforeApply(config) → apply` |
| `beforeApply(C config)` | 需要"apply 之前的钩子" | 默认空；**`Weight` 用它发 `WeightDefinedEvent`**（19.x 的记账！） |
| `apply(String routeId, C config)` | 需要知道路由 id | 若 config ∈ `HasRouteId` → `setRouteId(routeId)`（L14 的三段式之一） |

**与 L14/L15 三段式对上**（你亲手写过的两个工厂，就是这条链的"填空"）：

| L14/L15 写的 | 在这条链里的位置 |
|---|---|
| 类名 `XxxGatewayFilterFactory` | §20.3 的 `name()`（yml 名字） |
| 内部类 `Config` + 字段 | §20.4~20.6 的绑定目标（含 `@Validated` 校验） |
| `shortcutFieldOrder()` | §20.5 的"值↔字段"对照表 |
| `apply(Config)` | §20.7 的最后一跳 |

---

## 20.8 怎么自己验证

```bash
# ① 看"名字 ↔ 工厂"对照（yml 能写哪些名）
curl -s --noproxy '*' http://localhost:8080/actuator/gateway/routepredicates | python3 -m json.tool   # 15 个谓词
curl -s --noproxy '*' http://localhost:8080/actuator/gateway/routefilters    | python3 -m json.tool   # 33 个过滤器

# ② 看某条路由"编译后"的 args（= bind 的输入侧）
curl -s --noproxy '*' http://localhost:8080/actuator/gateway/routedefinitions | python3 -m json.tool
```

断点/Logpoint 建议（配合 18.7 的调试方式）：
- `RouteDefinitionRouteLocator#lookup` —— 看 `predicates.get(name)` 查到哪个工厂、args 原样长什么样
- `ConfigurableBuilder#normalizeProperties` —— 看 `_genkey_0 → patterns` 的替换瞬间
- `PathRoutePredicateFactory#apply`（或你自定义工厂的 `apply`）—— 看最后一跳的 config 值

（L14/L15 的实验本身就已经"间接实测"了这条链：你写的 `DemoHeader` 工厂能被 yml 识别、能 bind 到 config、能在链里生效 —— 那就是这 5 步在跑。）

---

## 20.9 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| 各内置工厂的 shortcut 细节（谁用哪种 ShortcutType） | ❌ 用到再查（Path 的 `TAIL_FLAG` 是本课例子，够了） |
| SpEL 的安全细节（RestrictivePropertyAccessor 实现） | ❌ 知道"默认限制、可关"即可 |
| **5 步绑定链（顺序 + 每步在哪个类）** | ✅ **要会**（面试"yml 怎么变对象"） |
| **两个 `name()` 默认实现 + `_genkey_` 规则** | ✅ 要会（自定义工厂为什么"自动可用"） |
| **三段式（类名/Config/apply）与链路的对应** | ✅ 要会（L14/L15 的"为什么这样写"） |

---

## 20.10 Self-Test

1. `- Path=/foo/**` 从 yml 到 `AsyncPredicate`，按顺序说出 **5 步**（每步一个类/方法）。
2. `Path` 这个名字和 `_genkey_0` 这个 key 分别是谁、在什么时候生成的？
3. `shortcutFieldOrder()` 不写会怎样？三种 `ShortcutType` 各是什么语义、各举一个内置例子。
4. `applyAsync` 的默认实现是什么？谓词工厂真正强制要实现的哪个方法？
5. （三段式回扣）L14/L15 自定义工厂写的 `Config` / `shortcutFieldOrder` / `apply` 分别落在链路哪一步？
6. `Weight` 工厂的两个特殊点在哪？（`shortcutFieldPrefix="weight"` 与 `beforeApply` 发事件）

---

## 20.11 Open Questions

- [ ] 用一个 **Logpoint** 完整录一次 `normalizeProperties` 的输入/输出（选 `Path=/a/**, true` 验证 TAIL_FLAG 的两分支）—— 未实测。
- [ ] `GATHER_LIST` 的 `AddRequestHeadersIfNotPresent`（`keyValues`）与 `Path` 的 `TAIL_FLAG` 行为差异实跑对比 —— 未实测。
- [ ] `GatewayEvaluationContext` 限制性开关关掉（`restrictive-property-accessor.enabled=false`）后的 SpEL 能力边界 —— 未实测。
- [ ] L15 的 `DemoHeaderRoutePredicateFactory` 实际用的哪种 ShortcutType / fieldOrder？（可直接看源码，留给复习时核对）
