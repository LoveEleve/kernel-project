# LB-05 名单管线：装饰器链与 7 预设

> 用途：讲清"名单"到"算法"之间隔着的那条管线——supplier **装饰器链**（"滤镜"模型）、Builder、**7 个官方预设逐个**、自定义链怎么搭
> 对应系列：**负载均衡（SCLB）专题 · 05**（使用篇第二篇）
> 口径：SCLB 4.1.4 / SCA 2023.0.1.2
> 素材：`loadbalancer-lab`（[A-类官方功能](../../loadbalancer-lab/docs/A-类官方功能.md) 第二批验证链 + 踩坑 / [D2 装饰器链](../../loadbalancer-lab/docs/D2-ServiceInstanceListSupplier-装饰器链体系.md)）· 网关课程 [L16](../gateway/L16-custom-loadbalancer.md)（自定义链）· [L46](../gateway/L46-sclb.md)（缓存 35s 实测）
> 前置：02（名单从哪来）/ 03（算法）/ 04（子上下文）；后续：06（权重与灰度）→ 07（摘除）→ 09（自定义）

---

## 0. 例子先行：默认链长什么样

lab 的 `/lb/supplier-chain` 端点把当前管线直接打出来（**从外到内**）：

```text
① 什么都不配（default）：
   [Caching → DiscoveryClient]
   —— 从注册中心拿全量名单 → 套一层缓存（35s）

② 配了 weighted：
   [Weighted → Caching → DiscoveryClient]
   —— 多了一层"按权重展开"（03 讲过的机制）

③ 配了 health-check：
   [HealthCheck → DiscoveryClient]
   —— 注意：这层没有 Caching（健康检查要的是实时性，07 详解）
```

一句话：**管线是一条"从注册中心取数、沿途层层加工"的洋葱**——每加一层就改变"算法最终看到的名单"。算法本身（轮询/随机）不在这条链里。

---

## 1. 掌握卡（3 条）

1. **两个角色分工**：supplier 链 = 改"**可挑集合**"（数据加工）；`ReactorLoadBalancer` = 从集合里"**挑一个**"（决策）。**7 个预设全是前者**。
2. **链是洋葱，从下往上包**：最底层是 `DiscoveryClientServiceInstanceListSupplier`（从注册中心拿全量），上面每包一层就是一个"滤镜"；打印顺序从外到内。
3. **预设覆盖大部分场景但不是全部**：7 个预设 = default / weighted / zone-preference / health-check / request-based-sticky-session / same-instance-preference / subset；**hint 没有预设**（只能 Builder）；要**叠加**多个滤镜（weighted + health-check + zone）也要 Builder 手搭（L16 就是这么干的）。

---

## 2. 底座：接口与 Builder

- **`ServiceInstanceListSupplier`**：两个方法 `get()` / `get(request)` → 返回 `Flux<List<ServiceInstance>>`（响应式）；
- **`DelegatingServiceInstanceListSupplier`**：装饰器基类——每层都持有一个 delegate，数据层层往上传；
- **`ServiceInstanceListSupplierBuilder`**：官方组装器，一整套 `withXxx()`：

  ```text
  withDiscoveryClient() / withBlockingDiscoveryClient()    ← 数据源（必须）
  withCaching()                                            ← 35s 缓存
  withHealthChecks() / withBlockingHealthChecks(...)       ← 主动健康检查
  withWeighted() / withWeighted(WeightFunction)            ← 权重（06）
  withZonePreference()                                     ← 同 zone 优先
  withRequestBasedStickySession()                          ← 请求粘性
  withSameInstancePreference()                             ← 同实例优先
  withRetryAwareness()                                     ← 重试感知（重试时剔除刚失败的实例，08）
  withHints()                                              ← 提示路由（无预设）
  withSubset()                                             ← 子集
  withBase(...)                                            ← 把外部 supplier 作为基底（L16 用它接自研过滤）
  ```

- **装在哪**：LB **子上下文**（04）——`configurations=xxx` 预设或你自己 `@LoadBalancerClient(configuration=…)` 里的 `@Bean`。

**叠加组合怎么写**（预设不够时手搭——L16 同款姿势，方法签名见 `ServiceInstanceListSupplierBuilder:92-352`）：

