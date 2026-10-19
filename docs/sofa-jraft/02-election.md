---
title: "02-原理一：选举"
series: "SOFAJRaft 共识系列"
previous: "01-what-is-raft.md"
next: "03-log-replication.md（待写）"
source_refs:
  - "NodeImpl.java 已核：handleElectionTimeout:620 / allowLaunchElection:640 / preVote:645,2787 / handlePreVoteRequest:1774（lease 拒绝:1804）/ handleRequestVoteRequest:1875（授权:1929-1944）/ 授权写票:1933+setVotedFor:1935 / setTermAndVotedFor:1342（term 变更清空）/ checkLeaderLease:1856"
---

# 02-原理一：选举

## §0 它干了什么（例子先行）

3 节点集群，Leader 挂了——JRaft 里发生什么（**两轮**，比论文多一轮）：

```
① A 收不到 Leader 心跳 → electionTimer 触发 handleElectionTimeout:620
   → isCurrentLeaderValid() 为假 → 不直接开选，先 preVote():645 【第一轮：试选】
② PreVote：A 以"虚拟任期 currTerm+1"拉票（自己 term 先不涨！）
   → 若能拿到多数派 → 才走正式投票 handleRequestVoteRequest:1875，term+1、写 votedId:1933 并 setVotedFor 落盘:1935
③ 多数派授权 → A 当选 Leader → 开始心跳
```

- **它干了干什么**：**先试选再正选**——PreVote 让"没戏的选举"根本不消耗 term；Leader Lease 让"活着的正主"不被骚扰下台。
- **本篇回答**：标准 Raft 选举有什么坑？JRaft 两个优化各自怎么解？投票授权的两个条件？

## §1 概念铺垫（What：坑与解）

| 标准 Raft 的坑 | JRaft 的解 | 源码锚点 |
|---|---|---|
| **term 通胀**：网络抖动/新节点入群，一个孤立节点反复拉票 → term 蹿升 → 还在任的合法 Leader 被"更高 term"逼退 → 集群反复换主 | **PreVote 先试选**：拿不到多数派连 term 都不涨 | preVote:645,2787 |
| **骚扰正主**：失联节点的选举请求把合法 Leader 踢下台（"disruptive server"） | **Leader Lease**：我与 Leader 的租约还有效 → 拒绝 PreVote | handlePreVoteRequest:1804（"lease is still valid"） |
| 投票无记名 | **votedId 持久化**（每 term 只投一次跨重启也认账） | setVotedFor:1935（授权时）/setTermAndVotedFor:1342（term 变更清空重落）|

**投票授权的两个条件**（handleRequestVoteRequest:1875 的核心）：

```
① term 对等（候选人 term ≥ 我的 currTerm，小的直接拒绝/自己退）
② 我还没投过这个 term（votedId 空 :1929）——且（标准 Raft 安全约束）
③ 候选人日志"至少一样新"（logIsOk :1929——回链 dist-theory 02 §3.2 安全约束）
```

**term 的三个角色**（贯穿本系列）：**逻辑时钟**（比较新旧）/ **选票防重放**（每 term 一票）/ **任期归属**（日志条目带 term——03 篇提交约束用）。

## §2 两轮选举的推导（为什么 PreVote 是"先试选"）

**朴素答案**：超时了就喊"我要当官"（term+1 再拉票）。

**But**：孤立节点每次都成功把 term 抬高——**它自己选不上，却把真领导掀翻了**。

**Therefore**：**先拿"模拟票"试探局势**——PreVote 用 `currTerm+1` 当虚拟任期去问，但**不动本地 term**（:1219 `setPreVote(false)` 才是正式票的分界）；多数派同意说明"民心可用"，再走正选。**落选零成本**。

**Cost**：多一轮 RPC——只有选举时付，稳态无感。

**同族对照**：这个"防骚扰"思想与 **Nacos/Distro 的反熵、Sentinel 的熔断**是同款——**先探测、后动真格**（工程通感）。

## §3 掌握卡（2 条）

1. **两轮选举**：PreVote（虚拟任期、term 不涨、落选免费）→ 正式投票（term+1、votedId 落盘、logIsOk 安全约束）——**02 是 dist-theory 02 §3.2 选举规则的代码版**。
2. **Leader Lease 两用**：①防干扰（拒绝骚扰票 :1804）②lease read（05 篇读优化 :1648）——**同一个租约对象，两处受益**。

## §4 边界（一句话翻篇）

- voteTimer/RepeatedTimer 定时器实现 → 翻篇。
- priorityelection（优先级选举，jraft-example 里有）→ 知道存在。
- PreVote 的完整状态机转移图 → 用时读源码。

## §5 Self-Test（3 题）

- Q1：PreVote 为什么不涨 term？解决什么问题？
- Q2：投票授权的三个条件？（term/未投票/日志新）
- Q3：Leader Lease 在哪两处生效？

## §6 衔接

- 选举收口；下篇 03 日志复制与提交：Replicator 推日志、BallotBox 计票、committed 位点怎么推进。
