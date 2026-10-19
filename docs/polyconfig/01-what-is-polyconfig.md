---
title: "01-什么是 PolyConfig：三层结构"
series: "PolyConfig 配置统一抽象系列"
previous: "README.md"
next: "02-startup-loading.md（待写）"
source_refs:
  - "仓库 README（209 行三层结构/对照表/工作原理）+ 源码已核：PropertySourceExtensionLoader:73 / NacosPropertySourceLoader:47 / ResourcePropertySourcesRefresher / EnvironmentConfigChangeListener"
cross_refs:
  - "nacos 01-04（配置中心本体已封卷——本系列是客户端使用侧）"
---

# 01-什么是 PolyConfig：三层结构

## §0 它干了什么（例子先行）

```java
// ① 声明：加一个注解，配置从 Nacos 进 Spring Environment
@NacosPropertySource(value = "application.properties", group = "DEFAULT_GROUP",
                     serverAddress = "127.0.0.1:8848")
@Configuration public class NacosConfig { }

// ② 感知：Nacos 上改了配置 → 自动刷 Environment → 一种事件通知业务
@Component class MyListener implements ApplicationListener<ConfigPropertiesChangedEvent> {
    public void onApplicationEvent(ConfigPropertiesChangedEvent e) {
        if (e.isChanged("my.datasource.url")) { /* 重建连接池 ... */ }
    }
}
```

- **它干了干什么**：**把"配置从哪来、变了怎么知道"统一成一套注解+一种事件**——业务不关心后面是 Nacos、Apollo 还是 Spring Cloud 原生刷新。
- **本篇回答**：三层结构各管什么？和 Spring 自带的两套方案差在哪？

## §1 概念铺垫（What：三层结构——README 实锤）

| 层 | 构件 | 管什么 |
|---|---|---|
| **① 元注解（声明）** | `@PropertySourceExtension` → `@NacosPropertySource`（@AliasFor 映射通用属性） | 标在 @Configuration 上**声明外部配置源** |
| **② Loader（启动时）** | `PropertySourceExtensionLoader<A>`（模板:73）→ `NacosPropertySourceLoader`（:47） | 启动拉配置进 Environment；**后端差异只在 2 个定制点里** |
| **③ 事件（运行时）** | `ConfigPropertiesChangedEvent`（属性名+新旧值+来源） | 刷新后统一广播；**双路来源**：声明式加载 vs 环境适配（source 标签区分） |

**一句话**：**注解管"来"、Loader 管"载"、事件管"变"**——三件事都跟具体配置中心解耦。

## §2 为什么需要它：已有两套为什么还不够（推导 + 对照表）

**朴素答案**：Spring 有 @PropertySource、Spring Cloud 有 EnvironmentChangeEvent，还要个封装？

**对照**（README 原表整理）：

| | Spring @PropertySource | Spring Cloud 原生刷新 | **PolyConfig** |
|--|--|--|--|
| 配置来源 | 仅 classpath 文件 | 配置中心（各客户端自管） | 配置中心+文件（统一抽象） |
| 热更新 | **不支持** | @RefreshScope / EnvironmentChangeEvent | 自动刷新+统一事件 |
| 变更感知 | 无 | **仅属性名**（EnvironmentChangeEvent） | 属性名+**新旧值**+来源 |
| 后端扩展 | - | 各客户端独立 | **Loader 模板，新后端只写 2 个方法** |

**推导**：三个缺口——文件配置无热更、原生事件**没有旧值**（业务要做 diff 得自己再查）、换后端要改监听代码。**Therefore**：统一的不是"配置中心"，是**"加载动作 + 变更事件"这两个面**——所以 README 说"不替代各客户端 SDK"。

**Cost**：多一层抽象（事件可能重复发——04 篇坑 1）。

## §3 掌握卡（2 条）

1. **三层**：元注解声明 → Loader 模板加载 → 统一事件感知——**后端差异收敛在 2 个定制点**：resolveResources:111（抽象）+ configureRefresher:122（可覆盖钩子）。
2. **对照定位**：比 Spring 多热更、比 Spring Cloud 多**新旧值与来源**——**统一的是事件面不是客户端**。

## §4 边界（一句话翻篇）

- @AliasFor/元注解机制 → spring-kernel 已封卷（02 篇一句带过）。
- Nacos 服务端配置管理 → nacos 系列翻篇。
- Apollo/etcd 后端 → Roadmap，未实现不讲。

## §5 Self-Test（3 题）

- Q1：三层各管什么？后端差异收敛在哪？
- Q2：和 Spring Cloud 的 EnvironmentChangeEvent 比，多哪两样？
- Q3："不替代各配置中心客户端"指什么？

## §6 衔接

- 概念收口；下篇 02 启动加载链：@Import 如何触发模板、配置怎么进 Environment（first/before/after 排位）。
