# Lesson 10: Reactor Operators — 读/写网关代码的最小算子集

> 课程索引：`../gateway-学习清单.md` → Stage 3 · Lesson 10
> 版本口径：Reactor **3.6.5**（SCG 4.1.5 依赖）
> 前置：**L09**（4 接口 / 信号流 / 惰性 / `Mono<Void>` / `request(n)`）—— 本课不重复契约，只讲**算子怎么用**
> 后续：L11（背压策略 + `Schedulers` + `ParallelFlux`）/ L12（`Context` / `Sinks` / 调试）
> **提问方式**：直接说编号，如「10.4 没懂」

---

## 10.0 掌握卡（只要记这些）

```java
Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain)   // ① 签名
```

| # | 要掌握的 | 一句话 | 在哪节 |
|---|---|---|---|
| **①** | **返回 `Mono` 不执行，框架会订阅** | 你**不要自己 `subscribe()`**（会脱离链） | 10.3 |
| **②** | **`map` vs `flatMap`** | `map` = 同步转换；`flatMap` = **异步展平**（`T → Mono<R>`，不套娃） | 10.4 |
| **③** | **`then` = post 阶段** | `chain.filter(exchange).then(收尾)` —— 整条链（含转发）做完才执行 | 10.5 |
| **④** | **两个坑** | ① `just(阻塞())` **构造时就执行** → 用 `fromCallable`<br>② 阻塞代码必须 `subscribeOn(boundedElastic())`（L11 细讲） | 10.6 |

> **过关标准**：能默写 L05-filters.md §5.3 那个 15 行 `MyFilter`，并说清 pre/post 两阶段。

---

## 10.1 例子先行：拆解你亲手写的那段（`DemoGlobalFilter`）

```java
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    long start = System.nanoTime();
    // ...（改请求头、放 attributes，省略）
    log.info("[gateway] --> {} {} traceId={}", ...);

    return chain.filter(mutated)                    // ①
            .then(Mono.fromRunnable(() -> {          // ② ③
                long costMs = (System.nanoTime() - start) / 1_000_000;
                log.info("[gateway] <-- {} {} status={} cost={}ms", ...);
            }));
}
```

| 位置 | 算子/结构 | 含义 |
|---|---|---|
| **①** | `chain.filter(mutated)` | 返回 `Mono<Void>`：**整条后续链（含转发）全部完成** |
| **②** | `.then(...)` | **等①完成**，执行后面的 Mono（**丢弃①的值**） |
| **③** | `Mono.fromRunnable(...)` | 把"无返回值代码"包成 Mono（**惰性**） |

**pre / post 两阶段**：
- `log -->` 在 **pre**（链开始前）
- `.then(...)` 里的 `log <--` 在 **post**（转发完成、响应回来后）→ **所以能记耗时、能改响应头**

---

## 10.2 算子速查表（会这 12 个就够读网关代码）

| 算子 | 用途 | 备注 |
|---|---|---|
| `Mono.just(v)` | 包一个值 | ⚠️ **立即求值**（10.6） |
| `Mono.empty()` | 空完成（无值） | `Mono<Void>` 的"成功但没有值" |
| `Mono.error(e)` | 错误信号 | |
| `Mono.fromRunnable(r)` | 包一段无返回值代码 | **惰性** |
| `Mono.fromCallable(s)` | 包一段有返回值代码 | **惰性** ⭐ 比 `just` 安全 |
| **`map`** | 同步转换 `T → R` | 10.3 |
| **`flatMap`** | 异步展平 `T → Mono<R>` | 10.3 |
| **`then`** | 等前面完成、丢值、接下一个 | 10.4 |
| `doOnNext` / `doOnSuccess` / `doOnError` / `doFinally` | **副作用**（打日志等） | 10.4 |
| `defaultIfEmpty` / `switchIfEmpty` | 空值兜底 | 10.5 |
| `filter` | 条件过滤（不匹配 → 空） | 10.5 |
| `onErrorResume` / `onErrorReturn` | 错误兜底 | 用时查 |

---

## 10.3 要掌握的 ①②：惰性 + `map` vs `flatMap`

### 惰性（L09 已讲契约，这里只记结论）

```java
Mono<Void> m = doSomething();   // ← 什么都没发生
m.subscribe();                  // ← 订阅了才执行
```

**在 Gateway 里**：你 **返回** `Mono`（不订阅），**框架订阅你的返回值**（`DefaultGatewayFilterChain`，L26）。

> ⚠️ **最常见的 bug**：在 filter 里自己 `subscribe()` → **逻辑脱离链**（框架不知道它还没做完，响应可能先返回）。
> **正确做法**：把逻辑**串进**返回的 Mono（`then` / `flatMap` / `doOnSuccess`）。

### `map` vs `flatMap`

```java
// map：同步转换（在同一个线程上直接算）
Mono<String> name = getUser().map(User::getName);           // Mono<User> → Mono<String>

// flatMap：异步展平（内层又返回一个 Mono）
Mono<Order> order = getUser().flatMap(u -> getOrder(u));    // Mono<User> → Mono<Order>
//                                                      ↑ 返回 Mono，不是 Mono<Mono<Order>>
```

