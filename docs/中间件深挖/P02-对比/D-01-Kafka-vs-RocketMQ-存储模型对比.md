# P02-对比 D-01 — Kafka vs RocketMQ 存储模型对比

> **定位**：P02 对比系列第 1 篇（Partition 分段日志 vs CommitLog 共用日志）。
> **源码基线**：Kafka **4.0.0**（`code/spring/kafka`） / RocketMQ **5.1.4**（`code/spring/rocketmq-5.1.4`）
> **读法**：先看结论表 → 再看同一条消息的落盘例子 → 再对比写/读/零拷贝路径 → 最后看设计哲学与生产影响

---

## 一、一句话结论

| | Kafka | RocketMQ |
|---|---|---|
| **核心模型** | **日志即存储**：每分区独立分段日志（.log/.index/.timeindex） | **双文件分离**：全局 CommitLog（数据）+ ConsumeQueue（索引） |
| **写入单位** | 分区（partition） | Broker（所有 Topic 混写一个日志） |
| **一句话** | 每个分区是一条"有序日志流"，文件系统能力用到极致 | 所有消息先落一条"全局顺序日志"，再异步建索引供消费 |

---

## 二、同一条消息的落盘例子

**场景**：Broker 收到 topic=orders、queue/partition=2 的一条 1KB 消息。

```
Kafka（分区独立）：
  /kafka-logs/orders-2/
    ├── 00000000000000000000.log      ← 消息追加到 activeSegment（1GB 滚动）
    ├── 00000000000000000000.index    ← 每 4096 字节记一条稀疏索引
    └── 00000000000000000000.timeindex

RocketMQ（双文件）：
  /store/commitlog/00000000000000000000  ← 追加到 CommitLog（所有 Topic 共用，1GB/文件）
  然后 ReputMessageService 异步分发：
  /store/consumequeue/orders/2/00000000000000000000  ← 追加 20 字节定长索引条目
  /store/index/xxx                                   ← 可选：按 message key 的哈希索引
```

**关键差异**：Kafka 写入 = "写自己分区的日志"；RocketMQ 写入 = "写全局日志 + 异步补索引"。

---

## 三、存储结构对比

| 维度 | Kafka | RocketMQ |
|---|---|---|
| 数据文件 | 每分区一组 segment（`.log`） | 全局 CommitLog（所有 Topic/队列混存） |
| 索引文件 | `.index`（偏移稀疏索引）+ `.timeindex`（时间索引） | ConsumeQueue（消费索引，20B/条）+ IndexFile（key 索引，哈希） |
| 索引粒度 | 稀疏（每 4096 字节一条，`index.interval.bytes`） | 稠密（每条消息一条 20B 条目） |
| 文件大小 | segment 1GB（`log.segment.bytes`） | CommitLog 1GB（`mappedFileSizeCommitLog`）；ConsumeQueue 6MB=30W 条×20B |
| 文件命名 | baseOffset（分区内偏移） | 起始物理偏移（全局） |
| 清理粒度 | 按分区按 segment 文件（retention） | 按 CommitLog 文件（72h）+ ConsumeQueue 跟随 |
| 消费位置 | 分区 offset（逻辑偏移） | ConsumeQueue offset（队列内逻辑序号）→ 映射 CommitLog 物理偏移 |

---

## 四、写路径对比

### 4.1 Kafka 写链

```
ReplicaManager.appendRecords → Partition.appendRecordsToLeader
  → UnifiedLog.appendAsLeader → LogSegment.append（activeSegment 追加）
  → OffsetIndex.append（每 4096B 稀疏记录）
  → 刷盘交给 OS page cache（默认不主动 fsync，可靠性靠 ISR 副本）
```

### 4.2 RocketMQ 写链

