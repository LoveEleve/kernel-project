# p9-os-domain9 · 虚拟化与容器（P9 深度实验）

> OS 轨道域 9/9（收官）。覆盖：Namespace/cgroups(v1 vs v2)/KVM/容器原理
> 衔接：网络域10 容器网络、OS 域2 内存（OOM/大页）

---

## 实验 1：Namespace（实测）

### /proc/self/ns/（本进程）

```
cgroup → cgroup:[4026531835]    ← cgroup namespace
ipc    → ipc:[4026531839]       ← System V IPC
mnt    → mnt:[4026531832]       ← 文件系统挂载
net    → net:[4026531833]       ← 网络栈
pid    → pid:[4026531836]       ← 进程 ID
time   → time:[4026531834]      ← 时间
user   → user:[4026531837]      ← 用户/组 ID
uts    → uts:[4026531838]       ← 主机名
```

### 容器 Namespace 对比（Docker 容器）

```
容器 55e6fe28cb0e:
  cgroup:[4026532840]  ← 不同（独立 cgroup）
  ipc:[4026532838]     ← 不同（独立 IPC）
  mnt:[4026532836]     ← 不同（独立文件系统）
  net:[4026531833]     ← 相同！（共享宿主网络，--network host）
  pid:[4026532839]     ← 不同（独立进程 ID）
```

### Namespace 类型

| 类型 | 隔离内容 | 容器应用 |
|---|---|---|
| **pid** | 进程 ID | 容器内 PID 从 1 开始 |
| **net** | 网络栈（IP/端口/路由） | 容器独立网络（veth/bridge） |
| **mnt** | 文件系统挂载 | 容器独立根文件系统 |
| **uts** | 主机名 | 容器独立 hostname |
| **ipc** | System V IPC/共享内存 | 容器独立 IPC |
| **user** | 用户/组 ID | 容器 root 映射宿主非 root |
| **cgroup** | cgroup 视图 | 容器看到自己的 cgroup |
| **time** | 时间命名空间 | 时间隔离（少见） |

### P9 追问链

- **Q：Namespace 做什么？** → 隔离视图（pid/net/mnt/uts/ipc/user/cgroup）
- **Q：容器和虚拟机区别？** → 容器=Namespace+Cgroups（共享内核）；虚拟机=独立内核（硬件虚拟化）
- **Q：容器 root 和宿主 root 区别？** → user namespace 映射（容器 root=宿主非 root）

---

## 实验 2：cgroups v1 vs v2（实测）

```
cgroup 挂载：cgroup2 on /sys/fs/cgroup type cgroup2
cgroup v2 控制器：cpuset cpu io memory hugetlb pids rdma misc dmem
cgroup v1：无挂载（本机纯 v2）
```

### cgroups（控制组）

```
cgroups：限制/隔离进程组的资源
  CPU：cpu.cfs_quota_us/cpu.cfs_period_us（CPU 配额）
  内存：memory.limit_in_bytes（内存限制）→ 超了 OOM Kill
  IO：blkio（磁盘 I/O 限制）
  PID：pids.max（最大进程数）

cgroup v1 vs v2：
  v1：每个控制器独立挂载（/sys/fs/cgroup/memory/ 等）
  v2：统一层级（所有控制器一棵树）★ 推荐
  v2 改进：统一层级/更好父子继承/内存压力通知（PSI）
```

### 容器内存限制（衔接域2 OOM）

```
容器内存限制（K8s limit）：
  cgroups memory.limit_in_bytes（v1）/ memory.max（v2）
  → 超了 → OOM Kill（cgroup 层，不是 JVM OOM）
  → /sys/fs/cgroup/memory.current（v2 当前用量）

衔接域2：
  JVM OOM = 堆内（-Xmx）
  容器 OOM Kill = cgroup 内存超 limit（堆外+Metaspace+线程栈）
  → NMT 排查堆外（域9 JVM 轨道）
```

### P9 追问链

- **Q：cgroups v1 vs v2？** → v1 每控制器独立挂载；v2 统一层级（推荐）
- **Q：容器内存限制怎么实现？** → cgroups memory.limit（v1）/memory.max（v2）；超了 OOM Kill
- **Q：容器 OOM Kill 和 JVM OOM 区别？** → 容器=cgroup 超 limit（堆外）；JVM=堆内（-Xmx）

---

## 补充：KVM（内核虚拟机）

```
KVM（Kernel-based Virtual Machine）：Linux 内核虚拟化
  → 内核模块（kvm.ko）把 Linux 变成 Hypervisor
  → 虚拟机=独立内核（硬件虚拟化 VT-x/EPT）
  → QEMU：设备模拟（配合 KVM）

KVM vs 容器：
  KVM：独立内核/完全隔离/开销大（启动秒级）
  容器：共享内核/轻量/启动毫秒级

Type1 vs Type2：
  Type1（裸机）：VMware ESXi/Xen（直接跑在硬件上）
  Type2（宿主）：KVM/VirtualBox（跑在 Linux 上）
  KVM 是 Type2（但性能接近 Type1，因为内核模块）
```

### P9 追问链

- **Q：KVM 是什么？** → 内核虚拟化（kvm.ko 把 Linux 变 Hypervisor）；Type2 但性能接近 Type1
- **Q：KVM vs 容器？** → KVM 独立内核（完全隔离）；容器共享内核（轻量）

---

## 补充：容器原理（Namespace+Cgroups+UnionFS）

```
容器 = Namespace（隔离视图）+ cgroups（限制资源）+ UnionFS（分层文件系统）

Namespace：隔离（pid/net/mnt/uts/ipc/user/cgroup）
cgroups：限制（CPU/内存/IO/PID）
UnionFS（OverlayFS）：分层（lowerdir 只读+upperdir 可写）

Docker 实现：
  容器进程 = 普通进程（Namespace 隔离）
  网络 = veth+bridge+NAT（域10 已讲）
  存储 = OverlayFS（域6 已讲 overlay）
  资源限制 = cgroups（本域）
```

### P9 追问链

- **Q：容器本质是什么？** → 普通进程（Namespace 隔离+cgroups 限制+OverlayFS 分层）
- **Q：容器为什么轻量？** → 共享内核（无独立内核/无硬件虚拟化开销）
- **Q：容器启动为什么快？** → 无内核启动（只有进程+Namespace+cgroups 配置）

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Namespace 类型** | pid/net/mnt/uts/ipc/user/cgroup/time（8 种隔离） | 实验 1（/proc ns 实测+容器对比） |
| **容器 vs 虚拟机** | 容器=Namespace+Cgroups（共享内核）；虚拟机=独立内核 | 实验 1（叙事） |
| **cgroups v1 vs v2** | v1 每控制器独立；v2 统一层级（推荐） | 实验 2（cgroup2 实测） |
| **容器内存限制** | cgroups memory.max（v2）；超了 OOM Kill（堆外） | 实验 2（衔接域2 OOM） |
| **KVM** | 内核虚拟化（kvm.ko）；Type2 但性能接近 Type1 | 补充（叙事） |
| **容器原理** | Namespace（隔离）+cgroups（限制）+OverlayFS（分层） | 补充（叙事） |
| 容器 OOM vs JVM OOM | 容器=cgroup 超 limit（堆外）；JVM=堆内（-Xmx） | 衔接 |

## 相关文件

- /proc/self/ns 实测：8 种 Namespace
- Docker 容器 ns 对比：net 相同（host 网络），其他不同
- cgroup2 实测：控制器 cpuset/cpu/io/memory/...
- 衔接：网络域10 容器网络、域6 overlay、域2 OOM
