# D1: ReactorLoadBalancer 响应式负载均衡接口

> 源码版本: spring-cloud-commons **v4.1.4**（tag；运行时对齐 SC 2023.0.3；4.3.2/5.x 新特性以注记标出）
> 源码路径: `spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/`
> 覆盖文件: `ReactorLoadBalancer.java`(45行)、`ReactorServiceInstanceLoadBalancer.java`(30行)、`RoundRobinLoadBalancer.java`(118行)、`RandomLoadBalancer.java`(90行)、`Response.java`(31行)、`DefaultResponse.java`(69行)、`EmptyResponse.java`(36行)、`Request.java`(34行)、`ReactiveLoadBalancer.java`(87行)

---

## 一、接口继承体系

整个 Spring Cloud LoadBalancer 的顶层接口是 `ReactiveLoadBalancer<T>`，然后派生出 `ReactorLoadBalancer<T>`，最终到 `ReactorServiceInstanceLoadBalancer`。

```
ReactiveLoadBalancer<T>                           (spring-cloud-commons)
  └─ ReactorLoadBalancer<T>                       (spring-cloud-loadbalancer/core)
       └─ ReactorServiceInstanceLoadBalancer       (marker, T = ServiceInstance)
              ├─ RoundRobinLoadBalancer            (实现类)
              ├─ RandomLoadBalancer                (实现类)
              └─ [自定义实现]
```

### 1.1 ReactiveLoadBalancer<T> — 最顶层抽象

**位置**: `spring-cloud-commons/.../loadbalancer/reactive/ReactiveLoadBalancer.java`

```java
public interface ReactiveLoadBalancer<T> {
    Request<DefaultRequestContext> REQUEST = new DefaultRequest<>();

    @SuppressWarnings("rawtypes")
    Publisher<Response<T>> choose(Request request);

    default Publisher<Response<T>> choose() { // conflicting name
        return choose(REQUEST);
    }
}
```

关键看点：

- **返回 `Publisher<Response<T>>` 而非 `Mono<Response<T>>`**。`Publisher` 是 Reactive Streams 的最顶级接口。这意味着理论上这个接口可以被 Reactor、RxJava、Java Flow 等任意响应式库实现。但现实是 Spring Cloud LoadBalancer 只用了 Reactor。

- **`Request<C>` 的泛型设计**：泛型参数 `C` 代表上下文类型。`DefaultRequestContext` 是默认空上下文（标记类，无成员）。这个设计允许不同的 LoadBalancer 实现携带不同的请求上下文。框架提供了 `RequestDataContext`（携带 HTTP 请求数据）和 `HintRequestContext`（携带 hint 字符串）两个具体上下文。

- **`choose()` 无参方法**: 底层调用 `choose(REQUEST)` 传入一个空的 `DefaultRequest` 实例。这是给那些不需要请求上下文的调用者使用的。

- **`Factory<T>` 内部接口**: LoadBalancer 的工厂抽象，4 个方法：

  ```java
  interface Factory<T> {
      ReactiveLoadBalancer<T> getInstance(String serviceId);

      default @Nullable LoadBalancerProperties getProperties(String serviceId) {
          return null;
      }

      <X> Map<String, X> getInstances(String name, Class<X> type);
      <X> X getInstance(String name, Class<?> clazz, Class<?>... generics);
  }
  ```

  `getInstance(String serviceId)` 是核心工厂方法。`getInstances()` / `getInstance()` 允许跨子上下文访问 Bean。
  `LoadBalancerClientFactory` 是唯一实现（见 D3）。

- **`REQUEST` 常量的类型**: `Request<DefaultRequestContext> REQUEST = new DefaultRequest<>()` 是带泛型参数的，但 `choose(Request)` 用的是 raw `Request`。当调用 `choose(REQUEST)` 或 `choose()` 时，编译器会 unchecked 转换，产生警告。这是 Java 泛型在向后兼容上的常见妥协。

### 1.2 ReactorLoadBalancer<T> — 绑定 Reactor 类型

**位置**: `spring-cloud-loadbalancer/.../core/ReactorLoadBalancer.java`

