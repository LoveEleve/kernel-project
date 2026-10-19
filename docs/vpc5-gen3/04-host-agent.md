---
title: "04-host-agent：框架微内核 / 54 个 Extension / Composer / 三层对账 / 迁移与恢复"
series: "VPC 5.0（Gen3）架构系列"
previous: "03-broker.md"
next: "05-set-sharding.md"
source_refs:
  - "vpc/docs-by-topic/33-vpc-agent-framework.md（793 行全读：20 包/datasyncer 153 文件/extension 83/75 工作项）"
  - "vpc/docs-by-topic/39-vpc-host-agent.md（关键节全读：extension 244/composer 49/dplane 74/reconcile 137/migration/dpdkrecovery）"
  - "vpc/test-good/02-vpc5-gen3/vpc5.0-overview.md（§6 54 Extension/Reconciler 30+）"
---

# 04-host-agent：框架微内核 / 60+ Extension / Composer / 三层对账 / 迁移与恢复

> vpc-host-agent = 母机侧网络代理（**基于 vpc-agent-framework 微内核**）：从 broker 收配置 → Composer 组合 → DPlane 拼装 → vpcko/DPDK/fake 三驱动 → 常驻对账。
> 标注：【实证】/【推导】/【开放】/【外部】。

## §0 它干了什么（例子先行）

一条 vmRTable 从 broker 到 DPDK 的旅程：

```
① DataSyncer（框架）：tRPC 流收到 binlog → key_queue 按 key 串行 → watchrouter 按 configType 路由
② vmrtable Extension：更新 ConfigStore（LevelDB）→ notify 依赖方（refnotify）
③ Composer（rtable_composer）：汇聚 vmRTable/serviceRTable/remoteGw → 展开为完整 rtable（update plan 增量）
④ DPlane Assembler：业务对象 → 数据面 entry（route entry 等）
⑤ 框架 Facade → adapter/{vpcko,dpdk,fake}：netlink 写内核 / DPDK 通道写用户态
⑥ Reconciler（L1 精准/L2 增量/L3 全量）：Diff(desired, actual) → 修复/上报
```

## §1 掌握卡（7 条）

1. **两层结构**：vpc-agent-framework（微内核，通用）+ vpc-host-agent（母机业务，60+ Extension）——同框架还派生 computation-agent / GW 类 Agent。
2. **三数据面驱动**：vpcko（netlink）/ DPDK（通道）/ fake（内存，测试）——Facade 统一入口 + 能力协商（capability）。
3. **54 个 Extension 按域组织**（overview 口径 54；39 目录列表 ≈57——**差标【开放】**）：契约（contracts）解耦域间依赖 + **声明式注册**（ExtensionDescriptor：名称/ConfigType/依赖）。
4. **Composer 解决跨对象组合**：rtable 展开（vmRTable+serviceRTable+remoteGw→完整路由）/ subnetroute 三级联动 / sflow 五表联合。
5. **三层对账**：L1 精准（对象粒度 diffkey）/ L2 增量（dirty 聚合门）/ L3 全量（VpcReconcileStage 保证 VPC 类先行）；**report-only 上报 broker**（与 auto_repair 解耦）。
6. **恢复三机制**：quick_restart（Shutdown Marker + 增量恢复，生产默认）/ dpdkrecovery（探测恢复→清库重启切双驱）/ watchdog。
7. **迁移状态机**：新老 Agent 滚动迁移（shadow→takeover 相位持久化）+ legacy HTTP 兼容层 + **SHADOW_MODE 预下发只读包**（只对账上报、不写数据面）。

## §2 框架层（vpc-agent-framework，20 包 / 982 文件）

