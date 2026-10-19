# K15 — 组件深度拷打：Prometheus / Grafana / Alertmanager

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：拉模型、TSDB、PromQL、告警链路（pending→firing）、Alertmanager 治理、通知黑洞、Watchdog
> 依据：xhs-project 运行态（Prometheus v2.48.1 配置 + 41 规则实测）+ 52-Prometheus监控栈深度拷打 + alerting-e2e 报告

---

## 问题

监控体系怎么搭的？PromQL 怎么用？告警怎么保证不丢不炸？Prometheus 单点怎么办？

---

## 面试可讲版（五段式）

### ① 原理层

```
拉模型 + 服务发现：Prometheus 按 scrape_config 周期抓 targets（static/consul/k8s）→ exporter /metrics
（对比 push：Pushgateway 适合短任务）
TSDB：时间序列 = 指标名 + labels；2h block 落盘 + compaction；retention 按时间/空间
告警链路：Prometheus 规则（for 持续时长）→ pending → firing → Alertmanager → 路由/分组/抑制/静默 → receiver
```

**PromQL 三组高频**：
| 用法 | 要点 |
|---|---|
| `rate()` | 只配 **counter**（处理重置/回绕）；`increase/irate` 区别 |
| `histogram_quantile(0.99, rate(...))` | P99 来自直方图桶——**桶设计要与 SLO 量级匹配**（否则插值误差大） |
| `by/without` | 聚合维度控制（高基数标签会打爆 TSDB） |

**Alertmanager 治理**：`group_wait`（首次等待）/`group_interval`/`repeat_interval`；**短命告警会被 group_wait 吞掉**；`send_resolved` 决定恢复通知。

### ② 项目用法（运行态实测）

```
Prometheus v2.48.1（19090，--web.enable-lifecycle 已开→热加载 POST /-/reload）
9 个 scrape job：my-xhs-services / prometheus / skywalking-oap / redis / elasticsearch / mysql / mysql-slave / canal / node
  my-xhs-services：15 服务 targets（:19000~19016，跳号 19005/19007；labels service=my-xhs-*），scrape_interval 10s
Alertmanager（19093）：receiver=default-webhook → 本地告警落地器 alert-sink（:19099，仓库外部署的 Python 单文件 service）
  → 落盘 /data2/logs/alerts.jsonl；send_resolved: true（恢复也通知）
  route：group_by [alertname, service] / group_wait 10s / group_interval 5m / repeat_interval 4h
exporter 矩阵：node-exporter / mysql-exporter ×2（主/从）/ redis-exporter / es-exporter
Grafana：datasource + 看板 provisioning（看板即代码，容器重建不丢）
VictoriaMetrics 容器（v1.93.12）：AI 诊断的指标查询后端；SLO 错误预算（30 天 ≈43m12s）+ Burn Ledger
```

**告警规则：9 组 / 41 条**（演进：31 → e2e 修复 +4 = 39 → 文档 40 → 当前 41）：
| 组 | 条数 | 组 | 条数 |
|---|---|---|---|
| application | 8 | mysql | 3 |
| business | 9 | rocketmq_dlq | 1 |
| core_golden_signals | 7 | node | 3 |
| mysql_conn | 6 | xxl_job_health | 3 |
| meta | 1 | | |

### ③ 坑与事故（全是真实治理）

**事故 1：通知黑洞（最经典）**
```
现象：Prometheus 31 条规则在跑，但 Alertmanager live receiver=default-noop（空）→ 所有告警被静默丢弃；
      仓库副本 webhook 指向占位地址（网关无该端点）——"告警 firing 了但没人收到"
修复（2026-09-17）：新增 alert-sink.service（Python 单文件 :19099）→ 落盘 alerts.jsonl + 可读日志；
      receiver 改 default-webhook；新增 4 条 MySQL 连接规则（31→39）
端到端验证：注入 SELECT SLEEP(600)×2 → 规则 firing → AM active → sink 落盘
      → 清理后 resolved 也收到（受 group_interval=5m 影响延迟）
```

**事故 2：短命告警被 group_wait 吞掉**
```
group_wait 窗口内 firing→resolved 的抖动→可能只剩 resolved（等于没通知）
修复：`keep_firing_for: 2m`（当前用于 MysqlDown：for 1m + keep_firing_for 2m，注释保留演练结论）
```

**事故 3：规则文件加载风险**
```
rule_files 挂载路径/语法错误 → 整组规则静默失效
纪律：promtool check rules 思路校准 + 规则进 git + 热加载（lifecycle）
```

**事故 4：常态误报（EsClusterNotGreen）**
```
单节点 ES 副本无法分配 → 常态 yellow → 告警长期 firing
决策项：① 副本数设 0（单节点正确姿势）② 只在 red 触发 ③ 加节点——建议 ① + Runbook 注明
```

**已知短板**：Prometheus 单实例无 HA（盲区）——生产方案双实例 + Thanos/VM 全局视图 + Watchdog 元监控。

### ④ 兜底

- **Watchdog**（always firing）验证"告警链路本身活着"——监控的监控
- **alert-sink 落盘** = 告警审计与丢失兜底（近实时文件可追溯）
- **指标兜底**：VictoriaMetrics（远期存储）+ 应用侧 `/actuator/prometheus`
- 规则分级 severity=critical/warning + 路由/抑制/静默治理风暴

### ⑤ 拷打追问（面试官视角）

