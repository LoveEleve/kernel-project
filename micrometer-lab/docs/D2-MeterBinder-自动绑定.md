# D2: MeterBinder——为什么一个方法就够

> **阅读顺序**: P0 必备——理解 Micrometer 如何通过极简 SPI 绑定 JVM/Tomcat/Kafka/PG 等 32 个组件的指标
> **源码版本**: micrometer-core **v1.12.5**（tag；运行时对齐 Boot 3.2.5，与 xhs-project 一致；1.13+ 新特性以注记标出）
> **核心文件**:
> - `MeterBinder.java` (31 行)——只有 `bindTo(MeterRegistry)` 一个方法
> - `binder/` 目录——18 个子包，32 个 implements MeterBinder + 大量非 Binder 观测集成类
> - `JvmGcMetrics.java` (376 行)——最复杂的 Binder 之一（JMX NotificationListener + **Timer**）
> - `KafkaClientMetrics.java` (97 行)——替代已废弃 KafkaConsumerMetrics
> - `PostgreSQLDatabaseMetrics.java` (371 行)——resettableFunctionalCounter（pg_stat_reset 免疫）

---

## 一、MeterBinder 的极致简洁——为什么只有一个方法

```java
// MeterBinder.java
public interface MeterBinder {
    void bindTo(MeterRegistry registry);
}
```

31 行——包括版权声明和注释。没有 `@Order`、没有 `supports()`、没有 `close()`、没有返回值。

**对比 Spring 的典型 SPI**：

| SPI | 方法数 | 需要生命周期？ | 为什么复杂 |
|-----|:--:|:--:|------|
| `MeterBinder` | **1** | 否 | 绑定是一次性的——注册完指标后 Binder 实例可丢弃 |
| `SmartLifecycle` | 4 | 是（start/stop） | 需要知道何时启动和关闭 |
| `InitializingBean` | 1 | 是（afterPropertiesSet） | 需要知道属性何时就绪 |
| `DisposableBean` | 1 | 是（destroy） | 需要知道何时释放资源 |

MeterBinder 不需要这些——**因为 `bindTo` 只做一件事：把指标注册到 Registry。注册完成后 Meter 永久存在于 Registry 中，Binder 实例可以被丢弃。** MeterBinder 不是"管理你的组件的生命周期"——它是"告诉 Registry 你有哪些指标"。

---

## 二、32 个 Binder 的全景分类

`binder/` 目录有 **18 个子包**，**32 个类 implements MeterBinder**：

### 2.1 复杂度分级

| 级别 | 代表 Binder | 行数 | 核心特征 |
|------|------|:--:|------|
| 🔴 重度 | `PostgreSQLDatabaseMetrics` | 371 | SQL 查询统计表 + resettableCounter（PG 版本分支为 1.13+） |
| 🔴 重度 | `JvmGcMetrics` | 376 | JMX NotificationListener + GC 事件驱动 |
| 🔴 重度 | `ExecutorServiceMetrics` | 419 | 反射 + MethodHandle + 三种 Executor 适配 |
| 🔴 重度 | `MetricsDSLContext` | 5107 | jOOQ DSL 包装——binder 目录最大文件 |
| 🔴 重度 | `KafkaConsumerMetrics` | 439 | @Deprecated——被 KafkaClientMetrics(97行) 替代 |
| 🟡 中度 | `KafkaClientMetrics` | 97 | `consumer::metrics` 方法引用 + 60s 调度 |
| 🟡 中度 | `TomcatMetrics` | 406 | 双 JMX 域 + session 对象绑定 |
| 🟡 中度 | `JvmMemoryMetrics` | 103 | MemoryPoolMXBean + BufferPoolMXBean 遍历 |
| 🟡 中度 | `JvmHeapPressureMetrics` | 155 | TimeWindowSum + long-lived pool 使用率 |
| 🟡 中度 | `LogbackMetrics` | 246 | TurboFilter + LongAdder + FunctionCounter |
| 🟡 中度 | `CommonsObjectPool2Metrics` | 282 | MBeanServer + metrics-updater 调度线程 + **AutoCloseable** |
| 🟡 中度 | `HazelcastCacheMetrics` | 251 | 存储空间/near cache/timing（+323 行 HazelcastIMapAdapter）|
| 🟢 轻度 | `CaffeineCacheMetrics` | 214 | `recordStats()` 必须开启 |
| 🟢 轻度 | `EhCache2Metrics` | 244 | 事务/存储空间详情 |
| 🟢 轻度 | `DiskSpaceMetrics` | 75 | `File.getUsableSpace()` |
| 🟢 轻度 | `UptimeMetrics` | 71 | `RuntimeMXBean.getUptime()` → TimeGauge |

