# Lesson 09: Reactive Streams 与 Reactor 编程模型

> 课程索引：`../gateway-学习清单.md` → Stage 3 · Lesson 09（**Stage 3 第一课，从 0 讲起**）
> 前置：`L00-prerequisites.md` → 0.3.1（Reactive Streams 一句话）/ 0.4（EventLoop）/ 0.5（命令式 vs 响应式）
> 后续：L10 算子（怎么写）/ L11 背压与调度 / L12 Context·Sinks·调试
> **提问方式**：直接说编号，如「9.7 没懂」

---

## 9.0 本课要掌握的 5 条

| # | 要掌握的 | 一句话 | 在哪节 |
|---|---|---|---|
| **①** | **Reactive Streams = 4 个接口的规范** | `Publisher` / `Subscriber` / `Subscription` / `Processor` | 9.6 |
| **②** | **信号流** | `onSubscribe` → `onNext*` → (`onComplete` \| `onError`)；**终态只能有一个** | 9.7 |
| **③** | **订阅才发生**（惰性） | 返回 `Publisher` ≠ 执行；`just` 立即求值 vs `fromCallable`/`defer` 延迟 | 9.9 |
| **④** | **背压的本体是 `request(n)`** | **消费者驱动**（拉模型）—— 规范存在的根本原因 | 9.10 |
| **⑤** | **`Flux` 0..N / `Mono` 0..1 / `Mono<Void>`** + **vs `CompletableFuture`** | `Mono<Void>` = 只关心"完成"（过滤器/写响应用它） | 9.8 / 9.11 |

---

## 9.1 Reactor 是什么（官方定位 + 一个例子）

**官方定位（`reactor-core` README 原文）**：

> **Non-Blocking Reactive Streams Foundation for the JVM** both implementing a **Reactive Extensions inspired API** and efficient **event streaming** support.

| 官方词 | 含义 |
|---|---|
| Non-Blocking | 不阻塞线程（配合 EventLoop） |
| **Reactive Streams Foundation** | 它是**规范的一个实现**（那份 4 接口规范，见 9.6） |
| **Foundation（基础库）** | 它是**库**不是框架 —— **WebFlux 才是框架** |
| Reactive Extensions inspired | 算子风格沿用 Rx（`map`/`flatMap`/`filter`…） |
| event streaming | 天生处理"**流**"（0..N），不只是单个结果 |

**一句话**：
> **Reactor = JVM 上的响应式编程库**：实现 Reactive Streams 规范，提供 `Flux`/`Mono` + 100+ 算子，用来写**非阻塞、可组合、带背压**的异步代码。

**例子先行**——同一段逻辑（查用户 → 查订单 → 取商品名）：

```java
// ❌ 命令式（阻塞）：线程在每一步都"卡住等"
User u  = userService.get(id);                     // 线程在这里等待 IO
Order o = orderService.get(u.getOrderId());        // 线程又在这里等待 IO

// ✅ Reactor（声明式链）
Mono<String> chain = userService.get(id)                   // Mono<User>
        .flatMap(u -> orderService.get(u.getOrderId()))    // Mono<Order>
        .map(Order::getItem);                              // Mono<String>
// ★ 到这行为止，一次 IO 都没发生！
chain.subscribe();                                         // 订阅才真正执行
```

**两个本质变化**：① **声明与执行分离**（惰性）；② **没有"等待"**（数据到了由 EventLoop 回调）。

---

## 9.2 Reactor 解决什么问题（3 个）

| # | 问题 | Reactor 的答案 |
|---|---|---|
| **①** | 少量线程扛大量连接 | `Flux`/`Mono` + 非阻塞传输（Reactor Netty） |
| **②** | 异步逻辑组合难（回调地狱） | **算子链**（`map`/`flatMap`/`zip`/`then`…） |
| **③** | 生产者快、消费者慢 → 压垮 | **背压**（`request(n)`，见 9.10） |

---

## 9.3 它在 Spring 生态的位置（★ 最该记住的一张图）

