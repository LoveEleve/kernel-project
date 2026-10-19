# D-09: Distro V2 协议 - Nacos 自研 AP 一致性协议

> 前置依赖: D-06（Client 模型, revision 机制）、D-07（事件驱动, ClientChangedEvent）
> 后续: D-10（CP/AP 双模式路由）

---

## 一、概述

D-08 覆盖了单个 Nacos 节点内的 Push + 健康检查 + 故障自愈。但 Nacos 是集群部署的,节点间需要同步数据。Distro 是 Nacos 自研的最终一致性协议,是 AP 模式下集群数据同步的基础。

这是面试注册中心开发岗位的**必考题**--理解了 Distro 就理解了 Nacos 如何在 AP 条件下保证数据最终一致。

---

## 二、Distro 协议五大操作

```
DistroProtocol 提供五个核心操作:

1. sync (同步) - 推送: 本地 Client 数据变更 -> 推送到所有其他节点
2. onReceive (接收) - 拉取: 收到其他节点的同步数据 -> 合并
3. onVerify (校验/反熵) - 定时: 交换 revision -> 不一致 -> 全量 sync
4. onQuery (查询) - 按需: 新节点加入时向其他节点查询数据
5. onSnapshot (快照) - 批量: 获取所有 Client 的快照 -> 新节点快速加载
```

---

## 三、sync - 推送同步(事件驱动)

### 3.1 触发源

Distro 同步由 `DistroClientDataProcessor` 监听 `ClientEvent` 触发:

```java
// DistroClientDataProcessor.java
public class DistroClientDataProcessor extends SmartSubscriber
    implements DistroDataStorage, DistroDataProcessor {

    // 订阅三种 ClientEvent
    public List<Class<? extends Event>> subscribeTypes() {
        return List.of(
            ClientEvent.ClientChangedEvent.class,      // Client 数据变更(注册/注销)
            ClientEvent.ClientDisconnectEvent.class,    // Client 断开
            ClientEvent.ClientVerifyFailedEvent.class   // verify 失败
        );
    }
}
```

> 注意: DistroClientDataProcessor 订阅的是 **ClientEvent**(由 `AbstractClient.addServiceInstance` 发布),不是 ClientOperationEvent(由 `EphemeralClientOperationServiceImpl` 发布)。ClientChangedEvent 是更底层的"Client 数据变了"信号,无论是本地注册还是远端同步都会触发。

### 3.2 syncToAllServer - 三重过滤 + 发送给所有成员

```java
private void syncToAllServer(ClientEvent event) {
    Client client = event.getClient();
    if (isInvalidClient(client)) return;  // 三重过滤

    if (event instanceof ClientDisconnectEvent) {
        distroProtocol.sync(distroKey, DataOperation.DELETE);
    } else if (event instanceof ClientChangedEvent) {
        distroProtocol.sync(distroKey, DataOperation.CHANGE);
    }
}

private boolean isInvalidClient(Client client) {
    // 1. null -> 跳过
    // 2. !ephemeral -> 持久实例不走 Distro(走 Raft)
    // 3. !isResponsibleClient -> 只有负责节点才触发 sync
    return null == client || !client.isEphemeral() || !clientManager.isResponsibleClient(client);
}
```

**三重过滤**:
1. `client == null` -- Client 已不存在
2. `!client.isEphemeral()` -- 持久实例不走 Distro(走 JRaft, D-10)
3. `!clientManager.isResponsibleClient(client)` -- 只有 DistroMapper 分配的**负责节点**才触发 sync

### 3.3 DistroProtocol.sync - 发送给所有其他成员

```java
// DistroProtocol.java
public void sync(DistroKey distroKey, DataOperation action, long delay) {
    for (Member each : memberManager.allMembersWithoutSelf()) {
        syncToTarget(distroKey, action, each.getAddress(), delay);
    }
}

public void syncToTarget(DistroKey distroKey, DataOperation action, String targetServer, long delay) {
    DistroKey distroKeyWithTarget = new DistroKey(
        distroKey.getResourceKey(), distroKey.getResourceType(), targetServer);
    DistroDelayTask distroDelayTask = new DistroDelayTask(distroKeyWithTarget, action, delay);
    distroTaskEngineHolder.getDelayTaskExecuteEngine().addTask(distroKeyWithTarget, distroDelayTask);
}
```

**关键**: sync 发送给 `allMembersWithoutSelf()` -- **所有其他成员**,不只是负责节点。发送端已通过 `isResponsibleClient` 过滤:只有负责节点触发 sync,但 sync 目标是所有成员 -> 1 -> N 传播模式。

