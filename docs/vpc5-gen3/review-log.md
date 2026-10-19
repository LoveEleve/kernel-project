# VPC 5.0（Gen3）系列 · 审查记录

> 每轮深度 review：① 事实主张对照 `vpc/` 材料核对（命令级证据）；② 推导与口径冲突复核；③ 跨文件一致性比对。
> 修正项直接应用并记录在此；审查过程语言只在本文件，不进正文。

---

## 批次 00（2026-10-11，全景落盘）

### 批次工作

- 材料全读：`vpc5.0-overview`（424 行）+ `vpc5.0-business-capabilities`（414 行）；
- 产出：**00-overview.md**（全景：10 子项目 / 主链路 / 关键决策 / 105 配置类型 / SET 分片 / 与 Gen2 边界）；
- 骨架：README（索引 + 语料盘点 + 系列状态）+ 本 review-log。

### 覆盖度

- 全景层从"仅零散对比（Gen2 系列 16 章）"→"独立成章"；01-09 章规划入表。

### 待办

- 00 章待首轮审查（Round 1）；
- 下一批次：01-写侧（读 docs-by-topic/34-vpc-api-server + 35-base + 41-protos + overview 相关段）。

**结论**：批次 00 落盘，待审查。

---

## Round 1 审查（2026-10-11，00-全景 全量逐条）

### 审查范围

00-overview.md 全篇 + README + 批次 00 记录，逐句对照 vpc5.0-overview（424 行）与 vpc5.0-business-capabilities（414 行）。

### 修正项（1 处，已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 00 §5 | "三代总对照表已在 Gen2 系列 16 章 §3（K8s 对比节）"——16 §3 为 K8s 对比，非三代总表 | 改为"Gen2 系列 01 章 §4（三代简表）与 16 章（业界对比）" |

### 全量核对确认（逐条）

- **§0** 六步链路（Saga 7 步 / CtrlSetRouter / Redis Lua / 快照通知 / 三向合并 / tRPC 四阶段 / Vpcsp）——对上。
- **§1** 七卡（10 子项目 / 主链路 / LSMT 类比 / 105=74+15+8+8 / 4 scope / 配置 vs 元数据）——对上。
- **§2** 痛点表 8 行（秒级 / 30-40min / reconcile / cleanconfig / 32 MERGE_CLASS / 微内核 / ops / 计算上移）——对上。
- **§3** 子项目表与依赖图——对上；**§4** 六决策（拆分 / CA / tRPC / LevelDB / ops / 元数据）——对上（含 source_versions / 降级内存）。
- **§6** 数字（105 / 54 含 58−4 与 55 口径【开放】/ 200万×50 / 每秒数十次 / 多 SET / 30+ 对账）——对上。

### 结论

**Round 1 通过**（1 项修正已应用；00-全景 全量核对完毕，无遗留）。

---

## 批次 01（2026-10-11，写侧落盘）

### 批次工作

- 材料全读：`34-vpc-api-server`（685 行）+ `41-vpc-protos`（569 行）；`35-vpc-base` 目录级（~20 包清单）；
- 产出：**01-write-side-api-server.md**（Saga 框架 / 版本生成 / 分发三件套 / 元数据驱动 / 后台服务 / Gen2 对照）；
- 链条：00 → 01；README 索引与语料盘点同步。

### 新发现（口径冲突 1 处）

- **配置数量两说**：overview/business-capabilities = 105（74/15/8/8）；protos 文档实列 = 80 biz + 15 + 9 + 12（≈116）——差异在 biz/api_server/fusion 计数，已在 01 §6 标【开放】（以 registry 实际为准）。

### 待办

- 01 章待审查（Round 2）；
- 下一批次：02-fusion（读 docs-by-topic/38 + test-good/02-vpc5-gen3 的 fusion 系列 5 篇，~3800 行——本系列最大章）。

**结论**：批次 01 落盘，待审查。

---

## Round 2 审查（2026-10-11，01-写侧 全量逐条）

### 审查范围

01-write-side-api-server.md 全篇 + README/批次 01 记录，逐句对照 34-vpc-api-server（685 行）、41-vpc-protos（569 行）、35-vpc-base（目录级）。

### 修正项（3 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 01 §0 | action 示例 "TVPCAPI.GenericConfig.SetGenericConfig" 未经验证（材料只有 `TVPCAPI.{Package}.{Action}` 格式） | 改回格式式 + "如 genericConfig 包的 SetGenericConfig" |
| 2 | 01 §1 卡 6 / §6 | "每配置 3 产物"仅适用 biz/control（api_server/vpc_fusion 为 INTERNAL 单产物） | 补范围限定 |
| 3 | 01 §7 | shard 的 FNV-1a 归属混淆（41 的分表索引 vs 34 的 shard_id 计算） | 拆分归属 + 注明层次不同 |

### 全量核对确认（逐条）

- **§0** 链路（gin→三中间件→Action 分发→Saga Handle→Worker 四步链→逆序回滚→outcome）——与 34 §4.4 逐条对上。
- **§2**（技术栈 / App 容器 / 启动流程 / watchdog MaxRetries=-1 / genericConfig 总入口）——对上。
- **§3** Saga 八件——对上；**§4** 版本三维 + Lua + version_state——对上。
- **§5** 分发三件套（Dispatcher / topic_resolver / Router / Manager 三协程）——对上。
- **§6** protos（生成链 / location 4 类 / ConfigType 五形态 / field_number / buf breaking / gen 入库 / 门禁）——对上；105 vs 116 口径冲突表述与两源一致。
- **§7**（rotation 五件 / deletion GuardFields / 35 包清单）——对上。
- **§8** Gen2 对照 5 维——与 Gen2 系列 02 §9.5 / 10 §1 口径一致。

