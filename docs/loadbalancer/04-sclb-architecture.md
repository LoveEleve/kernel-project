# LB-04 SCLB 总览与接入

> 用途：讲清"调用方怎么把请求交给 SCLB"——五种介入方式、两条路径（响应式/阻塞）、每服务子上下文、配置优先级
> 对应系列：**负载均衡（SCLB）专题 · 04**（使用篇第一篇）
> 口径：SCLB 4.1.4（代码在 `spring-cloud-commons` 仓库的 `spring-cloud-loadbalancer` 模块；与 Feign 的集成在 `spring-cloud-openfeign`）/ SCA 2023.0.1.2
> 素材：`loadbalancer-lab` D1/D3/D5/D7 · SCC-5 知识规划（@LoadBalanced 拦截链）· 网关课程 L16（子上下文装配）/ L46（响应式路径实测）
> 前置：01-03（形态/名单/算法）；后续：05（supplier 链与预设）→ 06/07/08（策略与兜底）→ 09（自定义）

---

## 0. 例子先行：请求是怎么"进入" SCLB 的

两个真实场景：

```text
① 网关：GET /lb/hello
   → 路由 demo-lb（uri: lb://demo-provider）匹配
   → ReactiveLoadBalancerClientFilter（order 10150）截住它 → 挑实例 → 直连

② 服务：Feign 调 @FeignClient(name = "demo-provider")
   → 调用 URL = http://demo-provider/hello（host 是"服务名"）
   → FeignBlockingLoadBalancerClient 截住它 → 挑实例 → 直连
```

**共同点：URL 里写的是"服务名"而不是 IP——这就是介入信号。**

> 写 `http://10.0.2.11:8081/hello`（真 IP）= 普通 HTTP 调用，没人拦；
> 写 `lb://demo-provider` 或 `http://demo-provider` = SCLB 接管。

SCLB 不是代理服务器，它是**装进调用方进程里的一套组件**：介入靠"约定（服务名）+ 拦截（filter/interceptor）"。

---

## 1. 掌握卡（3 条）

1. **介入靠约定**：`lb://服务名`（网关）/ `@LoadBalanced` 的 `http://服务名`（RestTemplate/RestClient）/ Feign `name=服务名` / WebClient（自动过滤）——写服务名就接管。
2. **两条路径**：**响应式**（网关 `ReactiveLoadBalancerClientFilter`、WebClient `ReactorLoadBalancerExchangeFilterFunction`）/ **阻塞**（Feign、@LoadBalanced RestTemplate/RestClient 走 `BlockingLoadBalancerClient` + 拦截器）——**同一内核，`Mono.block()` 桥接**。
3. **每服务一个子上下文**（`LoadBalancerClientFactory`）+ 配置优先级：**注解 `@LoadBalancerClient(configuration=…)` > `clients.<serviceId>.*` 属性 > 全局默认**——"我的配置为什么不生效"的标准答案。

---

## 2. SCLB 的组织结构（一张图）

```text
LoadBalancerClientFactory（NamedContextFactory：每个服务一个子上下文）
├── 契约：ReactorLoadBalancer / ReactorServiceInstanceLoadBalancer / Response<T>（挑一个）
├── 数据：ServiceInstanceListSupplier 装饰器链（名单从哪来、怎么加工 —— 05）
└── 接入层（两条路径）
    ├─ 响应式：ReactiveLoadBalancerClientFilter（网关）
    │          ReactorLoadBalancerExchangeFilterFunction（WebClient）
    └─ 阻塞：  BlockingLoadBalancerClient（Mono.block() 桥接）
               ← 接入方：LoadBalancerInterceptor（RestTemplate）
                         LoadBalancerRestClientBuilderBeanPostProcessor（RestClient）
                         FeignBlockingLoadBalancerClient（Feign）
```

> 和它平级、面向 Nacos 生态的还有 SCA 的 `NacosLoadBalancer`（06 讲它和内置预设的关系）。

