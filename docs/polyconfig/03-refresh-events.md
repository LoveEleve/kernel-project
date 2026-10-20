---
title: "03-原理二：刷新与统一事件"
series: "PolyConfig 配置统一抽象系列"
previous: "02-startup-loading.md"
next: "04-usage-pitfalls.md"
source_refs:
  - "已核：ResourcePropertySourcesRefresher（refresh:96/doRefresh synchronized:105/diff Objects.equals/rebuildAndReplace→replace:152,addLast/锁外发事件/同步监听器）/ ConfigChangePublisher（publish:50→publishEvent:54/空不发:48）/ EnvironmentConfigChangeListener（:54/SOURCE_NAME=environment:59/old 恒 null:76/new 可为 null）/ ConfigPropertiesChangedEvent（:52/isChanged:118）"
---

# 03-原理二：刷新与统一事件

## §0 它干了什么（例子先行）

Nacos 上把 `pool.max=10` 改成 `20`，代码侧发生什么（源码链）：

```
① Nacos Listener.receiveConfigInfo（02 篇 configureRefresher:89 挂上的）
   → refresher.refresh(dataId, 新内容):96
② doRefresh:105（synchronized——锁内干三件）：
   load 新内容 → flatten 新旧两份 → diff（Objects.equals 逐属性）
   → 没变？return null【**不发事件**——README"无变化不发"实锤】
   → 有变？resourceSources.put + rebuildAndReplace（replace:152 替换 Environment 里的组合源）
③ 回到 refresh（锁外！）：changePublisher.publish → ConfigPropertiesChangedEvent:54
   → 业务 MyListener.isChanged("pool.max") 命中 → 动作
```

- **它干了什么**：**diff 出真变化才替换、才发事件**——并且**状态更新在锁内、事件发布在锁外**。
- **本篇回答**：为什么"锁外发事件"是关键设计？双路事件（声明式 vs 环境适配）怎么合流？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **diff 语义** | 新旧 flatten 后按 key 并集逐个 `Objects.equals`——**属性级**（不是全文比对），产出 `(prop, oldValue, newValue)` 三元组 |
| **rebuildAndReplace** | 重建 CompositePropertySource → `propertySources.replace` 原位替换（:152；不存在则 addLast）——**Environment 不重建，只换源** |
| **锁边界（设计核心）** | `doRefresh synchronized`（:105）管状态；`publish` 在 refresh 主体=**锁外**——README 设计要点原话："监听器永不在持锁状态下执行"。注意：锁外发布**仍是同步**的，监听器在发布线程（Nacos 客户端通知线程）执行 |
| **ConfigChangePublisher** | 事件出口 :50——**空 changes 不发**（:48） |
| **双路来源** | 路 A 声明式（source=属性源名）；路 B **环境适配** `EnvironmentConfigChangeListener:54` 监听 Spring Cloud `EnvironmentChangeEvent` → 转发统一事件（source="environment":59，**old 恒 null**:76） |

## §2 两问推导

**① 为什么锁外发事件？——锁外只解决"不持锁"，不解决"不同步"**
监听器是任意业务代码，可能重、可能回调 refresh。若**持锁**执行，会阻塞其他刷新线程，严重时死锁。所以锁内只做"快而必须原子"的（diff+替换+记状态），事件在锁外发布。

**但锁外 ≠ 异步**：Spring 事件默认同步，`publishEvent` 在当前线程依次调用监听器。实测（06 §1）：发布线程 `nacos-notify-thread`，监听器线程同名；监听器 sleep 300ms，`publish` 阻塞 309ms。所以监听器仍跑在 **Nacos 客户端通知线程**上，慢监听器会阻塞该线程——只是不占锁，其他线程的刷新可以继续。要真正异步，需给 `ApplicationEventMulticaster` 配 `TaskExecutor` 或在监听器内异步；框架没有默认开启。

**② 双路事件为什么会重复发？（04 坑 1 的机制预告）**
声明式加载自己有 Listener（路 A），Spring Cloud 客户端感知变更后又发 EnvironmentChangeEvent（路 B 被动转发）——**两条路径都启用时**，同一次配置变更两条感知路径都亮，各发一个 `ConfigPropertiesChangedEvent`。**这不是 bug 是双保险的代价**——消费端按 `source` 过滤或幂等（README 注意事项 1）。

## §3 掌握卡（2 条）

1. **刷新四步**：Listener 推内容 → 锁内 load/flatten/diff → 有变才 replace+发事件（无变静默）——**属性级 diff 决定了事件带新旧值**（01 对照表的兑现）。
2. **锁内状态锁外事件**——"监听器不在持锁下执行"是线程安全的正确姿势；**但事件默认同步**，监听器仍占用 Nacos 客户端通知线程，异步要自己配 multicaster/监听器。
3. **双路感知**：声明式加载 + Spring Cloud 标准刷新同时启用时，同一次变更**可能**发两个事件（条件成立才重复，06 §3 有实验）；消费端按 source 过滤或幂等。

## §4 边界（一句话翻篇）

- SnakeYAML/properties 解析 → 翻篇。
- ConfigPropertiesChangedEvent 的 isChanged/getChangedPropertyNames 等 API → 04 使用篇 / 06 §4.5。
- Nacos Listener 的推送机制细节 → nacos 系列 04 已封卷。

## §5 Self-Test（3 题）

- Q1：无变化时会发事件吗？在哪一步拦掉的？
- Q2：为什么 publish 必须在锁外？持锁发会怎样？
- Q3：source="environment" 的事件 oldValue 为什么是 null？消费端注意什么？

## §6 衔接

- 刷新链收口；下篇 04 使用与三坑：注解属性表、重复事件/旧值 null/YAML 编码，以及 polydata 怎么消费这个事件（家族联动兑现）。
