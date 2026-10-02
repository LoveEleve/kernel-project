# Lesson 11: Backpressure & Scheduling — 背压与调度

> 课程索引：`../gateway-学习清单.md` → Stage 3 · Lesson 11
> 版本口径：Reactor **3.6.5**（SCG 4.1.5 依赖）
> 前置：L09（`request(n)` 是背压本体）· L10（算子）
> 后续：L12（`Context` / `Sinks` / 调试）
> **提问方式**：直接说编号，如「11.4 没懂」

---

## 11.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 | 在哪节 |
|---|---|---|---|
| **①** | **背压策略 4 种** | 生产者快了怎么办：**默认（报错）** / `BUFFER`（缓存） / `DROP`（丢新的） / `LATEST`（只留最新） | 11.2 |
| **②** | **`Schedulers` 三种** | `parallel`（CPU 密集）/ **`boundedElastic`（阻塞逃生舱）** / `single`（串行） | 11.3 |
| **③** | **`publishOn` vs `subscribeOn`** | `subscribeOn` 管**订阅发生在哪**（影响上游）；`publishOn` 管**它之后**在哪跑 | 11.4 |
| ＋ | `ParallelFlux` | `parallel(n)` + `runOn(scheduler)` = 把元素**分片并行**处理 | 11.5 |

> **Gateway 视角**：网关转发是"**一进一出 + 流式直通**"→ 背压**几乎不触发**；真正要背压的是 **SSE / 大文件 / `Flux` 扇出**（11.6）。

---

## 11.1 例子先行：生产者快、消费者慢

```java
// 生产者：每 20ms 产一个（共 5 个）
Flux<Long> fast = Flux.interval(Duration.ofMillis(20)).take(5);

// 消费者：一次只 request(1)，"处理完"再要下一个（慢消费）
fast.subscribe(new BaseSubscriber<Long>() {
    @Override protected void hookOnSubscribe(Subscription s) { request(1); }

    @Override protected void hookOnNext(Long v) {
        // ⚠️ 关键：慢消费【不能在发射线程上 sleep】（见下面的"实验陷阱"）
        //    正确做法：在【另一个线程】上延迟 100ms 再补一个 request(1)
        Schedulers.boundedElastic().schedule(() -> request(1), 100, TimeUnit.MILLISECONDS);
    }
});
```

**会发生什么**：
- t=20ms：收到第 1 个 → 下一次 `request(1)` 被排到 t=120ms
- t=40/60/80/100ms：生产者又产了 4 个，但消费者**没有额度** → **溢出（overflow）**

### ⚠️ 实验陷阱：为什么不能写 `Thread.sleep(100)`

| 写法 | 结果 | 为什么 |
|---|---|---|
| `hookOnNext` 里 `Thread.sleep(100)` | ❌ **看不到溢出**（实测 5 个全收到） | Reactor 信号**同步传递**：`hookOnNext` 跑在**发射线程**（interval 的定时器线程）上；在这里 sleep → **把定时器线程一起堵住** → 生产者根本产不出新数据 |
| 在**另一个线程**上延迟 `request(1)` | ✅ 溢出正常发生 | "慢"发生在别处，发射线程不受影响，生产者照常产数据 |

> **结论**：想复现"生产者快、消费者慢"，**"慢"必须发生在另一个线程上** —— 这是本课最容易踩的实验陷阱（写 demo 时我就在这里翻过车）。

**溢出了怎么办？—— 这就是"背压策略"要回答的。**

---

## 11.2 要掌握的 ①：背压策略（4 种）

| 策略 | 算子 | 行为 | 结果 |
|---|---|---|---|
| **默认（ERROR）** | （不加） | 溢出 → **报错** | `onError(IllegalStateException: Could not emit ... due to lack of requests)` |
| **BUFFER** | `onBackpressureBuffer()` | **全部缓存**起来等消费者 | 慢消费者**最终能收全**（代价：内存） |
| **DROP** | `onBackpressureDrop()` | 没额度就**丢弃**（丢新的） | 只收到部分数据 ✅ 最快 |
| **LATEST** | `onBackpressureLatest()` | **只保留最新一个**，旧的丢 | 收到部分，但**末尾是最新值** |

