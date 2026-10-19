---
title: "09-设备侧 vpc_agent 与 echo 时延监控"
series: "VPC 二代（Gen2）架构系列"
previous: "08-agent-broker-internals.md"
next: "10-consistency-recovery.md"
source_refs:
  - "vpc/docs-by-topic/28-vpc_agent（master 分支）.md"
  - "vpc/VPC控制面.md（Agent 层 / echo mmap 章 / 修正清单）"
  - "vpc/test-good/00-background/04-tencent-infrastructure.md"
---

# 09-设备侧 vpc_agent 与 echo 时延监控

> 标注：【实证】= 材料有据；【推导】= 材料无实现细节，按约束推导。

## §0 它干了什么（例子先行）

同一台母机上的 vpc_agent 做三件事：

```
① 启动：auto_config 探测（网卡/可用区/角色/架构/dpdk 版本）→ 确定 play_role（如 HOST）
② 运行：watch 配置（一代 ZK / 二代分区 ZK / broker 心跳）→ 全局锁串行处理事件
        → 数据面三通道下发（netlink vpcko / DPDK PAL / netagent）→ 对账
③ 健康：echo 探针（每 5 分钟写 /TVPC/ECHO/HOST|GW）→ 各 worker 记录时延到 mmap
        → Monitor 汇总上报 → 超阈值告警
```

## §1 掌握卡（5 条）

1. **单进程多角色**：play_role 自动识别（HOST / kernel vpcgw / MC_GW / NAT / CLASSIC_LINK / kernel pvgw / IGW / JNSGW…，共 11 种角色）。
2. **watcher 体系**：ChildWatcher（子节点列表）/ ValueWatcher（节点值）两个基类 + 业务 watcher（Host / VpcNode / Rtable / Rule / Vif…）。
3. **事件串行**：state.mutex 全局锁；rtable 例外（5 worker 并行 + vpc_mutex——修正版口径）。
4. **数据面三通道**：netlink（vpcko 内核）/ DPDK（PAL：TCP 127.0.0.1:6666 + protobuf）/ netagent（本地服务间接下发）。
5. **echo 是"静默故障探测器"**：ZK watch 可能"连接在、watch 丢"——echo 任务触发所有 agent 回调，用处理时延暴露异常；数据落 mmap 固定槽位（1MB，无锁）。

## §2 角色与启动

- 构建：`bin/init.sh`（解压自带 Python 2.7）+ `bin/start.sh`（按 CPU 亲和 taskset 拉起）。
- 启动探测（auto_config.py）：网卡/网络配置、可用区、角色（HOST/VPCGW/LBNATGW/JNSGW…）、架构（x86/arm）、dpdk/vpc ko 版本 → 写 config。
- 【推导】为什么"按内核模块探测角色"而不是配置声明：同一份镜像部署到不同设备（母机/网关/…），探测 = 零配置且不会配错；代价是启动多一次探测逻辑。

## §3 watcher 体系与事件处理

| 层 | 件 | 说明 |
|---|---|---|
| 基类 | ChildWatcher / ValueWatcher | 子节点列表变更 / 节点值变更；封装 exists/get_children 的 watch 重挂与事件过滤 |
| 一代 ZK | HostWatcher、VpcNodeWatcher（12 属性叶子）、RtableVmWatcher、RtableSubnetWatcher、RuleSnetWatcher、NatHostWatcher… | 对应 /TVPC/ 各树 |
| 二代 | the_2g_watcher（分区 ZK 选择、白名单/ReadyZk/HostVpc） | 见 04 §3 |

- **事件处理**：EventHandlerAndMonitor 异步队列（去重）串行执行，全程持 state.mutex。
- 【推导】为什么要全局锁：同 VPC 的"删路由 A + 加路由 B"若并发下发到内核，执行序可能颠倒（材料例子：期望 10.0.0.2，实际无路由 → 路由黑洞）——锁把事件序列化，用吞吐换确定性。

## §4 数据面三通道（边界：数据面本体不在本工作区）

| 通道 | 协议 | 对象 |
|---|---|---|
| netlink | generic netlink（TVPC family） | vpcko 内核模块（GRE + LPM 路由） |
| DPDK | TCP 127.0.0.1:6666 + protobuf（PAL） | 用户态转发（网关型） |
| netagent | 本地服务 | 间接下发 |

- tvpc.py：vbr/gre 设备创建销毁（iproute2）、add_if_to_vbr、setup_vpc_needed_vbr_gre。
- tvpc_acl.py / tvpc_cmd.py：ACL 下发封装 / 命令校验与应答填充。

## §5 echo 时延监控（材料 + 推导）

**材料**：
- echo_kicker：每 5 分钟向 `/TVPC/ECHO/HOST|GW` 写探测任务。
- mmap：`/dev/shm/vpc_agent_echo_shared_mem`，1MB = 1024×1024；每进程槽位 1KB；Worker 0-15 在 [0,16KB)；Slave Worker 从 100KB 起（bak_proc_idx_start=100，Master 0-15 / Slave 100-115）；二代 VPCGW Worker 16-31 用偏移 (idx+16)×1024。
- 写入：JSON 序列化 + 空格填充到 1024 字节；`DelayInfo.watcher_delay_info['thread_status']` 随 echo 上报；某线程超过 2×echo_cycle+60s（如 660s）未上报 → 告警。

**推导（为什么是这套设计）**：
- **为什么需要 echo**：ZK watch 是一次性触发——重挂失败/静默丢事件时连接仍在，系统"看起来正常"但配置已停更。echo 制造一次"必达的变更"，把静默故障变成可观测的时延指标。
- **为什么用 mmap 而不是 ZK/DB/MQ**：16+16 个 worker 每 cycle 高频写、Monitor 高频读；走网络/存储太重。固定槽位 mmap = 写方覆盖、读方直读，零锁零拷贝（L0/L1 约束下的最省方案）。
- **为什么 1KB 固定槽**：JSON 定长填充（截断可检测）——无锁读必须防"读写交错"，固定布局 + 填充是最便宜的解法。
- **为什么 Master/Slave 偏移隔离（0-15 vs 100-115）**：主备同机同时写，用不同槽区避免互踩；Monitor 按 process_index 找槽。
- **为什么 kicker 是"写 ZK 任务"而不是 agent 自报**：探测必须走真实数据路径（ZK → watch → 处理），才能测出"watch 是否真的在工作"。
- **槽位边界【推导】**（证伪检查补充）：
  1. **超长截断**：材料实现是 `json_str.ljust(1024)[:1024]`——JSON 超 1KB 直接截断；读方解析失败即视该槽无效（截断即失效，不会读出错误 JSON）；
  2. **撕裂读**：无锁覆盖下可能读到"旧尾 + 新头"的混合内容——JSON 结构强约束下"解析成功但语义错"概率低，Monitor 按异常值过滤；
  3. **容量核算**：1024 槽 × 1KB = 1MB；Master 0-15（16KB）、二代 VPCGW 16-31（至 32KB）、Slave 100-115（100~116KB）全在界内，余量充足。

## §6 主备与恢复（简表，详见 10）

- 同机双进程主备：fcntl.lockf 文件锁 + ha.py 仲裁（已知 4 个竞态，靠 ha.py 兜底）。
- 锁丢失 → suicide → watchdog 拉起 → 重建 ZK 连接。
- 网关主备倒换：do_switch 回放近期 binlog（04 §3）。

## §7 钩子

- the_2g 消费、白名单切换 → **04**。
- 对账闭环、恢复链、回退 → **10**。
- 设备侧全量（watcher 全清单/对账全清单/主备双层锁/DPDK 网关版）→ **11**。
