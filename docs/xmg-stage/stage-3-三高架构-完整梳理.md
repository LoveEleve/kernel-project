# stage-3 高并发、高性能与高可用 完整梳理

> 基于小马哥（mercyblitz）Java 分布式架构训练营第三期"高并发、高性能与高可用"33 文档逐章深度梳理。
> 原始载体：Shopizer 开源电商（Spring Boot 2.x — **不研究**）。
> **核心原则**：**只提炼性能优化方法论**（时间无关的硬技能），映射到 2025 年现代技术栈。

---

## 一、stage-3 概览

| 维度 | 内容 |
|---|---|
| 文档数 | 33 个（22 主章节 + 公开课 + 加餐 + 结营） |
| 主题 | 以 Shopizer 为载体，项目驱动完成"三高"架构改造：性能调优 → Reactive → Service Mesh → Native |
| 功能域 | 15 个（性能方法论 / 容器调优 / 可观测性 / 注册发现 / RPC升级 / 数据存储 / Reactive事件 / 网关Mesh / Dubbo / 配置中心 / 云原生 / JVM故障排查 / Dubbo Mesh / etcd配置 / **工程案例与成长**） |
| papers/slides | 1 篇 ZGC 论文 + 2 个 ZGC slides + JFR Runtime Guide + **附：论文资料说明** |

---

## 二、按功能域归类的子主题清单

### 1. 性能优化方法论域（第 02、04、06、33 章）

**调优六步法**（第 02 章）：
- 评估量化 → 测量基线 → 识别瓶颈 → 修改消除 → 重测验证 → 采纳或回滚；时间无关的通用方法论
- 四类性能测试：负载测试（特定负载下行为）/ 压力测试（寻找容量上限）/ 浸泡测试（内存泄漏检测）/ 尖峰测试（弹性伸缩验证）
- 三大指标：TPS/QPS（吞吐量）、RT = 服务时间 + 等待时间（排队理论）、排队理论预测设备越忙等待时间非线性暴增

**JFR 生产级分析**（第 04 章）：
- JFR 默认 <1% 性能开销，可直接用于生产。JDK 17+ 完全免费
- `jcmd <pid> JFR.start duration=60s filename=recording.jfr` 零重启在线分析
- 三种事件类型：持续时间事件（阈值过滤）/ 即时事件 / 样本事件
- 性能分析三板斧：JMeter（压测） + JFR（热点分析） + JMH（微基准）
- 现代对应：JDK 21+ JFR 免费 + Async Profiler（perf_event+eBPF 火焰图）

**JMH 微基准测试**（第 06 章）：
- 四种 Benchmark 模式：Throughput / AverageTime / SampleTime / SingleShotTime
- 死代码消除防护：必须返回计算结果，不返回值 JIT 会优化掉
- 常量折叠防护：输入必须非 final（`private double x = Math.PI`）
- 伪共享（False Sharing）：@Contended 注解或缓存行填充，吞吐量差异约 17%（4975 vs 4262 ops/us）
- 现代对应：JMH 1.37 + JDK 21+

**现代 Java**（第 33 章，25KB+，**补充展开**）：
- JDK 9-21 三大阶段（9-11 / 12-17 / 18-21）+ 三维分析框架（语言/API/JVM）
- **Java 17 是关键转折点**：模块强封装 + 默认强制 `--illegal-access=deny` + Record 正式化 + Sealed Class
- **虚拟线程（Java 21 Loom）是核心并发革命**：M:N 调度替代传统 OS 线程池，在 I/O 密集型场景下 1000 线程 vs 100W 虚拟线程消耗相当
- ZGC 分代（JDK 21，JEP 439）显著降低年轻代分配停顿，实现 <1ms 暂停
- GC 演进路线：CMS 移除（14）→ G1 默认 + ZGC 实验（11）→ ZGC 正式（15）→ ZGC 分代（21）
- 语言演进：var(10)、Switch 表达式(14)、Record(16)、Pattern Matching for instanceof(16)、Sealed Class(17)、Pattern Matching for switch(21)
- 偏向锁禁用（JDK 15）、Finalization 弃用（JDK 18）、FFM API 终将替代 JNI
- Vector API 持续孵化中，释放 CPU SIMD 指令集并行计算能力
- Record + Pattern Matching + Sealed Class 组合实现代数数据类型（ADT）范式
- 现代对应：JDK 21 LTS + ZGC 分代 + 虚拟线程 + GraalVM AOT

