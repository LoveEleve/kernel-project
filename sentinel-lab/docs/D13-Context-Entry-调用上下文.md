# D13: Context/Entry 体系 — Sentinel 的调用上下文

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/context/`
> 核心文件: `ContextUtil.java`(250+行) / `Context.java`(201行) / `NullContext.java`(32行)
> 关联: `CtSph.java`(300+行,D2已分析) — Context + SlotChain = Sentinel 的执行环境
> 前置阅读: D2（Slot 链）理解 `chain.entry` 的执行环境

---

## 一、问题背景：为什么需要 Context？

`SphU.entry("sentinelApi")` 被调用时，Sentinel 需要知道：

1. **当前请求是谁发起的？** — origin 字段（如调用方服务名 `order-service`），用于流控的"关联模式"（order-service 的流量超标时，拦截它关联的 inventory-service 的流量）
2. **当前资源在调用树中的位置？** — `curEntry` 维护调用栈。`/api/A` 内部调了 `/api/B` → Context 通过 entry 链表记录"A→B"的嵌套关系
3. **同一个请求内的多次资源调用如何关联？** — 同一个 `Context` 对象贯穿整个请求生命周期

```
HTTP Request → [Thread: http-nio-28011-exec-1]
  Context.name = "sentinel_default_context"
  Context.origin = "mobile-app"
  
  SphU.entry("sentinelApi")
    → CtEntry(name="sentinelApi", parent=null)  ← curEntry
    → chain.entry(FlowSlot.entry / DegradeSlot.entry / ...)
    → [业务处理]
    → entry.exit()
  
  SphU.entry("backendCall")
    → CtEntry(name="backendCall", parent=previousEntry)
    → 嵌套在 sentinelApi 的 entry 内部
    → entry.exit()
  
  [Request 结束] → ContextUtil.exit() → curEntry.unlink()
```

**核心洞察**：一个 HTTP 请求 = 一个 Context = 一个 ThreadLocal 绑定。Context 不是线程安全的——它**只在当前线程内有效**，跨线程自动隔离。

---

## 二、ContextUtil：ThreadLocal + 名称表上限

**位置**: `ContextUtil.java:L50-58`

```java
public class ContextUtil {
    // L50: Context 存储在 ThreadLocal 中，每个线程独立
    private static ThreadLocal<Context> contextHolder = new ThreadLocal<>();

    // L55: 上下文名称 -> DefaultNode 的全局映射（复用统计节点）
    private static volatile Map<String, DefaultNode> contextNameNodeMap = new HashMap<>();

    // L57-58: 锁 + 空上下文兜底
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Context NULL_CONTEXT = new NullContext();
}
```

**为什么需要名称表上限？** 如果每次 `SphU.entry("newName")` 都创建新 Context（新名称）且不复用，`contextNameNodeMap` 会无限增长。`trueEnter()` 用 **`contextNameNodeMap.size() > Constants.MAX_CONTEXT_NAME_SIZE`（2000）** 兜底：超限 → `setNullContext()` + 返回 `NULL_CONTEXT`（空上下文跳过所有规则检查，保证不 OOM）。

| 机制 | 真实实现（1.8.8） |
|------|------|
| Context 存储 | `ThreadLocal<Context> contextHolder`（线程隔离） |
| 名称上限 | `contextNameNodeMap.size() > MAX_CONTEXT_NAME_SIZE`（**2000**）→ 返回 `NullContext` |
| 并发保护 | 双重检查 + `ReentrantLock`（`trueEnter`） |

---

## 三、ContextUtil.getContext()：从 ThreadLocal 读取

**位置**: `ContextUtil.java:L237-245`

```java
public static Context getContext() {
    Context context = contextHolder.get();            // L238: ThreadLocal 读取
    if (context == null) {
        context = NULL_CONTEXT;                       // L240: 兜底——返回 NullContext（跳过所有规则）
    }
    return context;
}
```

**NullContext（L58）**：`PrivateFinalClass NullContext extends Context`，`getName()` 返回 `Constants.CONTEXT_DEFAULT_NAME`。它的 `getCurNode()` 返回 `null`（无调用树节点），`getOrigin()` 返回空字符串。当 `CtSph.asyncEntryWithPriorityInternal` 检测到 `context instanceof NullContext` 时，**直接走 `asyncEntryWithNoChain` 路径**——跳过所有 Slot 链，无流控/熔断保护。

**NullContext 出现的三种场景**：
1. `contextCount >= MAX_CONTEXT_COUNT` → 不再创建新 Context，防内存溢出
2. `entryCount` 超过阈值 → 同上
3. ThreadLocal 从未被初始化（直接调 SphU.entry 而不先调 ContextUtil.enter）

---

## 四、ContextUtil.enter()：创建/复用 Context

**位置**: `ContextUtil.java:L112-161`

```java
public static Context enter(String name, String origin) {
    if (Constants.CONTEXT_DEFAULT_NAME.equals(name)) {                // L113
        throw new ContextNameDefineException("...sentinel_default_context can't be permit!");
    }

    return trueEnter(name, origin);                                    // L117
}

