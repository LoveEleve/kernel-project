# Lesson 12: Context / Sinks / 调试 — 工程化三件套

> 课程索引：`../gateway-学习清单.md` → Stage 3 · Lesson 12（**Stage 3 收官课**）
> 版本口径：Reactor **3.6.5**（SCG 4.1.5 依赖）
> 前置：L09（信号/惰性）· L10（算子）· L11（调度/线程模型）
> **提问方式**：直接说编号，如「12.2 没懂」

---

## 12.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 | 在哪节 |
|---|---|---|---|
| **①** | **`Context` = 响应式上下文** | **跨链传数据**（替代"想用 ThreadLocal"）；`contextWrite` 写（**从订阅点向上**）、`deferContextual` 读 | 12.2 |
| **②** | **`Context` vs `attributes` 的分工** | Context 是 **Reactor 链级**（跟着订阅走）；`exchange.getAttributes()` 是 **请求级**（跟着请求走） | 12.2 |
| **③** | **`Sinks` = 手工往流里推数据** | 把**回调式 API**（监听器/事件）变成 `Flux`/`Mono` | 12.3 |
| ＋ | **调试三件套** | `log()`（看信号流）/ `checkpoint("说明")`（加路标）/ `Hooks.onOperatorDebug()`（全局，开销大） | 12.4 |

---

## 12.1 例子先行：为什么需要 `Context`

**场景**：你想在过滤器里放一个 `traceId`，让**下游任意算子**都能拿到（记日志、带进下游请求头）。

**第一反应**：用 `ThreadLocal`？
❌ **不行** —— 链路可能被 `publishOn`/`subscribeOn` 切到别的线程（L11），ThreadLocal 就丢了；而且 EventLoop 线程会被复用，**数据会串**。

**Reactor 的答案**：`Context` —— 它**不绑线程，绑订阅**：从写入点开始，沿链向上传递，**跨线程也不丢**。

```java
Mono.deferContextual(ctx -> {                       // ① 读（在链的任意位置）
            String traceId = ctx.get("traceId");
            return Mono.just("处理 traceId=" + traceId);
        })
        .contextWrite(Context.of("traceId", "T-123"))   // ② 写（写在"链的底部"）
        .subscribe(v -> log(v));
```

**规则一句话**：**`contextWrite` 写在下面（靠近订阅），`deferContextual` 在上面读 —— Context 从订阅点向上传播。**

---

## 12.2 要掌握的 ①：`Context` 精确语义

### 写 / 读

| 动作 | API | 说明 |
|---|---|---|
| **写** | `.contextWrite(Context.of(k, v))` | 放一个键值对 |
| | `.contextWrite(c -> c.put(k, v))` | 在"下游传来的 Context"基础上叠加 |
| **读** | `Mono.deferContextual(ctx -> ...)` | 在算子链中读（`ctx.get(k)` / `getOrDefault`） |

### 三条精确规则（实测见 `l12/Ex01_Context`）

| # | 规则 | 说明 |
|---|---|---|
| 1 | **Context 不可变** | 每次 `contextWrite` 产生**新的** Context，原对象不变 |
| 2 | **传播方向：从订阅点向上（bottom-up）** | 越靠近 `subscribe()` 的 `contextWrite` **越先**生效；上层算子看到的是**累积后**的 Context |
| 3 | **跟订阅走，不跟线程走** | 同一条链**每次订阅**可以写不同的 Context；换线程也不丢 |

### ⚠️ 与 `exchange.getAttributes()` 的分工（★ 面试点）

| | **Reactor `Context`** | **`exchange.getAttributes()`** |
|---|---|---|
| 归属 | **Reactor 链级**（跟着**订阅**走） | **请求级**（跟着**请求**走） |
| 生命周期 | 一次订阅 | 一次请求（全链路，含转发） |
| 读写位置 | 算子链内（`contextWrite` / `deferContextual`） | 过滤器内（`getAttributes().put/get`） |
| 谁在用 | Reactor/Gateway 的**观测链路**（Micrometer tracing） | **几乎所有的 Gateway 扩展点** |

**Gateway 的真实做法**（源码实证）—— **把 Context 桥接到 attribute**：

```java
// RoutePredicateHandlerMapping:88-89（匹配阶段）
return Mono.deferContextual(contextView -> {
    exchange.getAttributes().put(GATEWAY_REACTOR_CONTEXT_ATTR, contextView);   // ★ Context → attribute
    ...
});

// ObservedRequestHttpHeadersFilter:94（转发阶段，另一个位置读回来）
ContextView contextView = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REACTOR_CONTEXT_ATTR);
```

