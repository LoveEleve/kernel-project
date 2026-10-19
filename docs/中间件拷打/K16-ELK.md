# K16 — 组件深度拷打：ELK 日志链路（Filebeat / Logstash / Kibana）

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：filestream、ndjson、grok 提取、日索引、保留双口径、可检索 traceId、脱敏
> 依据：xhs-project 配置实证（filebeat/logstash/cleanup/check/ILM）+ 53-ELK日志链路深度拷打 + 运行态

---

## 问题

日志链路怎么搭的？为什么不用 Logstash 直采？日志怎么保留？出过什么问题？

---

## 面试可讲版（五段式）

### ① 原理层

```
链路分工：Filebeat（轻量采集，Go，低资源）→ 网络传输 → Logstash（重加工：grok/date/mutate，JVM）
         → ES（存储检索）→ Kibana（可视化）——采集与加工分离，采端不阻塞业务
```

| 概念 | 要点 |
|---|---|
| **采集模式** | `filestream`（文件指纹+游标，替代老 log input）；JSON 日志用 **ndjson 解析**（一行一条，免 multiline 硬调） |
| **处理链** | input(json_lines) → filter(grok 提服务名 + date 规范化 + mutate gsub 脱敏) → output(按天索引)；字段漂移靠 `overwrite_keys/add_error_key` |
| **保留** | ILM（hot/warm/cold/delete）标准做法；没有 ILM 靠外部 cron 删索引兜底 |
| **背压** | ES 慢 → Logstash 队列 → Filebeat 重试；harvester 限速防打爆磁盘/网络 |

### ② 项目用法（配置实配）

**Filebeat（8.19.19，deploy 版为运行口径）**：
```yaml
type: filestream → paths: /logs/*.json → parsers.ndjson(overwrite_keys/add_error_key)
processors: add_fields log_source=filebeat → output.logstash: 127.0.0.1:15045
（容器 /logs = 宿主 /data2/logs，:ro 挂载）
```

**Logstash（8.19.19）**：
```
input：tcp 15044（json_lines）+ beats 15045
filter：grok 从 [log][file][path] 提取服务名（/logs/(?<service>[^/]+)\.json）
       + date ISO8601 → @timestamp
       + mutate gsub 脱敏：ticket|access_token|refresh_token|token|password|secret|signature|authorization|captcha → ***
output：ES 127.0.0.1:19200；index=myxhs-logs-%{+YYYY.MM.dd}（按天，便于删除与查询裁剪）
```

**存储与查询**：
- ES `my-xhs-elasticsearch` **8.19.19** @19200（cpus 6，A2 调优）；`myxhs-logs-*` 压测日单索引 **650 万条 / 1.8GB**（副本 0）
- Kibana `my-xhs-kibana` **8.19.19** @**15601**（运行态已部署；XPACK 加密 key 固定防重启 session 失效 P-D16）
- 应用侧：Logback JSON（level/logger/**traceId**/stack_trace）→ **16 个业务服务 100MB/文件、30 天、3GB cap**；**xhs-ai 模块 100MB/7 天/2GB**（例外）；my-xhs-common/my-xhs-ai-app 为简化 console 配置（无文件滚动）

**保留三口径（诚实分层）**：
```
① 应用本地文件：log-cleanup.sh 删 3 天前（/data2/logs，默认 KEEP_DAYS=3）——运行口径
② ES 索引：log-cleanup.sh 删 7 天前索引（默认 ES_KEEP_DAYS=7）——运行口径
③ ILM 设计：apply-ilm.sh（P-D14）policy hot→delete 30d + template（replicas 0/shard 1）——设计口径
```

### ③ 坑与修复（全是真实治理）

**坑 1：管道漂移（运行时无 filter）**
```
运行时原为 compose 内联 `-e` 管道（无 filter）→ 仓库 logstash.conf 没真正生效
修复：挂载仓库 logstash.conf（grok/date 真正生效）+ 补 mutate gsub 凭据脱敏
验证：ES 实测脱敏、明文 0 命中（网关访问日志 query 的 SSE ticket 也同步脱敏）
```

