---
title: "12-数据面协议：vpcko / DPDK / DFW / GRE 字段复用"
series: "VPC 二代（Gen2）架构系列"
previous: "11-device-side-full.md"
next: "13-zk-custom-family.md"
source_refs:
  - "vpc/docs-by-topic/05-VPC 数据面与安全组深度架构.md（747 行全读）"
  - "vpc/test-good/00-background/14-business-scenarios.md（GRE 头字段复用完整表）"
  - "vpc/interview/vpc5-面试文档/题库-业务领域（VPC产品-网络路径-配置分发-运营）.md（GRE 复用口径）"
---

# 12-数据面协议：vpcko / DPDK / DFW / GRE 字段复用

> 前 11 篇讲"配置怎么下发"；本篇讲"配置下发后，数据包怎么走"——控制面与数据面的协议、表结构、封装编码。
> 边界：数据面**转发面本体**（内核模块、DPDK 转发进程）不在本工作区，本篇是文档级的协议与表结构梳理。
> 标注：【实证】= 材料有据；【推导】= 按约束推导。

## §0 它干了什么（例子先行）

**VM-A（10.0.1.9，母机 29.211.8.83）ping VM-B（10.0.1.71，母机 28.65.43.24）**——包在数据面的一生：

```
VM-A 发包 → vbr 网桥 → 内核/DPDK 拦截
→ 查路由表（GLOBAL → SUBNET → LOCAL；VHOST 兜底）
→ 查到 10.0.1.71 → 下一跳母机 28.65.43.24
→ GRE 封装（Key = 目的 VPC ID；Csum = remote_gwip）
→ 物理网络转发 → 母机 B 解封装（按 GRE flags 判来源）
→ 查 neigh_table → 构建 L2 帧 → 交付 VM-B
```

## §1 掌握卡（6 条）

1. **vpcko 是 8 个内核模块的集合**（vpc.ko 核心 + acl/qos/mirror/mc/dhcp/sflow/nf_ext_sync），每 VPC 一套内核数据结构（路由表/邻居表/VHOST 表 + 4 个版本号）。
2. **CT 版本机制是最易错的点**：路由/安全组变更必须推版本号，否则已有连接永远用旧规则——安全组变更必须补发 `DFW_VER_ADD`。
3. **双数据面**：内核 vpcko（generic netlink，慢路径/首包）+ DPDK（PAL，TCP 6666，快路径）；同一语义两个驱动（Go Facade ~250 方法统一）。
4. **GRE 字段复用**是标志性设计：GRE v0 字段有限，用 SEQ/CHECKSUM 传上下文（EIP/NATGW/VPN 四个场景）。
5. **DFW（安全组）独立体系**：Watcher-Builder 模型 → iptables 6 链 + ipset；同一规则生成两套（iptables + DPDK L4 二进制）。
6. **VPCGW 与母机的语义差异**：VPCGW 的 **LB 在 Route 之前**（先 DNAT 再路由）；母机没有 LB 步骤。

## §2 vpcko（内核数据面）

**8 个子模块**：vpc.ko（核心转发：netfilter hooks、GRE 隧道、LPM 路由）、tvpc_acl.ko、qos.ko、tvpc_mirror.ko、tvpc_mc.ko、tvpc_dhcp.ko、tvpc_sflow.ko、nf_ext_sync.ko（CT 状态同步）。

**每 VPC 数据结构**（`struct tvpc`）：route_table[8192]（13 位哈希 + RB-tree）、global_route（zone RB-tree）、route_learn（路由学习表）、neigh_table[64]（6 位哈希）、vhost_table[64]（VM→TGW tsvip 映射，用于 EIP/SNAT）、netagent（L4 负载均衡）；版本号：`global_route_version` / `dfw_version` / `vpc_version`（HBGW 学习）/ `bhole_rt_version`。

**CT 缓存版本机制**（最易错的点）：
```
路由变更 → global_route_version++ → 已有 CT 的 route_version 失配 → 失效重查
安全组变更 → 必须发 TVPC_CMD_DFW_VER_ADD → dfw_version++ → CT 失效 → 重新匹配
★ 漏发 DFW_VER_ADD = 已有连接永远不应用新规则（最常见下发 bug）
```

