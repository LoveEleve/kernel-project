# D3: 流控算法 — FlowSlot + FlowRuleChecker + 三种控制器

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/block/flow/`
> 核心文件: `FlowSlot.java` / `FlowRuleChecker.java`(209行) / `DefaultController.java`(85行) / `ThrottlingController.java`(167行) / `WarmUpRateLimiterController.java`(88行)
> 前置: D2（Slot链）理解 chain.entry 的执行路径；D1（滑动窗口）理解 node.passQps 的数据来源

---

## 一、问题背景：三种流控策略的取舍

Sentinel 一条 `FlowRule` 可以配置 3 种 grade：

| grade | 名称 | 含义 | 适用场景 |
|:--:|------|------|------|
| 1 | **QPS 直接拒绝** | 过去 1 秒的 pass 数量超过阈值 → 立即拒绝 | 保护下游服务不被打垮 |
| 1 | **QPS 匀速排队** | 过去 1 秒超限 → 计算等待时间 → sleep 后放行 | 削峰填谷（如秒杀流量排队） |
| 0 | **线程数隔离** | 当前正在处理的线程数超过阈值 → 立即拒绝 | 保护慢资源（如第三方 API 调用） |

QPS 和线程数的数据都来自 D1 的滑动窗口——`node.passQps()` 读的是 LeapArray 中最近 1 秒的统计值，`node.curThreadNum()` 读的是当前活跃线程计数器。

---

## 二、FlowSlot.entry：流控槽的入口

**位置**: `FlowSlot.java:L160-169`

```java
// L160: entry 方法（被 Slot 链调用）
public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, 
                  int count, boolean prioritized, Object... args) throws Throwable {
    checkFlow(resourceWrapper, context, node, count, prioritized);     // L162: 规则检查
    fireEntry(context, resourceWrapper, node, count, prioritized, args); // L164: 传递给下一个 Slot
}

// L167: 委托 FlowRuleChecker
void checkFlow(ResourceWrapper resource, Context context, DefaultNode node, 
               int count, boolean prioritized) throws BlockException {
    checker.checkFlow(ruleProvider, resource, context, node, count, prioritized); // L169
}
```

**执行时序**：`checkFlow` → 如果 block（抛 BlockException）→ `fireEntry` 不会执行 → 后续的 DegradeSlot、DefaultCircuitBreakerSlot 也不会执行——请求在流控槽就被终止了。

---

## 三、FlowRuleChecker：规则遍历与节点选择

**位置**: `FlowRuleChecker.java:L44-57`

```java
public void checkFlow(Function<String, Collection<FlowRule>> ruleProvider, 
                      ResourceWrapper resource, Context context, DefaultNode node, 
                      int count, boolean prioritized) throws BlockException {
    Collection<FlowRule> rules = ruleProvider.apply(resource.getName());   // L48
    for (FlowRule rule : rules) {                                          // L50
        if (!canPassCheck(rule, context, node, count, prioritized)) {      // L52
            throw new FlowException(rule.getLimitApp(), rule);             // L53
        }
    }
}
```

**多规则遍历**：一个资源可以配置多条 `FlowRule`（如：全天 QPS=1000 + 高峰时段 QPS=500）。所有规则必须**全部通过**才放行——只要一个不通过就抛 `FlowException`。

**canPassCheck 的分支决策**（`FlowRuleChecker.java:L64-76`）：

```java
public boolean canPassCheck(FlowRule rule, Context context, DefaultNode node, 
                            int acquireCount, boolean prioritized) {
    // L68: limitApp 为 null → 没有来源限制 → 直接放行
    // limitApp 不为 null 时才会做来源匹配（D6 章节详述）
    if (limitApp == null) {
        return true;
    }

    // L72: 集群模式？由 FlowRule.isClusterMode() 判断（不是全局 ClusterStateManager）
    if (rule.isClusterMode()) {
        return passClusterCheck(rule, context, node, acquireCount, prioritized);
    }
    // L75: 本地模式 → 走本地检查
    return passLocalCheck(rule, context, node, acquireCount, prioritized);
}
```

**passLocalCheck 的节点选择**（`FlowRuleChecker.java:L78-85`）：

```java
private static boolean passLocalCheck(FlowRule rule, Context context, DefaultNode node, 
                                      int acquireCount, boolean prioritized) {
    Node selectedNode = selectNodeByRequesterAndStrategy(rule, context, node);   // L80
    return rule.getRater()                                                       // L85
        .canPass(selectedNode, acquireCount, prioritized);
}
```

`selectNodeByRequesterAndStrategy`（L115）根据 `FlowRule.strategy` 选择不同的统计节点：

| strategy | 统计节点 | 含义 |
|:--:|------|------|
| 0（直接） | `node`（当前资源的 `ClusterNode`） | 该资源的所有请求共享阈值 |
| 1（关联） | `关联资源的 ClusterNode` | A 资源的流量超标 → 限制 B 资源 |
| 2（链路） | `context.getCurNode()`（调用树中的 `DefaultNode`） | 只有特定入口的调用才限流 |

**关联模式的生产场景**：秒杀时，订单服务 A 的 QPS 暴涨 → 关联策略限制库存服务 B 的 QPS → 减小库存服务压力 → 间接保护订单服务。

---

## 四、DefaultController：QPS/线程数快速拒绝

**位置**: `DefaultController.java:L44-76`

```java
public boolean canPass(Node node, int acquireCount, boolean prioritized) {
    int curCount = avgUsedTokens(node);                                  // L50: 读取当前统计值
    if (curCount + acquireCount > count) {                               // L51: 超过阈值
        if (prioritized && grade == RuleConstant.FLOW_GRADE_QPS) {       // L52: 优先请求？
            long currentTime = TimeUtil.currentTimeMillis();
            long waitInMs = node.tryOccupyNext(currentTime, acquireCount, count);
            if (waitInMs < OccupyTimeoutProperty.getOccupyTimeout()) {   // L57: 等待窗口内
                node.addWaitingRequest(currentTime + waitInMs, acquireCount);
                node.addOccupiedPass(acquireCount);
                // L62: 注意——优先请求不 sleep，而是抛 PriorityWaitException
                // 让上层调用方决定等待策略
                throw new PriorityWaitException(waitInMs);
            }
        }
        return false;                                                    // L65: 超过 → 拒绝
    }
    return true;                                                         // L67: 未超 → 通过
}

