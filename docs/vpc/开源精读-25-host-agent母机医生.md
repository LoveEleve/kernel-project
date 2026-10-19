# 开源精读 25 · vpc-host-agent——母机上的"全科医生"（S3 第二站）

> 课次：S3 第二站（39-vpc-host-agent 933 行；internal 598 文件）
> 坐标：第 24 课看了微内核（framework），本课看**第一个把它装满的业务 Agent**——母机 daemon：数据面五段流水（extension→composer→dplane→Facade→adapter）、对账 137 文件、发行工程（Adam / 双架构 / **只读影子包**）。
> 纪律：断言落材料文本；与第 18 课"54 Extension"的口径差异如实并记。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **watchdog（父）+ worker（子）** | 生产进程模型：**同一个二进制**既可当 worker 跑业务、也可当 watchdog 守护（framework 提供能力） |
| **autoconfig** | 母机环境自动探测：架构→**角色**→vpcko/DPDK socket→Zone/IP（材料自注"**对标一代 sdn_agent 的 auto_config.py**"） |
| **composer** | **跨对象组合器**（49 文件）——单对象 Extension 看不懂的"关系学"（如 rtable 展开下一跳） |
| **dplane（74）** | 母机数据面拼装门面（Assembler 层）——业务配置 → 面向 driver 的操作 |
| **adapter/{vpcko, dpdk, fake}** | 三个数据面适配器——生产双驱动 + 测试假货 |
| **SHADOW_MODE** | `make` 打包开关：打"**预下发只读**"包——**只对账上报、不写数据面**（演练/影子内建） |
| **ReportAggregator** | "**预下发 diff 上报**"（可选）——第 16 课 `UpdateHostDiff` 的 5.0 正式版 |
| **OnStartupSettled** | 启动基线：**首批配置处理完**才"放行 refnotify、驱动依赖方启动重算" |
| **`default_task_enabled=false`** | Reconciler 的**灰度门控**：首批 9 个任务先 enabled，其余逐步放量 |

## ① 它干了什么——一台母机的"上岗日"

母机开机，`vpc-host-agent` 从探自己开始：

1. **autoconfig**（`PostContextHook` 里）：探测**架构 → 角色（host/网关…）→ vpcko/DPDK socket → Zone/IP**——"我是谁、我在哪、我有什么能力"；
2. **OnInit**：驱动启用集合 = `autoconfig ∩ 配置`（**fallback fake；全禁则 panic**）→ 建 `pkg/dataplane.Facade`（vpcko/dpdk/fake）→ **composer runner**（vtrace/session **串行** Runner，"防回调栈死锁"）→ `extregistry.RegisterAll`（**60+ 业务 Extension** + WatchRouter 映射 + PostRegister 钩子）→ 登记对账任务；
3. **OnReady**：DataSyncer 连 broker（Watch/Poll 订阅**全部 ConfigType**）→ Reconciler 启动（**灰度门控**，首批 9 任务）→ **ReportAggregator**（预下发 diff 上报）→ **dpdkrecovery.Prober**（仅 host 角色且无 dpdk 时——**没有 DPDK 也要活着**）→ internal/http 路由（HTTP + **unix socket**）；
4. **OnStartupSettled**：**首批配置处理完毕**，结束启动基线——放行 refnotify、驱动依赖方启动重算。

然后进入稳态：`broker → DataSyncer → extension → composer → dplane → Facade → adapter → 内核`，对账走 `reconcile → Diff(desired, actual) → 自动修复 + 上报`。

**一句话**：这不是"一个 agent"，是**一台自带体检、自选科室、自备演练模式的母机全科医生**。

## ② 链路走查（材料行号）

### 跳 1：进程模型与发行工程（39 §1.4/§4.1）

