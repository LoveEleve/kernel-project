# D-01 详细规划: NamingService 接口设计 + Instance/ServiceInfo 数据模型

> 阶段: Phase 1 — API 契约层 | 预期产出: `md/D-01-NamingService-API.md` | 状态: 待审核

---

## 一、为什么需要这篇

注册中心的一切操作都通过 NamingService 接口暴露。这个 602 行的接口是理解 Nacos 整个服务注册/发现系统的入口契约。不理解接口设计就无法理解后续的 SDK 全链路、服务端注册处理、事件驱动索引等核心机制。

学习路径位置: API 契约 → Client SDK → gRPC 层 → 服务端核心 → 一致性 → 集群

---

## 二、源码阅读清单 (按阅读顺序)

| 序号 | 源码文件 | 行数 | 必须阅读的方法/类 | 阅读目的 |
|---|---|---|---|---|
| 1 | `api/src/main/java/com/alibaba/nacos/api/naming/NamingService.java` | 602 | 38 个方法声明的全部签名 | 掌握接口全貌，理解六大类操作 |
| 2 | `api/src/main/java/com/alibaba/nacos/api/naming/pojo/Instance.java` | 267 | 全字段 + equals(198-210) + toString(186-191) + getInstanceHeartBeatInterval(216-218) | 理解核心数据模型 |
| 3 | `api/src/main/java/com/alibaba/nacos/api/naming/pojo/ServiceInfo.java` | 278 | 全字段 + getKey(210-224) + expired(113-115) + validate(182-199) | 理解客户端缓存模型 |
| 4 | `api/src/main/java/com/alibaba/nacos/api/naming/listener/EventListener.java` | 32 | onEvent(31) | 理解事件监听接口 |
| 5 | `api/src/main/java/com/alibaba/nacos/api/naming/PreservedMetadataKeys.java` | 39 | 5 个 public static final 常量 | 理解 metadata 保留 key |
| 6 | `api/src/main/java/com/alibaba/nacos/api/common/Constants.java` | 267 | DEFAULT_GROUP(32), DEFAULT_CLUSTER_NAME(171), DEFAULT_HEART_BEAT_*(173-177), SERVICE_INFO_SPLITER(185) | 获取默认值和分隔符 |
| 7 | `api/src/main/java/com/alibaba/nacos/api/naming/pojo/ListView.java` | 53 | 全部 | 理解分页返回模型 |
| 8 | `api/src/main/java/com/alibaba/nacos/api/selector/AbstractSelector.java` | 50 | 全部(含@JsonTypeInfo注解) | 理解选择器抽象 + Jackson多态序列化 |

---

## 三、分析要点详解

### 3.1 NamingService 的六大类操作 (每个操作的重载演进分析)

**注册 (registerInstance)**
- 6 个重载的层层递进：serviceName+ip+port → +groupName → +clusterName → +Instance对象 → +batch
- 每个重载中缺省参数的默认值：groupName="DEFAULT_GROUP"(Constants.java:32), clusterName="DEFAULT"(Constants.java:171)
- batchRegisterInstance (v2.1.1 新增)的批量语义

**注销 (deregisterInstance)**
- 6 个重载，与 registerInstance 对称
- batchDeregisterInstance (v2.2.0 新增)

**全量查询 (getAllInstances)**
- 7 个重载：缺参数 → +groupName → +subscribe → +clusters → +clusters+subscribe → +groupName+clusters → +groupName+clusters+subscribe
- subscribe 参数的语义：true=同时订阅该服务的变更推送

**健康筛选 (selectInstances)**
- 7 个重载：在 getAllInstances 的参数维度上增加 healthy 参数
- healthy=true 的含义：只返回 healthy=true AND enabled=true 的实例

**负载均衡选择 (selectOneHealthyInstance)**
- 7 个重载：从 selectInstances 结果中按权重选一个
- 内置负载均衡算法默认是加权随机

**订阅 (subscribe/unsubscribe)**
- 4+4 个重载：支持按 serviceName → +groupName → +clusters → +groupName+clusters 维度订阅
- EventListener 的回调时机：服务实例列表发生变化时

### 3.2 Instance 数据模型深度 (揭示非显而易见的细节)