```
你的业务代码（Controller / Filter / Service）
        ↓ 写的是
Spring WebFlux（响应式 Web 框架）      ← Gateway 就活在这层（+ 自己的扩展点）
        ↓ 依赖
Project Reactor（响应式库：Flux/Mono/算子/Schedulers/Context）
        ↓ 实现
Reactive Streams（规范：4 个接口）      ← 9.6 讲的契约
        ↓ 传输
Reactor Netty（Netty 的响应式封装：HttpServer / HttpClient）
        ↓
Netty（网络层：EventLoop / Channel / ByteBuf）
```

> **记这句**：**Reactor 是"库"（给能力）· WebFlux 是"框架"（给结构）· Gateway 是"应用"（用前两者）**。

---

## 9.4 核心概念地图（初识认识这 7 个词就够）

| 词 | 一句话 | 在哪学 |
|---|---|---|
| `Flux<T>` / `Mono<T>` | 0..N / 0..1 的数据流 | 9.8 |
| 惰性 / 订阅 | 不订阅什么都不发生 | 9.9 |
| 信号（signal） | `onNext` / `onComplete` / `onError` | 9.7 |
| 算子（operator） | `map`/`flatMap`/`then`… 组装链 | **L10** |
| 背压 | 消费者 `request(n)` | 9.10 + L11 |
| `Schedulers` | 控制"在哪个线程跑" | L11 |
| `Context` | 响应式上下文（跨链传数据） | L12 |

> **名字背景**（不用背）："Reactor" 不是核反应堆 —— 事件驱动架构里有个经典模式叫 **Reactor 模式**（监听事件 → 分发 → 处理器做出反应）。名字体现的模型是：**不是"我去拉数据"，而是"数据来了我反应"**。

---

## 9.5 从零讲起：为什么需要"数据流"抽象

### 先看阻塞模型撞到的墙

```
传统 Servlet/Tomcat（一请求一线程）：
  10 个并发连接   → 10 个线程
  10000 个并发连接 → 10000 个线程   ← 内存（每线程 ~1MB 栈）+ 上下文切换直接压垮
```

**要突破**：用**少量线程**处理**大量连接**（EventLoop，见 0.4）。但这样就产生 3 个新问题：

| # | 问题 | 具体 |
|---|---|---|
| **①** | 数据怎么"**异步地、分多次**"交给处理者？ | 结果不是马上有，可能分多次到达（流式） |
| **②** | 消费者**处理不过来**怎么办？ | 生产者快、消费者慢 → 内存堆积 → OOM |
| **③** | 怎么**取消**？ | 用户断开连接 / 超时 → 要能停掉整条处理链 |

**Reactive Streams 就是这 3 个问题的标准化答案。**

> **一句话**：它不是"另一个回调框架"，而是一份**接口规范** —— 规定了"生产者怎么把数据交给消费者、消费者怎么反馈压力、双方怎么取消"。

**规范 vs 实现**：

| 层 | 是什么 | 例子 |
|---|---|---|
| **规范（接口）** | `reactive-streams`（**只有 4 个接口**，一个 jar 而已） | 跨语言/跨库的**契约** |
| **实现（库）** | 按规范实现的库 | **Reactor**（Spring 生态首选）、RxJava、Akka Streams、Mutiny |
| **适配** | 不同实现互转 | `Flow`↔`Publisher`（JDK9 适配）、RxJava 适配 |

---

## 9.6 要掌握的 ①：Reactive Streams 的 4 个接口

```java
package org.reactivestreams;

// ① 生产者：可以订阅
public interface Publisher<T> {
    void subscribe(Subscriber<? super T> s);
}

// ② 消费者：接收 4 类信号
public interface Subscriber<T> {
    void onSubscribe(Subscription s);   // 订阅建立（第一个信号）
    void onNext(T t);                   // 收到一个数据
    void onError(Throwable t);          // 出错（终态）
    void onComplete();                  // 完成（终态）
}

// ③ 订阅关系：消费者用它【反馈】和【取消】
public interface Subscription {
    void request(long n);   // ★ 我要 n 个（背压的入口）
    void cancel();          // ★ 我不再需要了
}

// ④ 处理器：既是消费者又是生产者（中间环节）
public interface Processor<T, R> extends Subscriber<T>, Publisher<R> {}
```

