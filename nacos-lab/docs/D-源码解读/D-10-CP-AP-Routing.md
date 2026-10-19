# D-10: CP/AP 双模式路由 + JRaft 整合

> 前置依赖: D-06（Client 模型, ephemeral 标志）、D-09（Distro V2, AP 实现）
> 后续: D-11（配置中心）

---

## 一、概述

D-09 覆盖了 AP 模式的 Distro 协议。但 Nacos 同时支持 CP 模式(基于 SOFAJRaft)。这篇解释 Nacos 如何在同一个系统中同时提供 CP 和 AP 两种一致性保证--这是 Nacos 相比 Eureka(纯 AP) 和 ZooKeeper(纯 CP) 的**核心差异化能力**。

---

## 二、CP/AP 路由决策点

### 2.1 ClientOperationServiceProxy - 代理路由

```java
// core/v2/service/ClientOperationServiceProxy.java
@Component
public class ClientOperationServiceProxy implements ClientOperationService {

    private final ClientOperationService ephemeralClientOperationService;   // AP/Distro
    private final ClientOperationService persistentClientOperationService;  // CP/JRaft

    private ClientOperationService chooseClientOperationService(Instance instance) {
        return instance.isEphemeral()
            ? ephemeralClientOperationService      // AP: EphemeralClientOperationServiceImpl
            : persistentClientOperationService;    // CP: PersistentClientOperationServiceImpl
    }
}
```

**一行三元运算符**决定走 AP 还是 CP。

**但它不是 gRPC 数据面的路由点**（常见误解）：
- **gRPC 数据面**：客户端先按 `instance.isEphemeral()` 分流——临时 → `InstanceRequest` → `InstanceRequestHandler`（`Service.newService(..., true)` 硬编码）；持久 → `PersistentInstanceRequest` → `PersistentInstanceRequestHandler`（`Service.newService(..., false)`）——**两个独立 Handler 各自直连对应实现，不经过 Proxy**（见 A-01）。
- **控制台/OpenAPI 管理面**：`InstanceOperatorClientImpl` 持有 `ClientOperationServiceProxy`（admin 接口传入的 Instance 类型不定）→ 三元路由。
- 两条路径最终都落到 `Ephemeral/PersistentClientOperationServiceImpl`。

### 2.2 路由结果

| Instance.ephemeral | 路由到 | 一致性协议 | 写入路径 |
|---|---|---|---|
| `true`(默认) | EphemeralClientOperationServiceImpl | Distro (AP) | Client 内存 -> ClientChangedEvent -> Distro sync |
| `false` | PersistentClientOperationServiceImpl | JRaft (CP) | Raft log -> 多数派确认 -> onApply -> Client 内存 |

### 2.3 订阅始终走 AP

```java
// ClientOperationServiceProxy
public void subscribeService(Service service, Subscriber subscriber, String clientId) {
    // Subscriber is an ephemeral type only, so call ephemeral client directly
    ephemeralClientOperationService.subscribeService(service, subscriber, clientId);
}
```

订阅(Subscriber)只有临时类型,不走 CP。持久实例的 Client 不支持订阅(`PersistentClientOperationServiceImpl.subscribeService` 抛 `UnsupportedOperationException`)。

---

## 三、AP 路径(复习 D-09)

```
EphemeralClientOperationServiceImpl.registerInstance():
  -> client.addServiceInstance()           // 直接写内存
    // 内部发 ClientChangedEvent -> DistroClientDataProcessor -> Distro sync(D-09)
  -> client.recalculateRevision()
  -> publishEvent(ClientRegisterServiceEvent)  // -> 索引更新 -> ServiceChangedEvent -> Push(D-07/D-08)
```

**特点**: 单节点直接写内存 -> 立即成功(AP, 不等集群确认)。延迟微秒级。数据在本节点 + Distro 异步同步(两条并行事件链: ClientChangedEvent 触发 Distro sync, ClientRegisterServiceEvent 触发索引+推送)。

---

## 四、CP 路径 - PersistentClientOperationServiceImpl + JRaft

### 4.1 写入流程

