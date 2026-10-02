# 面试题详解 02 | 内嵌 Tomcat 的线程模型（Acceptor / Poller / Worker）

> 难度：★★★★☆ ｜ 频率：★★★★☆ ｜ 区分度：高（线程模型 + 参数语义 + 与 Netty 对照）
> 关键词：Acceptor、Poller、Worker 线程池、maxThreads、acceptCount、maxConnections、LimitLatch、TaskQueue、`http-nio-8080-exec-*`、慢客户端、虚拟线程

---

## 问题

**内嵌 Tomcat 的线程模型是什么样？Acceptor / Poller / Worker 各干什么、各有几个？`server.tomcat.threads.max`、`accept-count`、`max-connections` 分别管什么？和 Netty 的 EventLoop 模型有什么区别？**

---

## 30 秒简洁回答

> Tomcat 10.1 的 NIO 端点分三层：**1 个 Acceptor 线程**只做 `accept()`（连接总数受 `maxConnections` 限制，满了会等待）；**1 个 Poller 线程**跑 epoll/select 检测就绪事件，把就绪的 socket 交给线程池；**Worker 线程池**（`maxThreads` 默认 200，线程名 `http-nio-8080-exec-*`）执行 HTTP 解析 + Servlet 业务。Spring Boot 映射：`server.tomcat.threads.max/min-spare` → Worker 池；`server.tomcat.accept-count` → `bind()` 的 backlog（默认 100）；`server.tomcat.max-connections` → 连接上限（默认 8192）。与 Netty 的区别：Netty 用少量 EventLoop 线程**既做 IO 又跑业务**（全异步）；Tomcat 把"就绪检测"与"业务执行"拆开，Worker 里是**同步阻塞语义**（Servlet API），慢客户端会占住 worker。

---

## 展开回答（高级开发级）

### 1）三层线程（源码）

```
① Acceptor（1 个）
   · 创建：AbstractEndpoint.startAcceptorThread():1395-1403，线程名 getName() + "-Acceptor"
   · 职责：循环 accept()；每接受一个连接 countUpOrAwaitConnection()（:1477-1485，基于 LimitLatch :1464）
   · 把新连接注册到 Poller

② Poller（1 个）
   · 创建：NioEndpoint.startInternal():277-282，线程名 getName() + "-Poller"，setDaemon(true) 硬编码
   · 职责：select/epoll 检测就绪 → processKey → processSocket(socketWrapper, OPEN_READ, true)（AbstractEndpoint:1247-1280）
   · 被 Executor 拒绝时返回 false → Poller 关连接（NioEndpoint:823-825）

③ Worker 线程池（默认 maxThreads=200）
   · 创建：AbstractEndpoint.createExecutor():1089-1091
       ThreadPoolExecutor(minSpareThreads=10, maxThreads=200) + TaskQueue
       线程工厂：TaskThreadFactory(getName() + "-exec-", daemon, priority)（:1090）
   · 职责：SocketProcessor → Http11Processor 解析 → CoyoteAdapter → Valve → Filter → Servlet
```

> 注意（容易答错的点）：Tomcat 10.1 的 NioEndpoint 就是 **1 Acceptor + 1 Poller**（全仓没有 `acceptorThreadCount` / `pollerThreadCount` 这样的配置）。accept 本身极快、epoll 是批量检测，瓶颈在 Worker，所以不需要多 IO 线程。

### 2）线程名从哪来（jstack 定位用）

- 协议处理器名：`AbstractProtocol.getNameInternal():409-431` → `<prefix>-<port>`，即 `http-nio-8080`；
- 于是线程名是：`http-nio-8080-Acceptor`、`http-nio-8080-Poller`、`http-nio-8080-exec-N`；
- Spring Boot 额外还有一个非 daemon 的 `container-N` 线程（`TomcatWebServer:214-225`，见 01 篇）。

### 3）四个容量参数各管什么（嵌入式映射）

| Spring Boot 属性 | 默认 | 落到哪（源码） |
|---|---|---|
| `server.tomcat.threads.max` | 200 | Worker 池 `maxThreads`（`TomcatWebServerFactoryCustomizer:100/163-164` → `AbstractProtocol.setMaxThreads:247-249` → endpoint） |
| `server.tomcat.threads.min-spare` | 10 | Worker 池 `minSpareThreads`（`:103/168-169`） |
| `server.tomcat.accept-count` | 100 | `serverSock.bind(addr, acceptCount)` 的 **backlog**（`NioEndpoint:217/239`） |
| `server.tomcat.max-connections` | 8192 | 连接总数上限（`LimitLatch`，`AbstractEndpoint:548/:1464`） |
| `server.tomcat.threads.max-queue-capacity` | 2147483647 | `TaskQueue` 上限（`AbstractEndpoint:818`） |

**三种"满了"的行为**（高频追问）：

