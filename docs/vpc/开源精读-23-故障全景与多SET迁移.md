# 开源精读 23 · 故障全景与多 SET 迁移——"用户承诺"的压力测试（S2b 收官）

> 课次：S2b 收官课（failure-scenarios 781 行 + resourcectrlset-priority 172 行）
> 坐标：第 18 课立了"用户四敏感"（创建即用/规则即生效/销毁即停止/业务不中断），第 20 课用故障矩阵压过一次；本课把**全链路 9 类故障点**逐个压一遍，再看一个正面的工程动作——**VPC 跨 SET 平滑迁移**。
> 纪律：断言落材料文本。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **故障点 0-6** | 全链路九个位置：api-server(0) / Kafka(1) / fusion 三件（2a 进程·2b Redis·2c MySQL）/ broker(3) / tRPC(4) / agent(5) / DPDK(6) |
| **全局单点** | 挂了影响**所有** VPC：Kafka / Redis / MySQL |
| **局部单点** | 只影响部分：fusion 单实例 / broker 单实例（agent 自动切换） |
| **SET（ResourceCtrlSet）** | broker 的分片单元；`VPC ID → CtrlSetId → Broker Addresses`（后者在 CoreCtrlSet） |
| **优先级选主** | **不是分布式协商**：所有 agent 收到相同数据 → **独立计算得同一胜出者**（priority 降序 + UniqCtrlSetId 字典序 tiebreak） |
| **FlipEvent** | 胜出 SET 翻转事件（三种：新出现 / 消失 / 真翻转） |
| **先建新后拆旧** | 迁移铁律：`watchOnNewSet` → `unwatchOnOldSet`——反过来会留**事件空窗** |

## ① 它干了什么——两个问题

**问题一：Redis 挂了，任务队列里的一分钟怎么过？**

fusion 的 snapshot/reconcile/cleanconfig **全依赖 Redis**（发布 RPUSH / 领取 LPOP / 续租 ZADD / 完成 ZREM）——Redis 一挂，全部操作失败。系统按**四层**反应：健康检查标 NotReady → Leader 选举停止 → Worker FetchTask 报错（sleep 60s 重试）→ 已领任务**心跳续租失败即自杀**（`executeCancel`）。Redis 恢复后**四步归队**：健康检查连成 3 次（15s）→ Ready → Leader 秒级重选 → Recovery 扫描过期任务重入队 → Worker 恢复领取。

**问题二：一个 VPC 从旧 SET 搬到新 SET，配置流怎么不断？**

**先建新、后拆旧**：先向新 SET 的 broker 发 `WatchReq`（**带本地 version 增量**——不用全量重来），注册完、持久化好，再向旧 SET 发 `UnwatchReq`——**重叠窗口**里两边的重复事件由"version 单调 + Extension 幂等"收敛。**不是"切换"，是"叠交"**。

## ② 链路走查（材料行号）

### 跳 1：故障全景表——九个故障点的一张体检单（failure §一）

```
vpc-api-server → Kafka → vpc-fusion → LevelDB快照 → vpc-broker → tRPC → vpc-host-agent → DPDK
     ↑              ↑           ↑              ↑            ↑              ↑              ↑
   故障点0       故障点1     故障点2        故障点3      故障点4        故障点5        故障点6
```

| 故障点 | 组件 | 挂了影响什么 | 自动恢复 | 恢复时间 | 需要人工 |
|---|---|---|---|---|---|
| 0 | vpc-api-server | 配置变更无法发起 | ✅ 多副本 | 秒级 | — |
| 1 | Kafka | 配置变更无法传递 | ✅ **ISR auto failover** | < 10s | — |
| 2a | vpc-fusion 进程 | 配置不处理 | ✅ **watchdog 重启** | 1-2 分钟 | — |
| 2b | Redis（任务队列） | 任务无法发布/消费 | ⚠️ **降级** | 1-2 分钟 | 视情况 |
| 2c | **MySQL（配置存储）** | 配置无法读写 | ❌ | — | ✅ |
| 3 | vpc-broker 进程 | 配置不推送给 agent | ✅ 多副本 | 5-10 秒 | — |
| 4 | tRPC 连接断开 | agent 收不到配置 | ✅ **重连+增量恢复**（第 19 课） | 5-10 秒 | — |
| 5 | agent 进程 | 配置不下发到内核 | ✅ watchdog 重启 | 秒级 | — |
| 6 | DPDK 内核 | 数据面转发停止 | ✅ agent 重启重建 | 30 秒-几分钟 | — |

