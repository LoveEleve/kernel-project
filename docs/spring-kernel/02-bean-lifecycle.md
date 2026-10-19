---
title: "02-原理一：Bean生命周期"
series: "Spring 内核系列"
previous: "01-container-overview.md"
next: "03-AOP与事务传播.md（待写）"
source_refs:
  - "framework 6.2.9 AbstractAutowireCapableBeanFactory（doCreateBean/populateBean/initializeBean/Before/After——方法名已核，行级在 05）"
---

# 02-原理一：Bean生命周期

## §0 它干了什么（例子先行）

一个 Bean 从图纸到销毁，走完这条流水线（主干五段）：

```
getBean → doCreateBean
  ① 实例化（反射调构造器——图纸上的构造参数在此用掉）
  ② 属性填充 populateBean（@Autowired/@Value 在此注入）
  ③ Aware 回调（想拿容器元老的接口——见 §1）
  ④ 初始化 initializeBean
       → BeanPostProcessor.postProcessBeforeInitialization（@PostConstruct 在这波）
       → invokeInitMethods（InitializingBean.afterPropertiesSet / 自定义 init-method）
       → BeanPostProcessor.postProcessAfterInitialization（**AOP 代理通常在这换壳**）
  ⑤ 出厂：进单例缓存，交给使用者
容器关闭 → 销毁（@PreDestroy/DisposableBean/destroy-method——逆序）
```

- **它干了什么**：五段主干 + 每段留了扩展点钩子——**生命周期=模板方法，扩展点=钩子位置**。
- **本篇回答**：五段各干什么？工程上该用哪个钩子？为什么 AOP 换壳在 After？

## §1 概念铺垫（What：五段词 + 扩展点两族）

| 阶段 | 词 | 一句话 |
|---|---|---|
| ① | 实例化 | 反射 new 出裸对象 |
| ② | 属性填充 | 依赖注入发生地 |
| ③ | Aware | "想知道容器信息"的回调接口族 |
| ④ | 初始化 | 自定义逻辑 + **后处理器换壳位** |
| ⑤ | 缓存出厂 | 单例进仓库 |

**扩展点两族**（先分清再谈用）：

- **生命周期回调**（针对单个 Bean）：`@PostConstruct`/`init-method`（初始化后）+ `@PreDestroy`/`destroy-method`（销毁时）+ Aware 三兄弟（填充后）——**业务侧最常用的一族**。
- **后处理器**（针对所有 Bean 的流水线插件）：`BeanPostProcessor`（Before/After 两波——AOP 换壳在此）/ `BeanFactoryPostProcessor`（更早，改图纸 BeanDefinition——05）——**框架侧的一族**。

## §2 工程使用：钩子怎么选

| 场景 | 用哪个 | 为什么 |
|---|---|---|
| 启动时初始化缓存/连接 | **`@PostConstruct`**（首选） | 注解直观、无需配置 |
| 第三方类没法加注解 | `init-method`（@Bean(initMethod=…)） | 不侵入类 |
| 基类统一收口 | `InitializingBean.afterPropertiesSet` | 继承体系强制契约 |
| 关闭时释放连接/线程池 | **`@PreDestroy`** | 与 @PostConstruct 成对 |
| 拿 Bean 名/工厂/上下文 | Aware 三选一 | 按需——能构造器注入就别用 Aware |
| 给所有 Bean 统一织入逻辑 | 自定义 BeanPostProcessor（框架级） | 业务别乱用——03 的 AOP 更适合业务横切 |

**选型口诀**：**业务初始化 `@PostConstruct`，释放 `@PreDestroy`；框架增强才动 Processor**。

## §3 掌握卡（3 条）

1. **五段主干**：实例化→填充→Aware→初始化→缓存出厂；销毁注册在出厂前、容器关闭逆序执行（源码方法名：doCreateBean/populateBean/initializeBean——行级归 05）。
2. **回调顺序固定**：`@PostConstruct`（Before 波）→ `afterPropertiesSet`/`init-method` → **After 波换壳**——顺序是规范不是巧合（03 的代理必须最后出场）。
3. **销毁逆序**：依赖者先销毁——先关订单服务再关它用的数据源。

## §4 为什么扩展点长在这些位置（推导）

**朴素答案**：生命周期写死在一个大方法里，要扩展改源码？

**But**：框架扩展五花八门——注解处理、校验、代理、缓存包装……

**Therefore**：**模板方法+钩子**：主干步骤固定，每步之间开钩子——扩展=实现钩子接口，主干永不改。**和 Sentinel 的 Slot 链、Micrometer 的模板方法同一味药**：稳定骨架+可插拔行为。

## §5 单例的"出厂即缓存"（和 01 呼应）

After 钩子的产物（可能是代理）→ 放进 **singletonObjects**（成品缓存）——之后所有 getBean 都拿同一个；**图纸（BeanDefinition）仍可查**（01 §2 分离兑现）。原型 Bean（prototype：每次 getBean 都新造）跳过缓存——销毁也不归容器管（对 prototype，钩子只到初始化）。

## §6 Self-Test（4 题）

- Q1：五段主干按顺序说；@PostConstruct 和 AOP 换壳各在哪波？
- Q2：初始化三选一（注解/init-method/afterPropertiesSet）各配什么场景？
- Q3：业务代码要拿 ApplicationContext，首选构造器注入还是 Aware？（能注入就注入）
- Q4：销毁为什么逆序？prototype 归容器销毁吗？

## §7 衔接

- 生命周期收口；下篇 03 AOP 与事务传播：换壳换的什么壳、@Transactional 工程配法与失效清单。
