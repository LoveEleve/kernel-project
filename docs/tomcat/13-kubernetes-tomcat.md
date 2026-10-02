# 面试题详解 13 | K8s / 云原生下的 Tomcat

> 难度：★★★★☆ ｜ 频率：★★★★★ ｜ 区分度：高（资源感知 + 探针 + 优雅下线对齐，全是现网事故点）
> 关键词：cgroup、MaxRAMPercentage、ActiveProcessorCount、liveness/readiness、terminationGracePeriodSeconds、preStop、SIGTERM、临时目录、OOMKilled

---

## 问题

**Tomcat 跑在容器/K8s 里和裸机有什么不同？JVM 和 Tomcat 参数要怎么改？就绪/存活探针怎么设计？优雅下线怎么和 K8s 对齐？有哪些典型事故坑？**

---

## 30 秒简洁回答

> 四个差异：**①资源感知**——容器有 cgroup 限额，JVM 靠 `UseContainerSupport`（JDK8u191+/10+）自动识别内存，**内存用 `-XX:MaxRAMPercentage` 而不是写死 `-Xmx`**；CPU 限额影响 `availableProcessors()`（GC/并行度），必要时 `-XX:ActiveProcessorCount` 钉住。**②探针**——liveness 只表示"进程该不该重启"（别放慢依赖！）、readiness 表示"能不能接流量"（Boot：`management.endpoint.health.probes.enabled=true` → `/actuator/health/liveness|readiness`，K8s 环境自动开启；`EventPublishingRunListener:104/:110` 在启动完成时翻状态）。**③优雅下线对齐**——`terminationGracePeriodSeconds` 必须 **>** `spring.lifecycle.timeout-per-shutdown-phase`（07 篇），并配 `preStop`（sleep 几秒等流量摘除）+ SIGTERM → Spring 关闭钩子（`SpringApplicationShutdownHook:94`）。**④目录/日志**——内嵌的 docBase 是 `/tmp/tomcat-docbase-*`（06 篇），`readOnlyRootFilesystem` 要挂 `emptyDir` 或用 `server.tomcat.basedir`；日志走 stdout。

---

## 展开回答（高级开发级）

### 1）资源感知：cgroup 怎么影响 JVM 与 Tomcat

| 维度 | 机制 | K8s 建议 |
|---|---|---|
| 内存 | JDK 识别 cgroup 限额（JDK8u191+/10+ 的 `UseContainerSupport`），默认堆 = 限额 1/4 | 用 `-XX:MaxRAMPercentage=60~75`（别写死 `-Xmx`，requests/limits 调整不用改参数）；预留堆外（Metaspace/线程栈/DirectBuffer） |
| CPU | `availableProcessors()` 影响 GC 线程数、ForkJoinPool、并行流；**Tomcat 的 `maxThreads=200` 是固定默认值，不随 CPU 变** | CPU limit 下用 `-XX:ActiveProcessorCount=N` 对齐，避免按宿主机核数算并行度 |
| 内存溢出语义 | Java heap OOM ≠ K8s OOMKilled（后者是 cgroup 杀进程，堆还没满） | `-XX:+ExitOnOutOfMemoryError` 快速失败 + 排查 dump 前先看 `kubectl describe` 的 Reason |

### 2）探针：liveness / readiness / startupProbe 三件套

```
management.endpoint.health.probes.enabled=true   # 或 K8s 环境自动开（CloudPlatform.KUBERNETES 探测）
  → AvailabilityProbesAutoConfiguration（:46）：livenessStateHealthIndicator / readinessStateHealthIndicator
  → /actuator/health/liveness   （LivenessState：CORRECT :34 / BROKEN :39）
  → /actuator/health/readiness  （ReadinessState：ACCEPTING_TRAFFIC :34 / REFUSING_TRAFFIC :39）
状态翻转点：EventPublishingRunListener —— :104 LivenessState.CORRECT、:110 ReadinessState.ACCEPTING_TRAFFIC
```

