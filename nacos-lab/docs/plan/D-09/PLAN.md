# D-09 详细规划: Distro V2 协议 — Nacos 自研 AP 一致性协议

> 阶段: Phase 5 — 数据一致性 | 预期产出: `md/D-09-DistroV2.md` | 状态: 待审核

---

## 一、为什么需要这篇

D-08 覆盖了单个 Nacos 节点内的 Push+健康检查+故障自愈。但 Nacos 是集群部署的，节点间需要同步数据。Distro 是 Nacos 自研的最终一致性协议，是 AP 模式下集群数据同步的基础。

这是面试注册中心开发岗位的**必考题**——理解了 Distro 就理解了 Nacos 如何在 AP 条件下保证数据最终一致。

前置依赖: D-06 (Client 模型, revision 机制), D-07 (事件驱动, ClientReleaseEvent 触发 sync)

> **与 D-06/D-07 的呼应**: D-07 的 ClientRegisterServiceEvent/ClientReleaseEvent → DistroClientDataProcessor 监听到 → 构建 DistroClientSyncData → DistroProtocol.sync() → gRPC 发送到目标节点。

---

## 二、源码阅读清单 (按调用链顺序)

| 序号 | 源码文件 | 行数 | 必须阅读的内容 | 阅读目的 |
|------|----------|------|---------------|----------|
| 1 | `core/src/main/java/.../distributed/distro/DistroProtocol.java` | ~230 | sync(103-136), onReceive(164-174), onVerify(183-195), onQuery(203-211), onSnapshot(219-226), startDistroTask(62-69) | 理解 Distro 协议核心: 5 大操作 |
| 2 | `naming/src/main/java/.../consistency/ephemeral/distro/v2/DistroClientDataProcessor.java` | ~80 | onEvent(), processData(), processVerifyData() | 理解 V2 如何监听事件触发 sync + 如何处理收到的数据 |
| 3 | `naming/src/main/java/.../consistency/ephemeral/distro/v2/DistroClientSyncData.java` | ~40 | 数据结构字段 | 理解 V2 同步什么数据(整个 Client) |
| 4 | `naming/src/main/java/.../consistency/ephemeral/distro/v2/DistroClientVerifyInfo.java` | ~60 | clientId + revision | 理解 verify 的校验信息 |
| 5 | `naming/src/main/java/.../core/DistroMapper.java` | ~140 | responsible(), onEvent(MembersChangeEvent) | 理解一致性哈希路由: Service → Server |
| 6 | `core/src/main/java/.../distributed/distro/component/DistroTransportAgent.java` | ~30 | getData(), syncData() | 理解传输层抽象: gRPC 实现 |
| 7 | `core/src/main/java/.../distributed/distro/entity/DistroData.java` | ~40 | type + content + distroKey | 理解 Distro 数据结构 |
| 8 | `core/src/main/java/.../distributed/distro/task/verify/DistroVerifyTimedTask.java` | ~60 | run() | 理解反熵校验的定时任务 |
| 9 | `core/src/main/java/.../distributed/distro/DistroConfig.java` | ~50 | getSyncDelayMillis(), getVerifyIntervalMillis() | 理解 Distro 配置参数 |

---

## 三、分析要点详解

### 3.1 Distro 协议五大操作全景

```
DistroProtocol 提供五个核心操作:

1. sync (同步) — 推送: 本地数据变更 → 推送到其他节点
2. onReceive (接收) — 拉取: 收到其他节点的同步数据 → 合并
3. onVerify (校验/反熵) — 定时: 交换 revision checksum → 不一致 → 全量 sync
4. onQuery (查询) — 按需: 新节点加入时向其他节点查询数据 → load
5. onSnapshot (快照) — 批量: 获取某个类型的所有数据 → 新节点快速加载
```

### 3.2 sync — 推送同步 (事件驱动)

