---
title: "07-全链路可观测：三件叠加与采样"
series: "Tracing 链路追踪系列"
previous: "06-xhs-trace-deep-dive.md"
next: "08-排障与面试收口.md（待写）"
lab_refs:
  - "tracing-lab/docs/D7-Sampler-SpanHandler.md（§五量化、§六 Q1）"
source_refs:
  - "Boot 3.2.5 BraveAutoConfiguration.java:147（Sampler.create(probability)）"
  - "xhs-project config/skywalking/trace-sampling-policy-settings.yml:17-26（rate/duration）"
  - "xhs-project scripts/restart-all-skywalking.sh（agent 9.6.0，15 服务）"
---

# 07-全链路可观测：三件叠加与采样

## §0 它干了什么（例子先行）

线上一个慢请求，三处数据各记一笔：

```
SW UI：     调用树 — 网关 800ms → 订单 750ms（SQL 700ms 标红）
日志（ES，日志搜索引擎）：traceId=TTT → 15 行日志，订单那行 "SQL 慢：700ms"
指标：      订单接口 P99 从 200ms 涨到 800ms（RED 看板）
```

- **它干了什么**：三件数据拼出完整故事——指标发现、trace 定位、日志定因。
- **输入/输出**：输入=三处采样与上报配置；输出=可查、可定位、可算账的可观测。
- **本篇回答**：三件各查什么？采样在哪配？成本多少？运维脚本怎么做？

## §1 掌握卡（4 条）

1. **三件各查各**：指标（RED 看板，发现异常）→ trace（调用树，定位哪一跳）→ 日志（traceId 搜 ES，定因）。关联键：自研 traceId（日志）+ SW correlation（06 缝的，双号互查）。
2. **两处采样**：Brave 侧 `management.tracing.sampling.probability`（hash 决策，lab 默认 1.0 全采）+ SW 侧 `rate`（万分比，默认 10000 = 全采；`duration` 给慢 trace 兜底）。
3. **成本账**：10% 采样约 3.6 GB/天（ES 再 ×2~3）；span 本地开销 ~13μs，贵的是网络上报（所以异步队列缓冲）。
4. **运维**：脚本一键装 agent + 重启 15 服务；影子表（结构一致、事后 TRUNCATE）；没配 reporter 有安全回退（04 讲过）。

## §2 三件叠加：各看什么

| 件 | 看什么 | 工具 | 关联键 |
|---|---|---|---|
| metrics | P99/错误率/流量（RED 三指标）——**发现**"订单接口变慢了" | Prometheus/Grafana（指标采集/看板） | 服务名+时间 |
| trace | 调用树——**定位**"慢在订单调支付的 SQL" | SW UI / Zipkin | SW 号 / 自研号（correlation 互查） |
| log | 15 行上下文——**定因**"SQL 700ms，索引没走" | ES（日志搜索引擎）+ traceId 搜 | 自研 traceId（05） |

顺序记成"**发现→定位→定因**"。01 的"三件叠加现状"到这里落地为查法。

## §3 Brave 采样：probability

```yaml
# tracing-lab yml（默认全采，学习环境）
management:
  tracing:
    sampling:
      probability: 1.0
```

```java
// BraveAutoConfiguration.java:147——注意不是 BitSet 版（03 已修正）
return Sampler.create(properties.getSampling().getProbability());
```

- hash 决策（`lab D7 Q1`）：`(traceId.low & Long.MAX_VALUE) < Long.MAX_VALUE * probability`——同一 traceId 每次结论一致（全链一致的前提）。
- 生产往下调（0.1 甚至 0.01），和 SW 侧 rate 一起调——两边是**独立开关**（01 结论），都全采就是双倍成本。

## §4 SW 采样：rate + duration

```yaml
# trace-sampling-policy-settings.yml:17-26
default:
  rate: 10000      # 万分比精度；10000 = 100% 全采
  duration: -1     # 慢 trace 兜底阈值（毫秒）；-1 = 不兜
#services:
#  - name: serverName
#    rate: 1000    # 某服务单独 10%
#    duration: 10000
```

- `rate` 万分比：1000 = 10%。可按服务覆盖。
- `duration` 兜底：采样没命中的慢 trace，超阈值也留——和 03 的 defer 同思想（有价值的破例留），不同实现。

## §5 成本账（lab D7 §五）

```
100K req/min × 10% × 5 spans = 50K spans/min = 72M spans/day
72M × 500B ≈ 36 GB/day（全采）→ 10% 采 3.6 GB/day → 1% 采 360 MB/day
ES 索引再 ×2~3：10% 采样 ≈ 10 GB/day ES 存储
```

span 本地开销：过滤 2μs + 变异 6μs + 上报 5μs ≈ **13μs/span**；5 跳链路 65μs，10K QPS 约 0.65 核。真正贵的是网络上报（10~50ms）——所以 reporter 走异步队列，别同步等。

## §6 运维：脚本 + 影子表

- `install-skywalking-agent.sh`：装 agent（默认 9.7.0）。
- `restart-all-skywalking.sh`：15 服务一键重启（网关 19000、用户 19001……共 15 个端口），统一挂 agent、统一日志目录。
- 影子表（06 §6 回顾）：结构与生产一致、表名 `+_shadow`、压测完 TRUNCATE；默认关闭，`myxhs.shadow.enabled=true` 才开。

## §7 边界（这里一句话翻篇）

- metrics 细讲（RED/USE、PromQL）→ 非本系列范畴。
- `install-otel-agent.sh`（OTel agent 脚本；OTel = 01 提过的另一套标准）→ 翻篇，xhs 主力是 SW agent。
- `SpanFilter` 自定义变异、`SpanExportingPredicate` 过滤写法 → 用时查 `lab D7 §二`。

## §8 Self-Test（3 题）

- Q1：慢请求排查"发现→定位→定因"三步各用什么数据？
- Q2：Brave probability 和 SW rate 是什么关系？都全采会怎样？
- Q3：10% 采样一天多少存储（含 ES 膨胀）？开销大头是哪部分？

## §9 衔接

- 生产对照线收口：06（自研）→ 07（三件 + 采样 + 运维）。
- 下篇 08（收口）：Z 七组考点、高频坑、自测清单。