---

## 3. 五种介入方式

| 方式 | 你写什么 | 谁接管 | 路径 |
|---|---|---|---|
| **网关路由** | `uri: lb://demo-provider` | `ReactiveLoadBalancerClientFilter`（order **10150**） | 响应式 |
| **@LoadBalanced RestTemplate** | `restTemplate.getForObject("http://demo-provider/hello", …)` | `LoadBalancerInterceptor` → `BlockingLoadBalancerClient` | 阻塞 |
| **Feign** | `@FeignClient(name = "demo-provider")` | `FeignBlockingLoadBalancerClient` | 阻塞 |
| **@LoadBalanced RestClient**（Boot 3.2+） | `restClient.get().uri("http://demo-provider/hello")` | `LoadBalancerRestClientBuilderBeanPostProcessor` | 阻塞 |
| **WebClient** | 用自动装配的 `WebClient.Builder` 直接调服务名 | `ReactorLoadBalancerExchangeFilterFunction` | 响应式 |

最小代码片段：

```yaml
# ① 网关（demo 里就有这条路由）
- id: demo-lb
  uri: lb://demo-provider
  predicates: [ Path=/lb/** ]
  filters: [ StripPrefix=1 ]
```

```java
// ② @LoadBalanced RestTemplate —— 注解是个 @Qualifier 标记；启动时被自动收集、装上拦截器
@Bean
@LoadBalanced
RestTemplate restTemplate() {
    return new RestTemplate();
}
// 调用：host 写服务名
String body = restTemplate.getForObject("http://demo-provider/hello", String.class);
```

```java
// ③ Feign
@FeignClient(name = "demo-provider")
public interface ProviderClient {
    @GetMapping("/hello")
    String hello();
}
```

```java
// ④ @LoadBalanced RestClient（Boot 3.2+）
@Bean
@LoadBalanced
RestClient.Builder restClientBuilder() {
    return RestClient.builder();
}
```

> `@LoadBalanced` 的收集方：`LoadBalancerAutoConfiguration`（commons）里注入 `@LoadBalanced List<RestTemplate>`，给每个装上 `LoadBalancerInterceptor`（时序问题由 `DeferringLoadBalancerInterceptor` 兜住）；RestClient 走另一条路——`LoadBalancerRestClientBuilderBeanPostProcessor` 检查 Builder bean 上有没有 `@LoadBalanced`，有才注入拦截器。

### 3.5 两跳完整链路：网关 → 服务 → 服务（RPC + LB 在哪交汇）

一句话关系：**RPC 解决"怎么调"（接口 → HTTP 请求），LB 解决"调谁"（服务名 → 实例 IP:端口）**——Feign 是 RPC 外壳 + LB 内核，两者在 `FeignBlockingLoadBalancerClient` 交汇。

```yaml
# 第一跳：外部 → demo-provider（网关 application.yml）
spring:
  cloud:
    gateway:
      routes:
        - id: demo-lb
          uri: lb://demo-provider        # 写服务名 = 交给 LB 选实例
          predicates:
            - Path=/lb/**
          filters:
            - StripPrefix=1
```

```java
// 第二跳：demo-provider → order-service（服务内完整代码）
@SpringBootApplication
@EnableFeignClients(basePackages = "com.example.provider.client")
public class ProviderApplication { }

@FeignClient(name = "order-service")          // 写服务名 = 交给 LB 选实例
public interface OrderClient {
    @GetMapping("/orders/{id}")
    String getOrder(@PathVariable("id") Long id);
}

@RestController
public class DemoController {
    private final OrderClient orderClient;    // 注入即用：RPC 的写法，LB 的内核
    @GetMapping("/lb/forward/{id}")
    public String forward(@PathVariable Long id) {
        return orderClient.getOrder(id);
    }
}
```

