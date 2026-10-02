# Lesson 16: Custom LoadBalancer — 灰度与 Zone

> 课程索引：`../gateway-学习清单.md` → Stage 4 · Lesson 16
> 版本口径：xhs-demo 实际运行 **SCG 4.1.5 + SCLB 4.1.4 + Nacos 2.2.3**（本文所有输出均为**实测原始输出**）
> 前置：L14/L15（写代码三段式）· 环境：`infra-nacos.md`（docker Nacos）

---

## 16.0 先看结果（本文要带你走到的终点）

一台网关 + 同一个服务名 `demo-provider` 的**两个实例**（版本不同），
通过一个属性切换三种流量分发方式：

```
模式：demo.lb.mode = none        ← 默认轮询：两边都发
    第1次 → port=8082
    第2次 → port=8081
    第3次 → port=8082
    第4次 → port=8081
    第5次 → port=8082
    第6次 → port=8081

模式：demo.lb.mode = gray        ← 灰度：只发 v2
    第1次 → port=8082
    ...
    第6次 → port=8082

模式：demo.lb.mode = zone        ← Zone 亲和：只发同 zone
    第1次 → port=8081
    ...
    第6次 → port=8081
```

**你可以自己跑**（一条命令，约 2 分钟，会依次重启网关并打印上面的输出）：

```bash
cd /data/workspace/source-code/xhs-demo
bash demo-gateway/scripts/l16-demo.sh
```

下面讲清楚：**为什么会这样、代码是怎么写的、每一环在干什么。**

---

## 16.1 起点：现在的流量是怎么分的？

先确认我们手里有什么。两个 provider 实例都注册到了 Nacos，各自**上报了 metadata**：

```bash
curl -s --noproxy '*' "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=demo-provider"
```

```
实例数: 2
  192.168.13.128:8082  zone=beijing   version=v2     ← 新版本
  192.168.13.128:8081  zone=shanghai  version=v1     ← 老版本
```

网关有一条 `lb://` 路由：

```yaml
- id: demo-lb
  uri: lb://demo-provider        # ← 不是 IP，是"服务名"
  predicates:
    - Path=/lb/**
  filters:
    - StripPrefix=1
```

发请求时，**流量被平均分给了两台**（就是上面 `none` 模式的输出）：
```
8082 → 8081 → 8082 → 8081 → …
```

**默认就这么工作**：Spring Cloud LoadBalancer（SCLB）拿"实例清单"，**轮询**选一个。

---

## 16.2 我要什么：为什么需要"自定义"

| 场景 | 我要的效果 |
|---|---|
| **灰度/金丝雀发布** | v2 刚上线，**只把流量发给 v2** 观察，v1 先不动 |
| **Zone 亲和** | 网关在 shanghai → **优先**用 shanghai 的实例（同区延迟低、跨区流量贵） |

现在的轮询**两个都做不到** —— 它只会"雨露均沾"。

所以我要介入 **"从哪些实例里选"** 这一步。

---

## 16.3 要改的是哪一环：`lb://` 的完整链路

理解这条链路，才知道**该往哪儿插代码**：

```
① 路由配置：uri = lb://demo-provider
        │
        ▼
② ReactiveLoadBalancerClientFilter（一个 GlobalFilter，order=10150）
        │   ← 它看到 uri 以 lb:// 开头，就把"选实例"的活交给 SCLB
        ▼
③ LoadBalancerClientFactory 给出这个服务的 LoadBalancer
        │
        ▼
④ ServiceInstanceListSupplier.get()  →  Flux<List<ServiceInstance>>
        │   ★ 这一层回答："这个服务现在有哪些实例可选？"  ← 我们要改的就是它
        ▼
⑤ LoadBalancer 从清单里【选一个】（默认 RoundRobin 轮询）
        │
        ▼
⑥ 把 lb://demo-provider 改写成 http://192.168.13.128:8082
        │
        ▼
⑦ 后续走 L23 的转发四步（NettyRoutingFilter 真正发请求）
```

