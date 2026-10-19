---
title: "07-xhs生产对照：运行态与实证"
series: "SkyWalking 可观测系列"
previous: "06-oap-alarm.md"
next: "08-面试收口.md（待写）"
lab_refs:
  - "docs/中间件拷打/K14-SkyWalking.md（②栈与实证+⑥话术+真实证据）"
---

# 07-xhs生产对照：运行态与实证

## §0 它干了什么（例子先行）

xhs 的 SkyWalking 运行态一张脸：

```
OAP 9.7.0（REST 12800 / gRPC 11800）+ UI（8080）+ 专用 ES 8.12.2（19201，与业务 ES 19200 隔离）
Agent 9.7.0（落地路径 `/data/workspace/skywalking-agent-9.7.0/`，仓库外——升级=换目录的前提）：release 脚本自动挂 -javaagent
  -Dskywalking.agent.service_name={模块}、logging.dir=/tmp/sw-logs/{模块}
审计口径：15/15 服务注册；15 组日志目录互不覆盖
```

- **它干了什么**：把 03/04/05 的机制、工程、采样**装进一套可审计的运行态**。
- **本篇回答**：怎么证明"真的在工作"（不是装了就算）？实证数字有哪几层？答辩口径怎么收？

## §1 掌握卡（3 条）

1. **"有数据"的审计三件**（K14②"不是'装了'而是'有数据'"）：**注册数**（15/15 服务）/ **链路数**（gateway→home 一条 trace）/ **依赖拓扑**（peers 被插件采集：MySQL 3306/3307、Redis 6379/6380/26379、ES 19200）。
2. **span 实证三层**：登录刷首页 **29 spans**（初版）→ 调优后 gateway→home **53 spans**（含 GatewayFilter）→ cart 单日 **1815 段**（吞吐量级证明）。
3. **每模块三隔离**（04 回顾的运行态落点）：`service_name` 分模块 / `logging.dir` 分目录 / 专用 ES 分集群——**名字、日志、存储三层不串**。

## §2 实证与话术的对齐（K14⑥ 拆解）

话术里每个数字都有出处，答辩时可反向给证据：

| 话术句 | 出处 |
|---|---|
| "15 个服务全注册" | agent-enable 报告（审计） |
| "53 span，CROSS_PROCESS/CROSS_THREAD，Lettuce 异步也串上" | agent-enable 报告（链路实测） |
| "开销 5.4%（7,439 vs 7,863）" | overhead 报告（05 篇） |
| "9.1.0 三个坑" | issue 报告（03/04 篇） |

**三份报告**（仓库内）：`skywalking-agent-enable-20260918.md` / `skywalking-overhead-20260918.md` / `skywalking-issue.md`——**结论跟着报告走**，不凭印象。

## §3 双 traceId 答辩收口（01 §3 的运行态版）

追问到最深处的标准收尾（K14⑤1 + ②表）：

- "为什么还要业务 traceId？" → 无 agent 依赖、能进消息/审计/DB 字段；APM 号出不了 APM 存储——**互补而非替代**。
- "两个号对不上？" → 正常，两套发号；需要关联时走 SW correlation（Tracing 06 已讲 `putCorrelation("traceId",…)`）。
- xhs 口径：**以业务 traceId 为主、APM 增强**。

## §4 边界（一句话翻篇）

- 1815 段/日的具体统计口径 → agent-enable 报告用时查。
- component-libraries.yml 组件映射维护 → 运维侧。
- 多语言 agent（Python/Go）→ K14 发散地图一句；xhs 仅 Java 栈。
- UI 看板/拓扑图使用 → 翻篇。

## §5 Self-Test（3 题）

- Q1："装了 agent"和"有可观测"差哪三件审计？
- Q2：29→53 span 的变化说明什么？（调优含 GatewayFilter）
- Q3：双 traceId 追问的三层递进收口怎么说？

## §6 衔接

- 生产线收口；下篇 08 面试收口（K14 五段式回链 + 陷阱 8 + 终检）。
