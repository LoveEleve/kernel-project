# 开源精读 20 · vpc-fusion（上）——配置中转站的心脏

> 课次：S2b 第三课（vpc-fusion-position 536 行 + vpc-fusion-snapshot-structure 969 行之半）
> 坐标：第 18 课立 5.0 全景（fusion = "配置中转 + 快照构建 + 对账 + 清理"八字），本课展开**它到底怎么转**；第 21 课再入任务队列可靠性（bugfix/disorder）。
> 一句话预告：**它是 Gen2 nfv-proxy 的替代者，但职责大了三圈**（材料原话"替代了 Gen2 的 nfv-proxy，但职责更大"）。
> 纪律：断言落材料文本。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **配置中转站** | fusion 的一字定位：消费 Kafka binlog → 存 MySQL；**它不直接服务 agent**（agent 与它隔着一个 broker） |
| **t_generic_config** | MySQL 里的配置全量表（每类资源的当前态） |
| **t_generic_snapshot** | MySQL 里的快照表（LevelDB 序列化产物落地处）——"generic"一词又一次出场（第 18 课"红利"注脚的延续） |
| **0 号快照** | 无旧版本时的全量快照（`loadConfigFullData` 全读全建） |
| **增量快照** | 有旧版本时：`mergeBinlog` 只读版本差 → 变化的 shard 重建、**未变化的 shard `cloneSnapshot` 只改版本号** |
| **四个 Leader-Worker 模块** | initconfig / snapshot / reconcile / cleanconfig——**各有独立 Redis 选举，不共享 Leader**（材料另注：**message 是 Kafka 消费者、不是 Leader-Worker——持续消费的流式模块**；vpcapi 是 HTTP 客户端） |
| **MANUAL_BUILD / MANUAL_REBUILD** | 快照的运维手动触发口 |
| **SnapshotReadyNotification** | 快照完成通知（Kafka → BrokerNotifyTopic）——**fusion 与 broker 的"交货铃"** |

## ① 它干了什么——一条 binlog 在 fusion 的旅行

用户创建 VM → api-server 写 MySQL + 发 Kafka binlog。**这条 binlog 到 fusion 之后的全程**（以 `vmrtable` 为例）：

1. **message/binlog 子模块**：KafkaConsumer 收到 `{type: INSERT, vmIp: 10.0.1.71, hostIp: 28.65.43.24, vpcId: 3345906}` → 检查初始化状态（`ConfigState=INITIALIZED`？）→ **保存实时配置到 MySQL `t_generic_config`** → **存 binlog protobuf 进 Redis 聚合集合（BizTypeBinlogPb）** → 通知 snapshot Leader"VPC 3345906 的 vmrtable 配置变了"；
2. **snapshot 模块**：Leader 扫描发现变更 → 发布 task `{configName, scopeId, latestVersion: 1235}` → Worker 领任务 → **增量路径**：`mergeBinlog` 读 1234→1235 之间的 binlog，按条 `SaveConfig`（INSERT/UPDATE）或 **`DeleteConfig`（DELETE——材料强调"`db.Delete` 不是 `Put(nil)`！"）**；**未变化的 shard → `cloneSnapshot`（只改版本号）**，变化的 shard → 遍历 LevelDB 组装 **ResSnapshot protobuf**；
3. **交货**：序列化 + GZIP（小于 `minSize` 跳过）→ **写 `t_generic_snapshot`（先 MySQL 再 CAS 写 Redis）** → 更新版本列表 → 删超 `MaxSnapshotCount` 的旧版本 → **Release LevelDB（Close + `os.RemoveAll` 临时目录）** → 发 `SnapshotReadyNotification` 到 Kafka；
4. **下游**：broker 侧消费通知/直读——就接上了第 19 课"`GetLatestSnapshot`（Redis 元信息 + CDB 分片数据）+ `ListBinlog`"。

**规模感（材料给的实例）**：VPC 3345906 全部配置 30 条（t_vm 7 + t_eni 5 + t_eni_pip 6 + t_subnet 3 + …）→ 序列化约 **12 KB** → GZIP 后 **3-5 KB** → 远小于单分片阈值（10000 条）→ **只有 1 个 shard（shardId=0）**。

**一句话**：fusion 把"**抵达成百上千条增量**"熬成"**一锅可整取的快照**"——它存在的第一性问题就在下一跳。

