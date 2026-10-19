# p9-os-domain1 · 内核与用户态（P9 深度实验）

> OS 轨道域 1/9（总纲）。覆盖：系统调用（开销/vDSO）/ /proc / /sys / dmesg/journalctl
> 核心命题：**用户态怎么和内核对话？/proc 是什么？**

---

## 实验 1：系统调用（strace 实测）

### strace 统计（ls /tmp）

```
% time     seconds  usecs/call     calls    errors syscall
 41.53    0.000103          14         7           openat
 27.82    0.000069           7         9           close
 27.42    0.000068          13         5           read
  3.23    0.000008           8         1           write
100.00    0.000248          11        22           total
```

### 系统调用机制

```
用户态程序 → 系统调用（syscall 指令）→ 内核态执行 → 返回用户态
  → 每次切换：保存/恢复寄存器 + 权限检查 + 上下文切换
  → 开销：~100-200 CPU 周期（~100ns-1μs）
  → 高频系统调用是性能瓶颈（如网络 I/O）

常见系统调用：
  进程：fork/exec/exit/wait
  文件：open/read/write/close/stat
  内存：mmap/munmap/brk
  网络：socket/connect/send/recv/epoll_wait
  其他：ioctl/fcntl/signal
```

### vDSO（免系统调用优化）

```
问题：gettimeofday/time 等高频调用开销大
解法：vDSO（Virtual Dynamic Shared Object）
  → 内核映射一段代码到用户态（[vdso]）
  → 用户态直接调用（无系统调用切换）
  → 实测：/proc/self/maps 有 [vdso] 映射

vDSO 函数：gettimeofday/time/getcpu/clock_gettime
```

### P9 追问链

- **Q：系统调用开销多大？** → ~100-200 CPU 周期（~100ns-1μs）；高频是瓶颈
- **Q：vDSO 优化什么？** → gettimeofday/time 免系统调用（内核映射代码到用户态）
- **Q：怎么统计系统调用？** → strace -c（按调用次数/耗时统计）

---

## 实验 2：/proc 文件系统（内核数据结构暴露为文件）

### 实测

```
/proc/meminfo（内核内存统计）：
  MemTotal: 16323980 kB     ← 总内存 16GB
  MemFree: 854980 kB        ← 空闲
  MemAvailable: 8616168 kB  ← 可用（含缓存回收）
  Buffers/Cached: 644208/6583748 kB  ← 缓存

/proc/<pid>/status（进程状态）：
  Name: bash / State: S (sleeping)
  Pid: 65168 / PPid: 6215
  VmSize: 13048 kB / VmRSS: 3800 kB
  Threads: 1

/proc/cpuinfo：
  processor: 0 / model name: AMD Ryzen 9 3900X 12-Core
```

### /proc 是什么

```
/proc = procfs = 虚拟文件系统（不是磁盘文件）
  → 内核把数据结构暴露为文件（读文件=读内核数据）
  → 每个进程一个目录（/proc/<pid>/）
  → 常用：meminfo/status/stat/fd/maps/net/tcp/interrupts

/proc/<pid>/ 关键文件：
  status：进程状态（内存/线程/状态）
  stat：进程统计（调度/CPU 时间）
  fd/：文件描述符（符号链接）
  maps：内存映射（虚拟地址空间）
  net/tcp：TCP 连接表（ss 的数据源）
  limits：资源限制（ulimit）
```

### P9 追问链

- **Q：/proc 是什么？** → 虚拟文件系统，内核数据结构暴露为文件（读文件=读内核）
- **Q：top/vmstat/ss 读什么？** → 都读 /proc（top 读 /proc/<pid>/stat，ss 读 /proc/net/tcp）
- **Q：/proc/<pid>/fd 是什么？** → 文件描述符目录（fd 0/1/2=标准输入输出，符号链接指向打开的文件）

---

## 实验 3：/sys 文件系统（设备/驱动信息）

### 实测

```
/sys/class/net/：网络接口（ens33/docker0/veth）
  ens33/mtu = 1500
  ens33/operstate = up

/sys/devices/system/cpu/：CPU 信息（cpu0-cpu12）
/sys/devices/system/node/：NUMA 节点（has_cpu/has_memory）
```

### /sys vs /proc

| | /proc | /sys |
|---|---|---|
| 全称 | procfs | sysfs |
| 内容 | 内核数据结构（进程/内存/网络） | 设备/驱动/硬件信息 |
| 用途 | 诊断（top/ss/vmstat） | 硬件管理（网卡/CPU/块设备） |
| 示例 | /proc/meminfo /proc/<pid>/status | /sys/class/net /sys/devices |

### P9 追问链

