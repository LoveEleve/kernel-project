---
title: "19-高可用与容灾：故障域、failover、锁、分区与多地域（深修版）"
series: "VPC 二代（Gen2）架构系列"
previous: "18-first-principles.md"
next: "review-log.md"
source_refs:
  - "vpc/docs-by-topic/08-VPC 控制器——分布式架构与分布式问题.md（590 行全读：failover/锁/分区/崩溃恢复/薄弱点）"
  - "vpc/docs-by-topic/04-VPC 控制器复刻参考——数据库、Topic、部署与配置.md（§3 部署拓扑）"
  - "vpc/docs-by-topic/46-zookeeper-3.4.6-differ（vpc_zk_differ）.md（§3.5.1 ansibleDisaster 容灾工具）"
  - "vpc/docs-by-topic/37-vpc-ctrl-knowledge.md（ZK 容灾锚点定位）"
  - "vpc/test-good/01-zk-distributed/architecture-evolution.md（双 Kafka 容灾）"
  - "vpc/test-good/01-zk-distributed/zk-knowledge.md（quorum 容忍）"
  - "vpc/test-good/00-background/13-dns-quota.md（AZ 故障行为）"
  - "vpc/docs-by-topic/11-VPC 控制器——生产环境血泪教训.md（#2 主备隔离缺失）"
---

# 19-高可用与容灾：故障域、failover、锁、分区与多地域（深修版）

> 本篇补"多活/容灾"维度（深修版）：故障域 → failover 机制 → 四种锁的失败模式 → 崩溃恢复对比 → 分区容忍 → 薄弱点 → AZ/多地域。
> 标注：【实证】= 材料有据；【推导】= 按约束推导；【开放】= 材料不足（完整多地域方案 deployment.md 不在工作区）。

## §0 它干了什么（例子先行）

**例 1（AZ 故障）**：AZ3 全挂 → 控制面检测（agent 无心跳/K8s readiness/告警）→ 数据面残留（DPDK 不更新）→ 对账报 Missing → AZ 恢复 → agent 拉最新快照 + 对账消除。AZ1/AZ2 不受影响（子网 = AZ 单元）。

**例 2（进程级 failover）**：母机 Master SIGKILL → 内核自动释放 fcntl 锁 → 同机备进程 3 秒内抢到锁 → 开 netlink 下发权 + 起 HTTP + **全量对账** → ~3-10s 数据面恢复一致。

本篇回答：**每个故障域有什么机制、会怎么失败、恢复要多久、数据丢不丢。**

## §1 掌握卡（8 条）

1. **故障域五层**：进程 → 母机 → AZ → Region → 集群——每层手段不同（§2）。
2. **组件分布式角色**：agent 同机主备（fcntl）/ nfv-proxy 消费者组（ZK 锁）/ broker 分片定归属（SQLite）/ sdn_oss 单实例（MySQL 行锁）（§3）。
3. **主备 failover 是"同机双进程抢文件锁"**：3s 轮询、3-10s 恢复；**四个竞态**（inode 双主/1 秒窗口/降级回调为空/ha.py 仲裁）（§4）。
4. **四种分布式锁各有失败模式**：ZK 锁"抖动即自杀"（已迁 DB 锁）/ Redis 锁"无 fencing 10s 双 Leader" / MySQL 行锁"gap lock + 40s 过期" / 文件锁"inode 陷阱"（§5）。
5. **崩溃恢复**：agent 30s-2min（母机）/ 30-40min（网关）；broker FastStart ~10s；nfv-proxy 全组重启 30s-2min（§6）。
6. **分区容忍**：ZK expire → panic → suicide（存量不变）；Redis 分区双 Leader → 幂等+对账兜底；Kafka rebalance → at-least-once（#22 实证）（§7）。
7. **最脆弱 5 薄弱点**：inode 双主（理论）/ ZK 自杀雪崩（已验证）/ Redis 无 fencing / 快照锁无续约 / 无 partition key 乱序（§8）。
8. **多地域**：ZK 容灾三件套（同步器 → differ → ansibleDisaster）；控制面单 AZ 集中 + 数据层多 AZ（§9-§10）。

## §2 故障域模型（五层）

