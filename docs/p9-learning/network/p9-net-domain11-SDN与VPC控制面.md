# p9-net-domain11 · SDN+VPC 控制面（P9 集大成）

> 网络轨道域 11/11（收官）。**你的核心工作方向（VPC 控制面开发）。**
> 覆盖：SDN 架构/OpenFlow/VPC 网络模型/BGP EVPN/Neutron/安全组实现

---

## 一、SDN 架构（控制面/数据面分离）

### 核心思想

```
传统网络：控制面+数据面耦合在每台交换机/路由器
  → 配置分散、难以自动化、策略不一致

SDN（Software Defined Networking）：
  控制面（Controller）：集中决策（算路由/下发策略）
  数据面（Switch/Data Plane）：只转发（按流表执行）
  → 解耦、可编程、自动化、策略集中
```

### 架构分层

```
① 应用层：网络应用（负载均衡/防火墙/流量工程）
   ↓ 北向接口（REST API）
② 控制层：SDN Controller（OpenDaylight/ONOS/自研 VPC 控制面）
   ↓ 南向接口（OpenFlow/Netconf/gRPC）
③ 转发层：交换机/路由器（Open vSwitch/硬件交换机）
   按流表（flow table）匹配+执行 Action
```

### 控制面 vs 数据面（VPC 视角）

| | 控制面（你的工作） | 数据面 |
|---|---|---|
| 职责 | 算路由/下发规则/管理状态 | 按规则转发报文 |
| 组成 | Controller + API + 数据库 | 交换机/OVS/iptables/网卡 |
| 性能要求 | 低频（配置变更） | 高频（每包处理） |
| 实例 | VPC 控制面（子网/路由/安全组） | OVS 流表/iptables 规则 |

### P9 追问链

- **Q：SDN 核心是什么？** → 控制面/数据面分离（Controller 集中决策，转发层执行）
- **Q：南北向接口？** → 北向=应用↔Controller（REST API）；南向=Controller↔交换机（OpenFlow/Netconf）
- **Q：VPC 控制面做什么？** → 算路由/下发安全组/管理子网/NAT 配置（你的工作）

---

## 二、OpenFlow 协议（南向接口）

### 流表（Flow Table）

```
OpenFlow 交换机核心：
  流表项 = 匹配域(Match) + 优先级(Priority) + 计数器 + 动作(Action)

匹配域（Match）：
  入端口/in_port、目的 MAC/dst_mac、源 IP/src_ip、目的 IP/dst_ip
  TCP 端口/dst_port、VLAN、以太网类型 eth_type...

动作（Action）：
  forward（转发到端口）、drop（丢弃）、modify（改字段）
  send-to-controller（上送控制器）、group（组播）

匹配流程：按优先级从高到低匹配，首个命中执行 Action
```

### OpenFlow 交互（Controller ↔ Switch）

```
① Controller → Switch：Flow_Mod（下发流表项）
② Switch：数据包到来 → 匹配流表 → 执行 Action
③ 未匹配 → Packet-In（上送 Controller 决策）
④ Controller → Flow_Mod（下发新规则）
⑤ 统计：Flow-Stats（流表计数器）
```

### P9 追问链

- **Q：OpenFlow 流表项结构？** → Match+Priority+Counters+Actions
- **Q：数据包没匹配流表怎么办？** → Packet-In 上送 Controller，Controller 决策后 Flow_Mod 下发
- **Q：VPC 控制面和 OpenFlow 关系？** → VPC 控制面算出路由/策略 → 通过 OpenFlow/iptables 下发到数据面

---

## 三、VPC 网络模型（云网络核心）

### 核心组件

```
VPC（Virtual Private Cloud）：虚拟私有云（逻辑隔离的网络）
  ├─ 子网（Subnet）：VPC 内的 IP 网段（如 10.0.1.0/24）
  ├─ 路由表（Route Table）：子网的路由规则（目的→下一跳）
  ├─ 网关（Gateway）：
  │    ├─ 网关（VPC 网关，跨 VPC/公网）
  │    ├─ NAT 网关（VPC 内实例访问公网）
  │    └─ VPN 网关（连接本地数据中心）
  ├─ 安全组（Security Group）：虚拟防火墙（实例级，有状态）
  ├─ 网络 ACL（Network ACL）：子网级（无状态）
  ├─ EIP（Elastic IP）：公网静态 IP（绑到实例/网关）
  ├─ 对等连接（VPC Peering）：两 VPC 互通
  └─ 专线（Direct Connect）：物理专线连本地数据中心
```

