# D-02: ConfigService API 接口设计

> 前置依赖: D-01（NamingService API） | 后续文档: D-04（Config SDK + ClientWorker）

---

## 一、ConfigService 接口全貌

ConfigService 只有 163 行（对比 NamingService 的 602 行），但它承载了 Nacos 配置中心全部对外能力：发布、获取、监听、删除。

```
api/src/main/java/com/alibaba/nacos/api/config/ConfigService.java  （163 行）
```

### 1.1 六大操作

| 类别 | 方法 | 数量 | 说明 |
|------|------|:---:|------|
| 获取 | `getConfig` | 1 | 返回配置内容 String |
| 获取+监听 | `getConfigAndSignListener` | 1 | 原子性地获取配置并注册监听器 |
| 发布 | `publishConfig` / `publishConfigCas` | 2 + 2 | CAS 版本支持乐观锁 |
| 删除 | `removeConfig` | 1 | 删除配置 |
| 监听 | `addListener` / `removeListener` | 1 + 1 | 注册和注销监听器 |
| 运维 | `getServerStatus` / `shutDown` / `addConfigFilter` | 1 + 1 + 1 | 健康检查 + 生命周期 + 过滤器 |

### 1.2 为什么这么简洁？

对比 D-01 的 NamingService 53 个方法，ConfigService 只有 12 个。根本原因：

| 维度 | Naming | Config |
|------|--------|--------|
| 数据层次 | Service → Cluster → Instance（三层） | dataId + group（一层） |
| 负载均衡 | `selectInstances` / `selectOneHealthyInstance`（16 个 LB 方法） | 不需要（配置内容直接返回） |
| 分页 | `getServicesOfServer`（4 个分页方法） | 不需要（配置管理数量远少于实例） |
| 订阅 | `subscribe`/`unsubscribe` 有多维重载 | 一个 `addListener`，无重载 |

**接口的复杂度反映了领域的复杂度**——配置是静态的 key-value，服务实例是动态的、有层次结构的。

### 1.3 dataId + group 二元组定位模型

```
Naming 的三层定位:
  定位一个服务:   groupName + serviceName
  定位一个集群:   groupName + serviceName + clusterName
  定位一个实例:   groupName + serviceName + clusterName + ip + port

Config 的一层定位:
  定位一个配置:   dataId + group（二元组）
  tenant 在 Properties 中配置，不在接口参数上
```

为什么 Config 用 `dataId` 而不是 `serviceName`？
- dataId 的命名习惯是 `application.properties`、`mysql.datasource.url`——代表"一段配置数据"
- serviceName 的命名习惯是 `user-service`、`order-service`——代表"一个服务"
- 配置是全局的，不需要按集群分组（不需要 clusterName 维度）

---

## 二、getConfig — 三种返回语义

```java
String getConfig(String dataId, String group, long timeoutMs) throws NacosException;
```

| 场景 | 返回值 | 调用方如何区分 |
|------|--------|--------------|
| 配置存在 | 配置内容 String | 直接使用 |
| 配置从未发布过 | `null` | 不抛异常，返回 null 让调用方判断 |
| 服务端不可达（超时） | 抛 `NacosException` | catch 异常 |
| 服务端不可达但本地快照存在 | 本地快照内容（SDK 内部容灾，详见 D-04） | 返回上次缓存的配置 |

**关键设计决策：配置不存在返回 null 而不是抛异常。**

考虑一个新服务首次部署的场景：
```java
// order-service 启动，尝试从 Nacos 读取自己的配置
String dbUrl = configService.getConfig("order-service.properties", "DEFAULT_GROUP", 3000);
if (dbUrl == null) {
    // 配置还没创建 → 用默认值启动，不影响服务启动
    dbUrl = "jdbc:mysql://localhost:3306/order_db";
    log.warn("order-service.properties not found in Nacos, using default: {}", dbUrl);
}
DataSource ds = createDataSource(dbUrl);  // 启动成功!
```

如果改为抛异常：新服务首次部署 → 配置还没创建 → `getConfig` 抛 `NacosException` → Spring Boot 启动流程中断 → 服务启动失败。返回 `null` 让应用可以用硬编码默认值平滑启动，运维再补充发布配置——这就是"不存在"和"出错了"的区别。

---

## 三、publishConfig — MD5 幂等 + CAS 乐观锁

### 3.1 基础发布与 MD5 幂等

```java
boolean publishConfig(String dataId, String group, String content) throws NacosException;
```

服务端接收到发布请求后的处理逻辑（详见 D-11）：

