---
title: "13-ZK 定制族深读：同步器 / 3.5.6 / differ / 管控 / 监控 / 备份"
series: "VPC 二代（Gen2）架构系列"
previous: "12-dataplane-protocol.md"
next: "14-business-scenarios.md"
source_refs:
  - "vpc/docs-by-topic/30-vpc_zk_cos_backup（VPC ZK 数据 COS 备份转储工具）.md（242 行全读）"
  - "vpc/docs-by-topic/31-vpc_zookeeper_3_4_6_synchronizer.md（265 行全读）"
  - "vpc/docs-by-topic/32-vpc_zookeeper_3_5_6.md（225 行全读）"
  - "vpc/docs-by-topic/43-zk_agent.md（200 行全读）"
  - "vpc/docs-by-topic/44-zk_controller.md（142 行全读）"
  - "vpc/docs-by-topic/45-ZooKeeper_VPC_Monitor（vpc_monitor）.md（293 行全读）"
  - "vpc/docs-by-topic/46-zookeeper-3.4.6-differ（vpc_zk_differ）.md（314 行全读）"
---

# 13-ZK 定制族深读：同步器 / 3.5.6 / differ / 管控 / 监控 / 备份

> 05 §4 给了 ZK 基建的族谱概览；本篇是七件套的深读版：集群本体（3.4.6 同步器版 / 3.5.6 定制版）+ 校验（differ）+ 管控（controller）+ 执行（zk_agent）+ 监控（vpc_monitor）+ 备份（cos_backup）。
> 标注：【实证】= 材料有据；【推导】= 按约束推导。

## §0 它干了什么（例子先行）

**主备 ZK 集群的一次"体检"**——七件套各干一段：

```
① 3.4.6 同步器（observer 角色）：把主集群事务实时增量同步到从集群（binlog 式）+ 全量追平
② differ（独立进程）：SFTP 拉两侧 snapshot/txnlog → 重建内存树 → 逐 znode/逐事务比对 → JSON 差异报告
③ vpc_monitor：四字命令/jstat/连接数/写时延探测 → barad + CMEM 上报；异常告警收敛
④ zk_controller：管控面（组件/集群/节点/池/客户端），供 agent 查询"就近 ZK 节点"
⑤ zk_agent：本机执行端——下发安全组规则、限流、启停 zk 程序、定时安全清理 ZK 数据
⑥ cos_backup：定时把 snapshot/txnlog 备份到 COS（10 天归档/30 天删除）
⑦ 3.5.6：集群本体的定制版（zxid 进位保护 + 只读 observer 白名单）
```

## §1 掌握卡（6 条）

1. **同步器 = 把 observer 改造成"跨集群同步器"**：主→从增量（Curator 批量事务）+ 全量追平 + LeaderLatch 主备 + 8 个四字命令远程控制。
2. **3.5.6 的核心是 zxid 进位（roll over）安全**：AtomicZxid + epoch 更新与选举配合 + 兼容 3.4.4 混部；外加"只读 Observer"（writeablePaths 白名单）。
3. **differ = 主备数据一致性校验**：快照对比（SnapLogDiffer）+ 事务对比（LogDiffer），SFTP 限速拉取，差异回查在线 ZK 二次确认。
4. **管控/执行分离**：zk_controller（Python 管控面）+ zk_agent（Go 节点执行端，安全组/限流/程序管理/清理）。
5. **监控覆盖全族**：vpc_monitor 采 ZK 本体 + syncer/differ/cos_bak/account/zknode 子组件，barad+CMEM 双通道上报。
6. **备份是有状态的**：cos_backup 用 Redis 角色锁 + 本地/Redis 缓存去重，COS bucket 带生命周期（10 天归档/30 天删除）。

## §2 3.4.6 同步器（observer → CrossClusterSynchronizer）

- **形态**：Apache ZooKeeper 3.4.6 官方源码 + 128 个定制提交；核心类 `CrossClusterSynchronizer`（约 1800 行）；状态机 `WAIT_TO_INIT / SYNCHRONIZING / PAUSED`；内嵌 LeaderLatch 主备选举；全量同步线程 `FullSynchronizerThread`（多线程分批写远端）。
- **增量同步**：Observer 提交事务后进入缓存队列 → 校验长度/大小限制 → 经 `CuratorConnect` 批量事务写入远端集群；异常经星云告警（以 `vpc_zk_syncer` 身份上报）。
- **配置**（synchronizer.conf）：`remoteLastSeenZxid / remoteZkAddress / sessionTimeoutMs / similarThrehold / skipSyncPaths.N / syncProjectName`（`remoteLastSeenZxid` 存于目标 ZK `/syncSlave/lastSeenZxid`——zk-knowledge 补遗）。
- **8 个四字命令**（经 NIOServerCnxn 的 SynchronizerCommand）：

