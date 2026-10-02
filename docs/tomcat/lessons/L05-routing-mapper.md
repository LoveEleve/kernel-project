# Lesson 05: 路由 — 请求如何落到正确的 Host / Context / Wrapper

> 课程索引：`../学习清单.md` → Stage 1 · Lesson 05
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`）；内嵌运行版本 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）
> 来源：`webapps/docs/config/context.xml`、`webapps/docs/config/host.xml`、Servlet 规范 API `jakarta.servlet.http.MappingMatch`；Boot 侧默认值为代码口径（概念级）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L05-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 回答"一个 URI 如何被分配到具体的 Servlet"：三层匹配（Host/Context/Wrapper）、四种 Wrapper 映射、路由表的维护方式、内嵌默认落点 |
| 前置 | L01（组件模型）、L04（请求已进入容器） |
| 不涉及 | 路由表的源码实现（Stage 3）；Spring MVC 的 Controller 路由（属 Spring 课程） |
| 下一课 | L06 生命周期：Lifecycle 状态机 |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **三层路由**：
   - **Host**：按域名（含别名；无匹配用默认主机）选虚拟主机；
   - **Context**：按 **URI 前缀最长匹配**选应用；根上下文 `""` 是默认应用，处理"未分配给其他 Context 的请求"；
   - **Wrapper**：按 **EXACT > 最长 PATH（前缀）> EXTENSION > DEFAULT** 四种映射选 Servlet。
2. **路由表是"预建 + 事件驱动维护"的**：容器启动/注册/卸载时更新；请求时只**查表匹配**，不扫描容器树。
3. **内嵌默认落点**：`DispatcherServlet` 注册在 `/`（**DEFAULT 槽位**）→ 未命中更具体映射的请求都落到它；"交给哪个 Controller"是 **Spring MVC 的第二层路由**（发生在 Servlet 之后）。

---

## 2. 官方原文

### 2.1 Context 的路径匹配（`config/context.xml` · `path`，节选）

> The **context path** of this web application, which is **matched against the beginning of each request URI** to select the appropriate web application for processing. All of the context paths within a particular Host must be unique. If you specify a context path of an empty string (""), you are defining the **default** web application for this Host, which will process all requests **not assigned to other Contexts**.

**翻译**：上下文路径用于和每个请求 URI 的**开头**做匹配，以选择处理该请求的 Web 应用；同一 Host 内路径必须唯一；空串 `""` 表示该 Host 的**默认应用**，处理**未分配给其他 Context 的请求**。

### ① 核心结论

Context 匹配是**前缀匹配**；根上下文 `""` 是**兜底应用**。

### ② 机制说明

- 多个 Context 都能前缀匹配时，取**最长匹配**（规范/Tomcat 实现行为；该官方段落只描述"与开头匹配"，未展开最长规则；源码口径留 Stage 3）；
- `""` 的语义与 Wrapper 层的 `/`（DEFAULT）类似——都是**兜底**，只是层级不同（应用 vs Servlet）。

### ③ 官方要点（2 条）

1. 匹配依据是 **URI 开头（前缀）**；
2. 根上下文 = 默认应用 = 处理"没人认领"的请求。

### ④ 术语对照

| 官方英文 | 中文 | 说明 |
|---|---|---|
| context path | 上下文路径 | 应用的前缀标识 |
| matched against the beginning | 与开头匹配 | 前缀匹配 |
| default web application | 默认 Web 应用 | 根上下文 `""` |
| not assigned to other Contexts | 未分配给其他 Context | 兜底范围 |

### ⑤ 结论

> **Context 层：URI 前缀最长匹配，根上下文 `""` 兜底。**

### 2.2 Host 的名称匹配（`config/host.xml` · `name`，节选）

> Usually the **network name** of this virtual host, as registered in your Domain Name Service server. Regardless of the case used to specify the host name, Tomcat will convert it to **lower case** internally. One of the Hosts nested within an Engine MUST have a name that matches the **defaultHost** setting for that Engine.

**翻译**：Host 通常是虚拟主机的网络名（DNS 名）；Tomcat 内部统一**转小写**；Engine 下必须有一个 Host 的名字与 `defaultHost` 一致（无匹配时的默认主机）。

- **要点**：Host 支持**别名**（官方另有 "Host Name Aliases" 一节：多个网络名指向同一虚拟主机）；内嵌场景只有一个 `localhost`。

### 2.3 规范定义的四种映射（Servlet 规范 API `jakarta.servlet.http.MappingMatch`）

Servlet 规范把"请求是怎么被匹配到的"定义为枚举（本地源码树中的规范 API）：

```
CONTEXT_ROOT / DEFAULT / EXACT / EXTENSION / PATH
```

> 命名纠错（面试易错点）：前缀/通配映射的规范名是 **PATH**，不是 PREFIX；`CONTEXT_ROOT` 是根上下文的特例。

---

## 3. 三层路由（概念级）

```
请求 URI（如 http://localhost:8080/hello）
  → Host 匹配      域名/别名（无匹配 → 默认主机；内嵌只有 localhost）
  → Context 匹配   URI 前缀最长匹配（无匹配 → 根上下文 ""）
  → Wrapper 匹配   EXACT > 最长 PATH > EXTENSION > DEFAULT
  → 得到目标 Servlet（内嵌 = DispatcherServlet）