- **微内核**：`vpc-broker → DataSyncer → WatchRouter → ExtManager → Extension → DataPlane driver`。
- **核心包**：
  - `datasyncer`（153 文件，最大包）：Syncer（Watch/ReSync 双模）+ watch_orchestrator + **recovery_coordinator / startup_recovery / startup_first_event（首事件屏障）** + rewatch_scheduler（3 worker）+ safe_resume_version + **bypass_executor（关键路径直通）** + merge_remote + remote_get + **scope_gate（strict 生产/dev 两实现）** + seed_fallback；
  - 子包：discovery（DNS 刷新/seedcache）/ watchrouter / internal/dispatcher（**key_queue 保序+公平**/medium_router/scope_blocker/version_tracker 乱序检测）/ **errpolicy**（分类/滑动窗口计数/熔断策略）/ setsyncer（manager/broker_conn/session 状态机/ratelimit/**backpressure**/acker）/ broker（probe_manager/selector/report_selector）；
  - `extension`（83 文件）：ExtManager + **configstore**（快照/增量/binlog 视图 + **canary 灰度 overlay/resolver/snapshot** + dirty manager + secondary_index + scope_deleted）+ refnotify（引用变更通知总线）+ retry（pending_manager/retryable_wrapper）+ setprovider；
  - 其他：storage（LevelDB/merge_core/**shutdown_marker**/fault 注入）/ versionstore / watchstore / shutdownstate（**停机恢复等级**）/ dplane（Facade/Assembler/Pipeline/EntryDiff）/ http（console+10 类 handler）/ metrics（push 到 broker）/ reconciler / capability。
- **启动流程**：配置 → 日志 → 存储（恢复 versionstore/watchstore；**Shutdown Marker 满足→快速重启，否则全量**）→ DataSyncer → Extension 注册 → HTTP/Reconciler/metrics → watchdog。
- **75 个工作项演进史**（specs）：rewatch/zombie brokerconn/shutdown marker/scope gate/canary resolver/quickrestart set drift…——名字即问题清单。

## §3 host-agent 全貌（1988 文件 / 60+ ConfigType）

```
internal/ (598): extension 244 / composer 49 / dplane 74 / reconcile 137 / dataplaneaccess 28 / dpdkrecovery 7 / migration 2 / netutil / metrics / http
pkg/ (270): dataplane 驱动与 Facade / httpclient
docs (351): 含 VPCSchema lineage 关系建模真相源
```

- **部署形态**：Adam 自动部署 + 包内 watchdog；`ENV=product|test|bjcs` 决定 default_brokers；`SHADOW_MODE=true` 打预下发只读包。
- **二进制**：vpc-host-agent（主）/ tvs（数据面 CLI）/ agentctl（运维 CLI）/ mock-broker / perf-runner。

## §4 Extension 体系

- **结构**：基础设施（common：批量错误/dplane 调用封装/itemkeyscope/reconciliation 辅助；contracts：acl/eni/globalroute/remote_gw/rtbroute/rule/subnet/subnetroute/vpc 等域契约；registry：descriptor+register）→ **54 个业务 Extension**（overview 口径；vpc/vpcconfig/…/rtable/servicertable/globalroute/havip/sflow×5/trafficmirror×4/vtrace×2/trunkingsubeni…）。
- **4 回调**（00 §6）：OnConfigChange / OnReconcile / OnConfigDelete / OnConfigFullSync。
- **级联与广播**：vpc 删除级联（del_vpc）、subnet 广播注册表（向 VM/ENI 广播）、子网路由三级联动（rule↔rtbRoute↔subnetRoute）。
- **canary（灰度）在框架 configstore 层**：overlay 叠在基线数据上 + resolver 决定读基线/灰度视图 + canary_snapshot 一致性。

## §5 Composer（跨对象组合）

- **rtable 展开**：vmRTable + serviceRTable + remoteGw → 完整 rtable（next_hop 解析 + remote type 区分 + **update_plan 增量计划**）。
- **runner 设计**：异步 Runner 串行执行 Recompute——**避免 datasyncer 回调栈重入死锁**；keyed_runner 同 key 去重排队。
- 其他域：session（跟随 rule/vpc 订阅）/ sflow 五表 / subnetroute（remote_vpc_ref_batch 差分订阅）/ trafficmirror 四表 / vtrace。

## §6 DPlane（Assembler → 三驱动）

