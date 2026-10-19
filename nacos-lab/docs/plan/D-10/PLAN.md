# D-10 详细规划: CP/AP 双模式路由 + JRaft 整合

> 阶段: Phase 5 — 数据一致性 | 预期产出: `md/D-10-CP-AP-Routing.md` | 状态: 待审核

---

## 一、为什么需要这篇

D-09 覆盖了 AP 模式的 Distro 协议。但 Nacos 同时支持 CP 模式(基于 SOFAJRaft)。这篇解释 Nacos 如何在同一个系统中同时提供 CP 和 AP 两种一致性保证——这是 Nacos 相比 Eureka(纯 AP) 和 ZooKeeper(纯 CP) 的**核心差异化能力**。

前置依赖: D-06 (Client 模型, ephemeral 标志), D-09 (Distro V2, AP 实现)

> **与 D-06/D-09 的呼应**: D-06 的 Instance.ephemeral 是整个架构的分叉点; D-09 是 AP 路径; D-10 解释分叉决策 + CP 路径。

---

## 二、源码阅读清单

| 序号 | 源码文件 | 必须阅读的内容 | 阅读目的 |
|------|----------|---------------|----------|
| 1 | `naming/.../core/v2/service/ClientOperationServiceProxy.java` | chooseClientOperationService — ephemeral→AP, !ephemeral→CP | 理解 CP/AP 路由决策点 |
| 2 | `naming/.../core/v2/service/impl/EphemeralClientOperationServiceImpl.java` | registerInstance — Distro AP 路径 | 复习 AP 实现 |
| 3 | `naming/.../core/v2/service/impl/PersistentClientOperationServiceImpl.java` | extends RequestProcessor4CP — JRaft CP 路径 | 理解 CP 实现 |
| 4 | `consistency/.../cp/RequestProcessor4CP.java` | apply() — Raft 状态机接口 | 理解 Raft 写入模型 |
| 5 | `consistency/.../cp/CPProtocol.java` | CP 协议抽象接口 | 理解 CP 协议层 |
| 6 | `naming/.../core/v2/pojo/Service.java` | ephemeral 字段 + newService | 复习 ephemeral 标志 |
| 7 | `naming/.../core/InstanceOperator.java` | registerInstance — 对外接口(路由前) | 理解 InstanceOperator 层的统一入口 |

---

## 三、分析要点详解

### 3.1 CP/AP 路由决策点 — ClientOperationServiceProxy

```
InstanceOperatorImpl.registerInstance(service, instance, clientId)
  → Service.newService(namespace, group, name, instance.isEphemeral())
  → clientOperationServiceProxy.registerInstance(service, instance, clientId)
      // ======== 核心路由! ========
      → chooseClientOperationService(instance)
        → if (instance.isEphemeral())
            → return ephemeralClientOperationService    // EphemeralClientOperationServiceImpl (AP/Distro)
          else
            → return persistentClientOperationService    // PersistentClientOperationServiceImpl (CP/JRaft)
      → operationService.registerInstance(service, instance, clientId)

// ClientOperationServiceProxy.java:89 (实际源码):
private ClientOperationService chooseClientOperationService(Instance instance) {
    return instance.isEphemeral() ? ephemeralClientOperationService : persistentClientOperationService;
}
```

**路由结果**:
- `ephemeral=true`(默认) → `EphemeralClientOperationServiceImpl` → Distro AP 协议(D-09)
- `ephemeral=false` → `PersistentClientOperationServiceImpl` → JRaft CP 协议

### 3.2 AP 路径(复习 D-09)

```
EphemeralClientOperationServiceImpl.registerInstance():
  → Client.addServiceInstance()  // 内存存储
  → client.recalculateRevision()
  → NotifyCenter.publishEvent(ClientRegisterServiceEvent) // 事件→索引→推送
  → DistroClientDataProcessor.syncToAllServer()           // AP 同步(allMembers)
```

### 3.3 CP 路径 — PersistentClientOperationServiceImpl + JRaft

