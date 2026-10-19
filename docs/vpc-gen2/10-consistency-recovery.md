---
title: "10-对账闭环与故障恢复"
series: "VPC 二代（Gen2）架构系列"
previous: "09-device-agent-echo.md"
next: "11-device-side-full.md"
source_refs:
  - "vpc/docs-by-topic/24-sdn_oss.md（Account 子系统）"
  - "vpc/docs-by-topic/23-nfv-proxy.md（snapshot_differ / file_differ）"
  - "vpc/docs-by-topic/16-AgentOS.md（CheckJob / inspector / FixStrategy）"
  - "vpc/VPC控制面.md（故障恢复章 / 附录时间表 / 分布式问题章）"
  - "vpc/docs-by-topic/09-VPC 控制器——架构的第一性原理.md（确认降噪参数——补遗 §3）"
---

# 10-对账闭环与故障恢复

> 标注：【实证】= 材料有据；【推导】= 材料无实现细节，按约束推导。

## §0 它干了什么（例子先行）

两个场景：

```
A. 快照漂移：CRS 里某 VPC 的路由快照与 DB 不一致
   → snapshot_differ 周期用 DB 重建快照 → 文件级 diff → 发现差异 → 告警/修复

B. nfv-proxy 崩溃：整组进程同时 suicide（防孤立消费）
   → watchdog 拉起 → init_zk（树）→ init_vpc（初始快照）→ binlog（从 offset 续）
   → merge（重建快照构建任务）→ ~30s-2min 恢复
```

## §1 掌握卡（5 条）

1. **四层对账各管一段**：DB↔ZK（sdn_oss Account）/ DB↔快照（snapshot_differ）/ ZK↔SQLite（CheckJob）/ 期望↔数据面（agent inspector）。
2. **修复只朝一个方向**：以更上游为准（DB 是权威）——统一原则【推导】。
3. **防震荡三件套**：删除保护阈值（DelMoreThreshold=5000）、跳过最近写入对象、频率递减（5min/10min/小时/天）。
4. **恢复链是"结构→数据→消费→合并"**：init_zk → init_vpc → binlog → merge（顺序不可换）【推导】。
5. **回退机制**：recovery_mode 回退一代（分 14 批重启守护进程，~90 分钟）。

## §2 四层对账

| 层 | 件 | 对比 | 频率 | 修复对象 |
|---|---|---|---|---|
| 控制面内部 | sdn_oss Account（GetExpected / GetActual / Account 三件套） | DB 期望 vs ZK 实际 | 周期 + 时间范围增量 | ZK 期望树 |
| 分发层（快照） | snapshot_differ（+ file_differ：sort/comm 文件级 diff） | DB 重建快照 vs CRS 线上快照 | 周期 | 告警为主 |
| 分发层（broker） | CheckJob | 上游 ZK vs 本地 SQLite | 5/10min + FullCheck | broker 本地镜像 |
| 网元层 | inspector + the_2g_check（port.py/proc.py vs dpdk_get/dpdk_objects） | 期望 vs 数据面 | 周期 | 数据面 |

- Account 细节【实证】：支持 dryrun、双确认删除、时间范围增量；单测 `tests/test_account/`；接入指南 `skills/db-zk-account-config/`。
- 【推导】为什么四层不打架：对比对、频率、修复对象各不相同；"以更上游为准"避免循环修复；"跳过最近写入"避免与正常变更流竞争。

## §3 修复策略（确认降噪【实证】 + 位标志 / CheckStrategy【推导】）

【实证】修复前的确认降噪（09 第一性原理补遗）：
- 参数：`ConfirmCount=3`（连续 3 次检测到差异才确认修复）/ `Interval=5 分钟`（采样周期）/ 重置阈值 > max(ConfirmInterval×2, Interval×2)——差异中途消失则计数重置（防瞬态）。
- 为什么不是 1 或 5：1 次→任何读取偏差立即误修复（#8 的 -1）；5 次→真差异要等 25 分钟；3 次 = 最少 15 分钟确认窗口，过滤瞬时噪声与恢复速度的折中。
- PID 类比（材料）：P=当前误差（不立即修）/ I=DiffHistory.DetectCount（累积确认）/ D=趋势重置（差异消失即清零）。

