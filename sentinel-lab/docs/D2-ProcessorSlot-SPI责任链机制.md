# D2: ProcessorSlot 责任链 — Sentinel 的骨架

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/slotchain/`
> 核心文件: `ProcessorSlot.java`(77行) / `DefaultSlotChainBuilder.java`(54行) / `AbstractLinkedProcessorSlot.java`
> SPI 配置: `META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot`（定义 9 个 Slot 的顺序）

---

## 一、问题背景：为什么需要 Slot 链？

当 `/sentinel/api` 这个请求到来时，Sentinel 需要对它依次做：
```
构建调用树 → 初始化链路客户端 → 记录统计 → 检查黑白名单 → 检查系统负载 → 流控 → 熔断
```

如果这 9 步硬编码在 `CtSph.entry()` 里，每加一个新功能就要改入口代码——不可扩展，也无法热插拔。Sentinel 的答案是 **SPI + 责任链**：每个检查是一个 `ProcessorSlot`（槽），槽通过 `META-INF/services/` 配置文件声明并排序，运行时由 `SpiLoader` 加载并串成链。

```
请求进入（正向）：
  NodeSelectorSlot → ClusterBuilderSlot → LogSlot → StatisticSlot → AuthoritySlot
       → SystemSlot → FlowSlot → DegradeSlot → DefaultCircuitBreakerSlot
                                                                        ↓ [全部通过]
                                                                      执行业务方法
请求退出（反向）：
  DefaultCircuitBreakerSlot.exit → ... → StatisticSlot.exit（记录 RT + 递减活跃线程）
```

---

## 二、ProcessorSlot 接口：链中的每一环

**位置**: `ProcessorSlot.java:L27-77`（接口定义），4 个抽象方法：

```java
public interface ProcessorSlot<T> {
    // L41: 请求进入槽（正向：链头 → 链尾）
    void entry(Context context, ResourceWrapper resourceWrapper, T param, int count, 
               boolean prioritized, Object... args) throws Throwable;

    // L55: 触发下一个槽的 entry（fire = 传递给下一个）
    void fireEntry(Context context, ResourceWrapper resourceWrapper, Object obj, int count, 
                   boolean prioritized, Object... args) throws Throwable;

