# 开源精读 02 · calico felix + typha：agent 的 delta 与 fan-out

> 课次：开源精读路线第 2 课（链②：分发与 agent）
> 对象：`refs/calico`（sparse：`felix/ + typha/`，commit `7b5254b`）
> 对照：我们的一代分发链（nfv-proxy → vpc_agent）与二代 broker（agent-broker）
> 纪律：所有路径与行号来自实仓核验（写入前 ls/grep/sed 逐条核过）。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **datastore** | 配置的源（K8s API Server 或 etcd）——相当于我们的 MySQL"权威库" |
| **Felix** | 每节点 agent：把期望配置算成内核规则（iptables/eBPF）。本课对照 vpc_agent |
| **Typha** | 扇出代理：一个实例服务几百个 Felix，替 datastore 挡住连接与事件放大。本课对照 agent-broker |
| **calc graph** | 计算图：从 datastore 对象**增量推导**出派生对象（endpoint→ipset 成员→规则） |
| **delta / resync** | 增量下发 / 全量重算校正——本课主线（对照我们"快照+binlog"与 Inspector 对账） |
| **breadcrumb（面包屑）** | Typha 缓存里的"快照+序列号"——felix 断线续传的一致点 |
| **InSync 三态** | `WaitForDatastore` → `ResyncInProgress` → `InSync`——同步就绪的可见状态机 |

## ① 它干了什么——一个例子

给集群里一个 Pod 打上一个被 NetworkPolicy 选中的标签。随后：

1. datastore 产生一串 update 事件；
2. **不是每个节点的 Felix 都直连 API**——Typha（每个实例）作为**它所属那批 Felix 的唯一上游 watcher** 收下事件流，**扇出**给几百个 Felix；
3. 每个 Felix 把 updates 喂进 **calc graph**：从"Pod/策略"这些源对象**增量推导**出派生对象（ipset 成员、iptables 链规则……）；
4. 推导出的 deltas 通过进程间协议发给**子进程 dataplane driver**，落成内核规则；
5. 全过程中 Felix 的同步状态在 `WaitForDatastore → ResyncInProgress → InSync` 间流转；**任何环节可疑，都有一条"全量 resync"兜底路径把状态重新校准**。

输入是一个标签变更；输出是"每个受影响节点的内核里多/少一条规则"，且**漏了也能自愈**。

## ② 链路走查（file:line 级）

### 跳 1：Felix 进程入口——共识超集

`felix/daemon/daemon.go:115`：

```go
func Run(configFile string, gitVersion string, buildDate string, gitRevision string) {
	// Special-case handling for environment variable-configured logging:
	// Initialise early so we can trace out config parsing.
	logrusr.ConfigureEarlyLoggingFromEnv("felix")
	...
	ctx := context.Background()
	...
	buildInfoLogCxt.Info("Felix starting up")

	// Health monitoring, for liveness and readiness endpoints.  The following loop can take a
	// while before the datastore reports itself as ready - for example when there is data that
	// needs to be migrated from a previous version - and we still want to Felix to report
	...
```

注意起点就写着：**datastore 可能很久才 ready，Felix 必须为此设计**——"就绪"不是理所当然的（与我们 `init_latency` 的关切同构）。

### 跳 2：dataplane 的**三形态**——进程内是默认，"子进程"只是可选形态

选形态在 `felix/dataplane/driver.go:64`（spawn 于主流程 `felix/daemon/daemon.go:456`：`dpDriver, dpDriverCmd = dp.StartDataplaneDriver(...)`）：

```go
	configParams *config.Config,
	...
) (DataplaneDriver, *exec.Cmd) {
	if !configParams.IsLeader() {
		// Return an inactive dataplane, since we're not the leader.
		log.Info("Not the leader, using an inactive dataplane")
		return &inactive.InactiveDataplane{}, nil
	}

	if configParams.UseInternalDataplaneDriver {
		log.Info("Using internal (linux) dataplane driver.")
		...
		intDP := intdataplane.NewIntDataplaneDriver(dpConfig)     // :514 —— 进程内实现
		...
	} else {
		log.WithField("driver", configParams.DataplaneDriver).Info(...)
		return extdataplane.StartExtDataplaneDriver(configParams.DataplaneDriver)  // :530 —— 外部子进程
	}
```

**三形态**：非 leader → `InactiveDataplane`（空实现，不碰内核）；leader + 内部驱动（**现代默认**）→ `intdataplane` **进程内**（同进程 goroutine + iptables/nftables/eBPF 客户端），此时返回的 `exec.Cmd` 是 `nil`；leader + 外部驱动 → `extdataplane` **子进程**。上一稿"dataplane 是子进程"是错的——那是**老式外部驱动形态**；`monitorAndManageShutdown` 里的 `if driverCmd == nil { log.Info("No driver process to monitor") }` 正是"进程内模式没有子进程可监控"的显式分支。

