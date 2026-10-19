# A 类: 官方主流功能（loadbalancer-lab）

> 代码位置: `loadbalancer-lab/src/main/java/com/scalab/loadbalancer/`
> 应用端口: 28010，依赖 nacos-lab（28001/28003 双实例）

---

## 验证环境

```
loadbalancer-lab (28010)
    │  @LoadBalanced RestTemplate / RestClient
    ▼
LoadBalancer 选择实例（D1~D7 分析的完整链路）
    ▼
nacos-lab 实例 (28001 / 28003)   ← /discovery/status 返回端口，验证落点
```

启动：

```bash
cd /data/workspace/source-code/xhs-demo/loadbalancer-lab
./start-lb-lab.sh                        # 默认
./start-lb-lab.sh -Dscalab.lb.strategy=random   # A-02 随机
```

---

## A-01 轮询（RoundRobinLoadBalancer）

**真实集成测试**：`A01RealNacosIntegrationTest`（@Tag("real")，连真实 Nacos + nacos-lab 双实例）

```
[Real] round-robin distribution: {28001=10, 28003=10}
[Real] services: [nacos-lab, openfeign-lab, loadbalancer-lab]
[Real] nacos-lab ports: [28001, 28003]
```

真实连注册中心拉取实例 + 真实调用，20 次严格 10:10；服务列表里三个 lab 共存。

**离线集成测试**：`A01RoundRobinIntegrationTest`（mock DiscoveryClient + 内嵌 HttpServer）

```
[A-01] round-robin: port41465=10, port39687=10
```

**测试分层**（为什么两层）：

| 层 | 运行方式 | 依赖 | 场景 |
|----|---------|------|------|
| 离线测试 | `mvn test`（默认） | 无 | CI/离线兜底，验证算法与装配逻辑 |
| 真实测试 | `mvn test -Dtest=A01RealNacosIntegrationTest -Dexclude.groups=` | Nacos + nacos-lab | 真实环境验证注册中心集成 |

**手动验证**：`GET /lb/distribution?calls=20`（默认策略）

```
round-robin: {'28001': 10, '28003': 10}
```

20 次调用严格 5:5 均匀分布——AtomicInteger 轮询（D1 4.3）+ Supplier 链返回的实例列表。

## A-02 随机（RandomLoadBalancer）

**验证**：`-Dscalab.lb.strategy=random` 启动后 `GET /lb/distribution?calls=20` 两轮

```
random 第一轮: {'28001': 11, '28003': 9}
random 第二轮: {'28001': 8,  '28003': 12}
```

两轮分布不同——随机特征（对比轮询的严格均匀）。

**关键认知（踩坑记录）**：`spring.cloud.loadbalancer.configurations=random` **不合法**！
`configurations` 只控制 **Supplier 链**（D3 5.3 矩阵：default/zone-preference/weighted/subset/...），
random 不在矩阵 → 无 Supplier Bean 激活 → 实例列表为空 → "No instances available"。
切换 LoadBalancer 策略的正确姿势是**自定义 `ReactorLoadBalancer` Bean**（D3 的
`@ConditionalOnMissingBean` 覆盖机制），本 lab 通过 `@LoadBalancerClient(configuration=RandomLbConfig.class)`
+ `scalab.lb.strategy=random` 属性切换。

**RandomLbConfig 的隐蔽机制（lite mode）**：

```java
@LoadBalancerClient(name = "nacos-lab", configuration = RandomLbConfig.class)
public class RandomLbConfig {          // ← 故意不带 @Configuration！
    @Bean
    @ConditionalOnProperty(name = "scalab.lb.strategy", havingValue = "random")
    public ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(...) { ... }
}
```

1. **不带 `@Configuration`**：防止被主上下文组件扫描成全局配置（D3 分析过 per-client 配置类必须在扫描路径外）
2. **lite mode**：`@LoadBalancerClient` 把 RandomLbConfig 注册进子上下文时，`@Bean` 方法在 lite mode 下仍被处理（无需 @Configuration，只是没有 CGLIB 代理）
3. **条件评估**：`@ConditionalOnProperty` 在子上下文 refresh 时评估，读继承的 Environment（`scalab.lb.strategy` 系统属性可见）
4. **默认生效**：未设置属性时 @Bean 不激活 → 子上下文默认 RoundRobin（`@ConditionalOnMissingBean` 兜底，D3 5.1）

