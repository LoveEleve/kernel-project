# D4: @Timed/@Counted——AOP 自动指标采集

> **阅读顺序**: P1 重要——理解 Micrometer 如何通过 Spring AOP 声明式采集方法级指标
> **源码版本**: micrometer-core **v1.12.5**（tag；运行时对齐 Boot 3.2.5，与 xhs-project 一致；1.13+ 新特性以注记标出）
> **核心文件**（1.12.5）:
> - `@Timed.java` (84 行)——声明性注解（value/extraTags/longTask/percentiles/histogram/description；**无** serviceLevelObjectives——那是 1.14+）
> - `@Counted.java`——Counter 注解（recordFailuresOnly + extraTags）
> - `TimedAspect.java` (334 行)——@Around 切面，双切点 + longTask 分支 + CompletionStage 处理
> - `CountedAspect.java` (250 行)——@Around 切面，镜像结构 + result/failure tag
> - `MeterTagAnnotationHandler.java` (78 行)——@MeterTag 三级优先级解析（1.12.5 无独立 MeterTagSupport 类）
> - `AnnotationHandler.java` (154 行, micrometer-commons)——注解合并基类（`addAnnotatedMethodResult` 为 1.15+，本基线无）

---

## 一、@Timed 注解——完整参数表（1.12.5）

```java
// @Timed.java（84 行）
@Repeatable(TimedSet.class)                   // ← 支持多个 @Timed 在同一个方法上
@Inherited                                     // ← 子类继承父类注解
@Target({ANNOTATION_TYPE, TYPE, METHOD})      // ← 可用在类/方法/元注解上
public @interface Timed {
    String value() default "";                 // ← metric name（空 → "method.timed"）
    String[] extraTags() default {};           // ← 静态 Tag
    boolean longTask() default false;          // ← 是否 LongTaskTimer
    double[] percentiles() default {};         // ← 客户端百分位（如 {0.5, 0.95, 0.99}）
    boolean histogram() default false;         // ← 是否服务端预分桶直方图
    String description() default "";
}
```

**两个版本要点**：
1. **`serviceLevelObjectives` 属性是 1.14+ 新增**——1.12.5 的 @Timed **没有**它（本 lab 基线）；SLO 桶在 1.12.5 只能用编程式 `Timer.builder().serviceLevelObjectives(...)`（见 A10 实现）；
2. @Timed **没有** `percentilesMode` 参数——这是常见的记忆混淆。

---

## 二、percentiles 的真相——TimeWindowPercentileHistogram

**D4 原版说 "percentiles 存储所有 sample——排序计算 P50/P95"——那是错的。**

真实实现：`Timer` 的 percentiles 使用 `TimeWindowPercentileHistogram`——**预分桶**，不是存全量样本：

```java
// 预分桶 = 固定大小桶数组（约 3KB）
// 每个桶 [min, max) 范围，桶内只存储计数
// P99 = 遍历桶直到累计计数 ≥ 99% 总数

// 内存模型（与 QPS 无关）：
TimeWindowPercentileHistogram = fixed-size bucket array (~3KB)
// vs 全量样本（D4 原版的错误模型）：
// 10K QPS × 60s = 600,000 样本 × 24 bytes = ~15MB  ← 这是虚构的！
```

| 参数 | 计算位置 | 内存 | 适用 QPS |
|------|---------|------|:--:|
| `percentiles={0.5,0.95}` | Micrometer 客户端 TimeWindowPercentileHistogram | ~3KB（固定） | 不限 |
| `histogram=true` | Prometheus 服务端 | ~1KB（固定 30 桶） | 不限 |

**为什么需要两个独立参数？** `percentiles` 提供任意精确百分位（P50/P95/P99.9），但需要客户端计算。`histogram` 只提供预分桶（[10ms, 25ms, 50ms, 100ms...]）——但服务端 Prometheus 用 `histogram_quantile(0.99, ...)` 可以近似计算。两者是互补的——`histogram` 给 Prometheus 计算分位数，`percentiles` 给 Grafana 显示精确 P99。

---

## 三、TimedAspect——双切面的完整实现

### 3.1 两个切点的精确表达式（1.12.5）

