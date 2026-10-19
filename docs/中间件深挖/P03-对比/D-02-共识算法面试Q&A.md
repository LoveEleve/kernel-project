# P03-对比 D-02 — 共识算法面试 Q&A（收官）

> **定位**：P03 系列收束篇：**5 道高频题 + 面试官意图分析 + 追问链**。
> **用法**：先自测（盖住答案），答不出的回对应篇；每题都能回指源码锚点。
> **基线**：etcd v3.5.34 / ZooKeeper 3.9.5 / Curator 5.8.0

---

## 一、速记卡（一页纸）

1. **Raft = 单状态机持续运行**（选举/复制统一在角色里）；**ZAB = 两模式**（崩溃恢复 + 消息广播）。
2. **选举哲学**：Raft 随机超时+选举限制（投票时查日志）；ZAB FLE 直接比 (epoch→zxid→sid)（数据最新者胜）。
3. **事务标识**：Raft (term, index)；ZAB zxid=epoch<<32|counter。
4. **提交**：都是"多数派确认才 commit"；Raft 只能提交当前任期 entry；ZAB 无 abort（简化 2PC）。
5. **读**：etcd ReadIndex/LeaseRead 支持线性读；ZK 读可能旧（sync 强同步）。
6. **committed ≠ applied**：多数派持久化 vs 状态机执行（中间窗口=崩溃恢复重放区）。
7. **恢复**：etcd 快照+WAL 回放 + follower 三态追赶；ZK 三阶段（Election/Discovery/Synchronization）。
8. **生态**：Raft 成标准（KRaft/DLedger/JRaft）；ZAB 仅 ZK；大规模注册/配置被 Nacos 类替代。
9. **工程纪律**：共识不自研（etcd raft / sofa-jraft 直接用）；节点数=容错×2+1。
10. **正确性边界**：共识保证"复制日志一致"，不保证"业务正确"（幂等/对账/fencing 仍要做）。

---

## 二、5 道题（意图 + 主线 + 追问链）

### Q1 "Raft 和 Paxos 什么区别？"（意图：是否理解设计目标，不是背定义）

**主线**：能力等价（Multi-Paxos ≈ Raft），差异在**可理解性与工程化**：
```
Raft 用三块拆解共识：① Strong Leader（日志单向流）② 日志匹配（前缀一致性质）
                    ③ 成员变更（Joint Consensus 两步）
Paxos 更泛化（任意值达成一致）但描述抽象，工程落地（活锁处理/日志化/成员变更）反而难
```
**追问链**：那为什么 KRaft/DLedger 都选 Raft？→ 有成熟库（etcd raft/sofa-jraft）+ 论文把工程细节写全。Paxos 的坑（乱序/空洞）谁来解决？→ 还是得引入 Leader 和日志——绕回 Raft。
**危险信号**：只会背"Raft 更容易理解"这种一句话；说不清 Multi-Paxos 和 Raft 的关系。

### Q2 "ZAB 和 Raft 的核心差异？"（意图：对比思维，是否只知道一个）

**主线**（三层）：
```
① 选举：ZAB FLE 直接比较 (epoch,zxid,sid) 选数据最新；Raft 随机超时+投票者的选举限制
② 形态：ZAB 显式两模式（崩溃恢复→消息广播）；Raft 单状态机（角色转换即切换）
③ 标识：zxid 单 64 位（epoch+counter）vs (term,index) 二元组
```
**追问链**：选举完为什么 ZAB 还要 Discovery？→ 定新 epoch 上限，防旧 leader 残留（Raft 靠 term 仲裁天然覆盖）。TRUNC 会不会丢数据？→ 只截断未提交事务。
**危险信号**："ZAB 就是 Raft 换皮"；说不出 epoch/counter。

### Q3 "etcd 怎么实现线性一致读？"（意图：读路径深水区）

