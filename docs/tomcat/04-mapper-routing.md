# 面试题详解 04 | Mapper 路由（四级匹配与热更新）

> 难度：★★★★☆ ｜ 频率：★★★★☆ ｜ 区分度：中高（Servlet 规范落地 + 数据结构 + 无锁热更新）
> 关键词：Mapper、MapperListener、MappingData、MappingMatch（EXACT/PATH/EXTENSION/DEFAULT）、exact/wildcard/extension wrappers、defaultWrapper、不可变数组替换、DispatcherServlet 映射 "/"

---

## 问题

**一个请求的 URL 是怎么被映射到具体 Servlet 的？Mapper 的数据结构是什么？四级匹配（EXACT / PATH / EXTENSION / DEFAULT）分别什么时候命中？容器启停时路由怎么热更新（不重启）？**

---

## 30 秒简洁回答

> Mapper 是 URL → (Host, Context, Wrapper) 的映射器，由 `MapperListener` 监听容器生命周期事件**在启动/变更时构建**。请求时 `CoyoteAdapter.postParseRequest` 调 `mapper.map(...)`：先按域名匹配 Host（精确 → 通配 → `defaultHost` 兜底），再按路径匹配 Context（**最长前缀优先**），最后在 `ContextVersion` 里按 **Servlet 规范的顺序**匹配 Wrapper：**EXACT（`/api`）→ PATH（`/api/*`）→ EXTENSION（`*.jsp`）→ DEFAULT（`/`）**，把结果写进 `MappingData`。热更新的做法是"**不可变数组 + 复制替换 + volatile 引用**"（读路径无锁）。内嵌场景 DispatcherServlet 注册在 `/`，命中 **defaultWrapper**——所以几乎所有请求都进它。

---

## 展开回答（高级开发级）

### 1）Mapper 的数据结构（一棵映射树）

```
Mapper（Mapper.java:47）
 └─ MappedHost[] hosts（:1489；精确 + 通配，有序；defaultHost 兜底）
      └─ ContextList（:1563-1598；MappedContext[] + nesting）
           └─ ContextVersion（:1611）
                ├─ defaultWrapper      ← "/" 的映射（:1616）
                ├─ exactWrappers[]     ← "/path"（:1617）
                ├─ wildcardWrappers[]  ← "/path/*"（:1618，nesting 记录最大段数）
                └─ extensionWrappers[] ← "*.jsp"（:1619）
```

### 2）Wrapper 四级匹配（Servlet 规范落地）

`internalMapWrapper`（:820）按 Tomcat 注释的规则顺序执行：

```
Rule 1 EXACT     ：internalMapExactWrapper（注释 :834，调用 :836；matchType=EXACT :1009）
Rule 2 Prefix    ：internalMapWildcardWrapper（注释 :838，调用 :842）
                   · “最长前缀”实现在 :1018-1067（对有序数组定位后向前走取最长匹配；matchType=PATH :1057）← "/path/*"
Rule 3 EXTENSION ：internalMapExtensionWrapper（注释 :871，调用 :874；matchType=EXTENSION :1099）← "*.jsp"
Rule 4 欢迎文件   ：welcome resources 处理（:877-947，含物理文件检查）
Rule 7 DEFAULT   ：contextVersion.defaultWrapper（注释 :949，matchType=DEFAULT :955）
（另有 CONTEXT_ROOT 特例：:1006）
```

`MappingMatch` 是 **Servlet 规范枚举**（`jakarta/servlet/http/MappingMatch.java`：`CONTEXT_ROOT :29 / DEFAULT :34 / EXACT :39 / EXTENSION :44 / PATH :49`）——注意"前缀/通配"的规范名字是 **PATH**，不是 PREFIX。

### 3）Host 与 Context 匹配

- **Host**：`exactFindIgnoreCase(hosts, host)`（:709）→ 通配 Host（如 `*.example.com`）→ **`defaultHost` 兜底**（:725）；
- **Context**：`internalMap` 里先 `find(contexts, uri)` 二分定位（:743），再**向前回退找最长前缀**（`uri.startsWith(context.name)` :755；root context `""` 兜底 :776；结构见 `ContextList` :1563-1598）。

### 4）谁建树、什么时候建（热更新的真相）

`MapperListener.lifecycleEvent`（:453）监听容器事件并调用 Mapper：