## ② 链路走查（材料行号）

### 跳 1：为什么 api-server 不能直接推给 agent（position §1.3）——中枢的"出生证明"

三个理由（材料原文的三段论证）：

1. **api-server 不认识母机**："它知道'VM 10.0.1.71 创建了，在母机 28.65.43.24 上'，但它不知道'这个 VM 的路由**要下发给哪些母机**'"——母机 29.211.8.83 也需要知道 10.0.1.71 的位置（否则跨母机通信不通），而"**母机信息在 vpc-host-agent 那边**"。**需要一个中间层，把'配置变更'和'母机订阅'对接起来**（这正是第 19 课 `routeMap` 存在的理由：**配置不知道谁会订阅、订阅者不知道配置何时变——中间层两头记账**）；
2. **配置需要快照**：母机重启后 agent 要**一次性**拿到整个 VPC 的全部配置——"vpc-fusion 把 MySQL 里的配置序列化成 LevelDB 快照——一个 protobuf 包含整个 VPC 的完整配置"；
3. **配置需要对账**：配置从 api-server 到母机要过 **Kafka → fusion → LevelDB → broker → tRPC → agent → DPDK 多跳**（§1.3 原文"这么多跳"；§3.4 另有"6 跳"表述——材料自身计数口径不一，此处按环节列出为准）——"任何一跳出问题都可能导致**配置漂移**（控制面觉得配了，数据面实际没配）"；fusion 的 reconcile 定期对比期望态与实际态。材料原话："**Gen2 没有系统化对账——依赖 ZK 自身的一致性保证**"。

**Gen2 对照**：fusion **替代了 nfv-proxy**（第 4 课/系统线里"把 binlog 推进分区 ZK"的那位）——但 nfv-proxy 只是"转发者"，fusion 是"**转发 + 加工 + 对账 + 清理**"四合一（§1.2 一句话定位）。

### 跳 2：六个核心模块与"四顶独立王冠"（position §三 + §3.7）

`internal/service/` 下 6 个核心业务模块（+ common/health/localcache 三个基础设施）：**initconfig / message（含 binlog）/ snapshot / reconcile / cleanconfig / vpcapi**。

- **initconfig**（§3.1）：首次启动/新配置类型/VPC 首次接入时，**从 api-server HTTP 拉全量**写 MySQL——"initconfig 不完成，该配置类型的后续 binlog 无法处理"（**先全量后增量，在单个资源粒度的又一次现身**——Worker 调 `DescribeNetworkInterfaces` 拿全量 → `InitState = INITIALIZED` → **触发 0 号快照**）；
- **message**（§3.2）：五子模块（binlog / broadcast / command / notification / topic），binlog 为核心——"binlog 挂 → 变更堆积在 Kafka → snapshot 不更新 → **所有后续配置变更不生效**"；
- **snapshot**（§3.3）：见 ① 全流程——**触发逻辑 = `NOT (时间 < SnapshotPeriod AND 变更 < SnapshotBinlogCount)`**（时间与变更数**双阈值取或**：`SnapshotBinlogCount` 动态配置 [1, 1000000] 条 / `SnapshotPeriod` [30, 3600] 秒；材料警告"600 秒是测试默认值，生产值动态配置"）；
- **reconcile**（§3.4）：期望态（API）vs 实际态（LevelDB 快照）——**全量每天一次 + 变动 5 分钟一次**（第 18 课能力表的落地）；
- **cleanconfig**（§3.5）：清理已下线资源的残留配置；
- **vpcapi**（§3.6）：被 initconfig/reconcile 复用的 HTTP 客户端。

**"四顶独立王冠"**（§3.7 面试追问）：**四个 Leader-Worker 模块各有独立 Redis 选举 Key，不共享 Leader**（一个模块的 Leader 挂了不影响其他模块）——

| 模块 | ElectionKey | Redis 实际 Key | TTL | 续租 | 源码位置 |
|---|---|---|---|---|---|
| initconfig | `init_zone` | `leader:election:init_zone` | 15s | 5s | `initconfig/leader_service.go:58` |
| snapshot | `snapshot-build-leader` | `leader:election:snapshot-build-leader` | 15s | 5s | `snapshot/leader_service.go:27` |
| reconcile | `reconcile` | `leader:election:reconcile` | 15s | 5s | `reconcile/leader_service.go:45` |
| cleanconfig | `clean-config-leader` | `leader:election:clean-config-leader` | 15s | 5s | `cleanconfig/leader_service.go:21` |