```java
// TimedAspect.java L163-L178：类级 @Timed
@Around("@within(io.micrometer.core.annotation.Timed)")
public Object timedClass(ProceedingJoinPoint pjp) {
    Method method = ((MethodSignature) pjp.getSignature()).getMethod();
    Class<?> declaringClass = method.getDeclaringClass();
    if (!declaringClass.isAnnotationPresent(Timed.class)) {
        declaringClass = pjp.getTarget().getClass();      // ← 回退：从 target class 取注解
    }
    Timed timed = declaringClass.getAnnotation(Timed.class);
    return perform(pjp, timed, method);
}

// L180-L195：方法级 @Timed
@Around("execution (@io.micrometer.core.annotation.Timed * *.*(..))")
public Object timedMethod(ProceedingJoinPoint pjp) {
    Method method = ((MethodSignature) pjp.getSignature()).getMethod();
    Timed timed = method.getAnnotation(Timed.class);
    if (timed == null) {                                   // ← 接口方法注解回退到实现类
        method = pjp.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
        timed = method.getAnnotation(Timed.class);
    }
    return perform(pjp, timed, method);
}
```

**版本差异（重要）**：1.12.5 的类级切点是纯 `@within(...)`——**没有** 1.17 新增的 `&& !@annotation(...)` 排除。这意味着**类级 @Timed + 方法级 @Timed 同时命中时两条 advice 都会执行**（各自按自己的注解记录）。1.17 才通过排除式解决该重叠。

### 3.2 perform()——分发到 Timer 或 LongTaskTimer

```java
// TimedAspect.java L197-L207
private Object perform(ProceedingJoinPoint pjp, Timed timed, Method method) {
    final String metricName = timed.value().isEmpty() ? DEFAULT_METRIC_NAME : timed.value(); // "method.timed"
    final boolean stopWhenCompleted = CompletionStage.class.isAssignableFrom(method.getReturnType());

    if (!timed.longTask()) {
        return processWithTimer(pjp, timed, metricName, stopWhenCompleted);
    } else {
        return processWithLongTaskTimer(pjp, timed, metricName, stopWhenCompleted);
    }
}
```

**关键**：默认 metric name 是 `"method.timed"`（常量 `DEFAULT_METRIC_NAME`，L88）——不是 `className.simpleName + "." + methodName`。class/method 信息通过 Tag 传递（`tagsBasedOnJoinPoint`），不编码在 name 中。

---

## 四、processWithTimer()——同步/异步双分支

### 4.1 异步分支（CompletionStage）

```java
// TimedAspect.java L209-L227
Timer.Sample sample = Timer.start(registry);

if (stopWhenCompleted) {
    try {
        return ((CompletionStage<?>) pjp.proceed()).whenComplete(
                (result, throwable) -> record(pjp, timed, metricName, sample, getExceptionTag(throwable)));
    } catch (Exception ex) {
        record(pjp, timed, metricName, sample, ex.getClass().getSimpleName());
        throw ex;
    }
}
```

### 4.2 同步分支（try/catch/finally）

```java
// L229-L242
String exceptionClass = DEFAULT_EXCEPTION_TAG_VALUE;   // "none"
try {
    return pjp.proceed();
} catch (Exception ex) {
    exceptionClass = ex.getClass().getSimpleName();     // ← catch 只改 tag，不 record
    throw ex;
} finally {
    record(pjp, timed, metricName, sample, exceptionClass);  // ← finally 统一 record
}
```

**修正**：D4 原版把同步分支写成了"catch 内 record"——实际是 `try/catch/finally`，finally 里统一 record（异常路径不会漏记）。

### 4.3 record() 与 recordBuilder()——Tag 组装顺序

```java
// L245-L252
private void record(ProceedingJoinPoint pjp, Timed timed, String metricName, Timer.Sample sample,
        String exceptionClass) {
    try {
        sample.stop(recordBuilder(pjp, timed, metricName, exceptionClass).register(registry));
    } catch (Exception e) {
        // ignoring on purpose    ← 指标失败不影响业务
    }
}

// L248-L261
Timer.Builder builder = Timer.builder(metricName)
    .description(timed.description().isEmpty() ? null : timed.description())
    .tags(timed.extraTags())                     // ← 1: 注解静态 extraTags
    .tags(EXCEPTION_TAG, exceptionClass)         // ← 2: exception tag（常量 "exception"）
    .tags(tagsBasedOnJoinPoint.apply(pjp))       // ← 3: class=declaringType, method=name
    .publishPercentileHistogram(timed.histogram())
    .publishPercentiles(timed.percentiles().length == 0 ? null : timed.percentiles());
if (meterTagAnnotationHandler != null) {
    meterTagAnnotationHandler.addAnnotatedParameters(builder, pjp);   // ← 4: 参数 @MeterTag
}
```

