# D-08 详细规划: Push 推送体系 + 健康检查 + 故障自愈

> 阶段: Phase 4 — 服务端命名服务核心 | 预期产出: `md/D-08-PushHealthRecovery.md` | 状态: 待审核

---

## 一、为什么需要这篇

服务端命名服务的最后一块拼图。D-07 覆盖了写路径(注册→事件→索引), 这篇覆盖了三个下游能力:

1. **Push 推送**: ServiceChangedEvent → DelayTask → 找到所有订阅者 → gRPC Push
2. **健康检查**: 连接断开 → 自动清理 + 定时补偿检查
3. **故障自愈**: ClientReleaseEvent → 全自动清理 + 集群同步

这三个能力合在一起, 构成了 Nacos "注册-变更感知-故障处理" 的完整闭环。

前置依赖: D-07 (注册处理链 + 事件驱动索引)

> **与 D-07 的呼应**: D-07 的 ClientServiceIndexesManager 发布 ServiceChangedEvent → D-08 的 NamingSubscriberServiceV2Impl 消费这个事件, 触发 Push。D-07 的 ClientReleaseEvent → D-08 的健康检查机制订阅, 触发故障清理。

---

## 二、源码阅读清单 (按调用链顺序)

| 序号 | 源码文件 | 行数 | 必须阅读的内容 | 阅读目的 |
|------|----------|------|---------------|----------|
| 1 | `naming/src/main/java/.../push/v2/NamingSubscriberServiceV2Impl.java` | 139 | subscribeTypes(107-112), onEvent(114-129), getSubscribers(80-87) | 理解 Push 的入口和推送给谁 |
| 2 | `naming/src/main/java/.../push/v2/executor/PushExecutorDelegate.java` | ~60 | doPush() | 理解实际的 gRPC push 执行 |
| 3 | `naming/src/main/java/.../push/v2/task/PushDelayTask.java` | ~80 | merge(), execute() | 理解延迟合并推送 |
| 4 | `core/src/main/java/.../core/remote/core/RpcPushService.java` | ~50 | push() | 理解服务端 push 到具体连接 |
| 5 | `naming/src/main/java/.../healthcheck/heartbeat/ClientBeatCheckTaskV2.java` | ~100 | run() | 理解 2.x 心跳补偿检查 |
| 6 | `naming/src/main/java/.../healthcheck/heartbeat/ExpiredInstanceChecker.java` | ~60 | check() | 理解过期实例清理 |
| 7 | `naming/src/main/java/.../remote/rpc/handler/ClientReleaseRequestHandler.java` | ~60 | handle() | 理解客户端断开处理 |
| 8 | `naming/src/main/java/.../push/v2/PushConfig.java` | ~40 | getPushTaskDelay() | 理解 PushDelayTask 的延迟配置 |

---

## 三、分析要点详解

### 3.1 Push 推送的完整链路 — 从 ServiceChangedEvent 到客户端感知

```
[触发源: D-07 的 ClientServiceIndexesManager]
ClientServiceIndexesManager.addPublisherIndexes/removePublisherIndexes
  → NotifyCenter.publishEvent(new ServiceChangedEvent(service, true))

[消费者: NamingSubscriberServiceV2Impl] (SmartSubscriber, NamingSubscriberServiceV2Impl.java:114-129)
subscribeTypes():
  - ServiceChangedEvent       // Service 实例列表变化
  - ServiceSubscribedEvent    // 新客户端订阅了服务

onEvent(event):
  if ServiceChangedEvent:
    → delayTaskEngine.addTask(service, 
        new PushDelayTask(service, pushTaskDelay))         // 延迟合并!
    → MetricsMonitor.incrementServiceChangeCount(service)  // Metrics 计数
  
  if ServiceSubscribedEvent:
    → delayTaskEngine.addTask(service, 
        new PushDelayTask(service, pushTaskDelay, clientId)) // 只推送新订阅者

[PushDelayTask — 延迟合并推送] (PushDelayTask.java)
// 合并策略: 同一个 Service 的多次变更合并为一次推送
merge(oldTask):
  → 更新 lastModifiedTime → 延迟窗口重新计时
  → 如果新 Task 有特定 clientId(ServiceSubscribedEvent) → 合并到集合中

execute():
  → 获取该 Service 的所有订阅者:
    indexesManager.getAllClientsSubscribeService(service) → Set<ClientId>
  → 遍历每个 clientId:
    → client = clientManager.getClient(clientId)
    → subscriber = client.getSubscriber(service)
    → ServiceStorage.getPushData(service) → ServiceInfo(完整实例列表)
    → pushExecutor.doPush(clientId, subscriber, serviceInfo)  // 实际推送!
```