### 逐个理解（关键在 `Subscription` 为什么要单独存在）

| 接口 | 角色 | 相当于 |
|---|---|---|
| `Publisher` | 数据源 | 自来水厂 |
| `Subscriber` | 数据处理者 | 你家水龙头 |
| **`Subscription`** | **一次订阅的"合同"** | **水表 + 阀门** |

**为什么 `Subscription` 不合并进 `Subscriber`？**
因为它是**双向**的：消费者通过它向生产者**提要求**（`request(n)`）和**叫停**（`cancel()`）。
没有它，生产者只能"闭眼猛推"（推模型）→ 问题②（压垮消费者）无法解决。

---

## 9.7 要掌握的 ②：一次订阅的完整时序（信号流）

```
Subscriber                                Publisher
    │                                        │
    │─────── ① subscribe(this) ─────────────▶│   （订阅）
    │◀────── ② onSubscribe(subscription) ────│   （建立合同，必须第一个来）
    │                                        │
    │─────── ③ subscription.request(n) ─────▶│   （消费者说"我要 n 个"）
    │◀────── ④ onNext(v1) ───────────────────│   （最多 n 个）
    │◀────── ④ onNext(v2) ───────────────────│
    │                                        │
    │─────── ⑤ request(m) ──────────────────▶│   （不够再要）
    │◀────── onNext(v3) ────────────────────│
    │                                        │
    │◀────── ⑥ onComplete() ────────────────│   （终态：正常结束）
    │        或 onError(e)                   │   （终态：出错结束；二者只有一个）
```

### 规范的 5 条关键规则（实现必须遵守）

| # | 规则 | 为什么 |
|---|---|---|
| 1 | `subscribe()` 后**必须最先**发 `onSubscribe`，且只能一次 | 消费者要先拿到"合同"才能提要求 |
| 2 | `request(n)`：**n > 0**；多次调用**累加**；n ≤ 0 → `onError(IllegalArgumentException)` | 保证背压语义正确 |
| 3 | 发出的 `onNext` 数量**不得超过**累计 `request(n)` | **背压的强制**（这是"流"而不是"推送"的关键） |
| 4 | `cancel()` 之后**不再发任何信号** | 支持取消（问题③） |
| 5 | `onComplete` / `onError` 是**终态**：之后不能再有信号，二者**只能出现一个** | 保证生命周期明确 |

> **⚠️ 记这个规则，可以解释 Gateway 里的很多代码**：为什么过滤器返回的 `Mono<Void>` 可以 `.then(...)`？因为 `Mono` 完成时会发 `onComplete` → `.then` 就是"等这个信号"。

---

## 9.8 要掌握的 ⑤（上）：`Flux` 与 `Mono`

| | **`Flux<T>`** | **`Mono<T>`** |
|---|---|---|
| 元素个数 | **0..N** | **0..1** |
| 信号序列 | `onSubscribe` → `onNext` × N → `onComplete` | `onSubscribe` → (`onNext` × 0/1) → `onComplete` |
| 空流 | `Flux.empty()` | `Mono.empty()` |
| 典型场景 | SSE、大文件、批量数据、扇出 | 一次 RPC、一次 DB 查询、一次 Redis 写 |
| 转换 | `flux.collectList()` → `Mono<List<T>>` | `mono.flux()` → `Flux<T>` |

### `Mono<Void>` 是什么（Gateway 里到处都是）

```java
Mono<Void>    // 0..1 个元素，且元素类型是 Void → 即【永远不会有值】，只会有【完成/错误】
```

**它的语义 = "只关心做完没做完"**：

| 代码 | 含义 |
|---|---|
| `chain.filter(exchange)` → `Mono<Void>` | "整条链跑完"（有没有值不重要） |
| `response.setComplete()` → `Mono<Void>` | "响应写完了" |
| `chain.filter(...).then(Mono.fromRunnable(...))` | "链跑完后，再做一件事，仍然不产出值" |

> **看懂 `Mono<Void>` 是读懂 Gateway 代码的关键一半。**

---

## 9.9 要掌握的 ③：惰性 —— "订阅才发生"

