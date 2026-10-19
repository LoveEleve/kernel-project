---
title: "02-原理一：责任链与滑动窗口"
series: "Sentinel 流量防卫系列"
previous: "01-what-is-sentinel.md"
next: "03-流控与熔断.md（待写）"
lab_refs:
  - "sentinel-lab/docs/D2-ProcessorSlot-SPI责任链机制.md（§一~§四核心段已读）"
  - "sentinel-lab/docs/D1-LeapArray-滑动窗口.md（§二§三已读）"
  - "sentinel-lab/docs/D5-Node-四层节点体系.md（§一§四已读）"
---

# 02-原理一：责任链与滑动窗口

## §0 它干了什么（例子先行）

`SphU.entry("hello")` 背后，一次请求穿过 **9 个槽**：

```
NodeSelectorSlot → ClusterBuilderSlot → LogSlot → StatisticSlot
  → AuthoritySlot → SystemSlot → FlowSlot → DegradeSlot → DefaultCircuitBreakerSlot
  （建调用树）  （挂聚合节点） （记日志）（记统计）（黑白名单）（系统负载）（流控）（熔断）（熔断器）
全部通过 → 执行业务 → 反向 exit 链（StatisticSlot 算 RT、递减活跃线程）
```

- **它干了什么**：正向 9 槽检查、反向 9 槽收尾；每槽一个职责，SPI 排序装配。
- **本篇回答**：链怎么拼怎么跑？统计数据从哪来（滑动窗口）？同一个资源为什么有好几个 Node？

## §1 掌握卡（4 条）

1. **Slot 链 = SPI 责任链**：每个检查是一个 `ProcessorSlot`，顺序写在 `META-INF/services/` 配置里，`SpiLoader` 加载串链——**加功能=加一个 Slot，不改入口**（硬编码 if 的死法解药，01 §2）。
2. **四方法双向链**（D2 §二）：`entry/fireEntry`（正向，链头→链尾）+ `exit/fireExit`（反向，链尾→链头）——fire=传给下一环。
3. **LeapArray = 环形统计底座**（D1 §二）：`AtomicReferenceArray<WindowWrap<T>>` 环形数组；默认 **2 格 × 500ms**（1 秒窗）；时间→索引 `(time/格长)%格数`；**无锁读 + tryLock 写**（抢不到锁就 yield 重试）——所有规则的数据来源。
4. **Node 四层 = 统计维度**（D5）：`StatisticNode`（真实统计，挂 LeapArray）→ `DefaultNode`（**资源+入口**维度）/ `ClusterNode`（**纯资源**维度）→ `EntranceNode`（Dashboard 调用树）——"链路流控"按 DefaultNode 算，普通 QPS 流控按 ClusterNode 算。

## §2 为什么拆 9 槽（推导）

**朴素答案**：`entry()` 里 if 流量、if 熔断、if 系统负载…… 20 行判断。

**But**：每加一类保护（热点参数、集群限流…）都要改入口；顺序要调（先鉴权还是先流控？）要改代码；用户自定义检查无处插入。

**Therefore**：SPI 链——顺序在配置文件、扩展=新 Slot、每槽只管一类规则。**Sentinel 的"可插拔保护"全靠这一层**。

## §3 滑动窗口为什么比固定窗口好（D1 的问题意识）

```
固定窗口：12:00:00.900~12:00:01.100 的尖峰被切成两窗——各 900/1100，看似没超 1000 阈值，实际瞬时 2000
滑动窗口：按 500ms 格滚动统计——尖峰被完整覆盖进窗
```

LeapArray 的精妙在**成本**：格数固定（2 格），时间到点循环覆盖旧格——内存 O(1)，统计 O(格数)；CAS（比较并交换，无锁并发更新）改格内容（`currentWindow` 四种情况的并发博弈，D1 §四细节用时查）。

## §4 链 × 窗 × 节点（一张咬合图）

```
请求进入 entry()
  → 9 槽正向（§0 序）
      StatisticSlot 从哪个 Node 拿数？→ DefaultNode/ClusterNode（§1-4 维度）
      Node 的数从哪来？→ LeapArray 滑动窗口（§1-3 底座）
      FlowSlot 拿 ClusterNode 的 QPS 判放行 → 超限抛 FlowException（03 细讲）
  → 业务执行
  → 9 槽反向 exit（算 RT——响应时间、更新窗口）
```

**记忆钩**：链管"顺序与职责"，窗口管"数据"，节点管"维度"——三件套凑齐才有"按什么口径限什么流"。

## §5 边界（一句话翻篇）

- `currentWindow` CAS 四情况逐行 → D1 §四用时查。
- Hystrix/Resilience4j 对比 → D2 §六 + 07 收口。
- StatisticSlot 的 pass/block/complete 记账细节 → 源码 1.8.8（05）。

## §6 Self-Test（4 题）

- Q1：9 槽按顺序复述；fire 与 fireExit 的方向？
- Q2：为什么链顺序写在 SPI 文件里而不是代码里？
- Q3：默认窗口几个格、每格多长？无锁读+tryLock 写防什么？
- Q4：链路流控和普通 QPS 流控各看哪个 Node？

## §7 衔接

- 统计与骨架就绪；下篇 03 拿数据做决策：流控四策略 + 熔断三态状态机。
