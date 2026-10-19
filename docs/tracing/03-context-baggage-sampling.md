---
title: "03-上下文与采样：Baggage 与 span 旅程"
series: "Tracing 链路追踪系列"
previous: "02-propagation-w3c-b3.md"
next: "04-Micrometer-Tracing接入.md（待写）"
lab_refs:
  - "tracing-lab/docs/D3-TraceContext-CurrentTraceContext.md"
  - "tracing-lab/docs/D6-Baggage-远程本地.md"
  - "tracing-lab/docs/D7-Sampler-SpanHandler.md"
  - "tracing-lab/docs/D1-Tracer-Span生命周期.md（§3.3 isNoop）"
source_refs:
  - "Boot 3.2.5 TracingProperties.java:87-（Baggage 只有 enabled/correlation/remote-fields）"
---

# 03-上下文与采样：Baggage 与 span 旅程

## §0 它干了什么（例子先行）

订单服务收到网关的请求，头里带着 `traceparent: 00-TTT-...-01` 和租户信息。接下来发生四件事：

```
1. 建 span：从头里读出 TTT，新建 span（"收到订单请求"），挂到 TTT 下面
2. 存上下文：把"当前 trace 是 TTT"放进 ThreadLocal——之后任何业务代码
   不用传参，直接就能问"我现在在哪个 trace 里"
3. 带行李：租户 acme-corp 跟着请求走，每行日志自动带上，但不进 span 存储
4. 定生死：采样器看一眼——这 10% 留下上报，那 90% 记个 traceId 就扔
```

- **输入**：上游请求头（trace 上下文 + 租户）；**输出**：本服务 span 上报 + 日志带 TTT/租户 + 下游收到的头。
- **xhs 对照**：`TraceContextHolder` = 第 2 步的自研版；5 个业务头 = 第 3 步的自研版；线程池透传 = 第 2 步跨线程的自研版（都是 06 的内容）。

## §1 掌握卡（4 条）

1. **上下文 = 进程内"当前 trace 是谁"**：`TraceContext`（身份证：4 字段，不可变）+ `CurrentTraceContext`（ThreadLocal 栈，scope 后进先出；跨线程靠 `wrap`）。
2. **Baggage = 跟着传、但不进 span 存储的行李**：远程（传给下游 + 进日志）vs 本地（只进日志不传播）；Boot 3.2.5 只支持配远程。
3. **采样 = trace 创建时一次决定、全链一致**：概率采样（BitSet 保证精确）/限流采样（token bucket 保底）；没采中的 span 是 noop——零代价，但 traceId 照传不断链。
4. **span 旅程一句话**：建（nextSpan）→ 起（start）→ 挂（withSpan 进栈）→ 标（tag/event）→ 收（end）→ 报（SpanHandler → reporter）。

## §2 TraceContext：span 的"身份证"

```java
// TraceContext.java:26（接口），4 个核心方法 :57-76
public interface TraceContext {
    String traceId();              // 全局唯一 trace ID（128-bit hex）
    @Nullable String parentId();   // 父 span ID（根 span 为 null）
    String spanId();               // 当前 span ID（64-bit hex）
    @Nullable Boolean sampled();   // 采样决策（true/false/null）
}
```

两个 null 语义（`lab D3 §一`）：

- **`sampled()` 为 null = 延迟决策**：`traceparent` flag 是 `00`（不采样），但 tracestate 里某厂商要求采样——先不拍板，把决定权留给下游（05 节的 defer 机制就靠它）。
- **`parentId()` 为 null = 无父**：根 span 没有父。注意和"空字符串"区分——null 是明确的"无父"（创建新 trace），空是"有父但未知"（尝试关联，可能失败）。Brave 对两者处理完全不同。

**为什么不可变？** 并发安全——多线程读同一个 `TraceContext` 不用同步。Brave 采取"一次构建、永不修改"，所有校验（hex 长度等）在 `build()` 时一次做完。

## §3 CurrentTraceContext：ThreadLocal 上的 scope 栈

ThreadLocal = 每个线程一份、互不可见的变量。"当前 trace 是谁"天然就该线程私有——A 线程的请求和 B 线程的请求不能串。所以上下文存在 ThreadLocal 里，结构是个 scope 栈：

