# D4: WeightedServiceInstanceListSupplier 权重展开机制

> 源码版本: spring-cloud-commons **v4.1.4**（tag；运行时对齐 SC 2023.0.3；4.3.2/5.x 新特性以注记标出）
> 源码路径: `spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/`
> 覆盖文件: `WeightedServiceInstanceListSupplier.java`(128行)、`WeightFunction.java`(41行)、`LazyWeightedServiceInstanceList.java`(147行)

---

## 一、整体机制：权重如何影响选择

Spring Cloud LoadBalancer 的权重实现不走"选择时计算权重"，而是走**展开（expand）**路线：

```
原始实例列表                   按权重展开后的列表              轮询选择
[ A(权重1), B(权重2) ]  →   [ A, B, B ]              →  依次选择 A, B, B, A, B, B...
```

权重 2 的实例在展开列表中出现 2 次，RoundRobinLoadBalancer 的轮询自然形成**加权轮询**：B 被选中的概率是 A 的 2 倍。

展开的职责在 `WeightedServiceInstanceListSupplier`（装饰器），展开的载体是 `LazyWeightedServiceInstanceList`（惰性列表）。

```
delegate.get()                     ← 原始实例列表
    ↓ map(expandByWeight)
WeightFunction.apply(instance)     ← 计算每个实例的权重
    ↓
LazyWeightedServiceInstanceList    ← 按权重惰性展开（GCD 归约 + 双队列轮询）
    ↓
RoundRobinLoadBalancer 轮询选择
```

---

## 二、WeightFunction — 权重计算接口

**位置**: `WeightFunction.java`（41 行）

```java
@FunctionalInterface
public interface WeightFunction {

    /**
     * Applies this function to the given service instance.
     * @param instance the service instance
     * @return the weight of service instance
     */
    int apply(ServiceInstance instance);
}
```

极简的函数式接口：输入一个实例，返回 int 权重。`@FunctionalInterface` 标注允许 Lambda 实现——自定义权重策略只需要一个 Lambda：

```java
// 示例：从 metadata 的 instance-weight 字段读取权重（需自行处理 null 和异常）
ServiceInstanceListSupplier.builder()
    .withWeighted(instance -> {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata == null) {
            return 1;
        }
        return Integer.parseInt(metadata.getOrDefault("instance-weight", "1"));
    })
    .build(context);
```

> 注意：自定义 `WeightFunction` 中抛出的异常（如 `NumberFormatException`）会被 `expandByWeight` 的 try-catch 兜底降级为默认权重 1，不会影响请求执行（见 3.3）。

---

## 三、WeightedServiceInstanceListSupplier — 权重装饰器

**位置**: `WeightedServiceInstanceListSupplier.java`（134 行）

```java
public class WeightedServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private static final Log LOG = LogFactory.getLog(WeightedServiceInstanceListSupplier.class);

    static final String METADATA_WEIGHT_KEY = "weight";   // metadata 中的权重 key
    static final int DEFAULT_WEIGHT = 1;                  // 默认权重

    private final WeightFunction weightFunction;

    private boolean callGetWithRequestOnDelegates;

    // 三个构造函数：默认 / 自定义 WeightFunction / +LoadBalancerClientFactory
    public WeightedServiceInstanceListSupplier(ServiceInstanceListSupplier delegate) {
        this(delegate, WeightedServiceInstanceListSupplier::metadataWeightFunction);
    }

    public WeightedServiceInstanceListSupplier(ServiceInstanceListSupplier delegate, WeightFunction weightFunction) {
        super(delegate);
        this.weightFunction = weightFunction;
    }

    public WeightedServiceInstanceListSupplier(ServiceInstanceListSupplier delegate, WeightFunction weightFunction,
            ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerClientFactory) {
        super(delegate);
        this.weightFunction = weightFunction;
        LoadBalancerProperties properties = loadBalancerClientFactory.getProperties(getServiceId());
        if (properties != null) {
            callGetWithRequestOnDelegates = properties.isCallGetWithRequestOnDelegates();
        } else {
            callGetWithRequestOnDelegates = true;
        }
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get().map(this::expandByWeight);
    }

    @Override
    public Flux<List<ServiceInstance>> get(Request request) {
        if (callGetWithRequestOnDelegates) {
            return delegate.get(request).map(this::expandByWeight);
        }
        return get();
    }
    // ...
}
```