## A-03 实例列表供应（ServiceInstanceListSupplier）

**验证**：`GET /lb/instances`

```
services: ['nacos-lab', 'openfeign-lab', 'loadbalancer-lab']
nacos-lab: 21.214.97.212:28001, 21.214.97.212:28003
```

`DiscoveryClientServiceInstanceListSupplier`（D2 4.1）从 Nacos 拉取实例，默认链
`[Discovery → Caching]`（D3 5.3 default 配置）。

## A-17 阻塞客户端（BlockingLoadBalancerClient）

**验证**：`GET /lb/client-info`

```
restTemplateIsLoadBalanced: true      ← LoadBalancerInterceptor 已注入（D7）
restClientCall: {port: 28001}         ← RestClient Builder 场景（DeferringLoadBalancerInterceptor，D7）
```

两个入口都走 `BlockingLoadBalancerClient.execute()`（D5）→ `Mono.block()` 桥接（D5 四）。

## A-18 响应式接口（ReactorLoadBalancer）

无独立端点——A-01/A-02 的 RoundRobin/RandomLoadBalancer 即该接口的实现（D1）；
自定义实现可用 `@LoadBalancerClient(configuration=...)` 注册（如 xhs-project 的 `LeastConnectionsLoadBalancer`）。

---

## 待验证项

| 编号 | 内容 | 切换方式 |
|:----:|------|---------|
| A-04 | 同区域优先 | `configurations=zone-preference` + `spring.cloud.loadbalancer.zone` |
| A-05 | 健康检查 | `configurations=health-check` |
| A-06 | 缓存 | 默认链自带 Caching |
| A-07 | 权重 | `configurations=weighted` + Nacos metadata.weight |
| A-08 | 提示路由 | `configurations=hints` + X-SC-LB-Hint 头 |
| A-09 | 同实例优先 | `configurations=same-instance-preference` |
| A-10 | 子集选择 | `configurations=subset` |
| A-11 | 请求粘性会话 | `configurations=request-based-sticky-session` |

## 第二批验证结果（A-04~A-11）

| 编号 | 链结构（supplier-chain 端点实测） | 行为验证 |
|:----:|----------------------------------|---------|
| A-04 | `[ZonePreference → Caching → DiscoveryClient]` | 实例无 zone metadata → 回退全部（5:5）|
| A-05 | `[HealthCheck → DiscoveryClient]`（自定义配置） | 健康实例存活，分布 5:5 |
| A-06 | `[Caching → DiscoveryClient]`（默认链） | 链结构确认 |
| A-07 | `[Weighted → Caching → DiscoveryClient]` | 无 weight metadata → 默认权重 1 → 5:5 |
| A-08 | `[HintBased → Caching → DiscoveryClient]`（自定义配置） | 无 hint / gray 无匹配 → 回退全部（5:5）|
| A-09 | `[SameInstancePreference → Caching → DiscoveryClient]` | 粘住一个实例（两轮全 28003）|
| A-10 | `[Subset → Caching → DiscoveryClient]` | 2 实例 < size 100 → 全部返回（5:5）|
| A-11 | `[RequestBasedStickySession → Caching → DiscoveryClient]` | 无 cookie → 正常路由（5:5）|

### 第二批的两个重要认知修正（踩坑）

**① `configurations=hints` 不合法**（A-08）：

D3 5.3 矩阵里**没有 hints 行**——`HintBasedServiceInstanceListSupplier` 在
`LoadBalancerClientConfiguration` 中没有对应 @Bean，只能通过 Builder 接入。
`configurations=hints` 会导致无 Supplier 激活、实例列表为空
（"No instances available"）。修正：自定义 `HintLbConfig`（`withHints()`）+ `scalab.lb.hints=true` 开关。

**② health-check 的健康探测客户端不能是 @LoadBalanced 的**（A-05）：

