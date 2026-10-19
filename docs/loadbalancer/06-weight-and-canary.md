# LB-06 权重与灰度

> 用途：两个"按 metadata 分流"的能力——**权重**（谁多收流量）与**灰度**（谁能收到请求）；以及围绕它们的全部坑
> 对应系列：**负载均衡（SCLB）专题 · 06**（使用篇第三篇）
> 口径：SCLB 4.1.4 / SCA 2023.0.1.2 / Nacos 2.2.3
> 素材：`loadbalancer-lab`（[D4 权重展开](../../loadbalancer-lab/docs/D4-WeightedServiceInstanceListSupplier-权重展开机制.md) / A-07 实测）· [ALI-A4 知识规划](../tmp-question/training-camp/source-code/analysis/source-analysis/spring-cloud-alibaba/knowledge-planning/ali-a4-nacos-loadbalancer.md)（NacosLoadBalancer 权重）· [训练营 stage-1 第 11-12 章](../xmg-stage/stage-1-服务治理-完整梳理.md)（指标驱动动态权重）· xhs-project（预热实现）
> 前置：02（metadata）/ 03（算法）/ 05（链与预设）；后续：07（摘除）→ 08（重试·粘性）→ 09（自定义）

---

## 0. 例子先行：两个请求，两种"分流"

```text
场景一（权重）：4C×1 + 16C×1 混部，大机器该吃 3 倍流量
  给大机器实例注册时写 metadata { weight: "3" }
  → weighted 预设按权重展开列表（大机器出现 3 次）→ 轮询 → 流量 3:1

场景二（灰度）：v2 上线 2 台，想让"灰度流量"先去 v2
  请求头带 X-SC-LB-Hint: gray → hint 滤镜只留 metadata { hint: "gray" } 的实例
  → 没有带标签的请求照常走全部实例
```

一句话：**权重是"服务给自己标价"（实例 metadata），灰度是"请求给自己打标"（请求头）**——两者都通过 metadata 发生关联（03/05 的"滤镜"在这里具体化）。

---

## 1. 掌握卡（3 条）

1. **两个 weight 别搞混**：SCLB 官方 `weighted` 预设读的是 **`metadata.weight`**；Nacos 的权重（控制台 / `spring.cloud.nacos.discovery.weight`）经 SCA 落到 metadata 的 key 是 **`nacos.weight`**——**默认互不相通**；另有一条 SCA 路线 `NacosLoadBalancer`（**默认关**）读 `nacos.weight`。
2. **权重是可插拔的 `WeightFunction`**：默认函数读 `metadata.weight`（缺省 1）；换成自定义函数就能做**指标驱动动态权重**（RT/连接数/CPU → 权重）——Ribbon 时代的 `WeightedResponseTimeRule` 就是它的历史版本。
3. **灰度 = hint 滤镜 + 实例 metadata**：请求头 `X-SC-LB-Hint`（可配）→ 只留 metadata `hint` 匹配的实例；**无匹配回退全量**（灰度流可能落到非灰度实例——要严格灰度需自研 fail-close）；hint **没有预设**，只能 Builder。

---

## 2. 权重之路：从 metadata 到"谁多收"

### 2.1 机制一句话回顾

```text
WeightFunction → 按权重"展开"列表（大机器出现多次）→ 轮询 → 自然加权
（展开是惰性的：GCD 归约 + active/expired 双队列；发生在每次 get()、在缓存层之上）
```

默认权重函数：读实例 metadata 的 `weight`（字符串，需能解析成整数；**缺省 1**）——03/05 已讲，此处不重复。

自定义权重的唯一契约就一个方法（`WeightFunction.java:39`）：

```java
int apply(ServiceInstance instance);   // 返回该实例权重；官方默认实现读 metadata.weight
```

`withWeighted(fn)` 把你的函数装进"展开 + 轮询"逻辑——展开部分不用自己写。

### 2.2 三条"权重来源"路线（含坑）

