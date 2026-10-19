# heap-01 · hprof 堆转储格式与生成

## 一、它是什么

某一瞬间的"整个 Java 堆 + GC roots + 类元信息"二进制快照。
文件头 19 字节实测：
```
4a415641 2050524f 46494c45 20312e30   "JAVA PROFILE 1.0"
2e320000 00000800 0001a0a4 27a95401   ".2\0" + idSize=08 + timestamp(ms)
```
- 魔数 `JAVA PROFILE 1.0.2\0`；`idSize=8`（64 位引用 8 字节）；时间戳 = 导出时刻

## 二、里面有什么（解析器实测，`jmap-demo.hprof` 4.3MB）

脚本：`experiments/jvm-diagnostics/02-commands/hprof_summary.py`
（tag 值以 `hotspot/share/services/heapDumper.cpp` 的 `hprofTag` 为准；注意 0x1C 才是 HEAP_DUMP_SEGMENT）

| 顶层记录 | 数量 | 含义 |
|---|---|---|
| UTF8(STRING) | 27592 | 字符串内容（类名、字段名、堆里 String 值） |
| LOAD_CLASS | 1080（去重 966 类） | 类身份（类对象 id → 类名字符串） |
| FRAME/TRACE | 22/9 | 栈的一点点信息 |
| HEAP_DUMP_SEGMENT | 1 | 主体（下面全是它的子记录） |

HEAP_DUMP_SEGMENT 内部：
| 子记录 | 数量 | 内容 |
|---|---|---|
| INSTANCE_DUMP | 18741 | 每个对象：类 id + **字段值**（注意：不含对象头） |
| PRIM_ARRAY_DUMP | 7435 | 基本类型数组元素 |
| OBJ_ARRAY_DUMP | 3291 | 对象数组引用 |
| CLASS_DUMP | 966 | 类元信息：父类、classloader、字段表、**静态字段值** |
| ROOT_* | 若干 | GC root：STICKY_CLASS 766、JNI_GLOBAL 53、JAVA_FRAME 26、THREAD_OBJ 8 |

直方图 Top（文件算出）：`[B` 7120 个 / String 6092 个——实例数与 `jmap -histo:live` **完全一致**。

**关键细节**：INSTANCE_DUMP 只存字段区，不含对象头——
实测 String：文件 79196B（≈13B/个 = hash4+coder1+value8）vs jmap -histo 146208B（24B/个含头+对齐）。
tag 表用 HotSpot 源码的（`0xFF`=UNKNOWN、`0x01-0x08`=8 种 root，**没有 0x09**）。

## 三、里面**没有**什么

- 时间轴（单点快照；分配历史是 JFR 的事）
- 对象头和对齐信息
- 堆外：Metaspace 完整 Klass 结构、JIT 代码、栈局部变量值、DirectByteBuffer 的 native 内存
- 完整线程栈内容（只有 26 个 JAVA_FRAME 引用作 root）
- JVM 启动参数/系统属性等元数据记录
- "语义"（不知道什么是泄漏，MAT 靠引用图猜）；非 live 转储还含垃圾对象

## 四、怎么生成的（核心）

入口四种：
1. `jmap -dump` / `jcmd GC.heap_dump` → attach `dumpheap`
2. `-XX:+HeapDumpOnOutOfMemoryError` → `dump_heap_from_oome()` → `java_pid<pid>.hprof`（cwd 或 HeapDumpPath；后续加 `.1`、`.2` 后缀）
3. JMX `HotSpotDiagnosticMXBean.dumpHeap`
4. JFR
- 内部：`HeapDumper::dump` → `VM_HeapDumper`（`VM_GC_Operation` → **STW**，同时 `AbstractGangTask` 并行写）
- attach `dumpheap` 参数：arg0 文件路径，arg1 `-live`/`-all`（缺省 live=true）；
  **`jmap -dump` 默认发 `-all`**，`jmap -dump:live` 发 `-live`（`JMap.java:183-208`）
- live 语义（`attachListener.cpp:236-239` 原话）：先 full GC（`collect_as_vm_thread(GCCause::_heap_dump)`），
  减少不可达对象、方便浏览；OOME 路径**不先 GC**（`dumper(false, oome)`，现场原样）
- 写出 = 顺序二进制流：先类/字符串，再一个 HEAP_DUMP_SEGMENT 装对象/数组/roots

## 五、代价

dump 期间应用**全程停顿**（STW），大堆秒~分钟级 → 生产走 OOME 自动 dump 或低峰手动；
容器注意 HeapDumpPath 与 `/proc/<pid>/root` 视角。

## 六、值不值得深挖

- 格式：知道"顶层记录 + 子记录 tag 表"即可（要查就查 `heapDumper.cpp` 顶部 `hprofTag` 枚举）
- 记住三件套：**对象（类id+字段值）、引用、GC roots**——MAT 的直方图/支配树/泄漏嫌疑全是这三样的组合
- 深挖点：STW 代价、容器 dump 路径
