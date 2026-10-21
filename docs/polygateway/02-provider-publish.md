---
title: "02-原理一：Provider 发布链"
series: "PolyGateway 接口级网关系列"
previous: "01-what-is-polygateway.md"
next: "03-route-generation.md"
source_refs:
  - "已核：PolyGatewayProviderListener（:31 监听 InstancePreRegisteredEvent/:40-41 触发 publish）/ WebEndpointMappingsCollector（collect:61→逐 HandlerMapping:63-64→sort:66/内容 id（WebEndpointMapping.stableId:160-169）/基础设施过滤 :124-125 org.springframework.boot./comparator:131）/ WebEndpointMetadataPublisher（publish:77/encode:79/metadata :80-81/context-path 常量 :56 读取 :83-85）/ Codec（ObjectMapper:49/encode:78/decode:96——schema free 协议注释 :35/缺 id 回退 stableId :142-144）/ Constants（web-endpoints:35/context-path:40）"
---

# 02-原理一：Provider 发布链

## §0 它干了什么（例子先行）

Spring MVC 服务启动的一瞬间，接口清单怎么进注册中心（源码链）：

```
服务要注册了 → InstancePreRegisteredEvent（注册前事件）
  → PolyGatewayProviderListener:31 → publisher.publish(registration):40-41
WebEndpointMetadataPublisher.publish:77：
  ① WebEndpointMappingsCollector.collect(context):61
     → 遍历所有 RequestMappingHandlerMapping :63-64
     → 每个 RequestMappingInfo → 六维 WebEndpointMapping（路径/方法/参数/头/consumes/produces）
     → 过滤 org.springframework.boot.* 基础设施 :124-125（actuator/error 不发）
     → 确定性排序 sort:66 → 六维内容哈希生成 id（同一接口跨实例一致，接口集不同也不漂移）
  ② Codec.encode:78 → JSON 数组（schema free :35）
  ③ 写 metadata：web-endpoints:81 + context-path:83-85（读 server.servlet.context-path:56）
  → 注册中心持有"带接口清单的实例"
```

- **它干了什么**：**注册的顺路车**——不加新接口、不加新端点，借注册前事件把清单塞进 metadata。
- **本篇回答**：六维模型是什么？id 为什么必须跨实例一致？context-path 为什么也要发？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **六维 WebEndpointMapping** | patterns/methods/params/headers/consumes/produces——Spring MVC 能声明的路由条件**全集**（core/:44，Builder 构造） |
| **stableId** | 由 kind.name()+negated+六维**共 8 因子** 31 链式哈希 :160-169——**同接口跨实例同 id**；位置序号在实例接口集不同（金丝雀）时会漂移，因此 collector 不再按序号覆盖 |
| **确定性排序** | mappingComparator:131 保证"同一套接口在任何实例/任何启动次序下顺序一致"——**元数据输出稳定，便于对账/去重**（id 本身不依赖位置） |
| **基础设施过滤** | 类名前缀 `org.springframework.boot.` :124-125 排除——**actuator/error 不该被网关路由到** |
| **schema free 协议** | Codec:35 注释原话——字段演进靠"网关宽容读"：缺维度按空、缺 id 回退 stableId（codec:142-144，03 篇并集解码） |
| **context-path 同发** | :83-85——网关拼路径要用（03 篇路径公式），**清单和路径语境必须成对** |

## §2 关键推导（三问）

**① 为什么 id 必须跨实例一致？**
路由 id 是网关侧路由表的主键——**同一接口在不同实例生成的路由若 id 不同，CachingRouteLocator 会见到重复/漂移的路由**（排序、去重、可视化全乱）。旧实现"排序后按序号赋 id"只在**接口集完全相同**时成立；金丝雀实例多一个排在前面的接口，后续接口 id 就会整体后移，甚至与另一实例的同号接口撞车。现在 collector 保留排序（元数据输出稳定），但 id 由 **stableId:160-169** 从六维内容计算；`kind.name()` 而不是枚举 identity hash，跨 JVM 稳定。网关侧（03 篇全实例并集）才好合并。

**② 为什么挂 InstancePreRegisteredEvent 而不是 ApplicationReadyEvent？**
注册前=**metadata 还能改的最后时机**——实例一旦注册完成，清单就补不进去了。与 polyzone 的 ZoneAttachmentListener 同事件同款（polyzone 05 注册传播——**家族共用"注册前最后一班车"**），xhs 的注册时机语境回链 spring-cloud-alibaba 系列。

**③ 为什么借 metadata 而不是独立接口/配置中心？**
清单的生命周期=**实例的生命周期**（实例下线清单消失、金丝雀实例带自己的清单——03 篇并集正是靠这点）——挂 metadata=**数据跟着事实走**，不引入第二套状态源。

## §3 掌握卡（2 条）

1. **发布四步**：注册前事件 → 扫 MVC 成六维 → 排序（输出稳定）+ 六维内容 id → encode 进 metadata（+context-path 成对发）。
2. **两个一致性**：id 跨实例一致（stableId:160-169 内容哈希）；清单与实例同生共灭（metadata 语义）。

## §4 边界（一句话翻篇）

- RequestMappingInfo 六维怎么从注解提取 :70-124 → 按需读源码。
- JSON 字段增删的兼容细节 → 缺维度按空、缺 id 回退 stableId（codec:142-144，guide §4.5）。
- 注册中心 metadata 大小限制 → 部署面（面试提一句"清单很长时注意注册中心限制"即可）。

## §5 Self-Test（3 题）

- Q1：写出发布四步（带类名）。
- Q2：为什么 id 不能用"排序后序号"？金丝雀接口集不同会怎样？
- Q3：context-path 为什么必须和清单一起发？

## §6 衔接

- 发布链收口；下篇 03 路由生成与请求旅程：网关怎么把清单翻译成路由（谓词映射/路径公式/特异排序/生成期排除）。
