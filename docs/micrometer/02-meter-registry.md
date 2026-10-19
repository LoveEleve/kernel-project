---
title: "02-原理一：MeterRegistry与多后端路由"
series: "Micrometer 指标系列"
previous: "01-what-is-metrics.md"
next: "03-类型与MeterBinder.md（待写）"
lab_refs:
  - "micrometer-lab/docs/D1-MeterRegistry-多后端路由.md（§二§三§六核心段）"
---

# 02-原理一：MeterRegistry与多后端路由

## §0 它干了什么（例子先行）

01 说"门面接一次、发 N 家"——机制是**模板方法 12 个**：

```java
// MeterRegistry 抽象类：9 个 abstract + 3 个 concrete（D1 §二，行号已列）
protected abstract Counter newCounter(Meter.Id id);                    // L148
protected abstract Timer newTimer(Meter.Id, DistributionStatisticConfig, PauseDetector); // L188
protected abstract Gauge newGauge(Meter.Id, T obj, ToDoubleFunction<T>);  // L140
// ... 共 9 个 abstract：每种 Meter 一个"出生口"
// 3 个 concrete 提供默认实现（如 TimeGauge 内部委托 newGauge——复用模板）
```

- **它干了什么**：子类（Prometheus/Simple/JMX Registry）只需实现各自的 `newXxx`——`registry.counter(...)` 的调用方代码永远不变。
- **本篇回答**：为什么 12 个方法而不是 1 个？一次 register 走什么流程？Composite 怎么"发 N 家"？

## §1 掌握卡（4 条）

1. **模板方法 12=9+3**（D1 §二）：每种 Meter 一个 `newXxx` 出生口（abstract 9）+ 3 个带默认实现的（TimeGauge 委托 newGauge 等）——**比 Dropwizard 单入口多 12 倍控制粒度**。
2. **为什么不统一成 `newMeter(type, Object config)`**（D1 §2.3 原因）：Counter 只要 Id、Timer 还要直方图配置+PauseDetector（检测长暂停、从延迟统计里剔除的探测器）、Gauge 要"被观察对象"——**参数集完全不同**，强行 Object 化丢掉编译期检查；模板方法=**用签名保类型安全**。
3. **register 主流程**（D1 §三）：`registerMeterIfNecessary`——查重（同 Id 复用）→ 过 MeterFilter 链（04 讲）→ 调对应 `newXxx` → 挂进内部 Map——**一次业务调用的全部开销**。
4. **CompositeMeterRegistry = 纯门面**（D1 §一§六）：自己不存数据，把每次 register **转发给所有子 Registry**——同一个 `counter()` 在 Prometheus 和 JMX 里是**两个独立对象**；删除 Meter 也逐家转发。

## §2 两条设计线（推导）

**朴素答案**：Registry 里存一份数据，各 Reporter 来读（Dropwizard 就这么死的——pull/push 时间节奏打架）。

**But**：Prometheus 要"被动等抓取、按步进清零的计数"；Datadog 要"每分钟推一次累计值"——**同一份存储伺候两种节奏=互相污染**。

**Therefore**：**每后端一个 Registry**，各自存储与节奏；共享的只有门面层——这为 06 的 Step（push 系步进）与 Pull（prometheus 拉取）分家埋下架构伏笔。

## §3 Clock 抽象（D1 §四一句话版）

Meter 的时间戳走 `Clock` 接口而非 `System.currentTimeMillis()`——**测试可控**（虚拟时间推进直方图窗口）+ 后端可换时钟源；这是"可测试性注入"的标准姿势。

## §4 边界（一句话翻篇）

- registerMeterIfNecessary 全行级/过滤链交互 → D1 §三 + 04。
- DistributionStatisticConfig 直方图引擎 → 05 Prometheus 篇用到再说。
- Composite 删除传播/嵌套深度 → D1 §六用时查。

## §5 Self-Test（3 题）

- Q1：12 个模板方法的 9+3 各是什么？为什么不用一个 Object 入口？
- Q2：一次 `registry.counter()` 内部走哪三步？（查重→过滤→newXxx）
- Q3：Composite 自己存数据吗？"两个独立 Counter"是什么意思？

## §6 衔接

- 门面机制收口；下篇 03 类型与 Binder：四类型怎么用、32 个 Binder 怎么自动绑上 JVM/DB/Kafka。
