# Circuit Breaker & Retry（弹性模式）专题 — 概念、算法、架构与业界对照

> 配套：**L37**（`L37-circuit-breaker-retry.md`——讲 SCG 里怎么用：过滤器源码、lab 实验、坑表）；
> 本专题讲 L37 放不下的部分：**概念体系 / 统计算法 / 阈值与自适应 / 重试进阶 / 状态放哪 / 业界实现对照 / 分层**。
> 交叉引用：L36 配套专题 `rate-limiting-concepts.md`（第 6 节做"熔断本地状态 vs 限流共享账本"的对照）。
> 证据口径：**源码** = 本地源包逐行核对（resilience4j 2.1.0 / sentinel-core 1.8.8 / SCG 4.1.5 与本地新版源码树）；
> **实测** = demo 现场跑出的输出；**文档口径** = 官方文档/公开资料（本地无源码可核，不冒充"源码级"）。

---

## 1 弹性模式到底在保护什么 —— 一个具体场景

订单服务同步调用库存服务。某天库存服务一次慢查询 + GC，RT 从 50ms 涨到 5s：

```
用户请求 → 订单服务（Tomcat 200 线程）
          → 每个请求在"等库存"上占住 5 秒
          → 40 秒后 200 个线程全部在等库存
          → 订单服务所有接口（不止库存）都不可用
          → 用户重试 → 流量 ×2 ×3 → 雪崩
```

注意伤害链：**一个下游的"慢"，吃光的是调用方（订单）的线程/连接资源**，然后故障顺着调用链往上游爬。弹性模式就是一套"在链路的每一环切一刀"的机制，目标不是"不失败"，而是**让失败不扩散**：

- **超时**切"等"：不让一个请求无限占资源（L37 实验 A：3s 的慢请求 1.1s 被切）；
- **重试**切"偶发"：对瞬时故障再给一次机会（L37 实验 C：第 3 次成功）；
- **熔断**切"持续"：确认对方病了，就**停止打它**（L37 实验 B：4 次失败后短路，provider 0 命中）；
- **限流**切"量"：入口按配额放行（L36）；
- **舱壁**切"资源池"：一个依赖打满不拖累其他依赖；
- **降级**是失败之后的动作：给用户"兜底答案"而不是错误。

本专题剩下的章节，就是把这张图拆开讲透。

---

## 2 弹性模式全景：熔断只是七件套之一

| 模式 | 防什么 | 触发依据 | 触发后动作 | 典型位置 |
|---|---|---|---|---|
| **超时** | 防"无限等" | 时间到 | 断开、报错（释放资源） | 每个调用点 / 网关 |
| **重试** | 防"瞬时失败" | 失败信号（异常/5xx） | 再试（限量、退避） | 客户端 / 网关 / 服务内 |
| **熔断** | 防"持续错/慢" | 窗口内统计（失败率/慢率） | 快速失败 / 兜底 | 网关 / 服务内 / sidecar |
| **限流** | 防"量" | 速率、并发 | 拒绝 / 排队 | 网关 / 服务内 |
| **舱壁** | 防"单一依赖吃光资源" | 资源池占用 | 排队 / 拒绝 | 服务内（线程/信号量）、网关（连接池） |
| **降级** | ——（不是防御，是**动作**） | 收到失败信号 | 返回兜底数据 | 各层 |
| **对端剔除** | 防"坏实例" | 连续失败计数 | LB 层不再选它 | sidecar / 服务网格 |

四个常见混淆点：

- **熔断 vs 降级**：熔断是"触发器"（什么时候不再调用），降级是"动作"（不调用之后返回什么）。
  L37 的 `fallbackUri` 就是把两者接起来的线；没有 fallback 时，熔断只给出 503/504。
- **熔断 vs 限流**：限流看的是"我们的承诺"（每秒允许多少），熔断看的是"对方的状态"（还值不值得打）。
  限流防的是"自己被打垮"，熔断防的是"持续把资源浪费在病人身上"。
- **舱壁 vs 限流**：限流管速率（每秒多少），舱壁管资源池（最多几个线程/连接在跑）。
  在响应式网关里"线程舱壁"没有意义（没有阻塞线程），它的等价物是**连接池上限**
  （reactor-netty `pool.maxConnections`）——这是网关侧很少单独讲舱壁的原因。
- **重试与熔断的方向相反**：重试把流量**放大**（1 次变 4 次），熔断把流量**收缩**（直接拒绝）——
  所以两者必须配合：熔断给重试踩刹车（L37 四件套顺序：重试在外、熔断在内）。

---

## 3 断路器核心：状态机与统计算法

> 本节路线与限流专题（`rate-limiting-concepts.md` §3）一致：**先理论、后实现证据**。
> 3.1 三个设计问题（理论）→ 3.2 统计算法谱系（理论）→ 3.3 状态机策略维度（模式层）→ 3.4/3.5 源码证据。
> 三态基础已在 **L37 §37.2** 速览过（状态图 + 行为表），这里不重复。

### 3.1 一个熔断器要回答三个问题（理论）

任何熔断器（R4j / Sentinel / Hystrix / Envoy）剥到骨头，都在回答三个问题：

