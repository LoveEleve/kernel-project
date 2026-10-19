---
title: "06-深水：集群限流与系统自适应"
series: "Sentinel 流量防卫系列"
previous: "05-core-source.md"
next: "07-面试收口.md（待写）"
lab_refs:
  - "sentinel-lab/docs/D9-ClusterToken-集群限流.md（§一§二已读）"
  - "sentinel-lab/docs/D10-SystemSlot-系统自适应.md（§一§二§三已读）"
  - "sentinel-lab/docs/D11/D13（地图句）"
---

# 06-深水：集群限流与系统自适应

## §0 它干了什么（例子先行）

两个致命的"各自看都没事"：

```
单机限流天花板：实例A 看自己 QPS=400（<500 放行）+ 实例B 同样 400 → 全局实际 800（远超 500）
入口流控盲区：getUser 限流中、createOrder 限流中——每个资源都没超标，但整机 CPU 已爆
```

- **它干了什么**：集群限流把"判断权"集中到 TokenServer；系统自适应把"被保护对象"从资源升到**整机**。
- **本篇回答**：TokenServer 怎么工作？两模式边界？5 个全局阈值各是什么？

## §1 掌握卡（3 条）

1. **集群限流=中心化令牌**（D9）：所有实例 `TokenClient.requestToken(resource, 1)` → TokenServer 全局计数，超限返回 BLOCKED——治"单机只能看见自己"的精度天花板。
2. **两模式+一个硬边界**（D9 §二）：**独立模式**（单独 JVM，生产）/ **嵌入模式**（某实例兼任，测试）——**无自动选举、Server 宕机不自动 failover**（角色靠 `ClusterStateManager.applyState` 手动指定）——集群限流把可用性责任交给了部署。
3. **系统自适应=5 阈值全局闸门**（D10）：`highestSystemLoad`（Load1）/ `highestCpuUsage` / `qps`（全局入口）/ `avgRt` / `maxThread`——`-1`=该维度不限，可任意组合；JMX 后台线程**每秒采集**，任一超标 → `SystemBlockException` **拦所有资源**。

## §2 集群流控的取舍（推导）

**朴素答案**：全局流量 Redis 计数一把梭？

**But**：每个请求都打 Redis=把限流本身变成新瓶颈；网络抖动时限流跟着抖。

**Therefore**：TokenServer 本地内存计数，实例只在**候选要放行时**才来问一次令牌（省 RPC）；TokenServer 挂了——按客户端配置可**降级单机兜底**（精度降级换可用性；行为细节 D9 用时查）。

**Cost**：多一跳 RPC + Server 单点（部署责任）——**只对"全局阈值必须精确"的接口开集群流控**，普通资源保持单机。

## §3 系统自适应与流控的分工（D10 的问题意识）

FlowRule 问"这个接口来多少"；SystemRule 问"**这台机器还活着吗**"。多个接口各自限流都正常、但 Load 已经 50+——只有整机视角能看见。**闸门位置**：链上 SystemSlot 在 FlowSlot 之前（02 链序第 6 vs 第 7）——系统快死时先拦全局，连流控判断都省了。

## §4 适配器一览（D11 一句带）

Sentinel"插入"宿主框架的方式：WebMVC=Filter/拦截器、Dubbo=Filter、**Feign=拦截器（xhs 的 feign.sentinel.enabled 生效点）**——适配器把"框架入口"翻译成 `SphU.entry`（01 的检查站）。Context/Entry 的 ThreadLocal 生命周期与嵌套 → D13 用时查。

## §5 边界（一句话翻篇）

- passClusterCheck/requestToken 源码级 → D9 §三§四。
- JMX 采集细节/SystemRuleManager 全分支 → D10 §三§四。
- 全局限流的准确率/网络分区行为 → 生产陷阱（D9 §五）用时查。

## §6 Self-Test（3 题）

- Q1：单机限流的精度天花板用一个算式说明；TokenServer 怎么解？
- Q2：嵌入模式的两个"没有"（无选举/无 failover）意味着什么？
- Q3：5 个系统阈值哪些可以只开两个？`-1` 语义？

## §7 衔接

- 深水线收口；下篇 07 面试收口（K05 五段式 + 陷阱 + 终检）。
