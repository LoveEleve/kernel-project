---
title: "07-xhs生产对照：配置与路由实景"
series: "Nacos 协调服务系列"
previous: "06-security-gray-cluster.md"
next: "08-面试收口.md（待写）"
source_refs:
  - "xhs order application.yml:61-71（discovery+config 双段 + metadata zone）"
  - "xhs 15 服务 import my-xhs-common.yaml / 1 个 gateway.yaml"
  - "xhs common ZoneContext/ReadWriteRoutingDataSource（zone 路由）"
  - "零 bootstrap.yml 残留（A-10 迁移完成的活例子）"
---

# 07-xhs生产对照：配置与路由实景

## §0 它干了什么（例子先行）

xhs 的 Nacos 用法一张脸：

```
20 个服务：spring.cloud.nacos.discovery（注册，namespace=my-xhs）
15 个服务：import my-xhs-common.yaml（一份共享配置管住 15 家的 DB/MQ 公共项）
0 个 bootstrap.yml（Boot 3 迁移完成——A-10 的活例子）
metadata.zone：按机房/可用区路由（zone→ZoneContext→数据源路由）
```

- **它干了什么**：注册+配置双中心在 xhs 的落法——共享配置收敛公共项、zone 元数据做同区路由。
- **本篇回答**：25 处 yml 各写什么？共享配置怎么收敛？zone 干什么用？

## §1 掌握卡（3 条）

1. **双段配置**：每个服务 yml 两段——`discovery`（注册：server-addr/namespace/metadata）+ `config`（import）；xhs 服务端 `192.168.0.142:18848`、namespace 统一 `my-xhs`。
2. **共享配置收敛**：`my-xhs-common.yaml` 被 **15 个服务** import（+gateway 专属 1 个）——01 说的"一份 DB 配置 10 家复用"的实景；`refreshEnabled=true` 配合 05 的刷新链路热生效。
3. **零 bootstrap 残留**：全项目无 `bootstrap.yml`——SC 2025 移除前就迁完了（A-10 迁移的生产范本）。

## §2 yml 实景（order 服务原文）

```yaml
spring:
  cloud:
    nacos:
      discovery:
        enabled: true
        server-addr: 192.168.0.142:18848
        namespace: my-xhs
        metadata:
          zone: ${MYXHS_ZONE:defaultZone}    # ← 机房标签，环境变量可切
      config:
        server-addr: 192.168.0.142:18848
        namespace: my-xhs
  config:
    import:
      - optional:nacos:my-xhs-common.yaml?group=DEFAULT_GROUP&refreshEnabled=true
```

统计：discovery 20 家 / common.yaml import **15 家** / gateway.yaml import 1 家 / bootstrap **0 家**。

## §3 zone 元数据：同区路由

discovery 里那行 `metadata.zone` 不是装饰：

```
metadata.zone (注册时打标)
  → 服务端按 metadata 存储/查询（A-08 元数据）
  → 数据源侧：ZoneContext 拿当前 zone → ReadWriteRoutingDataSource 按 zone 路由（源码已核）
  → 实例级同区负载均衡 → B-03 扩展点（MetadataBasedLoadBalancer，lab 方案）
```

价值：跨机房调用贵且慢——同区优先，故障域也小（一个 zone 挂不拖全区）。`MYXHS_ZONE` 环境变量切换：一套配置多套部署。B-03 的 `MetadataBasedLoadBalancer` 就是这条思路的扩展点版。

## §4 与本系列各篇的对应

| xhs 实景 | 对应篇 |
|---|---|
| import 共享配置 + refreshEnabled | 01 §2 / 05 §2 |
| 无 bootstrap | 05 §1（Bootstrap 已死） |
| metadata zone | 06 §2 边界外的能力 + B-03 扩展点 |
| namespace=my-xhs 统一 | 06 §2（单环境单 ns——多环境再拆） |

## §5 边界（一句话翻篇）

- zone 路由实现细节（ZoneContext/读写分离联动）→ 用时看 common 源码。
- B-03 MetadataBasedLoadBalancer 手写 → `lab B-03`。
- Prometheus-SD（A-09）：nacos_sd_configs 让 Prometheus 自动发现实例——运维侧一句带过，用时查 lab。

## §6 Self-Test（3 题）

- Q1：xhs 的共享配置收敛到几个文件？覆盖多少服务？
- Q2：`metadata.zone` 注册后，消费侧谁在用它？（ZoneContext→同区路由）
- Q3：xhs 为什么没有 bootstrap.yml？（Boot 3 + import，A-10 迁移完成）

## §7 衔接

- 生产线收口；下篇 08 面试收口（D-14 高频题 + 陷阱 + 终检）。
