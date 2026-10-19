---
title: "02-传播：W3C/B3 与跨进程不断链"
series: "Tracing 链路追踪系列"
previous: "01-what-is-tracing.md"
next: "03-上下文与采样.md（待写）"
lab_refs: "tracing-lab/docs/D2-Propagation-W3C-B3传播.md"
source_refs:
  - "Boot 3.2.5 TracingProperties.java:175,180（produce/consume 默认）"
  - "micrometer-tracing-1.2.5 Propagator.java:46,66,78,93,105（NOOP/三方法/Setter）"
  - "micrometer-tracing-1.2.5 W3CPropagation.java:56（FIELDS 两头）"
---

# 02-传播：W3C/B3 与跨进程不断链

## §0 它干了什么（例子先行）

还是 01 那条订单链路：网关 → 订单 → 支付。假设没有任何传播机制：

```
无传播（每个服务各自生成 traceId）:
  网关: [traceId=aaa] 收到请求 → 调订单
  订单: [traceId=bbb] 收到请求 → 调支付    ← 断链！aaa 和 bbb 对不上
  支付: [traceId=ccc] 收到请求

有传播（traceId 跟着请求走）:
  网关: [traceId=TTT] 收到请求 → 把 TTT 写进请求头 → 调订单
  订单: [traceId=TTT] 从请求头读到 TTT → 写进请求头 → 调支付
  支付: [traceId=TTT] 从请求头读到 TTT
```

- **它干了什么**：发送方把 trace 上下文**写进请求头**，接收方从请求头**读出来**，全链共用一个 traceId。
- **输入/输出**：输入=上游请求里带来的头；输出=下游收到的同一个 traceId + 本服务新建的 span 挂到它下面。
- **xhs 对照**：xhs 自研透传做的也是这件事，只是头的名字不同——标准叫 `traceparent`，xhs 叫 `X-Trace-Id`。

## §1 掌握卡（3 条）

1. **传播 = 跨进程不断链**：发送方 `inject`（写头）+ 接收方 `extract`（读头）；载体在 HTTP 场景是 header，在 MQ 场景是消息属性。
2. **两套协议**：W3C（`traceparent` 精确四段格式 + `tracestate` 厂商扩展）与 B3（单头/多头两种写法）；Boot 默认**发出用 W3C、接收全格式通吃**。
3. **自研对照**：xhs 单头 `X-Trace-Id` 只传 traceId；标准多传 parentId + 采样位——多传的两样正是"还原调用树"和"采样决策跨服务一致"所需的。

## §2 问题：传播层解决的是"头里放什么"

一句话：传播层解决的是**"traceId 放在哪些 HTTP header 里，下游如何读取"**的协议问题。它不管 span 怎么建（那是 01 的事），也不管上下文存哪（那是 03 的事）。

两套主流方案（`lab D2 §一`，Boot 3.2.5 口径）：

| 协议 | 格式 | HTTP Headers | 来源 |
|---|---|---|---|
| **W3C Trace Context** | `traceparent` + `tracestate` | 2 个 header | W3C 标准（2020） |
| **B3** | `b3: traceid-spanid-sampled[-parentspanid]` | 1 个 header | Zipkin（2015） |
| **B3_MULTI** | `x-b3-traceid/x-b3-spanid/x-b3-parentspanid/x-b3-sampled` | 3~4 个 header | Zipkin 多 header 格式 |

## §3 W3C：两个头的精确格式

W3C 传播只用两个 header（`W3CPropagation.java:56`）：

```java
private static final List<String> FIELDS =
    Collections.unmodifiableList(Arrays.asList(TRACEPARENT, TRACESTATE));
// TRACEPARENT = "traceparent", TRACESTATE = "tracestate"
```

### 3.1 traceparent：四段，格式错就被丢弃

```
traceparent: 00-{trace-id}-{parent-id}-{trace-flags}
              │    │           │           │
              │    │           │           └─ 01（采样）/ 00（不采样）
              │    │           └─ 16 hex 字符 = 64-bit 上级 span id
              │    └─ 32 hex 字符 = 128-bit trace id
              └─ version（当前为 00）
```

真实例子：

```
traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
```

读法：版本 00；traceId 是 `0af7...19c`；上级 span 是 `b7ad...331`；`01` 表示这条链路要采样。格式错一位（少一段、多一截、非 hex 字符），下游实现一般直接丢弃这个头、重新起一条 trace——效果等同断链。所以"发出去之前保证四段齐"，是 inject 的基本责任。

**为什么 traceId 是 128-bit？** Brave 从设计之初就是 128-bit（`traceIdHigh` + `traceIdLow`），但 Zipkin 早期只支持 64-bit。`traceparent` 保留 128-bit 完整空间，老的 64-bit traceId 高位补 0 即可兼容——新老系统互通不断链。

### 3.2 tracestate：厂商扩展，有长度坑

```
tracestate: vendor1=opaqueValue1,vendor2=opaqueValue2
```

每个厂商可追加自己的键值对，逗号分隔。**坑**：它不是自由字段——必须是 `vendor=value` 格式，总长度不能超过 512 字符（部分实现只给 256）。超限会被截断或丢弃，所以别往里面塞大东西。

## §4 B3：Zipkin 的遗产，两种写法

