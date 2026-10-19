# VPC 二代（Gen2）系列 · 审查记录

> 每轮深度 review：① 事实主张对照 `vpc/` 材料核对（命令级证据）；② 推导与口径冲突复核；③ 跨文件一致性比对。
> 修正项直接应用并记录在此；审查过程语言只在本文件，不进正文。

---

## Round 1（2026-10-11，全系列 8 文件首轮深审）

### 核对方法

- **事实核对**：`vpc/VPC控制面.md`（第二章二代 / 精确数据格式与转换管道章 / 修正清单）、`vpc/docs-by-topic/` 15/16/17/23/28/31、`vpc/test-good/01-zk-distributed/architecture-evolution.md`、`vpc/test-good/00-background/core-objects.md`（t_vpc_part 系列）；
- **推导复核**：前提清单与 Q1–Q9 的自洽性（06）；
- **一致性**：8 文件间数字/口径交叉比对（12h、4 版、5000、15+1、14 类型、6 Pack、GroupSize 500/1000…）。

### 修正项（6 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 01 §0 | 例子写"创建云主机"，但时间线取自材料"创建 VPC"口径（T+40ms 实为 proxy_init_vpc，非 proxy_binlog） | 例子改为"创建 VPC"，按材料时间线逐条对齐（T+0→T+60ms）；云主机/路由变更改为一句话指向 02 |
| 2 | 01 §3 | Kafka 消息格式"protobuf/JSON"两说混写 | 按精确数据格式章口径收敛为 JSON 信封（protobuf 属 VPC5.0，注于 02） |
| 3 | 02 §3 | 同上（"+ 数据体 protobuf"） | 同上；补"protobuf 口径属 VPC5.0，勿混" |
| 4 | 02 §5 | "14 种版本类型（消费侧枚举）"标签不准（该表为版本/路径口径；消费侧另有 agentos 0-11 枚举） | 标签改为"binlog 树/版本表口径"；三层映射链补"独立枚举、全局走 FakeVpc=-1024" |
| 5 | 02 §6 | "merge master 的**唯一**输入"绝对化 | 改为"主要输入" |
| 6 | README | 文件索引未挂 review-log | 索引补一行 |

### 核对无误（逐条抽查）

- 01 §2 Gen1 五问题数字（30-40 分钟 / 27.5 万 Watch / 1TB 惊群 / 19.3GB / 15M Znode + 50M Watch）—— `architecture-evolution.md` 一致；
- 02 §4 十四类进程清单（含 proxy1_binlog / proxy1_conf / append_snapshot）—— 第二章更正版一致（旧文"13 类"为误，修正清单第 3 条）；
- 02 §7 版本编码（×10000 / "151708.0001" / GroupSize 500/1000 / 修复 Group="-1"）—— 精确数据格式章一致；
- 03 §5 页大小（rtable=50000 / subnet_rule,acl=25000 / vip_tgw=20000 / config=7000~10000 / gw_config=12000）—— 修正清单第 6 条一致；
- 04 §2 心跳 6 种 Pack 及条件（含 PackError；`(peerVer, localVer]` 不含 peerVer；PackBinlogMaxSize=5000）—— 一致；
- 05 §2 t_vpc_part 系列（vpp-5fyd1tva / zone 3/4/5/6 / ckvHost=modId:cmdId / 二区七区引用五区主+四区备）—— `core-objects.md` 一致。

### 口径备注（记录在案，不改正文）

1. **材料"三代对比表"把二代对账列写为 proxy_lazymerge**（疑为笔误）——本系列按实际对账件表述（snapshot_differ / CheckJob / agent 对账）。
2. **材料时间线 T+60ms 写"母机 Agent 检测到新 VPC（Watch 触发）"** 为混合代际口径——二代母机为心跳拉取（见 04 §5）。
3. **节点命名 / VPCRECENTCHANGE 时间片 / 快照编码** 三处冲突维持 06 §4 的裁决与【开放】标记，不在正文单方面定论。
4. **Kafka 消息 protobuf 口径**（docs-by-topic/04）归属 VPC5.0 章，勿与二代 JSON 信封混用。

### 结论

**Round 1 通过**（无阻断级；6 项修正已应用）。残余开放项见 06 §6（全为参数级：桶时间片 / 清理保留窗 / 是否滞后一组 / agent 语言口径 / lazymerge 后 3 类）。

---

## Round 2（2026-10-11，覆盖度交叉检查 + 缺口发散）

### 覆盖度对照（系列 vs "吃透二代"预期）

| 组件/机制 | 系列覆盖 | 处置 |
|---|---|---|
| 写侧（sdn_oss / Kafka / ZK 树 / 分组 / 索引） | 02 | 完备 |
| merge / 快照 | 03 | 完备 |
| agent-broker 消费机制 | 04 | 补内部实现层 → **08** |
| 设备侧 vpc_agent | 04 仅 the_2g 消费 | 补角色 / watcher / 数据面 / echo → **09** |
| 分区 / ZK 基建 | 05 | 完备 |
| 对账闭环 | 04 §6 简表 | 补四层细节 + 修复策略 → **10** |
| 故障恢复 | 散见 03/04 | 补全链路恢复链 + 时间表 → **10** |
| 灰度 / 回退 | 04 §4 切换 | 补 recovery_mode 回退 → **10** |
| vpc-cgw 接入层 | 仅时间线 | 出界：一代/二代共用接入层，不展开 |
| 数据面本体（vpcko / DPDK） | 提及 | 出界：不属控制面（09 §4 简表） |
| 运维工具链（tools_*） | 未覆盖 | 出界：工具清单，不展开 |
| Gen2→Gen3 迁移四阶段 | 未覆盖 | 出界：属 vpc5.0 系列 |

### 新增（3 文件，材料拼装 + 【推导】补齐实现）

- **08** agent-broker 内部：NextGenRemoteGet 四用法、MergeUniObj 算法（推导）、binlog_replayer 任意版本重放（推导）、全局版本传播（推导）、worker 水位、SyncOnStart 顺序（推导）；
- **09** 设备侧：play_role / watcher / 全局锁 / 数据面三通道、echo mmap 设计意图（推导：静默故障探测、无锁槽位、主备偏移隔离）；
- **10** 对账四层 + FixStrategy / CheckStrategy（推导）+ 恢复链顺序（推导）+ 回退 + 故障时间表。

### 结论

**Round 2 通过**：覆盖度对照无未处置缺口（出界项已明示）；新增内容中【推导】项均可证伪，与 06 的开放项合并为同一张"待源码核对清单"。

---

## Round 3（2026-10-11，推导项证伪检查）

### 证伪方法

对 08-10 的全部【推导】项构造反例场景（幂等边界 / 缓存失效 / 槽位溢出 / 顺序假设 / 位定义），能推翻的修正、能站住的补依据。

### 证伪发现与修正（8 处，均已应用）

| # | 位置 | 证伪场景 | 结论与修正 |
|---|---|---|---|
| 1 | 08 §3 | MergeUniObj 幂等：若 U 做"旧值匹配才改"的乐观校验，重复 apply 第二次会因旧值已变而失败 | 补"幂等边界"4 条：升序 apply / U 无条件覆盖 / I 无一次性副作用 / D 空操作 |
| 2 | 08 §3 | replayer 缓存：未命中、淘汰、版本断裂时的行为未定义 | 补三条边界路径（未命中→基快照重放；淘汰→退化；断裂→Snapshot，与 BinlogIncomplete 同源） |
| 3 | 08 §1 | worker 水位材料原文自标"推测"，正文写成事实 | 补"（材料标注为推测）"，FastStart 判定来源标【推导】 |
| 4 | 08 §1 | BinlogParser 分隔符材料两写（`\|` vs `_`） | 注明以 `_` 模板为准 |
| 5 | 08 §4 | 全局版本传播的"受影响 vpc 集合"未定义 | 标注"传播范围材料未定义，待源码核对" |
| 6 | 09 §5 | echo 槽位：JSON 超 1KB 溢出、无锁撕裂读、槽位容量 | 补边界：`ljust(1024)[:1024]` 截断即失效；撕裂读代价与过滤；容量核算（余量充足） |
| 7 | 10 §4 | "恢复顺序不可换"过强——binlog 可先行（仅性能退化） | 改为"优化顺序而非正确性顺序" |
| 8 | 10 §3 | FixStrategy 位定义是纯猜测 | 显式标注"位定义材料未给，待源码核对" |

