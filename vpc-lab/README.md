# VPC Lab —— VPC 控制面核心机制的代码复现（一代 + 二代 + VPC 5.0）

> 目的：把「业务 / 分布式 / 工程」问题**用代码钉死**——不是能跑的产品工程，而是"可读、可审、可对照"的真实代码。
> 蓝图：`vpc/docs-by-topic/` 中各仓库的**逐文件清单**（文件命名、分层、职责与真实仓库对齐）。
> 参照系（三层，纪律：对照点必须可核验，不许凭记忆——见 `OSS-REFS.md`）：
> ① 本地对照文档：`vpc/test-good/06-open-source-vs/`（vs K8s Controller / vs Calico-OVN / vs Andromeda·Hyperplane·VFP）
> ＋ `vpc/papers/` 9 篇论文（Saga 1987 / ZK 2010 / Andromeda / B4 / Onix…）＋ `vpc/docs/论文-VPC架构映射.md`；
> ② 克隆开源代码：`refs/`（8 仓，逐对照点核验用；已 gitignore，可再生）；
> ③ 每个复现文件头标注对照点（形式：本地材料路径 或 `refs/<repo>:<file>:<line>`）。
> **纪律**：`【复现】` = 依据文档级证据 + 约束推导实现，**非真实源码**；关键参数取自材料口径；不冒充真实仓库、不用于对外披露。

---

## 一、批次计划（三车道：一代 / 二代 / VPC 5.0）

> **代际口径（材料实锤：docs-by-topic/17 跨项目导读）**：
> **一代** = sdn_oss 链（sdn_oss → Ckafka → nfv-proxy → 分区 ZK → vpc_agent(agent_base)，Python2/C）；
> **二代** = agent-broker + AgentOS 体系（分区 ZK/CRS → agent-broker → 母机 vpc-agent(agentos)，Go，过渡代际）；
> **VPC 5.0** = api-server/fusion/broker → host-agent(framework+base)，Go。
>
> **顺序纪律（用户指示）**：先老代际（一代+二代）全部收完，再动 VPC 5.0；
> **fusion 的审查挂起**，在 L3 启动时回填（它属 5.0，不混入老代际节奏）。
> **R5 决策（2026-10）**：复现线**冻结**（L1-3 起暂停）——学习主轴切换为《开源精读》
> （`refs/` 38 仓 + `OSS-REFS.md`）；本 lab 已完成批次降级为"对照地图"，材料口径校验仍在用。

### 车道 L1 — 一代（sdn_oss 链）

| 批次 | 组件 | 材料 | 重点问题 | 状态 |
|---|---|---|---|---|
| **L1-1** | **sdn_oss**（写入口·Saga 引擎） | 24 | Saga/Outbox/双写/ZK 异步写/迁移检查 | ✅ R1 审查已过 |
| L1-2 | nfv-proxy | 23 | 14 类进程 / 消费→分区 ZK + CRS 快照 / ZK 锁雪崩→DB 锁 | ✅ R2 审查已过 |
| L1-3 | vpc_agent(master) + agent_base | 28 + 14 | play_role / state→netlink / Inspector 对账 / 守护自杀机制 | 计划 |
| L1-4 | vpc_agent dpdk/vpcgw | 27 | 网关版 agent（DPDK 数据面分支）★拆出版 | 计划 |
| L1-5 | DFW（数据面/安全组） | 05 | Watcher-Builder / iptables+ipset / DPDK 双路径 | 计划 |
| L1-6 | vpc_sflow | 29 | 五宿主形态 / 内核 sFlow+共享内存 / 聚合上报 ★新补 | 计划 |
| L1-7 | ZK 基础设施族（7 件） | 30/31/32/43/44/45/46 | 定制 ZK（3.4.6 同步器 / 3.5.6）+ agent/controller/monitor/differ/backup ★补 3 件 | 计划 |
| ~~L1-8~~ | ~~vpc-cgw~~ | — | ~~云 API 声明式网关~~ | ⏭️ 跳过（用户裁定） |

