# 分布式理论快读（面试核心版）

> 定位：L2-1 分布式理论（对齐权威大纲 `分布式/outlines/01-分布式理论` 12 篇）的**面试速读版**——6 篇收口。
> 方法：**例子先行**（它干了什么 → 为什么 → 怎么做/坑 → 值不值得）+ 对照已学系统（Nacos/Redis/Dubbo/Feign/xhs-project）。
> 说明：书线在写完整正文（`docs/plan/book` D-09），本快读版不重复其深度，只保面试可用。

| # | 文档 | 核心内容 | 对照已学 |
|---|---|---|---|
| 01 | [CAP 与一致性模型](01-cap-consistency-model.md) | CAP（P 必选下的 C/A 取舍）/BASE/一致性谱系 | Nacos AP/CP 双模式、Redis 主从 |
| 02 | [共识与 Raft](02-consensus-raft.md) | quorum/选举/日志复制/安全约束/脑裂 | Nacos JRaft（D-10） |
| 03 | [2PC、3PC 与 TCC](03-distributed-transaction-2pc-tcc.md) | 两阶段提交/TCC 三坑（空回滚/悬挂/幂等） | **Seata 前置**、xhs-project 预扣 |
| 04 | [Saga、可靠消息与最大努力通知](04-saga-reliable-message.md) | 补偿/事务消息/本地消息表/幂等/对账 | xhs-project Outbox/事务消息/8 对账 Job |
| 05 | [故障检测与选主](05-failure-detection-leader-election.md) | 心跳/怀疑状态机/sdown-odown/防双主三件套 | Nacos 成员管理（A-07）、Redis Sentinel |
| 06 | [ZooKeeper 与 ZAB](06-zookeeper-zab.md) | znode/会话/watch/羊群效应/ZAB/CP 代价 | 与 Nacos/etcd 对比 |

## 大纲覆盖对照（12 篇 → 6 篇快读）
- ✅ 覆盖：01 CAP / 02 故障模型（05）/ 04-05 Paxos-Raft（02）/ 06 ZAB（06）/ 08 2PC-TCC（03）/ 09 Saga（04）/ 11 选主广播（05）/ 12 案例 ZK（06）
- 📦 归属其他单元：10 事务实战（XA/Seata 为什么没生效）→ **Seata 单元**
- 🟢 翻篇（低频，需要时回权威大纲）：03 逻辑时钟 / 07 Paxos 变种 / EPaxos 细节 / GFS-Spanner 案例细节

## 后续
- ✅ **Seata**（L1-4 分布式事务）：已迁入 `seata-lab` 并完成审校（AT 端到端验证 + TCC/SAGA/XA 对照）——见 `../../seata-lab/HANDOVER.md`。
- （可选）`dist-consensus-lab`：脑裂注入实验——需要时基于 Nacos 3 节点集群做。
