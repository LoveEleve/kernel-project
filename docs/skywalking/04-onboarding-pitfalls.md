---
title: "04-接入工程：三坑修复叙事"
series: "SkyWalking 可观测系列"
previous: "03-agent-enhancement.md"
next: "05-采样与开销.md（待写）"
lab_refs:
  - "docs/中间件拷打/K14-SkyWalking.md（③三段演进+⑤追问4/8+陷阱2~6）"
source_refs:
  - "xhs scripts/install-skywalking-agent.sh（幂等安装，默认 9.7.0——已核）"
---

# 04-接入工程：三坑修复叙事

## §0 它干了什么（例子先行）

"组件在跑但没数据"的一次真实工程事故（K14③坑2）：

```
现象：OAP/UI/ES 全在运行，UI 里却几乎没有业务 trace
排查：release-service.sh 的启动参数里根本没有 -javaagent；
     agent 包缺失；restart-service.sh 还指向云上旧 collector（上报接收端，这里指 OAP 的 gRPC 口；21.130.247.89）
修复（2026-09-18）：install 脚本落地 agent 9.7.0（TUNA 源）
     → release/restart 自动挂载（SW_AGENT_DIR/COLLECTOR/DISABLED/IGNORE_SUFFIX/SAMPLE）
     → 15/15 服务注册
```

- **它干了什么**：APM 接入是**发布链路工程**——脚本、参数、目录、审计，一个都不能少。
- **本篇回答**：挂载链怎么治理？插件目录怎么适配 Boot 3？哪些降噪不生效？

## §1 掌握卡（4 条）

1. **"组件在跑≠有数据"是头号工程坑**（陷阱2）：javaagent 未挂/collector 指错都会造成"假正常"——治理手段=**发布脚本参数化 + 注册数审计**（15/15 就是审计出来的口径）。
2. **挂载五参数**（release 脚本自动带）：`SW_AGENT_DIR`（版本目录参数化——升级=换目录）/ `SW_COLLECTOR`（指向本机 OAP，不指云）/ `SW_AGENT_DISABLED`（总开关）/ `SW_AGENT_IGNORE_SUFFIX`（降噪）/ `SW_AGENT_SAMPLE`（采样）。
3. **Boot 3 插件适配清单**（坑3 实操）：移出 plugins/ 的 `springmvc-annotation-3/4/5.x`（与 6.x 冲突）；移入 `6.x + webflux-6.x + gateway-4.x`（原 optional）；清 `._*` 元数据（macOS 打包残留，AgentClassLoader 报"jar can't be resolved"）；`SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins`；**logging.dir 每服务隔离**（否则 agent 日志互相覆盖，陷阱6）。
4. **降噪的已知边界**（坑4/陷阱5）：`ignore_suffix` 对 WebFlux/Gateway 插件**不生效**——gateway 仍记 `/actuator/prometheus`（约 4 条/分钟）；9.7 无 `trace_ignore_path`——升级或接受（量小，主动披露）。

## §2 升级怎么做（追问8）

```
换 SW_AGENT_DIR 指向新版本目录（脚本参数化，VER=9.7.0 默认）
→ 插件清单复检（对照框架大版本——03 的版本绑定课）
→ 全量重启 → 注册数审计
```

## §3 装机脚本的形状（install-skywalking-agent.sh 已核）

幂等（已装则跳过）+ TUNA 清华源下载 + 版本参数（`VER=${1:-9.7.0}`）——**安装脚本本身也要可重复执行**，这是"事故 2"之后的工程纪律。

## §4 陷阱速查（K14 8 条中工程向 5 条）

| # | 陷阱 | 一句话 |
|---|---|---|
| 2 | 组件在跑≠有数据 | 脚本参数化+注册审计 |
| 3 | 插件冲突 | 3/4/5.x 移出、6.x 移入 |
| 4 | `._*` 元数据 | 安装脚本清理 |
| 5 | 降噪失效（WebFlux/GW） | 接受/升级 |
| 6 | 日志目录不隔离 | logging.dir per module |

（1 插件版本不匹配→03；7 采样开销→05；8 OAP 单点→06。）

## §5 边界（一句话翻篇）

- 三份报告原文（agent-enable/overhead/issue）→ 仓库 `skywalking-*.md` 用时查。
- K8s/Docker 挂载形态 → 部署侧翻篇。
- OAP 侧部署（docker-compose 等）→ 06。

## §6 Self-Test（4 题）

- Q1："组件在跑没数据"的三处根因？治理两件套？
- Q2：Boot 3 插件适配的移出/移入清单；`._*` 不清会怎样？
- Q3：挂载五参数各管什么？升级为什么只换 DIR？
- Q4：ignore_suffix 对 gateway 为什么无效？量多大？

## §7 衔接

- 工程线前半；下篇 05 采样与开销：`SW_AGENT_SAMPLE` 怎么定 + 5.4% 实测的口径纪律。
