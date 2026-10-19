# D1: 滑动窗口（LeapArray）— Sentinel 的高并发统计基石

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/statistic/`
> 核心文件: `base/LeapArray.java`(420行) / `base/WindowWrap.java`(99行) / `data/MetricBucket.java`(LongAdder[]计数器)
> 默认参数: `SampleCountProperty.SAMPLE_COUNT=2` / `IntervalProperty.INTERVAL=1000`（→ `RuleConstant.DEFAULT_WINDOW_INTERVAL_MS`）
> 关联: D3 FlowSlot 读取滑动窗口数据判断流控；D4 DegradeSlot 读取窗口判断熔断

---

## 一、问题背景：为什么需要滑动窗口？

限流器要判断"当前 QPS 是否超限"，必须知道"过去 1 秒来了多少请求"。最简单的做法是设一个 `AtomicLong counter`，每秒重置一次——但这有**精度缺口**：假如 0.9秒~1.1秒之间来了大量请求，1秒重置的固定窗口会让这波流量穿过两个窗口"缝隙"，导致实际 QPS 远超限制。

```
固定窗口的精度陷阱：
  window_0 (0~1s) → counter=950     ← 0.9~1.0s 来了 900 请求
  window_1 (1~2s) → counter=50      ← 1.0~1.1s 又来了 900 请求
  实际 0.9~1.1s 的 QPS ≈ 1800，但两个窗口各自没超 1000 —— 漏了！
```

Sentinel 的答案：**滑动窗口 = 固定大小的环形数组**。把整个统计区间（如 1 秒）等分成 N 个小格（如 2 格=500ms 一格），每格独立计数。当前时间到了哪一格就对那一格计数——时间流逝时，过期格被新数据覆盖。**N 越大，精度越高**。Sentinel 默认 `sampleCount=2`（2 格，500ms 一格）。

```
滑动窗口：sampleCount=2, intervalInMs=1000
  ||____B0____|____B1____|____B0____|____B1____||___  timeline
  0         500       1000       1500       2000
              ↑ time=750ms → 打在 B1 上
  1秒窗口 QPS = B0(0~500ms) + B1(500~1000ms) 的总和
```

---

## 二、LeapArray 核心数据结构

**位置**: `LeapArray.java:L41-53`

```java
public abstract class LeapArray<T> {
    protected int windowLengthInMs;            // L43: 每格长度 = intervalInMs / sampleCount
    protected int sampleCount;                 // L44: 总格数
    protected int intervalInMs;                // L45: 统计区间总长度（默认 1000ms）
    private double intervalInSecond;           // L46: 秒级单位（QPS 计算用）

