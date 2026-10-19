# p9-os-domain7 · 中断与 I/O（P9 深度实验）

> OS 轨道域 7/9。覆盖：top half/bottom half/中断上下文
> 衔接：网络域5 NAPI/softirq（收发包路径）

---

## 实验 1：中断统计（/proc/interrupts 实测）

```
            CPU0-CPU15
19:  0 0 0 0 ... 1074932 ...  IO-APIC 19-fasteoi ens33
     ↑ ens33 网卡中断 107 万次，绑定在 CPU14
```

### 硬中断（Top Half）

```
硬中断：硬件触发（网卡/磁盘/定时器），最高优先级
  → 保存现场 → 执行中断处理 → 恢复现场
  → 要求：快（不能睡眠/不能做耗时操作）
  → 高频中断：中断风暴（如网卡收包）

中断处理：
  ① 硬件触发 → CPU 响应（关中断）
  ② 跳转中断向量表（IDT）→ 中断处理程序
  ③ 保存寄存器 → 执行 handler → 恢复寄存器
  ④ 开中断 → 返回被打断的代码
```

### P9 追问链

- **Q：硬中断要求？** → 快（不能睡眠/耗时操作）；高频会中断风暴
- **Q：中断处理流程？** → 硬件触发→IDT→handler→恢复→返回
- **Q：怎么看中断次数？** → /proc/interrupts（每 CPU 每中断号）

---

## 实验 2：软中断（Bottom Half，/proc/softirqs 实测）

```
软中断类型（/proc/softirqs）：
  TIMER:  301031（定时器）
  NET_TX: 1（网络发送）
  NET_RX: 1002744（网络接收）← ens33 收包软中断
  HRTIMER: 0（高精度定时器）
```

### Top Half vs Bottom Half

```
问题：硬中断要快，但有些操作耗时（如收包处理）
解法：拆分处理
  Top Half（硬中断）：快速响应（确认中断/调度下半部）
  Bottom Half（软中断）：耗时处理（协议栈/收包）

实现：
  softirq：可延迟的软中断（NET_RX/NET_TX/TIMER）
  tasklet：基于 softirq 的轻量下半部
  workqueue：进程上下文（可睡眠）

网络收包：
  硬中断：确认收到包 → 调度 NET_RX_SOFTIRQ
  软中断：NET_RX → NAPI poll → 协议栈处理
```

### P9 追问链

- **Q：top half/bottom half 区别？** → top=硬中断（快，不能睡）；bottom=软中断（耗时处理）
- **Q：为什么拆分？** → 硬中断要快（不能阻塞），耗时工作移到软中断
- **Q：softirq vs tasklet？** → softirq=多实例并行；tasklet=串行（同一 tasklet 不并行）

---

## 补充：中断上下文 vs 进程上下文

```
中断上下文：
  硬中断/软中断处理中（不在进程调度中）
  → 不能睡眠（没有进程调度）
  → 不能调用可能睡眠的函数（如 kmalloc GFP_KERNEL）
  → 不能访问用户态内存（地址空间不属于任何进程）

进程上下文：
  进程执行系统调用/普通代码
  → 可以睡眠（schedule()）
  → 可以访问用户态内存

内核代码区分上下文：
  in_interrupt()：是否在中断上下文
  in_irq()：是否在硬中断
  in_softirq()：是否在软中断
```

### P9 追问链

- **Q：中断上下文能睡眠吗？** → 不能（没有进程调度，睡眠=死锁）
- **Q：中断上下文能访问用户内存吗？** → 不能（地址空间不属于任何进程）
- **Q：内核怎么判断上下文？** → in_interrupt()/in_irq()/in_softirq()

---

## 衔接：网络域5（NAPI/softirq）

```
OS 域7（中断）↔ 网络域5（收发包）：
  收包：网卡硬中断 → NAPI 关中断 → 软中断 NET_RX → 协议栈
  衔接点：硬中断（top half）确认收包；软中断（bottom half）处理协议栈

/proc/interrupts：网卡中断次数（ens33 107 万次）
/proc/softirqs：NET_RX 软中断次数（100 万次）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **硬中断（top half）** | 硬件触发/最高优先级/快（不能睡）；/proc/interrupts | 实验 1（ens33 中断实测） |
| **软中断（bottom half）** | 耗时处理（NET_RX/TIMER）；/proc/softirqs | 实验 2（NET_RX=100万实测） |
| **top/bottom 拆分** | top=快确认；bottom=耗时处理（协议栈/收包） | 补充（叙事） |
| **中断上下文** | 不能睡眠/不能访问用户内存；in_interrupt() 判断 | 补充（叙事） |
| softirq vs tasklet | softirq 多实例并行；tasklet 串行 | 补充（叙事） |
| 衔接网络域5 | 收包=硬中断(top)+软中断(bottom,NET_RX+NAPI) | 补接 |

---

## 补充 #1：workqueue（内核延迟工作三件套补全）

```
内核延迟工作三种机制：

① softirq：
   最轻量（中断上下文）
   可并行（多实例）；不能睡眠
   用于：NET_RX/NET_TX/TIMER（高频）

② tasklet：
   基于 softirq（轻量）
   串行（同一 tasklet 不并行）；不能睡眠
   用于：网卡驱动/简单延迟工作

③ workqueue：
   进程上下文（可睡眠）
   有专用内核线程（kworker）执行
   用于：需要睡眠的延迟工作（磁盘 I/O/复杂处理）

对比：
  softirq/tasklet：中断上下文（不能睡，快）
  workqueue：进程上下文（能睡，灵活）

内核线程：
  ksoftirqd：处理 softirq（积压时）
  kworker：执行 workqueue 任务
  kthreadd：内核线程父进程（PID=2）
```

### P9 追问链

- **Q：内核延迟工作有哪几种？** → softirq/tasklet（中断上下文，不能睡）/workqueue（进程上下文，能睡）
- **Q：softirq 和 workqueue 区别？** → softirq 不能睡（中断上下文）；workqueue 能睡（kworker 线程）
- **Q：kworker 是什么？** → workqueue 的执行线程（进程上下文，可睡眠）

---

## 补充考点速查（#1）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **workqueue** | 进程上下文延迟工作（kworker 线程，可睡眠）；补全 softirq/tasklet 三件套 | #1（叙事） |
| 内核延迟工作三件套 | softirq/tasklet（中断上下文）/workqueue（进程上下文） | #1（对比） |

## 相关文件

- /proc/interrupts 实测：ens33 中断 1074932 次（CPU14）
- /proc/softirqs 实测：NET_RX=1002744/TIMER=301031
- 衔接：网络域5 NAPI/softirq
