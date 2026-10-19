---
title: "05-SET 分片与跨 SET 迁移：优先级选主 / FlipEvent / 先建新后拆旧"
series: "VPC 5.0（Gen3）架构系列"
previous: "04-host-agent.md"
next: "06-hard-problems.md"
source_refs:
  - "vpc/test-good/02-vpc5-gen3/resourcectrlset_priority.md（172 行全读：选主算法/翻转/迁移/8 工程问题/测试）"
  - "vpc/test-good/05-cross-cutting/02-control-plane/data-sharding.md（146 行全读：ShardId/三层一致/静态分片）"
  - "vpc/interview/vpc5-面试文档/专题-分片迁移与测试质量.md（Round 5 已读：迁移四阶段/跨 SET 步骤/测试反思）"
  - "vpc/test-good/02-vpc5-gen3/vpc5.0-overview.md（§7 SET 分片）"
---

# 05-SET 分片与跨 SET 迁移：优先级选主 / FlipEvent / 先建新后拆旧

> Gen3 的扩容单元是 **SET（ResourceCtrlSet）**：broker 按 SET 分片部署；VPC 在 SET 间**灰度搬迁**靠本套机制。
> 标注：【实证】/【推导】/【开放】/【外部】。

## §0 它干了什么（例子先行）

灰度搬迁一个 VPC（vpc-gu5knkob）从 SET-A 到 SET-B 的旅程：

```
① 控制面：给该 VPC 新增 SET-B 绑定（resourceCtrlSet 数据下发到 agent）
② agent 的 ResourceCtrlSet Extension：收到新数据 → 纯函数选主
   priority 降序 + UniqCtrlSetId 字典序 tiebreak → Winner 从 SET-A 翻转到 SET-B
③ 产生 WinnerFlipEvent{Old: set-a, New: set-b} → 三层通知
   Extension → SetProvider → DataSyncer
④ MigrateBizScope：先建新（watchOnNewSet：带本地 version 增量 Watch）
   → 后拆旧（unwatchOnOldSet）——事件空窗 = 0
⑤ 重叠窗口的重复事件：dispatcher version 单调 + Extension 幂等收敛
```

## §1 掌握卡（6 条）

1. **SET = 分片单元**：`VPC ID → CtrlSetId（ResourceCtrlSet）→ Broker 地址（CoreCtrlSet）`；广州 200 万 VPC 单集群扛不住 → 多 SET。
2. **选主是纯函数**（priority 降序 + UniqCtrlSetId 字典序 tiebreak）——**不是分布式协商**：所有 agent 收到相同数据、独立算出相同赢家。
3. **翻转产生 FlipEvent**（三场景：新出现/消失/真翻转）→ **三层通知保分层**（Extension→SetProvider→DataSyncer）。
4. **迁移原则"先建新后拆旧"**：反过来留事件空窗；重叠窗口靠 version+幂等收敛。
5. **分片键是 ShardId 间接层**（非 VPC ID）：`resource_obj` 存映射；MySQL 多表/LevelDB 前缀/Cache 四聚合模式——同 VPC 三层同 shardId；**静态分片**（扩容靠跨 SET 迁移）。
6. **跨 SET 迁移 = shard 重映射 + Watch 迁移**：5147 行/15 commit/4 天；测试:代码 ≈3:1。

## §2 SET 是什么

```
VPC ID ──→ CtrlSetId ──→ Broker Addresses
         (ResourceCtrlSet)   (CoreCtrlSet)
```

- **动机**：单 broker 集群容量不够（200 万 VPC）；按 SET 分片，每个 SET 服务一部分 VPC。
- **SET 两型（补遗对齐）**：`t_ctrl_set.type` 有 1=**分发层 SET**（Distribute Set）/ 2=**Broker SET**；VPC 绑定做在 **Broker SET** 维度（`t_resource_ctrl_set.ctrl_set_id → t_ctrl_set(BROKER_SET)`）——本篇讲的是这一侧；分发层 SET（Kafka + 副本 db/redis/fusions）的治理/灰度/排水见 **10 §2**。
- **路由**（00 §）：CtrlSetRouter 按 4 类 scope（GLOBAL 广播/VPC/OWNER/HOST）把 binlog 路由到目标 SET。
- **绑定数据**：resourceCtrlSet（VPC↔SET 多对多，灰度期一个 VPC 同时绑旧+新 SET）；owner_policy_resolver（owner 维度策略）；globalResourceBinding（vpc_-1/-3、default_owner、0.0.0.0 全局兜底）。

## §3 优先级选主（纯函数）

```
选主算法：
1. priority 降序（大者胜）
2. priority 相同时，UniqCtrlSetId 字典序升序（小者胜，稳定 tiebreak）
3. 取首条
```

- **为什么不用 ZK/Redis 选举**：不是分布式协商——所有 agent 收到**相同**的 ResourceCtrlSet 数据，独立计算得到相同胜出者；一致性由控制面下发数据保证（priority 是控制面字段）。
- 旧实现问题：`loadFromStore` 直接 `return items[0]`——Go map 迭代顺序随机，**重启前后可能选不同 SET**。
- **三路径一致**：selectWinner 在 LRU/缓存未命中/快照三条路径结果一致（单测覆盖）。

## §4 翻转检测与三层通知

**FlipEvent 三场景**：`Old=="" && New!=""`（新胜出）/ `Old!="" && New==""`（胜出消失）/ 都非空且不等（真跨 SET 翻转）。

**通知链路**（为什么三层：保持框架分层——Extension 不应反向驱动 DataSyncer）：

