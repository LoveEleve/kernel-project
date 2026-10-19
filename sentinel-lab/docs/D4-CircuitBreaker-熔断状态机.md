# D4: 熔断状态机 — CircuitBreaker OPEN→HALF_OPEN→CLOSED

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/block/degrade/circuitbreaker/`
> 核心文件: `CircuitBreaker.java`(81行,接口+状态枚举) / `AbstractCircuitBreaker.java`(163行,状态机+CAS) / `ExceptionCircuitBreaker.java`(166行) / `ResponseTimeCircuitBreaker.java`(170行)
> 注意: `onRequestComplete` 不在 AbstractCircuitBreaker 中——在 ExceptionCircuitBreaker(L65) 和 ResponseTimeCircuitBreaker(L65) 各自实现
> 前置: D3（流控）理解两种拒绝策略；D1（滑动窗口）理解 metrics 来源

---

## 一、问题背景：流控的"一刀切"和熔断的"自适应"

D3 的流控逻辑是只要 QPS 超标立即拒绝。但有些场景下**慢调用占比超标**需要触发保护，或者**异常率过高**意味着下游已经不可用了——这不是"来了多少请求"，而是"请多个请求成功/失败"的质量问题。Sentinel 的答案是 `DegradeRule` + `CircuitBreaker` 状态机。

**三种熔断策略**：

| 策略 | 触发条件 | 适用场景 |
|------|------|------|
| 慢调用比例 (`SLOW_REQUEST_RATIO`) | 过去统计窗口内慢调用比例 > 阈值 | 下游 DB/第三方 API 变慢，但不一定全失败 |
| 异常比例 (`ERROR_RATIO`) | 过去窗口内异常比例 > 阈值 | 下游返回 500/抛异常比例上升 |
| 异常数 (`ERROR_COUNT`) | 过去窗口内异常数 > 阈值 | 需要的绝对数值而非比例 |

---

## 二、CircuitBreaker 接口：状态枚举 + 三个入口方法

**位置**: `CircuitBreaker.java:L27-81`

```java
public interface CircuitBreaker {
    DegradeRule getRule();           // 获取关联的熔断规则
    boolean tryPass(Context context); // 请求进入前：当前状态允许通过吗？
    State currentState();            // 查询当前状态
    void onRequestComplete(Context context); // 请求完成后：状态转换 + metrics 记录

    enum State {
        OPEN,       // 熔断打开：所有请求立即拒绝
        HALF_OPEN,  // 探测阶段：允许少量探测请求通过
        CLOSED      // 正常：所有请求放行
    }
}
```

**状态机**：

```
          [超出阈值]
  CLOSED ────────────→ OPEN
                         │
                         │ retryTimeoutArrived()（重试时间到了）
                         ↓
                     HALF_OPEN
                      ↙     ↘
            [探测成功]         [探测失败]
                ↓                 ↓
            CLOSED              OPEN
```

---

## 三、AbstractCircuitBreaker：状态转换的 CAS 实现

**位置**: `AbstractCircuitBreaker.java:L33-163`

```java
public abstract class AbstractCircuitBreaker implements CircuitBreaker {
    // L40: 状态存储在 AtomicReference 中——CAS 保证状态原子转换
    protected final AtomicReference<State> currentState = new AtomicReference<>(State.CLOSED);

    // L68-79: 当前状态决定是否放行
    public boolean tryPass(Context context) {
        if (currentState.get() == State.CLOSED)     return true;    // L70
        if (currentState.get() == State.OPEN) {                      // L73
            // OPEN 状态下检查重试时间是否到了
            return retryTimeoutArrived() && fromOpenToHalfOpen(context); // L75
        }
        return true;   // HALF_OPEN → 允许探测请求通过                   // L78
    }
}
```

**四种状态转换的 CAS 实现**：

```java
// L93-99: CLOSED → OPEN（达到阈值）—— 注意: 源码方法名是 fromCloseToOpen（无 d）
protected boolean fromCloseToOpen(double snapshotValue) {
    State prev = State.CLOSED;                                           // L94
    if (currentState.compareAndSet(prev, State.OPEN)) {                  // L95
        updateNextRetryTimestamp();                                      // 记录下次重试时间
        notifyObservers(prev, State.OPEN, snapshotValue);                // L98
        return true;
    }
    return false;
}

