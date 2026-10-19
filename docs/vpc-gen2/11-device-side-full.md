---
title: "11-设备侧全量：watcher/对账/主备与 DPDK 网关版"
series: "VPC 二代（Gen2）架构系列"
previous: "10-consistency-recovery.md"
next: "12-dataplane-protocol.md"
source_refs:
  - "vpc/docs-by-topic/28-vpc_agent（master 分支）.md（819 行全读）"
  - "vpc/docs-by-topic/14-agent_base（VPC Agent 基础框架）.md（215 行全读）"
  - "vpc/docs-by-topic/27-vpc_agent_dpdk_vpcgw（dpdk 分支 vpcgw 网关版）.md（499 行全读）"
---

# 11-设备侧全量：watcher/对账/主备与 DPDK 网关版

> 本篇是 09 篇的"全量版"：09 讲设备侧基础与 echo；本篇把 watcher 全清单、对账全清单、主备双层锁、DPDK 网关版差异一次补全。
> 标注：【实证】= 材料有据；【推导】= 按约束推导。

## §0 它干了什么（例子先行）

**一台母机 agent 的完整生命周期**：

```
① 启动：auto_config 探测角色/架构/ko 版本 → zk_validator 校验上游 ZK → ms_whitelist 判主备
        → fork watchdog（DPDK 版：worker / _check / _http / _monitor）→ zkconn 装 watcher 树 → 写就绪状态
② 运行：ZK 事件去重入队 → 串行/优先级处理 → port.py 门面 → vpcko(netlink) 或 PAL(DPDK)
③ 对账：inspector 周期调度 → kernel_check/vpcgw_check/the_2g_check 分项比对 → 修复
④ 倒换（DPDK 网关版）：备侧抢锁 → 持写锁 → do_switch 回放近期 binlog → 更新 HA 状态
```

## §1 掌握卡（6 条）

1. **三份代码三种形态**：`agent_base`（框架底座，pvgw 样例）/ `vpc_agent` master（母机+内核网关主力）/ `vpc_agent_dpdk_vpcgw`（DPDK 网关形态，独立演进）。
2. **进程拓扑**：watchdog 守护 worker 子进程（DPDK 网关版另有每分片 `_check` 对账进程，HTTP 65357/65358 与 `_monitor` 挂在最后一个索引）；DPDK 版默认 **16 个逻辑 worker 分片**（`(vpc_id % zk_vpc_id_group_num) % (max_procs-1)`），master 版为 watchdog + N 组 worker/inspector + monitor。
3. **主备双层锁**：包级 twins 文件锁（防双模块冲突启动）+ 每分片 MsGuard 逻辑锁（持锁者拿数据面写入权 `dynamic_netlink_state`）。
4. **事件调度两代两制**：一代 = EventHandlerAndMonitor 去重串行（state.mutex）；二代 DPDK 版 = **四级优先级队列**（High/Normal/Lower/Ignore）+ 线程池 + 倒换读写锁。
5. **下发只走一个门面**：port.py（装饰器 nl/batch/coexist…），底下三通道：netlink(vpcko) / PAL(TCP 127.0.0.1:6666 + protobuf) / netagent。
6. **对账是"一套调度 + 多套分项"**：inspector 调度 + kernel/host/vpcgw/the_2g 四类分项 + account 子包四种模式 + rtable 增量对账 + 手动触发入口。

## §2 三份代码的关系

| 代码 | 定位 | 关键差异 |
|---|---|---|
| **agent_base**（框架） | Python 2 通用底座：Agent 类（watchdog 拉起 N 组 worker/inspector + monitor）、ModuleMgr 模块加载、HTTP+sock（unix socket 内部 RPC）、zk 封装、account/inspector、monitor(mmap 汇聚)；自带 pvgw 样例 | worker 角色枚举 BUSSINESS/INSPECTOR/MONITOR；`/dev/shm/agent.db` |
| **vpc_agent master** | 母机 / 内核 vpcgw / NAT / MC_GW / CLASSIC_LINK / kernel pvgw 等（11 种角色自动识别） | 全量业务 watcher（§4）；HTTP 65357/65358 |
| **vpc_agent_dpdk_vpcgw** | Linux DPDK 网关（vpcgw）形态 | 16 分片 + 四级优先级 + 双包（vpc_agent/vpc_agent_bak）双层主备 + 二代存储迁移 watcher |

## §3 进程拓扑与启动

**master 启动流程**（28 §4.2）：
```
auto_config → SdnAgent.run()（daemonize + watchdog 文件锁）
→ zk_validator.is_correct_upstream_zk（防误连）
→ ms_whitelist.should_enable_ms（判主备）→ ZkSvrChooser 选主/备 ZK 地址
→ 每 multi_proc_entry：MsGuard 抢锁 → SQLite/LevelDB/ready-vpc mmap 初始化
  → rtable_processor 独立路由进程 → zkconn/zk_init 装 watcher → WebThread → inspector + sflow notifier
```