Tag 合并顺序：静态 extraTags → exception → class/method → @MeterTag 动态（最后加所以优先级最高）。

**版本差异**：1.12.5 的 recordBuilder **没有** `serviceLevelObjectives(...)`（1.14+）也**没有** `addAnnotatedMethodResult(...)`（1.15+，方法返回值 @MeterTag）。

### 4.4 getExceptionTag()——只取**一层** cause

```java
// L263-L274
private String getExceptionTag(Throwable throwable) {
    if (throwable == null) return DEFAULT_EXCEPTION_TAG_VALUE;     // "none"
    if (throwable.getCause() == null) return throwable.getClass().getSimpleName();
    return throwable.getCause().getClass().getSimpleName();        // ← 只取直接 cause，不递归
}
```

**版本差异**：1.12.5 只取**直接 cause**（一层）；新版（1.17）才循环到最深层根因。对 `CompletionException(SQLException)` 两者一致（取 SQLException），但多层包装时结果不同。

---

## 五、longTask 分支——LongTaskTimer 的 Optional 降级

```java
// L276-L303（节选）
Optional<LongTaskTimer.Sample> sample = buildLongTaskTimer(pjp, timed, metricName).map(LongTaskTimer::start);
...
try {
    return pjp.proceed();
} finally {
    sample.ifPresent(this::stopTimer);     // ← 构建失败 → 无 Sample → 静默跳过
}

// L313-L332
private Optional<LongTaskTimer> buildLongTaskTimer(...) {
    try {
        return Optional.of(LongTaskTimer.builder(metricName)
            .description(...).tags(timed.extraTags()).tags(tagsBasedOnJoinPoint.apply(pjp))
            .register(registry));
    } catch (Exception e) {
        return Optional.empty();           // ← 任何异常 → 降级，不打扰业务
    }
}
```

`buildLongTaskTimer()` 返回 `Optional<LongTaskTimer>`——构建失败（如 Registry 不支持）→ `Optional.empty()` → 降级为**纯跳过**（不记录指标、不抛异常）。这是"安全创建"设计：**指标系统故障不能拖垮业务方法**。

---

## 六、@Counted 与 CountedAspect——镜像结构

```java
// CountedAspect.java L186-L218（节选）
@Around(value = "@annotation(counted)", argNames = "pjp,counted")
public Object interceptAndRecord(ProceedingJoinPoint pjp, Counted counted) {
    ...
    if (stopWhenCompleted) {   // CompletionStage 异步分支
        return ((CompletionStage<?>) pjp.proceed())
            .whenComplete((result, throwable) -> recordCompletionResult(pjp, counted, throwable));
    }
    // 同步：try/catch/finally——同 TimedAspect 结构
}

// L220-L231
private void recordCompletionResult(ProceedingJoinPoint pjp, Counted counted, Throwable throwable) {
    if (throwable != null) {
        String exceptionTagValue = throwable.getCause() == null ? throwable.getClass().getSimpleName()
                : throwable.getCause().getClass().getSimpleName();      // ← 同样只取一层
        record(pjp, counted, exceptionTagValue, RESULT_TAG_FAILURE_VALUE);
    } else if (!counted.recordFailuresOnly()) {
        record(pjp, counted, DEFAULT_EXCEPTION_TAG_VALUE, RESULT_TAG_SUCCESS_VALUE);
    }
}

// L233-L240：record 组装
counter(pjp, counted).tag(EXCEPTION_TAG, exception)
    .tag(RESULT_TAG, result)          // result=success/failure
    .tags(counted.extraTags())
    .register(registry).increment();
```

