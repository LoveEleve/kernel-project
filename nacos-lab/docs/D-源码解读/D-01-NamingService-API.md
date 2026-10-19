# D-01: NamingService 接口设计 + Instance/ServiceInfo 数据模型

> 前置依赖: 无（Nacos 源码学习的起点）
> 后续文档: D-03（NamingService SDK 全链路）、D-05~D-08（服务端核心）

---

## 一、NamingService 接口全景

NamingService 是 Nacos 注册中心的**唯一入口接口**——客户端（Spring Cloud、Dubbo、手写代码）通过它和 Nacos 通信。它是一个 602 行的 Java 接口，定义了 53 个方法，覆盖了服务注册发现的全部能力。

```
api/src/main/java/com/alibaba/nacos/api/naming/NamingService.java  （602 行）
```

### 1.1 六大类操作

NamingService 的方法按操作类型分为 8 大类：

| 类别 | 方法 | 方法数 | 说明 |
|------|------|:-----:|------|
| **注册** | `registerInstance` | 6 | — |
| **批量** | `batchRegister`/`batchDeregister` | 2 | v2.1.1/v2.2.0 新增 |
| **注销** | `deregisterInstance` | 6 | — |
| **全量查询** | `getAllInstances` | 8 | 返回所有实例，不管是否健康 |
| **健康筛选** | `selectInstances` | 8 | 只返回健康实例 |
| **负载均衡** | `selectOneHealthyInstance` | 8 | 从健康实例中按权重选一个 |
| **订阅** | `subscribe` / `unsubscribe` | 4 + 4 | 监听服务变更 |
| **运维** | `getServicesOfServer` / `getSubscribeServices` / `getServerStatus` / `shutDown` | 4 + 1 + 1 + 1 | 管控台 + 客户端生命周期 |

### 1.2 重载的笛卡尔积展开

每个操作都有多个重载，组合维度固定为四个：

| 维度 | 类型 | 默认值 | 源码出处 |
|------|------|--------|---------|
| serviceName | String | 必填 | — |
| groupName | String | `"DEFAULT_GROUP"` | `Constants.java:32` |
| clusters | List\<String\> | 空列表（所有集群） | — |
| Instance vs 原始参数 | — | — | — |

以 `registerInstance` 为例的 6 个方法重载：

```java
// 六个注册重载的层层递进（2.3.0 签名，均 throws NacosException）：
// Level 0: 最简形式 — 只需 3 个参数
void registerInstance(String serviceName, String ip, int port) throws NacosException;

// Level 1: 加上 group
void registerInstance(String serviceName, String groupName, String ip, int port) throws NacosException;

// Level 2: 加上 cluster
void registerInstance(String serviceName, String ip, int port, String clusterName) throws NacosException;

// Level 3: group + cluster 完整
void registerInstance(String serviceName, String groupName, String ip, int port, String clusterName)
        throws NacosException;

// Level 4: 使用 Instance 对象 — 所有字段都可控
void registerInstance(String serviceName, Instance instance) throws NacosException;
void registerInstance(String serviceName, String groupName, Instance instance) throws NacosException;

// v2.1.1+: 批量操作
void batchRegisterInstance(String serviceName, String groupName, List<Instance> instances) throws NacosException;
void batchDeregisterInstance(String serviceName, String groupName, List<Instance> instances) throws NacosException;
```

**渐进复杂度哲学**：90% 的使用场景只需 `registerInstance("my-service", "10.0.0.1", 8080)`——简单场景简单，复杂场景可能。

### 1.3 为什么不用 Builder 模式？

Builder 模式确实更清晰（`NamingService.register(RegisterRequest.builder()...)`），但 Nacos 选择方法重载，原因有三：

1. **历史兼容**：最早的签名就是 `registerInstance(name, ip, port)`，改为 Builder 会 break 所有已有代码
2. **Java 7 兼容**：早期 Nacos 需要兼容 Java 7，Builder+Optional 需要额外依赖
3. **Spring Cloud 集成友好**：`NacosDiscoveryProperties` 通过反射调用 NamingService 方法，重载签名对反射调用更友好