```java
@Bean
ServiceInstanceListSupplier stackedSupplier(ConfigurableApplicationContext context) {
    return ServiceInstanceListSupplier.builder()
            .withDiscoveryClient()      // 数据源：全量名单（必须打底）
            .withWeighted()             // 按 metadata.weight 展开
            .withZonePreference()       // 再只留同 zone（空回退全量）
            .withHints()                // 灰度提示路由（无预设，只能手搭）
            .withCaching()              // 最外层套缓存（SCLB 建议：缓存放最外）
            .build(context);
}
// 链（从外到内）：[Caching → Hints → ZonePreference → Weighted → DiscoveryClient]
```

> 注意：`withHealthChecks()` 别和 `withCaching()` 叠——官方 health-check 预设链本来就没有 Caching（实时优先；缓存会"糊住"探测结果，本篇 §3 预设 4）。

---

## 3. 7 个预设逐个（链结构 + 关键旋钮 + 场景）

> 链结构均为 lab 实测输出（`/lb/supplier-chain`）；预设通过 `spring.cloud.loadbalancer.configurations=xxx` 全局启用，或 `clients.<serviceId>.configurations=xxx` 按服务启用。

| # | 预设 | 链结构 | 一句话行为 | 关键旋钮 |
|---|---|---|---|---|
| 1 | `default` | `[Caching → DiscoveryClient]` | 全量 + 缓存 → 交给算法 | 无（默认就是它） |
| 2 | `weighted` | `[Weighted → Caching → DiscoveryClient]` | 按权重展开列表（03 的三步） | 实例 `metadata.weight`（缺省 1）；可换 `WeightFunction`（06） |
| 3 | `zone-preference` | `[ZonePreference → Caching → DiscoveryClient]` | 先只留同 zone；**空了回退全量** | `spring.cloud.loadbalancer.zone` + 实例 metadata `zone` |
| 4 | `health-check` | `[HealthCheck → DiscoveryClient]`（**无 Caching**） | 主动探测实例健康，剔除坏的 | 默认探 `/actuator/health`、间隔 25s（`health-check.path/interval`） |
| 5 | `request-based-sticky-session` | `[RequestBasedStickySession → Caching → DiscoveryClient]` | 按 cookie 粘同一实例 | cookie 名默认 `sc-lb-instance-id` |
| 6 | `same-instance-preference` | `[SameInstancePreference → Caching → DiscoveryClient]` | 优先"上次返回的实例"（重试时粘住） | 与默认重试语义相反：`retry.avoid-previous-instance=true` 是"换一台"（08） |
| 7 | `subset` | `[Subset → Caching → DiscoveryClient]` | 确定性切子集，每个调用方只看一片 | `subset.size`（默认 100）、`subset.instance-id`（默认本实例）；**实例数 ≤ size 时原样返回**（lab 实测：2 实例下看到 5:5） |

几个容易忽略的点：

- **weighted 没配 weight 不会报错**：默认权重 1 → 退化成均匀（lab 实测："无 weight metadata → 5:5"）——别误判为"权重不生效"；
- **health-check 的链里没有 Caching 是设计**：探测要实时，缓存会"糊住"结果；
- **zone 的回退是特性**：同 zone 全挂时自动打全量（防雪崩）；
- **subset 是"缩范围"不是"灰度"**：小集群（≤100 实例）看不到任何效果。

---

## 4. 预设之外：hint 与更多 `withXxx`

**hint 提示路由**（全链路灰度选实例）：

- 没有 `configurations=hints` 这个预设——写它**不报错但会让名单为空**（No instances available，lab A-08 踩坑实录）；必须 Builder：`withHints()`；
- 机制：请求头（默认 `X-SC-LB-Hint`，可配）带标签 → 只留 metadata `hint` 匹配的实例；**没有匹配 → 回退全量**；
- 用途：灰度发布时把"带灰度标签的请求"只发给灰度实例（06 展开）。

其余 `withXxx`（同实例、子集、粘性、健康检查的 builder 变体等）都是"滤镜"的另一种装法——预设能覆盖的优先用预设，覆盖不了的用 Builder。

---

