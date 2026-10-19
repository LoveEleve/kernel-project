# P02-Kafka D-02 — 消费者群组与 Rebalance

> **定位**：Kafka 深挖系列第 2 篇。三层齐：**原理（心智模型）+ 使用（配置/实践）+ 源码（核心调用链）**。
> **源码版本**：apache/kafka **4.0.0**（`code/spring/kafka`；经典协议默认，新协议 KIP-848 可切换）
> **质量标准**：源码阅读清单 / 完整调用链 / 设计哲学（反面假设）/ 生产陷阱 / 面试 Q&A（意图）/ 章节依赖

---

## 一、它干了什么：一个例子

消费组 `order-group`，3 个实例（C1/C2/C3）订阅同一 topic（6 分区）——**每个分区同一时刻只属于组内一个成员**：

```
稳定态： C1:[p0,p1]  C2:[p2,p3]  C3:[p4,p5]

C2 宕机（进程崩溃，不再发心跳）：
  t+45s  Broker 协调器判定 C2 死亡（session.timeout.ms）
  → 触发 Rebalance：C1/C3 重新入组（JoinGroup）→ 协调器选一个 leader
  → leader 按分配器重算：C1:[p0,p1,p2]  C3:[p3,p4,p5]
  → 各成员收到新分配（SyncGroup）→ 从各自已提交位移继续消费
```

**一句话**：Rebalance = **组内成员变化时，把分区"重新分家"**——协调器（Broker 端 GroupCoordinator）管成员与选 leader，分配算法在客户端 leader（经典协议）或服务端（KIP-848）。

---

## 二、原理（心智模型，不贴源码）

### 2.1 组模型与三个关键 ID

| 概念 | 含义 |
|---|---|
| `group.id` | 消费组身份（位移/成员/订阅都挂它） |
| `memberId` | 成员身份（入组时协调器分配） |
| `generation` | 组"朝代"——每次 rebalance +1，**旧 generation 的位移提交被拒**（fence） |

### 2.2 Rebalance 的触发条件

```
成员加入 / 成员离开（崩溃或主动） / 订阅变化（正则匹配到新 topic）
分区数变化（topic 扩分区） / 心跳超时（session.timeout.ms）
```

### 2.3 两种分配协议（经典协议内部）

| | Eager（Range/Sticky） | Cooperative（CooperativeSticky） |
|---|---|---|
| 行为 | **全量 revoke**：所有人先放弃全部分区 → 再重新分配 | 只 revoke 需要移动的分区 |
| 代价 | stop-the-world（大组抖动明显） | 可能两次 rebalance 才收敛 |
| **默认生效** | **默认列表 [Range, CooperativeSticky] 经"投票"后 = Range（eager）** | 需把策略只配为 CooperativeSticky 才走增量 |

（服务端选协议机制：每个成员投自己列表里第一个"所有成员都支持"的——默认全员第一偏好是 Range，锚：`GroupMetadata.selectProtocol:431` / `MemberMetadata.vote:134`）

### 2.4 心跳 vs 处理超时（面试必问）

```
心跳线程（独立）：heartbeat.interval.ms=3s 发心跳
  协调器判定死亡：session.timeout.ms=45s 内没心跳 → 踢出
消费处理超时：max.poll.interval.ms=300s 内没 poll() → 主动离组（防止"卡死的消费者"占分区）
```

**两者是不同维度**：session 管"进程活着吗"，max.poll 管"处理跟得上吗"。

### 2.5 位移提交与 fence

- 位移提交到 `__consumer_offsets`（协调器管理）
- 提交带 generation：**旧 generation 提交 → `ILLEGAL_GENERATION` 被拒**（防止已离组成员覆盖新成员进度）
- 自动提交（`enable.auto.commit=true`，5s）在 poll 时触发——rebalance 窗口内可能重复消费

### 2.6 新协议 KIP-848（4.0 可切换）

```
经典：客户端 leader 跑分配器（Range/Sticky...）→ 协议与分配器绑定，升级难
新协议：服务端（GroupCoordinatorService）直接算分配并推给成员
  → 客户端不再跑 rebalance 协议（分配器可服务端插拔）
  → 切换：group.protocol=consumer（默认 classic）
```

---

## 三、使用（生产口径）

### 3.1 配置速查（4.0 默认值）

