# D-07 详细规划: 注册处理链 + 事件驱动索引系统

> 阶段: Phase 4 — 服务端命名服务核心 | 预期产出: `md/D-07-RegistrationEventIndex.md` | 状态: 待审核

---

## 一、为什么需要这篇

理解了 D-05 的 gRPC 连接模型和 D-06 的 Client 数据模型后, 这篇追踪 **完整的写路径**: 一个 registerInstance 请求到达服务端后, 如何经过 Handler → ClientOperationService → Client 存储 → 事件发布 → 索引更新 → 最终到达订阅者感知。

事件驱动 + 索引系统是 Nacos 2.x 最核心的设计模式。不理解这个模式, 就无法理解为什么注册一个实例到其他客户端感知到新实例之间, 经历了复杂的异步事件链。

前置依赖: D-05 (gRPC 连接模型) + D-06 (Client 模型 + 数据存储)

> **与 D-05/D-06 的呼应**: D-05 的 GrpcRequestAcceptor 接收到请求 → D-06 的 Client.addServiceInstance 存储数据 → D-07 的 ClientServiceIndexesManager 建索引 → D-08 的 NamingSubscriberService 推送。

---

## 二、源码阅读清单 (按调用链顺序)

| 序号 | 源码文件 | 行数 | 必须阅读的内容 | 阅读目的 |
|------|----------|------|---------------|----------|
| 1 | `naming/src/main/java/.../remote/rpc/handler/InstanceRequestHandler.java` | 89 | handle(53-70), registerInstance(72-79), deregisterInstance(81-87) | 理解 gRPC 请求入口 + 类型分发 |
| 2 | `naming/src/main/java/.../core/v2/service/impl/EphemeralClientOperationServiceImpl.java` | 161 | registerInstance(55-76), deregisterInstance(105-124), subscribeService(126-136) | 理解注册/注销/订阅的核心实现 |
| 3 | `naming/src/main/java/.../core/v2/event/client/ClientOperationEvent.java` | ~90 | ClientRegisterServiceEvent, ClientDeregisterServiceEvent, ClientSubscribeServiceEvent, ClientReleaseEvent | 理解四类客户端事件 |
| 4 | `naming/src/main/java/.../core/v2/event/service/ServiceEvent.java` | ~40 | ServiceChangedEvent, ServiceSubscribedEvent | 理解服务变更事件 |
| 5 | `naming/src/main/java/.../core/v2/index/ClientServiceIndexesManager.java` | 162 | subscribeTypes(79-88), onEvent(90-97), addPublisherIndexes(130-134), handleClientDisconnect(99-114) | 理解索引管理者 — 事件驱动的核心 |
| 6 | `common/src/main/java/.../common/notify/NotifyCenter.java` | ~200 | publishEvent(), registerSubscriber(), registerToPublisher() | 理解事件发布中心 |
| 7 | `common/src/main/java/.../common/notify/listener/SmartSubscriber.java` | ~30 | subscribeTypes(), onEvent() | 理解智能订阅者接口 |
| 8 | `naming/src/main/java/.../core/v2/index/ServiceStorage.java` | ~100 | getData(), getPushData(), getServiceClusterMap | 理解查询路径上的数据聚合 |

---

## 三、分析要点详解

### 3.1 注册请求完整处理链 — 从 gRPC 到内存 + 事件

