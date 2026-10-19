# D4 — Broker 存储机制

> **定位**：D3 消息已发到 Broker，本篇回答"Broker 把它写到哪、怎么保证不丢、消费时怎么读回来"。
> **深度**：架构原理级——三类文件结构 + 写入/分发/刷盘/复制/清理五条链路。
> **源码依据**：rocketmq-all-5.1.4（store 模块；`code/spring/rocketmq-5.1.4`，与运行时对齐），所有数字经 grep 验证，见文末验证锚点。

---

## 一、写入链路全景

```
SendMessageProcessor (broker 收包)
   ▼
DefaultMessageStore.putMessage
   ▼
CommitLog.appendMessage ──▶ MappedFileQueue 尾部 MappedFile (mmap 写入 PageCache)
   │                         默认 1GB/文件，文件名=起始物理偏移(20位补零)
   ▼
┌── 刷盘（独立线程）─────────────────────────────┐
│ ASYNC_FLUSH(默认): FlushRealTimeService 每500ms  │
│ SYNC_FLUSH:        GroupCommitService 等待落盘,    │
│                    超时 syncFlushTimeout=5s        │
└────────────────────────────────────────────────┘
   ▼ (异步, 与刷盘并行)
ReputMessageService 单线程扫描新写入
   ▼  dispatcherList 逐个分发:
   ├─ CommitLogDispatcherBuildConsumeQueue → 写 ConsumeQueue 索引
   ├─ CommitLogDispatcherBuildIndex        → 写 IndexFile (有 KEYS 时)
   └─ CommitLogDispatcherCompaction        → 5.x Compaction(默认关)
   ▼ (主从场景并行)
DefaultHAService ──▶ Slave DefaultHAClient 拉取同步
   SYNC_MASTER 时: GroupTransferService 等 Slave 确认后才返回发送成功
```

**设计精髓**：写路径只有一次顺序 IO（CommitLog 追加），索引分发、刷盘、复制全部异步旁路——这是 RocketMQ 单机十万级 TPS 的根基。

---

## 二、三类文件的结构

存储根目录默认 `~/store`（`storePathRootDir`）：

| 文件 | 单文件大小 | 条目结构 | 角色 |
|---|---|---|---|
| **CommitLog** | **1GB**（`mappedFileSizeCommitLog`） | 变长：消息头+properties+body | 消息**本体**，全 Topic 混部顺序追加 |
| **ConsumeQueue** | 300,000×20B≈**6MB**（`mappedFileSizeConsumeQueue`） | **定长 20B**（`CQ_STORE_UNIT_SIZE`）：8B CommitLog 偏移 + 4B 大小 + 8B tagsCode | 每条 Queue 的**消费索引**（目录：`consumequeue/{topic}/{queueId}/`） |
| **IndexFile** | 500万 hash 槽 + 2000万条目（`maxHashSlotNum`/`maxIndexNum`） | Key hash → 链表挂条目（时间倒序） | 按 **Key 反查**消息 |

**为什么 ConsumeQueue 用定长条目**：定长 = offset 即数组下标，`第 N 条 = N×20B` 直接定位，无需任何查找结构。消费进度（offset）就是 ConsumeQueue 里的条目序号。

**MappedFile**：三类文件都通过 mmap 映射——写先进 PageCache，由刷盘策略决定何时 fsync；读优先命中 PageCache，命中不了才缺页读盘（冷数据读取是 Broker 毛刺的主要来源）。

#### CommitLog 单条消息的二进制格式（`MessageExtEncoder.java:126-158`）

按序写入：总长度（4B) → magicCode(4B) → bodyCRC(4B) → queueId(4B) → flag(4B) → queueOffset(8B) → physicOffset(8B) → sysFlag(4B) → bornTimestamp(8B) → bornHost(8B) → storeTimestamp(8B) → storeHost(8B) → reconsumeTimes(4B) → preparedTransactionOffset(8B) → body → properties。

**两个偏移字段别混淆**：`physicOffset` 是消息在 CommitLog 的字节位置（offsetMsgId 里编码的就是它）；`queueOffset` 是消息在所属 ConsumeQueue 的逻辑序号（消费进度记的是它）。CRC 校验在恢复时用于识别半条消息（宕机写一半）。

**写缓冲优化（默认关）**：`transientStorePoolEnable=false`（`MessageStoreConfig.java:232`）——开启后先写堆外 DirectBuffer 池再异步刷 PageCache，削 mmap 写毛刺；代价是多一次拷贝，一般不开。

