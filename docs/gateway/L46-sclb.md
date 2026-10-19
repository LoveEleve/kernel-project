# L46 Gateway × SCLB：负载均衡深入（策略与实例摘除）

> 用途：回答 `lb://` 背后的负载均衡怎么选实例、实例下线后网关多久感知
> 对应课程：**L46**（Stage 9 生态打通 · 第三课）
> 口径：SCG **4.1.5** / Spring Cloud 2023.0.3（SCLB **4.1.4**）/ SCA 2023.0.1.2 / Nacos 2.2.3；实测 2026-10-07
> 实验环境：demo-gateway:8080（`--demo.lb.mode=none`）· demo-provider:8081(v1) + :8082(v2) · 本机 Nacos
> 前置：L16（自定义 LB：supplier 洋葱）· L43（服务 metadata）/ 相关：L42（zone 多活）、L44（Nacos 宕机缓存）

---

## 0. 例子先行：杀一个实例，两端感知差 25 倍

现场三段实验（本机真实输出）：

```text
① 默认轮询（mode=none，6 连发 /lb/hello）：
   req1→8081  req2→8082  req3→8081  req4→8082  req5→8081  req6→8082   ← 严格交替

② kill v2 后：
   Nacos 实例列表（1.4 秒内）→ 只剩 8081                        ← 注册中心：秒级
   网关（缓存热着）的探测：
       t+2s~t+31s：交替出现 500（打到死掉的 8082，Connection refused）
       t+36s 起：全部 200 port=8081                            ← 网关：约 35 秒才"真的不看 8082"
③ 若缓存已冷（上次写入超过 35 秒后再 kill）：
   下一个请求直接就是 8081（立即摘除）——与 ② 对照，差异全在那 35 秒缓存
```

一句话：**注册中心 1.4 秒就摘了，网关最坏要等 SCLB 缓存 TTL（默认 35s）过期才不再选它**——这 35 秒里的请求打到死实例，客户端看到 500。

---

## 1. 掌握卡（3 条）

1. **`lb://` 链路**：`ReactiveLoadBalancerClientFilter`（order=10150）把 URI scheme `lb` 交给 **SCLB**：`LoadBalancerClientFactory`（每服务一个子上下文）→ `ServiceInstanceListSupplier`（实例列表，洋葱链）→ `ReactorLoadBalancer`（选一个）→ 用实例 host:port 改写 URL。
2. **策略选择**：SCLB 默认 **RoundRobin**；可选 zone-preference / health-check / 自定义；Nacos 栈还有 SCA 的 `NacosLoadBalancer`（权重随机 + 同集群优先，**默认关**）；xhs 生产用自研"最少连接"。
3. **实例摘除**：三个来源——① Nacos 推送（~1.4s）② SCLB 缓存 TTL（35s，最坏窗口）③ 可选主动健康检查（轮询 `/actuator/health`）。期间的失败表现：500 `Connection refused`。

---

## 2. `lb://` 链路：filter → factory → 洋葱 → LB

```text
ReactiveLoadBalancerClientFilter (GlobalFilter, order=10150)
  │  if scheme == "lb":
  ├─→ LoadBalancerClientFactory.getInstance(serviceId)      ← 每服务一个子上下文
  │       ├─ ServiceInstanceListSupplier（实例列表来源，洋葱）
  │       │    discovery → [gray/zone 自定义] → caching（Caffeine 35s）
  │       └─ ReactorLoadBalancer（选一个：RoundRobin / Nacos / 自研）
  ├─← Response<ServiceInstance>
  └─ 改写 URL = instance.host:port（再走后续 filter 转发）
```

关键类（源码位置）：

| 类 | 干什么 |
|---|---|
| `ReactiveLoadBalancerClientFilter`（SCG） | order **10150**（`ReactiveLoadBalancerClientFilter.java:69`）；只认 `lb://`，`choose()` 调 LB（`:174`） |
| `LoadBalancerClientFactory`（SCLB） | `NamedContextFactory`：每个服务一个**子上下文**（L16 讲过供应链装在哪） |
| `ServiceInstanceListSupplier` | 实例列表；`withDiscoveryClient().withCaching()...` 是"洋葱"（L16 §5.1） |
| `ReactorLoadBalancer` | 从列表里"选一个"；默认实现 `RoundRobinLoadBalancer` |

---

## 3. 策略怎么选

SCLB 4.x 的策略来自三层：**① 配置预设**（`spring.cloud.loadbalancer.configurations=...`，源码 `LoadBalancerClientConfiguration`）→ **② 自定义算法/supplier** → **③ Nacos 生态**（SCA 的 NacosLoadBalancer）。

**① 预设全集**（都是官方内置，改一行配置就换）——**关键认知：这 7 个换的都是"实例列表的滤镜链"（`ServiceInstanceListSupplier`），不是"选一个"的算法；选一个仍是 RoundRobin**：