**PushDelayTask 的核心价值**:
- **延迟合并窗口** (pushTaskDelay, 默认 `DEFAULT_PUSH_TASK_DELAY=500ms`, `PushConstants.java:31`):
  - 在窗口内同一个 Service 的多次变更 → 只推送一次
  - 例如: 3 个实例连续注册 → 3 个 ServiceChangedEvent → 3 个 PushDelayTask → merge 后只推送 1 次
  - 减少推送频率, 避免推送风暴
- **推送超时** (`DEFAULT_PUSH_TASK_TIMEOUT=5000ms`, `PushConstants.java:38`): 单个 push 操作的超时上限
- **重试间隔** (`DEFAULT_PUSH_TASK_RETRY_DELAY=1000ms`, `PushConstants.java:45`): push 失败后的重试间隔

### 3.2 两种推送场景的区别

| 场景 | 触发事件 | 推送范围 | PushDelayTask 特点 |
|------|---------|---------|-------------------|
| 实例变更 | ServiceChangedEvent | 该 Service 的**所有**订阅者 | merge 合并窗口内同 Service 的多次变更 |
| 新订阅 | ServiceSubscribedEvent | 仅新订阅的**单个**客户端 | 只推送给该 clientId, 不推送给其他人 |

### 3.3 健康检查 V2 — 连接即生命周期 + 定时补偿

Nacos 2.x 健康检查 = **两层机制**:

```
[第一层: 连接驱动 — 主要机制]
ConnectionManager.unregister(connectionId)  // D-05
  → clientConnectionEventListenerRegistry.notifyClientDisConnected
  → ClientManagerDelegate.clientDisconnected(clientId)
    → 发布 ClientReleaseEvent
    → ClientServiceIndexesManager.handleClientDisconnect  // D-07
      → 遍历清理 publisherIndex + subscriberIndex
      → 发布 DeregisterInstanceTraceEvent
    → DistroClientDataProcessor 同步到其他节点  // D-09

[第二层: 定时补偿 — 兜底机制]
ClientBeatCheckTaskV2 (定时任务):
  → 遍历所有 Client
  → 检查 client.getLastUpdatedTime() 是否超过阈值
  → 超时的临时 Client → 标记为不健康 → 触发清理
  → 超时的持久 Client → 仅标记不健康, 不删除

ExpiredInstanceChecker:
  → 检查临时实例是否过期
  → 过期 → 自动注销
```

**1.x vs 2.x 健康检查对比**:

| 维度 | 1.x | 2.x |
|------|-----|-----|
| 心跳方式 | 客户端每 5s HTTP POST /nacos/v1/ns/instance/beat | gRPC keep-alive 自动检测 |
| 服务端感知 | 定时扫描 lastBeat 时间 | 连接断开回调 + 定时补偿 |
| 故障检测延迟 | 最长 30s(IP_DELETE_TIMEOUT) | 毫秒级(连接断开) + 补偿周期 |
| 心跳超时 | HEART_BEAT_TIMEOUT=15s | gRPC keep-alive 可配置 |
| 删除超时 | IP_DELETE_TIMEOUT=30s | 连接断开即删(临时) |

