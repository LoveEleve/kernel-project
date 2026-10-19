---
title: "08-agent-broker 内部机制：合并、版本与快照"
series: "VPC 二代（Gen2）架构系列"
previous: "07-interview-wrapup.md"
next: "09-device-agent-echo.md"
source_refs:
  - "vpc/docs-by-topic/16-AgentOS.md"
  - "vpc/docs-by-topic/15-agent-broker（VPC Agent Broker 服务）.md"
  - "vpc/VPC控制面.md（精确数据格式章 / agent-broker 存储与心跳章）"
  - "vpc/docs-by-topic/10-VPC 控制器——Review 补遗：控制面差异、框架内核、完整生命周期.md（637 行全读：agentos 真实结构 / 版本细节——补遗 §3/§7）"
---

# 08-agent-broker 内部机制：合并、版本与快照

> 标注：【实证】= 材料有据；【推导】= 材料无实现细节，按约束推导（可证伪，方法见 06）。

## §0 它干了什么（例子先行）

一条 rtable binlog 落到 broker 内部，要走完这几步（每步对应一个数据结构）：

```
ZK 子节点变更（/BINLOG/12345/RTABLE/...）
 → BinlogHandler：子节点监听与分发
 → NextGenRemoteGet：拉取（快照 or 最新 binlog；watch/sync 模式）
 → BinlogParser：文本行 → UniObj（反射映射 + 默认值填充）
 → MergeUniObj：增量合并进快照对象集 → ObjsWithIdx
 → Res 写 SQLite + 内存（推进 per-vpc 版本）
 → 心跳按 peerVer 打包（6 种 Pack）→ 回给母机 agent
```

## §1 掌握卡（5 条）

1. **上游核心是 NextGenRemoteGet**：泛型远程拉取，四种用法（快照 / 最新 binlog / watch / sync）——决定"拉什么、怎么续"。
2. **两个合并/重放件**：MergeUniObj（增量→对象集，产 ObjsWithIdx）；binlog_replayer（快照+增量→**任意版本**视图，带 vpcSnapshot 缓存）。
3. **版本模型是 per-vpc**：Version 表按 res 表名派生；全局类型（viptgw/remotegw）以 FakeVpc=-1024 维护，**在 SaveBinlog 阶段拆分传播**到各 vpc 版本表。
4. **解析器吃文本**：BinlogParser（文本行 → UniObj 反射映射；分隔符以 `_` 模板为准——材料另一处写作 `|`，为记号）；SnapshotParser（快照文本，DevType 分类）。
5. **worker_snapshot 记"水位"**：每个 worker 已同步到的 binlog 位点（材料原文标注为"推测"）——FastStart 的远端版本判定来源【推导】。

## §2 上游：NextGenRemoteGet（拉什么、怎么续）

| 用法 | 场景【推导】 | 数据源 |
|---|---|---|
| 快照 | 冷启动 / 落后太多 | CRS 分片快照 |
| 最新 binlog | 增量追平 | ZK binlog 子节点 |
| watch | 常驻监听 | ZK 子节点变更事件 |
| sync | 兜底全量对齐 | 快照 + binlog 组合 |

- 驱动器（driver/north）：**PreGenZkDriver（一代 ZK）+ NxtGenPartZkDriver（二代 ZK）** + CRS / trpc client——broker 同时能读两代（过渡期）。
- 【推导】为什么四种而不是一种：冷启动要"最少请求拿全量"（快照），常驻要"最省通知"（watch），落后要"精确续传"（binlog），异常要"可对齐"（sync）——四种对应四种成本/一致性权衡。

## §3 合并与重放（实现推导）

### MergeUniObj：增量合并算法【推导】

- **输入**：快照对象集（base）+ 按版本升序的 binlog 列表；**输出**：ObjsWithIdx（对象集 + 索引）。
- **步骤**（被约束逼出来的形状）：
  1. 以对象 key 建 map（索引 = key → 对象）；
  2. 逐条 apply：I 置 / U 改（只覆盖变更字段——binlog 的 `旧>新` 提供了字段级信息）/ D 删（对象 + 索引同步移除）/ R 整体重读标记；
  3. 版本推进 = 最后一条 binlog 的版本。
