# D8: PushMeterRegistry——推模式的两种继承路线

> **阅读顺序**: P2 进阶——理解 Micrometer push 后端的两条继承路线及其 publish() 契约
> **源码版本**: micrometer-core **v1.12.5**（tag；运行时对齐 Boot 3.2.5，与 xhs-project 一致；1.13+ 新特性以注记标出）
> **核心文件**:
> - `PushRegistryConfig.java` (111 行)——step/enabled/batchSize 配置契约
> - `DatadogMeterRegistry.java`——StepMeterRegistry → publish() 四步标准模式
> - `DropwizardMeterRegistry.java`——Micrometer Meter → Dropwizard Metric 桥接
> - `JmxMeterRegistry.java` / `GraphiteMeterRegistry.java`——Dropwizard 桥接后端实例

> **注意**: D7 已经分析了 PushMeterRegistry 的**内部调度机制**（Semaphore/随机偏移/双线程/关闭链）。D8 聚焦 **publish() 契约**和**具体后端的两种继承路线**——如何从 Meter 数据到后端格式。

---

## 一、PushRegistryConfig——推送注册表的配置契约

```java
// PushRegistryConfig.java
public interface PushRegistryConfig extends MeterRegistryConfig {
    default Duration step()      { return getDuration(this, "step").orElse(Duration.ofMinutes(1)); }   // ← 1min
    default boolean enabled()    { return getBoolean(this, "enabled").orElse(true); }                  // ← 默认开启
    default int batchSize()      { return getInteger(this, "batchSize").orElse(10000); }               // ← 10000/批

    @Deprecated default int numThreads() { ... }           // ← 1.1.13 废弃——不再使用
    @Deprecated default Duration connectTimeout() { ... }  // ← 废弃——交给 HttpSender 配置
    @Deprecated default Duration readTimeout() { ... }     // ← 废弃——同上

    default Validated<?> validate() { ... }                // ← 校验 step/batchSize
}
```

**配置项解析**：

- **`step()`**：推送间隔——不仅控制 publish 频率，还作为 StepMeterRegistry 的 step 长度（如果走 StepMeterRegistry 继承线）
- **`enabled()`**：`false` → PushMeterRegistry.start() 不启动调度器 → 零 push
- **`batchSize()`**：一次 HTTP POST 最多发送多少个 Meter——超出则分批。Datadog API 有请求体大小限制，10000 是经验值
- **`validate()`**：检查 step 和 batchSize 必须 > 0——防止配置错误导致零间隔推送（打爆后端）

---

## 二、三条继承路线——push 后端的架构选择

```
MeterRegistry
    ├── PushMeterRegistry
    │       ├── StepMeterRegistry           ← 路线一：步进聚合 + push
    │       │       ├── DatadogMeterRegistry     (HTTP POST)
    │       │       ├── InfluxMeterRegistry      (HTTP POST)
    │       │       ├── SignalFxMeterRegistry    (HTTP POST)
    │       │       ├── StackdriverMeterRegistry (gRPC)
    │       │       └── 共 14 个子类（grep 实证）...
    │       │
    │       └── OpenTSDB / Otlp / Wavefront ← 路线三：直接 push（无 step 聚合）
    │
    └── DropwizardMeterRegistry             ← 路线二：Dropwizard 桥接
            ├── JmxMeterRegistry                 (MBean, 构造器中创建 reporter)
            └── GraphiteMeterRegistry            (TCP push)
```

**注**：Prometheus（1.12.5 旧版）是 pull 模式（D5），Atlas 直接写 Spectator Registry——都不在 push 之列。1.12.5 实测（grep）：`extends StepMeterRegistry` **14 个**、`extends DropwizardMeterRegistry` **2 个**（jmx/graphite）、`extends PushMeterRegistry` 直接 **3 个**（opentsdb/otlp/wavefront——用 push 调度但**不做 step 聚合**，发送累计值）。

**两条路线的本质区别**：

| 维度 | Micrometer 原生路线 | Dropwizard 桥接路线 |
|------|------|------|
| **继承** | `extends StepMeterRegistry` | `extends DropwizardMeterRegistry extends MeterRegistry` |
| **步进聚合** | StepValue + rollCount() CAS——原生实现 | **无**——Dropwizard Counter 是累计值，Reporter 自己算 rate |
| **publish() 实现** | 遍历 Meter → visit 模式序列化 → HTTP/TCP/gRPC 发送 | 遍历 Meter → 转 Dropwizard Metric → 注册到 `MetricRegistry` → 调 `Reporter.report()` |
| **push 触发** | PushMeterRegistry.scheduleAtFixedRate（D7 §二） | Dropwizard ScheduledReporter.start()（独立调度器） |
| **数据格式** | 每个后端自定义——Datadog JSON/KairosDB Telnet/Influx Line Protocol | Reporter 定义——Graphite plaintext/JMX MBean |

