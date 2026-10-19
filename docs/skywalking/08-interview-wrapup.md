---
title: "08-面试收口：五段式与陷阱"
series: "SkyWalking 可观测系列"
previous: "07-xhs-production.md"
next: "（本系列完）"
lab_refs:
  - "docs/中间件拷打/K14-SkyWalking.md（五段式+陷阱8+追问8+评分点+话术）"
---

# 08-面试收口：五段式与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① K14 五段式回链 ② 陷阱 8 连 + 追问秒答 ③ 终检
```

## §1 五段式回链（K14 ①~⑥）

| 段 | 要点 | 回链 |
|---|---|---|
| ① 原理 | 四组件/数据模型 refs/插件/采样 | 01/02/03 |
| ② 项目用法 | 栈+实证+双 traceId 表 | 07（+01 §3） |
| ③ 三坑演进 | 9.1.0 缺插件/没数据/版本适配 | 03/04 |
| ④ 开销采样 | 5.4% 实测+口径纪律 | 05 |
| ⑤ 追问 8 | 逐条回链（§2 精选 4） | 各篇 |
| ⑥ 话术 | 拆解成出处表 | 07 §2 |

**追问秒答 4 条**（K14⑤精选）：还要业务 traceId？→互补（01/07）；跨 MQ/线程串吗？→常见框架有插件、自定义需手动增强，业务 traceId 兜底（03/01）；OAP 挂了？→异步缓冲，业务无感，观测盲区（02 §1）；升级 agent？→换 DIR+插件复检+重启（04 §2）。

## §2 陷阱 8 连（K14 原表）

| # | 陷阱 | 正解 |
|---|---|---|
| 1 | 插件版本不匹配（javax→jakarta） | 插件清单对齐框架大版本 |
| 2 | 组件在跑≠有数据 | 脚本参数化+注册审计 |
| 3 | 插件 3/4/5.x 与 6.x 冲突 | 移出旧、移入新 |
| 4 | `._*` 元数据残留 | 安装脚本清理 |
| 5 | 降噪对 WebFlux/GW 无效 | 接受/升级 agent |
| 6 | agent 日志互相覆盖 | logging.dir 分模块 |
| 7 | 采样开销误判 | 双口径标注+DISABLED |
| 8 | OAP 单点 | HA/自愈告警 |

**危险信号三条**（K14 评分点）：APM 与业务 traceId 混为一谈；不知道插件版本冲突；**拿"装了 agent"当"有可观测性"**——对照 07 §1 审计三件答。

## §3 与其它系列的接线

- 双 traceId/关联 → Tracing 05/06（correlation 已封卷）。
- 采样三闸门 → Tracing 07 + 本系列 05。
- 独立 ES 容量/高基数 → ES 系列 02/04。
- 告警外部化 → K15-Prometheus（拷打线）。
- OpenTelemetry 对比 → Tracing 01 三路线 + K14 发散，不另开。

## §4 话术（K14⑥原文，出处见 07 §2）

> "SkyWalking 我们 OAP 9.7 + UI + 独立 ES 19201，Agent 9.7 通过 javaagent 接入、release 脚本自动挂载。实证：15 个服务全注册，gateway→home 一条 trace 53 span，CROSS_PROCESS/CROSS_THREAD 完整，Lettuce 异步 Redis 也串上。接入踩过三坑：9.1.0 缺 SpringMVC 6.x 插件（jakarta）导致只有 Redisson 零散 trace；'组件在跑但没数据'——release 没挂 javaagent、restart 还指向云 collector；插件版本适配（3/4/5.x 移出、6.x/webflux/gateway 移入 + 清 ._*）。开销实测 5.4%（7,439 vs 7,863 RPS），所以性能基线要标'无 agent'口径。它和业务 traceId 是互补关系。"

## §5 终检（8 题，过 6 算过关）

1. 四组件+端口栈（01/06）
2. Segment/三 span/refs（02）
3. premain/witness class/9.1.0 死法（03）
4. 挂载五参数+插件移出移入清单（04）
5. 采样三档+5.4% 怎么测的+口径纪律（05）
6. OAL 是什么；xhs 告警规则原文含义（06）
7. 审计三件 vs "装了就算"（07）
8. 双 traceId 三层递进收口（01/07）

## §6 衔接

- SkyWalking 系列 8 篇完，README 全挂链接；Tracing 系列的钩子债全部兑现（三件叠加/双号/采样/agent 配置）。
- 下一专题由你定（候选：ShardingSphere（K12 现成）/ Kafka）。
