---
title: "16-业界对比：Calico/OVN、K8s、Andromeda/Hyperplane/VFP（为什么自建）"
series: "VPC 二代（Gen2）架构系列"
previous: "15-hard-problems-and-incidents.md"
next: "17-ops-monitoring.md"
source_refs:
  - "vpc/test-good/06-open-source-vs/vs-calico-ovn.md（240 行全读）"
  - "vpc/test-good/06-open-source-vs/vs-industry-practices.md（237 行全读）"
  - "vpc/test-good/06-open-source-vs/vs-k8s-controller.md（379 行全读）"
---

# 16-业界对比：Calico/OVN、K8s、Andromeda/Hyperplane/VFP（为什么自建）

> 面试高频："你们为什么不用 Calico/OVN/K8s？和 Google/AWS/Azure 比怎么样？"本篇把三份对比材料收成一篇。
> 代际说明：对比对象是 VPC 控制面整体；VPC 侧链路口径以三代（vpc5.0）为主，标【vpc5.0】；二代对应差异随文注（如"对账"在二代有修复策略、三代只检测不修复）。

## §0 它干了什么（例子先行）

面试官："Calico 和 OVN 都是成熟的开源 SDN 方案——为什么你们要自建 VPC 控制面？"

30 秒回答（材料标准答案）：

> "因为规模和多租户隔离。Calico 的 BGP 没有租户概念——不能用 GRE Key 区分 VPC，而且 BGP table 撑不住千万级路由。OVN 的 OVSDB 是内存数据库——上限约 10 万实体，我们要管 200 万 VPC、1.6 亿实体——超出 1600 倍。这不是说它们不好——它们在各自的设计目标里都是最优的，只是公有云 VPC 的规模超出了范畴。"

把这句话展开：Calico/OVN（§2）、K8s（§3）、业界大厂（§4）。

## §1 掌握卡（6 条）

1. **Calico = BGP 驱动的纯 L3**：无 Overlay（不封装）、延迟最低，但 BGP 只有"路由"没有"租户"——VPC-A 和 VPC-B 的 10.0.1.0/24 在 BGP 里无法区分；VPC 用 GRE Key=vpcId 做隔离基石。
2. **OVN = OVSDB(RAFT) + Geneve + OpenFlow**：逻辑网络"编译"成流表；但 OVSDB 全内存 + RAFT 同步——实测上限约 10 万实体，VPC 需要 1.6 亿（200 万 VPC × (1 Switch+1 Router+50 Port+30 Route)）。
3. **K8s = Watch + Reconcile（单一 etcd 真相）**；VPC = Event Sourcing + 定时对账（多层缓存、无单一真相）——两种模式都成立，**约束决定架构**。
4. **reconcile 语义不同**：K8s 的 reconcile 是"主要执行路径"（修复=重新 apply）；VPC 的对账是"验证路径"（检测+报告）——二代另有修复策略（见 10），三代口径为"只检测不修复"【vpc5.0】。
5. **业界大厂**：Andromeda（Spanner 强一致 → 不需要对账）、Hyperplane（功能分片 Cellular）、VFP（统一流表编译）——差距的根因是**基础设施投入**（Spanner/Jupiter 自研 10+ 年）。
6. **最大启示是形式化验证**：AWS 用 TLA+ 验证 Hyperplane——可用来验 Lua 原子性/状态机/对账不变量。

## §2 Calico / OVN（开源 SDN 对比）

【实证】vs-calico-ovn：

### 2.1 Calico——BGP 驱动的纯 L3

```
K8s API Server → Calico Controller → Felix Agent（每节点）+ BIRD（BGP daemon）
                                          ↓ Linux kernel route table
                                    BGP → 物理交换机 Router
数据面：Node1 直接转发到 Node2（无 GRE/VXLAN 封装）
```

**为什么 VPC 不能用 Calico 的 BGP**：
- BGP 只有"路由"没有"租户"——两个 VPC 的 10.0.1.0/24 会路由冲突；VPC 用 GRE tunnel（Key=vpcId）区分。
- 规模：200 万 VPC × 5 路由 = 1000 万条——BGP table（~100K）无法承载；convergence 大量路由时分钟级，VPC 要秒级。
- BGP 不能表达安全组/ACL 等超越路由的策略。

