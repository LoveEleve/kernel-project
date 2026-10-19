---
title: "01-什么是 Raft：三件套与 JRaft 组件地图"
series: "SOFAJRaft 共识系列"
previous: "README.md"
next: "02-election.md（待写）"
source_refs:
  - "sofa-jraft 源码已核：NodeImpl:142 / Replicator:86 / BallotBox:46 / FSMCallerImpl:74 / ReplicatorGroupImpl:51 / SnapshotExecutor"
cross_refs:
  - "dist-theory 02（Raft 原理 95 行精要——本篇是它的源码深挖起点，不重复）"
  - "zookeeper 02/05（ZAB）、nacos 04（JRaft 服务端）、kafka 02（ISR）——06 对照篇的基线"
---

# 01-什么是 Raft：三件套与 JRaft 组件地图

## §0 它干了什么（例子先行）

一条写请求在 JRaft 集群里的旅程：

```
客户端 → Leader 节点 NodeImpl.apply(task)
  → 写进本地日志（LogEntry, 持久化）
  → Replicator 把日志推给 Follower 们（每节点一个 Replicator）
  → BallotBox 算"多数派确认了吗" → 多数派到 → 标记 committed
  → FSMCaller 按日志顺序调用业务状态机（stateMachine.apply)
  → 客户端收到"成功"
```

- **它干了干什么**：**把 Raft 论文的规则变成可运行的 Java 代码**——"多数派才提交/日志只追加/每任期一票"这些纸面规则，在 JRaft 里就是 NodeImpl/Replicator/BallotBox 几个类的协作。
- **本篇回答**：Raft 三件套一句话是什么（回链不重复）？JRaft 五组件各管哪条规则？

## §1 概念铺垫（What）

**① 三件套规则**（一句话版，推导与脑裂细节见 **dist-theory 02**——已封卷，不重复）：

| 机制 | 一句话 |
|---|---|
| 选举 | 心跳超时→任期+1→拉票→多数派当选（每任期每票） |
| 日志复制 | Leader 追加→复制→多数派确认→提交；同 index+term 日志必相同 |
| 安全约束 | 候选人日志"至少一样新"；Leader 只能提交当前任期日志 |

**② sofa-jraft 是什么**：蚂蚁开源的 **Java Raft 实现**（全仓 648 个非测试类，其中核心 `jraft-core` 279 个）——不是论文，是**生产级组件**（快照/流转/配置变更全有）。

**③ 组件地图**（五组件 × 三件套对应——本系列的"总导航"）：

| 组件 | 行号 | 管什么 | 对应哪条规则 |
|---|---|---|---|
| **NodeImpl** | :142 | 节点门面——状态机开关（follower/candidate/leader） | 选举的执行者 |
| **Replicator** | :86 | 每个 Follower 一个"复制员"——推日志、收响应 | 日志复制的执行者 |
| **BallotBox** | :46 | 计票箱——**数"多数派到了没"**，推进 committed 位点 | 多数派提交 |
| **FSMCallerImpl** | :74 | 把 committed 日志**按序**交给业务状态机 | 复制状态机的"确定顺序" |
| **SnapshotExecutor** | storage/ | 快照+日志截断——追不上就"发照片不发流水" | 工程化必需（04 篇） |

**④ 对照地图**（本系列反复走的坐标——详细差异 06 篇）：

| | Raft（JRaft） | ZAB（ZooKeeper） | JRaft in Nacos | KRaft（Kafka） |
|---|---|---|---|---|
| 出身 | 论文的工程实现 | ZK 专用广播协议 | **JRaft 被嵌进 Nacos 做持久实例** | Kafka 去 ZK 的元数据层 |
| 回链 | 本系列 | zk 02/05 | nacos 04 | kafka 01（知道即可） |

## §2 为什么值得深挖源码（推导）

**朴素答案**：dist-theory 02 已经会背三件套了，还看什么源码？

**But**：面试追问永远落在**工程细节**——"PreVote 是什么？""快照发了日志怎么截？""committed 怎么推的？"——**纸面规则答不了工程追问**。

**Therefore**：本系列 = 把 02 的每条规则**落到类与方法上**。每篇一条规则的"原理→代码"。

**Cost**：不追形式化证明、不追压测数字——安全约束推导只讲"为什么这么定"。

## §3 掌握卡（2 条）

1. **三件套**（选举/复制/安全）——回链 dist-theory 02，本系列管工程实现。
2. **五组件地图**：NodeImpl（选举）/Replicator（复制）/BallotBox（计票提交）/FSMCaller（按序应用）/SnapshotExecutor（快照截断）——**后面每篇深挖一个**。

## §4 边界（一句话翻篇）

- Paxos 形式化 → dist-theory 02 §3.4 已给一句话版，翻篇。
- rheakv/MultiRaft → 知道即可（README 已列）。
- consensus-lab 实验 → 未建，不阻塞（README 已声明）。

## §5 Self-Test（3 题）

- Q1：一条写请求从 apply 到状态机，经过哪四个组件？
- Q2：三件套分别由哪个组件执行？
- Q3：JRaft 与 Nacos 里的 Raft 是什么关系？（实现与嵌入方）

## §6 衔接

- 总导航收口；下篇 02 选举：term/投票/PreVote+Leader Lease——JRaft 对标准 Raft 的两个工程优化。
