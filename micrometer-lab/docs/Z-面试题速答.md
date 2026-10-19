# Z: Micrometer 面试题速答（收束篇）

> 定位：micrometer-lab 收束篇——把 A（生产使用）/B（扩展点）/D（核心原理）的结论面试化。
> 基线：**Boot 3.2.5 / micrometer 1.12.5（旧版 simpleclient）**（xhs-project 口径）；1.13+ 特性一律标注。
> 题库锚点：手册 Ch27「可观测性与故障诊断」10 题（331-340）。
> 用法：先自测（盖住答案），答不出的回对应文档。

---

## 一、速记卡（一页纸）

1. **MeterRegistry = Facade + 模板方法**（9 abstract + 3 concrete）；每个后端一个子类、独立存储（Prometheus/JMX/Simple 各自持有数据）。
2. **创建链**：`registerMeterIfNecessary` → `getMappedId`（过 MeterFilter.map 链）→ `getOrCreateMeter`（单 map + 双检锁）→ 类型校验；`filters` 是 **volatile 数组**（热路径无 iterator 分配）。
3. **accept 三元**：`DENY` 立即拒绝 / `ACCEPT` 立即放行 / `NEUTRAL` 继续——DENY/ACCEPT 都**终止链**，Filter 顺序影响结果。
4. **Composite**：`IdentityHashMap`（`==` 去重）+ CAS 自旋锁；`onMeterAdded` 把 CompositeMeter 广播到所有叶子 Registry；支持嵌套 + 循环检测。
5. **Clock 双时钟**：`wallTime()`（step 边界/时间戳，可被 NTP 回拨）/ `monotonicTime()`（duration，只增不减）——Timer 必须用后者。
6. **DistributionStatisticConfig**：DEFAULT = expiry 2min × bufferLength 3 = **6min 完整衰减**；`merge()` 语义"this 优先、parent 兜底"。
7. **MeterBinder 一个方法**：`bindTo(registry)` 一次性注册；32 个 Binder/18 子包；Boot 的 `deferBinding` 等所有单例创建完（`afterSingletonsInstantiated`）统一绑定。
8. **Observation**：`start/stop/error/event` 四事件；Handler 在**同一个列表**里，**运行时** `supportsContext` 过滤（1.12.5）；`Context` 是 Handler 间的 KV store（`Timer.Sample` 存这）。
9. **@Observed vs @Timed**：前者走 ObservationHandler 链（metrics+tracing+logging）；后者直接建 Timer。**Boot 3.2.5：@Timed/@Counted 切面默认不注册**（`management.observations.annotations.enabled` 默认 false）！
10. **Prometheus（1.12.5 旧版）**：scrape **无缓存**（全量 collect + TextFormat）；`_total` 由 `PrometheusNamingConvention` **手动追加**；同名 Meter 的 tagKeys 不一致 → 默认**静默失败**。
11. **Step 双线程**：roller（step+1ms，`rollCount` CAS + `sumThenReset`）先截断 → publisher（step+2ms+随机偏移）后读取；关闭时 flush 最后 step + `_closingRollover` 冻结。
12. **Push 三路线**：Step 聚合（14 个）/ Dropwizard 桥接（jmx/graphite）/ 直接 `PushMeterRegistry`（opentsdb/otlp/wavefront，发累计值）。

---

## 二、分组高频题

### A. MeterRegistry 与多后端

**A1. Micrometer 比 Dropwizard Metrics 解决了什么？**
Tag 作为一等公民（维度不编码进 name，可 `sum by(region)`）；Registry 与 Reporter 解耦（每个后端独立 Registry，pull/push 并存）。回链：D1 §一。

**A2. 为什么是 9+3 个模板方法而不是一个 `newMeter(type, config)`？**
参数集完全不同（Gauge 要被观察对象、Timer 要 DistributionStatisticConfig+PauseDetector）——不同签名保证编译期类型安全，避免 `Object config` 运行时转换。回链：D1 §二。

**A3. 一次 `registry.counter("x")` 内部发生了什么？**
`registerMeterIfNecessary` → `getMappedId`（Filter.map 链）→ `getOrCreateMeter`：查 meterMap → 未命中则（双检锁内）`accept` 准入 → `configure` 改直方图配置 → 调 `newCounter` 模板方法 → `onMeterAdded` 回调 → put。回链：D1 §三。