```
ResourceCtrlSetExtension.OnConfigChange → flipEvents
  → WinnerFlipPusher.NotifyFlipped（Extension → SetProvider）
  → WinnerFlipNotifier.OnWinnerFlipped（SetProvider → DataSyncer）
  → 逐个 event → MigrateBizScope
```

## §5 跨 SET 迁移（MigrateBizScope）

```
Step 1: watchOnNewSet（先建新）
  ├── 向新 SET 的 broker 发 WatchReq（带本地 version 增量）
  ├── registerWatch + scopeToSet 更新 + watchstore 持久化
  └── ★ 再向 SetProvider 解析 setID 校验（防 stale FlipEvent）
Step 2: unwatchOnOldSet（后拆旧）
  ├── 向旧 SET 发 UnwatchReq
  └── cleanupOldSetMetadata
失败语义：Step 1 失败保留旧 SET；Step 2 失败保留新 SET（errors.Join 汇总，不阻塞其他 ConfigType）
```

- **重叠窗口**：先建新让 broker 开始推送、后拆旧停止——窗口内重复事件由 **dispatcher version 单调递增 + Extension 幂等**收敛。

## §6 工程问题八则（本特性修/防的）

| # | 问题 | 解法 |
|---|---|---|
| 1 | 选主一致性 | 纯函数 selectWinner，三路径一致 |
| 2 | 脑裂 | 不存在传统脑裂（agent 是消费者非决策者）；"旧 Winner 残留"靠 watchOnNewSet 再校验 |
| 3 | 平滑迁移 | 先建新后拆旧 + version + 幂等 |
| 4 | Stale FlipEvent | watchOnNewSet 中再次向 SetProvider 解析校验 |
| 5 | 循环迁移 | 三层保护：业务 scope vs GLOBAL 天然不重叠 + Old==New 过滤 + 视图再校验 |
| 6 | 重启不抖动 | RestoreRunState 从 ConfigStore.List 重建 winnerSnapshot——不触发 flip |
| 7 | 自锁 | 持锁/锁外分离：持锁只算 flipEvents，锁外调下游回调 |
| 8 | 单 ConfigType 失败 | errors.Join 汇总不阻塞；Step1/Step2 失败语义相反保留 |

## §7 分片体系（ShardId 间接层）

| 层 | 分片方式 | 结构 |
|---|---|---|
| MySQL | 多物理表（ShardCount） | `t_config_0/1/…` |
| LevelDB | 前缀分区 | `config:{shardId}:{identifier}` |
| Cache | 四聚合模式 | Global/VPC/Host/Owner 维度各带 shard_id |

- **为什么 ShardId 而非 VPC ID 直接分片**：支持**重映射**（扩容/迁移只改映射表）+ 大 VPC 弹性 + 控制单片大小；代价=多一次映射查询。
- **映射位置**：`resource_obj` 表（scope_id → shard_id）；表名动态计算（`GetBizScopeIdShardedTableName`）。
- **静态分片**：初始化定 shard count；在线 re-shard 不是当前能力——**跨 SET 迁移是替代路径**（= 把 VPC 从旧 SET 的 shard 重映射到新 SET 的 shard：新 SET 建空 shard → 映射 → 全量重建 → 旧清理）。

## §8 Gen2 → Gen3 渐进迁移（专题-分片迁移补遗）

- **四阶段**：① 双写（Gen2 ZK + Gen3 Kafka 并行）→ ② 双读（部分 agent 切 Gen3 比对一致性）→ ③ 切流（Gen3 为主）→ ④ 清理（停 Gen2）。
- **双写期以 Gen2 为准**（不做比较）；**per-agent 渐变**：每天切 5%（~5000 台母机）→ 观察 24h → 约 20 天全量——"控制面迁移的单位不是系统，是每台母机"。
- **跨 SET 迁移不停机**：准备期旧 SET 继续服务，切换窗口 = 新 SET 拉全量生成快照（常 1-5s）。
- 测试反思（DeleteConfig 漏网）：**跨模块语义无测试覆盖**；拦它的测试=删除后同时断言 reconcile 结果与 LevelDB key 不存在。

## §9 数字与测试

- **规模**：5147 行 / 15 commit / 4 天；代码 ~1100 行 / 测试 ~3000 行（**测试:代码 ≈3:1**）。
- **E2E 五用例**：BIZ-008 priority 稳定性 / BIZ-009 翻转触发 push / **BIZ-010 真跨 Set 迁移（5 断言：winner 切换+broker 连接+unwatch 日志+watchstore 迁移+broker ack）** / BIZ-011 反向回滚 / BIZ-012 连续 4 次翻转 A→B→A→B→A 收敛。

## §10 外部对照（【外部】）

- **SET ↔ 软分片/租户分片**：与 SaaS 的"shard per tenant-group"同构；priority+tiebreak 选主 ≈ "确定性 leader 选择"（所有节点算同一结果——类似 rendezvous hashing 的确定性思想，但输入是控制面数据）。
- **先建新后拆旧 ≈ 蓝绿/双跑迁移**（overlap 窗口 + 幂等去重）——与 Vitess 在线 resharding（先复制后切流）思路一致。
- **ShardId 间接层 ≈ 虚拟分片（virtual shard）**：与 Redis Cluster 的 slot、Vitess 的 keyspace id 同属"间接层换重映射自由"。

## §11 钩子

- 难点与事故（跨 SET + rewatch/zombie 等工程问题清单）→ **06**；横切（迁移策略全貌/容量模型）→ **07**；面试口径（沙盘：换一致性哈希？）→ **09**。
