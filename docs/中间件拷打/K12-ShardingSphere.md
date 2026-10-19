# K12 — 组件深度拷打：ShardingSphere-JDBC

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：解析-路由-改写-归并、INLINE 两级哈希、绑定表、Snowflake 低位偏差、映射表、影子表、扩容无 rehash
> 依据：xhs-project 配置/代码（sharding-config.yaml / ShardingSphereDataSourceConfig / 5.5.1）+ 49-ShardingSphere深度拷打 + 13-分库分表 + 源码语料 5.5.2

---

## 问题

分库分表怎么做的？分片键怎么选？跨分片查询怎么办？Snowflake 有什么坑？扩容怎么扩？

---

## 面试可讲版（五段式）

### ① 原理层（JDBC 内核五步）

```
SQL 解析 → 分片路由（算 ds/表）→ SQL 改写（逻辑表→物理表；分页/聚合改写）→ 执行（多数据源）→ 结果归并（内存排序/聚合/LIMIT）
```

| 概念 | 说明 |
|---|---|
| **分片策略** | standard（单键，本项目）/ complex（多键）/ hint（强制路由） |
| **算法** | INLINE 表达式 / ClassBased 自定义 |
| **绑定表** | 分片规则同构的表**绑定** → JOIN 不产生笛卡尔积（路由到单分片） |
| **广播表** | 每个库一份（小字典表；本项目未用） |
| **分布式主键** | Snowflake（时间戳+worker+序列）——**worker-id 必须全局唯一** |
| **事务** | LOCAL / XA——本项目用 **LOCAL + 本地消息表 + 事务消息**（不上 XA） |

### ② 项目用法（订单域 4×4 = 16 分片）

**拓扑与策略（`sharding-config.yaml`，运行态）**：
```
4 database（ds0~ds3 = my_xhs_order_0~3，同实例多库仿真，生产改 jdbcUrl 即水平扩展）
× 每库 4 表 = 16 物理分片/逻辑表
库路由：ds${(user_id.hashCode() & 0x7fffffff) % 4}
表路由：t_xxx_${((user_id.hashCode() & 0x7fffffff).intdiv(4)) % 4}
（修复后版本；为什么不用 user_id % 4——见 ③ 倾斜事故）
```

**五张逻辑表全部绑定**：`t_order / t_order_item / t_local_message / t_order_snapshot / t_order_event`——同一 user_id 的数据**同库同表后缀**：JOIN 不跨库、**单用户事务不跨库**（下单+预扣本地事务原子）。

**集成坑（Boot 3.2.5）**：`jdbc:shardingsphere:` 协议 Spring Boot 自动配置不识别 → `ShardingSphereDataSourceConfig` 用 `YamlShardingSphereDataSourceFactory` **手动建 DataSource** + 手动 MybatisSqlSessionFactory（排除 MP 自动配置）。

**Snowflake worker-id（多级兜底）**：`WORKER_ID` 环境变量 → `-Dworker.id` → **IP+端口推导** `floorMod(ipPart*31 + serverPort, 1024)`。

**非分片键查询（订单号反查）**：`t_order_no_mapping`（order_no → user_id）放**公共库独立数据源**，绕过分片路由；写入同步落映射 + `OrderMappingRepairJob`（@XxlJob，5min）补录。

**支付域不分片（`PaymentDataSourceConfig` 三理由）**：① 支付表需精确路由到 my_xhs_payment；② 支持非分片键查询（按订单号查支付状态）；③ 退款乐观锁状态更新必须原子路由。

**压测隔离（`ShadowTableInterceptor`）**：默认关（`myxhs.shadow.enabled=true` 才生效）；`X-Pressure-Test` 请求把 `t_*` 表名改写为 `t_*_shadow`——压测数据不污染生产表。

**连接池**：每 ds 独立 Hikari（max 10 / min 2 / keepalive 30s / maxLifetime 30min）；`max-connections-size-per-query: 2`。

### ③ 坑与事故（三段真实演进，面试含金量最高）

