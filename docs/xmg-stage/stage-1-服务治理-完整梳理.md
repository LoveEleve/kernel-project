# stage-1 服务治理完整梳理

> 基于小马哥（mercyblitz）Java 分布式架构训练营第一期"服务治理"24 章节逐章深度梳理。
> 原始技术栈：Spring Boot 2.7.4 + Spring Cloud 2021.0.4 + Dubbo 2.7.8 + Netflix OSS（部分过时）。
> 本文档目的：提取每章主题，映射到 2025 年现代技术栈，作为 sca-lab 学习实验场的规划基础。
> **核心原则**：stage-1 的所有主题都要在 sca-lab 中实践，过时技术用现代替代品学习同样的主题，不跳过任何主题。

---

## 一、stage-1 概览

| 维度 | 内容 |
|---|---|
| 章节数 | 24 节（12 周，每周 2 节） |
| 主题 | 服务治理全链路（工程化 → REST → 容错 → 负载均衡 → 可观测 → 网关 → 性能优化 → 脚手架） |
| 原始技术栈 | Spring Boot 2.7.4 + Spring Cloud 2021.0.4 + Dubbo 2.7.8 + Resilience4j 1.7.0 + Netflix OSS |
| 现代对应栈 | Spring Boot 3.5.13 + Spring Cloud 2025.0.2 + SCA 2025.0.0.0 + Java 21 |
| 功能域 | 9 个（工程构建 / REST / 容错 / 负载均衡 / 指标 / 链路 / 网关 / 性能 / 脚手架） |

---

## 二、按功能域归类的子主题清单

### 1. 工程构建域（第 01-02 章）

#### 第 01 章：基础框架工程构建

**核心主题**：企业 Java 应用的 Maven 工程结构与版本管理基础设施

**子主题清单**：
- Maven 基础（POM 项目对象模型、项目继承与项目聚合）
- Build Profiles 构建配置（开发/测试/生产多环境）
- 依赖机制与可传递依赖（依赖仲裁"最接近定义"、依赖管理、6 种作用域：compile/provided/runtime/test/system/import）
- 多模块层次与 BOM 设计
- 基础框架工程结构（基础设施 / 业务共享模块 / 业务应用 三层）
- 模块命名模式（精确型 -lang/-io 与模糊型 -core/-commons/-support/-context）
- 依赖冲突场景与解决（exclude 或直接声明）
- 依赖管理经验（BOM 版本兼容、低版本兼容、可选依赖动态判断、复制 API 而非整包依赖如 Dubbo 复制 netty、Spring 复制 ASM、shade 插件）
- ClassLoading 加载范围
- 基础框架版本策略（SNAPSHOT vs RELEASE）
- 三方库升级策略（Spring Boot Dependencies → Spring Cloud Build 链式升级）
- FAT JAR ClassPath 扩展（JarLauncher/WarLauncher）
- JVM Attach API

**现代对应**：Maven 3.9+/Gradle；Spring Boot 3.5 `spring-boot-dependencies` BOM；Maven Enforcer Plugin 依赖收敛；容器化时代 FAT JAR 价值降低

#### 第 02 章：业务工程模板定制

**核心主题**：基于 Spring 模块化思想定制企业业务工程模板

**子主题清单**：
- Spring 模块化设计（Framework/Boot/Cloud 模块组织关系）
- 业务工程模块化设计（高内聚低耦合、最小化 Artifact 提炼、package 命名规范）
- 业务组件 BOM 设计（xxx-dependencies 命名、继承或组合基础设施 BOM）
- 业务组件依赖管理（Spring Cloud 与 Spring Boot 兼容性矩阵，引用 start.spring.io application.yml mappings）
- spring-boot-maven-plugin repackage 排除间接依赖（如 lombok）
- 标准化项目结构（api 工程：接口/模型/常量/枚举/注解；data 工程：SQL-JDBC/MyBatis/JPA 与 NoSQL-Redis/Mongo/ES；core/biz/service；web 主工程）
- 业务工程脚手架（start.spring.io / start.aliyun.com / code.quarkus.io）

**现代对应**：Spring Boot 3.5 + Spring Cloud 2025.0.2 + SCA 2025.0.0.0；Spring Initializr；ArchUnit 模块边界校验

**sca-lab 实践**：
- `sca-lab-bom` 模块：BOM 继承组合设计 + 依赖冲突诊断
- `sca-lab-common` 模块：api/data/core/web 四层结构

---

### 2. REST API 域（第 03-04 章）

#### 第 03 章：REST API 服务端设计

**核心主题**：基于 Spring WebMVC 设计统一 REST API 模型与服务端机制

**子主题清单**：
- 服务端 API 模型设计（ApiBase<T>/ApiRequest<T>/ApiResponse<T> 泛型包装，headers/metadata/body 三段）
- API 业务 Code 设计（StatusCode 枚举模拟 HTTP Status Code，支持国际化占位符）
- 服务端 API 校验设计（Bean Validation 实现依赖 EL 2.0+；Spring 适配 LocalValidatorFactoryBean）
- 服务端 API 异常处理（@RestControllerAdvice/@ControllerAdvice；HandlerExceptionResolver）
- 服务端 API POJO 通讯（隐形包装 POJO T → ApiResponse<T>；HandlerMethodReturnValueHandler）
- Spring WebMVC 核心流程（DispatcherServlet → HandlerMapping → HandlerExecutionChain → HandlerAdapter → ModelAndView）
- 服务端 API 幂等性（Redis Token 校验、并发状态判断）
- 服务端多版本 API 实现（Spring WebMVC 平滑升级）

**现代对应**：Spring WebMVC 6.x；Problem Details (RFC 9457)；Spring Boot 3 `@RestControllerAdvice`；Redisson 分布式锁幂等

#### 第 04 章：REST API 客户端设计

**核心主题**：基于 RestTemplate/WebClient/OpenFeign 的客户端 API 整合

**子主题清单**：
- 客户端 API 校验（RestTemplate/WebClient/OpenFeign 整合 Bean Validation）
- Spring RestTemplate（JAX-RS WebClient 风格，HTTP 资源→POJO；Spring Cloud 负载均衡提升）
- RestTemplate 反向场景（WebMVC HttpMessageConverter 核心）
- 扩展点：HttpMessageConverter（HTTP 消息序列化反序列化）
- 扩展点：ClientHttpRequestFactory（底层 HTTP Client 通讯）
  - 拦截模式 InterceptingClientHttpRequestFactory（装饰器链）
  - 底层实现：JDK HttpURLConnection / Apache HttpComponents / OkHttp3
- 扩展点：ClientHttpRequestInterceptor（HTTP 请求执行拦截）
- 性能优化：序列化反序列化优化（FastJSON、减少 Converter 数量）
- 性能优化：HTTP Client 底层实现优化（HttpComponents、OkHttp3）
- Spring Template 类模式（命令模式 XXXTemplate 实现 XXXOperations）
- Spring WebClient
- Spring Cloud OpenFeign
- 客户端 API 异常处理（统一异常处理，预留国际化扩展）
- 客户端 API POJO 通讯（OpenFeign 隐形包装 POJO 为 API 模型）
- 客户端多版本 API 调用（Java 接口实现平滑升级）

**现代对应**：Spring 6 RestClient（替代 RestTemplate）；Spring WebClient；Spring Cloud OpenFeign 4.x；OkHttp 4.x；Apache HttpClient5

**sca-lab 实践**：
- `sca-lab-common`：统一 ApiResponse 模型 + @RestControllerAdvice 全局异常 + StatusCode 枚举
- `openfeign-lab`：RequestInterceptor 透传 Accept-Language、ClientHttpRequestInterceptor 装饰器链、多版本 API 调用
- `gateway-lab`：多版本 API 路由

---

### 3. 容错域（第 07-10 章）

#### 第 07 章：基于 Tomcat 实现 Web 服务容错性

**核心主题**：以 Tomcat 容器为切入点，从线程模型、连接器配置到限流手段实现 Web 层容错

**子主题清单**：
- Tomcat 设计意图与 Servlet/JSP/EL/WebSocket 规范参考实现地位
- Tomcat 目录结构（bin/conf/lib/logs/temp/webapps/work）
- AIO/NIO 网络模型与 Reactor（Boss→Worker）、JavaThread:NativeThread:VirtualThread = 1:1:N
- Tomcat 术语：Server/Service/Engine/Host/Connector/Context 六层
- 启动过程：Bootstrap 反射加载 Catalina
- **Tomcat 线程模型**：与 JDK ThreadPoolExecutor 行为差异（核心→Max→入队 vs JDK 核心→队列→Max）
- Tomcat 核心组件：网络连接 + 协议处理
- **Tomcat 限流**：限制 ThreadPoolExecutor min/max 线程数 + 限制 AbstractEndpoint#setMaxConnections
- **同步 vs 异步 Servlet**：Boss/Worker 同一线程 vs 兄弟线程；异步场景如 HTTP Long Poll 配置中心
- Spring Boot 整合：ServerProperties / ServerProperties.Tomcat / Accesslog / Threads
- Spring Boot 配置扩展：WebServerFactoryCustomizer、ConfigurableTomcatWebServerFactory、TomcatServletWebServerFactory
- **Tomcat Customizer 三层设计**：TomcatContextCustomizer / TomcatConnectorCustomizer / TomcatProtocolHandlerCustomizer
- getWebServer 源码剖析
- JVM 进程关闭三手段：System.exit / ShutdownHook / SpringBoot & Tomcat shutdown Endpoint
- **限流四层模式**：网关限流 / Web Server 限流 / Web Framework 限流 / 组件限流

**现代对应**：Spring Boot 3.x 内嵌 Tomcat 10.1（Jakarta EE 10）；虚拟线程（Java 21 Loom）替代传统 Boss/Worker；Spring Cloud Gateway 替代"网关限流"层；Sentinel/Resilience4j 替代"组件限流"层

#### 第 08 章：基于 Resilience4j 实现 Web 服务容错性

**核心主题**：Resilience4j 三件套（CircuitBreaker/Bulkhead/RateLimiter）核心 API 与 Servlet/WebMVC 整合

**子主题清单**：
- Resilience4j 函数式 API — decorateSupplier / decorateRunnable / decorateCheckedFunction，包装 JDK 8 Functional 接口
- CircuitBreakerConfig 构建器与 CircuitBreaker.of() 工厂
- **sliding window 双实现** — count-based（默认 100 次调用滑动窗口，Ring Bit Buffer）vs time-based（默认 10 秒时间桶，滑动时间片段聚合）
- **状态机** — CLOSED（正常放行）→ failureRateThreshold 触发 → OPEN（拒绝）→ waitDurationInOpenState 后 → HALF_OPEN（permittedNumberOfCallsInHalfOpenState 次试探）→ 成功率达标回 CLOSED，否则回 OPEN；另含 DISABLED / FORCED_OPEN 两个控制态
- **关键配置参数** — failureRateThreshold、slowCallRateThreshold、slowCallDurationThreshold、minimumNumberOfCalls、waitDurationInOpenState、recordExceptions / ignoreExceptions、automaticTransitionFromOpenToHalfOpenEnabled
- **Bulkhead 双模式** — Semaphore-based（DefaultBulkhead，maxConcurrentCalls 信号量许可 + maxWaitDuration 排队等待，轻量无独立线程池）vs Thread pool-based（FixedThreadPoolBulkhead，独立线程池 + queueCapacity 队列，强隔离避免雪崩但开销大）
- **RateLimiter 令牌桶** — limitForPeriod（每周期令牌数）、limitRefreshPeriod（补充周期，纳秒精度）、timeoutDuration（线程抢令牌超时）；底层基于 AtomicReference<ActiveLimiter> 在周期边界原子补充令牌
- Retry — maxAttempts、intervalFunction（指数退避 IntervalFunction.ofExponentialBackoff）
- TimeLimiter — 基于 CompletableFuture.timeout() 实现超时取消
- Decorators 链式组合 — Decorators.ofSupplier().withCircuitBreaker().withBulkhead().withRateLimiter().get()
- Servlet 整合 — HttpServletResilience4jFilter 在 Filter 层织入
- Spring WebMVC 整合 — @CircuitBreaker / @RateLimiter / @Bulkhead 注解 + AOP 切面
- WebFlux 整合（作业）— resilience4j-reactor 适配 Reactor 源

**现代对应**：Spring Cloud CircuitBreaker 抽象层 + Resilience4j 实现取代 Netflix Hystrix；Sentinel 在熔断/限流场景与 Resilience4j 并存；Spring Cloud Gateway RequestRateLimiter 整合 Redis RateLimiter

#### 第 09 章：Resilience4j 与第三方框架整合

**核心主题**：通过"寻找扩展点 + 拦截器"方法论，把 Resilience4j 嫁接到 OpenFeign / MyBatis / Redis 三类组件

**子主题清单**：
- **方法论**：学会寻找扩展点，尤其拦截器部分（Fault Tolerance / Tracing 两个应用方向）
- resilience4j-feign 官方模块 — Resilience4jFeign.builder() 自定义 Feign.Builder
- InvocationHandlerFactory 替换 — Feign 默认 Default → Resilience4jFeign 注入自定义工厂
- DecoratorInvocationHandler — 拦截 method invoke，按方法分发到 decorated CheckedFunction
- decorateMethodHandlers(Map<Method, MethodHandler>) — 遍历 dispatch 表，对每个 MethodHandler 调用 FeignDecorator.decorate()
- **FeignDecorator 装饰器接口** — decorate(CheckedFunction, Method, MethodHandler, Target) 单方法，可包装 CB/Bulkhead/RateLimiter/Retry
- **FeignDecorators 组合模式** — FeignDecorators.builder().addCircuitBreaker().addRateLimiter().build() 内部按 add 顺序构造责任链，每个 FeignDecorator 嵌套包装前一个 CheckedFunction，形成洋葱模型
- Resilience4jFeign 整合类 — 把 FeignDecorator 与底层 Client 串联
- MyBatis Interceptor 三方法 — intercept(Invocation) / plugin(Object) / setProperties(Properties)
- **plugin() vs intercept() 双路径**：
  - plugin() 路径：调用 Interceptor.super.plugin()（即 Plugin.wrap）生成 Executor 动态代理，每次方法调用先进入 intercept()
  - 静态 Wrapper 路径：在 plugin() 中直接 decorateExecutor() 返回包装实例，intercept() 不会被触发
  - 两种路径择一，否则会双重拦截
- Resilience4jMyBatisInterceptor — 仅对 Executor 装饰（StatementHandler/ParameterHandler/ResultSetHandler 不动）
- ExecutorDecorator 列表注入 — setDecorators() 外部配置多种装饰器组合
- **Redis Connection 拦截** — microsphere-spring-projects 的 RedisConnectionInterceptor
- 拦截点选择 — 拦截 RedisConnectionFactory.getConnection() 返回装饰后的 RedisConnection，使所有 RedisCallback 执行都受 Resilience4j 保护（在 Connection 获取层而非 RedisTemplate 层）
- 拦截点选择原则 — Feign 入口在 InvocationHandler、MyBatis 入口在 Executor、Redis 入口在 Connection 获取处

**现代对应**：OpenFeign 内置 spring.cloud.openfeign.circuitbreaker.enabled=true + Spring Cloud CircuitBreaker；MyBatis-Plus Interceptor 链；Spring Data Redis 仍无原生断路器，需自实现或集成 Resilience4j-Spring

#### 第 10 章：服务容错动态变更

**核心主题**：基于 Spring Cloud Config 机制实现 Tomcat / Resilience4j 组件运行时动态调整

**子主题清单**：
- **Spring Cloud Config 动态变更**：理解与 @ConfigurationProperties Bean 动态绑定的关系
- 三种实现路径对比：
  - 配置客户端原生 API（Nacos/Apollo/Consul）— 性能高但代码迁移差
  - 基于 Spring 实现（Nacos Spring / Apollo Spring）
  - 基于 Spring Cloud Config 实现
