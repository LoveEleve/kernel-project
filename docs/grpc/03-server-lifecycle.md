# 03 服务端：ServerBuilder → Listener 生命周期

> 定位：gRPC 系列第 03 篇——覆盖 🔴 G-2（Server 服务端）。
> lab：`scripts/03-server-lifecycle.sh`；证据：`grpc-lab/evidence/03-server-lifecycle.log`。
> 课程对照：《RPC实战与核心原理》06 gRPC 源码实战。

---

## 一、先看实验：三个观测

**① 多服务注册**（一个 Server 同时服务 Greeter + Echo）：

```
[lab-client] blocking = hello lab from grpc-lab in 467ms
[lab-client] echo = echo lab from grpc-lab
server 侧：
[lab-server] started on 50051（services: Greeter + Echo）
SayHello #1
SayHello #2
SayHello #3
Echo name=lab
```

**② Listener 生命周期**（服务端探针，一次 unary 调用）：

```
[lab-server] listener startCall myxhs.lab.Greeter/SayHello
[lab-server] listener onMessage
[lab-server] listener onHalfClose
[lab-server] listener onComplete
```

**③ 优雅关闭**（调用进行中 SIGTERM）：

```
[lab-client] single = hello lab from grpc-lab in 1958ms
server 侧：
[lab-server] graceful shutdown, 等待 5000ms ...
[lab-server] shutdown 完成
关闭后新调用：
[lab-client] single -> FAIL in 190ms: StatusRuntimeException | UNAVAILABLE: io exception
```

三个观测回答：**一个 Server 怎么承载多个服务、业务方法是怎么被"事件"驱动的、关闭时在途请求怎么办**。

---

## 二、朴素答案"一个 Server = 一个服务"死在哪

两个直觉都要拆掉：

| 直觉 | 事实 |
|---|---|
| "Server 就是一个服务实例" | Server 是**容器**：承载多个 `ServiceDefinition`（`InternalHandlerRegistry` 持有服务列表），按**方法全名**分发 |
| "服务端收到请求后反射调用方法" | gRPC 服务端是**事件驱动**：请求被翻译成 `ServerCall.Listener` 上的回调（onMessage/onHalfClose），业务代码在回调里执行 |

对比 Dubbo 服务端（第 06/08 篇）：Dubbo 按 service key 找到 exporter，然后经过 Filter 链**反射调用**业务方法；gRPC 则通过生成的 `ServerCalls.asyncUnaryCall` 适配器把 `StreamObserver` 包装成 `ServerCallHandler`——两条路线，都是"找到方法 + 触发业务"。

---

## 三、装配：ServerBuilder → ServerImpl.start()

`ServerBuilder.build()`（`api/src/main/java/io/grpc/ServerBuilder.java:436`）→ `ServerImplBuilder.build()`（`core/src/main/java/io/grpc/internal/ServerImplBuilder.java:257`）→ `ServerImpl`。

`ServerImpl.start()`（`ServerImpl.java:180-192`）：

```java
  public ServerImpl start() throws IOException {
    synchronized (lock) {
      checkState(!started, "Already started");
      checkState(!shutdown, "Shutting down");
      // Start and wait for any ports to actually be bound.

      ServerListenerImpl listener = new ServerListenerImpl();
      transportServer.start(listener);
      executor = Preconditions.checkNotNull(executorPool.getObject(), "executor");
      started = true;
      return this;
    }
  }
```

`start()` 只做两件事：**绑定端口**（`transportServer.start`，真正监听在 Netty 层）和**取业务线程池**（`executorPool`，默认就是第 01 篇的 `grpc-default-executor`）。`addService` 注册的服务此时已经进了 `registry`（`ServerImplBuilder.java:143-144` 把服务加进 `registryBuilder`；`ServerImpl.java:146` 构造时 `build()`；容器见 `InternalHandlerRegistry.java:30/:32`）。

---

## 四、分发：从 HTTP/2 帧到方法

