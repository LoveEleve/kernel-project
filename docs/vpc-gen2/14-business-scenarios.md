---
title: "14-业务全景：20 个场景 × 四条承诺（业务 ↔ 机制映射）"
series: "VPC 二代（Gen2）架构系列"
previous: "13-zk-custom-family.md"
next: "15-hard-problems-and-incidents.md"
source_refs:
  - "vpc/test-good/00-background/14-business-scenarios.md（1303 行全读：20 场景 API→DB→ZK→DPDK→包路径→排障）"
  - "vpc/test-good/00-background/01-vpc-basics.md（399 行全读：Overlay/GRE/DPDK/GWMAC/veth）"
  - "vpc/test-good/07-advanced/first-principles.md（289 行全读：表设计第一性原理）"
  - "vpc/test-good/00-background/02-distributed-infrastructure.md（代际口径：Gen2 = sdn_oss→Kafka→nfv-proxy→分区 ZK+CKV）"
  - "vpc/test-good/00-background/11-gateway-detail.md（167 行全读：VPCGW/PVGW——补遗 §5.5）"
  - "vpc/test-good/00-background/12-advanced-features.md、13-dns-quota.md（补遗 §5.6）"
---

# 14-业务全景：20 个场景 × 四条承诺（业务 ↔ 机制映射）

> 前 13 篇讲机制，本篇补业务侧：用户看到什么、被承诺什么、20 个典型场景在数据面上怎么走、每条承诺背后用哪些二代机制兜底。
> 标注：【实证】= 材料有据；【推导】= 按约束推导；【口径冲突】= 材料内自相矛盾（已裁决）。

## §0 它干了什么（例子先行）

一家游戏公司开新服，运维的一天（材料场景 1-5 的客户版串讲）：

```
09:00  创建 VPC（vpc-dl18w3l7，10.0.0.0/16）→ 建两个子网（10.0.0.0/24、10.0.1.0/24）
09:01  批量创建 100 台子机（10.0.0.x / 10.0.1.x）→ 被调度到不同母机
09:05  创建内网 CLB（VIP 10.0.0.100 → 100 台 RS）→ 用户开始访问
09:10  改安全组（放行 443）→ 期望"规则立即生效"
09:20  给网关机绑 EIP（106.52.208.59）→ 出公网
09:30  和办公网 VPC 建对等连接（Peering）→ 内网互访
次日   大版本更新失败 → 删 20 台子机 → 期望"销毁即停、IP 立即可回收"
```

运维全程只说了 6 句话，系统做了几千次下发。这 6 句话对应产品对用户的**四条承诺**：

| # | 承诺 | 用户话语 | 量化口径（材料） |
|---|---|---|---|
| 1 | 创建即用 | "刚买的机器马上能通" | API 返回 <100ms；数据面生效秒级（异步） |
| 2 | 规则即生效 | "改了安全组/路由马上生效" | 变更 → 母机 DPDK 秒级 |
| 3 | 销毁即停止 | "删了就别转发，IP 要能马上再分配" | 删除 → 流量停 + IP 回收 |
| 4 | 业务不中断 | "你们内部怎么折腾我不管，存量流量不能断" | 控制面故障不碰已下发数据面 |

**输入**：用户的 6 句话（API 调用）。**输出**：母机/网关上 DPDK 表的变化 + 一条可排障的证据链（DB → ZK/快照 → 设备侧表）。

四条承诺每句背后都是一套分布式机制 + 一类翻车方式——这是本篇与 15 篇的主线。

## §1 掌握卡（7 条）