| 预设值 | 效果（加在哪层滤镜） | 关键旋钮 | 适用 |
|---|---|---|---|
| `default` | 发现 + 缓存 | — | 无状态、实例同构 |
| `weighted` | 权重展开（权 3 的实例出现 3 次 → 轮询自然加权） | 权重读实例 `metadata.weight`（缺省 1），可换 `WeightFunction` | 大小机器混部 |
| `zone-preference` | 同 zone 优先；**没有同 zone 实例时回退全部**（防雪崩） | 本机 zone=`spring.cloud.loadbalancer.zone`；实例 zone=metadata `zone` | 多机房就近 |
| `health-check` | 主动轮询实例健康，剔除坏实例 | 默认探 `/actuator/health`、间隔 25s（`health-check.path/interval`） | 防"僵而不死" |
| `request-based-sticky-session` | 按 cookie 粘同一实例 | cookie 名 `sc-lb-instance-id` | 有会话状态 |
| `same-instance-preference` | 优先"上次返回的实例"（重试时粘住） | 默认重试语义相反（`retry.avoid-previous-instance`） | 缓存亲和 |
| `subset` | 确定性把实例切成子集，每个调用方只连一个子集 | `subset.instance-id`（默认本应用 id）+ `subset.size`（默认 100） | 超大规模集群 |

> 想**叠加**（weighted + health-check + zone）不能只写一个预设值——用 L16 的 Builder 手工搭链：`builder().withDiscoveryClient().withHealthChecks().withWeighted().withCaching()`；
> 按服务单独设：`spring.cloud.loadbalancer.clients.<serviceId>.configurations=...`（client 值优先于全局默认）。

**预设之外的三种来源** + 一个生产案例（网关生产世界里"预设管不了"的场景）：

| 来源 | 怎么启用 | 说明 |
|---|---|---|
| **① 自定义 LB / supplier** | 注册 `ReactorLoadBalancer` bean，或 L16 的 supplier 洋葱 | 灰度、最少连接等业务规则（**L16 已讲透**） |
| **② SCA `NacosLoadBalancer`** | `spring.cloud.loadbalancer.nacos.enabled=true`（**默认关**） | Nacos 权重随机 + 同集群优先；权重在 Nacos 控制台配 |
| **③ 一致性哈希（自研）** | **SCLB 无内置**，需自研 `ReactorLoadBalancer`（参考 Dubbo `ConsistentHashLoadBalance`） | 按 key（用户/缓存键）亲和到固定实例——**别和"请求粘性"混淆**（粘性是记住上次实例，哈希是按 key 算） |
| （生产案例）**xhs** | 自研 `LeastConnectionsLoadBalancer` + zone 配置 + 优雅停机 | 就是①的一种实例：按"连接数最少"挑，避免慢请求堆在一台 |

### 3.1 权重 LB 的核心就三步（不用抠源码）

```text
权重函数（WeightFunction）→ 按权重"展开"实例列表（LazyWeighted：GCD 归约 + active/expired 双队列惰性展开）
→ 轮询选择器自然变成"加权轮询"
```

- 默认权重函数：读实例 **metadata 的 `weight` 字段**（缺省 1）——Nacos 控制台改权重，就是改这个字段；
- 换权重来源：`withWeighted(自定义 WeightFunction)`——权重逻辑可插拔，这是关键设计（源码 `WeightedServiceInstanceListSupplier`）。

> 深挖（GCD 归约 / 双队列展开 / `withXxx` 全集）→ **`loadbalancer-lab`**（另一条线）：`docs/D4-WeightedServiceInstanceListSupplier-权重展开机制.md`、`docs/D2-ServiceInstanceListSupplier-装饰器链体系.md`。

### 3.2 动态权重：指标驱动的"哪台快就多发给谁"（训练营第 11-12 章的核心）

- **思想**（Ribbon 时代叫 `WeightedResponseTimeRule`，已停维护）：用实例的**实时响应时间**算权重——快的多分流量、慢的少分；样本不足时回退轮询；
- **现代落地**：Micrometer 采集 RT/连接数/CPU → 喂给自定义 `WeightFunction` → 官方展开+轮询逻辑照用（`micrometer-lab` 已有指标设施可接）；
- **xhs 生产**是同一思想的另一种实现：跳过"展开"，直接选**连接数最少**的实例（`LeastConnectionsLoadBalancer`）。

> 训练营里的 Eureka/Ribbon/Servo/JMX 九大 MXBean 是这套东西的**历史实现**，不用学——记住上面三行的"现代对应"即可。

**实测（本课环境）**：

