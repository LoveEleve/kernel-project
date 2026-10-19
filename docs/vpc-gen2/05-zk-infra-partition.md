---
title: "05-分区模型与 ZK 基础设施"
series: "VPC 二代（Gen2）架构系列"
previous: "04-consumption.md"
next: "06-derivation-review.md"
source_refs:
  - "vpc/test-good/00-background/core-objects.md（t_vpc_part 系列）"
  - "vpc/docs-by-topic/17-VPC 控制面总体架构（跨项目导读）.md"
  - "vpc/docs-by-topic/31-vpc_zookeeper_3_4_6_synchronizer.md"
  - "vpc/VPC控制面.md（第二章 / ZK 树）"
---

# 05-分区模型与 ZK 基础设施

## §0 它干了什么（例子先行）

VPC 3345906 创建后，agent 怎么找到"该读哪套 ZK/CKV/CDB"？

```
① t_vpc_part：广州只有 1 个分区 vpp-5fyd1tva（系统默认，weight=10）
② t_vpc_part_entity：VPC 3345906 → 分区 1 + zone（3/4/5/6）
③ t_vpc_part_instance：每 zone 一行"三件套"：
     zkHost  = 30.121.200.91:2012,...         （ZK 集群）
     cdbHost = 9.255.46.14:31776:nfv_proxy_rw:...（MySQL 连接串）
     ckvHost = 192002753:243062               （CKV 的 L5 路由标识 modId:cmdId）
④ Agent 经 /TVPC/2GCTRL/{partition}/{zone} 拿 storage → 建连接
⑤ 没有独立实例的 zone（如二区/七区）→ 经 t_vpc_part_instance_refer 引用五区主/四区备
```

## §1 掌握卡（4 条）

1. **"分区"是 DB 实体**：t_vpc_part 系列 9 张表定义了"VPC → 分区 → zone → 三件套（ZK/CKV/CDB）"的完整寻址链。
2. **读写分离靠 observer**：agent 走 Observer 节点（gw 池），不打扰 leader 写路径；池按 poolName 分组 + weight 均衡。
3. **地址格式三种**：zkHost=IP:Port；cdbHost=IP:Port:User:DBName:Password；ckvHost=modId:cmdId（经 L5 解析成真实 Redis 地址）。
4. **ZK 是定制版**：3.4.6（observer 改造成跨集群同步器）+ 3.5.6（zxid 进位安全）+ 周边工具族（differ/backup/controller/monitor）。

## §2 分区模型（t_vpc_part 系列）

| 表 | 装什么 | 实例值 |
|---|---|---|
| t_vpc_part | 分区定义 | 广州 1 个分区 vpp-5fyd1tva（ownerLevel=-1 系统默认，weight=10） |
| t_part_zone | 分区覆盖哪些 zone | 3/4/5/6 四个可用区（bAllInOne=0） |
| t_vpc_part_entity | VPC → 分区 + zone | vpcId=3345906 → 分区 1，zone 3/4/5/6，state=2 ACTIVE |
| t_vpc_part_instance | 每 zone 三件套地址 | zkHost / cdbHost / ckvHost（三种格式见 §1） |
| t_vpc_part_storage | 存储资源目录 | 独立管理"某 zone 用了哪些 CKV/CDB" |
| t_vpc_part_zk | ZK 集群注册 | current + newZkHost（迁移目标，如四区正平滑替换） |
| t_vpc_part_zk_observer | Observer 池 | poolName=gw（专供 VPCGW agent 读），weight 均衡 |
| t_vpc_part_proxy | nfv-proxy 注册表 | proxyIp + consumerGroupId + zoneId；同组共享消费进度（一条消息只被组内一台消费），不同组各自全量消费 |
| t_vpc_part_instance_refer | zone 间引用 | 二区/七区无独立实例 → 引用五区主 + 四区备；主区引用自身 |

> 为什么"分区"要独立成表族：没有它，系统不知道"VPC 创建时分到哪个 zone、配置去哪套存储"——这是物理资源与逻辑 VPC 的绑定层。

## §3 分区寻址（agent 视角）

```
/TVPC/2GCTRL/{partition}/{zone}
  zone    = "{mode}:{masterZone}:{slaveZone}"（如 0:0:1 → RELATE；1:0:1 → STANDBY）
  storage = "zk1:2181|zk2:2181|zk3:2181|redis:6379|cdb:3306"

/TVPC/READY/2GZK/{zoneId}/{masterAddr} → observer 地址（data="{observerAddr}"）
/TVPC/READY/CKV                        → CKV/CRS 地址索引
```

- VPC 按 `vpcId % partition_count` 分配到分区；每个分区主备两个 zone。
- **Observer 是只读 ZK 节点**，供 agent 消费——写路径不受读压力影响。

## §4 ZK 基础设施族谱

```
                 zk_controller（zk-mgr，REST 管控面：component/cluster/zk_node/pool/client）
                  │ 提供"就近 zk 节点"查询
                  ▼
   ┌── 每台 ZK 机器上 ─────────────────────────────┐
   │ zk_agent（执行端：iptables/ipset 安全组、生命周期）│
   │ ZooKeeper_VPC_Monitor（指标采集 → barad 上报+告警）│
   └──────────────────────────────────────────────┘
        │                    │                    │
  vpc_zookeeper_3_4_6_   zookeeper-3.4.6-    vpc_zk_cos_backup
  synchronizer           differ               （snapshot/txnlog 定时备份 COS）
  （observer→跨集群同步器）  （主备一致性校验）
```

| 件 | 作用 |
|---|---|
| vpc_zookeeper_3_4_6_synchronizer | observer 改造成 CrossClusterSynchronizer：主→从增量 binlog 式同步 + 全量同步 + LeaderLatch 主备 + 四字命令（syld/syst/sypa/syfi/sysi/syss 等 8 个）控制 + 星云告警 |
| zookeeper-3.4.6-differ | 主备集群 snapshot+txnlog 逐条比对，输出差异报告 |
| vpc_zookeeper_3_5_6 | zxid 进位安全（AtomicZxid）等定制改造 |
| vpc_zk_cos_backup | ZK 数据（snapshot/txnlog）定时备份到 COS |
| zk_controller / zk_agent | ZK 集群管控面 / 节点执行端（安全组、生命周期） |
| ZooKeeper_VPC_Monitor | ZK 指标监控告警 |

## §5 钩子

- 为什么"分区/12h/observer"这样设计 → **06**（推导与负空间）。
- 消费侧怎么用这些地址 → **04 §2/§3**。
