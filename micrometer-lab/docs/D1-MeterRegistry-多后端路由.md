# D1: MeterRegistry——指标采集的统一入口

> **阅读顺序**: P0 必备——理解 Micrometer 如何通过 Facade + 模板方法实现多后端指标路由
> **源码版本**: micrometer-core **v1.12.5**（tag；运行时对齐 Boot 3.2.5，与 xhs-project 一致；1.13+ 新特性以注记标出）
> **核心文件**:
> - `MeterRegistry.java` (1121 行)——抽象 Facade，定义 12 个模板方法（9 abstract + 3 concrete）
> - `CompositeMeterRegistry.java` (243 行)——多后端路由 + 多层嵌套
> - `Meter.java` (528 行)——Meter 接口 + Id 内嵌类（等价判定逻辑）
> - `Clock.java` (58 行) + `MockClock.java` (56 行)——时间源抽象
> - `DistributionStatisticConfig.java` (517 行)——直方图配置引擎
> - `SimpleMeterRegistry.java` (224 行)——对照模板方法的标准实现

---

## 一、Micrometer 解决了什么 Dropwizard 解决不了的问题

2017 年之前 Java 监控用 Dropwizard Metrics。它有两个**结构性缺陷**：

**缺陷一：没有原生的 Tag（维度）概念**。所有维度必须编码到 metric name 字符串里：

```
"orders.created.us-east.api-v2.GET"
```

这不是风格问题——这意味着你不能在 Prometheus 中写 `sum by(region)`。维度被"硬编码"进了 name。

Micrometer 把 Tag 作为一等公民：

```java
registry.counter("orders.created", Tags.of("region","us-east","api","v2","method","GET"))
// Prometheus 输出: orders_created_total{region="us-east",api="v2",method="GET"}
```

**缺陷二：Registry 和 Reporter 紧耦合**。Dropwizard 的 `MetricRegistry` 持有实际数据，Reporter 定期读取——但你无法同时优雅地支持 Prometheus pull 模式（被动等抓取）和 JMX push 模式（主动推数据），因为它们在同一个数据存储结构上操作不同的时间节奏。

Micrometer 的解法是让每个后端有自己独立的 Registry：

```
CompositeMeterRegistry (纯 Facade，不存数据)
    └─→ SimpleMeterRegistry  (内存，开发环境)
    └─→ PrometheusMeterRegistry  (独立存储，/actuator/prometheus)
    └─→ JmxMeterRegistry    (独立存储，MBean)
```

每个后端有自己的存储——相同的 `registry.counter("http.requests")` 在 Prometheus 和 JMX 中创建**两个独立的 Counter 对象**。这是 Facade 模式 + 多后端路由的完美结合。

---

## 二、模板方法——9 个 abstract + 3 个 concrete 的职责分化

`MeterRegistry` 是一个抽象类。子类需要实现 **9 个 abstract** 方法，另有 3 个 concrete 方法提供默认实现：

### 2.1 9 个 abstract 方法（子类必须实现）

```java
// MeterRegistry.java
protected abstract <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> f);          // L140
protected abstract Counter newCounter(Meter.Id id);                                          // L148
protected abstract Timer newTimer(Meter.Id id, DistributionStatisticConfig, PauseDetector);  // L188
protected abstract DistributionSummary newDistributionSummary(Meter.Id id,
    DistributionStatisticConfig, double scale);                                              // L200
protected abstract Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement>);     // L211
protected abstract <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj,
    ToLongFunction<T> countFunction, ToDoubleFunction<T> totalTimeFunction,
    TimeUnit totalTimeFunctionUnit, TimeUnit baseTimeUnit);                                 // L262
protected abstract <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj,
    ToDoubleFunction<T> countFunction);                                                     // L275
protected abstract TimeUnit getBaseTimeUnit();                                              // L288
protected abstract DistributionStatisticConfig defaultHistogramConfig();                    // L300
```

### 2.2 3 个 concrete 方法（提供默认实现）

```java
// MeterRegistry.java
@Deprecated protected LongTaskTimer newLongTaskTimer(Meter.Id id) {                          // L160
    throw new UnsupportedOperationException("should use full constructor");                    // ← 已废弃
}
protected LongTaskTimer newLongTaskTimer(Meter.Id id, DistributionStatisticConfig config) {  // L174
    return newLongTaskTimer(id);  // ← 默认回退到废弃版本
}
protected <T> TimeGauge newTimeGauge(Meter.Id id, T obj, TimeUnit unit,                     // L224
    ToDoubleFunction<T> f) {
    return newGauge(id, obj, obj2 -> TimeUtils.convert(f.applyAsDouble(obj2), unit, getBaseTimeUnit()));
}  // ← TimeGauge = 基于时间单位的 Gauge，内部委托给 newGauge
```

**总计 12 个模板方法**（9 abstract + 3 concrete）——比 Dropwizard 的 `MetricRegistry` 单一 `newMetric` 入口多了 12 倍的控制粒度。

### 2.3 为什么不用一个 `newMeter(Meter.Type type, Object config)` 统一方法？

因为参数集完全不同：