```java
Flux.interval(Duration.ofMillis(20)).take(5).onBackpressureBuffer()   // 缓存
Flux.interval(Duration.ofMillis(20)).take(5).onBackpressureDrop()     // 丢弃
Flux.interval(Duration.ofMillis(20)).take(5).onBackpressureLatest()   // 只留最新
```

**怎么选**：

| 场景 | 选谁 |
|---|---|
| 数据**不能丢**（订单、账务） | `BUFFER`（但要设上限，防 OOM）或**改用拉模式** |
| 数据可丢、要实时（监控指标、日志） | `DROP` |
| 只要**最新状态**（行情、位置） | `LATEST` |
| 什么都不做 | 默认报错（**快速失败**，暴露问题） |

> **实验**：`reactor-lab/l11/Ex01_BackpressureStrategies`（四种策略跑给你看）

---

## 11.3 要掌握的 ②：`Schedulers` 三种（+ 默认参数）

| Scheduler | 线程名 | 用途 | 默认规模（源码实证） |
|---|---|---|---|
| **`Schedulers.parallel()`** | `parallel-N` | **CPU 密集**（纯计算） | **CPU 核数**（`DEFAULT_POOL_SIZE`） |
| **`Schedulers.boundedElastic()`** | `boundedElastic-N` | **阻塞任务逃生舱**（JDBC / 阻塞 IO） | **10 × CPU 核**，每线程队列 **10 万** |
| **`Schedulers.single()`** | `single-N` | 全局**串行**执行（顺序敏感） | 1 个线程 |
| `Schedulers.immediate()` | （当前线程） | 不切换线程（测试用） | — |

```java
Mono.fromCallable(() -> blockingJdbcCall())              // ← 阻塞代码
    .subscribeOn(Schedulers.boundedElastic())            // ← 挪出 EventLoop（L10 的坑 2）
    .subscribe(...);
```

> **⚠️ 三条纪律**（背下来）：
> 1. **CPU 密集** → `parallel()`
> 2. **阻塞 IO** → `boundedElastic()`（**唯一逃生舱**）
> 3. **千万不要**把阻塞代码放在 `parallel()` 上 —— 那是给计算用的，会被堵死

（`boundedElastic` 的槽位弹性 / 引用计数细节见 `L00-prerequisites.md` **0.4.8**）

### 11.3.2 切线程时到底发生了什么（EventLoop ↔ `Schedulers`）★ 实测

**问题**：默认不是 EventLoop 线程执行过滤器吗？那 `Schedulers` 是怎么插进来的？执行完会切回 EventLoop 吗？

**实测（`reactor-lab/l11/Ex05_EventLoopVsScheduler`，起真实 HTTP 服务）**：

```
① 默认请求
    [默认 handler] @reactor-http-epoll-4      ← 请求处理跑在 EventLoop
② 带阻塞的请求（subscribeOn(boundedElastic)）
    [阻塞任务]  @boundedElastic-1             ← 阻塞段被挪走
    [阻塞之后]  @boundedElastic-1             ← ★ 没有切回 EventLoop！
    [客户端] 响应: ok:/blocking               ← 响应仍正常发出（IO 由 EventLoop 做）
```

**① 默认：EventLoop 执行过滤器**（colocation，L00·0.4）—— 请求读事件和处理在**同一条 EventLoop 线程**上。

**② 切线程的机制 = "信号交接"，不是"切走再切回"**：

```
EventLoop 线程：把元素放进【队列】→ 立即返回（去处理别的连接）
                      ↓ 队列
Worker 线程：从队列取元素 → 继续往下游传播信号（onNext/onComplete/onError）
```
→ EventLoop **不被阻塞**（只是"投递"）；后续信号由 Worker 执行。

**③ 不会自动切回 EventLoop** —— Reactor 的线程模型是"**谁切走就一直在那**"：

> `Flux.publishOn` javadoc 原文：*"Run onNext, onComplete and onError on a supplied Scheduler Worker...
> **the chain below it will execute, up to a new occurrence of `publishOn`**"*

**能不能显式切回？** ❌ 不能直接用 Scheduler 切回：`LoopResources extends Disposable`（**不是 `Scheduler`**），
Reactor Netty 没把 EventLoop 暴露成 Scheduler。（真要切，可以 `Schedulers.fromExecutor(eventLoopGroup)` 包一层 —— 但实践上不需要。）

**④ 关键澄清：业务线程 ≠ IO 线程**

