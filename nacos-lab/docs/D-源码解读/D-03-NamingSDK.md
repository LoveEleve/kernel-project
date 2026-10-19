# D-03: NacosNamingService SDK 全链路

> 前置依赖: D-01（NamingService API） | 后续: D-05（gRPC 连接模型）、D-07（注册处理链）
> 与 D-04 对比: Naming SDK 走 gRPC 推送 + redo 保障;Config SDK 走 gRPC 服务端推送 + 四级降级

---

## 一、概述

D-01 定义了 NamingService 接口契约。这篇追踪这个接口在客户端 SDK 中如何实现——registerInstance 的参数归一化、代理路由决策、gRPC 请求构建、失败重试、本地缓存、推送回调。四条核心链路（注册/注销/发现/订阅）覆盖了 Naming SDK 的全部行为。

```
client/src/main/java/com/alibaba/nacos/client/naming/NacosNamingService.java  （496 行）
```

## 二、NacosNamingService 初始化

SDK 在构造时创建了后续所有组件：

```java
// NacosNamingService.init() — NacosNamingService.java:98-105（节选）
    this.notifierEventScope = UUID.randomUUID().toString();           // 事件 scope
    this.changeNotifier = new InstancesChangeNotifier(this.notifierEventScope);   // 回调桥接
    NotifyCenter.registerToPublisher(InstancesChangeEvent.class, 16384);
    NotifyCenter.registerSubscriber(changeNotifier);
    this.serviceInfoHolder = new ServiceInfoHolder(namespace, this.notifierEventScope, nacosClientProperties); // 缓存
    this.clientProxy = new NamingClientProxyDelegate(this.namespace, serviceInfoHolder, nacosClientProperties,
            changeNotifier); // 代理
```

| 组件 | 职责 |
|------|------|
| `ServiceInfoHolder` | 本地缓存 ServiceInfo |
| `InstancesChangeNotifier` | 推送事件 → 用户回调桥接 |
| `NamingClientProxyDelegate` | HTTP/gRPC 双协议路由代理 |

**每个 NacosNamingService 实例都会创建一套完整的 gRPC 连接 + 独立线程池**。这就是为什么多个实例会导致连接数爆炸。

为什么选择"每个实例独立"而不是"共享连接"的设计？因为 Nacos SDK 需要支持**多命名空间（namespace）**——同一个 JVM 可能同时连接多个 Nacos 集群（开发/测试/生产）。如果共享连接，不同 namespace 的数据会混在一起，隔离性无法保证。独立实例 = 每个 namespace 独立的 gRPC channel + 独立缓存 + 独立事件通知——虽然资源开销更大，但保证了**命名空间级别的隔离**。

---

## 三、注册链路 — registerInstance 从 API 到网络

```
NacosNamingService.registerInstance(serviceName, groupName, instance)
  
  // 第1步: 参数校验 + 默认值补全（客户端完成，服务端收完整参数）
  → NamingUtils.checkInstanceIsLegal(instance)
    // 验证 IP 格式、port 范围 (0-65535)、clusterName 字符集 (^[0-9a-zA-Z-]+$)
  → checkAndStripGroupNamePrefix(instance, groupName)
    // 去掉 instance.serviceName 中的 "group@@" 前缀

  // 第2步: 代理层路由决策（决定走 gRPC 还是 HTTP）
  → clientProxy.registerService(serviceName, groupName, instance)
    → NamingClientProxyDelegate.getExecuteClientProxy(instance)
    // NamingClientProxyDelegate.java:197-202，源码如下：
```

### 3.1 代理路由决策

```java
// NamingClientProxyDelegate.java:197-202
private NamingClientProxy getExecuteClientProxy(Instance instance) {
    if (instance.isEphemeral() 
        || grpcClientProxy.isAbilitySupportedByServer(AbilityKey.SERVER_SUPPORT_PERSISTENT_INSTANCE_BY_GRPC)) {
        return grpcClientProxy;    // 99% 场景：走 gRPC
    }
    return httpClientProxy;        // fallback：旧版服务端 + 持久实例
}
```

| 条件 | 选择的代理 | 场景 |
|------|----------|------|
| `ephemeral=true` | `grpcClientProxy` | 临时实例（默认，绝大多数） |
| `ephemeral=false` + 服务端支持 gRPC 持久 | `grpcClientProxy` | 持久实例 + Nacos 2.x 新能力 |
| `ephemeral=false` + 服务端不支持 | `httpClientProxy` | 向后兼容旧版 |