---

## 三、异步分发（ReputMessageService）

`ReputMessageService`（`DefaultMessageStore.java:2744`）是**单线程**常驻服务：从上次分发位点开始，把 CommitLog 新写入的消息逐条转成 `DispatchRequest`，交给 dispatcher 链（`:258-264`）：

1. **BuildConsumeQueue**：算 tagsCode（Tag 的 hashCode，供 D1 说的 Broker 端粗筛），写 20B 索引条目
2. **BuildIndex**：消息有 `KEYS` 属性才建索引
3. **Compaction**（5.x，默认关）：KV 压缩，本 lab 不涉及

分发是异步的 → **存在极短窗口：CommitLog 有、ConsumeQueue 还没有**。挂起的长轮询请求由 `MessageArrivingListener` 在分发后唤醒，所以新消息几乎无感；但 Broker 异常宕机重启后需按 ConsumeQueue 最大位点 vs CommitLog 做**对账恢复**（正常关机等态另说）。

---

## 四、刷盘双模式

| | SYNC_FLUSH | ASYNC_FLUSH（默认） |
|---|---|---|
| 实现 | `GroupCommitService`：提交请求入队，等落盘完成/超时 5s（`syncFlushTimeout`） | `FlushRealTimeService`：每 **500ms**（`flushIntervalCommitLog`）批量刷 |
| 宕机丢数据 | 不丢（返回成功=已落盘） | **最多丢 500ms 窗口**（PageCache 里未落盘部分） |
| 吞吐 | 低（每次等磁盘） | 高 |

ConsumeQueue/IndexFile 的刷盘**独立**且更宽松（`flushIntervalConsumeQueue=1000ms`）——索引丢了可以从 CommitLog 重建，本体不能丢。

## 五、主从复制（HA）

```
Slave 启动 → DefaultHAClient 主动连 Master haService 端口
   ▼
Master DefaultHAService 接受连接，按 Slave 上报的 maxOffset 推送增量 CommitLog
   ▼
SYNC_MASTER: GroupTransferService —— putMessage 等"主落盘 + Slave 确认"双完成才返回
ASYNC_MASTER(默认): 主写完即返回，复制完全异步
```

与刷盘正交组合（D1 §3.5 的 2×2 矩阵）。**默认 ASYNC_FLUSH + ASYNC_MASTER = 性能最高，Master 宕机可能丢最近未同步的消息**；金融级 SYNC_FLUSH + SYNC_MASTER。5.x Controller 模式（`enableControllerMode`）下由 Controller 协调选主+epoch fencing，否则 Slave 不会自动升主（需人工运维）。

---

## 六、读路径（消费时怎么取出来）

```
Consumer 拉取请求 (topic, queueId, consumeOffset)
   ▼
ConsumeQueue 定位: 第 consumeOffset 条 → 20B 条目 {commitLogOffset, size, tagsCode}
   ▼ (tagsCode 不匹配 → 跳过，Broker 端 hash 粗筛)
CommitLog 按 commitLogOffset 读 size 字节 → 完整消息
   ▼
PageCache 命中 → 直接返回;  未命中 → 缺页中断读盘（冷读毛刺）
```

**两次随机读变顺序读的技巧**：ConsumeQueue 条目小且连续，热数据的两次定位基本都命中 PageCache。

---

## 七、启动恢复（宕机后怎么重建一致性）

Broker 启动时的恢复流程（`DefaultMessageStore.java:1855-1870`）：

```
① 判断上次退出方式: abort 文件存在 → 异常退出（isTempFileExist, :1880）
   （正常退出会删除 abort 文件；异常宕机残留）
② recoverConsumeQueue — 先恢复 ConsumeQueue 自身
③ 取 maxPhyOffsetOfConsumeQueue（索引已分发到的物理位点）
④ 恢复 CommitLog:
   正常退出 → recoverNormally：从倒数第 3 个 MappedFile 开始校验
   异常退出 → recoverAbnormally：定位最后一条完整消息（CRC 校验），
             其后的半截写入直接丢弃
⑤ 超出 ConsumeQueue 已分发位点的消息 → 重新分发（Reput 追平）
⑥ recoverTopicQueueTable — 恢复消费 offset 表
```

**要点**：① 恢复以 **ConsumeQueue 已确认分发的位点**为基准对账——索引比本体慢，本体多出来的部分重新分发即可，天然幂等；② 异常退出的半截消息靠 magicCode+CRC 识别丢弃，不会污染读路径；③ 只倒查 3 个文件（`CommitLog.java:321-331`），不全量扫——恢复速度可控。

