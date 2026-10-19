# K14 — 组件深度拷打：SkyWalking（APM）

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：字节码增强（无侵入）、span/refs（CROSS_PROCESS/CROSS_THREAD）、插件版本绑定、APM vs 业务 traceId、开销实测
> 依据：xhs-project 运行态（OAP/Agent 9.7.0）+ 51-SkyWalking深度拷打 + agent-enable/overhead 报告 + skywalking-issue + 源码语料 10.4.0

---

## 问题

链路追踪怎么做的？和你们的业务 traceId 什么关系？Agent 怎么无侵入？踩过什么坑？开销多大？

---

## 面试可讲版（五段式）

### ① 原理层

```
Agent（字节码增强，无侵入）→ gRPC 上报 → OAP（接收/分析/存储）→ UI（查询）
存储：ES/BanyanDB（本项目专用 ES，与业务 ES 物理隔离）
```

| 概念 | 说明 |
|---|---|
| **数据模型** | Trace（一次请求的 span 树）+ **refs**（`CROSS_PROCESS` 跨进程 / `CROSS_THREAD` 跨线程）——"能否把异步/跨服务串起来"是 APM 的价值分水岭 |
| **插件机制** | 按框架自动增强（SpringMVC/Feign/WebFlux/Gateway/JDBC/Redis/Lettuce…）——**插件与框架大版本强绑定** |
| **采样** | 默认 `sample_n_per_3_secs=1`（每 3 秒 1 条）；全采样 `SW_AGENT_SAMPLE=-1` |
| **开销** | 字节码增强 + 异步上报队列——有 CPU/内存成本，生产要定采样率（见 ④ 实测） |

### ② 项目用法（栈与实证）

```
OAP 9.7.0（REST **12800** / gRPC 11800）+ UI（8080）+ 专用 ES 8.12.2（19201，与业务 ES 19200 隔离）
Agent 9.7.0：-javaagent 接入，-Dskywalking.agent.service_name={模块}、logging.dir=/tmp/sw-logs/{模块}
```

**真实链路实证**（不是"装了"而是"有数据"）：
- 15/15 服务注册；依赖 peers（MySQL 3306/3307、Redis 6379/6380/26379、ES 19200）被插件采集
- 真实登录 → gateway `/api/home/feed`：**29 spans**（`服务=[gateway, home]`，refs 含 `CROSS_PROCESS` + `CROSS_THREAD`，Lettuce 异步 Redis 调用也串上）
- 调优后 gateway→home 复验 **53 spans**（含 GatewayFilter）；cart 单日 1815 段

**与业务 traceId 的分工（答辩关键）**：
| | APM traceId | 业务 `X-Trace-Id` |
|---|---|---|
| 归属 | SkyWalking（span 树/性能归因） | 自研（TraceContextHolder/MqTraceHelper） |
| 用途 | 性能分析/链路可视化 | **日志/MQ/审计/DB 关联键**（16 题） |
| 依赖 | 需要 agent | 无 agent 依赖、可控 |
| 边界 | 出不了 APM 存储；自定义线程池/MQ 需手动增强 | 靠业务透传纪律 |

**本项目以业务 traceId 为主、APM 作为增强**——两套 ID 各管一摊，不混用。

### ③ Agent 接入的坑（实测修复记录，三段真实演进）

**坑 1：9.1.0 缺 SpringMVC 6.x 插件（javax → jakarta）**
```
现象：UI 只有 Redisson/SENTINEL 和 /actuator/prometheus 零散 trace，业务 HTTP 链路全无
根因：agent 9.1.0 只有 apm-springmvc-annotation-3/4/5.x（javax.servlet），
      项目是 Spring Boot 3（jakarta.servlet）→ 插件未激活（agent 日志有 WARN 证据）
"假正常"陷阱：Redisson 插件不依赖 servlet 能采到 → 给人"agent 工作正常"的错觉
```

