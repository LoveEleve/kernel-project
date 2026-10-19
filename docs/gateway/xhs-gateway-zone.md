# 网关侧 Zone 多活适配解读（xhs-gateway zone 包）

> 用途：吃透"**网关作为多活架构里的一个 LB 消费点**"到底做了什么、为什么这么做
> 定位：**网关课程配套解读**（与 L16/L42/L46 同线学习）；为 **L42（高可用·多活）** 与 **L46（Gateway × SCLB）** 的前置案例
> 源码：`xhs-project/my-xhs-gateway/src/main/java/com/myxhs/gateway/zone/`（**4 个文件**）
> 全景口径：zone 能力主体在 `my-xhs-common` 的 zone 相关文件（43 个，含测试）；网关因**不依赖 common** 做了最小适配

---

## 0. 例子先行：一次请求里的 zone 决策

场景：`demo-provider` 两个实例（Nacos 实例 metadata 带 `zone` 字段）：

| 实例 | 端口 | metadata.zone |
|---|---|---|
| A | 8081 | `shanghai` |
| B | 8082 | `beijing` |

网关部署在 `shanghai`（`MYXHS_ZONE=shanghai`）。请求 `GET /demo-provider/echo/hi`：

```text
① RoutePredicateHandlerMapping 匹配路由（来源不论：yml 手写或自动路由；示例为 Path=/demo-provider/**）
② lb://demo-provider 交给 ReactiveLoadBalancerClientFilter
③ 取该 client 的 ServiceInstanceListSupplier 链：[Discovery] → [Caching] → [ZonePreference]
④ ZonePreferenceFilter 过滤实例列表：
     实例 [A(shanghai), B(beijing)] → 同 zone = [A]，数量 1 ≥ 阈值 1 → 只返回 [A]
⑤ RoundRobin 从 [A] 选一个 → 转发到 8081
```

对照分支（过滤器的三档安全阀）：

| 条件 | 返回 | reason |
|---|---|---|
| 实例总数 ≤ 1 | 原样返回 | `single_or_empty` |
| 网关 zone 为空/`defaultZone` | 全部返回（不过滤） | `invalid_zone` |
| 列表里没有同 zone 实例 | 全部返回（回退） | `no_same_zone` |
| 同 zone 实例数 < `same-zone-min-available` | 全部返回（回退） | `min_available` |
| 否则 | 只返回同 zone 实例 | `ok`（decision=`same_zone`） |

---

## 1. 结论卡（记住 3 条）

1. **网关侧 = 4 个文件的最小适配**：`Registrar`（总开关+注册到所有 LB client）→ `Configuration`（每个 LB 子 context 的 Bean 装配）→ `Supplier`（反应式过滤包装）→ `Filter`（决策算法+指标）。原因是**网关 pom 不依赖 `my-xhs-common`**（实测 grep 无该依赖）。
2. **生效点是 SCLB 的 supplier 链**：`lb://` 每次选实例前，`ZonePreferenceFilter.filter()` 按"实例 metadata.zone"过滤列表；网关本身不碰转发逻辑。
3. **判"该不该优先"用的 zone 从哪来**：网关进程的 `myxhs.current.availability.zone`（**取值链**：Spring Environment → JVM `-D` → `MYXHS_ZONE` 环境变量 → `defaultZone`）；实例的 zone 来自服务注册的 metadata（各服务 yml：`zone: ${MYXHS_ZONE:defaultZone}`）。

---

## 2. 全景：zone 能力分布在哪

| 位置 | 文件数 | 角色 |
|---|---|---|
| `my-xhs-common` 的 zone 相关文件 | 43（含测试） | **主实现**：定位/上下文/服务侧 LB/传播/数据源/Redis/运维/指标 |
| `my-xhs-gateway` 的 `gateway/zone` 包 | **4** | **网关适配**：只为自己 `lb://` 的 SCLB 栈复刻最小件 |

