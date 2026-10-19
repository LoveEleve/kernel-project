---
title: "01-什么是SpringCloud：三层模型"
series: "Spring Cloud 抽象与 SCA 装配系列"
previous: "README.md"
next: "02-DiscoveryClient.md（待写）"
source_refs:
  - "spring-cloud-commons-4.1.4 模块结构已核：spring-cloud-commons（DiscoveryClient）/spring-cloud-context/spring-cloud-loadbalancer（ReactorLoadBalancer/ServiceInstanceListSupplier）/spring-cloud-starter-bootstrap"
  - "xhs pom 实锤：spring.cloud.version=2023.0.3 + spring.cloud.alibaba.version=2023.0.1.2"
---

# 01-什么是SpringCloud：三层模型

## §0 它干了什么（例子先行）

同一个业务代码，注册中心从 Eureka 换到 Nacos——**业务零改**：

```java
List<ServiceInstance> xs = discoveryClient.getInstances("order-service");
// 2019 年：这段话对着 EurekaDiscoveryClient 说
// 2026 年：同一段话对着 NacosDiscoveryClient 说——换的是 jar，不是代码
```

Spring Cloud 的全部秘诀就在这：

```
第一层 抽象（spring-cloud-commons）：DiscoveryClient/LoadBalancer/PropertySourceLocator… 接口
第二层 实现（spring-cloud-alibaba 等）：NacosDiscoveryClient implements DiscoveryClient
第三层 starter（spring-cloud-starter-alibaba-nacos-discovery）：依赖聚合，把实现塞进 classpath
```

- **它干了什么**：**造插座不造电器**——SC 定义"分布式原语"的接口，生态实现；starter 负责把插头插上。
- **本篇回答**：三层各是什么？它和 Boot 是什么关系？为什么说我们已封卷的 Nacos/Seata/Sentinel 都是"第二层公民"？

## §1 概念铺垫（What：三层 + 两个坐标）

| 层 | 一句话 | 源码位置 |
|---|---|---|
| **抽象层** | 分布式原语的 Java 接口（服务发现/负载均衡/配置定位/熔断模板…） | `spring-cloud-commons-4.1.4`（已核） |
| **实现层** | 各厂商/各协议的实现（Nacos/Consul/ZK…） | `spring-cloud-alibaba`（SCA 实现 Nacos 系） |
| **starter 层** | 依赖聚合 + 自动配置传递 | `spring-cloud-starter-*`（xhs pom 在用） |

**两个坐标**（版本矩阵——工程必修）：

- **坐标一：BOM 对**：`spring-cloud-dependencies 2023.0.3` + `spring-cloud-alibaba-dependencies 2023.0.1.2`（xhs pom:45/46 实锤）——**两者与 Boot 3.2.x 有官方配套表**，错配=一堆条件装配静默失效。
- **坐标二：SC 不是 Boot 的插件**：SC 构建在 Boot 之上（spring-cloud-context 挂 Boot 的事件/Environment），**Boot 升级先看 SC 配套**——依赖升级顺序：Boot → SC → SCA。

## §2 与已封卷系列的关系（总装图一角）

| 抽象（第一层） | 实现（第二层） | 我们封卷的系列 |
|---|---|---|
| DiscoveryClient | NacosDiscoveryClient | **Nacos 系列**（实现侧） |
| LoadBalancer（SC LoadBalancer） | 负载均衡策略 | **LoadBalancer 系列**（docs/loadbalancer 已有） |
| （配置侧）PropertySourceLocator（bootstrap 时代从配置中心拉配置的定位接口）→ ConfigData | Nacos 配置 | Nacos 系列 05/07 语义 |
| 熔断的容错模板 | Sentinel 适配 | Sentinel 系列（04 提过 Feign 侧） |
| Feign 抽象 | OpenFeign 实现 | OpenFeign 系列 |

**一句话**：各专系列讲"电器怎么工作"，**本系列讲"插座为什么这么设计、插头怎么插"**。

## §3 掌握卡（2 条）

1. **抽象先行的价值**：接口在 commons——换实现不换业务（§0 的例子）；**测试可以 mock 第一层**（单元测试拿假 DiscoveryClient 打桩——造个假实现顶替）。
2. **starter 是装配传递链**：`starter-alibaba-nacos-discovery` = commons 接口 + alibaba 实现 + 自动配置——**加依赖=完成插接**（Boot 自动配置语言，内核 04 第五次呼应）。

## §4 边界（一句话翻篇）

- DiscoveryClient 接口方法全表 → 02。
- spring-cloud-context 的事件/refresh → 03。
- 版本矩阵配套表全文 → 04（给出查法）。

## §5 Self-Test（3 题）

- Q1：三层各举一个例子；换实现不改代码靠哪层？
- Q2：xhs 的 BOM 对是什么？错配的症状是什么？
- Q3：Boot→SC→SCA 的升级顺序为什么是这个？

## §6 衔接

- 三层模型收口；下篇 02 DiscoveryClient：抽象接口的形状 + SCA 怎么把 Nacos 挂进来 + 负载均衡演进。
