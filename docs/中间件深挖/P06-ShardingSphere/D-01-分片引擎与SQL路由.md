# P06-ShardingSphere D-01 — 分片引擎与 SQL 路由

> **定位**：P06 分库线第 1 篇。三层齐：**原理 + 使用 + 源码（5.5.2）**。
> **源码版本**：shardingsphere **5.5.2**（`code/spring/shardingsphere`；xhs 用 5.5.1，内核同代）
> **质量标准**：源码清单 / 完整调用链 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：两条 SQL 的不同命运

```sql
-- SQL-A：带分片键 → 只打 1 个分片
SELECT * FROM t_order WHERE user_id = 10001 AND status = 1;
-- SQL-B：无分片键 → 打全部 16 个分片，再归并
SELECT * FROM t_order WHERE status = 1 ORDER BY id DESC LIMIT 0, 10;
```

```
SQL-A：解析 → user_id=10001 命中分片条件 → 路由 ds1.t_order_0 → 改写表名 → 执行 → 直通返回
SQL-B：解析 → 无分片条件 → 全路由（16 个 ds×table）→ 改写（表名 + LIMIT 放大为 0,10→0,10? 见下）
     → 16 路并行执行 → 归并（ORDER BY 内存/流式合并 → 截断取前 10）→ 返回
```

**一句话**：分片引擎 = **"有键走单路（standard），无键走全路（broadcast/cartesian），改写物理名，归并拼结果"**——路由质量由"SQL 里有没有分片键"决定。

---

## 二、原理

### 2.1 五步内核（一次 SQL 的完整链路）

```
解析（Parser）：SQL 文本 → AST（SQLStatement：表/列/条件/分页）
绑定（Binder）：SQLStatementContext（提取分片条件 ShardingCondition、ORDER BY/GROUP BY 上下文）
路由（Router）：分片条件 → 数据节点集合（RouteContext：哪些 ds×table）
改写（Rewrite）：逻辑表名→物理表名；LIMIT/ORDER BY 补偿改写
执行（Executor）：多数据源并行（DriverExecutorFacade + 连接模式）
归并（Merger）：多路结果 → MergedResult（流式/内存/装饰器）
```

### 2.2 路由三类 + 笛卡尔积

| 类型 | 触发 | 行为 |
|---|---|---|
| **standard**（单分片） | WHERE 有分片键等值 | 精确到 1 个 ds×table |
| **cartesian**（笛卡尔） | 多表 JOIN 且**未绑定** | 各表路由集交叉（N×M），结果集爆炸 |
| **broadcast**（广播） | DDL / 全库表 / 无表路由 | 发往所有节点 |
| **unicast/ignore** | 特殊语句（SHOW/SET 等） | 单节点或跳过 |

**绑定表的价值**：同分片键+同算法的表 JOIN → 各自路由到**同一分片** → 单分片执行（K12：5 表绑定组）。

### 2.3 改写（Rewrite）的三件事

```
① 表名替换：t_order → t_order_0（按路由单元逐条生成物理 SQL）
② LIMIT 放大：LIMIT offset, n → 各分片 LIMIT 0, offset+n（因为全局第 offset 条可能在任一分片）
③ 补列：ORDER BY/GROUP BY 依赖的列若不在 SELECT 里，补上（归并要用，用完再裁掉）
```

### 2.4 归并（Merger）三族

| 类型 | 场景 | 方式 |
|---|---|---|
| **Transparent**（直通） | 单路由结果 | 取唯一结果直接返回（零成本） |
| **Stream**（流式） | ORDER BY（有序输入） | 多路归并排序（堆），内存 O(分片数) |
| **Memory**（内存） | GROUP BY/聚合/去重 | 全量加载内存再算 |
| **Decorator**（装饰） | 分页截断/列裁剪 | 在其他归并结果上包装（LIMIT 截断到 n 条） |

---

## 三、使用（生产口径）

| 实践 | 要点 |
|---|---|
| 分片键设计 | 查询 90% 带什么键就用什么分（K12：user_id）——路由质量=业务设计 |
| 绑定表 | JOIN 的表必须同分片键+同算法，否则笛卡尔积爆炸 |
| 笛卡尔积排查 | 慢查询先看路由单元数（1 个 vs N×M 个） |
| hint 强制路由 | 特殊场景指定分片（绕过条件推导） |
| 跨分片分页 | 禁跳页/游标（归并代价随 offset 线性涨） |
| 读写分离叠加 | 读走从库（与分片正交的两层路由） |

---

## 四、源码核心（调用链 + 行号）

### 4.1 路由链

```
入口：SQLRouteEngine.route（infra/route/.../engine/SQLRouteEngine.java:89）
  → ShardingSQLRouter.createRouteContext（features/sharding/.../route/engine/ShardingSQLRouter.java:51 → :63）
       → 按语句类型分发：standard / cartesian / unicast / broadcast / ignore
standard：ShardingStandardRouteEngine（.../type/standard/ShardingStandardRouteEngine.java）
  route（:84）→ getDataNodes（:95）
  → routeByShardingConditions（:133）：条件为空 → 全路由（:135-137）；有条件 → WithCondition（:140）
cartesian：ShardingCartesianRouteEngine.java:51（多表路由集交叉）
unicast：ShardingUnicastRouteEngine.java:59；broadcast：ShardingDatabaseBroadcastRouteEngine.java:34
```