| | `map` | `flatMap` |
|---|---|---|
| 转换函数返回 | 普通值 `R` | **`Mono<R>` / `Flux<R>`** |
| 是否展开 | — | ✅ 自动展平成一层（**不套娃**） |
| 用途 | 字段提取、简单计算 | **链式调用另一个响应式方法** |

> **口诀**：**下一步要不要"再发一个请求 / 再读一次 Redis"？要 → `flatMap`。**

---

## 10.4 要掌握的 ③：`then` 与副作用算子

| 算子 | 触发时机 | 拿到值吗 | 性质 |
|---|---|---|---|
| `doOnNext(v)` | 每次有数据 | ✅ | **旁路副作用** |
| `doOnSuccess(v)` | 完成时 | ✅（可能为 `null`） | 旁路副作用 |
| `doOnError(e)` | 出错时 | 异常 | 旁路副作用 |
| `doFinally(sig)` | **无论成功/失败/取消** | 信号类型 | 旁路副作用 |
| **`then(m)`** | 前面完成后 | ❌ **丢弃值**，只等"完成" | **接续**（改变流） |

**最常用的组合**（网关里到处都是）：

```java
return chain.filter(exchange)                  // Mono<Void>
        .then(Mono.fromRunnable(() -> {...})); // 收尾（post 阶段）
```

> **区别**：`doOnNext` / `doOnSuccess` 是**旁路**（不影响数据流）；`then` / `flatMap` 是**接续**（改变流）。

---

## 10.5 兜底与过滤（写过滤器时的日常）

```java
// 空值兜底
cache.get(key).defaultIfEmpty("默认值");                 // 空 → 用默认值，继续往下
cache.get(key).switchIfEmpty(fallbackCall());           // 空 → 切到另一个 Publisher

// 条件过滤（不匹配 → 变成空流）
flux.filter(v -> v > 0);
```

**Gateway 里的用法**：读不到配置/缓存时兜底；按条件决定"继续转发"还是"短路返回"。

---

## 10.6 要掌握的 ④：两个坑（必踩）

### 坑 1：`just` 立即求值（构造就执行）

```java
// ❌ 错：computeSlow() 在【构造这行】时就跑了 —— 而且通常在 EventLoop 上
return Mono.just(computeSlow());

// ✅ 对：
return Mono.fromCallable(() -> computeSlow())
           .subscribeOn(Schedulers.boundedElastic());
// 或
return Mono.defer(() -> Mono.just(computeSlow()));
```

> 详细原理见 **L09 §9.5**（惰性）+ 实验 `reactor-lab/l09/Ex03_Laziness`。

### 坑 2：在 EventLoop 上阻塞

```java
// ❌ 卡住的是【整个 EventLoop 上的所有连接】—— 不是"慢一个请求"，是"慢一批请求"
redis.get(key);                    // 阻塞式客户端
Thread.sleep(100);                 // 任何阻塞

// ✅ 逃生舱：包成 fromCallable + 切到 boundedElastic
Mono.fromCallable(() -> redis.get(key))
    .subscribeOn(Schedulers.boundedElastic());
```

> 线程模型与 `Schedulers` 细节见 **L11**；真实事故（RV33/RV34）见 `L00-prerequisites.md` **0.4.7**。

---

## 10.7 动手实验（`reactor-lab` → `l10/`）

| 实验 | 文件 | 看什么 |
|---|---|---|
| **Ex01** | `l10/Ex01_MapVsFlatMap.java` | `map` 返回普通值 / `flatMap` 返回 `Mono`（**不套娃**）；什么时候必须 `flatMap` |
| **Ex02** | `l10/Ex02_ThenAndDoOn.java` | `then` / `doOnNext` / `doOnSuccess` / `doOnFinally` 的**触发时机**（pre/post） |
| **Ex03** | `l10/Ex03_FilterAndFallback.java` | `filter` / `defaultIfEmpty` / `switchIfEmpty` / `onErrorReturn` 四种兜底 |

```bash
mvn -pl reactor-lab exec:java                                        # 全部
mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l10.Ex01_MapVsFlatMap
mvn -pl reactor-lab test                                             # 断言
```

---

## 10.8 掌握边界

| 内容 | 判断 |
|---|---|
| 12 个算子（10.2 表） | ✅ **必须记**（会读会写） |
| `map` / `flatMap` 的取舍 | ✅ **必须记** |
| `then` = post 阶段 | ✅ **必须记** |
| 不自己 `subscribe()` | ✅ **必须记** |
| `just` 立即求值 / 阻塞要 `boundedElastic` | ✅ **必须记**（两个坑） |
| `zip` / `merge` / `window` / `groupBy` 等组合算子 | ⏭️ 用时再查 |
| `Schedulers` 内部实现、`ParallelFlux` | ⏭️ **L11** |
| Reactor 源码 | ⏭️ 不学 |

---

## 10.9 Self-Test

1. `filter()` 方法里能自己 `subscribe()` 吗？后果是什么？
2. `map` 和 `flatMap` 的区别？什么时候**必须**用 `flatMap`？
3. `chain.filter(exchange).then(...)` 里的 `.then(...)` 什么时候执行？为什么用它记耗时？
4. `doOnNext` 和 `then` 有什么本质区别？
5. `Mono.just(computeSlow())` 有什么问题？正确写法是什么？
6. 空值兜底用哪两个算子？错误兜底呢？

---

## 10.10 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「10.6 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
