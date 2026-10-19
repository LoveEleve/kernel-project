---
title: "02-写侧管道：从 Saga 到分区 ZK binlog"
series: "VPC 二代（Gen2）架构系列"
previous: "01-overview.md"
next: "03-merge-snapshot.md"
source_refs:
  - "vpc/VPC控制面.md（第二章 二代 / 精确数据格式与转换管道章）"
  - "vpc/docs-by-topic/23-nfv-proxy.md"
  - "vpc/docs-by-topic/24-sdn_oss.md"
  - "vpc/test-good/01-zk-distributed/architecture-evolution.md"
  - "vpc/test-good/00-background/add_vpc.md（568 行全读：AddVpc 5-step 源码走读——补遗 §9）"
---

# 02-写侧管道：从 Saga 到分区 ZK binlog

## §0 它干了什么（例子先行）

一条路由变更（VPC 12345 加路由 10.0.0.5 → 192.168.1.1）在写侧的全过程：

```
① sdn_oss：Saga 事务
   写 MySQL：t_rtable（权威数据）+ Outbox 表（待发消息）
   取号：transId（DB 自增 / CRS incr / 独占表 三路径）
② Binlog Producer：扫 Outbox（sendState=0）→ 发 Ckafka
   topic=t_rtable，key=12345_10.0.0.5，value=protobuf
③ nfv-proxy.proxy_binlog：消费 → 原样创建 ZK 节点
   /BINLOG/12345/RTABLE/{group}/1517070001_I_10.0.0.5_3_192.168.1.1
   并更新该组 trigger（=本组最大版本）+ 登记 VPCRECENTCHANGE
```

**输入**：DB 事务 + Outbox 消息；**输出**：分区 ZK 上的 binlog 节点 + 最近变更索引。

## §1 掌握卡（5 条）

1. **写侧保证"版本序"，不保证"到达序"**：Kafka key 只保同 VM 有序；跨 VM/分区可乱序——所以 ZK 树以版本组织，消费/合并按版本区间读（乱序不破坏正确性）。
2. **binlog 字符串**：`version_action_fields...`，字段模板按类型定义；action = I/D/U/R；Update 字段是 `旧值>新值`。
3. **分组**：`group = 版本 // GroupSize`（RTBROUTE/SERVICE/GLOBALROUTE=500，其余=1000）；每组一个 `trigger` 节点（值=组内最大版本）。
4. **VPCRECENTCHANGE = 反向索引**：时间片桶 + ≥5000 提前滚动 + 持久节点 + 发布保护；merge master 靠它找"哪些 VPC 变了"。
5. **12h 是拒写不是保留**：`valid_ts = now()-12h`，超窗 binlog 拒绝写入 ZK（防旧 binlog 污染）。

## §2 sdn_oss（写侧权威）

- **定位**：VPC OSS（Python 2.7，uwsgi+nginx），全量资源的期望状态权威源；Handle 流程 = 校验 → 事务 ID → 写 MySQL → 写 ZK 期望树（一代路径）。
- **Saga + Outbox**：每种配置类型一张 Outbox 表（共 25 张，如 t_service_trans），字段：transId/vpcId/epoch/action/sendState(PENDING→SENDING→DONE)/binlogData/retryCount。
- **transId 三条取号路径**：
  - DB 路径：`INSERT INTO t_vm_trans_id` 拿自增 ID
  - CRS 路径：`incr("itrs:vm:{vpcId}")`（快约 10 倍）
  - 独占表路径：超高频 VPC 单独建表
- **epoch**：VPC 销毁重建时 epoch+1，防跨生命周期 ID 冲突。
- **保序**：`asyncProducerLockQueueTrans` 用 DB 锁保证同 key 顺序 + 60s 补救；失败消息回扫 + dead letter。

## §3 Ckafka（变更总线）

