---
title: "10-规模化与治理机制补遗：两级 SET / 排水与状态机 / 顺序重编号 / CA 深挖"
series: "VPC 5.0（Gen3）架构系列"
previous: "09-external-lens.md"
next: "11-capstone.md"
source_refs:
  - "vpc/docs-by-topic/36-vpc-broker.md（排水器/服务状态机/六类元数据 bootstrap/两阶段订阅——文件级）"
  - "vpc/docs-by-topic/40-vpc-ops（VPC 5.0 运维运营系统）.md（两级 SET 治理 D-x/R-x/观察窗口——文件级）"
  - "vpc/docs-by-topic/19-computation-agent.md（CA 设计文档仓库——全读）"
  - "vpc/docs-by-topic/08-VPC 控制器——分布式架构与分布式问题.md（锁失败模式/迟到 binlog 重编号——全读）"
  - "vpc/docs-by-topic/12-VPC 控制器——精确数据格式与转换管道.md（版本编码/6-pack/常量表——全读）"
---

# 10-规模化与治理机制补遗

> 为什么有这篇：00-08 讲了主干链路；本篇补"**百万级/亿级配置快速且准确**"真正靠的治理与规模化机制——此前未读的五大素材源（36/40/19/08/12）。都是"变更自身的规模化"：扩容、灰度、退役、审批、重编号、启动编排。
> 标注：【实证】/【推导】/【开放】；36/40 素材为 repo 文件级描述（标"文件级"）。

## §0 它干了什么（例子先行）

**场景 A：一次新地域上线（规模化=被治理的流程）**【实证，文件级】

```
① 分发层 SET 创建（vpc-ops: CreateDistributeSet，D-1"全量引导"）：
   注册 Kafka + 登记副本（db/redis/fusions）+ 挂可用区引用
② 副本 R-1 上线：建骨架 → 组件登记 → 初始化状态轮询（ctrlSetReplica.state）→ 置 AVAILABLE
③ Broker SET 创建：从资产台账 t_asset 组装成员（Broker 默认 OFFLINE）→ 上线接客
④ 调权重（UpdateBrokerSetWeight）触发"观察窗口"人工审批：
   Approve 才放行灰度 flow；Reject 直接回滚
⑤ 老 SET 退役：Drain 排水（存量会话匀速关闭/资源迁移）→ Destroy
```

**场景 B：一条迟到 binlog 如何不丢**【实证，08 §4.2】

```
Kafka producer 不设 partition key（刻意决策："负载均衡 > 顺序性"——防热点 VPC 打爆单分区）
→ 同 VPC binlog 可能乱序到达 → 若丢弃则丢数据
→ FixBinlogVersion 重新编号：迟到 binlog 获得 max(对象当前版本, 全局当前版本)+1 的新版本号
→ 以"新版本"正常回放 → at-least-once 下不丢
```

## §1 掌握卡（7 条）

1. **两级 SET 是规模化主轴**：**分发层 SET**（内容规模：Kafka + 副本 db/redis/fusions）与 **Broker SET**（连接规模：broker 实例→agent）——扩容/灰度/退役都在这两级上做。
2. **治理流程化**：SET/副本全部走 vpc-ops 的 D-x（分发层）/R-x（副本）流程 + **观察窗口人工审批门控**（t_observe_window）。
3. **Broker 有完整服务状态机**：Ready / Accepting / Serving / **Draining**（枚举，host/report 双 role）+ Drainer 匀速排水。
4. **顺序性是"另一半真相"**：无 partition key 是决策不是疏忽；**FixBinlogVersion 重编号** + 版本协商 6-pack 才是"乱序不丢"的机制（07 §3 只写了快照纠正）。
5. **Broker 启动 = 元数据编排**：六类元数据 bootstrap → generation apply → SET reconcile → Watch 对齐 → 才 READY；期间 **tRPC 不 listen、心跳不发**；ctrlSetReplica **fail-closed**。
6. **CA 是完整工程设计**（6600 行设计文档）：四级存储 / 四阶段订阅 / 双版本号 / vpcMu 持锁一致性 / 热迁移重算——仍是设计阶段（无代码）。
7. **锁的正确性观（四件套失败模式）**：inode 删除双主 / ZK LOST 自杀雪崩 / 无 fencing / 无续约——再次印证"**锁只负责效率，正确性靠幂等+对账**"。

## §2 两级 SET 治理模型（vpc-ops 对象模型）【实证，文件级】