---

## 二、Instance 数据模型 — 注册中心的原子单元

Instance 是 Nacos 中最核心的数据结构——一个 Instance 对象代表一个被注册的服务提供方实例。

```
api/src/main/java/com/alibaba/nacos/api/naming/pojo/Instance.java  （267 行）
```

### 2.1 核心字段

| 字段 | 类型 | 默认值 | 源码行号 | 含义 |
|------|------|--------|:----:|------|
| `instanceId` | String | null | — | 服务端自动生成，格式：`ip#port#clusterName#serviceName` (`Constants.java:165-167`：分隔符 `#` + 4 段) |
| `ip` | String | null | — | 实例 IP |
| `port` | int | 0 | — | 实例端口 |
| `weight` | double | `1.0D` | `Instance.java:58` | 负载均衡权重，0 = 不分配流量 |
| `healthy` | boolean | `true` | `:63` | 健康状态，服务端健康检查可改变 |
| `enabled` | boolean | `true` | `:68` | 是否接受请求，运维手动控制 |
| `ephemeral` | boolean | `true` | `:75` | **架构分叉点** — true=AP/Distro， false=CP/JRaft |
| `clusterName` | String | null → `"DEFAULT"` | `Constants.java:171` | 集群名 |
| `serviceName` | String | null | — | 所属服务名 |
| `metadata` | Map\<String,String\> | `new HashMap<>()` | — | 扩展属性 |

### 2.2 三个状态维度的独立性

Instance 的状态不是单一的 boolean，而是三个独立维度的叠加：

```
         enabled=false ─── 完全不可用（运维手动下线，可恢复）
        /
Instance ─ enabled=true
            ├── healthy=false ─── 暂时不可用（健康检查失败，自动恢复）
            └── healthy=true ──── 正常可用
                └── weight=0 ──── 可用但不分配流量（灰度下线中间态）
```

`selectOneHealthyInstance` 只有实例同时满足 **enabled=true AND healthy=true AND weight>0** 时才会被选中。这三个维度的独立性实现了"平滑下线"——先将 weight 降到 0 观察，再将 enabled 设为 false 正式下线，最后 deregister 清理。

### 2.3 equals() 基于 toString() 的非显而易见设计

```java
// Instance.java:198-210
@Override
public boolean equals(final Object obj) {
    if (!(obj instanceof Instance)) {
        return false;
    }
    final Instance host = (Instance) obj;
    return Instance.strEquals(host.toString(), toString());  // 比较字符串！
}

@Override
public int hashCode() {
    return toString().hashCode();
}
```

**两个 Instance 相等 = toString() 字符串完全相等。** 这意味着所有字段（ip/port/weight/healthy/enabled/ephemeral/clusterName/serviceName/metadata）的任何变更都会导致 equals 返回 false。

**这是 Nacos 客户端差分推送的基石**：当服务端推送新的 ServiceInfo 时，客户端在 `ServiceInfoHolder.isChangedServiceInfo()` 中做新旧列表的**三路差分**(而非简单的 Set 减法)。实际机制如下：

```java
// 源码 ServiceInfoHolder.isChangedServiceInfo():
// 1. 用 ip:port 建索引(不是用 Instance.equals 做 Set 运算!)
Map<String, Instance> oldHostMap = ... // key = host.toInetAddr() = "ip:port"
Map<String, Instance> newHostMap = ... // key = host.toInetAddr() = "ip:port"

// 2. 三路差分:
Set<Instance> newHosts = new HashSet<>();  // 新增: newHostMap 有, oldHostMap 没有
Set<Instance> remvHosts = new HashSet<>();  // 移除: oldHostMap 有, newHostMap 没有
Set<Instance> modHosts  = new HashSet<>();  // 修改: ip:port 相同, 但 toString() 不同!

// 关键: 修改检测用的是 toString() 比较:
if (oldHostMap.containsKey(key) 
    && !StringUtils.equals(host.toString(), oldHostMap.get(key).toString())) {
    modHosts.add(host);  // 同一个 ip:port, 但字段变了(如 weight 变了)
}
```

