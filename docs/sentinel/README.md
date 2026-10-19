# Sentinel 流量防卫系列

> 基线：**Sentinel 1.8.8**（对齐 xhs-project）；源码 `code/spring/sentinel`（tag 1.8.8）
> 素材：`sentinel-lab/docs/`（**00 从0开始** 入口篇 + D0 EagleEye + D1~D13 共 15 篇，已审校）+ `docs/中间件拷打/K05-Sentinel.md`
> xhs 实锤：`SentinelBulkheadConfig` + payment/im yml（feign.sentinel.enabled）
> 计划：L5 治理（与 Seata/Nacos 同线）

## 一、总表（7 篇：概念 → 原理 → 使用 → 核心源码 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 Sentinel：第一个限流例子](01-what-is-sentinel.md) | **概念** | What：5 概念/三场景/规则三方式 | D0 |
| 02 | [原理一：责任链与滑动窗口](02-slotchain-leaparray.md) | **原理** | Slot 链 9 段流水线；LeapArray 数据底座；Node 四层 | D2/D1/D5 |
| 03 | [原理二：流控算法与熔断状态机](03-flow-circuitbreaker.md) | **原理** | 拒绝/排队/WarmUp；CLOSED→OPEN→HALF_OPEN | D3/D4 |
| 04 | [使用：规则与动态数据源](04-rules-datasource.md) | **使用** | 五类规则怎么配；Nacos 动态推送；xhs Bulkhead 实装 | D6/D7/D8+xhs |
| 05 | [核心源码：链的装配与窗口实现](05-core-source.md) | **核心源码** | ProcessorSlotChain 怎么拼；LeapArray 关键类 | 源码 1.8.8 |
| 06 | [深水：集群限流与系统自适应](06-deep-cluster-system.md) | **原理深水** | TokenServer 加总；Load/CPU 自适应；适配器一览 | D9/D10/D11/D13 |
| 07 | [面试收口](07-interview-wrapup.md) | **面试** | K05 五段式；陷阱；终检 | K05 |

## 二、学习路径

```text
概念：01
原理：02 → 03
使用：04
源码：05
深水：06
面试：07
```

## 三、前置要求

- Hystrix/断路器常识（对比语境）；Nacos 系列 05（动态推送同款机制）。
- OpenFeign 06（feign.sentinel.enabled 的宿主）。

## 四、掌握分级

- **必须掌握**：5 概念；Slot 链流水线；滑动窗口 vs 固定窗口；流控四策略；熔断三态；Nacos 数据源。
- **理解思想**：统计与规则解耦（数据底座可换规则）；单机 vs 集群限流的取舍；系统自适应的全局视角。
- **知道即可**：EagleEye 日志/Transport 协议/热点参数细节/授权规则。

## 五、边界

- Hystrix/Resilience4j 对比 → 01 一句 + 07 收口。
- Dashboard 部署/使用 → 运维翻篇（lab 有启动脚本）。
- 熔断与 Seata/重试的联动 → 各自系列已覆盖。
