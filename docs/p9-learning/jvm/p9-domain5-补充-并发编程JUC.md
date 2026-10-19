# p9-juc · 并发编程 JUC（Java SE 层面，独立轨道）

> ⚠️ **本轨道是 Java SE/JUC 层面（java.util.concurrent 库），不属于 JVM 轨道。**
> JVM 轨道域5 = 线程模型/SafePoint/锁膨胀(mark word)/虚拟线程/JMM（HotSpot 实现层）。
> 实验目录：`experiments/jvm-diagnostics/14-p9-thread-lock/`
> 覆盖：AQS / CAS+ABA / volatile / JMM / ThreadLocal / 线程池 / 并发容器 / 原子类 / 并发工具 / 自旋

---

## #41 AQS（AbstractQueuedSynchronizer，源码实证）

### 核心结构（源码注释）

```java
// 原子 int state 表示同步状态
private volatile int state;
// CLH 变体双向队列（等待线程）
private volatile Node head, tail;
```

### AQS 模板方法

```
acquire：state CAS 改为 1 → 成功则独占；失败则入 CLH 队列阻塞
release：state 改为 0 → 唤醒队列头后继

子类实现：
  ReentrantLock：state=重入次数
  Semaphore：state=许可数
  CountDownLatch：state=计数
  ReadWriteLock：state=高16位读锁+低16位写锁
```

### P9 答法

"AQS = 原子 state + CLH 等待队列。acquire 用 CAS 改 state，成功独占，失败入队阻塞；release 改 state 唤醒后继。ReentrantLock/Semaphore/CountDownLatch 都是它的子类，state 语义不同。"

---

## #42 CAS 原理与 ABA（实测）

### CAS 基础（实测）

```
10 线程 × 10000 自增 = 100000（AtomicInteger 无锁正确）
```

### ABA 问题（实测）

```
t1: 1→2→1（先改走再改回）
t2: CAS(1→999): true ← ABA 陷阱（t2 以为还是 1，实际经历过 1→2→1）
```

### 解法

```
1. 版本号：AtomicStampedReference（int stamp 版本号）
2. 时间戳：AtomicMarkableReference（boolean 标记）
3. 不可变对象：直接换对象引用（无 ABA）
```

### P9 答法

"CAS = Compare-And-Swap（CPU 原子指令 lock cmpxchg）。ABA 问题：值没变但中间变过。解法：AtomicStampedReference 加版本号。"

---

## #43 volatile 原理（实测 + 源码）

### 可见性实测

```
非 volatile: 线程未退出（JIT 优化成 while(true)，看不到主内存修改）
volatile:    线程退出（可见性保证）
```

### 内存屏障（源码 `orderAccess.hpp`）

```
volatile 写：StoreStore + StoreLoad 屏障（保证写之前操作完成，写之后读可见）
volatile 读：LoadLoad + LoadStore 屏障（保证读之后操作不重排到读之前）
```

### volatile vs synchronized

| | volatile | synchronized |
|---|---|---|
| 可见性 | ✓（写后读可见） | ✓ |
| 原子性 | ✗（i++ 不原子） | ✓ |
| 有序性 | ✓（禁止重排） | ✓ |
| 阻塞 | 不阻塞 | 阻塞 |

### P9 答法

"volatile：可见性+有序性（内存屏障），但不保证原子性（i++ 仍需锁）。写：StoreStore+StoreLoad；读：LoadLoad+LoadStore。double-check 单例的 instance 必须 volatile（防指令重排）。"

---

## #44 JMM（Java 内存模型）

### 主内存/工作内存

```
主内存（Main Memory）：所有线程共享（堆/静态变量）
工作内存（Working Memory）：每线程私有（CPU 寄存器/L1 缓存副本）
变量传递：线程读主内存→工作内存副本→修改→写回主内存
```

### Happens-Before 8 条规则（P9 高频）