### 结论

**Round 2 通过**（3 项修正已应用；01-写侧 全量核对完毕，无遗留）。

---

## 批次 02（2026-10-11，fusion 深挖落盘）

### 批次工作

- 材料全读：`vpc-fusion-position`（536）+ `vpc-fusion-task-queue`（768）+ `vpc-fusion-snapshot-structure`（969）= 2273 行；`38-vpc-fusion` 目录级；
- 产出：**02-fusion.md**（六模块 / 任务队列 8 Lua+三段式 / 快照结构 / 对账清理 / 故障矩阵 / Gen2 对比 / 口径与开放）；
- 链条：01 → 02；README 索引与语料盘点同步。

### 新发现（口径 4 处，均已登记）

1. **选举 Key 前缀两说**：`leader:election:{role}`（position）vs `fusion:leader:{role}`（task-queue）——【开放】；
2. **DeleteConfig 修复在 bugfix/disorder 分支**（commit 430b5ab），master 未合入——引用注明分支；
3. "4 级任务队列"标题 vs 实列 5 个队列名（High+4 Normal）——按名单引用；
4. 快照"保留 4 版"是 Gen2 口径；Gen3 为 `MaxSnapshotCount=configMeta.SnapshotCount`（eni=10）。

### 待办

- 02 章待审查（Round 3）；
- 下一批次：03-broker（读 test-good/02-vpc5-gen3 的 trpc-stream-protocol 682 + vpc-broker-consumption 475 + docs-by-topic/36）。

**结论**：批次 02 落盘，待审查。

---

## 批次 02 补充（2026-10-11，资料补全）

### 背景

用户指出"资料可能不全面"——核实成立：batch 02 首版**未读** fusion 修复三篇（bugfix_disorder 215 / core-fixes 574 / accompanying-fixes 606，共 1395 行）。

### 补读与扩章

- 三篇全读 → **02 新增 §8「修复全景：bugfix/disorder」**：分支事实（196 文件/50 commit/7 周）、"disorder"含义、5 commit 演进、**16 工程问题完整表**、7 附带 bug 精要（LevelDB 复活/双接口/自指死循环/continue 反转/缓存即真相/限频参数/14 运维接口）、2900 行测试 + LeaseFailover；
- source_refs / 掌握卡（+第 9 条）/ README 语料盘点同步。

### 仍待读（登记）

- `docs/vpc-fusion-Lua脚本-完整推断实现.md`（298，推断实现）；`failure-scenarios`（781，→ 06）；`38-vpc-fusion`（666，组件全文）；`interview/vpc5-面试文档`（24 篇，→ 08）；精要卡 02/03/06（浓缩版）。

**结论**：批次 02 补充完成（扩章 §8），待审查。

---

## Round 3 审查（2026-10-11，02-fusion 全量逐条 + 继续发散）

### 审查范围

02-fusion.md 全篇（230 行，含 §8 修复全景）逐句对照六篇源文档（position/task-queue/snapshot-structure/bugfix_disorder/core-fixes/accompanying-fixes）。

### 发散补读（4 篇面试专题 + 新细节）

`interview/vpc5-面试文档` 的 fusion 四专题全读（快照/对账/初始化清理/消息管道，共 ~300 行）——**发现 1 处口径冲突 + 6 组新细节**：

| # | 内容 | 处置 |
|---|---|---|
| 1 | **对账口径冲突**：position"对账触发补齐/清理/重建" vs interview"对账只报告、修复走主路径/显式规则（Redundant 先告警不自动删）" | 02 §5 改写为 interview 口径 + 标【开放】 |
| 2 | 三类差异处理细节（Missing >2 周期升级 / Redundant 防 DB 写窗口误删 / Mismatch 复查） | → §5 |
| 3 | 两阶段 Diff（左对右 / 右对左不合并） | → §5 |
| 4 | 对账规模计算（~61ms/VPC → 3.4h；实际 3-9h 瓶颈=限频）+ 四道防范 + 降噪三件套 | → §5 |
| 5 | InitState 四态 + 首启限频 27.8h + 消息管道（攒批 1000 / 幂等 / 恢复 +40s） | → §5 |
| 6 | cleanconfig 六连删 + 生命周期串讲（创世纪/日常/监视/收尸） | → §5 |

### 修正项（1 处，已应用）

- 02 §5 从"对账三差异→修复"改写为"验证路径 + 三类差异处理（interview 口径）+ 规模/防范/降噪 + 生命周期"，并登记口径差异。

### 全量核对确认（逐条）

- §8 修复全景（196 文件/50 commit/7 周/5 commit 演进/16 问题表/7 附带 bug/测试）——与三篇修复文档逐条对上；
- §0-§4、§6-§10 与六篇源文档对上（含选举 Key 口径差异、DeleteConfig 分支注）。

### 结论

**Round 3 通过**（1 处改写 + 6 组补读落章；02-fusion 全量核对完毕）。

---

## Round 4（2026-10-11，failure-scenarios + Lua 推断补读）

### 补读