| 维度 | Calico (BGP) | VPC（控制面下发） |
|---|---|---|
| 传播 | 分布式（节点间 BGP 交换） | 集中式（控制面下发到 Agent） |
| 传播延迟 | BGP convergence ~1-10s | 推送 <100ms【vpc5.0 口径】 |
| 路由规模 | ~100K | DB 容量（CDB 可扩） |
| 新增节点 | BGP peer 建立 ~10s | Agent 连 Broker + 拉全量 ~30s |

### 2.2 OVN——OVSDB + OpenFlow

```
OVN Northbound DB（逻辑网络：Logical Switch/Router/ACL）
  → ovn-northd（编译）→ Southbound DB（流表）
  → ovn-controller（pull）→ OVS（OpenFlow）
Overlay：Geneve（VNI 24bit）
```

**规模瓶颈算术**：OVSDB 是内存数据库 + RAFT——上限约 10 万实体；VPC 实体 = 200 万 × (1+1+50+30) ≈ **1.6 亿**（超上限 1600 倍，即 OVN 的 scale 只有 VPC 需求的 0.06%）。

**数据面 OVS(OpenFlow) vs DPDK**：OpenFlow 是软件 Match-Action（每包解析 ip_dst+vpc_id），流表缓存 ~10K；DPDK 是用户态轮询 + 硬件加速 hash/LPM（O(1)），表容量 ~100K，性能差约 10 倍。

### 2.3 三方路由模型与安全策略对比

| | Calico | OVN | VPC |
|---|---|---|---|
| 路由产生 | Felix → BIRD → BGP announce | northd 编译 → SB → pull | API → DB → Kafka binlog → 下发【vpc5.0 链路口径；二代为 nfv-proxy/分区 ZK】 |
| 延迟 | 1-10s | 1-5s（pull 间隔）+ 编译 | p50 ~3s / p99 ~5s（设计口径） |
| 规模上限 | ~100K 路由 | ~10 万实体 | 200 万 VPC（已验证） |
| 安全 | K8s NetworkPolicy（iptables O(n)） | Logical ACL（OpenFlow） | SG（有状态/CT 表）+ ACL（无状态），DPDK 规则表 ~10000 条 |

## §3 K8s Controller（控制面设计模式对比）

【实证】vs-k8s-controller：

### 3.1 两种哲学

```
K8s：   "用户声明 desired state，controller 不停尝试让它成真"
        etcd（单一真相）→ informer cache → workqueue → reconcile(key)

VPC：   "配置变更是一个事件流——消费事件、构建快照、定期验证"
        MySQL → Kafka binlog → Fusion → Broker → Agent → DPDK【vpc5.0 链路口径】
        对账 = 独立定时模块（全量 24h / 增量 5min），检测差异
```

### 3.2 反事实推演（材料精华）

- **把 K8s 模式套 VPC**：200 万 VPC → 至少 200 万 watcher（etcd 上限 ~100K）→ 内存/带宽不够；reconcile 串行处理全部 VPC = 278 小时；无状态 controller 每次重启要从 etcd 重载全量（本地缓存 24GB）。
- **把 VPC 模式套 K8s**：Kafka 多一层延迟（K8s 事件模型已够）；定时全量扫描浪费；多级缓存的不一致窗口对 10 万 Pod 规模不必要；Broker+Agent 增加故障点。
- **结论：两种模式都对了——约束不同；把对方设计搬进自己的约束都会失败。**

### 3.3 四个关键维度

| 维度 | K8s | VPC |
|---|---|---|
| 事件模型 | Watch（实时推送、断线重连、ResourceVersion 防重） | Event Sourcing（binlog 持久化、可重放、Consumer Group） |
| 一致性 | etcd Raft 强一致（CP） | MySQL 强一致 + 多层缓存副本 → 最终一致（AP）+ 对账 |
| 规模 | ~150K Pod / etcd 8GB | 200 万 VPC / 1 亿配置 / 日百万变更 |
| 对账语义 | 执行路径（requeue+重试） | 验证路径（只检测不修复【vpc5.0】；二代有修复策略） |
| 隔离 | Namespace（逻辑） | VPC（GRE 物理隔离 + vpcId 逻辑隔离） |

### 3.4 标准回答："为什么不用 K8s Operator 模式"

