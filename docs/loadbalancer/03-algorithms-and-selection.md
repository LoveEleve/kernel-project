# LB-03 挑法全集：算法原理与选型

> 用途：把"挑一个"的算法家族一次讲全——每种算法解决什么问题、SCLB 开不开箱、什么时候选它
> 对应系列：**负载均衡（SCLB）专题 · 03**（理论篇第三篇）
> 口径：SCLB 4.1.4 / SCA 2023.0.1.2；对齐 xhs-project
> 素材：[`loadbalancer-lab`](../../loadbalancer-lab/HANDOVER.md)（[A-类官方功能](../../loadbalancer-lab/docs/A-类官方功能.md)：A-01/A-02/A-07 实测 · D1 接口 · D4 权重展开）· 网关课程 L16/L46（实测）· xhs-project（最少连接）
> 前置：01（形态）/ 02（名单）；后续：05（装饰器链与预设）→ 06（权重与灰度深挖）→ 08（粘性/重试）→ 09（自定义）

---

## 0. 例子先行：同一份名单，不同挑法给出不同结果

名单：`[A(强), B(中), C(弱)]`（或 `[8081, 8082]`），连发 6 次请求：

| 挑法 | 6 次落点（示意） | 它在解决什么 |
|---|---|---|
| **轮询** | A B C A B C | 平均分配（平等对待） |
| **随机** | A C A B A B（无规律） | 打散（不依赖顺序） |
| **权重**（A:B:C=3:2:1） | A A A B B C … | 按能力分配 |
| **同 zone 优先** | 只发同机房的；同机房没了→回退全量 | 降低跨机房延迟 |
| **最少连接** | 谁手上活少发谁 | 防"慢请求堆积" |
| **一致性哈希** | 同一个 key 永远同一台 | 缓存命中/有状态亲和 |

**要点：算法没有"最好"，只有"适配什么场景"**——本课就是把每种讲清 + 给一张选型表。

---

## 1. 掌握卡（3 条）

1. **算法分两大类**：**"分流量"型**（轮询/随机/权重/zone/最少连接——目标是均衡）vs **"定映射"型**（一致性哈希/粘性——目标是亲和）。先答"我要均衡还是要亲和"，再选算法。
2. **SCLB 开箱盘点**：轮询（默认）/随机（有类无预设）/权重·zone·subset·粘性·健康检查（内置 supplier）；**最少连接与一致性哈希都没有内置，要自研**（xhs 的最少连接就是自研产物）。
3. **均衡算法解决不了"单 key 过热"**：热 key 是数据分布问题，不是挑法问题（一致的哈希反而会加剧）——见 §6。

---

## 2. 分流量型：五个均衡算法

### 2.1 轮询（RoundRobin）——默认，也是最常用的

- **原理**：一个原子计数器，对着名单依次轮转；无状态、零配置。
- **SCLB**：`RoundRobinLoadBalancer` 是**默认算法**（`LoadBalancerClientConfiguration` 的默认 Bean）——什么都不配就是它。
- **适用**：实例同构、无状态。
- **短板**：机器性能不均时会"**平均地压坏弱机**"；慢请求堆积场景无感知。
- **实测**：L46——`8081→8082→8081→8082…` 严格交替。

### 2.2 随机（Random）

- **原理**：`ThreadLocalRandom` 抽一台；短期有波动、长期趋近均匀。
- **SCLB**：`RandomLoadBalancer` 是**内置类，但没有配置预设**——要自己注册 bean 替换默认（lab A-02 就是这么跑的）。
- **适用**：实例同构；客户端数量多时天然打散（避免"所有调用方同节奏"）。
- **短板**：调用方少/样本小时分布可能明显不均。

### 2.3 权重（Weighted）

- **原理**：按能力分配份额（A:B:C = 3:2:1）。两种实现流派：
  - **展开式**（SCLB）：按权重把实例"重复"进列表再轮询（A 出现 3 次）——`WeightedServiceInstanceListSupplier`（LazyWeighted **惰性展开**：GCD 归约 + active/expired 双队列），默认读 **metadata `weight`**（05/06 详解）；
  - **概率式**（SCA `NacosLoadBalancer`）：按权重做**加权随机**（`NacosBalancer.getHostByRandomWeight3`）。
- **适用**：机器异构（4C vs 16C 混部）、发布期间按权重调流。
- **证据**：lab A-07 链实测 `[Weighted → Caching → DiscoveryClient]`。
- **坑**：SCLB 读 `metadata.weight`、Nacos 读 `nacos.weight`——**不是一个 key**（06 专题）。

### 2.4 同 zone 优先（Zone Preference）

