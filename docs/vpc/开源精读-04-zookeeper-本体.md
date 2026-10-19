# 开源精读 04 · ZooKeeper 本体："树"这台机器的四个真相面

> 课次：开源精读路线第 4 课（链②的"为什么"· 分布式底座）
> 对象：`refs/zookeeper`（commit `48f7cb0`）；**版本口径声明**：仓库 = 当前 master（含 `NodeHashMap`、TTL 节点、持久 watch 等新特性）；我们生产 = 定制 3.4.6 / 3.5.6——**概念一致、细节有代差**，凡涉及代差处单独标注。
> 配套材料：`vpc/test-good/01-zk-distributed/`（zk-learning-path Step1-4、zk-knowledge §12/§13、zxid_counter_extend 等）+ `vpc/papers/07-ZooKeeper-ATC2010.pdf`
> 纪律：路径与行号来自实仓核验。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **zxid** | 全局事务 id，64 位 = `epoch(32) \| counter(32)`——epoch 是"领导任期"、counter 是任期内序号 |
| **session / ephemeral** | 连接会话；会话名下的临时节点——**会话断，节点自销**（DataTree 按 session 记名） |
| **watch（STANDARD）** | 一次性订阅：触发一次即被移除，想续看得重新注册（新版本另加了 PERSISTENT 模式） |
| **ZAB** | ZK 的全序广播协议；quorum 里有 LOOKING / FOLLOWING / LEADING / OBSERVING 四种状态 |
| **commitProcessor** | 请求管道里"读并行、**写单飞行**"的收口环节（全序提交的实现） |

## ① 它干了什么——两个 10 秒小实验

**A. 一个 ephemeral 节点的生与死**：客户端 `create -e /locks/x` → 请求进管道 → 生成事务并**提议**给 quorum（成员确认前先经 `FollowerZooKeeperServer.logRequest@79` 转交落盘管道——"pendingTxns.add + syncProcessor.processRequest"）→ 多数派确认后**提交**：应用进 **DataTree**（`FinalRequestProcessor.processTxn@112`）并记入"session→路径"的 ephemeral 账本。客户端来不及续会、session 超时 → `killSession@1147` 把该 session 名下**所有** ephemeral 路径一次删光。

**B. 一个 watch 的生死**：`getData(/locks/x, true)` 注册 watch（STANDARD 模式 = 一次性）→ 别人改了 `/locks/x` → watch 触发一次、**随即被移除**；想继续看，必须**重新注册**——注册与下次事件之间的那条缝，客户端自己负责补。

## ② 链路走查（file:line 级）

### 跳 1：数据树——全内存 + 三本账

`server/DataTree.java`：核心字段（master 版本已换成 `NodeHashMap`）：

```java
public class DataTree {                                   // :95
    private final NodeHashMap nodes;                      // :105 —— 全量 znode 常驻内存
    ...
    private final Map<Long, HashSet<String>> ephemerals = new ConcurrentHashMap<>();  // :154 —— session→路径账本
    private final Set<String> containers = ...;           // :159 —— 容器节点（新特性）
    private final Set<String> ttls = ...;                 // :164 —— TTL 节点（新特性）
```

会话终结时"一笔勾销"在 `killSession`（`:1147`）：`killSession(session, zxid, ephemerals.remove(session), null)`。注释里还有一条呼应写管道的纪律（`:1141-1146`）："This is only called from FinalRequestProcessor so there is no need for synchronization"——**单线程提交让这类共享状态免锁**。

节点本体 `server/DataNode.java` 的类注释一句话说全："A data node contains a reference to its parent, a byte array as its data, an array of ACLs, a stat object, and a set of its children's paths."——再往上有个值得注意的优化：

```java
    // the digest value of this node, calculated from path, data and stat
    private volatile long digest;
    // indicate if the digest of this node is up to date or not, used to
    // optimize the performance.
    volatile boolean digestCached;
```

**全内存换读性能**（`digestCached` 注释原文："used to optimize the performance"）——代价直接写进它的"容量账"（`zk-knowledge.md §12` 有同源的 Watch 内存账）：**payload 不能大、节点数×副本数就是内存下限**。

### 跳 2：Watch——双索引表 + 一次性语义

`server/watch/WatchManager.java` 类注释："This class manages watches. It allows watches to be associated with a string and removes watchers and their watches in addition to managing triggers."（`:42-45`）。两本账：

```java
    private final Map<String, Set<Watcher>> watchTable = new HashMap<>();            // :50 路径→watchers
    private final Map<Watcher, Map<String, WatchStats>> watch2Paths = new HashMap<>(); // :52 watcher→路径
```

双索引的理由：**注册**走 path 侧（`addWatch@75`）、**连接断开清理**走 watcher 侧（`removeWatcher@113`——顺带解释了为什么"漏清"会成为一类 bug）。"一次性"的最终定义在 `server/watch/WatcherMode.java`：

