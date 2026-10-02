# 面试题详解 03 | 一个 HTTP 请求的完整旅程（Connector → Adapter → Mapper → Valve → Filter → Servlet）

> 难度：★★★★★ ｜ 频率：★★★★★ ｜ 区分度：高（跨 Coyote/Catalina 两层的完整链路）
> 关键词：Http11Processor、CoyoteAdapter、postParseRequest、Mapper、MappingData、Valve 链、StandardWrapperValve、ApplicationFilterChain、DispatcherServlet、404 的产生位置

---

## 问题

**一个 HTTP 请求从进入内嵌 Tomcat 到 Servlet 被调用，完整链路是什么？Coyote 与 Catalina 两层在哪里交接？Mapper 怎么找到目标 Context/Wrapper？Valve 链与 FilterChain 是什么关系？**

---

## 30 秒简洁回答

> Worker 线程上：`Http11Processor` 解析请求行/头，组装 Coyote 的 Request/Response → 调用 `CoyoteAdapter.service()`（跨入 Catalina 世界）→ `postParseRequest` 里用 **Mapper** 把 URL 映射到 `MappingData`（host/context/wrapper）→ `container.getPipeline().getFirst().invoke(...)` 进入 **Engine→Host→Context→Wrapper 四层 Valve 链** → `StandardWrapperValve` 里 `wrapper.allocate()` 拿到 Servlet、`createFilterChain()` 建 **ApplicationFilterChain** → `filterChain.doFilter(...)` → 最终 `servlet.service()`（内嵌场景就是 Spring MVC 的 `DispatcherServlet`）→ 响应沿原路返回，字节经输出缓冲写出、由 Poller 管可写事件。**一句话：Connector 管字节，Adapter 管跨界，Mapper 管路由，Valve 管容器链，FilterChain 管应用链，Servlet 管业务。**

---

## 展开回答（高级开发级）

### 1）从字节到 Coyote Request（协议层）

```
Worker 线程（见 02 篇）：SocketProcessor → Http11Processor.service
  · 解析请求行/头：inputBuffer.parseRequestLine(...)（Http11Processor:270）、parseHeaders()
  · 组装 coyote.Request / coyote.Response
  · :397  getAdapter().service(request, response)      ← 跨入 Catalina 的分界线
```

### 2）CoyoteAdapter：适配 + 路由 + 进入容器

```java
postParseSuccess = postParseRequest(req, request, res, response);
...
    connector.getService().getMapper().map(serverName, decodedURI, version, request.getMappingData());
...
connector.getService().getContainer().getPipeline().getFirst().invoke(request, response);
```

源码行：`:339`（调用 `postParseRequest`，实现 :560）、`:697`（其内部的路由调用）、`:344`（进入容器 pipeline）；`...` 表示省略，缩进省略。

`MappingData` 是路由结果（`MappingData.java`）：`host`（:33）、`context`（:34）、`wrapper`（:37）、`requestPath`（:40）、`pathInfo`（:42）、`matchType`（:47）。

### 3）四层 Valve 链（Catalina 容器链）

```
StandardEngineValve.invoke  :56  → :74  host.getPipeline().getFirst().invoke(...)
StandardHostValve.invoke    :80  → :115 context.getPipeline().getFirst().invoke(...)
                                   · 无匹配 Context → :87 response.sendError(404)
StandardContextValve.invoke :60  → :90  wrapper.getPipeline().getFirst().invoke(...)
                                   · :62-65 拒绝 /WEB-INF、/META-INF 的直接访问
StandardWrapperValve.invoke :86
   · :115 servlet = wrapper.allocate()                     ← 取/建 Servlet 实例
   · :141 ApplicationFilterChain filterChain = ApplicationFilterFactory.createFilterChain(...)
   · :155/:167 filterChain.doFilter(request.getRequest(), response.getResponse())
```

### 4）FilterChain → Servlet（应用链）

```
ApplicationFilterFactory.createFilterChain()  :57
ApplicationFilterChain.doFilter()             :117
  → internalDoFilter()                        :144
      → servlet.service(request, response)    :195   ← 链路终点
```

**嵌入式视角**：这里的 `servlet` 就是 Spring MVC 的 `DispatcherServlet`（Boot 注册：`DispatcherServletAutoConfiguration:89` `new DispatcherServlet()`、`:116-118` `DispatcherServletRegistrationBean`）。Tomcat 的链路到此为止——之后的 HandlerMapping/HandlerAdapter 属于 Spring MVC，不在 Tomcat 卷展开。

### 5）响应返回

