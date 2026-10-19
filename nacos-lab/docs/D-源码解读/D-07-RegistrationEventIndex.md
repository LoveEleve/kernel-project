# D-07: 注册处理链 + 事件驱动索引系统

> 前置依赖: D-05（gRPC 连接模型）、D-06（Client 模型 + 数据存储）
> 后续: D-08（Push 推送体系 + 健康检查）

---

## 一、概述

D-05 的 GrpcRequestAcceptor 接收请求,D-06 的 Client.addServiceInstance 存储数据。这篇追踪**完整的写路径**: 一个 registerInstance 请求到达服务端后,如何经过 Handler -> ClientOperationService -> Client 存储 -> 事件发布 -> 索引更新 -> 最终触发推送。

事件驱动 + 索引系统是 Nacos 2.x 最核心的设计模式。不理解这个模式,就无法理解为什么注册一个实例到其他客户端感知到之间,经历了复杂的异步事件链。

---

## 二、注册请求完整处理链

```
========== 层级 1: gRPC 入口 (D-05) ==========
InstanceRequest(REGISTER_INSTANCE, namespace, group, serviceName, instance)
  -> GrpcRequestAcceptor -> 解析 type=REGISTER_INSTANCE
  -> RequestHandlerRegistry -> InstanceRequestHandler

========== 层级 2: Handler 分发 ==========
InstanceRequestHandler.handle()
  -> Service.newService(namespace, groupName, serviceName, true)  // ephemeral=true
  -> InstanceUtil.setInstanceIdIfEmpty(instance, groupedServiceName)
  -> switch (request.getType()):
      case REGISTER_INSTANCE -> registerInstance(service, request, meta)

========== 层级 3: 业务处理 (EphemeralClientOperationServiceImpl) ==========
registerInstance(service, instance, clientId):
  -> NamingUtils.checkInstanceIsLegal(instance)              // 参数校验
  -> Service singleton = ServiceManager.getSingleton(service) // 获取单例(D-06)
  -> if (!singleton.isEphemeral()) -> 抛异常                  // ephemeral 类型校验
  -> Client client = clientManager.getClient(clientId)        // 找到 Client(D-06)
  -> if (!clientIsLegal(client)) -> return                    // Client 不存在或非临时 -> 跳过

  // ===== 核心: 数据存储 =====
  -> InstancePublishInfo info = getPublishInfo(instance)      // Instance -> InstancePublishInfo
  -> client.addServiceInstance(singleton, info)               // 存入 Client 内存(D-06)
    // !! addServiceInstance 内部发 ClientChangedEvent(ClientEvent) -> DistroClientDataProcessor -> Distro sync(D-09)
  -> client.setLastUpdatedTime()
  -> client.recalculateRevision()                             // 重算 Client revision(D-09 sync 用)

  // ===== 事件驱动链(三条并行) =====
  // 链 A(已在 addServiceInstance 内发出): ClientChangedEvent -> DistroClientDataProcessor -> Distro sync
  // 链 B: ClientRegisterServiceEvent -> ClientServiceIndexesManager -> 索引更新 -> ServiceChangedEvent -> Push
  // 链 C: InstanceMetadataEvent -> NamingMetadataManager -> 元数据
  -> NotifyCenter.publishEvent(
      new ClientOperationEvent.ClientRegisterServiceEvent(singleton, clientId))   // 链 B
  -> NotifyCenter.publishEvent(
      new MetadataEvent.InstanceMetadataEvent(singleton, info.getMetadataId(), false))  // 链 C

========== 层级 4: 索引更新 (ClientServiceIndexesManager) ==========
ClientServiceIndexesManager.onEvent(ClientRegisterServiceEvent):
  -> addPublisherIndexes(service, clientId)
    -> publisherIndexes.computeIfAbsent(service, new ConcurrentHashSet<>())
    -> publisherIndexes.get(service).add(clientId)
    -> NotifyCenter.publishEvent(new ServiceChangedEvent(service, true))  // incrementRevision=true

========== 层级 5: 推送触发 (D-08) ==========
NamingSubscriberServiceV2Impl.onEvent(ServiceChangedEvent):
  -> PushDelayTask(延迟合并) -> RpcPushService.push()  // 推送给每个订阅者
```

