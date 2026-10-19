---
title: "07-使用进阶：事件与Environment"
series: "Spring 内核系列"
previous: "06-circular-dependency.md"
next: "08-面试收口.md（待写）"
source_refs:
  - "framework 6.2.9 已核：SimpleApplicationEventMulticaster/ApplicationListenerMethodAdapter/StandardEnvironment/ConfigurableEnvironment（类名）"
---

# 07-使用进阶：事件与Environment

## §0 它干了什么（例子先行）

两块"运行时插线板"：

```java
// 事件：组件间不点名的通信
applicationEventPublisher.publishEvent(new OrderCreatedEvent(orderNo));
// 监听（三种姿势）：@EventListener / implements ApplicationListener / @TransactionalEventListener（事务绑定）

// Environment：一切配置的统一入口
@Value("${order.close.delay-level:16}")   // 读的就是 Environment 里的属性源集合
@Profile("prod")                           // 按环境开关 Bean
```

- **它干了什么**：事件=**解耦的广播**（发布者不认识监听者）；Environment=**配置的总接口**（yml/环境变量/命令行/配置中心都在这排队）。
- **本篇回答**：事件监听怎么写、同步还是异步？Environment 的属性源谁优先？Profile 怎么切？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **ApplicationEvent** | 一个事件对象（带 source；Spring 4.2 起可发任意对象当事件） |
| **ApplicationListener/@EventListener** | 订阅者（方法级注解最常用） |
| **Multicaster** | 广播器——把事件派给所有匹配的监听器（`SimpleApplicationEventMulticaster` 已核） |
| **PropertySource** | 一个配置来源（yml 文件/环境变量/…） |
| **Environment** | PropertySource 的有序集合 + Profile 开关（`StandardEnvironment` 起步） |

## §2 工程使用

**事件三问**：

```java
@EventListener                          // 默认：发布线程同步执行（异常会传回发布者！）
public void on(OrderCreatedEvent e) { ... }

@Async @EventListener                  // 异步：丢线程池——发布者不等、异常进线程池
public void onAsync(OrderCreatedEvent e) { ...

@TransactionalEventListener(phase = AFTER_COMMIT)   // 事务绑定：主事务提交后才触发
public void afterTx(OrderCreatedEvent e) { ...       // 典型：本地事务成功才发 MQ/通知
```

**同步 vs 异步的选择**：监听逻辑快且必须感知失败→同步；慢/可丢→异步；**和主事务生死绑定→AFTER_COMMIT**（事件机制与 03 事务的交叉点——本地消息表模式的搭档）。

**Environment 优先级**（常见序，靠后覆盖靠前；Nacos 05 讲的 import 覆盖也在此列）：

```
命令行参数 > SPRING_APPLICATION_JSON > 服务配置（application.yml）> …
（细节以官方文档为准；原则：**显式配置 > 默认**）
```

**Profile 工程用法**：`application-prod.yml` + `@Profile("prod")` Bean/`spring.profiles.active`——**切环境不改代码**；和 Nacos 的 namespace 隔离（06 篇）是同一思想的两个层。

## §3 掌握卡（3 条）

1. **事件默认同步**——发布者线程里跑完监听器，**监听器异常会冒泡回发布者**（最常见的坑）；要解耦就 @Async，要事务语义就 @TransactionalEventListener。
2. **Environment=有序 PropertySource 集合**：同名 key 后面的源覆盖前面的——所有"配置优先级"问题（yml/变量/import）都是排序问题。
3. **生命周期事件可挂载**：ContextRefreshed（05 ★10 之后）/ ApplicationReady（完全就绪——xhs 兜底挂点）/ ContextClosed（优雅停机——@PreDestroy 之外的另一钩子）。

## §4 核心源码锚点（只取两个最核心）

| 类 | 职责 |
|---|---|
| `SimpleApplicationEventMulticaster` | 默认广播器（同步/异步由 taskExecutor 决定） |
| `StandardEnvironment` | 标准 PropertySource 序的起点 |

## §5 边界（一句话翻篇）

- @EventListener 条件表达式/顺序 order → 用时查官方文档。
- EnvironmentPostProcessor（改 PropertySource 的启动级扩展）→ Boot 文档。
- 事件继承与泛型监听 → 少用，知道存在即可。

## §6 Self-Test（3 题）

- Q1：事件默认同步会有什么坑？三种监听姿势各配什么场景？
- Q2："配置优先级"的本质是什么？（PropertySource 排序）
- Q3：本地事务成功后再发 MQ，事件侧用哪个注解？（AFTER_COMMIT）

## §7 衔接

- Boot 层收口；下篇 08 面试收口：七篇回链 + 高频坑 + 终检。