- `failure-scenarios`（781 行全读）：10 场景 × 恢复时间线 / 7 故障点 / 全局单点 3（Kafka/Redis/MySQL）/ 组合故障 3 / 单点与恢复时间面试口径；
- `docs/vpc-fusion-Lua脚本-完整推断实现`（298 行全读，材料自标"推断稿"）：8 脚本完整实现 + 设计深一层（Ownership 校验在 Lua 内 / requeue 带 lease 校验 / claim 的 ARGV 语义）。

### 落章（3 处）

| # | 位置 | 内容 |
|---|---|---|
| 1 | §2 | 选举 Key 口径裁决线索（failure-scenarios 注明"面试统一用 `leader:election:`"） |
| 2 | §6 | 故障全景表（10 场景 / 7 故障点 / 全局单点 3 / 恢复时间 / 组合故障） |
| 3 | §3 | Lua 推断稿的设计深一层 + "推断非源码"性质声明 |

### 结论

**Round 4 完成**（fusion 材料基本读尽；仅余 38 全文与其余面试专题/题库——归 06/08）。

---

## Round 5（2026-10-11，38 关键段 + 3 专题补读）

### 补读

- `38-vpc-fusion` 关键段（服务层 58 文件 / 启动三阶段 Checker→Initializer→Worker / 存储 47 文件 / health + 降级内存）；
- 面试 3 专题：故障场景手册（10 场景总表 + 三句话口径）、分片迁移与测试质量（ShardId / 四阶段 / DeleteConfig 测试反思）、分布式深水区（选举 Lua / Fencing / 背压 / 冷启动 / 降级分级）。

### 落章（4 处）

| # | 位置 | 内容 |
|---|---|---|
| 1 | §2 | initconfig 两阶段（Phase1 全局串行 / Phase2 按 VPC 并行） |
| 2 | §3 | 选举续约 Lua（防脑裂）+ 分区双主兜底 + Fencing 未做理由 + 选举参数口径 |
| 3 | §5 | LevelDB 降级内存 + 冷启动/crash recovery 判定 + 降级分级（P0 fail-stop / P1 部分） |
| 4 | source_refs | 3 专题 + 38 关键段 |

### 登记（→ 后续章）

- **分片迁移**（ShardId 间接层 / 三层一致 / 迁移四阶段 双写→双读→切流→清理 / per-agent 5%×20 天 / 跨 SET 迁移窗口 1-5s / 测试体系与 DeleteConfig 反思）→ **05 章**；
- **背压全链路（6 层）+ 容错总装四层（重试/租约/降级/对账）** → 07 章。

### 结论

**Round 5 完成**（fusion 语料读尽：38 关键段 + 7 篇面试专题；仅余 38 尾部清单与题库 → 归 08）。

---

## Round 6（2026-10-11，材料之外：业界坐标章落盘）

### 背景

用户问"有没有材料没有的、你还能补充的"——确认六类可补：业界映射 / 一致性命名 / 边界评审 / 容量账 / 演进线 / 沙盘题。

### 决策（①+②+③ 分层）

- **① 每章"外部对照"小节**（就近放：SQS/Temporal→02 §3、xDS→03、Flink→02 §4）——从 03 起随章写，02 回头补；
- **② 独立章 09-external-lens.md**（跨章内容：五组映射 / 一致性命名 / TLA+ 不变量 / 六条边界评审 / 容量反推 / 演进线 / 五道沙盘）；
- **③ review-log 审计痕迹**（本条）。

### 落盘

- `09-external-lens.md`（全篇【外部】标注，与【实证】严格分离）；
- README：原则加【外部】约定；索引插 09（总纲顺延 10）；阅读顺序更新。

### 结论

**Round 6 完成**（材料之外章落盘；① 待 03+ 随章补、02 回补）。

---

## 批次 03（2026-10-11，broker 落盘）

### 批次工作

- 材料全读：`vpc-broker-consumption`（475）+ `trpc-stream-protocol`（682）= 1157 行；`36-vpc-broker` 目录级（3 proto 文件确认）；
- 产出：**03-broker.md**（ReplicaRouter / 三棵 BTree / 三向合并 / 16-Worker / 四阶段协议 / 7 态 / 断线三层保证 / Gen2 对比 / **xDS 外部对照**）；
- 链条：02 → 03；README 同步。

### 发现（1 处口径注）

- **ReportService 归属**：00/41 说 reporter.proto 有 ReportService；trpc 文档注明"syncer.proto 只定义 Sync+Discover 两个 service，无 ReportService"——两者不冲突（ReportService 在 reporter.proto），03 §6 已写明。

### 待办

- 03 章待审查（Round 7）；
- 下一批次：04-host-agent（33-agent-framework + 39-host-agent + overview 相关段）。

**结论**：批次 03 落盘，待审查。

---

## Round 7 审查（2026-10-11，03-broker 全量逐条）

### 审查范围

03-broker.md 全篇逐句对照 vpc-broker-consumption（475）与 trpc-stream-protocol（682）。

### 修正项（3 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | source_refs | "36 目录级"表述与事实不符（本轮并未打开 36） | 改为"头部+关键行"并实际核实（Sync/Discover/ReportService + 订阅链路有据） |
| 2 | §2 | DiscoverService 细节缺失 | 补"按 ZoneId 分组 + roles/states OR 过滤" |
| 3 | §6 | Get object_id 过滤 + 订阅建立链路缺失 | 补（Watch→WatchAsync→StartKafkaWatch→ConfigHandler→StartFusionWatch） |

### 全量核对确认（逐条）

