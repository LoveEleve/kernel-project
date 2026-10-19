# D6: MeterFilter——DENY/NEUTRAL/ACCEPT 三元过滤链

> **阅读顺序**: P1 重要——理解 Micrometer 如何通过 Filter 链实现指标级准入控制
> **源码版本**: micrometer-core **v1.12.5**（tag；运行时对齐 Boot 3.2.5，与 xhs-project 一致；1.13+ 新特性以注记标出）
> **核心文件**:
> - `MeterFilter.java` (445 行)——过滤接口 + 静态工厂方法族
> - `MeterFilterReply.java` (22 行)——DENY/NEUTRAL/ACCEPT 三元枚举

---

## 一、MeterFilter 的三层模型——不止 accept

```java
// MeterFilter.java
public interface MeterFilter {
    default MeterFilterReply accept(Meter.Id id) {
        return MeterFilterReply.NEUTRAL;                          // ← L421: 默认放行
    }
    default Meter.Id map(Meter.Id id) {
        return id;                                                // ← L429: 默认不改
    }
    default DistributionStatisticConfig configure(Meter.Id id,
            DistributionStatisticConfig config) {
        return config;                                            // ← L441: 默认不改
    }
}
```

**三个方法对应三个执行阶段**（参见 D1 §三 registerMeterIfNecessary）：

```
Meter 创建流程                     MeterFilter 阶段
─────────────────               ────────────────
originalId (用户传入)
    │
    ├── accept(originalId)      ← 阶段一：准入判定（DENY/NEUTRAL/ACCEPT）
    │
    ├── map(originalId)         ← 阶段二：Id 转换（改名/重命名 tag/加全局 tag）
    │       └→ mappedId
    │
    └── configure(mappedId, config)  ← 阶段三：直方图配置修改
            └→ 修改后的 config        （仅 Timer/DistributionSummary/LongTaskTimer）
```

**为什么需要三个独立方法而不是一个 `apply(id, config)`？**

因为三个阶段的职责完全不同——准入判定是"要不要"（副作用为零——只是 boolean 决策），Id 转换是"叫什么"（纯函数——old Id → new Id），直方图配置是"怎么统计"（需要 DistributionStatisticConfig 上下文——只有 Timer/Summary 有）。强耦合在一起 → 每个 Filter 实现需要理解三个阶段的关系 → 违反单一职责原则。

---

## 二、MeterFilterReply——为什么是三元而非布尔

```java
// MeterFilterReply.java
public enum MeterFilterReply {
    DENY,      // ← 立即拒绝——不再检查后续 Filter
    NEUTRAL,   // ← 不表态——继续检查下一个 Filter
    ACCEPT     // ← 立即接受——"提前放行"，不检查后续 Filter
}
```

**二元（true/false）vs 三元（DENY/NEUTRAL/ACCEPT）的区别**：

| 场景 | 二元（true=deny） | 三元（DENY/NEUTRAL/ACCEPT） |
|------|:--:|:--:|
| 无条件拒绝所有 Tomcat 指标 | `return true` → deny | `return DENY` → deny |
| 只保留 http 前缀的指标 | 不可表达——需要"拒绝非 http"的否定逻辑 | `return name.startsWith("http") ? ACCEPT : DENY` |
| 添加全局 tag（不影响准入） | `return false` → 不影响准入 | `return NEUTRAL` → 下个 Filter 决定 |
| 某 Filter 想"提前放行"一个关键指标 | 不可表达——true/false 只能拒绝/不表态 | `return ACCEPT` → 立即接受，跳过后续所有 Filter |

**真三元的好处——"提前放行"和"提前拒绝"都支持**。在 `accept()` 方法（MeterRegistry.java L657-L670）中：

```java
for (MeterFilter filter : filters) {
    MeterFilterReply reply = filter.accept(id);
    if (reply == DENY)   return false;   // ← 提前拒绝
    if (reply == ACCEPT) return true;    // ← 提前放行
}
return true;  // ← 全部 NEUTRAL → 默认接受
```

**陷阱**：`ACCEPT` 是"提前放行"——后续 Filter 不再检查。这意味着一个宽松的 Filter 排在前面可以"拯救"一个原本会被后面 DENY 拒绝的指标。**MeterFilter 的顺序影响结果**——不是 idempotent 的。

---

## 三、准入判定——deny/accept/denyUnless/maximumAllowable 家族

### 3.1 deny + accept——条件准入