默认 `configurations=health-check` 走 `withBlockingHealthChecks()`（内部
`getBean(RestTemplate.class)`）——loadbalancer-lab 的 RestTemplate 是 @LoadBalanced，
健康探测请求发往实例 IP 时被 LoadBalancerInterceptor 拦截（把 IP 当服务名二次 LB）
→ 死循环 → 全部实例被过滤（"No servers available for service: 21.214.97.212"）。

修复：应用提供**非负载均衡的 `plainRestClient`**（`RestClient` 类型，与 @LoadBalanced
RestTemplate 不冲突），自定义 `HealthCheckLbConfig` 用 `withBlockingHealthChecks(plainRestClient)`
重载显式传入 + `scalab.lb.health-check=true` 开关。

**排查方法**：先写隔离测试 `HealthCheckServiceInstanceListSupplierTest`（StubSupplier + 内嵌
HttpServer，200/500 各一个）确认机制本身正常，再定位应用配置问题。过程中还发现
`HttpStatus.OK.equals(int)` 恒为 false 的经典坑（statusCode() 返回 int，equals(Object)
装箱比较失败）——健康检查函数应写 `statusCode() == 200`。

## A-12 重试（RetryLoadBalancerInterceptor）

**前置改动**（本身默认不生效，需要接入）：
1. `pom.xml` 加 `spring-retry` 依赖——`RetryInterceptorAutoConfiguration` 靠
   `@ConditionalOnClass(RetryTemplate.class)` 激活，无此依赖时完全没有重试能力
2. `application.yml` 显式配置 `spring.cloud.loadbalancer.retry.retryable-status-codes: 500`
   （**踩坑**：`retryableStatusCodes` 默认是空集合，500 默认不会触发重试）
3. `loadBalancedRestTemplate` 加 `connectTimeout=2000/readTimeout=3000`（避免请求无限等待）
4. nacos-lab 新增故障模拟端点 `/echo/fail?status=&failPort=`（指定端口的实例始终失败，
   `failPort=0` 表示所有实例都失败）+ `/echo/fail/reset`（重置观测计数器）

**验证**：`GET /lb/retry-test?status=500&failPort=28001`（调用 nacos-lab 的 /echo/fail）

```
supplier-chain: [RetryAwareServiceInstanceListSupplier → Caching → DiscoveryClient]  ← 多了重试装饰器
```

**场景A：单实例故障，重试到健康实例成功**（`failPort=28001`，重复 5 次）
```
{"success":true,"response":{"attempt":1,"port":28003,"result":"success"}}
```
5 次全部成功，且全部由 28003（健康实例）响应——`RetryLoadBalancerInterceptor` 捕获 28001
的 500 → 触发重试 → `RetryAwareServiceInstanceListSupplier` 从候选列表剔除失败实例 →
换到 28003 → 成功。

**场景B：全部实例故障，重试耗尽后最终失败**（`failPort=0`）
```
{"success":false,"error":"InternalServerError: 500 ... "}
```
两实例调用计数各自 attempt=1（各打了一次）——先打其中一个失败，重试换到另一个也失败，
达到 `max-retries-on-next-service-instance` 默认值 1 后放弃，抛出异常。

**场景C：关闭重试对比**（`-Dspring.cloud.loadbalancer.retry.enabled=false` 重启，`failPort=28001`）
```
supplier-chain: [Caching → DiscoveryClient]  ← RetryAware 消失，链变回 2 层
```
连续 4 次调用：打到 28001 直接失败、打到 28003 才成功（约 50% 成功率，取决于轮询落点）——
与场景A（开启重试后 100% 成功）形成对照，证明重试机制确实在起作用。

**关键认知（踩坑记录）**：
1. `retryableStatusCodes` 默认空集合，500/503 等状态码**不会**自动触发重试，必须显式配置
2. `RetryAwareServiceInstanceListSupplier` 在重试时从候选列表中**移除** `previousServiceInstance`
   （不是用"计数器"隔离，而是直接剔除上次失败的实例），故障模拟端点若用"调用次数"计数（如
   "前N次失败"）会在每个实例各自累积——重试到新实例时该实例的计数器从 0 开始，仍会失败——
   应改用"按端口固定故障"的确定性设计，才能稳定复现"重试到健康实例后成功"