- **@RefreshScope 机制**
  - 基于 Spring Bean Scope（Web Request/Session/Application/ThreadLocal）
  - Bean 共享（singleton/prototype）
  - 使用场景：@ConfigurationProperties
- **ConfigurationPropertiesRebinder**：触发条件 EnvironmentChangeEvent
- **EnvironmentChangeEvent 使用场景**：动态日志级别变更 + @ConfigurationProperties Bean 属性重新绑定
- 动态 Tomcat 组件更新
- 动态 Resilience4j 组件更新
- 关联：Apollo / Nacos（对标 Consul）
- 阿里内部技术栈：Diamond / ConfigServer / HSF / Dubbo / SOFA / Webx
- **日志 MXBean**：Jolokia HTTP 桥接 → LoggingMXBean → Logback > Log4j2 > Log4j > Java Logging
- 日志级别变更三手段：LoggingMXBean / EnvironmentChangeEvent→LoggingRebinder / LoggersEndpoint
- 实现优化：配置中心推送 → EnvironmentChangeEvent / Spring Cloud Bus Remote Event
- Spring Bean 销毁三方式：DisposableBean / @PreDestroy / @Bean(destroyMethod)

**现代对应**：Nacos 2.x / Apollo 2.x；Spring Cloud 2024+ 中 @RefreshScope 仍为核心；Spring Cloud Bus 仍基于 Stream；Kubernetes ConfigMap + Spring Cloud Kubernetes 提供云原生替代

**sca-lab 实践**：
- `sentinel-lab`：Sentinel 流控/熔断/降级 + 对比 Resilience4j sliding window
- `circuitbreaker-lab`（可选）：Resilience4j 与 Sentinel 对比
- `nacos-lab`：@RefreshScope + EnvironmentChangeEvent + 动态日志级别 + 动态 Resilience4j 配置（Nacos 替代 Spring Cloud Config）
- `gateway-lab`：限流四层架构（网关限流层 + RequestRateLimiter）

---

### 4. 负载均衡域（第 11-12 章）

#### 第 11 章：基于监控指标的负载均衡实现

**核心主题**：JMX 核心指标采集 + Netflix Eureka 服务发现，为后续指标驱动负载均衡铺底

**子主题清单**：
- 核心监控指标定义 — CPU 使用率、Load（1/5/15 分钟）、线程状态、RT、QPS、TPS
- JMX 工厂 — ManagementFactory.getPlatformMXBean()
- **JMX 9 大 MXBean**：
  - OperatingSystemMXBean — 系统级 Load average、availableProcessors
  - MemoryPoolMXBean — Heap/Non-Heap 各区域（Eden/Survivor/Old/Metaspace/Code Cache）使用量与峰值
  - MemoryManagerMXBean — 内存管理器名称与所辖内存池
  - GarbageCollectorMXBean — GC 次数（getCollectionCount）与累计耗时（getCollectionTime）
  - ClassLoadingMXBean — loaded/totalLoaded/unloaded 类数
  - MemoryMXBean — Heap/Non-Heap 整体使用、finalization 队列
  - ThreadMXBean — 线程数、daemon 数、死锁检测、CPU 时间、各状态（RUNNABLE/BLOCKED/WAITING/TIMED_WAITING）
  - RuntimeMXBean — 启动参数、classpath、uptime、JVM name
  - CompilationMXBean — JIT 编译器名称、totalCompilationTime
- Hotspot 扩展 — com.sun.management.OperatingSystemMXBean.getProcessCpuLoad() 返回 [0.0,1.0] 进程 CPU 占比；另含 getSystemCpuLoad、getFreePhysicalMemorySize
- **指标来源 4 层**：
  - JMX — JVM Memory（Heap/Non-Heap）、CPU、Load、Threading（JVM 进程内）
  - JNI — 调用本地接口采集 OS 级指标
  - FileSystem — Unix/Linux /proc/stat（CPU）、/proc/net/dev（网络）、/proc/meminfo（物理内存）
  - CGroup — /sys/fs/cgroup/cpu 与 memory 子系统，采集容器 CPU 配额与内存 limit（容器化场景必需，否则 JMX 看到的是宿主机值）
- 指标收集方案 — Jolokia（JMX HTTP Bridge，已淘汰）/ Netflix Servo（已淘汰）/ Micrometer（现代主流）/ Spring Metrics / Prometheus / Pinpoint / Skywalking / Zipkin
- 指标存储 — ElasticSearch / Prometheus Server / OpenTSDB / InfluxDB / Redis / HBase / MySQL
- **Eureka Server REST API** — Netflix 原生 /eureka/v2/apps/，Spring Cloud 改为 /eureka/apps/（**过时 → Nacos 替代**）
- **Eureka 实例元数据字段展开**（**过时 → Nacos metadata 替代**）：
  - instanceId — host:app:port 唯一标识
  - hostName / ipAddr / app / status（UP/DOWN/STARTING/OUT_OF_SERVICE/UNKNOWN）/ overriddenstatus
  - port / securePort — HTTP/HTTPS 端口及 enabled 标志
  - dataCenterInfo — 数据中心（MyOwn 或 Amazon）
  - leaseInfo — renewIntervalInSecs（默认 30s 心跳）、durationInSecs（默认 90s 过期）、registrationTimestamp / lastRenewalTimestamp / evictionTimestamp / serviceUpTimestamp
  - metadata — 自定义 map（如 management.port），可注入指标
  - homePageUrl / statusPageUrl / healthCheckUrl — Web 端点
  - vipAddress / secureVipAddress — VIP 寻址
  - lastUpdatedTimestamp / lastDirtyTimestamp / actionType（ADDED/MODIFIED/DELETED）
- **Eureka Client 轮询机制** — 全量订阅 + 周期性 registryFetchIntervalSeconds（默认 30s）拉取；首次全量，后续 fetch 返回 delta 字段做增量合并
- Eureka 自我保护 — Renews 低于阈值进入 Self-Preservation 不再剔除实例

**现代对应**：Micrometer + Prometheus + Grafana 取代 Servo/Jolokia；**Nacos 注册中心取代 Eureka**（保留 metadata map 但 cluster/namespace 取代 vipAddress）；Spring Cloud LoadBalancer + Micrometer 驱动 WeightedResponseTimeRule 算法

#### 第 12 章：动态权重负载均衡

**核心主题**：基于 Netflix Servo 监控指标 + Netflix Ribbon 实现 WeightedResponseTimeRule 动态权重负载均衡

**子主题清单**：
- **Netflix Servo**（**过时 → Micrometer 替代**）
  - 设计三原则：Leverage JMX / Keep It Simple / Flexible Publishing
  - Metrics 数据源类型：GAUGE（瞬时值）/ COUNTER（单调递增）/ INFORMATIONAL（非数值）
- 上报监控指标：基于 Spring Cloud 服务注册接口
- 负载均衡实现：基于 Servo 指标实现 Ribbon 负载均衡策略
- **Netflix Ribbon 详解**（**过时 → Spring Cloud LoadBalancer 替代**）
  - Spring Cloud 场景：@FeignClient → Ribbon Client 上下文，每个服务独立 ApplicationContext
  - Ribbon 特性：独立 Spring 上下文 + IClientConfig 网络参数
  - RibbonClientConfiguration 配置类
  - IClientConfig / IClientConfigKey / CommonClientConfigKey
  - **IRule 负载均衡规则**
    - RandomRule 随机
    - RoundRobinRule 轮询
    - **WeightedResponseTimeRule**：权重响应时间，含完整伪代码（4 endpoint A=10/B=30/C=40/D=20，按累计权重划分区间，统计不足时回退 RoundRobin）
  - @RibbonClient 注解
  - ServerList：getInitialListOfServers / getUpdatedListOfServers
  - DiscoveryEnabledNIWSServerList 整合 DiscoveryClient
  - ServerListUpdater：PollingServerListUpdater，与 Eureka Client 30s 轮询时序对比
  - PropertiesFactory 自定义配置（<clientName>.ribbon.NFLoadBalancerRuleClassName 等 5 个 Key）
  - ILoadBalancer：默认 ZoneAwareLoadBalancer
  - **Servo 整合**：LoadBalancerStats 注册到 Servo
  - **OpenFeign 整合**：LoadBalancerFeignClient，注意 Ribbon 与 Feign Client 超时设置层级关系

**现代对应**：**Netflix Ribbon/Servo 已彻底停止维护 → Spring Cloud LoadBalancer（基于 ReactorLoadBalancer）+ Micrometer**；Spring Cloud LoadBalancer 提供 RoundRobinLoadBalancer / RandomLoadBalancer，但**无内置 WeightedResponseTimeRule — 这正是 sca-lab 可填补的空白**

**sca-lab 实践**：
- `loadbalancer-lab`：**手写 WeightedResponseTimeRule 算法**（在 Spring Cloud LoadBalancer 中自行实现 ServiceInstanceListSupplier + 指标采集）+ ServerListUpdater 时序对比
- `nacos-lab`：Nacos 替代 Eureka 做注册发现
- `micrometer-lab`：9 个 MXBean + 指标来源分层（迁移到 Micrometer MeterRegistry）

---

### 5. 可观测-指标域（第 13-16 章）

#### 第 13 章：Micrometer 基础

**核心主题**：Micrometer 指标门面 API 的核心概念与内建 Binder

**子主题清单**：
- 指标分类：系统指标（CPU/内存/JVM/GC/RT）vs 业务指标（成交量/转化率）
- 聚合维度：mean/max/min/p50/p95/p99/累计 + 时序数据库存储
- `Meter` 接口：id = name + tags + baseUnit + type，所有指标抽象根
- `Counter`：单调递增，`Counter.builder("name").tag(k,v).register(registry)` + `increment()`；场景：错误数、订单量
- `Timer`：持续时间 + 计数，`timer.record(Duration)` / `Timer.Sample.start(registry).stop(timer)` / `timer.record(() -> {…})`；场景：HTTP RT、DB 耗时
- `Gauge`：瞬时值，`Gauge.builder("name", obj, valueFunction)` 或 `registry.gauge("name", atomicLong)`；场景：线程池活跃数、连接池占用
- `DistributionSummary`：分布统计（非时长），`summary.record(amount)`；场景：响应 body 大小、批处理条数
- `MeterRegistry`：注册发布根接口；`SimpleMeterRegistry` 内存实现用于测试
- `CompositeMeterRegistry`：将同一指标路由到多后端（Prometheus + InfluxDB + JMX），子注册树状组合
- 内建 `MeterBinder`：`JvmMemoryMetrics` / `JvmGcMetrics` / `JvmThreadMetrics` / `JvmClassLoadingMetrics` / `JvmCompilerMetrics` / `LogbackMetrics` / `TomcatMetrics` / `KafkaClientMetrics` / `KafkaStreamsMetrics` / `HikariDataSourceMetrics` / `ExecutorServiceMetrics` / `ProcessorMetrics` / `UptimeMetrics` / `DiskSpaceMetrics`（Spring Boot Actuator 自动绑定大部分）
- `@Timed` 注解：方法级 AOP 计时，需注册 `TimedAspect` Bean，支持 `extraTags` / `percentiles` / `histogram` / `serviceLevelObjectives`
- `@Counted` 注解：方法计数（含异常计数），`recordFailures=ALWAYS/ON_EXCEPTION/NEVER`
- Tags/Tag 命名规范：name 用 `.` 分隔（`http.server.requests`），Tag key 避免与 Prometheus label 冲突
- BaseUnit：`BaseUnits.BYTES` / `BaseUnits.MILLISECONDS`，Prometheus 自动转 nanoseconds
- 直方图与分位：`publishPercentiles` / `publishPercentileHistogram` / `minimumExpectedValue` / `maximumExpectedValue` / `serviceLevelObjectives`，控制 SLO 指标

**现代对应**：Micrometer 1.13+ / Spring Boot 3.5 Actuator（**Observation API 已统一 metrics + tracing**）；Netflix Servo → Micrometer；Atlas → Micrometer Atlas Registry

#### 第 14 章：Micrometer 整合第三方框架

**核心主题**：把第三方框架（Ribbon/Redis/MyBatis/JDBC）的内部指标桥接到 Micrometer

**子主题清单**：
- Ribbon Servo → Micrometer 适配（**Ribbon 部分过时**）
- JMX / ObjectName
- Jolokia（JMX over HTTP）
- Redis Spring 整合（API 指标注册到 MeterRegistry）
- MyBatis Plug-in 整合
- JDBC 包装框架：Alibaba Druid、P6Spy
- JDBC API 抽象工厂层次（DataSource → Connection → Statement → ResultSet）
- JDBC Wrapper/装饰器模式 + unwrap 正确用法
- JDBC 监控指标四层拆解：连接获取时间、JDBC 实际执行时间、SQL 执行时间、数据传输时间、数据库事务时间、MyBatis 语句翻译/缓存、其他代码执行时间

**现代对应**：OpenTelemetry JDBC Instrumentation + P6Spy + HikariCP Metrics；LettuceMetrics / JedisMetrics 自动绑定

#### 第 15 章：Pull 方式指标监控平台

**核心主题**：Prometheus + Eureka SD + Grafana 的 Pull 模式监控平台

**子主题清单**：
- Actuator + `micrometer-registry-prometheus` 依赖；Spring Boot 3.4+ 已默认使用 micrometer-registry-prometheus（无需 simpleclient 版本）
- `management.endpoints.web.exposure.include=health,info,prometheus`，`management.metrics.export.prometheus.enabled=true`
- `/actuator/prometheus` Endpoint：以 `# TYPE`/`# HELP` 头 + Prometheus text exposition 格式输出，每次抓取动态计算
- `management.metrics.export.prometheus.step=30s`：直方图桶聚合粒度，需与 `scrape_interval` 对齐
- `management.server.port=` 分离管理端口，避免业务端口暴露 metrics
- Eureka 服务发现 `eureka_sd_configs`：从注册中心拉取应用实例列表（**Eureka SD 过时 → Nacos/K8s SD**）
- **`relabel_configs` 三类用法**：
  - 过滤 keep/drop：`__meta_eureka_app_instance_metadata_prometheus_scrape=true` 仅抓元数据中标注 scrape 的实例
  - 重写路径：`__meta_eureka_app_instance_metadata_prometheus_path` → `__metrics_path__`
  - 重写地址：拼接 IP + `metadata.prometheus.port`，避免业务端口与管理端口混淆
- Eureka metadataMap 三字段配置：`eureka.instance.metadataMap.prometheus.{scrape,path,port}`（**→ Nacos metadata 对应字段**）
- Prometheus 标签注入：`instance` / `application` / `env`，便于多环境区分
- Grafana 数据源：Prometheus URL + Basic Auth + scrape interval 与 step 一致；dashboard 使用 `${application}` 变量按标签过滤
- `scrape_interval` 与 `evaluation_interval`：与 Micrometer step 对齐避免数据空洞
- Alertmanager 告警规则：基于 PromQL（`rate` / `histogram_quantile` / `absent`）触发

**现代对应**：Prometheus 2.5x + **Nacos/Consul/K8s SD**（替代 Eureka SD，`nacos_sd_configs` + `__meta_nacos_instance_metadata_prometheus_*`）+ Grafana 11 + Alloy；K8s 场景用 Prometheus Operator + ServiceMonitor CRD

#### 第 16 章：Push 方式指标监控平台

**核心主题**：Pushgateway + InfluxDB + Pull/Push 混合模式

**子主题清单**：
- Pushgateway 适用场景：Cron Job / 短生命周期 Pod / Serverless 函数，进程随时退出无法被抓
- Pushgateway 单实例无水平扩展（内存存储），多实例 + DNS SRV + Nginx upstream 反向代理实现"伪集群"
- DNS SRV 记录 `_pushgateway._tcp.pushgateway.acme.com`：Nginx `resolver` + `proxy_pass` 动态解析后端列表
- Nginx 反向代理配置：`upstream pushgateway-cluster` + `least_conn` 负载策略 + `X-Prometheus-Pushgateway-Cluster` 头标识
- Pushgateway 作为 Prometheus Job：`job_name: "prometheus-pushgateway"` + `static_configs` 指向 Nginx VIP
- **`honor_labels: true`**：避免 Prometheus 用自身 `job`/`instance` 覆盖应用推送的标签（Pushgateway 的 `instance` 是其自身 IP，会丢失原始实例信息）
- 应用侧依赖 `simpleclient_pushgateway` + Micrometer 配置：
  - `management.metrics.export.prometheus.pushgateway.enabled=true`
  - `baseUrl=http://pushgateway.acme.com`
  - `pushRate=10s`（推送频率）
  - `job=${spring.application.name}-metrics-push-job`
  - `pushRate` vs Micrometer `step`：前者推送频率，后者本地聚合窗口
