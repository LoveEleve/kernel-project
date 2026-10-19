# D-03 详细规划: NacosNamingService SDK 全链路

> 阶段: Phase 2 — 客户端 SDK 层 | 预期产出: `md/D-03-NamingSDK.md` | 状态: 待审核

---

## 一、为什么需要这篇

从 D-01 的 NamingService 接口到服务端处理之间，有一个完整的客户端 SDK 实现。这篇追踪四条核心链路（注册/注销/发现/订阅）在 SDK 内部如何完成：从 API 调用到网络字节流的完整过程。

理解 SDK 链路是理解服务端处理的必要前提——不知道客户端发了什么请求、怎么发的，就不可能理解服务端为什么要那样处理。

前置依赖: D-01 (NamingService API + Instance/ServiceInfo 数据模型)

> **与 D-04(Config SDK) 的架构对比**: Naming SDK 走 gRPC 双向流推送 + redo 队列保障; Config SDK 走 HTTP 长轮询 + 四级降级容灾。两者通信模型完全不同,根源在于: 服务实例变更频繁(秒级),配置变更极低频(小时/天级)。

---

## 二、源码阅读清单 (按调用链顺序)

| 序号 | 源码文件 | 行数 | 必须阅读的内容 | 阅读目的 |
|------|----------|------|---------------|----------|
| 1 | `client/src/main/java/.../client/naming/NacosNamingService.java` | 497 | 全方法实现,重点是重载链(registerInstance:112-147, subscribe:388-412, getAllInstances:240-258, shutDown:471-477) | 理解 API → 代理层的调用链 |
| 2 | `client/src/main/java/.../client/naming/remote/NamingClientProxyDelegate.java` | 217 | getExecuteClientProxy(197-202), registerService(96-99), subscribe(166-177) | 理解路由决策 + HTTP/gRPC 二层代理 |
| 3 | `client/src/main/java/.../client/naming/remote/gprc/NamingGrpcClientProxy.java` | ~300 | start(113-119), 注册/注销/查询/订阅的 gRPC 请求构建 | 理解 gRPC 代理的实现细节 |
| 4 | `client/src/main/java/.../client/naming/remote/gprc/redo/NamingGrpcRedoService.java` | ~180 | onConnected(88-91), onDisConnect(93-101), cacheDataForRedo(), RedoScheduledTask | 理解断线重连和 redo 机制 |
| 5 | `client/src/main/java/.../client/naming/cache/ServiceInfoHolder.java` | ~200 | getServiceInfo(), processServiceInfo(), shutdown() | 理解客户端本地缓存机制 |
| 6 | `client/src/main/java/.../client/naming/event/InstancesChangeNotifier.java` | ~150 | registerListener(), onEvent(), deregisterListener() | 理解推送→用户回调的桥接 |
| 7 | `client/src/main/java/.../client/naming/core/Balancer.java` | ~60 | RandomByWeight.selectHost() | 理解客户端自带负载均衡 |

---

## 三、分析要点详解

### 3.1 四条核心链路的完整调用链

#### A) 注册链路 (registerInstance → gRPC → redo 队列)

```
NacosNamingService.registerInstance(serviceName, groupName, instance)
  // 步骤1: 参数校验 + 默认值补全
  → NamingUtils.checkInstanceIsLegal(instance)           (验证 IP 格式 + port 范围)
  → checkAndStripGroupNamePrefix(instance, groupName)   (去掉 serviceName 中的 group@@ 前缀)
  
  // 步骤2: 代理层路由决策
  → clientProxy.registerService(serviceName, groupName, instance)
    → NamingClientProxyDelegate.getExecuteClientProxy(instance)
      → if (instance.isEphemeral() || server supports grpc persistent)
        → 返回 grpcClientProxy    (99% 场景：临时实例走 gRPC)
      → else
        → 返回 httpClientProxy    (fallback: 持久实例且服务端不支持 gRPC 持久实例)
    
    // 步骤3: gRPC 代理构建并发送请求
    → NamingGrpcClientProxy.registerService(serviceName, groupName, instance)
      → 构建 InstanceRequest(type=REGISTER_INSTANCE, namespace, groupName, serviceName, instance)
      → rpcClient.request(request, requestTimeout)
        → gRPC 序列化 → 发送到服务端
        → 收到 InstanceResponse(success)
      → 如果失败(NacosException)
        → 加入 redo 队列: redoService.cacheDataForRedo(serviceName, groupName, instance)
```

