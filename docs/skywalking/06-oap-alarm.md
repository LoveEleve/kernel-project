---
title: "06-OAP架构与告警"
series: "SkyWalking 可观测系列"
previous: "05-sampling-overhead.md"
next: "07-xhs生产对照.md（待写）"
lab_refs:
  - "docs/中间件拷打/K14-SkyWalking.md（①OAP 职责+发散追问地图）"
source_refs:
  - "xhs config/skywalking/（20 个文件 ls 实证）：alarm-settings.yml 规则原文 / oal 目录 7 个 / endpoint-name-grouping.yml"
  - "源码 10.4.0 oap-server 模块（analyzer/oal-grammar/oal-rt/exporter/mqe-grammar——ls 已核）"
---

# 06-OAP架构与告警

## §0 它干了什么（例子先行）

agent 发来的 span 在 OAP 里走完四步，然后变出告警：

```
接收（gRPC 11800）→ 分析（按 OAL 脚本算指标）→ 存储（专用 ES 19201）→ 查询（REST 12800 / UI 8080）
                                          ↓
                          alarm 规则命中：sum(service_resp_time > 1000) >= 3（10s 窗口）
                                          ↓
                          webhook（xhs 配置里预留的 127.0.0.1/notify/ 入口）
```

- **它干了什么**：OAP=服务端四件套（收/算/存/查）+ 告警引擎。
- **本篇回答**：OAP 四职责怎么分工？OAL 是什么？告警规则怎么写？xhs 这套 20 个配置文件各管什么？

## §1 掌握卡（4 条）

1. **OAP 四职责**：接收（gRPC 11800）/ 分析（**OAL 脚本**定义指标口径）/ 存储（ES 或 BanyanDB）/ 查询（REST 12800 → UI 8080）——端口栈与 01/07 呼应。
2. **OAL = 指标 DSL**：oap-server 源码里有 `oal-grammar`/`oal-rt` 模块（10.4.0 ls 实证）；xhs 的 `oal/` 目录 7 个脚本（core/java-agent/mesh…）决定"哪些指标算出来"——**改指标口径=改 OAL 编译产物**，不是查库。
3. **告警 = MQE 规则**（xhs `alarm-settings.yml` 原文）：`sum(service_resp_time > 1000) >= 3` + `period: 10`——10 秒窗口内"响应时间超 1s 的求和 ≥3"触发；**webhook 通道预留**（`127.0.0.1/notify/`，注释态——接通知的口子在那）。
4. **高基数防护**：`endpoint-name-grouping.yml` 把海量 endpoint 名归组——URL 带 ID 的接口不归组会把指标维度打爆（与 ES 02 "keyword 高基数" 同一类问题）。

## §2 xhs OAP 配置地图（20 文件速览）

| 文件族 | 管什么 | 对应篇 |
|---|---|---|
| `application.yml` | OAP 本体（存储/端口/集群） | 本篇 |
| `oal/` 7 脚本 | 指标口径 | §1-2 |
| `alarm-settings.yml` | 告警规则+webhook | §1-3 |
| `trace-sampling-policy-settings.yml` | **OAP 侧采样 rate/duration** | Tracing 07 兑现 |
| `endpoint-name-grouping.yml` / `service-apdex-threshold.yml` | 维度防护/服务质量 | 本篇 |
| `log/` `lal/` `log-mal-rules` | 日志分析与字段提取 | 翻篇 |
| `otel-rules/` `meter-analyzer-config` | OTel/自定义指标接入 | 翻篇 |
| `component-libraries.yml` | 组件名映射（Tracing 05 见过这文件） | 07 |

## §3 存储与告警的两个边界

- **独立 ES**（19201，K14⑤6）：隔离 APM 数据量与索引生命周期，防 APM 写入冲击业务搜索（ES 系列 04 的"写入可见性/容量"话题在 APM 侧的镜像）。
- **告警边界**（K14 发散+陷阱8）：OAP 单点时告警也瞎；webhook 未启用=**规则命中只落 UI**——xhs 现状是"口子预留、通道未接"（主动披露类缺口，08 收口）。

## §4 边界（一句话翻篇）

- OAL/MQE 语法细节 → 翻篇（用时查官方文档）。
- BanyanDB → 知道名字即可（02 已注）。
- 日志分析（LAL）/OTel 指标规则 → xhs 配置在但本系列不展开。
- K15→告警链路外部化（Alarm→webhook→Alertmanager）→ `中间件拷打/K15-Prometheus.md`（K14 发散原话"见 K15"，该篇已存在）。
- 与日志/Metrics 联动（traceId 关联 ELK / Exemplars）→ K14 发散指路；日志侧本系列只在 07 提一句。

## §5 Self-Test（4 题）

- Q1：OAP 四职责+四端口？
- Q2：OAL 决定什么？改指标口径动哪里？
- Q3：xhs 那条告警规则在说什么？webhook 现状？
- Q4：endpoint grouping 防什么？（高基数维度打爆）

## §6 衔接

- 服务端收口；下篇 07 xhs 生产对照：运行态全景 + span 实证 + 双 traceId 答辩口径。