common 的 43 个 zone 相关文件（`zone` 包 + `config/ZoneLoadBalancerConfig`，含测试）覆盖：`locator`（ZoneLocator SPI + Env/File/IpRange 三种自动发现 + EnvironmentPostProcessor）、`ZoneContext/ZoneResolver/ZoneProperties`、`loadbalancer`（服务侧 supplier + 配置）、`propagation`（X-Zone 头传播）、`datasource`（DynamicDataSource）、`redis`（拦截器链）、`admin`（ZoneAdminController）、`metrics`。业务服务依赖 common 自动获得；**网关这 4 个文件是"不依赖 common 也要参与多活"的代价**。

---

## 3. 逐文件源码解读

### 3.1 `GatewayZoneLoadBalancerRegistrar`——总开关 + 注册到所有 LB client

```java
@Configuration(proxyBeanMethods = false)
@LoadBalancerClients(defaultConfiguration = GatewayZoneLoadBalancerConfiguration.class)   // ★ 默认配置注入所有 LB 子 context
@ConditionalOnClass(ServiceInstanceListSupplier.class)
@ConditionalOnProperty(prefix = "myxhs.availability.zone.preference", name = "enabled", havingValue = "true")
public class GatewayZoneLoadBalancerRegistrar {
}
```

- `@LoadBalancerClients(defaultConfiguration = ...)`：SCLB 的扩展点——把这份配置作为**每个 `lb://` client 的默认配置**（网关所有下游服务都生效），不需要逐服务点名；
- 整个能力由 `myxhs.availability.zone.preference.enabled=true` **开关控制**：条件要求显式 `=true`，**未配置即不装配**（默认关）。实测 grep 全仓库 yml/properties/json **未见该 key**——说明它由部署环境/配置中心在运行时注入；排查"多活没生效"时，先确认目标环境里这个开关被打开了。

**它自己是怎么被发现的**：组件扫描——实测 resources 无 `AutoConfiguration.imports`/`spring.factories` 引用、全模块无显式引用；它位于主启动类 `com.myxhs.gateway.GatewayApplication` 的扫描包下。类体为空是刻意的：全部信息都在注解元数据里（**声明类，不干活**）。

**启动期流程（把条件串起来）**：

```text
启动扫描到 Registrar
  ├─ enabled=true？ 否 → 不注册 → @LoadBalancerClients 不生效 → 所有 lb:// 用默认配置（无 zone 过滤）
  └─ 是 → SCLB 为每个 lb://serviceId 的子 context import GatewayZoneLoadBalancerConfiguration
             → 子 context 条件（loadbalancer.client.name 存在）成立 → 造 Filter + Supplier
```

**反事实**：没有它，`GatewayZoneLoadBalancerConfiguration` 只是一个躺在扫描路径上的类——根 context 扫到它时条件不成立、不造 Bean，也没有任何 LB 子 context 会装上 zone 过滤 → **多活断在网关这一跳**。它是"把配置送进子 context"的传送门。

### 3.2 `GatewayZoneLoadBalancerConfiguration`——子 context 装配 + 取值链