```java
// ① 返回 Publisher：此刻什么都没发生（只是"声明了"一条流水线）
Mono<String> m = Mono.fromCallable(() -> {
    System.out.println("执行了！");
    return "hello";
});
System.out.println("还没执行");        // ← 输出在这行之前不会看到"执行了！"

// ② 订阅：现在才执行
m.subscribe(v -> System.out.println("收到：" + v));
// 输出：还没执行 → 执行了！ → 收到：hello
```

### ⚠️ 两个"立即求值"的例外（最容易踩）

| 写法 | 求值时机 |
|---|---|
| `Mono.just(computeSlow())` | ❌ **构造时立即执行**（`computeSlow()` 先跑完，结果再包进去） |
| `Mono.fromCallable(() -> computeSlow())` | ✅ 订阅时才执行 |
| `Mono.defer(() -> Mono.just(System.currentTimeMillis()))` | ✅ 订阅时才求值 |

**这就是 L10 要展开的坑**（在过滤器里写 `Mono.just(阻塞调用())` 会在 EventLoop 上直接阻塞）。

### 冷流 vs 热流（补充概念）

| | 冷流（cold） | 热流（hot） |
|---|---|---|
| 每次订阅 | **重新执行**一遍（每个订阅者独立数据） | 共享同一份数据（订阅晚就错过） |
| 例子 | `Flux.range(1,10)`、HTTP 请求 | `Sinks`、事件总线 |
| Gateway 里 | 绝大多数是冷流 | 少（广播场景） |

---

## 9.10 要掌握的 ④：背压的本质 —— `request(n)`

**背压（backpressure）** = 消费者告诉生产者"我还能处理几个"，生产者**只能按量给**。

```
推模型（没有背压）：         拉模型（Reactive Streams）：
生产者 ──狂推──▶ 消费者       消费者 ──request(5)──▶ 生产者
  └─ 消费者处理不过来 → OOM      └─ 生产者最多给 5 个 → 不会压垮
```

| | 推（push） | 拉（pull） |
|---|---|---|
| 谁决定节奏 | 生产者 | **消费者**（`request(n)`） |
| 风险 | 消费者被压垮（问题②） | 无（按需拉取） |
| 代表 | 回调、事件监听 | Reactive Streams |

**为什么它是规范的核心**：只有把"消费者能承受多少"作为一等公民，异步流才能安全地在生产环境跑。

> **Gateway 里为什么不常触发** → **L11 细讲**（网关转发是"一进一出 + 流式直通"，不会积压）。

---

## 9.11 要掌握的 ⑤（下）：响应式 vs `CompletableFuture`（面试常问）

| 维度 | **`CompletableFuture`** | **`Flux` / `Mono`** |
|---|---|---|
| **元素数量** | 1 | 0..N / 0..1 |
| **求值时机** | **立即**（构造即开始执行） | **惰性**（订阅才执行） |
| **背压** | ❌ 无 | ✅ `request(n)` |
| **取消** | `cancel()`，但**传播依赖任务配合** | `cancel()` **标准化**，全链传播 |
| **重复执行** | ❌ 结果固化，不能重跑 | ✅ 冷流可**重新订阅**（重试/回放） |
| **组合能力** | `thenCompose` / `thenCombine` / `allOf`（少） | `flatMap` / `zip` / `merge` / `concat`…（**100+ 算子**） |
| **错误处理** | `exceptionally` / `handle` | `onErrorResume` / `onErrorReturn` / `retry` / `doOnError` |
| **线程/上下文** | 默认 `ForkJoinPool.commonPool`；切线程后上下文易丢 | `Schedulers` **显式控制** + `Context` 可跨链传递 |
| **多次/流式** | ❌ 不支持 | ✅ 原生支持 |

**一句话答面试**：
> "`CompletableFuture` 解决的是'**一个**异步结果'，它是饿汉（立即求值）、没有背压、组合能力弱；
> `Reactor` 解决的是'**一条异步流**'，它是懒汉（订阅才跑）、有背压和取消、组合算子丰富 —— 更适合网关这种**流式转发**的场景。"

---

