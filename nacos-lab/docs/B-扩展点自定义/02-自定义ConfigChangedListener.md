# B2: 自定义 ConfigChangedListener — 替代 @RefreshScope 的精确刷新

---

## 目录

1. [问题：@RefreshScope 的暴力刷新](#一问题refreshscope-的暴力刷新)
2. [框架源码：@RefreshScope 的销毁-重建机制](#二框架源码refreshscope-的销毁-重建机制)
3. [框架源码：NacosContextRefresher 的全局监听](#三框架源码nacoscontextrefresher-的全局监听)
4. [B2 的实现：独立 Listener，绕过 RefreshScope](#四b2-的实现独立-listener绕过-refreshscope)
5. [验证：两个 Listener 共存](#五验证两个-listener-共存)
6. [生产场景代码详解](#六生产场景代码详解)
7. [反模式](#七反模式)

---


## 〇、设计历史：为什么需要 @RefreshScope

### 0.1 2015-2016：配置热更新的原始需求

Spring Cloud 诞生的 2015 年，微服务改造的核心痛点之一就是配置热更新。当时有两类方案：

**方案 A：自己维护"配置变更 → 字段更新"映射表**
每个配置项配一个 `Map<String, Consumer<String>>`——配置变了就回调对应的 Consumer 修改对应的字段。问题是：配置项多了（50+），这个映射表本身就是巨大的维护负担，而且字段被改了之后，基于这个字段计算的其他值（如缓存 key、线程池大小计算）不会自动更新。

**方案 B：直接修改 @Value 字段**
`@Value` 是 Bean 创建时一次性注入的。字段变成普通 String/int 后，不会自动从 Environment 重新获取。即使用反射改了字段值，已持有了旧值的其他 Bean、缓存、计算结果都不会看到新值。

### 0.2 Spencer Gibb 的 @RefreshScope 方案（2016）

Spencer Gibb 提出用 Spring 的 `Scope` 机制解决：**销毁旧 Bean，重建新 Bean**——新 Bean 在创建时自然绑定最新的配置值。

为什么 Scope 是正确的抽象？
- Scope 天然处理"同一个 Bean 名 → 在不同时间返回不同实例"
- BeanFactory 层面的拦截——任何注入的引用都可以在底层被替换
- 销毁时自动触发 @PreDestroy（释放旧资源），创建时自动触发 @PostConstruct（初始化新资源）

但代价也随之而来：2016 年的 Bean 很简单（DTO、VO），如今的 Bean 持有连接池、线程池、缓存——"全量销毁重建"不再是零成本操作。

## 一、问题：@RefreshScope 的暴力刷新

### 1.1 场景

你的应用有 20 个 `@RefreshScope` Bean，其中一个 `ConnectionPoolConfig` 持有 HikariCP 连接池。运维在 Nacos 里把功能开关 `feature.new-algorithm=false` 改成 `true`。

结果：不仅功能开关变了——连接池也被销毁重建了。所有活跃的数据库连接被强制关闭，正在处理的事务被打断，等待队列里的请求全部超时。

### 1.2 为什么会这样

因为 `@RefreshScope` 的语义是"这个 Bean 的任何一个 `@Value` 属性变了 → 整个 Bean 销毁重建"。它不区分"哪个属性变了"——只要触发 RefreshScope 刷新，所有 `@RefreshScope` Bean 都被重建。

### 1.3 生产环境的真实代价

| Bean 类型 | 重建代价 |
|---|---|
| 简单 DTO / VO | 几乎为零 |
| HikariCP DataSource | 全部连接断开 → 重建连接池（1-3 秒） |
| 线程池 | 所有排队任务丢失（中断 RejectedExecutionHandler） |
| Caffeine Cache | 全部缓存丢失（缓存命中率暴跌） |
| Redisson Client | Redis 连接断开 → 重建（延迟可达数百毫秒） |
| Spring `@Scheduled` 方法的 Bean | 调度任务中断（正在执行的线程被取消） |

### 1.4 问题的根源

`@RefreshScope` 是 Spring Cloud 2016 年的设计，当时微服务的配置项很少、Bean 很简单。现在一个 Bean 可能持有连接池、线程池、缓存、RPC 客户端——不能被轻易销毁重建。

**B2 要证明的是**：不销毁 Bean，只在配置变更时执行精确的、目标明确的操作。

---


### 1.4.1 完整链路前置知识：Nacos SDK 的长轮询机制

在讲 RefreshScope 之前，必须理解：配置变了之后，是谁、怎么通知到我们的应用的。

> **版本注记**: 以下描述的是 **1.x HTTP 长轮询**机制（背景知识）。**2.x 已改为 gRPC 推送 + 5s 兜底**（`ConfigRpcTransportClient` + `listenExecutebell`），服务端 `LongPollingService` 仅为兼容 1.x 客户端保留；2.x 详情见 D-04/D-11。

Nacos 1.x SDK 的 `ClientWorker` 类（约 400 行）是配置拉取的核心。它运行一个 `LongPollingRunnable`（守护线程），每 30s 执行一次长轮询：

```
ClientWorker.checkConfigInfo(server, dataId, group)
  ↓
LongPollingRunnable.run()
  ↓ HTTP POST /nacos/v1/cs/configs/listener
  ↓ Body: "nacos-lab.yamlDEFAULT_GROUP{本地MD5}"
  ↓
Nacos Server ConfigServletLongPolling.doGet()
  ↓ 如果是长轮询请求 → 挂起 29.5s
  │  ├── 配置变了 → 立刻返回 "dataId^group^新MD5"
  │  └── 29.5s 到了 → 返回空（MD5 未变）
  ↓
ClientWorker 收到响应
  ├── 返回空（304 逻辑） → 不更新，发起下一次长轮询
  │   └── 带宽：< 100 bytes
  └── 返回新 MD5 → 发起 getConfig() 拉取全量
      └── CacheData.setContent(newContent) → 通知所有监听器
```

**为什么长轮询？**
- 短轮询（客户端每 5s 发请求）：100 个客户端 × 5s 间隔 = 每秒 20 个请求（多数是浪费）
- 长轮询（Server 挂起）：同一个条件 —— 0 个请求（直到配置真的变了。对 Server 是接近零开销）

**关键细节**：上面流程中提到的 `CacheData.setContent(newContent)` 就是 B2 后续章节的重点——它负责通知所有在该 dataId 上注册的监听器。此时 NacosContextRefresher 的监听器也会被通知，进而触发 RefreshScope 刷新（这是框架默认路径）。B2 的自定义监听器就是在同一个 CacheData 上注册的第二个监听器——和框架的监听器同时收到通知，但走不同的处理路径。

## 二、框架源码：@RefreshScope 的销毁-重建机制

先读懂框架是怎么做的，才知道怎么替换它。


### 1.4.2 addListener 背后的 SDK 数据结构

`configService.addListener(dataId, group, listener)` 这一行最终进入 `ClientWorker.addTenantListeners()`：

```java
// ClientWorker.java — 简化版
public void addTenantListeners(String dataId, String group, List<? extends Listener> listeners) {
    String key = group + "@" + dataId;
    CacheData cache = cacheMap.get(key);
    if (cache == null) {
        cache = new CacheData(key);
        cacheMap.put(key, cache);
    }
    // ⚡ 关键：追加 Listener（不是覆盖）
    for (Listener listener : listeners) {
        cache.addListener(listener);
    }
}
```

**CacheData 的数据结构**：
```java
public class CacheData {
    private final String key;                             // "DEFAULT_GROUP@nacos-lab.yaml"
    private volatile String content;                      // 配置内容
    private volatile String md5;                          // 当前内容的 MD5
    private final CopyOnWriteArrayList<ManagerListenerWrap> listeners;  // ← 监听器列表！
}

// ManagerListenerWrap 包装了每个 Listener + 它的 lastCallMd5
class ManagerListenerWrap {
    final Listener listener;
    volatile String lastCallMd5;  // ← 上次通知时的 MD5，防止重复通知
}
```

**为什么用 CopyOnWriteArrayList？**
- 写操作极少（只在应用启动时 addListener），读操作频繁（每次配置变更都遍历）
- CopyOnWriteArrayList 的读不需要锁——读的是快照
- 写操作会复制整个数组——但配置监听器通常只有 1-2 个，复制成本忽略不计

**这就是为什么 cnt=2 会发生**——NacosContextRefresher 调用一次 `addListener()` → 缓存中存在 `ManagerListenerWrap(cnt=1)` → CustomConfigChangeHandler 再调一次 → `CacheData.listeners` 追加一个新的 `ManagerListenerWrap(cnt=2)`。

### 1.4.3 AbstractSharedListener 的去重机制

```java
// AbstractSharedListener.java
public abstract class AbstractSharedListener implements Listener {
    private volatile String lastContent;  // 上次收到

的配置内容
    
    public void innerReceive(String dataId, String group, String configInfo) {
        // ⚡ 去重：内容没变 → 不触发
        if (configInfo == null || configInfo.equals(lastContent)) {
            return;
        }
        lastContent = configInfo;
        receiveConfigInfo(configInfo);  // 调用子类的具体实现
    }
}
```

**直接实现 Listener vs 继承 AbstractSharedListener**：
- 直接 `Listener`：每次 SDK 通知都调 `receiveConfigInfo()`（可能被重复推送）
- 继承 `AbstractSharedListener`：`innerReceive()` 帮你过滤相同内容的重复通知

**B2 的警示**：`CustomConfigChangeHandler` 用的是直接 `Listener`——这意味着如果 Nacos SDK 重复推送相同内容，`receiveConfigInfo()` 会被重复调用。生产代码应该在回调中加防御性检查（对比上次收到内容的 MD5）。

### 2.1 @RefreshScope 注解 —— 一个特殊的 Spring Scope

```java
// RefreshScope.java (spring-cloud-context 177 行)
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Scope("refresh")          // ← 关键：声明这是一个自定义 Scope
@Documented
public @interface RefreshScope {
    @AliasFor(annotation = Scope.class)
    ScopedProxyMode proxyMode() default ScopedProxyMode.TARGET_CLASS;
    // 默认使用 CGLIB 代理（TARGET_CLASS）
    // → 每个 @RefreshScope Bean 实际创建的是一个代理对象
    // → 代理对象不变，但代理背后的 target 可以被销毁和重建
}
```

**关键设计**：`proxyMode = TARGET_CLASS`（CGLIB 代理）

这意味着：
- 你注入的 `@Autowired DemoProperties demoProperties` —— 拿到的永远是同一个代理对象（内存地址不变）
- 代理对象内部的 target 可以被随意替换
- 所以 Spring 可以销毁旧 target、创建新 target——其他 Bean 不知道，它们还拿着同一个代理引用

### 2.2 RefreshScope.refreshAll() —— 全量销毁

```java
// RefreshScope.java:167-170
@ManagedOperation(description = "Dispose of the current instance of all beans "
        + "in this scope and force a refresh on next method execution.")
public void refreshAll() {
    super.destroy();  // ← GenericScope.destroy()
    this.context.publishEvent(new RefreshScopeRefreshedEvent());
}
```

### 2.3 GenericScope.destroy() —— 每个 Bean 逐一遍历并清理

```java
// GenericScope.java:126-148
@Override
public void destroy() {
    List<Throwable> errors = new ArrayList<>();
    Collection<BeanLifecycleWrapper> wrappers = this.cache.clear();
    // ↑ 先清除缓存，拿到所有曾经创建过的 BeanLifecycleWrapper

    for (BeanLifecycleWrapper wrapper : wrappers) {
        try {
            Lock lock = this.locks.get(wrapper.getName()).writeLock();
            lock.lock();
            try {
                wrapper.destroy();
                // ↑ 调用 BeanLifecycleWrapper.destroy()
                // → 触发 Bean 的 @PreDestroy 回调
                // → 如果 Bean 实现了 DisposableBean → destroy()
                // → 如果 Bean 有 destroy-method → 调用
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            errors.add(e);
        }
    }
}
```

**关键**：`this.cache.clear()` —— 一次性清空全部缓存，然后逐一调用 `wrapper.destroy()`。这个 destroy 行为对于持有资源的 Bean 是毁灭性的：

```
HikariCP DataSourceBean:
  @PreDestroy close() → HikariPool.shutdown() → 关闭所有连接
    → 业务线程拿到 BrokenConnection → SQLException
```

### 2.4 RefreshScope.refresh(String name) —— 单个 Bean 销毁

```java
// RefreshScope.java:151-163
@ManagedOperation
public boolean refresh(String name) {
    if (!ScopedProxyUtils.isScopedTarget(name)) {
        name = ScopedProxyUtils.getTargetBeanName(name);
        // 自动把 "demoProperties" → "scopedTarget.demoProperties"
        // 因为 Spring 内部存储是用 scopedTarget 前缀
    }
    if (super.destroy(name)) {
        // GenericScope.destroy(String) → cache.remove(name) → wrapper.destroy()
        this.context.publishEvent(new RefreshScopeRefreshedEvent(name));
        return true;
    }
    return false;
}
```

**重要**：Spring Cloud 提供了 `refresh(String name)` 方法——你可以只刷新一个 Bean，不刷新全部。但 NacosContextRefresher 调的是 `refreshAll()` 而不是 `refresh(String)`。

### 2.5 ContextRefresher.refresh() —— 触发全量 RefreshScope 刷新

```java
// ContextRefresher.java (spring-cloud-context)
public synchronized Set<String> refresh() {
    Set<String> keys = refreshEnvironment();
    // ↑ 更新 Environment 中的 PropertySource

    this.scope.refreshAll();
    // ↑ 全量刷新所有 @RefreshScope Bean

    return keys;
}
```

### 2.6 完整调用链（配置变更 → Bean 重建）

```
Nacos 控制台 → 修改 nacos-lab.yaml
    ↓
Nacos SDK ClientWorker 长轮询检测到 MD5 变化
    ↓
CacheData.setContent(newContent)
    ↓ 通知所有注册的 Listener
    │
    ├── [路径 1：NacosContextRefresher 的 Listener]
    │   ↓ AbstractSharedListener.innerReceive(dataId, group, configInfo)
    │   ↓ applicationContext.publishEvent(new RefreshEvent(...))
    │   ↓
    │   ContextRefresher.refresh()
    │   ↓ refreshEnvironment() → 更新 Environment 的 PropertySource
    │   ↓ RefreshScope.refreshAll()
    │   ↓ GenericScope.destroy()
    │   ↓ for (BeanLifecycleWrapper : cache) { wrapper.destroy(); }
    │   ↓ 每个 @RefreshScope Bean → @PreDestroy → 释放所有资源
    │   ↓ 下次访问 → 重新创建 → @PostConstruct → 重新初始化
    │
    └── [路径 2：CustomConfigChangeHandler 的 Listener（B2）]
        ↓ receiveConfigInfo(newContent)
        ↓ 解析变更，只更新受影响的业务逻辑
        ↓ 不影响任何 Bean 的生命周期
```

---

## 三、框架源码：NacosContextRefresher 的全局监听

```java
// NacosContextRefresher.java (171 行, spring-cloud-starter-alibaba-nacos-config)
public class NacosContextRefresher
        implements ApplicationListener<ApplicationReadyEvent>, ApplicationContextAware {

    private final ConfigService configService; // NacosConfigManager.getConfigService() → 全局单例
    private ApplicationContext applicationContext;
    private Map<String, Listener> listenerMap = new ConcurrentHashMap<>(16);

    // 1. 构造时拿到 ConfigService
    public NacosContextRefresher(NacosConfigManager nacosConfigManager, ...) {
        this.configService = nacosConfigManager.getConfigService();
        // ↑ ConfigService 是全局单例（NacosConfigManager 中 static field）
    }

    // 2. ApplicationReadyEvent → 注册所有配置的 Listener
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (this.ready.compareAndSet(false, true)) {
            this.registerNacosListenersForApplications();
        }
    }

    // 3. 遍历所有 NacosPropertySource
    private void registerNacosListenersForApplications() {
        if (isRefreshEnabled()) {
            for (NacosPropertySource propertySource : NacosPropertySourceRepository.getAll()) {
                if (!propertySource.isRefreshable()) continue;
                String dataId = propertySource.getDataId();
                registerNacosListener(propertySource.getGroup(), dataId);
            }
        }
    }

    // 4. 每个 dataId 注册一个 AbstractSharedListener
    private void registerNacosListener(final String groupKey, final String dataKey) {
        String key = NacosPropertySourceRepository.getMapKey(dataKey, groupKey);
        Listener listener = listenerMap.computeIfAbsent(key, lst -> new AbstractSharedListener() {
            @Override
            public void innerReceive(String dataId, String group, String configInfo) {
                refreshCountIncrement();
                nacosRefreshHistory.addRefreshRecord(dataId, group, configInfo);
                // ⚡ 关键：发布 RefreshEvent → 触发 ContextRefresher.refresh()
                applicationContext.publishEvent(
                    new RefreshEvent(this, null, "Refresh Nacos config"));
            }
        });
        try {
            configService.addListener(dataKey, groupKey, listener);
            // ↑ 注册到 Nacos SDK 的 ClientWorker
        } catch (NacosException e) {
            log.warn("register fail for nacos listener...");
        }
    }
}
```

**关键设计缺陷**：NacosContextRefresher 为**每个** dataId 注册了**同一个逻辑**的 Listener——无论哪个配置变了，都发 RefreshEvent → 全量刷新。它不区分"哪个配置变了"——只知道"有配置变了，全部刷新"。

---


### 3.5 ContextRefresher.refreshEnvironment() — 不是只有 RefreshScope

当 `NacosContextRefresher` 发布 `RefreshEvent` 后，`RefreshEventListener` 捕获该事件并调用 `ContextRefresher.refresh()`——这个方法做了**两件独立的事**：

```java
// ContextRefresher.java (spring-cloud-context, 约 80 行)
public synchronized Set<String> refresh() {
    // 第一件事：更新 Environment
    Set<String> keys = refreshEnvironment();
    // ↑ 遍历所有 PropertySourceLocator → locate() 重新拉取配置
    // → 替换旧的 PropertySource → Environment 中的值更新

    // 第二件事：刷新 RefreshScope
    this.scope.refreshAll();
    // ↑ 销毁所有 @RefreshScope Bean → 下次访问时重建
    // → 新 Bean 从已更新的 Environment 中读取新值

    return keys;
}
```

**为什么 refreshEnvironment() 必须先执行？**
因为 RefreshScope 重建 Bean 时，Bean 的 `@Value` 和 `@ConfigurationProperties` 都是从 Environment 读取值的。如果 Environment 还没更新，新建的 Bean 会绑定旧配置——重建白做了。

### 3.6 refreshEnvironment() 对 @ConfigurationProperties 的直接影响

```java
// refreshEnvironment() 内部做了什么
public synchronized Set<String> refreshEnvironment() {
    Map<String, Object> before = extract(this.context.getEnvironment()
        .getPropertySources());

    // 重新拉取所有配置源
    addConfigFilesToEnvironment();

    // 替换旧的 PropertySource
    Set<String> keys = changes(before, extract(this.context.getEnvironment()
        .getPropertySources())).keySet();

    return keys;
}
```

关键：`addConfigFilesToEnvironment()` 会重新实例化 `NacosPropertySource`（因为 Nacos 配置变了），然后用新的 `PropertySource` 替换 `MutablePropertySources` 中旧的项。

**对 @ConfigurationProperties 的影响**：
- `@ConfigurationProperties` 的绑定发生在每次 getter 调用时，通过 `ConfigurationPropertiesBindingPostProcessor` 从 Environment 实时取值
- `refreshEnvironment()` 更新了 PropertySource → 下次 `configProperties.getXxx()` 自然读到新值
- **不需要 Bean 重建**——这就是为什么 `@ConfigurationProperties` 可以不依赖 `@RefreshScope`

**对 @Value 的影响**：
- `@Value` 是在 Bean 创建时通过 `AutowiredAnnotationBeanPostProcessor` 一次性注入的
- 注入后字段是普通 String/int，不会自动从 Environment 重新获取
- **必须重建 Bean 才能拿到新值**——这就是为什么 `@Value` 需要 `@RefreshScope`

## 四、B2 的实现：独立 Listener，绕过 RefreshScope

### 4.1 核心思路

- 在 `@PostConstruct` 中，通过 NacosConfigManager 拿到全局 ConfigService
- 用 `configService.addListener()` 注册一个**额外的** Listener
- 这个 Listener 和 NacosContextRefresher 的 Listener **并存**（Nacos SDK 支持同一 dataId 注册多个 Listener）
- 当配置变更时，两条路径**同时触发**
- RefreshScope 路径：全量刷新所有 @RefreshScope Bean（不可关）
- 自定义 Listener 路径：只做精确的、目标明确的操作

### 4.2 CustomConfigChangeHandler 源码逐行注解

```java
@Component
public class CustomConfigChangeHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomConfigChangeHandler.class);
    private static final String DATA_ID = "nacos-lab.yaml";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired
    private NacosConfigManager nacosConfigManager;

    private final AtomicInteger changeCount = new AtomicInteger(0);
    private final AtomicLong lastChangeTime = new AtomicLong(0);
    private volatile String lastConfigContent = null;

    @PostConstruct
    public void init() {
        // 1. 从 NacosConfigManager 拿到 ConfigService
        //    NacosConfigManager.getConfigService() 返回的是 static 单例
        //    NacosContextRefresher 用的也是这个单例
        //    → 我们在同一个 ConfigService 上注册第二个 Listener
        try {
            nacosConfigManager.getConfigService().addListener(DATA_ID, GROUP,
                    new Listener() {

                        @Override
                        public Executor getExecutor() {
                            // null → 使用 Nacos SDK 的默认线程池
                            // 生产建议：传入自定义线程池，避免配置变更处理
                            // 阻塞 Nacos SDK 的长轮询线程
                            return null;
                        }

                        @Override
                        public void receiveConfigInfo(String configInfo) {
                            // 2. 收到完整的新配置内容（不是变更集，是全量文本）
                            int count = changeCount.incrementAndGet();
                            lastChangeTime.set(System.currentTimeMillis());
                            lastConfigContent = configInfo;

                            log.info("[CustomListener] 第{}次变更: {}", count,
                                    configInfo.replace("\n", " | "));

                            // 3. ⚡ 精准处理逻辑（扩展点）
                            // 生产代码：
                            //   Map<String, String> diff = diffConfig(oldConfig, configInfo);
                            //   if (diff.containsKey("pool.maxSize")) {
                            //       hikariDataSource.setMaximumPoolSize(parseInt(diff.get("pool.maxSize")));
                            //   }
                            //   if (diff.containsKey("feature.newAlgorithm")) {
                            //       featureFlagManager.setFlag("newAlgorithm", parseBool(diff.get("feature.newAlgorithm")));
                            //   }
                            //   其他配置变更 → 不处理（不触发任何 Bean 重建）
                        }
                    });
            log.info("[CustomListener] 已注册: dataId={}, group={}", DATA_ID, GROUP);
        } catch (Exception e) {
            log.error("[CustomListener] 注册失败: {}", e.getMessage(), e);
        }
    }
}
```

### 4.3 为什么不需要 @RefreshScope

这个 Handler 没有 `@RefreshScope` 注解——它不是 Scope Bean，是普通 Singleton Bean。

关键区别：
- `@RefreshScope` Bean：Spring 管理其生命周期，配置变了就销毁重建
- 自定义 Listener：我们自己管理"什么变了 → 怎么处理"，Bean 生命周期不变

---


### 3.7 CGLIB 代理如何实现 target 替换

`@RefreshScope` 的 `proxyMode = TARGET_CLASS` 意味着 Spring 为每个 `@RefreshScope` Bean 创建一个 CGLIB 代理：

```java
// Bean 创建时，ScopedProxyFactoryBean 介入：
ScopedProxyFactoryBean → getObject()
  → new CglibAopProxy(advisedSupport) → getProxy()

// 代理的 MethodInterceptor：
@Override
public Object intercept(Object proxy, Method method, Object[] args, MethodProxy mp) {
    // 1. 从 RefreshScope 获取当前 target
    Object target = RefreshScope.get(beanName, beanFactory);
    // ↑ GenericScope.get() → BeanLifecycleWrapper.getBean()
    // ↑ 如果 wrapper.bean == null（被销毁了）→ create() 创建新实例
    
    // 2. 调用 target 的对应方法
    return method.invoke(target, args);
}
```

**核心机制**：其他 Bean 注入的是 `@Autowired DemoProperties demoProperties`——Spring 容器注入的是**代理对象**的引用。代理对象的 Java 引用不变，但代理内部的 target 可以被随意替换。

```
时间线：
T0: Bean A 被创建 → 注入 demoProperties（代理引用 0x1234）
T1: Nacos 配置变更 → RefreshScope.refreshAll()
T2: GenericScope.destroy() → cache.remove("demoProperties")
    → BeanLifecycleWrapper.destroy() → target 被 @PreDestroy 清理
T3: Bean A 下次调 demoProperties.getMessage()
    → 代理的 MethodInterceptor.intercept()
    → GenericScope.get("demoProperties")
    → cache 中没有 → create() → 新 target 被创建
    → 新 target 的 @Value/@ConfigurationProperties 从已更新的 Environment 绑定
    → 返回新值
```

**这个机制的代价**：@RefreshScope Bean 每次重建时，Spring 都要重新创建 Bean、重新注入依赖、重新调用 `@PostConstruct`。如果 Bean 的构造函数做重量级操作（创建连接池、初始化线程池、预热缓存），每次重建都是一次"微启动"。

## 五、验证：两个 Listener 共存

### 5.1 注册确认

启动日志：
```
[add-listener] ok, dataId=nacos-lab.yaml, group=DEFAULT_GROUP, cnt=1
    ↑ 第一个 Listener（NacosContextRefresher）
[CustomListener] 已注册: dataId=nacos-lab.yaml, group=DEFAULT_GROUP
[add-listener] ok, dataId=nacos-lab.yaml, group=DEFAULT_GROUP, cnt=2
    ↑ 第二个 Listener（CustomConfigChangeHandler）
```

`cnt=2` 证明两个 Listener 同时注册在同一个 dataId 上。

其他配置只有 `cnt=1`：
```
[add-listener] ok, dataId=shared-redis.yaml, group=REDIS_GROUP, cnt=1
    ↑ 只有 NacosContextRefresher
```

### 5.2 变更验证

```
# 1. 初始状态
curl http://localhost:28001/config/custom-listener-status
# → changeCount: 0

# 2. Nacos 里修改配置
curl -X POST "http://21.130.247.89:18848/nacos/v1/cs/configs" -d "content=..."

# 3. 等 3 秒后再次查询
curl http://localhost:28001/config/custom-listener-status
# → changeCount: 1
# → lastConfigContent: "demo.message: b2-listener-test\n..."（完整新配置）
# → lastChangeTime: "2026-07-23T08:47:49.638Z"

# 4. 确认 RefreshScope 路径也触发了
curl http://localhost:28001/config/refresh-compare
# → properties_message: "b2-listener-test"（已更新）
# → value_welcome: "B2 自定义Listener验证"（已更新）
```

---

## 六、生产场景代码详解

### 6.1 场景一：连接池热调整

```java
@Component
public class ConnectionPoolManager {

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private NacosConfigManager nacosConfigManager;

    private volatile int currentMaxPoolSize = 10;

    @PostConstruct
    public void init() {
        nacosConfigManager.getConfigService().addListener(
            "datasource.yaml", "DATASOURCE_GROUP",
            new Listener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    // 解析 YAML
                    int newSize = parseMaxPoolSize(configInfo);
                    if (newSize != currentMaxPoolSize) {
                        log.info("[PoolManager] Resizing: {} → {}", currentMaxPoolSize, newSize);
                        dataSource.setMaximumPoolSize(newSize);
                        // ⚡ HikariCP 支持热调整 maximumPoolSize
                        // 现有连接不关闭，只是上限变了
                        // 如果 newSize < currentIdle，空闲连接会被逐渐回收
                        currentMaxPoolSize = newSize;
                    }
                }
                @Override public Executor getExecutor() { return null; }
            });
    }
}
```

**为什么不直接 @RefreshScope 重建 DataSource**：
- 重建 DataSource 会调用 HikariPool.shutdown() → 关闭所有连接 → 100+ 业务线程 BrokenConnection
- 热调整 maximumPoolSize 只改一个 int 字段，不关闭任何连接


### 6.1.1 HikariCP setMaximumPoolSize() 源码级别解释

```java
// HikariDataSource.setMaximumPoolSize(int maxPoolSize)
// → 实际上设置的是 HikariConfig.maximumPoolSize
// → 注意：这只是一个 int 字段赋值
public void setMaximumPoolSize(int maxPoolSize) {
    this.maximumPoolSize = maxPoolSize;
}
```

**当 maximumPoolSize 变小时的完整过程**：

```
T+0ms:    dataSource.setMaximumPoolSize(5)  （从 10 → 5）
          → HikariConfig.maximumPoolSize = 5
          → 仅仅是 int 字段赋值——零连接断开

T+30s:    HouseKeeper 后台线程运行 fillPool()
          → 检查 idleConnections.size() > minimumIdle (2)
          → 标记超出的 idle 连接为"待回收"

T+30s+N:  被标记的连接归还到池（returnConnection）时被关闭
          或者 evictor 定时扫描到它们时被关闭

关键结论：
- 活跃连接（正在执行 SQL 的）不会被立即断开
- 只回收空闲连接
- 如果 newSize < currentActive（假设 10 个活跃连接，缩容到 5）
  → 不会断开活跃连接，但池不再接受新连接请求
  → 直到活跃连接归还释放出位置
```

**对比 @RefreshScope 重建**：

```
@RefreshScope 重建 DataSource Bean：
  @PreDestroy close() → HikariPool.shutdown()
    → connection.close() for ALL connections
    → 0 连接可用
    → 耗时 1-3 秒
    → 其他线程拿到 BrokenConnection → SQLException
    → 需要等待完全重建完成才能接受新请求

自定义 Listener 热调整：
  setMaximumPoolSize(5) → int 赋值
    → 0 连接断开
    → 耗时 < 1μs
    → 对业务完全透明
```

### 6.2 场景二：功能开关切换

```java
@Component
public class FeatureFlagManager {

    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        nacosConfigManager.getConfigService().addListener(
            "feature-flags.yaml", "FEATURE_GROUP", new Listener() {
                @Override
                public void receiveConfigInfo(String configInfo) {
                    Map<String, Boolean> newFlags = parseFlags(configInfo);
                    Set<String> changes = computeDiff(flags, newFlags);
                    flags.putAll(newFlags);

                    for (String key : changes) {
                        log.info("[FeatureFlag] {} → {}", key, newFlags.get(key));
                        // ⚡ 只更新 flags Map，不重建任何 Bean
                        // 所有方法访问 FeatureFlagManager.isEnabled("xxx") 直接读新值
                    }
                }
                @Override public Executor getExecutor() { return null; }
            });
    }

    public boolean isEnabled(String featureKey) {
        return flags.getOrDefault(featureKey, false);
    }
}
```

### 6.3 场景三：跨配置一致性校验

```java
@PostConstruct
public void init() {
    // 监听两个配置
    nacosConfigManager.getConfigService().addListener("db.yaml", "DB_GROUP", listener);
    nacosConfigManager.getConfigService().addListener("redis.yaml", "CACHE_GROUP", listener);

    // 在 receiveConfigInfo 中做跨配置校验：
    // if (db.maxConnections + redis.maxConnections > OS.fileDescriptorLimit) {
    //     log.error("Combined connections would exceed OS limit!");
    //     sendAlert();
    // }
}
```

---


### 6.4 B2 的局限性：什么时候不能替代 @RefreshScope

B2 不是银弹。以下场景下，@RefreshScope 比自定义 Listener 更合适：

| 场景 | 为什么 @RefreshScope 更合适 | 为什么 B2 不够 |
|---|---|---|
| **嵌套配置对象** | @ConfigurationProperties 自动绑定嵌套 YAML → 一次重建全部更新 | 手动 diff 嵌套结构复杂度 O(n²) |
| **新增配置项** | 新 Bean 自动读新 key | 需要修改 Java 代码添加 if 分支 |
| **@Value 字段多** | Bean 重建后自动注入全部新值 | 每个 @Value 字段都要手动维护映射 |
| **重建代价低的 Bean** | 简单 DTO/VO，重建 < 1ms | over-engineering——写自定义 Listener 比 Bean 自动重建更复杂 |
| **需要 @PostConstruct 逻辑** | Bean 重建自然触发初始化 | 自定义 Listener 需要手动调用初始化方法 |

**选择指南**：
- 配置项 < 5，Bean 无资源持有 → @RefreshScope（简单直接）
- 配置项 ≥ 5，Bean 持有连接池/线程池 → 自定义 Listener（精确控制）
- 功能开关 → 自定义 Listener（只改标志位，不重建 Bean）
## 七、反模式

### 反模式 1：自定义 Listener 里又调 RefreshScope.refreshAll()

```java
// ❌ 破坏了精确控制的意义
@Override
public void receiveConfigInfo(String configInfo) {
    refreshScope.refreshAll();  // 又全量刷新了
}

// ✅ 只做目标明确的操作
@Override
public void receiveConfigInfo(String configInfo) {
    featureFlagManager.update(configInfo);  // 只更新功能开关
}
```

### 反模式 2：在 Listener 回调中做耗时操作

```java
// ❌ Nacos SDK 的线程被长时间阻塞
@Override
public void receiveConfigInfo(String configInfo) {
    Thread.sleep(5000);  // → 阻塞其他配置的 Listener
    heavyComputation(configInfo);
}

// ✅ 用自定义线程池异步处理
@Override
public Executor getExecutor() {
    return dedicatedConfigExecutor;  // 返回自定义线程池
}

@Override
public void receiveConfigInfo(String configInfo) {
    // 快速解析 + 异步处理
    ConfigChange change = parseChange(configInfo);
    dedicatedConfigExecutor.execute(() -> {
        applyChange(change);
    });
}
```

### 反模式 3：assume Listener 回调是线程安全的

Nacos SDK 可能并发触发多个 Listener 回调（虽然一般不会对同一 dataId 并发触发，但不保证）。

```java
// ✅ 用锁保护共享状态
private final ReadWriteLock lock = new ReentrantReadWriteLock();

@Override
public void receiveConfigInfo(String configInfo) {
    lock.writeLock().lock();
    try {
        lastConfigContent = configInfo;
    } finally {
        lock.writeLock().unlock();
    }
}
```
