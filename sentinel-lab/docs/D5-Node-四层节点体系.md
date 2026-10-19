# D5: Node 体系 — 四层节点的统计聚合

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/node/`
> 核心文件: `Node.java`(接口,200+行) / `StatisticNode.java`(实现) / `DefaultNode.java` / `ClusterNode.java` / `EntranceNode.java` / `OccupySupport.java`
> 前置: D1（滑动窗口）理解 passQps 的数据来源；D2（Slot链）理解 NodeSelectorSlot 如何创建节点

---

## 一、问题背景：为什么需要四层 Node？

一条 `FlowRule` 需要知道"过去 1 秒这个资源来了多少请求"——这个数据来自 Node。但"这个资源"在不同场景下有不同含义：

| 场景 | 统计维度 | Node 类型 |
|------|------|------|
| 流控规则 `grade=QPS` | 单个资源的所有请求总和 | `ClusterNode` |
| 流控规则 `strategy=链路` | 从特定入口来的请求 | `DefaultNode` |
| Sentinel Dashboard 显示的调用树 | 按入口分组的请求量 | `EntranceNode` |
| 熔断规则 `慢调用比例` | 单个资源的 RT 统计 | `StatisticNode` 基类 |

Sentinel 用**四层继承体系**来区分这些维度：

```
                    OccupySupport (接口——优先占用)
                         ↑
                    Node (接口——统计数据 API)
                         ↑
                  StatisticNode (实现——LeapArray 读写)
                    ↙         ↘
          DefaultNode           ClusterNode
     (资源+调用入口维度)      (纯资源维度，全集群聚合)
               ↑
         EntranceNode
     (入口维度，Dashboard 展示)
```

---

## 二、Node 接口：统计数据的 API 契约

**位置**: `Node.java:L33-197`

```java
public interface Node extends OccupySupport {
    // === 实时快照（读 LeapArray） ===
    double passQps();        // 过去 1 秒通过的 QPS
    double blockQps();       // 过去 1 秒拒绝的 QPS
    double totalQps();       // passQps + blockQps
    double avgRt();          // 过去 1 秒平均响应时间
    int curThreadNum();      // 当前活跃线程数
    double successQps();     // 过去 1 秒成功 QPS
    double maxSuccessQps();  // 历史最大成功 QPS

    // === 写操作（StatisticSlot 调用） ===
    void addPassRequest(int count);        // 放行请求 → pass+1
    void addRtAndSuccess(long rt, int success);  // 请求完成 → RT+1, 成功数+1
    void increaseThreadNum();              // 开始处理 → 活跃线程+1
    void decreaseThreadNum();              // 处理完毕 → 活跃线程-1
}
```

**读写分离设计**：
- 读（`passQps` 等）——直接从 D1 的 LeapArray 滑动窗口中取最近 1 秒的快照。无锁。
- 写（`addPassRequest` 等）——通过 `currentWindow().value().getPass().add(1)` 写入 `MetricBucket` 的 `LongAdder`。CAS 无锁。

---

## 三、StatisticNode：Node 接口的真实实现

**位置**: `StatisticNode.java:L90`

```java
public class StatisticNode implements Node {
    // L94: 秒级滑动窗口（QPS/RT 等实时指标）
    private transient volatile Metric rollingCounterInSecond = 
        new ArrayMetric(SampleCountProperty.SAMPLE_COUNT, IntervalProperty.INTERVAL);

    // L103: 分钟级滑动窗口（Dashboard 展示的历史数据，enableOccupy=false）
    private transient Metric rollingCounterInMinute = 
        new ArrayMetric(60, 60 * 1000, false);

    // L108: 当前活跃线程数——LongAdder（高并发下比 AtomicInteger 吞吐更高）
    private LongAdder curThreadNum = new LongAdder();
}
```

**为什么是两层滑动窗口？**

| 窗口 | sampleCount | intervalInMs | 用途 |
|------|:--:|:--:|------|
| `rollingCounterInSecond` | 2（可配置） | 1000ms | **流控判断**（实时性要求高，2 格够用） |
| `rollingCounterInMinute` | 60 | 60000ms | **Dashboard 展示**（历史趋势，1 分钟 60 格） |

**curThreadNum 为什么不用滑动窗口？**

线程数是**瞬间值**而非时间累计值——当前有多少线程正在处理，只有"加"和"减"两个原子操作。用 `AtomicInteger` 最直接，不需要窗口统计。

---

## 四、DefaultNode vs ClusterNode：同资源的两种聚合方式

**DefaultNode**：`资源名 + 入口 Context` 的唯一键。同名的 `SphU.entry("getUser")` 从不同入口（`/api/v1` vs `/api/v2`）进入时，创建**不同的** `DefaultNode`。

```java
// DefaultNode 的唯一标识 = 资源名 + Context
public class DefaultNode extends StatisticNode {
    private final ResourceWrapper id;      // 资源名
    private volatile Set<Node> childList;  // 子节点（调用树）
    private ClusterNode clusterNode;       // 指向所属的 ClusterNode
}
```

**ClusterNode**：**纯资源维度**——同名的 `SphU.entry("getUser")` 不管从哪个入口进入，都聚合到**同一个** `ClusterNode`。

```java
public class ClusterNode extends StatisticNode {
    private final String name;  // 资源名
    // 所有同名的 DefaultNode 都指向同一个 ClusterNode
}
```

**"链路"流控依赖 DefaultNode**：

```
QPS 限流：grade=QPS, strategy=DIRECT → 读 ClusterNode.passQps()
           → 所有入口的 getUser 请求共享 1000 QPS 阈值