```
1. 程序顺序规则：同线程内，前面操作 hb 后面操作
2. 锁规则：unlock hb 后续 lock
3. volatile 规则：volatile 写 hb 后续读
4. 线程启动：start() hb 线程内所有操作
5. 线程终止：线程内所有操作 hb join() 返回
6. 线程中断：interrupt() hb 中断检测
7. final 规则：构造函数内 final 写 hb 析构函数读
8. 传递性：A hb B, B hb C → A hb C
```

### P9 答法

"JMM 是规范（非实现）：主内存+工作内存抽象。happens-before 8 条规则保证有序性；volatile/锁/线程启动终止都建立 hb 边。实现层：HotSpot 用内存屏障+缓存一致性协议。"

---

## #45 ThreadLocal 原理与内存泄漏（实测）

### 实测（线程池场景）

```
task-0 thread=pool-1-thread-1 tl=true
task-2 thread=pool-1-thread-1 tl=true  ← 线程复用！
...
教训：线程池复用线程时，ThreadLocal 必须 remove()，否则内存泄漏
```

### 原理

```
每个 Thread 有 ThreadLocalMap（key=ThreadLocal 弱引用，value=强引用）
  → 线程存活时，ThreadLocalMap 及其 value 都存活
  → 线程池线程不销毁 → value 永不释放 → 内存泄漏
```

### 解法

```java
try {
    tl.set(value);
    // 业务逻辑
} finally {
    tl.remove();   // ★ 必须 remove
}
```

### P9 答法

"ThreadLocal：每个 Thread 有 ThreadLocalMap，key=ThreadLocal 弱引用，value=强引用。线程池复用线程时若不 remove，value 永不释放（线程不死）。生产必须 finally remove()。"

---

## #46 线程池原理深入（实测）

### execute 流程实测（core=2 max=3 queue=2）

```
task-0: pool=1 queue=0  ← 创建核心线程
task-1: pool=2 queue=0  ← 核心满
task-2: pool=2 queue=1  ← 入队
task-3: pool=2 queue=2  ← 队列满
task-4: pool=3 queue=2  ← 创建非核心线程
task-5: pool=3 queue=2  ← 非核心满（第 6 个会被拒绝）
```

### 流程图

```
任务提交
  → 当前线程数 < core？ → 创建核心线程执行
  → 队列未满？ → 入队
  → 当前线程数 < max？ → 创建非核心线程执行
  → 拒绝策略（Abort/CallerRuns/Discard/DiscardOldest）
```

### P9 答法

"线程池 execute：核心满→队列满→非核心→拒绝。生产选队列（IO 密集用无界要小心 OOM）+拒绝策略（CallerRunsPolicy 限流）。"

---

## #47 并发容器原理（ConcurrentHashMap）

### JDK7 vs JDK8

| | JDK7 Segment 分段锁 | JDK8 CAS+synchronized |
|---|---|---|
| 锁粒度 | Segment（16 段） | Node（桶头节点） |
| 数据结构 | Segment[] + HashEntry[] | Node[] + 链表→红黑树 |
| 并发度 | 16（固定） | 桶级（更高） |
| 锁升级 | 无 | 链表>8 且数组>64 → 红黑树 |

### JDK8 put 流程

```
1. 计算 hash → 定位桶
2. 桶空 → CAS 放入（无锁）
3. 桶非空 → synchronized 锁桶头节点 → 链表/红黑树插入
4. 链表≥8 且数组≥64 → 树化
```

### P9 答法

"ConcurrentHashMap JDK7 用 Segment 分段锁（16 段）；JDK8 改 CAS+synchronized（桶头节点锁），链表→红黑树（≥8 且数组≥64）。并发度从 16 提升到桶级。"

---

## #48 原子类（AtomicXxx / LongAdder）

### CAS 实现

```java
// AtomicInteger.incrementAndGet() 底层
public final int incrementAndGet() {
    return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
}
// unsafe.getAndAddInt：循环 CAS（while(!CAS(expected, expected+1))）
```