```java
@SuppressWarnings("rawtypes")
public interface ReactorLoadBalancer<T> extends ReactiveLoadBalancer<T> {
    Mono<Response<T>> choose(Request request);

    default Mono<Response<T>> choose() {
        return choose(REQUEST);
    }
}
```

相比父接口，两个 `choose()` 方法的返回类型都从 `Publisher` 收窄为 `Mono`（covariant return type），因为 Spring Cloud LoadBalancer 只用 Reactor，不需要通用 `Publisher`。接口体不到 10 行。

**注意 `@SuppressWarnings("rawtypes")`**：`choose(Request request)` 的参数声明是 raw `Request` 而非 `Request<?>`。原因在于 `choose()` 本身不关心 `Request<C>` 的具体类型参数 `C`——它只是把 Request 原样传给 `supplier.get(request)`。使用 raw `Request` 避免了在接口层级声明不必要的类型变量。`@SuppressWarnings` 压制编译器对 raw type 的警告。

### 1.3 ReactorServiceInstanceLoadBalancer — 标记接口

```java
public interface ReactorServiceInstanceLoadBalancer extends ReactorLoadBalancer<ServiceInstance> {
}
```

空接口。唯一的语义是：**这个 LoadBalancer 选择的实例是 `ServiceInstance` 类型**。所有真正的实现类（`RoundRobinLoadBalancer`、`RandomLoadBalancer`、以及用户自定义的 LB）都实现这个接口。

---

## 二、Request 泛型体系

`Request<C>` 接口非常简约：

```java
public interface Request<C> {
    default C getContext() { return null; }
}
```

泛型参数 `C` 是上下文类型。框架提供了下面这些实现：

| 实现类 | 上下文类型 | 用途 |
|--------|-----------|------|
| `DefaultRequest<DefaultRequestContext>` | `DefaultRequestContext`（空） | 默认无参请求 |
| `RequestDataContext` | `RequestData`（HTTP 方法、URI、头、body） | RestTemplate/WebClient 集成时使用 |
| `HintRequestContext` | `String hint`（默认 `"default"`） | hint 路由 |

这种设计利用了 Java 泛型来抽象请求上下文的多样性。不同的 LoadBalancer 实现可以从 `Request.getContext()` 中获取自己需要的上下文数据。

---

## 三、Response 响应体系

`Response<T>` 接口同样简约：

```java
public interface Response<T> {
    boolean hasServer();
    default T getServer() { return null; }
}
```

两个方法描述了两个关键信息：
- `hasServer()` — 是否有可用实例（false 表示无实例可用）
- `getServer()` — 选中的实例

没有 `getServer()` 抛出异常的约定——当 `hasServer()` 为 false 时，`getServer()` 默认返回 null。调用方必须自行检查 `hasServer()`。

两个实现类：

**`DefaultResponse`**: 包装一个选中的 `ServiceInstance`。`hasServer()` 检查实例是否为 null。

**`EmptyResponse`**: 实例为空的标记。`hasServer()` 永远返回 false，`getServer()` 返回 null。当 `ServiceInstanceListSupplier` 返回空列表时使用。

---

## 四、RoundRobinLoadBalancer 源码分析

### 4.1 构造函数与字段

```java
public class RoundRobinLoadBalancer implements ReactorServiceInstanceLoadBalancer {
    final AtomicInteger position;
    final String serviceId;
    private final SingletonSupplier<ServiceInstanceListSupplier> serviceInstanceListSingletonSupplier;

    public RoundRobinLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
            String serviceId) {
        this(serviceInstanceListSupplierProvider, serviceId, new Random().nextInt(1000));
    }

    public RoundRobinLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
            String serviceId, int seedPosition) {
        this.serviceId = serviceId;
        this.serviceInstanceListSingletonSupplier = SingletonSupplier
            .of(() -> serviceInstanceListSupplierProvider.getIfAvailable(NoopServiceInstanceListSupplier::new));
        this.position = new AtomicInteger(seedPosition);
    }
}
```

三个关键设计：

