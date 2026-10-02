# Lesson 10: Connector / Endpoint 主干 — 端口、连接、线程

> 课程索引：`../学习清单.md` → Stage 3 · Lesson 10
> 版本口径：Tomcat **10.1.34**（类名口径；本课不逐行、不背行号）
> 来源：`webapps/docs/config/http.xml`（连接器定义）；调用链按 Tomcat 类名整理（Stage 3 规范：只跟类/方法名）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L10-5 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 按**类名**走通"端口→连接→就绪→执行"主干：装配链、启动链、请求链；回答"类是怎么拼起来、启动时各干什么、请求怎么流过 Endpoint" |
| 前置 | L02（启动顺序）、L03（线程模型）、L04（请求进入容器后） |
| 不涉及 | 逐行解读与行号；HTTP 解析细节；参数语义（L09） |
| 下一课 | L11 Mapper 匹配主干 |

> Stage 3 读法：记住**类名 + 方法名 + 先后顺序**，不记实现细节；需要回看具体逻辑时按类名进源码。

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **装配链（类名）**：`Connector("HTTP/1.1")` → `ProtocolHandler.create()` → `Http11NioProtocol` → `new NioEndpoint()`。记住三层分工：**端口与配置入口 / 协议与参数 / IO 实现（线程/绑定/闸门）**。
2. **启动链**：`Endpoint.start()` → 未绑定则先 `bindWithCleanup`（真正的端口绑定）→ `startInternal()`：缓存 → `createExecutor`（线程池）→ 初始化连接闸门 → 启动 `Poller` 线程 → 启动 `Acceptor` 线程。
3. **请求链**：`Acceptor.run()`（闸门计数 → `serverSocketAccept` → 注册到 Poller）→ `Poller.run()`（消费事件 → `select` → `processKey` → `processSocket` 派发到 Executor）→ `SocketProcessor.doRun()`（Worker）→ `ConnectionHandler.process()` → `Http11Processor.service()`（解析请求行/头 → `CoyoteAdapter` → L04）。

---

## 2. 官方原文（`config/http.xml` · Introduction，节选）

> The **HTTP Connector** element represents a **Connector** component that supports the HTTP/1.1 protocol. It enables Catalina to function as a **stand-alone web server**, in addition to its ability to execute servlets and JSP pages. A particular instance of this component **listens for connections on a specific TCP port** number on the server. One or more such **Connectors** can be configured as part of a single **Service**, each forwarding to the associated **Engine** to perform request processing and create the response.

**翻译**：HTTP Connector 是一个支持 HTTP/1.1 的 Connector 组件；它使 Catalina 既能执行 Servlet/JSP，又能作为独立 Web 服务器；每个实例在**特定 TCP 端口**监听连接；一个 Service 可配多个 Connector，每个 Connector 都向关联的 Engine 转发请求以处理并生成响应。

### ① 核心结论

**Connector = 端口监听 + HTTP 解析 + 转发给 Engine**：既是"门"（端口），也是"Catalina 当独立服务器"的实现主体。

### ② 机制说明

- 默认协议 `HTTP/1.1` 对应 **Java NIO 实现**（L03 已引 `Http11NioProtocol`）；
- Connector 并不自己做 IO：真正的线程/绑定/闸门在更深的 **Endpoint**（§3）；
- "向 Engine 转发"这句对应整条请求链的终点（§5）。

### ③ 官方要点（3 条）

1. HTTP 连接器支持 HTTP/1.1（NIO 实现）；
2. 每个实例在特定 TCP 端口监听；
3. 多个 Connector 可属同一 Service，都向 Engine 转发。

### ④ 术语对照

| 官方英文 | 中文 | 说明 |
|---|---|---|
| Connector component | 连接器组件 | 端口入口 |
| stand-alone web server | 独立 Web 服务器 | 内嵌之外的形态 |
| forward to the Engine | 转发给引擎 | 请求链的方向 |

### ⑤ 结论

> **Connector 是"端口 + HTTP + 转发"的入口：配置在这里，网络字节最后经它变成 Engine 能收的请求。**

---

## 3. 装配链（概念级）

```
Tomcat.getConnector()
  → new Connector("HTTP/1.1")
      → ProtocolHandler.create("HTTP/1.1")  → new Http11NioProtocol()
          → （构造时）new NioEndpoint()
```

| 层 | 类 | 负责什么 |
|---|---|---|
| 端口与配置入口 | `Connector` | 端口、属性（Adapter/超时是参数层面），对外 API |
| 协议与参数 | `Http11NioProtocol`（抽象父 `AbstractProtocol`） | HTTP 语义、协议级参数、驱动 Endpoint 启停 |
| IO 实现 | `NioEndpoint`（抽象父 `AbstractEndpoint`） | 绑定 socket、Acceptor/Poller 线程、工作线程池、连接闸门 |

---

## 4. 启动链（概念级）