1. **统计什么？** 失败？慢？两者都要？——只看"报错"会漏掉"变慢"：很多熔断事故里下游根本没报错，
   只是变慢了（第 1 节的雪崩恰恰是"慢"造成的）。所以现代实现普遍把"慢调用"也当作一类失败信号。
2. **怎么统计？** 窗口怎么划——"最近 N 次"还是"最近 N 秒"？为什么不能用"累计失败 N 次就断"？
   ——见 3.2 的算法谱系。
3. **怎么恢复？** 等多久再试？放几个探测请求？一次失败就再断，还是按失败率评估？
   ——见 3.3 的策略维度。

（阈值具体定多少是"参数"问题，放第 4 节；本节只讲"机制"。）

### 3.2 统计算法谱系：每个算法都在修上一个的 bug（理论）

与限流专题同款讲法——**上一个算法的问题，就是下一个算法存在的理由**：

| # | 算法 | 修了上一个的什么 | 遗留问题 | 代表实现 |
|---|---|---|---|---|
| 1 | **累计计数**（失败数 ≥ N 就断） | ——（最朴素） | 不分时间：旧账一直压着，"最近是否已恢复"评估不了 | 手写/教学 |
| 2 | **固定窗口计数**（每分钟清零） | 旧账问题 | 边界效应：跨窗口的连续失败被切成两半，都达不到阈值（判定延迟/漏判） | 简单实现 |
| 3 | **滑动窗口日志**（存每次结果的精确窗口） | 边界效应，且精确 | 内存 O(调用次数)，高 QPS 不可用 | 理论精确解 |
| 4 | **滑动窗口计数**（时间桶 + 增量聚合） | 内存降到 O(桶数) | 桶边界近似（工程可接受） | R4j `TIME_BASED`、Sentinel `LeapArray` |
| 5 | **按次数的环形缓冲**（最近 N 次 + subtract-on-evict） | 换维度：不按时间、按次数 | 低频时"最近 N 次"跨越很长时间（迟钝）；高频时窗口极短（灵敏） | R4j `COUNT_BASED`（默认） |

两个窗口维度的取舍直接决定"性格"：

- **按次数（#5）**：窗口内永远有 N 个样本（低频不怕没数据），但覆盖的时间随流量浮动；
- **按时间（#4）**：窗口就是最近的固定时长（高频时样本充足），但低频时可能样本太少，要靠"最小样本数"兜底（§4）。

这解释了 R4j 为什么两种都提供（默认 `COUNT_BASED`）：**低频场景下，按次数的窗口仍能攒够样本。**

### 3.3 状态机语义：三态是壳，策略是肉（模式层）

三态与转移条件在 **L37 §37.2** 已有速览。这里换个视角：**把熔断器想成一条"决策链"**——
从"看到一次调用结果"到"重新放行"，它要连续做 6 个决定。三态是固定骨架，而这 6 个决定各有多种合法选项，
**各家实现的差异全在这里**：

| # | 决策点（阶段） | 要回答的问题 | 可选项 | R4j（2.1.0） | Sentinel（1.8.8） |
|---|---|---|---|---|---|
| 1 | 统计信号（CLOSED） | 一次调用结果算什么？ | 异常 / 慢 / HTTP 状态码（需适配层） | 异常 + 慢率独立评估；5xx 需 `statusCodes` 适配（L37 实验 E） | 异常 / RT 策略；5xx 需适配层转异常 |
| 2 | 样本门槛（CLOSED） | 攒够多少样本才允许评估？ | 任意 N | `minimum-number-of-calls`（默认 100） | `minRequestAmount`（默认 5） |
| 3 | 打开判定（CLOSED） | 怎么算"超阈值"？ | 失败率 / 慢率 / RT / 异常数 | 失败率或慢率任一超标即打开 | 默认平均 RT 策略，另有异常比例/异常数 |
| 4 | 打开等待（OPEN） | 打开后等多久再试？ | 固定 / 按次数递增 | `waitIntervalFunctionInOpenState` 可递增（防震荡） | `timeWindow` 固定 |
| 5 | 转换方式（OPEN→HALF_OPEN） | 谁触发"试一下"？ | 定时器自动 / 下次请求懒转换 | 两者可配（默认懒） | 懒转换（`tryPass` 时机判断） |
| 6 | 半开探测（HALF_OPEN） | 放几个探测、怎么判恢复？ | 单个 / 一批 | 批次按失败率评估 | 单探测，一发定生死 |

**这张表的用法**：① 面试答"两家差别"——沿着决策链一条条答；② 自己实现/选型时当 checklist——6 个决定都要有答案；
③ 它是下面 3.4/3.5 源码的目录（每一行都有对应的源码证据）。

**读表要点**：这些决定没有"标准答案"——R4j 整体偏"稳健保守"（样本门槛高、批次评估），
Sentinel 偏"快速反应、快速恢复"（门槛低、单探测）。

---

### 3.4 实现证据（源码级）：R4j 与 Sentinel

#### 3.4.1 R4j 的转移实现（`CircuitBreakerStateMachine`）：魔鬼在"半开"和"等待"里


