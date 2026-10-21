---
title: "01-什么是 PolyGateway：接口级路由"
series: "PolyGateway 接口级网关系列"
previous: "README.md"
next: "02-provider-publish.md"
source_refs:
  - "study-guide §1（两条路线缺口/例子）§3（16 类四模块地图）+ 源码结构已核（4 模块 16 主类路径全查）"
cross_refs:
  - "gateway 系列（SCG 直配路线已封卷）；loadbalancer 系列（lb:// 语义）"
---

# 01-什么是 PolyGateway：接口级路由

## §0 它干了什么（例子先行）

订单服务两个接口，一个想对外、一个不想（guide §1 原例）：

```
订单服务：GET /orders/{id}（对外）  POST /internal/reconcile（内部不想暴露）

官方 SCG 的两条路都有缺口：
  路线 A 手写 lb:// 路由 → 每个服务在网关维护一份，服务改路径网关跟着改
  路线 B discovery.locator → 自动到服务级，但路径原样透传——
        /order-service/internal/reconcile 照样被转发出去！

PolyGateway（接上后）：
  服务启动 → 把接口清单写进注册中心 metadata（发布链，02 篇）
  网关读清单 → 自动生成接口级路由（生成链，03 篇）：
    GET  /order-service/api/orders/42        → 200 转发
    POST /order-service/api/internal/reconcile → 404（excludes 没生成这路由）
    POST 同路径但 Content-Type 不符          → 404（媒体类型谓词不匹配）
```

- **它干了什么**：**"配置零维护"与"接口级精确控制"不再二选一**——清单由服务自己发，网关自动变精确。
- **本篇回答**：方案一句话是什么？四模块各管哪段？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **接口清单** | 服务的全部 HTTP 端点（路径/方法/参数/头/Content-Type/Accept **六维**）——发布进实例 metadata |
| **自动生成路由** | 网关把清单翻译成 **标准 SCG 路由**（Path/Method/Query/Header 谓词 + StripPrefix）——**不自造匹配引擎** |
| **生成期排除** | excludes 规则命中的接口**根本不生成路由** → 请求 404——隐藏接口的实现方式 |
| **心跳刷新** | 注册中心心跳 → 重算路由（04 篇）——新接口/规则变更的生效通道 |

**四模块**（16 主类——guide §3 地图的骨架）：

```
core      模型与协议：WebEndpointMapping（六维+稳定 id:160）/ Codec（JSON 跨进程）/ Exclusion（排除规则:90）
provider  只管"发"：Collector 扫 MVC（排序:65）→ Publisher 写 metadata → Listener 挂注册前事件
gateway   只管"生成"：WebEndpointRouteDefinitionLocator:73（核心）+ 2 自研谓词 + FilterRule + RefreshListener:34
boot      只管"装配"：两个条件自动配置（SERVLET Web / 有 DiscoveryClient）
```

**四层单向依赖**（guide:99 原话）：core 不知道谁在用、provider/gateway 互不相见、boot 只接线——**没有一处互相越界**。

## §2 为什么这么设计（推导）

**朴素答案**：网关感知下游接口？写个 GlobalFilter 每请求查一遍不就行了？

**But**：GlobalFilter 方案=**自己实现匹配引擎+自己选实例**——阻塞风险、黑盒、与官方谓词/静态路由不互通（guide §5 对照表四行）。

**Therefore**：**翻译而非替代**——发布期把接口变成数据（metadata JSON），网关期把数据变成 RouteDefinition，**匹配、选实例、可观测全部用官方机制**。guide §2.2 的一句话："网关做的事 = 把接口清单翻译成标准路由，之后全部交给 SCG 官方机制执行"。

**Cost**：路由数=接口数（中小规模无压力）；规则变更要等一次心跳（04 篇刷新链正是为此存在）。

## §3 掌握卡（2 条）

1. **一个缺口一个方案**：路线 A 配置维护贵、路线 B 粒度粗——**清单由服务发、路由由网关生成**，两侧都零手工。
2. **四模块分工**：core 协议 / provider 发 / gateway 生成 / boot 装——**读代码从任意一模块进都能看清边界**。

## §4 边界（一句话翻篇）

- 六维模型字段细节 → 02 篇。
- SCG 过滤器链内部 → gateway 系列翻篇。
- 注册中心选型 → nacos 系列按需。

## §5 Self-Test（3 题）

- Q1：两条官方路线各缺什么？
- Q2：四模块各管什么？依赖方向？
- Q3："翻译而非替代"换了什么、省了什么？

## §6 衔接

- 概念收口；下篇 02 Provider 发布链：MVC 接口怎么变成六维清单、稳定 id 怎么来、何时写进 metadata。
