---
title: "03-spring-cloud-context与配置抽象"
series: "Spring Cloud 抽象与 SCA 装配系列"
previous: "02-discovery-client.md"
next: "04-SCA装配地图.md（待写）"
source_refs:
  - "commons-4.1.4 context 模块已核：bootstrap/config/PropertySourceLocator、refresh/{ContextRefresher,ConfigDataContextRefresher,LegacyContextRefresher}、scope/refresh/RefreshScope"
cross_refs:
  - "Nacos 05/10（import 语义/Bootstrap 废弃——本篇讲 SC 侧机制）"
---

# 03-spring-cloud-context与配置抽象

## §0 它干了什么（例子先行）

配置中心的"拉取"在 SC 里也有一个一等接口——但它的时代正在交接：

```
旧时代（bootstrap.yml）：
  spring-cloud-starter-bootstrap 引入 bootstrap 上下文
    → PropertySourceLocator.locate()：从配置中心拉配置 → 塞进 Environment
    → 再启动主上下文（两步启动——Nacos 10 讲过）

新时代（Boot 2.6+ / 我们的现状）：
  spring.config.import（Boot ConfigData——Boot 07 机制层已讲）
    → Nacos 提供 ConfigData Loader 替代 Locator
    → 一步启动；starter-bootstrap 仍在（01 语料已核）但只服务遗留项目
```

- **它干了什么**：spring-cloud-context=SC 给 Boot 补的"分布式运行时胶水"——**配置抽象的旧接口（Locator）+ 刷新机制（ContextRefresher/RefreshScope）**。
- **本篇回答**：Locator 和 ConfigData 什么关系？refresh 事件链怎么走？RefreshScope 和 @ConfigurationProperties 刷新各管什么？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **spring-cloud-context** | SC 在 Boot 之上的运行时模块（refresh/encrypt/named…——源码目录已核） |
| **PropertySourceLocator** | bootstrap 时代"从配置中心 locate 配置"的抽象（Nacos 实现过它） |
| **ContextRefresher** | 刷新编排器：重拉配置→更新 Environment→**发布 RefreshEvent** |
| **RefreshScope** | `@RefreshScope` 的载体——销毁重建型刷新（Nacos 05 §5 已讲使用侧） |
| **LegacyContextRefresher** | bootstrap 遗产路径的刷新器（源码在册——"legacy"就是历史的墓志铭） |

## §2 两代机制对照（Nacos 钩子兑现）

| 维度 | bootstrap 时代 | ConfigData 时代 |
|---|---|---|
| 入口 | starter-bootstrap + bootstrap.yml | `spring.config.import` |
| 拉取接口 | **PropertySourceLocator** | ConfigData Loader（Boot 07） |
| 刷新事件 | RefreshEvent → ContextRefresher | **同款**（刷新机制没换代，换的是拉取层） |
| @RefreshScope | 销毁重建 | 仍有效（两种模式共用） |
| 现状（xhs） | 已迁完（零 bootstrap.yml——Nacos 系列 07 实锤） | **现行** |

**关键认知**：**废弃的只是"两步启动的拉取方式"，刷新语义（RefreshEvent/RefreshScope）原封不动**——所以 Nacos 05 的刷新链（推送→listener→RefreshEvent→Environment+RefreshScope）在 ConfigData 时代照旧成立。

## §3 工程使用

```java
// 刷新侧两件套的分工（Nacos 05 §5 回收，SC 机制背书）
@RefreshScope                      // 销毁重建型：适合 @Value 注入的简单配置
@Component
public class ThresholdConfig { @Value("${rate:100}") int rate; }

@ConfigurationProperties           // 原地更新型：适合批量绑定（03 系列 Boot）
public class PoolProps { ... }     // 刷新时绑定对象字段被重绑
```

**迁移遗留项**：还在用 bootstrap.yml 的项目——`spring-cloud-starter-bootstrap` 依赖+import 语义双轨可共存过渡，**终点是删 bootstrap.yml**（Nacos 系列 10 的 xhs 路径就是终点样例）。

## §4 边界（一句话翻篇）

- ContextRefresher 全流程行级 → refresh 包源码用时查。
- Encrypt/decrypt（配置密文）→ context/encrypt 包，知道存在。
- ContextRefresher 与 Boot 07 ConfigDataEnvironment 的调用关系 → 用时深查（两代并存期）。

## §5 Self-Test（3 题）

- Q1：Locator 和 ConfigData Loader 是什么关系？哪代废弃？
- Q2：刷新事件链在两代机制里变没变？
- Q3：@RefreshScope 和 @ConfigurationProperties 刷新的差别？（销毁重建 vs 原地重绑）

## §6 衔接

- 配置抽象收口；下篇 04 SCA 装配地图：每个 starter 挂哪个抽象、对应哪个已封卷系列——总装图主表。