- Micrometer `PrometheusPushGatewayMeterRegistry` 底层：基于 `io.prometheus.simpleclient_pushgateway.PushGateway`，`@Scheduled` 定时推送所有指标
- `groupingKey`：同一 job 下区分实例（如 `instance=${HOSTNAME}`），避免 Pushgateway 覆盖
- `Micrometer InfluxDB 注册中心`：`InfluxMeterRegistry` 通过 `influxdb-java` 客户端 HTTP 写入 InfluxDB v1/v2；与 Prometheus 差异：InfluxDB 是 push native 存储引擎，Prometheus 是抓取 + 存储一体
- 多后端混合：`CompositeMeterRegistry` 同时注册 Prometheus Pull + Pushgateway + Influx，针对不同部署模式（云原生 Pull + 边缘节点 Push + 长期存档 Influx）
- Push + Pull 混合模式：核心服务 Pull（健康检查保证可达），批处理/Cron Push（短任务），中央 Prometheus 同时配置 `eureka_sd_configs` 拉取 + `static_configs` 拉 Pushgateway
- Pushgateway 数据失效：默认无 TTL，重启丢失所有数据；K8s 场景下更适合 Prometheus Agent Mode（2.32+）+ Remote Write
- 防止指标污染：`honor_labels` + `metric_relabel_configs` 过滤无效 metric

**现代对应**：Pushgateway 1.6 + OpenTelemetry Collector + OTLP 协议（Push 模式新主流）；K8s 场景 Prometheus Agent Mode + Remote Write 到 Mimir/Thanos/VictoriaMetrics；InfluxDB v1 → InfluxDB 3.0（Apache Arrow + Parquet）

**sca-lab 实践**：
- `micrometer-lab`：自定义 MeterBinder 注册业务指标、DistributionSummary 记录请求大小、Timer 记录方法耗时
- `nacos-lab` 内特性：Nacos 作为 Prometheus SD（替代 Eureka SD）
- `sentinel-lab` 内特性：Sentinel Metrics 与 Micrometer 整合

---

### 6. 可观测-链路域（第 17-18 章）

#### 第 17 章：基于 Java 应用层追踪服务链路

**核心主题**：Spring Cloud Sleuth（Fork OpenZipkin Brave）应用层 Tracing（**Sleuth 已废弃 → Micrometer Tracing + OpenTelemetry**）

**子主题清单**：
- Sleuth 核心 API：Tracer、Span、TraceContext、SpanInScope（**Sleuth 在 Spring Cloud 2022.0 已 EOL**）
- Sleuth Instrument = Fork OpenZipkin Brave
- Trace ID 传输：HTTP 请求头（X-B3-TraceId）/ RPC 上下文（Dubbo RpcContext）（**→ W3C traceparent 头**）
- 日志扩展（MDC 注入 traceId/spanId）
- HttpClient/HttpServer Handler 整合
- OpenFeign 整合（TracingFeignClient 包装 feign.Client）
- Spring WebMVC 整合三层：SpanCustomizingHandlerInterceptor（拦截器）+ TracingFilter（Servlet Filter）+ TraceValve（Tomcat Valve）
- TraceValve vs TracingFilter 协作机制：TraceValve 提前于所有 Filter 创建 Span
- 自定义 Span 代码示例：tracer.withSpan + nextSpan().name() + tag() + event() + end()
- 可拦截框架清单：WebMVC/WebFlux、OpenFeign、JDBC/P6Spy、MyBatis Interceptor、Redis RedisConnection

**现代对应**：**Micrometer Tracing 1.3 + OpenTelemetry SDK + Zipkin/Tempo/Jaeger 后端 + W3C Trace Context**（替代 Sleuth + Brave）

#### 第 18 章：基于 Java Instrument 追踪链路重构

**核心主题**：用 Java Instrument + 字节码增强重写链路追踪（取代应用层 Wrapper）

**子主题清单**：
- Java Instrument 机制（premain/agentmain + ClassFileTransformer）
- Java ClassLoading 三方面：Class Loading / Class Definition / Resource Loading
- Java Reflection 元信息：Class/Field/Constructor/Method；Java 8 永久代 → Java 8+ Metaspace（堆外）
- ClassLoader 单例性：同全类名 Class 在不同 ClassLoader 是不同对象（Spring Boot devtools 经典问题）
- 反射可访问性：编译时检查 vs 运行时 AccessController 检查
- Java Dynamic Proxy 深度：Proxy.getProxyClass0 → WeakCache → ProxyClassFactory（命名前缀 $Proxy）→ ProxyGenerator.generateClassFile
- $Proxy32 extends Proxy implements RedisConnection 生成的代理类结构
- Java 注解实现本质（$Proxy6 extends Proxy implements ContextConfiguration，注解也是动态代理）
- sun.misc.ProxyGenerator.saveGeneratedFiles 保存生成字节码
- Java Agent 实战产品：JProfiler、Pinpoint、Skywalking
- Byte Buddy（运行时字节码生成与修改）
- ASM（字节码"鼻祖"）/ CGLIB / Javassist
- Java Tool API：Java Compiler（Dubbo Swagger API 自动生成）
- Java Annotation Processor（APT）：javax.annotation.processing.Processor；Dubbo dubbo-metadata-processor、Spring spring-context-indexer、Spring Boot spring-boot-configuration-processor
- Java Debugger API（JDB）
- Java Tool Interface（JVMTI）
- Web 服务链路重构（OpenFeign + WebMVC via 字节码）
- 第三方服务链路重构（Redis、JDBC、MyBatis）

**现代对应**：**OpenTelemetry Java Agent 2.x（基于 Byte Buddy）+ SkyWalking 10.x Java Agent**；sun.misc.ProxyGenerator 在 JDK 17+ 已迁移到 java.lang.reflect.ProxyGenerator

**sca-lab 实践**：
- `tracing-lab`：**Micrometer Tracing + OpenTelemetry**（不用 Sleuth），覆盖 W3C Trace Context 传播、OpenFeign + WebMVC 跨服务 Span、自定义 @Observed/@NewSpan、Baggage 业务字段透传、对接 Tempo/Jaeger
- 理论层：理解 premain vs agentmain、Byte Buddy Advice/MethodDelegation 区别、ClassLoader 单例性导致 devtools 陷阱

---

### 7. 网关域（第 19-20 章）

#### 第 19 章：服务网关稳定性设计

**核心主题**：Spring Cloud Gateway 的核心概念、路由机制与 Web 通用安全技术

**子主题清单**：
- Route/Predicate/Filter 三大核心概念（Route = id+uri+predicates+filters；Predicate = Java 8 Predicate<ServerWebExchange>；Filter = GatewayFilter）
- Route Predicate Factories：AfterRoutePredicateFactory/BeforeRoutePredicateFactory，函数式规范 PredicateSpec
- GatewayFilter Factories：AddRequestHeader/AddRequestParameter，函数式规范 GatewayFilterSpec
- RouteLocator 路由定位器（RouteDefinitionRouteLocator 依赖 GatewayProperties/GatewayFilterFactory/RoutePredicateFactory/RouteDefinitionLocator）+ NameUtils.normalizeRoutePredicateName 命名规则
- RouteDefinitionLocator：CompositeRouteDefinitionLocator（组合）+ DiscoveryClientRouteDefinitionLocator（服务发现，spring.cloud.gateway.discovery.locator.enabled）
- RoutePredicateHandlerMapping：基于 WebFlux DispatcherHandler SPI 的 HandlerMapping
- 核心执行链：DispatcherHandler → HandlerMapping(RoutePredicateHandlerMapping) → RouteLocator → RoutePredicateFactory/GatewayFilterFactory
- Web Security：Tomcat CSRF/CORS、Spring WebMVC CorsFilter、Spring Security、Gateway SecureHeadersGatewayFilterFactory
- 小作业：网关容错（Resilience4j 熔断限流）/ 网关柔性（柔性 LoadBalancer）

**现代对应**：Spring Cloud Gateway 2025.0.x（**-webflux/-webmvc 双形态**）、Spring Security 6.x、Resilience4j、Spring Cloud LoadBalancer

#### 第 20 章：服务网关可观测性设计

**核心主题**：Spring Cloud Gateway 微观架构（路由定义接口）+ 监控 + 链路跟踪

**子主题清单**：
- RouteDefinition 接口设计（id/predicates/filters/uri/metadata）
- PredicateDefinition（name + args，如 After=2017-01-20T...）
- FilterDefinition（name + args，如 AddRequestHeader=X-Request-red, blue）
- RouteDefinitionLocator SPI 设计策略：集合依赖是否有序/可重复、Composite 实现 + @Primary 模式
- RouteDefinitionRouteLocator：核心成员 + getRoutes() 方法（onErrorContinue 容错 + debug 日志）+ 被 RoutePredicateHandlerMapping.lookupRoute 调用
- RoutePredicateHandlerMapping.getHandlerInternal：管理端口隔离（DIFFERENT）、GATEWAY_HANDLER_MAPPER_ATTR/GATEWAY_ROUTE_ATTR 属性传递、switchIfEmpty 未匹配处理
- 对比 Spring WebMVC：FilteringWebHandler ≈ HandlerExecutionChain，GatewayFilter ≈ HandlerInterceptor，MappedInterceptor 对应关系
- 完整请求执行过程：HTTP Request → DispatcherHandler → RoutePredicateHandlerMapping → FilteringWebHandler → SimpleHandlerAdapter → GatewayFilter + GlobalFilter 迭代
- 网关监控设计：Gateway 整合 Micrometer 指标
- 网关链路跟踪设计：Gateway 整合 Spring Cloud Sleuth（**→ Micrometer Tracing**）

**现代对应**：Micrometer 1.13 + Prometheus + Grafana（监控）；**Spring Cloud Sleuth 已废弃 → Micrometer Tracing + OpenTelemetry + Zipkin/Tempo**（链路）

**sca-lab 实践**：
- `gateway-lab`：Route/Predicate/Filter 三层 SPI、PredicateSpec/GatewayFilterSpec 函数式 DSL、服务发现路由、SecureHeaders/CORS/CSRF 三道安全门、Gateway + Resilience4j 熔断限流
- `gateway-lab` 可观测性特性：Gateway + Micrometer 指标、Gateway + Micrometer Tracing/OpenTelemetry

---

### 8. 性能优化域（第 21-22 章）

#### 第 21 章：Spring Web 性能优化

**核心主题**：用静态代理替换 Spring AOP、Web 组件精简、REST 序列化优化、Java Native 化

**子主题清单**：
- Spring AOP 优化：在 Web 场景用静态代理替换 AOP 代理
- Spring AOP 三大场景：事务（@Transactional/PlatformTransactionManager/TransactionInterceptor/隔离级别源于 JDBC/传播源于 EJB+Savepoint）；缓存（@Cacheable/CacheOperationSource/CacheInterceptor）；自定义注解（@Idempotent 幂等/@Authorized 鉴权/@TokenGenerated）
- Spring Web 组件优化：精简非必需组件，用 WebMVC 特性减少 AOP
- HandlerInterceptor 静态代理体系：DelegatingMethodHandlerInterceptor（门面，1→N）→ MethodHandlerInterceptor（仅处理 HandlerMethod）→ AnnotatedMethodHandlerInterceptor（基于注解）
- HandlerMethodArgumentsResolvedEvent（基于 HandlerMethodArgumentResolver 装饰器）
- Spring Web 缓存优化：StoringHandlerMethodArgumentRequestBodyAdvice（Request Body 缓存）+ StoringHandlerMethodReturnValueResponseBodyAdvice（Response Body 缓存）
- REST 序列化优化：ContentCachingFilter（Response 内容缓存过滤器）
- Java 语言发展：强封装（Java 9 模块化）、强类型（Record）、语法特性、Native 化（C1/C2 编译 + GC 算法 vs GraalVM C1/C2 + 有限 GC）
- 非通用计算性（CPU 架构优化）
- 技术栈对比：Java EE 1-8 / Jakarta EE 9+ → Quarkus / MicroProfiles；Spring Reactive；Eclipse Vert.x
- 优化策略：启动期初始化数据、利用只读性用非线程安全集合、减少 native 方法调用

**现代对应**：Spring Boot 3.5 + Java 21 + GraalVM AOT（Spring Native）；Record；Spring AuthorizationServer

#### 第 22 章：Spring Cloud 性能优化

**核心主题**：@RefreshScope 弊端与替代、OpenFeign 性能优化、Bootstrap 上下文废弃

**子主题清单**：
- @RefreshScope 优化：替换实现减少上下文停顿
- Spring Bean Scope 背景：内建 singleton/prototype，扩展 RequestScope/SessionScope/RefreshScope
- Spring Scope 设计模式：注解 + @Scope 元标注 + ScopedProxyMode.TARGET_CLASS 代理包装（ScopedProxyCreator.createScopedProxy）
- **RefreshScope vs ConfigurationPropertiesRebinder**：相同点（操作配置 Bean、注解形式、IoC 管理、@ManagedResource、特定 Bean 特征、destroy）；不同点（RefreshScope 仅 destroy，Rebinder destroy+init；同时标注时 Rebinder 排除；Rebinder 由 EnvironmentChangeEvent 触发，RefreshScope 需外部触发；RefreshScope 发布 RefreshScopeRefreshedEvent）
- @RefreshScope 弊端：Bean 转 AOP 代理 → 反射调用开销；类层次性不兼容；仅 destroy 不 init 可能引发不确定影响；对配置 Bean 远不如 ConfigurationPropertiesRebinder
- OpenFeign 优化：REST 序列化（SpringEncoder + HttpMessageConverter + FastJSON/精简 Converter）；HTTP 客户端（HTTP Components / OkHttp3 替代默认）；负载均衡（避免 Ribbon 低效 ServerListUpdater/ZoneAwareLoadBalancer）；HTTP 压缩；超时（连接超时 + 请求超时）
- Spring Cloud 配置优化：失效 Bootstrap（spring.cloud.bootstrap.enabled=false）
- Bootstrap 背景与弊端：BootstrapApplicationListener 监听 ApplicationEnvironmentPreparedEvent → 创建 bootstrap 子上下文；PropertySource 名 "bootstrap"；弊端（事件多层传播重复处理、Stream/Integration 整合问题、属性优先级过高排查困难、增加启动时间）
- 优化配置读取：StandardEnvironment + Java System Properties
- 作业：动态替换 Java System Properties 避免并发锁

**现代对应**：**Bootstrap 已废弃**（Spring Cloud 2022+，application.yml + spring.config.activate.on-profile）；**Netflix Ribbon 已 EOL → Spring Cloud LoadBalancer**；**FastJSON 不推荐**（安全漏洞）→ Jackson；@RefreshScope 仍存在但配合 Nacos ContextRefresher

**sca-lab 实践**：
- `nacos-lab` 配置中心特性：@RefreshScope 原理与弊端对比、ConfigurationPropertiesRebinder 机制、Bootstrap 废弃验证
- `openfeign-lab` 五维特性：序列化优化、HTTP 客户端切换（Apache HttpClient/OkHttp）、HTTP 压缩、超时配置、负载均衡策略
- `performance-lab`（可选）：AOP 静态代理、Web 缓存、GraalVM AOT、通用注解（@Idempotent/@Authorized/@TokenGenerated）

---

### 9. 脚手架域（第 23-24 章）

#### 第 23 章：Spring 脚手架运用、架构与定制

**核心主题**：Spring Initializr 工程搭建、模块架构与定制