> **为什么这么桥接**：Context 只在**响应式链**里可见，而 Gateway 的扩展点（各种 Filter）**手里只有 `exchange`** ——
> 所以 Gateway 在匹配阶段把 Context 存进 attribute，**让后续任意过滤器都能拿到观测上下文**（这是 L11 线程模型 + 本课 Context 的综合应用）。

---

## 12.3 要掌握的 ②：`Sinks` —— 把回调式 API 变成流

**问题**：很多第三方库是**回调式**的（`onMessage(...)` / `addListener(...)`），怎么接到 Reactor 链上？

**答案**：`Sinks` —— 一个"**你手工往里推数据**"的 Publisher：

```java
// ① 单个值：Sinks.one()
Sinks.One<String> one = Sinks.one();
Mono<String> mono = one.asMono();              // 暴露给下游
one.tryEmitValue("回调结果");                    // 回调里推数据

// ② 多个值：Sinks.many()
Sinks.Many<String> many = Sinks.many().multicast().onBackpressureBuffer();
Flux<String> flux = many.asFlux();
many.tryEmitNext("event-1");                   // 推一条
many.tryEmitComplete();                        // 结束

// ③ 模拟"回调式 SDK"
class MyListener {                              // 假设这是第三方 SDK 的监听器接口
    void onEvent(String e) { many.tryEmitNext(e); }
}
```

| API | 语义 |
|---|---|
| `Sinks.one()` / `Sinks.empty()` | 0..1（`asMono()`）；`empty` 只完成不给值（对应 `Mono<Void>`） |
| `Sinks.many().multicast().onBackpressureBuffer()` | 0..N（`asFlux()`），**热流**：订阅**之后**的事件才收得到 |
| `tryEmitNext(v)` / `tryEmitValue(v)` | **返回 `EmitResult`**（`OK` / `FAIL_ZERO_SUBSCRIBER` / `FAIL_OVERFLOW` / `FAIL_CANCELLED`） |

> **⚠️ `tryEmitXxx` 返回 `EmitResult`（不抛异常）** —— 必须检查返回值，否则**丢事件你不知道**。
> （对比：`emitNext(v, EmitFailureHandler)` 可以带失败处理器）

**Gateway 里用没用到**：**没用**（grep 源码零引用）。但**流式场景**（SSE 转发、事件聚合）会用 —— 属于"知道怎么用"的知识。

---

## 12.4 调试三件套（响应式堆栈为什么难读）

**为什么难读**：Reactor 的调用栈是**算子组装栈**（`map` 套 `flatMap` 套 `subscribe`…），异常抛出的位置和"你写的那一行"隔了十万八千里。

| 工具 | 作用 | 开销 | 用法 |
|---|---|---|---|
| **`log()`** | 打印**信号流**（onSubscribe/request/onNext/onComplete/onError + 线程） | 小 | `flux.log()`（或 `log("myTag")`） |
| **`checkpoint("说明")`** | 在**异常堆栈**里留下"路标"（assembly traceback） | 小 | `.checkpoint("【订单查询之后】")` |
| **`Hooks.onOperatorDebug()`** | **全局**给所有算子加组装栈（重） | **大（生产禁用）** | 启动时 `Hooks.onOperatorDebug()`；排查完 `Hooks.resetOnOperatorDebug()` |

**实测对比**（`l12/Ex04_HooksDebug`，同一段会出错的链）：

| | 不开 Hooks | 开启 `Hooks.onOperatorDebug()` |
|---|---|---|
| 异常里的业务线索 | 只有"运行位置"（lambda 帧） | ✅ **Assembly trace**（链的**组装位置**，含业务行号）<br>`Assembly trace from producer [FluxMapFuseable] :`<br>`...Ex04_HooksDebug.chain(Ex04_HooksDebug.java:28)`<br>`*__Flux.map ⇢ at ...chain(Ex04_HooksDebug.java:28)` |
| 开销 | 无 | **大**（每算子抓组装栈），**生产禁用** |

> **选择顺序**：`checkpoint("业务语义")`（精准、便宜）→ `log()`（看信号节奏）→ `Hooks.onOperatorDebug()`（全局兜底，只本地开）。
> 更轻量的替代：`ReactorDebugAgent.init()`（Java agent，按需开启）。

```java
// ① log：看信号 + 线程
Flux.range(1, 3).map(i -> i * 2).log("【我的链】").subscribe(...);

// ② checkpoint：异常时能看到路标
Flux.range(1, 3)
    .map(i -> { if (i == 2) throw new IllegalStateException("boom"); return i; })
    .checkpoint("【二分之一的 map 之后】")
    .subscribe(...);   // 异常信息里会出现这个说明
```

