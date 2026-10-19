# Lesson 12: Lifecycle 状态机主干 — 状态、模板、事件

> 课程索引：`../学习清单.md` → Stage 3 · Lesson 12
> 版本口径：Tomcat **10.1.34**（类名口径；本课不逐行、不背行号）
> 来源：`org.apache.catalina.{Lifecycle,LifecycleState,LifecycleEvent,LifecycleListener}`、`util.LifecycleBase`（按类名）；启动/停止链复用 L02/L05/L06 结论
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L12-4 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 按**类名**走通"状态转换、模板方法、事件触发"主干：枚举 12 态、骨架方法、两条容器启停链 |
| 前置 | L02（启动顺序）、L05（MapperListener）、L06（状态机概念） |
| 不涉及 | 逐行解读与行号；Spring 侧 SmartLifecycle 细节（一句话带过） |
| 下一课 | L13 K8s / 云原生（Stage 4 开始） |

> Stage 3 读法：记住**类名 + 方法名 + 先后顺序**，不记实现细节；需要回看具体逻辑时按类名进源码。

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **状态与事件（类名）**：`LifecycleState` 枚举共 12 态——`NEW / INITIALIZING / INITIALIZED / STARTING_PREP / STARTING / STARTED / STOPPING_PREP / STOPPING / STOPPED / DESTROYING / DESTROYED / FAILED`；每个中间态都绑定一个 `Lifecycle` 接口的事件常量（`BEFORE_INIT / START / AFTER_START / STOP / … / CONFIGURE_START`）。
2. **模板方法（类名）**：`LifecycleBase` 的 `init / start / stop / destroy` 是 **`final synchronized` 骨架**（防并发、防非法转换），子类只实现 `xxxInternal`；**约定：子类必须在 `startInternal()` 里把状态置为 `STARTING`**，否则父类判为非法；失败分**受控**（组件自置 `FAILED`，父组件清理后继续）与**非受控**（抛异常，父组件失败）。
3. **容器启停两条链（类名）**：启动 `StandardServer.startInternal`（先 `CONFIGURE_START` 事件，再启动各 Service）→ `StandardService.startInternal`（`engine → executor → mapperListener → connectors`）；停止：先停流量（`closeServerSocketGraceful` + `pause`）→ `engine.stop()` 停容器 → `connector.stop()` 彻底停连接器 → mapperListener/executor。衔接 Spring 侧：Spring 调用 `Tomcat.start()` 启动整树；`WebServerStartStopLifecycle`（SmartLifecycle）把 WebServer 的 start/stop 挂进 Spring 上下文生命周期。

---

## 2. 状态 ↔ 事件绑定表（`LifecycleState` / `Lifecycle`）

| 枚举状态 | 绑定的事件常量（含义） |
|---|---|
| `INITIALIZING` | `BEFORE_INIT_EVENT`（"before_init"） |
| `INITIALIZED` | `AFTER_INIT_EVENT`（"after_init"） |
| `STARTING_PREP` | `BEFORE_START_EVENT`（"before_start"） |
| `STARTING` | `START_EVENT`（"start"） |
| `STARTED` | `AFTER_START_EVENT`（"after_start"） |
| `STOPPING_PREP` | `BEFORE_STOP_EVENT`（"before_stop"） |
| `STOPPING` | `STOP_EVENT`（"stop"） |
| `STOPPED` | `AFTER_STOP_EVENT`（"after_stop"） |
| `DESTROYING` | `BEFORE_DESTROY_EVENT`（"before_destroy"） |
| `DESTROYED` | `AFTER_DESTROY_EVENT`（"after_destroy"） |
| `NEW` / `FAILED` | 无绑定事件（起点 / 失败态） |
| （配置阶段） | `CONFIGURE_START_EVENT`（"configure_start"：Server 启动配置事件） |

> 记忆技巧：事件名 = "转换动作"。注意一个不对称点：几乎每个状态都有对应事件（NEW 是起点没有；FAILED 是失败终态也没有——此时需先 `stop()` 才能重新启动，Q3 展开）。

## 3. 模板方法分工（`LifecycleBase` 骨架 vs 子类）

```
LifecycleBase                 子类（如 StandardService / Connector / NioEndpoint）
─────────────────────────     ─────────────────────────────────────────
init()                        → initInternal()
start()                       → startInternal()   ← 约定：内部必须 setState(STARTING)
stop()                        → stopInternal()
destroy()                     → destroyInternal()
（四个都是 final synchronized）
```

| 骨架负责（基类） | 子类负责 |
|---|---|
| 状态检查（重复启动/非法转换 → `invalidTransition`） | 各自的内部动作：建线程池、绑端口、建路由表… |
| 状态切换（`setState`）与事件触发（`fireLifecycleEvent`） | 在 `startInternal` 内择机 `setState(STARTING)`（表"已进入启动"） |
| 失败处理：受控（组件自置 FAILED → 基类替该组件调 `stop()` 完成清理，其父组件继续启动其它子组件） | 失败策略选择：抛异常（父失败）或自置 FAILED（父继续） |