**关键设计点**:
- `getExecuteClientProxy` 的路由决策在 NamingClientProxyDelegate:197-202
- 临时实例(ephemeral=true)始终走 gRPC
- 持久实例默认走 HTTP(除非服务端声明支持 gRPC 持久实例)
- 注册失败后的 redo 机制在 GrpcRedoService

#### B) 注销链路（与注册完全对称的取消）

```
NacosNamingService.deregisterInstance(serviceName, groupName, instance)
  → NamingUtils.checkInstanceIsLegal(instance)
  → checkAndStripGroupNamePrefix(instance, groupName)
  → clientProxy.deregisterService(serviceName, groupName, instance)
    → getExecuteClientProxy(instance) → grpcClientProxy 或 httpClientProxy
    → NamingGrpcClientProxy.deregisterService()
      → 构建 InstanceRequest(type=DE_REGISTER_INSTANCE)
      → rpcClient.request() → gRPC 发送
      → 失败 → redoService.cacheDataForRedo()
      → 成功 → redoService.instanceDeregistered() (从 redo 队列移除)
```

#### C) 发现链路(getAllInstances/selectInstances → 本地缓存 → gRPC 查询)

```
NacosNamingService.getAllInstances(serviceName, groupName, clusters, subscribe)
  // 步骤1: 本地缓存优先
  → 如果 subscribe=true:
    → serviceInfoHolder.getServiceInfo(serviceName, groupName, clusterString)
    → if (缓存未命中 || 尚未订阅)
      → clientProxy.subscribe(serviceName, groupName, clusterString)  // 发起订阅+首次拉取
    → if (缓存命中且已订阅)
      → 直接返回缓存中的 hosts 列表
  
  // 步骤2: 不走订阅,直接查询
  → 如果 subscribe=false:
    → clientProxy.queryInstancesOfService(serviceName, groupName, clusterString, false)
      → NamingGrpcClientProxy → 构建 ServiceQueryRequest → gRPC 请求
      → 返回 ServiceInfo(含 hosts 列表)

  // 步骤3: 过滤(仅 selectInstances)
  → selectInstances(ServiceInfo, healthy)
    → 遍历 hosts
    → 过滤条件: healthy 必须匹配 + enabled=true + weight>0
    → 返回过滤后的列表
    → ⚠️ 直接在原 list 上 iterator.remove() — 非线程安全!
```

#### D) 订阅链路(subscribe → ChangeNotifier 注册 → gRPC 订阅 → Push 监听)

```
NacosNamingService.subscribe(serviceName, groupName, clusters, listener)
  // 步骤1: 注册用户回调
  → changeNotifier.registerListener(groupName, serviceName, clusterString, listener)
    // listener 存入 ConcurrentHashMap<String, List<EventListener>>
  
  // 步骤2: 发起服务端订阅
  → clientProxy.subscribe(serviceName, groupName, clusterString)
    → NamingClientProxyDelegate.subscribe()
      // 2.1 开启定时更新(兜底)
      → serviceInfoUpdateService.scheduleUpdateIfAbsent(serviceName, groupName, clusters)
      // 2.2 尝试从本地缓存获取
      → result = serviceInfoHolder.getServiceInfoMap().get(serviceKey)
      // 2.3 缓存未命中或未订阅 → gRPC 订阅
      → if (null == result || !isSubscribed(...)):
        → result = grpcClientProxy.subscribe(serviceName, groupName, clusters)
          → 构建 SubscribeServiceRequest(subscribe=true)
          → gRPC Bi-di Stream → 服务端
          → 服务端返回当前全量 ServiceInfo
          → rpcClient 注册 NamingPushRequestHandler 监听推送
      // 2.4 更新缓存
      → serviceInfoHolder.processServiceInfo(result)
      
// 推送触发客户端回调:
// 服务端 Push → gRPC Bi-di Stream → NamingPushRequestHandler 
//   → serviceInfoHolder.processServiceInfo(newServiceInfo)
//     → 与本地缓存差分(checksum 或 hosts 对比)
//     → NotifyCenter.publishEvent(InstancesChangeEvent)
//       → InstancesChangeNotifier.onEvent
//         → 遍历注册的 Listener
//         → listener.onEvent(new NamingEvent(serviceName, newInstances))
```

### 3.2 NamingClientProxyDelegate 的二层代理设计

这是 Nacos 客户端最核心的路由决策：

