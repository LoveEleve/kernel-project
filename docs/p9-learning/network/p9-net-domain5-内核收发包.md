# p9-net-domain5 · 内核收发包全链路（P9 深度实验）

> 网络轨道域 5/10。覆盖：网卡→中断→协议栈→socket + NAPI/GRO/GSO/TSO + RPS/RFS + 本机优化
> 对照 13 本书：深入理解Linux网络 Ch2/4、嵌入式Linux网络 Ch2-6

## 实验环境

```
网卡: ens33（TSO/GRO on）
RPS: off（rps_cpus=0）
工具: ethtool/sysctl
```

---

## 实验 1：网卡 Offload（ethtool 实测）

```
tcp-segmentation-offload: on     ← TSO（TCP 分段卸载网卡）
generic-segmentation-offload: on ← GSO（通用分段卸载）
generic-receive-offload: on      ← GRO（通用接收合并）
rx-checksumming: off             ← 本机网卡校验和卸载关
```

### Offload 机制

```
TSO（TCP Segmentation Offload）：
  发送：TCP 大块数据交给网卡分段（省 CPU）
  条件：网卡支持 + 对端 MSS 协商
GSO（Generic Segmentation Offload）：
  内核软件分段（网卡不支持 TSO 时兜底）
GRO（Generic Receive Offload）：
  接收：多个小包合并成大包（减少协议栈调用）
LRO（Large Receive Offload）：
  网卡硬件合并（GRO 更安全，可跨设备）
```

### P9 追问链

- **Q：TSO 干什么？** → 发送时网卡做 TCP 分段（CPU 省）
- **Q：GRO 干什么？** → 接收时合并小包（减少协议栈调用）
- **Q：offload 有什么副作用？** → 抓包看不到分段后的包（网卡内处理）；排障需关 offload

---

## 实验 2：RPS/RFS（CPU 亲和性）

```
rps_cpus: 00000000,00000000,00000000,00000000  ← RPS off（本机）
rps_flow_cnt: 0                                ← RFS off
```

### RPS/RFS/XPS 机制

```
RPS（Receive Packet Steering）：
  软件层把收到的包按 hash 分发到不同 CPU 的 backlog 队列
  → 单网卡多队列时，避免单 CPU 处理所有包

RFS（Receive Flow Steering）：
  把包分发到"处理该连接的 CPU"（减少缓存失效）
  → 需要 rps_flow_cnt + flow_table

XPS（Transmit Packet Steering）：
  发送时选择 CPU（避免多 CPU 抢同一网卡队列）
```

### P9 追问链

- **Q：RPS/RFS 区别？** → RPS 按 hash 分发；RFS 按"哪个 CPU 处理过此连接"分发（更智能）
- **Q：单队列网卡需要 RPS 吗？** → 不需要（单队列单 CPU）；多队列才需要

---

## 内核收包路径（叙事+源码锚点）

```
① 网卡收帧 → DMA 写入环形缓冲区（RX ring）
② 硬件中断 → NAPI 关中断，转软中断轮询
③ NAPI 轮询：net_rx_action → poll（网卡驱动）
④ GRO 合并 → netif_receive_skb
⑤ 协议栈：eth_type_trans → IP 层（ip_rcv）→ TCP 层（tcp_v4_rcv）
⑥ socket 层：sk_receive_queue → 唤醒等待进程（epoll/read 返回）
```

### 源码锚点（openjdk 不适用，Linux 内核）

```
RX ring: 网卡驱动（e1000/virtio_net）
NAPI: net_rx_action（softirq）
ip_rcv: net/ipv4/ip_input.c
tcp_v4_rcv: net/ipv4/tcp_ipv4.c
```

## 内核发包路径（叙事）

```
① 应用 send() → 内核 tcp_sendmsg → sk_write_queue
② TCP 分段（TSO 或内核分段）
③ IP 层：ip_queue_xmit → 路由查找 → 邻居子系统（ARP）
④ 设备层：dev_queue_xmit → qdisc（排队）→ 网卡驱动
⑤ 网卡发送（TSO 时网卡分段）
```

---

## 实验 5：Socket 缓冲区（sysctl 实测）

```
net.core.rmem_default = 212992      ← 默认接收缓冲 208KB
net.core.wmem_default = 212992      ← 默认发送缓冲 208KB
net.core.rmem_max = 4194304         ← 最大接收缓冲 4MB
net.core.wmem_max = 4194304         ← 最大发送缓冲 4MB
net.ipv4.tcp_rmem = 4096 131072 33554432  ← TCP 接收（min/default/max）
net.ipv4.tcp_wmem = 4096 16384 4194304    ← TCP 发送（min/default/max）
```

### 缓冲区机制