**为什么同时存在两条路线？**

Micrometer 的创建初衷是"取代 Dropwizard"——但它保留了 Dropwizard 的 Reporter 生态。JMX 和 Graphite 的 Dropwizard Reporter 已经有十年稳定性——重写风险高于桥接。Datadog/Influx/SignalFx 是新一代后端——走 Micrometer 原生路线，避免 Dropwizard 中间层。

---

## 三、Micrometer 原生路线的 publish() 契约——以 Datadog 为例

### 3.1 publish() 的四步标准模式

```java
// DatadogMeterRegistry.java L108-L146
@Override
protected void publish() {
    String endpoint = config.uri() + "/api/v1/series?api_key=" + config.apiKey();

    try {
        // Step 1: 分批——MeterPartition.partition(this, batchSize)
        for (List<Meter> batch : MeterPartition.partition(this, config.batchSize())) {

            // Step 2: 序列化——meter.match() visitor 模式
            String body = batch.stream().flatMap(meter -> meter.match(
                m -> writeMeter(m, metadata),        // Gauge/Counter → writeMeter
                timer -> writeTimer(timer, metadata), // Timer → writeTimer (sum/count/avg/max)
                summary -> writeSummary(...),         // DistributionSummary
                ...
            )).collect(joining(",", "{\"series\":[", "]}"));

            // Step 3: HTTP POST
            httpClient.post(endpoint)
                .withJsonContent(body)
                .send()
                .onSuccess(response -> logger.debug("sent {} metrics", batch.size()))
                .onError(response -> logger.error("failed: {}", response.body()));
        }
    } catch (Throwable e) {
        logger.warn("failed to send metrics to datadog", e);     // Step 4: 吞异常——不影响下次 push
    }
}
```

**MeterPartition.partition()**：按 `batchSize` 将所有 Meter 分成批次——如果 registry 有 50000 个 Meter 且 batchSize=10000，则分 5 批。每批独立 HTTP POST——防止单次请求体超过 API Gateway 限制。

**meter.match() 的 visitor 模式**：`match()` 的 9 个 Function 参数在序列化时成为"按 Meter 类型分派"的开关——Counter 序列化为 `{"metric":"order.count","type":"count"}`，Timer 序列化为 `count+sum+avg+max` 四个独立 time series + Histogram buckets（如果启用）。

### 3.2 为什么 publish() 要吞异常？

`publishSafelyOrSkipIfInProgress()` 已经 catch 了 Throwable（D7 §二）。但 publish() 内部还有一层 catch——**目的不是防止 crash（外层已经兜底了），而是防止一次失败导致整个 batch 被跳过**。

如果序列化单个 Timer 时出错（如 NPE），publish() 的 catch 确保剩余的 Meter 仍然被发送。同时，"部分成功"的 metadata 在 catch 块之后的 `metadataToSend.forEach(...)` 中单独发送。

### 3.3 其他原生后端的序列化差异

| 后端 | 协议 | writeMeter 格式 |
|------|------|------|
| **Datadog** | HTTP POST JSON | `{"metric":"name","points":[[ts,val]],"tags":[...]}` |
| **InfluxDB** | HTTP POST Line Protocol | `cpu,host=server01 value=0.42 1434055562000000000` |
| **Graphite** | TCP plaintext | `servers.prod.cpu.usage 42 1434055562` |
| **SignalFx** | HTTP POST JSON | SignalFx 独有的 `metricType` + `dimensions` 格式 |
| **Stackdriver** | gRPC CreateTimeSeries | Google Monitoring API v3 proto |

所有后端的 publish() 遵循同一模式：`for batch in partition → flatMap(serialize) → send → catch`。差异只在序列化格式和传输协议。

---

## 四、Dropwizard 桥接路线——JMX 和 Graphite 的十年稳定方案

### 4.1 DropwizardMeterRegistry——桥接核心