### 2.2 Cache 子族——模板方法模式

`CacheMeterBinder<C>`（153行）是 cache 指标的**抽象基类**——统一指标集：

```
cache.size          → Gauge     ← 缓存条目数
cache.gets{hit,miss} → FunctionCounter  ← （命中/未命中 累计）
cache.puts          → FunctionCounter
cache.evictions     → FunctionCounter
```

5 个子类继承：`CaffeineCacheMetrics`（214行）、`EhCache2Metrics`（244行）、`GuavaCacheMetrics`（151行）、`HazelcastCacheMetrics`（251行）、`JCacheMetrics`（162行）。每个子类只需实现特定 cache 库的 API 适配——设计模式是典型的"模板方法 + 统一指标模型"。

---

## 三、JvmGcMetrics——GC 指标的实战深度

### 3.1 JMX NotificationListener 事件驱动（非轮询）

```java
// JvmGcMetrics.java L160-L180（addNotificationListener L168）
for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
    NotificationEmitter emitter = (NotificationEmitter) gcBean;
    GcMetricsNotificationListener listener = new GcMetricsNotificationListener(registry);
    emitter.addNotificationListener(listener,
        notification -> notification.getType().equals(
            GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION),
        null);
    notificationListenerCleanUpRunnables.add(() ->
        emitter.removeNotificationListener(listener));
}
```

JVM 的 GC 是**事件驱动**的——GC 发生 → JVM 发送 Notification → Listener 收到。不能用 `getCollectionCount()` 轮询——因为你需要知道 GC 的类型（Minor/Major/Concurrent）、持续时间（pause time）、触发原因（System.gc vs 分配失败）。

### 3.2 暂停式 GC vs 并发 GC（修正：都用 Timer）

```java
// JvmGcMetrics.java L195-L215（GcMetricsNotificationListener 内部；concurrent L201 / pause L208）
if (info.getGcAction().contains("concurrent")) {
    // 并发 GC → Timer（不是 LongTaskTimer！）
    Timer.builder("jvm.gc.concurrent.phase.time")
        .tags("action", info.getGcAction(), "cause", info.getGcCause())
        .description("Time spent in concurrent phase")
        .register(registry)
        .record(duration, MILLISECONDS);
} else {
    // 暂停式 GC → Timer
    Timer.builder("jvm.gc.pause")
        .tags("action", info.getGcAction(), "cause", info.getGcCause())
        .register(registry)
        .record(duration, MILLISECONDS);
}
```

**关键修正**：JvmGcMetrics 从头到尾只用 `Timer`——无论是 Minor GC pause 还是 G1 concurrent-mark，都用 `Timer.record(duration)`。`LongTaskTimer` 从未出现在 JvmGcMetrics 的任何行。

### 3.3 其他关键指标

```java
// 内存分配与晋升（pool delta）——L147/L154
Counter.builder("jvm.gc.memory.allocated")      // ← 两次 GC 之间分配了多少内存
Counter.builder("jvm.gc.memory.promoted")        // ← 从新生代晋升到老年代的字节数（仅分代 GC）

// 堆大小（L133/L141）
Gauge.builder("jvm.gc.max.data.size", ...)       // ← 老年代最大容量
Gauge.builder("jvm.gc.live.data.size", ...)      // ← GC 后老年代存活量
```

