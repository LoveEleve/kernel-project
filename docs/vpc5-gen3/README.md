---
title: "VPC 5.0（Gen3）架构系列"
series: "VPC 5.0（Gen3）架构系列"
source_refs:
  - "vpc/test-good/02-vpc5-gen3/（12 篇核心：overview / business-capabilities / fusion 系列 / broker / tRPC / SET / failure-scenarios）"
  - "vpc/docs-by-topic/33-42（10 子项目组件文档）"
  - "vpc/test-good/05-cross-cutting/（29 篇横切原理）"
  - "vpc/interview/vpc5-面试文档/（30 篇面试口径）"
---

# VPC 5.0（Gen3）架构系列

> 腾讯云 VPC 控制面第三代（2024 起）：**完全去 ZK + Go 重写 + 微服务化**；10 个子项目、105 配置类型、54 Extension、SET 分片。
> 原则：机制有据标【实证】，推导标【推导】，材料不足标【开放】，**材料之外标【外部】**（公开知识/我的推导，可证伪，不与材料口径混用）；代际纪律（vpc5.0 材料中的 Gen1/Gen2 叙述仅作对比）。
> 姊妹系列：`docs/vpc-gen2/`（二代架构，已封卷）——三代对比与代际边界引用该系列 16/01 章。

## 它是什么（一句话）

Gen3 把"配置同步引擎"拆成微服务：`api-server（接入层：Saga+双库+binlog）→ fusion（合并+快照+对账+清理）→ broker（无状态扇出+tRPC 推送）→ host-agent（54 Extension→数据面）`，旁路 computation-agent 预计算；元数据驱动（105 配置类型零改动扩展）。**入口口径（勘误 2026-10-12）**：当前生产业务写入 = 控制台/云API → vpc-cgw → sdn_oss（VPC5.0 适配）→ Kafka protobuf → 上述链路；vpc-api-server 为迁移目标（详见 00 §3 尾注）。

## 文件索引

| # | 文件 | 内容 | 状态 |
|---|---|---|---|
| 00 | [00-overview.md](00-overview.md) | 全景：10 子项目 / 主链路 / 关键决策 / 105 类型 / SET 分片 | ✅ |
| 01 | [01-write-side-api-server.md](01-write-side-api-server.md) | 写侧：Saga / 版本 / 分发 / vpc-protos 元数据驱动 | ✅ |
| 02 | [02-fusion.md](02-fusion.md) | fusion 深挖：六模块 / 任务队列（8 Lua + 三段式）/ 快照结构 / 选举 | ✅ |
| 03 | [03-broker.md](03-broker.md) | broker：无状态扇出 / 三向合并 / tRPC Stream 四阶段 / Session 7 态（含 xDS 外部对照） | ✅ |
| 04 | [04-host-agent.md](04-host-agent.md) | host-agent：框架微内核 / 60+ Extension / Composer / 三层对账 / 迁移与恢复 | ✅ |
| 05 | [05-set-sharding.md](05-set-sharding.md) | SET 分片与跨 SET 迁移：优先级选主 / FlipEvent / 先建新后拆旧 / ShardId 间接层 | ✅ |
| 06 | [06-hard-problems.md](06-hard-problems.md) | 难点与事故：静默故障三案 / 两仓 ~210 工作项 bug 史 / Gen2 根因对照 | ✅ |
| 07 | [07-cross-cutting.md](07-cross-cutting.md) | 横切原理：一致性/顺序幂等/原子性/容错/背压容量/分区/可观测/发布迁移/测试（15 篇深读+16 篇一览） | ✅ |
| 08 | [08-interview.md](08-interview.md) | 面试口径：90 秒开场 / 三条线 / 险点兜底 / 沙盘 / Capstone / 行业对比（引用 Gen2 系列 07/16，不重复） | ✅ |
| 09 | [09-external-lens.md](09-external-lens.md) | 材料之外：业界坐标 / 边界评审 / 沙盘（【外部】） | ✅ |
| 10 | [10-scale-mechanics.md](10-scale-mechanics.md) | 规模化补遗：两级 SET 治理（D-x/R-x/观察窗口）/ Drainer 与状态机 / FixBinlogVersion / CA 深挖 | ✅ |
| 11 | [11-capstone.md](11-capstone.md) | 总纲：一页图 / 复刻指南（MVP≈7500 行）/ 决策账 / 不变量 / 坑单 | ✅ |
| 12 | [12-source-derivation.md](12-source-derivation.md) | 源码级推导（全【推导】）：两阶段 ready / GLOBAL 路径 / 旁路执行 / D-x 时序 / CA 一致性 / 双源分工 + 证伪清单 | ✅ |
| — | [review-log.md](review-log.md) | 审查记录 | ✅ |

