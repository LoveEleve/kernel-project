# Tomcat 学习课 · 审查记录

> 每课写完后做一轮深度 review：① 官方原文逐字核对；② 事实主张验证（本地文档树/源码/实测）；③ 与 gateway 教学规范的风格一致性；④ 与 `学习清单.md` 规则的自洽性。
> 修正项直接应用并记录在此。

---

## L01 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字比对：`webapps/docs/index.xml`、`architecture/overview.xml`、`config/valve.xml`、API 文档 `Wrapper`；
- 事实主张验证：Tomcat/Boot 侧默认行为（DispatcherServlet 注册位置、DefaultServlet 注册开关、Service 数量、运行版本）；
- 规范一致性：与 `gateway` 教学结构对比（三层阅读约定 / 官方原文 / 术语对照 / Self-Test 参考答案 / 编号提问）；
- 规则自洽：`学习清单` 硬规矩与 L01 实际内容是否冲突。

### 修正项（6 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | `index.xml` 原文中版本号是占位符 `<version-major-minor/>`，直接写 10.1 属"改字" | 加"引用说明"：占位符代入说明，其余逐字 |
| 2 | "10.0 = Servlet 5.0、9.0 = Servlet 4.0" 超出本地证据范围（10.1 文档树只覆盖 6.0） | 标注为"公开版本对应（本地未覆盖，仅作参照）" |
| 3 | "内嵌实况：只有一个 Wrapper" 绝对化（注册其它 Servlet 会新增 Wrapper） | 改为"**默认**只有一个" |
| 4 | "静态资源也由 Spring MVC 处理" 不精确；DEFAULT 匹配条件漏了"扩展名" | 精确为 `ResourceHttpRequestHandler`；匹配条件改为"精确/前缀/扩展名"（含 Q4 答案同步） |
| 5 | `学习清单` 硬规矩"概念课不出现源码与行号"与 L01 含类名（术语）冲突 | 规则改为："不给源码阅读任务、不出现行号；允许最小片段与类名（作为术语）" |
| 6 | `README.md` 未反映"00 已被 L01 取代"的关系 | README 学习入口更新：指向 `学习清单 + lessons/`，00 标注"仅作对照" |

### 核对无误（逐字抽查）

- Server / Service / Engine / Host / Connector / Context / Valve / Wrapper 的官方引用与原文一致（节选处已用 `...` 或"节选"标注）；
- 组件树关系（Service 绑 Connector+Engine；Engine→Host→Context→Wrapper）；
- 请求路径（Connector→Engine→Host→Context→Wrapper→Filter→Servlet）与 03 篇/lab 口径一致；
- 内嵌默认事实：DispatcherServlet 注册 `/`（`spring.mvc.servlet.path` 默认 `/`）；`register-default-servlet` 默认 false；Service 默认 1 个；运行口径 Boot 3.2.5 = Tomcat 10.1.20。

### 结论

**L01 Round 1 通过**（无阻断级；6 项修正已应用）。下一步：用户确认风格后写 L02。

---

## L02 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字：`webapps/docs/architecture/startup.xml`（两段引用）；
- 事实核对：`conf/server.xml` 模板的 Server 端口值、官方文档对 shutdown 端口的描述（`config/server.xml`）、Boot 启动日志的文本与语义位置、路由表建立时点（Service 启动序列 + MapperListener 行为）、内嵌 Connector 先摘后启的顺序细节。

### 修正项（4 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | "默认 8005 端口"表述不严：官方文档未给默认值，8005 是 **`conf/server.xml` 模板值** | 改为"`conf/server.xml` 模板默认 8005；可用 `-1` 禁用" |
| 2 | 路由表时点写"Context 启动期间"不够准确：MapperListener 在 Service 启动序列中位于**容器之后、Connector 之前**，启动时扫描已存在的容器树 | 改为"容器就绪后、Connector 之前建立"（§1、§4、Q3 三处同步） |
| 3 | 启动日志语义含糊：应说明 `Tomcat initialized with port ...` 记录于 `tomcat.start()` **之前**（端口已配置、尚未监听） | 补精确注释 |
| 4 | （同上：日志文本为拼接式，非整串字符串，确认存在） | 已在 §4 修正注释 |

### 核对无误

- startup.xml 两段引用逐字（含原文笔误 "and the starts the Server"，照录）；
- 官方明确列出 "From a Java program as an embedded server" 为启动方式之一；
- 内嵌无 shutdown 端口、由 Spring 生命周期管理（与 L07/旧 07 篇口径一致）；
- Boot 的"先摘 Connector、容器就绪后再启动"顺序细节与源码一致；
- 日志文本 `Tomcat initialized with port ...` / `Tomcat started on port ...` 在 Boot 源码中存在（拼接式构造）。

### 结论

**L02 Round 1 通过**（无阻断级；4 项修正已应用）。下一步：L03 线程模型。

---

