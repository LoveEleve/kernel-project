---
title: "11-总纲（Capstone）：一页图与复刻指南"
series: "VPC 5.0（Gen3）架构系列"
previous: "10-scale-mechanics.md"
next: "12-source-derivation.md"
source_refs:
  - "vpc/docs-by-topic/13-VPC 控制器——可复现性评估与最小可行搭建指南.md（不可移植障碍/官方测试路径/MVP 方案/移植分级——全读）"
  - "本系列 00-10 全章（决策账/坑单/不变量汇总）"
---

# 11-总纲（Capstone）：一页图与复刻指南

> 终章：把 00-10 收进一张图 + 一份"从零复刻"的操作指南。决策账、坑单、不变量全部汇集于此。

## §0 一页图（vpc5.0 全景，勘误后口径）

```
┌─ 用户与上游 ─────────────────────────────────────────────────────┐
│ 控制台 / CLI / 云 API v3            Defensor（变更平台）           │
└───────┬──────────────────────────────────┬──────────────────────┘
        │                                  │ 运维治理（不碰配置数据）
        ▼                                  ▼
  vpc-cgw（声明式 Action 网关：         vpc-ops（Python/ATE —— 两级 SET 治理：
  参数校验+CAM 鉴权+协议翻译）          D-x 分发层/R-x 副本、权重灰度、观察窗口审批）
        │ TVPC                              │ call_tvpcapi()
        ▼                                   ▼
  sdn_oss（Saga 业务编排·权威端│       vpc-api-server（VPC5.0 自建接入层：
  含 api/Vpc5/ 对象模型适配）  │       对象模型 CRUD+Saga+双库+Kafka——迁移目标）
        │                     └───────────────┘（迁移切片逐步接管）
        │ Kafka protobuf binlog（VPC 5.0 通道；无 partition key——负载均衡优先）
        ▼
┌─ 分发层（分发层 SET：Kafka+副本 db/redis/fusions）──────────────────┐
│ vpc-fusion（有状态中枢）：init / message / snapshot / reconcile /  │
│   cleanconfig / vpcapi；Redis 任务队列（8 Lua+租约）；快照 →        │
│   t_generic_snapshot + CDB/CRS；对账 report-only                    │
└───────────────┬─────────────────────────────────────────────────────┘
                │ SnapshotReady 通知 / broker 直读
                ▼
┌─ 扇出层（Broker SET）───────────────────────────────────────────────┐
│ vpc-broker（无状态）：三棵 BTree 三向合并（版本连续对齐）→ Dispatcher │
│   单协程 FIFO+16 Worker → tRPC SyncService（Handshake/Watch/Poll/   │
│   稀疏 ACK）；服务状态 Ready/Accepting/Serving/Draining+Drainer     │
└───────────────┬─────────────────────────────────────────────────────┘
                │ tRPC Stream（protobuf over gRPC）
                ▼
┌─ 端侧（母机）───────────────────────┐   ┌─ 预计算（设计阶段）──────┐
│ vpc-agent-framework → vpc-host-agent │   │ computation-agent（CA）  │
│ 54 Extension / Composer / DPlane /   │◀──│ FetchSnapshot（rtable/   │
│ L1-L3 对账 / SHADOW / quick-restart  │   │ vmentry 展开）+source_ver │
└───────────────┬──────────────────────┘   └──────────────────────────┘
                ▼
     数据面三驱动：vpcko（内核 netlink）/ DPDK（TCP6666 protobuf）/ fake

对账环：fusion reconcile（两态 Diff）⇄ agent Reconciler（三层）→ "差异可见、收敛可证"
版本链：MySQL per-scope 版本 → Kafka offset → 快照 version → Session LocalVersion（连续性对齐）
```

## §1 掌握卡（7 条——全系列载荷决策）

1. **价值主张**：200 万 VPC / 亿级配置，**秒级生效 + 可证明的准确**（对账即"证明"）。
2. **五条主决策**：去 ZK（职责归位）/ fusion-broker 拆分（故障域）/ 元数据驱动（105 类型零改动）/ 两级 SET（内容×连接两维扩容）/ 对账兜底（AP 的账单）。
3. **一致性骨架**：per-scope 版本单调 + 连续对齐 + at-least-once + 幂等三层 + 对账只报告。
4. **治理即架构**：扩容/灰度/退役/审批都是"被流程化的变更"（D-x/R-x/观察窗口/排水）。
5. **顺序性真相**：无 partition key 是决策；纠正 = 快照全量 + 迟到重编号（10 §4）。
6. **恢复模式**：fusion"近乎无状态"（任务在 Redis）/ broker 完全无状态 / agent 快照秒恢复 / CA 冷启动加速。
7. **诚实的边界**：CA 无代码、Gen3 未全量灰度、参数多处【开放】——"把可交付的做到可交付，把不知道的标出来"。

