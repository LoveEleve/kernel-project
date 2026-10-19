# D-05 详细规划: gRPC 连接模型 — GrpcServer + ConnectionManager + RequestHandler

> 阶段: Phase 3 — 服务端基础设施 | 预期产出: `md/D-05-GrpcConnectionModel.md` | 状态: 待审核

---

## 一、为什么需要这篇

Nacos 2.x 最大的架构变革就是从 HTTP + 轮询心跳模型变为 gRPC 长连接模型。不理解 gRPC 连接模型，就理解不了为什么健康检查从"每 5s 发一个 HTTP 心跳"变成了"连接断开 = 实例失效"，也理解不了 D-07/D-08 的事件驱动注册处理为什么能工作。

这篇是服务端的"基础设施层"——GrpcServer 负责启动和监听, ConnectionManager 负责连接生命周期, RequestHandler 负责请求路由分派。

前置依赖: D-03 (Naming SDK 全链路 — 客户端如何发起 gRPC 请求)

> **与 D-03 的呼应**: D-03 讲的是客户端用 `RpcClientFactory.createClient()` + `rpcClient.request()` 发送请求; D-05 讲的是服务端用 `BaseGrpcServer.startServer()` 接收这些请求。

---

## 二、源码阅读清单 (按调用链顺序)

| 序号 | 源码文件 | 行数 | 必须阅读的内容 | 阅读目的 |
|------|----------|------|---------------|----------|
| 1 | `core/src/main/java/.../core/remote/grpc/BaseGrpcServer.java` | ~120 | startServer(73-95), addServices() | 理解 gRPC Server 启动流程 |
| 2 | `core/src/main/java/.../core/remote/grpc/GrpcSdkServer.java` | ~50 | getServicePort(), 端口偏移逻辑 | 理解 SDK server vs Cluster server 端口设计 |
| 3 | `core/src/main/java/.../core/remote/ConnectionManager.java` | ~200 | register(100-128), unregister(148-160), checkLimit(130-141) | 理解连接注册/注销 + 限流 |
| 4 | `core/src/main/java/.../core/remote/Connection.java` | ~80 | request(), close(), getMetaInfo() | 理解连接抽象 |
| 5 | `core/src/main/java/.../core/remote/grpc/GrpcConnection.java` | ~150 | request(单向请求), requestBiStream(双向流) | 理解 gRPC 连接的具体实现 |
| 6 | `core/src/main/java/.../core/remote/RequestHandler.java` | 71 | handleRequest(46-59) — filter chain + dispatch | 理解请求处理的 filter→handler 链路 |
| 7 | `core/src/main/java/.../core/remote/core/RpcPushService.java` | ~50 | push(connectionId, request) | 理解服务端主动推送能力 |
| 8 | `core/src/main/java/.../core/remote/grpc/GrpcRequestAcceptor.java` | ~80 | request() — 接收请求 → RequestHandler 分派 | 理解 gRPC 请求入口 |

---

## 三、分析要点详解

### 3.1 Nacos 2.x 的三层 gRPC Server 架构

```
Nacos Process
├── GrpcSdkServer (port: serverPort + 1000 = 9848)
│     ↑ 客户端 SDK 连接此端口(注册/发现/订阅/推送)
│     └── ConnectionManager (独立实例,管理 SDK 客户端连接)
│
├── GrpcClusterServer (port: serverPort + 1001 = 9849)
│     ↑ Nacos 集群节点间连接此端口(Distro 同步/JRaft 通信)
│     └── ConnectionManager (独立实例,管理集群节点间连接)
│
└── RaftServer (port: serverPort - 1000 = 7848)
      ↑ JRaft 协议专用端口(Leader 选举/日志复制)
```

**端口偏移常量** (`Constants.java:90-91`):
```java
public static final Integer SDK_GRPC_PORT_DEFAULT_OFFSET = 1000;    // +1000 = 9848
public static final Integer CLUSTER_GRPC_PORT_DEFAULT_OFFSET = 1001; // +1001 = 9849
```

**设计意图**:
- 客户端流量和集群间流量走不同的端口 → 隔离,方便独立限流/监控
- 端口偏移基于主端口(8848) → 只配置一个端口,其他自动推算
- SDK Server 和 Cluster Server 各有独立的 ConnectionManager → 客户端连接和集群连接分别管理

### 3.2 GrpcServer 启动流程