```java
public enum WatcherMode {            // :23
    STANDARD(false, false),          // :24 —— 默认=一次性（isPersistent=false）
    PERSISTENT(true, false),         // :25 —— 后加的持久模式
    PERSISTENT_RECURSIVE(true, true),// :26
    ;
    public static final WatcherMode DEFAULT_WATCHER_MODE = WatcherMode.STANDARD;  // :29
```

**我们的一手坑**（材料 `zk-learning-path.md` Step 2）："Watch 为什么一次性？关联分支：`bugfix/fix_trpc_unwatch`（agentos）— **watch 泄漏**，删 watch 时没清理 map"——双索引表 + 一次性语义叠加出的客户端注册纪律，我们为它付过血。

### 跳 3：持久化与 zxid——我们改过的地方

追加写在 `server/persistence/FileTxnLog.java:275`：`public synchronized boolean append(Request request) throws IOException`（append-only + **磁盘预分配**，实据 `:188`/`:397`；快照由 `FileSnap` 兜底）。

zxid 的结构（我方材料 `zxid_counter_extend.md`）：

```
┌─────────────────┬──────────────────────────────────┐
│  epoch (32 bit) │           counter (32 bit)        │
└─────────────────┴──────────────────────────────────┘
```

以及一份"我们动过 ZK"的一手实证：定制仓 `vpc_zookeeper_3_5_6` 的 `fix/zxid_counter_extend`（**7 文件 +40/-15，未合入**——团队最终选了"跳过特殊值"方案）。配套的运维基建：`vpc_zk_differ` 的 **SnapLogTruncater**（按 zxid 截断修复，`docs-by-topic/46`）、`zk_agent` 的 snapshot 清理灰度（`feature/clear_snapshot_2605`）。

### 跳 4：QuorumPeer 与 ZAB——四种状态机

`server/quorum/QuorumPeer.java:579-582`：

```java
        LOOKING,      // 找人：正在选举
        FOLLOWING,    // 跟随者：同步 leader
        LEADING,      // 领导者：排序写
        OBSERVING     // 观众：同步但不投票
```

而我们对 ZK 最深的改造恰好落在 `OBSERVING` 上：`docs-by-topic/31`——**把 observer 角色改造成"跨集群数据同步器"（CrossClusterSynchronizer）**（在该仓库上是 128 个定制提交），把主集群事务数据实时同步到从集群/其他系统——"ZK 的读者"被我们改成了"ZK 的邮差"。

### 收口：五个生产坑 ↔ 代码位置

| 生产坑（zk-knowledge §12.2 原文） | 代码位置（本仓） |
|---|---|
| ChildrenWatch 的 **O(n) diff**（全量拉取无法 scale） | `getChildren` 语义 + DataNode 的 children 集合（比对在客户端做） |
| commitProcessor **单线程**（读写竞争延迟） | `quorum/CommitProcessor.java:54`——"only a single write request to be in flight simultaneously" |
| **Watch 内存** 271 字节/个 × 千万级 | `watchTable`/`watch2Paths` 双 map 的每注册开销 + `WatcherCleaner`（master 版的清理器）|
| agent 重启 **30-40 分钟**重新注册 watch | 一次性语义 × 无持久 watch（master 版才加 `PERSISTENT`）|
| 2019 年**惊群 1TB** 打满交换机 | watch 触发风暴 + 客户端"触发即全量拉取"的用法 |

## ③ 设计推导（问题树）

**Q1：为什么 DataTree 要全内存？**
读写路径上 ZK 的卖点是"协调"——低延迟、强一致。全内存 + digest 缓存把读臂做到极致。代价是**容量上限被内存锁死**（znode 数 × 每节点开销 × 数据）。这就是"ZK 不适合大 payload/大对象量"的第一性解释——不是实现不行，是**它按自己的定位（小数据协调）做的交换**。

**Q2：watch 为什么默认一次性？**
内存上限的自我保护：若默认持久，千万级 watch 的常驻注册会成为内存与触发风暴源。一次性把"服务端记住的状态"压到最小。代价外推给客户端：重注册纪律 + 注册缝 + 泄漏风险——我们 agentos 的 watch 泄漏修复就是这条税的收据。**master 版新增持久模式 = 多年后官方承认这个代价需要另一种付法**（工程上有意思的循环）。

**Q3：写路径为什么敢单线程（commitProcessor）？**
全序提交的最简实现：写请求排成单飞行（"single write request in flight"），读可以并行。ZK 的目标场景写比例极低（协调场景读多写少）——**单线程不是偷懒，是场景匹配**。当 VPC 把它当"配置推送通道"（写频繁、量大）时就崩了——第 3 课 §12 的账在这里对上了。

**Q4：zxid 为什么分 epoch|counter 两段？**
epoch 防"前任 leader 的旧事务被追认"（换了领导 = epoch+1，旧 epoch 的事务一律不认）；counter 保任期内单调。我们的 `zxid_counter_extend` 故事（32 位 counter 的边界）正是这个设计的现实位面：**counter 会打满**，我们量过、改过、最后选了兜底方案。