```
PersistentClientOperationServiceImpl extends RequestProcessor4CP  (line 83)
  → registerInstance():  (line 104-125)
    → 校验: Service 必须是持久实例(singleton.isEphemeral() → throw)
    → 构建 InstanceStoreRequest(service, instance, clientId)  // 内部请求对象
    → 序列化 InstanceStoreRequest → ByteString
    → 构建 WriteRequest(DataOperation.ADD, Group, ByteString)   // gRPC 写入请求
    
    → protocol.write(writeRequest)  // ⚠️ 通过注入的 CPProtocol, 不是 this.submit()
    // protocol = CPProtocol(接口), 实际是 JRaftServer(JRaft 实现)
    
    [JRaft 处理]:
    → JRaftServer.write(writeRequest) → Leader 收到 → Raft log append
    → 复制到多数派 Follower → 确认 → commit
    
    [onApply() — 状态机应用]:  (RequestProcessor.java:47)
    → PersistentClientOperationServiceImpl.onApply(writeRequest)
      → 反序列化 InstanceStoreRequest
      → clientConnected + addServiceInstance  // 内存存储(与临时实例共用 Client 模型)
      → 持久化存储(Derby/MySQL)               // 磁盘持久化
      → NotifyCenter.publishEvent(...)         // 事件→索引→推送
```

**CP vs AP 的写入路径对比**:

| 维度 | AP (Ephemeral/Distro) | CP (Persistent/JRaft) |
|------|----------------------|----------------------|
| 入口服务 | EphemeralClientOperationServiceImpl | PersistentClientOperationServiceImpl |
| 一致性协议 | Distro V2 | JRaft (SOFAJRaft) |
| 写入路径 | Client内存 → 事件 → sync(allMembers) | Raft log → 多数派确认 → apply |
| 数据持久化 | 仅内存(重启丢失) | 内存 + Derby/MySQL(重启恢复) |
| 写入延迟 | 微秒级(纯内存) | 毫秒级(Raft 共识) |
| 可用性 | 单节点可写(AP) | 需要多数派存活(CP) |
| 连接断开 | 实例自动删除 | 仅标记不健康,不删除 |

### 3.4 RequestProcessor4CP + onApply — Raft 状态机抽象

```java
// RequestProcessor4CP extends RequestProcessor (RequestProcessor.java:47)
// 实际方法:
public abstract Response onApply(WriteRequest log);

// PersistentClientOperationServiceImpl 实现 (line 190):
//   onApply(writeRequest) → 反序列化 InstanceStoreRequest → 内存存储 → 持久化 → 返回 success
```

**Raft 写路径**:
- `CPProtocol.write(writeRequest)` → JRaftServer 处理 → Raft log → commit
- 写入后由 JRaft 框架自身在 commit 后回调 `onApply()` → 状态机应用
- `protocol` 是注入的 `CPProtocol` 实例(实际为 JRaftServer)

### 3.5 CAP 理论在 Nacos 中的实际权衡

```
Nacos 的 CAP 策略: "按实例类型分叉"

临时实例(ephemeral=true):
  → AP 模式
  → 允许: 网络分区期间,分区两边的节点都接受注册
  → 不允许: 因为一致性等待导致注册操作失败
  → 适用场景: 微服务实例注册(95%+场景)

持久实例(ephemeral=false):
  → CP 模式
  → 允许: 牺牲可用性换取数据一致性
  → 不允许: 网络分区时,脑裂导致数据不一致
  → 适用场景: 基础设施注册(DB节点/DNS条目等)

为什么不在所有场景都选 CP？
  → CP 需要多数派存活才能写,在 3 节点集群中挂 1 个=可用→1 个节点=不可用
  → 微服务实例注册频率高(秒级),不可用 1 分钟=数百次注册失败
  → 但临时实例的重启=重新注册,短暂丢失可接受 → AP 是合理选择
```

### 3.6 持久实例的数据持久化

```
[写入]:
PersistentClientOperationServiceImpl.registerInstance()
  → Raft log → commit → apply()
    → Client 内存存储(与临时实例共用 Client 模型! D-06)
    → 持久化存储: Derby(默认)或 MySQL(生产)
      → INSERT INTO instance_info(namespace, group_name, service_name, ip, port, ...)

[读取]:
ServiceStorage.getData(service)
  → 从 Client 内存索引读取(与临时实例一样! D-07)
  → ClientServiceIndexesManager.publisherIndexes

[恢复]:
Nacos 重启 → 从 Derby/MySQL 加载持久实例数据 → 重建 Client 内存索引
```

---

## 四、设计哲学分析

### 4.1 同一系统同时支持 CP 和 AP — CAP 的场景化解耦

- 不是"全系统选 AP"或"全系统选 CP" → 而是"每个实例可以独立选"
- 决策依据: 实例的 ephemeral 标志
- 这比 Eureka(强制 AP)和 ZooKeeper(强制 CP)更灵活
- 本质上是对 CAP 理论的一个工程化解读: **不同数据有不同的一致性需求**

