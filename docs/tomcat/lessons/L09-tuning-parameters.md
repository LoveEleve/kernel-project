# Lesson 09: 调参 — 线程、连接、超时

> 课程索引：`../学习清单.md` → Stage 2 · Lesson 09
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`）；内嵌运行版本 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）
> 来源：`webapps/docs/config/http.xml`（参数定义）；Boot 属性名来自 `ServerProperties`；lab 实测（`TuningTest` / `close-wait-demo.sh`）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L09-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 三类参数（线程/连接/超时）的官方默认值、"满了"的现象、以及怎么定的思路 |
| 前置 | L03（三类线程 + maxThreads/maxConnections/acceptCount）、L07（停机）、L08（异步超时 30s） |
| 不涉及 | 源码（Stage 3）；压测方法学（属性能专题） |
| 下一课 | L10 Connector / Endpoint 主干（Stage 3 开始） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **三类参数**：
   - 线程类：`maxThreads`（200）、`minSpareThreads`（10）、`maxQueueSize`（**无界**）；
   - 连接类：`maxConnections`（8192）、`acceptCount`（100）；
   - 超时类：`connectionTimeout`（**60s**；标准 `server.xml` 设 **20s**）、`keepAliveTimeout`（跟随 connectionTimeout）、`asyncTimeout`（30s）、`maxKeepAliveRequests`（100）。
2. **"满了"的现象**：连接满 → 新连接被"卡住"（accept 但不处理）+ 队列满 → OS 拒绝/超时；线程满 + 无界队列 → **排队变慢**（不是报错）；**CLOSE_WAIT 连接同样占连接名额**。
3. **调参方法**：先观察（`busy` 指标 / `ss` / 日志）再改；`maxConnections` 可远大于 `maxThreads`（空闲 keep-alive 连接不占线程）；`acceptCount` 受内核 `somaxconn` 限制。

---

## 2. 官方原文（按类别）

### 2.1 线程类参数

> **minSpareThreads**: The minimum number of threads always kept running. This includes both active and idle threads. If not specified, the default of `10` is used. If an executor is associated with this connector, this attribute is ignored ...

> **maxQueueSize**: (int) The maximum number of runnable tasks that can queue up awaiting execution before they are rejected. The default value is `Integer.MAX_VALUE`.

**翻译（摘要）**：最少的常驻线程数（含活跃与空闲），默认 **10**；有 executor 时被忽略。任务队列上限，超出后**拒绝**，默认 **无界**（`Integer.MAX_VALUE`）。

> **maxThreads**（L03 已引）：请求处理线程上限，默认 **200**；有 executor 时被忽略。

**要点**：队列默认无界 → **线程满的默认表现是"变慢"，不是"报错"**。

### 2.2 连接类参数（L03 已引，此处回指）

- `maxConnections`（默认 8192）：达上限后官方口径是 "accept, but not process, one further connection"；**NIO/NIO2 下设为 -1 可禁用该限制**（官方原文："will disable the maxConnections feature and connections will not be counted"）；
- `acceptCount`（默认 100）：达连接上限后的 OS 入站队列长度，队列满则拒绝/超时。

### 2.3 超时类参数

> **connectionTimeout**: The number of milliseconds this Connector will wait, after accepting a connection, for the request URI line to be presented. ... The default value is `60000` (i.e. 60 seconds) **but note that the standard server.xml that ships with Tomcat sets this to 20000** (i.e. 20 seconds). Unless `disableUploadTimeout` is set to `false`, this timeout will also be used when reading the request body (if any).

> **keepAliveTimeout**: The number of milliseconds this Connector will wait for another HTTP request before closing the connection. The default value is to **use the value that has been set for the connectionTimeout attribute**. ...

> **maxKeepAliveRequests**: The maximum number of HTTP requests which can be pipelined until the connection is closed by the server. Setting this attribute to `1` will disable HTTP/1.0 keep-alive, as well as HTTP/1.1 keep-alive and pipelining. Setting this to `-1` will allow an unlimited amount... If not specified, this attribute is set to `100`.

**翻译（摘要）**：

- `connectionTimeout`：接受连接后，等"请求行"出现的时间；**API 默认 60000（60s）**；但**发行版 server.xml 显式设成 20000（20s）**；请求体读取也用它（除非关掉 upload timeout）；
- `keepAliveTimeout`：等下一个请求的时间；**默认取 connectionTimeout 的值**；
- `maxKeepAliveRequests`：一条连接最多处理多少请求；**1 关闭 keep-alive，-1 不限**，默认 **100**。

> **"三个数字"的完整版（内嵌 vs 独立）**：
> ① API 默认 60s（源码构造时的默认）；② **独立发行版 server.xml 设 20s**（上文官方原文，本地 `conf/server.xml` 已核实）；
> ③ **内嵌 Boot 不读 server.xml → 默认 60s**（除非配 `server.tomcat.connection-timeout`）。

### 2.4 异步超时

- `asyncTimeout` 默认 30000ms（L08 已讲）；Boot 侧可用 `spring.mvc.async.request-timeout` 覆盖。

---

## 3. 参数全景表（默认值 + Boot 属性）

| 类别 | 参数 | 默认 | 管什么 | Boot 属性（线程/连接/超时类均为 `server.tomcat.*`；异步超时例外） |
|---|---|---|---|---|
| 线程 | `maxThreads` | 200 | 请求处理线程上限 | `threads.max` |
| 线程 | `minSpareThreads` | 10 | 常驻线程数 | `threads.min-spare` |
| 线程 | `maxQueueSize` | 无界 | 任务队列上限（满则拒绝） | `threads.max-queue-capacity` |
| 连接 | `maxConnections` | 8192 | 同时处理的连接上限 | `max-connections` |
| 连接 | `acceptCount` | 100 | 达连接上限后的 OS 队列 | `accept-count` |
| 超时 | `connectionTimeout` | 60s（发行版 20s） | 等请求行/请求体 | `connection-timeout` |
| 超时 | `keepAliveTimeout` | 跟随 connectionTimeout | keep-alive 空闲等待 | `keep-alive-timeout` |
| 超时 | `maxKeepAliveRequests` | 100 | 连接复用次数上限 | `max-keep-alive-requests` |
| 超时 | `asyncTimeout` | 30s | 异步请求超时 | `spring.mvc.async.request-timeout` |

---

## 4. "满了"的行为（一图看清）

```
新连接 → OS 队列(acceptCount=100) → 连接闸门(maxConnections=8192) → Worker 池(maxThreads=200) → 任务队列(默认无界)
              ↑ 队列满：拒绝/超时          ↑ 满：accept 但不处理          ↑ 满：排队（默认）→ 延迟上涨