| 路线 | 权重来源 | 怎么开 | 动态性 | 坑 |
|---|---|---|---|---|
| **① 官方 `weighted` 预设**（SCLB） | `metadata.weight` | `configurations=weighted` + provider 注册时写 metadata | 静态（重新注册才变） | **与控制台那个"权重"不是一个 key** |
| **② SCA `NacosLoadBalancer`** | `nacos.weight`（Nacos 控制台改的就是它 / `spring.cloud.nacos.discovery.weight`） | `spring.cloud.loadbalancer.nacos.enabled=true`（**默认关**） | **控制台动态推送** | 算法是**权重随机**（不是展开）；**别和路线①双开**（两套权重机制打架） |
| **③ 自定义 `WeightFunction`** | 任意（可读 `nacos.weight`、也可读指标） | `withWeighted(fn)` | 看实现 | 自己维护 |

> 选路线的一句话：**控制台不碰权重 → ①；要在控制台动态调 → ②；要"指标驱动"→ ③（或直接自研 LB，见 2.4）**。

### 2.3 启动预热：给"刚上线的实例"降权

**问题**：新实例刚启动——JIT 没热、本地缓存空——一上线就按均权接流量，容易被打爆（"冷启动雪崩"）。

**xhs 的做法**（`LeastConnectionsLoadBalancer`）：

```text
权重 = metadata.weight（缺省 10） × 预热系数
预热系数：实例 metadata.startupTime 起 60 秒内，从 1% 线性爬到 100%
```

⚠️ 诚实标注（02 已核）：**读取逻辑在，但注册侧未见写入 `startupTime`**——所以预热目前是"预留能力"，不填就不预热、退回纯权重。要启用需在实例注册时写入该 metadata。

> 注意：**SCLB 官方没有预热特性**（`weighted` 只按静态权重展开）——预热是"自定义 LB/自定义权重函数"的活。

### 2.4 指标驱动动态权重（理论高点：第 11-12 章的正传）

- **思想**（Ribbon 时代的 `WeightedResponseTimeRule`，已停维护）：用实例的**实时表现**算权重——RT 快的多分流量、慢的少分；**样本不足时回退轮询**；
- **现代落地**：用 Micrometer 采集每实例的 RT/连接数/CPU → 喂给自定义 `WeightFunction`（`int apply(ServiceInstance)`）→ 官方"展开 + 轮询"逻辑照用；
  - 展开在每次 `get()` 重算（位于缓存层外层），所以权重能随指标"动"起来；
- **另一种落地**：跳过"展开"，直接自研 LB 选"最闲的实例"（xhs 的最少连接就是这一派，09 展开）；
- 前提：**指标要能按实例区分**——客户端视角（每个调用方自己统计/采集）最常见。

---

## 3. 灰度之路：hint（"请求打标"选实例）

### 3.1 机制（05 §4 回顾 + 补全）

```text
流量染色（前端/网关给请求带 X-SC-LB-Hint: gray）
  → HintBased 滤镜：只留 metadata { hint: "gray" } 的实例
  → 没带标签的请求：照常全量
  → 带标签但没匹配实例：回退全量（设计行为；要严格灰度得自研）
```

- 头名默认 `X-SC-LB-Hint`（`spring.cloud.loadbalancer.hint-header-name` 可改）；
- **没有 `configurations=hints`**——写了会名单空（No instances available，05 踩坑合辑 #1）；要 Builder `withHints()`。

**hint 怎么从请求进 LB**（`HintBasedServiceInstanceListSupplier.java:57-74`，本节缺失的一环）：

```java
return delegate.get(request).map(instances -> filteredByHint(instances, getHint(request.getContext())));
// getHint 两路：
//   RequestDataContext → 从请求头取（头名默认 X-SC-LB-Hint）   // :66 网关/HTTP 场景
//   否则 → ((HintRequestContext) request.getContext()).getHint()  // :69 编程式场景
```

所以"请求打标"分两步：**① 请求侧把标签放进上下文**（HTTP 就带头，编程式就 `new HintRequestContext("gray")`）→ **② LB 侧 `filteredByHint` 只留对上标签的实例**。只做①不做 `withHints()`，标签没人消费（L16 §16.8 结论）。

