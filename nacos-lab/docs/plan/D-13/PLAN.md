# D-13 详细规划: Nacos 注册中心设计全景 + 集群管理

> 阶段: Phase 7 — 设计全景 | 预期产出: `md/D-13-SystemDesign.md` | 状态: 待审核

---

## 一、为什么需要这篇

前 12 篇分析了 Nacos 的各个组件和子系统。这篇做两件事：
1. **全景串联**: 把 API→SDK→gRPC→注册处理→事件索引→Push→健康检查→Distro→CP/AP→Config 串成一条完整链路
2. **集群管理**: 补充 ServerMemberManager + MemberLookup — 分布式系统的"底座"

前置依赖: D-01~D-12 (全部)

---

## 二、源码阅读清单 (仅新增部分)

| 序号 | 源码文件 | 必须阅读的内容 | 阅读目的 |
|------|----------|---------------|----------|
| 1 | `core/.../cluster/ServerMemberManager.java` | allMembers(), onEvent() | 理解集群成员管理 |
| 2 | `core/.../cluster/MemberLookup.java` | doStart() | 理解成员发现接口 |
| 3 | `core/.../cluster/lookup/AddressServerMemberLookup.java` | run() | 理解地址服务器发现模式 |
| 4 | `core/.../cluster/lookup/FileConfigMemberLookup.java` | readClusterConf() | 理解 cluster.conf 文件模式 |
| 5 | `core/.../cluster/lookup/StandaloneMemberLookup.java` | doStart() | 理解单机模式 |

---

## 三、分析要点详解

### 3.1 端到端完整调用链回顾 (串联 D-01~D-12)

```
========== 客户端 SDK 层 (D-03/D-04) ==========
应用代码: namingService.registerInstance("user-service", "10.0.0.1", 8080)
  → NacosNamingService → NamingClientProxyDelegate → GrpcClientProxy
  → InstanceRequest(REGISTER_INSTANCE) → gRPC → :9848 端口

========== 服务端 gRPC 连接层 (D-05) ==========
GrpcSdkServer(9848) → GrpcRequestAcceptor → RequestHandlerRegistry

========== 注册处理链 (D-06/D-07) ==========
InstanceRequestHandler.handle()
  → EphemeralClientOperationServiceImpl.registerInstance()
    → Client.addServiceInstance() → 内存存储
    → client.recalculateRevision()
    → NotifyCenter.publishEvent(ClientRegisterServiceEvent)

========== 事件驱动索引 (D-07) ==========
ClientServiceIndexesManager.onEvent()
  → addPublisherIndexes → publisherIndexes 更新
  → NotifyCenter.publishEvent(ServiceChangedEvent)

========== Push 推送 (D-08) ==========
NamingSubscriberServiceV2Impl.onEvent()
  → PushDelayTask(500ms 合并窗口) → PushExecutorDelegate
  → RpcPushService.push(connectionId, ServiceInfo) → gRPC Bi-di Stream
    → 客户端 NamingPushRequestHandler → ServiceInfoHolder → listener.onEvent()

========== 数据一致性 (D-09/D-10) ==========
DistroClientDataProcessor.onEvent()  // AP 同步
  → isResponsibleClient 检查 → syncToAllServer
  → DistroProtocol.sync(distroKey, DataOperation.CHANGE)
  → allMembersWithoutSelf() → gRPC → DistroDelayTask → syncToTarget

CP 持久实例: ClientOperationServiceProxy.chooseClientOperationService
  → PersistentClientOperationServiceImpl → protocol.write() → JRaft

========== 健康检查 + 故障自愈 (D-08+D-05) ==========
连接断开 → ConnectionManager.unregister → ClientReleaseEvent
  → ClientServiceIndexesManager.handleClientDisconnect → 清理双索引
  → DistroClientDataProcessor → 集群同步删除

========== 集群配置同步 (D-11/D-12) ==========
ConfigPublishRequestHandler → MD5 幂等 → Derby/MySQL
  → ConfigDataChangeEvent
  → LongPollingService 唤醒本地
  → AsyncNotifyService 通知集群

========== 集群管理 (D-13) ==========
ServerMemberManager 维护成员列表
  → MemberLookup 发现新节点
  → MembersChangeEvent → DistroMapper 重建哈希环
```

### 3.2 ServerMemberManager — 集群成员管理

