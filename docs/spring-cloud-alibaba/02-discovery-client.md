---
title: "02-DiscoveryClient与服务发现装配"
series: "Spring Cloud 抽象与 SCA 装配系列"
previous: "01-three-layers.md"
next: "03-spring-cloud-context.md（待写）"
source_refs:
  - "commons-4.1.4 已核：DiscoveryClient:32（getInstances:50）/ServiceRegistry.java/Registration.java/BlockingLoadBalancerClient:59"
  - "SCA 已核：nacos-discovery starter 的 NacosDiscoveryAutoConfiguration + NacosServiceRegistry"
---

# 02-DiscoveryClient与服务发现装配

## §0 它干了什么（例子先行）

服务发现其实是**一读一写两个抽象**：

```
读（发现）：DiscoveryClient.getInstances("order-service") → 地址列表   ← 调用方用
写（注册）：ServiceRegistry.register(registration)      ← 自己上报自己
            Registration = 我是谁（服务名/ip/port/metadata）
```

SCA 把 Nacos 挂上来就是**各写一个实现 + 一个自动配置装配**：

```
NacosDiscoveryAutoConfiguration（SCA starter 内，源码已核）
  → 注册 NacosServiceRegistry implements ServiceRegistry（写侧——注册心跳走 nacos-client）
  → 注册 DiscoveryComposite（内含 NacosDiscoveryClient 实现读侧）
  → 业务代码里 discoveryClient.getInstances() 从此返回 Nacos 的地址
```

- **它干了什么**：**一读一写两个接口 + 一组实现类**——Nacos 系列讲了 nacos-client 怎么心跳，本篇讲**接口怎么被顶上**。
- **本篇回答**：两个抽象的分工？LoadBalancer 怎么接在 getInstances 后面？Ribbon 演进怎么回事？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **DiscoveryClient** | 读抽象（:32）——`getInstances(serviceId)`（:50）/`getServices()` |
| **ServiceRegistry + Registration** | 写抽象——注册/注销/心跳上报的统一入口 |
| **CompositeDiscoveryClient** | 多实现聚合（有多个 DiscoveryClient 时按 Ordered 依次问——commons 自带 Ordered 就为此） |
| **LoadBalancerClient** | 拿到地址列表后的**选一个**——发现与均衡是两个动作 |
| **BlockingLoadBalancerClient** | SC LoadBalancer 的阻塞实现（:59）——RestTemplate/Feign 走它 |

## §2 负载均衡演进（面试必问线）

```
Netflix 时代：Ribbon（独立项目，Netflix 停更）
  → SC 2020.0 起：Spring Cloud LoadBalancer 接管（spring-cloud-loadbalancer 模块——01 语料已核）
      BlockingLoadBalancerClient（阻塞栈）/ ReactorLoadBalancer（响应式栈）
      + ServiceInstanceListSupplier（地址从哪来——默认从 DiscoveryClient 拉+缓存）
```

**为什么弃 Ribbon**：Netflix 系全线停更——SC 把**地址来源（Supplier）+ 均衡算法（Rule/策略）+ 执行（Client）**重构成自家三件套，且全部可替换（自定义=配一个 @Bean 策略——Boot 退让语义第 N 次）。

**xhs 呼应**：OpenFeign 系列的 LB、Tracing 的 Feign 拦截器——都挂在这条链上（Feign → LoadBalancerClient → DiscoveryClient）。

## §3 工程使用

```yaml
# 发现侧（xhs yml 实景——Nacos 系列 07 同款）
spring:
  cloud:
    nacos:
      discovery: { server-addr: ..., namespace: my-xhs, metadata: { zone: ... } }
```

| 工程点 | 说明 |
|---|---|
| `spring.cloud.discovery.enabled=false` | 总开关——排障时先问它 |
| 选一个自己认的 DiscoveryClient | 多实现时 `@Primary` 或 Ordered（Composite 语义） |
| 自定义负载均衡 | 注入 `ReactorServiceInstanceLoadBalancer`/策略 @Bean——不改业务代码 |
| 本地直连不走发现 | Feign/RestTemplate 的 url 直连模式（OpenFeign 系列已讲） |

## §4 边界（一句话翻篇）

- NacosDiscoveryClient 内部（与 nacos-client 的桥）→ Nacos 系列已封卷，本系列不重复。
- 缓存/健康剔除细节（SC 侧缓存失效）→ 用时查 loadbalancer cache 包。
- Consul/ZK 的 DiscoveryClient 实现 → 非本线。

## §5 Self-Test（3 题）

- Q1：一读一写是哪两个接口？Composite 按什么排序？
- Q2：发现和负载均衡为什么是两步？BlockingLoadBalancerClient 在链路哪一环？
- Q3：为什么弃 Ribbon？新三件套指哪三个？

## §6 衔接

- 发现线收口；下篇 03 spring-cloud-context：配置侧的抽象——bootstrap 遗产、refresh 事件与 PropertySourceLocator（Nacos 钩子第二次兑现）。
