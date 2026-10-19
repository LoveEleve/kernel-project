---
title: "07-讲法速查：二代怎么讲"
series: "VPC 二代（Gen2）架构系列"
previous: "06-derivation-review.md"
next: "08-agent-broker-internals.md"
source_refs:
  - "本系列 01-11"
---

# 07-讲法速查：二代怎么讲

> 用途：把 01-10 的内容压缩成可出口的讲法。定位：**架构机制理解层**——讲清系统怎么工作、为什么这么设计。

## §0 30 秒版

> 二代（Gen2）是 VPC 控制面的第二代配置分发：**Kafka 增量 binlog + 分区 ZK（只存临时 binlog）+ CRS 全量快照 + agent-broker 中间层**。用户每次网络变更，经 sdn_oss 的 Saga/Outbox 发到 Ckafka；nfv-proxy 把 binlog 落成分区 ZK 的 `/BINLOG/{vpcId}/{TYPE}/{group}` 节点并登记最近变更索引；merge 进程把增量合并成全量快照写 CRS；母机 agent 通过 agent-broker 的心跳拉取增量/全量。它解决了 Gen1 的 Watch 全量 diff、惊群、重启 30-40 分钟和 ZK 内存爆炸——本质是**把 ZK 从数据库降级为索引，做冷热分离**。

## §1 2 分钟版（按链路讲）

1. **写侧**（20s）：sdn_oss 用 Saga 写 MySQL + Outbox（25 张 trans 表），transId 三路径取号（DB/CRS/独占表）+ epoch；Binlog Producer（20+1）把变更发 Ckafka（key=vpcId_vmIp 保同 VM 有序）。
2. **落 ZK**（20s）：nfv-proxy 的 proxy_binlog 消费 → 写分区 ZK：`/{REGION}/BINLOG/{vpcId}/{TYPE}/{group}/{binlog字符串}`；group = 版本 // GroupSize（500/1000）；每组一个 trigger（组内最大版本）；同时登记 VPCRECENTCHANGE（时间片桶 + ≥5000 提前滚动）。
3. **合并**（30s）：proxy_merge_master 单实例扫 VPCRECENTCHANGE → 任务进 CRS pending 管道 → 计数限流分发（治 vm_prop 慢）；proxy_merge（×5）按版本区间读增量 → 抢 `/SNAPSHOT_LOCK` → 合并写 CRS 分片快照 → version_list 保留 4 版 → 清理已合并 binlog。幂等靠"无新 binlog 跳过"。超 12h 的 binlog 拒写。
4. **消费**（30s）：两条形态——母机走 agent-broker（Go/agentos）：`vpcId%500%15` 分片 + SQLite，agent 心跳 Pull，broker 按 6 种 Pack 回增量/全量（FastStart ~10s / Snapshot 1-5min），CheckJob 5/10min 对账；网关直连 CKV+分区 ZK（the_2g_op，主备倒换回放）。
5. **灰度**（20s）：白名单 `2GNODE/2GSERVICE/2GHOST` 按 VPC 切；agent 双 ZK 共存，切换做全量 diff（以二代为准）。

## §2 高频追问速查

| 追问 | 回答要点 |
|---|---|
| 为什么用 binlog 替代 Watch？ | Watch 是布尔通知（只说变了）→ 消费侧要拉全量 diff（O(n)）；binlog 自带变更内容（O(1)） |
| ZK 里存什么？ | 只存临时 binlog + 索引；全量在 CRS；合并后清理、超 12h 拒写 |
| group 和 trigger 是什么？ | group=版本//GroupSize 的命名空间；trigger=组内最大版本（组水位线） |
| 什么时候能合并？ | 已闭合组（后继组出现）→ 水位=最后闭合组的 trigger；迟到写靠 12h 窗口+对账兜底（推导见 06） |
| 合并为什么不会重复/丢失？ | 幂等：读 version_list 对比版本，无新 binlog 跳过；同 (vpc,type) 锁互斥；清理只在合并后 |
| agent-broker 干什么？ | 把数万母机对 ZK/CRS 的读扇入；SQLite 落盘 + 心跳 Pull（6 种 Pack） |
| 6 种 Pack 是什么？ | Uninitialized / UpToDate / Outdated（>5000 降全量）/ BinlogIncomplete（断档）/ BinlogUpdate / PackError |
| 为什么 Pull 不是 Push？ | 兼容无长连接的 Python agent；Pull 天然背压、broker 不维护订阅（口径冲突见 06） |
| 冷启动怎么快？ | FastStart（本地 SQLite + 增量续跑 ~10s）；不行才 Snapshot（CRS 分片+MD5+版本移位 1-5min） |
| 两条消费形态为什么不同？ | 母机量大要扇入；网关少而关键，主备倒换要回放 binlog，直连更可控 |
| 快照怎么存的？ | version_list 保留 4 版；分片 `{vpcId}_{ver}_{type}_{offset}`（页 7000~50000 按类型）；worker 快照是 SQLite .db 分片 |
| 灰度怎么做？ | 白名单按 VPC 切；双 ZK 共存；切换时全量 diff 以二代为准；每一步可回退 |
| broker 内部怎么处理一条 binlog？ | BinlogHandler → NextGenRemoteGet → BinlogParser → MergeUniObj（快照+增量→对象集）→ SQLite；任意版本重放靠 binlog_replayer |
| echo 时延监控是什么？ | 每 5 分钟写 /TVPC/ECHO 探测任务（走真实 ZK→watch 路径）；worker 把处理时延写 mmap 固定槽位；超 660s 告警——专治 watch 静默失效 |
| 对账有几层？ | 四层：DB↔ZK / DB↔快照 / ZK↔SQLite / 期望↔数据面；修复一律朝上游（DB 权威） |
| 设备侧 agent 有几种形态？ | 三种：agent_base（框架底座）/ vpc_agent master（母机+内核网关，11 种角色）/ vpc_agent_dpdk_vpcgw（DPDK 网关：16 分片 + 四级优先级 + 双包主备） |

## §3 数字速查

- 14 种 binlog 类型；action I/D/U/R
- GroupSize：RTBROUTE/SERVICE/GLOBALROUTE=500，其余=1000
- 12h 拒写窗口；VPCRECENTCHANGE ≥5000 切分；发布保护 9:30-9:39
- broker：vpcId % 500 % 15 + GlobalWorker(15)；PackBinlogMaxSize=5000；DelMoreThreshold=5000
- 快照保留 4 版；对账 5/10min；恢复 10s / 1-5min
- nfv-proxy 14 进程；merge ×5；BinlogProducer 20+1
- echo：周期 300s / 告警阈值 660s；mmap 1024 槽 × 1KB

## §4 边界提醒

- 本系列是**架构机制知识**（"系统怎么工作"），不是个人成果描述；对外讲述时机制归系统、个人归属另行核对。
- 标注为【推断】【开放】的点（见 06）不要讲成定论。
