# 面试题详解 12 | 线上排查实战（CPU 飙高 / 内存泄漏 / 请求 hang）

> 难度：★★★★★ ｜ 频率：★★★★★ ｜ 区分度：极高（会不会"看现场"，一道题见功底）
> 关键词：top -H、jstack、ThreadMXBean、jmap/MAT、arthas、线程名地图、JreMemoryLeakPreventionListener、ThreadLocal、死锁、慢下游

---

## 问题

**线上 Tomcat 出现 CPU 飙高、内存持续增长、请求大量 hang，你从哪一步开始查？怎么把 jstack/MAT 看到的线程与对象，映射回 Tomcat 的组件（Acceptor/Poller/Worker/容器）？**

---

## 30 秒简洁回答

> 排查的本质是"**现场三件套 → 结构映射**"：**CPU** 用 `top -H` 找到高耗线程 id → `jstack` 看它的栈（先区分 GC 型：`jstat -gcutil` 看 GC 时间占比；业务型则栈顶是应用代码）；**内存**用 `jcmd GC.heap_dump` / `jmap -histo:live` → MAT 支配树找引用链（三大常客：`ThreadLocal` 被线程池线程持有、Session/大集合堆积、类加载器泄漏）；**hang** 先看 `tomcat.threads.busy`（09 篇）→ 线程池满/`maxConnections` 闸门/死锁（jstack 直接报 `Found one Java-level deadlock`）/慢下游（栈全在 `socketRead`）。关键是**线程名地图**：`http-nio-8080-Acceptor/-Poller/-exec-*`（02 篇）、`container-N`（Boot 非 daemon 后台线程）、`tomcat-shutdown`（停机）——jstack 一抓就知道"病"在哪一层。

---

## 展开回答（高级开发级）

### 1）线程名地图（排查第一步，先背）

| 线程名 | 角色 | 什么情况下会异常 |
|---|---|---|
| `http-nio-8080-Acceptor-0` | accept 新连接 | 阻塞在 LimitLatch=连接满（09 篇） |
| `http-nio-8080-Poller-0` | epoll 就绪检测 | 极少出问题；`select(selectorTimeout=1000)` 阻塞循环（`NioEndpoint:755`） |
| `http-nio-8080-exec-*` | 业务 worker | 池满/慢下游/死锁/死循环都在这 |
| `http-nio-8080-clientPoller-*` | HTTP/2 客户端侧 | 少见 |
| `container-0`（Boot） | 非 daemon 后台线程（`TomcatWebServer:214-225`） | 容器后台任务 |
| `tomcat-shutdown` | 优雅停机等待线程（07 篇） | 停机卡住 |
| `Timer-*`/`Catalina-utility-*` | 后台周期性任务（session 扫描等） | 11 篇的 60s 会话扫描 |
| `tomcat-handler-N`（虚拟线程模式） | 虚拟线程 worker（11 篇） | 看不到"池满"，但 pin 会占用载体 |

### 2）案例 A：CPU 飙高

```
top -H -p <pid>                       # 找到高 CPU 的线程 id（十进制）
printf '%x\n' <tid>                   # 转十六进制
jstack <pid> | grep -A 30 'nid=0x<hex>'   # 定位到具体栈
jstat -gcutil <pid> 1000 10           # 并行看一眼：GC 是否疯跑（GCT 占比高 → 内存问题不是线程问题）
```

**判型（关键分叉）**：
- **GC 型**：`jstat` 里 `GCT` 占比高、`OU/OC` 接近满、`FGC` 频繁 → 去查内存（案例 B），通常伴随 `GC task thread` 高 CPU；
- **业务型**：栈顶是应用方法（死循环/正则回溯/大 JSON 序列化）；
- **Tomcat 型（少见）**：Poller 用 `select(timeout)` 阻塞轮询（`NioEndpoint:755`），没有 Netty 那种空轮询自旋问题；真要看 Acceptor/Poller 得先排除上面两类。

### 3）案例 B：内存泄漏（内嵌三大常客）

```
jcmd <pid> GC.heap_dump /tmp/heap.hprof     # 或 jmap -dump:live,format=b,file=...
jmap -histo:live <pid> | head -20            # 快照：谁的实例数/占用异常
# MAT：打开 hprof → Dominator Tree → 找最大保留集 → Path to GC Roots
```

| 常客 | 引用链特征（MAT） | 诱因 |
|---|---|---|
| `ThreadLocal` | worker 线程 → threadLocals → 大对象 | 线程池线程**存活期超长**，ThreadLocal 不 remove；内嵌用虚拟线程后"每请求新线程"反而缓解 |
| Session/大集合 | `StandardSession`/静态集合 | 会话不失效、静态缓存无上限（11 篇：会话扫描 60s 一轮） |
| 类加载器 | `WebappClassLoader` → 驱动/ThreadLocal/Introspector | 独立部署 reload 经典问题；Tomcat 给 standalone 配了防泄漏监听器 |