```
Endpoint.start()
  → 未绑定？→ bindWithCleanup()        ← 真正的端口绑定发生在这里
  → startInternal()
      → 缓存/通道池初始化（processorCache / eventCache / nioChannels）
      → createExecutor()               ← Worker 线程池（没有则新建）
      → initializeConnectionLatch()    ← LimitLatch（maxConnections 闸门）
      → 启动 Poller 线程               ← 就绪检测
      → 启动 Acceptor 线程             ← 接收连接
```

> **与既有知识的连接点**：
> - "端口最后监听"（L02）：端口绑定发生在 `Endpoint.start()` 的 bind 步骤；Boot 用 `removeServiceConnectors` 让整个 start 延后；
> - `bindOnInit=false`（Boot 默认）→ bind 在 **start** 时发生；=true 则在 init 时绑定——这正是 `closeServerSocketGraceful` 生效的前提（L07）。

---

## 5. 请求链（概念级）

```
Acceptor.run（循环）
  → countUpOrAwaitConnection()        ← LimitLatch：满则阻塞（09 篇）
  → serverSocketAccept()              ← 拿新连接
  → setSocketOptions() + Poller.register()   ← 登记 OP_READ 就绪兴趣

Poller.run（循环）
  → 消费注册队列（events）
  → selector.select(selectorTimeout)  ← 等就绪（1000ms 周期，L03）
  → processKey（逐 key 处理）
  → processSocket(socketWrapper, OPEN_READ, dispatch=true)
  → Executor.execute(SocketProcessor)       ← 派发到 Worker

SocketProcessor.doRun（Worker 线程）：
  → ConnectionHandler.process()
      → 取/建 Processor（HTTP/1.1 → Http11Processor）
      → Http11Processor.service()      ← 解析请求行/请求头/请求体缓冲
      → CoyoteAdapter.service()        ← 跨层：Coyote → Catalina（L04）
```

> 与 L03/L04 的接口点：此链的起点在 Acceptor，终点在 `CoyoteAdapter`；中间经过 Poller 派发与 Worker 执行——整条链不涉及业务逻辑（业务在 Servlet，L04 之后）。

---

## 6. 线程交接图（谁把活递给谁）

```
Acceptor（接收线程：accept）
   │  latch 计数 + accept + 注册 OP_READ
   ▼
Poller（就绪线程：select）
   │  就绪 → 触发 Executor
   ▼
Worker（执行业务线程池里的一个线程）
   │  解析 → 跨层 → 容器 → Servlet → 响应
   ▼
keep-alive 的连接 → 回 Poller 等下一次请求
错误/新连接 → 按事件继续走上述循环
```

> 错误事件（`ERROR`）同样走 `processSocket` 派发路径（概念级：错误与读写是同一条提交机制）。

---

## 7. 术语对照（本课汇总）

| 类/术语 | 中文 | 一句话 |
|---|---|---|
| Connector | 连接器 | 端口入口 |
| ProtocolHandler / Http11NioProtocol | 协议处理器 | HTTP 语义与参数 |
| Endpoint / NioEndpoint | 端点 | IO 实现（线程/绑定/闸门） |
| Acceptor | 接收线程 | accept 新连接 |
| Poller | 轮询线程 | select 就绪 + 派发 |
| SocketProcessor | 套接字处理器 | Worker 侧的任务载体 |
| ConnectionHandler | 连接处理器 | 从连接拿 Processor 并执行 |
| Http11Processor | HTTP 处理器 | 解析请求行/头 |
| LimitLatch | 连接闸门 | maxConnections 的实现 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. Connector → ProtocolHandler → Endpoint 三层各自负责什么？**
> 参考答案：Connector 是端口与配置入口；ProtocolHandler 是协议语义与参数（HTTP/1.1 = Http11NioProtocol）；Endpoint 是 IO 实现（绑定 socket、Acceptor/Poller 线程、工作线程池、连接闸门）。

**Q2. 端口在启动链的哪一步真正绑定？**
> 参考答案：在 `Endpoint.start()` 里：未绑定则 `bindWithCleanup()`，之后才 `startInternal()`（建池、起 Poller、起 Acceptor）。

**Q3. 一个请求被 Acceptor 接收后，经过了哪些步骤才到业务代码？**
> 参考答案：闸门计数 → accept → 注册 OP_READ 到 Poller → Poller select 就绪 → processSocket 派发到 Executor → Worker 的 SocketProcessor → ConnectionHandler → Http11Processor 解析 → CoyoteAdapter 跨层。

**Q4. 为什么 Boot 要 `bindOnInit=false`？**
> 参考答案：让 bind 发生在 start 时（而非 init），配合"容器先就绪、Connector 后启动"的顺序；这也是 `closeServerSocketGraceful` 生效的前提（L07）。

**Q5. keep-alive 的连接处理完后去哪里？**
> 参考答案：不销毁，回 Poller 注册等待下一次请求（由 Poller 管理读写兴趣）。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 下一步：**L11 Mapper 匹配主干**——Stage 3 第二节：按类名走"URI → Host/Context/Wrapper 匹配 + 路由表维护"（不逐行）。
