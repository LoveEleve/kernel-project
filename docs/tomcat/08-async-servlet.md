# 面试题详解 08 | 异步 Servlet（AsyncContext / 状态机 / 超时与错误）

> 难度：★★★★☆ ｜ 频率：★★★★☆ ｜ 区分度：高（状态机 + 超时精度 + Spring 落点，一问三层）
> 关键词：AsyncContext、startAsync、complete、dispatch、AsyncStateMachine、TIMING_OUT、setErrorState、inProgressAsyncCount、WebAsyncManager、Callable、DeferredResult、SseEmitter、spring.mvc.async.request-timeout

---

## 问题

**异步 Servlet 是什么？`request.startAsync()` 之后发生了什么（容器线程去哪了）？Tomcat 内部状态机怎么流转？超时和错误怎么处理、默认超时多少、精度如何？Spring MVC 的 `Callable`/`DeferredResult`/`SseEmitter` 和它是什么关系？**

---

## 30 秒简洁回答

> Servlet 3.0 异步：容器线程调 `request.startAsync()` 后**立刻返回**（线程还给池子），业务在别的线程处理；完成后 `AsyncContext.complete()`（或 `dispatch()` 再走一遍容器）让容器把响应写完。Tomcat 侧由 `AsyncStateMachine` 管状态（`STARTING/STARTED/TIMING_OUT/COMPLETING/DISPATCHING/ERROR…`，`AsyncContextImpl` 是 Servlet 侧门面），并用 `inProgressAsyncCount` 计数（**07 篇优雅停机等的就是它**）。超时默认 **30s**（`Connector.asyncTimeout :131`），且**不是精确定时器**——由 `AbstractProtocol.startAsyncTimeout` **每秒轮询一次**（`scheduleAtFixedRate(…,1,1,SECONDS)`），所以实际触发 = 配置值 + 最多 ~1s；超时/异常若无人处理（listener 没 `complete/dispatch`）→ 容器兜底错误页（500，`setErrorState` 里按 SRV.2.3.3.3 走 error dispatch）。Spring MVC 的 `Callable`/`DeferredResult`/`SseEmitter` 全部落在 `WebAsyncManager` → `StandardServletAsyncWebRequest.startAsync()` 上，超时用 `spring.mvc.async.request-timeout`（不配则用容器默认）。

---

## 展开回答（高级开发级）

### 1）Servlet API 侧：AsyncContext 就 6 个动作

`jakarta.servlet.AsyncContext`（`AsyncContext.java`）：

```
startAsync（在 ServletRequest 上）→ 进入异步模式
complete()   :111  业务完成，容器写响应、结束请求
dispatch()   :79   重新派发回容器（可带路径 :92/:106）
setTimeout() :153 / getTimeout() :160
addListener():125  onStartAsync/onTimeout/onError/onComplete
start(Runnable) :118  让容器线程池跑一个任务
hasOriginalRequestAndResponse() :69
```

Tomcat 实现入口：`catalina/connector/Request.startAsync()`（`:1503`/`:1508`）→ `asyncContext = new AsyncContextImpl(this)`（`:1516`）。

### 2）Tomcat 内部：AsyncStateMachine 状态机

`org.apache.coyote.AsyncStateMachine`（文件头 `:35-126` 有一张完整状态转移图）：

```
DISPATCHED(同步态) ⇄ STARTING → STARTED ⇄ READ_WRITE_OP
STARTED → MUST_COMPLETE/COMPLETE_PENDING → COMPLETING → DISPATCHED
STARTED → MUST_DISPATCH/DISPATCH_PENDING → DISPATCHING → DISPATCHED
STARTED → TIMING_OUT（超时，等 complete/dispatch）
任意态 → ERROR（异常）
```

关键方法：`asyncStart :244`、`asyncOperation :258`、`asyncComplete :307`、`asyncTimeout :350`（置 `TIMING_OUT :352`）、`asyncDispatch :366`、`asyncDispatched :409`、`asyncError :420`、`asyncRun :455`、`recycle :500`。

计数语义（`AsyncContextCallback` javadoc `:33-46`）：**进入非 DISPATCHED 态 +1、回到 DISPATCHED 态 -1**；increment 在 `AsyncContextImpl.setStarted :309`，decrement 在 `AsyncStateMachine :286/:294/:412`——这就是 07 篇停机轮询的 `StandardContext.getInProgressAsyncCount()`。

### 3）超时：默认 30s + 每秒轮询（精度 ±1s）