**语义**：成功 `result=success, exception=none`；失败 `result=failure, exception=异常类名`。`recordFailuresOnly=true` 时只记录失败。**@Counted 不记录返回值**——只记异常与否。

---

## 七、@MeterTag——三级优先级（1.12.5 实现位置）

```java
// @MeterTag.java（71 行）
@Repeatable(MeterTags.class)
@Target({PARAMETER, METHOD})
public @interface MeterTag {
    String value() default "";                       // ← tag value（字面量）
    String key() default "";                         // ← tag key
    String expression() default "";                  // ← SpEL 表达式
    Class<? extends ValueResolver> resolver()        // ← 自定义 resolver（最高优先级）
        default NoOpValueResolver.class;
}
```

**三级优先级**（`MeterTagAnnotationHandler.resolveTagValue` L60-L75——1.12.5 无独立 MeterTagSupport 类）：

```java
static String resolveTagValue(MeterTag annotation, Object argument, ...) {
    String value = null;
    if (annotation.resolver() != NoOpValueResolver.class) {              // 1. resolver 最高
        value = resolverProvider.apply(annotation.resolver()).resolve(argument);
    } else if (StringUtils.isNotBlank(annotation.expression())) {        // 2. SpEL
        value = expressionResolverProvider.apply(ValueExpressionResolver.class)
            .resolve(annotation.expression(), argument);
    } else if (argument != null) {                                       // 3. toString
        value = argument.toString();
    }
    return value == null ? "" : value;
}
```

**关键**：SpEL 引擎**不是 micrometer 内置的**——需要外部注册 `ValueExpressionResolver`。Spring Boot **不为 MeterTag 自动提供 SpEL resolver**。用户需自建 `MeterTagAnnotationHandler` Bean 并注入 `ValueExpressionResolver`（Boot 的 `MetricsAspectsAutoConfiguration` 通过 `ObjectProvider` 注入——没有就静默忽略 @MeterTag）。

**版本注记**：方法返回值上的 @MeterTag（`@MeterTag(key="result", expression="#result.xxx")` 标在方法上）是 **1.15+** 能力——1.12.5 的 `AnnotationHandler` 只有 `addAnnotatedParameters`（参数级），**不支持方法返回值**。

---

## 八、@Observed vs @Timed——两条不同的处理路径

| 维度 | @Observed | @Timed |
|------|------|------|
| **Aspect 类** | `ObservedAspect`（micrometer-observation） | `TimedAspect`（micrometer-core） |
| **注册条件（Boot 3.2.5）** | `ObservedAspectConfiguration`（`@ConditionalOnClass(Advice)`）——**默认注册** | `MetricsAspectsAutoConfiguration`——需 `management.observations.annotations.enabled=true`（**默认 false！**） |
| **处理方式** | ObservationRegistry → Handler 链 | 直接创建 Timer → MeterRegistry |
| **产物** | Metrics + Tracing + Logging（三合一） | 仅 Metrics（Timer） |
| **直方图控制** | 依赖 ObservationConvention | @Timed 注解直接控制（percentiles/histogram） |

**Boot 3.2.5 关键差异（生产坑）**：@Timed/@Counted 的切面**默认不注册**——`MetricsAspectsAutoConfiguration` 的条件是 `management.observations.annotations.enabled=true`（元数据 `defaultValue: false`），不设置则注解静默无效！而 @Observed 的 `ObservedAspect` 无此门槛。本 lab 已在 `application.yml` 显式开启（否则 A07/A08 无指标）。

---

## 九、PauseDetector 的真相——@Timed 不可单独指定

`@Timed` 注解没有 `pauseDetector` 参数，`TimedAspect.recordBuilder()` 也没设置它。Timer 创建时走 `registry.config().pauseDetector()` 默认值（`NoPauseDetector.INSTANCE`）。需要 pauseDetector 只能在 MeterRegistry 级别配置（`registry.config().pauseDetector(new ClockDriftPauseDetector())`）——不能按方法粒度指定。

---

## 十、Spring Boot 3.2.5 MetricsAspectsAutoConfiguration

