---
title: "04-消费侧：agent-broker 与两条形态"
series: "VPC 二代（Gen2）架构系列"
previous: "03-merge-snapshot.md"
next: "05-zk-infra-partition.md"
source_refs:
  - "vpc/docs-by-topic/15-agent-broker（VPC Agent Broker 服务）.md"
  - "vpc/docs-by-topic/16-AgentOS.md"
  - "vpc/docs-by-topic/17-VPC 控制面总体架构（跨项目导读）.md"
  - "vpc/docs-by-topic/28-vpc_agent（master 分支）.md"
  - "vpc/VPC控制面.md（第二章 / agent-broker 存储与心跳章）"
---

# 04-消费侧：agent-broker 与两条形态

## §0 它干了什么（例子先行）

**母机 agent 重启**（同一个 VPC 12345 的配置源是 agent-broker）：

```
① 读本地 SQLite：localVer = 1517070000
② 对比远端最小可用版本 remoteMinVersion：
   localVer ≥ remoteMinVersion → FastStart（增量续跑，~10s）
   否则                        → Snapshot（从 CRS 拉 worker 分片快照，1-5min）
③ 心跳 Pull（TRPC HostHeartbeat）：
   agent 上报 peerVer → broker 按 6 种 Pack 决策 → 回增量/全量
```

**网关侧**（vpcgw 等）不走 broker：vpc_agent 的 the_2g_op 直连 CKV+分区 ZK，本地合并回放；主备倒换时 `do_switch` 回放近期 binlog。

## §1 掌握卡（5 条）

1. **消费侧两条形态**：母机 → agent-broker（TRPC 心跳 Pull）；网关 → 直连 CKV+分区 ZK。
2. **agent-broker = agentos 框架的一个部署形态**（Go）：Ext = Upstream + Res + Downstream 三段式。
3. **分片**：`vpcId % 500 % 15` → worker 0-14；GlobalWorker(15) 管白名单 VPC / 全局数据（-1/0/2^32-1 归 15）。
4. **心跳 6 种 Pack** 是版本协商协议：Uninitialized / UpToDate / Outdated / BinlogIncomplete / BinlogUpdate / PackError。
5. **对账贯穿三层**：nfv-proxy differ（快照 vs DB）→ agent-broker CheckJob（ZK vs SQLite）→ agent 侧对账（数据面 vs 期望）。

## §2 agent-broker（Go / agentos，有状态中间层）

**职责**：上游订阅二代 ZK/CRS → 落 SQLite 维护内存版本 → 下游 TRPC `HostHeartbeat` 向母机 agent 增量/全量同步。

**分片与存储**：
```
分片: vpcId % 500 % 15 → worker 0-14；特殊 -1(白名单VPC)/0/2^32-1 → worker 15 (GlobalWorker)
存储: /dev/shm/agent_broker_db/
  ├── current/db{idx}   ← 当前配置（proto → SQLite，24 种表 + version_{表名}）
  ├── binlog/db{idx}    ← binlog 缓存
  ├── snapshot/db{idx}  ← 快照暂存
  └── state.db          ← 全局状态
```

**启动两路径**：
```
localVer >= remoteMinVersion → FastStart（本地 SQLite 恢复 + 增量续跑，~10s）
否则                        → startFromSnapshot（CRS 分片 → MD5 校验 → shiftVersion×10000，1-5min）
```

**心跳 6 种 Pack**（agent 带 peerVer 拉取，broker 决策）：

| PackType | 条件 | 行为 |
|---|---|---|
| Uninitialized | peerVer < 0 | 全量 |
| UpToDate | peerVer == localVer | 跳过 |
| Outdated | diff > PackBinlogMaxSize(5000) | 降级全量 |
| BinlogIncomplete | peerVer ∉ [minVer, maxVer]（断档） | 切全量 |
| BinlogUpdate | 正常 | 增量 `(peerVer, localVer]`（不含 peerVer，防边界重复） |
| PackError | 错误 | 报错 |

**CheckJob 对账**：
- 周期：VPC 扩展 5 分钟 / 全局扩展 10 分钟 / FullCheck 全量；cron 凌晨 3 点起错峰。
- 对比：上游 ZK（RemoteGet）vs 本地 SQLite → more/less/update → 修复。
- 保护：修复 binlog 打 `Group="-1"`（区分正常 binlog）；`DelMoreThreshold=5000`；跳过最近写入对象（防假 diff）。

