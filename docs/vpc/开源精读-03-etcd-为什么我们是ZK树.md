# 开源精读 03 · etcd：lease / watch / MVCC——"为什么我们是 ZK 树"

> 课次：开源精读路线第 3 课（链②的"为什么"：分布式底座）
> 对象：`refs/etcd`（commit `d3c8a15`）；我方材料：`vpc/test-good/01-zk-distributed/`（7 份）
> 对照：我们一代的 ZK 树分发 / 分区 ZK / zklock；二代 agent-broker；三代 fusion
> 纪律：所有路径与行号来自实仓核验。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **MVCC / revision** | 多版本并发控制：每次写产生全局递增 `revision`（Main/Sub 两级）；历史可读、watch 可从任意版本重放 |
| **lease** | 租约对象：`grant` 出 TTL，客户端 `KeepAlive` 续；过期 → **挂在它身上的 key 被自动删除** |
| **watch** | 键/前缀订阅：etcd 支持**从指定 revision 开始** watch（离线补课内建）；ZK 的 watch 是**一次性**的（触发后需重注册） |
| **ZXID / 会话 / ephemeral** | ZK 三件套：全局事务 id（序）/ 连接会话 / 临时节点（会话断则自销） |
| **ZAB / Raft** | 两家的"全序广播"共识协议；都保证所有写经过单一日志排序 |

## ① 它干了什么——一个例子

**etcd 版（10 秒小实验）**：`lease grant 10` 得到租约 L；`put /locks/x v --lease=L`；进程崩了没人 `KeepAlive` → 10 秒后 L 过期 → **`/locks/x` 被服务端自动删除**。另一头：客户端离线了 5 分钟，重连后说"从 revision 100 开始 watch"→ **中间 5 分钟的所有事件被重放补齐**（不用先读快照再猜缝）。

**ZK 版（同题）**：`create -e /locks/x`——会话断，ephemeral 自销（这一点两家殊途同归）；但 watch 是**一次性**的：触发一次就没了，客户端必须**重新注册**——注册与事件之间那条缝，要靠客户端自己补（我们 agentos 的 `bugfix/fix_trpc_unwatch`（watch 泄漏）与 rtable 的"快照+增量+重注册"补丁，全是围着这条缝做工程）。

一句话：**etcd 把"离线补课"和"活着的证据"都内建成了服务端原语；ZK 把"活着的证据"内建（会话），把"补课"外推给客户端。**

## ② 链路走查（file:line 级）

### 跳 1：revision——序中序，重放的地基

`server/storage/mvcc/revision.go:35`：

```go
type Revision struct {
	// Main is the main revision of a set of changes that happen atomically.
	Main int64
	// Sub is the sub revision of a change in a set of changes that happen
	// atomically. Each change has different increasing sub revision in that
	// set.
	Sub int64
}
```

同文件 `:23-26` 的编码注释（const 块 `:22-33`）说明这是**磁盘上的字节格式**（8 字节 Main + `'_'` + 8 字节 Sub，大端）：

```go
	// revBytesLen is the byte length of a normal revision.
	// First 8 bytes is the revision.main in big-endian format. The 9th byte
	// is a '_'. The last 8 bytes is the revision.sub in big-endian format.
	revBytesLen = 8 + 1 + 8
```

**Main/Sub 两级 = "原子批 + 批内序号"**：一次 txn 里改 3 个 key 共享同一个 Main、Sub 递增——这正好回答我们快照体系里最难的一类问题："一批变更要么一起可见，要么都不可见"（对照我们的 `version` 单调 + 批内序）。

### 跳 2：lease——"活着的证据"对象化

`server/lease/lease.go:27`：

