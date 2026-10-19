# Lesson 19: Route Definition Sources（来源链）— "定义从哪来、存在哪、怎么合并"

> 课程索引：`../gateway-学习清单.md` → Stage 5 · Lesson 19（★v5.4 新增：补"来源链无课"的真缺口）
> 前置：L17（A/B 组总览；17.3.x 已首过三件套）· L08（Actuator 两视图；"改完要 refresh"）
> 口径：讲机制不逐行。本课回答两个问题：**① 四个来源怎么合成"一份定义流"；② 为什么 yml 路由 Actuator 删不掉**
> 与 17.3.x 的分工：17.3.x 给过"三件套"首过；本课把它们装进**一条完整来源链**（接口族 → 四来源 → 合并器 → 写入口）

---

## 19.0 掌握卡（只要记这些）

| # | 要掌握的 | 一句话 |
|---|---|---|
| **①** | **两个接口一条家族线** | `RouteDefinitionLocator`（**只读**）+ `RouteDefinitionWriter`（**可写**）→ `RouteDefinitionRepository` = 两者合体 |
| **②** | **四个来源 + 一个合并器** | yml / 内存 / 注册中心 / Redis → `CompositeRouteDefinitionLocator`（**@Primary**）合成**一份** `Flux<RouteDefinition>` |
| **③** | **L08 疑问的答案** | **Actuator 只写"内存那一份"** → yml 路由删不掉（404）；且写完要 **refresh** 才生效（定义 ≠ 编译成品） |

> **为什么有这一课**：L17 是总览、L08 是操作 —— 这一课把"**定义到底存在哪、谁读谁写、怎么合并**"一次钉死。
>
> **名词校准**（沿用 L17 的四组归纳）：**A 组 = 来源**（`RouteDefinitionLocator` 家族，产"定义"，本课主角）；**B 组 = 模型/编译**（`RouteLocator` 家族：`RouteDefinitionRouteLocator` 从 A 组**拉取定义并编译**成可运行的 `Route`）。

---

## 19.1 例子先行：L08 的两个"怪事"（实测）

**怪事 1（L17 实测过）**：用 Actuator `POST` 加一条路由后 ——
`/routedefinitions`（定义视图）**立刻** 6→7；`/routes`（运行视图）**要 refresh 后**才 6→7。

**怪事 2（本次实测）**：删除路由时，**两条路由两种命运**：

```
DELETE /actuator/gateway/routes/demo-provider   （yml 里定义的路由）→ HTTP 404   ❌ 删不掉
POST   /actuator/gateway/routes/tmp-l19-demo                       → HTTP 201   ✅ 加得上
DELETE /actuator/gateway/routes/tmp-l19-demo                       → HTTP 200   ✅ 删得掉
```

同一个端点、同一个动作，为什么一个 404 一个 200？
本课用一个模型解释这两件怪事：**"来源链"（多个定义仓库 → 合并成一份流 → 再编译）**。

---

## 19.2 家族线：两个接口（一读一写）

```java
// 只读：从"某个地方"吐出一堆路由定义
public interface RouteDefinitionLocator {
    Flux<RouteDefinition> getRouteDefinitions();
}

// 可写：保存 / 删除
public interface RouteDefinitionWriter {
    Mono<Void> save(Mono<RouteDefinition> route);
    Mono<Void> delete(Mono<String> routeId);
}

// 合体：既能读又能写（内存库、Redis 库都实现它）
public interface RouteDefinitionRepository extends RouteDefinitionLocator, RouteDefinitionWriter {
}
```

**有了这个拆分，事情就很清楚了**（拿四个来源对号入座）：

| 来源 | 实现哪个接口 | 为什么 |
|---|---|---|
| yml（`PropertiesRouteDefinitionLocator`） | **只实现 Locator** | yml 只能"读" —— 你没法通过网关 API 去改 yml 文件 |
| 内存（`InMemoryRouteDefinitionRepository`） | **Repository（读写）** | Actuator 要能加、能删、能列 |
| Redis（`RedisRouteDefinitionRepository`） | **Repository（读写）** | 路由存 Redis，多网关实例共享同一份 |
| 注册中心（`DiscoveryClientRouteDefinitionLocator`） | **只实现 Locator** | 服务实例列表是"读"出来的、路由是推导的，不给你直接写 |