**拓扑与心跳**：
- broker 互相发现：ZK `/TVPC/AGENT_BROKER/WORKER/{partId}/{hostname:8000}`
- 心跳：每 30s 写 CRS `broker_{hostname}_heartbeat`；60s 判死（供 agent 重选 broker）
- 运维 API：`/workerIds`、`/partitions`、`/state`、`/versions`、`/configmap`

**agentos 框架机制**（Gen2 agent-broker 底座；主文档 §2.5 ★）：
- Extension 三段式：Upstream（拉取）/ Res（本地存储）/ Downstream（下发）
- 并发模型：单协程 + 3 个无界 channel（天然无锁）
- 版本编码：ShiftSteps=4 / ShiftNum=10000（"151707.0001" ↔ 1517070001）
- 重试：SQLite gorm callback 层（10 次）；单层 wrapper（非链式）
- 其他：binlog 解析器（文本行 → UniObj 反射映射）、快照解析器、inspector 对账、Prometheus/CLS

## §3 网关直连形态（the_2g_op）

- **为什么单独一条**：网关数量少但关键；主备倒换要"回放近期 binlog"（`do_switch`），直连 CKV+分区 ZK 更可控；且进程粒度快照本就是为网关/broker 定制。
- **模块**（vpc_agent Python 侧）：`the_2g_watcher`（分区 ZK 选择、白名单/ReadyZk/HostVpc watcher）、`the_2g_zk`（PartitionZkConfig）、`the_2g_op`（CKV 快照+binlog 合并到本地模型、do_switch 回放）、`the_2g_op_check`（对账入口）、`the_2g_unwatch`（批量 unwatch 冷 VPC）。
- **RS 发现**：`ckv_rs_watcher` 监听 CKV 服务 RS 列表（proxy_cl5/北极星解析写入 ZK）。

## §4 agent 双 ZK 共存与白名单切换

```
同一个 agent 进程同时维护：
  一代 ZK（始终在线）: /TVPC/HOST/{hostIp}、/TVPC/WHITELIST/2GNODE、2GSERVICE、ECHO_V2
  二代 ZK（按需建立）: /BINLOG/{vpcId}/...
  gRPC Stream（VPC5.0，按需）

对每个 VPC：
  在 2G 白名单 → 二代；在 5G 白名单 → gRPC；否则 → 一代
```

**切换 9 步**（Gen1 → Gen2）：
```
① 运维写 /TVPC/WHITELIST/2GNODE/{vpcId} = "1"
② Agent 一代 ZK 连接收到白名单变更回调
③ 发现 vpcId 进入白名单
④ 经 /TVPC/2GCTRL/{partition}/{zone} 找到 storage（zk|ckv|cdb）
⑤ 建立二代 ZK 连接
⑥ 拉取配置 → cache
⑦ ★ 全量 diff（一代状态 vs 二代状态）→ 以二代为准 → 逐条下发差异
⑧ 关闭该 VPC 的一代 Watch
⑨ 此后只从二代获取
```
> 为什么必须 diff：切换瞬间两代数据可能有微小差异，不能假设一致。

## §5 为什么是 Pull 而不是 Push

- 材料口径【实证】：Gen2 设备 Agent 是 Python 2.7 代码，不支持长连接 gRPC Stream；心跳 Pull 是 Agent 主动发请求（VPC5.0 换 Go Agent 后才改 gRPC 实时 Push）。
- 口径已闭合（Round 10）：设备 Agent = vpc_agent（Python，同一进程同一套代码）；agentos 是 broker 底座（主文档 §2.5 ★）。残余：代际表并列 "agentos (Go)"、04-others 称其 Gen3——见 06 §6。
- 无论语言，Pull 的设计收益独立成立：天然背压（agent 按自己节奏拉）、broker 不维护订阅状态、崩溃恢复只需带版本重问。

## §6 对账体系（三层闭环）

| 层 | 实现 | 对比对象 |
|---|---|---|
| 分发层（快照） | snapshot_differ | DB 重建快照 vs CRS 线上快照 |
| 分发层（broker） | CheckJob | 上游 ZK vs 本地 SQLite |
| 网元层 | agent 对账（inspector / the_2g_check） | 数据面实际态 vs 期望态 |

## §7 钩子

- 分区寻址与 ZK 族谱 → **05**。
- Pack/水位/幂等的推导与冲突裁决 → **06**。