- **为什么输出带 Idx**：下游要写 SQLite（按主键 upsert）和建缓存（按 idx 查）——索引在合并时顺手产出，避免下游再扫。
- **幂等**：同版本重复 apply 无副作用（I 覆盖、U 覆盖同值、D 重复删为空操作）——这是心跳重发、对账重放都安全的前提。
- **幂等边界【推导】**（证伪检查补充）：
  1. 必须按版本**升序** apply——U 的 `旧>新` 基于前一版本，乱序应用会错；
  2. U 需**无条件覆盖新值**（或旧值不匹配时降级覆盖）——若做乐观校验（旧值匹配才改），重复 apply 第二次会因旧值已变而失败；
  3. I 不能携带一次性副作用（时间戳刷新、计数器自增）——否则重复 apply 非幂等；
  4. D 对已删除 key 为空操作；同 key 被新 I 复用（重建）时，靠版本序区分新旧生命周期。
- 与 VPC5.0 对比：VPC5.0 是"快照 + 历史增量 + 实时增量"三向合并（BTree 版本索引）；二代是"快照 + 增量"二向 + 重放缓存。

### binlog_replayer：为什么需要"重放到任意版本"【推导】

- **问题**：心跳的 peerVer 是**任意值**（agent 可能落后 1 条，也可能落后 5000 条）。broker 必须能构造"从 peerVer 到当前"的增量，或"该版本的全量视图"。
- **不能**为每个版本都存快照（空间爆炸）→ 存"基快照（版本 S）+ binlog 序列（S+1..localVer）"。
- **重放** = 基快照上 apply 到目标版本；**vpcSnapshot 缓存** = 已重放结果（避免同一 VPC 反复重放）。
- 【推导】缓存粒度与失效：按 (vpc, 基版本) 缓存，binlog 追加时只增量更新缓存尾——即"缓存 + 增量段"结构。
- 【推导】缓存的三条边界路径（证伪检查补充）：① 未命中（目标版本 < 缓存基版本）→ 从基快照重放；② 淘汰（LRU）→ 退化为未命中，正确性不变、性能退化；③ 版本断裂（binlog 空洞）→ 重放中断 → 走 Snapshot 全量（与 6 Pack 的 BinlogIncomplete 同源）。

### 空洞与迟到 binlog 的真实处理（docs-by-topic/10 补遗）

- **空洞**（binlog 不连续）：`latestContinuesVer` 停在断点前 → **不更新镜像版本** → 等 nfv-proxy 补产——**broker 不自己处理空洞**（与上面重放路径的"断裂→Snapshot"分层：连续同步等补产，按需重放走兜底）。
- **迟到**（版本 < currVer）：`onDelayedBinlog` → `fixVer = max(resVer, currVer) + 1` → `FixBinlogVersion` 重编号 → 正常回放。

## §4 版本模型与全局传播

- **per-vpc 版本**：`Version` 表（由 res 表名派生 snapshot / binlog / snapshotversion / version 四类表名）；每 VPC 每资源类型一条版本链。
- **全局版本**（FakeVpc=-1024）【实证 + 推导】：
  - 全局数据（viptgw/remotegw）不属于单个 VPC，用 -1024 存储；
  - **SaveBinlog 阶段把全局版本拆分传播到各 vpc 粒度版本表**——因为消费侧（agent/心跳）按 per-vpc 版本追踪；不传播的话"全局变了但 VPC 版本没动"，agent 永远不会拉。
  - 【推导】传播规则：全局 binlog 版本 v → 对受影响的 vpc 集合，在其版本表写入 ≥v 的水位（或把 binlog 复制进该 vpc 的 binlog 表）——写放大换消费模型统一。（传播范围——受影响的 vpc 集合如何界定——材料未定义，待源码核对）