    // L66: 请求退出槽（反向：链尾 → 链头，业务执行完后的收尾）
    void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args);

    // L76: 触发下一个槽的 exit
    void fireExit(Context context, ResourceWrapper resourceWrapper, int count, Object... args);
}
```

**四方法的设计意图**：

| 方法 | 方向 | 调用时机 | 典型操作（以 StatisticSlot 为例） |
|------|:--:|------|------|
| `entry()` | 正向 | 请求进入槽时被调用 | 递增活跃线程数、记录开始时间 |
| `fireEntry()` | 正向 | `entry()` 内部主动调用 | 调用 `next.entry()` 传递给下一个 Slot |
| `exit()` | 反向 | 业务执行完毕后，从链尾返回 | 计算 RT、递减活跃线程数、记录 pass/block |
| `fireExit()` | 反向 | `exit()` 内部主动调用 | 调用 `next.exit()` 回传给上一个 Slot |

**双向设计的核心价值**：`entry()` 记录开始时间，`exit()` 用"当前时间 - 开始时间 = 完整 RT"——这只有双向链才能做到。单向链（如 Servlet Filter）在 `chain.doFilter()` 之后无法精确知道每个 Slot 的耗时。

---

## 三、DefaultSlotChainBuilder：SPI 驱动的链构建

**位置**: `DefaultSlotChainBuilder.java:L36-52`

```java
public class DefaultSlotChainBuilder implements SlotChainBuilder {
    @Override
    public ProcessorSlotChain build() {
        ProcessorSlotChain chain = new DefaultProcessorSlotChain();               // L40

        // L42: 从 META-INF/services/ 加载所有 ProcessorSlot 实现，按 @Spi(order) 排序
        List<ProcessorSlot> sortedSlotList = 
            SpiLoader.of(ProcessorSlot.class).loadInstanceListSorted();

        for (ProcessorSlot slot : sortedSlotList) {                              // L44
            if (!(slot instanceof AbstractLinkedProcessorSlot)) {                // L45
                RecordLog.warn("..."); continue;
            }
            chain.addLast((AbstractLinkedProcessorSlot<?>) slot);                // L49
        }
        return chain;                                                            // L52
    }
}
```

**关键设计**：

1. **SpiLoader.loadInstanceListSorted()**：从 `META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot` 加载所有实现类，反射实例化后按 `@Spi(order=-xxx)` 的 order 值排序。order 值越小越靠前（类似 Spring `@Order`）。9 个默认 Slot 的 order 分别是 `-1000`、`-2000`...`-9000`。

2. **AbstractLinkedProcessorSlot 类型检查 (L45)**：只有继承 `AbstractLinkedProcessorSlot` 的类才能加入链——因为它需要 `getNext()/setNext()` 方法的链表传递语义。纯 `ProcessorSlot` 实现无法在链中传递到下一个 Slot。

3. **链与资源的一一对应**：每个 `ResourceWrapper`（资源名 + EntryType 的组合）对应一个独立的 Slot 链实例。`CtSph.lookProcessChain` 内部用 `ConcurrentHashMap<ResourceWrapper, ProcessorSlotChain>` 缓存——首次访问构建，后续复用。

---

## 四、SPI 配置文件：9 个 Slot 的加载顺序

**位置**: `META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot`

```
com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot       ← ① 节点选择
com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot   ← ② 集群节点构建
com.alibaba.csp.sentinel.slots.logger.LogSlot                      ← ③ 异常日志
com.alibaba.csp.sentinel.slots.statistic.StatisticSlot             ← ④ 核心统计 ★
com.alibaba.csp.sentinel.slots.block.authority.AuthoritySlot       ← ⑤ 黑白名单
com.alibaba.csp.sentinel.slots.system.SystemSlot                   ← ⑥ 系统自适应
com.alibaba.csp.sentinel.slots.block.flow.FlowSlot                 ← ⑦ 流控 ★
com.alibaba.csp.sentinel.slots.block.degrade.DegradeSlot            ← ⑧ 熔断降级 ★
com.alibaba.csp.sentinel.slots.block.degrade.DefaultCircuitBreakerSlot ← ⑨ 熔断器状态
```

**解读**：

| 序号 | Slot | 核心职责 | D 类 |
|:--:|------|------|:--:|
| 1 | NodeSelectorSlot | 为当前资源创建/查找 `DefaultNode`，构建调用树 | D5 |
| 2 | ClusterBuilderSlot | 构建 `ClusterNode`（集群维度统计聚合） | D8 |
| 3 | LogSlot | `BlockException` 发生时记录日志 | D0 |
| 4 | **StatisticSlot** | **核心统计**：QPS/pass/block/RT 采集入口 | D1 |
| 5 | AuthoritySlot | 黑白名单授权（`AuthorityRule`） | D6 |
| 6 | SystemSlot | 系统自适应限流（Load/CPU/RT/线程数） | D10 |
| 7 | **FlowSlot** | **流控**：QPS/线程数/关联/链路 | D3 |
| 8 | **DegradeSlot** | **熔断降级**：慢调用/异常比例/异常数 | D4 |
| 9 | DefaultCircuitBreakerSlot | 熔断器状态转换（独立于 DegradeSlot 的物理切换） | D4 |

**为什么 StatisticSlot 排在 FlowSlot 前面？** 因为 StatisticSlot 在 `entry()` 中记录开始时间、递增活跃线程数——后续的 `FlowSlot` 需要读取这些统计数据（QPS/线程数）来判断是否限流。如果 StatisticSlot 排在 FlowSlot 之后，流控看到的永远是过期数据，无法实时限流。

---

## 五、CtSph.asyncEntryWithPriorityInternal：链的获取与执行

**位置**: `CtSph.java:L64-111`

这是 Sentinel 的**核心执行入口**——每一条 `SphU.entry("resourceName")` 调用最终都会走到这里：

```java
private AsyncEntry asyncEntryWithPriorityInternal(ResourceWrapper resourceWrapper, 
        int count, boolean prioritized, Object... args) throws BlockException {   // L64-65
    Context context = ContextUtil.getContext();                                    // L66
    if (context instanceof NullContext) {                                          // L67
        return asyncEntryWithNoChain(resourceWrapper, context);                    // L70
    }
    if (context == null) {                                                         // L73
        context = InternalContextUtil.internalEnter(Constants.CONTEXT_DEFAULT_NAME);
    }
    if (!Constants.ON) {                                                           // L78: 全局开关关闭
        return asyncEntryWithNoChain(resourceWrapper, context);
    }

    ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);              // L82: 获取链

    if (chain == null) {                                                           // L85: 链为空（缓存超限）
        return asyncEntryWithNoChain(resourceWrapper, context);
    }

    AsyncEntry asyncEntry = new AsyncEntry(resourceWrapper, chain, context, ...); // L89
    try {
        chain.entry(context, resourceWrapper, null, count, prioritized, args);    // L91: ★ 执行链
        asyncEntry.initAsyncContext();
        asyncEntry.cleanCurrentEntryInLocal();
    } catch (BlockException e1) {                                                  // L97
        asyncEntry.exitForContext(context, count, args);
        throw e1;                                                                  // 向外抛出：被限流/熔断
    } catch (Throwable e1) {                                                       // L102
        RecordLog.warn("Sentinel unexpected exception...", e1);
    }
    return asyncEntry;
}
```

**执行流程**：

```
SphU.entry("resource")
  → entryWithPriority(L117) → asyncEntryInternal(L112)
    → asyncEntryWithPriorityInternal(L64)
      → L66: ContextUtil.getContext() 获取调用上下文
      → L78: Constants.ON 检查全局开关（false → 跳过所有规则）
      → L82: lookProcessChain(resourceWrapper) 获取/构建链
      → L91: chain.entry() ← 真正执行 9 个 Slot 的 entry 方法
              → NodeSelectorSlot.entry → ... → FlowSlot.entry → [规则检查]
              → 通过 → 返回
              → 拒绝 → 抛出 BlockException → L97 捕获 → 抛给调用方