```java
// PersistentClientOperationServiceImpl.java
public void registerInstance(Service service, Instance instance, String clientId) {
    Service singleton = ServiceManager.getInstance().getSingleton(service);
    if (singleton.isEphemeral()) {
        throw new NacosRuntimeException(INVALID_PARAM,
            "Current service is ephemeral, can't register persistent instance.");
    }

    // 构建 Raft 写入请求
    InstanceStoreRequest request = new InstanceStoreRequest(service, instance, clientId);
    WriteRequest writeRequest = WriteRequest.newBuilder()
        .setGroup(group())                                    // "naming_persistent_service_v2"
        .setData(ByteString.copyFrom(serializer.serialize(request)))
        .setOperation(DataOperation.ADD.name())
        .build();

    // 通过 CPProtocol 写入(JRaft 共识)
    protocol.write(writeRequest);
}
```

### 4.2 Raft 共识流程

```
protocol.write(writeRequest)
  -> CPProtocol(JRaft 实现)
    -> Leader 收到 WriteRequest
    -> 转化为 Raft log entry
    -> 复制到所有 Follower
    -> 等待多数派确认(N/2 + 1)
    -> commit
    -> 回调 onApply(WriteRequest)
```

### 4.3 onApply - 状态机应用

```java
// PersistentClientOperationServiceImpl.java
public Response onApply(WriteRequest request) {
    InstanceStoreRequest instanceRequest = serializer.deserialize(request.getData().toByteArray());
    switch (DataOperation.valueOf(request.getOperation())) {
        case ADD:
            onInstanceRegister(instanceRequest.service, instanceRequest.instance, instanceRequest.clientId);
            break;
        case DELETE:
            onInstanceDeregister(instanceRequest.service, instanceRequest.clientId);
            break;
        case CHANGE:
            if (instanceAndServiceExist(instanceRequest)) {
                onInstanceRegister(instanceRequest.service, instanceRequest.instance, instanceRequest.clientId);
            }
            break;
    }
    return Response.newBuilder().setSuccess(true).build();
}

private void onInstanceRegister(Service service, Instance instance, String clientId) {
    Service singleton = ServiceManager.getInstance().getSingleton(service);
    if (!clientManager.contains(clientId)) {
        clientManager.clientConnected(clientId, new ClientAttributes());  // 创建持久 Client
    }
    Client client = clientManager.getClient(clientId);
    client.addServiceInstance(singleton, getPublishInfo(instance));  // 存入 Client 内存
    client.setLastUpdatedTime();
    NotifyCenter.publishEvent(new ClientRegisterServiceEvent(singleton, clientId));  // 触发索引 + 推送
}
```

**关键**: onApply 的操作与 AP 路径几乎相同 -- 都是 `client.addServiceInstance` + 发 `ClientRegisterServiceEvent`。区别在于:
- **AP**: 直接调 `addServiceInstance`(不等共识)
- **CP**: 先走 Raft 共识, commit 后才调 `addServiceInstance`

CP 保证了在 `addServiceInstance` 执行前, 这个写操作已经被多数派确认。如果 Leader 挂了, 已 commit 的日志不会丢失(新 Leader 会重放)。

### 4.4 持久化机制 - JRaft Log + Snapshot

持久实例的数据持久化**不靠 Derby/MySQL**(那是 Config 的),而是靠:

1. **Raft Log**: 每个写操作(ADD/DELETE/CHANGE)都记录在 Raft 日志中。日志在多数派节点上持久化到磁盘。
2. **Snapshot**: 定期将所有持久 Client 的 `ClientSyncData` 序列化为 zip 文件(`persistent_instance.zip`)。JRaft 日志 compaction 后, 通过 snapshot 恢复。

```java
// PersistentInstanceSnapshotOperation - snapshot 保存
protected InputStream dumpSnapshot() {
    Map<String, IpPortBasedClient> clientMap = clientManager.showClients();
    ConcurrentHashMap<String, ClientSyncData> clone = new ConcurrentHashMap<>();
    clientMap.forEach((clientId, client) -> clone.put(clientId, client.generateSyncData()));
    return new ByteArrayInputStream(serializer.serialize(clone));
}
```

**恢复流程**: Nacos 重启 -> JRaft 从 snapshot 加载 -> 反序列化 -> 重建 Client 内存索引 -> 发布 ClientRegisterServiceEvent。

---

## 五、CP vs AP 完整对比