> **实践建议**：优先 `checkpoint("业务语义说明")`（精准、便宜）；`log()` 用于看信号节奏；`Hooks.onOperatorDebug()` 只在**本地复现**时开。

---

## 12.5 动手实验（`reactor-lab` → `l12/`）

| 实验 | 文件 | 看什么 |
|---|---|---|
| **Ex01** | `l12/Ex01_Context.java` | 写/读、**不可变**、**从订阅点向上**、跨线程不丢、与 attributes 的分工 |
| **Ex02** | `l12/Ex02_Sinks.java` | `Sinks.one` / `Sinks.many`（热流语义）+ **`EmitResult` 返回值** + 回调式 API 接流 |
| **Ex03** | `l12/Ex03_Debug.java` | `log()` 的信号输出 / `checkpoint()` 的路标（异常里能看到） |
| **Ex04** | `l12/Ex04_HooksDebug.java` | `Hooks.onOperatorDebug()` 三态对比：不开 → 开（**Assembly trace 出现业务行号**）→ 关 |

```bash
cd /data/workspace/source-code/xhs-demo/reactor-lab
./scripts/run-one.sh l12.Ex01_Context
mvn -pl reactor-lab test        # 断言
```

---

## 12.6 掌握边界

| 内容 | 判断 |
|---|---|
| Context 是什么 + 写/读 API | ✅ **必须记** |
| **Context vs `exchange.getAttributes()` 的分工** | ✅ **必须记**（面试点 + Gateway 桥接源码） |
| Context 三条规则（不可变 / bottom-up / 跟订阅走） | ✅ 记 |
| `Sinks` 的用途（回调 → 流）+ `EmitResult` | ✅ 记 |
| 调试三件套的**选择**（checkpoint 优先） | ✅ 记 |
| `Context` 内部实现（`ContextN` 持久化数据结构） | ⏭️ 不学 |
| `Sinks` 各种 multicast/unicast/replay 变体 | ⏭️ 用时查 |
| `Hooks` 的其它钩子（`onNextDropped` 等） | ⏭️ 用时查 |

---

## 12.7 Self-Test

1. 为什么链路上要传 `traceId` 不能用 `ThreadLocal`？
2. `contextWrite` 写在哪、`deferContextual` 读在哪？Context 往哪个方向传播？
3. `Reactor Context` 和 `exchange.getAttributes()` 的分工是什么？Gateway 怎么把两者桥接起来？
4. `Sinks` 解决什么问题？`tryEmitNext` 的返回值为什么必须检查？
5. 响应式堆栈难读时，`checkpoint` 和 `Hooks.onOperatorDebug` 怎么选？

### 12.7.1 参考答案

<details open>

**1. 为什么不能用 ThreadLocal**
链路可能被 `publishOn`/`subscribeOn` **切到别的线程**（L11）→ ThreadLocal 丢值；
且 EventLoop 线程**会被复用** → ThreadLocal 数据会**串到别的请求**。

**2. 写/读位置 + 传播方向**
- `contextWrite` 写在**链的底部**（靠近 `subscribe()`）；`deferContextual` 在**链中**任意位置读。
- 传播方向：**从订阅点向上（bottom-up）** —— 越靠近订阅的 `contextWrite` 越先生效，上层看到**累积后**的 Context。

**3. Context vs attributes**
- Context：**Reactor 链级**（跟订阅走）；`attributes`：**请求级**（跟请求走）。
- Gateway 的桥接：`RoutePredicateHandlerMapping:88` 用 `deferContextual` 拿到 `ContextView` → 存进 `GATEWAY_REACTOR_CONTEXT_ATTR`；
  之后 `ObservedRequestHttpHeadersFilter:94` 从 attribute 读回来 → **让只有 exchange 的过滤器也能拿到观测上下文**。

**4. Sinks**
把**回调式 API**（监听器 / 事件）变成 `Flux`/`Mono`。
`tryEmitNext` 返回 `EmitResult`（`OK`/`FAIL_ZERO_SUBSCRIBER`/`FAIL_OVERFLOW`/`FAIL_CANCELLED`）——**不抛异常**，不检查返回值就会**静默丢事件**。

**5. 调试怎么选**
优先 **`checkpoint("业务语义")`**（精准、开销小，异常堆栈里直接看到路标）；
`log()` 看**信号节奏 + 线程**；`Hooks.onOperatorDebug()` 是**全局**的（开销大，**生产禁用**），只在本地复现时开。

</details>

---

## 12.8 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「12.2 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
