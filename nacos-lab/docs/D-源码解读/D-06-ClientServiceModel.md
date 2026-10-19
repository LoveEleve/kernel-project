# D-06: Client 模型 + 数据存储设计

> 前置依赖: D-05（gRPC 连接模型 - ConnectionManager 驱动 Client 生命周期）
> 后续: D-07（注册处理链）、D-09（Distro V2 同步）

---

## 一、概述

这是 Nacos 2.x 架构设计的**灵魂**。理解了 Client 模型,就理解了 Nacos 2.x 为什么要把实例存在 Client 上而不是 Service 上--这是 1.x -> 2.x 最根本的数据结构变革,也是"连接即生命周期"的数据基础。

```
1.x 模型: Service -> List<Instance>（以服务为中心）
2.x 模型: Client -> Map<Service, InstancePublishInfo>（以客户端连接为中心）
```

---

## 二、1.x vs 2.x 数据结构根本变革

### 2.1 1.x 模型（以 Service 为中心）

```
Service("user-service")
  ├── Instance("10.0.0.1:8080", healthy=true)
  ├── Instance("10.0.0.2:8080", healthy=true)
  └── Instance("10.0.0.3:8080", healthy=false)

  查询某个服务的所有实例 -> O(1) 快（直接从 Service 的 List 取）
  一个客户端 crash -> 需要遍历所有 Service 找到该客户端的实例 -> O(S*I) 慢
  Distro 同步 -> 每个 Service 单独做一次 sync -> N 个 Service = N 次网络请求
```

### 2.2 2.x 模型（以 Client 为中心）

```
Client("192.168.1.1:52341")
  ├── publishers:
  │   ├── Service("user-service") -> InstancePublishInfo("10.0.0.1:8080")
  │   └── Service("order-service") -> InstancePublishInfo("10.0.0.1:8081")
  └── subscribers:
      └── Service("payment-service") -> Subscriber

Client("192.168.1.2:52342")
  ├── publishers:
  │   └── Service("user-service") -> InstancePublishInfo("10.0.0.2:8080")
  └── subscribers:
      └── Service("user-service") -> Subscriber

  查询某个 Service 的所有实例 -> 需要扫描所有 Client（用 ClientServiceIndexesManager 建索引补偿, D-07）
  一个客户端 crash -> O(1) 删除该 Client 即可
  Distro 同步 -> 一个 Client 的所有 Service 一次同步 -> M 个 Client = M 次请求(M << N*I)
```

**核心 trade-off**: 用查询变慢换取故障清理和同步的高效。查询慢的问题通过 `ClientServiceIndexesManager` 维护 `Service -> Set<ClientId>` 索引来补偿。

---

## 三、Service - 服务路由键

```java
// core/v2/pojo/Service.java
public class Service implements Serializable {
    private final String namespace;      // "public" 或自定义
    private final String group;          // DEFAULT_GROUP
    private final String name;           // 服务名
    private final boolean ephemeral;     // true=临时, false=持久
    private final AtomicLong revision;   // 并发安全的版本号
    private long lastUpdatedTime;        // 最后更新时间
}
```

### 3.1 不可变对象设计

所有字段都是 `final`（`lastUpdatedTime` 除外）,私有构造函数,只能通过 `Service.newService()` 静态工厂创建。这保证了:

- **线程安全**: final 字段天然线程安全,不需要同步
- **HashMap key 安全**: 作为 `ConcurrentHashMap` 的 key,hashCode 不会变化
- **单例管理**: 便于 `ServiceManager` 管理全局唯一实例

### 3.2 equals/hashCode - 不含 ephemeral

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Service)) return false;
    Service service = (Service) o;
    return namespace.equals(service.namespace)
        && group.equals(service.group)
        && name.equals(service.name);
}

