---
title: "00-全景：vpc5.0 是什么、10 个子项目与主链路"
series: "VPC 5.0（Gen3）架构系列"
previous: "README.md"
next: "01-write-side-api-server.md"
source_refs:
  - "vpc/test-good/02-vpc5-gen3/vpc5.0-overview.md（424 行全读：10 子项目/调用链路/关键决策/105 配置类型/54 Extension/SET 分片）"
  - "vpc/test-good/02-vpc5-gen3/vpc5.0-business-capabilities.md（414 行全读：74 业务配置/8 类场景/规模感/用户视角）"
  - "vpc/docs-by-topic/33-42（10 子项目组件文档，头部确认）"
---

# 00-全景：vpc5.0 是什么、10 个子项目与主链路

> VPC 控制面第三代（Gen3，2024 起研发）：**完全去 ZK + Go 重写 + 微服务化**；本系列逐域深挖。
> 标注：【实证】= 材料有据；【推导】= 按约束推导；【开放】= 材料不足。

## §0 它干了什么（例子先行）

用户在控制台创建一台 VM（10.0.1.71），这条配置在 vpc5.0 里的旅程（入口链已按勘误修正——见 §3 尾注）：

```
⓪ 写侧入口（当前生产）：控制台 / 云 API v3 → vpc-cgw（声明式 Action 网关：参数校验 + CAM 鉴权 + 协议翻译）
   → sdn_oss（Saga 业务编排、权威端；已加 VPC5.0 对象模型适配）→ Kafka protobuf binlog（VPC 5.0 通道，
   与 Gen1 ZK / Gen2 Ckafka 并列）。vpc-api-server（Saga 7 步 + 双库 + Redis Lua 发号 + CtrlSetRouter）
   是 VPC5.0 自建接入层：当前被 vpc-ops 等内部系统调用，是 sdn_oss 的迁移目标，不是控制台直连后端
① vpc-fusion（Go）：消费 binlog → 存 Redis 聚合 → 生成 LevelDB 快照 → 写 CDB/CRS
   → 发 Kafka 快照就绪通知（SnapshotReadyNotification）
② vpc-broker（Go）：fusion-sdk 直读 CDB/CRS 快照 + 拉 Kafka 增量 → 三向合并（BTree 版本索引）
③ tRPC Streaming（Handshake/Watch/Poll/Ack 四阶段双向流）→ 推给母机
④ vpc-host-agent（Go，母机 daemon）：vmrtable Extension → LevelDB（主存储）→ DPDK（Vpcsp 协议）
⑤ 流量通；Reconciler 持续对账（LevelDB vs DPDK）
```

**输入**：业务语义 API（当前生产入口 = 云API→cgw→sdn_oss；vpc-api-server 为迁移目标）。**输出**：母机 DPDK 表生效 + 两层对账保障。

## §1 掌握卡（7 条）

1. **定位**：Gen3 = 去 ZK + Go 重写 + 微服务；业务能力升级（秒级生效/元数据驱动/独立对账与清理/计算上移）。
2. **10 个子项目**：6 服务（api-server/fusion/broker/host-agent/computation-agent/ops）+ 3 Go 库（base/protos/agent-framework）+ 1 AI 工具集（skills）。
3. **主链路（分发段）**：`Kafka protobuf binlog → fusion/broker → tRPC Stream → host-agent → DPDK`；**写侧入口（当前生产）**= 控制台/云API → vpc-cgw → sdn_oss（VPC5.0 适配）→ Kafka；vpc-api-server = VPC5.0 自建接入层（迁移目标）；vpc-ops = 运维编排（不碰配置数据）；computation-agent 旁路预计算（FetchSnapshot）。
4. **核心拆分**：fusion（有状态/重逻辑：合并+快照+对账+清理）vs broker（无状态/轻逻辑：BTree+16 worker 扇出）——类比 LSMT 的 SSTable vs MemTable，故障域隔离。
5. **元数据驱动**：vpc-protos 用 txtpb 定义 **105 个配置类型**（74 biz + 15 control + 8 api_server + 8 fusion）；新增类型只改 1 个文件。
6. **SET 分片**：CtrlSetRouter 按 4 类 scope 路由（GLOBAL=广播 / VPC / OWNER / HOST）；跨 SET 迁移走 resourcectrlset 优先级。
7. **配置 vs 元数据**：vpc-api-server 有两类接口——**控制面自身元数据 CRUD**（SET/Kafka/Topic/策略）与 **genericConfig 业务配置通用 CRUD**（Saga → 双库 → Kafka binlog）；业务变更经 binlog 由 fusion→broker→agent 分发。**当前生产的业务写入入口仍是 sdn_oss**（已适配 VPC5.0 对象模型），vpc-api-server 为迁移目标。