- `Counter`：只需要 `Meter.Id`——内部只有一个 `AtomicDouble count`
- `Timer`：需要 `DistributionStatisticConfig`（直方图桶配置）+ `PauseDetector`（检测系统时钟跳变）
- `Gauge`：需要 `T obj` + `ToDoubleFunction<T>`——需要一个"被观察对象"
- `FunctionCounter`：需要 `T obj` + `ToDoubleFunction<T>`——惰性读取而非主动 increment
- `newMeter`：需要 `Type` + `Iterable<Measurement>`——最通用的自定义 Meter

强行用 `Object config` 需要运行时类型转换——放弃了编译期检查。模板方法的核心思想是"用不同方法签名保证类型安全"。

### 2.4 对照 SimpleMeterRegistry 看模板方法如何运作

```java
// SimpleMeterRegistry.java L115-L124
protected Counter newCounter(Meter.Id id) {
    switch (config.mode()) {
        case CUMULATIVE:
            return new CumulativeCounter(id);          // ← AtomicDouble 包装
        case STEP:
        default:
            return new StepCounter(id, clock, config.step().toMillis());  // ← 步进聚合
    }
}
```

两种实现体现了 Micrometer 的分层设计——API 层（`MeterRegistry`）决定"何时创建"，实现层（`SimpleMeterRegistry`/`PrometheusMeterRegistry`）决定"如何存储"。`CountingMode.STEP` 是 SimpleMeterRegistry 特有的——生产环境用 StepMeterRegistry 处理步进聚合。

---

## 三、registerMeterIfNecessary——完整源码漫游

所有公共 API（`counter()`, `timer()`, `gauge()`）最终走到 `registerMeterIfNecessary`——MeterRegistry.java L581（4 参重载）/ L586（5 参主实现）。这个方法分四层：

### 层一：映射 + 委托 + 类型校验（L586-L593）

```java
private <M extends Meter> M registerMeterIfNecessary(Class<M> meterClass, Meter.Id id,
        @Nullable DistributionStatisticConfig config,
        BiFunction<Meter.Id, DistributionStatisticConfig, M> builder,
        Function<Meter.Id, M> noopBuilder) {
    Id mappedId = getMappedId(id);                    // ← 先过 MeterFilter.map() 链
    Meter m = getOrCreateMeter(config, builder, id, mappedId, noopBuilder);
    if (!meterClass.isInstance(m)) {
        throw new IllegalArgumentException(
            "There is already a registered meter of a different type (%s vs. %s) with the same name: %s");
    }
    return meterClass.cast(m);
}
```

外层做三件事：**先算 post-filter Id**（`getMappedId`）→ 委托 `getOrCreateMeter` 创建/复用 → 校验返回 Meter 类型。`builder` 是 `BiFunction<Id, DistributionStatisticConfig, Meter>`（1.17 才改成 `NewMeterSupplier`）；`noopBuilder` 在 Registry 关闭或 Filter 拒绝时返回 `NoopCounter` 等——保证 shutdown 过程中安全访问 API 不崩溃。

### 层二：getOrCreateMeter——单 Map + 双检锁（L611-L650）

```java
private Meter getOrCreateMeter(@Nullable DistributionStatisticConfig config,
        BiFunction<Id, DistributionStatisticConfig, ? extends Meter> builder,
        Id originalId, Id mappedId, Function<Meter.Id, ? extends Meter> noopBuilder) {
    Meter m = meterMap.get(mappedId);                  // ← 直接以 post-filter Id 查
    if (m == null) {
        if (isClosed()) { return noopBuilder.apply(mappedId); }
        synchronized (meterMapLock) {
            m = meterMap.get(mappedId);                // ← double-check
            if (m == null) {
                if (!accept(mappedId)) { return noopBuilder.apply(mappedId); }  // Filter 拒绝
                if (config != null) {                  // ← 仅 Timer/Summary/LongTask 路径
                    for (MeterFilter filter : filters) {
                        DistributionStatisticConfig filteredConfig = filter.configure(mappedId, config);
                        if (filteredConfig != null) { config = filteredConfig; }
                    }
                }
                m = builder.apply(mappedId, config);   // ← 调用模板方法
                Id synAssoc = mappedId.syntheticAssociation();
                if (synAssoc != null) {
                    syntheticAssociations.computeIfAbsent(synAssoc, k -> new HashSet<>()).add(mappedId);
                }
                for (Consumer<Meter> onAdd : meterAddedListeners) { onAdd.accept(m); }
                meterMap.put(mappedId, m);             // ← 先回调后 put（都在锁内）
            }
        }
    }
    return m;
}
```

**1.12.5 只有一个 `meterMap`**（key = post-filter Id）——**没有** 1.17 的 `preFilterIdToMeterMap`/`meterToPreFilterIdMap` 双 Map 与 `isStaleId` 机制（那是新版为"过滤器改名后仍能按原始 Id 复用"引入的）。本基线里 `originalId` 只参与回调与 remove 语义。

**syntheticAssociations 的来源**：不是 Prometheus 的 count/sum/max（那是同一 Meter 内多个 metric family）。`syntheticAssociation` 来自 `Gauge.Builder.synthetic(Meter.Id)`（Gauge.java L165-L169，`@Incubating(since = "1.1.0")`），由 `HistogramGauges`（distribution/HistogramGauges.java）在生成 percentile / histogram bucket 衍生 Gauge 时设置——Timer 被 remove 时递归删除这些衍生 Gauge（见第十节）。

