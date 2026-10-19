# D5: PrometheusMeterRegistry——/actuator/prometheus 的背后

> **阅读顺序**: P1 重要——Micrometer 到 Prometheus 的完整转换链路
> **源码版本**: micrometer-registry-prometheus **v1.12.5**（本 lab 运行时；由 Boot 3.2.5 BOM 管理，对齐 xhs-project）；新版 `prometheusmetrics`（Micrometer 1.13+）仅作升级对照
> **核心文件**（1.12.5，包 `io.micrometer.prometheus`）:
> - `PrometheusMeterRegistry.java` (625 行)——MeterRegistry 实现（simpleclient 版）
> - `MicrometerCollector.java` (148 行)——extends Collector implements Describable
> - `PrometheusNamingConvention.java` (104 行)——**手动追加 `_total`**
> - `PrometheusConfig.java` (76 行)——含 `histogramFlavor()`
> **说明**: sca-lab 原版按 1.17 新版（`io.micrometer.prometheusmetrics`）重写；本次迁入按 **xhs-project 基线重定基**——Boot 3.2.5 用的是旧版 simpleclient 链路（`io.micrometer.prometheus` + `io.prometheus:simpleclient 0.16.0`）。

---

## 一、本 lab 用哪版？——新旧两套实现

| | **旧版 simpleclient（本 lab 运行时）** | 新版 prometheusmetrics（升级对照） |
|------|------|------|
| **包** | `io.micrometer.prometheus` | `io.micrometer.prometheusmetrics` |
| **PrometheusMeterRegistry** | 625 行 | 670 行 |
| **MicrometerCollector** | extends Collector implements Describable，148 行 | implements MultiCollector，134 行 |
| **底层 client** | `io.prometheus:simpleclient 0.16.0` | `io.prometheus:prometheus-metrics-core`（Client 1.x） |
| **collect() 返回** | `List<MetricFamilySamples>` | `MetricSnapshots` |
| **`_total` 后缀** | **name() 手动追加**（简单 client 不自带） | 不追加（Client 1.x 自动处理，手动追加会被拒绝） |
| **HistogramFlavor** | **有**（Prometheus / VictoriaMetrics vmrange） | 无（硬编码 `le`） |
| **冲突检查** | 按 **tagKeys** 检查 | 按 type 检查 |
| **createdTimestamp** | 无 | `_created`（OpenMetrics） |
| **版本状态** | 1.13.0 起标记 `@Deprecated` | 现行方向 |

**版本对应关系**：Spring Boot **3.2.5 → micrometer 1.12.5 → 旧版 simpleclient**（本 lab / xhs-project）；Boot 3.3+ 引入新版 `prometheusmetrics`。旧版虽已 deprecated，但 1.12.x 上仍是一等公民，且是 xhs-project 实际运行的链路——**本 lab 全部结论以旧版为准**。

---

## 二、双 Registry 架构——Micrometer 层 vs simpleclient 层

```
Micrometer 层（应用侧）                    simpleclient 层（内部）
─────────────────────────              ───────────────────────────
registry.counter("http.requests")      CollectorRegistry
    → Meter.Id{name, tags}                 ├── MicrometerCollector("http_requests_total")
    → registerMeterIfNecessary()           │   ├── children: Map<tagValues, Child>
    → newCounter(id)                       │   │   ["GET"] → lambda → Family(COUNTER)
    → applyToCollector(...)                │   │   ["POST"] → lambda → Family(COUNTER)
    → collectorMap: conventionName→collector
```

- **`MeterRegistry`**：应用看到的 API——Counter、Timer、Gauge 创建/复用/移除
- **`CollectorRegistry`**（`io.prometheus.client.CollectorRegistry`）：simpleclient 的注册表——存储 `Collector`，负责序列化（`metricFamilySamples()`）
- **`MicrometerCollector`**：两者的桥接——一个 Micrometer 逻辑指标对应一个 Collector；内部 `children` 按 **tagValues** 区分不同标签组合的实例

**关键差异（vs 新版）**：旧版 collectorMap 的 key 是 **conventionName**（转换后的指标名），新版 key 是 Meter.Id。旧版按"同名指标共享一个 Collector"组织——所以同名但 tagKeys 不同的 Meter 会冲突（§八）。