**为什么需要这个路由决策？** gRPC 是 Nacos 2.x 的主链路（长连接 + 连接即生命周期），HTTP 是向后兼容 1.x 的 fallback。通过 `AbilityKey` 做能力协商——服务端声明支持的功能，客户端据此决定用哪种协议。这是一种**渐进式升级**策略：不是直接砍掉 HTTP，而是通过能力声明让服务端控制何时完全切到 gRPC。

### 3.2 gRPC 请求构建与发送

```
NamingGrpcClientProxy.registerServiceForEphemeral(serviceName, groupName, instance)

  // 第1步: write-ahead -- 先缓存 redo(registered=false),再发请求
  -> redoService.cacheInstanceForRedo(serviceName, groupName, instance)
     -> InstanceRedoData(registered=false) -> registeredInstances.put(key, redoData)

  // 第2步: 构建 gRPC 请求并发送
  -> doRegisterService(serviceName, groupName, instance)
    -> 构建 InstanceRequest(type=REGISTER_INSTANCE, namespace, groupName, serviceName, instance)
    -> rpcClient.request(request, requestTimeout)
      -> Protobuf 序列化 -> gRPC 发送到服务端 :9848 端口
      -> 阻塞等待 InstanceResponse

  // 第3步: 处理结果
  -> 成功 -> redoService.instanceRegistered(serviceName, groupName)  // 标记 registered=true
  -> 失败 -> registered 保持 false -> RedoScheduledTask 下次扫描时重试
```

**关键设计点**：`registerInstance` 是**同步阻塞**的 gRPC 调用——调用方等待服务端确认后才返回。但数据传播是**异步**的（事件链 → 索引 → PushDelayTask → 推送），其他客户端要等这个异步链完成才能感知到新实例。

---

## 四、注销链路 — 与注册对称

```
NacosNamingService.deregisterInstance(serviceName, groupName, instance)
  → NamingUtils.checkInstanceIsLegal(instance)
  → checkAndStripGroupNamePrefix(instance, groupName)
  → getExecuteClientProxy(instance) → grpcClientProxy
  → NamingGrpcClientProxy.deregisterService()
    → 构建 InstanceRequest(type=DE_REGISTER_INSTANCE)
    → rpcClient.request() → gRPC 发送
     -> 发送前 redoService.instanceDeregister() (标记 unregistering)
     -> 成功 -> redoService.instanceDeregistered(key)  // 标记已完成
```

与注册唯一的区别是成功后会从 redo 队列移除——因为该操作已完成，不需要再重试。

---

## 五、发现链路 — 缓存优先 + subscribe 分支

```
NacosNamingService.getAllInstances(serviceName, groupName, clusters, subscribe)

  // 分支1: subscribe=true — 本地缓存优先，缓存未命中则自动订阅
  → 如果 subscribe=true:
    → serviceInfoHolder.getServiceInfo(serviceName, groupName, clusterString)
    → if (缓存命中且已订阅):
        → 直接返回缓存中的 hosts 列表
    → if (缓存未命中 || 尚未订阅):
        → clientProxy.subscribe(serviceName, groupName, clusterString)
          → gRPC SubscribeServiceRequest → 服务端返回当前全量 ServiceInfo
          → serviceInfoHolder.processServiceInfo(result)

  // 分支2: subscribe=false — 只查询不订阅
  → 如果 subscribe=false:
    → clientProxy.queryInstancesOfService(serviceName, groupName, clusterString, false)
      → gRPC ServiceQueryRequest → 服务端返回 ServiceInfo → 返回 hosts
```

**为什么先查缓存而不是先查服务端？** 注册中心是 AP 语义，允许短暂延迟。推送机制保证缓存及时更新，命中率很高。如果每次查询都走 gRPC，高频场景下服务端会被击穿。

`selectInstances` 在以上结果基础上做客户端过滤：

```java
// NacosNamingService.java:317-332
private List<Instance> selectInstances(ServiceInfo serviceInfo, boolean healthy) {
    List<Instance> list = serviceInfo.getHosts();
    Iterator<Instance> iterator = list.iterator();
    while (iterator.hasNext()) {
        Instance instance = iterator.next();
        if (healthy != instance.isHealthy() || !instance.isEnabled() || instance.getWeight() <= 0) {
            iterator.remove();
        }
    }
    return list;
}
```

---

## 六、订阅链路 — 回调注册 + gRPC 订阅 + Push 监听

