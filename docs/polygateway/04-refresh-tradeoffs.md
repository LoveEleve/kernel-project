---
title: "04-原理三：刷新与接入取舍"
series: "PolyGateway 接口级网关系列"
previous: "03-route-generation.md"
next: "05-usage-pitfalls.md"
source_refs:
  - "已核：PolyGatewayRouteRefreshListener（:34 监听 HeartbeatEvent/无条件发布 RefreshRoutesEvent:43-44）/ applyFilterRules:184-199（matches:77 后 filters.addAll——StripPrefix 保留:182 注释）/ PolyGatewayFilterRule 语义（:32-34 services 精确/patterns Ant/methods 交集、空=全）/ GatewayAutoConfiguration（:52-57 @AutoConfiguration/@AutoConfigureOrder(LOWEST_PRECEDENCE):53/@ConditionalOnClass:54/enabled:55）/ ProviderAutoConfiguration（:45-46 SERVLET+事件类在场）/ pom：SCG optional:27-28+loadbalancer 显式:33-37（注释实锤）"
---

# 04-原理三：刷新与接入取舍

## §0 它干了什么（例子先行）

新接口发布了，网关怎么知道（心跳 6 步——guide §4.7 的源码版）：

```
① 注册中心心跳 → Spring 发 HeartbeatEvent
② PolyGatewayRouteRefreshListener:34 收到 → 无条件发布 RefreshRoutesEvent:43-44
   （不做"心跳值变没变"的启发式——宁可多刷不可漏刷）
③ CachingRouteLocator 清缓存 → 重新调用所有 RouteDefinitionLocator
④ 我们的 locator 重新 getInstances:126（**内部无缓存，每次现算**）
⑤ 新清单 → 新路由 → 新端点立即可访问
⑥ 网关侧 filterRules 变更也走同一条链（:185-197 下次生成时生效）
```

**接入取舍**（为什么是 RouteDefinitionLocator 而不是 GlobalFilter——guide §5 四行对照的源码依据）：

| 维度 | GlobalFilter | **RouteDefinitionLocator（本项目）** |
|---|---|---|
| 选实例 | 自己写（易阻塞） | **官方响应式 LB**（lb://:89） |
| 匹配 | 自己写引擎 | **官方谓词**+2 自研（03 篇映射表） |
| 可观测 | 黑盒 | `/actuator/gateway/routes` 直接看 |
| 代价 | 每请求跑匹配 | 路由数=接口数；变更等心跳 |

**依赖两个 optional 的讲究**（pom 注释实锤 :27-37）：

```
SCG starter 声明 optional → 只用 provider 侧的 MVC 服务不被拖进网关栈
loadbalancer 必须显式引 → SCG 自己也把它 optional 了，不引则 lb:// 全 503（陷阱簿 #1）
```

- **它干了什么**：刷新=**无条件重算**（简单即可靠）；接入=**把匹配与选实例全部让渡给官方**。
- **本篇回答**：心跳链为什么"无脑刷"？filterRules 挂在哪？两个 optional 各防什么？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **无条件 RefreshRoutesEvent** | :43-44 不判断心跳内容——**30s 一次全量重算是可接受成本**，启发式的漏刷不可接受 |
| **locator 无内部缓存** | 每次 loadRouteDefinitions 现算——缓存责任在 CachingRouteLocator（官方层），**自己不再叠一层**（与"翻译不替代"同哲学） |
| **filterRules** | 按 services/patterns/methods 三维匹配（:77，空=全 :32-34）→ filters.addAll:193 **追加在 StripPrefix 之后**（:182 注释）——限流/重试等官方过滤器按规则上车 |
| **条件装配双侧** | gateway 侧 :52-57（有 RouteDefinitionLocator+DiscoveryClient 类且开关开，**@AutoConfigureOrder 最后排序 :53**）；provider 侧 :45-46（SERVLET Web+注册前事件类在场）——**类在场才装配，防误引入** |

## §2 关键推导（三问）

**① 为什么"无条件刷"反而是对的？**
心跳 30s 级——**每次刷新成本=重读 metadata+重生成路由（毫秒到几十毫秒）**；而"判断心跳值变没变"的启发式一旦漏判（注册中心不发心跳/值没变但实例换血），**新接口不可路由且无告警**。**可靠性>微优化**——简单策略赢。

**② filterRules 为什么挂生成期（:193 在 createRouteDefinition 里）而不是过滤器链？**
与排除同理：挂生成期=**路由带着 filter 出生**——actuator 可见、与 yaml 静态路由同构；请求期再判断=每请求一次规则匹配（guide §5 代价行）。**生成期做完所有决定**是本项目的一贯姿势（排除:287、filterRules:185、路径拼接:250 全在生成期）。

**③ 为什么 gateway 自动配置要最后排序？**
`@ConditionalOnBean(DiscoveryClient.class)` 是**注册期条件**：如果 PolyGateway 的自动配置先于 `SimpleDiscoveryClientAutoConfiguration` 等 discovery 自动配置处理，`DiscoveryClient` 还没注册，locator 就会被静默跳过。`@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE):53` 让它在所有自动配置之后处理，才能看到 simple/Nacos/Eureka 等贡献的 `DiscoveryClient`（测试见 `testGatewayLocatorWithSimpleDiscoveryClient`）。

## §3 掌握卡（2 条）

1. **刷新链 4 步**：心跳事件→无条件 RefreshRoutes→官方清缓存→locator 现算——**不叠自己的缓存层**。
2. **取舍一句话**：**匹配、选实例、可观测三样全让渡官方**，自己只做"清单↔路由"的翻译；代价=路由数与刷新延迟（可接受）。

## §4 边界（一句话翻篇）

- CachingRouteLocator 内部 → gateway 系列翻篇。
- RequestRateLimiter/Retry 过滤器实现 → gateway 系列按需。
- 多注册中心 composite 空列表坑 → 05 陷阱簿。

## §5 Self-Test（3 题）

- Q1：写出刷新 4 步；为什么 locator 不自己缓存？
- Q2：filterRules 的匹配维度？追加时机（StripPrefix 前后）？
- Q3：SCG starter 与 loadbalancer 两个 optional 各防什么坑？

## §6 衔接

- 刷新与取舍收口；下篇 05 使用：7 条工程陷阱簿 + 配置要点 + E2E 九场景动手路径。