## L03 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字：`webapps/docs/config/http.xml`（protocol / acceptorThreadPriority / pollerThreadPriority / selectorTimeout / maxThreads / maxConnections / acceptCount 共 7 处）；
- 源码复核："多 Acceptor/Poller 配置项是否存在"（全树搜索）、Acceptor 实例数量；
- 事实核对：线程名（lab 实测）、`container-N` 驻留线程、Netty EventLoop 默认值对照。

### 修正项（3 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | `pollerThreadPriority` / `selectorTimeout` 引文写成 "(int) ..."（原文为 "(int)..."，`(int)` 后无空格） | 逐字修正 |
| 2 | §2 缺"节选 + 引用规范"说明 | 补充引用规范（逐字、不改字） |
| 3 | `acceptCount` 漏官方补充说明（"OS 可能忽略该设置、使用不同大小队列"） | 在行为表补注 |

### 核对无误

- 7 处官方引用与原文一致（节选均已标注）；
- **负向断言（有据）**：`acceptorThreadCount` / `pollerThreadCount` 在 10.1.34 全源码树 0 命中；Acceptor 为单实例创建——正文"默认 1 Acceptor + 1 Poller，配置项不存在"成立；
- `maxThreads` 官方补充（关联 executor 时被忽略）与 L15 虚拟线程口径衔接；
- `selectorTimeout=1000ms` 与 L08 异步超时轮询口径衔接；
- Netty 对照（EventLoop 默认 ≈ 核数×2）与既有 Netty 面试系列一致。

### 结论

**L03 Round 1 通过**（无阻断级；3 项修正已应用）。下一步：L04 请求处理全链。

---

## L04 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字：`config/valve.xml`（Valve 定义、Error Report Valve）、`config/filter.xml`（Filter 配置层级）；
- 关键主张复核：**ErrorReportValve 是否默认装配**（`StandardHost` 默认类名字段 + `startInternal` 自动添加逻辑）；内嵌侧 Boot 是否介入（`TomcatWebServerFactoryCustomizer.customizeErrorReportValve`）；
- 与既有 lab/实测口径一致性：Filter 链构建时机、dispatcher type 默认值、短路与响应头提交时机语义。

### 修正项（1 处补充）

| # | 问题 | 修正 |
|---|---|---|
| 1 | 未说明内嵌侧 Boot 对 ErrorReportValve 的定制（`server.error.*`） | §2.3 补"内嵌细节"：`include-stacktrace=never` 时向 Host 管道追加关闭 showReport/showServerInfo 的实例 |

### 核对无误

- 3 处官方引用逐字（Valve 定义、Filter 配置、ErrorReportValve 描述）；
- "Valve 可配置层 = Engine/Host/Context"与官方一致；
- **ErrorReportValve 默认装配成立**：`StandardHost` 的 `errorReportValveClass` 字段默认即 `ErrorReportValve`，`startInternal` 中自动加入 Host 管道（不依赖 server.xml；内嵌同样生效）；
- 四层链、Valve/Filter 边界、Filter 链"按 dispatch 构建 + 短路 + 响应头提交时机"与既有 lab 实测（`FilterLabTest`）一致。

### 结论

**L04 Round 1 通过**（无阻断级；1 项补充已应用）。下一步：L05 路由（Mapper）。

---

## L04 Round 2（2026-09-29，深度轮 · 全文通读）

> 触发：用户要求"先深度 review 一轮"。方法：全文逐段通读 + 悬空引用检查 + 表述精度检查（Round 1 为重事实核对，本轮侧重"读者能否按字面正确理解"）。

### 修正项（6 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | §1.1 / Q1 把"基本 Valve 交给下一层容器"套到 Wrapper 层——Wrapper 之后是 Filter/Servlet，不是容器 | 改为"外层容器→下一层容器；Wrapper→Filter/Servlet"（两处同步） |
| 2 | §3 图注"跨层来源见 L01/L03"是**悬空引用**（两课均未讲适配/跨层） | 修改为独立的一句话说明（连接器解析 + 适配交接，细节留 Stage 3） |
| 3 | "Host 默认含 ErrorReportValve"属源码口径，未标注 | §3 加脚注：默认装配行为来自源码核对，细节留 Stage 3 |
| 4 | §4 "应用能否绕过：Filter=由应用自己注册与管理"答非所问 | 改为"应用自行注册/管理（可控）" |
| 5 | §5.1 "按请求构建（更准确地说是每次 dispatch）"表述含混 | 改为"每次 dispatch 重新匹配并组装链内容；链对象可能复用" |
| 6 | §5.2 "dispatcher type + URL + 或 Servlet 名"读起来是三者并列 | 改为"先按 dispatcher type 过滤，再按 URL 模式或 Servlet 名匹配" |

### 结论

**L04 Round 2 通过**（字面理解与引用完整性已校准；6 项修正已应用）。下一步：L05 路由（Mapper）。

---

## L05 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字：`config/context.xml`（path 属性）、`config/host.xml`（name 属性）；
- 规范 API 核对：`jakarta.servlet.http.MappingMatch` 枚举值；
- 关键主张复核：Context 最长前缀、`""`/`/` 兜底语义、路由表"预建+事件维护"、404 两来源、两层路由边界；
- Boot 侧默认值：`spring.mvc.servlet.path`、`server.servlet.context-path`、`server.servlet.register-default-servlet`。