```
========== 注册请求处理链 ==========

[层级 1: gRPC 入口 — D-05 的 GrpcRequestAcceptor 接收]
InstanceRequest(REGISTER_INSTANCE, namespace, group, serviceName, instance)
  → GrpcRequestAcceptor → 解析 type=REGISTER_INSTANCE
  → RequestHandlerRegistry → 找到 InstanceRequestHandler

[层级 2: Handler 分发 — InstanceRequestHandler.handle()]
  → Service.newService(namespace, groupName, serviceName, true)     // 构建 Service 路由键
  → InstanceUtil.setInstanceIdIfEmpty(instance, groupedServiceName) // 补全 instanceId
  → switch (request.getType()):
      case REGISTER_INSTANCE:
        → registerInstance(service, request, meta)                   // 调用私有方法
      case DE_REGISTER_INSTANCE:
        → deregisterInstance(service, request, meta)

[层级 3: 业务处理 — EphemeralClientOperationServiceImpl.registerInstance()]
  → NamingUtils.checkInstanceIsLegal(instance)            // IP/port/weight 合法性校验
  → ServiceManager.getInstance().getSingleton(service)    // 获取 Service 单例(首次则创建)
  → if (!singleton.isEphemeral()) → 抛异常                // ⚠️ ephemeral 类型校验
  → Client client = clientManager.getClient(clientId)     // 根据 connectionId 找到 Client(D-06)
  → if (!clientIsLegal(client)) → return                  // Client 不存在或非临时 → 跳过
  
  // ===== 核心: 数据存储 =====
  → InstancePublishInfo instanceInfo = getPublishInfo(instance)  // 构造发布信息
  → client.addServiceInstance(singleton, instanceInfo)           // 存入 Client 内存(D-06 §3.4)
  → client.setLastUpdatedTime()                                  // 更新活跃时间
  → client.recalculateRevision()                                 // 重算 revision(D-09 sync 用)
  
  // ===== 事件驱动链开始! =====
  → NotifyCenter.publishEvent(
      new ClientOperationEvent.ClientRegisterServiceEvent(singleton, clientId))
      // 事件1: 触发 ClientServiceIndexesManager 更新索引(见 3.2)
  
  → NotifyCenter.publishEvent(
      new MetadataEvent.InstanceMetadataEvent(singleton, metadataId, false))
      // 事件2: 触发元数据存储

[层级 4: 索引更新 — ClientServiceIndexesManager.onEvent()]
  → if (event instanceof ClientRegisterServiceEvent):
    → addPublisherIndexes(service, clientId)                       // 见 3.2 详细分析
    → NotifyCenter.publishEvent(ServiceChangedEvent)               // ⚡ 触发推送!

[层级 5: 推送触发 — D-08 的 NamingSubscriberServiceV2Impl]
  → 订阅 ServiceChangedEvent
  → getSubscribers(service)                                       // 找到所有订阅者
  → PushDelayTask(延迟合并) → RpcPushService.push()               // 推送给每个订阅者
```

**关键设计细节**:
- `InstanceUtil.setInstanceIdIfEmpty()`: instanceId 格式 = `ip#port#clusterName#serviceName`(Constants.java:165-168), 分隔符 `#`
- `getPublishInfo(instance)`: 将 Instance 转换为 InstancePublishInfo — 只保留 ip/port/cluster/metadata, 丢掉 healthy/enabled/weight(这些由 Client 管理)
- `clientIsLegal()`: 检查 Client 非 null 且是临时实例 — 防止持久 Client 走临时逻辑

### 3.2 事件驱动索引系统 — 核心设计模式

```
[事件发布者]
EphemeralClientOperationServiceImpl.registerInstance()
  → NotifyCenter.publishEvent(ClientRegisterServiceEvent)

[事件总线]
NotifyCenter
  → DefaultSharePublisher
    → 根据 Event 类型分发给所有已注册的 Subscriber

[事件消费者: ClientServiceIndexesManager]
ClientServiceIndexesManager (SmartSubscriber, 订阅 5 种事件)
  
  subscribeTypes():
    ClientRegisterServiceEvent       // 新实例注册
    ClientDeregisterServiceEvent     // 实例注销
    ClientSubscribeServiceEvent      // 新订阅
    ClientUnsubscribeServiceEvent    // 取消订阅
    ClientReleaseEvent               // Client 断开连接
  
  onEvent(event):
    if ClientRegisterServiceEvent:
      → addPublisherIndexes(service, clientId)
        → publisherIndexes.computeIfAbsent(service, new ConcurrentHashSet<>())
        → publisherIndexes.get(service).add(clientId)
        → NotifyCenter.publishEvent(new ServiceChangedEvent(service, true))
          // ⚡ 通知推送系统: 这个 Service 的实例列表变了!
    
    if ClientDeregisterServiceEvent:
      → removePublisherIndexes(service, clientId)
        → publisherIndexes.computeIfPresent → 删除 clientId
        → 如果 Set 变空 → 删除整个 entry(ServiceManager.removeSingleton?)
        → NotifyCenter.publishEvent(new ServiceChangedEvent(service, true))
    
    if ClientSubscribeServiceEvent:
      → addSubscriberIndexes(service, clientId)
        → subscriberIndexes.computeIfAbsent(service, new ConcurrentHashSet<>())
        → if (首次添加) → NotifyCenter.publishEvent(new ServiceSubscribedEvent(service, clientId))
    
    if ClientUnsubscribeServiceEvent:
      → removeSubscriberIndexes(service, clientId)
        → 删除后如果 Set 为空 → 移除整个 key
    
    if ClientReleaseEvent: // ⚠️ 最复杂的处理 — Client 连接断开
      → handleClientDisconnect(event)
        → 遍历 client.getAllSubscribeService() → removeSubscriberIndexes
        → 遍历 client.getAllPublishedService()
          → removePublisherIndexes → publish ServiceChangedEvent
          → 同时 publish DeregisterInstanceTraceEvent(记录追踪日志)
```

