---
title: "05-使用：工程陷阱簿与动手路径"
series: "PolyGateway 接口级网关系列"
previous: "04-refresh-tradeoffs.md"
next: "06-interview-wrapup.md"
source_refs:
  - "guide §6（7 条陷阱）§7（步骤 0-3）+ 配置源码已核：PolyGatewayGatewayProperties（PREFIX=polygateway.gateway:47/enabled:52 默认 true/excludes:57/filterRules:62）/ ProviderProperties（prefix:41/enabled:46 默认 true）/ E2E（PolyGatewayGatewayE2ETest——9 场景、locator.enabled=false:67）/ 全仓 11 测试文件 42 个 @Test"
---

# 05-使用：工程陷阱簿与动手路径

## §0 它干了什么（例子先行）

接入配置其实只有两组（源码已核）：

```yaml
polygateway.provider.enabled: true      # provider 侧默认开（类在场才装配，04 篇）
polygateway.gateway:
  enabled: true                         # gateway 侧默认开
  excludes:                             # 生成期排除（03 篇机制）
    - patterns: ["/internal/**"]
      services: [order-service]
  filter-rules:                         # 给路由挂官方过滤器（04 篇）
    - services: [order-service]
      patterns: ["/orders/**"]
      filters: [AddResponseHeader=X-Filter-Rule,applied]
```

**生产 7 条陷阱簿**（guide §6——本项目实测踩过/修过的）：

| # | 陷阱 | 正解 |
|---|---|---|
| 1 | loadbalancer 缺失 → lb:// 全 **503** | SCG starter 对它 optional——**必须自己声明**（pom:33-37 注释为证，04 篇） |
| 2 | 多注册中心 → LB 拿到空 composite → 503 | **生产只启用一个注册中心客户端**（README 已写；E2E 排了 4 个自动配置） |
| 3 | 端点条件当正则用误匹配（`X-Version=1.0`） | `Pattern.quote` 转义（03 篇 :245，单测钉住） |
| 4 | 取反条件 `!x`/`x!=v` 官方谓词表达不了 | 跳过+debug（宁松勿错，03 篇 :229-232） |
| 5 | Spring 双条件类版本差异 | PathPatterns/Patterns 双分支取路径；测试要 MockServletContext |
| 6 | 官方 discovery.locator 并存 → **重复路由** | 关 `spring.cloud.gateway.discovery.locator.enabled`（E2E:67 实锤） |
| 7 | 聚合 pom 与 parent 关系 | 五项目根 pom 无 parent——聚合可直接叠加 |

## §1 动手路径（guide §7 的压缩版）

```
步骤 0 跑起来：mvn -f polygateway/pom.xml test（42 个 @Test/11 文件，全绿）
步骤 1 读 E2E：PolyGatewayGatewayE2ETest 九场景对照 02-04 篇——
        ①路径变量→200 ②filter-rule 响应头 ③Content-Type 匹配 ④不匹配→404
        ⑤双媒体类型 ⑥excludes→404 ⑦services 域（他服务同名接口仍 200）⑧未知路径→404 ⑨元数据+心跳→新路径 200
步骤 2 改四实验：A 加接口断言路由时机 B 换 RequestRateLimiter C 最小 MVC 看 metadata JSON 怎么产生
        D 改 excludes 不发心跳→不生效，手动 publishEvent(HeartbeatEvent)→生效
步骤 3 源码序：core(模型协议) → provider(收发) → locator(核心,对照 03) → predicates+FilterRule → boot(装配) → 回 E2E 对号
```

## §2 掌握卡（2 条）

1. **配置双前缀**：`polygateway.provider.*`（发布）/`polygateway.gateway.*`（excludes+filter-rules）——默认全开、类在场才装配。
2. **7 陷阱三类**：依赖（1/2）、匹配语义（3/4）、版本与并存（5/6/7）——**1/2 是 503 高发，面试最常问**。

## §3 边界（一句话翻篇）

- 每条陷阱的根因深挖 → 对应 02-04 篇机制。
- 实验 A-D 详细步骤 → guide §7 原文。
- 限流组件选型 → sentinel 系列。

## §4 Self-Test（3 题）

- Q1：lb:// 503 的两个可能原因？怎么排查？
- Q2：excludes 改了之后多久生效？怎么立即生效？
- Q3：E2E 场景⑦证明了什么？（services 域隔离）

## §5 衔接

- 使用线收口；下篇 06 面试收口：五篇回链 + 高频坑 + 终检 + 简历 bullet 对照——polygateway 系列完结、**poly 家族 ④ 全闭环**。