父子生死约定（仅外部驱动形态有子进程；内部形态 `driverCmd == nil` 时这段逻辑空转），`felix/daemon/daemon.go:820-823` 原注释：

```go
	// Start a background thread to tell us when the dataplane driver stops.
	// If the driver stops unexpectedly, we'll terminate this process.
	// If this process needs to stop, we'll kill the driver and then wait
	// for the message from the background thread.
	driverStoppedC := make(chan bool)
	go func() {
		...
		err := driverCmd.Wait()
		log.WithError(err).Warn("Driver process stopped")
		driverStoppedC <- true
	}()
```

**语义（修正上一稿的含糊表述）**：driver 意外停止 → **felix 终止自身**（进程级恢复交给容器/系统管理器，不是父进程原地重启子进程）；felix 需要停 → **先 kill driver、再等它退出**。双向绑定。

两者间的消息协议：`DataplaneConnector`（`daemon.go:1158`；`readMessagesFromDataplane@1230` 收 driver 状态消息 / `sendMessagesToDataplaneDriver@1451` 发 deltas）。

### 跳 3：calc graph——异步 + 输出侧漏桶

`felix/calc/async_calc_graph.go:32`（节流常量）与 `:45`（状态映射）：

```go
const (
	tickInterval    = 10 * time.Millisecond   // :32
	leakyBucketSize = 10                      // :33
)
...
	statusToGaugeValue = map[api.SyncStatus]float64{   // :45
		api.WaitForDatastore: 1,
		api.ResyncInProgress: 2,
		api.InSync:           3,
	}
```

节流的**真实语义在输出侧**（上一稿写成"消费侧"过粗）——updates 到达即入图（`inputEvents chan any` 容量 10，`OnUpdates` 直接入队），但**向下游 flush 受漏桶限速**（`maybeFlush@206`）：

```go
// maybeFlush flushes the event buffer if: we know it's dirty and we're not throttled.
func (acg *AsyncCalcGraph) maybeFlush() {
	if !acg.dirty {
		return
	}
	if acg.flushLeakyBucket > 0 {
		log.Debug("Not throttled: flushing event buffer")
		acg.flushLeakyBucket--
		...
		acg.CalcGraph.Flush()
		acg.eventSequencer.Flush()
```

每 10ms tick 桶 +1（上限 10），flush 一次 -1：**输入不丢，输出削峰**。另外就绪语义也在这里（`reportHealth@196`，`Ready: acg.syncStatusNow == api.InSync` @200）：**readiness 直接绑 InSync 状态**。

同步状态机同时被暴露成指标（`felix_resync_state`）：**"我在 ResyncInProgress"是系统的一个正常状态**，不是告警状态。

### 跳 4：delta 落地 + full resync 兜底

`felix/dataplane/linux/int_dataplane.go:2320`（下发引擎内部注释）：

```go
		// iptables now and will wait for the full resync. That could be temporarily
		...
				Warn("Some early rules on filter FORWARD, skipping adding other, full resync will resolve it.")
		...
				Warn("Some early rules on filter OUTPUT, skipping adding other, full resync will resolve it.")
```

下发引擎允许"暂时不完整"——**它明确知道自己有个兜底：full resync 会把一切推平重算**。delta 管速度，resync 管正确。

### 跳 5：Typha 的定位——fan-out 代理

`typha/README.md`（原文）：

> An instance of Typha sits between the datastore (such as the Kubernetes API server) and many instances of Felix.
> Since one Typha instance can support **hundreds of Felix instances**, it reduces the load on the datastore by a large factor.
> Since Typha can filter out updates that are not relevant to Felix, it also reduces Felix's CPU usage. In a high-scale (100+ node) Kubernetes cluster, this is _essential_ ...
> Typha is strictly necessary ... with a cluster greater than **50 nodes**. Generally not required for ... etcdv3 datastore directly.

三个设计断言就在 README 里：**挡连接放大、滤掉无关更新、规模门槛（50 节点 / etcdv3 直连可免）**。Felix 侧的发现逻辑在 `felix/daemon/daemon.go:1653 createTyphaDiscoverer`。

### 跳 6：Typha 的核心——快照 + breadcrumb（而不是"纯转发"）

`typha/pkg/snapcache/cache.go:121`：

```go
type Cache struct {
	config Config

	inputC chan any

	pendingStatus  api.SyncStatus
	pendingUpdates []api.Update

	// kvs contains the current state of the datastore.  Its keys are the serialized form of our model keys
	// and the values are SerializedUpdate objects.
	kvs *btree.BTreeG[syncproto.SerializedUpdate]
	// breadcrumbCond is the condition variable used to signal when a new breadcrumb is available.
	breadcrumbCond *sync.Cond
	...
	// currentBreadcrumb points to the most recent Breadcrumb, which contains the most recent snapshot of kvs.
	// As described above, we use an unsafe.Pointer so we can do opportunistic atomic reads of the value to avoid
	// blocking.
	currentBreadcrumb unsafe.Pointer
	...
```