## 9.12 Reactor 子项目一览（大纲第 2 项）

| 子项目 | 是什么 | 我们是否碰 |
|---|---|---|
| **`reactor-core`** | `Flux`/`Mono`/算子/`Schedulers`/`Context` | ✅ **全程用** |
| **`reactor-netty`** | Netty 的响应式封装（`TcpServer`/`HttpServer`/`TcpClient`/`HttpClient`） | ✅ 传输层（L00·0.4.4；**只用 `Http*`**，`Tcp*` 不用） |
| `reactor-test` | `StepVerifier`（流式断言） | 🟡 写单测时 |
| `reactor-extra` / `reactor-adapter` | 算子扩展 / 与 RxJava、`CompletableFuture` 互转 | 🟡 需要互转时查 |
| `reactor-pool` | 对象池（响应式版连接池） | ⏭️ 不碰（Gateway 用 reactor-netty 的 `ConnectionProvider`） |
| `reactor-kafka` / `reactor-rabbitmq` | 消息集成 | ⏭️ 不碰 |

---

## 9.13 常见的"想当然"（避坑）

| 想当然 | 实际 |
|---|---|
| "`Mono.just(x)` 只是包一下，不会执行 x" | ❌ `x` 是**先算完再包**的（立即求值） |
| "返回了 `Mono` 就等于发起请求了" | ❌ 没订阅什么都没发生（Gateway 里由框架订阅） |
| "我在 filter 里 `subscribe()` 一下也能跑" | ❌ **脱离链**：框架不知道它没做完，响应可能先返回 |
| "`Mono<Void>` 就是返回 null" | ❌ 它是"**没有值，但有完成信号**"（完全不同的语义） |
| "背压是库自己处理的，我不用管" | ⚠️ 要看场景：网关转发基本不触发，但**你自己写的扇出流**必须管（L11） |

---

## 9.14 动手实验（`reactor-lab` 模块）

> 位置：`reactor-lab/`（与 `demo-gateway` 同级的新模块）；**跑法见 `reactor-lab/README.md`**
> 结论：**先跑、先看输出，再回来看上面的规则** —— 这个课光看文字记不住。

| 实验 | 文件 | 看什么 |
|---|---|---|
| **Ex01** | `l09/Ex01_CustomPublisher.java` | **手写 4 个接口** → `onSubscribe` 第一个来、`onNext` 数量 ≤ 累计 request、`onComplete` 终态唯一 |
| **Ex02** | `l09/Ex02_SignalTrace.java` | Reactor 信号轨迹：`request(1)` → `onNext` **交替推进**（拉模型） |
| **Ex03** | `l09/Ex03_Laziness.java` | `just` **构造时就执行** vs `fromCallable`/`defer` **订阅时才执行** + 冷流重跑 |
| **Ex04** | `l09/Ex04_MonoVoid.java` | `Mono<Void>` 只有 `onComplete` 没有 `onNext`；`Mono.just(null)` 抛 NPE |
| **Ex05** | `l09/Ex05_RequestN.java` | 不 request = 流**根本不动**；一次要 1 个 = 源与消费者交替 |

```bash
# 一键跑全部（5 个实验，带分隔标题）
mvn -pl reactor-lab exec:java

# 只跑某一个
mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l09.Ex01_CustomPublisher

# 契约断言（StepVerifier，5 个）
mvn -pl reactor-lab test
```

---

## 9.15 掌握边界（明确"记什么/不记什么"）

| 内容 | 判断 |
|---|---|
| 4 个接口的名字与职责 | ✅ **必须记**（面试问"Reactive Streams 是什么"直接答） |
| 信号流 + 5 条规则（尤其"终态唯一"） | ✅ **必须记** |
| 惰性 + `just` 立即求值 | ✅ **必须记** |
| `Flux` 0..N / `Mono` 0..1 / `Mono<Void>` 语义 | ✅ **必须记** |
| `request(n)` = 背压本体 | ✅ **必须记** |
| vs `CompletableFuture` 的 6 个维度 | ✅ **必须记**（面试高频） |
| Reactor 子项目名字 | ✅ 知道有哪几个即可 |
| 规范的完整条文（几十条） | ⏭️ 翻篇（记 5 条关键规则够用） |
| `Processor` 的实现细节 | ⏭️ 翻篇（知道"既是消费者又是生产者"即可） |
| 各库（RxJava/Akka）差异 | ⏭️ 不碰 |

