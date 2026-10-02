# Lesson 01: Tomcat 是什么 — 组件模型与请求路径

> 课程索引：`../学习清单.md` → Stage 1 · Lesson 01
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`，本课引用均出自该文档树）
> 来源：`webapps/docs/index.xml`、`webapps/docs/architecture/overview.xml`、`webapps/docs/config/valve.xml`；API 文档 `org.apache.catalina.Wrapper`
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L01-1.4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 建立"有哪些组件、各自职责、一个请求经过谁"的心智模型 |
| 前置 | 无 |
| 不涉及 | 源码与行号（概念课硬规矩）；Spring MVC 内部（属 Spring 课程） |
| 下一课 | L02 启动与装配（内嵌视角） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **Tomcat 有两个身份**：**Servlet 容器**（管理 Servlet 生命周期、派发请求）与 **HTTP 服务器**（连接器负责网络与协议）。内嵌场景下两者都由 Spring Boot 以代码方式装配。
2. **组件树（必记 6 个）**：
   `Server → Service → { Connector , Engine → Host → Context → Wrapper }`
   每层职责见 1.3（各一句）。
3. **一个请求的路径**（内嵌）：
   `Connector → Engine → Host → Context → Wrapper → Filter 链 → Servlet（DispatcherServlet）`
   "具体交给哪个 Controller"发生在 Servlet 之后，属 Spring MVC。

---

## 2. 官方定位（原文对照）

**英文原文**（`webapps/docs/index.xml`）

> This is the top-level entry point of the documentation bundle for the **Apache Tomcat** Servlet/JSP container. Apache Tomcat version 10.1 implements the Servlet 6.0 and Pages 3.1 specifications from Jakarta EE, and includes many additional features that make it a useful platform for developing and deploying web applications and web services.

> 引用说明：原文版本号处为占位符 `<version-major-minor/>`（构建文档时代入），此处直接写 10.1；其余逐字未改。

**中文翻译**

> 这里是 **Apache Tomcat** Servlet/JSP 容器文档包的顶层入口。Tomcat 10.1 实现了 Jakarta EE 的 Servlet 6.0 与 Pages 3.1 规范，并包含许多附加能力，使其成为开发与部署 Web 应用/Web 服务的实用平台。

### ① 核心结论

Tomcat 的第一身份是 **Servlet/JSP 容器**——"容器"意味着它管理组件的生命周期（Servlet 的创建、init、destroy）并负责把请求分派给正确的 Servlet。

### ② 机制说明

| 身份 | 负责什么 | 对应内部名称 |
|---|---|---|
| Servlet 容器 | 组件生命周期、请求派发、会话、安全等规范语义 | **Catalina**（容器世界） |
| HTTP 服务器 | 端口监听、HTTP 解析、IO 线程、连接管理 | **Coyote**（连接器世界） |

两个身份是**连接器（Coyote）向容器（Catalina）交接请求**的关系（详见 L04）。

### ③ 官方要点（2 条）

1. 定位用词是 **Servlet/JSP container**（容器），不是泛指"服务器"；
2. 规范版本对应关系：Tomcat 10.1 = **Servlet 6.0**（本地文档树可证）；10.0 = Servlet 5.0、9.0 = Servlet 4.0 为公开版本对应（本地 10.1 文档树未覆盖，仅作参照）。

### ④ 术语对照

| 官方英文 | 中文 |
|---|---|
| Servlet/JSP container | Servlet/JSP 容器 |
| implements the ... specifications | 实现（某版本的）规范 |
| Jakarta EE | 雅加达 EE（原 Java EE） |
| additional features | 附加能力（规范之外的实现能力） |

### ⑤ 结论

> **Tomcat = 规范实现（容器）+ 附加能力（连接器、管理等）。面试里说"Tomcat 是 Servlet 容器"比说"Tomcat 是服务器"准确。**

---

## 3. 组件模型（官方原文 + 必记 6 个）

> 以下引用均为官方原文**节选**（逐字摘录，省略处已标注）。

### 3.0 组件树

```
Server（整个容器实例）
└─ Service（把连接器与引擎绑定）
   ├─ Connector（对外通信：端口 + 协议 + IO）
   └─ Engine（请求处理管道的载体）
      └─ Host（虚拟主机，按域名）
         └─ Context（一个 Web 应用）
            └─ Wrapper（一个 Servlet 定义）