**核心字段逐个分析**
- instanceId：可为 null，服务端自动生成（格式：ip#port#clusterName#serviceName，Constants.java:165-168）
- ip/port：必须合法可解析
- weight：默认 1.0D，0=不分配流量（灰度下线中间态）
- healthy：默认 true，服务端健康检查可改变
- enabled：默认 true，manual operation 可改变，healthy=false 不可用时 enabled 可以保持 true
- ephemeral：默认 true，这是整个架构的**分叉点**（true=AP/Distro, false=CP/JRaft）
- clusterName：默认 "DEFAULT"
- metadata：扩展属性 Map，同时承载业务属性(version/region)和协议参数(preserved keys)

**equals() 基于 toString() 的非显而易见设计** (Instance.java:198-210)
- 两个 Instance 相等 = toString() 字符串完全相等
- 这意味着所有字段变更都会导致 equals 返回 false
- 这是 Nacos 客户端差分推送的基石：Set<Instance> 的差分运算能感知字段级变更
- 副作用：如果只改了 metadata 但没有 re-register，其他客户端通过 equals 对比会认为"这是不同实例"

**@JsonInclude(NON_NULL) 的影响** (Instance.java:35)
- null 字段不序列化 → JSON 反序列化时缺失字段保持 Java 默认值
- 客户端可以只发送 ip+port，其他字段用默认值
- 这是 Nacos "渐进复杂度" 策略在序列化层的体现

**三个状态维度的独立性与叠加**
- enabled(运维开关) × healthy(健康检查) × weight(负载均衡) 构成三维状态空间
- 必须 enabled=true AND healthy=true AND weight>0 才会被 selectOneHealthyInstance 选中
- 上线下线的完整动作序列：weight→0(观察) → enabled=false(正式下线) → deregister(清理)

### 3.3 ServiceInfo 模型分析

**key 设计** (ServiceInfo.java:210-224)
- separator = "@@" (Constants.java:185)
- 三种 key 格式：group@@service | group@@service@@cluster | group@@service@@cluster1,cluster2
- key 用于客户端缓存索引 + 磁盘缓存文件命名 + 服务端推送路由

**缓存过期机制** (ServiceInfo.java:113-115)
- expired() = (now - lastRefTime > cacheMillis)
- cacheMillis 默认 1000ms，服务端返回时会覆盖
- 三种刷新方式：主动查询(过期触发)、被动推送(服务端 push)、Timer 定时(兜底)

**validate() 的保护阈值逻辑** (ServiceInfo.java:182-199)
- allIPs=true(保护阈值触发) → 跳过所有健康检查,返回 true
- 正常模式 → 需要至少一个 healthy=true AND weight>0 的实例
- 保护阈值的作用：防止大量实例同时不健康时误判服务不可用导致调用链雪崩

### 3.4 临时实例 vs 持久实例 — CAP 理论的实际应用

**本质区别**
- ephemeral=true(临时)：走 Distro AP 协议，连接断开=实例自动删除，数据仅内存+Distro同步
- ephemeral=false(持久)：走 JRaft CP 协议，连接断开=仅标记不健康不删除，数据持久化到磁盘/DB

**ephemeral 的"传染性"**
- 同一个 Service 下不能混合临时和持久实例
- Service 的 isEphemeral() 由第一个注册的实例决定,之后不可变更
- EphemeralClientOperationServiceImpl 会抛出 NacosRuntimeException 拒绝类型不匹配的注册

**默认值与陷阱**
- Instance.ephemeral 默认 true，大部分微服务场景不需要关心
- 但持久实例场景(数据库节点/DNS 条目等)必须显式设置 ephemeral=false
- 一旦忘记显式设置导致数据不持久化，Nacos 重启后数据丢失

---

## 四、设计哲学分析

### 4.1 为什么注册中心 API 需要"注册+注销+发现+订阅"四步？

- 注册/注销：实例生命周期的创建和销毁 — 对应 DNS 的 A 记录注册/删除
- 发现：查询某个服务的实例列表 — 对应 DNS 解析
- 订阅：感知服务变更的推送 — DNS 没有对等手段，这是注册中心的核心增值能力
- 四步覆盖了全生命周期：上线(注册) → 运行(发现) → 变更感知(订阅) → 下线(注销)