以权重变更为例:
```
旧列表: {Instance(10.0.0.1:8080, weight=1), Instance(10.0.0.2:8080, weight=1)}
新列表: {Instance(10.0.0.1:8080, weight=2), Instance(10.0.0.2:8080, weight=1)}

三路差分结果:
  newHosts = {}                          // 没有新增的 ip:port
  remvHosts = {}                         // 没有移除的 ip:port
  modHosts  = {Instance(10.0.0.1, weight=2)}  // 同 ip:port, toString 不同 -> 修改!
```

**注意**:差分索引用的是 `toInetAddr()`(ip:port),不是 `Instance.equals()`。`Instance.equals()` 基于 `toString()` 的设计,其价值体现在**修改检测**(line 212:同 ip:port 时用 `toString()` 比对判断是否字段变更)。如果 equals 只基于 ip+port,`toString()` 比较就退化为永远相等,权重/healthy/metadata 变更就不会被归入 modHosts,客户端感知不到字段级变化。

**副作用**：Instance 的 hashCode 基于 toString()，如果把它放入 HashSet/HashMap 后再修改任何字段，hashCode 会变化导致元素"丢失"(remove/contains 失败)。这也是为什么 `ServiceInfoHolder` 的差分用 `toInetAddr()`(ip:port)做 Map key 而不是直接用 Instance 做 key--ip:port 是稳定的,toString 不是。

### 2.4 @JsonInclude(NON_NULL) 的序列化影响

```java
@JsonInclude(Include.NON_NULL)  // Instance.java:35
```

- null 字段不会被序列化到 JSON → 反序列化时缺失字段保持 Java 默认值
- 客户端可以只发送 `ip` 和 `port`，其余字段使用默认值（weight=1.0, healthy=true, ephemeral=true）
- 这是"渐进复杂度"策略在序列化层的体现——简单场景下不需要关心所有字段

### 2.5 PreservedMetadataKeys — 客户端到服务端的控制通道

Instance 的 metadata 不仅是用户自定义属性，Nacos 保留了一组特殊 key 作为客户端到服务端的协议参数：

```java
// PreservedMetadataKeys.java
public static final String REGISTER_SOURCE       = "preserved.register.source";        // 来源：Dubbo/SpringCloud
public static final String HEART_BEAT_TIMEOUT    = "preserved.heart.beat.timeout";     // 心跳超时：15s
public static final String IP_DELETE_TIMEOUT     = "preserved.ip.delete.timeout";      // IP删除超时：30s
public static final String HEART_BEAT_INTERVAL   = "preserved.heart.beat.interval";    // 心跳间隔：5s
public static final String INSTANCE_ID_GENERATOR = "preserved.instance.id.generator";  // ID生成策略："simple"
```

关键默认值（`Constants.java:173-177`）：
- `DEFAULT_HEART_BEAT_INTERVAL = 5000` （5 秒）
- `DEFAULT_HEART_BEAT_TIMEOUT = 15000` （15 秒）
- `DEFAULT_IP_DELETE_TIMEOUT = 30000` （30 秒）

注意：Nacos 2.x 中这些心跳参数主要用于兼容 1.x，实际健康检查已由 gRPC keep-alive 接管。

---

## 三、ServiceInfo 模型 — 客户端缓存的核心

ServiceInfo 是客户端缓存服务信息的数据容器——一个 ServiceInfo 对象代表客户端持有的"某个服务在某时刻的实例快照"。

```
api/src/main/java/com/alibaba/nacos/api/naming/pojo/ServiceInfo.java  （278 行）
```

### 3.1 核心字段

| 字段 | 类型 | 默认值 | 含义 |
|------|------|--------|------|
| `name` | String | null | 服务名 |
| `groupName` | String | null | 分组名 |
| `clusters` | String | null | 集群名（逗号分隔） |
| `cacheMillis` | long | `1000L` | 缓存有效期（毫秒） |
| `hosts` | List\<Instance\> | `new ArrayList<>()` | 实例列表 |
| `lastRefTime` | long | `0L` | 最后引用时间 |
| `checksum` | String | `""` | 校验和 |
| `allIPs` | boolean | `false` | 客户端 getAllInstances 显式请求全量时为 true |
| `reachProtectionThreshold` | boolean | `false` | 是否达到保护阈值 |