1. **产品模型五件套**：VPC（隔离容器）→ 子网（IP 管理单元，绑 Zone）→ ENI/IP（地址）→ 路由表（策略，三表复用）→ 网关族（NAT/VPN/DC/Peering/CCN/HAVIP，路由表里用 vgwType 区分）。
2. **四条承诺 = 四个机制族**：创建即用=异步 binlog+幂等；规则即生效=路由展开；销毁即停止=删除保护+对账修复；业务不中断=主备+心跳+对账。
3. **数据面转发三张表**：route_table（IP 粒度 Hash，O(1)）→ subnet_route（子网粒度 LPM）→ vhost（出公网）；收包查 neigh_table（远端用 GWMAC 代理）。
4. **GRE 字段复用是标志性设计**：EIP 用 SEQ 传子机私有 IP；NATGW 用 CHECKSUM/SEQ 传 lanIp；VPN 用 CHECKSUM 传 VSR IP——GRE v0 字段不够，"借用"已有字段传上下文。
5. **GRE Key 三种规则**：同 VPC=源 VPC ID；同 Region Peering=**目标** VPC ID；NATGW/EIP=源 VPC ID。隔离靠 Key，转发靠 route_table。
6. **GWMAC = 无协调的确定性协议**：`fe:ee:{modular_inverse(subnet_ip)}`，每台母机独立计算同一值，不需要控制面分发。
7. **排障固定顺序**：控制面（DB）→ 下发（ZK/快照）→ 发送端母机 → Underlay → 接收端母机 → 子机。跳步=白干。

## §2 产品模型：用户对象 ↔ 控制面表 ↔ 下发物

【实证】综合 01/14/first-principles：

| 用户对象 | 控制面表 | 下发物（设备侧） | 设计理由（first-principles） |
|---|---|---|---|
| VPC | t_vpc | /TVPC/ 顶层容器 | 提供隔离边界——两个 VPC 可用相同 CIDR |
| 子网 | t_subnet（intSubnet+mask 支持范围查询） | /TVPC/RTABLE/{vpcId}/{subnet}/ | 绑 Zone 才能做同 Zone 低延迟调度 |
| VM/ENI/IP | t_vm / t_eni / t_eni_pip / t_ip（vpcId%250 分表） | /TVPC/VM/{vpcId}/{subnet}/{ip}/（HOSTIP/MAC/PEERIP/TSVIP） | IP 是数据面查表的键 |
| 路由表 | t_rtb + t_rtb_route + t_rule（三表；t_router 为 Neutron 遗留纯逻辑对象，不写 ZK） | /TVPC/RTABLE、/TVPC/RTB | 策略复用：N 子网共享 1 条策略；合并成一张表会"改 1 条漏更 N 条" |
| 转发展开 | t_rtable | DPDK route_table | 策略（1 条）与转发（N 台 VM）分离，异步展开 + 对账 |
| 安全组/ACL | t_security_group + t_security_group_rule / ACL | DFW 规则表（见 12） | SG=ENI 粒度有状态（CT 表）；ACL=子网粒度无状态（性能） |
| LB | t_service + t_route（+t_route_state） | 母机本地选 RS | 路由是 1:n 策略；LB 是 n:1 聚合（VIP→RS 池+权重+健康） |
| 网关族 | t_nat / t_vpngw(+t_vsr) / t_dc_gateway / t_vpc_peer / t_vbc_* / t_havip | 各自 ZK 路径 + vgwType | 路由表用 vgwType 区分下一跳类型，数据面按类型走不同网关 |

**没有外键是刻意的**：高频变更下外键检查开销 > 存储开销，一致性靠对账延迟校验【实证】。

**数据模型三关键**（vpc-core 补遗）：① **IP 绑 ENI 不绑 VM**——`VM ←t_instance_eni→ ENI ←t_ip.uniqInstanceId→ IP`；字段名历史遗留（实际可装 eni-/lb-/探测等多种持有者）——这是 EIP 能独立于子机存在的原因；② t_ip 分表 = `hash(vpcId)%250`（Py2 `hash(int)=int`；旧 VPC（vpcid<100 或 HK/GZOPEN 段）走单表）；③ t_ip.flag：0 空闲 / 1 已分配 / 2 已释放待回收（推测）；`keep_ip` 已注释——子网创建不预占 IP。

