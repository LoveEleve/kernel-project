# 09 收束：一次调用的一生 + 设计哲学

> 定位：Dubbo 系列第 09 篇（主线收束）——把 01-08 串成一次调用的完整旅程，复盘设计哲学，并与 xhs-project 的 Feign 栈对照。（第 10/11 篇为覆盖补全篇：集群路由/LB 深化、协议扩展与 Triple。）
> lab：`scripts/10-full-journey.sh`；证据：`dubbo-lab/evidence/10-full-journey.log`。
> 课程对照：《Dubbo源码剖析与实战》结束语 + 《RPC实战与核心原理》24。

---

## 一、先看实验：一次调用的完整旅程

一次 `sayHello` 调用，端到端四个观测点：

**① 消费方 Filter**（`ClientTraceFilter`，第 04 篇的自定义扩展）：

```
[client-trace] 发起调用 sayHello（SPI 自定义 Filter 生效）
[client-trace] 调用完成 sayHello in 621ms
```

**② 网络字节**（wire-proxy 抓包，前 64 字节）：

```
[C->P] da bb c2 00 1a cb 44 e6 35 c3 be 2d 00 00 00 ef 05 32 2e 30 2e 32 30 23 63 6f 6d 2e 6d 79 78 68 73 2e 64 75 62 62 6f 2e 6c 61 62 2e 61 70 69 2e 44 65 6d 6f 53 65 72 76 69 63 65 05 30 2e 30 2e
[P->C] da bb 02 14 1a cb 44 e6 35 c3 be 2d 00 00 00 34 94 30 23 68 65 6c 6c 6f 20 74 69 6d 65 6f 75 74 2d 6c 61 62 20 66 72 6f 6d 20 6c 61 62 2d 70 72 6f 76 69 64 65 72 48 05 64 75 62 62 6f 05 32 2e
```

**③ 提供方 Filter + 服务实现**（`TraceFilter` + `DemoServiceImpl`）：

```
[trace] provider 收到 sayHello（SPI 自定义 Filter 生效）
[trace] provider 完成 sayHello in 142ms
```

服务实现日志：`sayHello 收到调用, name=timeout-lab, 线程=DubboServerHandler-...-thread-2`。

**④ 消费方拿到结果**：

```
[lab-consumer] sayHello = hello timeout-lab from lab-provider
```

一条时间轴：**消费方 621ms**（含建连 + 首次初始化开销）→ 网络（16 字节头 + hessian2 body）→ **提供方 142ms**（Filter → 服务方法）→ 响应按同一 request id 配对返回。这一篇把这条路径上的每一层，对应回前八篇。

---

## 二、一次调用的一生（01-08 全景）

```
consumer 方法调用
  → 代理 InvokerInvocationHandler（第 02 篇：ReferenceConfig 生成）
  → 集群：Router 路由 → LoadBalance 选实例 → Failover 容错（第 05/07 篇）
  → 过滤器链：ConsumerContextFilter(附件) → CacheFilter → ...（第 04/08 篇）
  → DubboInvoker → ExchangeCodec（16B 头 + hessian2）→ Netty（第 06 篇）
─── 网络（长连接；request id 配对）───
  → Netty 解码 → 线程池派发 → 过滤器链 → （按需）反序列化 → 反射调用实现（第 06/08 篇）
  ← 响应原路返回；DefaultFuture 按 request id 完成挂起的调用（第 06 篇）
```

| 阶段 | 做了什么 | 关键源码 | 篇目 |
|---|---|---|---|
| 1. 启动装配 | `DubboBootstrap` 装配配置 → `ServiceConfig.export`（injvm + 远程）→ 注册中心注册 | `ServiceConfig`、`DubboBootstrap` | 02/03 |
| 2. 消费方引用 | `ReferenceConfig.init` 生成代理；订阅目录 | `ReferenceConfig`、`InvokerInvocationHandler` | 02/03 |
| 3. 集群层 | Router 过滤 → LoadBalance 选一个 → Cluster 容错（默认 Failover：retries+1） | `RouterChain`、`AbstractClusterInvoker`、`FailoverClusterInvoker` | 05/07 |
| 4. 消费方 Filter 链 | 附件透传、缓存、泛化还原……SPI 按 URL 参数组装 | `ConsumerContextFilter`、`CacheFilter`、`GenericImplFilter` | 04/08 |
| 5. 协议编码 | 16 字节头（魔数/flag/id/长度）+ hessian2 body | `ExchangeCodec.encodeRequest` | 06 |
| 6. 网络 | 长连接、心跳、request id | `NettyClient`、`DefaultFuture` | 06 |
| 7. 提供方 | 解码 → 线程池派发（耗尽保护）→ Filter 链 → 反射调用实现 | `DubboProtocol`、`FixedThreadPool`、`AbstractProxyInvoker` | 06/08 |
| 8. 响应 | 编码 → 原路返回 → Future 按 id 配对 → 代理返回业务值 | `DefaultFuture.received` | 06 |
| 9. 治理面 | 超时/重试、路由、优雅上下线、QoS 诊断 | `FailoverClusterInvoker`、`TagStateRouter`、`DubboShutdownHook`、QoS | 07/08 |

