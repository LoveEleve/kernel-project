# p9-net-domain6 · I/O 模型+epoll/Reactor（P9 深度实验）

> 网络轨道域 6/10。覆盖：阻塞/非阻塞/多路复用/select-poll-epoll/LT-ET/惊群/Reactor
> 对照 13 本书：TCP/IP网络编程 Ch12/17、图解网络编程 Ch7-8、深入理解Linux网络 Ch3

---

## 实验 1：阻塞 vs 非阻塞（Java NIO 实测）

```
阻塞模式：accept 会卡住等待连接（线程挂起）
非阻塞模式：accept 立即返回 null（无连接时）
```

### 五种 I/O 模型

| 模型 | read 行为 | 线程状态 |
|---|---|---|
| 阻塞 I/O | read 卡住直到数据到达 | 阻塞等待 |
| 非阻塞 I/O | read 立即返回（无数据返回 -1/EAGAIN） | 轮询（CPU 忙） |
| I/O 多路复用 | select/epoll 等多个 fd，就绪才 read | 阻塞在 select（可等多个） |
| 信号驱动 I/O | 数据到达发信号（SIGIO） | 不阻塞 |
| 异步 I/O | 内核完成后通知（POSIX AIO/Linux AIO） | 不阻塞 |

### P9 追问链

- **Q：阻塞和非阻塞区别？** → 阻塞=等数据；非阻塞=立即返回（需轮询）
- **Q：为什么需要多路复用？** → 非阻塞轮询浪费 CPU；多路复用一次等多个 fd
- **Q：同步 vs 异步？** → 同步=自己 read；异步=内核读完通知（Linux AIO 对文件，网络差）

---

## 实验 2：epoll（Java Selector 实测）

```
Selector 注册 OP_ACCEPT
select 返回 1 个就绪事件
accept 就绪，新连接
```

### select/poll/epoll 对比

| | select | poll | epoll |
|---|---|---|---|
| 数据结构 | fd_set（bitmap） | pollfd 动态数组 | 红黑树+就绪链表 |
| fd 上限 | 1024（FD_SETSIZE） | 无硬限 | 无硬限 |
| 查找 | 线性扫描 O(n) | 线性扫描 O(n) | 事件驱动 O(1) |
| 拷贝 | 每次拷贝 fd_set | 每次拷贝 pollfd | epoll_ctl 一次注册 |
| 触发 | 水平触发 | 水平触发 | LT/ET 可选 |

### epoll 三个系统调用

```
epoll_create：创建 epoll 实例（内核红黑树）
epoll_ctl(ADD/MOD/DEL)：注册/修改/删除 fd（一次注册，长期有效）
epoll_wait：等待就绪事件（只返回就绪的 fd，无需扫描）
```

### P9 追问链

- **Q：epoll 为什么比 select 快？** → 事件驱动（就绪才通知）+ O(1) 查找（红黑树）+ 一次注册
- **Q：select 的 1024 限制？** → fd_set 是 bitmap（FD_SETSIZE=1024）
- **Q：epoll 数据结构？** → 红黑树（注册 fd）+ 就绪链表（返回就绪）

---

## LT vs ET（水平触发 vs 边缘触发）

```
LT（Level Trigger，默认）：
  只要 fd 就绪（缓冲区有数据），每次 epoll_wait 都返回
  → 可以不读完（下次还会通知）
  → 编程简单（默认）

ET（Edge Trigger）：
  只在状态变化时通知一次（空→有数据）
  → 必须一次读完（否则不通知）
  → 必须非阻塞 + 循环 read 直到 EAGAIN
  → 高效（减少 epoll_wait 调用）
```

### LT vs ET 对比

| | LT | ET |
|---|---|---|
| 通知时机 | 缓冲区就绪（持续） | 状态变化（一次） |
| 读取要求 | 可不读完 | 必须读完 |
| 编程难度 | 简单 | 复杂（非阻塞+循环） |
| 性能 | 一般 | 更高（少通知） |

### P9 追问链

- **Q：ET 为什么必须非阻塞？** → 必须循环 read 到 EAGAIN（否则数据留在缓冲不通知）
- **Q：ET 不读完会怎样？** → 数据留在缓冲，但不触发通知（应用以为没数据）
- **Q：生产用 LT 还是 ET？** → 大多数用 LT（简单）；Netty 等高性能框架用 ET

---

## 惊群问题

```
问题：多个进程/线程同时 accept 同一个 listen socket
  → 数据来一个连接，所有进程被唤醒（惊群）
  → 只有一个 accept 成功，其他浪费唤醒

解决：
  ① SO_REUSEPORT（内核 3.9+）：多个 socket 绑同一端口，内核分发
  ② 串行化 accept（单进程 accept + 转发）
  ③ epoll 的 EPOLLEXCLUSIVE（内核 4.5+）：只唤醒一个
```

**P9 答法**："惊群=多进程同时 accept 被同时唤醒。解决：SO_REUSEPORT（内核分发）/EPOLLEXCLUSIVE（只唤醒一个）/单进程 accept。"

---

## Reactor 模式