---

### 2. 服务容器调优域（第 05、09 章）

**JVM GC 调优**（第 05 章，**核心定量发现**）：
- **ZGC + Tomcat 500 + HikariCP 80 组合 TPS 100/s，相比 G1 基线 11.2/s 有 8.9 倍提升**
- GC 算法选择矩阵：小内存(<100MB)→Serial、吞吐优先→Parallel、低延迟优先→ZGC
- ZGC 完整调优公式：
  ```
  -Xms2G -Xmx2G -XX:+UseZGC
  -XX:ConcGCThreads=2 -XX:ParallelGCThreads=6
  -XX:ZCollectionInterval=120 -XX:ZAllocationSpikeTolerance=5
  ```
- Parallel GC 线程数公式：NP > 8 ? 5/8*NP : NP
- GC 策略与连接池存在**耦合效应**：HikariCP 80 在 G1 下 TPS 几乎不变(11.2→13.38)，在 ZGC 下暴增(64.2→100)
- ZGC 大页配置：显式 2MB hugepages + NUMA 自动启用 + `-XX:+ZUncommit` 内存返还
- 现代对应：**JDK 21 分代 ZGC** 暂停 <1ms + 吞吐量接近 G1；虚拟线程减少 Tomcat 线程池压力

**HTTP 服务升级**（第 09 章）：
- Servlet 3.0 异步化：`request.startAsync()` 创建 AsyncContext，避免线程阻塞。现代对应：Spring MVC DeferredResult/Callable
- Servlet 3.1 非阻塞 I/O：ReadListener/WriteListener 替代阻塞读写。现代对应：Spring WebFlux Netty 事件循环
- HTTP/2 多路复用：Servlet 4.0 原生支持。现代对应：Tomcat 9+ / Netty + HTTP/2
- 现代对应：Spring Boot 3.x + Jakarta Servlet 6.0 + Tomcat 10

---

### 3. 可观测性域（第 03、29、30 章）

**Prometheus + Grafana 三件套**（第 03 章）：
- Docker Compose 部署：Prometheus(19090) + Grafana(13000) + Node Exporter + MySQL Exporter
- Micrometer 整合：`/actuator/prometheus` 端点暴指标
- Sentinel 整合到 Micrometer 指标体系：`-Dserver.port=18080 -Dcsp.sentinel.dashboard.server=localhost:18080`
- 现代对应：Micrometer 1.13+ + Observation API + Prometheus + Grafana Faro/Pyroscope

**日志平台 ELK+Kafka**（第 29 章）：
- 三层架构：Logback Kafka Appender（异步发送）→ Kafka（缓冲层）→ Logstash（消费）→ ES（存储）→ Kibana（可视化）
- Kafka 作为日志缓冲区防 Logstash 背压
- ES 集群角色分离：Master Node / Data Node / Client Node
- ILM（Index Lifecycle Management）自动化索引生命周期
- 现代对应：ELK 仍主流；Kafka 可替换为 Redpanda；ES 可替换为 Loki（轻量日志）

**监控平台 VictoriaMetrics**（第 30 章）：
- 完全兼容 PromQL，零成本替换 Prometheus 单机部署为集群
- 集群架构：vminsert（写代理）→ vmstorage（存储）→ vmselect（查询代理）
- 比 Prometheus 减少 7x 存储空间（高效压缩） + downsampling 降采样
- 现代对应：VictoriaMetrics + Mimir + Thanos 生态

