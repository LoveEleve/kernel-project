---
title: "06-原理五：插件与缓存"
series: "连接池与 ORM 系列"
previous: "05-mybatis-core.md"
next: "07-MyBatis-Plus.md（待写）"
source_refs:
  - "mybatis 源码已核：InterceptorChain.pluginAll:29/Plugin.java/Intercepts+Signature；四大目标=Executor/StatementHandler/ParameterHandler/ResultSetHandler"
  - "xhs ShadowTableInterceptor.java:45-48（@Intercepts 挂 Executor.update/query——RocketMQ 06 见过其效果，本篇兑现机制）"
---

# 06-原理五：插件与缓存

## §0 它干了什么（例子先行）

xhs 的影子表改写（RocketMQ 06/Sharding 04 都见过它的效果）——机制就在 MyBatis 插件：

```java
@Intercepts({
    @Signature(type = Executor.class, method = "update", ...),   // 增删改入口
    @Signature(type = Executor.class, method = "query",  ...)    // 查询入口
})
public class ShadowTableInterceptor implements Interceptor {
    public Object intercept(Invocation inv) {
        // 把 SQL 里的 t_order 改写成 t_order_shadow 再放行
        return inv.proceed();
    }
}
```

- **它干了干什么**：**在四大扩展点上织入逻辑**——SQL 改写/分页/审计/耗时全从这进；xhs 在 Executor 层截住语句文本做表名替换。
- **本篇回答**：四大目标是谁？插件怎么"包"对象？一二级缓存各是什么、坑在哪？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **四大目标** | Executor（执行）/StatementHandler（语句）/ParameterHandler（参数）/ResultSetHandler（结果）——`@Signature` 指定"包谁+拦哪个方法" |
| **Plugin.wrap** | 用 JDK 动态代理把目标包一层——**又一次见到动态代理**（内核 AOP/Mapper 代理/这里） |
| **InterceptorChain** | 插件链（pluginAll:29）——多个插件依次 wrap（**包装顺序=链序**） |
| **一级缓存** | BaseExecutor 的 session 级 localCache——同 session 同参数查询直接返回 |
| **二级缓存** | namespace 级（跨 session）——需配置开启，默认关 |

## §2 缓存的坑（工程必修）

```
一级缓存陷阱：同一 SqlSession 里 mapper.update 后 mapper.select 同参
  → 写会清一级缓存（05 已讲）——所以"同 session 写后读"是安全的
  → 但：不同 session（不同线程）之间没有这层保证——跨请求读到旧值是正常的缓存语义

二级缓存陷阱（更狠）：
  → 不配序列化装饰器时跨 session **共享对象引用**——并发修改直接踩踏
  → 配了序列化则每次读写付序列化成本，且"反序列化副本"与业务持有的旧引用脱钩
  → 分布式环境二级缓存不同步——"本地缓存的一致性幻觉"
  → 生产建议：**直接关二级**（cacheEnabled=false）——除非完全理解失效模型
```

**口诀**：**一级可留（框架自动管写清），二级默认关**——除非你完全理解它的失效模型。

## §3 xhs 案例兑现（跨系列三连击的机制收尾）

| 系列 | 见过什么 | 本篇补什么 |
|---|---|---|
| RocketMQ 06 | ShadowTableInterceptor=自研 7 类之一 | 它 @Intercepts 挂 Executor.update/query（:45-48 实锤） |
| Sharding 04 | 影子表开关默认关 | 开关打开后 SQL 在插件里被改写——`inv.proceed()` 前重写语句文本 |
| 本篇 | —— | **插件四大目标的"Executor 派"就是干这个的** |

## §4 掌握卡（2 条）

1. **插件三件套**：@Intercepts 声明目标 → Plugin.wrap 动态代理 → InterceptorChain 串链——与 Druid FilterChain/Sentinel Slot 同族：**可插拔责任链第 6 个战场**。
2. **缓存两层**：一级 session 级自动管（update 清）；二级 namespace 级默认关（一致性幻觉）——**生产答案：留一关二**。

## §5 边界（一句话翻篇）

- PageHelper 分页插件原理 → 同为 StatementHandler 派，思路自推。
- 缓存装饰器（L2 的序列化存储）→ 用时查。
- 四大目标之外的扩展点（SqlSource 等）→ 不存在——就这四个。

## §6 Self-Test（3 题）

- Q1：四大目标是谁？xhs 插件挂哪个？
- Q2：@Signature 的三要素？（type/method/args）
- Q3：二级缓存为什么默认关？"一致性幻觉"指什么？

## §7 衔接

- 插件与缓存收口；下篇 07 MyBatis-Plus：在内核之上长出来的便捷层（wrapper/逻辑删除——xhs 30 处的用法全景）。
