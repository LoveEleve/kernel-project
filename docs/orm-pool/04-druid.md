---
title: "04-原理三：Druid"
series: "连接池与 ORM 系列"
previous: "03-hikaricp-governance.md"
next: "05-MyBatis核心.md（待写）"
source_refs:
  - "druid 源码已核：pool/DruidDataSource/wall/ 目录/stat/DruidDataSourceStatManager/filter/{FilterChain,FilterAdapter,stat/StatFilter}"
  - "druid/doc 已核（ha-datasource.md 等中文文档在库）"
---

# 04-原理三：Druid

## §0 它干了什么（例子先行）

Druid 不只是"另一个连接池"——它在池之上长了两层 Hikari 没有的器官：

```
业务 SQL
  → DruidDataSource（池化——借还与 Hikari 同类问题，实现不同）
  → FilterChain（责任链：stat 统计 → wall 防火墙 → …）  ← 每条 SQL 过链
  → 真正执行
统计侧：SqlStats/慢SQL/Wall 拦截记录 → DruidStatView（内置监控页）
```

- **它干了干什么**：**池 + SQL 观测 + SQL 防火墙**三合一——"数据库连接管家"升格为"数据库入口安检"。
- **本篇回答**：FilterChain 长什么样？stat/wall 各管什么？和 Hikari 怎么选？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **DruidDataSource** | 池本体（pool/ 目录；DruidConnectionHolder=连接户口——PoolEntry 的同类） |
| **FilterChain** | **SQL 过滤责任链**（filter/FilterChain+FilterAdapter 已核）——第 N 次见到责任链（Sentinel Slot/内核 BPP 同族哲学） |
| **StatFilter** | 统计滤镜——SQL 耗时/执行次数/慢 SQL 记录（stat/StatFilter 已核） |
| **Wall（防火墙）** | SQL 语义检查——拦注入类语句/危险操作（wall/ 目录已核） |
| **DruidDataSourceStatManager** | 池级统计聚合（stat/ 已核）——喂给监控页/metrics |

## §2 与 Hikari 的选型（工程高频问）

| 维度 | HikariCP | Druid |
|---|---|---|
| 定位 | **只做池**——极致简单与快 | 池+**观测+防火墙** |
| 复杂度 | 代码量小、心智负担低 | 功能多、配置面大 |
| 监控 | 靠 Micrometer Binder（micrometer 03） | **内置 StatView/SQL 统计**开箱即用 |
| 防火墙 | 无 | Wall 滤镜（生产可开 SQL 检查） |
| Boot 默认 | ✅（spring.datasource 默认 Hikari） | 换 starter（druid-spring-boot-3-starter 目录已核） |
| **选型直觉** | 要快与简、监控走 metrics 体系 | 要**开箱 SQL 可观测/防注入**、DB 入口管控强需求 |

**xhs 语境**：连接参数按 Hikari 配（Sharding 04 那组）——**栈默认 Hikari**；Druid 是"需要数据库入口管控"时的升级选项，不是信仰之争。

## §3 掌握卡（2 条）

1. **Druid 三件套**：池（pool/）+ 统计（stat/）+ 防火墙（wall/）——**FilterChain 是粘合层**（责任链哲学第 5 次出现：框架骨架+可插拔行为）。
2. **选型一句话**：默认 Hikari；要内置 SQL 观测/防火墙上 Druid——**"要不要把 SQL 层纳入池的管辖"是唯一分水岭**。

## §4 边界（一句话翻篇）

- Wall 规则语法/多数据源 HA（doc/ha-datasource.md）→ 库内中文文档用时查。
- StatView 部署（登录/白名单）→ 运维侧。
- PSCache（PreparedStatement 缓存，Oracle 时代议题）→ MySQL 下知道即可（prepared statement 重用已由驱动处理，池层缓存收益争议）。

## §5 Self-Test（3 题）

- Q1：Druid 比 Hikari 多哪两层器官？
- Q2：FilterChain 和已学的哪些责任链同族？
- Q3：什么信号出现时该从 Hikari 换 Druid？

## §6 衔接

- 池线收官（02/03/04）；下篇 05 MyBatis 核心：SqlSession/Executor——一次查询的完整旅程。
