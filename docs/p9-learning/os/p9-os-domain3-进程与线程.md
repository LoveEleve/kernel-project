# p9-os-domain3 · 进程与线程（P9 深度实验）

> OS 轨道域 3/9。覆盖：task_struct/fork/上下文切换/CFS 调度/信号
> 对照 13 本书：现代操作系统 Ch5-6

---

## 实验 1：进程状态（/proc/<pid>/status 实测）

```
Name: java / State: S (sleeping)
Pid: 2866 / PPid: 2800
VmRSS: 1026956 kB（~1GB 物理内存）
Threads: 349
voluntary_ctxt_switches: 76（主动切换）
nonvoluntary_ctxt_switches: 9（被动切换/被抢占）
```

### 进程状态

```
R (Running)：运行中/可运行
S (Sleeping)：可中断睡眠（等待事件）
D (Disk Sleep)：不可中断睡眠（磁盘 I/O）
T (Stopped)：停止（SIGSTOP）
Z (Zombie)：僵尸（父进程未 wait）
X (Dead)：死亡
```

### task_struct（进程控制块）

```
Linux 用 task_struct 描述进程：
  进程 ID（pid/tgid）
  状态（state）
  内存信息（mm_struct：页表/堆栈）
  文件信息（files_struct：打开的文件）
  调度信息（sched_entity：CFS 调度实体）
  信号信息（signal_struct）
  线程信息（线程也是 task_struct，共享 mm/files）
```

### P9 追问链

- **Q：进程和线程区别？** → 进程独立地址空间；线程共享地址空间（task_struct 不同，mm_struct 共享）
- **Q：僵尸进程怎么产生？** → 子进程退出但父进程没 wait()（Z 状态）
- **Q：D 状态说明什么？** → 不可中断睡眠（磁盘 I/O 等待，kill 无效）

---

## 实验 2：上下文切换（vmstat + /proc 实测）

```
vmstat: cs=55579（上下文切换）/ in=28995（中断）
/proc/<pid>/status:
  voluntary_ctxt_switches: 76（主动：等待 I/O/锁）
  nonvoluntary_ctxt_switches: 9（被动：被抢占）
```

### 上下文切换机制

```
主动切换（voluntary）：
  进程主动让出 CPU（等待 I/O/锁/sleep）
  → schedule() → 保存寄存器 → 切换页表 → 恢复目标进程

被动切换（involuntary）：
  进程被抢占（时间片用完/更高优先级就绪）
  → 时钟中断 → schedule() → 切换

开销：
  保存/恢复寄存器（~100 周期）
  切换页表（CR3 寄存器，可能刷 TLB）
  缓存失效（冷启动）
  ~1-10μs（高频切换是性能瓶颈）
```

### P9 追问链

- **Q：主动 vs 被动切换？** → 主动=等 I/O/锁；被动=被抢占（时间片用完）
- **Q：上下文切换开销？** → ~1-10μs（保存寄存器+切页表+缓存失效）
- **Q：怎么看切换次数？** → vmstat 的 cs 列 / /proc/<pid>/status voluntary/nonvoluntary

---

## 实验 3：CFS 调度（nice/sched 实测）

```
nice 值: 0（默认优先级）
/proc/<pid>/sched: se.exec_start（调度实体信息）
```

### CFS（Completely Fair Scheduler）

```
CFS 目标：所有进程"公平"分配 CPU
  按 vruntime（虚拟运行时间）排序（红黑树）
  → 总是选 vruntime 最小的进程运行
  → nice 值影响权重（nice -20 权重高，nice 19 权重低）

vruntime 计算：
  vruntime += 实际运行时间 × (nice_0权重 / 进程权重)
  → nice 值高（权重低）→ vruntime 增长快 → 排后面
  → nice 值低（权重高）→ vruntime 增长慢 → 排前面

调度类（优先级）：
  Realtime（SCHED_FIFO/RR）：实时调度（最高）
  CFS（SCHED_NORMAL/BATCH/IDLE）：普通调度
  Deadline（SCHED_DEADLINE）：截止时间调度
```

### taskset/CPU 亲和性

```
taskset：设置进程 CPU 亲和性（绑定到指定 CPU）
  taskset -cp 0,1 <pid>   ← 绑定到 CPU 0,1
  → 减少跨 CPU 迁移（缓存亲和）
  → NUMA 场景：绑定到本节点 CPU（避免跨节点访问）
```

### P9 追问链

- **Q：CFS 怎么调度？** → 按 vruntime 排序（红黑树），总是选最小的
- **Q：nice 值影响什么？** → 权重（影响 vruntime 增长速度），-20 高优先级
- **Q：CPU 亲和性有什么用？** → 减少跨 CPU 迁移（缓存亲和），taskset 设置

---

## 实验 4：信号（信号机制）

