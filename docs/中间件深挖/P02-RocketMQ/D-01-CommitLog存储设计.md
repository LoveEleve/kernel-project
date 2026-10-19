# P02-RocketMQ D-01 — CommitLog 存储设计

> **定位**：RocketMQ 深挖系列第 1 篇（按 P02 规划产出物）。**写一次顺序 IO** 是 RocketMQ 高性能的根基。
> **源码版本**：rocketmq-all-5.1.4（`code/spring/rocketmq-5.1.4`）
> **质量标准**：源码阅读清单 / 完整调用链 / 设计哲学（反面假设）/ 生产陷阱 / 面试 Q&A（意图）/ 章节依赖

---

## 一、它干了什么：一个例子

Producer 发一条消息到 Broker：

```
网络 → SendMessageProcessor → DefaultMessageStore.asyncPutMessage（:550）
  → CommitLog.asyncPutMessage（:824）
      → putMessageLock.lock()（:909）
      → 取最后一个 MappedFile（1GB 文件，mmap 映射）
      → MappedFile.appendMessage → DefaultAppendMessageCallback.doAppend（:1750）
          把 TOTALSIZE/MAGIC/CRC/queueId/flag/queueOffset/physicOffset/sysFlag/时间戳/body/properties
          顺序写入 mappedByteBuffer（mmap 内存映射，写即写 PageCache）
      → handleDiskFlushAndHA（:995）：按配置触发刷盘（异步 500ms / 同步等落盘）+ 主从复制
  → 返回 SEND_OK
异步旁路：ReputMessageService.doReput（:2782）扫到新写入 → 分发
  → CommitLogDispatcherBuildConsumeQueue（:2127）写 20B 索引
  → CommitLogDispatcherBuildIndex（:2144）写 IndexFile（有 KEYS 时）
```

**一句话**：所有 Topic 的消息**顺序追加到一个 CommitLog**（mmap + PageCache）——写路径只有一次顺序 IO；索引（ConsumeQueue/IndexFile）由后台线程异步分发，不在写路径上。

---

## 二、源码阅读清单

| 文件 | 行数 | 关键方法 | 行号 |
|---|---|---|---|
| `store/DefaultMessageStore.java` | 3000+ | `asyncPutMessage` / `putMessage` / `recover` | :550 / :621 / :1855 |
| `store/CommitLog.java` | 1900+ | `asyncPutMessage` / `handleDiskFlushAndHA` / `DefaultAppendMessageCallback.doAppend` | :824 / :995 / :1750 |
| `store/logfile/MappedFile.java` | 371 | 接口（5.x 拆分：接口+实现） | —— |
| `store/logfile/DefaultMappedFile.java` | 929 | **mmap 映射** / `appendMessagesInner` / `flush` | :168 / :270 / :364 |
| `store/CommitLog.java` | —— | `FlushRealTimeService`（异步）/ `GroupCommitService`（同步） | :1403 / :1530 |
| `store/DefaultMessageStore.java` | —— | `ReputMessageService.doReput` / 两个 Dispatcher | :2782 / :2127 / :2144 |
| `store/config/MessageStoreConfig.java` | —— | `mappedFileSizeCommitLog`=1GB / `mappedFileSizeConsumeQueue` / `CQ_STORE_UNIT_SIZE`=20B | :51 / :110 / `ConsumeQueue:59` |

---

## 三、完整调用链（四段）

### 3.1 写入链（同步返回前）

```
SendMessageProcessor
 └─ DefaultMessageStore.asyncPutMessage(:550)         ← 门面：状态检查、消息校验
     └─ CommitLog.asyncPutMessage(:824)
         ├─ putMessageLock.lock()(:909)               ← 自旋/可重入锁（store config 切换）
         ├─ mappedFileQueue.getLastMappedFile()       ← 取最后一个 1GB 文件
         ├─ mappedFile.appendMessage(...)(:932)       ← DefaultMappedFile.appendMessagesInner(:270)
         │    └─ DefaultAppendMessageCallback.doAppend(:1750)
         │         └─ 编码写入 mappedByteBuffer（:168 处 mmap：fileChannel.map(READ_WRITE,0,1GB)）
         └─ handleDiskFlushAndHA(:995)                ← 刷盘请求 + 主从复制请求
```

### 3.2 刷盘链（两种模式）

