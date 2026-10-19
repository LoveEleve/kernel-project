---
title: "06-深水：Raft vs ZAB vs Nacos JRaft vs KRaft"
series: "SOFAJRaft 共识系列"
previous: "05-usage.md"
next: "07-interview-wrapup.md（待写）"
cross_refs:
  - "dist-theory 02（Raft 原理）/ zookeeper 02（ZAB/zxid/epoch/选主比 zxid:18）/ nacos 04（JRaft 服务端）——已封卷；KRaft 行为通识+kafka 01 仅边界一句，本篇只做组装"
  - "学习计划 L5-2 落点原文（xhs 无自研共识→诚实答口径）"
---

# 06-深水：Raft vs ZAB vs Nacos JRaft vs KRaft

## §0 它干了什么（例子先行）

同一个问题，四个系统给了四个答案——"**多个节点怎么对'谁是主、哪些数据算数'达成一致**"：

```
ZooKeeper（ZAB）    → 选举 zxid 最大的 → 原子广播 PROPOSAL/COMMIT 两阶段
SOFAJRaft（Raft）   → 任期+多数派 → AppendEntries 复制计票（02-04 篇）
Nacos（嵌 JRaft）   → 只给"持久实例"用——配置/临时实例另有协议（nacos 04 已证）
Kafka（KRaft）      → Controller Quorum 自治——去 ZooKeeper，元数据日志自己管
```

- **它干了干什么**：**共识是"零件"不是"产品"**——每个系统把它嵌在不同的位置、只管不同的数据。
- **本篇回答**：四者差异一张表？xhs 没自研共识，面试怎么答才诚实又有货？

## §1 概念铺垫（What：六维全景——全部带已审出处）

| 维度 | Raft（JRaft） | ZAB（ZooKeeper） | JRaft in Nacos | KRaft（Kafka） |
|---|---|---|---|---|
| 出身 | 论文工程化（本系列） | ZK 专用广播协议 | **被嵌入** Nacos | KIP-500（Kafka 改造提案：去掉 ZooKeeper） |
| 选举依据 | term+日志新旧（logIsOk 02 篇） | **zxid 最大优先**（zk 02:18） | 同 JRaft | Controller 选主（同族 Raft 变体） |
| 进度标识 | logIndex+term | **zxid=epoch+counter**（zk 02:33） | 同 JRaft | 元数据日志 offset |
| 复制协议 | AppendEntries（03 篇） | **原子广播两阶段**（zk 02） | 同 JRaft | 元数据日志复制 |
| 管什么 | 业务自定义状态机 | znode 树 | **仅持久实例**（nacos 04） | **仅集群元数据** |
| 不管什么 | —— | —— | 配置=共享 MySQL+AsyncNotify（dist-theory 02 §3.3）、临时实例=Distro（nacos 04） | 分区数据（数据本体是"无共识"的分段文件，kafka 02） |

**一句话轴**：**ZAB 为"目录服务"量身、Raft 为"通用状态机"设计、KRaft 只把共识收缩到元数据一层、Nacos 把 JRaft 塞进一个功能点**——四个都不是"谁比谁强"，是**管辖范围不同**。

## §2 落点题推导：xhs 怎么答（学习计划 L5-2 原文口径）

**朴素答案（要避开）**："我研究过 Raft，选主是 term+1 拉票…"——背书，且被追问"你用在哪"就露馅。

**诚实答**（计划定死的口径，可展开成三段）：

```
① 事实：xhs 没有自研共识组件——共识层在我用的中间件里：
   Nacos 持久实例（JRaft）、Kafka KRaft 元数据（若用）
② 语义消费：我实际消费的是它的语义——leader 选举（05 篇 LeaderStateListener 同款思路）、
   强一致配置（Nacos 持久实例读写）、事务消息的"多数派持久化"心智（RMQ 06 回链）
③ 打开看过：源码层面我能讲 PreVote 防 term 通胀（02）、commitAt 计票推进（03）、
   快照截断（04）——共识不是黑盒
```

**为什么这样答赢**：**区分了"用过"与"建过"**——面试官问的是诚实边界，不是吹牛边界；而"打开看过"把深度补了回来。

**反向补充**（防追问）：如果要给 xhs 加"配置强一致"，**选型路径**=直接用 Nacos 持久化（JRaft 已在），而不是自己起 JRaft——**共识组件的正确姿势是"用成熟的"**（自研=dist-theory 02 §二 的"脑裂+丢数据"全套风险自付）。

## §3 掌握卡（2 条）

1. **六维全景**：选举依据（term/log vs zxid）、进度标识、复制协议、**管辖范围**——最后一行（管什么/不管什么）是区分度所在。
2. **落点话术三段**：无自研（事实）→ 消费语义（用）→ 打开看过（深度）——外加"要加就用现成的"收尾。

## §4 边界（一句话翻篇）

- ZAB 与 Raft 的形式化对比论文 → 学术线。
- KRaft 的 quorum voter 配置细节 → Kafka 线"知道即可"。
- 自建共识的工程清单 → 真有需求再翻 dist-theory 02。

## §5 Self-Test（3 题）

- Q1：zxid 和 (logIndex,term) 各是什么系统的世界观？
- Q2：Nacos 哪些数据走 JRaft、哪些不走？为什么？
- Q3：落点题三段怎么答？

## §6 衔接

- 深水线收口；下篇 07 面试收口：六篇回链 + 高频坑 + 终检——本系列完结。