**双重索引的精确数据结构**:
- `publisherIndexes`: ConcurrentMap<Service, Set<ClientId>> — 哪些 Client 注册了哪些 Service
- `subscriberIndexes`: ConcurrentMap<Service, Set<ClientId>> — 哪些 Client 订阅了哪些 Service

**为什么需要两个索引？**
- publisherIndexes → 用于查询"某个 Service 有哪些实例" + 健康检查/故障清理
- subscriberIndexes → 用于查询"某个 Service 有哪些订阅者" + Push 推送
- 两者独立 → 注册者不一定是订阅者, 订阅者不一定是注册者

### 3.3 ClientReleaseEvent 处理 — 连接断开的全自动清理

```
ClientManager.clientDisconnected(clientId)
  → 发布 ClientReleaseEvent
  
ClientServiceIndexesManager.onEvent(ClientReleaseEvent):
  → handleClientDisconnect(event):
    
    // 1. 清理订阅
    for (Service each : client.getAllSubscribeService())
      → subscriberIndexes.remove(subscriberId)
    
    // 2. 清理发布(关键!)
    for (Service each : client.getAllPublishedService())
      → publisherIndexes.remove(publisherId)
      → NotifyCenter.publishEvent(ServiceChangedEvent)        // 触发推送
      → NotifyCenter.publishEvent(DeregisterInstanceTraceEvent) // 记录追踪
    
    // 3. Client 本身由 ClientManager 清理(D-06)
    
    // 4. Distro sync 被触发(D-09):
    //    ClientReleaseEvent 也被 DistroClientDataProcessor 订阅
    //    → 构建 ClientSyncData(标记为 deleted)
    //    → DistroProtocol.sync() → 其他节点也清理该 Client
```

### 3.4 ServiceStorage — 查询路径的数据聚合

当 `getAllInstances(serviceName)` 被调用时, 不是扫描所有 Client, 而是:

```
ServiceStorage.getData(service):
  → ClientServiceIndexesManager.getAllClientsRegisteredService(service)
    → publisherIndexes.get(service) → Set<ClientId>
  
  → 遍历每个 clientId:
    → Client client = clientManager.getClient(clientId)               // D-06
    → InstancePublishInfo info = client.getInstancePublishInfo(service) // 获取实例信息
  
  → 组装成完整的 ServiceInfo(含 hosts 列表)
  → 返回给客户端
```

### 3.5 NotifyCenter — 事件驱动基础设施

```
NotifyCenter.publishEvent(event):
  → 根据 event 的 scope 找到对应的 Publisher
  → NamingEventPublisherFactory → NamingEventPublisher(命名模块专用)
  → DefaultPublisher → extends Thread, 使用 BlockingQueue 异步消费事件
    → publisher.publish(event) → BlockingQueue.offer(event)
      → Thread.run() → queue.take() → subscriber.onEvent(event)
```

**实现细节**:
- `DefaultPublisher extends Thread` (`DefaultPublisher.java:41`) — 一个事件类型对应一个线程
- `BlockingQueue<Event>` 作为事件缓冲区 (`DefaultPublisher.java:55`)
- `NamingEventPublisher extends Thread` (`NamingEventPublisher.java:39`) — 使用 `ArrayBlockingQueue` (`NamingEventPublisher.java:60`)
- `ringBufferSize=16384` (`NotifyCenter.java:72`) — 虽名叫 ringBuffer, 实际是 BlockingQueue 容量, 不是 Disruptor

