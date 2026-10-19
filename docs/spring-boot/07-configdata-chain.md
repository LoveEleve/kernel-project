---
title: "07-ConfigData加载链"
series: "Spring Boot 深化系列"
previous: "06-actuator-endpoints.md"
next: "08-生产特性与总收口.md（待写）"
source_refs:
  - "boot-3.2.5 config 包已核：ConfigDataEnvironmentPostProcessor/ConfigDataEnvironment:61/StandardConfigDataLocationResolver/ConfigDataImporter"
cross_refs:
  - "Nacos 05（spring.config.import 语义与优先级——本篇讲其机制层）"
---

# 07-ConfigData加载链

## §0 它干了什么（例子先行）

```yaml
spring:
  config:
    import:
      - optional:nacos:my-xhs-common.yaml?group=DEFAULT_GROUP&refreshEnabled=true
```

这行 `nacos:` 前缀是怎么被 Boot 认识的？——一条**插件化加载链**：

```
ConfigDataEnvironmentPostProcessor（run 早期的环境后处理器）
  → 解析 import 列表，得到一批 "location"（optional:nacos:xxx）
  → LocationResolver：谁认识 "nacos:" 这个 scheme？（Nacos 的 jar 里注册了自己的 Resolver）
  → Loader：向 Nacos 拉配置 → 包装成 PropertySource 挂进 Environment
  → 再由主配置加载器处理 application.yml（覆盖序在内核 07 已讲）
```

- **它干了什么**：把"配置从哪来"做成 **Resolver（认位置）+ Loader（真加载）双 SPI**——`file:`/`optional:`/`nacos:` 各是一个 scheme 实现。
- **本篇回答**：四件套（PostProcessor/Environment/Resolver/Loader）各干什么？`optional:` 在哪一层生效？为什么 Nacos 能"寄生"进 Boot 配置体系？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **ConfigData** | Boot 2.6+ 的配置数据统一模型（取代旧 EnvironmentPostProcessor 手工作坊） |
| **ConfigDataEnvironmentPostProcessor** | 触发整个流程的后处理器（run 早期——environmentPrepared 之前） |
| **ConfigDataEnvironment** | 流程编排器（:61）——收集 contributors、合成 Environment |
| **LocationResolver** | "这个 scheme 我认识"（`StandardConfigDataLocationResolver` 认 file: 等；Nacos 提供自己的） |
| **Loader** | 真正去读（本地文件/远端配置中心） |

## §2 工程使用

**三条语义（Nacos 05 回收 + 机制层兑现）**：

1. `optional:` 前缀=**缺了不炸**（Loader 层判缺）——生产默认建议加（内核系列 05 同款纪律：显式处理缺失 vs 静默忽略的取舍，Boot 给了开关）；
2. `?group=`/`?refreshEnabled=` 参数由**各 scheme 的 Loader 自己解释**——Boot 只传串不解释；
3. 第三方要寄生进配置体系=**注册一对 Resolver+Loader**（META-INF/services）——和 Micrometer Registry/Sentinel Slot 同一插件哲学：**Boot 提供插座，生态提供插头**。

**排查口诀**：import 没生效三查——**scheme 认识吗（Resolver 在不在）/ optional 吃了吗 / 覆盖序对吗（内核 07 的 PropertySource 排序）**。

## §3 掌握卡（2 条）

1. **时机**：整条链跑在 **environmentPrepared 之前**（内核系列 02 的事件波次再+1：配置数据先备好，事件才有完整 Environment 可发）。
2. **与 EnvironmentPostProcessor 的分工**：旧时代直接改 Environment 的后处理器仍在，但"从文件/配置中心**加载**配置"的正路是 ConfigData——**读取归 ConfigData，微调归 EnvironmentPostProcessor**。

## §4 边界（一句话翻篇）

- Importer/Contributor 链逐行 → config 包源码用时查。
- Nacos 新版（2023+）对 ConfigData 的官方实现细节 → Nacos 系列 05 的语义已够工程用。
- 配置密文/加密（jasypt 类）→ 翻篇（Seata 系列曾提传输/存储加密区分）。

## §5 Self-Test（3 题）

- Q1：四件套各干什么？Resolver 和 Loader 的分工？
- Q2：`optional:` 在哪一层生效？
- Q3：想让自研配置中心被 spring.config.import 认识，要实现什么？（Resolver+Loader 双 SPI）

## §6 衔接

- 配置加载机制收口；下篇 08 生产特性与总收口：优雅停机/lazy-init/devtools 一表 + **Boot 全域大终检**（八篇）。