**CLOSED → OPEN**：每次调用结果 record 之后检查阈值，达标就 CAS 打开：

```java
private void checkIfThresholdsExceeded(Result result) {
    if (Result.hasExceededThresholds(result) && isClosed.compareAndSet(true, false)) {
        publishCircuitThresholdsExceededEvent(result, circuitBreakerMetrics);
        transitionToOpenState();
    }
}
```

**OPEN → HALF_OPEN** 有两种实现（源码 `OpenState`）：

```java
if (circuitBreakerConfig.isAutomaticTransitionFromOpenToHalfOpenEnabled()) {
    ScheduledExecutorService scheduledExecutorService = schedulerFactory.getScheduler();
    transitionToHalfOpenFuture = scheduledExecutorService
        .schedule(this::toHalfOpenState, waitDurationInMillis, TimeUnit.MILLISECONDS);
} else {
    // 不调度：等下一次 tryAcquirePermission 发现"等待时间已过"时懒转换
}
```

- `automatic-transition-from-open-to-half-open-enabled=true`：后台调度器**准点**转半开；
- 默认 `false`：**懒转换**——没有请求来就一直 OPEN，第一个到来且等待时间已过的请求触发转换并成为探测。

**半开怎么"探"**：许可数是倒计数的原子计数（源码 `HalfOpenState`）：

```java
permittedNumberOfCalls.getAndUpdate(current -> current == 0 ? current : --current) > 0
```

拿到许可的调用跑完一轮后，**R4j 把这批探测当成一个独立的"小窗口"来评估**（源码 `CircuitBreakerMetrics.forHalfOpen`：窗口大小 = `permittedNumberOfCallsInHalfOpenState`，
且 `minimumNumberOfCalls = min(配置值, 窗口大小)`）：

```java
static CircuitBreakerMetrics forHalfOpen(int permittedNumberOfCallsInHalfOpenState, ...) {
    return new CircuitBreakerMetrics(permittedNumberOfCallsInHalfOpenState,
        SlidingWindowType.COUNT_BASED, circuitBreakerConfig, clock);
}
```

结果评估（源码 `HalfOpenState.checkIfThresholdsExceeded`）：

```java
if (Result.hasExceededThresholds(result) && isHalfOpen.compareAndSet(true, false)) {
    transitionToOpenState();        // 这批探测的失败率/慢率超标 → 回 OPEN
}
if (result == BELOW_THRESHOLDS && isHalfOpen.compareAndSet(true, false)) {
    transitionToClosedState();      // 达标 → CLOSED
}
```

还有两个"防卡死/防震荡"的字段，值得单独记住：

- **`maxWaitDurationInHalfOpenState`**：半开期间如果迟迟攒不齐探测结果（流量太低），超过这个时间就回 OPEN——
  防止熔断器"半开着慢慢漏流量"。
- **`waitIntervalFunctionInOpenState`**：OPEN 的等待时长可以按"第几次打开（attempts）"计算（默认固定
  `waitDurationInOpenState`；可配成递增，如 5s → 10s → 20s），这就是**防震荡**（flapping）的主手段：
  反复半开-失败-打开时，等待越来越长。

#### 3.4.2 窗口实现（源码）：R4j 两种窗口 + Sentinel 单桶特化

（3.2 谱系里的 #4 / #5 落到代码，就是下面这些类。）

**Resilience4j COUNT_BASED —— 固定长度环形数组**（源码 `FixedSizeSlidingWindowMetrics`，类注释原文）：

> The sliding window is implemented with a circular array of {@code N} measurements …
> The sliding window incrementally updates a total aggregation. … When the oldest
> measurement is evicted, the measurement is subtracted from the total aggregation. (Subtract-on-Evict)
> … The time to retrieve a Snapshot is constant 0(1) … space requirement … O(n).

含义：只保留**最近 N 次调用**的结果；每来一条就覆盖最旧的一条，同时从"总数"里减掉旧的（增量维护）。
快照 O(1)、内存 O(n)、无全量日志。

**Resilience4j TIME_BASED —— 时间桶**（源码 `SlidingTimeWindowMetrics`，类注释原文）：

> … circular array of {@code N} partial aggregations (buckets) … Every bucket aggregates the
> outcome of all calls which happen in a certain epoch second. (Partial aggregation) …
> (Subtract-on-Evict) … Snapshot is constant 0(1) …

含义：**最近 N 秒**，每秒一个桶，快照 = 所有桶求和（减去过期桶）。

**Sentinel（1.8.8）—— 通用 `LeapArray` + 熔断侧特化为单桶**。
`LeapArray` 是"时间桶环"的通用实现（源码注释：`sampleCount = intervalInMs / windowLengthInMs`），
但**熔断器**构造时用的是：

```java
public ExceptionCircuitBreaker(DegradeRule rule) {
    this(rule, new SimpleErrorCounterLeapArray(1, rule.getStatIntervalMs()));
}
```

`sampleCount=1`、`interval=statIntervalMs`（`DegradeRule` 默认 `1000`ms）——即**每秒翻转一次的单桶窗口**
（不是多桶滑动）。这与很多人对"Sentinel 滑动窗口"的印象不同：**流控统计**用多桶滑动，
而 1.8.8 的**熔断统计**是单桶滚动。两者行为差异：单桶在秒边界有"突刺式清零"，多桶/计数窗口更平滑。