### 证伪检查中站住的推导（无修正）

- **echo 阈值自洽**：2×echo_cycle+60s=660s > 2×kicker 周期 600s——"连续两次探测未处理"触发告警，自洽；
- **"跳过最近写入对象"有据**：SQLite 表带 createTime 列（`ext/res_com_db_func.go`），时间戳过滤可实现；
- **nfv-proxy 全组自杀**：材料原文已实证（"避免孤立消费"），推导仅补充理由；
- **四层对账不打架**：修复方向统一（上游为准）+ Group="-1" 标记 + 跳过最近写入，闭环成立。

### 结论

**Round 3 通过；系列冻结（3 轮）**。冻结理由：三轮后新增发现已收敛到参数级与位定义级；进一步深挖需源码对照，边际收益递减。

残余开放项（合并清单）：
1. 桶时间片标称值（10min vs 分钟）
2. 清理保留窗参数
3. 是否"滞后一组"再合并
4. 母机 agent 语言口径（Python vs agentos Go）
5. lazymerge 另外 3 类名字
6. 全局版本传播范围
7. FixStrategy 位定义

（验证方式：`proxy_merge_master.py` / `proxy_merge.py` / `proxy_lazymerge.py` / agentos `core/types.go` + 常量表，一次源码对照即可全部钉死。）

---

## Round 4（2026-10-11，集成一致性 + 回归检查）

### 本轮工作

- Round 3 修复回归检查（08 §3 / 09 §5 / 10 §3-§4 通读，无新破坏）；
- frontmatter 链条检查（README → 01 → … → 10）；
- 跨文件引用与开放项清单同步检查。

### 修正项（4 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 07 frontmatter | 缺 `next`（链条断在 06→07）；source_refs 写"01-06" | 补 `next: 08`；改"01-10" |
| 2 | 07 正文 | 引言写"压缩 01-06"；缺 08-10 讲法条目 | 改"01-10"；§2 表补 3 行（broker 内部合并 / echo / 对账四层）；§3 补 echo 数字 |
| 3 | 01 §6 | 深挖入口未列 08-10 | 补"broker 内部机制→08；设备侧与 echo→09；对账与恢复→10" |
| 4 | 06 §6 | 开放项清单未同步 Round 3 新增 2 项；验证方式缺 agentos | 补为 7 项；验证方式加 `core/types.go` |

### 口径备注

- proxy_conf 职责材料两说（"Ckafka → ZK config" vs "监控 binlog 消费子进程存活"）——02 §4 表以"配置类消费 + 守护"并列，不单方面定论。

### 结论

**Round 4 通过（全部为链接/同步类 Notes 级修正，无事实性新发现）**。

**冻结确认**：frozen after 4 rounds — further passes show diminishing returns。残余开放项 7 条见 06 §6；进一步深挖需源码对照（验证清单同上）。

---

## 补课批次 P0-a（2026-10-11，设备侧全量）

### 批次工作

- 材料全读：`28-vpc_agent（master）`（819 行）、`14-agent_base`（215 行）、`27-vpc_agent_dpdk_vpcgw`（499 行）；
- 产出：新增 **11-device-side-full.md**（设备侧全量：三份代码关系 / watcher 全清单 / 对账全清单 / 主备双层锁与倒换 / DPDK 网关版差异）；
- 链条同步：10 的 `next` → 11；README 索引与阅读顺序更新。

### 与 09 的分工

- 09 = 设备侧基础（角色 / watcher 基类 / 数据面三通道 / echo）；
- 11 = 全量版（进程拓扑与启动、watcher 全清单、四级优先级调度、对账全清单、双层锁与 do_switch 回放、存储与观测）。

### 覆盖度变化

- P0-a（设备侧）从"浅覆盖" → "已覆盖"；P0-b（数据面协议）、P1（ZK 定制族）仍在队列。

### P0-a 审查（2026-10-11）

对新增 11 篇对照 28/14/27 原文逐条核。

**修正项（5 处，均已应用）**：

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 11 §0/§1 | `_check`/末索引/monitor 属 DPDK 版口径，与 master 混写 | 加"DPDK 版"限定；补 master 版拓扑（worker/inspector + monitor） |
| 2 | 11 §7 | fix_manually / do_check 为 DPDK 版入口 | 加限定 |
| 3 | 11 §8 | "sleep 4h"把 shell 版与 py 版（约 1 小时）合并成一个数字 | 分列两版 |
| 4 | 11 §9 | "WAL 模式"未限定（源文为 DPDK 版 binlog 库） | 限定到 DPDK 版 binlog 库 |
| 5 | 09 / 07 | 链条与讲法未挂 11 | 09 钩子补 →11；07 source_refs 改 01-11、§2 补"设备侧三种形态"行 |

**核对无误（抽查）**：16 分片公式、wizard 阶段 check_all_ready、twins+MsGuard 双层锁、do_switch 回放链（CFastPath/OpData/BinLogSubOp/CVpcSyn）、四级队列（High/Normal/Lower/Ignore）、account 四模式、the_2g 文件族、HTTP 端口 65357/65358——与 27/28/14 一致。

**结论**：**P0-a 审查通过**（无阻断级；5 项口径精度修正已应用）。

### P0-a 审查 · 补记（2026-10-11，全量核对）

用户指出"不要抽查"——上一节为抽样核对，不达标。改为**全量逐条**：11 篇 133 行全部主张对照 28/14/27 原文逐句核。

**全量核对新增修正（2 处，已应用）**：

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 6 | 11 §3 | "启动 5 步"标签与正文 4 段不符（28 原文 5 步含异常路径） | 改为"启动流程" |
| 7 | 11 §5 | 漏 27 版 EventHandlerAndMonitor 关键口径（集群队列串行、分区队列无锁并行） | 补入 |

**全量核对确认（逐条）**：§0 例子各环节、§1 六卡、§2 三行、§3 两版启动流程与状态健康、§4 全部 watcher 域与文件族、§5 三种调度机制、§6 门面/三通道/门控/Adam、§7 对账表七行、§8 主备要素、§9 存储/观测/HTTP/工具四块、§10 边界——与源文一致（除累计 7 项修正）。

**方法更新**：本系列后续所有审查按"全量逐条"执行，不再抽样；旧批次（Round 1-4）中的"抽查"清单如需全量重核，另行补轮。

---

## 全量重核：Round 1 / Round 3 抽查清单（2026-10-11）

按"全量逐条"标准，对旧批次标注"抽查/核对无误"的 10 项清单逐条回到源文取证。

### 重核结果（逐条）

| 项 | 证据 | 结论 |
|---|---|---|
| Gen1 五问题数字（30-40 分钟 / 27.5 万 Watch / 1TB / 19.3GB / 15M Znode + 50M Watch） | architecture-evolution L87-89 | ✅ |
| 14 类进程清单（含 proxy1_* / append_snapshot） | VPC控制面.md L6757-6770（更正版） | ✅ |
| 版本编码（×10000 / "151708.0001" / GroupSize 500/1000 / Group="-1" / InspectFixStepVer=1） | L9526-9551 | ✅ |
| 快照页大小（rtable 50000 / subnet_rule,acl 25000 / vip_tgw 20000 / config 7000~10000 / gw_config 12000）+ 版本保留 4 | L6815-6820、L9762-9766 | ✅ |
| Pack 6 种及条件 | L3328-3336 | ✅ |
| t_vpc_part 系列（vpp-5fyd1tva / weight 10 / zone 3-6 / ckvHost=modId:cmdId / newZkHost 四区替换 / observer gw 池 / proxy 同组消费 / refer 五区主+四区备） | core-objects L370-470 | ✅ |
| echo 阈值自洽（echo_cycle 300s / 阈值 660s / kicker 5min） | L5920、L6233、28 文档 L470-471 | ✅ |
| nfv-proxy 全组自杀 + 恢复顺序 + ~30s-2min | L7587-7596 | ✅ |
| "跳过最近写入"有 createTime 列 | 16-AgentOS L212 | ✅ |
| 四层对账组件（Account / snapshot_differ / CheckJob / inspector+the_2g_check） | 24/23/16/28 各源 | ✅ |

