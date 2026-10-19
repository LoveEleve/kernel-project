# P02-对比 D-02 — Kafka vs RocketMQ 消息可靠性对比

> **定位**：P02 对比系列第 2 篇（ISR vs 主从/DLedger）。
> **源码基线**：Kafka **4.0.0** / RocketMQ **5.1.4**
> **读法**：先看"一条消息的可靠性链路"例子 → 发送侧/落盘/复制/切主逐层对比 → 配置组合清单

---

## 一、一句话结论

| | Kafka | RocketMQ |
|---|---|---|
| **可靠性主轴** | **副本复制**（ISR + HW）——单机不 fsync，靠"多副本确认" | **刷盘 × 主从复制**（4 组合）+ DLedger（Raft 多数派）可选 |
| **确认语义** | acks=all 等 ISR（min.insync.replicas 兜底） | SYNC_MASTER 等从节点复制 / SYNC_FLUSH 等本地刷盘 |
| **一句话** | "不丢"= 多条副本都收到了 | "不丢"= 本地盘刷了 +（可选）从节点也收了 |

---

## 二、例子：一条消息的"不丢"链路

**Kafka（acks=all, min.insync.replicas=2, unclean=false）**：
```
Producer → Leader 写入本地 → Follower 复制 → ISR 最小 LEO 推进 HW → 响应 ack
  → 单副本故障：不丢（ISR 还有 2）
  → 唯一 ISR 副本故障且无干净副本：拒绝写（不丢但不可用）
```

**RocketMQ（SYNC_FLUSH + SYNC_MASTER）**：
```
Producer → Master 写入 mmap → SYNC_FLUSH：等刷盘完成
                            → SYNC_MASTER：等 Slave 复制确认（HAService）
  → 响应 ack；Master 故障 → 4.x 手工切换 Slave（5.x DLedger 自动）
```

**核心差异**：Kafka 的"确认"是**副本集合确认**；RocketMQ 的"确认"是**刷盘 + 主从复制**两个正交维度。

---

## 三、发送侧可靠性对比

| 维度 | Kafka | RocketMQ |
|---|---|---|
| 同步/异步 | 天然异步（Future）+ 回调；close 时 flush | 同步/异步/单向（CommunicationMode） |
| 重试 | retries=MAX + delivery.timeout=120s 总预算 | 同步发送失败重试（默认 2 次，换 Broker 重投）+ 故障规避（`sendLatencyFaultEnable` 开启后按延迟规避；**默认关闭**） |
| 去重 | 幂等生产者（PID+序列号，默认开） | 无发送侧去重——靠消费幂等兜底 |
| 失败语义 | 回调异常（重试耗尽） | 同步抛异常 / 异步回调 |

**结论**：Kafka 发送侧有幂等（默认）；RocketMQ 发送侧重试+换 Broker，去重责任在消费端。

---

## 四、Broker 落盘对比（刷盘）

| | Kafka | RocketMQ |
|---|---|---|
| 默认刷盘 | **不主动 fsync**（OS page cache，`log.flush.*` 默认 Long.MAX） | `flushDiskType=ASYNC_FLUSH`（后台刷，500ms 级） |
| 可选项 | 无（不做单机持久化保证） | SYNC_FLUSH（等刷盘，`syncFlushTimeout=5000`） |
| 可靠性来源 | **多副本**（单机崩溃不丢，因为别处有） | **单机刷盘 + 主从复制**（两个维度都配才稳） |

**陷阱**：Kafka 不能用"单副本 + fsync"思路保证（没有这个开关）；RocketMQ 不能只配 SYNC_FLUSH 不配 SYNC_MASTER（切主仍丢未复制部分）。

---

## 五、复制对比（ISR vs 主从 vs Raft）

### 5.1 三种复制模型

| | Kafka ISR | RocketMQ 主从（HAService） | RocketMQ DLedger |
|---|---|---|---|
| 模型 | 动态副本集合 + HW | 异步/同步复制到 Slave（主从） | **Raft 多数派**（commit index） |
| 确认 | ISR 最小 LEO（HW 推进） | SYNC_MASTER 等 Slave 复制位点 | 多数派复制完成才 commit |
| 集合 | **动态**（落后 30s 踢出，追上加回） | 固定（1 主 N 从） | 固定多数派（3/5 节点） |
| 切主 | ISR 内选新 leader（unclean=false） | 4.x 手工 / 5.x Controller | Raft 自动选主 |
| 可用性取舍 | 少数副本可写（min.insync 兜底） | 主不可写则从不可写（除非切主） | 少数派不可用则拒写（与 Raft 一致） |

### 5.2 ISR vs Raft 的本质差异（面试高频）

```
ISR：确认 = "跟得上的副本"（集合可缩到 1）→ 可用性优先，min.insync.replicas 手动兜底
Raft：确认 = "多数派"（固定 N/2+1）→ 一致性优先，自动多数派容错
Kafka 选择 ISR：为吞吐（每条消息单独确认粒度、不要求多数派同步）
RocketMQ 默认主从（非 Raft）：为简单/延迟；要自动切主才上 DLedger（Raft）
```

### 5.3 HW vs 消费进度（不同概念）

| | Kafka HW | RocketMQ ConsumeQueue offset |
|---|---|---|
| 含义 | 数据**可见性/一致性边界**（ISR 最小 LEO） | 消费**进度**（队列内序号） |
| 存哪 | 每副本内存 + 随副本复制 | Broker 端（ConsumerOffsetManager） |
| 作用 | 消费者只能读到 HW 之前 | 记录消费到哪，与数据可见性无关 |

