# Lesson 00: Prerequisites — 前置最小包

> 课程索引：`../gateway-学习清单.md` → Stage 0 · Lesson 00
> 来源：Lesson 01 暴露出的后向依赖（详见 `concept-index.md`）
> 深度约定：**每个概念只讲到"能读懂 Lesson 01 与后续相关课"为止**，不做学科级展开
> 状态：✅ 已讲（2026-09-22）
> **提问方式：直接说编号**，例如「0.3 ② 没懂」

---

## 0.0 本课覆盖范围

| # | 概念 | 为什么需要它 |
|---|---|---|
| 0.1 | 反向代理（reverse proxy） | "网关"这个词的技术基础 |
| 0.2 | Servlet 与 Servlet 容器 | Lesson 01 说"不能跑在 Servlet 容器"，得先知道那是什么 |
| 0.3 | 同步阻塞 IO vs 非阻塞 IO | "阻塞"是 Gateway 一切约束的根源 |
| 0.3.1 | Reactive Streams | 背压的标准化规范；WebFlux 的底座契约 |
| 0.4 | 事件循环（EventLoop） | "非阻塞"在服务端的具体实现方式 |
| 0.4.1 | 「阻塞」到底指什么 | 澄清"Tomcat 也是 NIO"的关键误解 |
| 0.4.2 | 等待时到底谁在干活 | 澄清"回调 = 另有线程池"的常见追问 |
| 0.4.3 | Channel 是谁的东西 | 澄清"网关怎么和 Tomcat 建 Channel" |
| 0.4.4 | Reactor Netty | WebFlux 与 Netty 之间的传输层 |
| 0.4.5 | 网关进程的线程清单 | 有几种线程、各自负责什么（实测确定） |
| 0.4.6 | `Mono` 的底层实现 | `ChannelFuture` + `addListener`（源码实证） |
| 0.4.7 | 实战案例：RV33 vs RV34 | 阻塞式 Redis 卡在 EventLoop 上（含修复与提交） |
| 0.4.8 | boundedElastic 完整拆解 | 槽位弹性 / 引用计数 / Worker 串行语义 |
| 0.5 | 命令式 vs 响应式 | 两种编程模型，决定代码怎么写 |
| 0.5.1 | Mono 与 Flux | Reactor 的核心类型（0..1 / 0..N、惰性、订阅） |
| 0.6 | Spring WebFlux | Gateway 的宿主框架；Lesson 01 只给了名字 |

> 注：**AOP / 环绕通知** 不在本课（已确认你掌握）。Lesson 01 的 1.1.3 用它对照责任链，属于已知概念。

---

## 0.1 Reverse Proxy — 反向代理

**英文原文** — MDN《Proxy servers and tunneling》

> There are two types of proxies: **forward proxies** (or tunnel, or gateway) and **reverse proxies** (used to control and protect access to a server for load-balancing, authentication, decryption or caching).
>
> A forward proxy, or gateway, or just "proxy" provides proxy services to a client or a group of clients.
>
> As the name implies, a reverse proxy does the opposite of what a forward proxy does: **A forward proxy acts on behalf of clients (or requesting hosts). Forward proxies can hide the identities of clients whereas reverse proxies can hide the identities of servers.** Reverse proxies have several use cases, a few are:
> - Load balancing: distribute the load to several web servers,
> - Cache static content: offload the web servers by caching static content like pictures,
> - Compression: compress and optimize content to speed up load time.

**中文翻译**

> 代理有两类：**正向代理（forward proxy）**（也叫隧道或网关）与**反向代理（reverse proxy）**（用于控制并保护对服务器的访问，服务于负载均衡、认证、解密或缓存）。
>
> 正向代理（或简称代理）为**一个客户端或一组客户端**提供代理服务。
>
> 顾名思义，反向代理做的事与正向代理相反：**正向代理代表客户端（发起请求的一方）行事；正向代理可以隐藏客户端的身份，而反向代理隐藏的是服务器的身份。** 反向代理的典型用途：
> - 负载均衡：把负载分发到多台 Web 服务器；
> - 缓存静态内容：通过缓存图片等静态资源减轻 Web 服务器负担；
> - 压缩：压缩并优化内容以加快加载速度。

**① 核心结论**

正向代理隐藏**客户端**身份，代表客户端行事；反向代理隐藏**服务器**身份，代表服务器接收请求。二者是"代理"在信任方向上的两个相反方向。

**② 机制说明**

判断一个代理是正向还是反向，只需问一个问题：**它站在谁的位置上？**

| 维度 | 正向代理 | 反向代理 |
|---|---|---|
| 站在谁的位置 | 客户端 | 服务端 |
| 隐藏谁的身份 | 客户端（服务端不知道真实客户端是谁） | 服务端（客户端以为只有一台服务器） |
| 谁主动配置 | 客户端主动配置代理地址 | 服务端部署，客户端无感知 |
| 典型产品 | 浏览器代理、VPN、Tor | Nginx、HAProxy、**API Gateway** |
| 典型用途 | 科学上网、匿名访问、内网出口 | 负载均衡、TLS 终止、缓存、统一入口 |

**关键推论（对 Gateway 的理解至关重要）**：

反向代理对客户端是**透明的**——客户端认为自己直连的就是真正的服务端。因此：

- **客户端只能看到反向代理的地址**，看不到背后有几台服务器、分别是什么
- **服务端只能看到反向代理的地址**，看不到真实客户端 → 这是 X-Forwarded-For 存在的根本原因（Lesson 31）

**③ 官方要点**

MDN 明确列出反向代理的三个用途，且第一个即 Gateway 的核心职责：

1. **Load balancing** —— 负载均衡（对应 Gateway 的 `lb://`，Lesson 16）
2. **Authentication** —— 认证（对应 Gateway 的 JWT / HMAC，Lesson 32）
3. **Caching / Compression** —— 缓存与压缩（Gateway 通过 filter 实现）

> 官方把 `gateway` 列为正向代理的别名（"forward proxies (or tunnel, or **gateway**)"）。**此处的 gateway 与 API Gateway 不是同一概念**：前者是网络层代理设备，后者是应用层架构组件。注意区分。

**④ 术语对照**

| 英文 | 中文 |
|---|---|
| forward proxy / tunnel / gateway | 正向代理 / 隧道 / （网络层）网关 |
| reverse proxy | 反向代理 |
| hide the identities of clients / servers | 隐藏客户端 / 服务端身份 |
| load balancing | 负载均衡 |
| TLS termination | TLS 终止（HTTPS 解密在代理处完成） |

**⑤ 结论**

> **反向代理 = 站在服务端位置、对客户端透明的代理。API Gateway 是反向代理在应用层的实现。**

---

## 0.2 Servlet and Servlet Container — Servlet 与 Servlet 容器

**英文原文** — `jakarta.servlet.Servlet` 接口 Javadoc（Tomcat 源码所带官方接口定义）

> Defines methods that all servlets must implement.
>
> A servlet is a **small Java program that runs within a Web server**. Servlets receive and respond to requests from Web clients, usually across HTTP, the HyperText Transfer Protocol.
>
> To implement this interface, you can write a generic servlet that extends `jakarta.servlet.GenericServlet` or an HTTP servlet that extends `jakarta.servlet.http.HttpServlet`.
>
> This interface defines methods to initialize a servlet, to service requests, and to remove a servlet from the server. These are known as **life-cycle methods** and are called in the following sequence:
> 1. The servlet is constructed, then initialized with the `init` method.
> 2. Any calls from clients to the `service` method are handled.
> 3. The servlet is taken out of service, then destroyed with the `destroy` method, then garbage collected and finalized.

**英文原文 2** — Tomcat `architecture/overview.xml`

> In the Tomcat world, a **Server** represents the whole container.
> A **Service** is an intermediate component which lives inside a Server and ties one or more **Connectors** to exactly one **Engine**.
> An **Engine** represents request processing pipeline for a specific Service. As a Service may have multiple Connectors, the Engine receives and processes all requests from these connectors, handing the response back to the appropriate connector for transmission to the client.
> A **Host** is an association of a network name, e.g. `www.yourcompany.com`, to the Tomcat server.
> **Context** - In a nutshell, a Context is a web application.

**中文翻译**

> 定义所有 Servlet 必须实现的方法。
>
> Servlet 是**运行在 Web 服务器内部的 Java 小程序**。Servlet 接收并响应来自 Web 客户端（通常经由 HTTP 协议）的请求。
>
> 实现该接口的方式：编写继承 `jakarta.servlet.GenericServlet` 的通用 Servlet，或继承 `jakarta.servlet.http.HttpServlet` 的 HTTP Servlet。
>
> 该接口定义了初始化 Servlet、处理请求、以及从服务器移除 Servlet 的方法，即**生命周期方法**，调用顺序为：
> 1. 构造 Servlet，随后通过 `init` 方法初始化；
> 2. 处理来自客户端的 `service` 调用；
> 3. 停止服务，通过 `destroy` 方法销毁，随后被 GC 回收。

> Tomcat 架构：**Server** 代表整个容器；**Service** 把一组 **Connector** 绑定到一个 **Engine**；**Engine** 是某个 Service 的请求处理流水线；**Host** 是网络域名到 Tomcat 的关联；**Context** 就是一个 Web 应用。

**① 核心结论**

Servlet 是运行于 Web 服务器内部的 Java 组件，其生命周期（init → service → destroy）由 **Servlet 容器**管理。Tomcat 就是最主流的 Servlet 容器实现。

**② 机制说明**

**层级关系**：Servlet（业务逻辑单元）↔ Servlet 容器（管理 Servlet 生命周期并提供 HTTP 服务的运行环境）。

- 你写的 `HttpServlet` 子类**自身不会启动、不会监听端口、不会解析 HTTP**
- 这些工作全部由**容器**完成：监听端口 → 解析 HTTP 报文 → 匹配 URL 到某个 Servlet → 调用其 `service()` → 把响应写回

**Tomcat 的架构层级**（官方 overview 的组成，对应上文的引文）：

```
Server（整个容器）
└── Service（Connector ↔ Engine 的绑定）
    ├── Connector（负责网络协议：监听端口、解析 HTTP、把字节转为请求对象）
    └── Engine（请求处理流水线）
        └── Host（虚拟主机 / 域名）
            └── Context（一个 Web 应用）
                └── Wrapper（一个 Servlet）
```

**为什么 Gateway 不能跑在 Servlet 容器里**（回到 Lesson 01）：

Servlet 容器的执行模型是 **thread-per-request**：容器从线程池取一个线程，在该线程上完整执行 Servlet 的 `service()`。这套模型**预设了业务代码可以阻塞当前线程**（Lesson 0.3 展开）。而 Gateway 基于 Reactor Netty 的事件循环模型，二者不兼容。

**③ 官方要点**

1. Servlet 的运行环境是 **Web 服务器**，而非普通 JVM 进程 —— 它依赖容器提供的请求/响应对象
2. Servlet 的生命周期由**容器**驱动（构造 → `init` → `service` × N → `destroy`），不是由业务代码控制
3. Tomcat 的架构分层：`Server → Service → Connector/Engine → Host → Context → Wrapper`
4. `Context` 就是"一个 Web 应用" —— 这是 Tomcat 世界里最简单的对应关系

**④ 术语对照**

| 英文 | 中文 |
|---|---|
| Servlet | Servlet（运行于 Web 服务器内的 Java 组件） |
| Servlet Container | Servlet 容器（如 Tomcat / Jetty） |
| life-cycle methods | 生命周期方法（`init` / `service` / `destroy`） |
| Connector | 连接器（协议层：监听端口、解析 HTTP） |
| Engine | 引擎（请求处理流水线） |
| Host | 虚拟主机 |
| Context | Web 应用 |
| Wrapper | 单个 Servlet 的包装器 |

**⑤ 结论**

> **Servlet 由容器托管其生命周期；Tomcat 的 `Connector → Engine → Host → Context → Wrapper` 分层，即"容器"的物理形态。**

---

## 0.3 Blocking IO vs Non-Blocking IO — 同步阻塞 IO 与非阻塞 IO

**英文原文** — Spring WebFlux 官方文档 `webflux/new-framework.adoc`

> The term, "reactive," refers to programming models that are built around **reacting to change** -- network components reacting to I/O events, UI controllers reacting to mouse events, and others. In that sense, **non-blocking is reactive**, because, **instead of being blocked, we are now in the mode of reacting to notifications as operations complete or data becomes available.**
>
> There is also another important mechanism ... **non-blocking back pressure**. In synchronous, imperative code, **blocking calls serve as a natural form of back pressure that forces the caller to wait.** In non-blocking code, it becomes important to control the rate of events so that a fast producer does not overwhelm its destination.

**英文原文 2** — 同文档《Invoking a Blocking API》

> What if you do need to use a blocking library? Both Reactor and RxJava provide the `publishOn` operator to continue processing on a different thread. That means there is an easy escape hatch. Keep in mind, however, that **blocking APIs are not a good fit for this concurrency model.**

**中文翻译**

> "响应式（reactive）"指的是围绕**对变化做出反应**而构建的编程模型——网络组件对 IO 事件做出反应，UI 控制器对鼠标事件做出反应，等等。在这个意义上，**非阻塞就是响应式**：因为**我们不再被阻塞，而是转为"当操作完成或数据可用时，对通知做出反应"的模式。**
>
> 还有一个重要机制：**非阻塞背压（back pressure）**。在同步的命令式代码中，**阻塞调用本身就是一种天然的背压——它强制调用方等待**。在非阻塞代码中，则必须显式控制事件速率，以免快速的生产者压垮其目的地。

> 如果确实需要使用阻塞库呢？Reactor 和 RxJava 都提供 `publishOn` 操作符，将后续处理切换到另一个线程。这意味着存在一条现成的"逃生通道"。但请记住：**阻塞式 API 与这套并发模型并不契合。**

**① 核心结论**

阻塞 = 调用发起后，当前线程**停下来等待**结果返回；非阻塞 = 调用发起后立即返回，结果**就绪时通过回调/事件通知**。非阻塞带来更高的单线程吞吐，代价是失去"天然的背压"。

**② 机制说明**

**用一次网络读取说明差异**：

