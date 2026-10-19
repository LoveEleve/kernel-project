# p9-net-domain9 · 网络排障方法论（P9 深度实验）

> 网络轨道域 9/10。覆盖：tcpdump 抓包实战/ss/netstat/监控指标/工具失效逃生
> 核心命题：**tcpdump/ss 依赖内核；内核/网卡卡死时工具也失效，怎么办？**

---

## 实验 1：tcpdump 抓包实战（BPF 过滤器）

### 常用过滤器（实测可用）

```bash
# 指定主机
tcpdump -nn host 192.168.13.2 and icmp

# 指定端口
tcpdump -nn port 80 and tcp

# 抓 SYN（握手）
tcpdump -nn 'tcp[tcpflags] & tcp-syn != 0'

# 抓指定网段
tcpdump -nn net 192.168.13.0/24

# 抓包存文件（Wireshark 打开）
tcpdump -i ens33 -w capture.pcap

# 抓指定长度
tcpdump -nn greater 1000    # 大于 1000 字节
```

### BPF 语法

```
host <ip>           # 指定主机
src/dst host <ip>   # 源/目的主机
port <n>            # 指定端口
src/dst port <n>    # 源/目的端口
net <cidr>          # 指定网段
tcp/udp/icmp        # 协议
and/or/not          # 逻辑运算
tcp[tcpflags]       # TCP 标志位偏移
```

### P9 追问链

- **Q：怎么抓 TCP 握手？** → `tcpdump -nn 'tcp[tcpflags] & tcp-syn != 0'`
- **Q：怎么抓指定主机所有流量？** → `tcpdump -nn host <ip>`
- **Q：抓包存文件给谁看？** → Wireshark（图形化分析）

---

## 实验 2：ss/netstat 排障（实测）

### 连接状态统计

```
ss -tan | awk 'NR>1 {print $1}' | sort | uniq -c | sort -rn
→ 17 LISTEN / 16 ESTAB
```

### 监听端口+进程

```
ss -tlnp
→ LISTEN 0 4096 0.0.0.0:22 ...（监听端口+进程名）
```

### TIME_WAIT/CLOSE_WAIT 统计

```
TIME_WAIT: 0    ← 主动关闭后等 2MSL
CLOSE_WAIT: 0   ← 被动关闭方没 close（应用 bug）
```

### 排障常用命令

```bash
ss -tan | grep TIME-WAIT | wc -l      # TIME_WAIT 数量
ss -tan | grep CLOSE-WAIT | wc -l     # CLOSE_WAIT 数量
ss -tnp | grep :80                    # 80 端口连接+进程
ss -s                                 # 连接统计摘要
netstat -tlnp                         # 监听端口（同 ss -tlnp）
```

---

## 网络监控指标

| 指标 | 含义 | 排障意义 |
|---|---|---|
| **带宽** | bits/s | 是否打满链路 |
| **延迟** | RTT (ms) | 网络质量 |
| **丢包率** | % | 网络拥塞/质量问题 |
| **重传率** | % | 丢包导致（TCP 重传） |
| **零窗口** | 次数 | 接收方缓冲满（流控背压） |
| **TIME_WAIT** | 数量 | 主动关闭太多（端口耗尽风险） |
| **CLOSE_WAIT** | 数量 | 应用没 close（★ 排障重点） |
| **SYN_RECV** | 数量 | 半连接队列（SYN flood 风险） |

### 监控工具

```
ss/netstat：连接状态/队列
iftop/nload：带宽
mtr/ping：延迟/丢包
sar -n DEV/TCP：历史网络统计
prometheus node_exporter：自动化监控
```

---

## 工具失效逃生（P9 核心，类似 JVM 域6）

### 核心命题

```
tcpdump/ss 依赖内核（netlink/AF_PACKET）
  → 内核网络栈卡死/网卡驱动挂/softirq 卡死
  → tcpdump/ss 也失效（依赖同样的内核路径）
  → 怎么办？
```

### 逃生通道（绕过内核网络栈）

```bash
# ① /proc 网络信息（内核数据结构，不走协议栈）
cat /proc/net/tcp        # TCP 连接表（hex 编码）
cat /proc/net/sockstat   # socket 统计
cat /proc/net/dev        # 网卡流量统计
cat /proc/interrupts     # 中断统计（网卡中断）

# ② 网卡驱动层信息（绕过协议栈）
ethtool -S ens33         # 网卡统计（丢包/错误）
ip -s link show ens33    # 网卡收发包统计
cat /sys/class/net/ens33/statistics/*  # 原始统计文件

# ③ perf（OS 级采样，绕过内核网络栈）
perf top -g              # CPU 采样（看 softirq/网卡驱动）
perf record -g -- sleep 10

# ④ 硬件层（最底层）
dmesg | grep -i eth      # 内核日志（网卡驱动）
ethtool ens33            # 网卡状态/驱动信息
```

### 排障决策树（工具失效版）

```
网络不通/慢
├── tcpdump/ss（走内核协议栈）
│   ├── 有响应 → 正常排查（看状态/抓包）
│   └── 卡住（超时） → 内核网络栈/网卡可能卡死
│       ↓
├── /proc/net（内核数据结构，不走协议栈）★
│   ├── 有数据 → 看 TCP 表/socket 统计
│   └── 无数据 → 内核更深层卡死
│       ↓
├── ethtool -S / ip -s link（网卡驱动层）
│   → 看网卡丢包/错误统计
│       ↓
├── perf top（OS 级 CPU 采样）
│   → 看 softirq/网卡驱动 CPU 占用
│       ↓
└── dmesg / 硬件层
    → 看网卡驱动日志/硬件错误
```