**关键认识**：
- ②③⑤⑥⑦ 都是框架的活，我们不用碰；
- **④ 是入口**：只要控制"**给出去的实例清单长什么样**"，就控制了"能选到谁"。

> 本文的做法：**在 ④ 这层加一个"过滤器"** —— 把不符合条件的实例从清单里剔掉。

### 16.3.1 `get()` 到底跑在哪个线程（★ 把 L11 串起来）

**问题**：④ `ServiceInstanceListSupplier.get()` 要去**注册中心拿实例** —— 这是 IO。
**能在 EventLoop 线程上做吗？**（L11 的纪律：EventLoop 不能阻塞）

**答案：不能 —— 但框架已经帮你切走了，你一行都不用写。**

**证据链**（从你写的代码一路挖到底）：

**① 你写的（只有一行）**：
```java
ServiceInstanceListSupplier.builder()
        .withDiscoveryClient()      // ★ 剩下的 SCLB 内部做
        .build(context);
```

**② SCLB 内部：二选一**（字节码实证）—— `DiscoveryClientServiceInstanceListSupplier` 有两个构造器：

| 容器里有 `ReactiveDiscoveryClient`？ | 用哪个 |
|---|---|
| **有** | `DiscoveryClientServiceInstanceListSupplier(reactiveDiscoveryClient, env)` |
| 没有 | 阻塞 `DiscoveryClient` + **`Schedulers.boundedElastic()`** 包装 |

**③ SCA 提供了响应式版的 Nacos 客户端** —— `NacosReactiveDiscoveryClient`
（激活条件：`@ConditionalOnReactiveDiscoveryEnabled`，默认开）
→ 我们网关走的是**响应式**这条路。

**④ 而它内部仍然是阻塞的**，只是自己切了线程：
```java
// NacosReactiveDiscoveryClient.getInstances()（反编译）
Mono.justOrEmpty(...)
    .flatMapMany(loadInstancesFromNacos())        // ← 内部是【阻塞】的 getAllInstances()
    .subscribeOn(Schedulers.boundedElastic());    // ★★★ 挪到弹性线程池 = L11 的"阻塞逃生舱"
```

**为什么"实际几乎不碰 Nacos 服务端"（三层缓冲）**：

| 层 | 机制 |
|---|---|
| **Nacos 客户端本地缓存** | 客户端**订阅**服务 → 实例变化由服务端 **gRPC 推送** → `getAllInstances()` 读的是**本地内存** |
| **SCA `ServiceCache`** | SCA 自己的缓存层（字节码里可见） |
| **SCLB `withCaching()`**（我们用了） | 实例清单缓存，**TTL 默认 35 秒**（字节码：`long 35l`） |

```
正常请求 → 选实例 → 命中 SCLB 缓存（35s）→ 返回
              ↓ 缓存过期
          读 Nacos 客户端本地缓存（内存）
              ↓ 未命中 / 首次
          【boundedElastic 线程】才可能真的打 Nacos
```

**面试答法**：
> "Nacos 的实例查询 API 是**阻塞**的，但 **SCA 提供了 `NacosReactiveDiscoveryClient`**（内部 `subscribeOn(boundedElastic)`）；
> SCLB 的 `withDiscoveryClient()` 会**优先用响应式客户端**，没有才用 boundedElastic 包阻塞客户端 ——
> 加上三层本地缓存，正常请求路径**既不阻塞 EventLoop、也不打 Nacos 服务端**。"

> **这就是"框架层的线程纪律"**：你写业务要守（L11 坑 2），框架自己的 IO 也要守 ——
> 只是它守得更隐蔽（把 `subscribeOn(boundedElastic)` 藏在了 `NacosReactiveDiscoveryClient` 里）。

---

## 16.4 一个必踩的坑：`lb://` 需要 SCLB，而 SCG 不带它

