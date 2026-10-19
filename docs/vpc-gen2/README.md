---
title: "VPC 二代（Gen2）架构系列"
series: "VPC 二代（Gen2）架构系列"
source_refs:
  - "vpc/VPC控制面.md（第二章 二代 / 精确数据格式章 / 修正清单）"
  - "vpc/docs-by-topic/15-agent-broker、16-AgentOS、17-总体架构、23-nfv-proxy、28-vpc_agent、31-synchronizer、32-zk3.5.6"
  - "vpc/test-good/01-zk-distributed/architecture-evolution.md"
  - "vpc/test-good/00-background/core-objects.md（t_vpc_part 系列）"
---

# VPC 二代（Gen2）架构系列

> 腾讯云 VPC 控制面二代（2019~）：**Kafka 增量 binlog + 分区 ZK（只存临时 binlog）+ CRS 快照 + agent-broker 中间层**，设备侧按 VPC 白名单与一代灰度共存。
> 原则：机制有据标【实证】（材料出处），推导标【推断】；材料口径冲突统一在 06 裁决。

## 它是什么（一句话）

一代把 ZK 当"数据库 + 通知总线"（全量树 + Watch 全量 diff）；二代把 ZK **降级为"索引 + 临时增量"**，全量走 CRS 快照，变更走 Kafka binlog，母机侧加 agent-broker 做扇入——解决 Gen1 的 O(n) diff、惊群、重启慢、内存爆炸。

## 文件索引

| # | 文件 | 内容 |
|---|---|---|
| 01 | [01-overview.md](01-overview.md) | 二代全景：为什么有它、组件链路、三代关系、关键数字 |
| 02 | [02-write-path-binlog.md](02-write-path-binlog.md) | 写侧：sdn_oss → Kafka → nfv-proxy → 分区 ZK 树（分组/trigger/VPCRECENTCHANGE） |
| 03 | [03-merge-snapshot.md](03-merge-snapshot.md) | merge 管道与快照工程（master/worker/锁/分片/保留/清理） |
| 04 | [04-consumption.md](04-consumption.md) | 消费侧两条形态：agent-broker / 网关直连 / agent 双 ZK 切换 |
| 05 | [05-zk-infra-partition.md](05-zk-infra-partition.md) | 分区模型（t_vpc_part 系列）与 ZK 基础设施族谱 |
| 06 | [06-derivation-review.md](06-derivation-review.md) | 无源码推导 review：前提/Q&A/冲突裁决/负空间/开放项 |
| 07 | [07-interview-wrapup.md](07-interview-wrapup.md) | 讲法速查（30 秒 / 2 分钟 / 高频追问） |
| 08 | [08-agent-broker-internals.md](08-agent-broker-internals.md) | agent-broker 内部：NextGenRemoteGet / MergeUniObj / 版本模型 / 水位（含推导） |
| 09 | [09-device-agent-echo.md](09-device-agent-echo.md) | 设备侧 vpc_agent：角色 / watcher / 数据面 / echo mmap（含推导） |
| 10 | [10-consistency-recovery.md](10-consistency-recovery.md) | 对账四层闭环 + 修复策略 + 故障恢复链 + 回退（含推导） |
| 11 | [11-device-side-full.md](11-device-side-full.md) | 设备侧全量：watcher 全清单 / 对账全清单 / 主备双层锁 / DPDK 网关版 |
| 12 | [12-dataplane-protocol.md](12-dataplane-protocol.md) | 数据面协议：vpcko / DPDK / DFW / GRE 字段复用 |
| 13 | [13-zk-custom-family.md](13-zk-custom-family.md) | ZK 定制族深读：同步器 / 3.5.6 / differ / 管控 / 监控 / 备份 |
| 14 | [14-business-scenarios.md](14-business-scenarios.md) | 业务全景：20 个场景 × 四条承诺（业务 ↔ 机制映射） |
| 15 | [15-hard-problems-and-incidents.md](15-hard-problems-and-incidents.md) | 难点地图与生产事故库：22 起事故 × 7 类难点 × 5 种根因模式 |
| 16 | [16-open-source-comparison.md](16-open-source-comparison.md) | 业界对比：Calico/OVN、K8s、Andromeda/Hyperplane/VFP（为什么自建） |
| 17 | [17-ops-monitoring.md](17-ops-monitoring.md) | 运维与监控：Echo / Watchdog / 指标 / 告警 / 诊断（Ops 视角） |
| 18 | [18-first-principles.md](18-first-principles.md) | 第一性原理与复刻指南：为什么这么设计（定位/控制回路/关键抉择/优先级） |
| 19 | [19-ha-disaster-recovery.md](19-ha-disaster-recovery.md) | 高可用与容灾（深修版）：故障域 / failover 四竞态 / 四种锁失败模式 / 崩溃恢复 / 分区容忍 / 五薄弱点 / AZ 与多地域 |
| — | [review-log.md](review-log.md) | 审查记录（Round 1-24 + 补课批次 P0/P1/P2） |