```
[触发源: D-07 的 D-07 事件]
EphemeralClientOperationServiceImpl.registerInstance()
  → Client.addServiceInstance() → recalculateRevision()
  → NotifyCenter.publishEvent(ClientRegisterServiceEvent)

[监听者: DistroClientDataProcessor (SmartSubscriber)] (DistroClientDataProcessor.java:94-127)
DistroClientDataProcessor.onEvent(ClientEvent):
  // syncToAllServer 内部检查 isInvalidClient:
  // → if (!clientManager.isResponsibleClient(client)) → return  // 只有负责节点才触发 sync!
  // → if (!client.isEphemeral()) → return                     // 只同步临时实例
  → syncToAllServer(event):
    if (ClientDisconnectEvent):
      → DistroProtocol.sync(distroKey, DataOperation.DELETE)
    if (ClientChangedEvent):
      → DistroProtocol.sync(distroKey, DataOperation.CHANGE)
      → 构建 DistroClientSyncData(包含整个 Client 的完整数据 + revision)

[DistroProtocol.sync() — DistroProtocol.java:114-118]:
// ⚠️ sync 目标: ALL 其他成员(allMembersWithoutSelf), 不是只发负责节点!
// 发送端已通过 isResponsibleClient 过滤: 只有负责节点触发 sync
for (Member each : memberManager.allMembersWithoutSelf()) {
    syncToTarget(distroKey, action, each.getAddress(), delay);
}

syncToTarget() — DistroProtocol.java:128-136:
  → DistroDelayTask(distroKeyWithTarget, action, delay)
  → delayTaskExecuteEngine.addTask(distroKeyWithTarget, distroDelayTask)
  // 延迟执行(DistroDelayTask) — 类似 PushDelayTask 的合并机制

[实际执行: DistroDelayTask 到期后]:
  → DistroTransportAgent.syncData(distroData, targetServer)
  → gRPC 请求 → 目标节点的 GrpcClusterServer(9849)
    → DistroProtocol.onReceive(distroData)
```

**关键: V2 sync 的是整个 Client, 不是单个 Service**:
- V1: 每个 Service 变更 → 一次 sync → N 个 Service = N 次网络请求
- V2: 一个 Client 变更 → 一次 sync(携带该 Client 的所有 Service 数据) → M 个 Client = M 次请求
- 优势: M << N (一个 Client 通常注册多个 Service) → 大幅减少网络请求

### 3.3 onReceive — 接收合并

```
[目标节点收到 sync]:
DistroProtocol.onReceive(DistroData distroData)
  → 解析 resourceType = distroData.getDistroKey().getResourceType()
  → 查找对应的 DistroDataProcessor:
    distroComponentHolder.findDataProcessor(resourceType) → DistroClientDataProcessor
  → dataProcessor.processData(distroData)
    
    [DistroClientDataProcessor.processData()]:
      → 反序列化 DistroClientSyncData
      → 提取 clientId + revision
      → Client client = clientManager.getClient(clientId)
        if (client == null):
          → 创建新的 Client(来自其他节点的同步)
        else:
          → 比较 revision:
            if (incoming.revision > local.revision):
              → 覆盖 local Client 数据(merge: 合并 publishers + subscribers)
              → client.setRevision(incoming.revision)
            if (incoming.revision <= local.revision):
              → 跳过(本地数据更新)
      → 如果是 DELETE 操作 → clientManager.removeClient(clientId)
```

**Revision 的版本仲裁**:
- revision 基于内容计算(hash) → 不同内容 = 不同 revision
- `incoming.revision > local.revision` → 覆盖(远程更新)
- `incoming.revision <= local.revision` → 跳过(本地更新或相同)
- 这种"最后写入者胜出"(Last Write Wins) 的策略, 在 AP 系统中简化了冲突解决

### 3.4 onVerify — 反熵 (Anti-Entropy)

