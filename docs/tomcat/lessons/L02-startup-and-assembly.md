# Lesson 02: 启动与装配 — 这棵树是怎么建起来并开始服务的

> 课程索引：`../学习清单.md` → Stage 1 · Lesson 02
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`）；内嵌运行版本 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）
> 来源：`webapps/docs/architecture/startup.xml`（官方启动文档）；内嵌行为来自 Spring Boot（概念级描述，源码留到 Stage 3）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L02-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 回答三问：① 组件树何时/如何被创建；② 启动顺序是什么；③ 端口何时监听、Servlet 何时注册 |
| 前置 | L01（组件模型：Server → Service → {Connector, Engine → Host → Context → Wrapper}） |
| 不涉及 | 源码与行号；Lifecycle 状态机细节（L06）；优雅停机（L07） |
| 下一课 | L03 线程模型（Acceptor / Poller / Worker） |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **两条装配路径**：
   - 独立 Tomcat：由 **Digester 解析 `server.xml`** 创建并配置对象树，`HostConfig` 负责把 webapps 里的应用部署进去；
   - 内嵌（Spring Boot）：**用代码创建对象树**（没有 server.xml），Servlet/Filter 通过代码注册。
   官方文档明确把 "From a Java program as an embedded server" 列为支持的启动方式之一。
2. **启动顺序**：父组件启动时启动子组件（`Server → Service → Engine → Host → Context`）；**Connector 在容器之后启动**——这就是"容器先就绪、端口后监听"。
3. **三个关键时点**：Servlet/Filter 注册发生在 **Context 启动期间**；**路由表**在 Connector 启动前建立（容器就绪后）；**端口绑定**发生在 Connector 启动时（绑定失败 = 启动失败）。

---

## 2. 官方原文（独立形态的启动流程）

**英文原文**（`webapps/docs/architecture/startup.xml`）

> Once the class loaders have been initialized, Tomcat parses server.xml using the Digester and the Digester creates the objects defined in server.xml, configures them using the property values defined in server.xml and the starts the Server. The main Java thread waits in the await() method for a shutdown signal. Once a shutdown signal is received, the Server object is stopped and then destroyed. The JVM then exits.

**中文翻译**

> 类加载器初始化完成后，Tomcat 用 **Digester 解析 `server.xml`**：Digester 创建 `server.xml` 里定义的对象、用其中的属性值配置它们，然后启动 Server。主 Java 线程在 `await()` 方法中等待停机信号；收到信号后，Server 先 stop 再 destroy，最后 JVM 退出。

### ① 核心结论

独立形态的启动 = **"解析配置 → 创建对象 → 启动"** 三段式；进程的存活靠主线程 `await()` 撑住。

### ② 机制说明

| 阶段 | 做什么 | 依赖谁 |
|---|---|---|
| 解析 | 读 `server.xml` | Digester（XML → 对象） |
| 创建与配置 | 生成 Server/Service/Connector/Engine/Host/Context 等对象并注入属性 | 上一步产出的对象定义 |
| 启动 | 沿组件树逐级 start | Lifecycle（L06） |
| 驻留 | 主线程 `await()` 等停机信号 | 独立形态的 shutdown 机制（`conf/server.xml` 模板默认 8005 端口；可用 `-1` 禁用） |
| 停止 | stop → destroy → JVM 退出 | 停机信号（脚本/端口） |

> 第二条官方原话（同为 startup.xml）：
> *"A Server may have several Services although it typically only has one. Each Service may have multiple Connectors. A Connector instance is associated with a single Protocol instance and a single CoyoteAdapter instance."*
> 翻译：一个 Server 可以有多个 Service（通常只有一个）；每个 Service 可以有多个 Connector；每个 Connector 关联一个 Protocol 实例和一个 CoyoteAdapter 实例。

### ③ 官方要点（3 条）

1. 启动入口不止一种：命令行、**嵌入式（embedded server）**、Windows 服务；
2. 独立形态是"配置驱动"：`server.xml` 决定对象树；
3. 独立进程活着的依据是 `await()`；内嵌形态没有这一步（见 §3）。

### ④ 术语对照

| 官方英文 | 中文 | 说明 |
|---|---|---|
| Digester | 摘要器（XML→对象工具） | 独立形态创建对象树的方式 |
| server.xml | 服务器配置 | 独立形态的对象树定义 |
| embedded server | 嵌入式服务器 | 内嵌形态（由应用代码创建） |
| await() | 等待（停机信号） | 独立形态主线程驻留方式 |
| JVM then exits | JVM 随后退出 | 停机终点 |

### ⑤ 结论

> **独立：配置驱动（Digester + server.xml）+ 主线程 await 驻留；内嵌：代码驱动（对象树由代码创建）+ Spring 生命周期托管。**

---

## 3. 独立 vs 内嵌：两条装配路径对照

| 维度 | 独立 Tomcat | 内嵌（Spring Boot） |
|---|---|---|
| 对象树来源 | Digester 解析 `server.xml` | **代码创建**（无 server.xml） |
| 应用部署 | 把 WAR/目录放进 `webapps/`（HostConfig 扫描部署） | 应用本身即进程；Servlet/Filter 通过代码注册 |
| 进程驻留 | 主线程 `await()` 等停机信号（默认 shutdown 端口） | 无 shutdown 端口；由 Spring 生命周期管理（L07 讲） |
| 启动入口 | `bin/startup.sh` → 官方启动流程 | `SpringApplication.run(...)` → 创建并启动 WebServer |
| 启动失败表现 | 日志 + 进程退出 | 启动异常，框架输出失败原因（含端口占用场景） |

相同点（L01 已建立）：组件模型、生命周期机制、请求路径**完全一致**。

---

## 4. 启动顺序（概念级时序）

```
① 创建对象树（独立：Digester 解析 server.xml；内嵌：代码创建）
② 启动容器：Server → Service → Engine → Host → Context
      · Context 启动期间：注册 Servlet/Filter
      · 容器就绪后、Connector 之前：建立路由表（URL→Wrapper）