```text
外部请求 GET /lb/forward/42
  → 网关 SCG：ReactiveLoadBalancerClientFilter (order 10150) 见 lb://demo-provider
    → choose() 挑一台 → Netty 直连 8081                      ← 第一跳 LB（响应式）
  → demo-provider 8081：DemoController.forward(42)
    → orderClient.getOrder(42)：Feign 组装 HTTP（RPC）+ FeignBlockingLoadBalancerClient
    → choose() 挑一台 order-service → 直连                   ← 第二跳 LB（阻塞）
```

- 两跳各用各的 LB 子上下文（`LoadBalancerClientFactory` 按 serviceId 隔离：demo-provider 一套、order-service 一套）；
- Feign 的超时/重试/契约细节 → `openfeign-lab`（本系列只管"LB 选实例"那一环）。

---

## 4. 一次调用的旅程（两条路径各走一遍）

### 4.1 响应式（网关 `lb://`）

```text
ReactiveLoadBalancerClientFilter（order 10150）
  → scheme 是 lb？
  → LoadBalancerClientFactory.getInstance(serviceId).choose(request)
       ├─ supplier 链给出名单（05）
       └─ ReactorLoadBalancer 挑一个（03）
  → Response<ServiceInstance> → 用实例 host:port 改写 URL
  → 后续 filter 转发 → Netty 直连
```

实测锚点（L46）：

- `globalfilters` 里能看到它在 **10150** 的位置；
- 目标实例挂掉时日志是 `Connection refused: /192.168.13.128:8082`——**调用方直连的证据**（01 的判别口诀）。

### 4.2 阻塞（RestTemplate / Feign）

```text
restTemplate.getForObject("http://demo-provider/hello")
  → 【拦截】LoadBalancerInterceptor：从 URL host 提取 serviceId="demo-provider"
  → BlockingLoadBalancerClient.execute(serviceId, request)
       → LB.choose() 返回的是 Mono
       → ★ Mono.from(loadBalancer.choose(request)).block()   ← 桥接点（源码 :163）
  → Response<ServiceInstance> → reconstructURI(host:port)（LoadBalancerUriTools）
  → 真正发出 HTTP
```

两个要点：

- **@LoadBalanced 的本质**：注解本身只是 `@Qualifier` 标记，机制全在"收集 + 装拦截器"（见 §3 备注）；
- **Feign 的接入**：Feign **子上下文里的配置**（`DefaultFeignLoadBalancerConfiguration`，按 HTTP 客户端有 Http2/HttpClient5/OkHttp 变体）把默认 Client 换成了 `FeignBlockingLoadBalancerClient`（开启重试时是 `RetryableFeignBlockingLoadBalancerClient`）——所以 Feign 调用自动进入 SCLB；超时/重试/契约等细节在 `openfeign-lab`，本篇只关心"它从哪进入 SCLB"。

### 4.3 双路径对照

| | 响应式路径 | 阻塞路径 |
|---|---|---|
| 谁在用 | 网关、WebClient | Feign、@LoadBalanced RestTemplate/RestClient |
| 选实例的代码 | 事件循环里非阻塞 | `Mono.block()` 钉住调用线程 |
| 名单/策略/内核 | **完全共用**（同一套 supplier + LB） | 同左 |
| xhs 现状 | 网关 | 服务间调用（Feign） |

---

## 5. 每服务一个子上下文 + 配置优先级

`LoadBalancerClientFactory` 继承 `NamedContextFactory`（与 Feign 的 `FeignClientFactory` 同款设计）：

- **隔离**：`demo-provider` 和 `other-service` 各有一个子上下文——各自的 supplier 链、策略、`clients.<serviceId>.*` 配置互不干扰；
- **调试含义**：子上下文里的 bean（supplier/LB）**不出现在主 `actuator/beans` 视图**——要确认"我的配置生效没"，用 lab 的 `/lb/supplier-chain`（打印链结构）或看日志。

**三层配置优先级**（从高到低）：