// L104-107: OPEN → HALF_OPEN（重试时间到 + 探测开始）
protected boolean fromOpenToHalfOpen(Context context) {
    if (currentState.compareAndSet(State.OPEN, State.HALF_OPEN)) {      // L105
        notifyObservers(State.OPEN, State.HALF_OPEN, null);
        return true;
    }
    return false;
}

// L132-136: HALF_OPEN → OPEN（探测失败——又有异常/慢调用）
protected boolean fromHalfOpenToOpen(double snapshotValue) {
    if (currentState.compareAndSet(State.HALF_OPEN, State.OPEN)) {      // L133
        updateNextRetryTimestamp();
        notifyObservers(State.HALF_OPEN, State.OPEN, snapshotValue);
        return true;
    }
    return false;
}

// L141-147: HALF_OPEN → CLOSED（探测成功）—— 注意: 源码方法名是 fromHalfOpenToClose（无 d）
protected boolean fromHalfOpenToClose() {
    if (currentState.compareAndSet(State.HALF_OPEN, State.CLOSED)) {    // L142
        notificationCounter.incrementAndGet();
        notifyObservers(State.HALF_OPEN, State.CLOSED, null);
        return true;
    }
    return false;
}
```

**为什么用 `AtomicReference.compareAndSet` 而非 `synchronized`？**

状态转换是 Sentinel 的性能热路径——每个请求的 `tryPass()` 都要读状态。`compareAndSet` 无竞争时只需要一次 CAS 操作（纳秒级），而 `synchronized` 涉及对象头修改和上下文切换（微秒级）。在 HALF_OPEN 探测阶段，只有一个探测请求能成功 `fromHalfOpenToClosed`，其余请求都在 HALF_OPEN 状态下直接返回 `true`。

---

## 四、transformToOpen + 子类 onRequestComplete

**注意**：`onRequestComplete` 不在 `AbstractCircuitBreaker` 中——它在 `ExceptionCircuitBreaker.java:L65` 和 `ResponseTimeCircuitBreaker.java:L65` 各自实现。`AbstractCircuitBreaker` 只提供被 `onRequestComplete` 调用的 `transformToOpen` 方法。

**transformToOpen**（`AbstractCircuitBreaker.java:L150-162`）：

```java
protected void transformToOpen(double triggerValue) {
    State cs = currentState.get();                                       // L151
    switch (cs) {
        case CLOSED:
            fromCloseToOpen(triggerValue);                               // L155: CLOSED→OPEN
            break;
        case HALF_OPEN:
            fromHalfOpenToOpen(triggerValue);                            // L157: 探测失败→回退 OPEN
            break;
        default:
            break;   // OPEN 状态：请求已被拒绝，不做任何事
    }
}
```

**ExceptionCircuitBreaker.onRequestComplete**（`ExceptionCircuitBreaker.java:L65-82`）：
- 从 `context.getCurEntry()` 获取异常信息
- 根据 `DegradeRule.grade`（`ERROR_RATIO` 或 `ERROR_COUNT`）计算指标值
- 超过阈值则调用 `transformToOpen(metricValue)`

**ResponseTimeCircuitBreaker.onRequestComplete**（`ResponseTimeCircuitBreaker.java:L65-85`）：
- 从 `context.getCurEntry()` 获取 RT（响应时间）
- 统计窗口内 RT > `maxAllowedRtMs` 的请求比例
- 超过慢调用比例阈值则调用 `transformToOpen(ratio)`

---

## 五、DegradeSlot：熔断槽的入口

**位置**: `DegradeSlot.java:L41-76`

```java
public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node,
                  int count, boolean prioritized, Object... args) throws Throwable {
    performChecking(context, resourceWrapper);                           // L43: 委托给 performChecking
    fireEntry(context, resourceWrapper, node, count, prioritized, args); // L45
}

void performChecking(Context context, ResourceWrapper r) throws BlockException {
    List<CircuitBreaker> circuitBreakers = DegradeRuleManager.getCircuitBreakers(
        r.getName());                                                    // L49
    for (CircuitBreaker cb : circuitBreakers) {                         // L53
        if (!cb.tryPass(context)) {                                     // L54
            throw new DegradeException(cb.getRule().getLimitApp(),      // L55
                cb.getRule());
        }
    }
    fireEntry(context, resourceWrapper, node, count, prioritized, args); // L58
}