### 3.2 key 设计 — `groupName@@name@@clusters`

```java
// ServiceInfo.java:211-224
public String getKey() {
    String serviceName = getGroupedServiceName();
    return getKey(serviceName, clusters);
}

@JsonIgnore
public static String getKey(String name, String clusters) {
    
    if (!isEmpty(clusters)) {
        return name + Constants.SERVICE_INFO_SPLITER + clusters;
    }
    
    return name;
}
```

分隔符 `@@` (`Constants.java:185` — `SERVICE_INFO_SPLITER`) 将 groupName 和 serviceName 分开。key 有三种形式：

| 形式 | 示例 | 使用场景 |
|------|------|----------|
| `group@@service` | `DEFAULT_GROUP@@user-service` | 不指定集群，查询所有集群 |
| `group@@service@@cluster` | `DEFAULT_GROUP@@user-service@@SH` | 指定集群查询 |
| `group@@service@@c1,c2` | `DEFAULT_GROUP@@user-service@@SH,BJ` | 多集群查询 |

这个 key 用于客户端本地缓存索引（`ServiceInfoHolder.cacheMap`）、磁盘缓存文件命名、以及服务端推送路由。

### 3.3 缓存过期判断

```java
// ServiceInfo.java:113-115
public boolean expired() {
    return System.currentTimeMillis() - lastRefTime > cacheMillis;
}
```

`cacheMillis` 默认 1000ms，但服务端返回时会覆盖。客户端有三种刷新方式：
- 主动查询：`getAllInstances()` 时如果缓存过期则向服务端查询
- 被动推送：服务端推送 ServiceInfo → `ServiceInfoHolder.processServiceInfo()` 更新缓存
- Timer 定时：`ServiceInfoUpdateService` 定时检查过期缓存兜底

### 3.4 validate() - 客户端侧的可用性兜底

```java
// ServiceInfo.java:182-200
public boolean validate() {
    if (isAllIPs()) {
        return true;  // 客户端显式请求全量 IP(getAllInstances) -> 跳过校验
    }
    
    if (hosts == null) {
        return false;
    }
    
    boolean existValidHosts = false;
    for (Instance host : hosts) {
        if (host.isHealthy() && host.getWeight() > 0) {
            existValidHosts = true;
            break;
        }
    }
    return existValidHosts;
}
```

`validate()` 是**客户端侧**的兜底判断:从本地缓存取到 ServiceInfo 后,先确认它至少含有一个 `healthy=true AND weight>0` 的实例,否则视为无效、重新向服务端查询。`allIPs=true` 分支是给 `getAllInstances`(显式要全部 IP,含不健康实例)用的——这种场景本身就接受不健康实例,所以跳过校验。

**注意:`allIPs` 和保护阈值无关。** 保护阈值是**服务端**机制,不在 `validate()` 里体现,详见下文。

#### 保护阈值的真实机制(服务端)

保护阈值不在 `validate()` 里,而是服务端在 `selectInstances`/推送前做的一道防护(`ServiceUtil.selectInstancesWithHealthyProtection`):

```
触发条件(源码 ServiceUtil):
  healthyCount / totalInstanceCount <= protectThreshold   // 注意是 <=, 不是 <

触发后:
  1. setReachProtectionThreshold(true)        // 标记触发了保护(不是 setAllIPs!)
  2. 把所有不健康实例强制 setHealthy(true)      // 源码注释: "set all to healthy state to protect"
  3. 返回全部实例(含原本不健康的)
```

效果:`selectInstances(healthy=true)` 在保护触发时**不会返回空列表**,而是返回"被标记为健康的全部实例"——宁可给可能不可用的地址,也不让调用方无地址可调,防止调用链雪崩。

**默认 `protectThreshold=0.0` 不是"关闭保护"。** 因为 `0/total <= 0.0` 在零健康实例时仍成立,保护仍会触发(返回全部、避免空列表)。准确含义:0.0 = "仅在零健康实例时保护";0.5 = "健康实例低于一半就开始保护"。代码还会把负阈值 clamp 到 0,所以保护**无法完全关闭**——至少在"一个健康实例都没有"时仍然兜底。