一个请求到达后，服务端按 **full method name**（形如 `myxhs.lab.Greeter/SayHello`）查表：

- `registry.lookupMethod(methodName, null)`（`ServerImpl.java:501`、`InternalHandlerRegistry.java:51`）——查不到时还有 fallback registry（`:545`），仍为 null 则回 `UNIMPLEMENTED: Method not found`（`:547-549`）；
- 找到 `ServerMethodDefinition` 后：把 HTTP/2 stream 包装成 `ServerCallImpl`（`ServerCallImpl.java:52`），并取出 `methodDef.getServerCallHandler()`（`ServerImpl.java:594`、`:668`）；
- handler 的来源：生成代码里 `sayHello` 的实现会调用 `ServerCalls.asyncUnaryCall(...)`（`stub/src/main/java/io/grpc/stub/ServerCalls.java:49`），把业务写的 `StreamObserver` 适配成 `ServerCallHandler`。

**方法全名是分发的唯一钥匙**——实验②的探针打印 `startCall myxhs.lab.Greeter/SayHello` 就是它在起作用。生产上遇到 `UNIMPLEMENTED`，先核对"客户端调用的全名"和"服务端注册的全名"。

---

## 五、Listener 生命周期（实验②的解读）

业务方法不是"被直接调用"，而是由 `ServerCall.Listener` 的**回调**驱动。`ServerCall.Listener`（`api/src/main/java/io/grpc/ServerCall.java`）：

| 回调 | 位置 | 何时触发 | 语义 |
|---|---|---|---|
| `onMessage` | `:66` | 收到一条请求消息 | 流式调用可能触发**多次**（第 05 篇） |
| `onHalfClose` | `:71` | 客户端发完所有消息 | "请求发完"，但调用仍可能被取消 |
| `onCancel` | `:80` | 调用被取消 | 服务端应尽快中止处理省资源 |
| `onComplete` | `:88` | 调用完成 | `onComplete` 与 `onCancel` 互斥（保证不会同时来） |
| `onReady` | `:102` | 可以继续发消息 | 服务端写流控信号（第 05 篇） |

实验②的 unary 时序（一次请求）是：`startCall`（分发到方法）→ `onMessage`（收到请求）→ `onHalfClose`（请求发完）→ 业务执行 → `onComplete`（响应完成）。

**为什么要 Listener 而不是直接调用？** 因为 gRPC 要把 unary 和四种流式统一成同一套事件模型：unary 只是"onMessage 一次 + onHalfClose 一次"的特例。业务代码（`StreamObserver`）在 `onHalfClose` 之后执行，正是"请求完整到达才开始处理"的保证——生成代码里写得很直白：`ServerCalls.java:164-165` 注释 "We delay calling method.invoke() until onHalfClose()"，调用点 `:170`。

---

## 六、优雅关闭（实验③的解读）

`Server` 提供两个关闭方法（`ServerImpl.java`）：

| 方法 | 位置 | 行为 |
|---|---|---|
| `shutdown()` | `:254` | **停止接受新调用**，等在途调用完成 |
| `shutdownNow()` | `:274` | 立即关闭：在途调用被以 `UNAVAILABLE: Server shutdownNow invoked`（`:277`）终止 |
| `awaitTermination(timeout)` | `:309` | 等待关闭完成（带超时） |

实验③：`slow` 调用进行中收到 SIGTERM → 关闭钩子调 `shutdown()` → 在途调用**跑完**（client `1958ms` 拿到 OK）→ 进程退出；此时新连接被拒绝（client `UNAVAILABLE: io exception`）。

与 Dubbo 优雅下线（第 07 篇）对照：两者语义一致（先摘流量、再排空、最后关闭），但 gRPC 是**应用显式调用 `Server.shutdown()` + `awaitTermination()`**（K8s 里就是 preStop/SIGTERM 钩子），Dubbo 是框架 hook + `close(timeout)` 自动完成。

---

## 七、实验解读

