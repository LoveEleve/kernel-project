---
title: "05-核心源码：路由·改写·归并引擎"
series: "ShardingSphere 分库分表系列"
previous: "04-usage-config.md"
next: "06-深水问题.md（待写）"
source_refs:
  - "源码 5.5.2：features/sharding/core/.../route/engine（ShardingSQLRouter+RouteEngineFactory 五引擎——目录已核）"
  - "源码 5.5.2：infra/rewrite（改写引擎）/ infra/merge（ResultMergerEngine/MemoryMergedResult——类名已核）/ infra/executor"
---

# 05-核心源码：路由·改写·归并引擎

## §0 它干了什么（例子先行）

03 的五步在 5.5.2 源码里不是五个"if"，是**五个可插拔引擎**（模块级落点）：

```
解析 → kernel/sql-parser（ANTLR 文法 → SQLStatement 语法树）
路由 → features/sharding/core/.../route/engine
         ShardingSQLRouter（分片路由的入口，SPI 实现）
         ShardingRouteEngineFactory → 选引擎：
           standard / complex / broadcast / unicast / ignore（+笛卡尔归入 complex）
改写 → infra/rewrite（SQLRewriteContext：逻辑表→物理表 + 分页聚合改写）
执行 → infra/executor（按路由结果分组，并发跑多数据源）
归并 → infra/merge（ResultMergerEngine → MemoryMergedResult 内存归并 / Transparent 直通）
```

- **它干了什么**：五步=五个模块，**每步一个 SPI**——encrypt/shadow/读写分离等 feature 也挂同一批钩子。
- **本篇回答**：五步各在哪个模块？路由为什么分五种引擎？归并的两态是什么？

## §1 掌握卡（4 条）

1. **路由是策略分发点**（源码目录实锤）：`ShardingRouteEngineFactory` 按 SQL 形态选引擎——**standard**（带分片键，单格）/ **complex**（多键，含笛卡尔）/ **broadcast**（DDL 广播全格）/ **unicast**（单格但非键路由）/ **ignore**（不分片表直连）——03"三种命运"的源码版是**五种命运**。
2. **改写在 `infra/rewrite`**：`SQLRewriteContext` 聚集改写单元（表名单元/分页单元/加密单元…）——**改写是通用层**，sharding 和 encrypt 都往里挂单元（一表多 feature 的组合就在这层拼）。
3. **归并两态**（infra/merge 类名实锤）：`TransparentResultMerger`（路由到单格——**直通不合并**）vs `MemoryMergedResult`（多格——内存里排序/聚合/LIMIT）——单格查询不为归并付账，03 快慢表的源码根据。
4. **SPI 贯穿**：路由（SQLRouter）/改写（SQLRewriteContext 单元）/归并（ResultMerger）都是接口+多实现——**加一个 feature=加一组引擎实现**，内核不改。入口编排：JDBC 的 execute 管线按"解析→路由→改写→执行→归并"顺序串五个引擎（入口类行级位置用时查 5.5.2）。

## §2 五步 × 模块 × 关键类（骨架表）

| 步 | 模块 | 关键类（5.5.2 已核） |
|---|---|---|
| 解析 | `kernel/sql-parser`（ANTLR） | 文法→`SQLStatement` 树；解析缓存配置（YamlSQLParserCacheOption…） |
| 路由 | `features/sharding/core/.../route/engine` | `ShardingSQLRouter`、`ShardingRouteEngineFactory`、五引擎、`condition/` 分片值提取 |
| 改写 | `infra/rewrite` | `SQLRewriteContext` + 改写单元 |
| 执行 | `infra/executor` | 按 RouteContext 分组并发执行 |
| 归并 | `infra/merge` | `ResultMergerEngine`、`TransparentResultMerger`、`MemoryMergedResult` |

**condition/ 子包值得点名**（路由引擎旁）：`engine/condition/generator/impl`——**从 SQL 条件里提取分片值**（WHERE user_id=? 的值就从这来），路由的"原料车间"。

## §3 路由引擎怎么选（Factory 的决策树）

```
SQL 目标表都是不分片表？ → ignore/unicast
单分片键 + 有值条件？     → standard（90% 主路）
多键/无值/组合？          → complex（内含笛卡尔）
DDL/系统表？             → broadcast（对 16 格逐一下发——04 说的"DDL 翻译"；DDL=建表改表类结构变更语句）
```

（决策树为结构推断自引擎命名与工厂存在性，行级条件以 5.5.2 `ShardingRouteEngineFactory` 为准——本篇给地图不逐行。）

## §4 边界（一句话翻篇）

- 行级数据流（routeContext 字段流转/改写单元全集/归并的 order/aggregation 实现）→ 5.5.2 语料按需深挖。
- ANTLR 文法文件位置 → `grammar/` 目录用时查。
- sql-federation（联邦查询，跨分片复杂 SQL）→ 知道存在即可（kernel 模块清单里有），xhs 配置未见使用。

## §5 Self-Test（4 题）

- Q1：五步各对应哪个模块？（router/rewrite/executor/merge/parser）
- Q2：路由五引擎各管什么 SQL 形态？
- Q3：单格查询走哪个 Merger？（Transparent——不付内存归并账）
- Q4：encrypt/shadow 怎么和 sharding 共存？（同挂 rewrite/merge 的 SPI）

## §6 衔接

- 源码层收口；下篇 06 深水问题：主键三坑、哈希取模倾斜、非分片键方案、扩容 rehash——原理性难题的战场。
