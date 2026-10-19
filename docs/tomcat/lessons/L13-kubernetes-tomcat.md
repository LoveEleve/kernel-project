# Lesson 13: K8s / 云原生下的 Tomcat

> 课程索引：`../学习清单.md` → Stage 4 · Lesson 13
> 版本口径：Tomcat **10.1.34**；Spring Boot **3.5.16**；K8s 侧为部署最佳实践（本机无集群，以 JVM 参数实验等价验证）
> 来源：Boot 探针自动配置（`AvailabilityProbesAutoConfiguration`）与关闭钩子（`SpringApplicationShutdownHook`）；lab 实测（`KubernetesLabTest` / `k8s-jvm-demo.sh`）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L13-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 回答"同一套 Tomcat 搬进容器后，哪些假设失效"：资源感知、探针设计、优雅下线对齐、典型坑 |
| 前置 | L02（启动）、L07（优雅停机）、L09（参数）、L11（虚拟线程口径） |
| 不涉及 | K8s 本身的教程；逐行源码 |
| 下一课 | L14 线上排查（CPU / 内存 / hang） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **资源感知**：容器有 cgroup 限额；JVM 靠 `UseContainerSupport`（JDK8u191+/10+）自动识别内存限额——**内存用 `-XX:MaxRAMPercentage`，别写死 `-Xmx`**（limits 一调就错位）；CPU 限额影响 `availableProcessors()`（GC 线程数、ForkJoinPool 并行度），必要时用 `-XX:ActiveProcessorCount` 对齐；注意 **`maxThreads=200` 是固定默认值、不随 CPU 限额变化**。
2. **探针三分工**：`liveness` 只回答"进程该不该重启"（**绝不能放慢依赖**，否则依赖抖动变全量重启）；`readiness` 回答"能不能接流量"；慢启动用 `startupProbe` 兜住（而不是把 liveness 的 initialDelay 调大）。Boot：`management.endpoint.health.probes.enabled=true` → `/actuator/health/liveness|readiness`（K8s 环境自动开启）；启动完成时状态翻转（liveness→`CORRECT`、readiness→`ACCEPTING_TRAFFIC`）。
3. **优雅下线对齐**（L07 的生产落地）：`terminationGracePeriodSeconds ≥ preStop 时长 + timeout-per-shutdown-phase + 缓冲`（例：5s + 30s + 5s = **40s**）；SIGTERM 触发关闭钩子走完整优雅链；**SIGKILL（`kill -9`）不触发任何钩子**。

---

## 2. 探针机制（Boot 侧，类名口径）

- 自动配置类：`AvailabilityProbesAutoConfiguration`——属性开关 `management.endpoint.health.probes.enabled`；**探测到 K8s 环境（`CloudPlatform.KUBERNETES`）时自动开启**；
- 状态翻转点：启动完成时 `EventPublishingRunListener` 发布事件——liveness → `CORRECT`，readiness → `ACCEPTING_TRAFFIC`（初始为 `REFUSING_TRAFFIC`）；
- 信号入口：`SpringApplicationShutdownHook`（JVM 关闭钩子，SIGTERM 时执行 context.close()）。

**设计原则**：

| 探针 | 回答的问题 | 必须遵守 |
|---|---|---|
| liveness | 进程死没死（重启决策） | 只放进程级状态，**不放慢依赖** |
| readiness | 能不能接流量（摘流量决策） | 可含必要依赖；摘流量靠它 |
| startupProbe | 启动慢不慢（给足启动时间） | 慢启动应用必须配，否则 liveness 误杀 |

---

## 3. 资源感知（概念 + lab 实测）

| 维度 | 机制 | K8s 建议 |
|---|---|---|
| 内存 | JVM 自动识别 cgroup 限额；默认堆 ≈ 限额 1/4 | `-XX:MaxRAMPercentage=60~75`；另加 `-XX:+ExitOnOutOfMemoryError` 快速失败；堆外（Metaspace/线程栈/DirectBuffer）预留空间 |
| CPU | `availableProcessors()` 影响 GC 线程与并行度 | limits.cpu 下用 `-XX:ActiveProcessorCount=N` 对齐 |
| 内存溢出语义 | Java heap OOM `≠` K8s OOMKilled（后者是 cgroup 杀进程，堆还没满） | OOMKilled 先看 `kubectl describe` 的 Reason，再看堆 |

**lab 实测**（`k8s-jvm-demo.sh`，同一 jar 三组参数）：

```text
默认                          ：processors=16 maxMemoryMb=3986
MaxRAMPercentage=10           ：processors=16 maxMemoryMb=1596   ← 堆按 10% 物理内存
ActiveProcessorCount=2        ：processors=2  maxMemoryMb=3986   ← 对齐 CPU limit
```

---

## 4. 优雅下线全链（与 L07 对齐）

```
kubectl delete pod / 滚动更新
  → ① Pod 变 Terminating：从 Service/Endpoint 摘除（有传播延迟）
  → ② preStop 执行（sleep 5~10s，等摘除生效）
  → ③ SIGTERM → JVM 关闭钩子（SpringApplicationShutdownHook）
  → ④ Spring close：WebServerGracefulShutdownLifecycle（L07：停 accept、等在途）
  → ⑤ 超时/完成 → webServer.stop() → 进程退出
  → ⑥ 到 terminationGracePeriodSeconds 仍未退 → SIGKILL（硬杀）
```

**对齐公式**：`terminationGracePeriodSeconds ≥ preStop 时长 + shutdown-phase 超时 + 缓冲`。
配错的下场：请求被硬断（502/连接重置），且日志里看不到 "Graceful shutdown complete"。

