---
title: "15-难点地图与生产事故库：22 起事故 × 7 类难点 × 5 种根因模式"
series: "VPC 二代（Gen2）架构系列"
previous: "14-business-scenarios.md"
next: "16-open-source-comparison.md"
source_refs:
  - "vpc/docs-by-topic/11-VPC 控制器——生产环境血泪教训.md（541 行全读：22 起事故/根因分布/检测盲区/重启经济学/冷数据/防御反噬/性能数字）"
  - "vpc/test-good/07-advanced/incident-vpcId-collision.md（279 行全读：2026-02-03 广州 P0）"
  - "vpc/test-good/07-advanced/failure-mode-matrix.md（166 行全读：历史 Bug 表——含 vpc5.0 组件口径，引用时已分代）"
  - "vpc/test-good/07-advanced/tradeoff-analysis.md（223 行全读：ZK Lock vs DB Lock 为二代口径）"
  - "vpc/test-good/01-zk-distributed/kafka_consumer.md（195 行：假死修复=二代 nfv-proxy，+121/-3）"
  - "vpc/test-good/01-zk-distributed/syncer_skippath_fix.md（297 行：skipPath 泄漏=二代 ZK 同步器，+144/-87）"
  - "vpc/test-good/01-zk-distributed/db_dlock.md（259 行全读：ZK→DB 锁迁移——三阶段灰度/DualLock/advisory lock）"
  - "vpc/test-good/07-advanced/system-design.md（771 行；§5 容量数字为设计推演口径，已标注）"
---

# 15-难点地图与生产事故库：22 起事故 × 7 类难点 × 5 种根因模式

> 本篇回答"面试官更关心的东西"：这个系统难在哪、踩过哪些坑、每个坑的解法与代价。事故素材为控制器 2025-2026 生产复盘（二代时代）。
> 标注：【实证】= 材料有据；【推导】= 按约束推导；【vpc5.0】= 材料为三代口径，引用时已标注。

## §0 它干了什么（例子先行）

**一次"切换"引发的三类难点**——2025-08-18 广州二代切换（事故 #6）【实证，T+ 为相对时刻】：

```
T+0      旁路对账（连续多周期）发现大量设备配置不一致：152 台 VPCGW + 30 台 LBNATGW OOM
T+38min  确认告警（38 分钟检测延迟）
         根因：主 Agent 一边回放本地 SQLite 最近 10 分钟 binlog，
               一边消费 ZK 实时消息直接下发；
               两条路径用两把不同的锁（state.mutex vs state.do_2g_watch_mutex）
               → 同一表项：实时先下发大版本，回放用同一 key 但版本更小 → 被跳过
               → 152 台设备漏下发
         同时：855 台设备同时启动 + 全量对账 → CRS Proxy CPU 98% → 扩容
T+3h10m  扩容 CRS + 人工修复不一致，恢复
```

一次"切换"引出了三类难点叠加：**并发正确性（两把锁）、容量风暴（对账惊群）、检测延迟（38 分钟）**。这就是本篇的结构：难点地图（§2）→ 事故全景（§3）→ 根因模式（§4）→ 深读（§5）→ 盲区与重启（§6）→ 防御反噬（§7）。

## §1 掌握卡（7 条）

1. **难点 7 类**：规模×正确性 / 任务调度可靠性 / 分布式协调 / 一致性治理 / 演进迁移 / 数据面正确性 / 可观测与静默故障。
2. **事故 22 起**：并发 23%、状态管理 18%、依赖容量 18%、防御缺失 18%、安全机制自伤 14%、配置错误 9%。
3. **5 种根因模式**：回放与实时并发、check-then-act 非原子、防御机制反噬、检测盲区、外部输入不校验。
4. **检测是最大短板**：告警只覆盖 40% 故障场景；最长的坑 12 天才发现（GWLB rtable）。
5. **重启是核武器**：VPCGW 重启恢复 30-40 分钟；手册写"非必要不重启"；优先 do_check → full_check → 等重连。
6. **每个防御都是双刃剑**：删除保护误伤正确恢复（#10）、recovery_mode 回退自伤（#3）、对账既救命又炸弹（#8/#14/#16）。
7. **冷数据是 Gen1 之痛**：85-94% VPC 无子机但全量维护 Watch——Gen2"只推活跃"是被 5000 万次推送打出来的。

