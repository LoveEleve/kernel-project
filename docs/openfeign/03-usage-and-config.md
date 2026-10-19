# FEIGN-03 基础使用与配置

> 用途：会写会配——注解与参数绑定速查、`configuration` 的正确姿势、**配置优先级与键名细节**、日志级别
> 对应系列：**OpenFeign 专题 · 03**（使用篇第一篇）
> 口径：Spring Cloud OpenFeign 4.1.3 / feign-core 13.3
> 素材：`openfeign-lab`（[A-01 声明式调用与配置](../../openfeign-lab/docs/A-01-声明式调用与配置.md) / [D7 配置优先级](../../openfeign-lab/docs/D7-FeignClientProperties配置优先级.md)）· 官方 docs（本地仓库 `spring-cloud-openfeign.adoc`）· xhs-project（`InternalCallFeignConfig` / payment yml）
> 前置：01/02；后续：04 拦截器 → 05 超时与客户端 → 06 容错 → 07 LB 与重试

---

## 0. 例子先行：一个最小可用客户端（全流程）

```java
// ① 启动类
@EnableFeignClients(basePackages = "com.myxhs.demo.feign")
@SpringBootApplication
public class DemoApplication { }
```

```java
// ② 客户端接口
@FeignClient(name = "demo-provider", contextId = "demoProviderClient")
public interface DemoProviderClient {
    @GetMapping("/hello")
    String hello(@RequestParam("name") String name);
}
```

```yaml
# ③ 配置（按客户端）
spring.cloud.openfeign.client.config.demoProviderClient:   # ★ key = contextId（见 §4）
  connect-timeout: 2000
  read-timeout: 5000
  logger-level: basic

# ④ 让日志真的输出（logger 名 = 接口全限定类名）
logging.level.com.myxhs.demo.feign.DemoProviderClient: DEBUG
```

```java
// ⑤ 调用
@Autowired DemoProviderClient client;
String r = client.hello("world");   // 像本地方法；背后是 HTTP（02 的机制）
```

---

## 1. 掌握卡（3 条）

1. **注解面**：`@FeignClient`（寻址/配置/降级） + **Spring MVC 注解**描述请求（`@GetMapping`/`@RequestParam`/`@PathVariable`/`@RequestBody`…）——由 `SpringMvcContract` 解析（02）。
2. **配置生效顺序**：默认（`defaultToProperties=true`）**先 Bean 后属性**——`@FeignClient(configuration=X)` 的显式 Bean 先装，然后 `client.config.<key>.* > client.config.default.*` 的属性**后应用、同名覆盖 Bean**；`key = contextId`（缺省 = `name`）；新旧前缀别写混（`spring.cloud.openfeign.*` 才是 4.x 绑定）。
3. **日志两块开关**：`logger-level`（NONE/BASIC/HEADERS/FULL，默认 NONE）负责"记多少"；`logging.level.<接口 FQN>: DEBUG` 负责"发不发"——**两个都要开**。

---

## 2. 注解与参数绑定速查

**请求描述**（Spring MVC 风格，`SpringMvcContract` 支持）：

| 注解 | 用途 | 示例 |
|---|---|---|
| `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping` | 方法与路径 | `@PostMapping("/users/{id}")` |
| `@PathVariable` | 路径变量 | `@PathVariable("id") Long id` |
| `@RequestParam` | query / 表单参数 | `@RequestParam("name") String name` |
| `@RequestBody` | JSON 请求体 | `@RequestBody OrderDTO dto` |
| `@RequestHeader` | 请求头 | `@RequestHeader("X-Tenant") String t` |
| `@RequestPart` / form | 文件/表单（少用） | — |

**`@FeignClient` 关键字段**：

| 字段 | 作用 |
|---|---|
| `name` / `value` | **服务名**：寻址（LB）+ 配置键缺省值 |
| `contextId` | **子上下文 key**（同名客户端区分；也是配置键，见 §4） |
| `url` | 直连 URL（跳过注册中心，调试/特殊场景用） |
| `path` | 接口级统一路径前缀 |
| `configuration` | 子上下文配置类（§3） |
| `fallback` / `fallbackFactory` | 降级（06） |
| `primary` / `qualifiers` | 注入语义（Bean 侧） |

---

## 3. `configuration` 的写法与坑

**写法**：一个类 + `@Bean` 方法，被 `@FeignClient(configuration=…)` 注册进**子上下文**：

```java
// xhs 生产实例（节选）：给内部调用注入 X-Internal-Call 令牌
@Configuration
public class InternalCallFeignConfig {
    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @Bean
    public RequestInterceptor internalCallInterceptor() {
        return template -> {
            String path = template.path();
            if (path != null && path.contains("pay-success")) {
                template.header("X-Internal-Call", internalToken);   // 只给特定路径加
            }
        };
    }
}
```