## §3 四条承诺 ↔ 二代机制（含翻车锚点）

【推导】（机制出处见 02/04/10；锚点见 15）：

| 承诺 | 二代机制 | 代价 | 翻车锚点（15 篇） |
|---|---|---|---|
| 创建即用 | AP 不阻塞：API 写 DB 即返回 → Kafka binlog → ZK → broker → agent；at-least-once + 幂等（transId/版本比对）兜重复；CVM 初始隔离→属性就绪后解隔离（链路完成判据，Gen1 链路口径） | 最终一致窗口；用户可能"刚建就连不上"（秒级） | ready 误判（#5/#17）、下发并发漏发（#6） |
| 规则即生效 | 策略（t_rtb_route）→ 异步展开为 VM 级 t_rtable → binlog 下发；分组（group）+ 快照兜底 | 展开放大：1 条策略 × 100 台 VM = 100 条转发 | 删除耗时放大阻塞新增（#9）、下发筛选错（#11） |
| 销毁即停止 | 删除走 binlog → 设备删表；对账修复（more/less 方向口径见 06 §6 第 8 条）；删除保护阈值防误删；12h 拒写（合并延迟假设——防旧 binlog 晚到重放）；DeleteVpc ≈115 步（最复杂 Saga） | 保护误伤（该删被拦）vs 误删（不该删删了）的两难 | 阈值保护误伤（#10）、HAVIP 误删 21min（#18）、对账修复中间态（#8） |
| 业务不中断 | 控制面与数据面解耦（DPDK 表已下发生效，控制面挂了不碰存量）；主备（ZK 同步器/LeaderLatch）+ 心跳 + ECHO + 四层对账 | 切换窗口（HAVIP ~14s）；回放不全即漏 | 双断（#2）、切主漏 VPC、回退自伤（#3） |

> 一句话总结：**四条承诺全靠"异步 + 兜底"实现**——正常路径追速度，对账路径追正确性；代价是把"不一致窗口"暴露给用户，把"检测能力"变成系统最关键的短板（15 §6）。

## §4 20 场景速查矩阵

【实证】材料《14-business-scenarios》最终总结表（原样收录）：

| # | 场景 | 数据面路径 | GRE Key | GRE 字段复用 |
|---|---|---|---|---|
| 1 | 同 VPC 互访 | 母机→母机 | 源 VPC ID | 无 |
| 2 | NAT 网关 SNAT | 母机→NATGW→TGW→公网 | 源 VPC ID | CHECKSUM=lanIp |
| 3 | PrivateLink | 母机→VPCGW→TGW→RS 母机 | 消费者→提供者 VPC | — |
| 4 | EIP 公网 | 母机→TGW | 源 VPC ID | SEQ=子机 IP |
| 5 | Peering | 母机→母机（直通） | **目标 VPC ID** | 无 |
| 6 | VPN | 母机→VPNGW→TGW→公网→TGW→VPNGW→母机 | 源 VPC ID | CHECKSUM=VSR IP |
| 7 | CCN | 母机→CCNGW→CCNGW→母机 | 源→目标 VPC ID | — |
| 8 | HAVIP | 母机（GRE 路由切换） | 源 VPC ID | 无 |
| 9 | 专线 | 母机→DCGW→专线 | 源 VPC ID | — |
| 10 | IPv6 | 和 IPv4 共用 GRE 隧道 | vpcId | — |
| 11 | 辅助 IP | 母机路由更新 | 源 VPC ID | 无 |
| 12 | 内网 LB | 母机本地选 RS（TCW hold 连接） | 源 VPC ID | 无 |
| 13 | 容器网络 | 同 VPC 互访（容器 CIDR 100.64.0.0/16） | 源 VPC ID | 无 |
| 14 | 公网 LB / LBNAT | TGW→LB→RS | 源 VPC ID | — |
| 15 | 内网 LB+EIP | TGW→LB GW→LB→RS | 源 VPC ID | — |
| 16 | PaaS 访问（CRS 等） | 母机→VPCGW/CRS GW（169.254.x VIP） | 源 VPC ID | — |
| 17 | ClassicLink | 母机（基础网+VPC 混合） | 源 VPC ID | — |
| 18 | 组播/广播 | IGMP snoop→GRE 单播复制 | 源 VPC ID | — |
| 19 | JNSGW | 母机→JNSGW→母机 | 源 VPC ID | — |
| 20 | 私网 NAT | 母机→NATGW→母机 | 源 VPC ID | CHECKSUM=lanIp |

