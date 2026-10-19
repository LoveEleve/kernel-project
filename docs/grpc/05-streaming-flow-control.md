# 05 四种流式调用：StreamObserver 与流控

> 定位：gRPC 系列第 05 篇——覆盖 🔴 G-4（Stream 流式调用）。
> lab：`scripts/05-streaming.sh`；证据：`grpc-lab/evidence/05-streaming.log`。
> 课程对照：《RPC实战与核心原理》17 异步 RPC / 06 gRPC 源码实战 + 《系统性能调优必知必会》18（流模式协议编码）。

---

## 一、先看实验：四个观测

**① server streaming（默认自动流控）**——服务端推 5 条：

```
[lab-client] serverStream 收到 stream-1 @400ms
[lab-client] serverStream 收到 stream-2 @409ms
[lab-client] serverStream 收到 stream-3 @409ms
[lab-client] serverStream 收到 stream-4 @410ms
[lab-client] serverStream 收到 stream-5 @411ms
```

**② server streaming（手动 `request(1)`，每收一条等 400ms）**——同一个服务端：

```
[lab-client] flow 收到 stream-1 @382ms
[lab-client] flow 收到 stream-2 @790ms
[lab-client] flow 收到 stream-3 @1190ms
[lab-client] flow 收到 stream-4 @1596ms
[lab-client] flow 收到 stream-5 @1997ms
server 侧 isReady：
[lab-server] serverStream #1 isReady=true
[lab-server] serverStream #2 isReady=true
[lab-server] serverStream #3 isReady=true
[lab-server] serverStream #4 isReady=true
[lab-server] serverStream #5 isReady=true
```

**③ client streaming**（3 请求 → 1 响应）：

```
[lab-client] clientStream 结果 = received 3 @361ms
server 侧：
[lab-server] clientStream 收到 c1
[lab-server] clientStream 收到 c2
[lab-server] clientStream 收到 c3
```

**④ bidi streaming**（3 请求 ↔ 3 响应）：

```
[lab-client] bidi 收到 echo b1 @364ms
[lab-client] bidi 收到 echo b2 @373ms
[lab-client] bidi 收到 echo b3 @373ms
```

四个观测回答：**四种模式怎么映射、消息怎么分帧、`request(n)` 到底控制什么**（实验②的间隔与 server isReady 恒 true 是一对矛盾，要解释清楚）。

---

## 二、朴素答案"流式就是 WebSocket"死在哪

| 误解 | 事实 |
|---|---|
| "流式 = 另开一条连接（WebSocket）" | gRPC Stream 是**同一 HTTP/2 连接上的逻辑流**（stream id，第 01 篇），连接级问题会影响所有流 |
| "四种模式是四套实现" | 它们是**同一套 `StreamObserver` 协议的不同组合**：unary 只是"onMessage 一次 + onHalfClose 一次"的特例（第 03 篇） |
| "`request(n)` 是配额/限速" | 它是**流控信号**：请求对端再产 n 条消息（javadoc 原文："Requests the peer to produce count more messages to be delivered"） |

---

## 三、四种模式：同一套协议的不同组合

`StreamObserver` 只有三个方法（`stub/src/main/java/io/grpc/stub/StreamObserver.java`）：

| 方法 | 位置 | 语义 |
|---|---|---|
| `onNext(value)` | `:62` | 产出一条消息 |
| `onError(t)` | `:79` | 终止并报错 |
| `onCompleted()` | `:88` | 正常终止 |

四种模式就是"谁调 onNext、调几次"：

| 模式 | 请求 | 响应 | 客户端入口（ClientCalls） | 服务端入口（ServerCalls） |
|---|---|---|---|---|
| Unary | 1 | 1 | `asyncUnaryCall` | `asyncUnaryCall:49` |
| ServerStream | 1 | N | `asyncServerStreamingCall:95` | `asyncServerStreamingCall:59` |
| ClientStream | N | 1 | `asyncClientStreamingCall:111` | `asyncClientStreamingCall:69` |
| Bidi | N | N | `asyncBidiStreamingCall:127` | `asyncBidiStreamingCall:79` |

实验③的关键细节：**client streaming 的调用会返回一个"请求观察者"**（`StreamObserver<ReqT>`），业务侧对它 `onNext` 发请求、`onCompleted` 表示发完，服务端这时才回结果：

