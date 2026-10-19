# 06 Context 与 Deadline：跨线程传播与超时

> 定位：gRPC 系列第 06 篇——覆盖 🔴 G-7（Context + Deadline）。
> lab：`scripts/06-deadline-context.sh`；证据：`grpc-lab/evidence/06-deadline-context.log`。
> 课程对照：《RPC实战与核心原理》19 分布式环境下如何快速定位问题（traceId 上下文）/ 06 gRPC 源码实战。

---

## 一、先看实验：三个观测

**① Context 跨线程（客户端）**——主线程 attach 一个值：

```
[lab-client] 主线程 Context = ctx-123
[lab-client] 裸线程 Context = null
[lab-client] 包装线程 Context = ctx-123
[lab-client] interceptor interceptCall Context = ctx-123
[lab-client] interceptor onClose Context = ctx-123
[lab-client] 调用完成 = hello lab from grpc-lab
```

**② Context 跨线程（服务端）**——拦截器用 `Contexts.interceptCall` 注入值：

```
server 侧：
[lab-server] interceptor Context.current = null
[lab-server] handler Context = server-ctx
[lab-server] 裸线程 Context = null
[lab-server] 包装线程 Context = server-ctx
```

**③ Deadline 传播与取消**（client deadline 500ms；server slow 2000ms）：

```
[lab-client] single -> FAIL in 525ms: StatusRuntimeException | DEADLINE_EXCEEDED: CallOptions deadline exceeded after 0.482960203s. ...
[lab-client] status = DEADLINE_EXCEEDED | CallOptions deadline exceeded after 0.482960203s. ...
server 侧：
[lab-server] SayHello #1 name=lab, 线程=grpc-default-executor-0
[lab-server] 检测到取消（deadline 到期），停止处理
```

三个观测回答：**Context 是什么（不是 ThreadLocal、也不上网线）、怎么跨线程传、deadline 怎么传到服务端并触发取消**。

---

## 二、朴素答案"Context 就是 ThreadLocal / Deadline 就是本地 timer"死在哪

| 误解 | 事实 |
|---|---|
| "Context = ThreadLocal" | ThreadLocal 跨线程就丢（实验①裸线程 `null`）；Context 通过**存储抽象 + 显式传播**（`currentContextExecutor`/`wrap`）跨线程 |
| "Context 会随请求传到服务端" | **Context 不过网线**：跨进程传的是 Metadata（第 04 篇）；Context 只在进程内传播 |
| "Deadline 只是本地超时 timer" | Deadline 会被写进 **`grpc-timeout` 头**传给服务端，到期**取消服务端上下文**（实验③） |

---

## 三、Context：进程内上下文（实验①②解读）

### 3.1 API 骨架

`Context`（`api/src/context/java/io/grpc/Context.java`）：

| API | 位置 | 作用 |
|---|---|---|
| `key(String)` | `:149` | 声明一个上下文 key |
| `current()` | `:171` | 当前上下文 |
| `withValue(k, v)` | — | 派生一个带值的新上下文（不可变） |
| `attach()` / `detach(old)` | `:426` / `:448` | 绑定到当前线程 / 恢复（必须成对，`try/finally`） |
| `currentContextExecutor(Executor)` | `:619` | 包装线程池：提交的任务带着当前上下文执行 |
| `wrap(Runnable)` | `:560` | 包装单个任务 |
| `isCancelled()` | `:461` | 上下文是否已被取消（deadline 到期/调用取消） |

### 3.2 两条传播路径

**① gRPC 内部：调用线程 → 回调线程。** 实验①里 `interceptor onClose` 也能看到 `ctx-123`——因为 `ClientCallImpl` 在创建调用时捕获了当时的上下文（`ClientCallImpl.java:84/:119`：`this.context = Context.current()`），所有回调都包装成 `ContextRunnable`（`ContextRunnable.java:25/:34-39`：`run()` 里 `context.attach()` … `finally context.detach(previous)`；回调点 `ClientCallImpl.java:199/221/602/638/705/753`），恢复上下文后再执行。

**② 应用跨线程：必须显式包装。** 实验①/②的对照：

