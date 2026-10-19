# 01 为什么是 gRPC？一次 `stub.method()` 长什么样

> 定位：gRPC 系列第 01 篇（引子 + 骨架）——覆盖 G-1 表层（调用链全貌；深挖在 02 篇）。
> lab：`scripts/01-hello-world.sh`；证据：`grpc-lab/evidence/01-hello-world.log`。
> 课程对照：《RPC实战与核心原理》06 gRPC 源码实战 / 24 多协议 + 《系统性能调优必知必会》18（gRPC 编码与流模式）。

---

## 一、先看实验：三个观测

**① 三种 Stub 调用同一个方法**：

```
[lab-client] blocking = hello lab from grpc-lab in 2027ms
[lab-client] future   = hello lab from grpc-lab in 13ms
[lab-client] async    = hello lab from grpc-lab in 19ms
```

**② 服务端处理线程**（server 日志）：

```
线程=grpc-default-executor-0
```

**③ 网络上的前 64 字节**（wire-proxy 抓包）：

```
[C->P] 50 52 49 20 2a 20 48 54 54 50 2f 32 2e 30 0d 0a 0d 0a 53 4d 0d 0a 0d 0a 00 00 18 04 00 00 00 00 00 00 02 00 00 00 00 00 03 00 00 00 00 00 04 00 10 00 00 00 06 00 00 20 00
[P->C] 00 00 12 04 00 00 00 00 00 00 03 7f ff ff ff 00 04 00 10 00 00 00 06 00 00 20 00
[P->C] 00 00 04 08 00 00 00 00 00 00 0f 00 01
```

三个问题：**为什么同一次调用三种 Stub 耗时差 100 倍？`grpc-default-executor-0` 是谁？那串十六进制是什么？** 这一篇把它们讲清楚。

---

## 二、为什么还需要第三种 RPC

我们已经学过两种 RPC 形态：Feign（HTTP/1.1 + JSON）和 Dubbo（自有二进制帧协议）。为什么还要学 gRPC？看两种形态的死点：

| 死点 | Feign（HTTP/1.1） | Dubbo（自有协议） |
|---|---|---|
| 传输效率 | 头开销大、同连接请求**队头阻塞** | 高效，但跨语言要重写协议栈 |
| 契约 | HTTP 语义松散，无强制契约 | 接口 jar 是 Java 契约，**跨语言难** |
| 生态互通 | 浏览器/网关友好 | 需要 SDK 或泛化调用 |

gRPC 的答案（三支柱）：

1. **传输**：HTTP/2（多路复用、流控、头压缩）——第 07 篇展开；
2. **编码**：protobuf（二进制、带 schema）——本篇 + 09 篇；
3. **契约**：`.proto` 文件 + 代码生成——编译期就拿到强类型 Stub。

一句话：**gRPC = HTTP/2 上的、以 protobuf 为契约的跨语言 RPC**。

---

## 三、知识讲解：那串十六进制是什么

### 3.1 连接前言：每个 HTTP/2 连接的第一串字节

```
50 52 49 20 2a 20 48 54 54 50 2f 32 2e 30 0d 0a 0d 0a 53 4d 0d 0a 0d 0a
= "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
```

这是 HTTP/2 规定的**连接前言**：客户端连上后先发这 24 字节，告诉服务端"我支持 HTTP/2"。它的存在是为了兼容 HTTP/1.1 服务器（不支持的服务器会把这行当坏请求拒绝，而不是误解后面的二进制帧）。

### 3.2 帧格式：9 字节头 + 载荷

前言之后的每个帧都是：**长度(3) + 类型(1) + 标志(1) + 流ID(4) + 载荷**。

逐帧解码实验抓包（C->P 第一帧）：

```
00 00 18 04 00 00 00 00 00 | 00 02 00 00 00 00 | 00 03 00 00 00 00 | 00 04 00 10 00 00 | 00 06 00 00 20 00
长度=24  类型=4(SETTINGS) 标志=0 流=0   参数1: ENABLE_PUSH=0   参数2: MAX_CONCURRENT_STREAMS=0（客户端禁止服务端主动开流）   参数3: INITIAL_WINDOW_SIZE=1MB   参数4: MAX_HEADER_LIST_SIZE=8KB
```

