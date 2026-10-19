# P02-Kafka D-04 — 事务与 Exactly-Once

> **定位**：Kafka 深挖系列第 4 篇（收尾篇）。三层齐：**原理（心智模型）+ 使用（配置/实践）+ 源码（核心调用链）**。
> **源码版本**：apache/kafka **4.0.0**（协调器逻辑仍在 core/Scala；PID 管理与配置在 `transaction-coordinator` 模块）
> **质量标准**：源码阅读清单 / 完整调用链 / 设计哲学（反面假设）/ 生产陷阱 / 面试 Q&A（意图）/ 章节依赖

---

## 一、它干了什么：一个例子

**"消费-转换-生产"要保证端到端 Exactly-Once**（如：从 orders 消费 → 写 result 表 + 提交位移，两者必须原子）：

```java
producer.initTransactions();                      // 1. 找协调器，拿 PID + epoch
try {
    producer.beginTransaction();                  // 2. 开启事务
    producer.send(new ProducerRecord<>("result", key, value));   // 3. 业务消息
    producer.sendOffsetsToTransaction(offsets, consumerGroupMetadata); // 4. 位移也纳入事务
    producer.commitTransaction();                 // 5. 原子提交（全成功）
} catch (Exception e) {
    producer.abortTransaction();                  // 或原子回滚（全失败）
}
```

对端 `isolation.level=read_committed` 的消费者：**要么看到结果消息 + 新位移，要么都看不到**（中间态不可见）。

**一句话**：Kafka 事务 = **"PID 幂等 + 事务协调器 + 数据分区 marker + LSO 可见性"**——把"多分区写入 + 消费位移"打包成一个原子单元。

---

## 二、原理（心智模型，不贴源码）

### 2.1 事务要解决什么

| 问题 | 幂等生产者 | 事务 |
|---|---|---|
| 重试导致单分区重复 | ✅（PID+序列号） | ✅ |
| 多分区写入原子性 | ❌ | ✅ |
| 消费位移与业务消息原子提交 | ❌ | ✅（sendOffsetsToTransaction） |

### 2.2 五个组件

```
TransactionCoordinator（Broker 端协调器）
  ├─ 按 transactional.id hash 选 __transaction_state 分区（同一 ID 固定协调器）
  ├─ PID/epoch 分配（ProducerIdManager，epoch 递增用于 fence 僵尸实例）
  └─ 事务日志 __transaction_state（状态机持久化）

数据分区侧：
  ├─ Marker：COMMIT/ABORT 控制记录直接写入涉及的数据分区（ControlRecordType）
  ├─ TransactionIndex（.txnindex）：记录已 abort 的事务区间
  └─ LSO（Last Stable Offset）：未决事务之前的稳定边界
```

### 2.3 完整流程

```
initTransactions      → 找协调器（hash txn.id）→ InitProducerId → PID + epoch
beginTransaction      → 本地状态机进入 IN_TRANSACTION
send(...)             → 首次涉及某分区时向协调器 AddPartitionsToTxn 登记
sendOffsetsToTransaction → TxnOffsetCommit（位移提交也走事务，group coordinator 参与）
commitTransaction     → 协调器写 PREPARE_COMMIT 到 txn log
                      → 向所有涉及分区发 WriteTxnMarkers → 各分区写 COMMIT marker
                      → 协调器写 COMPLETE_COMMIT（事务完成）
abortTransaction      → 同理写 ABORT marker；消费者 read_committed 过滤掉该事务数据
```

### 2.4 消费侧可见性（read_committed）

```
read_uncommitted（默认）：读到 HW 之前全部（含未提交/已 abort——脏读）
read_committed：
  1) 只读 LSO 之前（未决事务不返回——消费者要等事务完成）
  2) 对每个 segment 查 .txnindex（collectAbortedTxns）→ 过滤已 abort 的数据
```

### 2.5 fencing（僵尸实例防护）

同一 `transactional.id` 的新实例 initTransactions → **epoch 递增** → 旧实例（网络分区后复活）继续写入时被拒（ProducerFencedException）——与幂等 PID 机制复用。

---

## 三、使用（生产口径）

### 3.1 配置速查（4.0 默认值）

| 配置 | 默认 | 说明/建议 |
|---|---|---|
| `transactional.id` | 无（事务必须显式设置） | **必须稳定**（重启同 ID 才能恢复 + fence 旧实例） |
| `transaction.timeout.ms` | 60000 | 协调器判超时 abort；**≥ 最长事务处理时间** |
| `isolation.level`（消费） | read_uncommitted | 事务场景设 `read_committed` |
| `enable.idempotence` | true | 事务自动依赖（无需显式） |
| `transaction.state.log.num.partitions` | 50 | **部署后不可改** |
| `transaction.state.log.replication.factor` | 3 | txn 日志副本数 |
| `transaction.state.log.min.isr` | 2 | txn 日志最小 ISR |

