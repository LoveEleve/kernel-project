# Lesson 26: Chain Execution — `DefaultGatewayFilterChain` 的"洋葱模型"

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 26（★v5.4 新增）
> 前置：**L10**（"洋葱模型"首现）· **L17 §17.5.2**（链源码首过 —— 本课深一层：时序与语义）· **L25**（链是怎么拼出来的）
> 口径：🔴 **链式递归（index 递增）如何实现"洋葱模型"（pre / post 两阶段）**

**本课回答一个问题**：一条已经拼好的链（L25），**执行起来是什么顺序**？
答案是**洋葱模型**：**pre 阶段顺流而下**（订阅传播）→ 链尾 → **post 阶段逆流而上**（完成信号回传）。
而 `DefaultGatewayFilterChain` 其实只做两件事：**把"下一棒"递给你**（index+1 的 chain）、**在链尾发完成信号**；所谓"后半段逻辑"，完全取决于**你自己**在 `chain.filter(...)` 后面写了什么（`.then` / `doOnSuccess`…）——这就是本课的全部。

---

## 26.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **链 = 一叠"下一棒"引用** | 每个 chain 对象只持 `filters` + `index`；filter 构造下一棒（index+1）后传给自己 |
| **②** | **洋葱 = 两级流向** | **pre 顺流而下**（订阅传播）→ 链尾 → **post 逆流而上**（完成信号回传） |
| **③** | **post 不是框架切的** | 是 **filter 自己**把逻辑写在 `chain.filter(...)` **之后的操作符**里（`then`/`doOnSuccess`/`doFinally`…） |
| **④** | **每请求一条链** | `handle` 里 `new`；filter 是**单例**（无状态约束）；**短路**=不调 `chain.filter` |

---

## 26.1 例子先行：三个过滤器跑一遍，谁先谁后？

假设链是 `[A, B, C]`（都写成"pre 打印 + post 打印"）：

```java
// A 的 filter（B、C 同理，只是打印不同）
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    System.out.println("A-pre");                              // ① pre
    return chain.filter(exchange)                             // ② 走下一棒
            .then(Mono.fromRunnable(() -> System.out.println("A-post")));   // ③ post
}
```

执行输出：

```
A-pre
B-pre
C-pre        ← 链尾（Mono.empty() 完成）
C-post
B-post
A-post
```

**一张图（洋葱剖开看）**：

```
请求 ▼                                     ▲ 响应
┌────────────────────────────────────────────────┐
│ filter A（order 1）   pre ─────────────► ── post │
│  ┌──────────────────────────────────────────┐  │
│  │ filter B（order 2） pre ──────────► ─ post│  │
│  │  ┌────────────────────────────────────┐  │  │
│  │  │ filter C（order 3）pre ─► ──── post │  │  │
│  │  │            └── 链尾 Mono.empty() ──┘  │  │  │
│  │  └────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────┘  │
└────────────────────────────────────────────────┘
```

**用 demo 路由的真实链对照**（运行视图 `/routes` 里的 filters：`StripPrefix(1) → AddRequestHeader(2) → AddResponseHeader(3) → DemoHeader(4)` + 全局过滤器们插在中间）：

| 过滤器 | 属哪一阶段 | 源码依据 |
|---|---|---|
| `StripPrefix` | **pre** | 改 path → `chain.filter(exchange.mutate().request(newRequest).build())`（走下一棒前就改完） |
| `AddRequestHeader` | **pre** | 加请求头 → `chain.filter(...)` |
| `AddResponseHeader` | **post** | `return chain.filter(exchange).then(Mono.fromRunnable(() -> addHeader(exchange, config)));` —— 整条下游跑完才加响应头（还有 `isCommitted()` 防护） |
| `DemoHeader`（L15） | pre（当时写法） | — |

**真实打点（demo 自己的 `DemoGlobalFilter`，order=100）**——它把"pre / post"写在了代码里，跑一次 `GET /provider/hello` 就能看到洋葱：

```java
// DemoGlobalFilter#filter（真实代码，节选）
log.info("[gateway] --> {} {} traceId={}", ...);        // ① pre：走下一棒之前
return chain.filter(mutated)                            // ② 走下一棒
        .then(Mono.fromRunnable(() -> {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[gateway] <-- {} {} status={} cost={}ms traceId={}", ...);   // ③ post：整条下游跑完才打
        }));
```