【实证】对账实现的更多细节（主文档 5.2-5.4 补遗）：
- 确认实现：DiffHistoryStore（LevelDB 存储）——key = `taskName:scopeKey:driver:objectType:objectId`；`IsConfirmed = DetectCount ≥ ConfirmCount`（默认 3）；`Cleanup`/`CleanupExpired` 管理记录。
- 策略链：ReconciliationPolicy（`Strategies[]` + `IgnoreStrategyErrors`）——6 种内置策略：Log / LevelDB（diff.result 持久化）/ RateLimit（>1000 条报错）/ Filter（白/黑名单）/ Composite（serial/parallel）/ Repair（task.Fix）；失败按逆序回滚已执行策略。（注：RateLimit 1000 与删除保护 `DelMoreThreshold=5000` 为两套阈值——前者限差异总量、后者限删除量。）
- 4 种 diff 模式：diff_set（无序集合差集）/ diff_identity（按主键 Hash 匹配）/ diff_ordered（LCS，少用）/ diff_composed（多子源合并去重——如 Rtable 合并 vmRTable + serviceRTable + remoteGw）。

材料线索：agentos `core/types.go` 有 FixStrategy（修复策略**位标志**）与 CheckStrategy（全量 / top / 最近变化）。

【推导】位标志的合理切分（每 bit 一个"允许动作"；位定义材料未给，待源码核对）：
- 允许加（修复 Missing）/ 允许删（修复 Redundant，受删除阈值约束）/ 允许改（修复 Mismatch）/ 只报告不修（dryrun）；
- 组合示例：常规 = 加 + 改 + 受限删；恢复期 = 放宽删除（材料亦有"恢复期删除阈值应更保守/更信任对账"的反思）。

【推导】CheckStrategy 三种与成本：
- FullCheck = 全量遍历（重、低频、凌晨错峰）；
- 最近变化 = 只查近期有 binlog 的 VPC（轻、高频）——与 VPCRECENTCHANGE 同源思路；
- top = 只查头部热点（折中）。

【推导】"跳过最近写入对象"的实现：给 res 写路径打"最近写入"时间戳（或 dirty 标记），对账 diff 时过滤该时间窗内的对象——避免"正常变更尚未落完 → 对账误判为缺失"。

## §4 恢复链（逐组件）

| 组件 | 故障 | 恢复 |
|---|---|---|
| nfv-proxy | 任一子进程异常 | **全组 suicide**（防孤立消费）→ watchdog 重启 → init_zk → init_vpc → binlog（committed offset）→ merge（重建任务）【~30s-2min】 |
| agent | ZK 锁 LOST | suicide → watchdog → 重建 ZK 连接（全量重读） |
| agent | 网络分区 | session expire → panic → suicide → 重启 |
| agent-broker | 进程崩溃 | 重启 → FastStart（本地 SQLite）或 Snapshot（CRS） |
| 母机 agent | 进程 Crash | watchdog + 冷启动（LevelDB 恢复；损坏则全量） |
| 网关 | 主备倒换 | do_switch 回放近期 binlog + HAStatus 更新 |

【推导】nfv-proxy 为什么"全组自杀"而不是各自重启：写侧（binlog）与读侧（merge）共享顺序假设；部分存活会出现"写停了、merge 还在清理"或"merge 停了、写继续堆"——回到一致基线比"部分可用"更安全，分钟级中断可接受。

【推导】恢复顺序为什么这样排（证伪检查修正）：这是**优化顺序而非正确性顺序**——binlog 消费即使先行也能工作，但存量 VPC 缺初始快照会让 merge 退化为全量重建（慢，且可能触发限流）；按 结构 → 基线 → 增量 → 派生 排，把代价压到最小。

## §5 回退机制

- **recovery_mode 回退一代**：分 14 批重启守护进程，~90 分钟——【推导】分批 = 避免全量同时重连的惊群/风暴。
- 回退与白名单配合：撤销 2G 白名单 → agent 回读一代；"解封禁后 recovery_mode 还在 → 不再消费二代 ECHO"（材料）——状态机化，防止半回退。

## §6 故障时间表（材料附录，选摘）

| 故障 | 恢复操作 | 时长 |
|---|---|---|
| VPCGW 152 台配置不一致 | 扩容 CRS + 人工修复 | 3h10m |
| recovery_mode 回退一代 | 分 14 批重启 | ~90min |
| HAVIP 误删 → 断流 | 回迁 HAVIP | 21min |
| QoS -1 下发全丢 | 等对账修复 | 6min |
| Kafka Topic 缺失 | 恢复 Partition + 重启 proxy | 分钟级 |
| vpc0 子网路由清零 | 重启 Agent | 单机重启时间 |

## §7 钩子

- 对账件在管道中的位置 → 03 §6 / 04 §6。
- 修复的版本语义（Group=-1、+1）→ 08 §4。
- 开放项与冲突裁决 → 06。