**vgwType 对照（裁决版）**：0=Local/VNET、1=VPNGW、2=DCGW、4=Peering、8=NATGW（材料最终表，标注 MCP 验证）。CCN/HAVIP 的 vgwType 未验证——见 §9 口径冲突。

## §5 四个场景的包路径深读

### 5.1 同 VPC 互访（场景 1）——一切的基础

【实证】VM-A(10.0.0.2 @ 母机 11.26.140.168) ping VM-B(10.0.1.17 @ 母机 11.71.172.145)，VPC 5154392：

```
① ARP：VM-A 问"10.0.1.17 的 MAC"→ 母机 DPDK 查 neigh 未命中（远端）
   → 用子网 GWMAC 代理回复（fe:ee:modinv(subnet_ip)）→ VM-A 学到 GWMAC
② VM-A 发包（dst MAC=GWMAC）→ 母机剥 L2 → 查 route_table（Hash O(1)）
   → 命中 10.0.1.17 → MasterIP=11.71.172.145
③ GRE 封装：Outer(src=11.26.140.168, dst=11.71.172.145)
   GRE Key=5154392（源 VPC ID）；Inner IP 原样（src=10.0.0.2, dst=10.0.1.17）
④ 母机 B 解 GRE → 查 neigh_table → 10.0.1.17 真实 MAC → veth → VM-B
```

- 查找顺序（发包）：`global_route → route_table → subnet_route → vhost`；收包：`global_neigh → neigh → subnet_gw`【实证】。
- 关键参数：保留网段 169.254.128.0/17（监控机/VPNGW 等内部服务）；QoS GroupID=`vpcId<<32|int(mainIp)`（限速与统计）【实证】。
- 排障 6 步见 §6。

### 5.2 NAT 网关 SNAT（场景 2）——SNAT 在网关做

【实证】子机 10.0.0.3 出公网（NATGW 四 IP：EIP 120.232.20.20 / LAN 169.254.128.8 / GW 9.171.204.48 / TSVIP 9.27.84.136）：

```
① 客户端母机：subnet_route 命中 0.0.0.0/0 → GRE 到 NATGW GW IP（Inner 原样 10.0.0.3→8.8.8.8）
② NATGW：SNAT（10.0.0.3→EIP）+ conntrack → GRE 到 TGW（CHECKSUM 字段塞 lanIp）
③ TGW：按 lanIp 关联 conntrack → 公网；回包反向找 NATGW
```

- **GRE CHECKSUM=lanIp 是标志性 field reuse**：多个 NATGW 共存时，TGW 靠它知道回包发给哪个 NATGW。
- 边界：SNAT 每条映射一条 conntrack 记录——6000 万连接会 OOM【实证】。
- 最常被忽略的配置错误：子机安全组必须放行 **169.254.128.0/17**（SNAT 前看到的源是 NATGW LAN 段，不是公网 IP）【实证】。

### 5.3 PrivateLink（场景 3）——VPCGW 是 NAT 网关，不是 GRE 中转

【实证】消费者 VPC(4462357, 10.1.0.3) 访问提供者 VPC(5154392) 的 LB VIP(10.0.0.5)，经终端 VIP(10.1.0.2)：

