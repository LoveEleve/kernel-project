# P03-Etcd D-02 — EtcdServer 主循环与状态机

> **定位**：P03 第 2 篇。三层齐：**原理（心智模型）+ 使用（观测/调参）+ 源码（核心调用链）**。
> **源码版本**：etcd **v3.5.34**（`code/spring/etcd`）。承接 D-01（raft 库内部），本篇讲 **raft 库 ↔ 应用层的契约（Ready）**与 apply 链路。
> **质量标准**：源码清单 / 完整调用链 / 设计哲学（反面假设）/ 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：一个例子的端到端

客户端 `etcdctl put k v` 后，一次写请求在 etcd 内部的生命周期：

```
① gRPC KVServer 收到 Put → 封装为 raft 提案（InternalRaftRequest）
② node.Propose（raft/node.go:420）→ raft 状态机（D-01）追加日志并广播
③ raft 状态机产出 Ready（有：HardState + Entries + Messages）
④ raftNode.run 消费 Ready：
     - storage.Save（WAL 落盘 HardState+Entries）——【先持久化】
     - ap := apply{entries, snapshot} → applyc <- ap
     - leader: transport.Send（并行发给 follower，raft thesis 10.2.1）
⑤ EtcdServer.run 的主循环收 applyc → 调度 applyAll
     - applySnapshot / applyEntries → s.apply（按 Entry 类型分发）
     - EntryNormal → applierV3.Apply（写 MVCC/backend）
⑥ apply 完成 → notifyc 通知等待的请求 → 客户端拿到 "OK"
```

**一句话**：etcd = **两层循环的流水线**——raft 循环（复制/持久化）与 server 循环（apply 状态机）通过 **Ready/applyc 管道**衔接；"commit 归 raft、apply 归应用"。

---

## 二、原理（心智模型）

### 2.1 Ready：raft 库与应用层的"契约"

```
raft 库不碰磁盘/网络——它把"这一轮需要你做的事"打包成 Ready：
  Ready = HardState + Entries + CommittedEntries + Snapshot + Messages + SoftState + ReadStates
应用层责任：
  ① 持久化：HardState/Entries/Snapshot 必须先落盘（WAL）——才能进入下一步
  ② 发送：Messages 发给其他节点（多数派复制）
  ③ 应用：CommittedEntries 交给状态机 apply
  ④ 反馈：处理完调用 Advance()——raft 库才会产出下一个 Ready
```

**关键语义**：**一次只有一个 Ready**（应用层没 Advance 前 readyc 关闭）——把"持久化/发送/应用的节奏"交给应用层控制（etcd 才能做批量化与并行化）。

### 2.2 两层循环 + 保序

```
① raft node loop（每节点一个）：tick（选举/心跳计时）+ Ready 产出
② raftNode.run（桥梁）：Ready → WAL 落盘 → applyc 管道 + 发消息
③ EtcdServer.run（应用）：applyc → FIFO Scheduler → applyAll → 状态机
```

**为什么 apply 要 FIFO 调度器**：apply 是异步的（与 raft 循环解耦），但状态机**必须按 index 顺序执行**——`schedule.NewFIFOScheduler()` 保证提交进 scheduler 的任务串行执行。

### 2.3 三个"顺序红线"

| 红线 | 规则 | 违反后果 |
|---|---|---|
| **先持久化再响应** | WAL 落盘（Save）先于 apply 与消息发送 | 崩溃丢已应答数据 |
| **先快照文件再 WAL 记录** | SaveSnap 先于 Save（同轮） | 崩溃恢复时快照引用的 WAL 未落 |
| **apply 按 index 严格递增** | FIFO + appliedi 校验（applyEntries:1464 用 `ep.appliedi+1-firsti` 截取） | 状态机错乱 |

### 2.4 快照与 apply 的关系