| 配置 | 默认 | 说明/建议 |
|---|---|---|
| `group.protocol` | **classic** | 新协议选 `consumer`（同组必须一致） |
| `session.timeout.ms` | 45000 | 调小更快发现宕机，但误判风险↑ |
| `heartbeat.interval.ms` | 3000 | 常规保持 session 的 1/3 以下 |
| `max.poll.interval.ms` | 300000 | **按单批最长处理时间调大**（风暴主因） |
| `max.poll.records` | 500 | 调小可降低单次处理时长 |
| `partition.assignment.strategy` | [Range, CooperativeSticky] | 增量分配用 CooperativeSticky |
| `group.instance.id` | 无 | 静态成员（session 超时内重启不触发 rebalance） |
| `enable.auto.commit` | true | 精确控制改手动提交 |

### 3.2 Rebalance 风暴治理清单

```
1. 处理慢被踢？ → 调大 max.poll.interval.ms / 调小 max.poll.records / 异步化处理
2. 滚动重启抖动？ → group.instance.id 静态成员（重启保留成员身份与位点）
3. 大组全量抖动？ → 策略只配 CooperativeStickyAssignor（去掉 Range，走增量 revoke）
4. 心跳误判？ → 确认 heartbeat.interval << session.timeout
5. 长期方案 → 新协议 KIP-848（服务端分配，4.0 可用）
```

### 3.3 位移提交与再平衡钩子

```java
props.put("enable.auto.commit", "false");
consumer.subscribe(List.of("orders"), new ConsumerRebalanceListener() {
    public void onPartitionsRevoked(Collection<TopicPartition> parts) {
        consumer.commitSync();          // 再平衡前提交（减少重复）
    }
    public void onPartitionsAssigned(Collection<TopicPartition> parts) { /* 初始化状态 */ }
});
// 处理完成后手动 commitSync/commitAsync
```

### 3.4 排查入口

- 频繁 rebalance：日志 `Revoking previously assigned partitions` / `Rebalance failed` / `Member ... has left group`
- 消费停滞：看 `max.poll.interval` 超时日志（"was removed from the group"）
- 重复消费：检查自动提交窗口 + rebalance 时是否先提交

---

## 四、源码核心（调用链 + 行号）

### 4.1 经典协议（默认，`group.protocol=classic`）

```
KafkaConsumer(:532) → ConsumerDelegateCreator（按协议选委托）
  → ClassicKafkaConsumer.poll
       └─ ConsumerCoordinator.poll(:470)
            └─ ensureActiveGroup(:511)（需要入组/再平衡时）
                 └─ AbstractCoordinator：sendJoinGroupRequest(:600) → 等待 JoinGroup 响应
                      （leader 成员拿到全组成员元数据 → 客户端分配器算分配）
                      → sendSyncGroupRequest(:800) → 拿回自己的分区
心跳线程：AbstractCoordinator.HeartbeatThread(:1439) → run(:1480) → 周期发心跳

服务端（core/Scala）：
  GroupCoordinator.handleJoinGroup(:163)（成员管理/选 leader/超时等待）
  GroupCoordinator.handleSyncGroup(:511)（下发分配）
  GroupCoordinator.handleHeartbeat(:843)（存活判定/触发 rebalance）
  元数据：GroupMetadataManager.scala / GroupMetadata.scala / MemberMetadata.scala
```

### 4.2 新协议（KIP-848，`group.protocol=consumer`）

```
客户端：AsyncKafkaConsumer（ConsumerDelegateCreator:63 选择）
  → ConsumerHeartbeatRequestManager（心跳/入组请求管理器，无客户端分配器）
服务端：group-coordinator 模块（Java）
  GroupCoordinatorService(:106) → joinGroup(:382) / syncGroup(:436)
  GroupMetadataManager.java（新协议组元数据）
  → 服务端直接计算并推送分配
```

### 4.3 关键差异一句话

**经典 = 客户端 leader 分配 + 两阶段 Join/Sync；新协议 = 服务端分配**（客户端只上报订阅与心跳）。

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **协调器在 Broker** | 客户端互相协调（Gossip） | 成员存活判定必须集中可信；客户端崩溃/网络分区不可信 |
| **generation fence** | 只靠 memberId | 防止旧成员在 rebalance 后仍提交位移（脑裂式覆盖） |
| **Eager 全量 revoke** | 直接增量（首版） | 实现简单、分配器无状态；代价是 stop-the-world（后来 Cooperative 补） |
| **心跳独立线程** | 与 poll 同线程 | 处理慢不应导致"死亡"误判（心跳/处理职责分离） |
| **KIP-848 服务端分配** | 继续客户端分配器 | 客户端分配器是升级最大阻力（协议与插件绑定）；服务端统一更可控 |
| **max.poll.interval 主动离组** | 只看心跳 | 防"活着的僵尸"占分区不消费（消费进度停滞是更常见故障） |