- §0 六步链路（Discovery/Handshake/Watch/Poll/ACK/Extension）——对上；
- §1 七卡——对上；§2（ReplicaRouter 5min 隔离/单 replica 不隔离/GetLatestSnapshot 两源）——对上；
- §3（三棵 BTree/度 16/Ascend/retentionWindow）——对上；§4（三来源/三条件/completeAlign/InitFailed）——对上；
- §5（buffer 1000/5000/单协程 FIFO/hashSessionID/阻塞）——对上；§6（四阶段/6 错误码/稀疏 ACK/三级队列注/分批/sendLoop）——对上；
- §7（7 态/CAS/localVersionSeq/TOO_SLOW/DataLoss）——对上；§8（三层保证/退避参数/ReWatch 3 worker/QuickRestart/Seed Fallback）——对上；
- §9 Gen2 对比 6 维——与 trpc §10 对上；§10 xDS 对照为【外部】自撰（标注正确）。

### 结论

**Round 7 通过**（3 项修正已应用；03-broker 全量核对完毕，无遗留）。

---

## 批次 04（2026-10-11，host-agent 落盘）

### 批次工作

- 材料全读：`33-vpc-agent-framework`（793 行全读）+ `39-vpc-host-agent` 关键节（240-584）+ 概览；
- 产出：**04-host-agent.md**（框架 20 包 / 60+ Extension / Composer / DPlane 三驱动 / 三层对账 / 恢复三机制 / 迁移状态机 / Gen2 对照 / kubelet 外部对照）；
- 链条：03 → 04；README 同步。

### 新料（相对 00 的增量）

- **canary 在 configstore 层**（overlay/resolver/snapshot——数据层灰度）；
- **SHADOW_MODE 预下发只读包** + report-only 上报链路（与 auto_repair 解耦）；
- **对账 L1/L2/L3 分层** + DiffKey 格式 + VpcReconcileStage/readonly guard；
- **dpdkrecovery**（清库重启切双驱）+ **migration 状态机**（shadow→takeover）+ legacy HTTP 兼容；
- **Composer runner 防死锁**（串行 Recompute）+ update_plan 增量；
- **contracts 域契约 + 声明式注册**（descriptor）；framework specs 75 工作项 + host-agent ~130 工作项（bug 清单素材 → 06）。

### 待办

- 04 章待审查（Round 8）；
- 下一批次：05-SET 分片（resourcectrlset_priority 172 + 05-cross-cutting 分片相关 + 面试专题-分片迁移已登记）。

**结论**：批次 04 落盘，待审查。

---

## Round 8 审查（2026-10-11，04-host-agent 全量逐条）

### 修正项（2 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 标题 / §1 卡 3 / §4 | Extension 数量写"60+"——overview 口径为 54、39 目录列表 ≈57 | 改"54 个（overview）/≈57（39 列表）【开放】" |
| 2 | §6 | "44+ Assembler"——39 §3.7.6 实列 ~36 个 | 改"36 个领域 Assembler" |

### 全量核对确认（逐条）

- §0 链路（key_queue/watchrouter/composer update_plan/assembler/facade/reconciler L1-L3）——对上；
- §2 框架层（20 包/datasyncer 153/rewatch 3 worker/scope_gate 双实现/errpolicy/setsyncer 六件/75 工作项/启动流程含 Shutdown Marker）——对上；
- §3（1988 文件/60+ ConfigType/SHADOW_MODE/ENV 三环境/五二进制）——对上；
- §4（contracts/声明式注册/4 回调/级联广播/canary 层）——对上；§5（rtable 展开/update_plan/runner 防死锁/keyed_runner/域组合器清单）——对上；
- §6（Facade/Pipeline/EnvDetector/EntryDiff/capability 字段级/dataplane_write 门禁）——对上；
- §7（L1 DiffKey 格式/聚合门/VpcReconcileStage/readonly guard/leveldb spool/report-only/域任务清单）——对上；
- §8（quick_restart/dpdkrecovery 300s+清库重启/migration 相位/legacy 兼容/GWMAC 模逆）——对上；
- §9 Gen2 对照 7 维——口径一致；§10 外部对照为【外部】自撰（标注正确）。

### 结论

**Round 8 通过**（2 项修正已应用；04-host-agent 全量核对完毕，无遗留）。

---

## 批次 05（2026-10-11，SET 分片落盘）

### 批次工作

- 材料全读：`resourcectrlset_priority`（172）+ `data-sharding`（146）；引用 Round 5 已读的专题-分片迁移（迁移四阶段）；
- 产出：**05-set-sharding.md**（SET 模型 / 纯函数选主 / FlipEvent 三层通知 / 先建新后拆旧 / 工程八则 / ShardId 三层一致 / 迁移四阶段 / 测试数字 / 外部对照）；
- 链条：04 → 05；README 同步。

### 要点

- **选主是纯函数**（非分布式协商）——所有 agent 独立算出同一赢家；
- **迁移"先建新后拆旧"** + version/幂等收敛重叠窗口；
- **ShardId 间接层**（静态分片/跨 SET=shard 重映射）+ 三层存储同 shardId；
- 测试:代码 ≈3:1（E2E 五用例含连续翻转收敛）。

### 待办

- 05 章待审查（Round 9）；
- 下一批次：06-难点与事故（failure-scenarios 已读 + bugfix_disorder 已读 + host-agent/framework specs 工作项素材 + Gen2 系列 15 章方法论对照）。

**结论**：批次 05 落盘，待审查。

---

## Round 9 审查（2026-10-11，05-set-sharding 全量逐条）