---

### 4. 注册发现与高可用域（第 07-08 章）

**Eureka 架构**（**已过时 → Nacos 替代**）：
- 心跳机制：30s 间隔、3 次失败（~90s）后移除
- 自我保护模式：续租 <85% 阈时停止驱逐，防止分区清空注册表
- 优雅停机：unregister → 2min 静默期 → 资源关闭
- 增量+全量注册表：增量 3min 缓存，不一致回退全量
- **Eureka 2.0 已废弃 → Nacos CP(JRaft)+AP(Distro)**
- Eureka Server 集群：基于 Tomcat Tribes + JGroups P2P 上下文复制（**已过时 → Raft/gRPC**）

**可学习点**：AP 设计哲学（宁可返回旧数据不返回错误）、自我保护机制的 CAP 实践

**现代对应**：Nacos（CP Raft + AP Distro 双模式 + gRPC 推送）+ 5s 级心跳 + 下线感知 <1min

---

### 5. RPC 与 HTTP 升级域（第 10、20 章）

**Dubbo Triple 协议**（第 10 章）：
- Triple = HTTP/2 多路复用 + Protobuf 序列化，解决 HTTP/1.1 队头阻塞
- Protocol 责任链：QosProtocolWrapper → ProtocolFilterWrapper → ProtocolListenerWrapper → RegistryProtocol
- RegistryDirectory：动态维护 Invoker 集合 + 本地缓存 + 变更通知
- Router 责任链：串行执行多 Router 做灰度/标签路由
- Cluster 执行链路：MigrationInvoker → MockClusterInvoker → ClusterInterceptor → AbstractClusterInvoker
- Filter 设计：@Activate 控制 Provider/Consumer 端生效，类似 Servlet Filter
- 现代对应：Dubbo 3.3.x + Triple + Nacos + Spring Cloud LoadBalancer

**RPC 网关**（第 20 章）：
- gRPC 网关 + Dubbo 泛化网关 + HTTP/2 SSL
- gateway → Dubbo 泛化调用（GenericService）消除编译时依赖
- 现代对应：Dubbo 3.x 网关 + Spring Cloud Gateway

---

### 6. 数据存储域（第 11-12 章）

**MySQL MGR 高可用**（第 11 章）：
- GTID 模式自动追踪复制进度，替代 binlog position
- MGR 容错公式：n = 2f + 1，3 节点容忍 1 故障
- 冲突检测：行级"先提交获胜"，XCom（Paxos 变种）做全局有序广播
- 故障检测：5s 心跳 → UNREACHABLE → 10s 传播怀疑 → 多数驱逐
- 现代对应：MySQL 8.0 MGR + ProxySQL / ShardingSphere + MySQL Router 8.0

**MyBatis + ShardingSphere**（第 12 章）：
- Executor 三种类型：Simple / Batch / Reuse
- 4 个可拦截接口：Executor / ParameterHandler / ResultSetHandler / StatementHandler
- SqlSession 与事务 1:1 绑定：BaseExecutor 持有单个 Transaction + Connection
- 现代对应：MyBatis-Plus 3.x + ShardingSphere 5.x JDBC（SQL 解析自动路由）

---

### 7. Reactive 与事件域（第 13-14、16-17 章）

**Reactive WebFlux**（第 17 章）：
- 四种并行策略：串行(6s) / ExecutorService+CompletionService(3s) / Future 链式 / CompletableFuture 链式
- 背压机制：Subscription.request(n) 下游控制上游，cancel() 停止
- Reactor 三种 Scheduler：single / boundedElastic / parallel
- WebFlux vs MVC 并发模型：MVC 大线程池 / WebFlux 小固定线程池（事件循环）
- RSocket 定位：服务端 Reactive 通信协议，原生支持背压 + 双向流 + 多路复用
- 现代对应：Spring WebFlux 6.x + Reactor 3.x + RSocket 1.x