（前缀 `leader:election:` 来自 `redis/leader_vote_dao_impl.go:15`；详见 `vpc-fusion-task-queue.md` 第二节——第 21 课正主。）**15s TTL + 5s 续租 = 三倍冗余的租约设计**——选举的可靠性直接决定"王冠"不悬空。

### 跳 3：reconcile 与 cleanconfig——对账产出"动作"（position §3.4/§3.5）

**reconcile**（§3.4）：期望态（反调 api-server API）vs 实际态（LevelDB 快照 + binlog 合并）——

```
reconcile Leader → 发布对账任务 {configName, scopeId, triggerType: PERIODIC}
reconcile Worker
  ├── LoadLeftData ← vpcapi 调 API（期望态：API 说有 5 个 ENI）
  ├── LoadRightData ← LevelDB 快照 + binlog 合并（实际态）
  └── dao.Reconcile() ← 流式比对（边读边比，避免 OOM）
三种差异 → 三种动作：
  Missing（左有右无）  → 触发 snapshot 补齐
  Redundant（右有左无）→ 触发 cleanconfig 清理
  Mismatch（不一致）   → 触发 snapshot 重建
输出 → Prometheus（reconcile_diff_total / reconcile_duration_seconds）
```

**强调**：对账的产出是**修复动作**不是报告——"对账不工作 → 配置漂移累积 → 长期不一致"。触发节奏：**全量每天 + 变动 5 分钟**。

**cleanconfig**（§3.5）：清理已下线资源残留——定时（1 小时扫描）+ binlog DELETE 事件双触发；Worker 领 `{configName, scopeId}` 后**六删一告**：`t_generic_snapshot`（快照）→ `t_generic_snapshot_version`（版本列表）→ `t_generic_config`（配置）→ `t_generic_config_state`（状态）→ Redis binlog 缓存 → ResourceObj 记录 → 最后**通知 api-server"资源已清理"**（闭环回执）。

（`vpcapi` 是两者复用的 HTTP 客户端；材料注：**不是 Leader-Worker**，被调用、不独立调度。）

### 跳 4：先纠正一个误解——LevelDB 只是"临时工作台"（snapshot-structure §一）

材料开场自带打假："**之前的文档里写的'快照存在 LevelDB 里'——不准确**"。真相：

- **LevelDB = 临时 merge 缓存**：合并 binlog + 全量数据的工作台，**构建完成后立即删除**——
  ```go
  // snapshot_merge_dao_impl.go:78-93
  func (d *GenericSnapshotMergeDaoImpl) Release() error {
      if d.db != nil { d.db.Close() }        // 关闭
      return os.RemoveAll(d.dbPath)          // 删除整个目录！
  }
  ```
- **最终快照 → `t_generic_snapshot` 表**（`sdk_write_client.go:94-178` `SaveSnapshot`：protobuf 序列化 → **智能压缩**（小于 `MinCompressSize` 跳过）→ `Put(BizTypeSnapshot)` MySQL + Redis 双写）；
- **版本列表 → `t_generic_snapshot_version` 表**。

**为什么用 LevelDB 做临时缓存**（§1.2）：合并需要大量随机读写——**随机写**（按 `shardId + identifier` 查/改/删）+ **范围读**（按 shardId 前缀扫描）。合并完成整体序列化成 protobuf **写一次 MySQL**——**把 N 次随机写变成 1 次顺序写**。材料的三路对比：直写 MySQL（大量随机 IO / 事务开销 / 锁竞争）；直写 Redis（单 value 限制 / 不支持范围查询）；LevelDB 临时缓存 = **高性能合并 + 完成后一次性持久化**。

### 跳 5：key、identifier 与 ResSnapshot（snapshot-structure §二/四）

**key 格式**（§2.1）：`config:{shardId}:{identifier}`——如 `config:0:vpc-gu5knkob|eni-bmc37aa3`。

**identifier 从哪来**（§2.2）：从配置类型的 **txtpb 元数据**里来——字段标记 `is_identifier: true` 的按 `field_number` 顺序用 `|` 拼接（eni 例：`uniq_vpc_id|uniq_eni_id`）。**"元数据驱动"再次现身：连主键都是元数据生成的**。

