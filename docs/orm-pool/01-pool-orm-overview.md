---
title: "01-什么是池化与ORM：全景"
series: "连接池与 ORM 系列"
previous: "README.md"
next: "02-HikariCP.md（待写）"
source_refs:
  - "hikaricp：HikariPool/PoolEntry 类已核；druid core+doc 已核；mybatis：DefaultSqlSession/SimpleExecutor/InterceptorChain 已核"
---

# 01-什么是池化与ORM：全景

## §0 它干了什么（例子先行）

```java
try (Connection c = dataSource.getConnection()) {   // 不是 new Connection！
    c.prepareStatement("SELECT ...").executeQuery();  // 原生 JDBC 这样写
}
// 或者 ORM 姿势：
List<Order> list = orderMapper.selectByUser(10001);  // MyBatis：SQL 在 XML/注解，结果自动映射
```

- **它干了什么**：**连接池**把"每次建 TCP+认证的贵操作"变成"借还复用"；**ORM**把 JDBC 的连接/预编译/映射样板变成接口调用。
- **本篇回答**：池化治什么病？ORM 分几层？四套组件（Hikari/Druid/MyBatis/MP）各占什么位？

## §1 概念铺垫（What：池化 + ORM 两半）

**池化半**：

| 词 | 一句话 |
|---|---|
| **连接池** | 预建一批 DB 连接，借出/归还复用——省 TCP 握手+认证（毫秒级→微秒级） |
| **借/还** | `getConnection()`/`close()`（close 归还不是断开——池的语义反转） |
| **池参数** | 最大/最小/超时/存活时间——03 参数与治理主角 |
| **泄漏** | 借了不还——池逐渐枯竭（08 排查主路） |

**ORM 半**：

| 词 | 一句话 |
|---|---|
| **SqlSession** | MyBatis 的一次会话门面——执行 SQL/管理事务/拿结果 |
| **Executor** | 真正干活的执行器（Simple/Reuse/Batch 三兄弟）——**插件和一级缓存都在这层** |
| **Mapper** | 接口绑定 SQL——`@Mapper` + 注解/XML 里的 SQL 映射到方法 |
| **ResultMap** | 列→属性的映射规则（类型转换/嵌套结果） |

**四组件地图**：

```
HikariCP（默认池，Boot 用它）/ Druid（阿里池，监控+防火墙更强） ← 池化半
MyBatis（ORM 内核）→ MyBatis-Plus（MP：在内核上加 wrapper/逻辑删除/代码生成） ← ORM 半
```

## §2 为什么"每次 new 连接"不可行（池化的一句话推演）

建立一条 MySQL 连接=TCP 三次握手+MySQL 认证握手+会话初始化——**毫秒级**；高并发下每请求都 new=握手风暴打爆 DB 连接数上限。池化=**把握手成本摊到冷启动**，热路径只做"借出数组元素"。**代价**：多一层治理（泄漏/参数/健康检查）——03/08 的主题由此而来。

## §3 ORM 的分层（和 Sharding 系列的咬合）

```
业务 Mapper 接口
  → MyBatis 内核：SqlSession → Executor → 语句执行 → 结果映射
      ↳ 插件链（InterceptorChain——06；xhs ShadowTableInterceptor 在这层改写 SQL）
      ↳ 一二级缓存（05）
  → DataSource（Hikari/Druid 池；Sharding 系列的分片 DataSource 再包一层）
      → 驱动 → MySQL
```

**一句话**：**池管连接，MyBatis 管语句，MP 管便捷**——三层各司其职，Sharding 再在最外面包分片（Sharding 04 集成坑的结构根据）。

## §4 掌握卡（2 条）

1. **池化的账**：省握手（毫秒→微秒）换治理成本（泄漏/参数/健康检查）——"借了要还"是第一纪律（try-with-resources）。
2. **ORM 的账**：省样板换透明度成本（SQL 不可见/缓存陷阱/插件链）——**"知道底下跑什么 SQL"是 ORM 使用者的底线素养**（慢 SQL 排查都从这来）。

## §5 边界（一句话翻篇）

- Hikari/Druid 内部 → 02/03。
- MyBatis 执行链 → 05；插件/缓存 → 06；MP → 07。
- 多数据源/读写分离 → Sharding 系列 04 已带。

## §6 Self-Test（2 题）

- Q1：close() 在池语义下是断开还是归还？为什么？
- Q2：四组件地图一句话；插件链挂在 ORM 的哪一层？

## §7 衔接

- 全景收口；下篇 02 HikariCP：默认池的快速路径与防逃逸设计。
