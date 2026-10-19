# LB-07 名单会变：摘除·健康检查·优雅停机

> 用途：客户端 LB 的"代价"在这一课兑现——名单变化时**多久生效、失败长什么样、怎么兜**
> 对应系列：**负载均衡（SCLB）专题 · 07**（使用篇第四篇）
> 口径：SCLB 4.1.4（缓存 TTL 35s）/ Nacos 2.2.3（15s/30s 补偿）/ SCG 4.1.5
> 素材：**L46 实测**（Nacos 1.4s vs 网关 35s 窗口）· `loadbalancer-lab` A-05/A-06（健康检查两坑）· xhs-project（`GracefulShutdownListener`）· `nacos-lab`（15s/30s）
> 前置：02（名单怎么变）/ 05（链结构：Caching、HealthCheck）；后续：08（重试·粘性）→ 09（自定义）

---

## 0. 例子先行：一次 kill 的完整时间线（L46 实测）

```text
T0        kill v2（8082）
T+1.4s    Nacos 实例列表摘掉 v2                        ← 注册中心：秒级
T+2s~T+31s 网关交替 200 / 500
          500 = 打到死掉的 8082：
          AnnotatedConnectException: Connection refused: /192.168.13.128:8082
          （LB 缓存里的旧名单还没过期，还在把流量发给它）
T+36s 起  全部 200（只剩 8081）                        ← 缓存 TTL（35s）过期

对照实验（缓存"冷"）：上次名单写入已超 35s → 下一个请求直接拿新名单 → 立即摘除，无失败窗口
```

两句话：

1. **注册中心秒级 ≠ 调用方秒级**——中间隔着消费端自己的缓存（默认 35s）；
2. 窗口期的失败形态 = **500 `Connection refused`**（打到死实例）。

---

## 1. 掌握卡（3 条）

1. **三个时间尺度，生效时间取决于最慢一环**：注册中心（秒级：注销推送；失联 15s/30s 补偿）→ **LB 缓存（默认 35s）** → 健康检查（默认 25s 轮询）。
2. **兜底三件套各管一段**：**优雅停机**（主动避免"死实例留在名单"）→ **健康检查**（主动探测"僵而不死"）→ **重试**（被动兜住残余窗口，08）。
3. **优雅停机的顺序必须是"先摘流、再关进程"**——顺序反了（先关再摘）就是自己制造 35s 窗口。

---

## 2. 名单变化的三条路径（时间尺度表）

| 路径 | 注册中心何时变 | 调用方（LB）何时变 | 备注 |
|---|---|---|---|
| ① 主动变更（扩容 / 注销） | 秒级（推送） | 取决于缓存：**≤35s**（冷缓存则立即） | 优雅停机走这条 |
| ② 失联剔除（进程崩） | 连接断开即刻 + 15s/30s 补偿 | 同①（再叠缓存） | 02 已讲 |
| ③ "僵而不死"（进程在、接口挂） | **不生效**（心跳正常） | 只能靠**健康检查**（25s 轮询） | 04/05 的 HealthCheck 层 |

**结论**：从"实例出问题"到"网关不再选它"，最慢的一环决定最终时延——默认配置下是那个 **35s 缓存**。

---

## 3. 缓存 35s：主角深挖

**机制**：`CachingServiceInstanceListSupplier`——`CacheFlux` 读穿透 + Caffeine 缓存，TTL 由 `LoadBalancerCacheProperties.ttl` 决定，**默认 35 秒**（`LoadBalancerCacheProperties:42`）。

**为什么要有它**：每次调用都去注册中心拉全量名单太贵（网络 + 反序列化）；35s 是"及时性 vs 成本"的折中。

**两种表现（L46 两次实验的差异全在这）**：

- **缓存"热"**（35s 内刚写过）：死实例还会被选中 → 失败窗口 ≤35s；
- **缓存"冷"**（上次写入已超 35s）：下一个请求 miss → 拉新名单 → 立即摘除。

**调参**：`spring.cloud.loadbalancer.cache.ttl`（调小 → 摘除更及时、注册中心压力更大；调大反之）。

```yaml
spring.cloud.loadbalancer:
  cache:
    ttl: 35s                          # 默认 35s（LoadBalancerCacheProperties:42）；大规模集群别盲目调 0
  health-check:
    interval: 25s                     # 健康检查轮询间隔（默认 25s）
    path.default: /actuator/health    # 探测路径是按 serviceId 的 Map，default 是特殊 key
```

> 注意：`health-check` 预设的链里**没有 Caching**（05）——它是"实时优先"的另一条路。

---

## 4. 健康检查 supplier：主动补漏

**机制**：`HealthCheckServiceInstanceListSupplier` 周期性探测每个实例的健康端点——

- 默认路径 `/actuator/health`（可配 `health-check.path`，支持按服务覆盖）；
- 默认间隔 **25s**（`health-check.interval`），initialDelay 0。

**它解决什么**：Nacos 心跳只管"**进程活着**"，管不了"**僵而不死**"（端口在、接口全 500/超时）——健康检查从**应用层**再筛一遍；同时它也能提前剔除（不等 35s 缓存过期）。

