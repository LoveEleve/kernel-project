---
title: "17-运维与监控：Echo / Watchdog / 指标 / 告警 / 诊断（Ops 视角）"
series: "VPC 二代（Gen2）架构系列"
previous: "16-open-source-comparison.md"
next: "18-first-principles.md"
source_refs:
  - "vpc/docs-by-topic/06-VPC 控制面补充架构文档：监控、运维、进程管理.md（690 行全读）"
  - "vpc/docs-by-topic/07-VPC 控制面——三代架构代码级对比（Review 修正版）.md（修正清单 17 条）"
---

# 17-运维与监控：Echo / Watchdog / 指标 / 告警 / 诊断（Ops 视角）

> 前 16 篇讲机制与业务；本篇补"养系统"的视角：探活（Echo）、进程监督（三层 Watchdog）、指标（tslog）、告警（三层）、诊断（SOP/接口/日志）。
> 标注：【实证】= 材料有据；【推导】= 按约束推导；vpc5.0 对比项标【vpc5.0】。

## §0 它干了什么（例子先行）

凌晨告警：ECHO 延迟 600s（21 台 VPCGW）——运维按 SOP 定位：

```
① 看 play_role：part_zk / bak_part_zk → 二代链路
② 看 Agent 本地指标：event_large≈0 / queue≈0 / restart≈0 → 不是 Agent 的锅
③ 查控制器侧：Kafka lag → nfv-proxy → 分区 ZK → CRS → 发现 Topic 缺 Partition
④ ★ 不要重启 Agent（不是它的问题）
```

这条 SOP 背后的整套体系（探针怎么发、指标怎么收、告警怎么分、进程怎么被守护）就是本篇。

## §1 掌握卡（6 条）

1. **Echo = 控制面主动注入探针、Agent 被动响应**：Kicker 写**一个** ZK 节点（广播），十万台 Agent 不逐个探测——避免上报风暴。
2. **三层 Watchdog 链 + "自杀+重启"哲学**：daemon（进程）→ service（主备锁）→ kernel_check（交叉检查）；任何一层卡死都会被发现；重启退避 random 1-20s。
3. **mmap 双通道**：数据走共享内存（1MB / 每进程 1KB 槽位，主备偏移隔离），信号走 Unix Socket（注释："mmap will replace this usock. But leave it as a backup"）。
4. **tslog**：8 类 logger → SQLite → Prometheus 文本；【vpc5.0】Go 侧 resettable 模式（30s gather + push 失败不 reset）。
5. **告警三层**：主动推送（星云）→ CLS 聚合 → 大盘（Barad）；ECHO 阈值 310s。
6. **诊断四件套**：HTTP 65358 四步检查 / 五类日志 / 排查矩阵 / ECHO SOP。

## §2 Echo/Kicker（探活体系）

- **Kicker（独立守护进程，不在 Agent 内）**：每 `echo_cycle`（默认 300s，最小 `MIN_ECHO_CYCLE=10s`）写 JSON 探针到 `/TVPC/ECHO_V2/HOST`（母机 Agent 监听）与 `/TVPC/ECHO_V2/GW`（网关）——**同一节点广播**（利用 ZK Watch 一对多），字段含 `timestamp`（核心）/echo_cycle/warning_time_out/report_cycle/range_step 等。
- **EchoWatcher（Agent 内）**：`cost = now - timestamp` = ZK 写入 + Watch 通知 + 事件队列排队 + Watcher 回调；动态更新周期与分桶梯度（**不重启生效**）；写 mmap + Unix Socket 通知 + tsLogger。
- **DelayInfo 分桶**：两类延迟（q_event 排队 / event 处理）× 三档（small/mid/large；`range_step` 默认 1s 可动态调）；额外统计 max_event / max_q_event / max_delay_queue_size / thread_status。
- **数据流**：Kicker → ZK → EchoWatcher → mmap(`/dev/shm/vpc_agent_echo_shared_mem`, 1MB) → AgentMonitor → **HTTP HEAD**（指标全在 Header）→ Nginx access log → CLS。
  - 为什么 HEAD 不是 POST JSON：Nginx 无需解析 body、直接记 access log；计算在 CLS 侧做。

## §3 进程监督（三层 Watchdog 链）

```
Layer 1  daemon.py（最外层）:
  fork/waitpid；子进程退出 → 随机退避 1-20s 重 fork（防多机同时重启）
  重启风暴检测: 5 分钟内 >10 次 → 告警（restart_times>=10 && recent_interval<=300）
  max_fork_times: Master/Slave=5；Monitor=-1（无限）；rtable=5
    超限 → 永久退出（不再重启）；Monitor 无限重启兜底
Layer 2  service.py（Master/Slave 主备）:
  fcntl.lockf 文件锁 /var/run/vpc_agent_ms_lock；每 3s 查锁
  Master 死 → 释放锁 → Slave 抢到 → s2m_cb_func():
    启动 HTTP 服务 + 重新拉 ZK 全量 + 做一次全量对账
  check_peer: 主备都超 max_fork_times 才全部停止
Layer 3  kernel_check.py（交叉检查）:
  rtable: ppid==1（父进程已死）→ suicide()
  Master/Slave: rtable 进程不在 → suicide()
  哲学: 任何对端进程消失 → 自杀让 Watchdog 重启整套
```

