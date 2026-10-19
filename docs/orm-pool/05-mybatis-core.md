---
title: "05-原理四：MyBatis核心结构"
series: "连接池与 ORM 系列"
previous: "04-Druid.md"
next: "06-插件与缓存.md（待写）"
source_refs:
  - "mybatis 源码已核：DefaultSqlSession:47(selectOne:68/73,update:188)/SimpleExecutor:37(doQuery:57,doUpdate:44)/Configuration:103/MappedStatement:34/InterceptorChain:25(pluginAll:29)"
---

# 05-原理四：MyBatis核心结构

## §0 它干了什么（例子先行）

`orderMapper.selectByUser(10001)` 之后发生的事：

```
Mapper 接口（无实现类！）→ JDK 动态代理 → SqlSession.selectOne("...方法全限定名", 10001)
  → Configuration 找 MappedStatement（这条 SQL 的完整图纸：SQL 文本/参数映射/结果映射）
  → Executor（Simple/Reuse/Batch 三选一）执行
      → 从 DataSource（02~04 的池）借连接 → 预编译 → 参数设置 → 查询
      → ResultSetHandler 把行映射成对象（ResultMap）
  → 返回 List<Order>
```

- **它干了干什么**：**接口代理 + 图纸（MappedStatement）+ 执行器（Executor）**三件套——SQL 写在图纸里，接口只是门面。
- **本篇回答**：三大件各干什么？一次查询走哪些类？事务边界在哪？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **DefaultSqlSession** | 会话门面（:47）——selectOne(:68)/update(:188) 等入口 |
| **Configuration** | 全局大配置（:103）——MappedStatement 仓库/插件/类型处理器/环境 |
| **MappedStatement** | 单条 SQL 的图纸（:34）——id（=方法全限定名）/SQL 文本/映射规则 |
| **Executor** | 执行器三兄弟：Simple（默认）/Reuse（复用 Statement）/Batch（批量） |
| **BaseExecutor 一级缓存** | Executor 层的 localCache——同 session 同查询复用（06 缓存篇主角） |

## §2 一次查询的旅程（源码锚点版）

```
mapperProxy.invoke
  → SqlSession.selectOne(msId, param)          ← DefaultSqlSession:68/73
  → Configuration.getMappedStatement(msId)     ← 图纸 (:34 的实例)
  → executor.query(ms, param, ...)             ← SimpleExecutor:57 doQuery
      → BaseExecutor 查一级缓存
      → newStatementHandler → 从 DataSource.getConnection()（池！）
      → PreparedStatement 执行 → ResultSetHandler 映射
  → 缓存结果 → 返回
```

**update 走 doUpdate(:44)**——增删改不同路（且**默认清一级缓存**——同 session 写后查一致的机制）。

## §3 工程使用

```java
// 事务边界：SqlSession 级（Spring 托管时=@Transactional）
@Transactional
public void create(Order o) { orderMapper.insert(o); itemMapper.batch(o.getItems()); }
// 两个 Mapper 同一事务——因为同一个 Spring 事务绑定的 SqlSession（连接同一条）
```

| 工程点 | 说明 |
|---|---|
| Mapper 接口无实现 | JDK 代理（和 03 AOP 家族同源——**接口+代理贯穿全 session**） |
| sqlSessionFactory 单例 | Configuration 重——全局一份；SqlSession 每请求一个（薄） |
| 事务与池的关系 | @Transactional 开启→从池借连接→事务提交/回滚→会话关→**连接归还池**——02 §4 "事务边界内用"的完整图 |
| #{} vs ${} | #{} 预编译参数（防注入）；${} 字符串替换——**除非拼表名否则永不用 ${}** |

## §4 掌握卡（2 条）

1. **三件套职责**：SqlSession 管入口/事务，Configuration 管图纸仓库，Executor 管执行——**MappedStatement 是图纸、Executor 是车间**。
2. **两层缓存的门牌**：一级缓存在 BaseExecutor（session 级，06 展开）；二级在 Executor 之间（namespace 级）——先记门牌，06 拆机制。

## §5 边界（一句话翻篇）

- ResultHandler/TypeHandler 全集 → 用时查。
- BatchExecutor 的批处理语义 → 用时查。
- 动态 SQL（SqlSource/OGNL）→ 用时查（面试偶尔问 if/foreach 编译产物）。

## §6 Self-Test（3 题）

- Q1：Mapper 没有实现类为什么能注入？（JDK 代理+msId 约定）
- Q2：MappedStatement 里装了什么？id 怎么和方法对应？
- Q3：@Transactional 里两个 Mapper 为什么同事务？（同 session 同连接）

## §7 衔接

- 核心结构收口；下篇 06 插件与缓存：InterceptorChain 四大目标 + 一二级缓存陷阱 + xhs ShadowTableInterceptor 兑现。