```java
// MeterFilter.java L147-L184
static MeterFilter denyUnless(Predicate<Meter.Id> iff) {        // ← 白名单
    return id -> iff.test(id) ? NEUTRAL : DENY;
}
static MeterFilter deny(Predicate<Meter.Id> iff) {               // ← 黑名单
    return id -> iff.test(id) ? DENY : NEUTRAL;
}
static MeterFilter accept(Predicate<Meter.Id> iff) {             // ← 提前放行
    return id -> iff.test(id) ? ACCEPT : NEUTRAL;
}
```

**组合使用**：

```java
registry.config()
    .meterFilter(MeterFilter.deny(id -> id.getName().startsWith("jvm")))       // ← 拒绝所有 JVM 指标
    .meterFilter(MeterFilter.accept(id -> id.getName().startsWith("order")))    // ← 但 order 前缀的提前放行
    .meterFilter(MeterFilter.denyUnless(id -> id.getTag("env").equals("prod")));// ← 其它只保留 prod

// 执行链（DENY→ACCEPT→NEUTRAL）：
// 1. "jvm.memory.used" → DENY → 拒绝（不会到第 2 个 Filter）
// 2. "order.created" → NEUTRAL（不以 jvm 开头）→ 第 2 个 Filter → ACCEPT（以 order 开头）→ 接受
// 3. "http.requests" → NEUTRAL → NEUTRAL → 第 3 个 Filter → env=staging → DENY → 拒绝
```

### 3.2 maximumAllowableMetrics——全局 time series 上限

```java
// MeterFilter.java L217-L229
static MeterFilter maximumAllowableMetrics(int maximumTimeSeries) {
    return new MeterFilter() {
        private final Set<Meter.Id> ids = ConcurrentHashMap.newKeySet();

        @Override
        public MeterFilterReply accept(Meter.Id id) {
            if (ids.size() > maximumTimeSeries)
                return DENY;                                    // ← 已达上限 → 拒绝

            ids.add(id);
            return ids.size() > maximumTimeSeries ? DENY : NEUTRAL;
        }
    };
}
```

**设计意图**：在按 time series 数量收费的监控系统中（如 Datadog、New Relic），这是**成本控制**的最后一道防线——无论业务如何创建指标，总数不超过 N。但注意：它**不区分关键 vs 非关键指标**——超出上限后所有新 Meter 全被拒绝。配合 `denyNameStartsWith` 优先级前置可以保护关键指标。

### 3.3 maximumAllowableTags——Tag 基数爆炸的防御

```java
// MeterFilter.java L241-L277
static MeterFilter maximumAllowableTags(String meterNamePrefix, String tagKey,
        int maximumTagValues, MeterFilter onMaxReached) {
    return new MeterFilter() {
        private final Set<String> observedTagValues = ConcurrentHashMap.newKeySet();

        @Override
        public MeterFilterReply accept(Meter.Id id) {
            String value = matchNameAndGetTagValue(id);
            if (value != null && !observedTagValues.contains(value)) {
                if (observedTagValues.size() >= maximumTagValues) {
                    return onMaxReached.accept(id);             // ← 委托给 onMaxReached
                }
                observedTagValues.add(value);
            }
            return NEUTRAL;
        }

        // configure() 也有对称逻辑——直方图配置也委托给 onMaxReached.configure()
        @Override
        public DistributionStatisticConfig configure(Meter.Id id,
                DistributionStatisticConfig config) {
            // ... 同样的 onMaxReached 委托逻辑
        }
    };
}
```

**为什么 accept() 和 configure() 都有委托？** 因为 `onMaxReached` 可能是 `deny()`（拒绝超出上限的指标），也可能是 `replaceTagValues()`（把超出上限的 tag value 替换为 `"other"`）。后者需要在 `configure()` 阶段也传递——因为 Timer 的直方图配置可能被 onMaxReached Filter 修改。

**实战场景**：

```java
registry.config().meterFilter(
    MeterFilter.maximumAllowableTags("http.server.requests", "uri",
        100,                                    // ← 只保留前 100 个 URI
        MeterFilter.replaceTagValues("uri",      // ← 超出后替换为 "other"
            v -> "other", "/api/health", "/api/info"))  // ← 排除健康检查
);
// 结果：101 个 URI 中，100 个保持原值，第 101 个开始 uri="other"
// /api/health 和 /api/info 除外——它们永远保持原值
```

---

## 四、Id 转换——map 阶段的四类操作

### 4.1 commonTags——全局 Tag 追加

