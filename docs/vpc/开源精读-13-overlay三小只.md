# 开源精读 13 · overlay 三小只：控制面、穿透与加密——"overlay 网络的全身"

> 课次：第二批第 4 课（课程里最后一块没碰的维度：**P2P overlay 的全身**）
> 对象：`refs/ZeroTierOne`（commit `899352e`）、`refs/tailscale`（`b83f28e`）、`refs/netmaker`（`e07ffab`）
> 对照：我们（IaaS overlay：GRE/VXLAN、网关终结、**无加密、无 P2P**）——同一坐标系里的不同象限
> 纪律：行号实仓核验；三仓各取"全身五件套"，不漫游。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **VL1 / VL2** | ZeroTier 的两层：VL1=加密寻址的 P2P 网络层；VL2=以太网仿真（"similar to VXLAN"） |
| **coordination（协调）** | overlay 的控制面：把"谁是谁、什么权限"发给节点（Tailscale 的 NetworkMap / Netmaker 的 WG 配置） |
| **NetworkMap** | Tailscale 控制面下发的"网络地图"：成员、公钥、DERP 归属——数据面的期望态 |
| **magicsock** | Tailscale 的"会换路的 socket"：主动寻找最佳通信路径（打洞引擎） |
| **disco** | Tailscale 的发现/打洞信令协议（自定义报文，nacl 加密） |
| **DERP** | Tailscale 的中继："direct path 打不开时的最后一招" |
| **WireGuard / Noise** | 现代 overlay 的加密底座——**共同立场是"加密默认"，底座各不同**：ZT 自有 ECC/AES；Tailscale/Netmaker 用 WireGuard（disco 信令另用 nacl） |

## ① 它干了什么——"加入一个网络"的三个版本

- **ZeroTier**：`zerotier-cli join <nwid>` → VL1 上找到网络控制器 → 控制器下发网络定义 + **成员证书** → 你在 VL2 上获得"以太网身份" → 与成员**直连（打洞）或经根中继**。
- **Tailscale**：`tailscale up` 登录 → 控制面返回 **NetworkMap**（成员/公钥/DERP 归属）→ **magicsock 主动打洞**，打不开走 **DERP** 中继 → 全程 WireGuard 加密。
- **Netmaker**：服务端生成 **WireGuard 配置**下发 → 客户端（netclient）应用 → **MQ 信令**辅助打洞。

**一句话**：**身份=公钥、控制面发地图、数据面自己打洞、加密端到端**——和我们"GRE key + ZK 树分发 + 网关终结 + 不加密"是同一坐标系里的**不同象限**。

## ② 链路走查（file:line 级）

### 跳 1：ZeroTier——两层网络 + 证书制成员

`README.md:18`（原文）：

> This is accomplished by combining a cryptographically addressed and secure peer-to-peer network (termed **VL1**) with an Ethernet emulation layer somewhat similar to VXLAN (termed **VL2**). Our VL2 Ethernet virtualization layer includes advanced enterprise SDN features like fine grained access control rules for network micro-segmentation…

`doc/zerotier-cli.1.md:33` 佐证 VL1 语义："lists the ZeroTier **VL1 (virtual layer 1, the peer to peer network)** peers…"。核心实现是 **C++**（`node/` 目录：`ECC.cpp`/`AES.cpp`——加密原语自持；`Bond.cpp`——多路径绑定；**`CertificateOfMembership.cpp`**——"成员证书"正是"证书制访问控制"的落地；`Capability.cpp`——能力授权）。服务入口 `service/OneService.cpp`。

### 跳 2：Tailscale——"薄控制面 + 会换路的 socket"

四段自述就是四件武器：

`derp/derp.go`：

> Package derp implements the Designated Encrypted Relay for Packets (DERP) protocol. … **DERP is used by Tailscale nodes to proxy encrypted WireGuard packets through the Tailscale cloud servers when a direct path cannot be found or opened. DERP is a last resort. Both sides between very aggressive NATs, firewalls, no IPv6, etc? Well, DERP.**

`disco/disco.go`（打洞信令的报文格式）：

> Package disco contains the discovery message types. A discovery message is: magic [6]byte // **"TS💬"** … senderDiscoPub [32]byte // nacl public key … The recipient then decrypts the bytes…

`wgengine/magicsock/magicsock.go`：

> Package magicsock implements a socket that **can change its communication path while in use, actively searching for the best way to communicate.**

控制面产物（**NetworkMap**）在 `control/controlclient/`（`auto.go`/`direct.go`）——协调客户端把"地图"取回来，交给数据面自己走。

### 跳 3：Netmaker——"最薄的全身"

`models/` 一览就是它的世界观：`device.go`（设备=节点）、`enrollment_key.go`（**入网=注册密钥**）、`acl.go`、`egress.go`/`egress_preset.go`（出口网关）、`dnsEntry.go`、`api_host.go`/`api_node.go`——**没有自研加密、没有自研数据面**：数据面直接吃 **WireGuard（wg 原生）**，控制面（`controllers/` + `grpc/` + `db/`）只干三件事：**生成配置、下发、维护名单**；再配一层信令——`mq/`（`mq.go` / `serversync.go` / `publishers.go`——**MQTT 信令实现**；`netclient/` 客户端侧也在同仓）。三小只里**控制面/数据面耦合最松**的一个。

### 跳 4：共同解剖——overlay 全身五件套

| # | 件 | ZeroTier | Tailscale | Netmaker |
|---|---|---|---|---|
| ① | 身份 | 公钥 + **成员证书** | 公钥 + 控制面签发 | WG 公钥 + enrollment |
| ② | 控制面 | 网络控制器（可自托管） | 协调服务（托管） | 薄 server（自托管） |
| ③ | 可达性 | 根中继 + 直连 | **disco 打洞 / DERP 兜底** | MQ 信令 + WG keepalive |
| ④ | 加密 | 自有 ECC/AES | WireGuard + disco(nacl) | WireGuard |
| ⑤ | 数据面 | 自有 VL2（TUN） | userspace/内核 WG | WireGuard |