> "因为规模：etcd 撑不住 200 万 VPC 的 watch；reconcile loop 串行处理 200 万资源要一个多月；Operator 无状态模型不适合我们——需要 LevelDB 本地缓存 24GB；K8s Namespace 是逻辑隔离，我们要 GRE 物理隔离。"
>
> 追问"为什么又做 reconcile"："不是重复——是补偿。K8s 的 reconcile 是主要执行路径；我们的对账是验证路径——正常路径已经把配置下发到 DPDK 了，对账只验证'没有遗漏'。对账失败不影响 VM 创建——影响的是'发现 bug 的延迟'。"

## §4 业界大厂（Google / AWS / Azure）

【实证】vs-industry-practices：

### 4.1 Google Andromeda——强一致、无对账

```
Spanner（全球强一致，Source of Truth）→ Honeybee Controller → SDVNIC（VM Guest 驱动）
网络：Jupiter（自研交换机 ASIC）；LB：Maglev
写入成功 = 配置生效 → 不需要对账
```

**差距根因**：Google 有 Spanner（自研 10+ 年）+ Jupiter——"从网络硬件到控制面全栈自研"；VPC 是"在商用基础设施上搭控制面"。**没有全球强一致数据库 → 用 MySQL + 对账；没有自研交换机 → 用 GRE 做 overlay**——不是最优解，是给定约束下最 pragmatic 的解。

### 4.2 AWS Hyperplane——Cellular 分片 + 形式化验证

- Hyperplane = 按**服务类型**分 Cell（IGW Cell / NATGW Cell / NLB Cell / Endpoint Cell）——blast radius 控制在 Cell 内。
- VPC 的分片是**容量分片**（SET，每 SET ~50 万 VPC【vpc5.0】）；"VPC 控制面只有一个核心功能（配置管理），功能单一 = 不需要 Cellular"。
- AWS 用 **TLA+** 验证协议正确性——可发现 Redis Lua 原子性问题/DeleteConfig 语义 bug/状态机非法转换。VPC 可引入的三个场景：Lua 组合执行不变量、任务状态机安全属性、对账一致性条件。

### 4.3 Azure VFP——统一流表

- 把 ACL+SG+路由+NAT **在控制面预编译**成统一流表（含优先级合并/规则排序），Agent 只执行；VPC 是 Agent 本地组合（控制面轻、Agent 重）。
- 权衡：增新规则类型——VFP 改编译器 vs VPC 改 Agent 代码。

## §5 可借鉴清单（材料原文）

| 周期 | 方向 | 来源 |
|---|---|---|
| 短期 | Per-VPC 指标（top-N VPC）、对账趋势告警（增长速度而非绝对值）、DeleteConfig 端到端测试 | AWS / Google |
| 中期 | TLA+ 验 Lua 原子性、Leader Fencing Token（epoch 防脑裂）、统一流表 proto | AWS / Chubby / VFP |
| 长期 | Cellular 分片（按 VPC 类型）、全球强一致存储、SmartNIC 硬件加速 | AWS / Spanner / Azure |

## §6 面试口径（速记卡）

1. **"Calico 无租户、OVN 有上限——不是不好，是量级不同"**："1 万 Pod（Calico）/ 10 万实体（OVN）/ 200 万 VPC（自建）——各属各自的量级。"
2. **"两种控制面哲学都对——约束决定架构"**："K8s Watch+Reconcile 因为单层 etcd 够用；VPC Event Sourcing+定时对账因为多层缓存+规模。"
3. **"VPC reconcile ≠ K8s reconcile"**："一个是执行路径（重新 apply），一个是验证路径（检测+报告）。"
4. **"Andromeda 有 Spanner——我们没有"**："强一致免对账 vs MySQL+对账——基础设施差距的 pragmatic 补偿。"
5. **"AWS Cellular = 功能分片，VPC SET = 容量分片"**【vpc5.0】；**"Azure VFP = 把复杂度挪到控制面"**。
6. **"业界给的不是'改进即上线'，是可参考的模式"**。

## §7 边界

1. 三份材料为 vpc5.0 时代口径（VPC 侧引用 Fusion/Broker/SET/Lua/DeleteConfig 等）——本篇已标注；对比结论（隔离/规模/哲学）跨代成立，二代对应链路随文注。
2. 数字口径：OVN 上限"实测约 10 万实体"、Calico "~100K 路由"、K8s "~150K Pod"均为材料引用，未独立验证【实证，单源】。
3. 本篇服务面试口径；具体机制以 01-15 篇为准。
