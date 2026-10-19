---
title: "04-使用：规则与动态数据源"
series: "Sentinel 流量防卫系列"
previous: "03-flow-circuitbreaker.md"
next: "05-核心源码.md（待写）"
lab_refs:
  - "sentinel-lab/docs/D8-NacosDataSource-数据源机制.md（§一§二核心段已读）"
  - "sentinel-lab/docs/D6/D7（§一背景句已读）"
source_refs:
  - "xhs common/config/SentinelBulkheadConfig.java（全文已读：伪配置修正注释）"
---

# 04-使用：规则与动态数据源

## §0 它干了什么（例子先行）

硬编码规则的三个致命伤（D8 §一原话）：改规则要重启 / 多实例各配各的（灰度时 A=1000 B=500）/ 谁改的没审计。生产姿势是**规则存 Nacos，推送即生效**：

```
改规则（Dashboard 或 Nacos 控制台）
  → Nacos 存储+版本号自增
  → push 给所有订阅客户端
  → NacosDataSource 的 Listener.receiveConfigInfo
  → Converter：JSON → List<FlowRule>
  → SentinelProperty.updateValue → XxxRuleManager.loadRules() 生效
```

- **它干了什么**：五类规则 + 一条推送链——规则从"代码里"搬到"配置中心里"。
- **本篇回答**：五类规则各管什么？推送链每一跳干什么？xhs 实装踩过什么"假配置"坑？

## §1 掌握卡（4 条）

1. **五类规则一张表**：`FlowRule`（量：QPS/线程数，03）/ `DegradeRule`（质：慢比例/异常率，03）/ `AuthorityRule`（**谁**：按调用来源黑白名单——D6："流控熔断管量，授权管谁"）/ `ParamFlowRule`（**哪个值**：热点参数单独限——D7："管资源总体 vs 管某个值"）/ `SystemRule`（全局：Load/CPU/RT——06）。
2. **推送链四跳**（D8 §一原图）：Nacos push → `receiveConfigInfo` → `Converter.convert(JSON)` → `SentinelProperty.updateValue` → Manager 生效——**Listener 在客户端单线程池上跑**（不阻塞业务线程）。
3. **NacosDataSource 的角色**：`(serverAddr, groupId, dataId, converter)` 四参构造——**一种规则一个 dataId**，Listener 初次订阅即拉全量、之后只推增量变更。
4. **@SentinelResource 注解**：AOP 方式圈资源（01 的 `SphU.entry` 是编程式）——**块处理（BlockHandler）**方法接住 BlockException，业务代码可选不写 try-catch。

## §2 为什么"动态"是体系问题（D8 §一收口）

不是"读个配置"那么简单：**一致性**（推送=所有实例同一时刻同一版本）+ **审计**（Nacos 版本历史）+ **可回滚**（配置版本回退）——手写热加载只有第一半，没有后两半。

## §3 xhs 实装与"伪配置"修正（真实案例）

`SentinelBulkheadConfig` 的修正注释（2026-09-23，全文已读）值得全文记住：

> 原实现 `System.setProperty("csp.sentinel.bulkhead.*")` 宣称配置"舱壁隔离"（bulkhead：把故障圈在局部、不波及全局的隔离模式）——**这些键并非 Sentinel 的配置项**（Sentinel 没有开箱的 Feign 线程池舱壁），属无效配置。实际生效：① 流控/降级规则（**Nacos 数据源推送**）；② Feign 全局关重试+超时；③ 自研最小连接 LB。

**教训**：框架没有的功能，setProperty 也变不出来——**"配了"要能指出生效链路**（哪个类读了这个键）；xhs 现在只留启动日志声明真实来源，把"Feign 线程池舱壁"列为**设计项**而非已做——诚实工程的范本。

## §4 边界（一句话翻篇）

- Converter/Property 传播链逐行 → D8 §三§四用时查。
- 授权/热点规则的字段级配置 → D6/D7 用时查。
- Dashboard↔客户端 Transport 心跳 → D12 翻篇。

## §5 Self-Test（4 题）

- Q1：五类规则各管什么？（量/质/谁/哪个值/全局）
- Q2：推送链四跳按顺序说；Listener 为什么用独立线程池？
- Q3：硬编码三致命伤？"动态"还差哪两半（审计/回滚）？
- Q4：xhs 的伪配置坑教了什么？怎么验证一个配置真的生效？

## §6 衔接

- 使用线收口；下篇 05 核心源码：SlotChain 怎么装配、LeapArray 关键类——1.8.8 源码落点。