> **版本注记**：`jvm.gc.cpu.time`（`getTotalGcCpuTime()`）是 1.13+ 新增——**1.12.5 没有**（本 lab 基线只有上面 4 个）。

`jvm.gc.memory.allocated` 和 `jvm.gc.memory.promoted` 是**两次 GC 之间的 delta**——在 NotificationListener 的 `handleNotification` 中计算 `currentPoolUsage - previousPoolUsage`。这是 GC 分析中最有价值的指标——可以看到每次 GC 之间应用实际分配了多少内存。

### 3.4 AutoCloseable + 清理

`JvmGcMetrics implements AutoCloseable`（一行中），`close()` 遍历 `notificationListenerCleanUpRunnables` 逐个移除 JMX NotificationListener——防止应用关闭后 JVM 仍在尝试回调已销毁的 listener。32 个 Binder 中只有 10 个需要 `AutoCloseable`——规律：**注册了事件监听器或启动了内部线程的才需要 close()**。

---

## 四、JvmMemoryMetrics + JvmHeapPressureMetrics——真相

### 4.1 JvmMemoryMetrics 遍历 MemoryPoolMXBean（非 MemoryMXBean）

```java
// JvmMemoryMetrics.java L60-L100（1.12.5：无 MeterConvention，直接 Gauge.builder）
// Layer 1: BufferPool（Direct/NIO 缓冲区）
Gauge.builder("jvm.buffer.count", bufferPoolBean, BufferPoolMXBean::getCount)
Gauge.builder("jvm.buffer.memory.used", bufferPoolBean, BufferPoolMXBean::getMemoryUsed)
Gauge.builder("jvm.buffer.total.capacity", bufferPoolBean, BufferPoolMXBean::getTotalCapacity)

// Layer 2: 内存池（Eden/Survivor/Old/Metaspace/CodeCache）
for (MemoryPoolMXBean memoryPoolBean : ManagementFactory.getPlatformMXBeans(MemoryPoolMXBean.class)) {
    String area = MemoryType.HEAP.equals(memoryPoolBean.getType()) ? "heap" : "nonheap";
    Iterable<Tag> tagsWithId = Tags.concat(tags, "id", memoryPoolBean.getName(), "area", area);
    Gauge.builder("jvm.memory.used", memoryPoolBean, (mem) -> getUsageValue(mem, MemoryUsage::getUsed))
    Gauge.builder("jvm.memory.committed", memoryPoolBean, (mem) -> getUsageValue(mem, MemoryUsage::getCommitted))
    Gauge.builder("jvm.memory.max", memoryPoolBean, (mem) -> getUsageValue(mem, MemoryUsage::getMax))
}
```

**根本不用 `MemoryMXBean.getHeapMemoryUsage()`**——而是遍历每个内存池（Eden/Survivor/Old/Metaspace/CodeCache/Compressed Class Space），每池 3 个 Gauge。tag `area` 区分 heap vs nonheap，tag `id` 区分具体池名。

### 4.2 MeterConvention——双命名体系（**1.13+ 特性，本 lab 基线无**）

> **版本注记**：1.12.5 的 `JvmMemoryMetrics` 只有 `()` 与 `(Iterable<Tag>)` 两个构造器——**没有** MeterConvention 支持；下表的双命名体系是 1.13+ 新增（升级对照）。

`JvmMemoryMetrics` 支持两套命名约定：

| Convention | `jvm.memory.used` 对应名 |
|-----------|------|
| `MicrometerJvmMemoryMeterConventions` | `jvm.memory.used` |
| `OpenTelemetryJvmMemoryMeterConventions` | `process.runtime.jvm.memory.used` |

其他 JVM Binder 也是同样的双命名模式：`JvmGcMeterConventions` × {Micrometer, OTel}、`JvmThreadMeterConventions` × {Micrometer, OTel} 等。