### 重核新增修正（2 处，已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 02 §3 | topic 举例含 `t_service`——材料无此 topic（有据的是 t_rtable / t_rtable_vpcgw / t_host_prop） | 替换为有据举例 |
| 2 | 05 §4 | synchronizer 四字命令列 6 个未注"等"——源文实际定义 8 个（另有 syra/syin） | 补"等 8 个" |

### 结论

**全量重核通过**（10 项清单逐条对上；新增 2 处小修）。Round 1-4 的抽查清单至此全部转全量核对，无遗留。

---

## 补课批次 P0-b（2026-10-11，数据面协议）

### 批次工作

- 材料全读：`05-VPC 数据面与安全组深度架构`（747 行）、`14-business-scenarios` 的 GRE 字段复用两表；
- 产出：新增 **12-dataplane-protocol.md**（vpcko 内核数据面 / DPDK 用户态 / DFW 安全组 / GRE 字段复用 / Go Facade 驱动抽象）；
- 链条同步：11 的 `next` → 12；README 索引与阅读顺序更新。

### 覆盖度变化

- P0-b（数据面协议）从"未碰" → "已覆盖"；P1（ZK 定制族：32/43-46）仍在队列。

### P0-b 审查（2026-10-11，全量逐条）

对新增 12 篇逐句对照 05 全文与 14 的 GRE 两表。

**修正项（4 处，均已应用）**：

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 12 §2 | `struct tvpc` 漏 `route_learn`（路由学习表） | 补入 |
| 2 | 12 §3 | Pipeline 名缩写（rule_limit_orig/reply）与源文不符 | 改全名 rule_limit_match_orig / rule_limit_match_reply |
| 3 | 12 §3 | CT 同步漏"短连接 <3s 不同步" | 补入 |
| 4 | 12 头部 | "GRE v0 不能加字段"口径的出处未挂（来自题库文档） | source_refs 补 |

**全量核对确认（逐条）**：§0 例子各环节、§1 六卡、§2 八模块/数据结构/CT 机制/出站 7 子阶段/remote_type 11 种/ECMP 4 种/入站三路径/17 hooks、§3 PAL 协议（magic 0x20161206/三层消息/5 Family/表结构/KUSC）与 VPCGW（Pipeline/LB 先于 Route/CT 同步三端口/TCP 11 态/TOA/STLS）、§4 DFW（Watcher 9 项/6 链/ipset/转换/双路径/mark bit 3/隔离/AddrSet）、§5 GRE 两表与入站判源、§6 Facade（~250 方法/三驱动/差异）——与源文一致（除 4 项修正）。

**结论**：**P0-b 审查通过**（无阻断级；4 项修正已应用）。

---

## 补课批次 P1（2026-10-11，ZK 定制族）

### 批次工作

- 材料全读：30-cos_backup（242）、31-synchronizer（265）、32-zk3.5.6（225）、43-zk_agent（200）、44-zk_controller（142）、45-vpc_monitor（293）、46-differ（314）——共 1681 行；
- 产出：新增 **13-zk-custom-family.md**（七件套深读：同步器 / 3.5.6 / differ / 管控 / 执行 / 监控 / 备份 + 族谱协作图）；
- 链条同步：12 的 `next` → 13；README 索引与阅读顺序更新。

### 覆盖度变化

- P1（ZK 定制族）从"族谱概览" → "已深读"；**P0/P1 批次全部完成，二代覆盖清单清零**（结构性空白无遗留；业务域/事故库仍为明确出界项）。

### P1 审查（2026-10-11，全量逐条）

对新增 13 篇逐句对照 30/31/32/43/44/45/46 七份原文。

**修正项（2 处，均已应用）**：

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 13 §2 | synchronizer.conf 配置项漏 `remoteLastSeenZxid`（源文首项） | 补入 |
| 2 | 13 §9 | 协作图"3.4.6 ←→ 3.5.6"暗示两版交互（实为两种集群本体版本） | 改为"集群本体：3.4.6 同步器版 / 3.5.6 定制版" |

**全量核对确认（逐条）**：§0 七件套分工、§1 六卡、§2（128 提交 / 1800 行 / 状态机 / 8 四字命令 / 增量+全量 / 配置项 / 配套件）、§3（~60 提交 / roll over 链 / 只读 OB / 新命令 / 运维配套 / 31 文件）、§4（双入口 / SFTP 限速 / ListDiffer / 二次确认 / skipPaths / 辅助分析器 / ansible 工具）、§5（五资源 / 就近查询白名单 / 六表）、§6（Go / cmux 8383 / 五职责 / 308G 清理 / 过载保护）、§7（22 插件 / 采集项 / barad+CMEM / 收敛策略 / ansible）、§8（V2 流程 / bucket 命名 / 生命周期 / SSM / 巡检脚本）——与源文一致（除 2 项修正）。

**结论**：**P1 审查通过**（无阻断级；2 项修正已应用）。**P0/P1 全部批次审查完毕。**

---

## 补课批次 P2（2026-10-11，业务域 + 事故库）

### 批次工作

- 材料全读：`14-business-scenarios`（1303）、`01-vpc-basics`（399）、`first-principles`（289）、`11-血泪教训`（541）、`incident-vpcId-collision`（279）、`failure-mode-matrix`（166）、`tradeoff-analysis`（223）、`system-design`（关键段 348-562）、`kafka_consumer`（195 头部）、`syncer_skippath_fix`（297 头部）、`02-distributed-infrastructure`（头部口径）——合计约 4000 行；
- 产出：新增 **14-business-scenarios.md**（业务全景：四条承诺 ↔ 机制映射、20 场景矩阵、4 场景包路径深读、排障六步）与 **15-hard-problems-and-incidents.md**（7 类难点地图、22 起事故表、5 种根因模式+代码片段、4 事故深读、检测盲区/重启经济学、防御反噬 5 条教训）；
- 链条同步：13 的 `next` → 14；14 的 `next` → 15；README 索引 / 掌握卡（10→12 条）/ 阅读顺序更新。

### 覆盖度变化

- 业务域（20 场景）与事故库（22 起）从"明确出界项" → "已覆盖"；**二代覆盖清单（含业务/事故）清零**。

### P2 自查（2026-10-11，全量逐条）

对新增 14/15 逐句对照源材料，关键数字全部回源 grep 核对（不做抽查）：

- 事故表 22 行（日期/影响/检测延迟）、38 分钟、3h10m、855、374/200/310/600 秒、85-94%、2.5 亿、10000 IP——对上；
- vpcId 碰撞（1,048,576 / 4,720,720 / 526,416 / 1,093 / 1032 / 961 / 提前 1000 / 65535）——对上；
- Kafka 假死（600/30/30000/+121/-3）、skipPath（+144/-87）、db_dlock（400s/35s/15s/5s/822 行）——对上；
- 业务矩阵 20 行、GRE 三种 Key 规则、NATGW 四 IP、PrivateLink 双向 NAT / SNAT 池 11.163.0.0/16、Peering 目标 VPC ID、HAVIP 9s/14s——对上。

**裁决 1 处（源材料内冲突）**：vgwType 数值——内联速记卡出现"VPNGW(0) / PVGW(3) / CCN(4)"，与最终对照表（0=Local/VNET、1=VPNGW、2=DCGW、4=Peering、8=NATGW，标注 MCP 验证）矛盾；**裁决以最终表为准**，CCN/HAVIP 标【开放】（14 §9）。

**修正 1 处（自查后应用）**：
1. 15 source_refs 补 `system-design.md`，§9 增 2a 条（200 万 VPC / 对账 166 个/s 等规模数字为设计推演口径，未逐项区分代际）。

**写作时即处理 2 处**：14 §9 口径冲突裁决与单源标注（HAVIP ~14s / 辅助 IP <1s）；15 §9 代际边界（failure-mode-matrix / tradeoff 的 vpc5.0 条目已分代）。

**结论**：**P2 自查通过**（无阻断级；数字全量回源）。待用户 review。

---

## Round 5 审查（2026-10-11，新章 14/15 全量逐条）

### 审查范围

