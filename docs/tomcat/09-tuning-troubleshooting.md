# 面试题详解 09 | 调参与故障排查（acceptCount / maxConnections / 线程池满 / CLOSE_WAIT）

> 难度：★★★★☆ ｜ 频率：★★★★★ ｜ 区分度：高（两层闸门 + 一个池 + 现场排查手法）
> 关键词：acceptCount、backlog、maxConnections、LimitLatch、maxThreads、maxQueueSize、connectionTimeout、keepAliveTimeout、CLOSE_WAIT、JMX、tomcat.threads.busy、mbeanregistry

---

## 问题

**Tomcat 的性能三件套（`maxThreads`/`maxConnections`/`acceptCount`）分别管什么、默认多少？打满时各是什么现象？CLOSE_WAIT 一大堆怎么排查和定性？内嵌 Spring Boot 怎么配这些参数、怎么看运行时指标（JMX/actuator）？**

---

## 30 秒简洁回答

> 三件套是"**两层闸门 + 一个池**"：`acceptCount`（默认 **100**）是**内核 accept 队列**（`serverSock.bind(sa, acceptCount)`）；`maxConnections`（默认 **8192**）是 Tomcat 的**连接闸门**（`LimitLatch`，`Acceptor` 线程在 accept 前 `countUpOrAwait`，满了就阻塞 Acceptor）；`maxThreads`（默认 **200**）是**业务线程池**。打满现象：maxConnections 满 → Acceptor 停 accept → 连接堆在内核 backlog → 超过 acceptCount 后被拒/超时；maxThreads 满 → 请求进队列（默认 `maxQueueSize=Integer.MAX_VALUE` **无界**）→ 延迟飙升而非报错。CLOSE_WAIT 是**被动关闭方没有 close**：对端 FIN 到了、本端没关——Tomcat 场景常见于"长请求期间客户端超时断开，Tomcat 直到写响应失败才关"，排查用 `ss -tan state close-wait` + JMX `Catalina:type=ThreadPool`（或 actuator `tomcat.threads.busy`，需 `server.tomcat.mbeanregistry.enabled=true`，Boot 默认 **false**）。

---

## 展开回答（高级开发级）

### 1）参数表（Tomcat 10.1 源码口径 + Boot 属性）

| 参数 | 默认 | 源码位置 | 作用点 | Boot 属性 |
|---|---|---|---|---|
| `acceptCount` | 100 | `NioEndpoint.bind`（`:217/:239` `serverSock.bind(sa, getAcceptCount())`） | 内核 accept 队列（backlog） | `server.tomcat.accept-count`（`ServerProperties:463`） |
| `maxConnections` | **8192**（8*1024） | `AbstractEndpoint:548`；`initializeConnectionLatch :1459-1464` | `LimitLatch` 连接闸门 | `server.tomcat.max-connections`（`:457`） |
| `maxThreads` | 200 | `AbstractEndpoint:794`；`createExecutor :1084-1095` | 业务线程池 | `server.tomcat.threads.max`（`:974`） |
| `minSpareThreads` | 10（endpoint 自建池） | `AbstractEndpoint:767` | 池核心线程 | `server.tomcat.threads.min-spare`（`:980`） |
| `maxQueueSize` | `Integer.MAX_VALUE`（无界） | `StandardThreadExecutor:87` | 任务队列 | `server.tomcat.threads.max-queue-capacity`（`:986`） |
| `connectionTimeout` | **60000** | `http11/Constants:28` + `AbstractHttp11Protocol:66`（构造时覆盖底层 `SocketProperties.soTimeout=20000 :151`） | 读请求行/请求体超时 | `server.tomcat.connection-timeout`（`:508`，默认 null） |
| `keepAliveTimeout` | null → 用 connectionTimeout | `AbstractEndpoint:716/:719` | keep-alive 空闲超时 | `server.tomcat.keep-alive-timeout`（`:476`） |
| `maxKeepAliveRequests` | 100 | `AbstractEndpoint:875` | 一条连接最多复用次数 | `server.tomcat.max-keep-alive-requests`（`:483`） |

> **注意一个双默认值坑**：低层 `SocketProperties.soTimeout` 默认 **20000**，但 HTTP 协议处理器构造时显式 `setConnectionTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT)` = **60000**——所以 HTTP Connector 实际默认 60s（AJP 是 -1 不超时）。

### 2）两层闸门 + 一个池：打满的行为链

