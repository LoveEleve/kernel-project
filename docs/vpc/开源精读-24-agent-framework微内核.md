# 开源精读 24 · vpc-agent-framework——微内核长成后的样子（S3 开篇）

> 课次：S3 第一站（33-vpc-agent-framework 793 行）
> 坐标：三代 Agent 框架的谱系在此收齐——一代 `agent_base`（材料 `docs-by-topic/14-agent_base` + 17 导读："Python Agent 的公共底座：ZK 事件响应、CLI、HTTP 管理、监控上报、进程守护"）→ 二代 `agentos`（第 16 课：Extension 三层 + inspector）→ **三代 `vpc-agent-framework`（本课）**。第 18 课说"契约是骨架"，本课翻开骨架看微内核。
> 纪律：断言落材料文本。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **微内核框架** | 材料定位："**边缘执行组件微内核框架**，运行在计算节点上"——负责从 Broker 拉配置（**Watch 增量 / ReSync 全量双模**）、本地持久化计算、驱动内核 |
| **AgentMain** | `pkg/agent/entry.go` 入口：解析命令行（含 **workerMode / logqueryMode / `--full-start`**）→ **串联**"配置加载 → 存储打开 → DataSyncer → Extension → HTTP → Reconciler" |
| **AgentContext** | 全局上下文：挂载 **Config / DataSyncer / ExtManager / Storage / DataPlane / HTTPServer / Reconciler** 全部核心模块 |
| **三大手** | **datasyncer（153 文件，吃 Broker）/ extension（83，吃业务）/ dataplane（39，吃内核）**——三个最不稳定的边界各配一只大手 |
| **DataPlaneFacade** | 数据面统一门面：**所有方法可用 opts 指定目标驱动**，缺省由 autoconfig 决定——多驱动（netlink/dpdk/**fake**）可插 |
| **fake adapter** | 内存假数据面——"用于测试与无内核环境开发"（**CI 里能跑完整 Agent**） |
| **shutdownstate** | 停机状态与**恢复等级**——QuickRestart 分级的框架化（第 19 课机制的第三代故乡） |
| **`.specify/`** | Spec-Kit 脚手架（11 文件）；连同 `.codebuddy/`（10）/`.cursor/`（3）——**AI 工具配置进仓库** |

## ① 它干了什么——`AgentMain` 一行的背后

框架的用法是"**业务仓库 import 微内核**"（第 16 课 agentos 的同款姿势，三代不变）。`pkg/agent/entry.go` 的 `AgentMain` 是总装配线：

```
解析命令行（workerMode / logqueryMode / --full-start）
  → 信号处理
  → 串联：配置加载 → 存储打开（LevelDB）
        → DataSyncer（从 Broker 拉配置：Watch 增量 / ReSync 全量）
        → Extension（业务处理）
        → HTTP 服务器（运维 API）
        → Reconciler（对账）
```

**注意最后一位**：`Reconciler` 不是业务自己接的——**它对账框架是微内核的默认件**（第 16 课 agentos 把对账升格为框架的 inspector；三代把它做成了启动线上的标准工序）。**一句话**：从 agent_base 的"工具包"、agentos 的"应用框架"，到 framework 的"**微内核 + 默认全套器官**"——**框架的责任边界又长了一圈**。

## ② 链路走查（材料行号）

### 跳 1：骨架——生命周期与"恢复等级"（33 §3.10/§3.24）

- **AgentContext**（`pkg/agent/context.go`）："挂载 Config/DataSyncer/ExtManager/Storage/DataPlane/HTTPServer/Reconciler 等**全部核心模块的全局上下文与协调逻辑**"——微内核的"灵台"；
- **AgentMain**（`entry.go`）：上述总装配线 + **workerMode（多进程 Worker 模式）**/ **logqueryMode（离线日志检索模式）**/ **`--full-start`**（与 QuickRestart 对应的全量启动开关）；
- **lifecycle**（`lifecycle.go`）：**生命周期状态机**——"**startup settled 判定、启停阶段准入与恢复等级决策**"；
- **shutdownstate**（§3.24，5 文件）："**停机状态与恢复等级**"——停机不是"啪"一下，而是**记录自己的死法**，为下次启动的**恢复等级**（增量 or 全量）做决策输入；
- **worker_config**（`worker_config.go`）：多进程 Worker 模式的**配置派生**（worker 子进程的配置裁剪与传参）。

