# Lesson 03: 线程模型 — 接收、就绪、执行三段分工

> 课程索引：`../学习清单.md` → Stage 1 · Lesson 03
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`）；内嵌运行版本 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）
> 来源：`webapps/docs/config/http.xml`（官方 Connector 文档）；线程名与行为来自 lab 实测
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L03-5 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 回答三问：① 连接由谁接收；② 请求由谁执行；③ 容量由哪些参数决定、达到上限会怎样 |
| 前置 | L01（组件模型）、L02（启动顺序：Connector 最后启动） |
| 不涉及 | 调参细节（L09）、异步 Servlet（L08）、虚拟线程（L15）、源码（Stage 3） |
| 下一课 | L04 请求处理全链（Valve → Filter → Servlet） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **三类线程分工**：
   - **Acceptor**：只负责接收新连接；
   - **Poller**：只负责检测"哪些连接已就绪"并派发；
   - **Worker**：执行**整条请求链**（解析 → 路由 → Filter → Servlet），"业务"全在这一类线程上。
2. **三个容量参数（官方定义）**：
   - `maxThreads`（默认 200）：请求处理线程的最大数量，决定"能同时处理多少请求"；
   - `maxConnections`（默认 8192）：服务器同时接受并处理的连接上限；
   - `acceptCount`（默认 100）：达到 `maxConnections` 后，操作系统**入站连接队列**的最大长度。
3. **阻塞语义的后果**：Worker 要执行完整请求链，**慢客户端与慢下游都会占住 Worker**——这是"调容量参数、异步 Servlet、虚拟线程"三类手段存在的根本原因。

---

## 2. 官方原文（连接器与线程）

**英文原文**（`webapps/docs/config/http.xml`，以下为节选；省略处已标注）

> 引用规范：逐字摘录（含原文 "(int)" 写法，不改字）。

> **protocol**: The default value is `HTTP/1.1` which uses a Java NIO based connector. To use an explicit protocol, the following values may be used: `org.apache.coyote.http11.Http11NioProtocol` - non blocking Java NIO connector ...

> **acceptorThreadPriority**: The priority of the acceptor thread. The thread used to accept new connections. The default value is `5` ...

> **pollerThreadPriority**: (int)The priority of the poller threads. ...

> **selectorTimeout**: (int)The time in milliseconds to timeout on a select() for the poller. This value is important, since connection clean up is done on the same thread, so do not set this value to an extremely high one. The default value is `1000` milliseconds.

**中文翻译（摘要）**

- 默认协议 `HTTP/1.1`，**基于 Java NIO 的连接器**（显式类名 `Http11NioProtocol`，"non blocking"）；
- 存在 **acceptor thread**（接收新连接的线程）；
- 存在 **poller threads**（默认优先级 5）；
- Poller 以 `selectorTimeout`（默认 **1000ms**）为周期做 `select()`——连接清理也发生在该线程上，所以不要设置过大。

### ① 核心结论

Tomcat 的 NIO 连接器把"网络职责"拆成三件：**接收（Acceptor）→ 就绪检测（Poller）→ 执行（Worker）**；官方文档从线程属性侧面确认了 Acceptor 与 Poller 线程的存在。

### ② 机制说明

| 角色 | 职责 | 明确不做的事 |
|---|---|---|
| Acceptor | 接收新连接（accept） | 不解析请求、不执行业务 |
| Poller | 检测连接是否就绪（读/写事件），派发给 Worker | 不执行业务 |
| Worker | 执行整条请求链：解析 → 路由 → Filter → Servlet → 写响应 | 不做 IO 事件监听 |

> 补充（官方文档未展开，源码口径留 Stage 3）：Tomcat 10.1 默认 **1 个 Acceptor + 1 个 Poller**（`acceptorThreadCount`/`pollerThreadCount` 这两个配置项在当前版本中已不存在）。

### ③ 官方要点（3 条）

1. 默认连接器是 **NIO 实现**（non blocking）；
2. Acceptor / Poller 是**独立线程**（存在优先级属性）；
3. Poller 以 1 秒为周期轮询（`selectorTimeout=1000ms`）——**异步超时的检查周期与此相关（L08 展开）**。

### ④ 术语对照

| 官方英文 | 中文 | 说明 |
|---|---|---|
| acceptor thread | 接收线程 | 只做 accept |
| poller thread | 轮询线程 | 检测就绪事件 |
| selector / select() | 选择器 / 选择操作 | IO 多路复用 |
| selectorTimeout | 选择超时 | 默认 1000ms |
| non blocking | 非阻塞 | NIO 连接器特征 |

### ⑤ 结论

> **NIO 连接器 = Acceptor（收）+ Poller（等）+ Worker（干）；官方参数暴露了前两者，第三者的容量由 `maxThreads` 控制。**

---

## 3. 三类线程与线程名地图（lab 实测）

| 线程名模式 | 角色 | 验证方式 |
|---|---|---|
| `http-nio-8080-Acceptor-0` | 接收连接 | `jstack`/线程列表 |
| `http-nio-8080-Poller-0` | 就绪检测与派发 | `jstack` |
| `http-nio-8080-exec-*` | 执行请求链 | `jstack`；`/slow` 响应里的 `thread=` 字段即它 |

> 内嵌补充：Boot 还会另起一个**非 daemon** 的 `container-N` 线程用于驻留（对应独立形态的 `await()`，L02）。

---

## 4. 一个请求的线程经历（概念级）

```
新连接到达
  → Acceptor 接收（只 accept）
  → Poller 检测到"可读"后就绪 → 派发
  → Worker 接管：解析 → 路由 → Filter → Servlet → 写响应
  → （keep-alive：连接回到 Poller 等待下一次请求）