| 维度 | AP (Ephemeral/Distro) | CP (Persistent/JRaft) |
|------|----------------------|----------------------|
| 入口服务 | EphemeralClientOperationServiceImpl | PersistentClientOperationServiceImpl |
| 一致性协议 | Distro V2 | JRaft (SOFAJRaft) |
| 写入路径 | Client 内存 -> ClientChangedEvent -> Distro sync | Raft log -> 多数派确认 -> onApply -> Client 内存 |
| 数据持久化 | 仅内存(重启丢失) | JRaft log + snapshot(zip) |
| 写入延迟 | 微秒级(纯内存) | 毫秒级(Raft 共识) |
| 可用性 | 单节点可写 | 需要多数派存活 |
| 连接断开 | 实例自动删除 | 仅标记不健康,数据保留 |
| 订阅 | 支持 | 不支持(UnsupportedOperationException) |
| 批量注册 | 支持 | TODO 未实现 |
| 集群同步 | Distro sync(allMembers) | Raft log 复制(多数派) |

### 关键: 两者共用 Client 模型

CP 和 AP 都使用 D-06 的 Client 模型存储数据:
- AP: ConnectionBasedClient(始终 ephemeral)
- CP: IpPortBasedClient(ephemeral=false, 持久)

两者都通过 `client.addServiceInstance` 存储, 都通过 `ClientOperationEvent` 触发索引更新和推送。**区别只在写入路径**(是否经过 Raft 共识), 存储和通知机制完全相同。

---

## 六、CAP 理论在 Nacos 中的实际权衡

```
Nacos 的 CAP 策略: "按实例类型分叉"

临时实例(ephemeral=true):
  -> AP 模式
  -> 允许: 网络分区期间, 分区两边的节点都接受注册
  -> 不允许: 因为一致性等待导致注册操作失败
  -> 适用: 微服务实例(95%+ 场景)

持久实例(ephemeral=false):
  -> CP 模式
  -> 允许: 牺牲可用性换取数据一致性
  -> 不允许: 网络分区时脑裂导致数据不一致
  -> 适用: 数据库节点、DNS 条目(基础设施)
```

**为什么不在所有场景都选 CP?**
- CP 需要多数派存活才能写。3 节点集群挂 1 个 -> 可用。挂 2 个 -> 不可用。
- 微服务实例注册频率高(秒级), 不可用 1 分钟 = 数百次注册失败。
- 但临时实例的重启 = 重新注册, 短暂丢失可接受 -> AP 是合理选择。

**为什么不在所有场景都选 AP?**
- 基础设施数据(DB 节点/DNS)需要强一致性和持久性。
- 这些数据往往是手工注册的, 不能在 Nacos 重启后丢失。
- AP 模式的数据仅内存, 重启全丢 -> 不可接受。

---

## 七、设计哲学分析

### 7.1 CAP 场景化解耦

不是"全系统选 AP"或"全系统选 CP", 而是"每个实例可以独立选"。决策依据: `Instance.ephemeral`。比 Eureka(强制 AP)和 ZK(强制 CP)更灵活。本质: **不同数据有不同的一致性需求**。

### 7.2 Strategy + Proxy 模式

`ClientOperationServiceProxy` 用代理模式统一入口, `chooseClientOperationService` 用策略模式选择实现。AP 和 CP 实现完全解耦 -> 未来可以换协议(如 CP 从 JRaft 换成其他 Raft 实现)。

### 7.3 共用 Client 模型 - 存储统一

CP 和 AP 都用 `client.addServiceInstance` 存储, 都发 `ClientRegisterServiceEvent`。差异只在写入前的共识过程。这使得索引、推送、查询等下游系统不需要区分 AP/CP -- 它们看到的都是统一的 Client 模型和事件。

---

## 八、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|------|------|------|
| 1 | 全系统只选 AP(像 Eureka) | 无法支持持久实例。DB 节点注册的数据重启后丢失 | 按 ephemeral 分叉: AP + CP |
| 2 | 全系统只选 CP(像 ZK) | 微服务高频注册 = 每次等 Raft 多数派 -> 注册 RT 数十 ms。节点不可用 = 注册全部失败 | 临时走 AP, 持久走 CP |
| 3 | 没有 Proxy 层 | InstanceOperator 需写 if-else -> 耦合 | 代理模式统一入口 |
| 4 | 持久实例不持久化, 只依赖 Raft log | log compaction 后旧日志丢失 -> 数据不完整 | Raft log + snapshot(zip)双保障 |
| 5 | 持久实例查询也走 Raft read-index | 每次查询 Raft 共识 -> 查询 RT 数十 ms | 查询走内存索引(与临时实例共用) |
| 6 | ephemeral 可在注册后修改 | 同一 Service 的两个实例 ephemeral 不同 -> 协议混乱 | ephemeral 由 Service 首次注册决定, 不可变更 |