```
Snapshot 在 Ready 里出现时：applySnapshot 优先于 applyEntries（applyAll 内分支）
applySnapshot：用快照整体替换内存状态（MVCC backend restore）+ 重建 appliedi/appliedt
（这就是"落后太多的 follower 用快照追赶"的落地点——D-04 展开）
```

---

## 三、使用（观测与调参）

```
观测指标：etcd_server_apply_duration_seconds（apply 耗时）
         etcd_server_proposals_committed_total（committed）
         appliedIndex 与 committedIndex 的差（etcdctl endpoint status --write-out=json）
         etcd_disk_wal_fsync_duration_seconds / etcd_disk_backend_commit_duration_seconds
关键参数：--snapshot-count（默认 100000：apply 多少条后触发快照——过大恢复慢、过小频繁快照）
         backend-batch-interval/batch-limit（批量提交；读写性能相关）
排障口诀：写延迟高 → 先看 WAL fsync → 再看 apply 滞后（committed - applied）→ 再看 backend commit
```

---

## 四、源码核心（调用链 + 行号）

### 4.1 raft 库侧：node.run 的 Ready 闸门

```
node.run（raft/node.go:300）：
  advancec == nil && HasReady() → rd = readyWithoutAccept()；真正读取时 readyc = n.readyc
  应用层拿走后必须 advance（advancec 触发）——期间 readyc = nil（一次一个 Ready 的结构保证）
node.Ready()（node.go:498）：暴露给应用层的 channel
```

### 4.2 桥梁侧：raftNode.run（etcdserver/raft.go:180）

```
case rd := <-r.Ready():                           // :188
  ├─ SoftState：leader 变更指标/回调（rh.updateLead/updateLeadership）
  ├─ ReadStates → r.readStateC（线性读通路，D-01 ReadIndex 的消费端）
  ├─ ap := apply{entries: rd.CommittedEntries, snapshot: rd.Snapshot, notifyc}   // :223（ap := apply{）
  ├─ updateCommittedIndex(ap, rh)（:384，commit 指标/回调）
  ├─ shouldWaitWALSync(rd)（:371）→ storage.Save(HardState, Entries)（:234/:265）
  ├─ applyc <- ap（:240，交给 EtcdServer.run）
  ├─ leader: transport.Send(processMessages(rd.Messages))（:244/397——并行发送，thesis 10.2.1）
  ├─ SaveSnap（:257，快照文件先落）
  └─ 再 storage.Save（未 waitWALSync 的分支）
tick（:162）：ticker 驱动 raft.Tick（选举/心跳计时）
```

### 4.3 应用侧：EtcdServer.run（server.go:1047）

