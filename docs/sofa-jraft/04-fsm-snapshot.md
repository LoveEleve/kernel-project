---
title: "04-原理三：状态机与快照"
series: "SOFAJRaft 共识系列"
previous: "03-log-replication.md"
next: "05-usage.md（待写）"
source_refs:
  - "已核：FSMCallerImpl（onCommitted:263/ApplyTaskHandler:143/lastAppliedIndex:166）/ SnapshotExecutorImpl（doSnapshot:305,314/onSnapshotSave:385/位距检查:355）/ LogManagerImpl（truncatePrefix→logStorage:556/从快照恢复:666,676,729）/ LogStorage.truncatePrefix:70 / NodeOptions（snapshotIntervalSecs:68=3600/snapshotLogIndexMargin:77 默认0=关）/ NodeImpl.apply:1736、snapshot:3272"
---

# 04-原理三：状态机与快照

## §0 它干了什么（例子先行）

"已提交"的日志怎么变成状态？（03 篇最后一棒）

```
① BallotBox.onCommitted:141（03 篇末）→ FSMCallerImpl.onCommitted:263
② 不直接调业务！先入队 → Disruptor 单消费线程 ApplyTaskHandler:143 按序吐
   → 逐条回调 StateMachine.apply(applicableContext)
   → lastAppliedIndex:166 向前推
③ 日志涨到阈值 → 快照：SnapshotExecutorImpl.doSnapshot:305
   → 业务 StateMachine 做存档（onSnapshotSave:385）
   → 截断：LogManagerImpl 把快照位点之前的日志 truncatePrefix 掉:556
```

- **它干了干什么**：**按序应用 + 快照截断**——应用保证"确定顺序"，快照保证"日志不用无限长"。
- **本篇回答**：为什么应用必须"单线程按序"？快照和日志截断怎么配合？新节点/落后节点怎么追上？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **FSMCaller** | 日志→业务状态机的**唯一通道**——onCommitted:263 收位点，ApplyTaskHandler:143 **单消费线程**按序派发 |
| **lastAppliedIndex** | "我用到哪了":166——快照/恢复/状态上报都看它 |
| **快照（Snapshot）** | 在 lastAppliedIndex 处给状态机**拍存档**——NodeImpl.snapshot:3272 手动 / doSnapshot:305 定时（snapshotIntervalSecs=3600:68）/ 位距触发（snapshotLogIndexMargin:77——**默认 0=关**） |
| **truncatePrefix** | 截前缀——快照位点之前的日志段不再需要（LogStorage.truncatePrefix:70；LogManagerImpl:556 落实） |

## §2 关键推导（三问）

**① 为什么应用必须单线程按序？**
复制状态机的根定义（dist-theory 02 §二）：**同一顺序 → 同一状态**。并发 apply=顺序不保证=三个节点算出三个状态——所以哪怕用 Disruptor 加速，**消费端仍是串行的**（:143 只有一个 handler）。同族：RocketMQ 05 的**单队列顺序消息**——顺序的代价都是"串行"。

**② 快照为什么能截日志？**
日志的价值只在于"**把状态机从 0 重放到当前**"——快照已经是当前状态的存档，之前的流水自然作废。**恢复公式**：`加载快照 + 回放快照位点之后的日志`。快照恢复时 LogManagerImpl 自己截到 `lastIncludedIndex+1`（:666/:676/:729——三处入口：加载/安装/重置）。

**③ 落后太多的节点怎么办？（发照片不发流水）**
追不上的场景：与其让 Leader 把几百万条日志重发一遍，不如**InstallSnapshot 一把梭**（SnapshotExecutorImpl:513）——Follower 收到快照 → 恢复状态 → 截断/覆盖本地日志 → 再回到 03 的日志补齐。**这就是"照片 vs 流水"的分工**：流水管增量，照片管追平。

## §3 掌握卡（2 条）

1. **应用通道**：onCommitted→入队→**单线程按序**→lastAppliedIndex——顺序是复制状态机的生命线（并发 apply=三个状态）。
2. **快照三件**：定时/位距/手动触发 → 状态机存档 → truncatePrefix 截前缀；落后者走 installSnapshot（照片不发流水）。

## §4 边界（一句话翻篇）

- Disruptor 队列实现细节 → 翻篇（思想已给）。
- 快照上传/下载的 fileService 分块 → 用时读。
- 快照与配置变更的交互 → 06 对照篇不展开。

## §5 Self-Test（3 题）

- Q1：为什么单消费线程？并发 apply 会怎样？
- Q2：恢复状态 = 哪两步？截断发生在哪一步？
- Q3：落后节点的"照片 vs 流水"怎么选？

## §6 衔接

- 原理三件套（选举/复制/状态机）收口；下篇 05 使用：起一个 3 节点集群 + 读一致（lease read/线性读）。