**关键设计点**: `registerInstance` 是同步阻塞的 gRPC 调用(等待 InstanceResponse),但数据传播是**异步**的(事件链 -> 索引 -> PushDelayTask -> 推送)。成功返回只意味着服务端已接收并存储,其他客户端要等异步链完成才能感知。

---

## 三、EphemeralClientOperationServiceImpl - 事件源头

### 3.1 registerInstance 的完整处理

```java
// EphemeralClientOperationServiceImpl.java
public void registerInstance(Service service, Instance instance, String clientId) {
    NamingUtils.checkInstanceIsLegal(instance);
    Service singleton = ServiceManager.getInstance().getSingleton(service);
    if (!singleton.isEphemeral()) {
        throw new NacosRuntimeException(INVALID_PARAM, "...");  // 类型不匹配
    }
    Client client = clientManager.getClient(clientId);
    if (!clientIsLegal(client, clientId)) return;

    InstancePublishInfo instanceInfo = getPublishInfo(instance);
    client.addServiceInstance(singleton, instanceInfo);  // 存入 Client
    client.setLastUpdatedTime();
    client.recalculateRevision();

    // 发布两个事件
    NotifyCenter.publishEvent(
        new ClientOperationEvent.ClientRegisterServiceEvent(singleton, clientId));
    NotifyCenter.publishEvent(
        new MetadataEvent.InstanceMetadataEvent(singleton, instanceInfo.getMetadataId(), false));
}
```

### 3.2 clientIsLegal - 防御性校验

```java
private boolean clientIsLegal(Client client, String clientId) {
    if (client == null) {
        log.warn("client already disconnect");  // 连接已断开
        return false;
    }
    if (!client.isEphemeral()) {
        log.warn("client type is not ephemeral");  // 持久 Client 不能走临时逻辑
        return false;
    }
    return true;
}
```

### 3.3 四个操作的对称性

| 操作 | Client 变更 | 发布的事件 |
|------|-----------|-----------|
| registerInstance | `addServiceInstance` | ClientRegisterServiceEvent + InstanceMetadataEvent |
| deregisterInstance | `removeServiceInstance` | ClientDeregisterServiceEvent + InstanceMetadataEvent(仅当 removed != null) |
| subscribeService | `addServiceSubscriber` | ClientSubscribeServiceEvent |
| unsubscribeService | `removeServiceSubscriber` | ClientUnsubscribeServiceEvent |

注意: `deregisterInstance` 只在 `removedInstance != null`（实际移除了东西）时才发事件--避免无意义的索引更新和推送。

---

## 四、ClientOperationEvent - 五种事件

```java
// core/v2/event/client/ClientOperationEvent.java
public class ClientOperationEvent extends Event {
    private final String clientId;
    private final Service service;
}
```

| 事件 | 携带数据 | 触发者 | 索引管理者做什么 |
|------|---------|--------|---------------|
| ClientRegisterServiceEvent | service, clientId | registerInstance | addPublisherIndexes + 发布 ServiceChangedEvent |
| ClientDeregisterServiceEvent | service, clientId | deregisterInstance | removePublisherIndexes + 发布 ServiceChangedEvent |
| ClientSubscribeServiceEvent | service, clientId | subscribeService | addSubscriberIndexes + 发布 ServiceSubscribedEvent(仅首次) |
| ClientUnsubscribeServiceEvent | service, clientId | unsubscribeService | removeSubscriberIndexes(不发下游事件) |
| ClientReleaseEvent | **Client 对象**, isNative | 连接断开/Client 释放 | 遍历清理所有 publisher + subscriber 索引 + 发布 DeregisterInstanceTraceEvent |

**ClientReleaseEvent 的特殊性**: 它携带完整的 `Client` 对象（不是 clientId + service），因为断开时需要遍历该 Client 的**所有**已发布服务和已订阅服务进行清理。`service` 字段为 null（因为是 Client 级操作,不是 Service 级）。`isNative` 区分直连断开 vs 同步数据删除。

