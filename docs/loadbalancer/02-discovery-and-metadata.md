# LB-02 名单从哪来：服务发现与实例元数据

> 用途：讲清"名单"这一半——名单从哪来（注册 + 订阅）、里面有什么（实例与 metadata）、怎么变（心跳/剔除/保护阈值）
> 对应系列：**负载均衡（SCLB）专题 · 02**（理论篇第二篇）
> 口径：Nacos **2.2.3**（本机 docker 实验环境；demo 侧 nacos-client 2.3.2）/ SCA 2023.0.1.2 / SCLB 4.1.4
> 素材：[`nacos-lab`](../../nacos-lab/HANDOVER.md)（[A-01 实例生命周期](../../nacos-lab/docs/A-01-服务注册与发现/01-实例生命周期.md)·[客户端缓存](../../nacos-lab/docs/A-01-服务注册与发现/02-客户端缓存与故障转移.md) / [A-04 健康检查](../../nacos-lab/docs/A-04-健康检查/01-心跳与自保护.md) / [A-08 元数据](../../nacos-lab/docs/A-08-元数据管理/01-元数据分析.md)）——细节以其为准，本篇只取"与 LB 相关"的部分
> 前置：01（客户端 vs 服务端）；后续：03 挑法全集 → 05/06（metadata 怎么被消费）→ 07（名单会变怎么兜）

---

## 0. 例子先行：网关内存里的那份名单长什么样

处理 `lb://demo-provider` 时，网关进程内存里握着的是这样一份东西：

```text
serviceId = "demo-provider"
[
  { host: 192.168.13.128, port: 8081, metadata: { zone: shanghai, version: v1 } },
  { host: 192.168.13.128, port: 8082, metadata: { zone: beijing,  version: v2 } },
]
```

对照服务端 LB 的名单（Nginx `upstream`）——那是**人写进配置文件**的、静态的；而这份名单是：

- **服务自己报上来的**（注册时带上 IP:端口 + metadata）；
- **活的**：会增（扩容）、会减（宕机/发布）、还有"健康态"。

围绕它只有三个问题：**怎么来的（§2）→ 里面有什么（§3）→ 怎么变（§4/§5）**。

---

## 1. 掌握卡（3 条）

1. **名单的键是 `serviceId`**（服务名）；获取方式是**订阅**（Nacos 2.x 以 gRPC 推送变更为主），不是每次请求都去注册中心问。
2. **metadata 是"LB 策略的输入面板"**：`zone` / `version` / `weight` / `hint` / `startupTime` …——本系列后面讲的挑法（03-06）全都在消费这些字段；**服务怎么填 metadata，决定它会被怎么挑**。
3. **名单会变，且"看到的不等于真实"**：注册/失联/剔除（15s/30s）、**保护阈值**（健康比例过低时注册中心会故意返回全部实例）、消费端还有本地缓存——所以 LB 侧必须再叠兜底（健康检查/重试/优雅停机 → 07）。

---

## 2. 名单怎么来的：注册 + 订阅（推送为主）

```text
① 服务启动 → 注册自己（serviceId + IP:端口 + metadata）→ 之后靠 gRPC 连接保活
② 网关启动 → 订阅 serviceId "demo-provider" → 首次拿全量名单
③ 名单有变化 → 注册中心【推送】变更给所有订阅者（批量延迟默认 500ms）
```

两个关键点：

- **拉 vs 推**：老一代 Eureka 是客户端 30s 定时轮询**拉**；Nacos 2.x 是服务端**推**（gRPC 长连接）。对 LB 的意义：名单在注册中心侧的变化是**秒级**可见的——但"LB 手里"的名单还隔着它自己的缓存（SCLB 35s → 07 详解）。
- **键**：`spring.application.name` = serviceId = `lb://<serviceId>` 里的那个名字——名单是按这个键查的（04 展开）。

---

## 3. 名单里有什么：ServiceInstance 与 metadata

通用字段：`serviceId` / `host` / `port` / `uri` / `instanceId` / `metadata`。

其中 **metadata 是后端唯一能自由控制、且 LB 直接消费的字段**——它就是一排"开关"：

| metadata 键 | 谁在消费 | 用途 | 本系列哪篇 |
|---|---|---|---|
| `zone` | `zone-preference` 预设 / xhs 自研 | 同机房优先 | 05 / 06 |
| `version` | 灰度 supplier（L16 `GrayInstanceListSupplier`） | 版本准入 / 灰度 | 06 |
| `weight` | `weighted` 预设 / xhs `LeastConnections` | 权重 | 06 |
| `hint` | `HintBasedServiceInstanceListSupplier` | 提示路由（灰度） | 06 |
| `startupTime` | xhs `LeastConnections` | 启动预热（60s 内低权重） | 06 / 09 |
| `API_VERSION` | `loadbalancer-lab` A-13（🟡了解项） | API 版本选择 | — |
| `nacos.weight` / `nacos.cluster` | SCA `NacosLoadBalancer` | SCA 自带 LB 的输入（06 区分它和 `weight`） | 06 |

**怎么填**（provider 注册时带上，值都是字符串）：

```yaml
# demo-provider 的 application.yml（L16 实验的实例就是这么报的）
spring:
  cloud:
    nacos:
      discovery:
        metadata:
          zone: shanghai     # 8081 实例
          version: v1
```

> "值都是字符串"这一点后面会带来一个坑：`weight` 需要自己解析（`metadata.weight` vs `nacos.weight` → 06）。

---

## 4. 名单怎么变（一）：注册 / 失联 / 剔除

微服务默认是**临时实例**（`ephemeral=true`）：