- **进程模型**（§4.1）："生产形态为 **watchdog（父进程）+ worker（子进程）**：`cmd/vpc-host-agent` 编译出的二进制**既可作为 worker 运行业务，也可作为 watchdog 守护进程运行**"——对照第 23 课 fusion 的"watchdog 独立进程 + systemd 两层"：**同一守护哲学（母机内自守）与中心侧（systemd 兜底）的两种落位**；
- **发行工程**（§1.4）：`make build` 产出 **Adam 发布包（amd64 + arm64 双架构）** + `agentctl` + `tvs`；**`ENV=product|test|bjcs`** 决定包内 `default_brokers` 地址与环境标识;；**`SHADOW_MODE=true` 打"预下发只读"包——只对账上报、不写数据面**（👈 这张牌后面推导环节要用）；部署"由 Adam 系统 + 包内 watchdog 守护"；**生产默认开启 quick_restart**（复用本地 LevelDB 增量启动——第 19 课的机制是默认值）；
- **本地三件套**：`make run` / **`make mock-broker-yaml`（本地 mock broker 提供测试数据！）** / `testrunner` 统一跑测——**"无中心也能开发"**（与第 24 课 fake adapter 的"无内核开发"凑成一对：**上下两端都能假**）。

### 跳 2：internal 域图——598 个文件的十四路诸侯（39 §3.7）

| 子域 | 文件数 | 角色 |
|---|---|---|
| **extension** | **244** | 业务 Extension（**每个 ConfigType 一个**） |
| **reconcile** | **137** | 对账任务 |
| **dplane** | **74** | 母机数据面拼装门面（Assembler 层） |
| **composer** | **49** | 跨对象组合器 |
| dataplaneaccess | 28 | 数据面导出/写入统一访问层 |
| autoconfig | 16 | 母机环境自动探测 |
| dpdkrecovery | 7 | **DPDK 恢复探测与自重启** |
| http | 7 | HTTP 导出/调试接口 |
| metrics | 4 | 业务指标注册 |
| netutil | 4 | 网络工具 |
| **migration** | **2** | **新老 Agent 滚动迁移状态机** |
| testdata 等 | 22+ | Mock 数据构建 |

**读法**：三梯队清晰——**业务族**（extension + composer + reconcile = 430 文件，占七成）是"母机特化业务核心"的本体；**数据面族**（dplane + dataplaneaccess + dpdkrecovery = 109）全在"怎么把配置落到内核"；**环境族**（autoconfig + migration + netutil）管"我是谁"和"怎么换代"——**migration 只有 2 个文件，但它是"新老 Agent 滚动迁移"的状态机**（一代→二代→三代的换代洪流，落到这里就是一个小小的状态机）。

> ⚠️ **材料口径并记**：第 18 课材料（business-capabilities）称 "**54 个 Extension**"；本材料 §4.2 写 "**60+ 业务 Extension**"——两处数字不同（可能各自成文时点不同），本课如实并列，存疑待考。

### 跳 3：数据面五段流水——extension → composer → dplane → Facade → adapter（39 §1.3/§3.7.3/§3.7.6）

**第 2 段 composer（49 文件）——"跨对象组合器"**：职责原话"**数据面下发前把多个 Extension 的数据组合为完整期望状态**"。领域一眼扫过：

- **rtable 家族最重**：`rtable_composer`（"汇聚 **vmRTable / serviceRTable / remoteGw** 等输入展开为完整 rtable"）+ `rtable_expand`（按目的/下一跳扇出）+ **`rtable_update_plan`（"大规模 rtable 变更的增量 update plan 计算"）** + `rtable_remotetype`（remote gw/peer vpc 类型标记）+ IPv6 同构版；
- **routing**：`next_hop`（下一跳"合法性/优先级处理"）；**sflow**（**五表联合**组装）；**trafficmirror**（**四表联合**）；**subnetroute**（含 `remote_vpc_ref_batch`——对 peer vpc 的**差分订阅批处理**）；session（sessionRule→数据面 session 表）；globalroute；vtrace；
- **Runner 双件**：`runner.go`（**异步串行执行 composer Recompute，"避免 datasyncer 回调栈重入死锁"**）+ `keyed_runner`（同 key 去重排队）——**"把重计算搬出回调栈"的工程姿势**；
- 测试：**25 个**（含 `rtable_gwchange_bench`、`next_hop_limit` 基准——**性能是有基准守护的**）。

**第 3 段 dplane（74 文件）**："把 Extension/Composer 输出的业务对象**转换为数据面 entry**"——`facade.go` 桥接框架 Facade 透传 `DeliverUpdate`；assembler 家族成行成市（acl_item / subnet_acl_rule / **enipip vif/neighbor/wan/eip_qos/ipv6** / broadcast_vm…）；`env_detector` 根据 autoconfig 结果决定启用驱动集合。