### 层三：getMappedId——MeterFilter 链的 map 阶段（L596-L605）

```java
private Id getMappedId(Id id) {
    if (id.syntheticAssociation() != null) { return id; }  // ← 合成 Meter 不经过 Filter
    Id mappedId = id;
    for (MeterFilter filter : filters) {                    // ← volatile MeterFilter[] filters (L83)
        mappedId = filter.map(mappedId);
    }
    return mappedId;
}
```

（1.17 中该方法更名为 `mapId`；本基线叫 `getMappedId`。）

`filters` 不是 `List<MeterFilter>`——它是 `volatile MeterFilter[]` 数组。**为什么用数组？** `getMappedId` 在每次 Meter 创建时被调用（热路径），数组的 `for-each` 遍历比 `List` 快——避免 iterator 分配 + 边界检查优化。`volatile` 保证 `config().meterFilter()` 修改后其他线程看到的是最新的数组引用。

### 层四：accept——MeterFilter 链的 deny/neutral/accept 阶段（L657-L670）

```java
private boolean accept(Meter.Id id) {
    for (MeterFilter filter : filters) {
        MeterFilterReply reply = filter.accept(id);
        if (reply == MeterFilterReply.DENY)   { return false; }
        if (reply == MeterFilterReply.ACCEPT) { return true;  }  // ← 立即终止！
    }
    return true;   // ← 全部 NEUTRAL → 默认接受
}
```

`MeterFilterReply` 是 `DENY/NEUTRAL/ACCEPT` 三元枚举。**任一 Filter 返回 DENY → 立即拒绝。任一 Filter 返回 ACCEPT → 立即接受——不检查后续 Filter。** 这意味着一个宽松的 ACCEPT Filter 排在链的前面时，可以"提前放行"一个原本可能被后续 DENY 拒绝的指标。MeterFilter 的顺序影响结果。

---

## 四、Clock 抽象——为什么不能只用 `System.currentTimeMillis()`

`MeterRegistry` 持有 `Clock` 实例（L86），构造时必须注入（L154）。Clock 只有 2 个方法：

```java
// Clock.java L47, L56
long wallTime()        // ← 毫秒纪元时间 = System.currentTimeMillis()
long monotonicTime()   // ← 纳秒单调时间 = System.nanoTime()
```

**为什么需要区分 wallTime 和 monotonicTime？**

- `wallTime()` 是**墙钟时间**——会被 NTP 回拨/闰秒调整。用于：给推送型后端打时间戳、判定 step 边界（`StepLong` 用 wallTime 决定何时 rollover）。
- `monotonicTime()` 是**单调时钟**——只增不减，只有差值有意义。用于：Timer 记录 durations、LongTaskTimer 记录长任务耗时。

如果 Timer 用 `wallTime()`，NTP 回拨会导致负的 duration——Timer 无法处理。如果用 `monotonicTime()` 给日志打时间戳，日志里的时间可能和墙钟差几秒（但不会回退）。

**MockClock——测试利器**（54 行）：

```java
MockClock clock = new MockClock();
clock.add(Duration.ofSeconds(30));       // ← 手动推进时间
clock.addSeconds(60);                    // ← 推进 60 秒

// 测试 step rollover: 推进刚好一个 step 周期
PrometheusMeterRegistry registry = new PrometheusMeterRegistry(
    PrometheusConfig.DEFAULT, MockClock.clock(registry));
clock.addSeconds(config.step().getSeconds());
// → StepMeterRegistry 的 StepLong 已 rollover，新计数从 0 开始
```

`MockClock` 内部 `timeNanos` 从 1ms 起步（注释：**非零防除零**）。测试中替代 `System.currentTimeMillis()/nanoTime()`，让时间完全可控。

---

## 五、DistributionStatisticConfig——直方图配置引擎

Timer/DistributionSummary 创建时都需要 `DistributionStatisticConfig`（498 行）。

### 5.1 8 个可空字段

```java
// DistributionStatisticConfig.java L50-L64
Boolean percentileHistogram;    // ← 是否启用客户端预分桶直方图
double[] percentiles;           // ← 要计算的百分位（如 {0.5, 0.95, 0.99}）
Integer percentilePrecision;    // ← 百分位精度（默认 1 = 整数）
double[] serviceLevelObjectives;// ← SLO 桶边界（如 {10ms, 50ms, 100ms, 500ms}）
Double minimumExpectedValue;    // ← 最小期望值（桶生成用）
Double maximumExpectedValue;    // ← 最大期望值（桶生成用）
Duration expiry;                // ← 样本过期时间
Integer bufferLength;           // ← 环形缓冲区长度
```

### 5.2 DEFAULT 常量——2min × 3 buffer = 6min 完整衰减

```java
// DistributionStatisticConfig.java L40
public static final DistributionStatisticConfig DEFAULT = builder()
    .percentilesHistogram(false)
    .percentilePrecision(1)
    .minimumExpectedValue(1.0)
    .maximumExpectedValue(Double.POSITIVE_INFINITY)
    .expiry(Duration.ofMinutes(2))        // ← 关键：每个样本 2 分钟后过期
    .bufferLength(3)                      // ← 环形缓冲区 3 个槽
    .build();
```

