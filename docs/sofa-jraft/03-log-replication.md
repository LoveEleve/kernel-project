---
title: "03-原理二：日志复制与提交"
series: "SOFAJRaft 共识系列"
previous: "02-election.md"
next: "04-fsm-snapshot.md（待写）"
source_refs:
  - "已核：LogManager.appendEntries:134（StableClosure:48）/ Replicator 请求构建:1673（prevLogIndex/prevLogTerm/committedIndex:1576）/ 成功回执 commitAt:1534 / BallotBox.commitAt:99→pendingMetaQueue 过半:102-137→onCommitted:141"
---

# 03-原理二：日志复制与提交

## §0 它干了什么（例子先行）

一条写请求从 Leader 落盘到"已提交"：

```
① NodeImpl.apply → LogManager.appendEntries(entries, StableClosure):134  本地日志落盘
② Replicator 给每个 Follower 构建 AppendEntriesRequest:1673：
   prevLogIndex/prevLogTerm（衔接校验）+ entries + committedIndex:1576（捎带已提交位点）
③ Follower 校验衔接 → 追加自己的日志 → 回"成功+我到哪了"
④ Leader 收到回执 → BallotBox.commitAt(范围, 该 Peer):1534,99  【记一票】
⑤ 计票发现"这个位点多数派都有了" → lastCommittedIndex 推进:137
   → waiter.onCommitted:141 → 通知 FSMCaller（04 篇接手）
```

- **它干了干什么**：**复制-计票-推进**三步——写请求的"成功"不是发出去，而是**位点进了 BallotBox 的已提交线**。
- **本篇回答**：衔接校验防什么？"多数派到哪"在代码里怎么算？committed 怎么"顺路"传播？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **AppendEntries** | Leader→Follower 的心跳+日志 RPC——请求里带 `prevLogIndex/prevLogTerm`（**衔接校验**）与 `committedIndex` |
| **衔接校验** | Follower 发现"我 prevLog 位置的 term 对不上" → 拒绝 → Leader 回退 nextIndex 重发——**这就是日志匹配性质的执法** |
| **commitAt（记票）** | 每收到一个 Follower 的成功回执，把"它覆盖到 X"记进 **pendingMetaQueue**（BallotBox:99） |
| **过半判定** | 队列算出"**连续位点里谁已过半**"（:102-137）→ 推进 `lastCommittedIndex`:137 → `onCommitted:141` |
| **committedIndex 捎带** | Leader 把已提交位点**塞进每个请求**:1576——Follower 借心跳就知道"该往上应用了"（**省专门 RPC**） |

## §2 关键推导（两问）

**① 为什么"发出去"≠"提交"？**
主从异步复制的教训（dist-theory 02 §二）：发出去没确认，Leader 挂→丢。**提交=多数派持久化**——所以代码里"成功回执"只是**记一票**（commitAt），真正的推进要看**连续位点是否过半**。

**② 为什么 committedIndex 捎带而不是专门通知？**
Follower 日志早就有这条数据，缺的只是"Leader 认可到哪了"——**搭顺风车**（每个 AppendEntries/心跳都带:1576）。同族思想：Nacos 04 的推送搭 gRPC 长连接、RocketMQ 长轮询搭心跳——**能捎带就不单开**。

**③ pendingMetaQueue 算什么**（面试够用版）：
每个位点需要的票数 = 多数派（Ballot.quorum = peers/2+1，entity/Ballot.java:80）；任务先经 `appendPendingTask` 入队（BallotBox:206-215），Follower 确认一票 `grant` 一次（quorum--），**从队首起连续过半的那段就是可提交段**（:133 先进先出消化）。**Leader 自己也投一票**——本地日志持久化回调里 commitAt 自投（NodeImpl:1398）。

## §3 与 02/04 的咬合

- **02 的选举限制为什么保提交**：候选人日志"至少一样新"→ 新 Leader 一定含全部已提交日志——**所以 commitAt 只在新主也数得清的范围里推进**。
- **04 的接口**：`onCommitted:141` 就是 FSMCaller 的入口——**BallotBox 只管"到哪了"，FSMCaller 管"按序用上"**（下篇）。

## §4 掌握卡（2 条）

1. **四步链**：本地落盘(:134)→复制(:1673)→记票(:1534/:99)→过半推进(:137)→onCommitted(:141)——**能默写这条链，复制题就通了**。
2. **衔接校验=日志匹配性质的执法**（同 index+term 内容必同——拒了就回退重发）；**committedIndex 捎带=省 RPC 的顺路通知**。

## §5 边界（一句话翻篇）

- Replicator 的流式发送/重试定时器 → 用时读。
- pendingMetaQueue 位图位运算细节 → 知道"连续过半"即可。
- Learner（非投票者）复制 → 知道存在（配置变更篇也不展开）。

## §6 Self-Test（3 题）

- Q1：写出复制-计票-推进四步链（带类名）。
- Q2：prevLogIndex/prevLogTerm 干什么？对不上会怎样？
- Q3：committedIndex 捎带为什么省 RPC？

## §7 衔接

- 复制收口；下篇 04 状态机与快照：FSMCaller 怎么"按序应用"、快照发了日志怎么截。