---

## 三、设计哲学复盘

**1. 统一 URL 模型：一切皆 URL。** 地址、协议、超时、重试、标签、线程数……全部落在 `URL` 上（`URL.valueOf`，`URL.java:237`）。路由、LB、注册、配置读取读的都是它——所以本系列的实验大多只是"改一个 URL 参数"（02 的超时、05 的 LB、07 的 tag/retries、08 的 cache/executes）。

**2. 微内核 + SPI：核心只定义扩展点。** Protocol / Filter / LoadBalance / Cluster / Router / CacheFactory……全部可换（第 04 篇：`ExtensionLoader` + `@Adaptive` + Wrapper，Protocol 启动时被六层 wrapper 包住）。框架的"可替换性"不是口号，是 SPI 文件 + URL 参数选择的机制。

**3. 分层责任链：每层只做一件事。** Filter 链、Router 链、集群策略各自组合、互不耦合；出问题时可以分层定位（第 05 篇的调用链、第 08 篇的线程池）。

**4. 模型隔离（3.x 架构）：FrameworkModel → ApplicationModel → ModuleModel。** 资源（线程池、注册表、扩展实例）按模型归属与隔离（`dubbo-common/.../rpc/model/`），多应用共存不再是"全局静态变量"的碰运气。

**5. 契约先行 + 二进制效率。** 接口 jar 就是契约（编译期检查）；16 字节头 + hessian2 是性能选择（第 06 篇）——对比 Feign 的 HTTP/JSON（见下节）。

**6. 运维内建：把"上线后"当一等公民。** QoS 命令、优雅下线、readonly 事件、线程池耗尽监听（第 07/08 篇）——不是外挂工具，是框架的一部分。

---

## 四、与 xhs-project 对照：Feign / Spring Cloud 栈

xhs-project 的事实（pom 与代码）：`spring-cloud-starter-openfeign`（多模块引入）、`feign-hc5`（连接池，父 pom `:186`）、`@FeignClient` 接口（如 `OrderFeignClient`/`ContentFeignClient`/`UserFeignClient`）、Nacos discovery/config、父 POM 引入 Sentinel（网关适配 + 服务限流，`:347-359`）。

两个有趣的"同题不同解"：

- `FeignUnifiedConfig`：统一 `RequestInterceptor` 在 common 层注入"染色标记 + 认证 Token"——对应 Dubbo 的 **RpcContext 附件**（第 08 篇）；
- `LeastConnectionsLoadBalancer`：自定义 `ReactorServiceInstanceLoadBalancer`（最少连接数）——对应 Dubbo 的 **LoadBalance SPI**（第 05 篇）。

| 维度 | Dubbo（本系列） | Feign / Spring Cloud（xhs-project） |
|---|---|---|
| 协议 | 二进制（16B 头 + hessian2），长连接 | HTTP/1.1（JSON），连接池 |
| 契约 | 接口 jar（编译期） | 接口 + 注解（HTTP 语义） |
| 治理位置 | SDK 内建（路由/LB/容错/重试） | 组合/外置（LoadBalancer、Sentinel、Gateway） |
| 服务发现 | 注册中心（接口级 + 应用级） | Nacos discovery（应用级） |
| 隐式传参 | `RpcContext` 附件 | `RequestInterceptor` 注入 Header |
| 调试 | 二进制需工具（QoS/wire 抓包） | curl 直接可读 |

**为什么 xhs-project 用 Feign？** Spring 生态一致性（Security/Gateway/Actuator 全家桶）、HTTP 的可读性与调试友好、对外/网关/前端场景天然适配。**Dubbo 适合什么？** 内部高频调用、强治理、低延迟、接口契约明确的场景。两者可以共存（Spring Cloud Alibaba 同时支持），不是二选一。

---

## 五、生产检查清单（全系列浓缩）

| 检查项 | 动作 / 参数 | 篇目 |
|---|---|---|
| 配置来源与覆盖 | `-D` / 外部化配置 / consumer-provider-method 三级覆盖；启动日志核对实际 URL | 02 |
| 注册与订阅 | service key（path/group/version）一致性；空目录/禁用地址排查 | 03 |
| 超时预算 | `(retries+1) × timeout` < 上游超时 | 02/07 |
| 重试 | 幂等才重试；写操作 `retries=0` 或幂等键 | 07 |
| 序列化 | 白名单；跨版本兼容 | 06 |
| 线程池 | `threads`/`queues` 取舍；EXHAUSTED 日志快照 + 消费方错误 | 08 |
| 上下线 | 先 `offline` 摘流量 → 优雅下线（readonly + wait 排空） | 07/08 |
| 诊断 | QoS `ls/count`；wire 抓包；自定义 Filter trace | 06/08 |