```
NacosNamingService.subscribe(serviceName, groupName, clusters, listener)

  // 第1步: 注册用户回调
  → changeNotifier.registerListener(groupName, serviceName, clusterString, listener)
    → listener 存入 ConcurrentHashMap<serviceKey, List<EventListener>>

  // 第2步: 发起 gRPC 订阅 + 首次拉取
  → clientProxy.subscribe(serviceName, groupName, clusterString)
    → serviceInfoUpdateService.scheduleUpdateIfAbsent(...)   // 定时更新兜底
    → grpcClientProxy.subscribe(serviceName, groupName, clusters)
      → 构建 SubscribeServiceRequest(subscribe=true)
      -> gRPC unary 请求(rpcClient.request) -> 服务端
      → 服务端返回当前全量 ServiceInfo
    → serviceInfoHolder.processServiceInfo(result)
      → 更新缓存 → 对比新旧 hosts → 只有变化时通知

// 推送触发的回调链:
// 服务端 Push -> bi-di stream 推送 NotifySubscriberRequest -> NamingPushRequestHandler(ServerRequestHandler)
//   → serviceInfoHolder.processServiceInfo(newServiceInfo)
//     -> Map<ip:port> 三路差分(newHosts/remvHosts/modHosts)
//     → NotifyCenter.publishEvent(InstancesChangeEvent)
//       → InstancesChangeNotifier.onEvent
//         → 遍历注册的 Listener
//         → listener.onEvent(new NamingEvent(serviceName, newInstances))
```

---

## 七、GrpcRedoService — 断线重连保护

redo 机制是 Nacos SDK 的"最终一致性"保险：

```java
// RedoScheduledTask 核心循环 (默认每 3s 执行一次)
// NamingGrpcRedoService.java, DEFAULT_REDO_DELAY_TIME=3000ms (Constants.java:222)
for (InstanceRedoData each : registeredInstances.values()) {
    if (!redoService.isConnected()) continue;       // 还没连上，跳过
    if (each.isRegistered() && !each.isExpected()) continue;  // 已确认，跳过
    clientProxy.redo(each);   // 需要重试：重新发送注册/注销/订阅
}
```

**完整生命周期**：

```
onDisConnect(connection):
  → connected = false
  → 所有 InstanceRedoData.registered = false   // 标记所有实例为"未确认"

onConnected(connection):
  → connected = true                            // 连接恢复
  → RedoScheduledTask 下次扫描 → 发现 registered=false → 重新发送

定时兜底:
  → RedoScheduledTask 每 3s 执行一次
  → 即使 onConnected 不触发，定时扫描也会发现未确认的操作
```

**哪些操作进 redo**：注册、注销、订阅（有状态操作，需要 at-least-once 保证）
**哪些不进 redo**：查询（无状态操作，失败了下次再查就行）

---

## 八、ServiceInfoHolder — 客户端本地缓存

```java
// 数据结构
ConcurrentHashMap<String, ServiceInfo> cacheMap;  // key = group@@service@@clusters
```

**processServiceInfo 的差分逻辑**：收到新的 ServiceInfo 后，与缓存中的旧版本对比 hosts 列表。只有 hosts 发生变化时才发布 `InstancesChangeEvent`——避免无意义的回调触发。

**缓存过期机制**：`ServiceInfo.expired()` — `(now - lastRefTime > cacheMillis)`。`cacheMillis` 默认 1000ms，服务端返回时会覆盖。三种刷新方式：
- 主动查询：缓存过期 → 重新向服务端查询
- 被动推送：服务端 push → 更新缓存
- 定时兜底：`ServiceInfoUpdateService` 定期检查过期缓存

**为什么客户端缓存选择"推送驱动 + 定时兜底"而不是"短周期轮询"？** 服务实例的变更频率差异巨大——低流量服务可能数小时不变，高流量服务可能秒级变化。如果统一用短轮询（如每 5s 查询一次），低流量服务的 99% 查询返回"没变化"——浪费带宽和 CPU。推送驱动让"没变化"的查询成本降为零——只有真的变化时才触发更新。定时兜底（ServiceInfoUpdateService）是推送丢失时的最后保障。

---

## 九、设计哲学分析

### 为什么参数归一化在客户端完成？

服务端收到的请求永远是完整参数（Null 字段已补全默认值），不需要再处理"如果 groupName 为空则用 DEFAULT_GROUP"这种逻辑。代价是客户端的重载链较长——但这是一次性复杂度，换取服务端逻辑的简洁。

