# D-12 详细规划: AsyncNotifyService + 集群配置同步

> 阶段: Phase 6 — 配置中心 | 预期产出: `md/D-12-ConfigSync.md` | 状态: 待审核

---

## 一、为什么需要这篇

D-11 覆盖了单节点内的配置发布/查询/长轮询。但 Nacos 配置中心也是集群部署的——一个节点发布了配置,其他节点如何感知？AsyncNotifyService 负责跨节点通知, ConfigClusterRpcClientProxy 负责实际的 RPC 通信。

前置依赖: D-11 (配置发布/查询/存储 + LongPolling)

---

## 二、源码阅读清单

| 序号 | 源码文件 | 必须阅读的内容 | 阅读目的 |
|------|----------|---------------|----------|
| 1 | `config/.../service/notify/AsyncNotifyService.java` | onEvent(), notifyConfigInfoChange | 理解集群通知机制 |
| 2 | `config/.../remote/ConfigClusterRpcClientProxy.java` | syncConfigChange() | 理解集群间 RPC 调用 |
| 3 | `config/.../remote/RpcConfigChangeNotifier.java` | configDataChanged() | 理解接收侧的处理 |
| 4 | `config/.../model/event/ConfigDataChangeEvent.java` | 字段定义 | 理解配置变更事件 |

---

## 三、分析要点详解

### 3.1 集群配置同步的完整链路

```
[节点 A 发布配置](D-11):
ConfigPublishRequestHandler.handle()
  → ConfigInfoPersistService.insertOrUpdate()   // 本地持久化
  → ConfigChangePublisher.notifyConfigChange(ConfigDataChangeEvent)
    → NotifyCenter.publishEvent(event)
    
    [事件消费者1: LongPollingService]           // 本地长轮询通知(D-11)
    → 唤醒本地等待的客户端
    
    [事件消费者2: AsyncNotifyService]           // 集群通知!
    AsyncNotifyService.onEvent(ConfigDataChangeEvent):  (AsyncNotifyService.java:93)
      → 获取集群所有成员: memberManager.allMembers()    (line 104)
      → 遍历成员(排除自己):
        → 构建 RPC 请求(ConfigChangeClusterSyncRequest):
          → dataId, group, tenant, lastModifiedTs
        → configClusterRpcClientProxy.syncConfigChange(request, member)
          → gRPC → 目标节点的 ConfigChangeClusterSyncRequestHandler
          
[节点 B 收到通知]:
ConfigChangeClusterSyncRequestHandler.handle():
  → 反序列化 ConfigChangeClusterSyncRequest
  → DumpService.dump(dataId, group, tenant, lastModifiedTs)  // 从 DB 加载最新配置
  → LongPollingService 唤醒本地等待该配置的客户端
  
  → 如果是新配置(节点 B 没有此配置):
    → 先存储到本地 DB(集群同步写)
    → 再 dump
```

### 3.2 AsyncNotifyService — 集群通知的核心

```java
// AsyncNotifyService 构造函数 (AsyncNotifyService.java:82-90):
// 1. 注册 ConfigDataChangeEvent 到 NotifyCenter 的 Publisher
// 2. 注册 Subscriber 监听 ConfigDataChangeEvent

onEvent(ConfigDataChangeEvent):
  → 提取事件信息: dataId, group, tenant, tag, lastModifiedTs
  → 获取健康成员列表: memberManager.allMembers()
  → 遍历每个健康成员(排除自己):
    → 异步发送 RPC 通知: asyncHttpClient 或 gRPC (2.x)
```

**重试机制** (AsyncNotifyService.java:61-65, 346-353):
- `MIN_RETRY_INTERVAL = 500ms` — 基础间隔
- `INCREASE_STEPS = 1000ms` — 增量步长
- `MAX_COUNT = 6` — 最多重试 6 次
- **计算公式**: `delay = MIN_RETRY_INTERVAL + failCount² * INCREASE_STEPS` (二次方退避!)
- 实际间隔: 1st=1500ms, 2nd=4500ms, 3rd=9500ms, 4th=16500ms, 5th=25500ms, 6th=36500ms
- 总最大时间: ~88s (不是线性的 ~21s)