**单点分析**（§1.2）：**全局单点** = Kafka / Redis / MySQL（挂了影响所有 VPC）；**局部单点** = fusion 单实例（其他实例继续）/ broker 单实例（**agent 自动切换到其他 broker**——第 19 课的连接池设计在此兑现）。

**这张表最值钱的一行是 2c**：整条链上**只有 MySQL 标 ❌ 需人工**——所有"无状态、可重建、有 watchdog"的组件都自动复活，唯独"配置存储真相本身"没有自动化兜底。**恢复能力的分布，就是架构"谁是无状态、谁背着真相"的分布。**

### 跳 2：Redis 挂深读——一切机制的总验收（failure §二）

**四层反应**（§2.3）：

1. **健康检查**——**双层探测**：① 基础写入测试（SET+GET+DEL）；② **任务能力探测（Lua 脚本：PSETEX+GET）**——两次探测各自"**连续失败 3 次**（间隔 5 秒）"才判不可用 → 节点 NotReady（**抗抖动：单次失败不算数**）；
2. **Leader 选举停止**：Redis 选举 Key TTL 15s 续不上 → 王冠自然落地；

> ⚠️ **材料自带的口径裁决**（§2.3 原文注）：选举 Key 一会儿 `fusion:leader:{electionKey}`（本文档写法）一会儿 `leader:election:{role}`（`leader_vote_dao_impl.go:15`）——**材料自己承认"两处口径不一致"，并给出处理意见："面试统一用后者"**。第 21 课"存疑待考"至此**钉棺**：以 `leader:election:{role}` 为准。

3. **Worker 行为**：`FetchTask` 的 `LIndex` 报错 → processTask 返回 → **consumer_entry sleep 60s 重试**（不空转打爆日志）；
4. **已处理任务**：心跳续租失败 → `executeCancel()`（第 21 课的"自杀"机制）——但注意材料冷知识："**Lease 过期后 Recovery 扫描重新入队（但 Redis 挂了 Recovery 也跑不了）**"——**依赖 Redis 的兜底，救不了 Redis 自己**。

**四步恢复**（§2.4）：健康检查**连续成功 3 次（15 秒）** → Ready → Leader 重选（秒级）→ Recovery 每分钟扫描 `ZRANGEBYSCORE processing 0 now` 重入队 → Worker 重新领取。**"恢复"也是渐进的**：先恢复判活（15s），再恢复王冠（秒级），最后恢复欠账（1 分钟内）——**按依赖顺序一层层站起来**。

### 跳 3：Kafka 积压——"慢"的三级连锁（failure §三）

**现象**：binlog 模块消费 **lag 持续上涨** + 快照触发延迟 + agent Poll 返空；告警 `kafka_consumer_lag_increasing` + `snapshot_stale`。**根因四选**（§3.2）：MySQL 写瓶颈 / 单条处理慢（反序列化+写库）/ **partition 数不够（消费并行度不足）** / 消费者 OOM 或 GC 停顿。

**三级连锁**（§3.3，全链**没有一处报错**）：

```
binlog 模块：saveToMySQL 慢 → 后续 Poll 拿不到新消息 → lag 上涨
     ↓（binlogCount 增长慢）
snapshot 模块：binlogCount < SnapshotBinlogCount（默认 1000）→ 不触发快照
     ↓
broker 侧：ListBinlog 返回空 → BTree 不更新 → Poll 返回空
```

**"沉默故障"**：堵在最上游，安静地传导到最远端——没有异常、没有 ❌，只有指标爬坡（lag/stale）。对照第 20 课的"**错比停危险**"，这里补第二句：**"慢比停难查"**。

### 跳 4：Worker 全崩——套娃守护与秒级时间线（failure §四）

