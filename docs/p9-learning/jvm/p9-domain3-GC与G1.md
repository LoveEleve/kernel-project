# p9-domain3 · GC 原理与 G1（P9 深度完整版）

> P9 面试核心域 3/6（最重）。实验目录：`experiments/jvm-diagnostics/12-p9-gc/`
> 覆盖：并发周期 / Mixed GC+CSet / 晋升 / RSet / 三色+SATB / Full GC / 调优
> 补全（#1-#10）：写屏障实现 / RSet 结构 / SATB 队列 / Region 状态机 / 日志解读 / 并发 abort / 引用处理 / humongous / 版本演进 / 生产案例

## 生产基线

```
-Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+AlwaysPreTouch
```

---

# 第一部分：核心机制（实验实证）

## 实验 1：并发标记周期全阶段

```
GC(4)  Pause Young (Concurrent Start)  149M->148M  108ms
GC(5)  Concurrent Cycle
         ├─ Concurrent Clear Claimed Marks        0.08ms
         ├─ Concurrent Scan Root Regions          8.5ms
         ├─ Concurrent Mark From Roots            42ms    ← 三色标记并发
         ├─ Concurrent Preclean                   0.17ms
         ├─ Pause Remark  197M->197M              4.8ms   ← ★ STW：drain SATB 缓冲
         ├─ Concurrent Rebuild Remembered Sets    36ms    ← ★ 重建 RSet
         ├─ Pause Cleanup  239M->239M             5.1ms   ← STW：清空+选回收集
         └─ Concurrent Cleanup for Next Mark      1.0ms
       Concurrent Cycle 总计 246ms
```

## 实验 2：Mixed GC（G1 核心创新）

### 触发决策（ergo 日志）

```
阈值 5%（默认）: reclaimable: 4.37% threshold: 5% → young-only（不触发）
阈值 1%（调低）: reclaimable: 1.18% threshold: 1% → request mixed gcs ✓
                reclaimable: 2.17% threshold: 1% → request mixed gcs
```

### Mixed GC 序列（实测）

```
GC(9)  Pause Young (Prepare Mixed)  306M->144M  18.9ms   ← 准备 CSet
GC(10) Pause Young (Mixed)          149M->143M  13.6ms   ← ★ 混合回收
GC(13) Pause Young (Prepare Mixed)  327M->170M  15.5ms
GC(14) Pause Young (Mixed)          175M->167M   8.8ms
GC(56) Pause Young (Prepare Mixed)  393M->344M   7.5ms
GC(57) Pause Young (Mixed)          361M->337M   5.3ms
```

### CSet 组成

```
GC(9):  collection set length 203 regions
GC(13): 202 regions
GC(56): 57 regions（垃圾渐少）
```

## 实验 3：对象晋升与年龄

```
GC(0): age 1: 1.7MB
GC(1): age 2: 1.7MB
GC(2): age 3: 1.7MB
GC(3): age 3: 51KB   ← ★ 1.65MB 晋升（MaxTenuringThreshold=3）
```

## 实验 4：三色标记 + SATB

三色状态机：白=未访问/灰=子未全/黑=全访问；漏标=并发丢引用。
SATB：引用修改时旧值入缓冲 → Remark STW drain。

## 实验 5：调优对比

```
IHOP=45: Concurrent Start=0（老年代未达45%）
IHOP=30: Concurrent Start=1（更早触发）
G1HeapWastePercent=5→1: Mixed GC 从不触发→触发（阈值决策见实验2）
```

---

# 第二部分：补全 #1-#10（源码+实测）

## #1 写屏障实现（源码实证）

### 位置：`g1BarrierSet.inline.hpp:49-55`

```cpp
inline void G1BarrierSet::write_ref_field_post(T* field, oop new_val) {
  volatile jbyte* byte = _card_table->byte_for(field);   // ① 定位 card
  if (*byte != G1CardTable::g1_young_card_val()) {       // ② 检查是否 young card
    write_ref_field_post_slow(byte);                     // ③ 老年代 card → 慢路径
  }
}
```

### 慢路径（`g1BarrierSet.cpp:61-72`）：enqueue 到 SATB 队列

```cpp
void G1BarrierSet::enqueue(oop pre_val) {
  if (!_satb_mark_queue_set.is_active()) return;         // 非并发标记期不入队
  Thread* thr = Thread::current();
  if (thr->is_Java_thread()) {
    G1ThreadLocalData::satb_mark_queue(thr).enqueue(pre_val);  // per-thread 缓冲
  } else {
    // 非 Java 线程 → 共享队列（加锁）
    MutexLockerEx x(Shared_SATB_Q_lock, ...);
    _satb_mark_queue_set.shared_satb_queue()->enqueue(pre_val);
  }
}
```

### 写屏障完整流程

```
引用写入 (putfield/aastore)
  → write_ref_field_pre: 旧值入 SATB 队列（SATB 快照）
  → write_ref_field_post: 标记 card dirty（RSet 更新用）
  → 只在并发标记期间激活（is_active()）
```