## §2 复刻指南（从零搭一套等价系统）

### 2.1 不可移植的障碍（先认清）【实证，13 号】

- **数据面**：vpcko 内核模块 + DPDK PAL 均**无源码**（绑定 TLinux 3.10 / 私有 Protobuf magic 0x20161206）；
- **老 Agent**：Python 2.7 依赖内核模块名启动（Ubuntu 直接 raise）；
- **内部服务**：Polaris/CAM/Barad/CLS/XingYun/Adam——**均可替换**（静态 IP/JWT/Prometheus/Loki/Alertmanager/systemd）。

### 2.2 可复现边界（移植分级）【实证，13 号】

| 级别 | 内容 | 处置 |
|---|---|---|
| L1 纯逻辑（直接参考） | Saga / Outbox / 对账多次确认降噪 / Composer / 声明式 Action | 照搬模式 |
| L2 换组件（中等） | ZK Watch→etcd、服务发现→DNS、鉴权→JWT、监控→Prometheus | 语义等价替换 |
| L3 重写（大量） | 内核数据面→VXLAN/OVS/eBPF、DPDK→标准 DPDK、代码生成管线 | 架构思路可参考 |
| L4 不可复现 | vpcko 精确行为、PAL 完整协议、内部基建集成 | 放弃 |

**官方测试路径可在 Ubuntu 跑**：fake 驱动（只记录不下发）+ mock-broker + go-mysql-server + miniredis + mock Kafka——"E2E 全链路，最后一步不落内核"。

### 2.3 最小概念验证（MVP）【实证，13 号】

- **架构简化**：Go API Server（简化 Saga，~2000 行）+ Go Agent（Extension 模式，~3000 行）+ Reconciliation Engine（~1500 行）+ Schema Validator（~1000 行）≈ **7500 行**；
- **依赖替换**：ZK→etcd（`/vpc/config/...` 树 + binlog 按版本排序）；Kafka→Redis Streams；
- **MySQL 5 张表**：vpc/subnet/route/ACL + 1 张 **Outbox**（`t_binlog`：send_state 0待发/1发送中/2已发——含"发送中死锁需 Mediator 重置"的坑位）；
- **对账引擎**：Scheduler（5min 增量 / 每 288 次全量 ≈ 每天）+ **ConfirmCount≥3 连续确认才修复** + DiffHistory 降噪；
- **验收测试**（9 步）：建 VPC → 加路由 → Agent 生效 → **手动篡改 fake 驱动** → 触发对账 → 断言修复回正确值。

### 2.4 复刻顺序（依赖拓扑）

```
vpc-base / vpc-protos（或自研 schema 管线）
   → vpc-api-server（写侧：Saga→双库→Kafka）
   → vpc-fusion（消费→快照→对账→清理）
   → vpc-broker（BTree→合并→Stream 扇出）
   → vpc-agent-framework → vpc-host-agent（Extension→DPlane→驱动）
   → （可选）computation-agent → vpc-ops（治理）
```

## §3 决策速查表（决策 × 理由 × 代价）

| 决策 | 理由 | 代价 |
|---|---|---|
| AP + 对账（非 CP） | 10 万+ agent 不可能全在线 | 对账 + 人工兜底 |
| Producer 无 partition key | 防热点 VPC 打爆单分区 | 乱序（靠快照+重编号纠正） |
| fusion/broker 拆分 | 故障域隔离（快照 vs 推送互不拖累） | 多一跳、两套部署 |
| 元数据驱动 | 105 类型零改动扩展 | 代码生成管线复杂（L3） |
| 两级 SET | 内容规模 × 连接规模两维独立扩容 | 治理复杂度（D-x/R-x/审批） |
| Redis 任务队列 | 任意 Worker 抢单 + 租约 + 优先级 | "任务可重建"是前提 |
| LevelDB（非 RocksDB） | 单线程写场景够用、资源省 | compaction 阻塞写（量小可控） |
| fail-stop（不降级） | "配置可延迟，不可错乱" | 可用性（Redis 挂=调度停） |
| 限频 10/s（发布侧） | 保护下游 + 用户操作延迟<1s | 首启 27.8h |
| ownership 三重校验（Lua 内） | 防旧 Worker 复活越权 | 脚本复杂度（核心是 claim/finish） |
| 对账只报告不执行 | 误报变真删的风险>收益 | 修复靠正常路径+规则 |

