# 负载均衡（SCLB）专题 · 10 篇 + 04b 扩展

> 状态：✅ **全系列 10 篇完成**（2026-10-08）+ **04b 扩展篇**（每服务子上下文与按服务配置）
> 定位：**客户端负载均衡**（Spring Cloud LoadBalancer 4.1.4）的 **原理 + 使用 + 生产** 课程（规格对齐 `docs/redis/` 的 10 篇）
> 口径：Spring Cloud 2023.0.3 / `spring-cloud-loadbalancer` **4.1.4** / SCA 2023.0.1.2（NacosLoadBalancer）/ Nacos 2.2.3；对齐 xhs-project
> 素材：`loadbalancer-lab`（A/D 实验记录与源码笔记——**做引用与证据，不重复写**）· 网关课程 L16 / L29 / L46 · 训练营 stage-1 第 11-12 章 · xhs-project 生产代码
> 实验：优先复用已有实测（L16 / L46 / lab 记录）；需要新实测时用 demo 栈（demo-gateway + demo-provider），**不搭 lab 环境**

---

## 一、系列总表（10 篇 + 04b 扩展）

| # | 文章 | 类型 | 核心内容 | 素材 / 实测证据 |
|---|---|---|---|---|
| 01 | [负载均衡全景：客户端 vs 服务端](01-client-vs-server.md) | 理论 | 两种形态、南北/东西向、服务端三形态（DNS/L4/L7）、"名单+挑一个"、生态对照（Nginx/Dubbo/gRPC） | 讲稿；已讲概念 |
| 02 | [名单从哪来：服务发现与实例元数据](02-discovery-and-metadata.md) | 理论 | Nacos 注册/心跳/推送/本地缓存；实例 metadata（zone/version/weight/hint/API_VERSION）；**保护阈值** | nacos-lab 文档（A-04 等） |
| 03 | [挑法全集：算法与选型](03-algorithms-and-selection.md) | 理论 | 轮询/随机/权重/zone/最少连接/**一致性哈希**（环+虚拟节点）/热 key | L46 §3；L16 分布实测 |
| 04 | [SCLB 总览与接入](04-sclb-architecture.md) | 使用 | 模块定位；五种介入（`lb://` / `@LoadBalanced` / Feign / RestClient / WebClient）；每服务子上下文；配置优先级；**阻塞/响应式双路径**（BlockingLoadBalancerClient + 拦截器家族） | lab D1/D3/D5/D7；SCC-5 |
| 04b | [每服务子上下文与按服务配置](04b-per-service-context.md) | 使用（扩展） | NamedContextFactory 机制；装配三方式与优先级；隔离污染隐患；**按服务配 LB 策略**（谁挑谁配置/网关也是调用方） | 04 §5 深挖 + 源码 |
| 05 | [名单管线：装饰器链与 7 预设](05-supplier-chain-and-presets.md) | 使用 | "滤镜"模型；Builder `withXxx`；7 预设逐个（链结构）；自定义 supplier（L16 gray/zone 回顾） | lab D2；A-04~A-11 实测链 |
| 06 | [权重与灰度](06-weight-and-canary.md) | 使用+坑 | `metadata.weight` vs `nacos.weight` 的坑；WeightFunction；启动预热；**指标驱动动态权重**；hint 灰度；NacosLoadBalancer（默认关） | lab D4/A-07；ALI-A4；xhs 预热实现 |
| 07 | [名单会变：摘除·健康检查·优雅停机](07-instance-churn.md) | 使用+实测 | **35s 缓存窗口**（Nacos 1.4s vs 网关 35s 实测）；health-check 两坑；优雅停机；重试只是兜底 | **L46 实测**；lab A-05/A-06 踩坑 |
| 08 | [重试·粘性·一致性哈希](08-retry-sticky-hash.md) | 使用 | 重试两口味（换台/原台）；sticky session；一致性哈希落地；有状态服务三件套 | lab A-09/A-11/A-12；L29 |
| 09 | [自定义 LB 与生产案例](09-custom-lb-and-production.md) | 生产 | 扩展点全集；xhs `LeastConnectionsLoadBalancer`（预热/活跃数/权重）；zone 配置；手写方向 | xhs-project 源码；L16 |
| 10 | [可观测·排障·选型收口](10-observability-and-selection.md) | 生产 | Lifecycle/Micrometer 指标；排障三视角；K8s 场景（Service vs SCLB）；决策树；面试收口 | lab D6/A-16/Z；踩坑案例 |

> 规模说明：可压缩到 8 篇（06 并 05、08 并 07）或展开到 12 篇；当前 10 篇与 `docs/redis/` 同级。

---

## 二、学习路径

```text
理论：01 → 02 → 03
使用：04 → 05 → 06 → 07 → 08
生产：09 → 10
```

- 只想"会用"：04~08；
- 想"讲得清"：01~03 补理论；
- 面试冲刺：10 收口 + `loadbalancer-lab/docs/Z-面试题速答.md`。

---

## 三、掌握分级（摘要）

- 🔴 **必掌握**：客户端/服务端 LB 定位；五种介入方式与配置优先级；Supplier 装饰器链与 7 预设；默认轮询与权重（含坑）；"名单会变"的三件套（缓存/健康检查/优雅停机）+ 重试；排障三视角；
- 🟡 **了解（用到回查）**：A-13 API 版本选择；A-14 X-Forwarded 转换；subset 细节；hint 参数细节；Ribbon 历史对应；
- ⚪ **翻篇（源码深挖）**：D2 逐类实现、D4 双队列/GCD 细节；被砍的 C 类手写（动态权重 WeightFunction / 一致性哈希）。

> 完整版见 `loadbalancer-lab/docs/00-学习路线与掌握清单.md`。

---

## 四、历史映射（翻篇用）

| 历史（训练营 11-12 章） | 现代对应 |
|---|---|
| Eureka（30s 轮询拉取 / 自我保护） | Nacos（推送 + 本地缓存）；保护阈值语义 |
| Ribbon `IRule`（Random/RoundRobin/**WeightedResponseTimeRule**） | SCLB `ReactorLoadBalancer`（RR/Random）+ **`WeightFunction` 动态权重** |
| Servo / JMX 九大 MXBean | Micrometer（指标采集 → 喂 WeightFunction / 可观测） |
| Ribbon `ServerListUpdater` 定时轮询 | Nacos 推送为主 + SCLB 缓存兜底（`CachingServiceInstanceListSupplier`，35s） |

---

## 五、边界

- **网关视角**已由 [`L46`](../gateway/L46-sclb.md) 覆盖（`lb://` 链路 / 策略选择 / 摘除实测）；本系列展开**框架全貌**；
- **不重复**：限流/熔断（L36/L37/L45）、Feign 用法（`openfeign-lab`）、Nacos 机制（`nacos-lab`）、zone 多活（L42 / `xhs-gateway-zone.md`）；
- **可跑可不跑**：素材里有实测记录；想亲眼跑 lab 再改 Nacos 指向（见 lab `00-学习路线` 第三节）。