**坑 2：yellow 索引（模板没覆盖的写入路径）**
```
某日索引 replicas=1 在单节点无法分配 → 集群 yellow + 告警
修复：修正 + es-log-index-check.sh 巡检（每天 5:00，replicas≠0 自动改 0）
教训："模板/ILM 没覆盖到的写入路径"是长期风险
```

**坑 3：保留两套并存（ILM 从未 apply）**
```
ES ILM 此前 managed=False（从未 apply）→ 设计 30d 但实际无人执行
修复：apply-ilm.sh 建 policy/模板并挂存量索引；cron 因进程重启窗口未跑 → 手动清 2 个老索引(1.8G)+45 文件后自动化
回答纪律：讲清"设计口径 vs 运行口径"——不统一就不知道日志到底留多久
```

**坑 4：时间戳/时区**——date filter 与 @timestamp 时区不一致会让按天索引错位（统一 UTC/Asia 口径）。
**坑 5：多行堆栈**——JSON 日志把 stack_trace 作为字段最省事；纯文本才需要 multiline（容易采歪）。

**配置漂移观察（诚实披露）**：仓库存在两版 filebeat.yml——顶层 `config/filebeat/`（docker 采集旧版）vs `deploy/docker/.../config/filebeat/`（filestream 版）；**compose 挂载 filestream 版为运行口径**。

### ④ 兜底

- 索引巡检 cron（副本） + 清理 cron（保留）——脚本化治理
- `traceId` 是 JSON 字段 → ES 直接按 traceId 检索全链路日志（16 题真实问题单）
- 应用本地日志 3 天兜底：ES 链路故障时排查不中断

### ⑤ 拷打追问（面试官视角）

1. **"为什么 Filebeat + Logstash 两层？"** 采端轻量（业务机低开销）+ 加工可扩展（grok/date/改字段）；Filebeat 直出 ES 也行，复杂加工放 Logstash 更稳。
2. **"为什么不用 Loki？"** Loki 按 label 索引省存储，但本项目已按 ES 技能栈建设（ES 同时承载搜索业务）；Loki 适合"只查不聚合"，选型不是不能换。
3. **"日志怎么不丢？"** Filebeat 游标 + at-least-once（可能重复，id 去重）；Logstash 队列/重试；本地文件最终兜底。
4. **"为什么按天索引？"** 删除/rollover 简单、查询能裁剪日期；缺点是跨天查询要 index pattern。
5. **"高流量怎么优化？"** 采端过滤（级别/字段）、采样（debug 不落 ES）、Logstash 横向扩、ES 冷热分层。
6. **"日志格式怎么定？"** JSON 结构化 + 必带 traceId/服务名/级别/时间戳；敏感信息应用侧脱敏。
7. **"保留到底几天？"** 三口径分层答：文件 3 天（cron）/ ES 索引 7 天（cron）/ ILM 设计 30 天（policy 已建）——**运行口径与设计口径分开讲**。
8. **"脱敏在哪做？"** Logstash mutate gsub（兜底）+ 应用侧（源头）双保险；网关 query 也脱敏。

### ⑥ 话术

> "日志链路 Filebeat 采、Logstash 加工、ES 存、Kibana 查：Filebeat filestream 采 /logs/*.json、ndjson 解析，Logstash 从文件路径 grok 出服务名按天写索引，还做了凭据脱敏（ticket/token/password → ***，实测明文零命中）。应用日志是结构化 JSON、traceId 直接可检索，本地留 3 天兜底。保留我会分层说：文件 3 天、ES 索引 7 天（都是清理脚本），ILM 30 天是设计口径——今天还发现 ILM 从未 apply、一个 replicas=1 的漏网索引把集群搞 yellow，修完加了巡检 cron。压测日单索引 650 万条 1.8G，日志治理必须应用本地和 ES 两头卡。"

---