### 4.3 JvmHeapPressureMetrics——GC 压力指标

`JvmHeapPressureMetrics` 注册两个隐蔽但重要的指标：

1. **`jvm.gc.overhead`**：基于 `TimeWindowSum`——最近 1 分钟内 GC 总耗时 ÷ 60s = GC 开销百分比（如 0.03 = 3% GC overhead）
2. **`jvm.memory.usage.after.gc`**：每次 GC 后 long-lived pool（如 Old Gen）的使用率——**这个值不降说明 Old Gen 在泄漏**

被 `JvmMetricsAutoConfiguration` 自动注册但因其隐蔽性常被忽略——这是 GC 调优中最关键的指标。

---

## 五、KafkaClientMetrics——替代废弃的 KafkaConsumerMetrics

```java
// KafkaClientMetrics.java（97行，1.12.5）替代已废弃的 KafkaConsumerMetrics(439行)
public KafkaClientMetrics(Consumer<?, ?> kafkaConsumer, Iterable<Tag> tags) {
    super(kafkaConsumer::metrics, tags);       // ← 直接方法引用！（L68，super 在 L52）
}
```

`KafkaClientMetrics` 不是通过 JMX MBeanServer 接入——直接用 `consumer.metrics()` 方法引用调用 Kafka Consumer 的原生 `metrics()` API。这是 Micrometer 从 JMX 模式到直连 API 模式的演进。

与其他 Kafka 相关的集成：
- `KafkaStreamsMetrics`——Kafka Streams 状态存储指标
- 非 Binder 集成类——`MicrometerConsumerInterceptor`/`MicrometerProducerInterceptor`（Kafka 原生 Interceptor 机制拦截每条消息并打点）

---

## 六、PostgreSQLDatabaseMetrics——resettableFunctionalCounter

### 6.1 只查询 5 张统计表，不是 150+ 个指标

```java
// PostgreSQLDatabaseMetrics.java Names 常量——19 个精选指标
pg_stat_database        → connections, rows.*, temp.writes, blocks.*, transactions
pg_stat_user_tables     → rows.dead（死元组数）
pg_stat_bgwriter        → checkpoints.*, buffers.*
pg_stat_checkpointer    → PG17+ 专用（num_timed, num_requested）
pg_stat_io              → PG17+ 专用（SUM(writes)）
```

不是"150+ PG 统计指标"——是**精选 19 个**，来自 5 张统计表。每个统计量用 `DoubleSupplier` 包装 SQL 查询。

### 6.2 resettableFunctionalCounter——核心算法

**问题**：DBA 执行 `pg_stat_reset()` → 所有累计计数器归零 → FunctionCounter 看到值从 10000 掉到 0 → Prometheus 算出 -10000 的"负增量"。

**解法**——双 Map 维护"重置后依然单调"的值：

```java
// PostgreSQLDatabaseMetrics.java（简化逻辑）
Double resettableFunctionalCounter(String key, DoubleSupplier function) {
    Double result = function.get();
    Double beforeReset = beforeResetValuesCacheMap.get(key);
    Double corrected = result + (beforeReset == null ? 0 : beforeReset);

    Double previous = previousValueCacheMap.get(key);
    if (previous != null && corrected < previous) {      // 检测到重置
        beforeResetValuesCacheMap.put(key, previous);    // 把重置前的累计值"记下来"
        corrected = previous + result;                    // 新值 = 旧累计 + 当前读数
    }

    previousValueCacheMap.put(key, corrected);
    return corrected;
}
```

**面试陷阱**：不处理 pg_stat_reset 的监控系统会在 DBA 维护窗口后看到全负数 spiky 图表——这是生产环境中真实发生过的故障。

### 6.3 PG17 版本适配（**1.13+ 特性，本 lab 基线无**）

> **版本注记**：1.12.5 的 PostgreSQLDatabaseMetrics 只查 `pg_stat_bgwriter`（源码 L336）——**没有** PG17 的 `pg_stat_checkpointer`/`pg_stat_io` 版本分支（1.13+ 新增）。以下代码为升级对照：