**Tomcat 自带的两道防线（内嵌要懂它们在不在）**：
- `JreMemoryLeakPreventionListener`（`catalina/core/JreMemoryLeakPreventionListener:46`）：独立部署由 server.xml 挂载——URLConnection 缓存/JAR 锁定（`:75` 附近 `urlCacheProtection=true`）、`DriverManager` 提前触发驱动加载（`driverManagerProtection=true :85`，避免 webapp 类加载器被驱动 pin）等；**Boot 内嵌全仓 0 引用——不加**（应用类在系统 classpath，经典 webapp 泄漏不适用）；
- `ThreadLocalLeakPreventionListener`（`:45/:61`）：reload 时重建线程池线程、清 ThreadLocal——同样是**独立部署**语境。

### 4）案例 C：请求 hang（四条线）

| 现象（jstack/指标） | 结论 | 对策 |
|---|---|---|
| `exec-*` 全部卡在应用代码（DB/HTTP 调用） | 慢下游 | 查下游 RT/超时；`/actuator/metrics/tomcat.threads.busy` 打满（09 篇） |
| `exec-*` 卡在 `socketRead`，客户端还在发 | 慢客户端占 worker | `connectionTimeout`/`keepAliveTimeout`（09 篇） |
| `Found one Java-level deadlock`（jstack 末尾直接报） | 死锁 | 定位互锁对象（案例：A/B 锁反序） |
| `busy` 不高但新请求无响应；Acceptor 阻塞在 latch | 连接闸门满 / backlog 满 | `maxConnections`/`acceptCount`（09 篇）；也查 CLOSE_WAIT |
| `busy` 打满、栈里大量 `park` 等任务队列 | 线程池满（默认无界队列 → 排队） | 扩容/限流/查为什么慢（09 篇） |

> ⚠️ **联动陷阱（lab 实测）**：请求 hang/死锁现场还会**拖住优雅停机**——`isActive` 一直为真，关停要等到 `timeout-per-shutdown-phase`（默认 30s）才强停（07 篇）。排查期间重启要有这个预期，赶时间可临时 `server.shutdown=immediate`。

### 5）工具速查（生产怎么选）

| 工具 | 用途 | 注意 |
|---|---|---|
| `jstack` / `jcmd Thread.print` | 线程栈 + 死锁检测 | 一次采样可能骗人，**间隔连抓 3 次**看栈是否停滞 |
| `jstat` / `jcmd GC.heap_info` | GC 判型 | CPU 型先排除它 |
| `jmap -histo:live` / `jcmd GC.heap_dump` | 堆对象快照 | `-histo:live` 触发 Full GC，生产慎用 |
| **arthas** | `dashboard`/`thread -n 5`/`trace`/`watch` | `trace/watch` 有性能开销，排障即用即走 |
| JFR（`jcmd JFR.start`） | 低开销飞行记录（含虚拟线程 pin 事件） | 首选无侵入 |

---

## 进一步回答（架构师层级）

- **先止损、后根因**：扩容/摘流量/重启（内存态坏了重启即好）→ 保留现场（dump/线程栈三连）→ 再定位；
- **为什么"重启就好"是重要线索**：泄漏/线程池内部状态/死锁都不落盘——重启消失 = 典型的运行时状态问题，不是代码逻辑必现；
- **容器里的排查**：镜像不带 JDK 工具时用 `jattach`/`kubectl debug` 挂临时容器；`jcmd` 也能远程 attach（JMX 或 local socket）；
- **把排查变日常**：`tomcat.threads.busy`/`tomcat.connections.current`/GC/线程数做监控（09 篇），比出事再上工具强一个量级；
- **虚拟线程时代的新特征**（11 篇）：`exec-*` 变少、`tomcat-handler-N` 泛滥；pin 的载体线程 CPU 高——JFR `jdk.VirtualThreadPinned` 是首选证据。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：考"给你一台坏掉的服务器，第一步做什么"——现场采集、判型、映射回组件；
- **回答顺序**：线程名地图 → CPU（判型）→ 内存（三大常客）→ hang（四条线）→ 工具与止损；
- **一句话收束**："先 top -H + jstack 判型，再按 CPU/内存/hang 三条线归因，最后用线程名和 MAT 引用链把问题映射回 Tomcat 的具体组件。"

## 必记关键词

`top -H` ｜ `jstack` ｜ `ThreadMXBean.findDeadlockedThreads` ｜ `jmap -histo:live` ｜ MAT 支配树 ｜ `arthas` ｜ JFR ｜ 线程名地图 ｜ `JreMemoryLeakPreventionListener` ｜ `ThreadLocalLeakPreventionListener` ｜ socketRead 堆积

## 必须明确的边界

- CPU 飙高**先排除 GC**（不然会查错方向）；
- 一次 `jstack` 不够（连抓 3 次看栈是否滞留）；
- `-histo:live` 会触发 Full GC，生产谨慎；
- **Boot 内嵌没有** `JreMemoryLeakPreventionListener`/`ThreadLocalLeakPreventionListener`（那是独立部署防线）；
- 虚拟线程模式下"池满"类排查要换线索（`tomcat-handler-N`/pin）。

