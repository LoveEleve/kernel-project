# D-06 详细规划: Client 模型 + 数据存储设计

> 阶段: Phase 3 — 服务端基础设施 | 预期产出: `md/D-06-ClientServiceModel.md` | 状态: 待审核

---

## 一、为什么需要这篇

这是 Nacos 2.x 架构设计的 **灵魂**。理解了 Client 模型，就理解了 Nacos 2.x 为什么要把实例存在 Client 上而不是 Service 上——这是 1.x → 2.x 最根本的数据结构变革，也是连接即生命周期的数据基础。

1.x 模型: Service → List<Instance>（以服务为中心）
2.x 模型: Client → Map<Service, InstancePublishInfo>（以客户端连接为中心）

前置依赖: D-05 (gRPC 连接模型 — ConnectionManager 的 register/unregister 如何驱动 Client 生命周期)

> **与 D-05 的呼应**: D-05 的 ConnectionManager.register → 触发 ClientManager.clientConnected → 创建 Client；unregister → clientDisconnected → 清理 Client。连接 = Client = 数据容器。

---

## 二、源码阅读清单 (按依赖层次)

| 序号 | 源码文件 | 行数 | 必须阅读的内容 | 阅读目的 |
|------|----------|------|---------------|----------|
| 1 | `naming/src/main/java/.../core/v2/pojo/Service.java` | ~100 | newService(55-61), equals(100-108), hashCode(112-114), revision(79-80) | 理解 Service 路由键设计 + revision 机制 |
| 2 | `naming/src/main/java/.../core/v2/ServiceManager.java` | ~120 | getSingleton(61-70), getSingletonIfExist(80-88), 双重索引 | 理解 Service 单例注册表 |
| 3 | `naming/src/main/java/.../core/v2/client/Client.java` | ~70 | addServiceInstance/removeServiceInstance/getAllPublishedService/subscribe/subscribe | 理解 Client 抽象接口 |
| 4 | `naming/src/main/java/.../core/v2/client/impl/IpPortBasedClient.java` | ~100 | addServiceInstance 实现、recalculateRevision | 理解临时实例 Client 存储 |
| 5 | `naming/src/main/java/.../core/v2/client/impl/ConnectionBasedClient.java` | ~80 | 持久实例存储的差异 | 理解持久实例 Client 存储 |
| 6 | `naming/src/main/java/.../core/v2/client/manager/ClientManagerDelegate.java` | ~60 | getClient(根据 clientId 路由) | 理解 ClientManager 路由层 |
| 7 | `naming/src/main/java/.../core/v2/client/manager/impl/IpPortBasedClientManager.java` | ~120 | clientConnected, clientDisconnected | 理解临时 ClientManager 生命周期 |
| 8 | `naming/src/main/java/.../core/v2/pojo/InstancePublishInfo.java` | ~60 | ip/port/cluster/metadata 字段 | 理解实例发布信息模型 |
| 9 | `naming/src/main/java/.../naming/consistency/ephemeral/distro/v2/DistroClientSyncData.java` | ~40 | 同步数据结构 | 理解 Distro 同步的完整 Client 数据 |

---

## 三、分析要点详解

### 3.1 Nacos 1.x vs 2.x 数据结构根本变革

```
1.x 模型 (以 Service 为中心):
Service("user-service")
  ├── Instance("10.0.0.1:8080", healthy=true)
  ├── Instance("10.0.0.2:8080", healthy=true)
  └── Instance("10.0.0.3:8080", healthy=false)
  
  问题: 查询某个服务的所有实例 → O(1) 快
       但一个客户端 crash → 需要遍历所有 Service 找到该客户端的实例 → O(S*I) 慢
       每个 Service 都要单独做 Distro 同步 → N 个 Service = N 次网络请求

2.x 模型 (以 Client 为中心):
Client("192.168.1.1:52341")
  ├── publishers:
  │   ├── Service("user-service") → InstancePublishInfo("10.0.0.1:8080")
  │   └── Service("order-service") → InstancePublishInfo("10.0.0.1:8081")
  └── subscribers:
      └── Service("payment-service") → SubscriberInfo

Client("192.168.1.2:52342")
  ├── publishers:
  │   └── Service("user-service") → InstancePublishInfo("10.0.0.2:8080")
  └── subscribers:
      └── Service("user-service") → SubscriberInfo

  优势: 查询某个 Service 的所有实例 → 需要扫描所有 Client (用 ClientServiceIndexesManager 建索引补偿)
       一个客户端 crash → O(1) 删除该 Client 即可
       一个 Client 的所有 Service 一次 Distro 同步 → N 个 Client = N 次网络请求(但每个 Client 可能有很多 Service)
```

