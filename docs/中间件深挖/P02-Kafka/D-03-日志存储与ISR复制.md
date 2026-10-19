# P02-Kafka D-03 — 日志存储与 ISR 复制

> **定位**：Kafka 深挖系列第 3 篇。三层齐：**原理（心智模型）+ 使用（配置/实践）+ 源码（核心调用链）**。
> **源码版本**：apache/kafka **4.0.0**（存储层部分 Java 重写：`LogSegment/OffsetIndex/LocalLog` 在 `storage` 模块；`UnifiedLog` 仍在 core/Scala）
> **质量标准**：源码阅读清单 / 完整调用链 / 设计哲学（反面假设）/ 生产陷阱 / 面试 Q&A（意图）/ 章节依赖

---

## 一、它干了什么：一个例子

分区 p0，RF=3（Leader=L，Follower=F1/F2），`acks=all`，`min.insync.replicas=2`。生产者发一条消息：

```
时刻1  各副本 LEO=104，HW=104（消费者只能读到 104）
时刻2  L 收到消息：append 到本地日志 → L.LEO=105
时刻3  F1/F2 各自 fetch(offset=104) 拉到这条 → append → F1.LEO=F2.LEO=105
时刻4  L 更新 HW = min(ISR 各副本 LEO) = 105 → 响应生产者 ack
时刻5  消费者现在可以读到 offset=104 这条（HW=105 之前的都可见）
```

若 F2 落后超过 `replica.lag.time.max.ms=30s` → **踢出 ISR**（ISR={L,F1}，仍满足 min.insync=2）；
若 F1 也挂 → ISR={L} < 2 → **acks=all 写失败**（宁可失败也不"只写 leader 就 ack"）。

**一句话**：Kafka 的可靠性 = **"写 leader + ISR 复制确认 + HW 推进"**——HW 是"消费者可见边界"，ISR 是"跟得上的副本集合"。

---

## 二、原理（心智模型，不贴源码）

### 2.1 分段日志（Segment）模型

```
分区目录：00000000000000000000.log / .index / .timeindex
          00000000000000368769.log / .index / .timeindex   ← 每个文件 baseOffset 命名
          00000000000000737538.log ...                      ← 正在写的 = activeSegment
```

- **segment**：日志切块（默认 1GB 或时间滚动）——retention/清理**以文件为单位**
- **.index（OffsetIndex）**：稀疏偏移索引——每写 4096 字节（`index.interval.bytes`）记一条 `<相对 offset, 物理位置>`；查找 = 二分索引 + 顺序扫描
- **.timeindex**：时间戳 → offset（按时间消费/找位点用）

### 2.2 三个位点（面试核心）

| 位点 | 含义 |
|---|---|
| **LEO**（Log End Offset） | 下一条要写入的 offset（每副本各自维护） |
| **HW**（High Watermark） | **ISR 所有副本 LEO 的最小值**——消费者只能读到 HW 之前 |
| **logStartOffset** | 日志起点（retention 删除/清理后前移） |

**为什么需要 HW**：已返回给消费者的数据，切主后必须还在（新 leader 至少同步到 HW）——HW 是"复制完成"的可见边界。

### 2.3 ISR：动态"跟得上"集合

```
Follower 周期 fetch 复制；上报自己的 LEO
  落后 ≤ replica.lag.time.max.ms（30s） → 留在 ISR
  落后 > 30s（或进程死） → 踢出 ISR（收缩）
  重新追上（LEO ≥ HW） → 加回 ISR（扩张）
```

**ISR 与 Raft 多数派的区别**：Kafka 不要求"多数派"，要求"**ISR 集合**"——慢节点可被踢出，写入不被少数慢节点拖死；代价是 ISR 可缩到 1（配合 `min.insync.replicas` 兜底）。

### 2.4 写入与复制链路

```
Producer → Leader: append 本地（LEO++）→ 等 ISR 确认（acks=all）
Follower: fetch(offset=自己的 LEO) → append → 上报 LEO
Leader: 收到足够确认（≥ min.insync.replicas）→ HW 推进 → 响应 Producer
消费者：只能读 HW 之前的数据
```

### 2.5 副本恢复与截断（Truncation）

副本重启后与 leader 对比：**按 leader epoch 找分歧点**，把分歧段截断（丢未复制部分），再从 leader 拉齐。
（KRaft 只管元数据一致性；**数据复制仍走 ISR/HW 这套**——与 Raft 的日志复制是两码事，这是常被混淆的点。）

---

## 三、使用（生产口径）

### 3.1 配置速查（4.0 默认值）