**子主题清单**：
- Spring 脚手架搭建：本地/测试环境 + CI/CD 注意事项
- GUI 工程 start.spring.io：start-client（React JS 前端）、start-site（后端依赖 initializr）、start-site-verification
- 初始化器 initializr 9 个模块：actuator/bom/docs/generator/generator-spring/generator-test/metadata/service-sample/version-resolver/web
- 核心概念：ProjectGenerator、ProjectDescription（coordinates/BuildSystem/Packaging/JVM Language/dependencies/platformVersion/applicationName/packageName/baseDirectory）、ProjectGenerationContext（子应用上下文）、ProjectGenerationInvoker、ProjectGenerationResult、InitializrMetadata、InitializrProperties
- @ConditionalOn 条件注解家族：@ConditionalOnBuildSystem/@ConditionalOnLanguage/@ConditionalOnPackaging/@ConditionalOnPlatformVersion/@ConditionalOnRequestedDependency + ProjectGenerationCondition
- ProjectContributor 项目构建器：HelpDocumentProjectContributor（HELP.md）、MavenWrapperContributor（maven/wrapper）、MavenBuildProjectContributor（pom.xml）
- Spring 脚手架架构：start.spring.io 与 initializr 关系、模块职责
- Spring 脚手架定制：BOM + 业务组件定制模块

**现代对应**：start.spring.io（10.x）+ initializr 0.20+；CI/CD 中 Initializr 私服

#### 第 24 章：Spring 脚手架原理、实现与扩展

**核心主题**：Spring Initializr 实现原理、Maven 代码生成、多模块扩展

**子主题清单**：
- 脚手架原理：项目元信息 + Project 子应用上下文 + Web Endpoints
- 脚手架实现：Maven 构建系统 + 代码生成 + 元信息处理
- 脚手架扩展：多模块 + 动态 Maven 业务模板
- 核心 API：ProjectRequest、ProjectGenerationController（Web Endpoints）、ProjectGenerationInvoker.invokeProjectStructureGeneration（含 publishProjectFailedEvent 失败事件）
- InitializrMetadata（依赖 InitializrProperties，元信息来自 application.yaml）
- ProjectDescription + DefaultProjectRequestToDescriptionConverter#convert（validate → platformVersion → resolvedDependencies → 设置 11 个字段）
- ProjectGenerator：customizeProjectGenerationContext（设置 Parent + 注册 InitializrMetadata/BuildItemResolver/MetadataProjectDescriptionCustomizer Bean）
- ProjectGenerationContext：Parent = parentApplicationContext（Initializr 主上下文）
- DefaultProjectAssetGenerator.generate：resolveProjectDirectoryFactory → initializerProjectDirectory → 迭代有序 ProjectContributor 流
- ProjectContributor：通常单例 + 有序（文件系统资源生成需关注顺序）
- @ProjectGenerationConfiguration（等同 @Configuration）+ spring.factories SPI（10 个内建实现：Build/Gradle/Maven/SourceCode/Groovy/Java/Kotlin/ApplicationConfiguration/HelpDocument/Git）
- BuildItemResolver（MetadataBuildItemResolver，不足：无法解析间接依赖/预配置内容）
- 构建建议：Initializr 仅在开发环境构建；配置化依赖管理；与 Apollo/Nacos 动态配置打通；LRU 算法减少重复生成
- 主要扩展组件：ProjectRequest/ProjectDescription/ProjectContributor/InitializrMetadata/MustacheTemplateRenderer
- 资料：Mustache 模板语法
- 作业十一：扩展 Initializr 生成多模块 Maven（api/data/core/web + 根 pom + 模块 pom + Java 代码到 web 模块）

**现代对应**：initializr 0.20+；Mustache；Spring Boot 3.x；可结合 AOT 优化启动

**sca-lab 实践**：
- `scaffold-lab`（可选）：Spring Initializr 定制 + 多模块生成扩展（可作为 sca-lab 自身脚手架工具）

---

## 三、过时技术 → 现代替代完整映射表

| stage-1 过时技术 | 现代替代技术 | 保留的主题 |
|---|---|---|
| Netflix Eureka | **Nacos** | 注册发现 |
| Netflix Ribbon | **Spring Cloud LoadBalancer** | 负载均衡 |
| Netflix Servo | **Micrometer** | 指标采集 |
| Spring Cloud Sleuth | **Micrometer Tracing + OpenTelemetry + SkyWalking** | 链路追踪 |
| Hystrix | **Sentinel**（含熔断） | 熔断 |
| RestTemplate 主流地位 | **Spring 6 RestClient + OpenFeign** | REST 客户端 |
| FastJSON | **Jackson**（安全原因） | 序列化 |
| javax.validation | **Jakarta Validation 3.0** | 参数校验 |
| Bootstrap 上下文 | **application.yml + spring.config.activate.on-profile** | 配置引导 |
| Spring Cloud Config | **Nacos Config** | 配置中心 |
| Netflix OSS（整体） | **Spring Cloud Core + SCA** | 服务治理 |
| spring-cloud-starter-gateway | **spring-cloud-gateway-server-webflux/webmvc** | 网关 artifactId |
| Tomcat Boss/Worker 线程模型 | **Java 21 虚拟线程** | Web 容器线程模型 |
| X-B3-TraceId | **W3C traceparent** | Trace ID 传输 |
| @NewSpan（Sleuth） | **@Observed（Micrometer Observation）** | 自定义 Span |
| WeightedResponseTimeRule（Ribbon 内置） | **手写在 Spring Cloud LoadBalancer 中实现** | 动态权重负载均衡算法 |

**核心原则**：stage-1 的所有主题都在 sca-lab 中有对应实践，过时技术用现代替代品学习同样的主题，不跳过任何主题。

---

## 四、基于 stage-1 梳理的 sca-lab lab 清单

### 核心 lab（必做，9 个）

| lab | 覆盖 stage-1 功能域 | 现代技术栈 | 来源章节 |
|---|---|---|---|
| **nacos-lab** | 注册发现 + 配置中心 + 动态变更 | Nacos（替代 Eureka + Spring Cloud Config） | 第 10、11、15、22 章 |
| **gateway-lab** | 网关 + 安全 + 可观测 | Spring Cloud Gateway 2025（webflux/webmvc） | 第 19-20 章 |
| **loadbalancer-lab** | 负载均衡 + 指标驱动 | Spring Cloud LoadBalancer（替代 Ribbon）+ 手写 WeightedResponseTimeRule | 第 11-12 章 |
| **openfeign-lab** | REST 客户端 + 五维优化 | OpenFeign + RestClient（替代 RestTemplate） | 第 04、22 章 |
| **sentinel-lab** | 容错 + 流控 + 熔断 | Sentinel（SCA 主流）+ Resilience4j 对比 | 第 08-09 章 |
| **micrometer-lab** | 指标采集 + Prometheus + Grafana | Micrometer 1.13+（替代 Servo） | 第 13-16 章 |
| **tracing-lab** | 链路追踪 + 字节码增强 | Micrometer Tracing + OpenTelemetry + SkyWalking（替代 Sleuth） | 第 17-18 章 |
| **seata-lab** | 分布式事务 | Seata 2.x（AT/TCC/SAGA/XA） | （stage-2 内容，stage-1 未讲） |
| **rocketmq-lab** | 消息 | RocketMQ 5.x | （stage-2/3 内容，stage-1 未讲） |

### 基础支撑 lab（2 个）

| lab | 覆盖 stage-1 功能域 | 现代技术栈 | 来源章节 |
|---|---|---|---|
| **sca-lab-bom** | 工程构建 | Maven 3.9 + BOM 继承组合 | 第 01-02 章 |
| **sca-lab-common** | REST API | Jakarta Validation + Jackson + RestClient | 第 03-04 章 |

### 可选 lab（3 个）

| lab | 覆盖 stage-1 功能域 | 现代技术栈 | 来源章节 |
|---|---|---|---|
| **dubbo-lab** | RPC | Dubbo 3.x Triple | （stage-2/3 内容） |
| **performance-lab** | 性能优化 | Java 21 虚拟线程 + GraalVM AOT | 第 21-22 章 |
| **scaffold-lab** | 脚手架定制 | Spring Initializr 0.20+ 多模块生成 | 第 23-24 章 |

---

## 五、学习顺序建议（支持并行）

| 阶段 | lab（∥ 表示可并行） | 理由 | stage-1 来源 |
|---|---|---|---|
| **阶段 0：基础** | sca-lab-bom → sca-lab-common | 工程骨架 + 统一响应/异常（串行） | 第 01-04 章 |
| **阶段 1：服务治理基础** | nacos-lab → openfeign-lab → loadbalancer-lab | 注册发现是基础；服务间调用三件套（串行） | 第 10-12 章 + 第 04 章 |
| **阶段 2：网关 + 容错** | gateway-lab ∥ sentinel-lab | 网关入口 + 容错核心（可并行，两者独立） | 第 07-10 章 + 第 19-20 章 |
| **阶段 3：可观测** | micrometer-lab ∥ tracing-lab | 指标 + 链路追踪（可并行，两者互相不依赖） | 第 13-18 章 |
| **阶段 4：事务 + 消息** | seata-lab ∥ rocketmq-lab | 数据一致性（可并行；stage-2/3 内容） | — |
| **阶段 5：RPC 升级** | dubbo-lab | 对比 OpenFeign | — |
| **阶段 6：性能 + 脚手架** | performance-lab ∥ scaffold-lab | 优化 + 工程化（可并行） | 第 21-24 章 |

---

## 六、章节间依赖关系

| 章节依赖 | 关系说明 |
|---|---|
| 第 10 章 ← 第 08 章 | 动态容错变更依赖 Resilience4j 组件（动态调整 CB/RateLimiter 参数） |
| 第 10 章 ← 第 13 章 | 动态变更依赖 Micrometer 指标（指标驱动动态调整） |
| 第 11 章 ← 第 13 章 | 指标驱动负载均衡依赖 Micrometer 采集指标 |
| 第 12 章 ← 第 11 章 | WeightedResponseTimeRule 依赖第 11 章的指标采集 |
| 第 14 章 ← 第 13 章 | Micrometer 整合第三方依赖第 13 章的 API 基础 |
| 第 15 章 ← 第 13 章 | Prometheus Pull 依赖 Micrometer 注册中心 |
| 第 16 章 ← 第 13 章 | Pushgateway Push 依赖 Micrometer 注册中心 |
| 第 17 章 ← 第 13-14 章 | 链路追踪依赖 Micrometer 指标（Trace + Metrics 整合） |
| 第 19 章 ← 第 08 章 | 网关容错依赖 Resilience4j（Gateway + CB/RateLimiter） |
| 第 20 章 ← 第 13/17 章 | 网关可观测依赖 Micrometer 指标 + 链路追踪 |
| 第 22 章 ← 第 10/19 章 | 性能优化依赖动态变更 + 网关 |

**核心依赖链**：第 13 章（Micrometer）是可观测域的根，第 10 章（动态变更）是配置域的根，第 08 章（Resilience4j）是容错域的根。

---

## 七、每个 lab 的特性清单

### nacos-lab（来源：第 10/11/15/22 章 + Nacos 2.3.0 源码 MCP 索引）

> MCP 索引：`data-workspace-source-code-code-nacos`（41441 节点 / 183696 边 / 2214 Java 文件）
> 四类特性（A/B/C/D）都要 100% 生产级别掌握，不是取舍关系。

#### A 类：官方主流功能（生产级 — 配置 + 调优 + 故障排查 + 高可用 + 最佳实践）

1. 服务注册 — 临时实例 vs 持久化实例（ephemeral）生产选型 + 故障排查
2. 服务发现 — 客户端订阅 + gRPC 推送机制 + 健康检查调优
3. 配置中心 — Data ID / Group / Namespace 三维隔离 + 灰度发布设计
4. 动态刷新 — @RefreshScope 生产级使用 + 弊端规避 + ConfigurationPropertiesRebinder 对比
5. 集群部署 — 3 节点 Raft CP + Distro AP 生产部署 + 容量规划 + 滚动升级
6. 元数据管理 — metadata map 生产用途（指标/权重/Prometheus SD）
7. 健康检查 — 心跳调优 + 自我保护机制生产处理
8. Prometheus SD — nacos_sd_configs + relabel_configs（替代 Eureka SD）
9. Bootstrap 废弃 — application.yml + spring.config.activate.on-profile 生产配置

#### B 类：扩展点自定义（生产级 — 能自定义扩展并用于生产）

1. 自实现 ServiceRegistry — 扩展 Spring Cloud ServiceRegistry 抽象
2. 自定义 ConfigChangedListener — 扩展配置监听机制
3. 自实现 LoadBalancer — 基于 Nacos metadata 权重的 ServiceInstanceListSupplier
4. 自定义 NamingService — 扩展服务发现客户端
5. 自定义 AuthPlugin — 扩展 Nacos 鉴权 SPI（ApiType.AUTH）

#### C 类：手写填补空白（生产级 — 理解算法 + 性能 + 边界 + 对比官方）

1. 手写 Nacos OpenAPI 客户端（参考 microsphere-nacos 150 文件实现）
2. 手写 Nacos 配置热加载机制（不依赖 @RefreshScope 的独立实现）
3. 手写简易 Distro 同步协议（参考 DistroProtocol.sync）

#### D 类：底层原理验证（生产级 — 读源码 + 理解机制 + 能排查底层问题）

**D1. 一致性协议（CP/AP 双模式）**
- 核心类：`PersistentConsistencyServiceDelegateImpl`（naming/.../persistent/PersistentConsistencyServiceDelegateImpl.java:38）、`BasePersistentServiceProcessor`（naming/.../persistent/impl/BasePersistentServiceProcessor.java:59）、`DistroProtocol`（core/.../distro/DistroProtocol.java）
- 机制：CP 模式基于 JRaft（持久化实例）+ AP 模式 Distro（临时实例），通过 PersistentConsistencyServiceDelegateImpl 委托路由
- 生产要点：CP/AP 路由选择、协议切换、JRaft 选举超时调优、Distro 同步延迟
- 源码入口：PersistentConsistencyServiceDelegateImpl.java:38、DistroProtocol.java:114（sync 方法）

**D2. 集群成员管理**
- 核心类：`Member`（core/.../cluster/Member.java）、`MemberLookup`（core/.../cluster/MemberLookup.java）、`MemberUtil`（core/.../cluster/MemberUtil.java）、`MemberReportHandler`（core/.../cluster/remote/MemberReportHandler.java）、`MembersChangeEvent`（core/.../cluster/MembersChangeEvent.java）
- 机制：成员发现（配置文件/寻址）、成员上报、变更事件通知
- 生产要点：成员扩缩容、节点掉线处理、集群脑裂排查
- 源码入口：Member.java、MemberReportHandler.java

**D3. 配置管理（Dump + 长轮询）**
- 核心类：`DumpService`（config/.../dump/DumpService.java）、`LongPollingService`（config/.../service/LongPollingService.java）、`LongPollingConnectionMetricsCollector`
- 机制：配置全量 dump 到本地 + 长轮询客户端拉取变更 + 服务端推送通知
- 生产要点：dump 性能调优、长轮询连接数控制、配置变更通知延迟
- 源码入口：DumpService.java、LongPollingService.java

**D4. 服务发现（ServiceManager + Push）**
- 核心类：`ServiceManager`（naming/.../core/v2/ServiceManager.java，396 fan_in）、`PushExecutor`（naming/.../push/v2/executor/PushExecutor.java）、`SpiPushExecutor`、`NamingPushRequestHandler`（client/.../gprc/NamingPushRequestHandler.java）、`Subscriber`（naming/.../pojo/Subscriber.java）
- 机制：服务注册表管理 + gRPC 推送 + 订阅通知（NotifySubscriberRequest）
- 生产要点：推送延迟优化、订阅连接管理、推送空保护
- 源码入口：ServiceManager.java、PushExecutor.java