三种实现的共同点：都靠**环形缓冲/增量聚合**做到 O(1) 快照、O(n) 内存——
反面教材是"存下窗口内每一次调用再算"（滑动窗口日志：精确但内存 O(次数)），生产级熔断器都不这么干。

> 一个工程细节（源码）：R4j 的 `FixedSizeSlidingWindowMetrics.record` 是 `synchronized` 的——
> **每个熔断器一把锁**。统计本身 O(1)，高并发下会有一个很小的串行点，通常可接受；
> 但它解释了为什么熔断状态适合放本地（第 6 节）而不是做全局共享。

#### 3.4.3 慢调用的实现口径（"慢"为什么算失败，见 3.1）

只统计"报错"是不够的：慢 = 占资源（第 1 节的雪崩就是"慢"造成的），很多熔断事故里下游根本没报错，只是变慢。

- **R4j**：`slowCallDurationThreshold`（默认 60s：低于 60s 的调用都不算"慢"）+ `slowCallRateThreshold`
  （默认 100%：只有"全部调用都慢"才触发）——两个默认值叠加，等于默认不启用慢调用熔断，用的时候要显式调低。
  评估结果有四种（源码 `Result` 枚举）：
  `BELOW_THRESHOLDS / FAILURE_RATE_ABOVE_THRESHOLDS / SLOW_CALL_RATE_ABOVE_THRESHOLDS / ABOVE_THRESHOLDS`——
  **任一超标都会触发打开**（`hasExceededThresholds = 失败率超标 || 慢率超标`）。
- **Sentinel**：`DegradeRule` 的默认策略就是 `DEGRADE_GRADE_RT`（平均 RT，源码 `RuleConstant`：`RT=0`），
  另有异常比例（1）、异常数（2）；`minRequestAmount` 默认 5、`slowRatioThreshold` 默认 1.0。

### 3.5 半开策略对照（源码证据）

| | Resilience4j 2.1.0 | Sentinel 1.8.8 |
|---|---|---|
| 半开许可 | `permitted-number-of-calls-in-half-open-state`（默认 10） | 一次只放**一个**探测（`tryPass` 里 `fromOpenToHalfOpen` 的 CAS 只有一个赢家） |
| 探测结果判定 | **一批**探测作为一个新窗口评估（`min(配置 minCalls, 许可数)`） | **单次探测**：成功 → CLOSED，失败 → OPEN（`ExceptionCircuitBreaker`：`if (error == null) fromHalfOpenToClose() else fromHalfOpenToOpen()`） |
| 回到 CLOSED 的代价 | 攒够 `min(配置 minCalls, 许可数)` 个探测且不超阈 | 一发成功即可 |

一个"发一个探测"的 Sentinel 风格恢复快但更激进；R4j 的批次评估更稳但恢复慢。
**没有优劣，只有权衡**——但读源码时要小心别把两者混为一谈。

---

## 4 阈值与自适应：怎么定、怎么防误杀

### 4.1 静态阈值的判据（理论 → 源码证据）

一句话原理：**"窗口内失败率超过阈值"并不够——必须先有足够样本，否则会误杀。**

把这句话拆开，判据由三件事构成：

1. **窗口内统计**（§3.2 的算法负责）：失败率 / 慢率 = 窗口内失败数 ÷ 窗口内总数；
2. **最小样本门槛**：样本不足时**不评估**——防误熔断的第一道门。极端例子：总共只来过 1 个请求且失败了，
   失败率 100%；若没有门槛，第一个失败就把熔断打开了；
3. **阈值比较**：失败率 ≥ `failureRateThreshold` 才打开。

R4j 的实现就是这个判据的代码化（源码 `CircuitBreakerMetrics.getFailureRate`）：

```
bufferedCalls < minimumNumberOfCalls  → 不评估（返回 -1）
否则：失败率 = 失败数 / 窗口内总数，与 failureRateThreshold 比较
```

三个参数的配合关系（怎么选）：

- **窗口大小**：即 §3.2 的取舍——COUNT_BASED 是"最近 N 次"（低频不怕没数据）；TIME_BASED 是"最近 N 秒"
  （高频样本充足）；
- **`minimum-number-of-calls`**：样本不足不评估——这是**防误熔断的第一道门**。
  默认 100 次；低流量服务（每分钟几十次）照抄默认值会永远不熔断；高流量服务配太小又会被一个毛刺打穿；
- **阈值**：50% 失败率（R4j 默认）实际相当保守（一半请求都失败才熔断）；Sentinel 的异常比例规则同理。

起点怎么给：先看监控（错误率、慢调用率、P99），把阈值定在"**正常波动的上沿之上、事故水平之下**"；
上线后按误熔断（fallback 触发率）和漏熔断（下游已挂但没打开）双向调。**没有数据的阈值都是猜测**。

### 4.2 误熔断比不熔断更糟

这是弹性工程里最反直觉的一条：