```
新连接 → 内核 backlog(acceptCount=100) → Acceptor: countUpOrAwait(LimitLatch, maxConnections)
   ↑ 满：backlog 堆积，超了内核直接拒（RST/超时）
   └─ 满：Acceptor 线程阻塞（Acceptor.java:115-116），不再 accept
        → 注册到 Poller → 交给线程池（maxThreads=200, TaskQueue）
             └─ 满：任务排队（默认无界 → 延迟涨；设了上限 → RejectedExecutionException）
```

- `LimitLatch` 是 AQS 共享模式实现（`LimitLatch:37`）：`countUpOrAwait :118`（满则等）/`countDown :129`/`releaseAll :143`；
- 计数成对：**accept 前 +1**（`Acceptor:116`）、**销毁 socket 时 -1**（`NioEndpoint.destroySocket:497-499`）——注意：CLOSE_WAIT 的连接也**仍占** maxConnections 名额，直到 Tomcat 真正 close；
- `releaseAll` 还被 pause/stop 用（07 篇 `releaseConnectionLatch`）——停机时把等在闸门上的线程全部放行。

### 3）线程池满的表现

- **默认无界队列**：maxThreads 打满后请求全部排队 → **吞吐不涨、延迟线性涨**（这是"Tomcat 没报错但越来越慢"的头号原因）；
- 设了 `max-queue-capacity` 后：队列满 → `RejectedExecutionException` → 连接被拒（客户端看到连接重置/5xx）；
- 池的线程名 `http-nio-8080-exec-*`（`TaskThreadFactory(name + "-exec-")`，`AbstractEndpoint:1090`）——`jstack` 一抓就能数忙线程；
- **虚拟线程模式**：`useVirtualThreads`（`AbstractEndpoint:598`；`createExecutor :1086-1087` 建 `VirtualThreadExecutor`）→ maxThreads 不再是瓶颈；Boot 3.5 由 `TomcatVirtualThreadsWebServerFactoryCustomizer:38-39` 在 `spring.threads.virtual.enabled=true` 时把协议处理器执行器换成 `VirtualThreadExecutor("tomcat-handler-")`。

### 4）CLOSE_WAIT：原理、场景、排查、处置

**原理**：TCP 四次挥手，对端发了 FIN → 本端内核回 ACK，进入 `CLOSE_WAIT`，**等本端应用 `close()`**；应用不关，连接就永远挂在那。

**Tomcat 典型场景**：请求还在处理（慢 SQL/慢下游/大响应），客户端等不及超时断开（FIN 到了）→ Tomcat 侧连接进入 CLOSE_WAIT → 直到 Tomcat 写响应失败（EPIPE/ECONNRESET）才关闭。

**排查**：
```bash
ss -tan state close-wait '( sport = :8080 )'      # 看数量与对端
ss -tan state close-wait | wc -l
netstat -ano | grep CLOSE_WAIT                     # 老工具
# 判断：CLOSE_WAIT 大量 → 先看应用是否"处理慢/不关流"；TIME_WAIT 大量 → 是主动关闭方多（短连接压测常见），属正常
```

**处置**：治本 = 缩短处理时间/加超时（`connectionTimeout`/`keepAliveTimeout`）、客户端超时对齐；治标 = 调大 maxConnections（CLOSE_WAIT 也占名额）；`tcp_fin_timeout` **不治** CLOSE_WAIT（它管 TIME_WAIT/孤儿）。

### 5）观测：JMX 与 actuator（内嵌必看）

- **Tomcat MBean**：`Catalina:type=ThreadPool,name="http-nio-8080"` → `currentThreadsBusy/currentThreadCount/maxThreads/connectionCount/maxConnections/acceptCount`；`Catalina:type=GlobalRequestProcessor,name=...` → `requestCount/errorCount/processingTime/maxTime`；
- **内嵌有个大坑**：Boot 默认**关掉 Tomcat MBean 注册**（`TomcatServletWebServerFactory:158` `disableMBeanRegistry=true`；customizer `:62` `setDisableMBeanRegistry(!mbeanregistry.enabled)`）→ 想用 JMX/actuator 指标必须 `server.tomcat.mbeanregistry.enabled=true`；
- **actuator 指标**（micrometer `TomcatMetrics`）：`tomcat.threads.busy :152`、`tomcat.threads.current :159`、`tomcat.threads.config.max :145`、`tomcat.connections.current :166`、`tomcat.connections.keepalive.current :173`、`tomcat.connections.config.max :180`、`tomcat.global.error :240`、`tomcat.global.request :245`、`tomcat.global.request.max :252`。

### 6）Boot 配置映射（customizer 源码）

