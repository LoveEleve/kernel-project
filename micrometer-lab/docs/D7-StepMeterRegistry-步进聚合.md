# D7: StepMeterRegistry——步进聚合与 push 调度

> **阅读顺序**: P2 进阶——理解 Micrometer 的 push 模式调度与步进聚合如何协同工作
> **源码版本**: micrometer-core **v1.12.5**（tag；运行时对齐 Boot 3.2.5，与 xhs-project 一致；1.13+ 新特性以注记标出）
> **核心文件**:
> - `StepMeterRegistry.java` (204 行)——步进聚合 + double-scheduler 时序协调
> - `PushMeterRegistry.java` (167 行)——push 调度器基类（Semaphore 防重入 + 随机偏移 + 关闭流程）
> - `StepValue.java` (102 行)——步进值抽象 + `rollCount()` CAS 核心算法
> - `StepLong.java` (45 行)——LongAdder + `sumThenReset` 原子重置
> - `StepDouble.java` (51 行)——DoubleAdder + `sumThenReset`（对称设计）
> - `StepTuple2.java` (106 行)——双值原子 rollover（count+totalTime 同一 step 窗口一致）
> - `StepCounter.java` (53 行)——extends AbstractMeter implements StepMeter + StepDouble 委托
> - `StepTimer.java` (106 行)——StepTuple2<Long,Long> + TimeWindowMax + LongAdder 双追踪
> - `StepMeter.java`——`_closingRollover()` 契约接口

---

## 一、三层继承链——MeterRegistry → PushMeterRegistry → StepMeterRegistry

```
MeterRegistry（1121 行）
    └── PushMeterRegistry（167 行）  ← 新增：publish() 调度器 + Semaphore 防重入
         └── StepMeterRegistry（204 行）  ← 新增：step rollover + 双线程时序协调
              └── 具体后端（如 Datadog/JMX/Graphite 等 push 型 Registry）
```

D1 分析的 `MeterRegistry` 负责 Meter 的创建/复用/移除。`PushMeterRegistry` 新增了**定时推送**能力。`StepMeterRegistry` 在它之上新增了**步进聚合**——在推送之前，先把当前计数器"截断"并 rollover 到上一个 step。

**为什么 StepMeterRegistry 不直接 extends MeterRegistry？**

因为步进聚合 + push 调度 = 强耦合。如果你只需要步进聚合但不需要定时推送（如 pull 模式的 Prometheus 也用 step 聚合），你需要把调度器和聚合器分开。Micrometer 选择了 "Step extends Push" 的设计，因为几乎所有步进聚合的场景都伴随定时推送。

---

## 二、PushMeterRegistry——Semaphore 防重入 + 随机偏移

### 2.1 `publish()` 的调度框架

```java
// PushMeterRegistry.java L53
protected abstract void publish();  // ← 子类实现（如 JMX 写 MBean、Datadog HTTP POST）

// L60-L75
void publishSafelyOrSkipIfInProgress() {
    if (this.publishingSemaphore.tryAcquire()) {          // ← Semaphore(1) 防重入
        this.lastScheduledPublishStartTime = clock.wallTime();
        try {
            publish();
        } catch (Throwable e) {
            logger.warn("Unexpected exception thrown while publishing", e);  // ← 吞异常
        } finally {
            this.publishingSemaphore.release();
        }
    } else {
        logger.warn("Publishing is already in progress. Skipping duplicate call.");
    }
}
```

**为什么用 Semaphore 而不是 synchronized？**

`synchronized` 在重入时**阻塞**——如果上一次 publish 还没完成（网络超时），新的调度线程会被挂在 `synchronized` 上，等上一个完成后立刻开始下一个——导致 publish 堆积。`Semaphore.tryAcquire()` 不阻塞——如果上一次还在执行，**跳过本次**，保证 publish 不会堆积。

### 2.2 调度启动——`calculateInitialDelay()` 的随机偏移