| 观测 | 结论 |
|---|---|
| 一个 Server 服务 Greeter + Echo | Server 是 ServiceDefinition 容器，按方法全名分发 |
| `startCall → onMessage → onHalfClose → onComplete` | 业务由 Listener 事件驱动；unary 是流式的特例 |
| SIGTERM 中 in-flight OK、新调用 UNAVAILABLE | `shutdown()` 停止接新 + 等在途；`awaitTermination` 控制等待预算 |

---

## 八、生产陷阱

1. **`UNIMPLEMENTED` 先查方法全名**：客户端调用名与服务端注册名必须一致（含包名）；
2. **优雅关闭用 `shutdown()` 而非 `shutdownNow()`**：K8s 的 `terminationGracePeriodSeconds` 要大于"排空预算"；`shutdownNow()` 会中断在途业务；
3. **默认共享线程池**：所有服务的业务都跑在 `grpc-default-executor`（01 篇）；慢服务会拖累其他服务——生产要给慢服务配独立 executor；
4. **服务端不"反射"**：分发靠生成的 handler（`ServerCalls.asyncUnaryCall` 适配），所以服务端也必须由 proto 生成代码，不能只凭接口名注册。

---

## 附录 A：面试速答（附带）

> ServerBuilder.build() → ServerImplBuilder.build() → ServerImpl.start()：绑定端口 + 取业务线程池。Server 是多 ServiceDefinition 容器（InternalHandlerRegistry 持有服务列表），按 full method name（`包.Service/方法`）分发：lookupMethod → 包装成 ServerCallImpl → 取生成代码注册的 ServerCallHandler（ServerCalls.asyncUnaryCall 把 StreamObserver 适配成 handler）。业务由 ServerCall.Listener 事件驱动：onMessage（收到请求，流式可多次）→ onHalfClose（请求发完）→ 业务执行 → onComplete；取消走 onCancel。优雅关闭：shutdown() 停止接新 + 等在途，awaitTermination(timeout) 控制预算；shutdownNow() 立即关闭，在途调用被以 UNAVAILABLE 终止。

## 附录 B：实验与复现

```bash
cd grpc-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -q compile
bash scripts/03-server-lifecycle.sh   # 输出见 evidence/03-server-lifecycle.log
```

| 观测点 | 结果 |
|---|---|
| 多服务 | Greeter + Echo 同 Server；Echo = `echo lab from grpc-lab` |
| 生命周期 | `startCall → onMessage → onHalfClose → onComplete` |
| 优雅关闭 | in-flight OK 1958ms；关闭后新调用 `UNAVAILABLE: io exception` |

## 附录 C：源码坐标（gRPC-Java 1.83.1 + lab）

| 内容 | 坐标 |
|---|---|
| 装配链 | `api/src/main/java/io/grpc/ServerBuilder.java:436`（build）；`core/src/main/java/io/grpc/internal/ServerImplBuilder.java:257`（build）；`ServerImpl.java:180-192`（start） |
| 多服务容器 | `ServerImpl.java:102`（registry 字段）；`InternalHandlerRegistry.java:30`（类）、`:32`（services）、`:51`（lookupMethod） |
| 分发 | `ServerImpl.java:501`（lookupMethod）、`:594`（ServerCallParameters）、`:668`（getServerCallHandler）；`ServerCallImpl.java:52` |
| 业务适配 | `stub/src/main/java/io/grpc/stub/ServerCalls.java:49`（asyncUnaryCall） |
| Listener 生命周期 | `api/src/main/java/io/grpc/ServerCall.java:66`（onMessage）、`:71`（onHalfClose）、`:80`（onCancel）、`:88`（onComplete）、`:102`（onReady） |
| 优雅关闭 | `ServerImpl.java:254`（shutdown）、`:274`（shutdownNow）、`:309`/`:321`（awaitTermination） |
| lab | `scripts/03-server-lifecycle.sh`；`ServerApp -Dlab.server.lifecycle/-Dlab.graceful.ms`；`ClientApp -Dlab.echo/-Dlab.single` |