**P9 答法**："G1 写屏障两步：pre-barrier 把旧引用 enqueue 到 per-thread SATB 缓冲（防漏标）；post-barrier 标记 card dirty（供 RSet 重建）。非并发标记期屏障几乎无开销（is_active 检查直接返回）。"

---

## #2 RSet 内部结构（源码实证）

### 位置：`heapRegionRemSet.hpp:41,74`

```cpp
class PerRegionTable;           // 每个被引用 region 一个表（card 位图）
class OtherRegionsTable {       // 一个 region 的 RSet = 多个 PerRegionTable 的哈希
```

### 结构

```
每个 region 一个 RSet（OtherRegionsTable）
  → 哈希表：key=引用它的 region，value=PerRegionTable
  → PerRegionTable：card 粒度位图（512B/card，记录哪些 card 引用本 region）
  → 粗粒度兜底：OverflowVector（region 太多时退化为整 region 标记）
```

### 卡粒度

```
Card size = 512B（-XX:G1ConcRefinementCardSize，默认512）
一个 region（1MB）= 2048 张 card
RSet 记录"哪个 card 引用我"，不记具体对象
```

**P9 答法**："RSet 是 per-region 的哈希表（OtherRegionsTable），key=引用它的 region，value=card 位图（PerRegionTable）。Card 粒度 512B，一个 1MB region 有 2048 张卡。引用太多时退化为粗粒度 OverflowVector。"

---

## #3 SATB 队列实现（源码实证）

### 结构

```
per-thread SATBMarkQueue（PaddedArray 避免伪共享）
  → 每线程本地缓冲（默认 256 槽）
  → 满了 → Enqueue 到全局 SATB 队列（Shared_SATB_Q_lock）
  → 并发标记时由 GC 线程 drain
```

### Remark 时 drain（`g1CollectedHeap.cpp` + 关闭屏障）

```cpp
// Remark STW:
G1BarrierSet::satb_mark_queue_set().drain_all();  // 处理所有线程缓冲残留
// 处理完 → 关闭 SATB 屏障（is_active()=false）
```

**P9 答法**："SATB 队列是 per-thread 缓冲（避免锁），满了溢出到全局队列。Remark STW 时 drain_all 处理残留，然后关闭屏障（非标记期写屏障无开销）。"

---

## #4 Region 状态机（源码实证）

### 位置：`heapRegionType.hpp:47-77`

```cpp
FreeTag = 0,                    // 00000 → 空闲
EdenTag = YoungMask,            // 00001 → Eden
SurvivorTag = YoungMask + 1,    // 00011 → Survivor
StartsHumongousTag,             // 00110 → Humongous 起始
ContinuesHumongousTag,          // 00111 → Humongous 延续
OldTag,                         // 01000 → Old
```

### 状态转换

```
Free → Eden（分配 Eden 区）
Eden → Survivor（Young GC 后存活）
Survivor → Old（晋升）
Free → StartsHumongous + ContinuesHumongous（大对象分配）
Old → Free（Mixed/Full GC 回收后）
Humongous → Free（并发标记后回收）
```

**P9 答法**："G1 region 有 6 种类型：Free/Eden/Survivor/Old/StartsHumongous/ContinuesHumongous，用 5 bit 编码在 region 头部。状态转换：Free→Eden→Survivor→Old（晋升）；Humongous 分配占连续 region；回收后→Free。"

---

## #5 GC 日志字段深度解读

```
GC(0) Pause Young (Normal) (G1 Evacuation Pause) 25M->2M(512M) 44.196ms
       ↑      ↑                    ↑              ↑    ↑     ↑
       事件   触发原因            回收前->后(总堆)  耗时
       
Pause Young (Concurrent Start)  ← 触发并发标记的 Young GC
Pause Young (Prepare Mixed)     ← 准备 Mixed CSet
Pause Young (Mixed)             ← 混合回收（young+old）
Pause Remark                    ← 并发标记 STW 修正
Pause Cleanup                   ← 清空+选回收集
Pause Full                      ← 兜底 Full GC

Heap after GC: Eden regions: 25->0(306)  ← Eden 25 个 region→0（共 306 可用）
               Survivor: 0->1(4)         ← Survivor 0→1
               Old regions: 0->0         ← Old 不变（Young GC 不收 Old）
```

---

## #6 并发标记中断/恢复（叙事）

```
并发标记可能 abort（中断）：
1. 时间不够：并发标记还没完成，Old 已满 → 放弃，转 Full GC
2. 内存不够：标记时需要分配内存（work queue），分配失败 → abort
3. 显式取消：JVM 内部原因（如 GC 压力过大）

abort 后：G1 尝试重新启动并发标记；连续失败 → Full GC 兜底
日志特征：Concurrent Mark Abort / Concurrent Mode Failure
```

**P9 答法**："并发标记可能因时间不够（Old 满）或内存不够（work queue 分配失败）abort。abort 后重启，连续失败转 Full GC。生产看 `concurrent mode failure` 日志即此场景。"