| 层 | 故障 | 手段 | 恢复 |
|---|---|---|---|
| 进程 | crash / 假死 | watchdog（1-20s 退避）+ 看门狗自杀（17 §3） | 秒级-32s |
| 母机 | 母机挂 | CVM 层重新调度；agent 冷启动 | 20-60min（15 §6） |
| AZ | AZ 全挂 | 子网 = AZ 单元；跨 AZ 业务由多子网/CLB 兜 | 受影响 VM 等 AZ 恢复；其他 AZ 不受影响 |
| Region | 地域挂 | ZK 跨集群同步（灾备）+ 业务面 CCN/专线 | 【开放】完整多地域方案不在材料 |
| 集群 | ZK leader / Kafka partition 故障 | quorum 选举 / ISR / 冗余消费 | #2 / #22 实证（15） |

## §3 组件分布式角色与 HA

【实证】08 §1.1：

| 组件 | 分布模式 | 冲突消解 | 故障恢复 |
|---|---|---|---|
| vpc_agent | 同机双进程（主备） | fcntl.lockf + ha.py | watchdog 拉起 + 全量对账 |
| nfv-proxy | Kafka 消费者组 | ZK 临时节点+顺序锁（已迁 DB 锁） | suicide 让 watchdog 拉起重连 |
| agent-broker | ZK 分片 + Redis 心跳 | vpcId 取模定归属 | 本地 SQLite + FastStart/Snapshot |
| sdn_oss | 单实例（不分片） | MySQL 行锁（Outbox） | BinlogProducer 20 worker 重试 |
| ZK | quorum（生产 5 台容忍 2）+ Observer 扩读 | 选举（zxid 最大者胜） | #2 教训=主备隔离 |
| ZK（跨集群） | 同步器：主→备 | observer 加入 / differ 校验 | ansibleDisaster 切换工具链 |
| CDB | 主从（AZ1 Master、AZ2/3 Slave） | — | failover <30s |
| Kafka | ISR + **双 Kafka 冗余消费**（proxy1_binlog） | ISR 选主 | <10s |
| VPCGW | HARP 主备 + Twins（16 worker 对抢锁） | 抢锁 | 秒级切换；冷启动 2000s+ |
| TGW | VIP 切换（#13：切换→CDB 对账超时） | — | 告警发现 |
| 母机 agent | 每机器 34 进程（HTTP 管理 65358） | — | watchdog |

关键数字【实证】：主备切换窗口 ≤3s；Fusion 双主窗口 ≤10s（锁 TTL，无 Fencing）。

## §4 主备 failover 深读（vpc_agent 同机双进程）

【实证】08 §2：

```
物理机 A
├── vpc_agent（主）: fcntl.lockf(/var/run/vpc_agent_ms_lock, LOCK_EX) 抢到 → master
│     ZK 连接 → zk_master（读主 ZK 集群）；HTTP 65358；netlink 下发 ✓
├── vpc_agent_bak（备）: 抢不到 → slave
│     ZK 连接 → zk_slave（读备 ZK 集群）；HTTP ✗；netlink ✗
└── watchdog: fork 管理两进程；异常退出 → 随机 1-20s → 重新 fork
```

**故障转移时间线**（T+0 SIGKILL）：

```
T+0    内核自动释放 fcntl 锁
T+1s   备进程轮询线程醒来（每 3s 一次）
T+3s   抢锁成功（最坏 3s）→ _is_master=True → cb_of_sw_2_master
T+3.1s dynamic_netlink_state=True（可下发数据面）
T+3.2s 启动 HTTP 服务
T+3.3s 全量对账（state.conn.check(fix=True)：内存 vs ZK 期望 → 修复差异）
T+10s  恢复完成（约 3-10s）
```

**四个真实竞态**（08 §2.4）：

| # | 竞态 | 机制 | 后果 |
|---|---|---|---|
| A | **删除锁文件打破 inode 锁**（最严重） | fcntl 锁 inode 非路径；启动路径 `os.remove(lock_file)` 后 reopen → 新进程拿新 inode 锁 | **双主**（两进程都认为自己是 master） |
| B | 1 秒窗口 | 备进程故意 `sleep(1)` 后再抢锁；Master 若 1s 内崩溃，备可能先拿锁 | 角色错位 |
| C | 降级回调为空 | `m2s_cb_func = None`——master 一旦置位不检查锁是否还在 | 锁丢失无感知 |
| D | ha.py 仲裁兜底 | `enable_only_one()`：检查 `/dev/shm/vpc_agent/{idx}` + 启动时间 + PID，保留先启动完成的 | 非实时（~6s 周期） |