**ResSnapshot**（§4.1）：`version`（全局单调递增）+ `config_name` + `resource_scope` + `owner` + `oneof payload { SnapshotObject object | SnapshotObjectMap object_map }`；`SnapshotObject` 内层再 `oneof { PayloadItemList item_list | Any payload }`——**4 种 ConfigType 对应 4 种 payload 构造方式**（§4.2，例：eni=CONFIG_TYPE_MAP_BASIC、vmRTable=CONFIG_TYPE_MAP_LEVEL）。

### 跳 6：分片——"动态均衡，不是 hash"（snapshot-structure §五）

**shardId 怎么算**（§5.1，源码 `vpc-api-server/internal/pkg/shard/shard.go:60-108`）：

1. 查 `t_shard_counter` 表（`ORDER BY config_count ASC`）；
2. 遍历分片，**第一个 `config_count < threshold`（默认 10000）** 的就是家；
3. 全满 → `max(shard_id) + 1`（新建分片）；
4. 无记录 → `shardId = 0`。

**为什么不用 hash**（§5.2 原文对照）：hash——分布不均 / 扩容 rehash / 无法控单 shard 大小；动态均衡——**总往最少的 shard 分配（自动均衡）/ 扩容时新 shard 从 0 开始（不影响已有）/ 精确控制单 shard 大小（threshold）**。

**为什么要分片**（§5.3，四条）：Redis 单 value 有限制（避免大 key 阻塞）/ 并行构建（不同 shard 不同 Worker）/ **增量效率（只重写变化 shard，未变 `cloneSnapshot`）** / broker 按需拉取（只拉需要的 shard）。`SnapshotVersion` 里带分片信息（version / timestamp / total_size…）。

### 跳 7：fusion 坏了会怎样——故障五景与影响矩阵（position §4）

**五种故障场景**（§4.1）：

- **场景 1 message/binlog 挂**：配置变更堆积在 Kafka → snapshot 不更新 → broker 拿旧快照 → agent 下发旧路由 → **新创建的 VM 流量不通**（材料例：ins-0wfilqu8/10.0.1.71 创建了，但 ins-o5fn07ji/10.0.1.9 ping 它——母机 A 查 route_table 找不到 → **丢包**）；
- **场景 2 snapshot 挂**：binlog 消费了但快照不生成 → **配置变更不生效**；
- **场景 3 任务队列不可靠**（**bugfix/disorder 修的问题**——第 21 课正主）三条死法：任务丢失 → 快照不更新；**任务被多 Worker 重复处理 → 版本号冲突 → 数据面混乱**；**任务卡在 processing → 该 VPC 永远不更新**（材料例：Worker A 领了"给 VPC 3345906 生成快照"的任务后 OOM 崩溃，任务卡在 processing 集合——**没有 Lease 机制（旧实现），永远不会被其他 Worker 接管**）；
- **场景 4 快照合并 bug（DeleteConfig Put(nil)）**：用户销毁 ins-0wfilqu8 → DELETE binlog 触发重新合并 → 但 `DeleteConfig` 曾用 **`Put(key, nil)` 而不是 `Delete(key)`**——key 还在、value 变空字节 → **已删除的路由在快照中"复活"** → agent 下发不存在的路由 → **流量发到已销毁的母机上 → 丢包 + 安全风险**（① 里"`db.Delete` 不是 `Put(nil)`！"那条材料强调，讲的就是这个事故）；
- **场景 5 初始化死循环**（resourceObj 自指检查）：resourceObj 反复创建删除 → CPU 打满 → **整个 vpc-fusion 不可用 → 所有 VPC 的配置同步停止**。

**故障业务影响矩阵**（§4.2 全表——四列即第 18 课"用户四敏感"）：

| 故障 | 创建即用 | 规则即生效 | 销毁即停止 | 业务不中断 |
|---|---|---|---|---|
| message/binlog 子模块挂 | ❌ VM 不通 | ❌ ACL 不生效 | ❌ 销毁后仍转发 | ❌ 跨 VPC 断 |
| snapshot 模块挂 | ❌ 配置不生效 | ❌ 规则不更新 | ❌ 销毁不停止 | ❌ 业务中断 |
| 任务队列不可靠 | ⚠️ 延迟 | ⚠️ 延迟 | ⚠️ 延迟 | ⚠️ 间歇性 |
| 快照合并 bug | ❌ 新配置不通 | ❌ 新规则不生效 | ❌ 已销毁复活 | ❌ 旧配置残留 |
| 初始化死循环 | ❌ 全部停止 | ❌ 全部停止 | ❌ 全部停止 | ❌ 全部受影响 |