```

**关键点**：**整条请求链都在同一个 Worker 线程上**（同步语义）。这是 Servlet 规范决定的编程模型；"异步"意味着主动把 Worker 还回去（L08）。

---

## 5. 三个"上限"的行为（官方定义 + 后果）

**英文原文**（`config/http.xml`）

> **maxThreads**: The maximum number of request processing threads ... If not specified, this attribute is set to 200. If an executor is associated with this connector, this attribute is ignored ...

> **maxConnections**: The maximum number of connections that the server will accept and process at any given time. When this number has been reached, the server will **accept, but not process, one further connection**. This additional connection be blocked until the number of connections being processed falls below maxConnections ... The default value is `8192`.

> **acceptCount**: The maximum length of the operating system provided queue for incoming connection requests **when maxConnections has been reached**. ... When this queue is full, the operating system **may actively refuse additional connections or those connections may time out**. The default value is `100`.

| 参数 | 官方一句话 | 达到上限后的行为 | 观察到的现象 |
|---|---|---|---|
| `maxConnections`（8192） | 同时接受并处理的连接上限 | 多出的连接被**卡住**（accept 但不处理，等位置释放） | 新连接"hang 在建立后" |
| `acceptCount`（100） | 达 `maxConnections` 后的 OS 队列长度 | 队列满 → OS **拒绝连接或让其超时**（官方另有提醒：OS 可能忽略该设置、采用不同的队列大小） | 客户端连接被拒/超时 |
| `maxThreads`（200） | 请求处理线程上限 | 请求**排队等待**线程（官方未描述排队细节） | 延迟上涨（默认队列无界，L09 展开） |

> 注意官方对 `maxThreads` 的补充：**若为 Connector 关联了 executor（如虚拟线程），该参数被忽略**——这是 L15 的前置知识。

---

## 6. 与 Netty 的线程模型对照（概念）

| 维度 | Netty | Tomcat（NIO 连接器） |
|---|---|---|
| 线程数量 | 少量 EventLoop（≈CPU 核数 × 2） | 1 Acceptor + 1 Poller + 最多 200 Worker |
| 是否同线程做 IO 与业务 | 是（全异步、事件驱动） | **否**（就绪检测与业务执行分离） |
| 编程语义 | 回调/异步 | **阻塞（Servlet API）** |
| 慢客户端的影响 | 不占死线程（事件驱动） | **占住一个 Worker** |

一句话：Tomcat 用"分离"换来了 Servlet 的同步语义；代价是 Worker 会被 IO 等待占用——容量参数与异步/虚拟线程都是在管理这个代价。

---

## 7. 术语对照（本课汇总）

| 官方英文 | 中文 | 一句话 |
|---|---|---|
| Acceptor | 接收线程 | 只 accept |
| Poller | 轮询线程 | 就绪检测与派发 |
| Worker | 工作线程 | 执行整条请求链 |
| selectorTimeout | 选择超时 | 默认 1000ms 的轮询周期 |
| acceptCount | 接受队列长度 | OS 队列，满则拒绝/超时 |
| maxConnections | 连接上限 | 8192；达限后 accept-but-not-process |
| maxThreads | 处理线程上限 | 200；关联 executor 时被忽略 |
| non blocking | 非阻塞 | NIO 的 IO 模型 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. Acceptor / Poller / Worker 各做什么、不做什么？**
> 参考答案：Acceptor 只接收新连接（不解析、不执行）；Poller 只检测读写就绪并派发（不执行业务）；Worker 执行整条请求链（解析→路由→Filter→Servlet→写响应）。

**Q2. 三个容量参数各是什么"上限"？默认值多少？**
> 参考答案：`maxThreads`=请求处理线程上限（200）；`maxConnections`=同时接受并处理的连接上限（8192）；`acceptCount`=达连接上限后 OS 入站队列长度（100）。

**Q3. 一个请求的整条处理链由哪个线程执行？**
> 参考答案：由 Worker 执行；Acceptor 与 Poller 不参与请求处理。同步语义下整条链在同一 Worker 上。

**Q4. `maxConnections` 达到后，新连接会怎样（用官方口径说）？**
> 参考答案：官方原话是 "accept, but not process, one further connection"——多出的连接被接收但阻塞，直到已处理连接数降到上限之下；超过 `acceptCount` 的部分由 OS 拒绝或超时。

**Q5. 为什么"慢客户端"是 Tomcat 的容量问题？**
> 参考答案：Worker 执行整条请求链且是阻塞语义，慢客户端会让一个 Worker 停在读/写上，等效减少可用处理能力；Netty 的事件驱动模型不存在这个占用（对照 §6）。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：启动 lab 后 `jstack` 观察三类线程名；`curl /slow?seconds=3` 观察响应体里的 `thread=http-nio-...-exec-N`。
- 下一步：**L04 请求处理全链**——请求进入容器后，如何穿过 Valve → Filter → Servlet，以及 Valve 与 Filter 的边界。
