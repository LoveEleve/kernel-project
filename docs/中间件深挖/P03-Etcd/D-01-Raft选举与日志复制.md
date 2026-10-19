# P03-Etcd D-01 — Raft 选举与日志复制

> **定位**：P03 分布式协调第 1 篇（Raft 双线：Etcd）。三层齐：**原理（心智模型）+ 使用（etcd 视角）+ 源码（核心调用链）**。
> **源码版本**：etcd **v3.5.34**（`code/spring/etcd`；`raft/` 库在仓库内 + `server/` 服务端）
> **质量标准**：源码阅读清单 / 完整调用链 / 设计哲学（反面假设）/ 生产陷阱 / 面试 Q&A（意图）/ 章节依赖

---

## 一、它干了什么：一个例子

3 节点集群（N1 leader / N2 / N3），N1 宕机：

```
t0    N2/N3 发现心跳超时（election timeout 内没收到 AppendEntries）
t1    N2 超时先到 → 变 Candidate（任期 term+1，投自己一票）
t2    N2 向 N3 发 RequestVote(term=2, lastLogIndex, lastLogTerm)——投票约束：
        N3 检查：term 不比自己的旧 + N2 的日志不比自己旧 → 投票
t3    N2 拿到 2/3 多数票 → becomeLeader（term=2）
t4    N2 周期心跳（空 AppendEntries）确立权威；客户端写请求到达
t5    N2 appendEntry 到本地日志 → bcastAppend 并行复制到 N3
t6    N3 确认（多数派=2/3）→ N2 maybeCommit → commitIndex 推进 → apply → 返回客户端
```

**一句话**：Raft = **"强 Leader 的多数派复制"**——选举保证唯一 leader（term 仲裁），复制保证"多数派持久化才算 commit"。

---

## 二、原理（心智模型）

### 2.1 三种角色 + 任期

```
Follower →（选举超时）→ Candidate →（多数票）→ Leader
   ↑──────────────────────────────────────────┘（发现更高 term / 收到 leader 消息）
term（任期）：单调递增的逻辑时钟——每条消息携带 term，**旧 term 一律拒绝**（脑裂防线）
```

### 2.2 选举三要素

| 要素 | 规则 | 为什么 |
|---|---|---|
| **随机超时** | 每个节点 election timeout 随机化（如 150-300ms） | 避免"同时竞选"平票风暴（split vote） |
| **投票约束** | 一个 term 只投一票（**持久化** r.Vote）；候选者日志必须**不旧于**投票者 | 保证选出的 leader 拥有已提交日志（选举限制） |
| **term 仲裁** | 收到更高 term → 立即降级 Follower；旧 term 消息拒绝 | 防止旧 leader 在分区恢复后继续发号施令 |

### 2.3 日志复制（AppendEntries）

```
Leader: appendEntry（本地日志）→ bcastAppend（并行推给所有 follower）
  AppendEntries{ term, prevLogIndex, prevLogTerm, entries[], leaderCommit }
Follower: 校验 prevLogIndex/prevLogTerm 匹配 → append → commitIndex = min(leaderCommit, 本地 lastIndex)
Leader:  收集确认 → maybeCommit（多数派 matchIndex ≥ N → commitIndex = N）→ apply
```

**Log Matching Property**：如果两个日志在相同 (index, term) 有相同 entry → 该 index 之前全部相同——用 `prevLogTerm` 逐段校验，不一致时回退重试。

### 2.4 committed vs applied

```
committed：多数节点已持久化（安全——不会丢）——由 leader 的 commitIndex 决定并告达 follower
applied  ：应用层状态机已执行（对客户端可见）——由各节点自己的 apply 进度决定
两者可以不同步：commit 了还没 apply 的窗口 = 崩溃恢复时要重放的部分
```

### 2.5 成员变更与线性读（进阶）

