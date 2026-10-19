# Lesson 11: Mapper 匹配主干 — URI 如何查表落到 Wrapper

> 课程索引：`../学习清单.md` → Stage 3 · Lesson 11
> 版本口径：Tomcat **10.1.34**（类名口径；本课不逐行、不背行号）
> 来源：Servlet 规范 `MappingMatch`（本地源码树 API）；调用链与数据结构按 Tomcat 类名整理；lab 实测（`MappingLabTest`）
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L11-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 按**类名**走通"URI → Host/Context/Wrapper 匹配 + 路由表维护"主干：查询链、数据结构、维护机制 |
| 前置 | L04（进入容器）、L05（三层路由职责与默认落点） |
| 不涉及 | 逐行解读与行号；二分/回退的内部实现代码 |
| 下一课 | L12 Lifecycle 状态机主干 |

> Stage 3 读法：记住**类名 + 方法名 + 先后顺序**，不记实现细节；需要回看具体逻辑时按类名进源码。

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **查询链（类名）**：`CoyoteAdapter.service` → `postParseRequest` → `Mapper.map` → `internalMap`（先定 Host、再定 Context）→ `internalMapWrapper`（Rule 1 **EXACT** → Rule 2 **最长 PATH** → Rule 3 **EXTENSION** → Rule 4 欢迎资源 → **DEFAULT**）。
2. **数据结构（类名）**：`Mapper`（`MappedHost` 数组，用 `find` 二分定位）→ `ContextList` → `ContextVersion`（四数组：`defaultWrapper` / `exactWrappers` / `wildcardWrappers` / `extensionWrappers`）→ 结果载体 `MappingData`（`host/context/wrapper/wrapperPath/requestPath/pathInfo/matchType`…）。
3. **维护机制**：`MapperListener`（生命周期监听器）响应 Host/Context/Wrapper 事件 `register/unregister`；数组更新用**不可变复制替换**（读路径无锁）；`DispatcherServlet` 注册在 `/` → 落在 `defaultWrapper` 槽位（L05）。

---

## 2. 匹配规则的来源

匹配优先级由 **Servlet 规范**定义，本地源码树中的规范 API 即证据：

```
jakarta.servlet.http.MappingMatch:
CONTEXT_ROOT / DEFAULT / EXACT / EXTENSION / PATH
```

> 命名纠错（L05 结论）：前缀/通配映射的规范名是 **PATH**，不是 PREFIX；`CONTEXT_ROOT` 是根上下文特例。

Tomcat 的 `internalMap*` 一族方法是这套规则的实现落地。

---

## 3. 查询链（概念级，按类名）

```
CoyoteAdapter.service
  → postParseRequest（解码、规范化 URI）
  → Mapper.map（入口：host + uri → mappingData）
      → internalMap
          ├─ 先定 Host：MappedHost[] 上 find（二分定位）
          └─ 再定 Context：ContextList 上查找最长前缀（无匹配 → 根上下文）
      → internalMapWrapper（在 ContextVersion 上按规则匹配）
          ├─ Rule 1：internalMapExactWrapper（EXACT）
          ├─ Rule 2：internalMapWildcardWrapper（PATH，要求最长/nesting 最深）
          ├─ Rule 3：internalMapExtensionWrapper（EXTENSION，如 *.jsp）
          ├─ Rule 4：欢迎资源（welcome files）
          └─ DEFAULT：defaultWrapper（"/" 注册的，内嵌 = DispatcherServlet）
```

| 阶段 | 做什么 | 关键类 |
|---|---|---|
| 跨层交接 | 从 Coyote 请求进入 Catalina 映射 | `CoyoteAdapter` |
| 定 Host/Context | 二分定位 + 最长前缀 | `Mapper.internalMap`、`find` |
| 定 Wrapper | 按 Rule 1→7 顺序匹配 | `internalMapWrapper` 一族 |
| 结果 | 填入同样一份结果载体 | `MappingData` |

---

## 4. 数据结构（概念级）

```
Mapper
 └─ MappedHost[]              ← 主机名/别名（find 二分定位）
     └─ ContextList           ← 应用路径列表
         └─ ContextVersion    ← 某 Context 版本的映射快照
              ├─ defaultWrapper       ← "/" 注册（内嵌 = DispatcherServlet）
              ├─ exactWrappers[]      ← EXACT
              ├─ wildcardWrappers[]   ← PATH（按 nesting 有序，最长优先）
              └─ extensionWrappers[]  ← EXTENSION

MappingData（结果载体）
  host / context / wrapper / wrapperPath / requestPath / pathInfo / matchType …
```

