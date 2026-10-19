# 09 面试题：gRPC 高频题与速答（收束）

> 定位：gRPC 系列第 09 篇（收束篇）——全系列结论的面试化重排；每题给"一句话答案 + 展开要点 + 回链"。
> 用法：先自测（盖住答案），答不出的回对应正文；证据全在 `grpc-lab/evidence/`。
> 边界：protobuf 编码与 TLS 只做问答（系列边界见 00 规划 §五）。

---

## 一、速记卡（一页纸）

1. gRPC = **HTTP/2 + protobuf + 代码生成**；消息走 DATA 帧，metadata 走 HEADERS。
2. 三种 Stub（Blocking/Future/Async）只是门面，**底层都是同一条 ClientCall 链**。
3. **`retryEnabled` 在 1.83.1 默认就是 true**；真正开关是 service config 的 `retryPolicy`（没配只有 transparent retry）。
4. `maxAttempts` **含首次**（配 3 = 最多 3 次调用），且被通道级 `maxRetryAttempts`（默认 5）钳制。
5. 拦截器**最后 add 的最外层**（client 回调反向、server 同向）；改响应头必须**包装** `sendHeaders`。
6. `request(n)` 是**两面**的：交付已缓冲消息 + 回补流控窗口。
7. `effectiveDeadline = min(CallOptions, Context)`；出站子调用**自动继承** deadline。
8. LB 默认 **pick_first**；`round_robin` 由 `grpc-util` 注册，依赖树缺它时 RPC 以 **INTERNAL** 失败（不是静默退化）。
9. Health 是**应用层 gRPC 服务**（≠ 传输层 PING 心跳）；Reflection 让客户端**无 proto** 发现服务。
10. 取消 = `RST_STREAM(CANCEL)`；`WriteQueue` 按 128 条分块 flush。

---

## 二、分组高频题

### A. 协议与定位

**A1. gRPC 是什么？**
一句话：基于 HTTP/2 传输、protobuf 契约、多语言代码生成的开源 RPC 框架。
展开：核心三件套 = 契约（.proto）+ 传输（HTTP/2 流）+ 生成物（Stub/序列化）。回链：01。

**A2. gRPC 相比 REST/JSON 的优势？**
强类型契约（跨语言 Stub）、流式原生支持、二进制+头压缩省带宽、HTTP/2 多路复用。
代价：可读性差（要工具）、浏览器要 grpc-web 代理。回链：01。

**A3. gRPC 和 Dubbo 什么关系？**
定位不同：Dubbo 是**服务治理框架**（路由/集群容错/标签等治理丰富），协议可插拔；gRPC 是**协议+框架**（跨语言/云原生标准）。
交汇点：Dubbo 3 的 **Triple 协议（protobuf 模式）与 gRPC 服务互通**。回链：Dubbo 系列 11。

**A4. protobuf 怎么编码？（边界题）**
tag = `(field_number << 3) | wire_type`；整数用 **varint**（变长）、负数用 **zigzag**、string/bytes/嵌套消息用 length-delimited。
一句话收：TLV + 变长整数；深入编码另线。回链：00 §五边界。

**A5. 为什么 gRPC 选 HTTP/2？**
多路复用（一条连接承载多调用，无队头阻塞）、二进制帧、HPACK 头压缩、流级+连接级流控、流优先级。
回链：07。

**A6. 四种调用模式？**
unary / server-streaming / client-streaming / bidi-streaming；都通过 StreamObserver（异步）或 Stub 组合表达。回链：05。

### B. 调用链与重试

**B1. 三种 Stub 有什么差别？**
Blocking（同步阻塞）、Future（ListenableFuture）、Async（StreamObserver 回调）；**生成物不同，内部调用链相同**。回链：01/02。

**B2. 一次 `stub.method()` 内部走了哪些对象？**
Stub → ClientCall → 拦截器链 → `ClientCallImpl` → ClientStream → Transport（Netty）→ 帧。回链：02。

**B3. 重试默认开启吗？（高频陷阱）**
**1.83.1 默认 `retryEnabled=true`**（`ManagedChannelImplBuilder.java:195`）；但"开子系统"≠"会重试"——真正决定的是 service config 里有没有 `retryPolicy`；没配只有 transparent retry。回链：02。

