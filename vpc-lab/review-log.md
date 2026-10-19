# vpc-lab 审查记录（review-log）

> 纪律：每个批次完成后全量审查一轮（对材料逐文件核对：证据真伪 / 口径精确 / 编造数字 / 归因正确）。
> 材料主库：`/data/workspace/vpc/`（docs-by-topic、VPC控制面.md、test-good、interview）。

---

## Round R1 —— sdn-oss 批次（17 文件）

**触发**：sdn-oss 初稿落盘后，用户要求"深度 review 一轮"；并指出核对"Saga"机制真伪。

### 一、机制裁定（用户拷问点）

**sdn_oss 用的是不是 Saga？→ 是，实锤。**
- `VPC控制面.md:3205`："sdn_oss —— Saga 事务引擎"；`:3216`："核心模式 — Saga 补偿事务（整个系统的核心）"
- `:411` §2.3 完整伪代码：`add_step(forward_fn, backward_fn=None, skip_on_codes=None)`；正序执行、逆序回滚；
- `:538` 真实样板：AddService ~60 步（`self.add_step(self.add_service_transaction, self.del_service_transaction)`）；
- 执行模型：按对象分批并发（split_num=20 / max_concurrency=20 / BoundedSemaphore / SagaWorker）；
- 回滚失败（§2.6）：缓存回滚上下文到 Redis（24h）+ 告警 + 对外暴露 `Revert.TVPC.CreateService` 重试接口；
- 与 Outbox 配合（§2.7）：业务表 + Outbox **同一 MySQL 事务**提交。

### 二、发现与修正（13 处）

| # | 位置 | 问题（原稿） | 修正 | 证据 |
|---|---|---|---|---|
| 1 | tvpc_handle.py | `add_step(name, fwd, bwd)` 两/三参数自定义 | 改为真实 `add_step(forward, backward=None, skip_on_codes=None)`，名字取 `__name__` | §2.3 |
| 2 | tvpc_handle.py | 单对象步骤链，无批量/并发模型 | 补 `execute(items)`：分批 20 + 信号量 20 + SagaWorker 线程 + 任一失败全批回滚（SagaTransactionFailed） | §2.3 |
| 3 | tvpc_handle.py | 回滚失败仅 log | 补 Redis 缓存（24h，key `rollback:{vpcId}:{i}`）+ 告警 + Revert 接口语义 | §2.6 |
| 4 | tvpc_handle.py | 无 skip_on_codes 语义 | 补：错误码命中 → 跳过后续步骤而非回滚 | §2.3 |
| 5 | tvpc_transaction_db_client.py | sendState 3 态（0/1/2） | **4 态**：0 待发 / 1 发送中 / 2 已发 / **3 失败**；补 epoch/retryCount/lastError/updateTime | §2.7 DDL |
| 6 | 同上 | "失败留在 1 等 Mediator" | **失败立即重置 0 + retryCount+1 + lastError**；Mediator 只管崩溃残留（sendState=1 且 updateTime 超 60s） | §2.7 |
| 7 | 同上 | 死信机制缺失 | 补 `sendState=3 或 retryCount>10 → 告警` | §2.7 |
| 8 | vpc_binlog_producer_task.py | batch=500 | **batch=100**（材料口径） | §2.7 |
| 9 | 同上 | 同 VPC 保序仅靠 ORDER BY | 补 **`SELECT ... FOR UPDATE` 行锁**（§2.8 定稿方案）；topic=`vpc5.service.{vpcId%16}`、key=vpcId | §2.8 |
| 10 | 同上 | Mediator 10s 周期、职责混淆 | 更名 **CRemedialThread**（真实类名）、30s 周期、只修崩溃残留 | zk-learning-path.md:147 |
| 11 | cgi_base_tvpc.py | 信封字段 appId/uin/region/args（编造） | 真实信封 `version/caller/callee/eventId/interface{interfaceName,para}`；上下文类更名 `CDc`（common/dc.py） | VPC控制面.md:12686 |
| 12 | add_rtb_route_handle.py | "路由上限 200"（编造数字） | 删除，改配额查询（真实值以配置配额表为准） | 全库无出处 |
| 13 | zklock.py | 归因：sdn_oss 此处自杀雪崩（错误） | 归因修正：雪崩在 **nfv-proxy proxy_merge worker**；DB 锁容忍 2 次心跳失败（30s） | docs-by-topic/08 §9.2 |

### 三、证据补充（原稿标"推测/缺证据"→ 已升级）

