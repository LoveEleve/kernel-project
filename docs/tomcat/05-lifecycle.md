# 面试题详解 05 | Lifecycle 生命周期与容器启停

> 难度：★★★☆☆ ｜ 频率：★★★★☆ ｜ 区分度：中（模板方法 + 状态机 + 启停顺序）
> 关键词：Lifecycle、LifecycleBase、LifecycleState、LifecycleEvent、LifecycleListener、startInternal、STARTING 约定、MapperListener、WebServerStartStopLifecycle

---

## 问题

**Tomcat 几十个组件（Server/Service/Engine/Host/Context/Wrapper/Connector/ProtocolHandler/Endpoint）的启停顺序怎么保证？`LifecycleBase` 的模板方法与状态机是怎么设计的？为什么子类必须在 `startInternal()` 里 `setState(STARTING)`？内嵌场景里 Spring Boot 怎么接管 WebServer 的启停？**

---

## 30 秒简洁回答

> Tomcat 用 `Lifecycle` 接口 + `LifecycleBase` 抽象类统一所有组件的生命周期：`init()/start()/stop()/destroy()` 都是 **`final synchronized`**（骨架不可改、不可并发），内部调子类实现的 `xxxInternal()`（**模板方法**）；状态机是 `LifecycleState`（`NEW→INITIALIZED→STARTING_PREP→STARTING→STARTED→…→DESTROYED`，失败进 `FAILED`），每次状态变更触发对应 `LifecycleEvent`（`before_start/start/after_start…`），`LifecycleListener`（如 `MapperListener`）据此联动。**启停顺序由容器代码控制**：Server→Service→Engine→Host→Context 先就绪，最后才启动 Connector（内嵌场景 Spring Boot 还用 `removeServiceConnectors`/`addPreviouslyRemovedConnectors` 控制端口绑定时机，见 01 篇）；WebServer 在 Spring 里的启停由 `WebServerStartStopLifecycle`（`SmartLifecycle`）挂接。

---

## 展开回答（高级开发级）

### 1）Lifecycle 接口：状态 + 事件

**事件常量**（`Lifecycle.java`）：`BEFORE_INIT :90 / AFTER_INIT :96 / START :102 / BEFORE_START :108 / AFTER_START :114 / STOP :120 / BEFORE_STOP :126 / AFTER_STOP :132 / BEFORE_DESTROY :144 / AFTER_DESTROY :138 / CONFIGURE_START :158`。

**状态枚举**（`LifecycleState.java:24-35`）——每个状态带"可用标志 + 对应事件"：

```
NEW / INITIALIZING / INITIALIZED / STARTING_PREP / STARTING / STARTED
STOPPING_PREP / STOPPING / STOPPED / DESTROYING / DESTROYED / FAILED
```

### 2）LifecycleBase：模板方法 + 状态检查

```
骨架（final synchronized，不可改不可并发）：
  init()    :115      start()   :139      stop()    :197      destroy()  :266
子类实现（abstract）：
  initInternal() :135  startInternal() :193  stopInternal() :262  destroyInternal() :311
```

`start()` 的流程（`:139-180`）：

```java
if (state in {STARTING_PREP, STARTING, STARTED}) return;   // 防重复启动
if (state == NEW) init();                                  // 未初始化先 init
else if (state == FAILED) stop();                          // 失败过先清理
else if (state not in {INITIALIZED, STOPPED}) invalidTransition(BEFORE_START_EVENT);
setStateInternal(STARTING_PREP, null, false);              // :163
startInternal();                                           // :164 子类干活
if (state == FAILED) stop();                               // 受控失败 → 清理
else if (state != STARTING) invalidTransition(AFTER_START_EVENT);  // 子类没遵守约定
else setStateInternal(STARTED, null, false);               // 正常完成
```

**STARTING 约定**（`startInternal` 的 javadoc，`:183-192` 原文）："Sub-classes must ensure that the state is changed to `LifecycleState.STARTING` during the execution of this method."——子类在启动过程中的合适时机自己置 `STARTING`（触发 `START` 事件）；失败可以抛异常（父组件一起失败）或自置 `FAILED`（父组件继续启动别的组件）。

事件与状态设置：`fireLifecycleEvent :106`、`setStateInternal :355`（内含状态转换合法性校验）。

### 3）启停顺序：由容器代码控制

```
StandardServer.startInternal（:855）
  · fire CONFIGURE_START_EVENT → setState(STARTING) → 启动各 Service

StandardService.startInternal（:406）
  · :415 engine.start()           容器树（Engine→Host→Context）先就绪
  · :419 executor.start()
  · :422 mapperListener.start()   路由树建好（04 篇）
  · :428 connector.start()        “Start our defined Connectors second” ← 最后才监听端口
```