@Override
public int hashCode() {
    return Objects.hash(namespace, group, name);
}
```

**关键含义**: Service 的 identity 由 `namespace + group + name` 三元组决定,**ephemeral 不参与 identity 判断**。

这带来一个重要约束: `ServiceManager` 中同一个三元组只存在一个 Service 单例。`ephemeral` 字段虽然是 final(不可变),但它不影响 equals。**第一个注册到该 Service 的实例类型决定了该 Service 的 ephemeral 标志**,后续注册不同类型会被拒绝（由 `EphemeralClientOperationServiceImpl` 校验,详见 D-10）。

> 对比 Instance.equals()（基于 toString,包含所有字段）: Instance 需要区分字段级差异用于客户端差分推送, Service 不需要--Service 是路由键而非完整描述。

### 3.3 Service.revision 机制

`revision` 是 `AtomicLong`。当 `ServiceChangedEvent(service, incrementRevision=true)` 发布时,会调用 `service.incrementRevision()`(递增)。

> **注意区分**: Service.revision 和 Client.revision 是**两个不同的东西**:
> - **Service.revision**: 由 `ServiceChangedEvent` 触发递增,主要用于 istio 集成模块。在 naming 核心流程中**几乎未被使用**。
> - **Client.revision**: 由 `client.recalculateRevision()` 重新计算(基于内容 hash 或递增),用于 **Distro verify**(D-09)。每次 Client 数据变更(注册/注销)后重新计算。详见本章 5.1 节。

---

## 四、ServiceManager - 服务单例注册表

```java
// core/v2/ServiceManager.java - 单例（非 Spring Bean）
public class ServiceManager {
    private static final ServiceManager INSTANCE = new ServiceManager();

    // 索引1: Service -> Service（单例映射,保证全局唯一）
    private final ConcurrentHashMap<Service, Service> singletonRepository;
    // 索引2: namespace -> Set<Service>（按命名空间分组）
    private final ConcurrentHashMap<String, Set<Service>> namespaceSingletonMaps;
}
```

### 4.1 getSingleton - 双重保障

```java
public Service getSingleton(Service service) {
    singletonRepository.computeIfAbsent(service, key -> {
        // 首次注册 -> 发布 ServiceMetadataEvent -> 触发元数据存储
        NotifyCenter.publishEvent(new MetadataEvent.ServiceMetadataEvent(key, false));
        return key;
    });
    Service result = singletonRepository.get(service);
    // 同时维护 namespace 索引
    namespaceSingletonMaps.computeIfAbsent(result.getNamespace(), k -> new ConcurrentHashSet<>());
    namespaceSingletonMaps.get(result.getNamespace()).add(result);
    return result;
}
```

双重保障:
1. **单例保证**: 同一个 `namespace+group+name` 的 Service 全局只有一个实例（`computeIfAbsent` 原子性）
2. **命名空间索引**: `getAllNamespaces()` 快速获取所有有服务的命名空间
3. **事件发布**: 首次注册时发布 `ServiceMetadataEvent` -> `NamingMetadataManager` 创建服务元数据

### 4.2 为什么 ServiceManager 不是 Spring Bean?

ServiceManager 是纯内存数据结构,不依赖任何 Spring 组件注入。用单例模式而非 Spring 管理,避免了 Spring Context 初始化顺序的影响--Service 注册可能在 Spring 完全启动前就发生。

---

## 五、Client 抽象接口

```java
// core/v2/client/Client.java
public interface Client {
    String getClientId();                    // 连接ID
    boolean isEphemeral();                   // 临时 or 持久

    // 实例管理（发布）
    boolean addServiceInstance(Service service, InstancePublishInfo info);
    InstancePublishInfo removeServiceInstance(Service service);
    InstancePublishInfo getInstancePublishInfo(Service service);
    Collection<Service> getAllPublishedService();

    // 订阅管理
    boolean addServiceSubscriber(Service service, Subscriber subscriber);
    boolean removeServiceSubscriber(Service service);
    Collection<Service> getAllSubscribeService();

    // 生命周期
    void setLastUpdatedTime();
    long getLastUpdatedTime();
    boolean isExpire(long currentTime);
    void release();

