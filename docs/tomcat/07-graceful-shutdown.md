# 面试题详解 07 | 优雅停机（GracefulShutdown）

> 难度：★★★★☆ ｜ 频率：★★★★★ ｜ 区分度：高（链路长、版本差异、生产事故高发区）
> 关键词：GracefulShutdown、SmartLifecycle、shutDownGracefully、connector.pause()、closeServerSocketGraceful、countAllocated、inProgressAsyncCount、timeout-per-shutdown-phase、terminationGracePeriodSeconds

---

## 问题

**Spring Boot 的优雅停机是怎么实现的？`server.shutdown=graceful` 背后发生了什么？Tomcat 怎么做到"停止接收新请求、等完在途请求"？超时了会怎样？为什么 `kill -9` 无效？**

---

## 30 秒简洁回答

> 关停时 Spring 按 `SmartLifecycle` **phase 从高到低**停：先 `WebServerGracefulShutdownLifecycle`（phase = `DEFAULT_PHASE - 1024`）→ 调 `webServer.shutDownGracefully(callback)`；Tomcat 侧（`TomcatWebServer` 内部 `GracefulShutdown`）对每个 Connector 做两步：**① `connector.pause()`**（`AbstractEndpoint.pause`：置 `paused`、`releaseConnectionLatch`、`unlockAccept`——Acceptor 不再 accept 新连接）；**② `protocolHandler.closeServerSocketGraceful()`**（关 server socket、禁 keep-alive、pause handler）→ 然后**轮询** `StandardContext.getInProgressAsyncCount()` 和每个 `StandardWrapper.getCountAllocated()` 直到全为 0（50ms 一次）→ 回调 `IDLE`/`REQUESTS_ACTIVE`；之后 `WebServerStartStopLifecycle`（phase 再低 1024）才 `webServer.stop()` 真正停 Tomcat。等待总时长受 `spring.lifecycle.timeout-per-shutdown-phase`（默认 30s）约束；**`kill -9`/崩溃不触发**（没有关闭钩子执行机会）。

---

## 展开回答（高级开发级）

### 1）触发链路

```
SIGTERM（K8s/脚本正常停） → SpringApplicationShutdownHook（Boot 注册的 JVM 钩子）
  → context.close() → DefaultLifecycleProcessor.onClose()
    → 按 phase 降序停 SmartLifecycle（先 graceful，后 start-stop）
  → destroyBeans()（@PreDestroy / DisposableBean 在优雅停机之后才执行）
```

### 2）两个 WebServer Lifecycle 与 phase（顺序的来源）

`ServletWebServerApplicationContext.createWebServer`（`:195-198`）注册两个单例：

```java
getBeanFactory().registerSingleton("webServerGracefulShutdown",
        new WebServerGracefulShutdownLifecycle(this.webServer));   // :195-196
getBeanFactory().registerSingleton("webServerStartStop",
        new WebServerStartStopLifecycle(this, this.webServer));    // :197-198
```

- `WebServerGracefulShutdownLifecycle`：`SMART_LIFECYCLE_PHASE = DEFAULT_PHASE - 1024`（`:34`）；`stop(Runnable) :60` → `webServer.shutDownGracefully((result) -> callback.run())`（`:62`）；**直接调 `stop()` 会抛 `UnsupportedOperationException`**（`:55-56`，只能走带 callback 的版本）；
- `WebServerStartStopLifecycle.getPhase()` = `SMART_LIFECYCLE_PHASE - 1024`（`:62-64`）→ 比 graceful 低 1024，所以**先 graceful、后 stop**（降序有代码保证：`DefaultLifecycleProcessor.stopBeans` 用 `new TreeMap<>(Comparator.reverseOrder())`，spring-framework `:423`）。

### 3）Tomcat 侧：Boot 的 `GracefulShutdown` 实现（`web/embedded/tomcat/GracefulShutdown.java`）

`TomcatWebServer` 构造（`:102-107`）：`gracefulShutdown = (shutdown == GRACEFUL) ? new GracefulShutdown(tomcat) : null`；`shutDownGracefully :453-458`——`null` 时直接回调 `IMMEDIATE`。

```
shutDownGracefully :53   起线程 "tomcat-shutdown" 执行 doShutdown，并等它完成 pause 动作
doShutdown :65           getConnectors → 每个 close → awaitInactiveOrAborted → 回调
getConnectors :85        Server.findServices() → Service.findConnectors()
close :93                connector.pause(); connector.getProtocolHandler().closeServerSocketGraceful();
awaitInactiveOrAborted :98   Engine→Host→Context 逐层：
                         while (!aborted && isActive(context)) Thread.sleep(50);
isActive :113            StandardContext.getInProgressAsyncCount() > 0
                         || 任一 StandardWrapper.getCountAllocated() > 0
abort :130               置 aborted=true（由 TomcatWebServer.stop :355-356 调用：强制停时不再等）
```