---

## 五、NotifyCenter - 事件驱动基础设施

### 5.1 发布/订阅模型

```
NotifyCenter.publishEvent(event)
  -> 根据 event 类型查找对应的 Publisher
  -> publisher.publish(event) -> ArrayBlockingQueue.offer(event)
  -> Publisher 线程(守护线程) queue.take() -> subscriber.onEvent(event)
```

### 5.2 线程模型 - 每个事件类型一个线程

NotifyCenter 为每种事件类型创建一个独立的 `EventPublisher`(继承 Thread),拥有自己的 `ArrayBlockingQueue`和独立的守护线程:

- **事件顺序保证**: 同一事件类型的事件由同一线程串行处理(FIFO),保证注册先于注销
- **事件隔离**: 不同事件类型由不同线程处理,互不影响
- **背压控制**: 队列满时 `offer` 失败 -> 在调用方线程**同步执行** `receiveEvent`(降级,不丢事件)

### 5.3 NamingEventPublisher - 命名模块专用

`ClientServiceIndexesManager` 使用 `NamingEventPublisherFactory` 注册订阅。这个工厂有一个关键设计: **按 enclosing class 缓存 Publisher**:

```java
// NamingEventPublisherFactory.java
public EventPublisher apply(Class<? extends Event> eventType, Integer maxQueueSize) {
    // 内部类用外部类作为缓存 key
    Class<? extends Event> cachedEventType =
        eventType.isMemberClass() ? eventType.getEnclosingClass() : eventType;
    publisher.computeIfAbsent(cachedEventType, eventClass -> {
        NamingEventPublisher result = new NamingEventPublisher();
        result.init(eventClass, maxQueueSize);
        return result;
    });
    return publisher.get(cachedEventType);
}
```

**关键含义**: `ClientRegisterServiceEvent`、`ClientDeregisterServiceEvent` 等 5 个事件都是 `ClientOperationEvent` 的内部类,它们的 enclosing class 都是 `ClientOperationEvent`。因此这 5 种事件**共享同一个 NamingEventPublisher 线程**--保证了同一个 Client 的注册/注销/订阅/释放操作是**串行**的,不会乱序。

> 这是命名模块的特殊需求: "Some naming event is in order, so these event need publish by sync(with same thread and same queue)." -- NamingEventPublisherFactory Javadoc

### 5.4 事件传播链

```
EphemeralClientOperationServiceImpl
  -> publishEvent(ClientRegisterServiceEvent)
    -> NamingEventPublisher 线程(ClientOperationEvent 专用)
      -> ClientServiceIndexesManager.onEvent
        -> addPublisherIndexes
          -> publishEvent(ServiceChangedEvent)
            -> ServiceChangedEvent Publisher 线程
              -> NamingSubscriberServiceV2Impl.onEvent (D-08)
                -> PushDelayTask -> RpcPushService.push

  -> publishEvent(InstanceMetadataEvent)
    -> InstanceMetadataEvent Publisher 线程
      -> NamingMetadataManager 处理元数据
```

**两级事件**: 第一级(ClientOperationEvent)由 ClientOperationService 发布,索引管理者消费; 第二级(ServiceChangedEvent)由索引管理者发布,推送系统消费。每一级都有独立的线程,形成异步管道。

---

## 六、ClientServiceIndexesManager - 事件驱动的索引核心

### 6.1 双重索引

```java
// core/v2/index/ClientServiceIndexesManager.java
@Component
public class ClientServiceIndexesManager extends SmartSubscriber {
    // 索引1: Service -> Set<clientId> - 哪些 Client 注册了这个 Service
    private final ConcurrentMap<Service, Set<String>> publisherIndexes;
    // 索引2: Service -> Set<clientId> - 哪些 Client 订阅了这个 Service
    private final ConcurrentMap<Service, Set<String>> subscriberIndexes;
}
```