```
[定时任务: DistroVerifyTimedTask]
// 默认间隔: DistroConfig.verifyIntervalMillis
// 每个节点随机挑选其他节点做 verify

verify 流程:
  → 遍历本节点的所有 Client
  → 构建 DistroClientVerifyInfo(clientId + revision)
    // 只传 clientId + revision, 不传完整数据(减小传输)
  
  → DistroTransportAgent.verify(verifyInfo, targetServer)
  → 目标节点收到 → DistroProtocol.onVerify():
    → DistroClientDataProcessor.processVerifyData(verifyInfo, sourceAddress)
      → 比较 revision:
        本节点: client.getRevision()
        来源节点: verifyInfo.getRevision()
    
      → if (localRevision > remoteRevision):
          → 返回 false(本地更新, 不需要同步)
      → if (localRevision < remoteRevision):
          → 发起 query 请求:
            DistroProtocol.queryFromRemote(distroKey) → 获取完整 Client 数据
          → 然后 onReceive → processData → 覆盖
      → if (localRevision == remoteRevision):
          → 返回 true(一致, 不需要处理)
```

**反熵的价值**:
- sync 是"推送" → 依赖事件驱动, 可能丢失(事件丢失/网络故障)
- verify 是"拉取" → 定时主动检查, 发现不一致 → 自动修复
- 推+拉 = 双重保障 → 数据最终一致

### 3.5 onQuery + onSnapshot — 新节点加载数据

```
[onQuery: 按需查询]
新节点加入集群 → 需要从其他节点加载负责的 Client 数据:
  → DistroLoadDataTask (启动时执行一次)
  → 根据 DistroMapper 计算出新节点应该负责哪些 Service
  → 向对应的节点发送 query 请求
  → DistroProtocol.queryFromRemote(distroKey)
    → transportAgent.getData(distroKey, targetServer)
    → 目标节点: DistroProtocol.onQuery(distroKey)
      → distroDataStorage.getDistroData(distroKey) → 返回该 Client 的完整数据

[onSnapshot: 批量获取]
节点还可以请求某个类型的所有数据:
  → DistroProtocol.onSnapshot("naming_client")
  → distroDataStorage.getDatumSnapshot()
  → 返回所有 Client 的快照数据
```

### 3.6 DistroMapper — 一致性哈希路由

```
DistroMapper.responsible(service):
  → 基于一致性哈希: hash(service) → 落在哪个节点的哈希环上
  → 返回该节点是否为当前节点负责

发送端过滤:
  → DistroClientDataProcessor.syncToAllServer 调用前检查:
    clientManager.isResponsibleClient(client)
    → 只有负责节点才触发 sync
    → 但 sync 的目标是 ALL 成员(allMembersWithoutSelf)
    → 所有节点都存储数据, 负责节点是 sync 的触发者而非排他目的地

节点变更:
  → onEvent(MembersChangeEvent) → 重建哈希环
  → 新的哈希环 → 重新计算每个 Service 的负责节点
  → 触发数据迁移
```

### 3.7 DistroConfig — 关键参数

| 参数 | 源码出处 | 含义 |
|------|------|------|
| `syncDelayMillis` | `DistroConfig` | sync 的延迟合并时间(与 PushDelayTask 同理) |
| `verifyIntervalMillis` | `DistroConfig` | verify 反熵检查的间隔 |
| `loadDataRetryDelayMillis` | `DistroConfig` | 加载数据失败后的重试间隔 |

---

## 四、设计哲学分析

### 4.1 推(sync) + 拉(verify) 双重保障

```
sync  = 事件驱动推送(快速, 但可能在网络故障时丢失)
verify = 定时反熵拉取(慢速, 但保证最终一致)
```

- 类似于 TCP 的 ACK + 超时重传: sync=数据包推送, verify=超时检查
- 在 AP 系统中这是经典模式: 正常情况走快速路径, 异常情况走慢速补偿
- Dynamo(Amazon)的 anti-entropy + hinted handoff 也有类似设计

### 4.2 V2 的 "同步整个 Client" vs V1 的 "同步单个 Service"

- V1 问题: 一个 Client 注册 N 个 Service → N 次 Distro sync → N 次 gRPC 请求
- V2 改进: Client 数据变更 → 1 次 Distro sync(携带完整 Client) → 大幅减少网络请求
- 这与 D-06 的 Client 中心模型完美对齐: 数据存储在 Client 上, 同步也是 Client 粒度