**根因**：OOM / panic / K8s 驱逐 / 母机故障。**三层守护**（§4.3）：

1. **watchdog**（**独立进程 + systemd 两层守护**，50-commit 表 E 组）：检测进程不在 → 重启；`/up` 不响应 → 重启；**watchdog 自己挂了 → systemd 重启 watchdog**——**套娃守护**；配置原文 `max_retries: -1`（**无限重启**）；
2. **Leader 选举**：全体阵亡 → Redis 选举 Key TTL 15s 过期 → 重启后**先抢到 Key 的当新 Leader**；
3. **任务恢复**：Recovery 扫描 `ZRANGEBYSCORE processing 0 now` → 过期任务重新入队 → 新 Worker 领取。

**恢复时间线**（§4.4）：`T+0 全崩 → T+1s 检测到进程不在 → T+2s 重启 → T+5s 进程启动完成`——**5 秒级**，这就是 watchdog 的价值（对比 15 课的 ZK 运维族 zk_agent"总开关"——同一哲学的另一种实现）。

### 跳 5：broker 挂 vs 网络分区——两种断法，两种代价（failure §五/§六）

**broker 挂**（§五）：根因（崩溃 / 母机故障 / **滚动升级 Drain 状态**——计划内也要断！）。反应=第 19 课的完整重演：`onDisconnect` CAS 防并发 → backoff 1→2→4→8→10s × 5 次 → **排除已失败 broker 换下一个**（`NextBrokerExclude`）→ 成功则 **`reWatch` 带 LocalVersion 增量恢复**；broker 无状态多副本，"一个挂了自动切到另一个"。

**网络分区**（§六）——**最阴险的一景**："tRPC Stream **看起来还连着**但实际不通"。两侧各有戏：

- **broker 侧**：`stream.Send` 失败 → `sendFailed=true` → EventLoopWorker 退出 → Session `lastSeen` 不更新 → **GC 标记 Expired**；binlogQueue 堆积 → 达 `MaxPendingVersions`（默认 1000）→ **驱逐未 ACK → `unackedEvicted` → MarkTooSlow → TOO_SLOW**（第 17/19 课的机制在故障剧本里再次现身——**机制的另一张脸就是故障反应**）；
- **恢复**（§6.4）：重连 → broker 上 session 已 Expired/TOO_SLOW → `INVALID_SESSION` 或 TOO_SLOW → **空 sessionID 重握手 → 全量 Watch**（"**增量数据可能已被驱逐**"）→ 重建本地状态。时间线：`T+0 分区 → T+30~60s keepalive 检测到不通 → T+60s 重连`。

**对比**：broker 挂 = 立即断、**增量恢复**（5-10s）；网络分区 = **假连真断**、拖到驱逐、**全量恢复**——**"断得晚，恢复贵"**；检测速度（keepalive 30-60s）是分区恢复的关键变量。

### 跳 6：三景速览与组合故障（failure §九/§十/§十一/§十二）

- **MySQL 挂**（§九）：binlog 保存失败 → **offset 不 commit → Kafka 重新投递**（at-least-once 的救赎时刻）；snapshot failed；**总影响 = MySQL 恢复时间 + 1-2 分钟**；"**超过 5 分钟不恢复 → 运维介入**"（唯一需要人肉红线的一景）；
- **api-server 挂**（§十）：用户无法创建/删除 VM；initconfig/reconcile 停摆（期望态拉不到）——**用户可感知**的一景；但 §10.2 澄清了边界："**已有的快照和 binlog 继续工作 → 已有 VM 的流量不受影响**"——**故障只坏在"入口"（新变更进不来），存量继续跑**；多副本 + LB 自动切换，秒级恢复；
- **DPDK 内核挂**（§十一）：母机所有 VM 流量不通 → agent 检测到不响应 → 重启 → **从 broker 拉全量快照重建 route_table/neigh_table**（重建期间该母机全部 VM 不通）；恢复三段分解："**agent 重启秒级 + 从 broker 拉全量 10-30 秒（取决 VPC 数量和快照大小）+ DPDK 重建秒级** = 总 30 秒到几分钟"；材料金句（§11.4）："**Gen3 比 Gen2 快——Gen2 从 CKV 拉快照**…"（第三次"全量恢复"现场）；
- **组合故障**（§十二）：`Redis+Kafka 积压 → 总影响 = 各自恢复 + 串行叠加 + 2-3 分钟`；`网络分区+broker 挂 → seed fallback，种子也不通 → agent 完全断连 → 人工`；`MySQL+Redis 同挂 → 系统完全不可用`——**组合故障 = 恢复关键路径的串联**（谁也越不过没恢复的下游）。

