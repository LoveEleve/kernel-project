# D7: 热点参数限流 — ParamFlowSlot + CacheMap

> 源码位置: `sentinel-extension/sentinel-parameter-flow-control/src/main/java/com/alibaba/csp/sentinel/slots/block/flow/param/`
> 核心文件: `ParamFlowSlot.java` / `ParamFlowChecker.java`(300+行) / `ParamFlowRule.java` / `CacheMap`
> 前置: D3（流控）理解 QPS/线程数统计；D5（Node）理解统计节点体系

---

## 一、问题背景：流控管的是"资源总体"，热点参数管的是"某个值"

D3 的 `FlowRule` 管的是"`getUser` 这个资源整体的 QPS"——所有请求共享一个阈值。但如果**特定商品 ID 被刷**，需要单独限制 `getUser?itemId=999` 而不影响其他正常商品——这就是 `ParamFlowRule` 的场景。

Sentinel 通过从方法参数中提取**热点值**（`args[paramIdx]`），为每个热点值**独立维护**一个计数器（`CacheMap<参数值, TokenUpdateStatus>`），当某个值的 QPS 超限时只拦截该值，不影响其他值。

```
SphU.entry("getUser", EntryType.IN, 1, itemId)
  → ParamFlowSlot.checkFlow
    → rule.getParamIdx()=0 → value=args[0]="itemId_999"  // 提取第一个参数
    → CacheMap[itemId_999].getTokenCount() > threshold? → 是 → 拒绝
    → CacheMap[itemId_888].getTokenCount() > threshold? → 否 → 放行
```

---

## 二、ParamFlowSlot.entry：热点参数槽

**位置**: `sentinel-extension/.../ParamFlowSlot.java:L38-80`

```java
public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node,
                  int count, boolean prioritized, Object... args) throws Throwable {
    checkFlow(resourceWrapper, count, args);                            // L40
    fireEntry(context, resourceWrapper, node, count, prioritized, args); // L41
}

void checkFlow(ResourceWrapper resourceWrapper, int count, Object... args) 
        throws BlockException {
    if (args == null) return;                                           // L62
    if (!ParamFlowRuleManager.hasRules(resourceWrapper.getName()))      // L65
        return;   // 没有热点规则 → 跳过

    List<ParamFlowRule> rules = ParamFlowRuleManager.getRulesOfResource(resourceWrapper.getName());
    for (ParamFlowRule rule : rules) {                                  // L68
        applyRealParamIdx(rule, args.length);                           // L70: 处理负数索引
        ParameterMetricStorage.initParamMetricsFor(resourceWrapper, rule); // L73: 初始化统计

        if (!ParamFlowChecker.passCheck(resourceWrapper, rule, count, args)) {
            throw new ParamFlowException(resourceWrapper.getName(), ...); // L78
        }
    }
}
```

**`applyRealParamIdx` 处理负数索引**：如果 `paramIdx=-1`（表示最后一个参数），转换为 `args.length - 1`。支持从参数列表末尾提取热点值。

---

## 三、ParamFlowChecker.passCheck：参数提取 + 集群/本地分流

**位置**: `sentinel-extension/.../ParamFlowChecker.java:L45-72`

```java
public static boolean passCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, 
                                int count, Object... args) {
    if (args == null) return true;                                      // L47

    int paramIdx = rule.getParamIdx();                                  // L50: 从规则中读参数索引
    if (args.length <= paramIdx) return true;                           // L54: 索引越界 → 放行

    Object value = args[paramIdx];                                      // L57: 提取参数值

    // 支持 ParamFlowArgument 接口——调用方可自定义"热点值"的提取逻辑
    if (value instanceof ParamFlowArgument) {                           // L60
        value = ((ParamFlowArgument) value).paramFlowKey();
    }
    if (value == null) return true;                                     // L63

    if (rule.isClusterMode() && rule.getGrade() == FLOW_GRADE_QPS) {   // L66
        return passClusterCheck(resourceWrapper, rule, count, value);
    }
    return passLocalCheck(resourceWrapper, rule, count, value);         // L72
}
```

