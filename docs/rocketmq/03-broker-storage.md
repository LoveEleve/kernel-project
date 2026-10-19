---
title: "03-存储内核：CommitLog三件套"
series: "RocketMQ 系列"
previous: "02-producer-send.md"
next: "04-消费原理.md（待写）"
lab_refs:
  - "rocketmq-lab/docs/D4-Broker-存储机制.md"
---

# 03-存储内核：CommitLog三件套

## §0 它干了什么（例子先行）

一条订单消息到 Broker 后的 24 小时：

```
收包 → CommitLog 尾部追加（进 PageCache，即刻返回）
  → 500ms 后刷盘 → Reput 分发 20B 索引 → Slave 拉走
  → 消费者按序号取走 → 72h 后过期删除（不管消费没消费）
宕机重启：abort 文件判异常 → 倒查 3 文件 → 半截丢弃 → 索引追平
```

- **它干了什么**：写一次顺序 IO，索引/刷盘/复制全走旁路；删除只看过期，不看消费。
- **输入/输出**：输入=Producer 请求；输出=落盘本体 + 可消费索引 + 可反查 Key。
- **本篇回答**：三文件各存什么？刷盘复制怎么配？宕机怎么恢复？消息活多久？

## §1 掌握卡（4 条）

1. **写路径只有一次顺序 IO**：CommitLog 追加是唯一的写；分发、刷盘、复制全异步——单机十万 TPS 的根基。
2. **三文件分工**：CommitLog 本体（1GB/文件）+ ConsumeQueue 定长索引（20B/条）+ IndexFile（Key 反查）。
3. **可靠性是 2×2 矩阵**：刷盘 SYNC/ASYNC × 复制 SYNC/ASYNC；默认双异步（丢 500ms 窗口 + 未同步部分），金融级双同步。
4. **offset 是序号**：消费进度 = ConsumeQueue 条目序号，不是物理偏移；消息最长活 72h，超期物理删。

## §2 写入链路

```
SendMessageProcessor（收包）→ putMessage
  → CommitLog.appendMessage（mmap 写 PageCache，1GB/文件，文件名=起始偏移）
  → 刷盘（独立线程）：SYNC 等落盘（5s 超时）/ ASYNC 每 500ms 批量
  → ReputMessageService（单线程）：新写入 → 三分发器
  → HA：Slave 拉取（SYNC_MASTER 等确认才返）
```

## §3 三类文件

| 文件 | 单文件 | 条目 | 角色 |
|---|---|---|---|
| CommitLog | **1GB** | 变长（头+properties+body） | 本体，全 Topic 混部追加 |
| ConsumeQueue | 300000×20B≈**6MB** | **定长 20B**（8B 偏移+4B 大小+8B tagsCode） | 消费索引（`consumequeue/{topic}/{queueId}/`） |
| IndexFile | 500 万槽+2000 万条目 | Key hash→链表（时间倒序） | Key 反查 |

**定长技巧**：`第 N 条 = N×20B` 直接定位——offset 就是数组下标，无需查找结构。

**双偏移别混**：`physicOffset`（CommitLog 字节位，offsetMsgId 编码它）vs `queueOffset`（ConsumeQueue 逻辑序号，消费进度记它）。异常退出的半截写入靠 magicCode+CRC 识别丢弃。

## §4 异步分发

单线程 `ReputMessageService` 从上次位点扫新写入，转 `DispatchRequest` 进三分发：BuildConsumeQueue（算 tagsCode）/ BuildIndex（有 KEYS 才建）/ Compaction（5.x 默认关）。

存在极短窗口（CommitLog 有、索引还没有）——长轮询靠 `MessageArrivingListener` 分发后唤醒，无感；宕机重启靠位点对账追平（§7）。

## §5 刷盘 × 复制

| | SYNC_FLUSH | ASYNC_FLUSH（默认） |
|---|---|---|
| 实现 | GroupCommit 等落盘（5s 超时） | 每 500ms 批量刷 |
| 宕机 | 不丢 | 最多丢 500ms 窗口 |

复制正交组合：SYNC_MASTER 等 Slave 确认；ASYNC_MASTER（默认）写完即返。Controller 模式（Raft=多数派投票选主，默认关）才自动升主，否则人工运维。索引刷盘更宽松（1000ms）——索引丢了可重建，本体不行。

## §6 读路径

```
（topic, queueId, consumeOffset）→ ConsumeQueue 第 N 条 → {commitLogOffset, size, tagsCode}
  → tagsCode 不对跳过（Broker hash 粗筛）
  → CommitLog 读 size 字节 → PageCache 命中直返，未中缺页读盘（缺页=要的数据不在内存，现读盘；冷数据常触发，是 Broker 毛刺的主因）
```

条目小且连续，热数据两次定位基本全中 PageCache——"两次随机读变顺序读"。

## §7 启动恢复

abort 文件在 = 异常退出 → `recoverAbnormally`（定位最后完整消息，半截丢）→ 超出索引位点的本体重新分发（重发一遍结果不变，这就叫幂等——02 说的两端义务之一）→ 恢复 offset 表。正常退出从倒数第 3 文件校验——**只倒查 3 个，不全扫**，恢复速度可控。

## §8 清理：只看过期，不看消费

`fileReservedTime=72h` + 凌晨 4 点触发。**积压超 72h 的消息物理删除，永远读不到**——堆积报警必须远早于此。磁盘水位三档：>75% 拒写 → >85% 强制提前清 → >90% 告警。

## §9 不丢分析（默认配置逐环节）

| 环节 | 会不会丢 |
|---|---|
| Producer→Broker | SEND_OK 才认；超时业务决策（02） |
| PageCache→磁盘 | **会**（500ms 窗口） |
| Master→Slave | **会**（异步复制） |
| 索引滞后 | 本体不丢，重启重建 |
| 积压超 72h | **会**（过期删） |
| 优雅重启 | 不丢 |

严格不丢 5 条件：SYNC_FLUSH + SYNC_MASTER + 等 SEND_OK + 消费幂等 + 72h 内消费完——每环都有代价。

## §10 边界（这里一句话翻篇）

- 二进制全字段、堆外写缓冲（默认关）→ 用时查 `lab D4 §二`。
- Controller Raft 细节 → 08 一句。
- 消费端 offset 提交 → 04；重试/DLQ → 07。

## §11 Self-Test（4 题）

- Q1：写路径几次顺序 IO？分发/刷盘/复制在哪条路上？
- Q2：offset 是什么的序号？physicOffset 和 queueOffset 各记在哪？
- Q3：默认配置哪三处会丢？严格不丢的 5 条件？
- Q4：消息被消费了会删除吗？什么删？超 72h 积压会怎样？

## §12 衔接

- 本篇回答"存哪"；下篇 04 回答"怎么消费"（长轮询 + 重平衡 + NameServer 路由）。
