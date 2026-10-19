---
title: "01-什么是Sentinel：第一个限流例子"
series: "Sentinel 流量防卫系列"
previous: "README.md"
next: "02-责任链与滑动窗口.md（待写）"
lab_refs:
  - "sentinel-lab/docs/00-从0开始-Sentinel是什么.md（全文已读）"
---

# 01-什么是Sentinel：第一个限流例子

## §0 它干了什么（例子先行）

要求一个接口**每秒最多通过 1 个请求**：

```java
// 1. 定义规则：资源 hello 的 QPS 上限 = 1
FlowRule rule = new FlowRule();
rule.setResource("hello");
rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
rule.setCount(1);
FlowRuleManager.loadRules(Collections.singletonList(rule));

// 2. 业务代码圈出要保护的资源
public String hello() {
    try (Entry entry = SphU.entry("hello")) {   // 进入资源——检查站
        return "hello";
    } catch (BlockException e) {                 // 被限流/熔断 → 走这里
        return "blocked";
    }
}
```

同一秒并发 3 次：`请求1→hello（通过）`、`请求2/3→blocked（超限拒绝）`。

- **它干了什么**：在方法外面套一层**检查站**——`SphU.entry()` 按规则判"放不放行"，不放行抛 `BlockException`（**业务自己决定降级返回什么**）。
- **本篇回答**：哪三类问题需要它？5 个核心概念是什么？规则从哪来？

## §1 掌握卡（4 条）

1. **三场景三答案**（D0 §二）：流量尖峰→**流控**（超阈值拒绝/排队）；下游变慢→**熔断**（慢调用/异常率超标快速失败一段时间）；单参数被刷→**热点参数限流**。
2. **定位一句话**：**流量防卫兵**——以"资源"为中心，把流控/熔断/系统保护统一在一条 **SPI 责任链（Slot Chain）** 上；对比 Hystrix（线程池隔离、已停更），Sentinel=统计窗口+规则驱动，轻量且规则可动态。
3. **5 个核心概念**（D0 §3.1，开工够用）：**资源**（被保护的东西）/ **规则**（策略：Flow/Degrade/…）/ **Entry**（一次调用的生命周期）/ **Context**（调用链共享的上下文）/ **Slot Chain**（规则检查的责任链）。
4. **规则三种来法**（D0 §3.3）：代码硬编码（仅实验）/ Dashboard（重启丢）/ **DataSource+Nacos 持久化（生产推荐——04 讲）**。

## §2 为什么不能手写 if（D0 §二收口）

三个需求把 if 判死：**规则要动态改**（不重启）、**多实例要一致**、**要能观测**（Dashboard）——所以需要"统计 + 规则 + 数据源"的完整体系，这就是 Sentinel 的三块骨架（本系列 02/03/04 各领一块）。

## §3 lab 实测须知（防踩空）

单次 curl 验不出限流——**必须并发**（统计窗口按秒计）：

```bash
for i in $(seq 1 5); do curl -s "http://localhost:28011/sentinel/api?key=t" & done; wait
# 阈值内 → 下游数据；超限 → {"blocked":true,...}
```

## §4 边界（一句话翻篇）

- 9 个 Slot 各管什么 → 02。
- EagleEye 日志/Transport 排障 → 翻篇（lab 的 D0=EagleEye 日志框架、D12=Transport，用时查）。
- 为什么不用 Resilience4j → 07 收口一句。

## §5 Self-Test（3 题）

- Q1：三场景各对应什么规则？BlockException 抛给谁处理？
- Q2：5 个核心概念一句话各是什么？
- Q3：规则三种来法，生产用哪种？为什么硬编码不行？

## §6 衔接

- 概念层收口；下篇 02 原理：一条请求进 `entry()` 后经过哪 9 段流水线（Slot 链）+ 统计数据怎么来（滑动窗口）。
