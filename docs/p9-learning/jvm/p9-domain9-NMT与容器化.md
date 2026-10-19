# p9-domain9 · NMT + 容器化 JVM（阶段3 生产实用）

> 补 JVM 生产实用：#7 NMT（堆外内存监控）+ #5 容器化 JVM。
> 实验目录：`experiments/jvm-diagnostics/15-p9-exec-engine/`（ThreadPanorama 长驻）

---

# 第一部分：NMT（Native Memory Tracking）

## 实测（JDK11 + `-XX:NativeMemoryTracking=summary`）

```
Total: reserved=1662505KB, committed=352597KB
  Java Heap (reserved=262144KB, committed=262144KB)     ← 堆 256MB
  Class (reserved=1056913KB, committed=5777KB)          ← Metaspace（730 类）
  Thread (reserved=19545KB, committed=909KB)            ← 19 线程栈
  Code (reserved=247740KB, committed=7604KB)            ← Code Cache
  GC (reserved=70277KB, committed=70277KB)              ← GC 数据结构
  Compiler (reserved=149KB, committed=149KB)            ← JIT 编译器
```

## NMT 机制

```
开启：-XX:NativeMemoryTracking=summary|detail（启动时，无法运行时开启）
查看：jcmd <pid> VM.native_memory summary|detail|baseline
对比：baseline → 运行一段时间 → summary.diff（看增长）

summary 模式：各区域总量（堆/元空间/线程/Code/GC/编译器）
detail 模式：每个分配点的调用栈（开销大，生产慎用）
```

## NMT 用途（域6 盲区 3 的诊断工具）

```
场景：Direct ByteBuffer 泄漏（off-heap 不在堆 dump）
  → jmap 看不到，NMT 能看到（堆外内存持续增长）
场景：线程栈泄漏（线程数暴涨）
  → NMT Thread 区域持续增长
场景：Code Cache 满
  → NMT Code 区域接近 ReservedCodeCacheSize
```

## NMT 开销

```
summary：~5-10% 性能开销（生产可接受）
detail：~10-15%（生产慎用，仅排障时开）
```

## NMT baseline 对比工作流（实测 #78）

```
# 1) 打基线
jcmd <pid> VM.native_memory baseline
→ Baseline succeeded

# 2) 运行一段时间后看增量
jcmd <pid> VM.native_memory summary.diff
→ Total: committed=349845KB -2752KB   ← 负值=GC 回收
→ Class malloc=145KB #918 -2           ← 变化量（分配/释放）
```

**用途**：定位堆外内存泄漏（哪块区域在持续增长）——比 `summary` 只看总量更精准。

## #79 cgroup v1 vs v2（实测）

```
检测：cat /sys/fs/cgroup/cgroup.controllers 存在 → cgroup v2
本机：→ cgroup v2（0::/user.slice/...）

JDK 支持：
  cgroup v1：读 /sys/fs/cgroup/memory/memory.limit_in_bytes
  cgroup v2：读 /sys/fs/cgroup/memory.max
  JDK11+ UseContainerSupport 同时支持两种（自动检测）
```

**P9 答法**："cgroup v1 用 `memory.limit_in_bytes`，v2 用 `memory.max`；JDK10+ UseContainerSupport 自动检测两种（本机 v2）。容器内存感知靠读 cgroup limit × MaxRAMPercentage。"

### P9 追问链

- **Q：NMT 怎么开？** → 启动时 `-XX:NativeMemoryTracking=summary`（无法运行时开启）
- **Q：NMT 看什么？** → 堆外内存（Direct Buffer/线程栈/Code Cache/Metaspace）
- **Q：NMT 开销？** → summary ~5-10%，detail ~10-15%
- **Q：和 jmap 区别？** → jmap 看堆内；NMT 看堆外+堆内全貌（含 native 分配）

---

# 第二部分：容器化 JVM

## flag 确认（JDK11 实测）

```
UseContainerSupport   = true    ← 默认开启（JDK10+）
MaxRAMPercentage      = 25.0    ← 默认堆 = 容器内存 × 25%
InitialRAMPercentage  = 1.5625  ← 初始堆 = 容器内存 × 1.56%
ActiveProcessorCount  = -1      ← 自动检测容器 CPU（-1=自动）
```

