# PolyGateway 接口级网关系列

> 语料：**polygateway 源码**（4 模块 / 16 主类——已核）+ **docs/polygateway/polygateway-study-guide.md**（280 行吃透指南：全链路/16 类地图/7 陷阱/12 题自测——**本系列的总索引，保留不删**）+ 仓库 README
> 关键锚点（已核）：WebEndpointRouteDefinitionLocator（:73/loadRouteDefinitions:110/createRouteDefinition:169,173/routeOrder:266-285 公式逐字/STRIP_PREFIX_FILTER:89）/ WebEndpointMapping（:44/stableId:160-169 六维 31 链（kind.name））/ WebEndpointExclusion（AND:28/patterns 必填:32/matches:90）/ Collector（排序:66/comparator:131）/ RefreshListener（:34/HeartbeatEvent→RefreshRoutesEvent 无条件:43-44）
> 依赖位次：**poly 家族 ④**（独立于 ①②③——网关只消费注册中心元数据）
> 诚实边界：42 测试全绿（E2E 9 场景）；**xhs 未直用**（xhs 网关是 spring-cloud-gateway 直配）——落点按"机制+取舍"讲

## 一、总表（6 篇：概念 → 原理 → 使用 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 PolyGateway：接口级路由](01-what-is-polygateway.md) | **概念** | SCG 两条路线缺口/方案 What；四模块 | guide §1+仓库 README |
| 02 | [原理一：Provider 发布链](02-provider-publish.md) | **原理** | 六维模型/stableId/确定性排序/metadata 发布 | core/provider 源码 |
| 03 | [原理二：路由生成与请求旅程](03-route-generation.md) | **原理** | 谓词映射/路径公式/特异排序/生成期排除/全实例并集 | gateway 源码 |
| 04 | [原理三：刷新与接入取舍](04-refresh-tradeoffs.md) | **原理** | 心跳 6 步刷新/filterRules/为何 RouteDefinitionLocator 不写 GlobalFilter | gateway/boot 源码 |
| 05 | [使用：陷阱簿与动手路径](05-usage-pitfalls.md) | **使用** | 7 条工程陷阱/配置要点/E2E 九场景阅读路径 | guide §6-§7 |
| 06 | [面试收口](06-interview-wrapup.md) | **面试** | 五篇回链；高频坑；终检；简历对照 | guide §8-§9 |

## 二、学习路径

```text
概念：01
原理：02 → 03 → 04
使用：05
收口：06（可对照 guide §8 自测 12 题）
```

## 三、前置要求

- **loadbalancer 系列**（已封卷——03/04 篇的 lb:// 负载均衡与 503 陷阱是其上的应用题）。
- gateway 系列（SCG 直配路线已封卷——本系列是其"自动路由"补丁，对照基础）。
- spring-cloud-alibaba 系列（注册中心/metadata 语境）。

## 四、掌握分级

- **必须掌握**：两条路线缺口→方案；六维→谓词映射；路径三行公式；生成期排除；心跳刷新链；Locator vs GlobalFilter 取舍。
- **理解思想**：**"翻译成标准路由，剩下交给官方"**——不造匹配引擎、不写选实例逻辑。
- **知道即可**：stableId 哈希细节；多注册中心 composite 坑的实现细节；聚合 pom 关系。

## 五、边界

- SCG 内核/过滤器链 → gateway 系列已封卷。
- 注册中心选型 → nacos/consul 按需。
- 限流/重试组件内部 → sentinel/rocketmq 系列按需回链。
