# D-13: Nacos 注册中心设计全景 + 集群管理

> 前置依赖: D-01~D-12（全部）
> 后续: D-14（面试模拟 18 题）

---

## 一、概述

前 12 篇分析了 Nacos 的各个组件和子系统。这篇做两件事:
1. **全景串联**: 把 API -> SDK -> gRPC -> 注册处理 -> 事件索引 -> Push -> 健康检查 -> Distro -> CP/AP -> Config 串成一条完整链路
2. **集群管理**: 补充 ServerMemberManager + MemberLookup -- 分布式系统的"底座"

---

## 二、端到端完整调用链

### 2.1 Naming 注册(临时实例)

```
========== 客户端 SDK 层 (D-03) ==========
应用代码: namingService.registerInstance("user-service", "10.0.0.1", 8080)
  -> NacosNamingService -> 参数归一化 -> NamingClientProxyDelegate
  -> getExecuteClientProxy: ephemeral=true -> GrpcClientProxy
  -> redoService.cacheInstanceForRedo (write-ahead, D-03)
  -> doRegisterService: InstanceRequest(REGISTER_INSTANCE) -> gRPC unary -> :9848

========== 服务端 gRPC 连接层 (D-05) ==========
GrpcSdkServer(9848) -> GrpcRequestAcceptor -> RequestHandlerRegistry
  -> filter chain: @Secured(鉴权) -> @TpsControl(限流) -> @ExtractorManager(参数校验)
  -> InstanceRequestHandler.handle

========== 注册处理链 (D-06/D-07) ==========
InstanceRequestHandler.handle
  -> Service.newService(namespace, group, name, ephemeral=true)
  -> clientOperationServiceProxy.chooseClientOperationService(instance)
    -> ephemeral=true -> EphemeralClientOperationServiceImpl
  -> EphemeralClientOperationServiceImpl.registerInstance
    -> ServiceManager.getSingleton(service) (D-06)
    -> client.addServiceInstance(singleton, info) (D-06)
      -> AbstractClient: publishers.put + ClientChangedEvent
    -> client.recalculateRevision()

========== 事件驱动索引 (D-07) ==========
ClientChangedEvent
  -> DistroClientDataProcessor.syncToAllServer (D-09 Distro sync)

ClientChangedEvent
  -> ClientServiceIndexesManager.onEvent
    -> addPublisherIndexes(service, clientId)
      -> publisherIndexes[service].add(clientId)
      -> ServiceChangedEvent(service, incrementRevision=true)

========== Push 推送 (D-08) ==========
ServiceChangedEvent
  -> NamingSubscriberServiceV2Impl.onEvent
    -> PushDelayTask(service, 500ms)
    -> 500ms 到期 -> PushExecuteTask
      -> 遍历 subscriberIndexes[service] -> 所有订阅者
      -> PushExecutorDelegate: gRPC(2.x) / UDP(1.x)
      -> RpcPushService.pushWithCallback -> connection.asyncRequest (bi-di stream)
        -> 客户端 NamingPushRequestHandler -> ServiceInfoHolder -> listener.onEvent

========== 数据一致性 (D-09) ==========
ClientChangedEvent
  -> DistroClientDataProcessor.syncToAllServer
    -> isResponsibleClient 检查
    -> DistroProtocol.sync(distroKey, CHANGE)
      -> allMembersWithoutSelf -> DistroDelayTask(1s)
      -> gRPC -> 目标节点 onReceive -> upgradeClient (diff 合并)

========== 健康检查 (D-08) ==========
连接断开 -> ConnectionManager.unregister (D-05)
  -> 发布 ClientReleaseEvent (ClientOperationEvent)
    -> ClientServiceIndexesManager.handleClientDisconnect
      -> 清理 publisherIndexes + subscriberIndexes
      -> ServiceChangedEvent -> Push(通知订阅者实例下线)
  -> 发布 ClientDisconnectEvent (ClientEvent, 不同事件类型!)
    -> DistroClientDataProcessor.syncToAllServer(DELETE) -> 集群同步删除
```

### 2.2 Config 发布