3. `spring.cloud.loadbalancer.retry.enabled=false` 会同时移除 `RetryAwareServiceInstanceListSupplier`
   （链深度从3变2）和 `RetryLoadBalancerInterceptor`——两者是配套的，缺一不可
4. **未验证场景**：本次只验证了 `retryable-status-codes: 500` 触发的重试，未验证
   `retryable-exceptions`（默认 IOException/TimeoutException）触发的重试。**已在 A-16
   验证中补测**：通过 `/lb/slow-test?seconds=5` 触发 readTimeout（3000ms）→
   SocketTimeoutException（is-a IOException）→ 重试一次（costMs≈6000ms=2×3000ms）→
   重试也失败 → 最终抛 ResourceAccessException。详见 A-16 场景A-2

## A-13 API 版本选择（BlockingApiVersionServiceInstanceListSupplier）

### 版本落差发现与解决

**版本核实**：用 `git show v4.1.4:.../LoadBalancerClientConfiguration.java` 确认
本 lab 运行时（SC 2023.0.3 / spring-cloud-commons **4.1.4**）**完全没有** ApiVersion
相关代码——该功能由 PR #1582（commit `77895a52`）引入，首次出现在 **v5.0.0-RC1**
（Spring Boot 4.x / Spring Cloud 2025.1+）。sca-lab 原基线 4.3.2 同样没有。

**解决路径（本 lab 不提供）**：4.1.4 下无法解锁该功能；如需 ApiVersion 需升级到
SC 2025.1+/commons 5.0.2（Boot 4.x）。原 sca-lab 文档中的 `spring-cloud-2025.1`
profile 机制**未迁入 xhs-demo**（父 POM 无该 profile）——本 lab 保留
`/lb/apiversion-test` 端点仅作版本落差说明。

### 版本落差小结

| 功能 | 4.1.4（本 lab） | 4.3.2（sca-lab 原基线） | 5.0.x+ |
|---|---|---|---|
| ApiVersion（A-13） | ❌ | ❌ | ✅（v5.0.0-RC1 起） |
| X-Forwarded 头（A-14） | ✅（@since 3.1.0） | ✅ | ✅ |

### 改动

1. nacos-lab `application.yml`：实例1 metadata `API_VERSION: "1.0.0"`
2. nacos-lab `application-instance2.yml`：实例2 metadata `API_VERSION: "2.0.0"`（覆盖主配置）
3. `LbTestController` 新增 `GET /lb/apiversion-test?apiVersion=` 端点（带 `X-API-Version` 头调用 nacos-lab `/discovery/status`）

### 验证（4 场景，sca-lab 时期实测——需 5.0.x profile；本 lab 4.1.4 不可复现）

**链结构**（`configurations=api-version`）：
```
[RetryAware → BlockingApiVersion → Caching → DiscoveryClient]  ← 4 层，多了 BlockingApiVersion
```

**场景A：`X-API-Version: 1.0.0` → 应路由到 28001**（5 次重复）
```
port=28001 metadata={API_VERSION: 1.0.0}  ← 100% 命中实例1
```

**场景B：`X-API-Version: 2.0.0` → 应路由到 28003**（5 次重复）
```
port=28003 metadata={API_VERSION: 2.0.0}  ← 100% 命中实例2
```

**场景C：`X-API-Version: 3.0.0`（无匹配实例）→ fallback 生效**（6 次重复）
```
28001/28003/28001/28003/28001/28003  ← 回退到所有实例轮询
```
（启动参数 `-Dspring.cloud.loadbalancer.api-version.fallback-to-available-instances=true`，
`BlockingApiVersionServiceInstanceListSupplier.filteredByVersion` 在无匹配且 fallback 开启时返回全部实例）

**场景D：不传版本头 → fallback 生效**，回退到任意实例。

**关键认知**：
1. ApiVersion 是**按请求维度**过滤实例（每次请求都从 `RequestDataContext` 读 `X-API-Version`，
   动态过滤 `metadata.API_VERSION` 匹配的实例子集），不是启动时固定的路由表