## 生产陷阱（ELK 8 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **管道漂移** | compose 内联管道无 filter——配置挂载仓库文件 + 验证生效 |
| 2 | **模板未覆盖的写入路径** | replicas=1 漏网索引 → yellow——巡检 cron 自动修正 |
| 3 | **ILM 从未 apply** | managed=False（设计≠运行）——apply + 存量迁移 + 口径分开讲 |
| 4 | **保留不统一** | 文件/索引/设计三口径混杂——统一巡检 + 文档化 |
| 5 | **凭据明文入 ES** | 只在应用脱敏漏了网关 query——双保险（应用+Logstash） |
| 6 | **时区错位** | date filter 与 @timestamp 不一致——统一口径 |
| 7 | **采集打爆磁盘** | 无上限/无限速——100MB×cap + 采端过滤 |
| 8 | **多行堆栈采歪** | 纯文本 multiline 硬调——优先 JSON 字段 |

---

## 发散追问地图（横向）

- **采集**：Filebeat/Fluent Bit/Vector 对比、filestream、backpressure
- **加工**：grok/date/mutate、ECS 字段规范、脱敏策略
- **存储**：索引设计、ILM/rollover、冷热分层、快照
- **查询**：KQL/Lucene、traceId 关联（与 K14 SkyWalking 联动）、看板
- **替代**：Loki/ClickHouse/SLS 对比
- **可观测三支柱**：Metrics（K15）↔ Logs（本篇）↔ Traces（K14）

---

## 面试官评分点

**高级开发级**：链路分工、采集/加工/索引/保留四段；traceId 检索。
**架构师加分**：**保留三口径的诚实表达**；"模板/ILM 未覆盖路径"的巡检设计；管道漂移修复（配置挂载 vs 内联）；两级保留容量治理；脱敏双保险；与 APM traceId 的日志联动。
**危险信号**：采集直写 ES 无背压；日志无保留上限；把 ILM 当已启用；堆栈全靠 multiline 硬调；保留天数说不清哪套口径。

---

## 本项目真实证据

**xhs-project**：
- Filebeat：`deploy/docker/my-xhs-deploy-zip/config/filebeat/filebeat.yml`（filestream+ndjson+log_source，compose 挂载；8.19.19）；顶层 `config/filebeat/filebeat.yml` 为 docker 旧版（漂移观察）
- Logstash：`config/logstash/logstash.conf`（json_lines/grok/date/gsub 脱敏/按天索引；8.19.19）
- ES/Kibana：`my-xhs-elasticsearch` 8.19.19 @19200（cpus 6）；`my-xhs-kibana` 8.19.19 @15601（运行态已部署）；SkyWalking 专用 ES 8.12.2 @19201
- 应用：16 业务服务 logback JSON（100MB/30 天/3GB；`/logs/${APP_NAME}.json`）；xhs-ai=100MB/7 天/2GB；common/ai-app=console 简化配置
- 脚本：`log-cleanup.sh`（文件 3 天/索引 7 天）、`es-log-index-check.sh`（副本巡检）、`config/deploy-cloud/apply-ilm.sh`（ILM 30d policy + 模板 replicas0/shard1）
- 运行态：`myxhs-logs-*` 单日 6.5M docs/1.8GB；yellow 修复与管道漂移修复记录（42 题）

**课程对照**：xhs 53-ELK日志链路深度拷打 + 16 题（traceId 检索）+ 42 题（yellow）。

---

## 版本与来源

Filebeat/Logstash/Kibana **8.19.19**、ES **8.19.19**（19200）/ **8.12.2**（19201，SkyWalking 专用）（xhs 部署）；官方文档；xhs 配置与脚本。

## 真实性说明

配置/脚本/保留参数/镜像版本/端口均为仓库与运行态事实；**修正两处旧文档口径**：logback 实为 30 天/3GB（53 章"7 天/2GB"过期）、Kibana 已部署 15601（skywalking-issue"未部署"为 2026-07 旧状态）；双版 filebeat 漂移、ILM 未启用等主动披露。
