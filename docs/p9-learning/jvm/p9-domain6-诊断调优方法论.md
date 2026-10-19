# p9-domain6 · 诊断调优方法论（P9 收官）

> P9 面试核心域 6/6。整合两条诊断轨道实操 + **工具失效逃生通道**（P9 核心盲区）。
> 核心命题：**jstack 依赖 attach→VMThread→SafePoint；VMThread 卡死时 jstack 也卡死，怎么办？**

---

## 核心盲区：工具链失效（P9 必问）

### jstack 的真实执行链路（源码实证）

```
jstack <pid>
  → Attach API 连接
  → attach listener 线程收命令
  → VMThread::execute(&op)   ← attachListener.cpp:185 投递给 VMThread
  → VMThread 需要 SafePoint（STW）
  → 输出线程栈
```

**致命依赖**：jstack 自己依赖 VMThread + SafePoint。VMThread 卡死 → jstack 也卡死。

### VMThread 卡死的三种场景

| 场景 | 卡在哪 | jstack 表现 |
|---|---|---|
| GC 卡死 | VMThread 在 GC 循环出不来 | jstack 挂住 |
| SafePoint 卡死 | 某线程无法到达 SafePoint（JNI 临界区） | jstack 挂住（等 SafePoint） |
| VM 内部锁 | VMThread 等 VM 内部锁（Heap_lock 等） | jstack 挂住 |

### 逃生通道（OS 层，绕过 JVM）★ P9 核心答案

```bash
# ① kill -3（SIGQUIT，走信号处理器，不走 attach→VMThread）
kill -3 <pid>          # 输出到 stdout/stderr

# ② gdb OS 级线程栈（完全绕过 JVM，读内核线程栈）★ 最后防线
gdb -p <pid> -batch -ex "thread apply all bt" 2>&1

# ③ /proc 内核态信息（不依赖 JVM）
cat /proc/<pid>/task/*/stack    # 内核栈
cat /proc/<pid>/status          # 线程数/状态
cat /proc/<pid>/wchan           # 当前阻塞点
ls /proc/<pid>/task | wc -l     # 线程数

# ④ Core dump（离线分析，不依赖活进程）
gcore <pid>                     # 或 kill -ABRT <pid>
jhsdb jstack --core <file> --exe <java>   # 离线分析

# ⑤ perf（OS 级 CPU 采样）
perf top -p <pid>
perf record -g -p <pid> -- sleep 10
```

### 排障决策树（工具失效版）

```
JVM 卡住
├── jstack/jcmd（走 attach→VMThread）
│   ├── 有响应 → 正常排查
│   └── 卡住（超时） → VMThread/SafePoint 卡死
│       ↓
├── kill -3（信号处理器，不走 VMThread）
│   ├── 有输出 → 看线程栈
│   └── 无输出 → JVM 更深卡死
│       ↓
├── gdb -p <pid>（OS 级，绕过 JVM）★ 最后防线
│   → 看 VMThread 在哪、哪些线程在跑
│       ↓
├── gcore + jhsdb（离线分析）
│   → core dump + jhsdb 离线分析
│       ↓
└── /proc + perf（内核态）
    → 内核栈 + CPU 采样
```

---

## 补充：其他 P9 盲区（工具失效同类）

### 盲区 1：SafePoint 卡死（JNI 临界区）

```
场景：线程进入 JNI Critical Region（GetPrimitiveArrayCritical）
  → 该线程无法到达 SafePoint
  → 所有需要 SafePoint 的操作（GC/jstack/JIT）全部卡住
  → 整个 JVM 冻结（不是单线程，是全局）

诊断：jstack 看不到（也卡住）→ gdb 看哪些线程在 JNI
修复：查代码里 JNI 调用是否持锁过久；-XX:+CriticalJNINatives（JDK 改进）
```

### 盲区 2：Code Cache 满（JIT 失效）

```
场景：JIT 编译产物（nmethod）占满 Code Cache
  → 编译失败 → 退化到解释器 → 性能骤降 10-100x
  → 日志：CodeCache: overflow / JIT compilation failed

诊断：jcmd Compiler.codecache / -XX:+PrintCodeCache
调优：-XX:ReservedCodeCacheSize（默认 240M，可调大）
     -XX:+UseCodeCacheFlushing（满时自动刷）
```

### 盲区 3：Direct ByteBuffer 泄漏（off-heap）

```
场景：Netty/NIO DirectByteBuffer 泄漏
  → 堆内存正常（dump 看不到），但进程内存暴涨 → OS OOM Kill
  → jmap 看不到（off-heap 不在堆里）

诊断：
  1. jcmd VM.native_memory summary（需 -XX:NativeMemoryTracking=summary 启动）
  2. -XX:MaxDirectMemorySize 限制
  3. Netty: -Dio.netty.leakDetection.level=PARANOID
修复：代码确保 release（ReferenceCountUtil.release）
```

### 盲区 4：Classloader 泄漏（Metaspace OOM）

```
场景：Web 应用反复部署（热部署）
  → 每次部署新 ClassLoader，旧 ClassLoader 未卸载
  → Metaspace 持续增长 → OOM: Metaspace

诊断：
  1. jcmd VM.classloader_hierarchy（看加载器层级/数量）
  2. jmap -histo 看 Class 对象数量
  3. GC 日志 Metaspace 趋势
修复：确保 webapp ClassLoader 可被 GC（无静态引用泄漏）
     -XX:MaxMetaspaceSize 限制 + 告警
```

### 盲区 5：Native Crash（SIGSEGV/SIGBUS）