**第 4/5 段**：`pkg/dataplane.Facade`（framework，第 24 课）→ `adapter/{vpcko, dpdk, fake}` → 内核 netlink / DPDK 通道。

**五段一张表**：

| 段 | 层 | 输入 → 输出 | 代表 |
|---|---|---|---|
| ① | extension（244） | 原始配置对象 | 每 ConfigType 一个 |
| ② | **composer（49）** | 多对象 → **完整期望状态** | rtable 汇聚/展开/update plan |
| ③ | **dplane（74）** | 业务对象 → **数据面 entry** | assembler 家族 |
| ④ | Facade（framework） | entry → 驱动操作 | 多驱动路由 |
| ⑤ | adapter | 驱动操作 → **内核** | vpcko / dpdk / fake |

**附：`dpdkrecovery` 的自重启序列（§3.7.5，7 文件）**——五段流水之外最诚实的一个包：`doc.go` 设计文档写明了"**不做的事：不热加载、不处理反向消失**"；`prober.go` 周期探测（默认 **300s，最低 60s**）只在"host 角色且当前不含 dpdk 驱动"时启动；一旦探测到 dpdk 通道恢复，`restart.go` 执行重启序列：**`framework Stop → 清理 leveldb 目录 → os.Exit(1)`——交给 watchdog 拉起并以"双驱"启动**。**"DPDK 热恢复做不到，就用'清库重启'换一个干净的双驱状态"**——对照第 23 课 DPDK 景的"恢复三段"：那里讲的是代价，这里讲的是**决策的坦率**（把"不做热加载"写进包文档）。

### 跳 4：对账 137 文件与"只读守卫"——SHADOW_MODE 的全链闭环（39 §3.7.11）

`internal/reconcile`（**137 文件**）：`core/` 共享底座 + `foundation/` 基础对象任务 + 按资源域分组；`register.go` 声明"**所有业务 task 与 Extension 的绑定关系**"（`RegisterReconcileConsumer`）。

两个最亮的设计：

- **`core/stage.go`：`VpcReconcileStage`——"保证 VPC 类任务先于默认任务完成首轮全量对账"**——对账**分阶段有次序**（先保 VPC 大局，再清边角）；
- **`core/repair_readonly_guard.go`：只读守卫——"shadow_mode 下阻止修复写数据面"**——👈 **SHADOW_MODE 全链闭环**：发行层打"预下发只读"包 → 运行时**代码级守卫**拒绝修复写——**演练模式从打包脚本一路焊到对账修复的出口**（同族：第 22 课 DAO 拒绝式校验）。

配套：`incremental.go`（dirty consumer **聚合门 aggregate gate**——增量对账的入口限流）、`repair_error_collector`（修复失败汇总上报）。**共享视图层**（§3.7.4 `dataplaneaccess`，28 文件）："按资源域提供**导出(Exporter)/写入(Writer)统一接口**，供 **HTTP 导出 API、对账 BuildActualView 与高危写接口**使用"——**同一套契约三处共用**（看视图、比视图、高危写），`contract/` 包里 ResourceBinding / ExportResult / Writer(add/del) 就是那份契约。与 **ReportAggregator**（OnReady 启动的"预下发 diff 上报"）接力，完成"**对账 → 修复/上报**"的三代双通道（对照第 16 课 `UpdateHostDiff`：二代端侧"报案"的正式工业化版）。

### 跳 5：五钩子生命周期——启动链的完整剖面（39 §4.2 + §3.3）

framework 的通用生命周期（第 24 课 `AgentMain`）在这里被业务**逐个具名实现**——`lifecycle.go` **约 1200 行**，是全仓库最重的"胶水文件"：

```
OnInit            → 驱动集合 / Facade / composer runner / 注册 60+ Extension / 对账绑定
OnReady           → DataSyncer 连接 / Reconciler（灰度 9 任务）/ ReportAggregator / dpdkrecovery / HTTP 路由
OnStartupSettled  → 首批配置处理完毕：放行 refnotify、驱动依赖方启动重算
OnStart           → 进入稳态：事件驱动下发 + 周期对账
OnStop            → 优雅停止：Facade / Runner / Aggregator / Prober 资源回收
```