---

## 三、旧版模板方法族（new* 全览）

### 3.1 newCounter——PrometheusCounter + lambda Child

```java
// PrometheusMeterRegistry.java L194-L206
@Override
public Counter newCounter(Meter.Id id) {
    PrometheusCounter counter = new PrometheusCounter(id, exemplarSampler);
    applyToCollector(id, (collector) -> {
        List<String> tagValues = tagValues(id);
        collector.add(tagValues,
                (conventionName, tagKeys) -> Stream.of(new MicrometerCollector.Family(
                        Collector.Type.COUNTER, conventionName,
                        new Collector.MetricFamilySamples.Sample(conventionName, tagKeys, tagValues,
                                counter.count(), counter.exemplar()))));
    });
    return counter;
}
```

**设计分析**：`collector.add()` 存的是**闭包**——`counter.count()` 在**抓取时**才被调用（lazy），不是注册时快照。所以 Prometheus 每次 scrape 都能拿到最新值。这是"Child = 函数式接口"的根因：**Child 不是状态容器，是取值函数**。

### 3.2 newGauge——DefaultGauge 的取值闭包

```java
// L330-L341
Gauge gauge = new DefaultGauge<>(id, obj, valueFunction);
applyToCollector(id, (collector) -> {
    List<String> tagValues = tagValues(id);
    collector.add(tagValues, (conventionName, tagKeys) -> Stream.of(
            new MicrometerCollector.Family(Collector.Type.GAUGE, conventionName,
                    new Collector.MetricFamilySamples.Sample(conventionName, tagKeys, tagValues, gauge.value()))));
});
```

### 3.3 newTimer / newDistributionSummary——共用 distribution 采样

```java
// L320-L328（newTimer）
PrometheusTimer timer = new PrometheusTimer(id, clock, distributionStatisticConfig, pauseDetector,
        prometheusConfig.histogramFlavor(), exemplarSampler);
applyToCollector(id, (collector) -> addDistributionStatisticSamples(distributionStatisticConfig, collector,
        timer, timer::lastExemplar, timer::histogramExemplars, tagValues(id), false));
```

`newDistributionSummary`（L208+）结构相同，且**直接**内联了 histogramFlavor 分支（L244-L295）——Prometheus 用 `le`、VictoriaMetrics 用 `vmrange`。

### 3.4 newMeter——5 种 Statistic 分支

```java
// L383-L435（节选）
switch (m.getStatistic()) {
    case TOTAL, TOTAL_TIME: name += "_sum";          // ← Counter
    case MAX:               name += "_max";          // ← Gauge
    case ACTIVE_TASKS:      name += "_active_count"; // ← Gauge
    case DURATION:          name += "_duration_sum"; // ← Gauge
}
// 每个 Sample 追加 statistic tag（statistic="total"/"max"/...）
```

**旧版只有 5 种 Statistic**（TOTAL/TOTAL_TIME/MAX/ACTIVE_TASKS/DURATION）；新版多了 `VALUE` 和 `COUNT`。`newMeter` 是"多 Measurement 自定义 Meter"的兜底路径（普通 Counter/Timer/Gauge 不走这里）。

### 3.5 其余模板方法

| 方法 | 行号 | 产物 |
|---|---|---|
| `newLongTaskTimer` | L343-350 | CumulativeHistogramLongTaskTimer + distribution samples（`_active_count`/`_duration_sum`） |
| `newFunctionTimer` | L352-368 | `_count` + `_sum`（SECONDS） |
| `newFunctionCounter` | L370-381 | COUNTER |
| `getBaseTimeUnit()` | L437-439 | **SECONDS** |

---

## 四、MicrometerCollector——children 按 tagValues 键

```java
// MicrometerCollector.java L35-L85（节选）
class MicrometerCollector extends Collector implements Collector.Describable {
    private final Map<List<String>, Child> children = new ConcurrentHashMap<>();
    private final String help;   // descriptions=false → " "（空格）

    @Override
    public List<MetricFamilySamples> collect() {
        Map<String, Family> families = new HashMap<>();
        for (Child child : children.values()) {
            child.samples(conventionName, tagKeys).forEach(family ->
                families.compute(family.getConventionName(), (name, matching) ->
                        matching != null ? matching.addSamples(family.samples) : family));
        }
        return families.values().stream()
                .map(f -> new MetricFamilySamples(f.conventionName, f.type, help, f.samples))
                .collect(toList());
    }

    interface Child {
        Stream<Family> samples(String conventionName, List<String> tagKeys);
    }
}
```

