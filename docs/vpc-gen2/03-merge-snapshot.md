---
title: "03-merge 管道与快照工程"
series: "VPC 二代（Gen2）架构系列"
previous: "02-write-path-binlog.md"
next: "04-consumption.md"
source_refs:
  - "vpc/VPC控制面.md（第二章 / 精确数据格式章：Redis 快照格式、ZK 树）"
  - "vpc/docs-by-topic/23-nfv-proxy.md"
  - "vpc/test-good/01-zk-distributed/architecture-evolution.md"
  - "vpc/docs-by-topic/08-VPC 控制器——分布式架构与分布式问题.md（四种锁）"
---

# 03-merge 管道与快照工程

## §0 它干了什么（例子先行）

一条 binlog 从"临时"到"快照"的一生：

```
① proxy_binlog 写入 ZK binlog 节点（02 篇）
② proxy_merge_master（单实例）扫 VPCRECENTCHANGE：发现 VPC 12345 最近变过
   → 决定它需要生成快照 → lpush 任务到 CRS pending 管道
   → 从 pending 取任务 + 计数限流 → 分发给 merge worker
③ proxy_merge（×5，rpop）：
   读 version_list 最新版 N → 读 ZK 中版本 > N 的 binlog
   没有新 binlog → 直接跳过（幂等）
   有 → 抢 /SNAPSHOT_LOCK/12345_rtable → 加载旧快照 → 逐条 apply(I/U/D)
   → 分片写 CRS → version_list 追加新版本（保留 4 版）→ 清理已合并的 binlog 组
④ 消费侧：agent/broker 冷启动读"最新快照 + binlog 补传"
```

**输入**：ZK 增量 binlog；**输出**：CRS 全量快照（+ 版本列表），并回收 ZK 空间。

## §1 掌握卡（5 条）

1. **merge 的对象是"已闭合组"**：闭合信号 = 后继组出现（版本单调）；水位 = 最后一个已闭合组的 trigger（活动组不动）——推导见 06 Q2。
2. **调度两段式**：master（单实例持锁）扫索引生成任务 → CRS pending 管道 → **计数限流**分发（治 vm_prop 压缩慢）→ worker rpop。
3. **幂等靠版本比对**：读 version_list → 读其后 binlog → 无新数据即跳过；同 (vpc,type) 用 ZK 锁互斥；任务重复无害。
4. **快照工程**：version_list 保留 4 版；分片 `{vpcId}_{ver}_{type}_{offset}`（页大小按类型 7000~50000）；worker 快照 `hbgw_worker_*` 是 SQLite .db 二进制（供 broker 冷启动）。
5. **ZK 锁的两个失败模式**（雪崩/假死）是后来迁 DB 锁的直接原因（背景，机制层面）。

## §2 调度：proxy_merge_master（单实例）

```
扫 VPCRECENTCHANGE（时间片桶，见 02 §6）
  → "决定哪些 VPC 需要生成快照"（全局视图，单实例做）
  → lpush 任务到 CRS pending 管道
  → 从 pending 取任务 + 计数限流（在飞任务 ≤ N，batch + sleep 风格）
  → 分发给 proxy_merge worker（rpop 消费）
```

- **为什么需要 master**：扫描是全局动作（多 worker 重复扫浪费 + 任务重复）；限流是全局成本控制（vm_prop 压缩耗时过长，无节制分发会打爆 worker）。
- **单实例保证**：持锁（原 ZK 锁，后迁 DB 锁）；master 崩溃 → 重启重建（任务幂等，无状态损失）。
- **任务粒度**：(vpc, type) 级；重复/漏发都由"版本比对"兜底。

## §3 执行：proxy_merge（×5）