**A4. MeterFilter 三个阶段分别在哪执行？**
`accept`（准入，originalId）→ `map`（改 Id，产生 mappedId）→ `configure`（改直方图配置，仅 Timer/Summary/LongTask）。**accept 在 map 之前**——accept 看不到 map 后的 tag。回链：D1 §三/D6 §一。

**A5. CompositeMeterRegistry 为什么用 IdentityHashMap？**
`equals()` 可能相同但引用不同的两个 Registry 需要共存（多后端路由语义）——`==` 去重才是正确语义。回链：D1 §6.1。

### B. MeterBinder 与自动绑定

**B1. MeterBinder 为什么只有一个方法？**
绑定是"一次性注册"——注册完 Meter 永久在 Registry 中，Binder 实例可丢弃；没有 start/stop 生命周期。回链：D2 §一。

**B2. JvmGcMetrics 为什么用 NotificationListener 而不是轮询？**
GC 是事件驱动的——需要 GC 类型/暂停时间/触发原因；轮询 `getCollectionCount()` 拿不到这些。回链：D2 §三。

**B3. Boot 的 deferBinding 解决什么？**
Registry 可能先于 Binder 创建——立即 bindTo 时 Binder 依赖的 Bean 可能未初始化。`MeterRegistryPostProcessor` 把 bindTo 推迟到 `afterSingletonsInstantiated`（所有单例就绪）。回链：D2 §八。

**B4. 自定义 MeterBinder 抛异常会怎样？**
`applyBinders` 无 try-catch → 异常传播 → **应用启动失败**。Binder 必须自己兜底（如惰性 Gauge）。回链：D2 Q5。

### C. Observation API

**C1. Observation 的四类事件？**
`onStart` / `onStop` / `onError` / `onEvent`（自定义事件）——广播到所有注册 Handler。回链：D3 §二。

**C2. Handler 是怎么分发的？（1.12.5）**
**不是**按类型分 Composite——所有 Handler 在同一个 `CopyOnWriteArrayList`，`SimpleObservation` 每次事件**运行时**逐个 `supportsContext` 检查。回链：D3 §四。

**C3. DefaultMeterObservationHandler 做了什么？**
onStart：`LongTaskTimer.builder(name+".active")` + `Timer.start` 存入 Context；onStop：低基数 KeyValue → Tag + error tag → `Timer.builder(name).tags(...)` 落库；onEvent：`<name>.<event>` Counter。回链：D3 §五。

**C4. 低基数 vs 高基数 KeyValue？**
低基数（method/status）→ Prometheus time series 可控；高基数（uri/userId）→ series 爆炸，留给 Tracing。两者在 Convention 里分开声明。回链：D3 §十三。

**C5. Observation 跨线程能拿到当前观测吗？**
不能——`openScope` 用 ThreadLocal；跨线程靠 Micrometer Tracing 的 `ContextPropagation` 或 Reactor `Hooks.enableAutomaticContextPropagation()`。回链：D3 §十。

### D. @Timed / @Counted

**D1. Boot 3.2.5 里加了 @Timed 没指标，为什么？**
`MetricsAspectsAutoConfiguration` 条件是 `management.observations.annotations.enabled=true`——**默认 false**（元数据实证），切面根本不注册。@Observed 不受影响（ObservedAspect 默认注册）。回链：D4 §十。

**D2. percentiles 的真相？**
不是"存全量样本"——`TimeWindowPercentileHistogram` 预分桶，固定 ~3KB，与 QPS 无关。`histogram=true` 则是 Prometheus 服务端算。回链：D4 §二。

**D3. `@Timed` 支持 SLO 桶吗？（1.12.5）**
**不支持**——`serviceLevelObjectives` 属性是 1.14+；本基线用编程式 `Timer.builder().serviceLevelObjectives(Duration...)`（A10 实测）。回链：D4 §一。

**D4. longTask=true 失效的情况？**
`buildLongTaskTimer()` 返回 Optional——构建失败（Registry 不支持等）→ `Optional.empty()` → 静默跳过（不报错）。设计：指标故障不能拖垮业务。回链：D4 §五。