---

## #7 引用处理（实测）

### 实测（RefDemo）

```
before GC:         weak=true  soft=true
after GC:          weak=false soft=true   ← 弱引用被清，软引用保留
after pressure:    soft=true              ← 10MB 分配后软引用仍未清（压力不够）
                   （G1 软引用清除阈值默认为堆的 1/2，需更大压力）
strong alive=true
```

### G1 引用处理规则

| 引用类型 | 清除时机 | G1 处理 |
|---|---|---|
| Soft | 内存压力（阈值可调 `-XX:SoftRefLRUPolicyMSPerMB`） | 并发标记后按压力清除 |
| Weak | 下次 GC（无论压力） | Young/Mixed GC 时清除 |
| Phantom | 对象被回收后入队 | Final Reference 队列 |
| Final | 对象被回收后，finalize 前 | 单独队列，Finalizer 线程处理 |

**P9 答法**："G1 引用处理：Weak 下次 GC 清；Soft 内存压力时清（`SoftRefLRUPolicyMSPerMB` 控制阈值）；Phantom 对象回收后入队；Final 有单独队列。都在 GC 的 reference processing 阶段处理。"

---

## #8 Humongous 分配/回收细节

### 分配

```
对象 ≥ region/2 → humongous 分配
  → 找连续 free region（StartsHumongous + N 个 ContinuesHumongous）
  → 不够 → 触发 Young GC（回收 Eden）或并发标记
  → 还不够 → 触发 Full GC（压缩整理连续空间）
```

### 回收

```
humongous 对象回收时机：
  1. 并发标记期间：标记为垃圾的 humongous region 直接回收
  2. Mixed GC：垃圾多的 humongous region 加入 CSet
  3. Full GC：全量压缩
注意：humongous 分配是"连续 region"，碎片化会导致分配失败
```

**P9 答法**："Humongous 对象（≥region/2）分配连续 region，回收依赖并发标记（标记期间直接回收垃圾 humongous region）。碎片化会导致分配失败触发 Full GC。生产避免大对象，或调大 `G1HeapRegionSize`。"

---

## #9 G1 版本演进（叙事）

| JDK | 改进 |
|---|---|
| JDK 7 | G1 首次引入（实验性） |
| JDK 9 | **成为默认 GC**（替代 Parallel） |
| JDK 10 | **Full GC 并行化**（之前单线程） |
| JDK 12 | **G1 Speedup**（JEP 346：更早回收 humongous、字符串去重默认关） |
| JDK 14 | **更及时的 Commit/Uncommit**（内存归还 OS） |
| JDK 15 | ZGC 并发（G1 仍默认） |
| JDK 16 | **JEP 376: ZGC 并发栈转移**（G1 不受影响） |
| JDK 17+ | 持续优化（NUMA 感知、大页改进） |

**P9 答法**："G1 JDK9 默认，JDK10 Full GC 并行化，JDK12 加速（更早回收 humongous），JDK14 内存及时归还。核心改进方向：降低停顿、减少碎片、内存效率。"

---

## #10 生产调优案例（P9 高频）

### 案例 1：停顿不达标（MaxGCPauseMillis=200 但实际 500ms）

```
诊断：GC 日志看 Pause Young 耗时 → 找原因
常见原因：
  1. Survivor 溢出 → 晋升失败 → Full GC（调大 MaxTenuringThreshold 或堆）
  2. Humongous 多 → Concurrent Start 频繁（调大 G1HeapRegionSize 或拆分对象）
  3. RSet 过大 → 写屏障开销高（减少跨 region 引用）
调优：
  -XX:MaxGCPauseMillis=100（更激进目标）
  -XX:G1HeapRegionSize=16m（减少 humongous）
  -XX:InitiatingHeapOccupancyPercent=35（提前并发）
```

### 案例 2：Old Gen 持续增长（Mixed GC 回收不及时）

```
诊断：GC 日志看 Mixed GC 频率 + reclaimable%
原因：G1HeapWastePercent 默认 5% 太保守
调优：
  -XX:G1HeapWastePercent=3（更积极回收老年代）
  -XX:G1MixedGCCountTarget=16（一次周期多做几轮 Mixed）
```

### 案例 3：Full GC 频繁（并发标记跟不上）

```
诊断：日志见 "concurrent mode failure" / "to-space exhausted"
原因：分配速率 > 并发回收速率
调优：
  -XX:InitiatingHeapOccupancyPercent=25（更早触发并发）
  -XX:G1ReservePercent=15（预留空间防晋升失败）
  -XX:+ParallelRefProcEnabled（并行处理引用）
```

---