### 修正项（2 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | "Context 最长前缀"写在机制说明里，易被误认为官方原文所述 | 标注出处：规范/Tomcat 实现行为，官方段落未展开（Stage 3 展开） |
| 2 | 内嵌默认落点缺 Boot 配置项名（读者无法自行核对） | §6 补 `spring.mvc.servlet.path` / `server.servlet.context-path` / `server.servlet.register-default-servlet` |

### 核对无误

- context.xml / host.xml 两处引用逐字（含"matched against the beginning"、"convert it to lower case"、"defaultHost"）；
- `MappingMatch` 枚举值 CONTEXT_ROOT/DEFAULT/EXACT/EXTENSION/PATH 与规范 API 一致；
- "最长 PATH 优先"与 04 篇深审结论一致；`""`（默认应用）与 `/`（DEFAULT）兜底语义类比成立；
- 404 两来源、两层路由边界与 03 篇/lab 口径一致；
- 路由表维护方式（预建 + 事件驱动增量更新 + 请求期只查表）与 04 篇"不可变数组替换"结论一致。

### 结论

**L05 Round 1 通过**（无阻断级；2 项精确化已应用）。下一步：L06 生命周期。

---

## L05 Round 2（2026-09-29，深度轮 · 全文通读）

> 触发：用户要求"先深度 review 一轮"。方法：全文逐段通读 + "读者视角"检查（本课承诺的教学目标是否在正文兑现）+ 例子完整性。

### 修正项（4 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | **全文没有出现 "Mapper / MapperListener" 这两个主角词**（课名与课程索引都指向它们，§5 只写"路由表"） | §5 补"由 **Mapper** 持有、**MapperListener** 维护"（并注明 L06 会讲事件机制）；§7 术语表补两行 |
| 2 | §3 缺具体 URI 走查例子（概念课"例子先行"原则） | 补 `http://localhost:8080/api/v2/users` 的三层走查示例 |
| 3 | §5 "启动期"只写"容器就绪时登记"，未点出是 MapperListener 的行为 | 并入修正 1，改为"MapperListener 就绪时登记" |
| 4 | §6 嵌套列表格式不一致（子项以 `；` 结尾） | 合并为单条，格式统一 |

### 核对无误

- 官方引文 2 处 + 规范枚举（Round 1 已逐字核对），本轮无新增引文问题；
- 三层路由/四种映射/404 两来源/两层路由边界与 L01/L04 口径一致；
- 悬空引用检查：本课对 L01/L02/Stage 3 的引用均指向确有该内容的位置（L01 讲 `/` 默认落点、L02 讲启动顺序、Stage 3 为源码阶段）。

### 结论

**L05 Round 2 通过**（"主角词缺失"这一关键教学问题已修复；4 项修正已应用）。下一步：L06 生命周期。

---

## L06 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字：`config/listeners.xml`（Listener 定义 + LifecycleListener 接口）、`architecture/startup.xml`（可注册自定义监听器）；
- **停止顺序源码复核**（本课最大风险点）：`StandardService.stopInternal` 全文；
- 状态/事件与失败语义复核：既有 05/07 篇结论。

### 修正项（1 处实质性错误，已修正）

| # | 问题 | 修正 |
|---|---|---|
| 1 | §6 写"停止：反向（先 Connector 后容器）"——**错误**。源码实际顺序：① `closeServerSocketGraceful`（关监听 socket）→ ② `connector.pause()`（停流量）→ ③ `engine.stop()`（停容器）→ ④ `connector.stop()`（彻底停连接器）→ ⑤ mapperListener/executor | 改为"不是简单反序：先停流量 → 再停容器 → 最后彻底停 Connector"（§6 与 Q5 同步） |

### 核对无误

- 3 处官方引用逐字（Listener 定义 / LifecycleListener 接口 / 自定义监听器可注册）；
- 状态与事件映射（init/start/stop/destroy + configure_start）与既有结论一致；
- 受控失败 vs 非受控失败、重复启动拦截语义与 05 篇一致；
- 事件驱动联动三例（MapperListener / HostConfig / 自定义）与 L05 衔接正确。

### 结论

**L06 Round 1 通过**（无阻断级；1 处停止顺序实质错误已修正）。**Stage 1（概念课 L01~L06）全部完成**。下一步：L07 优雅停机（Stage 2 开始；本轮发现的"Service 级停流量顺序"将在 L07 与 Boot 级优雅停机做区分）。

---

## L06 Round 2（2026-09-29，深度轮 · 全文通读 + 跨课一致性）

> 触发：用户要求"先深度 review 一轮"。方法：全文逐段通读找**内部矛盾** + 跨课文件/索引一致性核对。

