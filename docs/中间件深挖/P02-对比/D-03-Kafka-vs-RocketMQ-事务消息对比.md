# P02-对比 D-03 — Kafka vs RocketMQ 事务消息对比

> **定位**：P02 对比系列第 3 篇（幂等+事务协调器 vs 半消息+回查）。
> **源码基线**：Kafka **4.0.0** / RocketMQ **5.1.4**
> **读法**：先看两边"转账场景"代码例子 → 流程/可见性/语义逐层对比 → 适用场景

---

## 一、一句话结论

| | Kafka | RocketMQ |
|---|---|---|
| **模型** | 协调器 + 两阶段 + marker（**原子提交**） | 半消息 + 本地事务 + 回查（**最终一致**） |
| **提交点** | 数据分区写 COMMIT/ABORT marker | 半消息"恢复"为真实消息 / 删除 |
| **消费者可见性** | `read_committed`：LSO + abort 过滤 | 半消息天然不可见（在内部 half topic） |
| **范围** | 跨分区 + **消费位移**（Exactly-Once） | 单条消息与本地事务的最终一致 |

---

## 二、例子：同一个"转账"两边怎么写

**Kafka（发送侧原子 + 位移原子）**：
```java
producer.initTransactions();
producer.beginTransaction();
producer.send(new ProducerRecord<>("transfer-out", key, value));
producer.send(new ProducerRecord<>("transfer-in",  key, value));
producer.sendOffsetsToTransaction(offsets, groupMeta);   // 位移也进事务
producer.commitTransaction();                            // 原子：全成功或全回滚
```

**RocketMQ（半消息 + 本地事务 + 回查）**：
```java
TransactionMQProducer producer = new TransactionMQProducer("tx-group");
producer.setTransactionListener(new TransactionListener() {
    // 半消息发送成功后执行本地事务
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        return doLocalTransfer() ? LocalTransactionState.COMMIT_MESSAGE
                                 : LocalTransactionState.ROLLBACK_MESSAGE;
    }
    // 未决时 Broker 回查（只读查本地事务结果）
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        return queryLocalResult(msg) ? LocalTransactionState.COMMIT_MESSAGE
                                     : LocalTransactionState.ROLLBACK_MESSAGE;
    }
});
producer.sendMessageInTransaction(msg, arg);   // 半消息 → 本地事务 → 提交/回滚
```

**差异直观感受**：Kafka 把"多条消息+位移"打包成一个事务；RocketMQ 把"一条消息+本地事务"绑定，靠回查保证最终一致。

---

## 三、流程对比

### 3.1 Kafka 流程（主动两阶段）

```
initTransactions → 找协调器（txn.id hash）→ PID/epoch
beginTransaction → send（AddPartitionsToTxn 登记分区）
commitTransaction → 协调器写 PREPARE_COMMIT → WriteTxnMarkers 到各分区
                 → 各分区写 COMMIT marker → COMPLETE_COMMIT
超时（transaction.timeout.ms=60s）→ 协调器主动 abort（写 ABORT marker）
```

### 3.2 RocketMQ 流程（半消息 + 回查）

```
1. 发送半消息：原 topic/queue 替换为 RMQ_SYS_TRANS_HALF_TOPIC（消费者不可见）
2. 执行本地事务：executeLocalTransaction（应用侧）
3a. COMMIT → 半消息恢复为真实消息（写回原 topic/queue）
3b. ROLLBACK → 删除半消息 + 写 OP 记录（RMQ_SYS_TRANS_OP_HALF_TOPIC）
4. 未决（UNKNOWN / 进程崩溃）→ Broker 定时回查（transactionCheckInterval=30s）
   → checkLocalTransaction 再问一次 → 最多 transactionCheckMax=15 次
   → 超限丢弃 + 写 TRANS_CHECK_MAX_TIME_TOPIC（可监控）
```

---

## 四、可见性对比

| | Kafka | RocketMQ |
|---|---|---|
| 未决消息 | 在数据分区（未提交），**read_committed 不可见**（LSO 未推进） | 半消息在 half topic，**任何消费者都不可见**（物理隔离） |
| 提交后 | marker 写入 + LSO 推进 → 可见 | 半消息恢复为真实消息 → 可见 |
| 回滚后 | ABORT marker；消费者靠 `.txnindex` 过滤 | 半消息删除；消费者从未见过 |
| 隔离实现 | 读路径过滤（LSO + TransactionIndex） | 写路径隔离（换个 topic 放半消息） |

**哲学差异**：Kafka 靠"**读时过滤**"（同一条日志含所有状态）；RocketMQ 靠"**写时隔离**"（半消息物理上在另一个 topic）。

---

## 五、为什么 RocketMQ 要"回查"而 Kafka 不要？

```
RocketMQ 回查的必要性：
  半消息发出后、本地事务结果未告知 Broker 时（进程崩溃/网络断）
  → Broker 无法知道该 commit 还是 rollback
  → 必须"反查"应用（checkLocalTransaction）
  → 应用是唯一知道本地事务结果的一方（Broker 不参与本地事务）

Kafka 不需要回查：
  事务状态机完全在 Kafka 内部（协调器 + 客户端 TransactionManager）
  → 协调器掌握"事务进行到哪一步"（PREPARE_COMMIT 已写入）
  → 客户端失联 → 超时直接 abort（无需询问应用）
  → 前提：Kafka 事务不覆盖"应用本地事务"——只覆盖 Kafka 消息本身
```

**一句话**：RocketMQ 的事务跨了"应用数据库 + MQ"（必须回查应用）；Kafka 的事务只覆盖"MQ 内部"（协调器自己说了算）。