**读法**：一代的重启策略写在各 agent 的启动脚本里（第 15 课 ZK 运维族见过）；二代是 SyncOnStart 的清表重建；三代把它做成了 **lifecycle + shutdownstate 两个一等模块**——"**怎么启动、怎么停机、停机后怎么恢复**"从散落的 if-else 升格为**状态机与策略**。第 19 课的"三级降级恢复"（增量→全量→QuickRestart）在这里找到了框架级故乡。

### 跳 2：三巨头——吃 Broker / 吃业务 / 吃内核（33 §3.16/§3.17/§3.15）

框架把"最容易变的三条边界"各配了一只大手：

**① datasyncer（153 文件 = 源码 70 + 测试 83）——吃 Broker**。第 19 课每个机制在这里都有**专文件**：

- `syncer_impl.go`：**Watch/ReSync 双模同步编排**；`watch_orchestrator.go`：watch 的建立/迁移/失败重试；
- **恢复学四件套**：`recovery_coordinator.go`（断连/重启后的**整体恢复流程编排**）/ `startup_recovery.go`（**首启 vs 快速重启的增量恢复决策**）/ `safe_resume_version.go`（**断点续 watch 的版本游标保护**）/ `rewatch_scheduler(.worker/_timing)`（失效 watch 的延迟重订阅排队、**退避/去抖/优先级时间计算**）；
- **一致性两件**：`startup_first_event.go`（**启动首个事件屏障**——"保证首事件前订阅就绪的一致性语义"）/ `merge_remote.go`（远端快照与本地状态的合并）；
- 连接侧：`scope_gate_client.go`（**订阅放行决策**）/ `seed_fallback.go`（Discovery 失败回落种子）/ `set_resolver.go`（配置类型→SET 映射）/ `broker_recovery_state.go`（**per-broker 故障与恢复水位**）/ `bypass_executor.go`（**绕过队列的关键路径快速执行通道**）。

**② extension（83 文件 = 37 + 46）——吃业务**：`types.go`（Extension 接口与**生命周期钩子**框架契约）/ `manager.go`（**ExtManager：注册、订阅管理、事件分发的微内核中枢**）/ `capability_registry.go`（各 ext 声明支持的配置类型与能力）/ `pending_subscription.go`（**待决订阅：未满足前置条件的订阅挂起与补齐**）/ `scope_guard_wrapper.go`（**按 scope 维度对 Handler 调用做准入保护**）/ `scope_pending_queue.go`（scope 未就绪时事件排队缓冲）。

**③ dataplane（39 文件）——吃内核**（跳 4 详述）。

**读法**：三巨头的文件构成暴露了微内核的**真正投资方向**——不是"目录漂亮"，而是**把恢复、屏障、续传、准入这些分布式最难的部分，每个都做成独立可测的文件**；datasyncer 一包 83 个测试文件，几乎每个恢复机制都配了测试。

### 跳 3：数据面抽象——"三种驱动 + 一个假货 + 一条装配线"（33 §3.15/§3.18）

- **Facade**（`dataplane/facade.go`）：统一门面——"**所有方法可通过 opts 指定目标驱动**，缺省用 autoconfig 决定的默认驱动"；
- **驱动全家**：`adapter`（**ProtocolAdapter 协议适配器**——接口定义在 dataplane 包**避免循环依赖**）+ `transport`（**netlink**：rtnetlink 与内核网络栈交互 / **dpdk**：经通道与 OVS/DPDK 数据面通信）+ **`fake`（内存假数据面——"用于测试与无内核环境开发"）**；
- **delivery 子系统**：`Deliverer`（下发执行器——"**持有最小 capability 接口，按能力决定下发动作**"）/ `registry`（下发动作注册表）/ `value_resolver`（"从 Entry/期望态解析实际写入值"）；
- **能力协商**：`features.go`（Capability key 常量——driver 支持矩阵）/ `capabilities.go`（查询各 driver 对特性/操作的支持情况）；
- **装配线**：`cli`（**tvs** 调试命令行——`make build-tvs`）/ `example/host/`（**教学样板**：host-agent 场景如何组装 Facade）。