| 谁 | 干什么 |
|---|---|
| **业务线程**（EventLoop 或 boundedElastic） | 执行你的代码：过滤器逻辑 / 算子 / **组装**请求 |
| **EventLoop**（始终） | **真正的 socket 读写**：把数据写进 channel、从 channel 读响应 |

→ 即使链路被切到 boundedElastic，`response.sendString(...)` / `httpClient.request(...)` 也**只是把数据交给 channel**，真正的 IO 由 EventLoop 完成。

**⑤ 对 Gateway 的实践结论**

| 场景 | 跑在哪 | 结论 |
|---|---|---|
| 默认过滤器（改头 / 日志 / 鉴权） | **EventLoop** | ✅ 必须**非阻塞**（L10 坑 2 / L35） |
| 阻塞调用（JDBC / 阻塞 Redis） | `subscribeOn(boundedElastic)` | ✅ EventLoop 被解放；后续段也在 boundedElastic |
| 转发下游（`NettyRoutingFilter` → `HttpClient`） | 业务线程只"发起"，**IO 由 EventLoop 执行** | ✅ 转发本身不阻塞 |

> **一句话**：**默认在 EventLoop 上跑（所以不能阻塞）；阻塞就切 `boundedElastic`；切走后不用管"切回来"—— IO 有 EventLoop 兜底。**

### 11.3.1 线程家族全景（★ 别混：EventLoop ≠ `Schedulers`）

**两类完全不同的线程**：

| 类别 | **谁创建** | 线程名 | 干什么 |
|---|---|---|---|
| **EventLoop 线程** | **Reactor Netty**（Netty EventLoopGroup） | `reactor-http-epoll-N` | **网络 IO**（读写 socket、触发回调，L00·0.4） |
| **`Schedulers` 线程** | **Reactor Core**（`Schedulers` 类） | `parallel-N` / `boundedElastic-N` / `single-N` | **业务逻辑**（切线程用） |

| 线程名 | 归属 | 什么时候出现 | 用途 |
|---|---|---|---|
| `reactor-http-epoll-N` | **Reactor Netty** | 网关**启动就有** | 入站/出站 IO（N = CPU 核数） |
| `server` | Spring Boot（NettyWebServer） | 启动就有 | 保活（非守护线程） |
| `spring.cloud.inetutils` | spring-cloud-commons | 启动就有 | 探测本机地址 |
| **`parallel-N`** | Reactor Core | 用了 `Schedulers.parallel()` | CPU 密集（N = CPU 核数） |
| **`boundedElastic-N`** | Reactor Core | 用了 `boundedElastic()` | **阻塞逃生舱**（弹性 0~10×CPU） |
| **`boundedElastic-evictor-1`** | Reactor Core | 用了 boundedElastic 后 | **回收器**：清理空闲线程 |
| **`single-N`** | Reactor Core | 用了 `Schedulers.single()` | **全局串行**（只有 1 条线程） |

**`single` 的源码注释**（`Schedulers.java:1178`）：`"single" // non blocking tasks` —— 给**非阻塞**任务用的串行调度器。

> **为什么平时看不到 `parallel`/`boundedElastic`/`single`**：默认整条链路跑在 EventLoop 上（colocation）；
> **只有显式切线程**（`subscribeOn` / `publishOn`）时，Reactor Core 才会创建这些线程池。
>
> **一句话**：`reactor-http-epoll-*` = "**默认就在的 IO 线程**"；`parallel-N` / `boundedElastic-N` / `single-N` = "**框架/你用了才创建的线程**"。

**⚠️ 与前置课 0.4.5 的"实测 4 种"对账**：

| | 0.4.5 的实测表（4 种） | 本节的理论全集（Schedulers 家族） |
|---|---|---|
| 范围 | 那**一次实测进程里真实存在**的线程 | Reactor Core **提供**的全部调度器 |
| 包含 | `reactor-http-epoll-N` / `parallel-N` / `boundedElastic-N` / `boundedElastic-evictor-1` | 上述 4 种 + **`single-N`** + `immediate`（无线程） |
| 为什么差一个 | — | `Schedulers.single()` **没人调用** → **懒创建**机制下它不存在 |

**实测那 4 种"谁用出来的"（源码实证）**：

