# p9-net-domain2 · TCP 连接管理（P9 深度实验）

> 网络轨道域 2/10。覆盖：三次握手/四次挥手/状态机/TIME_WAIT/CLOSE_WAIT/SYN cookies

## 实验环境

```
本机: 192.168.13.128（loopback + ens33）
工具: tcpdump(sudo)/ss/sysctl
```

---

## 实验 1：三次握手（tcpdump 抓包）

### 实测（ssh 自连 lo）

```
① SYN:      127.0.0.1.35224 → .22  Flags [S],  seq=1819519965        ← 客户端 SYN
② SYN-ACK:  127.0.0.1.22 → .35224  Flags [S.], seq=2260269915, ack=1819519966  ← 服务端 SYN+ACK
③ ACK:      127.0.0.1.35224 → .22  Flags [.],  ack=1                  ← 客户端 ACK
④ 数据:     Flags [P.], SSH-2.0-OpenSSH...                            ← 握手完成，传数据
```

### 握手机制

```
① SYN：客户端 → 服务端（seq=x，SYN=1）
   客户端进入 SYN_SENT
② SYN-ACK：服务端 → 客户端（seq=y，ack=x+1，SYN=1，ACK=1）
   服务端进入 SYN_RECV（半连接队列）
③ ACK：客户端 → 服务端（ack=y+1）
   双方进入 ESTABLISHED（全连接队列→accept）
```

### P9 追问链

- **Q：为什么三次握手不是两次？** → 阻止历史连接（旧 SYN 的 seq 过期）；双方确认序列号
- **Q：SYN-ACK 的 ack 是什么？** → 客户端 seq+1（确认收到 SYN）
- **Q：半连接/全连接队列？** → SYN_RECV 在半连接队列；ESTABLISHED 在全连接队列（accept 取走）

---

## 实验 2：四次挥手（tcpdump 抓包）

### 实测（python 一次性服务）

```
① 客户端 FIN: Flags [F.], seq=6, ack=1     ← 客户端主动关（FIN）
② 服务端 FIN: Flags [F.], seq=1, ack=7     ← 服务端关（FIN+ACK 合并）
③ 客户端 ACK: Flags [.], ack=2             ← 最后 ACK
（实际 3 次：FIN+ACK 常合并；完整四次 FIN→ACK→FIN→ACK）
```

### 挥手机制

```
① FIN：主动关闭方 → FIN=1（我没数据了）
   主动方进入 FIN_WAIT_1
② ACK：被动方 → ACK=1（收到你的 FIN）
   被动方进入 CLOSE_WAIT；主动方进入 FIN_WAIT_2
③ FIN：被动方 → FIN=1（我也没数据了）
   被动方进入 LAST_ACK
④ ACK：主动方 → ACK=1
   主动方进入 TIME_WAIT（等 2MSL 后关闭）；被动方进入 CLOSED
```

### TIME_WAIT 为什么等 2MSL

```
MSL（Maximum Segment Lifetime）：报文最大生存时间（Linux 30s）
TIME_WAIT 等 2MSL：
  ① 确保最后 ACK 到达（丢了→被动方重发 FIN→主动方能再 ACK）
  ② 让旧连接报文消散（避免与新连接混淆）
```

### P9 追问链

- **Q：为什么有 TIME_WAIT？** → 确保最后 ACK 到达 + 旧报文消散（2MSL）
- **Q：TIME_WAIT 多了怎么办？** → 调 tcp_tw_reuse/tcp_tw_recycle（已废弃）/增大端口范围
- **Q：四次为什么可能变三次？** → 被动方 FIN+ACK 合并（无数据要发时）

---

## 实验 3：TCP 状态机（ss 实测）

### 实测状态分布

```
LISTEN     17    ← 监听中
ESTAB      10    ← 已建立
TIME-WAIT   1    ← 主动关闭后等 2MSL
（SYN_SENT/SYN_RECV/CLOSE_WAIT/FIN_WAIT_1/FIN_WAIT_2/LAST_ACK 未出现）
```

### 11 种状态全景

```
 LISTEN          → listen() 后等待连接
 SYN_SENT        → 发出 SYN（客户端）
 SYN_RECV        → 收到 SYN 发出 SYN-ACK（服务端，半连接）
 ESTABLISHED     → 连接建立（数据传输）
 FIN_WAIT_1      → 主动关发 FIN 后
 FIN_WAIT_2      → 收到 ACK 后等对方 FIN
 CLOSE_WAIT      → 收到 FIN 后（等应用 close）★ 排障重点
 LAST_ACK        → 被动关发 FIN 后
 TIME_WAIT       → 主动关最后 ACK 后（等 2MSL）
 CLOSING         → 双方同时关
 CLOSED          → 关闭完成
```

