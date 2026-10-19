# TRACE-01 链路追踪全景

> 用途：立全景——Trace/Span 是什么、为什么需要、三条路线（自研/标准/APM）+ xhs 三件叠加现状
> 对应系列：**追踪专题 · 01**（理论篇第一篇）
> 口径：Micrometer Tracing **1.2.5**（Boot 3.2.5，bridge-brave）/ SkyWalking Agent 9.x / xhs 自研 traceId
> 素材：`tracing-lab`（[D1 Tracer/Span](../../tracing-lab/docs/D1-Tracer-Span生命周期.md)）· xhs-project（自研 trace 7 类 + SW agent/插件/脚本配置）
> 前置：MQ 最小模型（开篇已补）；后续：02 传播 → 03 上下文与采样 → 04 接入

---

## 0. 例子先行：一次 payment→order 慢调用

没追踪时长这样：payment 日志 `14:01:02.331 调用订单…`，order 日志 `14:01:02.410 收到…`——靠**时间戳 + orderId 人肉对**；order 背后还调了库存？MQ 那段（`RefundResultConsumer`）呢？三跳以上基本靠猜。

有追踪时长这样：一个 traceId（如 `4bf92f3577b34da6a3ce929d0e0e4736`）串起**两边所有日志**；span 树再告诉你——800ms 里 690ms 花在"order→库存"这一段。

一句话：**追踪 = 给一次跨进程请求发"身份证"，走到哪记到哪**。

---

## 1. 掌握卡（3 条）

1. **Trace = 一棵树，Span = 树上一个节点**：一次请求是一棵 Trace；其中"一段工作"（一次方法、一次 HTTP、一次 MQ 收发）是一个 Span。
2. **三条路线各管一段**：**自研**（业务透传，轻量）/ **标准 API**（代码埋点规范）/ **APM 平台**（自动埋点 + 后端）；**xhs 三件全都要了**（§4）。
3. **两个省钱机制先记住名字**：**采样**（全采太贵）+ **isNoop**（没采中的零代价）；细节 03。

> **先对齐几个词（从0）**：**进程 / 跨进程**——进程是跑起来的程序实例，跨进程就是"A 程序调 B 程序（走网络）"，不像调本地方法；**ThreadLocal**——同一线程内随处可取的"线程私房变量"，跨线程就丢（所以线程池传递要用 `MdcAwareExecutorService`，06 讲）；**MDC**——日志框架给每条线程配的"随身小本本"，模板里 `%X{traceId}` 取出来；**reporter**——span 攒完后"发往后端"的动作，目的地如 Zipkin（开源追踪后端）/ OTel Collector；**OAP**——SkyWalking 的后端（收 span、存、UI 查）；**Baggage**——跟着 trace 走的"业务行李"（如 userId），区别于技术性的 traceId（03 详）；**OTel**——OpenTelemetry，可观测开放标准；**字节码埋点**——agent 在 JVM 启动时改框架字节码自动加埋点，业务代码不动；**bridge-brave**——标准 API 到 Brave/Zipkin 模型的"翻译层"（D5 详）。

---

## 2. Trace / Span 是什么（D1）

- **关系**：Trace 是一次请求的全链路（一棵树，parent/child 串起来）；Span 是"单一工作单元"（D1 原话），既是"可定制的工作单元"（tag/event），又是"可控制的生命周期"（start/end）；
- **Span 三阶段**：`Builder`（配 parent/kind/name/tags）→ `start()` 后**活跃**（可 tag/event/error，`currentSpan()` 可查）→ **`end()` 上报 / `abandon()` 丢弃**（配了但没价值的容器 span，比如 D1 的 cache-lookup 例子）；
- **Kind 四种**（语义来自 OTel）：`SERVER`（被调）/ `CLIENT`（主调）/ `PRODUCER`（发消息）/ `CONSUMER`（收消息）——后两种因为"发送≠接收"才独立成类（MQ 最小模型已讲）；
- **命名**：`@SpanName` 注解与 `SpanNamer` SPI；**Tracer API** 是一组直接方法（创建/管理/查询/工厂四组，D1 详）；
- **isNoop**：采样没中 → 所有操作空转**零代价**；但 `context()` 仍有效——**传播链不断**（下游照样能续上）。