```
Host    启动/停止 → addHost（:295） / removeHost（:319）
Context 启动/停止 → addContextVersion（:380） / removeContextVersion（:411）
Wrapper 注册/注销 → addWrappers（:431）/ addWrapper（:191） / removeWrapper（:207/:348）
Host 别名        → addHostAlias（:174） / removeHostAlias（:177）
```

**更新实现**：数组不可变 + 复制替换（`addWrapper` 里 `oldWrappers → newWrappers` 复制后替换引用，`Mapper.java:445-475`；`ContextList.addContext` 同理 :1574-1580）→ **读路径完全无锁**。所以部署/卸载/重载应用不需要重启，路由会热更新。

### 5）内嵌视角：DispatcherServlet 为什么能接住所有请求

- Boot 把 DispatcherServlet 注册到 `spring.mvc.servlet.path`（默认 **"/"**，`WebMvcProperties.Servlet.path:216`；注册见 `DispatcherServletAutoConfiguration:116-118`）；
- Mapper 的 `addWrapper` 对 "/" 走 **defaultWrapper** 分支（:467）；
- 于是除 EXACT/PATH/EXTENSION 命中外，其余请求全部落到 DispatcherServlet → Spring MVC 再按自己的 HandlerMapping 做**第二级路由**（Spring 卷展开）。

### 6）404 与 Mapper 的关系（呼应 03 篇）

```
Host 无匹配      → defaultHost 兜底（一般总能匹配到）
Context 无匹配   → StandardHostValve:87 → 404
Wrapper 无匹配   → defaultWrapper 兜底（DispatcherServlet）；若 default 也找不到资源 → DefaultServlet:732 → 404
```

---

## 进一步回答（架构师层级）

- **为什么用"不可变数组 + 复制替换"**：路由是"读极多、写极少"（只有部署/重载时写）——复制替换让读路径无锁且对 CPU cache 友好，写侧的复制成本可忽略。
- **ContextVersion 版本化**：同一 Context 的多个版本可并存（reload 时新版本先建、旧版本处理完在途请求后退役），这是"重载不中断请求"的基础。
- **正确性依赖事件顺序**：Mapper 树必须"先 Host/Context、后 Wrapper"地建（`MapperListener` 按容器生命周期事件顺序执行）；顺序错乱会导致路由缺失。
- **两级路由别混淆**：Tomcat Mapper 决定"**哪个 Servlet**"（内嵌场景几乎总是 DispatcherServlet）；Spring MVC 的 HandlerMapping 决定"**哪个 Controller**"。排查 404 时要先分清是哪一级。
- **监控/排障**：Mapper 相关问题（404、映射错乱、reload 后路由不更新）都先看"容器事件是否触发/路由树是否重建"。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：这道题考"Servlet 规范的 URL 匹配规则在 Tomcat 里怎么落地，以及路由如何热更新"；
- **回答顺序**：数据结构（树）→ 四级匹配顺序 → 谁建树（MapperListener）→ 热更新（不可变数组）→ 内嵌 DispatcherServlet 的 "/" 兜底；
- **一句话收束**："Mapper 把 URL 变成 (Host, Context, Wrapper)：EXACT→PATH→EXTENSION→DEFAULT 逐级兜底；树由 MapperListener 在容器事件里建，用不可变数组替换实现无锁热更新。"

## 必记关键词

`Mapper` ｜ `MapperListener` ｜ `MappingData` ｜ `MappingMatch` ｜ `EXACT/PATH/EXTENSION/DEFAULT` ｜ `exactWrappers/wildcardWrappers/extensionWrappers` ｜ `defaultWrapper` ｜ `ContextVersion` ｜ `nesting` ｜ 不可变数组替换 ｜ `DispatcherServlet "/"`

## 必须明确的边界

- 四级匹配的规范名字是 **EXACT / PATH / EXTENSION / DEFAULT**（PATH=前缀/通配），另有 `CONTEXT_ROOT` 特例；
- **最长路径优先**：`/api/v2/*` 优先于 `/api/*`——实现在 `internalMapWildcardWrapper`（:1018-1067：对有序数组二分定位后向前走，选最长匹配）；
- Mapper 只负责"找谁"，不负责"调用谁"（调用是 Valve 链，见 03 篇）；
- 路由树是**容器事件驱动**建的，不是请求时懒加载；
- 内嵌场景的 "/" 是 **defaultWrapper**（兜底），不是"精确映射到根路径"。