```
SO_RCVBUF/SO_SNDBUF：应用设置（getsockopt/setsockopt）
TCP 自动调优：tcp_rmem/tcp_wmem 三元组（min/default/max）
  → 根据 BDP 自动调整（内核 2.6+ 默认开 tcp_moderate_rcvbuf）
缓冲区大小影响：吞吐量（BDP）/内存占用（每连接）
```

### P9 追问链

- **Q：socket 缓冲区怎么调？** → setsockopt(SO_RCVBUF/SO_SNDBUF) 或 tcp_rmem/tcp_wmem
- **Q：缓冲区多大合适？** → ≥BDP（带宽×RTT）；每连接内存=收+发缓冲
- **Q：tcp_rmem 三元组？** → min/default/max（自动调优范围）

---

## 实验 4：本机通信优化（叙事）

```
Unix socket vs TCP localhost：
  Unix socket：内核内部通信（无协议栈/无网卡，快 2-5 倍）
  TCP localhost：走完整协议栈（loopback 虚拟网卡，但不过物理网卡）
  127.0.0.1 不过网卡：数据直接 loopback 驱动回环
  生产优化：本机进程间通信用 Unix socket（如 Docker daemon）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **TSO/GRO** | TSO=发送网卡分段；GRO=接收合并小包 | 实验 1（ethtool on） |
| **RPS/RFS** | RPS 按 hash 分发；RFS 按处理 CPU 分发 | 实验 2（rps_cpus=0 off） |
| **收包路径** | 网卡→DMA→NAPI→GRO→IP→TCP→socket→唤醒 | 叙事（源码锚点） |
| **发包路径** | send→tcp_sendmsg→IP→ARP→qdisc→网卡 | 叙事 |
| **socket 缓冲** | rmem/wmem 三元组（min/default/max）自动调优 | 实验 5（sysctl） |
| 本机优化 | Unix socket 比 TCP localhost 快（无协议栈） | 补充（叙事） |
| NAPI | 关中断转软中断轮询（减少中断开销） | 叙事（收包路径） |
| sk_buff | 内核网络包缓冲结构（链表组织） | 叙事 |

---

## 补充 #110：中断/NAPI/softirq 详细机制

### 传统中断处理的问题

```
高 PPS（每秒包数）时：
  每包一次硬中断 → CPU 全花在中断处理（中断风暴）
  → 丢包（中断处理不过来）
```

### NAPI（New API）机制

```
① 硬中断：网卡收包 → 关中断（disable irq）
② 软中断：调度 NET_RX_SOFTIRQ → net_rx_action
③ NAPI poll：轮询网卡收包（一次处理多个，直到无包或预算用完）
④ 处理完 → 开中断（enable irq）

好处：
  批量处理（一次中断收多包）
  关中断期间不丢包（ring buffer 缓存）
  软中断可被 ksoftirqd 处理（不阻塞硬中断）
```

### softirq 分类

```
NET_RX_SOFTIRQ  → 收包处理（NAPI poll）
NET_TX_SOFTIRQ  → 发包处理
TIMER_SOFTIRQ  → 定时器
TASKLET_SOFTIRQ → tasklet
```

### P9 追问链

- **Q：NAPI 解决什么？** → 中断风暴（关中断转软中断轮询，批量处理）
- **Q：硬中断和软中断区别？** → 硬中断=网卡触发（关中断处理）；软中断=可延迟（NET_RX 等）
- **Q：收包为什么用软中断？** → 软中断可批量/可延迟，不阻塞硬中断

---

## 补充 #111：sk_buff 结构（核心数据结构）

```
sk_buff（Socket Buffer）：内核中网络包的"载体"
struct sk_buff {
  unsigned char *head;    // 数据区起始（分配的缓冲）
  unsigned char *data;    // 当前协议头位置（随协议头添加移动）
  unsigned char *tail;    // 数据结束
  unsigned char *end;     // 数据区结束
  unsigned int len;       // 数据长度
  unsigned int data_len;
  struct sk_buff *next;   // 链表组织（队列）
  // ... 协议头指针（nh/mac 指针）
};

作用：
  收包：网卡 DMA → 分配 sk_buff → 协议头逐层剥离（data 指针后移）
  发包：构造 sk_buff → 逐层加协议头（data 指针前移）
  队列：sk_buff 通过 next 串成链表（receive_queue/write_queue）
```

**P9 答法**："sk_buff 是内核网络包载体：head/data/tail/end 四指针管理数据区，协议头剥离时 data 后移，加头时前移；多个 sk_buff 用 next 串成队列。收发包的核心结构。"

---

## 补充 #112：qdisc 排队（发包路径）

```
发包路径最后一环：dev_queue_xmit → qdisc（排队规则）→ 网卡
qdisc 类型：
  pfifo_fast（默认）：多队列 FIFO（简单快）
  fq_codel（推荐）：Flow Queue + CoDel（防 bufferbloat）
  tbf（Token Bucket）：限速
  netem：模拟延迟/丢包（测试）