```java
// MetricsAspectsAutoConfiguration.java（83 行，Boot 3.2.5）
@AutoConfiguration(after = {MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class})
@ConditionalOnClass({MeterRegistry.class, Advice.class})
@Conditional(ObservationAnnotationsEnabledCondition.class)   // ← 3.2.5 是自定义 AnyNestedCondition
@ConditionalOnBean(MeterRegistry.class)
public class MetricsAspectsAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    CountedAspect countedAspect(MeterRegistry registry) { return new CountedAspect(registry); }

    @Bean @ConditionalOnMissingBean
    TimedAspect timedAspect(MeterRegistry registry,
            ObjectProvider<MeterTagAnnotationHandler> meterTagAnnotationHandler) {
        TimedAspect timedAspect = new TimedAspect(registry);
        meterTagAnnotationHandler.ifAvailable(timedAspect::setMeterTagAnnotationHandler);
        return timedAspect;
    }

    // ObservationAnnotationsEnabledCondition = AnyNestedCondition（任一满足）：
    //   micrometer.observations.annotations.enabled=true（旧名，已 deprecated）
    //   management.observations.annotations.enabled=true（新名，默认 false）
}
```

**开关**：`management.observations.annotations.enabled=true`——**Boot 3.2.5 默认 false**（元数据 `defaultValue: false`），必须显式开启（Boot 3.4+ 换用 `@ConditionalOnBooleanProperty`；1.17 文档里的注解名不可照搬）。`ObjectProvider` 注入 `MeterTagAnnotationHandler`——用户没定义时 @MeterTag 被静默忽略。

---

## 十一、跨框架对比

| 维度 | Micrometer @Timed | Dropwizard @Timed | OpenTelemetry @WithSpan |
|------|------|------|------|
| **产物** | Timer（metrics）+ LongTaskTimer（可选） | Timer（metrics only） | Span（tracing only） |
| **直方图** | percentiles/histogram 两种模式（SLO 需编程式） | 无 | 无（tracing 不需要直方图） |
| **异常处理** | getExceptionTag() 取直接 cause simpleName | exceptionClass 记录 | span.setStatus(ERROR) |
| **异步支持** | CompletionStage.whenComplete() | 无 | Span.current() + Span.end() 跨线程 |

---

## 十二、面试核心考点

### Q1: percentiles 和 histogram 有什么区别？
`percentiles` 使用 `TimeWindowPercentileHistogram` 预分桶——固定 ~3KB 内存，与 QPS 无关。`histogram` 在 Prometheus 侧计算——固定桶数组，`histogram_quantile()` 函数估算分位数。生产 QPS > 100 推荐 histogram（零客户端开销），需要精确 P99.9 用 percentiles。

### Q2: 默认 metric name 是什么？class/method 怎么出现？
默认 metric name = `"method.timed"`（`DEFAULT_METRIC_NAME`，TimedAspect L88）。class/method 通过 `tagsBasedOnJoinPoint` Tag 传递（`class=declaringTypeName, method=methodName`）——**不是**编码在 name 中。

### Q3（陷阱）: Boot 3.2.5 里加了 @Timed 却没有指标，为什么？
`MetricsAspectsAutoConfiguration` 的条件是 `management.observations.annotations.enabled=true`——**默认 false**。不加配置则 TimedAspect/CountedAspect 根本不注册，注解静默无效。@Observed 不受此限制（ObservedAspect 默认注册）。

### Q4: @MeterTag 的 SpEL 表达式是内置的吗？
不是——micrometer-core 没有 SpEL 实现。需要外部注入 `ValueExpressionResolver`（自建 `MeterTagAnnotationHandler` Bean）。三级优先级：`resolver()` > `expression()` > `toString()`。1.12.5 只支持**参数级** @MeterTag；方法返回值级是 1.15+。

### Q5: longTask=true 失效是什么情况？
`buildLongTaskTimer()` 返回 `Optional<LongTaskTimer>`——构建抛异常（Registry 不支持等）→ `empty()` → 方法正常执行但不打点。**静默失效**——不报错、不 WARN。设计意图：指标系统故障不能拖垮业务。

### Q6（陷阱）: @Counted 的 recordFailuresOnly 以什么为准？
只看**异常**——没有异常就是 success（返回 null 也算 success）。`result` tag 反映"调用是否抛异常"，不是返回值。1.12.5 的 CountedAspect 无 `CountedMeterTagAnnotationHandler`（该扩展不存在于本基线）。