```java
private DoubleSupplier getBuffersCheckpointSupplier() {
    if (serverVersion.isAtLeast(Version.V17)) {
        return () -> runQuery(CHECKPOINTER_BUFFERS_WRITTEN);  // pg_stat_checkpointer（PG17）
    }
    return () -> runQuery(QUERY_BUFFERS_CHECKPOINT);           // pg_stat_bgwriter（PG16-）
}
```

不同 PG 版本有不同的统计表——Micrometer 用 `Version` 枚举（PG10→PG17 递增）做编译时不可知但运行时可知的版本分支。

---

## 七、TomcatMetrics + ExecutorServiceMetrics——适配的艺术

### 7.1 TomcatMetrics——双 JMX 域

```java
// TomcatMetrics.java
private static final String JMX_DOMAIN_EMBEDDED = "Tomcat";      // Spring Boot 内嵌
private static final String JMX_DOMAIN_STANDALONE = "Catalina";  // 独立 Tomcat

// 自动探测：先查 Tomcat:type=Server，再查 Catalina:type=Server
private String getJmxDomain() { ... }
```

**5 类指标**：

| 类别 | 指标 | 采集方式 |
|------|------|------|
| **GlobalRequest** | bytesSent/errorCount/requestCount + FunctionTimer | JMX 属性 |
| **Servlet** | j2eeType=Servlet 的 requestCount/errorCount/processingTime | JMX 属性 |
| **Cache** | cacheSize/hitCount/accessCount | JMX 属性 |
| **ThreadPool** | maxThreads/currentThreadsBusy/currentThreadCount/connectionCount/keepAliveCount/maxConnections | JMX 属性（6 Gauge）|
| **Session** | activeSessions/sessionCounter/maxActive | **直接对象引用**（`Manager` 接口的方法引用，不用 JMX）|

Session 不走 JMX——`Manager` 接口的 `getActiveSessions()`/`getSessionCounter()`/`getMaxActive()` 直接作为 `ToDoubleFunction` 传给 Gauge。这是**对象引用 vs JMX** 的两种采集模式并存。

### 7.2 ExecutorServiceMetrics——为什么 419 行

```java
// ExecutorServiceMetrics.java
public static ExecutorService monitor(MeterRegistry registry, ExecutorService executor, ...) {
    if (executor instanceof ScheduledExecutorService) {
        return monitor(registry, (ScheduledExecutorService) executor, ...);  // ← 包含 schedule 指标
    }
    if (executor instanceof ThreadPoolExecutor) {
        return monitor(registry, (ThreadPoolExecutor) executor, ...);       // ← pool size/queue size
    }
    return new TimedExecutorService(registry, executor, ...);               // ← 普通 Executor（仅 active 线程）
}
```

**419 行的根本原因**——Java Executor 体系没有统一接口暴露内部状态：

- `ThreadPoolExecutor.getQueue().size()`——队列深度
- `ForkJoinPool.getQueuedSubmissionCount()`——**完全不同的 API**
- `ForkJoinPool.getDelayedTaskCount()`——需要 **MethodHandle + `--add-opens`**（Java 16+ 标记为 protected）
- `ScheduledThreadPoolExecutor.getQueue()`——`DelegatedScheduledExecutorService` 包装后无法直接访问

每个类型需要单独的反射/适配逻辑。Micrometer 不能"修复 Java 标准库的设计缺陷"——它只能绕过去。`unwrapThreadPoolExecutor` 方法处理 8 种包装类（Delegated/FinalizableDelegated/AutoShutdownDelegated...）。

---

## 八、Spring Boot 侧的真实绑定流程——deferBinding 机制