> 易错点:`allIPs` 字段在生产代码中只由客户端全量查询时携带,服务端从不主动 `setAllIPs(true)`。把它和保护阈值挂钩是常见误解。

---

## 四、临时实例 vs 持久实例 — CAP 理论的实际应用

Instance 的 `ephemeral` 标志（默认 `true`）是 Nacos 整个架构的分叉点——它决定了一个实例走 AP 还是 CP。

### 4.1 完整对比

| 维度 | 临时实例 (ephemeral=true) | 持久实例 (ephemeral=false) |
|------|---------------------------|---------------------------|
| **默认值** | Nacos 默认 | 需显式设置 |
| **一致性协议** | Distro (AP) | JRaft (CP) |
| **健康检查** | gRPC 连接存活 | gRPC 连接存活，断开仅标记不健康 |
| **连接断开行为** | 自动删除该 Client 下所有实例 | 标记不健康，数据保留 |
| **数据持久化** | 仅内存 + Distro 同步 | 内存 + **JRaft 日志 + Snapshot(zip)**（不是 Derby/MySQL——那是 Config 的存储，见 D-10） |
| **Nacos 重启后** | 丢失，需客户端重新注册 | 保留，从状态机恢复 |
| **典型场景** | 微服务实例（Spring Boot 进程级） | 数据库节点、DNS 服务 |

### 4.2 ephemeral 的传染性

同一个 Service 下**不能混合**临时和持久实例。Service 的 `isEphemeral()` 由**第一个注册的实例**决定，之后不可变更。`EphemeralClientOperationServiceImpl` 会在注册类型不匹配时抛出 `NacosRuntimeException`。

### 4.3 为什么需要两种实例？

这是 CAP 理论在工程中的场景化解耦：

- **临时实例**选 AP：微服务实例的生命周期短暂（秒级上下线），短暂的数据不一致可接受，但注册中心的**可用性不能丢**。如果 Nacos 故障导致无法注册，所有新启动的服务都无法被发现。
- **持久实例**选 CP：数据库节点、DNS 条目等基础设施数据需要**强一致性和持久性**。这些数据往往是手工注册的，不能在 Nacos 重启后丢失。

---

## 五、设计哲学分析

### 为什么注册中心 API 需要"注册+注销+发现+订阅"四步？

| 步骤 | 语义 | 类比 |
|------|------|------|
| 注册 | "我上线了，这是我的地址" | DNS A 记录注册 |
| 注销 | "我下线了，忘记我吧" | DNS A 记录删除 |
| 发现 | "谁在提供这个服务？" | DNS 解析 |
| 订阅 | "如果有新提供者出现，通知我" | DNS 无对等手段 |

**订阅是注册中心区别于普通 DNS 的关键能力。** 如果只有注册+发现，那就和 DNS 没有本质区别。订阅机制让客户端可以**主动感知**服务端变化，而不需要每次调用都去查询。

### 为什么 NamingService 不包含健康检查方法？

客户端只需要知道"哪些实例可用"（`selectInstances(name, true)` 的结果），不需要知道服务端**怎么判断**可用。如果把健康检查方法暴露给客户端：
- 1.x HTTP 心跳 → 2.x gRPC 连接检测的版本升级会 break 客户端
- 客户端需要理解服务端内部的健康检查机制才能正确使用

这是**关注点分离**原则——服务端的实现应该对客户端透明。

---