## 掌握卡总览（16 条）

1. **链路**：`sdn_oss（Saga/Outbox）→ Ckafka → nfv-proxy（14 进程）→ 分区 ZK（binlog 增量）+ CRS（快照）→ agent-broker → 母机 agent / 网关直连`。
2. **ZK 只存临时 binlog**：`/BINLOG/{vpcId}/{TYPE}/{group}/{version_...}`；group = 版本 // GroupSize（RTBROUTE/SERVICE/GLOBALROUTE=500，其余=1000）；每组 `trigger` = 组内最大版本。
3. **14 种 binlog 类型**（0=RTABLE … 13=GW_CONFIG_MTU）；binlog 字符串 = `version_action_fields...`；action = I/D/U/R。
4. **VPCRECENTCHANGE 是反向索引**：分钟键时间片桶 + ≥5000 子节点提前滚动 + 持久节点 + 发布保护（9:30-9:39）；merge master 扫它找"哪些 VPC 变了"。
5. **merge = 增量压成全量**：master（单实例）扫索引 → CRS pending 管道 → 计数限流分发（治 vm_prop 慢）→ worker（×5）rpop 合并；幂等靠"版本比对 + 无新 binlog 跳过"。
6. **12h 是"拒写"不是"保留"**：超 12h 的 binlog 拒绝写入 ZK——本质是"合并延迟 < 12h"的显式假设。
7. **快照工程**：version_list 保留 4 版；分片 `{vpcId}_{ver}_{type}_{offset}`（页大小按类型 7000~50000）；worker 快照 `hbgw_worker_*`（SQLite .db 分片，供 broker 冷启动）。
8. **agent-broker（Go/agentos）**：分片 `vpcId % 500 % 15` + GlobalWorker(15)；启动 FastStart（本地 SQLite）或 Snapshot（CRS+MD5+版本移位）；心跳 6 种 Pack；CheckJob 对账（5/10min）。
9. **消费两条形态**：母机走 agent-broker TRPC 心跳 Pull；网关（vpcgw 等）直连 CKV+分区 ZK（the_2g_op，支持 do_switch 回放）。
10. **灰度**：白名单 `/TVPC/WHITELIST/2GNODE|2GSERVICE|2GHOST`；切换做全量 diff（以二代为准）；agent 双 ZK 连接共存。
11. **业务四条承诺**：创建即用（异步 binlog+幂等）/ 规则即生效（路由展开）/ 销毁即停止（删除保护+对账修复）/ 业务不中断（主备+心跳+对账）——20 个场景速查与排障六步见 14。
12. **难点与事故**：7 类难点（规模×正确性…可观测与静默故障）；22 起生产事故根因分布（并发 23%/状态 18%/依赖 18%/防御 18%）；检测只覆盖 40%；重启成本 30-40 分钟——见 15。
13. **业界对比**：Calico（BGP 无租户）/ OVN（OVSDB 上限 10 万实体 vs 1.6 亿）/ K8s（Watch+Reconcile vs Event Sourcing+定时对账）/ Andromeda（Spanner→无对账）/ Hyperplane（Cellular）/ VFP（统一流表）——见 16。
14. **运维监控栈**：Echo/Kicker 探针广播、三层 Watchdog 链（自杀+重启）、mmap 双通道、tslog 指标、三层告警、ECHO SOP 与 65358 诊断接口——见 17。
15. **第一性原理**：定位（配置同步引擎非 SDN 控制器）、闭环控制回路（对账=核心组件）、四个关键抉择（推送+对账/Saga/Kafka 不直推/灰度共存）、复刻优先级 P0-P2 与六个坑——见 18。
16. **高可用与容灾**：故障域五层；主备 failover（同机双进程 fcntl/3-10s/四竞态）；四种锁失败模式（ZK 自杀雪崩/Redis 无 fencing/MySQL gap lock/inode 陷阱）；崩溃恢复对比；分区容忍；五薄弱点；ZK 容灾三件套；RTO/RPO——见 19。

## 语料盘点（2026-10-11，未纳入正文者与去向）

