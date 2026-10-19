# p9-net-domain7 · Socket 编程+调优（P9 深度实验）

> 网络轨道域 7/10。覆盖：Socket 选项/多播/域套接字/原始套接字/内核调优参数
> 对照 13 本书：TCP/IP网络编程 Ch9/14/16、图解网络编程 Ch9-12

---

## 实验 1：Socket 选项

### 核心选项（Java setsockopt 对应）

| 选项 | 作用 | 生产场景 |
|---|---|---|
| **SO_REUSEADDR** | 允许重用 TIME_WAIT 地址 | 服务器重启（快速重启） |
| **SO_REUSEPORT** | 多进程绑同一端口（负载均衡） | 多 worker 监听（内核 3.9+） |
| **TCP_NODELAY** | 禁 Nagle 算法（小包立即发） | 延迟敏感（游戏/RPC） |
| **SO_KEEPALIVE** | TCP 保活（探测死连接） | 长连接（默认 7200s） |
| **SO_RCVBUF/SO_SNDBUF** | 收发缓冲区大小 | 大流量（≥BDP） |
| **SO_LINGER** | 关闭时是否等数据发完 | 立即关闭 vs 优雅关闭 |
| **TCP_CORK** | 延迟发送（攒包） | 批量发送（与 NODELAY 相反） |

### Nagle 算法（TCP_NODELAY）

```
Nagle：小包攒批发（减少小包数量）
  → 延迟敏感场景禁用（TCP_NODELAY=1）
  → RPC/游戏/交互式必须关（否则延迟增加）
```

### P9 追问链

- **Q：服务器为什么要 SO_REUSEADDR？** → 快速重启时复用 TIME_WAIT 地址（否则 bind 失败）
- **Q：TCP_NODELAY 什么时候用？** → 延迟敏感（RPC/游戏），禁 Nagle 小包立即发
- **Q：SO_REUSEPORT 好处？** → 多进程绑同一端口，内核负载均衡（替代 accept 惊群）

---

## 实验 2：域套接字（Unix Domain Socket）

```
Unix socket vs TCP localhost：
  Unix socket：内核内部通信（无协议栈/无网卡，快 2-5 倍）
  TCP localhost：走完整协议栈（loopback）
  路径：/tmp/xxx.sock（文件系统）或 @abstract（抽象命名空间）
  用途：本机进程间通信（Docker daemon/Redis 本地）
```

**P9 答法**："Unix socket 比 TCP localhost 快（无协议栈），本机 IPC 用它（Docker/Redis）。路径在文件系统或抽象命名空间。"

---

## 实验 3：多播/广播

```
广播：255.255.255.255（受限）/ 子网广播（如 192.168.13.255）
  → 只能 UDP（TCP 无广播）
  → 局域网发现（DHCP）

多播（组播）：224.0.0.0-239.255.255.255（D 类地址）
  → 加入组（IP_ADD_MEMBERSHIP）才收
  → IGMP 协议管理组成员
  → 视频会议/IPTV/金融行情
```

**P9 答法**："广播=子网所有主机（仅 UDP）；多播=加入组才收（IGMP 管理）。视频/行情用多播省带宽。"

---

## 实验 4：原始套接字（SOCK_RAW，了解级）

```
SOCK_RAW：构造自定义 IP/TCP 头（需 root）
  → Ping 实现（构造 ICMP）
  → 端口扫描（构造 TCP SYN）
  → 网络嗅探（配合 pcap）
  → 安全工具（nmap/tcpdump 底层）
```

---

## 实验 5：内核调优参数（sysctl 全套）

```
# 连接管理
net.core.somaxconn = 4096              ← listen backlog 上限
net.ipv4.tcp_max_syn_backlog = 1024    ← SYN 半连接队列
net.ipv4.tcp_max_tw_buckets = ???       ← TIME_WAIT 上限
net.ipv4.tcp_tw_reuse = 2              ← TIME_WAIT 复用
net.ipv4.ip_local_port_range = 32768 60999 ← 本地端口范围

# 超时
net.ipv4.tcp_fin_timeout = 60          ← FIN_WAIT_2 超时
net.ipv4.tcp_keepalive_time = 7200     ← 保活间隔

# 缓冲区
net.core.rmem_max = 4194304            ← 最大接收缓冲 4MB
net.core.wmem_max = 4194304            ← 最大发送缓冲 4MB
net.ipv4.tcp_rmem = 4096 131072 33554432 ← TCP 接收三元组
net.ipv4.tcp_wmem = 4096 16384 4194304   ← TCP 发送三元组

# 拥塞控制
net.ipv4.tcp_congestion_control = cubic
net.ipv4.tcp_fastopen = 1              ← TFO
```

### 生产调优场景