| 项 | 值 |
|---|---|
| topic | 按配置类型（材料举例：t_rtable / t_rtable_vpcgw / t_host_prop） |
| key | `vpcId_vmIp`（同 VPC 同 VM 同分区有序） |
| 消息 | JSON 信封（type/schema/data[zkPath]/ts；protobuf 口径属 VPC5.0，勿混） |
| 消费 | `enable_auto_commit=False`、`max_poll_records=1`、`auto_offset_reset='earliest'`、处理后 commit_async |
| consumer group | `proxy_binlog_{group}_{partition}_{zone}_{name}` |
| 冗余 | proxy1_binlog / proxy1_conf 双份消费（容灾） |

## §4 nfv-proxy 14 类进程

| 进程 | 职责 |
|---|---|
| proxy_init_zk | 分区 ZK 根路径初始化 |
| proxy_init_vpc（×5） | 新 VPC 全量初始化（从 MySQL 拉全量 → CRS 初始快照）/ terminate 清理 |
| **proxy_binlog** | Ckafka → 分区 ZK binlog + VPCRECENTCHANGE（按 feature group 拆：rtable / service / long_tail） |
| proxy1_binlog | 冗余消费 |
| proxy_conf / proxy1_conf | 配置类消费 + 守护 |
| **proxy_merge_master** | 合并调度（单实例，持锁；扫 VPCRECENTCHANGE → CRS pending → 限流分发） |
| **proxy_merge（×5）** | 合并压缩（rpop → 合并 → CRS；按 VPC 分片；37 种配置 / 32 个硬编码 MERGE_CLASS） |
| proxy_lazymerge | 冷数据补压缩（每小时，6 类公共快照） |
| proxy_cl5 | CL5/北极星 RS → ZK（供下游发现） |
| vpcgw_snapshot | 网关进程粒度快照 |
| append_snapshot | 快照增量追加 |
| snapshot_differ | 快照 vs DB 文件级对账 |
| proxy_monitor | 健康巡检 |
| （旁路）go_proxy | Go canal 订阅 CDB binlog → 分区 ZK（GTID 位点、分布式锁分 worker、表黑白名单；GTID 失效降级 MergeFromDb 全量补偿） |

## §5 分区 ZK 树（binlog 的家）

**路径**：
```
VPC 粒度: /{REGION}/BINLOG/{vpcId}/{TYPE}/{group}/{version_action_fields...}
全局粒度: /{REGION}/BINLOG/{TYPE}/{group}/{version_...}   （FakeVpc=-1024）
每组:     trigger 节点（值 = 本组最大版本号）
```

**14 种版本类型**（binlog 树/版本表口径）：

| # | 类型 | # | 类型 |
|---|---|---|---|
| 0 | RTABLE | 7 | REMOTE_GW |
| 1 | RTABLE_VPCGW | 8 | VPC_VPCGW |
| 2 | SERVICE | 9 | VPC_HBGW |
| 3 | GLOBAL_ROUTE | 10 | VPC_VIPTGW |
| 4 | RTB_ROUTE | 11 | VPC_CONFIG |
| 5 | SUBNET_ROUTE | 12 | HOST_CONFIG |
| 6 | SUBNET_RULE | 13 | GW_CONFIG_MTU |

**binlog 字符串模板**（节选）：

| 类型 | 模板 | 示例 |
|---|---|---|
| RTABLE | version_action_vmIp_type_remote | `1517070001_I_10.0.0.5_3_192.168.1.1` |
| RTBROUTE | version_action_rtbId_dnet_remoteType_remote_remoteGw_remoteVpcId_vmIp_hashSelect | dnet 含 `\|掩码` |
| GLOBALROUTE | version_action_dnet_remoteType_remote_remoteGw_hashSelect_vmIp | |
| VM | version_action_vmIp_tsvIp_peerIp_vmEip_eip_forwardHostIp_mac_eipType_aliasVpcId_bAlgSip_bAlgFtp | |
| SVC | version_action_svc(proto:vip:vport)_attrs_route(pip:pport)_attrs | |

- **action**：I=Insert / D=Delete / U=Update（字段 `旧>新`）/ **R=Reload（触发 Agent 全量重读该 VPC）**
- **三层映射链**：OSS transType（~33 种）→ ZK 路径类型字符串（RTABLE / GLOBALROUTE / HOSTVPCCONFIG…）→ agentos BinlogType（独立枚举；全局类型走 FakeVpc=-1024）

