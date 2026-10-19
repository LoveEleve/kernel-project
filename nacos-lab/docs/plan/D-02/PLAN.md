# D-02 详细规划: ConfigService API 接口设计

> 阶段: Phase 1 — API 契约层 | 预期产出: `md/D-02-ConfigService-API.md` | 状态: 待审核

---

## 一、为什么需要这篇

Nacos = 注册中心(Naming) + 配置中心(Config)，两者共用同一个 SDK 和集群基础设施，但提供不同的 API 契约。D-01 覆盖了 Naming 侧，D-02 覆盖 Config 侧。不理解 ConfigService 就无法理解后续的配置 SDK 全链路和配置变更推送体系。

ConfigService 只有 163 行（vs NamingService 的 602 行），但这并不意味着它"简单"——恰恰说明配置中心的 API 设计更克制：只提供"发布/获取/监听/删除"四个核心能力，把复杂性封装在内部。

---

## 二、源码阅读清单 (按阅读顺序)

| 序号 | 源码文件 | 行数 | 必须阅读的内容 | 阅读目的 |
|---|---|---|---|---|
| 1 | `api/src/main/java/com/alibaba/nacos/api/config/ConfigService.java` | 163 | 全部 11 个方法声明 | 掌握配置中心 API 全貌 |
| 2 | `api/src/main/java/com/alibaba/nacos/api/config/listener/Listener.java` | 41 | getExecutor() + receiveConfigInfo() | 理解 v1 监听器模型 |
| 3 | `api/src/main/java/com/alibaba/nacos/api/config/listener/AbstractListener.java` | ~35 | 内部 Listener 封装 | 理解 Listener 的默认实现 |
| 4 | `api/src/main/java/com/alibaba/nacos/api/config/listener/AbstractSharedListener.java` | ~40 | 内容diff + fillContext | 理解 v1 diff机制 |
| 5 | `api/src/main/java/com/alibaba/nacos/api/config/ConfigChangeEvent.java` | 44 | data Map + getChangeItem | 理解 v2 结构化变更事件 |
| 6 | `api/src/main/java/com/alibaba/nacos/api/config/ConfigChangeItem.java` | 77 | key/oldValue/newValue/type | 理解 v2 逐key变更模型 |
| 7 | `api/src/main/java/com/alibaba/nacos/api/config/ConfigType.java` | 105 | 8种类型枚举 + getDefaultType(=TEXT) | 理解配置类型体系 |
| 8 | `api/src/main/java/com/alibaba/nacos/api/config/PropertyChangeType.java` | ~30 | ADDED/DELETED/MODIFIED | 理解变更类型枚举 |
| 9 | `api/src/main/java/com/alibaba/nacos/api/config/filter/IConfigFilter.java` | ~20 | doFilter + order | 理解过滤器链模式 |

---

## 三、分析要点详解

### 3.1 ConfigService 四大类操作

| 类别 | 方法 | 数量 | 返回值 | 幂等性 |
|------|------|------|--------|--------|
| 获取 | getConfig | 1 | String(内容) | ✅ 只读 |
| 获取+监听 | getConfigAndSignListener | 1 | String + Listener注册 | ✅ get是只读,add是幂等 |
| 发布 | publishConfig + publishConfigCas | 4 | boolean(是否成功) | ✅ md5校验 |
| 删除 | removeConfig | 1 | boolean | ✅ 已删除的再删返回false |
| 监听 | addListener + removeListener | 2 | void | add重复dataId去重 |
| 运维 | getServerStatus + shutDown | 2 | String/void | — |

### 3.2 dataId + group 的二元组定位模型 (与 Naming 的对比)

**Naming 的定位模型** (D-01 中已分析):
```
定位一个服务: groupName + serviceName
定位一个集群: groupName + serviceName + clusterName
定位一个实例: groupName + serviceName + clusterName + ip + port
```

**Config 的定位模型**:
```
定位一个配置: dataId + group  (二元组)
定位一个 tenant 下的配置: dataId + group + tenant  (三元组,tenant在Properties中配置,不在接口上)
```

为什么 Config 用 dataId 而不是 serviceName？
- dataId 的命名习惯: `application.properties`, `mysql.datasource.url` — 代表"一段配置数据"
- serviceName 的命名习惯: `user-service`, `order-service` — 代表"一个服务"
- ConfigService 不需要 clusterName 维度(配置是全局的,不需要按集群分组)
- ConfigService 不需要 subscribe 参数(监听是配置中心的核心能力,始终开启)