```
GrpcSdkServer (继承 BaseGrpcServer extends BaseRpcServer)
  → start() → startServer()
    
    // 步骤1: 准备 gRPC Service 定义
    → MutableHandlerRegistry handlerRegistry = new MutableHandlerRegistry()
    → addServices(handlerRegistry, interceptors)
      // 注册两个 Service:
      // 1. GrpcRequestAcceptor — 处理单向请求(register/deregister/query)
      // 2. GrpcBiStreamRequestAcceptor — 处理双向流请求(subscribe/push)
    
    // 步骤2: 构建 Netty Server
    → NettyServerBuilder.forPort(getServicePort())  // SDK:8848+1000=9848
        .executor(getRpcExecutor())               // 自定义 ThreadPoolExecutor
        .addTransportFilter(...)                    // 连接事件过滤器
        .intercept(...)                             // ServerInterceptor 链
        .build()
    
    // 步骤3: 启动
    → server.start()
    → LOG.info("Nacos {} Grpc server started at port {}", serverName, port)
```

**启动时机**: 通过 Spring Boot 生命周期控制:
- `NacosApplicationListener` 监听 Spring 事件
- `started` 阶段 → `GrpcSdkServer.start()` + `GrpcClusterServer.start()`
- 确保在 HTTP server(8848) 启动之后 gRPC server 才启动

### 3.3 ConnectionManager — 连接生命周期的"守门人"

#### register — 连接注册

```java
// ConnectionManager.register(connectionId, connection)
public synchronized boolean register(String connectionId, Connection connection) {
    if (connection.isConnected()) {
        String clientIp = connection.getMetaInfo().clientIp;
        
        // 1. 去重检查
        if (connections.containsKey(connectionId)) {
            return true;  // 已经注册过了
        }
        
        // 2. 限流检查(checkLimit)
        if (checkLimit(connection)) {
            return false;  // 超过每 IP 最大连接数
        }
        
        // 3. 追踪标记(仅限监控 IP 列表中的 IP)
        if (traced(clientIp)) {
            connection.setTraced(true);
        }
        
        // 4. 注册
        connections.put(connectionId, connection);                       // connectionId → Connection
        connectionForClientIp.get(clientIp).getAndIncrement();           // clientIp 计数 +1
        
        // 5. 通知所有 ConnectionEventListener
        clientConnectionEventListenerRegistry.notifyClientConnected(connection);
        
        return true;
    }
    return false;
}
```

**限流机制** (checkLimit):
- 通过 `ControlManagerCenter.getConnectionControlManager().check()` 检查
- 支持: 每 IP 最大连接数限制、每 AppName 最大连接数限制
- 集群内部连接跳过限流(`isClusterSource()`)
- 超限 → register 返回 false → 客户端收到拒绝 → 连接关闭

#### unregister — 连接注销

```java
// ConnectionManager.unregister(connectionId)
public synchronized void unregister(String connectionId) {
    Connection remove = this.connections.remove(connectionId);
    if (remove != null) {
        String clientIp = remove.getMetaInfo().clientIp;
        AtomicInteger count = connectionForClientIp.get(clientIp);
        if (count != null && count.decrementAndGet() == 0) {
            connectionForClientIp.remove(clientIp);  // 清理计数
        }
        // 通知所有 ConnectionEventListener (包括 GrpcRedoService-like 机制)
        clientConnectionEventListenerRegistry.notifyClientDisConnected(remove);
    }
}
```

**注销触发时机**:
1. 客户端主动 shutdown → gRPC 连接关闭 → `onComplete` 回调 → unregister
2. gRPC keep-alive 超时 → 连接断开 → unregister
3. 管理端主动踢连接 → `ConnectionManager.evictConnection()`

### 3.4 GrpcConnection — gRPC 连接的具体实现

Connection 是抽象, GrpcConnection 是 gRPC 实现:

```java
// GrpcConnection 提供两种通信方式:
// 1. 单向请求/响应 (用于 register/deregister/query)
response = grpcConnection.request(request, timeoutMs)
  → 构建 Metadata + Payload(Request 序列化)
  → StreamObserver.onNext(payload)  // 发送
  → 阻塞等待 StreamObserver.onNext(response) // 等待响应
  → 返回 Response

// 2. 双向流 (用于 subscribe/push)
grpcConnection.requestBiStream(request, responseObserver)
  → 构建 Metadata + Payload(Request 序列化)
  → StreamObserver.onNext(payload)  // 发送
  → responseObserver.onNext(response)  // 每个响应通过 Observer 回调
```

### 3.5 RequestHandler 体系 — filter chain + handler dispatch