- 熔断打开 = **自己主动拒绝自己的合法流量**——下游也许还能服务，你先把用户挡在门外了；
- 如果 fallback 返回空数据/默认值，用户看到的是"功能坏了"而不是"稍后再试"——业务损失；
- 半开探测失败会重新打开，形成"开-关-开"的震荡（flapping），比稳定拒绝更糟（用户看到的错误时有时无）。

所以配置的默认方向应该是"**迟钝一点**"：宁可晚一点熔断，不要被瞬时的 3 个失败骗开。
防误杀三件套（前文源码都有出处）：**最小样本数**、**递增的打开等待**、**半开批次评估**。

### 4.3 自适应（文档口径）

静态阈值之外的路线，知道存在即可：

- **Sentinel 系统规则**（`sentinel-core` 有 `SystemSlot`/`SystemRule`；具体阈值语义按官方文档）：
  按 `load1`、CPU 使用率、平均 RT、并发线程数、入口 QPS 自动限流/降级；
- **Netflix concurrency-limits（BBR/Vegas）**：根据 RTT 与排队延迟动态算"该放多少并发"，
  把"固定阈值"换成"跟住瓶颈"；
- **Envoy Adaptive Concurrency**：基于延迟梯度的自适应并发控制。

共同思想：把"人工设定的固定数字"换成"对系统信号的闭环反馈"。代价是更难解释、更难预测，适合成熟团队 + 完善监控。

---

## 5 重试进阶：从"重试三次"到重试预算

L37 已经讲了重试的基础（幂等、默认值、放大效应）。这里补四个生产必须知道的进阶概念。

### 5.1 重试风暴（retry storm）

```
服务 B 抖动（成功率 50%）
→ 1 万个调用方各自重试 3 次 → 对 B 的请求量 ×4（很多是同时的）
→ B 更慢 → 更多失败 → 更多重试 …（正反馈）
```

重试是**向未来借流量**：借的时候不考虑对方还得起还不起，就会把"抖动"升级成"雪崩"。
两个标准解法：

- **退避 + 抖动（jitter）**：只退避不抖动，所有客户端会在同一时刻（5ms/10ms/20ms 后）同步重试，
  形成脉冲；抖动把重试时刻随机化，打散脉冲。
  连接 L37：**SCG 4.1.5 的 Retry 只有 backoff，没有 jitter**；本地新版源码树已加
  `JitterConfig`（`randomFactor` 默认 0.5，源码可查）——升级 SCG 时这是一个值得利用的新能力。
- **重试预算（retry budget）**：限制"重试流量占总流量的比例"，而不是限制单个请求的重试次数。

### 5.2 重试预算（文档口径）

中心思想（Google SRE 与主流代理均采用）：**健康的系统里重试应该很少**——
如果重试量突然涨到请求量的 20%，说明系统在病中，此时应该**收缩**重试而不是放大它。

- **Envoy**：`retry_budget`（默认 `budget_percent: 20`，即重试不超过总请求的 20%，另有最小并发保护）；
- **gRPC**：`retryThrottling`——用令牌桶控制重试比例（建议 token_ratio 0.1），
  且 `maxAttempts` 协议上限为 5；
- 工程含义：**网关/服务内做重试时，最好在网关维护一个"重试率"指标并告警**——
  重试率飙升 = 依赖在病中，而不是"重试在救命"。

### 5.3 对冲（hedging）≠ 重试

| | 重试（Retry） | 对冲（Hedging） |
|---|---|---|
| 时机 | **失败之后**再发 | **成功之前**（如 P95 延迟后）再发一份 |
| 目标 | 修瞬时错误 | 砍**尾延迟**（一份慢请求不影响用户） |
| 成本 | 失败才翻倍 | 平时就多花（P95 以上的请求都会双发） |
| 实现 | 几乎所有框架 | gRPC hedging policy、部分服务网格（文档口径）；SCG 没有 |

一句话：**重试治"错"，对冲治"慢"**。对冲适合"读多写少、同质后端、延迟方差大"的场景（如搜索扇出），
不适合有副作用的写请求。

### 5.4 deadline 传播 / 超时预算

一次用户请求穿过：客户端 → 网关 → 服务 A → 服务 B。如果每层都配"自己的超时"，最坏情况下总耗时是
各层超时之和；而用户的 deadline 是**全局的**。

- **gRPC 原生支持 deadline 传播**（协议带 `grpc-timeout`，每跳继承剩余时间；文档口径）；
- HTTP 生态没有标准，通常靠约定 header（如 `X-Request-Deadline`）自己传；
- 网关这层的落地就是 L37 的铁律：**网关超时 < 调用方超时**，且要把"剩余时间"传给下游，
  而不是让下游重新获得一个完整超时。

---

## 6 熔断状态放哪：本地 vs 共享（与限流的关键对照）

这是把 L37 和 L36 两篇串起来的一节。

**限流的账本必须共享**（L36）：配额是"对外的承诺"（比如全局限 1000 QPS），多实例各自计数会超卖 ×N，
所以用 Redis + Lua 做**中心化账本**，代价是每次限流判断多一跳 Redis。

**熔断的状态恰恰相反：主流实现全部是"实例本地"的。**
现场实测（demo 起两个网关实例，配置完全相同）：

