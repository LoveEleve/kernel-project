---
title: "01-二代全景：Kafka 增量 + 分区 ZK + CRS 快照"
series: "VPC 二代（Gen2）架构系列"
previous: "README.md"
next: "02-write-path-binlog.md"
source_refs:
  - "vpc/test-good/01-zk-distributed/architecture-evolution.md"
  - "vpc/VPC控制面.md（第二章 二代 / 三代对比 / 修正清单）"
  - "vpc/docs-by-topic/17-VPC 控制面总体架构（跨项目导读）.md"
  - "vpc/docs-by-topic/23-nfv-proxy.md"
---

# 01-二代全景：Kafka 增量 + 分区 ZK + CRS 快照

## §0 它干了什么（例子先行）

用户在控制台创建一个 VPC（vpc-abc123，CIDR 10.0.0.0/16）。这条"变更"在二代里的旅程（材料口径，毫秒级）：

```
T+0ms    用户调用 CreateVpc
T+5ms    vpc-cgw：参数校验 + CAM 鉴权
T+10ms   sdn_oss：Saga 开始（写 MySQL t_vpc + 建 ZK 顶层路径 + Outbox sendState=0）
T+20ms   返回 VpcId
T+25ms   Binlog Producer 扫描 Outbox
T+30ms   发 Ckafka（VPC 初始化事件）
T+40ms   nfv-proxy.proxy_init_vpc 消费 → 从 MySQL 全量拉默认配置 → 写 CRS 初始快照
T+50ms   agent-broker 收到 VPCRECENTCHANGE → 发现新 VPC → ZK binlog + CRS 快照同步 → SQLite
T+60ms   母机 agent 感知新 VPC → 创建 vbr + GRE 隧道 + veth pair → 下发默认路由
```

（之后在 VPC 内创建云主机、改路由等变更走 binlog 通道：sdn_oss → Ckafka → proxy_binlog → 分区 ZK，见 02 篇。）

**输入**：用户的网络变更（DB 事务 + binlog 消息）。
**输出**：母机/网关上数据面生效的配置；旁路产出 CRS 里的全量快照（供冷启动/兜底）。

## §1 掌握卡（5 条）

1. **ZK 从"数据库"降级为"索引 + 临时增量"**：全量数据不在 ZK，只在 CRS 快照；ZK 只保留近期 binlog（合并后清理、超 12h 拒写）。
2. **变更走 Kafka 而不是 Watch**：binlog 自带"什么变了"（对比 Gen1 Watch 只说"变了"→ O(n) diff）。
3. **中间层 agent-broker（Go/agentos）**：把数万台母机对 ZK/CRS 的读扇入收敛；母机用 TRPC 心跳 **Pull**（不是 Push）。
4. **两条消费形态**：母机 → agent-broker；网关（vpcgw 等）→ 直连 CKV+分区 ZK（the_2g_op，支持主备倒换回放）。
5. **灰度是 VPC 粒度**：白名单 `2GNODE/2GSERVICE/2GHOST` + `/TVPC/2GCTRL` 寻址；切换时做全量 diff（以二代为准）。

## §2 为什么有二代——Gen1 的五个问题与解法

| Gen1 问题 | 根因 | Gen2 解法 |
|---|---|---|
| Watch 只说"变了" → O(n) diff | ZK Watch 语义 | Kafka binlog 自带变更内容 |
| commitProcessor 单线程瓶颈 | 读写竞争、查询优先 | 分区 ZK + CRS，ZK 不存全量 |
| agent 重启 30-40 分钟 | 全量遍历重建 27.5 万 Watch | CRS 快照 + binlog 断点续传 |
| 惊群（2019 年 1TB 流量） | 一个变更触发 10 万+ agent 同时拉取 | 各自消费 Kafka + broker 心跳拉取 |
| ZK 内存不可扩展（19.3GB） | 15M Znode + 50M Watch 全内存 | ZK 只存临时 binlog，合并后清理 |

【补遗，zk-knowledge】内存公式：Watch ≈271B / Znode ≈645B——单母机 26,446 VM × 11 Watch ≈ 29 万 Watch ≈ 78.8MB；100 台母机 ≈ 7.8GB；2021 年已用 512GB 物理机跑 Observer。2019-02-12 故障实测：children 拉取 2.5M 个/s、带宽 160Mbps。