### 3.3 集群同步 vs Naming Distro — 两种模式的对比

| 维度 | Naming (Distro, D-09) | Config (AsyncNotify) |
|------|----------------------|---------------------|
| 同步内容 | Client 完整数据(instances + subscribers) | 通知(仅 dataId+group+ts) |
| 同步方式 | Push 数据 → 目标节点存储 | 通知目标节点 → 目标节点自己从 DB 加载 |
| 延迟 | 微秒级(直接内存合并) | 取决于 DB 加载 + 网络 |
| 一致性 | AP(最终一致) | AP(通知驱动拉取) |
| 失败处理 | verify 反熵补偿 | 重试(最多6次,递增间隔) |
| 原因 | 实例数据是内存级 | 配置数据有 DB 作为权威源 |

### 3.4 为什么 Config 用"通知+拉取"而不是 Push 数据？

- Config 的数据源是 DB → 通知目标节点"变了", 目标节点自己从 DB 读取
- 如果 Push 数据 → 集群间传输完整配置内容 → 带宽浪费
- Config 变更频率低 → "通知" 的可靠性要求低于 Naming
- DB 作为共享的权威数据源 → 不需要像 Naming 那样维护内存数据的一致性

---

## 四、设计哲学分析

### 4.1 DB 作为共享权威源的架构优势

```
节点 A 发布配置 → 写入 DB → 通知节点 B
节点 B 收到通知 → 从 DB 读取最新配置 → 更新内存 dump
```

- DB 是"真理来源" → 不需要内存数据在节点间同步
- 即使通知丢失(AsyncNotifyService 重试6次都失败), 节点 B 可以通过定时 DumpService 扫描发现差异
- 相比之下, Naming 的临时实例数据在内存 → 必须通过 Distro sync 保证不丢

### 4.2 渐进式重试 — 不阻塞发布流程

- 重试间隔(二次方退避): 1500ms → 4500ms → 9500ms → 16500ms → 25500ms → 36500ms
- 最多 6 次 → 总尝试时间约 88s
- 失败后不阻塞发布流程 → 发布已经成功(DB 已写入)
- 这是"尽力而为"的通知 → 目标节点可能延迟收到, 但最终会通过 DB 扫描感知

---

## 五、反面假设分析 (5 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | Config 用 Push 数据同步(像 Naming Distro) | 集群间传输完整配置 → 带宽浪费。配置变更低频,但单条可能很大。 | 通知+拉取: 目标节点从 DB 自取 |
| 2 | AsyncNotifyService 不做重试 | 一次网络故障 → 配置不一致 → 目标节点下次 DumpService 扫描才感知 | 最多 6 次重试 + 递增间隔 |
| 3 | 通知成功后才返回 publish 成功 | 集群所有节点确认 → 发布延迟 → AP 不适用 | 异步通知: publish 先成功, 通知后台进行 |
| 4 | 所有节点共用同一个 DB(不各自维护本地 DB) | 单点故障 → 配置不可用。所有节点的 DumpService 竞争同一个 DB。 | 各节点独立 Derby/共享 MySQL |
| 5 | 集群节点间不做配置同步, 只靠 DumpService 定时扫描 | 新配置发布后数秒甚至数分钟才同步到其他节点 → 长轮询客户端感知延迟大 | AsyncNotifyService 主动通知 + DumpService 定时兜底 |

---