```
初始：        8080 cb-lab CLOSED(0,0)  |  8082 cb-lab CLOSED(0,0)
只在 8080 打 4 发 500：
              8080 cb-lab OPEN(buffered=4, failed=4)  |  8082 cb-lab CLOSED(0,0)
8080 /cb-lab/hello → {"fallback":true, ..., CallNotPermittedException: 'cb-lab' is OPEN}
8082 /cb-lab/hello → {"port":8081, ..., "greeting":"hello world"}   ← 真实放行
```

同一个下游、同一个配置：**8080 已经熔断，8082 完全不受影响，继续打下游**。这就是"状态本地"。

为什么这样设计是合理的（三条理由）：

1. **目标不需要全局精确**：熔断的目的是"每个实例自己别再浪费资源、给下游减负"。
   每个实例独立判断，已经能达到目的；限流是"总配额不能超"，熔断是"我别再打了"——后者天然可分。
2. **一致性有代价**：共享熔断状态要给每次调用加一跳存储（熔断恰恰要"快"），
   还引入写热点和新的故障域（存储挂了，熔断怎么办？）——为了统一"概率判断"付出这个代价不值。
3. **本地状态有天然的正确性**：一个实例自己观测到的失败，就是它自己真实的体验。

代价与观感问题（面试可以拿这个答"多实例下熔断有什么坑"）：

- **状态不同步**：用户打到健康实例正常、打到已熔断实例 503——表现是"时好时坏"；
- **滚动发布重置**：新实例从 CLOSED 开始，旧实例可能 OPEN（发布瞬间行为不一致）；
- **流量不均时样本不足**：R4j 默认 `minimumNumberOfCalls=100`——流量小的实例可能永远凑不够样本。

需要"共享/集中"的场景其实不是熔断判断本身，而是**监控聚合**（历史上 Hystrix 用 Turbine 聚合多实例的
熔断状态做展示）与**网格层的实例剔除**（Envoy outlier detection 也是数据面本地判断——
"我不再把流量发给这个坏实例"，但每个 sidecar 各记各的）。**没人在每次调用路径上做分布式熔断。**

---

## 7 业界实现对照

> SCG 的 `CircuitBreaker` 过滤器不是一个独立实现，而是对 Resilience4j 的封装（L37 全文）；下表看的是各"引擎"本身的差异。

| | Hystrix | Resilience4j | Sentinel | Envoy / Istio | gRPC |
|---|---|---|---|---|---|
| 统计窗口 | 环形滚动计数（HystrixRollingNumber，1s 桶 × 10s）【文档口径】 | 环形数组：COUNT_BASED / TIME_BASED（源码） | LeapArray 时间桶；**熔断**为单桶每秒（源码） | 连续失败计数（outlier detection）【文档口径】 | 客户端重试节流令牌桶【文档口径】 |
| 隔离方式 | **线程池隔离**（每依赖一池）或信号量【文档口径】 | Bulkhead 模块（信号量/线程池） | 并发线程数流控模式 | 连接池 / sidecar 进程隔离 | —— |
| 重试 | 无内建重试（配 Spring Retry） | Retry 模块 | 无（重试不是 Sentinel 核心） | retry policy + **retry budget** | **协议内建**：retry / hedging policy + retryThrottling |
| 半开恢复 | 单探测（半开放 1 个）【文档口径】 | 一批探测按失败率评估（源码） | 单探测（一发定生死，源码） | 连续成功才恢复；ejection_time 递增 | —— |
| 状态 | 本地（Turbine 聚合展示） | 本地 | 本地 | 本地（每个 sidecar） | 本地（客户端库） |
| 维护状态 | **2018 起维护模式**，Netflix 官方推荐替代品【文档口径】 | 活跃 | 活跃 | 活跃 | 活跃 |

选型一句话（面试口径）：

- **Java 服务内**：Resilience4j（轻、函数式、无强制依赖）或 Sentinel（要控制台/动态规则/流控全家桶）；
- **网关/网格**：不用在业务代码里解决——Envoy/Istio 的 outlier detection + retry budget 覆盖"基础设施级"容错；
  SCG 自带的 CircuitBreaker/Retry 适合**没有网格**的中小架构；
- **Hystrix**：新项目不用（维护模式），但"线程池隔离"的思想和教训仍要会讲。

---

## 8 放哪层：客户端 / 网关 / 网格 / 服务 四层分工

| | 客户端 SDK | 网关（SCG） | 网格 sidecar（Envoy） | 服务内（R4j/Sentinel） |
|---|---|---|---|---|
| 最擅长 | 业务兜底、deadline 起点 | 入口统一保护、粗粒度 | 全语言无感、实例级剔除 | 业务语义降级、细粒度 |
| 幂等判断 | 最好（知道用户意图） | 只能按方法白名单（GET） | 只能按协议信号（5xx） | 最好（知道业务） |
| 覆盖范围 | 单客户端 | 所有入口流量 | 所有服务间调用 | 单依赖/单方法 |
| 配置方式 | 随 App 发布 | 网关配置/动态路由 | CRD/控制面 | 代码/动态规则 |