### 3.1 三个构造函数

| 构造函数 | 场景 | callGetWithRequestOnDelegates |
|----------|------|:---:|
| `(delegate)` | 使用默认 `metadataWeightFunction`，最简单的接入方式 | **false（默认值）** |
| `(delegate, weightFunction)` | 自定义权重策略（C-01 手写 WeightFunction 走这个） | **false（默认值）** |
| `(delegate, weightFunction, factory)` | 自定义权重 + 从 `LoadBalancerClientFactory` 读取 `callGetWithRequestOnDelegates` 配置 | 读取配置（默认 true） |

**重要行为差异**：`callGetWithRequestOnDelegates` 字段默认 `false`，且**只有带 `factory` 的第三个构造函数会赋值**（源码如此，前两个构造函数没有初始化它）。后果：

- 用前两个构造函数创建的 `WeightedServiceInstanceListSupplier`，`get(Request)` **总是走 `get()` 无参路径**（第 84 行 `if (callGetWithRequestOnDelegates)` 恒为 false）——Request 永远不会传给内层 delegate
- 只有第三个构造函数（从 `LoadBalancerClientFactory.getProperties()` 读取配置，默认 true）才恢复 D2 4.11 分析的传递行为

`callGetWithRequestOnDelegates` 逻辑详见 D2 的 4.11。

### 3.2 默认权重函数：metadataWeightFunction

```java
static int metadataWeightFunction(ServiceInstance serviceInstance) {
    Map<String, String> metadata = serviceInstance.getMetadata();
    if (metadata != null) {
        String weightValue = metadata.get(METADATA_WEIGHT_KEY);
        if (weightValue != null) {
            return Integer.parseInt(weightValue);   // 从 metadata.weight 读取
        }
    }
    return DEFAULT_WEIGHT;   // 无 metadata 或未配置 → 默认 1
}
```

权重来源：**注册中心实例的 `metadata.weight` 字段**。在 Nacos 控制台给实例配置：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        metadata:
          weight: "2"     # 该实例权重 2（注意是字符串）
```

**注意 `Integer.parseInt` 的风险**：如果 metadata.weight 不是合法数字，会抛 `NumberFormatException`——但这个异常被 `expandByWeight` 的 try-catch 捕获并降级为默认权重（见下）。

### 3.3 expandByWeight — 权重展开核心

```java
private List<ServiceInstance> expandByWeight(List<ServiceInstance> instances) {
    if (instances.size() == 0) {
        return instances;   // 空列表直接返回
    }

    int[] weights = instances.stream().mapToInt(instance -> {
        try {
            int weight = weightFunction.apply(instance);
            if (weight <= 0) {
                // 权重 <= 0 → 降级为默认权重 1
                LOG.debug("The weight of the instance %s should be a positive integer, but it got %d, using %d as default",
                        instance.getInstanceId(), weight, DEFAULT_WEIGHT);
                return DEFAULT_WEIGHT;
            }
            return weight;
        } catch (Exception e) {
            // 权重计算抛异常（如 NumberFormatException）→ 降级为默认权重 1
            LOG.debug("Exception occurred during apply weight function to instance %s, using %d as default",
                    instance.getInstanceId(), DEFAULT_WEIGHT, e);
            return DEFAULT_WEIGHT;
        }
    }).toArray();

    return new LazyWeightedServiceInstanceList(instances, weights);
}
```

**两层防御**：

1. `weight <= 0` 检查——权重必须是正整数，非正数降级为 1
2. try-catch——`weightFunction.apply()` 抛任何异常（包括 `NumberFormatException`）都降级为 1

**任何异常都不会让请求失败**——权重计算失败的最坏结果就是"该实例按默认权重参与选择"，这符合容错设计。

---

## 四、LazyWeightedServiceInstanceList — 惰性权重列表

**位置**: `LazyWeightedServiceInstanceList.java`（152 行）

这是权重机制的核心数据结构。它继承 `AbstractList<ServiceInstance>`，但**不预先展开**整个权重列表——只在 `get(index)` 被调用时才逐步展开。

```java
class LazyWeightedServiceInstanceList extends AbstractList<ServiceInstance> {