在写代码之前，先说一个**会让你困惑半小时**的坑（我们实测踩过）：

| | 缺 SCLB 时 | 补上 SCLB 后 |
|---|---|---|
| `/actuator/gateway/globalfilters` | **`NoLoadBalancerClientFilter`**（10150） | **`ReactiveLoadBalancerClientFilter`**（10150）<br>+ `LoadBalancerServiceInstanceCookieFilter`（10151） |
| `curl /lb/hello` | ❌ **503 Service Unavailable** | ✅ 200 |

**原因**：`spring-cloud-starter-gateway` 的依赖树里**没有** `spring-cloud-starter-loadbalancer`。
没有 SCLB，Gateway 就会装配一个"占位的" `NoLoadBalancerClientFilter` —— 它遇到 `lb://` 只能报错。

**修复**（`demo-gateway/pom.xml`）：

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

> 回头看看 **L08** 那张 `globalfilters` 表 —— 当时表里就是 `NoLoadBalancerClientFilter`：
> 因为那时 demo 还没引 SCLB。**同一张表，缺依赖/有依赖两种样子**，这就是最直观的诊断入口。

---

## 16.5 动手（一）：写"选实例"的供应商

### 5.1 先理解 SCLB 的设计：它是一串"洋葱"

`ServiceInstanceListSupplier` 不是一个类，而是**一层层包起来**的：

```
withDiscoveryClient()          ← 最里层：从注册中心取【全部】实例
        ↓
【你的自定义层】                 ← ★ 我们插在这里：过滤/排序
        ↓
withCaching()                  ← 最外层：缓存结果（别每次都打注册中心）
        ↓
交给 LoadBalancer 选一个
```

要写自定义层，**继承 `DelegatingServiceInstanceListSupplier`** 最省事 ——
它帮你拿着下一层（`delegate`），你只需要"改造 `delegate.get()` 的结果"。

### 5.2 灰度供应商：把 v1 剔掉

```java
public class GrayInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private final String requiredVersion;                       // "v2"

    public GrayInstanceListSupplier(ServiceInstanceListSupplier delegate, String requiredVersion) {
        super(delegate);
        this.requiredVersion = requiredVersion;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get()                                    // ① 拿全部实例（Flux，异步）
                .map(instances -> instances.stream()
                        .filter(i -> requiredVersion.equals(
                                i.getMetadata().get("version")))  // ② 只留 version=v2
                        .toList());
    }
}
```

**逐行说明**：
- ① `delegate.get()` 返回 `Flux<List<ServiceInstance>>` —— **注意是 Flux**（取实例列表本身也是异步的，可能打注册中心）
- ② 过滤条件读的是**实例的 metadata** —— 这份数据是 **provider 启动注册时上报的**（见 16.6 的"染色"）

### 5.3 Zone 供应商：优先同区，**但挑不到要能回退**

```java
public class ZonePreferredInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private final String preferredZone;                          // "shanghai"

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get().map(instances -> {
            List<ServiceInstance> sameZone = instances.stream()
                    .filter(i -> preferredZone.equals(i.getMetadata().get("zone")))
                    .toList();
            return sameZone.isEmpty() ? instances : sameZone;    // ★ 同区为空 → 用全部
        });
    }
}
```

**为什么要 `isEmpty() ? instances : sameZone`**：
如果"同区一个可用实例都没有"，返回空清单 = **整个服务不可用**。
回退到全部实例 = **宁可跨区，也要保住可用性**（高可用优先于亲和性）。

> **两个供应商的语义差别**（这是本课的重点）：
> - **灰度**：**筛掉**不要的 → 可能筛到"空"
> - **Zone**：**优先**挑想要的 → 挑不到就退而求其次

---

## 16.6 动手（二）：装配（三个容易"不生效"的点）