### 跳 7：多 SET 迁移——"确定性选主"与"先建新后拆旧"（resourcectrlset §一/§二/§三/§四）

**背景**（§一）：`VPC ID → CtrlSetId（ResourceCtrlSet）→ Broker Addresses（CoreCtrlSet）`——200 万 VPC 单集群扛不住，按 SET 分片。**为什么需要优先级选主**：**灰度搬迁**时同一 VPC 可能同时绑定旧+新两个 SET——旧代码 `loadFromStore` **直接 `return items[0]`，而 Go map 迭代顺序随机**：重启前后可能选不同 SET（agent 连错 broker）。**又一颗 Go 暗雷**，这次的解法是"**把随机变成确定**"。

**四个核心概念**（§二）：

1. **优先级选主**（§2.1）：`priority 降序（大者胜）→ 相同时 UniqCtrlSetId 字典序升序（小者胜，稳定 tiebreak）→ 取首条`。**材料明说："不是分布式协商选主。所有 agent 收到相同的 ResourceCtrlSet 数据，独立计算得到相同胜出者。一致性由控制面下发的数据保证"**——**确定性计算替代协商**；
2. **翻转检测 FlipEvent**（§2.2）：三种——`Old=="" && New!=""` 新出现 / 消失 / `Old!=New` 真翻转；
3. **跨 SET 迁移 `MigrateBizScope`**（§2.3）：**Step 1 `watchOnNewSet`（先建新）**——向新 SET 发 `WatchReq`（**带本地 version 增量**）+ registerWatch + `scopeToSet` 更新 + watchstore 持久化；**Step 2 `unwatchOnOldSet`（后拆旧）**——`UnwatchReq` + cleanup。**为什么先建新后拆旧**：反过来留**事件空窗**；重叠窗口的重复事件交给"**dispatcher version 单调递增 + Extension 幂等**"收敛；
4. **三层通知链路**（§2.4）：`Extension.OnConfigChange → WinnerFlipPusher.NotifyFlipped（Extension→SetProvider）→ WinnerFlipNotifier.OnWinnerFlipped（SetProvider→DataSyncer）→ MigrateBizScope`——**为什么三层：Extension 不应反向驱动 DataSyncer，通过 SetProvider 回调保持解耦**（框架分层的自律，对照第 16 课 agentos）。

**工程问题清单**（§三）：**选主一致性**——纯函数 `selectWinner`，LRU/cache miss/snapshot **三路径结果一致**（测试点）；**脑裂辨析**——"**不存在传统脑裂（agent 是消费者非决策者）**"，但"旧 Winner 残留"要靠 `watchOnNewSet` 时**再向 SetProvider 解析校验**；**Stale FlipEvent** 同款再校验。**代码与测试**（§四）：核心两文件 `resource_ctrl_set.go`（+499）/ `migrate_biz_scope.go`（+368），**测试:代码 ≈ 3:1（~3000/~1100）**——"修完补测试比修复重"的第二次现身。

## ③ 设计推导（问题树）

**Q1：故障全景表里"只有 MySQL 标 ❌"说明了什么？**
**自动化边界 = 真相所在**：无状态的（api-server/broker）、可重启的（watchdog 管辖的 fusion/agent/watchdog 套娃）都能自动复活；连 Redis 都只是"降级+视情况"；**唯独配置存储真相本身必须人工**——因为"重建真相"没有裁判者。这与第 20 课"唯一中转通道"、第 18 课"分工三层存储"拼成同一个架构公理：**可再生的一切自动化，不可再生的必须敬畏**。

