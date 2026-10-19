# P04-分布式存储 D-06 — OceanBase 架构深度分析

> **定位**：P04 第 6 篇（集大成：一本书一章的架构演进史）。
> **依据**：OceanBase 官方《架构演进史》（首席架构师杨传辉，`oceanbase.github.io`）+ 经典 0.x 组件模型（RootServer/UpdateServer/ChunkServer/MergeServer）。
> **质量标准**：架构演进线 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：三次架构跃迁一页纸

```
v0.5（2010）：计算层（无状态 SQL，可横向扩）+ 存储层（UpdateServer 单点写 + ChunkServer 多点读）
  → 撑起淘宝收藏夹；死穴：写单点 + 存算分离导致延迟不可控
v1.0-3.0：等价节点（每台 OBServer 都能处理事务+存数据+跑 SQL）+ Paxos 多副本
  → TPC-C 707M tpmC（1557 节点，20M txn/s，8 小时）——世界纪录
v4.0：动态日志流（log stream）——事务扩展与存储扩展解耦（多分片共享一条日志流）
  → 小应用不再为"分片数=日志流数"付资源税
```

**一句话**：OceanBase 十年 = **"单点写（够用但到头）→ 等价节点+Paxos（可扩展）→ 日志流解耦（降成本）"**——每次跃迁都由业务形态变化驱动。

---

## 二、原理

### 2.1 v0.5：读写分离的经典四件套

```
RootServer：元数据总管（选主/负载均衡/副本管理，单点+备）
UpdateServer：内存 MemTable 单点写（所有写先到内存，定期转储 SSTable）
ChunkServer：基线数据（只读 SSTable，只服务读）
MergeServer：SQL 入口（路由 + 读合并：基线 ChunkServer 数据 + UpdateServer 增量）
每日合并（daily merge）：基线 + 当天增量 → 新基线（离线批，夜间低峰跑）
```

### 2.2 为什么单点写撑不住了（v0.5→v1.0 的动因）

```
① 写并发天花板：单 UpdateServer 的内存/CPU 就是全集群写上限（收藏夹够用，交易不够）
② 延迟不可控：SQL 层与存储层分离，一次查询跨两次网络 + 两次执行（延迟抖动大）
③ 单点故障域：UpdateServer 挂 = 全集群不可写（尽管有备，但切换即停写窗口）
```

### 2.3 v1.0-3.0：等价节点 + Paxos（ textbook 分布式数据库长相）

```
每台 OBServer = SQL 引擎 + 事务引擎 + 存储引擎（LSM-Tree）——"小号的完整数据库"
分区（tablet）三副本跨 Zone，Paxos 选主；读写走主副本（强一致读）
横向：加 Zone 副本保可用；纵向：加 OBServer 扩吞吐（TPC-C 线性到 1557 节点）
```

### 2.4 v4.0：日志流解耦（降本革命）

```
旧问题：分区数 = 日志流数（每分片一条 Paxos 日志流）——小应用分片少但每条流有固定资源开销
新解：多分片共享一条日志流（事务/高可用按流扩展，存储按分片扩展）
效果：小租户资源占用大降（OceanBase 上云/下沉市场的关键）
```

---

## 三、怎么做 / 选型对照

```
读多 + 简单：v0.5 式读写分离够用（代价：写上限 + 延迟抖动 + 每日合并窗口）
交易级：等价节点 + Paxos（v1.0+）：强一致读写 + 水平扩展
小库多租户：日志流解耦（v4.0）：别为分片数交日志税
vs TiDB（计算存储分离 + Raft）：OB 是"一体化"（单进程多引擎函数调用，见 integrated-architecture 篇）
vs CockroachDB：同为分布式 SQL，但 OB 兼容 MySQL 生态 + LSM 存储
```

---

## 四、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么 |
|---|---|---|
| **v0.5 单点写** | 一开始就 Paxos | 90% 读 10% 写时代：单点写简化事务/并发/一致性检查（P04 规划原话） |
| **每日合并** | 实时合并 | 夜间低峰批处理摊薄成本；代价是"当天增量读要合并两份"（MergeServer 的活） |
| **v1.0 等价节点** | 读写分离加机器 | 交易并发 + 延迟可控 + 高可用——单点架构的三宗罪一次解决 |
| **Paxos 而非 Raft** | Raft 更易懂 | 历史路径（2010 年代初 Raft 未发表/未验证）；Multi-Paxos 领导者批租约优化吞吐 |
| **v4.0 日志流解耦** | 分片=日志流 | 小应用降本；大应用无感（流数照样随分片涨） |

---

## 五、面试 Q&A（含面试官意图）

**Q1："OceanBase 0.5 架构？为什么被换掉？"**（意图：演进思维）
计算无状态 + UpdateServer 单点写 + ChunkServer 读 + MergeServer 合并；换掉因为写单点/延迟抖动/故障域。

**Q2："UpdateServer 和 LSM 的 memtable 什么关系？"**（意图：存储引擎）
同构：内存增量 + 定期转储 + 每日合并 ≈ memtable + flush + major compaction（D-01/D-04 同哲学）。

**Q3："Paxos 在 OB 里管什么？"**（意图：共识落点）
分区多副本选主 + 日志复制（v1.0+）；对比etcd raft 是"KV 复制"，OB 是"事务日志复制"（含两阶段提交语义）。

**Q4："v4.0 日志流解决什么？"**（意图：成本视角）
分片数≠日志流数——小租户不为用不上的流付资源税；事务/HA 按流扩展、存储按分片扩展。

**Q5："和 TiDB 怎么选？"**（意图：选型）
OB 一体化（单进程多引擎，延迟低）+ MySQL 兼容；TiDB 存算分离（弹性好）+ 开源生态——工作负载与运维能力决定。

---

## 六、章节依赖

- **上游**：D-01（LSM：UpdateServer MemTable 同构）、D-02（分区复制：Paxos 多副本落点）、D-04（Bigtable：tablet/Chubby 对照）、D-05（Dynamo：去中心对照——OB 是强一致路线）
- **下游**：D-07 面试收束
- **横链**：P03（Paxos/Raft 共识）；K12（ShardingSphere 分片键设计对照：OB 是"数据库内部分片"）；K17（MySQL 单机对照）

---

## 七、验证锚点

| 声明 | 来源 |
|---|---|
| v0.5 架构/单点写死穴 | OceanBase 官方《架构演进史》（`oceanbase.github.io/docs/blogs/showcases/architectural-evolution`） |
| TPC-C 707M/1557 节点/20M txn/s | 同上（官方数据） |
| v4.0 日志流解耦 | 同上 |
| RootServer/UpdateServer/ChunkServer/MergeServer/每日合并 | 经典 0.x 架构（Ch8-9 对应内容；以官方演进史互证） |
| 一体化架构（单进程多引擎） | 官方《Integrated Architecture》篇 |