`bufferLength=3` + `expiry=2min` = 每个样本在环形缓冲区中保留 2 分钟，3 个 buffer 的总生命周期 = **6 分钟**。6 分钟后样本被完全覆盖——这是分段衰减（staggered decay），不是滑动窗口。

**面试陷阱**：Prometheus 的默认抓取间隔是 1 分钟。2min expiry × 3 buffer = 6min > 1min step——保证了 Prometheus 在连续两次抓取之间一定能看到数据，不会因为 sample 空间不够而丢失信息。

### 5.3 merge()——"this 优先，parent 兜底"

```java
// DistributionStatisticConfig.java L78-L94
public DistributionStatisticConfig merge(DistributionStatisticConfig parent) {
    return builder()
        .percentilesHistogram(this.percentileHistogram == null
            ? parent.percentileHistogram : this.percentileHistogram)
        .percentiles(this.percentiles == null ? parent.percentiles : this.percentiles)
        // ... 每个字段：this 非 null 用 this，否则用 parent
        .build();
}
```

合并发生在 `getOrCreateMeter` 中——`MeterFilter.configure()` 链之后，传入的 `config` 与 `registry.defaultHistogramConfig()` merge：

```java
// MeterRegistry.java L632-L640（getOrCreateMeter 内）
for (MeterFilter filter : filters) {
    DistributionStatisticConfig filtered = filter.configure(mappedId, config);
    if (filtered != null) { config = filtered; }
}
config = config.merge(defaultHistogramConfig());
```

**注意**：只有 `config != null`（即 Timer/DistributionSummary/LongTaskTimer 路径）才执行 Filter 链——Counter/Gauge 的 config 是 null，跳过分发。

---

## 六、CompositeMeterRegistry——多层嵌套的完整机制

`CompositeMeterRegistry`（253 行）不仅是"把 Meter 写到多个 Registry"——它是一个支持嵌套的、事件驱动的发布-订阅系统。

### 6.1 数据结构——IdentityHashMap + AtomicBoolean

```java
// CompositeMeterRegistry.java L42-L53
private final AtomicBoolean registriesLock = new AtomicBoolean();
private final Set<MeterRegistry> registries =
    Collections.newSetFromMap(new IdentityHashMap<>());  // ← 用 == 去重
private volatile Set<MeterRegistry> nonCompositeDescendants = Collections.emptySet();
private final AtomicBoolean parentLock = new AtomicBoolean();
private final Set<CompositeMeterRegistry> parents = Collections.newSetFromMap(new IdentityHashMap<>());
```

`IdentityHashMap` 用 `==` 去重——相同配置的两个 `PrometheusMeterRegistry` 实例可以共存（`equals()` 可能相同但引用不同）。这正是多后端路由需要的语义。

### 6.2 嵌套与循环检测

```java
// CompositeMeterRegistry.java L153-L161
private void forbidSelfContainingComposite(MeterRegistry registry) {
    if (registry == this) {
        throw new IllegalArgumentException("Adding a composite meter registry to itself is not allowed!");
    }
    if (registry instanceof CompositeMeterRegistry) {
        ((CompositeMeterRegistry) registry).getRegistries().forEach(this::forbidSelfContainingComposite);
    }
}
```

Composite 可以包含 Composite——`forbidSelfContainingComposite` 递归检查防止自包含和循环嵌套。`nonCompositeDescendants` 递归展平所有嵌套——把 Composite 中的所有叶子 Registry 收集到一个 Set 中。

### 6.3 onMeterAdded 回调驱动的 Meter 分发

```java
// CompositeMeterRegistry.java L65-L71（构造器）
config().onMeterAdded(m -> {
    if (m instanceof CompositeMeter) {
        lock(registriesLock, () -> nonCompositeDescendants.forEach(
            ((CompositeMeter) m)::add));          // ← 把 CompositeMeter 加到所有叶子 Registry
    }
}).onMeterRemoved(m -> {
    nonCompositeDescendants.forEach(r -> r.removeByPreFilterId(m.getId()));
});
```

关键设计：Composite 里创建的是 `CompositeMeter`（包装多个后端 Meter）。`onMeterAdded` 回调在 `getOrCreateMeter`（L644-L648）触发——**在 `meterMap.put` 之前，但在 `synchronized` 块内**。这意味着 CompositeMeter 在加入 Composite 的 meterMap 之前，已经被分发到所有叶子 Registry。

### 6.4 CAS 自旋锁——为什么不用 synchronized

```java
// CompositeMeterRegistry.java L185-L192
private void lock(AtomicBoolean lock, Runnable task) {
    while (!lock.compareAndSet(false, true)) {} // ← 纯 CPU 自旋
    try { task.run(); }
    finally { lock.set(false); }
}
```

临界区内只做 O(1) 操作（遍历 Set + 调 `add()`）。自旋等待微秒级别，比 JVM 线程挂起（synchronized 膨胀到重量级锁）更高效。但前提是**临界区内不能调用任何可能阻塞的外部代码**。

### 6.5 模板方法——全部返回 CompositeXxx