③ 启动 Connector：绑定端口 + 启动 IO 线程（连接器就绪）
④ 服务就绪（此后才能接收请求）
```

**为什么是这个顺序（关键）**：

1. **先容器、后端口**：避免"端口已监听，但应用/路由还没准备好"的窗口——任何请求进来都能被正确处理；
2. **注册与路由在端口之前**：Servlet/Filter 与路由表属于"应用就绪"的前提；
3. **失败可整体处理**：端口绑定失败发生在最后一步，启动直接失败，不会留下"半可用"状态。

**内嵌特有细节（概念级）**：Boot 在容器启动阶段会把 Connector 暂时摘掉，等容器就绪后再启动它——保证"容器先就绪、端口后监听"的顺序可控（源码细节留到 Stage 3）。

**lab 证据**（可自行复跑）：

```text
日志顺序（内嵌）：
  Tomcat initialized with port ...   ← tomcat.start() 之前：对象树已创建、端口已配置（尚未监听）
  Tomcat started on port ...         ← 启动完成（含 Connector）：端口已监听
```

---

## 5. 启动相关的两个常见问题

**Q：端口被占用是什么时候报出来的？**
> 在 ③ Connector 启动、绑定端口时失败——此时容器/应用都已就绪，但对外端口没拿到，最终表现为启动失败（日志与异常指向端口绑定）。

**Q：为什么内嵌没有 8005 shutdown 端口？**
> 8005 是独立形态 `await()` 方案的一部分（停机信号入口）。内嵌形态由 Spring 生命周期管理 WebServer 的启停（L07 优雅停机），不需要额外端口。

---

## 6. 术语对照（本课汇总）

| 官方英文 | 中文 | 一句话 |
|---|---|---|
| Bootstrap / startup | 启动（器） | 独立形态的启动入口 |
| Digester | 摘要器 | server.xml → 对象树 |
| HostConfig | 主机配置器 | 负责 webapps 部署（独立） |
| ContextConfig | 上下文配置器 | 负责 web.xml/注解等应用配置（独立） |
| LifecycleListener | 生命周期监听器 | 启动过程中接收事件（如部署触发） |
| await() | 等待停机信号 | 独立进程驻留方式 |
| Lifecycle | 生命周期 | 统一的启停骨架（L06） |

---

## 7. Self-Test（参考答案直接给出）

**Q1. 独立 Tomcat 与内嵌 Tomcat 的"装配"分别由谁完成？**
> 参考答案：独立由 Digester 解析 server.xml 创建并配置对象树（HostConfig 部署应用）；内嵌由 Spring Boot 用代码创建对象树并注册 Servlet/Filter。

**Q2. 启动顺序是什么？Connector 为什么在最后？**
> 参考答案：Server → Service → Engine → Host → Context 先启动，Connector 最后启动；这样保证"容器/应用/路由先就绪、端口后监听"，避免请求进来时应用未准备好的窗口，也便于启动失败的整体处理。

**Q3. Servlet/Filter 与路由表分别在什么时候就位？**
> 参考答案：Servlet/Filter 注册在 Context 启动期间；路由表在容器就绪后、Connector 启动前建立——两者都先于端口监听。

**Q4. 端口被占用会在启动的哪一步暴露？**
> 参考答案：Connector 启动、绑定端口时；容器与应用此时已就绪，但端口绑定失败 → 整体启动失败（异常指向端口绑定）。

**Q5. 独立形态的 `await()` 在内嵌里对应什么机制？**
> 参考答案：独立用主线程 await()（+ shutdown 端口）驻留与接收停机信号；内嵌没有 shutdown 端口，由 Spring 生命周期管理 WebServer 启停（优雅停机在 L07）。

---

## 8. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：`mvn -pl tomcat-lab spring-boot:run`，观察日志 "Tomcat initialized with port …" 与 "Tomcat started on port …" 的出现顺序；再故意占用 18080 端口复跑一次，观察启动失败的表现。
- 下一步：**L03 线程模型**——启动完成后，谁是接收连接的线程、谁是分发就绪事件的线程、谁是执行请求的线程（仍为概念级，不掺源码）。
