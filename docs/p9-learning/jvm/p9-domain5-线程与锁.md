# p9-domain5 · 线程/安全点/锁（P9 深度实验）

> P9 面试核心域 5/6。实验目录：`experiments/jvm-diagnostics/14-p9-thread-lock/`
> 覆盖：线程模型/状态/锁膨胀/safepoint/虚拟线程/死锁检测

## 生产基线

```
-Xms512m -Xmx512m -XX:+UseG1GC -XX:+AlwaysPreTouch
```

---

## 实验 1：线程模型 + 6 种状态

### 实测（ThreadStateDemo）

```
NEW           → 初始未 start
RUNNABLE      → 运行中（含等待 CPU/IO）
BLOCKED       → 等锁（synchronized）
WAITING       → 无限等（wait/join/park）
TIMED_WAITING → 超时等（sleep/wait(timeout)）
TERMINATED    → 结束
```

### 线程模型

```
Java Thread = 1:1 映射 OS 线程（pthread_create）
  → 创建/切换/销毁由 OS 调度
  → 上下文切换开销：用户态→内核态
  → 线程栈：默认 1MB（-Xss）
```

### P9 追问链

- **Q：Java 线程模型？** → 1:1 映射 OS 线程（HotSpot），非 M:N
- **Q：RUNNABLE 包含什么？** → 可运行 + 正在运行（不区分等待 CPU）
- **Q：BLOCKED vs WAITING？** → BLOCKED=等锁（synchronized）；WAITING=主动等（wait/join）

---

## 实验 2：锁膨胀全程（mark word 四态）

### 实测（LockEscalationDemo + JOL，持锁时读 mark word）

```
1) 无锁:      0x0000000000000005 (biasable; age: 0)
2) 偏向锁:    0x0000716630010005 (biased: 0x...; epoch: 0)   ← 线程 ID 写入
3) 轻量级:    0x00007165fa4fe868 (thin lock: 0x...)          ← ★ 持锁时读到 thin lock
4) 重量级:    0x00007165c4001782 (fat lock: 0x...)           ← ★ 竞争后持锁时读到 fat lock
```

### 锁膨胀流程

```
无锁（01）
  → 偏向锁（01+偏向位）：单线程进入，mark word 写线程 ID
     → 偏向撤销（revoke）：第二个线程进入 → 撤销偏向 → 轻量级
  → 轻量级锁（00）：CAS 在栈帧建 Lock Record，替换 mark word
     → 竞争失败 → 膨胀 → 重量级
  → 重量级锁（10）：mark word 指向 Monitor 对象（OS mutex/condvar）
     → 阻塞等待（park/unpark）
```

### 偏向锁版本差异

```
JDK11：默认开（单线程无开销）
JDK15：弃用（JEP 374，多线程撤销开销 > 收益）
JDK18+：默认关
```

### P9 追问链

- **Q：锁膨胀顺序？** → 无锁→偏向→轻量→重量（只升不降）
- **Q：偏向锁干什么？** → 单线程无 CAS，mark word 写线程 ID（消除同步开销）
- **Q：轻量级锁原理？** → CAS 在栈帧建 Lock Record，替换 mark word 为指针
- **Q：重量级锁原理？** → Monitor 对象（OS mutex），阻塞用 park/unpark（用户态）
- **Q：为什么只升不降？** → 降级需 STW（不值得），竞争减少时新对象重新走膨胀

---

## 实验 3：SafePoint（域3 已测，此处补充排查）

### SafePoint 卡住排查

```
症状：应用卡住不动
排查：
  1. jstack <pid>          ← 看线程状态（多数 BLOCKED/WAITING？）
  2. kill -3 <pid>         ← 触发 SIGQUIT，输出到 stdout
  3. jcmd <pid> Thread.print  ← 同 jstack
  4. -Xlog:safepoint       ← 看 SafePoint 耗时/原因
常见原因：
  - 锁竞争（大量 BLOCKED）
  - 死锁（jstack 检测 "Found deadlock"）
  - GC 停顿过长（-Xlog:gc 看 Pause 耗时）
  - SafePoint 耗时（JIT 编译/偏向锁撤销）
```

### P9 追问链

- **Q：应用卡住怎么排查？** → jstack 看线程状态 → 死锁/锁竞争/GC 停顿/SafePoint
- **Q：SafePoint 是什么？** → JVM 全停检查点，所有线程到达后 VMThread 执行操作
- **Q：什么操作需要 SafePoint？** → GC、偏向锁撤销、JIT 编译、debug 操作

---

## 实验 4：虚拟线程（JDK21 Loom）

### 实测（VirtualThreadDemo，JDK21）

```
10 万虚拟线程完成，382ms（平台线程会 OOM）
1000 平台线程完成，244ms
isVirtual=true
```

### 虚拟线程 vs 平台线程