```
① 消费者母机 → GRE → VPCGW（ECMP 多节点）
② VPCGW 双向 NAT：SNAT 10.1.0.3→11.163.4.2（SNAT 池 11.163.0.0/16，每 VPCGW 一个 /26）
                  DNAT 10.1.0.2→10.0.0.5（LB VIP）
③ TGW：LB 调度 DNAT 10.0.0.5→10.0.0.14（RS 真实 IP）→ GRE 到 RS 母机
④ 回包反向：TGW 反向 DNAT → VPCGW 反向 DNAT+SNAT → 消费者
```

- **为什么必须 SNAT**：消费者 IP 可能与提供者内部 IP 冲突；SNAT 后提供者只需放行 SNAT 池一次。
- 与 Peering 的本质区别：PrivateLink 必须改包（三层 NAT：VPCGW SNAT+DNAT，TGW DNAT）；材料 PrivateLink 节称 Peering 只做"解 GRE→重新 GRE"不改包——但与场景 5"同 Region 直通无 PVGW"存在口径张力，标【开放】（见 §9）。

### 5.4 同 Region Peering（场景 5）——直通，不是网关中转

【实证】VPC-A(5154392) ↔ VPC-B(4462357)：

```
母机 A 查 route_table：10.1.0.3 → MasterIP=30.121.205.206（母机 B 物理 IP，直通）
GRE Key = 4462357（目标 VPC ID！）—— 与同 VPC 通信的 Key 规则相反
回包 GRE Key = 5154392（对侧的目标 VPC ID）
```

- vgwType=4（≠3）；跨 Region 不支持——走 CCN（CCNGW 中转 + 多路由表 + PBR）【实证】。
- 修正记录：材料明确修正了"Peering 走 PVGW GRE 中转"的旧说法——同 Region 是母机直通【实证】。

### 5.5 网关族补遗：VPCGW 与 PVGW（查漏补缺）

【实证】test-good/00-background/11-gateway-detail.md：

**VPCGW（公网入口/出口，独立集群——不在母机上）**：

- ZK：`/TVPC/VPCGW/{vpcgwId}/{vip}` + `/TVPC/ZONEVPCGW/{vpcgwId}/{zone}/{vip}`；Agent = vpc_agent_dpdk_vpcgw。
- HA：**HARP 主备**——Master 端口 65357 / Backup 端口 65358，多路径健康探测切换；切主回放 4 类 binlog（漏 VpcBinlog 的 bug 见 15 §5.6）。
- 数据面：`add_wan(vpcId, vmIp, wanIp=EIP)`（SNAT/DNAT）+ `add_route(0.0.0.0/0 → VPCGW VIP)`（注：EIP 的 SNAT 位置口径冲突见 §9 第 6 条——场景 4 MCP 口径为母机直接 SNAT）。
- **Twins 模型（HA 的实现）**：两个对等模块（`vpc_agent` + `vpc_agent_bak`），无固定主备；每模块 1 wizard + 16 worker + 16 check + 16 watchdog + 1 monitor + 1 http；分片 `(vpcId%500)%15`，worker 15 特殊（白名单+HTTP+monitor）；**两层锁**——模块级 twins 锁（`/var/run/twins_vpc`，串行化冷启动——VPC 网关启动 2000+ 秒）+ 每 worker MS 锁（`/var/run/logic_{idx}_vpc_agent`，16 对按下标抢锁：赢家=下发权，输家 suicide、watchdog 重拉）；稳态时不同下标可归属不同模块（动态主备）。与 vpc_agent MS 的区别：MS 一个锁决定整棵树主备（角色由 ZK 指定）；Twins 角色全靠抢锁。

**PVGW（跨 VPC GRE 中转）**：