```java
// DropwizardMeterRegistry.java
public abstract class DropwizardMeterRegistry extends MeterRegistry {
    private final com.codahale.metrics.MetricRegistry dropwizardRegistry;

    // 模板方法桥接（1.12.5 实际包装类名）：
    protected Counter newCounter(Meter.Id id) {
        // 1. 创建 Dropwizard Counter
        com.codahale.metrics.Counter counter = dropwizardRegistry.counter(name);
        // 2. 包装为 Micrometer DropwizardCounter（L86）
        return new DropwizardCounter(id, counter);
    }

    // newTimer → DropwizardTimer（L112）
    // newGauge → DropwizardGauge（L106）
    // newDistributionSummary → DropwizardDistributionSummary / FunctionTimer → DropwizardFunctionTimer
    // ... 所有 9 个模板方法都桥接到 com.codahale.metrics.MetricRegistry
}
```

**关键**：DropwizardMeterRegistry **不继承 PushMeterRegistry**——它有自己的启动/调度机制：

```java
// JmxMeterRegistry.java L55-L62
// reporter 在构造器中创建：JmxReporter.forRegistry(metricRegistry).inDomain(config.domain()).build()
public void start() {
    this.reporter.start();   // ← Dropwizard JmxReporter 自己的调度/注册
}
public void stop() {
    this.reporter.stop();
}
```

### 4.2 JMX——通过 MBean 暴露

```java
// JmxMeterRegistry.java
// extends DropwizardMeterRegistry
// 不是 "push"（主动发送）——是 "pull"（被动查询）通过 JMX MBean
```

JmxMeterRegistry 是"名义上的 push 后端"——实际行为更接近 pull。JmxReporter 将 Dropwizard Metric 注册为 MBean → JConsole/VisualVM 主动查询 MBean → 读取当前值。但从架构上，Dropwizard Reporter 在 start() 时启动，在 stop() 时停止——生命周期与 push 相同。

### 4.3 Graphite——TCP Plaintext Protocol

```java
// GraphiteMeterRegistry.java
public void start() {
    if (config.enabled()) {
        reporter.start(config.step().getSeconds(), TimeUnit.SECONDS);  // ← interval=step(秒)
    }
}
public void close() {
    if (config.enabled()) {
        reporter.close();
    }
    super.close();
}
```

GraphiteReporter（内部字段，构造器中创建）遍历 `dropwizardRegistry.getMetrics()` → 每个 Metric 序列化为 `prefix.name value timestamp\n` → TCP write。**Graphite 不用 Micrometer 的 push 调度器——用 Dropwizard Reporter 自己的 ScheduledReporter 调度器**。

---

## 五、Push 后端什么时候用 StepMeterRegistry 路线的完整继承？

```
AppOptics, AzureMonitor, CloudWatch, CloudWatch2, Datadog, Dynatrace, Elastic, Ganglia, 
Humio, Influx, Kairos, NewRelic, SignalFx, Stackdriver
    → extends StepMeterRegistry (14 个，grep 实证)

OpenTSDB, Otlp, Wavefront
    → extends PushMeterRegistry（直接——push 调度 + 累计值，不做 step 聚合）

JMX, Graphite
    → extends DropwizardMeterRegistry (2 个)
    → 使用 Dropwizard ScheduledReporter 调度器
    → Micrometer Meter → Dropwizard Metric → Dropwizard Reporter

Atlas
    → extends MeterRegistry（不 push 也不 step——直接写 Spectator Registry）
    
Prometheus（新版）
    → extends MeterRegistry（pull 模式——D5 分析过）
```

**设计规律**：HTTP API 后端 → extends StepMeterRegistry。传统 TCP/UDP 监控协议 → extends DropwizardMeterRegistry。Atlas 和 Prometheus 是特例（各有独特的数据模型）。

---

## 六、PushMeterRegistry 的 enabled/disabled 生命周期

```java
// PushMeterRegistry.java L105
public void start(ThreadFactory threadFactory) {
    if (config.enabled()) {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduledExecutorService.scheduleAtFixedRate(
            this::publishSafelyOrSkipIfInProgress, ...);
    }
    // enabled=false → 不启动调度器 → 零 push！
}
```

**`enabled=false` 的场景**：
- 开发环境不需要 push → `management.metrics.export.{backend}.enabled=false`
- A/B 测试——一个实例走 Datadog，一个实例走 Prometheus
- 本地调试——不想污染生产监控数据

**风险**：enabled=false 时 Meter 仍然正常创建和记录——数据在内存中累积但不发送。如果长时间运行后 enabled 改为 true，第一次 publish 会发送**所有累积数据**——后端可能收到超大批次。生产环境不建议运行时切换 enabled。

---

