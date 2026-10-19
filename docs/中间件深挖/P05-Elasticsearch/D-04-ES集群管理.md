# P05-Elasticsearch D-04 — ES 集群管理（脑裂/备份/监控）

> **定位**：P05 检索线第 4 篇（收官）。三层齐：**原理 + 使用 + 源码（ES 8.12.2）**。
> **源码版本**：elasticsearch **8.12.2**（`code/spring/elasticsearch`）
> **质量标准**：源码清单 / 完整调用链 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：三个运维场景

```
① 网络分区（脑裂防护）：3 主节点集群中间断网 → 两边都选不出"多数派认可"的主
   → 少数派侧拒绝写（无 quorum 不服务）→ 网络恢复 → 自动重新收敛（无双主写冲突）
② 灾难恢复：误删索引 → 从昨日快照 restore（按索引恢复，重建分片）
③ 磁盘告警：某节点磁盘 96% → flood stage 只读锁（全集群写拒绝）→ 清理/扩容 → 解锁
```

**一句话**：ES 集群管理的三件事——**不让脑裂写（quorum 选主）、丢了能找回（快照）、提前能看见（监控+水位）**。

---

## 二、原理

### 2.1 选主与防脑裂（7.x 之后无 minimum_master_nodes）

```
term（任期）+ pre-vote（预投票：先问"我能当选吗"，避免 term 膨胀）
join 验证（JoinHelper）→ 多数派认可 → becomeLeader
防脑裂核心：VotingConfiguration.hasQuorum —— 没有多数派票数，选主/发布都不推进
自动 bootstrap：cluster.initial_master_nodes（首次启动用，之后靠投票配置自举）
节点角色：master-eligible / data / ingest / ml（生产 master 独立三节点）
```

### 2.2 集群状态发布

```
master 维护 ClusterState（version 单调递增：nodes + metadata + routing_table）
变更 → 发布给全集群（PublicationTransportHandler）→ 各节点 apply（落本地状态）
脑裂时分区少数派收不到新 version → 停留在旧状态 + 拒绝写（无主可跟）
排障助手：ClusterFormationFailureHelper——起不来时告诉你"缺谁的票"（看日志）
```

### 2.3 快照与恢复（备份）

```
仓库（Repository）：shared-fs / S3 / GCS / Azure（快照存远端，与集群解耦）
快照流程：SnapshotsService.createSnapshot → 各分片 SnapshotShardsService 并行做分片快照（增量：只存变化的段）
        → endSnapshot 收尾 → SnapshotInfo（含 shardFailures）
恢复：RestoreService.restoreSnapshot → 按索引重建（关闭→恢复分片→重开）；可恢复到别名/改名索引
SLM（Snapshot Lifecycle）：定时自动快照 + 保留策略（生产必配）
```

### 2.4 监控

```
节点级：MonitorService（Os/Process/Jvm）→ nodes stats/info API（os/jmv/fs/indices/thread_pool/breaker）
索引级：_cat/indices（docs/store）、_cat/shards（分片分布/UNASSIGNED 原因）
慢日志：index.search.slowlog / index.indexing.slowlog（阈值分级：trace/debug/info/warn）
集群健康：green/yellow/red（red=有主分片未分配——写不进去）
```

---

## 三、使用（生产运维口径）

| 场景 | 做法 |
|---|---|
| 部署 | 3 独立 master 节点；`discovery.seed_hosts` + 首次 `cluster.initial_master_nodes` |
| 备份 | SLM 每日快照 + 保留 N 天；恢复前先验证仓库可读（`/_snapshot/repo/_verify`） |
| 磁盘水位 | 85% 告警 / 90% 新分片不分配 / **95% flood stage 全集群只读**（先清后解锁） |
| 单节点 | 副本设 0（否则 yellow 常态，K16 实证）；生产至少 3 节点 |
| 慢查询治理 | slowlog 阈值 + Profile API 定位（哪个 shard/哪段慢） |
| 起不来 | 看 ClusterFormationFailureHelper 日志（缺票原因直接打印） |

---

## 四、源码核心（调用链 + 行号）

### 4.1 选主与 quorum

```
Coordinator（server/.../cluster/coordination/Coordinator.java）
  preVoteCollector（:154）→ joinLeaderInTerm（:600，becomeCandidate :607）
  → becomeLeader（:872）
VotingConfiguration.hasQuorum（.../coordination/CoordinationMetadata.java:347）
ElectionStrategy（.../coordination/ElectionStrategy.java:110，发布 quorum 判定）
Reconfigurator（.../coordination/Reconfigurator.java:140，变更配置的 quorum 检查）
ClusterFormationFailureHelper（:166/177，缺票诊断）
```