- **为什么"自杀+重启"而不是"进程内恢复"**：状态从头开始，不存在重组期间的事件一致性难题；代价=短暂不可用（主备秒级切换兜住）。
- **线程级**：ThreadingHealthMonitor 监控 6 类线程（alarm / inspector / event_scanner / zk_event_timer_0..N）；阈值 `2×echo_cycle+60`（默认 660s）；rtable worker 注入探针验证存活。
- 【vpc5.0】Go Watchdog：PID + HTTP 双检查（PID 存活≠服务健康）；cgroup 限制（防泄漏吃光整机）；`total_restart_count` **永不归零**（健康度关键指标）。

## §4 指标（tslog）

- **Python Agent**：8 类 logger（common / account / dp_op / echo / error / main_event_handle / rtable_event_handle / http_api）→ SQLite `ts_{role}.db`（每表 max 1×10000）→ MetricsExporter 每 60s → Prometheus 文本（`main_processor.metrics`）。
- **指标清单**：echo_latency / zk_event_process_latency+cost+count+queue_len / rtable_event_* / http_api_cost+error / start_time_cost / zk_get_cost。
- 【vpc5.0】**resettable 模式**（生产默认）：30s gather → datasyncer ReportMetrics RPC → vpc-broker → JSON 日志 → CLS；**push 失败不 Reset**（下一轮带走累计值，spec 025 修复）；broker 三种 reporter（noop / remote_write / log）+ 限流（500 数据点/1000 日志行每批）+ promoted_labels（vpc_id/host_ip/config_type）。

## §5 告警（三层）

```
Layer 1 主动推送（Worker → 星云）: qcloud_alarm.tvpc_alarm()
  60s 聚合 + Set 去重；high_priority→电话 / normal→企微
  触发: Watchdog 5min>10 次 / ZK 断开超阈 / 角色识别失败 / 关键模块加载失败
Layer 2 CLS 聚合:
  echo_delay > 310s / 10min 内上报进程数 <16 / 差异率 >5% 持续 10min / Kafka 错误率 >1%
Layer 3 大盘（CLS Dashboard → Barad）:
  快照构建失败率 >10% / Leader 选举失败 / 分布式任务队列积压 >1000
```

## §6 诊断（SOP + 接口 + 日志）

- **ECHO 告警 SOP**：先看 `play_role`——part_zk/bak_part_zk → 二代链路（看 event_large/queue/restart → 均正常则查控制器侧：Kafka lag / nfv-proxy / 分区 ZK / CRS → **不要重启 Agent**）；否则走一代链路（ZK 连接 / Watcher 队列 / 关键路径延时）。
- **HTTP 管理接口（65358）四步**：`/check_start_log`（最长等 40 分钟——大 VPC 冷启动）/ `/zk_check` / `/state_check` / `/ruok`。
- **日志**：sdn.zlog（路由处理，最重要）/ http.zlog / check.zlog（对账）/ watchdog.zlog（频繁重启）/ monitor.zlog / tslog/*.db / metric/*.metrics。
- **排查矩阵**：ECHO>300s→Kafka partition 缺失/ZK 断开/OOM；路由下发失败→netlink 超时/内核模块不兼容/配置格式；频繁重启→ZK 不稳/OOM/cgroup 过低；对账持续差异→内核 bug/时序竞争/并发修改；新 VPC 不生效→检查灰度白名单。

## §7 与事故库的呼应

- **#22（Kafka partition 缺失 → 600s）**：SOP 的反面教材——告警只说"delay 超标"，后来固化"告警附带判断维度"（15 §8）。
- **#3（recovery_mode）**：运维变更前后要检查 recovery_mode 标记，必要时手动清共享内存（15 §4 模式 3）。
- **关键数字**：Echo 300s（最小 10s）/ 告警阈值 310s / mmap 1MB / tslog 10s / Prometheus 60s / 重启退避 1-20s / 重启告警 5min>10 / 主备检查 3s / 线程超时 660s / 告警聚合 60s / Broker session TTL 30min【vpc5.0】/ 12h 拒写 / metrics 限流 500+1000。

## §8 钩子与边界

- echo 在设备侧的消费细节 → **09**；检测盲区与重启经济学 → **15 §6**；ZK 监控族（vpc_monitor）→ **13**。
- 本篇为 Python Agent（Gen2 设备侧）+ vpc5.0 对比口径；07 修正清单的 Gen1 细节（ZkEventTimer 5 worker 并行、level_db、ZK 探活吞异常等）未纳入——与二代主线无关。