---

## 实验 4：TIME_WAIT vs CLOSE_WAIT（排障）

### TIME_WAIT（主动关闭方）

```
场景：本机主动关闭连接（如 HTTP 请求完成客户端关）
  → 产生 TIME_WAIT（等 2MSL）
  → 大量 TIME_WAIT：端口耗尽风险（ip_local_port_range 默认 32768-60999 ≈ 2.8 万）
  → 调优：tcp_tw_reuse=1（复用 TIME_WAIT 端口）
```

### CLOSE_WAIT（被动关闭方）★ 排障重点

```
场景：对方发 FIN，但本机应用没调 close()
  → CLOSE_WAIT 堆积（应用 bug：忘记 close）
  → 排查：ss -tan | grep CLOSE-WAIT → 看进程 → 查代码 close
  → 根因：应用没 close（如异常路径漏 close）
```

### P9 追问链

- **Q：TIME_WAIT 多了怎么排查？** → ss -tan | grep TIME-WAIT 统计；看是否主动关太多；调 tcp_tw_reuse
- **Q：CLOSE_WAIT 多了说明什么？** → 应用没调 close（对方已关，本机还在等）→ 查代码
- **Q：FIN_WAIT_2 多了？** → 对方关了但本机应用没关（类似 CLOSE_WAIT，主动方视角）

---

## 实验 5：SYN cookies + 内核参数（实测）

### 内核参数实测

```
net.ipv4.tcp_syncookies = 1        ← SYN cookie 开启（防 SYN flood）
net.core.somaxconn = 4096          ← listen backlog 上限
net.ipv4.tcp_max_syn_backlog = 1024 ← SYN 半连接队列上限
net.ipv4.ip_local_port_range = 32768 60999 ← 本地端口范围（~2.8 万）
net.ipv4.tcp_fin_timeout = 60      ← FIN_WAIT_2 超时（秒）
net.ipv4.tcp_tw_reuse = 2          ← TIME_WAIT 复用（2=仅出向连接）
```

### SYN cookies 机制

```
SYN flood：攻击者发大量 SYN（伪造源 IP）→ 半连接队列满 → 正常连接失败
SYN cookie：
  ① 收到 SYN → 不分配资源，用 cookie 编码 seq（时间戳+MSS 等）
  ② SYN-ACK → 带 cookie（不存半连接）
  ③ 收到 ACK → 验证 cookie → 合法才分配资源建立连接
  → 服务端无状态（不存半连接），防 flood
```

### P9 追问链

- **Q：SYN flood 怎么防？** → SYN cookies（tcp_syncookies=1，默认开）；加大 tcp_max_syn_backlog
- **Q：TIME_WAIT 太多端口不够？** → tcp_tw_reuse=1 / 增大 ip_local_port_range / 客户端用长连接
- **Q：somaxconn 作用？** → listen backlog 上限（与 listen(backlog) 取 min）

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 实验背书 |
|---|---|---|
| **三次握手** | SYN→SYN-ACK→ACK（防历史连接，双方确认序列号） | 实验 1（tcpdump 抓包） |
| **四次挥手** | FIN→ACK→FIN→ACK（FIN+ACK 可合并成三次） | 实验 2（抓包） |
| **TIME_WAIT** | 主动关闭方等 2MSL（确保 ACK 到达+旧报文消散） | 实验 4 |
| **CLOSE_WAIT** | 被动关闭方没 close（应用 bug，★排障重点） | 实验 4 |
| **SYN cookies** | 无状态 cookie 防 SYN flood（tcp_syncookies=1 默认开） | 实验 5（实测参数） |
| 11 种状态 | LISTEN/ESTAB/TIME_WAIT/CLOSE_WAIT 等全景 | 实验 3（ss） |
| 内核参数 | somaxconn/syn_backlog/port_range/fin_timeout/tw_reuse | 实验 5（sysctl） |
| 半连接/全连接队列 | SYN_RECV 半连接；ESTABLISHED 全连接（accept 取走） | 补充（叙事） |
| **TCP 头部格式** | Seq/Ack/窗口/标志位/选项（抓包实测字段） | 补充 #96 |
| **序列号/确认号** | Ack=对方Seq+已收数据（累计确认；SYN/FIN 占序号） | 补充 #97（抓包 ack=seq+1） |
| 可靠传输基础 | 超时重传/快速重传（3 个重复 ACK） | 补充 #98 |
| RST 复位 | 连接异常关闭（拒绝/崩溃/未监听），无挥手 | 补充 #99 |
| **TCP 四元组** | 源IP+源端口+目的IP+目的端口（连接唯一标识） | 补充 #100（port_range 实测） |
| MSS/SACK/wscale | MSS=最大报文段；SACK 选择性确认；wscale 窗口缩放 | 补充 #101（抓包 options） |

