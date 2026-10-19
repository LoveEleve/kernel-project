---
title: "05-核心源码：refresh十三步与BeanFactory"
series: "Spring 内核系列"
previous: "04-auto-configuration.md"
next: "06-循环依赖与三级缓存.md（待写）"
source_refs:
  - "framework 6.2.9 AbstractApplicationContext.refresh():587（十三步调用序逐行已核：prepareRefresh→…→finishRefresh）"
---

# 05-核心源码：refresh十三步与BeanFactory

## §0 它干了什么（例子先行）

`SpringApplication.run()` 的心脏是 **`refresh()`（:587）**——容器的"总装车间"，十三步按序走完，应用才算"活了"：

```
prepareRefresh → obtainFreshBeanFactory（读图纸：扫描/加载 BeanDefinition）
  → prepareBeanFactory → invokeBeanFactoryPostProcessors（★改图纸的最后机会）
  → registerBeanPostProcessors（登记换壳工——02 After 波的执行者们）
  → initApplicationEventMulticaster → onRefresh（Boot 在此起 Tomcat 等容器）
  → registerListeners → finishBeanFactoryInitialization（★单例预实例化——真正造 Bean）
  → finishRefresh（发布 Ready 事件——xhs 的 ApplicationReadyEvent 兜底就挂这）
```

- **它干了什么**：refresh=**图纸总装 → 换壳工登记 → 批量造货 → 开门营业**的固定剧本。
- **本篇回答**：十三步里哪几步最核心？BFPP 和 BPP 谁改图纸谁改成品？非懒加载单例何时造？

## §1 概念铺垫（What：两类 PostProcessor 与两个"最核心时刻"）

| 词 | 一句话 | 作用对象 |
|---|---|---|
| **BeanFactoryPostProcessor（BFPP）** | 在**实例化之前**修改 BeanDefinition（图纸） | 图纸 |
| **BeanPostProcessor（BPP）** | 在每个 Bean 的**初始化前后**插手（成品） | 成品 |
| ★时刻1 | `invokeBeanFactoryPostProcessors`：**配置类解析+条件注解评估就在这步**（04 的自动配置被"读进来"） | 图纸定稿 |
| ★时刻2 | `finishBeanFactoryInitialization`：非懒加载单例**批量造**——02 的五段流水线被批量执行 | 成品出厂 |

**和 02/04 的咬合**：04 说"条件判定在装配期"——那个装配期就是★时刻1；02 说"After 波换壳"——换壳工（BPP）在★时刻2 之前已登记完毕（registerBeanPostProcessors）。

## §2 工程使用：什么时候写 BFPP/BPP

| 场景 | 用谁 | 例 |
|---|---|---|
| 读取/修改所有 BeanDefinition | BFPP | 扫描期统一改 scope/primary（框架级） |
| 给所有 Bean 统一织入 | BPP | 自定义注解处理器（框架级） |
| 业务横切（事务/耗时/审计） | **@Aspect / 拦截器**（03 §2） | 业务别动 Processor |
| 依赖"容器完全就绪" | ApplicationReadyEvent（04 提过的兜底挂点） | xhs 30s 真空期检查 |

**口诀升级版**（02 §2 的补全）：**图纸阶段找 BFPP，成品阶段找 BPP，业务横切找 AOP，就绪之后挂事件**。

## §3 十三步速查表（核心源码，行号已核）

| # | 步骤 | 一句话职责 |
|---|---|---|
| 1 | prepareRefresh | 环境预检、记录启动时间等 |
| 2 | obtainFreshBeanFactory | **读图纸**（refreshBeanFactory+加载 Definition） |
| 3 | prepareBeanFactory | 给工厂装标准件（ClassLoader/标准 Bean） |
| 4 | invokeBeanFactoryPostProcessors | **★图纸定稿**（含自动配置类解析） |
| 5 | registerBeanPostProcessors | 登记换壳工（BPP，按优先级） |
| 6 | initApplicationEventMulticaster | 事件广播器就位 |
| 7 | onRefresh | **Boot 的钩子**：创建内嵌 Web 容器等 |
| 8 | registerListeners | 注册监听器 |
| 9 | finishBeanFactoryInitialization | **★批量造单例** |
| 10 | finishRefresh | 发布 ContextRefreshed/Ready 事件 |

（官方叙述常数"十三步"——上表合并了少数样板步；**面试答 10 个有名步骤足够**，顺序一字不能错。）

## §4 边界（一句话翻篇）

- refresh try-catch 内的异常恢复/destroyBeans → 源码 :587 方法体用时查。
- obtainFreshBeanFactory 在 Boot 下不做 XML 换代（GenericApplicationContext 一路）→ 知道即可。
- onRefresh 的 Boot 各容器实现 → 用时查。

## §5 Self-Test（3 题）

- Q1：BFPP 和 BPP 各改什么？登记顺序为什么是"先 BFPP 后 BPP"？
- Q2：条件注解评估和单例批量造分别在哪一步？
- Q3：想在"容器完全就绪后"跑一段逻辑，挂哪？（Ready 事件，xhs 案例）

## §6 衔接

- 总装流程收口；下篇 06 深水：循环依赖三级缓存——★时刻2 批量造时的经典难题。