| 命令 | 作用 |
|---|---|
| `syld` | 读 synchronizer.conf 重新初始化同步器 |
| `syst` / `sypa` / `syfi` | 启动 / 暂停 / 结束同步 |
| `syin` | 查看同步器实例信息（状态机、计数器） |
| `sysi` | 查询 OB 与远端 zxid 差值是否达阈值（similarThrehold） |
| `syra` | 查询当前可同步的 zxid 区间与缓存队列大小 |
| `syss` | 触发 SYNC_ALL 全量同步 |

- **配套**：`skipSyncPaths` 跳过同步路径；远端超时等待/最大数据量限制/multi 事务大小计算；新增 `EphemeralAttr/PathAttr` jute 事务属性；`AtomicZxid` 预置（为 roll over 做准备）。

## §3 3.5.6 定制版（集群本体）

- **形态**：官方 3.5.6（Maven 多模块）+ ~60 个定制提交。四大定制能力：
  1. **zxid 进位安全**：`ZxidUtils.AtomicZxid` 原子 zxid；计数器临近满时 `Leader` 更新 epoch（`tryToUpdateEpochFromZxid`）+ 投票 epoch 补齐；`FastLeaderElection` 兼容 3.4.4 的 36 位 zxid 选票格式（支持混部）。
  2. **只读 Observer**：`writeablePaths.N` 可写白名单 + `readonly.enabled.if.observer` 开关（`ObserverZooKeeperServer.loadVpcObserverConfig()` 加载；白名单外写请求拒绝）——四字命令 `ldcf`（重载）/`qucf`（查询）。
  3. **新四字命令**：`mnt2`（增强 mntr + roll over 信息）、`elec`（leader new_epoch 与选举状态）。
  4. **运维配套**：Kona JDK11 + ZGC + 大页（按机器内存算 Xmx；<50GiB 不启用大页）；`myid=IP 整数值`；standalone docker；atop 监控。
- **patch 清单**：31 个文件（3 个新增命令处理器 + ZxidUtils/Leader/FastLeaderElection 等修改 + ZxidRolloverTest）。
- **保护链路**：`hzxid(AtomicZxid)` 原子递增 → 临近满更新 epoch → 选举侧兼容处理，避免跨 epoch 异常。
- **备查分支（未合入，查漏补缺）**：`fix/zxid_counter_extend`（7 文件 +40/-15，2021-08）——counter 32→40 位（容量 ×256：10 万 QPS 下从 12 小时到 4 个月；代价：epoch 42.9 亿→1677 万次、新旧节点不能混合组集群）；master 最终选"跳过特殊值"（保持 32 位、跳过 0 与 0xFFFFFFFF，完全兼容）。背景：ZOOKEEPER-1277——counter 进位到 epoch → Follower 误判新 epoch → 走 DIFF 而非 TRUNC → 未提交 txn 被错误保留（数据不一致）。

## §4 differ（主备一致性校验）

- **形态**：Java 11 + Maven fatjar（3 profile：main 占位 / `snap` 快照对比 / `txn` 事务对比）；`service.sh start snap|txn|all`。
- **两个主入口**：
  - `SnapLogDiffer`：读两侧 snapshot+txnlog 重建内存数据树 → 多线程逐 znode 比对 data/ACL/stat/临时节点归属 → 可疑差异回查远端在线 ZK 二次确认 → JSON 差异报告 + 告警。
  - `LogDiffer`：两侧 txn.log 解析 create/delete/setData/setACL/multi（multi 拆分、跳过临时节点创建）→ `ListDiffer` 按 zxid 对齐求差。
- **取数**：`ScpSnapLogThread` 经 SFTP（JSch + `SftpSpeedControlMonitor` 限速）拉取，避免影响线上磁盘 IO。
- **配置**：`differ.cfg`（左右目录/时间窗/SFTP/skipPaths：`/syncSlave`、`/timeflow`、`/tmptimeflow`、`/zookeeper/config` 等同步器写入路径）。
- **辅助分析器**：`SnapLogAclAnalyser`（ACL 风险：入方向可达而出不去的 IN 规则）、`SnapLogHostifnAnalyser`、`SnapLogTvpcRouteValueAnalyser`、`SnapLogTruncater`（截断到指定时间点）、`SnapLogAnalyser`（统计检查）。
- **运维工具**：`tools/ansibleDisaster/`（容灾切换：安装 differ/slave zk、校验 syncer 同步进度、提升 master、升级 syncer 包版本）+ `scan_zk_group_v1.0/check_zk_group.py`（集群巡检：冲突 server id/leader 数/znode/zxid/epoch 差距）。

## §5 zk_controller（管控面）