```
高并发服务器：
  somaxconn=65535 / tcp_max_syn_backlog=65535
  tcp_tw_reuse=1 / ip_local_port_range 扩大
  tcp_rmem/tcp_wmem 按 BDP 调大

长连接服务：
  tcp_keepalive_time=300（5 分钟探测）
  tcp_fin_timeout=15（快速回收）

延迟敏感：
  tcp_fastopen=3（0-RTT）
  TCP_NODELAY（应用层）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **SO_REUSEADDR** | 重用 TIME_WAIT 地址（服务器快速重启） | 实验 1（选项表） |
| **TCP_NODELAY** | 禁 Nagle（延迟敏感必须关） | 实验 1 |
| SO_REUSEPORT | 多进程绑同端口（内核负载均衡，替代惊群） | 实验 1 |
| **域套接字** | Unix socket 比 TCP localhost 快（无协议栈） | 实验 2（叙事） |
| 多播/广播 | 广播=子网所有（仅 UDP）；多播=加入组才收 | 实验 3（叙事） |
| 原始套接字 | SOCK_RAW 构造自定义头（需 root） | 实验 4（叙事） |
| **内核调优** | somaxconn/syn_backlog/tw_reuse/port_range/缓冲区 | 实验 5（sysctl） |
| Nagle | 小包攒批发；延迟敏感用 TCP_NODELAY 禁用 | 补充（叙事） |

---

## 补充 #121：粘包/拆包（P9 高频必问）

### 问题本质

```
TCP 是字节流（无消息边界）：
  发送方：send("Hello") send("World") → 网络上传输可能合并/拆分
  接收方：read() 可能收到 "HelloWorld"（粘包）或 "Hel"（拆包）

原因：
  ① TCP 网络优化（Nagle 攒包/GSO 分段）
  ② 接收方缓冲区满/空（read 时机不确定）
  ③ MSS 限制（大数据自动分段）
```

### 应用层协议设计（三种方案）

```
① 定长消息：
   每条固定 N 字节（如 100 字节，不足补空格）
   缺点：浪费空间

② 分隔符：
   每条以特殊字符结尾（如 \n）
   缺点：内容不能含分隔符（需转义）

③ 长度前缀（推荐）：
   [4字节长度][消息体]
   先读 4 字节长度 → 再读 N 字节体
   优点：通用（Protobuf/Netty LengthFieldBasedFrameDecoder）
```

### Java/Netty 解决

```
Java：自定义协议（长度前缀 + DataInputStream.readFully）
Netty：LengthFieldBasedFrameDecoder（长度字段解码器）
  → 自动处理粘包/拆包（按长度切帧）
```

### P9 追问链

- **Q：TCP 为什么有粘包？** → TCP 字节流无边界（Nagle/GSO/缓冲区）
- **Q：怎么解决？** → 应用层协议：定长/分隔符/长度前缀（推荐）
- **Q：Netty 怎么处理？** → LengthFieldBasedFrameDecoder（按长度切帧）

---

## 补充 #122：优雅关闭（shutdown vs close）

```
close：立即关闭（丢弃未发数据，发 RST 如果有未读数据）
shutdown：半关闭（SHUT_RD 读关/SHUT_WR 写关/SHUT_RDWR 全关）

优雅关闭流程：
  ① shutdown(SHUT_WR)：发 FIN（我没数据了）
  ② 继续 read：等对方数据 + 对方 FIN
  ③ read 返回 0：对方关闭
  ④ close：完全关闭

HTTP Connection: close 的实现：shutdown(SHUT_WR) + read 到 0 + close
```

**P9 答法**："close 立即关闭（丢数据/RST）；shutdown 半关闭（SHUT_WR 发 FIN 后还能收）。优雅关闭：shutdown(WR)→read 到 0→close。"

---

## 补充 #123：多线程/线程池服务器模型

```
① 一连接一线程：
   每 accept 一个连接 pthread_create 一个线程
   缺点：线程开销大（内存/上下文切换），不适合高并发

② 线程池：
   accept 后提交到线程池（固定线程数复用）
   优点：控制并发数，避免线程爆炸
   参数：core/max/queue/拒绝策略（JVM 域5 已讲）

③ Reactor + 线程池（生产标准）：
   epoll 事件循环（单线程）→ 就绪事件 → 业务线程池处理
   Netty：BossGroup(accept) + WorkerGroup(I/O) + 业务线程池

④ 协程/虚拟线程（新趋势）：
   JDK21 虚拟线程：百万级并发（阻塞不占 OS 线程）
   Go goroutine / Kotlin coroutine
```

**P9 答法**："高并发服务器：Reactor+线程池（epoll 事件循环+业务线程池）。一连接一线程不适合高并发（线程开销）。虚拟线程是新趋势（JDK21+）。"

---

## 补充考点速查（#121-#123）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **粘包/拆包** | TCP 字节流无边界；应用层协议：定长/分隔符/长度前缀（推荐） | #121（叙事） |
| **优雅关闭** | shutdown 半关闭（SHUT_WR 发 FIN）+read 到 0+close | #122（叙事） |
| **服务器模型** | 一连接一线程/线程池/Reactor+线程池/虚拟线程 | #123（叙事） |

## 相关文件

- 内核参数：sysctl 全套实测
- Socket 选项：Java setsockopt 对应表