回调结果：`IDLE`（等在途清零）/ `REQUESTS_ACTIVE`（被 abort）。

### 4）Tomcat 底层动作：pause 与 closeServerSocketGraceful

```
Connector.pause() :964-967            → protocolHandler.pause()
AbstractProtocol.pause() :682-688     → endpoint.pause()
AbstractEndpoint.pause() :1412-1419   → paused=true; releaseConnectionLatch(); unlockAccept(); getHandler().pause();

AbstractProtocol.closeServerSocketGraceful() :759-761 → endpoint.closeServerSocketGraceful()
AbstractEndpoint.closeServerSocketGraceful() :1511-1531 → acceptor.stopMillis(-1); releaseConnectionLatch();
                                                        unlockAccept(); getHandler().pause();
                                                        bindState=SOCKET_CLOSED_ON_STOP（源码注释：副作用=禁 keep-alive）
                                                        doCloseServerSocket() 关监听 socket
Acceptor（Acceptor.java:27；AbstractEndpoint 字段 :197/创建 :1398）：
  run() 循环 :75，其中 :89  while (endpoint.isPaused() && !stopCalled) → 暂停期间不再 accept
```

- `releaseConnectionLatch()` 释放 `LimitLatch`（02 篇 `maxConnections` 的等待者），避免停机卡在连接闸门；
- `closeServerSocketGraceful` 关掉监听 socket（新连接直接被拒），但**已建立的连接继续服务**；
- Tomcat 原生也提供 `AbstractProtocol.awaitConnectionsClose(waitMillis)`（`:764-767`），**Boot 没有用它**，而是自己轮询（原因见"进一步"）。

### 5）"在途请求为 0"怎么判定

| 请求类型 | 计数 |
|---|---|
| 同步请求（线程池处理中） | `StandardWrapper.getCountAllocated()`（`:269`） |
| 异步 Servlet / SSE / DeferredResult | `StandardContext.getInProgressAsyncCount()`（`:846`；increment `:835` / decrement `:841`） |

每 50ms 轮询，两类计数全为 0 → `IDLE`；被 `abort()` → `REQUESTS_ACTIVE`。结果枚举：`GracefulShutdownResult`：`REQUESTS_ACTIVE :32 / IDLE :37 / IMMEDIATE :42`。

### 6）超时控制

`spring.lifecycle.timeout-per-shutdown-phase`（默认 **30s**，`LifecycleProperties:36`）→ `LifecycleAutoConfiguration:44` 把它设给 `DefaultLifecycleProcessor`（**Spring Framework 自身默认 10s**：`DefaultLifecycleProcessor:124`，Boot 覆盖为 30s）；**超时后 Spring 打印警告并继续**（不再等），随后 `webServer.stop()` 强制关闭——在途请求被硬断。

### 7）内嵌 vs 独立

| | 独立 Tomcat | 内嵌（Boot） |
|---|---|---|
| 停机入口 | `bin/shutdown.sh` 连 **8005 端口**发 SHUTDOWN（`StandardServer.await()`） | SIGTERM → Spring 关闭钩子 → `context.close()`（**无 8005**，01 篇） |
| 优雅停机 | 需配置/代码调用 Connector pause | `server.shutdown=graceful` 即可（**3.5 默认已是 GRACEFUL**，`ServerProperties:116`） |

---

## 进一步回答（架构师层级）

