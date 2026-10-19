---
title: "04-原理三：切换与联动"
series: "PolyZone 异地多活路由系列"
previous: "03-preference-algorithm.md"
next: "05-usage-config.md（待写）"
source_refs:
  - "已核：ZoneContextChangedListener（onApplicationEvent:161→tryChangeZoneContext→changeZoneContext 临时监听收集:184-191/7 属性 handler 表:166-176/有变才广播:204-213/changeZone:217 originalZone 分叉/rememberOriginalZone 只记首次:232-236）/ ZoneContext（setZone:78/先提交后通知 firePropertyChange:216-225）"
cross_refs:
  - "polyconfig 03（EnvironmentChangeEvent 同款入口）；polyconfig 04（polydata 双事件——本篇是它另一条腿的机制）"
---

# 04-原理三：切换与联动

## §0 它干了什么（例子先行）

运维在 Nacos 把 `polyzone.zone` 改成 `eu-west-1a`，秒内发生什么（源码链）：

```
① 配置刷新 → Spring Cloud 发 EnvironmentChangeEvent（polyconfig 03 同款入口）
② ZoneContextChangedListener.onApplicationEvent:161 → tryChangeZoneContext
   → 给 ZoneContext 挂临时变更监听:184（收集这轮真变了什么）
   → 依 7 属性 handler 表逐个处理:166-176（zone/enabled/偏好开关/order/三阈值）
③ changeZone:217：值是 originalZone？→ revertOriginalZone（回首次记住的区域）
                 否则 → rememberOriginalZone（仅首次记住:232）→ zoneContext.setZone
④ setZone:78 → 新值先提交，再 firePropertyChange:225（README:192"先提交后通知"）
⑤ 有变更才广播 ZoneContextChangedEvent:213
   → 业务订阅方：polydata 数据源联动（polyconfig 04 见过入口）、Redis 等
   → LoadBalancer 下一次 filter 即用新 zone（03 篇——毫秒级生效）
```

- **它干了干什么**：**配置变更→上下文变更→事件广播**三跳——每跳都"有真变化才动"。
- **本篇回答**：为什么临时监听器这个设计？"先提交后通知"保证什么？originalZone 为什么只记首次？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **changeZoneContext 三段** | 挂临时监听(:184) → 跑 handler 表 → **finally 摘监听**（:191）——只收集本轮变更，不长期挂载 |
| **7 属性 handler 表** | zone + 总开关 + 偏好开关 + filter order + 三阈值——**一个配置中心改动可以热调整条 03 篇的算法参数** |
| **先提交后通知** | setZone 写新值在前、firePropertyChange 在后（:216-225）——**监听器里读到的永远是最新值**（README:192） |
| **有变才广播** | propertyChangeEvents 空 → 不发（:204-206）——**与 polyconfig 03"无变不发"同一纪律** |
| **originalZone** | 回滚基线=**首次同步记住的区域**；`rememberOriginalZone` 仅当 `originalZone==null` 记（:232-236）；**zone 为 null（定位链没找到）不记**——下次 revert 重试定位 |

## §2 关键推导（两问）

**① 为什么用"临时监听器收集"而不是直接 diff Environment？**
一轮 EnvironmentChangeEvent 可能只改了无关属性（如日志级别）——**临时监听器把"Context 真的变了什么"落成显式事件列表**，既过滤噪声（无变更不广播），又让广播**携带逐属性新旧值**（业务按属性精细响应——polydata 的 `contains(key)` 匹配就靠它，polyconfig 04 实锤）。

**② originalZone 为什么"只记首次、null 不记"？**
- 只记首次：**回滚的锚点必须稳定**——运维连改三次 zone，基线不能被冲掉（README:191"即使属性被显式改过也能正确回滚"）。
- null 不记：定位链失败时若记了 null，revert 会"回滚到空"——**留空让下次 revert 重新走定位链**（源码注释原话，:233-235）。

**③ 双事件汇合（家族视角）**：
polydata 的 listener 同时收 `ConfigPropertiesChangedEvent`（polyconfig）与 `ZoneContextChangedEvent`（本篇）——**两个框架各自把变更归一成事件，订阅方一个类接两腿**（polyconfig 04 的双分支入口就是这里发出的）。

## §3 掌握卡（2 条）

1. **三跳链**：EnvironmentChangeEvent→（handler 表+临时收集）→ ZoneContext.setZone→**有变才** ZoneContextChangedEvent→LB/业务两路生效。
2. **两条纪律**：先提交后通知（读到的永远最新）/ 无变不发（与 polyconfig 同款）；**originalZone=首次基线+null 不记**（回滚不空转）。

## §4 边界（一句话翻篇）

- 7 个属性各自的语义 → 03 篇已讲三阈值，05 配置表收全。
- ApplicationStartedEvent/ContextRefreshedEvent 的首次同步细节 → 按需读:101-109。
- ZoneContextChangedEvent 载荷结构 → 用时读。

## §5 Self-Test（3 题）

- Q1：写出三跳链（带类名）。
- Q2："先提交后通知"保证什么？无变为什么不发？
- Q3：originalZone 两条规则（首记/null 不记）各防什么？

## §6 衔接

- 切换链收口；下篇 05 使用：配置项全表、三注意事项（非 AWS 3s/自动注册依赖/回滚基线语义）与 LB 接线。