qdisc 作用：
  ① 排队（网卡忙时缓冲）
  ② 限速/整形
  ③ 拥塞管理（fq_codel 主动丢包防缓冲膨胀）

本机实测：ens33 qdisc = fq_codel（`ip link show` 可见）
```

**P9 答法**："qdisc 是发包排队规则：默认 pfifo_fast，推荐 fq_codel（防 bufferbloat）。作用=排队+限速+拥塞管理。`ip link show` 可看当前 qdisc。"

---

## 补充 #113：netfilter 钩子（收发包 hook 点）

### 5 个钩子点（iptables 基于此）

```
收包：PRE_ROUTING → LOCAL_IN（本机收）
转发：PRE_ROUTING → FORWARD → POST_ROUTING
发包：LOCAL_OUT → POST_ROUTING

iptables 规则挂在这些钩子上：
  filter 表：INPUT/FORWARD/OUTPUT（放行/丢弃）
  nat 表：PREROUTING/POSTROUTING（SNAT/DNAT）
  mangle 表：改包头（TTL/DSCP）
```

### 收发包路径上的 netfilter

```
收包：网卡→NAPI→GRO→ip_rcv→[PRE_ROUTING]→[LOCAL_IN]→tcp_v4_rcv→socket
发包：send→tcp_sendmsg→ip_queue_xmit→[LOCAL_OUT]→[POST_ROUTING]→网卡
```

### P9 追问链

- **Q：iptables 规则挂在哪？** → netfilter 5 个钩子（PREROUTING/INPUT/FORWARD/OUTPUT/POSTROUTING）
- **Q：为什么 Docker 用 iptables？** → 容器网络隔离+DNAT 端口映射都靠 netfilter 钩子（衔接域10）

---

## 补充考点速查（#110-#113）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **NAPI** | 关中断转软中断轮询（批量处理防中断风暴） | #110（叙事） |
| softirq | NET_RX 收包/NET_TX 发包（可延迟的软中断） | #110（叙事） |
| **sk_buff** | head/data/tail/end 四指针；data 移动剥/加协议头；next 串队列 | #111（结构） |
| **qdisc** | 发包排队规则（默认 pfifo_fast，推荐 fq_codel 防 bufferbloat） | #112（叙事） |
| **netfilter 钩子** | 5 钩子（PREROUTING/INPUT/FORWARD/OUTPUT/POSTROUTING）；iptables 挂此 | #113（衔接域10） |

---

## 补充 #114：中断绑核（irqaffinity）

```
/proc/irq/<irq_num>/smp_affinity：中断处理绑定到指定 CPU
  → 网卡中断绑到特定 CPU（避免跨 NUMA 访问）
  → 多队列网卡：每队列中断绑不同 CPU（并行处理）
  → ethtool -L ens33 combined 4：设置多队列
```

## 补充 #115：XPS（发送侧 CPU 亲和）

```
XPS（Transmit Packet Steering）：发送时选择 CPU 队列
  → 与 RPS 对应（RPS=接收侧，XPS=发送侧）
  → 避免多 CPU 抢同一网卡 TX 队列（减少锁竞争）
  → 配置：/sys/class/net/<dev>/queues/tx-*/xps_cpus
```

## 补充 #116：TCP 收发队列

```
接收侧：
  sk_receive_queue：按序到达的数据（read() 从这取）
  out_of_order_queue：乱序包（等缺失包到达后合并）
发送侧：
  sk_write_queue：待发送数据（send() 放这）
  TSO 队列：待分段的大包（网卡不支持 TSO 时内核分段）
```

## 补充 #117：邻居子系统（ARP 内核实现）

```
邻居子系统（neighbour）：管理 IP↔MAC 映射（ARP 缓存）
  → 发包时查邻居表拿 MAC（缓存命中直接发；未命中发 ARP 请求）
  → 表项状态：INCOMPLETE→REACHABLE→STALE→DELAY→PROBE
  → 工具：ip neigh show（查看邻居表）
本机实测：ip neigh show（域1 实验 2 的 arp -a 同款）
```

### 补充考点速查（#114-#117）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| 中断绑核 | irqaffinity 绑定中断到 CPU（多队列并行） | #114（叙事） |
| XPS | 发送侧 CPU 亲和（对应 RPS 接收侧） | #115（叙事） |
| TCP 队列 | receive_queue/out_of_order/write_queue/TSO 队列 | #116（叙事） |
| 邻居子系统 | IP↔MAC 映射（ARP 缓存内核实现）；ip neigh show | #117（衔接域1） |

## 相关文件

- ethtool/sysctl 实测：TSO/GRO/RPS/缓冲区
- 内核源码：`linux/net/ipv4/{ip_input,tcp_ipv4}.c`（本环境无内核源码，叙事级）
