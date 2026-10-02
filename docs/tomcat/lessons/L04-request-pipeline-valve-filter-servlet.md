# Lesson 04: 请求处理全链 — Valve → Filter → Servlet

> 课程索引：`../学习清单.md` → Stage 1 · Lesson 04
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`）；内嵌运行版本 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）
> 来源：`webapps/docs/config/valve.xml`、`webapps/docs/config/filter.xml`
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L04-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 建立"请求进入容器后如何到达 Servlet"的完整形状：四层 Valve → Filter 链 → Servlet；分清 Valve 与 Filter |
| 前置 | L01（组件）、L02（启动）、L03（线程：整条链在 Worker 上） |
| 不涉及 | 路由选择细节（L05）、源码（Stage 3） |
| 下一课 | L05 路由：Mapper 的职责 |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **四层 Valve 链**：`Engine → Host → Context → Wrapper`。每层容器持有一个 **Pipeline**，请求沿 Pipeline 内的 Valve 顺序传递；外层容器的"基本 Valve"负责把请求交给**下一层容器**，Wrapper 层的基本 Valve 负责交给 **Filter 链/Servlet**。Valve 是**容器级**扩展（由部署配置/容器代码装配，应用无法绕过）。
2. **Filter 是应用级**：请求到达 Wrapper 后，容器按 **dispatcher type + URL（+ Servlet 名）** 选出适用的 Filter，组成 **FilterChain**；`doFilter` 的**前段**在 `servlet.service()` 之前执行、**后段**在其之后；不调用 `chain.doFilter()` 即**短路**（Servlet 不执行）。
3. **边界与错误出口**：Valve **先于** Filter 执行（容器层比应用层靠外）；容器级错误出口是 Host 上的 **ErrorReportValve**（生成 HTML 错误页），应用内错误由应用自己处理（内嵌通常是 Spring MVC）。

---

## 2. 官方原文

### 2.1 Valve（`config/valve.xml`，节选）

> A **Valve** element represents a component that will be inserted into the request processing pipeline for the associated Catalina container (**Engine**, **Host**, or **Context**). Individual Valves have distinct processing capabilities, and are described individually below.

**翻译**：Valve 是要插入到关联 Catalina 容器（Engine、Host 或 Context）的**请求处理管道**中的组件；不同 Valve 具备不同处理能力。

### ① 核心结论

容器处理请求的方式是"**管道 + 阀门**"：每层容器有一条处理管道，请求顺序经过管道内的阀门；阀门是**容器级**扩展点。

### ② 机制说明

- "关联容器"决定了 Valve 的作用范围：挂在 Engine 上对整机生效，挂在 Context 上只对该应用生效；
- 每层管道都有一个**基本 Valve**（容器内置、位置固定），职责是把请求传递到下一层容器——这是"四层链"得以成立的原因；
- 官方文档列举的可配置层是 Engine/Host/Context；Wrapper 层的收尾由容器内置机制完成（源码口径留 Stage 3）。

### ③ 官方要点（2 条）

1. Valve 的挂载对象是**容器**（不是应用）；
2. Valve 的职责由各自实现决定（如访问日志、错误页）。

### ④ 术语对照

| 官方英文 | 中文 | 说明 |
|---|---|---|
| Valve | 阀门 | 容器管道的处理节点 |
| request processing pipeline | 请求处理管道 | Pipeline |
| associated Catalina container | 关联的 Catalina 容器 | Valve 的作用范围 |
| Individual Valves | 各个 Valve | 能力各自不同 |

### ⑤ 结论

> **Valve = 容器级处理节点，挂在 Engine/Host/Context 的管道上；请求由外层容器向内层容器逐层传递，靠的就是每层的基本 Valve。**

### 2.2 Filter 的配置层级（`config/filter.xml`，节选）

> Tomcat provides a number of **Filters** which may be configured for use with all web applications using `$CATALINA_BASE/conf/web.xml` or may be configured for individual web applications by configuring them in the application's `WEB-INF/web.xml`.

**翻译**：Filter 既可以配置为对所有 Web 应用生效（全局 `conf/web.xml`），也可以只对单个应用生效（应用的 `WEB-INF/web.xml`）。

- **要点**：Filter 的配置位置是 **Web 应用的配置**（web.xml / 代码注册）——它是**应用级**组件；这与 Valve 的容器级挂载形成对照。
- **内嵌形态**：没有 web.xml，Filter 由代码注册（相当于程序化完成 web.xml 的映射）。

### 2.3 错误出口（`config/valve.xml` · Error Report Valve，节选）

> The **Error Report Valve** is a simple error handler for HTTP status codes that will generate and return HTML error pages. It can also be configured to return pre-defined static HTML pages for specific status codes and/or exception types.

**翻译**：Error Report Valve 是一个针对 HTTP 状态码的简单错误处理器，会生成并返回 HTML 错误页；也可配置为对特定状态码/异常类型返回预置的静态 HTML 页。

- **要点**：这是**容器级**错误出口（默认挂在 Host 管道上）——与"应用自己处理异常"是两条出口。
- **内嵌细节**：Spring Boot 会按 `server.error.*` 定制该阀门（例如 `include-stacktrace=never` 时，向 Host 管道追加一个关闭 showReport/showServerInfo 的实例）。

---

## 3. 四层 Valve 链（概念级）

请求进入容器前还有一步"交接"：先由**连接器**把它解析为协议对象（Coyote 世界），再经**适配**交给容器（Catalina 世界）——本课从"已进入容器"开始，交接细节留 Stage 3。

```
请求（已由连接器解析并适配为容器请求）
  → Engine 管道      （引擎层阀门 → 交给 Host）
  → Host 管道        （主机层阀门；默认含 ErrorReportValve → 交给 Context）
  → Context 管道     （应用层阀门 → 交给 Wrapper）
  → Wrapper 管道     （收尾：定位 Servlet + 构建 Filter 链）
  → Filter 链        （应用级加工）
  → Servlet.service()