| 模式 | 服务 | 行为 |
|---|---|---|
| ASYNC_FLUSH（默认） | `FlushRealTimeService`(:1403) | 每 500ms 批量 `flush(flushLeastPages)`（MappedFile:364 → `mappedByteBuffer.force()`）——写返回≠落盘 |
| SYNC_FLUSH | `GroupCommitService`(:1530) | 写请求入队，等待刷盘完成（或 5s 超时）才返回——吞吐降、可靠升 |

### 3.3 分发链（异步，写路径之外）

```
ReputMessageService(:2744) 单线程循环 doReput(:2782)
  ├─ 从 reputFromOffset 扫 CommitLog 新消息
  ├─ 构造 DispatchRequest
  ├─ CommitLogDispatcherBuildConsumeQueue(:2127) → 写 20B 条目（offset+size+tagsCode）
  └─ CommitLogDispatcherBuildIndex(:2144)        → 有 KEYS 时写 IndexFile
```

**窗口**：CommitLog 已写入、ConsumeQueue 还没分发——极短（分发是内存级操作）；消费端由 `MessageArrivingListener` 在分发后唤醒长轮询请求。

### 3.4 恢复链（重启对账）

```
DefaultMessageStore.recover(lastExitOK)(:1855)
  ├─ abort 文件判断上次退出方式（isTempFileExist:1849）
  ├─ recoverConsumeQueue（先恢复索引自身）
  ├─ 取 maxPhyOffsetOfConsumeQueue 作基准
  ├─ recoverNormally（从倒数第 3 个 MappedFile 校验）/ recoverAbnormally（定位最后一条完整消息，CRC 丢弃半截）
  └─ 超出 ConsumeQueue 已分发位点的消息 → Reput 追平（天然幂等）
```

---

## 四、使用（生产口径）

### 4.1 存储配置速查

| 配置 | 默认 | 建议 |
|---|---|---|
| `flushDiskType` | ASYNC_FLUSH | 核心链路 SYNC_FLUSH（牺牲吞吐换不丢） |
| `brokerRole` | ASYNC_MASTER | 严格不丢配 SYNC_MASTER（或 DLedger，D-04） |
| `mappedFileSizeCommitLog` | 1GB | 一般不动（滚动文件） |
| `fileReservedTime` | 72h | 按堆积容忍度调；积压报警阈值必须远小于 72h |
| `deleteWhen` | "04" | 每日清理窗口 |
| 磁盘水位 | 75% 拒写 / 85% 强清 / 90% 告警 | 监控磁盘 + 提前扩容 |
| `transientStorePoolEnable` | false | 写毛刺敏感可开（堆外池，多一次拷贝） |

### 4.2 生产实践

- **可靠性组合**：`SYNC_FLUSH + autoCreateTopicEnable=false`（xhs 加固口径）
- **监控三件**：CommitLog 目录磁盘水位 / 分发滞后（`dispatchBehindBytes`）/ 文件滚动频率
- **大消息**：默认上限 4MB——大 body 走对象存储或压缩（批量消息不压缩）
- **刷盘×复制正交**：按 RPO 组合（lab D1 §3.5 的 2×2 矩阵）

### 4.3 排查入口

- "写入成功但读不到" → 看 Reput 分发位点（`dispatchBehindBytes` 是否收敛）
- "恢复慢/数据异常" → 看 abort 文件判定 + 恢复日志（recoverNormally/recoverAbnormally）

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **所有 Topic 共用 CommitLog** | 每 Topic 一个日志文件 | 顺序写 = 最快写路径；混部让磁盘 IO 最大化——把"写放大"换成"读定位"（ConsumeQueue 索引） |
| **mmap 而不是 read/write 系统调用** | FileChannel.write | mmap 把文件映射进虚拟内存，写即写 PageCache，省用户态/内核态拷贝；代价是缺页与刷盘不可控（靠 force 策略） |
| **1GB 单文件而不是无限追加** | 单文件持续增长 | 文件滚动 + 文件名=起始偏移：定位 O(1)、删除/回收按文件粒度、mmap 大小可控 |
| **索引异步分发而不是写时同步更新** | 写 CommitLog 同时写 ConsumeQueue | 写路径只做一件事（顺序写）；索引可重放（重启 Reput 追平）——性能与恢复正确性解耦 |
| **CRC 校验 + 半截消息丢弃** | 信任写入完整性 | 宕机可能写一半——magicCode+CRC 识别，恢复时丢弃，不污染读路径 |

---

## 六、生产陷阱（含复现思路）

