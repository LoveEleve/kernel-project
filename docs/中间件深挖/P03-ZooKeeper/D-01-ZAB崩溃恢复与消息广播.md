# P03-ZooKeeper D-01 — ZAB 崩溃恢复与消息广播

> **定位**：P03 分布式协调第 5 篇（ZAB 双线：ZooKeeper）。三层齐：**原理 + 使用 + 源码**。
> **源码版本**：ZooKeeper **3.9.5**（`code/spring/zookeeper`；`zookeeper-server/.../server/quorum/`）
> **质量标准**：源码清单 / 完整调用链 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：一个例子的两段旅程

3 节点集群（L 为 Leader，F1/F2），L 挂了：

```
【崩溃恢复】
t0  F1/F2 收不到 L 心跳 → 进入 LOOKING → Fast Leader Election（FLE）
t1  各自广播投票（vote=(leader, zxid, epoch)）；收到更优票就改投
t2  有人拿到多数派票 → 确认 leader（选"数据最新"的）
t3  Discovery：新 leader 收集各 follower 的 epoch 提议 → 定新 epoch(zxid 高 32 位)
t4  Synchronization：逐个决定同步方式——DIFF（增量）/ TRUNC（截断未提交）/ SNAP（全量）

【消息广播】（恢复完成后）
t5  客户端写 → leader 生成 zxid=(newEpoch, counter+1) → 广播 PROPOSAL
t6  follower 按序写 txnlog + 回 ACK
t7  多数派 ACK → leader COMMIT（同时通知 follower commit）→ 写入 DataTree → 应答客户端
```

**一句话**：ZAB = **两种模式的切换**——"崩溃恢复"（选主+定 epoch+日志对齐）与"消息广播"（Proposal→Ack→Commit 的简化 2PC）；zxid 一个 64 位数字同时表达"哪个时代（epoch）+ 时代内第几笔（counter）"。

---

## 二、原理（心智模型）

### 2.1 zxid：ZK 的"term+index"合体

```
zxid（64 位）= epoch（高 32 位，leader 时代）+ counter（低 32 位，时代内事务序号）
makeZxid(epoch, counter)——比较大小即可判断"新旧"：先比 epoch，再比 counter
每次 leader 更替 → epoch+1、counter 归零（旧时代的写永远小于新时代）
```

### 2.2 FLE（Fast Leader Election）——"数据最新者胜"

```
投票 = (leader, zxid, epoch)；判定（totalOrderPredicate）三级比较：
  ① 候选 epoch 更大 → 胜（时代新）
  ② epoch 相同且 zxid 更大 → 胜（日志全）
  ③ epoch/zxid 都相同 → sid（myid）大的胜（打破平局）
收到更优投票 → 改投并重新广播；某投票集达到多数派（hasAllQuorums）→ 当选
与 Raft 的差异：Raft 靠"选举限制"（投票者检查候选日志不旧）间接保证；
             ZAB 直接把"数据最新"做进投票比较（FLE 保证新 leader 必含最全日志）
```

### 2.3 崩溃恢复三阶段

```
① Leader election：FLE 选出新 leader（选中者 epoch = max(收到的 epoch)+1 意向）
② Discovery：leader 与多数派 follower 交换"已接受的 epoch 提议"——确定新 epoch
   （确保新 epoch 大于所有历史 epoch——防旧 leader 的残留事务）
③ Synchronization：对每个 follower 决策同步方式：
   DIFF（只补缺的增量）/ TRUNC（follower 有多余未提交事务 → 截断）/ SNAP（落后太多 → 全量快照）
   → 完成后进入广播模式；各节点恢复"已提交事务全部应用"的一致状态
```

### 2.4 消息广播（简化 2PC）

```
leader 收到写：分配 zxid → 放入 outstandingProposals → 广播 PROPOSAL（FIFO 队列推送）
follower：写 txnlog → 回 ACK（按 FIFO 顺序——保证顺序）
leader：processAck 计数达多数派 → commit（本地提交 + 通知所有 follower commit + 应答客户端）
特点：没有 abort 路径（ZK 的写是"要么成功要么客户端重试"）——简化版 2PC；
     所有写必须过 leader（顺序一致性来源）
```