材料自己的核心结论：**"vpc-fusion 是配置从控制面到数据面的唯一中转通道——它不可靠，4 类业务指标全部受影响。"** 读矩阵的方法：横着看是"一个组件的故障打穿用户承诺的哪几维"——**枢纽故障=全网故障**；而场景 3/4 告诉我们更阴险的一层：**"错"（复活、版本冲突）比"停"更危险**。

## ③ 设计推导（问题树）

**Q1：为什么需要 fusion 这一层中间站？**
三点理由合起来是**三种不可混合的节奏**：api-server 管 CRUD（命令式、事务式）；agent 要**全量快照 + 稳定订阅**（状态式、批量式）；对账要**第三种慢节奏**（周期式）。硬塞进任一端都错配（api-server 不认识母机——"母机信息在 agent 那边"）。**中间站不是缓冲，是"节奏翻译器"**。（开源同族：Typha 治读放大、etcd watch 补课——都是"在两端之间插一个正确节奏的层"。）

**Q2：LevelDB"临时工作台"模式的本质？**
**写放大逆转**：合并期间是 N 次随机小写（只配碰的工作台），完成后**压成 1 次顺序大写**（protobuf 整体落库）——而出来后立刻 `Close + os.RemoveAll`，"**工作台不留在现场**"。同族：第 15 课 cos_bak 的临时归档、etcd 的 snapshot 文件——**"临时物用完即毁"是配置工程的通用卫生习惯**。

**Q3：fusion 分片为什么"动态均衡不用 hash"？**
hash 是**均匀的抽象**（你控制不了谁多谁少），动态计数是**可控的均衡**（threshold 说话）。更深的对照：**broker 的 SET 分片**按连接归属（无状态扇出，求稳定映射）；**fusion 的快照分片**按配置计数（有状态构建，求负载均衡）——**同一个系统里两种分片哲学，各自服从自己的约束**。

**Q4：对账三差异（Missing/Redundant/Mismatch）设计精妙在哪？**
对账的产出不是"报告"而是**动作**：Missing→快照补齐；Redundant→清理；Mismatch→快照重建。**每一种差异都直接对应一个修复方向**——与第 17 课 tracker（向内记账）恰好成对：**一个向外对账出动作，一个向内记账出 SLO**。

**Q5：故障矩阵教会什么？**
两类爆炸半径：**枢纽组件**（message/snapshot）一挂四列全红——因为它们站在**唯一路径**上（所有变更都过它们）；**任务队列**的故障更阴险（重复处理→版本冲突→数据混乱，**"错"比"停"更危险**，呼应第 17 课 fail-closed 的取向）。矩阵四列 = 用户四敏感（创建即用/规则即生效/销毁即停止/业务不中断）——**控制面用故障矩阵的方式回答"用户承诺有多脆"**。

## ④ 对照卡：fusion vs nfv-proxy（Gen2 的它）

| 维度 | Gen2 nfv-proxy（第 4 课） | **Gen3 vpc-fusion** |
|---|---|---|
| 角色 | binlog 转发器（推进分区 ZK） | **配置中转 + 快照构建 + 对账 + 清理** |
| 快照 | 无（ZK 树即状态） | **ResSnapshot protobuf + 分片（threshold 10000）** |
| 对账 | 无系统化对账（依赖 ZK 一致性） | **reconcile：全量每天 + 变动 5 分钟，产出修复动作** |
| 清理 | 无独立模块 | **cleanconfig：六删一告 + 回执 api-server** |
| 存储 | 只碰 ZK | MySQL + Redis + **LevelDB 临时工作台** |
| 故障半径 | 配置分发停滞 | **枢纽故障=全网故障（矩阵四列全红）** |

## ⑤ 验收题