```

> 顺序说明（易误读）：连接闸门的等待发生在 **Acceptor 执行 accept 之前**；正因为闸门满（Acceptor 被拦住），新连接才**堆积在内核队列**（acceptCount）里——图中把"OS 队列"画在左侧表示"新连接等待的位置"，不是"先过队列再过闸门"的流水线。

| 打满的资源 | 现象 | 观测线索 |
|---|---|---|
| Worker + 无界队列 | 响应越来越慢（不报错） | `busy ≈ maxThreads`（L09 lab） |
| 连接闸门 | 新连接"卡住"（建立后无响应） | `connections.current ≈ maxConnections` |
| acceptCount 队列 | 连接被拒/超时 | 客户端报 connection refused/timeout |
| **CLOSE_WAIT 堆积** | **隐性占用连接名额**（连接没关，闸门不释放） | `ss -tan state close-wait`（lab 脚本） |

> CLOSE_WAIT 与连接闸门的关系是"隐性事故点"：客户端断开后服务端未关连接 → 名额不释放 → 新连接被卡。

---

## 5. 怎么定（容量思路，机制级）

1. **先定线程**：IO 等待型（调下游/DB）→ 200~500；CPU 密集 → 接近核数；**看 `busy` 而不是猜**；
2. **再定连接**：`maxConnections` 可以远大于 `maxThreads`——keep-alive 空闲连接只占连接名额、不占线程；
3. **队列**：默认无界适合"绝不丢请求"但会积累延迟；要快速失败就设 `maxQueueSize`（拒绝）；
4. **超时**：三层对齐——**客户端超时 > LB/网关 idle 超时 > keepAliveTimeout**（否则出现"中间设备先关连接"的偶发重置）；
5. **压测与监控**：`busy`/`connections`/`acceptCount` 现象 + `ss` 状态分布。

---

## 6. lab 实测（可复跑）

| 实验 | 配置 | 结果 |
|---|---|---|
| 线程上限 | `threads.max=1` + 两个并发 1s 请求 | B 耗时 **1811ms**（被串行）；线程名同一个 exec |
| 连接闸门 | `max-connections=1`（裸 Socket、无 keep-alive） | B 等 A 的连接释放，耗时 **1707ms** |
| busy 指标 | 3 个慢请求在途 | `tomcat.threads.busy=4.0`（含指标请求自身）→ 结束 `1.0` |
| CLOSE_WAIT | 客户端 1s 断开、服务端 8s 慢请求 | 慢请求期间 **CLOSE-WAIT=1**，结束后归零 |

> 实验注意（lab 踩坑）：`max-connections` 实验必须禁 keep-alive（否则空闲连接占满唯一名额）；自定义定制 `maxKeepAliveRequests` 会被 Boot 属性覆盖——Boot 的属性驱动 customizer **后注册**，会覆盖先注册的同名自定义 customizer（lab 定制实验实测）。

---

## 7. 术语对照（本课汇总）

| 术语 | 中文 | 一句话 |
|---|---|---|
| maxThreads / minSpareThreads | 最大/最少线程 | 处理线程池 |
| maxQueueSize | 队列上限 | 满则拒绝；默认无界 |
| maxConnections | 连接上限 | 达限后 accept-but-not-process |
| acceptCount | 接受队列 | OS 层队列 |
| connectionTimeout | 连接超时 | 等请求行/请求体 |
| keepAliveTimeout | 长连接超时 | 等下一个请求 |
| maxKeepAliveRequests | 复用次数上限 | 默认 100 |
| asyncTimeout | 异步超时 | 默认 30s |
| CLOSE_WAIT | 半关闭等待 | 客户端关了、服务端没关 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. 三类参数各有哪些、默认值多少？**
> 参考答案：线程类 maxThreads=200 / minSpareThreads=10 / maxQueueSize 无界；连接类 maxConnections=8192 / acceptCount=100；超时类 connectionTimeout=60s（发行版 server.xml 20s）/ keepAliveTimeout 跟随它 / maxKeepAliveRequests=100 / asyncTimeout=30s。

**Q2. 线程池满的默认表现是什么？为什么？**
> 参考答案：表现为"变慢"而非报错——因为任务队列默认无界（`maxQueueSize=Integer.MAX_VALUE`），超出线程数的请求排队等待；设了上限才会拒绝。

**Q3. `connectionTimeout` 为什么有"两个默认值"？**
> 参考答案：API 默认 60000（60s）；但独立发行版 `conf/server.xml` 显式设成 20000（20s）；内嵌 Boot 不读 server.xml → 默认 60s（可配 `server.tomcat.connection-timeout`）。

**Q4. CLOSE_WAIT 和连接名额有什么关系？**
> 参考答案：CLOSE_WAIT 是"对端已关、本端未 close"的连接，它仍然占用 `maxConnections` 名额（计数在真正关闭时才减），堆积会导致新连接被卡（lab 实测）。

**Q5. 超时参数怎么对齐？**
> 参考答案：客户端超时 > LB/网关 idle 超时 > `keepAliveTimeout`（后者默认跟随 connectionTimeout）——否则会出现中间设备先关连接导致的偶发重置。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：`mvn -pl tomcat-lab test -Dtest=TuningTest`；`scripts/close-wait-demo.sh 8 1` 观察 CLOSE_WAIT。
- 下一步：**L10 Connector / Endpoint 主干**——Stage 3（源码主干）开始：按类名走一遍"端口→连接→线程"的调用链（不逐行、不背行号）。