- **原理**：先只保留"本机 zone"的实例；**同 zone 一个都没有 → 回退全部**（防雪崩，是特性不是 bug）。
- **SCLB**：`ZonePreferenceServiceInstanceListSupplier`（预设 `zone-preference`）；本机 zone 读 `spring.cloud.loadbalancer.zone`，实例 zone 读 **metadata**。
- **适用**：多机房/多可用区降延迟省带宽（L16 实测过分发；xhs 自研版见 [xhs-gateway-zone.md](../gateway/xhs-gateway-zone.md)）。

### 2.5 最少连接 / 最少活跃（Least Connections）

- **原理**：选"**手上未完成请求最少**"的实例——通常按 `活跃数 / 权重` 打分，取最小；并列随机。
- **SCLB**：**没有内置**。xhs 自研 `LeastConnectionsLoadBalancer`（活跃数靠 LoadBalancerLifecycle 钩子 +1/-1；另带启动预热）。
- **适用**：**请求耗时差异大**——轮询会把新请求继续发给已堆积慢请求的机器；最少连接自适应地把流量挪走。
- **代价**：要统计活跃数（实现门槛）；且是**每个调用方各算一份**（单机视角）。

---

## 3. 定映射型：两个亲和算法

### 3.1 一致性哈希（Consistent Hashing）

- **原理**：机器和 key 都放到一个 0~2³²-1 的**环**上，`hash(key)` 顺时针找到第一台机器；机器数变化时**只搬 1/N**（对比取模法"全盘重排"）；**虚拟节点**解决分布倾斜。
- **环怎么工作**：

  ```text
              hash 环（0 ~ 2^32-1）
                  节点A
                ↗       ↘
         节点D             节点B
                ↖       ↙
                  节点C
  规则：hash(key) 顺时针找到的第一台机器
  加一台：只影响它在环上"接管"的那一段 key（≈1/N 迁移，而不是全盘重排）
  虚拟节点：每台在环上放多个虚拟位置 → 分布均匀、迁移也均匀
  ```

- **场景**：按 key 亲和——同一用户的请求打到同一台（那台有本地缓存/状态），提升缓存命中。
- **现状**：**SCLB 没有内置**，要自研 `ReactorLoadBalancer`（参考 Dubbo `ConsistentHashLoadBalance`）；Nginx 有现成的（`hash $key consistent`）。
- **与"粘性"的区别**（高频混淆点）：

| | 一致性哈希 | 请求粘性（sticky） |
|---|---|---|
| 怎么记住目标 | **按 key 现算**（无需记忆） | 服务器**记住**上次给的实例（cookie） |
| 实例挂掉 | 该段 key **自动顺延**到下一台 | cookie 失效 → 重新分配 |
| 典型 key | userId / 缓存键 / 商品 id | 会话 id |

### 3.2 请求粘性（Sticky Session）

- **原理**：响应里种 cookie（默认 `sc-lb-instance-id`）→ 下次请求带着它 → 优先同一实例。
- **SCLB**：`RequestBasedStickySessionServiceInstanceListSupplier`（预设）；`same-instance-preference` 是重试场景的同类（08 详解；L29 实测）。
- **场景**：实例本地有会话状态。
- **注意**：实例挂 → 会话丢；**有状态服务的正解是集中式 session（Redis），粘性只是过渡**。

---

## 4. 开箱盘点（哪种算法在 SCLB 里怎么开）

| 算法/能力 | SCLB 开箱 | 形态 | 怎么开 |
|---|---|---|---|
| 轮询 | ✅ | 默认算法 Bean | 什么都不配 |
| 随机 | 🟡（有类无预设） | `RandomLoadBalancer` | 自注册 Bean（lab A-02） |
| 权重 | ✅ | supplier | `configurations=weighted`（06） |
| 同 zone 优先 | ✅ | supplier | `configurations=zone-preference` |
| 请求粘性 | ✅ | supplier | `configurations=request-based-sticky-session` |
| 同实例优先 | ✅ | supplier | `configurations=same-instance-preference` |
| 子集 | ✅ | supplier | `configurations=subset` |
| 健康检查 / 缓存 | ✅ | supplier（"滤镜"） | 预设 / 默认链（05/07） |
| **最少连接** | ❌ | 自研 | xhs `LeastConnectionsLoadBalancer`（09） |
| **一致性哈希** | ❌ | 自研 | 参考 Dubbo（08） |

> 注：权重/zone/subset/粘性等是 **supplier（改变"可挑集合"）**，轮询/随机是**算法（从集合里挑一个）**——两者配合工作（05 详解"滤镜+算法"两个角色）。

---

## 4.5 算法一眼代码（每种三行，详细实现见 05/06/08/09）