### P9 追问链

- **Q：tcpdump 依赖什么？** → 内核 AF_PACKET socket（走内核网络栈）
- **Q：内核网络栈卡死 tcpdump 怎么办？** → /proc/net（内核数据结构，不走协议栈）★ 最后防线
- **Q：网卡驱动挂了看什么？** → ethtool -S（网卡统计）/ dmesg（驱动日志）

---

## 常见网络问题决策树

```
连接超时：
  ping 通？→ 通：端口/防火墙；不通：路由/ARP
  ss 有 ESTAB？→ 有：应用层；无：网络层
  tcpdump 有 SYN-ACK？→ 有：应用没 accept；无：服务端问题

连接慢：
  ping RTT 高？→ 网络延迟
  TCP 重传多？→ 丢包（tcpdump 看 retransmission）
  零窗口？→ 接收方缓冲满（流控背压）

大量 TIME_WAIT：
  主动关闭太多？→ 客户端用长连接/服务端主动关
  端口耗尽？→ 调 ip_local_port_range/tcp_tw_reuse

大量 CLOSE_WAIT：
  应用没 close？→ 查代码（异常路径漏 close）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **tcpdump 过滤器** | host/port/net/tcpflags（BPF 语法） | 实验 1（常用过滤器） |
| **ss 排障** | 状态统计/监听端口+进程/TIME_WAIT-CLOSE_WAIT | 实验 2（实测） |
| **监控指标** | 带宽/延迟/丢包/重传/零窗口/TIME_WAIT/CLOSE_WAIT | 补充（指标表） |
| **工具失效逃生** | /proc/net（内核数据结构不走协议栈）→ethtool→perf→dmesg | 补充（决策树） |
| tcpdump 依赖 | AF_PACKET socket（走内核网络栈） | 补充（叙事） |
| **CLOSE_WAIT 排障** | 应用没 close（查代码异常路径） | 实验 2（实测） |
| BPF 语法 | host/port/net/and/or/tcpflags | 实验 1 |
| 连接问题决策树 | 超时/慢/TIME_WAIT/CLOSE_WAIT 分类排查 | 补充（决策树） |

---

## 补充 #127：重传/零窗口分析（tcpdump 实战）

### 重传分析

```bash
# tcpdump 看重传（同一 seq 重复出现）
tcpdump -nn 'tcp[tcpflags] & tcp-retransmission != 0' -i ens34

# 或用 Wireshark 分析（存文件）
tcpdump -i ens33 -w capture.pcap
# Wireshark: Analyze → Expert Info → Retransmissions

# 重传原因：
#  ① 网络丢包（丢包率高）
#  ② 接收方处理慢（缓冲满，RST 前的重传）
#  ③ 网络拥塞（RTT 增大→超时重传）
```

### 零窗口分析

```bash
# tcpdump 看零窗口（win=0）
tcpdump -nn 'tcp[tcpflags] & tcp-ack != 0 and tcp[7:4] = 0' -i ens33

# 或 Wireshark: Filter → tcp.window_size == 0
# 零窗口原因：
#  ① 接收方应用读取慢（缓冲满）
#  ② 接收方处理能力不足（背压）
```

### P9 追问链

- **Q：重传怎么分析？** → tcpdump 看同一 seq 重复 / Wireshark Expert Info
- **Q：零窗口说明什么？** → 接收方缓冲满（应用读取慢/处理能力不足）
- **Q：重传和零窗口什么关系？** → 零窗口导致发送方停→超时重传（连锁反应）

---

## 补充 #128：网络延迟实测（ping/mtr）

```bash
# ping 延迟
ping -c 10 baidu.com
# 看 min/avg/max/mdev（mdev=抖动）

# mtr（traceroute + ping 结合，看每跳延迟/丢包）
mtr -r -c 10 baidu.com
# 每跳：Loss%（丢包）/Last/Avg/Best/Wrst/StDev（延迟统计）

# 排障：
#  ping 通但慢 → mtr 看哪一跳延迟高（定位问题节点）
#  丢包 → mtr 看哪一跳丢包（定位丢包点）
```

### P9 追问链

- **Q：ping RTT 包含什么？** → 传播+排队+处理（往返）
- **Q：mtr 干什么？** → 逐跳 traceroute + 每跳延迟/丢包统计（定位问题节点）
- **Q：延迟抖动（mdev/StDev）说明什么？** → 网络不稳定（排队延迟波动）

---

## 补充考点速查（#127-#128）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **重传分析** | tcpdump 同一 seq 重复 / Wireshark Expert Info | #127（命令） |
| **零窗口分析** | win=0（接收方缓冲满，应用读取慢） | #127（命令） |
| ping/mtr | ping 看 RTT；mtr 逐跳延迟/丢包（定位问题节点） | #128（命令） |

## 相关文件

- ss 实测：连接状态统计/监听端口/CLOSE_WAIT
- tcpdump：BPF 过滤器语法
- 逃生通道：/proc/net/ethtool/perf/dmesg