```go
type Lease struct {
	ID           LeaseID
	ttl          int64 // time to live of the lease in seconds
	remainingTTL int64 // remaining time to live in seconds, if zero valued it is considered unset and the full ttl should be used
	...
	expiry time.Time

	// mu protects concurrent accesses to itemSet
	mu      sync.RWMutex
	itemSet map[LeaseItem]struct{}
	revokec chan struct{}
}

func (l *Lease) expired() bool {
	return l.Remaining() <= 0
}
```

关键设计：**key 不自己带 TTL，而是"挂"在一个独立对象上**（`itemSet map[LeaseItem]struct{}` = 这个租约下挂着的全部 key）。"过期 → key 被删"落到代码上就是 `Revoke`（`lessor.go:326`）：

```go
	// sort keys so deletes are in same order among all members,
	// otherwise the backend hashes will be different
	keys := l.Keys()
	sort.StringSlice(keys).Sort()
	for _, key := range keys {
		txn.DeleteRange([]byte(key), nil)
	}
	...
	// lease deletion needs to be in the same backend transaction with the
	// kv deletion. Or we might end up with not executing the revoke or not
	// deleting the keys if etcdserver fails in between.
	schema.UnsafeDeleteLease(le.b.BatchTx(), &leasepb.Lease{ID: int64(l.ID)})
	txn.End()
```

两条教科书注释划重点：① **删除要排序**——"所有成员同一顺序，否则后端哈希不同"（复制状态机的可复现纪律）；② **lease 删除与 key 删除同一 backend 事务**——否则会出现"租约撤了、key 还在"的半截状态。配套函数全景：`Grant@281` / `Revoke@326` / `Checkpoint@367` / `Renew@396` / `expireExists@700`。

对照 ZK：ephemeral 是**节点属性 + 会话绑定**（连接断 → 会话超时 → 节点自销）；etcd 是**显式对象 + 可挂载**（一个 lease 挂多个 key；续约一次，多个 key 一起保命）。差异的后果：
- ZK 式：连接抖动直接威胁"命"（我们 zklock 雪崩的根因之一——**会话语义的破坏被当成了节点死亡**）；
- etcd 式：续约是显式动作、失败语义由客户端定义（业务可以选择"续不上也别死"）。

### 跳 3：watch——"从 revision 100 重放"是服务端能力

`server/storage/mvcc/watchable_store.go`：watcher 结构持有 `startRev`/`minRev`（`:128-133`），并且维护两组 watcher：

```go
	// contains all unsynced watchers that needs to sync with events that have happened
	unsynced watcherGroup
```

```go
	synced := startRev > s.store.currentRev || startRev == 0
```

`unsynced` = "事件已经发生、但 watcher 还没补完"的对照组（`syncWatchersLoop@87` 持续消化）。线上入口在 `server/etcdserver/api/v3rpc/watch.go:340`：

```go
			id, err := sws.watchStream.Watch(ctx, mvcc.WatchID(creq.WatchId), creq.Key, creq.RangeEnd, creq.StartRevision, filters...)
```

`creq.StartRevision` 直接进 watchStream——**客户端断线重连只需报一个版本号**。对比 ZK 一次性 watch + 客户端重注册：那条"注册与事件之间的缝"在 etcd 里由服务端的历史版本填平。

### 跳 4（对照）：ZK 侧的复杂度证据（我方材料）

`vpc/test-good/01-zk-distributed/zk-learning-path.md` 的问题清单本身就在"记账"：

> ### Step 2: Watch 机制
> **源码**：`server/WatchManager.java`
> **问题**：WatchManager 用什么数据结构？**Watch 为什么一次性？**
> **关联分支**：`bugfix/fix_trpc_unwatch`（agentos）— **watch 泄漏**，删 watch 时没清理 map

以及 Step 1（数据树 DataTree/DataNode）、Step 3（txn log/snapshot）、Step 4（QuorumPeer 状态机/ZAB）——四个 Step 就是"ZK 树"这台机器的四个真相面。

## ③ 设计推导（问题树）