### 3.2 Service — 服务路由键

```java
// Service.java: 不可变对象(所有字段 final)
public class Service implements Serializable {
    private final String namespace;      // public(默认) 或自定义
    private final String group;          // DEFAULT_GROUP
    private final String name;           // 服务名
    private final boolean ephemeral;     // true=临时, false=持久
    private final AtomicLong revision;   // 并发安全的版本号
    
    private Service(namespace, group, name, ephemeral) {
        // 私有构造 → 只能通过 newService() 静态工厂创建
        revision = new AtomicLong();     // 从 0 开始
    }
    
    public static Service newService(namespace, group, name, boolean ephemeral) {
        return new Service(namespace, group, name, ephemeral);
    }
}
```

**为什么 Service 是不可变对象？**
- Service 作为 HashMap 的 key → 必须不可变(否则 hashCode 变化导致找不到)
- 所有字段 final → 线程安全,不需要同步
- 工厂方法创建 → 便于 ServiceManager 管理单例

**equals/hashCode 设计** (Service.java:100-114):
```java
// ⚠️ 注意: equals 只基于 namespace + group + name，NOT ephemeral!
// 这意味着 ServiceManager 中同一个 namespace+group+name 只有一个 Service 单例
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

**关键含义**: 
- Service 的 identity 由 namespace + group + name 三元组决定, ephemeral 不参与 identity 判断
- ServiceManager 中同一个三元组只存在一个 Service 对象(单例)
- ephemeral 字段虽然是 final(不可变), 但它不影响 equals, 仅影响 Client 的注册行为
- **第一个注册到该 Service 的实例类型决定了该 Service 的 ephemeral 标志**, 后续注册不同类型会被拒绝(由 EphemeralClientOperationServiceImpl 校验)

### 3.3 ServiceManager — 服务单例注册表

```java
// ServiceManager: 同步锁 + 双重索引
public class ServiceManager {
    private static final ServiceManager INSTANCE = new ServiceManager();
    
    // 索引1: Service → Service (单例映射)
    private final ConcurrentHashMap<Service, Service> singletonRepository;
    // 索引2: namespace → Set<Service> (按命名空间分组)
    private final ConcurrentHashMap<String, Set<Service>> namespaceSingletonMaps;
    
    public Service getSingleton(Service service) {
        singletonRepository.computeIfAbsent(service, key -> {
            // 首次注册 → 发布 ServiceMetadataEvent
            NotifyCenter.publishEvent(new MetadataEvent.ServiceMetadataEvent(service, false));
            return service;
        });
        Service result = singletonRepository.get(service);
        // 同时维护 namespace 索引
        namespaceSingletonMaps.computeIfAbsent(result.getNamespace(), ...);
        namespaceSingletonMaps.get(result.getNamespace()).add(result);
        return result;
    }
}
```

**ServiceManager 的双重保障**:
1. **单例保证**: 同一个 namespace+group+name+ephemeral 的 Service 全局只有一个实例
2. **命名空间索引**: getAllNamespaces → 获取所有有服务的命名空间
3. **事件发布**: 首次注册时发布 ServiceMetadataEvent → 触发元数据存储

### 3.4 Client 抽象接口

```java
public interface Client {
    String getClientId();                          // 连接ID = connectionId
    boolean isEphemeral();                         // 临时 or 持久
    
    // 实例管理(发布)
    boolean addServiceInstance(Service, InstancePublishInfo);   // 注册实例
    InstancePublishInfo removeServiceInstance(Service);         // 注销实例
    InstancePublishInfo getInstancePublishInfo(Service);        // 查询实例
    Collection<Service> getAllPublishedService();               // 获取全部已发布服务
    
    // 订阅管理
    boolean addServiceSubscriber(Service, Subscriber);          // 添加订阅
    boolean removeServiceSubscriber(Service);                   // 移除订阅
    Subscriber getSubscriber(Service);                          // 查询订阅
    Collection<Service> getAllSubscribeService();               // 获取全部订阅
    
