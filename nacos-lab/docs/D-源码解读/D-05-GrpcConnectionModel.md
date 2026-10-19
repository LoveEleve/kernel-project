# D-05: gRPC 连接模型 — GrpcServer + ConnectionManager + RequestHandler

> 前置依赖: D-03（Naming SDK 全链路） | 后续: D-06（Client 模型）、D-07（注册处理链）、D-08（Push + 健康检查）
> 与 D-03 呼应: 客户端用 `rpcClient.request()` 发送 → 服务端用 `BaseGrpcServer` 接收

---

## 一、概述

Nacos 2.x 最大的架构变革：从 HTTP + 轮询心跳变为 gRPC 长连接。这篇覆盖服务端的 gRPC 基础设施——三层端口架构、连接生命周期、请求路由体系。理解连接模型是理解后续数据存储、事件驱动、健康检查的基础。

---

## 二、三层 gRPC Server 架构

```
Nacos Process
├── GrpcSdkServer     (port: 8848 + 1000 = 9848)
│     ↑ 客户端 SDK 连接此端口 — 注册/发现/订阅/推送
│     └── ConnectionManager（独立实例，管理客户端连接）
│
├── GrpcClusterServer (port: 8848 + 1001 = 9849)
│     ↑ Nacos 集群节点间通信 — Distro 同步 / JRaft
│     └── ConnectionManager（独立实例，管理集群节点连接）
│
└── RaftServer         (port: 8848 - 1000 = 7848)
      ↑ JRaft 专用 — Leader 选举 / 日志复制
```

```java
// Constants.java:90-92
public static final Integer SDK_GRPC_PORT_DEFAULT_OFFSET     = 1000;  // +1000 = 9848
public static final Integer CLUSTER_GRPC_PORT_DEFAULT_OFFSET = 1001;  // +1001 = 9849
```

**为什么需要三层端口隔离？** 不同的流量有不同的敏感度——客户端请求频率高但单次数据量小（注册/查询），集群间 Distro sync 数据量大且需要尽快完成（否则数据延迟窗口扩大），Raft 对网络延迟最敏感（100ms+ 可能触发 Leader 切换）。独立端口 = 独立限流/监控/熔断策略，避免相互干扰。

端口偏移设计的巧思：运维只需要配置 `server.port=8848`，gRPC 端口和 Raft 端口自动推算——减少配置项，降低出错概率。

---

## 三、GrpcServer 启动流程 — 从 Spring Boot 到 Netty

### 3.1 启动时机

```
Spring Boot 生命周期:
  NacosApplicationListener（监听 Spring 事件）
    → started 阶段 → GrpcSdkServer.start() + GrpcClusterServer.start()
    → 确保 HTTP server(8848) 先启动，gRPC 后启动
```

### 3.2 BaseGrpcServer.startServer() — 源码级分析

```java
// BaseGrpcServer.java:73-95
@Override
public void startServer() throws Exception {
    // 步骤1: 准备 gRPC Service 注册表（动态注册，非注解扫描）
    final MutableHandlerRegistry handlerRegistry = new MutableHandlerRegistry();
    addServices(handlerRegistry, getSeverInterceptors().toArray(new ServerInterceptor[0]));
    // addServices 内部注册两个核心 Service:
    //   ① GrpcRequestAcceptor — 处理单向请求（register/deregister/query）
    //   ② GrpcBiStreamRequestAcceptor — 处理双向流请求（连接建立 + 服务端推送）

    // 步骤2: 构建 Netty Server
    NettyServerBuilder builder = NettyServerBuilder.forPort(getServicePort())
        .executor(getRpcExecutor())                    // 自定义 ThreadPoolExecutor
        .addTransportFilter(transportFilters...)         // 连接建立/断开事件拦截
        .intercept(serverInterceptors...)               // gRPC Interceptor 链（鉴权前置）
        .build();

    // 步骤3: 启动
    server = builder.build();
    server.start();
    LOG.info("Nacos {} Grpc server started at port {}", serverName, getServicePort());
}
```