```java
private NamingClientProxy getExecuteClientProxy(Instance instance) {
    if (instance.isEphemeral() 
        || grpcClientProxy.isAbilitySupportedByServer(AbilityKey.SERVER_SUPPORT_PERSISTENT_INSTANCE_BY_GRPC)) {
        return grpcClientProxy;
    }
    return httpClientProxy;
}
```

**路由规则**:
1. 临时实例(ephemeral=true) → 始终 gRPC(快速、长连接、连接即生命周期)
2. 持久实例 + 服务端支持 gRPC 持久 → gRPC(2.x 新能力)
3. 持久实例 + 服务端不支持 → HTTP fallback(向后兼容 1.x)

**设计意图**:
- gRPC 是主链路(2.x 的默认选择)
- HTTP 是 fallback(兼容旧版)
- 临时/持久的协议选择通过 AbilityKey 解耦

### 3.3 GrpcRedoService — 断线重连保护机制

```java
// redoScheduledTask 的核心定时循环 (默认 3000ms, Constants.DEFAULT_REDO_DELAY_TIME):
for (InstanceRedoData each : registeredInstances.values()) {
    if (!redoService.isConnected()) 
        continue;  // 还没连上,跳过
    if (each.isRegistered() && !each.isUnregistering() && !each.isExpected())
        continue;  // 已在服务端注册成功,不需要 redo
    // 需要 redo: 重新发送注册/注销/订阅请求
    clientProxy.redo(each);
}
```

**redo 的核心机制**:
1. **缓存操作**: `registeredInstances`(注册实例) + `subscribes`(订阅) 两个 ConcurrentHashMap
2. **断线标记**: `onDisConnect()` → 将所有 instance 标记为 `registered=false`
3. **重连恢复**: `onConnected()` → `connected=true` → RedoScheduledTask 定时检查 → redo 未确认的操作
4. **定时兜底**: 即使不触发 onConnected,RedoScheduledTask 也会定期(3s)检查
5. **幂等保证**: 重复 redo 同一个注册请求,服务端通过 revision 判断是否已处理

**哪些操作进 redo**: 注册、注销、订阅(有状态操作 — 需要保证 at-least-once)
**哪些不进 redo**: 查询(无状态操作 — 失败了下次再查就行)

### 3.4 ServiceInfoHolder — 客户端本地缓存

- 数据结构: `ConcurrentHashMap<String, ServiceInfo>`(key = group@@service@@clusters)
- `processServiceInfo(newServiceInfo)`: 对比新旧 hosts 列表 → 只有变化时发布 InstancesChangeEvent
- `cacheMillis` 过期机制: expired() 用于判断主动查询时是否需要重新获取
- 快照持久化: DiskCache 将 ServiceInfo 写入本地文件 → 重启后无需等待首次推送

### 3.5 InstancesChangeNotifier — 推送桥接用户回调

- 职责: 将 `InstancesChangeEvent`(SDK 内部事件) 桥接到用户注册的 `EventListener.onEvent(NamingEvent)`
- 注册: `registerListener(group, service, clusters, listener)` → 按 serviceKey 存入 Map
- 触发: `onEvent(InstancesChangeEvent)` → 遍历该 serviceKey 的 Listener 列表 → 逐个回调
- 差分: 只在自己维护的 old/new ServiceInfo 对比有差异时才触发

---

## 四、设计哲学分析

### 4.1 Bundle 参数验证 + 默认值补全在客户端完成

`NacosNamingService` 的重载设计在服务端调用之前就完成了参数归一化：
```java
registerInstance(String serviceName, String ip, int port)
  → registerInstance(serviceName, ip, port, DEFAULT_CLUSTER_NAME)
    → registerInstance(serviceName, DEFAULT_GROUP, ip, port, DEFAULT_CLUSTER_NAME)
      → 构造 Instance → registerInstance(serviceName, DEFAULT_GROUP, instance)
        → NamingUtils.checkInstanceIsLegal(instance)  // 客户端真正处理
        → clientProxy.registerService(serviceName, groupName, instance)
```

**好处**: 服务端收到的请求永远是完整参数,不需要补全默认值
**代价**: 每个方法重载都需要通过层层调用传递默认值

### 4.2 代理链的"乐观返回" + "缓存优先"策略