```

| 层 | 匹配依据 | 多候选规则 | 内嵌实况 |
|---|---|---|---|
| Host | 域名 / 别名 | 精确域名；有默认主机兜底 | 只有 `localhost` |
| Context | URI 前缀 | **最长前缀优先**；`""` 兜底 | 根应用（path 为空） |
| Wrapper | Servlet 映射 | **EXACT > 最长 PATH > EXTENSION > DEFAULT** | `/`（DEFAULT）→ DispatcherServlet |

**走一遍例子**：`http://localhost:8080/api/v2/users`

```
Host    ：localhost 命中（内嵌唯一主机）
Context ：按前缀匹配 → 若无其他应用，根应用 "" 兜底
Wrapper ：EXACT 无；PATH 若注册了 /api/* 则命中（多候选取最长）；EXTENSION 无；最后 DEFAULT → DispatcherServlet
之后    ：Spring MVC 在自己的 HandlerMapping 里决定是否有一个 Controller 处理 /api/v2/users
```

---

## 4. Wrapper 的四种映射（含示例）

| 优先级 | 映射类型（规范名） | 写法示例 | 命中条件 |
|---|---|---|---|
| 1 | **EXACT** | `/hello` | 路径完全相同 |
| 2 | **PATH**（前缀/通配） | `/api/*` | 前缀匹配；**最长者优先** |
| 3 | **EXTENSION** | `*.jsp` | 以指定扩展名结尾 |
| 4 | **DEFAULT** | `/` | 以上都没命中时的兜底（默认 Servlet 槽位） |

- **最长 PATH 优先**的例子：`/api/v2/*` 与 `/api/*` 同时存在时，`/api/v2/users` 命中前者；
- **DEFAULT 只在最后兜底**：内嵌把 `/` 给了 DispatcherServlet（L01 已述）。

---

## 5. 路由表是怎么维护的（概念级）

这张表由 **Mapper** 持有（请求时的查询入口）；由 **MapperListener** 负责维护——它是生命周期监听器，随容器启动就绪、监听容器事件（L06 会讲事件机制）。

| 阶段 | 行为 |
|---|---|
| 启动期 | MapperListener 就绪时登记映射（对应 L02 的启动顺序："容器就绪后、Connector 之前"） |
| 运行期 | 监听容器事件，动态注册/卸载（如代码注册 Servlet）时**增量更新**；对外呈现为不可变快照（读路径无锁）——源码口径 Stage 3 |
| 请求期 | 只做**查表匹配**（Host → Context → Wrapper），不遍历容器树 |

