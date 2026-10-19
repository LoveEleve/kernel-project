---
title: "08-面试收口：要点回链与陷阱"
series: "Micrometer 指标系列"
previous: "07-observation.md"
next: "（本系列完）"
lab_refs:
  - "micrometer-lab/docs/D1~D8 八篇综合（无独立 K 篇——本篇按拷打口径自建）"
---

# 08-面试收口：要点回链与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① 八篇要点回链 ② 高频坑速查 ③ 话术（自建）+ 终检
```

## §1 八篇要点回链

| 篇 | 一句话考点 | 回链 |
|---|---|---|
| 01 | Tag 一等公民；Dropwizard 两病；四类型 | 01 |
| 02 | 模板方法 12=9+3；Composite 纯门面；"签名保类型安全" | 02 |
| 03 | MeterBinder 一方法（可丢）+ AutoCloseable 两例外；Cache 五子族 | 03 |
| 04 | @Timed 六参+两没有；percentiles=预分桶 ~3KB；Filter 三元三阶段 | 04 |
| 05 | 新旧 Prometheus 四点差；`_total` 旧手动新自动；双 Registry | 05 |
| 06 | 三继承链；push 三路线 14/2/3；Prometheus 为何 pull | 06 |
| 07 | @Observed 统一三路；3+1 广播；COW 写少读多 | 07 |

**追问秒答 4 条**：percentiles 贵吗→预分桶 3KB 非全量（04）；Filter 为什么三元→中立传递（04）；Prometheus 为何不 Step→要累计值给 rate()（06）；和 Tracing 什么关系→Observation 一个 API 两桥（07/Tracing 04）。

## §2 高频坑速查（八篇沉淀）

| # | 坑 | 正解 |
|---|---|---|
| 1 | 以为 percentiles 存全量样本 | 预分桶（D4 自纠） |
| 2 | 1.12.5 配 SLO 用 @Timed | 没这参数——`Timer.builder().slo` |
| 3 | 跨版本 `_total` 直接抄 | 新版自动且**拒绝手动** |
| 4 | 同名指标乱用 tagKeys | 旧版 collectorMap 冲突——统一键集 |
| 5 | Binder"注册完必扔" | JVMGc/Pool2 要 close |
| 6 | 以为 Prometheus 是 push 注册表 | 直挂 MeterRegistry（pull） |
| 7 | 硬编码规则式注册（无 Filter） | 全局 Tag/黑名单走 Filter 链 |
| 8 | 双注解 @Timed+@NewSpan | @Observed 一次声明 |

## §3 话术（按拷打口径自建）

> "Micrometer 我把它当指标门面用：业务只打 Tag 化的 Meter，Prometheus 走旧版 simpleclient（Boot 3.2.5 链路，`_total` 手动加），JMX/Datadog 走各自 Registry——Composite 门面接一次分发 N 家。自动指标全靠 MeterBinder（bindTo 注册完即丢，JVMGc 这类要 close）。治理走 MeterFilter 三段（准入/改名/直方图配置）。和 Tracing 共用 Observation API：@Observed 一次声明，Meter/Span/日志三路 Handler 各取所需。踩过的坑：percentiles 不是全量样本是预分桶；1.12.5 没有 SLO 注解参数；跨版本 `_total` 语义会反转。"

## §4 终检（8 题，过 6 算过关）

1. Dropwizard 两病 + 门面解法（01/02）
2. 模板方法 9+3；为什么不 Object 入口（02）
3. 四类型+Function 对；Binder 可丢与例外（03）
4. @Timed 两没有；Filter 三方法（04）
5. `_total` 新旧行为；双 Registry 三角色（05）
6. push 三路线数字；Step 解决什么（06）
7. Observation 3+1；COW 原因（07）
8. 指标/trace/日志三件分工（01，收口闭环）

## §5 衔接

- Micrometer 指标系列 8 篇完，README 全挂链接；**L5-3 可观测性（Tracing+SW+指标）全线闭环**。
- 下一专题由你定（候选：ZooKeeper/Curator——理论已铺 / Spring 内核——L5-6 / 连接池与 ORM——L5-8 / Kafka）。
