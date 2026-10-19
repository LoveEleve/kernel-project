# 自定义 NamingService - 拦截 SDK 层的服务发现

## 一、B4 和 B1 的关系

B1 替换了 Spring Cloud 层的 `ServiceRegistry`(控制"谁注册到 Spring Cloud")，B4 拦截的是 Nacos SDK 层的 `NamingService`(控制"Nacos SDK 的原始调用")。

| | B1(ServiceRegistry) | B4(NamingService 拦截器) |
|---|---|---|
| 控制层 | Spring Cloud 抽象层 | Nacos SDK 层 |
| 入口 | `register(Registration)` / `deregister(Registration)` | `registerInstance(Instance)` / `selectInstances()` / `subscribe()` |
| 影响范围 | Spring Cloud LoadBalancer/DiscoveryClient | 所有直接调 Nacos SDK 的代码(含非 Spring 应用) |
| 接口方法数 | 5 个 | 53 个(每版本变化) |
| 替换方式 | 实现接口，排除自动配置 | 包装模式(Decorator：实现接口 + 委托) |

**B4 比 B1 更低层**: 如果代码绕过 Spring Cloud 直接 `namingService.registerInstance()`,B1 拦不到,B4 能。

## 二、为什么用包装模式而不是实现接口

Nacos `NamingService` 接口有 53 个方法(D-01 详细分析),每个版本都可能新增。如果 `implements NamingService`,每次升级 SDK 都要重新对齐所有方法签名。

**包装模式**(Decorator Pattern): 实现 `NamingService` 接口,但只拦截核心方法,其余**委托给原始实例**。

```java
public class MonitoredNamingService implements NamingService {

    private final NamingService delegate;  // 原始 NacosNamingService
    private final MeterRegistry meterRegistry;

    public MonitoredNamingService(NamingService delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
    }

    // ===== 核心方法: 拦截 + 监控 + 委托 =====
    @Override
    public void registerInstance(String serviceName, String groupName, Instance instance)
            throws NacosException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            delegate.registerInstance(serviceName, groupName, instance);
            meterRegistry.counter("nacos.register.success").increment();
        } catch (NacosException e) {
            meterRegistry.counter("nacos.register.failure").increment();
            throw e;  // 不吞异常,让调用方自己处理
        } finally {
            sample.stop(meterRegistry.timer("nacos.register.latency"));
        }
    }

    @Override
    public void deregisterInstance(String serviceName, String groupName, Instance instance)
            throws NacosException {
        // 同理,监控注销操作
        delegate.deregisterInstance(serviceName, groupName, instance);
    }

    @Override
    public List<Instance> selectInstances(String serviceName, String groupName,
            List<String> clusters, boolean healthy, boolean subscribe) throws NacosException {
        List<Instance> instances = delegate.selectInstances(
            serviceName, groupName, clusters, healthy, subscribe);
        // 注入自定义逻辑: 过滤掉不想要的实例
        return instances.stream()
            .filter(i -> !"offline".equals(i.getMetadata().get("status")))
            .collect(Collectors.toList());
    }

    // ===== 其余 50 个方法: 全部委托 =====
    @Override
    public List<Instance> getAllInstances(String serviceName) throws NacosException {
        return delegate.getAllInstances(serviceName);
    }
    @Override
    public void subscribe(String serviceName, EventListener listener) throws NacosException {
        delegate.subscribe(serviceName, listener);
    }
    // ... 其余 47 个方法全部委托给 delegate
    @Override
    public void shutDown() throws NacosException { delegate.shutDown(); }
    @Override
    public String getServerStatus() { return delegate.getServerStatus(); }
}
```

**关键**: 实现了 `NamingService` 接口但通过 `delegate` 委托,同时拦截核心方法注入监控/过滤逻辑。中断不影响的委托链。

## 三、接入 Spring Cloud

```java
@Configuration
public class MonitoredNamingConfig {

    @Bean
    @Primary  // 覆盖 Spring Cloud 自动配置的 NamingService
    public NamingService namingService(
            NacosDiscoveryProperties properties, MeterRegistry meterRegistry)
            throws NacosException {
        Properties nacosProps = new Properties();
        nacosProps.put("serverAddr", properties.getServerAddr());
        nacosProps.put("namespace", properties.getNamespace());

        NamingService raw = new NacosNamingService(nacosProps);
        return new MonitoredNamingService(raw, meterRegistry);
    }
}
```

`@Primary` 确保所有注入 `NamingService` 的地方(包括 Spring Cloud 内部)都拿到包装后的实例。

## 四、生产场景

### 双注册中心(主 + 灾备)

```java
@Override
public void registerInstance(String serviceName, String groupName, Instance instance)
        throws NacosException {
    try {
        primary.registerInstance(serviceName, groupName, instance);   // 主集群
    } catch (NacosException e) {
        log.error("Primary register failed, trying backup", e);
        backup.registerInstance(serviceName, groupName, instance);    // 灾备集群
    }
}
```

### 注册降级 + 本地缓存兜底

```java
@Override
public void registerInstance(String serviceName, String groupName, Instance instance)
        throws NacosException {
    try {
        delegate.registerInstance(serviceName, groupName, instance);
    } catch (NacosException e) {
        log.warn("Register failed, falling back to local cache. Service: {}", serviceName);
        // 不抛异常 -> 应用继续启动 -> 依赖 Nacos SDK 本地缓存(ServiceInfoHolder)发现其他服务
        meterRegistry.counter("nacos.register.degraded").increment();
    }
}
```

**注意**: 这只降级了**注册**(自己不被别人发现),不影响**发现**(通过本地缓存找到别人)。如果 Nacos 完全不可达 + 首次启动,本地缓存为空 -> 服务启动但完全找不到调用方。

### 调用链审计

```java
// Prometheus 指标
nacos_register_success_total{service="nacos-lab"} 1542
nacos_register_failure_total{service="nacos-lab"} 3
nacos_register_latency_seconds{service="nacos-lab",quantile="0.99"} 0.15
nacos_discovery_latency_seconds{service="my-xhs-user",quantile="0.99"} 0.05
```

### 流量染色

```java
@Override
public void registerInstance(String serviceName, String groupName, Instance instance)
        throws NacosException {
    // 注入流量标识
    String trafficTag = System.getProperty("traffic.tag", "stable");
    instance.getMetadata().put("traffic", trafficTag);
    delegate.registerInstance(serviceName, groupName, instance);
}
```

配合 B3 的 LoadBalancer: `getInstances` 时按 `metadata["traffic"]` 过滤 -> 实现流量按版本路由。

## 五、反模式

| 反模式 | 说明 |
|--------|------|
| 包装模式中吞掉异常 | 注册失败后 `catch + return` 不抛异常 -> 调用方(包括 Spring Cloud)不知道注册失败,实例在 Nacos 中缺失 |
| `@Primary` 覆盖但未实现完整接口 | 只覆盖了一个方法,其他 52 个方法没有 delegate -> NPE |
| 在 selectInstances 中做网络 IO | `selectInstances` 每个请求可能调用多次 -> 网络 IO 导致请求延迟累加 |