**主线**：
```
ReadIndex：收到读请求 → 与多数派心跳确认"我仍是 leader" → 等到 commitIndex ≥ readIndex
          → 读本地状态机（不落日志，比走日志便宜）
LeaseRead：租约期内直接读（少一轮心跳，依赖时钟假设）
对比 ZK：默认读走任意节点可能旧；要强读 sync() 强制同步——两协议对读的定位不同
```
**追问链**：为什么直接读 leader 不行？→ 网络分区时旧 leader 可能已不是 leader（脑裂窗口）——ReadIndex 的确认就是防这个。
**危险信号**：以为"读 leader 就是线性一致"；不知道 committed/applied 对读可见性的影响。

### Q4 "committed 和 applied 什么区别？崩溃恢复为什么不丢？"（意图：状态机+恢复链路）

**主线**：
```
committed：多数派已持久化（WAL/txnlog）——不会丢
applied  ：状态机已执行（对客户端可见）——可能滞后（etcd apply 异步 FIFO）
崩溃恢复：快照（定格某 index）+ WAL/txnlog 回放（快照之后的增量）→ 重建到最新 committed
etcd 链路：Ready → storage.Save（WAL）→ applyc → FIFO apply（D-02）
ZK 链路：txnlog + snapshot（FileTxnSnapLog 同类配合）
```
**追问链**：apply 慢会怎样？→ committed 涨 applied 不涨（积压）——但写要等 apply 后才应答（notifyc）。为什么先 WAL 再 apply？→ 已应答=已持久化，apply 只是执行已确定的事实。
**危险信号**：把 committed 当"已执行"；不知道恢复要快照+日志配合。

### Q5 "为什么 Kafka 弃 ZK 用 KRaft？共识能解决一切吗？"（意图：选型判断与边界）

**主线**：
```
弃 ZK：元数据写全过 ZK leader（瓶颈）+ watch 一次性 + 双栈运维——KRaft 把元数据日志化内嵌
共识的边界：共识只保证"复制日志的顺序与一致"——业务正确还要：
  幂等（重复请求）、对账（最终一致）、fencing（旧主隔离）、容量规划
类比：Nacos 持久实例用 JRaft（CP），但服务发现主路径走 Distro（AP）——按场景分轨
```
**追问链**：注册中心为什么多数选 AP？→ 分区时"不能注册"比"短暂不一致"更痛（K04）；ZK 的 CP 写受限正是它不适合做大规模注册的原因。
**危险信号**：认为上了共识就"绝对正确"；说不出 CP/AP 的场景取舍。

---

## 三、学习检查清单（来自 P03 规划）

- [ ] 能手画 Raft 选举完整流程（campaign → RequestVote → 多数票 → 心跳确立）
- [ ] 能解释 Raft 的 committed vs applied 与崩溃恢复链路（快照+日志）
- [ ] 能手画 ZAB 崩溃恢复三阶段（Election → Discovery → Synchronization）
- [ ] 能对比 zxid 与 (term,index) 的语义差异
- [ ] 能说出 Raft 线性读的两种实现（ReadIndex/LeaseRead）
- [ ] 能回答"为什么 Kafka 弃 ZK"与"共识的边界"

---

## 四、回链索引

| 主题 | 文档 |
|---|---|
| Raft 选举/复制/ReadIndex | P03-Etcd D-01 |
| Ready 契约/两层循环/apply | P03-Etcd D-02 |
| MVCC/Watch/BoltDB | P03-Etcd D-03 |
| 快照/日志压缩/恢复 | P03-Etcd D-04 |
| ZAB/FLE/三阶段恢复 | P03-ZooKeeper D-01 |
| 会话分桶/Watch | P03-ZooKeeper D-02 |
| 锁/选主/配置（Curator） | P03-ZooKeeper D-03 |
| 6 维对比 | P03-对比 D-01 |
| 应用面（KRaft/DLedger/JRaft） | P02-Kafka D-02 / P02-RocketMQ D-04 / K04 |