```java
// PushMeterRegistry.java L105
public void start(ThreadFactory threadFactory) {
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
    long stepMillis = config.step().toMillis();
    scheduledExecutorService.scheduleAtFixedRate(
        this::publishSafelyOrSkipIfInProgress,
        calculateInitialDelay(),    // ← 关键：不是简单 stepMillis
        stepMillis, TimeUnit.MILLISECONDS);
}

// L156-L164
long calculateInitialDelay() {
    long stepMillis = config.step().toMillis();
    long randomOffsetWithinStep = Math.max(0,
        (long) (stepMillis * random.nextDouble() * 0.8) - 2);
    long offsetToStartOfNextStep = stepMillis - (clock.wallTime() % stepMillis);
    return offsetToStartOfNextStep + 2 + randomOffsetWithinStep;  // ← 至少 2ms 进入 step
}
```

**为什么需要 "at least 2ms into step" + 随机偏移？**

注释说：**"so it is after StepMeterRegistry's meterPollingService"**——StepMeterRegistry 的 roller 在 step 开始后 **1ms** 执行（见 §三），publisher 在 step 开始后 **至少 2ms** 执行。这保证了 **roller 先执行（截断计数器）→ publisher 后执行（读取上一个 step 的数据）**。

随机偏移的目的：**避免多实例的 publish 同一时刻击中监控后端**——如果 1000 个实例同时 POST 到 Datadog，后端瞬时压力巨大。`randomOffsetWithinStep` 在 [0, 0.8×stepMillis) 范围内随机分散。

---

## 三、StepMeterRegistry——双线程时序协调

### 3.1 `start()`——启动两个调度器

```java
// StepMeterRegistry.java L122
@Override
public void start(ThreadFactory threadFactory) {
    super.start(threadFactory);                           // ← Push 的 publish 调度器

    if (config.enabled()) {
        this.meterPollingService = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("step-meter-registry-poller-for-" + getClass().getSimpleName()));
        this.meterPollingService.scheduleAtFixedRate(
            this::pollMetersToRollover,
            getInitialDelay(),                            // ← stepMillis - (wallTime % stepMillis) + 1
            config.step().toMillis(), TimeUnit.MILLISECONDS);
    }
}

// L198
private long getInitialDelay() {
    long stepMillis = config.step().toMillis();
    return stepMillis - (clock.wallTime() % stepMillis) + 1;  // ← 1ms into next step
}
```

**时序关系**：

```
step boundary (wallTime % stepMillis == 0)
    │
    ├── +1ms:  roller 执行 pollMetersToRollover()
    │           ├── 所有 StepValue 的 rollCount() 被触发
    │           ├── current → previous (截断)
    │           └── lastMeterRolloverStartTime = clock.wallTime()
    │
    ├── +2ms~+X:  publisher 执行 publishSafelyOrSkipIfInProgress()
    │               └── publish() 读取 previous 的值 → 发送到后端
    │
    └── 下一个 step boundary...
```

**为什么需要两个独立线程？** 因为 `pollMetersToRollover()` 遍历所有 Meter 触发 `count()` → `rollCount()`——这个操作需要多长时间取决于 Meter 数量。如果和 publish 在同一线程中串行，publish 的延迟受 rollover 时间影响——产生数据漂移。

### 3.2 `pollMetersToRollover()`——强制 rollover

```java
// StepMeterRegistry.java L191
void pollMetersToRollover() {
    this.lastMeterRolloverStartTime = clock.wallTime();
    this.getMeters().forEach(m ->
        m.match(
            gauge -> null,                              // ← Gauge 不 rollover（瞬时值）
            Counter::count,                              // ← Counter.count() → StepValue.poll() → rollCount()
            Timer::count,                                // ← Timer
            DistributionSummary::count,                  // ← Summary
            meter -> null,                              // ← Meter.OTHER
            meter -> null,                              // ← TimeGauge
            FunctionCounter::count,                     // ← FunctionCounter
            FunctionTimer::count,                       // ← FunctionTimer
            meter -> null));                            // ← LongTaskTimer
}
```

**关键**：Gauge 不参与 rollover——Gauge 是瞬时值（可升可降），不需要步进聚合。只有 Counter/Timer/DistributionSummary/FunctionCounter/FunctionTimer 参与——它们是**累计值**，需要步进截断后计算 rate。