```java
// CurrentTraceContext.java:95,103
CurrentTraceContext.Scope newScope(@Nullable TraceContext context);
CurrentTraceContext.Scope maybeScope(@Nullable TraceContext context);
```

- `newScope`：**总是**压一层新 scope，哪怕和当前相同。
- `maybeScope`：和当前相同就返回 NOOP（不压栈）——防御性编程，避免多个 handler 重复设置造成无谓嵌套。

Scope 关闭是**后进先出**：内层 span 结束 pop 出栈，自动恢复外层。`withSpan` 把 span 压进栈，返回的 `SpanInScope` 就是个"出栈凭证"——close 即 pop：

```java
Span span1 = tracer.nextSpan().name("outer").start();
try (SpanInScope s1 = tracer.withSpan(span1)) {
    Span span2 = tracer.nextSpan().name("inner").start();
    try (SpanInScope s2 = tracer.withSpan(span2)) {
        // scope 栈: [outer, inner]
    } // s2.close() → 栈变为 [outer]，current = outer
    // 在 outer scope 中继续操作
} // s1.close() → 栈变为 []，current = null
```

反例：如果 close 直接清空而不是 pop，s2.close() 后 current 就丢失了——外层代码无法继续在 outer scope 中操作。

### 3.1 跨线程：wrap 是标准答案

```java
// CurrentTraceContext.java:111-124
<C> Callable<C> wrap(Callable<C> task);
Runnable wrap(Runnable task);
Executor wrap(Executor delegate);      // 所有 submit 的任务自动继承 trace
ExecutorService wrap(ExecutorService delegate);
```

原理：提交时**捕获**当前线程的上下文，执行时在新线程里**重建** scope。伪代码就三行：`context()` 抓一下 → 新线程里 `newScope(抓到的)` → 跑完 close。

**生产陷阱**（`lab D3 §2.2`）：`wrap(executor)` 让*所有*经它的任务都继承 trace——后台定时任务这种"不该有 trace"的也会被强行关联（"过度链接"）。xhs 的线程池透传要解决的正是这类问题，06 详讲。

## §4 Baggage：trace 的"行李箱"

traceId 只告诉你"请求经过了哪些服务"，不告诉你"这是谁的请求、什么业务场景"。这些信息不能塞 Span tag——tag 进存储索引，塞多了会爆炸。Baggage 就是**随请求传播、但不进 span 存储**的键值对：

```java
// 服务 A：
baggageManager.createBaggageInScope("tenant-id", "acme-corp");

// 服务 B（收到后）：
String tenantId = baggageManager.getBaggage("tenant-id").get(); // → "acme-corp"
```

它走 `baggage: tenant-id=acme-corp` 头传播，自动进 MDC（MDC = 日志框架给每个线程配的"备注栏"，打日志时自动带上，05 详讲；日志里 `%X{tenant-id}` 可搜），但 Zipkin 里搜不到它——这正是"行李"和"货物（tag）"的区别。

### 4.1 三类 Baggage：远程 vs 本地 vs Tag

| 类型 | 传播到下游 | MDC 输出 | Span Tag 输出 | Boot 3.2.5 配置 |
|------|:--:|:--:|:--:|------|
| **远程** | ✅ | ✅ | ❌（默认） | `management.tracing.baggage.remote-fields` ✅ |
| **本地** | ❌ | ✅ | ❌（默认） | 无此属性（1.2.5 靠手构造 `W3CPropagation(..., localFields)`） |
| **Tag** | ✅ | ✅ | ✅ | 无此属性（靠 `BraveBaggageManager(tagFields)` 构造参数） |

**版本注记**：Boot 3.2.5 的 Baggage 配置只有 `enabled` / `remote-fields` / `correlation`（源码已核）——`local-fields`/`tag-fields` 是更新版本才有的。lab 的 yml 已按 3.2.5 改为 `remote-fields`。

为什么分远程和本地？`tenant-id` 下游也要知道（远程）；`session-id` 只本服务日志需要（本地——还省 header 大小、少泄露）。实际发出的头：

```
traceparent: 00-{traceId}-{spanId}-01
baggage: tenant-id=acme-corp           ← 仅 remote-fields，本地字段在 inject 时被过滤
```

