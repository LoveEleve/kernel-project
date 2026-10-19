---
title: "07-横切原理：一致性 / 幂等 / 原子性 / 容错 / 背压 / 容量 / 分区 / 可观测 / 发布迁移"
series: "VPC 5.0（Gen3）架构系列"
previous: "06-hard-problems.md"
next: "08-interview.md"
source_refs:
  - "vpc/test-good/05-cross-cutting/01-distributed-systems（13 篇）+ 02-control-plane（11 篇）+ 03-network（6 篇）"
  - "深读 15 篇：consistency / ordering-idempotency / fault-tolerance / backpressure-flowcontrol / capacity-model / partition-splitbrain / observability / deployment-safety / migration-strategy / concurrency-atomicity / state-machine-design / event-driven-architecture / lifecycle / testing-correctness / capacity-planning"
  - "其余 16 篇：章节头一览（§11 表）"
---

# 07-横切原理：05-cross-cutting 31 篇精华

> 前六章按"组件"切；本篇按"横切追问"切：一致性 / 顺序幂等 / 原子性状态机 / 容错 / 背压容量 / 分区 / 可观测 / 发布迁移 / 测试。
> 口径提醒：素材同时描述 **master（生产）** 与 **bugfix/disorder 分支（修复设计）** 两种状态——§4 标双态，其余按"目标态"叙述。标注：【实证】/【推导】/【开放】/【外部】。

## §0 它干了什么（例子先行）

**一条路由在 5 层存储中的人生**（T0 = 用户点击创建）：

```
T0+10ms   MySQL INSERT 成功（Source of Truth）
T0+15ms   Kafka binlog 产生（offset=唯一版本号）
T0+1-5s   Fusion 消费 → LevelDB.Put
T0+1-6s   快照生成 → t_generic_snapshot（version+1）
T0+1-8s   Broker Watch → BTree 加载
T0+1-9s   Agent 收到 → 本地 LevelDB.Put → DPDK add_route（实际生效）
```

同一时刻查 5 层会得到**5 个不同版本**——这不是 bug，是 AP 系统的必然：**目标链 p50 ~3s / p99 ~5s，不一致窗口的终点是"对账"（5min 增量 / 24h 全量）**。

## §1 掌握卡（7 条）

1. **AP 是 pragmatic**：10 万+ Agent 不可能全在线，CP 意味着"一个 Agent 离线=全局停摆"。
2. **对账是观测者，不是执行者**：只检 Missing/Redundant/Mismatch 并报告，修复靠正常路径重放。
3. **Producer 不设 partition key**：乱序靠"快照全量覆盖"纠正，不靠 Kafka 分区内有序。
4. **幂等 ≠ 语义正确**：Put(nil) 幂等但语义错（key 存在=配置有效）；原子性 master（Pipeline+3 Lua）vs 分支（8 Lua+HashTag）双态。
5. **限频 10/s 是刻意的最窄瓶颈**：保护用户操作延迟 <1s，而不是处理不过来。
6. **发布窗口 72h = 2 个对账周期**：系统性 bug 要 24h+ 才能被对账抓出；canary 只能抓代码级 bug，抓不了规模级 bug。
7. **告警疲劳比没告警更危险**：Redundant 阈值"告多就调高"是掩耳盗铃——要用趋势告警。

## §2 一致性模型（consistency）

**5 层存储的版本语义**【实证】：MySQL=事务+隐式最新版；Kafka=offset（append-only 可重放）；Fusion LevelDB=覆盖式（临时工作区）；t_generic_snapshot=version 递增（Broker 判断"要不要拉新"）；Broker BTree=无版本（崩了从快照重建）；Agent LevelDB=本地持久化；DPDK=actual state。

**为什么 AP**：若 CP——DB 写入要等 10 万+ Agent ACK——延迟不可行 + 任一 Agent 离线全 Region 停摆。AP 后延迟 ≈20ms，代价是多级不一致。

**不一致窗口量化**【实证】：