**"fake adapter"值得单记**：**真正的微内核必须"能在没有内核的地方跑"**——CI 和无 DPDK 的开发机上，整个 Agent 链路可以对着内存假数据面完整跑通。"可测试性"不是附赠品，是 Driver 抽象的第一等，用户。

**dplane 的另一半（§3.18，17 文件）——业务翻译层**：`dataplane` 管"怎么跟驱动说话"，`dplane` 管"**业务话怎么翻译成驱动话**"——`Facade`（"**Extension 调用数据面的唯一入口，业务完全不感知底层 driver 数量**"）/ **`Assembler`（由业务 agent 实现**，把业务配置翻译为面向 driver 的数据面操作）/ `Pipeline`（Assembler 注册与**批量组装流水线**）/ `EnvDetector`（运行环境探测——driver 可用性决定操作路由）/ **`ReconcileView`（面向对账的数据面实际态读取视图）+ `EntryDiff`（期望态 vs 实际态的差异计算——增/删/改集合）**——reconciler 的对账数据从这里来，**"对账链路"在文件级闭环**。

### 跳 4：支撑件与"哲学三角"（33 §3.20-§3.26 + 启动流程）

- **reconciler（13 文件）**：管理器/注册表 + **任务集 `tasks/ctrl_set/`：`core_ctrl_set` 与 `resource_ctrl_set` 两个对账任务** + `example/vpc_ctrl_set_example`（教学样板）+ 策略日志。**注意对账对象**：Agent 对的是"**我订阅的 SET 视图**"——框架保"订阅视图正确"，业务配置的对账仍归 Extension（分层对账）；
- **storage（22 文件）**：Storage 抽象 / Manager（**命名空间管理**）/ `namespaced_store`（**按 scope/ext 隔离键空间**）/ `sync_store`（部分更新/范围扫描/**订阅通知**）/ `merge_core`（快照与增量的合并算法——"unify syncdata merge 后的统一实现"）/ `binlog_cache` / **`shutdown_marker`（停机标记持久化与完整性校验）** / **`fault.go`（故障注入：leveldb fault failstop 场景）**；
- **versionstore / watchstore**：前者"按 Scope/Set/ConfigType 维护最新配置版本号（**快速重启续传依据**）"，后者"**Watch 流持久化**"（按 SetID/ConfigType/ScopeKey 组织活动 Watch 条目）；
- **哲学三角**：`shutdownstate`（停机状态机：一致性准入、生产者静默、Drain）+ `storage/shutdown_marker`（标记持久化与完整性）+ `datasyncer/startup_recovery`（启动期决策）——**"停机—持久化死法—启动判案"跨进程生命周期协议**（对照第 19 课 `sessionSnapshotStore`：同一招在 Session 层与进程层各用一次）。

**启动流程六步**（材料 §启动流程）：`--full-start` 参数 → 配置/日志/全局 → 打开存储、恢复 version/watchstore，"**若存在 Shutdown Marker 且条件满足，走快速重启增量恢复，否则全量启动**" → DataSyncer（Discovery → tRPC → Watch/ReSync 双模 → dispatcher/watchrouter 分发）→ Extension（内置 `corectrlset / resourcectrlset / vpcbroker / agentecho` + 业务 ext）→ HTTP（**console 控制台（go:embed 前端进二进制）+ 完整运维 API 族**：statusz / binlog / data / config / **datasyncer（同步状态与 rewatch 队列观测）/ ops（触发重同步/清理——"agentctl ops 套件后端"）/ reconciler（对账报告）/ trace（最近流水 + 调用树）**）+ Reconciler + metrics push + **watchdog 守护**。

### 跳 5：工程纪律——"人怎么写代码"的第三代形态（33 §3.27/§3.28 + 构建矩阵）

- **specs/ 300 文件**（需求与改动跟踪）+ **test/ 140 文件** + **testrunner 统一入口**（`make test / test-unit / test-e2e / test-ci` 分类跑测并出报告）；
- **构建矩阵**：`build`（Agent）/ `build-tvs`（数据面调试 CLI）/ `build-testrunner` / `build-perf-runner`（**性能测试运行器**）——工具链也是一等公民；
- **三套 AI 配置进仓**：`.codebuddy/`（10 文件）+ `.cursor/`（3）+ **`.specify/`（Spec-Kit 脚手架 11 文件）**——对照第 18 课 skills（AI 工具集进架构文档）、第 22 课 2900 行测试：**"人怎么写代码"连续三代出现在工程产物里**。

## ③ 设计推导（问题树）

**Q1：为什么三巨头恰好是 datasyncer / extension / dataplane？**
三条边界的**变化速率**：Broker 协议与分布式语义（中速变）/ 业务配置类型（高速变——105 种）/ 内核与驱动（低速但难懂）。微内核把"易变"全部推给可替换的大手，自己只留**装配线 + 生命周期 + 存储 + 对账**这些"慢"的部分。**对照第 16 课 agentos 的 Extension 三层**——二代的三件套是"资源处理范式"，三代的三巨头是"**工程分工**"：同名不同深。

**Q2：datasyncer 为什么测试文件（83）比源码（70）还多？**
它是全框架状态机密度最高的包：首启/快重启/断连/重连/退役（`syncer_impl_retire`）/迁移（watch_orchestrator）/屏障（first_event）/游标保护（safe_resume）。**每一个恢复分支都必须有测试**（对照第 23 课 3:1、第 22 课 2900 行——"**测试重于修复**"是三代一贯的文化）。**分布式系统里，测试数量≈状态组合数的诚实度量**。

**Q3："首事件屏障"与"安全续传版本号"防的是哪两个经典坑？**
屏障防"**订阅就绪前的事件丢失**"（事件比订阅先到）；游标保护防"**断点回退/跳号**"（resume 时本地版本比远端新或旧错了方向）。**一个是时序坑、一个是版本坑**——恰好是配置分发系统的一对基本功（对照第 19 课"快照对齐 + cached binlog 注入"：同题的不同解法，这次下沉到了框架文件级）。

**Q4：内建 reconciler 对账的为什么是"SET 视图"而不是业务配置？**
分层对账：**框架的管辖范围是"我的订阅视图与 Broker 的数据一致"**（core/resource ctrl-set 的期望态 vs 实际态）；**业务配置的正确性归 Extension 的 CheckJob**（第 16 课 inspector 的第三代转世）。**"对账跟着责任边界走"**——谁维护什么，谁对账什么。

**Q5：shutdownstate + shutdown_marker + startup_recovery 三角的意义？**
把"重启恢复"从**每次现场发挥**变成**协议**：停机时**表态**（Drain、生产者静默、写 marker）→ 持久层**盖戳**（完整性校验——防"假 marker"）→ 启动时**判案**（快速重启 or 全量）。对照第 19 课 agent 侧 `sessionSnapshotStore` + LocalVersion：**同一设计母题（死亡要留遗言，重启要读遗言）在 Session 层与进程层的两次出现**。

**Q6：specs 300 / testrunner / 三套 AI 配置说明什么？**
**"怎么写代码"本身被工程化了**：spec 驱动（.specify/Spec-Kit）→ 需求进 specs/ → 测试进 test/ 统一入口 → AI 编辑器配置进仓。对照一代"手写四样、改 10+ 文件"（第 18 课）与二代 skills（第 18 课），**三代把工程纪律做成了仓库的物理结构**。

## ④ 对照卡：三代 Agent 框架谱系（完整版）

| 维度 | 一代 agent_base（材料 `14-agent_base`） | 二代 agentos（16 课） | **三代 vpc-agent-framework（本课）** |
|---|---|---|---|
| 定位 | 工具包（各 agent 自组装） | 应用框架（一框架多业务） | **微内核 + 默认全套器官** |
| 资源范式 | watcher / object / check | upstream / res / downstream + CheckJob | **datasyncer / extension / dataplane 三巨头** |
| 同步模式 | 分区 ZK watch | 心跳拉 + 中心记账 | **Watch 增量 / ReSync 全量双模** |
| 对账 | 各业务手写 | inspector（框架） | **Reconciler 上启动线（默认件）+ SET 视图对账** |
| 恢复 | 启动脚本时代 | SyncOnStart 清表重建 | **lifecycle + shutdownstate + marker + startup_recovery 协议化** |
| 数据面 | 直接 netlink/iptables | SQLite + 驱动 | **Facade + Driver + fake（无内核可测）** |
| 工程纪律 | 散落 | skills（AI 工具集） | **specs 300 / test 140 / testrunner / 三套 AI 配置** |

## ⑤ 验收题

1. "框架的责任边界又长了一圈"——从工具包到微内核，framework 新增接管的四件事是什么（启动线/生命周期/恢复协议/对账默认件）？
2. 三巨头的划分依据是"变化速率"——把 105 种业务配置、Broker 协议、内核驱动各归一位并说明理由。
3. datasyncer 的"恢复学四件套 + 一致性两件"各防什么？为什么"首事件屏障"必须由框架而不是业务实现？
4. fake adapter 的"无内核环境开发"给 CI 与新人上手分别带来什么？（对照一代"ibverbs 依赖"式的开发门槛）
5. 分层对账（框架对 SET 视图、业务对配置）与第 16 课 inspector 的分工有什么异同？
6. "停机留遗言"协议（state→marker→startup_recovery）与第 19 课 Session 快照持久化是同母题的两次出现——第三次会出现在哪一层？（提示：S3 还有一课没上）

---

*本课材料行号清单（供追问对质）：`33` §1（定位"边缘执行组件微内核框架"+ Watch/ReSync 双模 + 技术栈 Go 1.25.4 + 构建矩阵五种 make）/ §3.10（AgentContext/AgentMain 串联/workerMode/logqueryMode/--full-start/lifecycle startup settled/worker_config）/ §3.15（Facade opts 驱定/features Capability key/capabilities/adapter 避免循环依赖/fake"无内核环境开发"/delivery Deliverer"最小 capability 接口"/value_resolver/transport netlink+dpdk/cli tvs/example host）/ §3.16（153=70+83：syncer_impl 双模/watch_orchestrator/recovery_coordinator/startup_recovery/startup_first_event/safe_resume_version/rewatch_scheduler×3/merge_remote/remote_get/scope_gate_client/seed_fallback/set_resolver/broker_recovery_state/bypass_executor）/ §3.17（83=37+46：manager ExtManager 中枢/capability_registry/pending_subscription/scope_guard_wrapper/scope_pending_queue）/ §3.23（reconciler 13 文件 + tasks/ctrl_set 两任务 + example 样板）/ §3.24（shutdownstate 5：state Drain/reasons Marker 完整性/recovery_level_test）/ §3.25（storage 22：namespaced_store/sync_store/merge_core/binlog_cache/shutdown_marker/fault failstop + 13 测试）/ §3.26（versionstore"快速重启续传依据"/watchstore"Watch 流持久化"）/ §3.20（http console+statusz/binlog/data）/ 启动流程（六步 + Shutdown Marker 条件快启）/ §3.27（specs 300）/ §3.28（test 140）/ 测试流程（testrunner 分类）/ 开发路径（§末）。*

*审查记录（首轮深审）：修正 1 处——**归属虚指**（一代 `agent_base` 原写"第 4/28 课材料"：第 4 课是 ZK 本体、28 是 vpc_agent master 材料，两不沾；实证修正为 `docs-by-topic/14-agent_base` 专档 + 17 导读第 89 行"公共底座"原文）。硬锚升级 2 处——① **§3.18 dplane 补读**（我原只读了 dataplane 半边）：`Facade`"业务不感知 driver 数量"/ `Assembler` **由业务实现**的业务翻译 / `Pipeline` / `EnvDetector` / **`ReconcileView`+`EntryDiff`（期望态 vs 实际态差异计算）**——reconciler 的输入路径文件级闭环；② §3.20 http 从"statusz/binlog/data"升级为**运维 API 族全貌**（console go:embed / datasyncer 的 rewatch 队列观测 / ops "agentctl ops 套件后端" / trace 树）。逐字命中：AgentContext 挂载清单、AgentMain 三模式、lifecycle"startup settled/恢复等级"、§3.15 Facade opts、§3.16 恢复学文件清单、§3.17 ExtManager 中枢、§3.23 ctrl_set 两任务、§3.24 "Drain 状态机"、§3.25 namespaced_store/shutdown_marker/fault、§3.26 "快速重启续传依据"、启动六步、构建矩阵五 make。另备注：`syncer.go` 与 `global/state.go` 两处"规避循环依赖"——**接口换依赖**是本框架的惯用手法（`adapter` 第三处）。*

*审查记录（二次深审）：R1 修正自查在位（`14-agent_base`/Assembler/agentctl ops/EntryDiff，grep 5 处命中），"第 4/28 课材料"残留仅审查记录回溯（@139）——**零尾差、零新错**。*