    // 同步
    long recalculateRevision();
    long getRevision();
    void setRevision(long revision);
    ClientSyncData generateSyncData();
}
```

**Client = 一个客户端连接的全部状态**:
- `publishers`: 该客户端注册了哪些服务的哪些实例（服务提供方）
- `subscribers`: 该客户端订阅了哪些服务（服务消费方）
- 两者独立管理,因为注册和订阅的生命周期不同（一个 Client 可能只注册不订阅,如纯服务提供方）

### 5.1 AbstractClient - 核心实现

```java
// core/v2/client/AbstractClient.java
public abstract class AbstractClient implements Client {
    protected final ConcurrentHashMap<Service, InstancePublishInfo> publishers;
    protected final ConcurrentHashMap<Service, Subscriber> subscribers;
    protected volatile long lastUpdatedTime;
    protected final AtomicLong revision;
}
```

**addServiceInstance 的关键逻辑**:

```java
public boolean addServiceInstance(Service service, InstancePublishInfo instancePublishInfo) {
    // ... publishers.put(service, instancePublishInfo)
    // 发布 ClientChangedEvent -> 触发索引更新 + Distro sync + Push
    NotifyCenter.publishEvent(new ClientEvent.ClientChangedEvent(this));
    return true;
}
```

注意: `addServiceInstance` 是 `publishers.put()`（覆盖写）--天然幂等。重复注册同一个实例不会产生副作用,这也是 redo 机制能安全重试的基础（联动 D-03）。

**recalculateRevision 的关键逻辑**:

```java
// AbstractClient 中:基于内容计算 Distro hash
public long recalculateRevision() {
    int hash = DistroUtils.hash(this);
    revision.set(hash);
    return hash;
}
```

---

## 六、两种 Client 实现

### 6.1 对比

| 维度 | IpPortBasedClient | ConnectionBasedClient |
|------|-------------------|----------------------|
| **绑定方式** | IP+Port（clientId 含 `#`） | gRPC 连接 ID（clientId 不含 `#`） |
| **ephemeral** | 构造函数决定（可临时可持久） | **始终 true** |
| **数据来源** | 1.x 兼容 / Distro 同步 | 2.x gRPC 连接直连 |
| **isNative** | 不适用 | true=直连本节点, false=其他节点同步 |
| **过期判断** | ephemeral && 无发布实例 && 超时 | !isNative && 续约超时 |
| **recalculateRevision** | 继承 AbstractClient（DistroUtils.hash） | **重写**: `revision.addAndGet(1)` 递增 |
| **健康检查** | ClientBeatCheckTaskV2(临时) / HealthCheckTaskV2(持久) | 不需要（连接即健康检查） |

### 6.2 为什么 ConnectionBasedClient 重写 recalculateRevision?

`AbstractClient.recalculateRevision()` 用 `DistroUtils.hash(this)` 基于内容计算 hash。但 `ConnectionBasedClient` 改为 `revision.addAndGet(1)` --简单递增。

原因: `ConnectionBasedClient` 是 2.x 的原生 Client,它的数据变更频率可能很高（注册/注销/订阅都触发）。每次都计算 hash 有 CPU 开销。简单递增虽然不能像 hash 那样精确反映"内容是否一致"（不同内容可能碰巧有相同递增值）,但在 Distro verify 时只需要判断"变了没有"--递增值变了就说明有变更,触发全量 sync 即可。这是一个**性能优先**的工程取舍。

### 6.3 isExpire 的差异

```java
// IpPortBasedClient: 仅临时 Client 会过期
public boolean isExpire(long currentTime) {
    return isEphemeral()
        && getAllPublishedService().isEmpty()
        && currentTime - getLastUpdatedTime() > ClientConfig.getClientExpiredTime();
}

// ConnectionBasedClient: 仅非原生（同步来的）Client 会过期
public boolean isExpire(long currentTime) {
    return !isNative()
        && currentTime - getLastRenewTime() > ClientConfig.getClientExpiredTime();
}
```

- `IpPortBasedClient`: 临时 Client 在无发布实例且超时后过期。持久 Client 永不过期（数据在 DB 中）。
- `ConnectionBasedClient`: 直连本节点的（isNative=true）永不过期（连接断开即由 ConnectionManager 清理）。从其他节点同步来的（isNative=false）在续约超时后过期。

---

## 七、ClientManagerDelegate - 三路路由层

```java
// core/v2/client/manager/ClientManagerDelegate.java
@Component("clientManager")
public class ClientManagerDelegate implements ClientManager {
    private final ConnectionBasedClientManager connectionBasedClientManager;
    private final EphemeralIpPortClientManager ephemeralIpPortClientManager;
    private final PersistentIpPortClientManager persistentIpPortClientManager;
}
```

### 7.1 路由规则

```java
private ClientManager getClientManagerById(String clientId) {
    if (isConnectionBasedClient(clientId)) {       // clientId 不含 '#'
        return connectionBasedClientManager;
    }
    return clientId.endsWith(ClientConstants.PERSISTENT_SUFFIX)
        ? persistentIpPortClientManager             // 含持久后缀
        : ephemeralIpPortClientManager;             // 默认临时
}

private boolean isConnectionBasedClient(String clientId) {
    return !clientId.contains(IpPortBasedClient.ID_DELIMITER);  // 不含 '#' = 连接型
}
```

