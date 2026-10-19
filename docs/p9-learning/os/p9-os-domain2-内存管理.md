# p9-os-domain2 · 内存管理（P9 深度实验）

> OS 轨道域 2/9（最重）。覆盖：虚拟内存/分页/页表/TLB/缺页/COW/DemandPaging/
> 伙伴系统/slab/mmap/OOM Killer/大页/Swap/kswapd

---

## 实验 1：虚拟内存/分页（/proc/meminfo 实测）

```
MemTotal:       16323980 kB     ← 物理内存 16GB
MemFree:          743364 kB     ← 空闲
MemAvailable:    8141664 kB     ← 可用（含可回收缓存）
Buffers:          624728 kB     ← 块设备缓存
Cached:          6232744 kB     ← 页缓存（文件缓存）
Active/Inactive:  8633760/5163100 kB ← 活跃/非活跃页
Active(anon):    6773352 kB     ← 匿名页（堆/栈）
```

### 虚拟内存机制

```
虚拟地址 → MMU → 物理地址（页表映射）
  每个进程独立虚拟地址空间（互不干扰）
  分页：4KB 页（默认）/ 2MB 大页 / 1GB 大页
  页表：多级（4 级页表 x86_64：PGD→P4D→PUD→PMD→PTE）
  TLB：页表缓存（加速地址翻译）
```

### /proc/meminfo 关键字段

| 字段 | 含义 |
|---|---|
| MemTotal | 物理内存总量 |
| MemAvailable | 可用内存（含可回收缓存）★ 排障关键 |
| Cached | 页缓存（文件缓存，可回收） |
| Buffers | 块设备缓存 |
| Active(anon) | 匿名页（堆/栈，不可直接回收） |
| SwapCached | Swap 中的缓存页 |

### P9 追问链

- **Q：MemFree vs MemAvailable？** → Free=完全空闲；Available=Free+可回收缓存（应用真正可用）
- **Q：虚拟内存怎么映射？** → 虚拟地址→MMU→页表→物理地址；TLB 缓存加速
- **Q：页表几级？** → x86_64 四级（PGD→P4D→PUD→PMD→PTE）

---

## 实验 2：缺页中断（minor/major fault）

### 实测（/proc/<pid>/stat 字段 10-11）

```
初始：major_fault=0 minor_fault=0
读 1MB 后：major_fault=0 minor_fault=0（数据已在页缓存）
```

### 缺页类型

```
minor fault（次缺页）：
  页在内存中（如共享库/页缓存），只需更新页表
  → 开销小（无磁盘 I/O）

major fault（主缺页）：
  页不在内存（需从磁盘/swap 读）
  → 开销大（磁盘 I/O）
  → 频繁 major fault = 内存不足/磁盘慢

排障：
  /proc/<pid>/stat 字段 10=major, 11=minor
  sar -B：系统级缺页统计（pgpgin/pgpgout/fault/majflt）
```

### P9 追问链

- **Q：minor vs major fault？** → minor=页在内存（更新页表）；major=需读磁盘（慢）
- **Q：频繁 major fault 说明？** → 内存不足（页被换出）或磁盘慢
- **Q：怎么看缺页？** → /proc/<pid>/stat 或 sar -B

---

## 实验 3：COW（Copy-On-Write，写时复制）

### 机制

```
fork() 时：
  父子进程共享同一物理页（页表标记只读）
  → 不复制数据（省内存/快）
  → 任一方写入 → 触发缺页 → 复制页 → 修改副本（COW）

应用：
  fork 子进程（如 JVM exec、shell fork）
  vfork 优化（父子共享地址空间直到 exec）
  大页 COW（THP 透明大页）
```

### P9 追问链

- **Q：fork 为什么快？** → COW（不复制数据，共享物理页）
- **Q：COW 什么时候触发复制？** → 写入时（缺页中断→复制页→修改副本）
- **Q：COW 有什么问题？** → 大量写入时复制开销大（内存翻倍风险）

---

## 实验 4：Demand Paging（按需分页）

### 机制

```
mmap 文件/加载可执行文件时：
  不立即读入全部数据（只建页表映射）
  → 访问某页时才读入（缺页中断）
  → 按需分页（Demand Paging）

应用：
  mmap 大文件（不全量读入）
  可执行文件加载（.text 段按需加载）
  共享库（.so 按需加载）
```