### 修正项（4 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | **自相矛盾（关键）**：§4 仍写"停止则反向"，与 Round 1 修正后的 §6"不是简单反序"冲突 | §4 改为"停止同样由父组件触发子组件停止，但 Connector 停止顺序特殊（见 §6）" |
| 2 | §3 事件表把"配置（configure_start）"排在最后，逻辑顺序倒置 | 移到第一行（启动流程最前） |
| 3 | §3 失败语义（受控/非受控）为源码口径，未标注 | 补"源码口径，Stage 3 展开" |
| 4 | §6 停止顺序为源码口径，未标注 | 补标注 |

### 跨课一致性核对（通过）

- lessons 实际文件 6 个与 `学习清单` L01~L06 逐行文件名一致；
- L05→L06 引用兑现：L05"MapperListener（L06 会讲事件机制）"在本课 §2/§3/§5 已展开；
- L02↔L06 启停顺序口径一致；L04/L05 的"源码口径 Stage 3"标注惯例在本课补齐。

### 结论

**L06 Round 2 通过**（内部矛盾已消除）。Stage 1（L01~L06）全部通过深度审查。下一步：L07 优雅停机。

---

## L07 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字：`config/service.xml`（`gracefulStopAwaitMillis`）；Boot 源码 javadoc（`server.shutdown`、`timeout-per-shutdown-phase`）；
- 源码复核：Boot 优雅停机链路与 phase 顺序、在途计数两来源、超时语义、**Boot 是否满足 Tomcat 原生前提（bindOnInit=false）**；
- lab 数据对照：`GracefulShutdownTest` / `graceful-shutdown-demo.sh` 既有实测；
- 格式检查：§4 代码块闭合、文件尾部完整。

### 修正项（1 处机制连接点补充）

| # | 问题 | 修正 |
|---|---|---|
| 1 | 讲了"Tomcat 原生只对 `bindOnInit=false` 生效"，但没讲**内嵌为什么能生效** | §4 补"两套机制的连接点"：Boot 的 `TomcatWebServer.disableBindOnInit()` 自动把连接器设为 `bindOnInit=false` |

### 核对无误

- service.xml 引文逐字（"finish processing and close"、"bindOnInit=false 非默认"、"默认 0"）；
- Boot 两条 javadoc 引文准确（3.5 默认 GRACEFUL、phase 超时默认 30s）；
- Boot 链路（graceful phase 先于 start-stop phase；pause + closeServerSocketGraceful + 轮询计数 → webServer.stop）与 07 篇/lab 一致；
- lab 数字（graceful ≈3s、immediate 45~103ms、脚本 4970/103ms）与既有实测一致；
- 格式：代码块闭合、结尾完整。

### 结论

**L07 Round 1 通过**（无阻断级；1 处机制连接点已补齐）。下一步：L08 异步 Servlet。

---

## L08 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字：`config/http.xml`（asyncTimeout）、Servlet API（AsyncContext）、Spring `WebAsyncManager` javadoc、Boot `WebMvcProperties.Async` javadoc；
- lab 数据比对：`AsyncServletTest` 四组实测（deferred/callable/raw 超时接管与不接管）；
- 格式检查：代码块 fence 偶数；错误页归属（内嵌 vs 独立）实证核对。

### 修正项（2 处精度，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | "未处理超时 → 500（ErrorReportValve）"对**内嵌不准确**：lab 实测返回的是 Boot 错误页 JSON（`/error`），不是 ErrorReportValve 的 HTML | 拆分为"独立=ErrorReportValve / 内嵌=Boot `/error`" |
| 2 | T3（async dispatch）阶段的计数语义含糊 | 明确：该次 dispatch `countAllocated` 重新计入；`inProgressAsyncCount` 在进入"已 dispatch"态时已减 1 |

### 核对无误

- 4 处引文与原文一致（30s 规范默认、AsyncContext 定位、T1/T2/T3、request-timeout 默认取底层实现）；
- "超时检查约每秒一次"已标注源码口径（Stage 3）；
- lab 四组数据（2004/2011/1031/980ms、200/500）与实测一致；
- §6 异步与停机联动与 L07 口径一致；503 vs 500 的边界注解保留正确。

### 结论

**L08 Round 1 通过**（无阻断级；2 处精度已修正）。下一步：L09 调参。

---

## L08 Round 2（2026-09-29，深度轮 · 全文通读 + 术语歧义检查）

> 触发：用户要求"先深度 review 一轮"。方法：全文逐段通读 + **跨课术语一致性**（本课出现的"拦截器"与 L04 定义的 MVC Interceptor 是否冲突）+ 字面精度。

### 修正项（4 类共 5 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | "Worker **立刻**归还"不严谨：`startAsync()` 只是标记异步，容器线程在**业务方法返回后**才归还 | §1.1 / §3 图 / Q1 三处同步修正 |
| 2 | **术语歧义**："拦截器可提供超时结果"易与 L04 的 MVC `Interceptor` 混淆 | 明确为 `CallableProcessingInterceptor`（Spring 异步拦截器，**≠ MVC Interceptor**）；§5 表与 Q4 同步 |
| 3 | Q2 "（或已归零）"含糊 | 明确"容器线程退出时 deallocate 已归零" |
| 4 | `WebAsyncManager` 引文方法签名从略未说明 | 加"引用说明：javadoc 渲染为短名，签名从略" |

