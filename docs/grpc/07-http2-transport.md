# 07 HTTP/2 传输：grpc-netty 与帧映射

> 定位：gRPC 系列第 07 篇——覆盖 🔴 G-8（HTTP/2 传输）。
> lab：`scripts/07-http2-frames.sh` + `tools/h2-capture.py`（全量抓包）+ `tools/h2-frames.py`（帧解码）；证据：`grpc-lab/evidence/07-http2-frames.log`。
> 课程对照：《RPC实战与核心原理》04 网络通信 / 06 gRPC 源码实战 + Netty 系列 N-14（HTTP/2）。

---

## 一、先看实验：两次抓包的帧序列

**① 正常 unary 调用**（h2-capture 50992 → 50051，帧解码）：

```
client → server：
@0  PREFACE  b'PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n'
@24    len=24    SETTINGS      flags=-            stream=0 2=0,3=0,4=1048576,6=8192
@57    len=4     WINDOW_UPDATE flags=-            stream=0 increment=983041
@70    len=0     SETTINGS      flags=ACK          stream=0
@79    len=130   HEADERS       flags=END_HEADERS  stream=3 hpack=41 0f 6c 6f 63 61 6c 68 6f 73 74 3a
@218   len=10    DATA          flags=END_STREAM   stream=3 grpc_msg(compressed=0, len=5, body=0a 03 6c 61 62)
@237   len=8     PING          flags=ACK          stream=0
@254   len=8     PING          flags=-            stream=0
@271   len=8     GOAWAY        flags=-            stream=0
server → client：
@0     len=18    SETTINGS      flags=-            stream=0 3=2147483647,4=1048576,6=8192
@27    len=4     WINDOW_UPDATE flags=-            stream=0 increment=983041
@40    len=0     SETTINGS      flags=ACK          stream=0
@49    len=8     PING          flags=-            stream=0
@66    len=70    HEADERS       flags=END_HEADERS  stream=3 hpack=88 5f 10 61 70 70 6c 69 63 61 74 69
@145   len=30    DATA          flags=-            stream=3 grpc_msg(compressed=0, len=25, body=0a 17 68 65 6c 6c 6f 20 6c 61 62 20 66 72 6f 6d 20 67 72 70 63 2d 6c 61)
@184   len=15    HEADERS       flags=END_STREAM|END_HEADERS stream=3 hpack=40 0b 67 72 70 63 2d 73 74 61 74 75
@208   len=8     PING          flags=ACK          stream=0
```

**② deadline 取消**（client 500ms / server slow 2000ms）：

```
client → server：
@259   len=8     PING          flags=ACK          stream=0
@276   len=4     RST_STREAM    flags=-            stream=3 error_code=8
@289   len=8     GOAWAY        flags=-            stream=0
server → client：
@0     len=18    SETTINGS      flags=-            stream=0 3=2147483647,4=1048576,6=8192
@27    len=4     WINDOW_UPDATE flags=-            stream=0 increment=983041
@40    len=0     SETTINGS      flags=ACK          stream=0
@49    len=8     PING          flags=-            stream=0
```

（服务端还在 slow 中，取消先到——所以只有连接建立帧，没有响应。）

两次抓包把 gRPC 的协议映射全部暴露：**HEADERS（调用）+ DATA（消息）+ 第二个 HEADERS（trailers）+ RST_STREAM（取消）**。

---

## 二、朴素答案"grpc-netty 是另一个 Netty 封装"死在哪

| 误解 | 事实 |
|---|---|
| "grpc-netty 自己实现了 HTTP/2" | 它**直接继承** Netty 的处理器：`GrpcHttp2ConnectionHandler extends Http2ConnectionHandler`（`netty/src/main/java/io/grpc/netty/GrpcHttp2ConnectionHandler.java:36`） |
| "gRPC 有自己的连接管理" | 连接/EventLoop/Channel 全是 Netty 的；gRPC 只加**协议层**：帧映射、流状态、写队列 |

一句话：**grpc-netty = Netty codec-http2 之上的 gRPC 语义层**。

---

## 三、帧映射（实验①解读）

