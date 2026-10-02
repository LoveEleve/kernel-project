# tomcat-lab · 内嵌 Tomcat 实验室

> 目的：把 Tomcat 面试篇（同仓库 `../docs/tomcat/`，01~14）里的机制，拆成**能跑、能改、能观察**的最小复现代码。
> 版本：Java 17 / Spring Boot 3.2.5（与 xhs-demo / xhs-project 对齐）。

## 版本注意（先看这个）

- 本模块用 **Boot 3.2.5**：`server.shutdown` 默认是 **immediate**（3.2.5 jar 元数据 `defaultValue=immediate`），所以 `application.properties` 里**显式**写了 `server.shutdown=graceful`；
- 面试篇正文（07 篇）以 **Boot 3.5.16** 源码为准：默认已是 **graceful**（`ServerProperties:116`）。
- 两个口径都要知道——面试回答先说版本、再说默认值。
- 本模块实际内嵌 **Tomcat 10.1.20**；面试篇源码行号以 **10.1.34** 为准——07 篇涉及的机制（pause / closeServerSocketGraceful / 两个计数）两版一致。

## 目录

```
tomcat-lab/
├── src/main/java/com/myxhs/demo/tomcat/
│   ├── TomcatLabApplication.java      # 入口（默认端口 18080）
│   └── web/SlowController.java        # /slow /async /sse /inflight 四个实验端点
├── src/main/resources/
│   ├── application.properties         # graceful + timeout-per-shutdown-phase=30s
│   └── application-immediate.properties
├── src/test/java/com/myxhs/demo/tomcat/
│   └── GracefulShutdownTest.java      # 三个确定性断言：graceful(同步/异步) vs immediate
└── scripts/graceful-shutdown-demo.sh  # 一键复现（打包→启动→慢请求→SIGTERM→耗时+日志）
```

## 实验一：优雅停机（对应 07 篇）

### 一键脚本

```bash
cd /data/workspace/source-code/xhs-demo
tomcat-lab/scripts/graceful-shutdown-demo.sh graceful 10
tomcat-lab/scripts/graceful-shutdown-demo.sh immediate 10
```

预期（graceful）：
- 慢请求 `curl` **正常返回** `done tag=graceful ...`；
- 停机耗时 ≈ 10s（`context.close()` 等在途请求跑完）；
- 日志出现 `Commencing graceful shutdown. Waiting for active requests to complete` + `Graceful shutdown complete`。

预期（immediate）：
- 停机耗时 < 1s；
- 在途请求**没有响应体**（连接被硬断）；
- 日志无 "Commencing graceful shutdown"。

### 手动复现（不用脚本）

```bash
cd /data/workspace/source-code/xhs-demo
mvn -pl tomcat-lab spring-boot:run                      # 终端 A（默认 graceful）
curl "http://localhost:18080/slow?seconds=10&tag=manual" # 终端 B：发起 10s 慢请求
curl http://localhost:18080/inflight                    # 终端 B：确认 inFlight=1
kill -TERM $(pgrep -f 'tomcat-lab.*spring-boot')        # 终端 C：SIGTERM（或终端 A 按 Ctrl+C）
```

### 自动化测试

```bash
cd /data/workspace/source-code/xhs-demo
JAVA_HOME=/env/javaEnv/jdk17 mvn -pl tomcat-lab test
```

三个测试的断言（结果确定，不靠日志）：

| 测试 | 断言 |
|---|---|
| `gracefulShutdownWaitsForInFlightSyncRequest` | 3s 同步请求在途时 `close()` 阻塞 ≥ 2s，请求返回 200 |
| `gracefulShutdownWaitsForAsyncRequest` | 3s 异步请求（Callable）在途时 `close()` 阻塞 ≥ 2s，请求返回 200（验证 `inProgressAsyncCount` 计数） |
| `immediateShutdownDoesNotWait` | `close()` < 1s 返回；在途请求结局不保证（打印实际结果） |

### 实测输出（2026-09-23，JDK17 本机）