2. 版本号通过 `ApiVersionParser` 解析为 `Comparable` 后用 `equals` 匹配——默认
   `SemanticApiVersionParser`（来自 Spring Framework spring-web 模块），其具体解析行为
   **未在本 lab 验证**（源码不在 spring-cloud-commons 仓库）。实测 `1.0.0` 和 `2.0.0`
   能正确区分路由，但 `1.0.0` vs `1.0` 是否相等未测，建议使用完整三段式版本号
3. `configurations=api-version` 激活的是 `ApiVersionCondition`（`equalToForClientOrDefault`），
   与 weighted/zone-preference 同属 D3 5.3 矩阵，但**仅在 5.0.x+ 版本可用**
4. 与 weighted/hints 等不同，ApiVersion 在 **4.1.4/4.3.2 版本下均不存在**——这是版本落差而非配置错误，
   D2/D3 文档的相关记录已勘误

### 踩坑

**验证第三方框架某功能是否存在时，本地 clone 的仓库默认在 main 分支**——若未
`git checkout <正确版本tag>`，看到的可能是未来版本的代码，需先确认项目实际依赖的版本号，
再 `git show <tag>:<path>` 或 `git checkout <tag>` 核对。本次发现的 ApiVersion 功能在
main 分支存在但 4.3.2 tag 不存在，正是这个陷阱——详见 `feedback_git_source_version_pin.md`。

## A-14 X-Forwarded 头（XForwardedHeadersTransformer）

**前置核实**：`XForwardedHeadersTransformer` 在 **v4.1.4** 中存在（`@since 3.1.0`，比 A-13 的 ApiVersion 早得多，无版本问题）——core 与 blocking 包各一个类。
机制：`LoadBalancerProperties.XForwarded`（`enabled` 默认 `false`）控制是否在请求头
注入 `X-Forwarded-Host`（目标 `ServiceInstance` 的 host，不含端口）和
`X-Forwarded-Proto`（scheme）。`XForwardedHeadersTransformer` 作为
`LoadBalancerRequestTransformer` 在 `BlockingLoadBalancerClientAutoConfiguration`
中自动注册（`@ConditionalOnBean(LoadBalancerClientFactory.class)`）。

**验证**：新增 `GET /lb/xforwarded-test`（调用 nacos-lab 的 `/echo/headers` 回显请求头）

**场景A：默认（`x-forwarded.enabled` 未配置，即 false）**
```
{"xForwardedEnabled":"false（默认）","response":{"headers":{}}}
```
无任何 X- 开头的头被注入。

**场景B：开启**（`-Dspring.cloud.loadbalancer.x-forwarded.enabled=true` 重启）
```
{"xForwardedEnabled":"true","response":{"headers":{
    "x-forwarded-host":"21.214.97.212",
    "x-forwarded-proto":"http"
}}}
```
连续 4 次调用结果一致——`x-forwarded-host` 固定是 nacos-lab 实例的 IP（21.214.97.212），
不是 service name（nacos-lab）。**原因**：`XForwardedHeadersTransformer.transformRequest()`
接收的 request 是 `BlockingLoadBalancerRequest.apply()` 里创建的 `ServiceRequestWrapper`
（D5 分析过），其 `getURI()` 已从 `http://nacos-lab/...` 替换为 `http://21.214.97.212:28001/...`，
故 `getHost()` 返回实例 IP。源码只取 `getHost()`（不含端口），故两个实例的 host 相同、
无法从这个头区分落点，需配合 `/discovery/status` 返回的端口验证真实落点。

**关键认知**：这个头是"客户端→目标服务"方向的标记（告知目标服务真实请求的是
哪个 host/proto），不是反向代理场景的"客户端真实 IP 透传"语义（那是 X-Forwarded-For
的职责，本机制不涉及）。

## A-15 流式 Builder（ServiceInstanceListSupplierBuilder）