### 4.3 DistroMapper 的一致性哈希

- 将无界 Service 集合哈希到有界 Server 集合
- 虚拟节点 → 减少数据倾斜
- 新节点加入: 只重新分配部分 Service(不是全部) → 数据迁移量可控
- 节点离开: 该节点负责的 Service 由下一个节点接管 → 哈希环自动调整

---

## 五、反面假设分析 (7 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | Distro V2 继续 V1 的 per-Service sync | 每个 Service 一次 gRPC 请求。100 个 Service = 100 次请求。网络开销 N 倍。 | V2 的 per-Client sync: 一次携带全部 Service |
| 2 | 只做 sync(push)不做 verify(拉取反熵) | 事件丢失 → 数据永远不一致。网络分区合并后无法自动修复。 | 推+拉双重保障 |
| 3 | verify 传输完整 Client 数据而不是只有 revision | 10K Client × 每个 Client 的完整数据 × 定时 verify → 巨大的网络带宽消耗 | verify 只传 clientId+revision(几十字节) |
| 4 | revision 用 int 而不是 hash | 单调递增数字需要全局协调(如 Raft log index)。AP 模式下不适合。 | 基于内容的 hash revision |
| 5 | 每个节点都触发 sync(不做 isResponsibleClient 过滤) | 一个 Client 变更 → 所有节点同时 sync → N×(N-1) 次同步请求 → 重复数据乱序覆盖。 | 只有负责节点触发 sync(isResponsibleClient 过滤), 但 sync 目标为所有成员 → 1→N 传播模式 |
| 6 | 新节点加入不做 load, 只依赖 sync | 新节点需要等所有 Client 发生变更才能逐步获取数据 → 可能数小时才能追上 | DistroLoadDataTask + onQuery → 启动时主动拉取 |
| 7 | DistroMapper 用简单取模而不是一致性哈希 | 节点加入/离开 → 几乎所有 Service 重新分配 → 全量数据迁移 → 网络风暴 | 一致性哈希 + 虚拟节点 |

---

## 六、新手最可能犯的 5 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 认为 Distro sync 是同步的(等待所有节点确认) | 同样操作等集群所有节点确认才返回 → 注册 RT 可能是秒级 | sync 是异步的(DistroDelayTask 延迟队列) | syncToTarget → DistroDelayTask 异步执行 |
| 2 | 混淆 Distro V1 和 V2 的同步粒度 | V1: per-Service。V2: per-Client。V1 已废弃。 | Nacos 2.x 全部使用 V2 | DistroClientDataProcessor 只处理 Client 粒度 |
| 3 | 不配置 verifyInterval, 依赖默认值 | 默认值可能不适合大规模集群 → 反熵频率不够 → 数据不一致时间窗口长 | 根据集群规模调整: 小集群(3节点)5s, 大集群(10+节点)30s | configurable |
| 4 | 认为 revision 能防并发冲突 | revision hash 相同 ≠ 数据完全一致。LWW 可能丢更新。 | 不依赖 revision 做强一致性; 临时实例用 AP, 持久实例用 CP | verify 失败 → 全量 sync 补偿 |
| 5 | 新节点加入后不等待 load 完成就开始处理请求 | 节点负责的数据未同步完 → 查询结果不完整 | 检查 DistroProtocol.isInitialized() → true 后才注册到集群 | isInitialized 标志 |

### 6.1 可运行的错误实验

**实验: sync 延迟导致短暂数据不一致**

```
时间线:
T0: 节点 A 上 registerInstance("user-service", "10.0.0.1", 8080)
T1: DistroProtocol.sync(distroKey, ADD_OR_UPDATE) → DistroDelayTask
T2: DistroDelayTask 延迟窗口内(如 1s)
T3: DistroDelayTask 到期 → gRPC sync → 节点 B
T4: 节点 B.onReceive → processData → Client 创建/更新

T0→T4 窗口内: 节点 B 不知道 "user-service" 新增了实例
验证方式: T1 时刻从节点 B 查询 → 实例列表不变
```