```
Servlet 写 response → coyote.Response → Http11Processor 组装响应字节
→ NioSocketWrapper.write（写不进就注册 OP_WRITE，由 Poller 管可写，见 02 篇）
```

### 6）链路总图

```
[内核] 连接就绪
  → Acceptor/Poller（02 篇）
  → Worker：SocketProcessor
      → Http11Processor（解析）→ :397 adapter.service
          → CoyoteAdapter（:303）→ postParseRequest（:560）→ Mapper.map（:697）
          → Engine Valve（:56）→ Host Valve（:80）→ Context Valve（:60）→ Wrapper Valve（:86）
              → createFilterChain（:141）→ filterChain.doFilter（:117/:144）
                  → servlet.service（:195）＝ DispatcherServlet
  → 响应原路返回 → 输出缓冲 → Poller 可写
```

---

## 进一步回答（架构师层级）

- **为什么分 Coyote / Catalina 两层**：协议解析（HTTP/AJP/HTTP2）与容器模型（Engine/Host/Context/Wrapper）解耦——换协议不动容器，换容器不动协议；`CoyoteAdapter` 是唯一的"过桥"。
- **Valve vs Filter**：Valve 是**容器级**扩展（挂在 Engine/Host/Context/Wrapper 的 pipeline 上，由容器配置/代码装配，应用无法移除）；Filter 是**应用级**（Servlet 规范，应用注册、随 FilterChain 走）。访问日志用 Valve（`AccessLogValve`），编码/安全用 Filter。
- **404 的两个来源**：没有匹配到 Context → `StandardHostValve:87`；匹配到 Context 但资源不存在 → `DefaultServlet:732` `sendError(SC_NOT_FOUND)`（Spring Boot 下多数路径会命中 DispatcherServlet，404 通常由 Spring MVC 产生）。
- **Mapper 的作用**：启动时把 Host/Context/Wrapper 的映射建成预建结构（`ContextVersion` 的 `exactWrappers/wildcardWrappers/extensionWrappers`，`Mapper.java:1617-1619`），请求时按 `EXACT/PATH/EXTENSION/DEFAULT` 四级匹配（规范名 PATH=前缀/通配，04 篇）——把路由变成"预建结构上的查找"，而不是每次请求扫描容器列表。
- **异步请求**：链路在 `postParseRequest` 后可能分叉（`AsyncContext` 把请求从 Worker 释放），见 08 篇。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：这道题考"一个请求在容器内部被谁依次接手"，以及"协议层与容器层在哪交接"；
- **回答顺序**：Http11Processor（解析）→ CoyoteAdapter（跨界+路由）→ Valve 四层 → FilterChain → Servlet → 响应；
- **一句话收束**："Connector 管字节、Adapter 管跨界、Mapper 管路由、Valve 管容器链、FilterChain 管应用链、Servlet 管业务。"

## 必记关键词

`Http11Processor` ｜ `CoyoteAdapter` ｜ `postParseRequest` ｜ `Mapper.map` ｜ `MappingData` ｜ `StandardEngineValve/HostValve/ContextValve/WrapperValve` ｜ `ApplicationFilterFactory` ｜ `ApplicationFilterChain` ｜ `servlet.service` ｜ `DispatcherServlet`

## 必须明确的边界

- Valve 链是 **Engine→Host→Context→Wrapper** 四层，每层 `pipeline.getFirst().invoke(...)` 往下传；
- `Mapper` 只负责"找到谁"，不负责"调用谁"（调用是 Valve 链）；
- `ApplicationFilterChain` 是**应用级**的（Servlet 规范），与 Valve 不是一回事；
- Tomcat 链路终点是 `servlet.service()`（内嵌 = DispatcherServlet）；之后进入 **Spring MVC 域**：`Filter`（Servlet 规范，本卷 03/10/14 讲）→ DispatcherServlet 内部的 `HandlerInterceptor.preHandle/postHandle/afterCompletion`（`HandlerInterceptor:103/:130/:155`；调用链 `DispatcherServlet:1084/:1089/:1096`；逆序收尾 `HandlerExecutionChain:143/:158/:172`）→ AOP；
- **"Filter vs Interceptor vs AOP" 属 Spring MVC 域**（面试高频），计划归入 Spring MVC 面试系列，不在本卷展开；
- 无 Context 的 404（HostValve）与"资源不存在"的 404（DefaultServlet）不是同一个位置。

## 常见错误