## §6 VPCRECENTCHANGE（最近变更索引）

```
/BINLOG/VPCRECENTCHANGE/{YYYYMMDDHHMM}/{vpcId}
```

- **为什么需要**：200 万 VPC 全树扫描不可能；需要一个"写时顺带登记、读时按时间片扫"的反向索引（merge master 的主要输入）。
- **设计**：分钟键时间片桶；子节点 ≥ 5000（PREPARE_SPLIT_NUM）自动切分；**持久节点**（靠 group 滚动清理，不靠过期）；**发布保护 9:30-9:39**：写入收敛到当前最大分组（防发布期目录爆炸）。
- 口径冲突：时间片标称值（配置 10min vs 更正版"分钟"）→ 见 06，机制不受影响。

## §7 版本号编码

```
真实版本 = 151707（递增整数）
内部版本 = 真实版本 × 10000（ShiftSteps=4, ShiftNum=10000）
浮点样式字符串: "151708.0001" ↔ 1517080001
Group = 真实版本 / GroupSize（RTBROUTE/SERVICE/GLOBALROUTE=500，其余=1000）
对账修复: InspectFixStepVer=1（内部版本 +1）；修复 binlog 的 Group="-1"（特殊标记）
```

## §8 时效保护（12h 拒写）

- `valid_ts = now() - 12h`；**超过 12h 的 binlog 拒绝写入 ZK**（防旧 binlog 污染）。
- 本质假设：**合并延迟 < 12h**——超出这个假设的迟到变更走对账兜底（见 06 Q3）。

## §9 AddVpc 走读补遗：5 step、三套 ID 与 Saga 边界（查漏补缺）

【实证】test-good/00-background/add_vpc.md（sdn_oss 源码走读，数据锚点 vpcId=5154392 / id=1442154 / vpc-rpc088bl）：

### 9.1 入口 = 5 个 step 的 Saga（不是 2PC）

```python
# sdn_oss/api/add_vpc_and_subnet_handle.py:115-120
def do_after_init(self):
    self.add_step(self.add_vpc,          self.del_vpc)           # step 1
    self.add_step(self.add_router,       self.del_router)        # step 2
    self.add_step(self.add_default_rtb,  self.del_default_rtb)   # step 3
    self.add_step(self.add_subnet,       self.del_subnet)        # step 4
    self.add_step(self.add_rule,         self.del_rule)          # step 5
```

- 顺序由外键依赖决定：vpc（容器）→ router（逻辑分组）→ rtb（转发容器，依赖 router）→ subnet（依赖 vpc）→ rule（绑定 subnet↔rtb）；反了不行。
- 失败按反向顺序调 del_xxx（补偿是完整删除流程，不是简单逆向）；不用 2PC——跨 DB+ZK+异构资源无法 XA，代价是补偿路径靠人工保证完备。
- **补偿两类**（事故 #19 / add_vpc 所称"事故 A"的教训）：删节点型（低风险）vs 写值型（高风险——HostWatcher 更新必须前置 schema 校验，不能空串/0.0.0.0 都写）。
- 5 step 落库：vpcid+t_vpc+vpcConfig+vpcDirectSend / t_router / t_rtb / t_subnet+hostSubnetReserved+vpcgwSubnetDpSet / t_rule；三个生产 VPC 实测（gwIp 实际存 0.0.0.0、t_vpc 不存 region、清远 zone=32）。

### 9.2 三套 ID（最容易混）

| 字段 | 例 | 来源 | 用途 |
|---|---|---|---|
| id | 1442154 | t_vpc 自增主键 | 编码 uniqVpcId |
| vpcId | 5154392 | **独立 ID 分配系统申请**（不是自增） | 分表 t_ip_{hash(vpcId)%250} + ZK 路径 |
| uniqVpcId | vpc-rpc088bl | encode_uniq_id(id, zone)（加密+地域） | 前端展示 / API 引用 |