### 3.3 getConfig 的三种返回值语义

```java
String getConfig(String dataId, String group, long timeoutMs) throws NacosException;
```

| 场景 | 返回值 | 客户端行为 |
|------|--------|-----------|
| 配置存在 | 配置内容 String | 直接使用 |
| 配置不存在(从未发布过) | null | get接口不抛异常,返回null让调用方判断 |
| 服务端不可达(timeoutMs 超时) | 抛 NacosException | 调用方的异常处理 |
| 本地快照存在但服务端不可达 | 本地快照内容 | SDK内部容灾逻辑 |

**关键设计决策**：配置不存在返回 null 而不是抛异常。为什么？
- 启动时大量配置可能还未发布(如新建的服务)
- 调用方需要区分"服务端挂了"(异常)和"配置还没创建"(null)
- null 不中断启动流程,调用方可以用默认值

### 3.4 publishConfig 的幂等性保障

```java
boolean publishConfig(String dataId, String group, String content) throws NacosException;
```

**底层幂等机制**：服务端通过 MD5 校验判断内容是否真的变化
- 发布时服务端计算 content 的 MD5
- 与数据库中已有记录的 MD5 对比
- 相同 → 直接返回 true(幂等),不写 DB
- 不同 → 写入 DB,更新 MD5 → notify 订阅者

**publishConfigCas** — Compare-And-Swap 发布
```java
boolean publishConfigCas(String dataId, String group, String content, String casMd5);
```
- casMd5 = 你期望的"当前版本"的 MD5 值
- 服务端 Cassandra：当前MD5 == casMd5 才允许写入(否则被其他人修改过)
- 这是配置中心的乐观锁,防止并发覆盖

### 3.5 Listener 监听模型 — v1 vs v2 两代模型对比

**v1 Listener 模型** (Listener.java):
```java
public interface Listener {
    Executor getExecutor();           // 回调线程池(可自定义,不提供则主线程回调)
    void receiveConfigInfo(String configInfo);  // 收到完整配置内容
}
```

- `receiveConfigInfo(String)` 传入的是**完整配置内容**而不是变更diff
- 调用方需要自己对比新旧内容(AbstractSharedListener 提供了 diff 能力)
- `getExecutor()` 是关键设计——让调用方控制回调线程,避免阻塞推送线程

**v2 Listener 模型** (通过 ConfigChangeEvent):
```java
public class ConfigChangeEvent {
    Map<String, ConfigChangeItem> data;   // key → 变更项
    public ConfigChangeItem getChangeItem(String key);  // 获取单个key的变更
}

public class ConfigChangeItem {
    String key;       // 变更的key
    String oldValue;  // 旧值
    String newValue;  // 新值
    PropertyChangeType type;  // ADDED/DELETED/MODIFIED
}
```

- v2 模型**逐 key 返回变更**,调用方不需要自己做 diff
- 适用于 properties/yaml 格式的配置(结构化内容)
- Nacos 2.x 的 @NacosConfigListener 注解底层就是用 v2 模型

**两代模型的关系**：
- v1 (receiveConfigInfo) 是基础,兼容所有场景
- v2 (ConfigChangeEvent) 是增强,需要 SDK 在客户端做 content diff
- 调用方可以根据需求选择：简单场景用 v1,结构化配置用 v2

### 3.6 ConfigType — 配置内容格式分类

```
PROPERTIES / XML / JSON / TEXT(默认) / HTML / YAML / TOML / UNSET
```

- 默认类型 = TEXT (ConfigType.getDefaultType())
- ConfigType 影响服务端的**内容校验**和**diff 解析**
- 例如 JSON 类型的差异只对比有变化的 JSON key,而不是整个 JSON 字符串
- 支持 `isValidType()` 校验——服务端不接受未知类型

### 3.7 ConfigFilter — 可插拔过滤器链

```java
void addConfigFilter(IConfigFilter configFilter);  // v2.3.0+
```

过滤器链在 SDK 层执行,拦截 get/publish 操作：
```
ConfigService.getConfig()
  → ConfigFilterChain.doFilter(configRequest, configResponse)
    → filter1.doFilter() → filter2.doFilter() → ... → 实际网络请求
```

典型场景：
- 配置加密解密 filter(AES 加解密)
- 配置压缩解压 filter
- 配置内容校验 filter(格式/长度/非法字符)

---

## 四、设计哲学分析

### 4.1 为什么 ConfigService 这么简洁(163 行)？

