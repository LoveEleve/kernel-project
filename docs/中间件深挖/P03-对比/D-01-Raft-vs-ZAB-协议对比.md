# P03-对比 D-01 — Raft（Etcd）vs ZAB（ZooKeeper）协议对比

> **定位**：P03 对比系列第 1 篇（6 维度矩阵 + 适用场景）。双轨基线：**etcd v3.5.34** / **ZooKeeper 3.9.5**（+Curator 5.8.0）。
> **读法**：先看结论矩阵 → 再看每维展开（回链两侧深挖篇）→ 最后看生态选择与决策表。

---

## 一、一句话结论

| | Raft（Etcd） | ZAB（ZooKeeper） |
|---|---|---|
| **协议形态** | **单一状态机持续运行**（选举/复制统一在角色转换里） | **两模式切换**：崩溃恢复（选主+同步）+ 消息广播（Proposal→Ack→Commit） |
| **事务标识** | (term, index) 二元组 | **zxid 单 64 位**（epoch 高 32 + counter 低 32） |
| **一句话** | "强 Leader + 日志匹配 + 多数派提交"——通用共识库 | "主备 + 简化 2PC 广播"——为"协调原语"优化的协议 |

---

## 二、六维对比矩阵（速查）

| # | 维度 | Raft（Etcd） | ZAB（ZooKeeper） |
|---|---|---|---|
| 1 | **选举** | 随机超时 + RequestVote；靠**选举限制**（投票者检查候选日志不旧）间接保证数据安全 | **FLE**：投票直接比较 (epoch→zxid→sid)，**"数据最新者胜"**做进协议 |
| 2 | **日志/事务标识** | (term, index)；Log Matching（prevLogTerm 校验） | zxid=(epoch,counter)；TRUNC 按 zxid 对齐 |
| 3 | **复制/广播** | AppendEntries（单向推，probe/replicate/snapshot 三态 Progress） | PROPOSAL→ACK→COMMIT（简化 2PC，FIFO，无 abort） |
| 4 | **恢复/同步** | 快照+WAL 增量；落后 follower 走 StateSnapshot 发快照 | **崩溃恢复三阶段**：Election→Discovery（定 epoch）→Synchronization（DIFF/TRUNC/SNAP） |
| 5 | **读一致性** | **ReadIndex/LeaseRead** 支持线性读（不落日志） | 读可走 follower（可能旧）；强读用 **sync()** |
| 6 | **扩展与生态** | Go 库+K8s 生态（etcd）；Raft 成行业标准（KRaft/DLedger/JRaft） | 仅 ZK 用；客户端生态 Curator；watch 一次性 |

---

## 三、每维展开

### 3.1 选举（最大的哲学差异）

```
Raft：随机超时化解平票；投票者做"日志新旧"检查（选举限制）——安全在"投票环节"
ZAB ：FLE 广播投票并直接比较 (epoch, zxid, sid)——安全在"投票内容"（选数据最新的）
后果差异：ZAB 选举完还要走 Discovery/Synchronization 把"疑似最全"落成"确实一致"；
        Raft 选完直接进入正常复制（选举限制已保证）
锚点：etcd `raft/raft.go:779`（campaign）/:689（becomeCandidate）——ZK `FastLeaderElection.java:913`（lookForLeader）/:723（totalOrderPredicate）
```

### 3.2 事务标识

```
zxid 单 64 位 vs (term,index)：
  · 比较更省（一个数 vs 二元组）；ZK 的 TRUNC/SNAP 判断直观（比 zxid）
  · Raft 的 term/index 分离：term 管领导权仲裁，index 管日志位置——语义更清晰
锚点：etcd `raft/tracker/progress.go:30`（Match/Next）；ZK `server/util/ZxidUtils.java:29`（makeZxid）
```

### 3.3 复制与提交

```
Raft：AppendEntries 双向校验（prevLogTerm 不符 → 回退重试）；commit 规则"只能提交当前任期 entry"
ZAB ：Proposal 队列 FIFO 推送；follower 按序 ACK；多数派 → COMMIT（无 abort）；
      写必须过 leader（全序来源）——同 Raft 的 Strong Leader
锚点：etcd `raft/raft.go:621`（appendEntry）/:585（maybeCommit）——ZK `Leader.java:1288`（propose）/:1047（processAck）/:1212（commit）
```

### 3.4 恢复与同步

