# p9-net-domain3 · TCP 流控+拥塞控制（P9 深度实验）

> 网络轨道域 3/10。覆盖：滑动窗口/慢启动/拥塞避免/Cubic vs BBR/TCP Fast Open

## 实验环境

```
默认算法: cubic（sysctl 实测）
可用算法: reno cubic
内核参数: tcp_window_scaling=1 / tcp_timestamps=1 / tcp_sack=1
```

---

## 实验 1：拥塞控制算法（sysctl 实测）

```
net.ipv4.tcp_congestion_control = cubic         ← 默认 Cubic
net.ipv4.tcp_available_congestion_control = reno cubic
net.ipv4.tcp_window_scaling = 1                 ← 窗口缩放开
net.ipv4.tcp_timestamps = 1                     ← 时间戳开（RTT 计算）
net.ipv4.tcp_sack = 1                           ← SACK 开
```

### 切换 BBR（需加载模块）

```bash
modprobe tcp_bbr
sysctl -w net.ipv4.tcp_congestion_control=bbr
```

### P9 追问链

- **Q：默认拥塞算法？** → Cubic（Linux 默认）；BBR 需手动开启
- **Q：Cubic vs BBR 区别？** → Cubic 基于丢包（丢包才降速）；BBR 基于带宽/RTT 测量（不等丢包）
- **Q：BBR 适合什么场景？** → 高延迟长肥管道（跨机房/无线）；数据中心内 Cubic 通常够用

---

## 实验 2：滑动窗口（流控）

### 机制

```
接收方窗口（Receive Window）：告诉发送方"我还能收多少"
  → 抓包字段：win=65483（窗口大小）
  → 窗口缩放：win × 2^wscale（wscale=10 → ×1024，可到 1GB）
发送方滑动窗口：未确认数据的上限（受接收窗口+拥塞窗口约束）
  → 发送上限 = min(接收窗口, 拥塞窗口)
```

### 零窗口（流控背压）

```
接收方缓冲满 → win=0 → 发送方停止发送
  → 接收方消费数据 → win 更新 → 发送方继续（窗口更新）
  → 抓包证据：win=0 后出现 win 更新包
```

### P9 追问链

- **Q：流控和拥塞控制区别？** → 流控=接收方能力（win）；拥塞控制=网络容量（cwnd）
- **Q：发送上限？** → min(接收窗口, 拥塞窗口)
- **Q：零窗口怎么恢复？** → 接收方消费后发窗口更新（win>0）

---

## 拥塞控制四阶段（P9 核心）

```
① 慢启动（Slow Start）：
   cwnd 从 1 MSS 开始，每 RTT 翻倍（指数增长）
   → 直到 ssthresh（慢启动阈值）或丢包

② 拥塞避免（Congestion Avoidance）：
   cwnd 达到 ssthresh 后，每 RTT +1 MSS（线性增长）
   → 网络接近容量

③ 快重传（Fast Retransmit）：
   收到 3 个重复 ACK → 立即重传（不等超时）
   → ssthresh = cwnd/2；cwnd = ssthresh + 3（快恢复）

④ 快恢复（Fast Recovery）：
   快重传后进入拥塞避免（不回慢启动）
   → 丢包不严重时（3 重复 ACK）用快恢复
   → 丢包严重（超时）回慢启动
```

## Cubic vs BBR（P9 对比题）

| | Cubic | BBR |
|---|---|---|
| 原理 | 基于丢包（丢包才降速） | 基于带宽/RTT 测量 |
| 拥塞信号 | 丢包 | 带宽下降/RTT 上升 |
| 适合 | 数据中心（低延迟低丢包） | 长肥管道（高延迟，丢包≠拥塞） |
| 缺点 | 高延迟场景反应慢 | 需要测量周期，突发流量初期不准 |
| Linux | 默认 | 需 modprobe tcp_bbr |

**P9 答法**："Cubic 基于丢包（丢包才降速），数据中心默认；BBR 基于带宽/RTT 测量（不等丢包），适合跨机房/无线长肥管道。BBR 不需要丢包信号就能保持高吞吐。"

## TCP Fast Open（TFO，了解级）

```
问题：每次连接都要三次握手（1 RTT 延迟）
TFO：首次连接获取 cookie → 后续 SYN 带数据（省 1 RTT）
  → sysctl: net.ipv4.tcp_fastopen=1（客户端）/3（客户端+服务端）
  → 适用：HTTP 短连接高频场景
```

