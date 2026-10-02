# 00 前置速览 | 内嵌 Tomcat 组件职责与调用关系

> **定位：前置速览，不是面试题。**
> 目标不是记忆组件名称，而是建立三组对应关系：**类型 → 职责 → 调用关系**。建立之后，01（启动装配）与 02/03（请求处理）中的调用链才可读。
> 建议阅读顺序：本篇 → 01 → 02/03 → 04/05。
> 行号已省略（学习向）；需要源码定位时按类名/方法名检索，或在正文篇的"版本与来源"中查找。

---

## 1. 组件树（包含关系）

```
Server
└─ Service
   ├─ Connector
   │    └─ ProtocolHandler
   │         └─ Endpoint（NIO 实现为 NioEndpoint；包含 Acceptor/Poller/Executor）
   └─ Engine
      └─ Host
         └─ Context
            └─ Wrapper
```

包含关系决定启动顺序与生命周期传播：父组件的 `startInternal()` 负责调用子组件的 `start()`（05 篇）；内嵌场景由 `ServletWebServerApplicationContext` 触发整树启动（01 篇）。

---

## 2. 一个请求的数据形态与组件边界

```
输入：TCP 字节流 "GET /inflight HTTP/1.1\r\nHost: localhost:8080\r\n..."

TCP 字节
 → [Connector / Http11Processor]      协议解析 → Coyote Request/Response
 → [CoyoteAdapter]                    跨层（Coyote → Catalina）+ 触发路由
 → [Mapper.map()]                     查询映射结构 → MappingData{host, context, wrapper}
 → [容器 Pipeline 逐层传递]             EngineValve → HostValve → ContextValve → WrapperValve
 → [StandardWrapperValve]              allocate Servlet + 构建 FilterChain
 → [ApplicationFilterChain]            执行应用 Filter → servlet.service()
 → [DispatcherServlet]                 Spring MVC 处理 → 结果写入 Response
 → [Connector]                         编码 → TCP 字节

输出：HTTP/1.1 200 + "inFlight=0 asyncInFlight=0"
```

每次形态转换对应一个明确的组件边界，职责表由此展开。

---

## 3. 组件职责表（类型 / 职责 / 关系）