设计原则：
- **liveness** 只回答"进程是否需要重启"——**绝不要把 DB/Redis/下游检查放进去**（依赖抖动 → 全量重启雪崩）；启动慢用 `startupProbe` 兜住，而不是把 liveness 的 `initialDelaySeconds` 调大；
- **readiness** 放"能不能接流量"（内部初始化、必要的下游可用性）；摘流量靠它，别靠重启；
- 探针路径别用业务接口（避免被鉴权/限流/慢请求影响）。

### 3）优雅下线全链（与 07 篇对齐）

```
kubectl delete pod / 滚动更新
  → ① Pod 变 Terminating：从 Service/Endpoint 摘除（有传播延迟）
  → ② preStop 执行（sleep 5~10s 等摘除生效）
  → ③ SIGTERM → JVM 关闭钩子（SpringApplicationShutdownHook:94）
  → ④ Spring close：WebServerGracefulShutdownLifecycle（07 篇）
       停 accept、等在途请求，最多 spring.lifecycle.timeout-per-shutdown-phase
  → ⑤ 超时/完成 → webServer.stop() → 进程退出
  → ⑥ 到 terminationGracePeriodSeconds 仍未退 → SIGKILL（硬杀）
```

**参数对齐公式**：`terminationGracePeriodSeconds ≥ preStop 时长 + timeout-per-shutdown-phase + 缓冲`。例：preStop 5s + 30s + 5s = **40s**。配错的下场：请求被硬断（502/连接重置），而且日志上看不到"Graceful shutdown complete"。

### 4）K8s 部署参数清单（内嵌 Tomcat 视角）

| 类别 | 配置 | 说明 |
|---|---|---|
| 内存 | `-XX:MaxRAMPercentage=60`、`-XX:+ExitOnOutOfMemoryError` | 对齐 limits.memory |
| CPU | `-XX:ActiveProcessorCount=N`（必要时） | 对齐 limits.cpu |
| 停机 | `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase` + `terminationGracePeriodSeconds` + `preStop` | 见 §3 |
| 目录 | `server.tomcat.basedir=/tmp/...`（只读根文件系统） | docBase/baseDir 默认在 `java.io.tmpdir`（06 篇） |
| 日志 | stdout/stderr（`logging.file.name` 非必需） | 别写容器内文件 |
| 连接 | `keepAliveTimeout` 与 LB/Ingress idle timeout 对齐（09 篇） | 两边不匹配会出现"偶发连接重置" |
| 句柄 | `ulimit -n` / `fs.file-max` | 高连接场景 |

### 5）典型事故坑

| 现象 | 根因 | 修法 |
|---|---|---|
| 滚动更新期间 502/重置 | readiness 摘除延迟 + 无 preStop + grace 太短 | preStop sleep + 对齐 §3 公式 |
| Pod 反复重启 | liveness 放了慢依赖 / 启动慢没 startupProbe | liveness 只做进程级；加 startupProbe |
| OOMKilled 但 Java 堆没满 | 堆外（Metaspace/Direct/栈）超额 | 降 MaxRAMPercentage、限 DirectBuffer、看 cgroup 事件 |
| 启动报 `Read-only file system` | Boot 的 `tomcat-*`/`tomcat-docbase-*` 要写 `/tmp` | `emptyDir` 挂 `/tmp` 或 `server.tomcat.basedir` |
| 时区不对（日志差 8 小时） | 容器默认 UTC | `TZ=Asia/Shanghai` / `-Duser.timezone` |

---

## 进一步回答（架构师层级）

