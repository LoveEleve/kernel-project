# PolyZone 异地多活路由系列

> 语料：**polyzone 源码**（26 非测试类——已核）+ 仓库 README（206 行：特性/配置全表/定位链/十步算法/切换链/SC 对照实锤）
> 关键锚点（已核）：ZonePreferenceFilter（:53/filter:66 十步早退 :72-153）/ CompositeZoneLocator（:44 Ordered 排序+缓存系统属性 polyzone.current-zone）/ ZoneContext（setZone:75/先提交后通知:209）/ ZoneContextChangedListener（:81/onApplicationEvent:158/监听 EnvironmentChangeEvent:98）/ ZoneAttachmentListener（:41/:50 InstancePreRegisteredEvent）
> 依赖位次：**poly 家族 ②**——polydata 依赖 polyzone-commons/spring；04 篇与 polyconfig 的双事件在 polydata 侧已见（01 系列 04 篇兑现）
> 诚实边界：**无 lab、无拷打**；xhs 未直用——落点按"机制+对照 Spring Cloud 内置"讲

## 一、总表（6 篇：概念 → 原理 → 使用 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 PolyZone：多活与区域感知](01-what-is-polyzone.md) | **概念** | 问题/特性/五模块；与 SC 内置 ZonePreference 对照 | 仓库 README |
| 02 | [原理一：区域定位链](02-locate-chain.md) | **原理** | CompositeZoneLocator 四层 Order/缓存/非 AWS 3s 坑 | 定位链源码 |
| 03 | [原理二：同区域优先算法](03-preference-algorithm.md) | **原理** | 十步决策与退化保底——偏好永不致"无实例可用" | ZonePreferenceFilter 源码 |
| 04 | [原理三：切换与联动](04-switch-link.md) | **原理** | EnvironmentChangeEvent→ZoneContext→业务事件；originalZone 回滚 | Listener/Context 源码 |
| 05 | [使用：配置全表与注意事项](05-usage-config.md) | **使用** | 配置项/三注意事项/LB 接线 | README 配置表+接线源码 |
| 06 | [面试收口](06-interview-wrapup.md) | **面试** | 五篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03 → 04
使用：05
收口：06
```

## 三、前置要求

- Spring Cloud LoadBalancer 过滤器链基础（loadbalancer 系列已封卷——本系列是其上的区域过滤器）。
- polyconfig 系列（①——04 篇的 EnvironmentChangeEvent 事件源正是它同款入口）。

## 四、掌握分级

- **必须掌握**：定位链与缓存；十步算法的退化保底；切换链与 originalZone 语义；与 SC 内置的六点差异。
- **理解思想**：**偏好不能致死**（每步不满足即回原列表）与**先提交后通知**（ZoneContext 时序）。
- **知道即可**：AWS 三类元数据细节；spring.factories 扩展；Micrometer 监控 Roadmap。

## 五、边界

- 注册中心 metadata 传播的下游消费 → polydata/polygateway 按需回链。
- K8s Downward API 定位器 → Roadmap 未实现。
- 跨区域延迟监控 → Roadmap。