| 项 | 原状态 | 现证据 |
|---|---|---|
| zoob_proxy | 24 清单标"(推测)" | `VPC控制面.md:24287`：真实类名 `CZoobExchange/CZoobProxy/CZoobReqOp`；`:3244`"ZK 异步写入" |
| is_vif_ready 事故链 | 仅"曾用全局锁" | `test-good/04-others/is_vif_ready.md` 全文：state.mutex(state.py:370) / 对账持锁 40+ 检查 / 4.12.0 合入（2 文件 +21/-8） |
| endpoint 注册表 | 机制正确 | `VPC控制面.md:12746`（api/tvpc.py:351 application()）、`:12738`（一个接口=一个文件） |
| 585 表 / 25 Outbox 表 | 数字未对齐 | `VPC控制面.md:3922` 实锤 |

### 四、遗留（下一轮处理）

- **fusion 批次未审查** —— 归 **L3（VPC 5.0）车道**：审查挂起至 L3 启动时回填（用户指示：先完成老代际 一代+二代，fusion 属 5.0 不混入）。届时候重点核：`binlog_service.go` 幂等键引用出处（"fusion-position §2 分工表" 在本库未定位到）+ 与 sdn_oss 生产侧幂等键（transId/epoch/binlogVersion）的衔接关系。
- 材料中 "生产者协程" 表述与 Python 线程实现的差异（真实性存疑，已在文件注释标注为教学等价）。

---

### 五、R1 附记：全库差集盘点（代际口径修正）

**触发**：用户指出"fusion 是 5.0 的，先把二代架构搞清楚"——原 vpc-lab 计划的"Gen2 线/Gen3 线"命名**与材料代际口径不符**。

**材料口径实锤**（docs-by-topic/17 跨项目导读）：三代并存——
- **一代**：sdn_oss → Ckafka → nfv-proxy → 分区 ZK → vpc_agent（agent_base，Python2）
- **二代**：分区 ZK/CRS → agent-broker → 母机 vpc-agent（agentos，Go·过渡代际）
- **VPC 5.0（三代）**：api-server → fusion → broker → host-agent（vpc-agent-framework+vpc-base）

**全库盘点**（46 份组件文档 = 6 批次前已知 40 + 本轮补齐 6）：

| 分类 | 数量 | 明细 |
|---|---|---|
| 一代 | 13 | 24 sdn_oss / 23 nfv-proxy / 28 vpc_agent(master) / 14 agent_base / 27 vpc_agent_dpdk_vpcgw / 29 vpc_sflow / 05 数据面与安全组 / ZK 族 7 件（30/31/32/43/44/45/46） |
| 二代 | 2 | 15 agent-broker / 16 AgentOS |
| VPC 5.0 | 11 | 34/38/36/33/39/35/41/40/37/19/42（42-zenflow 实为 AI 工具，归排除） |
| 总纲/分析 | 15 | 01-13 / 17 / 20 |
| 非 VPC（AI 工具） | 6 | 18 codesense / 21 mcp-proxy / 22 netsense / 25 skills / 26 synapse / 42 zenflow |

**查出的计划缺口（4 处，已补）**：
1. **vpc_sflow（29）完全缺失** → 补 L1-6；
2. **ZK 族只列了 4/7 件**（漏 backup/synchronizer/3.5.6）→ 补全 L1-7；
3. **vpc_agent 未拆分**：master（28，一代主力）与 dpdk/vpcgw（27，网关分支）是两个仓库 → 拆 L1-3 / L1-4；agent_base（14）并入 L1-3；
4. **AgentOS 错混入 vpc_agent 批**：AgentOS（16）是**二代框架**、与 agent-broker 同代 → 归 L2-2。

**修正动作**：vpc-lab/README.md 批次计划重构为 L1/L2/L3 三车道（含排除清单）；
顺序纪律：先老代际（L1+L2）全部收完，再动 L3——fusion 审查在 L3 启动时回填。

---

*Round R1 完成：17 文件全部通过材料回溯；`python3 -m py_compile` 全绿。全库差集盘点同轮完成。*

---

## Round R2 —— nfv-proxy 批次（26 文件）

**触发**：用户要求"深度的 review"。核对基准：docs-by-topic/23（逐文件）+ 08（分布式问题）+ 17（链路导读）+ test-good。

### 一、发现与修正（9 处）

