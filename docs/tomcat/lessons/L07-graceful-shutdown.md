# Lesson 07: 优雅停机 — 停流量、等在途、再拆内部

> 课程索引：`../学习清单.md` → Stage 2 · Lesson 07
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`）；内嵌运行版本 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）
> 来源：`webapps/docs/config/service.xml`（Tomcat 原生）、Spring Boot 源码 javadoc（API 文档口径）、lab 实测（`GracefulShutdownTest` / `graceful-shutdown-demo.sh`）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L07-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 讲清停机为什么分三步（停流量 → 等在途 → 拆内部），以及**两套机制**：Tomcat 原生（Service 级）与 Boot 级（内嵌主流） |
| 前置 | L02（启动顺序）、L03（请求在 Worker 上）、L06（停止顺序：不是简单反序） |
| 不涉及 | K8s 细节（L13）、源码逐行（Stage 3） |
| 下一课 | L08 异步 Servlet（与"在途计数"直接相关） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **优雅停机三步**：① **停流量**（关闭监听 socket、pause）→ ② **等在途请求结束**（计数/等待）→ ③ **拆内部**（停容器 → 彻底停 Connector）。**直接停会硬断在途请求**（连接重置/线程被中断）。
2. **两套机制并存**（内嵌场景都涉及）：
   - **Tomcat 原生（Service 级）**：由 `gracefulStopAwaitMillis` 控制——"等客户端连接处理并关闭，再停容器层级"；**只对 `bindOnInit=false` 的连接器生效**，默认 **0（不等待）**；
   - **Boot 级（内嵌主流）**：`server.shutdown=graceful`（3.5 已默认）→ Spring 生命周期先执行"优雅停机"阶段：pause + 关闭监听 socket → **轮询在途计数直到 0**（或超时）→ 再真正 `webServer.stop()`。
3. **在途计数与超时**：同步请求看 `countAllocated`、异步请求看 `inProgressAsyncCount`；等待上限 = `spring.lifecycle.timeout-per-shutdown-phase`（默认 **30s**），**超时后强制继续**（硬断）。触发方式是 SIGTERM / JVM 关闭钩子——**`kill -9` 不触发**。

---

## 2. 官方原文

### 2.1 Tomcat 原生：`gracefulStopAwaitMillis`（`config/service.xml`，节选）

> The time to wait, in milliseconds, when stopping the Service for the client connections to finish processing and close **before the Service's container hierarchy is stopped**. The wait only applies to Connectors configured with a `bindOnInit` value of `false` which is **not the default**. Any value of zero or less means there will be no wait. If not specified, the **default value of zero** will be used.

**翻译**：停止 Service 时，等待若干毫秒让客户端连接**处理完并关闭**，**然后才停止 Service 的容器层级**；该等待仅适用于 `bindOnInit=false` 的连接器（**不是默认值**）；取值 ≤0 表示不等待；未配置时默认 **0**。

### ① 核心结论

Tomcat 原生就提供了"**先等连接处理完、再停容器**"的机制，但**默认不等待**（0），且对连接器配置有前提（`bindOnInit=false`）。

### ② 机制说明

- 官方描述的顺序是"**连接处理完并关闭 → 停容器层级**"——与 L06 的停止顺序（先停流量、再停容器、最后彻底停 Connector）一致；
- 默认 0 意味着：**独立 Tomcat 默认并不"优雅"**——生产要显式配置（或依赖 Boot 的机制）。

### ③ 官方要点（3 条）

1. 等待发生在"停容器层级之前"；
2. 只对 `bindOnInit=false` 的连接器生效（默认不满足）；
3. 默认 0（不等待）。

### ④ 术语对照

| 官方英文 | 中文 | 说明 |
|---|---|---|
| graceful stop | 优雅停止 | 等连接处理完再停 |
| finish processing and close | 处理完并关闭 | 等待的内容 |
| container hierarchy | 容器层级 | 停止对象 |
| bindOnInit | 初始化时绑定 | 前提条件（false 才适用） |

### ⑤ 结论

> **Tomcat 原生 = "等待连接处理完再停容器"，但默认关闭、有前提；内嵌生产实践更依赖 Boot 的优雅停机。**

### 2.2 Boot 属性（源码 javadoc，API 文档口径）

> **`server.shutdown`**: Type of shutdown that the server will support.（3.5 的默认值为 `GRACEFUL`）

> **`spring.lifecycle.timeout-per-shutdown-phase`**: Timeout for the shutdown of any phase (group of SmartLifecycle beans with the same 'phase' value).（默认 30 秒）

- **要点**：Boot 把"优雅停机"做成**生命周期阶段**的一环（L06 的机制在这里派上用场）；超时是"每个 phase"的等待上限。

---

## 3. 优雅停机三步（概念级）

```
① 停流量：关闭监听 socket（不再收新连接）+ pause（连接器暂停）
② 等在途：轮询/等待"处理中的请求"清零（计数归零）
③ 拆内部：停容器层级，最后彻底停 Connector（L06 停止顺序）
```

**为什么必须是这个顺序**：

- 先停流量，保证"新请求不再进来"；
- 再等在途，保证"已进来的请求能跑完"；
- 最后拆内部，保证"请求跑的依赖（容器/应用/路由）还在"。

反例（Immediate 模式，lab 实测）：不等待直接停 → **worker 被中断**（`InterruptedException`）、客户端连接被重置。

---

## 4. Boot 级优雅停机链路（内嵌主线）

```
SIGTERM（K8s 滚动更新 / Ctrl+C / 关闭脚本）
  → JVM 关闭钩子（Spring 注册）
  → Spring 生命周期按 phase 降序停止：
      ① 优雅停机阶段（WebServerGracefulShutdownLifecycle）
           → TomcatWebServer.shutDownGracefully：
                · pause + closeServerSocketGraceful（停流量）
                · 轮询在途计数，直到为 0（或超时）
           → 回调完成
      ② WebServer 启停阶段（WebServerStartStopLifecycle）
           → webServer.stop()（拆内部）
  → JVM 退出
