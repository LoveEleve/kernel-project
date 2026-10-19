# p9-net-domain8 · 零拷贝+高性能（P9 深度实验）

> 网络轨道域 8/10。覆盖：mmap/sendfile/splice + DPDK/RDMA/XDP + TCP内存开销 + fd/端口约束
> 对照 13 本书：图解网络编程 Ch13-14、深入理解Linux网络 Ch7-8、高性能网络详解

---

## 实验 1：零拷贝机制

### 传统拷贝路径（4 次拷贝 + 4 次上下文切换）

```
read(file)：磁盘→内核缓冲→用户缓冲（2 次拷贝）
write(socket)：用户缓冲→内核 socket 缓冲→网卡（2 次拷贝）
共 4 次拷贝 + 4 次用户/内核切换
```

### 零拷贝方案

| 方案 | 拷贝次数 | 原理 |
|---|---|---|
| **mmap + write** | 3 次 | mmap 映射内核缓冲到用户态（省 1 次拷贝） |
| **sendfile** | 2-3 次 | 内核直接文件→socket（无用户态拷贝） |
| **splice** | 0 次（管道） | 管道零拷贝（fd→fd 直接移动） |

### sendfile 原理

```
sendfile(out_fd, in_fd, offset, count)：
  文件数据直接从内核 page cache → socket 缓冲（无用户态参与）
  → 网卡支持 DMA 聚合（sendfile + DMA = 真正 2 次拷贝）
  → 限制：in_fd 必须支持 mmap（文件/块设备）
```

### P9 追问链

- **Q：传统 read+write 几次拷贝？** → 4 次（磁盘→内核→用户→内核→网卡）
- **Q：sendfile 省了什么？** → 省用户态拷贝（内核直接 page cache→socket）
- **Q：mmap 和 sendfile 区别？** → mmap 映射到用户态（仍需用户态 write）；sendfile 全内核
- **Q：零拷贝适用场景？** → 文件传输（静态资源/日志发送）；Java 用 FileChannel.transferTo

---

## 实验 2：传统 vs 零拷贝（对比）

```
传统：read(file) + write(socket)
  磁盘 → page cache → 用户缓冲 → socket 缓冲 → 网卡
  4 次拷贝 + 4 次切换

mmap + write：
  磁盘 → page cache（映射用户态） → socket 缓冲 → 网卡
  3 次拷贝 + 4 次切换

sendfile：
  磁盘 → page cache → socket 缓冲 → 网卡
  2-3 次拷贝 + 2 次切换（全内核）

Java：FileChannel.transferTo（底层 sendfile）
```

---

## 实验 3：DPDK/RDMA/XDP（三种绕过内核方案）

### DPDK（Data Plane Development Kit）

```
原理：用户态轮询网卡（PMD），绕过内核协议栈
  → 无中断/无系统调用/无内核拷贝
  → 大页内存（hugepage）+ 零拷贝 + CPU 亲和
  → 性能：千万 PPS（比内核快 10 倍）
  → 代价：独占网卡/CPU（不适合混合部署）
适用：NFV/虚拟交换机/高性能网关
```

### RDMA（Remote Direct Memory Access）

```
原理：网卡直接读写远程内存（绕过内核/CPU）
  → 微秒级延迟（比 TCP+epoll 快）
  → 实现：InfiniBand/RoCE/iWARP
  → Queue Pair（QP）+ Verbs API
适用：存储（NVMe over Fabrics）/HPC/数据库
```

### XDP（eXpress Data Path）

```
原理：BPF 程序在网卡驱动 hook 点运行（收包最早点）
  → 比软中断还早（包还没进协议栈就处理）
  → Action：PASS/DROP/TX/REDIRECT
  → 性能：千万 PPS 过滤/转发
适用：DDoS 防护/负载均衡/流量过滤
```

### 三者对比

| | DPDK | RDMA | XDP |
|---|---|---|---|
| 绕过 | 内核协议栈 | 内核+CPU | 协议栈（驱动层） |
| 通信 | 用户态↔网卡 | 网卡↔远程内存 | 网卡驱动层 |
| 延迟 | 微秒 | 亚微秒 | 纳秒级过滤 |
| 场景 | NFV/网关 | 存储/HPC | DDoS/过滤 |

**P9 答法**："DPDK=用户态轮询绕内核（千万 PPS）；RDMA=网卡直读写远程内存（微秒延迟）；XDP=BPF 在驱动层处理（最早过滤点）。三者都是绕过内核协议栈的高性能方案。"

---

## 实验 4：TCP 内存开销计算

### 单条 TCP 连接内存

```
每条连接 ≈ tcp_rmem 默认 + tcp_wmem 默认 + sock 结构 + 协议栈开销
  ≈ 131072 + 16384 + ~2KB ≈ 150KB/连接（默认配置）

一万条长连接：150KB × 10000 ≈ 1.5GB
调优后（减小缓冲）：tcp_rmem/tcp_wmem 调小 → 但影响吞吐
```

### 实测（sysctl）

```
tcp_rmem = 4096 131072 33554432   ← 默认接收 128KB
tcp_wmem = 4096 16384 4194304     ← 默认发送 16KB
tcp_mem = 189069 252094 378138    ← TCP 总内存（页为单位）
```

### P9 追问链

- **Q：一条 TCP 连接占多少内存？** → ~150KB（默认缓冲+sock 结构）
- **Q：一万长连接要多少内存？** → ~1.5GB（调缓冲可减小但影响吞吐）
- **Q：内存不够怎么办？** → 减小 tcp_rmem/tcp_wmem/减少连接数/用连接池

---

## 实验 5：fd/端口约束（sysctl 实测）