**分布式事件 + Kafka**（第 16 章）：
- Redis 命令级拦截转分布式事件：RedisConnectionInterceptor + RedisCommandInterceptor
- 性能四因素：传输效率（Netty）+ 序列化 + 消息协议 + 压缩
- 现代对应：Kafka 3.x + Debezium CDC + Redis Streams

---

### 8. 网关与 Service Mesh 域（第 19、21-22 章）— **补充展开**

**API 网关聚合**（第 19 章，380 行）：
- Spring Cloud Gateway 整合：负载均衡（LoadBalancer）+ 可观测性（Micrometer 指标 + Actuator endpoints）
- 服务聚合网关：收集 Spring MVC/WebFlux/Servlet Mappings 上报元信息
- 模块化网关：按业务权重独立映射部署，实现资源优化配置
- **Actuator Endpoints 架构**：
  - 统一注解 `@Endpoint`（Spring Boot 2.x+），合并 JMX 和 Web
  - 读操作（非敏感）vs 写操作（敏感）
  - 暴露类型：`management.jmx.exposure.include/exclude` + `management.web.exposure.include/exclude`
  - 安全配置：高版本默认关闭 JMX 和 Web，仅保留最低权限 endpoints
  - `management.endpoints.enabled-by-default` 默认 false
- 现代对应：Spring Cloud Gateway 2025（webflux/webmvc）+ Spring Boot 3.5 Actuator

**Istio Service Mesh**（第 21-22 章）：
- 五阶段演进：进程内复用→共享类库→代理服务→Sidecar→Service Mesh
- 四大特性：流量控制/策略/网络安全/可观测性
- 源于 Envoy 代理 data plane
- 现代对应：Istio 1.x + Cilium Service Mesh（兴起中）+ Dubbo 3.x Proxyless Mesh

**RPC 网关**（第 20 章，**补充展开**）：
- gRPC 网关：gateway→gRPC client→call→response；基于 HTTP/2 SSL
- Dubbo 泛化网关：gateway→`GenericService.$invoke(method, params)` 消除编译时依赖
- 对比：gRPC 网关适合跨语言场景，Dubbo 泛化网关适合纯 Java 生态
- 现代对应：Spring Cloud Gateway + Dubbo 3.x 泛化调用 + gRPC gateway

---

### 9. Dubbo 架构域（第 23-24 章）

**Dubbo 架构设计**（第 23 章）：
- 分层架构 + 三大中心（注册/配置/元数据）+ SPI 扩展
- **Dubbo Mesh**（第 24 章）：xDS 协议 + K8s 注册 + Proxy/Proxyless Mesh
- 现代对应：Dubbo 3.3.x Proxyless Mesh（无需 Sidecar）+ Nacos 注册 + Triple 协议

---

### 10. 配置中心域（第 25-27 章）

**Nacos 配置中心**（第 25 章）：
- Nacos = Diamond（配置）+ ConfigServer（注册）
- CP 模式基于 Raft + DB 模式基于高可用 MySQL，双模式满足不同一致性需求
- 概念体系：Region/Zone/Namespace/Data ID/Group/配置快照
- 现代对应：Nacos 2.x/3.x CP(DB 双模式)

**etcd 配置中心**（第 26 章）：
- Raft + Watch + 高可用集群。现代对应：etcd K8s 生态核心

**配置客户端**（第 27 章）：
- Spring PropertySource 整合 ZK/etcd/文件客户端
- 现代对应：Spring Cloud Config + Nacos Config Client

---

### 11. 云原生域（第 28、31-32 章）

**GraalVM AOT**（第 28 章）：
- Native Image：SubstrateVM + AOT = 启动毫秒级 + 内存大幅缩减
- 封闭世界假设要求反射/动态代理/序列化必须提前声明
- Tracing Agent：`-agentlib:native-image-agent` 自动收集运行时动态行为
- 不支持 SecurityManager、JMX 远程、Signal 处理等特性