    // 生命周期
    void setLastUpdatedTime();                      // 更新最后活跃时间
    long getLastUpdatedTime();                      // 获取最后活跃时间
    
    // 同步
    long recalculateRevision();                     // 重新计算 revision
    long getRevision();                             // 获取当前 revision
    void setRevision(long revision);                // Distro 同步时覆盖 revision
    
    // 清理
    void release();                                 // 释放资源
}
```

**Client = 一个客户端连接的全部状态**:
- publishers: 该客户端注册了哪些服务的哪些实例
- subscribers: 该客户端订阅了哪些服务
- 两者独立管理,因为注册和订阅的生命周期不同

### 3.5 IpPortBasedClient vs ConnectionBasedClient

```
临时实例客户端 (IpPortBasedClient):
- 对应 D-05 的 ConnectionManager 连接
- 内部: ConcurrentHashMap<Service, InstancePublishInfo>  // 单实例
- 连接断开 → ClientManager.clientDisconnected → 发布 ClientReleaseEvent
- ClientReleaseEvent → ClientServiceIndexesManager 清理索引
- 数据丢失: 纯内存,进程重启=数据全丢

持久实例客户端 (ConnectionBasedClient):
- 也对应 gRPC 连接,但数据持久化到 Raft/Disk
- 连接断开 → 标记不健康,数据不删
- 数据恢复: Nacos 重启后从 Raft 状态机恢复
```

### 3.6 ClientManagerDelegate — 路由层

```java
// 根据 clientId 找到对应的 ClientManager
public Client getClient(String clientId) {
    // 先找临时 ClientManager
    Client client = ipPortBasedClientManager.getClient(clientId);
    if (client != null) return client;
    // 再找持久 ClientManager
    return connectionBasedClientManager.getClient(clientId);
}
```

### 3.7 revision — Distro 同步的增量基础

```java
// Client.recalculateRevision()
public long recalculateRevision() {
    // 基于所有 publishedService + subscribedService 计算一个 hash
    // 每次注册/注销/订阅/取消订阅后重新计算
    // Distro verify 时交换 revision → 不一致 → 全量 sync
}
```

---

## 四、设计哲学分析

### 4.1 为什么要把 Instance 存在 Client 上而不是 Service 上？

**1.x 的问题**:
- 查询快 → 一个 Service 的所有实例在一个 List 里
- 清理慢 → 客户端 crash → 遍历所有 Service 找该客户端的实例 → O(S*I)
- 同步多 → 每个 Service 单独做一次 Distro 同步 → N 个 Service = N 次网络请求

**2.x 的选择**:
- 查询慢 → 需要扫描所有 Client 才能获取某个 Service 的实例
- 清理快 → 客户端 crash → 删除一个 Client → O(1)
- 同步少 → 一个 Client 的所有 Service 一次同步 → M 个 Client = M 次网络请求(M << N*I)
- 查询慢的问题通过 **ClientServiceIndexesManager** 建索引补偿(Service → Set<ClientId>)

### 4.2 Service 为什么是不可变对象 + 单例模式？

- 不可变对象 = 天然线程安全 → 不需要并发控制
- 单例保证 = 全局唯一 → HashMap 查找精确
- equals 基于 namespace+group+name(不含 ephemeral) → 同一个三元组只有一个 Service 对象
- ephemeral 是 final 但不影响 identity → 所以实际上 **同一个 Service 不能同时有临时和持久实例**(第一个注册决定 ephemeral 值,后续相反类型被拒绝)
- 这种设计的意图: Service 是路由键而非完整描述, ephemeral 是使用属性而非 identity 属性
- 对比 Instance.equals()(基于 toString,包含所有字段): Instance 需要区分字段级差异用于客户端差分推送, Service 不需要

### 4.3 为什么 revision 不是单调递增的数字而是基于内容计算的？

- 分布式场景下单调递增数字需要全局协调(类似 Raft log index)
- 基于内容计算的 revision(hash) 在 Distro verify 时可以快速判断"数据是否一致"
- 不一致就全量 sync(不管具体哪里不一致) → 简单粗暴但有效

---

## 五、反面假设分析 (7 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | Nacos 2.x 继续用 1.x 的数据模型(Service→List<Instance>)？ | 客户端 crash 清理需要 O(S*I) 扫描,大集群(10000 实例)下可能数秒完成。Distro 同步每个 Service 一次,网络开销大。 | Client 为中心: crash=O(1)清理,同步=一次一个 Client |
| 2 | Service 不用单例,每次都 new？ | 同一个 Service 可能有多个对象实例 → HashMap 查找失败 → 数据不一致。namespace 索引无法维护。 | ServiceManager 单例注册表 + 不可变对象 |
| 3 | Service 是可变对象？ | 线程不安全,潜在并发 bug。HashMap key 类必须不可变否则 hashCode 变化导致 key 丢失。 | 所有字段 final + 私有构造 |
| 4 | 不区分 IpPortBasedClient 和 ConnectionBasedClient？ | 临时实例和持久实例共用同一个存储,连接断开 → 无法区分应该删(临时)还是保留(持久)。 | 两个实现,ClientManagerDelegate 路由 |
| 5 | Client 不维护 subscriber,只维护 publisher？ | 连接断开时无法清理订阅,subscriber 泄漏。Distro 同步时只同步发布数据,订阅数据丢失。 | Client 同时维护 publishers + subscribers |
| 6 | revision 用单调递增数字(如 Raft log index)？ | 需要全局协调 → 引入额外的协调开销。Distro AP 模式下不适合全局递增计数器。 | 基于内容的 revision(hash) |
| 7 | getSingleton 不加双重检查(computeIfAbsent)？ | 并发创建同一个 Service → 多个实例 → 数据不一致。 | ConcurrentHashMap.computeIfAbsent 保证原子性 |

---

## 六、新手最可能犯的 5 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 认为 getSingleton 返回的是原对象(可修改) | 试图修改 Service 字段 → 编译失败(final) → 但修改 Client 内部的 Map 不会报错 | Service 是不可变对象,Client 的 Map 通过 put/remove 修改 | 字段 final 强制不可变 |
| 2 | 不理解 revision 的计算范围 | 修改 metadata 后认为 revision 自动更新 → 实际上 revision 只在 addServiceInstance/removeServiceInstance 时重算 | metadata 修改必须重新 registerInstance | recalculateRevision 只在特定时机触发 |
| 3 | 混淆 IpPortBasedClient 和 ConnectionBasedClient 的用途 | 给 IpPortBasedClient(临时)存持久实例数据 → 重启丢失 | 持久实例必须用 ConnectionBasedClient | ClientManagerDelegate 自动路由 |
| 4 | 认为 getAllPublishedService 返回的是不可变视图 | 拿到 Set 后直接修改 → 影响 Client 内部状态 | 只读遍历,修改通过 add/remove 方法 | get 返回原始引用(注意!目前无防止) |
| 5 | clientId 生成逻辑不理解 | 自定义 clientId 与 connectionId 不匹配 → ClientManager 找不到 Client | clientId 由 Nacos SDK 生成(connectionId),不要自定义 | SDK 内部 uuid 生成 |

### 6.1 可运行的错误实验

**实验: 修改 Client 内部的 Map 不触发 revision**

```java
// D-07 注册处理链中会看到:
EphemeralClientOperationServiceImpl.registerInstance(service, instance, clientId)
  → client.addServiceInstance(service, instancePublishInfo)  // 触发 recalculateRevision
  → client.recalculateRevision()                             // revision 更新