**B4. `maxAttempts` 是"重试次数"吗？**
不是，**含首次**：配 3 = 最多 3 次调用（1 原始 + 2 重试）；还被通道级 `maxRetryAttempts`（默认 5）钳制。回链：02。

**B5. 什么情况能重试？**
失败可安全重放（服务端未处理/未收到响应）且状态码在 `retryableStatusCodes` 内；**有副作用的调用必须幂等**（重试可能重复执行）。回链：02。

**B6. retry 和 hedging 的区别？**
retry 是**串行**（失败后再试）；hedging 是**并发**（超时未回就发第二份，取先回）——只用于严格只读。回链：02。

### C. 服务端

**C1. Server 怎么创建？**
`ServerBuilder.forPort(port).addService(...).build().start()`；Netty 实现是 `NettyServerBuilder`。回链：03。

**C2. 一个 Server 怎么承载多服务？**
每个 `BindableService` 转成 `ServerServiceDefinition`，按 **method full name**（`包.服务/方法`）路由分发。回链：03。

**C3. 请求怎么分发到业务方法？**
transport 收到 HEADERS → 找 `ServerCallHandler` → 执行业务（unary 在 `onHalfClose`）；服务没注册返回 `UNIMPLEMENTED`。回链：03。

**C4. Server 生命周期？**
`start()` 绑定端口并接受连接 → `shutdown()` 拒新请求/等存量 → `awaitTermination(timeout)`；超时可 `shutdownNow()`。回链：03。

**C5. 生产怎么做优雅下线？**
先 Health 翻 `NOT_SERVING` 摘流（08），再 `shutdown + awaitTermination`，最后强制退出。回链：03/08。

### D. 拦截器 + Metadata/Status

**D1. 拦截器执行顺序？**
**最后 add 的最外层、最先执行**；server 拦截器链与请求同向，client 拦截链在回调方向上是反的。回链：04。

**D2. Metadata 是什么？**
HTTP/2 headers 的键值对；ASCII 值直接传，**key 以 `-bin` 结尾自动 base64**（二进制安全）。回链：04。

**D3. 拦截器里直接改响应头为什么报错？**
`sendHeaders` 只能调一次（业务已调过）；正确做法是**包装 ServerCall**，覆写 `sendHeaders` 追加头。回链：04。

**D4. gRPC 错误模型？**
`Status`（code + description）+ trailers；常见码：OK / INVALID_ARGUMENT / NOT_FOUND / DEADLINE_EXCEEDED / UNAVAILABLE / UNIMPLEMENTED。回链：04。

**D5. metadata 有大小限制吗？**
有，默认 **8KB**（`GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE=8192`，`core/src/main/java/io/grpc/internal/GrpcUtil.java:218`）；超了连接/流会失败——别把大对象塞 metadata。回链：04。

**D6. traceId 怎么透传？**
client 拦截器写 metadata → server 拦截器读出放 Context（跨线程用 Context）；也可用 `-bin` 传二进制上下文。回链：04/06。

### E. 流式与流控

**E1. `request(n)` 到底干什么？（高频陷阱）**
**两面**：①交付已缓冲的 n 条消息；②回补流控窗口（`bytesRead → returnProcessedBytes → consumeBytes`）——不做②对端最终发不动。回链：05。

**E2. 不调 `request(n)` 会怎样？**
消息卡在缓冲不交付；窗口不回补，对端发到窗口耗尽后阻塞。回链：05。

**E3. `onReady` 是干什么的？**
告诉你"发送缓冲未满、现在可以发"；不是"对端已收到"。回链：05。

**E4. 流控是连接级还是流级？**
HTTP/2 两级都有：连接窗口 + 流窗口；gRPC 在其上再套"应用层缓冲"。回链：05/07。

**E5. `MAX_CONCURRENT_STREAMS=0` 是什么意思？**
本端通告"不接收对端发起的流"；本 lab 客户端通告 0（gRPC 不用 server push），**不是服务端限制**。回链：01/07。

### F. Context 与 Deadline

**F1. Context 怎么跨线程传播？**
gRPC 用 `ContextRunnable` 包装**所有回调**；用户自己起线程要手动 attach（否则丢 Context）。回链：06。