**坑 2："组件在跑但没数据"（工程事故）**
```
现象：OAP/UI/ES 一直在运行，但 release-service.sh 参数不含 -javaagent、agent 包缺失、
      restart-service.sh 还指向云 collector（21.130.247.89）
修复（2026-09-18）：agent 9.7.0 落地（install-skywalking-agent.sh，TUNA 源）；
      release/restart 脚本自动挂载（SW_AGENT_DIR/SW_COLLECTOR/SW_AGENT_DISABLED/
      SW_AGENT_IGNORE_SUFFIX/SW_AGENT_SAMPLE）→ 15/15 服务注册
```

**坑 3：插件版本适配（Spring Boot 3 / WebFlux）**
```
移出 plugins/：apm-springmvc-annotation-3.x/4.x/5.x（与 6.x 冲突）
移入 plugins/：apm-springmvc-annotation-6.x、apm-spring-webflux-6.x、apm-spring-cloud-gateway-4.x（原 optional-plugins）
清理 macOS 元数据 ._*.jar（否则 AgentClassLoader 报"jar file can't be resolved"）
SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins（不配则部分链路断）
日志目录隔离：logging.dir 每服务分开（否则 agent 日志互相覆盖）
```

**坑 4（已知边界）**：`ignore_suffix`（/actuator/health 等降噪）对 **WebFlux/Gateway 插件不生效**——gateway 仍记 Prometheus 抓取 span（约 4 条/分钟）；9.7 无 `trace_ignore_path` 配置项，升级或接受噪音（量小）。

### ④ 开销与采样（实测口径）

```
方法：同服务（product）同压测命令（wrk -t4 -c32 -d30s），agent ON/OFF 各两轮交替
结果：ON₁ 7,209 / ON₂ 7,668 vs OFF₁ 7,774 / OFF₂ 7,952 RPS
结论：吞吐开销 ≈ 5.4%（OFF 均值 7,863 vs ON 均值 7,439）；P50 +0.41ms（+17.6%）
口径纪律：历史基线 4,871 RPS 是"无 agent"口径（另一套方法与缓存状态，不可直接比）
```

### ⑤ 拷打追问（面试官视角）

1. **"为什么还要业务 traceId？"** 无 agent 依赖、能进消息/审计/DB 字段；APM traceId 出不了 APM 存储——互补而非替代。
2. **"Agent 怎么无侵入？"** 类加载时字节码插桩（插件），不改业务代码；代价 = 版本敏感（插件与框架大版本强绑定）。
3. **"跨 MQ/线程池能串吗？"** 常见框架有插件；自定义线程池/MQ 需手动增强——我们业务侧用 traceId 兜住。
4. **"采样怎么定？"** 按流量与存储预算：默认 1/3s；压测/全链路演示 `SW_AGENT_SAMPLE=-1` 注意开销；可 `SW_AGENT_DISABLED=1` 关闭。
5. **"OAP 挂了会怎样？"** Agent 异步上报+本地缓存，**业务不受影响**，只是 trace 丢失——OAP 无 HA 时是观测盲区。
6. **"为什么 APM 存储独立 ES？"** 隔离数据量与索引生命周期（19201 vs 业务 19200），防 APM 写入冲击业务搜索。
7. **"9.1.0 那个坑怎么排查的？"** 现象（零散 trace）→ 插件目录比对（无 6.x）→ agent 日志 WARN（Witness class javax.servlet...does not exist）→ 升级+插件适配。
8. **"升级 Agent 怎么做？"** 换 `SW_AGENT_DIR` 指向新版本目录（脚本参数化）+ 插件清单复检 + 全量重启。

### ⑥ 话术

> "SkyWalking 我们 OAP 9.7 + UI + 独立 ES 19201，Agent 9.7 通过 javaagent 接入、release 脚本自动挂载。实证：15 个服务全注册，gateway→home 一条 trace 53 span，CROSS_PROCESS/CROSS_THREAD 完整，Lettuce 异步 Redis 也串上。接入踩过三坑：9.1.0 缺 SpringMVC 6.x 插件（jakarta）导致只有 Redisson 零散 trace；'组件在跑但没数据'——release 没挂 javaagent、restart 还指向云 collector；插件版本适配（3/4/5.x 移出、6.x/webflux/gateway 移入 + 清 ._*）。开销实测 5.4%（7,439 vs 7,863 RPS），所以性能基线要标'无 agent'口径。它和业务 traceId 是互补关系。"

