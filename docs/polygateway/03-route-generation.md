---
title: "03-原理二：路由生成与请求旅程"
series: "PolyGateway 接口级网关系列"
previous: "02-provider-publish.md"
next: "04-refresh-tradeoffs.md（待写）"
source_refs:
  - "已核：WebEndpointRouteDefinitionLocator（getInstances:126/LinkedHashSet 并集:130/contextPath 首个非空:143-144/排除 debug:153/createRouteDefinition:169,174/createPredicates:201-218/取反跳过:229-232/Pattern.quote:245/paths 公式:255-263/STRIP_PREFIX_FILTER:89/routeOrder:266-285）+ Consumes/Produces 两个自研谓词工厂（gateway/predicate/）"
cross_refs:
  - "gateway 系列（SCG 谓词/过滤器链/RouteDefinitionLocator 语义已封卷——本篇是其应用）"
---

# 03-原理二：路由生成与请求旅程

## §0 它干了什么（例子先行）

`GET http://gateway/order-service/api/orders/42` 的完整旅程（下游 context-path=`/api`）：

```
① 网关启动/刷新 → locator.loadRouteDefinitions():110
   对每个服务 discoveryClient.getInstances:126 → 解码全实例 metadata → LinkedHashSet 并集:130
   排除（:153 debug）→ createRouteDefinition:169：
     谓词 = createPredicates:201（六维映射，见 §1 表）
     order = routeOrder:266（特异性公式，见 §2）
     uri = lb://order-service，filters = StripPrefix=1:89
② SCG 收到请求 → 路由排序后逐条匹配谓词：
   Path=/order-service/api/orders/{id} ✓  Method=GET ✓
③ lb:// → 官方响应式 LB 选实例（零阻塞）
④ StripPrefix=1 剥 /order-service → /api/orders/42
⑤ NettyRoutingFilter 转发 → http://实例:8080/api/orders/42 → 命中，200
无路由匹配 → SCG 默认 404（"隐藏接口"的语义来源）
```

- **它干了干什么**：**生成期把清单翻译成 RouteDefinition，请求期全是官方机制**——路径公式（:255-263）与特异排序（:266-285）是两把钥匙。
- **本篇回答**：六维怎么映射成谓词？路径变换为什么正好对上？排除为什么放生成期？

## §1 概念铺垫（What：六维→谓词映射表——:201-218 逐行）

| 端点维度 | 谓词 | 编码细节 |
|---|---|---|
| patterns | `Path=`（:203 逗号 join） | 拼服务前缀+contextPath（:255 公式） |
| methods | `Method=`（:204 仅声明才生成） | 空=任意方法都通 |
| headers/params | `Header=`/`Query=`（:207-212） | **字面值 Pattern.quote 转义**（:245——`1.0` 不会误匹配 `1x0`） |
| `!x`/`x!=v` | **跳过**（:229-232 debug） | 官方谓词无法表达取反——**宁松勿错**（端点保持可达） |
| consumes | `Consumes=`（自研工厂） | Content-Type 兼容匹配 |
| produces | `Produces=`（自研工厂） | Accept 兼容；**空 Accept=通配全过**（HTTP 语义） |

**两个自研谓词的存在理由**：SCG 官方清单没有 Content-Type/Accept 维度——六维里**唯二必须自己实现**的；工厂名去后缀即谓词名（SCG 命名约定）。

## §2 路径公式与特异排序（两把钥匙）

**路径三行**（:255-263 读码即背）：

```
生成：routePath = "/" + serviceId + contextPath + endpointPattern
转发：StripPrefix=1 只剥 /{serviceId} 一层 → contextPath 保留在转发路径里
对账：/order-service + /api + /orders/{id} → 剥 1 后 /api/orders/42 = 下游完整路径 ✓
命名空间：两服务都有 /health → /svc-a/health 与 /svc-b/health 不冲突
```

**特异排序**（:266-285 公式逐字）：`order = -(字面段×2) - 变量段 + (通配段×2)`——`/orders/special`(-4) 先于 `/orders/{id}`(-3) 先于 `/orders/**`(0)；SCG 侧经 Route→Ordered→CachingRouteComparator 排序生效（guide §4.3 已验字节码）。

## §3 生成期排除与全实例并集

- **排除**（isExcluded:286→Exclusion.matches:90 六维 AND、patterns 必填 :32）：命中→**不生成路由**→404——**零运行时开销、无隐藏转发面**；代价=规则变更等下次心跳（04 篇刷新链）。
- **并集**（:130 LinkedHashSet）：全实例清单合并——**金丝雀实例独有接口也能路由**；坏 metadata 只 warn 跳过（catch :138-139，guide §4.5）；contextPath 取第一个非空 :143-144。

## §4 掌握卡（2 条）

1. **映射全表**：六维→六谓词（含转义与取反跳过）；**唯二自研=Consumes/Produces**。
2. **两把钥匙**：路径三行公式（服务名前缀+StripPrefix=1+contextPath 保留）；特异公式（字面>变量>通配）——加上"生成期排除=404 隐藏"。

## §5 边界（一句话翻篇）

- SCG 匹配/过滤器链内部 → gateway 系列翻篇。
- Consumes/Produces 兼容匹配细节 → 两个工厂类 1 小时内读完。
- E2E 九场景逐个跑 → guide §7 步骤 1。

## §6 Self-Test（3 题）

- Q1：写出路径公式与 StripPrefix 后的实际转发路径（context-path=/api）。
- Q2：取反条件为什么跳过？`X-Version=1.0` 防误匹配做了什么？
- Q3：`/orders/special` 与 `/orders/{id}` 谁先匹配？order 各是多少？

## §7 衔接

- 生成与旅程收口；下篇 04 刷新与接入取舍：心跳 6 步刷新链、filterRules、为什么选 RouteDefinitionLocator 而非 GlobalFilter。
