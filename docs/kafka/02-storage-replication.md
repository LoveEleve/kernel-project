---
title: "02-原理一：存储与复制"
series: "Kafka 核心与对照系列"
previous: "01-log-model.md"
next: "03-消费与重平衡.md（待写）"
source_refs:
  - "kafka 源码已核：storage 模块 LogSegment.java/UnifiedLog.java（3.x 重构后 Java）；core Partition.scala（isr:198/LEO/HW 引用）；ReplicaManager.scala"
---

# 02-原理一：存储与复制

## §0 它干了什么（例子先行）

一个 Partition 的物理长相：

```
orders-2/                          ← Partition 2 的日志目录
├── 00000000000000880000.log       ← 消息本体（文件名=起始 offset）
├── 00000000000000880000.index     ← 偏移索引（稀疏！每 ~4KB 记一条）
├── 00000000000000880000.timeindex ← 时间索引
└── 00000000000000930000.log       ← 段滚动了（默认 1GB/段）——新段新起始
```

读 offset=8850：**先查 .index 找"最近的小于等于 8850 的条目"→ 定位到 .log 大致位置 → 顺序扫**——稀疏索引=用少量内存换"不必全量索引"。

- **它干了什么**：**分段存储+稀疏索引**——每个分区一串日志段文件；查消息先索引粗定位再顺序扫。
- **本篇回答**：LEO/HW/ISR 三兄弟各是什么？acks 与它们什么关系？和 RocketMQ 存储差在哪？

## §1 概念铺垫（What：三兄弟）

| 词 | 一句话 |
|---|---|
| **LEO**（Log End Offset） | 日志末尾的下一个写入位——**每个副本各自一份** |
| **HW**（High Watermark） | **已同步到 ISR 全体的位点**——消费者只能读到 HW 之前（"已提交"） |
| **ISR**（In-Sync Replicas） | 跟得上的副本集合（Partition.scala `isr: Set[Int]` :198）——**动态进出**：落后超阈值被踢，追上了再进 |

```
写入：Leader LEO++ → Follower 拉取同步 → 都到位的位点推进 HW
读取：消费者最多读到 HW（HW 之后=未确认，可能丢——对消费者不可见）
```

**acks 与三兄弟的关系**（04 展开）：`acks=0` 发完就忘；`acks=1` Leader 落盘即回（HW 还没推——Leader 挂会丢）；`acks=all/-1` 等 ISR 都同步（HW 推进才回——最稳）。

## §2 与 RocketMQ 存储对照（01 §3 的深挖）

| 维度 | Kafka | RocketMQ |
|---|---|---|
| 存储组织 | **每分区独立日志段组**（.log+.index+.timeindex） | **全 Topic 混写单 CommitLog** + ConsumeQueue 索引 |
| 索引 | 稀疏偏移索引（每 ~4KB 一条） | ConsumeQueue 每条消息都记（20B 定长） |
| 复制粒度 | **分区级**（leader/follower + ISR 动态集合） | **整机级**（Master/Slave 全量复制） |
| 读定位 | index 粗定位+顺序扫 | ConsumeQueue 定长数组直接定位 |
| 顺序写 | 分区内追加 | 全局单文件追加 |

**设计哲学差异**：Kafka"**分区自治**"（每个分区自成一世界——扩展/复制/消费全按分区）；RocketMQ"**全局日志+索引分层**"（混写省磁盘寻道，索引另存）。02 系列 RocketMQ 03 的"一次顺序 IO"与 Kafka 的"每分区顺序 IO"——**都是顺序写，粒度不同**。

## §3 工程使用

| 配置 | 说明 |
|---|---|
| `log.segment.bytes` | 段大小（默认 1GB）——滚动触发 |
| `log.retention.hours/bytes` | 保留策略（时间/大小）——段级删除 |
| `replica.lag.time.max.ms` | **ISR 踢人阈值**（跟不上的副本多久踢出） |
| `min.insync.replicas` | acks=all 时的最小 ISR 数——**1 与 2 决定可用性 vs 一致性** |

**运维要点**：`min.insync.replicas=1` + `acks=all` 退化成"只等 Leader"——高可用配置必须两参数配合看（面试坑）。

## §4 掌握卡（2 条）

1. **三兄弟时序**：LEO（写入进度）→ 副本同步 → HW（可读线）——**消费者只认 HW**；ISR 是"同步达标者集合"的动态管理。
2. **对照记忆**：Kafka 分区独立段+稀疏索引+分区级 ISR；RocketMQ 混写 CommitLog+定长 ConsumeQueue+整机主从——**存储模型之别**（01 §3 首分界的展开）。

## §5 边界（一句话翻篇）

- 日志清理（delete/compact 策略）→ 知道即可。
- Leader 选举细节（Controller 指派）→ 06 一句。
- 页缓存/零拷贝调优 → 翻篇（RocketMQ 03 已给同族概念）。

## §6 Self-Test（3 题）

- Q1：稀疏索引为什么能省内存？读一条消息的定位两步？
- Q2：LEO 和 HW 差在哪？消费者读不到 HW 之后的消息意味着什么？
- Q3：`acks=all` + `min.insync.replicas=1` 有什么问题？

## §7 衔接

- 存储与复制收口；下篇 03 消费与重平衡：消费组/GroupCoordinator/重平衡协议——Kafka 最"疼"的机制。