| 组件 | 类型与实现 | 职责（输入 → 处理 → 输出） | 与其他组件的关系（展开篇） |
|---|---|---|---|
| **Server** | 接口 `org.apache.catalina.Server`；实现 `StandardServer` | 实例根组件：持有 `Service[]`，负责整实例的启停与 `await()` | 无父组件；子组件为 Service（01） |
| **Service** | `StandardService` | 将一组 Connector 与一个 Engine 绑定为可独立启停的单元；持有 MapperListener 与共享 Executor；启动顺序 engine → executor → mapperListener → connectors | 父 Server；子 Connector、Engine、MapperListener（01/05） |
| **Connector** | `catalina.connector.Connector` | 端口持有者与协议处理入口：`bind` 监听、受理连接、驱动 ProtocolHandler 解析请求、经 Adapter 将请求交给 Engine；提供 `pause()` 供优雅停机 | 属 Service；下游为 ProtocolHandler；跨层入口为 CoyoteAdapter（02/07） |
| **ProtocolHandler** | 接口 `org.apache.coyote.ProtocolHandler`；实现 `AbstractHttp11Protocol` 等 | 协议语义与连接器级参数的持有者（HTTP/1.1、HTTP/2、AJP 的选择与配置；如 `setMaxThreads`）；驱动 Endpoint 启停（`pause`、`closeServerSocketGraceful`） | 被 Connector 持有；其下为 Endpoint（02/10） |
| **NioEndpoint** | `AbstractEndpoint` / `NioEndpoint` | IO 层实现：socket 绑定（`bind`）、连接准入（LimitLatch）、事件就绪检测（Poller）、任务执行（Executor，`createExecutor`，线程名 `-exec-`）；持有 Acceptor/Poller 线程 | 被 ProtocolHandler 驱动；经 ConnectionHandler 进入 Processor（02/11） |
| **Engine** | `StandardEngine` | 容器树顶层：持有 `Host[]`；Pipeline 首个 Valve 为 `StandardEngineValve`；维护容器后台线程（`backgroundProcessorDelay=10`） | 父 Service；子 Host（03/05） |
| **Host** | `StandardHost` | 虚拟主机：按域名组织 Context（内嵌仅 `localhost`）；Pipeline 基准 Valve 为 `StandardHostValve`（错误页处理位于此层）；独立部署时由 HostConfig 扫描部署 | 父 Engine；子 Context（03/06） |
| **Context** | `StandardContext` | Web 应用运行时：持有类加载器（WebappLoader）、会话管理器（Manager）、资源（docBase）、映射数组（ContextVersion 的 exact/wildcard/extension）；`sessionTimeout=30` | 父 Host；子 Wrapper；其映射子树由 Mapper 持有（04/05） |
| **Wrapper** | `StandardWrapper` | 单个 Servlet 的运行时封装：实例化/初始化/销毁、并发分配（`allocate` / `deallocate`，`countAllocated` 统计并发）、load-on-startup | 父 Context；由 `StandardWrapperValve` 调用（03） |
| **Pipeline/Valve** | 接口 `Pipeline`/`Valve`；实现 `StandardPipeline`/`ValveBase` | 容器级处理链：每层 Container 持有一个 Pipeline，Valve 按顺序调用，最后一个（基本 Valve）进入下一层容器或构建 FilterChain 调用 Servlet（`addValve`） | 挂载于每个 Container；与 FilterChain 层级不同（容器级 vs 应用级）（03/10） |
| **FilterChain** | 接口 `FilterChain`；实现 `ApplicationFilterChain` | 按注册顺序执行应用 Filter，最终调用 Servlet（`doFilter` / `internalDoFilter`）；由 `StandardWrapperValve` 构建（`createFilterChain`） | 位于 Wrapper 内、Servlet 前；输入输出为 ServletRequest/Response（03/10/14） |
| **Mapper / MapperListener** | `catalina.mapper.Mapper` / `MapperListener` | 维护 URI→Wrapper 的映射结构（MappedHost → ContextList → ContextVersion，含四种匹配数组），提供 `map()` 查询；MapperListener 监听容器事件更新映射（不可变数组替换） | 被 CoyoteAdapter 调用；数据源为 Host/Context/Wrapper 生命周期事件（04） |
| **Lifecycle** | 接口 `Lifecycle`；基类 `LifecycleBase` | 统一状态机与事件：`init/start/stop/destroy`（均为 `final synchronized`），状态变更触发 `LifecycleEvent` | 所有容器/连接器组件的共同基类（05） |
| **Manager** | 接口 `Manager`；实现 `StandardManager` 等 | Context 级会话管理：创建/查找/过期/持久化/集群复制 | 每个 Context 持有；由 `Request.doGetSession` 调用（本系列未展开） |

---

## 4. 请求处理调用序列（代码级）

```text
AbstractEndpoint.Acceptor.run()                            // 受理 TCP 连接
 → Poller 检测 OP_READ 就绪 → processSocket(dispatch)
 → ConnectionHandler.process → Http11Processor.service()   // 协议解析
 → CoyoteAdapter.service()                                 // 跨层
     → postParseRequest → Mapper.map()
     → container.getPipeline().getFirst().invoke(...)
         → StandardEngineValve → StandardHostValve
         → StandardContextValve → StandardWrapperValve
             → wrapper.allocate()
             → ApplicationFilterFactory.createFilterChain
             → ApplicationFilterChain.doFilter / internalDoFilter
                 → servlet.service()                       // DispatcherServlet
 → 响应写入 → Http11Processor 编码 → socket 写回
```

---

## 5. 启动序列（与 01 篇对应）