```
CommitLog.asyncPutMessage(:824)
  → MappedFile.appendMessage（DefaultMappedFile:259，mmap 内存映射写）
  → handleDiskFlush(:1209)：ASYNC_FLUSH（默认，页缓存刷盘）或 SYNC_FLUSH
  → 写入成功即返回（Reput 异步线程再分发索引）
  → ReputMessageService（DefaultMessageStore:2744）→ doDispatch(:1952)
       → ConsumeQueue.putMessagePositionInfo(:881)（20B 条目）
       → IndexFile（按 key 建哈希索引）
```

### 4.3 差异要点

| 点 | Kafka | RocketMQ |
|---|---|---|
| 写入目标 | 自己分区的 activeSegment | 全局 CommitLog（所有 Topic 混写） |
| 索引建立 | 写时同步（稀疏 index） | **写后异步**（Reput 分发 ConsumeQueue） |
| 落盘手段 | FileChannel 追加 + OS 页缓存 | **mmap**（MappedByteBuffer）写 |
| 刷盘策略 | 不主动 fsync（靠副本） | ASYNC_FLUSH/SYNC_FLUSH 可选（单机可靠性） |

---

## 五、读路径对比

### 5.1 Kafka 读链（offset → 文件）

```
消费者要 offset=1000：
  UnifiedLog.read → LocalLog.segments 二分定位 segment（baseOffset ≤ 1000）
  → OffsetIndex 二分（稀疏索引）→ 从索引位置顺序扫描 → 读到消息
  → 网络发送走 sendfile（FileRecords.writeTo:294 → PlaintextTransportLayer.transferFrom:213
     = fileChannel.transferTo，零拷贝）
```

### 5.2 RocketMQ 读链（ConsumeQueue → CommitLog）

```
消费者要 queue offset=500：
  ConsumeQueue 定长条目（20B）→ 直接算出文件内位置（offset×20）→ 读出
    （8B CommitLog 物理偏移 + 4B 消息长度 + 8B tagsCode）
  → 用物理偏移到 CommitLog 读消息体（page cache / mmap）
  → 网络发送（Netty FileRegion → transferTo 零拷贝；读 ConsumeQueue 走 mmap）
```

### 5.3 差异要点

| 点 | Kafka | RocketMQ |
|---|---|---|
| 定位方式 | 逻辑 offset → segment 二分 + 稀疏索引扫描 | 逻辑序号 → **定长条目 O(1)** → 物理偏移 |
| 读放大 | 索引到目标之间需扫描（≤4096B 数据） | 定长索引无扫描；数据体从 CommitLog 随机读（page cache 命中率高） |
| 零拷贝 | **sendfile**（transferTo） | **FileRegion transferTo**（消息体 page cache 直送网卡）+ ConsumeQueue mmap |

---

## 六、设计哲学对比（反面假设表）

| 问题 | Kafka 的选择 | RocketMQ 的选择 | 为什么 |
|---|---|---|---|
| **日志按什么组织？** | 每分区独立日志 | 全局一条 CommitLog | Kafka：分区即消费/复制单位，日志与分区对齐；RMQ：**写入只做顺序追加**（哪怕跨 Topic），把随机性挪到读侧 |
| **索引同步建还是异步建？** | 写时同步（稀疏） | 写后异步（Reput） | Kafka 索引极小（稀疏）；RMQ 每条都建稠密索引，异步化保写入吞吐 |
| **为什么 RMQ 不怕随机读？** | — | ConsumeQueue 定长 O(1) + page cache | 读集中在"最近写入"（消费滞后小），page cache 命中率高 |
| **retention 粒度** | 分区级（按 segment 文件删） | CommitLog 全局（文件级） | RMQ 混存导致"一个 Topic 过期 = 全局文件等最慢者"；Kafka 可精确到分区 |
| **零拷贝选型** | sendfile（读） | mmap（写/索引读） | Kafka 读放大；RMQ 写密集——mmap 避免用户态拷贝，sendfile 适合"读大文件直接推网卡" |

---

## 七、生产影响（容量/性能/运维）

