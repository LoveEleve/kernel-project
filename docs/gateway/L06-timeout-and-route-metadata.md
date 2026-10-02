# Lesson 06: Timeouts & Route Metadata — 超时与路由元数据

> 课程索引：`../gateway-学习清单.md` → Stage 2 · Lesson 06
> 版本口径：xhs-demo 实际运行的 SCG **4.1.5**
> 来源：官方文档 `http-timeouts-configuration` + `RouteMetadataUtils` + 实测
> **提问方式**：直接说编号，如「6.2 没懂」

---

## 6.0 本课要掌握的 4 条

| # | 要掌握的 | 一句话 | 在哪节 |
|---|---|---|---|
| **①** | **超时两层配置 + 单位坑** | 全局 `response-timeout` = **Duration**（`5s`）｜路由级 `metadata.response-timeout` = **毫秒**（`500`）<br>**写错 → 静默回退全局，不报错**<br>（转发侧其实共 **4 个**超时，另两个是 `pool.acquire-timeout` / `ssl.handshake-timeout`） | 6.2 |
| **②** | **超时值怎么定** | 铁律：**网关超时 < 上游超时**（否则上游白干） | 6.3 |
| **③** | **metadata 是什么** | **路由级自由 kv**（不属于任何具体过滤器）—— 和 `args` 的区别 | 6.4 |
| **④** | **框架原生只读 3 个 key** | `response-timeout` / `connect-timeout` / `cors`；<br>**其它 key 必须自己写过滤器读，否则是死配置** | 6.6 |

---

## 6.1 超时：它干了什么（例子先行）