## 补充 #102：BBR 状态机（P9 常问 "BBR 怎么工作"）

```
BBR 四状态：
① Startup（启动）：
   快速探测带宽（指数增长 cwnd）→ 找到瓶颈带宽
   → 切换条件：带宽不再增长

② Drain（排空）：
   把 Startup 积压的数据排空（降 cwnd 到 BDP）
   → 切换条件：排队数据排空

③ ProbeBW（探测带宽）：
   稳态：周期性探测带宽变化（8 个 RTT 循环）
   → 每个 RTT 换一个 pacing_gain（1.25/0.75/1.0...）
   → 找最大带宽

④ ProbeRTT（探测 RTT）：
   周期性降 cwnd 到 4 MSS，测最小 RTT（探测路径最小延迟）
   → 每 10 秒一次，持续 200ms
   → 更新 min_rtt（RTT 估计的基础）
```

**P9 答法**："BBR 四状态：Startup 快速探测带宽→Drain 排空排队→ProbeBW 稳态探测带宽→ProbeRTT 周期测最小 RTT。核心是持续测量带宽和 RTT，不依赖丢包信号。"

## 补充 #103：Cubic 算法细节

```
Cubic（CUBIC = Cubic function）：
  cwnd 变化用三次函数：C(t) = C_max - (C_max - cwnd) × (3 - 2×t/T)² × (1 - t/T)
  → C_max = 上次丢包时的 cwnd（Wmax）
  → t = 距上次丢包的时间
  → 特点：丢包后快速增长回 Wmax，接近时减速（凸函数）

与 Reno 区别：
  Reno 线性增长（每 RTT +1 MSS）
  Cubic 三次函数（更快恢复到 Wmax，更平滑）
```

**P9 答法**："Cubic 用三次函数控制 cwnd：丢包后快速回 Wmax（上次丢包的 cwnd），接近时减速。比 Reno 线性增长更快恢复高吞吐。数据中心默认。"

## 补充 #104：BDP（带宽时延积）

```
BDP = 带宽 × RTT（窗口大小的理想值）
  → 要跑满带宽，滑动窗口 ≥ BDP
  → 例：1Gbps 带宽 + 10ms RTT = 125KB/s × 0.01s = 1.25MB（窗口需 ≥1.25MB）

用途：
  ① 计算最优窗口/缓冲区大小
  ② 解释为什么长肥管道（高带宽+高延迟）需要大窗口（wscale 突破 64KB 限制）
  ③ BBR 的 BDP 就是这个概念（带宽×RTT）
```

**P9 答法**："BDP=带宽×RTT=跑满带宽所需窗口。1Gbps+10ms→1.25MB 窗口。长肥管道 BDP 大，需要窗口缩放（wscale）突破 64KB 限制。BBR 核心就是探测 BDP。"

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **默认拥塞算法** | Cubic（sysctl 实测 tcp_congestion_control=cubic） | 实验 1 |
| **流控 vs 拥塞控制** | 流控=接收方能力(win)；拥塞控制=网络容量(cwnd) | 实验 2（机制） |
| **发送上限** | min(接收窗口, 拥塞窗口) | 补充（叙事） |
| **拥塞控制四阶段** | 慢启动→拥塞避免→快重传→快恢复 | 补充（叙事） |
| **Cubic vs BBR** | Cubic 基于丢包；BBR 基于带宽/RTT 测量 | 补充（对比表） |
| 零窗口 | 接收方缓冲满 win=0→发送方停→窗口更新恢复 | 实验 2（机制） |
| 窗口缩放 | win×2^wscale（wscale=10→×1024） | 实验 1（wscale=10） |
| TCP Fast Open | 首次 cookie→后续 SYN 带数据（省 1 RTT） | 补充（叙事） |
| **BBR 状态机** | Startup→Drain→ProbeBW→ProbeRTT（持续测带宽/RTT） | 补充 #102 |
| **Cubic 算法** | 三次函数控制 cwnd，快速回 Wmax（比 Reno 快） | 补充 #103 |
| **BDP** | 带宽×RTT=跑满带宽所需窗口（长肥管道需大窗口） | 补充 #104 |

## 相关文件

- 内核参数：sysctl 实测（cubic/window_scaling/timestamps/sack）
- 抓包：`/data/workspace/arthas-output/tcp-{handshake,window}.log`