`m.match()` 是 Meter 的 visitor 模式（D1 §十分析过）——9 个 Function 参数的 instanceof 分派。

### 3.3 `close()`——flush 最后一个 step

```java
// StepMeterRegistry.java L142
@Override
public void close() {
    stop();  // ← 先停掉两个调度器

    if (config.enabled() && !isClosed()) {
        if (shouldPublishDataForLastStep() && !isPublishing()) {
            publish();                                    // ← 同步推送最后一个 step
        } else if (isPublishing()) {
            waitForInProgressScheduledPublish();           // ← 等当前 publish 完成
        }
        closingRolloverStepMeters();                       // ← 冻结所有 StepValue
    }
    super.close();  // ← MeterRegistry.close() 遍历 meter.close()
}

// L166
private boolean shouldPublishDataForLastStep() {
    if (lastMeterRolloverStartTime < 0) return false;
    long lastPublishedStep = getLastScheduledPublishStartTime() / stepMillis;
    long lastPolledStep = lastMeterRolloverStartTime / stepMillis;
    return lastPublishedStep < lastPolledStep;             // ← roller 比 publisher 多跑了一个 step
}

// L178
private void closingRolloverStepMeters() {
    getMeters().stream()
        .filter(StepMeter.class::isInstance)
        .map(StepMeter.class::cast)
        .forEach(StepMeter::_closingRollover);
}
```

**场景**：roller 刚刚 rollover 了第 10 个 step，但 publisher 还没来得及推送第 10 个 step 的数据 → `lastPublishedStep < lastPolledStep` → `shouldPublishDataForLastStep()` = true → 同步调用 `publish()` flush 最后一个 step 的数据。

`closingRolloverStepMeters()` 遍历所有 StepMeter 调 `_closingRollover()`——这是 `StepValue._closingRollover()`（见 §四），把 `lastInitPos` 设为 `Long.MAX_VALUE` → 永远不再 rollover。

---

## 四、StepValue——rollCount() 的 CAS 核心算法

### 4.1 数据结构

```java
// StepValue.java（102 行）
public abstract class StepValue<V> {
    private final Clock clock;
    private final long stepMillis;
    private final AtomicLong lastInitPos;       // ← 上次 rollover 的 step 编号（CAS 保护）
    private volatile V previous;                 // ← 上一个 step 的聚合值

    // 子类实现：
    protected abstract Supplier<V> valueSupplier();   // ← 如何读取当前值并重置
    protected abstract V noValue();                    // ← 无数据时的默认值
}
```

### 4.2 `rollCount()`——核心算法

```java
// StepValue.java L68-L78
private void rollCount(long now) {
    final long stepTime = now / stepMillis;            // ← 当前的 step 编号
    final long lastInit = lastInitPos.get();
    if (lastInit < stepTime && lastInitPos.compareAndSet(lastInit, stepTime)) {
        final V v = valueSupplier().get();             // ← 读取当前值并重置（原子操作）
        // 判断上一个 step 是否有活动：
        previous = (lastInit == stepTime - 1)
            ? v                                        // ← 连续活动 → 上一个 step 有有效数据
            : noValue();                               // ← 跳过了 step → 上一个 step 无数据
    }
}
```

**算法要点**：

1. **`wallTime / stepMillis`** → step 编号——跨 step 边界时编号变化
2. **`lastInitPos.compareAndSet(lastInit, stepTime)`** → CAS 保证只有一个线程成功 rollover
3. **`valueSupplier().get()`** → 读取当前值 + 重置当前值——这是**原子操作**（如 `LongAdder.sumThenReset()`）
4. **`lastInit == stepTime - 1`** → 上一个 step 是否有活动——如果跳过了 step（如 GC pause 导致 wallTime 跳跃），`previous = noValue()`（空数据）

### 4.3 StepLong——LongAdder + sumThenReset