`getAllInstances(String serviceName, ..., boolean subscribe)`:
```java
if (subscribe) {
    serviceInfo = serviceInfoHolder.getServiceInfo(...);  // 先查缓存,乐观返回
    if (null == serviceInfo || !isSubscribed(...)) {
        serviceInfo = clientProxy.subscribe(...);  // 缓存未命中才发起 gRPC
    }
}
```

**为什么设计成"先查缓存"而不是"先查服务端"？**
- 注册中心的数据是 AP 语义,允许短暂延迟
- 缓存命中率很高(推送机制保证及时更新)
- 减少服务端压力(每次查询都是 gRPC 调用 vs 本地内存访问)

### 4.3 GrpcRedoService 的"最终一致性"保障

- redo 机制不保证数据的实时一致性,只保证最终一致性
- 连接断开期间客户端可能无法注册,服务端已有实例可能被标记不健康
- 重连后 redo 恢复 → 实例恢复健康 → 推送通知订阅者 → 最终一致

---

## 五、反面假设分析 (7 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | 代理层不区分 HTTP/gRPC,只用一种协议？ | HTTP 无长连接,心跳需额外维护;gRPC 对防火墙不友好。单一协议无法兼顾所有场景。 | gRPC 主链路 + HTTP fallback,按 ephemeral 路由 |
| 2 | 不缓存 ServiceInfo,每次查询都请求服务端？ | QPS 1000+ 的微服务集群会击穿 Nacos 集群。查询操作变成分布式瓶颈。 | 本地缓存 + 推送更新,查询走内存 |
| 3 | 不实现 redo,断开后丢弃所有待发送操作？ | 注册/注销/订阅可能丢失,临时实例可能没有注册上但客户端以为注册了。 | redo 队列保证最终一致性 |
| 4 | redo 队列不做大小限制？ | 长时间断开连接,redo 队列可能堆积数十万条。OOM + 恢复后服务端被瞬间流量击穿。 | 限制 redo 队列大小,超出则丢弃旧数据 |
| 5 | 订阅推送不使用差分,每次都全量通知？ | Listener 被频繁触发,每次推送都执行用户回调。高频变更场景下 CPU 100%。 | InstancesChangeNotifier 只在实际有差异时触发 |
| 6 | selectInstances 的过滤直接修改 hosts 列表？ | NacosNamingService:326 `iterator.remove()` 修改原始 ServiceInfo 的 hosts 列表 → 非线程安全 + 破坏缓存 | 应该返回过滤后的新列表副本(目前实现有 bug 风险!) |
| 7 | 不同时注册 push handler 和 subscribe？ | push handler 负责接收推送,subscribe 负责发起订阅。缺一不可。只 subscribe 不注册 handler = 推送来了没人处理。 | NamingPushRequestHandler 在 grpcClientProxy.start() 时注册 |

---

## 六、新手最可能犯的 5 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 创建多个 NacosNamingService 实例 | 每个实例创建一个 gRPC connection + GrpcRedoService + ServiceInfoUpdateService,连接数爆炸 | 单例或 Spring Bean | 无明显对策,依赖用户正确使用 |
| 2 | 注册后立即 shutDown() | 来不及建立 gRPC 连接,注册请求进 redo 队列但连接已关闭,实例没注册上 | shutDown 前检查 redo 队列是否为空,或 sleep 等待 | shutDown 时不会 flush redo 队列 |
| 3 | subscribe 同一个服务多次(不同 Listener) | 每次 subscribe 都发起 gRPC subscribe 请求,重复订阅 | 先 unsubscribe 再 subscribe,或用 isSubscribed 检查 | clientProxy.isSubscribed() 可检查 |
| 4 | selectInstances 返回的列表被外部线程并发修改 | selectInstances 直接对 ServiceInfo.hosts 做 iterator.remove(),非线程安全 | 返回新列表副本或在锁内操作 | 当前实现有并发风险 |
| 5 | redo 队列堆积时重启客户端 | redo 中未完成的注册丢失,实例需要等待健康检查重新发现 | 关闭前 ensure redo 队列为空(调用 isConnected + check redo) | redo 是内存队列,重启丢失 |

### 6.1 可运行的错误实验

**实验: 多次创建 NacosNamingService 导致连接数爆炸**