### P9 追问链

- **Q：mmap 大文件会全读入内存吗？** → 不会（Demand Paging，访问才读）
- **Q：Demand Paging 好处？** → 启动快（不全量加载）+ 内存省（只加载用到的页）

---

## 实验 5：伙伴系统（/proc/buddyinfo 实测）

```
Node 0, zone DMA      0  0  0  0  0  0  0  0  1  2  2
Node 0, zone DMA32  13525 3817 6667 2093 724 308 144 56 32 9 38
Node 0, zone Normal 4350 2760 26 12 282 187 67 19 4 0 0
```

### 伙伴系统机制

```
内核物理页分配器（Buddy System）：
  以 2 的幂管理空闲页（4KB/8KB/16KB/.../4MB）
  分配：找最小满足的 order（合并/拆分伙伴块）
  释放：与"伙伴"合并（相邻同大小块）

zone 分区：
  DMA：16MB 以下（老设备）
  DMA32：4GB 以下（32 位设备）
  Normal：4GB 以上（常规）

buddyinfo 列：order 0-10（4KB 到 4MB）的空闲块数
```

### P9 追问链

- **Q：伙伴系统怎么分配？** → 2 的幂管理，找最小满足 order，拆分/合并伙伴块
- **Q：zone 是什么？** → 物理内存分区（DMA/DMA32/Normal，按地址范围）
- **Q：buddyinfo 怎么看？** → 每列=order（4KB 到 4MB）的空闲块数

---

## 实验 6：slab（/proc/slabinfo 实测）

```
name            <active_objs> <num_objs> <objsize> <objperslab> <pagesperslab>
isofs_inode_cache     56     72    664   24    4
nf_conntrack         504    512    256   32    2
ovl_inode           1036   1081    696   47    8
```

### slab 分配器

```
内核对象分配器（Slab/SLUB）：
  伙伴系统分配页 → slab 切成固定大小对象
  → 快速分配/释放（无碎片）
  → 缓存复用（常用对象预分配）

slabinfo 列：
  objsize：对象大小
  objperslab：每个 slab 的对象数
  pagesperslab：每个 slab 的页数
  active_objs：活跃对象数
```

### P9 追问链

- **Q：slab 和伙伴系统关系？** → 伙伴系统分配页；slab 把页切成对象（上层分配器）
- **Q：slab 有什么用？** → 快速分配内核对象（无碎片、缓存复用）
- **Q：怎么看内核内存？** → /proc/slabinfo（对象数×大小）

---

## 实验 7：mmap（内存映射）

### 机制

```
mmap：把文件/设备映射到虚拟地址空间
  → 访问内存=访问文件（无需 read/write）
  → Demand Paging（访问才读入）
  → 共享映射（多进程共享同一物理页）

应用：
  数据库（mmap 数据文件，如 MySQL InnoDB）
  大文件处理（不全量读入）
  进程间通信（共享内存 mmap）
  JVM（某些堆实现）
```

### P9 追问链

- **Q：mmap 和 read 区别？** → mmap=内存映射（访问内存=访问文件）；read=拷贝到用户缓冲
- **Q：mmap 有什么优势？** → Demand Paging + 共享映射 + 省缓冲区拷贝

---

## 实验 8：OOM Killer

### 机制

```
内存不足时：
  ① kswapd 后台回收（低速）
  ② direct reclaim（直接回收，分配路径上）
  ③ 还不够 → OOM Killer 选一个进程杀掉（按 oom_score）

oom_score：
  /proc/<pid>/oom_score（0-1000，越大越容易被杀）
  基于内存使用+运行时间（rss+年龄）
  可调：/proc/<pid>/oom_score_adj（-1000 到 1000）

排障：
  dmesg | grep -i oom（OOM Killer 记录）
  journalctl -k | grep -i oom
```

### P9 追问链

- **Q：OOM Killer 怎么选进程？** → 按 oom_score（内存使用+运行时间）
- **Q：怎么保护关键进程？** → echo -1000 > /proc/<pid>/oom_score_adj（永不杀）
- **Q：OOM 记录在哪？** → dmesg/journalctl -k（`Out of memory: Killed process`）

---

## 实验 9：大页（Huge Pages，实测）