---

## 七、面试 Q&A (5 题)

### Q1: Distro 协议的工作流程？sync/verify/snapshot/query 分别起什么作用？

**面试官意图**: 考察对 Distro 四大操作的理解

**参考答案要点**:
- sync(push): 本地变更异步推送到负责节点 → DistroDelayTask 延迟合并
- verify(pull): 定时交换 revision → 不一致 → query 拉取完整数据
- snapshot: 节点启动时批量获取所有数据
- query: 按需要向特定节点查询特定数据
- 推+拉 = 双重保障最终一致性

### Q2: Distro V2 相比 V1 的核心改进？为什么要把 Instance 存在 Client 上？

**面试官意图**: 考察对 V1→V2 演进的理解(联动 D-06)

**参考答案要点**:
- V1: per-Service sync → N 次网络请求
- V2: per-Client sync → 一次携带全部 Service → 大幅减少请求
- 与 D-06 的 Client 中心模型紧密耦合: 数据在 Client → 同步也是 Client 粒度
- 这是架构决策的一致性体现

### Q3: 一致性哈希(DistroMapper)在 Distro 中的作用是什么？

**面试官意图**: 考察对分布式路由的理解

**参考答案要点**:
- 无界 Service → 哈希 → 有界 Server
- 虚拟节点 → 减少数据倾斜
- 新节点加入: 只重新分配部分 Service → 数据迁移量小
- 节点离开: 下一个节点接管 → 哈希环自动调整

### Q4: revision 校验机制能否防止"脑裂后数据不一致"？

**面试官意图**: 考察对 AP 系统局限性的理解

**参考答案要点**:
- 不能完全防止。revision 是 hash 不是 timestamp → hash 相同 ≠ 数据一致
- 脑裂时两个分区同时修改同一个 Client → LWW → 最后一次写入覆盖
- verify 能在分区合并后检测不一致并修复(revision 可能不同)
- 真正的防脑裂需要 CP 协议(如 Raft)

### Q5: 如果 Nacos 集群中一个节点挂了又起来，Distro 如何恢复数据？（联动 D-08）

**面试官意图**: 考察对故障恢复的理解

**参考答案要点**:
- 新节点启动 → DistroLoadDataTask → 向其他节点 query/snapshot 数据
- isInitialized=false → 不对外提供服务 → 等 load 完成
- load 过程中: 其他节点可能还在 sync 新数据 → load 完成后通过 verify 反熵再补偿
- 因此: 新节点从启动到数据完整 = loadTime + 至少一个 verifyInterval

---

## 八、产出文档结构

```
D-09-DistroV2.md

1. 概述: Distro 在 Nacos 中的角色(AP 一致性)
2. Distro 五大操作
   2.1 sync — 事件驱动推送 + DistroDelayTask
   2.2 onReceive — 接收合并 + revision 仲裁
   2.3 onVerify — 定时反熵
   2.4 onQuery — 按需查询
   2.5 onSnapshot — 批量快照
3. Distro V2 的核心变革
   3.1 per-Service → per-Client
   3.2 ClientSyncData 数据结构
4. DistroMapper — 一致性哈希路由
5. DistroClientDataProcessor — V2 事件消费者
6. DistroConfig 关键参数
7. 设计哲学分析
   7.1 推+拉双重保障
   7.2 V2 粒度优化
   7.3 一致性哈希设计
8. 反面假设分析(7 组)
9. 新手最可能犯的 5 个错误 + 可运行错误实验
10. 面试 Q&A(5 题)
```

---

## 九、与后续文档的依赖关系

```
D-09 (Distro V2 协议)  ← 当前
  |--- Distro 是 AP 实现 → D-10 CP/AP 双模式路由
  |--- Distro sync 是 AP 数据同步 → D-10 JRaft 是 CP 同步
  ↓
D-10 (CP/AP 双模式路由 + JRaft 整合) ← AP vs CP 的完整对比
```