```
rpop 任务
 1. 读 version_list 最新版 N（无 → 首次全量从 MySQL 拉）
 2. 读 ZK 中版本 > N 的 binlog（按版本区间，跨组）
 3. 无新 binlog → return（幂等关键）
 4. 抢 /SNAPSHOT_LOCK/{vpc}_{type}（临时顺序节点，防同 VPC 并发构建）
 5. 加载旧快照 → 逐条 apply：
      INSERT → 置；UPDATE → 改字段；DELETE → 删
 6. 分片写 CRS：{vpcId}_{ver}_{type}_{offset}（offset/fragments/group/ver）
 7. version_list 追加新版本，裁剪保留 4 版（旧分片 key 删除）
 8. 清理已合并的 binlog 分组（ZK 空间回收）
```

- **worker 分片**：5 个进程按 VPC 分片；37 种配置（代码口径 32 个硬编码 MERGE_CLASS）。
- **合并类语义**：每种类型一个 MERGE_CLASS（CVpcRouteTableSyn 等），决定"怎么把 binlog apply 进快照对象"。
- **R（Reload）动作**：触发 Agent 全量重读该 VPC；合并侧如何处理待源码核实（推断：等价全量重建或打标）。

## §4 两个 ZK 锁失败模式（为什么后来迁 DB 锁）

- **雪崩模式**：ZK 抖动 → 所有持锁/等锁的 merge worker 同时检测 `LOST` → 同时 `suicide()` → 全组雪崩。
- **假死模式**：Kazoo `start()` 无 timeout → ZK 故障时无限挂死（修复：`start(timeout=400)` + listener suicide）。
- **DB 锁的差异**：容忍 2 次心跳失败（30s）才自杀，给 DB 恢复窗口；锁状态可查（一条 SQL）。
- 迁移手法（背景知识）：双持锁 + 三阶段灰度（zk → dual → db），迁移期无双主。

## §5 快照格式（CRS）

**版本列表**：
```
key: {vpcId}_{type}_version_list
值: 序列化 proto vpc_snapshot {vpcId, verList[]}；保留最近 4 个版本（MAX_SNAPSHOT_NUM=4）
例: "12345_rtable_version_list" → [1000, 2000, 3000, 4000]
```

**数据分片**：
```
key: {vpcId}_{binlogVersion}_{type}_{offset}
值: 分片数据（offset/fragments/group/ver + 条目）
页大小按类型不同：rtable=50000 / subnet_rule,acl=25000 / vip_tgw=20000
                 config=7000~10000 / gw_config=12000
全局类型: {ver}_{type}_{offset}（无 vpcId 前缀）
```

**worker 快照（agent-broker 冷启动用）**：
```
hbgw_worker_snapshot_version          → 快照时间戳版本
hbgw_worker_snapshot_{ver}            → 分片元信息（JSON: workers → fragCount）
hbgw_worker_{ver}_{workerId}_{frag}   → SQLite .db 文件二进制
冷启动: 拉元信息 → 逐 worker 并行拉分片 → 落 snapshot/db{idx} → MD5 校验
        → migrateFromSnapshotDB（rename 快照表为正式表）→ shiftVersion（×10000）
```

## §6 兜底与周边

| 进程 | 作用 |
|---|---|
| proxy_lazymerge | **每小时**全量扫描：补"长期无变更但快照落后"的 VPC（低优先级）；聚焦 6 类公共快照（全局路由 / rtb_route / vm_prop + 3 类） |
| snapshot_differ | 用 DB 数据重建 VPC 快照 vs CRS 线上快照做**文件级 diff**，不一致告警 |
| append_snapshot | 在既有进程粒度快照上做增量追加（避免每次全量重建） |
| vpcgw_snapshot | 为 vpcgw/hbgw 每个 worker 生成进程粒度快照（INIT 模式做首次全量） |

> 两类快照产物别混淆：
> ① **per-VPC per-type 快照**（merge 产出，消费侧按 VPC 读）；
> ② **worker 粒度快照**（vpcgw_snapshot/append_snapshot 产出，broker 冷启动按 worker 读）。

## §7 钩子

- 组闭合判定、合并水位、12h 假设的完整推导 → **06 Q2/Q3/Q5**。
- 消费侧怎么用这些快照与 binlog → **04**。