```
阻塞 IO：
  线程 T  →  发起 read()  →  [等待数据到达...]  →  拿到数据  →  继续执行
                              ↑ T 在此处无法做任何事

非阻塞 IO：
  线程 T  →  发起 read()  →  立即返回（暂无数据）  →  T 去处理别的连接
                              ↓ 数据到达时，由操作系统/框架通知
  事件循环  →  再次调度 T（或回调函数）  →  读取到数据  →  继续执行
```

**关键区别不在"快慢"，而在"等待期间线程能否做别的事"**：

| 维度 | 阻塞 IO | 非阻塞 IO |
|---|---|---|
| 等待期间线程 | 被占用，不能做其他事 | 释放，可处理其他连接 |
| 结果获取方式 | 调用直接返回结果 | 通过回调 / 事件通知 |
| 背压（back pressure） | **天然存在**（等待即自动限速） | **需要显式实现**（否则生产者会压垮消费者） |
| 编程复杂度 | 低（顺序执行） | 高（回调组合、状态管理） |

**术语：背压（back pressure）** —— 消费者控制生产者速度的机制。官方原文的表述很精确：同步代码中"阻塞调用本身就是天然的背压"（生产者必须等消费者处理完），而非阻塞代码中没有任何东西自动限速，因此**必须显式控制事件速率**。

**③ 官方要点**

1. **非阻塞 = 响应式**：不是"更快"，而是"不站着等，改为响应通知"
2. **阻塞是天然的背压**：同步代码靠"等待"自动限速；非阻塞代码必须显式实现背压
3. **逃生通道**：Reactor / RxJava 提供 `publishOn`，可把阻塞操作切换到独立线程执行
4. **但不推荐**：官方明确"blocking APIs are not a good fit for this concurrency model" —— `publishOn` 是权宜之计，不是正解

**④ 术语对照**

| 英文 | 中文 |
|---|---|
| blocking / non-blocking | 阻塞 / 非阻塞 |
| react to notifications | 对通知做出反应 |
| back pressure | 背压 |
| fast producer / destination | 快速生产者 / 目的地（消费者） |
| `publishOn` operator | `publishOn` 操作符（切换执行线程） |
| escape hatch | 逃生通道 / 权宜手段 |

**⑤ 结论**

> **阻塞 = 线程等待；非阻塞 = 线程不等待、改由通知驱动。非阻塞的代价是必须显式实现背压。**

---

### 0.3.1 Reactive Streams（背压的标准化）

> 本节回答 0.6.1 定义表中出现的"Reactive Streams"是什么。

**英文原文** — OpenJDK `java.util.concurrent.Flow` 类 Javadoc（Java 9 采纳 Reactive Streams 为标准 API）

> Interrelated interfaces and static methods for establishing **flow-controlled components** in which `Publisher`s produce items consumed by one or more `Subscriber`s, each managed by a `Subscription`.
>
> These interfaces correspond to the **reactive-streams** specification. They apply in both concurrent and distributed asynchronous settings: All (**seven**) methods are defined in `void` "one-way" message style. Communication relies on a simple form of **flow control** (method `Subscription#request`) that can be used to avoid resource management problems that may otherwise occur in "**push**" based systems.

**英文原文 2** — Spring WebFlux 官方文档《Define "Reactive"》

> Reactive Streams is a small spec (also adopted in Java 9) that defines the interaction between asynchronous components with back pressure. ... The main purpose of Reactive Streams is to let **the subscriber control how quickly or how slowly the publisher produces data**.
>
> NOTE: *Common question: what if a publisher cannot slow down?* The purpose of Reactive Streams is only to **establish the mechanism and a boundary**. If a publisher cannot slow down, it has to decide whether to **buffer, drop, or fail**.

**中文翻译**

> （OpenJDK）相互关联的接口与静态方法，用于建立**流控组件（flow-controlled components）**：`Publisher` 生产数据项，由一个或多个 `Subscriber` 消费，每个订阅由一个 `Subscription` 管理。
>
> 这些接口对应 **reactive-streams** 规范。它们同时适用于并发与分布式异步场景：全部（**七个**）方法都定义为 `void` 的"单向"消息风格。通信依赖一种简单的**流控**形式（`Subscription#request` 方法），用以避免 "**push**" 型系统中可能出现的资源管理问题。

> （WebFlux 文档）Reactive Streams 是一个很小的规范（Java 9 也采纳了它），定义异步组件之间**带背压的交互方式**。……Reactive Streams 的核心目的是让**订阅者控制发布者生产数据的速度**。
>
> 注意：*常见疑问——如果发布者无法减速呢？* Reactive Streams 的目的只是**建立机制与边界**。如果发布者无法减速，它必须自行决定是**缓冲、丢弃还是失败**。

**① 定义**

Reactive Streams 是一个**规范（specification）**，定义了"异步流"的标准接口：发布者生产数据、订阅者消费数据，且**订阅者能控制发布者的速率**。这个控制机制即**背压（back pressure）**。

**② 它由什么组成（4 个接口 / 7 个方法）**

| 接口 | 角色 | 方法 |
|---|---|---|
| `Publisher<T>` | 发布者（数据源） | `subscribe(Subscriber)` |
| `Subscriber<T>` | 订阅者（消费者） | `onSubscribe(Subscription)`、`onNext(T)`、`onError(Throwable)`、`onComplete()` |
| `Subscription` | 连接二者的"遥控器" | **`request(long n)`**、`cancel()` |
| `Processor<T,R>` | 既是订阅者又是发布者（中间处理节点） | 继承上述两个接口 |

**核心是 `Subscription.request(n)`**：订阅者主动声明"我还能处理 n 个"，而非被动接收。这是背压的落地形式。

**③ 它解决什么**

0.3 已说明：非阻塞世界**没有天然的限速**（阻塞调用本身就是天然背压，非阻塞没有）。若不限速，快速生产者会压垮慢消费者（内存耗尽或崩溃）。

Reactive Streams 的作用就是把这个"限速通道"**标准化**——官方措辞：避免 "push 型系统中可能出现的资源管理问题"。

**④ 官方边界（面试加分点）**

Reactive Streams **只建立机制与边界，不保证生产者一定能减速**。无法减速时，生产者须自行选择：缓冲（buffer）/ 丢弃（drop）/ 失败（fail）。

> 这句话的价值：面试官若追问"背压是不是能解决一切流量问题"，可直接回答"规范只定义机制，不解决生产者本身的产能问题"。

**⑤ 术语对照**

| 英文 | 中文 |
|---|---|
| specification（spec） | 规范 |
| flow-controlled components | 流控组件 |
| Publisher / Subscriber / Subscription / Processor | 发布者 / 订阅者 / 订阅（遥控器）/ 处理器 |
| `request(long n)` | 请求 n 个（背压的落地方法） |
| "push" based systems | 推送型系统 |
| buffer / drop / fail | 缓冲 / 丢弃 / 失败 |

**⑥ 面试答法（30 秒）**

> "Reactive Streams 是一个规范，定义了异步流的标准接口——Publisher / Subscriber / Subscription / Processor 四个接口、七个方法。核心机制是背压：订阅者通过 `Subscription.request(n)` 告诉发布者自己还能处理多少，避免生产者把消费者压垮。Java 9 把它采纳为标准 API，就是 `java.util.concurrent.Flow`。WebFlux 就建在它上面，`Mono` 和 `Flux` 都是 `Publisher` 的实现。"

**⑦ 你不需要知道的**

| 不需要知道 | 说明 |
|---|---|
| 规范的精确规则条目 | reactive-streams.org 的 TCK 规则（如 `request` 的溢出语义） |
| 自己实现 `Publisher` / `Subscriber` | Gateway 学习者不涉及 |
| `SubmissionPublisher` | JDK 自带的示例实现 |

**⑧ 结论**

> **Reactive Streams = 异步流的接口规范；`request(n)` 是背压的标准入口；Java 9 采纳为 `java.util.concurrent.Flow`。**

---

## 0.4 Event Loop — 事件循环

### 0.4.0 本节要掌握的 4 条（先看这个，其余全是推导）

| # | 要掌握的 | 一句话 | 推导在 |
|---|---|---|---|
| **1** | **两边的 IO 都是非阻塞的** | Tomcat 也是 NIO；"阻塞"指线程被整个请求独占 | 0.4.1 |
| **2** | **等待由内核负责** | `epoll` 持有等待状态，不需要线程 → 少量线程撑大量连接 | 0.4.2 |
| **3** | **Channel 绑定 EventLoop** | 同一 Channel 的事件串行执行 → 不用加锁 | 0.4.2 / 0.4.3 |
| **4** | **Gateway 是双面的** | 对客户端是 Server（入站 Channel），对下游是 Client（出站 Channel） | 0.4.3 / 0.4.4 |

**四条的关系**：

```
① 两边都是非阻塞 IO（Tomcat 也是 NIO）
        ↓
② 那"阻塞"指什么？→ 线程被请求独占；等待改由内核承担
        ↓
③ 线程怎么组织？→ EventLoop；一个 Channel 绑一个 EventLoop，串行执行（无需加锁）
        ↓
④ Gateway 有两条连接 → 两个 Channel（入站 / 出站），可能落在不同 EventLoop 上
```

> ⚠️ **阅读边界**：**0.4.1 ~ 0.4.4 是上述 4 条的推导过程与源码证据**。读懂 4 条结论即可。
> 源码行号、`addListener` 细节、Reactor Netty 三层划分、`ss` 实验记录 —— 属**查阅材料，不必记忆**。

---

**英文原文** — Netty 官方 README

> Netty is an **asynchronous event-driven network application framework** for rapid development of maintainable high performance protocol servers & clients.

**英文原文 2** — Spring WebFlux 官方文档《Threading Model》

> On a "vanilla" Spring WebFlux server (for example, no data access or other optional dependencies), you can expect **one thread for the server and several others for request processing (typically as many as the number of CPU cores)**. Servlet containers, however, may start with more threads (for example, 10 on Tomcat), in support of both servlet (blocking) I/O and servlet 3.1 (non-blocking) I/O usage.
>
> The reactive `WebClient` operates in **event loop style**. So you can see a small, fixed number of processing threads related to that (for example, `reactor-http-nio-` with the Reactor Netty connector).

**中文翻译**

> Netty 是一个**异步的、事件驱动的网络应用框架**，用于快速开发可维护的高性能协议服务端与客户端。

> 在一个"纯净"的 Spring WebFlux 服务器上（例如没有数据访问或其他可选依赖），可以预期：**一个用于服务器本身的线程，以及若干用于请求处理的线程（通常与 CPU 核数相当）**。而 Servlet 容器可能启动更多线程（例如 Tomcat 的 10 个），以同时支持 Servlet 的阻塞 IO 与 Servlet 3.1 的非阻塞 IO。
>
> 响应式的 `WebClient` 以**事件循环风格**运行，因此只能看到少量固定数量的处理线程（例如使用 Reactor Netty 连接器时为 `reactor-http-nio-` 前缀的线程）。

**① 核心结论**

事件循环（EventLoop）是非阻塞 IO 的**调度实现**：由少量固定线程（约等于 CPU 核数）组成，每个线程循环地从就绪事件队列中取事件并执行对应处理逻辑。它是"少量线程支撑大量连接"得以成立的原因。

**② 机制说明**

**核心结构**：一个线程 + 一个事件队列 + 一个循环。

```
EventLoop 线程（循环执行）：
  while (true) {
      events = selector.select();      // 询问 OS：哪些连接上发生了就绪事件
      for (event : events) {
          dispatch(event);             // 执行该事件对应的处理逻辑（非阻塞）
      }
  }
```

**关键性质**：

| 性质 | 说明 |
|---|---|
| 线程数固定且少 | 约等于 CPU 核数（官方原文："typically as many as the number of CPU cores"） |
| 一个连接绑定一个 EventLoop | 同一连接上的事件始终由同一线程处理，故**无需为该连接加锁** |
| 一个 EventLoop 承载多个连接 | 少量线程即可支撑大量并发连接 |
| **事件处理必须快速返回** | 若处理逻辑阻塞，该 EventLoop 上的**所有连接**都会被拖住 |

**为什么"阻塞"在事件循环下后果被放大**（对比 Lesson 01 的两种线程模型）：

| 模型 | 线程数 | 阻塞一个线程的后果 |
|---|---|---|
| thread-per-request（Tomcat） | 数百（线程池可弹性扩展） | 仅当前请求受影响，线程池可吸收 |
| EventLoop（Reactor Netty） | 约 CPU 核数 | **该线程承载的全部连接一起停滞**，且无可扩展的冗余线程 |

这正是官方在 WebFlux 文档中反复强调"不要阻塞"的原因——**不是风格偏好，是模型约束**。

**③ 官方要点**

1. Netty 的官方自我定位是 **asynchronous event-driven**（异步、事件驱动），这是 Gateway 的运行时基础
2. WebFlux 服务器的线程数：**1 个 server 线程 + 约 CPU 核数个处理线程**
3. WebFlux 的客户端 `WebClient` 同样以 **event loop style** 运行，线程名前缀为 `reactor-http-nio-`
4. Servlet 容器默认线程更多（Tomcat 官方文档提到 10 个起步），因为它需要同时容纳阻塞式与非阻塞式用法

**④ 术语对照**

| 英文 | 中文 |
|---|---|
| event-driven | 事件驱动 |
| event loop | 事件循环 |
| event loop workers | 事件循环工作线程 |
| selector / ready events | 选择器 / 就绪事件 |
| thread-per-request | 一请求一线程 |
| `reactor-http-nio-` | Reactor Netty 的 IO 线程名前缀 |

**⑤ 结论**

> **事件循环 = 少量固定线程 + 循环取就绪事件执行。线程越少，单个阻塞的放大效应越强。**

---

### 0.4.1 「阻塞」到底指什么（澄清一个关键误解）

**常见误解**

> "Tomcat 是阻塞的、Netty 是非阻塞的" → 所以 Tomcat 用阻塞 IO、Netty 用非阻塞 IO。

**这个理解是错的。** Tomcat 官方文档对自家连接器的描述（`config/http.xml`）：

> `org.apache.coyote.http11.Http11NioProtocol` - **non blocking Java NIO connector**

**Tomcat 的 IO 层同样是非阻塞的**（NIO / epoll）。因此"阻塞"不发生在 socket 层。

**那"阻塞"指什么？** —— 指**处理请求的线程在等待下游时是否被占用**。Tomcat 官方原文（`config/http.xml`）：