对 14-business-scenarios.md、15-hard-problems-and-incidents.md 全文逐句对照源材料：14-business-scenarios / 01-vpc-basics / first-principles / 11-血泪教训 / incident-vpcId-collision / failure-mode-matrix / tradeoff-analysis / kafka_consumer / syncer_skippath_fix / **db_dlock（补读 259 行）** / system-design；关键数字全部回源 grep。

### 修正项（9 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 15 §0 | 叙述化时刻（03:00/03:38/07:00）当实证；#6 检测路径误写 ECHO（源为旁路对账连续多周期） | 改 T+ 相对时刻并注明；检测改"旁路对账（连续多周期）" |
| 2 | 15 §0 | "OOM 边缘/配置不一致"弱化源口径 | 改"152 台 VPCGW + 30 台 LBNATGW OOM（对账报大量设备配置不一致）" |
| 3 | 14 §3 | 翻车锚点混入 vpc5.0 未标注项（"配置复活类 bug"） | 替换为二代 #8 对账修复中间态；"12h 拒写"口径修正为"合并延迟假设" |
| 4 | 14 §6 | 引号内混入自撰句（"不要先怀疑 DPDK 转发逻辑"） | 只保留材料原话；口诀标【推导】 |
| 5 | 14 §5.3/§9 | PrivateLink 节"Peering 经 PVGW 解 GRE→重 GRE"与场景 5"同 Region 直通无 PVGW"的口径张力未标注 | §5.3 标注张力；§9 新增第 5 条【开放】 |
| 6 | 15 §4 | 模式 5 指标名与源不符（max_queue→max_queue_size≈0/1；event_large 漏 0/1） | 按源修正 |
| 7 | 15 §5.1 | 自撰经验标成【实证】 | 改【推导】；(b) 告警部分标【实证】 |
| 8 | 15 §5.5 | db_dlock.md 未纳入——缺三阶段灰度（zk→dual→db）/DualLock/advisory lock 防 gap lock 死锁/TTL 不变量 | 补读 db_dlock 并扩写 §5.5；source_refs 补录 |
| 9 | 15 §9 | "2a" 编号 | 重排为 3/4/5 |

### 全量核对确认（逐条）

- **14**：§0 串讲与场景 1-5 一致（vpc-dl18w3l7/106.52.208.59/10.0.0.100 有据）；§2 产品模型表逐行对 first-principles/场景（t_ip vpcId%250、t_rtb 三表、t_route_state 等）；§4 矩阵 20 行与源表逐字对上；§5 四个包路径（GWMAC/路由查找序/QoS GroupID/NATGW 四 IP/SNAT 池 11.163.0.0/16/GRE Key=目标 VPC ID）对上；§6 六步；§7（200 万/85-94%/2.5 亿/10000 IP→5000 万/ECHO <1s）；§8 速记卡（GRE vs VxLAN 4 字节/2^32）；§9 vgwType 冲突裁决与源对得上。
- **15**：§0（除修正 1/2 外）；§1；§2 难点表；§3 22 行原表逐字对上；§4 五模式+四段代码逐字对上（#21 pop/get、#19 truthy、#5/#17 ready）；§5 深读（#6 两把锁/855/98%、#18 回迁 HAVIP、vpcId 数学 1,048,576/4,720,720/526,416、ZK 400s vs DB 35s）；§6（40%/12 天/5 天/23h/2 天/重启 30-40min 与 20-60min/性能数字）；§7 五条教训逐条；§8。

### 存疑项核实（1 项）

- **"9 worker 自杀"**：此前仅见简历/面试材料；本轮核实 db_dlock.md（"9 个 proxy_*.py 替换"+"9 个 worker 进程平滑迁移"）——口径成立；15 §2 措辞精化为"9 个 proxy worker"。

### 结论

**Round 5 审查通过**（9 项修正已应用；14/15 事实主张全量逐条核对完毕，无遗留）。**二代系列（含业务/事故）三轮自查+用户审查全部闭环。**

---

## Round 6（2026-10-11，查漏补缺：全库语料盘点 + 六处补遗）

### 盘点方法

对 `vpc/` 全库（test-good 7 目录 + docs-by-topic 46 篇 + 背景域）做清单级差集：系列已引用 vs 未引用，逐目录定性代际与价值；对高价值缺口全读后补遗。

### 盘点结论（差集）

- **新覆盖（本轮读并入正文）**：06-open-source-vs ×3（→ 新章 16）；04-others ×5（→ 15 §5.6）；11-gateway-detail、12-advanced-features、13-dns-quota（→ 14 §5.5/§5.6）；add_vpc（→ 02 §9）；zxid_counter_extend（→ 13 §3）。
- **明确出界/归 vpc5.0**：05-cross-cutting（29 篇，vpc5.0 口径）、02-vpc5-gen3（12 篇）、docs-by-topic 33-42、18-22/25/26/29；04-others 中 fix_trpc_unwatch / vm-create-end-to-end 为 vpc5.0。
- **备查**：00-background 概念文档 03-10、vpc-core / same-vpc-communication、03-interview 语料——README"语料盘点"表已登记去向。

### 新增/补遗（6 处）

| # | 位置 | 内容 |
|---|---|---|
| 1 | 16（新章） | 业界对比：Calico/OVN/K8s/Andromeda/Hyperplane/VFP——含代际标注与反事实推演 |
| 2 | 02 §9 | AddVpc 5-step Saga / vpcId 申请制（段）/ 三套 ID / AddVpc 不写数据面节点 / groupId 公式 / 补偿两类 |
| 3 | 14 §5.5/§5.6 | VPCGW（HARP 主备/65357-65358）与 PVGW（vgwType=3/gwIp 不参与 GRE）补遗；Flow Log/Traffic Mirror/DHCP IP/DNS/配额/多 AZ |
| 4 | 15 §5.6 | 04-others 代码级修复三件：VPCGW self-healing + CKV 超时；is_vif_ready 全局锁；invalid session 告警 |
| 5 | 13 §3 | zxid counter 32→40 位备查分支（ZOOKEEPER-1277 / 容量 ×256 / 未合入原因） |
| 6 | README | 索引 / 掌握卡 13 条 / 语料盘点表 / 阅读顺序 |

### 新发现的口径冲突（2 处，已标【开放】）

1. **uniqVpcId 编码入参**：add_vpc（源码走读）= `encode_uniq_id(id, zone)`；vpcId-collision = `((count << 20) | vpc_id)`——两文描述不同，待源码核对（02 §9.2）。
2. **"agentos" 指代**：add_vpc 称 agentos = 设备侧 Go 重写（watch /BINLOG，非 Gen2 核心）；docs-by-topic/15/16 用 agentos 指 broker 框架——同名分指，标【开放】（02 §9.4）。

### 修正（1 处）

- 14 §9 第 5 条（PVGW 张力）：由"裁决倾向同 Region 直通"更新为"与 PVGW 机制并存关系未定"，并补 vgwType=3 两处佐证。

### 存疑核实

- feature_3_9_2"4 行修复" = §4 历史 Bug 表"VPCGW 切主漏 VPC"条目（+31/-17 分支内 4 行核心修复）——对上。

**结论**：**Round 6 通过**（全库差集盘点完毕；6 处补遗落盘；2 处新口径冲突登记待源码核对；剩余未纳入语料均已在 README 登记去向）。

---

## Round 7 审查（2026-10-11，Round 6 补遗全量逐条）

### 审查范围

新章 16 + 02 §9 + 14 §5.5/§5.6 + 15 §5.6 + 13 §3 补遗 + README 语料盘点表，逐句对照：vs-calico-ovn / vs-industry-practices / vs-k8s-controller / add_vpc / 11-gateway-detail / 12-advanced-features / 13-dns-quota / 04-others ×3 / zxid_counter_extend。

### 修正项（4 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 14 §5.6 | Flow Log 行自撰"与 sflow 是两回事"——#21 影响列即"流日志缺失"，两者关系未核 | 改"#21 sflow 丢失影响列即'流日志缺失'——两者关系标【开放】" |
| 2 | 02 §9.1 | "事故 #19"与 add_vpc 所称"事故 A"的对应未注明 | 补"（add_vpc 称'事故 A'）" |
| 3 | 16 §3.2 | "重启要重载 24GB 缓存"表述混淆（源：重启从 etcd 加载全量；24GB 为本地缓存规模） | 改"每次重启要从 etcd 重载全量（本地缓存 24GB）" |
| 4 | README | 语料盘点行数偏差：vpc5-gen3 实为 ~6600 行（原 ~6300）；概念文档 03-10 实为 ~1800 行（原 ~2000） | 修正 |