| gRPC 概念 | HTTP/2 帧 | 实验观测 |
|---|---|---|
| 方法调用 | **HEADERS**（`:method POST`、`:path /myxhs.lab.Greeter/SayHello`、`content-type: application/grpc`、`te: trailers`…） | `HEADERS stream=3`（HPACK 压缩，只给字节摘要） |
| 请求消息 | **DATA**（1B 压缩标志 + 4B 长度 + protobuf） | `grpc_msg(compressed=0, len=5, body=0a 03 6c 61 62)`——`0a 03 "lab"` 就是 protobuf 的 `HelloRequest{name:"lab"}` |
| 响应消息 | **DATA** | `grpc_msg(len=25, body=0a 17 "hello lab from grpc-l")` |
| 状态/尾 | **第二个 HEADERS + END_STREAM**（trailers） | `HEADERS flags=END_STREAM\|END_HEADERS stream=3`，HPACK 字面量里直接可见 `67 72 70 63 2d 73 74 61 74 75`（"grpc-status"） |
| 取消 | **RST_STREAM** | `RST_STREAM stream=3 error_code=8`（8 = CANCEL） |
| 保活 | **PING** | `PING` / `PING flags=ACK` |
| 关连接 | **GOAWAY** | 客户端调用结束关闭 channel 时出现 |

两个细节：

1. **为什么是 stream 3 而不是 1**：Netty 客户端流 ID 初始化 `nextReservationStreamId = 1`（`DefaultHttp2Connection.java:737`），而 `incrementAndGetNextStreamId()` **先加 2 再返回**（`:748-750`）→ 第一个业务流 = 3（1 被跳过）；
2. **DATA 里的 5 字节前缀**（`00 00 00 00 05`）就是第 05 篇 `MessageFramer` 写的分帧格式——协议层与分帧层在此闭环。

---

## 四、写路径：WriteQueue 与帧命令

写消息不是直接 `channel.write`，而是走命令队列：

- `NettyClientStream.writeMessage` → `writeQueue.enqueue(new SendGrpcFrameCommand(...), flush)`（`NettyClientStream.java:187/:192`；服务端 `NettyServerStream.java:114`）；
- `SendGrpcFrameCommand`（`SendGrpcFrameCommand.java:30`）实现 `WriteQueue.QueuedCommand`；`WriteQueue`（`WriteQueue.java:35`）提供 `enqueue`（`:98`）、`scheduleFlush`（`:63`）、`flush`（`:122`）。

**为什么需要队列**：一次调用可能连续写多条消息（流式），逐条 flush 会产生大量 syscall；`WriteQueue.flush()` 把队列 drain 出来批量执行，**按 `DEQUE_CHUNK_SIZE = 128` 分块逐块 `channel.flush()`**（防 OOM），且"至少 flush 一次"（`:122-145`）。DATA 帧本身的写出走 `sendGrpcFrame`（`:833-844`：`encoder().writeData(...)`），注释明说 "no need to flush since this is handled by the outbound flow controller"（`:842-843`）——写队列与流控分工清晰。这是"gRPC 协议层"的一部分，不在 Netty 里。

**取消路径**：`CancelClientStreamCommand`（`CancelClientStreamCommand.java:26`）以 `flush=true` 入队（`NettyClientStream.java:207`）→ `NettyClientHandler` 的 write 分发（`:381-382`）→ `cancelStream` 真正发 RST_STREAM（`:826`：`encoder().writeRstStream(ctx, stream.id(), Http2Error.CANCEL.code(), promise)`）；入站的 RST_STREAM 则被 `onRstStreamRead`（`:471-474`）翻译成 `Status`（`statusFromH2Error`）。（`:600-601` 的 "Delegate to the base class to send a RST_STREAM" 是另一条路径：流错误 `onStreamError`。）

---

## 五、传输装配（谁装进 Netty pipeline）

- `NettyClientTransport`（`NettyClientTransport.java:74`）建立连接与 pipeline；协议协商由 `ProtocolNegotiators`（`ProtocolNegotiators.java:100`）负责（h2c/TLS/ALPN）；
- 业务处理器：`NettyClientHandler`（`:102`）/ `NettyServerHandler`（`:118`），都继承 `AbstractNettyHandler`；
- gRPC 侧流抽象：`NettyClientStream`（`:56`）extends `AbstractClientStream`——把 gRPC 的 `ClientStream` 调用翻译成 HTTP/2 写命令与流状态。

---

## 六、保活与空闲（语义与配置边界）

三个组件在 `core/internal`：

| 组件 | 位置 | 职责 |
|---|---|---|
| `KeepAliveManager` | `:34` | 客户端保活 ping 调度（keepalive_time/keepalive_timeout） |
| `KeepAliveEnforcer` | `:25` | **服务端**校验对端 ping 频率（构造点 `NettyServerHandler.java:261`）：`permitWithoutCalls`/最小间隔/`MAX_PING_STRIKES = 2`（`:27/:66-67`，超限拒绝） |
| `MaxConnectionIdleManager` | `:28` | 空闲超时 → 主动关闭（发 GOAWAY，NO_ERROR，`:60-61`） |

> 边界诚实：本 lab 未做保活实跑（需要调小参数并长跑）；这里给语义与坐标，配置陷阱见下节。

---

## 七、实验解读