```java
public class GatewayZoneLoadBalancerConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "loadbalancer.client", name = "name")   // ★ 只在 LB 子 context 创建
    @ConditionalOnMissingBean
    public ZonePreferenceFilter gatewayZonePreferenceFilter(Environment environment,
                                                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // 取值链修正：原实现只读 JVM -D 系统属性，而 gateway 不依赖 common（无 ZoneEnvironmentPostProcessor），
        // 任何脚本/容器只设 MYXHS_ZONE 时该属性从未被设置 → zone 恒为 defaultZone，Zone 优先静默失效。
        // 现口径：Spring Environment（yml/env 宽松绑定）→ JVM -D → MYXHS_ZONE 环境变量 → defaultZone。
        String zone = environment.getProperty("myxhs.current.availability.zone");
        if (zone == null || zone.isBlank()) {
            zone = System.getProperty("myxhs.current.availability.zone");
        }
        if (zone == null || zone.isBlank()) {
            zone = System.getenv("MYXHS_ZONE");
        }
        if (zone == null || zone.isBlank()) {
            zone = ZonePreferenceFilter.DEFAULT_ZONE;
        }
        int minAvailable = environment.getProperty(
                "myxhs.availability.zone.preference.upstream.same-zone-min-available", Integer.class, 1);
        return new ZonePreferenceFilter(zone, minAvailable, meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "loadbalancer.client", name = "name")
    @ConditionalOnMissingBean(name = "optimizedZonePreferenceServiceInstanceListSupplier")
    public ServiceInstanceListSupplier optimizedZonePreferenceServiceInstanceListSupplier(
            ConfigurableApplicationContext context, ZonePreferenceFilter gatewayZonePreferenceFilter) {
        return ServiceInstanceListSupplier.builder()
                .withDiscoveryClient()          // 基础：从注册中心拿全量实例
                .withCaching()                  // 缓存：避免每次选实例都打注册中心
                .with((ctx, delegate) ->
                        new ZonePreferenceServiceInstanceListSupplier(delegate, gatewayZonePreferenceFilter))  // 追加 zone 过滤
                .build(context);
    }
}
```

三个关键点：
1. **`loadbalancer.client.name` 条件**：LB 子 context 会带这个属性，Bean 只在**每个 LB client 的子 context**里创建，避免在 default context 造孤儿 Bean；
2. **取值链**（见 §5 的 bug）：Environment → `-D` → `MYXHS_ZONE` → `defaultZone`；
3. **supplier 链装配**：`withDiscoveryClient().withCaching().with(zone 过滤)`——标准的 SCLB 装饰器链，zone 过滤是链尾的最后一层。

**一个容易困惑的点**：`GatewayZoneLoadBalancerConfiguration` 也在扫描路径里，**根 context 同样会扫到它**。为什么不会在根 context 造出无主 Bean？因为两个 `@Bean` 都带 `@ConditionalOnProperty(prefix="loadbalancer.client", name="name")`，而该属性**只在 LB 子 context 存在**。同一个类的两种身份：

| 被发现方式 | 条件 | 行为 |
|---|---|---|
| 根 context 组件扫描 | `loadbalancer.client.name` 不存在 → 不成立 | 不造 Bean（避免孤儿，源码 javadoc 原话） |
| 子 context 经 `defaultConfiguration` 引入 | 属性存在 → 成立 | 正常造 Bean（zone 过滤生效） |

### 3.3 `ZonePreferenceServiceInstanceListSupplier`——反应式包装

```java
public class ZonePreferenceServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private final ZonePreferenceFilter zonePreferenceFilter;

    public ZonePreferenceServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
                                                     ZonePreferenceFilter zonePreferenceFilter) {
        super(delegate);
        this.zonePreferenceFilter = zonePreferenceFilter;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return getDelegate().get().map(zonePreferenceFilter::filter);   // ★ 只做一件事：把 delegate 的列表过一遍过滤器
    }
}
```

装饰器模式：不改变上游（Discovery/Caching）行为，只在"实例列表产出后"做一次过滤。

### 3.4 `ZonePreferenceFilter`——决策算法 + 指标

```java
    public List<ServiceInstance> filter(List<ServiceInstance> instances) {
        int total = (instances == null) ? 0 : instances.size();
        if (total <= 1) {
            record(total, 0, "pass_through", "single_or_empty");
            return instances;
        }
        if (zone == null || zone.isBlank() || DEFAULT_ZONE.equals(zone)) {
            record(total, 0, "all", "invalid_zone");
            return instances;
        }
        List<ServiceInstance> sameZone = new ArrayList<>();
        for (ServiceInstance instance : instances) {
            Map<String, String> metadata = instance.getMetadata();
            String instanceZone = (metadata == null) ? null : metadata.get(METADATA_ZONE_KEY);   // "zone"
            if (zone.equals(instanceZone)) {
                sameZone.add(instance);
            }
        }
        if (sameZone.isEmpty()) {
            record(total, 0, "all", "no_same_zone");
            return instances;
        }
        if (sameZone.size() < sameZoneMinAvailable) {
            record(total, sameZone.size(), "all", "min_available");
            return instances;
        }
        record(total, sameZone.size(), "same_zone", "ok");
        return sameZone;
    }
```