### 审查范围

05-set-sharding.md 全篇逐句对照 resourcectrlset_priority（172）、data-sharding（146）、专题-分片迁移（98，Round 5 已读）。

### 修正项

**无**（本轮全量核对未发现事实 / 数字 / 引用错误）。

### 全量核对确认（逐条）

- **§0** 五步迁移旅程（选主/Flip/三层通知/先建新后拆旧/version+幂等）——对上；
- **§1** 六卡——对上；**§2**（SET 模型 / 4 scope / owner_policy_resolver / globalResourceBinding 三兜底）——对上；
- **§3**（纯函数算法 / 三路径一致 / 旧实现 map 随机序）——对上；**§4**（三场景 / 三层链路 / 保分层理由）——对上；
- **§5**（Step1/Step2 / stale 校验 / 失败语义）——对上；**§6** 八则逐条——对上；
- **§7**（ShardId 三层表 / resource_obj 映射 / 静态分片 / 重映射四步）——对上；**§8**（四阶段 / 5%×20 天 / 窗口 1-5s / 测试反思）——对上；
- **§9**（5147 行 / 15 commit / 4 天 / 3:1 / BIZ-008~012）——数字级复核对上；**§10** 外部对照为【外部】自撰（标注正确）。

### 结论

**Round 9 审查通过（零修正）**——05-set-sharding 全量核对完毕，无遗留。

---

## Round 10（2026-10-11，查漏补缺：vpc5 全库差集盘点 + 3 篇补读）

### 盘点（全库差集）

- **已读**：02-vpc5-gen3 全 12 篇；docs 33/34/36/38/39/41 关键读；面试 10 篇（7 专题 + 3 篇本轮）；横切 5 篇。
- **未读登记**：面试 ~17 篇（业务追问/沙盘/自我拷打/必背30题/模拟面试实录/分布题库…→ 08）；横切 ~25 篇（consistency / fault-tolerance / deployment-safety / observability / reconciliation / backpressure / capacity-model / migration-strategy / partition-splitbrain / ordering…→ 07）；docs 40-vpc-ops（649→07）、42-zenflow（546→备查）；test/8 PPT ×10（备查）。

### 补读与落章（3 处）

| # | 位置 | 内容 |
|---|---|---|
| 1 | 02 §3 | Lease 崩溃恢复最坏界 ≈90s（30+60）+ "为什么任务队列不用 Kafka"（顺序流不支持抢单/租约/优先级/延迟重入队）+ "Redis=效率层，正确性在 DB/对账" |
| 2 | 00 §2 | 金句"并非 ZK 不好，是角色错配" + 职责归位表述 |
| 3 | README | 语料盘点更新（面试已读 10/24；剩余去向全部登记） |

### 结论

**Round 10 完成**（vpc5 全库差集盘点完毕；00/02 补遗 3 处；剩余语料去向登记，无隐性缺口）。

---

## Round 11（2026-10-11，06 全量审查）

复核对象：06-hard-problems.md（107 行）逐条对齐素材（failure-scenarios / 16 问题表 / 专题-消息管道深挖 / 33+39 specs 清单 / Gen2 15 章 / 题库 58 题）。

### 修正（9 处）

| # | 位置 | 原 | 修正 | 依据 |
|---|---|---|---|---|
| 1 | 06 §0/§3 | "四指标全中" | "四类指标全中（新 VM 不通 / ACL 不生效 / 销毁后仍转发 / 跨 VPC 断）" | 消息管道专题 L41/61 原文"四类指标" |
| 2 | 06 §0/§3 | "发往已销毁母机" | "发到已销毁 VM" | accompanying-fixes L126 |
| 3 | 06 §2 | "Kafka 积压假延迟"（无出处） | "agent 侧 backlog=0 假阴性，2-3 分钟恢复" | failure-scenarios L258 |
| 4 | 06 §2 | "三方队列"（却列 4 个，自相矛盾） | "四方队列（init/snapshot/reconcile/clean）" | task-queue L149 并发 20/20/3/10 |
| 5 | 06 §2/§4 | "quick restart 三 bug" | "两 bugfix（scopekey 未恢复 / global set 漂移）" | 33/39 清单各 2 项，无第三项 |
| 6 | 06 §2 | "echo + metrics…（04 §2/§7）" | "metrics push + trace + echo extension（specs）…04 §2" | 04 仅覆盖 metrics push |
| 7 | 06 §3 | 案二"发现靠日志（不是告警）" | "CPU 告警 + 日志刷屏 + list-config-state + /up 超时" | failure-scenarios §8.4 |
| 8 | 06 §4/§7 | "即此而来" / "组件级故障在…" | "对照 05 §6" / "03 §8 断线三层、04 §8 恢复与迁移" | 措辞精确化 |
| 9 | 02 §5 | "四指标全中"（Round 11 溯源修正） | "四类指标全中：…（四类展开）" | 同上 #1 |

### 核实通过（勾稽）

- §5 全部事故编号：#6 两把锁 / "add 被 eat" / #5+#17 ready 误判 / #10 阈值阻止恢复 / #3 回退自伤 90 分钟 / #19 0.0.0.0 写穿 / "告警只覆盖 40%" —— 全部命中 Gen2 15 章原文。
- §4 六族 28 个工作项名——全部命中 33 §3.27 / 39 §3.10 清单。
- §3 案一链条（Put(nil)→快照残留→反序列化失败/旧路由）与案二"HR 类比"——与 accompanying-fixes L13-61/L130-227 一致。
- §6 全部 10 条口径——与题库 58 题一致（Q1/3/7/10/11/30/35/36/41/57/58）。