内嵌场景还多一层：`TomcatWebServer.initialize` 在 Service 启动前 `removeServiceConnectors()`（`TomcatWebServer:117-122`），容器就绪后再 `addPreviouslyRemovedConnectors()`（`:236`）——保证"容器先就绪、端口后监听"（见 01 篇）。

### 4）LifecycleListener：联动机制

- **MapperListener**：由 `StandardService` 持有（`StandardService.java:103`），随 Service 启动（`:422`），监听 Host/Context/Wrapper 事件建路由树（04 篇）——这是"Lifecycle 事件驱动联动"的标准例子；
- 其他：`AprLifecycleListener`、`JreMemoryLeakPreventionListener` 等；
- 自定义扩展：实现 `LifecycleListener` 并 `addLifecycleListener(...)`，就能观察/介入组件启停。

### 5）内嵌：Spring Boot 怎么接管 WebServer 的启停

```
Tomcat 侧：Tomcat.start()（Tomcat.java:435）→ getServer().start()
          Tomcat.stop()（:445）/ destroy()（:456）
Spring 侧：WebServerStartStopLifecycle implements SmartLifecycle（Boot :29）
          · start() :43-44 → webServer.start()
          · stop()  :51-53 → webServer.stop()
```

两层分工：**Tomcat 的 Lifecycle 管"容器树内部"的启停顺序；Spring 的 `SmartLifecycle` 管"WebServer 在 Spring 上下文生命周期中的启停时机"**（停止时与优雅停机衔接，见 07 篇）。

### 6）状态机一张图

```
NEW →(init)→ INITIALIZED →(start)→ STARTING_PREP → STARTING → STARTED
                                          ↑ 子类必须在 startInternal 里置 STARTING
受控失败 → FAILED（父组件继续）；非受控失败 → 抛 LifecycleException（父组件也失败）
STARTED →(stop)→ STOPPING_PREP → STOPPING → STOPPED →(destroy)→ DESTROYING → DESTROYED
```

---

## 进一步回答（架构师层级）

- **为什么用"模板方法 + 状态机"**：组件多、依赖链长（容器先于协议），统一骨架避免"每个组件手写启停逻辑"；状态检查天然防"重复启动/非法转换/并发启停"。
- **为什么 STARTING 要子类自己置**：`STARTING` 表示"正在启动中"——子类最清楚"何时算进入启动关键段"（如端口绑定前后），由它触发 `START` 事件，监听器才能看到正确的中间状态。
- **内嵌与独立共用同一套 Lifecycle**：差异只在"谁建容器树"（代码装配 vs digester 解析 server.xml），启停机制完全一致。
- **与优雅停机的衔接**：`STOPPING_PREP/STOPPING` 阶段是"停流量、等在途请求"的窗口（07 篇）。
- **排障线索**：启动失败看 `FAILED` 与 `LifecycleException` 链；`lifecycleBase.alreadyStarted` 日志=重复启动；`invalidTransition`=子类没遵守约定或调用顺序错。

---

## 理解与复述提示（学习使用，面试时不要直接念）

- **本质**：这道题考"Tomcat 怎么用一个统一骨架管理几十个组件的启停"；
- **回答顺序**：接口（状态+事件）→ LifecycleBase（模板方法+STARTING 约定）→ 启停顺序（容器先、Connector 后）→ LifecycleListener 联动 → Spring 侧 SmartLifecycle；
- **一句话收束**："final 骨架 + abstract 内部实现 + 状态机 + 事件，容器代码按依赖顺序调用 start/stop；Spring Boot 再用 SmartLifecycle 把 WebServer 挂进自己的生命周期。"

## 必记关键词

`Lifecycle` ｜ `LifecycleBase` ｜ `LifecycleState` ｜ `LifecycleEvent` ｜ `LifecycleListener` ｜ `initInternal/startInternal/stopInternal/destroyInternal` ｜ `STARTING 约定` ｜ `MapperListener` ｜ `WebServerStartStopLifecycle` ｜ `SmartLifecycle`

## 必须明确的边界

- `LifecycleBase` 的四个公开方法是 **final synchronized**——骨架不能改、不能并发调用；
- `STARTING` 不是父类代设的，是**子类在 startInternal 里的义务**；不遵守会 `invalidTransition`；
- 启停顺序**不是自动推导**的，是容器代码（StandardServer/StandardService）显式控制的；
- Tomcat 的 Lifecycle 与 Spring 的 SmartLifecycle 是**两套**（各管一层），不要混；
- 内嵌与独立共用 Lifecycle，差异在建树方式。