**DistroDelayTask**: 使用 `NacosDelayTaskExecuteEngine`(与 PushDelayTask 同引擎),默认延迟 1s(`syncDelayMillis=1000ms`)。同一个 Client 的多次变更会合并。

### 3.4 V2 的核心: 同步整个 Client

V2 sync 携带的是整个 `ClientSyncData`(该 Client 的所有 **publishers + revision**,不含 subscribers),不是单个 Service。这与 D-06 的 Client 中心模型对齐:数据存储在 Client 上,同步也是 Client 粒度。订阅数据(subscribers)不需要 Distro 同步--每个客户端直接向每个节点发 `SubscribeServiceRequest`,订阅索引是每节点独立的。

---

## 四、onReceive - 接收合并

```java
// DistroProtocol.java
public boolean onReceive(DistroData distroData) {
    DistroDataProcessor processor = distroComponentHolder.findDataProcessor(resourceType);
    return processor.processData(distroData);
}

// DistroClientDataProcessor.java
public boolean processData(DistroData distroData) {
    switch (distroData.getType()) {
        case ADD:
        case CHANGE:
            ClientSyncData clientSyncData = deserialize(distroData.getContent());
            handlerClientSyncData(clientSyncData);  // 合并
            return true;
        case DELETE:
            clientManager.clientDisconnected(deleteClientId);  // 删除
            return true;
    }
}
```

### 4.1 upgradeClient - diff 合并

```java
private void upgradeClient(Client client, ClientSyncData clientSyncData) {
    Set<Service> syncedService = new HashSet<>();

    // 遍历同步数据中的每个 service
    for (int i = 0; i < namespaces.size(); i++) {
        Service service = Service.newService(namespaces.get(i), groupNames.get(i), serviceNames.get(i));
        Service singleton = ServiceManager.getInstance().getSingleton(service);
        syncedService.add(singleton);
        InstancePublishInfo info = instances.get(i);

        // diff: 只有数据不同才更新 + 发事件
        if (!info.equals(client.getInstancePublishInfo(singleton))) {
            client.addServiceInstance(singleton, info);
            NotifyCenter.publishEvent(new ClientRegisterServiceEvent(singleton, client.getClientId()));
            NotifyCenter.publishEvent(new InstanceMetadataEvent(singleton, info.getMetadataId(), false));
        }
    }

    // 本地有但同步数据中没有的 -> 移除
    for (Service each : client.getAllPublishedService()) {
        if (!syncedService.contains(each)) {
            client.removeServiceInstance(each);
            NotifyCenter.publishEvent(new ClientDeregisterServiceEvent(each, client.getClientId()));
        }
    }

    client.setRevision(clientSyncData.getAttributes().getClientAttribute(ClientConstants.REVISION, 0L));
}
```

**diff 合并机制**: 不是简单的"覆盖",而是逐 service 比较 `InstancePublishInfo.equals()`:
- **不同** -> `addServiceInstance`(覆盖写) + 发 ClientRegisterServiceEvent(触发索引更新 + 推送)
- **相同** -> 跳过(避免无意义的索引更新和推送)
- **本地有, 同步数据没有** -> `removeServiceInstance` + 发 ClientDeregisterServiceEvent

> 注意: 不是用 revision 比较(计划中的"incoming > local -> 覆盖"描述有误),而是用 InstancePublishInfo.equals 逐条 diff。revision 只是最后设置(`client.setRevision`),用于 verify 校验。

---

## 五、onVerify - 定时反熵(Anti-Entropy)

### 5.1 反熵任务

```java
// DistroVerifyTimedTask.java - 每 5s 执行一次(verifyIntervalMillis=5000ms)
public void run() {
    List<Member> targetServer = serverMemberManager.allMembersWithoutSelf();
    for (String type : distroComponentHolder.getDataStorageTypes()) {
        verifyForDataStorage(type, targetServer);
    }
}

private void verifyForDataStorage(String type, List<Member> targetServer) {
    if (!dataStorage.isFinishInitial()) return;  // 未初始化完成 -> 跳过
    List<DistroData> verifyData = dataStorage.getVerifyData();  // 只有 responsible client
    for (Member member : targetServer) {
        executeTaskExecuteEngine.addTask(member.getAddress() + type,
            new DistroVerifyExecuteTask(agent, verifyData, member.getAddress(), type));
    }
}
```

### 5.2 getVerifyData - 只发负责节点的数据