### 全量核对确认（逐条）

- **16**：Calico/OVN/K8s/业界三节的数字与结构（~100K 路由 / convergence 1-10s / 10 万实体 / 超 1600 倍 / 0.06% / OVS ~10K-100K / 1000 万 watcher / 278 小时 / 24GB / etcd 8GB / ~150K Pod / 每 SET ~50 万 VPC / TLA+ 三场景 / VFP 编译器 / 借鉴清单三周期）逐条对上；代际标注（tRPC 推送 / Broker / Fusion / SET / Lua / DeleteConfig）与边界说明一致。
- **02 §9**：5-step 代码块、外键顺序、Saga/补偿两类、三套 ID、段机制（vpcid/vpcidInstance/flag/KEEP_HOUR）、位编码、uniqVpcId 实测、AddVpc 不写数据面节点、groupId 公式——逐条对上；【开放】两处（编码入参 / agentos 指代）保留。
- **14 §5.5/§5.6**：VPCGW（ZK 路径 / HARP 端口 65357-65358 / 回放 4 类）、PVGW（vgwType=3 / gwIp 不参与 GRE / PVGWDPDK）、特性表（配额 8 项数字 / DNS 169.254.0.2 / 多 AZ 延迟 / 镜像约束）逐条对上。
- **15 §5.6**：三行修复（+31/-17、E_COMM_VPC_NOT_FOUND(-6)、127s、+21/-8、+41）逐条对上。
- **13 §3**：40 位分支（×256 / 12h→4 个月 / epoch 1677 万 / 未合入原因 / ZOOKEEPER-1277 链）逐条对上。
- **章节重编号**：02 §9→§10 钩子，全文件 grep 无悬空引用。

### 结论

**Round 7 审查通过**（4 项修正已应用；Round 6 六处补遗全量核对完毕，无遗留）。

---

## Round 8（2026-10-11，查漏补缺：主源扫描 + 五处补遗）

### 盘点方法

主源目录扫描：VPC控制面.md（24717 行，多文档聚合）章节差集；docs-by-topic 06-13 定性；reference/ 与 vpc-core / same-vpc-communication 评估。

### 新覆盖（本轮读并入正文）

- docs-by-topic/10（637 行）→ 08 §3/§7（agentos 真实结构 / 空洞与迟到 / worker 串行模型）+ 02 §9.5（Saga 回滚失败 / Go-Python 分界）；
- vpc-core.md（357 行）→ 14 §2（数据模型三关键：IP 绑 ENI / 分表公式 / flag 状态机）；
- reference/遗漏内容补充（250 行）→ 14 §5.5（Twins 模型）+ 15 §6（快慢重启路径 / ZK 全宕机）+ 14 §3 微调（CVM 解隔离 / DeleteVpc 115 步）。

### 发现与处置

- 08 未使用被 docs-by-topic/10 纠正的旧口径（"Extension 生命周期"/"ScopeGuard→Retry→Raw 包装链"）——已核，无修正；新增 §7 记录真实结构。
- 主文档附录 B"nfv-proxy 13 类"为旧口径——系列按更正版 14 类（Round 1 已裁决），README 记录。
- 剩余备查：docs-by-topic 06/07/09/13（~2300 行）、reference 速查表、background 概念文档——README 语料盘点表已登记。

### 补遗（5 处，均已落盘）

| # | 位置 | 内容 |
|---|---|---|
| 1 | 08 §3/§7 | 空洞（latestContinuesVer 等补产）/ 迟到（onDelayedBinlog→FixBinlogVersion）处理；agentos 真实生命周期 / 单层 Wrapper / worker 三 channel 串行无锁 |
| 2 | 02 §9.5 | 回滚失败（Redis rollback 上下文 + Revert 接口）/ Go 同步补偿【vpc5.0】/ TVPC 接口合约 / DeleteVpc ≈115 步 |
| 3 | 14 §2/§3/§5.5 | 数据模型三关键；CVM 解隔离与 DeleteVpc 步数微调；VPCGW Twins 两层锁完整模型 |
| 4 | 15 §6 | 崩溃重启 ≈32s vs 冷启动 30-40min；Watchdog random 1-20s 防惊群；ZK 全宕机 Gen1 时间线 |
| 5 | README | 语料盘点表扩展（docs-by-topic 10/06/07/09/13、reference、主文档、papers、test/8） |

### 结论

**Round 8 通过**（主源扫描完毕；5 处补遗落盘；无新口径冲突——"agentos 指代"维持 02 §9.4 的【开放】；Twins 双层锁与 11 §8 口径一致）。

---

## Round 9 审查（2026-10-11，Round 8 补遗全量逐条）

### 审查范围

08 §3/§7、02 §9.5、14 §2/§3/§5.5、15 §6 补遗 + README 语料盘点表，逐句对照：docs-by-topic/10、vpc-core、reference/遗漏内容补充（并复核主文档附录 B 口径）。

### 修正项（5 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 15 §6 | 崩溃重启 ≈32s 时间线实为 **vpc5.0 host-agent 口径**（Broker/LevelDB/Composer），未标注 | 加【vpc5.0 口径】标注，并说明二代完整重启仍为 30-40 分钟 |
| 2 | 14 §3 | "CVM 初始隔离→解隔离"来自 Gen1 链路口径（sdn_oss 直写 ZK 的生命周期文档），未标注 | 加"（Gen1 链路口径）" |
| 3 | 02 §9.5 | "Revert.TVPC.* 接口"泛化过度（源仅示 AddService） | 改"Revert.TVPC.AddService 类接口" |
| 4 | README | reference/ 行数误为 1351（实 1201）；docs-by-topic 06/07/09/13 误为 ~2000（实 2259） | 修正为 ~1200 / ~2300 |
| 5 | review-log R8 | 同上行数口径 | 同步修正 |

### 全量核对确认（逐条）

- **08 §3/§7**：latestContinuesVer / onDelayedBinlog / FixBinlogVersion、6 阶段生命周期、三 Wrapper、gorm Retry（10 次 / 100ms~1s）、三 channel 串行——逐条对上；08 全篇未用被纠正旧口径（复查通过）。
- **02 §9.5**：rollback 上下文（TTL 24h）/ AddService 60 步 del_zoo 案例 / Go prepareRollbackData / forward-backward 同一函数 / TVPC 协议（-3001/-3017）/ DeleteVpc ≈115 步——对上。
- **14 §2**：IP-ENI 持有模型（uniqInstanceId 字段名历史遗留）/ hash(vpcId)%250 / Py2 hash(int)=int / 旧 VPC 例外 / flag 0-1-2（含"2=推测"标注）/ keep_ip 已注释——对上。
- **14 §5.5**：Twins（1+16×3+1+1 / worker15 特殊 / 两层锁路径 / 2000+s / 动态主备 / MS 对比）——对上；与 11 §8 口径一致。
- **15 §6**：Watchdog random 1-20s / T+1→T+32s / ZK 全宕机 Gen1（T+1s / T+10s / T+5min）——对上（快路径已补代际标注）。
- **README**：语料盘点表 11 行逐条复核（含主文档"13 类"旧口径备注）。

### 结论

**Round 9 审查通过**（5 项修正已应用；Round 8 五处补遗全量核对完毕，无遗留）。

---

## Round 10（2026-10-11，查漏补缺：开口闭合 + 主文档深挖）

### 方法

针对 9 处开口做全文检索（VPC控制面.md + docs-by-topic + test-good），定位主文档 ★ 更正条款；挖主文档 §2.3 / §2.4 / §2.5 / 三代对比表。

### 开口闭合（3 项）

| # | 开口 | 闭合结论（证据） |
|---|---|---|
| 1 | 桶时间片（10min vs 分钟） | **已闭合**：minute_groups 分钟粒度；"10 分钟"实为 9:30-9:39 发布保护时段（主文档 §2.3 ★"之前的文档有 2 处错误，现更正"） |
| 2 | 母机 agent 语言口径 | **主体闭合**：Gen2 设备 Agent = vpc_agent（Python），与 Gen1 同一进程同一套代码；agentos/agent-broker 是新增中间代理层（主文档 §2.5 ★）。残余：代际表并列 agentos(Go)、04-others 称 Gen3 |
| 3 | agentos 指代 | **已闭合**：agentos = Gen2 agent-broker 底座框架（Go）；"VPC 5.0 Agent 框架"系旧文档错误（主文档 §2.5 ★） |