**事故 1：Snowflake 低位偏差 → 分片严重倾斜（2026-09-20）**
```
根因：user_id 也是 Snowflake 生成（timestamp<<22 | worker<<12 | seq）
  → Δms<<22 恒被 16 整除；低流量时 Δseq=0 → user_id mod 16 恒定
  → 131 单里 75% 落 2/16 个分片（分片形同虚设）
修复前分布实证：ds1.t0=99、ds2.t0=32、其余 14 片=0（最大 75%）；映射表 131=131（非一致性问题）
修复：hashCode(x) & 0x7fffffff 取模（高位混入低位）；**算法切换是破坏性变更**（不迁移存量则旧数据在新算法下不可见）
迁移：`migrate-sharding-hash.py`（支持 --dry-run）5 表 × 16 片按 id 游标；**停 order 服务约 2 分钟**执行，共 682 行
验证：16/16 片全有数据（最大 41/31% 为热点用户 10001 单用户聚集，属预期）；审计脚本 `sharding-distribution-check.sh`（分布+映射+路由抽查，exit 可告警）；E2E 30/30
```

**事故 2：worker-id 冲突（同机多实例）**
```
原实现仅用 IP：两个实例都得 257 → 同毫秒同序列生成相同 Snowflake ID
修复：加入端口维度（354 vs 454）→ floorMod(ipPart*31+port, 1024)
```

**事故 3：sql-show 生产血泪（P-D32）**
```
sql-show: true 把每条物理 SQL 打日志——16 分片放大后日志爆炸 + 性能损耗
修复：生产 false（配置注释保留血泪）
```

**明确边界**：固定 4×4 **无 rehash 能力**——扩容要走双写迁移/影子表同步（简历主动写"暂无 rehash"，不装）。

### ④ 兜底

- 映射表 + `OrderMappingRepairJob` 补录（分片外的第二索引）
- 本地消息表在分片内（同 user_id 同分片）→ 本地事务原子
- 跨系统一致性走事务消息 + 对账（K01/K06 同体系），不上 XA

### ⑤ 拷打追问（面试官视角）

1. **"分片键为什么选 user_id？"** 交易查询 90% 是"我的订单"；下单/支付/取消全在单用户上下文——**按最热查询模式选**（选订单号则用户列表全路由）。
2. **"4×4 怎么定？"** 单表容量/增长预估 + 单实例并行度；分片过多放大协调成本、过少无扩展余量。
3. **"用订单号查订单怎么定位分片？"** 映射表（order_no → user_id）+ 补录 Job；不靠扫描全分片。
4. **"绑定表原理？"** 同分片键+同算法 → 相同 user_id 的 JOIN 行落在同一物理分片，单分片执行。
5. **"Snowflake 的坑？"** ① 时钟回拨（内置等待/异常策略）；② **低位偏差导致取模倾斜（我们踩过）**；③ worker-id 冲突（多实例）。
6. **"跨分片分页？"** LIMIT/OFFSET 改写为各分片 `limit 0, offset+limit` 再内存归并；深分页代价高——产品侧禁跳页/改游标。
7. **"为什么 JDBC 不用 Proxy？"** 无额外进程/网络跳数/与 Spring 事务集成好；Proxy 适合多语言/遗留系统；代价是分片逻辑与应用耦合。
8. **"扩容怎么办？"** 诚实答：当前固定 4×4 无 rehash——方案是双写迁移（新老规则并行）+ 影子表比对 + 切读；这才是"会做"而不是"做过"。

### ⑥ 话术

> "订单按 user_id 4 库×4 表、两级哈希 16 分片，五张表绑定成组——同用户数据同库同表后缀，JOIN 不跨库、单用户事务不跨库。主键 Snowflake、worker-id 用 IP+端口推导防多实例冲突；订单号反查用公共库映射表绕过分片。坑都真实：Snowflake 低位偏差让分片一度 75% 数据挤在 2/16 片，改成 hashCode 取模 + 迁移 682 行修复；sql-show 开着把 16 分片物理 SQL 全打日志，生产事故级已关。扩容是短板——固定 4×4 没有 rehash，真要做是双写迁移 + 影子表比对。"