**坑**：`configuration` 类会被放进子上下文；**如果它同时被主包扫描（@ComponentScan 覆盖到），其中的 Bean 也会进主上下文 → 对所有客户端全局生效**。判断：

- xhs 这个例子是 **@Configuration + 被扫描**，但拦截器**自带路径过滤**，全局生效是"有意为之"；
- 换成 `Encoder`/`Decoder`/`Contract` 这类，全局泄漏就会改掉所有客户端的行为——此时应把配置类**移出扫描包**（或不要 @Configuration）。

---

## 4. 配置优先级与键名细节（本节是重点）

**默认生效顺序**（`FeignClientFactoryBean.configureFeign` 源码；两个开关默认都是"开/true"）：

```text
① 先：@FeignClient(configuration = X.class) 的显式 Bean（子上下文）
② 后：属性 —— client.config.<key>.*（按客户端）> client.config.default.*（全局默认）
                    ↑ 后应用，同名设置覆盖 Bean
```

记一句话：**"后应用的赢，默认属性赢"**（开关 `spring.cloud.openfeign.client.default-to-properties=false` 时反转：Bean 赢）。但要注意能力边界：**属性表达不了实例对象**（Decoder/Encoder 实例这类只能用 Bean）；属性能表达的（日志级别/超时/重试类名…）默认看属性。

**键名细节（容易踩）**：

- **`<key>` = `contextId`**（**缺省 = `name`**）——源码依据：`FeignClientsRegistrar.getContextId()`（无 contextId 时返回 name）+ `FeignClientFactoryBean` 用 `properties.getConfig().get(contextId)` 查配置；**所以设了 contextId 后，配置键要跟着改成 contextId**；
- **前缀**：`spring.cloud.openfeign.*`（4.x）；`feign.client.*` 旧前缀 4.1.3 已不绑定（01 已核）；例外：`feign.sentinel.enabled` 仍是 `feign.*`（SCA 的属性，06）；
- **超时单位**：毫秒整数（`connect-timeout: 2000`）。

---

## 5. 日志：两个开关都要开

**官方原话**（本地 docs）："A logger is created for each Feign client... **the name of the logger is the full class name of the interface**... Feign logging only responds to the `DEBUG` level."

| 开关 | 配置 | 作用 |
|---|---|---|
| 记多少 | `spring.cloud.openfeign.client.config.<key>.logger-level` | **NONE（默认）/ BASIC（方法+URL+状态码+耗时）/ HEADERS（BASIC+请求/响应头）/ FULL（头+body+metadata）**（官方 docs 原文口径；也可在 configuration 类里提供 `Logger.Level` Bean 设置） |
| 发不发 | `logging.level.<客户端接口 FQN>: DEBUG` | logger 名 = 接口全限定类名；**只响应 DEBUG** |
| （框架内部日志） | `logging.level.org.springframework.cloud.openfeign: DEBUG` | 看框架自身（如压缩拦截器等），lab 用法 |

xhs 生产默认开了 `logger-level: BASIC`（payment yml）——配合上面的 logging 开关才看得到。

---

## 6. xhs 的默认配置一览（payment 实例，节选）

```yaml
spring:
  cloud:
    openfeign:
      httpclient:
        hc5:                     # Apache HttpClient5
          enabled: true
          max-connections: 200
          max-connections-per-route: 50
      client:
        config:
          default:               # 全局默认
            request-interceptors:
              - com.myxhs.feign.config.FeignInternalCallInterceptor
            connect-timeout: 3000
            read-timeout: 5000
            logger-level: BASIC
```

（`request-interceptors` 也能在属性里声明——04 会讲它和 XML/Bean 声明的关系。）

---

## 7. Self-Test

1. `@FeignClient` 的 `name` 和 `contextId` 各干什么？配置键用哪个？
2. `configuration` 类里的 Bean 为什么可能"全局泄漏"？怎么防？
3. 日志要看到需要开哪两个开关？logger 名是什么？
4. 默认情况下，configuration Bean 与属性谁赢？跟 `default-to-properties` 开关什么关系？旧前缀为什么不能用？

---

## 8. 相关与衔接

- **04 拦截器与请求改造**：`request-interceptors` 的两种声明方式 + xhs 两个拦截器；
- **05 超时与客户端**：`connect/read-timeout` 与 HC5/OkHttp 的连接池；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[A-01 声明式调用与配置](../../openfeign-lab/docs/A-01-声明式调用与配置.md) / [D7 配置优先级](../../openfeign-lab/docs/D7-FeignClientProperties配置优先级.md)；
- 官方文档（本地）：`spring-cloud-openfeign/docs/modules/ROOT/pages/spring-cloud-openfeign.adoc`（logging 一节）。