### 未闭合（保留，均已注明证据边界）

清理保留窗 / 是否滞后一组 / lazymerge 另 3 类（复核：主文档同列已知 3 类）/ 全局版本传播范围（机制确认 glb_ext.go，受影响集合未定义）/ FixStrategy 位定义（仅述"位标志"）/ uniqVpcId 编码入参（两文口径冲突未解）。

### 补遗与更新（4 处）

| # | 位置 | 内容 |
|---|---|---|
| 1 | 06 §2/§4/§6 | 开放项闭合更新（1、4 号闭合；清单重排） |
| 2 | 08 §2/§5/§7 | driver 名（PreGenZkDriver / NxtGenPartZkDriver）；/dev/shm 路径 + 分片特例（-1/0/2^32-1 → 15）；agentos 闭合 |
| 3 | 02 §9.4 | agentos 闭合更新 |
| 4 | 01 §4 | 三代对照表补"瓶颈"行（ZK 单核 / CRS 构建 / GC 停顿） |

### 结论

**Round 10 通过**（闭合 3 处：1 处完全 + 2 处主体带残余；完全开放 6 处——全部在 06 §6 登记）。

---

## Round 11 审查（2026-10-11，Round 10 闭合更新全量逐条）

### 审查范围

06 §2/§4/§6、08 §2/§5/§7、02 §9.4、01 §4 + Round 10 记录，对照主文档 §2.3 / §2.5 / 代际表 ★ 更正条款；并做全系列 agentos 引用一致性扫描（12 处）。

### 修正项（5 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 01 §4 | Agent 行"母机 Go/agentos"与 Round 10 闭合（设备 Agent = vpc_agent Python）矛盾 | 改"vpc_agent（Python，同一套代码；agentos=broker 底座）" |
| 2 | 04 §2 | "agentos 框架机制（Gen2 agent 底座）"——"agent"歧义 | 改"Gen2 agent-broker 底座（主文档 §2.5 ★）" |
| 3 | 04 §5 | "口径冲突…两说并存（见 06 开放项）"未同步闭合 | 改"口径已闭合（Round 10）…残余见 06 §6" |
| 4 | 15 §5.6 | fix_trpc_unwatch 标"【vpc5.0】"——与其 agentos 代际残余分歧冲突 | 改"【不入本篇】…agentos 指代残余分歧见 02 §9.4；vm-create 归 vpc5.0" |
| 5 | review-log R10 | 结论"4 项保持开放"与列出的 6 项不符 | 改"闭合 3 处：1 完全 + 2 主体带残余；完全开放 6 处" |

### 全量核对确认（逐条）

- **06**：闭合表述与主文档 §2.3 ★（minute_groups 分钟粒度 / 9:30-9:39 发布保护）逐字对上；§6 清单计数（闭合 2 项）自洽。
- **08**：driver 名（PreGenZkDriver / NxtGenPartZkDriver）、/dev/shm 路径、分片特例（-1/0/2^32-1→15）、§7 闭合——与主文档 §2.5 逐条对上。
- **02 §9.4**：闭合表述与主文档 §2.5 ★ 逐条对上。
- **01 §4**：瓶颈行与主文档代际表逐字对上。
- **agentos 全系列扫描**：12 处引用逐条核（除修正 1-4 外其余一致）。

### 结论

**Round 11 审查通过**（5 项修正已应用；Round 10 闭合更新全量核对完毕，无遗留）。

---

## Round 12（2026-10-11，查漏补缺：Ops 维度 + 07 修正对照）

### 方法

读 docs-by-topic/06（690 行，监控运维）+ 07（561 行，三代对比修正版 17 条），逐条对照系列。

### 新覆盖

- docs-by-topic/06 → **新章 17**（Echo/Kicker 广播探针、mmap 双通道、三层 Watchdog 链、tslog 指标、三层告警、ECHO SOP / 65358 诊断 / 日志矩阵）。
- docs-by-topic/07 修正清单 17 条 → 对照系列，3 条涉及（#2 ScopeGuard 链归属、#12 more/less 方向、#15 对账周期）。

### 修正项（6 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 08 §7 | "ScopeGuard→Retry→Raw 在代码中不存在"不完整——实为 vpc-agent-framework（vpc5.0）形态（07 #2） | 改"不属于 agentos（张冠李戴）——该链实为 vpc-agent-framework 真实形态" |
| 2 | 08 §7 | "Inspector 周期对账（10s 一轮）"——07 #15：完整对账 5 分钟（30×10s） | 改"10s tick；完整对账 30×10s=5min" |
| 3 | 14 §1/§3 | "对账 more 修复"（删除语境）——方向口径冲突未处理 | 改中性"对账修复（方向见 06 §6 第 8 条）" |
| 4 | README 卡 11 | 同上 | 改中性 |
| 5 | 15 §8 | "修 less 的中间态"依赖方向 | 改"修复操作的中间态" |
| 6 | 06 §6 | more/less 方向冲突未登记 | 新增第 8 条：修正清单裁定 more=加/less=删；正文相反；系列保持中性 |

### 07 其余修正对照（不涉及本系列）

#1/#3-#7/#11 与系列一致（agentos 归属 / 14 进程 / 持久节点 / 分钟粒度 / 页大小 / 5000 可配置）；#7/#8/#16/#17 属 vpc5.0；#9/#10/#13/#14 为 Gen1 细节（17 §8 边界已注）。

### 结论

**Round 12 通过**（新章 17 落盘；6 处修正应用；07 修正清单逐条对照完毕）。

---

## Round 13 审查（2026-10-11，Round 12 全量逐条）

### 审查范围

新章 17 全篇 + 08 §7 / 06 §6 / 14 / 15 / README 的 Round 12 修正，逐句对照 docs-by-topic/06（690 行）与 07 修正清单（17 条）。

### 修正项（4 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | README | 掌握卡总览标题"13 条"未同步（实 14 条） | 改 14 条 |
| 2 | README | review-log 行"Round 1-12" | 改 1-13 |
| 3 | 17 §0 | "三步定位"实列 4 项 | 改"按 SOP 定位" |
| 4 | 17 §3 | 漏 max_fork_times 超限语义 | 补"超限→永久退出（Monitor 无限兜底）" |

### 全量核对确认（逐条）

- **17 全篇**：Echo（Kicker 广播 / 字段 / MIN_ECHO_CYCLE=10s / cost 公式 / DelayInfo 分桶 / HEAD→Nginx→CLS）、mmap（1MB / 1KB 槽位 / Slave 100+ / VPCGW 16-31 / usock 备份注释）、三层 Watchdog（1-20s 退避 / 风暴检测 5min>10 / max_fork_times / s2m_cb_func / 3s / kernel_check ppid==1）、线程健康（6 类 / 660s / 探针）、tslog（8 logger / 60s / 指标清单）、vpc5.0 resettable（30s / push 失败不 reset / spec 025 / 三 reporter / 限流 / 标签）、三层告警（星云 60s 去重 / CLS 4 项 / 大盘 3 项）、SOP（play_role / 不要重启）、65358 四步 / 日志 / 矩阵 / 数字表——与 06 逐条对上。
- **08 §7**：ScopeGuard 链归属（vpc-agent-framework）与 07 #2 对上；对账周期 10s tick + 5min 与 #15 对上。
- **06 §6 第 8 条**：more/less 行号引用（主文档 4258/9204/9458；11 §6.1/§10.3）复核对上。
- **14 / 15 / README 中性化**：四处"more 修复"改写核完。
- **07 修正清单**：17 条逐条复核（3 条涉及已处理；其余为一致 / vpc5.0 / Gen1）。

### 结论

**Round 13 审查通过**（4 项修正已应用；Round 12 全量核对完毕，无遗留）。

---

## Round 14（2026-10-11，全系列综合审计）

### 方法

13 轮增量修改后的全量一致性审计：链条完整性 / 交叉引用有效性（39 类引用）/ 跨文件数字一致性 / README 与实际文件对齐 / frontmatter 一致性。