```java
// DistroClientDataProcessor.java
public List<DistroData> getVerifyData() {
    for (String each : clientManager.allClientId()) {
        Client client = clientManager.getClient(each);
        if (client == null || !client.isEphemeral()) continue;
        if (clientManager.isResponsibleClient(client)) {  // 只发负责的
            DistroClientVerifyInfo verifyInfo = new DistroClientVerifyInfo(
                client.getClientId(), client.getRevision());  // 只传 clientId + revision
            // ... 构建 DistroData
        }
    }
}
```

**verify 数据极小**: 每个_client 只传 `clientId + revision`(几十字节),不传完整数据。

### 5.3 processVerifyData - revision 比较

```java
public boolean processVerifyData(DistroData distroData, String sourceAddress) {
    DistroClientVerifyInfo verifyData = deserialize(distroData.getContent());
    if (clientManager.verifyClient(verifyData)) {
        return true;   // 一致
    }
    return false;  // 不一致 -> 触发重新同步
}
```

`clientManager.verifyClient` 比较本地 Client 的 revision 与远端发来的 revision。如果不一致,verify 返回 false。**发送方(负责节点)**收到失败响应后,发布 `ClientVerifyFailedEvent(targetServer=不一致的节点)` -> `syncToVerifyFailedServer` -> 将正确数据**推送**给不一致的节点(延迟 0, 立即同步)。注意:是负责节点推数据给不一致节点,不是拉取。

### 5.4 推 + 拉双重保障

```
sync  = 事件驱动推送(快速, 但可能丢失)
verify = 定时反熵拉取(5s 一次, 保证最终一致)
```

- sync 可能在网络故障时丢失
- verify 每 5s 检查一次,发现 revision 不一致 -> 立即全量 sync 补偿
- 类似 TCP 的 ACK + 超时重传

---

## 六、onQuery + onSnapshot - 新节点加载数据

```
[onQuery: 按需查询]
新节点启动 -> DistroLoadDataTask
  -> 根据 DistroMapper 计算新节点应负责哪些 Client
  -> 向对应节点发送 query 请求
  -> DistroProtocol.onQuery -> getDistroData -> 返回该 Client 的完整数据

[onSnapshot: 批量快照]
  -> DistroProtocol.onSnapshot -> getDatumSnapshot
  -> 返回所有临时 Client 的快照
```

**新节点启动流程**:
1. `DistroLoadDataTask` 执行 -> 从其他节点加载负责的 Client 数据
2. 加载成功 -> `isInitialized = true` -> 开始对外服务
3. 加载过程中: 其他节点可能还在 sync 新数据 -> 加载完后通过 verify 反熵再补偿

**新节点从启动到数据完整 = loadTime + 至少一个 verifyInterval(5s)**

---

## 七、DistroMapper - 简单取模路由

### 7.1 路由算法

```java
// DistroMapper.java
public boolean responsible(String responsibleTag) {
    if (!switchDomain.isDistroEnabled() || EnvUtil.getStandaloneMode()) return true;
    if (CollectionUtils.isEmpty(servers)) return false;

    int index = servers.indexOf(localAddress);
    int target = distroHash(responsibleTag) % servers.size();
    return target >= index && target <= lastIndex;
}

private int distroHash(String responsibleTag) {
    return Math.abs(responsibleTag.hashCode() % Integer.MAX_VALUE);
}
```

**简单取模**: `Math.abs(tag.hashCode()) % serverCount`。不是一致性哈希,没有虚拟节点。

### 7.2 节点列表一致性保证

```java
public void onEvent(MembersChangeEvent event) {
    List<String> list = MemberUtil.simpleMembers(
        MemberUtil.selectTargetMembers(event.getMembers(),
            member -> UP.equals(member.getState()) || SUSPICIOUS.equals(member.getState())));
    Collections.sort(list);  // 排序! 保证所有节点列表顺序一致
    healthyList = Collections.unmodifiableList(list);
}
```

所有节点对 `healthyList` 做同样的排序,保证 `hashCode(tag) % size` 在所有节点上计算结果一致。

### 7.3 节点变更的影响

简单取模的特性: 节点加入/离开时 `serverCount` 变化 -> **几乎所有** tag 的负责节点都会改变。与一致性哈希不同(只有 1/N 的 key 需要迁移),简单取模会导致大规模数据迁移。

但 Nacos 选择简单取模的原因: 注册中心集群规模通常很小(3-5 节点),节点变更不频繁。简单取模实现简单,且 Distro 的反熵机制(5s verify)能快速修复迁移期间的数据不一致。工程取舍: 简单 > 完美。

---