```text
[lab] graceful(sync)  closeMillis=2988  body=done tag=graceful syncInFlight=1 elapsedMs=3000 thread=http-nio-auto-2-exec-1
[lab] graceful(async) closeMillis=3017  body=async done elapsedMs=3009 thread=task-1
[lab] immediate        closeMillis=45    在途请求被中断: ExecutionException: java.io.IOException: HTTP/1.1 header parser received no bytes
服务端日志（immediate）：ERROR ... Servlet.service() ... threw exception [Request processing failed: java.lang.InterruptedException: sleep interrupted]
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

结论：

- **graceful**：`close()` 阻塞 ≈ 请求剩余时长（同步/异步都被等到），请求正常 200 返回；
- **immediate**：`close()` 45ms 返回；在途 worker 被 `shutdownNow` **中断**（源码链：`NioEndpoint.stopInternal:316` → `AbstractEndpoint.shutdownExecutor:1105` `tpe.shutdownNow()`；`StandardThreadExecutor.stopInternal:137`），客户端连接被硬断。

### 一键脚本实测（2026-09-23，慢请求 5s）

```text
########## graceful ##########
   /inflight: inFlight=1 asyncInFlight=0
   停机耗时: 4970 ms
done tag=graceful syncInFlight=1 elapsedMs=5000 thread=http-nio-18080-exec-2
   Commencing graceful shutdown. Waiting for active requests to complete
   Graceful shutdown complete

########## immediate ##########
   /inflight: inFlight=1 asyncInFlight=0
   停机耗时: 103 ms