链路限流：grade=QPS, strategy=CHAIN → 读 DefaultNode.passQps()
           → 只有 /api/v2 入口的 getUser 受限制
```

---

## 五、EntranceNode：Dashboard 的入口节点

**位置**: `EntranceNode.java:L39`

```java
public class EntranceNode extends DefaultNode {
    public EntranceNode(ResourceWrapper id, ClusterNode clusterNode) {
        super(id, clusterNode);
    }

    @Override
    public double avgRt() {
        // 入口节点的 avgRt = 所有子节点 avgRt 的总和
        double total = 0;
        for (Node node : getChildList()) {
            total += node.avgRt();
        }
        return total / getChildList().size();
    }
}
```

EntranceNode 本质上是一个**根节点**——Dashboard 的调用树入口。`ContextUtil.enter("mobile-app")` 创建 `EntranceNode("mobile-app")`，其下挂载的 `DefaultNode("getUser")` / `DefaultNode("createOrder")` 作为子节点。

---

## 六、发散：Sentinel Node 体系 vs Spring Boot Actuator metrics

| 维度 | Sentinel Node | Micrometer + Spring Boot Actuator |
|------|------|------|
| 统计粒度 | 资源名入口 + 全集群聚合 + 入口维度（三层） | Tag 驱动的多维度 |
| 窗口机制 | LeapArray 滑动窗口（秒+分钟双层） | StepCounter/StepTimer（步进窗口） |
| 线程安全性 | AtomicReferenceArray + CAS + LongAdder | Micrometer 的 MeterRegistry 内部加锁 |
| 用途 | 流控/熔断的**实时决策** | 监控看板的**事后分析** |

**核心区别**：Sentinel Node 需要毫秒级响应（流控判断不能延迟），所以用无锁 CAS + LongAdder。Actuator metrics 面向秒级甚至分钟级采集，精度要求低得多。

---

## 七、面试考点

**Q1: DefaultNode 和 ClusterNode 的区别？链路限流用的是哪个？**

`DefaultNode` = 资源名 + 入口 Context（细粒度），`ClusterNode` = 纯资源名（粗粒度）。链路限流（strategy=CHAIN）读 `DefaultNode.passQps()`——只有从特定入口进来的请求才限流；QPS 限流（strategy=DIRECT）读 `ClusterNode.passQps()`——所有入口的该资源请求共享阈值。

**追问**: 为什么 EntranceNode 继承 DefaultNode 而非 StatisticNode？

EntranceNode 需要维护子节点列表（调用树结构）——这是 DefaultNode 才有的能力（`getChildList()`）。StatisticNode 只有统计方法，没有树结构。而且 EntranceNode 的 `avgRt` 聚合了所有子节点的平均值，DefaultNode 的树遍历正好满足这个需求。

**Q2: StatisticNode 的两层滑动窗口（秒级+分钟级）为什么 sampleCount 不同？**

秒级窗口 `sampleCount=2`（500ms 一格）——流控判断需要较低的延迟，2 格足够。分钟级窗口 `sampleCount=60`（1s 一格）——Dashboard 展示需要细粒度的历史数据，60 格提供 1 秒精度的 1 分钟趋势。分钟级窗口的 `addPass` 开销是秒级窗口的 30 倍（60/2），但 Dashboard 不需要实时性，可以接受。

---

## 八、生产陷阱

1. **`curThreadNum` 泄漏**：D3 中提过——如果业务代码在 `entry` 和 `exit` 之间抛了未捕获异常，`decreaseThreadNum` 不会被调用 → `LongAdder` 只增不减 → 最终所有请求被限流。`LongAdder.sum()` 没有 `reset` 方法，只能靠重启恢复。

2. **分钟级窗口的内存膨胀**：`rollingCounterInMinute` 维护了 60 个 `MetricBucket`——每个资源一个 `StatisticNode`。如果有 1000 个不同入口的 `SphU.entry("xxx")`，内存占用为 1000 × 60 × MetricBucket(约 200b) = ~12MB——这还不算 `ClusterNode`。生产上应限制 `MAX_CONTEXT_COUNT`（D13），防止入口数无限膨胀。

3. **DefaultNode 不释放**：`DefaultNode` 创建后永不销毁——只要有新的入口名出现，就会创建新的 DefaultNode 并缓存到 `ClusterNode` 的 Map 中。长时间运行下，如果动态生成资源名（如 `getUser:123`、`getUser:456`），Node 数量会无限增长。
