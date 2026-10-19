# Lesson 15: 虚拟线程与 Tomcat

> 课程索引：`../学习清单.md` → Stage 4 · Lesson 15
> 版本口径：Tomcat **10.1.34**；Spring Boot **3.5.16**；运行需 **JDK21+**（lab 用 JDK21 实测）
> 来源：Tomcat `VirtualThreadExecutor` javadoc、Boot `Threading` / customizer javadoc；lab 实测（`VirtualThreadsTest`，JDK21 A/B 对照）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L15-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 回答：虚拟线程是什么、Tomcat 怎么接、开了之后什么变了、三个陷阱（池化/pin/ThreadLocal） |
| 前置 | L03（线程模型与线程名地图）、L09（maxThreads 含义） |
| 不涉及 | 源码逐行；响应式对比展开（点到为止） |
| 下一课 | L16 Boot 2→3 / jakarta 迁移（收官） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **虚拟线程是什么**：JDK21 正式特性——**大量虚拟线程跑在少量"载体线程"上**；虚拟线程一阻塞（socket 读写、sleep、队列等待），JDK 就把它从载体上卸下，载体去跑别的。**IO 密集收益大，CPU 密集无收益**。
2. **Tomcat 的接法（两条路）**：
   - Tomcat 原生：`useVirtualThreads` 开关，线程池换成 `VirtualThreadExecutor`；
   - **Boot 3.2+ 标准做法**：`spring.threads.virtual.enabled=true` → 条件成立**且 JDK21+** → customizer 把协议处理器的执行器换成 `VirtualThreadExecutor("tomcat-handler-")`。
3. **开了之后什么变了**：**每请求一个虚拟线程**（`tomcat-handler-N`），阻塞语义不变但成本被摊薄；**`maxThreads`/`min-spare` 失效**；Acceptor/Poller 仍是平台线程；`maxConnections` 仍生效。**三个陷阱**：不要池化虚拟线程；JDK21~23 的 `synchronized` 会 **pin**（JDK24 修复）；`ThreadLocal` 每个虚拟线程一份。

---

## 2. 官方原文

### 2.1 Tomcat：执行器定义（`VirtualThreadExecutor` javadoc）

> An executor that uses a **new virtual thread for each task**.

**翻译**：每个任务使用一个**新的**虚拟线程的执行器。

- **要点**：关键字是 **new**——它**不是线程池**，不复用线程；内部通过 `JreCompat` 反射调 JDK21 的 `Thread.ofVirtual().name(prefix, 0)`（所以 Tomcat 在 JDK11+ 都能编译，运行时按 JDK 版本选择实现）。

### 2.2 Boot：开关定义（`Threading` javadoc）

> **Virtual threads**. Active if `spring.threads.virtual.enabled` is `true` **and running on Java 21 or later**.

**翻译**：虚拟线程。当 `spring.threads.virtual.enabled` 为 true **且运行在 Java 21 及以上**时激活。

- **要点**：两个条件缺一不可——JDK17 上配了属性也不生效（实现里还有 `JavaVersion ≥ 21` 的显式判断）。

### 2.3 Boot：装配动作（customizer javadoc）

> Activates `VirtualThreadExecutor` on Tomcat's protocol handler.

**翻译**：在 Tomcat 的协议处理器上启用 `VirtualThreadExecutor`。

- **要点**：`@since 3.2.0`；执行器顺序排在普通 customizer 之后（`ORDER + 1`，保证覆盖）；线程名前缀固定为 `tomcat-handler-`。

---

## 3. 平台线程 vs 虚拟线程（同一份配置的对照）

配置相同（`threads.max=1` + 两个并发 1s 慢请求）：

| 模式 | 执行线程 | B 耗时 | 含义 |
|---|---|---|---|
| 平台线程（不开） | 同一 `http-nio-…-exec-1` | **≈1811ms**（被串行） | 池子只有一个线程，后到的排队 |
| 虚拟线程（开启） | `tomcat-handler-1` / `tomcat-handler-2`（并行，`syncInFlight=2`） | **≈1009ms** | `maxThreads=1` 对虚拟线程无效 |

**对照表**：

| 维度 | 平台线程池（默认） | 虚拟线程 |
|---|---|---|
| 每请求线程 | 池里复用（`http-nio-8080-exec-*`） | 每请求新建（`tomcat-handler-*`） |
| `maxThreads` / `min-spare` | 生效 | **失效**（没有池） |
| 阻塞成本 | 占住平台线程 | unmount，载体复用 |
| Acceptor / Poller | 平台线程 | **不变**（仍是平台线程） |
| `maxConnections` | 生效 | **仍生效**（连接闸门没动） |

---

## 4. 三个陷阱（面试高频反直觉点）

### 4.1 不要池化虚拟线程

