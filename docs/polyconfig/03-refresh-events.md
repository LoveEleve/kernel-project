---
title: "03-原理二：刷新与统一事件"
series: "PolyConfig 配置统一抽象系列"
previous: "02-startup-loading.md"
next: "04-usage-pitfalls.md（待写）"
source_refs:
  - "已核：ResourcePropertySourcesRefresher（refresh:93/doRefresh synchronized:102/diff Objects.equals/rebuildAndReplace→replace:149,addLast/锁外发事件）/ ConfigChangePublisher（publish:45→publishEvent:49/空不发:43）/ EnvironmentConfigChangeListener（:52/SOURCE_NAME=environment:57/old 恒 null:74）/ ConfigPropertiesChangedEvent（:52/isChanged:42 javadoc）"
---

# 03-原理二：刷新与统一事件

## §0 它干了什么（例子先行）

Nacos 上把 `pool.max=10` 改成 `20`，代码侧发生什么（源码链）：

```
① Nacos Listener.receiveConfigInfo（02 篇 configureRefresher:88 挂上的）
   → refresher.refresh(dataId, 新内容):93
② doRefresh:102（synchronized——锁内干三件）：
   load 新内容 → flatten 新旧两份 → diff（Objects.equals 逐属性）
   → 没变？return null【**不发事件**——README"无变化不发"实锤】
   → 有变？resourceSources.put + rebuildAndReplace（replace:149 替换 Environment 里的组合源）
③ 回到 refresh（锁外！）：changePublisher.publish → ConfigPropertiesChangedEvent:49
   → 业务 MyListener.isChanged("pool.max") 命中 → 动作
```

- **它干了干什么**：**diff 出真变化才替换、才发事件**——并且**状态更新在锁内、事件发布在锁外**。
- **本篇回答**：为什么"锁外发事件"是关键设计？双路事件（声明式 vs 环境适配）怎么合流？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **diff 语义** | 新旧 flatten 后按 key 并集逐个 `Objects.equals`——**属性级**（不是全文比对），产出 `(prop, oldValue, newValue)` 三元组 |
| **rebuildAndReplace** | 重建 CompositePropertySource → `propertySources.replace` 原位替换（:149；不存在则 addLast）——**Environment 不重建，只换源** |
| **锁边界（设计核心）** | `doRefresh synchronized`（:102）管状态；`publish` 在 refresh 主体=**锁外**——README 设计要点原话："监听器永不在持锁状态下执行" |
| **ConfigChangePublisher** | 事件出口 :45——**空 changes 不发**（:43） |
| **双路来源** | 路 A 声明式（source=属性源名）；路 B **环境适配** `EnvironmentConfigChangeListener:52` 监听 Spring Cloud `EnvironmentChangeEvent` → 转发统一事件（source="environment":57，**old 恒 null**:74） |

## §2 两问推导

**① 为什么锁外发事件？（这是本篇最重要的一句）**
事件监听器里业务可能干重活（重建连接池=秒级）——**若持锁发布，Nacos 推送线程被业务拖死，甚至与其它监听器死锁**。所以：锁内只做"快而必须原子"的（diff+替换+记状态），事件出去后爱跑多久跑多久。**同族**：orm-pool 08 的"快借快还"、Dubbo 的 IO 线程不跑业务——**框架线程与业务线程解耦**的又一次。

**② 双路事件为什么会重复发？（04 坑 1 的机制预告）**
声明式加载自己有 Listener（路 A），Spring Cloud 客户端感知变更后又发 EnvironmentChangeEvent（路 B 被动转发）——**同一次配置变更两条感知路径都亮**，各发一个 `ConfigPropertiesChangedEvent`。**这不是 bug 是双保险的代价**——消费端按 `source` 过滤或幂等（README 注意事项 1）。

## §3 掌握卡（2 条）

1. **刷新四步**：Listener 推内容 → 锁内 load/flatten/diff → 有变才 replace+发事件（无变静默）——**属性级 diff 决定了事件带新旧值**（01 对照表的兑现）。
2. **锁内状态锁外事件**——"监听器不在持锁下执行"是线程安全的正确姿势；**双路感知必然可能重复**，按 source 处理。

## §4 边界（一句话翻篇）

- SnakeYAML/properties 解析 → 翻篇。
- ConfigPropertiesChangedEvent 的 isChanged/getChangedPropertyNames API → 04 使用篇。
- Nacos Listener 的推送机制细节 → nacos 系列 04 已封卷。

## §5 Self-Test（3 题）

- Q1：无变化时会发事件吗？在哪一步拦掉的？
- Q2：为什么 publish 必须在锁外？持锁发会怎样？
- Q3：source="environment" 的事件 oldValue 为什么是 null？消费端注意什么？

## §6 衔接

- 刷新链收口；下篇 04 使用与三坑：注解属性表、重复事件/旧值 null/YAML 编码，以及 polydata 怎么消费这个事件（家族联动兑现）。