---

## 4. 容器启停两条链（类名链）

```
启动链（L02 的机制来源）：
Spring 侧调用 `Tomcat.start()`（Tomcat 类）→ `getServer().start()`
  → StandardServer.startInternal
      → fire CONFIGURE_START_EVENT → 逐个 Service.start()
          → StandardService.startInternal
              → engine.start()（容器树：Engine→Host→Context……）
              → executor.start()
              → mapperListener.start()（路由树，L05）
              → connectors.start()（端口最后监听）

停止链（L06 的机制来源；不是简单反序）：
StandardService.stopInternal
  → 各 Connector：closeServerSocketGraceful()（关监听 socket）+ pause()（停流量）
  → engine.stop()（停容器树）
  → 各 connector.stop()（彻底停连接器）
  → mapperListener.stop() / executor.stop()
```

> Spring 侧衔接（一句话）：`Tomcat.start()` 是 Spring 侧调用点；`WebServerStartStopLifecycle`（SmartLifecycle）把 WebServer 的 start/stop 挂进 Spring 上下文生命周期；优雅停机另有一套"等待在途"机制（L07）。

---

## 5. 为什么状态机值得一个主干课（设计含义）

- **不是"文档装饰"**：没有统一状态与事件，L02 的启动顺序、L05 的路由表维护、L07 的停机等待都无从实现；
- **它是 Tomcat 的"总线"**：配置事件（CONFIGURE_START）、启动事件、容器事件（addHost/addContext）、停止事件都挂在同一套机制上；
- **排障含义**：启动失败先看状态（FAILED 还是抛异常）与最后触发的事件——这就是 05 篇"排障线索"的来源。

---

## 6. 术语对照（本课汇总）

| 类/术语 | 中文 | 一句话 |
|---|---|---|
| Lifecycle | 生命周期 | 统一启停接口（状态常量 + init/start/stop/destroy） |
| LifecycleState | 生命周期状态 | 12 态枚举，每个中间态绑定事件 |
| LifecycleEvent | 生命周期事件 | 状态变更通知（type + data） |
| LifecycleListener | 生命周期监听器 | 事件消费者（MapperListener 等） |
| LifecycleBase | 生命周期基类 | 模板方法骨架（final synchronized） |
| startInternal / xxxInternal | 内部动作 | 子类填充的"干什么" |
| FAILED | 失败态 | 受控失败可继续，非受控失败向上传播 |

---

## 7. Self-Test（参考答案直接给出）

**Q1. 12 个状态是哪些？事件如何与状态绑定？**
> 参考答案：NEW / INITIALIZING / INITIALIZED / STARTING_PREP / STARTING / STARTED / STOPPING_PREP / STOPPING / STOPPED / DESTROYING / DESTROYED / FAILED；每个中间态对应一个事件常量（如 STARTING→`start`、STARTED→`after_start`），另有 `configure_start`。

**Q2. `LifecycleBase` 的模板方法分工是什么？STARTING 约定是什么？**
> 参考答案：`init/start/stop/destroy` 是 `final synchronized` 骨架（状态检查、切换、事件、失败处理），子类实现 `xxxInternal`；子类必须在 `startInternal()` 内把状态置为 `STARTING`，否则按非法转换处理。

**Q3. 受控失败与非受控失败的区别？**
> 参考答案：受控=组件自己进入 `FAILED`（基类替该组件调 `stop()` 完成清理，其父组件继续启动其它子组件；要重新启动该组件需先 `stop()`）；非受控=抛异常（父组件随之失败）。看状态是 FAILED 还是抛了异常，就知道是哪种。

**Q4. 从类名说出启动与停止两条链。**
> 参考答案：启动：`StandardServer.startInternal`（CONFIGURE_START+各 Service）→ `StandardService.startInternal`（engine→executor→mapperListener→connectors）。停止：先 `closeServerSocketGraceful`+`pause`停流量 → `engine.stop()` → `connector.stop()` → mapperListener/executor。（L06 结论：不是简单反序。）

**Q5. 为什么说 Lifecycle 是 Tomcat 的"总线"？**
> 参考答案：配置、启动、容器事件、停止、优雅停机都经过状态+事件——MapperListener（路由）、HostConfig（部署）、优雅等待（停机）全是它的消费者。

---

## 8. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- **Stage 3（源码主干 L10~L12）全部完成**：L10 Connector/Endpoint → L11 Mapper → L12 Lifecycle。
- 下一步：**L13 K8s / 云原生**——Stage 4（生产）开始：资源限制、探针、优雅下线对齐。