池化的目的是"复用**稀缺**资源"；虚拟线程本来就廉价、用完即弃——把它们塞进固定池等于把"每任务一个"绑回"池里几个"，收益归零还引入排队。

### 4.2 pin（JDK 版本口径必须说）

- JDK21~23：虚拟线程在 **`synchronized` 块里阻塞**（或 native 帧）无法 unmount → **pin 住载体**（退化回平台线程，还占着载体）；
- JDK24（JEP 491）起 `synchronized` 不再 pin；
- 排查：开发用 `-Djdk.tracePinnedThreads=full`，生产用 JFR 事件 `jdk.VirtualThreadPinned`。

### 4.3 ThreadLocal 与共享锁

- `ThreadLocal` 在虚拟线程里**每个虚拟线程一份**（数量爆炸）→ 大对象改用 `ScopedValue`；
- 共享锁/连接池等 `synchronized` 对象：JDK24 前注意 pin（见 4.2）。

---

## 5. 什么时候开 / 不开（迁移决策）

| 场景 | 建议 |
|---|---|
| JDK21+ / Boot 3.2+ / 阻塞 IO 为主 | **建议开**（用同步写法拿扩展性） |
| CPU 密集 / 大量 `synchronized` 热锁 | 先压测 + pin 观测，不盲目开 |
| 已有响应式栈（WebFlux/Netty） | 不必迁（那是另一套模型） |
| 并发上限 | `maxThreads` 失效后，限制变成 `maxConnections`（连接数）、`acceptCount`（连接队列）、下游超时与限流——**别把"无限虚拟线程"当无限并发** |

---

## 6. lab 实测（`VirtualThreadsTest`，JDK21）

```bash
JAVA_HOME=/env/javaEnv/jdk21 mvn -pl tomcat-lab test -Dtest=VirtualThreadsTest
# JDK17 下两用例自动 Skip（@EnabledForJreRange）
```

---

## 7. 术语对照（本课汇总）

| 术语 | 中文 | 一句话 |
|---|---|---|
| 虚拟线程 / JEP 444 | — | JDK21 正式特性：轻量线程 |
| 载体线程 carrier | — | 实际执行的平台线程 |
| unmount | 卸载 | 阻塞时虚拟线程让出载体 |
| VirtualThreadExecutor | 虚拟线程执行器 | 每任务一虚拟线程（非池） |
| useVirtualThreads | （Tomcat 原生开关） | 换执行器为虚拟线程版 |
| tomcat-handler- | 线程名前缀 | Boot 虚拟线程的命名 |
| pin / JEP 491 | 钉住 | 无法 unmount；JDK24 修复 synchronized pin |
| ScopedValue | 作用域值 | 虚拟线程时代替代 ThreadLocal 传参 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. 开了虚拟线程，`maxThreads` 还有意义吗？**
> 参考答案：没有——执行器从线程池换成了 `VirtualThreadExecutor`（每任务一虚拟线程），`maxThreads`/`min-spare` 不再起作用；并发限制变成 `maxConnections`（连接数）、`acceptCount`（连接队列）、下游超时与限流。

**Q2. 为什么虚拟线程不要池化？**
> 参考答案：池化的目的是复用稀缺资源；虚拟线程本来就廉价、用完即弃，池化会把任务绑死在少量虚拟线程上，收益归零还引入排队。

**Q3. pin 是什么？怎么发现？**
> 参考答案：虚拟线程在某些情况下无法从载体 unmount（JDK21~23 的 `synchronized`、native 帧）→ 载体被占住，扩展性退化。发现：`-Djdk.tracePinnedThreads=full`（开发）或 JFR `jdk.VirtualThreadPinned`（生产）；JDK24 起 `synchronized` 不再 pin。

**Q4. 虚拟线程和 NIO（Acceptor/Poller）是什么关系？**
> 参考答案：没关系——Acceptor/Poller 仍是平台线程（IO 就绪检测不变）；虚拟线程替换的是"执行请求"的 Worker。`maxConnections` 连接闸门仍然生效。

**Q5. 你的项目该不该开？看什么指标决定？**
> 参考答案：看负载类型：阻塞 IO 为主（调下游/DB）+ JDK21/Boot3.2 → 建议开；CPU 密集或大量 `synchronized` 热锁 → 先压测 + pin 观测。开了之后看载体利用率与 pin 事件，而不是只看吞吐。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：跑 `VirtualThreadsTest`（JDK21）对照 `TuningTest` 同配置差异；在 `/slow` 里用 `synchronized` 包住 sleep，用 `-Djdk.tracePinnedThreads=full` 观察 pin。
- 下一步：**L16 Boot 2→3 / jakarta 迁移**（收官）——版本矩阵、包名迁移、属性改名与隐形坑。