### 2.5 follower 读的可见性

```
读可以在 follower 本地直接服务（不过 leader）——可能读到"旧但已提交"的数据（ZK 不保证线性读）
要最新读 → sync() 强制与 leader 同步（对应 etcd 的 ReadIndex 场景）
```

---

## 三、使用（运维与观测）

```
四字命令：`echo srvr | nc <zk> 2181`（角色/zxid）；mntr（监控指标，含 zxid/leader）
选举相关配置：tickTime（基本时间单位）、initLimit（follower 初次连接/同步超时，默认 10 ticks，实际=10×tickTime）、
             syncLimit（心跳超时，默认 5 ticks）——选举超时在 initLimit 量级
观测点：谁是 leader、zxid 是否推进、follower 落后（zxid 差）、选举次数（频繁选举=故障）
客户端：读可走任意节点；写自动转发 leader；要强一致读用 sync 后读
```

---

## 四、源码核心（调用链 + 行号）

### 4.1 FLE 选举链

```
QuorumPeer.run：state==LOOKING（QuorumPeer.java:526 枚举/:844/:1469）→ 启动 FLE
FastLeaderElection.lookForLeader（FastLeaderElection.java:913）：
  ├─ 初始自投：updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch())（:944）
  ├─ sendNotifications（:691）广播投票
  ├─ 收票比较：totalOrderPredicate（:723，epoch→zxid→sid 三级）
  │    更优 → updateProposal（:817）+ 重新广播（:1019/1021/1031）
  └─ 计票：voteSet.hasAllQuorums()（:1047）→ 定 leader（checkLeader :1154/1172）
QuorumPeer.setCurrentVote（:669）：落定当前投票
```

### 4.2 广播链（Leader 侧）

```
Leader.lead（Leader.java:632）：主循环——收 follower 连接/处理请求
Leader.propose（:1288）：分配 zxid → Proposal 存 outstandingProposals（:482，ConcurrentMap）→ 广播
Leader.processAck（:1047）：计数 ACK → 多数派 → 从 outstandingProposals 取（:1082）→ commit（:1212）
  → ToBeAppliedRequestProcessor（:1117）保证"先 COMMIT 再应用"的流水
Proposal（静态类 :89，extends SyncedLearnerTracker——跟踪 ack 集合）
```

### 4.3 follower 侧

```
Follower.followLeader（Follower.java:71）：连 leader → 注册 → 收 DIFF/SNAP → 收 PROPOSAL/COMMIT
FollowerZooKeeperServer.commit（:99）/ sync（:115）：按 leader 指令提交/同步
LearnerHandler.run（LearnerHandler.java:463）：leader 给每个 follower 的同步线程
  DIFF/TRUNC/SNAP 决策（:424-431 类型处理）
```

### 4.4 zxid 工具