| 语料 | 规模 | 处置 |
|---|---|---|
| test-good/05-cross-cutting（29 篇） | 5200+ 行 | 面试官视角横切（指标/缓存/降级/容量/对账…）——**vpc5.0 口径**（vpc_fusion_* 指标、Fusion/Broker 7 层缓存），归 vpc5.0 系列 |
| test-good/02-vpc5-gen3（12 篇） | ~6600 行 | vpc5.0 全系列（fusion/broker/trpc/迁移），归 vpc5.0 系列 |
| docs-by-topic 33-42 | — | vpc5.0 组件 + 周边工具（zenflow 等）——归 vpc5.0 系列 |
| test-good/00-background 概念文档（03-10） | ~1800 行 | 主张级审计（05-10 抽查）：无机制冲突；新增 vgwType 旧口径见证 + EIP SNAT 位置冲突 → 14 §9；场景层已由 14 覆盖 |
| vpc-core.md / same-vpc-communication.md | 562 行 | 数据模型三关键已提取（→ 14 §2）；same-vpc-communication 全文备查 |
| docs-by-topic 08 | 590 行 | 全读：failover 四竞态 / 四种锁失败模式 / 崩溃恢复 / 分区容忍 / 五薄弱点 → 19 深修版 |
| docs-by-topic 10 | 637 行 | 已提取（agentos 真实结构/版本细节 → 08 §3/§7；Saga 回滚失败/Go-Python 分界 → 02 §9.5） |
| docs-by-topic 04 | 739 行 | 部署拓扑 §3 已提取（→ 19 §4/§5）；DDL / Topic / 配置样例备查 |
| docs-by-topic 06/07 | 1251 行 | 已提取（监控运维 → 17；三代对比修正清单 → 08/06 对照） |
| docs-by-topic 09/13 | 1008 行 | 已审计（第一性原理 → 18 + 10 §3；可复现性边界 → 18 §7） |
| test-good/01-zk-distributed（zk-knowledge / zk-learning-path） | 832 行 | zk-knowledge 已审计（内存公式 → 01 §2；/syncSlave 路径 → 13 §2）；zk-learning-path 为教学路径，不提取 |
| reference/ | ~1200 行 | 遗漏内容补充已提取（→ 14 §5.5/15 §6）；速查表（配置类型/错误码）备查 |
| VPC控制面.md（主文档） | 24717 行 | 多文档聚合（八部分+内嵌多篇）；按章节提取，附录 B 关键数字已核（"nfv-proxy 13 类"为旧口径，系列按更正版 14 类）；5.x/6.2 已审计（→ 10 §3 / 15 §6 / 18 §3）；剩余：API 层 / 事务编排 2.5-2.9 章节 |
| papers/（9 篇）/ test/8（VPC3.0 PPT） | — | 背景论文 / vpc5.0 材料 |
| docs-by-topic 18-22/25/26/29 | — | 周边系统（CodeSense/DataWorkspace/MCP-Proxy/NetSense/Skills/Synapse/sflow）——出界 |
| test-good/03-interview | — | 面试问答/简历语料（非知识正文） |

## 阅读顺序建议

先 01（全景）→ 02（写侧）→ 03（合并）→ 04（消费）→ 05（基础设施）→ 06（推导与冲突）→ 07（讲法）→ 08/09/10（内部机制、设备侧、对账恢复）→ 11（设备侧全量）→ 12（数据面协议）→ 13（ZK 定制族）→ 14（业务全景）→ 15（难点与事故库）→ 16（业界对比）→ 17（运维与监控）→ 18（第一性原理）→ 19（高可用与容灾）。

## 系列状态（收尾 · 2026-10-11）

- **封卷**：19 章 + README + review-log，共 21 文件 / ~3690 行；**Round 1-23 全部闭环**（20 轮增量审查 + 收尾 / 补缺 / 深修）。
- **覆盖**：二代全链路——写侧（02）/ 合并快照（03）/ 消费（04）/ 分区与 ZK 基建（05）/ broker 内部（08）/ 设备侧（09/11）/ 数据面（12）/ ZK 定制族（13）+ 业务（14）/ 难点事故（15）/ 业界对比（16）/ 运维监控（17）/ 第一性原理（18）/ 高可用与容灾（19）；推导与冲突裁决（06）、讲法（07）。
- **语料**：vpc 素材库全部可读语料已审计或提取（去向见上表）；剩余登记项（主文档 API 层 / 事务编排 2.5-2.9、docs-by-topic 01-03、docs-by-topic 04 的 DDL/Topic/配置样例、速查表、same-vpc-communication）为可选深挖，不影响完整性。
- **开口总账（06 §6）**：闭合 2 + 口径冲突 3（more/less、uniqVpcId、EIP SNAT）+ 参数级 5——全部需源码核对（工作区无源码）。
- **下一步**：vpc5.0 系列（材料已盘点：test-good/02-vpc5-gen3、05-cross-cutting、docs-by-topic 33-42）。