> **Each incoming, non-asynchronous request requires a thread for the duration of that request.**
>
> （每个非异步请求，在该请求的整个持续期间都需要占用一个线程。）

**两条执行路径对比**

Tomcat（thread-per-request）：

```
Poller 线程（NIO selector）检测到连接可读
    ↓ 把请求交给 worker 线程池
Worker 线程 T1：执行 Servlet / Controller 全部代码
    ├─ 解析参数
    ├─ 调 DB        ← T1 在此真正停下等待
    ├─ 组装响应
    └─ 写回
    ↓ 请求结束，T1 归还线程池
```

T1 在"等 DB"期间**无法服务其他请求** → `maxThreads`（默认 200）即并发请求上限。

Netty / WebFlux（EventLoop）：

```
EventLoop 线程 E1（同样是 NIO selector）检测到连接可读
    ├─ 读请求 → 执行 filter / controller 代码
    ├─ 调 DB        ← 不等待！立即返回"未来结果"（Mono/Flux）并注册回调
    └─ E1 立即返回，处理其他连接        ← 关键差异
          ↓（DB 结果就绪时被调度回 E1）
    ├─ 回调继续：取 DB 结果，组装响应
    └─ 写回
```

E1 在"等 DB"期间**去服务其他连接** → 少量 EventLoop 线程可支撑数千并发连接。

> **结论：所谓"非阻塞"，是指处理请求的线程在等待下游（DB / 远程调用）时不被占用。**

**机制：`addListener` 而非 `await`**

Netty 官方 `ChannelFuture` 类 Javadoc：

> All I/O operations in Netty are asynchronous. It means any I/O calls will **return immediately** with no guarantee that the requested I/O operation has been completed at the end of the call. Instead, you will be returned with a `ChannelFuture` instance... It also allows you to add `ChannelFutureListener`s so you can get **notified when the I/O operation is completed**.
>
> **Prefer `addListener(...)` to `await()`**

| 方法 | 语义 | 对应模型 |
|---|---|---|
| `await()` | **阻塞等待** | Tomcat worker 线程 |
| `addListener()` | **注册回调** | EventLoop 线程 |

Reactor Netty 的工作，就是把 `ChannelFuture` + `ChannelFutureListener` 这套回调机制**包装成 `Mono` / `Flux`**，使上层可用声明式算子书写。

**对比总结**

| 维度 | Tomcat（thread-per-request） | Netty / WebFlux（EventLoop） |
|---|---|---|
| IO 层 | **非阻塞**（NIO / epoll） | **非阻塞**（NIO / epoll） |
| 处理请求的线程 | **一个线程占满整个请求周期** | 请求被切成若干段，无线程被独占 |
| 等待下游时 | 线程**被占用**（阻塞） | 线程**被释放**（回调驱动） |
| 并发上限 | ≈ `maxThreads`（默认 200） | 远高于线程数（可至数千连接） |
| 官方依据 | "requires a thread for the duration of that request" | "return immediately… addListener" |

**补充：Servlet 本身也支持异步**

Servlet 3.0 引入 `AsyncContext`、3.1 引入非阻塞 IO，因此在 Tomcat 上同样可以写非阻塞代码。但官方 WebFlux 文档指出这条路很别扭：

> Servlet non-blocking I/O **leads away from the rest of the Servlet API**, where contracts are synchronous (`Filter`, `Servlet`) or blocking (`getParameter`, `getPart`). **This was the motivation for a new common API**...

即：Servlet 的其余 API 仍是阻塞契约，非阻塞只能"绕开整个 API"。这正是 Spring 另起炉灶构建 WebFlux 的动因。

**关联**

- 本节解释了 0.3 中"非阻塞"在服务端的具体落点
- 本节解释了 0.4 中"单个阻塞的放大效应"为什么成立：EventLoop 线程被占用时，其上所有连接一起停滞
- Lesson 35 会看到 xhs-project 里"阻塞即灾难"的真实案例

---

### 0.4.2 等待时到底谁在干活（常见追问）

**常见追问**

> `addListener()` 是"任务完成后回调"，那说明**有另一个线程在干活**吧？既然 EventLoop 没在处理，肯定有别的线程池。

**答案：部分正确——需区分两种"等待"。**

#### 一、等待网络 IO：**不需要任何线程**

`selector.select()` 确实是阻塞调用，但它阻塞的是**内核中的 `epoll_wait`**。内核持有"哪个 socket 在等待什么数据"的状态，**不需要用户态线程参与**。

源码证据（Netty 本地仓库 4.2.5）：

`transport/src/main/java/io/netty/channel/nio/NioIoHandler.java:410` 主循环：

```java
public int run(IoHandlerContext context) {
    ...
    case SelectStrategy.SELECT:
        select(context, wakenUp.getAndSet(false));   // ← 线程在此休眠
    ...
    handled = processSelectedKeys();                 // :475 / :479 ← 醒来后由同一线程处理就绪事件
```

`NioIoHandler.java:621` 真正的阻塞点：

```java
private void select(IoHandlerContext runner, boolean oldWakenUp) throws IOException {
    Selector selector = this.selector;
    ...
    selector.select(timeout);   // 阻塞于 epoll_wait —— 由内核负责，非另一个线程
```

流程：

| 步骤 | 谁在执行 |
|---|---|
| 1. 线程调用 `selector.select()` | 线程**进入内核睡眠**（无用户态线程运行） |
| 2. 网络数据到达 | **内核**标记 socket 就绪并**唤醒**线程 |
| 3. 线程醒来执行 `processSelectedKeys()` | **同一个线程**读数据并触发回调 |

> **"等待"由内核承担，不是由线程承担。** 这正是"少量线程支撑大量连接"的物理基础。

#### 二、回调执行：仍在 EventLoop 线程

`spring-web` 中 Reactor Netty 与 WebFlux 的桥接类：

```java
public class ReactorHttpHandlerAdapter
        implements BiFunction<HttpServerRequest, HttpServerResponse, Mono<Void>> {   // :40
    @Override
    public Mono<Void> apply(HttpServerRequest reactorRequest, HttpServerResponse reactorResponse) {
        ...
        return this.httpHandler.handle(request, response)     // :65 ← WebFlux 的 HttpHandler 入口
    }
}
```

Reactor Netty **在 EventLoop 线程上**调用该 `apply()`，其内部再调用 WebFlux 的 `httpHandler.handle()`。**全程未切换线程池。**

#### 三、Gateway 的实际情形

官方 WebFlux 文档《Threading Model》：

> The reactive `WebClient` operates in event loop style... However, **if Reactor Netty is used for both client and server, the two share event loop resources by default.**

Gateway 正是"Reactor Netty server + Reactor Netty client"的组合 → **共享同一组 EventLoop**。一次转发的线程轨迹：

```
E1（EventLoop）: 收客户端请求 → 跑 filter 链 → 向 myservice 发起请求 → 立即返回（不等待）
                                ↓ 内核等待下游响应，无线程参与
E2（同组 EventLoop）: 下游响应到达 → 回调触发 → 继续跑 filter 链 → 写回客户端
```

> ⚠️ E1 → E2 **可能不是同一个线程**（响应可能被同组内的其他 EventLoop 取到），但两者**都属 EventLoop 线程，不存在额外的业务线程池**。

#### 四、确实存在其他线程池，但只在特定场景

| 场景 | 线程池 | 依据 |
|---|---|---|
| **阻塞式操作**（JDBC / 文件 IO） | `Schedulers.boundedElastic()` | reactor-core 的 `BoundedElasticScheduler`；官方称 `publishOn` 为 "escape hatch" |
| DNS 解析 | Netty 自身的 resolver 线程 | — |
| 独立响应式客户端（Reactive Redis / Mongo） | 各自独立的 EventLoop 组 | — |
| **Gateway 转发（Reactor Netty client）** | **无额外池，与服务端共享** | 官方："share event loop resources by default" |

**结论**：并非"一定有另一个线程池在干活"，而是"**网络等待由内核承担、业务回调由 EventLoop 承担**"。只有当操作**内核无法代劳**（如 JDBC 阻塞调用）时，才需要 `boundedElastic` 这一逃生舱——而官方明确指出它在响应式栈中"不契合"。

---

### 0.4.3 Channel 是谁的东西（澄清"网关怎么和 Tomcat 建 Channel"）

**常见疑问**

> 下游服务通常是 Tomcat。网关怎么可能"和下游建立 Channel"？Tomcat 又没有 Netty 的 Channel。

**答案**：`Channel` 是 **Netty 侧的本地对象**，不需要对端配合。

#### 一、官方定义

Netty `io.netty.channel.Channel`：

> "A **nexus to a network socket** or a component which is capable of I/O operations such as read, write, connect, and bind."

即：`Channel` 是 Netty 对"**一条连接在本进程内的表示**"。它存在于**使用 Netty 的那一侧**。

#### 二、实测证据（`ss -tnp`）

在 xhs-demo 上发起一个 8 秒慢请求（`/provider/slow?ms=8000`），期间抓取 TCP 连接：

```
连接①  curl(pid 102696):49578   ↔  网关(pid 102380):8080      [网关 fd=57]
连接②  网关(pid 102380):50004   ↔  provider(pid 102379):8081   [网关 fd=58]
```

**关键观察：网关进程（pid 102380）同时持有 fd=57 与 fd=58 两个连接端点** → 一次转发确实涉及两条独立 TCP 连接，网关各持一端。

#### 三、完整视图

```
    客户端                  网关（Netty）                    下游（Tomcat）
      │                        │                                │
      │◄──── 连接① TCP ───────►│◄─────── 连接② TCP ────────────►│
      │                        │                                │
  curl 的 socket        网关持有两个 Channel：             Tomcat 的 socket
                        · 入站 Channel（fd=57，网关为 Server 端）
                        · 出站 Channel（fd=58，网关为 Client 端）
```

| 连接 | 网关的角色 | 网关使用的组件 |
|---|---|---|
| ① 客户端 ↔ 网关 | **Server** | Reactor Netty Server（监听 8080） |
| ② 网关 ↔ 下游 | **Client** | Reactor Netty HttpClient（主动 connect 至 8081） |

**连接② 即"出站 Channel"**：网关在此处是客户端，主动向 Tomcat 发起 TCP 连接。

#### 四、为什么"网关连 Tomcat"不矛盾

连接②是标准 **HTTP over TCP**：

- 网关侧：Reactor Netty HttpClient 发起请求
- Tomcat 侧：HTTP/1.1 Connector 接收，走 `Connector → Engine → Host → Context` 处理链（见 0.2）

**双方均无需知道对端使用什么库。** 下游若替换为 Nginx、Go 服务或另一个 Netty 服务，网关侧代码无需改动。

> 因此"网关 ↔ 下游"与"下游是 Tomcat"并不冲突：前者描述**谁主动发起 TCP 连接**，后者描述**对端使用什么 HTTP 服务器实现**。

#### 五、这条结论对后续课程的意义

- 0.4.2 中"两个 Channel 各绑一个 EventLoop"由此成立：两个 `Channel` 都是**网关侧**的对象
- Lesson 16 的 `lb://`、Lesson 30 的连接池，都作用于**出站 Channel**（网关作为客户端的那一侧）
- xhs-project 修复的 T-048（`max-life-time` < 下游 `keep-alive-timeout`）正是**出站连接池**的配置问题

#### 六、易混点：Channel ≠ Server / Client

**常见混淆**："入站 Channel 就是 HttpServer，出站 Channel 就是 HttpClient。"

**修正**：`HttpServer` / `HttpClient` 是**组件（配置对象）**，`Channel` 是**连接实例**。二者的量级完全不同：

```
HttpServer（1 个，网关启动时创建）
  └── Channel × N        ← 每个客户端连接一个（入站）

HttpClient（1 个，Spring 单例 Bean）
  └── ConnectionProvider（连接池）
        └── Channel × M    ← 每个到下游的连接一个（出站）
```

| 概念 | 数量级 | 性质 |
|---|---|---|
| `HttpServer` / `HttpClient` | **各 1 个** | 可复用的配置对象/工厂 |
| `Channel` | 可达数千/数万 | 每个 TCP 连接一个实例 |

**源码依据**：`HttpClientFactory extends AbstractFactoryBean<HttpClient>` → Spring 单例 Bean → 整个网关只有一个 `HttpClient`；但它的连接池（`ConnectionProvider`）中可持有大量 `Channel`。

**准确表述**：

> Gateway 启动时创建 **1 个 `HttpServer`** 与 **1 个 `HttpClient`**。每个客户端连接对应 `HttpServer` 下的一个**入站 Channel**；每个到下游的连接对应 `HttpClient` 连接池中的一个**出站 Channel**。

---

### 0.4.4 Reactor Netty（Netty 与 WebFlux 之间的传输层）

> 本节回答 0.4.3 中提到的 "Reactor Netty HttpClient" 是什么。

**英文原文** — Reactor Netty 官方 README

> Reactor Netty offers **non-blocking and backpressure-ready TCP/HTTP/UDP/QUIC clients & servers based on Netty framework**.

**英文原文 2** — Reactor Netty 官方参考文档《HTTP Client》

> Reactor Netty provides the easy-to-use and easy-to-configure `HttpClient`. It **hides most of the Netty functionality that is required to create an HTTP client** and **adds Reactive Streams backpressure**.

**英文原文 3** — Reactor Netty 官方参考文档（EventLoop 共享，比 WebFlux 文档的表述更强）

> By default Reactor Netty uses an "Event Loop Group", where the number of the worker threads equals the number of processors available to the runtime on initialization (but with a minimum value of 4). **This "Event Loop Group" is shared between all servers and clients in one JVM.**

**中文翻译**

> Reactor Netty 基于 Netty 框架，提供**非阻塞、带背压**的 TCP / HTTP / UDP / QUIC 客户端与服务端。

> Reactor Netty 提供易用、易配置的 `HttpClient`。它**隐藏了创建 HTTP 客户端所需的大部分 Netty 功能**，并**加入 Reactive Streams 背压**。

> 默认情况下 Reactor Netty 使用一个 "Event Loop Group"，其工作线程数等于运行时可用处理器数（最小值为 4）。**该 Event Loop Group 在同一个 JVM 内被所有服务端与客户端共享。**

**① 定义**

