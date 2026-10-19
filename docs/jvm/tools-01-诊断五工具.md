# tools-01 · 诊断五工具（jstack / jmap / jinfo / jcmd / jstat）

> 进度：命令表全部讲完（jstack → jmap → jinfo → jcmd → jstat）。
> 实验进程示例 PID 会变（如 275230）：一律先 `jps -l` 复核。

## jstack：线程快照

- 用法：`jstack [-l][-e] <pid>`；底层 = attach `threaddump`
- 服务端：`arg(0)` 逐字符扫描 'l'/'e'，然后跑 3 个 VM op：
  `VM_PrintThreads`（线程）+ `VM_PrintJNI`（尾部 JNI 统计）+ `VM_FindDeadlocks`（死锁段）
- 输出 5 段：时间戳 / JVM 版本 / Threads class SMR info（JVM 内部，无视）/
  线程块 / 尾部 `JNI global refs`（+ 有死锁时 `Found one Java-level deadlock` 段）
- 线程头逐字段：`"main" #1 prio os_prio cpu elapsed tid nid state [栈地址]`
  - `cpu << elapsed` = 大多在等，不在算
  - **仅 `-e`**：`allocated=`（累计分配字节，TLAB 计数器）、`defined_classes=`
    （该线程定义过的类数：每次 `KlassFactory::create_from_stream` 由执行定义的线程 +1）
  - `nid=0x…` = **OS 原生线程 id（16 进制）** ↔ `top -H -p <pid>`（十进制 TID）对照找 CPU 热点
- 状态词：RUNNABLE / BLOCKED（等锁）/ WAITING（无限等）/ TIMED_WAITING（限时等）
- 锁行：`- waiting to lock <0x…>` / `- locked <0x…>`；`-l` 每个线程块多 `Locked ownable synchronizers`
- 实测：`-l` 输出 89→125 行；`-e` 只改线程头一行
- 实战：死锁看 `Found one Java-level deadlock`（DeadlockDemo：worker-1/worker-2 交叉持锁实测）；
  卡住 = 间隔 5s 抓 2~3 次对比同一栈是否不动；CPU 高 = top -H + nid 对照
- 风险：**3 次 safepoint 的短停 + 全量栈打印**；别高频/循环抓；只能看 Java 层
- 坑：瞬时快照（可能错过）；attach 约束（同用户 + /tmp socket，容器内执行）

## jmap：堆操作（分档收费）

- 用法（JDK11 官方 `-h`）：`-clstats` / `-finalizerinfo` / `-histo[:live]` / `-dump:<options>`
- **JDK11 移除了 `-heap`/`-F`**：`jmap -heap` 报 `Error: -heap option used` → 改用 `jcmd <pid> GC.heap_info`；JDK11 无 `jhat`（JDK9 移除，分析用 MAT）
- `-histo`：列 `num #instances #bytes class name (module)`；
  类型记号：`[B`=byte[]、`[I`=int[]、`[C`=char[]、`[Ljava.lang.Object;`=Object[]
- `-histo:live` / `-dump:live`：**先 full GC**（GC cause 分别为 `_heap_inspection` / `_heap_dump`）
- `-dump`：`format=b file=`；jmap 会把路径 canonicalize（文件由目标进程写，注意容器路径视角）
- 底层映射（`JMap.java`）：`-histo` → `inspectheap`（args: `-live`/`-all`，`-parallel=<n>` 默认 3/8 CPU）；
  `-dump` → `dumpheap`（args: filename, `-live`/`-all`）；
  `-clstats` → `jcmd GC.class_stats`；`-finalizerinfo` → `jcmd GC.finalizer_info`
- 源码注释：`inspectheap` ≠ `jcmd GC.class_histogram`、`dumpheap` ≠ `jcmd GC.heap_dump`（两套实现）
- 默认值注意：**jmap 默认发 `-all`**（`-dump` 不带 live = 全量含垃圾）；`attach dumpheap` 缺省才是 live=true
- clstats 列：InstBytes/KlassBytes/CpAll/MethodCount/Bytecodes/MethodAll/ROAll/RWAll/Total/ClassName
- 代价：histo 轻（1 次 safepoint 不 GC）/ `:live` 附带 full GC / dump 全程 STW（GB 堆秒~分钟）
- 实测：RandomLoop 上各命令 wall time 266~320ms（主因是 attach + 工具自身启动，小堆 STW 可忽略）

## jinfo：看配置 + 动态改 flag