## §2 为什么有 vpc5.0（Gen2 痛点 → Gen3 解法）

| 维度 | Gen1/Gen2 | Gen3 | 动机 |
|---|---|---|---|
| 配置生效延迟 | 分钟级（ZK Watch+diff） | 秒级（Kafka binlog + tRPC Stream） | 用户体验 |
| agent 重启恢复 | 30-40min / 秒级（CKV） | 秒级（LevelDB 本地缓存） | 惊群治理 |
| 对账 | 无系统化 / 依赖 ZK 一致性 | 独立 reconcile（全量每天 + 变动 5min）+ agent Reconciler（30+ 任务） | 一致性治理 |
| 配置清理 | 无独立模块 | 独立 cleanconfig（定时 + 事件触发） | 删除即停止 |
| 元数据扩展 | 硬编码（Gen2 32 种 MERGE_CLASS） | 元数据驱动代码生成（105 类型零改动） | 扩展成本 |
| Agent 框架 | 无统一框架 | 微内核 + Extension 可插拔（54 个） | 复用 |
| 运维编排 | 无统一中间层 | vpc-ops（ATE/Celery 异步） | 运维 |
| 计算上移 | host-agent 本地计算 | computation-agent 预计算 | 冷启动加速 |

> 一句话：**Gen3 = 去 ZK + 职责归位**——"去 ZK 不是去掉一致性，是把职责拆给更合适的组件：通知换成带内容的日志（Kafka binlog）、存储换成快照（Fusion+MySQL）、协调换成 TTL（Redis）"。不是 ZK 不好，是**角色错配**。

## §3 10 个子项目与依赖

```
6 服务:
  1. vpc-api-server   中心集群（多副本）  接入 + Saga CRUD + Kafka binlog 分发
  2. vpc-fusion       中心集群（Leader-Worker）  配置中转 + 快照 + 对账 + 清理
  3. vpc-broker       中心集群（按 SET 分片）  无状态扇出 + tRPC Stream
  4. vpc-host-agent   每台母机  daemon + 54 Extension → 数据面
  5. computation-agent 中心集群  预计算（rtable_expanded / vmentry_expanded）
  6. vpc-ops          Python（API + Celery worker + beat）  运维编排

3 库:
  7. vpc-base          16+ 公共包（HTTP/Logger/Kafka/Redis/Reconciliation/Watchdog/Metrics…）
  8. vpc-protos        元数据驱动代码生成（txtpb → proto + GORM + SQL + 桩代码；105 类型）
  9. vpc-agent-framework  Agent 微内核（DataSyncer + Extension + Storage + DataPlane + Reconciler）

1 工具集:
  10. skills           11 个 AI 开发辅助 skill（非运行时）
```

**依赖**：`vpc-base ← protos/api-server/fusion/broker/agent-framework ← host-agent/computation-agent`。