### 跨课一致性核对（通过）

- L04 确立的"Interceptor = Spring MVC、在 DispatcherServlet 之后"与本课的 Spring 异步拦截器已明确区分；
- L07 计数口径（countAllocated/inProgressAsyncCount）与 L08 §4/§6 一致；
- L03"整条链在 Worker 上"与 L08 的"归还点"表述一致。

### 结论

**L08 Round 2 通过**（术语歧义已消除；4 类修正已应用）。下一步：L09 调参。

---

## L09 Round 1（2026-09-29，深度 review）

### 核对方法

- 官方原文逐字：`config/http.xml`（minSpareThreads / maxQueueSize / connectionTimeout / keepAliveTimeout / maxKeepAliveRequests；maxThreads / maxConnections / acceptCount 回指 L03）；
- **"双默认值"闭环核实**：官方 connectionTimeout 描述（"default 60000 ... but standard server.xml sets this to 20000"）与本地 `conf/server.xml`（两个 Connector 均 `connectionTimeout="20000"`）对照；
- lab 数据比对：`TuningTest`（1811ms / 1707ms / busy 4.0→1.0）与 `close-wait-demo.sh`（CLOSE_WAIT 1→0）；
- 格式检查：fence 偶数；引文省略号规范。

### 修正项（2 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | `keepAliveTimeout` 引文尾部省略未标注 | 补 `...` |
| 2 | §3 表头写 "Boot 属性（`server.tomcat.*`）"，但 `asyncTimeout` 实际是 `spring.mvc.async.request-timeout` | 表头改为"线程/连接/超时类均为 `server.tomcat.*`；异步超时例外" |

### 核对无误

- 5 处官方引文与原文一致（含 60000/server.xml 20000 的原话与本地文件对照）；
- 内嵌默认 60s 的推导正确（Boot 不读 server.xml、默认不配 connection-timeout）；
- lab 四组数据与既有实测一致；CLOSE_WAIT 占名额与 L07/旧 09 篇口径一致；
- 队列无界 → "变慢不报错"、1/-1 的 maxKeepAliveRequests 语义均与官方一致。

### 结论

**L09 Round 1 通过**（无阻断级；2 处修正已应用）。**Stage 2（机制课 L07~L09）全部完成**。下一步：L10 Connector / Endpoint 主干（Stage 3 开始）。

---

## L09 Round 2（2026-09-29，深度轮 · 全文通读）

> 触发：用户要求"先深度 review 一轮"。方法：全文逐段通读 + **易误读点检查**（图示顺序、备注措辞）。

### 修正项（3 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | `maxConnections=-1` 的官方语义（NIO 下禁用限制）缺失 | §2.2 补官方原句（"will disable the maxConnections feature and connections will not be counted"） |
| 2 | §4 图"OS 队列 → 连接闸门"顺序**易误读**为流水线（实际是"闸门满 → 连接堆在 OS 队列"） | 补"顺序说明"：闸门等待发生在 accept 之前，OS 队列是等待位置，不是前置流水线 |
| 3 | §6 lab 踩坑"（属性后注册）"措辞过简 | 精确为"Boot 的属性驱动 customizer 后注册，会覆盖先注册的同名自定义 customizer" |

### 核对无误

- 5 处引文与原文一致（含新补的 maxConnections 末段）；
- 默认值表与 Q1 答案逐项一致；lab 数据与既有实测一致；
- 跨课口径（L03 连接/线程、L07 停机、L08 异步超时）无冲突。

### 结论

**L09 Round 2 通过**（易误读点已消除）。Stage 2（L07~L09）全部通过深度审查。下一步：L10 Connector / Endpoint 主干。

---

## L10 Round 1（2026-09-29，深度 review · Stage 3 首课）

### 核对方法

- 类名链逐段核对：`ProtocolHandler.create` 映射（"HTTP/1.1" → `Http11NioProtocol`）、`Http11NioProtocol` 构造（`new NioEndpoint()`）、`Endpoint.start`（未绑定则 `bindWithCleanup` → `BOUND_ON_START` → `startInternal`）、`NioEndpoint.startInternal` 顺序（缓存 → `createExecutor` → 闸门 → Poller → Acceptor）、`Poller.register/events/processKey`、`SocketProcessorBase.run→doRun`、`ConnectionHandler.process`、Acceptor 单实例创建；
- 官方原文逐字：`config/http.xml` Introduction（连接器定义）；
- 交叉引用检查：指向 L02/L03/L04/L07/L09 的引用均有对应内容。

### 修正项（0 项——本轮为零修正通过）

- 写作阶段已按核实结果成文：bind 位置（`Endpoint.start`，与 Boot `bindOnInit=false` 自洽）、Nio 构造链、Poller/Acceptor 单实例。

### 核对无误