```java
// MeterFilter.java L55-L62
static MeterFilter commonTags(Iterable<Tag> tags) {
    return id -> id.replaceTags(Tags.concat(tags, id.getTagsAsIterable()));
}
```

`Tags.concat()` 保证 metric 自身的 tag 优先——全局 tag 不会被同 key 的 metric tag 覆盖。这就是为什么 `D1 第八节` 中 commonTags "只对后续创建的 Meter 生效"——因为 commonTags 只修改 `map(id)` 的输出，已创建的 Meter 不会重新走 map。

### 4.2 renameTag——重命名 Tag Key

```java
// MeterFilter.java L71-L89
static MeterFilter renameTag(String meterNamePrefix, String fromTagKey, String toTagKey) {
    return id -> {
        if (!id.getName().startsWith(meterNamePrefix)) return id;  // ← 只影响指定前缀
        List<Tag> tags = new ArrayList<>();
        for (Tag tag : id.getTagsAsIterable()) {
            tags.add(tag.getKey().equals(fromTagKey)
                ? Tag.of(toTagKey, tag.getValue())
                : tag);
        }
        return id.replaceTags(tags);
    };
}
```

**典型用途**：Spring Boot 的 `http.server.requests` 默认使用 `uri` 标签，但 Prometheus 最佳实践是 `path`——`renameTag("http.server.requests", "uri", "path")`。

### 4.3 ignoreTags——删除 Tag

```java
// MeterFilter.java L96-L111
static MeterFilter ignoreTags(String... tagKeys) {
    return id -> {
        List<Tag> tags = stream(id.getTagsAsIterable().spliterator(), false)
            .filter(t -> !Arrays.asList(tagKeys).contains(t.getKey()))
            .collect(toList());
        return id.replaceTags(tags);
    };
}
```

删除高基数 Tag（如 `userId`）——这是控制 Prometheus 内存最简单的办法。

### 4.4 replaceTagValues——Tag Value 映射

```java
// MeterFilter.java L122-L139
static MeterFilter replaceTagValues(String tagKey, Function<String, String> replacement,
        String... exceptions) {
    return id -> {
        List<Tag> tags = stream(id.getTagsAsIterable().spliterator(), false)
            .map(t -> {
                if (!t.getKey().equals(tagKey)) return t;
                for (String ex : exceptions) {
                    if (t.getValue().equals(ex)) return t;         // ← 例外：保留原值
                }
                return Tag.of(tagKey, replacement.apply(t.getValue()));
            }).collect(toList());
        return id.replaceTags(tags);
    };
}
```

**reduce tag cardinality 的利器**——如把所有 `uri=/users/12345,/users/67890,...` 映射为 `uri=/users/{id}`。

---

## 五、直方图配置——maxExpected/minExpected

```java
// MeterFilter.java L309-L333（Duration 变体）
static MeterFilter maxExpected(String prefix, Duration max) {
    return (id, config) -> {
        if (id.getType() == Meter.Type.TIMER && id.getName().startsWith(prefix)) {
            return DistributionStatisticConfig.builder()
                .maximumExpectedValue((double) max.toNanos())
                .build().merge(config);          // ← 用 merge() 而非替换
        }
        return config;
    };
}
```

**为什么用 `merge(config)` 而非替换 `config`？**

`maxExpected` 只设置了 `maximumExpectedValue` 一个字段——其他 7 个字段（percentiles、percentilePrecision、SLO、expiry 等）保持 `config` 中的值。`merge()` 语义是"this 优先，parent 兜底"（参见 D1 §五）——如果 `config` 没有 maxExpected → maxExpected Filter 建的新 config 生效；如果 `config` 已有 → config 中的值优先（因为 merge 中 this = Filter 的新 config，this.maximumExpectedValue != null → 使用 Filter 的值）。

**实际效果**：`maxExpected` 并不真正"限制最大值"——它只影响 `DistributionStatisticConfig.getHistogramBuckets()` 的桶生成算法。桶的边界由 `minExpected/maxExpected/percentilePrecision` 共同决定。

---

## 六、forMeters——条件委托（**1.16.0，本 lab 基线无**）

```java
// MeterFilter.java L425-L442
static MeterFilter forMeters(Predicate<Meter.Id> predicate, MeterFilter delegate) {
    return new MeterFilter() {
        @Override public MeterFilterReply accept(Meter.Id id) {
            return predicate.test(id) ? delegate.accept(id) : MeterFilter.super.accept(id);
        }
        @Override public Meter.Id map(Meter.Id id) {
            return predicate.test(id) ? delegate.map(id) : MeterFilter.super.map(id);
        }
        @Override public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
            return predicate.test(id) ? delegate.configure(id, config) : MeterFilter.super.configure(id, config);
        }
    };
}
```