> 设计含义：路由开销与"容器数量"无关，只与"匹配查找"有关——这就是"预建路由表"存在的意义。

---

## 6. 内嵌的默认落点与 404（概念级）

- **默认落点**：Context = 根应用（path 空）+ Wrapper = DispatcherServlet（`/`，DEFAULT）；Boot 侧对应配置：`spring.mvc.servlet.path` 默认 `/`、`server.servlet.context-path` 默认 `/`；
- **Tomcat 自带 DefaultServlet 在内嵌下默认不注册**（`server.servlet.register-default-servlet` 默认 false）→ 静态资源默认由 Spring MVC 处理；
- **404 的两个来源**（概念级）：
  1. **没有 Context 匹配**（容器层）——请求根本没进应用；
  2. **进入应用后没匹配到资源/处理器**——内嵌多数是 Spring MVC 返回 404。

**两层路由的边界（务必分清）**：

| 层 | 决定 | 发生在 |
|---|---|---|
| Tomcat（本课） | URI → **哪个 Servlet** | Servlet 之前 |
| Spring MVC | URI → **哪个 Controller 方法** | DispatcherServlet 内部（Servlet 之后） |

---

## 7. 术语对照（本课汇总）

| 官方英文 | 中文 | 一句话 |
|---|---|---|
| Mapper | 路由表 | 持有 URI→Wrapper 映射并对外提供查询 |
| MapperListener | 路由表维护者 | 监听容器事件，增量更新映射 |
| virtual host | 虚拟主机 | Host：域名 → 应用集合 |
| alias | 别名 | 同一主机的多个网络名 |
| defaultHost | 默认主机 | 无匹配时使用的 Host |
| context path | 上下文路径 | 应用前缀；`""` 为默认应用 |
| mapping / match | 映射 / 匹配 | URI → Servlet 的规则 |
| exact / path / extension / default | 精确 / 路径 / 扩展名 / 默认 | Wrapper 四种映射 |
| MappingMatch | 映射匹配枚举 | Servlet 规范 API |

---

## 8. Self-Test（参考答案直接给出）

**Q1. 三层路由分别按什么匹配？多候选时怎么选？**
> 参考答案：Host 按域名/别名（有默认主机兜底）；Context 按 URI 前缀（最长优先，`""` 兜底）；Wrapper 按四种映射 EXACT > 最长 PATH > EXTENSION > DEFAULT。

**Q2. Context 的 `""` 与 Wrapper 的 `/` 有什么共同点？**
> 参考答案：都是"兜底"语义——`""` 是默认应用（处理未分配给其他 Context 的请求），`/` 是默认 Servlet 槽位（其他映射都不命中时使用）。

**Q3. 四种 Wrapper 映射的规范名与优先级？为什么容易答错？**
> 参考答案：EXACT / PATH / EXTENSION / DEFAULT（另有 CONTEXT_ROOT 特例），优先级按此顺序（PATH 取最长）。易错点是把 PATH 叫成 PREFIX。

**Q4. 路由表是"每请求扫描容器"还是别的机制？**
> 参考答案：启动期预建 + 运行期事件驱动增量更新；请求时只查表匹配，不扫描容器树。

**Q5. 内嵌下"几乎所有请求都进 DispatcherServlet"的原因？以及 404 的两个来源？**
> 参考答案：DispatcherServlet 注册在 `/`（DEFAULT 槽位）且 Tomcat DefaultServlet 默认不注册，所以兜底请求都进它。404 可能来自：容器层没有 Context 匹配；或进入应用后 Spring MVC 没匹配到处理器/资源。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：lab 注册一个额外 Servlet（如 `/other/*`）观察它优先于 `/` 命中；再访问一个不存在的应用路径，区分 404 来源。
- 下一步：**L06 生命周期**——组件如何统一启停（Lifecycle 状态机与事件），为 Stage 2 的停机/异步打基础。