### LongAdder（高并发累加）

```
问题：AtomicInteger 高并发时 CAS 自旋竞争激烈
解法：LongAdder 分段累加（Cell[]），最后 sum 汇总
  → 热点分散到多个 Cell → CAS 竞争减少
  → 适合计数场景（读少写多）；不适合需要实时准确值
```

### P9 答法

"AtomicXxx = Unsafe CAS 循环；LongAdder 分段累加（Cell[]），高并发计数用 LongAdder（写分散），读准确值用 sum 汇总。"

---

## #49 并发工具（CountDownLatch/Semaphore/CyclicBarrier，实测）

### CountDownLatch（实测）

```
worker-0 完成 → worker-1 完成 → worker-2 完成 → 所有完成（主线程继续）
用途：主线程等 N 个子任务完成（一次性）
```

### Semaphore（实测）

```
task-0/task-1 获得许可（并发中）
task-2 等待... task-0 释放 → task-2 获得
用途：限流（最多 N 并发）
```

### 三者对比

| | CountDownLatch | Semaphore | CyclicBarrier |
|---|---|---|---|
| 语义 | 等 N 个任务完成 | 限 N 并发 | N 线程互相等齐 |
| 复用 | 一次性 | 可重复 | 可循环（reset） |
| 底层 | AQS shared | AQS shared | ReentrantLock+Condition |

### P9 答法

"CountDownLatch=一次性等 N 个；Semaphore=限流 N 并发；CyclicBarrier=N 线程互相等齐（可循环）。都是 AQS 应用（state 语义不同）。"

---

## #50 自旋锁与自适应自旋（了解级）

```
自旋锁：轻量级锁竞争失败时不阻塞，循环 CAS 重试（消耗 CPU 换免上下文切换）
自适应自旋：JVM 根据上次自旋成功率动态调整自旋次数
  → 上次成功多 → 多自旋
  → 上次失败多 → 直接阻塞
```

### P9 答法

"轻量级锁竞争失败先自旋（CAS 重试），自适应自旋根据历史成功率调整次数。自旋适合短等待（省上下文切换），长等待应阻塞。"

---

## 补充考点速查（#41-#50）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **AQS** | 原子 state + CLH 队列；ReentrantLock/Semaphore/CountDownLatch 底层 | #41（源码） |
| **CAS** | lock cmpxchg 原子指令；AtomicXxx 底层 | #42（实测 10 万正确） |
| **ABA 问题** | 值没变但中间变过；解法 AtomicStampedReference 版本号 | #42（实测复现） |
| **volatile** | 可见性+有序性（内存屏障），不保证原子性；i++ 需锁 | #43（实测可见性） |
| **JMM** | 主内存+工作内存；happens-before 8 条规则 | #44（叙事） |
| **ThreadLocal 泄漏** | 线程池复用线程不 remove → value 永不释放 | #45（实测线程复用） |
| **线程池流程** | 核心满→队列满→非核心→拒绝 | #46（实测 pool=1→2→3） |
| **ConcurrentHashMap** | JDK7 分段锁→JDK8 CAS+synchronized 桶头+红黑树 | #47（叙事） |
| **LongAdder** | 分段累加（Cell[]），高并发计数 | #48（叙事） |
| **并发工具** | CountDownLatch=等N个/Semaphore=限流/CyclicBarrier=互相等齐 | #49（实测） |
| **自适应自旋** | 根据上次成功率动态调整自旋次数 | #50（叙事） |

## 相关文件

- `14-p9-thread-lock/CasAbaDemo.java`：CAS+ABA+并发工具
- `14-p9-thread-lock/VolatileThreadLocalDemo.java`：volatile+ThreadLocal
- `14-p9-thread-lock/ThreadPoolDemo.java`：线程池流程
- 源码：`openjdk11u/src/java.base/share/classes/java/util/concurrent/locks/AbstractQueuedSynchronizer.java`
