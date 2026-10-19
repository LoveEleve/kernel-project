# LB-09 自定义 LB 与生产案例

> 用途：预设不够用时怎么扩——**扩展点四个位置** + xhs 生产的两件自研（最少连接 / zone）完整拆解
> 对应系列：**负载均衡（SCLB）专题 · 09**（生产篇第一篇）
> 口径：SCLB 4.1.4；案例代码来自 xhs-project（`my-xhs-common`）
> 素材：xhs-project 源码（`LeastConnectionsLoadBalancer` / `LeastConnectionsLifecycle` / `ZoneLoadBalancerConfiguration`）· 网关课程 [L16](../gateway/L16-custom-loadbalancer.md)（自研 supplier 装配）/ [xhs-gateway-zone.md](../gateway/xhs-gateway-zone.md)（zone 逐行解读）· `loadbalancer-lab` D2/D6（装饰器链 / Lifecycle）
> 前置：03（算法）/ 05（链）/ 06（权重预热）/ 08（哈希方向）；后续：10（可观测·收口）

---

## 0. 例子先行：xhs 为什么自研？

三个现成算法解决不了的生产问题：

```text
① 慢请求堆积：某台实例上已有 20 个慢请求没回，轮询还把新请求继续发给它 → 越堆越多
② 冷启动打爆：新实例刚上线（JIT 没热、缓存空），一上来按均权接流量 → 容易崩
③ 跨机房延迟：北京机房的服务被广州的调用方轮询到 → 跨区延迟
```

xhs 的答案：**最少连接（① + ②）+ zone 优先（③）**——都是"预设之外"的自研件，本课拆开讲。

---

## 1. 掌握卡（3 条）

1. **扩展点就四个位置**：**① 挑一个**（`ReactorServiceInstanceLoadBalancer`——换算法）/ **② 改集合**（`ServiceInstanceListSupplier`——加滤镜，L16）/ **③ 组装**（`@LoadBalancerClients(defaultConfiguration=…)` 或 `@LoadBalancerClient(configuration=…)` + Builder）/ **④ 观测**（`LoadBalancerLifecycle` 钩子）。
2. **自研分两种难度**："滤镜型"（supplier，简单——只改列表）vs "**决策型**"（算法，得自己拿活跃数/指标——xhs 最少连接就是后者，配 Lifecycle 才能数出活跃数）。
3. **生产自研三要素**：**预热**（冷启动降权）+ **活跃数统计**（Lifecycle 钩子 +1/-1）+ **兜底**（空列表给 `EmptyResponse`、并列随机）——每一样 xhs 代码里都有对应。

---

## 2. 扩展点四个位置（含骨架代码）

| 位置 | 接口 | 干什么 | 本项目实例 |
|---|---|---|---|
| ① 算法 | `ReactorServiceInstanceLoadBalancer#choose` | 从列表**挑一个** | xhs 最少连接；一致性哈希（08） |
| ② 集合 | `ServiceInstanceListSupplier` | **改列表**（滤镜链） | L16 gray/zone；hint；subset |
| ③ 组装 | `@LoadBalancerClients(defaultConfiguration)/@LoadBalancerClient(configuration)` | 装进 **LB 子上下文** | L16 用 per-service；xhs 用"全服务默认" |
| ④ 观测 | `LoadBalancerLifecycle` | 选实例前/后、完成时的钩子 | xhs 活跃数统计；官方 MicrometerStats（10） |

**① 自研算法的骨架**（xhs 同款结构）：

```java
public class MyLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    private final String serviceId;

    public MyLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> supplierProvider, String serviceId) {
        this.supplierProvider = supplierProvider;
        this.serviceId = serviceId;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = supplierProvider.getIfAvailable(NoopServiceInstanceListSupplier::new);
        return supplier.get(request).next().map(instances -> {
            if (instances.isEmpty()) {
                return new EmptyResponse();       // 空列表别抛异常——交给上层变 503（07）
            }
            return new DefaultResponse(pick(instances));   // pick = 你的算法
        });
    }

    private ServiceInstance pick(List<ServiceInstance> instances) { /* 见 §3 */ }
}
```

**官方默认 RR 的挑法（三行，自研时对照）**（`RoundRobinLoadBalancer.java:99-116`）：

