---
title: "04-SCA装配地图与版本矩阵"
series: "Spring Cloud 抽象与 SCA 装配系列"
previous: "03-cloud-context.md"
next: "05-面试收口.md（待写）"
source_refs:
  - "xhs pom 实锤：Boot3.2.5×SC2023.0.3×SCA2023.0.1.2；nacos 2.3.0/sentinel 1.8.8（显式覆盖 SCA 传递的 1.8.6）/rocketmq-spring 2.3.0"
---

# 04-SCA装配地图与版本矩阵

## §0 它干了什么（例子先行）

xhs 一张 pom 管住整个微服务宇宙（实锤数字）：

```
Boot 3.2.5 ←×→ Spring Cloud 2023.0.3 ←×→ SCA 2023.0.1.2
SCA 各 starter 再传递：nacos-client 2.3.0 / seata 2.x / sentinel 1.8.6…
xhs 的两个"不听话"动作：
  ① sentinel.version 显式 1.8.8——**覆盖 SCA 传递的 1.8.6**（pom 注释原话）
  ② rocketmq-spring 2.3.0 自管（它不在 SCA 传递链里）
```

- **它干了干什么**：**矩阵对齐是 SCA 工程的第一能力**——starter 之间的隐式传递版本，必须能看穿、能覆盖。
- **本篇回答**：starter→抽象→系列的总装地图什么样？矩阵怎么查、怎么覆盖？

## §1 总装地图（主表：每个 starter 挂哪个抽象）

| starter（SCA/SC） | 挂的抽象（第一层） | 实现（第二层） | 已封卷系列 |
|---|---|---|---|
| `…alibaba-nacos-discovery` | DiscoveryClient/ServiceRegistry | NacosServiceRegistry 等 | Nacos 02 |
| `…alibaba-nacos-config` | ConfigData Loader /（旧）PropertySourceLocator | nacos-client | Nacos 05·07 / 本系列 03 |
| `…alibaba-seata` | （事务协调接入） | seata-spring | Seata 系列（05 集成层） |
| `…alibaba-sentinel` | 熔断限流适配（Feign/WebMVC） | sentinel-adapter | Sentinel 04·06 |
| `…openfeign` | Feign 抽象 | OpenFeign+LB 链 | OpenFeign 系列 |
| `…gateway` | （SC Gateway 体系） | WebFlux 网关 | Gateway 系列 |
| （非 SCA）rocketmq-spring | —（Boot starter 直连） | rocketmq-client | RocketMQ 08 |
| spring-cloud-loadbalancer | LoadBalancerClient | Blocking/Reactor 实现 | 本系列 02+docs/loadbalancer |

**读表口诀**：**看 starter 名字猜抽象，看已封卷系列找电器**——地图的作用是"知道去哪查"，不是背。

## §2 版本矩阵纪律（工程三动作）

| 动作 | 做法 | xhs 实例 |
|---|---|---|
| **对齐** | Boot×SC×SCA 查官方配套表再锁版本 | 3.2.5×2023.0.3×2023.0.1.2 |
| **看穿传递** | `mvn dependency:tree` 看 starter 暗带的客户端版本 | sentinel 1.8.6（SCA 暗带） |
| **覆盖** | 父 pom properties 显式钉版本 | `sentinel.version=1.8.8` 显式覆盖（pom 注释原话"覆盖 Spring Cloud Alibaba 传递的 1.8.6"） |

**升级顺序铁律**（01 §1 回收）：**Boot → SC → SCA → 各客户端**——反着升=条件装配连环静默失效。

## §3 掌握卡（2 条）

1. **地图即导航**：8 行主表=本 session 十余系列的"SC 侧门牌"——面试被问任何 SCA 组件，先答抽象层名字（第一层）再答实现细节（专系列）。
2. **覆盖是合法操作**：SCA 传递版本只是默认值——**父 pom properties 覆盖是标准姿势**（前提：兼容性自查，如 sentinel 1.8.6→1.8.8 同大版本）。

## §4 边界（一句话翻篇）

- 每个 starter 的完整依赖树 → mvn dependency:tree 用时跑。
- SCA 未覆盖组件（如 SCA 之外的 RocketMQ Spring）→ 表已标"非 SCA"。
- 版本兼容性矩阵官方全文 → alibaba/spring-cloud-alibaba GitHub 配套表。

## §5 Self-Test（3 题）

- Q1：nacos-discovery 和 nacos-config 各挂哪个抽象？
- Q2：xhs 怎么把 sentinel 从 1.8.6 换成 1.8.8？什么场景必须这么干？
- Q3：升级顺序铁律是什么？反着升的典型症状？

## §6 衔接

- 总装图收口；下篇 05 面试收口：四篇回链 + 高频坑 + 终检——B 计划完结。