    /* for testing */ final ServiceInstance[] expanded;

    private final Object expandingLock = new Object();

    private @Nullable WeightedServiceInstanceSelector selector;

    private volatile int position = 0;

    LazyWeightedServiceInstanceList(List<ServiceInstance> instances, int[] weights) {
        // 计算权重最大公约数（GCD），以及展开后的总元素数
        int greatestCommonDivisor = 0;
        int total = 0;
        for (int weight : weights) {
            greatestCommonDivisor = greatestCommonDivisor(greatestCommonDivisor, weight);
            total += weight;
        }
        expanded = new ServiceInstance[total / greatestCommonDivisor];
        selector = new WeightedServiceInstanceSelector(instances, weights, greatestCommonDivisor);
    }
    // ...
}
```

### 4.1 GCD 归约 — 为什么展开前先求最大公约数

假设权重 `[2, 4]`：

- 不归约：展开列表长度 = 2 + 4 = 6 → `[A, A, B, B, B, B]`
- GCD 归约：GCD(2,4) = 2，展开长度 = (2+4)/2 = 3 → `[A, B, B]`

**归约后比例不变**（A:B = 1:2），但列表长度缩短了。权重 `[100, 200, 300]` 归约后变成 `[1, 2, 3]`，展开长度从 600 降到 6——**内存节省 100 倍**。

```java
static int greatestCommonDivisor(int a, int b) {
    int r;
    while (b != 0) {
        r = a % b;
        a = b;
        b = r;
    }
    return a;   // 辗转相除法（欧几里得算法）
}
```

### 4.2 惰性展开 — get(index) 才计算

```java
@Override
public ServiceInstance get(int index) {
    if (index >= position) {
        synchronized (expandingLock) {
            for (; position <= index && position < expanded.length; position++) {
                if (selector != null) {
                    expanded[position] = selector.next();   // 逐步展开
                }
            }
            if (position == expanded.length) {
                selector = null;   // 展开完毕，释放 selector 供 GC
            }
        }
    }
    return expanded[index];
}
```

**惰性的价值**：RoundRobinLoadBalancer 每次 `choose()` 只调用 `instances.get(pos % instances.size())`——**只访问一个下标**。如果不惰性，每次选择都要展开整个列表（O(总权重)）；惰性后第一次访问只展开到目标下标（O(index)），且后续选择复用已展开的部分。

**线程安全**：
- `expandingLock`（synchronized）——保证展开操作的原子性，多个线程不会重复展开同一位置
- `position`（volatile）——读端无需加锁即可安全读取已展开区域

**为什么无锁读是安全的（happens-before 链）**：`expanded` 数组本身不是 volatile，元素写入发生在 synchronized 块内。读者走 `index < position` 分支直接返回 `expanded[index]` 时没有进入同步块——安全性依赖 volatile 的传递性：

```
写者线程:  expanded[i] = selector.next()   （synchronized 块内写）
           position++                       （volatile 写，happens-before 于后续 volatile 读）
读者线程:  读 position                      （volatile 读，看到新值）
           return expanded[i]               （安全读取，i < position）
```

写者的 `position++`（volatile 写）与读者的 `position` 读取（volatile 读）建立 happens-before，再通过程序顺序传递到 `expanded[i]` 的写入——读者必然能看到已展开的元素。这就是"volatile + synchronized 组合"的经典模式。

### 4.3 WeightedServiceInstanceSelector — 双队列加权轮询

```java
static class WeightedServiceInstanceSelector {

    Queue<Entry> active;    // 活跃队列（还有剩余次数）
    Queue<Entry> expired;   // 耗尽队列（本轮已选完）

    WeightedServiceInstanceSelector(List<ServiceInstance> instances, int[] weights, int greatestCommonDivisor) {
        active = new ArrayDeque<>(instances.size());
        expired = new ArrayDeque<>(instances.size());
        int i = 0;
        for (ServiceInstance instance : instances) {
            active.offer(new Entry(instance, weights[i] / greatestCommonDivisor));  // 归约后的权重
            i++;
        }
    }