### 车道 L2 — 二代（agent-broker + AgentOS）

| 批次 | 组件 | 材料 | 重点问题 | 状态 |
|---|---|---|---|---|
| L2-1 | agent-broker | 15 | 分区 ZK/CRS 订阅 → SQLite/内存版本 → TRPC HostHeartbeat 增量 | 计划 |
| L2-2 | AgentOS | 16 | Extension 微内核（upstream/res/downstream）/ worker group 协程分片 | 计划 |

### 车道 L3 — VPC 5.0

| 批次 | 组件 | 材料 | 重点问题 | 状态 |
|---|---|---|---|---|
| **L3-1** | **vpc-fusion** | 38 | 选举 / 任务队列 / 快照 / 对账 / 消息 | ✅ 初稿（审查挂起→L3 启动时回填） |
| L3-2 | vpc-api-server | 34 | Saga 7 步 / 双库 / 版本号 / CtrlSetRouter | 计划 |
| L3-3 | vpc-broker | 36 | BTree 三向合并 / Dispatcher / Session 7 态 / tRPC 流 | 计划 |
| L3-4 | host-agent + framework + base | 39/33/35 | DataSyncer / Extension / DPlane / Reconciler + 基础库 | 计划 |
| L3-5 | vpc-protos | 41 | 协议层（UniObj 等）★新补 | 计划 |
| L3-6 | vpc-ops + ctrl-knowledge | 40/37 | 运维编排（ATE）/ 知识库 ★新补 | 计划 |
| L3-7 | computation-agent | 19 | 预计算（设计阶段仓库，低优先） | 计划 |

### 排除清单（46 份组件文档中的非复现项）

- **总纲/分析类 15 份**（01-13、17、20）：参考物，不建工程；
- **非 VPC（AI/开发工具）6 份**：18-codesense / 21-mcp-proxy / 22-netsense / 25-skills / 26-synapse / 42-zenflow（均在 `/data/workspace/ai/*`）；
- **vpc-cgw**：用户裁定跳过（纯声明式参数校验/转发）。

> 语言策略：按真实系统语言——L1 为 Python 2/3 混用（sdn_oss/agent 系 Python2、nfv-proxy Python3.6）
> 与 C/C++（vpc_sflow）；L2/L3 为 Go；同一目录树内以子目录区分（`sdn-oss/`、`vpc-fusion/`…）。
> 审查记录：`review-log.md`（R1 sdn-oss / R2 nfv-proxy / R3-R4 全库开源对照点——发现/修正/遗留逐轮在档）；对照索引：`OSS-REFS.md`。

## 二、覆盖矩阵（真实文件 → 本复现 → 问题类别 → 参照系）

| 真实文件（docs-by-topic 清单） | 本复现 | 问题类别 | 开源对照点 |
|---|---|---|---|
| [38] `vpc-fusion/cmd/vpcfusion/main.go` ＋ 启动框架 | ✅ `vpc-fusion/cmd/vpcfusion/main.go`、`internal/server.go`、`internal/system/startup.go` | 工程 | kube-ovn `cmd/*`；k8s component-base |
| [38] `internal/service/common/leader_vote_service.go` | ✅ 同名 | 分布式 | etcd lease；TF control HA |
| [38] `internal/service/common/task_service.go` | ✅ 同名 | 分布式 | SQS visibility timeout / Temporal |
| [38] `internal/storage/dao`（task/lock/leader_vote impl） | ✅ `internal/dao/{leader_vote,task}_dao_impl.go` | 分布式 | Redis 官方 Lua 原子模式 |
| [38] `internal/service/snapshot/worker_service.go` | ✅ 同名 | 业务 | LSMT；Kube-OVN 快照 |
| [38] `internal/service/reconcile/reconcile.go` | ✅ 同名 | 业务 | K8s controller（报告制 vs converge） |
| [38] `internal/service/message/binlog/binlog_service.go` | ✅ 同名 | 业务＋工程 | franz-go 幂等消费 |
| [38] `internal/mq/consume/consumer.go` | ✅ 同名 | 工程 | franz-go poll 模型（反例：sarama channel 消费；R3 用克隆代码核验） |
| [24] `sdn_oss/api/tvpc_handle.py`（CTvpcThread Saga 基类） | ✅ `sdn-oss/api/tvpc_handle.py` | 业务 | Saga 论文；OpenStack TaskFlow |
| [24] `sdn_oss/common/zoob_proxy.py` / `kafkab_proxy.py` | ✅ `sdn-oss/common/{zoob,kafkab}_proxy.py` | 分布式 | ZK 写代理（CZoobProxy）；Kafka producer 重试 |
| [24] `sdn_oss/bin/vpc_binlog_producer_task.py` | ✅ 同名（20+CRemedialThread/16 分区） | 业务＋工程 | Outbox/Debezium |
| [24] `sdn_oss/api/Vpc5/decorator.py` | ✅ `sdn-oss/api/Vpc5/decorator.py` | 业务＋工程 | 双模型并行的迁移切片 |