| 场景 | 窗口 |
|---|---|
| 正常 binlog 路径 | p50 ~3s / p99 ~5s / p99.9 ~10s |
| binlog 故障（走对账增量） | ≤5min（增量周期） |
| 对账全量兜底 | ≤24h |
| 系统性 bug（如 DeleteConfig） | 持续到对账发现（最晚 ~24h）= **对账周期即最晚检测时间** |

**对账为什么只检测不修复**（三个误修场景）【实证】：① Missing 误修→与正常路径重复下发（浪费）；② Redundant 误删→若 DB 读取恰逢写入窗口→**删掉正在创建的配置→流量丢失**；③ 对账结果本身可能过期（读左侧/右侧之间数据在变）→ 修复与正常路径竞争。源码口径：`reconcile.go` 只 `LoadLeft/LoadRight/Reconcile` + `exporter.Export`，无修复逻辑。**"观测者-执行者分离"= 检测归对账、执行归 binlog 路径。**

**version 不用 timestamp**【实证】：时钟不同步（跨 SET 不可比）/ 同毫秒并发 / NTP 回拨——version 纯逻辑递增、全局单调。

## §3 顺序与幂等（ordering-idempotency）

**Producer 不设 key**（源码 `producer.go:213-217` `kgo.Record` 无 Key 字段→默认 round-robin）→ 同 VPC binlog 可能跨 partition 乱序【实证】。**为什么没出事**：binlog 路径"覆盖写入不在乎顺序"，快照路径（DB 全量 + 增量合并）定期矫正——**乱序的纠正者是快照，不是 Kafka**。（Gen2 侧还有迟到 binlog 重编号 FixBinlogVersion 机制——见 10 §4。）

**投递保证链**【实证】：`acks=all`（ISR 全确认，局域网 <10ms——binlog 丢了=用户 VM 路由不生效）；**手动 commit**（processBinlog 完才提交——auto-commit 原子性不匹配会产生重复）；offset reset 双刃（earliest 全量重放浪费/ latest 丢窗口——都是幂等+对账兜底，配置在 deployment YAML）。

**Put(nil) 复活的三层解读**：幂等（两次 Put nil 结果同）但**语义错**（LevelDB"key 存在" ≠ 对账"配置有效"）；修复=物理 `Delete`。「幂等保证不了语义正确——两个层面的定义不同」【实证】。

**消息格式**：`KafkaMessage{QueueClass, oneof{Binlog|Command|Notification}}`——oneof 编译期保证互斥；binlog item 带 `old_data+new_data`（Event Sourcing：可审计/可对比/理论上可反向重放，但未实现回滚）。

## §4 原子性与状态机（concurrency-atomicity + state-machine-design）

**双态对照**【实证，重要】：

| | master（生产现状） | bugfix/disorder 分支（修复设计） |
|---|---|---|
| 任务操作 | **Pipeline 为主**（Publish/Claim/Requeue） | 8 Lua 原子化 |
| Lua 数量 | 3 个（优先级取出 / 锁释放 / 锁续约） | 8 个任务 Lua + 锁 2 个 |
| Hash Tag | **无**（key 按 queueName 分布不同 slot） | `{queueName}` 同 slot |
| Requeue | 三步 Pipeline 非原子（crash 窗口丢任务，概率低） | `requeueTask` Lua 原子 |
| CompleteTask 校验 | 仅 taskID | ownership 三重校验（Status+WorkerID+Lease） |