## 八、DistroConfig 关键参数

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `syncDelayMillis` | 1000ms (1s) | sync 延迟合并窗口(DistroDelayTask) |
| `verifyIntervalMillis` | 5000ms (5s) | verify 反熵检查间隔 |
| `loadDataRetryDelayMillis` | 30000ms (30s) | 加载数据失败后重试间隔 |
| `syncTimeoutMillis` | - | sync 操作超时 |
| `verifyTimeoutMillis` | - | verify 操作超时 |

所有参数可通过 `EnvUtil.getProperty` 动态配置。

---

## 九、设计哲学分析

### 9.1 推 + 拉双重保障

```
sync  = 事件驱动推送(快速, 但可能丢失)
verify = 定时反熵拉取(5s, 保证最终一致)
```

类似 TCP 的 ACK + 超时重传。正常走快速路径, 异常走慢速补偿。Dynamo 的 anti-entropy + hinted handoff 有类似设计。

### 9.2 V2 的"同步整个 Client" vs V1 的"同步单个 Service"

V1: 每个 Service 变更 -> 一次 sync -> N 个 Service = N 次请求。V2: 一个 Client 变更 -> 一次 sync(携带完整 Client) -> 大幅减少请求。与 D-06 的 Client 中心模型对齐: 数据在 Client -> 同步也是 Client 粒度。

### 9.3 简单取模 vs 一致性哈希

Nacos 用简单取模, 不用一致性哈希。原因: 集群规模小(3-5 节点)、节点变更少、verify 反熵能快速修复。工程取舍: 简单 > 完美。

---

## 十、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|------|------|------|
| 1 | V2 继续 V1 的 per-Service sync | 100 个 Service = 100 次 gRPC 请求 | V2 的 per-Client sync: 一次携带全部 |
| 2 | 只做 sync 不做 verify | 事件丢失 -> 数据永远不一致 | 推 + 拉双重保障 |
| 3 | verify 传完整 Client 数据 | 10K Client x 完整数据 x 5s -> 巨大带宽 | verify 只传 clientId + revision(几十字节) |
| 4 | revision 用单调递增数字 | 需要全局协调, AP 模式下不适合 | AbstractClient 用 hash; ConnectionBasedClient 用本地递增 |
| 5 | 每个节点都触发 sync(不做 isResponsibleClient 过滤) | N x (N-1) 次重复同步 -> 数据乱序覆盖 | 只有负责节点触发, 目标为所有成员(1 -> N) |
| 6 | 新节点不做 load, 只依赖 sync | 等所有 Client 发生变更才能获取 -> 数小时 | DistroLoadDataTask + onQuery -> 启动时主动拉取 |
| 7 | 用一致性哈希替代简单取模 | 节点变更时迁移量小, 但实现复杂 | 简单取模: 集群小 + verify 补偿, 够用 |

---

## 十一、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|------|------|------|
| 1 | 认为 sync 是同步的(等所有节点确认) | 等集群确认 -> 注册 RT 秒级 | sync 异步(DistroDelayTask 1s 延迟) |
| 2 | 混淆 V1 和 V2 的同步粒度 | V1: per-Service。V2: per-Client | V2 全部使用 DistroClientDataProcessor |
| 3 | 不配置 verifyInterval | 默认 5s 可能不适合大规模集群 | 根据规模调整: 小集群 5s, 大集群 30s |
| 4 | 认为 revision 能防并发冲突 | revision 相同 不等于 数据一致; LWW 可能丢更新 | 不依赖 revision 做强一致; 临时用 AP, 持久用 CP |
| 5 | 新节点启动后不等 load 完成就处理请求 | 负责的数据未同步完 -> 查询不完整 | 检查 isInitialized -> true 后才服务 |

---

## 十二、面试 Q&A

### Q1: Distro 协议的工作流程?

sync(push): ClientChangedEvent -> isResponsibleClient 过滤 -> DistroDelayTask(1s) -> allMembersWithoutSelf -> gRPC。verify(pull): 每 5s 发 clientId+revision -> 不一致 -> 全量 sync。snapshot: 新节点启动批量加载。推 + 拉 = 双重保障最终一致。

### Q2: Distro V2 相比 V1 的核心改进?

V1: per-Service sync -> N 次请求。V2: per-Client sync -> 一次携带全部 -> 大幅减少请求。与 Client 中心模型对齐。架构决策一致性体现。

### Q3: DistroMapper 的路由算法?

**面试官意图**: 考察分布式路由。

简单取模: `Math.abs(tag.hashCode()) % serverCount`。不是一致性哈希, 没有虚拟节点。所有节点排序 healthyList 保证一致。节点变更 -> 几乎所有 tag 重新分配。但集群小(3-5 节点) + verify 反熵(5s)能快速修复, 工程上够用。

### Q4: revision 校验能防止脑裂后数据不一致吗?