对比 NamingService 的 602 行,ConfigService 只有 163 行。原因：

1. **配置无层次结构**：Naming 有 Service → Cluster → Instance 三层结构,Config 只有 dataId + group 一层
2. **不需要负载均衡**：配置内容直接返回,不像 Naming 需要 selectInstances/selectOneHealthyInstance
3. **不需要分页**：配置管理的数量远少于服务实例,getServicesOfServer 这类分页查询不需要
4. **监听模型更简单**：配置变更 push 全量内容或 diff,不需要 subscribe/unsubscribe 的多维重载

### 4.2 publishConfigCas — "先读后写"的 CAS 设计

- 类比：数据库的 `UPDATE WHERE version = ?` 乐观锁
- 使用场景：多个运维人员可能同时修改同一个配置
- 流程：getConfig(md5) → 修改 → publishConfigCas(casMd5=旧md5)
- 如果发布时 md5 已变化 → 返回 false → 提示冲突 → 重新 get → 合并 → 发布

### 4.3 getConfigAndSignListener — 原子性的"获取+监听"

```java
String getConfigAndSignListener(String dataId, String group, long timeoutMs, Listener listener);
```

这个方法的语义是"第一次获取配置的同时,注册监听器"。为什么需要这个组合方法？
- 如果分开调用 getConfig + addListener：
  - getConfig 返回后 → addListener 注册前 → 配置被修改
  - Listener 不会收到这次修改的推送 → 数据不一致
- 原子性保证：在 SDK 内部,get 和 add 是在同一个操作中完成的,中间不会有推送丢失

### 4.4 为什么 ConfigService 不改用 gRPC？

NamingService 在 2.x 中全面迁移到了 gRPC,但 ConfigService 的 addListener 依然走 HTTP 长轮询。原因：
- 配置监听的生命周期很长(可能持续数小时到数天)
- HTTP 长轮询对防火墙/代理更友好(不需要持久连接)
- gRPC 双向流推送在 2.x 中也支持,但作为增强,长轮询是 fallback
- ConfigTransportClient 同时支持 HTTP 和 gRPC 两种传输方式

---

## 五、反面假设分析 (5 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | ConfigService 也像 NamingService 一样有多重重载？ | 10 个 getConfig 重载 + 8 个 publishConfig 重载,接口膨胀。配置中心的使用场景更简单(只需要 dataId+group),过度重载反而增加学习成本。 | 保持简洁,每个操作只有 1-2 个方法 |
| 2 | getConfig 在配置不存在时抛异常而不是返回 null？ | 启动时大量配置缺失会导致大量异常,Spring Boot 启动直接失败。调用方无法区分"配置真的不存在"和"服务端挂了"。 | 返回 null(配置不存在) + 异常(服务端不可达) |
| 3 | Listener 回调在主线程执行而不是自定义线程池？ | 一个耗时回调会阻塞其他 Listener 的通知。配置变更是广播式的,一个慢回调影响所有订阅者。 | Listener.getExecutor() 允许自定义线程池 |
| 4 | ConfigService 不提供 publishConfigCas？ | 并发发布时后写覆盖先写,配置管理混乱。多个运维人员或自动化脚本同时修改同一配置时,最后一个写入者覆盖前面所有修改。 | CAS 乐观锁防止并发覆盖 |
| 5 | Listener 只接收变更 diff 而不接收全量内容？ | 调用方需要缓存上一次的完整内容才能做 diff,增加客户端复杂度。而且 callbacks 的顺序性无法保证,可能 diff 错位。 | v1 传全量内容,v2 额外提供结构化 diff |

---

## 六、新手最可能犯的 4 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 多次 addListener 同一个 dataId+group(不同 Listener 对象) | 推送时 receiveConfigInfo 被回调多次,重复处理 | 用同一个 Listener 实例,或 removeListener 清理旧 listener | SDK 内部对同一 Listener 引用做去重,但不同对象不去重 |
| 2 | receiveConfigInfo 中做耗时操作且不提供 Executor | 阻塞推送线程,所有该 dataId 的 Listener 都延迟收到通知 | 重写 Listener.getExecutor() 返回线程池 | Listener.getExecutor() 是接口契约的一部分,留给调用方控制 |
| 3 | 只调 addListener 不调 removeListener | Spring Bean 销毁后 Listener 仍然存活,内存泄漏 + 无效回调 | @PreDestroy 中 removeListener | 无自动对策,因为 Listener 生命周期无法被 Nacos 感知 |
| 4 | 依赖 getConfigAndSignListener 的原子性但 timeoutMs 设置过小 | 网络慢时超时返回 null,但 Listener 已注册(原子性),后续推送也可能延迟 | timeoutMs 设合理值(如 3000-5000ms),配合本地快照容灾 | 本地快照是最后防线,即使首��获取失败也有本地内容可用 |

