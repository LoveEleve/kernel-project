---
title: "05-面试收口：四篇回链与陷阱"
series: "Spring Cloud 抽象与 SCA 装配系列"
previous: "04-sca-map-versions.md"
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
| 01 | 三层模型（抽象/实现/starter）；BOM 对；升级序 | 01 |
| 02 | 一读一写（DiscoveryClient/ServiceRegistry）；Ribbon→SC LB 演进 | 02 |
| 03 | bootstrap 遗产与 ConfigData 交接；**刷新语义未换代**；双型刷新分工 | 03 |
| 04 | 总装地图 8 行；矩阵三动作（对齐/看穿/覆盖） | 04 |

**追问秒答 4 条**：SC 和 Boot 关系→SC 在 Boot 上、依赖升级 Boot 先行（01）；怎么自定义 LB→策略 @Bean，不改业务（02）；@RefreshScope 还能用吗→能，刷新机制没随 bootstrap 废弃（03）；SCA 版本谁说了算→配套表+父 pom 覆盖（04）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | Boot/SC/SCA 版本错配 | 条件装配静默失效——查配套表（01/04） |
| 2 | 忘了 SCA 传递版本"暗带"客户端 | dependency:tree 看穿再覆盖（04） |
| 3 | bootstrap.yml 与 import 双轨永久共存 | 迁移终点=删 bootstrap（03，xhs 样例） |
| 4 | 多 DiscoveryClient 不知谁生效 | Composite 按 Ordered——@Primary 定夺（02） |
| 5 | 以为 @RefreshScope 随 bootstrap 死了 | 刷新语义活着——换的只是拉取层（03） |
| 6 | 拿 Netflix 旧词汇答新问题 | Ribbon/Hystrix 已换代——答 SC LB/Resilience 侧（02） |

## §3 话术（按拷打口径自建）

> "Spring Cloud 我按三层讲：commons 定抽象（一读一写的 DiscoveryClient+ServiceRegistry、LoadBalancer、配置侧），SCA 提供 Nacos 实现，starter 完成装配——换实现不换业务。xhs 版本矩阵 Boot 3.2.5 配 SC 2023.0.3、SCA 2023.0.1.2，客户端版本用父 pom 显式覆盖（比如 sentinel 钉 1.8.8 盖掉 SCA 暗带的 1.8.6）。配置侧走 ConfigData 一步启动，bootstrap 两步时代已迁完——注意废弃的只是拉取层，RefreshEvent/@RefreshScope 刷新机制原样可用。负载均衡从停更的 Ribbon 换成 SC LoadBalancer 三件套（Supplier/策略/Client），全 @Bean 可替换。"

## §4 终检（6 题，过 5 算过关）

1. 三层各举一例；xhs 的 BOM 对（01）
2. 一读一写是哪两个接口；Composite 按什么排序（02）
3. Locator 被谁取代？刷新事件变没变（03/02）
4. sentinel 覆盖案例怎么做的（04）
5. 升级顺序铁律（01/04）
6. 自定义负载均衡的工程入口（02）

## §5 衔接

- **Spring Cloud 抽象与 SCA 装配系列 5 篇完，README 全挂链接**——"深度关联"总装图闭环：Spring（内核+Boot+MVC）三域 + SC 抽象层 + SCA 各电器系列，全部接线。
- 本 session 累计封卷 **16 个系列 / 112 篇**。
- 下一候选：ZooKeeper/Curator / 连接池与 ORM / Kafka——你定。
