# p9-domain1 · 对象与内存（P9 深度实验）

> P9 面试核心域 1/6。实验目录：`experiments/jvm-diagnostics/10-p9-object-memory/`
> 全程生产级基线参数；每个实验附 P9 追问链。

## 生产基线（全程使用）

```
-Xms512m -Xmx512m                    ← Xms=Xmx（避免堆伸缩抖动）
-XX:+UseG1GC -XX:MaxGCPauseMillis=200
-XX:+AlwaysPreTouch                  ← 启动预触摸所有页
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...
```

工具：JOL 0.17（`/data/workspace/tools/jol/jol-cli-0.17-full.jar`）

---

## 实验 1：对象内存布局（JOL 实测）

### 测量类

`LayoutDemo.java`：Empty / OneInt / OneLong / IntAndLong / ManyRefs / Mixed / WithRef

### 实测结果

| 类 | 大小 | 布局要点 | 教学点 |
|---|---|---|---|
| Empty | **16B** | 8B mark + 4B class + 4B gap | 最小对象 16B（头 12B + 对齐填充） |
| OneInt | **16B** | 8 + 4 + **4B int 塞进 gap** | int 正好填对齐空隙，0 浪费 |
| OneLong | **24B** | 8 + 4 + **4B padding** + 8B long | long 要 8B 对齐，前面垫 4B |
| IntAndLong | **24B** | 8 + 4 + 4B int + 8B long | 字段声明顺序即最优布局，0 浪费 |
| ManyRefs | **32B** | 8 + 4 + 4×4B ref + 4B gap | 引用 = 4B（compressed oops） |
| Mixed | **32B** | 8 + 4B int + 8B long + 1B bool + 1B byte + 6B gap | **字段重排证据**：long 声明第 2，布局却在 offset 16（8B 对齐） |
| WithRef | **24B** | 8 + 4B ref + 8B long | 混排 0 浪费 |

### P9 追问链

- **Q：对象头多大？** → mark word 8B + compressed class pointer 4B = 12B（64 位 + 压缩）
- **Q：为什么字段顺序影响内存？** → JVM 按"8B 对齐优先"重排（Mixed 证明：long 提前到 offset16）
- **Q：最小对象多大？** → 16B（头 12B + 4B 对齐填充），Empty 类实测
- **Q：引用多大？** → 压缩时 4B；关压缩 8B（实验 4）

---

## 实验 2：TLAB（Thread Local Allocation Buffer）

### 测试

`TlabDemo.java`：分配 50M 个 Node（强制逃逸存入静态 List 防标量替换）

### 实测结果（复跑落盘）

```
WITH TLAB:    203ms（tlab-with.log）
WITHOUT TLAB: 3422ms（tlab-without.log）
倍数:         约 17 倍（首跑 138ms vs 3220ms=23x，时序波动；稳定区间约 15-20 倍）
```

TLAB 统计（`-Xlog:tlab*=debug`，GC 时打）：
```
GC(0) TLAB totals: thrds: 5  refills: 73  slow allocs: 3  waste: 4.2%
GC(1) TLAB totals: thrds: 1  refills: 912 slow allocs: 0  waste: 0.2%
```

### P9 追问链

- **Q：为什么 TLAB 快？** → 每线程私有分配缓冲，避免 CAS 争抢堆顶指针（无锁分配）
- **Q：TLAB 大小怎么定？** → 自适应（按线程分配速率），`-XX:TLABSize` 可固定
- **Q：浪费了怎么办？** → GC 时丢弃剩余（waste 4.2%→0.2% 实测）；`-XX:TLABRefillWasteFraction` 调容忍度
- **Q：生产要关 TLAB 吗？** → 不关（默认开，关了慢 15-20 倍）

---

## 实验 3：逃逸分析（Escape Analysis）+ 标量替换

### 测试

`EscapeDemo.java`：两版本对比
- `nonEscaping()`：Point 只在方法内用（不逃逸）
- `escaping()`：Point 存入静态字段（真逃逸）

### 实测结果（4 组合，日志落盘 `ea-*.log`）

| 组合 | GC 次数 | 耗时 | 结论 |
|---|---|---|---|
| 不逃逸 + EA **on**（默认） | **0** | **12ms** | ★ 20M 个 Point 全被**标量替换**（0 实际分配） |
| 不逃逸 + EA off | 2 | 84ms | 真实分配 + GC（慢 7 倍） |
| 逃逸 + EA on | 2 | 97ms | 对象存静态 = 真逃逸，EA 无能为力 |
| 逃逸 + EA off | 2 | 103ms | 同上（EA 本来就帮不上） |

### 因果链（为什么 0 GC 证明标量替换）