### 6.1 可运行的错误实验

**实验：主线程回调阻塞导致配置推送延迟**

```java
// 错误做法：在主线程中做耗时操作
configService.addListener("app.properties", "DEFAULT_GROUP", new Listener() {
    @Override
    public Executor getExecutor() {
        return null;  // 使用主线程回调
    }
    @Override
    public void receiveConfigInfo(String configInfo) {
        // 模拟耗时操作：解析大配置文件
        try { Thread.sleep(5000); } catch (InterruptedException e) {}
        System.out.println("收到配置变更");
    }
});

// 此时再注册另一个配置的监听,它的回调会被第一个 Listener 阻塞
configService.addListener("db.properties", "DEFAULT_GROUP", new Listener() {
    @Override
    public void receiveConfigInfo(String configInfo) {
        System.out.println("这个回调被延迟了 5s 才触发");
    }
});
```

**实验：publishConfigCas 冲突重试**

```java
// 模拟两个进程并发修改同一配置
String dataId = "limit.properties";
String group = "DEFAULT_GROUP";

// Process A 读取
String content = configService.getConfig(dataId, group, 3000);
String oldMd5 = MD5Utils.md5Hex(content);  // 记录旧 MD5

// Process B 也读取并先发布了(模拟并发)
configService.publishConfig(dataId, group, "limit=200");  // B 先发布,MD5 变了

// Process A 尝试 CAS 发布
boolean success = configService.publishConfigCas(dataId, group, "limit=100", oldMd5);
// → false! MD5 已不匹配,A 的修改被拒绝
// A 需要：重新 getConfig → 看到 limit=200 → 决策是否覆盖 → 重新 CAS
```

---

## 七、面试 Q&A (5 题)

### Q1: Nacos 的配置中心 API 为什么这么简洁(163行)？和 NamingService(602行)的设计差异是什么？

**面试官意图**: 考察对配置中心和注册中心两种场景差异的理解

**参考答案要点**:
- 配置中心：无层次结构(dataId+group 一层),不需要负载均衡,不需要分页,监听模型单一
- 注册中心：Service→Cluster→Instance 三层结构,需要健康筛选+LB+分页,订阅有 subscribe=true/false 选择
- 根本原因：配置是静态的(key-value),服务实例是动态的(频繁上下线)
- 接口的复杂度反映了领域的复杂度

**追问**: "如果让你给 ConfigService 加功能,你会加什么?"
- 批量获取(一次 get 多个 dataId)
- 版本回滚接口 rollbackConfig(dataId, group, targetVersionId)
- 配置灰度发布接口 publishConfigGray(dataId, group, content, grayRule)

### Q2: publishConfig 和 publishConfigCas 的区别？什么时候用 CAS？

**面试官意图**: 考察对并发控制和乐观锁的理解

**参考答案要点**:
- publishConfig: 无条件覆盖,适用于单人运维场景
- publishConfigCas: CAS(Compare-And-Swap),需要传入预期旧 MD5,防止并发覆盖
- 实际流程：getConfig → 拿到 md5 → 修改 → publishConfigCas(casMd5=旧md5) → 如果 false(被人改过) → 重新 get → merge → 再 CAS
- 类比 Git merge conflict — 检测冲突 → 解决冲突 → 重新提交

**追问**: "如果发布频率很高(MD5 检查到冲突,重试 3 次都冲突)？"
- 说明多人并发修改同一配置,应该考虑分拆配置或引入锁机制
- 但注册中心的配置管理场景下,高并发冲突不常见(同一个配置不会同时多人修改)

### Q3: ConfigService 的 Listener 回调在哪个线程执行？高并发下会有什么问题？

**面试官意图**: 考察对异步回调线程模型和线程安全的理解

**参考答案要点**:
- 默认：在 SDK 内部的推送线程中执行(单线程)
- 自定义：Listener.getExecutor() 可以返回线程池
- 问题：如果 receiveConfigInfo 中有耗时操作(如刷新 Spring Context),会阻塞其他 Listener 的通知
- 解决：总是提供自定义 Executor