### 4.2 为什么 NamingService 不包含"健康检查"方法？

- 关注点分离：客户端只需要知道"哪些实例可用"，不需要知道"服务端怎么判断可用"
- 解耦：1.x HTTP 心跳 → 2.x gRPC 连接检测，如果接口暴露了健康检查方法，版本升级会 break 客户端
- 客户端关心的只是 selectInstances(name, true) 的结果，不用关心背后的检测机制

### 4.3 重载设计的"渐进复杂度"哲学

- 最简用例：registerInstance(name, ip, port) — 3 个参数
- 中级用例：registerInstance(name, group, ip, port, cluster) — 5 个参数
- 高级用例：registerInstance(name, Instance) — 1个参数+全部字段可控
- 设计目标：让简单场景简单，复杂场景可能，不需要为简单场景支付复杂性成本
- 对比 Builder 模式：Builder 在 Java 8+ 更清晰，但 Nacos 需兼容 Java 7 + Spring Cloud 反射调用

---

## 五、反面假设分析 (7 组)

| # | 假设 | 后果分析 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | 如果只有一个 registerInstance(group, Instance) 而不是 6 个重载？ | 简单场景必须创建 Instance 对象 + 设置 group，增样板代码。Spring Cloud 无法通过反射简单调用单参数方法。 | 提供渐进式重载，最简单的只需 ip+port |
| 2 | 如果 subscribe 绑定在 getAllInstances 里而不是独立方法？ | 生命周期不可控：一旦 getAllInstances(name, true) 调用了就无法取消订阅。滚动发布场景下会感知到 transient 的实例下线。 | 独立 subscribe/unsubscribe 方法 |
| 3 | 如果 selectOneHealthyInstance 的 LB 算法硬编码而不是可插拔？ | Dubbo 的一致性哈希路由、gRPC 的加权轮询无法使用。不同 RPC 框架需要不同的 LB 策略。 | 通过 AbstractSelector 支持可插拔 LB |
| 4 | 如果 Instance.equals 基于 ip+port 而不是 toString()？ | metadata/weight/healthy 变更不会触发客户端差分推送。调用方感知不到实例属性变化。 | 基于 toString() 确保所有字段变更都被感知 |
| 5 | 如果 Nacos 只有临时实例(ephemeral=true)？ | 无持久化实例场景(DB 节点/DNS)。Nacos 重启后全部实例丢失，需等所有客户端重注册，有空窗期。 | 提供 ephemeral 标志，兼顾两种场景 |
| 6 | 如果 healthy 和 enabled 合并为一个 boolean？ | 无法区分"运维下线"(可恢复)和"故障不健康"(自动恢复)。下线时没有 weight→0 的灰度中间态 | 三个独立维度组成状态机 |
| 7 | 如果 ServiceInfo 的 cacheMillis 客户端写死而不是服务端返回？ | 无法根据服务端负载动态调整缓存时间。高负载时应该增大缓存时间减少查询压力。 | 服务端返回 cacheMillis，客户端遵循 |

---

## 六、新手最可能犯的 5 个错误 + Nacos 对策

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 注册后立即 getAllInstances 期望看到刚注册的实例 | 可能返回空列表(异步延迟) | 注册后等待推送或本地缓存 | SDK 的 ServiceInfoHolder 处理本地缓存脏读 |
| 2 | 忘记 shutDown() | gRPC 连接不优雅关闭,服务端等 keep-alive 超时(数分钟)才发现 | @PreDestroy 中调用 | 无自动对策,需要用户主动关闭 |
| 3 | Lambda 表达式持有 EventListener 导致内存泄漏 | ServiceInfoHolder 持有 Lambda,间接持有外部对象 | unsubscribe 配合对象生命周期 | 无自动对策,需要用户管理生命周期 |
| 4 | 持久实例注册后忘记手动 deregisterInstance | 僵尸实例永久保留,其他服务连接失败 | PreDestroy 中 deregister | 持久实例不会自动清理(by design) |
| 5 | 修改 getAllInstances 返回的 Instance 的 metadata 而不重新注册 | 只改了客户端缓存,服务端不感知 | 修改后必须重新 registerInstance | metadata 变更通过重新注册传播 |