```
========== 客户端 SDK 层 (D-04) ==========
应用代码: configService.publishConfig(dataId, group, content)
  -> NacosConfigService.publishConfigInner
  -> ConfigPublishRequest -> gRPC -> :9848

========== 服务端 (D-11) ==========
ConfigPublishRequestHandler.handle
  -> configInfoPersistService.insertOrUpdate (DB: Derby/MySQL)
    -> MD5 幂等: 相同内容不写 DB
  -> ConfigChangePublisher.notifyConfigChange(ConfigDataChangeEvent)

  [ConfigDataChangeEvent 消费者: AsyncNotifyService] (唯一消费者, D-11/D-12)
    -> allMembers(含自己)
      -> 自己: dumpService.dumpFormal -> ConfigCacheService 更新缓存 -> 发布 LocalDataChangeEvent
        -> RpcConfigChangeNotifier -> ConfigChangeNotifyRequest(gRPC 推送)
          -> 客户端: isConsistentWithServer=false -> executeConfigListen
            -> ConfigBatchListenRequest -> 服务端比对 MD5 -> 返回变更 dataId
            -> ConfigQueryRequest -> 拉取内容 -> setContent -> checkListenerMd5
        -> LongPollingService -> 唤醒 1.x HTTP 长轮询
      -> 其他成员: gRPC 通知
        -> 目标节点: DumpService.dump(从 DB 读取) -> 更新缓存 -> LocalDataChangeEvent -> 推送
```

---

## 三、Naming vs Config 全景对比

| 维度 | Naming | Config | 关键差异 |
|------|--------|--------|---------|
| 入口 | GrpcSdkServer(9848) | GrpcSdkServer(9848) / HTTP(8848) | Config 双入口(1.x HTTP 兼容) |
| 存储 | Client 内存 | DB(Derby/MySQL) + 内存缓存 | 数据源不同决定同步模式 |
| 同步 | Distro V2 (per-Client, allMembersWithoutSelf) | AsyncNotify (通知+拉取, allMembers含自己) | Push 数据 vs 通知 |
| 一致性 | AP(临时)/CP(持久) | AP(异步通知) | Config 有 DB, 不需要强一致 |
| 健康检查 | 连接驱动(毫秒) + 定时补偿(15s/30s) | 不适用 | Config 没有实例概念 |
| Push | ServiceChangedEvent -> PushDelayTask(500ms) | ConfigChangeNotifyRequest(gRPC 推送) | 推数据 vs 推通知 |
| 客户端监听 | gRPC bi-di stream 推送 ServiceInfo | gRPC bi-di stream 推送 ConfigChangeNotifyRequest + 5s ConfigBatchListenRequest | 全量推 vs 通知+拉取 |
| 集群管理 | 共享 ServerMemberManager | 共享 ServerMemberManager | 底座共用 |

---

## 四、集群管理 - ServerMemberManager

### 4.1 核心职责

```java
// core/cluster/ServerMemberManager.java
@Component("serverMemberManager")
public class ServerMemberManager implements ApplicationListener<WebServerInitializedEvent> {
    // 集群成员列表(按 address 排序的 ConcurrentSkipListMap)
    private volatile ConcurrentSkipListMap<String, Member> serverList;
    // 健康成员地址集合(只有 UP 状态)
    private volatile Set<String> memberAddressInfos;
    // 成员发现策略
    private MemberLookup lookup;
}
```

核心职责:
- 维护集群成员列表: `allMembers()` / `allMembersWithoutSelf()`
- 管理节点状态: UP / DOWN / SUSPICIOUS
- 发布成员变更事件: `MembersChangeEvent`
- 心跳检测: 定期向其他节点报告自己状态

### 4.2 启动流程

```
Spring Boot 启动 -> ServerMemberManager 构造
  -> init():
    -> 解析 self(ip:port, RAFT_PORT=port-1000)
    -> serverList.put(self)
    -> registerClusterEvent(注册 MembersChangeEvent publisher)
    -> initAndStartLookup(选择并启动成员发现策略)
  -> WebServerInitializedEvent:
    -> self.setState(UP)
    -> 启动心跳任务(MemberInfoReportTask 2s + UnhealthyMemberInfoReportTask 5s)
```

### 4.3 心跳检测