- `pkg/dplane`：**Facade（Extension 调用数据面唯一入口）**/ Assembler（业务实现）/ Pipeline（批量组装）/ EnvDetector（driver 可用性路由）/ **EntryDiff（期望 vs 实际差异计算）**。
- host-agent `internal/dplane`（74 文件）：**36 个领域 Assembler**（39 §3.7.6 列表：routing/network/eni/vip/acl/hostservice/hostwide/routelearn…）。
- **capability 体系**（框架 pkg/capability）：版本/flag/driver 三维解析**字段级能力**（FieldRule——条件字段在各 driver 下的支持规则）。
- **dataplaneaccess**：Exporter/Writer 统一访问（对账读 actual + 高危写接口，受 `dataplane_write.enabled` 门禁）。

## §7 对账体系（三层 + 上报解耦）

- **分层**：**L1 精准**（`rtablekey.DiffKey` = `<scopeType>:<scopeValue>:<driver>:<objectType>:<vpc>|<dst>|<remote>`，对象粒度）/ **L2 增量**（dirty 聚合门）/ **L3 全量**；`VpcReconcileStage` 保证 VPC 类任务先于默认任务完成首轮全量。
- **修复与安全**：**repair_readonly_guard**（shadow_mode 阻止修复写数据面）/ repair_error_collector（失败汇总上报）。
- **上报链路**：report_aggregator（窗口聚合/去重/限流/**leveldb spool**）→ report_strategy（**report-only：confirmed diff 上报 broker**，与 auto_repair 解耦）→ 与 SHADOW_MODE 预下发只读包配套。
- **覆盖**：40+ 域任务（foundation/routing/eni/vip/network/traffic/hostwide/routelearn/acl…）。

## §8 恢复与迁移

| 机制 | 说明 |
|---|---|
| quick_restart | Shutdown Marker（持久化+完整性校验）+ versionstore 增量恢复；生产默认开启 |
| dpdkrecovery | 纯 vpcko 母机运行期 DPDK 恢复：探测 300s（最低 60s）→ Stop → **清 leveldb 目录 → os.Exit(1)** → watchdog 拉起切双驱 |
| migration 状态机 | 新老 Agent 滚动：shadow（预下发）→ takeover 相位持久化 + phase-2 钩子（停 shadow/停老 agent，TODO stub） |
| legacy_compat_handler | 老 vpc-agent HTTP 路径/报文格式兼容（迁移期） |
| netutil.gwmac | fe:ee 模逆算 GWMAC——与 Gen2 设备侧同算法（母机独立计算、零分发） |

## §9 与 Gen2 设备侧对照

| 维度 | Gen2（vpc_agent，Python） | Gen3（vpc-host-agent，Go） |
|---|---|---|
| 配置入口 | 双 ZK Watch / agent-broker 心跳 Pull | tRPC Stream（Watch/Poll） |
| 并发模型 | 全局锁串行（state.mutex） | key_queue 按 key 串行 + 分片并行 |
| 组合逻辑 | 散在 watcher/port.py | **Composer 独立层**（rtable 展开/update plan） |
| 下发 | port.py → netlink/DPDK | DPlane Facade → 三驱动 + capability 协商 |
| 对账 | Inspector（10s×6） | Reconciler（L1/L2/L3 + report-only） |
| 重启 | 30-40min（全量） | quick_restart 秒级（LevelDB 增量） |
| 灰度 | 双 ZK 白名单 | canary overlay（数据层灰度）+ SHADOW_MODE |

## §10 外部对照（【外部】）

- **host-agent ≈ "配置物化的 kubelet"**：声明式期望态 → 本地物化 + 周期对账——与 kubelet 的"期望态→容器运行时→status 上报"同构；差异：kubelet 靠 etcd/watch，这里靠 broker 快照+增量。
- **quick_restart ≈ Envoy hot restart**（复用本地状态增量恢复而非重建）；**SHADOW_MODE ≈ dry-run/shadow deployment**（全链路可观测但不生效）。
- **canary overlay ≈ 特性灰度（feature flag + 数据层覆盖）**——比传统"白名单切换"更细（数据层叠加）。

## §11 钩子

- SET 分片与跨 SET 迁移（agent 如何选 broker/迁移）→ **05**；难点与事故（75+130 工作项里的典型 bug）→ **06**；横切（对账/背压/降级框架）→ **07**。