| 事件 | 发生什么 | 时间尺度 |
|---|---|---|
| 正常注册 | 名单 +1，推送给订阅者 | 秒级 |
| 主动注销（优雅停机时） | 名单 -1，推送 | 毫秒~秒级（L46 实测 **≈1.4s**） |
| 连接断开（进程崩了） | 连接层回调，**即刻清理** + 定时补偿兜底 | 毫秒级 + 补偿 |
| 失联但连接未断 | 定时补偿：**T+15s 标记不健康**（推送）→ **T+30s 删除**（推送；服务端开关 `isExpireInstance=true` 时） | 15s / 30s |

> 注意版本口径：**"每 5s 发一次应用层心跳"是 Nacos 1.x 的机制**；2.x 靠 gRPC keep-alive 在连接层保活，15s/30s 只是兜底扫描的补偿参数。

对比**持久化实例**（`ephemeral=false`，数据库/中间件这类）：不自动摘除、CP（Raft）存储、Nacos 重启后恢复——"我不信你会自己报死活"。

**结论**：注册中心侧的摘除是"秒级"的（与我们 L46 实测吻合）；真正慢的那 35s 出在**消费端缓存**（07）。

---

## 5. 名单怎么变（二）：保护阈值与"看到的不等于真实"

**保护阈值（自保护）**：当服务的**健康实例比例 ≤ 阈值**（例如配 0.8）时，注册中心**故意返回全部实例**（含不健康的）——宁可错发，不让你"全拒"。

**在哪配**：Nacos 控制台 → 服务详情 → 保护阈值（0~1）；或创建/更新服务的 API 参数。注意三点：

- 它是 **per-service 字段**，不是全局开关，也不在客户端 `application.yml` 里配；
- 默认值 `0.0`（`ServiceMetadata.java:44`）——`healthy/total <= 0` 才触发，即**只有零健康实例时**才保护；
- 触发代码（`ServiceUtil.java:209-226`）：把不健康实例深拷贝一份 `setHealthy(true)` 再返回，同时打标 `reachProtectionThreshold=true`——"撒谎但留了标记"。配 0.8 就是"健康低于 80% 全返回"。

- 设计动机：怕"少数健康实例被瞬间打爆"，可用性优先；
- 双刃剑：网络抖动时不会误摘实例，但**会掩盖真故障**——真挂了 80%，名单看起来"全好"；
- 定位：**最终防线**，不是日常容错手段。

**再叠一层"不真实"**：消费端（Nacos 客户端/SDK）还有自己的缓存——

```text
内存缓存（正常路径，收到推送就更新，零网络开销）
└─ 磁盘缓存（客户端【重启】时恢复用；Nacos 挂了不会走它）
   └─ failover 文件（运维应急，手动）
```

**对 LB 的含义**：名单本身不可全信（可能含不健康实例、可能有缓存滞后）→ LB 侧必须兜底：`health-check` supplier（主动探测）/ 重试 / 优雅停机（07 详解，含实测）。

---

## 6. 生产对照（xhs / demo）

- **xhs 网关**：实例注册时把自己的 zone 报给注册中心——

  ```yaml
  spring.cloud.nacos.discovery.metadata.zone: ${MYXHS_ZONE:defaultZone}
  ```

  LB 侧的"同 zone 优先"策略消费的就是这个字段（L42 / `xhs-gateway-zone.md`）。
- **demo-provider**：8081 报 `zone=shanghai, version=v1`，8082 报 `zone=beijing, version=v2`——L16 的 gray / zone 实验全靠这两个 metadata。
- **xhs 服务侧**：自研"最少连接"**支持** `startupTime` 预热（读取逻辑在 `LeastConnectionsLoadBalancer`）；但当前仓库**未见写入方**（注册侧没有填这个 metadata）→ 预热是"预留能力"，不填就不预热、权重回落到 `metadata.weight`/默认值（09 展开）。

---

## 7. 边界与翻篇

- Nacos 的机制细节（命名空间 / 鉴权 / 集群 / JRaft / Distro）→ **`nacos-lab`**（本篇只取与 LB 相关部分，不重复）；
- 本篇只讲"名单怎么来、有哪些字段、怎么变"；**名单到 LB 手里还要经过哪些加工**（装饰器链/缓存）→ 05 / 07；
- "挑法怎么消费这些字段"→ 03 / 06。

---

## 8. Self-Test

1. 网关处理 `lb://demo-provider` 时，serviceId 是哪个？名单从哪来、怎么保持新鲜？
2. 实例 metadata 里哪些字段会被 LB 消费？各对应什么策略？
3. Nacos 2.x 临时实例"怎么知道实例死了"？（和 1.x 的 5s 心跳有什么不同？15s/30s 各发生什么？）
4. 保护阈值是什么？为什么说它"宁可错发"？它对 LB 侧意味着什么？

---

## 9. 相关与衔接

- **01 全景** → **02 本篇** → **03 挑法全集**（消费这份名单的"挑"）；
- **05/06**：`zone/weight/hint/version` 这些 metadata 怎么被"滤镜"消费；
- **07 名单会变**：15s/30s、保护阈值、本地缓存 → 实测与兜底；
- [`nacos-lab`](../../nacos-lab/HANDOVER.md)：[A-01 实例生命周期](../../nacos-lab/docs/A-01-服务注册与发现/01-实例生命周期.md) / [A-01 客户端缓存](../../nacos-lab/docs/A-01-服务注册与发现/02-客户端缓存与故障转移.md) / [A-04 健康检查·保护阈值](../../nacos-lab/docs/A-04-健康检查/01-心跳与自保护.md) / [A-08 元数据](../../nacos-lab/docs/A-08-元数据管理/01-元数据分析.md)；
- 网关课程：[L16](../gateway/L16-custom-loadbalancer.md)（gray/zone metadata 实测）/[L46](../gateway/L46-sclb.md)（摘除 1.4s vs 35s 实测）。