```
ServerMemberManager (implements ApplicationListener<WebServerInitializedEvent>):
  核心职责:
    - 维护集群成员列表: allMembers()
    - 管理节点状态: UP/DOWN/SUSPICIOUS
    - 发布成员变更事件: MembersChangeEvent
    
  启动流程 (Spring Boot):
    WebServerInitializedEvent 触发 → serverList 初始化
    → MemberLookup.doStart() → 从地址服务器/文件获取初始成员列表
    → 定时任务定期更新成员状态
    
  心跳检测:
    → 定期向其他节点发送心跳(通过 gRPC ClusterServer, 9849端口)
    → 超时未响应 → 标记 SUSPICIOUS → 再次超时 → DOWN
    → DOWN 事件 → MembersChangeEvent → DistroMapper 重建哈希环
```

### 3.3 MemberLookup — 三种节点发现模式

```
[模式1: address-server — AddressServerMemberLookup]
  → 适合动态扩缩容的云原生环境
  → 所有节点向同一个地址服务器注册
  → 从地址服务器获取最新的集群成员列表
  → 配置: nacos.core.member.lookup.type=address-server

[模式2: file — FileConfigMemberLookup]
  → 适合静态集群
  → 读取 ${nacos.home}/conf/cluster.conf 文件
  → 文件内容: ip:port 列表
  → 配置: nacos.core.member.lookup.type=file

[模式3: standalone — StandaloneMemberLookup]
  → 单机模式
  → 只返回自己
  → nacos.standalone=true 或 member list 为空时激活

LookupFactory 根据配置选择:
  → nacos.core.member.lookup.type → ADDRESS_SERVER(2) / FILE(1) / STANDALONE(0)
```

### 3.4 节点变更对系统的影响 — MembersChangeEvent 传播

```
MembersChangeEvent:
  新节点加入:
    → DistroMapper 重建哈希环 → Service 重新分配
    → 新节点启动 DistroLoadDataTask → 从其他节点拉取负责的 Client 数据
    → DistroProtocol.isInitialized=true → 注册到集群 → 对外服务
    
  节点离开(优雅下线):
    → 触发数据迁移 → 负责的 Service 分配给下一个节点
    → 其他节点收到 MembersChangeEvent → 重建哈希环
    
  节点 crash:
    → 心跳超时 → 标记 DOWN → MembersChangeEvent
    → 哈希环重建 → 数据由下一个节点接管
    → 但! crash 节点的数据已丢失(临时实例)
    → 客户端重连到其他节点 → 重新注册 → 数据恢复
```

### 3.5 关键维度全景对比

| 维度 | Naming | Config | 关键差异 |
|------|--------|--------|---------|
| 入口 | GrpcSdkServer(9848) | HTTP(8848) / gRPC | Naming 全 gRPC, Config 双入口 |
| 存储 | Client 内存 | Derby/MySQL + DumpService 缓存 | 数据源不同决定了同步模式 |
| 同步 | Distro V2 (per-Client, allMembers) | AsyncNotify (通知+拉取) | Push 数据 vs 通知 |
| 一致性 | AP(临时)/CP(持久) | AP(异步通知) | Config 有 DB, 不需要强一致 |
| 健康检查 | 连接驱动(毫秒)+定时补偿 | 不适用 | Config 没有实例概念 |
| Push | ServiceChangedEvent → PushDelayTask(500ms) | LongPollingService(29.5s hold) | 推送 vs 长轮询 |

---

## 四、设计哲学分析

### 4.1 Nacos 为什么是一个"双层"系统？

Nacos = Naming(服务注册发现) + Config(配置管理), 但两个子系统使用完全不同的基础设施:
- Naming: 内存存储 + gRPC 模型 + Distro 同步
- Config: DB 存储 + HTTP gRPC 双入口 + AsyncNotify

共享的只有: 集群管理(ServerMemberManager)、事件总线(NotifyCenter)、安全(鉴权)

### 4.2 为什么 Naming 不用 DB 而 Config 用 DB？

- Naming 的实例数据是"流式"的 → 高频变更 → 内存比 DB 快 1000x
- Config 的配置数据是"静态"的 → 低频变更 → DB 提供持久化和共享
- 如果把 Naming 存 DB → 每次注册/注销都是 SQL 写 → 性能灾难
- 如果把 Config 存内存 → 重启丢失 → 不可接受