### HTTP/gRPC 双协议路由 — 渐进式升级

不是直接砍掉 HTTP，而是通过 `AbilityKey` 做能力协商。老服务端说"我不支持 gRPC 持久实例" → 客户端就走 HTTP fallback。新服务端声明支持 → 全部走 gRPC。这种策略让升级是**可逆的、可控的**。

---

## 十、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | 代理层只用一种协议 | HTTP 无长连接，gRPC 对防火墙不友好。单一协议无法兼顾所有场景。 | gRPC 主链路 + HTTP fallback |
| 2 | 不缓存 ServiceInfo，每次查询走服务端 | QPS 1000+ 集群击穿 Nacos。 | 本地缓存 + 推送更新 |
| 3 | 不实现 redo | 注册/注销/订阅可能丢失。 | redo 队列 at-least-once |
| 4 | redo 队列无大小限制 | 长时间断开 -> OOM + 恢复后流量击穿。 | 无显式上限,但按 `groupedServiceName` 去重(每个服务仅一条),单客户端注册服务数有限,实际堆积可控 |
| 5 | 推送每次都全量通知 | Listener 频繁触发，CPU 100%。 | 差分对比后才触发 |
| 6 | selectInstances 直接修改原始 hosts 列表 | 并发修改 ServiceInfo 内部状态 -> 线程不安全。 | **不会**:`ServiceInfo.getHosts()` 返回 `new ArrayList<>(hosts)` 防御性副本,iterator.remove 只改副本 |
| 7 | push handler 和 subscribe 不同时注册 | 推送来了无人处理。 | `grpcClientProxy.start()` 时同步注册 |

---

## 十一、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|---|---|---|
| 1 | 创建多个 NacosNamingService 实例 | 每个实例独立 gRPC 连接 + 线程池 → 资源爆炸 | 无自动对策 |
| 2 | 注册后立即 shutDown() | 连接来不及建立，注册进 redo 但连接已关闭 | shutDown 不 flush redo |
| 3 | subscribe 同一服务多次 | 重复 gRPC subscribe 请求 | `isSubscribed()` 可检查 |
| 4 | 以为 selectInstances 返回的列表会随缓存自动更新 | 返回的是**快照副本**,后续实例变更不会反映在已返回的列表里 | 需重新调用或用 subscribe 监听变更 |
| 5 | redo 堆积时重启 | redo 内存队列丢失 | redo 是内存队列 |

### 可运行的错误实验

**实验 — 多实例导致连接数爆炸：**
```java
// 错误: 每次调用都 new
public Instance getInstance(String serviceName) throws NacosException {
    NamingService ns = new NacosNamingService("127.0.0.1:8848");  // 新建!
    Instance instance = ns.selectOneHealthyInstance(serviceName);
    ns.shutDown();
    return instance;
}
// 100 次调用 = 100 个 gRPC channel + 100 个 EventLoop + 100 个 redo 线程

// 正确: Spring Bean 单例
@Component
public class NamingClient {
    private final NamingService ns = new NacosNamingService("127.0.0.1:8848");
    @PreDestroy void destroy() { ns.shutDown(); }
}
```

---

## 十二、面试 Q&A

### Q1: registerInstance 是同步还是异步的？

**面试官意图**：考察对调用模型的精确理解。

调用层面同步（阻塞等待 gRPC response），数据生效异步（事件链 → 索引 → PushDelayTask → 推送）。成功返回只意味着服务端已接收，其他客户端可能还没感知到。

### Q2: redo 机制是怎么工作的？

**面试官意图**：考察 at-least-once 语义。

`onDisConnect` 标记所有实例为未确认 -> `RedoScheduledTask`(每 3s) 扫描 -> 重新发送。注册操作天然幂等(服务端 `addServiceInstance` 是 Map.put 覆盖写),重复注册不会产生副作用。

### Q3: 缓存是怎么避免脏读的？

**面试官意图**：考察 AP 系统的缓存一致性。

推送驱动更新 + 定时过期兜底。不是分布式锁或强一致性保证。一致性窗口 = 推送延迟 + 缓存过期周期。

### Q4: HTTP/gRPC 双协议路由设计的好处？

**面试官意图**：考察兼容性设计。

通过 `AbilityKey` 做能力协商——服务端声明支持的功能，客户端据此选择协议。这是一种可逆、可控的渐进式升级策略。

### Q5: 客户端网络间歇性断开时 SDK 的行为？（联动 D-05）