**为什么需要两个索引?**
- `publisherIndexes` -> 查询"某个 Service 有哪些实例"(遍历 clientId -> 获取 InstancePublishInfo)
- `subscriberIndexes` -> 查询"某个 Service 有哪些订阅者"(推送目标)
- 两者独立: 注册者不一定是订阅者, 订阅者不一定是注册者

### 6.2 onEvent - 事件分发

```java
@Override
public void onEvent(Event event) {
    if (event instanceof ClientReleaseEvent) {
        handleClientDisconnect((ClientReleaseEvent) event);  // 先检查子类!
    } else if (event instanceof ClientOperationEvent) {
        handleClientOperation((ClientOperationEvent) event);
    }
}
```

注意 `instanceof` 检查顺序: `ClientReleaseEvent` 是 `ClientOperationEvent` 的子类,必须先检查更具体的类型。

### 6.3 addPublisherIndexes - 索引更新 + 触发推送

```java
private void addPublisherIndexes(Service service, String clientId) {
    publisherIndexes.computeIfAbsent(service, key -> new ConcurrentHashSet<>());
    publisherIndexes.get(service).add(clientId);
    NotifyCenter.publishEvent(new ServiceChangedEvent(service, true));  // incrementRevision=true
}
```

`ServiceChangedEvent(service, true)` 构造函数内部:
- `service.renewUpdateTime()` - 更新 Service 的最后活跃时间
- `service.incrementRevision()` - 递增 Service 的 revision(不是 Client 的 revision!)

**注意区分**: Client 的 revision 由 `recalculateRevision()` 计算(用于 Distro sync); Service 的 revision 由 `incrementRevision()` 递增(用于推送判断)。

### 6.4 addSubscriberIndexes - 首次订阅才通知

```java
private void addSubscriberIndexes(Service service, String clientId) {
    subscriberIndexes.computeIfAbsent(service, key -> new ConcurrentHashSet<>());
    if (subscriberIndexes.get(service).add(clientId)) {  // Set.add 返回 true = 首次添加
        NotifyCenter.publishEvent(new ServiceSubscribedEvent(service, clientId));
    }
}
```

`ServiceSubscribedEvent` 只在**首次添加**时发布(当 `Set.add` 返回 true)。重复订阅不会触发通知。这是 Issue #5404 的修复。

### 6.5 handleClientDisconnect - 连接断开的全自动清理

```java
private void handleClientDisconnect(ClientReleaseEvent event) {
    Client client = event.getClient();

    // 1. 清理订阅索引
    for (Service each : client.getAllSubscribeService()) {
        removeSubscriberIndexes(each, client.getClientId());
    }

    // 2. 清理发布索引 + 发追踪事件
    for (Service each : client.getAllPublishedService()) {
        removePublisherIndexes(each, client.getClientId());  // 内部发 ServiceChangedEvent
        InstancePublishInfo instance = client.getInstancePublishInfo(each);
        NotifyCenter.publishEvent(new DeregisterInstanceTraceEvent(...));  // 追踪日志
    }
}
```

清理流程:
1. 遍历 `getAllSubscribeService()` -> 从 subscriberIndexes 移除
2. 遍历 `getAllPublishedService()` -> 从 publisherIndexes 移除 + 发 ServiceChangedEvent(触发推送) + 发 DeregisterInstanceTraceEvent(追踪日志)

**每个发布的服务都会触发 ServiceChangedEvent** --订阅者会收到"实例下线"的推送。这就是连接断开后其他客户端能快速感知的原因。

### 6.6 断开时发布两个不同类型的事件

客户端断开时, ClientManager(如 `ConnectionBasedClientManager`)同时发布**两个不同类型的事件**:

| 事件 | 类型 | 消费者 | 做什么 |
|------|------|--------|--------|
| `ClientReleaseEvent` | ClientOperationEvent | ClientServiceIndexesManager | 清理本地 publisher + subscriber 索引 |
| `ClientDisconnectEvent` | ClientEvent | DistroClientDataProcessor | Distro sync DELETE(同步删除到其他节点) |

> **注意**: 这是两个不同的事件体系。`ClientReleaseEvent` 是 `ClientOperationEvent` 的子类, `ClientDisconnectEvent` 是 `ClientEvent` 的子类。不要混淆--DistroClientDataProcessor 订阅的是 `ClientDisconnectEvent`, 不是 `ClientReleaseEvent`。