**为什么用单线程 + BlockingQueue 而不是线程池？**
- 单线程串行处理事件 → 保证同一事件类型的处理顺序
- BlockingQueue 提供了天然的背压: 队列满时 `offer` 失败 → `publishEvent` 可以阻塞或丢弃
- 每个事件类型独立线程 → 不同事件的 Subscriber 互不影响
- 代价: Subscriber.onEvent() 不能做耗时操作 → 阻塞事件处理线程

| 订阅者 | 订阅的事件 | onEvent 做什么 |
|--------|-----------|---------------|
| ClientServiceIndexesManager | 5种ClientOperationEvent | 维护 publisherIndex + subscriberIndex |
| NamingSubscriberServiceV2Impl | ServiceChangedEvent | 触发 Push(D-08) |
| DistroClientDataProcessor | ClientOperationEvent | 触发 Distro sync(D-09) |

---

## 四、设计哲学分析

### 4.1 事件驱动 vs 直接调用: 为什么用事件桥接操作和通知？

```
直接调用: registerInstance → addToIndex → pushToSubscribers
事件驱动: registerInstance → publish Event → [索引更新] → [推送] → [Distro sync]
```

**为什么选事件驱动？**
1. **解耦**: 注册逻辑不需要知道推送逻辑和索引逻辑的存在。未来增加新的监听者(如 metrics 统计)不需要修改注册代码
2. **异步**: 事件通过 BlockingQueue 异步投递, 注册请求可以立即返回(不等待推送完成)
3. **可扩展**: 任何模块都可以订阅 ClientOperationEvent, 无需改动核心注册逻辑

**代价**:
- 延迟: 事件异步处理引入微秒到毫秒级延迟
- 复杂度: 调试时需要追踪事件链, 不如直接调用直观

### 4.2 双重索引 vs 单索引: 为什么要分开 publisher 和 subscriber？

- 查询路径不同: 查询"谁注册了这个服务" vs "谁订阅了这个服务"
- 生命周期不同: Client 可能只注册不订阅(如纯服务提供方), 也可能只订阅不注册(如网关)
- 清理粒度不同: ClientReleaseEvent 需要分别清理两个索引
- 如果把两者合并 → 无法区分 Client 是注册者还是订阅者 → connect/disconnect 语义模糊

### 4.3 为什么 SmartSubscriber 需要 subscribeTypes() ?

- `SmartSubscriber` 声明式告诉 NotifyCenter "我只关心哪些事件"
- NotifyCenter 在分发事件时可以跳过不相关的 Subscriber → 减少无效调用
- 对比普通 Subscriber: 每个事件都调用 onEvent, 在 onEvent 内部做 instanceof 判断
- SmartSubscriber 的优化: 在分发层就过滤, onEvent 收到的已经确定是关心的类型

---

## 五、反面假设分析 (7 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | 注册操作直接调 addToIndex + push, 不走事件 | 紧耦合: 修改推送逻辑需要改注册代码。同步等待推送完成 → 注册 RT 增加。无法异步扩展。 | 事件驱动: publish Event → 异步处理 |
| 2 | publish 和 subscribe 共用一个索引 | 无法区分"谁注册了"和"谁订阅了"。ClientReleaseEvent 不知道清理哪些数据。推送的目标列表混入注册者(不是订阅者)。 | 双重索引: publisherIndexes + subscriberIndexes |
| 3 | ClientServiceIndexesManager 的 publisherIndexes 用 List 而不是 Set | 同一个 Client 可能重复注册同一个 Service → List 中有重复 → push 重复通知 → 浪费带宽。 | ConcurrentHashSet 保证唯一 |
| 4 | ServiceChangedEvent 同步通知而不是异步 | registerInstance 调用被阻塞, 直到所有推送完成才返回。1000 个订阅者 → 注册 RT 可能数秒。 | 异步 RingBuffer → 事件投递极快 → 注册立即返回 |
| 5 | NotifyCenter 用线程池而不是单线程+BlockingQueue | 线程池调度开销大(上下文切换 + 锁竞争)。事件顺序无法保证 → 同一个 Service 的注册/注销可能乱序。 | 单线程 Thread + BlockingQueue → 顺序保证 + 背压控制 |
| 6 | ClientReleaseEvent 不清除 publisherIndex | 连接断开的 Client 的实例永远留在索引中。查询接口返回不存在的实例。健康检查失效(Client 已不存在)。 | ClientReleaseEvent → 遍历清理所有 publisher + subscriber |
| 7 | EphemeralClientOperationServiceImpl 不做 ephemeral 校验 | 临时实例和持久实例混走同一个逻辑 → 持久实例的数据可能被当作临时数据被错误清理。 | 校验 singleton.isEphemeral() + client.isEphemeral() |