- vgwType=3；**不做 NAT**——解 GRE → 查路由 → 重新 GRE（源/目的 IP 不变）。
- gwIp（如 10.104.0.1）是**全 Region 共享 VIP**——只做控制面路由计算，不参与 GRE 封装（外层用物理母机 IP）。
- Agent 监听 `/TVPC/HOST/` + `/TVPC/WHITELIST/PVGWDPDK`。
- 存在理由（材料口径）：不同 VPC 的母机不能直接 GRE 互访（母机 B 收到 GRE key=源 vpcId，本机没有该 VPC 配置）→ PVGW 同时维护所有 VPC 路由做中转。
- 【开放】与场景 5 的关系：场景 5 说同 Region Peering 是母机直通（GRE Key=目标 VPC ID）——与"PVGW 中转"的并存关系未定（可能：直通=Peering 专用；PVGW=其他跨 VPC 形态或旧口径）。vgwType=3（PVGW）现有两处佐证（本篇 + 场景 2 速记卡）。

### 5.6 产品特性补遗：Flow Log / Traffic Mirror / DHCP IP / DNS / 配额 / 多 AZ（查漏补缺）

【实证】test-good/00-background/12-advanced-features.md、13-dns-quota.md；【代际注】材料原文的控制面路径用三代命名（binlog→Fusion→Broker），特性本体跨代一致：

| 特性 | 要点 |
|---|---|
| Flow Log | 五元组（+包数/字节）→ DPDK 统计 → 5 分钟聚合 → CLS；转发延迟影响 <1%（#21 sflow 丢失的影响列即"流日志缺失"——两者关系标【开放】） |
| Traffic Mirror | DPDK clone 一份 → GRE 到监控设备；原始包不受影响；目标须同 VPC 且不能绑安全组 |
| DHCP IP | 指定内网 IP 创建：校验（在 CIDR 内/非保留/flag=0）→ t_ip flag 0→1 |
| DNS | 内置 169.254.0.2（DHCP option 6）；可自定义覆盖（t_vpc_dns）；内网 FQDN 形如 `VM-xxx.internal.vpc-xxx...` |
| 配额 | VPC 20/租户、子网 100、路由表 10、路由条目 50、EIP 20、NATGW/VPNGW 10、SG 5/ENI、并发连接 200 万/VPCGW；检查在 API 事务内（SELECT … FOR UPDATE 防并发超配） |
| 多 AZ | 子网绑 AZ = 天然容灾单元；同 AZ <0.5ms（不 GRE）、跨 AZ GRE 1-2ms、跨 Region 10-30ms |

## §6 排障方法论（材料场景 1.5 的固定顺序）

【实证】六步不跳步：

```
1. 控制面：DB 查 t_vm/t_rtb_route —— 确认期望状态正确
2. 下发：查 ZK/快照 —— 确认期望状态已下发（二代：binlog/快照/设备侧表）
3. 发送端母机：/proc/net/tvpc/{vpcId}/route_table 查下一跳；dpdk-tcpdump 看 GRE 是否发出
4. Underlay：ping 对端母机物理 IP —— 物理网络可达？
5. 接收端母机：neigh 表 + 抓包确认收到 GRE
6. 子机：ip addr / iptables
```

判断口诀（推导）：**先确认控制面，再看下发，最后才怀疑数据面**——材料原话："先确认控制面正确——大概率问题是下发失败或 DPDK 表不对"【实证】。

## §7 规模数字：为什么"亿级"难

【实证】散见 11/first-principles/system-design：

- 量级：**200 万 VPC × 50 条配置 ≈ 1 亿条**；日均百万级变更；10 万+ 母机。
- 放大效应：1 条策略变更 → 子网内 N 台 VM 展开 N 条转发；百万变更 → 千万级 push。
- 冷数据：一台母机上 **85-94% 的 VPC 已无子机**，但 Gen1 仍为全部维护 Watch（10 万台 × 50 VPC × 50 路径 = 2.5 亿 Watcher，只有 5-15% 活跃）——Gen2 只推活跃 VPC 的原动力【实证，见 15 §2】。
- 批量操作：大客户退还 10000 IP → Gen1 产生 5000 万次推送 → ZK 20-50 秒排队【实证】。
- 实时性：ECHO 正常 <1s；配置变更 → DPDK 目标 p99 <5s（设计口径）【实证/设计目标】。

## §8 面试口径

