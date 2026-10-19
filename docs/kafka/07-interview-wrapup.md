---
title: "07-面试收口：六篇回链与陷阱"
series: "Kafka 核心与对照系列"
previous: "06-kafka-vs-rocketmq.md"
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
| 01 | 四件套（Topic/Partition/Offset/组）；**书签是消费者私事**——进度归属是与 RocketMQ 第一分界 | 01 |
| 02 | 日志段+稀疏索引；**LEO/HW/ISR 三兄弟**；acks 与 HW 关系 | 02 |
| 03 | GroupCoordinator；重平衡为什么疼；CooperativeSticky+静态成员两把钥匙 | 03 |
| 04 | 攒批三段（Accumulator→Sender→回调）；幂等=序号去重；事务包不了 MySQL | 04 |
| 05 | 三件套（Template/Listener/ErrorHandler）；Polycache 背压+分级重试实锤 | 05 |
| 06 | 六维全景；xhs 三刚需（跨DB事务/延迟/治理）；反向选 Kafka 场景 | 06 |

**追问秒答 4 条**：HW 是什么→ISR 全体同步到位、消费者只读到这（02）；重平衡怎么治→CooperativeSticky 增量+group.instance.id 静态成员（03）；幂等防什么→重试重复、防不了逻辑重复、要求 acks=all（04）；Kafka 事务能包 MySQL 吗→不能、跨系统用 RocketMQ 半消息（04/06）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | acks=all 但 min.insync.replicas=1 | 退化成只等 Leader——两参数配合看（02） |
| 2 | 异步消费业务处理超 max.poll.interval.ms | 实例被踢→全组触发重平衡——维持 poll/heartbeat（03） |
| 3 | Eager rebalance 每次全停 | 换 CooperativeSticky（03） |
| 4 | 开了幂等却只配 acks=1 | 幂等前提 acks=all——配置不生效（04） |
| 5 | 以为 Kafka 事务能跨 MySQL | 包不了 DB——跨系统用半消息/Outbox（04/06） |
| 6 | 生产事务消息硬选 Kafka | 业务链路选 RocketMQ；日志/流管道才选 Kafka（06） |
| 7 | 重试无上限卡死分区 | DefaultErrorHandler+FixedBackOff 分级（05） |
| 8 | 无界队列缓存发送 | 有界+offer 拒绝——背压（05） |

## §3 话术（按拷打口径自建）

> "Kafka 我按日志模型讲：分区独立日志段+稀疏索引，offset 是分区内编号——**进度由消费者自己提交到 __consumer_offsets**，这是和 RocketMQ（broker 存、5s 上报）的本质分界。复制是分区级 ISR：LEO 是各自写入进度，HW 是 ISR 全体同步到位的可读线，acks=all 等的就是 HW 推进——所以 min.insync.replicas=1 会让 acks=all 退化。消费侧 GroupCoordinator 管组，重平衡疼，解法是 CooperativeSticky 增量重平衡+静态成员重启不触发。生产侧 send 异步三段攒批，幂等靠 (producerId,partition) 序号去重但要求 acks=all，事务只包 Kafka 内、包不了 MySQL——**所以我们 xhs 订单链路选 RocketMQ 半消息，Kafka 留给日志管道**，同公司按链路选型。"

## §4 终检（6 题，过 4 算过关）

1. 四件套；进度归属差异（01）
2. LEO/HW/ISR 三兄弟；acks=all 与 min.insync（02）
3. 重平衡两把钥匙（CooperativeSticky/静态成员）（03）
4. 幂等 vs 事务分工；事务边界（04）
5. Polycache 三启示（背压/分级重试/编程式容器）（05）
6. xhs 三刚需+反向选 Kafka 场景（06）

## §5 衔接

- **Kafka 核心与对照系列 7 篇完，README 全挂链接**——L5-4 闭环；与 RocketMQ 系列构成双 MQ 对照体系。
- 本 session 累计封卷 **19 个系列 / 134 篇**。
- 剩余候选：**Kafka 之外的 L5 缺口**（素材已尽的翻篇）——或你另点。