| clientId 特征 | 路由到 | 场景 |
|--------------|--------|------|
| 不含 `#`（如 UUID） | ConnectionBasedClientManager | 2.x gRPC 直连客户端 |
| 含 `#` + 持久后缀 | PersistentIpPortClientManager | 持久实例（1.x 兼容 / Distro 同步） |
| 含 `#` 无持久后缀 | EphemeralIpPortClientManager | 临时实例（1.x 兼容 / Distro 同步） |

### 7.2 contains 和 allClientId 聚合所有子 Manager

```java
public boolean contains(String clientId) {
    // 不按 id 路由,而是查所有三个 Manager
    return connectionBasedClientManager.contains(clientId)
        || ephemeralIpPortClientManager.contains(clientId)
        || persistentIpPortClientManager.contains(clientId);
}
```

`contains` 和 `allClientId` 不做路由,而是聚合所有子 Manager--因为同一个 clientId 理论上只应存在于一个子 Manager 中,但查询方不一定知道类型。

### 7.3 与 ConnectionManager 的协作

```
ConnectionManager.register(connectionId, connection)    // D-05
  -> notifyClientConnected(connection)
    -> ClientManagerDelegate.clientConnected(connectionId, attributes)
      -> isConnectionBasedClient(connectionId) = true   // gRPC 连接 ID 不含 '#'
      -> connectionBasedClientManager.clientConnected(...)
        -> new ConnectionBasedClient(connectionId, isNative=true, revision)
        -> clients.put(connectionId, client)

ConnectionManager.unregister(connectionId)               // D-05
  -> notifyClientDisConnected(connection)
    -> ClientManagerDelegate.clientDisconnected(connectionId)
      -> connectionBasedClientManager.clientDisconnected(connectionId)
        -> client.release()                               // 清理资源
        -> clients.remove(connectionId)
        -> 发布 ClientReleaseEvent                        // D-07/D-08 清理索引 + Distro sync
```

---

## 八、InstancePublishInfo - 实例发布信息

```java
// core/v2/pojo/InstancePublishInfo.java
public class InstancePublishInfo implements Serializable {
    private String ip;
    private int port;
    private boolean healthy;
    private String cluster;
    private Map<String, Object> extendDatum;   // 扩展属性（含 weight、metadata 等）
}
```

### 8.1 equals - 排除 cluster

```java
@Override
public boolean equals(Object o) {
    // ...
    return port == that.port
        && healthy == that.healthy
        && Objects.equals(ip, that.ip)
        && Objects.equals(extendDatum, that.extendDatum);
    // 注意: cluster 不参与 equals!
}
```

**cluster 不参与 equals**: 两个 InstancePublishInfo 只要 ip/port/healthy/extendDatum 相同就认为相等,即使 cluster 不同。这意味着同一个 Client 不能同时发布两个 ip/port 相同但 cluster 不同的实例--因为 `publishers` 是 `ConcurrentHashMap<Service, InstancePublishInfo>`,key 是 Service（不含 cluster）,而 value 的 equals 也不含 cluster。

### 8.2 与 Instance（D-01）的区别

| 维度 | Instance（API 层） | InstancePublishInfo（存储层） |
|------|-------------------|---------------------------|
| 位置 | `api` 模块 | `naming` 模块 |
| equals | 基于 toString（所有字段） | ip+port+healthy+extendDatum（**排除 cluster**） |
| 用途 | 客户端 API 交互 | 服务端存储 + Distro 同步 |
| 字段 | 含 weight/enabled/ephemeral/serviceName/metadata | 含 healthy/cluster/extendDatum（weight 等放在 extendDatum 中） |

InstancePublishInfo 是 Instance 的"瘦身版"--只保留服务端存储和同步所需的字段,丢掉了客户端关心的 enabled/ephemeral/serviceName 等（这些由 Client 和 Service 管理）。

### 8.3 HealthCheckInstancePublishInfo

`IpPortBasedClient.addServiceInstance` 会将 `InstancePublishInfo` 包装为 `HealthCheckInstancePublishInfo`:

```java
// IpPortBasedClient
public boolean addServiceInstance(Service service, InstancePublishInfo instancePublishInfo) {
    return super.addServiceInstance(service, parseToHealthCheckInstance(instancePublishInfo));
}
```