(无响应体：连接被硬断)
(无优雅停机日志——immediate 模式属正常)
```

### 踩坑记录（lab 内已修复，面试可当故事讲）

- `SpringApplicationBuilder.properties(...)` 是 **default properties（最低优先级）**，会被 classpath 的 `application.properties` 覆盖——所以测试里改用命令行参数（最高优先级）。第一次跑 immediate 测试时实际执行了 graceful（等了 10s）才暴露出来；
- 本机环境设了 `http_proxy` 且没有 `no_proxy` 时，`curl` 会把 `localhost` 请求也发给代理（表现为"请求永远到不了服务端、脚本挂起"）——脚本已 `export no_proxy=localhost,127.0.0.1` + `curl --noproxy '*'`，慢请求单独放宽 `--max-time`。

### 代码与源码的对应关系

| 代码 | Tomcat/Boot 源码对应 |
|---|---|
| `/slow` 的 `Thread.sleep` | 占住 worker 线程 → `StandardWrapper.getCountAllocated()` > 0（allocate :599 / deallocate :606-607） |
| `/async` 的 `Callable` | 异步 Servlet → `StandardContext.getInProgressAsyncCount()` > 0 |
| `/inflight` | 实验辅助：确定性确认"请求已进入服务端" |
| `server.shutdown=graceful` | `TomcatWebServer:106` → `new GracefulShutdown(tomcat)` |
| `timeout-per-shutdown-phase=15s`（测试内） | `LifecycleProperties:36` → `LifecycleAutoConfiguration:44` |
| 停机时日志 | `GracefulShutdown:53`（Commencing...）/ `:113`（isActive 判定） |

> **为什么 async 用例验证的是 `inProgressAsyncCount` 而不是 `countAllocated`**：`StandardWrapperValve` 的 `finally :212-222` **无条件** deallocate（容器线程一退出，`countAllocated` 就回到 0）；异步等待期的计数由 `AsyncContextCallback` 的语义维护——"进入非 DISPATCHED 态 +1、回到 DISPATCHED 态 -1"（`AsyncContextImpl.setStarted:309`、`AsyncStateMachine:286/:294/:412`）。所以异步等待期 `countAllocated==0`、`inProgressAsyncCount==1`，能拦住停机的只可能是后者。

## 实验二：异步 Servlet（对应 08 篇）

### 端点

| 端点 | 演示 |
|---|---|
| `/async?seconds=N` | Spring `Callable` 异步（07 篇已用于停机计数） |
| `/deferred-timeout?timeoutMs=N` | `DeferredResult` + `timeoutResult`：超时走兜底结果（200） |
| `/deferred-ok?delayMs=N` | `DeferredResult` 正常完成（对照） |
| `/raw-async?mode=ok` | 原生 `AsyncContext`：worker 线程 complete（200）。默认 `timeoutMs=5000&workMs=500` |
| `/raw-async?mode=timeout&timeoutMs=500&workMs=5000` | 超时**无人处理** → 容器兜底错误页（500） |
| `/raw-async?mode=timeout-handled&timeoutMs=500&workMs=5000` | `onTimeout` 里 complete → 200 |
| `/sse?seconds=N` | SSE 长连接（会拖住优雅停机） |

### 自动化测试

```bash
cd /data/workspace/source-code/xhs-demo
JAVA_HOME=/env/javaEnv/jdk17 mvn -pl tomcat-lab test -Dtest=AsyncServletTest
```

### 实测输出（2026-09-23，`spring.mvc.async.request-timeout=1s`）

```text
deferred-timeout（timeoutMs=1000）        → 200 "deferred timeout fallback"  elapsed≈2004ms
callable-timeout（/async 5s，1s 超时）     → 500（Boot 错误页）                elapsed≈2011ms
raw-async(ok)                            → 200 "raw done after 300ms"
raw-async(timeout 500ms 不接管)           → 500（错误页）                     elapsed≈1031ms
raw-async(timeout 500ms + listener 接管)  → 200 "timeout handled by listener" elapsed≈980ms
worker 在容器接管后收尾 → IllegalStateException（AsyncContext already completed）
Tests run: 5, Failures: 0
```

结论与源码对应：

- **超时不是精确定时器**：`AbstractProtocol.startAsyncTimeout:655-670` 每秒轮询一次（`scheduleAtFixedRate(…,1,1,SECONDS)`）→ 实际触发 ≈ 配置值 + 1s。预热后 9 次探针实测（timeout=1000/2000/3000ms 各 3 次）：偏差 996~1001ms；
- **未处理超时/异常 = 容器兜底 500**：`AsyncContextImpl.setErrorState:396-465`（错误页，SRV.2.3.3.3）；listener 在 `onTimeout` 里 complete/dispatch 才能接管（500→200）；
- **`AsyncRequestTimeoutException` 状态码是 503**（`:44-46`），但默认 `DefaultHandlerExceptionResolver:498-502` 返回 null → 裸用是 **500**；配 `ResponseEntityExceptionHandler`（@ControllerAdvice）才是 503；
- Spring 落点：`WebAsyncManager.startAsyncProcessing:485` → `StandardServletAsyncWebRequest.startAsync():496`；超时配置 `spring.mvc.async.request-timeout`（`WebMvcProperties.Async:192-205` → `WebMvcAutoConfiguration:239-242`）。

## 实验三：调参与故障排查（对应 09 篇）

### 自动化测试（`TuningTest`，3 用例）

```bash
cd /data/workspace/source-code/xhs-demo
JAVA_HOME=/env/javaEnv/jdk17 mvn -pl tomcat-lab test -Dtest=TuningTest
```

| 测试 | 配置 | 实测 |
|---|---|---|
| `threadsMaxLimitsConcurrency` | `threads.max=1, min-spare=1` | A=1001ms；B=1811ms（同一 `http-nio-auto-1-exec-1` 串行，证明排队） |
| `maxConnectionsThrottlesNewConnections` | `max-connections=1` + 裸 Socket（`Connection: close`） | A=2005ms；B=1707ms（B 等 A 的连接销毁才被 Acceptor 放行） |
| `busyThreadsMetricReflectsLoad` | `mbeanregistry=true` + actuator | 在途 `tomcat.threads.busy[name:http-nio-auto-3]=4.0`（3 慢请求+指标请求自身）；结束=1.0 |

**三个踩坑（都已修进代码，面试可讲）**：

1. `threads.max` 必须和 `threads.min-spare` 一起调小——否则 core(10) > max 直接建池失败；
2. `max-connections` 实验必须禁用 keep-alive：JDK HttpClient 禁止设置 `Connection` 头 → 改用**裸 Socket**；且 warm-up 也得"用完即关"，否则空闲连接占满唯一名额；
3. **指标要按 `name` 标签过滤到本实例**：同 JVM 里多个 Tomcat 实例的 MBean 会一起聚合，且**已停止实例的 `currentThreadsBusy` 返回 -2**（`AbstractEndpoint:1058-1072`）→ 不过滤会看到负数聚合值；端口 0 时连接器名是 `http-nio-auto-N`（从 `/slow` 响应线程名反推）。

### 脚本：CLOSE_WAIT 复现

```bash
tomcat-lab/scripts/close-wait-demo.sh 8 1    # 慢请求 8s，客户端 1s 超时断开
```

实测输出（2026-09-24）：

```text
== 4) 此刻服务端状态（慢请求还在跑，连接应是 CLOSE_WAIT）==
CLOSE-WAIT 1  0  [::1]:18080  [::1]:59196    数量: 1
== 5) 等 8s 慢请求结束（Tomcat 写响应失败 → 关连接）==
数量: 0
```

### 源码对应

| 现象 | 源码 |
|---|---|
| 连接闸门 | `LimitLatch`（AQS）；`Acceptor:115-116`（accept 前 `countUpOrAwait`，满则阻塞）；`NioEndpoint.destroySocket:497-499`（`countDown`） |
| backlog | `NioEndpoint.bind :217/:239`（`serverSock.bind(sa, getAcceptCount())`） |
| 线程池 | `AbstractEndpoint.createExecutor :1084-1095`（线程名 `-exec-`；默认 max 200 / minSpare 10 / 队列无界） |
| connectionTimeout 双默认 | 底层 `SocketProperties.soTimeout=20000 :151`，HTTP 构造时覆盖为 `Constants:28`=60000（`AbstractHttp11Protocol:66`） |
| 指标 | `server.tomcat.mbeanregistry.enabled=true`（Boot 默认关：`TomcatServletWebServerFactory:158`）；`tomcat.threads.busy`（Micrometer `TomcatMetrics:152`） |

### 常用排查命令

```bash
curl -s 'http://localhost:18080/actuator/metrics/tomcat.threads.busy?tag=name:http-nio-18080'
ss -tan state close-wait sport = :18080
jstack <pid> | grep -A 5 'http-nio-18080-exec'
```

## 实验四：替换与定制（对应 10 篇）

### 自动化测试（`CustomizationTest`，4 用例）

```bash
cd /data/workspace/source-code/xhs-demo
JAVA_HOME=/env/javaEnv/jdk17 mvn -pl tomcat-lab test -Dtest=CustomizationTest
```

| 测试 | 定制点 | 实测（2026-09-24） |
|---|---|---|
| `customValveAddsHeader` | `WebServerFactoryCustomizer` + 自定义 Valve（引擎级） | `X-Lab-Valve=hit` |
| `connectorCustomizerSetsServerHeader` | `TomcatConnectorCustomizer` → `connector.setProperty("server", ...)` | `Server=xhs-lab/1.0` |
| `propertyDrivenKeepAliveDisablesConnection` | `--server.tomcat.max-keep-alive-requests=1` + 裸 Socket | 原始响应含 `Connection: close` |
| `remoteIpValveHonorsXForwardedFor` | `--server.forward-headers-strategy=native` | `remoteAddr=1.2.3.4`（XFF 还原；且 XFF 头被 Valve 消费掉） |

### 实测挖出的机制坑（面试可讲）

- **自定义 `TomcatProtocolHandlerCustomizer` 改 `maxKeepAliveRequests` 会被 Boot 覆盖回 100**：Boot 属性驱动的 customizer 由 `WebServerFactoryCustomizerBeanPostProcessor:55/:68` 在 factory 初始化前**后注册**（`TomcatWebServerFactoryCustomizer:144-145` 映射该属性），把用户先注册的同类 customizer 覆盖。要改这类属性：用属性本身，或自定义 `ServletWebServerFactory` Bean，或选 Boot 不设置的定制点（如 `server` 头）；
- 看 `Connection` 这类**逐跳头必须用裸 Socket**（JDK HttpClient 会藏掉）。

### 源码对应

| 定制点 | 源码 |
|---|---|
| Customizer 收集 | `ServletWebServerFactoryConfiguration.EmbeddedTomcat:63-79`（三类 customizer `:75-77`，`orderedStream` 支持 @Order） |
| ProtocolHandler customizer 应用 | `TomcatServletWebServerFactory:349-352/:376-378`（LambdaSafe 泛型匹配） |
| Valve | `ValveBase:37` + `StandardPipeline.addValve:268`；引擎级用 `addEngineValves:588` |
| RemoteIpValve 自动装配 | `TomcatWebServerFactoryCustomizer.customizeRemoteIpValve:230-256`（`getOrDeduceUseForwardHeaders:258-265`） |
| 换容器 SPI | `ServletWebServerFactoryConfiguration` 的 Tomcat/Jetty/Undertow 条件（`:64/:86/:104`） |

## 实验五：虚拟线程（对应 11 篇，**需 JDK21**）

### 自动化测试（`VirtualThreadsTest`，2 用例；JDK17 下自动 Skip）

```bash
cd /data/workspace/source-code/xhs-demo
JAVA_HOME=/env/javaEnv/jdk21 mvn -pl tomcat-lab test -Dtest=VirtualThreadsTest
```

| 测试 | 配置 | 实测（2026-09-24，JDK21） |
|---|---|---|
| `platformThreadsStillSerializeWithSameConfig` | `threads.max=1`（不开虚拟线程） | B=**1818ms**，`thread=http-nio-auto-1-exec-1`（被唯一线程串行） |
| `virtualThreadsIgnoreMaxThreads` | `threads.max=1` + `--spring.threads.virtual.enabled=true` | B=**1009ms**，A/B 同时在跑（`syncInFlight=2`），`thread=tomcat-handler-1/2` |

### 源码对应

- Boot 注册链：`Threading:42/:49`（`spring.threads.virtual.enabled`）→ `EmbeddedWebServerFactoryCustomizerAutoConfiguration:73-74`（`@ConditionalOnThreading(VIRTUAL)`）→ `TomcatVirtualThreadsWebServerFactoryCustomizer:37-39`（`setExecutor(new VirtualThreadExecutor("tomcat-handler-"))`）/ `:43-44`（order+1 覆盖）；
- Tomcat：`AbstractEndpoint.useVirtualThreads:598`、`createExecutor:1084-1095`（`-virt-` 命名分支）、`VirtualThreadExecutor:42-43/:47-52`（每次 execute 起新虚拟线程）、`Jre21Compat.createVirtualThreadBuilder:72-77`（反射 `Thread.ofVirtual().name(prefix, 0)`）。

### 边界（复试必背）

- JDK21+ 才有；开启后 `maxThreads`/`min-spare` **失效**，`maxConnections`/`acceptCount` 仍生效；
- Acceptor/Poller 仍是平台线程；
- **不要池化虚拟线程**；JDK21~23 的 `synchronized` 会 **pin**（JDK24 起修复）。

## 实验六：线上排查实战（对应 12 篇）

### 端点（`DiagnosticsController`）

| 端点 | 模拟的故障 |
|---|---|
| `/spin?ms=N` | CPU 型：worker 忙循环（jstack/栈采样能定位到本方法） |
| `/leak?mb=N` / `/leak/status` / `/leak/clear` | 内存型：静态 `List<byte[]>` 持有（MAT 的"静态集合"常客） |
| `/deadlock/a` + `/deadlock/b` | hang 型：两个端点反序抢锁 → 真死锁 |

### 自动化测试（`DiagnosticsTest`，3 用例；用代码演一遍 jstack/MAT）

```bash
cd /data/workspace/source-code/xhs-demo
JAVA_HOME=/env/javaEnv/jdk17 mvn -pl tomcat-lab test -Dtest=DiagnosticsTest
```

实测（2026-09-24）：

```text
busyThreadStackRevealsHotMethod → 热点线程 http-nio-auto-1-exec-1
                                  at DiagnosticsController.spin(DiagnosticsController.java:43)