### E. Prometheus

**E1. /actuator/prometheus 的一次 scrape 发生了什么？**
`PrometheusScrapeEndpoint` → `scrape()` → `CollectorRegistry.metricFamilySamples()` 遍历所有 Collector `collect()` → Child lambda 取值（lazy）→ `TextFormat.writeFormat`（TEXT_004）。**无缓存**。回链：D5 §五。

**E2. 为什么旧版命名要手动追加 `_total`？**
旧版底层 simpleclient 不自带——`PrometheusNamingConvention.name()` 对 COUNTER 手动加（L66-L69）。新版（1.13+）Client 1.x 自动处理，手动加会抛异常。回链：D5 §六。

**E3. 两个同名 Counter 但 tagKeys 不同会怎样？**
`applyToCollector` 按 tagKeys 检查——不等则 `meterRegistrationFailed`：**默认不抛异常**（静默失败，指标消失）；要快速失败调 `throwExceptionOnRegistrationFailure()`。回链：D5 §八。

**E4. 1.12.5 的 Prometheus 是新版还是旧版？**
**旧版**（`io.micrometer.prometheus` + simpleclient 0.16.0，Boot 3.2.5 管理）；新版 `prometheusmetrics` 是 1.13+。旧版独有 HistogramFlavor（VictoriaMetrics vmrange）。回链：D5 §一/§九。

**E5. `getBaseTimeUnit()` 为什么是 SECONDS？**
Prometheus 生态共识——`rate()`/`histogram_quantile()` 假定秒；毫秒输出要全查询乘 0.001。回链：D5 Q3。

### F. MeterFilter

**F1. MeterFilterReply 为什么是三元？**
二元无法表达"提前放行"（关键指标必须保留，不管后面 Filter）——`ACCEPT` 就是。DENY/ACCEPT 都终止链，顺序敏感。回链：D6 §二。

**F2. maximumAllowableMetrics 的坑？**
不区分关键/非关键——超限后所有新 Meter 静默拒绝（关键指标晚创建就没了）。必须配合 `acceptNameStartsWith` 前置保护。回链：D6 §3.2/Q3。

**F3. 1.12.5 里晚注册 Filter 会怎样？**
**静默无效**——新 Filter 只对之后创建的 Meter 生效（无警告、无 stale 机制——1.17 才加）。Filter 必须早于第一个 Meter。回链：D6 §七。

**F4. commonTags 对已创建的 Meter 为什么无效？**
它是 `map(id)` 阶段的 MeterFilter——map 只在 Meter 创建时走一次。回链：D6 §4.1/Q4。

### G. Step 与 Push

**G1. 为什么需要两个调度线程？**
roller（step+1ms）截断计数器、publisher（step+2ms+随机）读取——时序分离避免 rollover 延迟影响 publish 准确性；随机偏移避免千实例同时打后端。回链：D7 §二/§三。

**G2. rollCount 的核心算法？**
`stepTime = now/stepMillis`；`lastInitPos.compareAndSet(lastInit, stepTime)` 保证只 rollover 一次；`valueSupplier().get()`（如 `sumThenReset`）原子取值+重置；`lastInit == stepTime-1` 才认为上一 step 有数据。回链：D7 §四。

**G3. 关闭时最后一步数据会丢吗？**
不会——`shouldPublishDataForLastStep()`（roller 比 publisher 多跑一个 step 时同步 publish）+ `closingRolloverStepMeters()` 冻结（`lastInitPos=Long.MAX_VALUE`）。回链：D7 §3.3/§四。

**G4. Graphite/JMX 用的谁家调度器？**
Dropwizard Reporter 自己的（`DropwizardMeterRegistry extends MeterRegistry`——**不经过 PushMeterRegistry**）。回链：D8 §四/Q5。

### H. 生产与对照（xhs-project）

**H1. 你们业务指标怎么打点？**
`BusinessMetrics`（my-xhs-common/metrics）封装 counter/timer：`orders.created.total{status}`、`orders.create.latency`、`orders.paid.total{channel,result}`——统一入口避免各处裸调 registry。回链：D1 §二 + xhs 源码。