**Spring Native**（第 31 章）：
- Spring AOT 引擎：编译阶段处理 @Autowired/@Value/@ConfigurationProperties 反射依赖
- 动态代理在 Native Image 需 proxy-config.json 声明；CGLIB 需 reflect-config 声明
- 现代对应：Spring Boot 3.5 原生支持 GraalVM Native Image

---

### 12. JVM 故障排查域（第 18 章，**新增遗漏补充**）

**核心主题**：基于真实生产案例的 JVM 故障排查方法论 — jstack 线程分析 + OOM Killer + Native Memory Tracking

**优化方法论提炼**：
- **ThreadPoolExecutor 泄漏模式**：方法内创建 `newFixedThreadPool(1)` 未 shutdown → core thread WAITING 永久 → 线程泄漏。解决方案：单例共享 ThreadPoolExecutor / 合理设置 KeepAlive
- **fastthread.io 在线分析**：上传 jstack dump 文件自动分析线程状态分布、死锁检测、CPU 热点
- **OOM Killer 排查**：`grep "Out of memory" /var/log/messages` 检查 OS 级别 OOM
- **top vs JVM 实际内存差异**：实际 600MB > JVM 堆内+堆外 319MB，原因在于 NMT 的 8 个内存区域
- **NMT（Native Memory Tracking）公式** — Java 进程内存 = heap（-Xmx）+ class（metaSpace）+ **thread（线程栈 × 线程数，不受限！）** + code（JIT，-XX:ReservedCodeCacheSize）+ gc（G1 最多 10%堆，ZGC 最多 15-20%堆）+ compiler + internal + symbol
- **GC 额外内存占用**：Parallel GC 几乎不占、G1 最多 10%堆额外、ZGC 最多 15-20%堆额外（持续优化中）
- **僵尸进程检查**：`ps -A -ostat,ppid,pid,cmd | grep -e '^[Zz]'`
- **Arthas 在线诊断**：`vmtool --action forceGc` 强制 Full GC、线程 trace、方法耗时分析
- 现代对应：JDK 21 + async-profiler + Arthas + fastthread.io

### 13. Dubbo Mesh 域（第 24 章，**补充展开**）

- xDS 协议：Dubbo 3.x 原生支持 Envoy xDS v3，与 Istio 控制面集成
- Proxyless Mesh：应用进程内处理，无需 Sidecar 代理，减少网络跳数
- K8s 服务发现：直接读取 K8s API Server，不再依赖注册中心（Nacos/ZK）
- Proxy Mesh vs Proxyless Mesh 对比：Proxy（Sidecar 全代理，跨语言支持） vs Proxyless（减少一跳延迟，应用内处理）
- 现代对应：Dubbo 3.3.x Proxyless Mesh + xDS + K8s Service

### 14. etcd 配置中心域（第 26 章，**补充展开**）

- etcd = CoreOS 出品的分布式 K-V 存储，Raft 共识，强一致性
- Watch 机制：一次注册持续监听 key 变更（对比 ZK 一次绑定后即失效）
- 高可用集群：3/5 节点 Raft 集群，Leader 处理写、Follower 处理读（ReadIndex 线性一致读）
- gRPC 网关：etcd v3 API 基于 gRPC，支持 HTTP/2 + SSL/TLS
- 对比 Nacos：etcd 纯粹 K-V 存储 + Watch，不适合结构化配置；Nacos 自带配置管理 UI + 命名空间/Group 隔离
- 现代对应：etcd 仍是 K8s 核心存储；配置场景 Nacos/Apollo 更合适

### 15. 工程案例与成长域（结营文档，**新增遗漏补充**）

**阿里云 2023.11.12 大规模故障案例**：
- 故障根因：OSS 单服务故障导致语雀文档不可用
- 核心教训：**故障隔离粒度** — 其他功能正常说明隔离不彻底，单点故障传播链过长
- 工程启示：服务依赖梳理 + 故障演练（Chaos Engineering）+ 降级预案