Reactor Netty 是 **Netty 的响应式封装层**：把 Netty 的回调式 API 包装为 Reactor 的 `Mono` / `Flux` API，并加上背压。提供四个组件：`HttpServer` / `HttpClient` / `TcpServer` / `TcpClient`。

**② 为什么需要它**

Netty 的 API 是**回调式**的（见 0.4.1 的 `ChannelFuture.addListener`）。简单场景尚可，但组合复杂逻辑（超时、重试、连接池、背压）非常繁琐。Reactor Netty 将这些封装为声明式流。

**③ 三层职责划分**

| 层 | 组件（jar） | 职责 | 本课程位置 |
|---|---|---|---|
| **框架层** | `spring-webflux` | `HandlerMapping` / `DispatcherHandler` / `WebFilter` / `ServerWebExchange` | 0.6 |
| **传输层** | **`reactor-netty-http`** | **`HttpServer` / `HttpClient`**，把 Netty 包成 Mono/Flux | **本节** |
| **网络层** | `netty-*` 4.1.109 | EventLoop / Channel / ByteBuf | 0.4 |

依赖关系（实测依赖树）：

```
spring-boot-starter-webflux
├── spring-webflux
└── spring-boot-starter-reactor-netty
    └── reactor-netty-http:1.1.18              ← 传输层
        ├── reactor-netty-core
        └── netty-codec-http:4.1.109           ← 网络层
            (+ netty-transport / netty-buffer / netty-common …)
```

**④ Gateway 中的实际使用（源码证据）**

| 位置 | 代码 |
|---|---|
| `NettyRoutingFilter.java:32` | `import reactor.netty.http.client.HttpClient;` |
| `NettyRoutingFilter.java:81` | `private final HttpClient httpClient;` |
| `NettyRoutingFilter.java:142` | `.request(method).uri(url).send(...)` ← **真正发往下游的调用** |
| `HttpClientFactory.java:47` | `class HttpClientFactory extends AbstractFactoryBean<HttpClient>` |
| `HttpClientFactory.java:83` | `HttpClient.create(connectionProvider)` ← 创建带连接池的客户端 |

> 0.4.3 中那条"出站 Channel"即由此产生：`NettyRoutingFilter` 用 `HttpClient` 主动 connect 到下游。

**⑤ 一句话定位**

> **Netty 提供"网络能力"；Reactor Netty 提供"以 Mono/Flux 使用网络能力的方式"；WebFlux 提供"Web 框架"。**

**⑥ 面试答法（30 秒）**

> "Reactor Netty 是 Netty 的响应式封装，官方定位是 'non-blocking and backpressure-ready clients & servers based on Netty'，它把 Netty 的回调式 API 包装成 Mono/Flux 并加上背压。WebFlux 的 server 和 WebClient 底层都用它。在我们网关里，转发下游用的就是它的 `HttpClient`——`NettyRoutingFilter` 持有 `HttpClient`，由 `HttpClientFactory` 创建并配置连接池。而且它默认的 Event Loop Group 在同一个 JVM 内被所有 server/client 共享，这也是网关入站与出站能共用同一组 EventLoop 的原因。"

**⑦ 基本使用（最小例子，已做成可运行 demo）**

> 代码：`reactor-lab/src/main/java/com/myxhs/demo/reactor/l00/`（Ex01 = Http 版，Ex02 = TCP 版）
> 跑：`mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l00.Ex01_MinimalHttpServerClient`

**HttpServer（服务端）**

```java
DisposableServer server = HttpServer.create()
        .port(0)                                       // 0 = 让系统分配随机端口
        .handle((request, response) -> {               // ★ 拿到的是【HTTP 对象】
            System.out.println(request.method() + " " + request.uri());
            return response.header("Content-Type", "text/plain")
                    .sendString(Mono.just("hello, uri=" + request.uri()));
        })
        .bindNow();                                    // 绑定端口（只阻塞这一次，启动时做）
```

**HttpClient（客户端）**

```java
String body = HttpClient.create()                      // 创建（带默认连接池）
        .get()                                         // ★ 声明式链
        .uri("http://localhost:" + server.port() + "/ping")
        .responseContent()                             // 响应体：ByteBufFlux（流）
        .aggregate()                                   // 聚合成一个 ByteBuf
        .asString()                                    // Mono<String>
        .block();                                      // ⚠️ 只为实验；网关里绝不这样写
```

**实测输出**（`Ex01`）：

```
[HttpServer] 已启动，端口 = 32991
[HttpServer] 收到请求: GET /ping?from=demo
[HttpClient] 收到响应: hello from HttpServer, uri=/ping?from=demo
```

**⑧ 附录：TcpServer / TcpClient 是什么（不学，但要知层次）**

| | **TcpServer / TcpClient**（`reactor-netty-core`） | **HttpServer / HttpClient**（`reactor-netty-http`） |
|---|---|---|
| `handle` 给什么 | **字节流**（`NettyInbound` / `NettyOutbound`） | **HTTP 对象**（`HttpServerRequest` / `Response`） |
| 懂 HTTP 吗 | ❌ 完全不懂（没有 URL / Header / 状态码） | ✅ |
| 用途 | **非 HTTP 协议**（自定义二进制协议、IM 长连接） | HTTP 服务端 / 客户端 |
| Gateway 用吗 | ❌ **零引用** | ✅ 入站 `HttpServer` / 出站 `HttpClient` |

```java
// 对比：TCP 版的 handle 拿到的是【入站/出站字节流】
TcpServer.create().port(0)
        .handle((inbound, outbound) -> outbound.sendString(Mono.just("hello over TCP\n")))
        .bindNow();
```

**层次关系（源码实证）**：`HttpServer extends ServerTransport`，且存在 `HttpServer.from(TcpServer)` / `HttpClient.from(TcpClient)`
→ **HTTP 层 = TCP 配置 + HTTP 编解码 + 请求/响应对象**。

**⑨ 你不需要知道的**

| 不需要知道 | 何时再看 |
|---|---|
| `ConnectionProvider` / `LoopResources` 的配置细节 | **Lesson 30**（连接池生命周期） |
| `TcpServer` / `TcpClient` / UDP / QUIC | Gateway 不使用（**层次关系见 ⑧**） |
| `HttpClient` 的操作符 API 全集 | 用时查（**最小用法见 ⑦**） |

**⑩ 结论**

> **Reactor Netty = Netty 的响应式外壳；Gateway 的出站调用（转发下游）就是它的 `HttpClient`。**

**⑪ 交叉印证**

本节引文 3 与 0.4.2 所引 WebFlux 文档互相印证：**EventLoop 在 JVM 内全局共享** —— 这解释了为什么网关的入站 Channel 与出站 Channel 会落在同一组 EventLoop 上，从而不需要额外的业务线程池。

---

### 0.4.5 网关进程的线程清单（实测确定）

> ⚠️ **这张表是"实测快照"，不是"Schedulers 全集"**（2026-09-26 澄清）：
> 它只包含**那一次运行里真实存在**的线程。Reactor 的 `Schedulers` 是**懒创建**的 ——
> `Schedulers.single()` 没人调用，所以 `single-N` 不在表里（但它是真实存在的调度器）。
> 另：表里的 `parallel-N` / `boundedElastic-N` 是**框架自己用出来的**（连接池后台清理 / 网关路由 ID 生成），
> 源码位置与完整对照见 `L11-backpressure-and-scheduling.md` **§11.3.1**。

> 本节回答：一个运行的 Gateway 进程里到底有几种线程、各自负责什么。

#### ① 核心结论（要掌握的 3 条）

1. **业务线程只有 4 种**，其中**只有 `reactor-http-epoll-*` 处理请求**；其余是 Reactor 的调度器池，普通请求碰不到
2. **一次请求的完整生命周期全程在同一个 epoll 线程上**（收请求 → filter → 转发 → 收响应 → 写回）
3. 其余 30+ 个是 **JVM 自己的线程**（GC / VM / Compiler 等），与 Gateway 无关

#### ② 线程清单（实测：共 57 个）

**业务线程（4 种）**

| 线程 | 实测数量 | 归属 | 负责什么 | 普通请求用吗 |
|---|---|---|---|---|
| **`reactor-http-epoll-N`** | **16**（= CPU 核数） | **Netty** | **全部网络 IO** | ✅ **全程用它** |
| `parallel-N` | 2（上限 16） | Reactor Core | CPU 密集型算子 | ❌ |
| `boundedElastic-N` | 3（弹性伸缩） | Reactor Core | 阻塞任务逃生舱 | ❌ |
| `boundedElastic-evictor-1` | 1 | Reactor Core | 回收空闲的 `boundedElastic` | 后台常驻 |

**JVM 内部线程（36 个，不用管）**

`GC Thread#0-12`、`G1 Conc/Refine/Main Marker/Service`、`VM Thread`、`VM Periodic Task`、`C1/C2 CompilerThread`、`Reference Handler`、`Finalizer`、`Signal Dispatcher`、`Common-Cleaner`、`Cleaner`、`Attach Listener`、`DestroyJavaVM`、`main`、`server`、`spring.cloud.inetutils`

#### ③ 每类线程负责什么

**`reactor-http-epoll-*`（网络 IO）—— 唯一处理请求的**

它独自承担 5 件事：

1. 接受客户端连接（acceptor 角色）
2. 读客户端请求数据
3. **发起**到下游的连接（client 角色）
4. 读下游响应数据
5. 写回客户端

**`parallel-*`（CPU 池）**

Reactor Core 的 `Schedulers.parallel()`。只有算子显式切换才会用到。SCG 里的例子：`ModifyResponseBodyGatewayFilterFactory.java:272,295`（改写响应体）。

**`boundedElastic-*`（阻塞逃生舱）**

Reactor Core 的 `Schedulers.boundedElastic()`。唯一用途：执行**内核无法代劳的阻塞操作**（JDBC / 文件 IO）。SCG 里的例子：`CompositeRouteDefinitionLocator.java:68`（生成路由 ID）。

**三类池的行为对比**

| 池 | 启动方式 | 回收 |
|---|---|---|
| `reactor-http-epoll-*` | 首次出站时**全组拉起**（见 ⑤） | ❌ 只增不减 |
| `parallel-*` | 按需 | — |
| `boundedElastic-*` | 按需 | ✅ **idle 回收** |

#### ④ 一次请求的线程轨迹（实测）

日志证据（Spring Boot 日志格式含 `[线程名]`）：

```
[or-http-epoll-3] DemoGlobalFilter : [gateway] --> GET /echo/T1 traceId=74e12e83...
[or-http-epoll-3] DemoGlobalFilter : [gateway] <-- GET /echo/T1 status=200 OK cost=250ms

[nio-8081-exec-1] EchoController   : [provider] /echo/T1 收到请求, traceId=74e12e83...
```

> `[or-http-epoll-3]` 是日志列宽对 `reactor-http-epoll-3` 的截断显示。

轨迹图：

```
客户端 ──> [reactor-http-epoll-3]  收请求 → 跑 filter 链（pre）
                                   转发下游（同一线程发起，不等待）
                    ↓ 内核等待响应（无线程参与，见 0.4.2）
           [nio-8081-exec-1]       provider（Tomcat，一请求一线程）
                    ↓
           [reactor-http-epoll-3]  收响应 → 跑 filter 链（post）→ 写回客户端
```

**全程 1 个线程。** 与 provider 侧 Tomcat 的 `nio-8081-exec-1`（一请求一线程）形成鲜明对照。

#### ⑤ 附：16 个线程为什么"一次全起"

实测数据：干净启动 → **1** 个；health（不转发）→ **2** 个；**首次转发 → 16 个**（此时 8080 上只有 2 个客户端连接）。

JFR（`jdk.ThreadStart` 事件）抓到**两种创建栈**：

| 栈型 | 线程数 | 触发路径 |
|---|---|---|
| ① 接受连接 | 2 | `ServerTransport$Acceptor.channelRead` → `register` → 启动 |
| ② **首次出站** | **13** | `DefaultLoopResources.cacheNativeClientLoops` → `LoopResources.colocate` → **逐 EventLoop `submit()`** |

**根因**（reactor-netty v1.1.18 源码，`ColocatedEventLoopGroup` 构造器）：

```java
ColocatedEventLoopGroup(EventLoopGroup eventLoopGroup) {
    this.eventLoopGroup = eventLoopGroup;
    for (EventExecutor ex : eventLoopGroup) {      // 遍历组内全部 EventLoop
        EventLoop eventLoop = (EventLoop) ex;
        if (eventLoop.inEventLoop()) {
            if (!localLoop.isSet()) localLoop.set(eventLoop);
        } else {
            eventLoop.submit(() -> {               // ★ 每个都提交一个任务 → startThread()
                if (!localLoop.isSet()) localLoop.set(eventLoop);
            });
        }
    }
}
```

**因果链**：

```
首次出站（网关作为 client）
  → DefaultLoopResources.cacheNativeClientLoops()
    → LoopResources.colocate(serverGroup)       创建 ColocatedEventLoopGroup
      → 构造器 for-each 全部 EventLoop
        → eventLoop.submit(...)                 ← 16 次
          → SingleThreadEventExecutor.execute()
            → startThread()                     16 个线程一次性启动
```

**目的**：把每个 EventLoop **colocate（共置）到当前线程**（`FastThreadLocal<EventLoop> localLoop`），使后续操作复用同一 EventLoop，避免跨线程切换。**线程全启只是该行为的副作用。**

> ⚠️ 这**不是**"预热"（实测出站连接数稳定在 1 条），也**不是**"每连接一个线程"。

#### ⑥ 术语对照

| 术语 | 含义 |
|---|---|
| `reactor-http-epoll-N` | Reactor Netty 为 HTTP 创建的 Netty EventLoop 线程 |
| `parallel-N` | Reactor `Schedulers.parallel()` 的线程 |
| `boundedElastic-N` | Reactor `Schedulers.boundedElastic()` 的线程 |
| evictor | 回收器（清理空闲的 `boundedElastic` 线程） |
| colocate | 共置：把 EventLoop 绑定到当前线程，避免跨线程切换 |
| JFR | JDK Flight Recorder，可记录 `ThreadStart` 等事件及其创建栈 |

#### ⑦ 结论

> **一个网关进程：业务线程 4 种，但只有 `reactor-http-epoll-*` 处理请求；一次请求全程单线程；其余是 JVM 自己的。**