```
ZxidUtils.makeZxid（ZxidUtils.java:29）：epoch<<32 | counter
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **zxid 打包 epoch+counter** | 分开维护 term 和 index | 一个数表达"时代+序"——比较/传输/存储都省（对比 Raft (term,index) 二元组） |
| **FLE 直接比较数据新旧** | 只比 epoch 大小 | 新 leader 必须含最全已提交日志——把"选举限制"做进投票逻辑，恢复阶段更简单 |
| **崩溃恢复三阶段（含 Discovery）** | 选完直接同步 | 防旧 leader 残留：用多数派 Python 收集的 epoch 提议确定新 epoch 上限 |
| **DIFF/TRUNC/SNAP 三档同步** | 一律全量 | 按落后程度选择——增量优先（快），多余事务截断（一致），太落后才全量 |
| **写广播无 abort（简化 2PC）** | 完整 2PC（可中断） | ZK 写语义"要么最终提交要么客户端重试"——无需 abort 路径，实现更简 |
| **写必须过 leader** | follower 也可接写 | 全局顺序唯一来源——leader 的 zxid 分配即全序 |
| **follower 可本地读（可能旧）** | 所有读都过 leader | 读扩展性（可加 follower/observer）；一致性要求高的场景用 sync |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **选举风暴** | tickTime/initLimit 与网络时延不匹配 → 反复选举——按 RTT 调大超时 |
| 2 | **Session 超时 → 临时节点全删** | 网络闪断——sessionTimeout 合理设置 + 业务续约 |
| 3 | **TRUNC 截断"丢数据"误解** | 被截断的是**未提交**事务——不违反一致性（客户端未收到成功应答） |
| 4 | **follower 读到旧数据** | 本地读语义——强一致读先 sync；或读走 leader |
| 5 | **observer 误当投票节点** | Observer 不参与投票（只同步）——容量扩展用 |
| 6 | **epoch 切换期不可用** | 恢复阶段拒绝写——选举超时窗口是配置与可用性的权衡 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："ZAB 和 Raft 的核心差异？"**（意图：对比理解）
① 选举：ZAB 直接比较 (epoch,zxid) 选"数据最新"；Raft 用随机超时+选举限制间接保证。② 设计形态：ZAB 显式分"崩溃恢复/消息广播"两模式；Raft 单一状态机持续运行。③ 日志标识：zxid 单 64 位 vs (term,index) 二元组。

**Q2："zxid 怎么组成？"**（意图：基础）
高 32 位 epoch（leader 时代）+ 低 32 位 counter（事务序号）——比较即知新旧。

**Q3："崩溃恢复为什么三阶段？"**（意图：机制细节）
Election 选主 → Discovery 定新 epoch（收集多数派提议，防旧 leader 残留）→ Synchronization 对齐日志（DIFF/TRUNC/SNAP）。

**Q4："TRUNC 会不会丢数据？"**（意图：一致性边界）
只截断"未被多数派提交"的事务——客户端没收到成功应答，语义上不算已提交数据。

**Q5："为什么说 ZK 写是 2PC 的简化？"**（意图：协议本质）
Proposal→Ack→Commit 两阶段；但无 abort（无回滚路径）——不满足多数派就永不 commit，不是"失败回滚"。

---

## 八、章节依赖

- **上游**：D-01（Etcd Raft，对照基线）
- **下游**：D-02（ZNode 会话/Watch——恢复后基于数据树的机制）；D-03（典型应用——锁/选主用 zxid 语义）；P03-对比 D-01
- **横链**：K04（Nacos 不用 ZK 的取舍）；P02（Kafka 旧版依赖 ZK → KRaft）

---

## 九、验证锚点（ZooKeeper 3.9.5）

| 声明 | 位置 |
|---|---|
| ServerState（LOOKING 等） | `quorum/QuorumPeer.java:526`（枚举）/`:844/:1469`（LOOKING 分支）/`:669`（setCurrentVote） |
| FLE 主流程 | `quorum/FastLeaderElection.java:913`（lookForLeader） |
| 投票广播 | `FastLeaderElection.java:691`（sendNotifications） |
| 三级比较 | `FastLeaderElection.java:723`（totalOrderPredicate）→ `:817`（updateProposal） |
| 多数派判定 | `FastLeaderElection.java:1047/1154/1172`（hasAllQuorums/checkLeader） |
| Leader 主循环/提案 | `quorum/Leader.java:632`（lead）/`:1288`（propose）/`:482`（outstandingProposals） |
| Ack→Commit | `Leader.java:1047`（processAck）/`:1212`（commit）/`:1117`（ToBeAppliedRequestProcessor）/`:89`（Proposal） |
| follower 同步 | `Follower.java:71`（followLeader）；`FollowerZooKeeperServer.java:99/115`（commit/sync） |
| Learner 同步类型 | `LearnerHandler.java:463`（run）/:424-431（DIFF/TRUNC/SNAP） |
| zxid 编码 | `server/util/ZxidUtils.java:29`（makeZxid） |