**架构师五层能力模型**：
- 成型案例 → 架构策略（八股文）→ **代码设计能力** → **架构能力（技能+沟通）**→ **底层功力（OS/存储/网络/算法/硬件）**
- 关于 35 岁危机：本质是"仅搬运技术不创造技术"带来的被动局面

**训练营演进路线回顾**：容器调优→微服务升级→注册发现→HTTP/RPC 架构→数据存储→Reactive→API 网关→Istio→Dubbo→配置中心→ELK→监控→GraalVM

---

## 附：stage-3 配套论文/资料

| 资料 | 内容 | 用途 |
|---|---|---|
| **Deep Dive into ZGC**（5.4MB 长论文） | ZGC 底层实现：colored pointers + load barriers + concurrent relocation | 理解 ZGC <1ms 暂停的根本原理 |
| **ZGC-OracleDevLive-2022.pdf** | ZGC 整体架构演讲 | 快速建立 ZGC 全局认知 |
| **Joker-ZGC-ConcStack.pdf** | ZGC 并发栈处理 | 深入了解并发标记/重定位栈处理 |
| **JFR Runtime Guide**（JDK 内置） | JFR 事件类型、配置参数、事件流 API | JFR 在生产环境中的高级用法 |

GC 是"三高"架构最后的性能堡垒，ZGC 作为低延迟 GC 的标杆是理解现代 JVM 性能调优的关键入口。

---

## 附：第三轮查漏补缺 — 关键参数/策略名汇总

### RPC 升级（第 10 章）

- Triple 协议 5 个自定义 Header：`tri-service-version`、`tri-service-group`、`tri-trace-traceid`、`tri-trace-rpcid`、`tri-unit-info`
- Triple 三序列化策略：Protobuf（原生）+ **Hessian + JSON**（Java 用户无需 IDL）
- Triple 支持 mTLS 双向 TLS 认证
- Triple Streaming 模型：同一 TCP 连接多 Stream，StreamId 标识，元信息与 Payload 分离避免中间设备解析

### MySQL MGR（第 11 章）

- 单主模式（Single-Primary）vs 多主模式（Multi-Primary）两策略名

### 数据存储（第 12 章）

- StatementType 三枚举：`STATEMENT` / `PREPARED` / `CALLABLE`
- CachingExecutor 装饰器：`cacheEnabled=true` 时自动包裹任意 ExecutorType
- MyBatis 两个事务实现名：`JdbcTransaction`（默认，延迟获取连接）+ `ManagedTransaction`

### 分布式事件（第 16 章）

- `RedisCommandEvent.serializationVersion = VERSION_V1` + 8 个 transient 字段（applicationName/sourceBeanName/method/args/interfaceName/parameterTypes/parameterCount）

### Reactive（第 17 章）

- Schedulers 四类型（补 `immediate()`）：`immediate()`（当前线程）/ `single()` / `boundedElastic()`（替代已弃用的 elastic()）/ `parallel()`
- Reactive Streams 四组件：`Publisher.subscribe(Subscriber)` / `Subscriber(onSubscribe/onNext/onError/onComplete)` / `Subscription.request(n)/cancel()` / `Processor<T,R>`
- WebFlux 组件链：`HttpHandler` → `WebHandler` → `WebFilter`(0..N) → `WebExceptionHandler`(0..N)

### RPC 网关（第 20 章）

- Gateway 性能瓶颈量化：每次请求 **3 次 `Arrays.copyOf`** + M+N+1 次 DefaultGatewayFilterChain 对象创建
- CachingFilteringWebHandler 优化方案：MethodHandle + volatile HashMap 缓存 + RefreshRoutesResultEvent 驱动

### Istio（第 21-22 章）