### 数据路径（VPC 内通信）

```
实例 A (10.0.1.10) → 实例 B (10.0.1.20)：
  ① 安全组检查（源/目的/端口/协议）
  ② 路由表查找（同子网→本地转发）
  ③ VPC 转发（虚拟交换机）

实例 A (10.0.1.10) → 公网：
  ① 安全组检查
  ② 路由表（0.0.0.0/0 → NAT 网关）
  ③ NAT 网关（SNAT：VPC IP → EIP）
  ④ 出公网
```

### P9 追问链

- **Q：VPC 和子网关系？** → VPC 是大网段，子网是 VPC 内的分段（一个 VPC 多个子网）
- **Q：安全组 vs 网络 ACL？** → 安全组=实例级+有状态；ACL=子网级+无状态
- **Q：跨 VPC 通信？** → 对等连接（Peering）/VPC 网关/专线
- **Q：EIP 和公网 IP 区别？** → EIP 可动态绑定；公网 IP 随实例释放

---

## 四、BGP EVPN（VXLAN 控制平面）

### VXLAN 问题与 EVPN 解决

```
VXLAN：Overlay 网络（+50B 封装，跨物理网络）
  问题：MAC 学习靠广播泛洪（BUM：Broadcast/Unknown/Unicast）
  → 大规模网络广播风暴

BGP EVPN（Ethernet VPN）：
  控制平面：BGP 分发 MAC/IP 路由（替代广播学习）
  → 数据面首包：查 BGP 路由表 → 直接转发（无泛洪）
  → 支持 ARP 代理/多归属/移动性
```

### EVPN 路由类型

| Type | 作用 | 内容 |
|---|---|---|
| **Type 2** | MAC/IP 路由 | MAC 地址 + IP（ARP 表同步） |
| **Type 3** | Inclusive Multicast | 组播树（BUM 转发） |
| **Type 5** | IP Prefix | IP 前缀路由（跨子网路由） |

### VPC 中的 BGP EVPN

```
Spine-Leaf 架构：
  Leaf 交换机：VTEP（VXLAN 封装/解封装）
  Spine：骨干转发
  BGP EVPN：Leaf 间交换 MAC/IP 路由（控制平面）

VPC 控制面（你的工作）：
  云 VPC 用 BGP EVPN 做 Overlay 控制平面
  → 下发 Type2（实例 MAC/IP）/Type5（子网路由）
```

### P9 追问链

- **Q：VXLAN 为什么需要 BGP EVPN？** → VXLAN 靠广播学 MAC（风暴）；EVPN 用 BGP 分发路由（无泛洪）
- **Q：Type2/3/5 区别？** → Type2=MAC/IP；Type3=组播树；Type5=IP 前缀路由
- **Q：Leaf/Spine 架构？** → Leaf=接入（VTEP）；Spine=骨干（高带宽互联）

---

## 五、Neutron 架构（OpenStack 网络）

```
Neutron（OpenStack 网络服务）：
  ① Neutron Server：API 入口（REST API）
  ② ML2 插件：网络类型（VXLAN/GRE/VLAN）
  ③ L3 Agent：路由/NAT（网关实现）
  ④ DHCP Agent：IP 分配（给容器/VM）
  ⑤ Security Group Agent：安全组（iptables 下发）
  ⑥ OVS Agent：数据面（OVS 流表配置）

网络流程：
  创建 VPC → Neutron API → ML2 → OVS Agent（配流表）
  创建安全组 → Security Group Agent → iptables（下发规则）
```

**P9 答法**："Neutron=OpenStack 网络服务：Server(API)+ML2(网络类型)+L3 Agent(路由NAT)+DHCP Agent(IP分配)+OVS Agent(数据面)。你的 VPC 控制面类似 Neutron 架构。"