**DPDK 网关版启动**（27 §4.2）：`auto_config`（依据 DPDK pid 与 `/etc/gw/host.conf`）→ **wizard 阶段** `detect_remote_service.check_all_ready()` 检查一代 ZK/二代 CKV/分区依赖（不满足退出退避）→ fork watchdog ×16 → 每 watchdog 守护 worker + `_check`（备角色不对账）→ 末索引守护 `_http`/`_monitor`。

**状态与健康**：`/dev/shm/<proc>/<idx>` 写 `pid:ready:enable`（ha.py）；`get_status.py`、`check_agent_status.sh`、`check_start_log`、`health_check`（端口/进程数/版本）。

## §4 watcher 体系全清单

**基础设施**：`zkclient`（zkpython 同步+回调）→ `zkbase`（连接/PartitionZKConn）→ **`zkconn`（总装入口：按角色/特性/分片安装整棵 watcher 树）** + `zkbuild`（递归构建）+ `zkwatcher`（Child/Value/Both 基类 + ZkEventTimer）+ `async_zkclient`（去重入队/串行/DelayInfo）。

**一代业务 watcher（按域）**——组织模式是"领域 × 三件套（watcher / objects / check）"：

| 域 | 代表文件 |
|---|---|
| 路由 | rtable_watcher、rtb_watcher、custom_rtb_*、global_route_watcher、subnet_route_watcher、proto_map、whitelist_rtable/ |
| 地址对象 | eip_*、havip_*、tgw_vip_*、vip_zone、snat_ip_* |
| 网卡/主机/VM | eni_*、host_vif_*、host_config_*、gen_vm_from_vif、cvm_set |
| DHCP/组播/镜像/QoS | dhcp_*、group_dhcp_*、multicast/mc_gw/mcflag、sflow_*、mirror_*、dscp_*、lan_subnet_*、jumbo_*、diff_az_qos/ |
| 网关族 | nat_*、lbnat_*、igw、jns*、hbgw_*、pv_gw_*、svp_gw、cluster/zone_vpcgw、vpcgw_set_mgr、vpg_*、zone_vpc_peer、ccn_dc、region_link |
| IPv6 | ipv6_*（host/global_route/clb_vip/havip/rtb/service/session_rule 全套） |
| 规则/服务/追踪 | session_rule_*、binding/（规则引用）、vtrace_*、service_watcher、service_ipv6 |

**二代 the_2g 族**：`the_2g_watcher`（分区 ZK 选择、白名单/ReadyZk/HostVpc、KeepWatchVpc）、`the_2g_storage_watcher`（**新存储迁移感知**：ApplyNewStorage/NewPartition/NewObZk/NewReadyZk）、`the_2g_unwatch`（阈值批量 unwatch 冷 VPC，压 watcher 数量）、`the_2g_vpc_config`（binlog 使能开关）、`the_2g_zk/exception/op/op_check`。

**配置项框架**：`switch/`（ConfigItem 接口+存储+watcher）与 `group/`（中间节点→最终节点两级分组）——所有 `*_config_watcher` 的底座。

## §5 事件调度与处理（两代两制）

- **一代**：`EventHandlerAndMonitor`——以 `(handler, type, path, state)` 去重入队、串行消费、排队时延统计（DelayInfo；27 版口径补充：**集群队列串行、分区队列无锁并行**）；全程 `state.mutex`（rtable 有 5 worker 并行 + vpc_mutex 例外）。
- **二代 DPDK 版**：`control_rule_manager` + `local_ipc_syncer`（HTTP 进程收到控制规则 → Unix socket 同步各 worker → 重调度）→ `PriorityScheduler` 四级队列（**High/Normal/Lower/Ignore**）→ `PriorityWorkerPool` 消费；倒换用 `read_write_lock`（持写锁暂停普通分区事件）；**Ignore 事件仍重建 watch 并存 binlog**。
- **批量合并**：`batch/`（13 文件）——启动期 `flush_batch_req` 合并下发 + 各资源 BatchHandler（EIP/HAVIP/CLB VIP/子网路由/HBGW 路由/RS/网关实例）+ filter（启动期过滤无效对象）。

## §6 状态与数据面门面

- `state.py`（全局状态单例：连接/缓存/锁/开关）、`proc.py`（**期望状态生成器**：对账基准）、`port.py`（**唯一数据面门面**：装饰器 nl/batch/coexist/cache_gw_changed_vpcid）。
- **三通道**：
  - netlink：`netlink.py`/`genetlink.py`（vendored）→ `netlink_conn.py` 按属性模板构造 genl 消息 → vpcko；
  - PAL/DPDK：`vpc_adapter/` 族（host/vpcgw/pvgw/natgw/harp_service，各带 proto）→ TCP 127.0.0.1:6666 + protobuf；
  - netagent：`netagent.py` 及角色变体（vpc_gw/jns_gw）。