- 用法：`-flag <name>`（打印值）/ `-flag [+|-]<name>`（开关）/ `-flag <name>=<value>`（设值）/
  `-flags`（全部）/ `-sysprops`（系统属性）/ 无选项（两者都打）
- 底层：`properties` / `printflag` / `setflag`（`JInfo.java:146-156`：`+X` 传 `"1"`，`-X` 传 `"0"`）
- **只能改 manageable 的**：`java -XX:+PrintFlagsFinal -version | grep manageable`
  （HeapDumpOnOutOfMemoryError、HeapDumpPath、MaxHeapFreeRatio、PrintClassHistogram…）
- 实测：`jinfo -flag +HeapDumpOnOutOfMemoryError <pid>` 对活进程开启 OOME 自动 dump
- 坑：`HeapDumpBeforeFullGC/AfterFullGC` 能改但生产禁用（每次 full GC 都 dump）

## jcmd：诊断总入口

- 用法：`jcmd <pid|主类> <command …|PerfCounter.print|-f file>`；`jcmd -l`；无参数 = 列 JVM；
  `pid 0` = 发给所有 Java 进程（重命令慎用）；主类名可部分匹配
- `jcmd <pid> help`：约 40+ 命令，分 6 门类：GC.* / Compiler.* / VM.* / JFR.* / JVMTI.* / ManagementAgent.*，另有 Thread.print、PerfCounter.print、help
- 底层：attach `jcmd` → `DCmd::parse_and_execute(...op->arg(0)...)`；
  每个子命令 = 一个 DCmd 类（`HeapDumpDCmd`、`ThreadDumpDCmd`…），经 `DCmdFactory` 注册
- 每条命令自带 Impact（代价）/ Permission / Syntax：`help GC.heap_dump` 可见
  `Impact: High…Request a full GC unless the '-all' option is specified`
- 与专用工具映射：Thread.print ≈ jstack；
  GC.class_histogram ≠ jmap -histo；**GC.heap_dump 默认 live（和 jmap 默认 -all 相反）**；
  VM.flags/VM.set_flag ≈ jinfo；VM.system_properties ≈ jinfo -sysprops
- 常用：`help | grep`、`VM.command_line`（看启动参数）、`VM.info`（一页纸全信息）、
  `GC.heap_info`（代替已移除的 jmap -heap）
- 坑：同等 attach 约束；`VM.native_memory` 需启动加 `-XX:NativeMemoryTracking=summary`

## jstat：零成本仪表盘（唯一不 attach 的）

- 用法：`jstat <option> <pid> [interval [count]]`；
  选项：-class -compiler -gc -gccapacity -gccause -gcmetacapacity -gcnew -gcnewcapacity
  -gcold -gcoldcapacity -gcutil -printcompilation
- 数据源：`/tmp/hsperfdata_<user>/<pid>`（mmap 共享内存，JVM 自己更新计数器）；
  HotSpot 侧：`perfMemory.cpp` / `perfData.cpp`；JDK11 无此文件会报 not available（`-XX:+PerfDisableSharedMem` 时）
- 所以：**不 attach、不发命令、不 safepoint、不 STW**（高频采样如 100ms 也安全）；
  同数据另一出口：`jcmd <pid> PerfCounter.print`
- 推论：JVM 卡死时 jstat 仍可读（但计数器停更）；读到的是"停滞前的最后值"
- `-gc` 列（KB/秒，**全是自启动累计值**，频率要两次采样相减）：
  S0C/S1C survivor 容量，S0U/S1U 使用；EC/EU Eden；OC/OU Old；
  MC/MU Metaspace；CCSC/CCSU 压缩类空间；
  YGC/YGCT 年轻代次数/耗时；FGC/FGCT（>0 持续增长=危险）；CGC/CGCT（G1 并发周期）；GCT 总耗时
- `-gccause`：百分比 + `LGCC`（上次 GC 原因）/ `GCC`（当前）；`-class`：Loaded/Unloaded；
  `-compiler`：Compiled/Failed/Time
- 坑：只能本机同用户（读本机 /tmp，容器内执行）；列随 GC 器/版本变；看不到"谁在制造垃圾"（那要 hprof）

## 一句话总结

- jstack = 3 个 safepoint 的短停 + 全量栈打印
- jmap = 分档收费：histo 轻 / `:live` 附带 full GC / dump 全程 STW
- jinfo = 不重启改 JVM 配置的逃生通道（只限 manageable）
- jcmd = 总线（40+ 命令，DCmd 框架，Impact 预告代价）
- jstat = 只读计数器仪表盘，零成本