## 常见错误

- 把四级匹配说成"EXACT/PREFIX/EXTENSION/DEFAULT"（规范叫 **PATH**）；
- 以为 Mapper 每次请求都遍历容器列表（实际是预建结构上的查找）；
- 以为热更新靠加锁（实际是不可变数组 + 引用替换）；
- 把 Tomcat Mapper 和 Spring MVC HandlerMapping 混为一谈；
- 说不清 DispatcherServlet 为什么能接住所有请求（答不出 defaultWrapper）。

## 自测要求

- **30 秒**：数据结构 + 四级顺序 + 谁建树 + 热更新方式；
- **3 分钟**：能画出映射树并解释"最长路径优先"的实现（`internalMapWildcardWrapper:1018-1067`）；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：为什么"最长路径优先"？`/api/*` 和 `/api/v2/*` 同时存在时怎么选？**
> 规范要求更具体的路径优先：Rule 1 精确未中后，Rule 2 在有序的 `wildcardWrappers` 上定位并**向前走找最长（nesting 最深）的前缀**（`internalMapWildcardWrapper:1018-1067`）——所以 `/api/v2/users` 命中 `/api/v2/*`，而不是 `/api/*`。

**追问 2：热更新怎么做到不加锁？**
> 映射结构用不可变数组 + 复制替换（`addWrapper` :445-475、`ContextList.addContext` :1574-1580），字段是 volatile 引用——读请求永远看到某一完整版本，写（部署/重载）只是替换引用。

**追问 3：Tomcat 的 Mapper 和 Spring MVC 的 HandlerMapping 有什么区别？**
> 两级路由：Tomcat Mapper 把 URL 映射到 **Servlet**（内嵌场景几乎总是 DispatcherServlet，`/` 为 defaultWrapper）；DispatcherServlet 内部再由 HandlerMapping 映射到 **Controller/Handler**。排查 404 要先分清哪一级。

---

## 示例与使用说明

```text
观察路由结果（断点/日志）：
  Mapper.internalMapWrapper   （:820）——看 EXACT/PATH/EXTENSION/DEFAULT 哪一步命中
  MappingData                 ——host/context/wrapper/requestPath/pathInfo/matchType
验证 defaultWrapper：
  请求一个不存在的路径（如 /no-such）——仍会进入 DispatcherServlet，由 Spring MVC 返回 404
```

## 面试官评分点

**通过标准**
- 能讲四级匹配的规范顺序（EXACT/PATH/EXTENSION/DEFAULT）与最长路径优先；
- 能讲 MapperListener 的建树时机；
- 能讲不可变数组热更新。

**加分项**
- 能带行号讲 `internalMapWrapper` 的回退循环与 matchType 赋值；
- 能解释 DispatcherServlet 的 "/" → defaultWrapper；
- 能区分 Tomcat Mapper 与 Spring HandlerMapping 两级路由。

**危险信号**
- 四级匹配名字说错（PREFIX）；
- 以为请求时遍历容器；
- 不知道热更新机制。

## 实战练习

1. 在内嵌应用注册两个 Servlet：`/api/*` 与 `/api/v2/*`，请求 `/api/v2/x` 验证最长优先；
2. 注册一个 `*.foo` 的 Servlet，观察 EXTENSION 匹配；
3. 断点 `Mapper.internalMapWrapper`，分别请求 exact/path/extension/default 四类路径，观察 matchType。

## 版本与来源

- Tomcat **10.1.34**：`java/org/apache/catalina/mapper/{Mapper,MapperListener}.java`；`java/jakarta/servlet/http/MappingMatch.java`；
- Spring Boot **3.5.16**：`DispatcherServletAutoConfiguration.java`、`WebMvcProperties.java`；
- 本卷章节：06（Mapper 路由）、01（请求旅程）、05（CoyoteAdapter）；
- 规划：`Tomcat源码学习范围规划.md` T-6（Mapper 路由与动态更新专题）。

## 个人经历填充区（留空，按真实项目补）

- 遇到过路由相关的线上问题吗（404、reload 不生效、映射冲突）？
- 自定义过 Servlet 映射或替换过默认 Servlet 吗？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Spring Boot 3.5.16）；示例为操作路径（未附真实运行输出）；项目经历请用真实案例填充。
