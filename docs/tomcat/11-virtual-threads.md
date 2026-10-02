# 面试题详解 11 | 虚拟线程与 Tomcat（JDK21 / Boot 3.2+）

> 难度：★★★★☆ ｜ 频率：★★★★★ ｜ 区分度：高（2026 必问的现代化题，三道防线：原理/开关/陷阱）
> 关键词：虚拟线程、JEP 444、VirtualThreadExecutor、useVirtualThreads、spring.threads.virtual.enabled、tomcat-handler-、pin、池化陷阱

---

## 问题

**Tomcat 支持虚拟线程吗？怎么开？开了之后线程模型怎么变、`maxThreads` 为什么失效？虚拟线程和 NIO（Acceptor/Poller）是什么关系？pin 是怎么回事、虚拟线程能不能池化？**

---

## 30 秒简洁回答

> 支持，且是 **2026 的主流玩法**。Tomcat 10.1 自带开关 `useVirtualThreads`（`AbstractEndpoint:598`）：`createExecutor :1084-1087` 为 true 时建 `VirtualThreadExecutor`（线程名 `-virt-`，内部通过 `Jre21Compat` 反射调 `Thread.ofVirtual().name(prefix, 0)`，让 Tomcat 在 JDK11+ 都能编译）。**Boot 3.2+ 的标准做法**：`spring.threads.virtual.enabled=true`（`Threading:42/:49`）→ `EmbeddedWebServerFactoryCustomizerAutoConfiguration:72-75`（`@ConditionalOnThreading(VIRTUAL)`）注册 `TomcatVirtualThreadsWebServerFactoryCustomizer` → `protocolHandler.setExecutor(new VirtualThreadExecutor("tomcat-handler-"))`（`:37-39`；order = 普通 customizer +1，保证覆盖）。开启后**每个请求一个虚拟线程**（名字 `tomcat-handler-N`）：阻塞语义不变，但"每请求一线程"的成本被摊薄；**`maxThreads`/`min-spare` 池参数失效**；Acceptor/Poller 仍是平台线程（IO 就绪检测不变），`maxConnections` 连接闸门仍然生效。

---

## 展开回答（高级开发级）

### 1）虚拟线程是什么（一句话 + 一个类比）

JDK21（JEP 444）正式特性：**大量虚拟线程跑在少量"载体线程"（carrier，= 平台线程）上**；虚拟线程一阻塞（socket 读写、sleep、队列等待），JDK 就把它从载体上 **unmount**，载体去跑别的虚拟线程。类比：平台线程=办公桌，虚拟线程=办事的人——人多了不用每人一张桌子，等窗口叫号时桌子让出来。

- 收益：**IO 密集/阻塞多**的服务用同步写法也能横向扩展（不用改写响应式）；
- 无收益：**CPU 密集**（载体=CPU 核数，纯计算卡死载体）；
- 与 NIO 不冲突：NIO 解决"连接多"，虚拟线程解决"每连接一个阻塞线程"的成本。

### 2）Tomcat 侧实现：两个入口

```
① Tomcat 原生：AbstractEndpoint.useVirtualThreads（:598-603）
     createExecutor（:1084-1095）：
       if (getUseVirtualThreads()) executor = new VirtualThreadExecutor(getName() + "-virt-");
       else                       executor = new ThreadPoolExecutor(minSpare, maxThreads, ...)（老池）
② Boot 推荐：不改 endpoint，直接换 ProtocolHandler 的 executor（见 §3）
```

`VirtualThreadExecutor`（Tomcat `:32-52`）不是线程池：`execute()` 每次 `threadBuilderStart` 起一个新虚拟线程；`Jre21Compat.createVirtualThreadBuilder :72-77` 用反射 `Thread.ofVirtual()` + `name(prefix, 0)`——所以名字是 `tomcat-handler-0/1/2...`。

### 3）Boot 侧开关（注册链与顺序）

```
spring.threads.virtual.enabled=true（Threading :42/:49）
  → @ConditionalOnThreading(Threading.VIRTUAL)（EmbeddedWebServerFactoryCustomizerAutoConfiguration:73）
  → new TomcatVirtualThreadsWebServerFactoryCustomizer()（:74）
  → customize :37-39：factory.addProtocolHandlerCustomizers(
        handler -> handler.setExecutor(new VirtualThreadExecutor("tomcat-handler-")))
  → getOrder :43-44 = TomcatWebServerFactoryCustomizer.ORDER + 1（排在普通 customizer 之后，覆盖生效）
```

副作用（同开关还切换别的）：Boot 会把应用 `TaskExecutor` 等也换成虚拟线程实现（`VirtualThreadTaskExecutor`，如 Pulsar/OTLP 等组件），保持一致语义。