## §2 难点地图（7 类：为什么难 → 二代解法 → 代价 → 翻车锚点）

【推导，锚点【实证】】：

| # | 难点 | 为什么难（规模算术） | 二代解法 | 代价 | 翻车锚点 |
|---|---|---|---|---|---|
| 1 | 规模 × 正确性 | 200 万 VPC × 50 配置=1 亿条；1 条策略×100 VM=100 条转发；百万变更→千万级 push | 分区 ZK + binlog 增量 + 快照冷热分离 + broker 扇入 | 最终一致窗口；对账扫描接近瓶颈（200 万/166 个/s≈3.3h） | Gen1 1TB 惊群；删 10000 IP→5000 万推送 |
| 2 | 任务调度可靠性 | merge master/worker 单实例 + 锁 + 限流分发；进程假死无信号 | master 单实例+心跳锁；计数限流分发；worker×5 rpop | 锁本身是单点；假死检测要自杀重启 | ZK 抖动→9 个 proxy worker 自杀；Kafka 假死 600s |
| 3 | 分布式协调 | ZK session timeout 400s；网络分区下 AP 锁不互斥 | ZK→DB 锁迁移（心跳 15s+socket 5s→35s 检测）；LeaderLatch 主备 | 822 行迁移代码；DB 成新依赖 | ZK Lock 雪崩（listener LOST→suicide 全量雪崩） |
| 4 | 一致性治理 | 删除不可逆；差异是瞬态还是真不一致难判断 | 四层对账 + 版本比对 + 12h 拒写 + 删除保护 | 保护误伤 vs 误删两难；对账中间态 | #10 保护误伤；#18 误删 21min；#8 -1 中间态 |
| 5 | 演进与迁移 | Gen1/Gen2 共存；切换要做全量 diff；回退要保命 | 白名单（2GNODE/2GSERVICE/2GHOST）+ 全量 diff 以二代为准 + recovery_mode | recovery_mode 持久化共享内存，触发后不清除 | #3 回退自伤 90 分钟 |
| 6 | 数据面正确性 | GRE 字段复用=语义重载；ARP 代理/路由展开/限速全自研 | GWMAC 模逆确定性算法；字段复用；六步排障法 | 校验缺失即写穿（0.0.0.0） | #19 全量子网路由清零；#18 MAC 错误 |
| 7 | 可观测与静默故障 | 故障=进程活着但不再工作；告警只说"超标"不说"哪里" | ECHO（端到端心跳）+ 对账 + 巡检 + 新告警逐案补齐 | 反应式治理：永远出了事再加监控 | 假死 600s；#12 12 天；#20 23 小时；#7 5 天 |

## §3 22 起生产事故全景（2025-06 ~ 2026-06）

【实证】原表收录：

