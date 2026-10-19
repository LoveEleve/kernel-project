---
title: "12-源码级推导：无源码下的机制重建（两阶段 ready / GLOBAL 路径 / 旁路执行 / 治理时序 / CA 一致性 / 双源分工）"
series: "VPC 5.0（Gen3）架构系列"
previous: "11-capstone.md"
next: "README.md"
source_refs:
  - "本篇全部为【推导】——锚点来自 11 章正文与 docs-by-topic；无源码，每条附证伪路径"
  - "方法与标注：五段式（问题→naive→But→Therefore→代价）+ 约束上锁（L0-L3）+ 证据分级"
---

# 12-源码级推导（无源码下的机制重建）

> 定位：11 章之后"发散篇"。对已识别的**源码级盲点**做约束反推：锚点（实证碎片）→ 约束（不可违）→ 推导（伪代码/时序）→ **证伪路径**。
> 纪律：本文件**全部标【推导】**（含置信度），与【实证】严格分离；推导必须给出"为什么别的形状不行"或备选多解+判别信号。

## §0 方法：无源码怎么"发散"才可信

```
① 锚点收集：文档里的机制碎片（状态枚举/文件清单/一行描述）
② 约束上锁：L0 物理（万级 agent 重连是真实的）/ L1 数学（放大倍数可算）/ L3 域不变量（版本域必须唯一）
③ 形状收敛：给定约束，机制形状的可选空间 → 排他或列备选；备选给"判别信号"
④ 证伪路径：写明"去看哪个文件/什么现象能推翻本条"
```

三条判定线：**排他**（其余形状违反约束）→ 高置信；**多解**（都能满足）→ 列备选标判别信号；**未锁**（约束不够）→ 标【开放】不硬编。

## §1 问题一：broker 启动为什么要"两阶段 ready"？【推导·中高置信】

**锚点**：六类元数据 bootstrap → generation apply → SET reconcile → Watch 对齐 → READY；期间 **tRPC 不 listen、心跳不发送**；状态枚举 Ready/Accepting/Serving/Draining；ctrlSetReplica **fail-closed**；"两阶段 ready 与归属判断"（知识库目录级）。

**Naive**：六类元数据拉完就 listen——反正请求来了再判断。

**But（naive 死在哪）**：broker 的地址怎么被 agent 发现？经 **vpcBoker 元数据 → agent 的 DiscoverService**。如果**没对齐就先 listen、心跳先发**：
- agent 会把本 broker 选进候选池并建立会话——而此时"我负责哪些 VPC"（SetBinding + generation）还没算出来；
- 归属判断（每个 Watch 解构 scope → 判定是否本 SET）在**旧 generation** 上执行——灰度期 resourceCtrlSet 是**多对多双绑**（VPC 同时绑旧+新 SET），旧 generation 会把"已经归新 SET 的 VPC"依然判为己有 → **灰度切换被回滚**（错误订阅）；
- 万级 agent 的握手风暴（L0：重连是真代价）白打一轮。

**Therefore（被迫的形状）**：两阶段——**①metadataReady**（六类拉取→generation apply→SET reconcile→Watch 对齐；此间不 listen/不心跳，不在任何候选池里）→ **②servingReady**（listen → Accepting：可握手，但按 role 做 serving 校验）。**Accepting→Serving 的具体触发未锁**【开放】——候选：按 role（host/report）就绪，或按会话订阅物化；不硬编。归属判断 = 请求携带 scope → 反查**本 generation** 的 SetBinding/hash → 非本 SET 返回明确错误（让 agent 重发现，而不是静默接受）。fail-closed：ctrlSetReplica 状态**未知 ≠ 可用**——按不可用处理。

**代价**：启动变慢（对齐后才可服务）；状态机×双 role 的复杂度。**证伪路径**：`internal/metadata/` 七阶段状态机定义、`service_status.go` 的转移触发点、`server/trpc/server.go` 的 WaitReady 条件——若发现 listen 早于两次对齐，本条被推翻。

## §2 问题二：一条 GLOBAL 配置在两级 SET 里怎么走？【实证（写侧展开，07 §3.4）+ 推导（放大会计）】

**锚点**：CtrlSetRouter 4 scope——GLOBAL=**广播**（发所有 DISTRIBUTE SET，含 state=SYNCING）；t_resource_ctrl_set 的 biz_scope_type=1=GLOBAL；分发层 SET=Kafka+副本。

**Naive**：GLOBAL 也按绑定表找到"某个 SET"发一条。

**But**：GLOBAL 数据的语义是"**所有 VPC 都可能引用**"（远端网关注册、全局路由类）。只发一个 SET → 其他 SET 的 agent 永远收不到 → 数据面黑洞。（L3：全局数据的可见域=全集。）