## 5. 自定义链：L16 回顾（gray / zone 供应商）

L16 写的 `DemoLoadBalancerConfiguration` 就是"自搭洋葱"的完整例子：

```java
// ① 数据源：从注册中心拿全量
ServiceInstanceListSupplier discovery = ServiceInstanceListSupplier.builder()
        .withDiscoveryClient().build(context);

// ② 按模式包一层"选实例"滤镜（灰度只留 v2 / zone 优先 shanghai）
ServiceInstanceListSupplier filtered = switch (mode) {
    case "gray" -> new GrayInstanceListSupplier(discovery, "v2");
    case "zone" -> new ZonePreferredInstanceListSupplier(discovery, "shanghai");
    default -> discovery;
};

// ③ 最外层加缓存（SCLB 建议：缓存放最外层）
return ServiceInstanceListSupplier.builder()
        .withBase(filtered)
        .withCaching()
        .build(context);
```

装配三要点（L16 §16.6 的三个"不生效"坑）：**① 放对子上下文**（自定义 Configuration 不能在主扫描路径）；**② 用 `@LoadBalancerClient(configuration=…)` 挂进去**；**③ 模式下开关由属性控制、重启生效**（演示用，生产按需固化）。

---

## 6. 踩坑合辑（全部来自 lab / L16 实测）

| # | 坑 | 现象 | 对策 |
|---|---|---|---|
| 1 | `configurations=hints` 不合法 | 无 Supplier 激活 → 名单空 → "No instances available" | 用 Builder `withHints()` |
| 2 | health-check 的探测客户端也被 `@LoadBalanced` | 探测请求被拦截器二次 LB → 死循环 → 全实例被过滤 | 用**非** @LoadBalanced 的客户端（lab：`plainRestClient`） |
| 3 | `HttpStatus.OK.equals(int)` 恒 false | 健康判断全部判"不健康" | 写 `statusCode() == 200`（装箱比较坑） |
| 4 | 无 `metadata.weight` | 权重预设看起来"没生效"（均匀） | 默认权重 1 是正常行为；核对 metadata |
| 5 | 小集群配 `subset` | 看不到效果 | `size`（默认 100）> 实例数时原样返回 |

---

## 7. 生产对照

- **xhs**：在链上叠自研 filters（zone/gray 思路）+ 算法层用自研 least-conn（09）；权重/同集群则用 SCA `NacosLoadBalancer` 路线（06）；
- **demo-gateway**：`demo.lb.mode=gray/zone` 开关就是 L16 的自定义链（本篇 §5 代码）。

---

## 8. 边界与翻篇

- 权重机制/`nacos.weight` 坑/预热/hint 深挖 → **06**；
- 缓存 35s、摘除、优雅停机 → **07**；
- 扩展点全集（自定义 Supplier/Algorithm/Lifecycle）+ 一致性哈希/最少连接怎么写 → **09**；
- 装饰器链 14 个类的逐个实现（源码级）→ `loadbalancer-lab` D2。

---

## 9. Self-Test

1. supplier 链和算法各自的职责是什么？（"可挑集合" vs "挑一个"）
2. 默认链长什么样？配了 weighted / health-check 后各"多/少"了什么层？
3. 为什么 hint 没有预设？写了 `configurations=hints` 会怎样？
4. health-check 的两个经典坑分别是什么？怎么绕？

---

## 10. 相关与衔接

- **06 权重与灰度**：`metadata.weight` 深挖、WeightFunction、预热、hint；
- **07 名单会变**：`[Cache 35s]` 和 `[HealthCheck]` 两层的生产含义（含 L46 实测）；
- **09 自定义 LB**：链与算法的扩展点全集；
- [`loadbalancer-lab`](../../loadbalancer-lab/HANDOVER.md)：[A-类官方功能](../../loadbalancer-lab/docs/A-类官方功能.md)（第二批验证链 + 踩坑）/ [D2 装饰器链](../../loadbalancer-lab/docs/D2-ServiceInstanceListSupplier-装饰器链体系.md)；
- 网关课程：[L16](../gateway/L16-custom-loadbalancer.md)（自定义链）/ [L46](../gateway/L46-sclb.md)（缓存 35s 与摘除）。