### 4.2 改写与执行

```
RouteSQLRewriteEngine.rewrite（infra/rewrite/.../engine/RouteSQLRewriteEngine.java:71）
  → RouteSQLRewriteResult（每路由单元一条物理 SQL：表名替换 + LIMIT 补偿 + 补列）
执行：JDBC DriverExecutorFacade（jdbc/.../driver/executor/engine/facade/DriverExecutorFacade.java）
  + StatementExecuteCallback（多数据源并行 + 连接模式）
```

### 4.3 归并

```
TransparentResultMerger.merge（infra/merge/.../merger/impl/TransparentResultMerger.java:36-37）
  → new TransparentMergedResult(queryResults.get(0))（单结果直通）
结果族：StreamMergedResult（流式归并）/ MemoryMergedResult（内存聚合）/ DecoratorMergedResult（分页截断）
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **解析/绑定/路由/改写/执行/归并分层** | 一体化查改写 | 每层独立可测、可插拔（hint 改路由、自定义算法改分片）——"查询编译器"架构 |
| **无分片键就全路由（不报错）** | 强制要求分片键 | 可用性优先（查得出，慢）；把"路由质量"交给业务设计而非框架拒绝 |
| **LIMIT 改写放大** | 各分片取 n 条 | 全局第 offset 条可能在任一分片——正确性要求"宁可多取" |
| **归并分三族** | 全内存归并 | 按场景选成本：单结果零成本、ORDER BY 流式、GROUP BY 才内存 |
| **绑定表显式声明** | 自动推导可绑定 | 推导易错（算法等价难判定）——显式声明=契约，错配时报错而非错结果 |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **笛卡尔积爆炸** | 多表 JOIN 未绑定——绑定表组 + 慢查询看路由单元数 |
| 2 | **无分片键全路由** | WHERE 无分片键 → 16 分片全扫——加分片键条件/映射表 |
| 3 | **跨分片深分页** | LIMIT 放大 × 分片数——禁跳页/游标 |
| 4 | **ORDER BY 大结果集内存** | 归并走内存——流式只适合有序输入；大聚合走离线 |
| 5 | **改写后 SQL 与预期不符** | 补列/表名替换——开 SQL 日志核对物理 SQL（开发环境） |
| 6 | **hint 滥用** | 绕过路由推导——只用于运维/迁移等特殊场景 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："一条 SQL 在分片中间件里走了哪些步骤？"**（意图：内核流程）
解析→绑定→路由→改写→执行→归并——六步，每步可插拔。

**Q2："什么情况下会全路由？"**（意图：路由质量）
无分片键条件（或条件推导不出）→ 全 ds×table；DDL/全库表广播。

**Q3："绑定表解决什么？"**（意图：JOIN 优化）
同分片键+同算法的表 JOIN 落在同一物理分片——避免 N×M 笛卡尔积。

**Q4："跨分片分页为什么慢？"**（意图：改写代价）
LIMIT 要放大（0, offset+n）× 分片数 + 归并截断——offset 越大越慢。

**Q5："单分片查询和全路由查询在归并上有何不同？"**（意图：归并成本）
单路由走 Transparent 直通（零成本）；多路才需要 Stream/Memory/Decorator。

---

## 八、章节依赖

- **上游**：K12（使用层：4×4/绑定表/映射表/INLINE 口径）
- **下游**：D-02（JDBC vs Proxy 架构对比：本篇是 JDBC 驱动模式内核）；D-03（陷阱收束）
- **横链**：P02 对比（存储模型：分片路由 vs Kafka 分区）；P04（查询优化器对照）

---

## 九、验证锚点（ShardingSphere 5.5.2）

| 声明 | 位置 |
|---|---|
| 路由入口 | `infra/route/.../engine/SQLRouteEngine.java:63/89` |
| 分片路由器 | `features/sharding/.../route/engine/ShardingSQLRouter.java:51/63` |
| standard 路由 | `.../type/standard/ShardingStandardRouteEngine.java:84/95/133/137/140` |
| 笛卡尔/单播/广播 | `.../type/complex/ShardingCartesianRouteEngine.java:51`；`.../type/unicast/ShardingUnicastRouteEngine.java:59`；`.../type/broadcast/ShardingDatabaseBroadcastRouteEngine.java:34` |
| 改写 | `infra/rewrite/.../engine/RouteSQLRewriteEngine.java:71` |
| 执行 | `jdbc/.../driver/executor/engine/facade/DriverExecutorFacade.java`（门面） |
| 归并直通 | `infra/merge/.../merger/impl/TransparentResultMerger.java:36-37` |
| 结果族 | `infra/merge/.../result/impl/{stream,memory,decorator,transparent}/` |
