# LB-10 可观测·排障·选型收口

> 用途：收口篇——怎么"看见"LB 在干什么、出问题怎么查、什么时候用什么（决策树）、面试怎么答
> 对应系列：**负载均衡（SCLB）专题 · 10**（生产篇第二篇 / 终篇）
> 口径：SCLB 4.1.4 / SCG 4.1.5 / Nacos 2.2.3
> 素材：`loadbalancer-lab`（[D6 生命周期](../../loadbalancer-lab/docs/D6-LoadBalancerLifecycle-生命周期.md) / A-16 指标 / [Z 面试题](../../loadbalancer-lab/docs/Z-面试题速答.md)）· 网关课程 [L46](../gateway/L46-sclb.md)（排障实证）· 本系列 01-09 的坑合辑
> 前置：01-09（本篇不再讲新机制，只做"看/查/选"的收口）

---

## 0. 例子先行：一个"流量不均"怎么查？

```text
现象：5 台实例，1 台 QPS 明显偏高，另外有 1 台几乎收不到流量

排查顺序（三视角，见 §3）：
① 看分布：先确认"不均"是真是假（打 10 次看落点 / 指标）
   → 真不均 → ② 看链：滤镜是不是把它筛掉了？（zone / hint / subset / 权重展开）
   → 链正常 → ③ 看名单：注册中心 vs LB 手里是否一致？（缓存窗口 / 健康检查剔除 / 保护阈值）
```

90% 的"LB 问题"都能在这三步里定位；而**看不见分布和链**，就变成"盲猜配置"。

---

## 1. 掌握卡（3 条）

1. **可观测三个观察面**：**指标**（Lifecycle → Micrometer：`loadbalancer.requests.success/failed`）/ **分布**（谁被选中——打点/日志）/ **名单**（注册中心 vs LB 手里）。
2. **排障三视角**：**看分布 → 看链 → 看名单**（顺序重要：先把"现象"坐实，再查"滤镜"，最后查"数据源"）。
3. **选型决策树**（一句话版）：默认轮询 → 异构上权重 → 多机房上 zone → 慢请求上最少连接（自研）→ 亲和上哈希/粘性；**"集群精确配额"不是 LB 的事**（那是限流，走 Redis/L36）。

---

## 2. 可观测：LB 的"黑匣子"打开方式

### 2.1 Lifecycle + 官方 Micrometer 指标

`LoadBalancerLifecycle` 三个回调（+ `supports` 过滤）：

```text
onStart（开始）→ onStartRequest（选完实例）→ onComplete（完成，成功/失败都到）
```

官方实现 `MicrometerStatsLoadBalancerLifecycle` 把结果记成指标：

- **`loadbalancer.requests.success`** / **`loadbalancer.requests.failed`**（Timer，带服务/实例/异常等 tags）；
- **开启方式**（默认关）：

  ```yaml
  spring.cloud.loadbalancer.stats.micrometer.enabled: true   # LoadBalancerStatsAutoConfiguration 条件（lab A-16 验证）
  ```

- **用途**：回答"实例级失败率/延迟"——"是不是某台一直在失败"、"流量是不是全砸一台"。

### 2.2 实例分布（谁被选中）

- lab 姿势：`GET /lb/distribution?calls=10`（打 10 次看落点分布）；
- 生产姿势：LB 日志打实例 IP / 指标按实例 tag 聚合（`loadbalancer.requests.*` 的 tags 就够）；
- 实证锚点（L46）：网关日志里的 `Connection refused: <实例IP>` 本身就是"分布证据"——它证明这次落点是谁。

### 2.3 名单（注册中心 vs LB 手里）

- 注册中心侧：Nacos 控制台/API 看实例列表与健康态；
- **LB 侧：本地缓存（35s）可能与之不一致**——"名单一看全好，请求照样打空"的根因（07）。

---

## 3. 排障三视角 + 案例映射（把前 9 课坑全归位）

| 视角 | 看什么 | 工具/手段 | 对应坑（本系列） |
|---|---|---|---|
| ① 看分布 | 各实例收到的流量 | `/lb/distribution` / 日志打 IP / 指标 tags | 权重没配→均匀（06）；粘性过载（08）；热 key（03） |
| ② 看链 | supplier 链结构 | `/lb/supplier-chain`（lab） | hints 非法→空名单（05）；health-check 死循环全过滤（05） |
| ③ 看名单 | 注册中心 vs LB 手里 | Nacos 控制台 + 缓存 TTL 知识 | 35s 窗口（07）；保护阈值放开（02） |

**症状 → 视角速查**：

| 症状 | 先看 | 大概率原因 |
|---|---|---|
| 请求打空 500 | ③ 名单 | 缓存窗口内死实例（07） |
| "No instances available" | ② 链 | hints 空名单 / health-check 全过滤（05） |
| 某实例完全收不到流量 | ② 链 + ③ 名单 | zone/hint/subset 过滤；健康检查剔除 |
| 流量恒定打一台 | ① 分布 | sticky；权重集中；热 key |
| 配置改了没效果 | —（先查装配） | 三层优先级 / 子上下文 / serviceId 拼写（04） |

