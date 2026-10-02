# Lesson 06: 生命周期 — 组件如何统一启停

> 课程索引：`../学习清单.md` → Stage 1 · Lesson 06
> 版本口径：Tomcat **10.1.34**（源码树自带官方文档树 `webapps/docs/`）；内嵌运行版本 = Spring Boot 3.2.5 自带 Tomcat 10.1.20（机制一致）
> 来源：`webapps/docs/config/listeners.xml`、`webapps/docs/architecture/startup.xml`
> 状态：✅ 已讲（2026-09-29）
> **提问方式：直接说编号**，如「L06-3 没懂」

---

## 0. 本课定位

| 项 | 内容 |
|---|---|
| 目标 | 回答"几十个组件为什么能有序启停、还能被监听/定制"：统一状态机 + 模板方法 + 事件机制（概念级） |
| 前置 | L01（组件模型）、L02（启动顺序）、L05（MapperListener 第一次出现） |
| 不涉及 | 源码实现（Stage 3）；停机时的业务语义（L07 展开） |
| 下一课 | L07 优雅停机——Stage 2（机制）开始 |

---

## 1. 🔴 要掌握的 3 条（结论层，需要能复述）

1. **统一状态机与事件**：所有组件（Server/Service/Engine/Host/Context/Connector…）遵循同一套生命周期状态；每次状态变更都会**触发事件**（`before_start` / `start` / `after_start` 等）。状态机保证：顺序合法、不重复启动、失败可定位（`FAILED`）。
2. **模板方法**：启停的**骨架统一**（`init/start/stop/destroy` 对外行为一致），子类只实现各自的内部动作（`xxxInternal`）；**父组件负责启动/停止子组件**——L02 的启动顺序正是这套机制的结果。
3. **事件驱动联动**：监听器（`LifecycleListener`）通过监听事件完成任务——**MapperListener 维护路由表**（L05）、**HostConfig 部署 webapps**（独立形态）；启动过程可用自定义监听器定制（官方原话）。

---

## 2. 官方原文

### 2.1 监听器是什么（`config/listeners.xml`，节选）

> A **Listener** element defines a component that performs actions when specific events occur, usually Tomcat starting or Tomcat stopping.

> Java class name of the implementation to use. This class must implement the `org.apache.catalina.LifecycleListener` interface.

**翻译**：Listener 是"在特定事件发生时执行动作"的组件，通常是 Tomcat 启动/停止相关事件；其实现类必须实现 `org.apache.catalina.LifecycleListener` 接口。

### ① 核心结论

组件启停不是"悄悄发生"的——每一步都会**发事件**，监听器据此执行联动动作。

### ② 机制说明

- 事件源：组件状态变更；
- 事件消费者：`LifecycleListener`（可挂在 Server/Engine/Host/Context 等容器上）；
- 典型联动：路由表维护（MapperListener）、应用部署（HostConfig）——它们都不"主动轮询"，而是**被动响应事件**。

### ③ 官方要点（2 条）

1. 监听器的作用是"事件发生时执行动作"；
2. 监听器必须实现 `LifecycleListener` 接口，可挂在容器上（Server/Engine/Host/Context）。

### ④ 术语对照

| 官方英文 | 中文 | 说明 |
|---|---|---|
| Listener | 监听器 | 事件发生时执行动作的组件 |
| specific events | 特定事件 | 通常与启动/停止相关 |
| LifecycleListener | 生命周期监听器 | 规定的实现接口 |

### ⑤ 结论

> **事件机制让组件之间"解耦联动"：组件只管发事件，监听器负责做事。**

### 2.2 启动过程可定制（`architecture/startup.xml`，节选）

> The startup process can be customized in many ways by implementing your own **LifecycleListeners** which are then registered in the server.xml configuration file.

**翻译**：启动过程可以通过实现自定义 `LifecycleListener` 来做多种定制，然后注册到 `server.xml`。

- **要点**：官方把"监听器"定位为**扩展点**；内嵌形态没有 server.xml，等价做法是把监听器注册到容器对象上（代码方式）。

---

## 3. 状态机（概念级）

```
NEW → INITIALIZED → STARTING → STARTED → STOPPING → STOPPED → DESTROYED
                        │                     │
                        └────── 失败 → FAILED ─┘
```

| 状态转换 | 触发的事件 |
|---|---|
| 配置（启动流程最前） | `configure_start` |
| 初始化 | `before_init` / `after_init` |
| 启动 | `before_start` / `start` / `after_start` |
| 停止 | `before_stop` / `stop` / `after_stop` |
| 销毁 | `before_destroy` / `after_destroy` |

要点（概念级）：

- `STARTING` / `STOPPING` 是**中间态**——代表"正在进行"，监听器能看到这个过程（完整枚举含"准备/进行中"的细分，源码口径留 Stage 3）；
- **状态检查**：重复启动、非法转换会被拦下；
- **失败语义**：组件可**受控失败**（自行进入 `FAILED`，父组件继续启动其它组件）；也可**非受控失败**（抛异常，父组件随之失败）——源码口径，Stage 3 展开。