```java
// RequestHandler.handleRequest(request, meta)
public Response handleRequest(T request, RequestMeta meta) throws NacosException {
    // 第1步: 过滤器链
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

**过滤器链的典型角色**:
- `@Secured` → AuthFilter: 鉴权(token/ak/sk 校验)
- `@TpsControl` → TpsControlFilter: 限流(指定 pointName 的 QPS 限制)
- `@ExtractorManager.Extractor` → ParamCheckFilter: 参数校验

**Handler 注册和匹配**:
- `RequestHandlerRegistry` 维护 `Map<String, RequestHandler>` — type → handler
- 请求到来 → 解析 type → registry.get(type) → 调用对应的 Handler.handle()
- 典型 Handler: `InstanceRequestHandler`(注册/注销), `SubscribeServiceRequestHandler`(订阅), `ServiceQueryRequestHandler`(查询)

### 3.6 RpcPushService — 服务端主动推送的通道

gRPC 双向流让服务端可以主动推送数据给客户端:

```java
// RpcPushService.push(connectionId, request)
→ connections.get(connectionId)  // 从 ConnectionManager 查找连接
→ grpcConnection.requestBiStream(request, callback)
  → 通过 gRPC StreamObserver 发送
  → 客户端 NamingPushRequestHandler 接收
  → ServiceInfoHolder.processServiceInfo()
```

---

## 四、设计哲学分析

### 4.1 连接即生命周期的哲学

Nacos 1.x: 客户端 = HTTP 无状态请求, 心跳 = 额外的 HTTP 请求维持存活
Nacos 2.x: 客户端 = gRPC 长连接, 连接存活 = 客户端存活

**为什么这个变革如此重要？**
- 1.x: 注册和心跳是两条独立的 HTTP 调用路径, 服务端要维护"最后心跳时间", 需要额外的定时任务扫描过期心跳
- 2.x: gRPC 长连接本身就是一个"隐式心跳"。keep-alive 机制自动检测连接是否存活。连接断开 → 服务端立即感知 → 自动清理该连接相关的全部临时实例
- 这个设计将"健康检查"从应用层下沉到传输层, 大幅简化了系统复杂度

### 4.2 三层端口隔离的设计

- 客户端流量(9848)和集群流量(9849)分离 → 各自的限流/熔断策略独立
- JRaft 端口(7848)独立 → Raft 协议对延迟敏感, 不应受客户端流量影响
- 主端口(8848)保留 HTTP → 向后兼容 1.x 客户端 + Dashboard/API
- 设计原则: 不同的流量类型走不同的端口 → 运维灵活度最大化

### 4.3 filter chain 的"责任链"模式

请求处理 = filter1 → filter2 → ... → handler：
- filter 可以:
  - 通过: 返回 null 或 success=true → 下一个 filter
  - 拒绝: 返回 fail response → 直接返回客户端
- 每个 filter 独立、可插拔、可排序
- 与 Spring MVC Interceptor 对比: filter 链在 gRPC 层执行, 更接近传输层

---

## 五、反面假设分析 (7 组)

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|---|---|---|
| 1 | 如果 Nacos 2.x 继续用 HTTP 而不是 gRPC？ | 依然需要心跳维持健康检查,1.x 的复杂性和性能问题延续。HTTP 没有双向流,服务端无法主动推送。 | gRPC 长连接 + bi-di stream |
| 2 | 如果 SDK Server 和 Cluster Server 共用一个端口？ | 客户端流量高峰影响集群间同步(如 Distro verify),集群数据延迟增大。无法独立限流。 | 三层端口隔离(9848/9849/7848) |
| 3 | 如果 ConnectionManager 不做 maxConnection 限制？ | 恶意或 buggy 客户端可以耗尽服务端连接资源。GrpcConnection 占用内存 + Netty channel + 线程。 | 通过 ControlManagerCenter 做连接数限制 |
| 4 | 如果 RequestHandler 不经过 filter chain,直接 dispatch？ | 无鉴权、无限流、无参数校验。任何人都可以随意注册/注销实例。 | handleRequest → filter chain → handle |
| 5 | 如果连接断开后不立即 unregister, 等待心跳超时？ | 可能长达 30s(1.x 的 IP_DELETE_TIMEOUT) 的路由黑洞期。故障实例可能被调用方选中。 | 连接断开即刻 unregister, 触发 ClientReleaseEvent |
| 6 | 如果 GrpcConnection.request() 不设超时？ | 服务端处理慢或死锁 → 客户端线程永久阻塞 → 线程池耗尽 → 服务整体不可用。 | requestTimeout 参数(客户端传入),默认由 GrpcChannel 配置 |
| 7 | 如果每个请求都建立一个 gRPC channel 而不是复用？ | gRPC 连接建立的开销(SSL 握手 + 协议协商)远大于 HTTP。每个连接需要额外的 Netty handler 内存。 | 客户端维护长连接,服务端通过 ConnectionManager 管理 |

---

## 六、新手最可能犯的 5 个错误

| # | 错误 | 后果 | 正确做法 | Nacos 内置的对策 |
|---|---|---|---|---|
| 1 | 在 Nacos 2.x 集群中混合部署 1.x/2.x 服务端 | 1.x 节点无法处理 gRPC 客户端请求,2.x 的客户端连接到 1.x 节点后注册/发现全部失败 | 全集群统一升级到 2.x | gRPC 客户端会尝试切换节点 |
| 2 | gRPC keep-alive 超时配置不当(设太大或太小) | 设太大 → 连接断开后服务端长时间不知道。设太小 → 网络抖动频繁触发断开重连。 | keepAliveTime=60s, keepAliveTimeout=20s(生产推荐) | 默认值较保守,生产需调整 |
| 3 | 不区分 SDK Server/Cluster Server 的端口监控 | 客户端连接问题(端口 9848)和集群同步问题(端口 9849)混在一起,故障定位困难 | 按端口独立监控 QPS/连接数/延迟 | 两个端口独立,天然分离 |
| 4 | 忽视 ConnectionManager 的限流规则 | 默认每 IP 无连接数限制 → 单个客户端可能创建大量连接(多 NacosNamingService 实例) | 在 Nacos 控制台设置 connectionControlRule | 支持通过 Dashboard 动态调整限流规则 |
| 5 | 在 filter 中做耗时操作 | filter 链在 Netty EventLoop 线程执行 → 阻塞 → 所有请求延迟 | filter 中只做轻量校验,耗时操作异步化 | 当前 filter 无异步化保护 |

### 6.1 可运行的错误实验

**实验 1: keep-alive 配置不当导致连接泄漏**

```bash
# 客户端配置 keep-alive 超时过长
nacos.remote.client.grpc.keepAliveTime=300000  # 5分钟才发一次 keep-alive
nacos.remote.client.grpc.keepAliveTimeout=60000 # 超时 1分钟
# 后果: 客户端 crash 后,服务端最慢 6 分钟才感知到连接断开
# 这 6 分钟内: 已注册的实例标记为 healthy, 其他客户端仍然路由到已 crash 的实例
```

**实验 2: 多 NacosNamingService 实例绕过 ConnectionManager 限流**

```java
// 如果每个请求都创建新的 NacosNamingService（错误做法）
// 每次 new 都会建立一个新的 gRPC connection
for (int i = 0; i < 100; i++) {
    Properties props = new Properties();
    props.setProperty("serverAddr", "127.0.0.1:8848");
    NamingService ns = new NacosNamingService(props);
    ns.registerInstance("test-service", "10.0.0." + i, 8080);
    // 忘记 shutdown() → 100 个 gRPC 连接泄漏
}

