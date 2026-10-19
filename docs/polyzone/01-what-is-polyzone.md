---
title: "01-什么是 PolyZone：多活与区域感知"
series: "PolyZone 异地多活路由系列"
previous: "README.md"
next: "02-locate-chain.md（待写）"
source_refs:
  - "仓库 README（206 行：特性表/五模块/SC 对照/定位链/十步算法/切换链）+ 源码结构已核（26 类，五模块）"
cross_refs:
  - "loadbalancer 系列（过滤器链已封卷——本系列是其上的区域过滤器）；polyconfig ①（EnvironmentChangeEvent 同款事件入口）"
---

# 01-什么是 PolyZone：多活与区域感知

## §0 它干了什么（例子先行）

三地部署同一套微服务（README 例子实锤）：

```
北美 us-east-1a、中国 cn-hangzhou-b、欧洲 eu-west-1a
请求到北美实例 → 调 user-service → LoadBalancer 无区域意识随便挑（可能挑到欧洲节点）= 白白 +100ms

接入 PolyZone：
① 自动定位：本实例知道自己在 us-east-1a（4 类定位器，02 篇）
② 同区优先：只在 us-east-1a 的实例里挑（十步算法，03 篇）
③ 保底：同区不够 → 自动退化全量列表，绝不把流量打死
④ 秒切：运维把 polyzone.zone 改成 eu-west-1a → 所有实例秒级切欧洲
⑤ 回滚：改回 originalZone → 各自回到原本区域
```

- **它干了干什么**：**给调用装"地缘感"**——同区优先省延迟、故障区秒切、切完能回。
- **本篇回答**：多活要解决什么？五模块各管哪段？Spring Cloud 不是自带 ZonePreference 吗？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **Zone（可用区）** | 云上物理隔离的部署单元（us-east-1a 这类）——**同区=低延迟，跨区=百 ms 级** |
| **ZoneLocator** | 定位器 SPI（可插拔扩展点）——"我是哪个区的"（4 类实现，02 篇） |
| **ZonePreferenceFilter** | 同区优先过滤器——LoadBalancer 挑实例前先过滤（十步，03 篇） |
| **ZoneContext** | 当前区域的**单一事实源**——setZone 触发事件（04 篇） |
| **ZoneContextChangedEvent** | 区域变更广播——数据源/Redis 等订阅联动（**polydata 双事件的另一条腿**，polyconfig 04 见过入口） |

**五模块**（README :35-50，依赖单向）：

```
polyzone-commons（零框架依赖：Context/Filter/Resolver）← spring ← spring-boot ← spring-cloud（LB 接线）
                                                            ↑
                                                    polyzone-aws（三类元数据定位器）
```

## §2 为什么内置的不够（推导 + 对照表）

**朴素答案**：Spring Cloud LoadBalancer 自带 ZonePreference，配 `zone` 属性就完了？

**对照**（README 原表）：

| | Spring Cloud 内置 | **PolyZone** |
|--|--|--|
| 区域发现 | **手动配** `spring.cloud.loadbalancer.zone` | 4 类 ZoneLocator **自动发现**（AWS EC2/ECS+属性兜底） |
| 安全阈值 | 无 | **就绪率+最小可用数双阈值**（03 篇退化保底） |
| 禁用区域 | 无 | disabled-zone |
| 动态切换 | 改配置需刷新上下文 | **EnvironmentChangeEvent 秒级生效** |
| 资源联动 | 无 | **ZoneContextChangedEvent** 广播到数据源/缓存 |
| 故障恢复 | 无 | originalZone 回滚 |

**推导**：内置版是"**静态选区**"——区写死在配置里、切一次要刷上下文、也没有"同区不够别硬选"的保护。**Therefore**：多活场景要的是"**动态感知+安全退化+联动**"三件。**Cost**：多一套组件要接线（05 篇配置表）。

## §3 掌握卡（2 条）

1. **五件事**：定位（我在哪）→ 过滤（同区优先）→ 保底（退化不致死）→ 切换（配置秒效）→ 回滚（originalZone）——**02-04 三篇各深挖一段**。
2. **与 SC 内置的分界**：自动发现 vs 手动配区、双阈值 vs 无、秒切 vs 刷上下文、事件联动 vs 无、禁用区域 vs 无、originalZone 回滚 vs 无——**六点差异就是选型理由**。

## §4 边界（一句话翻篇）

- AWS 元数据端点细节 → 02 一句带过。
- LB 过滤器如何插入链 → loadbalancer 系列已封卷，05 篇接线说明。
- 注册 metadata 传播 → 05 使用篇+下游按需回链。

## §5 Self-Test（3 题）

- Q1：五模块依赖方向？commons 为什么零框架依赖？
- Q2：与 SC 内置 ZonePreference 的六点差异？
- Q3："偏好永不致死"指什么？（每步不满足即回原列表——03 篇）

## §6 衔接

- 概念收口；下篇 02 区域定位链：CompositeZoneLocator 四层 Order 谁先试、结果缓存、非 AWS 的 3 秒坑。