| 线程 | 谁在用 | 位置 |
|---|---|---|
| `reactor-http-epoll-N` | Reactor Netty：全部 IO | — |
| `parallel-N` | ① **Reactor Netty 连接池后台清理**<br>② Gateway 响应体改写 | `PooledConnectionProvider.scheduleInactivePoolsDisposal()`<br>`ModifyResponseBodyGatewayFilterFactory:272` |
| `boundedElastic-N` | Gateway **路由 ID 生成** | `CompositeRouteDefinitionLocator:68` |
| `boundedElastic-evictor-1` | boundedElastic 的**回收器** | 创建时自动带 |

> **纪律**：**不要背数量**（数量 = "这次跑了什么代码"的函数），**要背分类**：
> ① **默认就在**（EventLoop / server / inetutils）；② **用了才有**（`Schedulers` 家族，**懒创建**）。

---

## 11.4 要掌握的 ③：`publishOn` vs `subscribeOn`（精确语义）

**⚠️ 验证姿势**：**必须在每个阶段内部打印线程名**（不能用 `map` 的返回值——值会被下一段覆盖，就看不到上游跑在哪了）：

```java
static String me() { return Thread.currentThread().getName(); }

// ① 不切线程
Flux.range(1, 2)
        .map(v -> { log("上游 map@" + me()); return v; })
        .map(v -> { log("下游 map@" + me()); return v; });

// ② subscribeOn：影响【订阅发生在哪】→ 整条链（含上游）都在那个线程上
Flux.range(1, 2)
        .map(v -> { log("上游 map@" + me()); return v; })
        .subscribeOn(Schedulers.parallel())
        .map(v -> { log("下游 map@" + me()); return v; });

// ③ publishOn：影响【它之后】→ 上游仍留在调用线程
Flux.range(1, 2)
        .map(v -> { log("上游 map@" + me()); return v; })
        .publishOn(Schedulers.parallel())
        .map(v -> { log("下游 map@" + me()); return v; });
```

**实测输出**（`./scripts/run-one.sh l11.Ex03_PublishOnVsSubscribeOn`）：

| | 上游 map | 下游 map | subscribe |
|---|---|---|---|
| **① 不切线程** | 调用线程 | 调用线程 | 调用线程 |
| **② `subscribeOn(parallel)`** | **parallel-1** | parallel-1 | parallel-1 |
| **③ `publishOn(parallel)`** | **调用线程** ← ★ 没被切走 | parallel-2 | parallel-2 |
| **④ 组合拳** | 阻塞阶段 @`boundedElastic-1` | 计算阶段 @`parallel-3` | parallel-3 |

| | `subscribeOn(s)` | `publishOn(s)` |
|---|---|---|
| 影响范围 | **订阅动作**（→ 源头与整条链在 `s` 上跑） | **它之后**的算子与订阅者 |
| 位置要求 | 放哪都行（**只有第一个生效**） | **有顺序意义**（后面的都在 `s` 上） |
| 典型用途 | **把阻塞源挪出 EventLoop** | 切换后续处理线程（如切回并行计算） |

> **口诀**：**`subscribeOn` 管"从哪儿开始"，`publishOn` 管"从这儿往后"。**

---

## 11.5 `ParallelFlux`：把元素分片并行

```java
Flux.range(1, 4)
    .parallel(4)                        // ① 分成 4 条"轨道"（Rail）
    .runOn(Schedulers.parallel())       // ② 每条轨道跑在 parallel 线程上
    .map(i -> cpuHeavy(i))              // ③ 现在才是真并行
    .sequential()                       // ④ 合回一条流
    .subscribe(...);
```

| | `publishOn(Schedulers.parallel())` | `parallel(n).runOn(...)` |
|---|---|---|
| 并行的是什么 | ❌ 只是**换线程**，元素仍**串行**处理 | ✅ 元素**分片并行**（多个元素同时处理） |
| 适用 | 切换执行上下文 | **CPU 密集**的批量处理 |

> **注意**：`parallel()` 单独用**不会并行** —— **必须配 `runOn(scheduler)`** 才真正跑起来。

---

## 11.6 Gateway 视角：为什么这里几乎不触发背压

| 原因 | 说明 |
|---|---|
| **一进一出** | 一个请求 → 一个响应；数据量由下游决定，网关不会"消费不过来" |
| **流式直通** | `NettyRoutingFilter` 把下游响应**边收边写**，不缓冲积压 |
| 真正要注意的场景 | **SSE / 大文件流式 / `Flux` 扇出**（广播、聚合多条流） |