#### ⑧ 证据来源

| 证据 | 获取方式 |
|---|---|
| 线程清单、请求轨迹 | 实测：`jstack` + 网关日志的 `[线程名]` |
| 16 线程触发路径 | `jcmd JFR.start` 录制 → `jfr print --events jdk.ThreadStart` 分析创建栈 |
| `ColocatedEventLoopGroup` 构造器 | reactor-netty `v1.1.18` 源码（`reactor-netty-core`） |

---

### 0.4.6 `Mono` 的底层：`ChannelFuture` + `addListener`（源码实证）

> 本节回答：响应式写法（返回 `Mono` + 算子链）在底层究竟靠什么实现。

#### ① 核心结论（要掌握的 2 条）

1. **`Mono` 的本质 = Netty 的 `ChannelFuture` + `addListener` 的声明式包装**
2. 网关"不等待"的实现方式：epoll 线程发起请求后**立刻返回**；下游响应到达时，由 **Netty 回调唤醒它继续**执行算子链

#### ② 源码证据

`reactor.netty.FutureMono` —— Netty `Future` → Reactor `Mono` 的桥：

```java
static <F extends Future<Void>> void doSubscribe(CoreSubscriber<? super Void> s, F future) {
    if (future.isDone()) {
        if (future.isSuccess()) Operators.complete(s);
        else Operators.error(s, FutureSubscription.wrapError(future.cause()));
        return;
    }

    FutureSubscription<F> fs = new FutureSubscription<>(future, s);
    // propagate subscription before adding listener to avoid any race
    s.onSubscribe(fs);

    if (fs.cancelled) return;

    // add listener to the future to propagate on complete when future is done
    future.addListener(fs);            // ★★★ 底层就是这一句
    ...
}

static final class FutureSubscription<F extends Future<Void>>
        implements GenericFutureListener<F>,   // ← Netty 的回调接口
                   Subscription,               // ← Reactor 的订阅接口
                   Supplier<Context> {
```

**关键设计**：`FutureSubscription` **同时实现两边的接口**，因此它既是 Netty 的回调监听器，又是 Reactor 的订阅对象。

**转换链路**：

```
Netty 的 future 完成
  → Netty 回调 FutureSubscription.operationComplete()
    → 转成 Reactor 的 onComplete()
      → Mono 完成
        → 算子链继续执行（如 .map(...) / .then(...)）
```

> 结论：**`Mono` 不是"新发明的异步机制"，它只是 `ChannelFuture` + `addListener` 的声明式外壳。**

#### ③ 两种模型的对照（实测）

| 维度 | Tomcat（provider 侧） | Gateway（WebFlux 侧） |
|---|---|---|
| 模型 | thread-per-request | EventLoop + 回调 |
| 线程轨迹 | **一个 worker 线程从 request 待到 response** | **发出请求后线程立刻走人，响应到达时回调唤醒** |
| 官方/实测依据 | 官方："requires a thread for the duration of that request"（见 0.4.1）<br>实测日志：`[nio-8081-exec-1]` | 源码：`FutureMono.doSubscribe` 的 `future.addListener(fs)`<br>实测日志：`[or-http-epoll-3]` |
| 等待期间线程 | 被占用 | 去服务其他连接 |

**实测日志对照**：

```
网关侧：
[or-http-epoll-3] DemoGlobalFilter : [gateway] --> GET /echo/T1
        （这 250ms 内该线程在服务其他连接，没有等待）
[or-http-epoll-3] DemoGlobalFilter : [gateway] <-- GET /echo/T1 status=200 OK cost=250ms

provider 侧：
[nio-8081-exec-1] EchoController   : [provider] /echo/T1 收到请求
```

#### ④ 与 0.4.5 的呼应：为什么 `-->` 和 `<--` 是同一个线程

这不是巧合，正是 0.4.5 ⑤ 中 `ColocatedEventLoopGroup` 的**设计目的**：

```
入站连接被 EventLoop-3 接受
  → 在 EventLoop-3 上发起出站（colocate：出站 Channel 也绑定到 EventLoop-3）
    → 下游响应到达，由 EventLoop-3 读取
      → 回调 FutureSubscription 在 EventLoop-3 上执行
        → post filter、写回客户端，全程仍在 EventLoop-3
```

**colocation 保证"发起的线程 = 回调的线程"**：避免跨线程切换、保住 ThreadLocal 语义。这就是"一次请求全程单线程"（0.4.5 ④）的底层原因。

> 反例：若两个 Channel 落在不同 EventLoop，写回时会通过 `eventLoop.execute()` **交接一次线程**（见 0.4.3 ⑥ 的任务队列机制）。

#### ⑤ 结论

> **Tomcat：一个 worker 线程从 request 等到 response。**
> **Gateway：epoll 线程发出请求后立刻走人，响应到达时由 Netty 的 `addListener` 回调唤醒它继续。**

#### ⑥ 证据来源

| 证据 | 位置 |
|---|---|
| `FutureMono.doSubscribe` / `FutureSubscription` | reactor-netty `v1.1.18`：`reactor-netty-core/.../reactor/netty/FutureMono.java` |
| 请求线程轨迹 | 实测：网关日志的 `[线程名]`（Spring Boot 日志格式） |

---

### 0.4.7 实战案例：阻塞式 Redis 卡在 EventLoop 上（RV33 vs RV34）

> 本节是 0.4.1~0.4.6 的实战收束：真实项目里"阻塞即灾难"长什么样、怎么发现、怎么修。
> 素材：xhs-project `my-xhs-gateway` 的两个过滤器（已修复并提交 `0607103b`）。

#### ① 案例背景

`my-xhs-gateway` 跑在 **WebFlux + Netty**（EventLoop 模型）上，但两个过滤器都用的是**阻塞式** Redis 客户端 `StringRedisTemplate`：

| 过滤器 | 用途 | Redis 调用 |
|---|---|---|
| `GatewayAuthFilter` | 校验 Token 黑名单 | `opsForValue().get(jti)` |
| `HmacSignatureFilter` | ① 读 per-session HMAC 密钥<br>② nonce 去重 | `opsForValue().get(...)`<br>`execute(LuaScript)` |

**问题**：这些调用**直接写在 `filter()` 方法体里** → 会在 Netty EventLoop 线程上真正阻塞。

#### ② 对照：一个改对了、一个漏了

**RV33 —— `GatewayAuthFilter`（已改对）**

```java
// 5+6. RV33：黑名单查询（Redis）移出 Netty EventLoop（boundedElastic），通过后再注入身份放行
return Mono.fromCallable(() -> isBlacklisted(jti))            // ← 内含阻塞的 Redis 查询
        .subscribeOn(Schedulers.boundedElastic())              // ★ 移出 EventLoop
        .flatMap(blacklisted -> { ... })
        .onErrorResume(e -> { ... 503 ... });
```

**RV34 —— `HmacSignatureFilter`（本次修复前）**

```java
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {   // :115
    ...
    String perUserSecret;
    try {
        perUserSecret = stringRedisTemplate.opsForValue().get(          // :173 ← 阻塞调用在方法体里！
                "myxhs:user:hmac:secret:" + userId);
    } catch (Exception e) { ... }
    ...
}                                                                                  // :235
```

**为什么会阻塞**：`filter()` 方法体在**调用线程**上同步执行 —— 而调用它的正是 **EventLoop 线程**。因此 `get()` 期间，该 EventLoop 承载的**所有其他连接全部停滞**（见 0.4.1 / 0.4.4）。

#### ③ 修复方式（与 RV33 同一模式）

```java
// RV34：两处阻塞式 StringRedisTemplate 调用统一移出 EventLoop
return Mono.fromCallable(() -> {
            try {
                return SecretLookup.ok(stringRedisTemplate.opsForValue()
                        .get(HMAC_SECRET_KEY_PREFIX + fUserId));
            } catch (Exception e) {
                return SecretLookup.failed(e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())          // ★ 移出 EventLoop
        .flatMap(lookup -> {
            if (lookup.error != null) { ...fail-closed 403... }
            if (lookup.value == null) { ...密钥已过期... }
            ... 验签（纯 CPU，不阻塞）...
            // 第二处：nonce 去重，同样移出
            return Mono.fromCallable(() -> stringRedisTemplate.execute(NONCE_SET_SCRIPT, ...))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(e -> { ...降级放行... })
                    .flatMap(isNew -> ...);
        });
```

**关键设计点：`SecretLookup` 载体**

若直接让 `fromCallable` 抛异常，外层 `onErrorResume` 会**连下游过滤链的异常一起捕获**（语义被扩大）。用载体把 Redis 异常显式带出来，保证与改造前完全一致：

| 场景 | 改造前后行为 |
|---|---|
| Redis 异常（读密钥阶段） | 403「签名校验暂不可用」 |
| 密钥不存在 | 403「HMAC 密钥已过期，请重新登录」 |
| 下游过滤链异常 | **仍然正常向上抛**（不被误捕获） |

#### ④ 判断口诀（怎么一眼看出这类问题）

> **看客户端类名是不是 `Reactive*` 开头。**

| 客户端 | 性质 | 用法 |
|---|---|---|
| `ReactiveRedisTemplate` / `ReactiveStringRedisTemplate` | 响应式 | 可直接用 |
| **`StringRedisTemplate` / `RedisTemplate`** | **阻塞** | **必须** `fromCallable + subscribeOn(boundedElastic)` |
| `JdbcTemplate` / JPA / 同步 HTTP 客户端 | **阻塞** | 同上 |

#### ⑤ 影响量化（为什么不是"必然崩"但必须改）

| 维度 | 说明 |
|---|---|
| 单次开销 | 一次 Redis 往返（本地 ~0.1ms，跨机 ~1ms） |
| 并发影响 | 16 个 EventLoop 线程；每个被阻塞期间，其承载的连接全部停滞 |
| 表现 | 高 QPS 下吞吐下降、尾延迟抬高；极端情况触发超时级联 |
| 结论 | 不是"必然崩溃"，但**违背响应式栈的约束**，是潜在瓶颈 |

#### ⑥ 提交记录

```
0607103b  fix(gateway): HMAC 验签的阻塞式 Redis 调用移出 Netty EventLoop
          1 file changed, 129 insertions(+), 63 deletions(-)
```

#### ⑦ 结论

> **EventLoop 上不能有阻塞调用。**
> **发现**：看是否用了非 `Reactive*` 的客户端；
> **修复**：`Mono.fromCallable(阻塞操作).subscribeOn(Schedulers.boundedElastic())`。

#### ⑧ 关联

- 原理：0.4.1（「阻塞」的准确含义）· 0.4.4（EventLoop 的放大效应）
- 官方态度：0.3 引文 *"blocking APIs are not a good fit for this concurrency model"*
- 对应课程：**Lesson 35「Blocking Is Fatal + Observability」**（本案例即其素材）

---

### 0.4.8 boundedElastic 完整拆解（槽位弹性 / 引用计数 / Worker 串行语义）

> 本节把 `boundedElastic` 从"知道有它"讲到"完全理解它的设计"。源码：`reactor-core v3.6.5`

#### ① 核心结论（要掌握的 3 条）

1. **"弹性"在池级**：boundedElastic = **最多 160 个"单线程槽位"**（0~N，可缩到 0），不是"一个大池子伸缩线程数"
2. **空闲判定靠"引用计数归零"事件**，不是查询线程状态或队列
3. **每个槽位只有 1 个线程**，是为了给 `Worker` 提供"**串行执行**"的保证

#### ② 结构：不是一个大池，而是 0~N 个单线程槽位

类级 Javadoc 原话：

> Scheduler that hosts a pool of **0-N single-threaded** `BoundedScheduledExecutorService`

```
BoundedElasticScheduler（1 个 scheduler）
├── maxThreads = 10 × CPU = 160        ← 池级上限
├── idleQueue                          ← 空闲槽位队列
├── busyStates                         ← 使用中的槽位
└── BoundedState × 0..160              ← ★ 最多 160 个槽位
      └── 每个槽位 = 独立的单线程 executor（core=1, max=1）
```

官方注释（`Schedulers.java:1174`）：
```java
static final String BOUNDED_ELASTIC = "boundedElastic"; // Blocking stuff with scale to zero
//                                                                        ↑ scale to zero = 可缩到 0
```

`pick()` 的三条分支（Javadoc：*"picked from the idle pool, **created anew** or reused from the busy pool"*）：

| 分支 | 条件 | 动作 |
|---|---|---|
| 1 | `idleQueue` 非空 | **复用**一个空闲槽位 |
| 2 | `当前槽位数 < maxThreads` | **新建**一个槽位（★ 扩容） |
| 3 | 到达上限 | 挑一个 busy 槽位（**过载**，任务进它的队列排队） |

#### ③ 线程池参数：为什么"永不超时"

Reactor 的传参：
```java
BoundedScheduledExecutorService(int queueCapacity, ThreadFactory factory) {
    super(1, factory);              // corePoolSize = 1；其余走 Scheduled 默认
    setMaximumPoolSize(1);          // ★ 显式把 max 改成 1
    setRemoveOnCancelPolicy(true);
}
```

JDK `ScheduledThreadPoolExecutor(corePoolSize, threadFactory)` 的默认：
```java
super(corePoolSize, Integer.MAX_VALUE,          // ← maximumPoolSize 默认是 MAX_VALUE！
      DEFAULT_KEEPALIVE_MILLIS, MILLISECONDS,   // = 10 毫秒
      new DelayedWorkQueue(), threadFactory);
```

| 参数 | 值 | 说明 |
|---|---|---|
| corePoolSize | **1** | |
| maximumPoolSize | **1**（Reactor 显式覆盖） | → **唯一线程就是核心线程** |
| keepAliveTime | 10ms（默认，形同虚设） | max=core，不存在非核心线程 |
| workQueue | `DelayedWorkQueue` | 支持延迟/周期任务 |
| **allowCoreThreadTimeOut** | **false（默认，未开）** | ★ 关键 |

**关键推论**（`ThreadPoolExecutor.getTask()`）：
```java
boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;
// allowCore=false, wc=1, core=1 → timed = false
Runnable r = timed ? workQueue.poll(keepAliveTime, NANOSECONDS) : workQueue.take();
//                                                                  ↑ 无条件阻塞，永不超时
```
> **这个唯一的线程阻塞在 `take()` 上，永不自己退出** → 所以 evictor 是**必需的**。