| # | 位置 | 问题 | 修正 | 证据 |
|---|---|---|---|---|
| 1 | `proxy_binlog.py` | **/BINLOG 路径捏造**：写成 `/BINLOG/{region}/{zone}/{table}/{pk}` | 实锤修正：`/BINLOG/{vpcId}/{资源组}/...`；payload 补 epoch 字段 | tools_clean_expire_group.py（"/BINLOG/{vpcId}/VM 下 group 子树"）；tools_check_zk_last_epoch_binlog.py（多 epoch 残留） |
| 2 | README | "常驻进程 12 类" | 14 个模块（§4.1 映射逐条，含 proxy1 双份） | §4.1 |
| 3 | README | "bin/ 14 个启停脚本" | 20 个文件（17 .sh + include 函数库 + 2 建库 SQL） | §3.6 逐行 |
| 4 | README | "go_proxy 21 个 Go 文件" | 21 个文件条目（17 个 .go） | §3.7 逐行 |
| 5 | README | 双份 binlog 进程"分载+冗余"直陈 | 标注为**推导**（材料未展开语义） | §3.2/§3.6 仅见"双份" |
| 6 | 6 处参数 | 示意值未标注（suicide 窗口/watchdog 阈值/zkconn 重试/merge 限流/monitor 清单/channel 缓冲/commitPath） | 全部加注"示意——材料未给具体值" | 纪律：不确定的数字要么有出处、要么标示意 |
| 7 | `ckv_op.py` | 死代码（`_fragment_size`/未用变量） | 清理 | — |
| 8 | `file_differ.py` | `open()` 未闭合 | 改 `with` | — |
| 9 | `service/server.go` | 拼写错 "laughing from" | "catching from" | — |

### 二、核实无误（本轮抽查确认的关键项）

- `lock.py` 雪崩归因与 DB 锁特性（"容忍 2 次心跳失败（30 秒）"）逐字对齐 08 §9.2；
- `main.py` 14 个 module 映射 ↔ §4.1 逐条一致；`daemon.py` 五机制（双 fork/watchdog/信号/cond_wait/随机 suicide）↔ §3.1；
- `init_vpc_common.py` 五机制 + 25000（SubnetRule）↔ §3.3；粒度口径（单快照 vs 单片）已诚实标注；
- `recovery_vpcgw_snapshot.py` 进程粒度 + 版本表名 + gevent"勿 import"↔ §3.2（且 tools_fix_data.py"独立进程形态，勿 import"为同款模式佐证）；
- `file_differ`（sort/comm）、`proxy_merge_master`（pending 管道/计数限流/vm_prop 动机）↔ §3.1/§3.2；
- `go_proxy` 全链（Replicater 抢 worker 序号/锁/位点决策/GTID 降级 ↔ §3.7 service/server.go；fnv 队列/GTIDSet PENDING-FINISHED ↔ dispacher.go+const；GTIDSet|date+通知子树 ↔ event_2_zk.go；MergeFromDb ↔ event_from_db.go）。

### 三、遗留

- 无阻断项。省略模块（syn_op.py 等 9 类）已在 nfv-proxy/README「省略清单」固定口径。
- 新证据副产品（供后续批次）：`tools_replay_zk_from_db.py`（从 DB 重放 service binlog 修复延迟——init_latency 进程）、`tools_check_filter.py`（account_api 对账差异核对）——L1-5/monitor 相关批次可引用。

---

*Round R2 完成：26 文件通过材料回溯；`py_compile` + `go build/vet` 全绿。*

---

## Round R3 —— 开源对照专项审查（全批次）

**触发**：用户拷问"你没参考实现吗？之前不说找了很多开源的资料吗？要拉到本地吗？"——审查**对照点维度**：
此前只核了"材料口径"，没有核"开源对照"。

### 一、发现（两类硬伤）

| # | 级别 | 发现 | 处置 |
|---|---|---|---|
| 1 | 🔴 | **本地对照材料零引用**：`vpc/test-good/06-open-source-vs/`（三份 vs 文档）+ `vpc/papers/`（9 篇论文，含 Saga 1987/ZK 2010/Andromeda）+ `docs/论文-VPC架构映射.md` + 精要卡 43/44/45 全部存在，vpc-lab 从未引用 | 建立 **`OSS-REFS.md`** 三层对照体系（本地文档/克隆代码/文件头对照点），接入相关文件头 |
| 2 | 🔴 | **错误对照点**：fusion consumer.go 写"kafka-go 的 Reader——`for msg := range reader` 反例"——kafka-go **根本没有该 API**（克隆核实 `segmentio/kafka-go@2e0b396`） | 修正为 **sarama channel 模型**（`consumer.go:403 Messages() <-chan`；`config.go:615` ChannelBufferSize 反压） |
| 3 | 🟡 | "franz-go 示例的 upsert 模式"不存在（franz-go examples 无 upsert） | 修正为"应用层 upsert + `examples/group_committing` 手动提交对照" |
| 4 | 🟡 | "Kube-OVN 的 VPC/Subnet 快照推送"——kube-ovn 无此特性（`grep snapshot` 无果） | 修正为"集中式 OVN NB DB 对照"（`pkg/ovs/ovn-nb-*.go`） |

### 二、克隆与核验（8 仓 → `vpc-lab/refs/`，已 gitignore）