protected static Context trueEnter(String name, String origin) {
    Context context = contextHolder.get();                             // L121
    if (context == null) {
        // 当前线程还没有 Context → 首次创建
        if (contextCount.incrementAndGet() > MAX_CONTEXT_COUNT) {     // L124
            contextCount.decrementAndGet();
            setNullContext();                                          // L127: 超限 → 写 NullContext
            return NULL_CONTEXT;
        }        
        context = new Context(entryCount, name, origin);               // L131
        contextHolder.set(context);                                    // L136
    } else {
        // 已有 Context → 复用（相当于子调用）
        entryCount.incrementAndGet();                                  // 递增嵌套层级计数器
    }
    return context;
}
```

**ThreadLocal 三态**：

| ThreadLocal 值 | 含义 | getContext() 返回 |
|------|------|------|
| `null`（从未 set） | 线程还没有进入任何 Sentinel 保护 | `NULL_CONTEXT` |
| `NullContext` | 被强制设为空（溢出保护） | `NULL_CONTEXT` |
| 正常的 `Context` | 线程在执行受保护资源 | 正常 Context |

---

## 五、CtSph.asyncEntryWithPriorityInternal：Context 的消费者

**位置**: `CtSph.java:L64-111`（D2 已分析，这里聚焦 Context 相关逻辑）

```java
private AsyncEntry asyncEntryWithPriorityInternal(...) throws BlockException {
    Context context = ContextUtil.getContext();                              // L66: ★ 读 ThreadLocal
    if (context instanceof NullContext) {                                    // L67
        return asyncEntryWithNoChain(resourceWrapper, context);              // L70: ★ NullContext → 跳过所有规则
    }
    if (context == null) {
        context = InternalContextUtil.internalEnter(                         // L74
            Constants.CONTEXT_DEFAULT_NAME);
    }
    // ...
    ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);         // L82
    // ...
    AsyncEntry asyncEntry = new AsyncEntry(resourceWrapper, chain, context,  // L89
        count, args);
    try {
        chain.entry(context, resourceWrapper, null, count, prioritized, args); // L91
        // ...
    } catch (BlockException e1) {
        asyncEntry.exitForContext(context, count, args);                      // L98
        throw e1;
    }
    return asyncEntry;
}
```

**完整调用链**：

```
ContextUtil.enter("mobile-app")            // ① 创建 Context（ThreadLocal.set）
  → SphU.entry("sentinelApi")             // ② 请求入口
    → CtSph.asyncEntryWithPriorityInternal
      → ContextUtil.getContext()            // ③ 从 ThreadLocal 读 Context
      → lookProcessChain("sentinelApi")     // ④ 获取 Slot 链（D2 分析过）
      → new AsyncEntry(resource, chain, context) // ⑤ 创建 Entry（记录当前资源）
      → chain.entry(context, resource, ...)     // ⑥ 执行 Slot 链（D2 分析过）
        → FlowSlot.entry → FlowRuleChecker.checkFlow → [pass/block]
      → asyncEntry.exit()                    // ⑦ 释放 Entry（递减 entryCount）
  → ContextUtil.exit()                     // ⑧ 释放 Context（ThreadLocal.remove）