```java
private Response<ServiceInstance> getInstanceResponse(List<ServiceInstance> instances) {
    if (instances.isEmpty()) {
        return new EmptyResponse();          // 同上：空列表不抛，变 503
    }
    if (instances.size() == 1) {
        return new DefaultResponse(instances.get(0));  // 单实例短路，不动计数器
    }
    int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;  // AtomicInteger 自增，屏蔽符号位
    ServiceInstance instance = instances.get(pos % instances.size());
    return new DefaultResponse(instance);
}
```

自研算法（§3 最少连接/08 一致性哈希）就是把最后一句 `pos % size` 换成自己的 `pick`——骨架（空列表/单例/计数器）照抄官方。

**③ 注册骨架**（xhs 是"全服务默认装"）：

```java
@Configuration
@LoadBalancerClients(defaultConfiguration = MyLbConfig.class)   // 所有 LB 子上下文都装
public class MyLbConfig {

    @Bean
    public ReactorLoadBalancer<ServiceInstance> myLoadBalancer(Environment environment,
                                                               LoadBalancerClientFactory factory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new MyLoadBalancer(factory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
    }
}
```

> xhs 源码里还给这个 Bean 加了 `@ConditionalOnProperty(prefix="loadbalancer.client", name="name")`——`loadbalancer.client.name` 只存在于**子上下文**环境，用它保证"只在 LB 子上下文实例化"。
> L16 用的是 per-service 版：`@LoadBalancerClient(name="demo-provider", configuration=…)`——两种都行，看粒度。

**④ Lifecycle 骨架**（活跃数就是这么数的）：

```java
public class MyLifecycle implements LoadBalancerLifecycle<Object, Object, ServiceInstance> {

    @Override public void onStart(Request<Object> request) { }

    @Override public void onStartRequest(Request<Object> request, Response<ServiceInstance> response) {
        if (response.hasServer()) markStart(response.getServer());          // 选中 → +1
    }

    @Override public void onComplete(CompletionContext<Object, ServiceInstance, Object> ctx) {
        Response<ServiceInstance> r = ctx.getLoadBalancerResponse();
        if (r != null && r.hasServer()) markEnd(r.getServer());             // 完成（无论成败）→ -1
    }
}
```

---

## 3. 生产案例一：xhs"最少连接"（`LeastConnectionsLoadBalancer`）完整拆解

**打分公式**（`selectInstance`）：

```text
分数 = 活跃请求数 / 权重          → 取最小
并列 → 随机挑（避免恒定选列表第一台）
权重 = metadata.weight（缺省 10） × 预热系数
预热系数：metadata.startupTime 起 60 秒内，从 1% 线性爬到 100%
```

**活跃数**：`ConcurrentHashMap<instanceId, AtomicInteger>`——由 `LeastConnectionsLifecycle` 钩子维护（§2 ④）；覆盖 Feign（含重试客户端）与 `@LoadBalanced` RestTemplate/RestClient（都经 `BlockingLoadBalancerClient` 触发同一套 Lifecycle）。

**它治的病**（§0 的 ① + ②）：

- 慢请求堆积 → 活跃数高的实例自动降分，流量挪给空闲的；
- 冷启动 → `startupTime` 预热期权重极低，新实例慢慢"热身"。

**三个诚实限制**（源码注释/核查）：

| # | 限制 | 后果 |
|---|---|---|
| 1 | Lifecycle 钩子没接上（如某些调用路径） | `activeRequests` 恒 0 → **退化成"按权重"挑**（仍比轮询好） |
| 2 | 注册侧当前**未见写入** `startupTime` | 预热是预留能力，不填就不生效（02 已核） |
| 3 | 单机视角 | 每个调用方各算一份活跃数（不是全局负载） |

> 这与 lab 的坑同款："机制写对了、接线漏了"就是残废（05 的 hints 空名单 / health-check 死循环）——**自研决策型 LB 最怕"钩子没接上还以为是全功能"**。

---

## 4. 生产案例二：zone（`ZoneLoadBalancerConfiguration`）

