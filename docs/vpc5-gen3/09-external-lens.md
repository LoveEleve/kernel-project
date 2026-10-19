---
title: "09-材料之外：业界坐标、边界评审与沙盘（【外部】）"
series: "VPC 5.0（Gen3）架构系列"
previous: "08-interview.md"
next: "10-scale-mechanics.md"
source_refs:
  - "本篇内容不在材料中——公开知识 + 推导（标注【外部】）；与正文【实证】严格分离"
  - "对照锚点：vpc5-gen3 00-02（已写章）+ Gen2 系列（跨代对照）"
---

# 09-材料之外：业界坐标、边界评审与沙盘（【外部】）

> **本篇全部内容标【外部】**= 材料之外（公开知识 / 我的推导，可证伪）；面试按"我的推导/业界常识"口径讲，不冒充材料事实。
> 目的：把 vpc5.0 放进业界坐标系（认出它）、指出材料未讨论的边界（评审它）、自拟沙盘题（推演它）。

## §0 它干了什么（例子先行）

面试官："Lease+Heartbeat+Recovery 是什么？"
- 材料答案：vpc-fusion 的任务租约机制（30s/10s/1min）。
- **业界答案：这就是 SQS visibility timeout**（2006 年就有的模式）——lease=可见性超时、heartbeat=ChangeMessageVisibility、Recovery=redelivery、ownership=receipt handle 语义。
- 换个名字，设计立刻"可识别"——这就是本篇的价值：**认出（映射）、评审（边界）、推演（沙盘）**。

## §1 业界等价物映射（五组）

1. **任务队列 ↔ SQS visibility timeout / Temporal activity**：租约+心跳+重新投递是分布式任务队列的标准三件套；fusion 的 ownership 三重校验 ≈ SQS receipt handle（防旧消费者确认新投递的消息）。
2. **快照链 ↔ Flink checkpoint/savepoint**：LevelDB=state backend、snapshot=checkpoint、version=watermark/barrier、cloneSnapshot=增量 checkpoint、RebuildSnapshot(skipVersions)=savepoint 回滚。差异：fusion 是 **per-(config,scope) 独立版本链**，比 Flink 全局 checkpoint 粒度细得多。
3. **tRPC Stream ↔ Envoy xDS**：Handshake/Watch/Poll/Ack ≈ 连接+订阅+增量+ACK；LocalVersion ≈ version_info；INVALID_SESSION 重握手 ≈ NACK/重订阅；"增量已被驱逐则全量" ≈ Delta xDS 失败退回 SotW。
4. **LSMT 类比展开**（材料只给了 SSTable/MemTable 两词）：binlog=WAL、Redis 聚合=MemTable、snapshot=SSTable、merge=compaction、cloneSnapshot=未动层不重写、保留 N 版=多版本快照。
5. **可靠性语义 ↔ 消息系统术语**：at-least-once + 幂等 = **effectively-once**；关键区分——ownership 保证的是"**不双完成**"，不是"不双执行"。

## §2 一致性模型命名（材料只描述行为，没命名）

- 系统实际提供：**per-scope 单调版本 + 有界陈旧**（staleness ≤ 快照周期 + poll 窗口）+ effectively-once。
- 与线性一致的差距：无全局时钟、无跨 scope 原子、无跨 scope read-your-writes（scope 内靠版本连续对齐）。
- **TLA+ 不变量候选**（呼应材料提过的 AWS Hyperplane 形式化验证）：① 任务不丢（最终 completed/failed）② 不双完成（ownership）③ 版本连续（三向合并条件）④ 快照版本单调。

## §3 材料未讨论的边界（推导评审，可证伪）

| # | 边界 | 说明 | 更稳的做法 |
|---|---|---|---|
| 1 | **时钟假设** | `leaseExpireAt` 由 worker 本地时钟计算——漂移会让 Recovery 早接管（误抢）或晚接管（延迟） | Redis TIME / Lua 内取服务端时间 |
| 2 | **心跳/执行耦合** | heartbeat 线程死了但任务仍在跑 → finish 被 ownership 拒绝 → 任务重入队**重复执行**；对 cleanconfig 等非幂等任务是隐患 | 执行前检查心跳存活；任务级幂等键 |
| 3 | **at-most-once 不存在** | 防"双完成"不防"双执行"——快照/对账幂等所以无害，清理类要单独设计幂等 | 幂等键 + 完成标记（两阶段） |
| 4 | **init 单 Leader 27.8h** | 单 Leader 限频发布 200 万任务 ≈ 27.8h | 可按 SET 分片并行（每 SET 一 Leader） |
| 5 | **选举 Key 前缀两说** | `leader:election:` vs `fusion:leader:`——根因是代码常量与文档不同步 | 单一常量出处 + 文档生成 |
| 6 | **legacy key 清理纪律** | 双写双读的旧格式 key"24h TTL 后删代码"——容易忘 | 清理 deadline 写进任务系统 |

## §4 容量模型反推（材料只有碎片数字）

- **存储账**：全量表 1 亿条 × ~400B ≈ 40GB；快照 2000 万份（10 配置 × 200 万 VPC）× 3-5KB ≈ **60-100GB**；单次 LevelDB 合并 ~24GB 量级（临时）。
- **速率账**：snapshot 200 万 / 3.4h ≈ **163 VPC/s**；"每秒数十次变更"经攒批（阈值 1000）摊薄到快照重建频率。
- **队列账**：任务数据 24h TTL——峰值队列深度 = 发布速率 × 最长任务时长（被限频参数约束）。

## §5 跨代演进线（材料分代讲，本篇串线）

| 维度 | Gen1 | Gen2 | Gen3 |
|---|---|---|---|
| GC 模型 | ZK 自然过期 + 人工 | 12h 拒写 + 合并清理 | 版本保留（MaxSnapshotCount）+ cleanconfig 六连删 |
| 对账 | 手动脚本 | 四层（含修复策略） | 两层（fusion + agent Reconciler）+ **从"修"转"报"** |
| 协调 | ZK 全量 | ZK 锁 → DB 锁 | Redis 选举/Lease（去 ZK） |

## §6 面试沙盘题（自拟，考察迁移/替换思维）

1. **任务队列换 Temporal？** 租约模型可映射（activity=task/心跳=heartbeat）；但 Temporal 引入重依赖（Cassandra/ES）——fusion 自研是"轻量+可控"的选择。
2. **Redis 换 etcd？** Lease 可换 etcd lease；但任务队列依赖 **ZSet 按过期时间范围扫（ZRANGEBYSCORE）**——etcd 没有对应物，要自己维护时间索引。
3. **Flink 替换快照链？** checkpoint 可对应；但 fusion 的 per-(config,scope) 独立版本 + clone 未变分片，比 Flink 全局 checkpoint 更细——Flink 反而"重"。
4. **怎么彻底防双执行？** 幂等键 + 完成标记（两阶段：执行前写 intent、完成后写 done）；或 exactly-once sink 思路（幂等写 + 去重表）。
5. **27.8h 初始化怎么加速？** 按 SET 并行 + 拉取与写库流水线化；限频本质是保护 API/MySQL，可换成令牌桶做自适应。

## §7 边界

- 本篇全部【外部】——结论可证伪，不与材料口径混用；面试讲时明确"这是我的推导/业界对照"。
- ①"每章外部对照"小节从 03 章起随章写（02 回头补）；③ 审计痕迹在 review-log。