```
nr_open = 1048576          ← 单进程 fd 上限（100 万）
file-max = 9223372036854775807 ← 系统 fd 上限（极大）
ulimit -n = 1048576        ← 当前 shell fd 限制
ip_local_port_range = 32768 60999 ← 出向端口 ~2.8 万
```

### 百万连接约束

```
一台机器最多 TCP 连接数受限于：
  ① 文件描述符：nr_open（单进程）+ file-max（系统）
  ② 端口数：ip_local_port_range（出向）+ 服务端监听端口
  ③ 内存：每连接 ~150KB（tcp_rmem+wmem+sock）
  ④ 四元组：源IP×源端口×目的IP×目的端口（唯一）

C10M（千万连接）：
  fd：nr_open=1000万（调 ulimit）
  内存：调小缓冲（tcp_rmem/wmem）→ 每连接 ~10KB
  端口：多 IP（每个 IP 6.5 万端口）或 SO_REUSEPORT
  CPU：epoll + RPS/RFS + CPU 亲和
```

**P9 答法**："百万连接约束：fd（nr_open）/端口（ip_local_port_range）/内存（每连接 ~150KB）/四元组。C10M 需调 fd+缓冲+多 IP+epoll+CPU 亲和。"

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **传统拷贝** | 4 次拷贝（磁盘→内核→用户→内核→网卡） | 实验 1（路径） |
| **sendfile** | 内核直接文件→socket（2-3 次拷贝，省用户态） | 实验 1（原理） |
| **mmap vs sendfile** | mmap 映射用户态（仍需 write）；sendfile 全内核 | 实验 1（对比） |
| **DPDK/RDMA/XDP** | 用户态轮询/网卡直读写/驱动层 BPF（三者绕内核） | 实验 3（对比表） |
| **TCP 内存开销** | ~150KB/连接（tcp_rmem+wmem+sock） | 实验 4（sysctl） |
| **fd/端口约束** | nr_open/ip_local_port_range/四元组 | 实验 5（sysctl 实测） |
| C10M | 调 fd+缓冲+多 IP+epoll+CPU 亲和 | 实验 5（叙事） |
| 零拷贝场景 | 文件传输（静态资源/日志发送）；Java FileChannel.transferTo | 实验 1 |

---

## 补充 #124：Java 零拷贝 API（生产相关）

```
Java 零拷贝方式：
① FileChannel.transferTo(position, count, WritableByteChannel)
   → 底层 sendfile（Linux）；文件→SocketChannel
   → 例：文件服务器发送文件（NIO）

② FileChannel.transferFrom(ReadableByteChannel, position, count)
   → 接收文件到本地（反向）

③ Netty FileRegion（DefaultFileRegion）
   → 底层 transferTo；Netty 文件传输（零拷贝）

④ Netty MixedFileRegion
   → 多区域组合（大文件分段零拷贝）

生产场景：
  静态资源服务器（Nginx/Netty 文件传输）
  日志收集（文件→网络发送）
  大文件上传/下载
```

**P9 答法**："Java 零拷贝：FileChannel.transferTo（底层 sendfile）/Netty FileRegion。生产文件传输用它（省用户态拷贝）。"

---

## 补充 #125：splice 原理 + sendfile 限制

### splice（管道零拷贝）

```
splice(fd_in, offset_in, fd_out, offset_out, len, flags)：
  数据在两个 fd 间直接移动（不经过用户态）
  → fd_in 可以是管道/文件/socket
  → 通过管道（pipe）中转（内核 buffer）
  → 真正 0 次拷贝（用户态）

例：文件 → 管道 → socket（sendfile 的通用版）
```

### sendfile 限制

```
① in_fd 必须支持 mmap（文件/块设备，不能是 socket）
② 只能 文件→socket（不能 socket→socket）
③ 不能自定义数据处理（纯传输）

splice 更通用：任意 fd→管道→fd（socket→socket 也行）
```

**P9 答法**："splice=管道零拷贝（fd→管道→fd，真正 0 次用户态拷贝）；sendfile 限制 in_fd 必须文件、只能文件→socket。splice 更通用。"

---

## 补充 #126：io_uring 零拷贝（新趋势，了解级）

```
io_uring（Linux 5.1+）：
  新一代异步 I/O（替代 select/epoll/AIO）
  → 提交队列+完成队列（SQ/CQ，无系统调用开销）
  → 支持 send/recv/文件读写/accept 全异步
  → 零拷贝：固定缓冲区（registered buffers）

vs epoll：
  epoll：同步 I/O（就绪后应用 read）
  io_uring：全异步（内核完成通知）= Proactor 模式

Java 支持：
  JDK19+ 虚拟线程 + io_uring（Project Loom）
  Netty io_uring 传输（实验性）
```

**P9 答法**："io_uring 是 Linux 新一代异步 I/O（5.1+）：提交/完成队列，全异步（Proactor），支持零拷贝。Java 19+ 虚拟线程/Netty io_uring 传输跟进。"

---

## 补充考点速查（#124-#126）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Java 零拷贝** | FileChannel.transferTo（底层 sendfile）/Netty FileRegion | #124（API） |
| **splice** | 管道零拷贝（fd→管道→fd，真正 0 次用户态拷贝） | #125（原理） |
| sendfile 限制 | in_fd 必须文件；只能文件→socket；splice 更通用 | #125 |
| **io_uring** | Linux 5.1+ 全异步 I/O（Proactor）；提交/完成队列 | #126（趋势） |

## 相关文件

- sysctl 实测：fd/端口/缓冲区/tcp_mem
- 原理：传统 vs mmap vs sendfile 拷贝路径对比
