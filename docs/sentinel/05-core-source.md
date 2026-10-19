---
title: "05-核心源码：链的装配与窗口实现"
series: "Sentinel 流量防卫系列"
previous: "04-rules-datasource.md"
next: "06-深水.md（待写）"
source_refs:
  - "1.8.8 源码已核：DefaultSlotChainBuilder.build（SpiLoader.loadInstanceListSorted）"
  - "1.8.8 源码已核：META-INF/services/ProcessorSlot 文件九行（顺序=NodeSelector→…→DefaultCircuitBreaker）"
  - "1.8.8 源码已核：LeapArray（:48 AtomicReferenceArray/:53 updateLock/:100 calculateTimeIdx）"
---

# 05-核心源码：链的装配与窗口实现

## §0 它干了什么（例子先行）

02 说"链是 SPI 拼的"——1.8.8 源码里就是**三行装配**：

```java
// DefaultSlotChainBuilder.build()（:39-43 已核）
List<ProcessorSlot> sortedSlotList = SpiLoader.of(ProcessorSlot.class).loadInstanceListSorted();
for (ProcessorSlot slot : sortedSlotList) {
    chain.addLast(slot);   // 串成双向链
}
```

顺序的唯一权威是**一个文本文件**（core 的 `META-INF/services/...ProcessorSlot`，九行已核）：

```
NodeSelectorSlot → ClusterBuilderSlot → LogSlot → StatisticSlot
→ AuthoritySlot → SystemSlot → FlowSlot → DegradeSlot → DefaultCircuitBreakerSlot
```

- **它干了什么**：一个 SPI 文件定顺序、一个 Builder 串链、一个 Loader 加载——"可插拔"的全部实现成本。
- **本篇回答**：装配三件套怎么协作？链怎么被执行？LeapArray 的关键字段在源码里长什么样？

## §1 掌握卡（3 条）

1. **装配三件套**：`SpiLoader`（SPI 加载器，sentinel-core/spi 包）+ `ProcessorSlot` 配置文件（**顺序权威**，一行一类）+ `DefaultSlotChainBuilder`（loadInstanceListSorted 按序加载 → addLast 串链）——02 §2"加功能=加 Slot"的源码实锤。
2. **执行入口**：`CtSph.entry` → 取链（缓存复用）→ 从链头 `entry`——每槽 entry 内 fire 下一槽；业务返回后从链尾 `exit` 反向走（D2 §五细节用时查行级）。
3. **LeapArray 关键字段**（:41-53 已核，与 D1 行号一致）：`AtomicReferenceArray<WindowWrap<T>> array`（环形格）/ `ReentrantLock updateLock`（写锁）/ `calculateTimeIdx`（:100，`(time/格长)%格数`）——D1 讲的结构就是这三处。

## §2 朴素答案的死法（装配层推导）

**朴素答案**：Slot 顺序写在 Builder 的数组里？

**But**：改顺序要重新编译发版；三方扩展（如自定义 Slot）要改核心包。

**Therefore**：顺序进**资源文件**——改文本即改链（不改代码）；三方 jar 带自己的 services 文件可被 SPI 合并加载（SpiLoader 的 loadInstanceListSorted 负责归一排序）。

**Cost**：顺序的可读性从代码搬到配置文件——排错时先看这个文件（02/03 的"以 SPI 文件为准"争议收口点）。

## §3 边界（一句话翻篇）

- SpiLoader 的排序算法/优先级注解 → `spi/SpiLoader.java` 用时查。
- CtSph 链缓存与 Context 绑定 → D13。
- StatisticSlot 记账（pass/block/complete 三记）→ 源码 statistic 包用时查。

## §4 Self-Test（3 题）

- Q1：装配三件套各是什么？顺序的唯一权威在哪？
- Q2：为什么顺序放资源文件而不是 Builder 数组？
- Q3：LeapArray 环形与写锁的源码位置（冒号行）？与 D1 讲的结构一致吗？

## §5 衔接

- 源码层收口；下篇 06 深水：集群限流（TokenServer）与系统自适应（Load/CPU 全局保护）。
