---
title: "04-消费原理：长轮询与重平衡"
series: "RocketMQ 系列"
previous: "03-broker-storage.md"
next: "05-顺序消息与广播.md（待写）"
lab_refs:
  - "rocketmq-lab/docs/D5-Consumer-消费机制.md"
  - "rocketmq-lab/docs/D2-NameServer-路由发现.md"
---

# 04-消费原理：长轮询与重平衡

## §0 它干了什么（例子先行）

coupon 服务 3 个实例吃 `COUPON_CLAIM_TOPIC`（12 队列）：

```
每实例分 4 队列 → 长轮询拉（无消息挂 15s，有即返）→ 20 线程消费 → 成功 5s 报一次位点
一实例挂了 → 20s 后重分配（活着的两家各吃 6 队列）→ 5s 窗口的消息被重复吃一遍
Broker 宕机 → 路由里还留它 120s → 发送端重试换 Broker 兜底（02 §5 回顾）
```

- **它干了什么**：拉（长轮询）→ 缓（ProcessQueue）→ 吃（线程池回调）→ 认（报位点）；人变了重分，机死了等剔。
- **输入/输出**：输入=Queue 分配 + 位点；输出=业务回调 + 新位点（或重试/DLQ）。
- **本篇回答**：Push 怎么拉？队列怎么分？失败怎么办？位点怎么记？路由谁维护？

## §1 掌握卡（5 条）

1. **Push = 长轮询拉**：Broker 无消息挂起 15s（到期返空重拉，有即返，分发后唤醒）；单次最多 32 条/256KB；消费线程池 20。
2. **Rebalance 20s 一次**：每实例独立算（全量 Queue + 全体 clientId，排序过算法）；频繁上下线 = 事故（换 owner 导致 5s 窗口重复）。
3. **失败三分支**：并发（重试 16 次→DLQ）/顺序（挂起重试，毒丸堵队列）/广播（直接丢，只配无副作用场景）。
4. **offset 内存 + 5s 上报**：换 owner 从 5s 前起拉——重复区间 ≤5s；新 Group 起点只生效一次。
5. **NameServer 是 AP**：30s 全量重报自愈；故障剔除最长 120s+5s——这两分钟靠客户端重试掩盖。

## §2 长轮询循环

```
PullMessageService（单线程 take）→ pullKernelImpl(queue, offset, 32条, 挂15s)
  → 有消息即返 / 无挂起到期返空 / Reput 分发后唤醒
  → putMessage 进 ProcessQueue（TreeMap）→ 消费线程池回调 → 成功记位点 → 5s 上报
```

`pullBatchSize=32` / `pullBatchSizeInBytes=256KB` / `consumeMessageBatchMaxSize=1`（>1 才批消费）/ `pullInterval=0`（连续拉）。

## §3 Rebalance

触发：实例上下线（Broker 通知）/ Topic 路由变 / 每 20s 全量。**实例间不协商**，各算各的"我吃哪些"。

| | 集群（默认） | 广播 | 顺序 |
|---|---|---|---|
| 算法 | 6 种可选，默认平均 | 拿全量 | 同集群 + 全局锁 |
| 顺序锁 | — | — | `lockAll()` 向 Broker 申请，20s 续期（到期前重新申请，锁一直有效），宕机过期被抢 |

**两事故模式**：频繁 Rebalance（队列换 owner——owner=队列当前归谁吃——叠加 5s 上报窗口 = 批量重复）；广播每次拿全集（只刷新位点，不分摊）。

## §4 ProcessQueue：每队列一个 TreeMap 缓存

`msgTreeMap`（TreeMap=按 key 有序存的 Map，这里 key 是 offset，未消费）+ 顺序专用的 `consumingMsgOrderlyTreeMap`（处理中，失败 rollback 回头部）。流量阀：1000 条 / 100MB / offset 跨度 2000——超了暂停拉，等消费推进（**背压**：下游吃不动时让上游慢下来）。

## §5 失败三分支

| | 并发（默认） | 顺序 | 广播 |
|---|---|---|---|
| 失败 | `RECONSUME_LATER` → sendMessageBack | rollback + 挂起后重吃（无限，默认 MAX_VALUE；挂起时长 D5 两处口径 5s/1s，用时核源码） | log + **丢弃** |
| 重试延时 | level3=10s 起跳递增（3→10s…18→2h），最多 16 次→DLQ | 1s 挂起（可覆写），delay 递增 | 无 |
| 线程 | 共享 20 池 | 同队列单线程串行 | 共享 20 池 |

三句：批消费（>1）整批一起算——**半对半错也全重发，幂等按条做**；顺序毒丸（永远失败的消息）堵死队列——生产必须超时兜底+人工干预（挂起参数默认 1s 可覆写）；广播只配无副作用（缓存刷新/配置），要写 DB 用集群+去重。

## §6 offset

集群：内存表 → 成功即更 → 每 5s 上报 Broker。广播：本地文件。**新 Group 的起点策略只生效一次**——之后全看已存位点，重置靠运维命令。

## §7 Pop 一句（5.x）

无队列绑定，`popAsync` + 60s 不可见——Serverless/弹性场景用。xhs 不用，08 一句。

## §8 NameServer：敢不要强一致

| 维度 | ZK/Nacos（CP） | NameServer |
|---|---|---|
| 一致 | 强一致（选主+过半） | 最终一致，无协议 |
| 节点 | 互相同步 | **互不通信**，各收全量上报 |
| 状态 | 有状态 | **无状态**，重启即空，30s 重报填满 |

敢的三兜底：30s 全量重报自愈 / 客户端 30s 重拉+本地缓存 / 路由错了被发送重试掩盖（02 §5）。CP 的秒级一致抵不过选主窗口期"全 MQ 不可路由"的代价。

六张表（`RouteInfoManager`，读写锁）：topic→Broker 队列 / brokerName→主从地址 / cluster→Broker 集 / **brokerLive（唯一带时间的生死表）** / filter（遗留）/ 5.x 映射表。

注册即心跳（启动 1 次 + 每 30s 全量重报，无独立心跳）；剔除双路径：优雅注销秒级 / 故障 120s+5s 扫描。客户端查路由：30s 刷新本地，NS 短暂故障不影响。

## §9 边界（这里一句话翻篇）

- 6 种分配算法细节、TreeMap 全操作 → `lab D5 §二/§三`。
- 路由查询报文、Controller epoch 字段 → `lab D2 §五` / 08。
- 广播/顺序/Pop 实现 → 05/07/08。

## §10 Self-Test（4 题）

- Q1：长轮询 15s 挂起是谁等谁？分发后谁唤醒？
- Q2：Rebalance 谁触发、谁计算？频繁为什么是事故？
- Q3：三分支失败各去哪？顺序毒丸怎么办？
- Q4：NameServer 为什么敢 AP？120s 窗口谁兜底？

## §11 衔接

- 本篇回答"怎么消费 + 路由谁维护"；下篇 05 回答"顺序怎么保证"（锁模型展开 + 缺陷）。