**xhs 对照**：那 5 个业务头干的就是"远程 Baggage"的活——只不过是自研头名、自研读写（06 揭晓实现）。

## §5 采样：trace 创建时定生死

采样发生在 **trace 创建时**，且全链必须一致——根 span 被采，子孙全采；根没采，全不采。反过来（子采而根不采），可视化工具拼不出完整调用链。

两种常用采样器（`lab D7 §一`）：

1. **概率采样**（`ProbabilityBasedSampler`）：预生成 100 位 BitSet，恰好 N 位为 1——每 100 个请求精确采中 N 个。不用 `random()` 是为了**统计精确**（算 P99 延迟这类指标，全靠采样率准）+ O(1) 快约 10 倍。**默认用哪个？** 注意：Boot 默认实际用 Brave 原生 `Sampler.create(probability)`（`BraveAutoConfiguration.java:147`，按 traceId hash 决策，支持任意精度），而不是这个 BitSet 包装类——后者是 Micrometer 提供的实现（`Supplier` 可动态改概率）。思想一样（概率定生死），默认实现是 hash 版（07 讲采样配置时展开）。
2. **限流采样**（`RateLimitingSampler`）：token bucket，每秒最多 N 个 trace。QPS 波动大时比概率更安全——低峰不断档、高峰不压垮 Zipkin。

**defer（延迟决策）**：§2 那个 `sampled()=null` 在这里落地——`traceparent` 说 00，但下游发现 500 错误"这条值得留"，可以**后续强制采样**。错误信息价值高，值得破例。

## §6 isNoop：没采中的 span，零代价

```java
// Span.java:110
boolean isNoop();
```

`management.tracing.sampling.probability=0` 时所有 Span 都是 noop——`tag()`/`event()`/`end()` 全是空操作。业务代码在干重活前先问一句：

```java
if (!span.isNoop()) {
    span.tag("user.id", lookupUserId());  // lookupUserId 可能要查 DB，不采就别查
}
```

**陷阱**（`lab D1 §3.3`）：`isNoop()==true` 的 span，`context()` 照样返回有效 `TraceContext`——否则 traceId 传不下去，下游直接断链。零代价的是"存储和上报"，不是"传播"。

## §7 span 的完整旅程（串起 01+02+03）

```
收到请求 → extract 读头（02）
        → tracer.nextSpan() 建 span（01）
        → name/kind 补齐 → start（01/02 §5）
        → 采样器定生死（本篇 §5；死的变 noop，§6）
        → withSpan 压栈，业务代码从 ThreadLocal 拿上下文（本篇 §3）
        → tag/event 标记，Baggage 跟着走（本篇 §4）
        → end 收尾 → SpanHandler.end 编排：过滤 → 变异 → reporter 上报（D7 §二）
        → inject 写头调下游（02）
```

01 给了"是什么"，02 给了"跨进程"，本篇给了"进程内 + 生死"。理论三块到此收口——04 开始讲"在 Boot 里怎么接上"。

## §8 边界（这里一句话翻篇）

- `ThreadLocalSpan` 栈式工具、`Span.Builder` 15 个方法全表 → `lab D1/D3`，用时再查。
- `CompositeSpanHandler.end()` 编排链（过滤→变异→上报）、`SpanFilter`、`SpanReporter`、无 reporter 时的安全回退 → 04（接入）/07（可观测）展开。
- `TraceContext.Builder` 参数校验细节 → 翻篇。

## §9 Self-Test（4 题）

- Q1：`sampled()` 为 null 和 false 有什么区别？各举一个场景。
- Q2：`newScope` 和 `maybeScope` 的区别？后者防的是什么？
- Q3：线程池任务拿不到 trace，标准解法是哪一个方法？它"捕获"和"重建"分别发生在哪两个时刻？
- Q4：租户信息为什么放 Baggage 而不放 Span tag？远程和本地 Baggage 的区别是哪两个维度？

## §10 衔接

- 理论线收口：01（是什么）→ 02（跨进程不断链）→ 03（进程内上下文 + 生死）。
- 下篇 04（接入）：`Tracer` Bean 哪来的（bridge-brave）、自动配置做了什么、`SpanHandler` 怎么导出到 Zipkin/SkyWalking。