**定位**：D2 3.1/3.2 分析了 Builder 的 `Creator` / `DelegateCreator` 双抽象机制。
现有的 HealthCheckLbConfig / HintLbConfig 只用了 `withXxx()` 预设方法（D2 3.3 表格），
A-15 补一个 **`with(DelegateCreator)` 自定义装饰器** demo——这是 Builder 最灵活的扩展点，
允许在 Supplier 链中插入任意自定义装饰器，而不局限于框架预设的 16 个 `withXxx()`。

**改动**：新增 `BuilderDemoLbConfig.java`，用 `with(DelegateCreator)` 接入一个
`LoggingServiceInstanceListSupplier`（继承 `DelegatingServiceInstanceListSupplier`，
在 `get()` 时记录实例列表大小）。

```java
ServiceInstanceListSupplier.builder()
        .withBlockingDiscoveryClient()              // baseCreator（最内层）
        .withCaching()                              // DelegateCreator（第1层装饰器）
        .with((ctx, delegate) ->                    // DelegateCreator（第2层，自定义）
                new LoggingServiceInstanceListSupplier(delegate))
        .build(context);
```

**验证**：`-Dscalab.lb.builder-demo=true` 启动后 `GET /lb/supplier-chain`

```
chain: [RetryAware → LoggingServiceInstanceListSupplier → Caching → DiscoveryClient]
chainDepth: 4
```

`LoggingServiceInstanceListSupplier` 已注入链中（Caching 之外、RetryAware 之内）。
调用 `/lb/distribution?calls=3` 后查看日志：
```
[BuilderDemo] get() returned 2 instances for service 'nacos-lab'
[BuilderDemo] get() returned 2 instances for service 'nacos-lab'
[BuilderDemo] get() returned 2 instances for service 'nacos-lab'
```
3 次调用 3 条日志——自定义装饰器的 `get()` 确实被调用。**注意**：每次 distribution
调用都触发了 Logging.get()（calls=5 → 5 条日志），因为 Logging 在 Caching **外层**
（链：RetryAware→Logging→Caching→Discovery），Caching 只减少了 DiscoveryClient.get()
的调用次数，不影响 Logging.get() 的调用次数。

**关键认知**：
1. `with(DelegateCreator)` 是 Builder 的通用扩展点——`withXxx()` 预设方法内部都是
   用 `with(DelegateCreator)` 实现的（D2 3.2），用户可以直接用 `with()` 插入任意装饰器
2. 装饰器顺序由 `creators` 列表的 add 顺序决定（D2 3.2 build() 循环），`withCaching()`
   先于 `with(DelegateCreator)` 调用 → Caching 在内层，Logging 在外层
3. 自定义装饰器只需继承 `DelegatingServiceInstanceListSupplier` 并覆盖需要的 `get()` 方法，
   其余行为透传给 delegate——这是 D2 4.x 分析过的装饰器基类模式
4. `LoggingServiceInstanceListSupplier` 只覆盖了 `get()`，未覆盖 `get(Request)`——
   `ServiceInstanceListSupplier` 接口的 `get(Request)` default 方法委托给 `get()`，
   所以日志仍会触发。但如果装饰器需要从 Request 读信息（如 HintBased 读 hint 头），
   则必须覆盖 `get(Request)` 传递 Request 给 delegate
5. 这也是 B-02（自定义 ServiceInstanceListSupplier）的预热——B-02 将用同样模式实现
   功能性装饰器（而非仅日志观测）

## A-16 生命周期（MicrometerStatsLoadBalancerLifecycle）

**定位**：D6 分析了 `LoadBalancerLifecycle` 四回调接口（onStart/onStartRequest/onComplete）
和框架内置的 `MicrometerStatsLoadBalancerLifecycle`（4 个 Micrometer 指标）。本节验证
开启 `spring.cloud.loadbalancer.stats.micrometer.enabled=true` 后指标是否正确暴露。

**改动**：`application.yml` 新增 `management.endpoints.web.exposure.include: health,info,metrics`
（默认只暴露 health/info，需加 metrics 才能查询指标）。

**验证**：`-Dspring.cloud.loadbalancer.stats.micrometer.enabled=true` 启动后 `GET /lb/distribution?calls=10`

**场景A：开启 stats.micrometer**——10 次调用后查 `/actuator/metrics`