**为什么不用 ZK 选举**（08 §9.1）：两进程同机 → fcntl 是系统调用（零网络/零依赖/毫秒级 failover）；ZK 选举要连接+RTT（≥30ms）+抖动误切换。代价：只保障单机互斥，整机宕机靠外部拉起 + 竞态靠 ha.py 仲裁。

## §5 四种分布式锁与失败模式

【实证】08 §3：

| 锁 | 使用者 | 机制 | 失败模式 |
|---|---|---|---|
| **ZK 临时顺序节点** | nfv-proxy 快照构建（`/SNAPSHOT_LOCK/{vpc_id}_{type}`） | 最小序号持锁 + watch 前一个 | ① **LOST/SUSPENDED → 立即 suicide**（ZK 抖动全量雪崩——已迁 DB 锁）；② Kazoo `start()` 无 timeout → 挂死（修复 `start(timeout=400)`+listener） |
| **Redis SETNX** | Fusion Leader 选举/任务锁 | SETNX + TTL + 续约 Lua（校验 node_id） | ① **无 Fencing → 分区双 Leader**（10s TTL 窗口）；② 快照锁无续约（AutoRenew=false/TTL 10min）→ 长任务并发构建（幂等补偿）；③ 主从异步复制丢锁 |
| **MySQL 行锁** | Outbox 保序（`FOR UPDATE`） | 同 VPC 串行 | ① Gap Lock 死锁（→ `GET_LOCK` 串行化清理）；② `sendState=1` 死锁（Mediator 每 60s 重置 >60s 记录）；③ 40s 锁过期清理无 fencing |
| **文件锁** | vpc_agent 主备 | fcntl.lockf | **删除锁文件 = inode 陷阱 = 双主**（§4 竞态 A） |

**设计决策**（08 §9）：ZK→DB 锁（session 丢失≠suicide，容忍 30s）；Fusion 不用 Raft（对账能修复双主重复 → 不需要强一致；Raft 要 3+ 节点/选举 150-300ms/成员固定）；Fusion 无 partition key（负载均衡 > 顺序，乱序靠 FixBinlogVersion 消解）。

## §6 崩溃恢复对比

【实证】08 §6：

| 组件 | 恢复机制 | 恢复时间 |
|---|---|---|
| vpc_agent（母机） | watchdog → 检测角色 → ZK 全量拉取 → 对账 | 30s-2min（视 VPC 数） |
| vpc_agent（网关） | 同上（路由表巨大） | **30-40min** |
| agent-broker | FastStart（本地 SQLite + 补增量）或 Snapshot（Redis 分片快照 + MD5 + 版本移位） | ~10s / 1-5min |
| nfv-proxy | 全组自杀（防孤立消费）→ init_zk → init_vpc → binlog（committed offset）→ merge | 30s-2min |
| ZK 全宕机（Gen2） | nfv-proxy 暂停消费（消息不丢）→ broker BinlogIncomplete → 恢复续消费补推 | 修复后分钟级（15 §6） |

## §7 分区容忍（ZK / Redis / Kafka）

【实证】08 §7：

- **ZK 分区（Agent 失联）**：session expire → `EXPIRED_SESSION_STATE` → `_panic()` → suicide → watchdog 重启 → 新 session 全量重读。分区中：存量配置不变（内核已有），新变更无法应用。
- **Redis 分区（Fusion）**：slot 级——各自管各自 slot（任务不重叠）；全分区——两节点都 SETNX 成功 = 双 Leader → 生成重复任务 → **幂等 + 对账兜底**；恢复后 Recovery 扫描 requeue。
- **Kafka 分区（nfv-proxy）**：poll 超时 → rebalance → at-least-once + 幂等吸收重复。**#22 实证**：韩国主备切换 → Topic 缺 Partition → ECHO 600s，但**无业务影响**（binlog 冗余通道正常）。