private int avgUsedTokens(Node node) {                                   // L71
    if (node == null) return DEFAULT_AVG_USED_TOKENS;                    // 不是 return 0！
    return grade == RuleConstant.FLOW_GRADE_THREAD                        // L75
        ? node.curThreadNum()                                            // 线程数模式 → 读活跃线程数
        : (int)(node.passQps());                                         // QPS 模式 → 读滑动窗口 QPS
}
```

**QPS 模式的决策流程**：
```
canPass(node, acquireCount=1)
  → avgUsedTokens → node.passQps()  → 读取 LeapArray 最近 1 秒的 pass 计数
  → curCount + 1 > 1000（阈值）？→ 是 → return false → FlowException
  → 否 → return true → 放行
```

**线程数模式的决策流程**：
```
canPass(node, acquireCount=1)
  → avgUsedTokens → node.curThreadNum()  → 读取当前正在处理的线程数
  → curCount + 1 > 200（阈值）？→ 是 → return false
  → 否 → return true
```

---

## 五、ThrottlingController：匀速排队（令牌桶模拟）

**位置**: `ThrottlingController.java:L66-133`

与 DefaultController 的"即时拒绝"不同，ThrottlingController 实现了**削峰填谷**：超限时不直接拒绝，而是计算当前请求应该等待的时间，`Thread.sleep` 后放行。

```java
public boolean canPass(Node node, int acquireCount) {
    long currentTime = TimeUtil.currentTimeMillis();
    long latestPassedTime = latestPassedTimeQps.get();                   // 上一次请求的通过时间

    // 计算本次请求的理想通过时间 = 上一次通过时间 + 间隔
    long expectTime = acquireCount * costTime + latestPassedTime;        

    if (expectTime <= currentTime) {                                      // 当前时间已过 → 立即放行
        if (latestPassedTimeQps.compareAndSet(latestPassedTime, currentTime)) {
            return true;
        } else {
            Thread.yield();                                              // CAS失败 → 重试
        }
    } else {
        // 当前请求来得太早 → 需要等待
        long waitTime = costTime + latestPassedTime - currentTime;
        if (waitTime > maxQueueingTimeMs) {                              // 等待超时 → 拒绝
            return false;
        } else {
            while (true) {                                                // CAS 循环
                latestPassedTime = latestPassedTimeQps.get();
                long newPassedTime = latestPassedTime + costTime;
                if (latestPassedTimeQps.compareAndSet(latestPassedTime, newPassedTime)) {
                    sleepMs(waitTime);                                    // ★ 休眠等待
                    return true;
                }
            }
        }
    }
}
```

**核心算法**：`costTime = 1.0 / count * 1000ms`。例如 QPS=5 → costTime=200ms → 每个请求间隔 200ms → 无论前端怎么并发打过来，后端最终以每秒 5 个的匀速消耗。

**与 DefaultController 的区别**：

| | DefaultController | ThrottlingController |
|------|------|------|
| 超限行为 | 立即拒绝 | 计算等待时间 → sleep → 放行 |
| 适用场景 | 保护下游（不让超标请求通过） | 削峰填谷（峰值排队，谷底放行） |
| 线程占用 | 不占用 | **占用**（sleep 期间线程不释放） |
| 用户体验 | 立即返回错误 | 延迟但成功 |

---

## 六、发散：Sentinel 流控 vs Guava RateLimiter vs Resilience4j

| 维度 | Sentinel | Guava RateLimiter | Resilience4j |
|------|------|------|------|
| 流控维度 | QPS + 线程数 + 关联 + 链路（4 维） | QPS 单一维度 | QPS 单一维度 |
| 引擎 | LeapArray 滑动窗口（实时非锁） | SmoothBursty/SmoothWarmingUp | 环形缓冲 |
| 等待策略 | 立即拒绝 / sleep 排队 / 预热 | sleep 排队 | 立即拒绝 / 可配置等待 |
| 规则热更新 | Nacos/文件 数据源 → 运行时动态生效 | 代码内 `RateLimiter.create(5)`，需重启 | 配置文件 + 动态生效 |
| 集群限流 | ✅（Token Server/Client 模式） | ❌ | ❌ |

---

## 七、面试考点

**Q1: Sentinel 流控算法有几种？warmUp 如何计算？**

三种：(1) DefaultController（QPS/线程数直接拒绝）；(2) ThrottlingController（匀速排队，costTime=1.0/count*1000ms）；(3) WarmUpRateLimiterController（冷启动预热，令牌生成速率从 lowFactor 逐步增加到稳定值）。warmUp 的核心是 `storedTokens`（存储的令牌越多，生成越慢——防止冷系统被突然打垮）。

**追问**: throttle 排队模式下，`maxQueueingTimeMs` 设太大有什么风险？

线程池会被耗尽——每个请求都在 `Thread.sleep(waitTime)` → 线程不释放 → Tomcat 线程池满 → 新请求无法接入。应配合超时控制（`DEFAULT_TIMEOUT`）和线程池调大。

**Q2: `selectNodeByRequesterAndStrategy` 的关联模式和链路模式有什么区别？**

关联：A→B 的调用关系。A 资源流量超标 → 限制 B 资源。链路：只有从特定入口进来的调用才受限（如从 `/api/v2` 入口调用的 `getUser` 才限流，从 `/api/v1` 调用的不限）。

---

## 八、生产陷阱

1. **线程数模式的统计误差**：`node.curThreadNum()` 来自 `StatisticSlot.entry` 的 `node.increaseThreadNum()` 和 `exit` 的 `decreaseThreadNum()`。如果业务代码在 entry 和 exit 之间抛了未捕获异常 → `decreaseThreadNum()` 不会被调用 → 线程数只增不减 → 最终所有请求都被限流。

2. **ThrottlingController 的"假放行"**：`costTime = 1.0/count * 1000` → 如果 count=2000（高 QPS），`costTime=0.5ms` → CAS 循环中多个线程可能同时到达同一微秒窗口 → 精度丢失 → 实际 QPS > 阈值。QPS 越大误差越明显——ThrottlingController 不适合极高 QPS 场景。

3. **关联模式的循环依赖死锁**：A 关联 B、B 关联 A → 任一资源触发阈值 → 两者互相限制 → 全部不可用。生产上关联关系必须是单向的，且最好通过配置中心审计。
