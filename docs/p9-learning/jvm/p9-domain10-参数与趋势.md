# p9-domain10 · 参数系统 + 趋势（阶段4 收口）

> JVM 轨道最后一块：#6 参数系统整理 + #8 GraalVM/AOT/CDS 趋势。
> 把散落在 9 个域的参数按域归类，形成生产参数速查。

---

# 第一部分：JVM 参数系统（按域归类）

## 生产基线（全轨道通用）

```
# 内存（铁律：Xms=Xmx）
-Xms512m -Xmx512m
-XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m
-XX:MaxDirectMemorySize=256m

# GC（G1 生产默认）
-XX:+UseG1GC -XX:MaxGCPauseMillis=200
-XX:+AlwaysPreTouch

# 诊断
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/data/workspace/heapdump
-Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=10m

# 容器（K8s）
-XX:MaxRAMPercentage=75.0
-XX:ActiveProcessorCount=2
-XX:+UseContainerSupport
```

## 参数分类速查（按域）

### 内存域（域1）

| 参数 | 默认 | 作用 |
|---|---|---|
| `-Xms` / `-Xmx` | 物理内存 1/4 | 初始/最大堆（生产 Xms=Xmx） |
| `-XX:MetaspaceSize` | 21MB | Metaspace 初始大小 |
| `-XX:MaxMetaspaceSize` | 无限 | Metaspace 上限（生产设限防泄漏） |
| `-XX:MaxDirectMemorySize` | =Xmx | Direct ByteBuffer 上限 |
| `-XX:NewRatio` | 2 | 老年代:新生代（G1 不用，自适应） |
| `-XX:SurvivorRatio` | 8 | Eden:Survivor（G1 不用） |
| `-XX:PretenureSizeThreshold` | 0 | 大对象直接进老年代（G1 用 humongous） |

### GC 域（域3）

| 参数 | 默认 | 作用 |
|---|---|---|
| `-XX:+UseG1GC` | JDK9+ 默认 | G1 收集器 |
| `-XX:MaxGCPauseMillis` | 200 | 停顿目标（预测模型输入） |
| `-XX:InitiatingHeapOccupancyPercent` | 45 | 并发标记触发阈值 |
| `-XX:G1HeapWastePercent` | 5 | Mixed GC 触发阈值 |
| `-XX:G1HeapRegionSize` | 自适应 | Region 大小（2 的幂，1MB-32MB） |
| `-XX:MaxTenuringThreshold` | 15 | 晋升年龄（4bit 最大值） |
| `-XX:G1MixedGCCountTarget` | 8 | 一次周期后 Mixed GC 轮数 |
| `-XX:G1ReservePercent` | 10 | 预留空间防晋升失败 |
| `-XX:+ParallelRefProcEnabled` | false | 并行处理引用 |
| `-XX:+DisableExplicitGC` | false | 禁 System.gc()（生产建议开） |
| `-XX:+ExplicitGCInvokesConcurrent` | false | System.gc() 触发并发 GC |

### JIT 域（域4）

| 参数 | 默认 | 作用 |
|---|---|---|
| `-XX:+PrintCompilation` | false | 打印编译日志（诊断用） |
| `-XX:CompileThreshold` | 10000 | 解释器→C1 调用计数阈值 |
| `-XX:MaxInlineSize` | 325 | 内联最大字节码 |
| `-XX:FreqInlineSize` | 325 | 热点内联最大字节码 |
| `-XX:ReservedCodeCacheSize` | 240m | Code Cache 上限 |

### 线程域（域5）

| 参数 | 默认 | 作用 |
|---|---|---|
| `-Xss` | 1MB | 线程栈大小 |
| `-XX:ActiveProcessorCount` | -1(自动) | CPU 核数（容器/指定） |
| `-XX:ParallelGCThreads` | CPU 核数 | GC 并行线程数 |
| `-XX:ConcGCThreads` | CPU/4 | 并发 GC 线程数 |

### 诊断域（域6/9）

