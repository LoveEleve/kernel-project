# P06-ShardingSphere D-02 — JDBC 与 Proxy 模式架构对比

> **定位**：P06 分库线第 2 篇。三层齐：**原理 + 使用/选型 + 源码（5.5.2）**。
> **源码版本**：shardingsphere **5.5.2**
> **质量标准**：源码清单 / 完整调用链 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：同一条查询的两种部署

```sql
SELECT * FROM t_order WHERE user_id = 10001;
```

```
【JDBC 模式】应用进程内：
  业务代码 → ShardingSphereDriver（jdbc:shardingsphere: URL）→ 内核（路由/改写/归并）
  → 应用自己的 Hikari 连接池 → ds1.t_order_0
  （无额外进程、无网络跳数；升级=升级应用依赖）

【Proxy 模式】独立进程：
  任意 MySQL 客户端 → Proxy:3307（MySQL 线协议）→ 内核（同一套路由/改写/归并）
  → Proxy 自己的连接池 → ds1.t_order_0
  （多语言透明；DistSQL 在线改规则；升级 Proxy 不动应用）
```

**一句话**：**同一套内核（infra），两种接入形态**——JDBC 是"库"（嵌入 JVM），Proxy 是"服务"（独立进程说 MySQL 方言）。

---

## 二、架构对比

### 2.1 接入层差异

| 维度 | JDBC 模式 | Proxy 模式 |
|---|---|---|
| 形态 | jar 嵌入应用 JVM | 独立 Java 进程（Bootstrap 启动） |
| 客户端 | Java（DriverManager/DataSource） | 任意 MySQL/PG/openGauss 客户端 |
| 协议 | JDBC API | 数据库线协议（frontend 引擎：MySQL/PG/openGauss 三实现） |
| 连接池 | 应用的池（Hikari，xhs 每 ds 10/2） | Proxy 自持池 |
| 事务 | 应用 Spring 事务（同一连接） | Proxy 内事务（BackendConnection 会话状态） |

### 2.2 共享内核（相同的部分）

```
解析（parser）→ 绑定（binder）→ 路由（route）→ 改写（rewrite）
→ 执行（executor）→ 归并（merge）
两模式只是"前后两端"不同：JDBC 端 = Driver/DriverState；Proxy 端 = frontend/backend
```

### 2.3 Proxy 独有能力

```
DistSQL（distsql/ral/rul）：在线查规则/改配置/预览执行计划（PreviewExecutor）——不用改应用重启
多协议 frontend：MySQL / PostgreSQL / openGauss（DatabaseProtocolFrontendEngine 抽象）
独立运维：扩缩/升级/重启不影响应用进程（代价：多一跳网络 + 独立组件）
```

---

## 三、使用/选型（生产口径）

| 场景 | 选型 |
|---|---|
| Java 应用 + 性能敏感 + Spring 事务 | **JDBC**（xhs 选它：无跳数、事务集成好；代价：分片逻辑与应用耦合） |
| 多语言客户端 / 遗留系统不动代码 | **Proxy**（MySQL 方言透明接入） |
| DBA 统一治理 / 在线改规则 | **Proxy**（DistSQL） |
| 各自升级节奏 | Proxy 独立演进；JDBC 跟应用发版 |

---

## 四、源码核心（调用链 + 行号）

### 4.1 JDBC 模式

```
ShardingSphereDriver（jdbc/.../driver/ShardingSphereDriver.java）
  implements Driver（:36）；DRIVER_URL_PREFIX="jdbc:shardingsphere:"（:38）
  connect（:56-57）：acceptsURL → dataSourceCache.get → getConnection
DriverState（driver/state/：OKDriverState / CircuitBreakDriverState——熔断态直通）
执行：DriverExecutorFacade（jdbc/.../driver/executor/engine/facade/DriverExecutorFacade.java）
```

### 4.2 Proxy 模式