### 结论

**Round 11 通过**（9 处修正后；06 与 02 §5 口径同步）。

---

## Round 12（2026-10-11，07 全量审查）

复核对象：07-cross-cutting.md（155 行）vs 15 篇源文档（05-cross-cutting）逐条。

### 修正（6 处）

| # | 位置 | 原 | 修正 | 依据 |
|---|---|---|---|---|
| 1 | 07 §4 表 | "8 个 + 锁 2 个"（含糊） | "8 个任务 Lua + 锁 2 个" | concurrency-atomicity / task-queue 口径 |
| 2 | 07 §6 | "对账优化最贵也最优先"（误读——成本表实为 0 增量成本） | "对账优化最优先（0 增量成本、改代码数周）" | capacity-planning 扩容成本表 |
| 3 | 07 §11 | multitenancy → "04 §6"（04 §6=DPlane） | "02 §6（故障扩散）" | 04/01 章节表核对 |
| 4 | 07 §11 | security-design → "04 §2"（04 §2=框架层） | "—（本系列未展开）" | 同上 |
| 5 | 07 §11 | operations-day2 → "01 §5"（01 §5=分发链路） | "01 §6（元数据驱动）" | 同上 |
| 6 | 07 §11 | network-policy → "14 章"（过细） | "数据面域" | 统一口径 |

### 核实通过（勾稽）

- §0 时间线（T0→T0+9s/p50 3s）/ §2 窗口表与三误修场景 / §3 `producer.go:213-217` 引码 / §4 双态表 / §5 四类故障+26s 时间线 / §6 参数（588/166/256/10/s、5000 缓冲）/ §7 双主三场景+fencing 理由 / §8 runbook 6 步+10 指标 / §9 灰度 72h+回退条件 / §10 三层全败——逐条对 15 篇源文档一致。
- 金句（"控制面清白""你病了/哪个器官病了""多做一遍"）均源文原话；无错字（復/。。/最贵 残留检查为 0）。

### 同批附带落章（07 写作轮）

- 02 §9 重写：**分支态注记**（Hash Tag/8 Lua/三段式/ownership 均为 bugfix/disorder 分支、master 未合入；master=Pipeline+3 Lua+无 HashTag）+"选举参数裁定 10s/3.3s/30ms（三源引码）"。
- 02 §3 Hash Tag 段加内联注（指向 §9）。

### 结论

**Round 12 通过**（6 处修正；07 落盘 + 02 口径两处更新）。

---

## 勘误 · 入口链修正（2026-10-12）

**触发**：用户连续两轮挑战——"控制台怎么就直接到 api-server 了？"→"不对吧？"。
**根因**：此前引用 test-good 合成总览图（"Defensor/控制台/CLI → vpc-ops → api-server"）作为用户链路，未按全量扫描文档口径；且把 vpc-ops（运维编排）误作业务链路必经层。

**权威口径（逐条核实）**：
- `docs-by-topic/02` §1.2 三代对比表：VPC 5.0 **数据源 = "sdn_oss → Kafka protobuf"**（Gen1=sdn_oss→ZK；Gen2=sdn_oss→Ckafka→nfv-proxy）；
- `03`：sdn_oss 产出"Kafka 生产（VPC 5.0 binlog）"；
- `24`（sdn_oss）：含 `api/Vpc5/` 适配层（`@support_vpc5` 注入旧 Handle，"新旧对象模型并行"）+ skills"vpc5 对象在 sdn_oss 中直接操作 Handle 清单（迁移切片依据）"+ `vpc_binlog_producer_task`；
- `37`（团队知识库）："CGW = OSS 上层的服务接入层"；存在 `vpc5.0/03-end-to-end-config-flow.md`。

**修正后的口径**：
- 用户业务链（当前生产）：控制台 / 云 API v3 → **vpc-cgw**（声明式 Action 网关：参数校验 + CAM 鉴权 + 协议翻译）→ **sdn_oss**（Saga 业务编排、权威端；VPC5.0 对象模型适配）→ **Kafka protobuf binlog**（VPC 5.0 通道）→ fusion/broker → host-agent → DPDK；
- vpc-api-server = VPC5.0 自建**接入层**（对象模型 CRUD + Saga + 双库 + Kafka binlog），当前调用方为内部（vpc-ops 等），是 sdn_oss 的**迁移目标**；
- vpc-ops = 运维编排（不碰配置数据）。

**受影响修正（本次）**：00（§0 旅程/§1 卡 3·7/§3 尾注/§6 入口行）、01（§0 输入行/§2 上游与定位）、02（§2 定位注）、README（它是什么/掌握卡 3/系列状态）。
**保留边界**：目标态表述（`17` 号矩阵"用户/上游平台 → vpc-api-server | VPC 5.0 配置 CRUD"）登记为迁移完成后口径；当前以 sdn_oss→Kafka 为准。

---

## Round 13（2026-10-12，深查漏补缺：规模化机制补遗轮）

**触发**：用户——"一个能做到百万级/亿万级配置快速准确下发的架构，肯定没这么简单吧"。

### 差集盘点（本轮新读）