1. 计算 `content` 的 MD5
2. 与数据库中已有记录的 MD5 对比
3. **相同** → 直接返回 `true`（幂等：不写 DB，不触发通知，零开销）
4. **不同** → 写入 DB → 更新内存缓存 → 触发 `ConfigDataChangeEvent` → 通知客户端(2.x 服务端 gRPC 推送 ConfigChangeNotifyRequest;1.x 唤醒长轮询)

**这意味着**：运维脚本可以放心地"每次部署都发布一次配置"，不用担心无意义的 DB 写入和配置推送。

### 3.2 publishConfigCas — Compare-And-Swap

```java
boolean publishConfigCas(String dataId, String group, String content, String casMd5) throws NacosException;
```

`casMd5` 是你期望的"当前版本"的 MD5 值。服务端只在 **当前 MD5 == casMd5** 时才允许写入：

```
// 使用 CAS 的安全发布流程:
String oldMd5 = getMd5(configService.getConfig(dataId, group, timeoutMs));  // getConfig 返回 String,需自行算 MD5
修改配置内容
boolean ok = publishConfigCas(dataId, group, newContent, oldMd5);
if (!ok) {
    // 被人改过了! 重新 get → merge → 再 CAS
}
```

这是配置中心的**乐观锁**——类比数据库的 `UPDATE WHERE version = ?`。防止多个运维人员同时修改同一个配置时后写覆盖先写。

### 3.3 ConfigType — 配置内容格式

```java
publishConfig(String dataId, String group, String content, String type);
```

支持 8 种格式（`ConfigType.java`）：
```
PROPERTIES / XML / JSON / TEXT(默认) / HTML / YAML / TOML / UNSET
```

默认类型 = `TEXT`（`ConfigType.getDefaultType()`）。格式影响**客户端**的 diff 解析--v2 监听模型(ConfigChangeItem)对不同格式做逐 key diff,而非整串对比。服务端只存原始内容,不做 type-aware diff。

---

## 四、Listener 监听模型 — v1 vs v2

Nacos 配置监听有两代模型，各自适用不同场景。

### 4.1 v1 模型：接收完整内容

```java
// api/src/main/java/com/alibaba/nacos/api/config/listener/Listener.java
public interface Listener {
    Executor getExecutor();                          // 回调线程池
    void receiveConfigInfo(String configInfo);       // 收到完整配置内容
}
```

- `receiveConfigInfo(String)` 传入**完整配置内容**——不是变更 diff
- `getExecutor()` 是关键设计：调用方控制回调线程，不提供则用主线程回调
- `AbstractSharedListener` 是便捷抽象类:存储 dataId/group,委托给 `innerReceive(dataId, group, configInfo)`,**不提供 diff 能力**

### 4.2 v2 模型：逐 key 返回变更

```java
// ConfigChangeEvent + ConfigChangeItem
public class ConfigChangeEvent {
    Map<String, ConfigChangeItem> data;
}

public class ConfigChangeItem {
    String key;              // 变更的 key
    String oldValue;         // 旧值
    String newValue;         // 新值
    PropertyChangeType type; // ADDED / DELETED / MODIFIED
}
```

v2 模型**逐 key 返回变更**，调用方不需要自己做 diff。适用于 properties/yaml 等结构化配置。Nacos 2.x 的 `@NacosConfigListener` 注解底层就是用 v2 模型。

### 4.3 两代模型的关系

- v1（`receiveConfigInfo`）是基础，兼容所有场景：适合简单 key-value、大型配置文件
- v2（`ConfigChangeEvent`）是增强，适合结构化配置：SDK 在客户端做 content diff 后逐 key 回调
- 调用方按需选择：简单场景用 v1，需要知道"哪个 key 被改了"用 v2

---

## 五、getConfigAndSignListener - 获取+注册的合并操作

```java
String getConfigAndSignListener(String dataId, String group, long timeoutMs, Listener listener);
```

这个方法"获取当前配置的同时注册监听器"。为什么推荐用它而不是分开调用 `getConfig` + `addListener`?

**分开调用的实际问题**(不是"丢失通知",而是"CacheData 初始内容缺失"):