```java
// ① 配置类 —— 放在【组件扫描包之外】：com.myxhs.demo.lb
//    （网关扫描的是 com.myxhs.demo.gateway.**，所以这个包扫不到）
@Configuration(proxyBeanMethods = false)
public class DemoLoadBalancerConfiguration {

    @Bean
    public ServiceInstanceListSupplier serviceInstanceListSupplier(
            ConfigurableApplicationContext context,
            @Value("${demo.lb.mode:none}") String mode) {

        // 底层：从注册中心拿实例
        ServiceInstanceListSupplier discovery = ServiceInstanceListSupplier.builder()
                .withDiscoveryClient()
                .build(context);

        // 中间层：按模式插入自定义逻辑
        ServiceInstanceListSupplier filtered = switch (mode) {
            case "gray" -> new GrayInstanceListSupplier(discovery, "v2");
            case "zone" -> new ZonePreferredInstanceListSupplier(discovery, "shanghai");
            default -> discovery;
        };

        // 最外层：加缓存
        return ServiceInstanceListSupplier.builder()
                .withBase(filtered)
                .withCaching()
                .build(context);
    }
}
```

```java
// ② 在网关主类上，把这个配置挂给【具体的服务】
@SpringBootApplication
@LoadBalancerClient(name = "demo-provider", configuration = DemoLoadBalancerConfiguration.class)
public class GatewayApplication { ... }
```

```yaml
# ③ 模式开关（演示用；生产按需求固化）
demo:
  lb:
    mode: gray
```

**三个"不生效"陷阱**（都是我踩过/同行常踩的）：

| 陷阱 | 为什么 | 后果 |
|---|---|---|
| 配置类放在**被扫描的包**里 | 会被**主上下文**也注册一份，和 SCLB 的**子上下文**打架 | 行为诡异（有时生效有时不生效） |
| 忘记 `@LoadBalancerClient` | 自定义的 bean 在子上下文里**根本没人引用** | 还是默认轮询（代码白写） |
| 忘记 `withDiscoveryClient()` | 没有底层数据源 | 拿不到实例 |

> **为什么 SCLB 要搞"子上下文"**：负载均衡配置是**按服务隔离**的 ——
> `demo-provider` 一套策略，`my-xhs-order` 可以是另一套。所以它有独立的子容器。

---

## 16.7 看结果：三种模式的原始输出

**跑法**（脚本会依次重启网关、各发 6 次请求）：

```bash
cd /data/workspace/source-code/xhs-demo
bash demo-gateway/scripts/l16-demo.sh
```

**真实输出**（原样贴，未经修饰）：

```
== 前置检查 ==
  ✅ Nacos（docker）在跑
  ✅ demo-provider 实例数 = 2
     192.168.13.128:8082  zone=beijing version=v2
     192.168.13.128:8081  zone=shanghai version=v1

==============================================================
 模式：demo.lb.mode = none
==============================================================
    第1次 → port=8082
    第2次 → port=8081
    第3次 → port=8082
    第4次 → port=8081
    第5次 → port=8082
    第6次 → port=8081

==============================================================
 模式：demo.lb.mode = gray
==============================================================
    第1次 → port=8082
    第2次 → port=8082
    第3次 → port=8082
    第4次 → port=8082
    第5次 → port=8082
    第6次 → port=8082

==============================================================
 模式：demo.lb.mode = zone
==============================================================
    第1次 → port=8081
    第2次 → port=8081
    第3次 → port=8081
    第4次 → port=8081
    第5次 → port=8081
    第6次 → port=8081
```

**怎么读这三段**：
| 模式 | 观察到的 | 说明 |
|---|---|---|
| `none` | **交替** | SCLB 默认轮询，两个实例都被用上 |
| `gray` | **全是 8082** | v1（8081）被 `GrayInstanceListSupplier` 过滤掉了 |
| `zone` | **全是 8081** | 网关自己 zone=shanghai → 优先同区 |

---

## 16.8 回到概念：**"染色" ≠ "选实例"**（★ 本节最重要）

上面的实验里，藏着一条**很容易被忽略的事实**：