**细节**：从不用 TxPipeline（Cluster 跨 slot EXECABORT）；锁续约是"必须 Lua"的原型（GET+比较+PEXPIRE 三步非原子=给别人的锁续约→脑裂）。**4 个状态机**：Task 5 态（pending→processing→completed/failed/**timeout[Recovery 强制]**）、Worker 4 态、Leader 3 态（TTL 10s/续约 3.3s/探测 30ms）、Session 4 阶段（Handshake→Watch→Poll→Ack）。非法转换靠"Status 检查"防（非 CAS）。

## §5 容错与降级（fault-tolerance + graceful-degradation）

**四类故障 × 恢复**【实证】：

| 类别 | 例 | 恢复方式 | 时间 |
|---|---|---|---|
| 进程级 | Worker OOM / Agent crash | watchdog/K8s 重启 | <30s |
| 组件级 | Leader 宕 / Broker 宕 | 选举 / 多副本 | <10s |
| 基础设施 | MySQL / Kafka | 托管 failover | <30s |
| 系统性 bug | DeleteConfig | 对账发现+人工 | 数小时~天 |

**依赖分级**：P0（MySQL/Kafka——挂了配置面停，不能降级）；P1（Redis/Leader——**降级不降核心**：Redis 挂任务队列停但 binlog 路径照常）；P2（Worker/Broker/Agent——<30s 自愈）。**三段式时间线**：T20 OOM → T45 租约过期 → T46 接管 = **26s < 30s**。**降级精确行为**：Kafka 挂=P0 直接报错（不做不可靠的直写降级）；Redis 挂=快照/对账暂停、已有变更照常下发。

## §6 背压与容量（backpressure + capacity-model + capacity-planning）

**三种背压覆盖全链路**【实证】：① Producer buffered channel（5000，满则 SendMsg 阻塞=调用方限流）+ 连错 10 次切 Producer 实例；② Worker Semaphore（16×10=160 并发，满则排队）；③ tRPC Stream ACK 窗口（Broker 等 ACK 才推下一条）+ per-agent 10 msg/s。**无背压处**：DB→Fusion（自然降速）、API→Fusion（Kafka 缓冲=隐式背压）。

**限频 10/s 的深意**：Worker 能力 256/s vs Publish 10/s——若去掉限频，全量对账 10 万任务瞬间入队→用户操作排队 6.5min；限频保证"全量对账不淹没用户操作"。

**容量反推**（config 默认值）：快照 588 VPC/s（全量 57min）；对账 166 VPC/s（全量 3.3h + publish ~6h）；4 SET×3 副本=12 实例、每 SET ~50 万 VPC。**三个增长拐点**：500 万 VPC（对账 >24h 跟不上日周期）/ 1000 万（Kafka consumer 瓶颈）/ 2000 万（t_ip 单分区热点）。**扩容 ROI**：Worker 扩容最快（HPA 5min）；**对账优化最优先**（0 增量成本、改代码数周）。

## §7 分区与选主（partition-splitbrain）

**双主三种场景**【实证】：① slot 级分区——两 Leader 各管各的 slot（任务不重叠，恢复时 SET NX 值可能冲突）；② Redis 全分裂——两侧各自完整集群、各自选主、同任务可能双产双领；③ 恢复合并——wrong side 数据被覆盖→Recovery 扫过期→requeue 重做（幂等兜底："AP 系统的分区后果不是'出错'，是'多做一遍'"）。

**Fencing Token 未做=pragmatic**（不是忘了）：TTL 10s 够短 + 快照/对账幂等 + 分支有 workerID 校验 + 对账检测。**若加**：`epoch` INCR + 所有写在 Lua 内校验 epoch（~100 行，每次写 +1 次 Redis GET）。对照：etcd Raft（term=fencing 天然、少数派停=CP）vs SET NX（无 fencing、双主可能=AP）。

## §8 可观测（observability）

**5 分钟排障 runbook**（6 步逐层）：VM 存在？→ DB 有路由？→ Kafka 有 binlog？→ Fusion LevelDB？→ Broker 推了？→ Agent/DPDK 生效？——每步有工具（configCollector/queryDBRawSQL/clsSearchLog…）、预期输出、超时标准；6 步全过=控制面清白，转数据面（GRE/ARP/ACL/Underlay）。

**10 个核心指标 × 4 维**：吞吐（publish/claim rate）/ 健康（worker alive、lease expire rate）/ 积压（Kafka lag、Redis queue length）/ 正确性（reconcile Missing/Redundant 计数）。**Missing=P0**（DB 有 DPDK 没有=用户不通），但需等 1-2 个对账周期排除正常延迟。

**告警疲劳教训**【实证】：DeleteConfig 产生大量 Redundant→阈值被"告多就调高"→bug 存活更久。改进：**趋势告警**（500→5000 的增长）+ 每告警有 owner。金句："端到端监控是'你病了'，对账是'你哪个器官病了'。"

## §9 发布与迁移安全（deployment-safety + migration-strategy）

**灰度**：canary 1-2 台（24h）→ 10%（48h）→ 全量（72h）；**72h 回滚窗口 = 2 个全量对账周期**。观察指标：publish/claim rate、retry rate、lease expire rate、对账 diff、LevelDB 写延迟。**自动回滚**：publish=0 或新增 Missing>10000（P0 级）。**canary 盲区**：DeleteConfig 金丝雀抓不住（删除频率低）——"只能抓代码级 bug，抓不了规模级 bug"。

**Gen2→Gen3 迁移四阶段**：双写→双读→切流→清旧；**per-agent 5%×20 天**逐步切（不是一键）；切流门槛=双读一致>99.99% + Broker 可用性>99.9% 一个月。**最危险一步**="新 SET 拉全量配置"（DB 部分失败丢配置 / offset 算错漏 binlog / 双写窗口冲突——缓解：快照 hash 校验 + 精确 offset + 先建新后拆旧硬约束）。**兼容顺序**：生产者在先（API→Fusion→Broker→Agent），protobuf optional 兜底。

## §10 测试与验证（testing-correctness）

**DeleteConfig 三层防线全败**【实证】：单测 PASS（测了"Put 后 Get=nil"——LevelDB 正确行为，但没测对账怎么解读）；集成测试**不存在**（delete→reconcile→diff=0 + key 消失）；E2E 没测跨模块语义。**能拦住的测试**：`TestDeleteRoute_Reconcile_Clean`（delete→reconcile→断言 Redundant=0 且 key 不存在）。**TLA+**：能穷举验证"Put(nil) vs Delete"的语义等价（不变量：不该有 key 时 reconcile 不报 Redundant）。**kill-9**：目前灰度手动验证，应进 CI（Docker Compose + 随机 kill）。现状最强防线：`-race` + 50 goroutine 并发测试。

## §11 其余篇一览（16 篇）

| 篇 | 一句话精华 | 归属 |
|---|---|---|
| cold-start-recovery | 冷启动三路径（首部署 / Redis 全重启 / LevelDB 损坏）+ ConfigState 状态机判断 | 02 §3-§5 |
| graceful-degradation | 每依赖不可用的精确行为表（"降级不降核心"） | §5 |
| reconciliation | 1 亿条配置的仲裁者（三差异/两阶段/报告解耦） | 02 §5 |
| multitenancy | 四层隔离 + 单 VPC 故障扩散 + 配额/熔断现状 | 02 §6（故障扩散） |
| security-design | 6 层纵深 + 攻击面 + "为什么没有 RBAC" | —（本系列未展开） |
| operations-day2 | 日常项 / 加一种配置类型的全流程 / 可调参数 | 01 §6（元数据驱动） |
| testing-quality | 四层塔 / kill-9 / TLA+（与 §10 姊妹） | §10 |
| performance-metrics / caching-strategy / data-sharding | 指标 / 缓存（ResourceObj 缓存即真相）/ 分片 | 已读前批（01/02/05） |
| gateway-architecture | 六网关全景（VPCGW/PVGW/NATGW/CCN/VPN…） | 数据面域 |
| network-virtualization | Overlay/Underlay + GRE/VXLAN + DPDK/tvpc.ko | Gen2 系列 12 章 |
| routing-architecture | RTB/RTABLE/子网路由闭环 | Gen2 系列 12/14 章 |
| service-loadbalancing | t_service/t_route VIP-RS 模型 | 数据面域 |
| network-policy | ACL vs 安全组（有状态/无状态的控制面差异） | 数据面域 |

> 网络组 6 篇属数据面域——本系列不深挖，对照 Gen2 系列 12/14 章；需要时按章引用。

## §12 边界

- **双态纪律**：§4 的 master/branch 双态以 concurrency-atomicity / fault-tolerance 口径为准；其余节按"修复后目标态"叙述，引用时注意分支（02 §9 注记）。
- 容量数字来自 `config/*.go` **默认值**反推（生产可能覆盖）——标【实证-默认值口径】。
- 网络组 6 篇未深读（章节头级）；reconciliation/multi tenancy/security 等以章节头+前批素材为据。
- 本篇为精华重排——每节详版在源篇（05-cross-cutting）与对应正文章节。