**Q5：ZAB / 四状态机为什么可以被业务"无感使用"？**
因为 ZK 把复杂性包进了两个业务原语："session + ephemeral"（活着的证据）和"watch"（变化通知）。业务不用懂 LOOKING/LEADING 也能写出"锁"和"注册表"。——这个抽象红利在"协调场景"成立；一旦拿它当**分发管道**（大规模、写频繁、watch 千万级），抽象就开始漏：漏出来的正是 §12.2 五条。

## ④ 对照卡

| 维度 | ZK | etcd | 我们（一代实际） |
|---|---|---|---|
| 数据模型 | 全内存 znode 树 + digest；session→ephemeral 账本 | 平坦 KV，MVCC 落盘 + 内存索引 | ZK 树（+我们的"树只做信号"纪律） |
| 会话/租约 | session 绑定连接；ephemeral 自销 | lease 独立对象、显式续约、可挂多个 key | ZK 会话 + 自建 ready 检查（validator/guard） |
| 变更通知 | watch 一次性（STANDARD）；触发即移除 | watch 持久 + startRevision 重放 | ZK watch + 重注册 + CKV 快照 + /BINLOG + 对账 |
| 持久化 | txnlog 追加（`append@275`） + 快照 | Raft 日志 + MVCC 存储 | +我们自研的 SnapLogTruncater / snapshot 灰度清理 |
| 选举 | ZAB / 四状态机 | Raft | 定制：observer→跨集群同步器（128 提交） |
| 我们的用法史 | "分发中枢"（一代）| — | 二代切 TRPC 心跳、三代完全退出（第 3 课已结案） |

## ⑤ 验收题

1. ephemeral 自销在数据结构上是怎么记的？（哪本"账"、什么时机勾销）
2. watch 的双索引表为什么需要两份 map？"一次性" + 双索引与我们 agentos 的 watch 泄漏 bug 是什么关系？
3. commitProcessor 的"单写飞行"设计在什么场景是优点、被我们推到什么场景后成为瓶颈？§12.2 哪一条是它的直接后果？
4. zxid 的 epoch/counter 各防什么？为什么 counter 会打满——我们当年怎么处理的（材料）？
5. 我们为什么能把 observer 改造成"跨集群同步器"？这个改造说明定制 ZK 的边界在哪（改得动什么、改不动什么）？

---

*本课行号清单（供追问对质）：`server/DataTree.java` 类@95、nodes@105、ephemerals@154、containers@159、ttls@164、killSession@1147；`server/DataNode.java` 类注释@32-38（关键句@35-36）、digest@43、digestCached@47（注释@42/45-46）；`server/watch/WatchManager.java` 类注释@42-45、watchTable@50、watch2Paths@52、addWatch@75、removeWatcher@113；`server/watch/WatcherMode.java` enum@23、PERSISTENT@25、DEFAULT_WATCHER_MODE@29；`server/persistence/FileTxnLog.java` append@275；`server/quorum/CommitProcessor.java` 注释@54、类@77、run@197；`server/quorum/QuorumPeer.java` 四态@579-582；`server/quorum/FollowerZooKeeperServer.java` logRequest@79；`server/FinalRequestProcessor.java` processTxn@112；我方材料：zk-learning-path（Step1-4）、zk-knowledge §12/§13、zxid_counter_extend、docs-by-topic/31（observer→同步器）/46（SnapLogTruncater）。*

*审查记录（二次深审，专查"修正自身"+精化）：① R1 修正自查通过（digest@43/digestCached@47、FileTxnLog 预分配 :188/:397）；② **写路径顺序修正**——原"写 DataTree 后广播 quorum"顺序错了，改为"提议→成员落盘（`FollowerZooKeeperServer.logRequest@79`→syncProcessor）→多数派确认→提交应用（`FinalRequestProcessor.processTxn@112`）"；③ WatcherMode 补全 `STANDARD(false,false)@24`（一次性铁证进引码）；④ killSession 补免锁注释（`:1141-1146`，"仅由 FinalRequestProcessor 串行调用"——单线程管道的直接红利）；⑤ 措辞修正（"digest 为变更比对服务"无据 → 引原文"used to optimize the performance"；"第 12 课"笔误 → `zk-knowledge.md §12`）。*

*审查记录（首轮深审）：修正 2 处——① 清单 DataNode 行号虚报（"类注释@36-40、digest@40/44" → 逐行核出 类注释@32-38 关键句@35-36、digest 字段@43、digestCached@47）；② "磁盘预分配"断言补实据（`FileTxnLog.java:188`（preallocate size 注释）/`:397`（"written more than previously preallocated"））。其余引用（DataTree 五字段/WatchManager 双 map+类注释@42-45/WatcherMode@23·25·29/append@275/CommitProcessor@54·77·197/QuorumPeer@579-582）逐条 grep 命中。*