## 常见错误

- 以为 start() 会递归启动子组件（实际由子类 startInternal 里显式启动自己的孩子）；
- 不知道 STARTING 是子类的义务；
- 把 Tomcat Lifecycle 与 Spring SmartLifecycle 混为一谈；
- 说不清"Connector 为什么最后启动"（端口后绑定，见 01）；
- 以为 FAILED 一律让父组件失败（受控失败父组件会继续）。

## 自测要求

- **30 秒**：接口（状态+事件）+ 模板方法 + 启停顺序 + Spring 侧挂接；
- **3 分钟**：能画出状态机与 start() 的分支流程（含 STARTING 约定）；
- 能回答追问 1~3。

---

## 追问与参考回答

**追问 1：为什么 `LifecycleBase` 的骨架方法是 `final synchronized`？**
> `final` 保证骨架不被子类改写（子类只能填 `xxxInternal`）；`synchronized` 保证同一个组件不会被并发 init/start/stop（配合状态检查，天然防重复启动与非法转换）。

**追问 2：为什么子类必须在 `startInternal()` 里 `setState(STARTING)`？**
> `STARTING` 表示"正在启动中"：子类最清楚启动关键段何时开始（如端口绑定/资源初始化），由它触发 `START` 事件，监听器（如 MapperListener）才能在正确时机做联动；不遵守会被父类 `invalidTransition(AFTER_START_EVENT)` 抓住。

**追问 3：内嵌场景 WebServer 的启停由谁触发？**
> Spring 的 `WebServerStartStopLifecycle`（`SmartLifecycle`）：`start() :43-44` 调 `webServer.start()`、`stop() :51-53` 调 `webServer.stop()`；而 WebServer 内部再走 Tomcat 自己的 Lifecycle（`Tomcat.start():435` → `Server.start()`）。

---

## 示例与使用说明

```text
观察事件顺序（日志/断点）：
  LifecycleBase.start()        （:139，所有组件的统一入口）
  StandardService.startInternal（:406，看 engine→executor→mapperListener→connector 顺序）
  grep 日志 "Starting|Stopping" 可看到组件按依赖顺序启停
自定义联动：
  实现 LifecycleListener 并 addLifecycleListener(...)，即可观察/介入组件启停
```

## 面试官评分点

**通过标准**
- 能讲清模板方法 + 状态机 + 事件三件套；
- 能讲 STARTING 约定与 start() 的分支；
- 能讲"容器先、Connector 后"的顺序及 Spring 侧挂接。

**加分项**
- 能带行号讲 `start()` 的状态检查（:139-180）与 `StandardService` 的启动顺序（:415/:419/:422/:428）；
- 能区分 Tomcat Lifecycle 与 Spring SmartLifecycle；
- 能解释受控失败 vs 非受控失败。

**危险信号**
- 以为 start() 自动递归启动子树；
- 不知道 STARTING 是子类义务；
- 两层 Lifecycle 混淆。

## 实战练习

1. 加日志/断点跟一次启动：观察 `LifecycleBase.start` 被各组件依次调用的顺序；
2. 写一个 `LifecycleListener` 打印事件类型，核对状态机（before_start→start→after_start…）；
3. 故意让一个 Context 启动抛异常，观察 FAILED 传播与父组件行为。

## 版本与来源

- Tomcat **10.1.34**：`java/org/apache/catalina/{Lifecycle,LifecycleState}.java`、`java/org/apache/catalina/util/LifecycleBase.java`、`java/org/apache/catalina/core/{StandardServer,StandardService}.java`、`java/org/apache/catalina/startup/Tomcat.java`；
- Spring Boot **3.5.16**：`WebServerStartStopLifecycle.java`；
- 本卷章节：09（启动与装配）、14（Spring Boot 桥）、01（请求旅程）；
- 规划：`Tomcat源码学习范围规划.md` T-1（启动与装配闭环）。

## 个人经历填充区（留空，按真实项目补）

- 排查过启动失败/停不干净的问题吗（FAILED、端口占用、Context 未关）？
- 自定义过 LifecycleListener 或调整过启动顺序吗？

## 真实性说明

技术结论与源码引用可复现（Tomcat 10.1.34 + Spring Boot 3.5.16）；示例为操作路径（未附真实运行输出）；项目经历请用真实案例填充。