> **`none` 模式下，流量是"平摊给 v1/v2"的 —— 尽管实例带着 `version` 标签。**

也就是说：
**给实例打上 `version=v2` 这个标签，本身对路由没有任何影响。**

两件事必须分清楚：

| | **染色（打标签）** | **选实例** |
|---|---|---|
| 谁做 | **服务实例自己**（provider 侧） | **消费端**（网关/调用方） |
| 什么时候 | **启动注册时**上报 | **每次选实例前**过滤/排序 |
| 怎么实现 | `spring.cloud.nacos.discovery.metadata.version=v2` | 本课自定义的 `ServiceInstanceListSupplier` |
| 产物 | 注册中心里实例带标签 | 清单里只剩符合条件的实例 |

**灰度 = 染色 + 选实例，两步缺一不可。**

> **⚠️ 生产常见事故**（记住这个画面）：
> 新版本服务**打了 `version=v2` 标签上线**了，网关也"说要灰度"——
> 但**没人实现按标签过滤** → 流量照样轮询到 v1/v2 → **用户看到老版本，灰度根本没生效**。
>
> 排查时怎么快速确认：看**实例清单是否带标签**（染色做了没），再看**消费端有没有过滤逻辑**（选实例做了没）。
> 这正是 xhs 项目里"灰度只打标签、实例过滤未实现"的问题类型。

---

## 16.9 生产怎么做（★ 含 microsphere 实战 + Gateway 两个坑）

> 本节素材：microsphere 源码分析（`17-01`）+ 生产落地指南（`17-03`），位于
> `learn-book/.../training-camp/microsphere-analysis/17-microsphere-multiactive-analysis/`

### 16.9.1 第一层：能用内置就别自己写（★ 逐个讲清机制）

```java
ServiceInstanceListSupplier.builder()
        .withDiscoveryClient()
        .withZonePreference()          // ← 内置 Zone 亲和（本课手写只为看清原理）
        .withHealthChecks()            // ← 主动健康检查（不合格实例踢出清单）
        .withWeighted(...)             // ← 按权重选实例
        .withCaching()
        .build(context);
```

| 内置 | **机制（实现细节）** | 配置 / 默认 |
|---|---|---|
| `withDiscoveryClient()` | 从注册中心取实例（**响应式优先**，否则 boundedElastic 包装） | 见 16.3.1 |
| `withCaching()` | 实例清单缓存 | TTL **35s**（`spring.cloud.loadbalancer.cache.ttl`） |
| `withZonePreference()` | 优选"**与我同 zone**"的实例（见 16.9.4：读的是**我的** zone） | 我的 zone：`spring.cloud.loadbalancer.zone`<br>实例的 zone：metadata 的 `zone` |
| `withHealthChecks()` | **周期性探活**：WebClient 挨个 GET 实例的 **`/actuator/health`**，维护"存活清单"（`aliveInstancesReplay`）；`get()` **只返回存活的** | 路径默认 **`/actuator/health`**（可按服务覆盖） |
| `withWeighted(...)` | **按权重把实例"复制"成多份**放进候选列表（`expandByWeight`）→ 轮询按比例命中 | 读实例 metadata 的 **`weight`**（默认 100；非法值回退默认并告警） |
| `withSameInstancePreference()` | 同一请求（按 hint）**优先复用同一实例**（sticky） | 与 L29 sticky session 相关 |

**两个实现要点（面试可以讲）**：
- **Weighted 的原理很土但有效**：不是"加权随机"，而是**把权重大的实例在列表里重复多份**，然后照常轮询
- **HealthChecks 不是请求时探活**：是**后台周期任务**维护存活清单，请求时只读清单 —— **不占请求路径**

**顺序有讲究**：builder 是**逐层包装**，顺序 = 洋葱的层序；`withCaching()` 一般放**最外层**（缓存最终清单）。

**只有内置满足不了时才自定义**（下一节讲哪些情况）。