**两个经典坑（lab 实测）**：

| # | 坑 | 现象 | 对策 |
|---|---|---|---|
| 1 | 探测客户端也被 `@LoadBalanced` | 探测请求被拦截器**二次 LB** → 死循环 → 全实例被过滤（"No servers available"） | 用**非** @LoadBalanced 的客户端（lab：`plainRestClient`） |
| 2 | `HttpStatus.OK.equals(int)` 恒 false | 健康判断全判"不健康" | 写 `statusCode() == 200`（`equals(Object)` 装箱比较陷阱） |

---

## 5. 优雅停机：把窗口从"被动等"变"主动关"

**反例**：直接 `kill -9` / 不做摘流的关闭——注册中心要等"连接断开/15s/30s"，窗口期请求持续打空。

**正解（顺序不能反）**：

```text
① 先摘流：实例主动从注册中心摘除（或 K8s 把 Pod 移出 Service 端点）
② 排空在途：等待名单传播 + 在途请求处理完（期间实例仍可服务）
③ 再关进程：容器退出
```

**xhs 的实现**（`GracefulShutdownListener`，监听 `ContextClosedEvent`）：

- SIGTERM → 容器 close → **先发 ContextClosedEvent**（此时 web 仍在接收请求）：**① 摘除实例**（`ServiceRegistry.deregister`）→ **② `sleep 10s`**（`myxhs.shutdown.deregister-wait-seconds`，默认 10）等名单传播 → **③ 自定义 hooks**；线程池关闭挪到 `@PreDestroy`（destroy 阶段 = web 排空之后执行）；
- K8s 场景注释里给了同款 preStop 姿势：`curl -X PUT .../service-registry?status=DOWN && sleep 10`。

**一个值得注意的缝隙（推理，未实测）**：xhs 的等待是 **10s**（对准"注册中心传播"）；但消费端 SCLB 缓存 TTL 是 **35s**——理论上"摘流后、缓存过期前"仍有残余窗口。要完全消掉窗口，排空期应 ≥ 消费端缓存 TTL（或把客户端的 `cache.ttl` 调小/装健康检查）。

---

## 6. 失败形态与兜底矩阵

| 场景 | 现象 | 兜底 |
|---|---|---|
| 死实例被选中（缓存窗口内） | **500 `Connection refused`**（L46 实测） | 重试（08）/ 健康检查 / 优雅停机 |
| 无可用实例（全部被摘除 / 无实例） | **503** `Unable to find instance for <service>`（`use404=true` 则 404；`NotFoundException.create` 源码确认） | 降级/兜底响应（L37 的 fallback 思路） |
| 保护阈值放开（名单混入不健康实例） | 请求被转发到不健康实例 | 健康检查 / 重试 |
| "僵而不死"实例（心跳正常） | 请求超时/500，名单却"健康" | **只有健康检查能治** |

---

## 7. 生产对照

- **xhs**：优雅停机（`GracefulShutdownListener`）+ 发布流程"先摘后停"；网关/服务调用都吃同一套 SCLB 缓存；
- **调参建议**：`cache.ttl` 与"注册中心压力"成反比——大规模集群别盲目调 0；配合健康检查 + 重试，窗口影响可控；
- 本课只讲"名单会变"；**"变了之后的补救动作（重试/粘性）"→ 08**。

---

## 8. 边界与翻篇

- 重试机制（两口味、`retry.avoid-previous-instance`）→ **08**；HealthCheck 源码/测试 → lab A-05 与其隔离测试；注册中心侧机制（15s/30s、保护阈值）→ `nacos-lab`；
- 本课的 L46 日志原文来自会话实测记录（原始日志文件已随环境清理）。

---

## 9. Self-Test

1. 从"实例死掉"到"网关不再选它"，要过哪几关？各自默认多久？最慢的是谁？
2. 窗口期失败长什么样？为什么 L46 两次实验一次"立即"、一次"35s 窗口"？
3. 健康检查解决的是哪一类问题（和 Nacos 心跳的分工）？它的两个经典坑是什么？
4. 优雅停机为什么必须"先摘流"？排空期理论上要覆盖什么（xhs 配的 10s 覆盖了吗）？

---

## 10. 相关与衔接

- **08 重试·粘性·一致性哈希**：窗口期的"被动兜底"与亲和场景；
- **09 自定义 LB**：最少连接（xhs）如何降低"死实例/慢实例"的伤害；
- [`loadbalancer-lab`](../../loadbalancer-lab/HANDOVER.md)：[A-类官方功能](../../loadbalancer-lab/docs/A-类官方功能.md)（A-05 健康检查两坑 / A-06 缓存实测）；
- `nacos-lab`：[A-01 实例生命周期](../../nacos-lab/docs/A-01-服务注册与发现/01-实例生命周期.md)（15s/30s）/ [A-04 保护阈值](../../nacos-lab/docs/A-04-健康检查/01-心跳与自保护.md)；
- 网关课程：[L46](../gateway/L46-sclb.md)（本文 §0 的全部实测来源）。