```
sched := schedule.NewFIFOScheduler()（保序执行器）
raftReadyHandler：lead 变更时 lessor.Demote / compactor.Pause（领导权变化联动租约与压缩）
主循环 select：
  case ap := <-s.r.apply():（applyc 消费）
      sched.Schedule(f)（:1156，f = applyAll）——异步保序
  case leases := <-expiredLeaseC: revokeExpiredLeases
  case err := <-s.errorc: ...
applyAll（:1281）：
  含 Snapshot → applySnapshot（:1303，整体替换 + 重建 appliedi）
  否则 → applyEntries（:1464）：按 appliedi 截取 → s.apply（:2254）
apply（:2254）：switch EntryNormal → applyEntryNormal；ConfChange 分支
  → applierV3.Apply（apply.go:133，写 MVCC/backend）
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **Ready 契约（库不碰磁盘/网络）** | raft 库直接写 WAL/发消息 | 职责分离：库只管共识逻辑；应用层控制持久化时机与批量化（etcd 才能组批写 WAL） |
| **一次一个 Ready + Advance** | 随意并发产出 | 背压：应用没处理完不再产出——防内存堆积 |
| **WAL 先落盘再 apply/发送** | 先 apply 再落盘 | 崩溃安全：已应答的数据必须已在 WAL（重启可重放） |
| **leader 发送与落盘并行** | 串行：先落盘再发 | 延迟优化（raft thesis 10.2.1）：并行不破坏正确性（多数派对 WAL 的要求由各自节点保证） |
| **apply 走 FIFO Scheduler（异步）** | 与 raft 循环同步 apply | 解耦：apply 慢不阻塞 raft 心跳/选举（否则延迟雪崩）；保序由 scheduler 兜 |
| **快照优先于增量条目** | 先 apply 剩下的条目 | 快照已包含全部历史——先恢复快照再 apply 之后的条目（appliedi 校验截取） |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **apply 慢 → 写阻塞** | committed 涨、applied 不涨（Ready 背压）——查 backend commit（磁盘）；批量参数 |
| 2 | **WAL fsync 慢** | 磁盘抖动 → 写延迟高——独立盘/监控 `wal_fsync_duration` |
| 3 | **snapshot-count 不合理** | 过大：重启回放久；过小：频繁快照 IO——按容量与恢复目标调 |
| 4 | **未等 notify 就响应** | 误把"propose 成功"当"apply 成功"——etcd 内部用 notifyc 等待，业务层别绕过 |
| 5 | **快照与 WAL 顺序** | 恢复时引用未落的 WAL——etcd 内置顺序保证，自研存储要抄这份作业 |
| 6 | **监控只看 committed 不看 applied** | 滞后被掩盖——两个指标一起看（差值=apply 积压） |

---

## 七、面试 Q&A（含面试官意图）

**Q1："etcd 一个写请求的完整链路？"**（意图：两层循环）
gRPC → Propose → raft 复制/commit → Ready → WAL 落盘 → applyc → FIFO apply → MVCC → notifyc → 响应。

**Q2："Ready 是什么？"**（意图：库/应用契约）
raft 库把"待持久化/待发送/待应用"打包成 Ready 交给应用，处理完 Advance 才产出下一个——应用层掌控节奏。

**Q3："为什么 apply 是异步的？"**（意图：性能设计）
apply 慢（磁盘/状态机）不能拖住 raft 心跳（否则误选举）——FIFO 保序解耦；代价是 apply 滞后窗口。

**Q4："committed 和 applied 在 etcd 里怎么观测？"**（意图：运维）
`endpoint status` 的 raftIndex vs raftAppliedIndex；差 = 积压——apply 慢先查 backend commit。

**Q5："为什么先写 WAL 再 apply？"**（意图：崩溃安全）
已应答=已多数派持久化；WAL 是重放依据——apply 只是"把已持久化的事实执行到状态机"。

---

## 八、章节依赖

- **上游**：D-01（raft 库产出 Ready）
- **下游**：D-03（apply 的目标：MVCC 存储与 Watch）；D-04（快照的生成与恢复细节）
- **横链**：K04（Nacos 持久实例底层 JRaft 的同类"状态机+日志"模型）

---

## 九、验证锚点（etcd v3.5.34）

| 声明 | 位置 |
|---|---|
| Ready 结构与 channel | `raft/node.go:52/259/275/498` |
| node.run 闸门 | `raft/node.go:300`（advancec/readyc 结构） |
| raftNode.run（Ready 处理） | `server/etcdserver/raft.go:188` |
| WAL 落盘（先持久化） | `raft.go:234/265`（storage.Save 两分支）；`shouldWaitWALSync:371` |
| apply 管道 | `raft.go:442`（apply() chan）/ `:239`（applyc <- ap） |
| leader 并行发送 | `raft.go:244` → `processMessages:397` |
| 快照文件先落 | `raft.go:251` 附近（SaveSnap）；`storage.go:56` |
| EtcdServer.run | `server/etcdserver/server.go:1047`（FIFO scheduler）；apply 消费点 `:1156` |
| applyAll / 快照 / 条目 | `server.go:1281/1303/1464` |
| apply 分发 | `server.go:2254`（EntryNormal/ConfChange）→ `apply.go:133`（applierV3.Apply） |