**Q1：revision 为什么分 Main/Sub 两级？**
一二级分别回答两个问题："哪些变更是一个原子批"（Main 相同）、"批内的顺序"（Sub 递增）。没有它，"从 revision 重放"就会把原子批拆开、重放出一致性洞。——我们的对应物：`transId`（批序）+ 批内条目序，以及快照的 `version` 语义。

**Q2：lease 与 ZK ephemeral 的本质区别在哪？**
ZK：生命周期**绑定会话（连接态）**；etcd：生命周期是**独立对象，可挂载、显式续约**。后果：ZK 把"网络抖动"翻译成"节点死亡"（抖 = 命没了）；etcd 把两者解耦，客户端可以自己决定"续不上时怎么办"。我们吃的第二大血案（ZK 锁 LOST→suicide 雪崩）正是 ZK 语义的这条性质 + 业务策略叠加的产物。

**Q3：watch replay 把复杂度放在了哪一侧？**
etcd 放在**服务端**（保留历史版本、维护 unsynced 组、compaction 与"版本已被压掉"的错误语义——`ErrCompacted = "mvcc: required revision has been compacted"`，`server/storage/mvcc/kvstore.go:38`）；ZK 放在**客户端**（快照兜底 + 重注册 + 去重）。我们一代选了 ZK → 复杂度全落在客户端侧 → 于是我们的分发体系长出了"CKV 快照 + /BINLOG + 重注册 + 对账"这一整层工程。**这层工程不是偶然，是选了 ZK 的必然税**。

**Q4：那当年"为什么是 ZK 树"？**
三条原语恰好三合一：① **层级命名空间**——资源天然是树（/TVPC/vpc/…），路径即语义；② **子树通知**——"我关心这个 VPC 子树的一切变化"，child watch 直接表达；③ **会话/临时节点**——agent/进程"活着"的注册原语。再加上时代约束：一代立项在 etcd v3（MVCC + watch replay）成型之前（v3.0 发布于 2016，**公开史实，非本仓证据**），ZK 是彼时经过超大规模验证的选项；且团队有现成的 ZK 基础设施与定制能力（旁证：内网维护着定制 ZK 仓——跨集群同步器 / 3.5.6，见 `vpc/docs-by-topic/31`、`32`）。——**当年的最优解，不是永恒的正确**。

**Q5：后来为什么在逐步"退出 ZK"？**
"树"的三条原语同时带来三个负担，我们的三代演进就是逐条还债：
- **payload 小**（znode/全内存约束）→ 数据引流到 CKV（ZK 只留配置树+触发信号）；
- **一次性 watch** → 重注册复杂度 + 泄漏风险 → agentos 修 watch 泄漏；二代改用 TRPC 心跳增量（agent-broker）；
- **会话语义绑生死** → 雪崩 → zklock 迁 DB 锁。
三代 fusion 甚至整套都不碰 ZK：选举用 Redis（`SET NX EX` + Lua 续约校验）、增量走 Kafka、存储 LevelDB。这条"退出"路线有**材料级的定量证据**（`vpc/test-good/01-zk-distributed/zk-knowledge.md §12.2`）：ChildrenWatch 的 **O(n) diff**、commitProcessor 单线程、**Watch 内存 271 字节/个 × 千万级**、agent 重启 **30-40 分钟**重新注册 watch、**2019 年惊群 1TB 流量打满交换机**；§12.3 的 Gen2 答案表一句话——**不让 ZK 干它不该干的事**（全量状态→CKV 快照；布尔通知→Kafka binlog；持久缓冲→分区 ZK 临时节点）。**"ZK 树"的地位从"分发中枢"退到"一部分场景的配置快照"，这正是架构演进的证据。**

## ④ 对照卡