- **修复版本**：对账修复 binlog 版本 = 内部版本 +1（InspectFixStepVer=1）；Group="-1" 标记。
- **版本编码**：×10000（ShiftSteps=4）；"151708.0001" 浮点样式。

## §5 存储与缓存（每 worker）

```
SQLite 3 库: current / binlog / snapshot（+ state.db 全局）
路径: /dev/shm/agent_broker_db/{current|binlog|snapshot}/db{idx} + state.db
current: 24 种表 + version_{表名}
缓存: event_cache（写入队列 + 按 vpcId 内存缓存）
      query_cache（binlog 区间查询结果缓存）
水位: worker_snapshot/upstream（每 worker 已同步位点）
分片特例: worker 编号 -1=白名单 VPC / 0 / 2^32-1 → worker 15（GlobalWorker）
```

- 【推导】为什么 event_cache 按 vpcId 组织：事件按"同 VPC 顺序"应用，缓存按 vpcId 分组可直接支持"某 VPC 的待处理队列"。
- 【推导】query_cache 的存在理由：心跳按 (vpc, peerVer..localVer] 区间取 binlog——短时间多次心跳（重连/多 agent）会重复查询同一区间。

## §6 启动同步顺序（SyncOnStart）

材料顺序：**先最近变更 VPC → 再全局 ext 补齐（全新启动清表重建 + 传播全局版本）→ 最后为每 worker 每 ext 构建 SQLite 缓存**。

【推导】为什么是这个顺序：
1. 先"最近变更"——最快让热点 VPC 可用（用户感知最强的部分先恢复）；
2. 再"全局数据"——全局是所有 VPC 的依赖，必须在局部缓存构建前就位；
3. 最后建缓存——缓存是派生数据，放最后避免被前面的写入作废重做。

## §7 补遗：agentos 框架真实结构（docs-by-topic/10）

> 材料明确纠正：'Extension 生命周期（Init→Subscribe→OnChange→Reconcile→Stop）'与'ScopeGuard→Retry→Raw 包装链'**不属于 agentos**（旧文档张冠李戴）——该链实为 **vpc-agent-framework（vpc5.0 host-agent）** 的真实形态（07 修正 #2）。agentos 的真实结构：

- **真实生命周期 6 阶段**：RegistExt（三件套 Res/Upstream/Downstream + 单层包装）→ InitWorker（异步初始化）→ **RemoteGet(setWatch=true)**（订阅 = 拉数据 + 注册 ZK Watch）→ **BinlogHandler.DoChange**（即 OnChange）→ **Inspector 周期对账**（10s tick；完整对账 = 30×10s = 5min——07 修正 #15）→ Stop。
- **单层包装器**：ResWrapper（记录最近变更对象 ID——对账 diff 跳过"刚改过的"对象，避免假阳性）/ DownstreamWrapper（同上）/ UpstreamWrapper（调用审计：>2s warn + 耗时直方图）。**Retry 不在包装层**——在 SQLite gorm callback（"database is locked" 重试最多 10 次，sleep 100ms~1s）。
- **Worker 并发模型（核心）**：每 workerIdx 三个 channel（ZK 回调 / 跨 worker Res 操作 / 任意函数）；一个 eventHandler goroutine **串行消费**——同一 worker 天然无锁（agentos 最核心的并发设计）。
- **口径备注**：本篇 §2-§6 未使用被纠正的旧口径（已核）。**agentos 指代已闭合（Round 10）**：主文档 §2.5 ★ 更正——agentos 是 Gen2 **agent-broker 的底座框架**（Go），"VPC 5.0 Agent 框架"系旧文档错误；**Gen2 设备 Agent 就是 Gen1 的 vpc_agent（Python，同一进程同一套代码）**，agentos/agent-broker 只是新增的中间代理层。残余：代际表并列 "agentos (Go)"、04-others 称其 Gen3——并存形态待源码。

## §8 钩子

- 心跳 6 Pack / CheckJob / 分片 → **04**。
- 版本序、组闭合、12h 假设的推导 → **06**。
- 设备侧如何消费 → **09**；对账与恢复 → **10**。