```

---

## 六、发散：Sentinel Context vs Spring SecurityContext vs 朴素 ThreadLocal

| 维度 | Sentinel Context | Spring SecurityContext | 朴素 ThreadLocal |
|------|------|------|------|
| 生命周期 | 请求开始→结束 | 请求开始→结束 | 线程创建→销毁 |
| 防泄漏机制 | `contextCount` + `entryCount` 双计数器防溢出 | `SecurityContextHolder.clearContext()` 需手动调 | **无**——ThreadLocal 泄漏是常见事故 |
| 嵌套调用 | `curEntry` 链表（A→B→C 调用树） | 无嵌套概念（一个请求一个 Principal） | 需手动维护 |
| 跨线程传递 | 不支持（设计如此：每个线程独立 Context） | `InheritableThreadLocal`（子线程可继承） | 需 `InheritableThreadLocal` |
| 超限行为 | 返回 `NullContext`（静默降级） | 无超限机制 | N/A |

**Sentinel Context 的"不跨线程"设计意图**：限流/熔断是**线程级别**的决策——线程 A 被限流不应该影响线程 B 的统计。如果 Context 通过 `InheritableThreadLocal` 跨线程传播，子线程会"继承"父线程的调用树，导致统计混乱（同一请求的多次异步调用被算成一次）。

---

## 七、面试考点

**Q1: Sentinel 的 Context 是什么？和 ThreadLocal 什么关系？**

Context 是 Sentinel 的"请求上下文"——存储当前请求的 origin、调用树节点（curEntry）、嵌套 Entry 链表。底层是 `ThreadLocal<Context>`（每个线程独立），线程 A 的流控决策不影响线程 B。

**追问**: 如果 Context 跨线程了会怎样？

子线程会继承父线程的调用树 → 统计混乱：同一请求的多次异步调用被重复计数 → QPS 统计偏大或偏小。Sentinel 的设计选择是"不跨线程"——异步调用需要自己创建新的 Context。

**Q2: NullContext 是什么？什么时候会出现？**

`NullContext` 是 Context 的空对象（Null Object Pattern），出现于：(1) Context 总数超过 `MAX_CONTEXT_COUNT`（默认 5000）；(2) entry 嵌套层级超过阈值；(3) ThreadLocal 从未被初始化。出现 NullContext 时，`CtSph.asyncEntryWithPriorityInternal` 走 `asyncEntryWithNoChain` 路径——**跳过所有 Slot 链，无流控/熔断保护**。这是一个"静默降级"策略（宁可放行也不阻塞），但生产上应监控 NullContext 的触发频率。

---

## 八、生产陷阱

1. **Context 泄漏**：`ContextUtil.enter()` 创建 Context，但 forget 调 `ContextUtil.exit()` → `contextHolder` 中的 ThreadLocal 永不释放 → `contextCount` 只增不减 → 达到 `MAX_CONTEXT_COUNT` 后所有新 Context 都变成 `NullContext` → **整站限流/熔断全部失效**。Tomcat 线程池复用会使泄漏累积。

2. **NullContext 静默降级**：当 `contextCount` 超限时，Sentinel 不报错也不打 WARN 日志，只是默默返回 NullContext → 所有规则跳过后**没有任何迹象**。运维应监控 `SentinelConfig.MAX_CONTEXT_COUNT` 和 `contextCount` 的 metric。

3. **异步跨线程调用**：`CompletableFuture.supplyAsync(() -> SphU.entry("backend"))`——新线程中 `contextHolder.get()` 为 null → 返回 NullContext → 异步调用不受限流保护。正确做法：在异步线程中手动 `ContextUtil.enter("async-context")` 创建新 Context。