---

## 七、ServiceStorage - 查询路径的数据聚合

当 `getAllInstances(serviceName)` 被调用时,不是扫描所有 Client,而是通过索引聚合:

```java
// core/v2/index/ServiceStorage.java
public ServiceInfo getPushData(Service service) {
    ServiceInfo result = emptyServiceInfo(service);
    if (!ServiceManager.getInstance().containSingleton(service)) {
        return result;  // 服务不存在 -> 空 ServiceInfo
    }
    Service singleton = ServiceManager.getInstance().getSingleton(service);
    result.setHosts(getAllInstancesFromIndex(singleton));
    serviceDataIndexes.put(singleton, result);  // 缓存
    return result;
}

private List<Instance> getAllInstancesFromIndex(Service service) {
    Set<Instance> result = new HashSet<>();
    for (String clientId : serviceIndexesManager.getAllClientsRegisteredService(service)) {
        Client client = clientManager.getClient(clientId);
        if (client == null) continue;
        InstancePublishInfo info = client.getInstancePublishInfo(service);
        // InstancePublishInfo -> Instance + 合并元数据
        Instance instance = parseInstance(service, info);
        result.add(instance);
    }
    return new LinkedList<>(result);
}
```

聚合流程:
1. `publisherIndexes.get(service)` -> `Set<clientId>` (哪些 Client 注册了这个服务)
2. 遍历每个 clientId -> `clientManager.getClient(clientId)` -> `client.getInstancePublishInfo(service)`
3. `InstancePublishInfo` -> `Instance` + 合并元数据(NamingMetadataManager)
4. 结果缓存在 `serviceDataIndexes`(下次 getData 直接返回缓存)

**缓存失效**: 当服务被移除时,`removeData(service)` 清除缓存。注册/注销时不主动失效缓存--因为推送时会调用 `getPushData` 重新聚合并覆盖缓存。

---

## 八、设计哲学分析

### 8.1 事件驱动 vs 直接调用

```
直接调用: registerInstance -> addToIndex -> pushToSubscribers
事件驱动: registerInstance -> publish Event -> [索引更新] -> [推送] -> [Distro sync]
```

**为什么选事件驱动?**
1. **解耦**: 注册逻辑不需要知道推送逻辑和索引逻辑的存在。增加新监听者(如 metrics)不需要改注册代码
2. **异步**: 事件通过 BlockingQueue 异步投递,注册请求可以立即返回(不等推送完成)
3. **可扩展**: 任何模块都可以订阅 ClientOperationEvent,无需改动核心注册逻辑

**代价**: 延迟(微秒到毫秒级) + 调试复杂度(需要追踪事件链)

### 8.2 双重索引 vs 单索引

- 查询路径不同: "谁注册了这个服务" vs "谁订阅了这个服务"
- 生命周期不同: Client 可能只注册不订阅(纯服务提供方), 也可能只订阅不注册(网关)
- 清理粒度不同: ClientReleaseEvent 需要分别清理两个索引

### 8.3 为什么 5 种 ClientOperationEvent 共享一个线程?

`NamingEventPublisherFactory` 按 enclosing class 缓存,所有 `ClientOperationEvent` 子类共享一个 `NamingEventPublisher` 线程。这保证了:
- 同一个 Client 的注册/注销不会乱序(注册先于注销)
- ClientReleaseEvent(断开清理)不会和 ClientRegisterServiceEvent(注册)并发执行
- 代价: 所有 Client 操作串行执行,但注册中心 QPS 不高,够用

---