**出站流水线（7 子阶段）**：
```
VPC 定位（br_port → vpcid）→ CT 4 级回退（缓存/版本/强制/完整查）
→ route_iterate 钩子链：-200 connext(CT) / -100 route_lookup（GLOBAL LPM → SUBNET → LOCAL）
   / +200 default_lookup → vhost_lookup（EIP/SNAT 兜底）
→ remote_type 编码（0=VM2VM … 10=VM2CDC，共 11 种）
→ ECMP（4 种 hash_select：五元组/源目/仅目的/仅源）
→ neigh_iterate 三层（local/global/gw）→ GRE 封装（按 remote_type 编码）
→ MSS clamp（1460→1424）→ dev_queue_xmit
```

**入站三路径**：A. TGW GRE（按 GRE flags 判来源 → `tvpc_mangle_tgw_in` DNAT（VIP→真实 VM IP）→ extent_header 24B → netif_rx → `tvpc_tgw_pre_routing` 填 CT 扩展）；B. 同主机 VM→VM（vbr 直转，无隧道）；C. ARP（`tvpc_arp_pre_routing`：子网网关代答 + 免费 ARP 做 HAVIP 漂移检测）。

**netfilter 17 个 hook**（优先级示例）：brnf_drop(-1) → conntrack_in(0) → tgw_pre_routing(+5) → pre_routing(+10) → arp(+12) → dfw_in(FILTER-1) → dfw_out(FILTER+2) → forward(+15) → post_routing(+20)…

## §3 DPDK（用户态数据面）

**母机 TVS-DPDK（PAL 进程）**：
- 库组成：libvpcfp.so（快路径：包处理/路由/GRE/CT/DFW/QoS）、libvpcsp.so（慢路径：配置 CRUD/ACL/NetAgent）、libhooks.so、libsys_family.so。
- 线程模型：Worker×N（每 lcore 一个，独立 RX/TX 队列与 mempool，无锁并行）+ Control 线程（libev，TCP:6666 收配置）。
- **配置下发协议**：`ctrl_msg_prefix_t`(24B：magic 0x20161206 / family_id / msg_len / version / cmd_num) + `ctrl_cmd_hdr_t`(12B) + Protobuf；5 个 Family（文档列出 SYS(0)~13 命令 / VPCFP(1)~10+ / **VPCSP(2)~463 命令**——主配置接口）。
- 表结构：vpc_table[4096]（cache line 对齐）、iface 双索引（名称表 + IP 表各 8192）、subnet mask_zone[32]。
- **KUSC**：/dev/kusc → dpdknet.ko（共享内存 ring + ioctl + eventfd）——只做端口 device map/unmap 通知，**不用于批量配置**。

**VPCGW DPDK（网关）**：
- Pipeline：Receiver（rx_burst → l2_decap → ip_handler → DIP 查 ipgroup：TUNNEL→gre_decap 重入 / SPSC FIFO）→ Worker 优先级链：`conntrack_in(1000) → rule_in(2000) → rule_limit_match_orig(4500) → lb_handler(5000) → rule_limit_match_reply(5500) → conntrack_confirm(1000000) → rule_out(1500000) → route_packet(2000000)`。
- **LB 先于 Route**（核心差异）：先 DNAT（VIP→RS IP）再路由——母机没有 LB 步骤。
- **CT 会话同步**（跨集群）：6667 UDP 组播（默认）/ 6668 UDP 单播 / 4567 TCP 全量（冷启动预热）；触发（新会话延迟 3s / 状态变更立即 / 保活 ~600s / 过期删除；短连接 <3s 不同步）；Master/Slave 按"收到业务报文 vs 收到同步消息"自动定角；`last_update` 时间戳解决多层重传乱序。
- **TCP 11 状态机**（sNO→sSS→sSR→sES→sFW→sCW→sLA→sTW→sCL，三维跳表）；**TOA**（TCP Option 200/201/202 注入真实客户端 IP，MSS 降 1400；插入失败三策略：覆盖/跳过/丢包）；**STLS**（无 conntrack 的请求-响应式 LB 路径，查询间隔 3s/1s）。

## §4 DFW Agent（安全组）