> **一句话**：**Locator = "只读来源"；Repository = "可读可写的仓库"；Writer 是"写动作"抽出来的最小接口** ——
> Actuator 只依赖这个小接口（`RouteDefinitionWriter`），不绑死任何具体仓库。

---

## 19.3 来源 ①：yml —— `PropertiesRouteDefinitionLocator`

**先纠正一个常见误解：它不解析 yml！** yml 的解析/绑定是 Spring Boot 配置绑定干的（绑进 `GatewayProperties`），这个类只做一件事 —— 把绑好的数据吐出来：

```java
// org.springframework.cloud.gateway.config.PropertiesRouteDefinitionLocator（注意在 config 包）
public class PropertiesRouteDefinitionLocator implements RouteDefinitionLocator {
    private final GatewayProperties properties;
    public PropertiesRouteDefinitionLocator(GatewayProperties properties) {
        this.properties = properties;
    }
    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.fromIterable(this.properties.getRoutes()).filter(RouteDefinition::isEnabled);
    }
}
```

逐行：
- `properties.getRoutes()`：读**已绑定好的** `List<RouteDefinition>`（来源是 yml 的 `spring.cloud.gateway.routes`）
- `.filter(RouteDefinition::isEnabled)`：过滤掉被禁用的路由（`enabled` 字段**默认 true**，yml 可写 `enabled: false`）
- 整个类**没有任何"读取/解析文件"的代码** —— 它只是"配置对象的转发口"

装配（`GatewayAutoConfiguration`）：

```java
@Bean
@ConditionalOnMissingBean
public PropertiesRouteDefinitionLocator propertiesRouteDefinitionLocator(GatewayProperties properties) {
    return new PropertiesRouteDefinitionLocator(properties);
}
```

> **链路**：`yml → (Spring Boot 绑定) → GatewayProperties.routes → PropertiesRouteDefinitionLocator → Flux<RouteDefinition>`