- **成员变更**：Joint Consensus 两步——先提交"新旧配置并存的联合配置"，再提交新配置（防一步切换出现双 leader）
- **线性一致性读 ReadIndex**：leader 用心跳确认自己仍是 leader（多数派确认）→ 等待 `commitIndex ≥ readIndex` → 返回——**不落日志也能线性读**（比"读走日志"便宜）

---

## 三、使用（etcd 视角）

```
etcd 节点 = raft 状态机（raft/）+ WAL（server/wal/）+ MVCC 存储（server/mvcc/）+ gRPC API
关键参数：--heartbeat-interval（默认 100ms）/ --election-timeout（默认 1000ms）
持久化：term/vote/entries 先写 WAL（fsync）再应答——崩溃恢复从 WAL 重建
运维观测：`etcdctl endpoint status`（leader/term/raftIndex）、`etcd_disk_wal_fsync_duration_seconds`
3 节点容 1 台故障；5 节点容 2 台（规划按"容错数×2+1"）
```

---

## 四、源码核心（调用链 + 行号）

### 4.1 选举链（etcd raft 库）

```
node.Campaign(ctx)（node.go:418，发 MsgHup 给自己）
  → raft.Step（:841）→ case MsgHup（:917）→ r.hup（:754）→ r.campaign（:779）
       ├─ becomeCandidate（raft.go:689）：term+1、r.Vote = r.id（:697）
       ├─ campaign 收集 voters → r.send(MsgVote)（send:386）广播
       └─ 对端 Step(MsgVote)：检查 term/日志新旧 → 授予 → r.Vote = m.From（:966）
  → 收 MsgVoteResp 计票 → 多数 → becomeLeader（raft.go:718）
becomeFollower（raft.go:680）：遇到更高 term / 同 term 已有 leader 时降级（r.Vote = None:593）
```

### 4.2 日志复制链

```
Leader 收客户端写：node.Propose（node.go:420）→ Step(MsgProp) → appendEntry（raft.go:621）
  → bcastAppend（raft.go:515）→ sendAppend（raft.go:423，按 Progress 决定发 entries 还是 snapshot）
Follower 侧：Step(MsgApp) → handleAppendEntries（raft.go:1469）
  → raftLog.maybeAppend（log.go:88，校验 prevLogTerm 后 append 到 unstable）
Leader 收 MsgAppResp → Progress.Match 推进 → maybeCommit（raft.go:585，多数派 → commitIndex）
  → commitTo → 通过 node.Ready（node.go:498）交给应用层持久化+apply
心跳：tickHeartbeat（raft.go:655）→ 空 AppendEntries → handleHeartbeat（raft.go:1507）
```

### 4.3 复制进度跟踪（Progress）

`raft/tracker/progress.go:30`：`Match, Next, State` 三态：
```
StateProbe：探测（发一条、等确认）——新 follower/回退后
StateReplicate：正常复制（乐观推进 Next，靠 inflights 记录在途）
StateSnapshot：落后太多（Next 已被 compact）→ 发快照
```

### 4.4 持久化与线性读

