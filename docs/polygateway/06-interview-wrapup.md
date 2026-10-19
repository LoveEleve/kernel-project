---
title: "06-面试收口：五篇回链与陷阱"
series: "PolyGateway 接口级网关系列"
previous: "05-usage-pitfalls.md"
next: "（本系列完）"
---

# 06-面试收口：五篇回链与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口（可对照 guide §8 十二题自测）：

```
① 五篇要点回链 ② 高频坑速查 ③ 话术（自建）+ 终检 + 简历对照
```

## §1 五篇要点回链

| 篇 | 一句话考点 | 回链 |
|---|---|---|
| 01 | 两条路线缺口→"清单服务发、路由网关生成"；四模块单向依赖 | 01 |
| 02 | 发布四步；id 跨实例一致（确定性排序:65+按序:141）；注册前最后一班车 | 02 |
| 03 | 六维→谓词全表；路径三行公式；特异公式；生成期排除；金丝雀并集 | 03 |
| 04 | 刷新 4 步（无条件刷）；filterRules 生成期挂载；Locator vs GlobalFilter；双 optional | 04 |
| 05 | 7 陷阱三分类；配置双前缀；E2E 九场景动手路径 | 05 |

**追问秒答 5 条**：lb:// 为什么必须→官方响应式 LB 零阻塞+服务发现（03/04）；StripPrefix=1 为什么是 1→只剥服务名层、contextPath 保留（03）；`1.0` 怎么防误匹配→Pattern.quote（03）；改 excludes 多久生效→等下次心跳，立即生效=手动发 HeartbeatEvent（04/05）；两服务同名 /health 冲突吗→服务名前缀=天然命名空间（03）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | lb:// 全 503 | 两因：loadbalancer 没引 / 多注册中心空列表（05 陷阱 1/2） |
| 2 | 与官方 discovery.locator 并存 | 重复路由——必须关（05 陷阱 6，E2E:67） |
| 3 | 端点条件当正则 | Pattern.quote（03/05） |
| 4 | 取反条件硬匹配 | 跳过+debug——宁松勿错（03/05） |
| 5 | 只用 provider 侧却拖进网关栈 | SCG optional 防的（04 依赖讲究） |
| 6 | 规则改了没生效 | 生成期决定→等心跳刷新（03/04） |
| 7 | 自己写 GlobalFilter 匹配 | 让渡官方四好处全丢（04 取舍表） |

## §3 话术（按拷打口径自建）

> "PolyGateway 解决 SCG 两条路线的缺口——手写路由维护贵、discovery.locator 粒度粗会把内部接口透出去。做法：服务在 InstancePreRegisteredEvent 时把 HTTP 接口扫成六维清单（路径/方法/参数/头/consume/produce），确定性排序赋 id 保证跨实例一致，encode 进实例 metadata，context-path 成对发。网关侧用 RouteDefinitionLocator 把全实例清单取并集（金丝雀友好、坏数据跳过）翻译成标准路由：六维映射 Path/Method/Query/Header 谓词、字面值 Pattern.quote 转义、取反条件跳过，Content-Type/Accept 官方没有就自研两个谓词工厂。路径公式'服务名前缀进 Path、StripPrefix 只剥它、context-path 保留'，特异公式'字面×2+变量'负数排序保证精确路由先赢。排除和 filterRules 都在**生成期**做——隐藏接口不生成路由直接 404，零运行时开销。刷新是心跳无条件发 RefreshRoutes、locator 每次现算不叠缓存。整体取舍：匹配、选实例、可观测全部让渡给 SCG 官方机制，自己只做清单到路由的翻译；代价是依赖上要把 loadbalancer 显式引进来，否则 lb:// 全 503。"

## §4 终检（5 题，过 4 算过关）

1. 缺口→方案+四模块（01）
2. 发布四步+两个一致性（02）
3. 映射表+两把钥匙+生成期排除（03）
4. 刷新链+取舍表+双 optional（04）
5. 7 陷阱+九场景（05）

## §5 简历 bullet ↔ 代码对照（guide §9 索引化）

| 简历原话 | 对应实现 | 证据 |
|---|---|---|
| 端点自动发现（零接入/排管理端点/跨实例一致） | Collector+Publisher+Listener | provider 4 测试+E2E① |
| 端点级路由（官方谓词/响应式 LB/隐藏接口 404/限流重试） | Locator+两自研谓词+FilterRule | E2E①-⑧ |
| 实时生效（心跳即刷新） | RouteRefreshListener | E2E⑨ |
| 依赖精简（SCG optional/loadbalancer 补声明） | gateway pom | 构建验证 |

## §6 衔接

- **PolyGateway 系列 6 篇完，README 全挂链接**——**poly 家族 ④ 全闭环**（①②③④=5+6+8+6=25 篇互链成网，总索引=各仓 README+本 guide）。
- 本 session 累计封卷 **24 个系列 / 166 篇**。
- 剩余候选回到主缺口：**Netty**（L5-1 最大缺口）/ L5-11 安全 / L5-13 测试 / L5-14 DDD——你点。