### 审计结果

- **链条**：README→01→…→17→review-log 完整无断（19 文件逐一核对 previous/next）。
- **交叉引用**：39 类 § 引用全部解析（12 个被引文件的章节清单逐一比对，无悬空引用）。
- **数字一致性**：12h / 14 类进程 / vpcId%500%15（含 -1/0/2^32-1 特例）/ 30-40 分钟 / 85-94% / ECHO 310s——跨文件全部一致。
- **README**：索引 17 章 + review-log 全行对齐；掌握卡 14 条；语料盘点 11 行。
- **frontmatter**：18 文件 series 字段一致（review-log 无 frontmatter 属正常）。

### 修正项（2 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 06 §6 表头 | "其余为参数/位定义级"未含第 8 条口径冲突 | 改"其余为参数/位定义级 + 1 项口径冲突" |
| 2 | 06 §6 | uniqVpcId 开口仅在 02 §9.2，未登记进总表 | 补第 9 条 |

### 结论

**Round 14 通过**（全系列综合审计完毕；2 处修正应用；链条/引用/数字/README/frontmatter 五维全部一致）。

---

## Round 15（2026-10-11，备查源审计：09/13 + 补遗）

### 方法

把剩余"备查"材料中的 Gen2 相关两份读掉并做冲突审计：docs-by-topic/09（第一性原理，599 行）、13（可复现性，409 行）；逐条对照系列既有主张。

### 审计结果

- **无冲突**：09 的关键主张（确认 3 次/5000 阈值/灰度 8 步切换/双 ZK/有状态无状态对照）与系列逐条一致；13 为边界类材料（不可移植组件/替代方案），与 18 §7 边界一致。
- **新料识别（09）**：定位框架（配置同步引擎）、约束表、控制回路/PID、推送+对账三模型、Saga vs 2PC/事件溯源、Kafka 不直推三问题、复刻优先级 P0-P2、复刻者六个坑——**补入新章 18 + 10 §3**。
- **13 处置**：可复现性边界（vpcko/DPDK 不可移植；标准 Linux 替代）→ 18 §7 一句收录；搭建指南不纳入。

### 补遗（3 处，均已落盘）

| # | 位置 | 内容 |
|---|---|---|
| 1 | 18（新章） | 第一性原理与复刻指南（定位/约束/控制回路/四个抉择/优先级/六个坑/边界） |
| 2 | 10 §3 | 确认降噪参数（ConfirmCount=3 / Interval=5min / 重置 2×Interval / 15 分钟窗口 / PID 类比） |
| 3 | README | 索引/卡 15 条/阅读顺序/语料盘点（09/13 已审计；zk-knowledge 仍备查） |

### 结论

**Round 15 通过**（09/13 审计无冲突；3 处补遗落盘）。

---

## Round 16 审查（2026-10-11，Round 15 补遗全量逐条）

### 审查范围

新章 18 全篇 + 10 §3 补遗 + README 更新，逐句对照 docs-by-topic/09（599 行）与 13（边界）。

### 修正项（1 处，已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 18 §1 卡 5 | "1% 问题 × 十万台 = 1000 台"与材料原例不符（源：5000 台→50 台受影响） | 改按材料原例（5000→50），去掉自推数字 |

### 全量核对确认（逐条）

- **18 §0**：先做对账 / 推送是加速对账是保底 / 开环必漂移——对上（§11/§2）。
- **18 §1**：定位引文（集中式数据库→十万台内核）/ 闭环 / 混合模型 / Saga / 优先级——对上。
- **18 §2**：SDN 对比与约束表（十万台 / 85-94% / 分区 / vpcko / Py2.7 / 秒级）——对上。
- **18 §3**：开环三失效（ZK 断连 / netlink 假成功 / 内存错误）/ 收敛上限 / 救场三例（#6/#10/#14）——对上。
- **18 §4**：纯推送两证（#2/#22）/ 纯拉取两因（1-3M children/s、85% 白拉）/ Saga 三否（XA / 事件原子性 / TB 重放）/ Kafka 不直推三问题 / 灰度——对上。
- **18 §5/§6**：P0-P2 十条与六个坑——逐条对上。
- **10 §3**：ConfirmCount=3 / Interval=5min / 重置 2×Interval / 15 分钟窗口 / PID 三项 / 为什么不是 1 或 5——对上。
- **README**：索引 / 卡 15 条 / 阅读顺序 / 语料盘点（09/13 已审计、zk-knowledge 备查 832 行）——一致。

### 结论

**Round 16 审查通过**（1 项修正已应用；Round 15 补遗全量核对完毕，无遗留）。

---

## Round 17（2026-10-11，备查源审计：zk-knowledge + 背景概念文档）

### 方法

读 test-good/01-zk-distributed/zk-knowledge.md（445 行全读）+ zk-learning-path（387 行定性）+ 背景概念文档 05-10 主张级抽查（grep 关键主张）。

### 审计结果

- **zk-knowledge**：**无冲突**（Observer 池/session 400s/zxid 40-48 位/commitProcessor/同步器状态/Gen2 答案表逐条一致）；新料 3 项 → 落 2（内存公式 → 01 §2；/syncSlave 路径 → 13 §2；branch 名不入正文）。
- **zk-learning-path**：教学路径（阶段/步骤组织）——非机制语料，不提取（README 已注）。
- **背景概念 05-10 抽查**：无机制冲突；**新增 2 项证据**——(a) vgwType 旧口径见证增至 5+ 处（05-routing VPNGW=0、09-hybrid-cloud VPN=0、08-cross-vpc PVGW=3/CCN=4）；(b) **EIP SNAT 位置冲突（新）**：场景 4（MCP）vs 07-eip-nat/11-gateway-detail（VPCGW）。

### 补遗（4 处，均已落盘）

| # | 位置 | 内容 |
|---|---|---|
| 1 | 01 §2 | 内存公式（Watch≈271B/Znode≈645B；26,446 VM 例；512GB Observer）+ 2019-02-12 实测（2.5M/s、160Mbps） |
| 2 | 13 §2 | remoteLastSeenZxid 存于目标 ZK /syncSlave/lastSeenZxid |
| 3 | 14 §9 第 1 条 | vgwType 旧口径见证增至 5+ 处（裁决不变） |
| 4 | 14 §9 第 6 条（新增） | EIP SNAT 位置冲突（场景 4 为准，标【开放】） |

### 结论

**Round 17 通过**（最后一批备查审计完毕；无冲突，4 处补遗落盘；主要机制语料已审计/提取；剩余（速查表 / same-vpc-communication 全文 / 主文档未挖章节 / docs-by-topic 01-04）已在 README 登记）。

---

## Round 18 审查（2026-10-11，Round 17 补遗全量逐条）

### 审查范围

Round 17 的 4 处补遗（01 §2 / 13 §2 / 14 §9 ×2）+ README 语料盘点更新 + Round 17 记录，逐句对照 zk-knowledge（445 行）与背景文档抽查证据。

### 修正项（2 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 14 §5.5 | VPCGW 数据面（add_wan SNAT/DNAT）未挂 §9 第 6 条 EIP SNAT 冲突交叉引用——两处口径并列无联动 | 补"（注：EIP 的 SNAT 位置口径冲突见 §9 第 6 条）" |
| 2 | review-log R17 | 结论"无剩余备查项"过度声称（README 仍登记速查表 / 概念全文 / 主文档未挖章节 / docs-by-topic 01-04） | 改"主要机制语料已审计/提取；剩余已登记" |

### 全量核对确认（逐条）

- **01 §2**：内存公式（271/645B；290,906 Watch≈78.8MB；100 台≈7.8GB；512GB）+ 2019-02-12（2.5M/s、160Mbps）——与 zk-knowledge 逐条对上。
- **13 §2**：/syncSlave/lastSeenZxid——对上。
- **14 §9 第 1 条**：05-routing（VPNGW=0）/ 09-hybrid-cloud（VPN=0）/ 08-cross-vpc（PVGW=3、CCN=4）——三处见证逐条对上；"5+ 处"计数成立。
- **14 §9 第 6 条**：场景 4（母机 SNAT）/ 07-eip-nat / 11-gateway-detail（VPCGW）——三处口径逐条对上；裁决倾向与【开放】标注完整。
- **README**：语料盘点两行更新与实测一致。