**判定表**（同 §0）：

| 分支 | 条件 | 输出 | decision/reason |
|---|---|---|---|
| 直通 | 总数 ≤ 1 | 原样 | `pass_through / single_or_empty` |
| 不过滤 | zone 为空/`defaultZone` | 全部 | `all / invalid_zone` |
| 回退 | 无同 zone | 全部 | `all / no_same_zone` |
| 回退 | 同 zone < 阈值 | 全部 | `all / min_available` |
| 优先 | 其余 | 仅同 zone | `same_zone / ok` |

**指标**（Micrometer，可选依赖，`meterRegistry == null` 时静默跳过）：

```text
myxhs_zone_route_total{zone, decision, reason}    # 决策计数（每次 filter 一次）
myxhs_zone_instances{zone, kind=total|same}       # 当前实例数（gauge）
```

这两个指标是**排查"zone 优先到底有没有生效"的第一入口**，先区分两种"没生效"：

| 现象 | 含义 |
|---|---|
| 指标**一条都没有** | Registrar 没生效（开关没开 / 类路径缺失）——装配问题 |
| 有指标但持续 `decision=all, reason=invalid_zone` | 开关开了、过滤器装了，但网关没拿到自己的 zone——取值链问题（§5） |

---

## 4. 与 common 版的关系：同名不同实现

| | `my-xhs-common` 版 | `my-xhs-gateway` 版 |
|---|---|---|
| 类签名 | `ZonePreferenceFilter<E>`（泛型，可过滤任意实体） | `ZonePreferenceFilter`（只处理 `ServiceInstance`） |
| 决策复杂度 | **10 步决策流**（javadoc 明列）：功能开关/优先开关/无效 zone/**禁用 zone 列表**/**上游 zone 就绪百分比**/最小可用阈值/fallback... | **5 分支**：直通/无效 zone/无同 zone/阈值/优先 |
| 依赖 | `ZoneContext` + `ZoneResolver<E>` + `ZoneRouteMetrics`（common 体系） | 无（自持 Environment 取值 + 内联 Micrometer） |
| 指标 | 统一走 `ZoneRouteMetrics` | 内联 `myxhs_zone_route_total` / `myxhs_zone_instances` |
| 使用方 | 业务服务（经 common 自动配置） | 网关（经 `@LoadBalancerClients` 注入所有 `lb://`） |
| 注册方式 | `config/ZoneLoadBalancerConfig`：`@LoadBalancerClients(defaultConfiguration=...)` | `GatewayZoneLoadBalancerRegistrar`：**同一扩展点** + 总开关属性 |

**为什么允许"重复实现"**：网关是独立部署的边缘组件，刻意不依赖 common（业务公共库）；复刻的只是整条能力里**网关需要的最小闭环**。代价是两版逻辑要保持行为口径一致（同 zone 优先 + 不足回退），否则会出现"服务侧按多活走、网关侧没走"的语义漂移。

---

## 5. 那个真实 bug：取值链修复（源码注释实锤）

**修复前**：网关版只读 JVM 系统属性：

```java
System.getProperty("myxhs.current.availability.zone");
```

而这个属性是 common 的 `ZoneEnvironmentPostProcessor` 负责从环境变量桥接设置的——**网关没有 common，也就没人设置它**。部署脚本/容器通常只设 `MYXHS_ZONE`（各服务实例 metadata 也用这个变量，见 `zone: ${MYXHS_ZONE:defaultZone}`），于是：

```text
MYXHS_ZONE=shanghai（容器里设了）
  → 网关 System.getProperty(...) == null
  → zone = defaultZone
  → filter 走 invalid_zone 分支：所有实例都返回
  → "Zone 优先"静默失效（没有任何报错，流量照跑，只是不优先生效）
```

