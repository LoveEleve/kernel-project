# p9-net-domain10 · 容器网络+K8s（P9 深度实验）

> 网络轨道域 10/10（基础域收官）。覆盖：veth/bridge/NAT/CNI/Service/kube-proxy/负载均衡
> 对照 13 本书：深入理解Linux网络 Ch10、K8s网络全书、开源网络全栈

---

## 实验 1：Docker 网络（veth/bridge 实测）

```
网桥：docker0（默认）+ br-7f5481a29627（自定义网络）
veth 对：veth08c4148@if2 → br-7f5481a29627（容器接口挂网桥）
容器 IP：172.17.0.2/16，网关 172.17.0.1（docker0）
```

### Docker 网络模式

| 模式 | 网络栈 | 适用 |
|---|---|---|
| **bridge**（默认） | veth+bridge+NAT | 一般容器 |
| **host** | 共享宿主网络栈 | 高性能（无隔离） |
| **none** | 无网络 | 安全隔离 |
| **container** | 共享另一容器网络 | sidecar |
| overlay | 跨主机（VXLAN） | Docker Swarm |

### Docker bridge 模式网络路径

```
容器 eth0 (172.17.0.2)
  → veth 对（容器内 eth0 ↔ 宿主 vethxxx）
  → docker0 网桥（二层转发）
  → iptables MASQUERADE（SNAT：172.17.0.2 → 宿主 IP）
  → 物理网卡出网
```

### P9 追问链

- **Q：Docker bridge 怎么上网？** → veth→docker0→iptables MASQUERADE（SNAT 换源 IP）
- **Q：容器 IP 怎么分配？** → docker0 网段（172.17.0.0/16），DHCP 分配
- **Q：容器间怎么通信？** → 同一 bridge 二层转发（mac 学习）

---

## 实验 2：虚拟网络设备

```
veth pair：虚拟点对点链路（一端容器，一端宿主）
  → 容器 eth0 ↔ 宿主 vethxxx（成对出现）
bridge：虚拟交换机（二层转发，MAC 学习）
  → docker0/br-xxx（容器流量汇聚）
TUN/TAP：三层/二层虚拟设备（VPN 用）
macvlan/ipvlan：虚拟网卡（共享物理网卡 MAC/IP）
```

### P9 追问链

- **Q：veth pair 为什么成对？** → 一端容器内，一端宿主（虚拟链路两端）
- **Q：bridge 和 veth 区别？** → veth 是点对点链路；bridge 是虚拟交换机（多端口）
- **Q：Docker 用什么连容器？** → veth pair + bridge（容器 veth 挂到 docker0）

---

## 补充：K8s Service/kube-proxy（叙事）

### Service 四种类型

| 类型 | 作用 | 实现 |
|---|---|---|
| ClusterIP | 集群内 VIP | kube-proxy iptables/ipvs |
| NodePort | 节点端口暴露 | kube-proxy + 节点端口 |
| LoadBalancer | 云 LB | 云厂商 LB + NodePort |
| ExternalName | DNS 别名 | CoreDNS CNAME |

### kube-proxy 模式

```
iptables 模式（默认）：
  每个 Service 生成 iptables 规则（DNAT 到 Pod）
  → 规则多时性能下降（O(n) 查找）

ipvs 模式：
  IP 负载均衡（内核级，O(1) 查找）
  → 更多调度算法（rr/wlc/lc 等）
  → 大规模集群推荐
```

### P9 追问链

- **Q：Service 怎么实现？** → kube-proxy 生成 iptables/ipvs 规则（VIP→Pod DNAT）
- **Q：iptables vs ipvs？** → iptables O(n) 规则查找；ipvs O(1) 内核负载均衡（大规模用 ipvs）
- **Q：ClusterIP 是什么？** → 集群内虚拟 IP（kube-proxy 维护 VIP→Pod 映射）

---

## 补充：CNI 插件（叙事）