```java
// StepLong.java（45 行）
public class StepLong extends StepValue<Long> {
    private final LongAdder current = new LongAdder();

    @Override
    protected Supplier<Long> valueSupplier() {
        return current::sumThenReset;          // ← LongAdder 的原子 sum + reset
    }

    @Override
    protected Long noValue() {
        return 0L;                             // ← 无数据 → 0
    }
}
```

`LongAdder.sumThenReset()` 是 JDK 8 的原子操作——读取当前和并重置为 0，**在一个操作中完成**。这是 StepLong 不需要额外 `synchronized` 的原因。

**StepDouble**（51 行）完全对称——用 `DoubleAdder` 替换 `LongAdder`，`noValue()` 返回 `0.0`。

### 4.4 `_closingRollover()`——关闭时冻结

```java
// StepValue.java L96-L100
protected void _closingRollover() {
    lastInitPos.set(Long.MAX_VALUE);           // ← 永远不再 rollover
    previous = valueSupplier().get();          // ← 捕获最后的当前值
}
```

`lastInitPos = Long.MAX_VALUE` → 任何 `now / stepMillis` 都 < `Long.MAX_VALUE` → `rollCount()` 的 `lastInit < stepTime` 条件永远为 false → 不再 rollover。`previous` 被设为最后一拍的数据——应用关闭前的最后一个 step 的数据不会丢失。

---

## 五、push 模式 vs pull 模式——为什么 Prometheus 不继承 PushMeterRegistry

### 5.1 Pull 模式的 Prometheus

```java
// PrometheusMeterRegistry extends MeterRegistry（不是 Push/Step）
// 没有 publish() 方法
// 没有 scheduleAtFixedRate
// 数据由 Prometheus 服务器 pull（GET /actuator/prometheus）
```

Prometheus 不需要定时推送——数据在内存中累积，每次 scrape 时被动输出。如果 Prometheus 继承了 StepMeterRegistry，metrics 会在 step rollover 时被截断——pull 模式需要看到的是**累计值**（方便 `rate()` 计算），不是步进值。

### 5.2 Push 模式的典型后端

```
StepMeterRegistry → DatadogMeterRegistry    (HTTP POST)
                 → JmxMeterRegistry        (写 MBean)
                 → GraphiteMeterRegistry    (TCP push)
                 → InfluxMeterRegistry      (HTTP POST)
```

这些后端都需要**定时推送**数据 → StepMeterRegistry 的步进聚合确保发送的是"上一个时间窗口的 rate"而非"启动以来的累计总数"。

---

## 六、StepMeterRegistry 的模板方法实现——step Meter 如何组装

前面只讲了 StepMeterRegistry 的调度层。但 D1 告诉我们 MeterRegistry 有 9 个 abstract 模板方法——StepMeterRegistry 怎么实现的？

### 6.1 StepCounter——最简单的委托模式

```java
// StepCounter.java（53 行）
public class StepCounter extends AbstractMeter implements Counter, StepMeter {
    private final StepDouble value;

    public StepCounter(Id id, Clock clock, long stepMillis) {
        super(id);
        this.value = new StepDouble(clock, stepMillis);        // ← 核心：StepDouble 做步进聚合
    }

    @Override public void increment(double amount) {
        value.getCurrent().add(amount);                          // ← 写入当前 DoubleAdder
    }
    @Override public double count() {
        return value.poll();                                     // ← poll() → rollCount() → previous
    }
    @Override public void _closingRollover() {
        value._closingRollover();                                // ← 关闭时冻结
    }
}
```

**设计的精妙**：53 行代码 —— StepCounter 只是一个"委托壳"。`increment()` 写入 DoubleAdder（无锁），`count()` 通过 `value.poll()` 触发 rollover。**StepCounter 不实现任何聚合逻辑**——全委托给 StepDouble。

**`implements StepMeter`**：这是 `_closingRollover()` 的契约接口——所有 step 实现类都实现它。StepMeterRegistry 的 `closingRolloverStepMeters()`（L177-L182）遍历所有 `StepMeter` 实例调 `_closingRollover()`。

### 6.2 StepTimer——双值追踪 + TimeWindowMax

