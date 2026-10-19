# FEIGN-05 HTTP 客户端与超时

> 用途：Feign 底层"用什么发请求"、超时怎么分层、连接池怎么定、压缩与序列化
> 对应系列：**OpenFeign 专题 · 05**（使用篇第三篇）
> 口径：Spring Cloud OpenFeign 4.1.3 / feign-core 13.3 / feign-hc5 13.3
> 素材：`openfeign-lab`（[A-03 超时](../../openfeign-lab/docs/A-03-超时配置.md) / [A-04 HTTP 客户端切换](../../openfeign-lab/docs/A-04-HTTP客户端切换.md) / [A-05 压缩与序列化](../../openfeign-lab/docs/A-05-压缩与序列化.md)）· xhs-project（payment yml）· 源码（FeignHttpClientProperties / ApacheHttp5Client 字节码）
> 前置：03（配置）/ 04（拦截器）；后续：06 容错 → 07 LB 与重试（超时×重试矩阵联动）

---

## 0. 例子先行：xhs 的 HTTP 客户端配置

```yaml
spring:
  cloud:
    openfeign:
      httpclient:
        hc5:
          enabled: true                 # Apache HttpClient5
          max-connections: 200          # 连接池总上限
          max-connections-per-route: 50 # 对单个目标（IP:端口）的并发上限
      client:
        config:
          default:
            connect-timeout: 3000       # 建连超时 3s
            read-timeout: 5000          # 读超时 5s
```

两个问题：**① 为什么从默认客户端换 HC5？② 3s/5s 这两个数怎么定？**——本篇回答（连接池/超时分层）。

---

## 1. 掌握卡（3 条）

1. **客户端可替换**（常用三种 + 一个可选）：默认 `Client.Default`（HttpURLConnection，**无池可调**）→ **OkHttp**（`okhttp.enabled=true`，opt-in）→ **Apache HttpClient5**（**放了 `feign-hc5` 依赖就默认生效**——条件 `havingValue=true, matchIfMissing=true`）；另有 **HTTP/2 客户端**（`http2client.enabled`，opt-in、少用）；**4.x 已移除 HttpClient4**。
2. **超时两层**：业务层 `client.config.<key>.connect-timeout / read-timeout`（**默认 10s / 60s**，来自 feign-core `Request.Options`，**按请求生效**）× 客户端构建默认 `httpclient.connection-timeout`（默认 **2s**）——配的时候以业务层为准。
3. **池在客户端层**：`max-connections` / `max-connections-per-route` / `time-to-live` 等（默认 **200 / 50 / 900s**）——**只有换了带池客户端（HC5/OkHttp）才有意义**。

---

## 2. HTTP 客户端：三种实现与开关

| 实现 | 依赖 | 开关（前缀 `spring.cloud.openfeign`） | 默认 | 特点 |
|---|---|---|---|---|
| `Client.Default` | 无（feign-core） | — | **缺省** | `HttpURLConnection`；无池化参数可调 |
| OkHttp | `feign-okhttp` | `okhttp.enabled=true` | opt-in（条件无 matchIfMissing） | **透明压缩**（源码注：带 Accept-Encoding 会关掉它） |
| **HC5** | `feign-hc5` | `httpclient.hc5.enabled`（**缺省 true**） | **放依赖即生效** | 连接池、redirect、HTTP2 |
| HTTP/2 客户端 | 对应 HTTP/2 客户端依赖 | `http2client.enabled=true` | opt-in | HTTP/2 专用（实际项目少用，了解即可） |

> 4.x 已无 HttpClient4（`feign-httpclient`）支持——网上老文章的 `feign.httpclient.enabled` 是旧前缀/旧版本口径（03 已讲前缀迁移）。

---

## 3. 连接池：参数与定值（`FeignHttpClientProperties`，前缀 `...httpclient`）

| 参数 | 默认 | 说明 |
|---|---|---|
| `max-connections` | **200** | 池总上限 |
| `max-connections-per-route` | **50** | **单个目标（host:port）** 的并发上限 |
| `time-to-live` | **900s** | 连接存活上限 |
| `connection-timeout` | **2000ms** | HC5/Http2 客户端构建时的建连默认（§4） |
| `follow-redirects` | true | 跟随重定向 |
| `hc5.pool-concurrency-policy` / `reuse-policy` | STRICT / FIFO | HC5 池策略 |

**定值要点**：

- **"per-route" = 每个下游实例**（`IP:端口`）——服务扩到 5 个实例，池就按 5 条 route 分散；per-route 要 ≥ **单实例**峰值并发；
- 池参数只影响"连接复用"效率，不影响正确性；实例摘除后旧连接自然废弃；
- 与 keep-alive 的关系：**`time-to-live` 必须小于下游的 keep-alive 超时**，否则会复用到"对端已关"的连接 → `PrematureClose`（网关侧踩过的同类坑，跨 L46 的 httpclient pool 注释/网关课程）。

---

## 4. 超时：两层、默认值、异常形态

### 4.1 配置与默认值

```yaml
spring.cloud.openfeign.client.config.<key>:
  connect-timeout: 2000    # 默认 10000（feign-core Request.Options）
  read-timeout: 5000       # 默认 60000
```