**Q2：Redis 景为什么是全课机制的"总验收"？**
四层反应把 S1-S2b 的机制全部对上号：双层健康检查（抗抖动的 3 次判定）、选举 TTL、FetchTask、**心跳自杀**、Recovery 扫描。且暴露一个结构性事实："**Lease 过期后 Recovery 重新入队——但 Redis 挂了 Recovery 也跑不了**"——**兜底机制的兜底在哪里？** 依赖 Redis 的恢复救不了 Redis 自己（自举问题）；答案在全景表层：Redis 是"全局单点"之一，它挂不是流程问题而是**依赖问题**。

**Q3：Kafka 积压的"沉默传导"为什么最危险？**
三级链上没有一处判错——只是变慢。故障可见性谱系：**抛异常（响）> 变慢（哑）> 完全沉默（更哑）**——lag/stale 这类"爬坡指标"才是真正的值班噩梦；对策在 50-commit 的限频/背压设计里（哪一级都不许"无限接单"）。

**Q4：broker 挂与网络分区为什么恢复代价差一档？**
立即断（broker 挂）时 broker 侧**知道自己死了**（session 正常回收），agent 重连拿回增量；假连真断（分区）时双方都"以为对方还活着"，broker 侧**按超时判死并驱逐**（1000 条水位），恢复时**增量已被驱逐，只能全量**。**"断得晚，恢复贵"——故障检测的速度直接定价恢复成本**（keepalive 30-60s 就是在给这个定价打折）。

**Q5：多 SET 选主为什么不需要 Raft/ZK 选举？**
因为**把一致性从运行时挪到了数据面**：所有 agent 收到**相同的输入**（控制面下发的 ResourceCtrlSet 数据）+**相同的纯函数**（priority+tiebreak）= 必然同一结果——共识算法解决的是"参与者可能收到不同输入"，而这里输入本身被控制面统一了（第 17 课 `SET_RECONCILING`/`ctrlSetReplica fail-closed` 正是保输入一致的环节）。**"确定性计算"替代"协商"——同族：第 4 课 OVN 的序列号、第 17 课版本号**：能算出来的，就别商量。

**Q6："先建新后拆旧"与第 19 课"先增量后全量"的共性？**
**宁可重叠（幂等收敛），不可空窗（丢事件）**——两个动作窗口重叠时的重复由**幂等**兜底（Extension 幂等 / version 单调）；拆旧的顺序不能反，增量的路线不能跳。**迁移的第一原则不是"快"，是"不断"**。

**Q7：组合故障"总影响时间 = 相加"的启示？**
恢复是**串联路径**：先恢复的组件也越不过未恢复的下游——所以故障的代价=关键路径长度，而不是最长单故障。12.3（MySQL+Redis 同挂=完全不可用）直接给出运维演练的重点：**全局单点的两两组合**就是灾备剧本的目录。

## ④ 对照卡：九景的"断法 × 恢复手段 × 代价"

| 故障点 | 断法 | 恢复手段 | 代价 |
|---|---|---|---|
| api-server | 立即 | 多副本 | 秒级 |
| Kafka | 立即 | ISR auto failover | < 10s |
| fusion 进程 | 立即 | **watchdog 套娃** | 1-2 分钟 |
| Redis | 立即 | 降级 + 视情况人工 | 1-2 分钟~ |
| **MySQL** | 立即 | **❌ 人工（>5 分钟介入）** | 恢复+1-2 分钟 |
| broker 进程 | 立即 | 多副本 + **增量重连** | 5-10 秒 |
| tRPC 连接 | 立即 | 重连+增量恢复 | 5-10 秒 |
| agent 进程 | 立即 | watchdog | 秒级 |
| DPDK 内核 | 立即 | agent 重启重建 | 30s-几分钟 |
| **网络分区** | **假连真断** | 重连 + **全量重建**（增量已被驱逐） | 60s+全量 |

## ⑤ 验收题