```java
            for (int i = 1; i <= 3; i++) {
                req.onNext(HelloRequest.newBuilder().setName("c" + i).build());
            }
            req.onCompleted();
```

bidi 则是"收到一条回一条"（实验④）。

---

## 四、消息怎么变成"流"

每条 gRPC 消息在 DATA 帧里是 **1 字节压缩标志 + 4 字节长度 + 消息体** 的分帧格式（`MessageFramer.java:70-72`：`HEADER_LENGTH = 5`、`UNCOMPRESSED = 0` / `COMPRESSED = 1`；写入见 `:226/:247`）；发送侧 `MessageFramer`（`:45`）、接收侧 `MessageDeframer`（`MessageDeframer.java:43`）。一个 HTTP/2 stream 上可以连续承载多条消息——这就是"流"的物理形态。

---

## 五、流控：两层机制（实验②解读）

### 5.1 两层流控

| 层 | 粒度 | 机制 | 位置 |
|---|---|---|---|
| HTTP/2 | **字节** | 窗口 + WINDOW_UPDATE；Netty 的 `DefaultHttp2LocalFlowController` | `NettyClientHandler.java:248`、`NettyServerHandler.java:272` |
| gRPC | **消息** | `request(n)` 控制"交付给应用的条数" | `CallStreamObserver.java:126` |

`request(n)` 的 javadoc（`CallStreamObserver.java:119-126`）：

```java
  /**
   * Requests the peer to produce {@code count} more messages to be delivered to the 'inbound'
   * {@link StreamObserver}.
   *
   * <p>This method is safe to call from multiple threads without external synchronization.
   *
   * @param count more messages
   */
  public abstract void request(int count);
```

落到实现：`MessageDeframer.request`（`MessageDeframer.java:156-163`）：

```java
  public void request(int numMessages) {
    checkArgument(numMessages > 0, "numMessages must be > 0");
    if (isClosed()) {
      return;
    }
    pendingDeliveries += numMessages;
    deliver();
  }
```

**它有两面**：

1. **立即效果**：把**已缓冲**的消息交付给应用（`pendingDeliveries += numMessages; deliver()`）；
2. **传递效果**：应用消费后，deframer 通过 `bytesRead` 通知传输层"可以再收"（`Deframer.Listener.bytesRead` javadoc："typically used to indicate to the underlying transport that more data can be accepted"，`MessageDeframer.java:56-61`）——传输层回补 HTTP/2 窗口（`AbstractStream.java:130` 实现 Listener → `NettyClientStream.java:332-336` → `NettyClientHandler.returnProcessedBytes:411-417` → `flowController().consumeBytes`；服务端镜像在 `NettyServerStream.java:183` / `NettyServerHandler.java:746`）——对端才能继续发。

实验②的逐条交付体现的是第 1 面：5 条小消息的字节**早已在本地缓冲**，交付节奏完全由 `request(1)` 控制；当消息大到耗尽窗口时，不 request 就会通过第 2 面挡住对端（`consumeBytes` 不触发 → 窗口不回补）。

### 5.2 实验②的矛盾怎么解释

- 手动 `request(1)` 时，消息**逐条交付**（382→790→1190→1596→1997ms，间隔 ~400ms）——这是 gRPC 层的交付门槛在起作用；
- 但 server 侧 `isReady` **恒 true**——因为 5 条小消息没有耗尽 HTTP/2 窗口，**服务端根本感知不到压力**。

这正是"两层"的直观证据：**request(n) 是客户端"取用"的节奏；服务端 isReady 反映的是 HTTP/2 窗口**。`isReady` 的 javadoc（`ServerCallStreamObserver.java:119-128`）说得很清楚：true = "capable of sending additional messages **without requiring excessive buffering**"，且只是"建议"；false 时通过 `setOnReadyHandler`（`CallStreamObserver.java:87`）注册的回调会在恢复后触发。

### 5.3 一个 API 演进细节

lab 用的 `disableAutoInboundFlowControl()`（`CallStreamObserver.java:116`）javadoc 标注**正在被替代**："On client-side `disableAutoRequestWithInitial(1)`"（`:112-114`；新 API 在 `ClientCallStreamObserver.java:59`）——新代码建议用新 API。

---

## 六、实验解读