```
启动：proxy/bootstrap/.../proxy/Bootstrap.java
前端：DatabaseProtocolFrontendEngine（proxy/frontend/spi/.../DatabaseProtocolFrontendEngine.java）
  + MySQL/PostgreSQL/openGauss 三实现（proxy/frontend/type/{mysql,postgresql,opengauss}/）
后端执行：ProxySQLExecutor（proxy/backend/core/.../connector/ProxySQLExecutor.java）
在线管理：proxy/backend/core/.../handler/distsql/{ral,rul}/（PreviewExecutor 等）
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **内核与接入分离** | JDBC/Proxy 各写一套 | 路由/改写/归并只实现一次——接入形态只是"前后端插件" |
| **Proxy 说原生方言** | 自定义协议 | 零客户端改造成本（mysql 客户端直连）；代价是协议兼容工作量 |
| **JDBC 共享应用事务** | 独立事务管理 | Spring 事务无缝（同一连接）；Proxy 则要自己维护会话事务 |
| **DistSQL 只在 Proxy** | JDBC 也支持 | JDBC 在应用进程内——"在线改规则"语义弱；Proxy 是长驻服务，适合运维入口 |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **JDBC 升级要全量发版** | 分片逻辑在应用依赖里——版本管理 + 灰度 |
| 2 | **Proxy 多一跳延迟** | 网络往返——延迟敏感链路压测对比 |
| 3 | **Proxy 成单点** | 独立组件故障域——集群部署 Proxy |
| 4 | **协议兼容坑** | 客户端用偏门特性——以 MySQL 兼容矩阵为准测 |
| 5 | **两模式混用认知混乱** | 同一集群两种接入——统一治理口径（规则同源） |

---

## 七、面试 Q&A（含面试官意图）

**Q1："JDBC 和 Proxy 模式区别？"**（意图：架构理解）
同一内核两种接入：JDBC 嵌入 JVM（性能/事务好，耦合应用）；Proxy 独立进程说 MySQL 方言（多语言/在线治理，多一跳）。

**Q2："Proxy 怎么做到 MySQL 客户端透明？"**（意图：协议层）
frontend 按 MySQL/PG/openGauss 线协议解析（认证/命令），后端转 JDBC 执行——客户端以为在连 MySQL。

**Q3："DistSQL 是什么？"**（意图：运维能力）
Proxy 的在线管理语言：查规则/改配置/预览执行计划——JDBC 模式没有对等物。

**Q4："xhs 为什么选 JDBC？"**（意图：选型）
Java 栈 + 性能（无跳数）+ Spring 事务集成；代价是升级跟应用走（可接受）。

---

## 八、章节依赖

- **上游**：D-01（共享内核：路由/改写/归并）；K12（xhs 使用口径）
- **下游**：D-03（生产陷阱与 Q&A 收束）
- **横链**：K08（Dubbo 协议层对照：线协议兼容的思路相通）

---

## 九、验证锚点（ShardingSphere 5.5.2）

| 声明 | 位置 |
|---|---|
| JDBC Driver | `jdbc/.../driver/ShardingSphereDriver.java:36/38/56-57` |
| Driver 状态 | `jdbc/.../driver/state/{OKDriverState,CircuitBreakDriverState}.java` |
| JDBC 执行门面 | `jdbc/.../driver/executor/engine/facade/DriverExecutorFacade.java` |
| Proxy 启动 | `proxy/bootstrap/.../proxy/Bootstrap.java` |
| Proxy 前端抽象+三实现 | `proxy/frontend/spi/.../DatabaseProtocolFrontendEngine.java`；`proxy/frontend/type/{mysql,postgresql,opengauss}/` |
| Proxy 后端执行 | `proxy/backend/core/.../connector/ProxySQLExecutor.java` |
| DistSQL | `proxy/backend/core/.../handler/distsql/{ral,rul}/`（如 `PreviewExecutor.java`） |
