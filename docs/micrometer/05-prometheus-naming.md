---
title: "05-使用：Prometheus接入与命名"
series: "Micrometer 指标系列"
previous: "04-annotations-filter.md"
next: "06-Step与Push模式.md（待写）"
lab_refs:
  - "micrometer-lab/docs/D5-Prometheus-exposition-SLO.md（§一§二§五§六核心段已读）"
---

# 05-使用：Prometheus接入与命名

## §0 它干了什么（例子先行）

```java
// Boot 3.2.5 的默认链路（= xhs）：micrometer 1.12.5 → 旧版 simpleclient 0.16.0
registry.counter("http.requests", "method", "GET").increment();
// 抓取 /actuator/prometheus 时出现：
// http_requests_total{method="GET"} 42      ← snake_case + 手动追加的 _total
```

- **它干了什么**：Micrometer 层的 Meter 经 `MicrometerCollector` 桥进 simpleclient 的 `CollectorRegistry`，`scrape()` 时序列化成 Prometheus 文本。
- **本篇回答**：新旧两套实现差在哪？双 Registry 怎么分工？`_total` 为什么要手动加？

## §1 掌握卡（3 条）

1. **版本现实**（D5 §一）：本基线（Boot 3.2.5/1.12.5/xhs）用**旧版 simpleclient 0.16.0**（`io.micrometer.prometheus`，1.13 起标记 deprecated 但仍是 1.12 一等公民）；新版 `prometheusmetrics`（Client 1.x）是 Boot 3.3+ 方向——**结论以旧版为准，升级时四点全变**（collect 返回类型/`_total` 自动/冲突检查按 type/`_created` 时间戳）。
2. **双 Registry 分工**（D5 §二）：`MeterRegistry`（应用侧 API）+ `CollectorRegistry`（simpleclient 存储与序列化）+ **`MicrometerCollector` 桥**（一个逻辑指标一个 Collector，内部 `children` 按 **tagValues** 区分标签组合）。
3. **`_total` 手动追加**（D5 §六）：旧版命名约定在 snake_case 后**手动加** `_total`（Counter 类）；**新版 Client 1.x 自动处理且拒绝手动加**——跨版本迁移的头号兼容点。

## §2 scrape 链（D5 §五）

```
GET /actuator/prometheus
  → PrometheusMeterRegistry.scrape()（L106-108）
  → CollectorRegistry.metricFamilySamples()：遍历所有 Collector.collect()
  → TextFormat.writeFormat（TEXT_004=Prometheus 0.0.4 文本格式）
```

pull 模式的"被动美学"：**应用只备菜，抓取那一刻才出餐**——与 06 的 push（定时出餐）对照。

## §3 冲突检查的一个坑（D5 §二尾）

旧版 `collectorMap` 以 **conventionName（命名约定转换后的名字，如 snake_case 化的指标名）为 key**——**同名但 tagKeys 组合不同的 Meter 会冲突**（一个写 `method`、一个写 `path`）；新版改按 Meter.Id。生产纪律：**同名指标统一 tag 键集**（Filter 的 map 阶段正好可以兜这个——04 §1-3 呼应）。

## §4 边界（一句话翻篇）

- new* 全家族/MicrometerCollector 源码行 → D5 §三§四用时查。
- SLO 桶配置 → 04 §1（1.12.5 走 `Timer.builder().slo`）。
- OpenMetrics/_created → 新版特性，用时查。

## §5 Self-Test（3 题）

- Q1：旧版和新版在 `_total` 上的行为差异？迁移会炸在哪？
- Q2：双 Registry 三个角色各干什么？children 按什么键？
- Q3：同名不同 tagKeys 为什么冲突？怎么防？

## §6 衔接

- pull 侧收口；下篇 06 Step 与 Push：push 后端的继承链、StepValue CAS——"为什么 Prometheus 不走 PushMeterRegistry"。