## 常见错误

- CPU 高直接查线程不先排除 GC；
- 拿一次 jstack 就下结论；
- 死锁靠猜（jstack 会自动报，要会读）；
- 把 CLOSE_WAIT/池满/慢下游混为一谈；
- 不知道内嵌少了 Tomcat 的两道防泄漏监听器。

## 自测要求

- **30 秒**：三件套（CPU/内存/hang）各一句 + 线程名地图；
- **3 分钟**：能讲 CPU 判型、内存三常客、hang 四线（含行号/指标）；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：CPU 飙高怎么区分是 GC 还是业务？**
> 并行看 `jstat -gcutil`：`GCT` 占比高、`FGC` 频繁 → GC 型，去查内存/分配；GC 正常而热点栈顶是应用方法 → 业务型（死循环/正则/Schema 校验等）。Tomcat 自身 Poller 是 `select(timeout)` 阻塞模型（`NioEndpoint:755`），不是常见元凶。

**追问 2：内存泄漏怎么快速定位？**
> 两步：`jmap -histo:live` 看谁多（快）→ `jcmd GC.heap_dump` + MAT 支配树/Path to GC Roots 看**谁持有它**（准）。内嵌三大常客：ThreadLocal（线程池线程长活）、Session/静态集合、类加载器（DevTools/reload 场景）。

**追问 3：请求 hang 怎么判断是池满还是慢下游？**
> 看 `tomcat.threads.busy` 与 jstack：busy≈max 且栈全在应用/下游调用 → 慢下游→池满；busy 不高但无响应 → 查连接闸门（LimitLatch/Acceptor 阻塞）、CLOSE_WAIT、死锁（jstack 直接报）。

---

## 示例与使用说明（lab 复现，`xhs-demo/tomcat-lab`）

三个"可复现的坏请求" + 用**代码模拟 jstack/MAT 的判断**：

```java
@GetMapping("/spin")     // CPU 型：worker 忙循环
@GetMapping("/leak")     // 内存型：往静态列表塞 byte[]（MAT 的"静态集合"常客）
@GetMapping("/deadlock/a" + "/deadlock/b")   // hang 型：两个端点反序抢锁 → 死锁
```

实测（2026-09-24，lab）：见 `tomcat-lab/README.md`「实验六」——`ThreadMXBean` 检测死锁、栈采样定位热点方法、静态集合增长与清理。

## 面试官评分点

**通过标准**
- 会按"判型→归因→映射组件"的顺序答；
- 线程名地图准确（Acceptor/Poller/exec/container/tomcat-shutdown）；
- 内存三大常客与 hang 四线说得清。

**加分项**
- 能带行号讲 Tomcat 的两道防泄漏监听器与"内嵌没有"（负向证据）；
- 有真实 dump/MAT/arthas 排障故事（STAR）；
- 知道虚拟线程/JFR 的新口径；
- 强调"先止损+连抓三次"等实战细节。

**危险信号**
- 不判型直接查线程；
- 死锁/池满/慢下游混为一谈；
- 只会背工具名没实操路径。

## 实战练习

1. 跑 lab 的 `DiagnosticsTest`（三用例）看"代码版 jstack/MAT"；
2. 自己用 `jstack <pid> | grep -c 'java.lang.Thread.State'` 统计三种状态占比；
3. 用 arthas `thread -n 3` 抓一次 `/spin` 实验，对照栈顶。

## 版本与来源

- Tomcat **10.1.34**：`catalina/core/JreMemoryLeakPreventionListener.java`（:46/:85/:126/:141，javadoc 各保护项）、`catalina/core/ThreadLocalLeakPreventionListener.java`（:45/:61）、`tomcat/util/net/NioEndpoint.java`（Poller.run :740-770，`select(selectorTimeout)` :755）、`catalina/core/ContainerBase.java`（:1091-1104 后台线程）；
- Spring Boot **3.5.16**：`TomcatWebServer.java`（:214-225 `container-N`）；负向断言：全仓无 `JreMemoryLeakPreventionListener`（grep 0 命中）；
- JDK：`ThreadMXBean`/`jcmd`/JFR/arthas 为工具引用（源码树未收录，行为通过 lab 代码验证）；
- lab：`xhs-demo/tomcat-lab`（`DiagnosticsController` + `DiagnosticsTest`）；
- 本卷章节：18（故障排查）、17（可观测性）；
- 规划：`Tomcat源码学习范围规划.md` T-2/T-4（请求处理与错误处理主线）。

## 个人经历填充区（留空，按真实项目补）

- 排查过 CPU 飙高/内存泄漏吗？现场是怎么采样的、根因是什么？
- 用过 arthas/JFR 做生产诊断吗？有什么坑（性能/快照大小）？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Boot 3.5.16）；lab 实测为 2026-09-24 本机运行（Boot 3.2.5 / 内嵌 Tomcat 10.1.20；诊断手段用 JDK `ThreadMXBean`/栈采样，等价于 jstack 的判读逻辑）；项目经历请用真实案例填充。