---

## 六、新手最可能犯的 5 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 认为 registerInstance 返回 = 其他客户端已感知 | 注册 → 返回成功 → 事件还在 RingBuffer 中排队 → 其他客户端还没收到推送 | 注册后使用 subscribe 监听推送, 不依赖即时可见性 | 事件异步处理, 注册返回快但推送有延迟 |
| 2 | 在 SmartSubscriber.onEvent 中做耗时操作 | 阻塞事件处理线程 → 所有后续事件堆积 → 整个事件系统卡死 | onEvent 只做轻量操作, 耗时操作异步化 | 无自动防止, 但 RingBuffer 满时会阻塞 publish |
| 3 | 假设 publisherIndexes 和 subscriberIndexes 总是同步更新 | 两者是通过不同的事件路径更新的(不同 Subscriber), 存在微小的时间窗口 | 不依赖两者同步, 查询时分别查 | 事件处理的顺序性保证同一个 Client 的注册/注销不会乱序, 但不同 Client 可能交叉 |
| 4 | 不理解 ClientReleaseEvent 的双重清理 | 以为只需要清理 publisherIndex 不需要 subscriberIndex — 导致 subscription 泄漏 | 实际源码中遍历了 getAllSubscribeService + getAllPublishedService | handleClientDisconnect 同时清理两者 |
| 5 | 在 registerInstance 之后立即 deregisterInstance | 两个操作可能通过 RingBuffer 乱序到达 → deregister 先于 register 执行 → 实例清理失败 | 确保注册和注销之间有足够的间隔(至少一个事件处理周期) | 无明确防止, 但 RingBuffer 的单线程处理保证单 Client 的操作是顺序的 |

### 6.1 可运行的错误实验

**实验: 事件驱动延迟导致注册后立即查询不到实例**

```java
// 这个实验要配合 D-03 的客户端行为理解:
// 客户端 registerInstance → gRPC 请求 → 服务端处理 → 返回成功
// 但其他客户端通过 selectInstances 查询时, 数据可能还没走完事件链

// 服务端查看到的时间线:
// T0: InstanceRequestHandler.handle → registerInstance → publish ClientRegisterServiceEvent
// T1: NotifyCenter RingBuffer 处理 ClientRegisterServiceEvent
// T2: ClientServiceIndexesManager.addPublisherIndexes → publish ServiceChangedEvent
// T3: NotifyCenter RingBuffer 处理 ServiceChangedEvent
// T4: NamingSubscriberServiceV2Impl.onEvent → PushDelayTask 延迟合并
// T5: 延迟 window 结束后 → RpcPushService.push → gRPC 推送

// 从 T0 到 T5: 微秒到毫秒级(取决于 BlockingQueue 深度 + PushDelayTask 合并窗口)
// 如果 BlockingQueue 满了(queueMaxSize=16384), publish 会阻塞 → 注册请求被阻塞
```

---

## 七、面试 Q&A (5 题)

### Q1: Nacos 的注册请求从 gRPC 到其他客户端感知, 完整路径上做了什么？

**面试官意图**: 考察对完整写路径的理解, 能否串联多个组件

**参考答案要点**:
- gRPC 入口: InstanceRequestHandler.handle → 类型分发
- 业务处理: EphemeralClientOperationServiceImpl.registerInstance → Client.addServiceInstance
- 数据存储: Client 内存 Map 更新 → recalculateRevision
- 事件发布: ClientRegisterServiceEvent → RingBuffer
- 索引更新: ClientServiceIndexesManager → publisherIndexes
- 事件再发布: ServiceChangedEvent → RingBuffer
- 推送: NamingSubscriberServiceV2Impl → PushDelayTask → RpcPushService
- 最终到达客户端: NamingPushRequestHandler → ServiceInfoHolder → listener.onEvent

### Q2: Nacos 的事件驱动索引系统是怎么设计的？为什么用双重索引？

**面试官意图**: 考察对事件驱动 + 索引设计模式的理解