---

## 4. 模板方法：骨架统一 + 子类填空

| 对外（统一骨架） | 子类实现（各自内部动作） |
|---|---|
| `init()` | `initInternal()` |
| `start()` | `startInternal()` |
| `stop()` | `stopInternal()` |
| `destroy()` | `destroyInternal()` |

规则（概念级）：

- 骨架负责状态检查、状态切换、事件触发、失败处理；
- 子类只写"干什么"（建线程池、绑定端口、注册映射…）；
- **父组件的 `startInternal()` 负责启动子组件**（L02 顺序的来源）；停止同样由父组件触发子组件停止，但 **Connector 的停止顺序特殊**（见 §6）。

**为什么这样设计**：几十个组件的启停逻辑若各自为政，顺序/失败/可观测都无法统一；模板方法把"怎么启停"收进基类，把"启停时干什么"留给子类。

---

## 5. 事件驱动联动（三个例子）

| 监听器 | 监听什么 | 做什么 |
|---|---|---|
| **MapperListener** | Host/Context/Wrapper 相关事件 | 维护路由表（L05：启动期登记、运行期增量更新） |
| **HostConfig** | Host 相关事件 | 扫描 `webapps/` 部署应用（独立形态） |
| 自定义监听器 | 任意生命周期事件 | 观察/介入启动过程（官方认可的定制手段） |

> 事件顺序示例（概念级）：一个 Context 启动时，监听器会依次看到 `before_start` → `start` → `after_start`——这就是"组件启停可观测"的来源。

---

## 6. 启停顺序（回扣 L02）

```
启动：Server → Service → Engine → Host → Context ……（Connector 最后，L02）

停止（注意：不是简单反序；源码口径，Stage 3 展开）：
  ① 先让 Connector 停流量（关闭监听 socket、pause）
  ② 再停容器（Engine → Host → Context ……）
  ③ 最后才彻底停止 Connector 本身
```

> 为什么不是简单反序：停止要先"切断外部流量"（①），再拆内部（②），最后释放连接器资源（③）——停机业务语义在 L07 展开。

统一生命周期机制是顺序、失败处理与可观测的**共同前提**：没有它，L02 的"容器先就绪、端口后监听"就无法保证。

---

## 7. 术语对照（本课汇总）

| 官方英文 | 中文 | 一句话 |
|---|---|---|
| Lifecycle | 生命周期 | 统一的启停抽象 |
| LifecycleListener | 生命周期监听器 | 事件发生时执行动作 |
| LifecycleEvent | 生命周期事件 | 状态变更的通知 |
| state | 状态 | NEW/STARTED/STOPPED/FAILED… |
| startInternal / stopInternal | 内部启停动作 | 子类实现的"干什么" |
| FAILED | 失败状态 | 失败可定位、可清理 |

---

## 8. Self-Test（参考答案直接给出）

**Q1. 为什么 Tomcat 需要统一的生命周期机制？**
> 参考答案：组件多且有依赖顺序（容器先、Connector 后）；统一机制负责顺序、状态检查（防重复/非法）、事件通知与失败处理——否则每个组件各自为政，无法保证"先就绪、后监听"和可观测。

**Q2. 状态与事件怎么对应（举三组）？**
> 参考答案：初始化→`before_init/after_init`；启动→`before_start/start/after_start`；停止→`before_stop/stop/after_stop`（另有销毁与 configure_start）。

**Q3. 模板方法里"骨架"和"子类"各负责什么？**
> 参考答案：骨架（父类）负责状态检查、状态切换、事件触发、失败处理；子类实现 `xxxInternal`（线程池、端口、映射等具体动作）。父组件的 `startInternal()` 负责启动子组件。

**Q4. 举两个事件驱动的联动实例。**
> 参考答案：MapperListener 监听容器事件维护路由表（L05）；HostConfig 监听 Host 事件部署 webapps（独立形态）。

**Q5. 启动/停止顺序分别是什么？为什么 Connector 的位置特殊？**
> 参考答案：启动 Server→Service→Engine→Host→Context，Connector 最后（容器先就绪、端口后监听）；停止**不是简单反序**——先让 Connector 停流量（关监听 socket、pause），再停容器，最后才彻底停止 Connector 本身（先切外部流量、再拆内部）。

---

## 9. 本课状态与下一步

- 状态：✅ 已讲（2026-09-29）
- **Stage 1（概念）完成小结**：L01 组件模型 → L02 启动与装配 → L03 线程模型 → L04 请求全链 → L05 路由 → L06 生命周期。
- 下一步：**L07 优雅停机**（Stage 2 机制开始）——停机时如何"停流量、等在途请求、再拆内部"。