---

## 六、方法论小结（这个系列是怎么做的）

1. **问题驱动**：每篇一个"朴素答案 → 死在哪 → 所以 → 代价"，先给例子再进原理；
2. **证据链**：每个结论配 lab 实验 + evidence 日志 + 源码坐标（file:line），不引用未验证的二手结论；
3. **演化式 lab**：同一套 dubbo-lab 从手写 RPC（01）演化到完整治理（08），10 个脚本可复现；
4. **边界诚实**：未实验的（熔断生态、validation）明确标注；版本口径（Dubbo 3.3.3 / JDK17）写清。

---

## 附录 A：面试速答（综合大题）

> 一次 Dubbo 调用：consumer 代理（ReferenceConfig 生成）→ 集群层（Router 过滤 → LoadBalance 选实例 → Cluster 容错，默认 Failover `retries+1` 次）→ 过滤器链（附件/缓存等）→ 协议编码（16 字节头 + hessian2）→ Netty 长连接 → provider 解码 → 线程池派发 → 过滤器链 → 反射调用实现 → 响应按 request id 配对返回。设计哲学：统一 URL 模型（一切皆 URL）、微内核 + SPI（Protocol/Filter/LB/Cluster 全可换）、分层责任链、模型隔离（Framework/Application/Module）、契约先行、运维内建（QoS/优雅下线）。与 Feign 对照：Dubbo 二进制 + SDK 内建治理；Feign HTTP/JSON + 组合外置治理（LoadBalancer/Sentinel/Gateway）；内部高频强治理选 Dubbo，对外/异构/简单选 HTTP。

## 附录 B：实验与复现（系列 12 个脚本）

```bash
cd dubbo-lab
export JAVA_HOME=/env/javaEnv/jdk17
mvn -DskipTests install
# 逐个运行（04/06/08/09/11 部分段落依赖 Nacos）
```

| 脚本 | 对应篇 | 一句话 |
|---|---|---|
| `01-naive-rpc.sh` | 01 | 手写最简 RPC（零依赖） |
| `02-direct-invoke.sh` | 02 | 直连调用与超时覆盖 |
| `03-timeout-precedence.sh` | 02 | 三级超时对照 |
| `04-registry-nacos.sh` | 03 | 注册/订阅与上下线通知 |
| `05-spi-extension.sh` | 04 | SPI 扩展与 Filter 生效 |
| `06-call-chain-lb.sh` | 05 | 调用链 + 多实例 LB 对照 |
| `07-protocol-async.sh` | 06 | 抓包 + 异步 + timeout 真相 |
| `08-failover-graceful.sh` | 07 | 重试放大 + 优雅下线 + 标签路由 |
| `09-governance-qos.sh` | 08 | 附件/泛化/缓存/QoS/线程池耗尽 |
| `10-full-journey.sh` | 09 | 一次调用端到端 trace |
| `11-routing-lb-group.sh` | 10 | 条件路由（直连对照）+ 最少活跃 + 分组 |
| `12-triple-protocol.sh` | 11 | 双协议共存 + Triple HTTP/2 抓包 |

## 附录 C：源码坐标（设计哲学相关）

| 内容 | 坐标 |
|---|---|
| 统一 URL 模型 | `dubbo-common/.../common/URL.java:237`（`valueOf`） |
| 微内核 + SPI | `dubbo-common/.../extension/ExtensionLoader.java`；第 04 篇附录 C（Protocol 六层 wrapper） |
| 模型隔离 | `dubbo-common/.../rpc/model/{ScopeModel,FrameworkModel,ApplicationModel,ModuleModel}.java` |
| 集群/路由/LB | `dubbo-cluster/.../support/{AbstractClusterInvoker,FailoverClusterInvoker}.java`、`.../router/tag/TagStateRouter.java` |
| 协议/网络 | `dubbo-remoting/.../codec/ExchangeCodec.java`、`.../support/DefaultFuture.java` |
| 治理/诊断 | `dubbo-config/.../config/DubboShutdownHook.java`、`dubbo-plugin/dubbo-qos/.../command/impl/*` |
| xhs-project 对照 | `xhs-project/pom.xml:186`（feign-hc5）、`:347-359`（Sentinel）；`my-xhs-common/.../feign/config/FeignUnifiedConfig.java`、`.../loadbalancer/LeastConnectionsLoadBalancer.java` |
| 完整坐标 | 各篇附录 C（01-08） |