| 配置 | 默认 | 说明/建议 |
|---|---|---|
| `log.segment.bytes` | 1GB | 清理粒度；过大清理不细，过小文件多 |
| `log.index.interval.bytes` | 4096 | 稀疏索引步长（越小索引越大、扫描越少） |
| `log.retention.hours/ms` | 168h（7 天） | 按消费 SLA 设；**必须 > 最大消费延迟** |
| `log.retention.bytes` | -1（不限） | 按分区上限 |
| `min.insync.replicas` | **1** | **建议 ≥2**（否则 acks=all 不保险） |
| `replica.lag.time.max.ms` | 30000 | 太小误踢、太大拖 HW |
| `replica.fetch.max.bytes` | 1MB | follower 单次拉取上限（大消息注意） |
| `num.replica.fetchers` | 1 | 复制并发（高吞吐可调大） |
| `unclean.leader.election.enable` | false | **保持 false**（防落后副本当选丢数据） |
| `log.flush.*` | 靠 OS page cache（flush 默认几乎不主动触发） | 可靠性靠副本，不靠 fsync |

### 3.2 可靠性组合（Broker 侧）

```
RF=3 + min.insync.replicas=2 + acks=all + unclean=false
  → 容忍 1 副本故障不丢不阻塞；容忍 2 副本故障则拒绝写入（不丢但不可写）
```

### 3.3 运维监控四件

| 指标 | 含义 | 告警 |
|---|---|---|
| UnderReplicatedPartitions | 副本落后（ISR < RF） | 持续 >0 要查 |
| OfflinePartitions | 无 leader（不可读写） | 立即告警 |
| ISR 收缩/扩张频率 | 抖动（网络/磁盘慢） | 频繁即隐患 |
| HW 推进滞后 | 写延迟高 | 结合请求延迟看 |

### 3.4 排查入口

- 消费者"读不到最新消息" → **HW 语义**（正常；看 HW 与 LEO 差）
- 写超时 `NotEnoughReplicas` → ISR 收缩（查 follower 慢的原因：磁盘/网络/`replica.fetch.max.bytes`）
- 数据"丢了" → 查 `unclean` 是否被开、retention 是否早于消费位点

---

## 四、源码核心（调用链 + 行号）

### 4.1 写链（Leader）

```
ReplicaManager.appendRecords(:646)
  └─ appendToLocalLog(:1320)
       └─ Partition.appendRecordsToLeader(:1354)
            └─ UnifiedLog.appendAsLeader(:706)（core/Scala）
                 └─ LogSegment.append(:240)（storage/Java）+ OffsetIndex.append(:143)
  → Partition.maybeIncrementLeaderHW(:1153)（更新 HW = min ISR LEO）
  → acks=all：Partition.checkEnoughReplicasReachOffset(:1090)（等 ISR 到达才完成 DelayedProduce）
```

### 4.2 复制链（Follower）

```
ReplicaFetcherThread.processPartitionData(:101)
  └─ Partition.updateFollowerFetchState(:904)（记录 follower LEO → 尝试加入/踢出 ISR）
       └─ Partition.maybeIncrementLeaderHW(:1153)（ISR 最小 LEO 前移 → HW 推进）
```

### 4.3 读链与位点

```
ReplicaManager.fetchMessages(:1577)
  └─ UnifiedLog.read → LocalLog.segments(:147) 定位 segment → OffsetIndex 二分 + 扫描
       （HW 之前的才返回；零拷贝 sendfile 走 OS 页缓存）
HW 读写：UnifiedLog.highWatermark(:258) / updateHighWatermark(:269/:280)
分段容器：LocalLog.append(:528) / logEndOffset(:297)；activeSegment（UnifiedLog:1865）
截断依据：LeaderEpochFileCache（storage/.../internals/epoch/）
```

### 4.4 4.0 代码布局备注（读源码别找错地方）