**场景**：下游 `/slow` 要 3 秒才返回，你希望网关最多等 500ms —— 超了就**立刻**给客户端 504，不再占着连接。

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: demo-provider
          uri: http://localhost:8081
          predicates:
            - Path=/provider/**
          metadata:
            response-timeout: 500        # ← 单位：毫秒
```

| 请求 | 结果 |
|---|---|
| 配了 `response-timeout: 500` | **504 Gateway Timeout，0.52s** |
| 没配（全局 10s） | 200，3.08s |

---

## 6.2 两个配置位 + 单位坑（要掌握的 ①）

| | 配置位置 | `response-timeout` 单位 | `connect-timeout` 单位 |
|---|---|---|---|
| **全局** | `spring.cloud.gateway.httpclient.*` | **Duration**（`5s` / `500ms`） | 毫秒 |
| **路由级** | `metadata.*` | **毫秒**（`500`） | 毫秒 |

```yaml
# 全局
spring:
  cloud:
    gateway:
      httpclient:
        connect-timeout: 1000
        response-timeout: 5s          # ★ Duration

# 路由级（覆盖全局）
        metadata:
          connect-timeout: 200        # 毫秒
          response-timeout: 200       # ★ 毫秒
```

**坑（必须记）**：`response-timeout` 两层**单位不同**（全局 Duration、路由级毫秒）。

| 写错的后果 | 说明 |
|---|---|
| **静默回退全局值** | 不报错、不警告（源码里 `NumberFormatException` 被 catch 后直接返回全局默认）—— 你以为配了 500ms，实际是全局 5s |

**另外一条**：路由级 `response-timeout: -1`（负数）= **禁用全局超时**（官方文档明说）。

### 精确清单：`httpclient` 下一共 4 个超时（△ 第 3 个最容易出事）

| # | 配置 | 默认 | 作用 | 路由级能覆盖？ |
|---|---|---|---|---|
| 1 | `httpclient.connect-timeout` | 网关不设（沿用 Netty 默认） | **TCP 连接建立** | ✅ `metadata.connect-timeout` |
| 2 | `httpclient.response-timeout` | 网关不设（**= 不限制**） | **等下游响应** | ✅ `metadata.response-timeout` |
| 3 | `httpclient.pool.acquire-timeout` | **45000ms** | **池满时等空闲连接** | ❌ |
| 4 | `httpclient.ssl.handshake-timeout` | **10s** | **SSL 握手** | ❌ |

（另有 `pool.max-idle-time` / `max-life-time` = **连接存活期**，不是超时）

**为什么容易以为"只有 2 个"**：
- 官方 `http-timeouts-configuration` 那页只讲 1、2
- 3、4 藏在 `Pool` / `Ssl` 配置块里
- **路由级视角确实只有 2 个**（`RouteMetadataUtils` 只有 2 个常量）

**⚠️ 第 3 个 `pool.acquire-timeout`（生产事故级）**：

```
并发高 + 下游慢 → 连接池（默认 max(CPU核数,8)×2 个）被占满
              → 新请求连"连接"都拿不到 → 干等 45s → 抛 PoolAcquireTimeoutException
```

→ 它和 `response-timeout` 是**两回事**：一个是"**等响应**"，一个是"**等连接**"。

**方向说明**：以上都是**出站**（网关 → 下游）超时；**入站**（客户端 → 网关）Gateway 没有提供专门配置（属于 Netty server，要自己定制）。

### 连接池是什么池？

> **是"网关 → 下游服务"的出站 TCP 连接池**（reactor-netty 的 `ConnectionProvider`）。

**⚠️ 先分清：网关里有 3 种"连接"，只有 ② 是本文说的池**：

```
① 客户端 ──TCP──→ 网关              （入站连接，Netty server 侧，不是池）
② 网关 ──TCP池──→ 下游微服务实例     （★ 本文说的连接池）
③ 网关 ──gRPC──→ Nacos              （Nacos 客户端自己管的，不在②里）
```

| | ② 转发池 | ③ Nacos 连接 |
|---|---|---|
| 谁管理 | **reactor-netty `ConnectionProvider`**（`NettyRoutingFilter` 用） | **nacos-client 自己**（gRPC 长连接） |
| 配置在哪 | `spring.cloud.gateway.httpclient.pool.*` | `spring.cloud.nacos.*` |
| 装什么 | 到**下游业务服务**的连接 | 到 **Nacos Server** 的连接 |
| `max-connections` 影响谁 | 下游业务调用 | ❌ **不影响** Nacos |

（同理：**Redis**（限流用）、**Sentinel** 也各管各的连接，都不在这个池里）

| 问题 | 答案 |
|---|---|
| 池里装什么 | **到下游的 TCP 连接**（HTTP/1.1 keep-alive 复用） |
| 怎么分池 | **按下游目标分**（`PoolKey` = FQDN + 地址 + pipeline） |
| 池多大 | 默认 `max(CPU核数, 8) × 2`（8 核 = 32 条）—— **per-pool** |
| 为什么要 | **省掉每次的 TCP 三次握手（+TLS 握手）** |

**"分池"是什么意思**：连接池不是一个大池子，而是**一个"连接目标"一个池**。

**池键 = `(FQDN, 目标地址 IP:port, channelHash)`**（`channelHash` = 连接参数：channel options + 代理 + resolver 的哈希）

| 场景 | 池数 |
|---|---|
| 静态路由 `http://localhost:8081` | **1 个** |
| `lb://demo-provider` 有 **3 个实例** | **3 个**（每实例一个） |
| 两条不同路由都指向 `http://localhost:8081` | **共用 1 个**（池跟目标走，**不跟路由走**） |
| 同一地址，一条走代理、一条不走 | **2 个**（channelHash 不同） |

**为什么必须分**：连接**只能连到特定目标**（到 A 的连接不能用来请求 B）。
**为什么 FQDN 也进 key**：同一个 IP 可能承载多个域名（虚拟主机 + TLS SNI），不能混用一个池。

**⚠️ 重要推论（容量评估容易漏）**：`max-connections` 是 **per-pool** 的：

```
demo-provider 有 10 个实例
→ 10 个池 × 每池默认 32 条 = 最多 320 条出站连接（不是 32！）
```

→ **下游扩容会"放大"网关的出站连接数**。

**后果（池满）**：池满是 **per-target** 的 —— `demo-provider` 慢只会让**它自己的池**满，不影响去别的服务的请求。

```
不用池：每个请求 → 握手 → 请求 → 关连接       （握手开销大）
用池：  每个请求 → 池里借 → 请求 → 还回池      （连接复用）
```

**池满 = 排队**：下游慢（连接被长期占用）+ 并发高 → 池里没空闲连接 → 新请求**排队等**。

### "等下游响应"是什么？（非阻塞，没有线程在等）

**超时的实现 = Netty pipeline 上的 `ReadTimeoutHandler`**（`IdleStateHandler` 的子类）：

```
① 网关把请求 write 进连接 → 线程立刻走人（去处理别的请求）
② pipeline 上挂着 ReadTimeoutHandler（事件循环的定时器）
③ 下游响应到 → epoll 通知事件循环 → 触发读事件 → 处理响应
   或
   定时器先到 → 触发 ReadTimeoutException → 关连接 → 网关返回 504
```

| | 阻塞模型（传统 Tomcat） | **非阻塞（Gateway / reactor-netty）** |
|---|---|---|
| 等待方式 | **线程卡在 `socket.read()`** | **没有线程等** —— 事件循环 + 定时器 |
| 一万个慢请求 | 要一万个线程 | **就那几个事件循环线程** |
| 超时实现 | socket 读超时 | pipeline 上的 `ReadTimeoutHandler` |

→ `response-timeout` 的准确含义 = **"请求写出去 → 收到响应"之间允许的最大时间窗**（定时器测量，不是线程在等）。参见前置课 0.4.x EventLoop。

### 三个超时 = 转发的三个"卡点"

```
网关要转发请求：
 ① 需要一条连接
    ├─ 池里有空闲 → 立刻借到
    └─ 池满 → 排队 ← pool.acquire-timeout（默认 45s）★ 等【连接】
 ② 借到连接，建连
    └─ 等 TCP 握手完成 ← connect-timeout ★ 等【建连】
 ③ 请求写出去，等下游回
    └─ 从写完到收到响应 ← response-timeout ★ 等【响应】
```

| 超时 | 等什么 | 典型触发 | 表现 |
|---|---|---|---|
| `pool.acquire-timeout` | **等空闲连接** | 下游慢 + 并发高 → 池满 | `PoolAcquireTimeoutException` |
| `connect-timeout` | **等 TCP 建连** | 下游挂了 / 网络不通 | `ConnectTimeoutException` |
| `response-timeout` | **等响应数据** | 下游"连上了但不回" | 网关返回 **504** |

**排障口诀**：看异常类型就知道卡在哪一步（等连接 / 等建连 / 等响应）。

> **正交概念**：连接池（减少**建连次数**）与非阻塞 IO（减少**线程数**）是两个独立优化，互补不替代。

---

## 6.3 超时值怎么定（要掌握的 ②）

> **铁律：网关超时 < 上游超时**

```
客户端 → 网关（超时 5s）→ 上游（超时 10s）
              ↑ 5s 到了返回 504，但上游还在跑
```

**为什么**：
| 顺序 | 后果 |
|---|---|
| **网关先超时**（错） | 上游的工作**不会停**（资源照烧），且上游可能马上就把活干完了（**白干**） |
| **上游先超时**（对） | 网关能拿到真实错误码（500）→ **更有诊断价值** |

| 场景 | 建议 |
|---|---|
| 全局默认 | 安全值（如 5s） |
| 慢接口 | 单独配大值（如 30s） |
| 流式 / SSE / WebSocket | 调大或**禁用** |

---

## 6.4 metadata 是什么（要掌握的 ③）

metadata = **挂在路由上的自由 kv 袋子**，不属于任何具体过滤器：

```yaml
- id: demo-provider
  uri: http://localhost:8081
  predicates:
    - Path=/provider/**
  filters:
    - StripPrefix=1
  metadata:                          # ← 就是它
    response-timeout: 500            # 框架读
    gray: true                       # 你自己的过滤器读
    version: v2                      # 你自己的过滤器读
```

### 和 `args` 的区别

| | `args` | `metadata` |
|---|---|---|
| 属于谁 | **某个具体的 Predicate / Filter 工厂** | **路由本身** |
| 结构 | 由工厂的 `Config` 类决定（**固定**） | **自由** kv（随你写） |
| 谁读 | 那个工厂 | 框架 + **任何自定义过滤器** |
| Actuator 里长啥样 | `{"name":"Path","args":{"_genkey_0":...}}` | `"metadata":{...}` |

**一句话**：`args` 是"喂给某个组件的参数"，`metadata` 是"路由的扩展位"。

> ⚠️ **别混淆（重要）**：名字都叫 metadata，但**完全不是一回事**。

```yaml
# 1️⃣ 路由 metadata —— 写在【网关】的 yml 里
spring:
  cloud:
    gateway:
      routes:
        - id: demo-provider
          uri: lb://demo-provider
          metadata:                  # ← 路由 metadata
            response-timeout: 500
            gray: true
```

```yaml
# 2️⃣ 实例 metadata —— 写在【被调用方服务】自己的 yml 里
spring:
  cloud:
    nacos:
      discovery:
        metadata:                    # ← 实例 metadata（随注册报给 Nacos）
          zone: shanghai
          version: v2
```

| | **路由 metadata** | **实例 metadata** |
|---|---|---|
| 挂在哪 | 网关的 `Route` 对象（本地） | 注册中心里的每个 `ServiceInstance` |
| **定义在哪** | **网关的 yml** | **被调用方服务的 yml** |
| **谁写** | 网关维护者 | **各个微服务自己**（启动时随注册上报） |
| 谁读 | 网关框架 + 网关里的过滤器 | 网关的 LoadBalancer（**选实例时**） |
| 数量 | 每条路由 1 份 | **每个实例 1 份**（3 个实例 = 3 份，可各不相同） |
| 典型 key | `response-timeout` | `zone`、`version`、`weight` |
| 什么时候学 | ✅ L06（本课） | ⏭️ **L42 zone / L43 `we://` / L44 Nacos** |

**一次请求里两者都用到**：

```
网关收到请求
 ① 匹配路由 R → 读 R 的【路由 metadata】（response-timeout: 500）
 ② 路由 uri = lb://demo-provider
 ③ LoadBalancer 去 Nacos 拿实例列表
      实例A【实例 metadata】zone=shanghai / 实例B zone=beijing
 ④ 按 zone 过滤/加权 → 选出 1 台
 ⑤ 按 ① 的超时转发
```

**现在只需记一句**：**路由 metadata 是网关自己的；实例 metadata 是每个服务实例报给注册中心的，用来"选实例"。**

---

## 6.5 框架原生读哪些 key（要掌握的 ④）

**只有 3 个**：

| key | 谁读 | 用途 |
|---|---|---|
| `response-timeout` | `NettyRoutingFilter` | 路由级响应超时（**毫秒**） |
| `connect-timeout` | `NettyRoutingFilter` | 路由级连接超时（毫秒） |
| `cors` | `CorsGatewayFilterApplicationListener` | 路由级 CORS 配置（**L07 讲**） |

**⚠️ 其它 key 都是自定义的**：

```yaml
metadata:
  rate-limit-qps: 10      # ← 框架不管它
```

→ 只有你写了读它的代码（自定义过滤器）才生效；
→ **不写读它的代码 = 死配置**（不报错）

**隐藏用法**：`POST /actuator/gateway/refresh?metadata=group:group-1`
→ **只刷新带该 metadata 的路由**（不用全量重建）—— 见 L08

---

## 6.6 配置写在哪、对谁生效（2 个位置 / 3 种配置）

> 6.2 说的"两层"是**位置**（全局 / 路由级）。精确地说：**位置只有 2 个，但全局那个位置里装着两种性质不同的东西** —— 所以一共 3 种配置。

```
配置文件
│
├─ spring.cloud.gateway.xxx           ← 位置 1：全局
│    ├─ ① 配置值（httpclient: / globalcors: / discovery: ...）
│    └─ ② 过滤器列表（default-filters:）        ★ 性质特殊
│
└─ spring.cloud.gateway.routes[]      ← 位置 2：路由级
     └─ ③ predicates / filters / metadata
```

### 一份三层齐全的配置

```yaml
spring:
  cloud:
    gateway:
      httpclient:
        response-timeout: 5s                      # ① 全局配置值
      default-filters:
        - AddResponseHeader=X-Gateway,my-gw       # ② 全局过滤器列表
      routes:
        - id: demo-provider
          uri: http://localhost:8081
          predicates:
            - Path=/provider/**
          filters:
            - StripPrefix=1                       # ③ 路由级过滤器
          metadata:
            response-timeout: 500                 # ③ 路由级 metadata（覆盖 ①）
```

### 发一个 `/provider/hello`，它们各自干了什么

| 配置 | 哪层 | 效果 |
|---|---|---|
| `AddResponseHeader=X-Gateway,my-gw` | ② | 响应多一个头（**所有路由**都加） |
| `response-timeout: 5s` | ① | 全局默认超时 5s |
| `response-timeout: 500` | ③ | **这条路由**超时 500ms（**覆盖** ①） |
| `StripPrefix=1` | ③ | **这条路由**去掉 `/provider` |

### 为什么 ② 要单独拿出来

因为 ② **长得像 ③**（都是过滤器列表），但**写在全局**：

| | ② `default-filters` | ③ `routes[].filters` |
|---|---|---|
| 写在哪 | 全局 | 路由里 |
| 对谁生效 | **所有路由** | 只该路由 |
| 语义 | **追加**（等价于给每条路由抄一份） | 该路由自己的 |
| 顺序 | 先追加②再追加③，**然后一起按 `order()` 排序** | 同左 |

**关键**：② 和 ③ 是**叠加**关系（都是过滤器）—— **不是覆盖**。这跟"③ 覆盖 ①"不一样。

### 判断口诀（3 秒定位）

| 你看到的 | 属于 |
|---|---|
| 在 `routes[]` 里 | ③ 路由级 |
| 在 `spring.cloud.gateway.` 下，叫 `default-filters` | ② 默认过滤器 |
| 在 `spring.cloud.gateway.` 下的其它 | ① 全局配置值 |

---

## 6.7 值不值得深挖

| 内容 | 判断 |
|---|---|
| 两层超时 + **单位坑** | ✅ **必须记**（单位写错静默失效） |
| 路由级 `-1` 禁用 | 知道即可（一句话翻篇） |
| **网关超时 < 上游超时** | ✅ **值得**（铁律） |
| metadata = 路由扩展位 | ✅ **值得**（自定义过滤器的入口） |
| 框架读的 3 个 key | ✅ **值得**（多一个都是死配置） |
| "为什么写了不生效"的完整审计清单 | ⏭️ **见 L41 审计**（死配置 4 种：位置不对 / 框架没读 / 单位写错 / 没刷新） |

---

## 6.8 Self-Test

1. 全局和路由级的 `response-timeout` 单位分别是什么？写错会怎样？
2. 怎么用路由级配置**禁用**全局超时？
3. 为什么"网关超时 < 上游超时"？反过来会怎样？
4. `args` 和 `metadata` 的区别是什么？
5. 框架原生读哪 3 个 metadata key？写第 4 个会怎样？
6. 路由 metadata 和实例 metadata 的区别是什么？（各举一个例子）
7. 配置有 2 个位置、3 种。3 种分别是什么？其中哪种和路由级过滤器是"叠加"而非"覆盖"？
8. 转发侧（出站）一共有哪 4 个超时？哪个超时会导致 `PoolAcquireTimeoutException`？它和 `response-timeout` 的区别是什么？
9. 入站（客户端 → 网关）超时 Gateway 提供了吗？
10. 连接池的池键包含哪三部分？一个服务有 3 个实例时是几个池？两条路由指向同一地址时是几个池？
11. `max-connections` 是 per-pool 还是全局的？下游从 3 个实例扩到 10 个实例，网关出站连接上限怎么变？
12. 网关里有哪 3 种"连接"？本文说的连接池是哪一种？Nacos 的连接在这个池里吗？

---

## 6.9 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「6.2 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| 1 | `spring.config.import` 拉 Nacos 配置的机制（谁覆盖谁、本地 fallback 怎么设） | 已答（导入 Nacos 覆盖宿主本地），归档 L44 |
| | | |