```
分开调用 getConfig + addListener:
  T0: getConfig("app.properties") -> 返回 "timeout=3000"(但内容只在调用方的局部变量里)
  T1: addListener("app.properties", listener) -> 注册监听器
      -> 内部调 addTenantListeners(不带 content)
      -> CacheData 创建时没有初始内容,isConsistentWithServer=false
  T2: 下一次 executeConfigListen(最多 5s 后) -> ConfigBatchListenRequest
      -> 服务端发现 MD5 不一致 -> 返回变更 -> 客户端拉取 "timeout=3000"(或最新值)
      -> listener 收到回调

  问题: T0 到 T2 之间,listener 手上没有内容(不是"一直是 3000")
        如果 T0-T1 之间配置变了,listener 要等到 T2 才知道(最多 5s 延迟)
```

**getConfigAndSignListener 的实际做法**(源码 `NacosConfigService:103-121`):

```
getConfigAndSignListener:
  -> queryConfig(dataId, group, tenant, timeoutMs) -> 从服务端获取当前内容
  -> addTenantListenersWithContent(dataId, group, content, ...) 
     -> CacheData 创建时直接带上当前 content + MD5
     -> listener 注册到一个"已知当前内容"的 CacheData 上
  -> 返回 content
```

核心区别:`addTenantListenersWithContent` 把 getConfig 返回的 content **直接灌入 CacheData**,listener 注册时 CacheData 已有正确内容。而 `addListener` 走 `addTenantListeners`(不带 content),CacheData 要等下次检查周期才填充内容。

所以这不是"防止通知丢失",而是**消除初始内容空窗期**--listener 注册的瞬间就知道当前配置是什么,不用等检查周期。

## 六、ConfigFilter — 可插拔过滤器链

```java
void addConfigFilter(IConfigFilter configFilter);  // v2.3.0+
```

过滤器链在 **SDK 端**执行，拦截 get/publish 操作：

```
ConfigService.getConfig()
  → ConfigFilterChain.doFilter(request, response)
    → filter1.doFilter() → filter2.doFilter() → ... → 实际网络请求
```

典型场景：
- **加密解密**：publish 时 AES 加密 → 服务端存储密文 → get 时解密
- **压缩解压**：大配置文件发布时 gzip 压缩
- **内容校验**：检查配置格式、长度、非法字符

过滤器在 SDK 端执行而不是服务端，意味着服务端只存原始内容——加解密和校验的 CPU 成本由客户端承担。

---

## 七、设计哲学分析

### Config 在 2.x 也迁移到了 gRPC,但推送模式和 Naming 不同

**常见误解**:以为 2.x 只有 Naming 走 gRPC,Config 还在用 HTTP 长轮询。实际上 2.x 配置客户端也全面 gRPC 化了--`ClientWorker` 内部的 `agent` 只有一个实现 `ConfigRpcTransportClient`(`ConnectionType.GRPC`),不存在 HTTP 版本。服务端仍保留 `LongPollingService` 仅为兼容 1.x 客户端。

但 Config 的 gRPC 用法和 Naming 有本质区别:

| 维度 | Naming(2.x) | Config(2.x) |
|------|------------|------------|
| 推送通道 | gRPC bi-di stream 推送完整 `NotifySubscriberRequest`(含 ServiceInfo) | gRPC bi-di stream 推送 `ConfigChangeNotifyRequest`(仅通知 dataId) |
| 变更感知 | 服务端直接推完整数据 | 服务端推通知 -> 客户端再发 `ConfigQueryRequest` **拉取内容** |
| 兜底校验 | ServiceInfoUpdateService 定时过期重查 | `executeConfigListen` 每 5s 发 `ConfigBatchListenRequest` 比对 MD5 |

**为什么 Config 用"通知+拉取"而不是像 Naming 那样直接推数据?**

- 配置内容可能很大(几十 KB 到 MB 级),而 Naming 的 ServiceInfo 通常只有几 KB。直接推完整配置 -> 带宽浪费严重
- 配置变更低频(小时/天级),单次拉取成本可接受;Naming 实例变更高频(秒级),必须直接推才能保证实时性
- 所以 Config 选择"轻量通知 + 按需拉取":服务端只推一个 dataId 通知(几十字节),客户端收到后再拉取完整内容

> 对比 1.x:1.x 配置监听确实是 HTTP 长轮询(POST `/v1/cs/configs/listener`,服务端 hold 连接 30s)。2.x 用 gRPC 服务端推送替代了这套机制,但"通知+拉取"的两步模型保留了--只是传输层从 HTTP 换成了 gRPC。

### MD5 幂等 vs CAS 乐观锁的区别

| 机制 | 作用 | 场景 |
|------|------|------|
| MD5 幂等 | 相同内容重复发布 → 零开销 | 每次部署都 `publishConfig`，不用担心重复 |
| CAS 乐观锁 | 检测并发冲突 → 拒绝覆盖 | 多人运维同一配置 |