**F2. deadline 怎么传到服务端？**
`grpc-timeout` header；服务端 `effectiveDeadline = min(CallOptions, Context)`（06 篇验证）。回链：06。

**F3. 子调用会自动继承超时吗？**
会：出站调用继承当前 Context 的 deadline，取 `min`；父剩余时间更少时子调用按父算。回链：06。

**F4. 取消链是怎么走的？**
客户端 `cancel` → `RST_STREAM(CANCEL)`（`NettyClientHandler.java:826`）→ 服务端 Context 取消、业务线程可感知。回链：06/07。

**F5. 为什么 Context 不能跨请求乱传？**
Context 是**请求级**的；线程池复用下跨请求持有会串数据/泄漏——用完必须还原（try/finally）。回链：06。

### G. HTTP/2 传输

**G1. grpc-netty 的 handler 链长什么样？**
`NettyClientHandler`（gRPC 逻辑）→ `Http2ConnectionHandler`（codec-http2 帧层）→ `NettyClientTransport`；写队列按 **128 条分块 flush**。回链：07。

**G2. gRPC 消息怎么映射到 HTTP/2 帧？**
长度前缀消息切成 DATA 帧；metadata → HEADERS；trailers → 带 END_STREAM 的 HEADERS。回链：07。

**G3. 取消发的是什么帧？**
`RST_STREAM`，error code `CANCEL`。回链：07。

**G4. keepalive 是什么？**
HTTP/2 `PING` 帧；可配间隔/超时/无调用是否允许（`permitWithoutCalls`）——服务端通常禁无调用 PING。回链：07。

**G5. 为什么一条连接能跑很多请求？**
HTTP/2 流多路复用；gRPC **一次调用 = 一个 stream**，连接可复用。回链：07。

### H. 服务发现与生产

**H1. NameResolver 是干什么的？**
把 target 字符串解析成地址列表；**DNS 只是默认实现**，注册中心=换 scheme（自定义 Provider + `Listener2.onResult`）。回链：08。

**H2. 默认负载均衡策略是什么？**
**pick_first**（`grpc-core` SPI 只注册它）；`round_robin` 由 `grpc-util` 注册。回链：08。

**H3. 配了 `round_robin` 没轮询，为什么？（陷阱）**
先查依赖树有没有 `grpc-util`（本 lab 由 grpc-services 传递引入）；缺了不是静默退化——**RPC 直接以 `INTERNAL: Could not find policy` 失败**（`AutoConfiguredLoadBalancerFactory.java:56-63`）。回链：08。

**H4. pick_first 的后端挂了会怎样？**
顺序尝试下一个地址（failover）：`addressIndex.increment()` + `requestConnection()`（`PickFirstLeafLoadBalancer.java:356-365`）；全挂则 TRANSIENT_FAILURE + 退避。回链：08。

**H5. Health 怎么用于发布？**
服务端 `HealthStatusManager.setStatus`；发布流程：翻 `NOT_SERVING` → 等探活/网关摘流 → 优雅关闭。回链：03/08。

**H6. Reflection 有什么用？风险？**
让客户端**无 proto 动态发现**服务（grpcurl 原理）；生产默认关（暴露接口清单），要用加鉴权。回链：08。

### I. 对照与边界

**I1. gRPC vs Dubbo vs Feign 怎么选？**
gRPC：跨语言/流式/云原生标准；Dubbo：Java 生态服务治理（路由/容错/标签），Triple 兼容 gRPC；Feign：HTTP/JSON 声明式客户端（Spring Cloud 生态）。回链：Dubbo 11 / 00。

**I2. Triple 和 gRPC 的关系？**
Triple 是 HTTP/2 上的 RPC：**protobuf 模式与 gRPC 服务互通**（`grpc=GrpcProtocol` 同族），另有非 protobuf 模式；Dubbo 3 用它打通 gRPC 生态。回链：Dubbo 11。

**I3. gRPC 的 TLS 边界？（边界题）**
TLS + ALPN 协商 h2；支持 mTLS；netty 侧用 `GrpcSslContexts`/`ClientCredentials`（07 附录坐标）——细节本系列不展开。回链：07 附录。