**`position`**（AtomicInteger）：轮询位置计数器。用 `AtomicInteger` 是因为 `choose()` 可能被并发调用。初始值使用 `Random.nextInt(1000)` 随机化——如果所有实例都从 0 开始，刚启动时所有请求会集中到列表第一个实例。随机化 seed 让起始位置分散，避免启动瞬间的流量倾斜。

**`SingletonSupplier`** + **`ObjectProvider`** — 懒加载打破循环依赖：

`RoundRobinLoadBalancer` 构造时接收的是 `ObjectProvider<ServiceInstanceListSupplier>` 而非直接的 `ServiceInstanceListSupplier`。区别在于：

- **直接注入 `ServiceInstanceListSupplier`**：Spring 在构造 `RoundRobinLoadBalancer` 时必须先完成 `ServiceInstanceListSupplier` 的完整初始化。但 `ServiceInstanceListSupplier` 可能在构造过程中也需要访问 LoadBalancer 上下文，形成循环依赖。
- **注入 `ObjectProvider<ServiceInstanceListSupplier>`**：`ObjectProvider` 是一个 **延迟引用**。调用 `getIfAvailable()` 时才会真正去上下文获取 Bean。`SingletonSupplier.of(() -> ...)` 进一步包装成**单例工厂**——第一次调用 `obtain()` 时执行 Lambda，缓存结果，后续返回缓存实例。

执行时序：
```
RoundRobinLoadBalancer 构造                ← ObjectProvider 只是引用，不触发 Supplier 初始化
    ↓
第一次 choose() 调用
    ↓
supplier.obtain()                          ← 首次触发 Lambda
    ↓
ObjectProvider.getIfAvailable()            ← 从 Spring 上下文获取 Supplier Bean
    ↓
serviceInstanceListSingletonSupplier       ← 缓存结果，后续 choose() 不再获取
```

这个模式在 `FeignClientFactoryBean`（OpenFeign D1 中 `ObjectProvider` 的使用）和 `RoundRobinLoadBalancer` 中完全一致。

**`NoopServiceInstanceListSupplier`**: `getIfAvailable(NoopServiceInstanceListSupplier::new)` 的第二个参数是兜底工厂——当上下文中没有 `ServiceInstanceListSupplier` Bean 时，使用 `NoopServiceInstanceListSupplier`（返回空列表）。保证 `choose()` 永不 NPE。

### 4.2 choose() 执行链

```java
public Mono<Response<ServiceInstance>> choose(Request request) {
    ServiceInstanceListSupplier supplier = serviceInstanceListSingletonSupplier.obtain();
    return supplier.get(request)
        .next()
        .map(serviceInstances -> processInstanceResponse(supplier, serviceInstances));
}
```

执行链拆解：

```
choose(request)
  │
  ├─ 1. obtain() Supplier                   ← 懒加载，首次触发
  │
  ├─ 2. supplier.get(request)               ← 返回 Flux<List<ServiceInstance>>
  │     │                                      （响应式流，可能持续推送实例列表更新）
  │     └─ .next()                          ← 取第一个 List<ServiceInstance>
  │                                           （转 Flux 为 Mono）
  │
  └─ 3. .map(processInstanceResponse)       ← 从列表中选一个实例
        │
        ├─ getInstanceResponse(instances)   ← 实际选择算法
        └─ SelectedInstanceCallback          ← 通知 Supplier 选中的实例
```

### 4.3 getInstanceResponse() — 轮询算法

```java
private Response<ServiceInstance> getInstanceResponse(List<ServiceInstance> instances) {
    if (instances.isEmpty()) {
        log.warn("No servers available for service: " + serviceId);
        return new EmptyResponse();
    }
    if (instances.size() == 1) {
        return new DefaultResponse(instances.get(0));
    }

    int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;
    ServiceInstance instance = instances.get(pos % instances.size());
    return new DefaultResponse(instance);
}
```

三个分支：

1. **空列表 → `EmptyResponse`**：无可用实例。上游调用方需要处理 `hasServer() == false`。
2. **只有一个实例 → 直接返回**：不做轮询，也不需要移动 position。这是性能优化——大多数 Supplier 已经做了过滤，单实例场景不应该浪费原子操作。
3. **正常轮询**：`position.incrementAndGet()` 原子递增，然后 `& Integer.MAX_VALUE` 清除符号位（保证 position 永远非负），再 `% instances.size()` 取模。