#### ④ 引用计数：对**槽位**计数，不是对线程

`markCount` 的含义：**有多少个使用方正在引用这个槽位**

| 使用方式 | 何时 +1 | 何时 -1 |
|---|---|---|
| **一次性任务** `schedule(task)` | `pick()` 时 | 任务执行完 `picked.dispose()` |
| **延迟/周期任务** `schedule(task, delay)` | **`pick()` 时（提交就占位）** | 任务跑完 / 取消 |
| **Worker** `createWorker()` | `pick()` 时 | **worker 被 dispose 时** |

证据（`createWorker()`）：
```java
public Worker createWorker() {
    BoundedState picked = state.currentResource.pick();          // markCount +1
    ExecutorServiceWorker worker = new ExecutorServiceWorker(picked.executor);
    worker.disposables.add(picked);   // ★ worker 存活期间一直持有 → markCount 保持 > 0
    return worker;
}
```

**空闲判定 = "归零事件"驱动**（不是状态查询）：
```java
void release() {                                     // 引用释放时
    int picked = MARK_COUNT.decrementAndGet(this);
    if (picked == 0) {                               // ★ 归零这一刻
        this.idleSinceTimestamp = parent.clock.millis();   //   记录空闲起点
        parent.setIdle(this);                        //   放进 idleQueue
    } else {
        this.idleSinceTimestamp = -1L;               // 还有引用 → 撤销空闲标记
    }
}
```
evictor 只需比较 `now - idleSince >= 60s`，**不需要判断线程状态**。

**为什么不用其他方式判断空闲？**

| 方式 | 问题 |
|---|---|
| `Thread.getState()` | 线程 parked 在 `take()` 上**看似空闲**，但队列里可能压着延迟任务 → 实为忙 |
| **看队列大小** | **队列空 ≠ 空闲**：任务可能**正在执行**（已出队但线程在跑）→ 会误回收正在跑的槽位 ❌ |
| **`markCount` 引用计数** | ✅ 同时覆盖"正在执行 + 在队列等 + worker 占用"，是语义级的准确信号 |

#### ⑤ Worker 串行语义：为什么每槽位只给 1 个线程

**Reactor 的两级抽象**：

```
Scheduler（池）              例：Schedulers.boundedElastic()
    │
    └── createWorker() ──→ Worker（★ 保证串行的执行上下文）
                              └── worker.schedule(task) × N → 按序串行执行
```

**为什么需要这个保证**：Reactive Streams 规范要求**信号（onNext/onError/onComplete）不得并发、必须有序**。而 `publishOn` / `subscribeOn` 就是靠 Worker 满足它（真实源码）：

```java
// MonoSubscribeOn.java
Scheduler.Worker worker = scheduler.createWorker();   // :48  ★ 创建 Worker
worker.schedule(parent);                              // :55  ★ 用 Worker 调度
worker.schedule(() -> s.request(n));                  // :193
```

**串行怎么实现**：每个槽位只有一个线程 → Worker 的任务全进同一队列 → 按序执行：

```
✅ pinned（保证串行）：
   槽位1 的 task1 → T1 执行
   槽位1 的 task2 → 排在槽位1 队列，等 T1 做完   ← 串行

❌ 假想"共享多线程池"（破坏语义）：
   槽位1 的 task1 → T1 正在执行
   槽位1 的 task2 → 被空闲 T2 偷走执行          ← 并发！
```

Javadoc 原话：*"tasks are **pinned** to a given executor, so they **won't be stolen by an idle executor**"*。

**过载时的取舍**（Javadoc 也提到）：达到 `maxThreads` 后，多个 Worker 会被分配到**同一个槽位**（"reused from the busy pool"），一个慢任务会拖慢另一个 Worker —— 即 *"impeded by long-running tasks of a sibling worker"*。这是**保证串行 > 保证隔离**的取舍。

#### ⑥ boundedElastic 到底用来做什么（澄清一个常见混淆）

| 操作 | 谁来做 | 为什么 |
|---|---|---|
| **非阻塞网络 IO**（HTTP 收发、响应式 Redis 客户端） | **EventLoop 线程** | 不阻塞，EventLoop 直接干 |
| **阻塞式调用**（JDBC、`StringRedisTemplate`、文件 IO、同步 HTTP） | **boundedElastic** | 会"停下来等"，不能占 EventLoop |

> ❌ 不是"IO 就要用 boundedElastic" —— Netty 的网络 IO 全在 EventLoop 上，没问题
> ✅ 是"**会让线程停下来等**的调用"才要用

#### ⑦ 结论

> **boundedElastic = 最多 160 个"单线程槽位"，弹性在槽位数（0~160）。**
> **引用计数对槽位计数，归零瞬间记录空闲起点；evictor 只比较时间戳。**
> **每槽位单线程是为了给 Worker 提供"串行"保证（Reactive Streams 规范要求）。**

#### ⑧ 证据来源

| 证据 | 位置 |
|---|---|
| 槽位结构 / pick 分支 / 引用计数 / evictor | `reactor-core v3.6.5`：`.../scheduler/BoundedElasticScheduler.java` |
| 参数默认值（`maximumPoolSize=MAX_VALUE`、`DEFAULT_KEEPALIVE_MILLIS=10`） | `openjdk11u`：`.../util/concurrent/ScheduledThreadPoolExecutor.java:443,472` |
| `getTask()` 的 `timed` 判定与退出守卫 | `openjdk11u`：`.../util/concurrent/ThreadPoolExecutor.java` |
| `subscribeOn` 使用 Worker | `reactor-core v3.6.5`：`.../publisher/MonoSubscribeOn.java:48,55,193` |
| boundedElastic 默认参数（160 / 100000 / 60s） | `.../scheduler/Schedulers.java` + `BoundedElasticSchedulerSupplier.java` |

#### ⑨ 关联

- 0.3（阻塞/非阻塞）· 0.3.1（Reactive Streams）· 0.4.1（EventLoop 的放大效应）
- **0.4.7**（实战：把阻塞 Redis 移到 boundedElastic）
- **0.5.1**（Mono / Flux —— 本节提到的 `Publisher` 的具体实现）

---

## 0.5 Imperative vs Reactive — 命令式与响应式

**英文原文** — Spring WebFlux 官方文档《Concurrency Model》

> Both Spring MVC and Spring WebFlux support annotated controllers, but there is a key difference in the **concurrency model** and the default assumptions for blocking and threads.
>
> In Spring MVC (and servlet applications in general), it is assumed that applications **can block the current thread** (for example, for remote calls). For this reason, servlet containers use a **large thread pool** to absorb potential blocking during request handling.
>
> In Spring WebFlux (and non-blocking servers in general), it is assumed that applications **do not block**. Therefore, non-blocking servers use a **small, fixed-size thread pool (event loop workers)** to handle requests.
>
> TIP: "To scale" and "small number of threads" may sound contradictory, but **to never block the current thread (and rely on callbacks instead) means that you do not need extra threads, as there are no blocking calls to absorb.**

**中文翻译**

> Spring MVC 与 Spring WebFlux 都支持注解式控制器，但二者的**并发模型**、以及关于阻塞与线程的默认假设存在关键差异。
>
> 在 Spring MVC（以及一般意义上的 Servlet 应用）中，**假设应用允许阻塞当前线程**（例如发起远程调用）。因此，Servlet 容器使用**大线程池**来吸收请求处理过程中的潜在阻塞。
>
> 在 Spring WebFlux（以及一般意义上的非阻塞服务器）中，**假设应用不阻塞**。因此，非阻塞服务器使用**小的、固定大小的线程池（事件循环工作线程）**来处理请求。
>
> 提示："可扩展"与"少量线程"听起来矛盾，但**只要永不阻塞当前线程（改为依赖回调），就不需要额外线程，因为没有阻塞调用需要吸收。**

**① 核心结论**

命令式（imperative）与响应式（reactive）是两种编程模型：前者以"顺序执行的语句 + 阻塞调用"为基本假设，后者以"声明式的算子组合 + 回调"为基本假设。**模型本身不存在优劣，但与运行时线程模型必须匹配。**

**② 机制说明**

**两种模型的代码形态对比**（同一语义：发起远程调用 → 处理结果）：

```java
// 命令式（Spring MVC 的常规写法）
User user = userClient.getUser(id);     // 阻塞：当前线程等待结果
String name = user.getName();           // 下一句一定在"结果已返回"之后执行
return name;

// 响应式（WebFlux / Gateway 的写法）
return userClient.getUser(id)           // 返回 Mono<User>，立即返回，尚未有结果
        .map(user -> user.getName());   // 声明"结果就绪后要做什么"（算子组合）
```

**关键差异**：

| 维度 | 命令式 | 响应式 |
|---|---|---|
| 控制流 | 语句顺序执行 | 声明算子链，由框架在数据就绪时驱动 |
| 取值时机 | 调用返回时值已就绪 | 调用返回时值尚未就绪（拿到的是"未来"） |
| 阻塞假设 | 允许阻塞 | 假设不阻塞 |
| 配套线程模型 | 大线程池（吸收阻塞） | 小固定线程池（事件循环） |
| 组合异步逻辑 | 困难（需手动 Future/回调编排） | 天然（算子即可组合） |
| 调试与排错 | 直观（栈帧连续） | 较复杂（栈帧被回调切断） |

**官方关于"适用性"的判断**（同文档《Applicability》节，摘录要点）：

> If you have a Spring MVC application that works fine, there is no need to change. **Imperative programming is the easiest way to write, understand, and debug code.** You have maximum choice of libraries, since, historically, most are blocking.
>
> If you have blocking persistence APIs (JPA, JDBC) or networking APIs to use, **Spring MVC is the best choice** for common architectures at least. It is technically feasible with both Reactor and RxJava to perform blocking calls on a separate thread but you would **not be making the most of a non-blocking web stack.**
>
> If you have a large team, keep in mind **the steep learning curve** in the shift to non-blocking, functional, and declarative programming.

**③ 官方要点**

1. **差异的本质是"默认假设"**：Servlet 假设"可以阻塞"→ 配大线程池；WebFlux 假设"不阻塞"→ 配小固定线程池
2. **"少量线程"与"可扩展"不矛盾**：官方解释——不阻塞就不需要额外线程去"吸收"阻塞
3. **官方不主张无条件迁移**：MVC 应用运行良好则无需改动；命令式是最易写、易读、易调试的
4. **遇到阻塞式持久化 API（JPA / JDBC）时，官方推荐 Spring MVC** —— 强行搬到 WebFlux 无法发挥其价值
5. **学习曲线陡峭**（steep learning curve）是官方明确的提醒

**④ 术语对照**

| 英文 | 中文 |
|---|---|
| imperative | 命令式 |
| reactive | 响应式 |
| concurrency model | 并发模型 |
| to block the current thread | 阻塞当前线程 |
| large thread pool | 大线程池 |
| small, fixed-size thread pool | 小固定大小线程池 |
| rely on callbacks | 依赖回调 |
| steep learning curve | 陡峭的学习曲线 |

**⑤ 结论**

> **命令式 = 顺序语句 + 允许阻塞（配大线程池）；响应式 = 算子组合 + 假设不阻塞（配小固定线程池）。选型取决于是否存在阻塞式依赖。**

---

### 0.5.1 Mono 与 Flux（Reactor 的核心类型）

> 本节回答：响应式编程里到处出现的 `Mono` / `Flux` 到底是什么。
> 0.3.1 讲过 Reactive Streams 的四个接口 —— `Mono` / `Flux` 就是 **`Publisher` 的两个标准实现**。

#### ① 定义

| 类型 | 定义 | 值的个数 |
|---|---|---|
| **`Mono<T>`** | **0 或 1 个值**的异步序列 | 0..1 |
| **`Flux<T>`** | **0 到 N 个值**的异步序列 | 0..N |

两者都实现 `org.reactivestreams.Publisher<T>` —— 所以它们能互相转换、能被任何响应式库消费。

#### ② 为什么是两个类型（而不是一个）

**类型即文档**：

```java
Mono<User>   getUser(id);        // 读一眼就知道：最多一个结果
Flux<User>   listUsers();        // 读一眼就知道：可能多个（或 0 个）
```

如果统一成一个 `Stream<T>`，就丢失了这个语义信息。而且两种场景的算子**语义不同**（`Mono.then` vs `Flux.thenMany`）。

#### ③ 与 `CompletableFuture` 的对比（如果你熟悉它）

| 维度 | `CompletableFuture` | `Mono` / `Flux` |
|---|---|---|
| 值的个数 | 恰好 1 个 | 0..1（Mono） / 0..N（Flux） |
| 组合方式 | `thenApply` / `thenCompose` | `map` / `flatMap` |
| 背压（back pressure） | ❌ 无 | ✅ 有（0.3.1） |
| **惰性** | ❌ 创建即开始执行 | ✅ **订阅才执行** |
| 多值 | ❌ | ✅（Flux） |
| 取消 | 有限 | ✅ 完整的 `Disposable` |

> **最关键的差别是"惰性"** —— 见下。

#### ④ 核心概念：惰性（lazy）—— 订阅才执行

```java
Mono<String> m = Mono.fromCallable(() -> doSomething());   // ★ 什么都不发生！
```

这一行**不会执行** `doSomething()`。它只是**描述**了一个"未来要做的事"。

只有 `subscribe()` 才触发执行：

```java
m.subscribe(
    value -> log.info("onNext: {}", value),   // 有值时
    error -> log.error("onError", error),     // 出错时
    ()    -> log.info("onComplete")           // 完成时
);
```

**这正是 `filter()` 里能直接 `return Mono.fromCallable(...)` 的原因** —— 返回的是"描述"，不是结果；框架会在合适的时机订阅它。

> 类比：`Mono` 像一张**订单**，不是菜。下单不等于做菜，厨房（框架）拿到订单才会开工。

#### ⑤ 与 Gateway 的关系（为什么到处是 `Mono<Void>`）

```java
// DemoGlobalFilter（你已写过）
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return chain.filter(mutated)                        // Mono<Void>：过滤链执行完
            .then(Mono.fromRunnable(() -> {             // .then：前者完成后执行后者
                log.info("[gateway] <-- ...");
            }));
}
```