---

## 八、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | ConfigService 也像 NamingService 做多重重载 | 接口膨胀。配置场景简单，过度重载增加学习成本。 | 每个操作 1-2 个方法 |
| 2 | 配置不存在抛异常而不是返回 null | 启动时大量配置缺失 → Spring Boot 启动失败。 | 返回 null + 异常区分 |
| 3 | Listener 回调只在主线程执行 | 一个耗时回调阻塞所有监听者。 | `getExecutor()` 可自定义线程池 |
| 4 | 不提供 `publishConfigCas` | 并发发布 → 后写覆盖先写。 | CAS 乐观锁防覆盖 |
| 5 | Listener 只接收 diff 而不接收全量 | 调用方需缓存旧内容做 diff，callbacks 顺序无法保证。 | v1 全量 + v2 diff 双模型 |

---

## 九、新手最可能犯的 4 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|---|---|---|
| 1 | 多次 `addListener` 同一个 dataId（不同 Listener 对象） | 推送时回调多次，重复处理 | 同一 Listener 引用去重，不同对象不去重 |
| 2 | `receiveConfigInfo` 中做耗时操作且不提供 `Executor` | 阻塞推送线程，所有监听者延迟 | `getExecutor()` 可返回线程池 |
| 3 | 只调 `addListener` 不调 `removeListener` | Bean 销毁后 Listener 存活，内存泄漏 | 无自动对策 |
| 4 | `getConfigAndSignListener` 的 timeoutMs 过小 | 超时返回 null 但 Listener 已注册（原子性），后续推送可能延迟 | 本地快照兜底 |

### 可运行的错误实验

**实验 1 — 主线程回调阻塞：**
```java
configService.addListener("app.properties", "DEFAULT_GROUP", new Listener() {
    @Override public Executor getExecutor() { return null; }
    @Override public void receiveConfigInfo(String configInfo) {
        try { Thread.sleep(5000); } catch (InterruptedException e) {}
        System.out.println("收到配置变更");
    }
});
// 另一个监听者被阻塞 5s 才收到通知
```

**实验 2 — CAS 冲突重试：**
```java
String oldMd5 = getMd5(configService.getConfig("limit.properties", "DEFAULT_GROUP", 3000));
// 另一个进程修改了配置
configService.publishConfig("limit.properties", "DEFAULT_GROUP", "limit=200");
// CAS 发布 → false!
boolean ok = configService.publishConfigCas("limit.properties", "DEFAULT_GROUP", "limit=100", oldMd5);
if (!ok) { /* merge and retry */ }
```

---

## 十、面试 Q&A

### Q1: ConfigService 为什么比 NamingService 简洁那么多？

**面试官意图**：考察对配置中心和注册中心场景差异的理解。

配置无层次结构（只有 dataId+group 一层）、不需要负载均衡（静态 key-value）、不需要分页（配置数量少）、监听模型单一（只有 add/remove）。接口复杂度反映了领域复杂度。

### Q2: publishConfig 和 publishConfigCas 的区别？

**面试官意图**：考察并发控制和乐观锁。

`publishConfig` 无条件覆盖。`publishConfigCas` 需要传入预期旧 MD5——服务端只在 MD5 匹配时才写入。这是配置中心的乐观锁，防止多人同时修改同一配置时覆盖。

### Q3: Listener 回调在哪个线程执行？

**面试官意图**：考察异步回调线程模型。

默认在 SDK 内部推送线程（单线程）。通过 `Listener.getExecutor()` 可以返回自定义线程池。如果 `receiveConfigInfo` 中有耗时操作（如刷新 Spring Context），**必须**提供 Executor，否则阻塞所有监听者。

### Q4: Config 和 Naming 的推送模型有什么本质区别？（联动 D-01）

**面试官意图**：考察跨模块对比。

- Naming：推送**全量 ServiceInfo** → 客户端 Map 三路差分（实例列表数据量小，全量推送可行）
- Config：推送**变更通知**（dataId 变了） → 客户端按需 getConfig 拉取（配置内容可能很大，几十 KB）
- 本质：Naming 是"端到端"推送数据，Config 是"通知+拉取"两步

### Q5: Config 和 Naming 的监听模型有什么本质区别？（联动 D-01）

**面试官意图**：考察两种"监听"底层的本质差异。