---

## 补充 #96：TCP 头部格式（关键字段）

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
├───────────────────────────────────────────────────────────────┤
│          源端口(16)             │        目的端口(16)          │
├───────────────────────────────────────────────────────────────┤
│                    序列号 Seq(32)                              │
├───────────────────────────────────────────────────────────────┤
│                 确认号 Ack(32)                                │
├──────┬─────┬───────────────────┬──────────────────────────────┤
│数据偏移│保留 │C│E│U│A│P|R│S│F  │          窗口 Window(16)     │
│ (4)  │(3) │W│C│R│C│S│S│Y│I│  │                              │
│      │    │R│E│G│K│H│T│N│N│  │                              │
├──────┴─────┴───────────────────┼──────────────────────────────┤
│       校验和(16)                │       紧急指针(16)           │
├──────────────────────────────────────────────────────────────┤
│                 选项（可选，长度可变） + 填充                 │
└──────────────────────────────────────────────────────────────┘
抓包实测字段：seq=序列号 / ack=确认号 / win=窗口 / Flags=[S.]=SYN+ACK
```

## 补充 #97：序列号/确认号机制

```
序列号（Seq）：本报文数据第一个字节的序号（保证有序）
确认号（Ack）：期望收到的下一个字节序号（累计确认）
  → 抓包实测：SYN seq=1819519965 → SYN-ACK ack=1819519966（=seq+1）
  → 数据段：seq=1:44 表示发了 43 字节；ack=44 表示收到 43 字节

规则：
  SYN/FIN 各占一个序号（所以握手挥手 seq+1）
  Ack = 对方 Seq + 已收到数据长度（累计确认）
```

## 补充 #98：可靠传输基础（衔接域3）

```
超时重传：发送后启动定时器，超时未收到 ACK → 重传
快速重传：收到 3 个重复 ACK → 立即重传（不等超时）
  → 抓包证据：重复 ack=N 说明丢了 N 之后的包
域3 详讲：拥塞控制（慢启动/拥塞避免/快恢复）决定重传后的窗口调整
```

## 补充 #99：RST 复位

```
RST=1：连接异常关闭（拒绝连接/进程崩溃/端口未监听）
  场景：连未监听端口→RST；进程 crash→RST；iptables REJECT→RST
  与 FIN 区别：FIN=优雅关闭（四次挥手）；RST=立即关闭（无挥手）
抓包：Flags [R] = RST 包
```

## 补充 #100：TCP 四元组（连接唯一标识）

```
四元组：源IP + 源端口 + 目的IP + 目的端口
  → 唯一标识一个 TCP 连接
  → 服务端并发上限：受 ip_local_port_range（客户端侧）+ 端口数（服务端侧）
  → 一台机器最多连接数：受限于四元组组合数 + 文件描述符 + 内存
本机实测：ip_local_port_range=32768-60999（~2.8 万出向端口）
```

## 补充 #101：MSS/窗口缩放/SACK 选项（抓包实测）

### 抓包证据（TCP 握手 options）

```
options [mss 65495, sackOK, TS val ..., nop, wscale 10]
```

### 各选项含义

| 选项 | 含义 | 实测值 |
|---|---|---|
| **MSS** | 最大报文段长度（不含 TCP 头，=MTU-40） | 65495（loopback）/1460（以太网） |
| **SACK** | 选择性确认（只重传丢失段，非全部） | sackOK=支持 |
| **TS** | 时间戳（RTT 计算+防序号回绕） | val/ecr |
| **wscale** | 窗口缩放（窗口字段 ×2^wscale） | 10（×1024，突破 64KB 窗口限制） |

### P9 追问链

- **Q：MSS 是什么？** → TCP 一次传的最大数据（不含头）；以太网 MSS=1460（1500-20-20）
- **Q：SACK 解决什么？** → 只重传真正丢失的段（避免全部重传）
- **Q：窗口缩放为什么需要？** → 原始窗口字段 16 位最多 64KB；×2^wscale 可到 1GB（高带宽场景）

## 相关文件

- 抓包日志：`/data/workspace/arthas-output/{tcp-handshake,tcp-fin}.log`
- 工具：tcpdump(sudo)/ss/sysctl