不能。revision 是 hash/递增, 不是 timestamp。hash 相同 不等于 数据一致。脑裂时两个分区同时修改 -> diff 合并(InstancePublishInfo.equals) -> 最后收到者胜出。verify 能在分区合并后检测不一致并修复。真正防脑裂需要 CP(Raft, D-10)。

### Q5: 集群中一个节点挂了又起来, Distro 如何恢复?

新节点启动 -> DistroLoadDataTask -> 向其他节点 query/snapshot。isInitialized=false -> 不服务。load 完成后 -> isInitialized=true。其他节点可能还在 sync 新数据 -> load 后通过 verify(5s)反熵补偿。新节点从启动到数据完整 = loadTime + 至少一个 verifyInterval。

---

## 十三、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| DistroProtocol | Distro 协议核心, 5 大操作(sync/onReceive/onVerify/onQuery/onSnapshot) | `core/distributed/distro/DistroProtocol.java` |
| DistroClientDataProcessor | V2 事件消费者, 订阅 ClientEvent, isResponsibleClient 过滤 | `consistency/ephemeral/distro/v2/DistroClientDataProcessor.java` |
| DistroMapper | 简单取模路由(非一致性哈希), responsible 判断 | `core/DistroMapper.java` |
| DistroVerifyTimedTask | 5s 反熵, 发 clientId+revision 到所有其他节点 | `distro/task/verify/DistroVerifyTimedTask.java` |
| DistroDelayTask | sync 延迟合并(1s), 同 Client 多次变更合并 | `distro/task/delay/DistroDelayTask.java` |
| syncDelayMillis | sync 延迟 = 1000ms | `DistroConstants.DEFAULT_DATA_SYNC_DELAY_MILLISECONDS` |
| verifyIntervalMillis | verify 间隔 = 5000ms | `DistroConstants.DEFAULT_DATA_VERIFY_INTERVAL_MILLISECONDS` |

---

## 十五、测试分析

### 15.1 DistroClientDataProcessorTest

`naming/src/test/java/com/alibaba/nacos/naming/consistency/ephemeral/distro/v2/DistroClientDataProcessorTest.java`

| 测试内容 | 验证点 |
|---------|--------|
| client 注册 | DistroClientDataProcessor 接收到 ClientChangedEvent -> 触发 sync |
| client 注销 | ClientDisconnectEvent -> 触发移除 + 同步 |
| verify 失败 | ClientVerifyFailedEvent -> 触发全量 sync |
| 数据接收 | onReceive 正确处理其他节点发来的 distro 数据 |

### 15.2 DistroLoadDataTaskTest

`core/src/test/java/com/alibaba/nacos/core/distributed/distro/task/load/DistroLoadDataTaskTest.java`

| 测试内容 | 验证点 |
|---------|--------|
| 新节点加载 | 从其他节点获取快照 -> 加载到本地 |
| 加载回调 | 加载完成后触发 `onEvent()` 或 `onException()` |

### 15.3 其他测试

| 测试文件 | 覆盖内容 |
|---------|---------|
| `DistroKeyTest` | DistroKey 的 equals/hashCode（用作 Map key） |
| `DistroDataTest` | DistroData 序列化/反序列化 |
| `DistroComponentHolderTest` | DistroComponentHolder 组件注册 |
| `DistroConfigTest` | Distro 配置加载 |
| `DistroRecordsHolderTest` | 延迟任务记录管理 |

## 十六、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Gossip** | Distro verify 反熵协议 | 节点间定期交换 revision，发现不一致后全量同步 |
| **Event-Driven** | `ClientChangedEvent` -> `DistroClientDataProcessor` -> `sync` | 变更事件驱动同步，非轮询 |
| **Delta Sync** | 基于 revision 的反熵 | 只传 revision，不一致才全量 sync |
| **Task Merge** | `DistroDelayTask` (1000ms 延迟合并) | 同一 Client 的多次变更在 1s 内合并为一次 sync |
| **Responsibility** | `DistroMapper.t 取模路由 | responsible 节点负责特定 Client 的主同步 |
| **Snapshot** | `onSnapshot()` 批量快照 | 新节点加入时快速加载，避免逐条 sync |

## 十七、本 Lab 中的实际映射

| Lab 场景 | 对应源码 |
|---------|---------|
| A-07 两个 nacos-lab 实例注册 | 每个实例在 Distro 中为独立 Client -> `DistroClientDataProcessor` 处理注册事件 |
| A-07 端口 28001/28003 实例数据同步 | `DistroProtocol.sync()` 将一个节点上的 Client 数据推送到另一节点 |
