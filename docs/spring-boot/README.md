# Spring Boot 深化系列

> 语料：**spring-boot-3.2.5** 源码（spring-boot 主工程 + autoconfigure 已在内核系列 04 用过）
> 定位：补内核系列未覆盖的 **Boot 运行机制半区**——run 全程/Binder 绑定/logging 时机/测试切片
> 关系：与 `spring-kernel`（内核+自动配置）互补；SCA 粘合另立 `spring-cloud-alibaba` 系列

## 一、总表（8 篇：概念 → 原理 → 使用 → 面试；01-04 运行机制批 + 05-08 补强批）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 SpringApplication：run 的角色](01-springapplication.md) | **概念** | What：run 脚本骨架/与内核系列的分工 | SpringApplication 源码 |
| 02 | [原理：run() 全流程分步](02-run-lifecycle.md) | **原理** | 从 main 到 Ready 的固定剧本 | run:317 体内锚点 |
| 03 | [原理：ConfigurationProperties 绑定](03-configuration-properties.md) | **原理** | @ConfigurationProperties 怎么绑上 yml；宽松绑定；校验 | binder 源码+工程 |
| 04 | [使用与收口：logging·切片·终检](04-usage-wrapup.md) | **使用+面试** | logging 初始化时机；@SpringBootTest 切片；坑+终检 | 综合 |
| 05 | [Fat jar 与类加载](05-fat-jar-classloader.md) | **原理** | java -jar 怎么跑的；三层类加载；分层镜像 | loader 源码 |
| 06 | [Actuator 端点体系](06-actuator-endpoints.md) | **使用** | 端点注册/暴露/健康组/自定义端点 | actuator 源码 |
| 07 | [ConfigData 加载链](07-configdata-chain.md) | **原理** | spring.config.import 背后的 resolver/loader SPI | boot configdata 源码 |
| 08 | [生产特性与总收口](08-production-wrapup.md) | **使用+面试** | 优雅停机/lazy-init；Boot 全域大终检 | 综合 |

## 二、学习路径

```text
运行机制批：01 → 02 → 03 → 04
补强批：05 → 06 → 07 → 08
```

## 三、前置要求

- Spring 内核系列 04（自动配置）/07（Environment/事件）——本系列不重复。

## 四、掌握分级

- **必须掌握**：run 全流程步序；@ConfigurationProperties 绑定机制与宽松规则；logging 为何先于 context。
- **理解思想**："脚本化启动"——Boot 把 SpringApplication 写成可读剧本。
- **知道即可**：AOT/native；devtools；FailureAnalyzer 家族。

## 五、边界

- 自动配置细节 → 内核 04。
- Actuator 端点内部 → 各使用系列已带（xxl-job/tracing）。
- Spring Cloud 抽象 → `spring-cloud-alibaba` 系列（后续）。