```
storage 模块（Java）：LogSegment / OffsetIndex / TimeIndex / LocalLog / LogConfig / LeaderEpochFileCache
core 模块（Scala）：UnifiedLog（主日志）/ Partition / ReplicaManager / ReplicaFetcherThread / GroupCoordinator
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **HW 作为可见边界** | leader 写完即可见 | 已读数据切主后不丢（新 leader 至少到 HW）；代价是"读延迟 ≈ 复制延迟" |
| **ISR 动态集合** | 固定多数派（Raft） | 慢节点可踢出，写入不被拖死；用 `min.insync.replicas` 兜底不丢 |
| **稀疏索引** | 每条记录都索引 | 索引小（内存/磁盘省），代价是"二分 + 少量扫描" |
| **retention 以 segment 为单位** | 按 offset 精确删 | 实现简单（删文件）；代价是粒度粗（≤1 个 segment） |
| **flush 交给 OS（页缓存）** | 每条 fsync | 可靠性由副本保证；fsync 会毁吞吐（Kafka 的"不丢"不靠单机磁盘） |
| **数据复制不用 Raft** | 元数据/数据统一 Raft | 数据吞吐优先（ISR 轻量）；Raft 只用于 KRaft 元数据日志 |

---

## 六、生产陷阱（含复现思路）

| # | 陷阱 | 根因 | 修复 |
|---|---|---|---|
| 1 | **acks=all 仍丢** | `min.insync.replicas=1` 且 ISR 缩到 1（写入成功但无冗余） | min.insync ≥2 + unclean=false |
| 2 | **写入卡住/超时** | ISR 收缩到 < min.insync（慢副本被踢） | 查 follower 慢因；调大 replica.lag.time.max.ms（谨慎） |
| 3 | **消费读不到最新** | HW 语义（复制未完成） | 正常现象；检查 HW 推进是否停滞 |
| 4 | **数据被"提前删"** | retention < 消费延迟（位点落后 logStartOffset） | retention > 最大消费延迟；监控 lag |
| 5 | **ISR 抖动** | 网络抖动/磁盘 IO 慢/`num.replica.fetchers` 不足 | 排查 IO 与网络；调 fetcher 并发 |
| 6 | **unclean 打开的隐性债** | 落后副本当选 → 数据回退 | 保持 false（可用性 vs 一致性取舍要显式） |

---

## 七、面试 Q&A（含面试官意图）

**Q1："LEO 和 HW 的区别？"**（意图：位点模型）
LEO=下一条写入位（副本本地进度）；HW=ISR 最小 LEO（消费者可见边界）。消费者只能读到 HW 之前。

**Q2："acks=all 就保证不丢吗？"**（意图：组合拳）
不——还要 `min.insync.replicas≥2`（防 ISR 缩到 1）和 `unclean=false`（防落后副本当选）。三者缺一都可能丢。

**Q3："ISR 怎么维护？"**（意图：动态集合）
follower 周期 fetch；落后 > `replica.lag.time.max.ms` 踢出，追上（LEO≥HW）加回；写请求等 ISR 达标。

**Q4："副本怎么恢复/截断？"**（意图：一致性细节）
按 leader epoch 找分歧点截断，再从 leader 拉齐；KRaft 只负责元数据，数据复制仍是 ISR/HW。

**Q5："为什么读不到刚发的消息？"**（意图：可见性）
HW 未推进（follower 未复制完）——这是设计语义（已读不丢），不是 bug。

---

## 八、章节依赖

- **上游**：D-01（acks=all 的生产者侧等待）；D-02（协调器与位移存储 `__consumer_offsets` 也走这套复制）
- **下游**：D-04 事务与 Exactly-Once（LSO/事务索引在日志中的位置）
- **横链**：P02-RocketMQ D-01（CommitLog 存储设计——对比 Kafka 分区 segment 模型）；K01 拷打篇

---

## 九、验证锚点（Kafka 4.0.0）

| 声明 | 位置 |
|---|---|
| UnifiedLog.highWatermark / updateHighWatermark | `core/.../log/UnifiedLog.scala:258/269/280` |
| UnifiedLog.appendAsLeader / appendAsFollower | `core/.../log/UnifiedLog.scala:706/734` |
| activeSegment | `core/.../log/UnifiedLog.scala:1865` |
| LogSegment.append | `storage/.../internals/log/LogSegment.java:240` |
| OffsetIndex.append | `storage/.../internals/log/OffsetIndex.java:143` |
| LocalLog.append / logEndOffset / segments | `storage/.../internals/log/LocalLog.java:528/297/147` |
| Partition.isr / updateFollowerFetchState / checkEnoughReplicasReachOffset / maybeIncrementLeaderHW / appendRecordsToLeader | `core/.../cluster/Partition.scala:198/904/1090/1153/1354` |
| ReplicaManager.appendRecords / appendToLocalLog / fetchMessages | `core/.../server/ReplicaManager.scala:646/1320/1577` |
| ReplicaFetcherThread.processPartitionData | `core/.../server/ReplicaFetcherThread.scala:101` |
| LeaderEpochFileCache | `storage/.../internals/epoch/LeaderEpochFileCache.java` |
| segment.bytes=1GB / retention.ms=7d | `storage/.../log/LogConfig.java:129/132`（define:160/198/212） |
| min.insync.replicas=1 | `server-common/.../ServerLogConfigs.java:137` |
| index.interval.bytes=4096 / retention.bytes=-1 | `server-common/.../ServerLogConfigs.java:79/63` |
| replica.lag.time.max.ms=30s / fetch.max.bytes=1MB / num.replica.fetchers=1 | `server/.../config/ReplicationConfigs.java:47/60/88` |
| flush 默认 Long.MAX（不主动 fsync） | `server-common/.../ServerLogConfigs.java:83/91` |
| unclean.leader.election.enable（默认 false） | `clients/.../common/config/TopicConfig.java:165`；默认值 `storage/.../log/LogConfig.java:137/230` |