## 九、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|------|------|------|
| 1 | 注册操作直接调 addToIndex + push, 不走事件 | 紧耦合: 修改推送逻辑需要改注册代码。同步等待推送完成 -> 注册 RT 增加 | 事件驱动: publish Event -> 异步处理 |
| 2 | publish 和 subscribe 共用一个索引 | 无法区分"谁注册了"和"谁订阅了"。ClientReleaseEvent 不知道清理哪些数据 | 双重索引: publisherIndexes + subscriberIndexes |
| 3 | publisherIndexes 用 List 而不是 Set | 同一个 Client 重复注册同一个 Service -> List 有重复 -> push 重复通知 | ConcurrentHashSet 保证唯一 |
| 4 | ServiceChangedEvent 同步通知 | registerInstance 调用被阻塞, 直到所有推送完成。1000 个订阅者 -> 注册 RT 可能数秒 | 异步事件 -> PushDelayTask 延迟合并 |
| 5 | 5 种 ClientOperationEvent 各用独立线程 | 同一个 Client 的注册/注销可能乱序 -> 数据不一致 | 共享一个 NamingEventPublisher 线程 -> 顺序保证 |
| 6 | ClientReleaseEvent 不清除 publisherIndex | 断开的 Client 的实例永远留在索引中 -> 查询返回不存在的实例 | handleClientDisconnect 遍历清理所有索引 |
| 7 | deregisterInstance 无论是否移除都发事件 | 空操作(实例不存在)也触发索引更新和推送 -> 浪费 | 仅 removedInstance != null 时发事件 |

---

## 十、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|------|------|------|
| 1 | 认为 registerInstance 返回 = 其他客户端已感知 | 事件还在 BlockingQueue 中排队 -> 其他客户端还没收到推送 | 注册后使用 subscribe 监听推送, 不依赖即时可见性 |
| 2 | 在 onEvent 中做耗时操作 | 阻塞 NamingEventPublisher 线程 -> 所有 Client 操作堆积 -> 系统卡死 | onEvent 只做轻量操作; 耗时操作通过 PushDelayTask 异步化 |
| 3 | 混淆 Service revision 和 Client revision | Service revision 由 incrementRevision 递增(推送判断); Client revision 由 recalculateRevision 计算(Distro sync) | 两者独立, 用途不同 |
| 4 | 不理解 ClientReleaseEvent 的双重清理 | 以为只需清理 publisherIndex 不需 subscriberIndex -> subscription 泄漏 | handleClientDisconnect 同时清理两者 |
| 5 | 在 registerInstance 之后立即 deregisterInstance | 两个操作通过同一个 NamingEventPublisher 串行处理 -> 不会乱序, 但有微秒级延迟 | 共享线程保证顺序 |

---

## 十一、面试 Q&A

### Q1: 注册请求从 gRPC 到其他客户端感知, 完整路径上做了什么?

**面试官意图**: 考察端到端全局理解。

Handler(InstanceRequestHandler) -> 业务处理(EphemeralClientOperationServiceImpl: addServiceInstance + recalculateRevision) -> 事件发布(ClientRegisterServiceEvent) -> 索引更新(ClientServiceIndexesManager: addPublisherIndexes) -> 事件再发布(ServiceChangedEvent) -> 推送(NamingSubscriberServiceV2Impl -> PushDelayTask -> RpcPushService) -> 客户端(NamingPushRequestHandler -> ServiceInfoHolder -> listener.onEvent)。

**追问**: "哪些步骤同步, 哪些异步?"
- gRPC 请求/响应同步, 事件通知/Push/Distro sync 异步。

### Q2: 事件驱动索引系统怎么设计的? 为什么用双重索引?

publisherIndexes: Service -> Set<clientId> - 查询"谁注册了"。subscriberIndexes: Service -> Set<clientId> - 查询"谁订阅了"。分开的原因: 查询路径不同、生命周期不同、清理粒度不同。事件驱动: ClientRegisterServiceEvent -> addPublisherIndexes -> ServiceChangedEvent -> push。

### Q3: NotifyCenter 为什么让 5 种 ClientOperationEvent 共享一个线程?

**面试官意图**: 考察事件驱动 + 线程模型。

单线程串行处理 -> 保证同一 Client 的注册/注销/释放不会乱序。NamingEventPublisherFactory 按 enclosing class 缓存, 所有 ClientOperationEvent 子类共享一个 NamingEventPublisher。代价: 所有 Client 操作串行, 但注册中心 QPS 不高, 够用。