### 6.1 可运行的错误实验

**实验 1：Lambda 持有 EventListener 导致内存泄漏**

```java
public class HeavyService {
    private byte[] heavyData = new byte[100 * 1024 * 1024]; // 100MB 数据
    
    public void subscribeToService(NamingService ns) throws NacosException {
        // 错误：Lambda 隐式捕获 this(HeavyService),持有 100MB 引用
        ns.subscribe("user-service", event -> {
            System.out.println("Instances changed: " + heavyData.length); // 隐式引用 this
        });
    }
}

// 即使 HeavyService 不再被使用,Listener 依然存活在 ServiceInfoHolder 中
// 100MB 内存无法被 GC 回收
// 正确做法：在 destroy 时 unsubscribe
```

**实验 2：注册后立即查询看不到实例**

```java
// 错误：期望同步注册
namingService.registerInstance("order-service", "10.0.0.1", 8080);
List<Instance> instances = namingService.getAllInstances("order-service");
// instances 可能为空！因为注册是异步的(事件链尚未完成)

// 正确做法：等待推送或使用本地缓存
namingService.registerInstance("order-service", "10.0.0.1", 8080);
Thread.sleep(100); // 简单场景下等待推送完成(不优雅,仅演示)
instances = namingService.getAllInstances("order-service");

// 更优雅的方式：使用 subscribe 替代轮询
namingService.subscribe("order-service", event -> {
    NamingEvent namingEvent = (NamingEvent) event;
    System.out.println("Now we have " + namingEvent.getInstances().size() + " instances");
});
```

---

## 七、面试 Q&A (5 题,每道含面试官意图 + 追问)

### Q1: NamingService 有 30+ 个方法，核心设计理念是什么？

**面试官意图**: 考察抽象能力 — 能否从海量方法签名中提取核心模型(初级只能罗列方法,高级能抽象出模型)

**参考答案要点**:
- 核心模型: Service = Instance集合, Instance由 ip+port+metadata 唯一标识
- 四种操作: 注册(上线)、注销(下线)、查找(发现)、负载均衡(选择)
- 订阅机制: 被动接收推送替代主动轮询
- 30+ 方法是核心概念在 groupName/clusters/healthy/subscribe 四个维度上的笛卡尔积展开

**追问**: "那这个接口设计有什么问题？你怎么改进？"
- 方法数量过多(38个)是 Java 缺少默认参数的妥协。用 Kotlin 或 Builder 模式可缩减到 ~10 个
- 缺少异步原生支持(都是同步 void/List 返回)
- 但考虑历史兼容性,接口稳定性 > 完美性

### Q2: selectInstances 和 getAllInstances 有什么区别？为什么这样设计？

**面试官意图**: 考察对服务发现语义的精确理解 — 健康检查信息和控制信息需要分离

**参考答案要点**:
- getAllInstances: 全量查询(含 unhealthy),管控台/运维用途
- selectInstances: 健康实例筛选,服务调用用途
- 分离 = 消费者不需要关心不健康实例,运维人员需要看到全貌
- 保护阈值场景下 selectInstances → 退回 getAllInstances 行为(防止空列表)

**追问**: "保护阈值是怎么工作的？设置 0 和不设置有什么区别？"
- 公式: 健康实例数/总实例数 < protectionThreshold → 触发保护
- 0.0(默认) = 关闭保护; 0.5 = 如果一半实例不健康就开始保护
- 保护触发后返回所有实例(含不健康的),宁可给可能不可用的地址也不给空列表

### Q3: 临时实例 vs 持久实例的架构决策依据？

**面试官意图**: 考察 CAP 理论在实际系统中的应用能力

**参考答案要点**:
- 临时实例(ephemeral=true): 选 AP — "宁可短暂数据不准,不可服务不可用" (微服务 95%+ 场景)
- 持久实例(ephemeral=false): 选 CP — "数据不能丢,不能自动清理" (基础设施服务)
- Nacos 同时支持两者 = CAP 的场景化解耦
- Eureka 只能 AP, ZK 只能 CP, Nacos 的差异化优势