```
TomcatWebServerFactoryCustomizer：
  maxConnections → AbstractProtocol::setMaxConnections（:136/:208-209）
  acceptCount    → AbstractProtocol::setAcceptCount   （:139/:178-179）
  threads.max    → AbstractProtocol::setMaxThreads    （:164）
  threads.min-spare → setMinSpareThreads              （:169）
  max-queue-capacity → setMaxQueueSize                （:174）
  mbeanregistry.enabled → factory.setDisableMBeanRegistry(!enabled)（:62）
```

---

## 进一步回答（架构师层级）

- **容量规划**：`maxThreads` 不是越大越好（上下文切换/内存）；IO 密集可大（200~500），CPU 密集接近核数；`maxConnections` 可以远大于 `maxThreads`（keep-alive 空闲连接不占线程，只占闸门）；
- **排障手册（症状→看什么→结论）**：
  - 吞吐上不去、延迟线性涨 → `tomcat.threads.busy ≈ max` → 线程池满，查慢下游/慢 SQL；
  - 新连接被拒/握手超时 → `connectionCount ≈ maxConnections` + backlog 满 → 连接闸门满（或 CLOSE_WAIT 泄漏占名额）；
  - 偶发 502/连接重置 → `tomcat.global.error` 突增 + `max-queue-capacity` 满 → 拒绝；
  - `ss` 大量 CLOSE_WAIT → 应用不关连接/处理慢；配合 `jstack` 看卡在哪；
- **内核参数**：`net.core.somaxconn` 要 ≥ `acceptCount`（否则 acceptCount 配大也没用）；容器里注意 `ulimit -n`；
- **和 07/08 联动**：优雅停机等 `countAllocated + inProgressAsyncCount`；SSE 长连接既占连接闸门又拖停机——排查时先看这两个指标。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"请求从内核到线程池要过几道闸门、每道满的现象、以及怎么看见"；
- **回答顺序**：三件套（默认/位置/现象）→ CLOSE_WAIT（原理/场景/命令）→ 观测（JMX + mbeanregistry 坑 + actuator 指标）→ 排障手册；
- **一句话收束**："acceptCount 是内核队列、maxConnections 是 Tomcat 连接闸门、maxThreads 是业务池；排查先看 busy/connectionCount，再看 CLOSE_WAIT 是谁不关。"

## 必记关键词

`acceptCount/backlog` ｜ `maxConnections/LimitLatch` ｜ `maxThreads/maxQueueSize` ｜ `connectionTimeout/keepAliveTimeout` ｜ `CLOSE_WAIT` ｜ `tomcat.threads.busy` ｜ `mbeanregistry` ｜ `ss -tan state close-wait` ｜ `VirtualThreadExecutor`

## 必须明确的边界

- `maxConnections` **不是**并发请求数（keep-alive 空闲连接也算）；`maxThreads` 才是并发处理数；
- 默认队列**无界**——线程池满的默认表现是"慢"不是"错"；
- CLOSE_WAIT 是**应用侧**（本端）不 close；TIME_WAIT 是主动关闭方的正常状态，两者别混；
- Boot 默认**关** Tomcat MBean 注册（`disableMBeanRegistry=true`），不配 `mbeanregistry.enabled=true` 就没有这些指标；
- connectionTimeout 双默认值：底层 20s、HTTP 协议层 60s（构造覆盖）；
- `tcp_fin_timeout` 治不了 CLOSE_WAIT。

## 常见错误

- 把 maxConnections 当"最大并发"；
- 线程池满时以为是 Tomcat bug（其实是队列无界排队）；
- 把 CLOSE_WAIT 归因给 Tomcat/内核；
- 内嵌下找不到 JMX 指标（不知道 mbeanregistry 默认关）；
- 调 acceptCount 却不调 `somaxconn`。

## 自测要求

- **30 秒**：三件套 + 两层闸门图 + CLOSE_WAIT 定性；
- **3 分钟**：能讲每个参数打满的现象与观测手段（含行号）；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：`maxConnections` 和 `maxThreads` 什么关系？**
> 闸门与池：maxConnections（`LimitLatch`，Acceptor 阻塞点 `Acceptor:116`）限制**打开的连接数**，maxThreads 限制**同时处理请求数**；keep-alive 空闲连接占连接不占线程，所以 maxConnections（8192）通常远大于 maxThreads（200）。

**追问 2：CLOSE_WAIT 和 TIME_WAIT 的区别？谁的问题？**
> CLOSE_WAIT = 对端 FIN 到了、**本端还没 close**（应用问题：处理慢/不关流）；TIME_WAIT = **本端主动关闭**后的等待（正常，短连接压测常见）。CLOSE_WAIT 堆积先查应用，TIME_WAIT 堆积调内核/连接复用。