| 素材 | 规模 | 处置 |
|---|---|---|
| docs-by-topic 08（分布式架构与分布式问题） | 590 行全读 | 锁失败模式/FixBinlogVersion/6-pack → **10 章** |
| docs-by-topic 12（精确数据格式与转换管道） | 486 行全读 | 版本编码/常量表 → 10 章 |
| docs-by-topic 19（computation-agent） | 70 行全读 | CA 设计全貌 → 10 章 §5 |
| docs-by-topic 05（数据面与安全组） | 747 行全读 | 数据面 CT 版本/DFW_VER_ADD → 边界（对照 Gen2 系列 12） |
| docs-by-topic 06（监控运维进程） | 结构+关键节 | Echo/进程监督/Go watchdog → 归 Gen2 系列 17 + 10 章边界 |
| docs-by-topic 36（broker 文件级） | 定点 grep | Drainer/服务状态机/六类 bootstrap/两阶段订阅 → 10 章 §3 |
| docs-by-topic 40（vpc-ops 文件级） | 定点 grep | 两级 SET D-x/R-x/观察窗口/权重灰度 → 10 章 §2 |
| 42-zenflow | 头部确认 | **AI 开发平台（与 VPC 无关）——除名备查** |

### 判定：系列缺失的规模化机制（本轮补进 10 章）

1. **两级 SET 治理**（分发层 SET vs Broker SET；D-1/D-5、R-1/R-2/R-3、权重灰度、观察窗口审批、t_asset）；
2. **Broker 排水与状态机**（Ready/Accepting/Serving/Draining + Drainer + WaitReady/fail-closed + 七阶段 bootstrap）；
3. **FixBinlogVersion 迟到重编号 + 6-pack 版本协商**（07 §3 只写了快照纠正——补"另一半真相"）；
4. **CA 设计深挖**（四级存储/四阶段订阅/双版本号/vpcMu）；
5. **锁失败模式四件套**（inode/ZK 雪崩/无 fencing/无续约）。

### 产出

- 新增 **10-scale-mechanics.md**（规模化与治理机制补遗）；capstone 顺延为 **11-capstone.md**（09.next 与 README 索引/阅读顺序同步）。
- README 语料盘点 + 系列状态更新（13 文件 / 2076 行）。

### 边界

- 36/40 为文件级证据（流程细节待源码）；CA 为设计文档口径；BypassExecutor 仅知识库目录级——均已在 10 章标注。

---

## Round 14（2026-10-12，10 章全量审查）

复核对象：10-scale-mechanics.md（118 行）vs 36/40/19/08/12 素材逐条。

### 修正（5 处）

| # | 位置 | 原 | 修正 | 依据 |
|---|---|---|---|---|
| 1 | §0 A④ | "默认流量给新 SET 权重 0"（推断未标） | 删除该推断，保留"调权重→观察窗口审批" | 40 文件级无此表述 |
| 2 | §2 表 | "可用区引用 = 哪些 AZ 消费"（推断未标） | 加"（推测：…）" | 标注纪律 |
| 3 | §3/§1 | "Ready→Accepting→Serving→Draining"（顺序为推断） | "状态枚举 Ready / Accepting / Serving / Draining（退出态）" | 36 原文为枚举非流转 |
| 4 | §3 | "启动编排（七阶段）"（归属不清） | "启动编排（MetadataManager 七阶段状态机）" | 36 原文七阶段属元数据状态机 |
| 5 | §1 卡 3 | 同步 #3 | 同步 #3 | — |

### 核实通过（勾稽）

- §0 场景 A 全部流程项（D-1/D-5/R-1/R-2/R-3/审批/t_asset/默认 OFFLINE/Drain 含资源迁移）与 40 文件级描述一致；
- §0 场景 B / §4：FixBinlogVersion 公式、"负载均衡>顺序性"原文、ShiftSteps=4、Group="-1"、6-pack 五分支、GetRange(peerVer+1) 边界——与 08 §4/§9.4、12 §一/§八一致；
- §3：Drainer（StartDelay/DrainDuration）、service_status 枚举、六类元数据清单、fail-closed、两阶段订阅、DistributeSetManager、specs 2319——与 36 文件级一致；
- §5：CA 全部条目与 19 一致（agent_role/BrokerRole/四级存储/四阶段/双版本号/vpcMu/七场景/仓库 2 文件）；
- §6：四锁失败模式与 08 §三逐条一致（10s 窗口 / AutoRenew=false / Mediator 60s / 40s 锁清理 / ha.py 约 6s 仲裁）。

### 结论

**Round 14 通过**（5 处修正）。**10 章定稿**——系列剩余：11-总纲（计划）。

---

## Round 15（2026-10-12，全系列架构一致性审计）

**范围**：00-10 全 13 文件（勘误 + 补遗后的架构叙述一致性 + 完整性）。
**方法**：全库 grep 归零法（旧口径残留）+ 交叉机制比对（两阶段订阅/乱序/快照锁/CA/vpc-ops）。

### 通过项（勾稽）

- 入口链：00/01/README 已统一（控制台/云API → cgw → sdn_oss → Kafka）；03/04/06/07/08/09 无残留旧口径（grep 归零）；
- 关键术语出现位置受控：`sdn_oss` 仅 00/01/02/README（均为勘误后语境）；`接入层` 全系列一致；**Defensor 此前全系列缺失**（本次补入 10 §2）；
- 交叉机制一致：两阶段订阅（03 §6 补遗 ↔ 10 §3）、乱序（07 §3 ↔ 10 §4，本次加交叉引用）、快照锁 10min（07 §6 ↔ 10 §6）、CA（00 §4.2 ↔ 10 §5）、vpc-ops（全系列均"运维编排，不碰配置数据"）；
- 数字口径：105/116（105 主口径）；4 SET×3 副本（07）与分发层副本（10）兼容。