## 三、使用纪律

1. 每个文件头部标 `【复现】` 并注明：真实文件出处（材料行号/清单行）+ 证据等级（实证/推导）；
2. 关键参数（TTL 10s、租约 30s、心跳 10s、20 生产者、批次 100、60s 补救、16 分区…）全部来自材料口径；**不确定的数字一律不写**；
3. 与真实源码的差距：每个批次均为**教学切片**，省略模块在 README 中列明；
4. 开源对照只做"业界怎么做 vs 我们怎么做"的参照，不参与口径。

## 四、建议阅读顺序（L3-1 = vpc-fusion）

```
cmd/vpcfusion/main.go        ← 工程入口：停机为什么要 detached ctx（#12）
  → internal/server.go       ← 组装：降级语义 + 停机编排
  → internal/system/startup.go  ← 启动框架：Checker/Initializer/Worker
  → internal/dao/leader_vote_dao_impl.go   ← 分布式①：SET NX EX + Lua 续约校验
  → internal/service/common/leader_vote_service.go ← 选举循环/四域/回调
  → internal/dao/task_dao_impl.go          ← 分布式②：8 Lua（claim/finish/requeue/Ownership）★
  → internal/service/common/task_service.go ← 心跳/回收/限频/优雅停机 ★
  → internal/service/snapshot/worker_service.go ← 业务①：快照 + DeleteConfig 事故现场
  → internal/service/reconcile/reconcile.go     ← 业务②：只报告不修复 + 阈值保护
  → internal/service/message/binlog/binlog_service.go ← 业务③：幂等 + 版本连续性
  → internal/mq/consume/consumer.go             ← 工程：Kafka 假死防护（看门狗哲学）
```

## 五、L1-1 阅读顺序（sdn-oss）

```
api/tvpc.py                     ← 入口：interfaceName → Handle 注册表（一个接口=一个文件）
  → api/cgi_base_tvpc.py        ← 信封校验/region/配额（真实信封 version/caller/callee/eventId）
  → api/tvpc_handle.py          ← ★ Saga 引擎：add_step(fwd,bwd,skip_on_codes) / 分批并发 / Redis 回滚缓存
  → api/add_rtb_route_handle.py ← 一条路由的完整步骤链（双写=同事务）
  → common/tvpc_db_client.py    ← 主库（585 业务表）+ CQueryCond
  → common/tvpc_transaction_db_client.py ← Outbox（25 表，4 态 sendState）
  → common/zoob_proxy.py        ← ZK 异步写（按 VPC 保序）
  → bin/vpc_binlog_producer_task.py ← ★ 20 生产者 + CRemedialThread → Kafka（16 分区）
  → common/zklock.py            ← ZK 锁教训（LOST→suicide 雪崩 → DB 锁迁移）
```