## 六、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | 只有一个 `registerInstance(group, Instance)` 而不是 6 个重载？ | 简单场景必须创建 Instance 对象，增样板代码。Spring Cloud 无法通过反射简单调用。 | 渐进式重载，最简单只需 ip+port |
| 2 | `subscribe` 绑定在 `getAllInstances` 里？ | 生命周期不可控，调用过就无法取消订阅。 | 独立 subscribe/unsubscribe 方法 |
| 3 | `selectOneHealthyInstance` 的 LB 算法可插拔？ | 实际**硬编码**为 `Balancer.RandomByWeight`(加权随机)，Dubbo 一致性哈希等无法通过 API 定制。但 Spring Cloud/Dubbo 都在客户端侧自行实现 LB(Ribbon/LoadBalancer)，不依赖此方法。 | LB 硬编码在 SDK 内；`AbstractSelector` 只用于服务端实例过滤(Service.selector)，不参与客户端 LB |
| 4 | `Instance.equals` 基于 ip+port？ | metadata/weight/healthy 变更不会触发客户端差分推送。 | 基于 toString()，所有字段变更都被感知 |
| 5 | Nacos 只有临时实例？ | 无持久化实例场景。重启后全丢，需等所有客户端重新注册。 | ephemeral 标志兼顾两种场景 |
| 6 | `healthy` 和 `enabled` 合并？ | 无法区分运维下线（可恢复）和故障不健康（自动恢复）。 | 三个独立维度组成状态机 |
| 7 | `ServiceInfo.cacheMillis` 客户端写死？ | 无法根据服务端负载动态调整缓存时间。 | 服务端返回，客户端遵循 |

---

## 七、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 内置对策 |
|---|---|---|---|
| 1 | 注册后立即 `getAllInstances` 期望看到刚注册的实例 | 可能不包含新实例(注册请求同步,但缓存由推送异步更新,若缓存未过期则返回旧缓存) | SDK 的 ServiceInfoHolder 处理缓存脏读 |
| 2 | 忘记 `shutDown()` | gRPC 连接不优雅关闭，服务端等 keep-alive 超时才感知 | 无自动对策，需用户主动关闭 |
| 3 | Lambda 持有 EventListener 导致内存泄漏 | ServiceInfoHolder 持有 Lambda，间接持有外部对象 | 无自动对策 |
| 4 | 持久实例注册后忘记手动 `deregisterInstance` | 僵尸实例永久保留，其他服务连接失败 | 持久实例不会自动清理（by design） |
| 5 | 修改 `getAllInstances` 返回的 Instance 的 metadata 不重新注册 | 只改了客户端缓存，服务端不感知 | metadata 变更通过重新注册传播 |

### 可运行的错误实验

**实验 1 — Lambda 持有 EventListener 导致内存泄漏：**
```java
public class HeavyService {
    private byte[] heavyData = new byte[100 * 1024 * 1024]; // 100MB
    
    public void subscribe(NamingService ns) throws NacosException {
        ns.subscribe("user-service", event -> {
            System.out.println(heavyData.length); // Lambda 捕获 this → 100MB 无法 GC
        });
    }
}
// HeavyService 不再使用 → 但 Listener 存活在 ServiceInfoHolder 中 → 内存泄漏
// 正确: @PreDestroy 中调用 namingService.unsubscribe(serviceName, listener)
```

**实验 2 — 注册后立即查询看不到实例：**
```java
namingService.registerInstance("order-service", "10.0.0.1", 8080);
List<Instance> instances = namingService.getAllInstances("order-service");
// instances 可能不包含刚注册的实例!(注册同步,但本地缓存由推送异步更新)
// 正确: subscribe 替代轮询
```

---

## 八、面试 Q&A

### Q1: NamingService 有 50+ 个方法，核心设计理念是什么？

**面试官意图**：考察抽象能力——能否从海量方法签名中提取核心模型。

核心模型：Service = Instance 集合，Instance 由 ip+port+metadata 唯一标识。5 种核心操作（注册、注销、查找、LB、订阅）在 groupName/clusters/healthy/subscribe 四个维度上做笛卡尔积展开，产生了 53 个方法。理解了这个展开逻辑，所有方法签名都是自解释的。

**追问**：那这个接口设计有什么问题？
- 方法数量过多是 Java 缺少默认参数的妥协。用 Kotlin 或 Builder 模式可缩减到 ~10 个。但历史兼容性 > 完美性。

### Q2: selectInstances 和 getAllInstances 有什么区别？

**面试官意图**：考察对服务发现语义的精确理解。