```
[lab-client] 裸线程 Context = null
[lab-client] 包装线程 Context = ctx-123
```

（裸线程 = 新建的 Thread 不继承；包装线程 = `currentContextExecutor` 提交的任务带着上下文。）

服务端同理：拦截器用 `Contexts.interceptCall`（`api/src/main/java/io/grpc/Contexts.java:44-47`）把"带值的上下文"绑定到 handler 及其回调上——handler 里 `CTX_KEY.get()` 拿到 `server-ctx`；但 handler 里**裸开线程**照样 `null`。

### 3.3 用途

traceId、租户、超时预算、鉴权主体……这类"横切数据"放 Context（进程内），跨进程时再序列化进 Metadata（第 04 篇）——课程 19 的"一次请求全链路日志"就是这个组合。

---

## 四、Deadline：附着在调用链上的超时（实验③解读）

### 4.1 从 API 到线缆

- 客户端：`stub.withDeadlineAfter(500, MILLISECONDS)`（`CallOptions.java:177`）→ `CallOptions.deadline`；
- **过线**：`AbstractClientStream` 把它写进 `grpc-timeout` 头（`AbstractClientStream.java:126-127`）：

```java
    headers.discardAll(TIMEOUT_KEY);
    headers.put(TIMEOUT_KEY, deadline.timeRemaining(TimeUnit.NANOSECONDS));
```

（头名常量 `grpc-timeout` 在 `GrpcUtil.java:187`。）

顺带解释实验③错误信息里的前缀：`effectiveDeadline = min(CallOptions 的 deadline, Context 的 deadline)`（`ClientCallImpl.java:425-428`），取两者中**更早**的那个；来源是 CallOptions 时错误写 "CallOptions deadline exceeded"，来源是 Context 时写 "Context deadline exceeded"（`ClientCallImpl.java:244-246/:255`）。

### 4.2 服务端：从 header 到取消

服务端收到请求时，`ServerImpl.createContext`（`ServerImpl.java:639-656`，节选）读这个头并构造**可取消上下文**：

```java
      Long timeoutNanos = headers.get(TIMEOUT_KEY);
      ...
      Context.CancellableContext context =
          baseContext.withDeadline(
              Deadline.after(timeoutNanos, NANOSECONDS, ticker),
              transport.getScheduledExecutorService());
```

到期后，服务端上下文被取消——业务通过 `Context.current().isCancelled()` 感知（实验③的 handler 每 50ms 检查一次，到期即停止）。

### 4.3 与 Dubbo 的对照（第 07 篇）

| 维度 | Dubbo（07 篇实测） | gRPC（本篇实测） |
|---|---|---|
| 超时语义 | 消费方等待上限；**timeout ≠ 取消**，provider 继续执行 | deadline 传播到服务端；到期**取消服务端上下文** |
| 服务端感知 | 默认无（除非业务自己查） | `Context.isCancelled()`（**协作式**：业务必须主动检查） |
| 剩余时间 | —— | `Deadline.timeRemaining()`（可做超时预算递减） |

关键限定词是**协作式**：gRPC 不会抢占线程，业务不检查 `isCancelled()`，慢任务照样跑完——所以"服务端停止处理"是业务代码配合的结果（实验③的 `return`）。

---

## 五、实验解读

| 观测 | 结论 |
|---|---|
| 裸线程 `null` vs 包装线程有值 | Context 不是 ThreadLocal：跨线程要 `currentContextExecutor`/`wrap` |
| interceptor onClose 看得到值 | gRPC 内部回调会恢复调用时捕获的上下文（`ClientCallImpl` 捕获 + `ContextRunnable`） |
| handler 看到 `server-ctx` | 服务端用 `Contexts.interceptCall` 绑定上下文 |
| client 525ms `DEADLINE_EXCEEDED` | deadline 写进 `grpc-timeout` 头 |
| server 检测到取消并停止 | 服务端上下文到期取消（协作式，业务查 `isCancelled`） |

---

## 六、生产陷阱