---

## 九、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|------|------|------|
| 1 | 不理解 ephemeral, 随便选 | 持久实例选 ephemeral=true -> 重启丢失 | 默认 ephemeral=true; 基础设施需显式设 false |
| 2 | CP 模式 3 节点挂 2 个尝试注册 | 1 节点无法满足多数派 -> Raft 写拒绝 -> 注册失败 | CP 至少需 2 节点存活(3 节点集群) |
| 3 | 持久和临时实例混合在同一 Service | 第二个不同类型被拒绝 | EphemeralClientOperationServiceImpl 校验 isEphemeral 一致性 |
| 4 | 持久实例注册后不主动注销 | 僵尸实例永久保留 | 持久实例不会自动清理(by design) |
| 5 | 认为 CP 模式数据通过 Distro sync | CP 走 Raft log 复制, 不是 Distro | DistroClientDataProcessor 的 isInvalidClient 过滤 !ephemeral |

---

## 十、面试 Q&A

### Q1: CAP 理论中, 注册中心应该选 CP 还是 AP? Nacos 怎么做的?

Nacos 两者都支持: `ephemeral->AP(Distro)`, `!ephemeral->CP(JRaft)`。注册中心大多数场景选 AP(允许短暂不一致, 不允许不可用)。但基础设施注册(DB 节点)需要 CP(数据不能丢)。分叉关键: `ClientOperationServiceProxy.chooseClientOperationService`。对比: Eureka 只能 AP, ZK 只能 CP。

### Q2: JRaft 在 Nacos 中的角色?

持久实例的注册/注销通过 `CPProtocol.write()` -> JRaft -> commit -> `onApply()` 写入状态机。Raft 负责: Leader 选举 + 日志复制 + 回调 onApply。不用于: 临时实例(走 Distro)、查询(走内存索引)。

### Q3: 持久实例的数据在 Nacos 重启后会丢吗?

不会。持久实例写入 Raft log + snapshot(zip)。重启: JRaft 从 snapshot 加载 -> 重建 Client 内存 -> 发 ClientRegisterServiceEvent。注意: 持久实例的持久化靠 **JRaft log + snapshot**, 不是 Derby/MySQL(那是 Config 用的)。

### Q4: 为什么不在 CP 模式用 Distro, 或在 AP 模式用 Raft?

Distro 不持久化 -> 不符合 CP 的持久性要求。Raft 有写延迟(多数派确认) -> 不符合 AP 的高频写 + 低延迟需求。两种协议各有优势, Nacos 通过 ephemeral 标志做到"按需选择"。

### Q5: 集群中 CP 节点挂了多数, 会发生什么? AP 节点呢?

**面试官意图**: 考察 CP/AP 故障模式差异。

CP(3 节点挂 2 个): 剩余 1 个无法满足多数派 -> Raft 写拒绝 -> 持久实例注册失败。但查询正常(走内存索引)。临时实例注册正常(AP)。

AP(3 节点挂 2 个): 剩余 1 个继续接收临时实例注册 -> Distro sync 无目标 -> 数据只在本地。节点恢复后通过 verify 反熵同步。

---

## 十一、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| ClientOperationServiceProxy | CP/AP 路由代理, `ephemeral ? AP : CP` | `core/v2/service/ClientOperationServiceProxy.java` |
| PersistentClientOperationServiceImpl | CP 路径, extends RequestProcessor4CP, Raft 写入 | `core/v2/service/impl/PersistentClientOperationServiceImpl.java` |
| EphemeralClientOperationServiceImpl | AP 路径, 直接写内存 + Distro sync | `core/v2/service/impl/EphemeralClientOperationServiceImpl.java` |
| CPProtocol | CP 协议抽象接口(JRaft 实现) | `consistency/cp/CPProtocol.java` |
| RequestProcessor4CP | Raft 状态机抽象(onApply 回调) | `consistency/cp/RequestProcessor4CP.java` |
| onApply | Raft commit 后回调, 执行 addServiceInstance + 发事件 | `PersistentClientOperationServiceImpl.onApply()` |
| PersistentInstanceSnapshotOperation | JRaft snapshot 保存/加载(zip) | `PersistentClientOperationServiceImpl` 内部类 |