---

## 六、生产陷阱（含复现思路）

| # | 陷阱 | 根因 | 修复 |
|---|---|---|---|
| 1 | **Rebalance 风暴** | 单批处理 > max.poll.interval（5min）被踢 → 循环重入组 | 调大 interval / 调小 max.poll.records / 处理异步化 |
| 2 | **心跳误判死亡** | heartbeat 间隔 ≥ session.timeout（网络抖动放大） | heartbeat << session（如 3s/45s 默认） |
| 3 | **全量抖动** | Eager 协议大组 rebalance（每个成员都暂停） | CooperativeStickyAssignor（增量） |
| 4 | **重启即 rebalance** | 无静态成员（滚动发布连续触发） | `group.instance.id`（静态成员；注意缩容需等待 session 过期） |
| 5 | **协议混用** | 同组部分实例 classic、部分 consumer | 全组统一（否则组无法收敛） |
| 6 | **重复消费窗口** | 自动提交 + rebalance 时未提交 | 手动提交 + `onPartitionsRevoked` 里 commitSync |

---

## 七、面试 Q&A（含面试官意图）

**Q1："Rebalance 的过程？"**（意图：协议机制）
触发 → 成员 JoinGroup（协调器选 leader）→ leader 算分配 → SyncGroup 下发 → 各成员从提交位移继续。经典协议是客户端分配，新协议服务端分配。

**Q2："怎么减少 Rebalance 影响？"**（意图：生产治理）
调参（max.poll.interval/max.poll.records）、Cooperative 增量分配、静态成员（滚动重启场景）、新协议 KIP-848；根治是让处理时间稳定小于 max.poll.interval。

**Q3："session.timeout 和 max.poll.interval 区别？"**（意图：概念边界）
前者是"进程活着吗"（心跳维度），后者是"处理跟得上吗"（poll 维度）——被踢的原因与日志不同。

**Q4："Rebalance 时会丢消息/重复吗？"**（意图：位移语义）
不丢（新成员从已提交位移继续）；可能重复（已消费未提交的部分重放）——消费端幂等兜底。

**Q5："KIP-848 改了什么？"**（意图：版本演进）
分配从客户端 leader 移到服务端；客户端不再运行 rebalance 协议/分配器；4.0 默认仍 classic，`group.protocol=consumer` 切换。

---

## 八、章节依赖

- **上游**：D-01（生产者全链路——位移提交走 `sendOffsetsToTransaction` 时与事务联动）
- **下游**：D-03 日志存储与 ISR 复制（协调器状态也在副本间复制）；D-04 事务与 Exactly-Once（消费位移进事务）
- **横链**：P02-RocketMQ D-05（消费端启动流程——RocketMQ 的"队列负载"对比 Kafka 分区分配）；K01 拷打篇

---

## 九、验证锚点（Kafka 4.0.0）

| 声明 | 位置 |
|---|---|
| KafkaConsumer 委托结构 | `clients/.../consumer/KafkaConsumer.java:532/534/536` |
| 委托选择（classic/新协议） | `clients/.../internals/ConsumerDelegateCreator.java:42-65` |
| ConsumerCoordinator.poll / ensureActiveGroup | `clients/.../internals/ConsumerCoordinator.java:470/511` |
| JoinGroup/SyncGroup 请求 | `clients/.../internals/AbstractCoordinator.java:600/800` |
| 心跳线程 | `clients/.../internals/AbstractCoordinator.java:1439/1480` |
| 服务端经典协调器 | `core/.../coordinator/group/GroupCoordinator.scala:163/511/843` |
| 新协议服务端 | `group-coordinator/.../GroupCoordinatorService.java:106/382/436` |
| 新协议元数据 | `group-coordinator/.../GroupMetadataManager.java` |
| group.protocol 默认 classic | `ConsumerConfig:114/643-648` |
| session.timeout=45s / heartbeat=3s | `ConsumerConfig:422-429` |
| max.poll.records=500 | `ConsumerConfig:93/607-609` |
| max.poll.interval=300s | `ConsumerConfig:613-615` |
| 默认分配策略 [Range, CooperativeSticky] | `ConsumerConfig:432-434` |