---

## 生产陷阱（ShardingSphere 8 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **分片键拍脑袋** | 全路由查询爆炸——按最热查询模式选（本项目 user_id） |
| 2 | **Snowflake 低位取模倾斜** | 低位是序列（低流量恒 0）——hashCode/高位混合后取模 |
| 3 | **worker-id 冲突** | 同机多实例——IP+端口/环境变量注入 |
| 4 | **sql-show 生产开启** | 物理 SQL 日志爆炸（16 分片放大）——生产 false |
| 5 | **绑定表配错** | JOIN 笛卡尔积（每库拉全表）——分片规则必须同构 |
| 6 | **深分页跨分片** | 内存归并代价大——禁跳页/游标 |
| 7 | **无映射表的非分片键查询** | 16 分片全路由——映射表 + 补录兜底 |
| 8 | **扩容无方案** | 固定分片数——双写迁移/影子表先行设计 |

---

## 发散追问地图（横向）

- **内核**：SQL 解析（ANTLR）/路由/改写/归并细节；Hint 强制路由
- **算法**：INLINE/自定义/HASH_RANGE；基因法分片（订单号嵌 user_id 后缀）
- **迁移扩容**：rehash、双写、影子表比对、停机/不停机方案（ShardingSphere Scaling）
- **生态**：JDBC vs Proxy；分布式事务（XA/Seata，本项目不用）；联邦查询
- **周边**：号段（Leaf/Segment）、Snowflake 变体（百度 uidgen）、UUIDv7；分片监控/慢 SQL
- **业务**：订单号生成策略（含分片信息 vs 映射表反查）

---

## 面试官评分点

**高级开发级**：五步内核流程、INLINE 规则、绑定表、Snowflake、映射表方案。
**架构师加分**：分片键的业务驱动论证；**Snowflake 低位取模倾斜的实战诊断与修复**；worker-id 多级兜底；绑定表组设计使单用户事务不跨库；扩容短板的诚实方案；sql-show 事故叙事。
**危险信号**：分片键拍脑袋；跨分片查询没方案；宣称 XA 解决一切；不知道绑定表；Snowflake 只会背"时钟回拨"。

---

## 本项目真实证据

**xhs-project**：
- `my-xhs-order/src/main/resources/sharding-config.yaml`：4 ds（Hikari 10/2/30s/30min）、5 逻辑表、绑定表组、**修复后 INLINE 表达式（hashCode & 0x7fffffff）**、Snowflake、sql-show=false（P-D32 注释）、max-connections-size-per-query: 2
- `ShardingSphereDataSourceConfig`：手动工厂（SB 3.2.5 协议不识别）+ worker-id 三级推导（WORKER_ID/-D/`floorMod(ipPart*31+port,1024)`，2026-09-20 修复注释）+ 手动 SqlSessionFactory
- `PaymentDataSourceConfig`（不分片三理由注释）；`MappingDataSourceConfig`（公共库映射表）；`OrderMappingRepairJob`（@XxlJob）
- `ShadowTableInterceptor`（默认关 + X-Pressure-Test 影子表）
- 版本：ShardingSphere-JDBC **5.5.1**（pom.xml:83）
- 事故记录：倾斜修复（131 单 75%→16/16 全用，迁移 682 行）；worker-id 257 冲突→354/454

**源码语料**：`code/spring/shardingsphere` **5.5.2**（行号级深入时以此为准）。

**课程对照**：xhs 49-ShardingSphere深度拷打 + 13-分库分表。

---

## 版本与来源

ShardingSphere **5.5.1**（xhs-project）；源码语料 **5.5.2**（`code/spring/shardingsphere`）；官方文档；xhs 配置/代码/事故记录。

## 真实性说明

分片规则/表清单/池参数/worker-id 算法/影子表均为配置与代码事实（注释已核）；倾斜事故（131 单/682 行/16 片）与 worker-id 修复为变更记录事实；"无 rehash"主动披露。