| 出现 | 含义 |
|---|---|
| **`Mono<Void>`** | **0 个值**的 Mono —— 表示"一个动作，没有返回值"（对应 Servlet 的 `void doFilter`） |
| `.then(next)` | 忽略前者的值，**等它完成后**执行 `next` |
| `Mono.fromRunnable(...)` | 把一个普通 `Runnable` 包成"完成即结束"的 Mono |

**在 Gateway 里你几乎不写 `subscribe()`** —— WebFlux 框架在把响应写回客户端时会自动订阅。这是**控制反转**：你返回"描述"，框架决定何时执行。

#### ⑥ 最小算子表（Lesson 09 会展开）

| 算子 | 作用 | 类比命令式 |
|---|---|---|
| `map` | 同步转换值 | `x = f(x)` |
| `flatMap` | 转换并**展开**成新的 Mono/Flux（用于异步嵌套） | 嵌套调用后取结果 |
| `then` | 忽略前者的值，等完成 | `; `（顺序执行） |
| `doOnNext` / `doOnSuccess` / `doOnError` | 副作用（日志/指标），不改变流 | 打日志 |
| `defaultIfEmpty` | 空时给默认值 | `x != null ? x : def` |
| `filter` | 过滤 | `if` |
| `subscribeOn` | 指定**订阅（=执行）发生在哪个池** | 换线程跑 |
| `publishOn` | 指定**后续算子在哪**个池执行 | 换线程继续 |

> 本节只需**认识**这些算子；逐个理解是 **Lesson 09** 的事。

#### ⑦ 面试答法（30 秒）

> "`Mono` 和 `Flux` 是 Reactor 的两个核心类型，分别是 0..1 和 0..N 个值的异步序列，都实现了 Reactive Streams 的 `Publisher`。它们和 `CompletableFuture` 最大的区别是**惰性** —— 创建时不执行，`subscribe` 时才触发，而且支持背压。Gateway 的过滤器返回 `Mono<Void>` 表示'一个无返回值的动作'，框架负责订阅。"

#### ⑧ 你不需要知道的（现在）

| 内容 | 何时再看 |
|---|---|
| 算子逐个讲（约 10 个） | **Lesson 09** |
| `Schedulers` 家族（`single` / `immediate` / `fromExecutor`） | 用时查 |
| 背压类算子（`limitRate` / `onBackpressureBuffer`） | 不涉及 |
| `Flux` 的多值场景（Gateway 几乎只用 `Mono`） | Lesson 09 |

#### ⑨ 结论

> **`Mono` = 0..1 个值，`Flux` = 0..N 个值；都是 `Publisher` 的实现；都是惰性的（订阅才执行）。**
> **Gateway 里最常见的是 `Mono<Void>` —— "一个无返回值的动作"。**

#### ⑩ 关联

- **0.3.1**（Reactive Streams：`Mono`/`Flux` 是 `Publisher` 的实现）
- **0.4.6**（`Mono` 的底层：`ChannelFuture` + `addListener`）
- **0.4.8**（boundedElastic：`subscribeOn` 用的 Worker 就是从这里来的）
- **Lesson 09**（Reactor 算子逐个讲）

---

## 0.6 Spring WebFlux

### 0.6.0 阅读顺序

本节分两部分：

| 部分 | 小节 | 处理方式 |
|---|---|---|
| **要掌握的** | 0.6.1 它是什么（定义）· 0.6.2 面试答法 · 0.6.3 怎么认出它 · **0.6.4 基本使用** · 0.6.5 边界 | 读懂，能复述 |
| **存档 / 查阅** | 0.6.6 官方英文原文 · 0.6.7 MVC 对照表 · 0.6.8 与 Gateway 的关系 | 不用读，遇到时回来查 |

> 前提：引了 `spring-cloud-starter-gateway`，WebFlux 就作为传递依赖被拉了进来——**你已经在用它了**。

### 0.6.1 它是什么（定义）

**一句话定义**

> WebFlux 是 Spring **5.0 引入的响应式 Web 框架**，与 Spring MVC **并列**：两者解决同一个问题——把 HTTP 请求分发到 Java 处理方法、把返回值写成 HTTP 响应——但**底座不同**。

**底座差异（这才是 WebFlux 的本质）**

| 维度 | Spring MVC | Spring WebFlux |
|---|---|---|
| 建立在什么规范上 | **Servlet API** | **Reactive Streams**（见 0.3.1） |
| IO 模型 | 阻塞 IO | **非阻塞 IO** |
| 线程模型 | 一请求一线程（大线程池） | **事件循环（少量固定线程）** |
| 默认服务器 | Tomcat | **Netty** |
| 异步结果表达 | `CompletableFuture` / `DeferredResult` | **`Mono` / `Flux`** |

**它内部由什么组成**（Web 框架的职责，WebFlux 用响应式方式重做了一遍）

| Web 框架的职责 | Spring MVC 的实现 | WebFlux 的实现 |
|---|---|---|
| HTTP 服务器适配 | Servlet API | `HttpHandler` + 各服务器适配器 |
| 请求-响应上下文 | `HttpServletRequest` / `Response` | **`ServerWebExchange`** |
| 过滤器 | `Filter`（Servlet 规范） | `WebFilter` |
| 请求分发 | `DispatcherServlet` | `DispatcherHandler` |
| 处理器匹配与调用 | `HandlerMapping` / `HandlerAdapter` | 同名组件（职责相同） |
| 控制器写法 | 注解式 | 注解式（**同注解**）**或**函数式端点 |
| 结果写回 | `HttpMessageConverter` | 响应式 codecs |

> 这张表回答的就是"WebFlux 到底是什么"：**它是把上表右列这一整套东西，用非阻塞 + Reactive Streams 重新实现出来的 Web 框架。**

### 0.6.2 面试答法（30 秒版，可直接背）

> "WebFlux 是 Spring 5.0 引入的**响应式 Web 框架**，和 Spring MVC 并列。二者解决同一个问题——把 HTTP 请求分发到处理方法、把结果写回响应——区别在底座：MVC 基于 Servlet API，一请求一线程 + 阻塞 IO，跑在 Tomcat 上；WebFlux 基于 Reactive Streams，事件循环 + 少量固定线程 + 非阻塞 IO，默认跑在 Netty 上。它的控制器返回值用 `Mono`/`Flux` 表达 0..1 和 0..N 的异步序列。我们项目的网关就建在 WebFlux 上，所以网关的过滤器里不能有阻塞调用——**这是它的推论，不是它的定义**。"

**为什么最后要加那半句**：面试官最常在这里追问"那'不能阻塞'是 WebFlux 的特性吗？"——**主动把"定义"与"推论"分开**，比被追问后才发现说不清要好得多。

### 0.6.3 怎么认出它（3 条实测证据）

#### 第 1 件：它在依赖里

实测 `mvn -pl demo-gateway dependency:tree` 的结果：

```
demo-gateway
└── org.springframework.cloud:spring-cloud-starter-gateway
    └── org.springframework.boot:spring-boot-starter-webflux:3.2.5     ← WebFlux 在这里
        ├── org.springframework.boot:spring-boot-starter-reactor-netty:3.2.5
        │   └── io.projectreactor.netty:reactor-netty-http:1.1.18
        └── org.springframework:spring-webflux:6.1.6
```

对照 `demo-provider`（MVC 侧）：

```
demo-provider
└── org.springframework.boot:spring-boot-starter-web:3.2.5
    └── org.springframework.boot:spring-boot-starter-tomcat:3.2.5
        └── org.apache.tomcat.embed:tomcat-embed-core:10.1.20
```

**结论：`spring-boot-starter-webflux` 就是 WebFlux。** 引它 = 用 WebFlux；引 `spring-boot-starter-web` = 用 MVC。

#### 第 2 件：它在启动日志里

实测两个服务的启动日志关键字：

| 服务 | 启动日志 | 判定 |
|---|---|---|
| `demo-provider` | `o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat initialized with port 8081`<br>`Starting Servlet engine: [Apache Tomcat/10.1.20]` | **MVC** + Servlet 容器 |
| `demo-gateway` | `o.s.b.web.embedded.netty.NettyWebServer : Netty started on port 8080`<br>（该日志中 **Tomcat 出现 0 次**） | **WebFlux** + Netty |

**结论：看到 `NettyWebServer` 就是 WebFlux；看到 `TomcatWebServer` + `Servlet engine` 就是 MVC。**

#### 第 3 件：它在代码里

```java
// WebFlux（Gateway）的写法：返回 Mono / Flux
return chain.filter(mutated)
        .then(Mono.fromRunnable(() -> log.info("...")));

// MVC（provider）的写法：返回普通对象
@GetMapping("/echo/{msg}")
public Map<String, Object> echo(@PathVariable String msg, HttpServletRequest request) {
    return body;
}
```

**结论：返回值是 `Mono` / `Flux` 的，就是 WebFlux 侧的代码。**

### 0.6.4 基本使用（最小例子，不引 Spring Boot）

> 代码：`reactor-lab/src/main/java/com/myxhs/demo/reactor/l00/Ex03_MinimalWebFlux.java`（**已跑通**）
> 跑：`mvn -pl reactor-lab exec:java -Dexec.mainClass=com.myxhs.demo.reactor.l00.Ex03_MinimalWebFlux`

**拼装关系**（理解 Gateway 位置的关键）：

```
RouterFunction（路由表）
     ↓ RouterFunctions.toHttpHandler(...)
HttpHandler（WebFlux 对外的统一形态）
     ↓ ReactorHttpHandlerAdapter（桥）
Reactor Netty HttpServer（传输层，见 0.4.4）
```

```java
// ① 路由表（函数式端点写法）
RouterFunction<ServerResponse> routes = RouterFunctions.route()
        .GET("/hello", req -> ServerResponse.ok().bodyValue("hello webflux"))
        .build();

// ② RouterFunction → HttpHandler
HttpHandler httpHandler = RouterFunctions.toHttpHandler(routes);

// ③ HttpHandler → Reactor Netty HttpServer（Spring Boot 的 NettyWebServer 就干这个）
DisposableServer server = HttpServer.create().port(0)
        .handle(new ReactorHttpHandlerAdapter(httpHandler))
        .bindNow();
```

**实测输出**：

```
[WebFlux] 已启动，端口 = 42451
[HttpClient] GET /hello → hello webflux
[HttpClient] GET /echo/my-xhs → echo: my-xhs
```

**要点**：

| # | 结论 |
|---|---|
| 1 | **不引 Spring Boot 也能跑** WebFlux（只要 `spring-webflux` + `reactor-netty`） |
| 2 | `ReactorHttpHandlerAdapter` = **Spring Boot 里 `NettyWebServer` 干的事** |
| 3 | **Spring Boot 的自动配置 = 把上面的装配好**（外加 `DispatcherHandler` / 各 `HandlerMapping` 注册） |
| 4 | Gateway = 这套 + 自己的扩展点：`RoutePredicateHandlerMapping`（HandlerMapping）+ `FilteringWebHandler`（WebHandler） |
| 5 | ⚠️ `DispatcherHandler` **没有 setter**（Spring 6 只能由 `ApplicationContext` 注入策略）→ 最小例子走 RouterFunction 形态；**它的机制与 Gateway 插入点见 Lesson 18** |

### 0.6.5 你不需要知道的（明确划界）

| 不需要知道 | 归谁管 |
|---|---|
| `DispatcherHandler` / `HandlerAdapter` / `ResultHandler` 的**逐行实现** | **Lesson 18**（请求管线：讲"3 个策略 + 遍历 HandlerMapping"的机制，**不逐行**）⭐ v5.5 改判：原写"不用学"，但它就在请求链路上，不学 = 断档 |
| Reactive Streams 规范细节、背压的实现 | **Lesson 09 / 11**（Stage 3 Reactive Foundation） |
| `Mono` / `Flux` 算子大全（`map` / `flatMap` / `then` / `publishOn` …） | **Lesson 10**（算子） |
| `WebHandler` API 的完整组件清单（session / codec / multipart …） | 用时查官方文档 |

**对本课程而言，只需要记住 3 条约束**（后面会反复用到）：

| # | 约束 | 出现在哪 |
|---|---|---|
| 1 | Gateway 项目**不能引** `spring-boot-starter-web`（Servlet 栈，会冲突） | Lesson 02 |
| 2 | 代码里会看到 `Mono` / `Flux`，不是普通对象 | Lesson 09 |
| 3 | **不能写阻塞代码**（由 0.3 + 0.4 推出） | Lesson 35 |

### 0.6.6 官方原文（存档，需要时再读）

**英文原文** — Spring WebFlux 官方文档 `web/webflux.adoc`

> The original web framework included in the Spring Framework, Spring Web MVC, was purpose-built for the Servlet API and Servlet containers. The reactive-stack web framework, **Spring WebFlux**, was added later in **version 5.0**. It is **fully non-blocking**, supports Reactive Streams back pressure, and runs on such servers as **Netty, Undertow, and Servlet containers**.
>
> Both web frameworks **mirror** the names of their source modules (`spring-webmvc` and `spring-webflux`) and **co-exist side by side** in the Spring Framework. Each module is optional. Applications can use one or the other module or, in some cases, both -- for example, Spring MVC controllers with the reactive `WebClient`.

**中文翻译**

> Spring Framework 最初的 Web 框架 **Spring Web MVC** 是专为 Servlet API 和 Servlet 容器打造的。响应式栈 Web 框架 **Spring WebFlux** 在 **5.0 版本**加入。它**完全非阻塞**，支持 Reactive Streams 背压，并可运行在 **Netty、Undertow 及 Servlet 容器**之上。
>
> 两个 Web 框架的命名**镜像**了各自的源码模块（`spring-webmvc` 与 `spring-webflux`），在 Spring Framework 中**并列共存**。两个模块都是可选的，应用可选用其一，某些场景也可同时使用——例如用 Spring MVC 控制器配合响应式 `WebClient`。

**一句话**：WebFlux 是 **5.0 新增**、与 MVC **并列**（官方用词 `mirror` / `co-exist side by side`）的响应式 Web 栈。

### 0.6.7 MVC ↔ WebFlux 对照表（查阅用，不用背）