**反模式：四层都开满重试**——每层 4 次尝试，一条链的流量乘数就是 `4 × 4 × 4`；
必须选一层作为"重试责任人"，其他层只做超时和熔断。

决策速查：

- "入场配额/粗粒度熔断/统一超时" → 网关；
- "跨语言、基础设施级实例剔除" → 网格；
- "业务级降级（返回缓存/默认值）、依赖级舱壁" → 服务内；
- "用户可见的兜底与 deadline 起点" → 客户端。

---

## 9 面试题库（10 问，一句话答案 + 追问弹药）

1. **三态怎么转？** CLOSED 超阈 → OPEN → 等 waitDuration → HALF_OPEN → 成功回 CLOSED / 失败回 OPEN。
   弹药：懒转换 vs 自动调度的源码区别；半开批评估 vs 单探测。
2. **熔断统计为什么用滑动窗口，不用全量日志？** 窗口是"最近 N 次/秒"的近似，环形缓冲 O(1) 快照、
   O(n) 内存；全量日志 O(次数) 内存且快照 O(n) 计算。
3. **COUNT_BASED 和 TIME_BASED 怎么选？** 低流量用计数（不怕慢热），高流量用时间（窗口内样本足）。
4. **慢调用为什么要算失败？** 慢占资源，雪崩常常先由"慢"引起；R4j 有 slowRate 独立评估，Sentinel 默认策略就是 RT。
5. **半开为什么不同框架行为差这么多？** Sentinel 一发定生死、R4j 一批按失败率评估——
   恢复速度 vs 误杀率的权衡。
6. **熔断阈值怎么定？** 监控先行：错误率/慢率的"正常上沿"与"事故水平"之间；最小样本数防误杀；
   上线后双向调参。
7. **什么是 retry storm / retry budget？** 重试把抖动放大成雪崩；预算把重试流量封顶（Envoy 20%、gRPC 令牌桶）。
8. **jitter 解决什么？** 退避不抖动 = 全世界同一时刻重试，脉冲共振；抖动去相关。
9. **熔断状态为什么本地，而限流要共享？** 熔断是"我自己别再打"（可分、概率判断），限流是"总配额不能超"
   （精确承诺）；共享熔断还会给每次调用加一跳存储。实测：双实例一个 OPEN 一个 CLOSED。
10. **重试和对冲的区别？** 重试治错（失败后），对冲治慢（P95 前双发）；对冲平时就多花钱。

---

## 10 Self-Test

1. 画出三态转移图，并标出："懒转换"与"自动转换"、"批评估"与"单探测"两条分支。
2. 用第 1 节的雪崩场景，分别说出超时/熔断/舱壁/限流各切断了哪一环。
3. R4j 半开窗口的 `minimumNumberOfCalls` 是怎么算的？（提示：`min(配置值, permitted)`）
4. 某服务 QPS 20、R4j 默认配置（窗口 100、最少 100 次），熔断会有什么问题？怎么改？
5. 重试预算 20% 的含义是什么？如果重试率告警持续 >20%，说明什么？
6. 为什么"四层都开重试"是反模式？画出一条链的流量乘数。
7. 双实例实验里 8080 OPEN、8082 CLOSED——如果业务要求"一个实例熔断、全部实例都别再打下游"，
   怎么做？代价是什么？
8. （对照）L36 限流账本为什么不能像熔断一样放本地？（用"超卖 × 实例数"推理）
9. Hystrix 的"线程池隔离"解决什么问题？为什么它在响应式网关里不再适用？
10. 给一个"读多写少、延迟方差大"的接口选方案：重试还是对冲？为什么？

---

## 11 Open Questions

- [ ] Sentinel 1.8.8 熔断侧是单桶（`SimpleErrorCounterLeapArray(1, statIntervalMs)`）；后续版本是否改成多桶滑动——升级/对照时要复核。
- [ ] 网关侧"连接池舱壁"的配置口径（reactor-netty `pool.maxConnections` 与 SCG `httpclient` 配置的对应关系）——与 L42 容量并线。
- [ ] 自适应并发（BBR / Envoy adaptive concurrency）在网关的落地可行性与收益——需要压测/实验，暂不展开。
- [ ] hedging 在 SCG 无内建实现；如果要做，能否用 Retry + 双发 + 取先返回组合出来——待验证（成本与取消语义复杂）。

---

## 附录 A：Resilience4j 最小上手（读 L37 前的 10 分钟预备）

> L37 只需要 R4j 的"最小必要集"——不需要先系统学一个框架。本附录把入口补齐，看完回读 L37 就不会卡。
> 配套实操就是 L37 §37.1 的 lab 路由（`/cb-lab`、`/cb-nofallback`、`/retry-lab`、`/retry-timeout-lab`）。

### A.1 模块：每个模块只做一件事