1. **"拉 vs 推？"** 拉=服务端控节奏 + 天然 target 健康（`up` 指标）；短任务用 Pushgateway；长驻服务拉更合适。
2. **"P99 怎么算？"** Histogram 桶 `histogram_quantile`——桶边界决定精度（与 SLO 量级匹配）。
3. **"counter/gauge 用错？"** counter 要 `rate()`；gauge 直接看；`rate(gauge)` 出假峰。
4. **"告警风暴怎么防？"** 分组（group_by 服务/告警名）+ 抑制（inhibit 主机挂→服务告警静音）+ 静默（维护窗）+ severity 路由。
5. **"规则改了怎么生效？"** `--web.enable-lifecycle` + `POST /-/reload`；配置进 git。
6. **"Prometheus 怎么 HA？"** 双实例同配各自抓取（查询去重）或 remote_write 到 VM/Thanos；AM 集群去重。
7. **"告警怎么保证不丢？"** 端到端演练（firing→落盘→resolved 全链验证）+ Watchdog + 落盘审计——本项目从此而来。
8. **"为什么不用 Pushgateway？"** 长驻服务拉模型更简单（push 有单点与过期语义问题）。

### ⑥ 话术

> "监控是 Prometheus v2.48 拉模型：9 个 job 覆盖 15 个服务和中间件，加 Alertmanager 加 exporter 一组容器，告警走 webhook 到自建 alert-sink 落盘。最能讲的是治理过程：早期 receiver 是空的 default-noop，告警发了没人收——典型通知黑洞；修复后接了 sink 还做了端到端演练（注入慢 SQL，链路 firing→落盘→resolved 全过）。短命告警会被 group_wait 吞掉，MysqlDown 加了 keep_firing_for。41 条规则 9 组分级路由；用 always firing 的 Watchdog 监控告警链路本身。单点是已知短板，生产双实例加 VictoriaMetrics。"

---

## 生产陷阱（Prometheus 8 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **通知黑洞** | receiver 空/占位——接 sink + 端到端演练验证 |
| 2 | **短命告警被吞** | group_wait 窗口——keep_firing_for |
| 3 | **规则整组静默失效** | 语法/挂载路径错——promtool + git + reload |
| 4 | **counter/gauge 混用** | rate(gauge) 假峰——按类型用函数 |
| 5 | **高基数 label** | traceId/userId 打爆 TSDB——label 纪律（服务/接口/状态码） |
| 6 | **告警风暴** | 无分组/抑制——AM 治理三件套 |
| 7 | **单点盲区** | 无 HA——双实例 + 长期存储 + Watchdog |
| 8 | **常态误报** | 单节点 ES yellow 等——按环境修正规则（副本 0） |

---

## 发散追问地图（横向）

- **存储**：TSDB 原理、retention、remote_write、Thanos/VM/Mimir 对比
- **PromQL**：rate/increase/irate、histogram vs summary、recording rules
- **告警**：路由树、分组抑制静默、SLO 多窗口燃烧率（Burn Rate）
- **生态**：exporter 自研（/actuator/prometheus 是 Micrometer）、Pushgateway、Grafana as Code
- **可观测三支柱**：Metrics（本篇）↔ Logs（K16 ELK）↔ Traces（K14 SkyWalking）
- **AI 运维**：VictoriaMetrics + 诊断场景（DIAG-10）

---

## 面试官评分点

**高级开发级**：拉模型/TSDB/PromQL/告警链路五步。
**架构师加分**：**通知黑洞的完整治理与 e2e 验证**；短命告警（group_wait vs keep_firing_for）；Watchdog 元监控；规则演进治理（31→41 + git/reload）；单点与长期存储方案；错误预算 43m12s 落地。
**危险信号**：counter/gauge 混用；label 塞高基数；告警无分级无抑制；不知道 group_wait 语义；"有规则=有告警"。

---

## 本项目真实证据

**xhs-project**：
- 配置：`config/prometheus/prometheus.yml`（9 job / my-xhs-services 15 服务 :19000~19016 @10s）、`config/prometheus/alert_rules/myxhs_rules.yml`（**9 组 41 条**，精确计数：app 8/biz 9/golden 7/mysql 3/dlq 1/node 3/mysql_conn 6/xxl 3/meta 1）、`config/alertmanager/alertmanager.yml`（group_wait 10s/interval 5m/repeat 4h/send_resolved）
- 运行态：prom/prometheus:v2.48.1 + `--web.enable-lifecycle`（compose:848-857）、AM 19093、alert-sink 19099→`/data2/logs/alerts.jsonl`
- 报告：`docs/reports/alerting-e2e-20260917.md`（黑洞→sink→SLEEP(600) 全链验证 + EsClusterNotGreen 决策项）
- keep_firing_for：`myxhs_rules.yml:388`（MysqlDown，1 条）
- SLO：错误预算 43m12s + Burn Ledger（38 题）

**课程对照**：xhs 52-Prometheus监控栈深度拷打 + 38 题（告警治理/SLO）。

---

## 版本与来源

Prometheus **v2.48.1** / Alertmanager（xhs 部署）；官方文档；xhs 配置与 e2e 报告。

## 真实性说明

规则数（31→39→40→**41 当前**，精确计数与演进标注）/端口/receiver/exporter/keep_firing_for 均为配置与运行态事实；"单点无 HA"主动披露；EsClusterNotGreen 为报告中的决策项。