- 把 Valve 和 Filter 混为一谈；
- 说请求直接由 "Tomcat 调 DispatcherServlet"（漏掉 Adapter/Mapper/Valve/FilterChain 的完整链）；
- 把 Mapper 说成"负责调用 Servlet"；
- 说不清 Coyote 与 Catalina 的交接点（`CoyoteAdapter.service`）；
- 以为所有 404 都是同一个地方产生的。

## 自测要求

- **30 秒**：能背出"六段式"链路（解析→跨界→路由→Valve→FilterChain→Servlet）；
- **3 分钟**：能画出带行号的链路图，并说清 404 的两个来源；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：Valve 和 Filter 有什么区别？**
> Valve 是容器级扩展（挂在 Engine/Host/Context/Wrapper 的 pipeline 上，由容器代码/配置装配，Servlet API 无法操作它；`Pipeline.addValve/removeValve` 是容器侧 API，如 AccessLogValve/ErrorReportValve）；Filter 是应用级（Servlet 规范，应用注册，随 ApplicationFilterChain 执行）。前者管"容器行为"，后者管"请求处理"。

**追问 2：一个请求在 Tomcat 里经历了哪些线程？**
> 连接建立：Acceptor；就绪检测：Poller；**整条业务链（解析→Adapter→Valve→Filter→Servlet）都在同一个 Worker 线程上**（这正是 02 篇说的同步语义）。异步/升级请求才会脱离 Worker（08 篇）。

**追问 3：404 和 500 分别在哪产生？**
> 404：没有匹配到 Context → `StandardHostValve:87`；Context 内资源不存在 → `DefaultServlet:732`（或 Spring MVC 自己返回）。500：Servlet/Filter 抛异常 → `StandardWrapperValve`/`StandardHostValve` 的错误处理 → `ErrorReportValve` 输出错误页（默认挂在 Host pipeline：`StandardHost:149` 默认类名、`:750-772` 装配；`ErrorReportValve.invoke:90` / `report:186`；本卷正文 07/19 章展开）。

---

## 示例与使用说明

```text
断点/日志建议（按顺序打，能一眼看到链路）：
  CoyoteAdapter.service            （:303 跨界点）
  Mapper.map                       （:697 路由结果：host/context/wrapper）
  StandardEngineValve.invoke       （:56  进入容器链）
  StandardWrapperValve.invoke      （:115 allocate / :141 filterChain）
  ApplicationFilterChain.internalDoFilter （:144 逐个 Filter）
  DispatcherServlet.doDispatch     （Spring 侧起点）
```

## 面试官评分点

**通过标准**
- 能讲六段式链路与 Coyote/Catalina 交接点；
- 能区分 Valve 与 FilterChain；
- 能指出 404 的两个来源。

**加分项**
- 能带行号讲关键节点（`CoyoteAdapter:303/:697`、`StandardWrapperValve:141/:155`、`ApplicationFilterChain:195`）；
- 能说清"整条业务链在一个 Worker 线程上"与异步的差异。

**危险信号**
- 只会说"Tomcat 调用 Servlet"，中间组件全缺；
- Valve/Filter 混淆；
- 不知道 Mapper 的产物是 `MappingData`。

## 实战练习

1. 在内嵌应用打断点/加日志，按"示例"清单走一遍，记录每步的线程名（应全程 `http-nio-8080-exec-*`）；
2. 请求一个不存在的路径，观察 404 由哪一层产生（对比 `server.servlet.context-path` 配错时 HostValve 的 404）；
3. 自定义一个 Valve 加进 Host pipeline，观察它在 Filter 之前执行。

## 版本与来源

- Tomcat **10.1.34**：`java/org/apache/coyote/http11/Http11Processor.java`、`java/org/apache/catalina/connector/CoyoteAdapter.java`、`java/org/apache/catalina/mapper/MappingData.java`、`java/org/apache/catalina/core/{StandardEngineValve,StandardHostValve,StandardContextValve,StandardWrapperValve,ApplicationFilterFactory,ApplicationFilterChain}.java`、`java/org/apache/catalina/servlets/DefaultServlet.java`；
- Spring Boot **3.5.16**：`DispatcherServletAutoConfiguration.java`；
- 本卷章节：01（请求旅程）、05（CoyoteAdapter）、06（Mapper 路由）、07（Valve/FilterChain）；
- 规划：`Tomcat源码学习范围规划.md` T-3（容器执行闭环）。

## 个人经历填充区（留空，按真实项目补）

- 有没有顺着这条链排查过问题（404/500、Filter 顺序、Valve 定制）？
- 自定义 Valve/Filter 的真实场景是什么？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Spring Boot 3.5.16）；断点为建议路径（未在本文附真实运行输出）；项目经历请用真实案例填充。