**为什么 `& Integer.MAX_VALUE`？** 如果 position 溢出到负数（`AtomicInteger` 到达 `Integer.MAX_VALUE` 后再 `incrementAndGet()` 会变成 `Integer.MIN_VALUE`，即 -2147483648），取模的结果也可能是负数，会导致 `IndexOutOfBoundsException`。`& 0x7FFFFFFF`（即 `Integer.MAX_VALUE`）把符号位清零，把负数映射到 `[0, 2147483647]` 区间。

这个技巧来自 Netflix Ocelli 的 `RoundRobinLoadBalancer`，源码注释中引用了它：
```java
// see original
// https://github.com/Netflix/ocelli/blob/master/ocelli-core/
// src/main/java/netflix/ocelli/loadbalancer/RoundRobinLoadBalancer.java
```

### 4.4 processInstanceResponse() — SelectedInstanceCallback

```java
private Response<ServiceInstance> processInstanceResponse(ServiceInstanceListSupplier supplier,
        List<ServiceInstance> serviceInstances) {
    Response<ServiceInstance> serviceInstanceResponse = getInstanceResponse(serviceInstances);
    if (supplier instanceof SelectedInstanceCallback && serviceInstanceResponse.hasServer()) {
        ((SelectedInstanceCallback) supplier).selectedServiceInstance(serviceInstanceResponse.getServer());
    }
    return serviceInstanceResponse;
}
```

`SelectedInstanceCallback` 是一个 SPI 接口，允许 LoadBalancer 把选中实例的信息传递给 Supplier 链。

**谁实现了它？**

- `DelegatingServiceInstanceListSupplier` — 装饰器基类直接实现该接口，`selectedServiceInstance()` 方法把调用**向下传递**给 `delegate`（链中下一个 Supplier）：

  ```java
  public void selectedServiceInstance(ServiceInstance serviceInstance) {
      if (delegate instanceof SelectedInstanceCallback callback) {
          callback.selectedServiceInstance(serviceInstance);
      }
  }
  ```

  由于所有具体 Supplier 都继承自 `DelegatingServiceInstanceListSupplier`，这个回调会**沿着装饰器链层层传递**，直到最内层的 `DiscoveryClientServiceInstanceListSupplier`（它不实现该接口，链就此终止）。

- `SameInstancePreferenceServiceInstanceListSupplier` — 唯一**重写**该方法的类。它在调用 `super.selectedServiceInstance()`（继续向下传递）之后，额外缓存了选中的实例，用于实现"同实例优先"策略：

  ```java
  public void selectedServiceInstance(ServiceInstance serviceInstance) {
      super.selectedServiceInstance(serviceInstance);
      if (previouslyReturnedInstance == null || !previouslyReturnedInstance.equals(serviceInstance)) {
          previouslyReturnedInstance = serviceInstance;
      }
  }
  ```

回调的执行路径（以 `[SameInstance, ZonePreference, DiscoveryClient]` 三层链为例）：

```
RoundRobinLoadBalancer
  │
  └─ .selectedServiceInstance(instance)           ← 调用最外层 Supplier
        │
        ├─ SameInstancePreference                     ← 缓存实例
        │   └─ super.selectedServiceInstance()
        │         │
        │         └─ ZonePreference                   ← 无 override，直接传递
        │             └─ super.selectedServiceInstance()
        │                   │
        │                   └─ DiscoveryClient         ← 不实现该接口，链终止
        │
        └─ 回调返回
```

**设计模式**：`instanceof` 检查是一种轻量级的 SPI。每次 `choose()` 调用都会触发一次回调传播，通知整条装饰器链"这个实例被选中了"。

---

## 五、RandomLoadBalancer 源码分析