## §8 最脆弱的 5 个分布式薄弱点（08 §8）

| # | 薄弱点 | 机制 | 后果 | 生产验证 |
|---|---|---|---|---|
| 1 | 锁文件删除打破 inode 锁 | fcntl 锁 inode；os.remove 后 reopen | 双主 | ⚠ 理论存在 |
| 2 | ZK 断开即自杀 | Kazoo LOST → suicide | ZK 抖动全量雪崩 | ✓ 已迁 DB 锁 |
| 3 | Redis 锁无 Fencing Token | SETNX 无 fencing | 分区双 Leader | ✓ 靠 TTL+幂等 |
| 4 | 快照锁无续约 | TTL 10min / AutoRenew=false | 长任务并发构建 | ⚠ 幂等补偿 |
| 5 | Kafka producer 无 partition key | 轮询分布 | 同 VPC binlog 乱序 | ✓ FixBinlogVersion 消解 |

## §9 AZ 级容灾（子网 = 单元）

- **子网绑 AZ**（14 §5.6）：同 AZ <0.5ms（不 GRE）、跨 AZ GRE 1-2ms、跨 Region 10-30ms。
- **AZ 故障行为**（13-dns-quota §2.3）：检测（agent 无心跳/K8s readiness/告警）→ 数据面残留（DPDK 不更新）→ 对账 Missing → 恢复（拉快照+对账消除）。
- **部署视角**（04 §3.1）：AZ1 = 控制面主（vpc-cgw/sdn_oss/MySQL Master/Kafka/ZK/中间层）；**AZ2/AZ3 含 MySQL Slave**——控制面单 AZ 集中 + 数据层多 AZ。

## §10 Region 级与多地域

- **单 Region 独立部署**（04 §3.1/§3.2）：vpc-cgw 8 进程（8520）；sdn_oss 256 uWSGI + ~51 后台进程；nfv-proxy / agent-broker 分片部署。
- **跨地域灾备**：ZK 同步器（13 §2）→ differ 校验（13 §4）→ **ansibleDisaster 工具链**（46 §3.5.1：备机房 syncer observer 加入 → 校验同步进度 → 提升/切换 zk master → 升级包版本）。
- **ZK 容灾定位**（37）：OSS 与 Agent 间的"消息中间件 + 配置存储 + **容灾锚点**"。
- **地域线索**：清远 zone=32（02 §9.2）；融合地域封禁迁移（事故 #3）；CCN 跨地域（14 §7）。
- 【开放】完整多地域多活方案（`deployment.md`）**不在工作区**——以上为材料散点汇总。

## §11 数据级容灾与 RTO/RPO

- **数据级**：ZK COS 备份（13 §8，10 天归档/30 天删除）+ differ 主备校验；CDB 主从（半同步）；CRS 保留 4 版（03）；Kafka 持久化（ZK 宕机不丢，15 §6）。

| 故障 | RTO | RPO | 出处 |
|---|---|---|---|
| 进程 crash（vpc5.0） | ~32s | 0 | 15 §6 |
| 主备 failover（vpc_agent） | 3-10s | 0（全量对账） | 08 §2.3 |
| CDB failover | <30s | 半同步（无丢失） | 16 |
| ZK 选举 / Kafka ISR | <10s | 0 | 16 / zk-knowledge |
| ZK 全宕机 | 修复后分钟级（存量不受影响） | 0（binlog 持久化） | 15 §6 |
| VPCGW 完整重启 | 30-40min | SQLite 快照兜底 | 15 §6 |
| 母机 agent 重启 | 20-60min | LevelDB/SQLite | 15 §6 |
| HAVIP 误删（#18） | 21min | — | 15 |
| 152 台不一致（#6） | 3h10m | — | 15 |

## §12 边界

- 材料为散点+深料（08 分布式问题全读 / 04 部署 / 46 容灾工具 / 事故复盘）——**没有一份完整的"多活架构"文档**；标【开放】。
- vpc5.0 的多活/容灾（Fusion 选举域 / SET 跨 SET 迁移）归 vpc5.0 系列。
- `deployment.md`（多地域容灾方案）不在工作区——若拿到可补全 §10。