P->C 方向：

```
00 00 12 04 ... = 服务端 SETTINGS（MAX_CONCURRENT_STREAMS=2^31-1 / INITIAL_WINDOW_SIZE=1MB / MAX_HEADER_LIST_SIZE=8KB）
00 00 04 08 ... = WINDOW_UPDATE（连接级流控窗口）
```

**帧类型速查**（抓包中会反复出现）：

| 类型值 | 名称 | 作用 |
|---|---|---|
| 0x0 | DATA | 承载 gRPC 消息体 |
| 0x1 | HEADERS | 承载 gRPC 方法与 metadata |
| 0x4 | SETTINGS | 连接参数协商（对端要回 ACK，标志位=1） |
| 0x6 | PING | 保活探测（对端回 PING ACK） |
| 0x8 | WINDOW_UPDATE | 流控窗口调整 |

关键认知：**gRPC 的方法调用、metadata、消息、状态码，全部装在这些帧里**；同一连接上可以有多个流（多路复用），每个流由**流 ID** 区分——这是它和 Dubbo"一连接多请求靠 request id"最大的不同（第 07 篇对照）。

---

## 四、三种 Stub：同一套异步调用的三种"外观"

### 4.1 生成代码从哪来

lab 的 `hello.proto` 只声明了一个方法：

```proto
service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
}
```

`protoc` + `protoc-gen-grpc-java` 生成 `GreeterGrpc`，三个工厂方法返回三种 Stub（生成代码，节选）：

```java
  public static GreeterBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<GreeterBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<GreeterBlockingStub>() {
        @java.lang.Override
        public GreeterBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new GreeterBlockingStub(channel, callOptions);
```

三种 Stub 的区别只是**调用外观**：

| Stub | 发起方式 | 实验耗时 |
|---|---|---|
| `newBlockingStub` | 看起来同步，返回结果 | 2027ms（**首调用含建连握手**） |
| `newFutureStub` | 返回 `ListenableFuture` | 13ms |
| `newStub`（Async） | `StreamObserver` 回调 | 19ms |

### 4.2 关键认知：BlockingStub 内部也是异步的

`ClientCalls.blockingUnaryCall`（`ClientCalls.java:155-182`，节选）：

```java
  public static <ReqT, RespT> RespT blockingUnaryCall(
      Channel channel, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, ReqT req) {
    ThreadlessExecutor executor = new ThreadlessExecutor();
    boolean interrupt = false;
    ClientCall<ReqT, RespT> call = channel.newCall(method,
        callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING)
            .withExecutor(executor));
    try {
      ListenableFuture<RespT> responseFuture = futureUnaryCall(call, req);
      while (!responseFuture.isDone()) {
        try {
          executor.waitAndDrain();
        } catch (InterruptedException e) {
          interrupt = true;
          call.cancel("Thread interrupted", e);
          // Now wait for onClose() to be called, so interceptors can clean up
        }
      }
      executor.shutdown();
      return getUnchecked(responseFuture);
      ...
```

**它内部先调 `futureUnaryCall`（异步），再 `waitAndDrain` 阻塞等结果**——所以"同步 Stub"只是包装，不是另一条链路。这也是第 02 篇要拆的调用链起点。

### 4.3 首调用为什么慢

2027ms vs 13ms 不是 RPC 变快了，而是**首次调用要建连**：TCP + HTTP/2 前言 + SETTINGS 协商（上面抓到的帧就是握手过程），之后调用复用同一条连接。压测不预热，测的就是握手成本。

---

## 五、服务端：谁在执行你的方法

`grpc-default-executor-0` 不是 Netty 的 IO 线程，而是 gRPC 的**共享业务线程池**（`GrpcUtil.java:557-570`，节选）：

```java
  /**
   * Shared executor for channels.
   */
  public static final Resource<Executor> SHARED_CHANNEL_EXECUTOR =
      new Resource<Executor>() {
        private static final String NAME = "grpc-default-executor";
        @Override
        public Executor create() {
          return Executors.newCachedThreadPool(getThreadFactory(NAME + "-%d", true));
        }
        ...
```