`enabled` 的用途（补个例子）：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: gray-v1
          uri: http://svc-v1:8080
          enabled: false            # ← 定义保留；但 A 组输出时被过滤掉 → B 组拉不到 → 不编译成 Route（临时下线）
          predicates: [ Path=/api/** ]
```

（`RouteDefinition.enabled` 默认 `true`；`false` 时在上面 `.filter()` 那步被过滤掉 —— 定义还留在 yml 里，随时改回来。）

**为什么它没有 `Map`？** —— 因为 yml 的定义**根本不在它手里**：定义存在 `GatewayProperties.routes`，那是一个 **`List<RouteDefinition>`**（Spring Boot 配置绑定的产物）。
它是**只读来源**：没有"按 id 增删"的需求 → 不需要 `Map<id, RouteDefinition>`；吐数据就是 `Flux.fromIterable(list).filter(...)`。
（对比 19.4：内存库要 `save`（按 id 放，**覆盖 = 更新**）、`delete`（按 id 删）→ **必须能按 id 定位** → 所以它用 `Map`。）

---

## 19.4 来源 ②：内存 —— `InMemoryRouteDefinitionRepository`（Actuator 的写入口）

一个 `Map` 就是一个仓库：

```java
public class InMemoryRouteDefinitionRepository implements RouteDefinitionRepository {
    private final Map<String, RouteDefinition> routes = synchronizedMap(new LinkedHashMap<>());

    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        return route.flatMap(r -> {
            if (ObjectUtils.isEmpty(r.getId())) {
                return Mono.error(new IllegalArgumentException("id may not be empty"));
            }
            routes.put(r.getId(), r);
            return Mono.empty();
        });
    }

    @Override
    public Mono<Void> delete(Mono<String> routeId) {
        return routeId.flatMap(id -> {
            if (routes.containsKey(id)) {
                routes.remove(id);
                return Mono.empty();
            }
            return Mono.defer(() -> Mono.error(
                    new NotFoundException("RouteDefinition not found: " + routeId)));   // ★ 404 的来源
        });
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        Map<String, RouteDefinition> routesSafeCopy = new LinkedHashMap<>(routes);   // 快照，防并发
        return Flux.fromIterable(routesSafeCopy.values());
    }
}
```

**逐行讲解**：

| 代码 | 人话 | 为什么这么写 |
|---|---|---|
| `synchronizedMap(new LinkedHashMap<>())` | 一个**保序 + 线程安全**的 Map | 多线程读写（Actuator 线程 vs 请求线程）→ 要线程安全；保序 → 列表顺序稳定可预期 |
| `save`：`id 为空 → error` | 必须有 id | 没 id 后续无法定位/删除（注意：**Composite 对"来源吐出的定义"会自动补 id，但写入端要求调用方自己给**） |
| `save`：`routes.put(id, r)` | 放进去（同 id 覆盖） | **覆盖 = 更新** → 所以 Actuator 的 POST 也能当"改路由"用 |
| `delete`：找不到 → `NotFoundException` | 区分"删了"与"没这条" | 让调用方正确响应 —— **Actuator 据此返回 404**（19.7 的因果链主角） |
| `getRouteDefinitions`：先拷贝快照 | 读之前先复制一份 | 防"边遍历边被写"（并发安全） |

**把命令说全**（`POST /routes/tmp-l19-demo` 是简写）：

```bash
POST http://localhost:8080/actuator/gateway/routes/tmp-l19-demo    # ← 路径里的 tmp-l19-demo = 路由 id
Content-Type: application/json
{"id":"tmp-l19-demo","uri":"http://localhost:8081",
 "predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-l19/**"}}]}
# Body = 一条 RouteDefinition（声明式）→ save 进上面这个 Map → 201 Created
```

**和实测对上**（现场重跑）：POST → `201`；`/routedefinitions` 立刻 8→**9** 条（含 `tmp-l19-view`）；
而 `GET /routes/tmp-l19-view`（**运行视图**）→ **404**（还没 refresh，编译缓存里没有它）—— "怪事 1"在这接上了。

装配（注意条件）：

```java
@Bean
@ConditionalOnMissingBean(RouteDefinitionRepository.class)
public InMemoryRouteDefinitionRepository inMemoryRouteDefinitionRepository() {
    return new InMemoryRouteDefinitionRepository();
}
```

> "没别的 Repository 才装它" —— 你定义了自己的 `RouteDefinitionRepository`，它就让位（可接管点）。

---

## 19.5 来源 ③④：注册中心 / Redis（了解即可）

**注册中心来源（`DiscoveryClientRouteDefinitionLocator`）—— "服务实例 → 路由"的自动翻译**

- 解决什么：几十个服务，不想为每个服务手写 `lb://xxx + Path=/xxx/**`
- 开法：`spring.cloud.gateway.discovery.locator.enabled=true`（**默认关**）→ 之后**每个服务自动得到一条路由**（下面是源码默认值）：

```
routeIdPrefix 默认 = discoveryClient 类名 + "_"
id        = <前缀><serviceId>
uri       = "'lb://'+serviceId"                    （urlExpression 默认值）
predicate = Path=/<serviceId>/**                   （initPredicates 默认）
filter    = RewritePath（把 /<serviceId> 前缀去掉）  （initFilters 默认）
metadata  = 整个实例 metadata 复制过来
```

→ 举例：服务 `demo-provider` 有 `/hello`，客户端访问 **`/demo-provider/hello`** 即可（零配置）

- **短板（记住，L43 要用）**：它是**服务级**的 —— **网关并不知道下游有哪些端点**，`/demo-provider/a/b` 和 `/demo-provider/x/y` 一律透传（下游有没有这个接口它不管）
- 实测（demo）：原未开 → beans 里只有配置类；**现已在 yml 开启**（见本节末 Demo A）
- 细节 → **L44**

**Redis 来源（`RedisRouteDefinitionRepository`）—— 把"定义库"升级为多实例共享**

- 解决什么：InMemory 是"**每个网关实例一份**" —— 多实例部署时，你在 A 实例加的路由，B 实例看不到。换成 Redis 后**多网关共享同一份定义**
- 开法（两个条件，源码）：
  - 类级：`@ConditionalOnBean(ReactiveRedisTemplate)` + `spring.cloud.gateway.redis.enabled`（默认 true）
  - bean 级：**`spring.cloud.gateway.redis-route-definition-repository.enabled=true`**（显式打开）
  - 顺序：`@AutoConfigureBefore(GatewayAutoConfiguration)` → Redis 库**先生效** → InMemory 的 `@ConditionalOnMissingBean(RouteDefinitionRepository.class)` **自动让位**
  - → **Actuator 的 `routeDefinitionWriter` 改指向 Redis 库**（读写都落 Redis）
- key 结构：`routedefinition_<routeId>`（`createKey`）
- demo：**已加 `spring-boot-starter-data-redis-reactive` 并开启**（见本节末 Demo B）｜ 细节 → **L46**

**对照：microsphere-gateway（小马哥）不是"第五个来源"**

它**不在 `RouteDefinitionLocator` 家族里** —— 它扩展的是"**匹配与转发**"这条路（源码验证）：
- 引入自定义 scheme `we`（`RouteConstants.SCHEME = "we"`）
- **替换官方 `FilteringWebHandler`**：`FilteringWebHandlerBeanDefinitionRegistryPostProcessor` 把 bean 定义换成 `CachingFilteringWebHandler`（**不改 SCG 源码的扩展姿势**）
- 加 `WebEndpointMappingGlobalFilter`：读实例 metadata 里的**端点清单**，做**端点级**匹配/排除

与官方"注册中心来源"的对比：官方 = **服务级**（`Path=/serviceId/**` 路径透传）；microsphere = **端点级**（网关知道下游有哪些端点）。
详见 L43：`L43-polygateway-extension.md`。

### 实测（2026-09-30）：两个来源各跑一遍

**前置**：Nacos 在跑（本环境踩到 docker 端口转发失效 → 用 `--network host` 重建，见 `infra-nacos.md`）；provider 以 `version=v2` 注册（原因见下面联动坑）。

**Demo A：注册中心来源**（就加一行配置）

```yaml
spring.cloud.gateway.discovery.locator.enabled: true
```

开关一开，`/actuator/gateway/routedefinitions` 立刻多出一条（实测全文）：

```json
{
  "id": "ReactiveCompositeDiscoveryClient_demo-provider",
  "predicates": [ { "name": "Path", "args": { "pattern": "/demo-provider/**" } } ],
  "filters":    [ { "name": "RewritePath", "args": { "regexp": "/demo-provider/?(?<remaining>.*)", "replacement": "/${remaining}" } } ],
  "uri": "lb://demo-provider",
  "metadata": { "nacos.weight": "1.0", "zone": "shanghai", "version": "v2", "...": "实例 metadata 全量复制" }
}
```

| 请求 | 结果 |
|---|---|
| `GET /demo-provider/hello` | **200**（★ 零配置自动路由） |
| `GET /demo-provider/no-xxx` | **404 —— 但这是下游给的 404**（网关盲转发；"服务级"短板的直接实证） |

**联动坑（L16 知识点）**：demo 的 `demo.lb.mode: gray` → `GrayInstanceListSupplier(..., "v2")` **只放行 `version=v2` 的实例** ——
provider 必须以 `--spring.cloud.nacos.discovery.metadata.version=v2` 注册，否则 lb 过滤成空 → **503**（本次踩过）。

**Demo B：Redis 来源**

```yaml
spring.data.redis.host: 127.0.0.1     # 本机 native redis（docker 的 16379 端口转发当前坏）
spring.data.redis.port: 6379
spring.cloud.gateway.redis-route-definition-repository.enabled: true
```

| 观察 | 实测结果 |
|---|---|
| beans | `redisRouteDefinitionRepository` ✓；**`inMemoryRouteDefinitionRepository` 不存在（自动让位）** |
| POST `/actuator/gateway/routes/tmp-redis-demo` | 201 → `redis-cli keys 'routedefinition_*'` 出现 **`routedefinition_tmp-redis-demo`**，value = **JSON 化的 RouteDefinition** |
| `/actuator/gateway/routedefinitions` | 10 条（含 tmp） |
| DELETE | 200 → Redis key 清空 |

（这组实测同时证明了 19.7 的因果链：**Actuator 的 `routeDefinitionWriter` 现在指向 Redis 库** —— 因为 InMemory 让位了。）

**四个来源总表**：

| 来源 | 类 | 接口 | 开关/前置 | 详情课 |
|---|---|---|---|---|
| yml | `PropertiesRouteDefinitionLocator` | Locator（只读） | 默认有 | 本课 |
| 内存 | `InMemoryRouteDefinitionRepository` | **Repository（读写）** | 默认有（**Redis 开启时让位**） | 本课 + L08 |
| 注册中心 | `DiscoveryClientRouteDefinitionLocator` | Locator（只读） | `discovery.locator.enabled=true` | L44（本课已实测） |
| Redis | `RedisRouteDefinitionRepository` | **Repository（读写）** | 引 Redis + `redis-route-definition-repository.enabled=true` | L46（本课已实测） |

（旁注：`CachingRouteDefinitionLocator`（给来源加缓存、监听 `RefreshRoutesEvent` 清缓存）**代码存在但默认没装配** —— 只有测试引用，"备着没用上"。）

---

## 19.6 合并器：`CompositeRouteDefinitionLocator`（@Primary）

B 组只认**一个** `RouteDefinitionLocator` —— 多来源怎么办？加一个"合并器"：

```java
public class CompositeRouteDefinitionLocator implements RouteDefinitionLocator {
    private final Flux<RouteDefinitionLocator> delegates;
    private final IdGenerator idGenerator;   // 默认 new AlternativeJdkIdGenerator()

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return this.delegates.flatMapSequential(RouteDefinitionLocator::getRouteDefinitions)   // ★ 合并
            .flatMap(routeDefinition -> {
                if (routeDefinition.getId() == null) {          // ★ 没 id 的自动补一个
                    return randomId().map(id -> {
                        routeDefinition.setId(id);
                        ...
                        return routeDefinition;
                    });
                }
                return Mono.just(routeDefinition);
            });
    }
}
```

逐行讲解：
- **`delegates`**：`Flux<RouteDefinitionLocator>` —— "要合并的所有来源"
- **`flatMapSequential(...getRouteDefinitions)`**：**按顺序**把每个来源的定义流拼成**一条流**（顺序拼接、保留来源内顺序）—— 这就是"**四来源 → 一份定义流**"
- **`if (id == null) 自动补 id`**：定义没写 id 也放行，这里自动生成一个（保证下游按 id 处理）
- **"sequential" 的关键语义**：按 `delegates` 顺序逐个取、**每个来源内部保持原顺序**地拼成一条流
  （对比 `flatMap` 不保序）→ 最终定义顺序 = **delegates 顺序 × 来源内顺序**

装配（`@Primary` 就是它的"合并器身份"）：

```java
@Bean
@Primary
public RouteDefinitionLocator routeDefinitionLocator(List<RouteDefinitionLocator> routeDefinitionLocators) {
    return new CompositeRouteDefinitionLocator(Flux.fromIterable(routeDefinitionLocators));
}
```

- 参数 `List<RouteDefinitionLocator>` = **按类型全收**（同 L18 的机制）→ 任何来源 bean（含**你自己注册的**）自动进合并器
- `@Primary` → **B 组注入 `RouteDefinitionLocator` 时拿到的是它**（17.3.5 ① 验证过）

**实测（demo）**：

- 改造前（默认）：`delegates` = `[propertiesRouteDefinitionLocator, inMemoryRouteDefinitionRepository]`
- 开启 discovery + Redis 后：`delegates` = `[redisRouteDefinitionRepository, discoveryClientRouteDefinitionLocator, propertiesRouteDefinitionLocator]`
  （**定义流顺序 = delegates 顺序** → 所以 `/routedefinitions` 里 discovery 生成的路由排在 yml 路由**前面** —— Demo A 的输出可见 ✅）
- 结论：**你注册一个 `RouteDefinitionLocator` bean，就会自动多一个 delegate**（顺序由 bean 收集顺序决定，不要依赖它）

> **为什么要合并**：让 B 组对"几个来源"完全无感 —— 它只调 `getRouteDefinitions()` 拿一份流，不关心来源数目与类型。

---

## 19.7 写入口与"删不掉"的真相（回答 L08 怪事 2）

**Actuator 拿到的 `RouteDefinitionWriter` 是谁？** 看装配：Actuator 端点 bean 注入 `RouteDefinitionWriter`：

```java
public GatewayControllerEndpoint(..., RouteDefinitionWriter routeDefinitionWriter, ...)
```

**按类型注入** —— 谁实现了 `RouteDefinitionRepository`，写入口就是谁：

- **默认（没开 Redis）**：唯一的 `RouteDefinitionRepository` = `InMemoryRouteDefinitionRepository` → **Actuator 读写只落在"内存那一份"**
- **开了 Redis 后**：`RedisRouteDefinitionRepository` 先生效、InMemory 让位（19.5 Demo B）→ 写入口变成 **Redis 库**
- **两种情况下都不碰 yml 那份** —— 这正是"yml 路由删不掉"的本质：**Actuator 只写"可写来源"**

`save` / `delete` 端点源码（关键段）：

```java
@PostMapping("/routes/{id}")
public Mono<ResponseEntity<Object>> save(@PathVariable String id, @RequestBody RouteDefinition route) {
    return Mono.just(route)
        .doOnNext(this::validateRouteDefinition)
        .flatMap(routeDefinition -> this.routeDefinitionWriter.save(Mono.just(routeDefinition).map(r -> {
            r.setId(id);
            return r;
        })).then(...created...));
}

@DeleteMapping("/routes/{id}")
public Mono<ResponseEntity<Object>> delete(@PathVariable String id) {
    return this.routeDefinitionWriter.delete(Mono.just(id)).then(Mono.defer(() -> {
        publisher.publishEvent(new RouteDeletedEvent(this, id));      // 顺带发个"删除了"事件（L30 会用到）
        return Mono.just(ResponseEntity.ok().build());
    })).onErrorResume(t -> t instanceof NotFoundException, t -> Mono.just(ResponseEntity.notFound().build()));   // ★ 404 在这里
}
```

**因果链（把怪事 2 说圆）**：

```
DELETE /actuator/gateway/routes/demo-provider
  → routeDefinitionWriter = InMemoryRouteDefinitionRepository
  → 内存 Map 里没有 "demo-provider"（它在 yml 里！）
  → 抛 NotFoundException → Actuator 映射成 404          ← 删不掉 ✅

DELETE /actuator/gateway/routes/tmp-l19-demo（自己 POST 的）
  → 内存 Map 里有它（POST 时 save 进去的）
  → 删掉 → 200                                          ← 删得掉 ✅
```

**再加一层（解释怪事 1 的"为什么要 refresh"）**：
Actuator 改的是**定义**（原料库）；`/routes` 看的是 **B 组编译好的成品**（`CachingRouteLocator` 缓存）。
→ **改原料 ≠ 改成品**，必须 `POST /refresh` 让 B 组重新编译（L17 的刷新机制）。

**这个"两步走"带来的好处**（从代价看设计）：
- 写定义 = 数据操作（毫秒级）；重编译 = **全量重编译**（查 15 个谓词工厂 + 33 个过滤器工厂 + bind + apply，L17 的 `convertToRoute`）
- 所以可以"**批量改 N 条 → 只 refresh 一次**"；不用改一条刷一条
- 自动刷新当然也有：**配置中心推送 / 注册中心心跳**会转成 `RefreshRoutesEvent` 自动刷（L17 17.4.8 的触发源表）；**Actuator 手动改的**则要你手动刷

---

## 19.8 怎么自己验证

```bash
GW=http://localhost:8080/actuator/gateway
# ① 定义视图 vs 运行视图
curl -s --noproxy '*' $GW/routedefinitions | python3 -c "import json,sys;print('定义:',len(json.load(sys.stdin)))"
curl -s --noproxy '*' $GW/routes           | python3 -c "import json,sys;print('成品:',len(json.load(sys.stdin)))"

# ② 删 yml 路由（404）vs 删自己加的路由（200）
curl -s --noproxy '*' -X DELETE -o /dev/null -w "删 yml 路由: %{http_code}\n"          $GW/routes/demo-provider
curl -s --noproxy '*' -X POST   -o /dev/null -w "加临时路由: %{http_code}\n" $GW/routes/tmp-x \
  -H 'Content-Type: application/json' -d '{"id":"tmp-x","uri":"http://localhost:8081","predicates":[{"name":"Path","args":{"_genkey_0":"/tmp-x/**"}}]}'
curl -s --noproxy '*' -X DELETE -o /dev/null -w "删临时路由: %{http_code}\n"          $GW/routes/tmp-x
```

**查看方式速查（运行时怎么看 RouteDefinition）**：

| 想看 | 端点 | 读的是 |
|---|---|---|
| 全部**定义**（合并视图） | `GET /actuator/gateway/routedefinitions` | A 组 `Composite`（yml + 内存 + …） |
| 全部**成品**（编译后） | `GET /actuator/gateway/routes` | B 组编译缓存 |
| 单条**成品** | `GET /actuator/gateway/routes/{id}` | 同上（**未 refresh 时看不到新定义**，实测 404） |
| 单条**定义** | ❌ 无专门端点 | 从 `/routedefinitions` 按 id 过滤；或断点看 `GatewayProperties.routes`（yml 那部分） |

断点建议（配合 18.7 的调试方式）：
- `CompositeRouteDefinitionLocator#getRouteDefinitions`（看 delegates 合并）
- `InMemoryRouteDefinitionRepository#delete`（看 404 从哪来）

---

## 19.9 掌握边界（值不值得深挖）

| 内容 | 口径 |
|---|---|
| 注册中心/Redis 来源的**实现细节** | ❌ 用到再看（L44/L46） |
| `CachingRouteDefinitionLocator` | ❌ 默认没装配，知道有即可 |
| **两接口关系（Locator/Writer/Repository）** | ✅ 要会（面试"定义从哪来"第一句） |
| **四来源 + Composite 合并（flatMapSequential / 自动补 id）** | ✅ 要会 |
| **"删除 404 / 改完 refresh"的因果链** | ✅ 要会（L08 疑问的正式答案） |

---

## 19.10 Self-Test

1. `RouteDefinitionLocator` / `RouteDefinitionWriter` / `RouteDefinitionRepository` 三者什么关系？各自的方法？
2. 四个来源分别是谁、开关/前置是什么？
3. `Composite` 用什么把多来源拼成一份流？它对"没写 id"的定义做了什么？
4. B 组为什么只注入一个 `RouteDefinitionLocator` 也能看到所有来源？（@Primary + 按类型收集）
5. （因果链）`DELETE` 一条 yml 路由为什么 404？一条自己 POST 的路由为什么 200？
6. 为什么 Actuator 改完后 `/routes` 不马上变？（定义 vs 编译成品 + refresh）
7. （Demo B）开了 Redis 库后 Actuator 的写落到哪？`InMemoryRouteDefinitionRepository` 去哪了？（提示：让位条件）

---

## 19.11 Open Questions

- [x] ~~yml 路由 DELETE 的返回码~~：实测 **404**（`NotFoundException` → Actuator 映射）；自己 POST 的可删（200）。
- [x] ~~`DiscoveryClientRouteDefinitionLocator` 生成的路由长什么样~~：**Demo A 已实测**（id=`ReactiveCompositeDiscoveryClient_demo-provider`；`Path=/demo-provider/**` + `RewritePath` 去前缀 + `lb://` + 实例 metadata 全量复制）。细节 → L44。
- [~] `RedisRouteDefinitionRepository`：key = `routedefinition_<id>`（✓）、value = JSON 化 RouteDefinition（✓）；**TTL 行为未测** → 留 L46。
- [~] `Composite.delegates` 顺序：实测 `[redis, discovery, properties]` —— 由 bean 收集顺序决定，**不要依赖**（定义流顺序 = delegates 顺序）。
- [ ] **Redis 库的多实例共享**：第二个网关实例（8082）能否立刻看到 A 实例写的路由？（本次只测了单实例）→ 多实例专题/L49。
