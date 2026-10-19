# Spring 内核系列

> 语料：**spring-boot-3.2.5**（autoconfigure/starters 全量）+ **spring-framework 6.2.9**（Boot 3.2.5 官方配 6.1.x——本系列源码语料以 6.2.9 为准，行级差异用时注明）
> 钩子债：本 session 多系列已埋（Tracing 04 条件注解/Seata 02 BeanPostProcessor/OpenFeign AOP/各系列自动配置）——本系列集中兑现
> 计划：**L5-6 Spring 内核 🔴 每场必问**

## 一、总表（8 篇：概念 → 原理 → 使用 → 核心源码 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 Spring 内核：容器全景](01-container-overview.md) | **概念** | Bean/BeanFactory/ApplicationContext/生态分层 What | 源码结构 |
| 02 | [原理一：Bean 生命周期](02-bean-lifecycle.md) | **原理** | 实例化→填充→Aware→初始化→销毁；扩展点全家 | framework 源码 |
| 03 | [原理二：AOP 与事务传播](03-aop-transaction.md) | **原理** | 代理模型；@Transactional 语义；失效场景集 | framework 源码 |
| 04 | [使用：自动配置体系](04-auto-configuration.md) | **使用** | 条件注解/装配顺序/starters——钩子全兑现 | boot autoconfigure |
| 05 | [核心源码：refresh 十三步与 BeanFactory](05-refresh-beanfactory.md) | **核心源码** | refresh 流程；BFPP/BeanPostProcessor 管线 | framework 源码 |
| 06 | [深水：循环依赖与三级缓存](06-circular-dependency.md) | **原理深水** | 三级缓存为什么是三级；AOP 代理与循环依赖 | framework 源码 |
| 07 | [使用进阶：事件与 Environment](07-events-environment.md) | **使用** | 事件广播；Environment/Profile/配置优先级 | framework+boot |
| 08 | [面试收口：七篇回链与陷阱](08-interview-wrapup.md) | **面试** | 七篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03
使用：04
源码：05
深水：06
进阶：07
收口：08
```

## 三、前置要求

- 各系列已用到的 Boot 皮毛（条件注解/@Bean/自动配置——Tracing 04、Seata 05、Nacos 05 均讲过用法侧）。
- JDK 基础（反射/动态代理——JVM 系列）。

## 四、掌握分级

- **必须掌握**：Bean 生命周期主干 + 扩展点；AOP 代理与事务传播语义；自动配置条件体系；循环依赖三级缓存。
- **理解思想**：模板方法+回调贯穿（BeanPostProcessor/InitializingBean）；"约定大于配置"的装配实现；代理 vs 注入的事务语义差异。
- **知道即可**：Boot 事件全家/Environment 细节/devtools；Kotlin/Groovy DSL；原生镜像。

## 五、边界

- Spring Cloud 全家桶 → 各适配系列（OpenFeign/Nacos/Gateway 已封卷）。
- Spring WebMVC 内核（DispatcherServlet 全流程）→ 本系列只在 03/07 带到代理与拦截器概念。
- Spring Security/Data → 不属本线。