### Q4: ClientReleaseEvent 的处理流程是什么?

ClientReleaseEvent 携带完整 Client 对象 -> handleClientDisconnect -> 遍历 getAllSubscribeService 清理 subscriberIndexes + 遍历 getAllPublishedService 清理 publisherIndexes + 每个 published service 发 ServiceChangedEvent(触发推送) + DeregisterInstanceTraceEvent(追踪日志)。

### Q5: 注册一个实例到订阅者感知, 延迟是多少?

**面试官意图**: 考察端到端延迟理解。

路径: 注册同步(gRPC 确认) + BlockingQueue 处理(微秒级) + PushDelayTask 合并窗口(默认 500ms) + gRPC push 网络。典型值: 毫秒到几十毫秒。极端情况: BlockingQueue 满时 publish 阻塞 -> 注册请求延迟增加。

---

## 十二、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| EphemeralClientOperationServiceImpl | 临时实例注册/注销/订阅核心, 事件源头 | `core/v2/service/impl/EphemeralClientOperationServiceImpl.java` |
| ClientOperationEvent | 5 种客户端操作事件(Register/Deregister/Subscribe/Unsubscribe/Release) | `core/v2/event/client/ClientOperationEvent.java` |
| ClientServiceIndexesManager | SmartSubscriber, 维护 publisherIndexes + subscriberIndexes 双重索引 | `core/v2/index/ClientServiceIndexesManager.java` |
| ServiceChangedEvent | Service 实例列表变更事件, 触发推送(incrementRevision=true) | `core/v2/event/service/ServiceEvent.java` |
| ServiceStorage | 查询路径聚合, 遍历 publisherIndexes 收集实例 + 缓存 | `core/v2/index/ServiceStorage.java` |
| NotifyCenter | 事件总线, 每种事件类型一个 Publisher 线程 | `common/notify/NotifyCenter.java` |
| NamingEventPublisherFactory | 按 enclosing class 缓存, ClientOperationEvent 子类共享线程 | `common/notify/NamingEventPublisherFactory.java` |

---

## 九、测试分析

### 9.1 ClientServiceIndexesManagerTest

`naming/src/test/java/com/alibaba/nacos/naming/core/v2/index/ClientServiceIndexesManagerTest.java`

| 测试方法 | 验证内容 |
|---------|---------|
| `testAddPublisher` | 注册 Instance -> 添加到 `publisherIndexes` |
| `testAddSubscriber` | 订阅 Service -> 添加到 `subscriberIndexes` |
| `testRemovePublisher` | 注销 Instance -> 从索引中移除 |
| `testRemoveSubscriber` | 取消订阅 -> 从索引中移除 |
| `testGetPublisher` | 根据 service + client 获取 publisher 列表 |
| `testGetSubscriber` | 根据 service 获取 subscriber 列表 |
| `testEventPublish` | ClientOperationEvent -> 触发 ServiceChangedEvent -> push |

## 十、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Observer** | 事件驱动: `ClientOperationEvent` -> `ClientServiceIndexesManager` -> `ServiceChangedEvent` | 注册/注销事件串联成完整处理链 |
| **Index Table** | `publisherIndexes` + `subscriberIndexes` 双重索引 | Service -> Client 的正向/反向索引 |
| **Pub/Sub** | `NotifyCenter` 事件总线 | 生产和消费解耦 |
| **Chain of Responsibility** | `ClientServiceIndexesManager` -> `DistroClientDataProcessor` -> `PushExecutor` | 事件沿注册链传播 |

## 十一、本 Lab 中的实际映射

| Lab 场景 | 对应源码 |
|---------|---------|
| A-01 注册 `registerInstance` | `ClientOperationEvent.ClientRegisterServiceEvent` -> `ClientServiceIndexesManager.addPublisher()` |
| A-01 `POST /discovery/set-unhealthy` | 更新实例数据 -> `ClientEvent.ClientChangedEvent` -> 触发 Diff |
| A-03 查询 `GET /discovery/services` | `ServiceStorage` 遍历 `publisherIndexes` 收集实例 |