- 官方引文 1 处与原文一致（"listens for connections on a specific TCP port … forwarding to the associated Engine"）；
- 类名链：`Connector("HTTP/1.1")` → `ProtocolHandler.create()` → `Http11NioProtocol(new NioEndpoint())` → `bind` → `createExecutor/Poller/Acceptor` → `Poller.register/events/processKey` → `processSocket(OPEN_READ, dispatch=true)` → `SocketProcessor.doRun` → `ConnectionHandler.process` → `Http11Processor.service`；
- `bindOnInit=false → BOUND_ON_START` 与 L07 的 `closeServerSocketGraceful` 前提衔接；
- Q4（bindOnInit）与 L07 口径一致；格式（fence 偶数）正常。

### 结论

**L10 Round 1 通过**（零修正）。下一步：L11 Mapper 匹配主干。

---

## L11 Round 1（2026-09-29，深度 review · Stage 3 第二节）

### 核对方法

- 类名/方法名逐段核对：`Mapper.map`（2 重载）、`internalMap`、`internalMapWrapper`、`internalMapExactWrapper`、`internalMapWildcardWrapper`、`internalMapExtensionWrapper`、`addHost`/`addHostAlias`/`addContextVersion`/`addWrappers`、`removeHost`、`MapperListener.lifecycleEvent/register*/unregister*`、`MappingData` 字段集、`jakarta.servlet.http.MappingMatch` 枚举；
- 避免 04 篇的已知坑：不引用有歧义的 Rule 5/6/7 编号（只述 Rule 1~4 + DEFAULT），`addWrappers` 分流（`/*`→前缀、`*.`→扩展名、`/`→default）；
- lab 实测核对：`MappingLabTest` 1/1 通过，数据与正文一致（EXACT→200 / PATH→200 / 默认→404）；
- 格式检查：fence 偶数；交叉引用（L02/L04/L05）均有对应内容。

### 修正项（0 项——零修正通过）

- 写作阶段已按 04 篇深审结论成文；唯一 deliberate 简化是"有序数组定位"（二分+回退的算法细节留具体实现，概念课不展开）。

### 核对无误

- 查询链类名顺序：`CoyoteAdapter.service` → `postParseRequest` → `Mapper.map` → `internalMap`（先 Host 后 Context）→ `internalMapWrapper`（Rule1→Rule4→DEFAULT）；
- 数据结构类名与字段：`MappedHost/ContextList/ContextVersion`（四数组）/`MappingData`（host/context/wrapper/wrapperPath/requestPath/pathInfo/matchType…）；
- 维护机制：`MapperListener` 事件（register/unregister）、不可变数组复制替换读路径无锁；
- `DispatcherServlet → defaultWrapper`、`register-default-servlet=false` 与 L05 口径一致；
- lab 实测可复现（`MappingLabTest`: 200/200/404）。

### 结论

**L11 Round 1 通过**（零修正）。下一步：L12 Lifecycle 状态机主干（Stage 3 收官）。

---

## L12 Round 1（2026-09-29，深度 review · Stage 3 收官）

### 核对方法

- 枚举 12 态 + 事件绑定逐项核对（`LifecycleState` / `Lifecycle` 接口常量）；
- 模板骨架与约定复核（`LifecycleBase` final 方法 + abstract 内部方法 + STARTING 约定 + 两类失败）；
- 容器启停两条链复核（`StandardServer/StandardService.startInternal` 顺序；`StandardService.stopInternal` 顺序——见 L06）；
- 格式检查：fence 偶数；类归属表述。

### 修正项（1 处类归属表述）

| # | 问题 | 修正 |
|---|---|---|
| 1 | §4 写 "Tomcat.start()（Spring 侧入口）"——易误读为 `Tomcat` 是 Spring 的类 | 改为"Spring 侧**调用** `Tomcat.start()`（Tomcat 类）→ `getServer().start()`"（`Tomcat.start` 在 Tomcat 侧，`TomcatWebServer` 在 `:128` 处调用它） |

### 核对无误

- 12 态全 + 每个中间态的事件绑定（INITIALIZING←BEFORE_INIT … DESTROYED←AFTER_DESTROY；NEW/FAILED 无绑定；configure_start）；
- 骨架 `final synchronized` + `xxxInternal` + STARTING 约定 + 受控/非受控失败，与 05 篇一致；
- 启动链（CONFIGURE_START → 各 Service → engine/executor/mapperListener/connectors）与停止链（停流量 → engine → connector → mapperListener/executor）与 L02/L05/L06 一致；
- Spring 侧衔接（`WebServerStartStopLifecycle` + L07 另有停机机制）表述无出入。

### 结论

**L12 Round 1 通过**（1 处类归属表述已修正）。**Stage 3（源码主干 L10~L12）全部完成**。下一步：L13 K8s / 云原生（Stage 4 开始）。

---

## L12 Round 2（2026-09-29，深度轮 · 全文通读）

> 触发：用户要求"先深度 review 一轮"。方法：全文逐段通读 + **归因与措辞精确性**（失败时"谁"在清理、"谁"在调用）。