| 维度 | ZK（我们一代的选择） | etcd | 我们（一代实际） |
|---|---|---|---|
| 命名空间 | znode 树（层级） | 平坦 KV + 前缀 | ZK 树（/TVPC/*、分区 /BINLOG） |
| "活着"原语 | 会话 + ephemeral（连接绑定） | lease（独立对象、可挂载、显式续约） | 会话 + 我们自建的 ready 文件/ZK ready 节点 |
| 增量补课 | **客户端**（一次性 watch + 重注册 + 快照兜底） | **服务端**（startRevision 重放 + unsynced 消化） | ZK（子树 watch）+ 自建 CKV 快照 + /BINLOG + 重注册 + 对账（**全客户端侧**） |
| 序模型 | zxid（ZAB） | revision Main/Sub（Raft） | 自建 version/transId + 对账 |
| 大 payload | 不行（引到 CKV） | 有限（`DefaultMaxRequestBytes = 1.5 * 1024 * 1024`，`server/embed/config.go:65`——同样不适合大对象） | 数据走 CKV / 快照，ZK 只做信号 |
| 我们的终局 | — | — | 二代切 TRPC 心跳；三代完全去 ZK |

## ⑤ 验收题

1. revision 的 Main/Sub 各解决什么问题？没有它，"从版本重放"会坏在哪？
2. lease 与 ZK ephemeral 的语义差在哪？为什么说"ZK 把网络抖动翻译成了节点死亡"？
3. "为什么我们是 ZK 树"的三条原语理由是什么？各自的代价（三个负担）与我们的三个补丁是什么？
4. watch replay 的复杂度在 etcd 放在服务端、在 ZK 放在客户端——我们一代为此付出的"客户端税"具体有哪些工程？（至少列 3 个）
5. 三代 fusion 完全不碰 ZK：它的选举/增量/存储各用什么，为什么不需要"树"？

---

*本课行号清单（供追问对质）：`server/storage/mvcc/revision.go` Revision@35、字节格式注释@23-26（const 块 :22-33）；`server/lease/lease.go` Lease@27、expired@51；`server/lease/lessor.go` Grant@281、Revoke@326、Checkpoint@367、Renew@396、expireExists@700；`server/storage/mvcc/watchable_store.go` watchable 接口@49（watch 方法@50）、unsynced 注释@67、watch@128-141、syncWatchersLoop@87；`server/etcdserver/api/v3rpc/watch.go` StartRevision@270/334/340；`server/storage/mvcc/kvstore.go` ErrCompacted@38；`server/embed/config.go` DefaultMaxRequestBytes@65；我方材料：`vpc/test-good/01-zk-distributed/`（zk-learning-path Step1-4、一次性 watch、agentos fix_trpc_unwatch；zk-knowledge §12 五个定量证据 + Gen2 答案表）。*

*审查记录（首轮深审）：修正 3 处引用精度——`expired` 实为 @51（原 @50）、revision 编码注释实为 `:23-26`（原笼统写 :23-31）、1.5MB 限额补实仓出处（`server/embed/config.go:65`）；Q4 补证——"etcd v3 2016 成型"标注公开史实、"有 ZK 基础设施"改为**材料旁证**（定制 ZK 仓 31/32）；Q5 升级——接入 `zk-knowledge.md §12` 的五个定量证据（O(n) diff / 271 字节×千万 / 30-40min / 1TB 惊群）与 Gen2 答案表。全部回仓核验。*

*审查记录（二次深审，专查"修正自身"+机制断言）：① 上一轮修正自查通过——revision const 块 `:22-33`（"const ("@22、" )"@33）、expired@51、DefaultMaxRequestBytes@65 全部复核命中；② **机制升级**——"过期→删 key"补入 `Revoke@326` 实码：删除前排序（"same order among all members, otherwise the backend hashes will be different"——复制状态机可复现纪律）+ lease 删除与 kv 删除**同一 backend 事务**（防"租约撤了 key 还在"）；③ 重放边界补证——`ErrCompacted = "mvcc: required revision has been compacted"`（`kvstore.go:38`）：replay 不是无限的，压缩会截断历史。*