**D5. 鉴权**
- 核心类：`NacosAuthManager`（plugin-default-impl/nacos-default-auth-plugin/.../NacosAuthManager.java）
- 机制：IdentityContext + Token + AuthPlugin SPI
- 生产要点：Token 续期、权限模型、鉴权失败排查
- 源码入口：NacosAuthManager.java

**D6. 存储（Derby vs MySQL）**
- 核心类：`DataSourceService`（persistence/.../datasource/DataSourceService.java，310 fan_in）
- 机制：Derby 内嵌（单机）vs MySQL 外置（集群），DataSourceService 抽象
- 生产要点：Derby → MySQL 迁移、连接池调优、主从配置
- 源码入口：DataSourceService.java

**D7. 网络通信（gRPC 长连接）**
- 核心类：`GrpcClient`（common/.../remote/client/grpc/GrpcClient.java）、`GrpcClientConfig`、`NamingGrpcClientProxy`（client/.../gprc/NamingGrpcClientProxy.java）
- 机制：gRPC server/client + 长连接管理 + 流控
- 生产要点：连接池配置、长连接保活、gRPC 流控调优
- 源码入口：GrpcClient.java、NamingGrpcClientProxy.java

**D8. 健康检查**
- 核心类：`AbstractHealthChecker`（api/.../healthcheck/AbstractHealthChecker.java）、`HealthCheckType`、`HealthCheckerFactory`、`HealthCheckRequest/Response`
- 机制：客户端心跳（临时实例）vs 服务端主动检查（持久化实例）
- 生产要点：心跳超时调优、健康检查失败排查、实例摘除策略
- 源码入口：AbstractHealthChecker.java、HealthCheckType.java

**D9. 集群同步（Distro 任务）**
- 核心类：`DistroProtocol`（sync/syncToTarget 方法）、`AbstractDistroExecuteTask`（core/.../distro/task/execute/AbstractDistroExecuteTask.java）、`DistroVerifyExecuteTask`、`DistroComponentHolder`、`DistroConfig`
- 机制：Distro 同步任务（立即/延迟/验证）+ 责任切片 + 数据校验
- 生产要点：同步延迟优化、同步失败重试、跨集群数据一致性
- 源码入口：DistroProtocol.java:114（sync）、DistroProtocol.java:128（syncToTarget）、AbstractDistroExecuteTask.java:46

**D10. 限流/控制（TpsControl）**
- 核心类：`TpsControl`（core/.../control/TpsControl.java）、`TpsControlConfig`、`NacosHttpTpsControlRegistration`、`TpsControlRequestFilter`、`NacosTpsControlManager`（plugin-default-impl/.../NacosTpsControlManager.java）
- 机制：请求优先级 + TPS 控制 + 请求过滤
- 生产要点：限流规则配置、热点接口保护、限流降级
- 源码入口：TpsControl.java、TpsControlRequestFilter.java

**D11. ID 生成（雪花算法）**
- 核心类：`SnowFlowerIdGenerator`（core/.../distributed/id/SnowFlowerIdGenerator.java，260 fan_in）、`IdGeneratorManager`（core/.../distributed/id/IdGeneratorManager.java）
- 机制：雪花算法 + workerId 分配 + 时钟回拨处理
- 生产要点：workerId 冲突、时钟回拨处理、ID 生成性能
- 源码入口：SnowFlowerIdGenerator.java

**D12. 开关管理**
- 核心类：`SwitchManager`（naming/.../misc/SwitchManager.java）
- 机制：动态开关 + 推送 + 持久化
- 生产要点：开关热更新、开关影响范围、开关回滚
- 源码入口：SwitchManager.java

**D13. 灰度发布（Beta）**
- 核心类：`Beta`（common/.../Beta.java）、`ConfigInfo4Beta`（config/.../model/ConfigInfo4Beta.java）、`DumpAllBetaProcessor`（config/.../dump/processor/DumpAllBetaProcessor.java）
- 机制：Beta 配置 + 灰度规则 + 独立 dump
- 生产要点：灰度发布流程、灰度回滚、灰度规则配置
- 源码入口：Beta.java、ConfigInfo4Beta.java

**D14. 推送（Push v2）**
- 核心类：`PushExecutor`（naming/.../push/v2/executor/PushExecutor.java）、`SpiPushExecutor`、`PushDataWrapper`、`NamingPushRequestHandler`、`PushConstants`
- 机制：SPI 推送执行器 + 推送数据包装 + 客户端推送请求处理
- 生产要点：推送延迟、推送失败重试、推送聚合
- 源码入口：PushExecutor.java、SpiPushExecutor.java

**D15. Naming 模块 v2 架构**
- 核心目录：`naming/.../core/v2/`（含 cleaner/client/event/index/metadata/pojo/service 子目录）
- 机制：v2 架构（gRPC + Client 模型 + 事件驱动）vs v1 架构（HTTP + 服务端模型）
- 生产要点：v1 → v2 迁移、Client 连接管理、事件处理
- 源码入口：naming/.../core/v2/ServiceManager.java

### gateway-lab（来源：第 19-20 章 + spring-cloud-gateway 源码 MCP 索引）

> MCP 索引：`data-workspace-source-code-code-spring-cloud-gateway`（8235 节点 / 30820 边）
> 源码位置：`/data/workspace/source-code/code/spring-cloud-gateway/`
> 四类特性（A/B/C/D）都要 100% 生产级别掌握。

#### A 类：官方主流功能（生产级）
1. Route/Predicate/Filter 三层 SPI — 核心架构 + 生产路由设计
2. 路由定位器 — RouteLocator / RouteDefinitionLocator / CompositeRouteDefinitionLocator
3. 服务发现路由 — DiscoveryClientRouteDefinitionLocator + spring.cloud.gateway.discovery.locator
4. 函数式 DSL — PredicateSpec / GatewayFilterSpec 路由编程
5. 限流 — RequestRateLimiter + Redis RateLimiter + KeyResolver 自定义
6. 熔断 — Resilience4j CircuitBreaker 整合 + fallbackUri
7. 安全 — SecureHeaders / CORS / CSRF 三道安全门 + Spring Security 6
8. 可观测 — Gateway + Micrometer 指标 + Micrometer Tracing
9. 管理端口隔离 — management.server.port + DIFFERENT 模式
10. 路由容错 — onErrorContinue + switchIfEmpty 未匹配处理
11. artifactId — spring-cloud-gateway-server-webflux/webmvc（2025 新拆分）

#### B 类：扩展点自定义（生产级）
1. 自定义 RoutePredicateFactory — 扩展路由谓词（如基于 Nacos metadata 的路由）
2. 自定义 GatewayFilterFactory — 扩展过滤器（如自定义鉴权/日志/灰度）
3. 自定义 GlobalFilter — 全局过滤器（如统一请求头注入）
4. 自定义 RouteDefinitionLocator — 扩展路由来源（如从 Nacos 配置中心读取路由）
5. 自定义 GatewayFilter 排序 — 实现 Ordered 接口控制过滤器顺序

#### C 类：手写填补空白（生产级）
1. 手写 WeightedResponseTimeRule GatewayFilter（基于响应时间的网关层负载均衡）
2. 手写灰度发布 GatewayFilter（基于 Header/Cookie 的流量染色）
3. 手写简易网关（参考 RoutePredicateHandlerMapping + FilteringWebHandler）

#### D 类：底层原理验证（生产级 — 含源码入口）

**P0 必备（生产 + 面试双高频，~500 行源码）**

**D1. RoutePredicateHandlerMapping 执行链**
- 核心类：`RoutePredicateHandlerMapping`（spring-cloud-gateway-server/.../handler/RoutePredicateHandlerMapping.java）、`FilteringWebHandler`（.../handler/FilteringWebHandler.java）
- 机制：DispatcherHandler → RoutePredicateHandlerMapping.lookupRoute → FilteringWebHandler.handle → GatewayFilter 链迭代
- 生产要点：路由匹配失败排查、过滤器顺序问题、管理端口隔离（DIFFERENT 模式）
- 面试考点：Gateway 请求处理流程？RoutePredicateHandlerMapping 如何匹配路由？
- 源码入口：RoutePredicateHandlerMapping.java、FilteringWebHandler.java

**D2. RouteDefinitionLocator Composite + @Primary 模式**
- 核心类：`CompositeRouteDefinitionLocator`（.../route/CompositeRouteDefinitionLocator.java）、`PropertiesRouteDefinitionLocator`、`DiscoveryClientRouteDefinitionLocator`（.../discovery/DiscoveryClientRouteDefinitionLocator.java）、`CachingRouteDefinitionLocator`
- 机制：组合多个 RouteDefinitionLocator + @Primary + 缓存装饰器
- 生产要点：路由来源扩展、路由热更新、路由缓存
- 面试考点：Gateway 路由定义有几种来源？Composite 模式如何设计？
- 源码入口：CompositeRouteDefinitionLocator.java、DiscoveryClientRouteDefinitionLocator.java

**D3. GatewayFilter 执行顺序 + GlobalFilter vs GatewayFilter**
- 核心类：`GatewayFilter`（.../filter/GatewayFilter.java）、`GlobalFilter`（.../filter/GlobalFilter.java）、`OrderedGatewayFilter`（.../filter/OrderedGatewayFilter.java）
- 机制：GatewayFilter（路由级）+ GlobalFilter（全局级）合并排序后统一迭代，Ordered 接口控制顺序
- 生产要点：过滤器顺序排查、GlobalFilter 注册、Order 冲突
- 面试考点：GlobalFilter 和 GatewayFilter 区别？过滤器如何排序？
- 源码入口：GatewayFilter.java、GlobalFilter.java、OrderedGatewayFilter.java、FilteringWebHandler.java

**P1 重要（生产常用 / 面试可能问，~300 行源码）**

**D4. RouteDefinitionRouteLocator 路由构建**
- 核心类：`RouteDefinitionRouteLocator`（.../route/RouteDefinitionRouteLocator.java）、`RouteLocator`（.../route/RouteLocator.java）
- 机制：RouteDefinition → Route 转换 + Predicate/GatewayFilter 绑定
- 源码入口：RouteDefinitionRouteLocator.java

**D5. GatewayProperties 配置机制**
- 核心类：`GatewayProperties`（.../config/GatewayProperties.java）
- 机制：spring.cloud.gateway.routes 配置绑定 + RouteDefinition 列表
- 源码入口：GatewayProperties.java

**P2 了解（面试可能问，读笔记即可）**

**D6. RequestRateLimiter 限流**
- 核心类：`RequestRateLimiterGatewayFilterFactory`（.../filter/factory/RequestRateLimiterGatewayFilterFactory.java）
- 机制：Redis 令牌桶 + KeyResolver 自定义限流 key
- 建议：读笔记，不读源码

**D7. 管理端口隔离 + 路由容错**
- 机制：DIFFERENT 模式 + GATEWAY_HANDLER_MAPPER_ATTR 属性传递 + onErrorContinue
- 建议：读笔记，不读源码

**D8. ForwardedHeadersFilter 转发头处理（P2）**
- 核心类：`ForwardedHeadersFilter`（.../filter/headers/ForwardedHeadersFilter.java，413 fan_in）
- 机制：自动处理 X-Forwarded-For / X-Forwarded-Host / X-Forwarded-Proto / X-Forwarded-Prefix
- 生产要点：反向代理后 IP 获取、多层代理头叠加、信任代理 IP 配置
- 建议：读笔记，不读源码

**D9. CorsGatewayFilterApplicationListener CORS 自动配置（P2）**
- 核心类：`CorsGatewayFilterApplicationListener`（.../filter/cors/CorsGatewayFilterApplicationListener.java）
- 机制：监听 Spring 事件 + 自动注入 CORS GatewayFilter
- 生产要点：全局 CORS vs 路由级 CORS、允许的方法/头/源配置
- 建议：读笔记，不读源码

### loadbalancer-lab（来源：第 11-12 章 + spring-cloud-commons 源码 MCP 索引）

> MCP 索引：`data-workspace-source-code-code-spring-cloud-commons`（8554 节点 / 29399 边）
> 源码位置：`/data/workspace/source-code/code/spring-cloud-commons/spring-cloud-loadbalancer/`
> 四类特性（A/B/C/D）都要 100% 生产级别掌握。
> **2026-07 更新**：基于 MCP 索引深度探索，补充了原始规划中遗漏的官方类和结构调整 C-01 目标

#### A 类：官方主流功能（生产级）

| 编号 | 内容 | 核心类 |
|:----:|------|--------|
| A-01 | 轮询 | `RoundRobinLoadBalancer` |
| A-02 | 随机 | `RandomLoadBalancer` |
| A-03 | 实例列表供应 | `ServiceInstanceListSupplier`、`DiscoveryClientServiceInstanceListSupplier`、`NoopServiceInstanceListSupplier` |
| A-04 | 同区域优先 | `ZonePreferenceServiceInstanceListSupplier` |
| A-05 | 健康检查 | `HealthCheckServiceInstanceListSupplier` |
| A-06 | 缓存 | `CachingServiceInstanceListSupplier` |
| A-07 | 权重负载均衡（内置） | `WeightedServiceInstanceListSupplier`、`WeightFunction`、`LazyWeightedServiceInstanceList` |
| A-08 | 提示路由（Header/Hint） | `HintBasedServiceInstanceListSupplier` |
| A-09 | 同实例优先（粘性） | `SameInstancePreferenceServiceInstanceListSupplier` |
| A-10 | 子集选择 | `SubsetServiceInstanceListSupplier` |
| A-11 | 请求粘性会话 | `RequestBasedStickySessionServiceInstanceListSupplier` |
| A-12 | 重试 | `RetryAwareServiceInstanceListSupplier`、`BlockingLoadBalancedRetryPolicy` |
| A-13 | API 版本选择（阻塞 + 响应式） | `BlockingApiVersionServiceInstanceListSupplier`、`ReactiveApiVersionServiceInstanceListSupplier` |
| A-14 | X-Forwarded 头处理 | `XForwardedHeadersTransformer`（阻塞+响应式两种） |
| A-15 | 流式 Builder | `ServiceInstanceListSupplierBuilder`（`withWeighted()`、`withHealthChecks()`、`withCaching()`、`withSameInstancePreference()`、`withHints()`、`withSubset()`、`withZonePreference()`、`withRequestBasedStickySession()`） |
| A-16 | LoadBalancerLifecycle 生命周期钩子 | `LoadBalancerLifecycle` |
| A-17 | 阻塞 LoadBalancer 客户端 | `BlockingLoadBalancerClient` |
| A-18 | 响应式 LoadBalancer 接口 | `ReactorLoadBalancer`、`ReactorServiceInstanceLoadBalancer` |

##### A-07 补充说明：WeightedServiceInstanceListSupplier

这是官方内置的权重 LB，通过 `WeightFunction` 计算每个实例的权值，把实例列表按权重"展开"——权值 2 的实例在展开后的列表中出现 2 次，然后由轮询选择器自然实现加权轮询。

展开使用 `LazyWeightedServiceInstanceList`（惰性 `AbstractList`），只在 `get(index)` 时才展开，避免预先创建大数组。

权值来源：默认从 `metadata.weight` 字段读取；支持自定义 `WeightFunction`。

#### B 类：扩展点自定义（生产级）
1. 自定义 ReactorServiceInstanceLoadBalancer — 实现响应式负载均衡器
2. 自定义 ServiceInstanceListSupplier — 扩展实例列表（含指标采集）
3. 自定义 LoadBalancerClientConfiguration — 替换默认配置
4. 自定义 WeightFunction — 扩展权重计算（响应时间 / CPU / 自定义指标）
5. 自定义 LoadBalancerLifecycle — 扩展生命周期（可观测性埋点）

#### C 类：手写填补空白（生产级）
1. **手写动态响应时间 WeightFunction** — 基于响应时间动态计算权重的 `WeightFunction` 实现（RIbbon `WeightedResponseTimeRule` 算法移植为 `WeightFunction`，接入官方 `WeightedServiceInstanceListSupplier`）
2. 手写基于 Nacos metadata 的权重负载均衡器（自定义 WeightFunction 读 Nacos metadata）
3. 手写一致性哈希负载均衡器（参考 Dubbo ConsistentHashLoadBalance，直接实现 ReactorServiceInstanceLoadBalancer）