```java
// CompositeMeterRegistry.java L75-L140（newTimer L79 / newCounter L91 / newGauge L101 / newMeter L133）
newCounter         → new CompositeCounter(id)
newTimer           → new CompositeTimer(id, config, pd)
newGauge           → new CompositeGauge<>(id, obj, f)
// ... 所有 9 个 abstract + 3 个 concrete 都返回 Composite 包装
getBaseTimeUnit    → TimeUnit.SECONDS                           // L127
defaultHistogramConfig → DistributionStatisticConfig.NONE      // L132  ← Composite 自己不配直方图
```

`CompositeXxx` 的构造器在内部为每个叶子 Registry 调用对应的 `registry.counter()/timer()`——然后在调用 `increment()`/`record()` 时广播到所有后端。

---

## 七、Meter.Id——为什么 type 不参与等价判定

### 7.1 equals() 只用 name + tags

```java
// Meter.java L352-L362
public boolean equals(Object o) {
    Meter.Id other = (Meter.Id) o;
    return name.equals(other.name) && tags.equals(other.tags);
    // type/description/baseUnit 不参与！
}
```

**如果 type 参与等价判定会怎样？**

同一个 `name+tags` 组合（如 `"http.requests", {uri="/api"}`）可以是 Counter（计数）也可以是 Timer（计时）。如果 type 参与 equals，两个不同类型的 Meter 会被视为不同的 Id → `meterMap` 中会存两份 → 后端出现两个同名指标。Micrometer 选择"同一个 name+tags 只有一个 Id"——牺牲灵活性换取数据一致性。

### 7.2 Type 枚举——6 种 Meter 类型

```java
// Meter.java L64-L66
enum Type {
    COUNTER, GAUGE, LONG_TASK_TIMER, TIMER, DISTRIBUTION_SUMMARY, OTHER
}
```

`OTHER` 是 fallback——通过 `registry.newMeter()` 创建的自定义 Meter 统一归入此类型。

### 7.3 Meter.Id 的可变操作——所有返回新对象

```java
// Meter.java L220-L340
withName(String)          // ← 改名（不修改原 Id）
withTag(Tag)              // ← 追加 Tag
replaceTags(Iterable)     // ← 替换全部 Tag
withTag(Statistic)        // ← 追加 statistic 标签（如 statistic="count"）
withBaseUnit(String)      // ← 设置基础单位
getConventionName(NamingConvention)  // ← 按命名约定转换（惰性）
getConventionTags(NamingConvention)  // ← 按命名约定转换 tags
```

所有修改操作返回新 `Meter.Id` 实例——不可变设计保证了 `ConcurrentHashMap` key 的安全。`getConventionName()` 和 `getConventionTags()` 在序列化时调用——如果修改了 `NamingConvention`（如 `config().namingConvention(snakeCase)`），不同的序列化轮次可能看到不同的名字，但原始 `name` 永远不变。

---

## 八、Config 内部类——运行时配置的完整 API

`MeterRegistry.Config`（L748 起）是 MeterRegistry 的配置入口：

### 8.1 配置方法清单

```java
// MeterRegistry.java
registry.config()
    .commonTags("env", "prod")              // L756——追加全局 Tag
    .meterFilter(MeterFilter.deny(...))     // L778——追加 MeterFilter
    .onMeterAdded(meter -> { ... })         // L791——注册 Meter 添加监听器
    .onMeterRemoved(meter -> { ... })       // L803
    .onMeterRegistrationFailed((id, msg) -> { ... }) // L816
    .namingConvention(snakeCase)           // L827——设置命名约定
    .clock()                               // L843——读取时钟（1.12.5 只有 getter，无 setter；换时钟走构造器）
    .pauseDetector(new CustomPauseDetector()) // L854——替换时钟跳变检测器
    .withHighCardinalityTagsDetector()     // L871——高基数 Tag 检测（1.10+；参数版 L872）
```

### 8.2 meterFilter() 的实现——数组追加（1.12.5）

```java
// MeterRegistry.java L778-L784
public synchronized Config meterFilter(MeterFilter filter) {
    MeterFilter[] newFilters = new MeterFilter[filters.length + 1];
    System.arraycopy(filters, 0, newFilters, 0, filters.length);
    newFilters[filters.length] = filter;
    filters = newFilters;                                  // ← volatile 发布
    return this;  // ← 返回 this → 链式 API
}
```

**1.12.5 的语义**：新 Filter **只对之后创建的 Meter 生效**——已注册的 Meter 不会被重新映射/过滤。1.17 才引入"late filter 警告 + stalePreFilterIds 标记"闭环（本基线没有）。所以生产上 **MeterFilter 必须在 Meter 创建前注册**（Boot 通过 `MeterRegistryCustomizer` 在启动早期完成）。

### 8.3 commonTags 的实现——本质是 MeterFilter

`commonTags` 不直接追加 Tag——它注册了一个 `MeterFilter`，在 `map(id)` 阶段追加 Tag。这意味着：
- **全局 Tag 对已创建的 Meter 无效**——只在创建新 Meter 时追加
- **`meterMap` 中的 key 包含全局 Tag**——因为 `map(id)` 在 `meterMap.get()` 之前执行

### 8.4 onMeterAdded 的真实触发时间

回调在 `getOrCreateMeter`（L644-L648）触发——在 `synchronized(meterMapLock)` 块内，但在 `meterMap.put(mappedId, m)`（L649）之前。这意味着监听器能看到新 Meter，但它还没有出现在 `meterMap` 中——这是一个微妙但重要的时序：监听器不能依赖 `registry.find()` 来查找这个 Meter。