### 3.2 生产实践

- **transactional.id 命名**：按"业务实例身份"（如 `order-service-1`），**不要随机 UUID**（否则无法 fence 且每次 init 都是新身份）
- **超时预算**：`transaction.timeout.ms` ≥ 事务内最长处理（含所有 send + 位移提交）；否则协调器中途 abort
- **性能取舍**：事务有 marker 写入 + 协调器交互开销——**不需要 EOS 就别用**（幂等已覆盖单分区去重）
- **消费-转换-生产**：位移必须走 `sendOffsetsToTransaction`（而非 commitSync）才在事务内
- **监控**：协调器侧事务超时/abort 次数、`ProducerFencedException`、LSO 滞后（read_committed 消费者延迟）

### 3.3 排查入口

- `ProducerFencedException` → 同 transactional.id 有活跃实例（检查是否有僵尸/双跑）
- 事务被 abort → 查 `transaction.timeout.ms` 是否小于实际处理时间
- read_committed 消费"慢" → LSO 滞后（未决事务未结束/长事务）
- 重复消费 → 位移没进事务（用错 API）

---

## 四、源码核心（调用链 + 行号）

### 4.1 客户端（TransactionManager）

```
KafkaProducer.sendOffsetsToTransaction(:727)
  └─ TransactionManager.sendOffsetsToTransaction(:368)
       （本地状态机：beginTransaction:310 / beginCommit:322 / beginAbort:334）
       → maybeSendAndPollTransactionalRequest（与协调器交互：InitPid/AddPartitions/TxnOffsetCommit/EndTxn）
       → epoch 失效时重置：bumpIdempotentEpochAndResetIdIfNeeded(:623，见 D-01）
```

### 4.2 服务端（TransactionCoordinator + 状态机）

```
TransactionCoordinator.handleInitProducerId(:109)          → 分配 PID/epoch
TransactionCoordinator.handleAddPartitionsToTransaction(:389) → 登记分区
TransactionCoordinator.handleEndTransaction(:488)
  └─ endTransaction(:731)
       ├─ 写 PREPARE_COMMIT/PREPARE_ABORT 到 __transaction_state
       ├─ sendTxnMarkersCallback(:597/:894)（WriteTxnMarkers 到各分区）
       └─ onEndTransactionComplete(:987)（写 COMPLETE_COMMIT/ABORT）

状态机：TransactionMetadata.prepareAbortOrCommit(:336) / prepareDead(:372)
状态存储：TransactionStateManager.partitionFor(:416)（txn.id hash → 分区）
         getTransactionState(:301) / putTransactionStateIfNotExists(:305)
         txn 日志主题：__transaction_state（Topic.java:28）
```

### 4.3 数据分区侧（marker 与读过滤）