1. "自动化边界 = 真相所在"——为什么唯一需要人工的是 MySQL？举出本项目里第二个"不可再生因而特殊照顾"的组件对比（提示：快照/版本号）。
2. Redis 景四层反应各对应前面哪一课的机制？"Recovery 也跑不了"暴露的自举问题，标准答案为什么是"依赖问题而非流程问题"？
3. "抛异常 > 变慢 > 完全沉默"——为什么 lag/stale 类指标比错误日志更难值班？限频与背压在此扮演什么角色？
4. broker 挂走增量、网络分区走全量——把"断得晚恢复贵"拆成三句话（检测/驱逐/恢复）。
5. 多 SET 选主"确定性计算替代协商"成立的**两个前提**是什么？缺了哪个前提就要退回共识算法？
6. "宁可重叠不可空窗"在迁移与恢复两个场景各出现一次——它背后的数学工具是什么（哪个机制负责吞掉重叠）？

---

*本课材料行号清单（供追问对质）：`failure-scenarios` §1.1（九故障点表全行：0/1/2a/2b/2c/3/4/5/6 + 恢复时间列）/ §1.2（全局单点 vs 局部单点）/ §2.3（双层健康检查 + Lua 任务能力探测 + 连败 3 次 + 选举 Key 口径注"面试统一用后者" + FetchTask sleep 60s + "Recovery 也跑不了"）/ §2.4（四步恢复：3 次 15 秒/秒级重选/1 分钟扫描）/ §3（lag+stale 告警 + 四根因 + 三级连锁 + SnapshotBinlogCount 默认 1000）/ §4.3（watchdog.yaml max_retries -1 + 套娃三层）/ §4.4（T+0→T+5s 时间线）/ §5.2（Drain 状态）/ §5.3（onDisconnect CAS + backoff 1→10s + NextBrokerExclude + reWatch LocalVersion）/ §6.3（broker 侧：sendFailed/Expired/MaxPendingVersions 1000/unackedEvicted/TOO_SLOW）/ §6.4（恢复：INVALID_SESSION→空 sessionID→全量）+ §6.5（T+30~60s keepalive）/ §9.3（offset 不 commit → 重新投递）/ §9.5（5 分钟人工红线）/ §十（api-server 三影响）/ §十二（组合：12.1 相加 2-3 分钟 / 12.2 seed fallback 断连 / 12.3 双单点同挂）；`resourcectrlset_priority` §一（SET 链路 + return items[0] 随机雷 + 灰度搬迁背景）/ §2.1（priority 降序 + tiebreak 字典序升序 + "不是分布式协商"原文）/ §2.2（FlipEvent 三场景）/ §2.3（watchOnNewSet 四步 + unwatchOnOldSet + "先建新后拆旧"防空窗 + version+幂等）/ §2.4（三层通知链路 + "Extension 不应反向驱动 DataSyncer"）/ §三（selectWinner 三路径一致 / 无传统脑裂 / Stale FlipEvent 再校验）/ §四（两文件行数 + 测试:代码 ≈ 3:1）。*

*审查记录（首轮深审）：**0 修正 + 2 处硬锚升级**——① §十补"**已有 VM 流量不受影响**"（故障只坏入口、存量继续跑）+ 秒级恢复；② §十一 DPDK 从"全景表口径"升级为正文三段分解（agent 重启/拉全量 10-30 秒/重建）+ 金句"Gen3 比 Gen2 快——Gen2 从 CKV 拉快照"+"第三次全量恢复现场"注。逐字命中：九故障点表全行、双层健康检查（3 次×5s）、口径裁决注原文（"面试统一用后者"）、watchdog.yaml（max_retries -1）、T+0→T+5s 时间线、onDisconnect/NextBrokerExclude/reWatch、MaxPendingVersions 1000 与 unackedEvicted/TOO_SLOW 链、offset 不 commit→重新投递、5 分钟人工红线、组合 12.1/12.2/12.3、`return items[0]` 随机雷、priority+tiebreak 算法、先建新后拆旧、三层通知链、brain-split 辨析（"agent 是消费者非决策者"）。*

*审查记录（二次深审）：R1 两处升级自查在位（"已有 VM 的流量不受影响" / "Gen3 比 Gen2 快" / "第三次全量恢复现场"，grep 3 处命中）；"全景表口径"旧表述零残留（仅审查回溯 @179）——**零尾差、零新错。S2b 六课（18-23）至此全部双轮闭环**。*