### 3.4 故障自愈 — 连接断开的全自动清理

```
完整时间线 (Client crash):
T0: 客户端进程 crash → gRPC 连接断开
T1: gRPC server 检测到连接断开 → grpcConnection.onComplete()
T2: ConnectionManager.unregister(connectionId) → 移除连接
T3: ClientConnectionEventListener.notifyClientDisConnected(connection)
T4: ClientManagerDelegate.clientDisconnected(clientId)
    → 发布 ClientReleaseEvent
T5: ClientServiceIndexesManager.handleClientDisconnect:
    → 遍历 publisherIndexes 和 subscriberIndexes → 清理
    → publish ServiceChangedEvent → 触发 Push
    → publish DeregisterInstanceTraceEvent → 日志追踪
T6: DistroClientDataProcessor 监听到 ClientReleaseEvent
    → DistroClientSyncData(标记为 deleted)
    → DistroProtocol.sync() → 其他 Nacos 节点也清理
T7: PushDelayTask 执行 → RpcPushService.push → 通知所有订阅者

总延迟: T0→T7 通常在毫秒到秒级(取决于 keep-alive 配置 + Distro sync)
```

---

## 四、设计哲学分析

### 4.1 PushDelayTask 的延迟合并 — 类似 debounce 的 "最终一致性" 设计

```
3 个实例连续注册(200ms 内):
  → 3 个 ServiceChangedEvent
  → 3 个 PushDelayTask 被创建
  → merge() 合并: 只保留最新的 lastModifiedTime
  → 延迟窗口到期 → 执行 1 次 Push → 推送给所有订阅者
```

**设计意图**:
- 不是"每条变更都 push"(会导致 push 风暴)
- 不是"只 push 最后一条"(可能丢失中间状态, 不过 Nacos 是 AP 系统, 可以接受)
- 延迟合并 = 在"即时性"和"效率"之间取平衡

### 4.2 连接即生命周期 + 定时补偿 — 双层保障

- 连接层 = 快速路径(毫秒级故障检测)
- 定时补偿 = 慢速兜底(连接层可能遗漏的情况, 如 keep-alive 配置异常)
- 这类似于 CPU 的 L1 Cache + L2 Cache 设计: 大部分情况走快速路径, 特殊情况走兜底

### 4.3 1.x 心跳 → 2.x 连接 — 从主动轮询到被动感知

- 1.x: 客户端主动发心跳, 服务端被动接收 → 网络开销大(每个实例每 5s 一次 HTTP 请求)
- 2.x: gRPC keep-alive 自动检测 → 零额外网络开销
- 结论: 将健康检查从应用层下沉到传输层, 是 Nacos 2.x 最关键的架构升级

---

## 五、反面假设分析 (7 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | ServiceChangedEvent 直接 push, 不经 DelayTask 合并 | 3 个实例连续注册 → 3 次 push → 推送风暴。网络带宽和 CPU 浪费在重复推送相同内容上。 | PushDelayTask 延迟合并, 同 Service 只推送一次 |
| 2 | ServiceSubscribedEvent 也推送给所有订阅者(不是只推新订阅者) | 每次新客户端订阅 → 向所有现有订阅者发送一次无意义的全量 push | 只推送给新订阅的单个 clientId |
| 3 | Nacos 2.x 完全依赖连接检测, 不做定时补偿 | keep-alive 配置异常时, 连接断开未被检测 → 实例永远标记为 healthy → 路由黑洞。 | ClientBeatCheckTaskV2 定时补偿 |
| 4 | 连接断开后不自动清理, 等定时任务扫描 | 可能长达数十秒(DEFAULT_IP_DELETE_TIMEOUT=30s) 的路由黑洞期。故障实例继续被路由。 | 连接断开即刻清理 + 定时补偿兜底 |
| 5 | PushDelayTask 的 merge 窗口固定不变 | 高流量时合并不足(推送频繁), 低流量时延迟过长(订阅者等待) | pushTaskDelay 可配置(PushConfig) |
| 6 | PushExecutorDelegate 同步执行 push(一个一个推) | 1000 个订阅者 → 按序推送 → 最后一个等 1000 次 gRPC 调用才能收到 | 并行推送(PARALLEL_SIZE=100) |
| 7 | 健康检查只在连接层, 不做实例层的过期检查 | 临时实例注册后 Client 存活但实例本身已过期, 不会被清理 | ExpiredInstanceChecker 定时扫描过期实例 |

