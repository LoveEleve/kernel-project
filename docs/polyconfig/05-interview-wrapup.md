---
title: "05-面试收口：四篇回链与陷阱"
series: "PolyConfig 配置统一抽象系列"
previous: "04-usage-pitfalls.md"
next: "（本系列完）"
---

# 05-面试收口：四篇回链与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① 四篇要点回链 ② 高频坑速查 ③ 话术（自建）+ 终检
```

## §1 四篇要点回链

| 篇 | 一句话考点 | 回链 |
|---|---|---|
| 01 | 三层结构（元注解/Loader 模板/统一事件）；与 Spring/Spring Cloud 对照（多热更+多新旧值） | 01 |
| 02 | 五步加载链；**ImportSelector 假导入真副作用**；Composite+排位=覆盖优先级 | 02 |
| 03 | 刷新四步（无变不发）；**锁内状态锁外事件**；双路来源与重复 | 03 |
| 04 | 属性三组；三坑一限；polydata 双事件联动实锤 | 04 |

**追问秒答 4 条**：为什么 ImportSelector 不 @Bean→解析阶段早于一切 @Bean 实例化（02）；事件为什么锁外发→监听器持锁跑业务会拖死推送线程（03）；environment 事件 old 为何 null→EnvironmentChangeEvent 只带属性名（03/04）；新后端怎么加→只写 resolveResources+configureRefresher 两处（01/02）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | 双路刷新重复发事件 | 按 source 过滤/幂等（04） |
| 2 | 依赖 environment 事件的旧值 | old 恒 null——用 getNewProperties()（04） |
| 3 | YAML 配了 encoding 没用 | 固定 UTF-8——只对 properties/xml 生效（04） |
| 4 | 以为 selectImports 返回导入类 | 空数组——真活是 loadPropertySource 副作用（02） |
| 5 | 配置源排位随手 first | first/before/after=同名 key 覆盖优先级，乱排会吃掉本地默认（02） |
| 6 | 无变化也写业务刷新逻辑 | diff 空则不发事件——监听端天然收不到噪声（03） |

## §3 话术（按拷打口径自建）

> "PolyConfig 我按三层讲：注解声明（@NacosPropertySource 经 @AliasFor 归一）→ Loader 模板启动加载——Loader 是个**永远返回空数组的 ImportSelector**，借解析钩子做副作用：getConfig 拉内容、多 dataId 合成 CompositePropertySource、按 first/before/after 排位进 Environment；后端差异收敛在 resolveResources 和 configureRefresher 两个定制点，加 Apollo 只写这两处。刷新侧：Nacos Listener 推内容进来，锁内做属性级 diff——**没变不发事件**，变了 replace 原位替换，然后**锁外**发统一事件（监听器绝不在持锁下执行）。和 Spring Cloud 的差异：EnvironmentChangeEvent 只有属性名，我们带新旧值+来源；代价是双路感知可能重复发，消费端按 source 处理。polydata 就是这么消费的——配置变更和区域变更都接在这个事件总线上。"

## §4 终检（4 题，过 3 算过关）

1. 三层结构+对照表三差异（01）
2. 五步加载链+ImportSelector 为什么（02）
3. 锁边界为什么这么切（03）
4. 三坑一限+polydata 双事件（04）

## §5 衔接

- **PolyConfig 系列 5 篇完，README 全挂链接**——poly 家族 ① 闭环。
- 下一步：**polyzone**（②，26 类——polydata 依赖的另一半）。
- 本 session 累计封卷 **21 个系列 / 146 篇**。