**lab 实测**（`KubernetesLabTest`）：`shutdown-phase=1s` + 5s 慢请求在途 → `close()` 约 1182ms 即强制继续，在途请求被硬断（`ConnectException`）——1s 的"超时"直接决定了 K8s 那边 grace period 的下限。

---

## 5. K8s 部署参数清单（内嵌 Tomcat 视角）

| 类别 | 配置 | 说明 |
|---|---|---|
| 内存 | `-XX:MaxRAMPercentage=60`、`-XX:+ExitOnOutOfMemoryError` | 对齐 limits.memory |
| CPU | `-XX:ActiveProcessorCount=N`（必要时） | 对齐 limits.cpu |
| 停机 | `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase` + `terminationGracePeriodSeconds` + `preStop` | 见 §4 |
| 目录 | `server.tomcat.basedir=<emptyDir 挂载的可写目录>`（只读根文件系统） | docBase/baseDir 默认在 `java.io.tmpdir`（lab 断言：启动即创建 `tomcat-docbase-*`） |
| 日志 | stdout/stderr | 别写容器内文件 |
| 连接 | `keepAliveTimeout` 与 LB/Ingress idle timeout 对齐（L09） | 两边不匹配会出现"偶发连接重置" |
| 时区 | `TZ=Asia/Shanghai` / `-Duser.timezone` | 容器默认 UTC，日志差 8 小时 |

---

## 6. 典型事故坑

| 现象 | 根因 | 修法 |
|---|---|---|
| 滚动更新期间 502/重置 | readiness 摘除延迟 + 无 preStop + grace 太短 | preStop sleep + 对齐 §4 公式 |
| Pod 反复重启 | liveness 放了慢依赖 / 启动慢没 startupProbe | liveness 只做进程级；加 startupProbe |
| OOMKilled 但 Java 堆没满 | 堆外（Metaspace/Direct/栈）超额 | 降 MaxRAMPercentage、限 DirectBuffer、看 cgroup 事件 |
| 启动报 `Read-only file system` | Boot 的 `tomcat-*`/`tomcat-docbase-*` 要写 `/tmp` | `emptyDir` 挂 `/tmp` 或 `server.tomcat.basedir` |
| 时区不对（日志差 8 小时） | 容器默认 UTC | `TZ` / `-Duser.timezone` |

---

## 7. 术语对照（本课汇总）

| 术语 | 中文 | 一句话 |
|---|---|---|
| cgroup | 控制组 | 容器的资源限制来源 |
| UseContainerSupport | 容器支持 | JVM 自动识别 cgroup 限额 |
| MaxRAMPercentage | 最大堆占比 | 堆按限额百分比计算 |
| ActiveProcessorCount | 有效处理器数 | 对齐 CPU limit |
| liveness / readiness / startupProbe | 存活/就绪/启动探针 | 重启决策 / 流量决策 / 启动兜底 |
| terminationGracePeriodSeconds | 优雅期秒数 | 最后兜底，超时 SIGKILL |
| preStop | 停止前钩子 | sleep 等流量摘除 |
| OOMKilled | 内存超限杀 | cgroup 杀进程，≠ Java OOM |
| emptyDir | 临时卷 | 给只读根文件系统提供可写区 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. 容器里为什么建议 `-XX:MaxRAMPercentage` 而不是 `-Xmx`？**
> 参考答案：堆按 limits 的百分比自动算，扩缩容不用改参数；JDK8u191+/10+ 起 JVM 自动识别 cgroup 限额。写死 `-Xmx` 容易出现"limit 调小但 Xmx 没改 → OOMKilled"或"调大却浪费"。

**Q2. liveness 和 readiness 怎么分工？**
> 参考答案：liveness 只判断"进程死没死"（重启决策），放进程级状态；readiness 判断"能不能接流量"（摘流量决策），可含必要依赖。Boot 用 `/actuator/health/liveness|readiness`（K8s 环境自动开启，启动完成时翻状态）；慢启动用 startupProbe。

**Q3. 滚动更新为什么还会 502？怎么根治？**
> 参考答案：三段没对齐：Endpoint 摘除有延迟（要 preStop sleep）、graceful 等待时长要小于 `terminationGracePeriodSeconds`（否则 SIGKILL 硬杀）、连接层 keepalive 与 LB idle 要对齐。公式：`terminationGracePeriodSeconds ≥ preStop + shutdown-phase-timeout + 缓冲`。

**Q4. OOMKilled 和 Java 的 OutOfMemoryError 是一回事吗？**
> 参考答案：不是。OOMKilled 是 cgroup 杀进程（堆外也算，堆可能没满）；Java OOM 是 JVM 内部抛错。排查先看 `kubectl describe` 的 Reason。

**Q5. 为什么只读根文件系统会让内嵌 Tomcat 起不来？**
> 参考答案：Boot 的 baseDir/docBase 默认在 `java.io.tmpdir`（`tomcat-*`/`tomcat-docbase-*`），只读根下写不进去 → 用 `emptyDir` 挂 `/tmp` 或配 `server.tomcat.basedir`。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：`KubernetesLabTest`（docBase 临时目录 + shutdown 超时硬断）；`scripts/k8s-jvm-demo.sh` 对比三组 JVM 参数。
- 下一步：**L14 线上排查**——Stage 4 继续：CPU 100% / 内存泄漏 / 请求 hang 的定位路径（jstack 落到 Tomcat 结构的映射）。