| | 平台线程 | 虚拟线程 |
|---|---|---|
| 映射 | 1:1 OS 线程 | M:N（多个虚拟→少量 OS carrier） |
| 栈 | 1MB 固定 | 按需增长（KB 级） |
| 数量 | 千级（OS 限制） | 百万级 |
| 阻塞 | 阻塞 OS 线程 | 阻塞虚拟线程，carrier 去跑别的 |
| 调度 | OS 调度 | JVM 调度（ForkJoinPool） |
| JDK | 全版本 | JDK21+ 稳定 |

### P9 追问链

- **Q：虚拟线程原理？** → M:N 调度，多个虚拟线程映射少量 OS carrier，阻塞时 carrier 让出
- **Q：什么场景用虚拟线程？** → IO 密集（网络/磁盘），阻塞多、CPU 少
- **Q：什么场景不用？** → CPU 密集（无阻塞收益）、synchronized 长临界区（carrier 被占）
- **Q：虚拟线程坑？** → ThreadLocal 内存放大、synchronized pinning（JDK21 改进）、不建议池化

---

## 实验 5：死锁检测

### 实测（DeadlockDemo + jstack）

```
Found one Java-level deadlock:
"Deadlock-T1": waiting to lock object 0x...c90, held by "Deadlock-T2"
"Deadlock-T2": waiting to lock object 0x...c80, held by "Deadlock-T1"
Found 1 deadlock.
```

### 死锁排查流程

```
1. jstack <pid>            ← 自动检测死锁（Found deadlock）
2. jcmd <pid> Thread.print ← 同 jstack
3. 看 "Found deadlock" 段  ← 两线程交叉持锁的证据
4. 修复：统一加锁顺序 / tryLock 超时 / 减少锁粒度
```

### P9 追问链

- **Q：死锁怎么排查？** → jstack 自动检测（Found deadlock），看两线程持锁/等锁关系
- **Q：死锁条件？** → 互斥/占有且等待/不可剥夺/循环等待
- **Q：怎么预防？** → 统一加锁顺序、tryLock 超时、减少锁粒度、避免嵌套锁

---

## 补充：synchronized vs ReentrantLock（P9 对比题）

| | synchronized | ReentrantLock |
|---|---|---|
| 实现 | Monitor（mark word） | AQS（AbstractQueuedSynchronizer） |
| 释放 | 自动（出作用域） | 手动 unlock（finally） |
| 可中断 | 不可 | lockInterruptibly() |
| 超时 | 不可 | tryLock(timeout) |
| 公平锁 | 非公平 | 可选公平/非公平 |
| 条件变量 | wait/notify | 多个 Condition |
| 性能 | JDK6+ 接近 | 接近（略慢） |
| 选择 | 简单同步 | 需要高级功能时 |

---

## 补充：线程池参数（P9 高频，简版）

```
corePoolSize    核心线程数（常驻）
maximumPoolSize 最大线程数
keepAliveTime   非核心线程存活时间
workQueue       工作队列（LinkedBlockingQueue/ArrayBlockingQueue/SynchronousQueue）
handler         拒绝策略（AbortPolicy/CallerRunsPolicy/DiscardPolicy/DiscardOldestPolicy）
```

**P9 答法**："线程池参数：核心/最大线程数、存活时间、工作队列、拒绝策略。任务进来→核心满→队列满→非核心→拒绝。"

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 实验背书 |
|---|---|---|
| 线程模型 | 1:1 映射 OS 线程（HotSpot） | 实验 1 |
| 6 种线程状态 | NEW/RUNNABLE/BLOCKED/WAITING/TIMED_WAITING/TERMINATED | 实验 1 |
| 锁膨胀 | 无锁→偏向→轻量→重量（只升不降） | 实验 2（mark word 四态） |
| 偏向锁 | 单线程无 CAS，mark word 写线程 ID；JDK18+ 默认关 | 实验 2 |
| 轻量级锁 | CAS 在栈帧建 Lock Record，替换 mark word | 实验 2 |
| 重量级锁 | Monitor 对象（OS mutex），park/unpark 阻塞 | 实验 2 |
| SafePoint | JVM 全停检查点，GC/偏向撤销/JIT 需要 | 域3 实验+实验 3（排查） |
| 虚拟线程 | M:N 调度，百万级并发，IO 密集场景 | 实验 4（JDK21 实测） |
| 死锁检测 | jstack 自动检测（Found deadlock） | 实验 5 |
| synchronized vs ReentrantLock | Monitor vs AQS；自动 vs 手动；简单 vs 高级 | 补充（对比表） |
| 线程池参数 | 核心/最大/存活/队列/拒绝策略 | 补充（简版） |

## 相关文件

- `14-p9-thread-lock/ThreadStateDemo.java`：6 种线程状态
- `14-p9-thread-lock/LockEscalationDemo.java`：锁膨胀全程（JOL mark word）
- `14-p9-thread-lock/DeadlockDemo.java`：死锁演示
- `14-p9-thread-lock/VirtualThreadDemo.java`：虚拟线程（JDK21）
- `/data/workspace/arthas-output/deadlock.log`：死锁日志
