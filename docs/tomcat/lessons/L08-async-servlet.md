# Lesson 08: 异步 Servlet — 把 Worker 还回去

> 课程索引：`../学习清单.md` → Stage 2 · Lesson 08
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`）；内嵌运行版本 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）
> 来源：`webapps/docs/config/http.xml`（asyncTimeout）、Servlet API `jakarta.servlet.AsyncContext`、Spring Framework `WebAsyncManager` javadoc、Spring Boot `WebMvcProperties.Async` javadoc；lab 实测（`AsyncServletTest`）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L08-5 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 回答："异步请求怎么把 Worker 还回去"、超时/错误如何收尾、为什么停机要单独计它在途（L07） |
| 前置 | L03（Worker 执行整条链）、L07（在途计数：`inProgressAsyncCount`） |
| 不涉及 | 源码逐行（Stage 3）；Spring MVC 的完整异步体系（只讲三个入口） |
| 下一课 | L09 调参：线程 / 连接 / 超时 |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **异步的动作**：`startAsync()` 把请求转入异步模式（拿到 `AsyncContext`）；**容器线程在业务方法返回后归还**（严格说不是"调用瞬间"归还——`startAsync()` 只是把请求标记为异步）；业务在**另一个线程**完成后调 `complete()`（直接写完响应）或 `dispatch()`（回容器再走一遍）。
2. **Tomcat 侧的计数与状态**：异步期间请求**不在** `countAllocated` 里，而在 **`inProgressAsyncCount`**（L07 优雅停机等的就是它）；内部由异步状态机管理（启动中/已启动/超时中/完成/分发中/错误）。
3. **超时与错误**：默认超时 **30s**（Servlet 规范默认）；超时/异常**先回调监听器**（`onTimeout`/`onError`），**没人处理则容器兜底**（500 错误页）；Spring MVC 把 `Callable`/`DeferredResult`/`SseEmitter` 映射到这套机制上。

---

## 2. 官方原文

### 2.1 异步超时的默认值（`config/http.xml` · `asyncTimeout`，节选）

> The default timeout for asynchronous requests in milliseconds. If not specified, this attribute is set to the **Servlet specification default of 30000 (30 seconds)**.

**翻译**：异步请求的默认超时（毫秒）；未指定时采用 **Servlet 规范默认值 30000（30 秒）**。

- **要点**：30s 来自**规范默认**；Boot/Spring MVC 可以覆盖（见 2.3）。

### 2.2 容器侧的 API（Servlet API `jakarta.servlet.AsyncContext`）

> Provides the context for asynchronous request handling（@since Servlet 3.0）

**翻译**：提供异步请求处理的上下文。

- **接口动作**：`startAsync()`（在请求上调用）→ `complete()` / `dispatch()` / `setTimeout()` / `addListener()`；
- `addListener` 的回调：`onStartAsync` / `onTimeout` / `onError` / `onComplete`——**超时与错误的第一处理点**。

### 2.3 框架侧模型与配置（javadoc 口径）

> **WebAsyncManager**: The central class for managing asynchronous request processing ... An async scenario starts with request processing as usual in a thread (**T1**). Concurrent request handling can be initiated by calling `startCallableProcessing` or `startDeferredResultProcessing`, both of which produce a result in a separate thread (**T2**). The result is saved and the request **dispatched to the container**, to resume processing with the saved result in a third thread (**T3**).
>
> 引用说明：javadoc 中两个方法名为链接（渲染为短名），方法签名从略。

**翻译**：`WebAsyncManager` 是 Spring 管理异步请求的核心类——异步场景从容器线程 **T1** 开始；通过 `startCallableProcessing`/`startDeferredResultProcessing` 在**另一个线程 T2** 产生结果；结果保存后**把请求重新 dispatch 回容器**，在**第三个线程 T3** 继续处理。

> **`spring.mvc.async.request-timeout`**: Amount of time before asynchronous request handling times out. If this value is not set, the **default timeout of the underlying implementation** is used.

**翻译**：异步处理超时时间；未设置时使用**底层实现（容器）的默认值**（即 30s）。

---

## 3. 同步 vs 异步（线程视角）

```
同步：Worker ──[解析→路由→业务(阻塞等待)──────────→写响应]── 归还
异步：Worker ──[解析→路由→startAsync→方法返回]── 归还（此后不再占用）
                    │
                    ├─ 业务线程 T2（如 Spring 的 task-1）：做远程调用/等待/推送
                    │
                    └─ 完成：complete()（直接写） 或 dispatch()（T3 回容器再走一遍）
```

- 收益：IO 等待型业务不再占着 Worker（吞吐提升）；
- 代价：状态机 + 回调 + 超时/错误处理复杂度（本课重点）。

---

## 4. Tomcat 侧的计数与状态（机制级）

| 阶段 | 请求在哪里 | 计数 |
|---|---|---|
| 同步处理中 | Worker 执行 | `countAllocated` |
| `startAsync()` 后、完成前 | 异步状态机 | **`inProgressAsyncCount`** |
| dispatch 回容器（T3） | 再次占用一个 Worker | 该次 dispatch 期间 `countAllocated` 重新计入；`inProgressAsyncCount` 在进入"已 dispatch"态时已减 1 |

状态机（概念级）：

```
STARTED（异步进行中） → COMPLETE（直接完成）/ DISPATCHING（回容器）
                     → TIMING_OUT（超时等待处理）
                     → ERROR（异常）