// 查看服务端连接数:
// curl http://localhost:8848/nacos/v2/core/cluster/metrics
// → connectionCount: 120 (远超正常值)
// 此时 ConnectionManager 的 connectionForClientIp 计数异常

// 正确做法: 全局单例 + shutdown
@Bean(destroyMethod = "shutDown")
public NamingService namingService() throws NacosException {
    return new NacosNamingService("127.0.0.1:8848");
}
```

---

## 七、面试 Q&A (5 题)

### Q1: Nacos 2.x 为什么用 gRPC？和 HTTP/2 对比有什么优势？

**面试官意图**: 考察对 RPC 框架选型的理解

**参考答案要点**:
- gRPC 原生支持双向流(bi-di stream) → 服务端可以主动 push(HTTP/2 也可以,但 gRPC 封装更好)
- Protobuf 序列化 → 比 HTTP/JSON 小 3-10 倍, 解析更快
- 连接即生命周期 → HTTP 需要额外的 keep-alive 机制, gRPC 内置
- 长连接复用 → HTTP/1.1 也可以 keep-alive, 但 gRPC 的 multiplexing 更高效

**追问**: "那为什么配置中心还保留 HTTP 长轮询,不全部改成 gRPC？"
- HTTP 长轮询对防火墙/代理友好, HTTP 兼容性好
- 配置变更高频低, HTTP 够用, 不需要 gRPC 的双向流复杂性

### Q2: 连接断开后,已经注册的临时实例、持久实例分别会怎样？

**面试官意图**: 考察对连接模型和实例生命周期的关联理解

**参考答案要点**:
- 临时实例: 连接断开 → ConnectionManager.unregister → ClientReleaseEvent → ClientServiceIndexesManager 清理索引 → Distro 同步到其他节点 → 实例从注册中心删除
- 持久实例: 连接断开 → unregister → 但持久实例不自动删除, 只标记不健康 → 数据保留在 Raft 状态机 + 持久化存储中
- Nacos 1.x vs 2.x: 1.x 需要等 IP_DELETE_TIMEOUT(30s) 才删除, 2.x 断开即删

### Q3: gRPC 的 bi-directional stream 在 Nacos 中具体怎么用的？

**面试官意图**: 考察对双向流在实际系统中的应用的深度理解

**参考答案要点**:
- 客户端 subscribe 一个服务 → 建立 bi-di stream
- 客户端通过 stream 发送 SubscribeServiceRequest
- 服务端通过同一个 stream 持续推送 ServiceInfo(实例变更时)
- 客户端也可以通过 stream 发送 unsubscribe
- stream 复用: 一个 stream 承载多个服务的 subscribe/unsubscribe

### Q4: ConnectionManager 的注册过程为什么是 synchronized？有什么并发风险？

**面试官意图**: 考察对并发安全的理解

**参考答案要点**:
- connections 和 connectionForClientIp 是两个需要原子性更新的数据结构
- 如果不加锁: 两个线程同时 register 不同的 connectionId 但同 IP → connectionForClientIp 计数可能错误
- synchronized 在 register/unregister 上 → 保证连接注册/注销的原子性
- 代价: 连接注册成为全 global lock → 极端高并发下可能成为瓶颈
- 权衡: 连接注册频率不高(每秒数百), synchronized 足够

### Q5: RequestHandler 的 filter chain 是如何实现鉴权和限流的？（联动 D-01 Q5）

**面试官意图**: 考察对服务端安全架构的理解

**参考答案要点**:
- AuthFilter: 检查 `@Secured` 注解 → 从 RequestMeta 获取 token → 校验 → 通过或拒绝
- TpsControlFilter: 检查 `@TpsControl` 注解 → 按 pointName 查限流规则 → 当前 QPS 超限 → 拒绝
- ParamCheckFilter: 检查 `@ExtractorManager.Extractor` → 调用对应的 ParamExtractor 校验参数合法性
- 每个 filter 独立 → 可以添加/移除/排序 → 可插拔的安全体系

---

## 八、产出文档结构

```
D-05-GrpcConnectionModel.md