`HealthCheckInstancePublishInfo` 扩展了 `InstancePublishInfo`,增加了健康检查相关信息（lastBeat 等）。这是因为 `IpPortBasedClient` 需要自己做健康检查（1.x 兼容）,而 `ConnectionBasedClient` 不需要（连接即健康检查）。

---

## 九、设计哲学分析

### 9.1 为什么要把 Instance 存在 Client 上而不是 Service 上?

| 维度 | 1.x（Service 中心） | 2.x（Client 中心） |
|------|---------------------|---------------------|
| 查询 | O(1) - 直接从 Service 的 List 取 | 需索引补偿（ClientServiceIndexesManager） |
| 故障清理 | O(S*I) - 遍历所有 Service 找该客户端的实例 | O(1) - 删除一个 Client |
| Distro 同步 | O(N) - 每个 Service 一次 sync | O(M) - 每个 Client 一次 sync（M << N*I） |

这是一个**空间换时间**的典型设计: 多维护一份索引（Service -> Set<ClientId>）,换取故障清理和同步的高效。对于注册中心来说,故障清理的效率比查询效率更重要--因为查询可以靠缓存补偿,但故障清理慢了会导致路由黑洞。

### 9.2 Service 为什么是不可变对象 + 单例模式?

- **不可变 = 天然线程安全** -> 不需要并发控制
- **单例 = HashMap 查找精确** -> 同一个三元组只有一个对象
- **equals 不含 ephemeral** -> 同一个 Service 不能同时有临时和持久实例（第一个注册决定 ephemeral 值）
- **ephemeral 是 final 但不影响 identity** -> ephemeral 是使用属性而非 identity 属性

### 9.3 为什么 revision 有两种实现?

- `AbstractClient`（IpPortBasedClient 继承）: `DistroUtils.hash(this)` - 基于内容计算,能精确反映"数据是否一致"
- `ConnectionBasedClient`（重写）: `revision.addAndGet(1)` - 简单递增,只能反映"变了没有"

这是**性能 vs 精度**的取舍: 2.x 原生 Client 变更频率高,每次算 hash 有 CPU 开销; 简单递增虽然不能精确比较内容,但 Distro verify 只需要知道"变了没有"--递增了就 sync,够用。

---

## 十、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|------|------|------|
| 1 | 2.x 继续用 1.x 的 Service->List 模型 | 客户端 crash 清理 O(S*I),大集群下数秒。Distro 同步每个 Service 一次,网络开销大。 | Client 中心: crash=O(1) 清理,同步=一次一个 Client |
| 2 | Service 不用单例,每次 new | 同一个 Service 可能有多个对象 -> HashMap 查找失败 -> 数据不一致 | ServiceManager 单例注册表 + 不可变对象 |
| 3 | Service 是可变对象 | 线程不安全,HashMap key 类必须不可变否则 hashCode 变化导致 key 丢失 | 所有字段 final + 私有构造 |
| 4 | 不区分 IpPortBasedClient 和 ConnectionBasedClient | 临时和持久实例共用同一存储,连接断开无法区分该删还是该留 | 两个实现 + ClientManagerDelegate 路由 |
| 5 | ConnectionBasedClient 用 DistroUtils.hash 算 revision | 高频变更场景下 CPU 开销大 | 重写为 addAndGet(1) 简单递增 |
| 6 | InstancePublishInfo.equals 包含 cluster | 同一 ip:port 不同 cluster 的实例无法共存于同一 Client | equals 排除 cluster |
| 7 | revision 用单调递增数字（如 Raft log index） | 需要全局协调,AP 模式下不适合 | AbstractClient 用 hash; ConnectionBasedClient 用本地递增 |

---