```

> 实现细节（源码口径，Stage 3 展开）：超时检查是**周期性**的（约每秒一次），所以实际触发时间 = 配置值 + 最多约 1s——lab 实测吻合（见 §5）。

---

## 5. 超时与错误（机制级 + lab 实测）

**处理顺序**（超时/错误发生时）：

```
① 回调 AsyncListener（onTimeout / onError）
     · 监听器 complete() / dispatch() → 接管成功
② 无人处理 → 容器兜底：TIMING_OUT / ERROR 状态 → 置 500 并走错误页处理
     · 独立形态：ErrorReportValve 生成 HTML 错误页（L04）
     · 内嵌 Boot：由 Boot 的错误页（/error）处理——lab 实测返回 Boot 的 JSON 错误体
```

**Spring MVC 三个入口都落到这套机制**：

| 入口 | 用途 | 超时行为 |
|---|---|---|
| `Callable` | 简单异步（Spring 提供执行线程） | 默认无兜底 → 走容器兜底（裸用 500）；`CallableProcessingInterceptor` 可提供超时结果（Spring 异步拦截器，**≠ MVC Interceptor**） |
| `DeferredResult` | 业务线程自行 setResult | 可配置 `timeoutResult`（超时返回兜底结果） |
| `SseEmitter` | 长连接推送 | 超时/断连触发回调 |

**lab 实测**（`AsyncServletTest`，`request-timeout=1s`）：

| 场景 | 结果 |
|---|---|
| DeferredResult + timeoutResult（1s） | **200** + 兜底文本；耗时 ≈2004ms（1s 超时 + 约 1s 轮询） |
| Callable 超时（1s 超时、5s 业务） | **500**（无兜底）；耗时 ≈2011ms |
| 原生 AsyncContext：超时不处理 | **500**（错误页）；≈1031ms |
| 原生 AsyncContext：onTimeout 里 complete() | **200**（"timeout handled by listener"）；≈980ms |

> 面试注意（机制边界的细节）：`AsyncRequestTimeoutException` 的规范状态码是 503，但**默认异常解析器不映射**（返回 null）→ 裸用是 500；配置了 `ResponseEntityExceptionHandler`（@ControllerAdvice）才是 503。

---

## 6. 异步与停机（回扣 L07）

- 异步请求在途时，**`countAllocated` 可能为 0**，但 `inProgressAsyncCount > 0` → 优雅停机仍会等待（L07）；
- lab 实测：异步请求（3s）在途时 `close()` 阻塞 ≈3s，请求正常返回 200——说明停机等的计数**同时覆盖同步与异步**；
- SSE/长轮询会一直算"在途"，直到超时或断开——生产要配心跳/超时（L09/L13 讨论）。

---

## 7. 术语对照（本课汇总）

| 术语 | 中文 | 一句话 |
|---|---|---|
| startAsync | 启动异步 | 把请求转入异步模式、归还 Worker |
| AsyncContext | 异步上下文 | 异步请求的操作入口 |
| complete / dispatch | 完成 / 分发 | 直接写响应 / 回容器再走一遍 |
| AsyncListener | 异步监听器 | onTimeout/onError/onComplete 回调 |
| inProgressAsyncCount | 异步在途计数 | 停机等待的依据之一 |
| asyncTimeout | 异步超时 | 默认 30000ms（规范默认） |
| Callable / DeferredResult / SseEmitter | Spring 三入口 | 都落到同一套机制 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. `startAsync()` 之后 Worker 去哪了？响应由谁写？**
> 参考答案：容器线程在业务方法返回后归还（`startAsync()` 只是标记异步）；业务在另一个线程（T2）完成，之后由 `complete()` 直接写响应，或 `dispatch()` 回容器在 T3 继续处理并写响应。

**Q2. 异步请求在途时，两个计数分别是什么？**
> 参考答案：异步期间 `countAllocated` 已归零（容器线程退出时 deallocate），在途体现在 `inProgressAsyncCount`；这也是优雅停机要同时看两个计数的原因（L07）。

**Q3. 异步超时默认多少？没人处理会怎样？**
> 参考答案：默认 30000ms（Servlet 规范默认）；超时先回调监听器，没人 complete/dispatch 则容器兜底（500 错误页）。

**Q4. DeferredResult 的超时兜底和 Callable 有什么不同？**
> 参考答案：DeferredResult 可配置 `timeoutResult`（超时返回兜底结果，lab 实测 200）；Callable 默认没有兜底，超时走容器兜底（裸用 500），除非拦截器/异常处理器介入。

**Q5. 为什么"异步请求"仍然会被优雅停机等待？**
> 参考答案：因为优雅停机同时看 `countAllocated` 与 `inProgressAsyncCount`（L07）；异步请求计入后者，未被等到就不会拆内部。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：跑 `AsyncServletTest`；或起 lab 后 `curl "/async?seconds=30"` 观察默认 30s 超时与 ±1s 抖动。
- 下一步：**L09 调参**——线程、连接、超时三类参数怎么定，满了各是什么现象。