## 七、PushGateway——Prometheus 的 push 模式补充

Prometheus 原生是 pull 模式，但有 PushGateway 支持短期任务（batch job）：

```java
// PushGateway 是 simpleclient 的类（io.prometheus.client.exporter.PushGateway）
// ——不是 Micrometer 的 PushMeterRegistry 子类，接收的是 CollectorRegistry！
PushGateway pushGateway = new PushGateway("localhost:9091");
pushGateway.pushAdd(prometheusMeterRegistry.getPrometheusRegistry(), "batch_job");
// ↑ 用 PrometheusMeterRegistry.getPrometheusRegistry()（D5 §二）取底层 simpleclient 注册表
```

**边界澄清（1.12.5 事实）**：
- Micrometer 1.12.5 **没有**自己的 PushGateway 类——用的是 simpleclient 的 `io.prometheus.client.exporter.PushGateway`，它操作 `CollectorRegistry`（不是 Micrometer MeterRegistry）；
- Spring Boot 的集成是 `PrometheusPushGatewayManager`（`PrometheusMetricsExportAutoConfiguration.PrometheusPushGatewayConfiguration`，条件 `management.prometheus.metrics.export.pushgateway.enabled=true`）——它拿 `CollectorRegistry` 定时 push；
- 适用于生命周期 < scrape 间隔的短期任务（CronJob/Spark Job），push 后需自行 `delete()` 清理。

## 八、跨框架对比

| 维度 | Micrometer PushMeterRegistry | Dropwizard ScheduledReporter | OTel OTLP Exporter |
|------|------|------|------|
| **调度机制** | `ScheduledExecutorService` + Semaphore(1) 防重入 | `ScheduledExecutorService` + synchronized | 独立 exporter thread + backpressure |
| **步进聚合** | StepValue.rollCount() CAS（原生） | Counter 累计值 → rate 由 Reporter 计算 | SDK 内部 TemporalMetricStore |
| **分批** | `MeterPartition.partition(batchSize)` | 无——Reporter 全量 report | `MetricProducer.collect()` 全量 |
| **序列化** | 每个后端自定义（JSON/LineProtocol/Plaintext） | Reporter 定义 | OTLP proto（标准化） |
| **enabled** | `config.enabled()`——不启动调度器 | 无原生 disabled——需手动不调用 start() | `OTelSdkBuilder.setMeterProvider()` |

---

## 九、面试核心考点

### Q1: PushMeterRegistry 为什么有两条继承路线？

Micrometer 保留 Dropwizard Reporter 生态——JMX/Graphite/Ganglia 的 Reporter 已经有十年稳定性。新后端（Datadog/Influx/SignalFx）走 Micrometer 原生路线——避免 Dropwizard 中间层的额外 GC 和锁竞争。

### Q2（陷阱）: enabled=false 时 Meter 还会被创建和记录吗？

会——Meter 照常创建和记录，只是不 push。数据在内存中累积。如果运行时改为 enabled=true，第一次 publish 发送所有累积数据——可能超过 batchSize 导致多个超大批次。

### Q3: 为什么 publish() 需要两个 catch（publishSafelyOrSkipIfInProgress 外层 + publish() 内层）？

外层 catch 防止 publish() 抛异常导致调度器线程 crash。内层 catch 防止单个 Meter 序列化失败导致整个 batch 被跳过——保证"部分成功"。

### Q4: MeterPartition.partition() 为什么需要分批？

单次 HTTP POST 请求体有大小限制（API Gateway 通常 10MB，Datadog API 限制 batch size）。50000 Meter × 平均 500 bytes/Meter = 25MB → 超过任何 API Gateway 限制 → 必须分批。

### Q5: GraphiteMeterRegistry 用的是谁的调度器？

Dropwizard ScheduledReporter 自己的调度器——不是 Micrometer PushMeterRegistry 的调度器。GraphiteMeterRegistry extends DropwizardMeterRegistry → extends MeterRegistry，不经过 PushMeterRegistry。

### Q6: StepRegistryConfig extends PushRegistryConfig，但具体 step() 在哪一层定义？

PushRegistryConfig 定义 `step() default 1min`。StepRegistryConfig 也继承了这个默认——但在 StepMeterRegistry 的 `defaultHistogramConfig()` 中还额外设置 `expiry(step)`。PushMeterRegistry 只用 step 控制调度频率，StepMeterRegistry 用它同时控制调度频率 + 数据聚合窗口 + Histogram 样本过期时间——三个维度的复用。