**追问 3：线程池满后请求会怎样？**
> 默认队列无界（`maxQueueSize=Integer.MAX_VALUE`）→ 全部排队，表现为延迟飙升；设了 `max-queue-capacity` 后队列满 → `RejectedExecutionException` → 连接被拒。所以生产要监控 `tomcat.threads.busy` 而不是等报错。

---

## 示例与使用说明（lab 复现，`xhs-demo/tomcat-lab`）

```bash
# 1) 线程池满：maxThreads=1 + 两个并发慢请求 → 第二个排队（约 2 倍耗时）
mvn -pl tomcat-lab test -Dtest='TuningTest#threadsMaxLimitsConcurrency'

# 2) 指标观测：mbeanregistry=true + actuator → 压 3 个慢请求看 busy
mvn -pl tomcat-lab test -Dtest='TuningTest#busyThreadsMetricReflectsLoad'

# 3) 连接闸门：maxConnections=1 → 第二个请求等第一个的连接释放
mvn -pl tomcat-lab test -Dtest='TuningTest#maxConnectionsThrottlesNewConnections'

# 4) CLOSE_WAIT 复现（客户端 1s 超时断开，10s 慢请求）
tomcat-lab/scripts/close-wait-demo.sh
```

实测（2026-09-24，lab）：见 `tomcat-lab/README.md`「实验三」——包含每组实验的实际输出与 `ss` 观察。

## 面试官评分点

**通过标准**
- 三件套默认值与作用层（内核/闸门/池）；
- 打满现象（拒连接 vs 排队慢）；
- CLOSE_WAIT 的定性与命令。

**加分项**
- 能带行号讲 `Acceptor:115-116` / `LimitLatch` / `destroySocket:497-499`；
- 能讲 connectionTimeout 双默认值坑；
- 能讲 Boot `mbeanregistry` 默认关与 actuator 指标名；
- 有真实排障故事（busy 打满/CLOSE_WAIT 定位）。

**危险信号**
- 把 maxConnections 当并发数；
- 线程池满归因 Tomcat；
- 不知道 mbeanregistry 默认关。

## 实战练习

1. 跑上面 4 个实验，记录输出；
2. 用 `jstack` 在 maxThreads=1 实验期间抓线程，找到 `http-nio-18080-exec-1` 的 sleep；
3. 把 `server.tomcat.mbeanregistry.enabled` 去掉，确认 actuator 指标消失（理解默认关）。

## 版本与来源

- Tomcat **10.1.34**：`tomcat/util/net/{NioEndpoint,AbstractEndpoint,Acceptor,SocketProperties}.java`（:217/:239、:548/:767/:794/:875/:1084-1095/:1459-1464、:115-116、:151）、`tomcat/util/threads/LimitLatch.java`（:37/:118/:129/:143）、`catalina/core/StandardThreadExecutor.java`（:62/:67/:87）、`coyote/http11/Constants.java`（:28）、`coyote/http11/AbstractHttp11Protocol.java`（:66）、`coyote/AbstractProtocol.java`（:371-372）；
- Spring Boot **3.5.16**：`ServerProperties.java`（:457/:463/:476/:483/:508/:974/:980/:986/:1052）、`TomcatWebServerFactoryCustomizer.java`（:62/:97/:136/:139/:164/:169/:174/:178-179/:208-209）、`TomcatServletWebServerFactory.java`（:158/:197/:780）、`TomcatVirtualThreadsWebServerFactoryCustomizer.java`（:38-39）；
- Micrometer：`TomcatMetrics.java`（:145/:152/:159/:166/:173/:180/:240/:245/:252）；
- lab：`xhs-demo/tomcat-lab`（`TuningTest` + `scripts/close-wait-demo.sh`）；
- 本卷章节：02（NIOEndpoint 线程）、11（并发与线程池）、16（生产性能）、17（可观测性）；
- 规划：`Tomcat源码学习范围规划.md` T-2/T-4。

## 个人经历填充区（留空，按真实项目补）

- 线上遇到过 maxThreads 打满/CLOSE_WAIT 堆积吗？怎么定位到根因的？
- 容量参数怎么定的（压测口径、机器规格）？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Boot 3.5.16 + Micrometer）；lab 实测数据为 2026-09-24 本机运行（Boot 3.2.5 / 内嵌 Tomcat 10.1.20）；项目经历请用真实案例填充。