```java
// StepTimer.java（106 行）
public class StepTimer extends AbstractTimer implements StepMeter {
    private final LongAdder count = new LongAdder();               // ← 当前 step 的计数
    private final LongAdder total = new LongAdder();               // ← 当前 step 的合计耗时(ns)
    private final StepTuple2<Long, Long> countTotal;               // ← 双值原子 rollover
    private final TimeWindowMax max;                               // ← 滑动窗口最大值
}
```

**StepTimer 比 StepCounter 复杂——因为它需要追踪 3 个维度：count、totalTime、max**。但关键设计相同：`record()` 写入 `count.add(1)` + `total.add(durationNs)`——直接写 LongAdder（无锁）。`count()/totalTime()` 通过 `StepTuple2.poll1()/poll2()` 读出上一个 step 的值。

**StepTuple2——双值原子 rollover**（106 行）：

```java
// StepTuple2.java L63-L73
private void rollCount(long now) {
    long stepTime = now / stepMillis;
    long lastInit = lastInitPos.get();
    if (lastInit < stepTime && lastInitPos.compareAndSet(lastInit, stepTime)) {
        t1Previous = (lastInit == stepTime - 1) ? t1Supplier.get() : t1NoValue;
        t2Previous = (lastInit == stepTime - 1) ? t2Supplier.get() : t2NoValue;
    }
}
```

**为什么需要 StepTuple2？** Timer 的 count 和 totalTime 必须在**同一个 CAS 成功**中完成 rollover——如果两个独立 StepValue，可能出现"count rollover 了但 totalTime 还没 rollover"的时间窗口不一致。StepTuple2 保证 count.t1Previous 和 totalTime.t2Previous 来自**同一个 step 窗口**——`rate(count) / rate(totalTime) = 平均耗时` 才不会出现"分子在 step_N、分母在 step_{N-1}"的错位。

### 6.3 StepFunctionTimer——惰性读取的步进聚合

```java
// StepMeterRegistry.java L96-L100
protected <T> FunctionTimer newFunctionTimer(Meter.Id id, T obj,
        ToLongFunction<T> countFunction, ToDoubleFunction<T> totalTimeFunction,
        TimeUnit totalTimeFunctionUnit) {
    return new StepFunctionTimer<>(id, clock, config.step().toMillis(),
        obj, countFunction, totalTimeFunction, totalTimeFunctionUnit, getBaseTimeUnit());
}
```

StepFunctionTimer（127 行）是 StepMeterRegistry 中最复杂的实现——它用 `StepTuple2<Long, Double>` 追踪 count+totalTime 两个惰性读取值。不像 StepTimer（主动 `record()`），StepFunctionTimer 的 `countFunction.apply(obj)` 在每个 step 边界被调用一次——**pollMetersToRollover() 中的 `FunctionTimer::count` 触发惰性读取**。

### 6.4 HistogramGauges.registerWithCommonFormat——为什么每个 newTimer/newDistribution 都调

```java
// StepMeterRegistry.java L78-L83
protected Timer newTimer(...) {
    Timer timer = new StepTimer(...);
    HistogramGauges.registerWithCommonFormat(timer, this);        // ← 关键！
    return timer;
}
```

**原因**：StepTimer 的 percentiles 和 histogram buckets 是 Gauge 类型的——它们需要注册为独立的 Gauge Meter 以便 Prometheus 采集。`HistogramGauges.registerWithCommonFormat()` 内部调用 `Gauge.builder(...).synthetic(timer.getId()).register(registry)`——这就是 D1 中讲的 `syntheticAssociation` 机制的**真实触发源**。

`registerWithCommonFormat()` 为 StepTimer 注册 3 类衍生 Gauge：
- `timerName.percentile{phi="0.5"}` → P50
- `timerName.percentile{phi="0.95"}` → P95
- `timerName.histogram{le="0.01"}` → histogram buckets

这三个 Gauge 通过 `Gauge.Builder.synthetic(timer.getId())` 与 Timer 关联——当 Timer 被 remove 时，`syntheticAssociations` 级联删除所有衍生 Gauge（D1 §十的 remove 五层清理）。

### 6.5 为什么 Gauge 和 LongTaskTimer 不走 step？

