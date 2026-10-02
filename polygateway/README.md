# PolyGateway

> Endpoint-level routing for Spring Cloud Gateway (WebFlux): provider applications publish their web endpoints, the gateway discovers and routes them precisely.
> 面向 Spring Cloud Gateway（WebFlux）的端点级路由：业务服务发布自己的 HTTP 端点，网关自动发现并精确路由。

PolyGateway 解决一个具体问题：**网关如何不写任何路由配置，就把请求精确转发到下游服务的具体端点上（还能按条件排除内部接口）。**

一个例子：订单服务的两个端点 `GET /orders/{id}`（对外）与 `POST /internal/reconcile`（内部对账）。

```java
// 订单服务：只需要引入 starter，启动时自动把端点写入注册中心 metadata
//   polygateway.web-endpoints=[{"patterns":["/orders/{id}"],"methods":["GET"],...}, ...]
```

网关侧同样只需要引入 starter，路由自动生成：

```
GET  /order-service/api/orders/42          ->  POST /api/orders/42  到实例（StripPrefix 剥掉服务名前缀）
POST /order-service/api/orders (json)      ->  200（Content-Type 匹配 Consumes）
POST /order-service/api/orders (text)      ->  404（无匹配路由）
POST /order-service/api/internal/reconcile ->  404（被 excludes 排除，根本没有生成路由）
```

## 特性

| 能力 | 说明 |
|------|------|
| 端点自动发现 | Provider 启动（注册前事件）扫描 `RequestMappingHandlerMapping`，六维端点（path/method/param/header/consumes/produces）序列化进实例 metadata，跳过 Spring Boot 基础设施接口 |
| 端点级路由生成 | 每个端点一条路由：`/{serviceId}{contextPath}{endpoint}` + `Method/Header/Query/Consumes/Produces` 谓词 + `StripPrefix=1`；越特异的路径优先级越高；`excludes` 选中的端点不生成路由 |
| 全实例并集 | 同一服务的全部实例元数据取并集（金丝雀实例独有的端点也可路由） |
| 官方负载均衡 | 路由 URI 固定 `lb://serviceId`，实例选择走官方响应式 LB，事件循环永不阻塞 |
| 官方谓词复用 | `Path`/`Method`/`Header`/`Query` 全部走 Spring Cloud Gateway 内置谓词；另补 `Consumes`/`Produces` 两个官方缺失的媒体类型谓词 |
| context-path 正确转发 | 路由路径保留 context-path，剥除部分仅为服务名前缀：`/order-service/api/orders/42` → 远端 `/api/orders/42` |
| 刷新及时 | 每次注册中心心跳都强制重算路由（心跳值是否变化不重要），实例元数据新增端点即刻可路由 |
| 双端自动装配 | Provider 侧（Servlet MVC）与 Gateway 侧各自条件装配，开关均可独立关闭 |

## 模块

| 模块 | 职责 |
|------|------|
| **polygateway-core** | 端点六维模型、JSON 元数据协议、端点排除规则 |
| **polygateway-provider** | 端点收集器（确定性排序 + 稳定 id）与注册前元数据发布 |
| **polygateway-gateway** | 端点级路由生成器（全实例并集 + excludes + 特异性排序）、`Consumes`/`Produces` 谓词、心跳刷新监听器 |
| **polygateway-spring-boot** | 双端自动装配与配置属性 |

数据流：

```
Provider 应用（MVC）
  │  启动注册前：扫描 HandlerMapping → JSON → 实例 metadata（polygateway.web-endpoints）
  ▼
注册中心（Nacos / Eureka / 任意 DiscoveryClient）
  │  网关：读全部实例元数据 → 并集 → 生成端点路由（lb:// + 六维谓词 + StripPrefix）
  ▼
Spring Cloud Gateway : GET /order-service/api/orders/42 → 官方 LB 选实例 → http://实例:8080/api/orders/42
```

## 快速开始

### 服务提供方（Spring MVC 应用）

```xml
<dependency>
    <groupId>io.github.loveeleve</groupId>
    <artifactId>polygateway-spring-boot</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- 另需：注册中心客户端（如 spring-cloud-starter-alibaba-nacos-discovery） -->
```

```properties
polygateway.provider.enabled=true
```

启动后，该应用的端点会自动写入注册 metadata，无需任何注解。

### 网关（Spring Cloud Gateway WebFlux 应用）

```xml
<dependency>
    <groupId>io.github.loveeleve</groupId>
    <artifactId>polygateway-spring-boot</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- 端点路由使用 lb://，必须显式引入（gateway starter 仅作 optional 声明）： -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

```properties
polygateway.gateway.enabled=true
# 内部接口不生路由
polygateway.gateway.excludes[0].patterns=/internal/**
polygateway.gateway.excludes[0].methods=POST
```

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `polygateway.provider.enabled` | `true` | 是否收集并发布端点（仅 Servlet MVC 应用生效） |
| `polygateway.gateway.enabled` | `true` | 是否生成端点路由 |
| `polygateway.gateway.excludes[n].patterns` | - | 排除规则（Ant 风格，必填，不填则该规则永不命中） |
| `polygateway.gateway.excludes[n].methods` | - | 排除的方法（交集即命中；端点未声明方法视为任意方法） |
| `polygateway.gateway.excludes[n].params` / `.headers` | - | 须全部被端点声明才命中 |
| `polygateway.gateway.excludes[n].consumes` / `.produces` | - | 与端点取交集即命中 |

## 设计取舍与限制

- **一个生效的 DiscoveryClient**：应用中应当只有一个生效的注册中心客户端；多个客户端并存时负载均衡拿到的实例列表不可预期（测试中曾因此拿到空列表）。生产（Nacos/Eureka 单一注册中心）天然满足。
- **端点级排除是"整端点"粒度**：一条规则命中端点即不生成该端点的任何路由（与"请求级精确排除"语义略有不同，已在文档注明）。
- **字面值转义**：端点条件值转为官方谓词参数时做正则转义（精确匹配，如 `1.0` 不会匹配 `1x0`）；`!` 与 `name!=value` 类条件官方谓词无法表达，会被跳过并打日志。
- **刷新依赖心跳**：路由在每次注册中心心跳后重算；无心跳发布的注册中心实现需自行发布 `HeartbeatEvent`（或 `RefreshRoutesEvent`）。
- **Provider 侧目前只支持 Spring MVC**：WebFlux 业务应用的端点收集在 Roadmap。
- **Kafka/配置中心**：本框架不涉及，与其它 Poly 项目（PolyConfig/PolyCache）正交。

## 测试

29 个测试，全部使用进程内依赖（Mock/Stub 注册中心、嵌入式 HTTP 下游、嵌入式 `SimpleDiscoveryClient` 风格桩）：

- `polygateway-core`（13）：端点模型、JSON 协议往返、排除规则匹配矩阵
- `polygateway-provider`（4）：MVC 端点收集（跳过 Boot 基础设施接口、确定性 id）、注册前元数据发布
- `polygateway-gateway`（8）：端点路由生成（排除/并集/上下文组合/排序）、`Consumes`/`Produces` 谓词、心跳刷新
- `polygateway-spring-boot`（条件测试 + **全链路 E2E**：桩注册中心 + 真实下游 + `WebTestClient` 验证六维匹配、剥离前缀、context-path、excludes、404 语义与心跳刷新）

```bash
./mvnw test
```

## License

[Apache License, Version 2.0](LICENSE)