## 八、文件清理

消息**不是因为被消费而删除**——CommitLog 是追加日志，删除只由过期驱动：

| 参数 | 默认 | 含义 |
|---|---|---|
| `fileReservedTime` | **72h** | 文件保留 72 小时 |
| `deleteWhen` | **"04"**（凌晨 4 点） | 每日触发删除的时间窗 |
| `cleanResourceInterval` | 10s | 清理扫描间隔 |

**推论**：消息"有效期"最长 72h——消费积压超过 72h 的消息会被物理删除，消费者永远读不到（堆积报警必须远早于此阈值）。磁盘水位三档兜底（`MessageStoreConfig.java:132,134,157`）：使用率 >75%（`diskMaxUsedSpaceRatio`）拒写 → >85%（`diskSpaceCleanForciblyRatio`）**强制提前清理，不等 72h** → >90%（`diskSpaceWarningLevelRatio`）告警。

---

## 九、消息不丢分析（逐环节推演）

| 环节 | 默认配置下会不会丢 |
|---|---|
| Producer → Broker | 同步发送 + SEND_OK 才认为成功；超时状态需业务决策（D3） |
| Broker PageCache → 磁盘 | **会**：异步刷盘最多丢 500ms |
| Master → Slave | **会**：异步复制，Master 宕机丢未同步部分 |
| ConsumeQueue 索引滞后 | 不丢本体，重启对账重建索引 |
| 消费后 | 与删除无关；但**积压超 72h 被过期删** |
| Broker 优雅重启 | 不丢（关机先摘流量再刷盘） |

要"严格不丢"：SYNC_FLUSH + SYNC_MASTER + 发送等 SEND_OK + 消费幂等 + 72h 内消费完——每一环都有代价。

---

## 十、本篇要点（面试视角）

1. **三类文件分工**：CommitLog 本体（1GB 顺序追加）/ ConsumeQueue 20B 定长索引 / IndexFile Key 反查（500 万槽+2000 万条目）
2. **写路径一次顺序 IO**，分发/刷盘/复制全异步旁路——高吞吐根基
3. **默认丢数据窗口 = 500ms 刷盘间隔 + 主从异步复制**；要零丢失就牺牲吞吐
4. **offset = ConsumeQueue 条目序号**，不是 CommitLog 物理偏移（后者在 20B 条目的前 8B）
5. **消息最长活 72h**——与消费与否无关，积压超期被物理删除
6. **消费读 = ConsumeQueue 定位 + CommitLog 取体**，两次都靠 PageCache 命中保性能

---

## 十一、验证锚点

| 声明 | 源码位置 |
|---|---|
| CommitLog 1GB / ConsumeQueue 300000×20B | `store/.../config/MessageStoreConfig.java:51,110` |
| CQ_STORE_UNIT_SIZE=20 | `store/.../ConsumeQueue.java:59` |
| dispatcherList 三个分发器 | `store/.../DefaultMessageStore.java:258-264` |
| ReputMessageService 单线程分发 | `DefaultMessageStore.java:2744` |
| FlushRealTimeService / GroupCommitService | `store/.../CommitLog.java:1403,1530` |
| flushIntervalCommitLog=500ms / ConsumeQueue=1000ms / syncFlushTimeout=5s | `MessageStoreConfig.java:123,145,215` |
| storePathRootDir=~/store | `MessageStoreConfig.java:32` |
| HA 组件（DefaultHAService/DefaultHAClient/GroupTransferService） | `store/.../ha/` 目录 |
| IndexFile 500万槽+2000万条目 | `MessageStoreConfig.java:194-195` |
| fileReservedTime=72h / deleteWhen="04" / cleanResourceInterval=10s | `MessageStoreConfig.java:160,156,147` |
| 磁盘水位 75%拒写/85%强清/90%告警 | `MessageStoreConfig.java:157,134,132` |
| consumequeue 目录（StorePathConfigHelper） | `store/.../config/StorePathConfigHelper.java:24` |
| 消息二进制格式（magicCode/CRC/双偏移） | `store/.../MessageExtEncoder.java:126-158` |
| abort 文件判退出方式 + 正常/异常恢复 | `store/.../DefaultMessageStore.java:1849,1855-1870` |
| 正常恢复倒查 3 个 MappedFile | `store/.../CommitLog.java:321-331` |
| transientStorePoolEnable=false | `store/.../config/MessageStoreConfig.java:232` |
