---
title: "03-原理二：流控算法与熔断状态机"
series: "Sentinel 流量防卫系列"
previous: "02-slotchain-leaparray.md"
next: "04-规则与动态数据源.md（待写）"
lab_refs:
  - "sentinel-lab/docs/D3-FlowSlot-流控算法.md（§一~§五核心段已读）"
  - "sentinel-lab/docs/D4-CircuitBreaker-熔断状态机.md（§二§三已读）"
---

# 03-原理二：流控算法与熔断状态机

## §0 它干了什么（例子先行）

同一条链上，FlowSlot 和 DegradeSlot 各拿窗口数据做不同的决策：

```
FlowSlot：过去 1 秒 pass 数 > 阈值？→ 抛 FlowException（请求到此终止，后面的槽不执行）
DegradeSlot：过去窗口里慢调用比例/异常率超标？→ 熔断器 OPEN → 后续请求直接 Block，直到 retryTimeout
             → HALF_OPEN 放一两个探测 → 成功回 CLOSED / 失败再 OPEN
```

- **它干了什么**：流控管"来太多"，熔断管"下游坏了"——**同一份滑动窗口数据，两种决策**。
- **本篇回答**：流控几种控制器各治什么？熔断三态怎么转？

## §1 掌握卡（4 条）

1. **流控三控制器**（D3 三文件）：`DefaultController`（QPS/线程数**直接拒绝**）/ `ThrottlingController`（**匀速排队**——算等待时间 sleep 后放行，`costTime=1000/count` ms/个）/ `WarmUpRateLimiterController`（**冷启动预热**——令牌生成速率从低到高爬坡，防冷系统被突然打垮）。
2. **两种 grade**：QPS（读窗口 pass 数）/ 线程数（当前活跃线程计数）——线程数隔离治"慢资源被慢调用占满"（第三方 API）。
3. **熔断三态状态机**（D4 §二原图）：`CLOSED`（全放）→（超阈值）→ `OPEN`（全拒，等 retryTimeout）→ `HALF_OPEN`（探测）→ 成功回 `CLOSED` / 失败再 `OPEN`——状态存 `AtomicReference`，CAS 原子转换。
4. **熔断三策略**：慢调用比例 / 异常比例 / 异常数——触发阈值不同，状态机同款（00 地图原话）。

## §2 FlowSlot 的执行时序（D3 §二）

```java
// FlowSlot.entry：查规则不过就抛，过了才 fire 下一槽
checkFlow(...) → 不过 → throw FlowException（链终止，DegradeSlot 不执行）
              → 通过 → fireEntry(...)（继续下一槽）
```

**多规则语义**（D3 §三）：一资源可挂多条 FlowRule（全天 1000 + 高峰 500）——**全过才放行**，一条不过就拒。

## §3 排队的数学（ThrottlingController 一句话版）

超限不拒绝：`expectTime = 上次通过时间 + costTime`（costTime=1000/QPS）→ 当前请求 sleep 到点再放——**把尖峰摊平成匀速流**（削峰填谷；秒杀排队场景）。对比 DefaultController 的"宁可拒不可慢"——**同一条规则用哪个 Controller 是产品决策**（延迟换吞吐）。

## §4 熔断为什么不是流控（D4 的问题意识）

**朴素答案**：下游慢就限流呗？

**But**：限流阈值是拍的——下游半死不活时 100 QPS 也嫌多；下游恢复了又不敢放。

**Therefore**：熔断**自适应**——从实际结果（慢比例/异常率）反推"现在该不该放"：全拒一段时间省资源 → 半开探测 → 看探测结果决定回不回。**流控防"自己被冲垮"，熔断防"陪坏下游一起死"**。

## §5 链上的位置（02 回收）

FlowSlot（第 7 槽）在 DegradeSlot（第 8 槽）**之前**——先问"量合不合适"再问"下游健不健康"；被流控拦截的请求根本不进熔断统计（不污染熔断的指标）。

## §6 边界（一句话翻篇）

- canPassCheck 全分支/限流效果日志 → D3 §三/§八用时查。
- 熔断状态转换 CAS 逐行（transformToOpen）→ D4 §三§四用时查。
- 热点参数（ParamFlowSlot）→ 04 规则层一句带过（默认链的槽位清单以 02 引的 SPI 文件为准，各小版本略有差异）。

## §7 Self-Test（4 题）

- Q1：三控制器各一句话；排队的 costTime 怎么算？
- Q2：线程数 grade 治什么病？和 QPS grade 用数据的区别？
- Q3：三态状态机画出来；HALF_OPEN 失败去哪？
- Q4：流控在链上为什么排在熔断前？

## §8 衔接

- 原理线收口（02 统计+03 决策）；下篇 04 使用：五类规则怎么配 + Nacos 动态推送 + xhs Bulkhead 实装。