```java
// MeterRegistryPostProcessor.java（Spring Boot Actuator）
class MeterRegistryPostProcessor implements BeanPostProcessor, SmartInitializingSingleton {

    private volatile boolean deferBinding = true;
    private final Set<MeterRegistry> deferredBindings = new LinkedHashSet<>();   // ← Boot 3.2.5 用 Set

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof MeterRegistry meterRegistry) {
            // 1. applyCustomizers()——MeterRegistryCustomizer（全局 tag 等，先于 Binder）
            // 2. applyFilters()——MeterFilter
            // 3. addToGlobalRegistryIfNecessary()——management.metrics.use-global-registry
            // 4. applyBinders()——但如果是 deferBinding，先挂起
            postProcessMeterRegistry(meterRegistry);
        }
        return bean;
    }

    void applyBinders(MeterRegistry registry) {
        if (this.deferBinding) {
            synchronized (this.deferredBindings) {   // ← 双检 + 挂起（Boot 3.2.5）
                if (this.deferBinding) {
                    this.deferredBindings.add(registry);
                    return;
                }
            }
        }
        this.binders.orderedStream().forEach(
            binder -> binder.bindTo(registry));   // ← 无 try-catch，异常直接传播
    }

    @Override
    public void afterSingletonsInstantiated() {   // ← 关键时刻：所有单例已创建
        synchronized (this.deferredBindings) {
            this.deferBinding = false;
            this.deferredBindings.forEach(this::applyBinders);  // ← 统一绑定时序
        }
    }
}
```

**为什么需要 deferBinding？**——Registry 可能在 Binder 之前创建。如果 Registry 创建时立刻 bindTo，Binder 引用的其他 Bean（如 `GarbageCollectorMXBean`/`MemoryPoolMXBean`）可能尚未初始化——导致指标缺失或不完整。

---

## 九、非 Binder 的观测集成类——Interceptor/EventListener 族

`binder/` 目录下还有大量类**不直接 implements MeterBinder**，而是通过框架的 Interceptor/EventListener 机制集成：

| 包 | 类 | 集成方式 |
|------|------|------|
| `grpc` | `MetricCollectingClientInterceptor` | gRPC ClientInterceptor |
| `httpcomponents` | `MetricCollectingHttpClientInterceptor` | Apache HttpClient HttpRequestInterceptor |
| `okhttp3` | `OkHttpMetricsEventListener` | OkHttp EventListener |
| `mongodb` | `MongoMetricsCommandListener` | MongoDB CommandListener |
| `jersey` | `TimedHandler` | Jersey ContainerResponseFilter |

这些类的共同模式：**在框架调用链路中插入 Micrometer 打点**——不是 Binder 的"一次性注册"模式，而是"每次请求都记录"模式。它们依赖 Binder 之外的 Bootstrap 机制（如 gRPC `ClientInterceptor` 注册、OkHttp `EventListener.Factory` 配置）。

---

## 十、AutoCloseable 规律——10/32 Binder 需要 close()

| Binder | close() 做什么 |
|--------|------|
| `JvmGcMetrics` | 移除 JMX NotificationListener |
| `JvmHeapPressureMetrics` | 同上 |
| `KafkaConsumerMetrics` | 移除 MBeanServerDelegate listener |
| `KafkaMetrics` | `scheduler.shutdownNow()` + `registry.remove(id)` |
| `TomcatMetrics` | 移除 delegation notification listeners |
| `LogbackMetrics` | 移除 LoggerContext listener + TurboFilter |
| `Log4j2Metrics` | 移除 LoggerContext listener |
| `CommonsObjectPool2Metrics` | 停止 metrics-updater 调度器 |
| `JettyServerThreadPoolMetrics` | 移除事件监听器 |
| `JettyConnectionMetrics` | 同上 |

**规律**：凡是**注册了 JMX/事件监听器、或启动了内部线程/调度器**的才需要 close()。纯 getter 型 Binder（JvmMemoryMetrics/JvmThreadMetrics/UptimeMetrics/ProcessorMetrics）不需要——它们只注册 `ToDoubleFunction`，无资源持有。

---

## 十一、跨框架对比