---

## 九、More——高级 API 抽象

`registry.more()` 返回 `MeterRegistry.More` 实例（L918 起），提供核心 4 种 Meter 之外的扩展：

```java
registry.more()
    .longTaskTimer("batch.process")               // ← 长时间运行任务计时
    .functionCounter("cache.hits", cache,          // ← 从对象惰性读取（无需手动 increment）
        ConcurrentHashMap::size);
```

### 9.1 FunctionCounter vs 普通 Counter——两种采集哲学

```java
// 普通 Counter: 必须手动 increment()
Counter c = registry.counter("order.count");
orderService.onOrderCreated(() -> c.increment());  // ← 必须侵入业务代码

// FunctionCounter: 被动读取
registry.more().functionCounter("order.count", orderService,
    OrderService::getTotalOrderCount);
// Micrometer 在 publish 时自动调用 getTotalOrderCount() → 无需侵入
```

`FunctionCounter` 的 `countFunction` 是 `ToDoubleFunction<T>`——每次后端推送（publish）时被动读取值。**注意**：MeterRegistry 本身没有 `publish()` 方法——发布工作流在 `PushMeterRegistry` 子类中（见 D7/D8）。SimpleMeterRegistry 是纯内存 registry，无 publish 机制。

**`more()` 为什么不是直接在 `MeterRegistry` 上加方法？**——因为 `longTaskTimer()` 和 `functionCounter()` 只有少数场景使用。`More` 是一个分组器——把"高级但低频"的 API 从核心 API 分离出来，保持核心 API 的简洁和职责单一。

---

## 十、Meter 的完整生命周期

### 阶段一：创建——Builder 模式 vs registry 方法

```java
// 方式一: registry.counter()——简单场景（name + tags）
Counter c = registry.counter("http.requests", "uri", "/api");

// 方式二: Counter.builder()——需要直方图或 SLO 时
Counter c = Counter.builder("http.requests")
    .description("HTTP 请求计数")
    .tag("uri", "/api")
    .baseUnit("requests")
    .register(registry);           // ← 最终调用 registry 的模板方法
```

Builder 的作用不是"另一种创建方式"，而是**参数空间的管理**——`Counter.builder()` 支持 8 个可选参数，如果全放在 `registry.counter()` 的方法签名上，API 会爆炸。Builder 最终也调用 `registry.counter(customId)`——两者走同一个 `registerMeterIfNecessary`。

### 阶段二：查询

```java
registry.getMeters();                                          // ← 返回所有 Meter（Arrays.asList 零拷贝）
registry.find("http.requests").tags("uri", "/api").counter();  // ← 精确查找
registry.find("http.requests").tagKeys("uri").meters();        // ← 模糊查找
registry.forEachMeter(m -> { ... });                           // ← Consumer 遍历
```

`find()` 返回 `Search` 对象——链式 API，遍历 `meterMap` 做流式过滤。O(n) 但实际使用频率低，不需要索引。

**Meter.match/use**（Meter.java L92-L147）：9 个 Function 参数的 instanceof 链式分发——`counter(this::handleCounter, timer(this::handleTimer), gauge(this::handleGauge), ...)`。这是 visitor 模式在 Meter 类型上的应用。

### 阶段三：移除——remove() 的级联清理（1.12.5）

```java
// MeterRegistry.java L710-L733
public Meter remove(Meter.Id mappedId) {
    Meter m = meterMap.get(mappedId);
    if (m != null) {
        synchronized (meterMapLock) {
            m = meterMap.remove(mappedId);              // ← key 是 post-filter Id
            if (m != null) {
                Set<Id> synthetics = syntheticAssociations.remove(mappedId);
                if (synthetics != null) {
                    for (Id synthetic : synthetics) {
                        remove(synthetic);              // ← 递归删除衍生 Gauge
                    }
                }
                for (Consumer<Meter> onRemove : meterRemovedListeners) {
                    onRemove.accept(m);                 // ← 通知监听器
                }
                return m;
            }
        }
    }
    return m;
}
```

**1.12.5 的 remove 只有"meterMap 删除 + synthetic 级联 + 监听器"三步**——没有 1.17 的 preFilter 双 Map 与 stale 清理。`removeByPreFilterId(preFilterId)`（L695-L697）的实现极简：`remove(getMappedId(preFilterId))`——先把原始 Id 过一遍 Filter 链再删。

**面试陷阱**：`remove()` 后立刻用同样的 name+tags 创建同名 Meter——结果是新 Meter（count=0）。`remove` 从 meterMap 中删除映射 → 下次 `registerMeterIfNecessary` 走创建分支生成新 Meter。旧 Meter 的历史数据永久丢失。

### 阶段四：关闭——close() 的级联清理

```java
// MeterRegistry.java L1086-L1098
public void close() {
    if (closed.compareAndSet(false, true)) {
        synchronized (meterMapLock) {
            for (Meter meter : meterMap.values()) {
                meter.close();                     // ← 遍历关闭所有 Meter
            }
        }
        if (highCardinalityTagsDetector != null) {
            highCardinalityTagsDetector.close();   // ← 关闭高基数检测器
        }
    }
}
```

