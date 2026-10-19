---
title: "06-核心源码：StepRegistry与Push模式"
series: "Micrometer 指标系列"
previous: "05-prometheus-naming.md"
next: "07-Observation统一观测.md（待写）"
lab_refs:
  - "micrometer-lab/docs/D7-StepMeterRegistry-步进聚合.md（§一§二§五已读）"
  - "micrometer-lab/docs/D8-PushMeterRegistry-推模式.md（§二已读，14/2/3 继承数 grep 实证）"
---

# 06-核心源码：StepRegistry与Push模式

## §0 它干了什么（例子先行）

push 型后端的继承是一条三段链：

```
MeterRegistry（1121 行：Meter 创建/复用/移除——02 的主角）
  └─ PushMeterRegistry（167 行：+ publish() 定时调度 + Semaphore 防重入）
       └─ StepMeterRegistry（204 行：+ step rollover 步进聚合）
            └─ DatadogMeterRegistry / Influx…（1.12.5 实测共 14 个子类）
```

- **它干了什么**：每层只加一件事——调度、步进、后端协议。
- **本篇回答**：为什么要 Step？为什么 Prometheus 不在这条链上？push 家族有几条路线？

## §1 掌握卡（4 条）

1. **三层各司其职**（D7 §一）：02 已见 MeterRegistry；Push 层加 `publish()` 抽象（子类写 HTTP POST/MBean/TCP）+ **Semaphore(1) 防重入**（上次没推完这次直接跳过）；Step 层加**步进聚合**——推送前把当前计数"截断"rollover（窗口翻页：把当前计数挪给上一窗口）发出，推送的是**上一窗口的完整值**。
2. **Step 为谁服务**（D7 §三标题）：push 后端要的是"每分钟增量/窗口值"——StepValue 的 `rollCount()` 用 CAS 完成窗口翻页（D7 §四细节用时查）。
3. **三条 push 路线**（D8 §二，grep 实证）：**Step 系 14 个**（Datadog/Influx/SignalFx/Stackdriver…）/ **Dropwizard 桥 2 个**（JMX/Graphite——十年老接口，走 Dropwizard Reporter）/ **裸 Push 3 个**（OpenTSDB/OTLP/Wavefront——要调度不要 step，**发累计值**）。
4. **为什么 Prometheus 不继承 Push/Step**（D7 §五）：pull 模式发的是**累计值**（Prometheus 用 `rate()` 自己算变化）——Step 截断后它就看不到累计了；且 scrape 是被动时刻，无 publish 调度。**度量语义决定继承树位置**。

## §2 防重入的巧思（D7 §二）

```java
void publishSafelyOrSkipIfInProgress() {
    if (publishingSemaphore.tryAcquire()) {   // 拿不到=上次还在推 → 本次跳过
        try { publish(); } finally { semaphore.release(); ... }
    }
}
```

**为什么 tryAcquire 而不是阻塞等**：推送卡住时，下一个调度周期应该**跳过**而不是排队堆压——push 后端的语义是"最新窗口尽量到，旧窗口丢得起"（与 04 的 Filter 三元、Tracing 队列满丢弃同一旁路哲学：**保主业务，欠自己**）。

## §3 边界（一句话翻篇）

- StepValue rollCount CAS 算法行级 → D7 §四。
- Dropwizard 桥的十年细节 → D8 §四。
- OTLP 路线与 OpenTelemetry 协议关系 → 对照视角不展开（README 边界）。

## §4 Self-Test（3 题）

- Q1：三层继承链各加什么？Semaphore 防什么？
- Q2：push 三条路线的子类数？裸 Push 与 Step 系差在哪个能力？
- Q3：Prometheus 为什么直接挂 MeterRegistry？（累计值 vs 步进值）

## §5 衔接

- 源码深水收口；下篇 07 Observation 统一观测：和 Tracing 系列共用的那套 API（生命周期 3+1、Handler 分发）。