- **探针 + PDB + maxSurge/maxUnavailable**：滚动更新"不掉请求"是三件事的合力（探针准确 + 下线优雅 + 副本策略）；
- **服务网格的隐性问题**：Istio/Envoy 默认 idle timeout（如 1h vs 5s 等）与 Tomcat `keepAliveTimeout` 不一致 → 连接被中间设备先关，客户端偶发重置；把 Tomcat keepalive 调到比网格短；
- **弹性指标**：HPA 用 CPU 往往滞后；09 篇的 `tomcat.threads.busy` 更能反映饱和（BusyThreads 打满 → 扩容），这也是"内嵌指标要开 mbeanregistry"的价值；
- **镜像与启动**：分层缓存依赖、`jlink`/`AppCDS` 加速启动（可选）；健康检查用 actuator 而不是 curl 业务接口；
- **与 11 篇联动**：虚拟线程模式下容器 CPU 限额的意义变化（pin 吃满载体 → CPU 打满），扩容信号要看 JFR pin 事件与载体利用率。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"同一套 Tomcat，换了运行环境后哪些假设失效"——资源、探针、信号、目录四件事；
- **回答顺序**：资源感知（内存/CPU）→ 探针设计 → 优雅下线公式 → 坑清单；
- **一句话收束**："容器里 JVM 要认 cgroup（MaxRAMPercentage/ActiveProcessorCount），探针要各司其职，下线要三段对齐（preStop + graceful timeout + grace period）——不然事故全在滚动更新时发生。"

## 必记关键词

`UseContainerSupport` ｜ `MaxRAMPercentage` ｜ `ActiveProcessorCount` ｜ `liveness/readiness/startupProbe` ｜ `AvailabilityProbesAutoConfiguration` ｜ `EventPublishingRunListener` ｜ `terminationGracePeriodSeconds` ｜ `preStop` ｜ `SIGTERM` ｜ `OOMKilled` ｜ `emptyDir`

## 必须明确的边界

- `maxThreads=200` **不随 CPU 限额变化**（它是 Tomcat 默认，不是按核数算）；
- liveness **不能**放慢依赖（重启风暴）；
- `terminationGracePeriodSeconds` 是**最后兜底**（超过即 SIGKILL），不是优雅停机时长本身；
- 容器 OOMKilled 与 Java `OutOfMemoryError` 是两回事；
- 只读根文件系统下内嵌 Tomcat 需要可写的 `/tmp`（docBase/baseDir）。

## 常见错误

- 写死 `-Xmx` 不考虑 limits 调整；
- liveness 探业务依赖；
- 没有 preStop、grace period 配 30s 但 graceful 超时也 30s（对撞）；
- 把 OOMKilled 当 Java 内存溢出排查；
- 忽略 keepalive 与 LB idle timeout 的对齐。

## 自测要求

- **30 秒**：资源/探针/下线/目录四点各一句；
- **3 分钟**：能画优雅下线全链与参数对齐公式，能列 5 个坑；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：容器里为什么建议 `-XX:MaxRAMPercentage` 而不是 `-Xmx`？**
> 堆按 limits 的百分比自动算，扩缩容不用改参数；JDK8u191+/10+ 起 JVM 自动识别 cgroup 限额。写死 `-Xmx` 容易出现"limit 调小但 Xmx 没改 → OOMKilled"或"调大却浪费"。

**追问 2：liveness 和 readiness 怎么分工？**
> liveness 只判断"进程死没死"（重启决策），放进程级状态；readiness 判断"能不能接流量"（摘流量决策），可含必要依赖。Boot 用 `/actuator/health/liveness|readiness`（`AvailabilityProbesAutoConfiguration`，启动完成时由 `EventPublishingRunListener:104/:110` 翻状态）；慢启动用 startupProbe。

**追问 3：滚动更新为什么还会 502？怎么根治？**
> 三段没对齐：Endpoint 摘除有延迟（要 preStop sleep）、graceful 等待时长要小于 `terminationGracePeriodSeconds`（否则 SIGKILL 硬杀）、连接层 keepalive 与 LB idle 要对齐。公式：`terminationGracePeriodSeconds ≥ preStop + shutdown-phase-timeout + 缓冲`。