```
场景：JVM 自身/ JNI 代码段错误
  → 进程直接崩溃（不是 Java 异常）
  → 生成 hs_err_pid<tid>.log

诊断：分析 hs_err_pid.log：
  1. "Problematic frame"：崩溃在哪（C++ 函数/so）
  2. "Java frames"：崩溃时的 Java 调用栈
  3. "Registers"：寄存器状态
  4. "Dynamic libraries"：加载的 so
修复：升级 JDK（native bug）、检查 JNI 代码、检查第三方 so
```

### 盲区 6：多 JVM 进程 attach 错误

```
场景：机器上多个 Java 进程
  → jps 看到多个 pid
  → attach 错进程 → 排查错对象

诊断：
  1. jps -l：看 pid + 主类名
  2. ps -ef | grep java：看完整启动命令
  3. /proc/<pid>/cmdline：精确匹配
修复：attach 前用 jps -l 确认 pid + 主类名
```

---

## 案例叙事（P9 高频，整合诊断轨道实操）

### 案例 1：OOM 排查（完整决策树）

```
症状：应用变慢/OOM 崩溃
1. jstat -gcutil -t 5：看 Eden 爬升/O 满/FullGC 频率 → 定方向
2. jcmd GC.heap_info：看堆结构
3. jmap -histo | head：看对象大户（byte[]/String/自定义类）
4. MAT 分析 dump：Dominator Tree → Path to GC Roots → 找泄漏源
5. 修复：断源（停泄漏/改代码）→ 验证（histo 回落）
教训：清数据不停源头=白清（drill 实战验证）
```

### 案例 2：CPU 高排查

```
症状：CPU 100%
1. top -Hp <pid>：找高 CPU 线程（tid）
2. printf '%x' <tid>：转 16 进制
3. jstack <pid> | grep <tid16>：找线程栈
4. Arthas thread -n 3 / profiler：交叉验证
5. 修复：死循环/正则回溯/GC 过频
教训：瞬时 %CPU 可能骗人（drill 实测 hot-burner sleep 中显示 0%）
```

### 案例 3：应用卡死排查（含工具失效）

```
症状：应用无响应
1. jstack <pid>：有响应 → 看线程状态（死锁/锁竞争/GC）
2. jstack 卡住 → VMThread/SafePoint 卡死
3. kill -3：看信号输出
4. gdb -p <pid>：OS 级看线程栈（最后防线）
5. /proc/<pid>/task/*/stack：内核栈
6. perf top：CPU 在哪
教训：jstack 依赖 VMThread；卡死时用 gdb/kill -3 逃生
```

### 案例 4：死锁排查

```
症状：应用卡死 + jstack "Found deadlock"
1. jstack 自动检测：Found one Java-level deadlock
2. 看交叉持锁：T1 持 A 等 B，T2 持 B 等 A
3. 修复：统一加锁顺序/tryLock 超时/减少锁粒度
教训：jstack 能检测死锁，但卡死场景 jstack 可能卡住（用 gdb）
```

---

## 工具链依赖关系（P9 理解层）

```
jstack / jcmd / jmap（轻量）
  → Attach API → attach listener → VMThread → SafePoint
  → ★ 依赖 VMThread + SafePoint（可能失效）

Arthas（attach）
  → 同上依赖，但有 dashboard/thread 等独立命令
  → profiler 用 async-profiler（perf_event_open，OS 级采样，较独立）

kill -3（SIGQUIT）
  → 信号处理器（不走 attach→VMThread）
  → 较可靠，但信号处理器也可能卡

gdb / /proc / perf（OS 级）
  → 完全绕过 JVM
  → ★ 最后防线（永远可用）
```

**P9 答法**："jstack 依赖 attach→VMThread→SafePoint，VMThread 卡死时 jstack 也卡死。逃生通道：kill -3（信号）→ gdb（OS 级线程栈，最后防线）→ /proc+perf（内核态）。这是 P9 与初级工程师的分水岭：会用工具是初级，知道工具失效怎么逃生是 P9。"

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **jstack 依赖链** | attach→VMThread→SafePoint（可能失效） | 源码 attachListener.cpp:185 |
| **VMThread 卡死场景** | GC 卡死/SafePoint 卡死/VM 内部锁 | 本域（盲区分析） |
| **工具失效逃生** | kill -3（信号）→ gdb（OS 级）→ /proc+perf（内核态） | 本域（决策树） |
| SafePoint 卡死 | JNI 临界区线程无法到达 SafePoint → 全局冻结 | 盲区 1 |
| Code Cache 满 | JIT 失效退化解释器；调 ReservedCodeCacheSize | 盲区 2 |
| Direct ByteBuffer 泄漏 | off-heap 不在堆 dump；用 NMT/限制 MaxDirectMemory | 盲区 3 |
| Classloader 泄漏 | 反复部署 ClassLoader 不卸载 → Metaspace OOM | 盲区 4 |
| Native Crash | SIGSEGV 分析 hs_err_pid.log | 盲区 5 |
| OOM 排查 | jstat→jmap→MAT→断源验证 | 案例 1（drill 实战） |
| CPU 高排查 | top -Hp→jstack→Arthas profiler 交叉验证 | 案例 2（drill 实战） |
| 应用卡死排查 | jstack→卡住→kill -3→gdb（最后防线） | 案例 3 |
| 死锁排查 | jstack 自动检测 Found deadlock；卡死时用 gdb | 案例 4 |

## 相关文件

- 源码：`openjdk11u/src/hotspot/share/services/attachListener.cpp:185`（VMThread::execute）
- 实战：`experiments/jvm-diagnostics/08-drill/`（OOM/CPU 案例）
- 逃生：gdb/kill -3//proc/perf（OS 级工具）