---

## 六、安全组实现（控制面→数据面下发）

### 实测（Docker iptables 安全组）

```
Chain DOCKER:
  ACCEPT  tcp  → 172.18.0.3:6379   ← 允许访问 Redis
  ACCEPT  tcp  → 172.18.0.2:6379   ← 允许访问 Redis
  DROP    all  → 0.0.0.0/0         ← 默认拒绝（有状态）

Chain DOCKER-ISOLATION:
  DROP  → 隔离不同 Docker 网络
```

### 安全组下发流程

```
① 控制面（你的工作）：
   API 收到安全组规则（允许 TCP 6379）
   → 算出数据面规则（iptables/OVS 流表）
   → 下发到节点（Agent 执行）

② 数据面执行：
   iptables -A DOCKER -p tcp --dport 6379 -j ACCEPT
   → 数据包到达 → 匹配规则 → ACCEPT/DROP

③ 有状态：安全组记录连接状态（ESTABLISHED 直接放行）
   回包不需要反向规则（状态跟踪）
```

### P9 追问链

- **Q：安全组怎么实现？** → 控制面算规则 → 下发 iptables/OVS 流表（数据面执行）
- **Q：安全组有状态？** → 是（出向建立连接后，回向 ESTABLISHED 自动放行）
- **Q：安全组和 ACL 区别？** → 安全组=实例级+有状态；ACL=子网级+无状态

---

## 域11 集大成（VPC 控制面全景）

```
你的 VPC 控制面工作涉及的全部知识点：
  域1：IP/路由/NAT/BGP/OSPF（基础）
  域2/3：TCP（连接/流控，健康检查/负载均衡）
  域10：veth/bridge/CNI（容器网络）
  域11：
    SDN 架构（控制面/数据面分离）★ 你的架构
    OpenFlow（南向接口，下发流表）
    VPC 模型（子网/路由/安全组/NAT）★ 你的业务
    BGP EVPN（VXLAN 控制平面）★ 你的 Overlay
    Neutron（OpenStack 参考架构）
    安全组（控制面→iptables/OVS 下发）
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **SDN 架构** | 控制面/数据面分离；北向 REST/南向 OpenFlow | 一（架构图） |
| **OpenFlow 流表** | Match+Priority+Actions；未匹配 Packet-In 上送 | 二（叙事） |
| **VPC 模型** | 子网/路由表/安全组/NAT/对等/专线/EIP | 三（数据路径） |
| **安全组 vs ACL** | 安全组=实例级+有状态；ACL=子网级+无状态 | 三/六 |
| **BGP EVPN** | VXLAN 控制平面（Type2 MAC/IP、Type5 IP 前缀） | 四（叙事） |
| **Neutron** | Server+ML2+L3 Agent+DHCP+OVS Agent | 五（叙事） |
| **安全组实现** | 控制面算规则→下发 iptables/OVS（实测 DOCKER 链） | 六（iptables 实测） |
| Leaf/Spine | Leaf=接入(VTEP)；Spine=骨干 | 四（叙事） |

---

## 补充 #129：OVS 架构（Open vSwitch）

### OVS 组件

```
OVS（Open vSwitch）：开源虚拟交换机
  ① ovs-vswitchd：核心守护进程（用户态，处理流表）
  ② ovsdb-server：数据库（存储流表/端口配置）
  ③ ovs-ofctl：OpenFlow 控制工具（下发流表）
  ④ ovs-vsctl：OVSDB 管理工具（配置网桥/端口）

数据路径：
  内核模块（openvswitch.ko）：快速路径（流表匹配+转发）
  用户态（ovs-vswitchd）：慢速路径（未匹配→用户态处理）
```

### OVS 与 OpenFlow 关系

```
OVS 支持多种南向协议：
  OpenFlow：流表下发（Controller 管理）
  OVSDB：配置数据库（网桥/端口）
  NetConf：配置管理

VPC 控制面（你的工作）：
  通过 OVSDB 配置网桥/端口
  通过 OpenFlow 下发流表（匹配/转发规则）
  或直接用 OVS API（如 libvirt 网络）