1. "fusion 是节奏翻译器"——三种节奏（CRUD/快照订阅/对账）各是什么、为什么不能合并到 api-server？
2. LevelDB"临时工作台"把 N 次随机写变 1 次顺序写；为什么直接写 Redis 不行？（列两条硬约束）
3. fusion 快照分片与 broker SET 分片是"两种分片哲学"——各自服从什么约束？`threshold=10000` 属于哪种可控性？
4. 对账三差异各触发什么动作？为什么说"对账产出动作而非报告"比一代"事后报告"更进一步？
5. 故障矩阵"四列全红"的原因是什么？为什么"任务重复处理→版本冲突"比"任务丢失"更危险？
6. `Release()` 里 `os.RemoveAll(d.dbPath)` 的果决（删整个目录）背后是什么设计假设？（提示：什么叫"工作台"）

---

*本课材料行号清单（供追问对质）：`position` §1.1（定位图 + "替代了 Gen2 的 nfv-proxy，但职责更大"）/ §1.2（一句话定位四件事）/ §1.3（三理由全文：不认识母机 / 需要快照 / 需要配置漂移对账 + "Gen2 没有系统化对账"）/ §3.1（initconfig 流程 + INITIALIZED + 0 号快照）/ §3.2（message 五子模块 + binlog 流程 + "不是 Leader-Worker"注）/ §3.3（snapshot 触发逻辑 NOT(时间<Period AND 变更<Count) + 增量/0 号 + cloneSnapshot + db.Delete 非 Put(nil) + 先 MySQL 再 CAS 写 Redis + MaxSnapshotCount + Release + SnapshotReadyNotification + 12KB→3-5KB→shardId=0）/ §3.4（reconcile：LoadLeft/Right + 流式比对防 OOM + 三差异三动作 + Prometheus）/ §3.5（cleanconfig 六删一告 + 回执）/ §3.6（vpcapi 复用 + 非 Leader-Worker 注）/ §3.7（协作图 + 四王冠表：TTL 15s 续租 5s + 五行源码位置 + "一个模块 Leader 挂了不影响其他"）/ §4.1（五景：例 ins-0wfilqu8/ins-o5fn07ji、任务队列不可靠、初始化死循环）/ §4.2（矩阵前两行）；`snapshot-structure` §一（纠误解 + 代码证据 snapshot_merge_dao_impl.go:78-93 / sdk_write_client.go:94-178 + 1.2 三路对比）/ §2.1（key 格式 config:{shardId}:{identifier}）/ §2.2（is_identifier:true + field_number 顺序 + `|` 拼接 + eni 例）/ §3（value 格式与 eni/vmRTable 两例——本课取标题级）/ §4.1（ResSnapshot protobuf + 双层 oneof）/ §4.2（4 型 payload：BASIC/LEVEL 例）/ §5.1（shard.go:60-108 算法 + threshold 10000）/ §5.2（为什么不用 hash 三对三）/ §5.3（分片四理由）/ §5.4（SnapshotVersion 字段）。*

*审查记录（首轮深审，含 part 1 先行核验）：修正 4 处——① 引文漏字（"vmrtable **配置**变了"补"配置"二字）；② 计数口径（自造的"七跳"：材料 §1.3 只说"这么多跳"、§3.4 称"6 跳"——改为"多跳 + 口径不一注"）；③ "七连删"→"**六删一告**"（6 项删除 + 1 项通知，原表述把通知也算作删）；④ routeMap 类比句澄清（原句"正排索引的由来"逻辑绕，改为"中间层两头记账"）。硬锚升级 3 处——① §1.3 理由 3 补入"**配置漂移**"定义与"Gen2 没有系统化对账"原话；② §3.2 补"**message 不是 Leader-Worker**"注；③ §4.1/§4.2 由"三景+节选两行"补齐为**全五景 + 全矩阵**（新增场景 4 `Put(nil)` 复活事故与 21 课点题、场景 3 卡-processing/OOM 例、矩阵核心结论原话）。软化 1 处（"generic 第三次出场"→"又一次出场"）。逐字命中：§1.1"替代了 Gen2 的 nfv-proxy，但职责更大"、§3.3 触发逻辑 NOT 式与"db.Delete 不是 Put(nil)！"、王冠表四行源码位置、§5.1 shard.go:60-108 与 threshold 10000、snapshot §一代码证据两处行号（78-93/94-178）。*

*审查记录（二次深审）：R1 修正自查在位（@44 多跳+口径注、@89 六删一告、@138 场景 4、@194 审查记录），**然尾差实逮 2 处**——"七连删"残留于对照卡（@177）与清单（@192），R1 只改了正文单点，本轮回填。教训重申（第 18 课同款）：**同断言多处出现时须全量检索、全部替换**。*