**两个容易记错的点**：
1. **旧版 Child 也是函数式接口**（`samples(...)` 返回 Stream）——不是"锁保护的对象"（那是更早版本/误传）；新旧差异在**键与返回类型**，不在"对象 vs lambda"；
2. **`help=" "` 的 workaround 旧版同样存在**（L51）——`descriptions=false` 时输出空 HELP，规避 simpleclient 对空 help 的约束。

---

## 五、scrape() 链路——simpleclient TextFormat

```java
// PrometheusMeterRegistry.java L106-L108 / L148-L155 / L188-L192
public String scrape() { return scrape(TextFormat.CONTENT_TYPE_004); }

public void scrape(Writer writer, String contentType) throws IOException {
    scrape(writer, contentType, registry.metricFamilySamples());   // ← CollectorRegistry
}
private void scrape(Writer writer, String contentType, Enumeration<MetricFamilySamples> samples)
        throws IOException {
    TextFormat.writeFormat(contentType, writer, samples);          // ← simpleclient 序列化
}

// 按指标名过滤（includedNames）：
registry.filteredMetricFamilySamples(includedNames)
```

**链路**：`CollectorRegistry.metricFamilySamples()` → 遍历所有 Collector 调 `collect()` → `TextFormat.writeFormat` 输出 TEXT_004。与新版（`ExpositionFormats.findWriter(contentType).write(os, MetricSnapshots)`）不同——旧版只有 simpleclient 的 TextFormat，格式切换能力有限。

---

## 六、命名约定——`_total` 手动追加

```java
// PrometheusNamingConvention.java L52-L86（节选）
public String name(String name, Meter.Type type, @Nullable String baseUnit) {
    String conventionName = NamingConvention.snakeCase.name(name, type, baseUnit);
    switch (type) {  // ① baseUnit 后缀
        case COUNTER: case DISTRIBUTION_SUMMARY: case GAUGE:
            if (baseUnit != null && !conventionName.endsWith("_" + baseUnit))
                conventionName += "_" + baseUnit;
    }
    switch (type) {
        case COUNTER:   // ② 手动追加 _total（simpleclient 不自带）
            if (!conventionName.endsWith("_total")) conventionName += "_total";
            break;
        case TIMER: case LONG_TASK_TIMER:   // ③ _seconds
            if (!timerSuffix.isEmpty() && conventionName.endsWith(timerSuffix))
                conventionName += "_seconds";
            else if (!conventionName.endsWith("_seconds"))
                conventionName += timerSuffix + "_seconds";
    }
    String sanitized = nameChars.matcher(conventionName).replaceAll("_");  // ④ 非法字符→_
    if (!Character.isLetter(sanitized.charAt(0))) sanitized = "m_" + sanitized;  // ⑤ 数字开头→m_
    return sanitized;
}
```

**面试常问**：`http.requests` → `http_requests_total`（snake_case + 手动 `_total`）；Timer → `xxx_seconds`。**注意**：手动追加是**旧版专属**——升级到新版后，Client 1.x 自动处理 `_total`，再手动追加会得到 `xxx_total_total` 并抛 `IllegalArgumentException`（§一表格）。

---

## 七、distribution samples——percentile 与 histogram 双形态

```java
// addDistributionStatisticSamples L449-L553（节选）
// ① percentile（summary 语义）→ quantile tag
quantileValues.add(Collector.doubleToGoString(v.percentile()));
samples.add(new Sample(conventionName, quantileKeys, quantileValues, v.value(TimeUnit.SECONDS)));

// ② histogram → _bucket + le / vmrange
Collector.Type type = distributionStatisticConfig.isPublishingHistogram()
        ? Collector.Type.HISTOGRAM : Collector.Type.SUMMARY;
switch (prometheusConfig.histogramFlavor()) {
    case Prometheus:      histogramKeys.add("le");     // le="0.01" / "+Inf"
    case VictoriaMetrics: histogramKeys.add("vmrange");
}

// ③ 基础三件套
conventionName + "_count" / "_sum" / "_max"（_max 单独 GAUGE family）
```