```text
① 注解：@LoadBalancerClient(name = "demo-provider", configuration = DemoLoadBalancerConfiguration.class)
② 按服务属性：spring.cloud.loadbalancer.clients.demo-provider.configurations=weighted
③ 全局属性：spring.cloud.loadbalancer.configurations=default
```

```java
// ① 的用法（L16 就是靠它把自写 supplier 装进子上下文）
@LoadBalancerClient(name = "demo-provider", configuration = DemoLoadBalancerConfiguration.class)
```

> "配置不生效"先查三件事：是不是写对了层级（注解 > clients > 全局）；是不是放进了**子上下文**该管的东西（自写 Configuration 不能放在主扫描路径）；serviceId 拼写是否与 `spring.application.name` 一致。

---

## 6. 阻塞 vs 响应式：`Mono.block()` 的代价（为什么网关必须响应式）

- 阻塞路径的核心是 `Mono.block()`：**把当前线程钉住**等选实例完成——在 Servlet/线程池模型（Feign/RestTemplate）里是常态；
- 但**在 Reactor Netty 的事件循环线程里调用 block 会被直接拒绝**（Reactor 的 guard 报 "block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-nio-*"）——所以网关只能走响应式 filter 版本；
- 这就是同一条 SCLB 内核要配两套"外壳"的原因：**不是功能不同，是线程语义不同**。

桥接代码实锤（`BlockingLoadBalancerClient.java:158-163`）：

```java
public <T> ServiceInstance choose(String serviceId, Request<T> request) {
    // ...
    Response<ServiceInstance> loadBalancerResponse = Mono.from(loadBalancer.choose(request)).block();
    // ...
}
```

完整阻塞链：`execute:68` → `choose:74` 拿实例 → `execute:80` 发请求——Feign、`@LoadBalanced` 的 RestTemplate/RestClient 全走这条，内核还是响应式的 `choose`。

---

## 7. 边界与翻篇

- Feign 的用法/超时/重试/契约细节 → **`openfeign-lab`**（本篇只讲"接入 SCLB 的那一环"）；
- supplier 链怎么组、7 预设逐个 → **05**；权重/灰度 → **06**；名单会变 → **07**；粘性/重试 → **08**；自定义 → **09**；
- 网关侧 `lb://` 的全链路实测（摘除/35s 窗口）→ [`L46`](../gateway/L46-sclb.md)。

---

## 8. Self-Test

1. SCLB 是怎么"知道你调的是哪个服务"的？（介入信号是什么？为什么不写 IP？）
2. 五种介入方式分别属于哪条路径？各自的"接管者"是谁？
3. 阻塞路径里 `Mono.block()` 意味着什么？为什么网关不能走这条路？
4. 配置优先级三层的顺序？"配置不生效"先查什么？

---

## 9. 相关与衔接

- **05 名单管线**：supplier 装饰器链与 7 预设（子上下文里装的"数据流"）；
- **06 权重与灰度** / **07 名单会变** / **08 重试·粘性·一致性哈希** / **09 自定义 LB**；
- [`loadbalancer-lab`](../../loadbalancer-lab/HANDOVER.md)：[D1 接口体系](../../loadbalancer-lab/docs/D1-ReactorLoadBalancer-响应式负载均衡接口.md) / [D3 自动装配](../../loadbalancer-lab/docs/D3-LoadBalancerClientConfiguration自动装配.md) / [D5 阻塞客户端](../../loadbalancer-lab/docs/D5-BlockingLoadBalancerClient-阻塞客户端.md) / [D7 拦截器家族](../../loadbalancer-lab/docs/D7-LoadBalancerInterceptor-RestTemplate集成层.md)；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)（Feign 侧完整使用）；
- 网关课程：[L16](../gateway/L16-custom-loadbalancer.md)（子上下文装配）/ [L46](../gateway/L46-sclb.md)（响应式路径实测）。
