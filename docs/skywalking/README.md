# SkyWalking 可观测（APM）系列

> 部署基线：**OAP/Agent 9.7.0**（xhs 运行态）；源码语料 **10.4.0**（`code/spring/skywalking`，新版参考，差异按部署口径）
> 素材：`docs/中间件拷打/K14-SkyWalking.md`（五段式+三坑演进+开销实测+陷阱 8）+ xhs `config/skywalking/` 全套 OAP 配置
> 钩子债：Tracing 系列的"三件叠加/双号/agent 配置"全部在此兑现
> 计划：L5-3 可观测性 🔴 用户点名

## 一、总表（8 篇）

| # | 文章 | 类型 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [APM 全景：三件叠加的最后一块](01-apm-overview.md) | 理论 | APM 管什么；与业务 traceId 分工 | K14①②+Tracing 回链 |
| 02 | [数据模型：一条请求在 SW 里变成什么](02-sw-data-model.md) | 理论 | Trace/Segment/Span；refs；采样位置 | K14①② |
| 03 | [Agent 无侵入原理](03-agent-enhancement.md) | 原理 | 字节码增强/插件机制/版本强绑定 | K14①③（agent 独立仓口径） |
| 04 | [接入工程：三坑修复叙事](04-onboarding-pitfalls.md) | 生产 | javaagent 挂载/插件适配/降噪边界 | K14③⑤陷阱 |
| 05 | [采样与开销：实测口径](05-sampling-overhead.md) | 生产 | 采样参数/5.4% 实测/口径纪律 | K14④ |
| 06 | [OAP 架构与告警](06-oap-alarm.md) | 原理 | 接收/分析/存储/Alarm→webhook | K14发散+源码 |
| 07 | [xhs 生产对照：运行态与实证](07-xhs-production.md) | 生产 | 栈全景/span 实证/双 traceId 答辩 | K14②⑥ |
| 08 | [面试收口：五段式与陷阱](08-interview-wrapup.md) | 收口 | 五段式；陷阱 8；终检 | K14 |

## 二、学习路径

```text
全景：01 → 02
原理：03
工程：04 → 05
服务端：06
对照：07
收口：08
```

## 三、前置要求

- Tracing 全系列（01 双 ID 铺垫/05 双号/07 采样——本系列逐一兑现）。
- ES 系列 04（SW 存储在 ES 上，独立集群）。
- RocketMQ 05（MQ 串链与 SW 插件覆盖的关系）。

## 四、掌握分级

- **必须掌握**：四组件架构；插件版本强绑定（javax→jakarta 坑）；"组件在跑≠有数据"；双 traceId 分工；采样与开销口径。
- **理解思想**：字节码增强 vs 手动埋点；专用 ES 隔离；异步上报的故障语义（OAP 挂=观测盲区非业务故障）。
- **知道即可**：OAL 脚本/告警规则语法；BanyanDB；多语言 agent。

## 五、边界

- OTel/Zipkin/Jaeger 横向对比 → 07 一句（Tracing 01 已给三路线）。
- UI 使用/告警规则调参 → 运维侧翻篇。
- 源码逐行（agent 增强器内部）→ 10.4.0 语料只取骨架。