**H2. Prometheus 端点在项目里怎么开？**
actuator 暴露 `health,info,prometheus,metrics`（application.yml）+ Boot 自动装配 `PrometheusScrapeEndpoint`（旧版链路）——Grafana 直接 scrape。回链：D5 §十。

**H3. traceId 跨线程池怎么处理？**
`MdcAwareExecutorService`（my-xhs-common/trace）——包装线程池任务时传递/恢复 MDC（traceId）；这属于 tracing 线（tracing-lab 预告）。回链：D3 §十 + xhs 源码。

---

## 三、陷阱速查（15 连）

| # | 反直觉点 | 正解 |
|---|---|---|
| 1 | getOrCreateMeter 用 computeIfAbsent | 显式 `synchronized`（onMeterAdded 副作用回调不能重复） |
| 2 | accept 链顺序无关 | DENY/ACCEPT **立即终止**——顺序影响结果 |
| 3 | Composite 用 equals 去重 | **IdentityHashMap**（`==`） |
| 4 | Timer 用 wallTime 计时 | **monotonicTime**（NTP 回拨会出负值） |
| 5 | 样本 2min 过期=生命周期 2min | 2min × **3 buffer** = 6min 完整衰减 |
| 6 | MeterBinder 需要生命周期 | 一次性 `bindTo`——注册完可丢弃 |
| 7 | Observation Handler 有 Composite 分派 | 1.12.5 是**单列表 + 运行时 supportsContext** |
| 8 | @Observed 也要开关 | @Observed 默认注册；**@Timed/@Counted 才需要**（Boot 3.2.5 默认 false） |
| 9 | percentiles 存全量样本 | 预分桶 TimeWindowPercentileHistogram（~3KB 固定） |
| 10 | @Timed 注解配 SLO | `serviceLevelObjectives` 是 **1.14+**；1.12.5 编程式 |
| 11 | 1.12.5 Prometheus 是新版 | **旧版 simpleclient**；`_total` 手动追加 |
| 12 | 同名 Meter 不同 tags 自动合并 | tagKeys 不一致 → **默认静默失败** |
| 13 | commonTags 对已有 Meter 生效 | 只对**后续创建**的 Meter（map 阶段） |
| 14 | rollover 和 publish 同线程 | 双线程：roller +1ms / publisher +2ms+随机 |
| 15 | PushGateway 是 Micrometer 子类 | 是 **simpleclient 的类**（操作 CollectorRegistry） |

---

## 四、文档索引（回链）

| 主题 | 文档 |
|---|---|
| MeterRegistry / 模板方法 / Composite / 生命周期 | D1 |
| MeterBinder / JVM/Kafka/PG / deferBinding | D2 |
| Observation / Handler / Context / Convention | D3 |
| @Timed/@Counted / 切面 / percentiles / MeterTag | D4 |
| Prometheus（旧版）/ scrape / 命名 / SLO | D5 |
| MeterFilter 三元链 / map 族 / 成本控制 | D6 |
| Step 聚合 / 双线程 / rollCount / 关闭链 | D7 |
| Push 三路线 / publish 契约 / PushGateway | D8 |
| 生产使用端点（A01-A13）与扩展点（B01-B05） | a/ / b/ 源码 |
| 评审记录 | REVIEW-2026-10-05 |

---

## 五、一页纸收束

> **模型**：MeterRegistry（Facade+模板方法）→ 每后端独立存储；Meter.Id = name+tags（type 不参与 equals）；Filter 三阶段 accept→map→configure。
> **绑定**：MeterBinder 一次 bindTo；Boot deferBinding 延后统一绑定；32 Binder 覆盖 JVM/连接池/缓存/Kafka/PG。
> **观测**：Observation 四事件 + Context KV；@Observed 走 Handler 链、@Timed 直建 Timer（**Boot 3.2.5 需开 annotations.enabled**）。
> **输出**：Prometheus 旧版 simpleclient（scrape 无缓存/`_total` 手动/tagKeys 冲突静默失败）；Step+Push 双线程（+1ms 截断/+2ms 推送）。
> **生产**：Filter 早注册；maximumAllowable 护成本；业务统一走 BusinessMetrics；traceId 跨线程用 MdcAwareExecutorService。