| # | 日期 | 故障 | 影响 | 根因类型 | 检测延迟 |
|---|---|---|---|---|---|
| 1 | 2025-06-26 | classiclink 母机启动失败 | classiclink 全量 | 环境兼容 | 立即 |
| 2 | 2025-08-04 | ZK leader 故障→szsycft 双断 | 财付通断网 | 主备隔离缺失 | 事后发现 |
| 3 | 2025-08-12 | 封禁触发回退一代→ECHO 告警持续 | ECHO 监控失效 | recovery_mode 自伤 | 约 1h |
| 4 | 2025-08-13 | ARM 平台 dhcpip 失败 | 鲲鹏母机 | 机型识别错误 | 偶发 |
| 5 | 2025-08-14 | VM ready 误判 | EKS 容器不通 | ready 语义过宽 | 立即 |
| 6 | 2025-08-18 | 广州切换二代→152 台设备告警 | 152 VPCGW + 30 LBNATGW OOM | 回放与实时下发并发 | 38 分钟 |
| 7 | 2025-09-09 | CDC proxy 连接泄露 | 多台机器配置失败 | FIN 丢失→连接池耗尽 | 5 天 |
| 8 | 2025-09-17 | QoS 对账下发 -1 | 客户内网中断 6 分钟 | 对账修复引入中间态 | 6 分钟 |
| 9 | 2025-09-19 | 子网路由删除阻塞新增 | 跨域 21 秒超时 | 删除耗时放大 | 直接感知 |
| 10 | 2025-10-15 | ZK 孤岛→阈值保护阻止修复 | 客户 NAT 路由残留 | 安全机制误伤 | 事后排查 |
| 11 | 2025-11-02 | CDC 子网路由缺失 | CLB 转发异常 | 下发筛选条件错误 | 用户报障 |
| 12 | 2025-11-03 | GWLB rtable 缺失 | 无（无客户使用） | more/less 污染+unwatch bug | 12 天 |
| 13 | 2025-11-12 | TGW VIP 切换→CDB 对账超时 | CDB 切换变慢 | 事件队列堆积 | 告警发现 |
| 14 | 2025-11-17 | QoS 持续对账误报 | 反复对账不一致 | 语义不一致 | 持续 |
| 15 | 2025-12-02 | TGW→vpcgw-agent 延迟 | PaaS 不通 | 配置延迟 | 告警发现 |
| 16 | 2025-12-04 | vpc-agent 内存泄漏 | 母机 OOM | 外部数据读取死循环 | 逐步恶化 |
| 17 | 2026-03-13 | VM ready 提前 25 秒返回 true | 用户不通 | ready 不校验完整性 | 日志复盘 |
| 18 | 2026-03-25 | 远端 VPC 误删→HAVIP 中断 21 分钟 | 断流 21 分钟 | 删除保护缺失+MAC 错误 | 7 分钟感知 |
| 19 | 2026-05-26 | OSS 回滚写 0.0.0.0→全量子网路由清零 | 单母机全断 | 输入校验缺失 | 日志复盘 |
| 20 | 2026-06-03 | IPv6 USG 规则未随 VIF 上线 | TCP 不通 23 小时 | 事件处理路径错误 | 23 小时 |
| 21 | 2026-06-05 | pop/get 顺序 bug→sflow 丢失 | 流日志缺失 | 数组操作顺序错误 | 2 天 |
| 22 | 2026-06-12 | Kafka 主备切换→Topic 缺 Partition | 21 台 600s echo delay | 依赖未校验 | 8 分钟 |

**根因分布**（源文口径）：并发问题 5 起（23%：#6/9/12/18/21）；状态管理 4 起（18%：#5/8/14/17）；依赖/容量 4 起（18%：#2/7/13/22）；防御缺失 4 起（18%：#1/4/19/20）；安全机制自身故障 3 起（14%：#3/10/16）；配置错误 2 起（9%：#11/15）。

> 注：源文分类个别偏宽（如 #16 内存泄漏归入"安全机制自身故障"），按每行"根因类型"列为准。

## §4 根因模式与代码级教训

【实证】源文提炼的 5 种模式 + 4 段代码：

### 模式 1：事件处理并发——"add 被 eat"

```
#6：回放（本地 SQLite 最近 10min binlog）与实时（ZK 消息）两条路径
     用两把不同的锁（state.mutex vs state.do_2g_watch_mutex）
     → 实时先下发大版本，回放小版本同 key 被版本比对跳过 → 漏下发
#21：subeni 先删后加 → add_node 中 pop 先于 get → TypeError → 流日志丢 2 天
```

```python
# 事故 #21：错误代码（先 pop 后 get）
if vif in state.delay_del_enis:
    state.delay_del_enis.pop(vif, None)              # pop 先执行
    state.vif_container.rm(
        state.delay_del_enis.get(vif)[2]             # get 返回 None → TypeError
    )
# 正确代码（先 get 后 pop）
if vif in state.delay_del_enis:
    eni_info = state.delay_del_enis.get(vif)
    state.delay_del_enis.pop(vif, None)
    if eni_info:
        state.vif_container.rm(eni_info[2])
```

### 模式 2：状态检查与修改不在同一原子操作