2.x 中两者都用 gRPC,但推送模式不同:
- Naming:gRPC bi-di stream 推送完整 `NotifySubscriberRequest`(含 ServiceInfo 数据)
- Config:gRPC bi-di stream 推送 `ConfigChangeNotifyRequest`(仅通知 dataId) -> 客户端再发 `ConfigQueryRequest` 拉取内容;另有 5s 周期发 `ConfigBatchListenRequest` 比对 MD5 兜底

差异根源:不是"长轮询 vs 推送",而是**推送粒度**不同--Naming 直接推数据(实例列表小,几 KB),Config 只推通知再拉取(配置内容可能几十 KB,直接推浪费带宽)。

> 1.x 时 Config 确实用 HTTP 长轮询(hold 30s),2.x 已改为 gRPC 服务端推送。服务端保留 `LongPollingService` 仅兼容 1.x 客户端。

---

## 十一、核心概念速查

| 概念 | 一句话解释 | 关��源码 |
|------|-----------|---------|
| ConfigService | 配置中心统一入口，163行定义12个方法 | `api/.../config/ConfigService.java` |
| dataId + group | 配置的二元组定位模型 | `Constants.java:32`（DEFAULT_GROUP） |
| MD5 幂等 | 相同内容重复发布不写 DB | D-11 服务端实现 |
| publishConfigCas | CAS 乐观锁防止并发覆盖 | 需传入预期旧 MD5 |
| Listener | 配置变更监听器，支持自定义 Executor | `.../listener/Listener.java:33` |
| ConfigChangeEvent | v2 监听模型，逐 key 返回变更 | `.../ConfigChangeEvent.java` |
| ConfigFilter | SDK 端过滤器链，拦截 get/publish | `.../filter/IConfigFilter.java` |

---

## 九、测试分析

### 9.1 NacosConfigServiceTest (310 行)

`client/src/test/java/com/alibaba/nacos/client/config/NacosConfigServiceTest.java`

| 测试方法 | 验证内容 |
|---------|---------|
| `testGetConfig` | getConfig 委托给 ClientWorker.getServerConfig；正确返回内容 |
| `testGetConfigWithTypes` | getConfig 携带 ConfigType 参数 |
| `testPublishConfig` | publishConfig 委托给 ClientWorker.publishConfig |
| `testPublishConfigWithCas` | publishConfigCas 传入 casMd5 参数 |
| `testAddListener` | addListener -> worker.addTenantListeners |
| `testRemoveListener` | removeListener -> worker.removeListeners |
| `testConfigFilter` | ConfigFilter 链在获取配置后过滤内容 |

Mockito 模拟 `ClientWorker`，不涉及真实网络调用。

### 9.2 ClientWorkerTest (208 行)

`client/src/test/java/com/alibaba/nacos/client/config/impl/ClientWorkerTest.java`

| 测试方法 | 验证内容 |
|---------|---------|
| `testConstruct` | 构造函数创建 ClientWorker 实例 |
| `testAddListenerWithoutTenant` | addListener/removeListener -> CacheData 中的 Listener 增删 |
| `testListenerWithTenant` | 含租户(命名空间)的 Listener 注册 |
| `testAddListenersForBatch` | 批量添加多个 Listener |
| `testNotifyAllListeners` | 配置变更后通知所有 Listener |
| `testGetServerConfig` | checkLocalConfig 优先于服务端查询的路径选择 |

## 十、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Facade** | `ConfigService` 接口 | 为配置管理提供统一入口 |
| **Observer** | `Listener` + `ClientWorker` | 配置变更时通知所有注册的 Listener |
| **Chain of Responsibility** | `ConfigFilterChainManager` | getConfig/publishConfig 经过过滤器链 |
| **Strategy** | `ConfigTransportClient` | gRPC vs HTTP 双协议路由 |
| **Guarded Suspension** | `ConfigRpcTransportClient.listenExecutebell` | 5s 兜底轮询 + 推送唤醒（bell 队列），避免空转 |
| **Cache-Aside** | `LocalConfigInfoProcessor` | 本地快照缓存，服务端不可达时降级 |
| **CAS** | `publishConfigCas` | 乐观锁防止并发覆盖 |

## 十一、本 Lab 中的实际映射

| Lab 场景 | 对应源码 |
|---------|---------|
| A-02 `GET /config/current` | `DynamicConfigController` -> `ConfigService.getConfig()` |
| A-02 `GET /config/all-sources` | 多个 `ConfigService.getConfig()` 查询不同 dataId+group |
| B-02 配置监听 | `ConfigService.addListener()` -> `ClientWorker.addCacheDataIfAbsent()` |
| B-02 配置刷新 | ConfigChangeNotifyRequest -> Listener.receiveConfigInfo() |