**面试官意图**：考察连接生命周期。

断开 → `onDisConnect` 标记未确认 → 连接恢复 → `RedoScheduledTask` 扫描 → redo。断开期间服务端可能标记实例不健康（取决于 keep-alive），恢复后 redo 补偿。

---

## 十三、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| NacosNamingService | NamingService 的 SDK 实现，496 行 | `client/.../NacosNamingService.java` |
| NamingClientProxyDelegate | HTTP/gRPC 双协议路由代理 | `:197-202`（getExecuteClientProxy） |
| GrpcRedoService | 断线重连保护，3s 扫描 redo | `redo/NamingGrpcRedoService.java` |
| ServiceInfoHolder | 客户端缓存，ConcurrentHashMap | `cache/ServiceInfoHolder.java` |
| InstancesChangeNotifier | 推送 → 用户回调桥接 | `event/InstancesChangeNotifier.java` |
| DEFAULT_REDO_DELAY_TIME | redo 扫描间隔 = 3000ms | `Constants.java:222` |

---

## 十四、测试分析

### 14.1 NacosNamingServiceTest (965 行)

`client/src/test/java/com/alibaba/nacos/client/naming/NacosNamingServiceTest.java`

**测试分类**:

| 类别 | 测试方法数 | 覆盖内容 |
|------|:--------:|---------|
| 注册 | 6 | `registerInstance` 各种重载（ip+port / Instance / groupName / clusterName）|
| 注销 | 4 | `deregisterInstance` 各种重载 |
| 查询 | 6 | `getAllInstances` / `selectInstances` / `selectOneHealthyInstance` |
| 订阅 | 6 | `subscribe` / `unsubscribe` + 事件回调 |
| 服务列表 | 2 | `getServicesOfServer` / `getSubscribeServices` |
| 批量操作 | 2 | `batchRegisterInstance` / `batchDeregisterInstance` |

**关键测试示例**:

```java
// 验证 registerInstance(serviceName, ip, port) 委托给 proxy.registerService
@Test
public void testRegisterInstance1() throws NacosException {
    client.registerInstance("service1", "1.1.1.1", 10000);
    verify(proxy, times(1)).registerService(eq("service1"), eq(Constants.DEFAULT_GROUP),
        argThat(instance -> instance.getIp().equals("1.1.1.1") && instance.getPort() == 10000
            && Math.abs(instance.getWeight() - 1.0) < 0.01f && instance.getClusterName()
            .equals(Constants.DEFAULT_CLUSTER_NAME)));
}
```

**覆盖的边界情况**:
- 默认值: `registerInstance(name, ip, port)` 自动填充 weight=1.0, cluster=DEFAULT, group=DEFAULT_GROUP
- 空列表: `getAllInstances`/`selectInstances` 在无实例时返回空列表而不抛异常
- 订阅: `subscribe` 回调线程池执行，不阻塞主线程(Executor 在 Listener 中指定)

## 十五、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Delegate** | `NamingClientProxyDelegate` | 将注册/注销/查询委托给 HTTP 或 gRPC 代理 |
| **Strategy** | `NamingClientProxyDelegate.getExecuteClientProxy()` | 根据 Instance.ephemeral 选择 gRPC(临时)或 HTTP(持久) |
| **Redo Log** | `NamingGrpcRedoService` | 断线后保存未完成的操作，重连后重新执行 |
| **Observer** | `InstancesChangeNotifier` + 用户 `EventListener` | 实例变更时通知订阅者 |
| **Cache-Aside** | `ServiceInfoHolder` | 缓存 ServiceInfo，过期后主动查询 |
| **Two-Phase** | redo 机制: `onDisConnect` 标记 + `onReconnected` 执行 | 断连标记未确认 -> 重连后补偿 |

## 十六、本 Lab 中的实际映射

| Lab 场景 | 对应源码 |
|---------|---------|
| A-01 `GET /discovery/status` 查看注册状态 | `NacosNamingService.getServicesOfServer()` + `selectInstances()` |
| A-03 `GET /discovery/services` | `NamingClientProxyDelegate` -> gRPC `ServiceListRequest` |
| A-07 nacos-lab 注册到 Nacos | `NacosNamingService.registerInstance()` -> `NamingClientProxyDelegate` |
| B-04 `NamingServiceInterceptor` 拦截注册 | 在 `NacosNamingService.registerInstance()` 调用 `clientProxy.registerService()` 前拦截 |
