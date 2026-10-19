---
title: "02-原理一：启动加载链"
series: "PolyConfig 配置统一抽象系列"
previous: "01-what-is-polyconfig.md"
next: "03-refresh-events.md（待写）"
source_refs:
  - "已核：PropertySourceExtensionLoader（ImportSelector:74/selectImports:85/loadPropertySource:127/Composite:138/addPropertySource:210=addFirst:213,addAfter:219）/ NacosPropertySourceLoader（resolveResources→ConfigService.getConfig:78→ByteArrayResource；configureRefresher 挂 Listener:94→refresher.refresh）/ ResourcePropertySources.load:62,flatten:101"
---

# 02-原理一：启动加载链

## §0 它干了什么（例子先行）

`@NacosPropertySource` 标上后，Spring 启动时到底跑了什么（源码链）：

```
Spring 解析 @Configuration
  → 发现 @Import(NacosPropertySourceLoader.class)
    → Loader 本身是 ImportSelector（:74）——selectImports():85 读注解属性
      → loadPropertySource():127（注意：返回空数组——**不真导入类，借 @Import 钩子干活**）
        → resolveResources():Nacos 侧 ConfigService.getConfig(dataId, group, timeout):78
          → 内容包成 ByteArrayResource
        → ResourcePropertySources.load():62 解析 .properties/.yml
        → 装进 CompositePropertySource:138（一个注解实例=一个组合源）
        → addPropertySource():210 排位：first→addFirst:213 / after→addAfter:219
```

- **它干了干什么**：**借 @Import 的钩子做副作用加载**——模板定流程，后端只出"配置内容"。
- **本篇回答**：为什么不 new 个 Bean 而用 ImportSelector？Composite 为什么必要？排位 first/before/after 谁在用？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **ImportSelector 钩子** | Loader 实现 `selectImports`——但它**永远返回空数组**（:94 `return new String[0]`）：导入类是假的，**真活是 loadPropertySource 的副作用** |
| **CompositePropertySource** | 组合源——一个注解可能带多个 dataId（`value={"a.properties","b.yaml"}`），**合并成一个命名源**进 Environment |
| **排位（first/before/after）** | 注解属性决定插入位置——**配置覆盖优先级**（同名 key 谁赢） |
| **flatten:101** | 把组合源摊平成 map——刷新时 diff 的原料（03 篇接手） |

**为什么用 ImportSelector 而不是 @Bean**（推导）：

```
朴素：配置加载写个 @Bean 不就行了？
But：@Bean 要到配置类加载中段才执行——更早初始化的 Bean 可能已经读过 Environment，
     配置源那时还没进 Environment（晚一步=全错过）
Therefore：ImportSelector 在**配置类解析阶段**触发——任何 @Bean 实例化之前完成注入，
          且天然支持"一个注解一个 Loader 子类"的后端扩展（01 §1 第②层）
Cost：副作用式编程（不返回导入类）——读代码要盯 loadPropertySource
```

## §2 后端差异怎么收敛（01 §3 兑现）

Nacos 子类只做三件（源码对账）：

| 定制点 | Nacos 实现 |
|---|---|
| `getAnnotationType:70` | 返回 NacosPropertySource.class |
| `resolveResources:75` | `ConfigService.getConfig:78` → ByteArrayResource（content==null→空数组=资源缺失语义） |
| `configureRefresher:88` | 逐 dataId `addListener:94` → `receiveConfigInfo` → `refresher.refresh`（**通往 03 的桥**） |

**换 Apollo 后端=照这三条各写一遍**——不碰模板（01 表格"新后端只实现 2 个方法"的落地；getAnnotationType 算样板）。

## §3 掌握卡（2 条）

1. **加载五步链**：@Import 触发 → selectImports（假导入真副作用）→ resolveResources（后端取内容）→ Composite 合并 → 排位进 Environment——**first/before/after 是覆盖优先级的旋钮**。
2. **ImportSelector 而非 @Bean**：配置类解析阶段更早拿 Environment + 一注解一 Loader 的扩展形状——**副作用钩子的代价是可读性**。

## §4 边界（一句话翻篇）

- properties/yml 解析细节（SnakeYAML）→ 翻篇。
- @AliasFor 展开机制 → spring-kernel 已封卷。
- 多地址/鉴权的 getConfigService → 04 使用篇一句。

## §5 Self-Test（3 题）

- Q1：selectImports 为什么返回空数组？真活在哪？
- Q2：CompositePropertySource 解决什么？（多 dataId 合一）
- Q3：first 和 after 分别影响什么？（同名 key 覆盖优先级）

## §6 衔接

- 启动链收口；下篇 03 刷新与统一事件：Listener 推来新内容后，diff/替换/发事件的完整链与锁边界。