```java
// 轮询 RoundRobinLoadBalancer.java:111-114 —— 完整三行见 09 §2
int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;
ServiceInstance instance = instances.get(pos % instances.size());

// 随机 RandomLoadBalancer.java:84-86
int index = ThreadLocalRandom.current().nextInt(instances.size());
ServiceInstance instance = instances.get(index);

// 权重 WeightedServiceInstanceListSupplier —— 列表先"展开"（A出现3次）再轮询；
// 权重函数唯一契约见 06 §2.1：int apply(ServiceInstance instance)

// 同 zone ZonePreferenceServiceInstanceListSupplier.java:78-97
for (ServiceInstance s : serviceInstances)
    if (zone.equalsIgnoreCase(getZone(s))) filtered.add(s);  // getZone 读 metadata
if (!filtered.isEmpty()) return filtered;
return serviceInstances;   // 同 zone 空了回退全量——是特性不是 bug

// 最少连接 —— SCLB 无内置，见 09 §3 xhs 实现（含预热/活跃数）
// 一致性哈希 —— SCLB 无内置，见 08 §4 方向性示例
// 粘性 —— 读 cookie（默认 sc-lb-instance-id），见 08 §3
```

---

## 5. 选型表（本课核心）

| 你的需求 | 选什么 | 备注 |
|---|---|---|
| 同构无状态，简单均衡 | **轮询（默认）** | 零配置 |
| 想避免"同步节奏"、大量调用方自然打散 | 随机 | 需自配；样本小会不均 |
| 机器异构 / 按能力分配 | **权重**（`metadata.weight`）或 `NacosLoadBalancer` | 注意两个 weight 的坑（06） |
| 多机房低延迟 | zone-preference | 回退全量是特性 |
| 慢请求多、防堆积 | **最少连接**（自研） | xhs 生产姿势（09） |
| 有状态 / 缓存亲和 | 一致性哈希（自研）或粘性 | 见 §3 对比表 |
| 灰度发布 | hint / version supplier / 调权重 | 06 |

---

## 6. 热 key：均衡算法管不了的那一类问题

- 一致性哈希的**代价**：同一个 key 被固定到同一台——如果这个 key 特别热（爆款商品/大 V 用户），那台会被打爆。**它管"同 key 固定"，不管"分摊热度"**。
- 热 key 的解法在**数据层**，不是挑法层：
  1. **打散**：key 加盐/二级分片（`key#1..N` 分散到多台）；
  2. **缓存**：本地缓存/多级缓存把读压力挡掉；
  3. **隔离**：热点数据单独集群/单独实例组。
- 均衡算法负责"整体流量均匀"，热 key 负责"单点流量倾斜"——两种问题别混。

---

## 7. 生产对照：xhs 不是一个算法，是一个"组合"

- xhs 的选择：**zone（自研）+ 最少连接（自研，带权重与预热）+ metadata 驱动**——不是单一算法，而是"按 zone 收敛候选 → 按活跃数/权重挑"的组合拳；
- 动机：长连接/慢请求场景 + 发布预热 + 多机房；
- 网关与服务调用吃同一套（09 展开它的实现）。

---

## 8. 边界与翻篇

- 各算法的**源码细节**（RR 原子计数器、权重展开 GCD/双队列）→ `loadbalancer-lab` D1/D4；本文只到"原理+选型"；
- **权重与灰度的配置坑**（`metadata.weight` vs `nacos.weight`、WeightFunction、预热、hint）→ 06；
- **自定义算法怎么写**（ReactorLoadBalancer/Supplier 扩展点）→ 09；
- 一致性哈希的"槽位改良版"（Redis Cluster 16384 slot）→ [`docs/redis`](../redis/README.md)。

---

## 9. Self-Test

1. 轮询在什么场景下会"平均地把事情搞坏"？换哪个算法？
2. 权重在 SCLB 里怎么实现（一句话原理）？它和 `NacosLoadBalancer` 的加权随机有什么不同？
3. 一致性哈希和请求粘性的区别？实例挂掉时各自的表现是什么？
4. 为什么"热 key"不是换个均衡算法能解决的？

---

## 10. 相关与衔接

- **05 名单管线**：这些 supplier（滤镜）怎么组成链、7 预设逐个看；
- **06 权重与灰度**：`metadata.weight` 深挖 + hint + WeightFunction + 预热；
- **08 重试·粘性·一致性哈希**：亲和类算法的落地；
- **09 自定义 LB**：最少连接/一致性哈希怎么写；
- 网关课程：[L16](../gateway/L16-custom-loadbalancer.md)（gray/zone 实测）/[L46](../gateway/L46-sclb.md)（轮询与摘除实测）；
- [`loadbalancer-lab`](../../loadbalancer-lab/HANDOVER.md)：[A-类官方功能](../../loadbalancer-lab/docs/A-类官方功能.md)（A-01/A-02 轮询/随机、A-07 权重链）/ D1（接口）/ D4（权重展开）。