> **C-01 调整说明**：原规划"手写 WeightedResponseTimeRule"是针对早期版本（无 WeightedServiceInstanceListSupplier）的。当前版本官方已内置 WeightedServiceInstanceListSupplier，因此 C-01 调整为"手写 WeightFunction"，把 Ribbon 的动态响应时间算法实现为 WeightFunction 接口，插入官方框架。复用官方展开逻辑，只替换权重计算策略。

#### D 类：底层原理验证（生产级 — 读源码 + 理解机制 + 能排查底层问题）

> 每篇不限制篇幅，以讲清楚、讲透彻、能回答面试和生产问题为准。
> 核心方法、数据结构、设计模式、类间关系，逐段展开。

**D1. ReactorLoadBalancer 响应式负载均衡接口**
- 核心类：`ReactorLoadBalancer`、`RoundRobinLoadBalancer`、`RandomLoadBalancer`、`ReactorServiceInstanceLoadBalancer`
- 阅读范围：
  - `ReactorLoadBalancer` — 接口定义的两个 `choose()` 方法，`Request` 泛型设计
  - `ReactorServiceInstanceLoadBalancer` — `Mono<Response<ServiceInstance>>` 响应式契约
  - `RoundRobinLoadBalancer` — 轮询算法实现，原子计数器 + ServiceInstanceListSupplier 组合
  - `RandomLoadBalancer` — 随机算法实现，ThreadLocalRandom
  - `Response<T>` 数据包装类 — 成功/失败/空 三种状态
- 讲解要点：响应式 LoadBalancer 接口的设计意图、为什么 choose 返回 Mono、Request 泛型的扩展性、轮询和随机的原子性保障
- 面试考点：Spring Cloud LoadBalancer 如何选择实例？响应式接口如何设计的？为什么不是 `ServiceInstance` 而是 `Response<ServiceInstance>`？

**D2. ServiceInstanceListSupplier 装饰器链体系**
- 核心类：`ServiceInstanceListSupplier`、`DelegatingServiceInstanceListSupplier`、`ServiceInstanceListSupplierBuilder`
- 阅读范围：
  - `ServiceInstanceListSupplier` — 接口的 get() 和 get(Request) 双方法，ServiceInstanceListSupplierBuilder 的 Creator/DelegateCreator SPI
  - `DelegatingServiceInstanceListSupplier` — 装饰器基类，delegate 的传递和 destroy 传播
  - `ServiceInstanceListSupplierBuilder` — 每个 `withXxx()` 方法的实现，DelegateCreator 链式组合
  - 各 Supplier 实现：
    - **精读（理解完整逻辑）**：`ServiceInstanceListSupplierBuilder`（骨架）、`DelegatingServiceInstanceListSupplier`（装饰器基类）、`DiscoveryClientServiceInstanceListSupplier`（数据源）、`WeightedServiceInstanceListSupplier`（权重）、`HealthCheckServiceInstanceListSupplier`（健康检查）
    - **了解（笔记即可）**：`ZonePreferenceServiceInstanceListSupplier`、`CachingServiceInstanceListSupplier`、`HintBasedServiceInstanceListSupplier`、`SameInstancePreferenceServiceInstanceListSupplier`、`SubsetServiceInstanceListSupplier`、`RequestBasedStickySessionServiceInstanceListSupplier`、`RetryAwareServiceInstanceListSupplier`
- 讲解要点：装饰器模式的 SPI 设计、Supplier 链的构建顺序和数据流向、每种 Supplier 的作用和典型配置场景、Builder 如何与 Spring 上下文集成
- 生产要点：Supplier 链构建顺序、装饰器扩展点、自定义 Supplier 的注入方式

**D3. LoadBalancerClientConfiguration 自动装配**
- 核心类：`LoadBalancerClientConfiguration`、`LoadBalancerClientFactory`、`LoadBalancerClientSpecification`
- 阅读范围：
  - `LoadBalancerClientConfiguration` — @Bean 方法清单、缺省配置策略、Spring Cloud LoadBalancer 的 ServiceInstanceListSupplier 自动装配逻辑
  - `LoadBalancerClientFactory` — 继承 NamedContextFactory，每个服务独立子上下文，getInstance() / getProperties() 方法
  - `LoadBalancerAutoConfiguration` — 自动配置触发条件
- 讲解要点：每个 @LoadBalancerClient 独立 ApplicationContext 的机制、配置覆盖优先级（注解 vs 配置文件 vs 默认）、LoadBalancerClientFactory 与 FeignClientFactory 的对比
- 面试考点：LoadBalancerClientFactory 如何工作？每个服务独立上下文的设计模式？

**D4. WeightedServiceInstanceListSupplier + LazyWeightedServiceInstanceList 权重展开机制**
- 核心类：`WeightedServiceInstanceListSupplier`、`WeightFunction`、`LazyWeightedServiceInstanceList`
- 阅读范围：
  - `WeightFunction` — 函数式接口设计，int apply(ServiceInstance)
  - `WeightedServiceInstanceListSupplier` — 默认 metadataWeightFunction、权重展开逻辑 expandByWeight()、异常的兜底处理
  - `LazyWeightedServiceInstanceList` — GCD 归约计算、WeightedServiceInstanceSelector 双队列算法、惰性展开的 synchronized 线程安全
- 讲解要点：从 metadata.weight 到展开列表的完整链路、GCD 归约为什么能减少队列长度、active/expired 双队列如何实现加权轮询、惰性展开解决什么问题
- 这一步挖深：`WeightedServiceInstanceSelector.next()` 的双队列切换算法，比预展开节省多少内存

**D5. BlockingLoadBalancerClient 阻塞客户端**
- 核心类：`BlockingLoadBalancerClient`、`BlockingLoadBalancerClientAutoConfiguration`
- 阅读范围：
  - `BlockingLoadBalancerClient` — 阻塞 client 的实现，从 request URL 提取 serviceId，调用 LoadBalancer.choose()（底层 Mono.block()），重构 Request URI
  - `BlockingLoadBalancerClientAutoConfiguration` — 自动创建 BlockingLoadBalancerClient 的条件
  - LoadBalancerLifecycle 在 BlockingLoadBalancerClient 中的调用时机
- 讲解要点：阻塞 client 如何桥接响应式内核、Mono.block() 的线程阻塞模型、与 FeignBlockingLoadBalancerClient/ @LoadBalanced RestTemplate 的关系

**D6. LoadBalancerLifecycle 生命周期**
- 核心类：`LoadBalancerLifecycle`、`MicrometerStatsLoadBalancerLifecycle`
- 阅读范围：
  - `LoadBalancerLifecycle` — supports() / onStart() / onStartRequest() / onComplete() / onError() 接口定义
  - `MicrometerStatsLoadBalancerLifecycle` — Micrometer 指标采集的实现
- 讲解要点：四阶段回调的时序和线程模型、如何实现自定义 Lifecycle（日志/指标/链路追踪）
- 面试考点：LoadBalancerLifecycle 回调顺序？与 Filter 和 Interceptor 的区别？

**D7. LoadBalancerInterceptor + RetryLoadBalancerInterceptor — @LoadBalanced RestTemplate 集成层**
- 核心类：`LoadBalancerInterceptor`、`RetryLoadBalancerInterceptor`（spring-cloud-commons/.../client/loadbalancer/）、`ServiceRequestWrapper`、`LoadBalancerRequestFactory`、`LoadBalancerRequestAdapter`、`LoadBalancerUriTools`
- 阅读范围：
  - `LoadBalancerInterceptor` — `ClientHttpRequestInterceptor` 实现，intercept() 方法从 URI host 提取 serviceName，委托给 `loadBalancer.execute(serviceName, requestFactory.createRequest(...))`
  - `RetryLoadBalancerInterceptor` — 重试包装器，`intercept()` 里的重试循环 + 同实例重试/跨实例重试策略 + `interceptWithRetry()` 方法
  - `ServiceRequestWrapper` — `HttpRequest` 包装器，重写 `getURI()` 返回已替换 host:port 的 URI
  - `LoadBalancerRequestFactory` — 创建 `LoadBalancerRequest<ClientHttpResponse>`
  - `LoadBalancerRequestAdapter` — 适配 `HttpRequest` 到 `RequestData`
  - `LoadBalancerUriTools` — URI 重构工具（替换 serviceId 为实际 host:port）
  - `BlockingLoadBalancerRequest` — 请求 + `ClientHttpRequestData`（方法 + body + mediaType）
  - `LoadBalancerAutoConfiguration`（spring-cloud-commons 下）— `@LoadBalanced RestTemplate` 自动注入拦截器
- 讲解要点：`@LoadBalanced` 注解如何触发拦截器注入、拦截器→loadBalancer→BlockingLoadBalancerClient→ReactorLoadBalancer 的完整调用链、RetryLoadBalancerInterceptor 的重试循环（同实例 vs 跨实例）、URI 替换的时机和方法
- 这一步挖深：retry 的重试策略如何与 LoadBalancerProperties.Retry 绑定、backoff 算法
- 面试考点：@LoadBalanced RestTemplate 如何实现负载均衡？RetryLoadBalancerInterceptor 的重试策略？

### openfeign-lab（来源：第 04/22 章 + spring-cloud-openfeign 源码 MCP 索引）

> MCP 索引：`data-workspace-source-code-code-spring-cloud-openfeign`（4343 节点 / 15743 边）
> 源码位置：`/data/workspace/source-code/code/spring-cloud-openfeign/`
> 四类特性（A/B/C/D）都要 100% 生产级别掌握。

#### A 类：官方主流功能（生产级）
1. 声明式调用 — @FeignClient 接口 + name/contextId/url 配置
2. 拦截器 — RequestInterceptor（透传 Header/Token/traceId）
3. 超时配置 — connectTimeout / readTimeout + 与 LoadBalancer 超时层级关系
4. HTTP 客户端切换 — Apache HttpClient5 / OkHttp4（替代默认 HttpURLConnection）
5. HTTP 压缩 — Gzip 压缩配置 + min-response-size
6. 序列化 — Jackson + SpringEncoder/SpringDecoder（替代 FastJSON）
7. 熔断 — Spring Cloud CircuitBreaker 整合（Sentinel/Resilience4j）+ fallback/fallbackFactory
8. 负载均衡 — FeignBlockingLoadBalancerClient 整合 + @LoadBalancerClient 自定义
9. 多版本 API — 接口级平滑升级（Header 版本路由）
10. RestClient — Spring 6 新 HTTP 客户端对比

#### B 类：扩展点自定义（生产级）
1. ✅ 自定义 RequestInterceptor — 扩展请求拦截（如灰度流量染色） — 已实现 MyTraceIdInterceptor
2. ❌ 自定义 InvocationHandlerFactory — 替换默认调用处理器
3. ❌ 自定义 Contract — 扩展注解解析（自定义 @FeignClient 注解属性）
4. ❌ 自定义 Encoder/Decoder — 扩展序列化（如 Protobuf）
5. ❌ 自定义 ErrorDecoder — 扩展错误处理（重试/降级策略）

#### C 类：手写填补空白（生产级）
1. ✅ 手写简易声明式 HTTP 客户端（参考 Feign.Builder + JDK 动态代理） — 已实现 C-01(831行, 26文件)
2. ❌ 手写 FeignDecorator 责任链（参考 resilience4j-feign 洋葱模型）
3. ❌ 手写多版本 API 路由（基于 Header 的版本路由 + Feign 拦截器）

#### D 类：底层原理验证（生产级 — 含源码入口）

**P0 必备（生产 + 面试双高频，~500 行源码）**

**D1. FeignClientFactoryBean 创建机制**
- 核心类：`FeignClientFactoryBean`（spring-cloud-openfeign-core/.../FeignClientFactoryBean.java）
- 机制：FactoryBean.getObject → Feign.Builder → Target → Feign 实例化 + contextId 隔离
- 生产要点：@FeignClient contextId 隔离、配置覆盖、Bean 注册时机
- 面试考点：@FeignClient 如何工作？FactoryBean 如何创建 Feign 实例？
- 源码入口：FeignClientFactoryBean.java

**D2. InvocationHandlerFactory 扩展点**
- 核心类：`FeignCachingInvocationHandlerFactory`（spring-cloud-openfeign-core/.../FeignCachingInvocationHandlerFactory.java）
- 机制：InvocationHandlerFactory 替换 + 方法调用分发 + 缓存优化
- 生产要点：自定义调用处理器、方法级拦截、缓存策略
- 面试考点：Feign 如何分发方法调用？InvocationHandlerFactory 如何扩展？
- 源码入口：FeignCachingInvocationHandlerFactory.java

**P1 重要（生产常用 / 面试可能问，~300 行源码）**

**D3. FeignBlockingLoadBalancerClient 整合**
- 核心类：`FeignBlockingLoadBalancerClient`（spring-cloud-openfeign-core/.../loadbalancer/FeignBlockingLoadBalancerClient.java）、`RetryableFeignBlockingLoadBalancerClient`（.../loadbalancer/RetryableFeignBlockingLoadBalancerClient.java）
- 机制：delegating feign.Client + LoadBalancer 选择实例 + 请求执行 + 重试
- 生产要点：与 LoadBalancer 超时层级、重试策略、实例选择失败处理
- 源码入口：FeignBlockingLoadBalancerClient.java

**D4. SpringEncoder/SpringDecoder 整合**
- 核心类：`SpringEncoder`（spring-cloud-openfeign-core/.../support/SpringEncoder.java）
- 机制：HttpMessageConverter 整合 + 请求体序列化 + 响应体反序列化
- 生产要点：Converter 选择、序列化优化、自定义 Converter
- 源码入口：SpringEncoder.java

**P2 了解（面试可能问，读笔记即可）**
- Feign 上下文隔离（每个 @FeignClient 独立 ApplicationContext + FeignClientFactoryBean） — 读笔记
- HttpMessageConverter 扩展机制 — 读笔记
- feign-hc5/feign-okhttp 底层实现 — 读笔记
- `RefreshableUrlFactoryBean` URL 动态刷新（配合 @RefreshScope） — 读笔记

**D5. FeignClientsRegistrar — @FeignClient 注册机制（P0 — 新增遗漏补充）**
- 核心类：`FeignClientsRegistrar`（spring-cloud-openfeign-core/.../FeignClientsRegistrar.java）
- 机制：ImportBeanDefinitionRegistrar + 扫描 @EnableFeignClients + 注册 FeignClientSpecification BeanDefinition
- 生产要点：@FeignClient 扫描路径配置、Bean 命名冲突、与 Spring 上下文生命周期关系
- 面试考点：@FeignClient 如何被 Spring 发现并注册？ImportBeanDefinitionRegistrar 如何工作？
- 源码入口：FeignClientsRegistrar.java

**D6. FeignClientsConfiguration 默认配置（P1 — 新增遗漏补充）**
- 核心类：`FeignClientsConfiguration`（spring-cloud-openfeign-core/.../FeignClientsConfiguration.java）
- 机制：Spring 自动装配 + Feign Contract/Encoder/Decoder/Logger/Retryer 默认 Bean 注册
- 生产要点：默认配置覆盖、自定义 Contract 替换、配置优先级（全局 vs 局部）
- 源码入口：FeignClientsConfiguration.java

**D7. FeignClientProperties 配置优先级（P1 — 新增遗漏补充）**
- 核心类：`FeignClientProperties`（spring-cloud-openfeign-core/.../FeignClientProperties.java）
- 机制：spring.cloud.openfeign.client.config 配置绑定 + 全局 default 与局部 clientName 优先级
- 生产要点：配置覆盖顺序、公共配置提取、多 Feign Client 配置隔离
- 源码入口：FeignClientProperties.java

### sentinel-lab（来源：第 08-09 章 + Sentinel 1.8.8 源码 MCP 索引）

> MCP 索引：`data-workspace-source-code-code-Sentinel`（已索引）
> 关联：Sentinel source-md 37 篇 PLAN（18 模块）已完成，D 类原理可引用 source-md 成果
> 四类特性（A/B/C/D）都要 100% 生产级别掌握，不是取舍关系。

