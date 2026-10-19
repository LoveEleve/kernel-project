# Spring Cloud 抽象与 SCA 装配系列

> 语料：**spring-cloud-commons 4.1.4**（commons/context/loadbalancer 三模块已核）+ **spring-cloud-alibaba** 源码
> xhs 版本矩阵实锤：**Spring Cloud 2023.0.3 + SCA 2023.0.1.2**（pom:45/46，配套 Boot 3.2.5）
> 定位：**粘合层/总装图**——Nacos/Seata/Sentinel 各系列已封卷，本系列讲"SC 抽象是什么、SCA 怎么挂进来"
> 计划：L5-12 补完 + 你点名的"深度关联"

## 一、总表（5 篇：概念 → 原理 → 使用 → 地图 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 Spring Cloud：三层模型](01-three-layers.md) | **概念** | commons SPI / 实现 / starter What；与 Boot 关系 | commons 模块结构 |
| 02 | [DiscoveryClient 与服务发现装配](02-discovery-client.md) | **原理** | 抽象接口；SCA nacos-discovery 怎么挂进来；负载均衡演进 | commons+loadbalancer 源码 |
| 03 | [spring-cloud-context 与配置抽象](03-cloud-context.md) | **原理** | bootstrap 遗产/refresh 事件/PropertySourceLocator | context 源码+Nacos 钩子 |
| 04 | [SCA 装配地图与版本矩阵](04-sca-map-versions.md) | **使用** | 每个 starter 对应哪个已封卷系列；BOM 对齐纪律 | xhs pom+SCA 源码 |
| 05 | [面试收口](05-interview-wrapup.md) | **面试** | 四篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
抽象线：02 → 03
装配线：04
收口：05
```

## 三、前置要求

- Spring Boot 系列（自动配置/条件注解是 SC 装配的底座）。
- Nacos 系列 02/05（DiscoveryClient 的 Nacos 实现侧 + import 语义）。

## 四、掌握分级

- **必须掌握**：三层模型（SPI/实现/starter）；DiscoveryClient 抽象与实现替换；loadbalancer 从 Ribbon 到 Spring Cloud LoadBalancer 的演进；bootstrap 遗产与替代；BOM 版本矩阵纪律。
- **理解思想**："抽象先行、实现插拔"——SC 不造轮子，造**插座**（和内核自动配置/各系列 SPI 哲学第五次呼应）。
- **知道即可**：OpenFeign/Gateway/Zookeeper 等其余 starter（各有专系列或非本线）；SC Bus/Stream（消息总线，非本线）。

## 五、边界

- Nacos/Seata/Sentinel 内部 → 各自已封卷系列（本系列只讲"接口怎么接"）。
- Netflix 时代（Ribbon/Hystrix）→ 只作演进口径。
- Dubbo 注册中心接入 → Dubbo 系列（已封卷）。