> **版本注记**：1.12.5 的 MeterFilter **没有** `forMeters`（1.16.0 新增）；本基线里"条件委托"只能用 `accept/deny/denyUnless` + `renameTag/ignoreTags` 等带 prefix 的工厂组合。以下为升级对照。

**解决的问题**：早期的 MeterFilter 只能在全局或按 name prefix 生效——`renameTag("http.server.requests", ...)` 只能按前缀匹配。`forMeters` 用 `Predicate<Meter.Id>` 任意匹配——按 tag、type、描述等任意条件：

```java
registry.config().meterFilter(
    MeterFilter.forMeters(
        id -> id.getType() == Meter.Type.TIMER && id.getTag("uri") != null,  // ← 任意条件
        MeterFilter.ignoreTags("exception")                                    // ← 仅对匹配的 Meter 生效
    )
);
```

---

## 七、Filter 链的注册与"晚注册"问题（1.12.5）

在 `MeterRegistry.meterFilter()`（L778-L784，D1 §8.2 分析过）中：

```java
public synchronized Config meterFilter(MeterFilter filter) {
    MeterFilter[] newFilters = new MeterFilter[filters.length + 1];
    System.arraycopy(filters, 0, newFilters, 0, filters.length);
    newFilters[filters.length] = filter;
    filters = newFilters;                  // ← volatile 发布
    return this;
}
```

**1.12.5 的语义**：新 Filter **只对之后创建的 Meter 生效**——已注册的 Meter 不会被重新映射/过滤，也没有任何警告或"stale"机制（1.17 才引入 late-filter 警告 + stalePreFilterIds 闭环）。

**生产建议**：**所有 MeterFilter 必须在第一个 Meter 创建之前注册**——否则后注册的 Filter 对已有 Meter 静默无效，你会以为规则生效了但面板数据没变。Boot 的 `MeterRegistryPostProcessor` 在 `postProcessMeterRegistry` 里先 `applyFilters`（D2 §八）——保证 Filter 早于 Binder 生效。

## 八、生产量化——Filter 链的实际成本

### 8.1 maximumAllowableMetrics 的内存模型

```java
// ConcurrentHashMap.newKeySet() = ConcurrentHashMap<Id, Boolean> 
// 每个 Id ~200 bytes（name + tags 字符串池化后）
// 限制 10000 个 time series：
10000 × 200 bytes = ~2MB（ConcurrentHashMap overhead ≈ +40%）
实际内存 ≈ 2.8MB（JVM 堆内）
```

在 Datadog 按 time series 收费（~$5/100 custom metrics）的环境中，`maximumAllowableMetrics(10000)` = 月费节省 ~$500/month × 数据保留周期。

### 8.2 Filter 链的热路径开销

```
每个 Meter 创建时：
  accept() → for (filter : filters)  // 最多 N 次 Predicate.test()
  map()    → for (filter : filters)  // 最多 N 次 id.replaceTags()
  configure() → for (filter : filters)  // 最多 N 次（只有 Timer/Summary 走这条路）

生产典型：5 Filter × 10K Meter/day 创建 = 微不足道
但：1000 Filter × 1M Meter 热部署 = for-each 遍历 10 亿次/天 → ~600ms CPU
```

**优化建议**：Filter 数量保持 < 20，避免在 Predicate 中做 IO 操作。`volatile MeterFilter[]` 的 for-each 遍历已经是最快路径（无 iterator 分配）。

### 8.3 replacement/replaceTagValues 的 GC 冲击

```java
// ignoreTags 内部：每调用一次 id.replaceTags() 创建新的 ArrayList + Tags 对象
// 100K Meter × 每年重启 12 次 = 1.2M Tags 对象/年
// Tags 对象 ~80 bytes → ~96MB/年 GC 开销（微）
```

只有在超大注册量（>1M Meter/registry）时才需要关注——此时推荐用 `denyNameStartsWith` 前置过滤 80% 的指标，让 Filter 链作用于剩余的 20%。

---

## 九、实战模式——常见 Filter 组合

### 模式一：按环境过滤

```java
registry.config()
    .meterFilter(MeterFilter.denyUnless(
        id -> "prod".equals(id.getTag("env"))));    // ← 只保留生产环境指标
```