**追问**: "如果多个 Listener 监听同一个 dataId,回调顺序有保证吗？"
- 无保证,Listener 以注册顺序存储但 Executor 是线程池异步执行
- 需要依赖 receiveConfigInfo 中的版本号(通过 md5)来判断新旧

### Q4: ConfigService 的配置变更推送和 NamingService 的实例变更推送有什么本质区别？

**面试官意图**: 考察对两种推送模型的深度理解

**参考答案要点**:
- NamingService: 推送 ServiceInfo(全量实例列表) → 客户端用 Set 差分
- ConfigService: 推送变更通知("dataId 变了") → 客户端再发起 getConfig 拉取内容
- 差异原因：ServiceInfo 的数据量小(实例列表通常几十到几千),全量推送可控；配置内容可能很大(几十 KB),推送变更通知后由客户端按需拉取
- 推送模型的不同决定了延迟特性：Naming 推送延迟在毫秒~秒级,Config 推送延迟包含了客户端二次拉取的开销

### Q5: ConfigService 的监听模型和 NamingService 的订阅模型有什么本质区别？（联动 D-01）

**面试官意图**: 考察跨模块对比能力 — 能否看到 Naming 和 Config 虽然都叫"监听"，但底层是完全不同的机制

**参考答案要点**:
- Naming(ServiceInfo推送): 服务端主动推送全量 ServiceInfo → 客户端 Set 差分 → 触发 callback
- Config(长轮询通知): 服务端 hold 住 HTTP 连接 → 配置变更时返回"变了" → 客户端再 getConfig → 触发 receiveConfigInfo
- 差异根源：ServiceInfo 数据量小(几十到几千实例),全量推送可行；Config 内容可能很大(几十KB),推送变更通知+按需拉取
- Naming 的推送是"端到端"(直接给订阅者全部数据)，Config 的推送是"通知+拉取"(只告诉订阅者"变了"，自己来拉)

**追问**: "如果 Nacos 2.x 用 gRPC 双向流做配置推送,是不是就统一了？"
- 2.x 的 ConfigTransportClient 确实支持 gRPC ConfigChangeNotifier
- 但 HTTP 长轮询作为 fallback 仍然重要：防火墙友好、兼容性、无需持久连接
- 本质上,即使换成 gRPC,配置推送依然是"通知+拉取"模式(因为配置内容可能很大)

---

## 八、产出文档结构

```
D-02-ConfigService-API.md

1. ConfigService 接口全貌
   1.1 四大类操作(与 D-01 NamingService 的对比表)
   1.2 dataId+group 二元组定位 vs Naming 的三元组定位

2. getConfig — 三种返回语义深度分析
   2.1 存在/不存在/服务端不可达
   2.2 GET 端错误处理机制(异常 vs null)

3. publishConfig — 幂等性保障
   3.1 MD5 校验机制
   3.2 publishConfigCas CAS 乐观锁模型
   3.3 配置类型 ConfigType

4. Listener 监听模型深度分析
   4.1 v1 模型(Listener + receiveConfigInfo + Executor)
   4.2 v2 模型(ConfigChangeEvent + ConfigChangeItem)
   4.3 两代模型的对比与演进

5. getConfigAndSignListener 的原子性设计

6. ConfigFilter 可插拔过滤器链

7. 设计哲学分析
   7.1 为什么 ConfigService 这么简洁(163行)？
   7.2 CAS 乐观锁的设计思路
   7.3 为什么配置监听不用 gRPC 推送

8. 反面假设分析(5 组)

9. 新手最可能犯的 4 个错误 + 可运行的错误实验

10. 面试 Q&A(5 题,含面试官意图 + 追问,含 D-01 联动题)
```

---

## 九、与后续文档的依赖关系

```
D-02 (ConfigService API)  ← 当前
  |--- 什么是 getConfig/publishConfig/addListener → D-04 和 D-11~D-12 需要用到
  ↓
D-04 (Config SDK + ClientWorker) ← 使用 D-02 的接口定义,追踪实现
  ↓
D-11 (配置发布/查询/存储 + LongPolling) ← 使用 D-02 的模型概念(ConfigInfo/md5/ConfigType)
D-12 (AsyncNotifyService + 集群配置同步)
```

D-02 建立了配置中心的命名空间：ConfigService 定义了"配置中心能做什么"；Listener 定义了"如何感知变更"；ConfigType 定义了"配置是什么格式"。后续的 D-04(D-11/D-12) 分别在客户端和服务端讨论"如何实现"。