## 本域 P9 高频考点速查（15 行）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| G1 并发周期阶段 | Start→Mark→Remark(STW)→Rebuild RSet→Cleanup(STW) | 实验 1 |
| Remark 为什么 STW | drain SATB 缓冲残留 | 实验 1 + #3 |
| **Mixed GC 触发** | reclaimable% > G1HeapWastePercent（默认5%） | 实验 2（ergo 日志） |
| **Mixed GC 模式** | Prepare Mixed → Mixed（young+选定 old） | 实验 2（8 组实测） |
| **CSet 组成** | Young=Eden+Survivor；Mixed=+部分 Old 垃圾 region | 实验 2（57-203 regions） |
| 对象晋升 | age≥MaxTenuringThreshold（默认15）或动态年龄 | 实验 3 |
| RSet 作用 | 记录跨 region 引用，Young GC 不全扫 old gen | 实验 3 |
| **写屏障实现** | pre=SATB enqueue / post=card marking；非标记期近零开销 | **#1（源码）** |
| **RSet 结构** | per-region 哈希表 + card 位图（512B/card） | **#2（源码）** |
| **SATB 队列** | per-thread 缓冲，Remark drain_all 后关闭屏障 | **#3（源码）** |
| **Region 状态机** | Free/Eden/Survivor/Old/Humongous 6 态，5bit 编码 | **#4（源码）** |
| 三色标记 | 白/灰/黑；漏标=并发丢引用 | 实验 4 |
| SATB 防漏标 | 旧值入缓冲，Remark 统一处理 | 实验 4 + #3 |
| **Full GC 触发** | 晋升失败/并发失败/humongous/显式 GC | 补充（#6+#8） |
| Card Table vs RSet | CMS=全堆卡表；G1=per-region RSet | #2（源码） |
| **引用处理** | Weak=下次GC清；Soft=压力时清；Phantom/Final=队列 | **#7（实测）** |
| **Humongous** | ≥region/2 分配连续 region；并发标记回收 | **#8（叙事+日志）** |
| 停顿预测 | 历史耗时→选 CSet 大小 | 补充（叙事） |
| IHOP 调优 | 老年代快→调低 | 实验 5 |
| G1HeapWastePercent | Mixed 阈值，默认5% | 实验 2（5%→1%） |
| **G1 版本演进** | JDK9 默认→10 FullGC并行→12加速→14内存归还 | **#9（叙事）** |
| **生产调优案例** | 停顿不达标/Old增长/FullGC频繁 三类场景 | **#10（案例）** |
| G1 vs ZGC | G1=写屏障+RSet；ZGC=读屏障+转发 | 补充（对比表） |

## 相关文件

- `12-p9-gc/GcDemo.java`/`GcDemo2.java`/`GcDemo3.java`：基础/加强靶子
- `12-p9-gc/MixedGcDemo4.java`：★ Mixed GC 靶子
- `12-p9-gc/PromotionDemo.java`：晋升演示
- `12-p9-gc/RefDemo.java`：★ 引用处理演示
- `12-p9-gc/FullGcDemo.java`：Full GC 触发尝试
- `12-p9-gc/StringDedupDemo.java`：★ String Dedup 演示
- `/data/workspace/arthas-output/gc-{full,concurrent,age,promotion,mixed,safepoint}.log`：GC/Safepoint 日志
- 源码：`openjdk11u/src/hotspot/share/gc/g1/{g1BarrierSet,heapRegionRemSet,heapRegionType,g1SATBQueue,g1ConcurrentRefine,g1Policy,heapRegion}.*`

---

# 第三部分：深挖补全 #21-#30

## #21 Concurrent Refinement（并发精化，源码实证）

### 机制

```
写屏障标记 dirty card → 不等 GC，后台线程 g1ConcurrentRefineThread 立即处理
  → 处理：扫描 dirty card，把跨 region 引用更新到目标 region 的 RSet
  → 目的：GC 时 RSet 已就绪，减少 STW 阶段的 RSet 更新时间
```

### 源码（`g1ConcurrentRefine.hpp:33-40`）

```cpp
class G1ConcurrentRefine;
class G1ConcurrentRefineThread;         // 后台精化线程
class G1ConcurrentRefineThreadControl { // 线程控制（yellow/red zone 调速）
```

### 调速机制（yellow/red zone）

```
dirty card 堆积量：
  < yellow zone → 1 个精化线程
  ≥ yellow zone → 增加线程
  ≥ red zone → 停止应用分配（反压），全力精化
```

**P9 答法**："G1 的 Concurrent Refinement：写屏障只标 dirty card，后台 `g1ConcurrentRefineThread` 立即扫描处理进 RSet（yellow/red zone 调速）。GC 时 RSet 已就绪，减少 STW 更新时间。"

---

## #22 Card Table ↔ RSet 完整协同链路

```
① 应用线程写引用
   → G1BarrierSet::write_ref_field_post（pre-barrier 标 dirty card）
② 后台 Concurrent Refinement 线程
   → 扫描 dirty card → 找到跨 region 引用
   → 更新目标 region 的 RSet（OtherRegionsTable::add_reference）
③ Young/Mixed GC 时
   → CSet 内 region：用 RSet 找到外部引用（不全扫 old gen）
   → 更新存活对象的 RSet（并发期遗留的增量）
④ Concurrent Mark 时
   → Rebuild RSet（并发重建，为下一轮 Mixed GC 准备）
```