- 默认值：`Connector.asyncTimeout = 30000`（`:131`；`AsyncContextImpl :73-74` 注释"Default of 30000 (30s) is set by the connector"）；
- **检查机制**：`AbstractProtocol.startAsyncTimeout`（`:655`）用工具线程池 `scheduleAtFixedRate(…, 1, 1, TimeUnit.SECONDS)`（`:665-670`）扫 `waitingProcessors`（字段 `:86`），逐个 `AbstractProcessor.timeoutAsync(now)`（`:686-693`）→ 超时才进 `AsyncStateMachine.asyncTimeout()`；
- **结论**：异步超时**不是精确定时器**，实际触发 ≈ 配置值 + 1s（固定周期轮询；lab 预热后 9 次实测偏差 996~1001ms）——生产上不要依赖秒级精度，测试断言要留余量。

超时后的处理链：`AsyncContextImpl.timeout() :121-146` 逐个 fire `onTimeout`；listener 里 `complete()/dispatch()` 则状态离开 `TIMING_OUT`；**没人处理 → 状态留在 TIMING_OUT，容器按错误处理（500 错误页）**。

### 4）错误：setErrorState 与 SRV.2.3.3.3

`AsyncContextImpl.setErrorState(t, fireOnError)`（`:396-465`）：

1. `ActionCode.ASYNC_ERROR` 置状态机为 ERROR；
2. fire `onError`（listener 可 `dispatch()/complete()` 接管）；
3. 仍处 ERROR → **设 500**、走 Host 的 `StandardHostValve.throwable(...)` 错误页机制（源码注释直接引用 **SRV.2.3.3.3 "error dispatch"**）；错误页还没 `complete()` → 兜底 `complete()`。

### 5）Spring MVC 落点：Callable / DeferredResult / SseEmitter

```
@RequestMapping 返回 Callable/DeferredResult/SseEmitter
  → WebAsyncManager（spring-web …/async/WebAsyncManager.java）
      · startCallableProcessing :296/:311      · startDeferredResultProcessing :406
      · startAsyncProcessing :485 → StandardServletAsyncWebRequest.startAsync() :496
      · 默认拦截器 :72/:75（TimeoutCallableProcessingInterceptor / TimeoutDeferredResultProcessingInterceptor）
  → 超时：TimeoutCallableProcessingInterceptor :44 → new AsyncRequestTimeoutException()
          TimeoutDeferredResultProcessingInterceptor :42 → setErrorResult(AsyncRequestTimeoutException)
          DeferredResult 自带 timeoutResult 时优先生效（DeferredResult :310-330，timeoutResult.get() :320）
```

- 超时配置：`spring.mvc.async.request-timeout`（`WebMvcProperties.Async.requestTimeout :192-205`）→ `WebMvcAutoConfiguration:239-242`（`configurer.setDefaultTimeout(...)`）；**不配置则用容器默认（Tomcat 30s）**；
- `AsyncRequestTimeoutException` 实现了 `ErrorResponse`、状态码 **503**（`:38/:44-46`）——但要 **`ResponseEntityExceptionHandler`（@ControllerAdvice）** 才会映射成 503（映射链：异常分支 `:130/:175-176` → `handleAsyncRequestTimeoutException :419-423` → 用异常自带 503）；默认 `DefaultHandlerExceptionResolver.handleAsyncRequestTimeoutException`（`:498-502`）**直接返回 null**，所以裸用（无 advice）时客户端看到的是 **500 错误页**（lab 实测，见下）。

### 6）异步与线程/停机

- `startAsync()` 后容器线程退出 → `StandardWrapperValve` 的 `finally` **无条件** deallocate（`:212/:220-222`）→ 异步等待期 `countAllocated == 0`，只剩 `inProgressAsyncCount` 拦着停机（07 篇）；
- 所以 **SSE / 长轮询会一直拖住优雅停机**，直到超时（`timeout-per-shutdown-phase`）或客户端断开。

---

## 进一步回答（架构师层级）

- **异步不是"更快"，是"不占容器线程"**：同步慢 IO 会占满 `maxThreads`；异步把等待移出容器线程池——但如果你把 `Callable` 丢进另一个小池子，瓶颈只是换了个地方（要看下游是否真异步/非阻塞）。
- **Tomcat 的 `READ_WRITE_OP`**：Servlet 3.1 非阻塞 IO（`ReadListener/WriteListener`）期间的状态——SSE/大响应流式写出都经过它。
- **超时精度**：每秒轮询意味着 30s 超时实际可能 30.9s 才触发；反过来，压测里"超时后 QPS 恢复"的时间点也有 ~1s 抖动。
- **未处理超时的代价**：错误页 dispatch 会再占一次容器线程/一次请求处理；SSE 场景要给 `onTimeout/onError` 明确兜底，否则连接体验是 500。
- **Spring 选型**：`Callable`（简单，Spring 提供执行线程）→ `DeferredResult`（完全自己控制何时/在哪个线程 setResult，适合 MQ/事件驱动）→ `SseEmitter`（长连接推送，注意心跳与超时）。
- **与 07 篇联动**：优雅停机的 `isActive` 两个计数（`countAllocated` + `inProgressAsyncCount`）正是"同步请求 + 异步请求"两代模型；排查"停不下来"先看是不是有 SSE/长轮询。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"线程怎么还回去、响应谁来写、超时错误谁兜底"三件事；
- **回答顺序**：API（startAsync/complete/dispatch）→ 状态机（STARTED/TIMING_OUT/COMPLETING/ERROR + 计数）→ 超时（30s + 每秒轮询）→ 错误（setErrorState/SRV.2.3.3.3）→ Spring 落点（WebAsyncManager + 超时属性）；
- **一句话收束**："startAsync 把容器线程还回去，complete/dispatch 再把响应写回来；超时/错误没 listener 接管就是容器兜底 500——Spring MVC 的三件套只是这套机制的上层封装。"