```
20M 个 Point × 16B = 320MB 名义分配
→ 320MB > Eden 容量（512MB 堆 G1 Eden 约 200MB）
→ 若真分配必触发至少 1 次 GC
→ 实测 0 GC
→ 对象从未进堆 → 标量替换（字段拆为局部变量，对象"消失"）
```

### P9 追问链

- **Q：什么情况 EA 失效？** → 对象逃逸（存字段/静态/作为返回值/传给未知方法）
- **Q：EA 还做什么？** → 锁消除（同步块内对象不逃逸→去掉锁）、栈上分配
- **Q：生产能关 EA 吗？** → 技术上 `-XX:-DoEscapeAnalysis`，但无理由关（纯优化）
- **Q：怎么验证 EA 生效？** → GC 日志 0 GC（如本次）；或 JFR ObjectAllocationSample 计数

---

## 实验 4：压缩指针（Compressed OOPs）

### 测试

JOL 对比 `±UseCompressedOops`，测 WithRef / ManyRefs

### 实测结果

| 类 | +CompressedOops（默认） | -CompressedOops | 差值 |
|---|---|---|---|
| WithRef | **24B**（class 4B + ref 4B + long 8B） | **32B**（class 8B + long 8B + ref 8B） | +8B |
| ManyRefs | **32B**（4×4B ref + 4B gap） | **48B**（4×8B ref） | +16B |

### 源码依据（HotSpot 11.0.32）

```
arguments.cpp:1609  OopEncodingHeapMax = (max_juint+1) << LogMinObjAlignmentInBytes
                    = 2^32 << 3 = 2^35 = 32GB
arguments.cpp:1638  if (max_heap_size <= max_heap_for_compressed_oops())  // 启用
arguments.cpp:1648  FLAG_SET_DEFAULT(UseCompressedClassPointers, false);  // 关 oops 连带关 class ptr
```

### P9 追问链

- **Q：压缩指针阈值？** → 堆 ≤32GB（源码 `OopEncodingHeapMax=2^35`）
- **Q：堆 >32GB 怎么办？** → 自动关压缩（引用 8B）；或调 `-XX:ObjectAlignmentInBytes=16` 撑到 64GB
- **Q：压缩指针影响什么？** → 引用 4B↔8B；`-XX:-UseCompressedOops` 连 class pointer 也变 8B（源码 1648 行）
- **Q：怎么确认生效？** → `jcmd <pid> VM.flags | grep CompressedOops` 或 JOL 输出

---

## 实验 5：G1 巨型对象（Humongous）

### 测试

`HumongousDemo.java`：分配 500 个 600KB 数组（强制触发 GC）

### 实测结果

```
G1HeapRegionSize = 1048576（1MB，ergonomic，512MB 堆自适应）
humongous 阈值 = region/2 = 512KB
600KB 数组 = 614416B > 512KB → humongous 分配
```

GC 日志证据（`humongous.log`）：
```
GC(0) Pause Young (Concurrent Start) (G1 Humongous Allocation) 232M->230M(512M) 22.499ms
[gc,humongous] Live humongous region 0 object size 614416 ... reclaim candidate 0
```

### P9 追问链

- **Q：什么是 humongous 对象？** → G1 中 ≥ region/2 的对象，直接分配在老年代 region
- **Q：有什么问题？** → 分配即触发并发周期（Concurrent Start）；回收依赖 concurrent marking，比 young GC 慢
- **Q：怎么避免？** → 拆分大对象 / 调 `-XX:G1HeapRegionSize`（必须 2 的幂，1MB-32MB）
- **Q：region 大小怎么定？** → 堆 ≤512MB→1MB；≤1GB→2MB…自适应（`-XX:G1HeapRegionSize` 可固定）

---

## 实验 6：Mark Word 位布局（补实验）

### 测试

`MarkWordDemo.java`：JOL 观察 4 种状态下 mark word 变化

### 实测结果

```
1) 新对象:            0x0000000000000005 (biasable; age: 0)
2) identityHashCode后: 0x00000035047d0301 (hash: 0x35047d03; age: 0)
3) synchronized内:     0x00007bd680010005 (biased: 0x...; epoch: 0; age: 0)
3b) 退出后:            同上（偏向锁不立即撤销）
```

### 位布局（64 位 mark word）

```
┌──────────────────────────────────────┬────────┬─────┬─────────┐
│  hashcode / 锁记录指针 / Klass 指针   │  age   │ 偏向 │ 锁标志  │
│            62 bits                   │ 4 bits │ 1b  │  2 bits │
└──────────────────────────────────────┴────────┴─────┴─────────┘
```

- **hashcode**：`identityHashCode` 后写入高位（状态 2 证明）
- **age**：4 bit → **最大 15**（`MaxTenuringThreshold` 默认 15 的原因）
- **锁状态**：低 2 bit（01=无锁/偏向、00=轻量级、10=重量级、11=GC）
- **偏向锁**：JDK11 默认开；JDK15 弃用、JDK18+ 默认关