- **Q：/sys 是什么？** → sysfs 虚拟文件系统，暴露设备/驱动/硬件信息
- **Q：/proc 和 /sys 区别？** → /proc=内核数据（诊断）；/sys=设备信息（硬件管理）
- **Q：改网卡 MTU？** → `echo 1400 > /sys/class/net/ens33/mtu`（写 /sys）

---

## 实验 4：dmesg/journalctl（内核日志）

### 机制

```
dmesg：内核环形缓冲区日志（内核启动/驱动/OOM/错误）
journalctl：systemd 日志（系统服务/内核/应用）
  → journalctl -k：内核日志（同 dmesg）
  → journalctl -u <service>：指定服务日志

排障：
  dmesg | grep -i oom      ← OOM Killer 记录
  dmesg | grep -i eth      ← 网卡驱动日志
  dmesg | tail -50         ← 最近内核事件
```

### P9 追问链

- **Q：dmesg 读什么？** → 内核环形缓冲区（启动/驱动/OOM/错误）
- **Q：OOM Killer 记录在哪？** → dmesg（`Out of memory: Killed process`）
- **Q：journalctl vs dmesg？** → journalctl 更全（含服务日志）；dmesg 只有内核

---

## 补充：Linux 启动流程（P9 偶尔问）

```
① BIOS/UEFI：硬件自检（POST）→ 找启动设备
② GRUB：Boot Loader → 加载内核镜像（vmlinuz）+ initrd（初始内存盘）
③ 内核启动：
   解压内核 → 初始化硬件/驱动 → 挂载 initrd 中的文件系统
   → 启动第一个进程（init/systemd，PID=1）
④ systemd：
   读 /etc/systemd/system/ → 按依赖顺序启动服务
   → 挂载文件系统/启动网络/启动应用
⑤ 用户登录/应用启动

关键文件：
  /boot/vmlinuz-*：内核镜像
  /boot/initrd.img-*：初始内存盘（启动时临时根文件系统）
  /etc/systemd/system/：systemd 服务单元
  dmesg：内核启动日志（从 BIOS 到 systemd）
```

### P9 追问链

- **Q：Linux 启动流程？** → BIOS→GRUB→内核→init/systemd（PID=1）→服务
- **Q：initrd 干什么？** → 启动时临时根文件系统（加载必要驱动后切换到真实根）
- **Q：systemd 和 init 区别？** → systemd 并行启动（依赖图）；SysV init 串行（脚本顺序）

---

## 补充：内核参数调优（sysctl 系统讲）

```bash
# 内存
vm.swappiness = 60              ← swap 倾向（0=不用 swap）
vm.overcommit_memory = 0        ← 内存超分配策略
vm.dirty_ratio = 20             ← 脏页比例阈值（写回触发）

# 进程
kernel.pid_max = 32768          ← 最大进程号
kernel.threads-max = ???        ← 最大线程数

# 文件系统
fs.file-max = 9223372036854775807 ← 系统 fd 上限
fs.nr_open = 1048576             ← 单进程 fd 上限

# 网络（域7 已覆盖）
net.core.somaxconn / net.ipv4.tcp_*
```

**P9 答法**："内核参数调优通过 sysctl：vm.swappiness（swap 倾向）/vm.overcommit（内存超分配）/fs.nr_open（fd 上限）。网络参数域7 已覆盖。"

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **系统调用开销** | ~100-200 周期（~100ns-1μs）；strace -c 统计 | 实验 1（strace 实测 22 次） |
| **vDSO** | 内核映射代码到用户态（gettimeofday 免系统调用） | 实验 5（/proc/self/maps [vdso]） |
| **/proc** | 虚拟文件系统，内核数据结构暴露为文件（读文件=读内核） | 实验 2（meminfo/status 实测） |
| **/sys** | sysfs，设备/驱动/硬件信息 | 实验 3（net/cpu/node 实测） |
| /proc vs /sys | /proc=内核数据（诊断）；/sys=设备信息（硬件） | 实验 2/3（对比） |
| **dmesg/journalctl** | dmesg=内核环形缓冲；journalctl=systemd 全量日志 | 实验 4（叙事） |
| top/ss/vmstat | 都读 /proc（top 读 stat，ss 读 /proc/net/tcp） | 补充（叙事） |
| **Linux 启动流程** | BIOS→GRUB→内核→init/systemd（PID=1）→服务 | 补充（叙事） |
| **sysctl 调优** | vm.swappiness/vm.overcommit/fs.nr_open | 补充（叙事） |

## 相关文件

- strace 实测：`strace -c ls /tmp`（22 次系统调用）
- /proc 实测：meminfo/cpuinfo/status
- /sys 实测：net/cpu/node
- vDSO：`/proc/self/maps` [vdso] 映射