#### A 类：官方主流功能（生产级 — 配置 + 调优 + 故障排查 + 高可用 + 最佳实践）

1. 流控规则 — QPS/线程数/关联/链路 + 生产调优（统计窗口选择、warmUp 参数）
2. 熔断规则 — 慢调用比例/异常比例/异常数 + 生产调优（RT 阈值、最小请求数）
3. 降级 — fallback + blockHandler + 生产最佳实践（异常分类、业务隔离）
4. 热点参数限流 — paramFlowRule + 生产场景（商品 ID 限流、用户 ID 限流）
5. 系统自适应限流 — SystemRule（Load/CPU/RT/线程数）+ 生产场景
6. 集群限流 — Token Server/Client + 生产部署（Server 集群、Client 失败回退）
7. 规则持久化 — Nacos datasource + 生产配置（动态规则、规则分组）
8. 整合 OpenFeign — spring.cloud.openfeign.circuitbreaker.enabled
9. 整合 Gateway — sentinel-spring-cloud-gateway-adapter

#### B 类：扩展点自定义（生产级 — 能自定义扩展并用于生产）

1. 自定义 ProcessorSlot — 扩展 Slot 链（实现 ProcessorSlot 接口 + SPI 注册）
2. 自定义 SlotChainBuilder — 替换默认 SlotChainBuilder（扩展 SpiLoader）
3. 自定义 DataSource — 扩展规则源（实现 ReadableDataSource）
4. 自定义 BlockExceptionHandler — 扩展限流响应（统一错误格式）
5. 自定义 InitFunc — 扩展初始化（SPI 注册 com.alibaba.csp.sentinel.init.InitFunc）

#### C 类：手写填补空白（生产级 — 理解算法 + 性能 + 边界 + 对比官方）

1. 手写滑动窗口（参考 LeapArray 实现，AtomicReferenceArray<WindowWrap<MetricBucket>>）
2. 手写令牌桶限流器（参考 ThrottlingController，maxQueueingTimeMs 排队等待）
3. 手写简易熔断器（参考 CircuitBreaker 状态机：CLOSED/OPEN/HALF_OPEN）

#### D 类：底层原理验证（生产级 — 读源码 + 理解机制 + 能排查底层问题）

**P0 必备（生产 + 面试双高频，~600 行源码）**

**D1. 滑动窗口（LeapArray）**
- 核心类：`LeapArray`（sentinel-core/.../slots/statistic/base/LeapArray.java）
- 机制：AtomicReferenceArray<WindowWrap<MetricBucket>> + 时间片滑动 + LeapArray.currentWindow 原子更新
- 生产要点：窗口长度选择、sampleCount 与 intervalInMs 关系、高并发下的桶切换
- 面试考点：LeapArray 如何实现无锁统计？滑动窗口 vs 滑动计数器区别？
- 源码入口：LeapArray.java（重点 currentWindow/values/isWindowDeprecated）

**D2. Slot 链机制（ProcessorSlot + SpiLoader + DefaultSlotChainBuilder）**
- 核心类：`ProcessorSlot`（sentinel-core/.../slotchain/ProcessorSlot.java）、`DefaultSlotChainBuilder`（sentinel-core/.../slots/DefaultSlotChainBuilder.java）、`SpiLoader`（sentinel-core/.../spi/SpiLoader.java）
- 机制：SPI 加载 SlotChainBuilder → 构建 Slot 链 → 责任链模式逐个执行
- 生产要点：Slot 顺序、自定义 Slot 插入位置、@Spi 注解 order 属性
- 面试考点：Sentinel 的 Slot 链如何构建？SPI 如何加载？Slot 顺序如何保证？
- 源码入口：DefaultSlotChainBuilder.java、SpiLoader.java

**D3. 流控算法（DefaultController + ThrottlingController）**
- 核心类：`FlowSlot`（sentinel-core/.../slots/block/flow/FlowSlot.java）、`DefaultController`（.../controller/DefaultController.java）、`ThrottlingController`（.../controller/ThrottlingController.java）
- 机制：DefaultController（QPS 超限直接拒绝）+ ThrottlingController（匀速排队，maxQueueingTimeMs）+ WarmUpController（冷启动预热）
- 生产要点：控制策略选择、warmUp 预热参数、throttling 排队超时
- 面试考点：Sentinel 流控算法有几种？warmUp 如何计算？throttling 如何排队？
- 源码入口：FlowSlot.java、DefaultController.java、ThrottlingController.java

**D4. 熔断状态机（CircuitBreaker + DegradeSlot）**
- 核心类：`DegradeSlot`（sentinel-core/.../slots/block/degrade/DegradeSlot.java）、`CircuitBreaker`（sentinel-core/.../slots/block/degrade/circuitbreaker/CircuitBreaker.java）
- 机制：CircuitBreaker 状态机（CLOSED→OPEN→HALF_OPEN）+ 慢调用/异常比例/异常数三种策略 + 熔断恢复探测
- 生产要点：熔断窗口大小、恢复探测次数、熔断与限流协同
- 面试考点：熔断三种策略区别？HALF_OPEN 如何探测？与 Resilience4j 状态机差异？
- 源码入口：CircuitBreaker.java、DegradeSlot.java

**P1 重要（生产常用 / 面试可能问，~400 行源码）**

**D5. Node 体系（StatisticNode / DefaultNode / ClusterNode / EntranceNode）**
- 核心类：`StatisticNode`（sentinel-core/.../node/StatisticNode.java）、`DefaultNode`、`ClusterNode`、`EntranceNode`
- 机制：4 层 Node 继承体系 + 统计数据聚合（rollingParamMinute/rollingParamSecond）+ 调用树构建
- 生产要点：Node 树解读、统计数据来源、clusterNode vs defaultNode 区别
- 面试考点：Node 体系如何构建？StatisticNode 如何统计 QPS？ClusterNode 作用？
- 源码入口：StatisticNode.java、DefaultNode.java

**D6. 热点参数限流（ParamFlowChecker）**
- 核心类：`ParamFlowSlot`（sentinel-extension/sentinel-parameter-flow-control/.../ParamFlowSlot.java）、`ParamFlowChecker`（.../ParamFlowChecker.java）
- 机制：参数索引提取 + CacheMap 统计 + 热点参数独立限流
- 生产要点：参数索引选择、热点阈值配置、特例参数配置
- 面试考点：热点参数如何统计？CacheMap 如何淘汰？为何只支持 4 个参数？
- 源码入口：ParamFlowSlot.java、ParamFlowChecker.java

**D7. 数据源机制（NacosDataSource + FileRefreshableDataSource）**
- 核心类：`NacosDataSource`（sentinel-extension/sentinel-datasource-nacos/.../NacosDataSource.java）、`FileRefreshableDataSource`（sentinel-extension/sentinel-datasource-extension/.../FileRefreshableDataSource.java）
- 机制：ReadableDataSource 抽象 + Listener 模式 + 配置变更自动转换为规则对象
- 生产要点：数据源切换、规则转换 Converter、初始化时机
- 面试考点：Sentinel 规则如何持久化？NacosDataSource 如何监听变更？
- 源码入口：NacosDataSource.java、FileRefreshableDataSource.java

**P2 了解（面试可能问，读笔记即可，不读源码）**

**D8. 集群限流（ClusterTokenClient + ClusterTokenServer）**
- 核心类：`ClusterTokenClient`（sentinel-core/.../cluster/client/ClusterTokenClient.java）、`ClusterTokenServer`（sentinel-core/.../cluster/server/ClusterTokenServer.java）
- 机制：Token Server 集中分配令牌 + Token Client 网络请求 + 失败回退本地限流
- 生产要点：Server 集群部署、Client 失败回退、网络超时
- 面试考点：集群限流如何实现？Server 挂了怎么办？与单机限流差异？
- 建议：读笔记，不读源码

**D9. 系统自适应（SystemSlot）**
- 机制：基于 Load1/RT/线程数/CPU 的自适应限流
- 面试考点：系统规则如何触发？自适应原理？
- 建议：读笔记，不读源码

**D10. 适配器机制（WebMVC/WebFlux/Dubbo/Gateway）**
- 机制：Filter/Interceptor/Adapter 模式织入 Sentinel
- 面试考点：Sentinel 如何整合 Spring WebMVC？资源名如何生成？
- 建议：读笔记，不读源码

**P3 选学（运维/进阶，按需）**

**D11. 传输层（CommandCenter + HeartbeatSender）**
- 机制：SimpleHttpCommandCenter + 心跳上报 + Dashboard 通信
- 何时学：Dashboard 集成排查时

**D12. SPI 扩展（SpiLoader + InitFunc）**
- 机制：SPI 加载 + @Spi 注解 + InitFunc 初始化
- 何时学：自定义 Slot/DataSource 时

**D13. Context/Entry 体系（ContextUtil + CtEntry + CtSph）**
- 机制：Context 调用上下文 + Entry 资源入口 + CtSph 入口处理器
- 何时学：深入理解 Sentinel 调用链时
- 源码入口：ContextUtil.java、CtEntry.java、CtSph.java

### micrometer-lab（来源：第 13-16 章 + micrometer 源码 MCP 索引）

> MCP 索引：`data-workspace-source-code-code-micrometer`（20386 节点 / 89080 边）
> 源码位置：`/data/workspace/source-code/code/micrometer/`
> 四类特性（A/B/C/D）都要 100% 生产级别掌握。

#### A 类：官方主流功能（生产级）
1. Counter — 单调递增（错误数/订单量）+ 生产埋点
2. Timer — 持续时间 + 计数（HTTP RT/DB 耗时）+ Timer.Sample
3. Gauge — 瞬时值（线程池活跃数/连接池占用）+ registry.gauge
4. DistributionSummary — 分布统计（响应 body 大小/批处理条数）
5. MeterRegistry — Simple / Composite / Prometheus + 多后端路由
6. MeterBinder — JVM / Tomcat / HikariCP / Executor / Kafka 内建绑定（14+ Binder）
7. @Timed / @Counted 注解 — AOP 切面 + TimedAspect Bean
8. SLO 直方图 — publishPercentiles / publishPercentileHistogram / serviceLevelObjectives
9. Prometheus Pull — /actuator/prometheus + Nacos SD + relabel_configs
10. Pushgateway — 短生命周期任务推送 + honor_labels + groupingKey
11. Pull + Push 混合模式 — CompositeMeterRegistry 多后端路由
12. Grafana 仪表板 — PromQL + 模板变量 + SLO 仪表板
13. Observation API — 统一 metrics + tracing（Spring Boot 3+）

#### B 类：扩展点自定义（生产级）
1. 自定义 MeterBinder — 扩展业务指标绑定（如订单量/用户活跃数）
2. 自定义 MeterRegistry — 扩展后端（如自研监控系统）
3. 自定义 ObservationHandler — 扩展 Observation API（metrics + tracing + logging）
4. 自定义 MeterFilter — 指标过滤/转换（如 Tag 重命名/值过滤）
5. 自定义 HttpServerObservationFilter — 扩展 HTTP 指标

#### C 类：手写填补空白（生产级）
1. 手写简易指标采集框架（参考 Micrometer Meter/MeterRegistry 抽象）
2. 手写简易 Prometheus text exposition 格式输出
3. 手写简易滑动窗口聚合（参考 Micrometer DistributionStatisticConfig）

#### D 类：底层原理验证（生产级 — 含源码入口）

**P0 必备（生产 + 面试双高频，~400 行源码）**

**D1. MeterRegistry 多后端路由**
- 核心类：`MeterRegistry`（micrometer-core/.../instrument/MeterRegistry.java）、`CompositeMeterRegistry`（micrometer-core/.../instrument/composite/CompositeMeterRegistry.java）、`PrometheusMeterRegistry`（implementations/micrometer-registry-prometheus/.../PrometheusMeterRegistry.java）
- 机制：CompositeMeterRegistry 树状组合 + 自动路由到子 Registry + baseUnit 转换
- 生产要点：多后端路由策略、Registry 生命周期、指标一致性
- 面试考点：Micrometer 如何路由指标到多后端？CompositeMeterRegistry 如何工作？
- 源码入口：MeterRegistry.java、CompositeMeterRegistry.java

**D2. MeterBinder 自动绑定**
- 核心类：`MeterBinder`（micrometer-core/.../instrument/binder/MeterBinder.java）
- 机制：BinderPostProcessor（Spring Boot Actuator）自动发现 + bindTo 注册 + 14+ 内建 Binder
- 生产要点：自定义 Binder 注册、Binder 执行时机、Binder 顺序
- 面试考点：Micrometer 如何自动绑定 JVM/Tomcat/HikariCP 指标？
- 源码入口：MeterBinder.java

**D3. Observation API 统一 metrics+tracing**
- 核心类：`ObservationRegistry`（micrometer-observation/.../ObservationRegistry.java）、`DefaultMeterObservationHandler`（micrometer-core/.../observation/DefaultMeterObservationHandler.java）
- 机制：ObservationHandler 链 + ObservationConvention + 统一 metrics+tracing+logging
- 生产要点：Handler 链顺序、Convention 自定义、Observation 生命周期
- 面试考点：Observation API 如何统一 metrics 和 tracing？Handler 链如何工作？
- 源码入口：ObservationRegistry.java、DefaultMeterObservationHandler.java

**P1 重要（生产常用 / 面试可能问，~300 行源码）**

**D4. @Timed/@Counted AOP 切面**
- 核心类：`TimedAspect`（micrometer-core/.../aop/TimedAspect.java）、`CountedAspect`（micrometer-core/.../aop/CountedAspect.java）
- 机制：@Timed/@Counted 注解 + AOP 切面 + Timer/Counter 自动注册
- 生产要点：注解参数配置、extraTags、percentiles/histogram
- 源码入口：TimedAspect.java、CountedAspect.java

**D5. Prometheus text exposition + SLO 直方图**
- 核心类：`PrometheusMeterRegistry`（implementations/micrometer-registry-prometheus/.../PrometheusMeterRegistry.java）、`ServiceLevelObjective`（implementations/micrometer-registry-health/.../ServiceLevelObjective.java）
- 机制：# TYPE/# HELP + 指标命名转换 + SLO（Error Budget + Health Indicator）
- 生产要点：命名转换规则、SLO 直方图配置、PromQL 查询
- 源码入口：PrometheusMeterRegistry.java

**P2 了解（面试可能问，读笔记即可）**
- Pushgateway 推送机制（PrometheusPushGatewayMeterRegistry + groupingKey） — 读笔记
- Pull + Push 混合模式（CompositeMeterRegistry + 不同后端） — 读笔记
- Grafana PromQL 查询优化 — 读笔记
- `ServiceLevelObjective` SLO 接口定义 — 读笔记
- `HealthConfig` 健康指标配置 — 读笔记

### tracing-lab（来源：第 17-18 章 + micrometer-tracing 源码 MCP 索引）

> MCP 索引：`data-workspace-source-code-code-micrometer-tracing`（4495 节点 / 17111 边）
> 源码位置：`/data/workspace/source-code/code/micrometer-tracing/`
> 四类特性（A/B/C/D）都要 100% 生产级别掌握。

#### A 类：官方主流功能（生产级）
1. W3C Trace Context — traceparent 头传播（替代 X-B3-TraceId）+ tracestate
2. 跨服务 Span — OpenFeign + WebMVC 整合 + 自动传播
3. @Observed 注解 — 自定义 Span（替代 Sleuth @NewSpan）+ Observation API
4. Baggage — 业务字段透传（如 userId/tenantId 跨服务传递）
5. MDC 注入 — traceId/spanId 入日志 + SLF4J MDC 自动注入
6. Micrometer Tracing + OpenTelemetry — SDK 整合 + OTel Bridge
7. SkyWalking Java Agent — 字节码增强（替代应用层 Wrapper）+ 自动埋点
8. 后端 — Zipkin / Tempo / Jaeger + Span 存储与查询
9. 采样策略 — 概率采样（ProbabilisticSampler）+ 速率限制采样（RateLimitingSampler）