> 一句话：**Gen2 = 冷热分离**。热（近期变更）放 ZK 做索引/通知，冷（全量）放 CRS；通知从 Watch 换成 binlog。

## §3 组件链路（全景图）

```
sdn_oss（Python 2.7，VPC OSS）
  │ Saga → 25 张 Outbox trans 表 → BinlogProducer（20 worker + 1 mediator）
  ▼
Ckafka（topic 按配置类型；key=vpcId_vmIp；action I/D/U/R；消息格式见 02 §3）
  ▼
nfv-proxy（Python 3，14 类进程）
  ├─ proxy_binlog ──→ 分区 ZK：/{REGION}/BINLOG/{vpcId}/{TYPE}/{group}/{version...}
  ├─ proxy_merge_master / proxy_merge（×5）/ proxy_lazymerge ──→ CRS 快照
  ├─ proxy_init_vpc / proxy_init_zk / proxy_cl5 / proxy_monitor
  └─ vpcgw_snapshot / append_snapshot / snapshot_differ（进程粒度快照与对账）
  │ 旁路：go_proxy（Go canal 订阅 CDB binlog → 分区 ZK，GTID 位点）
  ▼
分区 ZK（binlog 热数据 + 索引）＋ CRS（全量快照，保留 4 版）
  ▼
消费侧两条形态：
  ├─ 母机：agent-broker（Go/agentos，SQLite）→ TRPC HostHeartbeat → 母机 agent
  └─ 网关：vpc_agent（the_2g_op）直连 CKV+分区 ZK（本地合并、do_switch 回放）
  ▼
数据面：vpcko(netlink) / DPDK(PAL TCP:6666+protobuf) / netagent
```

## §4 与一代 / VPC 5.0 的对照（简表）

| 维度 | 一代 | 二代 | VPC 5.0 |
|---|---|---|---|
| 变更通知 | ZK Watch 广播 | Ckafka → nfv-proxy → ZK binlog | Kafka protobuf → gRPC Stream |
| 中间层 | 无（Agent 直连 ZK） | agent-broker（Go，有状态） | vpc-broker（Go，无状态） |
| 全量 | ZK 全量树 | CRS 分片快照 | Fusion 快照（MySQL+LevelDB） |
| 增量 | Watch + diff（O(n)） | 分区 ZK binlog（O(1) 消费） | BTree 版本索引 + 三向合并 |
| Agent | vpc_agent（Python 2.7） | vpc_agent（Python，同一套代码；agentos=broker 底座） | vpc-host-agent（Go） |
| 分片 | 无 | vpcId % 500 % 15（broker 进程持 partition） | SET 元数据驱动 |
| 灰度 | 无 | ZK 白名单（2G） | 白名单 + SET 元数据 |
| 对账 | 手动脚本 | differ + CheckJob + agent 对账 | 三层系统化对账 |
| 瓶颈 | ZK 单核 1-3M children/s；冷数据 85-94% | CRS 快照构建开销 | GC 停顿 |

## §5 关键数字速查

| 项 | 值 |
|---|---|
| binlog 类型 | 14 种（0=RTABLE … 13=GW_CONFIG_MTU） |
| binlog 保护窗口 | 12h（超窗拒写） |
| 分组 | group = 版本 // GroupSize（500 / 1000） |
| broker 分片 | vpcId % 500 % 15 + GlobalWorker(15) |
| 心跳 Pack | 6 种（含 PackError） |
| 心跳降级阈值 | PackBinlogMaxSize = 5000 |
| 快照保留 | 4 版（MAX_SNAPSHOT_NUM=4） |
| 对账 | VPC 5min / 全局 10min；DelMoreThreshold=5000 |
| 恢复 | FastStart ~10s / Snapshot 1-5min |
| nfv-proxy | 14 类进程；merge ×5；BinlogProducer 20+1 |

## §6 本篇回答的问题

- 二代是什么、为什么存在（§1/§2）
- 全链路长什么样、各组件职责（§3）
- 与一代/三代的边界（§4）

深挖入口：写侧细节 → 02；合并与快照 → 03；消费侧 → 04；分区与 ZK 基础设施 → 05；无源码推导 → 06；broker 内部机制 → 08；设备侧与 echo → 09；对账与恢复 → 10。