---

## 4. K8s 场景：Service 与 SCLB 的分工

| | K8s Service | SCLB（客户端 LB） |
|---|---|---|
| 形态 | **服务端 LB**（kube-proxy/iptables/IPVS） | 调用方进程内 |
| 调用方式 | `http://my-svc.namespace`（ClusterIP） | `lb://serviceId`（需注册中心或 K8s DiscoveryClient） |
| 给什么 | 语言无关、集群内 DNS 寻址 | 请求级策略（zone/权重/灰度/最少连接/重试） |
| xhs | Ingress/Service 管外部入口 | **内部调用走 Nacos 注册 + SCLB**（Pod 里跑） |

**铁律**：两个 LB 可以叠加（Service 做寻址、SCLB 做策略），但**别对同一个"Service 名"再做一次 SCLB**——`Service → Service` 的双重 LB 只会让落点和语义都不可控。

---

## 5. 选型决策树（收口）

```text
需要"key 亲和"吗？（有状态/本地缓存要命中）
├─ 要 → 一致性哈希（自研，08）或请求粘性（cookie，08）
└─ 否 → 常规均衡，继续问：
    ├─ 实例规格不均？        → 权重（metadata.weight / NacosLoadBalancer，06）
    ├─ 多机房？              → zone-preference（05/06）
    ├─ 慢请求/长连接多？      → 最少连接（自研，09）
    └─ 都还好                → 轮询（默认）
补充问题：
· 配置要动态调？            → 属性走配置中心刷新；控制台权重则用 NacosLoadBalancer（06）
· 要"集群精确配额"？         → 这不是 LB，是【限流】——Redis 令牌桶（L36）或 Sentinel
```

> 预设覆盖 ~80% 场景；剩下的 20% 用**扩展点**（09 的 xhs 三件套：最少连接 + zone + 优雅停机）。

---

## 6. 面试收口（lab Z 六组 ↔ 本系列）

| 面试主题 | 一句话答案 | 本系列 |
|---|---|---|
| 客户端 vs 服务端 LB | "挑"在调用方进程 vs 中间人；判别看出错时谁报后端 IP | 01 |
| 名单/注册中心 | 订阅推送 + 本地缓存；**名单会变**（15s/30s/保护阈值/35s 缓存） | 02 / 07 |
| 算法与选型 | 分流量（轮询/随机/权重/zone/最少连接）vs 定映射（哈希/粘性） | 03 |
| 接入与装配 | 五种介入、两条路径（阻塞 `Mono.block()` / 响应式）、每服务子上下文、三层配置优先级 | 04 |
| 链与预设 | supplier 装饰器链 = "滤镜"；7 预设；hint 无预设 | 05 |
| 权重/灰度 | `metadata.weight` vs `nacos.weight`；WeightFunction；hint 回退 | 06 |
| 重试/粘性/哈希 | 两口味（换台/原台）；重试需 spring-retry；哈希 ≠ 粘性 | 08 |
| 自定义/生产 | 四扩展点；xhs 最少连接（预热/活跃数/三项限制） | 09 |

> 题库（37 题 + 15 坑）在 `loadbalancer-lab/docs/Z-面试题速答.md`——本表是"从本系列回去查"的索引。

---

## 7. 边界与后续深化

- 指标接入 Prometheus/Grafana、告警 → **可观测线**（`micrometer-lab`）；本篇只到"指标产生与含义"；
- K8s 原生服务发现（KubernetesDiscoveryClient）→ 需要时单独学；本课只讲与 SCLB 的分工；
- 手写 C 类（动态权重 `WeightFunction` / 一致性哈希）→ sca-lab git 恢复（09 §6）；
- **本系列到此收口**。

---

## 8. Self-Test（综合）

1. "流量不均"的三视角排查顺序？各视角的代表症状？
2. K8s Service 与 SCLB 的分工与"铁律"是什么？
3. 给场景选型："3 机房 + 机器异构 + 有几个慢接口"——怎么组合？
4. LB 的失败指标叫什么、怎么开？它回答什么问题？

---

## 9. 系列收尾（10 篇地图）

```text
理论：01 全景（客户端 vs 服务端）→ 02 名单从哪来 → 03 挑法全集
使用：04 SCLB 接入 → 05 链与预设 → 06 权重与灰度 → 07 名单会变 → 08 重试·粘性·哈希
生产：09 自定义与生产案例 → 10 可观测·排障·选型（本篇）
```

- 回顾按钮：每篇的 **§1 掌握卡 + §9 Self-Test** 是复习入口；
- 深挖去处：`loadbalancer-lab`（A-01~A-18 / D1~D7 / Z）；
- 相关线：网关课程（L16/L29/L37/L46）、`openfeign-lab`（阻塞侧）、`nacos-lab`（名单侧）、`docs/redis`（一致性哈希的槽位变体）。