```

### 3.1 Server

> In the Tomcat world, a **Server** represents the whole container.

- 职责：代表整个容器实例；持有 Service。
- 内嵌实况：Boot 装配出的唯一实例；`await()` 用于阻塞驻留（L02 讲）。

### 3.2 Service

> A **Service** is an intermediate component which lives inside a Server and ties one or more Connectors to exactly one Engine.

- 职责：中间层，把**若干 Connector** 绑到**恰好一个 Engine**。
- 内嵌实况：恒为 1 个。

### 3.3 Connector

> A **Connector** handles communications with the client. There are multiple connectors available with Tomcat. These include the **HTTP connector** ... and the **AJP connector** ...

- 职责：与客户端通信——端口监听、协议解析、IO。
- 内嵌实况：HTTP connector（NIO 实现）；AJP 基本不用。

### 3.4 Engine

> An **Engine** represents request processing pipeline for a specific Service. ... the Engine receives and processes all requests from these connectors, handing the response back to the appropriate connector ...

- 职责：该 Service 的请求处理管道入口；接收所有 Connector 的请求并把响应交回对应 Connector。
- 内嵌实况：`StandardEngine`；容器后台线程挂在它下面。

### 3.5 Host

> A **Host** is an association of a network name, e.g. www.yourcompany.com, to the Tomcat server.

- 职责：虚拟主机——域名到应用的关联（含别名）。
- 内嵌实况：只有 `localhost`。

### 3.6 Context

> A **Context** represents a web application. A Host may contain multiple contexts, each with a unique path.

- 职责：一个 Web 应用（独立类加载器、会话、路由子树、资源根）。
- 内嵌实况：你的 Spring 应用，路径通常为空（根应用）。

### 3.7 Wrapper

> A **Wrapper** is a Container that represents an individual servlet definition ... Implementations of Wrapper are responsible for managing the servlet life cycle ... including calling init() and destroy() at appropriate times.

- 职责：单个 Servlet 的定义与生命周期管理。
- 内嵌实况：**默认只有一个**——`DispatcherServlet`（另行注册 Servlet 会新增 Wrapper）。

### 3.8（补充）Valve

> A **Valve** element represents a component that will be inserted into the request processing pipeline for the associated Catalina container (Engine, Host, or Context).

- 职责：插入到容器的请求处理管道中（容器级扩展）。
- 与 Filter 的区别见 L04（一句话：Valve 在容器层、应用无法绕过；Filter 在应用层）。

---

## 4. 一个请求的路径（概念级）

```
客户端
  → Connector        把网络字节解析为请求对象（协议世界）
  → Engine           请求处理管道入口
  → Host             选择虚拟主机
  → Context          选择 Web 应用
  → Wrapper          定位具体 Servlet
  → Filter 链        应用级加工（编码/鉴权/日志）
  → Servlet          DispatcherServlet
  → （Spring MVC 内部：HandlerMapping → Controller）
```

| 站 | 它做的判断/动作 |
|---|---|
| Connector | 协议解析、组装请求对象 |
| Engine / Host / Context | 逐层选择（依据 = 路由表，L05 讲） |
| Wrapper | 定位 Servlet、构建 Filter 链 |
| Filter 链 | 按注册规则执行应用级加工 |
| Servlet | 业务入口（内嵌 = DispatcherServlet） |

**内嵌场景的两个默认事实**（面试常问）：

1. `DispatcherServlet` 注册在 **`/`**（默认 Servlet 槽位）——没有其它更具体的映射（精确/前缀/扩展名）命中时，请求落到它；
2. Tomcat 自带的 `DefaultServlet` 在内嵌下**默认不注册**——静态资源默认由 Spring MVC 的静态资源处理（`ResourceHttpRequestHandler`）承担。

---

## 5. 内嵌形态与独立形态（只记 2 条差异）

| 维度 | 独立 Tomcat | 内嵌（Spring Boot） |
|---|---|---|
| 组件树来源 | 解析 `conf/server.xml` | **代码装配**（启动时创建） |
| 应用部署 | 把 WAR/目录放进 `webapps/` | 应用本身就是进程，DispatcherServlet 在代码里注册 |

相同点：组件模型、生命周期、请求路径**完全一致**——差异只在"谁把树建起来"。

---

## 6. 术语对照（本课汇总）

| 官方英文 | 中文 | 一句话 |
|---|---|---|
| Container | 容器 | 管理组件生命周期与请求派发的组件 |
| Connector | 连接器 | 端口/协议/IO 的对外入口 |
| Engine | 引擎 | 请求处理管道的入口 |
| Host | 虚拟主机 | 域名 → 应用 |
| Context | 上下文（Web 应用） | 一个应用 |
| Wrapper | 包装器 | 一个 Servlet 定义 |
| Valve | 阀门 | 容器管道的处理节点 |
| Pipeline | 管道 | Valve 的有序集合 |
| Catalina / Coyote | — | 容器世界 / 连接器世界 |
| Servlet container | Servlet 容器 | Tomcat 的第一身份 |

---

## 7. Self-Test（参考答案直接给出，自测用）

**Q1. 用一句话说明 Tomcat 的两个身份及对应关系。**
> 参考答案：Servlet 容器（Catalina，管生命周期与派发）+ HTTP 服务器（Coyote，管网络与协议）；连接器把请求交给容器处理（"Coyote → Catalina"交接）。

**Q2. 画出组件树并说出每层职责。**
> 参考答案：`Server → Service → {Connector, Engine → Host → Context → Wrapper}`；Server=整个实例；Service=绑定 Connector 与 Engine；Connector=对外通信；Engine=请求管道入口；Host=虚拟主机；Context=应用；Wrapper=Servlet。

**Q3. 一个请求从客户端到 DispatcherServlet 经过哪些组件？**
> 参考答案：Connector → Engine → Host → Context → Wrapper → Filter 链 → Servlet（DispatcherServlet）；之后是 Spring MVC 内部处理。

**Q4. "为什么内嵌下几乎所有请求都会进 DispatcherServlet"？**
> 参考答案：DispatcherServlet 注册在 `/`（默认 Servlet 槽位，兜底）；内嵌默认不注册 Tomcat 的 DefaultServlet；没有更具体的映射（精确/前缀/扩展名）时会命中它。

**Q5. 内嵌与独立 Tomcat 在"组件模型和请求路径"上是否不同？**
> 参考答案：不同点只有组件树来源（代码装配 vs server.xml）与应用部署方式（进程 vs webapps）；组件模型、生命周期、请求路径一致。

---

## 8. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 下一步：**L02 启动与装配**——回答"这棵树什么时候、按什么顺序被建起来，端口何时监听，DispatcherServlet 何时注册"（仍为概念级，不掺源码）。
- 校验方式：学完 L04 后回来做 Q3 的加长版（加入 Filter/Valve 的层次差异）。
