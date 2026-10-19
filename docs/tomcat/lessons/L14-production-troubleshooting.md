# Lesson 14: 线上排查 — CPU 飙高 / 内存泄漏 / 请求 hang

> 课程索引：`../学习清单.md` → Stage 4 · Lesson 14
> 版本口径：Tomcat **10.1.34**；排查工具为 JDK/arthas/JFR（行为与版本相关处另行标注）
> 来源：`ThreadMXBean`/线程名地图（lab `DiagnosticsTest` 实测）、Tomcat 线程模型（L03）、官方线程语义（NioEndpoint Poller `select(timeout)` 阻塞轮询）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L14-2 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 掌握"现场三件套 → 结构映射"：看到症状，能说出先采什么证据、判什么型、落到 Tomcat 的哪个结构 |
| 前置 | L03（线程模型）、L07（停机）、L09（参数）、L11（虚拟线程口径） |
| 不涉及 | 逐行源码；K8s 侧排查（日志平台/链路追踪属平台域） |
| 下一课 | L15 虚拟线程（Stage 4 继续） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **CPU 先判型**：GC 型（`jstat` 看 GCT 占比、`OU/OC` 满、`FGC` 频繁） vs 业务型（栈顶是应用代码） vs 罕见 Tomcat 自身；**`top` 的百分比以“单个逻辑核”为 100% 基准**——多核机器上 200%/800% 都正常，含义是"约 2 个/8 个核被吃满"，判读要除以 `nproc`。
2. **内存两步走**：`jmap -histo:live` 看谁多（快）→ `jcmd dump` + MAT 支配树/Path to GC Roots 看"谁持有它"（准）；内嵌三大常客：ThreadLocal（线程池线程长活）、Session/静态集合、类加载器（DevTools/reload 语境）。
3. **hang 四线归因**：池满（`busy≈max` + 排队）/ 连接闸门（`connectionCount≈max` + Acceptor 阻塞）/ 死锁（jstack 直接报）/ 慢下游（栈全在 `socketRead`）——另有隐性占用（CLOSE_WAIT 占连接名额；见 lessons L09 与面试 09 篇）；**hang 现场还会拖住优雅停机**（等到 phase 超时）。

---

## 2. CPU 判读：先理解百分比，再判型

### 2.1 百分比语义（本课最重要的纠偏）

```text
top 的 %CPU = "该进程/线程占用【单个】逻辑核的百分比"
  → 100% = 一整核跑满
  → 200% ≈ 两个核被吃满（JVM 多线程跑在多核上）
  → 800% ≈ 八个核被吃满（8 线程并行忙循环，或 GC 线程+业务线程叠加）
```

| 现象 | 含义 |
|---|---|
| 某 Java 进程 800% | 约 8 个核的算力被它占用（8 核机器 ≈ 跑满，16 核机器 ≈ 一半） |
| `top -H` 里某线程 100% | 该线程占满一整核（看 `nid=0x…` 定位） |
| 容器里超过 100% | 同样按宿主机逻辑核折算；另受 cgroup 限额影响（见 L13） |

- **判读公式**：`top` 进程值 ÷ `nproc` = 占总算力的比例；
- **线程级**：`top -H -p <pid>` 找最高线程 → `printf '%x\n' <tid>` 转十六进制 → `jstack <pid> | grep 'nid=0x…'`。

### 2.2 先排除 GC（判型第一步）

```
jstat -gcutil <pid> 1000 10        # 并行观察
```

| 信号 | 结论 |
|---|---|
| `GCT` 占比高、`OU/OC` 接近满、`FGC` 频繁 | **GC 型**：去查内存（§3），CPU 高的是 GC 线程 |
| GC 指标正常、热点栈顶是应用方法 | **业务型**：死循环、正则回溯、大对象序列化等 |
| 排除以上两者 | 才看 Tomcat 自身（Poller 是 `select(timeout)` 阻塞模型，非空轮询；见 L03） |

### 2.3 定位线程（jstack 读法）

- 一次采样可能骗人：**间隔连抓 3 次**，看栈是否停在同一帧；
- 死锁不用找：jstack 末尾直接报 `Found one Java-level deadlock`（lab 用 `ThreadMXBean.findDeadlockedThreads` 等价复现）；
- 线程名即地图：`http-nio-*-Acceptor/-Poller/-exec-*`、`container-*`、`tomcat-shutdown*`（对应 L03/L07）。

---

## 3. 内存定位：两步 + 三大常客

```
jmap -histo:live <pid> | head -20      # 第一步（快）：谁的实例数/占用异常
jcmd <pid> GC.heap_dump /tmp/heap.hprof # 第二步：落盘
MAT: Dominator Tree → 找最大保留集 → Path to GC Roots   # 第三步（准）：谁持有它
```

| 常客 | 引用链特征 | 诱因 |
|---|---|---|
| `ThreadLocal` | worker 线程 → threadLocals → 大对象 | 线程池线程**存活期超长**，`remove()` 漏调；虚拟线程模式反而缓解（每请求新线程） |
| Session / 静态集合 | `StandardSession`、static 容器 | 会话不失效、缓存无上限（会话后台扫描约 60s 一轮） |
| 类加载器 | `WebappClassLoader` → 驱动/ThreadLocal/内省缓存 | 独立部署 reload 经典问题；注意：Tomcat 的两道防泄漏监听器（`JreMemoryLeakPreventionListener` / `ThreadLocalLeakPreventionListener`）是**独立部署语境**，**内嵌默认不挂**（全仓无引用） |