> **面试答法**："网关的转发链路是一进一出的流，背压基本不触发；真正需要考虑背压的是**流式场景**（SSE/大文件）和**应用内的 Flux 扇出**。但 `Schedulers` 的纪律必须守 —— 阻塞代码一律 `boundedElastic`。"

---

## 11.7 动手实验（`reactor-lab` → `l11/`）

| 实验 | 文件 | 看什么 |
|---|---|---|
| **Ex01** | `l11/Ex01_BackpressureStrategies.java` | 4 种策略的实测结果（报错 / 收全 / 丢部分 / 只要最新） |
| **Ex02** | `l11/Ex02_Schedulers.java` | 三种 `Schedulers` 的**线程名**与**默认规模** |
| **Ex03** | `l11/Ex03_PublishOnVsSubscribeOn.java` | 线程名对比：`subscribeOn` 影响上游 / `publishOn` 影响下游 |
| **Ex04** | `l11/Ex04_ParallelFlux.java` | `parallel + runOn` 的**真并行**（耗时对比 + 线程名） |

```bash
mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l11.Ex01_BackpressureStrategies
mvn -pl reactor-lab test        # 断言
```

---

## 11.8 掌握边界

| 内容 | 判断 |
|---|---|
| 背压策略 4 种 + 怎么选 | ✅ **必须记** |
| `Schedulers` 三种 + 三条纪律 | ✅ **必须记** |
| `publishOn` vs `subscribeOn` | ✅ **必须记** |
| `parallel().runOn()` 的意义 | ✅ 记 |
| Gateway 为什么几乎不触发背压 | ✅ 记（面试答法） |
| `Schedulers` 内部实现（Worker/槽位/引用计数） | ⏭️ 用时查（`0.4.8` 有 boundedElastic 拆解） |
| `FluxSink.OverflowStrategy`（**生产端**策略） | ⏭️ 用时查（和本文的**消费端**算子是一对，别混） |

---

## 11.9 Self-Test

1. 背压策略有哪 4 种？各自的行为是什么？
2. 数据不能丢、但生产者可能更快时，选哪个策略？要注意什么？
3. `Schedulers` 三种分别用于什么？为什么阻塞代码不能放 `parallel`？
4. `subscribeOn` 和 `publishOn` 分别影响哪一段？为什么说"`subscribeOn` 只有第一个生效"？
5. `parallel(n)` 单独用会并行吗？为什么？
6. 网关里为什么几乎不需要背压策略？

### 11.9.1 参考答案

<details open>

**1. 4 种策略**
默认（ERROR，溢出即报错）/ `BUFFER`（全缓存）/ `DROP`（丢新的）/ `LATEST`（只留最新）。

**2. 不能丢 + 生产者快**
`BUFFER`，但**必须设上限**（`onBackpressureBuffer(maxSize, ...)`）防止内存无限增长；更彻底的办法是改用**拉模式**（消费者驱动）。

**3. `Schedulers` 三种**

| | 用途 | 默认规模 |
|---|---|---|
| `parallel` | CPU 密集 | CPU 核数 |
| `boundedElastic` | **阻塞 IO 逃生舱** | 10×CPU 核，每线程 10 万队列 |
| `single` | 全局串行 | 1 线程 |

阻塞代码不能放 `parallel`：它是**给计算用的固定大小线程池**，一个阻塞任务就会占死一个线程 → 拖垮整个池。

**4. `subscribeOn` vs `publishOn`**
- `subscribeOn`：影响**订阅动作**发生的线程 → 源头和整条链在它上面跑；链中只有**第一个**（最靠近源）生效，因为订阅只发生一次。
- `publishOn`：影响**它之后**的算子与订阅者；有顺序意义（后面都在该线程上）。

**5. `parallel(n)` 单独用**
**不会**并行。它只是把数据分成 n 条轨道（Rail），必须配 **`runOn(scheduler)`** 才会在多线程上执行。

**6. 网关为什么几乎不用**
转发是"**一进一出 + 流式直通**"（边收边写，不缓冲），不存在生产者压垮消费者；真正要背压的是 **SSE / 大文件 / Flux 扇出**。

</details>

---

## 11.10 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「11.4 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
