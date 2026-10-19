---
title: "01-APM全景：三件叠加的最后一块"
series: "SkyWalking 可观测系列"
previous: "README.md"
next: "02-agent无侵入原理.md（待写）"
lab_refs:
  - "docs/中间件拷打/K14-SkyWalking.md（①原理+②项目用法）"
cross_refs:
  - "Tracing 01/05/07（三件叠加/双号/采样——本系列兑现）"
---

# 01-APM全景：三件叠加的最后一块

## §0 它干了什么（例子先行）

Tracing 01 说过"xhs = SW agent + logback 插件 + 自研透传三件叠加"——今天把 SW 这件拆开：

```
业务服务挂 `-javaagent`（JVM 启动参数，加载外部 agent jar）skywalking-agent.jar（代码一行不改）
  → 框架调用被自动插桩（插桩=在既有代码里织入采集逻辑；SpringMVC/Feign/Lettuce/JDBC…）
  → gRPC 上报给 OAP（12800/11800）
  → OAP 分析存储进专用 ES（19201，和业务 ES 19200 隔离）
  → UI（8080）里看到：gateway→home 一条 trace 53 个 span，
    CROSS_PROCESS 跨服务 + CROSS_THREAD 跨线程，连 Lettuce 异步 Redis 都串上
```

- **它干了什么**：APM（应用性能监控）——**自动**采集全链路 span，回答"慢在哪、谁拖累谁"。
- **本篇回答**：APM 和指标/日志怎么分工？APM traceId 和业务 `X-Trace-Id` 是什么关系？（答辩第一问）

## §1 掌握卡（3 条）

1. **四组件**：Agent（字节码增强，无侵入）→ gRPC 上报 → OAP（接收/分析/存储）→ UI；存储用 ES（xhs 专用集群）或 BanyanDB（SkyWalking 自研的新一代存储，知道名字即可）。
2. **APM 的价值分水岭 = refs**：`CROSS_PROCESS`（跨进程）/`CROSS_THREAD`（跨线程）——**能把异步和跨服务串成一棵树**才叫 APM（K14①原话）；只会单服务 span 的只是打点。
3. **双 traceId 分工**（Tracing 05 兑现）：APM traceId 管性能分析/可视化；业务 `X-Trace-Id` 管日志/MQ/审计/DB 关联——**互补不混用**（K14②对照表）。

## §2 在三件叠加里的位置（Tracing 回收）

| 件 | 干什么 | 对应系列 |
|---|---|---|
| SW agent | span 采集+性能归因（APM 侧） | **本系列** |
| SW logback 插件 | APM 号进日志（`%tid`） | 02/06 兑现 |
| 自研透传 | 业务号进日志/MQ/审计（6 头） | Tracing 06（已封卷） |

Tracing 05 埋的钉子——"网关日志两个号，`%X{traceId}` 和 `%tid`，对不上是正常的"——本篇给出**为什么正常**：两套系统各自发号，靠 correlation 缝合（Tracing 06 讲过 `putCorrelation("traceId",…)`）。

## §3 双 traceId 对照表（K14② 答辩关键）

| | APM traceId | 业务 `X-Trace-Id` |
|---|---|---|
| 归属 | SkyWalking（span 树） | 自研（TraceContextHolder） |
| 用途 | 性能分析/链路可视化 | 日志/MQ/审计/DB 关联键 |
| 依赖 | 需要 agent | 无 agent 依赖、可控 |
| 边界 | 出不了 APM 存储；自定义线程池/MQ 需手动增强 | 靠业务透传纪律 |

**xhs 口径**（K14②原话）：**以业务 traceId 为主、APM 作为增强**——两套 ID 各管一摊。

**为什么不二选一**：APM 号出不了 APM 存储（进不了审计表/消息体）；业务号做不了 span 树性能归因。追问"为什么还要业务 traceId"（K14⑤1）的标准答案：**无 agent 依赖 + 能进消息/审计/DB 字段**。

## §4 边界（一句话翻篇）

- 字节码增强怎么实现 → 02。
- 三坑修复叙事 → 03。
- 开销实测 → 04（Tracing 07 采样话题的兑现）。
- OAP 内部 → 05。

## §5 Self-Test（3 题）

- Q1：APM 的价值分水岭是什么？（refs 串异步/跨服务）
- Q2：双 traceId 各管什么？xhs 以哪个为主？
- Q3：三件叠加里哪件负责"APM 号进日志"？

## §6 衔接

- 本篇给全景；下篇 02 数据模型（一条请求在 SW 里变成什么——先概念后机制）；03 回答"无侵入怎么做到"。