**追问**: "临时实例走 AP 协议,那如果有 3 个 Nacos 节点,1 个挂了,数据会不会丢？"
- Distro V2 的冗余机制: 数据会同步到多个节点(不是只有一个副本)
- 一致性哈希决定哪个节点是"负责节点",其他节点的数据是冗余副本
- 挂掉的如果是负责节点,其他节点上有冗余数据 + 客户端重新注册会补偿

### Q4: 如果你来设计一个注册中心 API,你会怎么改进 NamingService？

**面试官意图**: 考察设计思维和批判性能力(不是找 bug,是展示设计思考)

**参考答案要点**:
- 精简重载: Builder/Options 模式替代 38→~10 个方法
- 异步原生支持: CompletableFuture<RegisterResponse>
- 条件查询: QueryBuilder 支持 metadata 过滤
- 批量操作增强: 统一的 batch(service, instances) 方法
- 但要强调: 接口稳定性 > 完美性,NamingService 已是大量框架的依赖

### Q5: NamingService 的安全性考量 — registerInstance 有没有鉴权风险？（联动 D-05/D-06）

**面试官意图**: 考察安全意识和 "API 不只是功能,还有权限" 的思维

**参考答案要点**:
- NamingService 本身没有任何鉴权注解(Secured/Token)——鉴权不在接口层
- 真正的鉴权在服务端的 RequestHandler 层: @Secured(action = ActionTypes.WRITE)
- registerInstance → 服务端 InstanceRequestHandler → @Secured → 校验 token/ak/sk → 放行或拒绝
- 这意味着：在 SDK 侧 registerInstance 没有鉴权,任何人都可以调用,但请求到了服务端后才被拦截
- 设计意图：SDK 保持轻量,鉴权集中在服务端统一管理

**追问**: "如果恶意客户端发送大量 registerInstance,怎么防御？"
- 服务端有 @TpsControl(pointName = "RemoteNamingInstanceRegisterDeregister") 限流
- ConnectionManager 有 maxConnectionPerClientIp 限制
- 实例名称合法性校验(集群名只允许 ^[0-9a-zA-Z-]+$)

---

## 八、产出文档结构

```
D-01-NamingService-API.md

1. NamingService 接口全景
   1.1 六大类操作 + 重载数量
   1.2 重载的笛卡尔积展开(以 registerInstance 为例)
   1.3 为什么不用 Builder 模式

2. Instance 数据模型
   2.1 核心字段表(含默认值源码出处)
   2.2 三个状态维度(enabled × healthy × weight)
   2.3 equals() 基于 toString() 的非显而易见设计
   2.4 @JsonInclude(NON_NULL) 的序列化影响
   2.5 PreservedMetadataKeys 的控制通道
   2.6 clusterName 的初始化链

3. ServiceInfo 模型
   3.1 核心字段表
   3.2 key 设计(@@分隔符)
   3.3 缓存过期判断
   3.4 validate() 保护阈值逻辑

4. 临时实例 vs 持久实例
   4.1 完整对比表(7 个维度)
   4.2 为什么需要两种实例(CAP 理论)
   4.3 ephemeral 的传染性

5. 设计哲学分析
   5.1 为什么"注册+注销+发现+订阅"四步
   5.2 为什么 NamingService 不包含健康检查方法
   5.3 重载的"渐进复杂度"哲学

6. 反面假设分析(7 组)

7. 新手最可能犯的 5 个错误 + 可运行的错误实验

8. 面试 Q&A(5 题,含面试官意图 + 追问,含安全性联动题)

9. 核心概念速查表
```

---

## 九、与后续文档的依赖关系

```
D-01 (NamingService API)  ← 当前
  |--- 什么是 registerInstance/selectInstances/subscribe → 下文需要用到这些术语
  ↓
D-03 (Naming SDK 全链路)  ← 使用 D-01 的接口定义,追踪实现
  ↓
D-05~D-08 (服务端核心)    ← 使用 D-01 的模型概念(Instance/Service/ServiceInfo)
```

D-01 建立了命名空间: NamingService 定义了"注册中心能做什么"; Instance 定义了"存储什么"; ServiceInfo 定义了"客户端缓存什么"。后续所有文档都在这个命名空间内讨论"如何实现"和"为什么这样实现"。