```python
# #5 / #17：ready 只检查"记录存在"，不检查"记录完整"
def _vm_is_ready(vm):
    return vm in state.vms and group_exists(vm.group_id)
    # 缺少 vm_completely(vm)：vmip + mac 都存在
# #17：host 树比 vm 树先处理完 → MAC 节点还是 NoNode → ready 已返回 true（提前 25 秒）
```

### 模式 3：安全机制在自己的恢复路径上成为绊脚石

```
#10：ZK 孤岛恢复 → 对账发现大量多余路由 → 删除保护阈值触发（"一次删太多，拒绝执行"）
     → 阈值阻止了正确的恢复 → 孤岛残留到人工重启
#3 ：封禁 374s > 回退阈值 200s → recovery_mode=True 写共享内存 → 自杀重启
     → 重启后因 recovery_mode 回退一代 → 解封后标志不清除 → 必须重启守护进程
     → 14 批 × 5 分钟 ≈ 90 分钟恢复
```

### 模式 4：外部输入不校验（truthy 陷阱）

```python
# 事故 #19：OSS 热迁移回滚把 /TVPC/HOST/hostIp 写成 "0.0.0.0:0.0.0.0"
if not value:                     # "0.0.0.0:0.0.0.0" 是 truthy → 不拦截
    return
pv_gateway = value.split(':')[0]  # → "0.0.0.0" → update_pv_gateway → 全量 vpc0 路由清零
# 同期 rtable_host_watcher.py 已加 if vpcgw_val == DEFAULT_IP 保护，zkconn.py 没有
```

### 模式 5：依赖未校验 / 静默故障

```
#22：Kafka 主备切换后 Topic 缺 Partition → 21 台 VPCGW echo delay 600s
     告警只说"delay 超标"，正确排查是看 Agent 本地指标（event_large≈0/1、max_queue_size≈0/1、has_restarted=0）
     → 排除本机问题 → 查 Topic 完整性
#7 ：CDC proxy FIN 丢失 → 连接池耗尽 → 容器监控 5 天前异常，5 天后才定位根因
```

### 历史 Bug 表（跨代，已分代标注）

【实证 + 分代】failure-mode-matrix §2：

| Bug | 发现方式 | 根因 | 修复量 | 代际 |
|---|---|---|---|---|
| skipPath 状态泄漏 | 监控发现不该同步的路径被同步 | 重新初始化没清空 skipPath set | +144/-87（vpc_zookeeper_3_4_6_synchronizer） | 二代 |
| ZK Lock 雪崩 | ZK 抖动后所有 Worker 同时死 | listener LOST→suicide；timeout 400s | 822 行（db_dlock 迁移） | 二代 |
| Kafka Consumer 假死 | 小时级消息不消费 | `for record in kc` 无限迭代器阻塞，无法心跳 | +121/-3（nfv-proxy） | 二代 |
| VPCGW 切主漏 VPC | 切主后部分 VPC 不通 | do_switch 没回放 VpcBinlog | 4 行 | 二代 |
| DeleteConfig Put(nil) | 对账 Redundant 增长 | LevelDB Delete 语义错误（Put→Delete） | 1 行（commit 430b5ab） | 【vpc5.0】 |

## §5 四个事故深读

### 5.1 #6 广州切换（并发 + 容量叠加）——见 §0 时间线

补充【推导】：事故引申两条工程经验——(a) 回放与实时应共享同一把锁或统一版本通道（否则并发 eat add）；(b) 大批设备重启需要限速——事故后补了"大量设备重启>10 台"告警【实证】。

### 5.2 #18 HAVIP 误删 21 分钟（删除的两难）

```
现象：远端 VPC 误删 → HAVIP 断流 21 分钟（7 分钟才感知）
根因：删除保护缺失 + MAC 错误
恢复：回迁 HAVIP
元教训：与 #10 构成"删除的两难"——该删的被保护拦住（#10），
        不该删的被删了（#18）；阈值是硬编码的，没有"恢复期"上下文
```

### 5.3 #19 写穿 0.0.0.0（一行校验缺失 → 单母机全断）

见 §4 模式 4 代码。元教训：**同一个系统里有的 watcher 做了 DEFAULT_IP 校验、有的没做**——防护要作为架构模式统一，而不是逐点打补丁【实证】。