- 四大 Golden Signals：Latency / Traffic / Errors / Saturation
- envoy 代理指标名：`envoy_cluster_internal_upstream_rq`、`envoy_cluster_ssl_connection_error`、`envoy_cluster_lb_subsets_removed`
- istio 服务指标 label 维度：`connection_security_policy` / `destination_app` / `reporter` / `response_flags`
- 4 种追踪后端：Zipkin / Jaeger / LightStep / Datadog
- 可视化工具 **Kiali**
- 开源软件评估 5 条标准：团队稳定性 / 社区活跃度 / 缺陷修复及时性 / 商业化支持 / 开源协议影响

### Dubbo 架构（第 23 章）

- RPC Profiles 三模式：**min**（仅RPC）/ **default**（RPC+Registry+LB+Routing+SPI）/ **all**（default+Monitoring+Metrics+Tracing+Logging）
- 8 个子模块名：common / remoting / rpc / cluster / registry / monitor / config / container

### Nacos 配置中心（第 25 章）

- 配置变更通知 6 要素：配置内容(String/byte[]) + 配置 ID(String) + 变更时间 + **MediaType(JSON/XML/Binary)** + **字符集(UTF-8/US-ASCII)** + 配置版本
- 数据模型默认值：**Namespace=""（public）** + **Group="DEFAULT_GROUP"**
- 健康保护阈值：0~1 浮点数，低于时全返回防雪崩

### 配置客户端（第 27 章）

- `@PropertySource` 4 个设计缺陷：value() 必填无法扩展、不支持自动刷新、不支持绝对/相对顺序、Spring 内部处理
- `@ResourcePropertySource` 扩展注解 3 特性：支持顺序 / 支持动态配置 / 支持多资源

### 日志平台（第 29 章）

- logback-kafka-appender 版本 0.2.0 + 关键参数：`topic=kafka-logging-channel`、`deliveryStrategy=AsynchronousDeliveryStrategy`、`keyingStrategy=NoKeyKeyingStrategy`
- ES 清理工具 **Elastic Curator** + Docker Elk 项目 **deviantony/docker-elk**

---

## 三、过时技术 → 现代替代映射表

| stage-3 过时技术 | 现代替代 | 保留的主题 |
|---|---|---|
| Eureka（已废弃） | **Nacos** | 服务注册发现 |
| Eureka JGroups/Tomcat Tribes | **Raft/gRPC** | 集群复制 |
| Spring Cloud Netflix | **Spring Cloud Alibaba** | 服务治理 |
| CMS GC（JDK 14 移除） | **ZGC / G1** | JVM GC 调优 |
| JDK 8/11 | **JDK 21 LTS（虚拟线程+分代ZGC+GraalVM AOT）** | 现代 Java |
| RestTemplate | **RestClient + WebClient** | REST 客户端 |
| JPA | **MyBatis-Plus**（精确 SQL 控制） | ORM |
| OpenTSDB | **VictoriaMetrics / Mimir** | 监控存储 |
| RSocket（衰退） | **gRPC Triple** | 流式通信 |

## 四、对 sca-lab 的影响

**现有模块补充**：
- **performance 模块**（可选→提升优先级）：JVM 调优（ZGC 分代 + 虚拟线程）、JMH 基准测试、容器调优（Tomcat + GraalVM AOT）、Reactive 并行策略对比
- **nacos 模块**补充：配置中心 CP/DB 双模式 + Spring Environment 整合
- **dubbo 模块**补充：Triple 协议 + Proxyless Mesh + Custom Router/Filter
- **sentinel 模块**补充：Sentinel + Micrometer 指标整合

---

## 五、待 review 检查点

1. **15 个功能域划分是否合理**？各域覆盖的章节是否有遗漏？
2. 过时技术 → 现代替代映射是否完整（9 组）？
3. ZGC 8.9x 提升的定量发现是否准确？
4. 现代对应判断是否正确（RSocket 衰退 → gRPC Triple、虚拟线程替代 OS 线程池等）？
5. 第三轮查漏补缺附录中的关键参数是否完整？