```

**lookProcessChain 的内部实现**（`CtSph.java:L194-205`）：

```java
ProcessorSlot<Object> lookProcessChain(ResourceWrapper resourceWrapper) {
    ProcessorSlotChain chain = chainMap.get(resourceWrapper);          // 先查缓存
    if (chain == null) {
        synchronized (LOCK) {
            chain = chainMap.get(resourceWrapper);                     // DCL 第二次查
            if (chain == null) {
                chain = SlotChainProvider.newSlotChain();              // → DefaultSlotChainBuilder.build()
                chainMap.put(resourceWrapper, chain);                  // 写入缓存
            }
        }
    }
    return chain;
}
```

DCL（Double-Checked Locking）：外层 `get` 避免不必要的同步；`synchronized` 块内再 `get` 一次防止并发重复构建。

---

## 六、发散：Sentinel vs Hystrix vs Resilience4j

| 维度 | Sentinel | Hystrix（已停更） | Resilience4j |
|------|------|------|------|
| 扩展机制 | **SPI + SlotChain（责任链）** | 继承 `HystrixCommand` | **函数式 + 装饰器链** |
| 执行入口 | `SphU.entry("name")` 返回 `Entry` | `new XxxCommand().execute()` | `decorateSupplier/Callable` |
| 可扩展粒度 | 槽级别（加一个新 Slot 即可） | 命令级别（需新建 Command 类） | 装饰器级别（加一个 Decorator） |
| 指标统计 | 内置滑动窗口（LeapArray） | HystrixRollingNumber | 环形缓冲（RingBitSet） |
| 并发模型 | 异步（AsyncEntry + 回调） | 同步（信号量/线程池隔离） | 同步/异步均可 |

**Sentinel 的 Slot 链模式优势**：当需要新增一个规则（如 OAuth2 Token 校验）时，只需：
1. 实现 `ProcessorSlot` 接口 + 继承 `AbstractLinkedProcessorSlot`
2. 在 `META-INF/services/` 配置文件中加一行 `com.xxx.MyAuthSlot`
3. 指定合适的 `@Spi(order)` 值

无需修改任何现有代码——这是 SPI 驱动的最大价值。

---

## 七、面试考点

**Q1: Sentinel 的 Slot 链如何构建？SPI 如何加载？**

`DefaultSlotChainBuilder.build()` 通过 `SpiLoader.of(ProcessorSlot.class).loadInstanceListSorted()` 从 `META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot` 加载所有实现，按 `@Spi(order)` 排序。9 个默认 Slot 的 order 从 `NodeSelectorSlot(-1000)` 到 `DefaultCircuitBreakerSlot(-9000)`。

**追问**: 为什么 `StatisticSlot` 必须在 `FlowSlot` 前面？

`StatisticSlot` 在 `entry()` 中递增活跃线程数、记录开始时间。`FlowSlot` 根据 QPS/线程数判断是否限流——如果 `StatisticSlot` 在后面，流控看到的是上一秒的静态数据，无法实时响应突发流量。

**Q2: Sentinel 的双向链（entry/exit）和 Servlet Filter 的单向链有什么区别？**

`entry/fireEntry` 是正向（链头→链尾），`exit/fireExit` 是反向（链尾→链头）。Servlet Filter 只有单向 `doFilter`，无法在业务执行完毕后做收尾（如记录 RT）。Sentinel 的 `StatisticSlot.exit()` 通过 `当前时间 - entry中记录的开始时间` 计算出精确 RT——这是 Filter 做不到的。

---

## 八、生产陷阱

1. **SPI 配置缺失**：如果 `META-INF/services/...ProcessorSlot` 文件被误删或遗漏 → `SpiLoader` 返回空列表 → `DefaultSlotChainBuilder.build()` 构建空链（但 `chain != null`）→ **所有请求无防护直接放行**。虽然 Sentinel 核心 jar 自带配置，但自定义打包时容易翻车。

2. **Slot 链缓存无失效机制**：`chainMap` 一旦构建就永久缓存。运行时动态添加的新 Slot（通过 SPI 重加载）不会自动更新——**必须重启应用**或调用 `CtSph.resetChainMap()` 清空缓存。

3. **order 值冲突**：两个自定义 Slot 设置相同 order 时，`SpiLoader` 的加载顺序取决于 JDK `ServiceLoader` 的迭代顺序（不确定）→ 加载顺序不可预测。解决：使用唯一 order 值，预留足够间隔（官方的 `-1000/-2000/.../-9000` 是好的示范）。

4. **`Constants.ON = false` 全局开关**：在生产中误触（如配置中心推送了错误配置）会导致 L78 检查通过 → 所有请求跳过 Slot 链 → **整站限流/熔断全部失效**。运维应监控此开关的状态。