**参考答案要点**:
- publisherIndexes: Service → Set<ClientId> — 查询"谁注册了这个服务"
- subscriberIndexes: Service → Set<ClientId> — 查询"谁订阅了这个服务"
- 分开的原因: 查询路径不同、生命周期不同、清理粒度不同
- 事件驱动: ClientRegisterServiceEvent → addPublisherIndexes → ServiceChangedEvent → push

### Q3: NotifyCenter 用单线程 + BlockingQueue 而不是线程池, 为什么？

**面试官意图**: 考察对事件驱动 + 线程模型的理解

**参考答案要点**:
- 单线程串行处理 → 保证同一事件类型的处理顺序(注册先于注销)
- BlockingQueue 提供天然背压: 队列满时阻塞 publish → 保护系统
- 每个事件类型独立线程 → 不同类型互不影响(命名事件不会阻塞配置事件)
- 代价: onEvent 不能做耗时操作, 队列满时注册请求延迟增加
- 对比线程池: 线程池有锁竞争 + 上下文切换开销 + 事件顺序无法保证

**追问**: "如果改用 LMAX Disruptor 会有什么提升？"
- Disruptor 无锁 ring buffer → 更高吞吐, 但引入额外依赖
- Nacos 选择了更简单的 Thread + BlockingQueue, 对注册中心的 QPS 来说足够
- 这是一个"够用"而非"最优"的选择 — 工程决策的典型例子

### Q4: ClientReleaseEvent 的处理流程是什么？有什么陷阱？

**面试官意图**: 考察对故障处理流程的理解

**参考答案要点**:
- ClientReleaseEvent 触发 → handleClientDisconnect
- 遍历 client.getAllSubscribeService → removeSubscriberIndexes
- 遍历 client.getAllPublishedService → removePublisherIndexes → publish ServiceChangedEvent + DeregisterInstanceTraceEvent
- 陷阱: 如果 Client 有大量已发布服务(如一个服务注册了 1000 个不同 Service), 遍历可能耗时 → 在事件处理线程中执行

### Q5: 注册一个实例到订阅者感知, 路径上是同步还是异步？延迟是多少？（联动 D-03/D-08）

**面试官意图**: 考察对端到端延迟的理解

**参考答案要点**:
- 路径: 注册请求本身同步(等待 gRPC response), 但数据传播是异步的
- 延迟组成: BlockingQueue 处理 + PushDelayTask 合并窗口 + gRPC push 网络
- 典型值: 微秒到几十毫秒(取决于订阅者数量和网络 RTT)
- 极端情况: BlockingQueue 满时 publish 阻塞 → 注册请求延迟增加

---

## 八、产出文档结构

```
D-07-RegistrationEventIndex.md

1. 概述: 注册链路的全景(从 gRPC 到 push)
2. InstanceRequestHandler — gRPC 入口
   2.1 handle() 的类型分发
   2.2 @TpsControl + @Secured 注解
3. EphemeralClientOperationServiceImpl — 业务处理
   3.1 registerInstance 的完整处理链
   3.2 deregisterInstance + subscribeService(对称)
   3.3 clientIsLegal 的校验逻辑
4. ClientOperationEvent — 四种事件定义
5. NotifyCenter — 事件驱动基础设施
   5.1 Disruptor RingBuffer 架构
   5.2 DefaultSharePublisher vs NamingEventPublisherFactory
6. ClientServiceIndexesManager — 事件消费者的核心
   6.1 SmartSubscriber 接口
   6.2 双重索引: publisherIndexes + subscriberIndexes
   6.3 ClientReleaseEvent 的自动清理逻辑
7. ServiceStorage — 查询路径的数据聚合
8. 设计哲学分析
   8.1 事件驱动 vs 直接调用
   8.2 双重索引 vs 单索引
   8.3 SmartSubscriber 的性能优化
9. 反面假设分析(7 组)
10. 新手最可能犯的 5 个错误 + 可运行错误实验
11. 面试 Q&A(5 题,含面试官意图 + 追问)
```

---

## 九、与后续文档的依赖关系

```
D-07 (注册处理链 + 事件驱动索引)  ← 当前
  |--- ServiceChangedEvent → D-08 Push 推送体系用到
  |--- ClientReleaseEvent → D-08 健康检查/故障清理用到
  |--- ClientRegisterServiceEvent → D-09 Distro sync 用到
  ↓
D-08 (Push 推送体系 + 健康检查 + 故障自愈)
D-09 (Distro V2 协议)
```
