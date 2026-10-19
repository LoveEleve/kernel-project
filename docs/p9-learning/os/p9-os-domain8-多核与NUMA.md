# p9-os-domain8 · 多核与 NUMA（P9 深度实验）

> OS 轨道域 8/9。覆盖：缓存一致性 MESI/内存屏障/per-CPU/NUMA
> 衔接：OS 域4 内存屏障、JVM 域5 volatile、网络域5 RPS/CPU 亲和

---

## 实验 1：NUMA 信息（numactl 实测）

```
available: 1 nodes (0)        ← 单 NUMA 节点
node 0 cpus: 0-15             ← 16 核在 node0
node 0 size: 15941 MB         ← 内存 16GB
node distances: 0: 10         ← 节点距离（单节点=10）
```

### NUMA 架构

```
UMA（Uniform Memory Access）：所有 CPU 访问内存等速
NUMA（Non-Uniform Memory Access）：跨节点访问慢
  → 每个 CPU 有本地内存（快）+ 远程内存（慢）
  → 跨节点延迟：~1.5-2x（本地 vs 远程）

本机：单节点（UMA），numactl 显示 1 node
多路服务器：2-8 节点（NUMA）
```

### P9 追问链

- **Q：NUMA 和 UMA 区别？** → NUMA 跨节点访问慢（1.5-2x）；UMA 等速
- **Q：NUMA 怎么优化？** → 内存分配在本地节点（numactl --membind）；CPU 绑定本节点
- **Q：怎么看 NUMA？** → numactl --hardware（节点数/内存/距离）

---

## 实验 2：CPU 拓扑（/sys 实测）

```
CPU 数: 16 / 在线: 0-15
缓存（cpu0）：
  L1 Data: 32K / L1 Instruction: 32K
  L2 Unified: 512K
  L3 Unified: 16384K（16MB，共享）
```

### 缓存层次

```
L1（32K×2）：每核独有（数据+指令分开，最快 ~1ns）
L2（512K）：每核独有（~3-10ns）
L3（16MB）：多核共享（~10-30ns）
主存（GB）：~100ns

缓存行（Cache Line）：64 字节（数据传输单位）
  → 伪共享（False Sharing）：不同变量在同一缓存行（频繁失效）
```

### P9 追问链

- **Q：缓存层次？** → L1(32K,~1ns)→L2(512K,~5ns)→L3(16MB共享,~20ns)→主存(~100ns)
- **Q：什么是伪共享？** → 不同线程变量在同一缓存行（频繁失效，性能差）
- **Q：怎么避免伪共享？** → 缓存行对齐（@Contended/填充到 64B）

---

## 补充：缓存一致性 MESI

```
多核缓存一致性协议（MESI）：
  M（Modified）：已修改（独占，脏）
  E（Exclusive）：独占（干净，未修改）
  S（Shared）：共享（多核有副本，干净）
  I（Invalid）：无效（已失效）

一致性维护：
  核 A 写 → 通知其他核失效（I）→ 核 A 变 M
  核 A 读 S → 变 E（其他核失效）/保持 S
  → 写时总线广播失效（开销大）

写传播：
  MESIF（加 F=Fetch）：S 状态可直接响应读（不用总线）
  MOESI：加 O=Owned（共享+脏，减少写传播）
```

### P9 追问链

- **Q：MESI 四种状态？** → Modified/Exclusive/Shared/Invalid
- **Q：多核写同一变量会怎样？** → 总线广播失效（缓存一致性开销）
- **Q：MESIF/MOESI 改进？** → MESIF 加 F（S 直接响应）；MOESI 加 O（减少写传播）

---

## 补充：内存屏障（域4 已讲，此处衔接多核）

```
多核内存屏障：
  编译器屏障：禁止编译器重排序（asm volatile("" ::: "memory")）
  CPU 屏障：禁止 CPU 重排序（mfence/lfence/sfence）
  完整屏障：StoreLoad（最常用）

x86 TSO：
  只保证 StoreLoad 有序（其他隐含有序）
  → volatile 写 = mfence（StoreLoad）
  → volatile 读 = 无需屏障（隐含）

ARM 弱内存序：
  需要显式屏障（dmb/dsb/isb）
  → volatile 开销比 x86 大
```

---

## 补充：per-CPU 变量

```
per-CPU 变量：每 CPU 一份的变量（避免共享竞争）
  → 内核大量使用（统计/计数器/中断计数）
  → /proc/interrupts 的每 CPU 计数就是 per-CPU

优势：
  无锁（每 CPU 独立）
  缓存亲和（变量在本地 CPU 缓存）

应用：
  /proc/interrupts（每 CPU 中断计数）
  /proc/softirqs（每 CPU 软中断计数）
  内核统计（网络包数/上下文切换）
```

### P9 追问链

- **Q：per-CPU 变量什么用？** → 避免共享竞争（每 CPU 独立，无锁+缓存亲和）
- **Q：/proc/interrupts 怎么实现？** → per-CPU 变量（每 CPU 中断计数）

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **NUMA** | 跨节点访问慢（1.5-2x）；numactl --membind 绑本地节点 | 实验 1（numactl 实测单节点） |
| **缓存层次** | L1(32K,~1ns)→L2(512K)→L3(16MB共享)→主存(~100ns) | 实验 2（/sys 缓存实测） |
| **缓存行/伪共享** | 64B 传输单位；不同变量同行=频繁失效 | 实验 2（叙事） |
| **MESI** | Modified/Exclusive/Shared/Invalid（写时总线广播失效） | 补充（叙事） |
| **内存屏障** | x86 只需 StoreLoad（TSO）；ARM 弱内存序需显式屏障 | 补充（衔接域4） |
| **per-CPU 变量** | 每 CPU 一份（无锁+缓存亲和）；/proc/interrupts 实现 | 补充（叙事） |
| NUMA 优化 | 内存绑本地节点+CPU 绑定本节点 | 实验 1（叙事） |

## 相关文件

- numactl 实测：单节点/内存/距离
- /sys 缓存实测：L1 32K/L2 512K/L3 16MB
- 衔接：OS 域4 内存屏障、网络域5 RPS/CPU 亲和