```java
// StepMeterRegistry.java L61-L62
protected <T> Gauge newGauge(Meter.Id id, @Nullable T obj, ToDoubleFunction<T> valueFunction) {
    return new DefaultGauge<>(id, obj, valueFunction);       // ← NOT step！直接用非步进 DefaultGauge
}

// StepMeterRegistry.java L71-L74
protected LongTaskTimer newLongTaskTimer(Meter.Id id, DistributionStatisticConfig config) {
    LongTaskTimer ltt = new DefaultLongTaskTimer(id, clock, getBaseTimeUnit(), config, false);
    HistogramGauges.registerWithCommonFormat(ltt, this);      // ← 仍然注册衍生 Gauge
    return ltt;
}

// StepMeterRegistry.java L108-L110
protected Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements) {
    return new DefaultMeter(id, type, measurements);          // ← NOT step
}
```

**Gauge 不走 step 的原因**：Gauge 是瞬时值——可升可降。步进聚合需要截断累积值（如 Counter 的 count），但截断 Gauge 的瞬时值毫无意义——它的值是多少就是多少，不随时间累积。

**LongTaskTimer 不走 step 的原因**：LongTaskTimer 跟踪正在运行中的任务数量——`activeTasks()` 返回当前有多少任务在执行。这不是累积值——不需要截断。但它的 percentiles（P50/P95 耗时）仍然需要衍生 Gauge → `HistogramGauges.registerWithCommonFormat()` 在这条路径上照常调用。

**newMeter 不走 step 的原因**：自定义 Meter 的 Measurement 是任意意义的——可能瞬时、可能累计、可能两者混合。StepMeterRegistry 选择不截断——每个 Measurement 的语义由调用方定义，盲目步进聚合可能破坏测量含义。

### 6.6 StepDistributionSummary + StepFunctionCounter——同模复用

```java
// StepMeterRegistry.java L87-L93
protected DistributionSummary newDistributionSummary(Meter.Id id,
        DistributionStatisticConfig config, double scale) {
    DistributionSummary summary = new StepDistributionSummary(id, clock, config, scale,
            config.step().toMillis(), false);
    HistogramGauges.registerWithCommonFormat(summary, this);   // ← 同步进版 HistogramGauges
    return summary;
}

// StepMeterRegistry.java L103-L105
protected <T> FunctionCounter newFunctionCounter(Meter.Id id, T obj,
        ToDoubleFunction<T> countFunction) {
    return new StepFunctionCounter<>(id, clock, config.step().toMillis(), obj, countFunction);
}
```

**StepDistributionSummary**（111 行）与 StepTimer 同模——`StepTuple2<Long, Double>` 追踪 count+totalAmount，`TimeWindowMax` 追踪 max，`HistogramGauges` 生成衍生 Gauge。**StepFunctionCounter**（59 行）与 StepCounter 同模——`StepDouble` + 委托模式。

**完整的模板方法实现对比**：

| 模板方法 | 实现类 | 核心数据 | 走 step？ | 原因 |
|------|------|------|:--:|------|
| newGauge | `DefaultGauge` | 无——惰性读取 | ❌ | Gauge 是瞬时值 |
| newCounter | `StepCounter` | `StepDouble` | ✅ | 累计值需要截断 |
| newTimer | `StepTimer` | `StepTuple2<Long,Long>` + `TimeWindowMax` | ✅ | count+totalTime 需同一 step 窗口 |
| newDistributionSummary | `StepDistributionSummary` | `StepTuple2<Long,Double>` + `TimeWindowMax` | ✅ | 同 StepTimer |
| newLongTaskTimer | `DefaultLongTaskTimer` | 无 | ❌ | 跟踪进行中任务，非累积值 |
| newFunctionTimer | `StepFunctionTimer` | `StepTuple2<Long,Double>` | ✅ | 惰性读取需步进截断 |
| newFunctionCounter | `StepFunctionCounter` | `StepDouble` | ✅ | 同 StepCounter |
| newMeter | `DefaultMeter` | 无 | ❌ | 语义不确定，不截断 |

