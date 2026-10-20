---
title: "05-使用：配置全表与注意事项"
series: "PolyZone 异地多活路由系列"
previous: "04-switch-link.md"
next: "06-interview-wrapup.md"
source_refs:
  - "README 配置全表（:96-108）与注意事项（:176-186）+ 接线源码已核：ZoneAutoConfiguration（@ConditionalOnZoneAvailable:64）/ ConditionalOnZoneEnabled（matchIfMissing=true:43）/ CustomizedLoadBalancerClientConfiguration（configurations=optimized-zone-preference:48,105）/ ZonePreferenceServiceInstanceListSupplier:42（javadoc:29 自称 SC 内置的 optimized replacement）/ ZoneAttachmentListener:41（spring-cloud/event 包）"
---

# 05-使用：配置全表与注意事项

## §0 它干了什么（例子先行）

接线三步（README 快速开始）：

```yaml
# 需要 AWS EC2/ECS 自动定位时额外引入 polyzone-aws；只配属性时不需要
spring.cloud.loadbalancer.configurations: optimized-zone-preference  # ① 启用 PolyZone 的 Supplier
polyzone.zone.preference.enabled: true                               # ② 开偏好（默认关！03 篇三关的第二关）
polyzone.spring.cloud.loadbalancer.customized: true                   # ③ 设为所有 LB 客户端默认
```

- **它干了什么**：三个开关接通"定位→过滤→切换"全链；**默认只定位不路由**（README :31——两级开关，偏好默认 false）。
- **本篇回答**：配置项全表怎么记？三注意事项各防什么坑？

## §1 配置项全表（README :96-108——按"谁的地盘"分组记）

| 组 | 属性（默认） | 关联 |
|---|---|---|
| **定位** | `polyzone.zone`（defaultZone）、`zone.enabled`（true）、`locator.timeout`（3000）、`locator.fast-fail`（false） | 02 篇定位链；`zone` 缺省/originalZone→走定位 |
| **偏好** | `preference.enabled`（**false**）、`ready-percentage`（100）、`same-zone-min-available`（5）、`disabled-zone`（空） | 03 篇十步算法的三阈值+开关 |
| **接线** | `customized`（false）、`spring.cloud.loadbalancer.configurations=optimized-zone-preference` | 本篇 §0 三步 |

**记忆钩子**：**两个默认要小心的**——偏好默认关（要显式开）、`zone` 默认 defaultZone（无定位效果）；**两个保命的**——ready 100%/min 5（03 篇退化保底）。

## §2 三注意事项（README :176-186——部署前必读）

| # | 坑 | 机制（回链） | 处方 |
|---|---|---|---|
| 1 | **非 AWS 启动 3 秒** | 仅在引入 `polyzone-aws` 时出现：EC2 定位器 supports 恒 true→问元数据到超时（02 篇 :56-58） | 非 AWS **显式配 `polyzone.zone`**（正常启动不走定位链），或接受首次 3s |
| 2 | **手工注册不带区域** | ZoneAttachmentListener:41 只挂 `InstancePreRegisteredEvent`（`AbstractAutoServiceRegistration` 发布）；写入 key=`polyzone.zone`（ZoneConstants:58，CloudServerZoneResolver:45 同 key 读回） | 手工注册要**自己写 metadata：`polyzone.zone=<zone>`** |
| 3 | **回滚基线=首次同步的区域** | originalZone 双规则（04 篇 :232-236） | 属性缺省→定位链识别；否则=首次配置值——**别把"首次"理解成"上次"** |

## §3 LB 接线机制（03/04 篇的拼装点）

```
configurations=optimized-zone-preference
  → CustomizedLoadBalancerClientConfiguration:105 把本系列 Supplier 装进 LB 容器
  → ZonePreferenceServiceInstanceListSupplier:42（Delegating 包装 delegate）
      内部调 ZonePreferenceFilter（03 篇十步）过滤候选
  → 原有负载均衡策略在幸存者里挑（README :89）
同名不同包：它与 SC 内置 Supplier 同名——javadoc:30 自称 optimized replacement（03 篇边界兑现）
```

**注册传播链（闭环）**：注册时 ZoneAttachmentListener 把 zone 写进 metadata → 下游 LB 拿实例列表时**有 zone 可读** → 03 篇就绪率阈值才可能达标——**②没接，①的偏好就不敢开**（README 注意事项 2 与 03 篇就绪率的咬合）。

## §4 掌握卡（2 条）

1. **三步接线**：configurations 启用 Supplier / preference.enabled 开偏好 / customized 全局默认——**两级开关默认只定位不路由**。
2. **三注意事项各回链一章**：3s（02 定位）/ 手工注册（03 就绪率的土壤）/ 回滚基线（04 originalZone）——**注意事项不是附录，是前三篇的部署面**。

## §5 边界（一句话翻篇）

- AWS 三类端点 URI 变量名 → 02 已给，用时查 README。
- 过滤器的插入位置由 LB Supplier 链决定（本项目固定为 caching 之后）→ 03 篇接线。
- 监控指标（Micrometer）→ Roadmap 未实现。

## §6 Self-Test（3 题）

- Q1：三步接线分别开什么？哪两个默认容易踩？
- Q2：手工注册为什么没区域？怎么补？
- Q3：注册传播和就绪率阈值什么关系？

## §7 衔接

- 使用线收口；下篇 06 面试收口：五篇回链 + 高频坑 + 终检——本系列完结。
