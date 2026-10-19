# L44 Gateway × Nacos：服务发现 + 配置中心

> 用途：回答"网关和 Nacos 怎么打通"——自动路由从哪来、配置中心怎么改路由、Nacos 挂了怎么办
> 对应课程：**L44**（Stage 9 生态打通 开篇）
> 口径：SCG **4.1.5** / Spring Cloud 2023.0.3 / SCA **2023.0.1.2**（nacos-client 2.3.2）/ Nacos **2.2.3**（docker）；实测 2026-10-06
> 实验环境：demo-gateway:8080 · demo-provider:8081（v1）· demo-provider:8082（v2）· 本机 Nacos + Redis
> 前置：L16（`lb://` 与 SCLB）/ L19（路由来源链）/ L30（刷新链路）

---

## 0. 例子先行：Nacos 在网关里有"两副面孔"

同一个 Nacos，对网关提供两种完全不同的能力：

| 面孔 | 网关用它做什么 | 本文实验 |
|---|---|---|
| **注册中心**（discovery） | 自动生成路由：`/demo-provider/** → lb://demo-provider` | 实验 A |
| **配置中心**（config） | 动态提供路由定义：改配置 → 路由热生效 | 实验 B |
| （宕机） | 两套都断供时会怎样 | 实验 C |

本文实测的三条请求线：

```text
GET /provider/echo/x        ← 手写静态路由（uri=http://localhost:8081）   —— 不依赖 Nacos
GET /demo-provider/echo/x   ← 自动路由（uri=lb://demo-provider）          —— 依赖【注册中心】
GET /nacos-lab/echo/x       ← 配置中心动态路由（本次实现）                 —— 依赖【配置中心】
```

一句话：**注册中心决定"能路由到谁"（服务名级），配置中心决定"路由长什么样"（可热改）**。两者都断时，网关靠已生成的缓存和本地快照继续顶——但不再接受任何变化。

---

## 1. 掌握卡（3 条）

1. **自动路由**：`spring.cloud.gateway.discovery.locator.enabled=true` → 为每个服务生成 `/{serviceId}/** → lb://serviceId`（带 `RewritePath` 去前缀）。零配置，但**路径被服务名绑定**；要自由路径（`/api/xxx/**`）还是得手写。
2. **配置中心 → 动态路由**：两条路线——方案 A"把 `spring.cloud.gateway.routes` 整体放 Nacos、靠官方重绑"有**列表"整表替换"**的坑（多来源同配时**只有一张**列表整张生效、另一张被静默忽略，**不是按 index 合并**——2026-10-06 实测，见 §3.1）；本次用方案 B"**自定义监听器 + `RouteDefinitionWriter` + `RefreshRoutesEvent`**"，增删粒度可控（与 L47 的 Redis 路由仓库同一思想）。
3. **Nacos 挂了**：运行中的网关——静态路由、`lb://` 路由、已装载的动态路由**全部照常服务**（SCLB 缓存 + Nacos 客户端本地快照）；`health` 变 DOWN；日志持续重连。重启 Nacos 后**自动重连**：health 回 UP，配置监听恢复（改配置照常生效）。

---

## 2. 实验 A：注册中心 → 自动生成路由

### 2.1 开关与生成物

