---
title: "06-难点与事故：静默故障三案 / 两仓 bug 史 / Gen2 根因对照"
series: "VPC 5.0（Gen3）架构系列"
previous: "05-set-sharding.md"
next: "07-cross-cutting.md"
source_refs:
  - "vpc/test-good/02-vpc5-gen3/failure-scenarios.md（781 行全读：10 场景/单点/组合故障——另见 02 §6）"
  - "vpc/test-good/02-vpc5-gen3/vpc-fusion-position.md §4（五故障场景业务影响）"
  - "vpc/docs-by-topic/33-vpc-agent-framework.md §3.27（specs 75 工作项全清单）"
  - "vpc/docs-by-topic/39-vpc-host-agent.md §3.10（specs 139 工作项五月度主题）"
  - "vpc/interview/vpc5-面试文档/题库-分布式问题（58 题全读：理论×系统落点）"
  - "vpc/docs-by-topic/11-血泪教训 + Gen2 系列 15 章（根因方法论对照）"
---

# 06-难点与事故：静默故障三案 / 两仓 bug 史 / Gen2 根因对照

> 03/04 已给组件级故障；本篇给"跨组件视角"：静默故障三案（最危险）、两仓 ~210 个工作项聚类的 bug 史、与 Gen2 事故方法论的对照。
> 标注：【实证】/【推导】/【开放】/【外部】。

## §0 它干了什么（例子先行）

**最危险的不是宕机，是"静默"**——三类不报错、不宕机、只是"错"的故障：

```
① 静默数据错误：DeleteConfig Put(nil) → 已删配置"复活" → 流量发到已销毁 VM
   （无异常、无日志，靠对账报 Redundant 才暴露——这就是不测"遍历为空"能漏掉的原因）
② 静默空转：resourceObj 自指死循环 → 反复删/建 state，日志高频重复
③ 静默停更：message/binlog 停消费 → 不报错、不宕机，只是"变更不再前进"→ Kafka 堆积 → 四类指标全中
```

## §1 掌握卡（7 条）

1. **vpc5 把 Gen2 的 5 类根因"机制化"了**：并发→Lua 原子化；状态原子→ownership；防御反噬→fail-stop/只读护栏；检测盲区→Reconciler+report-only；校验缺失→capability 门控。
2. **两仓 ~210 个工作项**（framework 75 + host-agent 139）是活的 bug 史——名字即问题清单。
3. **静默故障三案**：配置复活 / 初始化死循环 / 变更停更——共同点"无异常"，共同解法"对账 + 日志模式 + echo/metrics"。
4. **单点依然有三个**：Kafka / Redis / MySQL（组合挂=系统不可用）——恢复时间见 02 §6。
5. **版本是分布式系统的坐标系**：诊断= trace + 版本对齐（"这条配置到哪一层、版本几"）。
6. **锁只负责效率，正确性靠唯一键/条件更新/fence**（ownership 三重校验即 fencing 的工程实现）。
7. **系统最难的题不是一致性，是可运营性**——能发现、能定位、能修复、能验证。

## §2 难点地图（vpc5 版七类，对照 Gen2 系列 15 章）

| # | 难点 | vpc5 难点形态 | 解法 | 翻车锚点 |
|---|---|---|---|---|
| 1 | 规模 × 正确性 | 200 万 VPC/1 亿条/快照 60-100GB（09 §4 容量账） | 分片（SET/ShardId）+ 攒批 + cloneSnapshot | Kafka 积压（agent 侧 backlog=0 假阴性，2-3 分钟恢复）；27.8h 首启 |
| 2 | 任务调度可靠性 | 四方队列（init/snapshot/reconcile/clean）+ 租约 | 8 Lua + Lease/Heartbeat/Recovery + 限频 | BLPop 丢任务；卡死 processing；heartbeat 死重复执行（09 §3 边界） |
| 3 | 分布式协调 | Redis 选举脑裂窗口（TTL 内双 Leader 产任务） | claim 原子唯一性吸收 + priority 纯函数选主（无需共识） | 双 Leader 重复产任务；循环迁移（05 §6） |
| 4 | 一致性治理 | 版本连续性/三向合并/复活 | 版本单调+连续判定 + 对账 report-only + cleanconfig 显式规则 | DeleteConfig 复活；Redundant 误删风险 |
| 5 | 演进与迁移 | Gen2→Gen3 双写/跨 SET/quick restart | 四阶段迁移 + 先建新后拆旧 + Shutdown Marker | quick restart 两 bugfix（scopekey 未恢复 / global set 漂移，下 §4） |
| 6 | 数据面正确性 | LevelDB 语义/capability/三驱动（vpcko/dpdk/fake）差异 | Put vs Delete 测试观测点 + 字段级 capability 门控 | 复活 bug；能力门控缺口 |
| 7 | 可观测与静默故障 | 静默停更/静默空转/水位盲区 | metrics push + trace（agentctl）+ echo extension（specs）+ 对账 | 04 §2；**队列水位告警是短板（题库自认）** |

## §3 静默故障三案深读

**案一：配置复活（DeleteConfig Put(nil)→Delete）**【实证，02 §8】——影响链：DELETE binlog → `db.Put(key,nil)`（key 还在、value 空）→ 组装修复快照 → broker 推送 → agent 反序列化空字节失败或保留旧路由 → 流量发到已销毁的 VM。发现：对账 Redundant。**漏网根因：单测只测 Get 返回 nil，没测遍历为空**。

**案二：初始化死循环（自指检查）**【实证】——"HR 检查谁已入职时发现自己没记录→删自己→再发任务"。修复：跳过自身；**发现：CPU 高告警 + 日志刷屏（"resource obj not found, task end"）+ /system/list-config-state 状态反复 + /up 超时**。