**为什么用 MutableHandlerRegistry 而不是 Spring 的 @Component 注解？** gRPC Service 定义和 Spring Bean 是不同的生命周期——gRPC Handler 需要在 gRPC Server 启动时注册到 grpc 框架，而不是在 Spring Context 初始化时。`MutableHandlerRegistry` 允许运行时动态添加/移除 Handler。

### 3.3 客户端请求到达后的完整路由

```
客户端 gRPC 请求（D-03 的 NamingGrpcClientProxy.request）
  → 网络传输 → Netty Server 接收
  → GrpcRequestAcceptor.request(payload)
    → 反序列化 Payload → 提取 Request.type（如 "REGISTER_INSTANCE"）
    → RequestHandlerRegistry.getByRequestType(type)
      → 返回对应的 Handler 实例（如 InstanceRequestHandler）
    → handler.handleRequest(request, meta)
      → filter chain（鉴权 → 限流 → 参数校验）
      → handler.handle(request, meta)
        → 实际业务处理（D-07 的注册处理链）
```

---

## 四、ConnectionManager — 连接生命周期的守门人

### 4.1 register — 连接注册五步检查

```java
// ConnectionManager.java:100-128
public synchronized boolean register(String connectionId, Connection connection) {
    if (connection.isConnected()) {
        // 步骤1: 获取客户端 IP
        String clientIp = connection.getMetaInfo().clientIp;

        // 步骤2: 去重 — 同一个 connectionId 不能重复注册
        if (connections.containsKey(connectionId)) {
            return true;
        }

        // 步骤3: 限流检查 — 每 IP / 每 AppName 最大连接数
        if (checkLimit(connection)) {
            return false;  // 超限 → 拒绝 → 客户端连接关闭
        }

        // 步骤4: 注册
        connections.put(connectionId, connection);                    // connectionId → Connection
        if (!connectionForClientIp.containsKey(clientIp)) {
            connectionForClientIp.put(clientIp, new AtomicInteger(0));
        }
        connectionForClientIp.get(clientIp).getAndIncrement();       // clientIp 计数 +1

        // 步骤5: 通知所有 ConnectionEventListener
        // → 这会触发 ClientManagerDelegate.clientConnected (D-06)
        // → 创建 IpPortBasedClient 或 ConnectionBasedClient
        clientConnectionEventListenerRegistry.notifyClientConnected(connection);

        return true;
    }
    return false;
}
```

**为什么整个方法是 `synchronized` 的？** `connections` 和 `connectionForClientIp` 是两个需要原子性更新的数据结构——如果一个线程 A 在 `put(connectionId)` 后、`getAndIncrement()` 前被线程 B 打断，两个线程同时操作同一个 IP 的不同 connectionId，`connectionForClientIp` 的计数可能错误。`synchronized` 保证注册/注销的原子性。

连接注册的频率不高（每秒几百），`synchronized` 不是瓶颈。真正的高频操作是 `connections.get(connectionId)` ——这个不需要锁，因为 `ConcurrentHashMap` 自己保证并发安全。

### 4.2 checkLimit — 限流机制

```java
// ConnectionManager.java:130-141
private boolean checkLimit(Connection connection) {
    // 集群内部连接跳过限流（9849 端口的节点间通信不应该被限流）
    if (connection.getMetaInfo().isClusterSource()) {
        return false;
    }
    // 通过 ControlManagerCenter 检查：
    //   - 每 IP 最大连接数
    //   - 每 AppName 最大连接数
    //   - 连接数限流规则可通过 Nacos Dashboard 动态调整
    ConnectionCheckResponse checkResponse = ControlManagerCenter.getInstance()
        .getConnectionControlManager().check(connectionCheckRequest);
    return !checkResponse.isSuccess();
}
```

### 4.3 unregister — 连接注销