**MemberInfoReportTask**(每 2s):
- 轮询一个其他节点(cursor 递增)
- gRPC 或 HTTP 报告自己的元数据
- 成功 -> `MemberUtil.onSuccess`(状态 -> UP)
- 失败 -> `MemberUtil.onFail`(状态 -> SUSPICIOUS, 失败计数 +1)

**UnhealthyMemberInfoReportTask**(每 5s):
- 遍历所有非 UP 节点
- 主动探测, 尝试恢复

### 4.4 状态转换

```
探测成功: 任何状态 -> UP(failAccessCnt 重置为 0)
探测失败: 任何状态 -> SUSPICIOUS(failAccessCnt +1)
         failAccessCnt > 3 或 "Connection refused" -> DOWN
```

**memberAddressInfos**(健康集合):
- UP -> add 到 memberAddressInfos
- DOWN/SUSPICIOUS -> 从 memberAddressInfos 移除
- AsyncNotifyService 和 DistroMapper 使用 memberAddressInfos 判断哪些节点可用

---

## 五、MemberLookup - 三种节点发现模式

### 5.1 LookupFactory 选择规则

```java
// LookupFactory.createLookUp
if (standaloneMode) -> StandaloneMemberLookup
else if (cluster.conf 存在 或 memberList 属性) -> FileConfigMemberLookup
else -> AddressServerMemberLookup
```

### 5.2 三种模式

| 模式 | 类 | 发现机制 | 适用场景 |
|------|---|---------|---------|
| **standalone** | StandaloneMemberLookup | 单成员(自己) | 开发测试(本地单机) |
| **file** | FileConfigMemberLookup | 读取 cluster.conf + WatchFileCenter 监听变更 | 传统运维(固定 IP) |
| **address-server** | AddressServerMemberLookup | HTTP GET 地址服务器, 每 5s 轮询 | 云原生(K8s 动态扩缩容) |

### 5.3 FileConfigMemberLookup

```java
// 读取 ${nacos.home}/conf/cluster.conf
// WatchFileCenter 监听文件变更 -> 自动重新加载
private void readClusterConfFromDisk() {
    List<String> tmp = EnvUtil.readClusterConf();
    Collection<Member> tmpMembers = MemberUtil.readServerConf(tmp);
    afterLookup(tmpMembers);  // -> memberManager.memberChange(members)
}
```

### 5.4 AddressServerMemberLookup

```java
// HTTP GET http://<domain>:<port>/<contextPath>/serverlist
// 初始重试 5 次, 之后每 5s 轮询
// 连续 12 次失败(maxHealthCheckFailCount) -> 标记地址服务器不健康
private void syncFromAddressUrl() {
    RestResult<String> result = restTemplate.get(addressServerUrl, ...);
    if (result.ok()) {
        afterLookup(MemberUtil.readServerConf(result.getData()));
    }
}
```

---

## 六、MembersChangeEvent 传播

```
节点加入/离开:
  Lookup.afterLookup(members) -> memberManager.memberChange(members)
    -> 检测成员变化 -> 持久化到 cluster.conf
    -> NotifyCenter.publishEvent(MembersChangeEvent)

节点状态变化:
  MemberInfoReportTask 探测 -> onSuccess/onFail
    -> 状态变化 -> notifyMemberChange(member)
    -> NotifyCenter.publishEvent(MembersChangeEvent)

MembersChangeEvent 消费者:
  1. DistroMapper.onEvent -> 重建 healthyList -> 重新计算所有 tag 的负责节点
  2. ProtocolManager -> 重建 Raft 组
  3. RaftPeerSet -> 更新 Raft 成员
```

**DistroMapper 重建**:
- `healthyList` 重新排序 -> `hashCode(tag) % newServerCount` 重新计算
- 简单取模: 节点数变化 -> 几乎所有 tag 的负责节点改变
- 但 Distro 的 verify 反熵(5s)能快速修复迁移期间的数据不一致

---

## 七、设计哲学分析

### 7.1 Nacos 为什么是"双层"系统?

Nacos = Naming(服务注册发现) + Config(配置管理), 使用完全不同的基础设施:
- Naming: 内存存储 + gRPC + Distro 同步
- Config: DB 存储 + gRPC/HTTP 双入口 + AsyncNotify

共享的: 集群管理(ServerMemberManager)、事件总线(NotifyCenter)、安全(鉴权)