## §4 不变量清单（复刻验收 checklist）

1. per-scope 版本单调 + **连续性对齐**（max(快照侧版本)+1 ≥ Kafka 未对齐最小版本）；
2. at-least-once + **幂等三层**（message=version+scopeId / 任务=业务键 / 快照=同 scope+version 重建）；
3. **对账只报告**（Missing/Redundant/Mismatch），执行归 binlog 路径/cleanconfig；
4. 乱序不丢：快照全量纠正 + 迟到重编号；
5. **先建新后拆旧**（迁移）/ **先排水后退役**（SET）；
6. **fail-closed**（broker 没对齐好不接客）；
7. 缓存即真相的**可重建性**（DAO 防御封直写）；
8. 任务租约三段式（30s / 10s / 扫描≤60s，最坏 ~90s 回收）。

## §5 最容易搞错的十个坑（全系列教训单）

1. `Put(key,nil)` ≠ 删除（观测点要测"遍历为空"）；
2. 无 key 乱序时**丢弃=丢数据**（要纠正不要忽略）；
3. channel 满**不阻塞=丢消息**（宁可慢不可丢）；
4. 心跳只证明进程活着——**僵尸任务要 Timeout + watchdog 两层**；
5. 租约续期**别刷 StartedAt**（只刷过期时间）；
6. 告警阈值"告多就调高"= 掩耳盗铃（**趋势告警**）；
7. canary 只能抓代码级 bug，**抓不了规模级 bug**（全量对账做后验）；
8. 回滚窗口**跟着对账周期走**（72h=2 周期）；
9. 缺陷：防御机制反噬（删除保护误伤恢复 / 回退自伤）；
10. 外部输入不校验=写穿（0.0.0.0 类事故）。

## §6 系列地图（11 章）

| 章 | 一句话 | 必读节 |
|---|---|---|
| 00 全景 | 10 子项目 + 入口勘误 + 主链路 | §0/§3 尾注 |
| 01 写侧 | Saga 7 步 / 双库 / 版本 / 分发三件套 | §3/§4/§5 |
| 02 fusion | 任务队列 / 快照 / 对账 / 分支态注记 | §3/§5/§8/§9 |
| 03 broker | 三向合并 / tRPC 四阶段 / 7 态 | §4/§6 |
| 04 host-agent | framework / Extension / DPlane / 三层对账 | §4/§6/§7 |
| 05 SET 分片 | 选主纯函数 / 迁移四阶段 / **两型 SET** | §2/§3 |
| 06 难点事故 | 静默三案 / 两仓 bug 史 / Gen2 根因对照 | §3/§4/§5 |
| 07 横切 | 一致性 / 幂等 / 背压 / 容量 / 发布 | §2/§3/§9 |
| 08 面试 | 90 秒开场 / 险点兜底 / 沙盘 | §3/§4/§6 |
| 09 外部 | 业界坐标 / 边界评审（【外部】） | §3 六条 |
| 10 规模补遗 | 两级 SET 治理 / Drainer / 重编号 / CA | §2/§3/§4 |

## §7 边界（【开放】总账）

- 数字口径：配置类型 105 vs 116；Extension 54 vs 57；
- 选举：Key 前缀 `leader:election:`（倾向）/ TTL 10s·3.3s（代码默认）vs 15s·5s（面试卡）；
- CA：**无代码**（6600 行设计文档）；BypassExecutor 仅知识库目录级；
- 分支态：Hash Tag / 8 Lua / 三段式 / ownership 均为 bugfix/disorder 分支（master 未合入——引用注明）；
- 入口：当前生产 = cgw→sdn_oss→Kafka；api-server 承接用户写入属**迁移完成后**（17 号矩阵目标态）；
- Gen3 灰度：0%（建设期）——对外表述纪律见 08 §1。