### 修正（3 处）

| # | 位置 | 修正 | 依据 |
|---|---|---|---|
| 1 | 05 §2 | 补"SET 两型"注记：`t_ctrl_set.type` 1=分发层 SET / 2=Broker SET；VPC 绑定做在 Broker SET 维度（`t_resource_ctrl_set.ctrl_set_id → t_ctrl_set(BROKER_SET)`）；分发层治理 → 10 §2 | 04 §1.1 + 40 |
| 2 | 07 §3 | 乱序段尾补"（Gen2 侧迟到 binlog 重编号 FixBinlogVersion——见 10 §4）" | 10 §4 |
| 3 | 10 §2 | 补 vpc-ops 上游定位（"对外向 Defensor 等上游变更平台暴露业务语义 API"） | 40 |

### 结论

**Round 15 通过**——架构叙述层面全系列一致（13 文件）；剩余：11-总纲。

---

## Round 16（2026-10-12，收官审查：11 章全量）

复核对象：11-capstone.md（169 行）vs 13 号文档 + 系列 00-10 全章。

### 修正（4 处）

| # | 位置 | 原 | 修正 | 依据 |
|---|---|---|---|---|
| 1 | §0 图 | "54+ Extension" | "54 Extension"（口径统一，另见 §7） | 00/§7 口径 |
| 2 | §0 图 | "三向合并（无空洞对齐）" | "三向合并（版本连续对齐）" | 03 §4 术语 |
| 3 | §2.3 | "MySQL 5 张表：vpc/subnet/route + 1 张"（4+1≠5） | "vpc/subnet/route/ACL + 1 张" | 13 §3.1（5 表含 ACL） |
| 4 | §4-1 | "max+1 ≥ 快照后首增量" | "max(快照侧版本)+1 ≥ Kafka 未对齐最小版本" | 03 §4 对齐条件 |

### 核实通过（勾稽）

- §0 一页图逐元素对源（Defensor/cgw/vpc-ops/sdn_oss/api-server/Kafka 无 key/fusion 六模块/broker 状态机+Drainer/agent/CA/三驱动/对账环/版本链）——全部一致，勘误口径无残留；
- §2 复刻指南全条目对 13 号（不可移植障碍/测试路径/四组件≈7500 行/5 表/etcd 树/对账 Scheduler 288 次+ConfirmCount≥3/9 步验收/L1-L4）；
- §3 决策账 11 行、§4 不变量 8 条、§5 坑单 10 条——逐条回溯源章一致；
- §6 系列地图 / §7 开放总账——与各章边界同步（105/116、54/57、选举两版、CA 无代码、分支态、入口、灰度 0%）。

### 结论

**Round 16 通过**（4 处修正）。**全系列 00-11 收官**——14 文件；后续仅剩"按需补读"（05-cross-cutting 余篇 / 面试余 7 篇 / 43-46 ZK 工具族）。

---

## Round 17（2026-10-12，12 章全量审查——推导类章节标准）

复核对象：12-source-derivation.md（148 行）。审查标准：①锚点真伪（对照源文档）②推导链自洽（无内部矛盾）③置信度与证伪路径诚实。

### 修正（4 处）

| # | 位置 | 原 | 修正 | 依据 |
|---|---|---|---|---|
| 1 | §1 | "Watch 对齐完成后转 Serving"（**内部矛盾**——阶段 1 已含 Watch 对齐） | "Accepting→Serving 具体触发**未锁**【开放】（候选：按 role 就绪/按会话订阅物化）" | 36 原文："七阶段…Watch 对齐后 READY；期间 tRPC 不 listen" |
| 2 | §2 标题 | "【推导·高置信（写侧放大）】" | 拆注："写侧展开=**实证**（07 §3.4）；放大会计=推导" | 证据分级纪律 |
| 3 | §5 | rawVer/computedVer（自创命名未标） | 补"**命名系推导**——文档只称'双版本号'" | 标注纪律 |
| 4 | §7 表 | §2 行"高（写侧）/（含 SYNCING?）" | "实证（写侧）+推导 / 文档口径已含 SYNCING——需源码确认" | 与 #2 同步 |

### 核实通过（勾稽）

- **锚点逐条对源**（无虚锚）：36（六类/七阶段/fail-closed/状态枚举/WaitReady）、37（两阶段 ready 与归属判断/BypassExecutor 目录级）、07 §3.4（GLOBAL→所有 DISTRIBUTE SET 含 SYNCING——**写侧实为实证**）、04（biz_scope_type）、40（D-x/R-x/审批/权重/Drain 含资源迁移）、19（source_versions/vpcMu/四级存储/ZoneGwIPs 按 zone 过滤）、24+08（vpc_binlog_producer_task / 20 worker 重试）、39（202604"Bypass 首次同步完成语义缺失"中文工作项）；
- **推导链自洽**：§2 写放大会计（O(N_SET) vs O(1)）、§5 临界区论证（版本错位=坐标系错位）、§6 单一版本域论证（双写=不可比错序）——逻辑成立；
- **置信诚实**：§3 保持低置信双解+第三可能（观测语义修复），§1 触发点转【开放】——符合"推形状不推名单"纪律。

### 结论

**Round 17 通过**（4 处修正）。12 章定稿——系列全 15 文件，后续仅"按需补读"。
