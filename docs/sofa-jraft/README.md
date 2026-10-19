# SOFAJRaft 共识系列

> 语料：**sofa-jraft 源码**（648 非测试 Java 文件——已核；jraft-core 主模块）
> 关键锚点（已核行号）：NodeImpl:142 / Replicator:86 / BallotBox:46 / FSMCallerImpl:74 / SnapshotExecutor / ReplicatorGroupImpl:51
> 对照基线（全部已封卷）：**dist-theory 02（Raft 原理）/ ZooKeeper 02（ZAB）05（选主）/ Nacos 04（JRaft 服务端）/ Kafka 02（ISR 对照）**
> xhs 落点（学习计划 L5-2 定死）：**项目无自研共识 → 面试诚实答"共识层在中间件里，我消费它的语义"；lab 把它打开看**
> 诚实边界：**无 lab**（计划里的 consensus-lab 未建）、**无拷打**——本系列以"源码+对照+概念"为主

## 一、总表（7 篇：概念 → 原理/源码 → 使用 → 对照 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 Raft：三件套与 JRaft 组件地图](01-what-is-raft.md) | **概念** | 选举/日志复制/安全性；五组件地图；与 ZAB/KRaft 对照 | dist-theory 02+源码 |
| 02 | [原理一：选举](02-election.md) | **原理** | term/投票/PreVote+Leader Lease 防干扰（防 term 通胀/防骚扰正主） | NodeImpl 源码 |
| 03 | [原理二：日志复制与提交](03-log-replication.md) | **原理** | LogEntry/Replicator/匹配点/committed 推进 | Replicator/BallotBox |
| 04 | [原理三：状态机与快照](04-fsm-snapshot.md) | **原理** | FSMCaller 应用顺序/快照/日志截断 | FSMCallerImpl/SnapshotExecutor |
| 05 | [使用：起集群与读一致](05-usage.md) | **使用** | jraft-example；读三模式（本地/Lease/ReadIndex） | jraft-example+读路径源码 |
| 06 | [深水：Raft vs ZAB vs Nacos JRaft vs KRaft](06-three-consensus.md) | **深水** | 四维全景（管辖范围是区分度）；xhs 落点题 | 四系列对照 |
| 07 | [面试收口](07-interview-wrapup.md) | **面试** | 六篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03 → 04
使用：05
对照：06
收口：07
```

## 三、前置要求

- dist-theory 02（Raft 原理 95 行精要——本系列是它的源码深挖版）。
- ZooKeeper 02/05（ZAB 已封卷，对照基准）。
- Nacos 04（JRaft 服务端已封卷）。

## 四、掌握分级

- **必须掌握**：三件套；term/投票；日志复制的匹配点与 committed 推进；快照与日志截断；三套共识差异。
- **理解思想**：**"安全第一"的设计**——所有优化（PreVote、Leader Lease 防干扰、lease read）都在不破坏安全性前提下省开销。
- **知道即可**：rheakv（分布式 KV 用法）；MultiRaft（group 分片）；性能压测数字。

## 五、边界

- jraft-rheakv 详解 → 知道即可。
- 形式化证明 → 学术线。
- consensus-lab 实验（计划里有，未建）→ 有空再补，不阻塞。