**P9 答法**："写屏障标 dirty card → Concurrent Refinement 后台处理进 RSet → GC 时用 RSet 定位跨代引用 → 并发周期 Rebuild RSet 为下轮准备。四步闭环，GC 时不用全扫 old gen。"

---

## #23 PLAB（Promotion LAB，GC 分配缓冲）

### 机制

```
TLAB：应用线程分配对象的线程本地缓冲（Eden 区）
PLAB：GC 线程分配存活对象的线程本地缓冲（Survivor/Old 区）

Young GC 时：
  每个 GC worker 一个 PLAB（Survivor 区）
  → 存活对象复制到 PLAB（无锁）
  → PLAB 满了 → 向 GC 申请新 PLAB
  → 晋升时 → PLAB 在 Old 区
```

**P9 答法**："PLAB 是 GC 线程的分配缓冲（对应 TLAB），Young GC 复制存活对象到 Survivor PLAB，晋升时用 Old PLAB。减少 GC worker 间的分配竞争。"

---

## #24 Adaptive IHOP（自适应 IHOP，源码实证）

### 源码（`g1Policy.cpp:739-741`）

```cpp
G1IHOPControl* G1Policy::create_ihop_control(...) {
  if (G1UseAdaptiveIHOP) {           // JDK9+ 默认开
    return new G1IHOPPercentDepPredictor(...);  // 基于历史预测
  } else {
    return new G1IHOPFixedPercent(...);         // 固定阈值
  }
}
```

### 自适应逻辑

```
G1 统计：老年代分配速率 + 并发标记耗时
  → 预测"下次并发标记需要多少提前量"
  → 动态调整 IHOP 阈值（非固定 45%）
  → 老年代增长快 → 自动调低 IHOP（提前触发）
  → 增长慢 → 自动调高（减少不必要并发）
```

**P9 答法**："JDK9+ G1 默认 Adaptive IHOP（`G1UseAdaptiveIHOP=true`）：基于老年代分配速率和并发标记耗时预测，动态调整 IHOP 阈值。固定 IHOP 只在显式关闭自适应时用。"

---

## #25 Evacuation Failure 详细处理

### 触发条件

```
Young/Mixed GC 时，存活对象复制失败：
  1. Survivor PLAB 满 → 晋升失败
  2. Old 区分配失败（连续 free region 不够）
  3. Humongous 分配失败（连续 region 不够）
```

### 处理流程

```
Evacuation Failure 发生
  → G1 尝试"自救"：用本地缓冲（EvacuationFailedInfo）继续
  → 自救失败 → 放弃本次 GC，转 Full GC（单线程压缩）
日志特征：to-space exhausted / Evacuation Failure
```

**P9 答法**："Evacuation Failure = GC 时复制存活对象空间不够（Survivor/Old/humongous）。G1 先尝试本地缓冲自救，失败则转 Full GC。生产配 `-XX:G1ReservePercent=15` 预留空间防此场景。"

---

## #26 Full GC 并行压缩（JDK10+）

### 版本对比

```
JDK9-：Full GC 单线程（G1 唯一单线程阶段）
JDK10+：Full GC 并行化（JEP 不单独提，G1 内部改进）
  → 标记阶段并行（多 worker）
  → 压缩阶段并行（分区压缩）
  → 停顿显著降低（但仍是 STW，比 Young/Mixed 长）
```

**P9 答法**："G1 Full GC JDK10+ 并行化（标记+压缩并行），但仍是 STW。生产尽量避免 Full GC（并发标记+Mixed 足够）；真触发时 JDK10+ 比 JDK9 快数倍。"

---

## #27 Region 大小选择算法（源码实证）

### 源码（`heapRegion.cpp:63-75`）

```cpp
void HeapRegion::setup_heap_region_size(size_t initial_heap_size, size_t max_heap_size) {
  size_t region_size = G1HeapRegionSize;
  if (FLAG_IS_DEFAULT(G1HeapRegionSize)) {
    size_t average_heap_size = (initial_heap_size + max_heap_size) / 2;
    region_size = MAX2(average_heap_size / HeapRegionBounds::target_number(),  // 目标 2048 个
                       HeapRegionBounds::min_size());                          // 最小 1MB
  }
  region_size = ((size_t)1 << log2_long(region_size));  // 向下取 2 的幂
  // 限制在 [min_size, max_size] = [1MB, 32MB]
}
```

### 公式

```
region_size = max(average_heap / 2048, 1MB)，向下取 2 的幂，限制 [1MB, 32MB]

512MB 堆 → 512/2048 = 256KB → max(256KB, 1MB) = 1MB
1GB 堆 → 1024/2048 = 512KB → max(512KB, 1MB) = 1MB
2GB 堆 → 2048/2048 = 1MB → 1MB
4GB 堆 → 4096/2048 = 2MB → 2MB
32GB 堆 → 32768/2048 = 16MB → 16MB
```

