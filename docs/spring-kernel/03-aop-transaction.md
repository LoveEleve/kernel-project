---
title: "03-原理二：AOP与事务传播"
series: "Spring 内核系列"
previous: "02-bean-lifecycle.md"
next: "04-自动配置体系.md（待写）"
source_refs:
  - "framework 6.2.9：JdkDynamicAopProxy/CglibAopProxy/DefaultAopProxyFactory（aop/framework 目录已核）"
  - "framework 6.2.9：TransactionInterceptor.java:55（extends TransactionAspectSupport implements MethodInterceptor——已核）"
---

# 03-原理二：AOP与事务传播

## §0 它干了什么（例子先行）

02 说 After 波"换壳"——换的就是 **AOP 代理**：

```java
@Service
public class OrderService {
    @Transactional
    public void create(Order o) { db.insert(o); mq.send(o); }   // 调用时实际走的是代理
}

// 外部调用：
orderService.create(order);   // ← 拦到的是代理：开事务 → 真方法 → 提交/回滚
```

- **它干了什么**：事务不是 DB 给的，是**拦截器给的**——方法被套了一层"开关事务"的壳，壳在 02 的 After 波装上。
- **本篇回答**：AOP 这套词各指什么？代理怎么选型？`this.method()` 为什么丢事务？传播语义怎么配？

## §1 AOP 概念铺垫（What，先词后理）

| 词 | 一句话 | 事务场景的对应物 |
|---|---|---|
| **切面 Aspect** | 一段"横切"多个方法的逻辑（一个类） | 事务切面 |
| **连接点 JoinPoint** | 程序执行中可被拦截的点（Spring 里=方法调用） | create() 的每次调用 |
| **通知 Advice** | 在连接点上执行的动作（before/after/around…） | 开事务/提交/回滚 |
| **切点 PointCut** | 挑"哪些连接点要拦"的表达式 | `@Transactional` 标注的方法集合 |

**一句话**：切面=把散落各方法的同款逻辑（事务/日志/耗时）抽出来，按切点挂上去——**业务只留业务**。

## §2 工程使用：两个注解的常用配法

**事务（90% 场景就这三种组合）**：

```java
@Transactional                                  // 默认：REQUIRED + 只回滚运行时异常
@Transactional(rollbackFor = Exception.class)   // 受检异常也回滚（最常用修正）
@Transactional(propagation = Propagation.REQUIRES_NEW,
               timeout = 3, readOnly = true)    // 独立事务+超时+只读（记日志/报表场景）
```

**自定义切面（@Aspect 工程样例）**：

```java
@Aspect @Component
public class CostAspect {
    @Around("execution(* com.myxhs..service..*.*(..))")   // 切点：service 包所有方法
    public Object cost(ProceedingJoinPoint pjp) throws Throwable {
        long t = System.nanoTime();
        try { return pjp.proceed(); }
        finally { log.info("{} cost {}ms", pjp.getSignature(), (System.nanoTime()-t)/1_000_000);
                  // 想打点？这正是 Micrometer 04 @Timed 的手工版
        }
    }
}
```

**选型常识**：框架级扩展（事务/@Timed）用框架自带切面；业务横切（审计/耗时打印）写 @Aspect——**能用拦截器/Filter 解决的别上 AOP**（粒度和顺序更直白）。

## §3 掌握卡（4 条）

1. **代理选型**（DefaultAopProxyFactory）：有接口优先 JDK 动态代理；无接口/强制 CGLIB（`proxyTargetClass=true`，Boot 默认开）用 CGLIB——**换壳发生在 02 的 After 波**（AutoProxyCreator）。
2. **事务=拦截器**：`@Transactional` 不是魔法——`TransactionInterceptor` 读注解/规则（`RuleBasedTransactionAttribute`）得到"事务属性"，在方法前后 begin/commit/rollback；**没被代理=没有拦截器=没有事务**。
3. **失效场景集**（高频考，全部同一根因"没进拦截器"或"属性不匹配"）：
   - `this.method()` 自调用（代理没出场）
   - `private/final/static` 方法（JDK 代理接口管不到；CGLIB 难增强 final）
   - 异常被 catch 吞掉（拦截器看不到异常——默认只回滚 RuntimeException/Error）
   - rollbackFor 不含受检异常（受检异常默认**不**回滚）
   - 类没被代理（非 Spring 管理/条件装配没生效）
4. **传播语义七件套**：`REQUIRED`（默认：有就 join，无就开新的）/ `REQUIRES_NEW`（挂起当前，开独立新事务——**两层事务互不影响**）/ `NESTED`（同事务内保存点——回滚到内层点不伤外层）/ `SUPPORTS`/`NOT_SUPPORTED`/`MANDATORY`/`NEVER`——记法：**默认加入；NEW 独立；NESTED 可局部退**。

## §4 传播语义对照表（06 深水会用）

| 语义 | 当前有事务时 | 无事务时 | 典型场景 |
|---|---|---|---|
| REQUIRED | join | 新开 | 绝大多数业务 |
| REQUIRES_NEW | **挂起旧的，开新的** | 新开 | 记操作日志：日志成败不能连坐主事务 |
| NESTED | 保存点 | 新开 | 批量导入：单条失败回滚到条边界 |

**挂起≠回滚**：REQUIRES_NEW 的"挂起"只是暂停外层事务的连接占用，外层稍后恢复继续——两个事务物理隔离（不同连接/不同提交点）。

## §5 自调用失效的完整链条（把 01/02/03 串起来）

```
orderService.create()  → 容器里的是代理（02 After 波产物）
  → TransactionInterceptor：begin → 真 create() → commit
create() 内部 this.audit()  → 调的是裸 this，不是代理 → audit 的 @Transactional 没人拦
```

**修法**：注入自身代理 / `AopContext.currentProxy()` / 拆 Bean——本质都是"**从代理走**"。

## §6 边界（一句话翻篇）

- AspectJ 切点表达式全家/编织模式 → 用时查官方文档。
- 事务同步（TransactionSynchronization）/编程式 TransactionTemplate → 用时查。
- 隔离级别与 DB 对应 → MySQL 系列（未开）/通用知识。

## §7 Self-Test（4 题）

- Q1：JDK 代理和 CGLIB 各在什么条件启用？Boot 的 proxyTargetClass 默认？
- Q2：失效场景五连各是什么根因？
- Q3：REQUIRED/REQUIRES_NEW/NESTED 一句话各是什么？"挂起"挂的是什么？
- Q4：@Transactional 默认对哪些异常回滚？受检异常呢？

## §8 衔接

- AOP/事务收口；下篇 04 使用：Boot 自动配置体系——条件注解怎么判、装配顺序为什么重要（各系列钩子大兑现）。