**关键细节**：`meter.close()` 只清理 Meter 持有的资源（如 DedicatedExecutor close()）。Prometheus 的 collectorMap 清理不在 close() 中——在 `remove()` 时通过 `onMeterRemoved` 回调触发。

**各级 close() 的级联**：
- `PushMeterRegistry.close()`：stop() + 最后一次 publishSafelyOrSkipIfInProgress() + super.close()
- `StepMeterRegistry.close()`：stop() + flush 最后一个 step + closingRolloverStepMeters() + super.close()
- `CompositeMeterRegistry.close()`：`registries.forEach(MeterRegistry::close)` + super.close()

**`isClosed()` 的作用**：`getOrCreateMeter` L617-L619 `if (isClosed()) return noopBuilder.apply(mappedId)`——关闭后创建新 Meter 返回 Noop 而非抛异常。这允许 shutdown 过程中安全地访问 registry API（如日志里的 `registry.counter("shutdown.events").increment()`）。

### Meter.close()——Meter 也可以关闭

```java
// Meter.java L521
default void close() {}
```

默认空实现。有资源的 Meter（如 FunctionTimer 持有 DedicatedExecutor 内部线程）可以 override 来释放。MeterRegistry.close() 遍历所有 Meter 逐个调 close()——这是"Registry 关闭 → 所有 Meter 关闭"的级联路径。

---

## 十一、Noop 体系——关闭后的安全防护

`noop/` 目录有 9 个实现类：`NoopCounter/NoopTimer/NoopGauge/NoopDistributionSummary/NoopLongTaskTimer/NoopFunctionCounter/NoopFunctionTimer/NoopTimeGauge/NoopMeter`。

```java
// MeterRegistry.java L581-L593（registerMeterIfNecessary 的 noopBuilder 参数）
// 每个公共 API 都传入 Noop builder:
registry.counter("http.requests")
    → counter(Meter.Id) → registerMeterIfNecessary(Counter.class, id,
        (id2, conf) -> newCounter(id2),      // ← builder: 创建真实 Counter
        noopId -> new NoopCounter(noopId));  // ← noopBuilder: 关闭后返回 Noop
```

**设计意图**：Registry 关闭后 `getOrCreateMeter` 返回 NoopCounter 而非抛异常——保证 shutdown 过程中的代码路径安全。调用 `increment()` 是空操作——不产生数据，也不崩溃。

---

## 十二、NamingConvention——命名约定的分层

```java
registry.config().namingConvention(NamingConvention.snakeCase);
```

Micrometer 内部用 `camelCase`（如 `httpServerRequests`），但每个后端有自己的命名约定：

| 后端 | NamingConvention | `http.server.requests` 输出为 |
|------|-----------------|------------------------------|
| Prometheus（1.12.5） | `PrometheusNamingConvention` | Timer → `http_server_requests_seconds`；Counter → `..._total`（**手动追加**） |
| JMX | `JmxNamingConvention` | `http.server.requests`（保留点号） |
| Graphite | `GraphiteNamingConvention` | `http.server.requests` |
| Composite | `NamingConvention.identity` | `http.server.requests`（原样） |

转换在 `Meter.Id.getConventionName(NamingConvention)` 中惰性发生——后端推送时按各自的约定转换。`Meter.Id` 内部存储的是**原始 name**——转换只在序列化时发生。`getMeters()` 返回的 name 永远是原始的。

### Tags 的内部结构——为什么是不可变的

`Tags` 是 `Iterable<Tag>`，每个 `Tag` 是 `key=value` 对：

```java
// Tag 的 equals 语义
Tag.of("uri", "/api").equals(Tag.of("uri", "/api"))  // → true

// Tags 内部按 key 字典序排序
Tags.of("a","1","b","2").equals(Tags.of("b","2","a","1")) // → true（消除了调用顺序差异）

// hashCode() 惰性计算并缓存——ConcurrentHashMap.get() 的热路径优化
```

**为什么不可变？** `Tags` 用于 `Meter.Id` 中——`Meter.Id` 是 `ConcurrentHashMap` 的 key。如果 Tags 可变，hash code 改变会导致已有 Meter 无法被 `get()` 找到——内存泄漏 + 指标重复。

---

## 十三、MeterProvider——模板化 Meter 获取（1.12+）

```java
// Meter.MeterProvider（嵌套接口，Meter.java L492，@since 1.12.0）
Meter.MeterProvider<LongTaskTimer> provider = LongTaskTimer.builder("batch.process")
    .tags("service", "order")
    .withRegistry(registry);          // ← Builder.withRegistry（LongTaskTimer.java L486）

LongTaskTimer t = provider.withTag("region", region);  // ← 按动态 Tag 获取/创建
```

本质是"builder 一次 + 动态 Tag 多次"的模板模式——避免每次写 `registry.timer("x", "region", region)` 的重复代码。**API 形态注意**：没有 `MeterProvider.of(Class)`——入口是各 Builder 的 `withRegistry(registry)`；接口方法为 `withTags/withTag`。

---

## 十四、生产场景量化分析

### 场景一：100 个 Meter 的内存占用

