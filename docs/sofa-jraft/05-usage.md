---
title: "05-使用：起集群与读一致"
series: "SOFAJRaft 共识系列"
previous: "04-fsm-snapshot.md"
next: "06-three-consensus.md（待写）"
source_refs:
  - "已核：jraft-example（counter: CounterServer/CounterClient/CounterStateMachine；election: ElectionNode/LeaderStateListener）"
  - "读路径已核：NodeImpl.readIndex:1494,1499→leader RPC:1608→ReadOnlySafe 多数派心跳确认:1654-1670 / LeaseBased 本地判定:1674 / 租约失效自动降级 Safe:1648-1650 / filterPeersForReadIndex(maxReadIndexLag) / ReadOnlyOption:26"
---

# 05-使用：起集群与读一致

## §0 它干了什么（例子先行）

两个官方例子（jraft-example 已核目录）：

```java
// A. election —— 最小用法：只要"谁是主"（ElectionBootstrap 实测流程）
ElectionNodeOptions opts = new ElectionNodeOptions();
opts.setInitialServerAddressList(initialConfStr);        // 集群成员配这里 :46
ElectionNode node = new ElectionNode();                  // 无参构造 :48
node.addLeaderStateListener(new LeaderStateListener() {  // :134
    public void onLeaderStart(long leaderTerm) { /* node.getNode().getLeaderId() */ }
    public void onLeaderStop(long leaderTerm)  { ... }
});
node.init(opts);                                         // Lifecycle 起步

// B. counter —— 完整用法：状态机承载业务
CounterServer server = new CounterServer(...);  // 内含 CounterStateMachine（onApply:85 真正的业务）
// CounterClient 主入口走 CLI：incrementAndGet(cliClientService, leader, ...):72
// 服务接口只有两个：get(readOnlySafe, closure) / incrementAndGet(delta, closure)
//   ⚠️ get 的 readOnlySafe 参数正是 §1 读三模式的开关——例子自带读一致性选型
```

- **它干了干什么**：**三行起一个 Raft 节点**——业务只写两样：`StateMachine`（apply/快照）和 `readIndex`（线性读）；选举/复制/提交全被 NodeImpl 吃掉。
- **本篇回答**：集群怎么起？**读为什么还要讲究**——三种读的差别与配置？

## §1 概念铺垫（What：读一致三模式）

**问题**：写走 Leader 没问题——**读 Follower 可能是旧的**（复制有延迟）。三种解法：

| 模式 | 怎么读 | 一致性 | 代价 |
|---|---|---|---|
| 本地读 | Follower 直接读本地状态 | **可能旧**（stale） | 零 |
| **LeaseBased（租约读）** | Leader 看"我与 Follower 的租约还在"→ 直接读本地 | 线性（租约期内没人能换届） | 一次本地判定（:1674） |
| **ReadOnlySafe（ReadIndex）** | Leader 向 **Follower 群发心跳、多数派回**"你还是主"→ 读 | 线性（多数派确认在任） | **一轮心跳 RPC**（:1654-1670） |

**关键机制**（源码实锤）：

```
readIndex:1494 → 发给 leader:1608 → leader 按模式分派：
  LeaseBased：本地 lease 判定：1674 直接回 OK
  ⚠️ 但租约失效 → 自动降级 ReadOnlySafe:1648-1650（"lease timeout 就换安全路"）
  Safe：quorum 心跳确认（等多数派回执才回读结果）
加分项：filterPeersForReadIndex 按 maxReadIndexLag 跳过落后太多的 Follower
       （不给"还没追上的人"发确认心跳——省无谓 RPC）
```

**为什么租约读敢省心跳**：租约期内（`checkLeaderLease:1856`，02 篇同款）**不可能产生新 Leader**——旧主知道自己还是主，无需再问多数派。**时间换 RPC**。

## §2 工程使用（参数与选型）

| 配置/API | 说明 |
|---|---|
| `readOnlyOption` | Safe（默认，稳）/ LeaseBased（快，靠时钟不漂） |
| `snapshotIntervalSecs` / `snapshotLogIndexMargin` | 快照触发（04 篇） |
| `readIndex(ctx, closure)` | **线性读入口**——业务读别直接读 StateMachine 字段 |
| election 例子 | 只需"选主感知"（如选主选举组件）——**不必用 Raft 管数据** |

**选型口诀**：写必须走 Leader；读默认 Safe；**同机房/时钟可信才上 LeaseBased**——配置中心/元数据类组件的常见优化（思路同构，具体产品是否开启看其配置）。

## §3 掌握卡（2 条）

1. **用法两件**：StateMachine（apply+快照）+ readIndex（线性读）——其余被框架吃掉；election 例子说明"只选主不存数"也是合法用法。
2. **读三模式**：本地读=可能旧；LeaseBased=时间换 RPC；ReadOnlySafe=多数派心跳确认——**租约失效自动降级 Safe:1648，稳字兜底**。

## §4 边界（一句话翻篇）

- CounterClient 的事务式流式读写细节 → 读 example 源码。
- rheakv 用法 → README 已列"知道即可"。
- 时钟/NTP 对租约读的影响 → 运维侧（面试提一句即可）。

## §5 Self-Test（3 题）

- Q1：三种读各自的代价与一致性？
- Q2：为什么 LeaseBased 敢不发心跳？失效了怎么办？（降级 :1648）
- Q3：maxReadIndexLag 过滤解决什么？（跳过落后者省 RPC）

## §6 衔接

- 使用线收口；下篇 06 深水：Raft vs ZAB vs Nacos JRaft vs KRaft 对照 + xhs 落点题。