```
/sys/kernel/mm/hugepages/
  hugepages-2048kB: nr_hugepages=0 free_hugepages=0     ← 2MB 大页
  hugepages-1048576kB: nr_hugepages=0 free_hugepages=0  ← 1GB 大页
```

### 大页机制

```
普通页 4KB → TLB 条目多（TLB miss 多）
大页 2MB/1GB → TLB 条目少（TLB miss 少）

用途：数据库/Java（-XX:+UseLargePages）/HPC
配置：
  echo 100 > /sys/kernel/mm/hugepages/hugepages-2048kB/nr_hugepages
  Java: -XX:+UseLargePages -XX:LargePageSizeInBytes=2m
```

### P9 追问链

- **Q：大页有什么用？** → 减少 TLB miss（2MB 页 = 512 个 4KB 页的 TLB 覆盖）
- **Q：大页什么时候用？** → 数据库/Java 大堆/HPC（内存密集型）

---

## 实验 10：Swap（实测）

```
/proc/swaps:
  /swap.img  file  4194300  0  -1   ← Swap 4GB，用 0
swappiness: 60   ← Swap 倾向（0=不用，100=积极用）
```

### Swap 机制

```
内存不足时：匿名页（堆/栈）换出到 swap 分区/文件
  → 回收物理内存（匿名页不能像页缓存那样丢弃）
  → 需要时换入（major fault）

swappiness：0=尽量不用 swap；100=积极用 swap
  → 生产建议：数据库等延迟敏感设 0-10
```

### P9 追问链

- **Q：Swap 和页缓存区别？** → 页缓存可直接丢弃（文件可重读）；匿名页需 swap 换出
- **Q：swappiness 怎么调？** → 延迟敏感 0-10；内存紧张可调高
- **Q：大量 swap in/out 说明？** → 内存不足（匿名页被换出）

---

## 实验 11：kswapd（内存回收，实测）

```
kswapd0（内核回收线程）：
  后台回收内存（当空闲页低于 watermark）
  → 回收页缓存/swap 换出匿名页
  → 避免 direct reclaim（分配路径上回收）

watermark：
  min/low/high 三档（/proc/zoneinfo）
  低于 low → 唤醒 kswapd
  低于 min → direct reclaim（阻塞分配）
```

### P9 追问链

- **Q：kswapd 干什么？** → 后台回收内存（低于 watermark 时唤醒）
- **Q：kswapd 和 direct reclaim 区别？** → kswapd=后台（不阻塞）；direct=分配路径（阻塞）
- **Q：怎么看 kswapd 活动？** → vmstat 的 si/so（swap in/out）/ /proc/vmstat 的 pgsteal

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **虚拟内存/分页** | 虚拟→MMU→页表→物理；x86_64 四级页表；TLB 缓存 | 实验 1（meminfo 实测） |
| **MemAvailable** | Free+可回收缓存（应用真正可用）★ 排障关键 | 实验 1 |
| **minor/major fault** | minor=页在内存；major=需读磁盘（频繁=内存不足） | 实验 2（/proc stat） |
| **COW** | fork 共享物理页，写入时复制（省内存） | 实验 3（叙事） |
| **Demand Paging** | mmap/加载不全读，访问才读（缺页） | 实验 4（叙事） |
| **伙伴系统** | 2 的幂管理物理页；zone 分区（DMA/DMA32/Normal） | 实验 5（buddyinfo 实测） |
| **slab** | 内核对象分配器（伙伴系统分配页→slab 切对象） | 实验 6（slabinfo 实测） |
| **mmap** | 文件映射到虚拟地址（Demand Paging+共享映射） | 实验 7（叙事） |
| **OOM Killer** | 内存不足杀进程（按 oom_score）；dmesg 记录 | 实验 8（叙事） |
| **大页** | 2MB/1GB 页（减少 TLB miss）；数据库/Java 用 | 实验 9（hugepages 实测） |
| **Swap** | 匿名页换出；swappiness 控制倾向 | 实验 10（/proc/swaps 实测） |
| **kswapd** | 后台回收线程（低于 watermark 唤醒） | 实验 11（ps 实测 kswapd0） |

---

## 补充 #1：四级页表结构（虚拟内存映射核心）

