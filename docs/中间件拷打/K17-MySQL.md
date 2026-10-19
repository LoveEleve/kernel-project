# K17 — 组件深度拷打：MySQL / InnoDB（矩阵收官）

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：B+树/回表、MVCC、三层锁、redo/binlog 两阶段提交、GTID 复制、RTO/RPO、僵尸连接、SqlGuard
> 依据：xhs-project 运行态（8.0.46 配置）+ 43-MySQL深度拷打 + 23-MySQL治理 + failover/comment/备份 报告

---

## 问题

InnoDB 索引和锁原理？一条 update 的完整链路？主从复制怎么保证一致？故障切换 RTO/RPO 多少？

---

## 面试可讲版（五段式）

### ① 原理层（索引/MVCC/锁/两阶段）

- **B+ 树索引**：聚簇索引（主键叶子存整行）+ 二级索引（叶子存主键 → **回表**）；最左前缀、覆盖索引；页 16KB，三层约 2000 万行
- **MVCC**：隐藏列 `trx_id/roll_pointer` + undo 版本链 + ReadView——RR 下普通读=**快照读**（不加锁）；当前读（FOR UPDATE/UPDATE）读最新+加锁
- **锁三层**：全局/表/行（记录锁+间隙锁+临键锁）；意向锁做表行兼容；RR 范围条件加 gap lock 防幻读；死锁由 InnoDB 检测并回滚代价小的事务
- **一条 UPDATE 链路**：Buffer Pool 改页（脏页）→ redo（prepare）→ binlog → redo commit（**两阶段提交**）→ 后台刷脏——保证崩溃恢复时 redo/binlog 一致

### ② 项目配置与用法（运行态/配置实证）

```
MySQL 8.0.46（compose mysql:8.0）；隔离级别 RR；buffer pool 1G；max_connections=500（cpus2/内存1536M）
innodb_flush_log_at_trx_commit=2 + sync_binlog=0 —— 性能换持久性：OS 崩溃可能丢约 1s 事务
GTID ON + enforce + Auto_Position=1（异步复制，无 semi-sync——RPO 不保证）
binlog ROW + binlog-row-image=FULL；slow-query-log 0.5s；innodb-print-all-deadlocks=ON；server-id 1(主)/2(从)
```

- 分片：订单 4×4（ShardingSphere，K12）；支付/映射表独立数据源
- 读写分离 `ReadWriteRoutingDataSource`；池 master 20/slave 10 + `initializationFailTimeout(-1)`
- **SqlGuard 慢 SQL 分级治理**（`SqlGuardInterceptor`+`SqlGuardBlockedException`）：v1 只观测（>200ms 日志+指标）→ v2 分级（连续 5 次判定 → 阻断；**默认关** + 白名单 + 豁免名单 + 冷却恢复 + 3 指标 `slow_total/open_total/blocked_total`；真机验证第 2 次请求即 500）

### ③ 慢 SQL 与连接（真实事故复盘）

**comment 事故（三因素叠加）**：
```
① O(N²)：相关子查询模拟 ROW_NUMBER → 单根 5,202 子评论时 >30s / HTTP 500
   修复：窗口函数改写 + 复合索引（V3__comment_perf_index.sql：
   idx_note_parent_deleted_id / idx_parent_deleted_id，Covering index 3.08ms）
② 僵尸连接风暴：压测中发布重启 content，旧实例被 kill 后 TCP CLOSE-WAIT，
   MySQL 侧 318 条挂起查询（t_comment SHARED_READ MDL，挂 64 分钟）
   → 连接 459/500 逼近上限、P99 500ms；KILL 后 459→106，立即恢复
   → 教训：不要在压测中发布重启；连接数/长查询告警（事后补 4 条规则）
③ 不盲目加池：先审计连接持有者再谈池大小（"读池饱和"曾误判，真因是僵尸连接）
最终基线：4,418 RPS / P99 70ms（ab n=20000 c=64，0 失败）
```

**从库拥塞事故**：单条大 DELETE 清 5,200 行 → 从库回放 `Seconds_Behind=177s` → 期间压测查询在从库挂起 30 分钟 → kill >60s 长查询恢复——**批量 DML 必须分批（LIMIT 500 循环）**。