```java
// ConnectionManager.java:148-160
public synchronized void unregister(String connectionId) {
    Connection remove = this.connections.remove(connectionId);
    if (remove != null) {
        // 步骤1: 清理 IP 连接计数
        String clientIp = remove.getMetaInfo().clientIp;
        AtomicInteger count = connectionForClientIp.get(clientIp);
        if (count != null && count.decrementAndGet() == 0) {
            connectionForClientIp.remove(clientIp);
        }

        // 步骤2: 通知所有 ConnectionEventListener
        // → ClientManagerDelegate.clientDisconnected → ClientReleaseEvent
        // → ClientServiceIndexesManager 清理索引(D-07)
        // → DistroClientDataProcessor 同步删除到其他节点(D-09)
        clientConnectionEventListenerRegistry.notifyClientDisConnected(remove);
    }
}
```

**注销触发的三种场景**：
1. 客户端主动 shutdown → gRPC 连接关闭 → `onComplete` → unregister
2. gRPC keep-alive 超时 → 连接断开 → unregister
3. 管理端主动踢连接 → `evictConnection()`

**为什么注销是整个 Nacos 2.x 健康检查的起点？** 连接断开 = 客户端不可用。临时实例的连接断开 → `ClientReleaseEvent` → 自动清理该 Client 下所有实例（D-08）。1.x 需要等 30s 的 `IP_DELETE_TIMEOUT`，2.x 是毫秒级的"断开即删"。

---

## 五、GrpcConnection - 两种 gRPC 方法

Nacos gRPC 在 `RequestGrpc` 服务上定义了**两个 RPC 方法**,对应两种通信模式:

### 方式1: `request` (unary 单向请求/响应) - 客户端发起的所有请求

```java
// GrpcConnection.request() - 客户端 -> 服务端的普通请求
response = grpcConnection.request(request, timeoutMs)
  -> grpcFutureServiceStub.request(payload)   // gRPC unary 调用(不是 StreamObserver!)
  -> 阻塞等待 ListenableFuture 返回 Response -> 超时抛异常

// 所有客户端发起的请求都走这条路径:
//   register / deregister / query / subscribe / ConfigBatchListenRequest / ConfigQueryRequest
```

这是标准的 gRPC unary RPC:客户端发起请求,服务端处理后返回单个响应。`subscribe` 也是走这条路径(发送 `SubscribeServiceRequest`,服务端返回当前全量 ServiceInfo)。

### 方式2: `requestBiStream` (双向流) - 连接建立 + 服务端推送

```java
// 连接建立时,客户端打开一个 bi-di stream:
StreamObserver<Payload> payloadStreamObserver = stub.requestBiStream(responseObserver);

// 客户端通过 stream 发送:
payloadStreamObserver.onNext(ConnectionSetupRequest)   // 连接建立
payloadStreamObserver.onNext(response)                  // 对服务端推送的响应

// 服务端通过 bi-di stream 推送(GrpcConnection.streamObserver):
connection.asyncRequest(NotifySubscriberRequest, callback)  // Naming 推送
connection.asyncRequest(ConfigChangeNotifyRequest, callback)  // Config 推送
  -> sendRequestInner: 生成 requestId -> sendRequestNoAck -> streamObserver.onNext(payload)
  -> 客户端收到后处理(ServerRequestHandler)并通过 stream 回送响应(带 requestId)
  -> RpcAckCallbackSynchronizer 匹配 requestId -> 完成 callback(带 ACK 确认)
```

bi-di stream 在**连接建立时创建一次**,持续整个连接生命周期。它的用途:
- **客户端 -> 服务端**:ConnectionSetupRequest(建立连接)、对服务端推送请求的响应
- **服务端 -> 客户端**:推送数据(Naming 的 `NotifySubscriberRequest`、Config 的 `ConfigChangeNotifyRequest`)

客户端收到推送后,通过注册的 `ServerRequestHandler` 处理(`NamingPushRequestHandler` / Config 的 configChange handler),再通过 stream 回送响应。

### 两种方式的协作

