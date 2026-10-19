---
title: "02-数据模型：一条请求在SW里变成什么"
series: "SkyWalking 可观测系列"
previous: "01-apm-overview.md"
next: "03-agent无侵入原理.md（待写）"
lab_refs:
  - "docs/中间件拷打/K14-SkyWalking.md（①数据模型/refs+②span 实证）"
cross_refs:
  - "Tracing 01/02（span 基础概念——本篇讲 SW 自己的术语与形态）"
---

# 02-数据模型：一条请求在SW里变成什么

## §0 它干了什么（例子先行）

一次登录后刷首页（xhs 实证：gateway→home，**53 个 span**），在 SkyWalking 里的完整形态：

```
用户请求 → gateway：SW 生成 1 个 Segment（内含 1 个 span：入口段）
        → Feign 调 home：HTTP 头带 SW 的 trace 上下文
        → home：新 Segment（入口 span，refs 标记 CROSS_PROCESS——"我是从 gateway 来的"）
        → home 内：@Async 线程池任务 → 新 Segment，refs 标记 CROSS_THREAD
        → home 内：Lettuce 异步 Redis 调用 → 子 span 挂在当前段下
UI 里：一棵跨 2 服务、含异步分支的调用树；点开能看到每段耗时
```

- **它干了什么**：把一次请求变成**分段的 span 树**——每进程一段，段内嵌套，段间用 refs 缝合。
- **本篇回答**：SW 里 Trace/Span/Segment 三个词各指什么？refs 怎么串？数据从产生到可查经过哪几步？

## §1 掌握卡（4 条）

1. **三个词**：**Trace**=一次请求的全树（全局唯一 id）/ **Segment**=一个线程（进程内一次处理）产生的 span 集合（SW 的传输与存储单位）/ **Span**=树上一个节点（同 Tracing 01，SW 里有 Entry/Local/Exit 之分）。
2. **三种 span**（SW 术语）：**Entry**（入口，如 Controller/Consumer 入站）/ **Local**（进程内段，如 @Async 任务）/ **Exit**（出口，如 Feign/JDBC/Redis 调用）——一棵树=入口套出口与本地段。
3. **refs 是缝合线**：`CROSS_PROCESS`（跨进程：HTTP 头携带 SW 上下文）/ `CROSS_THREAD`（跨线程：线程池增强传递）——K14①原话：**"能否把异步/跨服务串起来是 APM 的价值分水岭"**。
4. **从产生到可查四步**：agent 内存缓冲 → 异步 gRPC 批量上报 → OAP 接收分析（组装树/算指标）→ 存储（ES）→ UI 查询。**OAP 挂了=缓冲丢 trace，业务无感**（K14⑤5）——观测盲区不是业务故障。

## §2 SW 术语 vs Tracing 术语（防混淆）

| 概念 | Tracing 系列（Micrometer/Brave） | SkyWalking |
|---|---|---|
| 一次请求全树 | Trace | Trace（同） |
| 一个节点 | Span | Span（细分 Entry/Local/Exit） |
| 传输/存储单位 | FinishedSpan 逐条上报 | **Segment**（线程级打包） |
| 跨进程缝合 | traceparent 头（W3C） | SW 自己的上下文头（gRPC/HTTP 增强） |
| 手动埋点 | Tracer API | @Trace/@TraceContext（手动增强，少数场景） |

**答辩一句话**：SW 的 Segment 相当于"一个线程里所有 span 的打包"——批量上报省往返；refs 是它跨段续树的钩子。

## §3 一条请求的数据旅程（理论版）

```
① 入口（gateway Entry span）
② 传播：SW 上下文进 HTTP 头（类比 Tracing 02 的 traceparent——SW 用自己的格式）
③ 下游（home Entry span + CROSS_PROCESS ref）
④ 进程内：异步线程（CROSS_THREAD ref）/ 出口调用（Exit span）
⑤ 采样闸门（本篇 §4）——决定这个 Segment 值不值得上报
⑥ 缓冲+批量 gRPC → OAP → ES → UI
```

## §4 采样在哪一步（概念先行，实测 05 篇）

采样发生在**上报前**（agent 侧）：默认 `sample_n_per_3_secs=1`——**每 3 秒只放行 1 个 trace**；全采样 `SW_AGENT_SAMPLE=-1`（压测/演示用，开销另算）。采样掉的 trace **完全不上报**——不是"上报后丢"，是"没出门"。

## §5 边界（一句话翻篇）

- 三种 span 在插件里怎么落 → 03。
- 采样参数调优与开销实测 → 05。
- OAP 内部流水线（OAL/告警）→ 06。
- SW 上下文头的格式细节 → 翻篇（K14 发散追问地图"上下文传播 W3C/B3"为通用背景）。

## §6 Self-Test（4 题）

- Q1：Trace/Segment/Span 各是什么？Segment 为什么是传输单位？
- Q2：Entry/Local/Exit 各举例一个。
- Q3：refs 两种各在什么场景出现？没有它们 APM 缺了什么？
- Q4：采样发生在哪一步？采掉的 trace 去哪了？（没出门）

## §7 衔接

- 本篇给数据模型理论；下篇 03 回答"无侵入怎么实现"（字节码增强——机制，不再是概念）。