**Therefore**：写侧**一对一展开为 N 条**（每个分发层 SET 一条 binlog，无 key 轮询分布）；**SYNCING 的新 SET 也必须发**（否则新 SET 引导期缺全局数据）。分发侧：每个 fusion 快照都含 GLOBAL 分片 → 每个 broker 的 BTree 都有 → agent 侧**只有订阅了该 configType 的 Extension 才处理**（无 N 放大）。

**写放大会计**（L1）：1 次 GLOBAL 变更 = O(N_SET) 条 binlog + O(N_SET) 次快照重生成；1 次 VPC 变更 = O(1)。→ **GLOBAL 只能承载低变更频率的数据**；GLOBAL 高频 = 系统级热点（与"VPC 属性迁移至 vpcconfig（把 VPC 级属性从全局感数据下沉）"的方向一致——202608 spec，弱旁证）。

**代价**：写放大 N 倍、新 SET 引导必须重放 GLOBAL。**证伪路径**：CtrlSetRouter `resolveTargetSetIds` 的 GLOBAL 分支（是否含 SYNCING）、单条 binlog 的扇出日志。

## §3 问题三：BypassExecutor 为什么叫"旁路"？（异步首包）【推导·低置信·双解】

**锚点（弱，目录级）**：知识库"BypassExecutor **异步首包优化**、16 个 Error Sentinel 与 Event 类型汇总"；39 specs 202604 中文工作项"**Bypass 首次同步完成语义缺失**"。

**Naive**：首包（agent 启动后第一条要下发的配置）走主 Pipeline 就行。

**But（推导出的场景）**：主 Pipeline 是**按依赖图排序的批处理**（单调、正确、慢）；而"首包"决定第一台 VM 的流量何时通——它只需要**这条路径的依赖子集**，不需要等全量对齐。

**Therefore（两个候选形状）**：
- 解 A（事件级旁路）：首包事件到达即异步旁路执行（不等批次对齐），结果幂等合并回主链路；
- 解 B（依赖级旁路）：首包只解析其依赖子集（rawStore 已就绪部分）提前展开。
"首次同步完成语义"（202604 bug 修复对象）= bypass 成功但**未推进"首次同步完成"状态位** → 后续增量误判"尚未同步"（推测：新增 syncComplete 状态由 bypass 完成路径推进）。16 个 Error Sentinel = 旁路失败的分类处理（重试 / 降级主 Pipeline / 致命）。**判别信号**：知识库 17-bypass-executor-and-event-types.md 的图；202604 spec 的修复 diff（若为状态位，则解 A/B 之外还有第三条：纯观测语义修复）。

**代价**：双路径一致性负担（旁路与主路径结果合并需幂等）。**证伪路径**：同上两文件——本条**未锁**，不得当作结论引用。

## §4 问题四：分发层 SET 的上线为什么是 D-1→R-1→（D-5）→权重→观察窗口？【推导·中高置信】

**锚点**：CreateDistributeSet（D-1 全量引导：注册 Kafka+副本+可用区引用）/引用增删（D-5 变体）/R-1 骨架→组件登记→轮询→AVAILABLE/R-2 降级恢复/R-3 销毁/权重灰度（含观察窗口审批）/Drain 排水含资源迁移。

**Naive**：新 SET 建好即接流量。

**But**：SET 是**数据域**（每个副本=独立 db/redis/fusions 栈）。数据域冷启动有三个空窗：Kafka topic 未就绪（写入丢）、快照未生成（agent 拉空）、副本未对齐（版本缺口）。任一空窗接流量 = 数据黑洞。

**Therefore（时序被四道闸门锁死）**：

```
D-1: ① t_ctrl_set(type=DISTRIBUTE, state=SYNCING) ②注册 Kafka（topic 绑定）
     ③登记副本（db/redis/fusions 绑定）④挂可用区引用（D-5 增删）⑤触发 R-1
R-1: 骨架 → 组件登记（replica_register_fusion）→ 初始化轮询（ctrlSetReplica.state）
     → 全部 AVAILABLE → SET 置 AVAILABLE
灰度: 权重 0 → 渐增（UpdateDistributeSetWeight）+ 观察窗口 Approve 才放行 flow
退役: 权重→0（停新流量）→ Drain（匀速关存量会话/资源迁移）→ Destroy（批删 Kafka+副本）
```

为什么"先建新后拆旧"贯穿始终：任何时刻**旧 SET 仍是权威**，直到新 SET 快照完整 + 权重切完 + 观察窗结束。（与 05 迁移四阶段同构——治理层与数据层用同一个不变量。）

**代价**：上线/退役都是**天级流程**；收益：每步可回退。**证伪路径**：vpc-ops `flows/` 的 D-1/D-5 flow 定义文件——步骤数/顺序若不同，以文件为准。

