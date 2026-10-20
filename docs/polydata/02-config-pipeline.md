---
title: "02-原理一：配置模型与 6 步管道"
series: "PolyData 动态多数据源系列"
previous: "01-what-is-polydata.md"
next: "03-child-context.md"
source_refs:
  - "已核：JSON 解析 DynamicJdbcConfigUtils（parseDynamicJdbcConfig:162/readValue:165/getDynamicJdbcConfigs:85/ObjectMapper:74）/ 入口 DynamicJdbcContextApplicationListener:49（extends OnceApplicationPreparedEventListener:38）→ processDynamicJdbcContext:211-216→processor.process:215 / 管道 DynamicJdbcContextProcessor.process:66-86（六步 :69,74,77,79,83,86）/ 校验阻断 :109-111 ConfigValidationException / 深克隆入参 :139 / 子上下文复用管道 DynamicJdbcChildContext:161 + 防递归 setDynamic(false):254"
---

# 02-原理一：配置模型与 6 步管道

## §0 它干了什么（例子先行）

一行 JSON 属性变成运行时数据源（源码链）：

```
ApplicationPreparedEvent（Environment 就绪、bean 加载前）
  → DynamicJdbcContextApplicationListener:49（父类 Once...:38——只处理一次）
  → getConfigs:125 扫 polydata.jdbc.configs.* → parseDynamicJdbcConfig:162
      Jackson objectMapper.readValue:165 → DynamicJdbcConfig 对象（@JsonProperty 映射 :81,90）
  → processDynamicJdbcContext:211 → processor.process:215【6 步管道:66-86】
      1 registerAnnotationConfigProcessors:69   装好注解驱动的基建
      2 postProcess...:74                        SPI：默认值/规范化
      3 validate...:77 → 失败 throw :109-111     SPI：校验，ConfigValidationException【阻断启动】
      4 processDynamic:79（仅 isDynamic=true）    深克隆 :139 → 注册 DynamicDataSource 门面
      5 propertiesSynthesizers:83                 SPI：合成 spring.* 属性
      6 registerDynamicJdbcConfigBeanDefinitions:86 SPI：注册模块 BeanDefinition
```

- **它干了什么**：**配置的"受理-质检-分发"流水线**——质检与分发按 SPI 扩展，坏了当场拒绝启动。
- **本篇回答**：为什么在 ApplicationPreparedEvent 这个时点？防递归怎么做的？管道为什么能复用？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **配置模型** | 一个 JSON=一个 `DynamicJdbcConfig`（Jackson @JsonProperty：datasource:81/ha-datasource:90/transaction:96 等各一个模块槽（六类模块 README:29）） |
| **Once ApplicationPreparedEvent** | 基类:38——**时点**：Environment 已含配置、Bean 尚未加载——**改 Environment 还来得及**（步骤 5 合成的属性能被后续消费） |
| **6 步管道** | 受理(1)→质检(2,3)→分发(4,5,6)——**2/3/5/6 是 SPI 点**；4 是核心 DynamicDataSource 注册，不是 SPI |
| **防递归** | 门面重建子上下文前 `setDynamic(false):254`——子上下文再跑管道时**跳过第 4 步**，不再嵌套造 DynamicDataSource |
| **管道复用** | 子上下文也调同一 Processor（DynamicJdbcChildContext:161）——**父子用同一条流水线装配，区别只是 context 传谁** |

## §2 关键推导（两问）

**① 为什么校验失败要"阻断启动"（而不是打日志继续）？**
数据源配置坏了（URL 缺失/账号空/模块冲突），**带病启动=请求进来才炸**——比启动失败危险得多。`throw ConfigValidationException:109-111` 让 fail-fast 落在部署期。**同族**：polyzone 的 `fast-fail` 也是同款取舍（默认关是因为定位失败≠致命；配置校验失败=致命——**按故障的确定性决定 fail 策略**）。

**② 为什么步骤 4 要深克隆（:139）？**
配置对象后续会被**多处共享**（子上下文持有、热替换时又要 clone 一份重建——04 篇 :246）。若注册时直接共享引用，**任何一处改动（如 254 的 setDynamic(false)）都会污染源配置**。深克隆=每份上下文拿到自己的快照——**04 篇原子替换的前提就是"配置不可变"**。

**③ 四个 SPI 为什么拆这么细？**
一个模块（如 mybatis-plus）要在不同阶段各插一手：预处理自己的属性、校验互斥、合成自己的 spring 配置、注册自己的 BeanDefinition——**拆 4 个点=模块按需挂钩，不实现的点不背锅**（README :80"新增模块只需实现它们"的机制基础）。

## §3 掌握卡（2 条）

1. **时点**：ApplicationPreparedEvent（配置已读/Bean 未载）→ Jackson 解析 → 6 步=受理/质检（**失败阻断**）/分发（**2/3/5/6 四个 SPI 挂载点**；4 是核心注册）。
2. **两个关键细节**：深克隆=配置不可变（热替换前提）；setDynamic(false)=防递归（父子同管道、只造一次门面）。

## §4 边界（一句话翻篇）

- 四 SPI 各自的实现清单 → 06/07 按模块讲。
- Jackson 映射细节 → 翻篇（@JsonProperty 对着配置模型看即可）。
- 默认值/URL 归一化的具体规则 → 07 使用篇属性表。

## §5 Self-Test（3 题）

- Q1：写出 6 步（哪两步是质检、哪四步挂 SPI）。
- Q2：校验失败为什么抛异常而不是警告？
- Q3：深克隆和 setDynamic(false) 各防什么？

## §6 衔接

- 管道收口；下篇 03 子上下文隔离与 Bean 升迁：每个配置怎么长出独立世界、关键 Bean 怎么升迁给外面用。