| 观测 | 结论 |
|---|---|
| HEADERS/DATA/HEADERS(trailers)/PING/RST_STREAM | gRPC 全部语义映射到 HTTP/2 帧；一个调用 = 一个 stream |
| stream=3 | Netty 客户端流 ID 先加 2 再返回（1 跳过） |
| DATA 里 `00 00 00 00 05` + protobuf | 与第 05 篇分帧格式闭环 |
| 取消 → RST_STREAM(8) | deadline/取消是**流级**信号；连接级是 GOAWAY |

---

## 八、生产陷阱

1. **抓包看帧而不是猜**：HEADERS 是 HPACK 压缩（`h2-frames.py` 只能给摘要）；要看明文头需 HPACK 解码工具或服务端日志；
2. **RST_STREAM vs GOAWAY**：流级取消（deadline/客户端取消）用 RST_STREAM；连接级关停（channel 关闭/服务端 idle）用 GOAWAY——排障先分清层级；
3. **保活参数有防滥用边界**：服务端 `KeepAliveEnforcer` 会拒绝过于频繁的 ping（触发 GOAWAY）；客户端 `permitWithoutCalls` 决定空闲时是否发 ping——配错会被对端踢掉；
4. **不要自己往 pipeline 塞 handler 做"优化"**：协议层由 grpc-netty 管理；扩展走 Interceptor/TransportFilter（第 04 篇）；
5. **写队列与流控联动**：`isReady`/`request(n)` 的语义（第 05 篇）在传输层的落点就是 WriteQueue 与窗口——两边一起看。

---

## 附录 A：面试速答（附带）

> grpc-netty 直接继承 Netty 的 Http2ConnectionHandler（GrpcHttp2ConnectionHandler），复用 Netty 的 EventLoop/Channel，只加协议层。帧映射：调用=HEADERS（HPACK）、消息=DATA（1B 压缩标志+4B 长度+protobuf）、状态=第二个 HEADERS+END_STREAM（trailers，含 grpc-status）、取消=RST_STREAM（error_code=8 CANCEL）、保活=PING、关连接=GOAWAY。写路径走 WriteQueue 队列（SendGrpcFrameCommand 入队，合并 write/flush），取消走 CancelClientStreamCommand → RST_STREAM。客户端流 ID 从 1 起、先加 2 再返回 → 首个业务流是 3。保活/空闲：KeepAliveManager（客户端）/KeepAliveEnforcer（服务端防滥用）/MaxConnectionIdleManager（idle 关连接）。

## 附录 B：实验与复现

```bash
cd grpc-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -q compile
bash scripts/07-http2-frames.sh   # 输出见 evidence/07-http2-frames.log
```

| 观测点 | 结果 |
|---|---|
| 正常调用 | HEADERS(stream 3) → DATA(len=5 protobuf) → 响应 DATA(len=25) → trailers HEADERS(END_STREAM, grpc-status) |
| 取消 | RST_STREAM(stream 3, error_code=8) |
| 工具 | `tools/h2-capture.py`（全量按方向落盘）+ `tools/h2-frames.py`（逐帧解码） |

## 附录 C：源码坐标（gRPC-Java 1.83.1 + Netty + lab）

| 内容 | 坐标 |
|---|---|
| 继承 Netty HTTP/2 | `netty/src/main/java/io/grpc/netty/GrpcHttp2ConnectionHandler.java:36` |
| 处理器/流/传输 | `NettyClientHandler.java:102`、`NettyServerHandler.java:118`、`NettyClientStream.java:56`、`NettyClientTransport.java:74`、`ProtocolNegotiators.java:100` |
| 写队列 | `WriteQueue.java:35/:63/:98/:122`；`SendGrpcFrameCommand.java:30`；入队点 `NettyClientStream.java:187/:192`、`NettyServerStream.java:114` |
| 取消 | `CancelClientStreamCommand.java:26`；入队 `NettyClientStream.java:207`；分发 `NettyClientHandler.java:381-382`；发 RST_STREAM `:826`；流错误路径 `:600-601`；入站 RST_STREAM `:471-474` |
| DATA 写出/flush | `NettyClientHandler.java:833-844`（sendGrpcFrame，flush 由 outbound flow controller 处理）；`WriteQueue.java:39/:122-145`（DEQUE_CHUNK_SIZE=128 分块 flush） |
| 流 ID 分配（Netty） | `codec-http2/.../DefaultHttp2Connection.java:737/:748-750`（netty 源码树） |
| 保活/空闲 | `core/src/main/java/io/grpc/internal/KeepAliveManager.java:34`、`KeepAliveEnforcer.java:25`、`MaxConnectionIdleManager.java:28` |
| lab | `scripts/07-http2-frames.sh`；`tools/h2-capture.py`、`tools/h2-frames.py` |