- **模型**：独立于 vpc_agent 的 C++ 进程；ZK 变化 → Watcher（内存模型）→ Builder（规则字符串）→ 数据面（iptables/ipset/ebtables + HostDpdk L4FW + TVPC netlink）。
- **关键 Watcher**：HostWatcher（/DFW/HOST）、CVMWatcher、CVMVifWatcher、VifWatcher、SGWatcher、USGWatcher、ACLGroupWatcher、L2Watcher（ebtables）、SVCWatcher（VPCGW）。
- **iptables 6 链**（每 VIF）：`CVMI/CVMO`（CVM 级白名单/隔离）→ `SGI/SGO`（SG 级 ipset 匹配）→ `USGI/USGO`（用户规则，末尾 -j DROP）。
- **ipset 体系**：IST_LAN（全局 LAN）/ IST_{chain}_LAN / IST_{chain}_SG（list:set）/ IST_{chain}_VPC_LINK / IST_sg-xxxx（USG 引用的地址集）。
- **转换**：USG JSON（dir/proto/ip/port/action）→ USGBuilder → iptables-restore 原子批量；**DPDK 双路径**：IptConvertor 三阶段（PHASE_VM 0 / PHASE_APP 2000 / PHASE_CONST 4000，各 2000 条上限）→ l4classifier 二进制 → `HostDpdk.set_vif_stateless`；`is_dpdk_ready` 标记同步就绪。
- **conntrack 特殊处理**：VPC 子机仅放行 REPLY 方向的 ESTABLISHED；ORIGINAL 方向必须带 mark bit 3(0x4)；安全组变更清除该 mark → 已有连接强制重过新规则。
- **CVM 隔离**：ebtables 断 ARP + `tvpc_add_vm(..., TVPC_VM_BAD=1)` 内核标记。
- **AddrSet 引用计数**：USG 引用其他安全组（sg-xxxx）→ AddrSetWatcher + obtain/release，引用归零自动销毁。

## §5 GRE 字段复用（标志性设计）

**编码对照表**（封装侧）：

| 场景 | GRE Key | GRE Seq | GRE Csum | Flags |
|---|---|---|---|---|
| VM→VM（同 VPC 直连） | 目的 vpcid | - | remote_gwip | GRE_KEY |
| VM→TGW（EIP 出公网） | remote.vpcid | vmip | orig_tsvip | KEY+SEQ |
| VM→VPCGW(RS) | self_vpcid | rsip | rsport + 0x0010 | KEY+SEQ+CSUM |
| VM→NATGW | - | - | 0x0020 | NATGW_FLAGS |

**字段复用完整表**（业务场景）：

| 场景 | 复用字段 | 传什么 | 谁用 |
|---|---|---|---|
| EIP 出公网 | SEQ | 子机私有 IP | TGW 回包（知道还给哪个子机） |
| NATGW SNAT | CHECKSUM | lanIp（169.254.128.x） | NATGW↔TGW（多 NATGW 共存） |
| NATGW DNAT | SEQ | lanIp | TGW→NATGW（反向） |
| VPN | CHECKSUM | VSR IP | 母机→VPNGW 母机（多 VSR 共存） |

- **为什么要复用**【实证】：GRE v0 字段就这些，不能加新字段——复用是唯一选择。
- **入站判来源**（按 flags）：Key only → VM→VM；Key+SEQ → TGW；Key+SEQ+CSUM → PVGW/VPN。
- **母机 vs VPCGW**：母机按 `global_route.type` 决定编码路径；VPCGW 进隧道后只有 GRE Key 的 VPC ID 被使用（type 丢失，route_packet 自己决策）。

## §6 Go Facade 驱动抽象（控制面视角）

- `HostAdapter` 接口 ~250 个方法统一内核/DPDK：三驱动 `DriverTypeVpcko`（Generic Netlink，family="TVPC"）/ `DriverTypeDpdk`（Protobuf over Unix socket /var/run/vhost_cmd）/ `DriverTypeFake`（测试）。
- Facade 分发：指定 driver 走单驱动，否则广播所有驱动。
- **驱动差异**（注释级）：`AssociateSubENIs`/`SetFtupleAclRule`/`DhcpIp` → DPDK only；同一语义不同命令号：`CtScan` = KO cmd 225 / DPDK cmd 175；`GetRTables` 对账读：KO 从 /proc，DPDK 走 RPC。

## §7 边界与钩子

- 数据面转发面本体（内核模块源码、DPDK 转发进程）不在工作区；本篇为文档级协议梳理。
- 设备侧如何调用这些通道 → **09 §4 / 11 §6**；GRE 场景与业务路径全景 → `00-background/14-business-scenarios`。
