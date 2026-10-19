# JVM 诊断学习记录 · 总览

> 位置：`xhs-demo/docs/p9-learning/`（从 `experiments/jvm-diagnostics/学习记录/` 搬迁至此）
> 规则：每篇 = 知识点 + 证据（源码路径行号 / 实验）+ 可复制命令。不写散文，只收结论。

## 记录清单

| 编号 | 主题 | 状态 | 说明 |
|---|---|---|---|
| README | 总览（本文件） | ✅ | 导航 |
| agent-学习计划 | Agent 知识包计划 | ✅ | 进度/自测/下一步（随学习更新） |
| attach-01 | Attach 机制与协议 | ✅ | socket、懒启动、协议格式、10 条命令、load、客户端映射 |
| attach-02 | VM 操作 / STW / 卡死排查 | ✅ | VMThread::execute、safepoint、listener 排队、写响应分工、卡死三层、kill-3、OS 姿势 |
| tools-01 | 诊断五工具 | ✅ | jstack/jmap/jinfo/jcmd/jstat：用法、参数、底层命令、代价 |
| heap-01 | hprof 格式与生成 | ✅ | JAVA PROFILE 1.0.2、记录结构、:live 先 full GC、解析器 |
| heap-02 | OOME 自动 dump | ✅ | 开关三件套、命名、退出码、容器注意 |
| mat-01 | MAT 使用四步法 | ✅ | Histogram/Dominator/Path/Unreachable、OomDemo 案例 |
| agent-01 | Java agent 本体 | ✅ | agentmain/premain、MANIFEST、Instrumentation、load 实操 |
| agent-02 | transformer 与字节码 | ✅ | 钩子、ASM、事件链、retransform/redefine、限制、撤销 |
| agent-03 | native 与 JVMTI | ✅ | Agent_OnLoad/OnAttach、capabilities、事件、libinstrument |
| arthas-01 | Arthas 全景 | ✅ | 通道/接口/能力、37 学习点、源码验证地图 |
| arthas-02 | Arthas 实操记录 | ✅ | attach/dashboard/watch/trace/tt/retransform/profiler/mc+redefine/stop/G1-G5 |
| arthas-03 | 排障实战 Drill | ✅ | 端到端：三问题靶子 → 决策树 0-9 → OOME dump 515MB 现场分析 |
| jmx-jfr-01 | JMX 深入 | ✅ | 自定义 MBean、远程客户端、GC 通知、远程认证双文件 |
| jmx-jfr-02 | JFR 深入 | ✅ | 事件模型、自定义 settings、自定义事件 API、streaming |
| jmx-jfr-03 | 分工决策 | ✅ | JMX/JFR/Arthas 三工具决策表 + 决策树 |
| p9-domain1 | P9 对象与内存 | ✅ | 对象布局/TLAB/逃逸分析/压缩指针/humongous（含 review 修正） |
| p9-domain2 | P9 类加载 | ✅ | 双亲委派/自定义loader/热部署/初始化时机/Tomcat隔离 |
| p9-domain3 | P9 GC与G1 | ✅ | 并发周期/Mixed GC+CSet/晋升/RSet/三色+SATB/调优/#1-#40 全补 |
| p9-domain4 | P9 JIT与去优化 | ✅ | 分层编译/OSR/内联/去优化触发 |
| p9-domain5 | P9 线程与锁（JVM 层） | ✅ | 线程模型/锁膨胀(mark word)/安全点/虚拟线程/JMM |
| p9-juc | P9 并发编程 JUC（独立轨道） | ✅ | AQS/CAS+ABA/ThreadLocal/线程池/并发容器（Java SE 层，非 JVM） |
| p9-domain6 | P9 诊断调优方法论 | ✅ | 工具失效逃生通道/6个P9盲区/4案例 |
| p9-domain7 | P9 解释器与启动流程 | ✅ | 字节码/栈执行/模板解释器/JVM启动链路 |
| p9-domain7-补充 | P9 解释器与启动深挖 | ✅ | 方法调用5种/异常字节码/线程全景/Shutdown/vtable/invokedynamic |
| p9-domain8 | P9 ZGC与JNI | ✅ | ZGC染色指针/读屏障/亚毫秒停顿实测/JNI引用类型/Critical |
| p9-domain8-补充 | P9 ZGC与JNI深挖 | ✅ | ZGC分代(JDK21)/颜色位/ZGC vs Shenandoah/JNI注册/缓存/线程安全/Panama趋势 |
| p9-domain9 | P9 NMT与容器化 | ✅ | NMT堆外监控/UseContainerSupport/MaxRAMPercentage/容器内存构成 |
| p9-domain10 | P9 参数与趋势 | ✅ | 参数8大类速查/CDS/AOT/GraalVM/趋势（JVM轨道收官） |
| p9-net-domain1 | 网络域1 网络基础 | ✅ | 分层封装/ARP/路由/ICMP/MTU分片/VLAN/IPsec/延迟拆解 |
| p9-net-domain2 | 网络域2 TCP连接管理 | ✅ | 三次握手/四次挥手/状态机/TIME_WAIT/SYN cookies（tcpdump实测） |
| p9-net-domain3 | 网络域3 TCP流控与拥塞 | ✅ | 滑动窗口/慢启动/Cubic vs BBR/零窗口/TFO（sysctl实测） |
| p9-net-domain4 | 网络域4 UDP与应用层 | ✅ | UDP/DNS/TLS/HTTP2/HTTP3/WebSocket（dig/curl实测） |
| p9-net-domain5 | 网络域5 内核收发包 | ✅ | TSO/GRO/RPS/收发包路径/socket缓冲（ethtool实测） |
| p9-net-domain6 | 网络域6 IO与epoll | ✅ | 阻塞非阻塞/epoll/select-poll对比/LT-ET/惊群/Reactor（Java NIO实测） |
| p9-net-domain7 | 网络域7 Socket编程与调优 | ✅ | SO_REUSEADDR/NODELAY/域套接字/多播/内核调优参数 |
| p9-net-domain8 | 网络域8 零拷贝与高性能 | ✅ | mmap/sendfile/DPDK-RDMA-XDP/TCP内存/fd端口约束 |
| p9-net-domain9 | 网络域9 网络排障 | ✅ | tcpdump过滤器/ss排障/监控指标/工具失效逃生 |
| p9-net-domain10 | 网络域10 容器网络与K8s | ✅ | veth/bridge/Docker网络/CNI/Service/负载均衡（实测） |
| p9-net-domain11 | 网络域11 SDN与VPC控制面 | ✅ | SDN架构/OpenFlow/VPC模型/BGP EVPN/Neutron/安全组（集大成） |
| p9-os-domain1 | OS域1 内核与用户态 | ✅ | 系统调用/vDSO/proc文件系统/sysfs/dmesg（strace实测） |
| p9-os-domain2 | OS域2 内存管理 | ✅ | 虚拟内存/分页/缺页/COW/伙伴系统/slab/OOM/大页/Swap/kswapd（实测） |
| p9-os-domain3 | OS域3 进程与线程 | ✅ | 进程状态/上下文切换/CFS调度/信号（/proc实测） |
| p9-os-domain4 | OS域4 同步原语 | ✅ | 自旋锁/互斥锁/内存屏障/RCU/死锁（衔接JVM/JUC） |
| p9-os-domain5 | OS域5 进程间通信 | ✅ | 管道/共享内存/信号量/eventfd/ipcs（实测） |
| p9-os-domain6 | OS域6 文件系统 | ✅ | VFS/inode/日志/COW/崩溃一致性/overlay（df/mount实测） |
| p9-os-domain7 | OS域7 中断与IO | ✅ | top half/bottom half/中断上下文/workqueue（/proc interrupts实测） |
| p9-os-domain8 | OS域8 多核与NUMA | ✅ | NUMA/缓存层次/MESI/内存屏障/per-CPU（numactl实测） |
| p9-os-domain9 | OS域9 虚拟化与容器 | ✅ | Namespace/cgroups v1v2/KVM/容器原理（实测） |
| p9-mysql-domain1 | MySQL域1 InnoDB架构与线程模型 | ✅ | 线程模型(vs Tomcat)/InnoDB后台线程/Buffer Pool/Change Buffer（Docker实测） |
| p9-mysql-domain2 | MySQL域2 事务与并发控制 | ✅ | 隔离级别/MVCC/行锁/死锁/MDL（Docker实测+死锁日志） |
| p9-mysql-domain3 | MySQL域3 索引原理 | ✅ | B+Tree/聚簇vs二级/回表/EXPLAIN/索引失效（Docker实测） |
| p9-mysql-domain4 | MySQL域4 Redo与Undo与WAL | ✅ | WAL/LSN/Group Commit/Undo/ARIES（SHOW ENGINE实测） |
| p9-mysql-domain5 | MySQL域5 查询优化 | ✅ | 优化器/EXPLAIN FORMAT=TREE/慢查询/子查询（Docker实测） |
| p9-mysql-domain6 | MySQL域6 复制与高可用 | ✅ | binlog/GTID/并行复制/故障切换/备份（Docker实测） |
| p9-mysql-domain7 | MySQL域7 性能监控与诊断 | ✅ | Performance Schema/sys/锁监控/慢日志（Docker实测） |
| p9-mysql-domain8 | MySQL域8 连接管理与中间件 | ✅ | 连接池/ProxySQL/连接数优化（Docker实测） |
| p9-mysql-domain9 | MySQL域9 存储引擎与分布式 | ✅ | LSM vs B+Tree/CDC/HTAP/CAP/分库分表/选型 |

## 相关位置

- 实验代码：`experiments/jvm-diagnostics/`（01-attach-basics、02-commands、03-java-agent、04-bytecode、05-native-agent、06-bootstrap、07-jmx、08-drill、playground）
- 可复用沙箱：`xhs-demo/agent-lab/`（Maven 模块，包 `com.myxhs.demo.agent`）
- 工具安装：`/data/workspace/tools/mat/`、`/data/workspace/tools/arthas/`、`/data/workspace/tools/arthas-bin.zip`
- 源码：`/data/workspace/openjdk11u/src`（HotSpot 11.0.32）、`source-code/arthas`（tag 4.3.5）
- 其他轨道：confucius-commons（`docs/openjdk/vol-microsphere/`）、Tomcat（`docs/plan/book/正文/vol-tomcat/`，迁移待办）