| 参数 | 默认 | 作用 |
|---|---|---|
| `-XX:+HeapDumpOnOutOfMemoryError` | false | OOM 自动 dump |
| `-XX:HeapDumpPath` | java_pid<pid>.hprof | dump 路径 |
| `-XX:NativeMemoryTracking` | off | NMT（summary/detail） |
| `-XX:+PrintFlagsFinal` | false | 打印所有参数最终值 |
| `-XX:+UnlockDiagnosticVMOptions` | false | 解锁诊断参数 |
| `-XX:+UnlockExperimentalVMOptions` | false | 解锁实验参数 |

### 容器域（域9）

| 参数 | 默认 | 作用 |
|---|---|---|
| `-XX:+UseContainerSupport` | true | 容器内存/CPU 感知 |
| `-XX:MaxRAMPercentage` | 25 | 堆 = 容器内存 × %（生产 75） |
| `-XX:InitialRAMPercentage` | 1.56 | 初始堆 = 容器内存 × % |
| `-XX:ActiveProcessorCount` | -1 | 容器 CPU（自动检测） |

### ZGC 域（域8）

| 参数 | 默认 | 作用 |
|---|---|---|
| `-XX:+UseZGC` | false | ZGC（JDK15+ 生产） |
| `-XX:+ZGenerational` | JDK21+ | 分代 ZGC |
| `-XX:ZAllocationSpikeTolerance` | 1 | 分配尖峰容忍度 |
| `-XX:ZProactive` | true | 主动 GC |
| `-XX:ZUncommitDelay` | 300 | 内存归还 OS 延迟 |

### 安全/其他

| 参数 | 默认 | 作用 |
|---|---|---|
| `-XX:+DisableExplicitGC` | false | 禁 System.gc()（防误触 Full GC） |
| `-XX:+UseStringDeduplication` | false | G1 字符串去重 |
| `-XX:+UseLargePages` | false | 大页（减少 TLB miss） |
| `-XX:+UseNUMA` | false | NUMA 感知 |

## P9 参数追问链

- **Q：生产最重要的 3 个参数？** → Xms=Xmx（防伸缩）/ MaxGCPauseMillis（停顿目标）/ HeapDumpOnOutOfMemoryError（取证）
- **Q：容器里最容易配错的？** → MaxRAMPercentage（默认 25% 太小，生产 75%）+ 忘配 ActiveProcessorCount
- **Q：防 System.gc 误触？** → `-XX:+DisableExplicitGC`（生产建议开）
- **Q：NMT 怎么开？** → 启动时 `-XX:NativeMemoryTracking=summary`（无法运行时开）
- **Q：怎么查参数默认值？** → `java -XX:+PrintFlagsFinal -version | grep <参数>`

---

# 第二部分：GraalVM / AOT / CDS 趋势

## CDS（Class Data Sharing，类数据共享）

### flag 实测（JDK11）

```
UseSharedSpaces = false    ← 默认关
SharedArchiveFile = (空)   ← CDS 归档文件
UseAppCDS（JDK12+）        ← 应用类也可共享
```

### 机制

```
问题：JVM 启动时加载/验证/解析类慢（几百个 JDK 类）
解法：CDS 把类的元数据（字节码/常量池）预处理成归档文件
  → 启动时 mmap 加载（免解析/验证）
  → 启动加速 10-30%
  → 内存共享（多 JVM 进程共享同一份类元数据）

用法：
  1. 生成归档：java -Xshare:dump -XX:SharedArchiveFile=app.jsa -cp app.jar
  2. 使用归档：java -Xshare:on -XX:SharedArchiveFile=app.jsa -cp app.jar
  JDK12+ AppCDS：应用类也可共享（闭合的类集合）
```

### P9 答法

"CDS：类元数据预处理成归档，启动 mmap 加载（免解析/验证），启动加速 10-30%，多 JVM 共享内存。JDK12+ AppCDS 支持应用类共享。容器/Serverless 冷启动优化常用。"

## AOT（Ahead-of-Time，提前编译）

### flag 实测