### 16.9.2 第二层：自定义时，看 microsphere 怎么写（生产级参考）

**① 供应商结构 —— 和我们 L16 手写的**几乎一样**（这是最有说服力的一点）**：

```java
public class ZonePreferenceServiceInstanceListSupplier
        extends DelegatingServiceInstanceListSupplier {            // 同款基类
    private final ZonePreferenceFilter<ServiceInstance> zonePreferenceFilter;

    @Override
    public Flux<List<ServiceInstance>> get() {
        return getDelegate().get().map(this::filteredByZone);      // 同样在 get() 里过滤
    }
}
```

**② 装配方式 —— 用 `.with(...)` 插中间层 + 一个 opt-in 开关**：

```java
ServiceInstanceListSupplier.builder()
        .withDiscoveryClient()
        .withCaching()
        .with((ctx, delegate) -> new ZonePreferenceServiceInstanceListSupplier(delegate, filter))  // ← 插中间层
        .build(context);

// opt-in 条件：spring.cloud.loadbalancer.configurations=optimized-zone-preference 才启用
@Conditional(OptimizedZoneConfigurationCondition.class)
```

> **为什么必须 opt-in**（源码分析原话）：
> 区域偏好会**改变流量分布** —— 可能导致 **同区实例过载 / 他区实例空闲 / 跨区故障转移失效**。
> **不能默认开** —— 这是生产纪律（对比我们 demo 的 `demo.lb.mode` 直开）。

**③ 它的过滤参数是"生产三件套"**（我们 demo 没有的部分）：

```yaml
myxhs:
  availability:
    zone: beijing-1                  # 本机 zone（ZoneLocator 自动探测 or 手动配）
    preference:
      upstream:
        zone-ready-percentage: 100   # 同区实例"就绪比例"阈值（渐进放量用）
        same-zone-min-available: 5   # ★ 同区最少可用实例数（不足则放宽到他区）
        disabled-zone:               # ★ 禁用的区域（切换/故障时用）
```

| 参数 | 解决什么 | 我们 demo 的对应 |
|---|---|---|
| `same-zone-min-available` | **同区实例不足时放宽**（不是"全有或全无"） | ❌ 我们只有"同区为空 → 回退全部" |
| `zone-ready-percentage` | **渐进放量**（切换时不一次性全切） | ❌ 无 |
| `disabled-zone` | **主动禁掉某区**（切换期间） | ❌ 无 |

**④ ★ 既然 SCLB 有内置，为什么 microsphere / xhs 还要扩展？（4 个理由）**

| # | 内置的不足 | microsphere 的补法 |
|---|---|---|
| **1** | **zone 要手动配**（`spring.cloud.loadbalancer.zone`）—— 部署环境一变就得改，K8s 里还得配 Downing API | **`ZoneLocator` 自动探测**（环境变量 / 文件 / IP 段） |
| **2** | "同区优先"**太粗**：只有"同区**为空**才回退"（等价 `min-available=1`）→ **同区只剩 1 个实例也会被全部流量打满** | **`same-zone-min-available`**（同区少于 N 个就放宽）+ `zone-ready-percentage` + `disabled-zone` |
| **3** | zone 是**静态属性**（改了要重启 / 靠 refresh） | 监听 **`EnvironmentChangeEvent`** → **不重启动态切换**（16.9.3） |
| **4** | 只解决"选实例"这一件事 | 多活**整套配套**：区域切换的优雅关闭、与 Sentinel / Resilience4j 的配合（`17-03` 场景二/三） |

> **一句话**：**内置解决"能不能做"，扩展解决"生产敢不敢用"** ——
> **容量保护**（#2）+ **自动探测**（#1）+ **动态切换**（#3）才是生产的三块硬需求。

### 16.9.3 第三层：动态切换怎么做（配置中心 + 事件监听）★ 回答"为什么不用配置中心"

**机制**（这才是"动态"的正解）：