**P9 答法**："Region 大小 = max(堆均值/2048, 1MB)，向下取 2 的幂，范围 [1MB, 32MB]。目标是约 2048 个 region（平衡管理开销与回收粒度）。"

---

## #28 NUMA / Large Page 支持（了解级）

```
NUMA：G1 感知 NUMA 节点，region 按节点分配（-XX:+UseNUMA）
      → 减少跨节点内存访问（延迟降低）
Large Page：-XX:+UseLargePages（2MB/1GB 页）
      → 减少 TLB miss（大内存应用收益明显）
      → 配置：-XX:LargePageSizeInBytes=2m
```

**P9 答法**："G1 支持 NUMA-aware 分配（`-XX:+UseNUMA`，region 按节点分配）和大页（`-XX:+UseLargePages`，减少 TLB miss）。大内存 + NUMA 架构生产建议开启。"

---

## #29 String Deduplication（实测）

### 实测（StringDedupDemo + `-XX:+UseStringDeduplication`）

```
Concurrent String Deduplication 480.7K->9752.0B(471.1K) avg 98.0%  ← 去重率 98%
  Inspected:  12025
  Deduplicated: 10079 (97.5%)  471.1K (98.0%)  ← 节省 471KB
  Old: 10079 (100%)  ← 全是 Old 区去重
```

### 机制

```
String 内部 = byte[] value（字符数组）
  → 相同内容的 String 共享同一 byte[]（去重）
  → 触发：String 对象被 GC 复制时（Young/Mixed GC 期间）
  → 后台线程 g1StringDedupThread 处理
  → 只去重"新复制的"String（不扫描全堆）
```

**P9 答法**："G1 String Dedup：GC 复制 String 时后台线程去重（相同 byte[] 共享）。实测去重率 97.5%，节省 98% 字符数组内存。`-XX:+UseStringDeduplication` 开启（JDK8u20+）。"

---

## #30 SafePoint 与 GC 关系（实测）

### 实测（`-Xlog:safepoint` + `jcmd GC.run`）

```
Entering safepoint region: EnableBiasedLocking
  Total time stopped: 0.000585s, Stopping threads: 0.000224s
Entering safepoint region: G1CollectForAllocation    ← GC 触发 safepoint
  Total time stopped: 0.004s
Application time: 0.048s                             ← 应用运行时间
```

### 机制

```
SafePoint = JVM 的"全体停"检查点
  → 所有应用线程到达 SafePoint（循环回边/方法返回/异常点）
  → 到达后 → VMThread 执行 GC（STW）
  → GC 完成 → 恢复应用线程

GC 触发 SafePoint 的方式：
  1. 主动请求：VMThread::execute（jcmd GC.run / GC 触发）
  2. 被动等待：分配失败/安全点轮询（os::check_pages）
```

**P9 答法**："GC 暂停的本质是 SafePoint：所有线程到达 SafePoint 后 VMThread 执行 GC。`-Xlog:safepoint` 可看每次 SafePoint 的停止时间和原因（G1CollectForAllocation/G1CollectFull 等）。"

---

## 深挖考点速查（#21-#30）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Concurrent Refinement** | 写屏障标 dirty card → 后台线程处理进 RSet（yellow/red zone 调速） | #21（源码） |
| **Card↔RSet 链路** | 写屏障→dirty card→Refine 线程→RSet→GC 用 RSet 定位 | #22（源码+叙事） |
| **PLAB** | GC 线程分配缓冲（对应 TLAB），Survivor/Old 各一个 | #23（叙事） |
| **Adaptive IHOP** | JDK9+ 默认自适应（基于分配速率+标记耗时预测动态调阈值） | #24（源码） |
| **Evacuation Failure** | 复制空间不够→本地缓冲自救→失败转 Full GC | #25（叙事） |
| **Full GC 并行** | JDK10+ 标记+压缩并行（仍 STW） | #26（叙事） |
| **Region 大小算法** | max(堆均值/2048, 1MB)，2 的幂，[1MB,32MB] | #27（源码公式） |
| **NUMA/LargePage** | NUMA-aware 分配 + 大页减少 TLB miss | #28（叙事） |
| **String Dedup** | GC 复制 String 时后台去重，实测 97.5% 去重率 | #29（实测） |
| **SafePoint 与 GC** | GC 暂停=SafePoint，所有线程到达后 VMThread 执行 | #30（实测） |

---

# 第四部分：深挖补全 #31-#40

## #31 RSet 粗/细粒度切换（源码实证）

### 三级结构（`heapRegionRemSet.cpp:346-380`）

```
引用进来 → ① FromCardCache 去重（per-thread 缓存）
         → ② Sparse Table（稀疏表，引用少时，card 位图）
         → ③ PerRegionTable（细粒度，card 位图，最多 N 个）
         → ④ Coarse Map（粗粒度，整 region 标记，退化兜底）
```