**备份 20B 空文件事故**：旧脚本是云模板（无效 host/端口 + 吞错）→ 长期产出 20B 空文件且无调度 → 重写 `docker exec mysqldump + pipefail + size≥100KB + gunzip -t` 双校验 → **3.0MB 实体备份 + cron 每日 3:30（保留 7 天）**（`deploy/scripts/mysql-backup.sh` 为修复版；`config/deploy-cloud/` 为旧模板——配置漂移观察）。

### ④ 复制与故障转移（演练实测）

```
停主（优雅）10.2s → 提升从库 0.087s → 应用改配置切换 22s → content 视角 RTO ≈32s（不含故障发现）
本次 RPO=0（"幸运"：停前 2s 已同步）——异步复制不保证
```

**暴露的 5 个短板（诚实清单）**：① **无自动检测/切换**（真实 RTO=检测+32s；需 MHA/Orchestrator/ProxySQL/MGR）② 逐服务改配置（应收敛到 VIP/DNS/ProxySQL）③ RPO 不保证（要 semi-sync 或受控 switchover）④ errant GTID 风险（非受控提升）⑤ 告警被吞（MysqlDown firing 75s 后被 group_wait 丢弃 → 已修 keep_firing_for:2m + group_wait:10s + Watchdog，联动 K15）。
**为什么不用 MGR**：单机环境无多节点仲裁价值；生产三节点 MGR/Orchestrator 为正解（路线）。

### ⑤ 拷打追问（面试官视角）

1. **"RR 和 RC 区别？"** RR 快照一致性更强但 gap lock 多（并发写差）；RC 无 gap lock 有幻读——本项目 RR，范围更新注意 gap 死锁。
2. **"update 为什么要两阶段提交？"** redo/binlog 两个日志系统：prepare→binlog→commit 保证崩溃恢复一致（防主从/备份分叉）。
3. **"flush_log=2 丢了怎么办？"** 丢 OS 崩溃窗口（约 1s）；兜底=对账+幂等；强一致可调 1（性能代价）——**明确取舍不是疏忽**。
4. **"主从延迟怎么处理？"** 监控 Seconds_Behind_Source；写后读强制主库；大事务分批（防 177s 重演）。
5. **"死锁怎么排查？"** `SHOW ENGINE INNODB STATUS` LATEST DETECTED DEADLOCK（本机还开了 print-all-deadlocks）；固定加锁顺序、缩小事务、重试。
6. **"连接池多大合适？"** 按 QPS×耗时估并发×系数；不是越大越好——459/500 时不加池先清僵尸。
7. **"慢 SQL 会拖垮服务吗？"** 应用层 SqlGuard 熔断 + DB 层 max_execution_time + 读写分池 + 告警（三件套）。
8. **"分库分表后跨分片事务？"** 避免强事务：本地事务+最终一致（事务消息/对账）；单用户事务不跨库（K12）。

### ⑥ 话术

> "InnoDB 聚簇索引加二级索引回表、MVCC 快照读、行锁加临键锁；update 走 redo/binlog 两阶段提交。我们 8.0.46、RR、buffer 1G，flush_log=2 加 sync_binlog=0 是性能换持久性，极端丢约 1 秒，靠对账兜底——这个取舍我会主动讲。最典型事故是 comment：O(N²) 子查询加僵尸连接风暴，318 条挂起查询把连接顶到 459/500，清理后 459 到 106 立刻恢复——所以不盲目加池。故障转移演练过：停主 10.2 秒、提升 0.087 秒、应用切换 22 秒，RTO 约 32 秒不含发现时间；最大短板是没有自动检测切换，路线是 Orchestrator 或 ProxySQL。"

---

