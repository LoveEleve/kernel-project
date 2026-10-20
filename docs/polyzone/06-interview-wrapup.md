---
title: "06-面试收口：五篇回链与陷阱"
series: "PolyZone 异地多活路由系列"
previous: "05-usage-config.md"
next: "（本系列完）"
---

# 06-面试收口：五篇回链与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① 五篇要点回链 ② 高频坑速查 ③ 话术（自建）+ 终检
```

## §1 五篇要点回链

| 篇 | 一句话考点 | 回链 |
|---|---|---|
| 01 | 多活五件事（定位/过滤/保底/切换/回滚）；与 SC 内置六点差异 | 01 |
| 02 | 定位链 Order 5/10/15/20；缓存首返；**非 AWS 3 秒坑**（supports 恒 true） | 02 |
| 03 | 十步决策；三阈值；**退化保底 + disabled 黑名单退化态仍有效** | 03 |
| 04 | 三跳链；先提交后通知；有变才广播；originalZone 首记+null 不记 | 04 |
| 05 | 三步接线；三注意事项各回链一章；注册 key `polyzone.zone` 读写闭环 | 05 |

**追问秒答 4 条**：非 AWS 为什么慢 3 秒→EC2 supports 恒 true 直问元数据（02）；同区只有 2 个实例选不选→min-available=5 不选，保底回全量（03）；改配置多久生效→EnvironmentChangeEvent 三跳、LB 下次 filter 毫秒级（04）；手工注册为什么没区域→只挂自动注册事件，key 自己写 `polyzone.zone`（05）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | 非 AWS 忽略 3s 直接上 | 显式配 `polyzone.zone`（02/05） |
| 2 | 忘开偏好开关 | `preference.enabled` 默认 false——只定位不路由（01/05） |
| 3 | 就绪率阈值调低硬开 | 信息不全不决策——默认 100%（03） |
| 4 | 以为退化会把 disabled 区放回来 | :108 才回原始，:137/:148 保持剔除（03） |
| 5 | 手工注册实例没 metadata | 自己写 key=`polyzone.zone`（05） |
| 6 | originalZone 理解成"上次的区" | 是**首次同步**的区——首记+null 不记（04） |
| 7 | zone 变更直接读 Environment | 走 ZoneContextChangedEvent——先提交后通知（04） |
| 8 | 以为过滤器自己选实例 | 只改候选集，LB 原策略在幸存者里挑（03/05） |

## §3 话术（按拷打口径自建）

> "多活路由我按四段讲：**定位**用组合责任链——Order 5/10/15/20 从 ECS 元数据到属性兜底，首胜缓存+写系统属性，非 AWS 的坑是 EC2 定位器 supports 恒 true 会白等 3 秒超时。**过滤**是十步决策：能力关/偏好关/zone 无效三关，禁用区/就绪率/同区最小数三阈值——每步不满足就退化回可用列表，**偏好永远不能导致无实例可用**，且退化时 disabled 黑名单依然有效。**切换**走 EnvironmentChangeEvent 三跳：handler 表处理 6 个属性、临时监听收集真变更、ZoneContext 先提交后通知、有变才广播 ZoneContextChangedEvent——数据源这些订阅方跟着切。**回滚**靠 originalZone：首次同步记基线、null 不记防空转。和 Spring Cloud 内置比，多的是自动发现、双阈值、秒切和事件联动；注册时把 `polyzone.zone` 写进 metadata，下游 CloudServerZoneResolver 同 key 读回——就绪率阈值才立得住。"

## §4 终检（5 题，过 4 算过关）

1. 五件事+六点差异（01）
2. 定位链顺序与 3 秒坑机制（02）
3. 十步三阈值+disabled 分叉语义（03）
4. 三跳链+originalZone 双规则（04）
5. 三步接线+三注意事项（05）

## §5 衔接

- **PolyZone 系列 6 篇完，README 全挂链接**——poly 家族 ② 闭环。
- 下一步：**polydata**（③，95 类/家族最大——动态多数据源+子上下文隔离+区域容错）。
