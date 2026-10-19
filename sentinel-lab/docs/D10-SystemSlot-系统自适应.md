# D10: 系统自适应限流 — SystemSlot + SystemStatusListener

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/system/`
> 核心文件: `SystemSlot.java` / `SystemRule.java`(193行) / `SystemRuleManager.java` / `SystemStatusListener.java`(100行)
> 前置: D3（流控）理解 QPS/线程数限流；D5（Node）理解 ClusterNode 的总量统计

---

## 一、问题背景：入口流控管不了系统整体健康

D3 的 `FlowRule` 管的是**单个资源**的 QPS（"getUser 这个接口来了多少请求"）。但如果**多个资源同时被压**——getUser 在限流中、createOrder 也在限流中——整体系统的 CPU/Load 已经爆了，但每个接口单独看都没超标。Sentinel 通过 `SystemRule` 从 **JMX 采集系统级指标**（Load1/CPU/RT/线程数/QPS），**全局保护**整个应用实例。

```
JMX OperatingSystemMXBean
    → SystemStatusListener.getSystemLoadAverage()  [后台线程每秒采集]
        → SystemRuleManager.checkSystem()
            → 5 个阈值任一项超标 → 抛 SystemBlockException → 所有资源被拦截
```

---

## 二、SystemRule：5 个全局阈值

**位置**: `SystemRule.java:L38-50`

```java
public class SystemRule extends AbstractRule {
    private double highestSystemLoad = -1;    // L43: Load1 阈值（-1=不限制）
    private double highestCpuUsage = -1;      // L47: CPU 使用率阈值
    private double qps = -1;                  // L48: 全局入口 QPS 阈值
    private long avgRt = -1;                  // L49: 全局平均 RT 阈值
    private long maxThread = -1;              // L50: 全局并行线程数阈值
}
```

**-1 的设计意图**：`-1` = "不限制此维度"。5 个阈值可以**任意组合**——比如只监控 Load1 和 CPU，不管 QPS 和线程数。

---

## 三、SystemStatusListener：JMX 指标采集

后台线程每秒采集一次系统指标：

```java
public class SystemStatusListener implements Runnable {
    volatile double currentLoad = -1;                                    // L33

    @Override
    public void run() {
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(
            OperatingSystemMXBean.class);
        currentLoad = osBean.getSystemLoadAverage();                     // L53
        // 采集 CPU、RT 等其他指标...
    }
}
```

**为什么用 JMX 而非 `/proc/loadavg`？** JMX 是 JDK 标准 API，跨平台（Linux/Windows/macOS 都能跑）。`/proc/loadavg` 只存在于 Linux，且需要文件读取权限。Sentinel 通过 JMX 的 `OperatingSystemMXBean.getSystemLoadAverage()` 获取 Load1 指标。

---

## 四、SystemRuleManager.checkSystem：全局闸门

```java
public static void checkSystem(ResourceWrapper resourceWrapper, int count) 
        throws BlockException {
    SystemRule rule = ... // 获取唯一的一条 SystemRule（全局只有一条）
    if (rule.getHighestSystemLoad() > 0) {
        double currentLoad = SystemStatusListener.getCurrentLoad();
        if (currentLoad > rule.getHighestSystemLoad()) {
            throw new SystemBlockException(...);
        }
    }
    // qps / avgRt / maxThread 同理...
}
```

**与 FlowRule 的关键区别**：FlowRule 需要 `selectNodeByRequesterAndStrategy` 选择统计节点（DefaultNode vs ClusterNode）。SystemRule 不需要——它只读全局的 JMX 指标，不依赖任何 Node。

---

## 五、面试考点

**Q: Sentinel 系统自适应限流和资源级流控的区别？**

资源级流控（FlowRule）管单个接口的 QPS/线程数——"getUser 不超过 1000 QPS"。系统自适应限流（SystemRule）管整个应用实例的 Load/CPU/QPS/RT/线程数——"整个应用 Load1 > 4 时拦截所有资源"。前者是精确定点防控，后者是全局兜底保护。

**追问**: 为什么 SystemRule 只需要一条规则（全局只有一条）？

因为 Load1/CPU 是实例级别的全局指标——不可能"getUser 调用时 CPU=80%、createOrder 调用时 CPU=20%"。一张规则覆盖所有资源，简单直接。

---

## 六、生产陷阱

1. **容器环境的 Load1 失真**：Docker/K8s 容器内 `getSystemLoadAverage()` 读取的是**宿主机的 Load1**，不是容器的。宿主机 64 核、Load1=4（仅 6%），容器只有 2 核——如果阈值设 Load1=4，触发不了（宿主机 Load 根本没上去）。**必须用 `highestCpuUsage` 替代 `highestSystemLoad` 在容器环境中。**

2. **JMX 指标采集延迟**：`SystemStatusListener` 每秒采集一次——当前实际 Load1=10，但上次采集还是 1 秒前的 Load1=3 → 1 秒内流量全部放行 → 系统瞬间崩。在流量剧增场景下（如秒杀），1 秒的采集间隔可能太长了。