// 但如果直接操作 Client 的内部 Map(绕过 addServiceInstance):
// client.getPublishers().put(service, instanceInfo);  // 绕过 revision 重算!
// → Distro verify 时 revision 不变,数据被误判为"一致" → 集群数据不一致
```

**实验: 并发创建同名 Service 的 race condition (被 ServiceManager 防止)**

```java
// 两个线程同时 new Service("public", "DEFAULT_GROUP", "user-service", true)
// 如果没有 ServiceManager.computeIfAbsent:
Thread t1: new Service(...)  // 创建实例 A
Thread t2: new Service(...)  // 创建实例 B
// → A 和 B 是不同的对象(但 hashCode 相同)
// → 两个线程各自拿到自己的实例 → 数据分裂

// ServiceManager 的 computeIfAbsent 保证:
// 第一个线程放入 A → 第二个线程 computeIfAbsent 发现已存在 → 返回 A
```

---

## 七、面试 Q&A (5 题)

### Q1: Nacos 2.x 为什么要把实例数据从 Service 移到 Client 上？架构决策还是实现细节？

**面试官意图**: 考察对数据结构设计的深度理解

**参考答案要点**:
- 这是架构决策,不是实现细节。涉及: 数据模型、同步策略、清理效率、并发安全
- 1.x 问题: 清理 O(S*I)、同步 O(N 个 Service)
- 2.x 改进: 清理 O(1)、同步 O(M 个 Client)
- 代价: 查询变慢 → 通过 ClientServiceIndexesManager 建索引补偿
- 这是一个"空间换时间"的典型设计: 多维护一份索引,换取故障清理和同步的高效

### Q2: Service 为什么设计成不可变对象？

**面试官意图**: 考察对并发安全和 HashMap key 设计的理解

**参考答案要点**:
- HashMap key 必须不可变 → 否则 hashCode 变化导致 key 丢失
- 所有字段 final → 天然线程安全
- 工厂方法 newService → 便于 ServiceManager 管理单例
- 代价: ephemeral 一旦设置不可变更

### Q3: revision 机制能保证数据强一致性吗？

**面试官意图**: 考察对 AP 系统一致性的理解

**参考答案要点**:
- 不能。revision 是"最终一致性"的检测机制,不是"强一致性"的保证机制
- revision 不变 ≠ 数据完全一致 — 这是基于 hash 的近似判断
- verify 发现 revision 不一致 → 全量 sync → 但 sync 过程中仍可能有新变更
- 真正的强一致性需要 Raft(如持久实例所用的协议)

### Q4: ClientManagerDelegate 的路由逻辑和 ConnectionManager 的注册逻辑如何配合？

**面试官意图**: 考察对组件间协作的理解

**参考答案要点**:
- ConnectionManager.register → ClientConnectionEventListener.onConnected
- → IpPortBasedClientManager.clientConnected(connectionId)
  → new IpPortBasedClient(connectionId)
  → clients.put(connectionId, client)
- ClientManagerDelegate.getClient → 先查 IpPortBased → 再查 ConnectionBased

### Q5: 如果查询某个 Service 的所有实例需要遍历所有 Client,性能怎么保证？（联动 D-07）

**面试官意图**: 考察对 Client 中心模型的 trade-off 理解

**参考答案要点**:
- ClientServiceIndexesManager 维护了 publisherIndexes: Service → Set<ClientId>
- 查询时: 先查索引找到哪些 Client 注册了这个 Service → 再批量 getClient → 获取 InstancePublishInfo
- 索引更新: ClientRegisterServiceEvent → addPublisherIndexes
- 这是"写时维护索引,读时查索引"的经典策略

---

## 八、产出文档结构

```
D-06-ClientServiceModel.md