**两级**: 分发层 SET（**Distribute Set**）承载"内容侧"（Kafka + 副本）；Broker SET 承载"连接侧"（broker 实例）。规模扩展两个正交维度：内容规模 vs 连接规模。

| 对象 | 生命周期流程（vpc-ops Action/Flow） | 机制要点 |
|---|---|---|
| 分发层 SET | Create（**D-1 全量引导**：注册 Kafka+副本+可用区引用）/ Update / **Drain 排水** / Destroy（批删 Kafka 与副本）/ 引用增删（D-5 变体） | **权重灰度**（UpdateDistributeSetWeight）；可用区引用（推测：哪些 AZ 消费此 SET） |
| Broker SET | Create（**pre-query t_asset 组装成员**，Broker 默认 OFFLINE）/ Update / **Drain（含资源迁移）** / Destroy | **权重灰度 + 观察窗口审批门控**（UpdateBrokerSetWeight） |
| 分发层副本 Replica | **R-1 上线**（骨架→组件登记→初始化轮询→AVAILABLE）/ **R-2 降级与恢复**（AVAILABLE→UNAVAILABLE→重新拉起）/ **R-3 销毁** | 组件字段 db/redis/**fusions**；`replica_register_fusion` 登记 Fusion 绑定；`DescribeReplicaInitStatus` 查 ctrlSetReplica 状态 |
| 观察窗口（审批门控） | List / **Approve（放行等待中的灰度 flow）/ Reject（触发 flow 回滚）** | `t_observe_window`：幂等键 observe_window_id + 归属键 flow_root_id |
| 资产台账 | 被 CreateBrokerSet 引用组装成员 | `t_asset` |

**设计含义**：大系统的"变更自身"也要被规模化管理——**SET=扩容单位、flow=风险单位、观察窗口=人的闸门**（vpc-ops 对外向 **Defensor 等上游变更平台**暴露业务语义 API——40 口径）。这回答了"架构没这么简单"的一半：规模化不是单靠组件能力，还靠治理模型。

## §3 Broker 侧规模机制【实证，文件级】

- **服务状态机（枚举）**：Ready / Accepting / Serving / **Draining**（退出态）——host/report 双 role；TRPC 侧做 role-aware serving 校验；启动 **WaitReady 后才 listen**。
- **Drainer 优雅排水**：DRAINING 后延迟 StartDelay → 在 DrainDuration 内**匀速关闭存量会话**（降低对 agent 冲击）→ 停止各组件。与 vpc-ops 的 `DrainBrokerSet/DrainDistributeSet` 配套——**先排水后退役**。
- **启动编排**（MetadataManager 七阶段状态机）：六类元数据（vpcBroker / coreCtrlSet / resourceCtrlSet / resourceTopic / configTopic / ctrlSetReplica）经 `GetAllConfigs` 分页拉取 → generation apply → SET reconcile → Watch 对齐 → READY；**期间 tRPC 不 listen、心跳不发送**；ctrlSetReplica 提供 **fail-closed** 语义（没对齐好就不接客）。
- **两阶段订阅**：`StartKafkaWatch`（Kafka 订阅 + cached binlog 先行）→ ConfigHandler 注入缓存并注册 pending → `StartFusionWatch`（初始 snapshot，失败切副本重试）——"先有增量缓冲、再补全量"。
- **DistributeSetManager**：generation apply / SET reconcile / **回调抑制**（防元数据抖动引发风暴）。
- 登记：`specs 2319-optimize-stream-backpressure`（tRPC stream 背压优化工作项）；agent 侧 **BypassExecutor 异步首包**（知识库目录级：16 个 Error Sentinel 与 Event 类型汇总）——机制细节待补【开放】。

## §4 顺序性的另一半：迟到 binlog 重编号（FixBinlogVersion）【实证】

- **决策**（08 §9.4，原文）：有 partition key=同 VPC 有序但热点打爆单分区；**无 key=负载均衡优先**——"负载均衡 > 顺序性"，乱序靠机制消解。
- **机制**（08 §4.2，`binlog_handler.go:165-199`）：

```
迟到 binlog（version < currentVer）：
  fixVer = max(对象 resVer, 全局 currVer) + InspectFixStepVer(1)
  → FixBinlogVersion 重新编号 → 以 fixVer 回放（不丢）
版本编码（12 §一）：真实版本 ×10000（ShiftSteps=4）；修复 binlog Group="-1" 标记区分
```

- **版本协商（agent 侧，Gen2 对照）**（08 §4.3 / 12 §八）：Agent 心跳带 peerVersion → broker 6 种裁决：
  `-1→Uninitialized(全量) / ==local→UpToDate / diff>5000→Outdated(全量) / 不在[min,max]→BinlogIncomplete(全量) / 否则 BinlogUpdate`；
  增量区间 `GetRange(peerVer+1, localVer)`——**不含 peerVer，边界防重**。
- **与 Gen3 快照纠正的关系**：快照全量+增量合并是"周期兜底"；重编号是"binlog 级不丢"——两者共同构成"乱序下的准确性"。

## §5 CA 深挖（computation-agent 设计文档）【实证，设计阶段】

- **部署与服务发现**：CA 以 `agent_role: "computation"` 接入 framework，**BrokerRole 复用 BROKER_ROLE_HOST**（订阅同一批 HOST Broker）；地址经 `core_ctrl_set(core_type=CA)` + `vpc_ctrl_set(type=CA)`/`host_ctrl_set` 下发（复用现有 Bootstrap，不加新订阅）。
- **四级存储**：`rawStore`（原始依赖）/ `DepStore`（依赖展开中间件）/ `computedStore`（展开结果）/ `versionstore`——全部 LevelDB。
- **两类展开**：rtable_expanded（VmRTable+eniPip/eni/subnet/remoteGw）；vmentry_expanded（VM 邻居）。
- **订阅管理（四阶段）**：hostInstance **逐 IP HostScope 订阅**、instanceEni **Owner 级联订阅**；DepExtension 全局单例直持 DataSyncer。
- **一致性设计**：**双版本号 + source_versions**（FetchSnapshot 响应携带各上游 configType 的原始版本 → host-agent 可无缝切回 broker 增量）+ **vpcMu 持锁**（版本与数据的一致性窗口）。
- **覆盖场景**：初始化 / 变更生效 / **热迁移重算** / 冷启动拉快照 / 动态订阅 / 断线重连 / 并发一致性验证；另含容量规划、HA RTO、灰度放量与回滚清单。
- **状态**：仓库仅 2 文件（README + 6600 行设计文档 v0.1）——**无代码**，所有运行结论标【开放】。

## §6 锁失败模式四件套（08 §三精选）【实证】

| 锁 | 失败模式 | 现状/兜底 |
|---|---|---|
| ZK 临时顺序锁（nfv-proxy 快照构建 `/SNAPSHOT_LOCK/...`） | LOST/SUSPENDED **→ suicide**：ZK 抖动=全量雪崩 | 已验证——迁 DB 锁（容忍 2 次心跳失败 30s） |
| Redis SETNX（Fusion 选举/快照锁） | 分区双 Leader（无 fencing，10s 窗）；**快照锁 AutoRenew=false、TTL=10min**——超时并发构建；主从异步复制丢锁 | TTL + 幂等（"latestSnapshotVersion 已存在→skip"）+ 对账 |
| MySQL 行锁（Outbox 保序） | Gap Lock 死锁（清理时）；Producer 崩溃卡 sendState=1（Mediator 60s 重置）；**40s 锁过期无 fencing** | GET_LOCK 串行化清理；Mediator；对账 |
| 文件锁（vpc_agent fcntl） | **删除锁文件=打破 inode 锁→双主**；m2s 回调为空（锁丢失无感知） | ha.py 仲裁兜底（约 6s 周期） |

> 结论：四种锁覆盖三代，没有任何一种把"正确性"押在锁上——全部靠 **TTL/仲裁/幂等/对账**收尾。

## §7 边界

- §2/§3 为 repo 文件级证据（vpc-ops/36 文件清单描述）——流程细节（D-x/R-x 的具体步骤）待源码；标【实证，文件级】。
- §4 的 FixBinlogVersion/6-pack 主体是 Gen2 agentos/agent-broker 机制（对 Gen3 是"同一物理约束的另一解"）；Gen3 fusion 侧乱序消解仍以快照纠正为主（05-cross-cutting 口径）——两者并存，引用时区分。
- CA 全部为**设计文档口径**（无代码）；BypassExecutor 仅知识库目录级——均登记待补。
- 数据面协作（CT 版本机制 `global_route_version/dfw_version`、安全组变更必须补发 `DFW_VER_ADD` 否则存量连接不换规则）→ 对照 Gen2 系列 12 章，本系列不展开。
