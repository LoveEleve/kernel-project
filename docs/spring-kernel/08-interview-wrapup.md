---
title: "08-面试收口：七篇回链与陷阱"
series: "Spring 内核系列"
previous: "07-events-environment.md"
next: "（本系列完）"
---

# 08-面试收口：七篇回链与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① 七篇要点回链 ② 高频坑速查 ③ 话术（自建）+ 终检
```

## §1 七篇要点回链

| 篇 | 一句话考点 | 回链 |
|---|---|---|
| 01 | 容器三层；图纸/成品分离 | 01 |
| 02 | 五段生命周期；钩子选型口诀（@PostConstruct/@PreDestroy/Processor 分工） | 02 |
| 03 | AOP 四词；JDK vs CGLIB；失效五连；传播三件套 | 03 |
| 04 | 发现→排序→过滤→注册；On* 条件成对；starter=依赖聚合 | 04 |
| 05 | refresh 十步；★1 图纸定稿/★2 批量造；BFPP vs BPP | 05 |
| 06 | 三级缓存各存什么；为什么必须三级（代理一致性）；构造器循环无解 | 06 |
| 07 | 事件默认同步的坑；AFTER_COMMIT；PropertySource 排序 | 07 |

**追问秒答 4 条**：AOP 换壳在哪一步→02 After 波/05 ★2 前登记；条件注解何时评→★1 装配期一次性；三级缓存为什么不是两级→ObjectFactory 延迟换壳；监听器异常去哪→冒泡回发布者（07）。

## §2 高频坑速查

| # | 坑 | 正解 |
|---|---|---|
| 1 | `this.method()` 丢事务 | 自调用不过代理（03） |
| 2 | 受检异常不回滚 | 默认只回滚运行时——`rollbackFor=Exception` | 
| 3 | 构造器循环指望三级缓存 | 解不了——拆环/@Lazy（06） |
| 4 | 监听器里异常"消失"了 | 默认同步，异常冒泡回发布者（07） |
| 5 | 条件装配"没生效"以为是运行期 | 装配期一次性判定——查类在场/Bean 有无（04） |
| 6 | 自定义 starter 忘退让 | 每个 @Bean 补 @ConditionalOnMissingBean（04） |
| 7 | @PostConstruct 里干重活拖慢启动 | ★2 批量造全在启动期——重活挪事件/异步（02/05） |
| 8 | 以为 prototype 归容器销毁 | 不管——自建自收（02） |

## §3 话术（按拷打口径自建）

> "Spring 我按容器视角讲：核心是 BeanFactory 家族的 ApplicationContext，BeanDefinition 图纸和成品分离。生命周期五段（实例化-填充-Aware-初始化-出厂），AOP 代理在初始化后的 BeanPostProcessor 波次换壳——事务是 TransactionInterceptor 给的，所以自调用/final/吞异常都会失效，受检异常要显式 rollbackFor。Boot 层是自动配置：imports 收集、Sorter 拓扑排序、条件注解装配期一次性判定，OnBean+OnMissingBean 成对保证用户可覆盖。总装是 refresh 十步：BFPP 改图纸在前、单例批量造在后。循环依赖靠三级缓存——关键是第三级 ObjectFactory 让早期引用和出厂代理一致；构造器循环无解只能拆。运行时插线板是事件（默认同步，异常会冒泡）和 Environment（PropertySource 有序覆盖）。"

## §4 范围声明（诚实边界）

本系列=**framework 内核 + Boot 装配半区**；未覆盖：SpringMVC（DispatcherServlet 全流程）、Boot 运行机制全细节（run 方法逐步）、SCA 粘合（三大件已在 Nacos/Seata/Sentinel 独立系列封卷）。

## §5 终检（8 题，过 6 算过关）

1. 图纸/成品分离；容器三层（01）
2. 五段+钩子口诀（02）
3. 失效五连根因一句话（03）
4. starter 里有什么；条件成对（04）
5. ★1/★2 各是哪一步（05）
6. 三级各存什么；二级为什么可能存代理（06）
7. AFTER_COMMIT 给谁用（07/03 交叉）
8. 监听默认同步的坑（07）

## §6 衔接

- Spring 内核系列 8 篇完，README 全挂链接；**L5-6 的内核+Boot 半区闭环**。
- 后续候选：SpringMVC（DispatcherServlet 线）/ Boot 运行机制深化 / 其它（ZK/Kafka/连接池）。