- 生产注意：`-histo:live` 会触发 Full GC，用 `jcmd` dump 更稳；dump 文件大，注意磁盘。

---

## 4. hang 四线归因 + 联动陷阱

| 现象（证据） | 结论 | 对策 |
|---|---|---|
| `busy≈max` 且栈里大量 park 排队 | 线程池满（默认队列无界 → 排队） | 扩容/限流/查为什么慢（L09） |
| `busy` 不高但新请求无响应；Acceptor 阻塞在 latch | 连接闸门满 / backlog 满 | `maxConnections`/`acceptCount`（L09）；查 CLOSE_WAIT |
| jstack 末尾直接报死锁 | 死锁 | 定位互锁对象（lab：A/B 锁反序） |
| `exec-*` 栈全在 `socketRead`，客户端还在发 | 慢客户端占 worker / 慢下游 | `connectionTimeout`/`keepAliveTimeout`；查下游 RT |

> ⚠️ **联动陷阱**：hang/死锁现场会**拖住优雅停机**——`isActive` 一直为真，`close()` 要等到 phase 超时（默认 30s）才强停（lab 实测整类耗时 44.76s）。排查期重启要有这个预期，赶时间可临时 `server.shutdown=immediate`。

---

## 5. 先止损、后根因（SRE 口径）

1. **止损**：扩容 / 摘流量 / 重启（内存态坏了重启即好）；
2. **保现场**：dump + 线程栈三连（jstack 间隔抓）；
3. **再定位**：按 §2~§4 归因；
4. **转日常**：`busy`/`connections`/GC/线程数进监控（L09），比出事再上工具强一个量级。

**为什么"重启就好"是重要线索**：泄漏/线程池内部状态/死锁都不落盘——重启消失 = 典型的运行时状态问题，不是代码逻辑必现。

---

## 6. lab 实测（`DiagnosticsTest`，用代码演 jstack/MAT）

```text
busyThreadStackRevealsHotMethod → 热点线程 http-nio-auto-1-exec-1
                                  at DiagnosticsController.spin(:43)
deadlockDetectedByThreadMxBean  → Found 2 Java-level deadlock(s)（两 exec 线程互等 Object）
staticMapLeakGrows              → leakedMb=3 → clear → 0
Tests run: 3, Failures: 0
```

---

## 7. 术语对照（本课汇总）

| 术语 | 中文 | 一句话 |
|---|---|---|
| top %CPU（单核基准） | 进程/线程占用单个逻辑核的比例 | 多核可超 100%；判读除以 `nproc` |
| top -H | 按线程展示 | 定位高耗线程 id |
| jstack / `Thread.print` | 线程栈快照 | 死锁自动报告；连抓 3 次防误判 |
| jstat -gcutil / GCT | GC 统计 / GC 总耗时 | CPU 判型的第一步 |
| jmap -histo:live / heap_dump | 堆直方图 / 堆转储 | 谁多（快）/ 谁持有（准） |
| MAT 支配树 / Path to GC Roots | 内存分析 | 找最大保留集与引用链 |
| arthas | 诊断工具 | `thread -n` / `trace` / `watch`，即用即走 |
| JFR | 飞行记录 | 低开销；虚拟线程 pin 事件 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. `top` 显示 Java 进程 800%，是什么意思？怎么换算？**
> 参考答案：`top` 的 %CPU 以**单个逻辑核**为 100% 基准——800% ≈ 8 个核的算力被占用（8 核机器约跑满）。判读：进程值除以 `nproc` 得占总算力比；再用 `top -H` 定位到具体线程（转十六进制 → jstack `nid`）。

**Q2. CPU 飙高怎么区分是 GC 还是业务？**
> 参考答案：并行看 `jstat -gcutil`：`GCT` 占比高、`FGC` 频繁 → GC 型，去查内存；GC 正常而热点栈顶是应用方法 → 业务型；两者都排除才看 Tomcat 自身（Poller 是阻塞 `select`，非空轮询）。

**Q3. 内存泄漏怎么快速定位？**
> 参考答案：`jmap -histo:live` 看谁多（快）→ `jcmd dump` + MAT 支配树/Path to GC Roots 看"谁持有它"（准）。内嵌三大常客：ThreadLocal（池线程长活）、Session/静态集合、类加载器（注意 Tomcat 的两道防泄漏监听器仅独立部署语境，内嵌默认不挂）。

**Q4. 请求 hang 怎么判断是池满还是慢下游？**
> 参考答案：看 `busy` 与 jstack：`busy≈max` 且栈全在应用/下游调用 → 慢下游→池满；`busy` 不高但无响应 → 查连接闸门（LimitLatch/Acceptor 阻塞）、CLOSE_WAIT、死锁（jstack 直接报）。

**Q5. 为什么 hang 现场重启也慢？**
> 参考答案：优雅停机要等在途请求（`isActive` 一直为真）直到 phase 超时（默认 30s）才强停；排查期重启要有这个预期，赶时间可临时 `server.shutdown=immediate`。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：跑 `DiagnosticsTest`（栈采样定位热点 / 死锁检测 / 泄漏增长清理）；`jstack <pid> | grep -c Thread.State` 统计三种状态占比。
- 下一步：**L15 虚拟线程**——JDK21 虚拟线程在 Tomcat 里的行为变化（`tomcat-handler-*`、pin、池参数失效）。