### 模式二：Prometheus 命名空间隔离

```java
registry.config()
    .meterFilter(MeterFilter.renameTag("http.server.requests", "uri", "path"))
    .meterFilter(MeterFilter.replaceTagValues("path", v -> v.replaceAll("/\\d+", "/{id}")))
    .meterFilter(MeterFilter.maximumAllowableTags("http.server.requests", "path", 50,
        MeterFilter.deny()));                        // ← 超出 50 个 URI 后全拒绝
```

### 模式三：成本控制 + 关键指标保护

```java
registry.config()
    .meterFilter(MeterFilter.acceptNameStartsWith("order.payment"))   // ← 关键指标提前放行
    .meterFilter(MeterFilter.maximumAllowableMetrics(10000));          // ← 总量上限
```

---

## 十、跨框架对比

| 维度 | Micrometer MeterFilter | OpenTelemetry View | Dropwizard MetricFilter |
|------|------|------|------|
| **过滤粒度** | Meter（name + tags） | Metric（name + attributes + instrument type） | Metric（name only） |
| **Id 转换** | `map()`——改名/修改 tags | `View.setAttributeKeysFilter()`——deny matching attribute keys | 无——name 不可改 |
| **准入模式** | 三元 DENY/NEUTRAL/ACCEPT + 提前终止 | 无——所有 View 匹配后取 aggregation | 二元 true/false（拒绝/不放行） |
| **直方图配置** | `configure()`——修改 DistributionStatisticConfig | `View.setAggregation()`——覆盖 aggregation | 无 |
| **条件委托** | `forMeters()` 1.16+ | `View` 的 `InstrumentSelector` | `MetricFilter.startsWith()` |

---

## 十一、面试核心考点

### Q1: MeterFilterReply 为什么是三元（DENY/NEUTRAL/ACCEPT）而非二元？

二元只有"拒绝/不表态"——无法表达"提前放行"。一个关键指标需要"不管后面的 Filter 怎么说，这个指标必须保留"——这就是 ACCEPT。DENY 是"提前拒绝"，NEUTRAL 是"往下传"。ACCEPT 和 DENY 都**立即终止链式检查**——后续 Filter 不再被调用。

### Q2（陷阱）: `accept()` 和 `denyUnless()` 的区别？

`accept()` 返回 ACCEPT → 立即接受，后续 Filter 不检查。`denyUnless()` 返回 NEUTRAL（放行时）→ 后续 Filter 仍可 DENY。所以 `accept()` 是强更强势——它"保护"指标不受后续 Filter 影响。

### Q3: maximumAllowableMetrics 有什么隐藏缺陷？

不区分关键 vs 非关键指标——超出上限后所有新 Meter 全被拒绝。如果关键业务指标（如 `order.payment`）创建得晚，它会被静默拒绝——应用正常运行但监控面板空白。**必须配合 `acceptNameStartsWith` 前置保护关键指标。**

### Q4: commonTags 为什么对已创建的 Meter 无效？

commonTags 在 `map(id)` 阶段追加 tag。`map(id)` 只在 Meter 创建时调用——已创建的 Meter 不会重新走 map。要修改已有 Meter 的 tag，只能 `remove()` + 重新创建——但历史数据丢失。

### Q5: MeterFilter 链的顺序为什么影响结果？

因为 `ACCEPT` 和 `DENY` 都立即终止链式检查。`filters[0]=acceptNameStartsWith("order")` → `filters[1]=deny(id -> true)`：order 前缀的指标被 `filters[0]` 的 ACCEPT 提前放行 → 不会被 `filters[1]` 的 DENY 拒绝。反过来 `filters[0]=deny(id -> true)` → `filters[1]=acceptNameStartsWith("order")`：deny 返回 DENY → 所有指标在 `filters[0]` 被拒绝 → `filters[1]` 永远不会被调用。

### Q6（陷阱）: 在 `map()` 中改了 tag key，`accept()` 能看到吗？

不能——`accept()` 在 `map()` **之前**调用。`accept()` 用的是 `originalId`（用户传入的原始 Id），`map()` 用的是 `mappedId`（Filter 链转换后的 Id）。如果你在 `map()` 中把 `uri` 改成了 `path`，然后用 `accept(id -> id.getTag("path") != null)` 检查——不会命中，因为 `accept()` 看到的还是 `uri` tag。这是 `accept` → `map` → `configure` 三阶段分离的核心原因。