---

## 9.16 Self-Test

1. Reactive Streams 规范包含哪 4 个接口？各自职责是什么？
2. 为什么 `Subscription` 要单独存在，而不把 `request/cancel` 放进 `Subscriber`？
3. 一次订阅的信号顺序是什么？哪个信号必须是第一个？哪些是终态？
4. `request(n)` 里的 n 能是 0 或负数吗？违反了会怎样？
5. `Mono.just(computeSlow())` 和 `Mono.fromCallable(() -> computeSlow())` 有什么区别？
6. `Mono<Void>` 的语义是什么？为什么 Gateway 的过滤器返回它？
7. 背压为什么是"消费者驱动"？和推模型比它解决了什么问题？
8. 响应式和 `CompletableFuture` 的 6 个差异是什么？（能说出 4 个算过关）

---

### 9.16.1 参考答案

<details open>

**1. 4 个接口及职责**

| 接口 | 职责 |
|---|---|
| `Publisher<T>` | **生产者**：`subscribe(Subscriber)` |
| `Subscriber<T>` | **消费者**：接 4 类信号（`onSubscribe`/`onNext`/`onError`/`onComplete`） |
| `Subscription` | **一次订阅的"合同"**：`request(n)`（背压）/ `cancel()`（取消） |
| `Processor<T,R>` | **既是消费者又是生产者**（中间环节） |

**2. `Subscription` 为什么单独存在**
它**双向**：消费者要能对生产者**提要求**（`request(n)`）和**叫停**（`cancel()`）。
合并进 `Subscriber` → 生产者只能"闭眼猛推" → **背压无从表达**。

**3. 信号顺序 + 终态**
```
subscribe() → onSubscribe（★第一个，只一次）
           → onNext × N（≤ 累计 request）
           → onComplete 或 onError（★终态：二选一，之后不能再有信号）
```

**4. `request(n)` 的 n**
必须 **> 0**；违规（n ≤ 0）→ 生产者必须 `onError(IllegalArgumentException)`（规范 3.9）；多次调用**累加**。

**5. `just` vs `fromCallable`**

| | 求值时机 |
|---|---|
| `Mono.just(computeSlow())` | ❌ **构造时就执行**（在 EventLoop 上即当场阻塞） |
| `Mono.fromCallable(() -> computeSlow())` | ✅ **订阅时才执行**（惰性） |

证据：`reactor-lab/l09/Ex03_Laziness`

**6. `Mono<Void>`**
语义 = **永远不会有值（无 `onNext`），只有完成/错误信号**。
过滤器只关心"做完没做完"；且完成后能 `.then(...)` 接 post 阶段。
`chain.filter(exchange)` 就是 `Mono<Void>` = "整条后续链（含转发）跑完"。

**7. 背压为什么"消费者驱动"**
消费者用 `request(n)` 声明"我还能收几个"，生产者**只能按额度发** → 节奏由消费者掌握。
推模型：生产者猛推 → 消费者处理不过来 → 内存堆积 → **OOM/雪崩**。
证据：`Ex05_RequestN`（不 request → 源一个都不发）。

**8. vs `CompletableFuture`（6 个差异）**

| # | 维度 | `CompletableFuture` | Reactor |
|---|---|---|---|
| 1 | 元素数量 | 1 个 | 0..N / 0..1 |
| 2 | 求值时机 | **立即** | **惰性**（订阅才执行） |
| 3 | 背压 | ❌ 无 | ✅ `request(n)` |
| 4 | 取消 | 传播依赖任务配合 | ✅ 标准化、全链传播 |
| 5 | 重复执行 | 结果固化，不能重跑 | ✅ 冷流可重新订阅 |
| 6 | 组合能力 | `thenCompose`/`thenCombine`/`allOf`（少） | ✅ 100+ 算子 |

</details>

---

## 9.17 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「9.10 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