```
maxConnections 满 → Acceptor 在 countUpOrAwaitConnection() 等待（不 accept 新连接，连接堆在内核 backlog）
acceptCount 满   → 内核直接拒绝/丢弃新连接（客户端表现为超时/被拒）
线程池 + 队列满  → processSocket 捕获 RejectedExecutionException → 返回 false → 连接被关
                  （日志 "endpoint.executor.fail"，AbstractEndpoint:1268-1269 / NioEndpoint:823-825）
```

### 4）慢客户端会占住 worker（关键边界）

- Worker 处理请求时若数据没到齐，会 park：`readBlocking=true; readLock.wait(timeout)`（`NioEndpoint:1273-1282`）；
- 由 Poller 在可读时唤醒（`readLock.notify()`，`:818-821`；写侧对应 `writeLock`，`:834-835`）；
- 所以：**慢客户端占用 worker**（thread-per-request 语义的代价）；防护靠 `connectionTimeout` / `keepAliveTimeout` / `maxKeepAliveRequests`（见 09 篇）。
- Keep-Alive **间隔期**：worker 释放、连接回到 Poller——`AbstractProtocol:973-979`（`SocketState.OPEN` 分支，注释："In keep-alive but between requests. OK to recycle processor. Continue to poll for the next request."）；只有"正在处理中"、已读到半个请求（`SocketState.LONG` → `longPoll → registerReadInterest`，`:1076-1081`）或 async/upgrade 时才与处理器保持关联。

### 5）与 Netty 对照（一表）

| | Tomcat NIO | Netty |
|---|---|---|
| IO 线程职责 | Acceptor/Poller **只做就绪检测** | EventLoop **既做 IO 又跑 handler** |
| 业务执行 | Worker 池（同步 Servlet 语义） | 同一 EventLoop（异步、跨事件状态机） |
| 默认线程数 | 1 + 1 + 200 | 2×CPU（EventLoopGroup） |
| 等待的代价 | 慢客户端 park 占 worker | 等待由共享的 epoll 承担，不占线程 |
| 编程模型 | thread-per-request + 阻塞 API | 事件驱动 + 回调/状态机 |

### 6）现代变量：虚拟线程（Boot 3.5）

开启 `spring.threads.virtual.enabled=true` 后，`TomcatVirtualThreadsWebServerFactoryCustomizer` 会给协议处理器换执行器（源码原文，`TomcatVirtualThreadsWebServerFactoryCustomizer.java:38-39`）：

```java
factory.addProtocolHandlerCustomizers(
        (protocolHandler) -> protocolHandler.setExecutor(new VirtualThreadExecutor("tomcat-handler-")));
```

线程名前缀因此变为 `tomcat-handler-*`。此时 Worker 固定池（`maxThreads` / `min-spare`）**不生效**（`ServerProperties.Tomcat.Threads` 注释明确写了这点）；阻塞语义不变，但"慢客户端占线程"的成本被虚拟线程摊薄。

---

## 进一步回答（架构师层级）

- **为什么 Tomcat 不做 Netty 那种全异步**：Servlet 规范与生态（JDBC/阻塞库/同步 API）决定了 thread-per-request 语义；直到 Servlet 3.0 的 `AsyncContext` 才给出"释放 worker"的通道（见 08 篇）。
- **maxThreads 怎么定**：并发处理中的请求数 ≈ QPS × RT（Little's law）；IO 密集可放宽、CPU 密集看核数；不是越大越好（上下文切换与内存）。
- **acceptCount 与 LB 的配合**：LB 队列 + `acceptCount` 决定过载时"先丢谁"；两者与连接超时要一起设计。
- **可观测性**：`tomcat.threads.busy/current`、`tomcat.connections.current/max`（Actuator metrics）；`jstack` 看 `http-nio-8080-exec-*` 状态（RUNNING vs WAITING/TIMED_WAITING）。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：这道题考"Tomcat 怎么把海量连接和一个有限的业务线程池解耦"；
- **回答顺序**：三层线程（1+1+200）→ 四个容量参数与"满了会怎样" → 慢客户端占 worker 的边界 → 与 Netty 对照；
- **一句话收束**："Acceptor 接客、Poller 看谁有事、Worker 干活；连接数靠 maxConnections/backlog 控，处理能力靠 worker 池控，慢客户端靠超时参数兜。"

## 必记关键词

`Acceptor` ｜ `Poller` ｜ `Worker 线程池` ｜ `maxThreads(200)` ｜ `acceptCount(100)` ｜ `maxConnections(8192)` ｜ `LimitLatch` ｜ `TaskQueue` ｜ `readLock.wait` ｜ `http-nio-8080-exec-*` ｜ `VirtualThreadExecutor`

## 必须明确的边界

- Tomcat 10.1 NioEndpoint = **1 Acceptor + 1 Poller**（不是每 CPU 一个）；
- `acceptCount` 是**内核 backlog**（`bind()` 参数），`maxConnections` 是**应用层连接计数**，两者不是一回事；
- `maxThreads` 管的是**处理中请求**的并发数，不是连接数（Keep-Alive 空闲连接不占）；
- Poller 不做业务：就绪只"派发"，解析与 Servlet 在 Worker；
- 队列默认近乎无界（`Integer.MAX_VALUE`），所以"池满"通常先表现为**延迟上升/线程全忙**，而不是立刻拒绝。