### 5.4 vpcId 碰撞（2026-02-03 广州 P0，7 小时）

【实证】incident-vpcId-collision：

```
现象：CreateVpc 直接失败 + 1032 条脏数据（961 Lighthouse）
根因链：vpcId 编码只占 20 位（VPC_BIT_LEN=20 → 2^20=1,048,576）
        自增 ID 每增长 1,048,576，decode 低 20 位回绕，落入旧 vpcId 区间（<65535）→ 冲突
        正常有"跃迁保护"：get_auto_increment 提前 1000 检测并跳过冲突区间
        2025-09-30 引入 schema 入参改动 → ORM 兼容性 BUG → 跃迁保护拿到错误自增值 → 失效
DBA 修复的数学：+100 无效（4,195,397 低 20 位=1,093 <65,535，仍在冲突区）
               +500K 有效（4,720,720 低 20 位=526,416，安全）
为什么测试没发现：测试主干自增已超 1000 万，跃迁保护永不触发
为什么有预警没接住：香港"get vpc curr increment not exists"告警存在数月未重视
```

### 5.5 ZK Lock → DB Lock（二代协调机制的自我修正）

【实证】tradeoff-analysis §2 + db_dlock（nfv-proxy `feature/db_dlock`：14 文件 +950/-19，9 个 proxy 进程迁移）：

```
ZK Lock（原方案，lock.py 57 行）4 个问题：
  ① 全量雪崩：listener 监到 LOST/SUSPENDED 立即 suicide——ZK 抖动 = 所有持锁 worker 同时死
  ② session timeout=400s——网络分区恢复慢；③ 独立 ZK 集群运维成本；④ 锁状态二进制，只能 zkcli 查

DB Lock（db_lock.py +822 行）核心设计：
  时间常量：LOCK_TTL=60s / HEARTBEAT_INTERVAL=15s / HEARTBEAT_MAX_FAILURES=2 / CHECK_INTERVAL=5s
  不变量：TTL(60) > 心跳间隔(15)×最大失败(2)=30，留 30s 余量
  acquire：清理过期（advisory lock 串行化）→ INSERT 行 → CAS 抢头（min(lockId) + heartbeat CAS）→ 失败 5s 重试
  心跳：UPDATE 带 4 个 WHERE（lockId+lockKey+lockValue+heartbeat>0——防偷心跳/防给已释放锁续命）
        2 次失败 → LockPreempted → daemon.suicide()
  释放：DELETE 重试 3 次 → 全失败降级 UPDATE heartbeat=0（不必等 TTL）
  死锁防护：范围 DELETE 的 InnoDB gap lock 死锁（1213）→ GET_LOCK(name,0) 非阻塞串行化；
            异常链递归找 1213（peewee 会重包装异常，不能按类型判断）
  灰度（三阶段）：zk → dual → db；DualLock 先 ZK 后 DB、双持才算 master；
            DB 侧 suicide_on_lost=True 强制（否则运行中退化为单锁 → 双主）
  互斥矩阵：dual vs zk-only → 靠 ZK；dual vs db-only → 靠 DB；zk-only vs db-only 严禁直接共存
  检测对比：ZK 400s+ vs DB 2×15s+5s≈35s（快 ~80 倍）；SELECT 可查锁状态
  姊妹实现：sdn_oss 有平行版（直接替换、无 DualLock、无 advisory lock）——nfv-proxy 版更完整
为什么不用 Redis Lock：AP 系统，分区下 SET NX 不保证互斥 → 可能双持锁
```

### 5.6 更多代码级修复（04-others，二代）（查漏补缺）

【实证】test-good/04-others；与 §4 历史 Bug 表互补：