| # | 陷阱 | 根因 | 复现/验证 | 修复 |
|---|---|---|---|---|
| 1 | **异步刷盘丢最后 500ms** | ASYNC_FLUSH 只写 PageCache | kill -9 Broker 后对比最后消息 | 核心链路 SYNC_FLUSH（或 DLedger） |
| 2 | **"写入成功"但消费读不到（短暂）** | Reput 分发滞后窗口 | 写入后立即按位点读 ConsumeQueue | 正常现象（毫秒级）；长轮询有唤醒机制 |
| 3 | **磁盘水位拒写** | 75% 拒写/85% 强清（`diskMaxUsedSpaceRatio` 等） | 灌数据到水位 | 监控磁盘 + 清理策略 |
| 4 | **恢复慢** | recoverAbnormally 逐条 CRC 校验（倒查 3 文件缓解） | 大量文件 + 异常退出 | 正常退出（abort 文件清除）走 recoverNormally |
| 5 | **大消息写放大** | 单条 >4MB 默认上限；properties 膨胀 | 发超大消息被拒 | 控制消息体；批量压缩 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："一条消息写入 CommitLog 的完整流程？"**（意图：是否读过写路径）
DefaultMessageStore.asyncPutMessage → CommitLog.asyncPutMessage（putMessageLock）→ MappedFile.appendMessage → doAppend 编码 → handleDiskFlushAndHA。**加分**：指出刷盘/复制不在 append 内、而是 handleDiskFlushAndHA 统一处理。

**Q2："为什么 CommitLog 所有 Topic 共用？"**（意图：存储设计理解）
顺序写是磁盘最快路径；混部避免多文件随机写。读时 ConsumeQueue 索引定位。**危险信号**：说"为了省空间"（错——是为了顺序 IO）。

**Q3："mmap 和 sendfile 的区别？"**（意图：零拷贝对比 Kafka）
RocketMQ 用 mmap（MappedByteBuffer，堆外映射，读写都受益）；Kafka 用 sendfile（读路径零拷贝）。**对比题**：见 P02-对比 D-01。

**Q4："写成功但消费不到，为什么？"**（意图：分发链理解）
Reput 异步分发窗口（毫秒级）+ 长轮询唤醒机制；若持续读不到才是异常（检查 reputFromOffset/位点）。

**Q5："Broker 异常宕机后怎么恢复？"**（意图：恢复链）
abort 文件判定 → recoverConsumeQueue 基准 → recoverAbnormally 定位完整消息（CRC 丢半截）→ Reput 追平分发。**加分**：只倒查 3 个 MappedFile（不全量扫）。

---

## 八、章节依赖

- **上游**：lab D1 §3.4（存储三件套概念）/ D4（刷盘/恢复概要）
- **下游**：D-05 消费端启动流程（offset 与 ConsumeQueue 的关系）；P02-对比 D-01（Kafka 分段日志对比）
- **横链**：RocketMQ K01 拷打篇（"为什么快"话术）；xhs-project（SYNC_FLUSH 加固、DLQ 治理）

---

## 九、验证锚点（汇总）

| 声明 | 位置（5.1.4） |
|---|---|
| 写入门面/校验 | `DefaultMessageStore.asyncPutMessage:550` |
| 写锁 | `CommitLog.asyncPutMessage:909`（putMessageLock） |
| append 调用 | `CommitLog.asyncPutMessage:932` → `DefaultMappedFile.appendMessagesInner:270` |
| 编码格式 | `CommitLog.DefaultAppendMessageCallback.doAppend:1750` |
| mmap 映射 | `DefaultMappedFile:168`（fileChannel.map(READ_WRITE,0,fileSize)） |
| flush 实现 | `DefaultMappedFile.flush:364` |
| 异步/同步刷盘服务 | `CommitLog.FlushRealTimeService:1403` / `GroupCommitService:1530` |
| 刷盘+HA 统一入口 | `CommitLog.handleDiskFlushAndHA:995` |
| 分发服务/两个 Dispatcher | `DefaultMessageStore.ReputMessageService:2744/doReput:2782`；Dispatcher:2127/2144 |
| 恢复链 | `DefaultMessageStore.recover:1855`；`isTempFileExist:1849`；倒查 3 文件 `CommitLog:321` |
| 文件大小配置 | `MessageStoreConfig:51`（1GB）/`:110`（CQ）；`ConsumeQueue:59`（20B） |