---

## 六、故障切换对比

| 场景 | Kafka | RocketMQ |
|---|---|---|
| 副本故障 | ISR 收缩，继续写（min.insync 约束） | 主故障：4.x 手工切从；5.x Controller/DLedger 自动 |
| 脏数据风险 | unclean=false 禁落后副本当选 | 主从切换需保证从数据不落后（DLedger commit 语义） |
| 运维动作 | 自动（KRaft Controller 选主） | 4.x 需人工（或用 5.x 自动） |

---

## 七、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **Kafka 不做单机 fsync** | 每条消息 fsync | 单机持久化=吞吐杀手；用副本兜底（故障域从"磁盘"换成"机器"） |
| **ISR 而非固定多数派** | 固定 N/2+1 | 动态集合容忍慢节点（踢出继续写），用 min.insync 手动选"可靠性档位" |
| **RocketMQ 刷盘与复制正交** | 只做一种 | 业务按 RPO 组合（4 档），成本/延迟/可靠性显式取舍 |
| **RocketMQ 默认主从（非 Raft）** | 默认 DLedger | 主从延迟低、实现简单；自动切主是"可选增强"（DLedger/Controller） |
| **消费位点存 Broker（RMQ）** | 客户端管理 | 重平衡/换机不丢进度（Kafka 存 `__consumer_offsets` 也是 Broker 侧，但语义不同） |

---

## 八、生产配置组合清单

### 8.1 Kafka（不丢四件套）

```
发送：acks=all + enable.idempotence=true（默认）
Broker：min.insync.replicas=2 + unclean.leader.election.enable=false + RF≥3
消费：先处理后提交（enable.auto.commit=false）+ 业务幂等
```

### 8.2 RocketMQ（4 组合矩阵）

| flush × role | 单机宕机 | 切主 | 性能 | 场景 |
|---|---|---|---|---|
| ASYNC_FLUSH + ASYNC_MASTER | 可能丢 | 可能丢 | 最快 | 日志/可丢 |
| SYNC_FLUSH + ASYNC_MASTER | 不丢 | 可能丢 | 中 | 单机可靠 |
| ASYNC_FLUSH + SYNC_MASTER | 可能丢（OS 崩溃） | 不丢 | 中 | 复制可靠 |
| **SYNC_FLUSH + SYNC_MASTER** | 不丢 | 不丢 | 最慢 | 资金/订单 |
| DLedger（3 节点） | 不丢 | **自动切** | 较慢 | 要免人工运维 |

---

## 九、面试 Q&A（含面试官意图）

**Q1："Kafka 怎么保证不丢？"**（意图：端到端）
三段式：Producer（acks=all+重试+幂等）→ Broker（ISR≥2+min.insync.replicas=2+unclean=false）→ Consumer（先处理后提交+幂等）。

**Q2："RocketMQ 怎么保证不丢？"**（意图：刷盘×复制）
SYNC_FLUSH（本地刷盘）+ SYNC_MASTER（复制确认）+ 消费幂等；要自动切主上 DLedger（Raft）。

**Q3："ISR 和 Raft 的区别？"**（意图：共识 tradeoff）
ISR=动态"跟得上"集合（可用性优先，min.insync 兜底）；Raft=固定多数派（一致性优先）。Kafka 选 ISR 为吞吐，RMQ 默认主从为简单、可选 DLedger。

**Q4："acks=all 就够了吗？"**（意图：组合细节）
不够——还要 min.insync.replicas≥2（防 ISR 缩到 1）+ unclean=false（防落后副本当选）。

**Q5："RocketMQ 从节点能读吗？"**（意图：细节）
默认 `slaveReadEnable=false`（不读）；主从复制位点/延迟是监控重点。

---

## 十、验证锚点

**Kafka 4.0.0**
| 声明 | 位置 |
|---|---|
| ISR / updateFollowerFetchState / HW 推进 | `core/.../cluster/Partition.scala:198/904/1153` |
| acks=all 等 ISR | `core/.../cluster/Partition.scala:1090`（checkEnoughReplicasReachOffset） |
| HW 位点 | `core/.../log/UnifiedLog.scala:258/269` |
| min.insync.replicas 默认 1 | `server-common/.../ServerLogConfigs.java:137` |
| replica.lag.time.max.ms=30s | `server/.../config/ReplicationConfigs.java:47` |
| flush 默认不主动 fsync | `server-common/.../ServerLogConfigs.java:83/91` |

**RocketMQ 5.1.4**
| 声明 | 位置 |
|---|---|
| brokerRole 默认 ASYNC_MASTER | `store/.../config/MessageStoreConfig.java:211` |
| flushDiskType 默认 ASYNC_FLUSH / syncFlushTimeout=5s | `store/.../config/MessageStoreConfig.java:213/215` |
| 主从复制入口 | `store/.../store/ha/HAService.java:124`（putRequest）；`store/.../store/ha/HAConnection.java` |
| DLedgerCommitLog（Raft） | `store/.../store/dledger/DLedgerCommitLog.java:61/418` |
| 崩溃恢复（abort 判定） | `store/.../store/DefaultMessageStore.java:1855`（recover） |
| 从节点默认不读 | `common/.../BrokerConfig.java:139`（slaveReadEnable=false） |
| 发送失败重试默认 2 次 | `client/.../producer/DefaultMQProducer.java:115` |
| 异步刷盘间隔 500ms | `store/.../config/MessageStoreConfig.java:123`（flushIntervalCommitLog） |