## 必记关键词

`AsyncContext` ｜ `startAsync/complete/dispatch` ｜ `AsyncStateMachine` ｜ `TIMING_OUT` ｜ `setErrorState` ｜ `inProgressAsyncCount` ｜ `WebAsyncManager` ｜ `Callable/DeferredResult/SseEmitter` ｜ `spring.mvc.async.request-timeout` ｜ `AsyncRequestTimeoutException`

## 必须明确的边界

- 异步**只释放容器线程**，业务线程池自己负责；CPU 密集任务用异步没有收益；
- Tomcat 超时默认 **30s** 且**每秒轮询**（精度 ±1s），不是精确调度；
- 超时/错误**没有 listener 处理 = 容器兜底 500**（不是"自动取消"）；
- `AsyncRequestTimeoutException` 状态码是 503，但**默认 resolver 不映射**（返回 null）→ 裸用是 500；配 `ResponseEntityExceptionHandler` 才是 503；
- `countAllocated` 在异步等待期是 0（deallocate 无条件），异步靠 `inProgressAsyncCount`；
- SSE 长连接会拖住优雅停机（07 篇），生产要配心跳/超时。

## 常见错误

- 以为 `startAsync()` 后容器线程还在等（其实已释放）；
- 把异步当性能银弹（线程只是换池子）；
- 认为超时是精确的；
- 以为超时后 Spring 一定返回 503（裸用是 500）；
- 分不清 Callable 与 DeferredResult 的线程模型。

## 自测要求

- **30 秒**：API 三动作 + 状态机 + 超时/错误兜底 + Spring 落点；
- **3 分钟**：能画状态转移图（至少 STARTED/TIMING_OUT/COMPLETING/DISPATCHING/ERROR）；
- 能回答追问 1~4。

---

## 追问与参考回答

**追问 1：`startAsync()` 之后容器线程去哪了？**
> 立刻还给线程池（`StandardWrapperValve` 的 finally 无条件 deallocate，`:212/:220-222`）；业务线程完成时 `complete()`/`dispatch()` 让容器（可能是另一个线程）把响应写完。

**追问 2：异步超时默认多少？精度如何？**
> Tomcat 默认 **30s**（`Connector.asyncTimeout :131`）；检查是**每秒轮询**（`AbstractProtocol.startAsyncTimeout :655-670` 的 `scheduleAtFixedRate(…,1,1,SECONDS)`）→ 实际触发 = 配置值 + 最多 ~1s。Spring 侧可用 `spring.mvc.async.request-timeout` 覆盖（不配则容器默认）。

**追问 3：超时了但没人处理会怎样？**
> 状态停在 `TIMING_OUT` → 容器按错误处理：`AsyncContextImpl.setErrorState`（`:396-465`）置 500、走错误页（源码注释引 SRV.2.3.3.3），错误页仍未 `complete()` 则兜底 complete。listener 在 `onTimeout` 里 `complete()/dispatch()` 才能接管（lab 实测：接管=200，不接管=500）。

**追问 4：Callable 和 DeferredResult 有什么区别？**
> `Callable`：Spring 用 async task executor（默认 `SimpleAsyncTaskExecutor`）执行，简单；`DeferredResult`：由业务在任意线程 `setResult`（MQ/事件驱动），可设 `timeoutResult` 兜底；`SseEmitter` 是 `ResponseBodyEmitter` 的长连接变体。三者都走 `WebAsyncManager` → `startAsync`。

---

## 示例与使用说明（lab 复现，`xhs-demo/tomcat-lab`）

```java
// 1) DeferredResult + timeoutResult：超时返回兜底（200）
@GetMapping("/deferred-timeout")
public DeferredResult<String> deferredTimeout(@RequestParam(defaultValue = "1000") long timeoutMs) {
    DeferredResult<String> result = new DeferredResult<>(timeoutMs, "deferred timeout fallback");
    return result;   // 故意不 setResult，等超时
}

// 2) 原生 AsyncContext：listener 接管超时（200）vs 不接管（容器 500）
AsyncContext async = request.startAsync();
async.setTimeout(timeoutMs);
async.addListener(new AsyncListener() {
    @Override public void onTimeout(AsyncEvent event) throws IOException {
        event.getAsyncContext().getResponse().getWriter().write("timeout handled by listener");
        event.getAsyncContext().complete();
    }
    // onStartAsync/onError/onComplete 略
});
// worker 线程：sleep(workMs) → write → complete()
```