## 六、新手最可能犯的 4 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 集群模式下所有节点用 Embedded Derby(各自独立 DB) | 节点 A 发布的配置写入自己的 Derby → 节点 B 从自己的 Derby 读 → 永远读不到 | 集群用 MySQL 共享存储 | 默认 Derby, 生产需配置 MySQL |
| 2 | 认为 AsyncNotifyService 通知失败 = 发布失败 | Notify 失败 ≠ 发布失败: 配置已在 DB 持久化, 只是目标节点暂时不知道 | 发布成功只校验本地 DB 写入, 不等待集群通知 | 通知是异步的, 与发布解耦 |
| 3 | 修改配置后立即在另一个节点 getConfig 期望读到新值 | Notify 异步 → 有网络延迟 → 另一个节点可能还没同步 | 等待 Notify 完成(通常 <1s), 或使用 gRPC ConfigChangeNotifier(实时) | 异步通知有延迟 |
| 4 | 不配置集群成员的 HEALTHY_CHECK_STATUS | 网络波动时 SUSPICIOUS 状态的节点不发送通知 → 延迟更大 | 根据网络稳定性调整健康检查参数 | 默认 SUSPICIOUS 也算健康(可调) |

### 6.1 可运行的错误实验

**实验: Derby 集群模式数据不一致**

```
3 节点 Nacos 集群, 全部用默认 Derby:
  节点 A 发布配置 key=db.password → 写入自己的 Derby
  节点 B 收到 Notify → 从自己的 Derby 读取 → 没有 key=db.password!
  → 节点 B 的长轮询客户端永远收不到变更通知
  
正确做法: 集群用 MySQL → 所有节点共用同一个 DB
```

---

## 七、面试 Q&A (4 题)

### Q1: 配置变更如何通知集群中所有节点？

**面试官意图**: 考察对集群同步机制的理解

**参考答案要点**:
- ConfigDataChangeEvent → AsyncNotifyService.onEvent
- 遍历所有健康成员 → configClusterRpcClientProxy.syncConfigChange
- 目标节点收到 → DumpService.dump → 从 DB 加载 → LongPollingService 唤醒客户端
- 失败重试: 二次方退避(1500ms→4500ms→9500ms→...→36500ms), 最多 6 次(总约 88s)

### Q2: Config 集群同步和 Naming Distro 同步有什么本质区别？

**面试官意图**: 考察对两种同步模式的对比理解(联动 D-09)

**参考答案要点**:
- Naming: Push 数据(内存级), 频率高, 需要反熵补偿
- Config: 通知+拉取(DB 级), 频率低, 有 DB 作为权威源
- 根本原因: Config 数据有 DB, Naming 临时实例数据仅在内存

### Q3: 集群间配置同步的可靠性？

**面试官意图**: 考察对容错机制的理解

**参考答案要点**:
- 第一层: AsyncNotifyService 二次方退避重试(最多 6 次, ~88s 内完成)
- 第二层: DumpService 定时扫描 DB → 发现差异 → 自动加载
- 第三层: 客户端长轮询 → 30s 内至少一次全量检查 → 最终感知
- 综合: 最坏情况 ~30s + Notify 失败窗口内丢失配置更新

### Q4: 如果 Nacos 集群中一个节点长时间离线后又恢复, 配置会丢吗？

**面试官意图**: 考察对数据持久化和恢复的理解

**参考答案要点**:
- 不会丢。配置存储在 DB(MySQL)。
- 离线期间: 其他节点发布的配置写入 DB → 该节点不知道
- 恢复后: DumpService 启动 → 从 DB 加载全量配置 → 补充离线期间的变更
- 时间: 取决于 DumpService 启动加载时间(磁盘 dump 优先 → DB fallback)

---

## 八、产出文档结构

```
D-12-ConfigSync.md

1. 概述: 集群配置同步的整体架构
2. AsyncNotifyService — 集群通知核心
   2.1 注册 ConfigDataChangeEvent 监听
   2.2 遍历健康成员 + 异步通知
   2.3 重试机制(6次递增间隔)
3. ConfigClusterRpcClientProxy — RPC 通信
4. 接收侧处理 — ConfigChangeClusterSyncRequestHandler
5. Config vs Naming 同步对比
6. 反面假设分析(5 组)
7. 新手最可能犯的 4 个错误 + 实验
8. 面试 Q&A(4 题)
```

---

## 九、与后续文档的依赖关系

```
D-12 (集群配置同步)  ← 当前
  ↓
D-13 (Nacos 注册中心设计全景 + 集群管理)
D-14 (面试模拟 18 题)
```
