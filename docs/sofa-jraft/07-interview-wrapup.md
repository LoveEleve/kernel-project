---
title: "07-面试收口：六篇回链与陷阱"
series: "SOFAJRaft 共识系列"
previous: "06-three-consensus.md"
next: "（本系列完）"
---

# 07-面试收口：六篇回链与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① 六篇要点回链 ② 高频坑速查 ③ 话术（自建）+ 终检
```

## §1 六篇要点回链

| 篇 | 一句话考点 | 回链 |
|---|---|---|
| 01 | 三件套回链 dist-theory 02；**五组件地图**（NodeImpl/Replicator/BallotBox/FSMCaller/SnapshotExecutor） | 01 |
| 02 | **两轮选举**：PreVote 虚拟任期 term 不涨；Leader Lease 防干扰；投票三条件（term/votedId/logIsOk） | 02 |
| 03 | 四步链：落盘→复制→**记票 commitAt**→过半推进→onCommitted；衔接校验=日志匹配执法 | 03 |
| 04 | **单线程按序应用**（顺序=生命线）；快照三触发→truncatePrefix；照片不发流水 | 04 |
| 05 | 读三模式：本地=可能旧 / Lease=时间换 RPC / Safe=多数派心跳确认（**租约失效自动降级 :1648**） | 05 |
| 06 | 六维全景（**管辖范围是区分度**）；落点三段诚实答法 | 06 |

**追问秒答 5 条**：PreVote 为什么不涨 term→落选零成本防 term 通胀（02）；committed 怎么推→commitAt 记票+连续过半（03）；为什么单线程 apply→同一顺序同一状态（04）；Lease 读凭什么省心跳→租约期内无新主（05）；ZAB 和 Raft 区别→zxid vs term+log、管辖范围（06）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | 超时就 term+1 直接选 | 先 PreVote——落选也涨 term=干扰正主（02） |
| 2 | 读 Follower 直接读本地 | 可能旧——线性读走 readIndex（05） |
| 3 | LeaseBased 当万能快读 | 时钟漂移即险——租约失效自动降级 Safe:1648（05） |
| 4 | 日志无限增长 | 定时/位距快照+truncatePrefix（04） |
| 5 | 以为写成功=多数派提交 | 发出≠提交——commitAt 计票才算（03） |
| 6 | 落后节点靠重放追 | 几百万条重发→installSnapshot 照片一把梭（04） |
| 7 | 落点题吹"我用 Raft 做过项目" | 三段诚实答：无自研→消费语义→打开看过（06） |
| 8 | 给 xhs 自研共识 | 用现成的（Nacos 持久实例已是 JRaft）（06） |

## §3 话术（按拷打口径自建）

> "共识我按 Raft 的工程实现（SOFAJRaft）讲：选举是**两轮**——PreVote 用虚拟任期试水、term 不涨，防孤立节点 term 通胀掀翻正主；Leader Lease 让租约期内的骚扰票被拒。日志复制四步链：本地落盘→Replicator 携 prevLogIndex/committedIndex 推→Follower 回执进 BallotBox 记票→连续位点过半推进 committed→FSMCaller **单线程按序**应用到状态机——顺序是复制状态机的生命线。快照定时触发、truncatePrefix 截前缀，落后者 installSnapshot 不重发流水。读有三模式：本地可能旧、Lease 时间换 RPC、ReadIndex 要多数派心跳确认、租约失效自动降级 Safe。对照上：ZAB 用 zxid 选主、KRaft 只把共识收到元数据层、Nacos 只在持久实例用 JRaft——**管辖范围不同不是优劣**。xhs 没自研共识，我消费的是语义，但源码打开看过。"

## §4 终检（6 题，过 4 算过关）

1. 五组件地图；写请求四步链（01/03）
2. 两轮选举+投票三条件（02）
3. commitAt 过半推进；衔接校验（03）
4. 单线程 apply 为什么；快照截断怎么配合（04）
5. 读三模式+租约降级（05）
6. 四系统管辖范围+落点三段（06）

## §5 衔接

- **SOFAJRaft 共识系列 7 篇完，README 全挂链接**——L5-2 闭环；dist-theory 02（原理）→ 本系列（源码）的纵深打通。
- 本 session 累计封卷 **20 个系列 / 141 篇**。
- 剩余候选：**Netty**（L5-1 基座，最大缺口）/ poly 家族 4 仓（polyconfig/polydata/polyzone/polygateway 成系列）/ L5-11 安全、L5-13 测试、L5-14 DDD——你点。