```

> **两套机制的连接点**：内嵌场景下，Boot 会把连接器自动设为 **`bindOnInit=false`**（`TomcatWebServer.disableBindOnInit()`）——这恰好是 Tomcat 原生 `closeServerSocketGraceful`/`gracefulStopAwaitMillis` 生效的前提（§2.1 官方原文："only applies to Connectors configured with bindOnInit=false"）。

**在途计数（谁算"在途"）**：

| 请求类型 | 计数 |
|---|---|
| 同步请求（业务线程在执行） | `countAllocated`（分配出的 Servlet 调用数） |
| 异步请求（startAsync/SSE） | `inProgressAsyncCount`（L08 展开） |

**超时兜底**：等待超过 `timeout-per-shutdown-phase`（默认 30s）→ 强制继续（在途请求被硬断）——所以"优雅"是**有上限的**。

**lab 实测**（`GracefulShutdownTest` / 脚本）：

| 场景 | 结果 |
|---|---|
| graceful + 3s 同步/异步请求在途 | `close()` 阻塞 ≈3s，请求正常返回 200 |
| immediate + 在途请求 | `close()` 45~103ms 返回；worker 被中断、客户端连接重置 |
| 脚本（5s 慢请求） | graceful 停机 ≈4970ms；immediate ≈103ms |

---

## 5. 为什么"直接停"不行（Immediate 的代价）

- 在途请求被硬断：客户端看到连接重置（lab：`IOException`）；
- 正在执行的业务被中断（lab：`InterruptedException: sleep interrupted`）；
- 对发布/滚动更新意味着**用户可见的错误**。

适用边界：immediate 只适合开发测试或明确可中断的场景；生产（尤其 K8s）必须 graceful。

---

## 6. 与 K8s 的衔接（预告 L13）

| 概念 | 关系 |
|---|---|
| SIGTERM | 触发优雅停机（上链路）；K8s 删除 Pod 时发送 |
| SIGKILL | 强制杀（`kill -9`）：**不触发**任何钩子，优雅停机失效 |
| `terminationGracePeriodSeconds` | K8s 的最后兜底：必须 **>** 应用内等待上限（30s）+ preStop 等，否则会被 SIGKILL（L13 展开公式） |

---

## 7. 术语对照（本课汇总）

| 术语 | 中文 | 一句话 |
|---|---|---|
| graceful shutdown | 优雅停机 | 停流量 + 等在途 + 拆内部 |
| pause | 暂停 | 连接器停止接收/处理新请求 |
| closeServerSocketGraceful | 优雅关闭监听套接字 | 关闭监听 socket、禁 keep-alive |
| in-flight | 在途 | 正在处理中的请求 |
| countAllocated / inProgressAsyncCount | 在途计数 | 同步 / 异步两类 |
| timeout-per-shutdown-phase | 阶段超时 | 默认 30s，超时强停 |
| shutdown hook | 关闭钩子 | SIGTERM 时的执行入口 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. 优雅停机的三步是什么？为什么是这个顺序？**
> 参考答案：停流量（关监听 socket/pause）→ 等在途请求清零（计数）→ 拆内部（停容器、彻底停 Connector）。先保证不进新请求，再保证旧请求跑完，最后才拆依赖。

**Q2. Tomcat 原生的优雅停机是什么？默认开启吗？**
> 参考答案：`gracefulStopAwaitMillis`（Service 级）：等待客户端连接处理完并关闭后再停容器层级；只对 `bindOnInit=false` 的连接器生效，默认 **0（不等待）**——所以默认并不优雅。

**Q3. Boot 级优雅停机怎么实现（链路）？**
> 参考答案：SIGTERM → 关闭钩子 → 生命周期先执行优雅停机阶段（pause + closeServerSocketGraceful + 轮询在途计数）→ 再执行 WebServer 停止阶段（webServer.stop()）。

**Q4. "在途请求"怎么统计？超时后会怎样？**
> 参考答案：同步请求看 `countAllocated`，异步请求看 `inProgressAsyncCount`；等待上限 `spring.lifecycle.timeout-per-shutdown-phase`（默认 30s），超时后强制继续（硬断在途）。

**Q5. 为什么 `kill -9` 无法优雅停机？**
> 参考答案：`kill -9`（SIGKILL）由内核直接终止进程，JVM 没有机会执行关闭钩子——优雅停机链路完全不会启动。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：`scripts/graceful-shutdown-demo.sh graceful 5` 与 `immediate 5` 对照停机耗时与请求结局。
- 下一步：**L08 异步 Servlet**——`startAsync` 把 Worker 还回去之后，"在途"如何被计数、超时/错误如何收尾。
