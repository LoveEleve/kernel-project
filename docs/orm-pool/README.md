# 连接池与 ORM 系列

> 语料：**hikaricp**（HikariPool/PoolEntry 已核）+ **druid**（core/doc 中文文档已核）+ **mybatis**（DefaultSqlSession/SimpleExecutor/InterceptorChain 已核）+ **mybatis-plus**
> xhs 实锤：MP 3.5.7（pom）全服务 30 处（BaseMapper/IService）；`ShadowTableInterceptor`=MyBatis 插件机制的实战件（RocketMQ 06/Sharding 04 已见其效果）
> 计划：**L5-8 连接池与 ORM** 🟡

## 一、总表（9 篇：概念 → 原理 → 使用 → 深水 → 面试；池线 3 篇）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是池化与 ORM：全景](01-pool-orm-overview.md) | **概念** | 连接池为什么存在；ORM 分层 What | 源码结构 |
| 02 | [原理一：HikariCP 借还主路](02-hikaricp-borrow.md) | **原理** | getConnection 快速路径；ConcurrentBag；PoolEntry | hikaricp 源码 |
| 03 | [原理二：HikariCP 参数与治理](03-hikaricp-governance.md) | **原理** | keepAlive/evict/泄漏检测；参数语义全表 | hikaricp 源码 |
| 04 | [原理三：Druid](04-druid.md) | **原理** | 监控/SQL 防火墙；与 Hikari 选型 | druid 源码+doc |
| 05 | [原理四：MyBatis 核心结构](05-mybatis-core.md) | **原理** | SqlSession/Executor 两层；一次查询的旅程 | mybatis 源码 |
| 06 | [原理五：插件与缓存](06-plugin-cache.md) | **原理** | InterceptorChain 四大目标；一二级缓存；xhs 插件案例兑现 | mybatis 源码+xhs |
| 07 | [使用：MyBatis-Plus](07-mybatis-plus.md) | **使用** | wrapper/逻辑删除/xhs 用法 | MP 源码+xhs 30 处 |
| 08 | [深水：池与 SQL 故障排查](08-pool-sql-troubleshoot.md) | **深水** | 泄漏/耗尽/慢 SQL 定位路径 | 交叉 |
| 09 | [面试收口](09-interview-wrapup.md) | **面试** | 八篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
池线：02 → 03 → 04
ORM 线：05 → 06 → 07
深水：08
收口：09
```

## 三、前置要求

- MySQL 基础（ShardingSphere 系列用过索引/事务语境）。
- Spring 内核 02/03（DataSource 也是 Bean；插件链与 AOP 哲学同源）。

## 四、掌握分级

- **必须掌握**：池化目标与 Hikari 快速路径；MyBatis 两层结构与插件四大目标；MP 与原生分工；池故障排查路径。
- **理解思想**：池=空间换时间+泄漏治理；插件=责任链（又一次）；缓存一致性边界。
- **知道即可**：Druid SQL 防火墙细则；MP 代码生成；多数据源路由（Sharding 系列已带）。

## 五、边界

- 分库分表/影子表 → ShardingSphere 系列（本系列插件机制讲 ShadowTableInterceptor 的"怎么实现"）。
- Hikari 与 Druid 的完整压测对比 → 工程选型一句话级，不开专题。
- JPA/Hibernate → 非 xhs 技术栈，不做。
