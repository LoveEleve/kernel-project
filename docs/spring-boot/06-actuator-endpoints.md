---
title: "06-Actuator端点体系"
series: "Spring Boot 深化系列"
previous: "05-fat-jar-classloader.md"
next: "07-ConfigData加载链.md（待写）"
source_refs:
  - "boot-3.2.5：actuate/endpoint/annotation/@Endpoint:56（已核）；EndpointAutoConfiguration:47；HealthEndpointGroup（已核）"
---

# 06-Actuator端点体系

## §0 它干了什么（例子先行）

`/actuator/health`、`/actuator/env`、tracing 系列用过的 `/actuator/prometheus`——背后是同一套模型：

```
@Endpoint(id = "orders")            ← 声明一个端点（id=URL 段）
public class OrdersEndpoint {
    @ReadOperation Map<String,Object> info() { ... }     ← GET /actuator/orders
    @WriteOperation ...                                  ← POST（操作类端点）
    @DeleteOperation ...                                 ← DELETE
}
```

- **它干了什么**：**把"运维可见面"做成一等公民**——注解声明端点，Web 层自动挂 `/actuator/{id}`。
- **本篇回答**：端点怎么注册、怎么暴露（全开/全关/白名单）？健康组怎么拼？自定义端点怎么写？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **@Endpoint(id)** | 端点声明（:56）——id 即路径段 |
| **@ReadOperation/@WriteOperation/@DeleteOperation** | 三种操作→GET/POST/DELETE |
| **exposure** | 暴露控制：`management.endpoints.web.exposure.include/exclude`（**默认只暴露 health**——其余要显式开） |
| **HealthEndpointGroup** | 健康组：按组裁剪健康 contributor 集合（`management.endpoint.health.group.*`） |
| **EndpointAutoConfiguration** | 端点装配入口（:47）——发现 @Endpoint Bean 并接 Web |

## §2 工程使用

**暴露配置三板斧**：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus     # 显式白名单（生产推荐）
  endpoint:
    health:
      show-details: always                  # health 默认不给细节——按环境开
      group:
        readiness:
          include: readinessState,db        # 就绪组：只看真影响接客的项
```

**自定义端点样例**：

```java
@Endpoint(id = "mq-backlog")
@Component
public class MqBacklogEndpoint {
    @ReadOperation
    public Map<String, Long> backlog() { return mqAdmin.depths(); }  // GET /actuator/mq-backlog
}
```

**三条生产纪律**：① **默认不暴露不是安全依赖**——公网仍要网关挡 `/actuator`；② health 与探针分离（readiness 组 vs liveness 组——02 系列 Ready 语义的运维落点）；③ 端点是**只读优先**——带 Write 的端点（如 shutDown）能不开就不开。

## §3 掌握卡（2 条）

1. **注册-暴露两段闸**：@Endpoint Bean 注册≠Web 可见——**exposure 白名单是第二道闸**（装了不给看，是设计不是 bug——"组件在跑≠有数据"的 Boot 版镜像）。
2. **health 是唯一默认暴露的**——所以"怎么加个指标端点"的答案永远是：加 @Endpoint Bean + 把 id 写进 exposure.include。

## §4 边界（一句话翻篇）

- Endpoint 发现机制（Discoverer/过滤器链）→ endpoint 包用时查。
- prometheus 端点的注册链 → micrometer 05 已讲 scrape——actuator 只是挂载点。
- JMX 暴露面（management.endpoints.jmx）→ 知道对称存在即可。

## §5 Self-Test（3 题）

- Q1：注册了 @Endpoint 为什么 /actuator 看不到？（exposure 第二道闸）
- Q2：三种 Operation 注解各映射什么 HTTP 方法？
- Q3：readiness 组和 liveness 组各该放什么？（接客条件 vs 进程活着）

## §6 衔接

- 端点体系收口；下篇 07 ConfigData 加载链：`spring.config.import` 背后的 resolver/loader SPI——Nacos 05 语义钩子的机制兑现。