### 修正项（4 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | §1.3 写法与 Round 1 修正后的 §4 口径不一致（仍把 `Tomcat.start()` 写成 Spring 侧机制） | 改为"衔接 Spring 侧：Spring **调用** `Tomcat.start()` 启动整树；`WebServerStartStopLifecycle`（SmartLifecycle）挂生命周期" |
| 2 | §3 表"失败处理：父组件清理该组件"——**归因偏差**（实际是 `LifecycleBase` 替该组件调 `stop()`，父组件只负责继续启动其它子组件） | §3 表与 Q3 答案同步修正为"基类替该组件调 `stop()` 完成清理，其父组件继续启动其它子组件" |
| 3 | §2"asymmetry 细节"中英混写且表意含糊 | 改为中文表述，并明确"需先 `stop()` 才能重新启动" |
| 4 | "需人工/重启恢复"含糊 | 并入修正 3，明确恢复方式 |

### 核对无误

- 官方引文/规范（Round 1 已逐字核对）；
- 术语对照表与 Self-Test 答案与正文逐项一致；
- "面试话术产出物（05 篇）"的引用指向确有该内容的 05 篇位置；
- 格式：代码块偶数；结尾完整。

### 结论

**L12 Round 2 通过**（归因精确性已校准）。Stage 3（L10~L12）全部通过深度审查。下一步：L13 K8s / 云原生。

---

## L13 Round 1（2026-09-29，深度 review · Stage 4 开始）

### 核对方法

- Boot 侧复核：`AvailabilityProbesAutoConfiguration`（开关属性 + K8s 自动探测）、`EventPublishingRunListener` 状态翻转点、状态枚举值、关闭钩子注册——均与 13 篇深审结论一致；
- lab 数据核对：`KubernetesLabTest` 三组输出、`k8s-jvm-demo.sh` 三组参数对比（3986→1596MB、processors 16→2）；
- 格式检查：fence 偶数；YAML/输出块完整。

### 修正项（1 处）

| # | 问题 | 修正 |
|---|---|---|
| 1 | §5 basedir 示例写 `/tmp/...`——在"只读根文件系统"语境下有歧义（/tmp 本身不可写时示例误导） | 改为 `<emptyDir 挂载的可写目录>` |

### 核对无误

- 探针三分工与翻转点表述准确（liveness 只做进程级；K8s 环境自动开启；启动完成翻状态）；
- 资源感知结论（MaxRAMPercentage 而非 Xmx；ActiveProcessorCount；maxThreads 不随 CPU 变）与 lab 实测一致；
- 优雅下线对齐公式与 L07 一致；SIGTERM/SIGKILL 区分正确；
- 5 个坑与 5 道 Self-Test 答案逐项成立；lab 数字（1182ms 硬断、docbase 12→13）与实测一致。

### 结论

**L13 Round 1 通过**（无阻断级；1 处示例歧义已修正）。下一步：L14 线上排查。

---

## L14 Round 1（2026-09-29，深度 review）

### 核对方法

- 引文与主张核对：`top` %CPU 单核基准（OS 工具行为，按宿主机逻辑核折算）、GC 判型路径（`jstat -gcutil` / GCT）、线程/堆工具用法（jstack 连抓三次、死锁自动报告、`jmap -histo:live` 的 Full GC 副作用）；
- 引用一致性：所有"（xx篇/Lxx）"引用逐一核对归属（lessons 用 `Lxx`，面试系列用"xx篇"）；
- lab 数据核对：`DiagnosticsTest` 三组实测输出逐字对照。

### 修正项（3 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | §1"（09 篇）"未标明归属（lessons 也有 L09，同名易混） | 改为"（见 lessons L09 与面试 09 篇）" |
| 2 | §3"会话扫描约 60s 一轮，见 08 篇相关机制"——**引用错误**：L08 是异步，与会话扫描无关 | 删去错误引用；结论保留（后台周期性扫描约 60s 一轮） |
| 3 | fence 偶数性 | 8 个，正常 |

### 核对无误

- CPU 百分比语义（单核基准 100%；200%/800% = 约 2/8 个核吃满；判读除以 `nproc`）；
- 负向断言：Boot 全仓 0 命中 `JreMemoryLeakPreventionListener`（"内嵌没有独立部署的两道防线"成立，与既有结论一致）；
- 线程名地图与 L03/L07 口径一致；hang 四线与 L09 口径一致；拖优雅停机联动与 07 篇/lab 实测（44.76s）一致；
- lab 三组输出逐字一致（`spin(:43)` / `Found 2 Java-level deadlock(s)` / `leakedMb=3 → 0`）；
- Self-Test 5 题答案与正文逐项一致。

### 结论

**L14 Round 1 通过**（1 处引用错误已修正）。下一步：L15 虚拟线程。

---

## L15 Round 1（2026-09-29，深度 review）

### 核对方法

- javadoc 逐字：Tomcat `VirtualThreadExecutor`、Boot `Threading.VIRTUAL`、customizer（三处）；
- 行为核对：Boot 注册链与 order、命名规则（`tomcat-handler-N`）、lab A/B 实测（1818ms vs 1009ms、`syncInFlight=2`）；
- 版本口径：JDK21（JEP 444）/ JDK24（JEP 491）概念引用标注。

### 修正项（1 处措辞）