```
x86_64 四级页表（虚拟地址 48 位有效）：
  虚拟地址（48 位）→ PGD → P4D → PUD → PMD → PTE → 物理页帧 → 物理地址

各级含义：
  PGD（Page Global Directory）：全局页目录（进程级）
  P4D（Page 4th Directory）：4 级目录（5 级页表用）
  PUD（Page Upper Directory）：上级目录（1GB 大页）
  PMD（Page Middle Directory）：中间目录（2MB 大页）
  PTE（Page Table Entry）：页表项（4KB 页）

地址翻译：
  虚拟地址 → 取高位查 PGD → P4D → PUD → PMD → PTE → 物理页帧
  → 每级一次内存访问（4 级 = 4 次额外访问）
  → TLB 缓存翻译结果（避免 4 次访问）

大页优化：
  2MB 大页：PMD 直接指向 2MB 物理块（跳过 PTE 级）
  1GB 大页：PUD 直接指向 1GB 物理块（跳过 PMD/PTE 级）
```

### P9 追问链

- **Q：x86_64 页表几级？** → 四级（PGD→P4D→PUD→PMD→PTE）
- **Q：为什么需要多级页表？** → 节省内存（稀疏映射不用全分配）
- **Q：大页怎么优化？** → 2MB 跳过 PTE 级；1GB 跳过 PMD/PTE 级（少几级访问）

---

## 补充 #3：透明大页（THP，生产调优）

```
THP（Transparent Huge Pages）：自动使用 2MB 大页（无需显式配置）
  → 对应用透明（JVM/数据库不需要改代码）
  → 但可能带来问题：
     ① 内存碎片化（需要连续 2MB）
     ② 延迟毛刺（THP 分配/合并耗时）
     ③ 内存浪费（用不满 2MB 也分配）

配置：
  /sys/kernel/mm/transparent_hugepage/enabled
    always：总是用 THP（默认）
    madvise：只在 madvise(MADV_HUGEPAGE) 时用
    never：不用

生产建议：
  数据库/Java：often 设 madvise 或 never（避免延迟毛刺）
  内存密集型：可设 always（减少 TLB miss）
  排障：cat /sys/kernel/mm/transparent_hugepage/enabled
       /proc/meminfo 的 AnonHugePages 字段
```

### P9 追问链

- **Q：THP 有什么问题？** → 内存碎片化/延迟毛刺（THP 分配耗时）/内存浪费
- **Q：数据库建议 THP 怎么设？** → madvise 或 never（避免延迟毛刺）
- **Q：怎么看 THP 使用？** → /proc/meminfo AnonHugePages

---

## 补充 #4：页回收算法（LRU/Clock）

```
内存不足时回收哪些页？（页回收算法）

LRU（Least Recently Used）：
  按最近使用时间排序，回收最久未用的
  → 维护活跃/非活跃链表（Active/Inactive）
  → /proc/meminfo 的 Active/Inactive 字段

Clock（时钟算法）：
  LRU 近似（环形缓冲+访问位）
  → 扫描访问位=0 的页回收（第二机会）

Linux 实际：多链表 LRU（活跃/非活跃+文件/匿名分离）
  → Active(anon)：堆/栈（需 swap 换出）
  → Inactive(anon)：非活跃匿名页（优先回收）
  → Active(file)/Inactive(file)：页缓存（可直接丢弃）
```

### P9 追问链

- **Q：内存回收用什么算法？** → LRU 近似（多链表：Active/Inactive + 文件/匿名分离）
- **Q：先回收哪类页？** → Inactive(file) 页缓存（可直接丢弃）→ Inactive(anon)（swap 换出）
- **Q：Active/Inactive 在哪看？** → /proc/meminfo 的 Active/Inactive(anon/file)

---

## 补充考点速查（#1/#3/#4）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **四级页表** | PGD→P4D→PUD→PMD→PTE（4 次内存访问，TLB 缓存加速） | #1（结构） |
| **大页优化** | 2MB 跳 PTE 级；1GB 跳 PMD/PTE 级（少几级访问） | #1 |
| **THP** | 自动 2MB 大页；碎片化/延迟毛刺问题；数据库设 madvise/never | #3（叙事） |
| **页回收算法** | LRU 近似（Active/Inactive + 文件/匿名分离）；先回收页缓存 | #4（叙事+meminfo） |

## 相关文件

- /proc/meminfo/buddyinfo/slabinfo/swaps/hugepages 实测
- /proc/<pid>/stat 缺页字段
- ps 实测 kswapd0