`getAllInstances` 返回全量（含 unhealthy），管控台/运维用途。`selectInstances` 只返回健康实例，服务调用用途。保护阈值场景下 `selectInstances` 会退回 `getAllInstances` 行为——宁可给可能不可用的地址，也不给空列表。

**追问**：保护阈值公式？
- 健康实例数/总实例数 **<=** protectionThreshold -> 触发(注意是 `<=`,源码 `ServiceUtil` 用 `<=`)。
- 默认 0.0 **不是关闭保护**:零健康实例时 `0/total <= 0.0` 仍成立,仍会触发(返回全部、避免空列表)。0.0 = "仅在零健康时保护";0.5 = "健康实例低于一半就保护"。
- 触发后服务端把不健康实例强制标记 healthy=true 再返回(靠 `reachProtectionThreshold`,不是 `allIPs`)。

### Q3: 临时实例 vs 持久实例的架构决策依据？

**面试官意图**：考察 CAP 理论的实际应用。

临时实例（ephemeral=true）选 AP："宁可短暂数据不准，不可服务不可用"（95%+ 微服务场景）。持久实例（ephemeral=false）选 CP："数据不能丢，不能自动清理"（基础设施）。Nacos 同时支持两者 = CAP 的场景化解耦。

**追问**：临时实例走 AP，3 节点挂 1 个数据会丢吗？
- Distro V2 数据冗余在多个节点上。**简单取模**（`Math.abs(tag.hashCode()) % serverCount`，非一致性哈希）决定"负责节点"，其他节点有冗余副本（见 D-09 §9.3）。

### Q4: 如果你来设计一个注册中心 API，你会怎么改进？

**面试官意图**：考察设计思维和批判性能力。

精简重载（Builder 模式 53→~10）、异步原生支持（CompletableFuture）、条件查询（QueryBuilder 支持 metadata 过滤）。但要强调：接口稳定性 > 完美性，NamingService 已是大量框架的依赖。

### Q5: registerInstance 有没有鉴权风险？

**面试官意图**：考察安全意识和 API 分层设计。

NamingService 没有鉴权注解——鉴权不在接口层，而在服务端的 `@Secured(action = ActionTypes.WRITE)`。SDK 保持轻量，鉴权集中在服务端统一管理。恶意请求由 `@TpsControl` 限流 + ConnectionManager 连接数限制防御。

---

## 九、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| NamingService | 注册中心统一入口，602行定义53个方法 | `api/.../naming/NamingService.java` |
| Instance | 服务实例的完整描述，equals基于toString() | `api/.../pojo/Instance.java:198-210` |
| ServiceInfo | 客户端缓存的服务信息快照，key用@@分隔 | `api/.../pojo/ServiceInfo.java` |
| ephemeral | 决定走 AP 还是 CP 的开关，默认 true | `Instance.ephemeral`（`:75`） |
| DEFAULT_GROUP | 未指定 group 时的默认值 | `Constants.java:32` |
| SERVICE_INFO_SPLITER | ServiceInfo key 分隔符 = "@@" | `Constants.java:185` |
| PreservedMetadataKeys | metadata 中保留 key，客户端→服务端控制通道 | `PreservedMetadataKeys.java` |

---

## 十、测试分析

### 10.1 InstanceTest (168 行)

`api/src/test/java/com/alibaba/nacos/api/naming/pojo/InstanceTest.java`

| 测试方法 | 验证内容 |
|---------|---------|
| `testSetAndGet` | 默认值 (weight=1.0, healthy=true, ephemeral=true) + setter/getter |
| `testJsonSerialize` | `@JsonInclude(NON_NULL)` —— null 字段不序列化；`preserved` 心跳参数序列化 |
| `testJsonDeserialize` | JSON 反序列化后所有字段正确还原 |
| `testToInetAddr` | `toInetAddr()` 格式 `ip:port` (用于差分推送的 key) |
| `testContainsMetadata` | `metadata=null` 时 `containsMetadata` 不抛 NPE |
| `testGetInstanceIdGenerator` | metadata 中 `INSTANCE_ID_GENERATOR` 覆盖默认生成器 |
| `testEquals` | 关键: 同一个 `toString()` 的 Instance 相等；metadata 变更后不等 |

