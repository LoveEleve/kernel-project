---
title: "03-原理二：类型体系与MeterBinder"
series: "Micrometer 指标系列"
previous: "02-meter-registry.md"
next: "04-注解与过滤链.md（待写）"
lab_refs:
  - "micrometer-lab/docs/D2-MeterBinder-自动绑定.md（§一§二§三核心段已读）"
---

# 03-原理二：类型体系与MeterBinder

## §0 它干了什么（例子先行）

JVM 的 GC 次数/耗时不用手写——一行注册全有：

```java
new JvmGcMetrics().bindTo(registry);   // Binder：把指标"告诉"Registry
// 之后 registry 里自动出现 jvm.gc.pause 等一串 Timer/Counter
```

- **它干了什么**：`MeterBinder` 是"指标自注册"的 SPI——`bindTo(registry)` 做完就扔。
- **本篇回答**：四类型怎么选？Binder 为什么只有一个方法？32 个官方 Binder 怎么分族？

## §1 掌握卡（3 条）

1. **类型选型看数据形状**：Counter（单调累加——订单数）/ Gauge（瞬时值——池活跃数，**注意被观察对象要自己维持**）/ Timer（耗时+次数，自带百分位能力）/ DistributionSummary（值分布——金额/包大小）；辅助对：FunctionCounter/FunctionTimer（**惰性读取**——不主动打点，注册时传取数函数，02 模板方法里的那两位）。
2. **MeterBinder 一个方法**（D2 §一）：`void bindTo(MeterRegistry)`——31 行含版权头；**没有生命周期**：bindTo 只注册，注册完 Meter 永驻 Registry、Binder 可丢——"管理生命周期"是别的 SPI 的事，Binder 只"报户口"。
3. **32 个 Binder 分族**（D2 §二）：18 子包/32 类——重度（JvmGcMetrics 376 行 JMX 事件驱动/ExecutorServiceMetrics 反射适配/jOOQ 包装）/ 中度（JvmMemory/KafkaClient…）/ 轻度（Uptime/DiskSpace）——**复杂度全在"怎么拿到源数据"，不在接口**。

## §2 Cache 子族：模板方法的第二现场（D2 §2.2）

`CacheMeterBinder<C>` 抽象基类定统一指标集：

```
cache.size→Gauge；cache.gets{hit,miss}→FunctionCounter；puts/evictions→FunctionCounter
```

Caffeine/EhCache/Guava/Hazelcast/JCache **五个子类只做 API 适配**——和 02 的 Registry 模板方法同一味药：**变化点（各家 cache API）下沉，统一模型（指标集）上浮**。

## §3 JvmGcMetrics 实战点（D2 §三）

GC 指标**事件驱动而非轮询**：JMX `NotificationListener` 挂 GC 通知——有 GC 才记，省掉定时扫 MXBean 的空转；这也是它 376 行的主要复杂度（监听注册/清理/多收集器适配）。**坑**：Binder 不是 fire-and-forget 到底——有 AutoCloseable 例外（1.12.5 源码已核：`JvmGcMetrics:71` 与 `CommonsObjectPool2Metrics:46` 都实现了它）：通知监听器/调度线程要注销——"注册完可丢"的通用规则在这类 Binder 上改成"用完关"。

## §4 边界（一句话翻篇）

- 32 类逐行/PG 的 resettableCounter → D2 §二表用时查。
- KafkaClientMetrics 60s 调度细节 → D2 §五。
- HeapPressure 的 TimeWindowSum → D2 §四。

## §5 Self-Test（3 题）

- Q1：四类型+两个 Function 对，各配什么数据形状？
- Q2：MeterBinder 为什么可以"注册完就扔"？哪些类是 AutoCloseable 例外？（JVMGc/Pool2，源码已核）
- Q3：Cache 子族五兄弟共享什么？各改什么？

## §6 衔接

- 类型与自动绑定收口；下篇 04 使用：@Timed/@Counted 注解怎么变成 Timer/Counter + MeterFilter 三层准入。