**2 分钟业务版**（先业务后机制）：

> "VPC 产品对用户有四条承诺：创建即用、规则即生效、销毁即停止、业务不中断。实现上，控制面全部走异步——API 写 DB 即返回，变更经 Kafka binlog 下发到母机 DPDK，秒级生效；正确性靠幂等 + 版本 + 四层对账兜底。数据面是 DPDK + GRE overlay：同 VPC 直通，Peering 直通但 GRE Key 换成目标 VPC ID，NATGW 做 SNAT 且用 GRE CHECKSUM 传 lanIp，PrivateLink 在 VPCGW 做双向 NAT 解决 IP 冲突。排障按'控制面→下发→发送端→Underlay→接收端'六步走。"

**高频追问**（详见 01/12 的速记卡）：

| 追问 | 一句话答案 |
|---|---|
| 为什么 GRE 不用 VxLAN | GRE 头 4 字节开销小、2^32 租户够用；VxLAN 是 UDP+8 字节【实证】 |
| 两个 VPC 同 CIDR 为何不冲突 | GRE Key=VPC ID 隔离；像两栋楼都有 301 但楼不同 |
| EIP vs NATGW | EIP 独享、母机直接 SNAT（1 跳）；NATGW 共享、网关做 SNAT（2 跳） |
| Peering vs PrivateLink | Peering 直通不改包；PrivateLink 要隐藏消费者 IP → VPCGW 双向 NAT |
| HAVIP 切换多快 | 健康检查 9s 检测 + 路由切换，窗口 ~14s（纯路由切换，不碰 GRE） |

## §9 边界与口径冲突（挖掘时发现）

1. **vgwType 数值冲突**：材料内联表（场景 2/5 速记卡）出现"VPNGW(0)、PVGW(3)、CCN(4)"等说法，与最终对照表（0=Local/VNET、1=VPNGW、2=DCGW、4=Peering、8=NATGW，标注 MCP 验证）矛盾。**裁决：以最终表为准**；CCN/HAVIP 的 vgwType 未验证，标【开放】。查漏补缺（Round 17）：背景文档 05-routing（VPNGW=0）、09-hybrid-cloud（VPN=0）、08-cross-vpc（PVGW=3、CCN=4）均持旧/异口径——旧口径见证增至 5+ 处，裁决不变。
2. **本材料为"下发语义/数据面"口径**：ZK 路径（/TVPC/…）描述的是设备侧逻辑数据模型（跨代不变的数据面语义）；二代的实际传输形态（binlog→分区 ZK→broker→agent）见 02/04 篇。
3. 部分场景仅概要（13 容器/17 ClassicLink/18 组播/19 JNSGW/20 私网 NAT），无完整包路径——标【开放】。
4. 场景 8（HAVIP）与 11（辅助 IP）的切换窗口（~14s / <1s）来自材料单点，未见交叉验证【实证，单源】。
5. **Peering 路径口径张力**：PrivateLink 节说"Peering 的 PVGW 只做解 GRE→重新 GRE"；场景 5 明确"同 Region 母机直通、不经过任何中间网关"（并自我修正了旧说法）。查漏补缺新增：11-gateway-detail 给出 PVGW 完整机制（vgwType=3、GRE 中转、gwIp 全 Region 共享、/TVPC/WHITELIST/PVGWDPDK）——与场景 5 的并存关系未定（可能：直通=Peering 专用；PVGW=其他跨 VPC 形态或旧口径），标【开放】（见 §5.5）。
6. **EIP SNAT 位置口径冲突（Round 17 新增）**：场景 4（MCP 验证：vhost_table / GRE SEQ 证据链）称 EIP 直绑由**母机 DPDK 直接 SNAT** → TGW（1 跳）；背景文档 07-eip-nat 与 11-gateway-detail 称经 **VPCGW** 做 SNAT/DNAT。裁决倾向：以场景 4 为准；07/11 或描述"公网网关"形态/概念简化——标【开放】。