## §5 问题五：CA 的 FetchSnapshot 怎么保证"展开结果与切回 broker 无缝"？【推导·中高置信】

**锚点**：source_versions（各上游 configType 的 Broker 原始版本）/vpcMu 持锁一致性/四级存储（rawStore/DepStore/computedStore/versionstore）/FetchSnapshot(vpc_id, config_type, zone_id)。

**Naive**：读 computedStore 返回即可；版本号最后补上。

**But**：新 binlog 到达会触发**重算**（写 computedStore + 更新 source_versions）。若读操作在"数据已更、版本未更"或"版本已更、数据未更"之间穿过 → agent 拿到的**版本标注与实际数据错位** → 之后按该版本切回 broker 增量 → **重复或丢**（L3：版本是坐标系，错位即漂移）。

**Therefore**：vpcMu **读写锁**（粒度=单 VPC，不是全局）+ 读路径原子：

```go
FetchSnapshot(vpc, type, zone):
    vpcMu[vpc].RLock(); defer RUnlock()
    computed := computedStore.Get(vpc)          // 展开结果
    src := versionstore.Get(vpc)                // 各 configType 原始版本
    payload := filterByZone(computed, zone)     // VPCGW 类按 AZ 过滤
    stream.Send(payload, source_versions: src)  // 数据+版本同一临界区
```

**双版本号**（关键设计；**命名系推导**——文档只称"双版本号"）：rawVer（上游原始，**对外**——对接 broker 版本域）vs computedVer（展开完成度，**对内**——判断该 VPC 是否需要重算）。对外必须用 rawVer，否则 CA 与 broker 的版本域不可比。

**代价**：持锁读挡住该 VPC 的重算（秒级）；用 per-VPC 锁把影响面压到单 VPC。**证伪路径**：设计文档 §③ "source_versions 版本号设计与 vpcMu 持锁一致性"与 §⑥ "并发一致性验证"——锁类型/粒度若不同以文档为准。

## §6 问题六：迁移期 sdn_oss 与 api-server 的 binlog 生产怎么分工？【推导·中置信】

**锚点**：sdn_oss=权威端（Saga，api/Vpc5 适配，vpc_binlog_producer_task 20 worker 重试）；api-server=VPC5.0 接入层（Saga 7 步 → 双库 → Kafka）；"迁移切片"（scanner 列 handle 清单）。

**Naive**：双写——两边都写同一份配置的 binlog。

**But**：双写=**两个版本域**。fusion 侧幂等依赖 version+scopeId；两个源头各自发号 → 同 scope 的两条 binlog 版本不可比 → 合并时"谁新谁旧"不可判。**比漏发更难治**（漏发能对账，错序不能）。

**Therefore**：**按迁移切片分域，不按对象双写**——同一 scope/configType 的版本号**单一生产源发放**；切片从 sdn_oss 切到 api-server 时，**切换点是版本交接点**（切换窗口的连续性由对账兜底）。scanner 的用途（"扫描 vpc5 对象在 sdn_oss 的直接操作 Handle 清单"）= 界定切片边界。

**代价**：迁移切换需要版本对账；产出对账报告可证。**证伪路径**：24 号文档对 scanner 的完整描述、fusion 侧是否对 binlog 来源设有判别字段——若存在"双源并存同发"证据，本条被推翻。

## §7 证伪清单（每条推导 → 一票否决的检查点）

| 推导 | 置信 | 证伪路径（看什么） |
|---|---|---|
| §1 两阶段 ready | 中高 | metadata 七阶段定义 / WaitReady 条件 / listen 时点 |
| §2 GLOBAL 广播展开 | 实证（写侧）+推导 | resolveTargetSetIds 的 GLOBAL 分支（文档口径已含 SYNCING——需源码确认） |
| §3 BypassExecutor | **低·双解** | 知识库 17-bypass 文件 + 202604 spec diff |
| §4 D-x/R-x 时序 | 中高 | vpc-ops flows/ D-1/D-5 定义 |
| §5 vpcMu 原子读 | 中高 | design 文档 §③（source_versions/vpcMu） |
| §6 切片分域（非双写） | 中 | scanner 描述 / fusion 来源判别字段 |

## §8 负空间（推导不出、必须源码的部分）

- D-x/R-x 的**具体步骤编号与错误分支**（只有流程名）；
- 16 个 Error Sentinel 的**名单**；
- generation apply / 回调抑制的**算法**；
- 双源版本交接的**字段级细节**（迁移日志/边界的物理表示）；
- fusion 8 个 Lua 的脚本原文（另有推断稿 `docs/vpc-fusion-Lua脚本-完整推断实现.md`，口径：以仓库为准）。

> 本文件是"发散的纪律版"：**可以推形状，不可推数字名单**；每条推导都配了一票否决检查点——拿到源码的第一件事，就是按 §7 表逐条打勾或推翻。