#### B 类：扩展点自定义（生产级）
1. 自定义 ObservationHandler — 扩展 Observation API（Span 创建/结束回调）
2. 自定义 SpanAttributeProvider — 扩展 Span 属性（业务字段注入）
3. 自自定义 BaggageManager — 扩展 Baggage 管理
4. 自定义 Sampler — 扩展采样策略（如基于 URL 的采样）
5. 自定义 Propagator — 扩展 Trace Context 传播（如自定义 Header）

#### C 类：手写填补空白（生产级）
1. 手写简易链路追踪框架（参考 Tracer/Span/TraceContext API）
2. 手写 W3C traceparent 解析/生成
3. 手写简易 Java Agent（参考 Byte Advice + MethodDelegation 字节码增强）

#### D 类：底层原理验证（生产级 — 含源码入口）

**P0 必备（生产 + 面试双高频，~500 行源码）**

**D1. W3C Trace Context 传播 + Tracer/Span API**
- 核心类：`Tracer`（micrometer-tracing/.../Tracer.java）、`Span`（micrometer-tracing/.../Span.java）、`TraceContext`（micrometer-tracing/.../TraceContext.java）
- 机制：traceparent 格式（version-traceid-parentid-traceflags）+ Tracer.nextSpan/withSpan + Span.start/end/error
- 生产要点：TraceContext 跨服务传播、Span 生命周期、错误记录
- 面试考点：traceparent 格式？Tracer 如何创建 Span？TraceContext 如何传播？
- 源码入口：Tracer.java、Span.java、TraceContext.java

**D2. Observation API 统一 metrics+tracing**
- 核心类：`DefaultTracingObservationHandler`（micrometer-tracing/.../handler/DefaultTracingObservationHandler.java）、`TracingObservationHandler`（micrometer-tracing/.../handler/TracingObservationHandler.java）、`TracingAwareMeterObservationHandler`（micrometer-tracing/.../handler/TracingAwareMeterObservationHandler.java）
- 机制：ObservationHandler 链 + TracingObservationHandler 桥接 metrics+tracing + ObservationConvention
- 生产要点：Handler 链顺序、Convention 自定义、metrics+tracing 协同
- 面试考点：Observation API 如何统一 metrics 和 tracing？Handler 链如何工作？
- 源码入口：DefaultTracingObservationHandler.java、TracingObservationHandler.java

**D3. SkyWalking Java Agent 字节码增强**
- 机制：Byte Buddy + InstanceMethodsInterProcessor + 自动埋点（生产主流，my-xhs 在用）
- 生产要点：Agent 配置、插件开发、忽略列表
- 面试考点：SkyWalking Agent 如何自动埋点？字节码增强原理？
- 建议：SkyWalking 源码未拉取，读官方文档 + 笔记

**P1 重要（生产常用 / 面试可能问，~300 行源码）**

**D4. Micrometer Tracing + OTel/Brave 整合**
- 核心类：`BaggageManager`（micrometer-tracing/.../BaggageManager.java）、`BraveBaggageManager`（micrometer-tracing-bridges/micrometer-tracing-bridge-brave/.../BraveBaggageManager.java）、`OtelBaggageManager`（micrometer-tracing-bridges/micrometer-tracing-bridge-otel/.../OtelBaggageManager.java）、`PropagationType`（Brave/OTel 两个实现）
- 机制：OTelBridge + BraveBridge + PropagationType 选择 + Baggage 跨服务透传
- 生产要点：Bridge 选择（OTel vs Brave）、Baggage 配置、传播类型
- 源码入口：BaggageManager.java、BraveBaggageManager.java、OtelBaggageManager.java

**P2 了解（面试可能问，读笔记即可）**
- Java Instrument premain/agentmain 机制（ClassFileTransformer） — 读笔记
- Byte Buddy Advice/MethodDelegation 区别 — 读笔记
- ClassLoader 单例性（devtools 类加载陷阱） — 读笔记
- MDC 自动注入（Slf4jBaggageEventListener） — 读笔记
- `SpanNamer` Span 命名策略 — 读笔记
- `SpanMetrics` Span 指标报告 — 读笔记

**D5. CurrentTraceContext 当前 Trace 上下文管理（P0 — 新增遗漏补充）**
- 核心类：`CurrentTraceContext`（micrometer-tracing/.../CurrentTraceContext.java）
- 机制：ThreadLocal 存储当前 Trace 上下文 + 跨线程传播（wrap/execute）+ Scope 管理
- 生产要点：异步线程 Trace 传播、@Async 场景、Reactor/Hystrix 线程池适配
- 面试考点：TraceContext 如何跨线程传播？ThreadLocal vs Scope？
- 源码入口：CurrentTraceContext.java

**D6. ScopedSpan + SpanCustomizer Span 操作（P1 — 新增遗漏补充）**
- 核心类：`ScopedSpan`（micrometer-tracing/.../ScopedSpan.java）、`SpanCustomizer`（micrometer-tracing/.../SpanCustomizer.java）
- 机制：ScopedSpan 自动结束 + SpanCustomizer 自定义标签/事件
- 生产要点：Span 作用域、标签/事件最佳实践、Span 过度自定义影响性能
- 源码入口：ScopedSpan.java、SpanCustomizer.java

### seata-lab（stage-2 内容，stage-1 未讲）

1. AT 模式 — undo_log 反向补偿
2. TCC 模式 — Try/Confirm/Cancel 三阶段
3. SAGA 模式 — 长事务状态机
4. XA 模式 — 强一致
5. TC/TM/RM 三角色
6. 全局事务回滚验证

### rocketmq-lab（stage-2/3 内容，stage-1 未讲）

1. 事务消息 — 半消息 + 回查
2. 顺序消息 — 全局/分区顺序
3. 广播消息 — 集群 vs 广播
4. 延时消息 — 延时级别
5. 消费者组/队列/Offset
6. 与 Kafka 对比

### sca-lab-bom（来源：第 01-02 章）

> 四类特性（A/B/C/D）都要 100% 生产级别掌握。

#### A 类：官方主流功能（生产级）
1. Maven 多模块 — parent/inheritance/aggregation + 生产项目结构
2. BOM 设计 — dependencyManagement + Spring Boot/Cloud/SCA 版本兼容矩阵
3. 依赖冲突诊断 — exclude + Maven Enforcer Plugin 依赖收敛
4. api/data/core/web 四层结构 — 标准化项目组织
5. spring-boot-maven-plugin repackage — FAT JAR 打包 + 排除间接依赖
6. Maven Profiles — 多环境配置（dev/test/prod）

#### B 类：扩展点自定义（生产级）
1. 自定义 BOM 继承组合 — 业务 BOM 组合基础设施 BOM
2. 自定义 Maven Plugin — 扩展构建流程（代码生成/校验）
3. 自定义 Maven Archetype — 项目模板生成

#### C 类：手写填补空白（生产级）
1. 手写简易依赖仲裁算法（最近定义优先 + 依赖树遍历）
2. 手写 FAT JAR ClassPath 扩展（参考 JarLauncher）

#### D 类：底层原理验证（生产级）
- **P0 必备**：Maven 依赖仲裁算法（最近定义优先 + 依赖树遍历）、BOM 继承 vs 组合机制（import scope vs parent 继承）
- **P1 重要**：FAT JAR ClassPath 扩展（JarLauncher/WarLauncher 源码）、Maven Shade 插件原理（复制 API 如 Dubbo 复制 netty）
- **P2 了解**：Maven 依赖传递机制、Maven 6 种作用域（compile/provided/runtime/test/system/import）、SNAPSHOT vs RELEASE 版本策略

### sca-lab-common（来源：第 03-04 章）

> 四类特性（A/B/C/D）都要 100% 生产级别掌握。

#### A 类：官方主流功能（生产级）
1. 统一 ApiResponse 模型 — ApiRequest/ApiResponse 泛型包装 + headers/metadata/body 三段
2. @RestControllerAdvice — 全局异常处理 + HandlerExceptionResolver
3. StatusCode 枚举 — 模拟 HTTP Status Code + 业务错误码体系
4. Jakarta Validation 3.0 — Bean Validation + Hibernate Validator 8.x
5. Jackson — 序列化（替代 FastJSON，安全原因）+ HttpMessageConverter
6. RestClient — Spring 6 新 HTTP 客户端（替代 RestTemplate）

#### B 类：扩展点自定义（生产级）
1. 自定义 HandlerMethodReturnValueHandler — 隐形包装 POJO 为 ApiResponse
2. 自定义 HandlerMethodArgumentResolver — 参数解析扩展
3. 自定义 HttpMessageConverter — 序列化扩展（如 FastJSON2 替代 Jackson）
4. 自定义 Validator — Bean Validation 扩展（跨字段校验）
5. 自定义 ResponseBodyAdvice — 响应后置处理

#### C 类：手写填补空白（生产级）
1. 手写统一响应包装（ResponseBodyAdvice + ReturnValueHandler 隐形包装）
2. 手写幂等性 Token 机制（Redis + 自定义 @Idempotent 注解）
3. 手写多版本 API 路由（基于 Header/Path 的版本路由）

#### D 类：底层原理验证（生产级）
- **P0 必备**：DispatcherServlet 请求处理流程（HandlerMapping→HandlerAdapter→ViewResolver）、@RestControllerAdvice 异常处理机制（HandlerExceptionResolver 调用链）
- **P1 重要**：HandlerMethodReturnValueHandler 隐形包装机制、Bean Validation 实现（Hibernate Validator MessageInterpolator）、Jackson 序列化机制（ObjectMapper + JsonSerializer/JsonDeserializer）
- **P2 了解**：HttpMessageConverter 扩展机制、Problem Details (RFC 9457) 标准、RequestResponseBodyMethodProcessor 源码

### performance-lab（可选，来源：第 21-22 章）

> 四类特性（A/B/C/D）都要 100% 生产级别掌握。

#### A 类：官方主流功能（生产级）
1. AOP → 静态代理替换 — HandlerInterceptor 体系减少 AOP 代理
2. 通用注解 — @Idempotent（幂等）/ @Authorized（鉴权）/ @TokenGenerated（令牌）
3. Web 缓存 — RequestBody/ResponseBodyAdvice + ContentCachingFilter
4. Java 21 虚拟线程 — 替代 Tomcat Boss/Worker 线程模型
5. GraalVM AOT — Spring Boot 3.3+ Native Image
6. OpenFeign 五维优化 — 序列化（Jackson）/ HTTP 客户端（HttpClient5/OkHttp4）/ 压缩 / 超时 / LB
7. @RefreshScope 弊端规避 — ConfigurationPropertiesRebinder 替代方案

#### B 类：扩展点自定义（生产级）
1. 自定义 DelegatingMethodHandlerInterceptor — 多拦截器聚合（门面 1→N）
2. 自定义 ResponseBodyAdvice 缓存 — Response Body 缓存优化
3. 自定义 ContentCachingFilter — Response 内容缓存过滤器
4. 自定义 ScopedProxyMode — 替代 @RefreshScope 的 Scope 实现

#### C 类：手写填补空白（生产级）
1. 手写 AOP 静态代理（替代 Spring AOP JDK 动态代理/CGLIB）
2. 手写简易虚拟线程池（Java 21 Loom + StructuredTaskScope）
3. 手写 GraalVM AOT 兼容适配（动态代理 → 静态代理迁移）

#### D 类：底层原理验证（生产级）
- **P0 必备**：Spring AOP 代理机制（JDK 动态代理 vs CGLIB + AopProxy 调用链）、@RefreshScope 原理与弊端（ScopedProxyMode.TARGET_CLASS 代理 + destroy 不 init）
- **P1 重要**：Java 21 虚拟线程调度机制（ForkJoinPool + continuation）、GraalVM AOT 编译原理（C1/C2 替代 + 闭世界假设 + Reachability）、Bootstrap 废弃原理（BootstrapApplicationListener + 子上下文）
- **P2 了解**：HandlerInterceptor 静态代理体系（DelegatingMethodHandlerInterceptor 源码）、OpenFeign 超时层级关系（Ribbon→Feign→HttpClient）、System Properties 锁替换

### scaffold-lab（可选，来源：第 23-24 章）

> 四类特性（A/B/C/D）都要 100% 生产级别掌握。

#### A 类：官方主流功能（生产级）
1. Spring Initializr 定制 — BOM + 业务组件 + 私服部署
2. ProjectContributor SPI — 自定义文件生成（HELP.md / pom.xml / Maven Wrapper）
3. 多模块生成 — api/data/core/web 模板 + 根 pom + 模块 pom
4. Mustache 模板渲染 — 模板语法 + 自定义渲染
5. @ConditionalOn 条件注解 — @ConditionalOnBuildSystem/Language/Packaging/PlatformVersion

#### B 类：扩展点自定义（生产级）
1. 自定义 ProjectContributor — 扩展文件生成（业务模板）
2. 自定义 @ProjectGenerationConfiguration — 扩展配置（spring.factories SPI 注册）
3. 自定义 BuildItemResolver — 扩展依赖解析（间接依赖/预配置）
4. 自定义 MustacheTemplateRenderer — 扩展模板渲染

#### C 类：手写填补空白（生产级）
1. 手写简易项目生成器（参考 Initializr ProjectGenerator + ProjectDescription）
2. 手写多模块 Maven 生成（作业十一：api/data/core/web + 根 pom + Java 代码）

#### D 类：底层原理验证（生产级）
- **P0 必备**：ProjectGenerationContext 子上下文机制（Parent = Initializr 主上下文 + 子上下文 refresh）、ProjectContributor SPI 有序迭代（有序 Bean 迭代生成文件）
- **P1 重要**：spring.factories SPI 加载（10 个内建 @ProjectGenerationConfiguration）、Mustache 模板渲染原理
- **P2 了解**：Initializr 9 模块架构（actuator/bom/docs/generator/generator-spring/generator-test/metadata/service-sample/version-resolver/web）、LRU 缓存优化、ProjectDescription 11 字段模型

---

## 八、stage-1 定位说明

**stage-1 只是 sca-lab 规划的第一步**。stage-1 覆盖了 9 个功能域（工程构建/REST/容错/负载均衡/指标/链路/网关/性能/脚手架），但**未覆盖**以下 SCA 生态核心组件：

| 未覆盖的 lab | 在哪个 stage 讲 |
|---|---|
| seata-lab（分布式事务） | stage-2 第 13-20 章 |
| rocketmq-lab（消息） | stage-2/3 内容 |
| dubbo-lab（RPC） | stage-2 第 21-22 章 + stage-3 第 10/23-24 章 |
| shardingsphere-lab（分库分表） | stage-2 第 25-26 章 + stage-3 第 12 章 |
| redis-lab（分布式锁/缓存） | stage-2 第 27-28 章 |
| multiactive-lab（多活） | stage-4 全部 |
| middleware-lab（手写 Raft/2PC/锁/Gossip） | stage-2 第 01-06 章理论 + stage-2 middleware-projects |

**完整 sca-lab 规划需要 stage-2/3/4 梳理完成后才能确定**。当前基于 stage-1 的 lab 清单（9 核心 + 2 基础 + 3 可选 = 14 个）会在后续 stage 梳理中继续补充。

---

## 九、待 review 检查点

请重点 review 以下内容：

1. **功能域归类是否合理** — 9 个功能域的划分是否准确？
2. **子主题清单是否完整** — 每章的子主题是否有遗漏？（第 08/09/11/13/15/16 章已补齐深度）
3. **现代对应是否准确** — 过时技术 → 现代替代的映射是否正确？
4. **sca-lab lab 清单是否合理** — 9 核心 + 2 基础 + 3 可选 = 14 个 lab 是否合适？
5. **学习顺序是否合理** — 阶段 0-6 的依赖关系 + 并行设计是否正确？
6. **章节间依赖关系是否准确** — 第六部分的依赖链是否正确？
7. **每个 lab 的特性清单是否完整** — 第七部分的特性清单是否有遗漏？

如有调整意见，请直接指出，我会更新文档。