| 维度 | Micrometer MeterBinder | OpenTelemetry Instrumentation | Dropwizard Reporter |
|------|------|------|------|
| **绑定模式** | `bindTo(registry)` 一次性注册 | `Instrumenter<T>` 每次请求创建 span/metric | `Reporter.report(gauges, counters, ...)` 批量上报 |
| **采集时机** | 启动时 bindTo → 指标被动等待查询 | 请求时创建 span/metric | Reporter 定时轮询 |
| **自动发现** | Spring Boot BeanPostProcessor + deferBinding | Java Agent + ClassFileTransformer（字节码增强）| 无——手动注册 Reporter |
| **指标类型** | Gauge/Counter（被动读取 vs 主动递增）| Counter/Histogram/UpDownCounter | Gauge/Counter |
| **生命周期** | 10/32 需要 AutoCloseable | closeable/close() pattern | stop()/close() |

关键差异：Micrometer 的 `bindTo` 模式是"注册后永久存在"——Meter 跟 Registry 生命周期绑定。OTel 的 Instrumentation 模式是"每次请求创建"——每个 span/metric 都是临时对象。Micrometer 更适合长期运行的服务器指标（JVM/Tomcat/连接池），OTel 更适合请求级 trace + metric 关联。

---

## 十二、面试核心考点

### Q1: MeterBinder 只有一个方法——为什么不需要更多？

因为 `bindTo` 的职责只是"注册指标"——注册完成后 Meter 永久存在于 Registry 中，Binder 实例可以被丢弃。不需要生命周期管理（没有 start/stop）——因为绑定的指标跟随 Registry 的生命周期。

### Q2（陷阱）: JvmGcMetrics 的并发 GC 阶段用什么 Meter 类型记录？

**Timer**（`jvm.gc.concurrent.phase.time`），不是 LongTaskTimer。并发 GC 持续几十毫秒到几秒——用 `Timer.record(duration)` 在 GC 结束时一次性记录。LongTaskTimer 用于跟踪"正在进行中"的任务（如在处理的 HTTP 请求）——GC 的并发阶段在 GC 结束前是不可观测的（JVM 内部不暴露中间状态）。

### Q3: PostgreSQLDatabaseMetrics 如何处理 DBA 执行 pg_stat_reset() 导致计数器归零？

用 `resettableFunctionalCounter`——双 Map（beforeResetCache + previousValueCache）维护"重置后依然单调"的值。检测到 `currentValue < previousValue` → 把 previousValue 记入 beforeReset → 输出 previousValue + currentValue。不处理的系统会在人工维护窗口后看到负 spike。

### Q4: ExecutorServiceMetrics 为什么需要 419 行？三种 Executor 的指标不能统一吗？

不能——`ThreadPoolExecutor.getQueue().size()` 和 `ForkJoinPool.getQueuedSubmissionCount()` 是完全不同的 API。Java 的 Executor 体系没有统一接口暴露内部状态——Micrometer 只能逐个适配（plus MethodHandle + `--add-opens` 处理 Java 16+ 的封装限制）。

### Q5（陷阱）: 自定义 MeterBinder 在 `bindTo` 中抛异常会怎样？应用会启动失败吗？

会——`MeterRegistryPostProcessor.applyBinders()` 中 `binder.bindTo(registry)` 调用无 try-catch 包装，异常直接传播。应用启动失败。因此自定义 Binder 必须处理自己的异常（如数据库未就绪时用 Gauge 惰性求值），不能让 bindTo 抛异常。

### Q6: 为什么 Spring Boot 需要 deferBinding 机制？

Registry 可能在 Binder 之前创建。如果 Registry 创建时立刻 bindTo，Binder 引用的 `GarbageCollectorMXBean`/`MemoryPoolMXBean` 等 Bean 可能尚未初始化——导致指标缺失。deferBinding 将 bindTo 延迟到 `afterSingletonsInstantiated()`——所有单例 Bean 创建完成后统一绑定。