- **形态**：Python 2.7 + Falcon + SQLAlchemy/MySQL + uwsgi/nginx；进程名 `zk-mgr`。
- **资源模型**：五类资源 CRUD——`components`（组件）/ `clusters`（集群）/ `zk_nodes`（节点，pool+ip 唯一）/ `zk_pools`（池 + 成员维护）/ `clients`（接入客户端）。
- **就近查询**：`/zk_nodes_nearby/{client}/rsync(_to_db)`——客户端白名单 `CLUSTER_NEARBY_CLIENT = [vpc_agent, vpcgw_vpc_agent, vpcgw_vpc_agent_bak, dfw_api]`。
- **认证**：Basic / Digest（`auth_backend`）；DB 六张表（t_zk_node 等）。

## §6 zk_agent（节点执行端，Go）

- **形态**：Go 1.19；gin（HTTP）+ gRPC 经 **cmux 单端口（8383）** 复用；admin 9003；北极星服务发现。
- **五类职责**：
  1. **安全组下发**：从 zk_controller 拉 serviceSg → 落地 iptables + ipset（`ZK_INGRESS_QUORUM/ELECTION/CLIENT` 等链，保护 2888/3888/2181 端口）；
  2. **客户端限流**：连接总数 / SYN 速率 / 突发限制（持久化 `limit.cfg`）；
  3. **程序管理**：按指令启停 `zknode / zk_alarm / zk_monitor / differ / syncer / cos_bak`；
  4. **ZK 数据清理**：定时调 `tools/zkCleanupSafe.sh`（自管 ZK_HOME、**剥离主服务 java.env 注入的 308G ZGC JVMFLAGS** 再执行 PurgeTxnLog；角色白名单 + 灰度验证脚本）；
  5. **设备初始化 + 过载保护**：rsyslog/kernel notice 日志与 `/etc/rc.d/iptables/zk_agent/` 规则持久化；ESTABLISHED/CLOSE-WAIT 超阈值时 iptables 封禁客户端。

## §7 vpc_monitor（监控）

- **形态**：Python 2.6/2.7，epoll poller 多线程框架；进程名 `vpc_monitor`；crontab 每 3 分钟保活。
- **采集项**（22 个采集插件 + 子组件目录）：
  - ZK 本体：四字命令（`mntr/srvr/conf/cons/mnt2`）、jstat GC、Java 环境、连接数/连接分布、主从与选主状态、持久化一致性（acceptedEpoch/currentEpoch vs snapshot/txnlog）、**写时延探测**（watch/临时节点 + 快速写事务探针）、ICMP leader RTT；
  - 机器：CPU/内存/磁盘（psutil）；
  - 子组件：syncer / differ / cos_bak / account / zknode（各含全量指标字典 + 心跳 + 状态）。
- **上报**：barad（智研 HTTP）+ CMEM（memcache KV，大数据量旁路）。
- **告警**：阈值判断 + **收敛策略**（固定步进 / 倍增间隔）；心跳异常告警。
- **远程采集**：ansible（SSH）到集群其他机器取配置/状态（zk_book playbook）。

## §8 cos_backup（备份到 COS）

- **形态**：Java 11 fatjar（由 COS 团队开源迁移工具改造）；主程序 `com.qcloud.vpc.zk.cosbak`（V2），基座 `com.qcloud.cos.migrate.tool`（10 组 Task/Executor + RocksDB 迁移记录）。
- **V2 运行流程**：toml 配置 → 每个 `[[item_list]]` 一个 `ItemApp` 周期任务：
  ```
  Redis 抢角色锁（master 才执行）→ zkctrl 查询集群族/选中 ZK 节点
  → SSH 扫描 snapshot/txnlog 新文件 → 本地 caffeine + Redis 缓存去重
  → ExecuteTaskPool 并行"下载到临时目录 → 上传 COS（带 UserMeta）"
  ```
- **COS 约定**：bucket `${region}-vpc-${zkType}-${appId}`；生命周期 **10 天转归档 / 30 天删除**；SSM 托管 AK/SK（支持轮换）。
- **巡检脚本**：`config_scan_and_alarm.py` / `config_scan_and_fix.py`（扫各 region 备份配置中 ZK 节点可用性，告警/自动修复）。

## §9 族谱协作（一图）

```
                    zk_controller（管控面：资源/就近查询）
                        ▲                    ▲
              反向调用  │                    │ 反向调用
        ┌───────────────┘                    └──────────────┐
        │ zk_agent（节点执行：安全组/限流/程序/清理）          │ cos_backup（备份→COS）
        │                                                    │
   ┌────┴───────────────────────── 集群本体 ─────────────────┴────┐
   │  集群本体：3.4.6 同步器版（主→从同步） / 3.5.6 定制版（roll over、只读 OB） │
   └───────────────────────────┬─────────────────────────────────┘
                               │ 校验 / 监控
              differ（主备一致性校验）   vpc_monitor（指标+告警）
```

## §10 钩子

- 族谱概览（05 §4）→ 本篇是深读；设备侧如何挂接 ZK（the_2g）→ **04 §3 / 11 §4**。
- 与二代数据分发的关系：分区 ZK 是 binlog 的家（02 §5）；ZK 抖动 → 锁雪崩（03 §4）。