## 常见错误

- 把 Poller 说成 N 个（每 CPU 一个）——那是 Netty/其他模型；
- 把 `maxConnections` 当成 `maxThreads`（连接数 ≠ 并发处理数）；
- 说 `acceptCount` 是线程数（它是 backlog）；
- 忽略"慢客户端 park 占 worker"这一边界（只答参数、不答语义）;
- 与 Netty 对照时只说"都是 NIO"，说不清"IO 与业务是否分离"。

## 自测要求

- **30 秒**：1+1+200 三层 + 四个参数 + 一张对照表；
- **3 分钟**：能画"accept → register → 就绪 → worker → Servlet"的数据流，并解释"三种满了"的行为；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：为什么 Acceptor/Poller 各只有 1 个？为什么不用多线程 accept？**
> accept 本身很快、epoll 是批量检测，真正的瓶颈在 Worker（业务）；Tomcat 10.1 全仓没有 `acceptorThreadCount`/`pollerThreadCount` 配置。连接规模的上限由 `maxConnections` 控，处理能力上限由 Worker 池控。

**追问 2：`maxThreads` 满了会怎样？**
> 请求先进入 `TaskQueue` 排队；因为默认队列近乎无界（`maxQueueCapacity=Integer.MAX_VALUE`），通常表现为延迟上升/线程全忙；若显式设了 `max-queue-capacity` 且队列也满，`processSocket` 会捕获 `RejectedExecutionException`、返回 false，连接被关闭（日志 `endpoint.executor.fail`）。

**追问 3：慢客户端为什么能拖垮 Tomcat？Netty 为什么不会？**
> Tomcat 的 Worker 在数据未到齐时会 park（`readBlocking=true` → `readLock.wait(timeout)`，NioEndpoint:1273-1282），慢客户端占住一个 Worker；Netty 的等待由共享 epoll 承担、事件处理器不绑线程，所以不会被单连接占死。防慢客户端要靠 `connectionTimeout`/`keepAliveTimeout`/`maxKeepAliveRequests`。

---

## 示例与使用说明

```bash
# 验证线程模型（嵌入式视角）
jstack <pid> | grep -E "http-nio-8080-(Acceptor|Poller|exec)|container-"
# 典型输出：
#   http-nio-8080-Acceptor        （1 个）
#   http-nio-8080-Poller          （1 个，daemon）
#   http-nio-8080-exec-N          （Worker 池，默认最多 200）
#   container-N                   （Spring Boot 非 daemon await 线程）
```

配置示例：

```yaml
server:
  tomcat:
    threads:
      max: 400          # Worker 池上限
      min-spare: 20
    accept-count: 200   # bind backlog
    max-connections: 20000
```

## 面试官评分点

**通过标准**
- 能讲清 1 Acceptor + 1 Poller + Worker 池的分工；
- 能区分 `maxThreads` / `acceptCount` / `maxConnections`；
- 能说"慢客户端占 worker"的边界。

**加分项**
- 能讲"三种满了"的行为差异与默认队列近乎无界的事实；
- 能对照 Netty 讲"IO 与业务是否分离"；
- 知道 Boot 3.5 虚拟线程会替换固定池（`maxThreads` 不生效）。

**危险信号**
- 说 Poller 有 N 个/按 CPU 配；
- 把 `acceptCount` 当线程数；
- 只会背参数、说不清 worker park 的语义。

## 实战练习

1. 起一个内嵌应用，`jstack` 核对四类线程名与数量；
2. 用 `server.tomcat.threads.max=5` + `delay` 接口（sleep 2s）压测，观察队列排队与延迟上升；再看设 `threads.max-queue-capacity` 后的拒绝行为；
3. 用 `nc` 慢速发送请求体，`jstack` 观察 worker 停在 `readLock`/TIMED_WAITING 的形态。

## 版本与来源

- Tomcat **10.1.34**：`AbstractEndpoint` / `NioEndpoint.java`（`java/org/apache/tomcat/util/net/`）、`AbstractProtocol.java`（`java/org/apache/coyote/`）；
- Spring Boot **3.5.16**：`TomcatWebServerFactoryCustomizer`、`TomcatVirtualThreadsWebServerFactoryCustomizer`（`spring-boot-autoconfigure`）、`ServerProperties.java`；
- 本卷章节：02（NIOEndpoint 线程）、11（并发与线程池）、16（生产性能）；
- 规划：`Tomcat源码学习范围规划.md` T-2（请求进入与协议处理闭环）。

## 个人经历填充区（留空，按真实项目补）

- 你项目里 `maxThreads`/`accept-count`/`max-connections` 怎么定的？依据是什么？
- 遇到过线程池打满/慢客户端占线程的事故吗？怎么定位与缓解？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Spring Boot 3.5.16）；`jstack` 输出为形态示例（未在本文附真实运行输出）；项目经历请用真实案例填充。