- **vpcId 申请制**：`vpcid` 表存"段"（section/mask/min/max，按地域由广州总控统一分配，防跨地域撞号——类比手机号段）；`vpcidInstance` 表存段内号（flag 0=可分配/1=已分配/2=退还中）；分配 = 条件更新 flag 0→1；退还 = flag→2，过 KEEP_HOUR（默认 12h）回收为 0。
- **位编码**：`vpcId = (intPrefix << 24) | intPostfix`（高 8 位子段 + 低 24 位段内号；intPrefix 默认 0）；5154392 >> 24 = 0，低 24 位 = 5154392。
- **uniqVpcId 编码的是 id（不是 vpcId）+ zone**：实测 `encode_uniq_id('vpc', 1442154, zone=0)=vpc-rpc088bl`；不同 zone 不同结果（藏真值 + 跨地域唯一 + 可校验）。
- 【开放】与 15 §5.4（vpcId 碰撞）的编码口径差异：add_vpc = `encode_uniq_id(id, zone)`；碰撞篇 = `((count << 20) | vpc_id)`——两文对"编码入参"描述不同，待源码核对。

### 9.3 关键边界：AddVpc 不写数据面节点（Gen1 链路口径）

- AddVpc 写的 ZK = 控制面开关节点（DHCP / MCFLAG / 广播 bc_zoo / CDC / reserved_subnet_zoo / RULE）；**/TVPC/RTABLE/{vpcId}/{subnet}/{vmIp}-{hostIp}、/TVPC/VM/{vpcId}/{vmIp} 都是 AddVm 写的**（AddVpc 时还没有子机）——"控制面→数据面的真正起点是 AddVm"。
- Gen2 对照：对应 init_vpc 全量初始化（proxy_init_vpc → CRS 初始快照）+ 后续 binlog（见 01/03）。
- t_router 是 OpenStack Neutron 遗留的纯逻辑对象——不写 ZK、不参与数据面转发；rtb 才是转发容器。
- 设备侧 groupId 公式（vpc_agent）：`groupId = (vpcId << 32) | ip_to_kernel(vmIp)`（实测 22137945238536194）。

### 9.4 口径备注

- **agentos 指代（Round 10 闭合）**：主文档 §2.5 ★ 更正——agentos 是 **Gen2 agent-broker 的底座框架**（Go）（"之前文档错误地把它放在 VPC 5.0 章节"）；Gen2 设备 Agent 就是 Gen1 的 vpc_agent（Python，同一进程同一套代码），agentos/agent-broker 只是新增的中间代理层。add_vpc 的"agentos 不是 Gen2（核心）"指它不是设备 Agent 本体——与上述一致；04-others 称其"Gen3 框架"与更正冲突，标【残余开放】。

### 9.5 Saga 回滚失败与 Go/Python 语义分界（docs-by-topic/10 补遗）

- **回滚失败处理**：缓存回滚上下文到 Redis（key `rollback:{transId}:{step}`，TTL 24h）+ 告警 + 暴露 `Revert.TVPC.AddService` 类接口供人工重试（案例：AddService 60 步中 del_zoo 因 ZK 断连失败）。
- **Go 控制面（vpc5.0）用同步补偿**：Kafka 发送失败 = 整个 Saga 回滚（`prepareRollbackData` 预生成反向数据；forward/backward 用同一函数）——**与二代 Python 经典 Outbox（DB 先提交 + 异步补发）语义不同，勿混**【vpc5.0】。
- **接口合约**：vpc-cgw → sdn_oss 走 `POST /tvpc/api`（interfaceName=TVPC.AddVpc；returnValue 0=成功 / -3001=记录不存在 / -3017=超配额）。
- DeleteVpc 是 sdn_oss 最复杂 Saga（**≈115 步**）。

## §10 钩子

- 这些 binlog 怎么变成快照、什么时候能合并 → **03**。
- 节点命名/顺序保证的推导 → **06 Q1**。
- VPCRECENTCHANGE 桶设计的推导 → **06 Q6**。