```
marker 类型：ControlRecordType ABORT=0 / COMMIT=1（:45-46）
abort 索引：TransactionIndex.append(:84) / collectAbortedTxns(:169)
           → LogSegment.collectAbortedTxns(:528) → LocalLog.read(:561) 过滤
LSO：UnifiedLog.firstUnstableOffset(:386) / lastStableOffset(:417)
     maybeIncrementFirstUnstableOffset(:953)
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **marker 写数据分区** | 事务结果只存 txn log | 消费者只读数据分区即可判断事务结果（无需跨系统查询/参与协调） |
| **LSO 可见性边界** | 事务提交后消费者才推进 offset | LSO 统一"稳定边界"：未决事务前的数据先可见，长事务不阻塞无关数据 |
| **txn.id hash 选协调器** | 任意协调器/全局单点 | 同一事务 ID 状态固定在一处（简单、无跨协调器协调） |
| **epoch fencing** | 只靠 PID | 防僵尸实例复活写入（网络分区场景必需） |
| **协调器做进 Broker（非 XA/2PC 外部）** | 标准 XA 两阶段提交 | 避免消费者参与 2PC；Kafka 把"提交点"落到数据分区 marker 上 |
| **50 个 txn 分区固定** | 可动态扩 | 事务状态按 id hash 固定分区；扩容会打乱映射（**不可改**） |

---

## 六、生产陷阱（含复现思路）

| # | 陷阱 | 根因 | 修复 |
|---|---|---|---|
| 1 | **transactional.id 随机化** | 每次启动新身份 → 无法 fence 旧实例、无法恢复 | 用稳定业务身份 |
| 2 | **长事务被协调器 abort** | `transaction.timeout.ms`（60s）< 实际处理时间 | 调大或拆分事务 |
| 3 | **位移没进事务** | 用 `commitSync` 而非 `sendOffsetsToTransaction` | 换 API（EOS 关键） |
| 4 | **read_committed 消费延迟** | 等 LSO（未决事务不返回） | 控制事务时长；监控 LSO 滞后 |
| 5 | **不需要 EOS 却用事务** | marker/协调器开销换不来收益 | 幂等（默认开）已够单分区去重 |
| 6 | **read_uncommitted 脏读** | 默认隔离级别 | 事务场景消费端显式 read_committed |
| 7 | **僵尸实例双写** | 旧实例未停 + 新实例同 id | epoch fencing 会拒绝旧实例——但要监控告警 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："Kafka 事务解决什么问题？"**（意图：EOS 边界）
多分区写入的原子性 + 消费位移与业务消息的原子提交（consume-transform-produce 的端到端 EOS）。单分区去重靠幂等即可。

**Q2："事务提交流程？"**（意图：机制全貌）
init（找协调器/PID）→ begin → send（AddPartitionsToTxn）→ sendOffsetsToTransaction → commit：写 PREPARE_COMMIT 到 txn log → 各分区写 COMMIT marker → COMPLETE_COMMIT。

**Q3："read_committed 怎么实现？"**（意图：可见性细节）
读 LSO 之前 + 用 .txnindex 过滤已 abort 数据。代价是"等未决事务完成"。

**Q4："幂等和事务的区别？"**（意图：概念边界）
幂等=PID+序列号，单分区去重（发送侧）；事务=跨分区/位移原子（需要协调器 + marker + LSO）。

**Q5："和 RocketMQ 事务消息对比？"**（意图：双轨）
RocketMQ=半消息 + 回查（最终一致，消费侧可见半消息不可见）；Kafka=协调器两阶段 marker（原子提交，消费者可见性由 LSO 控制）。RocketMQ 无"位移进事务/跨分区原子"。

---

## 八、章节依赖

- **上游**：D-01（幂等 PID/epoch 基础）；D-03（LSO/TransactionIndex 在日志中的位置）；D-02（TxnOffsetCommit 与 GroupCoordinator 协作）
- **下游**：P02-对比（事务模型 Kafka vs RocketMQ）
- **横链**：P02-RocketMQ D-03（事务消息半消息回查）；K01 拷打篇

---

## 九、验证锚点（Kafka 4.0.0）

| 声明 | 位置 |
|---|---|
| sendOffsetsToTransaction | `clients/.../KafkaProducer.java:727` |
| TransactionManager.beginTransaction/beginCommit/beginAbort/sendOffsetsToTransaction | `clients/.../internals/TransactionManager.java:310/322/334/368` |
| epoch 重置 | `clients/.../internals/TransactionManager.java:623` |
| handleInitProducerId / handleAddPartitionsToTransaction / handleEndTransaction | `core/.../coordinator/transaction/TransactionCoordinator.scala:109/389/488` |
| endTransaction / sendTxnMarkersCallback / onEndTransactionComplete | `core/.../coordinator/transaction/TransactionCoordinator.scala:731/597+894/987` |
| 状态机 prepareAbortOrCommit / prepareDead | `core/.../coordinator/transaction/TransactionMetadata.scala:336/372` |
| partitionFor（hash 选分区） | `core/.../coordinator/transaction/TransactionStateManager.scala:416` |
| txn 日志主题名 | `clients/.../common/internals/Topic.java:28` |
| txn 日志配置 50/3/100MB/ISR=2 | `transaction-coordinator/.../TransactionLogConfig.java:32/40/36/45` |
| marker 类型 ABORT=0/COMMIT=1 | `clients/.../common/record/ControlRecordType.java:45-46` |
| TransactionIndex.append/collectAbortedTxns | `storage/.../internals/log/TransactionIndex.java:84/169` |
| abort 过滤链 | `storage/.../internals/log/LogSegment.java:528` → `LocalLog.java:561` |
| LSO | `core/.../log/UnifiedLog.scala:386/417/953` |
| transaction.timeout.ms=60000 | `clients/.../ProducerConfig.java:346/518-520` |
| isolation.level 默认 read_uncommitted | `clients/.../ConsumerConfig.java:367/632-635` |