```

| 层 | 该层做什么 | 默认/典型阀门 | 应用能绕过吗 |
|---|---|---|---|
| Engine | 请求进入容器的第一站 | 基本 Valve（向内传递） | 不能 |
| Host | 主机级处理、错误页出口 | 基本 Valve + **ErrorReportValve** | 不能 |
| Context | 应用级处理 | 基本 Valve；可加 `AccessLogValve` 等 | 不能 |
| Wrapper | 定位 Servlet、构建 Filter 链 | 收尾 Valve（交给 Filter/Servlet） | 不能 |

> 可配置阀门（官方）：挂在 Engine/Host/Context 的管道上；典型如访问日志（AccessLogValve）、错误页（ErrorReportValve）。
> 默认装配行为（如 Host 默认含 ErrorReportValve）来自源码核对；细节留 Stage 3。

---

## 4. Valve vs Filter（一表看清边界）

| 维度 | Valve | Filter |
|---|---|---|
| 归属 | **容器**（Tomcat） | **应用**（Servlet 规范） |
| 配置位置 | 容器配置/容器代码（`conf/server.xml` 等） | 应用配置（web.xml / 代码注册） |
| 作用范围 | 挂载容器的全部请求 | 按 **URL 模式 / Servlet 名 / dispatcher type** 匹配的请求 |
| 执行时机 | Filter **之前**（容器层） | Servlet 之前/之后（应用层） |
| 应用能否绕过 | **不能**（容器装配） | 应用自行注册/管理（可控） |
| 典型用途 | 访问日志、错误页、真实 IP | 编码、CORS、鉴权、TraceId、耗时 |

---

## 5. Filter 链的执行模型（概念级）

1. **构建时机**：请求到达 Wrapper、确定目标 Servlet 之后，**每次 dispatch 重新匹配并组装链内容**（forward/error/async 转发各自会构建一次；链对象本身可能被复用）；
2. **匹配条件**：先按 dispatcher type 过滤（默认仅 `REQUEST`），再按 **URL 模式**（精确/前缀/扩展名/`/*`）**或 Servlet 名**匹配；
3. **执行顺序**：按注册的 order（小者先）；执行到链尾进入 `servlet.service()`；返回时按**逆序**执行各 Filter 的后段；
4. **短路**：Filter 不调用 `chain.doFilter()`，链终止、Servlet 不执行（例如鉴权失败直接返回 401）；
5. **响应头时机**：后段写响应头时，若响应已提交则会被忽略——"事后才知道的值"（如耗时）更适合日志/指标上报。

> 与 L03 的衔接：Filter 链运行在 **Worker** 线程上（同步语义），与 `servlet.service()` 同线程。

---

## 6. 链的终点与响应

- Wrapper 找到目标 Servlet（内嵌 = **DispatcherServlet**）；
- `servlet.service()` 是 Tomcat 的"交棒点"：之后进入 Spring MVC（HandlerMapping → Controller → 返回）；
- 响应沿原路返回：Servlet → Filter 链后段 → Valve 链 → 连接器编码 → 客户端。

---

## 7. 术语对照（本课汇总）

| 官方英文 | 中文 | 一句话 |
|---|---|---|
| Valve | 阀门 | 容器管道的处理节点 |
| Pipeline | 管道 | Valve 的有序集合 |
| Filter | 过滤器 | 应用级处理组件 |
| FilterChain | 过滤器链 | 应用级链，链尾调用 Servlet |
| dispatcher type | 分派类型 | REQUEST/FORWARD/INCLUDE/ERROR/ASYNC |
| Error Report Valve | 错误报告阀 | 容器级 HTML 错误页 |
| servlet.service() | — | Servlet 的处理入口（交棒点） |

---

## 8. Self-Test（参考答案直接给出）

**Q1. 四层 Valve 链的顺序是什么？请求如何从一层到下一层？**
> 参考答案：Engine → Host → Context → Wrapper；每层容器持有一个 Pipeline，请求沿其 Valve 顺序传递；外层容器的"基本 Valve"把请求交给下一层容器，Wrapper 层的基本 Valve 交给 Filter 链/Servlet。

**Q2. Valve 和 Filter 的边界？各自归属谁、能被应用绕过吗？**
> 参考答案：Valve 属容器（配置在容器层，应用无法绕过）；Filter 属应用（web.xml/代码注册，按匹配生效）。Valve 先执行，Filter 后执行。

**Q3. Filter 链什么时候构建？按什么匹配？**
> 参考答案：请求到达 Wrapper、确定目标 Servlet 后按请求（每次 dispatch）构建；按 dispatcher type + URL 模式（或 Servlet 名）匹配。

**Q4. `doFilter` 的前段/后段分别相对什么而言？不调用 `chain.doFilter()` 会怎样？**
> 参考答案：前段在 `servlet.service()` 之前执行、后段在其之后；不调用 `chain.doFilter()` 则链终止、Servlet 不执行（短路）。

**Q5. 容器级错误与应用内错误分别由谁处理？**
> 参考答案：容器级错误（如状态码错误页）默认由 Host 上的 ErrorReportValve 生成 HTML 错误页；应用内异常由应用自己处理（内嵌通常是 Spring MVC 的异常处理）。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：写一个 Filter（lab 恰好有 `RequestTimingFilter`）观察"前段写头、后段记耗时"的时序；对照 `/slow` 响应确认 Filter 与 Servlet 同线程。
- 下一步：**L05 路由**——请求如何被分配到正确的 Host/Context/Wrapper（Mapper 的职责与默认落点）。
