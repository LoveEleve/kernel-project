---
title: "09-面试收口：八篇回链与陷阱"
series: "连接池与 ORM 系列"
previous: "08-pool-sql-troubleshoot.md"
next: "（本系列完）"
---

# 09-面试收口：八篇回链与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① 八篇要点回链 ② 高频坑速查 ③ 话术（自建）+ 终检
```

## §1 八篇要点回链

| 篇 | 一句话考点 | 回链 |
|---|---|---|
| 01 | 池化账/ORM 账；四组件地图 | 01 |
| 02 | ConcurrentBag 三级借路（热路径零锁）；close=归还 | 02 |
| 03 | 治理四件（寿命/心跳/泄漏/换血）；参数五维度 | 03 |
| 04 | Druid 三件套；FilterChain 责任链；选型分水岭 | 04 |
| 05 | SqlSession/Configuration/MappedStatement/Executor；一次查询旅程 | 05 |
| 06 | 四大目标；Plugin.wrap；一二级缓存（留一关二） | 06 |
| 07 | MP 三层糖；逻辑删除三代价 | 07 |
| 08 | 三线排查；预算铁律 | 08 |

**追问秒答 4 条**：Hikari 为什么快→三级借路热路径零锁（02）；close 之后连接去哪→requite 归还非断开（02）；二级缓存开不开→默认关（06）；逻辑删除的唯一索引坑→deleted 要进唯一键（07）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | 手动 getConnection 不 close | 泄漏——try-with-resources（02/08） |
| 2 | 不设 maxLifetime | DB 掐线报错——03 处方 |
| 3 | 池上限拍脑袋调大 | 预算铁律：池之和 ≤ DB 预算（08） |
| 4 | 二级缓存当性能银弹 | 一致性幻觉——默认关（06） |
| 5 | 逻辑删除后唯一键冲突 | deleted 纳入唯一索引（07） |
| 6 | 生产开 sql-show | 分片放大日志爆炸（Sharding P-D32 血泪） |
| 7 | 多表 JOIN 硬拼 wrapper | 复杂查询写原生 SQL（07） |
| 8 | ${} 拼参 | 注入——#{} 预编译（05） |

## §3 话术（按拷打口径自建）

> "连接池我按 Hikari 讲：ConcurrentBag 三级借路——线程本地列表、共享列表、新建，热路径零锁所以快；close 是归还不是断开（ProxyConnection 拦截）。治理四件：maxLifetime 主动退役防 DB 掐线、keepalive 心跳带随机偏移、ProxyLeakTask 泄漏报警、softEvict 手动换血——参数五维度大小/等待/寿命/心跳/报警，且池之和不能超 DB 连接预算。ORM 侧 MyBatis 三件套：SqlSession 门面、MappedStatement 图纸、Executor 车间；插件四大目标（Executor/StatementHandler/ParameterHandler/ResultSetHandler）——我们项目的影子表改写就挂在 Executor 的 update/query 上。缓存留一关二。MP 是便捷层，逻辑删除注意唯一索引带 deleted 字段。"

## §4 终检（8 题，过 6 算过关）

1. 三级借路；为什么快（02）
2. 治理四件；参数五维度（03）
3. Druid 比 Hikari 多什么（04）
4. 一次查询的旅程五站（05）
5. 四大目标；Plugin.wrap（06）
6. 留一关二；二级幻觉（06）
7. 逻辑删除三代价（07）
8. 池耗尽三根因；预算铁律（08）

## §5 衔接

- **连接池与 ORM 系列 9 篇完，README 全挂链接**——L5-8 闭环；Sharding/RocketMQ 里两见不见其里的 ShadowTableInterceptor 机制终于收口。
- 本 session 累计封卷 **18 个系列 / 127 篇**。
- 剩余候选：**Kafka**（L5-4 配对，素材缺口）——或你另点。