---

## 生产陷阱（SkyWalking 8 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **插件版本不匹配** | javax/jakarta 时代差 → 业务链路全无（零散 trace 假正常）——插件清单与框架大版本对齐 |
| 2 | **"组件在跑≠有数据"** | javaagent 未挂/collector 指错——发布脚本参数化 + 注册数审计 |
| 3 | **插件冲突** | 3/4/5.x 与 6.x 共存——移出旧版、移入新版 |
| 4 | **._* 元数据文件** | macOS 打包残留 → AgentClassLoader 报错——安装脚本清理 |
| 5 | **降噪失效（WebFlux/Gateway）** | ignore_suffix 不生效 → actuator span 噪音——接受/升级 agent |
| 6 | **日志目录不隔离** | 多服务 agent 日志互相覆盖——logging.dir per module |
| 7 | **采样开销误判** | 压测全采样 → 数字失真——基线标注口径 + SW_AGENT_DISABLED |
| 8 | **OAP 单点** | 挂了丢 trace（业务无感但观测盲区）——HA/自愈告警 |

---

## 发散追问地图（横向）

- **APM 原理**：字节码增强（ByteBuddy/JVM TI）、span 模型、上下文传播（W3C traceparent/B3）
- **生态对比**：SkyWalking vs Zipkin/Jaeger vs OTel + Collector vs 商业 APM
- **存储**：OAP 存储选型（ES/BanyanDB）、TTL/采样、独立集群
- **告警**：OAP Alarm → webhook → Alertmanager（本项目链路见 K15）
- **工程**：Agent 升级策略、插件裁剪、开销评估方法、多语言（Python/Go agent）
- **与日志/Metrics 联动**：traceId 关联日志（ELK）/ Exemplars（Prometheus）

---

## 面试官评分点

**高级开发级**：APM 架构/span/refs；与业务 traceId 的分工；无侵入原理。
**架构师加分**：插件与框架大版本绑定的实证坑（javax→jakarta）；"组件在跑但没数据"的完整修复叙事（发布链路治理）；专用 ES 隔离的容量考虑；**开销实测与口径纪律**；两套追踪的取舍。
**危险信号**：APM 与业务 traceId 混为一谈；不知道插件版本冲突；宣称全自动无需人工增强；拿"装了 agent"当"有可观测性"。

---

## 本项目真实证据

**xhs-project**：
- 运行态：OAP 9.7.0（REST 12800 / gRPC 11800）+ UI（8080）+ 专用 ES 8.12.2（19201）；Agent 9.7.0（`/data/workspace/skywalking-agent-9.7.0/`，仓库外）
- 报告：`skywalking-agent-enable-20260918.md`（插件适配清单/15 服务注册/29→53 span/降噪边界）、`skywalking-overhead-20260918.md`（5.4% 开销，ON/OFF 两轮）、`skywalking-issue.md`（9.1.0 坑）
- 脚本：`install-skywalking-agent.sh`（幂等/TUNA）、`release-service.sh`（自动挂载）、`restart-service.sh`（collector 修正）
- 业务 traceId：`TraceContextHolder`/`MqTraceHelper`（自研，与 APM 互补）

**源码语料**：`code/spring/skywalking` **10.4.0**（新版参考；xhs 部署 9.7.0，差异按部署版本口径）。

**课程对照**：xhs 51-SkyWalking深度拷打。

---

## 版本与来源

SkyWalking **OAP/Agent 9.7.0**（xhs 部署）+ 源码语料 **10.4.0**；官方文档；xhs 接入/开销/issue 报告。

## 真实性说明

版本/端口/插件清单/注册数/span 实证/开销数字/采样参数均为报告与运行态事实；"9.6.0→9.7.0"演进与历史口径（22 span 旧环境）如实标注；WebFlux 降噪边界主动披露。