### 7.2 为什么 Naming 不用 DB 而 Config 用 DB?

- Naming 实例数据是"流式"的 -> 高频变更 -> 内存比 DB 快 1000x
- Config 配置数据是"静态"的 -> 低频变更 -> DB 提供持久化和共享
- Naming 存 DB -> 每次注册/注销 SQL 写 -> 性能灾难
- Config 存内存 -> 重启丢失 -> 不可接受

### 7.3 MemberLookup 三种模式 - 从开发到生产到云原生

- standalone: 开发者本地测试 -> 零配置
- file(cluster.conf): 传统运维 -> 手动维护 IP 列表
- address-server: 云原生 -> K8s 动态扩缩容, Pod IP 变化 -> 地址服务器自动更新

---

---

## 八、版本演进与设计决策历史

> 来源: Nacos 源码 git 历史(98 个 tag, v0.1.0~v3.2.2) + CHANGELOG.md

### 8.1 版本里程碑

| 时间 | 版本 | 里程碑 |
|------|------|--------|
| 2018.07 | v0.1.0 | Nacos 诞生:配置管理 + 服务发现 + 保护阈值 + 四种健康检查 |
| 2019.03 | v1.0.0-RC1 | **ephemeral vs persistent 实例**(#677) + **CP/AP/mixed 运行模式**(#745) |
| 2019.03 | v1.0.0-RC4 | **默认 AP 模式**(#946) |
| 2021.03 | v2.0.0 | **gRPC 长连接 + Client 中心模型(v2 架构)**。注意: ConfigRpcTransportClient 从 2.0.0 第一天就存在--**Config 从 2.x 一开始就用 gRPC**,不是后来才改的 |
| 2022.05 | v2.1.1 | batchRegisterInstance 引入 |
| 2022.12 | v2.2.0 | batchDeregisterInstance 引入 |
| 2023.11 | v2.3.0 | 当前代码版本(本文档基于此版本) |

### 8.2 关键设计决策的历史脉络

**为什么 `healthy` 和 `enabled` 是分开的?**

1.0.0-RC1 的 [#809] 将 `valid` 字段废弃,拆分为:
- `healthy`: 自动检测(健康检查),服务端可改变
- `enabled`: 手动控制(运维操作),只由用户改变

这就是 D-01 中"三维状态"(enabled × healthy × weight)设计的来源。在此之前只有一个 `valid` boolean 字段。

**为什么默认 AP 而不是 CP?**

1.0.0-RC1 的 [#745] 引入了 CP/AP/mixed 三种运行模式。但 1.0.0-RC4 的 [#946] 将**默认改为 AP**。原因:对于注册中心来说,"不可用"比"短暂不一致"更致命--如果 Nacos 因为 CP 共识等待导致注册失败,所有新启动的服务都无法被发现。

**Config 从什么时候开始用 gRPC?**

从 **2.0.0 第一天**。`ConfigRpcTransportClient` 在 2.0.0 就已经存在,替代了 1.x 的 `LongPollingRunnable`(HTTP 长轮询)。服务端保留 `LongPollingService` 仅为兼容 1.x 客户端。常见误解"2.x Config 还在用 HTTP 长轮询"是**错误的**。

**batchRegister 和 batchDeregister 什么时候加的?**

- `batchRegisterInstance`: v2.1.1 新增(在 NamingService.java 标注 `@since 2.1.1`)
- `batchDeregisterInstance`: v2.2.0 新增(标注 `@since 2.2.0`)

这是 API 渐进式演进的体现:先有单个注册,再有批量注册,最后有批量注销。

### 8.3 CHANGELOG 中的设计决策引用

以下关键设计决策在 CHANGELOG.md 中有明确记录,可作为面试时引用:

| 设计点 | Issue | 面试引用 |
|--------|-------|---------|
| healthy vs enabled 分离 | [#809] v1.0.0-RC1 | "1.0.0 之前只有一个 `valid` 字段,后来拆分为 `healthy`(自动)和 `enabled`(手动),让运维下线和服务故障能独立表达" |
| CP/AP 双模式 | [#745] v1.0.0-RC1 | "1.0.0 就支持了 CP/AP/mixed 三种运行模式,比 Eureka(纯 AP)和 ZK(纯 CP)更灵活" |
| 默认 AP | [#946] v1.0.0-RC4 | "社区讨论后决定默认 AP--注册中心的可用性比一致性更重要" |
| 临时/持久实例 | [#677] v1.0.0-RC1 | "ephemeral 标志从 1.0.0 就有了,不是 2.x 才引入的--1.x 是 HTTP 心跳+临时,2.x 是 gRPC 连接+临时" |
| Subscribe 可选 | [#497] v0.8.0 | "getAllInstances 的 subscribe 参数从 0.8.0 就支持了--客户端可以选择只查询不订阅" |


## 九、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|------|------|------|
| 1 | Naming 用 DB 存储(像 Config) | 每次注册/注销 -> SQL 写 -> 性能灾难 | Naming 内存, Config DB |
| 2 | 只有 file 模式(无 address-server) | K8s 动态扩缩容 -> Pod IP 变化 -> 手动更新 cluster.conf | 支持 address-server 动态发现 |
| 3 | MembersChangeEvent 不触发 DistroMapper 重建 | 新节点加入 -> 数据不迁移 -> 新节点无法提供完整查询 | 哈希环重建 + DistroLoadDataTask |
| 4 | Naming 和 Config 用同一套同步协议 | Naming 高频变更冲击 Config 通知通道; Config DB 存储浪费在 Naming | Distro vs AsyncNotify |
| 5 | ServerMemberManager 不做心跳检测 | 节点 crash 无感知 -> 哈希环指向不存在的节点 -> 路由黑洞 | 2s 心跳 + 3 次失败 -> DOWN |

---

## 十、面试 Q&A

### Q1: 从客户端注册到另一个客户端感知, 全部经历了哪些步骤?

SDK: GrpcClientProxy -> InstanceRequest -> gRPC。Handler: InstanceRequestHandler -> EphemeralClientOperationServiceImpl -> Client.addServiceInstance。Event: ClientChangedEvent -> DistroClientDataProcessor(Distro sync) + ClientServiceIndexesManager -> publisherIndexes -> ServiceChangedEvent。Push: NamingSubscriberServiceV2Impl -> PushDelayTask(500ms) -> RpcPushService -> gRPC。Client: NamingPushRequestHandler -> ServiceInfoHolder -> listener。Cluster: DistroClientDataProcessor -> DistroProtocol.sync -> allMembersWithoutSelf。

### Q2: 哪些是同步的, 哪些是异步的?

同步: gRPC 请求/响应(注册确认)、DB 写入(Config)。异步: 事件通知、Push 推送、Distro sync、集群通知。原因: "写"需要确认(至少本地确认), "通知"可以异步(最终一致)。

### Q3: 如果让你从零设计一个注册中心, 你会怎么取舍?

存储: 内存(像 Naming) vs DB(像 ZK)。健康检查: 连接驱动(2.x) vs 心跳轮询(1.x)。一致性: AP(大多数) vs CP(基础设施)。推送: 事件驱动 + 延迟合并。关键原则: "简单够用" > "完美复杂"。

### Q4: MemberLookup 三种模式分别适用于什么场景?

standalone: 开发测试。file(cluster.conf): 传统运维(固定 IP)。address-server: 云原生(K8s 动态扩缩容)。

---

## 十一、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| ServerMemberManager | 集群成员管理, 心跳检测, 发布 MembersChangeEvent | `core/cluster/ServerMemberManager.java` |
| MemberLookup | 成员发现接口(standalone/file/address-server) | `core/cluster/MemberLookup.java` |
| LookupFactory | 按配置选择发现模式 | `core/cluster/lookup/LookupFactory.java` |
| MemberInfoReportTask | 每 2s 心跳, 轮询一个节点, gRPC/HTTP | `ServerMemberManager` 内部类 |
| MembersChangeEvent | 成员变更事件(DistroMapper/Raft 监听) | `core/cluster/MembersChangeEvent.java` |
| Member.state | UP/SUSPICIOUS/DOWN, >3 次失败(第4次)或 Connection refused -> DOWN | `core/cluster/Member.java` |
| DistroMapper | 订阅 MembersChangeEvent, 重建 healthyList | `naming/core/DistroMapper.java` |