```text
mode=none → 严格交替（RoundRobin）；
网关日志 "cross-cluster" 警告数 = 0
  → 说明生效的不是 SCA 的 NacosLoadBalancer（它每次都会打这条警告）
  → 因为 `spring.cloud.loadbalancer.nacos.enabled` 没配（2023.0.1.2 的条件注解**没有 matchIfMissing**，
     `ConditionalOnLoadBalancerNacos.java:28`——默认关，要显式开）
```

> 想用 Nacos 权重/同集群：加 `spring.cloud.loadbalancer.nacos.enabled=true`；它的默认算法是 `NacosBalancer.getHostByRandomWeight3`（**权重随机**，`DefaultLoadBalancerAlgorithm.java`），不是轮询。

---

## 4. 实例摘除：三个来源 + 实测时间线

```text
实例死掉
 ├─ ① Nacos 推送（优雅停机主动注销 / 心跳超时剔除）→ 注册中心列表更新   实测 ≈ 1.4s
 ├─ ② SCLB 缓存 TTL（CachingServiceInstanceListSupplier，默认 35s）  实测 ≤35s 窗口
 └─ ③ 主动健康检查（HealthCheckServiceInstanceListSupplier，可选装） 未实测
```

**② 的机制（源码）**：`CachingServiceInstanceListSupplier` 用 `CacheFlux` 做"读缓存，miss 才拉取并写回"；缓存 TTL = `LoadBalancerCacheProperties.ttl` 默认 **35 秒**（`LoadBalancerCacheProperties.java:42`）。所以：

- **缓存热着**（35s 内刚写回）：死实例还会被选中 → 请求 500（`AnnotatedConnectException: Connection refused: 192.168.13.128:8082`，网关日志实测）；
- **缓存冷了**：下一个请求 miss → 拉新列表 → 立即只剩活实例。

**失败长什么样**（实测，SCG 把连接拒绝变成 500）：

```text
t+2s http=500  ← 打到 8082（已死）
AnnotatedConnectException: finishConnect(..) failed: Connection refused: /192.168.13.128:8082
500 Server Error for HTTP GET "/lb/hello"
```

**生产对策**（任一/组合）：

1. **优雅停机**：先摘流再关（xhs 的 `GracefulShutdownListener`——实例下线前给出排空期）；
2. 调 `spring.cloud.loadbalancer.cache.ttl`（trade-off：调小→注册中心抖动更敏感）；
3. 装 `health-check` supplier（主动探测）；
4. 路由配 `Retry`（L37）兜瞬时失败。

---

## 5. 坑与边界

| # | 坑 | 说明 |
|---|---|---|
| 1 | **摘除感知最坏 = 缓存 TTL（35s）** | Nacos 秒级≠网关秒级；窗口内请求打空 → 500。生产必须有优雅停机/健康检查/重试兜底 |
| 2 | **SCA NacosLoadBalancer 默认关** | 2023.0.1.2 条件无 `matchIfMissing`；不显式开 = SCLB RoundRobin（权重/同集群不生效） |
| 3 | **`demo.lb.mode=gray/zone` 会覆盖默认** | 演示包的开关（L16）；做默认轮询实验要 `none` |
| 4 | **provider 侧 Sentinel 规则干扰实验** | demo-provider 有 L45 的 `/echo/{msg}` QPS=1 → `lb` 实验打 `/lb/echo/hi` 会先被服务层 429；LB 实验用 `/lb/hello` |
| 5 | health-check preset 未实测 | 源码可见（`withDiscoveryClient().withHealthChecks()`），本课没装 |

---

## 6. Self-Test

1. `lb://` 从 filter 到选出实例，经过哪几个组件？哪一步决定"用哪个算法"？
2. 默认算法是什么？想用 Nacos 权重/同集群，配什么？它的算法本质是什么？
3. kill 一个实例后：Nacos 多久摘？网关多久不再选它？为什么？
4. 那 35 秒窗口里，打到死实例的请求表现是什么？生产怎么兜？

---

## 7. 相关与衔接

- **L16**（自定义 LB）：supplier 洋葱 + `@LoadBalancerClient` 装配——本文第 2 节是它的"上游全景"
- **loadbalancer-lab**（另一条线）：完整 SCLB 深挖（A-01~A-18 官方功能 + D1~D7 源码 + 可跑端点，端口 28010）——本文只取**网关视角**，机制细节以那边为准；C 类手写（动态权重 WeightFunction / 一致性哈希）按此前决定已砍，可从 sca-lab git 恢复
- **L42/L43**（zone/元数据）：zone-preference 的 xhs 自研实现（`xhs-gateway-zone.md`）
- **L44**（Nacos 宕机）：缓存 35s 的另一面——Nacos 全挂时靠它顶住
- **L37**（Retry）：窗口期失败的兜底手段之一
- **xhs 生产**：`LeastConnectionsLoadBalancer`（最少连接）+ `ZoneLoadBalancerConfiguration` + `GracefulShutdownListener`