```

**P9 答法**："OVS=开源虚拟交换机：ovs-vswitchd(核心)+ovsdb(配置)+ovs-ofctl(OpenFlow)。支持 OpenFlow/OVSDB/NetConf 南向协议。VPC 控制面通过 OVS 配置网络/下发流表。"

---

## 补充 #130：VXLAN 封装细节

### VXLAN 包结构

```
原始包 + VXLAN 头（50 字节）：
  外层 UDP 头（8B）：源端口（哈希）+ 目的端口 4789
  VXLAN 头（8B）：VNI（24 位，支持 1600 万租户）+ 标志
  外层 IP 头（20B）：VTEP 源/目的 IP
  外层以太网头（14B）：VTEP MAC

VNI（VXLAN Network Identifier）：24 位，替代 VLAN 12 位（4094 限制）
```

### VTEP（VXLAN Tunnel End Point）

```
VTEP：VXLAN 封装/解封装点（Leaf 交换机/宿主机 OVS）
  发送：内层包 → VTEP 封装（+50B）→ 物理网络
  接收：物理网络 → VTEP 解封装 → 内层包 → 容器/VM
  每个 VTEP 一个 IP（VXLAN 隧道端点）
```

### P9 追问链

- **Q：VXLAN 头多大？** → 50 字节（UDP 8B + VXLAN 8B + 外层 IP 20B + 以太网 14B）
- **Q：VXLAN 端口？** → UDP 4789（IANA 分配）
- **Q：VNI 和 VLAN 区别？** → VNI 24 位（1600 万）；VLAN 12 位（4094）
- **Q：VTEP 是什么？** → VXLAN 封装/解封装点（Leaf 交换机/宿主 OVS）

---

## 补充 #131：Spine-Leaf 架构

### 架构

```
传统三层：核心→汇聚→接入（南北向流量优化）
Spine-Leaf：Leaf→Spine 全互联（东西向流量优化）

  Spine1 ←→ Spine2（骨干互联）
    ↑  ↑  ↑  ↑
  Leaf1 Leaf2 Leaf3 Leaf4（接入）
    ↑    ↑    ↑    ↑
  服务器 服务器 服务器 服务器

特点：
  所有 Leaf 互联所有 Spine（全互联，无跨 Leaf 转发）
  东西向流量：Leaf→Spine→Leaf（一跳）
  ECMP：多 Spine 负载均衡（等价多路径）
```

### Underlay + Overlay

```
Underlay：物理网络（Spine-Leaf + IGP/BGP 路由）
  → 提供基础 IP 连通性（VTEP 间可达）

Overlay：虚拟网络（VXLAN + BGP EVPN）
  → 提供租户隔离（VPC/子网/安全组）
  → 跑在 Underlay 之上（VTEP 隧道）

关系：Underlay 是"地基"（物理路由）；Overlay 是"房子"（虚拟网络）
```

### P9 追问链

- **Q：Spine-Leaf 为什么替代三层？** → 东西向流量优化（任意 Leaf 一跳到 Spine）；ECMP 负载均衡
- **Q：Underlay vs Overlay？** → Underlay=物理网络（IGP/BGP 路由）；Overlay=虚拟网络（VXLAN/EVPN）
- **Q：ECMP 在 Spine-Leaf 作用？** → 多 Spine 等价路径负载均衡（哈希分摊流量）

---

## 补充考点速查（#129-#131）

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **OVS 架构** | ovs-vswitchd(核心)+ovsdb(配置)+ovs-ofctl(OpenFlow) | #129（叙事） |
| **VXLAN 封装** | +50B（UDP 4789+VNI 24位+外层IP/以太网）；VTEP 封装解封装 | #130（结构） |
| **Spine-Leaf** | Leaf 接入+Spine 骨干全互联；ECMP 负载均衡；Underlay+Overlay | #131（架构图） |

## 相关文件

- iptables 实测：`DOCKER` 链（安全组数据面下发）
- 叙事：SDN/OpenFlow/VPC/EVPN/Neutron（无 OVS 环境，源码级/架构级）