——**五件套对全，才叫"overlay 的全身"**；我们只做了第 ⑤ 件的"隧道版"（GRE），前四件由"**物理隔离 + 集中网关**"替代。

## ③ 设计推导（问题树）

**Q1：为什么三小只都"身份=公钥"？**
P2P 的前提：**没有中心锚点，地址即身份**（公钥/公钥哈希）。对照我们：IP 是分配物、身份在控制面数据里——两类"谁是它"的哲学。附带效果：中间人/伪造天然被密码学挡住（它们必须挡）；我们不担心，因为有物理边界。

**Q2：控制面为什么能薄成"发地图"？**
因为**数据面自治**：每个节点都会转发、都会加密、都能打洞——控制面只解决"认识谁 + 能不能连"。对照我们：网关/母机**不会自己推导**，必须喂全量期望态（ZK 树+快照+对账）。一句话固化：**控制面的厚度 = 数据面自治程度的反函数**。

**Q3：NAT 穿透的通用形状？**
**先试直连（disco/STUN 打洞）、不行走中继（DERP/根服务器）**——信令与数据分离、多路径并行探测（magicsock 的"actively searching"）。对照我们：**GRE 终结在公网可达的网关**——有天然锚点，就根本不需要打洞。**拓扑（中心辐射 vs 尽力 P2P）是部署形态决定的，不是能力高低。**

**Q4：加密为什么是它们的默认、我们的没有？**
威胁模型不同：P2P 流量穿过**不可信第三方网络**（家庭宽带/运营商/NAT）→ 端到端加密是刚需；云内 overlay 走自有隔离网络 + 集中网关 → 加密加在别处（专线/IPsec）。**"不加密"不是缺陷，是威胁模型写进架构的结果**；反过来，我们若做"客户端直连"，加密立刻从可选变必需。

**Q5：三小只的取舍谱？**
ZeroTier = 老牌全栈（自有加密/多路径/自托管控制器）；Tailscale = Go 单仓 + 托管协调 + 自研 DERP 网（体验优先）；Netmaker = 最薄（WG 原生 + MQ 信令，自托管小集群）。**共同趋势：薄控制面 + 厚数据面（原生 WG）+ 兜底中继**——现代 overlay 的标准配方。

## ④ 对照卡

| 维度 | ZeroTier | Tailscale | Netmaker | 我们（IaaS overlay） |
|---|---|---|---|---|
| 身份 | 公钥 + 成员证书 | 公钥 + 控制面 | WG 公钥 + enrollment | **IP 分配（身份=控制面数据）** |
| 控制面 | 自托管控制器 | 托管协调 | 最薄 server | ZK 树+快照+对账（**厚**） |
| 可达性 | 根中继 + 打洞 | disco / DERP | MQ + WG | **网关终结（无需打洞）** |
| 加密 | 自有 ECC/AES | WireGuard + nacl | WireGuard | 无（或专线/IPsec 在别处） |
| 拓扑 | 尽力 P2P | 尽力 P2P | 尽力 P2P | **中心辐射** |
| 一句话 | 老牌全身 | 体验最好 | 最薄 | 最可控 |

## ⑤ 验收题

1. "身份=公钥"与"身份=控制面分配"各适用什么范围？若我们的数据面要支持"客户端直连"，哪个机制会最先被迫引入？
2. 用三小只 + 我们（四例）验证："控制面的厚度 = 数据面自治程度的反函数"。
3. 为什么"尽力 P2P"必须自带兜底中继？我们为什么不需要（把架构差异的根源一句话说清）？
4. 把"威胁模型 → 架构决定"的推理链走一遍：为什么它们加密是默认、我们不是？
5. 选型题：5 个节点、都在 NAT 后、要求加密的跨地域小集群——三小只各适合什么场景？各一句话。

---

*本课行号清单（供追问对质）：`ZeroTierOne/README.md:18`（VL1/VL2 原文）、`doc/zerotier-cli.1.md:33`（VL1 定义）；`node/`（ECC/AES/Bond/CertificateOfMembership/Capability）、`service/OneService.cpp`；`tailscale/derp/derp.go`（自述）、`disco/disco.go`（报文格式 "TS💬"）、`wgengine/magicsock/magicsock.go`（自述）、`control/controlclient/`（NetworkMap）；`netmaker/models/`（device/enrollment_key/acl/egress/dnsEntry/api_host/api_node）+ `controllers/`/`grpc/`/`db/`；三仓 commit：`899352e` / `b83f28e` / `e07ffab`。*

*审查记录（首轮深审）：修正/补强 2 处——① Netmaker 的 MQ 从"（`mq`）"升级为**文件级实据**（`mq/mq.go`/`serversync.go`/`publishers.go` + **netclient 同仓**）；② 术语表 "WireGuard/Noise" 行歧义修正——**"加密默认"是共同立场，底座各不同**（ZT 自有 ECC/AES，不是 Noise/WG）。复核项：ZT README:18 与 cli.md:33 两引文（VL1/VL2 逐字）、node/ 五文件（ECC/AES/Bond/CertificateOfMembership/Capability）、tailscale 三段自述（derp/disco/magicsock）+ NetworkMap 目录、netmaker models 八件、三 commit——全部命中。*

*审查记录（二次深审）：R1 两处修正自查通过（mq 文件级实据@60、Noise/WG 拆歧义@20）；三小只引文（ZT 两处 / TS 三段自述 / NM models）复核无残留同义表述——**零尾差**。*