### 4.2 快照与恢复

```
SnapshotsService.createSnapshot（server/.../snapshots/SnapshotsService.java:266）
  → 分片级 SnapshotShardsService（SnapshotShardsService.java:563/575/583，sendSnapshotShardUpdate）
  → endSnapshot（SnapshotsService.java:1281，收尾 + shardFailures）
RestoreService.restoreSnapshot（server/.../snapshots/RestoreService.java:230/242）
```

### 4.3 监控

```
MonitorService（server/.../monitor/MonitorService.java:26-28：Os/Process/Jvm 三服务）
  → _nodes/stats、_nodes/info、_cat/* API 的数据源
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **quorum 选主（无脑裂写）** | 少数派也能选主服务 | 宁可不可用（拒绝写）也不双主写冲突——CP 取舍 |
| **pre-vote 预投票** | 直接加 term 竞选 | 分区恢复的节点反复拉高 term 搅局——预投票先确认"我有机会"才加 term |
| **快照只存增量段** | 全量拷贝 | 段不可变 → 未变段复用（快照小、恢复快） |
| **flood stage 只读锁** | 直接拒绝分配 | 先保集群不崩（只读），给运维清理窗口——熔断优先 |
| **SLM 定时快照** | 手动备份 | 备份是"纪律"不是"操作"——自动化+保留策略 |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **脑裂双主写** | 无 quorum 仍服务（老版本 minimum_master_nodes 配错）——7+ 自动投票配置；监控选主 term 跳变 |
| 2 | **单节点 yellow 常态** | 副本无法分配——副本设 0（K16 实证 + 巡检 cron） |
| 3 | **磁盘 95% 全集群只读** | flood stage——清数据/扩容后手动解锁（`cluster.routing.allocation` + 只读开关） |
| 4 | **快照仓库不可写** | 权限/网络——定期 `_verify` + 恢复演练（备份没演练=没备份） |
| 5 | **慢查询拖垮** | 无阈值——slowlog + Profile + 限流（search throttle） |
| 6 | **master 与 data 混部** | GC/IO 互相影响——生产 master 独立 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："ES 怎么防脑裂？"**（意图：选主机制）
term + pre-vote + join 验证 + voting quorum——无多数派不选主不服务；7.x 起自动投票配置替代 minimum_master_nodes。

**Q2："快照怎么做？恢复呢？"**（意图：备份链路）
仓库（远端）+ 分片级增量快照（只存变段）+ endSnapshot 收尾；恢复按索引重建（可改名）；SLM 自动化。

**Q3："磁盘满了会怎样？"**（意图：水位治理）
85% 告警 → 90% 不分配新分片 → 95% flood stage 全集群只读——熔断优先，给清理窗口。

**Q4："单节点 ES 生产注意什么？"**（意图：务实）
副本 0（否则 yellow）、备份照做、小规模可接受——但要明确"非高可用"的边界。

**Q5："慢查询怎么排查？"**（意图：运维）
slowlog 分级 + Profile API（看到哪个 shard 哪段慢）+ _cat/shards 看倾斜——先定位再优化（mapping/查询/分片）。

---

## 八、章节依赖

- **上游**：D-01（倒排——备份恢复的是这些文件）；D-02（分片——快照按分片做）；D-03（suggest/同义词——集群分发）
- **横链**：K03（xhs ES 使用/单节点口径）；K15（Prometheus 监控 ES exporter）；K16（ELK 日志链路 + yellow 巡检）
- **下游**：P04（分布式存储：Raft/复制对照——ES 选主与 Raft 的异同）

---

## 九、验证锚点（ES 8.12.2）

| 声明 | 位置（默认值已核） |
|---|---|---|
| 选主 | `server/.../cluster/coordination/Coordinator.java:154`（preVote）/`:600/607`（join+becomeCandidate）/`:872`（becomeLeader） |
| quorum | `.../coordination/CoordinationMetadata.java:347`（hasQuorum）；`ElectionStrategy.java:110`；`Reconfigurator.java:140` |
| 缺票诊断 | `.../coordination/ClusterFormationFailureHelper.java:166/177` |
| 快照创建/收尾 | `server/.../snapshots/SnapshotsService.java:266`（createSnapshot）/`:1281`（endSnapshot） |
| 分片快照 | `.../snapshots/SnapshotShardsService.java:563/575/583` |
| 恢复 | `server/.../snapshots/RestoreService.java:230/242` |
| 监控数据源 | `server/.../monitor/MonitorService.java:26-28`（Os/Process/Jvm） |
| 磁盘水位 low/high/flood | `.../cluster/routing/allocation/DiskThresholdSettings.java:34-36`（85%）/`:56-58`（90%）/`:78-80`（95%） |