| 观测 | 结论 |
|---|---|
| 自动流控：5 条 ~400-411ms 齐到 | 默认自动 request，交付不设门槛 |
| 手动 request(1)：逐条 ~400ms 间隔 | gRPC 层流控：交付条数由客户端决定 |
| server isReady 恒 true | 小消息未耗尽 HTTP/2 窗口；两层流控各管一段 |
| client streaming 返回请求观察者 | N→1：onCompleted 触发服务端回结果 |
| bidi 收到即回 | N↔N：两个 StreamObserver 独立推进 |

---

## 七、生产陷阱

1. **流式 ≠ WebSocket**：流共享一条 HTTP/2 连接；连接级故障（GOAWAY/断连）影响该连接上所有流；
2. **不 `request` = 消息积压在客户端缓冲**（不是服务端停了）——大流量下会撑爆内存；用 `setOnReadyHandler`/`isReady` 配合；
3. **服务端必须检查 `isReady`**：javadoc 明说忽略会导致 "excessive buffering"；正确姿势是 `setOnReadyHandler` + 状态机；
4. **client streaming 别忘了 `onCompleted`**：不发完服务端会一直等；
5. **新 API 优先**：客户端手动流控用 `disableAutoRequestWithInitial(1)`（旧 `disableAutoInboundFlowControl` 正在被替代）。

---

## 附录 A：面试速答（附带）

> 四种模式是同一套 StreamObserver（onNext/onError/onCompleted）的不同组合：unary 1→1、serverStream 1→N、clientStream N→1（调用返回请求观察者，onCompleted 后服务端回结果）、bidi N↔N。消息分帧 = 1 字节压缩标志 + 4 字节长度 + 消息体（MessageFramer/MessageDeframer）；流是 HTTP/2 上的逻辑流（stream id），不是独立连接。流控两层：HTTP/2 窗口（字节级，Netty DefaultHttp2LocalFlowController + WINDOW_UPDATE）与 gRPC `request(n)`（消息级，MessageDeframer.request 的 pendingDeliveries 控制交付）；实验里手动 request(1) 消息逐条交付、而 server isReady 恒 true——小消息未耗尽窗口。服务端要 setOnReadyHandler + isReady，否则 excessive buffering。

## 附录 B：实验与复现

```bash
cd grpc-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -q compile
bash scripts/05-streaming.sh   # 输出见 evidence/05-streaming.log
```

| 观测点 | 结果 |
|---|---|
| serverStream（自动） | 5 条 @400-411ms（齐到） |
| serverStream（request(1)+400ms） | 逐条 @382/790/1190/1596/1997ms |
| clientStream | `received 3`；服务端收到 c1/c2/c3 |
| bidiStream | 3 回声 |

## 附录 C：源码坐标（gRPC-Java 1.83.1 + lab）

| 内容 | 坐标 |
|---|---|
| StreamObserver | `stub/src/main/java/io/grpc/stub/StreamObserver.java:45`（接口）、`:62`（onNext）、`:79`（onError）、`:88`（onCompleted） |
| 流控 API | `CallStreamObserver.java:67`（isReady）、`:87`（setOnReadyHandler）、`:116`（disableAutoInboundFlowControl，正被替代）、`:126`（request）；`ClientCallStreamObserver.java:59`（disableAutoRequestWithInitial）、`:73/:103`；`ServerCallStreamObserver.java:127/:158` |
| 客户端流控入口 | `stub/src/main/java/io/grpc/stub/ClientResponseObserver.java:41`（beforeStart） |
| 四种模式入口 | `ClientCalls.java:95/:111/:127`；`ServerCalls.java:49/:59/:69/:79` |
| 分帧 | `core/src/main/java/io/grpc/internal/MessageFramer.java:45`；`MessageDeframer.java:43`、`:156-163`（request 交付门槛） |
| HTTP/2 窗口 | `netty/src/main/java/io/grpc/netty/NettyClientHandler.java:248`、`NettyServerHandler.java:272`（DefaultHttp2LocalFlowController） |
| 消费→窗口回补链 | `MessageDeframer.java:56-61/:364`（bytesRead）；`AbstractStream.java:130`（implements Listener）；`NettyClientStream.java:332-336`；`NettyClientHandler.java:411-417`（consumeBytes）；服务端 `NettyServerStream.java:183`、`NettyServerHandler.java:746` |
| lab | `scripts/05-streaming.sh`；`ClientApp -Dlab.stream=server/flow/client/bidi`；`ServerApp` StreamerImpl |