deadlockDetectedByThreadMxBean → Found 2 Java-level deadlock(s)
                                  exec-1 等 Object@3b7b0b57 / exec-2 等 Object@21a9f95b
staticMapLeakGrows              → leakedMb=3 → clear → leakedMb=0
Tests run: 3, Failures: 0
```

### 踩坑（同时是教学点）

- **死锁现场会拖住优雅停机**：首次运行时 `context.close()` 一直等在途请求，整类耗时 44.76s（30s phase 超时）；测试改用 `--server.shutdown=immediate` 后 16.7s。线上排查重启要有这个预期（正文已把它写成"联动陷阱"）。

### 源码对应

| 现象 | 源码 |
|---|---|
| 内嵌**没有**独立部署的两道防泄漏防线 | 负向断言：Boot 全仓 0 命中 `JreMemoryLeakPreventionListener`（Tomcat 侧在 `catalina/core/JreMemoryLeakPreventionListener:46/:85/:126`；还有 `ThreadLocalLeakPreventionListener:45/:61`，仅独立/reload 语境） |
| Poller 不是空轮询模型 | `NioEndpoint.Poller.run :740-770`（`select(selectorTimeout)` :755 阻塞轮询，无自旋保护逻辑） |
| 线程名地图 | 02 篇（Acceptor/Poller/exec）、07 篇（tomcat-shutdown）、11 篇（tomcat-handler-） |

## 实验七：K8s / 云原生（对应 13 篇）

### 端点与脚本

| 内容 | 说明 |
|---|---|
| `/jvm-info` | 进程级信息：processors / maxMemoryMb / tmpdir / javaVersion |
| `KubernetesLabTest`（3 用例） | docBase 临时目录 / shutdown 超时硬断 / jvm-info 一致性 |
| `scripts/k8s-jvm-demo.sh` | 同一 jar 三组 JVM 参数对比（模拟 K8s 的 limits） |

```bash
cd /data/workspace/source-code/xhs-demo
JAVA_HOME=/env/javaEnv/jdk17 mvn -pl tomcat-lab test -Dtest=KubernetesLabTest
./tomcat-lab/scripts/k8s-jvm-demo.sh
```

### 实测（2026-09-24）

```text
/jvm-info 一致性      → processors=16 maxMemoryMb=3986 tmpdir=/tmp
docBase 临时目录       → java.io.tmpdir 下 tomcat-docbase 目录数 12 → 13（启动即创建）
shutdown-phase=1s     → closeMillis=1182ms；5s 慢请求被硬断（ConnectException）
                        ← K8s 的 terminationGracePeriodSeconds 必须 > shutdown 超时