---

## 六、新手最可能犯的 5 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 认为 push 是实时的(注册后立即 push) | 注册后客户端可能等 pushTaskDelay 窗口到期才收到推送 | 生产环境 pushTaskDelay 设小(如 500ms)或接受延迟 | PushDelayTask 可配置延迟 |
| 2 | 认为 ServiceChangedEvent 和 ServiceSubscribedEvent 推送逻辑相同 | 第 1 个事件推所有订阅者, 第 2 个只推新订阅者 | 理解两者的语义差异: Changed=广播, Subscribed=单播 | onEvent 中两者的 PushDelayTask 构造不同 |
| 3 | keep-alive 配置不当导致故障检测太慢 | 客户端 crash 后服务端几十秒才感知 | 生产环境 keepAliveTime=60s, keepAliveTimeout=20s | ClientBeatCheckTaskV2 兜底 |
| 4 | 忽视 ExpiredInstanceChecker 的作用 | 连接正常的 Client 但其某个实例过期 → 不会被清理 | 理解 2.x 的双层健康检查: 连接层 + 实例层 | ExpiredInstanceChecker 独立定时任务 |
| 5 | pushTaskDelay 设太小(如 50ms) | 合并窗口太短 → 几乎每个变更都单独 push → 推送风暴 | 设为 500ms-2000ms 之间(容忍合并 + 及时推送) | PushConfig 可配置 |

### 6.1 可运行的错误实验

**实验 1: pushTaskDelay 对推送频率的影响**

```
模拟场景: 5 个实例同时注册(10ms 内):
  pushTaskDelay=1000ms → 1 次 push(5 个变更合并)
  pushTaskDelay=100ms → 1-2 次 push(可能来不及合并)
  pushTaskDelay=0ms → 5 次 push(每个变更单独推送)

验证方法: 观察 NamingSubscriberServiceV2Impl 的 getPushPendingTaskCount()
  → push 前 count=5, push 后 count=0
```

**实验 2: 连接断开后的延迟感知对比**

```
// 1.x 场景(模拟):
// 客户端注册临时实例 → 不再发送心跳 → 等待 IP_DELETE_TIMEOUT(30s)
// → 30s 后其他客户端才知道实例下线

// 2.x 场景(实际):
// 客户端连接断开 → T0+毫秒级 unregister → T0+毫秒级+pushTaskDelay push
// → 订阅者几乎立即(最多 pushTaskDelay)就知道实例下线
```

---

## 七、面试 Q&A (5 题)

### Q1: Nacos 2.x 的 Push 推送是怎么实现的？PushDelayTask 合并机制的作用是什么？

**面试官意图**: 考察对推送优化策略的理解

**参考答案要点**:
- 触发: ServiceChangedEvent → NamingSubscriberServiceV2Impl.onEvent → PushDelayTask
- 合并: 同一个 Service 的多次变更 → merge() 只保留最新 → 一次推送
- 执行: delay 到期 → getSubscribers → pushExecutor.doPush → RpcPushService.push → gRPC
- 两种场景: ServiceChanged → 广播, ServiceSubscribed → 单播
- 核心价值: 减少重复推送, 避免推送风暴

**追问**: "如果 pushTaskDelay=0(不做合并), 会有什么问题？"
- 每个注册都触发一次 push → 1000 个实例批量启动 → 1000 次 push
- 每次 push 构建完整的 ServiceInfo(含所有实例) → 传输 O(N*M) 的数据量