| 维度 | Kafka | RocketMQ |
|---|---|---|
| 分区/队列扩容 | 加分区（老消息不重分布） | 加队列（ConsumeQueue 按队列独立） |
| 磁盘热点 | 按分区分布（活跃分区集中） | CommitLog 全局顺序写（**单盘写热点**，靠多盘/多 Broker 摊） |
| 过期删除粒度 | 分区级精确 | 全局文件级（受最慢 Topic 影响） |
| 索引重建 | segment 自带索引 | CommitLog 在、ConsumeQueue 可重建（recover） |
| 运维关键指标 | 分区大小、HW、URP | CommitLog 磁盘水位、dispatchBehindBytes（分发滞后） |
| 容量规划要点 | 分区数 × segment | CommitLog 总量（所有 Topic 之和） |

---

## 八、面试 Q&A（含面试官意图）

**Q1："Kafka 和 RocketMQ 存储的最大区别？"**（意图：模型级理解）
Kafka 每分区独立分段日志（日志即存储）；RocketMQ 全局 CommitLog + ConsumeQueue 双文件（写入全局顺序、读取索引定位）。

**Q2："RocketMQ 为什么所有 Topic 共用一个 CommitLog？"**（意图：IO 原理）
让写路径永远是"顺序追加"（最大化磁盘吞吐），把跨 Topic 的随机性转移到读侧（ConsumeQueue 定长索引 + page cache）。

**Q3："Kafka 为什么不用 CommitLog 模式？"**（意图：tradeoff）
分区是 Kafka 的复制/消费单位，日志与分区对齐简化了 ISR/位点/HW 语义；代价是文件数多（分区×segment）。

**Q4："两者读路径谁快？"**（意图：场景分析）
RocketMQ 索引定长 O(1)（少扫描），适合"消费滞后小、读热点集中"；Kafka 稀疏索引需小扫描但 sendfile 零拷贝，顺序消费场景吞吐高。

**Q5："retention 删除的差异对生产的影响？"**（意图：运维经验）
Kafka 分区级精确；RocketMQ 全局文件级——某 Topic 大量积压会拖住整盘文件的过期（受最慢消费者影响）。

---

## 九、验证锚点

**Kafka 4.0.0**
| 声明 | 位置 |
|---|---|
| 分段追加 | `storage/.../internals/log/LogSegment.java:240` |
| 稀疏索引 | `storage/.../internals/log/OffsetIndex.java:143`；间隔 `LogConfig.java`（`LOG_INDEX_INTERVAL_BYTES_DEFAULT=4096`，ServerLogConfigs:79） |
| segment 1GB / retention 7d | `storage/.../log/LogConfig.java:129/132` |
| activeSegment / appendAsLeader | `core/.../log/UnifiedLog.scala:1865/706` |
| 零拷贝（sendfile） | `clients/.../common/record/FileRecords.java:294` → `clients/.../common/network/PlaintextTransportLayer.java:213-214` |

**RocketMQ 5.1.4**
| 声明 | 位置 |
|---|---|
| CommitLog 写入 | `store/.../store/CommitLog.java:824`（asyncPutMessage） |
| 刷盘 | `store/.../store/CommitLog.java:1209`（handleDiskFlush）；默认 ASYNC_FLUSH（`MessageStoreConfig.java:213`） |
| mmap 追加 | `store/.../store/logfile/DefaultMappedFile.java:259`；接口 `MappedFile.java:88` |
| ConsumeQueue 20B 定长 | `store/.../store/ConsumeQueue.java:61`；写索引 `:881`（putMessagePositionInfo） |
| 异步分发 | `store/.../store/DefaultMessageStore.java:2744`（ReputMessageService）→ `:1952`（doDispatch） |
| 文件大小 | `MessageStoreConfig.java:51`（CommitLog 1GB）/ `:110`（ConsumeQueue 300000×20B=6MB） |
| 零拷贝发送（FileRegion） | `remoting/.../netty/FileRegionEncoder.java:50/76`；`broker/.../processor/DefaultPullMessageResultHandler.java:150` |
| IndexFile（key 哈希索引） | `store/.../store/index/IndexFile.java` |
| 文件保留 72h | `store/.../config/MessageStoreConfig.java:160` |