**测试覆盖的边界情况**:

| 测试 | 边界 | 对应源码 |
|------|------|---------|
| 默认值 | 未设置字段时 weight=1.0, healthy=true, ephemeral=true | `Instance.java` 字段声明 |
| JSON NON_NULL | null 字段跳过序列化 | `@JsonInclude(Include.NON_NULL)` |
| toInetAddr | ip:port 格式作为 Map key 的稳定性 | `Instance.java:193-195` |
| equals 基于 toString | metadata 变更 -> toString 变 -> equals false | `Instance.java:197-205` |
| metadata 为 null | `containsMetadata` 不抛异常 | `Instance.java:135-145` |

### 10.2 ServiceInfoTest (175 行)

`api/src/test/java/com/alibaba/nacos/api/naming/pojo/ServiceInfoTest.java`

| 测试方法 | 验证内容 |
|---------|---------|
| `testSerialize` | JSON 序列化排除 `key`、`keyEncoded`、`jsonFromServer`（计算字段） |
| `testDeserialize` | 反序列化后默认 `expired()=true`（`lastRefTime=0`, `cacheMillis=1000`） |
| `testGetKey` | key 格式 `group@@name@@clusters` |
| `testValidateForAllIps` | `allIPs=true` -> 跳过校验 |
| `testValidateForNullHosts` | `hosts=null` -> 返回 false |
| `testValidateForEmptyHosts` | 空列表 -> 返回 false |
| `testValidateForUnhealthyHosts` | 全部不健康 -> 返回 false |
| `testValidateForBothUnhealthyAndHealthyHosts` | 至少一个健康 -> 返回 true |
| `testFromKey` | `fromKey("group@@name@@c2")` 正确解析 cluster |
| `testReachProtectionThreshold` | `reachProtectionThreshold` JSON 序列化/反序列化 |

**测试覆盖矩阵**:

```
validate() 的分支覆盖:
  allIPs=true      → true   (testValidateForAllIps)
  hosts=null        → false  (testValidateForNullHosts)
  hosts=empty       → false  (testValidateForEmptyHosts)
  hosts=[unhealthy] → false  (testValidateForUnhealthyHosts)
  hosts=[unhealthy, healthy] → true (testValidateForBothUnhealthyAndHealthyHosts)
```

## 十一、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Facade** | `NamingService` 接口 | 为服务注册发现提供统一入口，隐藏 gRPC/HTTP 双协议 |
| **DTO** | `Instance`, `ServiceInfo` | 数据传输对象，序列化为 JSON 在网络间传递 |
| **Key-Value** | `ServiceInfo.key` = `group@@name@@clusters` | 客户端缓存索引键，用于本地缓存和推送路由 |
| **Protection Proxy** | `selectInstancesWithHealthyProtection` | 健康实例不足时，代理返回全部实例防止调用链雪崩 |
| **Flyweight** | `Instance 复用` | 测试中 `InstanceBuilder` 提供 Builder 模式创建 Instance |
| **Strategy** | `selectOneHealthyInstance` → `Balancer.RandomByWeight` | 负载均衡算法可替换，默认为加权随机 |
| **Separated Interface** | NamingService 接口与 `NacosNamingService` 实现分离 | API 与实现解耦，支持多版本实现 |

## 十二、本 Lab 中的实际映射

| Lab 场景 | 对应源码 |
|---------|---------|
| A-01 健康检查 `GET /health` | `RegistrationController.java` -> `NamingService.registerInstance()` |
| A-01 查看状态 `GET /discovery/status` | `NamingService.getServicesOfServer()` + `selectInstances()` |
| A-07 服务发现 `NacosLabClient(name="nacos-lab")` | `NamingService.selectInstances("nacos-lab")` -> `serviceInfo.getHosts()` |
| A-07 LB 实例列表 `GET /lb/instances` | `MetadataLBTestController` -> `NamingService.selectInstances()` |
| B-04 `NamingServiceInterceptor` | 拦截 `NamingService.registerInstance()` 注入自定义 metadata |