```
Reactor（反应堆）：
  ① 注册 fd + 事件到 Selector/epoll
  ② 事件循环：epoll_wait 等就绪
  ③ 就绪事件分发给 Handler（accept/read/write）
  ④ Handler 处理（非阻塞）

单 Reactor 单线程：Redis（简单）
单 Reactor 多线程：Reactor 处理连接，worker 线程处理业务
主从 Reactor：Master 处理连接，Slave 处理 I/O（Netty 主从）

vs Proactor：
  Reactor：应用自己 read（同步 I/O）
  Proactor：内核读完通知（异步 I/O，POSIX AIO）
  Linux 网络多用 Reactor（epoll 是同步）
```

### P9 追问链

- **Q：Reactor 核心？** → 事件循环（epoll_wait）+ 事件分发（Handler）
- **Q：Redis 用什么模型？** → 单 Reactor 单线程（epoll + 单线程处理）
- **Q：Netty 用什么模型？** → 主从 Reactor（BossGroup 处理连接，WorkerGroup 处理 I/O）

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **阻塞 vs 非阻塞** | 阻塞等数据；非阻塞立即返回（需轮询） | 实验 1（Java NIO 实测） |
| **epoll** | 红黑树+就绪链表；一次注册 O(1) 返回 | 实验 2（Selector 实测） |
| select/poll/epoll | bitmap 线性 O(n) vs 事件驱动 O(1) | 实验 2（对比表） |
| **LT vs ET** | LT 持续通知可不读完；ET 变化通知必须读完 | 补充（对比表） |
| **惊群** | 多进程同时 accept 被唤醒；SO_REUSEPORT/EPOLLEXCLUSIVE 解决 | 补充（叙事） |
| **Reactor** | 事件循环+分发；Redis 单 Reactor；Netty 主从 | 补充（叙事） |
| 异步 vs 同步 | 同步=自己 read；异步=内核完成通知 | 实验 1（五模型对比） |
| epoll 数据结构 | 红黑树（注册）+就绪链表（返回） | 补充（叙事） |

---

## 补充 #118：Proactor vs Reactor 对比

| | Reactor | Proactor |
|---|---|---|
| I/O 类型 | 同步（应用自己 read） | 异步（内核读完通知） |
| 读取时机 | 事件就绪→应用 read | 内核完成→通知结果 |
| 数据拷贝 | 应用 read 时拷贝 | 内核直接拷贝到应用缓冲 |
| Linux 支持 | epoll（主流） | io_uring / POSIX AIO（对文件好，对网络差） |
| 代表 | Netty/Redis | Windows IOCP / Linux io_uring |

**P9 答法**："Reactor=同步 I/O（epoll 就绪后应用自己 read）；Proactor=异步 I/O（内核读完通知）。Linux 网络多用 Reactor（epoll 成熟）；io_uring 是 Linux 的 Proactor 方案。"

---

## 补充 #119：epoll 源码级（ep_poll_callback/就绪链表）

```
epoll_wait 返回的内核路径：
① 数据到达 → 网卡中断 → 协议栈处理 → socket 可读
② 唤醒路径：tcp_data_queue → sk_data_ready → ep_poll_callback
③ ep_poll_callback：把 socket 的 epitem 加入 ep->rdllist（就绪链表）
④ epoll_wait：从 ep->rdllist 取就绪事件返回（无需扫描全树）

关键：ep_poll_callback 是"事件通知"的入口
  → socket 数据到达时主动回调（不是 epoll_wait 轮询）
  → 就绪链表只放就绪的（O(1) 取）
```

**P9 答法**："epoll 事件驱动：数据到达→sk_data_ready→ep_poll_callback 把 epitem 加入就绪链表→epoll_wait 从链表取。不是轮询，是回调驱动（就绪才通知）。"

---

## 补充 #120：Netty 线程模型（BossGroup/WorkerGroup）

```
Netty 主从 Reactor：
  BossGroup：处理新连接（accept）→ 分配给 WorkerGroup
  WorkerGroup：处理 I/O（read/write）→ 交给业务线程池

线程数：
  BossGroup：默认 1（accept 不需要多线程）
  WorkerGroup：默认 2×CPU（处理 I/O）

事件流：
  Boss accept → 注册到 Worker 的 Selector → Worker epoll_wait
  → read → 解码 → 业务线程池处理 → 编码 → write

对比：
  Redis：单 Reactor 单线程（简单场景）
  Netty：主从 Reactor 多线程（高并发网络框架）
```

**P9 答法**："Netty 主从 Reactor：BossGroup 处理连接（默认 1 线程），WorkerGroup 处理 I/O（默认 2×CPU）。事件流：accept→注册 Worker→epoll_wait→read→业务线程池→write。高并发网络框架标配。"

---

## 补充考点速查（#118-#120）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Proactor vs Reactor** | Reactor=同步(epoll+自己read)；Proactor=异步(内核读完通知) | #118（对比表） |
| **epoll 源码级** | 数据到达→ep_poll_callback→就绪链表→epoll_wait 取（回调驱动非轮询） | #119（路径） |
| **Netty 线程模型** | BossGroup(accept)+WorkerGroup(I/O)；主从 Reactor 多线程 | #120（叙事） |

## 相关文件

- 实验：`16-p9-net-io/IoModelDemo.java`（阻塞/非阻塞/epoll Java NIO 实测）
