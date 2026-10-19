# D8: 数据源机制 — NacosDataSource 动态规则

> 源码位置: `sentinel-extension/sentinel-datasource-nacos/` + `sentinel-extension/sentinel-datasource-extension/`
> 核心文件: `NacosDataSource.java`(~140行) / `AbstractDataSource.java`(56行) / `ReadableDataSource.java`(接口) / `Converter.java`(接口)
> 前置: D3（流控）理解 FlowRuleManager 如何加载规则；D4（熔断）理解 DegradeRuleManager

---

## 一、问题背景：规则从哪来？

D3/D4 验证时我们手动调 `FlowRuleManager.loadRules()` 硬编码规则。这有三个致命缺陷：

1. **无法动态调整**：改规则必须重新部署应用（重启 = 有损）
2. **多实例不一致**：每个实例独立编码，Nacos 实例 A 的 QPS=1000，实例 B 的 QPS=500——这在灰度发布时极其危险
3. **无审计能力**：谁改了规则、什么时间改的、改了什么——没有任何记录

Sentinel 的数据源机制解决这三个问题：规则存储在 Nacos Config（中心化 + 版本管理），Dashboard 修改 → Nacos 推送 → 所有实例实时生效，变更历史由 Nacos 配置管理自带。

```
Sentinel Dashboard [修改 FlowRule QPS=500→1000]
    → Nacos Config Server [存储 + 版本号自增]
        → push 到所有订阅此 dataId 的 Sentinel 客户端
            → NacosDataSource.receiveConfigInfo
                → Converter.convert(JSON) → List<FlowRule>
                    → SentinelProperty.updateValue
                        → FlowRuleManager.loadRules() [规则生效]
```

---

## 二、NacosDataSource：Listener 驱动的自动更新

**位置**: `sentinel-datasource-nacos/.../NacosDataSource.java:L42-140`

```java
public class NacosDataSource<T> extends AbstractDataSource<String, T> {
    private final Listener configListener;                             // L53

    public NacosDataSource(final String serverAddr, final String groupId, 
            final String dataId, Converter<String, T> parser) {        // L71
        super(parser);                                                  // converter 存入父类
        this.configListener = new Listener() {                          // L94
            @Override
            public Executor getExecutor() {
                return pool;                                            // 单线程池（见下）
            }

            @Override
            public void receiveConfigInfo(final String configInfo) {
                T newValue = NacosDataSource.this.parser.convert(configInfo);  // L104: JSON→规则
                getProperty().updateValue(newValue);                   // L106: ★ 传播链起点
            }
        };
        initNacosListener();                                           // L109
    }

    private void initNacosListener() {                                  // L125
        ConfigService configService = NacosFactory.createConfigService(properties);
        configService.addListener(dataId, groupId, configListener);    // L129: 注册
        T newValue = configService.getConfig(dataId, groupId, 3000);    // L131: 立即读
        getProperty().updateValue(newValue);                           // L134: 首次加载
    }
}
```

**为什么返回 `pool`（独立的单线程池）？** `pool` 的定义（L47-51）：

```java
/**
 * Single-thread pool. Once the thread pool is blocked, we throw up the old task.
 */
private final ExecutorService pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<Runnable>(1), new NamedThreadFactory("sentinel-nacos-ds-update", true),
    new ThreadPoolExecutor.DiscardOldestPolicy());
```

设计意图：**单线程**保证同一 dataId 的更新串行有序；队列容量 1 + `DiscardOldestPolicy`——池忙时**丢弃旧任务**而不是阻塞 Nacos 推送线程（源码注释："Once the thread pool is blocked, we throw up the old task"）。

---

## 三、Converter 接口：JSON → 规则对象的桥梁

**位置**: `sentinel-datasource-extension/.../Converter.java`

```java
public interface Converter<S, T> {
    T convert(S source) throws Exception;
}
```

用户需要实现 `Converter<String, List<FlowRule>>` 来解析 JSON：

```java
// Nacos 上存储的 JSON 内容示例
// [{ "resource": "sentinelApi", "grade": 1, "count": 100.0 }]

Converter<String, List<FlowRule>> flowRuleConverter = source -> {
    List<FlowRule> rules = JSON.parseArray(source, FlowRule.class);
    return rules;
};

// 注册到 NacosDataSource
ReadableDataSource<String, List<FlowRule>> dataSource = 
    new NacosDataSource<>("127.0.0.1:8848", "DEFAULT_GROUP", 
        "sentinel-rules", flowRuleConverter);
FlowRuleManager.register2Property(dataSource.getProperty());
```

**`register2Property` 内部做的事**：`dataSource.getProperty().addListener(propertyListener)`→ 把 FlowRuleManager 自己注册为 SentinelProperty 的监听者 → 每次 `updateValue` 触发 `propertyListener.configUpdate` → `FlowRuleManager.loadRules()`。