**修复后**的四级取值链（当前源码）：

```text
Spring Environment（yml/env 宽松绑定）→ JVM -D → MYXHS_ZONE 环境变量 → defaultZone
```

**教训**：把一份能力从一个部署形态"复刻"到另一个（服务库 → 网关进程）时，最容易漏的不是业务逻辑，而是**配置的来源桥接层**（环境变量 → 系统属性）。而且它失败的方式是"静默降级"，不配合指标（`decision/reason`）根本发现不了。

---

## 6. 行为证据与验证方法

**源码级证据**（本文所有引用）：
- 4 个文件全文：`my-xhs-gateway/.../gateway/zone/`（本文逐段引用）；
- 装配方式：`GatewayZoneLoadBalancerRegistrar` 的 `@LoadBalancerClients(defaultConfiguration=...)`（grep 实证：网关内唯一引用点）；
- 部署侧：各服务 yml `metadata.zone: ${MYXHS_ZONE:defaultZone}`；网关自身 yml 同样配了该 metadata。

**行为级验证（现状）**：
- 网关 zone 包**没有测试**（`src/test` 下无 zone 相关文件）；
- 行为等价的课程实验已跑过：**L16** 的 `ZonePreferredInstanceListSupplier`（课程版，简化实现）实测——`none=轮询 / gray=全 8082 / zone=全 8081`（见 `L16-custom-loadbalancer.md`）；
- 想在 xhs-demo 里做网关版对照实验（可选）：给 demo-gateway 加同款 supplier + 两个不同 zone 的 provider 实例，观察 `same_zone/ok` 分支与指标。

---

## 7. 边界与已知缺口（如实）

| 项 | 说明 |
|---|---|
| 决策时机 | 只在每次 `supplier.get()`（选实例前）；实例上下线跟随 SCLB 缓存（`withCaching()` 的默认刷新周期） |
| 静默失败面 | zone 恒为 `defaultZone` → 不过滤、无报错；只能靠 `myxhs_zone_route_total{reason="invalid_zone"}` 发现 |
| 不含的能力 | 跨 zone 权重、zone 故障转移策略、禁用 zone 列表、上游 zone 就绪百分比（这些在 common 版的 10 步里，网关版没有） |
| 与灰度的关系 | 网关里另有 `GrayRouteFilter`（按实例元数据 `gray-tag` + 请求头 `X-Gray-Tag` 做灰度）；**灰度（gray-tag）与多活（zone）是两条独立的过滤维度**，别混为一谈 |
| 重复实现风险 | 两版（common / gateway）逻辑需人工保持口径一致 |
| 开关 | 总开关 `myxhs.availability.zone.preference.enabled=true`；关掉则整包不装配 |

---

## 8. 与课程的关系 / 面试口径

- **L16**（课程版自定义 LB：gray/zone 实验）是最简单形态 → **本文**是生产形态（SCLB 装饰器链 + 子 context + 指标 + 开关）→ **L46（Gateway × SCLB）**会正式把这套讲进课程；
- 面试可讲："网关作为独立部署单元**不依赖业务公共库**，通过 `@LoadBalancerClients` 把自己的 `ServiceInstanceListSupplier` 注入全部 `lb://` client，实现同 zone 优先 + 阈值回退；并用 Micrometer 指标把'优先/回退/失效'三类决策全部暴露出来"；
- 排障可讲："zone 优先不生效时先看 `myxhs_zone_route_total` 的 `decision/reason`——`invalid_zone` 就是网关自己没拿到 zone（典型原因：配置桥接缺失）"。

---

## 9. 关联

- 课程：`L16-custom-loadbalancer.md`（课程版 zone/gray 实验）、`L42`（高可用·多活）、`L46`（Gateway × SCLB）
- 源码：`xhs-project/my-xhs-gateway/.../gateway/zone/`（4 文件）、`xhs-project/my-xhs-common/.../zone/`（43 文件）、`xhs-project/my-xhs-gateway/pom.xml`（不依赖 common 的实证）