**"OnStartupSettled"是 Ready 的再定义**：进程起来不算 Ready、连接建上也不算——**首批配置处理完（有数据了）才算**。配套的 `cmd/` 39 个入口里：主程序（`cmd/vpc-host-agent`：main.go + lifecycle.go）、**`cmd/tvs`**（数据面 CLI）、**`cmd/agentctl`（15 文件，cobra 多命令）**——`--addr/--socket/超时/重试/认证策略` 可远程/trace 操作（unix socket 也在 HTTP 层注册过）。

## ③ 设计推导（问题树）

**Q1：composer 为什么必须独立成层？**
"单个对象的处理"（extension）与"**对象之间的关系学**"（composer）是两种知识：rtable 需要 vmRTable + serviceRTable + remoteGw **汇聚**；sflow 五表、trafficmirror 四表。更微妙的边界是**"中心预计算 vs 本地组合"的分工**：第 18 课 computation-agent 把 `rtable_expanded` 提前算好搬走（省母机算力）；而 composer 保留的恰是**依赖实时本地状态**的组合（session、neighbor、驱动能力）——**两端都算，但算的东西不同：能提前的搬走，必须本地的留下**。

**Q2：Runner 为什么要"串行 + keyed 去重"？**
composer 的 Recompute 由 datasyncer 的事件回调触发——**在回调栈里做大计算 = 重入/死锁风险**（vtrace/session 已踩过）；`runner.go` 把 Recompute 挪进异步队列**串行**执行，`keyed_runner` 让"同一对象连改三次"合并为一次（**去抖**）。对照第 24 课 datasyncer 的 `rewatch_timing`（退避/去抖）——**"去抖"在两端各出现一次**。

**Q3：对账 137 文件里最值得抄的两个设计？**
`VpcReconcileStage`（**阶段化**：VPC 类任务先完成首轮全量——"大局先行"）与 `repair_readonly_guard`（**只读守卫**：shadow 模式禁写——"**宁可不修，不可乱写**"，与第 17 课 fail-closed 同一取向）。**对账系统的高级形态不是"算得准"，是"知道什么时候不许修"**。

**Q4：SHADOW_MODE 从打包贯通到运行时——说明什么？**
三代的发布纪律：**演练能力被产品化**——`make` 开关打只读包（只上报不写）、代码级守卫兜底（想写也写不了）。对照一代的"发布窗口保护"（第 4 课：变更窗口停手等）：**从"停手等"进化为"影子练"**——新版本可以带着真实流量练一遍，再切换。

**Q5：五钩子生命周期里，哪一钩是"三代精髓"？**
`OnStartupSettled`——它把"Ready"从**进程态**（起来了）与**连接态**（连上了）推进到**数据态**（**首批配置处理完**）。对照第 19 课 agent 的 Handshake/Watch/Poll 顺序与第 17 课 broker 的七阶段引导：**"就绪"的定义三层递进在同一条课程线上出现三次**（进程→连接→数据），这是配置分发系统的通用"上岗观"。

**Q6：54 还是 60+？**
第 18 课材料写 54、本材料（§3.3.1/§4.2）写 60+——**无源码在盘，如实并列**；两种可能：成文时点不同（Extension 在增长）或统计口径不同（业务 Extension vs 含控制域）。存疑待考，不擅自裁一个数。

## ④ 对照卡：母机侧的"三任医生"

| 维度 | 一代 vpc_agent（材料 28；底座 agent_base 见材料 14） | 二代 vpc-agent（16 课） | **三代 vpc-host-agent（本课）** |
|---|---|---|---|
| 探测 | sdn_agent `auto_config.py`（材料自注对标） | — | **autoconfig 16 文件**（角色/驱动/Zone/IP + cgo-free shims） |
| 业务承载 | 领域 × watcher/object/check | upstream/res/downstream + CheckJob | **extension 244（每 ConfigType 一个）+ composer 49（关系学）** |
| 数据面 | 直接 iptables/netlink | SQLite + 驱动 | **dplane 74 assembler → Facade → adapter{vpcko,dpdk,fake}** |
| 对账 | 各业务手写 check | inspector + 端侧报案 | **reconcile 137（阶段化 + 只读守卫）** |
| 演练 | 发布窗口保护（停手等） | — | **SHADOW_MODE 只读包（影子练）** |
| 守护 | 各自脚本 | watchdog 重启 | **watchdog 父 + worker 子（同一二进制）** |