```
客户端                                    服务端
  |                                         |
  |--- requestBiStream (建立 stream) ------>|
  |--- ConnectionSetupRequest (via stream)->|  注册连接
  |                                         |
  |--- request(SubscribeServiceRequest) --->|  订阅(unary)
  |<-- SubscribeServiceResponse -------------|
  |                                         |
  |<-- NotifySubscriberRequest (via stream)--|  推送 ServiceInfo 变化
  |--- NotifySubscriberResponse (via stream)>|
  |                                         |
  |--- request(InstanceRequest, REGISTER)-->|  注册(unary)
  |<-- InstanceResponse --------------------|
```

> 易错点:subscribe 不是"建立双向流",而是一个普通的 unary 请求。双向流在连接建立时就已创建,所有推送(包括 subscribe 后的变更通知)都复用这同一条 stream。文档常见错误是把 subscribe 和 bi-di stream 混为一谈。

## 六、RequestHandler — filter chain + 具体处理

```java
// RequestHandler.java:46-59
public Response handleRequest(T request, RequestMeta meta) throws NacosException {
    // 第1步: 过滤器链（鉴权 → 限流 → 参数校验）
    for (AbstractRequestFilter filter : requestFilters.filters) {
        Response filterResult = filter.filter(request, meta, this.getClass());
        if (filterResult != null && !filterResult.isSuccess()) {
            return filterResult;  // filter 拒绝 → 直接返回拒绝响应
        }
    }

    // 第2步: 调用具体 Handler
    return handle(request, meta);
}
```

**过滤器链的职责分配**：

| Filter | 触发注解 | 作用 | 失败返回 |
|--------|---------|------|---------|
| AuthFilter | `@Secured(action=WRITE)` | token/ak/sk 鉴权 | 403 Response |
| TpsControlFilter | `@TpsControl(pointName="RemoteNaming...")` | 按 pointName QPS 限流 | 429 Response |
| ParamCheckFilter | `@ExtractorManager.Extractor` | 参数合法性校验 | 400 Response |

**Handler 注册与类型匹配机制**：

```
RequestHandlerRegistry:
  Map<String, RequestHandler> registry;  // type → handler

  InstanceRequest → REGISTER_INSTANCE / DE_REGISTER_INSTANCE → InstanceRequestHandler
  SubscribeServiceRequest → SUBSCRIBE_SERVICE → SubscribeServiceRequestHandler
  ServiceQueryRequest → QUERY_SERVICE → ServiceQueryRequestHandler
```

**为什么采用"类型字符串匹配"而不是"注解扫描"？** gRPC 请求的 `type` 字段是 Protobuf 定义的字符串常量（如 `"REGISTER_INSTANCE"`），直接做字符串匹配比 Java 的 `instanceof` 检查更快、更直观。而且注册新的 Handler 只需要在 `RequestHandlerRegistry` 中 `put` 一行，不需要修改框架代码。

### 6.1 以 InstanceRequestHandler 为例的完整路径

```java
// InstanceRequestHandler.java:53-87（注册/注销的 gRPC Handler）
@Override
@TpsControl(pointName = "RemoteNamingInstanceRegisterDeregister")  // 限流
@Secured(action = ActionTypes.WRITE)                               // 需要写权限
public InstanceResponse handle(InstanceRequest request, RequestMeta meta) throws NacosException {
    // 构建 Service 路由键（D-06）
    Service service = Service.newService(namespace, groupName, serviceName, true);
    InstanceUtil.setInstanceIdIfEmpty(request.getInstance(), service.getGroupedServiceName());

    switch (request.getType()) {
        case REGISTER_INSTANCE:  →  return registerInstance(service, request, meta);
        case DE_REGISTER_INSTANCE: → return deregisterInstance(service, request, meta);
    }
}

private InstanceResponse registerInstance(Service service, InstanceRequest request, RequestMeta meta) {
    clientOperationService.registerInstance(service, request.getInstance(), meta.getConnectionId());
    NotifyCenter.publishEvent(new RegisterInstanceTraceEvent(...));   // 追踪日志
    return new InstanceResponse(REGISTER_INSTANCE);
}
```