- **版本门控**：`support_check`（is_config_ko_support / is_config_dpdk_support——按 ko/dpdk 版本决定特性可下发）；`no_ko`（特定 VPC 跳过内核）；`rtable_delivery_control`（等 lw agent ready 再放行路由）。
- **Adam 名字服务**：`adam.py`（AdamHost/Tas/Sticker/Tns/TnsWeight → 维护 SIP-VIP-RS 映射与权重）。

## §7 对账体系（agent 侧全清单）

| 层 | 件 | 干什么 |
|---|---|---|
| 调度 | `inspector.py`（InspectorThread/VpcGwInspectorThread） | 周期巡检调度；DPDK 版另有 `fix_manually.py` + HTTP do_check/full_validate_status 手动触发 |
| 内核 | `kernel_check.py` | check_by_vpc / non_lock_check / learn route / gw / hbgw qos 分项 |
| 母机 | `host_check.py` | netagent/dpdk 的 sip/service/route/rs 修复 |
| 网关 | `vpcgw_check.py` + `vpcgw_proc.py` | do_vpc_check 及各表二次复核（_again 系列）；期望生成 |
| 二代 | `the_2g_op_check` + `vpc_adapter/*/the_2g_check` | do_whole_check/do_rtable_check/do_svc_check；CheckCache 缓存比对（VpcgwRouteTableCheck 等） |
| account 子包 | original（全量）/ light_weight（轻量）/ no_state_mutex（不持锁）/ vpc_account_handler（各维度） | 四种对账模式 |
| 路由增量 | `rtable_account/` | AccountManager：变更 IP 收集 + 全量对账标记；KoRtableGenerator/TvsRtableGenerator 生成期望 |

## §8 主备与倒换（双层锁 + 回放）

```
状态文件: /dev/shm/<proc>/<idx> = "pid:ready:enable"（ha.py HAStatus 读写）
双层锁:   twins.py 包级 twins_lock_file（防双模块冲突启动）
        + MsGuard 每分片 master_slave_lock_file % proc_idx（持锁者置 dynamic_netlink_state）
监控:     guard.py（GuardThread 周期检查）/ switch.py（SwitchThread 触发备唤醒/切换）
倒换:     备侧取得锁 → read_write_lock 写锁 → syn_op.do_switch() 回放近期二代 binlog
        （CFastPath/OpData/BinLogSubOp/CVpcSyn 族）→ 更新 HAStatus
启用判定: ms_whitelist.should_enable_ms（ZK 白名单 + host_ip）
ZK 选择:  zk_svrs_chooser（主/备各自 ZK 地址）；zk_session_health_check（会话健康）
重启:     vpc_agent_twins_restart（先备后主，可重入；shell 版 sleep 4h、py 版约 1 小时后自动拉起对端）
```

## §9 存储与观测

- **存储**：SQLite（peewee；master：AgentState/HostInfo/The2GVpc/RouteTableSnapshot/Binlog；DPDK 版另有 BinlogZkHasWatched/Partition/NewPartition/ReadyObZk/DpSet/VipSet/LocalVpcSet/SnapshotVersion/RemoteVpcSet，binlog 库为 WAL 模式）；LevelDB（local_db + rtable_storage）；`/dev/shm` 共享内存 + mmap（ready-vpc 实时通知、echo）。
- **观测**：`tslog/`（SQLite 指标表：account/dp_op/echo/error/event_handle/http_api + prometheus 导出）；`monitor.py`（mmap 汇聚 ZK 延迟 + echo 上报）；echo 四件套（kicker_config/echo_kicker/echo_thread/echo_watcher，见 09 §5）；`qcloud_alarm`（云平台告警队列）；logger（压缩滚动 + critical 计数）。
- **HTTP 运维**：version/check_start_log/get_agent_state/ruok/proc_stats/state_dump/dump_memory/zk_check/state_check/gateway/vm；DPDK 版另有 do_check/full_validate_status/check_vpc/get_vpc_version/clean_vpc_version/murder。
- **工具**：tools/——远程 shell 注入（remoteshell/pyrasite）、check_zhandle（ZK 句柄检查）、kill_check_pid（杀卡住的 _check）、proc_status、vpc_agent_pid_monitor、ZK 造数（zktool）。

## §10 边界与钩子

- 数据面**转发面本体**（vpcko 内核模块、DPDK 转发进程）不在本工作区；PAL 协议细节以 `vpc_adapter/*.proto` 清单为界（09 §4）。
- echo 机制细节 → **09 §5**；二代消费（the_2g 与 broker 的关系）→ **04 §3**；对账闭环全景 → **10 §2**。