## ⑤ 验收题

1. 五段流水里每段各解决什么问题？为什么 composer 与 dplane 不能合并（"关系学"与"翻译学"的区别）？
2. "中心预计算 vs 本地组合"的分工线画在哪？举一个"能提前搬走"与一个"必须本地"的例子。
3. Runner 的"串行 + keyed 去重"防的两种病各是什么？为什么在回调栈里做重计算危险？
4. `repair_readonly_guard` 与 SHADOW_MODE 打包开关为什么**都要存在**（一个够了不行吗）？
5. "Ready 的三层递进"（进程→连接→数据）在本课程的三处出现各是什么课？`OnStartupSettled` 与它们的关系？
6. 54 vs 60+ 的并记处理方式为什么优于"擅自选一个"？你还见过哪处类似的材料互证（提示：第 21/23 课）。

---

*本课材料行号清单（供追问对质）：`39` §1.3（五段数据流原文）/ §1.4（Adam 双架构包 / ENV 三环境 / SHADOW_MODE 只读包 / mock-broker-yaml / quick_restart 默认）/ §1.5（二进制入口）/ §3.3（cmd 39：main/go+lifecycle.go ~1200 行 / tvs / agentctl 15 文件）/ §3.7.1（autoconfig 16：对标 auto_config.py / uname cgo-free / dpdk vhost_cmd socket）/ §3.7.2（extension 244）/ §3.7.3（composer 49：rtable_composer 汇聚三源 / rtable_update_plan 增量 plan / runner 串行防重入 / keyed 去重 / sflow 五表 / trafficmirror 四表 / remote_vpc_ref_batch / 25 测试含基准）/ §3.7.4（dataplaneaccess 28）/ §3.7.5（dpdkrecovery 7）/ §3.7.6（dplane 74：facade 透传 DeliverUpdate / eni assembler 家族 / env_detector）/ §3.7.9（migration 2：新老 Agent 滚动迁移状态机）/ §3.7.11（reconcile 137：RegisterReconcileConsumer / VpcReconcileStage / aggregate gate / repair_readonly_guard / repair_error_collector）/ §4.1（watchdog 父+worker 子同二进制）/ §4.2（五钩子启动链全文 + 灰度 9 任务 + fallback fake 全禁 panic + OnStart/OnStop）。*

*审查记录（首轮深审）：修正 1 处——④ 卡片一代列归属精确化（"材料 14/28"→"材料 28；底座 agent_base 见材料 14"，两个材料各归其位）。硬锚升级 2 处——① **§3.7.5 dpdkrecovery 补读**（原仅表头引用）：doc.go"**不做的事：不热加载、不处理反向消失**"/探测 300s 最低 60s/**restart 序列：Stop → 清 leveldb → os.Exit(1) 交 watchdog 双驱拉起**——"用清库重启换干净状态"的坦率设计；② **§3.7.4 dataplaneaccess 补读**："Exporter/Writer 统一接口供 **HTTP 导出 / 对账 BuildActualView / 高危写**三处共用"+ contract 契约（ResourceBinding/ExportResult/Writer）。清单补录 §3.7.12（`rtablekey` DiffKey 五段编码 / `startup/settle.go` = OnStartupSettled 实现处）。逐字命中：§1.3 五段数据流、§1.4 SHADOW_MODE"预下发只读包（只对账上报、不写数据面）"、§3.7.3 composer 全行（汇聚三源/增量 plan/串行防重入/keyed 去重/五表四表/25 测试含基准）、§3.7.11 四件（RegisterReconcileConsumer/VpcReconcileStage/aggregate gate/readonly guard）、§4.2 五钩子全文、"60+" 与 18 课"54" 并记。*

*审查记录（二次深审）：R1 修正自查在位（"不做的事"/"Exporter/Writer"/"材料 28；底座"，grep 3 处命中），"材料 14/28"残留仅审查记录回溯（@159）——**零尾差、零新错**。*