**案三：配置停更（binlog 停消费）**【实证】——"沉默的致命伤"（原文话术）：Kafka 堆积→快照停更→broker 拉旧快照→**四类指标全中（新 VM 不通 / ACL 不生效 / 销毁后仍转发 / 跨 VPC 断）**；恢复=积压消除+40s。

**共同解法**：① 对账（Redundant/Missing 可见化）② 日志模式巡检 ③ metrics push/echo 端到端活性 ④ **测试观测点要选对**（"Get 返回 nil" ≠ "遍历为空"）。

## §4 两仓 bug 史（~210 工作项聚类）

framework specs 75 项 + host-agent specs 139 项（202604-08）——按主题聚类（名字即问题）：

| 族 | 代表工作项 | 教训主题 |
|---|---|---|
| **连接与重订阅** | zombie-brokerconn-poll-loop、rewatch-stale-syncer、orphan-rewatch-after-failed-watch、rewatch-reroute-same-set-loop、rewatch-burst-broker-send-channel-full、startup-watch-timeout-failfast、poll-stale-no-stream-failfast | 重订阅是最容易出"循环/孤儿/风暴"的路径（对照 05 §6 循环迁移三层保护） |
| **停机与重启** | shutdown-marker-data-loss、unified-shutdown-consistency-guard、quickrestart-ctrlset-scopekey-not-restored、quickrestart-global-scope-set-drift、hybrid-startup-recovery、startup-settled-lifecycle | 快速重启=两个 bugfix（scopekey 未恢复 / global set 漂移）+ shutdown marker 丢数据/一致性准入系列；停机期间"一致性准入"是独立课题 |
| **订阅生命周期** | partial-unwatch-watchstore-cleanup、partial-snapshot-itemkeys、unwatch-symmetric-itemkeys、configstore-batch-subscription、forcewatch-set-migration、scope-gate-derived-subscription | 部分退订/部分快照的清理容易泄漏（对称化是通用修法） |
| **版本与游标** | versionstore-snapshot-key-collision、safe-snapshot-cursor、safe-resume-version、startup-rewatch-first-event-barrier | 断点续传的"安全游标"必须防回退/防错位 |
| **恢复与故障域** | leveldb-fault-failstop-recovery、broker-health-recovery、seed-dns-resolve-shuffle-fallback、pending-manager-notify-burst、delete-oldvalue-capture | 恢复路径同样要防风暴（notify burst）/防错值（oldvalue） |
| **数据面与对账** | rtable 精准三层对账、instance-cascade 性能、百万母机基数裁剪、shadow-mode 资源护栏、capability 门控缺口、dpdk-recovery 自重启 | 对账从两态升级三层；指标基数=可观测的规模税 |

> 读法：**把工作项名当"事故索引"**——每个名字背后是一次真实问题+修复；面试问"你们踩过什么坑"，从这里取材。

## §5 与 Gen2 根因方法论对照（Gen2 系列 15 章的 5 根因 → vpc5 机制化）

| Gen2 根因模式 | Gen2 翻车 | vpc5 的机制化回答 |
|---|---|---|
| 事件并发"add 被 eat" | #6 两把锁漏下发 | key_queue 按 key 串行 + 版本单调（天然单通道） |
| check-then-act 非原子 | #5/#17 ready 误判 | ownership 三重校验在 Lua 内原子；scope gate 闸门 |
| 防御机制反噬 | #10 阈值阻止恢复 / #3 回退自伤 | fail-stop 取舍（宁可停不可带病）+ 只读护栏（shadow/failstop） |
| 检测盲区 | 告警只覆盖 40% | 对账 report-only + trace（agentctl）+ metrics push + echo |
| 外部输入不校验 | #19 0.0.0.0 写穿 | capability 门控 + delete-oldvalue-capture + safe cursor |

> **结论**：Gen2 是"踩坑后打补丁"，Gen3 把同类坑做成了机制（一致性/校验/恢复路径全部框架化）——但**框架自身也可能出 bug**（§4 的 ~210 项就是新战场）。

## §6 面试口径（题库 58 题精华，全部【实证】）

- **CAP**：AP——"可延迟、不可错乱"；不一致窗口四类各有检测手段（DB→Kafka→fusion→broker→agent 逐层）。
- **投递语义**：at-least-once + 幂等 = **效果上 exactly-once；别说 exactly-once**。
- **fencing token**："我们的 ownership 三重校验（workerID+lease）就是它的工程实现。"
- **锁**："只负责效率——正确性靠唯一键/条件更新/fence；把分布式锁当一致性保证是经典误区。"
- **对账哲学**："发现而不是消灭——自动删的风险大于收益。"
- **回滚**："回滚是生成反向变更（新版本），不是撤销历史。"
- **版本**："不用时间戳（回拨/同毫秒并发）；版本单调且能判断连续性。"
- **租约**："带时限的独占授权——时间边界即容错边界（Gray & Cheriton）。"
- **最难**："不是一致性本身，是**可运营性**——能发现、能定位、能修复、能验证。"
- **诚实边界（自认短板）**："发布侧是限频闸门，不是按水位背压——队列水位告警是短板，可加'水位→暂停发布'。"

## §7 边界

- §4 从 spec **名称**聚类（材料只给名称与月度主题）——不编造细节，逐项细节待源码；标【实证，名称级】。
- 故障场景全表（10 场景/单点 3/组合 3/恢复时间）在 **02 §6**；组件级恢复保证在 03 §8（断线三层）/04 §8（恢复与迁移）——本篇不重复。
- Gen2 事故明细（22 起）在 Gen2 系列 15 章；本篇只做根因方法论对照。
