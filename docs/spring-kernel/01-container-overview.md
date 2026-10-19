---
title: "01-什么是Spring内核：容器全景"
series: "Spring 内核系列"
previous: "README.md"
next: "02-Bean生命周期.md（待写）"
source_refs:
  - "framework 6.2.9 模块结构（spring-beans/factory 包已核：BeanFactory/Hierarchical/Listable）"
  - "boot-3.2.5 模块结构（autoconfigure/starters 已核）"
---

# 01-什么是Spring内核：容器全景

## §0 它干了什么（例子先行）

```java
@Component
public class OrderService {
    private final PaymentClient payment;   // 没有 new，对象从哪来？
    public OrderService(PaymentClient payment) { this.payment = payment; }
}
```

`OrderService` 从"一个 class"变成"能用的对象"，中间发生的事：

```
容器读到类 → 记进"菜单"（BeanDefinition：类名/构造参数/作用域/是否懒加载…）
  → 到需要时：反射实例化 → 构造器注入 payment → 套上 AOP 代理（若需要）
  → 放进容器"仓库"（ singletonObjects 一类的缓存）
  → 别处 @Autowired 拿走的就是仓库里那个成品
```

- **它干了什么**：Spring 内核=**一个管对象"从类到成品"全生命周期的容器**——你声明，它造、它配、它管、它收。
- **本篇回答**：容器家族谁管什么？Bean/BeanDefinition/Registry 这些词各指什么？内核和 Boot 是什么关系？

## §1 掌握卡（4 条）

1. **容器家族三层**（framework 源码 factory 包实锤）：**BeanFactory**（最底层接口——"给我一个 Bean"）→ **Listable/Hierarchical**（可枚举/分层）→ **ApplicationContext**（完整应用上下文：**额外**带国际化/事件发布/Environment/资源加载——日常用的是它，但造 Bean 的还是底下的 BeanFactory 家族）。
2. **三个核心名词**：**Bean**（容器管理的对象）/ **BeanDefinition**（Bean 的"图纸"——造它之前的一切描述）/ **BeanFactory**（造+取的工厂）——**先有图纸，后有成品；成品进缓存，图纸可查**。
3. **内核 vs Boot**：内核（framework：beans/context/aop）管**造对象**；Boot 管**怎么快速装起一个能用的应用**（自动配置/starters/属性绑定——04 主场）。**Boot 内核仍是 ApplicationContext**。
4. **生态分层一张图**：`spring-core/beans`（基础+容器）→ `spring-context`（ApplicationContext/事件）→ `spring-aop`（切面）→ `spring-tx`（事务）→ `spring-boot`（装配加速器）→ 各 starter——本系列 02/03/05/06 在内核层，04/07 在 Boot 层。

## §2 为什么"图纸"要独立（概念的为什么一句话）

BeanDefinition 独立于对象存在的意义：**注册可以发生在实例化之前**——批量读类路径（扫描）时只收集图纸不造对象；单例缓存保证"一个图纸最终只对应一个成品"；循环依赖（06）与懒加载全靠"图纸在、成品可以晚点造"这套时序。**图纸/成品分离是 Spring 容器的骨架级设计**。

## §3 生态分层与本系列地图（和 01 概念咬合）

```
使用视角：@Component/@Autowired/@Transactional/@Bean/@ConditionalOn…
内核视角：02 生命周期（成品怎么造）→ 03 AOP/事务（成品怎么被包/事务怎么传播）
          → 05 refresh（图纸总装流程）→ 06 循环依赖（图纸成品时序的经典难题）
Boot 视角：04 自动配置（图纸从哪些 jar 来）→ 07 事件与 Environment（运行时的插线板）
```

## §4 边界（一句话翻篇）

- ApplicationContext 接口全方法 → 源码用时查。
- BeanDefinition 属性全表 → 02 生命周期里用到什么讲什么。
- WebMVC/Security/Data → README 边界已声明。

## §5 Self-Test（3 题）

- Q1：BeanFactory 和 ApplicationContext 谁底层？后者多哪四样能力？
- Q2：Bean 和 BeanDefinition 各是什么？哪个先有？
- Q3：内核和 Boot 的分工一句话；本系列哪几篇在 Boot 层？

## §6 衔接

- 概念层收口；下篇 02 Bean 生命周期：一张图纸到成品的完整流水线 + 五个扩展点为什么都在那几个时刻。