// L67-76: exit 中记录请求完成（触发状态转换）
public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
    List<CircuitBreaker> circuitBreakers = DegradeRuleManager.getCircuitBreakers(
        resourceWrapper.getName());
    if (circuitBreakers != null) {
        for (CircuitBreaker circuitBreaker : circuitBreakers) {
            circuitBreaker.onRequestComplete(context);                   // L76
        }
    }
    fireExit(context, resourceWrapper, count, args);
}
```

**执行时序**：`entry` → 每个 CircuitBreaker 的 `tryPass` → 通过则 `fireEntry`（进入下一个 Slot）→ 业务执行 → exit → 每个 CircuitBreaker 的 `onRequestComplete`（记录指标 + 可能触发状态转换）。

---

## 六、发散：Sentinel CircuitBreaker vs Hystrix vs Resilience4j

| 维度 | Sentinel | Hystrix | Resilience4j |
|------|------|------|------|
| 状态管理 | `AtomicReference<State>` + CAS | `HystrixCircuitBreaker` + 锁 | `AtomicReference<State>` + CAS |
| 指标来源 | LeapArray 滑动窗口（D1） | `HystrixRollingNumber`（环形缓冲区） | 环形缓冲（RingBitSet） |
| 探测恢复 | HALF_OPEN → 探测请求成功/失败 → 决定恢复/回退 | HALF_OPEN → 一个探测请求 | HALF_OPEN → 可配置探测请求数 |
| 默认开启 | ❌ 需手动配 `DegradeRule` | ✅ 默认开启 | ❌ 需显式创建 `CircuitBreakerConfig` |
| 生产监控 | Dashboard 展示状态转换 + `onRequestComplete` 记录 | Hystrix Dashboard（已停更） | Micrometer metrics |

---

## 七、面试考点

**Q1: Sentinel 熔断三种策略的区别？HALF_OPEN 状态如何探测？**

慢调用比例：`maxAllowedRtMs` 定义慢调用阈值，统计窗口内慢调用占比超限则熔断。异常比例/异常数：统计窗口内异常占比或绝对数超限。HALF_OPEN 状态允许第一个探测请求通过——如果探测成功（`onRequestComplete` 中 `getMetricValue` ≤ 阈值），`fromHalfOpenToClosed()` 恢复；探测失败则 `fromHalfOpenToOpen()` 回退。

**追问**: HALF_OPEN 只有一个探测请求够吗？

CAS 保证了只有第一个 compareAndSet 成功的线程探测——其他线程在 HALF_OPEN 状态下直接返回 `true`（不检查指标），因为 `tryPass` 中 `State.HALF_OPEN` 直接 return true。所以 HALF_OPEN 实际只有一个真实探测，其余都是"借道放行"。

**Q2: `retryTimeoutArrived` 什么作用？**

熔断 OPEN 状态不是永久的——`retryTimeoutMs` 超时后自动进入 HALF_OPEN 尝试恢复。`retryTimeoutArrived()` 检查 `currentTime - recoveryStartTime >= retryTimeoutMs`。如果恢复失败（探测再次异常），重新计时。

---

## 八、生产陷阱

1. **`minRequestAmount` 的坑**：如果设置 `minRequestAmount=10`（最小请求数），但前 9 个请求全部异常——**不会触发熔断**，因为还没有达到最小统计样本。生产上设太小则容易误触发，设太大则反应延迟。

2. **HALF_OPEN 的"雪崩重演"**：探测成功→CLOSED→大量请求涌来→如果下游还没真正恢复→再次 OPEN→再次 HALF_OPEN→再次 CLOSED→循环。解决：加长 `retryTimeoutMs` 并配合预热策略（逐步增大 `minRequestAmount`）。

3. **慢调用比例的统计窗口不敏感**：慢调用比例基于 D1 滑动窗口，默认 `sampleCount=2`（500ms 一格）。如果慢调用是一阵一阵的（burst），可能在窗口缝隙中被"漏掉"→熔断不触发。提高 `sampleCount` 可增加精度。