### 源码逻辑

```cpp
void OtherRegionsTable::add_reference(...) {
  if (_coarse_map.at(from_hrm_ind)) return;      // 已粗粒度，直接返回
  // 尝试 Sparse Table
  if (G1HRRSUseSparseTable && _sparse_table.add_card(...)) return;
  // 找 PerRegionTable；满了 → 加锁创建/扩容
  // PerRegionTable 数量超限 → 退化为 Coarse Map（整 region 标记）
}
```

**P9 答法**："RSet 三级退化：Sparse Table（稀疏）→ PerRegionTable（细粒度 card 位图）→ Coarse Map（粗粒度整 region）。引用太多时退化为粗粒度，GC 时整 region 扫描（牺牲精度换空间）。"

---

## #32 Card Table 布局与字节值（源码实证）

### 常量（`g1CardTable.hpp`）

```cpp
enum G1CardValues {
  g1_young_gen = CT_MR_BS_last_reserved << 1   // young card 特殊值
  // dirty_card_val() / clean_card_val() / claimed_card_val() / deferred_card_val()
};
static jbyte g1_young_card_val() { return g1_young_gen; }
```

### Card 值语义

| 值 | 含义 |
|---|---|
| clean | 干净（无跨 region 引用） |
| dirty | 脏（有引用待处理） |
| **g1_young_gen** | Young region 的 card（不进 RSet，Young GC 不需要） |
| claimed | 被 GC worker 认领处理中 |
| deferred | 延迟处理（并发精化推迟） |

**Card 大小**：默认 512B（`G1ConcRefinementCardSize`），一个 1MB region = 2048 卡。

**P9 答法**："Card Table 每 card 一字节，值有 clean/dirty/g1_young_gen/claimed/deferred。Young card 特殊标记（不进 RSet，Young GC 不扫）。Card 默认 512B。"

---

## #33 Mixed GC CSet 排序算法（Garbage First）

### 排序逻辑

```
候选 Old region（垃圾比例 > G1MixedGCLiveThresholdPercent，即存活 < 85%）
  → 按"垃圾比例"降序排序（垃圾最多的在前）
  → 依次加入 CSet，直到预测停顿达标或 CSet 大小上限
  → 名字由来："Garbage First" = 优先回收垃圾最多的 region
```

### CSet 大小约束

```
-XX:G1MixedGCCountTarget=8     ← 一次并发周期后分 8 轮 Mixed GC
-XX:G1OldCSetRegionThresholdPercent=10  ← 每轮 Mixed 最多收 10% 的 Old region
```

**P9 答法**："CSet 排序：候选 Old region 按垃圾比例降序（Garbage First 名字由来），依次加入直到预测停顿达标。每轮 Mixed 最多收 `G1OldCSetRegionThresholdPercent`（默认10%）的 Old region，分 `G1MixedGCCountTarget`（默认8）轮完成。"

---

## #34 Region Commit/Uncommit（JDK14+）

### 版本差异

```
JDK11：G1 有 Uncommit 能力但默认不归还 OS（保留虚拟地址空间）
JDK14+：G1PeriodicGC（周期性 GC）+ 更积极的 Uncommit
  -XX:G1PeriodicGCInterval=0     ← 默认关（JDK14+ 可开）
  -XX:G1PeriodicGCInvokesConcurrent=true
  -XX:G1PeriodicGCSystemLoadThreshold=0.0  ← 系统负载阈值
```

### 机制

```
空闲 region → Uncommit（归还物理页给 OS，保留虚拟地址）
  → 需要时 → Commit（重新分配物理页）
  → JDK14+ G1PeriodicGC：空闲时周期性触发 GC + Uncommit
```

**P9 答法**："JDK11 G1 有 Uncommit 但默认不归还 OS；JDK14+ 引入 `G1PeriodicGC`（空闲时周期性 GC+归还）。生产内存敏感场景可开 `-XX:G1PeriodicGCInterval`。"

---

## #35 Old Gen 碎片化问题

### 诊断（GC 日志）

```
Old regions: 426->427  ← Mixed GC 后 Old 不减反增（碎片化/晋升）
Humongous regions: 0->0 ← 无 humongous（排除大对象碎片）
```

### 碎片化来源

```
1. Mixed GC 只收垃圾多的 region → 剩下"垃圾少+存活多"的 region（碎片）
2. Humongous 分配失败 → 连续 region 不够（碎片化）
3. 晋升失败 → Survivor 溢出到不连续 Old region
```

### 诊断工具

```
jcmd <pid> GC.heap_info          ← 看 Old region 数量与分布
jcmd <pid> GC.class_histogram    ← 看老年代对象分布
jmap -histo:live                  ← 强制 Full GC 后看存活对象
GC 日志: Old regions 趋势 + Humongous regions 数量
```

**P9 答法**："Old 碎片化来源：Mixed GC 只收垃圾多的 region（剩下碎片）、humongous 分配失败、晋升失败。诊断：看 GC 日志 Old regions 趋势 + Humongous 数量 + `jcmd GC.heap_info`。"