B3 得名于 BigBrotherBird（Zipkin 吉祥物），支持单头和多头：

| 格式 | HTTP Headers | 示例 |
|------|------------|------|
| B3（单 header） | `b3` | `b3: {traceId}-{spanId}-{sampledFlag}-{parentSpanId}` |
| B3_MULTI（3~4 header） | `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-ParentSpanId`, `X-B3-Sampled` | 4 个独立 header |

B3 vs W3C（`lab D2 §三`）：

| 维度 | B3 | W3C |
|------|------|------|
| traceId 长度 | 128-bit（原 B3 为 64-bit，Brave/Zipkin v2 扩展） | 128-bit 标准 |
| 厂商扩展 | 无 | `tracestate` 支持多厂商 |
| Header 数量 | 1~4 个 | 2 个 |
| 与 Zipkin 兼容 | 原生 | 需转换 |

### 4.1 Boot 默认：发出用 W3C，接收全都要

```java
// TracingProperties.java:175（对应 management.tracing.propagation.produce）
private List<PropagationType> produce = List.of(PropagationType.W3C);

// TracingProperties.java:180（对应 management.tracing.propagation.consume）
private List<PropagationType> consume = List.of(PropagationType.values());
```

设计意图：**发出时用标准**（W3C 是正道）；**接收时全兼容**（老服务发 B3 照样能解析）。你的新服务用 W3C 发送，但收到 B3 格式的老请求也不会断链。

## §5 Propagator 接口：inject + extract + fields

```java
// Propagator.java:66/78/93
List<String> fields();  // 传播用的 header key，如 ["traceparent","tracestate"]

<C> void inject(TraceContext context, @Nullable C carrier, Setter<C> setter);
// 注入：把 trace 上下文写进载体（HTTP 请求头）

<C> Span.Builder extract(C carrier, Getter<C> getter);
// 提取：从载体读出 trace 上下文
```

配套：`Propagator.NOOP:46`（空 fields + 空 inject/extract，用于"本地 DB 操作不需要传播"的场景）；`Setter<C>:105` 是无状态函数式接口（`set(carrier, key, value)`），`Getter` 同理。

**为什么 `extract()` 返回 `Span.Builder` 而不是 `Span`？** 回顾 01：Span 一旦 `start()` 核心属性就不可改。extract 只拿到了 traceId/parentId/采样位，还没有 span name 和 kind——返回 Builder 让调用者在 `start()` 前补上这两样。

**Feign 对应关系**（呼应 01 §5）：OpenFeign 04 的请求拦截器 = **inject**（发出前写头）；网关/下游服务读头 = **extract**（收到后读头）。carrier 是泛型：HTTP 请求能做载体，MQ 消息也能做——§7 展开。

## §6 自研对照：xhs 单头 vs 标准（对照理解，非源码实锤）

| 维度 | W3C 标准 | xhs 自研 |
|---|---|---|
| 头数量 | 2 个（traceparent + tracestate） | 1 个（`X-Trace-Id`）+ 另 5 个业务头（03 讲） |
| 传 traceId | 是 | 是 |
| 传 parentId | 是（还原调用树） | 否 |
| 传采样位 | 是（采样决策跨服务一致） | 否（自研侧如何采样，06 揭晓） |

少传的两样，各自对应一个后果：

1. **没有 parentId**：跨服务的父子关系拼不起来——只能按"同一 traceId + 时间"看调用列表，画不出真正的调用树。
2. **没有采样位**：采样决策传不到下游——上游决定采，下游可能不采，一条链路采一半。

xhs 侧用日志聚合 + 自研规则补（具体规则 06 揭晓）。够不够用？对"查一次请求的完整日志"够用；对"可视化调用树 / 全链采样一致性"不够——这正是 06 要细看自研 7 类的原因。

## §7 MQ：同一套思想，换个载体

HTTP 用 header 做 carrier，MQ 用**消息属性**做 carrier——思路是：`inject` 在发送前把 traceId 写进消息属性，`extract` 在消费时读出来。接口思想不变，只是 `Getter/Setter` 的实现从"读写 HTTP 头"换成"读写消息属性"。xhs 的 `MqTraceHelper` 即此路数，06 详讲。

## §8 边界（这里一句话翻篇）

- `W3CPropagation.injector()` / `extractor()` 的内部实现（hex 编解码、B3 兼容分支）→ `lab D2 §五/§六`，用时再查。
- B3 单头解析规则、Zipkin v1 64-bit 兼容细节 → 翻篇。
- Baggage（那 5 个业务头像什么）→ 03；自研 6 头实现 → 06。

## §9 Self-Test（4 题）

- Q1：`traceparent` 四段各是什么？`flags=01` 与 `00` 的区别？
- Q2：为什么 Boot 默认 produce=W3C 但 consume=全部？（从"新老互通"答）
- Q3：`extract()` 为什么返回 `Span.Builder` 而不是 `Span`？
- Q4：xhs 单头相对 W3C 少传了哪两样？各导致什么后果？

## §10 衔接

- 上篇 01 回答"trace/span 是什么"；本篇回答"traceId 如何跨进程不断链"。
- 下篇 03（上下文与采样）：trace 上下文在进程内存在哪（`TraceContextHolder`）、Baggage 本地 vs 远程、采样决策怎么做、span 的完整旅程。