```java
private Response<ServiceInstance> getInstanceResponse(List<ServiceInstance> instances) {
    if (instances.isEmpty()) {
        log.warn("No servers available for service: " + serviceId);
        return new EmptyResponse();
    }
    int index = ThreadLocalRandom.current().nextInt(instances.size());
    ServiceInstance instance = instances.get(index);
    return new DefaultResponse(instance);
}
```

`RandomLoadBalancer` 的结构与 `RoundRobinLoadBalancer` 几乎一样，唯一的区别在 `getInstanceResponse()` 里：

- 使用 `ThreadLocalRandom.current().nextInt()` 而非 `AtomicInteger.incrementAndGet()`
- **不需要 position 字段**（无共享可变状态）
- 没有单实例短路优化（`RoundRobinLoadBalancer` 有——单实例时避免 position 原子操作的开销；`RandomLoadBalancer` 没有——即使只有一个实例也执行随机数生成，结果等价于直接返回唯一实例）

`ThreadLocalRandom` 比 `Random` 性能更好（消除锁竞争），适合高并发场景。

**注意**：`RandomLoadBalancer` 也没有 `SelectedInstanceCallback` 的处理——选择逻辑在 `processInstanceResponse()` 中统一处理，与负载均衡器无关。所以回调是共享的。

---

## 六、总结：完整调用链

```
(上层调用方 — 见 D5/D7 详细分析)
  │
  ├─ @LoadBalanced RestTemplate → LoadBalancerInterceptor
  ├─ @LoadBalanced WebClient → LoadBalancerExchangeFilterFunction
  └─ Feign → FeignBlockingLoadBalancerClient
        │
        └─ BlockingLoadBalancerClient.execute()
              │  (Mono.block() — 桥接响应式到阻塞)
              ▼
        RoundRobinLoadBalancer.choose(request)
              │
              ├─ obtain() Supplier                    ← 懒加载
              │
              └─ supplier.get(request)                ← 传递请求上下文
                    │  (返回 Flux<List<ServiceInstance>>)
                    │
                    └─ .next()                        ← 取第一个列表
                         │  (Flux → Mono)
                         │
                         └─ .map(getInstanceResponse) ← 从列表中选一个实例
                              │
                              ├─ instances.isEmpty()    → EmptyResponse
                              ├─ instances.size() == 1  → DefaultResponse(唯一实例)
                              └─ pos = position.incrementAndGet() & Integer.MAX_VALUE
                                   instance = instances.get(pos % size)
                                   → DefaultResponse(instance)
```

关键细节：`supplier.get(request)` 传递的是 `Request` 请求上下文（内含 hint、请求数据等），与 `supplier.get()`（无参）不同。传递 Request 可以让下游的 Supplier（如 `HintBasedServiceInstanceListSupplier`）根据请求内容调整行为。

`ReactorLoadBalancer.choose()` 是整个调用的核心枢纽。它接受 `Request`，返回 `Mono<Response<T>>`。调用方可以是阻塞的（通过 `Mono.block()` 同步等待，见 D5），也可以是响应式的（直接链式调用，见后续文档）。

## 七、面试考点

1. **`ReactorLoadBalancer.choose()` 为什么返回 `Mono<Response<T>>`？**
   因为实例列表可能来自响应式源（如 ReactiveDiscoveryClient），选择过程本身也可能是异步的。Mono 提供了 0..1 的异步结果容器。

2. **为什么不是直接返回 `ServiceInstance`，而是 `Response<ServiceInstance>`？**
   `Response<T>` 包含了 `hasServer()` 状态，可以表达"无可用实例"的情况。调用方无需通过 null 判断或异常来判断是否有实例可用。

3. **RoundRobinLoadBalancer 的 position 起始值为什么用随机数？**
   多个实例同时启动时如果都从 0 开始，可能在短时间内所有请求都落到同一个后端。随机化 seed 让请求分布更均匀。

4. **`position & Integer.MAX_VALUE` 的作用？**
   清除符号位，防止 AtomicInteger 溢出到负数后取模导致 IndexOutOfBoundsException。

5. **SingletonSupplier 解决什么问题？**
   延迟 ServiceInstanceListSupplier 的初始化，避免构造函数中存在尚未初始化好的 Bean 导致的循环依赖。
