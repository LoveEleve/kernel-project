# PolyConfig 配置统一抽象系列

> 语料：**polyconfig 源码**（11 非测试类——已核）+ 仓库 README（209 行，三层结构/工作流/注意事项实锤）
> 关键锚点（已核）：PropertySourceExtensionLoader:73（selectImports:85/resolveResources:111 抽象）/ NacosPropertySourceLoader:47（getConfig:78/addListener:94）/ ResourcePropertySourcesRefresher（refresh:93 锁外发事件/doRefresh:102 synchronized）/ EnvironmentConfigChangeListener / ConfigPropertiesChangedEvent
> 依赖：**polyconfig 是 polydata 的地基**（polydata pom 依赖 polyconfig-spring）——本系列是 poly 家族 ①
> 诚实边界：**无 lab、无拷打**（xhs 未直用；polydata 内部消费其事件——04 篇兑现联动）

## 一、总表（5 篇：概念 → 原理 → 使用 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 PolyConfig：三层结构](01-what-is-polyconfig.md) | **概念** | 声明式配置源/统一事件/后端适配 What；与 Spring/Spring Cloud 对照 | 仓库 README+源码结构 |
| 02 | [原理一：启动加载链](02-startup-loading.md) | **原理** | Loader 模板方法；@Import→getConfig→CompositePropertySource | Loader/Nacos 源码 |
| 03 | [原理二：刷新与统一事件](03-refresh-events.md) | **原理** | diff→替换→事件；锁内更新锁外发；环境适配双路 | Refresher/Listener 源码 |
| 04 | [使用：注解属性与三坑](04-usage-pitfalls.md) | **使用** | 属性表；重复事件/旧值 null/YAML 编码；polydata 联动 | README 注意事项 |
| 05 | [面试收口](05-interview-wrapup.md) | **面试** | 四篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03
使用：04
收口：05
```

## 三、前置要求

- Spring `Environment`/`PropertySource`/`@Import` 基础概念。
- Nacos 系列 01~04（配置中心本体已封卷——本系列是"客户端侧怎么用它"）。

## 四、掌握分级

- **必须掌握**：三层结构（元注解/Loader 模板/统一事件）；刷新链的 diff 语义与锁边界；三坑。
- **理解思想**：**"统一的是事件不是客户端"**——不替代各配置中心 SDK，只统一加载与感知。
- **知道即可**：Apollo/etcd Roadmap；ConfigData 对齐评估。

## 五、边界

- Nacos 服务端配置管理细节 → nacos 系列已封卷。
- `@RefreshScope` 代理机制 → spring-kernel 线，翻篇。
- polydata 消费事件后的数据源重建 → polydata 系列 ③。