> **上游入口勘误（2026-10-12）**：vpc5.0 的"10 子项目"不含入口网关——当前生产的用户写入链路是 **控制台/云 API v3 → vpc-cgw（声明式 Action 网关：鉴权/限速/协议翻译）→ sdn_oss（Saga 业务编排、权威端；含 `api/Vpc5/` 对象模型适配）→ Kafka protobuf binlog（VPC 5.0 通道）**；vpc-api-server 是 VPC5.0 自建的接入层（repo 归档《VPC5.0-接入层详细设计》），当前被 vpc-ops 等内部系统调用、按"迁移切片"逐步接管 sdn_oss，**不是控制台直连后端**。来源：docs-by-topic 02 §1.2 / 03 / 24 / 37。

## §4 关键架构决策（为什么这么设计）

1. **为什么拆 fusion 和 broker**：fusion 既做快照又做推送时，母机连接数会拖慢快照构建——拆开后 fusion 慢只影响快照延迟、broker 挂只影响推送，**故障域隔离**。
2. **为什么有 computation-agent**：host-agent 冷启动要展开路由表（关联 ENI/Subnet/RemoteGw），大 VPC 本地展开拖慢冷启动 → CA 预计算（结果存 LevelDB）→ agent `FetchSnapshot` 一次拉取；响应带 `source_versions` 可无缝切回 broker 增量。
3. **为什么 tRPC 不用 gRPC/HTTP**：腾讯内部标准 + Polaris 服务发现集成 + 原生 server streaming（长连接推送）。
4. **为什么 LevelDB**：本地嵌入式、无需部署、适合母机受限资源；fusion（临时合并缓存）/broker（binlog 持久化）/agent（本地主存储）三处都用；健康检查失败降级内存。
5. **为什么 vpc-ops 用 Python**：复用 ATE（Celery）基建、不在热路径、延迟不敏感。
6. **为什么元数据驱动**：Gen2 加一种配置要改 10+ 处（DB Model/ZK 路径/merge/下发）→ Gen3 只改 1 个 txtpb。

## §5 与 Gen2 的对照（边界）

- 三代总对照表已在 **Gen2 系列 01 章 §4**（一代/二代/VPC 5.0 简表）；**16 章**为业界对比（Calico/OVN/K8s/大厂）——本系列不重复，只引用。
- 代际纪律：vpc5.0 材料中出现的 Gen1/Gen2 叙述仅作对比；机制一律以本系列为准。
- 面试口径中的"你的位置"（vpc-fusion bugfix/disorder 任务队列）→ 后续 06/08 章展开。

## §6 关键数字速查

| 项 | 值 |
|---|---|
| 配置类型 | 105（74 biz + 15 control + 8 api_server + 8 fusion） |
| Extension | **54**（目录口径：58 子目录 − 4；另有"55"口径——标【开放】） |
| 业务域 | 8 类核心场景（创建 VM/挂 ENI/CLB/改路由/改 ACL/绑 EIP/销毁 VM/对等连接） |
| 规模 | 广州 200 万 VPC × ~50 条 = ~1 亿条配置；每秒数十次变更；数万台母机 |
| SET 路由 | 4 scope（GLOBAL=1 广播 / VPC=2 / OWNER=3 / HOST=4） |
| 对账 | fusion reconcile（每天全量 + 5min 变动）+ agent Reconciler（30+ 任务） |
| 入口 | 控制台/云 API v3 → vpc-cgw → sdn_oss（当前生产；VPC5.0 适配）→ Kafka protobuf；vpc-api-server（接入层）= 迁移目标 |
| 协议 | cgw→sdn_oss TVPC/HTTP；binlog→Kafka（protobuf）；broker→agent tRPC Stream |

## §7 钩子

- 写侧（Saga 7 步/CtrlSetRouter/Redis Lua 发号）→ **01**；fusion 深挖（任务队列/快照/选举）→ **02**；broker（三向合并/tRPC 协议）→ **03**。
- host-agent（54 Extension/Composer/Reconciler）→ **04**；SET 分片与迁移 → **05**；难点与事故 → **06**。