```go
func (c *Cache) CurrentBreadcrumb() *Breadcrumb {
	return (*Breadcrumb)(atomic.LoadPointer(&c.currentBreadcrumb))
}

func (c *Cache) OnUpdates(updates []api.Update) {
	if len(updates) == 0 {
		log.Debug("Ignoring 0-length update")
		return
	}
	c.inputC <- updates
}
```

要点：Typha **不是把事件流原样转发**，而是把流物化成一个 **BTree 有序 KV 缓存**，并周期发布 `Breadcrumb`（快照+序列号，`publishBreadcrumbs@368`，注释里明说用 unsafe.Pointer 做无锁读——为的是**不阻塞新客户端**）。协议侧（`typha/pkg/syncproto/sync_proto.go:26` 起）定义了握手顺序：**先整份 snapshot，再 KVs 增量，最后 `SyncStatus(InSync)`**——新 Felix 接入永远从"一致点"开始。

### 跳 7：扇出服务本体

`typha/pkg/syncserver/sync_server.go`：`New@382`（按 `SyncerType` 取不同 `BreadcrumbProvider`）、`SetMaxConns@442`（**连接数上限**——几百个 Felix 的多路复用是有预算的）、`serve@451`（accept 循环）、`recordConnection@623`。压缩协商在 `syncproto`（zstd / snappy，DESIGN.md 记录 zstd 约为 snappy 一半体积）。

## ③ 设计推导（问题树）

**Q1：Typha 防的是哪一层放大？**
datastore 侧：N 个节点 = N 个 watcher + N 份事件分发；事件量随集群规模增长。fan-out 后：上游 1 份（或几份）、下游 N 份从内存扇出；再加"过滤无关更新"。这是典型的**读放大治理**。（对照：我们 agent-broker 在二代链上的位置；nfv-proxy 在一代链上的位置——**不同代际、同一个方程**。）

**Q2：为什么 Typha 必须实现"快照+breadcrumb"，纯转发不够吗？**
纯转发只对"在线且跟得上"的消费者成立。新加入/断线太久的 Felix 无法从"当前时刻"开始收增量（缺历史、缺一致性点）。Breadcrumb = **给缓存打序列号快照**：客户端要么从快照重建、要么从某个 seq 续传。代价是 Typha 要一直持全量（内存 × 有序结构）。——与我们"CKV 快照 + /BINLOG 增量 + 版本号"是完全同构的三件套。

**Q3：为什么 delta 与 full resync 必须成对？**
delta 快、细、省，但**静默错误会长期存在**（丢一条事件没人知道）；resync 慢、粗、重，但**权威**。系统显式接受"短暂不一致"（int_dataplane.go:2320 的注释就是现场），用周期性 resync 把半衰期压到可接受。代价：**静默错误存活窗口 = resync 周期**。——我们的对应物：vpc_agent Inspector 对账；但注意我们的对账是**报告制**（差异报告、不自动修），felix 是**自动修**（重算+覆盖）。差异根因见对照卡。

**Q4：calc graph 为什么要异步 + 漏桶？**
datastore 突发（比如批量改配置）时，同步回调不能被长计算卡住（否则心跳/状态机全停）；漏桶 + 短 tick 让"消费速率"平滑，同时保持低延迟。代价：**数据面看到的是"滞后收敛"**，需要 InSync 状态机向外部表达这个事实。——对照我们：nfv-proxy `proxy_merge_master` 的计数限流（防 vm_prop 拖死队列）、vpc_agent 的 `rtable_processor` 独立进程。**同一个"削峰"问题。**

**Q5：为什么 dataplane 要抽象成 `DataplaneDriver` 接口（三形态）？**
接口化让"**管不管事**（leader→inactive）"与"**跑在哪里**（进程内 / 外部子进程）"成正交选择。子进程形态的收益：故障隔离（driver 崩了主进程感知并整进程重建）、独立升级、语言/权限边界；代价：多一层 IPC 协议 + 双向生命周期管理（`monitorAndManageShutdown` 的复杂度全是这份税）。**默认进程内 = 少交这份税**；`driverCmd == nil` 时监控逻辑自动空转，接口把两种形态统一了。——我们 vpc_agent 是进程内 state→netlink；DPDK 通道恰是"子进程形态"同款：TCP+protobuf 到 PAL 进程。

## ④ 对照卡（vs 我们）