```
配置中心改 zone
  → Spring 发 EnvironmentChangeEvent
    → microsphere 的 ZoneContextChangedListener 收到
      → 重新读配置、更新 ZoneContext
        → 下次选实例就用新 zone ✅
```

> **关键**：**在"事件触发时"重新读配置** —— 而不是像我们 demo 那样在 **Bean 构造时读一次**。
> 这就是为什么"我们把 `demo.lb.mode` 放进配置中心也没用"（见 16.6 的读取时机问题）。

**生产切换 / 回滚清单**（来自 `17-03` 场景四）：

```
【切换】
1. 确认目标区域 Redis/DB 可用
2. 确认目标区域实例已注册到 Nacos
3. 调 same-zone-min-available（放宽，让更多实例参与）
4. 切 zone                    ← 配置中心推送，事件驱动生效
5. 观察错误率
6. 确认无误 → 加 disabled-zone 禁用源区

【回滚】
1. zone 改回 originalZone（触发自动探测）
2. 删除 disabled-zone
3. 观察错误率
```

### 16.9.4 ★ LB 的 zone 从哪来？（"LB 又不是微服务"）

**疑问**：`withZonePreference()` 要读"我的 zone" —— 但 **LoadBalancer 又不是一个服务**，它怎么会有 zone？

**关键澄清**：**SCLB 不是服务端组件，它是"跑在每个应用进程里的一个库"**：

```
你的网关进程（部署在 shanghai 机房）
 ├─ SCG（WebFlux 框架）
 ├─ SCLB（库！）        ← 它的 zone = 【本进程所在的 zone】
 └─ Nacos 客户端（库）
```

所以有**两个 zone**，含义完全不同：

| 概念 | 含义 | 来源 |
|---|---|---|
| **`spring.cloud.loadbalancer.zone`** | **我（调用方）在哪个 zone** | 手工配 / K8s Downing API 注入 / **microsphere `ZoneLocator` 自动探测** |
| **实例 metadata 的 `zone`** | **被调用实例**在哪个 zone | 实例**注册时上报**（provider 侧配置 = 16.8 的"染色"） |

**"Zone 亲和" = 拿"我的 zone"去比"实例的 zone"，优选相等的** ✅

> **类比**：**"我在上海办公室，优先找上海的同事办事"**
> —— "我在哪"是**环境信息**（进程跑在哪个机房），不是"LB 提供的能力"。

**为什么 Gateway 要特别小心**（见下一节坑 1）：
Gateway 常和业务**不在同一部署单元**（物理机 LB / 独立网络层 vs K8s）→ 自动探测不准 → **必须手动配 `zone`**。

### 16.9.5 ★ Gateway 特有的两个坑（生产文档点名）

**坑 1：Gateway 的 zone 从哪来？**

```
Gateway 常和业务不在同一部署单元
（如：Gateway 在物理机/LB 上，业务在 K8s 里）
→ ZoneLocator 自动探测不准 → 必须【手动配置】
    myxhs.availability.zone: beijing-1
```
（K8s 场景可用 Downward API 把 `topology.kubernetes.io/zone` 注入环境变量，让 ZoneLocator 读）

**坑 2：跨区域重试失效——"同区全挂不会 fallback 到他区"**（反直觉但重要）

```
ZonePreferenceFilter 把"他区实例"过滤掉了
  → LoadBalancer 只在"同区实例"里重试
    → 同区全挂 = 请求失败（【不会】自动跨区）
```

| 方案 | 做法 | 评价 |
|---|---|---|
| **A（推荐）** | **重试 + 降级**：同区重试耗尽 → 返回降级响应 | 同区全挂是极端场景，**降级比跨区调用更安全**（跨区可能引发连锁故障） |
| B | 调低 `same-zone-min-available`（更快放宽到他区） | 适合实例数波动大（滚动更新期） |
| C | **两级路由**：Gateway 不做 zone 偏好，让"第一跳内部服务"做 | 缺点：第一跳延迟不可控 |