### 4.2 ClientOperationServiceProxy — 代理模式的路由

- `chooseClientOperationService(instance)` → 一行三元运算符
- 对外透明: 调用者(InstanceOperator)不需要知道走 AP 还是 CP
- AP 和 CP 实现完全解耦 → 未来可以换协议(如 CP 从 JRaft 换成其他 Raft 实现)
- 这是典型的 Strategy 模式 + 代理模式

### 4.3 持久实例的"双写" — 内存 + 磁盘

- 内存(Client 模型): 与临时实例共享,保证查询性能
- 磁盘(Derby/MySQL): CP 的持久性要求
- Raft 日志: 保证多节点数据一致
- 三层保障: Raft(一致性) + 内存(性能) + DB(持久性)

---

## 五、反面假设分析 (6 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | Nacos 全系统只选 AP(像 Eureka) | 无法支持持久实例场景。DB 节点手工注册的数据在重启后丢失。 | 按实例分叉: ephemeral→AP, !ephemeral→CP |
| 2 | Nacos 全系统只选 CP(像 ZooKeeper) | 微服务高频注册 = 每次都要等 Raft 多数派确认 → 注册 RT 数十毫秒。节点不可用=注册全部失败。 | 临时实例走 AP, 只持久实例走 CP |
| 3 | CP 和 AP 路径没有代理层(ClientOperationServiceProxy) | InstanceOperator 需要写 if-else 判断 → 耦合。加新协议需要改所有调用方。 | 代理模式统一入口, 实现解耦 |
| 4 | 持久实例不持久化到 DB, 只依赖 Raft 日志 | Raft 日志 compaction 后旧日志丢失 → 数据不完整。Raft snapshot 频率低 → 恢复时可能丢数据。 | Raft + DB 双写: Raft 保证一致性, DB 保证持久性 |
| 5 | 持久实例的查询也走 Raft read-index | 每次查询都要 Raft 共识 → 查询 RT 数十毫秒 → 不可接受 | 持久实例和临时实例共用内存索引, 查询不走 Raft |
| 6 | ephemeral 标志可以在实例注册后修改 | 同一个 Service 的两个实例可以是不同 ephemral → 数据混合 → 协议混乱 | ephemeral 由 Service 首次注册决定, 不可变更(D-06) |

---

## 六、新手最可能犯的 5 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 不理解 ephemeral 标志的意义,随便选 | 持久实例选成 ephemeral=true → 重启丢失 → 数据丢失 | 微服务实例=ephemeral(默认), 基础设施=ephemeral=false | Instance.ephemeral 默认 true, 不设就走 AP |
| 2 | 在 CP 模式下, Nacos 集群只有 1 个节点存活时尝试注册 | 1 节点无法满足多数派 → Raft 写拒绝 → 注册失败 | CP 模式至少需要 2 个节点存活(3 节点集群) | Raft 内置多数派检查 |
| 3 | 认为临时和持久实例可以混合在同一个 Service 下 | 注册时 Service 的 ephemeral 已由第一个实例确定 → 第二个不同类型被拒绝 | 提前规划 Service 的 ephemeral 属性 | EphemeralClientOperationServiceImpl 校验 ephemeral 一致性 |
| 4 | 持久实例注册后不主动注销 | Zombie 数据永久保留 → 路由到不存在的节点 | 持久实例需要主动 deregister | 持久实例不会自动清理(by design) |
| 5 | 认为 CP 模式下数据也通过 Distro sync | CP 走 Raft log 复制,不是 Distro。syncToAllServer 只处理临时实例。 | 理解 isInvalidClient→isEphemeral 控制 sync 范围 | DistroClientDataProcessor.syncToAllServer 内部检查 ephemeral |

### 6.1 可运行的错误实验

**实验: CP 模式下, 少数派节点存活时写失败**

```
集群: 3 节点 Nacos, 2 个节点挂了, 只剩 1 个
  → Raft 需要多数派(2/3)才能写
  → PersistentClientOperationServiceImpl.registerInstance()
  → Raft submit() → 等待多数派确认 → 超时! → 注册失败!
  
对比: 临时实例(AP):
  → 单节点直接写内存 → 立即成功(牺牲分布式一致性)
```

---

## 七、面试 Q&A (5 题)

### Q1: CAP 理论中,注册中心应该选 CP 还是 AP？Nacos 是怎么做的？