同一请求的日志时间线（2026-10-02 14:19:32 实测）：

```
DemoWebFilter    : [DemoWebFilter] 匹配前生效 #154 /provider/hello     ← 更外层 WebFilter（L05/L18，先于一切）
DemoGlobalFilter : [gateway] --> GET /hello traceId=8ae0d0e...        ← pre（order=100 层进入）
DemoGlobalFilter : [gateway] <-- GET /hello status=200 OK cost=15ms   ← post（下游全跑完、响应状态已回来）
```

看第三行的 **`status=200 cost=15ms`**：它只能在整条下游（含转发与响应回程）完成之后才打得出来——这就是"**完成信号逆流而上**"的实物。

---

## 26.2 源码再拆三个层次（L17 给过代码，这里补"为什么这么写"）

```java
private static class DefaultGatewayFilterChain implements GatewayFilterChain {
    private final int index;
    private final List<GatewayFilter> filters;

    DefaultGatewayFilterChain(List<GatewayFilter> filters) { this.filters = filters; this.index = 0; }
    private DefaultGatewayFilterChain(DefaultGatewayFilterChain parent, int index) {
        this.filters = parent.getFilters();      // 共享同一个列表
        this.index = index;                      // 只是"读到哪儿了"
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange) {
        return Mono.defer(() -> {                                  // ★ 把"取谁"推迟到订阅时刻
            if (this.index < filters.size()) {
                GatewayFilter filter = filters.get(this.index);
                DefaultGatewayFilterChain chain = new DefaultGatewayFilterChain(this, this.index + 1);  // 下一棒
                return filter.filter(exchange, chain);             // 交给当前 filter；它自己决定何时走下一棒
            }
            else {
                return Mono.empty(); // complete               // ★ 链尾
            }
        });
    }
}
```

**层次 ①：链对象只是"读指针"**
`filters` 列表从头到尾共享一份；每个 chain 对象唯一的新东西是 `index`。所谓"递归"不是方法递归，而是**一叠互相引用的 chain 对象**，谁拿到 index+1 的那只"下一棒"谁就能继续走。

**层次 ②：`Mono.defer` 让每一步都在"订阅时刻"执行**
链是**懒组合**：`filter()` 被调用时"装配"Mono，真正跑要等订阅。`defer` 保证"取 filter + 造下一棒"发生在**每次订阅**时（不是装配时）—— 这也是**重试能重放整条下游链**的基础（26.4）。

**层次 ③：`Mono.empty()` 是链尾的"完成信号"**
它不是"啥也没干"，而是"**下游到此为止、整链的第 N 层顺利完成**"。这个完成信号会**逆流而上**触发各层的 post。

---

## 26.3 洋葱的时序：订阅顺流而下，完成逆流而上

以订阅 `[A, B, C]` 的链为例：

```
handle() → chain0.filter(exchange)            ← 订阅开始
   │ defer 出 A，造 chain1
   ▼ A.filter(ex, chain1)                     ← A-pre 在此执行（同步部分）
   │        └─ 内部调用 chain1.filter(ex)      ← 只是"装配"后续（defer 尚未执行）
   ▼ （A 返回 Mono 被订阅）→ defer 出 B，造 chain2
   ▼ B.filter(ex, chain2)                     ← B-pre
   ▼ ... C-pre
   ▼ 链尾 Mono.empty() 完成 ─────────────┐
                                          │
   ┌──────────────────────────────────────┘
   ▼ 完成信号逆流：C 的 then/doOnSuccess → B 的 → A 的   ← C-post、B-post、A-post
   ▼ 整链 Mono<Void> 完成 → WebFlux 写响应
```

两个对照：

- **与 Servlet `FilterChain.doFilter` 同构**（也是洋葱）——区别是 SCG 用**懒执行的 Mono 组合**表达，而不是同步调用栈；pre/post 天然对应"订阅前逻辑"与"完成回调"
- **post 阶段不是框架切出来的** —— 框架只做两件事：给你"下一棒"、在链尾发完成信号。**post 是你自己把代码写在 `chain.filter(...)` 后面**（`.then` / `.doOnSuccess` / `.doFinally`）。不想写 post 就直接 `return chain.filter(exchange);`（StripPrefix/AddRequestHeader 均如此）

---