这张表是 StepMeterRegistry 设计意图的完整体现：**只有跟踪累积值的 Meter 才需要步进聚合**。

### 6.7 defaultHistogramConfig——expiry=config.step()

```java
// StepMeterRegistry.java L113-L118
protected DistributionStatisticConfig defaultHistogramConfig() {
    return DistributionStatisticConfig.builder()
        .expiry(config.step())                                     // ← 样本过期 = step 周期
        .build()
        .merge(DistributionStatisticConfig.DEFAULT);               // ← 其他参数用默认
}
```

为什么 expiry = step 周期？——因为步进聚合中，每个 step 的数据在 rollover 后不再需要之前的样本。expiry=60s 配合 bufferLength=3（DEFAULT）= 180s 完整衰减——刚好覆盖 Prometheus 的 1min scrape × 2-3 次重试窗口。

---

## 七、PushMeterRegistry 的完整关闭链

### 7.1 close()——最后的同步推送

```java
// PushMeterRegistry.java L129-L137
@Override
public void close() {
    stop();         // ← 1: 停掉调度器（不再有新的 publishSafelyOrSkipIfInProgress）

    if (config.enabled() && !isClosed()) {
        publishSafelyOrSkipIfInProgress();            // ← 2: 最后一次同步推送
        waitForInProgressScheduledPublish();           // ← 3: 等当前正在进行的 publish 完成
    }
    super.close();  // ← 4: MeterRegistry.close() → 遍历 meter.close()
}
```

### 7.2 waitForInProgressScheduledPublish() —— 阻塞等待

```java
// PushMeterRegistry.java L144-L153
protected void waitForInProgressScheduledPublish() {
    try {
        publishingSemaphore.acquire();          // ← 阻塞直到 Semaphore 可用
        publishingSemaphore.release();          // ← 立刻释放
    } catch (InterruptedException e) {
        logger.warn("Interrupted while waiting for publish on close to finish", e);
    }
}
```

**设计意图**：如果另一个线程正在执行 `publish()`（可能因为网络超时卡了几十秒），`close()` 需要等待它完成——防止发送一半的数据（corrupted partial push）。`acquire() + release()` 的模式确保：① 等待 in-progress publish 完成 ② 不放行后续 publish（因为 stop() 已经停了调度器）。

### 7.3 Push close vs Step close——双重 flush

```
PushMeterRegistry.close():
  stop() → publishSafelyOrSkipIfInProgress() → waitForInProgressScheduledPublish() → super.close()

StepMeterRegistry.close():
  stop() → [roller stop + publisher stop]        // ← super.stop() 停了两个调度器
  shouldPublishDataForLastStep()? → publish()    // ← 同步 flush 最后一个 step
  closingRolloverStepMeters()                    // ← 冻结所有 StepValue
  super.close() → push.close() → ...             // ← 走 Push 的 close 链
```

Step close 在 Push close 之前做了**额外工作**：判断 roller 比 publisher 多跑了一个 step → 同步推送最后一个 step 的数据 → 然后冻结所有 StepValue。这样做的好处：**应用关闭前的最后一个 step 的数据不会丢失**。

### 7.4 isPublishing() —— 运行时状态查询

```java
// PushMeterRegistry.java L84
protected boolean isPublishing() {
    return publishingSemaphore.availablePermits() == 0;  // ← permit=0 意味着有人在 publish
}
```

`shouldPublishDataForLastStep()` 中用它判断"publisher 是否正在发送数据"——如果是，等它完成；如果不是，同步 push 最后一个 step。

---

## 八、生产量化

### 8.1 双线程开销

```
roller:  1 thread × N Meter × 1μs/match = N μs/step
publisher: 1 thread × serialize + network

N=10000 meters × 1μs = 10ms rollover time
step=60s → roller CPU 占比 = 10ms/60s = 0.017%
```

Roller 线程 CPU 开销可忽略。真正压力在 publisher 的序列化和网络 IO。

### 8.2 随机偏移 0.8×step 的散开效果

