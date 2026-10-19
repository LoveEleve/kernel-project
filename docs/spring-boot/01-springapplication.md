---
title: "01-什么是SpringApplication：run的角色"
series: "Spring Boot 深化系列"
previous: "README.md"
next: "02-run全流程.md（待写）"
source_refs:
  - "boot-3.2.5 SpringApplication.run():317 体内骨架（prepareEnvironment/printBanner/createContext/prepareContext/refreshContext/callRunners/ready——已核）"
---

# 01-什么是SpringApplication：run的角色

## §0 它干了什么（例子先行）

每个 Boot 应用的 main 都长这样：

```java
@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);   // 一行，但背后是一场编排
    }
}
```

`SpringApplication.run` 不是"启动一个容器"那么简单——它是一场**固定剧本的编排**：

```
准备环境（yml/环境变量装进 Environment）→ 广播 starting 事件
  → 创建并准备 ApplicationContext → refreshContext（★这就是内核系列 05 的 refresh！）
  → 广播 started → 跑 CommandLineRunner → 广播 ready
```

- **它干了什么**：SpringApplication=**启动编排器**——把"环境、上下文、事件、命令行参数"按剧本串起来；**真正造 Bean 的仍是内核的 refresh**。
- **本篇回答**：run 和内核 refresh 什么关系？这个编排器自己又是谁（类角色）？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **SpringApplication** | 启动编排器类——run() 是它的唯一正戏 |
| **SpringBootServletInitializer** | war 部署时的另一入口（容器式部署——知道即可） |
| **Runner** | `CommandLineRunner`/`ApplicationRunner`——refresh 完成后执行的"开机自检" |
| **RunListeners** | 启动过程的事件广播器（starting/started/ready 三波） |
| **★分工线** | **编排归 SpringApplication，造 Bean 归 refresh（内核 05）**——Boot 没有重写容器，只是把容器"开场白"写好了 |

## §2 和内核系列的咬合图

```
本系列（Boot 运行机制）                内核系列（已封卷）
prepareEnvironment（配置就位）   →    Environment/PropertySource（内核 07）
refreshContext                  →    refresh 十三步（内核 05 ★）
listeners.started               →    事件机制（内核 07）
callRunners                     →    Boot 新增的最后一段
（自动配置在 refresh ★1 内生效） →    自动配置体系（内核 04）
```

**一句话定位**：内核讲"容器怎么造 Bean"；本系列讲"main 到容器造好之间，Boot 多写了哪些开场白与收尾"。

## §3 掌握卡（2 条）

1. **run 的骨架七拍**（源码体内已核，02 逐步展开）：环境 → 建上下文 → 准备 → **refreshContext（=内核 refresh）** → started 事件 → runners → ready 事件——**Boot 的私货只有首尾，中段全是内核**。
2. **失败也走剧本**：任何一步抛异常 → `handleRunFailure` → 广播 failed 事件 + FailureAnalyzer 排版报错——**Boot 的"友好报错"就是这一层**（常考：为什么 Boot 报错比裸 Spring 好读）。

## §4 边界（一句话翻篇）

- 每一步细节 → 02。
- Banner/StartupInfoLogger → 装饰件翻篇。
- AOT 处理器（SpringApplicationAotProcessor）→ 知道存在。

## §5 Self-Test（2 题）

- Q1：run 和 refresh 的关系？谁造 Bean？
- Q2：Runner 在哪个事件之后跑？Boot 的友好报错挂在哪？

## §6 衔接

- 角色定位收口；下篇 02 run 全流程：骨架七拍逐步拆（每步一行源码锚点）。