---

## #36 SATB 队列溢出处理（了解级）

```
per-thread SATB 缓冲满了 → Enqueue 到全局 SATB 队列（Shared_SATB_Q_lock）
全局队列也满 → ① GC 线程加速 drain（处理更快）
              ② 应用线程阻塞等待（极少发生）
              ③ 连续溢出 → 并发标记 abort（转 Full GC）
```

**P9 答法**："SATB 队列溢出：per-thread 满→全局队列；全局满→GC 加速 drain 或应用阻塞；连续溢出→并发标记 abort。生产极少发生（缓冲够大）。"

---

## #37 Humongous Region 的 Pinning

```
Humongous region 分配后 → pinned（钉住）
  → 不参与普通 Young/Mixed GC 的 region 重用
  → 只能等并发标记确认垃圾后回收（或 Full GC）
  → 好处：大对象地址稳定（不移动，减少指针更新）
  → 代价：碎片化风险（连续 region 被钉住）
```

**P9 答法**："Humongous region 分配后被 pinned（不移动、不参与普通回收），只等并发标记回收。好处是大对象地址稳定，代价是碎片化风险。"

---

## #38 Pause Prediction 具体算法（了解级）

### 机制（`g1Predictions.hpp`）

```
G1 维护每个 region 的回收耗时统计（AdaptiveWeightedAverage）
  → 加权平均：新样本权重高，旧样本衰减
  → 预测：median + max（保守估计）
  → CSet 选择：累加预测耗时，直到接近 MaxGCPauseMillis
```

### 统计对象

```
每个 region 的：扫描耗时 + 转移耗时 + RSet 更新耗时
  → CSet 总预测 = Σ(选中 region 的预测耗时)
  → 目标：CSet 总预测 ≤ MaxGCPauseMillis
```

**P9 答法**："G1 停顿预测：每个 region 维护回收耗时的加权平均（新样本权重高），预测 median+max。CSet 选择时累加预测直到接近 MaxGCPauseMillis。目标是上限非精确值。"

---

## #39 Region top/end 指针分配（了解级）

### 源码（`heapRegion.hpp:48-56`）

```cpp
// Each heap region is self contained. top() and end() can never
// be set beyond the end of the region. For humongous objects,
// the first region's top() points to the end of the object,
// and continues regions have top() == end().
```

### 分配机制

```
Region 内部：bump pointer 分配
  bottom() → 已分配起点
  top()    → 当前分配位置（bump pointer）
  end()    → region 结束
  分配：obj = top; top += size;  （无锁，TLAB 内）

Humongous：
  StartsHumongous.top() = 对象末尾
  ContinuesHumongous.top() = end()（已满）
```

**P9 答法**："Region 内部分配用 bump pointer（bottom→top→end），TLAB 内无锁。Humongous 的 Starts region top=对象末尾，Continues region top=end。"

---

## #40 G1 与 Biased Locking 交互

```
GC 需要移动对象 → 偏向锁的线程 ID 在对象头（mark word）
  → 移动后 mark word 要更新（偏向线程 ID 不变）
  → JDK15+ 偏向锁默认关（多线程撤销开销大）
  → JDK11：GC 时可能触发 RevokeBias SafePoint（撤销偏向锁）
  → 实测：-Xlog:safepoint 可见 "RevokeBias" 事件
```

**P9 答法**："G1 移动对象时需处理偏向锁（mark word 里的线程 ID）。JDK11 GC 可能触发 RevokeBias SafePoint；JDK15+ 偏向锁默认关（JEP 374），此交互消失。"

---

## 深挖考点速查（#31-#40）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **RSet 退化** | Sparse→PerRegionTable→Coarse（引用太多时粗粒度兜底） | #31（源码） |
| **Card 值** | clean/dirty/g1_young_gen/claimed/deferred；512B/card | #32（源码） |
| **CSet 排序** | 按垃圾比例降序（Garbage First），每轮最多 10% Old region | #33（源码+叙事） |
| **Region Commit** | JDK11 有但默认不归还；JDK14+ G1PeriodicGC 周期归还 | #34（JDK21 flag） |
| **Old 碎片化** | Mixed 只收垃圾多的 region/humongous/晋升失败；看日志诊断 | #35（日志+叙事） |
| **SATB 溢出** | per-thread→全局→GC 加速 drain/应用阻塞/abort | #36（叙事） |
| **Humongous Pinning** | 分配后 pinned（不移动），只等并发标记回收 | #37（叙事） |
| **Pause Prediction** | 每 region 加权平均耗时，CSet 累加到 MaxGCPauseMillis | #38（源码+叙事） |
| **Region 分配** | bump pointer（bottom→top→end），TLAB 内无锁 | #39（源码） |
| **Biased Lock 交互** | GC 移动对象需处理偏向锁；JDK11 RevokeBias；JDK15+ 默认关 | #40（safepoint 实测） |