```
常见信号：
  SIGTERM(15)：终止（可捕获，默认 kill）
  SIGKILL(9)：终止（不可捕获，强制杀）
  SIGINT(2)：中断（Ctrl+C）
  SIGSEGV(11)：段错误（非法内存访问）
  SIGCHLD(17)：子进程退出（通知父进程）
  SIGSTOP/SIGCONT：停止/继续

发送：kill -<signal> <pid>
  kill -9 <pid>   ← SIGKILL（强制杀，不可捕获）
  kill -15 <pid>  ← SIGTERM（优雅终止，可捕获）
```

### P9 追问链

- **Q：SIGTERM vs SIGKILL？** → SIGTERM 可捕获（优雅退出）；SIGKILL 不可捕获（强制杀）
- **Q：SIGSEGV 什么时候发？** → 非法内存访问（段错误，JVM/native crash）
- **Q：僵尸进程怎么清理？** → 父进程 wait()；或 kill 父进程（孤儿进程被 init 收养）

---

## 补充：fork/exec/线程模型

```
fork()：复制进程（COW，共享物理页）
  → 返回两次（父=子 PID；子=0）
  → 子进程复制父的地址空间（COW 不复制数据）

exec()：加载新程序（替换地址空间）
  → fork + exec 是创建新进程的标准方式

线程（pthread_create）：
  → clone() 创建（共享 mm/files，独立 task_struct）
  → 线程切换比进程切换快（不切页表）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **进程状态** | R/S/D/T/Z/X（D=不可中断，Z=僵尸） | 实验 1（/proc status 实测） |
| **task_struct** | 进程控制块（pid/state/mm/files/sched） | 实验 1（叙事） |
| **上下文切换** | 主动（等 I/O）/被动（被抢占）；开销 ~1-10μs | 实验 2（vmstat cs=55579 实测） |
| **CFS 调度** | 按 vruntime 排序（红黑树），nice 影响权重 | 实验 3（nice=0 实测） |
| **CPU 亲和性** | taskset 绑定 CPU（减少跨 CPU 迁移） | 实验 3（叙事） |
| **信号** | SIGTERM 可捕获/SIGKILL 不可；SIGSEGV 段错误 | 实验 4（叙事） |
| 进程 vs 线程 | 进程独立地址空间；线程共享（task_struct 不同，mm 共享） | 补充（叙事） |
| fork/exec | fork=COW 复制；exec=替换地址空间 | 补充（叙事） |

---

## 补充 #1：实时调度（SCHED_FIFO/SCHED_RR vs CFS）

```
Linux 调度类（优先级从高到低）：
  Deadline（SCHED_DEADLINE）：截止时间调度（最高）
  Realtime（SCHED_FIFO/SCHED_RR）：实时调度
  CFS（SCHED_NORMAL/BATCH/IDLE）：普通调度（默认）

SCHED_FIFO：先进先出（同优先级先来先服务，不被同优先级抢占）
SCHED_RR：轮转（同优先级时间片轮转）
  → 实时优先级 1-99（数字越大越高）
  → 抢占所有 CFS 进程

使用场景：
  音频/视频处理（延迟敏感）
  工业控制（硬实时）
  内核线程（ksoftirqd 等）

设置：
  chrt -f 50 <pid>   ← SCHED_FIFO 优先级 50
  chrt -r 50 <pid>   ← SCHED_RR 优先级 50
```

### P9 追问链

- **Q：实时调度和 CFS 区别？** → 实时抢占所有 CFS；SCHED_FIFO 不被同优先级抢占；SCHED_RR 轮转
- **Q：什么时候用实时调度？** → 延迟敏感（音频/工业控制）；一般应用不用（可能饿死 CFS）
- **Q：chrt 干什么？** → 设置进程调度策略（SCHED_FIFO/RR/NORMAL）

---

## 补充 #2：进程组/会话（信号传播）

```
进程组（Process Group）：
  一组进程（共享 PGID），可一起收信号
  kill -<signal> -<pgid>  ← 杀整个进程组

会话（Session）：
  一组进程组（共享 SID），终端相关
  控制终端：前台进程组接收 SIGINT（Ctrl+C）

应用：
  Ctrl+C → SIGINT 发给前台进程组（整个组）
  kill -9 -<pgid> → 杀整个进程组（如 shell 启动的子进程）
  setsid：创建新会话（脱离终端，daemon 进程）
```

### P9 追问链

- **Q：Ctrl+C 杀了什么？** → SIGINT 发给前台进程组（整个组，不只是当前进程）
- **Q：kill 杀进程组？** → `kill -9 -<pgid>`（负号=进程组）
- **Q：daemon 进程怎么创建？** → setsid（创建新会话，脱离终端）

---

## 补充考点速查（#1/#2）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **实时调度** | SCHED_FIFO/RR 抢占 CFS；chrt 设置；延迟敏感场景用 | #1（叙事） |
| **进程组/会话** | 进程组共享 PGID（kill -pgid）；会话共享 SID（daemon 用 setsid） | #2（叙事） |

## 相关文件

- /proc/<pid>/status 实测：进程状态/线程数/上下文切换
- vmstat 实测：cs（上下文切换）
- nice/sched 实测：CFS 调度信息