---

## 3. 为什么需要

- 调用链**跨进程**（网关→服务→服务）+ 有**异步**（MQ 发了就不等）→ 单机日志串不起来；
- **traceId 串日志 = 简版追踪**（xhs 自研在做的事：MDC 里带 traceId，按它搜日志）；
- **span 树 = 完整版**：每段耗时分解 + 树状展示，慢请求"慢在哪一段"一目了然。

---

## 4. 三条路线对比 + xhs 现状

| 路线 | 谁埋点 | 存哪 / 查什么 | 成本 |
|---|---|---|---|
| **自研 traceId** | 代码手动透传（header + ThreadLocal + MDC） | 日志检索（按 traceId 搜） | 零外部依赖；只解决"串起来"，不解决"耗时分解/可视化" |
| **标准 API**（Micrometer Tracing） | 代码按规范埋点（Tracer/Span/Baggage），实现可换（Brave/OTel） | reporter 送 Zipkin/Collector | 要写代码；跨系统可比 |
| **APM 平台**（SkyWalking） | **agent 字节码自动埋点**（框架层零代码：Feign/MVC/MQ/JDBC…） | OAP 后端 + UI | 要运维后端 |

**xhs 三件叠加**（全部核实过）：

| 件 | 证据 | 说明 |
|---|---|---|
| SW agent | `Dockerfile.template`（"可选"挂载）+ `release-service.sh`（默认自动探测，`SW_AGENT_DISABLED=1` 可关；忽略 actuator 等路径） | 框架层自动埋点 |
| logback 插件 | `apm-toolkit-logback-1.x`（payment/im/analytics/content/notification 五个服务 pom 里都有） | 把 traceId 写进 MDC（和 agent 配套） |
| 自研透传 | `TraceContextHolder` + Feign 六头 + `MqTraceHelper` + `MdcAwareExecutorService` | 业务语义：userId/灰度/AB/压测（APM 不直接管这些业务语义） |

> **分别适合**：只要"串起来"+业务透传 → 自研；要规范+以后可能换实现 → 标准 API；要可视化+零代码 → APM 平台。

---

## 5. 采样一句话

全采太贵（存储 + 性能）→ 入口做** head 采样**（采不采用请求一进来就定）；xhs 配的是 `sample_n_per_3_secs=50`（`restart-all-skywalking.sh`）；没采中的调用靠 `isNoop` 零代价不断链。细节 03。

> span 的旅程（一句话）：产生 → 采样决策 → reporter 发往后端 → UI/日志里查（§6）。

---

## 6. 与 metrics / log 的关系（三支柱一句）

- metrics 答"多少/多快"（Counter/Timer）；trace 答"慢在哪一段"；log 答"当时发生了什么"；
- **traceId 是三者的粘合剂**：MDC 里带上它，日志就能按 traceId 搜到；span 上也能挂业务 tag。

---

## 7. 边界与翻篇

- 本系列只到"**应用侧**"：agent 怎么挂、采样怎么配、日志怎么带、标准 API 怎么用——**SW 后端（OAP/存储/UI）运维不在本系列**；
- 不重复：Feign 透传细节（OpenFeign 04）；Sentinel 的 `Tracer.traceEntry`（L45；OpenFeign 06）。

---

## 8. Self-Test

1. Trace 和 Span 是什么关系？Span 的三个阶段？
2. traceId 串日志和 span 树各解决什么？差在哪？
3. 三条路线各适合什么？xhs 为什么三件全要？
4. 采样是为了解决什么？没采中的调用会怎样（isNoop）？

---

## 9. 相关与衔接

- **02 传播**：W3C/B3 与 header 传递（跨进程不断链的机制）；
- **03 上下文与采样**：TraceContext、Baggage、Sampler；
- **06 自研对照**：xhs 七个类的逐个拆解；
- [`tracing-lab`](../../tracing-lab/HANDOVER.md)：[D1](../../tracing-lab/docs/D1-Tracer-Span生命周期.md)；
- OpenFeign 04（Feign 六头透传）/ L45（Sentinel 熔断统计）。