k8s-jvm-demo.sh       →
  默认                ：processors=16 maxMemoryMb=3986
  MaxRAMPercentage=10 ：processors=16 maxMemoryMb=1596   ← 堆按 10% 物理内存
  ActiveProcessorCount=2：processors=2  maxMemoryMb=3986 ← 对齐 CPU limit
```

### 源码对应

| 主题 | 源码 |
|---|---|
| 探针自动配置 | `AvailabilityProbesAutoConfiguration:46`（`management.endpoint.health.probes.enabled :75`；K8s 自动探测 `:91`） |
| 探针状态翻转 | `EventPublishingRunListener:104`（Liveness CORRECT）/ `:110`（Readiness ACCEPTING_TRAFFIC） |
| SIGTERM → 关闭钩子 | `SpringApplicationShutdownHook:48/:94` |
| docBase 临时目录 | `TomcatServletWebServerFactory:245-246`（06 篇） |

### 踩坑

- 端口残留：早期脚本被中断时可能留下 `java -jar ... --server.port=18080` 孤儿进程（本次排查清掉一个跑了 4h40m 的）；脚本改用前先 `ss -ltn | grep 18080` 确认。

## 实验八：jakarta 迁移（对应 14 篇）

### 自动化测试（`MigrationLabTest`，2 用例）

```bash
cd /data/workspace/source-code/xhs-demo
JAVA_HOME=/env/javaEnv/jdk17 mvn -pl tomcat-lab test -Dtest=MigrationLabTest
```

| 测试 | 演示点 | 实测（2026-09-24） |
|---|---|---|
| `jakartaFilterIsRegisteredAndRuns` | Boot 3 标准写法：`jakarta.servlet.Filter` + `FilterRegistrationBean<Filter>`（javax 写法在 Boot 3 编不过） | 响应头 `X-Lab-Filter=jakarta`，200 |
| `oversizedRequestHeaderRejectedWith400` | 改名属性 `server.max-http-request-header-size`（Boot 2 叫 `server.max-http-header-size`，旧名**静默失效**） | 2KB 头（限 1KB）→ **400**；正常 → 200 |

### 源码对应

| 主题 | 源码 |
|---|---|
| 属性与映射 | `ServerProperties.maxHttpRequestHeaderSize:111`；`TomcatWebServerFactoryCustomizer:107-110/:268-269` |
| Tomcat 执行点 | `Http11Processor:167`（把 `getMaxHttpRequestHeaderSize()` 传给 `Http11InputBuffer`）；`AbstractHttp11Protocol:274/:288`；超限文案 `LocalStrings.properties:61` |

## 附加实验：自定义 Filter（Spring Boot 四种注册方式）

### 代码（main 源，`mvn spring-boot:run` 即可观察）

| 文件 | 作用 |
|---|---|
| `web/RequestTimingFilter` | 继承 `OncePerRequestFilter`：请求前写标记头、请求后记录耗时 |
| `config/FilterConfig` | `FilterRegistrationBean` 注册：URL `/*`、order、dispatcher types |
| `SlowController./lab/filter-last-cost` | 查询最近一次请求耗时 |
| `FilterLabTest` | 断言标记头生效 + 耗时上报 |

### 四种注册方式

| 方式 | 写法 | 特点 |
|---|---|---|
| 1. Spring Bean | Filter 类加 `@Component`（或 `@Bean` 返回 Filter） | Boot 自动注册到 `/*`；顺序用 `@Order`/`Ordered` |
| 2. **`FilterRegistrationBean`（推荐）** | `@Bean FilterRegistrationBean<T>` | 精确控制 URL、顺序、dispatcher types、名称 |
| 3. Servlet 原生注解 | `@WebFilter` + `@ServletComponentScan` | 不走 Spring 注册模型，顺序控制弱 |
| 4. 直接实现 `jakarta.servlet.Filter` | `implements Filter` | 注意 forward/include/async dispatch 会重复执行（用 `OncePerRequestFilter` 规避） |

### 三个坑（本 demo 已实测）

1. **双重注册**：Filter 既是 Spring Bean 又放入 `FilterRegistrationBean` → **执行两次**；只保留一种注册途径；
2. **响应头提交时机**：`chain.doFilter()` 之后的 `setHeader` 常因响应已提交而失效（实测事后设的 `X-Lab-Timing-Ms` 被静默忽略）→ 耗时用日志/指标上报，或包装 Response 缓冲（`ContentCachingResponseWrapper`）；
3. **dispatcher types 默认仅 REQUEST**：需要在 ERROR/FORWARD/ASYNC 转发时生效，须显式声明。

### 实测（2026-09-29）

```text
X-Lab-Timing=on            → 所有端点均生效（调用链前设置的头）
/slow?seconds=1 cost=1017ms → 经 /lab/filter-last-cost 上报
RequestTimingFilter ...（响应已提交=true，耗时通过日志/端点上报）
Tests run: 1, Failures: 0
```

### 源码对应

- `OncePerRequestFilter`（spring-web）、`FilterRegistrationBean`（spring-boot）；
- 链构建与匹配：`ApplicationFilterFactory`（按 DispatcherType + URL + Servlet 名筛选）；`ApplicationFilterChain.internalDoFilter`（逐个执行 Filter，链尾调用 `servlet.service()`）。

## 后续实验（跟着面试篇进度加）

- [x] 08 异步 Servlet：`/async` 状态机、超时（`AsyncContext.setTimeout`）与错误回调
- [x] 09 调参排障：`maxConnections` 打满（LimitLatch）、`acceptCount` 排队、CLOSE_WAIT 复现
- [x] 10 替换/定制：`WebServerFactoryCustomizer` / `TomcatConnectorCustomizer` / 自定义 Valve / RemoteIpValve
- [x] 11 虚拟线程：`VirtualThreadsTest`（JDK21；maxThreads 失效的 A/B 对照）
- [x] 12 线上排查实战：`DiagnosticsTest`（CPU/泄漏/死锁三案例，代码版 jstack/MAT）
- [x] 13 K8s / 云原生：`KubernetesLabTest` + `k8s-jvm-demo.sh`（资源限制/临时目录/停机对齐）
- [x] 14 Boot 2→3 / jakarta 迁移：`MigrationLabTest`（jakarta Filter + 属性改名 400 验证）
- [x] 附加：自定义 Filter（`RequestTimingFilter` + `FilterConfig` + `FilterLabTest`；四种注册方式与提交时机坑）

## 编译/运行环境

- JDK17：`/env/javaEnv/jdk17`（Maven 默认跑在 JDK21 上，编译目标 17 不受影响）
- 端口：18080（避开 demo-provider 8081 / demo-gateway 8080）
