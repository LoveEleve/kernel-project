---
title: "07-面试收口：五段式与陷阱"
series: "Sentinel 流量防卫系列"
previous: "06-deep-cluster-system.md"
next: "（本系列完）"
lab_refs:
  - "docs/中间件拷打/K05-Sentinel.md（五段式+陷阱6+追问8+话术+评分点）"
---

# 07-面试收口：五段式与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① K05 五段式回链 ② 陷阱 6 连 + 追问秒答 ③ 终检
```

## §1 五段式回链（K05 ①~⑥）

| 段 | 要点 | 回链 |
|---|---|---|
| ① Slot 链+滑动窗口 | 5 概念/9 槽/LeapArray | 01/02/05 |
| ② 流控与熔断 | 三策略/三态/三熔断策略 | 03 |
| ③ 数据源+集群+系统 | 三档持久化/TokenServer/5 阈值 | 04/06 |
| ④ xhs 生产口径 | **双轨限流**+真空期兜底+伪配置修正 | 04 §3 +本篇 §3 |
| ⑤ 追问 8 | 逐条回链（§2 精选 5） | 各篇 |
| ⑥ 话术 | 拆解见 §4 | 本篇 |

**链序权威定案**（本系列 05 已核）：K05/00 的槽清单在第 9 位命名略有出入——**以 core 的 SPI 文件九行为准**（末位=`DefaultCircuitBreakerSlot`；热点参数另有 ParamFlowSlot，小版本链图有差异——03 §6 同款口径）。

**追问秒答 5 条**（K05⑤精选）：滑动 vs 固定→边界双倍放行（03 §3 临界例子）；QPS vs 线程数→速率 vs 并发占用（03 §1）；排队会阻塞吗→会 sleep，只给"愿意等"的场景（03 §3）；改规则要重启吗→DataSource 推送（04）；单机 vs 集群→N 台=N 倍 vs TokenServer 加总（06）。

## §2 陷阱 6 连（K05 原表）

| # | 陷阱 | 正解 |
|---|---|---|
| 1 | 硬编码规则 | 生产 DataSource |
| 2 | 本地兜底覆盖 Nacos 推送 | ApplicationReadyEvent+真空期检查（xhs 已修） |
| 3 | fallback 不生效 | 签名/异常类型严格对齐 |
| 4 | 线程数当 QPS 用 | 并发占用 vs 速率语义不同 |
| 5 | 以为天生集群限流 | 默认单机——集群要 TokenServer |
| 6 | 假配置（bulkhead.*） | 先查源码键名（04 §3 案例） |

**危险信号**（K05 评分点）：以为 Sentinel 天生集群；说不清 BlockException 谁处理；**编造不存在的配置项**。

## §3 xhs 双轨口径一段（K05④精华）

网关轨：`SentinelGatewayFilter`+`GatewayFlowRule`（Nacos dataId `my-xhs-gateway-sentinel-flow.json`；SCA 转换器 Bean 手动注册；30s 真空期不覆盖推送；BlockHandler 出 429）。业务轨：自研 `@RateLimit`（Redis Lua ZSet 滑动窗口，45 处，fail-open+Micrometer 计数）。**缺口主动提**：用户级限流未落、集群限流未部署——"知道边界"是架构师口径。

## §4 话术（K05⑥原文）

> "Sentinel 在我们项目是双轨限流的第一轨：网关 SentinelGatewayFilter + GatewayFlowRule（Nacos 数据源 my-xhs-gateway-sentinel-flow.json，30 秒真空期回退 yml metadata 兜底），路由级 QPS 限流，BlockHandler 返回 429。第二轨是业务层自研 @RateLimit + Redis Lua ZSet 滑动窗口，方法级精确限流（45 处，Redis 挂了 fail-open 有 Micrometer 计数）。缺口主动说：用户级限流未落、集群限流需 TokenServer。隔离手段是组合拳：Sentinel 规则 + Feign 关闭重试和超时 + 最小连接 LB——之前文档写的'Feign 线程池舱壁'配置是无效键，已修正。"

## §5 终检（8 题，过 6 算过关）

1. 5 概念 + 三场景（01）
2. 9 槽顺序（SPI 文件口径）+ LeapArray 默认 2×500ms（02/05）
3. 三控制器 + 排队 costTime 公式（03）
4. 熔断三态 + 三策略（03）
5. 五类规则 + 推送链四跳（04）
6. 伪配置案例的教训一句话（04 §3）
7. TokenServer 治什么 + 两个"没有"（06）
8. 单机限流 N 台=N 倍——K05 陷阱 5（§2）

## §6 衔接

- Sentinel 系列 7 篇完，README 全挂链接；K05 拷打线兑现。
- 下一专题由你定（候选：sofa-jraft——L5-2 点名 / Kafka）。