1. **Context 不过网线**：跨进程用 Metadata（04 篇）；Context 只做进程内传播；
2. **线程池/异步回调必须包装**：裸线程丢 Context（实验）；用 `currentContextExecutor` 或 `Context.wrap`；
3. **attach/detach 成对**：漏 detach 会污染线程后续任务（`try/finally`）；
4. **deadline 是整条调用链的预算**：`effectiveDeadline = min(CallOptions, Context)`（`ClientCallImpl.java:425-428`）——服务端把入站 deadline 放进 Context 后，**出站子调用会自动继承**（无需手工传）；重试的每次尝试也写同一个绝对 deadline 的剩余时间（`AbstractClientStream:127`，第 02 篇）；嵌套调用要预留余量（`Deadline.timeRemaining()`）；
5. **服务端取消是协作式的**：业务不检查 `isCancelled()`，deadline 到了也白搭；长循环/慢查询要主动检查。

---

## 附录 A：面试速答（附带）

> Context 是进程内上下文（key/current/withValue/attach/detach），不是 ThreadLocal：跨线程要用 currentContextExecutor/wrap（裸线程会丢）；也**不过网线**——跨进程传的是 Metadata。gRPC 内部回调（interceptor 的 onClose 等）会恢复调用时捕获的上下文（ClientCallImpl 捕获 + ContextRunnable）。服务端用 Contexts.interceptCall 把上下文绑定到 handler。Deadline：withDeadlineAfter → 写进 grpc-timeout 头（AbstractClientStream）→ 服务端 ServerImpl.createContext 读头构造带 deadline 的 CancellableContext → 到期取消服务端上下文；业务用 Context.isCancelled() 感知（协作式取消，非抢占）。对比 Dubbo：Dubbo timeout 只是消费方等待上限（timeout ≠ 取消）；gRPC deadline 会传播并取消服务端上下文（前提是业务配合检查）。

## 附录 B：实验与复现

```bash
cd grpc-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -q compile
bash scripts/06-deadline-context.sh   # 输出见 evidence/06-deadline-context.log
```

| 观测点 | 结果 |
|---|---|
| 客户端 Context | 主线程 ctx-123 / 裸线程 null / 包装线程 ctx-123 / interceptor onClose ctx-123 |
| 服务端 Context | handler server-ctx / 裸线程 null / 包装线程 server-ctx |
| Deadline | client FAIL 525ms `DEADLINE_EXCEEDED`；server 检测到取消并停止 |

## 附录 C：源码坐标（gRPC-Java 1.83.1 + lab）

| 内容 | 坐标 |
|---|---|
| Context API | `api/src/context/java/io/grpc/Context.java:149`（key）、`:171`（current）、`:426/:448`（attach/detach）、`:461`（isCancelled）、`:560`（wrap）、`:619`（currentContextExecutor） |
| 服务端上下文绑定 | `api/src/main/java/io/grpc/Contexts.java:44-47`（interceptCall） |
| 客户端上下文捕获/回调恢复 | `core/src/main/java/io/grpc/internal/ClientCallImpl.java:84/:119`（`this.context = Context.current()`）、`:199/221/602/638/705/753`（回调 ContextRunnable）；`ContextRunnable.java:25/:34-39`（attach/detach） |
| deadline 来源 | `ClientCallImpl.java:244-246/:255`（来源判定与命名）、`:425-428`（`min(CallOptions, Context)`） |
| Deadline API | `api/src/main/java/io/grpc/CallOptions.java:177/:182`（withDeadlineAfter）；`api/src/context/java/io/grpc/Deadline.java:69/:95`（after）、`:122`（isExpired）、`:176`（timeRemaining） |
| 过线（grpc-timeout） | `core/src/main/java/io/grpc/internal/AbstractClientStream.java:126-127`；头名 `core/src/main/java/io/grpc/internal/GrpcUtil.java:187` |
| 服务端构造取消上下文 | `core/src/main/java/io/grpc/internal/ServerImpl.java:639-656`（createContext） |
| lab | `scripts/06-deadline-context.sh`；`ClientApp -Dlab.context/-Dlab.deadline.ms`；`ServerApp -Dlab.context` |