## 掌握卡总览（7 条，详见 00 §1）

1. 定位：去 ZK + Go 重写 + 微服务；秒级生效 / 元数据驱动 / 独立对账清理 / 计算上移。
2. 10 子项目 = 6 服务 + 3 库 + 1 工具集。
3. 主链路（分发段）：`Kafka protobuf binlog → fusion/broker → tRPC Stream → host-agent → DPDK`；写侧入口（当前生产）= 控制台/云API → vpc-cgw → sdn_oss → Kafka；vpc-api-server = 接入层（迁移目标）；vpc-ops = 运维编排。
4. fusion（有状态重逻辑）vs broker（无状态轻逻辑）= LSMT SSTable/MemTable，故障域隔离。
5. vpc-protos 元数据驱动：105 配置类型（74/15/8/8）。
6. SET 分片：CtrlSetRouter 4 scope（GLOBAL 广播/VPC/OWNER/HOST）。
7. 配置 vs 元数据：api-server CRUD 管控制面自身元数据；业务配置走 binlog。

## 语料盘点（vpc5.0 相关）

| 语料 | 规模 | 处置 |
|---|---|---|
| resourcectrlset_priority + 05-cross-cutting 分片 | 318 行 | 已提取（→ 05：选主/翻转/迁移 + ShardId 三层一致）；capacity-model/migration-strategy → 07 |
| docs/vpc-fusion-Lua脚本-完整推断实现.md | 298 行 | 已提取（推断稿 → 02 §3）；38-vpc-fusion 目录级 |
| docs-by-topic 33/39 | 1726 行 | 33-framework 全读 → 04 §2 + 06 §4（specs 75 项）；39-host-agent 关键节全读 → 04 §3-§8 + 06 §4（specs 139 项）；34/41 → 01；36 → 03（排水/状态机/六类 bootstrap 细节 → 10）；38 → 02 |
| docs-by-topic 补读（05/06/08/12/19） | ~2500 行 | 08 分布式问题（锁失败模式/FixBinlogVersion/6-pack）、12 格式与常量表、19 CA 设计、05 数据面（对照 Gen2 系列 12）、06 监控运维 → 10 章 + 各章引用 |
| interview/vpc5-面试文档（30 篇，已读 23——08 轮 +14） | ~3600 行 | fusion 七专题 + ZK/机制/组件/分布题库（前批）→ 各章；自我介绍/必背30/模拟面试/业务追问/产品客户/系统设计/行业对比/逐链自测/追问二轮/收尾合并册/自我拷打/业务题库/broker 专题 → 08 全章；余 7 篇（投递 meta/网络深挖×2/分片迁移测试质量/故障场景手册/决策追问演练/挖掘路线图）→ 按需 |
| test-good/05-cross-cutting（31 篇，已读 15） | ~4970 行 | 横切原理——15 篇深读 → 07 全章；网络/测试/多租户等 16 篇一览（§11） |
| docs-by-topic 40/42 + test/8 | 649 + 546 行 + PPT×10 | 40-vpc-ops（运维系统）→ 10 章（D-x/R-x/观察窗口/权重灰度）；42-zenflow = AI 开发平台（与 VPC 无关，备查）；VPC3.0 官方 PPT → 备查 |
| test/8（VPC3.0 PPT ×10） | PDF | 官方系列——按需（对象模型/分片灰度/可观测） |
| 零散已读（Gen2 系列期间） | — | system-design / failure-matrix / tradeoff / vm-create-end-to-end / fix_trpc_unwatch——按章引用 |

## 阅读顺序建议

00（全景）→ 01（写侧）→ 02（fusion）→ 03（broker）→ 04（host-agent）→ 05（SET 分片）→ 06（难点事故）→ 07（横切）→ 08（面试）→ 09（外部对照）→ 10（规模化补遗）→ 11（总纲）→ 12（推导篇）。

## 系列状态

- **启动**：2026-10-11；**00-12 落盘**（15 文件 / 2538 行）；Round 1-17 审查/补遗/架构审计/推导审查通过；12 章为**源码级推导篇**（全【推导】+证伪清单——无源码工作区下的发散产物）。
- 工作方法沿用 Gen2 系列：深读 → deposit → review rounds（全量逐条）；格式 frontmatter + §0 例子先行 + 掌握卡。