- **为什么 Boot 自己轮询而不是用 Tomcat 原生 `awaitConnectionsClose`**：Boot 要等的是"**请求处理完**"（含 async/SSE/DeferredResult），而不只是"连接关闭"；并且要支持 `abort`——`TomcatWebServer.stop()` 被调用时（如启动失败清理、强制关）立刻放弃等待（`:355-356`）。
- **版本差异（高频坑）**：Spring Boot **3.5.16 的默认值是 `Shutdown.GRACEFUL`**（`ServerProperties:116`）；老版本默认 `IMMEDIATE`，所以旧教程都教你显式配 `server.shutdown=graceful`。回答时先说版本，再说默认。
- **顺序保证**：graceful 的 phase 比 start-stop 高 1024 → 一定先"停流量+等在途"，再 `webServer.stop()`；而 `@PreDestroy`/`DisposableBean`（含数据源关闭）在 lifecycle 阶段之后执行——**不会出现"请求还在跑、连接池先关了"**（spring-framework `AbstractApplicationContext.doClose`：`:1172` `lifecycleProcessor.onClose()` → `:1178` `destroyBeans()`）。
- **K8s 生产口径**：`terminationGracePeriodSeconds` 必须 **>** `timeout-per-shutdown-phase`，否则 SIGKILL 打断优雅停机；常见做法再加 `preStop` sleep 几秒等 Service/Endpoint 摘除（避免摘除延迟期间的 502）。
- **排障线索**：日志 `Commencing graceful shutdown. Waiting for active requests to complete` / `Graceful shutdown complete` / `Graceful shutdown aborted with one or more requests still active`；停机慢先查长请求、SSE/大文件、`maxConnections` 闸门等待。
- **边界**：`kill -9`、OOM、JVM 崩溃不触发；MQ 消费线程/定时任务不属于 Tomcat 请求，需要应用自己监听 `ContextClosedEvent` 停止。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"关停时怎么把在途流量收干净"——拆成四问：谁触发（SIGTERM/钩子）、怎么停流量（pause + closeServerSocketGraceful）、怎么等在途（两个计数 + 50ms 轮询）、怎么兜底（超时/abort）；
- **回答顺序**：触发链路 → 两个 Lifecycle 的 phase 顺序 → Tomcat 的 pause/close → 计数判定 → 超时与 K8s 口径；
- **一句话收束**："先摘流量（pause+关监听），再轮询等在途清零，超时兜底强停；phase 保证它发生在 `webServer.stop()` 和 Bean 销毁之前。"

## 必记关键词

`GracefulShutdown` ｜ `SmartLifecycle/phase` ｜ `shutDownGracefully` ｜ `connector.pause()` ｜ `closeServerSocketGraceful` ｜ `countAllocated` ｜ `inProgressAsyncCount` ｜ `timeout-per-shutdown-phase` ｜ `GracefulShutdownResult` ｜ `terminationGracePeriodSeconds`

## 必须明确的边界

- 优雅停机**只对正常关闭**（SIGTERM/`context.close()`）有效，`kill -9`/崩溃无效；
- **3.5 默认已是 graceful**（`ServerProperties:116`），别照搬"必须手动开启"的老说法；
- `closeServerSocketGraceful` 只关**监听** socket，已建立连接继续服务到结束；
- `abort()` 会让等待立刻结束（结果 `REQUESTS_ACTIVE`）——强制停路径不会无限等；
- 超时后是**强制继续**（硬断在途），不是无限等待；
- `immediate` 模式下 Tomcat 会 `shutdownNow` **中断在途 worker**（源码：`NioEndpoint.stopInternal:316` → `AbstractEndpoint.shutdownExecutor:1105`；`StandardThreadExecutor.stopInternal:137`；lab 实测：`InterruptedException: sleep interrupted` + 客户端连接被硬断）——graceful 因为先等到计数为 0，worker 自然结束不会被中断；
- MQ/定时任务/其他非 HTTP 线程不在优雅停机范围内。

## 常见错误

- 以为 `server.shutdown=graceful` 能等所有后台线程；
- 把 `kill -9` 也当作"会优雅停机"；
- 说不清 phase 顺序（graceful 先于 start-stop）；
- 以为超时会一直等；
- 不知道判定在途用的是"两个计数"（allocated + async）。

## 自测要求

- **30 秒**：触发链路 + 停流量两步 + 计数轮询 + 超时兜底；
- **3 分钟**：能画出 phase 顺序与 `GracefulShutdown` 的内部调用链（含行号）；
- 能回答追问 1~4。

---

## 追问与参考回答

**追问 1：优雅停机默认开启吗？**
> Spring Boot 3.5.16 源码里 `ServerProperties.shutdown` 默认已是 `GRACEFUL`（`:116`）；老版本默认 `IMMEDIATE`，需要显式配 `server.shutdown=graceful`。`Shutdown.IMMEDIATE` 时 `TomcatWebServer` 的 `gracefulShutdown` 字段为 null，`shutDownGracefully` 直接回调 `IMMEDIATE`（`:453-458`）。

**追问 2：怎么判定在途请求为 0？async/SSE 算不算？**
> 同步请求看 `StandardWrapper.getCountAllocated()`（`:269`），异步/SSE 看 `StandardContext.getInProgressAsyncCount()`（`:846`）；每 50ms 轮询，两者全 0 才算 `IDLE`——SSE 会一直算"在途"，直到连接结束或超时。

**追问 3：超时后会发生什么？**
> `DefaultLifecycleProcessor` 按 `spring.lifecycle.timeout-per-shutdown-phase`（默认 30s）等待每个 phase；超时打印警告并继续 → `webServer.stop()` 强制关闭，在途请求被硬断。所以生产要把编排系统的 grace period 配得比它大。