## 容器内存感知机制

```
JVM 启动 → UseContainerSupport=true → 读 cgroup 配置
  → /sys/fs/cgroup/memory/memory.limit_in_bytes（cgroup v1）
  → /sys/fs/cgroup/memory.max（cgroup v2）
  → MaxRAMPercentage × 容器内存 = 堆上限
  → ActiveProcessorCount = 容器 CPU 限制（cgroup cpu quota）
```

## 容器化 JVM 调优（K8s 生产）

```
# 内存（K8s limit=1Gi）
-XX:MaxRAMPercentage=75.0      ← 堆 = 75% 容器内存（留 25% 给 Metaspace/线程栈/堆外）
-XX:InitialRAMPercentage=75.0  ← 初始堆 = 75%（避免运行时伸缩）

# CPU（K8s limit=2）
-XX:ActiveProcessorCount=2     ← 显式指定（或自动检测）
-XX:ParallelGCThreads=2        ← GC 线程数
-XX:ConcGCThreads=1            ← 并发 GC 线程数

# 注意
-XX:+UseContainerSupport       ← 默认开（JDK10+），确认没关
-XX:+UseSerialGC               ← 单核容器可考虑（默认 G1）
```

## 容器内存 vs JVM 内存（P9 常考）

```
容器内存（K8s limit）
├── JVM 堆（-Xmx = MaxRAMPercentage × 容器内存）
├── Metaspace（~100-200MB）
├── 线程栈（每线程 ~1MB × 线程数）
├── Code Cache（~240MB）
├── GC 数据结构
├── Direct ByteBuffer（堆外）
└── JNI/native 内存

OOM Kill 场景：容器内存（JVM 之外的部分）超 limit → OS Kill
  → jmap 看不到（不在堆里）
  → NMT 能看到（堆外内存）
```

### P9 追问链

- **Q：容器里 JVM 怎么感知内存？** → UseContainerSupport 读 cgroup limit × MaxRAMPercentage
- **Q：K8s 里怎么配 JVM？** → MaxRAMPercentage=75（留 25% 给堆外）+ ActiveProcessorCount
- **Q：容器 OOM Kill 但堆没满？** → 堆外内存（Metaspace/线程栈/Direct Buffer）超 limit；用 NMT 排查
- **Q：MaxRAMPercentage 默认多少？** → 25%（生产建议 75%，留堆外空间）

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **NMT 机制** | 启动时 -XX:NativeMemoryTracking=summary；jcmd VM.native_memory 查看 | 实测 |
| **NMT 用途** | 堆外内存（Direct Buffer/线程栈/Code Cache/Metaspace） | 实测（6 区域） |
| NMT 开销 | summary ~5-10%；detail ~10-15% | 叙事 |
| NMT vs jmap | jmap 看堆内；NMT 看堆外+堆内全貌 | 叙事 |
| **NMT baseline 工作流** | baseline → 运行 → summary.diff 看增量（定位泄漏区域） | 实测 #78 |
| **cgroup v1/v2** | v1=memory.limit_in_bytes；v2=memory.max；JDK 自动检测 | 实测 #79 |
| **UseContainerSupport** | 默认 true（JDK10+），读 cgroup limit 感知容器内存 | flag 实测 |
| **MaxRAMPercentage** | 默认 25%；生产建议 75%（留堆外空间） | flag 实测 |
| 容器内存构成 | 堆+Metaspace+线程栈+Code Cache+GC+Direct Buffer+JNI | 叙事 |
| 容器 OOM Kill | 堆外超 limit（jmap 看不到，NMT 能看到） | 叙事（衔接域6 盲区3） |

## 相关文件

- NMT 实测：`ThreadPanorama.java` + `-XX:NativeMemoryTracking=summary`
- NMT 日志：`/data/workspace/arthas-output/nmt.log`
- 容器 flag：`-XX:MaxRAMPercentage/-XX:ActiveProcessorCount`（JDK11 实测确认）