```
UseAOT = false      ← 默认关
AOTLibrary = (空)   ← AOT 编译产物（.so）
```

### 机制

```
问题：JIT 编译耗 CPU（启动期慢）
解法：AOT 提前把字节码编译成机器码（.so）
  → 启动时直接加载（免 JIT 编译）
  → 启动加速，但牺牲运行时优化（无法 profile）

用法：
  jaotc --output app.so -cp app.jar com.example.Main   ← JDK9-16
  JDK17+：AOT 部分被 GraalVM/Project Leyden 取代
```

### P9 答法

"AOT 提前编译字节码成机器码（.so），启动免 JIT。但牺牲运行时优化（无 profile）。JDK17+ AOT 被 GraalVM/Project Leyden 演进。容器冷启动场景可用。"

## GraalVM

### 定位

```
GraalVM = 多语言 VM + Graal JIT 编译器
  1. Graal JIT：用 Java 写的 JIT 编译器（替代 C2）
     -XX:+UseJVMCICompiler（JVMCI 接口）
     - 更激进优化（部分场景比 C2 快）
     - JDK21 可用（-XX:+UseJVMCICompiler）
  2. 多语言：Truffle 框架跑 JS/Python/Ruby/LLVM 等
  3. Native Image：AOT 编译成原生可执行文件（免 JVM 启动）
     - GraalVM Native Image（Serverless/CLI 常用）
     - 启动毫秒级，但牺牲部分 Java 特性（动态类加载等）
```

### P9 答法

"GraalVM：Graal JIT（Java 写的 JIT，部分场景优于 C2）+ 多语言（Truffle）+ Native Image（AOT 原生可执行）。Native Image 适合 Serverless/CLI（启动毫秒级），但牺牲动态特性。"

## Project Leyden（JDK 演进中）

```
JDK 内置的 AOT 优化（GraalVM Native Image 的 JDK 内化）
  → 预生成 profile/编译产物
  → 启动加速（类似 CDS+AOT 结合）
  → JDK 22+ 演进中（JEP 跟踪）
```

## 趋势总结（P9 问趋势）

```
启动优化三件套：
  CDS（类共享，JDK9+）→ AOT（提前编译，JDK9-16）→ GraalVM Native Image（原生，JDK17+）
  
演进方向：
  1. 启动加速（CDS→AppCDS→Leyden）
  2. 内存效率（ZGC 分代/Shenandoah/容器感知）
  3. 并发能力（虚拟线程/Loom，域5 已覆盖）
  4. 替代 JNI（Panama/FFM，域8 已覆盖）

P9 答法："JVM 趋势：启动优化（CDS/AOT/Native Image）、低延迟 GC（ZGC 分代）、高并发（虚拟线程）、内存效率（容器感知）、替代 JNI（FFM）。生产选型看场景：长生命周期服务用 G1/ZGC+JIT；Serverless/CLI 用 Native Image。"
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **生产基线参数** | Xms=Xmx/MaxGCPauseMillis/HeapDumpOnOOM/DisableExplicitGC | 第一部分 |
| **参数按域归类** | 内存/GC/JIT/线程/诊断/容器/ZGC 8 大类速查表 | 第一部分 |
| **容器参数** | MaxRAMPercentage=75/ActiveProcessorCount/UseContainerSupport | 第一部分 |
| **CDS** | 类元数据预处理归档，启动加速 10-30%，多 JVM 共享 | flag 实测 |
| **AOT** | 提前编译字节码成 .so，启动免 JIT（牺牲运行时优化） | flag 实测 |
| **GraalVM** | Graal JIT + 多语言 + Native Image（原生可执行） | 叙事 |
| **趋势** | 启动优化（CDS/AOT/Native Image）/低延迟 GC/虚拟线程/FFM | 叙事 |

## 相关文件

- 参数速查：本文档第一部分（8 大类）
- CDS/AOT flag：JDK11 实测（UseSharedSpaces/UseAOT 默认关）
- 趋势：GraalVM/Leyden（JDK 演进）