---

## 四、SentinelProperty 传播链：最终一公里

```
NacosDataSource.receiveConfigInfo(configInfo)
    → parser.convert(configInfo)                    [JSON → List<FlowRule>]
        → getProperty().updateValue(newValue)        [SentinelProperty 写]
            → 遍历所有 PropertyListener
                → FlowRuleManager 的 listener.configUpdate(newValue)
                    → FlowRuleManager.loadRules()    [正式生效]
```

**SentinelProperty 内部实现**：`ConcurrentHashMap<PropertyListener, Boolean>` 存储所有监听者 + `synchronized` 保证线程安全的遍历和更新。注意是 **同步遍历**——如果某个 PropertyListener 的 `configUpdate` 执行耗时（如调外部 API 验证规则），会阻塞其他监听者的更新。

---

## 五、发散：Sentinel DataSource vs Spring Cloud Config vs Apollo

| 维度 | Sentinel NacosDataSource | Spring Cloud Config | Apollo |
|------|------|------|------|
| 数据模型 | 单个 dataId（JSON Array） | 多文件（application.yml） | namespace + cluster + 多配置项 |
| 推送机制 | Nacos Listener（服务端推送） | Bus + RabbitMQ/Kafka（广播） | HTTP Long Polling（客户端轮询） |
| Converter | 用户自写（`Function<S,T>`） | 内置（YAML/Properties 自动绑定） | 内置 |
| 适用场景 | 流控/熔断规则（Sentinel 专属） | 应用级别的业务配置 | 通用配置中心 |

**Sentinel 为什么不用 Spring Cloud Config？** Config 的 `@RefreshScope` 需要重新创建 Bean——重新创建 `FlowRuleManager` 会丢失所有运行时统计数据（QPS 计数器归零）。Sentinel 的 `loadRules()` 只更新规则对象，不影响数据组件，是真正的热更新。

---

## 六、面试考点

**Q1: Sentinel 规则如何持久化？NacosDataSource 如何监听变更？**

`NacosDataSource` 通过 `configService.addListener(dataId, groupId, listener)` 注册 Nacos 监听器。Dashboard 修改规则 → Nacos Config 推送变更 → `receiveConfigInfo` 回调 → `Converter.convert(JSON)` → `SentinelProperty.updateValue` → 遍历 `PropertyListener` → `FlowRuleManager.loadRules()`。

**追问**: 如果 Nacos 宕机了，Sentinel 规则还在吗？

Sentinel 规则**不依赖 Nacos 的持续可用性**——`NacosDataSource` 只在初始化时读一次 + 注册监听。如果 Nacos 宕机：已加载的规则继续生效（在内存中），新规则无法推送，重启应用时规则丢失（因为读不到 Nacos）。**这道追问是面试官的陷阱**——考察你是否混淆了"规则存储"和"规则执行"。

**Q2: 如何从文件数据源切换到 Nacos 数据源？Converter 的作用？**

初始化时创建不同的 `ReadableDataSource` 即可——`FileRefreshableDataSource` 读本地文件，`NacosDataSource` 读 Nacos。`Converter` 负责将原始字符串（JSON）转换为 Sentinel 规则对象——这是解耦点：数据源只负责"读字符串"，Converter 负责"转换类型"。切换数据源不需要修改任何规则处理逻辑。

---

## 七、生产陷阱

1. **Converter 异常静默吞掉**：如果 Nacos 上的 JSON 格式错误，`converter.convert` 抛异常 → `receiveConfigInfo` 中无 try-catch → 规则**静默失效**，继续使用上一次的规则——没有任何日志告警。应加 `try-catch` + 告警发送（如钉钉/Slack）。

2. **Nacos 宕机 + 应用重启 = 规则丢失**：规则存储在 Nacos 上，应用启动时从 Nacos 加载。如果 Nacos 宕机 + 应用重启 → `configService.getConfig` 失败 → 规则为空 → **所有请求不受任何流控/熔断保护**。解决：初始化时加 fallback——Nacos 读不到时加载本地快照文件（`FileRefreshableDataSource` 兜底）。

3. **`receiveConfigInfo` 中的耗时操作阻塞推送线程**：如果 `converter.convert` 内部调了外部服务（如规则合法性校验 API），Nacos 的推送线程被阻塞 → 后续所有 dataId 的推送都被延迟。`receiveConfigInfo` 应该只做"读 + 更新属性"的轻量操作，耗时校验应在 `PropertyListener.configUpdate` 中用异步线程执行。

4. **多实例规则不一致窗口**：Nacos 推送不是瞬间同步——A 实例先收到（QPS=1000），B 实例后收到（还是旧规则 QPS=500）。这中间存在**不一致窗口**（通常 < 100ms，但网络抖动可能更长）。应监控各实例的 `FlowRuleManager.getRules()` 版本号一致性。