| 维度 | calico（felix+typha） | 我们 | 差异根因 |
|---|---|---|---|
| 扇出层 | Typha（>50 节点必需；etcdv3 直连可免） | agent-broker（二代）；nfv-proxy（一代）| 我们**从来没有"直连"选项**——规模与 ZK 扇出成本决定的 |
| 一致点 | breadcrumb（BTree 快照+seq） | CKV 快照 + 版本号 + /BINLOG | 同构：都是"快照+增量+序号" |
| 增量处理 | calc graph（异步+漏桶） | 期望状态生成器（proc/vpcgw_proc）+ merge 限流 | 同为"派生计算 + 削峰" |
| 兜底 | full resync（自动重算覆盖） | Inspector 对账（**报告制**）+ 快照重拉 | **风险画风**：K8s 单租户域 vs 云控制面多租户——我们不敢自动修（误修代价高，见 R1 对账误删现场） |
| 就绪语义 | InSync 三态显式化 | ready ZK / ready vpc 列表 / init_latency | 同题：**"已就绪"必须可观测** |
| 下发进程模型 | `DataplaneDriver` 接口三形态（**默认进程内**；外部驱动可子进程） | 进程内 netlink；DPDK 走 TCP+protobuf | 同为"隔离 vs 简单"的正交选择 |

## ⑤ 验收题

1. Typha 防的是哪一层放大？"50 节点 / etcdv3 可免"这两个边界分别说明什么约束在生效？
2. breadcrumb 是什么？一个断线 30 秒的 Felix 重连，从协议上如何保证不丢不重？（提示：snapshot→KVs→SyncStatus(InSync) 的顺序）
3. Felix 的同步三态是哪三个？`ResyncInProgress` 期间 dataplane 为什么可以"等"而不慌？
4. delta 与 resync 各兜什么底？为什么 calico 敢"自动修"，我们的对账却是"报告制"？
5. `DataplaneDriver` 的三形态是什么？为什么"子进程"只是可选项？`driverCmd == nil` 时 `monitorAndManageShutdown` 说了什么？

---

*本课行号清单（供追问对质）：`felix/daemon/daemon.go` Run@115、dpDriverCmd spawn 调用@456、monitorAndManageShutdown@775/813、driver 生死约定注释@820-823、`driverCmd == nil` 分支@826、DataplaneConnector@1158、readMessagesFromDataplane@1230、sendMessagesToDataplaneDriver@1451、createTyphaDiscoverer@1653；`felix/dataplane/driver.go` StartDataplaneDriver@64、IsLeader 分支@74、UseInternalDataplaneDriver@80、进程内实例@514/return@525、外部驱动@530（import 别名 external@47 / inactive@48 / linux@49）；`felix/calc/async_calc_graph.go` tickInterval@32、leakyBucketSize@33、statusToGaugeValue@45、inputEvents 入队@104/124、flushTicks 赋值@257、reportHealth@196（readiness@200）、maybeFlush@206（桶扣减@212）；`felix/dataplane/linux/int_dataplane.go` full resync 注释@2320；`typha/pkg/snapcache/cache.go` Cache@121、CurrentBreadcrumb@255、OnUpdates@265、Start@274、fillBatchFromInputQueue@296、publishBreadcrumbs@368；`typha/pkg/syncserver/sync_server.go` New@382、SetMaxConns@442、serve@451（accept 循环@532）、recordConnection@623；`typha/pkg/syncproto/sync_proto.go` 握手注释@26/34、InSync@204、Envelope@210；typha README/DESIGN（fan-out、50 节点、zstd/snappy）。*

*审查记录（本轮"深审"）：**修正一处重大误述**——"dataplane 是子进程"实为"`DataplaneDriver` 接口三形态（非 leader/inactive；leader+内部驱动=进程内【默认】；leader+外部驱动=子进程）"，并解释 `driverCmd == nil` 分支；修正 2 处行号（async 常量 31→32/33、状态映射 44→45）；重写"漏桶语义"（节流在**输出 flush**侧，非消费侧，附 `maybeFlush` 原文）；修正 driver 死亡语义（"重启或带因退出"→"**终止 felix 自身**，恢复交容器层"）；补 spawn 点（`daemon.go:456` → `driver.go:64`）。全部回仓核验。*

*审查记录（二次深审，专门查"上一轮修正自身"）：**抓到本轮修正引入的一处新错**——driver 生死约定注释实为 `:820-823`（首轮修正时写了 `:818`，差 2 行）；补齐细粒度引用：内部驱动 `return intDP, nil@525`（"exec.Cmd 为 nil"实锤）、import 别名 `external@47 / inactive@48 / linux@49`、`flushTicks` 赋值@257（10ms tick 实锤）、`IsLeader@74`、serve 内 `l.Accept()@532`；措辞精度：Typha"唯一上游 watcher" → "**它所属那批 Felix 的**唯一上游 watcher"（Typha 可多实例部署）。*