**I4. protobuf 演进规则？（边界题）**
字段号永不复用/改类型、新增字段给默认值、`reserved` 防误用——一句话：**只增不改不删号**。回链：00 §五。

**I5. xDS/grpclb 要不要学？**
控制面负载均衡（服务网格集成），面试低频；本系列明确不覆盖（00 §五）。回链：00。

---

## 三、陷阱速查（15 连）

| # | 反直觉点 | 正解 |
|---|---|---|
| 1 | 重试要 `enableRetry()` | 1.83.1 默认开；真正的开关是 service config `retryPolicy` |
| 2 | 没配策略也会重试 | 没配只有 transparent retry（安全重放） |
| 3 | `maxAttempts` 是重试次数 | 含首次；且被 `maxRetryAttempts`（默认 5）钳制 |
| 4 | 先 add 的拦截器最外层 | 最后 add 的最外层（最先执行） |
| 5 | 拦截器里直接调 `sendHeaders` | 只能一次；要包装 ServerCall 覆写 |
| 6 | `-bin` 要自己 base64 | 框架按 key 后缀自动 base64 |
| 7 | `request(n)` 只是"让对端发 n 条" | 交付缓冲 + 回补窗口，两面 |
| 8 | `MAX_CONCURRENT_STREAMS=0` 是服务端限制 | 是本端通告"不收对端流"（客户端禁用 server push） |
| 9 | deadline 只看调用时传的 | `effectiveDeadline = min(CallOptions, Context)` |
| 10 | 子调用超时独立 | 自动继承父 Context 的 deadline |
| 11 | round_robin 缺依赖会退化 | 不是退化，RPC 以 INTERNAL 失败 |
| 12 | pick_first 挂一个就全挂 | 会顺序换地址（failover） |
| 13 | Health = 心跳 | Health 是应用层服务；心跳是传输层 PING |
| 14 | 取消是关连接 | 只 `RST_STREAM(CANCEL)` 单条流 |
| 15 | 写队列逐条 flush | 128 条分块 flush |

---

## 四、证据索引（自测后回看）

| 篇 | 实验 | 关键证据 |
|---|---|---|
| 01 | `scripts/01-hello-world.sh` | `evidence/01-hello-world.log`（三 Stub + 抓包） |
| 02 | `scripts/02-call-chain-retry.sh` | `evidence/02-call-chain-retry.log`（重试 3 次/对冲） |
| 03 | `scripts/03-server-lifecycle.sh` | `evidence/03-server-lifecycle.log`（生命周期/优雅关闭） |
| 04 | `scripts/04-interceptor-metadata.sh` | `evidence/04-interceptor-metadata.log`（顺序/metadata/错误码） |
| 05 | `scripts/05-streaming.sh` | `evidence/05-streaming.log`（四种模式/流控） |
| 06 | `scripts/06-deadline-context.sh` | `evidence/06-deadline-context.log`（超时/取消/继承） |
| 07 | `scripts/07-http2-frames.sh` | `evidence/07-http2-frames.log`（帧抓包） |
| 08 | `scripts/08-discovery-health.sh` | `evidence/08-discovery-health.log` + `08-grpc-util-transitive.log` |

---

## 五、一页纸收束

> **协议**：HTTP/2 多路复用 + protobuf 契约 + 生成 Stub；unary/三种流。
> **调用**：Stub → ClientCall → 拦截器 → ClientStream → Transport；重试默认开（策略决定），`maxAttempts` 含首次且被 5 钳制，重试要幂等。
> **服务端**：ServerBuilder 多服务按 method 路由；优雅下线 = Health 摘流 + shutdown + awaitTermination。
> **元数据**：Metadata（`-bin` 自动 base64，默认 8KB）+ Status 错误模型；拦截器最后 add 最外层。
> **流控**：`request(n)` 两面；onReady 是发送许可。
> **上下文**：ContextRunnable 包装回调；`effectiveDeadline=min`；取消 = RST_STREAM(CANCEL)。
> **传输**：gRPC 消息 → DATA 帧；写队列 128 分块。
> **生产**：NameResolver（DNS 只是实现）+ pick_first/round_robin（缺 grpc-util → INTERNAL）+ Health/Reflection。