从这个例子可以看到 gRPC 请求处理的完整模式：**注解声明能力（@TpsControl / @Secured） → filter chain 执行拦截 → handle() 执行业务逻辑 → 发布事件触发下游**。

---

## 七、设计哲学分析

### 连接即生命周期 — 从应用层心跳到传输层检测

```
Nacos 1.x:  HTTP POST /beat（每 5s）→ 服务端定时扫描 lastBeat → 30s 删除
Nacos 2.x:  gRPC keep-alive（传输层自动检测）→ 连接断开 → 即刻 unregister
```

这个变革的价值不仅是"更快了"——它把健康检查从**应用层逻辑**变成了**基础设施**。应用开发者不需要关心"多久发一次心跳"，传输层自己保证检测的准确性和时效性。

### 三层端口隔离 — 运维灵活度最大化

客户端流量（9848）、集群流量（9849）、Raft（7848）三套完全独立的网络策略。如果客户端流量突然暴增（如全集群滚动重启），只会影响 9848 端口，不会干扰集群间同步和 Raft 共识。

---

## 八、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | 2.x 继续用 HTTP | HTTP 无双向流，心跳需额外维护 | gRPC 长连接 + bi-di stream |
| 2 | SDK Server 和 Cluster Server 共用端口 | 客户端流量高峰影响集群同步 | 三层端口隔离 |
| 3 | ConnectionManager 不做 maxConnection | 恶意客户端耗尽连接资源 | ControlManagerCenter 限流 |
| 4 | RequestHandler 跳过 filter chain | 无鉴权、无限流 | filter chain → handle |
| 5 | 连接断开后等心跳超时（像 1.x） | 30s 路由黑洞 | 连接断开即刻 unregister |
| 6 | GrpcConnection.request() 不设超时 | 服务端死锁 → 客户端线程永久阻塞 | requestTimeout 参数 |
| 7 | GrpcRequestAcceptor 用注解扫描而不是字符串匹配 | 运行时反射开销，无法热加载 | Map<String, Handler> 字符串匹配 |

---

## 九、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|---|---|---|
| 1 | 混合部署 1.x/2.x 节点 | 2.x 客户端连上 1.x → gRPC 不可用 → 注册失败 | 全集群统一升级 |
| 2 | keep-alive 配置不当 | 太大→长时间不知客户端挂了；太小→频繁重连 | ClientBeatCheckTaskV2 兜底（D-08） |
| 3 | 不区分 SDK/Cluster 端口监控 | 故障定位困难 | 端口天然分离 |
| 4 | 多 NacosNamingService 实例绕过 ConnectionManager 限流 | 同一 IP 创建大量连接 | 每 IP 连接数限制可动态调整 |
| 5 | filter 中做耗时操作 | Netty EventLoop 线程阻塞 → 所有请求延迟 | 无异步化保护 |

### 可运行的错误实验

**实验 — 多实例绕过 ConnectionManager 限流：**
```java
for (int i = 0; i < 100; i++) {
    NamingService ns = new NacosNamingService("127.0.0.1:8848");
    ns.registerInstance("test", "10.0.0." + i, 8080);
    // 忘记 shutdown → 100 个 gRPC 连接 + 100 个 eventloop + 100 个 redo 线程
}
// 服务端: curl /nacos/v2/core/cluster/metrics → connectionCount: 120（远超正常值）
```

---

## 十、面试 Q&A

### Q1: Nacos 2.x 为什么用 gRPC？

双向流（bi-di stream）让服务端可以主动 push 数据——HTTP 不具备这个能力。Protobuf 序列化比 JSON 小 3-10 倍。长连接本身作为隐式心跳——连接断开立即感知，不需要 1.x 的定时 HTTP 心跳。

### Q2: 连接断开后临时实例和持久实例分别怎样？

临时：unregister → ClientReleaseEvent → 清理索引 + 自动删除所有该 Client 下的实例。持久：仅标记不健康，数据保留，等待主动注销。

### Q3: gRPC 双向流在 Nacos 中怎么用的?