### 4）开启后到底变了什么（对照表）

| 维度 | 平台线程池（默认） | 虚拟线程 |
|---|---|---|
| 每请求线程 | 池里复用（`http-nio-8080-exec-N`） | 每请求新建（`tomcat-handler-N`） |
| `maxThreads`/`min-spare` | 生效 | **失效**（没有池） |
| 阻塞成本 | 占住平台线程 | unmount，载体复用（IO 密集收益大） |
| Acceptor/Poller | 平台线程 | **不变**（仍是平台线程） |
| `maxConnections` | 生效 | **仍生效**（连接闸门没动） |
| CPU 密集 | 与核数绑定 | 无收益（可能更差） |

### 5）pin：为什么"虚拟线程也别乱用 synchronized"

JDK21~23：虚拟线程在 **`synchronized` 块里阻塞**（或 native 帧里）无法 unmount → **pin 住载体**（等于退化回平台线程，还占用载体）。JDK24（JEP 491）起 `synchronized` 不再 pin——**回答要带版本口径**。
排查：JDK21 用 `-Djdk.tracePinnedThreads=full` 打印 pin 栈；生产用 JFR 事件 `jdk.VirtualThreadPinned`。

### 6）池化陷阱（面试高频反直觉点）

- **不要池化虚拟线程**（`Executors.newFixedThreadPool` 包一层）——把"每任务一个"的机制塞回池，收益归零；
- `ThreadLocal` 在虚拟线程里**每个虚拟线程一份**（数量爆炸）→ 大对象别放或改用 `ScopedValue`；
- 共享锁/连接池等 `synchronized` 对象：JDK24 前注意 pin。

---

## 进一步回答（架构师层级）

- **虚拟线程 vs 响应式**：虚拟线程用**同步语法**拿到接近响应式的可扩展性，代价是心智模型仍要理解载体/pin；已有响应式栈（WebFlux/Netty）不必迁，阻塞栈（MVC/JDBC）收益最大；
- **迁移决策**：JDK21+ / Boot 3.2+ / 阻塞 IO 为主 → 建议开；CPU 密集或大量 `synchronized` 热锁 → 先压测+pin 观测；
- **并发上限换地方**：`maxThreads` 失效后，控制并发的闸门变成 `maxConnections`、下游超时、信号量/限流——**别把"无限虚拟线程"当无限并发**；
- **排查口径**：`jcmd <pid> Thread.dump_to_file -format=json` / JFR；载体线程名仍是 `http-nio-8080-exec-*`（默认 ForkJoinPool 载体），虚拟线程名 `tomcat-handler-N`；
- **与 02/09 的联动**：02 篇的"池参数怎么调"在虚拟线程模式下作废一半（maxThreads 无效，maxConnections/acceptCount 仍有效）；09 篇的 `tomcat.threads.busy` 指标语义也要重新解读（虚拟线程不占池）。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"虚拟线程机制 + Tomcat 落点 + 边界（maxThreads 失效/pin/池化）"；
- **回答顺序**：机制一句 → 两条入口（Tomcat 原生/Boot 开关）→ 行为对照表 → pin 与池化陷阱 → 版本口径；
- **一句话收束**："虚拟线程把'每请求一线程'的成本摊薄，Tomcat/Boot 都有一行开关；但 maxThreads 失效、Acceptor/Poller 不变、synchronized 在 JDK24 前会 pin——开之前先看负载类型和锁。"

## 必记关键词

`虚拟线程/JEP 444` ｜ `载体线程 carrier` ｜ `VirtualThreadExecutor` ｜ `useVirtualThreads` ｜ `spring.threads.virtual.enabled` ｜ `tomcat-handler-` ｜ `pin/JEP 491` ｜ `jdk.tracePinnedThreads` ｜ `池化陷阱` ｜ `ScopedValue`

## 必须明确的边界

- 虚拟线程是 **JDK21+** 能力（Tomcat 10.1 用 `Jre21Compat` 反射兼容，JDK17 上开了也不生效）；
- 开启后 **`maxThreads`/`min-spare` 失效**，但 `maxConnections`/`acceptCount` 仍生效；
- **Acceptor/Poller 不会变虚拟线程**；
- **不要池化虚拟线程**；
- JDK21~23 的 `synchronized` 会 **pin**（JDK24 起修复）——版本口径必须说；
- 虚拟线程不解决"CPU 密集"与"下游本身瓶颈"。

## 常见错误

- 以为开了虚拟线程就"无限并发"（连接数/下游限制还在）；
- 把虚拟线程池化；
- 说"Tomcat 的 Acceptor 也变成虚拟线程"；
- 不知道 `maxThreads` 失效；
- 不提 pin 与 JDK 版本差异。

## 自测要求