**设计分析**：一个 Timer 在 Prometheus 里可能同时输出 `quantile`（summary）和 `le`（histogram）——注释原文 "Prometheus doesn't balk at a metric being BOTH a histogram and a summary"。`isPublishingHistogram()` 决定 type 标签；`+Inf` bucket 恒等于 count。

---

## 八、冲突检测与注销——按 tagKeys 检查

```java
// applyToCollector L575-L599（节选）
collectorMap.compute(getConventionName(id), (name, existingCollector) -> {
    if (existingCollector == null) {
        MicrometerCollector c = new MicrometerCollector(id, config().namingConvention(), prometheusConfig);
        consumer.accept(c);
        return c.register(registry);
    }
    List<String> tagKeys = getConventionTags(id).stream().map(Tag::getKey).collect(toList());
    if (existingCollector.getTagKeys().equals(tagKeys)) {
        consumer.accept(existingCollector);   // 同 tagKeys → 复用
        return existingCollector;
    }
    meterRegistrationFailed(id, "Prometheus requires that all meters with the same name have the same set of tag keys...");
    return existingCollector;                 // 默认：记录失败但不抛
});
```

**冲突规则（旧版）**：同名指标必须 **tagKeys 完全一致**——`http_requests{method}` 与 `http_requests{status}` 不能共存。默认行为是 `meterRegistrationFailed`（**静默丢弃**，只记事件）；要"快速失败"需显式 `throwExceptionOnRegistrationFailure()`（L617-L623）。**这是生产常见坑**：指标悄悄消失，日志里才有线索。

**注销**：`onMeterRemoved`（L564-L573）——按 conventionName 找到 collector → `remove(tagValues)` → 空了才 `unregister`。

---

## 九、VictoriaMetrics 支持——旧版有，新版放弃

旧版 `HistogramFlavor`（28 行枚举）两值：`Prometheus`（默认）/ `VictoriaMetrics`。后者输出 `vmrange` 替代 `le`，配合 `FixedBoundaryVictoriaMetricsHistogram.getRangeTagValue()`。

**升级对照**：新版（1.13+）硬编码 `le`，源码注释写着 `"TODO: Add support back for VictoriaMetrics"`——**新版的 VictoriaMetrics 用户会失去 vmrange 输出**。若需要 vmrange：留在旧版，或改用 VictoriaMetrics 原生兼容层。

---

## 十、Spring Boot 3.2.5 集成

```java
// PrometheusMetricsExportAutoConfiguration.java（Boot 3.2.5, L72-L107 节选）
@Bean @ConditionalOnMissingBean
public PrometheusMeterRegistry prometheusMeterRegistry(PrometheusConfig config,
        CollectorRegistry collectorRegistry, Clock clock, ObjectProvider<ExemplarSampler> sampler) {
    return new PrometheusMeterRegistry(config, collectorRegistry, clock, sampler.getIfAvailable());
}

@Bean @ConditionalOnMissingBean
public CollectorRegistry collectorRegistry() {
    return new CollectorRegistry(true);          // ← simpleclient 原生注册表
}

// 端点（@ConditionalOnAvailableEndpoint(PrometheusScrapeEndpoint.class)）
@Bean @ConditionalOnMissingBean
public PrometheusScrapeEndpoint prometheusEndpoint(CollectorRegistry collectorRegistry) {
    return new PrometheusScrapeEndpoint(collectorRegistry);
}
```

- 条件链：`@ConditionalOnClass(PrometheusMeterRegistry.class)` + `@ConditionalOnEnabledMetricsExport("prometheus")`（`management.prometheus.metrics.export.enabled`）
- 配置绑定：`PrometheusPropertiesConfigAdapter` ← prefix **`management.prometheus.metrics.export`**
- `CollectorRegistry(true)` 的 `true` = `autoDescribe`——scrape 时自动输出 HELP/TYPE
- Exemplar：有 `SpanContextSupplier`（tracing 集成）时才创建 `DefaultExemplarSampler`——本 lab 无 tracing 依赖，不走此分支