### 4.3 MemgerLookup 的三种模式 — 从开发到生产到云原生

- standalone: 开发者本地测试 → 零配置
- file (cluster.conf): 传统运维 → 手动维护 IP 列表
- address-server: 云原生 → 动态扩缩容

---

## 五、反面假设分析 (5 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | Naming 用 DB 存储(像 Config) | 每次注册/注销 → SQL 写 → 性能灾难。10K 实例同时心跳 → DB 拒绝。 | Naming 内存存储, Config DB 存储 |
| 2 | 只有 file 模式(无 address-server) | K8s 动态扩缩容 → Pod IP 变化 → 手动更新 cluster.conf → 运维噩梦 | 支持 address-server 动态发现 |
| 3 | MembersChangeEvent 不触发 DistroMapper 重建 | 新节点加入 → 数据不迁移 → 新节点无法提供完整查询 | 哈希环重建 + DistroLoadDataTask |
| 4 | Naming 和 Config 用同一套同步协议 | Naming 高频变更会冲击 Config 的通知通道。Config 的 DB 存储浪费在 Naming。 | 不同的同步模式: Distro vs AsyncNotify |
| 5 | ServerMemberManager 不做心跳检测 | 节点 crash 无感知 → 哈希环数据指向不存在的节点 → 路由黑洞 | 定期心跳 + DOWN 标记 + MembersChangeEvent |

---

## 六、面试 Q&A (4 题)

### Q1: 从客户端注册到另一个客户端感知到新实例，全部经历了哪些步骤？

**面试官意图**: 考察端到端的全局理解

**参考答案要点**:
- SDK: GrpcClientProxy → InstanceRequest → gRPC
- Handler: InstanceRequestHandler → EphemeralClientOperationServiceImpl → Client.addServiceInstance
- Event: ClientRegisterServiceEvent → ClientServiceIndexesManager → publisherIndexes 更新
- Push: ServiceChangedEvent → NamingSubscriberServiceV2Impl → PushDelayTask(500ms) → RpcPushService
- Client: NamingPushRequestHandler → ServiceInfoHolder → listener.onEvent()
- Cluster: DistroClientDataProcessor → syncToAllServer → allMembersWithoutSelf → gRPC

### Q2: Nacos 的架构中，哪些是同步操作的，哪些是异步的？为什么？

**面试官意图**: 考察对同步/异步设计哲学的理解

**参考答案要点**:
- 同步: gRPC 请求/响应(注册确认)、DB 写入(Config)
- 异步: 事件通知、Push推送、Distro sync、集群通知
- 原因: "写"需要确认(至少本地确认), "通知"可以异步(最终一致)
- 异步 = 性能, 同步 = 可靠性

### Q3: 如果让你从零设计一个注册中心，你会怎么取舍？

**面试官意图**: 考察架构权衡能力

**参考答案要点**:
- 存储: 内存(像 Nacos Naming) vs DB(像 ZooKeeper)
- 健康检查: 连接驱动(像 Nacos 2.x) vs 心跳轮询(像 Nacos 1.x)
- 一致性: AP(大多数场景) vs CP(基础设施)
- 推送: 事件驱动(像 Nacos) vs 轮询
- 关键原则: "简单够用" > "完美复杂"

### Q4: MemberLookup 的三种模式分别适用于什么场景？

**面试官意图**: 考察对集群部署演进的理解

**参考答案要点**:
- standalone: 开发测试(本地单机)
- file(cluster.conf): 传统运维(固定 IP)
- address-server: 云原生(动态扩缩容 K8s)

---

## 七、产出文档结构

```
D-13-SystemDesign.md

1. 端到端完整调用链(注册→Push→集群同步)
2. 全景对比: Naming vs Config 7 维度
3. 集群管理
   3.1 ServerMemberManager 成员管理
   3.2 MemberLookup 三种发现模式
   3.3 MembersChangeEvent 传播
4. 设计哲学: 双层系统、同步vs异步、存储选型
5. 性能关键路径分析
6. 反面假设(5 组)
7. 面试 Q&A(4 题)
```

---

## 八、与后续文档的依赖关系

```
D-13 (设计全景 + 集群管理)  ← 当前
  ↓
D-14 (面试模拟 18 题)
```