```yaml
spring.cloud.openfeign.httpclient:
  connection-timeout: 2000 # 默认 2000；HC5 客户端构建时的建连默认
```

**生效链（HC5，字节码核实）**：`ApacheHttp5Client.configureTimeoutsAndRedirection(Request.Options)` → 对每个请求设置 `RequestConfig.setConnectTimeout(...)/setResponseTimeout(...)`——**业务层的两个超时按请求生效**。

### 4.2 异常形态（lab A-16 实测口径）

```text
建连失败/超时    → ConnectTimeoutException
读超时           → SocketTimeoutException（is-a IOException，也是默认 retryable-exceptions）
包装             → RetryableException →（重试）→ 最终 ResourceAccessException / FeignException
实测：read-timeout=3000 + retry 一次 → 总耗时 ≈6000ms → 抛 ResourceAccessException
```

### 4.3 超时 × 重试 × 熔断 矩阵（本节是重点）

| 层 | 参数 | 默认 | 备注 |
|---|---|---|---|
| Feign 单跳 | `read-timeout` | 60s | **单跳最坏 = read-timeout × (1 + 重试次数)**（实测 3s×2=6s） |
| Feign 重试 | `spring.cloud.loadbalancer.retry.*` | next=1 | 超时是"可重试异常"（IOException 家族）——超时会被重试**放大**（07） |
| 网关 | 路由 `metadata.response-timeout` / 全局 | L37/L46 | 谁短谁先切；网关超时应 ≥ 下游全链最坏（含重试） |
| 熔断 | Sentinel/Resilience4j | — | 慢调用（RT）统计要放在超时**之后**的观测点上（06） |

**定值经验**：

- 连接超时短（1~3s，建连通常毫秒级，出问题是网络/对端故障，等久没意义）；
- 读超时按接口分级（P99 + 余量），**慢接口单独配一个 client**（别用 default 的 60s 硬扛）；
- 改 read-timeout 时**同步检查重试次数**（乘法效应）。

---

## 5. 压缩与序列化

### 5.1 压缩（两开关 + 细节属性）

```yaml
spring.cloud.openfeign.compression:
  request:
    enabled: true            # 请求体 gzip（条件：spring.cloud.openfeign.compression.request.enabled）
    mime-types: [text/xml, application/xml, application/json]   # 默认
    min-request-size: 2048   # 默认（小于它不压）
  response:
    enabled: true            # 响应 Accept-Encoding: gzip
```

- 请求压缩、响应压缩**各自独立**；响应压缩本质是加 `Accept-Encoding`（服务端要支持）；
- **OkHttp 是透明压缩**：若显式带了 `Accept-Encoding`，会**关掉**它的透明压缩（源码注释原文）——用了 OkHttp 别重复配。

### 5.2 序列化

- 默认走 `SpringEncoder`/`SpringDecoder` + `HttpMessageConverters`（Jackson）；
- 自定义编解码 → 扩展点 B-04（08 展开）；xhs 的 `FeignUnifiedConfig` 就是在 Decoder 上做 **`R<T>` 自动解包 + BizException 还原**（08）。

---

## 6. 坑清单

| # | 坑 | 说明 |
|---|---|---|
| 1 | 换客户端用错开关/前缀 | `okhttp.enabled` vs `httpclient.hc5.enabled`；**HC5 是"放依赖即开"**（matchIfMissing=true） |
| 2 | 默认超时太长 | read 60s 会拖死调用侧资源——生产必须显式配（xhs：3s/5s） |
| 3 | 超时 × 重试的乘法 | 改超时忘看 retries（矩阵见 §4.3） |
| 4 | 池与 keep-alive 失配 | TTL/连接存活要小于下游 keep-alive，否则 PrematureClose |
| 5 | 压缩两边不一致 | 请求压缩需服务端解压支持；OkHttp 透明压缩与显式 Accept-Encoding 冲突 |

---

## 7. Self-Test

1. 三种 HTTP 客户端怎么切？各有什么前提？HC5 的默认状态是什么？
2. Feign 的两层超时分别是什么、默认多少、哪层按请求生效？
3. read-timeout=3s + 重试 1 次，单请求最坏耗时？与网关 response-timeout 怎么配合？
4. 请求压缩和响应压缩分别怎么配？OkHttp 的注意点是什么？

---

## 8. 相关与衔接

- **06 容错**：超时/异常 → Sentinel 熔断的观测点；
- **07 LB 与重试**：`retryable-exceptions` 默认含超时——为什么超时会被重试放大（含 `NEVER_RETRY` 立场）；
- **08 进阶**：`FeignUnifiedConfig` 的 Decoder 增强；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[A-03 超时](../../openfeign-lab/docs/A-03-超时配置.md) / [A-04 客户端切换](../../openfeign-lab/docs/A-04-HTTP客户端切换.md) / [A-05 压缩与序列化](../../openfeign-lab/docs/A-05-压缩与序列化.md)；
- 网关课程：[L37](../gateway/L37-circuit-breaker-retry.md)（网关侧超时/重试/熔断联动）。