1. 概述: Nacos 2.x 从 HTTP 到 gRPC 的架构变革
2. 三层 gRPC Server 架构
   2.1 GrpcSdkServer(9848) vs GrpcClusterServer(9849) vs RaftServer(7848)
   2.2 端口偏移设计(主端口 + 1000/1001)
3. GrpcServer 启动流程
   3.1 startServer() 的完整步骤
   3.2 gRPC Service 注册(GrpcRequestAcceptor + GrpcBiStreamRequestAcceptor)
   3.3 Spring Boot 生命周期集成
4. ConnectionManager — 连接生命周期管理
   4.1 register: 去重→限流→注册→通知
   4.2 unregister: 移除→清理计数→通知
   4.3 checkLimit: ControlManagerCenter 的限流机制
5. GrpcConnection — gRPC 连接实现
   5.1 单向请求/响应(request)
   5.2 双向流(requestBiStream)
   5.3 keep-alive 配置
6. RequestHandler 体系
   6.1 filter chain(handleRequest → filters → handle)
   6.2 Handler 注册与类型匹配
   6.3 典型 Handler: InstanceRequestHandler
7. RpcPushService — 服务端主动推送
8. 设计哲学分析
   8.1 连接即生命周期
   8.2 三层端口隔离
   8.3 filter chain 责任链模式
9. 反面假设分析(7 组)
10. 新手最可能犯的 5 个错误 + 可运行错误实验
11. 面试 Q&A(5 题,含面试官意图 + 追问)
```

---

## 九、与后续文档的依赖关系

```
D-05 (gRPC 连接模型)  ← 当前
  |--- ConnectionManager 的 register/unregister → D-06 Client 模型用到
  |--- RequestHandler 体系 → D-07 注册处理链用到
  |--- GrpcConnection/双向流 → D-08 Push 推送体系用到
  ↓
D-06 (Client 模型 + 数据存储设计)
D-07 (注册处理链 + 事件驱动索引系统)
```