## 生产陷阱（MySQL 10 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **flush_log=2 当不丢** | OS 崩溃窗口 ~1s——对账兜底 + 主动讲取舍 |
| 2 | **僵尸连接** | 压测+发布叠加，CLOSE-WAIT 挂 MDL——不压测期发布 + 告警 + Kill Runbook |
| 3 | **盲目加池** | 先审计连接持有者（459 不健康） |
| 4 | **大 DELETE 拖从库** | 177s 延迟 + 30min 挂起——分批提交 |
| 5 | **相关子查询 O(N²)** | 极端数据不可用——窗口函数+复合索引 |
| 6 | **RTO 只报提升时间** | 忽略检测与切换——报总链路（32s+检测） |
| 7 | **RPO 口头保证** | 异步复制不保证——semi-sync/受控切换 |
| 8 | **errant GTID** | 非受控提升——计划切换（停写→同步→提升） |
| 9 | **备份吞错** | 云模板 20B 空文件——双校验（size+gunzip）+ cron |
| 10 | **阻断误伤** | 慢≠坏（对账天然慢）——SqlGuard 默认关+白名单+豁免 |

---

## 发散追问地图（横向）

- **索引**：B+树/哈希/全文、联合索引顺序、索引下推、统计信息
- **事务**：隔离级别、next-key lock、两阶段提交、组提交
- **日志**：redo/undo/binlog、WAL、日志格式
- **复制**：异步/半同步/组复制、GTID、多源、延迟复制
- **高可用**：Orchestrator/MHA/ProxySQL、MGR；在线 DDL（gh-ost/pt-osc）；备份恢复（PITR）
- **治理**：SqlGuard 分级、慢查询观测→校准→强制；连接池/泄漏检测

---

## 面试官评分点

**高级开发级**：索引/MVCC/锁/两阶段基础；知道自己配置的取舍。
**架构师加分**：**flush_log/sync_binlog 的取舍表达**；僵尸连接事故完整根因链（318/64min/459→106）；RTO 分解与"无自动检测"短板；errant GTID/半同步深水区；SqlGuard"观测→校准→分级强制"的方法论；备份双校验。
**危险信号**：RR/RC 说不清；主从不一致没预案；RTO 只报提升；把 flush_log=2 说成不丢；盲目加池/加缓存。

---

## 本项目真实证据

**xhs-project**：
- 配置：compose mysql:8.0（max-connections 500 / buffer 1024M / flush_log=2 / sync_binlog=0 / GTID / ROW+FULL / slow 0.5s / deadlocks print / cpus2·1.5G）；运行态 8.0.46
- 报告：`mysql-failover-drill-20260917.md`（10.2/0.087/22/32s + 5 短板 + 选型）、`comment-sql-tuning-20260917.md`（O(N²)/318 僵尸/459→106/4,418 RPS/177s 从库）、`gap-sweep-20260920.md`（备份 20B→3.0MB+cron）
- 代码：`SqlGuardInterceptor`/`SqlGuardBlockedException`/`MyBatisMetricsInterceptor`；`V3__comment_perf_index.sql`
- 脚本：`deploy/scripts/mysql-backup.sh`（修复版）vs `config/deploy-cloud/`（旧模板，漂移观察）

**课程对照**：xhs 43-MySQL深度拷打 + 23-MySQL治理 + 21（读写分离）/29（分片）/30（独立数据源）。

---

## 版本与来源

MySQL **8.0.46**（运行态；compose mysql:8.0）；官方文档（InnoDB/复制）；xhs 配置/演练/调优/备份报告。

## 真实性说明

配置/RTO/事故数字（318/459→106/177s/4,418 RPS/3.0MB）均为运行态与报告事实；"RPO=0"为演练特例；短板与配置漂移主动披露；备份双版脚本以 `deploy/scripts` 修复版为准。

---

## K 系列完成度（xhs 14 组件矩阵闭环）

```
docs/中间件拷打/
├── K01 RocketMQ ✅  K02 Redis ✅  K03 ES ✅  K04 Nacos ✅  K05 Sentinel ✅
├── K06 Seata ✅  K07 Feign-LB ✅  K08 Dubbo ✅  K09 gRPC ✅
├── K10 Canal ✅  K11 Redisson ✅  K12 ShardingSphere ✅  K13 XXL-Job ✅
├── K14 SkyWalking ✅  K15 Prometheus ✅  K16 ELK ✅  K17 MySQL ✅
└── REVIEW-2026-10-05.md
—— xhs 14 组件（40-53 章）：RocketMQ/Redis/ES/MySQL/Nacos/Redisson/XXL-Job/Sentinel/Feign/ShardingSphere/Canal/SkyWalking/Prometheus/ELK 全覆盖 + Seata/Dubbo/gRPC 扩展
```