go-mysql@56e3844 / kazoo@f6a78f7 / franz-go@7b6cac3 / kafka-go@2e0b396 / sarama@557e059 /
taskflow@571351c / controller-runtime@168e1ca / kube-ovn@524119b。

**补证成功的关键对照点**：
- canal：`StartFromGTID`（canal.go:213）/ `SyncedGTIDSet`（:558）存在；**无自动降级**——MergeFromDb 为自研上层（写入 go_proxy 两个文件头）；
- kazoo `recipe/lock.py`：丢锁以异常告知、**"丢锁即自杀"是业务策略而非 ZK 要求**（写入 lock.py——雪崩因果链条补齐）；
- TaskFlow：`RevertAll`（completer.py:72）+ `REVERT_FAILURE`（actions/task.py:153）——回滚失败需人工介入，对照 sdn-oss 的 Redis+Revert 接口（写入 tvpc_handle.py，连同本地 Saga 论文 PDF）；
- controller-runtime `Result{RequeueAfter}`（reconcile.go:44）——K8s converge 语义 vs 本系统报告制（写入 reconcile.go + 本地 vs-k8s-controller.md）；
- kube-ovn `leaderelection`（pkg/controller/config.go:18）实锤。

### 三、遗留解除

- **R1 遗留（fusion-position §2 分工表）解除**：材料原文在 `vpc/test-good/02-vpc5-gen3/vpc-fusion-position.md`（此前只在 docs-by-topic 找，没搜 test-good）。
- 未克隆边界（TF/DPVS/Katran/gobgp/Curator/Debezium）已记入 OSS-REFS.md §四，需要时补拉。

### 四、新增纪律

文件头对照点形式统一为「本地材料路径」或「`refs/<repo>:<file>:<line>`」——**可核验才准落**。

---

*Round R3 完成：对照点全部可核验；`py_compile` + `go build/vet` 复验全绿。*

---

## Round R4 —— 对照点全量审计（R3 延续）+ 跨批次一致性

**触发**：用户再次要求"深度的 review"。

**范围**：全库 34 条对照点（grep 全量收集）逐一过筛；R3 自身自检（8 处 file:line 引用全部复核）；
跨批次一致性抽查（/BINLOG 路径口径、阈值标注、幂等键）。

### 一、发现与修正（6 处）

| # | 级别 | 发现 | 修正 |
|---|---|---|---|
| 1 | 🟡 | "Tungsten Fabric 各进程独立生命周期"（server.go）**无本地依据**（TF 未克隆） | 改引本地 `docs-by-topic/01 附录 A`（TF 逐项对照：无 Saga/Outbox、仅内核数据面等） |
| 2 | 🟡 | "Calico felix 的 dataplane/健康降级思路"（server.go）**无据**；且 felix 独立仓已归档（实测 clone 只剩弃用页 200K） | raw 定向核验 → 改述为 full resync（calico monorepo `felix/dataplane/linux/int_dataplane.go:2320`） |
| 3 | ✅ | felix resync 主张（reconcile.go） | 补 raw 引用（同 #2 依据） |
| 4 | ✅ | kube-ovn"入口极薄" | 核验：cmd/cmdmain.go 多二进制分发；`cmd/controller/cmdmain.go` **29 行**实锤 |
| 5 | ✅ | scanner 出处模糊 | 精确化：`sdn_oss/skills/vpc5-object-api-scanner/`（23 文件，24 清单:506） |
| 6 | ✅ | 26 条概念级对照未登记 | 全部登记入 `OSS-REFS.md §四`（模式名级纪律：禁止附加未核验的具体断言） |

### 二、自检与一致性（通过项）

- R3 引入的 8 处 `refs/…:file:line` 引用**逐一 grep 复核无一失准**；
- 跨批次路径口径：nfv-proxy `/BINLOG/{vpcId}/{资源组}` vs go_proxy `/CDB_BINLOG/*`——两条通道、互不冲突（event_2_zk.go 已注明差异）；
- fusion 幂等键"version+scopeId"经 `test-good/02-vpc5-gen3/vpc-fusion-position.md` 复核：**材料有据**（scopeId 示例、version 流、`t_generic_snapshot_version` 版本列表——同文件另见 BTree degree=16 等 broker 材料，留 L3）；
- 阈值口径标注一致性：60s（sdn-oss=材料）/90s（fusion=推导）/300s（nfv-proxy=示意）——三处均带标注。

### 三、遗留

- 无阻断。Curator / Debezium / DPVS / Katran / gobgp 未克隆——已登记 `OSS-REFS.md §四`，按需补拉。

---

*Round R4 完成：34 条对照点全部完成"核验/补证/登记"三分类处置；`py_compile` + `go build/vet` 复验全绿。*