- **30 秒**：机制 + 两条开关 + 三个边界（maxThreads/pin/池化）；
- **3 分钟**：能画 Boot 注册链（Threading→条件→customizer→order）并对照平台线程表；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：开了虚拟线程，`maxThreads` 还有意义吗？**
> 没有——executor 从线程池换成了 `VirtualThreadExecutor`（每任务一虚拟线程），`maxThreads`/`min-spare` 不再起作用；并发闸门变成 `maxConnections`/`acceptCount`、下游超时与限流。

**追问 2：为什么虚拟线程不要池化？**
> 池化的目的是"复用稀缺资源"；虚拟线程本来就是廉价、用完即弃（阻塞自动 unmount），池化会把任务绑死在少量虚拟线程上，收益归零还引入排队。

**追问 3：pin 是什么？怎么发现？**
> 虚拟线程在某些情况下无法从载体 unmount（JDK21~23 的 `synchronized`、native 帧）→ 载体被占住，扩展性退化。发现：`-Djdk.tracePinnedThreads=full`（开发）或 JFR `jdk.VirtualThreadPinned`（生产）；JDK24 起 `synchronized` 不再 pin。

---

## 示例与使用说明（lab 复现，`xhs-demo/tomcat-lab`）

```properties
# 关键就一行：开启虚拟线程（Boot 3.2+ / JDK21+）
spring.threads.virtual.enabled=true
```

对照实验（`VirtualThreadsTest`，需 JDK21）：

```text
同一配置 threads.max=1 + 两个并发 1s 慢请求：
  平台线程（TuningTest 实测）：B 耗时 ≈ 1811ms（被唯一线程串行）
  虚拟线程（VirtualThreadsTest）  ：B 耗时 ≈ 1000ms（并发，不被 maxThreads 限制）
  响应线程名：tomcat-handler-N（平台线程模式是 http-nio-...-exec-N）
```

运行：`JAVA_HOME=/env/javaEnv/jdk21 mvn -pl tomcat-lab test -Dtest=VirtualThreadsTest`

## 面试官评分点

**通过标准**
- 能讲虚拟线程机制与 Tomcat 落点；
- 知道 `maxThreads` 失效、Acceptor/Poller 不变；
- 知道 pin 与池化禁忌。

**加分项**
- 能带行号讲 Boot 注册链（`Threading:42/:49`、`:73-74`、`:37-39/:43-44`）；
- 能讲 JDK21~23 与 JDK24 的 pin 差异；
- 能讲迁移决策与"并发上限换地方"；
- 有压测/生产开启经验（含 pin 排查）。

**危险信号**
- 认为"虚拟线程=无限并发"；
- 池化虚拟线程；
- 不知道 maxThreads 失效；
- 不讲版本口径（JDK21 vs 24）。

## 实战练习

1. 跑 `VirtualThreadsTest`（JDK21）对照 `TuningTest` 同配置差异；
2. 在 `/slow` 里用 `synchronized` 包住 sleep，JDK21 下用 `-Djdk.tracePinnedThreads=full` 观察 pin；
3. 换成 JDK17 启动同一配置，确认虚拟线程未生效（回退平台线程池）。

## 版本与来源

- Tomcat **10.1.34**：`tomcat/util/net/AbstractEndpoint.java`（:598-603/:1084-1095）、`tomcat/util/threads/VirtualThreadExecutor.java`（:32/:42-43/:47-52）、`tomcat/util/compat/Jre21Compat.java`（:72-77）；
- Spring Boot **3.5.16**：`autoconfigure/thread/Threading.java`（:28/:42/:49）、`autoconfigure/web/embedded/EmbeddedWebServerFactoryCustomizerAutoConfiguration.java`（:63-76，bean :72-75）、`autoconfigure/web/embedded/TomcatVirtualThreadsWebServerFactoryCustomizer.java`（:33/:37-39/:43-44）；
- JDK：JEP 444（虚拟线程，JDK21）/ JEP 491（synchronized 去 pin，JDK24）——概念引用（JDK 源码未收录）；
- lab：`xhs-demo/tomcat-lab`（`VirtualThreadsTest`，JDK21）；
- 本卷章节：02（NIOEndpoint 线程）、11（并发与线程池）；
- 规划：`Tomcat源码学习范围规划.md` T-2。

## 个人经历填充区（留空，按真实项目补）

- 线上开过虚拟线程吗？压测对比数据如何？踩过 pin/ThreadLocal 的坑吗？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Boot 3.5.16）；lab 实测数据为 2026-09-24 本机运行（Boot 3.2.5 / 内嵌 Tomcat 10.1.20，JDK21）；JEP 444/491 为规范引用（JDK 源码未收录）；项目经历请用真实案例填充。
