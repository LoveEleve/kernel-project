---
title: "04-使用：注解与过滤链"
series: "Micrometer 指标系列"
previous: "03-types-binder.md"
next: "05-Prometheus与SLO.md（待写）"
lab_refs:
  - "micrometer-lab/docs/D4-Timed-Counted-AOP切面.md（§一§二§三已读）"
  - "micrometer-lab/docs/D6-MeterFilter-过滤链.md（§一§二已读）"
---

# 04-使用：注解与过滤链

## §0 它干了什么（例子先行）

```java
@Timed(value = "orders.create", extraTags = {"api", "v2"}, percentiles = {0.5, 0.95, 0.99})
public Order create(...) { ... }
// 需要 TimedAspect Bean（AOP）——之后每次调用自动生成 orders_create Timer
```

改名/加全局 Tag/砍掉不要的指标？不用改业务代码——注册一条 MeterFilter：

```java
registry.config().addFilter(new MeterFilter() {
    public Meter.Id map(Meter.Id id) { return id.withTag("env", "prod"); }  // 全局加 env 标
    public MeterFilterReply accept(Meter.Id id) {
        return id.getName().startsWith("jvm.") ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
    }
});
```

- **它干了什么**：注解=零代码打点；Filter=注册期的统一整形（准入/改名/配置三段）。
- **本篇回答**：@Timed 参数哪些坑？percentiles 真实开销？Filter 三方法各管什么阶段？

## §1 掌握卡（3 条）

1. **@Timed 六参数 + 两个"1.12.5 没有"**（D4 §一）：value/extraTags/longTask/percentiles/histogram/description；**没有 `serviceLevelObjectives`**（1.14+ 才有——1.12.5 想配 SLO（服务等级目标，如"99% 请求 <200ms"）桶得走编程式 `Timer.builder()`）；**没有 `percentilesMode`**——常见记忆混淆。
2. **percentiles 的真相**（D4 §二，D4 原版自纠）：不是存全量样本排序——是 **`TimeWindowPercentileHistogram` 预分桶**（约 3KB 固定桶数组，遍历桶累计计数算 P99）——"60 万样本 15MB"是虚构模型，别背。
3. **MeterFilter 三方法三阶段**（D6 §一）：`accept`（准入：DENY/NEUTRAL/ACCEPT 三元——不是布尔）→ `map`（Id 转换：改名/加全局 Tag）→ `configure`（只改 Timer/Summary 的直方图配置）——**三段在 register 流程里依次执行**（02 §1-3 的第二步展开）。

## §2 双切面的结构（D4 §三）

`TimedAspect` 两个 `@Around`（环绕通知：方法前后都插入逻辑的切面写法）：类级 `@within(Timed)` + 方法级 `execution(@Timed * *.*(..))`——类级注解还会从 target class 回退取注解（继承场景）；`@Counted`/`CountedAspect` 是**镜像结构**（计数版）。使用侧只管加 Bean（`TimedAspect(registry)`），织入是 Spring AOP 的事。

## §3 三元回复为什么不是布尔（D6 §二）

`accept` 返回 `MeterFilterReply`：**NEUTRAL**（我不管，问下一个 filter）/ **ACCEPT**（放行，**跳过后续 filter**）/ **DENY**（拒绝）——布尔表达不了"中立传递"，而过滤链的价值正在于**多个 filter 分工不互相越权**（一个管环境 Tag、一个管黑名单、一个管上限）。

## §4 边界（一句话翻篇）

- @Timed 全参数/longTask 降级 → D4 §一§五用时查。
- 直方图 configure 阶段细节 → D6 §五。
- forMeters 条件委托 → D6 §六（**1.16 新 API，本基线无**——版本边界主动标注）。

## §5 Self-Test（3 题）

- Q1：1.12.5 的 @Timed 缺哪两个参数？SLO 桶怎么配？
- Q2：percentiles 的内存模型是什么？"全量样本"说法错在哪？
- Q3：Filter 三方法与三元回复各管什么？为什么不是布尔？

## §6 衔接

- 注解与过滤收口；下篇 05 Prometheus 接入：双 Registry、scrape 链、`_total` 命名与 SLO。