### P9 追问链

- **Q：mark word 存什么？** → hashCode、GC 年龄、锁状态、偏向线程 ID（按状态复用）
- **Q：为什么 GC 年龄最大 15？** → mark word 里 age 占 4 bit，最大值 2^4-1=15
- **Q：hashCode 和锁能共存吗？** → 不能（hashcode 写入后偏向位清除，状态 2 证明）
- **Q：偏向锁现在还用吗？** → JDK18+ 默认关（多线程下撤销开销大于收益）

---

## 实验 7：数组对象布局（补实验）

### 实测结果

```
int[5]:    16B header(mark8+class4+length4) + 5×4B + 4B gap = 40B
Object[3]: 16B header + 3×4B ref + 4B gap = 32B
数组基址:  Array base offset = 16B（JOL VM 信息确认）
```

### 教学点

- 数组头比普通对象**多 4B length 字段**（普通对象头 12B → 数组头 16B）
- 元素紧跟 length 之后，从 offset 16 开始
- 引用数组元素 = 4B（compressed oops）

---

## 补充知识：对象分配完整链路

```
new Object()
  │
  ├─ 1. TLAB 有足够空间？ → 是 → TLAB 内 bump pointer（无锁，最快）
  │                        │
  │                        否 ↓
  ├─ 2. Eden 区有空间？ → 是 → CAS 抢堆顶指针 + 指针加法（慢路径①）
  │                        │
  │                        否 ↓
  ├─ 3. Young GC（Minor GC）→ 晋升存活对象 → Eden 腾出空间
  │                        │
  │                        还不够 ↓
  ├─ 4. 老年代担保（HandlePromotionFailure）→ 直接分配到老年代
  │                        │
  │                        还不够 ↓
  └─ 5. Full GC → 还不够 → OOM
```

**P9 答法**：对象分配 = TLAB 无锁 → Eden CAS → Young GC → 担保 → Full GC → OOM 的逐级降级链路。

---

## 补充知识：栈上分配误区（面试陷阱）

**误区**："HotSpot 有栈上分配"

**事实**：HotSpot **没有真正的栈上分配**（方法结束自动弹栈回收）。实际发生的是：

```
逃逸分析 → 不逃逸 → 标量替换（字段拆为局部变量，存在栈帧里）+ 锁消除
```

对象从未被"分配到栈上"——它**根本没被分配**，字段直接变成栈上的局部变量。

**为什么常被误解**：C++ 等语言有真正的栈上分配（`MyClass obj;` 在栈帧）；HotSpot 只做"看起来像栈分配"的标量替换优化。

**P9 答法**："HotSpot 没有栈上分配，只有逃逸分析后的标量替换。实验 3 证明：不逃逸对象 0 GC = 从未进堆 = 标量替换而非栈分配。"

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 实验背书 |
|---|---|---|
| 对象内存布局 | 头 12B + 字段 + 8B 对齐；JVM 自动字段重排 | 实验 1 |
| TLAB | 线程私有分配缓冲，无锁 CAS，快 15-20 倍 | 实验 2 |
| 逃逸分析 | 不逃逸对象可标量替换（0 分配）/锁消除 | 实验 3 |
| 压缩指针 | 堆 ≤32GB 引用 4B；>32GB 自动关 | 实验 4（源码 arguments.cpp:1609） |
| G1 humongous | ≥region/2 直接进老年代，触发并发周期 | 实验 5 |
| mark word | hashCode/年龄(4bit=15)/锁状态/偏向线程 ID 复用 | 实验 6 |
| GC 年龄上限 15 | mark word age 占 4 bit，MaxTenuringThreshold 默认 15 | 实验 6 |
| 数组布局 | 头 16B（多 4B length），基址 offset 16 | 实验 7 |
| 对象分配链路 | TLAB→Eden CAS→Young GC→担保→Full GC→OOM | 补充知识 |
| 栈上分配 | HotSpot 无真栈上分配，只有标量替换 | 补充知识 |

## 相关文件

- `10-p9-object-memory/LayoutDemo.java`：对象布局测量类
- `10-p9-object-memory/TlabDemo.java`：TLAB 分配测试
- `10-p9-object-memory/EscapeDemo.java`：逃逸分析对比
- `10-p9-object-memory/HumongousDemo.java`：humongous 分配
- `10-p9-object-memory/MarkWordDemo.java`：mark word + 数组布局（补实验）
- `/data/workspace/arthas-output/{tlab-with,tlab-without,ea-*,humongous}.log`：实验日志
- `/data/workspace/tools/jol/jol-cli-0.17-full.jar`：JOL 工具