```
Raft：崩溃恢复 = 加载快照 + WAL 回放 + follower 三态追赶（Probe/Replicate/Snapshot）
ZAB ：崩溃恢复是显式"阶段"——Discovery（收集 epoch 提议防旧主残留）+ Synchronization（三档）
锚点：etcd `raft/raft.go:1528`（restore）/`server/etcdserver/storage.go:87`（readWAL）
     ZK `LearnerHandler.java:424-431`（DIFF/TRUNC/SNAP）
```

### 3.5 读一致性

```
etcd：ReadIndex（心跳确认领导权后读，不落日志）/ LeaseRead（租约内直读）——线性读一等公民
ZK ：默认读走任意节点（可能旧）；sync() 强制与 leader 同步——**读一致性不是主打**
锚点：etcd `raft/raft.go:1072/1088`（MsgReadIndex/sendMsgReadIndexResponse）
     ZK `FollowerZooKeeperServer.java:115`（sync）
```

### 3.6 扩展与生态

```
Raft 已成为"共识库标准"：Kafka KRaft / RocketMQ DLedger / Nacos JRaft（sofa-jraft）/ TiKV / Consul
ZAB 仅 ZK 实现：客户端由 Curator 补生态（自动重注册/状态机）
ZK 的 watch 一次性 + CP 写受限 → 大规模服务发现/配置场景被 Nacos 类系统替代（K04）
```

---

## 四、为什么生态选择它们（决策视角）

| 系统 | 共识 | 为什么 |
|---|---|---|
| Kafka KRaft | Raft | 元数据日志化内嵌（去掉 ZK 外部依赖）；Raft 库成熟可嵌入 |
| RocketMQ DLedger | Raft | 存储层主从自动切换（CommitLog + Raft） |
| Nacos JRaft | Raft（sofa-jraft） | 持久实例 CP 需要复制日志；Java 生态（sofa-jraft 即 sofa 对 jraft 的实现，Nacos 直接采用） |
| ZooKeeper | ZAB | 自研协议服务协调原语（锁/选主）——为"顺序+活性"设计 |
| **Kafka 弃 ZK** | — | ZK 管元数据的代价：写全过 ZK leader（瓶颈）、watch 一次性、运维双栈——KRaft 内嵌 |

---

## 五、适用场景决策表

| 需求 | 选型建议 |
|---|---|
| K8s 生态/KV+watch/配置 | **etcd**（线性读、lease、gRPC 生态） |
| 分布式锁/选主（强一致协调原语） | ZK（Curator 全家桶）；但真正确性要 fencing/下游兜底（对比 K02） |
| 大规模服务发现/配置（AP 优先） | 都不选——Nacos 类（连接模型+推送，K04） |
| 嵌入式共识（自研系统加复制） | Raft 库（etcd raft / sofa-jraft）——别自研共识 |
| 元数据主备自动切换 | Raft（DLedger/JRaft）——比手工主从强 |

---

## 六、陷阱与误区

| # | 误区 | 正解 |
|---|---|---|
| 1 | "ZAB 就是 Raft 换皮" | 选举哲学（数据最新 vs 选举限制）与协议形态（两模式 vs 单状态机）不同 |
| 2 | "etcd 读一定线性" | 默认读可能走 follower/过期数据——线性读要 ReadIndex/LeaseRead 加持 |
| 3 | "ZK watch 能保证不漏" | 一次性 + 重注册窗口——事件只当"拉取提示" |
| 4 | "共识算法能随便自研" | 两大协议都是血泪打磨——工程上用成熟库（etcd raft/sofa-jraft） |
| 5 | "节点越多越好" | 多数派成本随节点涨（3/5 最优区间）——按容错数×2+1 |
| 6 | "zxid 和 Raft index 等价" | zxid 含 epoch（领导权信息），Raft index 不含——比较语义不同 |

---

## 七、验证锚点（两侧汇总）

**Etcd v3.5.34**：选举 `raft/raft.go:779/689`；复制 `:621/585`；Progress `raft/tracker/progress.go:30`；恢复 `:1528` + `server/etcdserver/storage.go:87`；ReadIndex `:1072/1088`
**ZooKeeper 3.9.5**：FLE `quorum/FastLeaderElection.java:913/723`；广播 `quorum/Leader.java:1288/1047/1212`；同步 `LearnerHandler.java:424-431`；sync `FollowerZooKeeperServer.java:115`；zxid `server/util/ZxidUtils.java:29`

> 每维深入见：P03-Etcd D-01~D-04 / P03-ZooKeeper D-01~D-03。