demo-gateway 的配置（L19 已开）：

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true      # 注册中心自动路由
```

actuator 实拍（`/actuator/gateway/routes`，节选）：

```text
ReactiveCompositeDiscoveryClient_demo-provider
  uri:       lb://demo-provider
  predicate: Paths: [/demo-provider/**], match trailing slash: true
  filters:   ["[[RewritePath /demo-provider/?(?<remaining>.*) = '/${remaining}'], order = 1]"]
```

拆解生成规则（本地 SCG 源码 `GatewayDiscoveryClientAutoConfiguration` + `DiscoveryClientRouteDefinitionLocator`）：

| 元素 | 生成规则 | 源码位置 |
|---|---|---|
| 路由 id | `<routeIdPrefix><serviceId>`；prefix 默认 = discoveryClient 类名 + `_`（实测 `ReactiveCompositeDiscoveryClient_`） | `DiscoveryClientRouteDefinitionLocator:71/:74` |
| 谓词 | `Path='/'+serviceId+'/**'`（默认注入，可配 `predicates` 覆盖） | `GatewayDiscoveryClientAutoConfiguration:62` |
| 过滤器 | `RewritePath /{serviceId}/?(?<remaining>.*) → /${remaining}`（去掉第一段服务名） | 同文件 `:76` |
| uri | 默认 `'lb://'+serviceId`（SpEL，可配 `url-expression`） | `DiscoveryLocatorProperties.urlExpression` |
| 其余可配 | `include-expression`（默认 `true`）、`route-id-prefix`、`lower-case-service-id`（默认 `false`）、`filters` | `spring.cloud.gateway.discovery.locator.*` |

### 2.2 请求实测 + 一个 503 插曲

`GET /demo-provider/echo/hi-from-auto-route`：

- 第一发 **503**——原因是本项目开了灰度 LB（`demo.lb.mode: gray`，只发 `version=v2` 的实例），当时只有 v1（8081）在线 → 没有可用实例；
- 起一个 v2 实例（8082，`--spring.cloud.nacos.discovery.metadata.version=v2`）后再打 → **200**，应答体 `"port":8082`，响应头含 `x-forwarded-prefix: /demo-provider`。

这个插曲恰好证明一件事：**自动路由的 `lb://` 与手写路由共用同一套 SCLB 实例选择链**（灰度/zone 等策略对它同样生效）——自动路由不是"另一条路"，只是"自动生成的路由定义"。

### 2.3 选型：自动生成 vs 手写

| | `discovery.locator` 自动路由 | 手写路由 |
|---|---|---|
| 配置成本 | **零配置**（服务注册即生效） | 每个服务/路径手工维护 |
| 路径 | **被服务名绑定**：`/demo-provider/**` | 自由：`/api/orders/**`、`/pay/**`… |
| 控制粒度 | 服务级（无法端点级匹配、无法按端点排除内部接口） | 任意（谓词/过滤器随意组合） |
| 适合 | 内部调用、临时环境、服务名即对外契约 | 对外 API、需要路径解耦/治理策略 |
| 端点级自动路由 | ❌（另一条路：L43 polygateway 的端点级路由） | — |

**生产组合**：对外用**手写路由**（路径自由 + 策略齐），内网/调试用 `locator` 兜底——两者在 `CompositeRouteDefinitionLocator` 里天然共存（L19）。

---

## 3. 实验 B：配置中心 → 动态路由

### 3.1 两条实现路线

| | 方案 A：整体 rebind | 方案 B：监听器 + 写入口（本次采用） |
|---|---|---|
| 做法 | `spring.cloud.gateway.routes` 全部放 Nacos，靠 `GatewayProperties` 重绑 + `RouteRefreshListener` 触发刷新 | Nacos 配置（JSON）→ 自定义监听器解析 → `RouteDefinitionWriter` 增删 → 发 `RefreshRoutesEvent` |
| 代码量 | 零代码 | 一个组件（约 100 行） |
| 增删粒度 | 整体替换 | **单个路由粒度可控**（本实验：热增/热删） |
| 坑 | **列表是"整表替换"（实测）**：本地 yml 与 Nacos 同时定义 `routes` 时，**只有一个来源的整张列表生效、另一来源整张被静默忽略**——不按 index 混合、缺字段也不从另一来源回填；要用 A 必须把**全部路由**放同一来源（都进 Nacos，本地一条不留） | 无（独立 dataId，不碰本地 routes） |
| 同思想 | — | L47 的 `RedisRouteDefinitionRepository`、L43 的"生成期翻译" |

**实测："按 index 合并"是错觉，实际是"整表替换"**（2026-10-06，Boot 3.2.5 `Binder` + 双 `MapPropertySource`）：

| 场景 | 输入（同一列表键） | 绑定结果 |
|---|---|---|
| 远端(高) / 本地(低) | 远端 2 条（第 2 条缺 uri）；本地 3 条 | **2 条**（全来自远端）；第 2 条 `uri=null`（**不从本地回填**）；本地第 3 条整条消失 |
| 本地(高) / 远端(低) | 本地 3 条；远端 2 条 | **3 条**（全来自本地）；远端 2 条**整张被忽略** |

- 官方同口径："When lists are configured in more than one place, overriding works by replacing the entire list."（Spring Boot 文档 *Merging Complex Types*）
- 机制：`IndexedElementsBinder.bindIndexed` 按优先级遍历配置来源，**第一个能提供该列表的来源生效后立即 return**，其余来源完全不参与。

### 3.2 实现（demo-gateway 新增）

**依赖**（pom.xml）：`spring-cloud-starter-alibaba-nacos-config`

**配置**（application.yml）：

```yaml
spring:
  cloud:
    nacos:
      config:
        server-addr: localhost:8848
        import-check:
          enabled: false   # 本实验根本不走 spring.config.import：路由 JSON 由监听器用 Nacos SDK 直读
```

> 注：**为什么没有 `spring.config.import`？因为方案 B 根本不用它**——路由 JSON 由监听器经 Nacos SDK **直接读取**（`configService.getConfig(DATA_ID, GROUP, 5000)` + `addListener`），内容不进入 Spring Environment。`import-check.enabled=false` 不是"import 写漏了"，而是关掉 SCA 2023 的默认校验——"引了 nacos-config 就必须配 `spring.config.import`"，不配则启动报 "The spring.config.import property is missing a nacos: entry"；这个开关正是为"SDK 直读"模式放行（替代方案：给一个 `optional:nacos:xxx.yml`）。

**监听器**（`com.myxhs.demo.gateway.route.NacosDynamicRouteListener`，完整代码）：

```java
@Component
public class NacosDynamicRouteListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(NacosDynamicRouteListener.class);
    private static final String DATA_ID = "demo-gateway-dynamic-routes.json";
    private static final String GROUP = "DEFAULT_GROUP";

    private final NacosConfigManager nacosConfigManager;
    private final RouteDefinitionWriter routeDefinitionWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> managedRouteIds = ConcurrentHashMap.newKeySet();

    public NacosDynamicRouteListener(NacosConfigManager nacosConfigManager,
                                     RouteDefinitionWriter routeDefinitionWriter,
                                     ApplicationEventPublisher eventPublisher) {
        this.nacosConfigManager = nacosConfigManager;
        this.routeDefinitionWriter = routeDefinitionWriter;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            ConfigService configService = nacosConfigManager.getConfigService();
            apply(configService.getConfig(DATA_ID, GROUP, 5000));          // 启动装载
            configService.addListener(DATA_ID, GROUP, new AbstractListener() {   // 订阅变更
                @Override
                public void receiveConfigInfo(String configInfo) {
                    log.info("[nacos-route] config changed: {}@{}", DATA_ID, GROUP);
                    apply(configInfo);
                }
            });
            log.info("[nacos-route] listener registered for {}@{}", DATA_ID, GROUP);
        } catch (Exception e) {
            log.warn("[nacos-route] init failed: {}", e.toString());
        }
    }

    private void apply(String content) {
        try {
            List<RouteDefinition> routes = (content == null || content.isBlank())
                    ? List.of()
                    : objectMapper.readValue(content, new TypeReference<List<RouteDefinition>>() {});
            Set<String> newIds = new HashSet<>();
            for (RouteDefinition route : routes) {
                newIds.add(route.getId());
                routeDefinitionWriter.save(Mono.just(route)).block();      // 新增/覆盖
            }
            for (String oldId : managedRouteIds) {
                if (!newIds.contains(oldId)) {
                    routeDefinitionWriter.delete(Mono.just(oldId)).block(); // 删除已下线
                }
            }
            managedRouteIds.clear();
            managedRouteIds.addAll(newIds);
            eventPublisher.publishEvent(new RefreshRoutesEvent(this));      // 触发路由重建（L30 链路）
            log.info("[nacos-route] applied {} route(s): {}", routes.size(), newIds);
        } catch (Exception e) {
            log.warn("[nacos-route] apply failed: {}", e.toString());
        }
    }
}
```

三个关键设计：
- **官方写入口**：`RouteDefinitionWriter` 与 actuator 的 `POST /actuator/gateway/routes/{id}` 是同一个写入口（本项目开了 `redis-route-definition-repository`，写落 **Redis**，键 `routedefinition_<id>`，见 SCG 源码 `RedisRouteDefinitionRepository:42`）；
- **只托管自己的路由**：`managedRouteIds` 记录本监听器写过的 id，删除时只删自己——不碰 yml 手写路由；
- **JSON 格式 = SCG 原生 `RouteDefinition`**：`id/uri/predicates/filters`，`args` 用 `_genkey_N`（与 yml 位置参数等价）。

Nacos 侧配置（dataId=`demo-gateway-dynamic-routes.json`，DEFAULT_GROUP）：

```json
[
  { "id": "nacos-lab-route",  "uri": "http://localhost:8081",
    "predicates": [ { "name": "Path", "args": { "_genkey_0": "/nacos-lab/**" } } ],
    "filters":    [ { "name": "StripPrefix", "args": { "_genkey_0": "1" } } ] },
  { "id": "nacos-lab2-route", "uri": "http://localhost:8081",
    "predicates": [ { "name": "Path", "args": { "_genkey_0": "/nacos-lab2/**" } } ],
    "filters":    [ { "name": "StripPrefix", "args": { "_genkey_0": "1" } } ] }
]
```

### 3.3 全生命周期实测（不用重启网关）

```text
17:33:19 [main]            [nacos-route] applied 1 route(s): [nacos-lab-route]         ← 启动装载
17:33:19 [main]            [nacos-route] listener registered for demo-gateway-dynamic-routes.json@DEFAULT_GROUP
17:35:10 [listener.task-0] config changed → applied 2 route(s)                          ← 热增
17:35:50 [listener.task-0] applied 1 route(s)（发布 route1-only）                        ← 热删
17:35:55 [listener.task-0] config changed → applied 2 route(s)（恢复）                   ← 再增
```

请求侧对照：

| 阶段 | `GET /nacos-lab/echo/x` | `GET /nacos-lab2/echo/x` |
|---|---|---|
| 初始（只配 route1） | **200**（`x-forwarded-prefix: /nacos-lab`） | 无此路由 |
| 热增 route2 | 200 | **200**（`x-forwarded-prefix: /nacos-lab2`） |
| 热删 route2 | 200 | **404**（路由被移除） |
| 恢复 route2 | 200 | **200** |

**结论**：配置变更 → 监听器 → 写入口 → `RefreshRoutesEvent` → `CachingRouteLocator` 重建（L30 链路）——整条链**不重启网关**。

---

## 4. 实验 C：Nacos 挂了会怎样

### 4.1 停 Nacos：三条路由全活，health DOWN

```
docker stop nacos
```

| 指标 | 停后立即 | 停后 ~1.5 分钟 |
|---|---|---|
| provider（静态 http） | 200 | 200 |
| lb（灰度 → v2） | 200 | 200 |
| nacos-lab（动态路由） | 200 | 200 |
| `/actuator/health` | **DOWN** | DOWN |

为什么路由还全活？两层兜底：

1. **SCLB 缓存**：`CachingServiceInstanceListSupplier`（默认 TTL 35s）——缓存过期前直接命中；
2. **Nacos 客户端本地快照**：过期后回源 `NacosDiscoveryClient`，客户端断连期间用**本地缓存/磁盘快照**兜底。实拍快照目录：

```text
~/nacos/naming/public/DEFAULT_GROUP@@demo-provider      ← 实例列表快照（磁盘）
~/nacos/naming/public/DEFAULT_GROUP@@demo-gateway
...
```

网关日志同步刷"重连中"（每 5 秒一档，持续重试）：

```text
Fail to connect server, after trying 40 times, last try server is {serverIp = 'localhost', server main port = 8848}
```

**关键区分**：停 Nacos **不断已生成的路由和已缓存的实例**；断的是"**接受变化**"的能力——新配置、新实例、实例上下线全部暂停（无事件源）。health DOWN 只是健康指示器如实反映了"发现客户端不可用"。

### 4.2 恢复：自动重连，监听器照常工作

```
docker start nacos
```

- Nacos readiness 200 → 网关 `health` 回 **UP**（客户端自动重连，无需重启网关）；
- 三条路由仍全部 200；
- **配置监听自动恢复**：删 route2 → `applied 1 route(s)` → `/nacos-lab2` 404；恢复 → `applied 2 route(s)` → 200（日志 `[listener.task-0] config changed`）。

### 4.3 一个前置设计（本项目已做）

demo-gateway 的 discovery 配置里有一行注释值得记住：

```yaml
register-enabled: false   # 网关【不注册自己】：注册自己会让【网关启动依赖 Nacos 可用性】
                          # （实测踩过：Nacos gRPC 抖动 → 网关启动失败）
```

**运行中的高可用靠缓存（本实验）**；**启动期的高可用靠"不注册自己"**（边缘组件由 DNS/SLB 发现，不需要被发现）——两件事互补。

---

## 5. 坑与边界

| # | 坑 | 说明 |
|---|---|---|
| 1 | **引入 nacos-config 后必须处理 import-check** | SCA 2023 默认要求 `spring.config.import`；不用就 `import-check.enabled=false`，否则启动失败 |
| 2 | **方案 A 的列表"整表替换"坑** | `spring.cloud.gateway.routes` 是列表：多来源同时定义时**只有一个来源整张生效、其余整张被静默忽略**（非按 index 合并；2026-10-06 实测）——本地与 Nacos 混写必丢一边；要用方案 A 就把全部路由放同一来源 |
| 3 | **自动路由路径被服务名绑定** | `/demo-provider/**` 直接暴露服务名；对外契约建议手写路由 |
| 4 | **health DOWN ≠ 网关不可用** | 停 Nacos 后路由照常服务；监控告警要分清"health 抖"与"流量跌" |
| 5 | **动态路由落 Redis（本项目配置）** | 开了 `redis-route-definition-repository` 时，动态路由写 Redis；Redis 也挂了则这部分失效（L47 展开） |
| 6 | **启动期依赖** | 网关自身 `register-enabled: false` 已解耦注册；但 `locator` 与动态路由都需要 Nacos 可达才能装载 |

---

## 6. Self-Test

1. 自动路由生成的路由 id 前缀、路径谓词、默认过滤器分别是什么？`RewritePath` 的作用是什么？
2. 为什么 `discovery.locator` 的自动路由在灰度模式下会 503？这说明了什么？
3. 方案 B 里 `managedRouteIds` 集合的作用是什么？不维护它会出什么问题？
4. 停 Nacos 后 1.5 分钟，为什么 `lb://` 路由还能 200？是哪两层缓存在兜底？
5. 动态路由方案 B 与 L47 的 Redis 路由仓库有什么共同点？

<details>
<summary>参考答案</summary>

1. id 前缀默认是 discoveryClient 类名 + `_`（实测 `ReactiveCompositeDiscoveryClient_`）；谓词 `/{serviceId}/**`；默认过滤器 `RewritePath` 把第一段服务名剥掉——否则下游会收到 `/demo-provider/echo/x` 而不是 `/echo/x`。
2. 因为它生成的是 `lb://demo-provider`，走同一套 SCLB 实例选择链；灰度供应商只保留 `version=v2`，v2 不在线就没有实例 → 503。说明自动路由与手写路由共用 LB 策略。
3. 记录"本监听器写过的路由 id"；变更时只删自己托管、且新配置里已不存在的路由，避免误删 yml 手写路由或 actuator 加的路由。
4. SCLB 的 `CachingServiceInstanceListSupplier`（默认 35s TTL）+ Nacos 客户端断连后的本地缓存/磁盘快照（`~/nacos/naming/...`）。
5. 都是"`RouteDefinitionWriter` 写入口 + 外部存储（Redis/Nacos 配置）+ `RefreshRoutesEvent` 触发重建"的模式。

</details>

---

## 7. 关联

- **L16**（`lb://` 与 SCLB——实验 A 的 503 插曲正是它的灰度链）/ **L19**（路由来源链：自动路由在家族中的位置）/ **L30**（`RefreshRoutesEvent` 刷新链路——实验 B 的下半段）
- **L43**（polygateway：另一条"端点级自动路由"，与本文的"服务级自动路由"对比）
- **L45**（Gateway × Sentinel）/ **L47**（Redis 路由仓库——方案 B 的同思想路线）
- 实验室：demo-gateway `route/NacosDynamicRouteListener`（本次新增）；Nacos dataId `demo-gateway-dynamic-routes.json`