```
loadbalancer.requests.active    (Gauge)  VALUE: 0       ← 请求已完成，无活跃
loadbalancer.requests.success   (Timer)  COUNT: 10      ← 10 次成功调用
                                         TOTAL_TIME: ~0.054s
                                         MAX: ~0.018s
```

按实例维度过滤（D6 分析的 serviceInstance 标签）：
```
tag=serviceInstance.port:28001 → COUNT: 5    ← 10:10 轮询，每实例 5 次
tag=serviceInstance.port:28003 → COUNT: 5
```

availableTags 完整列表（与 D6 3.2 分析一致）：
- `serviceInstance.host`: 21.214.97.212
- `serviceInstance.port`: 28001, 28003
- `serviceInstance.instanceId`: 21.214.97.212#28001##DEFAULT_GROUP@@nacos-lab, ...
- `serviceId`: nacos-lab
- `outcome`: SUCCESS
- `status`: 200
- `method`/`uri`: UNKNOWN（success 场景下 RestTemplate 未记录到 RequestData）

`loadbalancer.requests.discard`（Counter）未出现——没有 DISCARD 事件（无可用实例）不注册。

**场景A-2：触发 failed 指标**——新增 `/lb/slow-test` 端点调 nacos-lab `/echo/slow?seconds=5`
（RestTemplate readTimeout=3000ms → SocketTimeoutException → IOException → FAILED 事件）

```
{"costMs":6011,"success":false,"error":"ResourceAccessException"}
```

costMs=6011ms ≈ 2×3000ms——说明**重试了一次**（A-12 的异常重试场景也由此验证：
SocketTimeoutException is-a IOException，在 retryableExceptions 默认集合中，触发重试）。

failed Timer 指标：
```
COUNT: 2          ← 第一次 + 重试一次，两次都失败
TOTAL_TIME: ~6s   ← 2×3s
MAX: ~3s          ← 单次 readTimeout 上限
exception: SocketTimeoutException    ← 精准定位异常类型
serviceInstance.port: 28001, 28003   ← 两个实例都试过了（重试换了实例）
method: GET, uri: /echo/slow         ← 注意：failed 场景 method/uri 有值（与 success 的 UNKNOWN 不同）
```

**method/uri 标签差异**：success 场景 method/uri=UNKNOWN，failed 场景 method/uri=GET//echo/slow。
原因：failed 事件经 `RetryLoadBalancerInterceptor` 的 `RetryableRequestContext`（包装了
`RequestData`），而 success 事件经 `BlockingLoadBalancerClient.execute()` 的默认 `DefaultRequest`（不含 RequestData）。

**场景B：关闭 stats.micrometer（默认）**——同样 10 次调用后查指标
```
loadbalancer 指标: 无（默认关闭）
```
`MicrometerStatsLoadBalancerLifecycle` 未注册（`LoadBalancerStatsAutoConfiguration` 靠
`@ConditionalOnProperty(stats.micrometer.enabled=true)` 激活），指标完全不存在。

**关键认知**：
1. `stats.micrometer.enabled` 默认 **false**——必须显式开启，否则 `MicrometerStatsLoadBalancerLifecycle`
   不注册，4 个指标都不存在
2. 指标按 `ServiceInstance` 维度打标签（host/port/instanceId），可按实例过滤查询——
   这是 D6 3.2 分析的 `LoadBalancerTags` 标签体系的实际效果
3. `discard`/`failed` 指标按需创建——只有发生 DISCARD（无可用实例）或 FAILED（IOException）
   事件时才注册。实测 failed 指标通过 `/echo/slow` 触发 readTimeout 产生，COUNT=2（含重试），
   exception 标签精准为 `SocketTimeoutException`
4. 自定义 `LoadBalancerLifecycle`（实现 onStartRequest/onComplete 采集响应时间等）注册为
   Bean 后会被 `LoadBalancerClientFactory.getInstances()` 自动发现——A-16 只验证框架内置的
   `MicrometerStatsLoadBalancerLifecycle`；自定义示例见 xhs-project 的 `LeastConnectionsLifecycle`