**`ParamFlowArgument` 接口的设计意图**：调用方传入的参数可能是一个复杂对象（如 `UserRequest`），而不是简单的 String。实现 `ParamFlowArgument.paramFlowKey()` 方法，从对象中提取关键字段作为热点值（如 `userRequest.getToken()`），而不是用整个对象的 `toString()`。

---

## 四、passLocalCheck：CacheMap 独立计数器

**位置**: `sentinel-extension/.../ParamFlowChecker.java:L75-130`

```java
private static boolean passLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, 
                                      int count, Object value) {
    // L80: 获取该资源+规则组合的统计指标
    ParameterMetric metric = ParameterMetricStorage.getParamMetric(resourceWrapper);

    // L127: 从 CacheMap 中取该参数值对应的 TokenUpdateStatus
    CacheMap<Object, AtomicReference<TokenUpdateStatus>> tokenCounters = 
        metric.getRuleStampedTokenCounter(rule);

    AtomicReference<TokenUpdateStatus> tokenStatusRef = tokenCounters.putIfAbsent(
        value, new AtomicReference<>(new TokenUpdateStatus()));

    // 根据 grade 做状态判断（QPS或线程数）
    // ... 超限则 return false
}
```

**CacheMap 的结构**：

| 维度 | 说明 |
|------|------|
| Key | 参数值（如 `itemId_999`） |
| Value | `AtomicReference<TokenUpdateStatus>`（QPS 令牌）或 `AtomicLong`（时间记录） |
| 淘汰策略 | **无自动淘汰**——热点值达到规则中的 `durationInSec` 后才移除统计 |
| 与 LeapArray 的区别 | LeapArray 是固定窗口（时间驱动），CacheMap 是值驱动（每个热点值独立计数） |

**为什么用 CacheMap 而非 LeapArray？**

LeapArray 是按时间索引的——"过去 1 秒内有多少请求"。热点参数是按**值**索引的——"过去 1 秒内 itemId_999 有多少请求"。如果用 LeapArray，需要 `LeapArray<Map<value, counter>>` 双重数据结构，复杂度飙升。CacheMap 直接按值索引，更简洁。

---

## 五、发散：Sentinel 热点参数 vs Redis 滑动窗口 vs 本地 CacheMap

| 维度 | Sentinel CacheMap | Redis 滑动窗口（Sorted Set） | 本地 Guava Cache |
|------|------|------|------|
| 统计粒度 | 参数值级别（每商品/每用户） | 参数值级别 | 参数值级别 |
| 内存开销 | `N 个热点值 × TokenUpdateStatus` | `N 个热点值 × ZSET 元素` | 同 Guava weight |
| 过期策略 | `durationInSec` 定时清理 | `ZREMRANGEBYSCORE` | `expireAfterWrite` |
| 分布式支持 | ❌ 单机 | ✅ | ❌ |
| 精度 | 微秒级（内存操作） | 毫秒级（网络 + 排序） | 微秒级 |

---

## 六、面试考点

**Q1: Sentinel 的热点参数限流如何实现？如何区分不同商品 ID？**

`ParamFlowChecker` 从 `args[rule.getParamIdx()]` 提取参数值，用 `CacheMap<参数值, AtomicReference<TokenUpdateStatus>>` 为每个值独立维护计数器。每个热点值独立统计 QPS——itemId_999 超限不影响 itemId_888。

**追问**: 为什么热点参数限流不用 LeapArray 而用 CacheMap？

LeapArray 是时间索引的固定窗口——适用"每个时间片均匀计数"。热点参数是值索引的——为每个值独立计数，用 CacheMap 直接映射，避免双重数据结构的复杂度。

---

## 七、生产陷阱

1. **CacheMap 无上限**：热点值到达 `durationInSec` 后才被清理——在高频参数场景下（如 userId 百万级），`CacheMap` 会无限膨胀，最终 OOM。必须配合 Sentinel Dashboard 监控热点值的数量。

2. **`args==null` 直接放行**：与 `AuthoritySlot` 的 `origin` 空值一样，如果 `SphU.entry` 没有传参数（`args` 为 null），热点规则全部跳过——"故障开放"设计。调用方必须确保参数正确传入。

3. **`paramIdx` 越界静默放行**：`args.length <= paramIdx` 时直接 return true——如果参数列表变短了（重构），热点规则不会报错只是失效，排查困难。