**面试官意图**: 考察对 CAP 理论在注册中心场景下的深度理解

**参考答案要点**:
- Nacos 两者都支持: ephemeral→AP(Distro), !ephemeral→CP(JRaft)
- 注册中心大多数场景选 AP: 允许短暂不一致, 不允许不可用
- 但基础设施注册(如 DB 节点)需要 CP: 数据不能丢
- 分叉关键: `ClientOperationServiceProxy.chooseClientOperationService`
- 对比: Eureka 只能 AP, ZK 只能 CP, Nacos 两者兼顾

### Q2: JRaft 在 Nacos 中的角色是什么？

**面试官意图**: 考察对 Raft 协议在 Nacos 中实际应用的理解

**参考答案要点**:
- Nacos 集成 SOFAJRaft 作为 CP 模式的共识协议
- 持久实例的注册/注销通过 `CPProtocol.write()` → JRaft → commit → `onApply()` 写入状态机
- Raft 负责: Leader 选举 + 日志复制 + 回调 onApply
- 不用于: 临时实例(走 Distro), 查询(走内存索引)

### Q3: 为什么不在 CP 模式下用 Distro, 或在 AP 模式下用 Raft？

**面试官意图**: 考察对不同一致性协议的适用场景的理解

**参考答案要点**:
- Distro 不持久化 → 不符合 CP 的持久性要求
- Raft 有写延迟(多数派确认) → 不符合 AP 的高频写+低延迟需求
- 两种协议各有优势, Nacos 通过 ephemeral 标志做到"按需选择"
- 这是工程上的"最好的工具做最好的事"

### Q4: 持久实例的数据在 Nacos 重启后会丢吗？

**面试官意图**: 考察对 CP 持久性的理解

**参考答案要点**:
- 不会。持久实例写入 Raft 日志 + 持久化到 Derby/MySQL
- Nacos 重启: 从 DB 加载 → 重建 Client 内存索引 → 通过 Raft snapshot 校验
- 但要注意: 如果 Derby(嵌入式)数据文件损坏, 数据可能丢失
- 生产环境建议用 MySQL 作为持久化存储

### Q5: 如果集群中 CP 节点挂了多数, 会发生什么？AP 节点呢？（联动 D-09）

**面试官意图**: 考察对 CP/AP 故障模式差异的理解

**参考答案要点**:
- CP(3 节点挂 2 个): 剩余 1 个无法满足多数派 → Raft 写拒绝 → 持久实例注册失败。但查询正常(走内存索引)。临时实例注册正常(AP)。
- AP(3 节点挂 2 个): 剩余 1 个继续接收临时实例注册 → Distro sync 无目标节点 → 数据只在本地。节点恢复后通过 verify 反熵同步。
- 关键: 同一个 Nacos 集群中, AP 和 CP 的故障表现不同 → 运维需要区分理解

---

## 八、产出文档结构

```
D-10-CP-AP-Routing.md

1. 概述: CP/AP 双模式在 Nacos 中的架构
2. ClientOperationServiceProxy — 路由决策点
   2.1 chooseClientOperationService: ephemeral → AP or CP
   2.2 代理模式的优势
3. AP 路径复习(链接 D-09)
4. CP 路径 — PersistentClientOperationServiceImpl
   4.1 extends RequestProcessor4CP
   4.2 Raft submit → log → commit → apply
5. JRaft 整合
   5.1 SOFAJRaft 的角色
   5.2 Raft Group 的构建(每个 Service 一个 Group)
   5.3 Leader 选举 + 日志复制
6. 持久实例的数据持久化
   6.1 内存(Client 模型) + 磁盘(Derby/MySQL) 双写
   6.2 重启恢复流程
7. CAP 理论的实际权衡
   7.1 为什么不在所有场景都选 CP
   7.2 为什么不在所有场景都选 AP
8. 设计哲学分析
   8.1 CAP 场景化解耦
   8.2 Strategy+Proxy 模式
   8.3 双写(内存+磁盘)保障
9. 反面假设分析(6 组)
10. 新手最可能犯的 5 个错误 + 可运行错误实验
11. 面试 Q&A(5 题)
```

---

## 九、与后续文档的依赖关系

```
D-10 (CP/AP 双模式路由 + JRaft)  ← 当前
  |--- JRaft 集群通信 → D-13 集群管理
  |--- 持久化存储 → D-11 配置中心的存储
  ↓
D-11 (配置发布/查询/存储 + LongPolling)
```