分工：**Netty EventLoop 只做 IO（收字节/发字节），业务方法在 gRPC 线程池执行**。为什么不让 IO 线程直接跑业务？业务阻塞（sleep、DB 调用）会卡死整条连接的 IO——第 07 篇会看到这条边界的具体实现。

---

## 六、与 Dubbo / Feign 对照

| 维度 | Feign | Dubbo | gRPC |
|---|---|---|---|
| 契约 | 接口 + 注解 | 接口 jar | **`.proto` + 代码生成** |
| 传输 | HTTP/1.1 | 自有帧（16 字节头） | **HTTP/2** |
| 编码 | JSON | hessian2 等 | **protobuf** |
| 治理 | 外置（LoadBalancer/Sentinel） | SDK 内建 | 拦截器 + 插件（LB/NameResolver） |
| 跨语言 | 好（HTTP） | 需 SDK/泛化 | **好（protobuf 契约）** |

---

## 七、生产陷阱（初识版）

1. **BlockingStub ≠ 同步**：内部仍是异步（`:155` 的证据）；**不要在 Netty EventLoop 或 gRPC 回调线程里调 BlockingStub**，会阻塞 IO/造成死锁；
2. **首调用慢是握手**：压测/上线要预热，别把建连成本算进 RPC P99；
3. **`.proto` 是契约不是文档**：字段号一旦发布不能复用（兼容规则第 09 篇展开）；
4. **排障视角要换**：抓包看 HTTP/2 帧（前言/SETTINGS/流 ID），不是 dubbo 的 `da bb` 16 字节头。

---

## 附录 A：面试速答（附带）

> gRPC = HTTP/2 + protobuf + 代码生成契约。客户端连上先发连接前言 "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"，之后所有数据走 9 字节头（长度/类型/标志/流ID）+ 载荷的帧：DATA/HEADERS/SETTINGS/PING/WINDOW_UPDATE。三种 Stub 只是外观：BlockingStub 内部先 `futureUnaryCall` 再阻塞等待（`ClientCalls.blockingUnaryCall`），FutureStub 返回 ListenableFuture，AsyncStub 用 StreamObserver 回调。服务端业务跑在 `grpc-default-executor` 共享线程池，Netty EventLoop 只做 IO。首调用慢是 HTTP/2 建连握手，后续复用连接。

## 附录 B：实验与复现

```bash
cd grpc-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -q compile        # protoc + protoc-gen-grpc-java 生成 Stub
bash scripts/01-hello-world.sh   # 输出见 evidence/01-hello-world.log
```

| 观测点 | 结果 |
|---|---|
| 三 Stub | blocking 2027ms（首调含握手）/ future 13ms / async 19ms |
| 服务端线程 | `grpc-default-executor-0` |
| 抓包 | 连接前言 + 客户端 SETTINGS(4 参数) + 服务端 SETTINGS(3 参数) + WINDOW_UPDATE |

## 附录 C：源码坐标（gRPC-Java 1.83.1 + lab）

| 内容 | 坐标 |
|---|---|
| 三种调用实现 | `stub/src/main/java/io/grpc/stub/ClientCalls.java:81`（asyncUnaryCall）、`:155`（blockingUnaryCall）、`:321`（futureUnaryCall） |
| Stub 基类/工厂 | `stub/src/main/java/io/grpc/stub/AbstractStub.java:54`（类）、`:114`（newStub） |
| 服务端线程池 | `core/src/main/java/io/grpc/internal/GrpcUtil.java:557-570`（SHARED_CHANNEL_EXECUTOR，`grpc-default-executor`）；服务端默认 executor 池 `core/src/main/java/io/grpc/internal/ServerImplBuilder.java:71-72`；任务派发 `ServerImpl.java:188` |
| 客户端 SETTINGS 参数 | `netty/src/main/java/io/grpc/netty/NettyClientHandler.java:256-259`（pushEnabled(false) / maxConcurrentStreams(0) / initialWindowSize） |
| 传输 builder | `netty/src/main/java/io/grpc/netty/NettyChannelBuilder.java`、`NettyServerBuilder.java`（第 07 篇展开） |
| lab | `grpc-lab/src/main/proto/hello.proto`；生成物 `target/generated-sources/protobuf/grpc-java/.../GreeterGrpc.java`；`scripts/01-hello-world.sh` |