```java
// 错误做法: 每次调用都 new
public Instance getInstance(String serviceName) throws NacosException {
    Properties props = new Properties();
    props.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:8848");
    NamingService ns = new NacosNamingService(props);  // 每次新建!
    Instance instance = ns.selectOneHealthyInstance(serviceName);
    ns.shutDown();
    return instance;
}

// 问题: 每个 new NacosNamingService 创建:
// - 一个 GrpcClient(含 Netty EventLoop)
// - 一个 GrpcRedoService(含定时线程)
// - 一个 ServiceInfoUpdateService(含定时线程)
// 100 次调用 = 100+ 线程 + 100 个 gRPC connection（按 ServerList 数量倍增）

// 正确做法: 单例
@Component
public class NamingClientHolder {
    private final NamingService namingService;
    
    public NamingClientHolder() throws NacosException {
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:8848");
        this.namingService = new NacosNamingService(props);
    }
    
    @PreDestroy
    public void destroy() throws NacosException {
        namingService.shutDown();
    }
}
```

**实验: selectInstances 并发修改问题**

```java
// selectInstances 的当前实现:
// NacosNamingService:326 → iterator.remove() 直接在 ServiceInfo.hosts 上操作
List<Instance> instances = namingService.selectInstances("user-service", true);
// instances 与 ServiceInfo.hosts 是同一个引用!
// 如果在多线程中并发调用 selectInstances,会触发 ConcurrentModificationException
```

---

## 七、面试 Q&A (5 题)

### Q1: Nacos SDK 的 registerInstance 是同步还是异步的？失败后怎么处理？

**面试官意图**: 考察对 SDK 调用模型的精确理解

**参考答案要点**:
- 调用层面是同步的：`registerInstance` 返回 void,内部等待 gRPC response
- 但数据生效是异步的：注册完成后还需要事件链 → 索引更新 → PushDelayTask → 推送 → 订阅者感知
- 失败处理：gRPC 失败 → 异常抛给调用方 + 操作进入 redo 队列(定时重试)
- 所以从调用方的视角：成功返回 = gRPC 确认(服务端已接收),但其他客户端还不一定感知到

**追问**: "那客户端进程 crashed 后重启,redo 队列里的数据会怎样？"
- redo 是纯内存队列,进程崩溃 = redo 数据丢失
- 但进程重启后会重新注册(应用启动逻辑),不需要依赖 redo
- redo 主要应对"网络短暂断开"而非"进程崩溃"

### Q2: Nacos SDK 的 redo 机制是怎么工作的？能保证强一致性吗？

**面试官意图**: 考察对"最终一致性"和"at-least-once"语义的理解

**参考答案要点**:
- redo = at-least-once 语义,不保证强一致性
- 机制: 注册/注销/订阅操作 → 成功前存入 ConcurrentHashMap → 定时(3s)检查 → 未确认则重发
- 幂等性: 服务端通过 `revision` 判断是否已处理,重复 redo 不会重复注册
- 局限: redo 是内存队列(进程重启丢失),只适合网络短暂中断,不适应长时间进程不可用

**追问**: "redo 和 Kafka 的 at-least-once 有什么本质区别？"
- Kafka 的 offset 持久化到磁盘/zk,进程重启不丢
- Nacos redo 纯内存,重启丢失
- Nacos 不需要持久化 redo,因为注册中心的语义：进程重启 = 重新注册(新 IP/新端口)

### Q3: Nacos SDK 的缓存(ServiceInfoHolder)是怎么避免脏读的？

**面试官意图**: 考察对客户端缓存一致性策略的理解

**参考答案要点**:
- 不是通过"分布式锁"或"强一致性"保证的
- 通过"推送驱动更新"保证: 服务端有变更 → push ServiceInfo → processServiceInfo → 更新缓存
- 缓存过期兜底: ServiceInfoUpdateService 定时检查过期缓存 → 重新查询
- 因此一致性窗口 = 推送延迟 + 缓存过期周期(通常 1-10s)
- 这是 AP 系统对性能的妥协

**追问**: "如果 cacheMillis 设为 0,推送到缓存更新之间有没有窗口期问题？"
- cacheMillis=0 意味着缓存永不主动过期,只依赖推送更新
- 如果推送丢失(网络抖动期间刚好有实例变更),缓存永远不更新 → 脏读
- ServiceInfoUpdateService 的定时兜底检查 expired(),但 0 意味着永不过期 → 定时兜底失效
- 默认 1000ms 的合理性: 1s 是"推送延迟通常低于此值"和"减少服务端查询压力"之间的平衡点
- Push(快,事件驱动) + ServiceInfoUpdateService(慢,定时兜底) 互补非竞争

### Q4: NamingClientProxyDelegate 的 HTTP/gRPC 双协议路由设计有什么好处？

