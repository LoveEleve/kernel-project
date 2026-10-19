# Tomcat 架构全景（architecture-map）· Stage 5 产出物

> 位置：`source-code/xhs-demo/docs/tomcat/architecture-map.md`
> 用途：学完 L01~L16 后的"一页纸"总览 + 面试速记；与 14 篇面试话术双向校验（§5）。
> 版本口径：Tomcat 10.1.34；内嵌运行 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）。
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「map-2 没懂」。

---

## 1. 组件全景（四组）

```
Server（整个实例）
└─ Service（绑定 Connector + Engine）
   ├─ [A 连接器组] Connector（端口/协议）
   │    └─ ProtocolHandler → NioEndpoint（Acceptor / Poller / Executor）
   └─ [B 容器组] Engine（管道入口）
      └─ Host（localhost）
         └─ Context（Spring 应用）
            └─ Wrapper（DispatcherServlet）
```

| 组 | 成员 | 一句话 | 详见 |
|---|---|---|---|
| A 连接器 | Connector / ProtocolHandler / NioEndpoint / Acceptor / Poller / Executor | 端口监听、协议解析、IO 线程 | L02 / L03 / L10 |
| B 容器 | Server / Service / Engine / Host / Context / Wrapper | 请求处理的层级载体 | L01 / L04 |
| C 链与扩展 | Pipeline+Valve / FilterChain / Servlet / Mapper(+MapperListener) | 容器级链 + 应用级链 + 路由表 | L04 / L05 / L11 |
| D 横切机制 | Lifecycle（状态/事件）/ 指标（JMX/actuator） | 启停、观测的统一机制 | L06 / L12 / L09 |

---

## 2. 请求流转（一屏完整链）

```
客户端 TCP
  → Connector.bind 监听 / Acceptor.accept              [A]
  → Poller.select 就绪 → processSocket 派发             [A]
  → Worker(SocketProcessor)                            [A]
  → Http11Processor.service（解析）                    [A]
  → CoyoteAdapter.service（跨层：Coyote → Catalina）
  → Mapper.map → MappingData                           [C]
  → EngineValve → HostValve → ContextValve → WrapperValve   [B/C]
  → ApplicationFilterChain（Filter 前段/后段）          [C]
  → servlet.service()（DispatcherServlet）             [C]
     → Spring MVC（HandlerMapping → Controller）→ 响应
  → 原路返回 → socket 写回
```

每跳的"谁负责"见 §1 的组；线程归属见 L03（整条链同一 Worker，异步除外）。

---

## 3. 三张面试图（手绘复述版）

### 图 1：组件树

```
Server → Service → { Connector , Engine → Host → Context → Wrapper }
```

### 图 2：线程三段 + 同 Worker 执行

```
Acceptor（收） → Poller（等/派） → Worker（干：解析→路由→Filter→Servlet）
```

### 图 3：停机三步 + K8s 对齐

```
停流量（关监听/pause） → 等在途（计数归零/超时） → 拆内部
terminationGracePeriodSeconds ≥ preStop + shutdown-phase 超时 + 缓冲
```

---

## 4. 速记卡（按场景，面试直接用）

**启动装配**：代码建树（无 server.xml）→ 容器先启 → 路由表建好 → Connector 最后启动（端口监听）→ Servlet/Filter 在 Context 启动期注册。

**线程模型**：1 Acceptor + 1 Poller + N Worker（maxThreads=200）；`http-nio-8080-exec-*`；整条链同一 Worker。

**请求链**：Connector → Engine → Host → Context → Wrapper → Filter → Servlet（DispatcherServlet）；Valve 在 Filter 之前。

**路由**：Host（域名）→ Context（前缀最长，`""` 兜底）→ Wrapper（EXACT > 最长 PATH > EXTENSION > DEFAULT）；DispatcherServlet 在 `/`（DEFAULT）。

**Lifecycle**：12 态 + 事件；模板方法（骨架统一、子类填 `xxxInternal`）；MapperListener/HostConfig 是事件消费者。

**优雅停机**：停流量 → 等在途（countAllocated + inProgressAsyncCount）→ 拆内部；超时 30s 强停；`kill -9` 无效。

**异步**：`startAsync()` 归还 Worker；T2 执行业务；`complete()/dispatch()` 收尾；默认 30s 超时；无人处理 → 500。

**调参**：线程 200/10/无界；连接 8192/100；超时 60s（发行版 20s）/跟随/30s/100 次；满了→慢/卡/拒；CLOSE_WAIT 占名额。

**虚拟线程**：`spring.threads.virtual.enabled`（JDK21+）→ `tomcat-handler-*`；maxThreads 失效；不池化；JDK21~23 synchronized pin（JDK24 修复）。

**排查**：CPU 先判型（GC/业务）→ 内存两步走（三常客）→ hang 四线；线程名即地图；先止损后根因。

**K8s**：MaxRAMPercentage 而非 Xmx；liveness 不放依赖；grace 公式 5+30+5=40s；`/tmp` 要可写。

**迁移**：Boot 2.7→3.x（JDK17/Spring 6/Tomcat 10.1/jakarta）；EE 包全换、JDK 包不动；属性改名静默失效；三件工具 + 回归灰度。

---

## 5. 与 14 篇面试话术的双向校验

| map 位置 | 覆盖的面试篇 |
|---|---|
| §1 组件全景 | 01（启动装配）、02（线程模型）、06（内嵌 vs 独立） |
| §2 请求流转 | 03（完整旅程）、04（Mapper）、05（Lifecycle 链路） |
| 图 1 组件树 | 01、06 |
| 图 2 线程三段 | 02、11（虚拟线程对照） |
| 图 3 停机 | 07（优雅停机）、13（K8s 对齐） |
| 速记：路由 | 04 |
| 速记：Lifecycle | 05 |
| 速记：异步 | 08 |
| 速记：调参 | 09 |
| 速记：排查 | 12 |
| 速记：K8s | 13 |
| 速记：迁移 | 14 |
| 速记：定制/Valve（10 篇） | §1 C 组 Valve 行（定制点见 10 篇） |

| 面试篇 | 在 map 中的落点 | 状态 |
|---|---|---|
| 01 启动装配 | §1 A/B 组 + §2 启动链 | ✅ |
| 02 线程模型 | §1 A 组 + 图 2 + 速记 | ✅ |
| 03 完整旅程 | §2 全链 | ✅ |
| 04 Mapper | §1 C 组 + 速记路由 | ✅ |
| 05 Lifecycle | §1 D 组 + 速记 | ✅ |
| 06 内嵌 vs 独立 | §1（Boot 代码装配行）+ 图 1 | ✅ |
| 07 优雅停机 | 图 3 + 速记 | ✅ |
| 08 异步 | 速记（T1/T2/T3、计数、30s） | ✅ |
| 09 调参 | 速记（三类参数 + 满了现象） | ✅ |
| 10 定制/换容器 | §1 C 组 Valve 行（定制点见 10 篇） | ✅ |
| 11 虚拟线程 | 图 2 对照 + 速记 | ✅ |
| 12 排查 | 速记（判型/定位/先止损） | ✅ |
| 13 K8s | 图 3 公式 + 速记 | ✅ |
| 14 迁移 | 速记（矩阵/四类/工具） | ✅ |

> 校验结论：14 篇的每个核心主张在 map 中都有落点；map 的每条速记都可在对应篇找到展开。**双向闭合**。