| 维度 | Spring MVC | Spring WebFlux |
|---|---|---|
| 引入版本 | 1.0 | **5.0** |
| 面向规范 | Servlet API | Reactive Streams |
| 前端控制器 | `DispatcherServlet` | `DispatcherHandler` |
| 处理器映射 | `HandlerMapping` | `HandlerMapping`（同名同职责） |
| 处理器适配 | `HandlerAdapter` | `HandlerAdapter`（同名） |
| 过滤器契约 | `Filter`（Servlet 规范） | `WebFilter` |
| 请求-响应上下文 | `HttpServletRequest` / `HttpServletResponse` | **`ServerWebExchange`** |
| 控制器返回值 | 对象 / `ModelAndView` | **`Mono<T>` / `Flux<T>`** |
| 服务器（实测） | Tomcat（`TomcatWebServer`） | **Netty**（`NettyWebServer`） |
| 线程模型 | thread-per-request | **EventLoop**（见 0.4） |
| 阻塞假设 | 允许阻塞 | **假设不阻塞**（见 0.5） |

> 这张表是**索引**不是**学习内容**：以后看到 WebFlux 的某个类名，回来查它对应 MVC 的什么即可。

### 0.6.8 与 Gateway 的关系（本课收束）

| Gateway 组件 | 对应的 WebFlux 扩展点 |
|---|---|
| `RoutePredicateHandlerMapping` | WebFlux 的 **`HandlerMapping`**（源码 Lesson 23；管线 Lesson 18） |
| `FilteringWebHandler` | WebFlux 的 **`WebHandler`**（Lesson 21；管线见 L18） |
| `ServerWebExchange` | WebFlux 的请求-响应上下文（Gateway 全程使用） |

> ⚠️ **易混点**：Gateway 的 `GlobalFilter` 与 WebFlux 的 `WebFilter` **不是同一个东西**——前者是 Gateway 自定义的过滤器契约（`org.springframework.cloud.gateway.filter.GlobalFilter`），后者是 WebFlux 的通用过滤器（`org.springframework.web.server.WebFilter`）。

### 0.6.9 结论

> **定义**：WebFlux 是与 Spring MVC 并列的**响应式 Web 框架**——把 Web 框架的整套职责，用"非阻塞 + Reactive Streams"重新实现了一遍。
>
> **认出它**：依赖里的 `spring-boot-starter-webflux`、启动日志的 `NettyWebServer`、代码里的 `Mono` / `Flux`。
>
> **它的推论**（不是定义）：不能写阻塞代码。

---

## 0.7 概念关系图

```
0.1 反向代理
     │  站在服务端位置、对客户端透明的代理
     ↓
0.2 Servlet 容器（Tomcat）
     │  容器的执行模型 = thread-per-request
     ↓
0.3 阻塞 IO ── 与 ── 非阻塞 IO
     │  阻塞 = 线程等待；非阻塞 = 回调通知
     ↓
0.4 事件循环（EventLoop）
     │  非阻塞的调度实现：少量固定线程 + 事件队列
     ↓
0.5 命令式 vs 响应式（编程模型）
     │  命令式配大线程池 / 响应式配小固定线程池
     ↓
0.6 Spring WebFlux
        把 0.5 的响应式编程模型落成 Web 框架（MVC 的镜像）
        → Gateway 就建在它上面
```

**回到 Lesson 01**：Gateway 选择了右侧那条路（非阻塞 + 事件循环 + 响应式 + WebFlux），因此：
- 不能写阻塞代码（0.3 + 0.4 的推论）
- 不能跑在 Servlet 容器里（0.2 的结论）
- 代码形态是 `Mono`/`Flux` 算子链（0.5 + 0.6）
- 路由匹配挂在 WebFlux 的 `HandlerMapping` 上（0.6 的结论）

---

## 0.8 Self-Test

1. 正向代理与反向代理的核心区别是什么？判断方法是什么？
2. Servlet 的生命周期方法有哪几个？由谁驱动？
3. Tomcat 的架构层级自外向内依次是什么？
4. "阻塞调用本身就是天然的背压"——这句话怎么理解？非阻塞为什么需要显式实现背压？
5. EventLoop 为什么只需要约 CPU 核数个线程？单个阻塞为什么在此模型下后果被放大？
6. Tomcat 的 IO 层是阻塞的吗？（用官方对 NIO 连接器的描述回答）那 Tomcat 所谓的"阻塞"到底指什么？
7. Netty 官方建议用 `await()` 还是 `addListener()`？两者对应哪两种模型？
8. `selector.select()` 阻塞时，**是哪个线程/组件在等**？为什么说"等待不需要线程"？回调最终跑在哪个线程上？
9. 官方对"是否应该迁移到 WebFlux"给出了什么建议？遇到 JPA/JDBC 时官方推荐哪个？
10. WebFlux 与 Spring MVC 的关系是什么（官方用了哪两个词）？说出至少 4 组对应组件。
11. Gateway 的 `RoutePredicateHandlerMapping` 和 `FilteringWebHandler` 分别实现了 WebFlux 的哪个扩展点？
12. 一次网关转发涉及几条 TCP 连接？网关各持哪一端？为什么说 `Channel` 是"本地对象"（Tomcat 侧不存在 Channel）？
13. Reactor Netty 与 Netty 是什么关系？它提供哪四个组件？官方说它的 Event Loop Group 在 JVM 内是共享还是独占？
14. Gateway 转发下游时，`NettyRoutingFilter` 持有的是哪个类的实例？它由哪个 Factory 创建？
15. 一个运行的网关进程里有几种**业务线程**？其中哪种**处理请求**？其余两种池（`parallel` / `boundedElastic`）各负责什么？
16. 一次请求的完整生命周期（收请求→filter→转发→收响应→写回）用几个线程？与 provider 侧 Tomcat 的模型有何对比？
17. 16 个 `reactor-http-epoll-*` 为什么在"首次转发"时**一次全部启动**？（提示：`ColocatedEventLoopGroup` 构造器做了什么）
18. `Mono` 的底层靠 Netty 的哪个机制实现？`FutureSubscription` 为什么能同时当"Netty 回调"和"Reactor 订阅"用？
19. 为什么实测中同一次请求的 `-->` 和 `<--` 是**同一个线程**？这与 `ColocatedEventLoopGroup` 有何关系？
20. 案例：`HmacSignatureFilter` 的阻塞问题是怎么发现和修复的？为什么 `SecretLookup` 载体是必要的（直接抛异常会有什么语义问题）？
21. 一眼判断某个 Redis/JDBC 客户端能否直接用在 EventLoop 上的口诀是什么？
22. boundedElastic 的"弹性"体现在哪里（单个槽位的线程数是多少）？`markCount` 是对**什么**计数？"归零"之后发生了什么？
23. `Mono` 和 `Flux` 分别是什么？它们与 `CompletableFuture` 最关键的差别是什么？Gateway 里为什么到处是 `Mono<Void>`？

---

## 0.9 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「0.3 ② 没懂」），我会就地补进正文。

| # | 疑问 | 状态 |
|---|---|---|
| | | |

---

## 0.10 术语补遗（查阅用，一句话一个）

> 本课正文出现但未展开的术语，统一在此收口。**不必记忆**，遇到回来查。

| 术语 | 一句话 | 出现在 |
|---|---|---|
| `ServerWebExchange` | WebFlux 的"请求-响应上下文"对象，一次请求对应一个实例 | 0.6.1 / what-is 1.1.3 |
| `DispatcherHandler` | WebFlux 的前端控制器（对应 MVC 的 `DispatcherServlet`） | 0.6.1 |
| `WebHandler` | WebFlux 的核心处理器契约（`FilteringWebHandler` 是它的实现之一） | 0.6.1 / 0.6.5 |
| `WebClient` | WebFlux 的响应式 HTTP 客户端（对应 MVC 时代的 `RestTemplate`） | 0.4.4 / 0.6.5 |
| `Scheduler` / `publishOn` | Reactor 的线程池抽象 / 切换执行线程的算子（逃生舱机制） | 0.3 / 0.4.2 |
| `ConnectionProvider` | Reactor Netty 的连接池 —— **出站 Channel 的来源** | 0.4.4 |
| `LoopResources` | Reactor Netty 对 EventLoop Group 的封装 | 0.4.4 |
| `HttpMessageConverter` | MVC 的消息转换器（Java 对象 ↔ HTTP body） | 0.6.1 |
| `ResultHandler` | WebFlux 中把方法返回值写回响应的组件 | 0.6.1 |
| `AsyncContext` | Servlet 3.0 的异步支持（在 Tomcat 上写非阻塞的入口） | 0.4.1 |
| `epoll` | Linux 的多路复用 IO 接口 —— 前文"内核负责等待"所指的机制 | 0.4.2 |

---

## Appendix: 官方来源清单

| 小节 | 来源 | 位置 |
|---|---|---|
| 0.1 | MDN《Proxy servers and tunneling》 | https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Proxy_servers_and_tunneling |
| 0.1 补充 | Tomcat `proxy-howto.xml`（运行在代理之后的后果） | `{tomcat}/webapps/docs/proxy-howto.xml` |
| 0.2 | `jakarta.servlet.Servlet` 接口 Javadoc | `{tomcat}/java/jakarta/servlet/Servlet.java:22-45` |
| 0.2 补充 | Tomcat `architecture/overview.xml` | `{tomcat}/webapps/docs/architecture/overview.xml` |
| 0.3 | Spring WebFlux 官方文档《Define Reactive》《Invoking a Blocking API》 | `{spring-framework}/framework-docs/modules/ROOT/pages/web/webflux/new-framework.adoc` |
| 0.3.1 | OpenJDK `java.util.concurrent.Flow` 类 Javadoc | `{jdk11u}/src/java.base/share/classes/java/util/concurrent/Flow.java:39-46` |
| 0.4 / 0.5 | Spring WebFlux 官方文档《Threading Model》《Concurrency Model》 | `{spring-framework}/framework-docs/modules/ROOT/pages/web/webflux/new-framework.adoc` |
| 0.6 | Spring WebFlux 官方文档（总览页） | `{spring-framework}/.../web/webflux.adoc` |
| 0.6 | Spring WebFlux 官方文档（WebHandler API 层） | `{spring-framework}/.../web/webflux/reactive-spring.adoc` |
| 0.4 补充 | Netty 官方 README | `{netty}/README.md` |
| 0.4.1 | Tomcat `config/http.xml`（线程模型/NIO 连接器/maxThreads） | `{tomcat}/webapps/docs/config/http.xml:54,220,584` |
| 0.4.1 | Netty `ChannelFuture` 类 Javadoc（异步模型 / addListener） | `{netty}/transport/src/main/java/io/netty/channel/ChannelFuture.java:29,67` |
| 0.4.2 | Netty `NioIoHandler`（EventLoop 主循环 / select / processSelectedKeys） | `{netty}/transport/src/main/java/io/netty/channel/nio/NioIoHandler.java:410,475,621` |
| 0.4.2 | Spring `ReactorHttpHandlerAdapter`（Reactor Netty ↔ WebFlux 桥） | `{spring-framework}/spring-web/src/main/java/org/springframework/http/server/reactive/ReactorHttpHandlerAdapter.java:40,55,65` |
| 0.4.2 | WebFlux 官方《Threading Model》（共享 event loop resources） | `{spring-framework}/framework-docs/modules/ROOT/pages/web/webflux/new-framework.adoc` |
| 0.4.3 | Netty `Channel` 类 Javadoc（"nexus to a network socket"） | `{netty}/transport/src/main/java/io/netty/channel/Channel.java:31-32` |
| 0.4.3 | 实测 `ss -tnp`（两条 TCP 连接，网关各持一端） | 实验记录见本节 |
| 0.4.4 | Reactor Netty 官方 README（"based on Netty framework"） | https://github.com/reactor/reactor-netty |
| 0.4.4 | Reactor Netty 官方参考文档《HTTP Client》（隐藏 Netty 功能 + 背压） | https://projectreactor.io/docs/netty/release/reference/index.html |
| 0.4.4 | Reactor Netty 官方参考文档（Event Loop Group 在 JVM 内共享） | 同上（TCP/HTTP Client 章节） |
| 0.4.4 | Gateway `NettyRoutingFilter` / `HttpClientFactory`（出站调用实现） | `{gateway}/spring-cloud-gateway-server/src/main/java/org/springframework/cloud/gateway/filter/NettyRoutingFilter.java:32,81,142`；`.../config/HttpClientFactory.java:47,83` |
| 0.4.5 | reactor-netty `ColocatedEventLoopGroup` 构造器（16 线程触发根因） | reactor-netty `v1.1.18`：`reactor-netty-core/.../resources/ColocatedEventLoopGroup.java`；`DefaultLoopResources.cacheNativeClientLoops()` |
| 0.4.5 | JFR `jdk.ThreadStart` 创建栈（线程由谁启动） | 实测：`jcmd <pid> JFR.start` → `jfr print --events jdk.ThreadStart` |
| 0.4.6 | reactor-netty `FutureMono`（Netty Future → Mono 的桥，`addListener`） | reactor-netty `v1.1.18`：`reactor-netty-core/src/main/java/reactor/netty/FutureMono.java` |
| 0.4.8 | boundedElastic 槽位结构 / 引用计数 / evictor | reactor-core `v3.6.5`：`reactor-core/src/main/java/reactor/core/scheduler/BoundedElasticScheduler.java` |
| 0.4.8 | 线程池参数默认值 / `getTask()` 退出守卫 | `openjdk11u`：`src/java.base/share/classes/java/util/concurrent/{ScheduledThreadPoolExecutor,ThreadPoolExecutor}.java` |
| 0.4.8 | `subscribeOn` 使用 `Worker` | reactor-core `v3.6.5`：`.../publisher/MonoSubscribeOn.java:48,55,193` |
| 0.5.1 | `Mono` / `Flux` 的类型与惰性 | reactor-core `v3.6.5`：`.../publisher/{Mono,Flux}.java`；0.3.1 的 `Publisher` 定义 |

> 路径变量：`{tomcat}` = `/data/workspace/source-code/code/spring/tomcat`；`{spring-framework}` = `/data/workspace/source-code/code/spring/spring-framework`；`{netty}` = `/data/workspace/source-code/code/spring/netty`；`{jdk11u}` = `/data/workspace/openjdk11u`；`{gateway}` = `/data/workspace/source-code/code/spring/spring-cloud-gateway`