实测（JDK17 / Boot 3.2.5 内嵌 Tomcat 10.1.20，`spring.mvc.async.request-timeout=1s`）：

```text
deferred-timeout（timeoutMs=1000）        → 200 "deferred timeout fallback"  elapsed≈2004ms（1s 超时 + ~1s 轮询粒度）
callable-timeout（/async 5s，1s 超时）     → 500（Boot 错误页）                elapsed≈2011ms
raw-async(ok)                            → 200 "raw done after 300ms"
raw-async(timeout 500ms 不接管)           → 500（错误页）                     elapsed≈1031ms
raw-async(timeout 500ms + listener 接管)  → 200 "timeout handled by listener" elapsed≈980ms
worker 在容器接管后收尾 → IllegalStateException（AsyncContext already completed）
```

## 面试官评分点

**通过标准**
- 能讲 startAsync/complete/dispatch 与容器线程释放；
- 能讲状态机关键状态与计数；
- 能讲超时默认值/精度与"未处理→500"。

**加分项**
- 能带行号讲 `AbstractProtocol.startAsyncTimeout` 的每秒轮询；
- 能讲 `setErrorState` 与 SRV.2.3.3.3；
- 能讲 Spring 超时链（拦截器 + `AsyncRequestTimeoutException` 503 vs 默认 resolver 500）；
- 能联系 07 篇停机（inProgressAsyncCount）。

**危险信号**
- 以为异步让线程还在等；
- 认为超时精确/自动取消；
- 分不清 Spring 三件套与 AsyncContext 的关系。

## 实战练习

1. 跑 lab 的 `AsyncServletTest`（5 个用例），对照 `[lab]` 输出与状态码；
2. 把 `/raw-async?mode=timeout` 的 listener 加上，观察 500→200 的变化；
3. 用 `/async?seconds=30` + 默认超时（不配 request-timeout）验证 30s 默认值与 ±1s 抖动；
4. 开 SSE（`/sse?seconds=60`）同时触发优雅停机，观察停机被拖到超时（07 篇）。

## 版本与来源

- Tomcat **10.1.34**：`java/jakarta/servlet/AsyncContext.java`（:69/:79/:111/:118/:125/:153/:160）、`catalina/connector/{Request,Connector}.java`（:1503/:1508/:1516、:131）、`catalina/core/AsyncContextImpl.java`（:73-74/:88/:97/:121-146/:151/:309/:372/:379/:396-465）、`coyote/AsyncStateMachine.java`（:35-126/:135-147/:244/:258/:286/:294/:307/:350/:366/:409/:412/:420/:455）、`coyote/AsyncContextCallback.java`（:33-46）、`coyote/AbstractProtocol.java`（:86/:655-670）、`coyote/AbstractProcessor.java`（:686-693）、`catalina/core/StandardWrapperValve.java`（:212/:220-222）、`StandardWrapper.java`（:558/:599/:606-607）；
- Spring Framework：`spring-web/.../async/{WebAsyncManager,DeferredResult,StandardServletAsyncWebRequest,TimeoutCallableProcessingInterceptor,TimeoutDeferredResultProcessingInterceptor,AsyncRequestTimeoutException}.java`（:296/:311/:406/:485/:496/:72/:75、:310-330、:185-206、:44、:42、:38/:44-46）、`spring-webmvc/.../{DefaultHandlerExceptionResolver,ResponseEntityExceptionHandler}.java`（:498-502、:130/:175-176/:419-423）；
- Spring Boot **3.5.16**：`WebMvcProperties.Async.requestTimeout`（:192-205）、`WebMvcAutoConfiguration`（:239-242）；
- lab：`xhs-demo/tomcat-lab`（`AsyncController` + `AsyncServletTest`，实测输出见上）；
- 本卷章节：12（Servlet 异步）、14（Spring Boot 桥）、02（NIOEndpoint 线程）；
- 规划：`Tomcat源码学习范围规划.md` T-2/T-5。

## 个人经历填充区（留空，按真实项目补）

- 项目里用过 SSE/长轮询吗？超时与断连怎么兜底的？
- 遇到过"优雅停机停不下来"或"异步超时后 500"吗？怎么定位的？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Spring Framework + Boot 3.5.16）；lab 实测数据为 2026-09-23 本机运行（Boot 3.2.5 / 内嵌 Tomcat 10.1.20）；项目经历请用真实案例填充。
