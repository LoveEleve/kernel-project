# B1: 自实现 ServiceRegistry — Spring Cloud 的 SPI 解耦哲学

---

## 目录

1. [问题起源：Spring Cloud 为什么需要 SPI](#一问题起源spring-cloud-为什么需要-spi)
2. [三层架构深度拆解](#二三层架构深度拆解)
    - [第 1 层：ServiceRegistry 接口](#21-第-1-层serviceregistry-接口)
    - [第 2 层：AbstractAutoServiceRegistration 生命周期](#22-第-2-层abstractautoserviceregistration-生命周期)
    - [第 3 层：Spring Boot 自动配置引擎](#23-第-3-层spring-boot-自动配置引擎)
3. [NacosServiceRegistry 源码逐行解读](#三nacosserviceregistry-源码逐行解读)
4. [Nacos SDK 注册的底层全过程](#四nacos-sdk-注册的底层全过程)
5. [端口的秘密：为什么会有 -1](#五端口的��密为什么会有-1)
6. [自定义实现：每一行代码的解释](#六自定义实现每一行代码的解释)
7. [NacosRegistrationCustomizer：官方扩展点及其局限](#七nacosregistrationcustomizer官方扩展点及其局限)
8. [生产场景代码详解](#八生产场景代码详解)
9. [反模式](#九反模式)
10. [验证](#十验证)

---

## 一、问题起源：Spring Cloud 为什么需要 SPI

### 1.1 2014-2016：注册中心的战国时代

2014 年 Netflix 开源 Eureka，Spring Cloud Netflix 把它集成进了 Spring Boot 生态。但 Eureka 不是唯一的注册中心：

| 注册中心 | 推出时间 | 协议 | 特点 |
|---|---|---|---|
| Eureka | 2014 | HTTP REST | AP，Netflix 内部使用 |
| Consul | 2014 | HTTP + DNS + Gossip | HashiCorp，内置健康检查 |
| Zookeeper | 2013 | TCP + Watcher | CP，强一致性 |
| Nacos | 2018 | gRPC + HTTP | CP+AP，阿里巴巴 |
| etcd | 2013 | gRPC + Raft | CP，Kubernetes 核心 |

如果你用 Spring Boot 写了一个微服务，��天用 Eureka，明天业务要求搭在阿里云上需要 Nacos——你不想改代码。

### 1.2 ServiceRegistry 接口的诞生

2016 年，Spring Cloud 核心工程师 Spencer Gibb 抽象出了 `ServiceRegistry<R extends Registration>` 接口。

它的设计目标只有一句话：**让 Spring Cloud ���用不关心底层用的是哪个注册中心**。

```java
// 2016 年的原始设计，至今未变
public interface ServiceRegistry<R extends Registration> {

    // 注册一个实例到这个注册中心
    void register(R registration);

    // 摘除实例
    void deregister(R registration);

    // 关掉注册中心连接
    void close();

    // 配合 Actuator /service-registry 端点：上下线控制
    void setStatus(R registration, String status);

    // 查询实例当前的上/下线状态
    <T> T getStatus(R registration);
}
```

**为什么就这 5 个**？因为 Spring Cloud 对注册中心的要求就四件事：上线、下线、查询状态、关闭连接。

注意缺了什么：
- **没有心跳方法** — 心跳是 `NacosAutoServiceRegistration` 内部的 gRPC 双向流，`ServiceRegistry` 不关心
- **没有服务列表查询** — 那是 `DiscoveryClient.getServices()` 的职责，另一条线
- **没有健康检查** — 那是 Spring Actuator 和 Nacos 健康检查器的协作，不在注册接口里

### 1.3 Registration 接口的设计

```java
public interface Registration extends ServiceInstance {
    // 继承 ServiceInstance，加上注册专用的方法
}

public interface ServiceInstance {
    default String getInstanceId() { ... }
    String getServiceId();   // 服务名 = spring.application.name
    String getHost();        // IP 地址
    int getPort();           // 服务端口
    boolean isSecure();      // 是否 HTTPS
    URI getUri();            // 完整 URI
    Map<String, String> getMetadata();  // 自定义元数据
    default String getScheme() { ... }
}
```

这个接口的设计理念是一个通用"服务实例"模型——不需要知道底层是 Nacos `Instance` 还是 Eureka `InstanceInfo`。

`getMetadata()` 是关键：String-String Map，什么都可以往里塞。Nacos 的 `weight`、`clusterName`、`ephemeral` 都通过这个通道流入 Spring Cloud。

---

### 1.4 为什么是这 8 个方法——Registration 接口的设计哲学

`Registration extends ServiceInstance`，每个方法不是一个随机命名——每个都在解决一个具体的设计问题：

**`getServiceId()` — 为什么是 String 不用枚举？**
String 是 Java 的 universal identifier。Nacos 里叫 `serviceName`，Consul 里叫 `serviceName`，Eureka 里叫 `appName`——String 抹平了所有命名差异。不用枚举是因为枚举会把注册中心的名字硬编码进 Spring Cloud Commons——违背了"注册中心无关"的初衷。

**`getHost() + getPort()` — 为什么分开不是一个字段？**
IP 和 port 是两个正交维度。同一台机器跑多个服务（不同端口）很常见。如果合成 `host:port`，LoadBalancer 按 IP 分组的逻辑就废了——需要先拆分再分组。

**`isSecure()` — 为什么是 boolean 不是枚举？**
2016 年 Spring Cloud 只支持 HTTPS/HTTP 两种。如果要支持 mTLS、gRPC-TLS，boolean 确实不够——但这是 API 设计的 tradeoff：当下够用 vs 未来扩展。实际生产中对 mTLS 的使用是通过 metadata 注入证书指纹。

**`getUri()` — 已有 host+port+isSecure 为什么还要 URI？**
契约约束：`isSecure()=true → getUri().getScheme() = "https"`。避免每个调用方自己去拼 URI（如果某个调用方弄错 scheme，问题很难排查）。`getUri()` 是权威来源。

**`getMetadata()` — 为什么是 `Map<String, String>` 不是 `Map<String, Object>`？**
Nacos gRPC 协议是 protobuf，metadata 的 protobuf 定义是 `map<string, string>`。Spring Cloud Commons 取最小公约数：String。这也意味着你不能直接塞 Object——需要序列化为 String（如 JSON string）。

**为什么 `Registration extends ServiceInstance` 而不是组合？**
Spring Cloud 的核心假设：一个服务就是一个 ServiceInstance 的注册视图。继承意味着 Registration **是一个** ServiceInstance（只是加了注册元数据信息），不是"包含一个" ServiceInstance。这个假设让 DiscoveryClient 和 ServiceRegistry 可以直接共享同一个对象模型——不需要转换。

## 二、三层架构深度拆解

### 2.1 第 1 层：ServiceRegistry 接口

五个方法各自回答一个问题：

| 方法 | 场景 | 问题 |
|---|---|---|
| `register()` | 服务启动 | "我上线了，让调用方找到我" |
| `deregister()` | 服务关闭 | "我下线了，别再给我发请求" |
| `setStatus()` | 运维操作 | "暂时摘除这个实例" |
| `getStatus()` | 运维查询 | "这个实例现在是 UP 还是 DOWN？" |
| `close()` | 连接关闭 | "进程要退出了，释放注册中心连接" |

注意：`setStatus/getStatus` 是为 Spring Boot Actuator 的 `/actuator/service-registry` 端点设计的：
```bash
# 手动摘除
POST /actuator/service-registry  →  status=DOWN  →  setStatus(reg, "DOWN")

# 手动恢复
DELETE /actuator/service-registry  →  status=UP   →  setStatus(reg, "UP")
```

### 2.2 第 2 层：AbstractAutoServiceRegistration 生命周期

这个 314 行的类是整个注册生命周期的核心控制器。它不是"框架胶水代码"——它是一个精确的状态机。

#### 2.2.1 完整启动链路

```
[Spring Boot 启动流程]

1. SpringApplication.run()
   ↓
2. AbstractApplicationContext.refresh()
   ↓
3. finishRefresh() → WebServerStartStopLifecycle.start()
   ↓
4. Tomcat 绑定端口 28001 → 发布 WebServerInitializedEvent
   ↓
5. AbstractAutoServiceRegistration.onApplicationEvent(event)
   │
   ├── this.port.compareAndSet(0, event.getWebServer().getPort())
   │   // ↑ 关键：从 event 拿到真实端口，存到 AtomicInteger port
   │
   ├── this.start()
   │   ├── if (!isEnabled()) return;
   │   │   // ↑ 检查 spring.cloud.service-registry.auto-registration.enabled
   │   │
   │   ├── if (!this.running.get()) {  // 防止重复注册
   │   │   │
   │   │   ├── publishEvent(InstancePreRegisteredEvent)
   │   │   │   // ↑ 注册前钩子，允许预处理
   │   │   │
   │   │   ├── register()
   │   │   │   └── serviceRegistry.register(getRegistration())
   │   │   │       // ↑ 调用你实现的 ServiceRegistry.register()
   │   │   │
   │   │   ├── (可选) registerManagement()  // management 端口单独注册
   │   │   │
   │   │   ├── publishEvent(InstanceRegisteredEvent)
   │   │   │   // ↑ 注册后钩子，通知 Spring 应用上下文
   │   │   │
   │   │   └── this.running.compareAndSet(false, true)
   │   │       // ↑ 标记为 running
   │   │
   │   └── }
```

#### 2.2.2 @PreDestroy 下线链路

```
[JVM 关闭 — SIGTERM]

1. Spring 容器关闭
   ↓
2. @PreDestroy AbstractAutoServiceRegistration.destroy()
   ↓
3. this.stop()
   ├── if (running.compareAndSet(true, false) && isEnabled()) {
   │   │
   │   ├── deregister()
   │   │   └── serviceRegistry.deregister(getRegistration())
   │   │       // ↑ 调用你实现的 ServiceRegistry.deregister()
   │   │
   │   └── serviceRegistry.close()
   │       // ↑ 释放资源
   │
   └── }
```

**为什么不能 kill -9**：SIGKILL 直接终止 JVM 进程，不触发 Spring 容器的关闭流程 → `@PreDestroy` 不被调用 → 实例永远不会被摘除 → Nacos Server 等 30s 超时才移除。这 30s 里，调用方还会把请求路由到这个已死的实例上。


#### 2.2.3 六种终止信号的完整行为矩阵

生产环境不止"正常 kill"和"kill -9"两种场景：

| 信号/场景 | 触发机制 | @PreDestroy触发？ | deregister完成？ | 调用方影响 |
|---|---|---|---|---|
| **kill (SIGTERM)** | Shell kill 或 docker stop 第一枪 | ✅ | ✅ | 正常摘除 |
| **Ctrl+C (SIGINT)** | 终端信号 | ✅ | ✅ | 同上 |
| **docker stop（超时）** | 先 SIGTERM，等 10s 后 SIGKILL | ⚠️ shutdown > 10s 则跳过 | ⚠️ 可能被中断 | 死实例在 Nacos 中停留 30s |
| **kill -9 (SIGKILL)** | 内核直接杀进程 | ❌ | ❌ | 30s 内请求打到死实例 |
| **OOM Killer** | Linux 内核杀进程 | ❌ | ❌ | 同 kill -9，更难排查 |
| **K8s preStop hook** | preStop 在 SIGTERM 之前执行 | ✅（需 hook 主动 curl deregister） | ✅ | 推荐方案 |

**K8s 环境最佳实践**：

```yaml
spec:
  terminationGracePeriodSeconds: 30
  containers:
    - name: app
      lifecycle:
        preStop:
          exec:
            command:
              - /bin/sh
              - -c
              - |
                curl -s -X POST http://localhost:28001/discovery/deregister
                sleep 10
```

**docker stop 的陷阱**：默认超时 10s。如果 Spring 关闭耗时超过 10s，SIGKILL 会跳过 deregister。设置 `docker stop -t 30` 或 Compose `stop_grace_period: 30s`。

**@PreDestroy 不是 ShutdownHook**：@PreDestroy 是 Spring 容器管理的——只在 Spring 正常关闭时触发。ShutdownHook（`Runtime.addShutdownHook()`）是 JVM 级别的，在 JVM 退出前执行。但 SIGKILL 连 ShutdownHook 都绕过——JVM 进程直接被内核杀，不执行任何 Java 代码。

### 2.3 第 3 层：Spring Boot 自动配置引擎

#### 2.3.1 @Conditional 系列的运行原理

```java
@Configuration
@ConditionalOnNacosDiscoveryEnabled  // ← 有 nacos discovery 依赖才创建
@ConditionalOnProperty(              // ← yml 配置了才创建
    value = "spring.cloud.service-registry.auto-registration.enabled",
    matchIfMissing = true)           // 不加这个配置 = 默认启用
@AutoConfigureAfter({                // ← 在这些配置之后执行（顺序保证）
    AutoServiceRegistrationConfiguration.class,
    AutoServiceRegistrationAutoConfiguration.class,
    NacosDiscoveryAutoConfiguration.class
})
public class NacosServiceRegistryAutoConfiguration {
    // ...
}
```

三个注解的含义：
- `@ConditionalOnNacosDiscoveryEnabled` — 检查 classpath 里有没有 `NacosDiscoveryProperties`
- `@ConditionalOnProperty(... matchIfMissing = true)` — 默认值为 true（不配也生效）
- `@AutoConfigureAfter` — **顺序很重要**：必须先有 `AutoServiceRegistrationProperties`，才能创建依赖它的 Bean

#### 2.3.2 为什么没有 @ConditionalOnMissingBean

```java
@Bean
public NacosServiceRegistry nacosServiceRegistry(...) {
    return new NacosServiceRegistry(...);
}
// ⚠️ 没有 @ConditionalOnMissingBean！
```

这不是疏忽——这是有意设计的。

原因是：如果你定义了同名 Bean 覆盖，Spring 的 `DefaultListableBeanFactory` 会用后注册的 Bean 替换先注册的。但如果这两个 Bean 都依赖同一个 `NacosServiceManager`，替换后的 Bean 可能拿到一个已经被旧 Bean 占用过的 NamingService 实例——导致 gRPC 连接冲突。

所以正确的方式不是覆盖，而是**排除整个配置类**，自己重建全部依赖链：

```yaml
spring:
  autoconfigure:
    exclude:
      - com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration
```

#### 2.3.3 spring.factories → AutoConfiguration.imports 的变迁

Spring Boot 2.x 用 `META-INF/spring.factories` 声明自动配置：
```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
  com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration
```

Spring Boot 3.x 改用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：
```
com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration
```

两种方式都做同一件事：告诉 Spring Boot "启动时把这个类当前配置类处理"。

为什么改？`spring.factories` 是 Key-Value 文件，一个 Key 下挂所有配置类，多个 starter 混在一起；`.imports` 按 starter 分开，更干净。

---

## 三、NacosServiceRegistry 源码逐行解读

读完源码再看自己的实现，才能理解"我改了哪里"和"为什么这样改"。

### 3.1 register() — 把 Spring Cloud Registration 变成 Nacos Instance

```java
@Override
public void register(Registration registration) {

    // 1. 空值防御
    if (StringUtils.isEmpty(registration.getServiceId())) {
        log.warn("No service to register for nacos client...");
        return;
    }

    // 2. 拿到 Nacos SDK 的 NamingService（gRPC 客户端）
    NamingService namingService = namingService();
    // → 底层调用 nacosServiceManager.getNamingService()
    // → 返回一个 NamingClientProxyDelegate（持有 gRPC 连接）

    String serviceId = registration.getServiceId();  // "nacos-lab"
    String group = nacosDiscoveryProperties.getGroup();  // "DEFAULT_GROUP"

    // 3. 关键转换：Registration → Instance
    Instance instance = getNacosInstanceFromRegistration(registration);

    // 4. 注册到 Nacos Server
    try {
        namingService.registerInstance(serviceId, group, instance);
        log.info("nacos registry, {} {} {}:{} register finished",
                 group, serviceId, instance.getIp(), instance.getPort());
    } catch (Exception e) {
        // 5. 失败处理：fail-fast or 降级
        if (nacosDiscoveryProperties.isFailFast()) {
            log.error(...);
            rethrowRuntimeException(e);
            // fail-fast=true → 注册失败 = 启动失败 = 拒绝启动
        } else {
            log.warn(...);
            // fail-fast=false → 注册失败也继续启动
            // 常用于双注册中心场景：A 中心挂了还有 B 中心
        }
    }
}
```

### 3.1.1 fail-fast vs fail-safe — 完整行为矩阵

`register()` 的异常处理不只是 "抛异常 or 打日志"——真正的生产行为取决于 Nacos 故障的类型：

| 场景 | fail-fast=true | fail-fast=false |
|---|---|---|
| **Nacos 完全不可达**（startup） | 抛 RuntimeException → Spring 启动失败，进程退出 | 打 WARN 日志 → 应用照常启动。**但是：实例没有注册成功！调用方发现不了它 = 没有流量进来** |
| **Nacos 网络抖动**（registerInstance 超时 500ms） | 抛异常 → 启动失败 | 打日志 → 启动成功但实例缺失。**Nacos SDK 的 registerInstance() 调用本身没有自动重试**（gRPC 通道有 keepalive，但 RPC 调用失败不重试）→ 注册永久缺失 |
| **Nacos 启动后恢复** | N/A（进程已退出） | 应用已运行但实例不在 Nacos 中。**不会自动重新注册！**需要手动重启应用或调用 `/discovery/reregister` |
| **注册失败 + 本地有服务缓存** | N/A | 应用能通过缓存发现其他服务，但**自己不能被别人发现** = 单方面依赖 |

**关键源码证据**：Nacos SDK 的 `NamingClientProxyDelegate.registerService()` 中没有 `retry()` 或 `doOnError(retry)` 等重试机制。`NamingGrpcRedoService` 只在**连接重新建立后**重放堆积的请求，不会对单次 RPC 失败做自动重试。

**生产建议**：
- `failFast=false` 是默认的，但需要配合：启动后健康检查（确认注册是否成功）+ 注册失败的 metrics 告警（Prometheus counter `nacos_registration_failed_total`）
- 不推荐 `failFast=true` 用于核心服务（Nacos 抖动导致全部实例启动失败 = 雪崩）
- `failFast=true` 适合测试环境（快速暴露配置错误）

### 3.2 getNacosInstanceFromRegistration — 最重要的映射函数（也是最需要理解的一段）

```java
private Instance getNacosInstanceFromRegistration(Registration registration) {
    Instance instance = new Instance();

    // ← Spring Cloud 通用 →  Nacos 专属
    instance.setIp(registration.getHost());              // getHost() → ip
    instance.setPort(registration.getPort());            // getPort() → port
    instance.setWeight(nacosDiscoveryProperties.getWeight());         // 来自 yml
    instance.setClusterName(nacosDiscoveryProperties.getClusterName()); // 来自 yml
    instance.setEnabled(nacosDiscoveryProperties.isInstanceEnabled()); // 来自 yml
    instance.setMetadata(registration.getMetadata());   // getMetadata() → metadata
    instance.setEphemeral(nacosDiscoveryProperties.isEphemeral());   // 来自 yml

    return instance;
}
```

**理解这段的关键**：哪些属性来自 `Registration`（Spring Cloud 通用），哪些来自 `NacosDiscoveryProperties`（Nacos 专属）？

| 属性 | 来源 | 说明 |
|---|---|---|
| `ip` | `registration.getHost()` | Spring Cloud 抽象 |
| `port` | `registration.getPort()` | Spring Cloud 抽象 |
| `metadata` | `registration.getMetadata()` | Spring Cloud 抽象，**自定义 metadata 注入点** |
| `weight` | `nacosDiscoveryProperties.getWeight()` | Nacos 专属，yml 配置 |
| `clusterName` | `nacosDiscoveryProperties.getClusterName()` | Nacos 专属，yml 配置 |
| `enabled` | `nacosDiscoveryProperties.isInstanceEnabled()` | Nacos 专属，yml 配置 |
| `ephemeral` | `nacosDiscoveryProperties.isEphemeral()` | Nacos 专属，yml 配置 |

**自定义点的位置**：`instance.setMetadata(registration.getMetadata())` 之前，你可以往 metadata Map 里注入任何字段。这就是 B1 实现的核心位置。

### 3.3 deregister() — 摘除

```java
@Override
public void deregister(Registration registration) {
    log.info("De-registering from Nacos Server now...");

    if (StringUtils.isEmpty(registration.getServiceId())) {
        return;
    }

    NamingService namingService = namingService();
    String serviceId = registration.getServiceId();
    String group = nacosDiscoveryProperties.getGroup();

    try {
        namingService.deregisterInstance(
            serviceId, group,
            registration.getHost(),      // 要摘除的 IP
            registration.getPort(),      // 要摘除的端口
            nacosDiscoveryProperties.getClusterName()  // 要摘除的集群
        );
    } catch (Exception e) {
        log.error("ERR_NACOS_DEREGISTER, de-register failed...");
    }
    log.info("De-registration finished.");
}
```

注意：deregister 调用 `namingService.deregisterInstance()`（五个参数的重载），而 register 调用 `namingService.registerInstance()`（三个参数的重载）。register 传 `Instance` 对象（包含所有属性），deregister 只传定位参数（service+group+ip+port+cluster）——摘除不需要元数据，只需要定位到对应的实例。

### 3.4 setStatus() / getStatus() — 运维接口

```java
@Override
public void setStatus(Registration registration, String status) {
    if (!STATUS_UP.equalsIgnoreCase(status)
            && !STATUS_DOWN.equalsIgnoreCase(status)) {
        log.warn("can't support status {}, please choose UP or DOWN", status);
        return;
    }

    String serviceId = registration.getServiceId();
    Instance instance = getNacosInstanceFromRegistration(registration);

    // 设置 enabled 属性
    if (STATUS_DOWN.equalsIgnoreCase(status)) {
        instance.setEnabled(false);  // enabled=false → 不健康
    } else {
        instance.setEnabled(true);   // enabled=true → 健康
    }

    try {
        // 使用 NamingMaintainService 而非 NamingService
        // NamingMaintainService 是管理接口（更新实例属性）
        // NamingService 是运行时接口（注册/发现）
        Properties nacosProperties = nacosDiscoveryProperties.getNacosProperties();
        nacosServiceManager.getNamingMaintainService(nacosProperties)
                .updateInstance(serviceId, nacosDiscoveryProperties.getGroup(), instance);
    } catch (Exception e) {
        throw new RuntimeException("update nacos instance status fail", e);
    }
}
```

**NamingMaintainService vs NamingService**：
- `NamingService` → 服务注册/发现/注销 → 通过 `NamingClientProxyDelegate`（gRPC 流）
- `NamingMaintainService` → 实例管理（更新属性）→ 通过 `NamingClientProxy` 的两种实现（`NamingHttpClientProxy` / `NamingGrpcClientProxy`）

`setStatus` 用的是 NamingMaintainService——因为它是更新实例属性（改 `enabled`），不是注册。

---

## 四、Nacos SDK 注册的底层全过程

`namingService.registerInstance(serviceId, group, instance)` 这一行背后发生了什么？

### 4.1 客户端：NamingClientProxyDelegate → gRPC 流

```
NacosNamingService.registerInstance(serviceId, group, instance)
  ↓
NamingClientProxyDelegate.registerService(serviceId, group, instance)
  ↓ 判断实例类型
  ├── ephemeral=true → NamingGrpcClientProxy.registerService()
  │   ↓
  │   构造 InstanceRegisterRequest { serviceName, group, ip, port, metadata, ephemeral }
  │   ↓
  │   BiRequestStreamObserver.onNext(request)
  │   │  ← gRPC 双向流，通过已建立的 gRPC 连接发送
  │   ↓
  │   等待 BiRequestStreamObserver.onNext(response)
  │   ↓
  │   parse InstanceRegisterResponse { result, message }
  │
  └── ephemeral=false → NamingHttpClientProxy.registerService()
      ↓
      HTTP POST /nacos/v1/ns/instance
      传 JSON body: { serviceName, groupName, ip, port, metadata, ephemeral }
```

**ephemeral=true（临时实例，默认）**：走 gRPC 双向流，低延迟，心跳内嵌在流中。

**ephemeral=false（持久化实例）**：走 HTTP REST，延迟较高，没有自动心跳。

### 4.2 服务端：Nacos Server 收到注册请求

```
InstanceRequestHandler.handle(InstanceRegisterRequest)
  ↓ 校验参数（port 0~65535，serviceName 非空）
  ↓
EphemeralClientOperationServiceImpl.registerInstance(request)
  ↓ 创建 Instance 对象
  ↓ 写入内存数据结构
  ↓
ServiceManager.getInstance().addInstance(namespaceId, serviceName, instance)
  ↓ 更新 Service 对象的 Cluster → Instance 列表
  ↓
NotifyCenter.publishEvent(ServiceChangedEvent)
  ↓ 触发 PushExecutor → gRPC push 到所有订阅者
  ↓
DistroProtocol.sync(InstanceSyncData)   ← CP/AP route
  ├── ephemeral=true → Distro 异步同步（AP）
  └── ephemeral=false → Raft 日志复制（CP）
```

**临时实例 vs 持久化实例在服务端的差异**：

| 步骤 | 临时 (ephemeral=true) | 持久化 (ephemeral=false) |
|---|---|---|
| 数据写入 | 内存 + Distro 异步同步 | Raft 日志 → StateMachine → ��存 |
| 一致性 | AP（最终一致） | CP（强一致） |
| 性能 | 高（无 Raft 开销） | 较低（Raft 多数派确认） |
| 心跳 | gRPC 双向流内嵌心跳 | 无自动心跳 |

---

### 4.3 Spring Cloud Commons 三大服务发现 SPI 的三角关系

Spring Cloud Commons 有三个 SPI 分别对应三个关注点。这不是框架臃肿——这是 CQRS（命令查询职责分离）在服务发现领域的具体体现：

```
                    ┌──────────────────────┐
                    │   Nacos / Eureka /   │
                    │   Consul / ZK        │
                    └──────┬───────┬───────┘
                           │       │
                    写入    │       │  读取
                           │       │
              ┌────────────▼─┐  ┌──▼──────────────┐
              │ServiceRegistry│  │ DiscoveryClient  │
              │  register()   │  │ getInstances()   │
              │  deregister() │  │ getServices()    │
              │  setStatus()  │  └────────┬─────────┘
              └───────────────┘           │
                                          │ 提供实例列表
                                ┌─────────▼──────────┐
                                │ServiceInstanceList │
                                │Supplier            │
                                │ (装饰器链)          │
                                └─────────┬──────────┘
                                          │ 选择
                            ┌─────────────▼────────────┐
                            │ ReactorLoadBalancer<T>    │
                            │ choose(Request)            │
                            └───────────────────────────┘
```

**为什么拆成三个而不是一个大接口？**

- **写操作**（注册/摘除）：只在启动/关闭时执行，频率极低，在意正确性
- **读操作**（发现）：每个请求都可能执行，频率极高，在意性能
- **选择操作**（路由）：依赖读操作的结果，但有自己的策略（权重/版本/亲和）

如果合在一起，LoadBalancer 就耦合了注册逻辑——LoadBalancer 每次 choose() 都要处理注册失败、健康检查、缓存刷新。

**实际的好处**：
- `CustomServiceRegistry`（B1）只替换了写路径 → `DiscoveryClient` 和 `LoadBalancer` 完全不感知
- `MetadataBasedLoadBalancer`（B3）只替换了选择路径 → `ServiceRegistry` 和 `DiscoveryClient` 完全不感知
- 这就是 SPI 解耦的力量：改一个点，不动其他两个点

## 五、端口的秘密：为什么会有 -1

这是整个 B1 实现中最容易踩的坑，也是最值得深入理解的一节。

### 5.1 两个端口，一个 gap

Spring Cloud 应用有两个"端口"的概念：

```
server.port: 28001            ← Spring Boot Web 服务器端口
                                 ↑
                                 │ 这是你真正需要注册的端口
                                 │
NacosDiscoveryProperties.port: -1 ← Nacos discovery 配置端口
                                       ↑
                                       │ 这个默认值是 -1，除非你显式配了
                                       │ spring.cloud.nacos.discovery.port
```

为什么 `NacosDiscoveryProperties.getPort()` 默认是 -1？因为如果你不配 `spring.cloud.nacos.discovery.port`，Nacos 期望你从其他地方拿到端口。

这个"其他地方"就是 `AbstractAutoServiceRegistration.port`（AtomicInteger），在 `WebServerInitializedEvent` 时设置。

### 5.2 完整的端口解析时序

```
时间线：
T+0: SpringApplication.run()
T+1: NacosRegistration Bean 创建 → NacosRegistration.getPort() = -1
T+2: Tomcat 启动，绑定 28001 端口
T+3: WebServerInitializedEvent 发布 → AbstractAutoServiceRegistration.port.set(28001)
T+4: start() → getRegistration() → getPort() 还是 -1！❌
     → 需要显式 setPort(getPort().get()) ✅
T+5: register() → CustomServiceRegistry.register(registration)
     → registration.getPort() 已经是 28001 ✅
```

**关键点**：`AbstractAutoServiceRegistration.port`（父类私有字段） ≠ `NacosRegistration.port`（子类的字段）。WebServerInitializedEvent 只更新了父类的 AtomicInteger，子类的 NacosDiscoveryProperties 里的 port 没变。

官方 `NacosAutoServiceRegistration` 通过 override `register()` 来修复这个 gap——这也是为什么 `CustomAutoServiceRegistration` 必须 override 同样的方法。

---

## 六、自定义实现：每一行代码的解释

### 6.1 CustomServiceRegistry — 为什么从零实现，不继承 NacosServiceRegistry

```java
public class CustomServiceRegistry implements ServiceRegistry<Registration> {
    // 注意：implements ServiceRegistry<Registration>，不是 extends NacosServiceRegistry

    private final NacosServiceManager nacosServiceManager;
    private final NacosDiscoveryProperties properties;

    public CustomServiceRegistry(NacosServiceManager nacosServiceManager,
                                  NacosDiscoveryProperties properties) {
        this.nacosServiceManager = nacosServiceManager;
        this.properties = properties;
    }
```

**为什么 implements 而不是 extends**？继承了就得走父类的 `register()` 流程，而父类的 `getNacosInstanceFromRegistration()` 是 `private`，不能 override。你想加自定义 metadata，只能完全重新实现 `register()`���

```java
    @Override
    public void register(Registration registration) {
        if (registration == null) {
            log.warn("[CustomRegistry] Registration is null, skipping");
            return;
        }

        String serviceId = registration.getServiceId();  // "nacos-lab"
        String group = properties.getGroup();              // "DEFAULT_GROUP"

        // === 构造 Nacos Instance（和官方 NacosServiceRegistry 一样的逻辑）===
        Instance instance = new Instance();
        instance.setIp(registration.getHost());
        instance.setPort(registration.getPort());
        instance.setWeight(properties.getWeight());
        instance.setClusterName(properties.getClusterName());
        instance.setEnabled(properties.isInstanceEnabled());
        instance.setEphemeral(properties.isEphemeral());

        // === ⚡ 自定义扩展点：注入运维 metadata ===
        Map<String, String> metadata = new HashMap<>(registration.getMetadata());
        metadata.put("startupTime", Instant.now().toString());
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("groupId", "scalab-custom-registry-v1");
        instance.setMetadata(metadata);
        // ⚡ 官方实现只有 instance.setMetadata(registration.getMetadata())
        // ⚡ 这里在官方的基础上加了 3 个字段

        try {
            namingService().registerInstance(serviceId, group, instance);
            log.info("[CustomRegistry] {} {} {}:{} registered, metadata={}",
                    group, serviceId, instance.getIp(), instance.getPort(),
                    metadata.keySet());
        } catch (Exception e) {
            if (properties.isFailFast()) {
                throw new RuntimeException("Custom registry register failed", e);
            }
            log.warn("[CustomRegistry] Failfast=false, {} register failed: {}",
                    serviceId, e.getMessage());
        }
    }
```

**注册前健康门禁的扩展思路**（当前代码未实现，但展示了扩展点的位置）：

```java
// 在 instance.setMetadata(metadata) 之前可以插入：
if (!checkDatabaseHealth()) {
    log.warn("Database not healthy, refusing to register");
    return;  // 不注册 = 不给调用方看到
}
```

### 6.2 CustomAutoServiceRegistration — 处理端口 gap

```java
public class CustomAutoServiceRegistration
        extends AbstractAutoServiceRegistration<Registration> {

    private final NacosRegistration registration;
    private final NacosDiscoveryProperties properties;

    public CustomAutoServiceRegistration(
            ServiceRegistry<Registration> serviceRegistry,
            AutoServiceRegistrationProperties autoServiceRegistrationProperties,
            NacosRegistration registration,
            NacosDiscoveryProperties properties) {
        // SC 2025 用 2 参构造（ServiceRegistry, AutoServiceRegistrationProperties）
        // 旧版本用 3 参（额外 ApplicationContext）
        super(serviceRegistry, autoServiceRegistrationProperties);
        this.registration = registration;
        this.properties = properties;
    }

    @Override
    protected NacosRegistration getRegistration() {
        // ⚡ 关键：端口 gap 修复
        // this.getPort() 来自父类的 AtomicInteger，
        // 在 WebServerInitializedEvent 时已经设置为 28001
        if (this.registration.getPort() < 0 && this.getPort().get() > 0) {
            this.registration.setPort(this.getPort().get());
            log.info("[CustomAutoReg] Port resolved: {}", this.getPort().get());
        }
        return this.registration;
    }

    @Override
    protected void register() {
        // 注册前再次确保端口已解析
        if (this.registration.getPort() < 0) {
            this.registration.setPort(getPort().get());
        }
        log.info("[CustomAutoReg] Registering via CustomServiceRegistry, port={}",
                this.registration.getPort());
        super.register();
        // ↑ super.register() → serviceRegistry.register(getRegistration())
        // → CustomServiceRegistry.register(NacosRegistration)
    }
```

### 6.3 CustomRegistrationConfig — 重建 Bean 图

```java
@Configuration
public class CustomRegistrationConfig {

    @Bean
    public CustomServiceRegistry customServiceRegistry(
            NacosServiceManager nacosServiceManager,
            NacosDiscoveryProperties properties) {
        return new CustomServiceRegistry(nacosServiceManager, properties);
    }

    @Bean
    public CustomAutoServiceRegistration customAutoServiceRegistration(
            CustomServiceRegistry serviceRegistry,
            AutoServiceRegistrationProperties autoServiceRegistrationProperties,
            NacosRegistration registration,
            NacosDiscoveryProperties properties) {
        return new CustomAutoServiceRegistration(
                serviceRegistry, autoServiceRegistrationProperties,
                registration, properties);
    }

    @Bean
    public NacosRegistration nacosRegistration(
            ObjectProvider<List<NacosRegistrationCustomizer>> registrationCustomizers,
            NacosDiscoveryProperties nacosDiscoveryProperties,
            ApplicationContext context) {
        return new NacosRegistration(
                registrationCustomizers.getIfAvailable(),
                nacosDiscoveryProperties, context);
    }
}
```

**替换前后的 Bean 图谱**：

```
[替换前]  NacosServiceRegistryAutoConfiguration
              ├── NacosServiceRegistry
              ├── NacosRegistration
              └── NacosAutoServiceRegistration
                         ↓ 依赖 NacosServiceRegistry
                         ↓ 持有 NacosRegistration

[替换后]  CustomRegistrationConfig (yml 排除了自动配置)
              ├── CustomServiceRegistry         ← 自实现，注入自定义 metadata
              ├── NacosRegistration             ← 复用官方（端口/properties 不变）
              └── CustomAutoServiceRegistration  ← 自实现，修复端口 gap
                         ↓ 依赖 CustomServiceRegistry
                         ↓ 持有 NacosRegistration
```

关键点：**NacosRegistration 还是官方的**。我们没改注册信息的基础结构（host、port、serviceId），只改了"谁把它写到 Nacos 上"（ServiceRegistry）和"谁来调度注册时机"（AutoServiceRegistration）。

---

## 七、NacosRegistrationCustomizer：官方扩展点及其局限

Spring Cloud Alibaba 其实提供了一个官方的 metadata 扩展点：

```java
@FunctionalInterface
public interface NacosRegistrationCustomizer {
    void customize(NacosRegistration registration);
}

// 使用方式
@Component
public class MyCustomizer implements NacosRegistrationCustomizer {
    @Override
    public void customize(NacosRegistration registration) {
        registration.getMetadata().put("customKey", "customValue");
    }
}
```

**它能做什么**：往 metadata 加字段。

**它不能做什么**：
- 不能改变注册的时机（必须等 `WebServerInitializedEvent`）
- 不能做注册前健康检查
- 不能自定义心跳策略
- 不能做双注册中心

**所以 B1 比 Customizer 多了什么**：

| | NacosRegistrationCustomizer | B1: 自实现 ServiceRegistry |
|---|---|---|
| 加 metadata | ✅ | ✅ |
| 注册前健康门禁 | ❌ | ✅ （在 `register()` 里加检查） |
| 控制注册时机 | ❌ | ✅ （override `start()`/`stop()`） |
| 自定义心跳 | ❌ | ✅ （换 `NamingService` 实现） |
| 双注册中心 | ❌ | ✅ （在 `register()` 里同时写两个） |

---

## 八、生产场景代码详解

### 8.1 双注册中心迁移

Eureka → Nacos 灰度迁移期间，一个应用同时注册到两边：

```java
@Override
public void register(Registration registration) {
    // 原 Nacos 注册（继续用）
    nacosNamingService.registerInstance(serviceId, group, nacosInstance);

    // 同时注册到 Eureka
    InstanceInfo eurekaInfo = InstanceInfo.Builder.newBuilder()
        .setAppName(registration.getServiceId())
        .setHostName(registration.getHost())
        .setPort(registration.getPort())
        .build();
    eurekaClient.register(eurekaInfo);

    log.info("Dual-registered: Nacos + Eureka");
}
```

**灰度切流**：自定义 LoadBalancer（B3 的内容），按百分比把流量从 Eureka 实例切到 Nacos 实例。

### 8.1.1 完整的 deregister 双写与部分失败策略

`register()` 的双写只是第一步。真正的双注册中心迁移需要完整的生命周期覆盖：

```java
@Override
public void deregister(Registration registration) {
    boolean eurekaOk = true, nacosOk = true;
    
    try {
        eurekaClient.deregister(eurekaInfo);
    } catch (Exception e) {
        eurekaOk = false;
        log.error("Eureka deregister failed", e);
    }
    
    try {
        nacosNamingService.deregisterInstance(...);
    } catch (Exception e) {
        nacosOk = false;
        log.error("Nacos deregister failed", e);
    }
    
    if (!eurekaOk && !nacosOk) {
        // 两边都失败：紧急告警。至少一个注册中心认为实例还活着
        alertManager.send("Dual-deregister both failed for " + serviceId);
    } else if (!eurekaOk || !nacosOk) {
        // 一边失败：打 warn + metrics 计数
        metrics.increment("dual_registry_deregister_partial_failure");
    }
}
```

**部分失败策略**：
| Eureka | Nacos | 策略 |
|---|---|---|
| 成功 | 成功 | 正常 |
| 成功 | 失败 | 打 metrics，Nacos 等 30s 心跳超时自动移除 |
| 失败 | 成功 | 打 metrics，Eureka 需要后台线程重试 deregister |
| 失败 | 失败 | 告警！两个中心都认为实例还在 |

### 8.1.2 灰度迁移的五阶段策略

```
阶段 1: 双写注册（100% 流量走 Eureka）
   → 确保 Eureka 摘除正常，Nacos 注册正常
   → 预期：Nacos 上有实例，但��流量进入

阶段 2: 双写 + 10% 流量走 Nacos（通过自定义 LoadBalancer 控制）
   → 观察 Nacos 实例的响应时间、错误率
   → 如果正常 → 进入阶段 3
   → 如果异常 → 回退到阶段 1

阶段 3: 双写 + 50% 流量走 Nacos
   → 负载测试确认容量足够

阶段 4: 双写 + 100% 流量走 Nacos
   → Eureka 只做 backup，不承载流量

阶段 5: 停止 Eureka 写入（只保留 Nacos）
   → 观察 7 天，无异常后完全下线 Eureka
```

**监控指标**：
- 双注册中心各自看到的实例数（应一致，差异 > 0 → 告警）
- Nacos 注册延迟 vs Eureka 注册延迟（p50/p99）
- 从 Nacos 实例接收的流量占比（阶段 4 应趋近 100%）
### 8.2 K8s metadata 注入

把 Pod 的信息自动注册到 Nacos：

```java
@Override
public void register(Registration registration) {
    Map<String, String> metadata = new HashMap<>(registration.getMetadata());

    // K8s Downward API 注入的环境变量
    metadata.put("podName", env.getProperty("HOSTNAME", "unknown"));
    metadata.put("k8sNamespace", env.getProperty("KUBERNETES_NAMESPACE", "unknown"));
    metadata.put("podIP", env.getProperty("POD_IP", "unknown"));
    metadata.put("nodeName", env.getProperty("NODE_NAME", "unknown"));

    instance.setMetadata(metadata);
    nacosNamingService.registerInstance(serviceId, group, instance);
}
```

运维在 Nacos 控制台直接看到每个实例属于哪个 Pod、哪个宿主机。

### 8.3 注册前健康门禁

```java
@Override
public void register(Registration registration) {
    // 检查数据库连通性
    if (!pingDatabase(3, TimeUnit.SECONDS)) {
        log.error("Database not reachable — refusing to register");
        return;  // 不注册 = 不给调用方看到 = 流量不进这个半残实例
    }

    // 检查 Redis 连通性
    if (!pingRedis(2, TimeUnit.SECONDS)) {
        log.error("Redis not reachable — refusing to register");
        return;
    }

    // 健康通过 → 正常注册
    nacosNamingService.registerInstance(serviceId, group, instance);
}
```

---

## 九、反模式

### 反模式 1：忘记端口 gap

```java
// ❌
@Override protected NacosRegistration getRegistration() { return this.registration; }
// 结果：register() 收到 port=-1 → Nacos 报错拒绝

// ✅
@Override
protected NacosRegistration getRegistration() {
    if (this.registration.getPort() < 0 && this.getPort().get() > 0) {
        this.registration.setPort(this.getPort().get());
    }
    return this.registration;
}
```

### 反模式 2：用 @Primary 硬盖 Bean

```java
// ❌ 试图用 @Primary 替换同一类型的 Bean
@Bean @Primary
public NacosServiceRegistry myRegistry(...) { ... }
// 为什么不工作：两个 Bean 都依赖同一个 NacosServiceManager，
// 旧 Bean 先创建的 NamingService 实例可能被污染

// ✅ 排除自动配置类，重建全部
// application.yml:
// spring.autoconfigure.exclude: NacosServiceRegistryAutoConfiguration
```

### 反模式 3: fail-fast=true 导致启动失败阻塞

```java
// ❌ failFast=true 时 register 抛异常 = 应用无法启动
if (properties.isFailFast()) {
    throw new RuntimeException("register failed", e);
}

// ✅ 生产建议：failFast=false
// 注册失败降级：用本地缓存继续服务（A 类-02 的缓存机制兜底）
```

---

## 十、验证

```
# 1. 启动时确认三个 Bean 都已替换
[CustomConfig] Creating NacosRegistration for custom use
[CustomConfig] Creating CustomServiceRegistry
[CustomConfig] Creating CustomAutoServiceRegistration

# 2. 确认端口解析正常
[CustomAutoReg] Port resolved: 28001
[CustomAutoReg] Registering via CustomServiceRegistry, port=28001

# 3. 确认自定义 metadata 已发送到 Nacos Server
curl "http://21.130.247.89:18848/nacos/v1/ns/instance/list\
?serviceName=nacos-lab&groupName=DEFAULT_GROUP&namespaceId=sca-lab-dev"
# → 响应中的 metadata 包含: startupTime, javaVersion, groupId

# 4. 确认摘除/恢复正常
curl -X POST http://localhost:28001/discovery/deregister
# → Nacos 控制台实例消失
curl -X POST http://localhost:28001/discovery/reregister
# → 恢复，metadata 中仍有自定义字段

# 5. 确认其他功能未受影响
curl http://localhost:28001/config/current
curl http://localhost:28001/config/all-sources
# → 配置中心功能正常
```