- **位置**：它属于扩展点 **②（滤镜/supplier 层）**——改"可挑集合"，不改算法；
- **装配**：`@LoadBalancerClients(defaultConfiguration = ZoneLoadBalancerConfiguration.class)`（全服务默认）+ `@ConditionalOnClass(ServiceInstanceListSupplier.class)`；
- **链**（源码原文）：`builder().withBlockingDiscoveryClient().withBlockingHealthChecks(zoneHealthCheckRestTemplate).with((ctx, delegate) -> new ZonePreferenceServiceInstanceListSupplier(delegate, zonePreferenceFilter)).build(context)`——**阻塞发现 + 主动健康检查（注入了非负载均衡的 RestTemplate，正好绕开 05 的死循环坑）+ 定制 zone 过滤**（没有 Caching 层）；
- **本机 zone 取值链**（逐行解读见 `xhs-gateway-zone.md`）：`Environment → -D → MYXHS_ZONE → defaultZone`；
- **实例 zone**：注册 metadata（02 §6）。

> 和官方 `zone-preference` 预设的关系：同样是"同 zone 优先 + 空则回退"，xhs 版把"取值链/过滤细节"做成了可维护的自研件——**滤镜型自研性价比高**（改动小、风险低）。

---

## 5. 自研怎么做不翻车（工程要点）

| # | 要点 | 说明 |
|---|---|---|
| 1 | **放对子上下文** | 自定义 Configuration **不能**在主扫描路径（L16 三要点之一）；用 `@LoadBalancerClients/@LoadBalancerClient` 挂进去 |
| 2 | **空列表给 `EmptyResponse`** | 别抛异常——上层统一变 503（07 的失败矩阵） |
| 3 | **线程安全** | `choose()` 在关键路径上：活跃数用 `ConcurrentHashMap + AtomicInteger`；ring 用不可变对象换引用 |
| 4 | **`choose()` 里别做 IO/锁** | 每次请求都要过（网关/Feign 都是）；指标更新也走"读本地、异步上报" |
| 5 | **观测** | 打日志/指标（lab `/lb/distribution` 是"看分布"的现成思路）；否则出问题无法归因 |
| 6 | **隔离测试** | 抄 lab 的姿势：`StubSupplier + 内嵌 HttpServer` 先验证机制，再测应用配置（lab `HealthCheckServiceInstanceListSupplierTest` 就是这么定位出两个坑的） |

---

## 6. 手写方向（被砍的 C 类，需要时恢复）

- **动态权重 `WeightFunction`**（Ribbon `WeightedResponseTimeRule` 移植）——06 §2.4 讲了思想，动手版在 sca-lab git；
- **一致性哈希 LB**（参考 Dubbo `ConsistentHashLoadBalance`）——08 §4 给了骨架；
- 恢复方式：sca-lab git 历史（`loadbalancer-lab` HANDOVER §一）。

---

## 7. xhs 的 LB 全景（一页对照）

| 层 | xhs 做什么 | 本系列 |
|---|---|---|
| 名单 | Nacos 推送 + SCLB 缓存（35s） | 02 / 07 |
| 滤镜 | zone（自研） | 09 / 05 |
| 算法 | **最少连接**（自研，带预热） | 09 / 03 |
| 观测 | Lifecycle（活跃数）+ 优雅停机 | 09 / 10 / 07 |
| 兜底 | `NEVER_RETRY` 立场 + 优雅停机 | 08 / 07 |

---

## 8. 边界与翻篇

- Lifecycle/Micrometer 的官方实现细节 → 10；装饰器链 14 类 → lab D2；zone 逐行 → `xhs-gateway-zone.md`；
- 本课**不改 xhs 代码**——是"读懂生产为什么这么写"，不是"重写一遍"。

---

## 9. Self-Test

1. 扩展点四个位置各在"名单 → 挑一个"的哪一环？各自的最小骨架长什么样？
2. xhs 最少连接的打分公式？活跃数从哪来？三个诚实限制分别是什么？
3. 自研 LB 最容易翻车的三个工程点（子上下文 / 空列表 / 线程）各自怎么防？
4. zone 属于哪类扩展点？它的"取值链"是什么？

---

## 10. 相关与衔接

- **10 可观测·排障·选型收口**：Lifecycle 官方实现、排障三视角、决策树；
- [`loadbalancer-lab`](../../loadbalancer-lab/HANDOVER.md)：[D2 装饰器链](../../loadbalancer-lab/docs/D2-ServiceInstanceListSupplier-装饰器链体系.md) / [D6 生命周期](../../loadbalancer-lab/docs/D6-LoadBalancerLifecycle-生命周期.md)；
- 网关课程：[L16](../gateway/L16-custom-loadbalancer.md)（装配三要点）/ [xhs-gateway-zone.md](../gateway/xhs-gateway-zone.md)（zone 逐行解读）。