    ServiceInstance next() {
        if (active.isEmpty()) {
            Queue<Entry> temp = active;     // 双队列切换：expired 变 active
            active = expired;
            expired = temp;
        }

        Entry entry = active.poll();
        if (entry == null) {
            return null;
        }

        entry.remainder--;
        if (entry.remainder == 0) {
            entry.remainder = entry.weight;   // 本轮耗尽 → 重置并移入 expired
            expired.offer(entry);
        } else {
            active.offer(entry);              // 还有剩余 → 放回 active 末尾
        }
        return entry.instance;
    }

    static class Entry {
        final ServiceInstance instance;
        final int weight;       // 归约后的权重
        int remainder;          // 剩余次数
        Entry(ServiceInstance instance, int weight) {
            this.instance = instance;
            this.weight = weight;
            remainder = weight;
        }
    }
}
```

**算法推演**（权重 `[A:1, B:2]`，GCD 归约后不变）：

```
初始化:  active = [A(1), B(2)]    expired = []

next() ①: 取 A → remainder 1→0 → 移入 expired → 返回 A
          active = [B(2)]    expired = [A(1)]
next() ②: 取 B → remainder 2→1 → 放回 active 末尾 → 返回 B
          active = [B(1)]    expired = [A(1)]
next() ③: 取 B → remainder 1→0 → 移入 expired → 返回 B
          active = []        expired = [A(1), B(2)]
next() ④: active 为空 → 切换队列（expired 变 active，原 active 变 expired）
          active = [A(1), B(2)]    expired = []
          继续下一轮...
```

**输出序列**：A, B, B, A, B, B, ...——完美的加权轮询，B 出现频率是 A 的 2 倍。

**双队列切换的正确性**：切换只发生在 active 空时，此时 expired 恰好包含所有 Entry 且 remainder 都已重置为 weight——等价于"一轮结束，重新开始"。

---

## 五、完整链路

```
① Nacos/Eureka 注册中心
    ↓ 实例 metadata.weight
② DiscoveryClientServiceInstanceListSupplier.get()
    ↓ List<ServiceInstance>: [A, B]
③ WeightedServiceInstanceListSupplier.expandByWeight()
    ↓ WeightFunction 计算 → weights [1, 2]
④ LazyWeightedServiceInstanceList (GCD 归约 → 长度 3)
    ↓ 惰性展开序列: [A, B, B]
⑤ RoundRobinLoadBalancer.getInstanceResponse()
    ↓ pos % 3 → 依次选 A, B, B, A, B, B...
⑥ 加权轮询生效
```

**与其他装饰器的配合**：`Weighted` 应放在**过滤类装饰器（HealthCheck/Zone/Hint）的外层**——即过滤类在内层先执行，Weighted 在外层后执行展开。这样只有"过滤后存活"的实例才被展开，避免给最终会被过滤的实例浪费展开空间：

```
（内层）                                    （外层）
DiscoveryClient → Caching → HealthCheck → Weighted → RoundRobinLoadBalancer
            先拉取      先缓存      先过滤       后展开         最后选择
```

对应 D3 的配置：`spring.cloud.loadbalancer.configurations=weighted` 时默认链是 `[Discovery → Caching → Weighted]`（无过滤层）；如需组合，自定义 Builder 把 `withHealthChecks()` 加在 `withWeighted()` 之前。

---

## 六、面试考点

1. **权重展开 vs 选择时加权？**
   展开路线把"加权"转化为"列表中出现次数"，复用 RoundRobin 的轮询逻辑，无需实现新的选择算法。代价是列表变长，但 GCD 归约 + 惰性展开把内存和时间开销降到最低。

2. **GCD 归约解决了什么？**
   权重 `[100, 200, 300]` 展开长度 600，归约后 `[1, 2, 3]` 长度 6——比例不变，空间省 100 倍。

3. **惰性展开如何工作？**
   `get(index)` 只在需要时展开到目标下标，`synchronized + volatile` 保证并发安全，展开完毕后释放 selector 供 GC。

4. **双队列如何实现加权轮询？**
   active 队列存"还有剩余次数的 Entry"，remainder 减到 0 移入 expired；active 空时两队列交换，等价于新一轮开始。输出序列严格按权重比例分布。

5. **权重异常如何兜底？**
   权重 ≤ 0 或 weightFunction 抛异常 → 降级为默认权重 1，不影响请求执行。