## 十一、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|------|------|------|
| 1 | 认为 Service.equals 包含 ephemeral | 误以为同一个 Service 可以同时有临时和持久实例 | equals 只看 namespace+group+name,首个注册决定 ephemeral |
| 2 | 混淆 IpPortBasedClient 和 ConnectionBasedClient 的用途 | 给 ConnectionBasedClient（始终临时）存持久实例数据 -> 重启丢失 | ConnectionBasedClient.isEphemeral() 恒为 true |
| 3 | 直接操作 Client 内部的 publishers Map（绕过 addServiceInstance） | 不触发 recalculateRevision -> Distro verify 误判一致 -> 集群数据不一致 | 必须通过 addServiceInstance/removeServiceInstance |
| 4 | 认为 revision 能防并发冲突 | revision hash 相同 不等于 数据完全一致。LWW 可能丢更新 | 不依赖 revision 做强一致性; 临时实例用 AP, 持久实例用 CP |
| 5 | 以为 getAllPublishedService 返回不可变视图 | 实际返回 `publishers.keySet()` -- **live view,可变**!直接修改会影响 Client 内部状态 | **无自动对策**,返回的是 ConcurrentHashMap 的 live keySet,调用方需自律不修改 |

---

## 十二、面试 Q&A

### Q1: Nacos 2.x 为什么要把实例数据从 Service 移到 Client 上?

**面试官意图**: 考察对数据结构设计变革的深度理解。

1.x 问题: 清理 O(S*I), 同步 O(N Service)。2.x 改进: 清理 O(1), 同步 O(M Client)。查询变慢 -> 通过 ClientServiceIndexesManager 建索引补偿。这是"空间换时间"设计: 多维护一份索引,换取故障清理和同步的高效。

**追问**: "查询性能怎么保证?"
- `publisherIndexes`: `Service -> Set<ClientId>`,写时维护索引,读时查索引。

### Q2: Service 为什么设计成不可变对象?

**面试官意图**: 考察并发安全和 HashMap key 设计。

HashMap key 必须不可变,否则 hashCode 变化导致 key 丢失。所有字段 final -> 天然线程安全。工厂方法 newService -> 便于 ServiceManager 管理单例。代价: ephemeral 一旦设置不可变更。

### Q3: revision 机制能保证数据强一致性吗?

**面试官意图**: 考察对 AP 系统一致性的理解。

不能。revision 是"最终一致性"的检测机制,不是"强一致性"的保证机制。AbstractClient 用 `DistroUtils.hash` 近似判断; ConnectionBasedClient 用 `addAndGet(1)` 只能判断"变了没有"。verify 发现不一致 -> 全量 sync,但 sync 过程中仍可能有新变更。真正的强一致性需要 Raft（持久实例, D-10）。

### Q4: ClientManagerDelegate 的路由逻辑和 ConnectionManager 如何配合?

**面试官意图**: 考察组件间协作。

ConnectionManager.register -> ClientConnectionEventListener.onConnected -> ClientManagerDelegate.clientConnected。路由规则: clientId 不含 `#` -> ConnectionBasedClientManager -> new ConnectionBasedClient。ClientManagerDelegate.getClient -> 按 clientId 路由到对应子 Manager。

### Q5: IpPortBasedClient 和 ConnectionBasedClient 的过期判断有什么区别?

**面试官意图**: 考察对不同 Client 生命周期的理解。

- IpPortBasedClient: 仅 ephemeral && 无发布实例 && 超时才过期。持久 Client 永不过期。
- ConnectionBasedClient: 仅 !isNative（同步来的） && 续约超时才过期。直连的（isNative=true）永不过期--连接断开由 ConnectionManager 即刻清理,不需要过期检查。

---

## 十三、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| Service | 不可变服务路由键,equals 不含 ephemeral | `core/v2/pojo/Service.java` |
| ServiceManager | 单例注册表,computeIfAbsent 保证全局唯一 | `core/v2/ServiceManager.java` |
| Client | 一个连接的全部状态（publishers + subscribers） | `core/v2/client/Client.java` |
| AbstractClient | Client 核心实现,addServiceInstance 发布 ClientChangedEvent | `core/v2/client/AbstractClient.java` |
| IpPortBasedClient | 按 IP+Port 标识,可临时可持久,自带健康检查 | `core/v2/client/impl/IpPortBasedClient.java` |
| ConnectionBasedClient | 按 gRPC 连接标识,始终临时,重写 revision 为递增 | `core/v2/client/impl/ConnectionBasedClient.java` |
| ClientManagerDelegate | 三路路由:连接型 / 临时 IP+Port / 持久 IP+Port | `core/v2/client/manager/ClientManagerDelegate.java` |
| InstancePublishInfo | 实例存储模型,equals 排除 cluster | `core/v2/pojo/InstancePublishInfo.java` |
| revision | Distro 同步版本号(AbstractClient 用 hash, ConnectionBased 用递增) | `AbstractClient.recalculateRevision()` |