```
持久化：term/vote（HardState）+ entries → WAL（server/wal/wal.go：Create:100 / Save:915 / ReadAll:432）
恢复：ReadAll 重建 HardState+日志 → r.Vote 恢复（raft.go:1702）、快照 restore（raft.go:1528）
ReadIndex：Step(MsgReadIndex)（raft.go:1072）→ sendMsgReadIndexResponse（:1088）——心跳确认领导权后读
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **Strong Leader（一切写归 Leader）** | 任意节点可提议 | 日志流单向（Leader→Follower），一致性推理简单——可理解性优先 |
| **随机超时选举** | 固定超时 | 固定超时会反复平票；随机化让一个候选先跑出来 |
| **term 单调 + 旧 term 拒绝** | 只比日志长度 | term 是高优先级仲裁，防旧 leader 分区恢复后继续"执政" |
| **多数派 commit** | 单节点确认 | 多数派交集保证新 leader 一定见过已提交日志（选举限制的保障） |
| **只能 commit 当前任期 entry** | 顺带提交旧任期 entry | Raft 论文的安全性修正——旧任期 entry 靠当前任期 entry 的提交间接提交 |
| **Joint Consensus 两步成员变更** | 一步原子切换 | 防新旧配置各成多数派选出双 leader |
| **ReadIndex 而非读日志** | 读也走一次日志复制 | 不落盘也能线性读（性能与正确性兼得） |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **偶数节点集群** | 3/5 才是容错最优（4 节点容 1，不如 3）——按容错数×2+1 规划 |
| 2 | **election timeout 太小** | 频繁误选举（网络抖动）——heartbeat << election timeout（如 100ms/1000ms） |
| 3 | **WAL fsync 慢** | 磁盘瓶颈拖垮写路径——监控 fsync duration；独立盘 |
| 4 | **commit 与 apply 混淆** | apply 慢 → 已提交未执行、读不到——监控 apply 滞后 |
| 5 | **忘记 vote 持久化** | 重启后重复投票 → 双 leader 风险——WAL 持久化（etcd 已内置） |
| 6 | **快照落后（Progress StateSnapshot）** | follower 落后太多只能发快照——快照大小/频率治理 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："Raft 和 Paxos 什么区别？"**（意图：是否理解设计目标）
Raft 以可理解性优先：Strong Leader + 日志匹配 + 成员变更三块拆解；Multi-Paxos 与 Raft 能力等价但更难工程化。

**Q2："committed 和 applied 区别？"**（意图：状态机概念）
committed=多数派持久化（不会丢）；applied=状态机已执行（客户端可见）。中间窗口是崩溃恢复的重放区间。

**Q3："Raft 怎么实现线性一致读？"**（意图：读路径理解）
ReadIndex：心跳 + 多数派确认领导权 → 等 commitIndex ≥ readIndex → 读本地状态机（不写日志）；或 LeaseRead（租约期内直接读）。

**Q4："选举怎么防丢数据？"**（意图：选举限制）
投票约束：候选人日志必须不旧于投票者（lastLogTerm/lastLogIndex 比较）——保证新 leader 含所有已提交 entry。

**Q5："网络分区旧 leader 会脑裂吗？"**（意图：term 机制）
不会：旧 leader 的 AppendEntries 带旧 term，follower 拒绝并回更高 term → 旧 leader 降级。脑裂被 term 仲裁化解。

---

## 八、章节依赖

- **上游**：P02（Kafka KRaft / RocketMQ DLedger 的应用动机）
- **下游**：D-02 EtcdServer 主循环（node.Ready → apply）；D-04 快照与压缩；P03-ZooKeeper（ZAB 对比）；P03-对比 D-01
- **横链**：Nacos JRaft（nx K04，同款思想）——etcd 是"教科书 Raft"，sofa-jraft 是 Java 工业实现

---

## 九、验证锚点（etcd v3.5.34）

| 声明 | 位置 |
|---|---|
| 选举入口 | `raft/node.go:418`（Campaign）→ `raft/raft.go:779`（campaign） |
| becomeCandidate/Leader/Follower | `raft/raft.go:689/718/680` |
| 投票持久化 | `raft/raft.go:697`（self-vote）/`:966`（grant）/`:593`（reset） |
| 日志追加与广播 | `raft/raft.go:621`（appendEntry）/`:515`（bcastAppend）/`:423`（sendAppend） |
| AppendEntries 处理 | `raft/raft.go:1469` → `raft/log.go:88`（maybeAppend） |
| commit 推进 | `raft/raft.go:585`（maybeCommit） |
| 心跳 | `raft/raft.go:655`（tickHeartbeat）/`:1507`（handleHeartbeat） |
| 复制进度 | `raft/tracker/progress.go:30`（Match/Next/State） |
| Ready 通道 | `raft/node.go:498` |
| Revote/恢复 | `raft/raft.go:1528`（restore 快照）/`:1702`（HardState 恢复） |
| ReadIndex | `raft/raft.go:1072/1088` |
| WAL | `server/wal/wal.go:100/432/915` |