1. 概述: Nacos 1.x vs 2.x 数据结构变革(为什么这是 2.x 的灵魂)
2. Service — 服务路由键
   2.1 不可变对象设计
   2.2 equals/hashCode 实现
   2.3 revision 机制
3. ServiceManager — 单例注册表
   3.1 双重索引: singletonRepository + namespaceSingletonMaps
   3.2 首次创建时的 ServiceMetadataEvent 发布
4. Client 抽象接口
   4.1 实例管理(publisher)
   4.2 订阅管理(subscriber)
   4.3 生命周期管理
5. IpPortBasedClient vs ConnectionBasedClient
   5.1 临时实例存储(纯内存)
   5.2 持久实例存储(内存+Raft+DB)
   5.3 release() 的行为差异
6. ClientManagerDelegate 路由层
7. InstancePublishInfo 模型
8. 1.x vs 2.x 数据结构对比总结
9. 设计哲学分析
   9.1 Client 中心 vs Service 中心
   9.2 不可变对象 + 单例模式
   9.3 基于内容的 revision
10. 反面假设分析(7 组)
11. 新手最可能犯的 5 个错误 + 可运行错误实验
12. 面试 Q&A(5 题,含面试官意图 + 追问)
```

---

## 九、与后续文档的依赖关系

```
D-06 (Client 模型 + 数据存储)  ← 当前
  |--- Client.addServiceInstance → D-07 注册处理链用到
  |--- recalculateRevision → D-09 Distro V2 sync 用到
  |--- ClientReleaseEvent → D-08 健康检查/故障清理用到
  ↓
D-07 (注册处理链 + 事件驱动索引系统) ← 事件驱动索引基于 Client 模型的 publishers/subscribers
D-09 (Distro V2 协议) ← 同步 whole Client, not per Service
```