1. **创建实例树**：`new Tomcat()` → Server → Service → Engine → Host（代码装配，无 server.xml）；
2. **创建 Context 并挂注册入口**：`prepareContext` 创建 `TomcatEmbeddedContext`，并将 `ServletContextInitializer`（DispatcherServlet/Filter 的注册器）交给 `TomcatStarter`；
3. **容器启动**：`tomcat.start()` 沿 Lifecycle 逐级启动 Server → Service → Engine → Host → Context；Context 启动期间 `TomcatStarter` 回调 `ServletContextInitializer` 完成注册；`MapperListener` 建立映射结构；
4. **Connector 最后启动**：`NioEndpoint.bind` 绑定端口，Acceptor/Poller 线程与 Executor 就绪（Boot 通过 `removeServiceConnectors`/`addPreviouslyRemovedConnectors` 控制此时机）；
5. **进入可受理状态**（此后才进入 02/03 篇的请求处理路径）。

---

## 6. 内嵌场景的三个关键差异

1. **无 server.xml / webapps**：实例树由代码创建，`org.apache.catalina.startup.Tomcat` 是 Tomcat 提供的程序化装配辅助类（01/06 篇）；
2. **Boot 的职责是"装配与托管"**：`TomcatStarter` 为注册桥，`WebServerStartStopLifecycle` 将 WebServer 接入 Spring 生命周期（01/05 篇）；
3. **类归属容易混淆**：`TomcatServletWebServerFactory`/`TomcatWebServer`/`TomcatStarter` 属 **Spring Boot**；`org.apache.catalina.startup.Tomcat` 及容器组件属 **Tomcat**。

---

## 7. 掌握标准（三个判据）

不要求记忆表格，要求能用以下判据自行推导：

1. **包含关系判据**：`Server → Service → {Connector, Engine → Host → Context → Wrapper}`——决定启动顺序与生命周期传播；
2. **数据形态判据**：字节 → Coyote Request → MappingData → ServletRequest → Response → 字节——每段转换对应一个组件边界；
3. **调用关系判据**：谁创建谁（`new Tomcat()`/`prepareContext`）、谁调用谁的 `start`/`invoke`/`map`（01/03 篇链路）。

面试实际考察的 5 个点：Connector 与 Engine 的边界（CoyoteAdapter 跨层）、Acceptor/Poller/Worker 分工、Mapper 的映射结构与查询路径、Wrapper 对 Servlet 的封装、Valve 与 Filter 的层级差异。

## 8. 深挖与略读界线

- **深挖**：Endpoint/Connector（02）、Mapper（04）、Wrapper/FilterChain（03）；
- **略读**：Server/Service（仅"持有与绑定"关系）、Host（内嵌为单值）。

## 9. 自测

1. 依据包含关系写出启动顺序，并说明 Connector 最后启动的原因；
2. 指出"字节 → Request → MappingData → Servlet 调用"各在哪个组件完成；
3. 说明 Valve 与 Filter 的层级差异（谁可绕过、谁维护）；
4. 说明 Mapper 映射结构的来源（谁维护）与查询入口；
5. 指出下列类分别属于哪一侧：`TomcatServletWebServerFactory`、`org.apache.catalina.startup.Tomcat`、`TomcatStarter`、`StandardWrapper`。

> lab 对照：启动日志（"Tomcat initialized with port" → "Tomcat started on port"）、`/slow` 响应中的线程名（`http-nio-8080-exec-*`）、`/inflight` 计数。

---

## 版本与来源

- Tomcat **10.1.34**：`catalina/core/Standard{Server,Service,Engine,Host,Context,Wrapper}.java`、`catalina/connector/Connector.java`、`coyote/AbstractProtocol.java`、`tomcat/util/net/{AbstractEndpoint,NioEndpoint}.java`、`catalina/mapper/Mapper.java`、`catalina/Lifecycle.java`、`catalina/core/StandardPipeline.java`、`catalina/core/ApplicationFilterChain.java`；
- Spring Boot **3.5.16**：`web/embedded/tomcat/{TomcatServletWebServerFactory,TomcatWebServer,TomcatStarter}.java`、`web/servlet/context/ServletWebServerApplicationContext.java`；
- 各组件细节展开：见对应篇的"版本与来源"。