| 分支 | 内容（代码级） | 与 15 的关系 |
|---|---|---|
| vpc_agent_dpdk_vpcgw `feature/3.9.2`（+31/-17） | ① 切主回放漏 VpcBinlog → DPDK 返回 E_COMM_VPC_NOT_FOUND(-6) → self-healing 4 行（先 add_vpc 再重试 route；与 v6 路径对称）；② CKV 自研 Redis 客户端 connect 前未 settimeout → 网络黑洞卡 OS SYN 重试 127s → 全链路透传 socket_connect_timeout=3s（5 次重试 15s 快速失败） | 即 §4 历史 Bug 表"VPCGW 切主漏 VPC（4 行修复）"的完整版；补充"VPC 必须先于 route 下发"（类外键约束） |
| vpc_agent `bugfix/is_vif_ready`（+21/-8） | is_vif_ready 被对账（LightWeightAccount.do_account，40+ 检查函数持 state.mutex 数秒~数十秒）阻塞 → 60s 超时 → 跨 VPC 迁移失败；修复 = 纯读操作移除全局锁 + try/except 兜底 set 遍历（ZK ChildWatcher 并发修改）+ Py3 e.message→str(e) | "全局锁阻塞 RPC"案例：读操作无锁化、用一致性级别换并发度 |
| ZooKeeper_VPC_Monitor `feature/alarm_invalid_session`（+41，2021） | ZK invalid session 无告警——session 超时 → 临时节点/Watch 全失效 → agent 静默脱节；shell 定时扫日志提取源 IP → zkalarm | §6 检测盲区在 ZK 层的早期案例（发现延迟从"无感"到 1h） |

【不入本篇】`fix_trpc_unwatch`（agentos tRPC watchManager 三层精确删除 rmIdxFromReq + IsEmpty，+163/-53——多 vpcId 共用 reqId 时 UnWatch 误删导致事件黑洞；agentos 指代残余分歧见 02 §9.4）与 `vm-create-end-to-end`（vpc5.0 8 步链路）——vm-create 归 vpc5.0 系列。

## §6 检测盲区与重启经济学

【实证】：

**漏了多久才发现**：#12 GWLB rtable 缺失 12 天（无客户使用）；#7 连接泄露 5 天；#20 IPv6 USG 23 小时；#21 sflow 2 天（无任何告警覆盖）；#10 孤岛残留时间未知。**告警只覆盖约 40% 故障场景**，其余靠用户报障/巡检/DB 对比，延迟小时~天级。

**有效检测方法排名**：用户报障（立即但灾害已发生）> 旁路对账连续多周期（分钟级）> ECHO 告警（分钟~小时）> 路由监控 hostMore 差异（小时）> DB vs ZK vs 数据面对比（可到天级）。

**重启成本**：

```
VPCGW Agent 完整重启 → 路由恢复 30-40 分钟（拉 ZK 全量+Redis 快照+增量 binlog → 重建 → 对账）
母机 Agent 重启 → 视 VPC 数 20-60 分钟
运维手册："非必要不重启"；优先 ① do_check（增量对账）② full_check + check_vpc（单 VPC）③ 等 ZK 自动重连
murder 强杀（daemon.suicide）仅极端情况：不走正常停止流程 → SQLite 快照可能不完整 → 下次全量重建（更慢）
```

**快慢两条重启路径**（遗漏内容补充 补遗；快路径为【vpc5.0 host-agent 口径】——二代完整重启仍是上面的 30-40 分钟）：

```
崩溃重启（Watchdog 路径）≈32s【vpc5.0 口径】：
  SIGKILL → Watchdog 检测（T+1s）→ sleep(random 1-20s，防多机同时重启)
  → fork（T+22s）→ 检测角色/连 Broker → LevelDB 恢复 → 注册订阅
  → 全量 Apply → 强制对账一次 → 正常服务（T+32s）
完整冷启动 30-40 分钟（上面主体）——两条路径差两个数量级。
```

**ZK 全宕机（Gen1 口径）**：T+1s Agent 断连 → T+10s 进入"丧失 ZK 连接"模式（对账继续、存量配置不变、不能处理新变更）→ 修复后重连 → 全量重拉 /TVPC 树 → diff 补发差异。

**ZK 全宕机（Gen2 口径，主文档 6.2 补遗）**：T+1s nfv-proxy 无法写 ZK → 暂停 Kafka 消费（消息持久化不丢）→ T+5s agent-broker 无法读 ZK → 对账继续（本地 SQLite）、Agent 心跳正常（存量）→ T+20s broker 心跳响应 BinlogIncomplete → 全量下发存量 → ZK 恢复：nfv-proxy 从 commit offset 续消费 + broker 重新 watch → 补推累积 binlog。影响：新变更延迟但不丢失。

