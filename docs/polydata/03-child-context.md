---
title: "03-原理二：子上下文隔离与 Bean 升迁"
series: "PolyData 动态多数据源系列"
previous: "02-config-pipeline.md"
next: "04-hot-swap.md"
source_refs:
  - "已核：DynamicJdbcChildContext:46（extends AnnotationConfigApplicationContext/process 复用:161）/ 建立：启动路径三步 registerParentBeans→mergeParentEnvironment→refresh（Listener:191-195），重建路径仅 merge+refresh（DynamicDataSource:198-200，registerParentBeans 全仓仅 Listener:191 一处调用）/ 升迁监听器 DynamicJdbcChildContextRefreshedListener:51（ContextRefreshedEvent/exposed 判定:158-167/默认名 childContextId+$:139/registerParentBean:149-156 双路径）/ BeanRegistrar.registerBean:54（InstanceSupplier+beanClass+primary:62）/ registerFactoryBean:84（DelegatingFactoryBean+OBJECT_TYPE_ATTRIBUTE——注释明确类型查找仍可用）/ expose/primary 属性常量 :85,:87,:109-110"
---

# 03-原理二：子上下文隔离与 Bean 升迁

## §0 它干了什么（例子先行）

一套配置 = 一个**独立小 Spring**（DynamicJdbcChildContext:46）：

```
多配置时，每个 polydata.jdbc.configs.<name> 长出自己的子上下文
（单配置复用父上下文跑 6 步管道，只有 DynamicDataSource 内部再建 datasource 子上下文）：
  ├─ 自己的物理 DataSource、自己的 SqlSessionFactory、自己的事务管理器
  ├─ 跑同一条 6 步管道（:161——但 setDynamic(false) 已防递归，02 篇）
  └─ refresh 完成时 → 升迁监听器:51 醒来，把关键 Bean 搬进父上下文
       名字：childContextId + "$" + childBeanName :139（如 "orders$dataSource"）
       类型 ∈ expose 列表（默认 DataSource 等 README:137）→ registerBean:54 + @Primary:62
       其余 → DelegatingFactoryBean 包一层 registerFactoryBean:84
```

外面（业务代码）`@Autowired DataSource` 拿到的就是**升迁上来的实例**——但它的生命周期、连接池、事务都活在子上下文里。

- **它干了什么**：**隔离换并存**——MyBatis 与 MyBatis-Plus 打架？各关各的子上下文互看不见；升迁让外面**用起来像一个 Spring**。
- **本篇回答**：隔离什么、并存怎么实现？升迁的两条注册路径差在哪？为什么需要配置级 `"primary":true`？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **子上下文** | `AnnotationConfigApplicationContext` 子类:46——**有自己的 BeanFactory**；启动路径三步建立：registerParentBeans→mergeParentEnvironment→refresh（:191-195） |
| **隔离边界** | 多配置时每配置的 DataSource/SqlSessionFactory/事务只在自己上下文——**类加载器之外的第二重隔离**（README:35"ORM 隔离与并存"）；单配置复用父上下文 |
| **升迁（expose）** | 子 refresh 后由监听器:51 把**类型命中 expose 列表**的 Bean 注册进父（判定:157-164）——范围可配（`bean-classes.expose`:109-110，默认 DataSource 等） |
| **双路径注册** | expose 类→`registerBean:54`（实例+beanClass+primary:62）；其余→`registerFactoryBean:84`（DelegatingFactoryBean 委派）——**源码注释明确：两条路类型查找（getBean(Class)）都可用**（OBJECT_TYPE_ATTRIBUTE 保留目标类型） |
| **@Primary** | 需要**两个条件同时满足**：配置 JSON 里 `"primary":true`，且 Bean 类型在 `bean-classes.primary` 列表（默认含 DataSource）——只有类型列表不会自动产生 @Primary |

**命名规则**：`childContextId + "$" + beanName`（:139）——**前提是 `name` 唯一**：子上下文 ID 由配置 `name` 生成，重复 name 会在父上下文 Bean 名碰撞，现已在校验阶段拒绝。SPI `ParentContextBeanNameGenerator` 可自定义。

## §2 关键推导（两问）

**① 为什么需要"升迁"这一步？（不升迁会怎样）**
若不升迁：业务在父上下文 `@Autowired DataSource`——**找不到**（Bean 在子上下文里，父子 BeanFactory 不共享）。升迁=**把实例注册进父的 BeanDefinitionRegistry**：父侧按类型/名字拿，实例背后仍连着子上下文的池。**这不是复制，是"引用上交"**——04 篇热替换时换的也是这份上交的引用。

**② 隔离为什么能解 MyBatis/MP 互斥？**
互斥的本质是**类与配置的冲突**（orm-pool 07 讲过：两者都魔改 SqlSessionFactory 生态）——子上下文各自装配各自的栈，banned-modules 在自动配置过滤层双向拦截（default.properties 实锤：mybatis↔mybatis-plus :45/:49、datasource↔sharding-sphere :12/:33），**类空间隔离+装配隔离双保险**；进程内并存的前提由此成立（06 篇展开）。

**③ 双路径的工程含义**（读码即答）：
expose 走直注（父直接持有实例定义，@Primary 生效于类型注入）；其余走 DelegatingFactoryBean——**委派同一实例**，类型查找仍可用（:84 注释原话）。差异是注册形态不是能力边界——**记"两条路都通类型查找"就不会答错**。

## §3 掌握卡（2 条）

1. **一配置一世界**：子上下文启动三步建立（父 Bean 注册/环境合并/refresh）→ 同管道装配 → 隔离边界=ORM 并存的根。
2. **升迁=引用上交**：expose 列表定范围、name 唯一防撞、`"primary":true` + primary 列表定赢家；**双路径类型查找都通**（源码注释背书）。

## §4 边界（一句话翻篇）

- banned-modules 自动配置过滤细节 → 06 篇。
- ParentContextBeanNameGenerator SPI → 知道可扩展即可。
- mergeParentEnvironment 的合并规则 → 按需读源码。

## §5 Self-Test（3 题）

- Q1：子上下文建立三步？和父的环境什么关系？
- Q2：升迁解决什么问题？"引用上交"指什么？
- Q3：expose 与 primary 两个列表各管什么？配置级 `"primary":true` 起什么作用？双路径类型查找可用吗？

## §6 衔接

- 隔离收口；下篇 04 热替换：深克隆→重建→volatile+synchronized 原子换→延迟关闭——不杀在途请求的四步。