### 16.9.6 一句话总结

> **染色**（实例注册时报 zone/metadata）
> ＋ **选实例**（消费端自定义 `ServiceInstanceListSupplier`，带**容量保护** `same-zone-min-available` 与 **`disabled-zone`**）
> ＋ **opt-in 开关**（会改流量分布的行为**不能默认开**）
> ＋ **动态切换 = 配置中心 + 事件监听**（在事件里**重读配置**，不是启动时读一次）
> ＋ Gateway 额外注意：**zone 手动配**、**同区全挂不 fallback → 必须配降级**。

**对应课程**：这套内容会在 **L32（多活 / Zone）** 展开；**L44（Gateway × Nacos）** 讲配置中心联动。

---

## 16.10 掌握边界

| 内容 | 判断 |
|---|---|
| `lb://` 链路（哪一步是"选实例"） | ✅ **必须记** |
| **SCLB 依赖坑**（缺了 503 + `NoLoadBalancerClientFilter` 标志） | ✅ **必须记**（现场排障用） |
| `ServiceInstanceListSupplier` = 实例清单来源（洋葱结构） | ✅ **必须记** |
| **染色 vs 选实例**（只染色 = 白打标签） | ✅ **必须记**（面试点 + 事故点） |
| 自定义供应商写法（`Delegating...`，过滤 ≠ 优先，回退策略） | ✅ 记 |
| 装配三件事 + 三个"不生效"陷阱 | ✅ 记 |
| SCLB 内置供应商清单 | ✅ 知道有（优先用） |
| SCLB 源码（`RoundRobinLoadBalancer` 等实现） | ⏭️ **L42**（SCLB 深入） |

---

## 16.11 Self-Test

1. `lb://demo-provider` 从配置到"发往某个物理地址"，中间经过哪几个组件？我在哪一层做自定义？
2. 只引 `spring-cloud-starter-gateway` 时 `lb://` 会怎样？从哪个端点能一眼看出来？
3. **为什么说"只染色不选实例 = 白打标签"？** 具体会发生什么？
4. 灰度过滤和 Zone 亲和，在"清单为空"时的处理有什么本质不同？
5. 自定义 LB 的配置类为什么不能放在组件扫描包下？

### 16.11.1 参考答案

<details open>

**1. 链路与自定义层**
`lb://服务名` → `ReactiveLoadBalancerClientFilter`（10150 的 GlobalFilter）→ `LoadBalancerClientFactory` → **`ServiceInstanceListSupplier`（实例清单）** → LoadBalancer 选一个（默认轮询）→ **改写 URI** 为物理地址 → 转发四步。
**自定义插在"实例清单"这一层**。

**2. 缺 SCLB**
`lb://` → **503**；`/actuator/gateway/globalfilters` 里是 **`NoLoadBalancerClientFilter`**（而正常应是 `ReactiveLoadBalancerClientFilter`）。

**3. 只染色不选实例**
实例带着标签注册进了注册中心，**但没人按标签过滤** → 消费端照样轮询到所有实例 → **灰度不生效**（用户可能被路由到老版本）。实测证据：`none` 模式流量平摊 v1/v2。

**4. 空清单的处理**
- **灰度**：筛掉的就不回来了 → 可能**清单为空**（此时该服务不可用 —— 这是"筛"的代价）
- **Zone**：同区为空 → **回退全部实例**（保可用性）
本质：一个是**硬过滤**，一个是**软偏好**。

**5. 配置类位置**
因为它会被**主上下文**也扫描注册一份，和 SCLB 的**按服务子上下文**中的注册冲突 → 行为不可预期。
正确做法：放扫描包外 + 用 `@LoadBalancerClient(configuration = ...)` 挂进子上下文。

</details>

---

## 16.12 Open Questions

> 用法：直接在这里追加，或对话里说编号（如「16.8 没懂」）。

| # | 疑问 | 状态 |
|---|---|---|
| | | |
