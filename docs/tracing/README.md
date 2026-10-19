# 链路追踪（Tracing）专题 · 8 篇

> 状态：🚧 写作中（当前进度：01）
> 定位：**分布式链路追踪**的 **原理 + 使用 + 生产对照**（规格对齐 `docs/redis`、`docs/loadbalancer`、`docs/openfeign`）
> 口径：Micrometer Tracing **1.2.5**（Boot 3.2.5，bridge-brave）/ SkyWalking Agent 9.x / xhs 自研 traceId；对齐 xhs-project
> 素材：[`tracing-lab`](../../tracing-lab/HANDOVER.md)（D1~D8 / Z——**做引用与证据，不重复写**）· xhs-project（自研 trace 7 类 + SW agent/插件配置）
> **前置要求**：Nacos 会用即可（操作层面 LB-02/L44 已覆盖；`nacos-lab` 原理深挖不 blocking 本系列）；RocketMQ 只需"最小模型"（生产者→topic→消费者/消费组，开课前 10 分钟补；完整课程后置）

---

## 一、系列总表（8 篇）

| # | 文章 | 类型 | 核心内容 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [链路追踪全景](01-what-is-tracing.md) | 理论 | Trace/Span 是什么；三条路线对比（自研/标准/APM）；xhs 三件叠加现状 | D1 |
| 02 | [传播：W3C/B3 与 header 传递](02-propagation-w3c-b3.md) | 理论 | `traceparent`/`tracestate` 格式；B3；Propagator 的 inject/extract | D2 |
| 03 | [上下文与采样](03-context-baggage-sampling.md) | 理论 | TraceContext/CurrentTraceContext；Sampler；Baggage；isNoop 零代价 | D3/D6/D7 |
| 04 | [Micrometer Tracing 接入](04-micrometer-tracing-setup.md) | 使用 | Tracer API；bridge-brave；自动配置；SpanHandler 导出 | D1/D4/D5/D8 |
| 05 | [日志集成：MDC 与 traceId](05-log-mdc-traceid.md) | 使用 | 日志里带 traceId；SW logback 插件对照 | D8 |
| 06 | [xhs 自研 trace 全景对照](06-xhs-trace-deep-dive.md) | 生产 | TraceContextHolder/6 头/MQ/线程池；为什么自研、和标准差在哪 | xhs 7 类 |
| 07 | [全链路可观测](07-observability-sampling.md) | 生产 | trace+metrics+log 三件叠加；采样配置 | 脚本/运维配置 |
| 08 | [排障与面试收口](08-troubleshoot-interview.md) | 生产 | Z 七组（A~G：Tracer/Span、Context、传播、Baggage、桥接、采样、生产对照）；高频坑 | Z |

---

## 二、学习路径

```text
理论：01 → 02 → 03
使用：04 → 05
生产对照：06 → 07
收口：08
```

- 只想"会用标准"：04/05；
- 想"讲得清"：01~03 + 06（自研对照）；
- 面试冲刺：08 收口 + `tracing-lab/docs/Z-面试题速答.md`。

---

## 三、掌握分级（摘要）

- 🔴 **必掌握**：Trace=树/Span=节点；三阶段生命周期；传播（header 透传是跨进程不断链的关键）；采样（全采太贵）；xhs"三件叠加"各管什么；
- 🟡 **了解（用到回查）**：Baggage 本地 vs 远程；Span.Kind 四种；bridge-brave 细节；
- ⚪ **翻篇（源码深挖）**：D1~D8 逐行（Tracer 接口 9 方法、Propagator inject/extract 实现等）。

---

## 四、边界（与相邻专题）

- **不讲 SW 后端运维**（OAP/存储/UI 部署）——本系列只到"应用侧"（agent 怎么挂、采样怎么配、日志怎么带）；
- **不重复**：Feign 透传细节（OpenFeign 04 已讲）；Sentinel 的 `Tracer.traceEntry`（L45/06）；
- **交叉引用**：链路透传完整机制 → `tracing-lab` D2；自研 trace → xhs 源码。