    protected final AtomicReferenceArray<WindowWrap<T>> array;  // L48: ★ 环形数组
    private final ReentrantLock updateLock = new ReentrantLock(); // L53: 写锁
}
```

**设计要点**：

1. **`AtomicReferenceArray<WindowWrap<T>>` (L48)**：固定大小的原子引用数组。`sampleCount=2` 时只有 2 个槽位，时间前进时槽位被循环覆盖——这是"环形"的含义。CAS 保证多线程并发修改同一个槽位时的线程安全。

2. **`ReentrantLock updateLock` (L53)**：用于窗口过期时的重置操作。注意是 `tryLock()` 非阻塞获取——竞争不到锁的线程直接 `Thread.yield()` 让出 CPU，等待持有锁的线程完成重置后重试。这是"无锁读、有锁写"的策略。

3. **泛型抽象类 `LeapArray<T>`**：`T` 是每个窗口格存储的数据类型。Sentinel 的 `BucketLeapArray extends LeapArray<MetricBucket>`，每个格是一个 `MetricBucket`（内部维护 `LongAdder[] counters` 数组）。

**构造方法**（`LeapArray.java:L61-72`）：

```java
public LeapArray(int sampleCount, int intervalInMs) {
    this.windowLengthInMs = intervalInMs / sampleCount;   // L67: 每格长度
    this.intervalInMs = intervalInMs;
    this.intervalInSecond = intervalInMs / 1000.0;
    this.sampleCount = sampleCount;

    this.array = new AtomicReferenceArray<>(sampleCount);  // L72: 初始化环形数组
}
```

**默认参数**：`SampleCountProperty.SAMPLE_COUNT=2`（`SampleCountProperty.java:L39`），`IntervalProperty.INTERVAL=1000`（→ `RuleConstant.DEFAULT_WINDOW_INTERVAL_MS`（`RuleConstant.java:L66`），可运行时动态调整。由此 `windowLengthInMs=500`，即每秒 2 格、每格 500ms。

---

## 三、时间到索引的映射：`calculateTimeIdx`

**位置**: `LeapArray.java:L100-104`

```java
private int calculateTimeIdx(long timeMillis) {
    long timeId = timeMillis / windowLengthInMs;    // L101: 时间除以每格长度 → 逻辑时间片序号
    return (int)(timeId % array.length());           // L103: 对数组长度取模 → 映射到环形数组索引
}
```

**举例**：`sampleCount=2, windowLengthInMs=500`，数组长度为 2

| 当前时间 | timeId = time/500 | idx = timeId % 2 | 打在哪个槽 |
|---|---|---|---|
| 750ms | 1 | 1 | `array[1]` |
| 1250ms | 2 | 0 | `array[0]`（覆盖了 0~500ms 的旧数据） |
| 1750ms | 3 | 1 | `array[1]`（覆盖了 500~1000ms 的旧数据） |

**核心洞察**：`timeId` 是单调递增的，`% array.length()` 把它映射到固定大小的环形数组上——时间流逝时，旧槽位被新数据自然覆盖。不需要显式的"清理"操作。

---

## 四、核心方法：`currentWindow(timeMillis)` — 四种情况的 CAS 博弈

**位置**: `LeapArray.java:L116-208`（93 行，是整个 LeapArray 的心脏）

```java
public WindowWrap<T> currentWindow(long timeMillis) {
    int idx = calculateTimeIdx(timeMillis);                // L121
    long windowStart = calculateWindowStart(timeMillis);   // L123

    while (true) {
        WindowWrap<T> old = array.get(idx);                // L133: 读当前槽位的窗口
        if (old == null) {                                 // L134: 情况① 槽位为空
            WindowWrap<T> window = new WindowWrap<T>(
                windowLengthInMs, windowStart, newEmptyBucket(timeMillis));
            if (array.compareAndSet(idx, null, window)) {  // L149: CAS 竞争
                return window;
            } else {
                Thread.yield();                             // L154: CAS失败→让出CPU
            }
        } else if (windowStart == old.windowStart()) {      // L159: 情况② 槽位有效
            return old;
        } else if (windowStart > old.windowStart()) {       // L167: 情况③ 槽位过期
            if (updateLock.tryLock()) {                     // L186: 获取写锁
                try {
                    return resetWindowTo(old, windowStart); // L189: 重置窗口
                } finally {
                    updateLock.unlock();
                }
            } else {
                Thread.yield();                             // L194: 锁竞争失败→让出CPU
            }
        } else {                                            // L197: 情况④ 时钟回拨
            return new WindowWrap<T>(windowLengthInMs, windowStart, 
                newEmptyBucket(timeMillis));
        }
    } // while(true)
}
```

**四种情况的完整分析**：

| 情况 | 条件 | 操作 | 并发安全 |
|------|------|------|------|
| ① 槽位为空 | `old == null` | `new WindowWrap` + `CAS(idx, null, window)` | CAS 保证只有一个线程写入成功 |
| ② 窗口未过期 | `windowStart == old.windowStart()` | 直接返回 old | 无锁读，读到的数据可能有 1 个 tick 的延迟 |
| ③ 窗口已过期 | `windowStart > old.windowStart()` | `tryLock` → `resetWindowTo(old, windowStart)` | ReentrantLock 保证只有一个线程做重置 |
| ④ 时钟回拨 | `windowStart < old.windowStart()` | 返回新窗口但不更新环形数组 | 隔离异常数据，不影响其他槽位 |

**为什么用 `tryLock()` 而不是 `lock()`？** L186 的 if-else 分支显示：抢不到锁的线程直接 `Thread.yield()`，然后回到 `while(true)` 重新开始循环——这时拥有锁的线程已经完成了 `resetWindowTo()`，所以重试时大概率命中情况②（窗口未过期），直接返回。这是"用短时等待替代阻塞"的优化——避免 `lock()` 的上下文切换开销。

**CAS (L149) 的语义**：`array.compareAndSet(idx, null, window)` 只在 `array[idx]` 当前值为 `null` 时才写入 `window`。多个线程同时到达情况①时，只有一个 CAS 成功，其余失败 → yield → 重试时 array[idx] 已被成功线程写入 → 命中情况② → 直接返回。

---

## 五、发散：Sentinel 的 LeapArray vs 其他滑动窗口实现

| 维度 | Sentinel LeapArray | Hystrix RollingNumber | 朴素 LinkedBlockingQueue |
|------|------|------|------|
| 数据结构 | `AtomicReferenceArray` + CAS | 环形缓冲区 + 写锁 | 链表 + 全局锁 |
| 写入路径 | CAS 无锁（情况①②） / tryLock（情况③） | 写锁（全局） | 锁（全局） |
| 读取路径 | 无锁（遍历数组累加） | 无锁（遍历环形缓冲区） | 锁（全局） |
| 窗口粒度 | 固定等分（如 2格 × 500ms） | 固定等分 | 无固定粒度 |
| 垃圾回收 | 时间驱动自动覆盖 | 显式更新 | 需要手动清理 |

**LeapArray 的最大创新**：把"清理过期数据"这个动作**自然融入时间索引映射**。`calculateTimeIdx` 的取模操作让时间流逝自动覆盖旧槽位——不需要后台定时任务，不需要显式标记"过期"，不需要清理线程。这是"算法减负"的典范。

---

## 六、面试考点

**Q1: Sentinel 的 LeapArray 如何实现无锁统计？滑动窗口 vs 滑动计数器有什么区别？**

`LeapArray` 用 `AtomicReferenceArray` + CAS 实现了绝大部分路径的无锁：
- 槽位空 → CAS 插入（无锁）
- 槽位有效 → 直接读（无锁）
- 槽位过期 → `tryLock`（有锁，但非阻塞）
- 时钟回拨 → 直接返回（无锁隔离）

滑动**窗口**统计的是**时间区间**内的总量（如过去 1 秒的 QPS），滑动**计数器**只维护一个当前值。窗口的优势是可以按时间维度切片（如 500ms 一格），计数器的优势是简单但精度低。

**追问**: `tryLock()` 失败时为什么 `Thread.yield()` 而不是 `lock()`？

`tryLock()` 失败意味着另一个线程正在重置窗口。`yield()` 让出 CPU 后回到 `while(true)` 重试——此时持有锁的线程大概率已完成重置，重试直接返回（情况②）。`lock()` 会导致当前线程阻塞等待，带来上下文切换开销。在窗口重置这种"毫秒级操作"中，yield + 重试比 block + 唤醒更高效。

**Q2: `sampleCount=2` 和 `sampleCount=10` 的权衡？**

| sampleCount | 每格长度 | 精度 | 内存 |
|:--:|:--:|:--:|:--:|
| 2 | 500ms | 低（半个窗口的延迟） | 2 × MetricBucket |
| 10 | 100ms | 高 | 10 × MetricBucket |
| 20 | 50ms | 最高 | 20 × MetricBucket（但 `intervalInMs % sampleCount != 0` → 断言失败） |

Sentinel 默认 `sampleCount=2`，是精度和内存的折中。对于大多数 QPS 限流场景，500ms 的精度足够——流控判断的是"过去 1 秒的总量"，不需要毫秒级精度。过度提高 sampleCount 不会提升限流准确性，反而增加遍历开销（`values()` 需要遍历 sampleCount 个槽位）。

---

## 七、生产陷阱

1. **`intervalInMs % sampleCount != 0` 断言失败**：构造方法 L64 要求 `intervalInMs % sampleCount == 0`。如果配置 `sampleCount=3, intervalInMs=1000`（1000/3 除不尽），直接抛异常。生产上修改采样参数时必须保证整除关系。

2. **时钟回拨（L197-200）的处理是"隔离"而非"纠正"**：当 NTP 同步导致系统时钟倒退时，`windowStart < old.windowStart()` → 返回一个新窗口但**不更新环形数组**。这意味着回拨期间的数据不会被统计到——这是"宁可丢数据也不污染历史数据"的设计。配合监控告警（检测到时钟回拨事件），运维可以及时介入。

3. **`values()` 方法是"快照"不是"精确值"**：`values()` 遍历 `array` 时没有加锁，读取过程中其他线程可能在并发修改——得到的是"近似一致"的快照。对于流控判断（"QPS 超 1000 了吗？"），近似值足够；对于计费稽核，不足。