| 模块 | 干什么 | 本课/网关里的角色 |
|---|---|---|
| `resilience4j-circuitbreaker` | 熔断状态机 + 统计 | L37 主角（SCG `CircuitBreaker` 过滤器的底层引擎） |
| `resilience4j-timelimiter` | 超时 | 熔断器自带（默认 1s，L37 实验 A） |
| `resilience4j-retry` | 重试 | **SCG 的 Retry 过滤器不用它**（reactor-extra 实现，L37 §37.4）；服务内可用 |
| `resilience4j-bulkhead` | 舱壁（信号量/线程池） | 网关（响应式）用不上；服务内用 |
| `resilience4j-ratelimiter` | 单机限流 | 网关用 Redis 版（L36）；服务内单机场景才用它 |
| `resilience4j-core` | 环形缓冲 / Registry / 事件 / 指标 | 被上面各模块依赖 |

### A.2 原生 API：装饰器（不在 Spring 里怎么用）

R4j 的核心用法就是把任意调用**包一层**：

```java
CircuitBreaker cb = CircuitBreaker.ofDefaults("backendA");   // Registry 按名管理，同名复用

// 同步：装饰 Supplier
Supplier<String> call = CircuitBreaker.decorateSupplier(cb, () -> backend.call());
String r = call.get();     // 熔断打开时抛 CallNotPermittedException（L37 实验 B 见过）
                           // 业务异常会被记账，达到阈值后 cb 自动 OPEN

// 响应式：装饰 Mono/Flux（spring-cloud-circuitbreaker 内部就是这么接的，L37 §37.3③）
Mono<String> m = backend.reactiveCall().transform(CircuitBreakerOperator.of(cb));
```

Retry / TimeLimiter / Bulkhead 是同样的装饰器风格（`Retry.decorateSupplier`、`TimeLimiter.decorateFutureSupplier`…）。
**这解释了 Spring Cloud 为什么能"换引擎"**：它依赖抽象接口（`ReactiveCircuitBreakerFactory`），R4j 只是 provider 之一（§7 + L37 §37.3④）。

### A.3 Spring Cloud 集成：从 starter 到 SCG 过滤器

```
spring-cloud-starter-circuitbreaker-reactor-resilience4j
 ├─ spring-cloud-circuitbreaker-resilience4j
 │    └─ ReactiveResilience4JCircuitBreakerFactory   ← R4j 适配到 Spring Cloud 抽象
 └─ resilience4j-spring-boot3                        ← 读 resilience4j.* 配置、建 Registry、带 actuator endpoint

SCG 过滤器 SpringCloudCircuitBreakerResilience4JFilterFactory
 └─ 只调抽象工厂：reactiveCircuitBreakerFactory.create(name)
```

三处配置（L37 §37.3④ 有完整表）：`resilience4j.circuitbreaker.instances.<name>.*`、
`resilience4j.timelimiter.instances.<name>.timeout-duration`、
`spring.cloud.circuitbreaker.resilience4j.disable-time-limiter`。

### A.4 服务内用法（网关之外，知道有就行）

R4j 还有注解形态：`@CircuitBreaker(name = "x", fallbackMethod = "fb")`（`resilience4j-spring-boot3`），
这是"服务内熔断"的标准姿势（L37 §37.7 的下一层）。网关场景不用注解，用过滤器。

### A.5 建议阅读顺序

专题 §2（七件套）→ §3（状态机/窗口）→ **附录 A.2/A.3** → L37 §37.3（SCG 接线）→ L37 五组实验 → 回专题 §5/§6。

---

## 附录 B：外部实验参考（`source-code/sca-lab`，非本课程）

> 下面这些实验来自**另一个独立项目** `sca-lab`，**不属于本课程的组成，也不作为前置**——只当"想看别人怎么配、怎么验证"的延伸参考。
> 本课程的实验仍然是 L37 §37.1 的那四条 lab 路由（自洽、可复跑）。

| 实验 | 位置 | 看什么 | 与本专题的对应 |
|---|---|---|---|
| **SCG × R4j 熔断**（gateway-lab A-06） | `sca-lab/gateway-lab`（HANDOVER §A-06；路由 `/api/circuitbreaker/**` → `/fallback/circuitbreaker`，端口 28012） | 同类能力的另一种配置/验证姿势 | 实测 "503 Circuit Breaker Open"，与 L37 §37.3② 的短路映射一致 |
| **手写熔断器**（sentinel-lab C-03） | `sca-lab/sentinel-lab`（`core/SimpleCircuitBreaker` + 6 个单测；配 `docs/D4-CircuitBreaker-熔断状态机.md`） | "自己实现一遍三态"的学习法 | 与专题 §3 对照读：熔断器核心 = 状态机 + 窗口统计 |
| **Sentinel 熔断/降级 live**（A-02/A-03） | `sca-lab/sentinel-lab`（`/sentinel/error`、`/sentinel/circuit-status`、`/sentinel/fallback?error=true`，配 Dashboard） | Sentinel 侧的规则触发与状态查询 | 与 §7 对照表互补（R4j 一侧看本专题） |
| **Sentinel × Feign 熔断**（A-06） | `sca-lab/openfeign-lab/docs/A-06-熔断Sentinel.md` | 熔断放在"服务间调用客户端"层的姿势 | 第 8 节"放哪层"的实例 |

> sca-lab 的 Sentinel 源码深挖（sentinel-lab `docs/D1~D13`）是**它自己那条线**的内容；本课程对应的课是**计划中的 L45（Gateway × Sentinel）**。