**面试官意图**: 考察对兼容性设计和渐进式升级的理解

**参考答案要点**:
- gRPC: 2.x 的默认选择(长连接 + 连接即生命周期)
- HTTP: 1.x 遗留功能 + fallback(防火墙友好)
- 路由规则: 临时实例 → gRPC,持久实例 → 依赖服务端能力声明
- 这是一种"渐进式下线"策略 — 不是直接砍掉 HTTP,而是通过能力声明让服务端控制何时完全切到 gRPC

### Q5: 如果客户端和 Nacos 之间的网络出现间歇性断开,SDK 的行为是什么？（联动 D-05）

**面试官意图**: 考察对连接生命周期和 redo 机制的整合理解

**参考答案要点**:
- 连接断开 → GrpcRedoService.onDisConnect → 标记所有实例为 registered=false
- 连接恢复 → onConnected → RedoScheduledTask 定时扫描 → redo 未确认操作
- 断开期间: 服务端可能标记实例不健康(取决于 keep-alive 超时),其他客户端可能感知不到此实例
- 恢复后: redo 重新注册 → 服务端更新健康状态 → push 通知订阅者
- 整个过程: 最终一致性延迟 = 断开时间 + keep-alive 超时 + redo 间隔 + push 延迟

---

## 八、产出文档结构

```
D-03-NamingSDK.md

1. 概述: SDK 在 Nacos 架构中的位置(承上启下)
2. NacosNamingService 初始化流程
   2.1 构造函数 → init() → 组件初始化链
   2.2 关键组件: ServiceInfoHolder + InstancesChangeNotifier + NamingClientProxyDelegate

3. 注册链路
   3.1 重载链的参数归一化(DEFAULT_GROUP/DEFAULT_CLUSTER_NAME)
   3.2 NamingClientProxyDelegate.getExecuteClientProxy 路由决策
   3.3 NamingGrpcClientProxy.registerService → gRPC 请求构建
   3.4 失败 → GrpcRedoService.cacheDataForRedo

4. 注销链路(对称分析)

5. 发现链路
   5.1 本地缓存优先策略
   5.2 subscribe=true 的首次订阅路径
   5.3 subscribe=false 的直接查询路径
   5.4 selectInstances 的客户端过滤(healthy/enabled/weight)

6. 订阅链路
   6.1 changeNotifier.registerListener 注册用户回调
   6.2 clientProxy.subscribe → gRPC subscribe + 首次拉取
   6.3 NamingPushRequestHandler 接收服务端推送
   6.4 InstancesChangeNotifier 桥接推送→用户回调

7. GrpcRedoService 深度分析
   7.1 redo 队列数据结构
   7.2 连接生命周期回调(onConnected/onDisConnect)
   7.3 RedoScheduledTask 定时轮询
   7.4 redo 的局限性与适用场景

8. ServiceInfoHolder 缓存机制
   8.1 缓存结构与 key 设计
   8.2 processServiceInfo 的差分逻辑
   8.3 缓存过期与 ServiceInfoUpdateService 兜底

9. 设计哲学分析
   9.1 参数归一化 + 缓存优先策略
   9.2 HTTP/gRPC 双协议路由
   9.3 最终一致性的代价与收益

10. 反面假设分析(7 组)

11. 新手最可能犯的 5 个错误 + 可运行错误实验

12. 面试 Q&A(5 题,含面试官意图 + 追问)
```

---

## 九、与后续文档的依赖关系

```
D-03 (Naming SDK 全链路)  ← 当前
  |--- gRPC 请求格式(InstanceRequest/SubscribeServiceRequest) → D-06~D-07 服务端用到
  |--- redo 机制 → D-05 连接模型处需要引用
  ↓
D-05 (gRPC 连接模型: GrpcServer + ConnectionManager) ← 服务端如何接收 gRPC 连接
D-06 (Client 模型 + 数据存储) ← 服务端如何处理注册请求
```

D-03 建立了客户端到服务端的桥梁：知道客户端发什么请求、用什么格式、怎么处理失败。后续 D-05~D-08 讨论"服务端收到这些请求后做什么"。

> **Forward 引用**: D-03 中 NamingGrpcClientProxy 发出的 `InstanceRequest`/`SubscribeServiceRequest` 到达服务端后，由 D-05 的 `GrpcRequestAcceptor` → `RequestHandler` 体系分派到 `InstanceRequestHandler`(D-07) 处理。