### 结论

**Round 18 审查通过**（2 项修正已应用；Round 17 补遗全量核对完毕，无遗留）。

---

## Round 19（2026-10-11，主文档末批章节审计：5.x + 6.2）

### 方法

读主文档第五部分（对账层深度 5.1-5.4，240 行）+ 第六部分 6.2（异常路径，96 行），逐条对照系列 10/15/18。

### 审计结果

- **无冲突**：确认参数（ConfirmCount=3 / 重置 max(2×ConfirmInterval, 2×Interval)）与 10 §3 一致；6.2 场景 2（crash ~32s）与 15 §6 已落内容一致；**5.4 的 missing/extra 语义佐证 more/less 修正方向**（06 §6 第 8 条）。
- **新料 4 项** → 落 3：七种失败模式枚举（→ 18 §3）；DiffHistoryStore 实现 + 6 种策略链 + 4 种 diff 模式（→ 10 §3）；**Gen2 专属 ZK 全宕机时间线**（→ 15 §6，此前只有 Gen1 口径）。Gen3 时间线（vpc5.0）不入。

### 补遗（4 处，均已落盘）

| # | 位置 | 内容 |
|---|---|---|
| 1 | 15 §6 | ZK 全宕机 Gen2 时间线（nfv-proxy 暂停消费 → broker BinlogIncomplete → 恢复续消费补推） |
| 2 | 10 §3 | DiffHistoryStore（LevelDB / key 格式）/ 6 种策略链（含 RateLimit 1000）/ 4 种 diff 模式 |
| 3 | 18 §3 | 七种失败模式枚举 |
| 4 | README | 主文档行更新（5.x/6.2 已审计；剩余 API 层 / 2.5-2.9） |

### 结论

**Round 19 通过**（主文档末批章节审计完毕；无冲突，4 处补遗落盘）。

---

## Round 20 审查（2026-10-11，Round 19 补遗全量逐条）

### 审查范围

Round 19 的 4 处补遗（15 §6 / 10 §3 / 18 §3 / README）+ Round 19 记录，逐句对照主文档 5.1-5.4 与 6.2 原文。

### 修正项（2 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 10 §3 表头 | "（推导）"已过时（现含两段【实证】） | 改"（确认降噪【实证】 + 位标志 / CheckStrategy【推导】）" |
| 2 | 10 §3 | RateLimit 1000 与 DelMoreThreshold 5000 两阈值并存易混 | 补区分注（前者限差异总量、后者限删除量） |

### 全量核对确认（逐条）

- **15 §6**：Gen2 时间线（T+1s / T+5s / T+20s / 恢复续消费）与 6.2 逐条对上。
- **10 §3**：DiffHistoryStore（key 5 段格式 / IsConfirmed≥3 / Cleanup）/ 6 策略（RateLimit>1000 / Composite serial-parallel / Repair task.Fix）/ 逆序回滚 / 4 diff 模式——逐条对上。
- **18 §3**：七失败模式与 5.1 表逐行对上。
- **README**：主文档行更新一致；Round 19 记录自洽。

### 结论

**Round 20 审查通过**（2 项修正已应用；Round 19 补遗全量核对完毕，无遗留）。

---

## Round 21（2026-10-11，收尾）

- 系列封卷：20 文件 / ~3440 行（18 章 + README + review-log）；Round 1-20 全部闭环。
- README 增"系列状态（收尾）"节；review-log 行更新至 Round 1-21。
- 最终状态：全链路覆盖 + 全部可读语料审计/提取 + 开口总账登记（2 闭合 / 3 冲突 / 5 参数）；剩余登记项明确为可选。
- 后续：vpc5.0 系列（材料已盘点）。

---

## Round 22（2026-10-11，补缺：高可用与容灾章）

### 背景

用户质疑"多活/容灾未梳理"——核实成立：此前只有散点（ZK 同步器 / AZ 子网 / HARP / Twins / 恢复时间表），无系统章节。

### 方法

全库扫描"多活/容灾/灾备"关键词 + 读 docs-by-topic/04 §3（部署拓扑）、46 §3.5.1（ansibleDisaster）、37（ZK 容灾锚点）、13-dns-quota §2（AZ 故障行为）、architecture-evolution（双 Kafka 容灾）。

### 产出

- 新章 **19-高可用与容灾**（故障域五层 / 组件 HA 清单 / AZ 容灾 / Region 多地域 / ZK 容灾三件套 / 数据级 / RTO-RPO 表 / 边界）。
- README：索引 / 卡 16 条 / 阅读顺序 / 语料盘点（04 行）/ 收尾节更新。
- 链条：18 → 19 → review-log。

### 结论

**Round 22 完成**（补缺章落盘；多活/容灾维度从散点 → 系统收拢；完整多地域方案（deployment.md）不在工作区，标【开放】）。

---

## Round 23（2026-10-11，容灾专项深审 + 19 章深修）

### 背景

用户："容灾再深度 review，感觉还是不够"——核实：19 章首版是散点收拢，未吃透 docs-by-topic/08（此前只读锁章节）。

### 方法

全读 docs-by-topic/08（590 行，分布式架构与分布式问题）——failover / 四竞态 / 四种锁 / 崩溃恢复 / 分区容忍 / 五薄弱点 / 设计决策；对照 19 章首版逐条。

### 审计结果

- 19 章首版与 08 **无冲突**（failover 3s / 恢复时间 / watchdog 退避均一致）。
- 08 提供六个新维度 → **19 章全量重写（深修版）**：组件分布式角色表 / 主备 failover 深读（时间线 + 四竞态 + 为何不用 ZK 选举）/ 四种锁失败模式 + 设计决策 / 崩溃恢复对比（五组件）/ 分区容忍（ZK/Redis/Kafka）/ 五薄弱点表。

### 产出

- 19 章：110 行 → ~200 行（12 节）。
- README：索引 / 卡 16 / 语料盘点（08 行）/ 收尾节更新。

### 结论

**Round 23 完成**（容灾维度从"散点收拢"→"机制级深修"；新增 failover 竞态 / 锁失败模式 / 薄弱点三块硬料）。

---

## Round 24 审查（2026-10-11，Round 23 深修全量逐条）

### 审查范围

19 章深修版全篇（184 行）+ README 更新，逐句对照 docs-by-topic/08（590 行）及 04/46/37/13-dns-quota/zk-knowledge。

### 修正项（2 处，均已应用）

| # | 位置 | 问题 | 修正 |
|---|---|---|---|
| 1 | 19 §6 标题 | "（五组件）"与表格不符（08 五组件含 Fusion/vpc-broker，本表为母机/网关拆分 + ZK 场景） | 去掉"（五组件）" |
| 2 | README 收尾 | 行数 ~3660（实 ~3690）与轮次描述（"21 轮增量审查"计数不准） | 修正 |

### 全量核对确认（逐条）

- **§3** 组件角色表：六组件行与 08 §1.1 逐条对上；关键数字（3s / 10s）对上。
- **§4** failover：架构（zk_master / zk_slave / 65358 / dynamic_netlink_state）、时间线（T+0→T+10s）、四竞态（inode / sleep(1) / m2s=None / ha.py 6s）、ZK 选举对比——逐条对上。
- **§5** 四种锁：ZK（/SNAPSHOT_LOCK / LOST 自杀 / Kazoo timeout=400）、Redis（node_id 校验 / 无 fencing 10s / AutoRenew=false 10min / 主从异步）、MySQL（FOR UPDATE / Gap Lock→GET_LOCK / sendState=1→Mediator 60s / 40s 清理）、inode——逐条对上；设计决策（DB 锁 / 不用 Raft / 无 partition key）对上。
- **§6** 恢复时间（30s-2min / 30-40min / 10s / 1-5min）对上；**§7** 分区容忍（EXPIRED_SESSION_STATE→_panic / slot 级 / 双 Leader→幂等 / #22）对上；**§8** 五薄弱点表逐行对上。
- **§9-§11**：AZ / Region / 数据级 / RTO——与 04/46/13-dns-quota/15 对应对上。

### 结论

**Round 24 审查通过**（2 项修正已应用；Round 23 深修全量核对完毕，无遗留）。