**性能数字**：ZK commitProcessor 单线程 1-3M children/s；nfv-proxy 单实例消费 ~5000 msg/s、ZK 写入 ~2000 create/s；lag 告警阈值 >10000；binlog 写入延迟阈值 >500ms；ECHO 正常 <1s、告警阈值 310s、recovery 回退阈值 200s（#3 实际封禁 374s>200s 暴露参数不合理）。

## §7 防御反噬与工程教训清单

【实证】源文"复刻系统时必须内置的防护"：

| # | 教训 | 反面案例 |
|---|---|---|
| 1 | 所有外部输入显式校验（不信任任何上游写入） | #19 truthy 陷阱 |
| 2 | 删除做"二次确认+撤销窗口"（引用检查/延迟提交/超阈值切逐条确认） | #10 误伤 / #18 误删 |
| 3 | 对账修复避免"中间态比不修更糟"（less 与 more 同 key 合并、原子化、下发值校验） | #8 下发 -1 |
| 4 | 安全机制阈值区分场景（正常期保守 / 恢复期信任对账） | #10 阈值阻止正确恢复 |
| 5 | 可观测性是架构一部分（外部依赖状态变更必须告警：Partition 数变更、ZK leader 切换、recovery_mode 置位、未捕获异常、连续 N 周期对账差异） | 每起事故催生一个新告警（反应式） |

**运维最痛 5 件事**（源文）：该不该重启没有算法；告警只说"有问题"不说"哪里有问题"；可观测性缺口反复被点名；对账既是救命稻草也是定时炸弹；删除操作不可逆但保护又太保守。

## §8 面试口径

**30 秒难点版**：

> "VPC 控制面难在三层叠加：规模（200 万 VPC/1 亿配置）、异步正确性（变更经 Kafka→ZK→设备，at-least-once 靠幂等+版本+对账兜底）、防御的自我矛盾（删除保护误伤 vs 误删、回退机制自伤、对账既救命又炸弹）。我们的生产事故库 22 起，根因集中在并发、状态原子性、检测盲区三类；检测是最大短板——告警只覆盖 40%。"

**高频追问**：

| 追问 | 一句话答案 |
|---|---|
| 为什么 ZK 锁换 DB 锁 | ZK session timeout 400s + LOST 即 suicide → 全量雪崩；DB 心跳 15s+socket 5s → 35s 检测，且可观测 |
| 对账为什么是定时炸弹 | 差异瞬态 vs 真不一致难判断；修复操作的中间态可能比不修更糟（#8 下发 -1；more/less 方向口径见 06 §6 第 8 条） |
| 删除保护该不该有 | 该有，但要区分恢复期；#10 证明"一刀切阈值"会阻止正确恢复，#18 证明没有保护会误删 |
| 怎么发现"进程活着但假死" | 端到端心跳（ECHO）+ 看门狗自杀重启（Kafka 假死 600s 无进度 → suicide） |
| 为什么 12 天才发现 GWLB 缺失 | 无客户使用 + 无告警覆盖——说明检测要覆盖"外部依赖状态变更"，不能只覆盖"用户可感知" |

## §9 边界

1. 事故素材为**控制器生产复盘**口径（2025-06~2026-06），部分事故（如 #6）的完整时间线未逐分钟给出；#6 的"38 分钟"与"3h10m"来自源表与附录，§0 的 T+ 为相对时刻（叙述化）。
2. failure-mode-matrix/tradeoff-analysis 中 Fusion/LevelDB/Broker/tRPC 等条目为【vpc5.0】口径，本篇仅收录已分代的二代条目（ZK Lock/DB Lock/假死/skipPath/切主）。
3. §2 规模数字（200 万 VPC/1 亿配置/对账 166 个/s）来自设计推演文档（system-design/first-principles），按"控制面量级口径"引用，未逐项区分代际。
4. 事故 #2（财付通双断）、#13（CDB 对账超时）等缺根因细节，标【开放】。
5. vpcId 碰撞篇含"我的具体参与"叙述（材料口径）——本篇只收录技术事实，不引用个人叙事。