**本 lab 验证**：`/actuator/prometheus` 由 `PrometheusScrapeEndpoint` 提供（`@WebEndpoint(id="prometheus")`），输出 `a10_slo_request_seconds_bucket{le="1.0"}` 等——A10 的 SLO 桶边界（1s/5s/10s/50s/100s）在此可见。

---

## 十一、跨框架对比

| 维度 | Micrometer（旧版 simpleclient） | OpenTelemetry Prometheus Exporter | Graphite |
|------|------|------|------|
| **数据模型** | Meter.Id + Tag → Collector + Child（tagValues 键） | MetricData → Collector（SDK 内部存储→导出转换） | 平面 name + period-separated dots |
| **命名规则** | `PrometheusNamingConvention`（snake_case + 手动 `_total` + baseUnit） | `otel_` 前缀 + `_total` 自动 | `.<host>.env.metric`（层次命名） |
| **_total 后缀** | **name() 手动追加** | 自动 | 无 |
| **Bucket 格式** | `le`（或 vmrange） | `le`（同 Prometheus） | 无 |
| **createdTimestamp** | 无 | 无 | 无 |

---

## 十二、面试核心考点

### Q1: 为什么旧版命名要手动追加 `_total`？
旧版底层是 simpleclient（`io.prometheus:simpleclient`），它不自动处理 `_total`——`PrometheusNamingConvention.name()` 对 COUNTER 手动追加（L66-L69）。新版换成 Prometheus Client 1.x 后自动处理，手动追加反而抛异常。**所以升级时不能带旧版的命名习惯**。

### Q2（陷阱）: 两个同名 Counter 但 tagKeys 不同（`{method}` vs `{status}`）会怎样？
`applyToCollector` 检查 tagKeys 相等性——不等则调 `meterRegistrationFailed`。**默认不抛异常**（指标静默丢弃，只触发注册失败事件）；要显式失败需调 `throwExceptionOnRegistrationFailure()`。这是"指标在测试环境正常、上生产消失"的经典原因。

### Q3: `getBaseTimeUnit()` 为什么返回 SECONDS？
Prometheus 生态共识——`rate()` / `histogram_quantile()` 假定秒。`Timer.record(100, MILLISECONDS)` 输出时转 0.1s；如果输出毫秒，查询侧要全部乘 0.001。源码 L437-L439 硬编码 SECONDS。

### Q4: scrape 一次发生了什么？
`PrometheusScrapeEndpoint` → `PrometheusMeterRegistry.scrape()` → `CollectorRegistry.metricFamilySamples()` 遍历所有 `MicrometerCollector.collect()` → 每个 Child lambda 取值（lazy）→ `TextFormat.writeFormat` 序列化 TEXT_004。**没有缓存**——每次抓取都全量重算。

### Q5（陷阱）: Timer 为什么同时有 `quantile` 和 `le` 两种输出？
`addDistributionStatisticSamples` 对 percentile 输出 `quantile` tag（summary 语义），对 histogramCounts 输出 `_bucket{le}`（histogram 语义）。源码注释明确说 Prometheus 允许一个指标同时是 histogram 和 summary——所以配置了 percentiles + histogram 时会看到两种样本。

### Q6: 旧版和新版（1.13+）最本质的差异是什么？
**底层 client 换代**：simpleclient → prometheus-metrics-core（Client 1.x）。连带的：`_total` 从手动变自动、`HistogramFlavor`（vmrange）被放弃、collect() 从 `List<MetricFamilySamples>` 变 `MetricSnapshots`、冲突检查从 tagKeys 变 type。旧版 1.13.0 起 deprecated 但仍在维护。

---

## 十三、本篇要点

1. **本 lab / xhs-project = 旧版 simpleclient**（Boot 3.2.5 → 1.12.5）；新版 `prometheusmetrics` 是升级方向——所有结论以旧版为准
2. **双 Registry**：MeterRegistry（应用层）→ MicrometerCollector（桥）→ CollectorRegistry（simpleclient 层）
3. **Child 是取值闭包**（lazy），不是状态对象——旧版新版都是函数式接口
4. **命名**：snake_case + **手动 `_total`** + `_seconds`；升级新版后不能再手动加
5. **冲突按 tagKeys 检查**，默认静默失败——生产排障点
6. **scrape 无缓存**：每次抓取全量 collect + TextFormat 序列化