```
CNI（Container Network Interface）：容器网络标准接口
  运行时（Docker/containerd）调用 CNI 插件配置容器网络

主流 CNI：
  Flannel：VXLAN overlay（简单，跨主机）
  Calico：BGP 路由（纯路由，无 Overlay，性能好）
  Cilium：eBPF（高性能，可观测）
  Weave：mesh（加密）

对比：
  Flannel VXLAN：封装开销（+50B），简单
  Calico BGP：纯路由（无封装），需 BGP 支持
  Cilium eBPF：绕过 iptables（性能最好），需内核 4.18+
```

### P9 追问链

- **Q：Flannel 和 Calico 区别？** → Flannel=VXLAN overlay（简单）；Calico=BGP 纯路由（性能好）
- **Q：Cilium 优势？** → eBPF 绕过 iptables（性能+可观测），需内核 4.18+
- **Q：CNI 插件做什么？** → 给容器配 IP/路由/iptables（ADD/DEL 操作）

---

## 补充：负载均衡（L4/L7/一致性哈希）

### L4 vs L7 负载均衡

| | L4（四层） | L7（七层） |
|---|---|---|
| 层 | 传输层（IP+端口） | 应用层（HTTP/内容） |
| 转发 | 修改目的 IP:端口（DNAT） | 解析 HTTP 后转发（代理） |
| 性能 | 高（内核级） | 低（用户态代理） |
| 功能 | 端口转发/简单 LB | URL 路由/Cookie/SSL 卸载 |
| 代表 | LVS/IPVS/Nginx stream | Nginx/HAProxy/云 ALB |

### 一致性哈希（分布式定位）

```
问题：分布式缓存/存储，key→节点的稳定映射
一致性哈希：
  节点和 key 映射到环上 → 顺时针最近节点
  节点增删只影响相邻节点（最小重哈希）
  虚拟节点：每物理节点多个虚拟点（负载均衡）

应用：
  Redis Cluster（16384 slot）
  Memcached 一致性哈希
  负载均衡（会话保持）
```

### P9 追问链

- **Q：L4 vs L7 负载均衡？** → L4=传输层 DNAT（快）；L7=应用层代理（智能）
- **Q：一致性哈希解决什么？** → 分布式 key→节点稳定映射（节点增删最小影响）
- **Q：虚拟节点干什么？** → 平衡负载（每物理节点多个虚拟点，避免数据倾斜）

---

## 容器网络排障（衔接域9）

```
容器不通：
  ① 容器内 ping 网关（172.17.0.1）→ 通：出网问题；不通：veth/bridge
  ② 宿主 ip link show → veth 状态
  ③ iptables -t nat -L → MASQUERADE 规则
  ④ docker network inspect → 网段/网关

K8s Pod 不通：
  ① kubectl exec ping ServiceIP → 不通：kube-proxy/iptables
  ② kubectl get endpoints → 后端 Pod 就绪
  ③ ipvsadm -Ln / iptables -t nat -L → Service 规则
```

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **Docker bridge** | veth→docker0→MASQUERADE（SNAT）出网 | 实验 1（实测 veth/bridge） |
| **veth pair** | 虚拟点对点链路（容器 eth0 ↔ 宿主 veth） | 实验 1（ip link 实测） |
| **K8s Service** | kube-proxy iptables/ipvs（VIP→Pod DNAT） | 补充（叙事） |
| iptables vs ipvs | iptables O(n) 规则；ipvs O(1) 内核 LB（大规模用 ipvs） | 补充 |
| **CNI** | Flannel VXLAN/Calico BGP/Cilium eBPF | 补充（对比） |
| **L4 vs L7** | L4=传输层 DNAT（快）；L7=应用层代理（智能） | 补充（对比表） |
| 一致性哈希 | key→节点稳定映射；虚拟节点平衡负载 | 补充（叙事） |
| 容器网络排障 | ping 网关→veth→iptables MASQUERADE 逐层查 | 补充（排障） |

## 相关文件

- Docker 网络：ip link/ip addr 实测（veth/bridge/docker0）
- 工具：docker network ls/ip link show/iptables