---

## 六、语义与能力对比

| 能力 | Kafka | RocketMQ |
|---|---|---|
| 多消息/多分区原子 | ✅（一个事务跨分区） | ❌（每条消息独立事务） |
| 消费位移进事务 | ✅（sendOffsetsToTransaction） | ❌（位移提交独立） |
| 端到端 Exactly-Once | ✅（read_committed + 幂等 + 事务） | ❌（最终一致 + 消费幂等兜底） |
| 与应用本地事务绑定 | ❌（不覆盖应用 DB） | ✅（executeLocalTransaction） |
| 未决消息处理 | 协调器超时 abort | 定时回查（最多 15 次）→ 丢弃告警 |

---

## 七、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **Kafka：协调器统一管事务** | 半消息+回查 | Kafka 事务域在 MQ 内部（可封闭）；协调器状态机 + 超时即可，无需询问应用 |
| **RMQ：半消息物理隔离** | 写进原 topic 加"未提交"标记 | 消费者零成本隔离（看不到 half topic）；Kafka 相反（读时过滤，日志统一） |
| **RMQ：回查而不是 2PC** | prepare 后等 commit（XA） | 避免未决消息长期占用可见位置 + 避免消费者参与 2PC；最终一致换取简单 |
| **Kafka：marker 写数据分区** | 事务结果只在 txn log | 消费者读数据分区即可判断（无需查协调器） |
| **RMQ：只绑定单条消息** | 支持跨消息事务 | 业务事务消息通常"一条消息对应一次本地事务"；跨消息用别的手段（对账/补偿） |

---

## 八、生产实践对比

| 实践点 | Kafka | RocketMQ |
|---|---|---|
| 关键配置 | transactional.id（稳定）、transaction.timeout.ms=60s | transactionTimeOut=6s、transactionCheckMax=15、transactionCheckInterval=30s |
| 超时兜底 | 协调器超时 abort | 回查超限丢弃（监控 CHECK_MAX_TIME topic） |
| 消费端 | isolation.level=read_committed | 无需特殊配置（半消息不可见） |
| 幂等兜底 | 事务已保证，但消费侧仍建议幂等 | **必须**消费幂等（最终一致） |
| 性能开销 | marker + 协调器交互（较重） | 半消息 + 回查（轻量但回查有延迟） |
| 适用场景 | 流处理 EOS、CDC、跨分区原子 | 订单/支付等"本地事务+通知"场景 |

---

## 九、面试 Q&A（含面试官意图）

**Q1："RocketMQ 事务消息流程？"**（意图：机制背诵）
半消息（half topic）→ 本地事务 → COMMIT（恢复真实消息）/ROLLBACK（删除）→ 未决时 Broker 回查（30s 间隔、最多 15 次）→ 超限丢弃。

**Q2："为什么需要回查？"**（意图：故障模型）
半消息发出后应用崩溃/未回告，Broker 不知道结果；应用是唯一知道本地事务结果的一方——必须反查。

**Q3："Kafka 为什么不用回查？"**（意图：对比理解）
Kafka 事务状态全在 MQ 内部（协调器 + 客户端状态机），协调器超时可直接 abort；它不覆盖应用本地事务，所以没有"要问应用"的问题。

**Q4："两者事务语义一样吗？"**（意图：边界）
不一样：Kafka=原子提交（跨分区 + 位移，EOS）；RocketMQ=最终一致（单消息 + 本地事务，消费幂等兜底）。

**Q5："各自适合什么场景？"**（意图：选型）
Kafka：流处理/数据管道要 EOS；RocketMQ：业务解耦（订单成功后发消息）要"本地事务+消息"一致性。

---

## 十、验证锚点

**Kafka 4.0.0**
| 声明 | 位置 |
|---|---|
| 协调器：InitPid/AddPartitions/EndTxn | `core/.../coordinator/transaction/TransactionCoordinator.scala:109/389/488` |
| 结束事务与 marker | `TransactionCoordinator.scala:731`（endTransaction）→ `:597/894`（markers） |
| 客户端事务 API | `clients/.../internals/TransactionManager.java:310/322/334/368` |
| marker 类型 COMMIT/ABORT | `clients/.../common/record/ControlRecordType.java:45-46` |
| abort 过滤（txnindex） | `storage/.../internals/log/TransactionIndex.java:84/169` |
| LSO | `core/.../log/UnifiedLog.scala:386/417` |
| txn 日志与超时 | `transaction-coordinator/.../TransactionLogConfig.java:32/45`；`ProducerConfig.java:518-520`（60s） |

**RocketMQ 5.1.4**
| 声明 | 位置 |
|---|---|
| 事务服务（回查核心） | `broker/.../transaction/queue/TransactionalMessageServiceImpl.java:51/144`（check） |
| 提交/回滚落点 | `broker/.../processor/EndTransactionProcessor.java:128`（commitMessage）/`:146/163`（deletePrepareMessage）/`:259`（恢复消息 putMessage） |
| half/op 主题常量 | `common/.../topic/TopicValidator.java:28/30/31` |
| 半消息读写桥 | `broker/.../transaction/queue/TransactionalMessageBridge.java:60/112/212/219`（parseHalfMessageInner） |
| 回查定时线程 | `broker/.../transaction/queue/TransactionalMessageCheckService.java:25/53` |
| 配置默认 | `common/.../BrokerConfig.java:255/261/267`（6s/15 次/30s） |
| 客户端 API | `client/.../impl/producer/DefaultMQProducerImpl.java:1381`；`client/.../producer/TransactionListener.java:30/39` |