- **读路径无竞争**：`MappingData` 是每次请求的栈上载体（复用时先缓存）；四数组读时不加锁；
- **写路径复制替换**：注册/卸载时对数组复制后替换引用（见 §5）。

---

## 5. 维护机制（概念级）

`MapperListener` 是挂在 Service 上的生命周期监听器（概念回顾）：

```
容器事件（Host/Context/Wrapper 启动、注册、卸载）
  → MapperListener.lifecycleEvent
  → registerHost / registerContext / registerWrapper（与 unregister 对应）
  → addHost / addContextVersion / addWrappers（Mapper 的结构方法）
```

- **add 语义**：按映射类型分流——`/*` 进前缀数组、`*.` 进扩展名数组、`/` 进 defaultWrapper，其余精确——数组更新采用**复制后替换**；
- **Boot 对应**：`DispatcherServlet` 注册路径 `/` → 落在 `defaultWrapper`；`register-default-servlet` 默认 false → Tomcat 自带 `DefaultServlet` 不参与竞争。

---

## 6. lab 实测（`MappingLabTest`）

同存三个 Servlet：

| 注册 | 匹配类型 | 请求 | 结果 |
|---|---|---|---|
| `/ping-exact` | EXACT | `/ping-exact` | **200 "exact"** |
| `/ping-path/*` | PATH | `/ping-path/abc` | **200 "path"** |
| DispatcherServlet（`/`） | DEFAULT | `/ping-elsewhere` | 兜底进 DispatcherServlet（无对应 Controller → **404**） |

> 精确胜前缀、前缀胜默认——三行输出即全部证据。

---

## 7. 术语对照（本课汇总）

| 类/术语 | 中文 | 一句话 |
|---|---|---|
| Mapper | 映射器 | 路由表的持有与查询入口 |
| MapperListener | 映射监听器 | 监听容器事件，维护路由表 |
| MappedHost / ContextList / ContextVersion | 映射树节点 | Host / Context / 某版本映射 |
| MappingData | 映射结果载体 | host/context/wrapper/路径/匹配类型 |
| internalMap / internalMapWrapper | 内部匹配 | Host+Context 定位 / Wrapper 规则匹配 |
| addWrappers / register* / unregister* | 增删 | 注册/卸载时的结构更新 |
| MappingMatch | 映射匹配枚举 | EXACT/PATH/EXTENSION/DEFAULT（规范） |

---

## 8. Self-Test（参考答案直接给出）

**Q1. 从 CoyoteAdapter 到得到 Wrapper，经历了哪些类/方法？**
> 参考答案：`CoyoteAdapter.service` → `postParseRequest` → `Mapper.map` → `internalMap`（定 Host、Context）→ `internalMapWrapper`（Rule 1 EXACT → Rule 2 最长 PATH → Rule 3 EXTENSION → Rule 4 欢迎资源 → DEFAULT）。

**Q2. ContextVersion 里的四个"抽屉"各存什么？**
> 参考答案：`defaultWrapper`（注册在 `/` 的默认 Servlet）、`exactWrappers`（EXACT）、`wildcardWrappers`（PATH，按 nesting 有序）、`extensionWrappers`（EXTENSION）。

**Q3. "最长前缀优先"在数据结构上怎么实现？**
> 参考答案：前缀数组按路径深度（nesting）有序存放；匹配时在有序数组上定位后向前找最长、仍能匹配的前缀（概念级；实现细节 Stage 3 按类名查 `internalMapWildcardWrapper`）。

**Q4. 路由表谁维护？增删的机制是什么？**
> 参考答案：`MapperListener` 监听容器事件（register/unregister），经 `Mapper` 的 add/remove 方法更新结构；数组采用复制后替换，读路径无锁。

**Q5. 为什么 DispatcherServlet 在 `defaultWrapper` 里？**
> 参考答案：Boot 把它注册在 `/`，按 `addWrappers` 分流规则进入默认槽位；且内嵌默认不注册 Tomcat 自带 `DefaultServlet`，所以兜底请求都归它。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- 实验（可选）：跑 `MappingLabTest`；把 `/ping-path/*` 改为更具体的 `/ping-path/abc/def` 再注册一个试试优先级。
- 下一步：**L12 Lifecycle 状态机主干**——Stage 3 收官：按类名走状态转换、模板方法与事件触发（不逐行）。