**追问 4：为什么 `kill -9` 无效？**
> `kill -9` 直接由内核终止进程，JVM 没有机会执行关闭钩子（`SpringApplicationShutdownHook`）→ `context.close()` 不会跑，优雅停机链路完全跳过。

---

## 示例与使用说明

```properties
# Spring Boot 3.5 默认已是 graceful；显式写出来便于阅读
server.shutdown=graceful
# 每个关闭 phase 的等待上限（默认 30s）
spring.lifecycle.timeout-per-shutdown-phase=30s
```

```text
观察日志：
  关闭时（Ctrl+C / SIGTERM / actuator shutdown）：
    Commencing graceful shutdown. Waiting for active requests to complete
    Graceful shutdown complete                       ← 正常
    Graceful shutdown aborted with one or more requests still active   ← 被 abort/超时
K8s 配套：
  terminationGracePeriodSeconds: 45   (> 30s)
```

> lab 复现：`source-code/xhs-demo/tomcat-lab`——`GracefulShutdownTest`（3 用例：同步/异步/立即）+ `scripts/graceful-shutdown-demo.sh`（一键 SIGTERM 对照），见该模块 README「实验一」。

## 面试官评分点

**通过标准**
- 能讲清"先 graceful 后 stop"的 phase 顺序与四问链路；
- 能讲 pause + closeServerSocketGraceful 的作用；
- 能讲两个计数与超时兜底。

**加分项**
- 能带行号讲 `GracefulShutdown:53-130` 与 `AbstractEndpoint:1412-1419/:1511+`；
- 能指出 3.5 默认 GRACEFUL 的版本差异；
- 能讲 K8s `terminationGracePeriodSeconds` 与 preStop 的配合；
- 能区分"等请求"与"等连接"（为什么不用 `awaitConnectionsClose`）。

**危险信号**
- 认为 `kill -9` 也会优雅停机；
- 说不清 phase 顺序；
- 以为超时后无限等；
- 把 MQ/定时任务也算进优雅停机。

## 实战练习

1. 起一个 Boot 应用，写一个 sleep 10s 的接口，`Ctrl+C`（SIGTERM）观察日志与请求是否被服务完；
2. 把 `spring.lifecycle.timeout-per-shutdown-phase=3s`，重复实验，观察超时后请求被硬断；
3. 在接口里返回 SSE/DeferredResult，观察 `inProgressAsyncCount` 对停机等待的影响；
4. `kill -9` 对比：观察请求直接被断、日志无 "Commencing graceful shutdown"。

## 版本与来源

- Spring Boot **3.5.16**：`web/embedded/tomcat/GracefulShutdown.java`（:53/:65/:85/:93/:98/:113/:130）、`web/embedded/tomcat/TomcatWebServer.java`（:106/:355-356/:453-458）、`web/context/WebServerGracefulShutdownLifecycle.java`（:34/:55-56/:60-62）、`web/servlet/context/WebServerStartStopLifecycle.java`（:51/:62-64）、`web/servlet/context/ServletWebServerApplicationContext.java`（:195-198）、`web/server/GracefulShutdownResult.java`（:32/:37/:42）、`web/embedded/tomcat/TomcatServletWebServerFactory.java`（:517）、`autoconfigure/context/{LifecycleProperties,LifecycleAutoConfiguration}.java`（:36、:44）、`autoconfigure/web/ServerProperties.java`（:116）；
- Tomcat **10.1.34**：`connector/Connector.java`（:964-967）、`coyote/AbstractProtocol.java`（:682-688/:759-761/:764-767）、`tomcat/util/net/AbstractEndpoint.java`（:197/:1398/:1412-1419/:1511-1531）、`tomcat/util/net/Acceptor.java`（:27/:75/:89/:183）、`catalina/core/{StandardContext,StandardWrapper}.java`（:835/:841/:846-848、:269-271）；
- Spring Framework（降序/销毁顺序证据）：`AbstractApplicationContext.doClose`（:1172/:1178）、`DefaultLifecycleProcessor`（:124/:423/:606）；
- 本卷章节：09（启动与装配）、02（NIOEndpoint 线程：LimitLatch）、14（Spring Boot 桥：优雅停机）；
- 规划：`Tomcat源码学习范围规划.md` T-1/T-2。

## 个人经历填充区（留空，按真实项目补）

- 生产发布时遇到过"请求被硬断/502"吗？当时 grace period 怎么配的？
- 有没有因为优雅停机等待过久导致发布超时的经历？怎么定位的？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Spring Boot 3.5.16）；示例为操作路径（未附真实运行输出）；项目经历请用真实案例填充。