## 26.4 三个语义细节（面试爱问）

### ① 短路：不调 `chain.filter` 就结束

```java
// 伪代码：鉴权失败
if (!authorized) {
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    return exchange.getResponse().setComplete();      // ★ 不碰 chain → 后面的过滤器全不执行
}
```

- 这是"**过滤器即关卡**"的实现：后置的转发过滤器根本不会跑（xhs 的 `GatewayAuthFilter` 就是这条路径，L32/L41）
- 反过来，`return Mono.empty();` 也表示"到此为止"：对框架来说完成信号与"正常走完"相同，区别只在**语义**（你主动停在半路）

### ② 错误：onError 逆流而上

- 链中任何一层 `onError` → **向上冒泡**（A 能看到 B/C 的错误）
- 正常 post（`then`）**不执行**，但 `doFinally` 会执行（含 cancel 场景）—— 清理逻辑要挂 `doFinally`
- 网关的错误最终由 WebFlux 错误处理接管（L18 的 `ErrorWebExceptionHandler` 链）

### ③ 多次订阅：Retry 就是"重放整条下游链"

- `RetryGatewayFilterFactory` 的骨架：`chain.filter(exchange)` →（可选）`retryWhen(...)` →（可选）`repeatWhen(...)`，`Mono.fromDirect` 收尾 —— 重试 = **重订阅** `chain.filter(...)` 这条 Mono（完整版见 L27 §27.8）
- 因为 chain 对象**无状态**（只是 index 引用）+ `Mono.defer` 每次订阅重新取 filter —— **整段下游链被完整重放**（L27 过滤器家族详）
- ⚠️ 由此的实践约束：下游过滤器会**被跑多次**，副作用要幂等（或避开 Retry 之后放副作用过滤器）

---

## 26.5 每请求一条链 vs 单例过滤器

```java
// FilteringWebHandler.handle —— 每个请求：
return new DefaultGatewayFilterChain(combined).filter(exchange);   // 链对象每请求新建（index 是请求级状态）
```

- **filter 实例是单例**：工厂 `apply(config)` 在编译期产出一个对象，之后**所有请求共用**（DemoHeader 的实例就一直活在 Route 里）
- 因此：**过滤器不能把请求状态存在自己的字段里** —— 状态要放 exchange 属性 / Reactor Context（L12/L18 的知识回收）

---

## 26.6 掌握边界（值不值得深挖）

> 本课定位：**机制型（中轻）**——记住"pre 顺流 / post 逆流 + post 是自己写的"即可；链源码首过在 L17 §17.5.2，细枝末节用到再查。

| 内容 | 口径 |
|---|---|
| 链源码逐行 | ❌ L17 §17.5.2 已给，本课只加"为什么" |
| **pre/post 的时序与"post 是谁定义的"** | ✅ **本课唯一 🔴** |
| 短路 / 错误逆流 / 重试重放 | ✅ 要会（面试三连） |
| `Mono.defer` 在这里的意义 | ⚪ 知道"订阅时刻执行 + 支持重订阅"即可 |
| cancel 时各操作符的精确行为 | ❌ 用到再查（L11 背压课有底子） |

---

## 26.7 Self-Test

1. `[A, B, C]` 三个过滤器的完整执行顺序（含 pre/post）？
2. post 阶段是谁定义的？为什么 StripPrefix 没有 post？
3. 一个过滤器不调用 `chain.filter(exchange)` 会发生什么？有哪两种"到此为止"的写法？
4. `Mono.defer` 在链里起什么作用？去掉它最直接的影响是什么？
5. 为什么 chain 每请求 `new`、而 filter 是单例？这给写过滤器的你什么约束？
6. 中途发生错误时：谁能看到错误？正常 post 会执行吗？`doFinally` 呢？

---

## 26.8 Open Questions

- [ ] 同一个 filter 对 `chain.filter` 调两次（除 Retry 外）的语义 —— 未实测（下游会被跑两次？链对象可复用吗）。
- [ ] 请求被 cancel 时，各层 `doFinally` / post 操作符的精确触发顺序 —— 未实测（L11 有理论底子）。
- [x] `NettyWriteResponseFilter`（order -1）如何利用"最外层 post"实现响应写回 —— **L27 §27.5 已回收**（pre 为空 + `then(Mono.defer(...))`，body 在链尾完成之后才写）。