---

## 示例与使用说明（lab 复现，`xhs-demo/tomcat-lab`）

```yaml
# Deployment 关键片段（内嵌 Tomcat 视角）
spec:
  terminationGracePeriodSeconds: 40        # ≥ preStop(5s) + shutdown 超时(30s) + 缓冲
  containers:
  - name: app
    env:
    - { name: JAVA_TOOL_OPTIONS, value: "-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=2" }
    - { name: TZ, value: "Asia/Shanghai" }
    lifecycle:
      preStop: { exec: { command: ["sh","-c","sleep 5"] } }
    readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8080 }, periodSeconds: 5 }
    livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8080 }, periodSeconds: 10 }
    startupProbe:   { httpGet: { path: /actuator/health/readiness, port: 8080 }, failureThreshold: 30 }
```

lab 实测（2026-09-24）：见 `tomcat-lab/README.md`「实验七」——`/jvm-info` 对比 JVM 参数、docBase 临时目录断言、shutdown 超时硬断对照。

## 面试官评分点

**通过标准**
- 资源感知/探针/优雅下线/目录四点齐全；
- 知道 liveness 不放依赖、grace period 是兜底；
- 能给出参数对齐公式。

**加分项**
- 能带行号讲 Boot 探针（`AvailabilityProbesAutoConfiguration:46`、`EventPublishingRunListener:104/:110`）；
- 能讲 OOMKilled vs Java OOM、keepalive 与网格 idle 对齐；
- 有真实滚动更新排障经验（502/硬断）。

**危险信号**
- 写死 Xmx、liveness 探依赖、无 preStop、把 OOMKilled 当堆问题。

## 实战练习

1. 跑 `KubernetesLabTest`（docBase 临时目录 + shutdown 超时硬断）；
2. 跑 `scripts/k8s-jvm-demo.sh`，对比三组 JVM 参数下的 `/jvm-info`；
3. 给自己项目写一份 §4 的参数清单（含对齐公式）。

## 版本与来源

- Spring Boot **3.5.16**：`actuate/autoconfigure/availability/AvailabilityProbesAutoConfiguration.java`（:46/:75/:91）、`context/event/EventPublishingRunListener.java`（:104/:110）、`availability/{ReadinessState,LivenessState}.java`（:34/:39）、`SpringApplicationShutdownHook.java`（:48/:94）、`web/embedded/tomcat/TomcatServletWebServerFactory.java`（docBase/baseDir :201-202/:245-246）、`autoconfigure/web/ServerProperties.java`（basedir :401）；
- Tomcat **10.1.34**：容器化相关行为复用 02/06/07/09 篇结论（`AbstractEndpoint:794` maxThreads 默认 200、`TomcatWebServer:117-122/:236`）；
- K8s/JVM 容器感知：`UseContainerSupport`/`MaxRAMPercentage`/`ActiveProcessorCount` 为 JVM 规范能力（JDK 源码未收录，lab 脚本实测验证）；
- lab：`xhs-demo/tomcat-lab`（`/jvm-info` + `KubernetesLabTest` + `scripts/k8s-jvm-demo.sh`）；
- 本卷章节：16（生产性能）、17（可观测性）；
- 规划：`Tomcat源码学习范围规划.md` T-1/T-2。

## 个人经历填充区（留空，按真实项目补）

- 滚动更新掉过请求吗？最后怎么对齐的（preStop/grace/探针）？
- 你们的探针策略是什么（liveness/readiness/startup）？

## 真实性说明

技术结论与源码引用可复现（Boot 3.5.16 + Tomcat 10.1.34）；lab 实测为 2026-09-24 本机运行（Boot 3.2.5 / JDK17，用 JVM 参数模拟 K8s 限制）；K8s/YAML 为部署最佳实践示例（本机无 K8s 集群，参数对齐公式来自 07 篇的机制推演）；项目经历请用真实案例填充。