### 3.2 全链路灰度的三层（与网关课程呼应）

```text
① 入口染色（网关）：按用户/设备等规则给请求打标签（xhs 网关 GrayRouteFilter 用 gray-tag 等）
② LB 选实例（hint）：标签 → 只发给打了对应 hint 的实例
③ 标签透传（header 传播）：下游服务还能看到灰度标（L48）
```

**关键边界（L16 §16.8 的结论）**：**"染色"和"选实例"是两件事**——染了色不等于会自动发给灰度实例，必须有人在 LB 层消费标签（hint / 自研 supplier）。

### 3.3 另一种灰度：按 version 过滤（自研 supplier）

- L16 的 `GrayInstanceListSupplier` 直接按 metadata `version=v2` 过滤（demo 的 `demo.lb.mode=gray`）；
- **官方没有 version 预设**——要么自研 supplier（L16 姿势），要么把它翻译成 hint（实例标 `hint: gray` + 请求带头）。

---

## 4. 坑清单（本节是重点）

| # | 坑 | 后果/对策 |
|---|---|---|
| 1 | `metadata.weight` vs `nacos.weight` | 控制台改权重，`weighted` 预设"没反应"——两个 key；选路线（2.2） |
| 2 | 没配 `metadata.weight`（或值 ≤ 0） | 回落到默认权重 1 → 均匀——**正常行为**，别误判"权重失效"；也**不能靠 `weight=0` 摘实例**（源码：≤0 一律换默认值，仅打 debug 日志） |
| 3 | `configurations=hints` | 名单空、No instances available——用 Builder |
| 4 | NacosLoadBalancer 与 weighted 预设双开 | 两套权重打架——二选一 |
| 5 | 预热依赖 `startupTime` | 不写 metadata → 不预热（当前 xhs 即如此） |
| 6 | hint 回退全量 | 灰度流量可能落到非灰度实例；严格灰度要自研 fail-close |

---

## 5. 生产对照

- **xhs**：权重（`metadata.weight` × 预热）→ zone 收敛 → 最少连接挑（09）；灰度在网关侧按标签（`xhs-gateway-zone.md` 的 gray-tag / zone）；
- **demo-gateway**：`demo.lb.mode=gray` 是 version 过滤的自研版（L16）；`weighted` 预设的实验链见 lab A-07。

---

## 6. 边界与翻篇

- 权重展开的源码细节（GCD/双队列）→ `loadbalancer-lab` D4；NacosLoadBalancer 的机制 → ALI-A4 知识规划；
- **指标驱动动态权重的手写实验**（自定义 `WeightFunction`）属于被砍的 C 类——想做从 sca-lab git 恢复；
- 摘除/健康检查 → 07；重试两口味 → 08；自研 LB → 09。

---

## 7. Self-Test

1. `metadata.weight` 和 `nacos.weight` 分别被谁消费？想"控制台动态改权重"走哪条路？
2. 权重为什么是"可插拔"的？指标驱动动态权重怎么接上去（一句话链路）？
3. 启动预热解决什么问题？xhs 的实现依赖什么 metadata？当前生效吗？
4. hint 灰度的匹配与回退规则是什么？它和"染色"的区别（L16 的结论）？

---

## 8. 相关与衔接

- **07 名单会变**：`[Cache 35s]`、健康检查、优雅停机（本课的权重/灰度解决"分发多少"，07 解决"名单变了怎么办"）；
- **08 重试·粘性**：`retry.avoid-previous-instance` / same-instance / sticky；
- **09 自定义 LB**：最少连接（含预热的真实实现）与一致性哈希；
- [`loadbalancer-lab`](../../loadbalancer-lab/HANDOVER.md)：[D4 权重展开](../../loadbalancer-lab/docs/D4-WeightedServiceInstanceListSupplier-权重展开机制.md) / A-07 实测 / [Z 面试题](../../loadbalancer-lab/docs/Z-面试题速答.md)；
- 网关课程：[L16](../gateway/L16-custom-loadbalancer.md)（染色≠选实例）/ [L46](../gateway/L46-sclb.md)。