```
每个 Counter = AtomicDouble(24 bytes) + Meter.Id(~200 bytes) + Tag overhead(~80 bytes/replica)
    = ~300 bytes/Meter
100 Counter × 300 bytes = ~30KB (微)
```

但如果每个 Counter 有 100 个 tag 组合（如 `{method×10, status×5}` = 50 个组合），每个组合一个 Child——实际是 5000 个 Counter：

```
5000 × 300 bytes = ~1.5MB (仍然可控)
```

### 场景二：Timer percentiles 内存 = TimeWindowPercentileHistogram 固定大小

percentiles 用的是 `TimeWindowPercentileHistogram`（预分桶），不是存全量样本。固定大小桶数组——约 1KB/Timer，与 QPS 无关。

### 场景三：Composite 的 10 个 Registry × 1000 个 Meter

```
每个 Meter 创建时 → getOrCreateMeter → onMeterAdded 回调 → 广播到 10 个叶子 Registry
每个叶子 Registry 内部创建 1 个 Meter → 1000 × 10 = 10000 个 Meter
但 Composite 自身只存 CompositeMeter（1 个引用）→ O(1) 的内存开销
```

---

## 十五、跨框架对比

| 维度 | Micrometer MeterRegistry | Dropwizard MetricRegistry | OpenTelemetry MeterProvider |
|------|---------|---------|---------|
| **抽象模式** | Facade + 模板方法 + Composite | 单一 Registry + Reporter | Builder + Reader（pull/push 统一） |
| **Tag 模型** | `Tags` + `Meter.Id`（不可变） | 无原生 Tag——编码到 name | `Attributes`（可变，比 Tags 更通用） |
| **多后端路由** | `CompositeMeterRegistry` 递归嵌套 | 不支持——需手动多 Reporter | `PeriodicExportingMetricReader` 多 reader 模式 |
| **Clock 抽象** | `wallTime()/monotonicTime()` 分离 | 无抽象——直接 `System.nanoTime()` | `Clock` + `SystemClock` / `ManualClock` |
| **指标类型** | 6 种（Counter/Gauge/Timer/LTT/Summary/Other） | 5 种（Counter/Gauge/Timer/Histogram/Meter） | 6 种 Sync + 6 种 Async（更细粒度） |
| **命名约定** | 惰性转换——`getConventionName()` | 无——name 即最终输出 | `View` 机制——更强大的重命名/过滤 |

关键差异：Micrometer 的模板方法让每个后端有独立数据存储（Prometheus 用 CollectorRegistry，JMX 用 MBeanServer），而 OTel 的 SDK 统一存储数据，通过 Reader 导出。Micrometer 的优势是后端隔离（Prometheus 崩溃不影响 JMX），劣势是跨后端聚合困难（Composite 只能广播——无法对多个后端的 Counter 求 sum）。

---

## 十六、面试核心考点

### Q1: Micrometer 比 Dropwizard Metrics 解决了什么根本问题？

Tag 作为一等公民——维度独立存在而非编码在 metric name 中。Prometheus 等后端可按任意维度组合聚合。这是"指标模型从一维线性到多维"的范式转换。加上 Facade 模式实现多后端独立存储——Dropwizard 的 Reporter 模型无法支持 Prometheus pull 和 JMX push 同时运行。

### Q2: MeterRegistry 的模板方法有几个 abstract？为什么不用一个统一方法？

**9 个 abstract** + 3 个 concrete = 12 个扩展点。Counter 只需要 Id，Timer 需要 DistributionStatisticConfig + PauseDetector，Gauge 需要被观察对象 + ToDoubleFunction——参数集完全不同，强行用 `Object config` 放弃编译期检查。模板方法用不同方法签名保证类型安全。

### Q3: IdentityHashMap 去重 vs HashSet 去重？为什么用前者？

Composite 用 `IdentityHashMap`（`==` 去重）——相同配置的两个 PrometheusMeterRegistry 实例可以共存（`equals()` 可能相同但引用不同）。`HashSet`（`equals()` 去重）会把配置相同的实例当成同一个——多后端路由需要前者："相同类型的 Registry 可以被添加多次"。

### Q4（陷阱）: 为什么 `getOrCreateMeter` 用 `synchronized` 而不用 `ConcurrentHashMap.computeIfAbsent`？

`computeIfAbsent` 的 lambda 可能被多次执行（CAS 冲突重试）→ `onMeterAdded.accept(m)` 这个有副作用的外部回调会被重复调用——监听器收到重复通知。显式 `synchronized` 保证副作用只触发一次。

### Q5（陷阱）: `remove(meter)` 之后再用同样的 name+tags 创建同名 Meter——结果是新 Meter 还是旧 Meter？

新 Meter（count=0）。`remove()` 从 `meterMap` 中删除映射——下一次 `registerMeterIfNecessary` 走创建分支生成新 Counter。旧 Meter 的历史数据永久丢失——这是 remove 的设计意图："丢了这个 Meter 的所有数据"。

### Q6: `commonTags` 为什么对已创建的 Meter 无效？

`commonTags` 本质是注册 `MeterFilter`——在 `map(id)` 阶段追加 Tag。`map(id)` 只在 `getOrCreateMeter` 中 new Meter 创建时调用——已创建的 Meter 不会重新走 `map(id)`。所以全局 Tag 只对后续创建的 Meter 生效。