### Q2: Nacos 2.x 基于连接的健康检查和 1.x 的心跳检查有什么本质区别？

**面试官意图**: 考察对架构演进的理解

**参考答案要点**:
- 1.x: 应用层心跳(HTTP POST), 5s 一次, 服务端定时扫描, 删除延迟 30s
- 2.x: 传输层检测(gRPC keep-alive), 零额外开销, 断开即删
- 优势: 网络开销↓ 故障感知速度↑ 系统复杂度↓
- 兜底: ClientBeatCheckTaskV2 定时补偿, 防止 keep-alive 配置异常导致的遗漏

### Q3: 客户端 crash 后, Nacos 服务端如何感知并处理？完整的时间线是什么？

**面试官意图**: 考察对故障自愈全流程的理解

**参考答案要点**:
- T0: crash → 连接断开 → T1-T4: unregister + disconnect → T5: 清理索引 + ServiceChangedEvent → T6: Distro sync → T7: Push 通知订阅者
- 总延迟: 毫秒到秒级(取决于 keep-alive + pushTaskDelay + Distro sync)
- 临时实例: 全自动清理(索引 + 实例数据 + 集群同步)
- 持久实例: 仅标记不健康, 数据保留

### Q4: PushDelayTask 的合并窗口应该设置多大？

**面试官意图**: 考察对性能调优的理解

**参考答案要点**:
- 如果设太小(<200ms): 合并效果差 → 推送风暴
- 如果设太大(>5000ms): 订阅者感知延迟长 → 路由表过期
- 推荐: 500-2000ms, 根据实际推送频率和订阅者数量调整
- 权衡: 推送频率 vs 感知延迟

### Q5: ExpiredInstanceChecker 和 ClientBeatCheckTaskV2 的分工是什么？（联动 D-07/D-09）

**面试官意图**: 考察对双层健康检查的理解

**参考答案要点**:
- ClientBeatCheckTaskV2: 检查 Client 级别是否存活(连接 → lastUpdatedTime)
- ExpiredInstanceChecker: 检查 Instance 级别是否过期(实例 → TTL)
- 分工: Client 存活但 Instance 过期 → ExpiredInstanceChecker 清理
- 补偿关系: 连接层可能漏(keep-alive 异常) → 两层兜底

---

## 八、产出文档结构

```
D-08-PushHealthRecovery.md

1. 概述: Push/Health/Recovery 三大闭环
2. Push 推送体系
   2.1 NamingSubscriberServiceV2Impl — 事件消费者
   2.2 PushDelayTask — 延迟合并机制
   2.3 PushExecutorDelegate + RpcPushService — 实际推送
   2.4 ServiceChanged(广播) vs ServiceSubscribed(单播)
3. 健康检查 V2
   3.1 连接即生命周期 — 主要机制
   3.2 ClientBeatCheckTaskV2 — 定时补偿
   3.3 ExpiredInstanceChecker — 实例层过期检查
   3.4 1.x vs 2.x 完整对比
4. 故障自愈
   4.1 连接断开的完整时间线(T0→T7)
   4.2 临时实例 vs 持久实例的处理差异
   4.3 Distro sync 在故障传播中的作用
5. 设计哲学分析
   5.1 PushDelayTask 的 debounce 设计
   5.2 双层健康检查(L1连接 + L2补偿)
   5.3 从应用层心跳到传输层检测的演进
6. 反面假设分析(7 组)
7. 新手最可能犯的 5 个错误 + 可运行错误实验
8. 面试 Q&A(5 题)
```

---

## 九、与后续文档的依赖关系

```
D-08 (Push 推送 + 健康检查 + 故障自愈)  ← 当前
  |--- Distro sync 在故障传播中的作用 → D-09 Distro V2 协议
  |--- RpcPushService → 跨节点推送 → D-09 集群同步
  ↓
D-09 (Distro V2 协议)
D-10 (CP/AP 双模式路由 + JRaft 整合)
```