```
1000 instances × step=60s
randomOffset range = [0, 48s)
→ publish 在 12s-60s 区间内均匀分布
→ 任意 1s 窗口内的并发 publish 数 ≈ 1000/48 ≈ 21
→ 后端接收速率 = 21 requests/s（可控）
```

如果没有随机偏移 → 1000 实例在同一秒同时 publish → Datadog API 限流 500 req/s → 500 个实例 report failure。

### 8.3 StepLong 内存

```
每个 StepCounter = StepLong(46行) + LongAdder(24 bytes) + Meter.Id(200 bytes)
                 ≈ 270 bytes
10000 StepCounters = ~2.7MB（JVM 堆内）
```

---

## 九、跨框架对比

| 维度 | Micrometer StepMeterRegistry | Dropwizard ScheduledReporter | OpenTelemetry PeriodicMetricReader |
|------|------|------|------|
| **调度机制** | `ScheduledExecutorService` × 2（roller + publisher） | `ScheduledExecutorService` × 1（reporter） | `ScheduledExecutorService` × 1（export） |
| **步进聚合** | StepValue.rollCount() CAS + LongAdder.sumThenReset | 无——Counter 是累计值，Reporter 自己算 rate | 无——SDK 内部存储，Reader 读取快照 |
| **防重入** | Semaphore(1).tryAcquire() → 跳过 | 无——synchronized 阻塞 | 无——Reader 无状态 |
| **关闭 flush** | shouldPublishDataForLastStep() + _closingRollover | report() 一次 | forceFlush() 阻塞 |
| **随机偏移** | 0.8×step 范围内随机 | 无 | 无 |

---

## 十、面试核心考点

### Q1: StepMeterRegistry 为什么需要两个独立调度线程？

roller 在 step 边界 +1ms 执行 `pollMetersToRollover()`（截断计数器），publisher 在 step 边界 +2ms~随机偏移执行 `publish()`（读取上一个 step 的数据）。时序分离避免 rollover 延迟影响 publish 准确性。如果同线程串行，rollover 时间（N meters × 1μs）会延迟 publish，导致数据漂移。

### Q2（陷阱）: `pollMetersToRollover()` 为什么 Gauge 不参与？

Gauge 是瞬时值（可升可降）——不需要步进聚合。Counter 是累计值——需要截断后计算 `rate = stepValue / stepDuration`。Gauge 的值是多少就是多少——你可以在 publish 时直接读取，不需要 rollover。

### Q3: `rollCount()` 为什么用 CAS 而不是 `synchronized`？

CAS 无锁——如果 rollover 竞争失败（另一个线程已经 rollover 了），CAS 失败线程什么都不做。这是乐观锁模式：只有一个线程成功 rollover，其他线程跳过。如果用 `synchronized`，所有线程排队——但在 rollover 已经完成的情况下排队毫无意义。

### Q4（陷阱）: 如果 roller 线程 crash 了，publisher 会发送什么数据？

Publisher 继续执行——它读取 `StepValue.poll()` → `rollCount()` → 如果长时间没有 rollover，`lastInitPos` 落后多个 step → `lastInit == stepTime - 1` 为 false → `previous = noValue()` → 发送空数据（0）到后端。**应用正常运行但监控面板全 0**——这是最危险的静默故障。

### Q5: `_closingRollover()` 为什么把 `lastInitPos` 设为 `Long.MAX_VALUE`？

防止关闭后 JVM 仍在运行（如 shutdown hook 延迟）时发生新的 rollover。`Long.MAX_VALUE` 使任何 `now / stepMillis` 都 < `lastInitPos` → `rollCount()` 永远不触发。最后把 `previous = valueSupplier().get()`——最后一拍数据不丢失。

### Q6: Push 模式 vs Pull 模式——为什么 Prometheus 不需要 StepMeterRegistry？

Prometheus 是 pull 模式——服务器定时 scrape `/actuator/prometheus`，每次 scrape 看到的是**累计值**。`rate()` 函数在服务端计算 `(currentValue - previousValue) / (currentTimestamp - previousTimestamp)`——服务端做步进聚合。如果把数据在客户端截断了，`rate()` 无法计算。