bi-di stream 在**连接建立时**就创建(客户端发送 ConnectionSetupRequest),不是 subscribe 时才建立。subscribe 本身是一个 unary 请求(`SubscribeServiceRequest`)。服务端推送 `NotifySubscriberRequest` 时,复用这条已存在的 stream 把数据发给客户端。一个连接只有一条 bi-di stream,承载该客户端所有推送。

### Q4: ConnectionManager.register 为什么是 synchronized？

connections 和 connectionForClientIp 两个 Concurrent 数据结构需要原子性更新。连接注册频率低（每秒几百），synchronized 不是瓶颈。

### Q5: RequestHandler filter chain 如何实现鉴权？（联动 D-01 Q5）

AuthFilter（@Secured）→ TpsControlFilter（@TpsControl）→ ParamCheckFilter（@ExtractorManager.Extractor）。每个 filter 独立、可插拔。鉴权不在接口层（NamingService 无任何注解），统一在服务端 handler 层处理。

---

## 十一、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| GrpcSdkServer | SDK 客户端 gRPC 端口，9848 | `grpc/GrpcSdkServer.java` |
| BaseGrpcServer.startServer | 注册 Acceptor + 构建 Netty Server | `grpc/BaseGrpcServer.java:73-95` |
| ConnectionManager.register | 去重→限流→注册→通知，synchronized | `ConnectionManager.java:100-128` |
| ConnectionManager.unregister | 删除→清理计数→通知，触发故障清理链 | `ConnectionManager.java:148-160` |
| GrpcRequestAcceptor | 反序列化+Handler匹配+filter chain调用 | `grpc/GrpcRequestAcceptor.java` |
| RequestHandler | filter chain → abstract handle() | `RequestHandler.java:46-59` |
| RpcPushService | 服务端主动推送通道 | `core/RpcPushService.java` |
| 端口偏移 | +1000=SDK, +1001=Cluster | `Constants.java:90-92` |

---

## 十二、测试分析

### 12.1 GrpcServerTest

`core/src/test/java/com/alibaba/nacos/core/remote/grpc/GrpcServerTest.java`

| 测试内容 | 验证点 |
|---------|--------|
| 启动 | Server 成功绑定指定端口 |
| 请求处理 | gRPC 请求被正确路由到对应 Handler |
| 关闭 | 优雅关闭释放端口 |

### 12.2 ConnectionManagerTest

`core/src/test/java/com/alibaba/nacos/core/remote/ConnectionManagerTest.java`

| 测试内容 | 验证点 |
|---------|--------|
| 连接注册 | `register()` 后可通过 `getConnection()` 获取 |
| 连接注销 | `unregister()` 后触发 `ConnectionEventListener` 回调 |
| 连接数追踪 | `tracedConnectionCount` 随注册/注销变化 |
| 重复注册 | 同一 connectionId 多次 register 只算一次 |

## 十三、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Factory** | `BaseGrpcServer.startServer()` 注册 Handler | 通过 MutableHandlerRegistry 动态注册 |
| **Chain of Responsibility** | `RequestHandler` filter chain | 请求经过多个 filter 后到达 handle() |
| **Observer** | `ConnectionEventListener` + `ConnectionManager` | 连接注册/注销时通知监听器 |
| **Registry** | `ConnectionManager` | 所有长连接的注册中心 |
| **Facade** | `GrpcSdkServer` / `GrpcClusterServer` | 对外暴露统一的 start/shutdown 接口 |

## 十四、本 Lab 中的实际映射

| Lab 场景 | 对应源码 |
|---------|---------|
| A-01 nacos-lab 启动时注册到 Nacos | `NacosNamingService` -> gRPC `InstanceRequest` -> `GrpcSdkServer` -> 注册处理链 |
| A-05/A-06 nacos-lab 实例健康检测 | gRPC keep-alive -> `ConnectionManager` 连接存活检测 |
| A-03 nacos-lab gRPC 连接 | `NamingGrpcClientProxy` -> `GrpcClient` -> `GrpcSdkServer:9848` |