| # | 问题 | 修正 |
|---|---|---|
| 1 | "并发闸门变成 `maxConnections`/`acceptCount`"——`acceptCount` 是 OS 连接队列长度，不是并发闸门 | 改为"限制变成 `maxConnections`（连接数）、`acceptCount`（连接队列）、下游超时与限流"（§5 表 + Q1 同步） |

### 核对无误

- 3 处 javadoc 引文准确（"每任务一虚拟线程"、"JDK21+ 双条件"、"在协议处理器上启用"+order+1）；
- 命名规则（`Thread.ofVirtual().name(prefix, 0)`）与 lab 线程名一致；
- lab 数据与实测一致；JDK17 自动 Skip 机制与全量无回归一致；
- 格式：fence 偶数；结尾完整。

### 结论

**L15 Round 1 通过**（1 处措辞已修正）。下一步：L16 Boot 2→3 / jakarta 迁移（收官）。

---

## L15 Round 2（2026-09-29，深度轮 · 全文通读 + 跨轨道引用检查）

> 触发：用户要求"先深度 review 一轮"。方法：全文逐段通读 + **全 lessons 扫描"N篇"引用**（lessons 用 `LNN`、面试系列用"N篇"，两套编号并存，必须消歧）。

### 修正项（2 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | **引用串台（关键）**：L15 §0 前置写"L11 引用（线程名）"——lesson 体系里 L11 是 **Mapper 课**，线程名实际是 **L03** 教的（"11"指的是面试 11 篇，造成串台） | 改为前置"L03（线程模型与线程名地图）" |
| 2 | L10 注释"(09 篇)"含义模糊（同一含义在 L14 已规范为"面试 09 篇"） | 改为"（面试 09 篇；限流语义见 lessons L09）"，与既定消歧惯例对齐 |

### 全轨道引用核对（通过）

- 全 lessons 扫描"N篇"引用：L10（已修正）、L12:100"05 篇排障线索"（面试 05 确有该节 ✓）、L14:26（已消歧 ✓）；其余均为 `LNN` 内部引用且目标课存在；
- L15 其余内容（引文/实测/术语/Self-Test）Round 1 已核，本轮无新增问题。

### 结论

**L15 Round 2 通过**（跨轨道引用已消歧）。下一步：L16（收官）。

---

## L16 Round 1（2026-09-29，深度 review · 收官）

### 核对方法

- 引文核对：`spring-boot-properties-migrator` 包说明逐字；版本矩阵标注"公开版本对应"；
- 前置引用核对：L16 §0 引用的出处（L01/L04）逐一验证存在；
- lab 数据核对：`MigrationLabTest` 2 组输出（200 / 400）与正文一致；
- 格式：fence 偶数；结尾"Stage 4 完成 + Stage 5"正确。

### 修正项（1 处）

| # | 问题 | 修正 |
|---|---|---|
| 1 | §0 前置写"L06（版本引用：Servlet 6.0）"——**L06 根本没讲版本号**，Servlet 6.0 出处是 **L01 §2** | 改为"L01（组件；版本引用：Servlet 6.0）、L04（Filter）" |

### 核对无误

- migrator 引文逐字；版本矩阵（Boot 2.7 vs 3.x）与公开口径一致，文内已标注；
- lab 数据一致；Self-Test 5 题答案与正文逐项一致；
- Stage 4 四课（L13~L16）引用链闭合（L16 → Stage 5）。

### 结论

**L16 Round 1 通过**（1 处前置引用修正）。**Stage 4（L13~L16）全部完成** → 全部 16 课（L01~L16）完成。下一步：Stage 5 产出（`architecture-map`）。

---

## Stage 5 Round 1（2026-09-29，深度 review · 产出物）

### 核对方法

- 双向校验表逐行核对：map 14 处引用 → 面试篇存在且主题对版；面试篇 14 处落点 → map 确有对应内容；
- 引用消歧检查：`LNN`（lessons）vs "N篇"（面试系列）——全 map 扫描；
- 新增主张核对：凡 map 中首次出现的主张必须有来源课。

### 修正项（3 处，均已应用）

| # | 问题 | 修正 |
|---|---|---|
| 1 | §1 D 组含 **Session**——全 lessons 仅 L14 提过一次（内存嫌疑对象），无专课覆盖，违反"双向闭合" | D 组改为 Lifecycle / 指标（JMX/actuator） |
| 2 | 定制行写"L10（ProtocolHandler 定制）"——L10 是 Connector/Endpoint 主干课，定制点在 **10 篇** | 两处（正反表）统一为"（定制点见 10 篇）" |
| 3 | （扫描确认）其余引用全部对版，无悬空 | — |

### 核对无误

- 组件树/请求链/三张图/12 张速记卡与 L01~L16 口径一致；
- 14 篇双向表：正向 14 行、反向 14 行全部闭合（每篇都有落点、每处引用都有篇）；
- 格式：fence 偶数；结尾完整。

### 结论

**Stage 5 Round 1 通过**（双向闭合成立）。**Tomcat 学习课 L01~L16 + architecture-map 全部完成并通过审查** 🎉
