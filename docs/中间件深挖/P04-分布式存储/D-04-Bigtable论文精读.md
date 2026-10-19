# P04-分布式存储 D-04 — Bigtable 论文精读（OSDI'06）

> **定位**：P04 第 4 篇（论文线第 2 篇）。三层齐：**例子（Webtable 一行）+ 原理 + 坑/演进**。
> **论文版本**：OSDI'06 全文（`P04-分布式存储/papers/bigtable-osdi06.pdf`，14 页）——Chang/Dean/Ghemawat 等。
> **质量标准**：完整链路 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：Webtable 一行的一生

```
行键：com.cnn.www（反转 URL，同域相邻 → 局部性）
写入（RowMutation 原子）：anchor:www.c-span.org="CNN" 新增 + anchor:www.abc.com 删除 → Apply 一次原子提交
读取（Scanner）：按列族/时间范围迭代；contents: 取最近 3 个版本（t3/t5/t6 降序）
存储：commit log（redo）→ memtable（内存有序缓冲）→ 冻结构 SSTable（GFS 文件）
      → 后台 merging/major compaction 合并；删除靠"墓碑+大合并"彻底消失
```

**一句话**：Bigtable = **"稀疏多维有序 Map（行/列/时间戳→字节串）+ tablet 分区 + LSM 式存储（memtable/SSTable）+ Chubby 协调"**——给结构化数据一个"可伸缩但不关系"的家。

---

## 二、原理（论文机制）

### 2.1 数据模型：三维有序 Map（§2，论文最出名的定义）

```
(row:string, column:string, time:int64) → string
行：任意字符串（≤64KB，常用 10-100B）；单行读写原子（多列也原子——并发推理的基石）
   按字典序分区 → tablet（行区间，分发/负载均衡单位）；反转域名保局部性（com.google.maps/...）
列族：family:qualifier；列族是访问控制/压缩/记账单位（数量少、 почти 不变；列无限）
时间戳：64 位（客户端指定或微秒 real time）；降序存；GC 策略：只留最近 n 版 / 只留 7 天内
```

### 2.2 三级定位（§5.1，B+ 树式层次）

```
Chubby 文件 → root tablet（第 1 个 METADATA tablet，永不分裂）
→ METADATA tablets → 用户 tablets
METADATA 行 ~1KB；128MB 的 METADATA 可寻址 2^34 个 tablet
客户端缓存 + 预取：空缓存 3 次 RTT；缓存过期最多 6 次（miss 才发现）
```

### 2.3 写/读路径（§5.3：LSM 式三件套）

```
写：合法性+鉴权（Chubby 读 writer 名单，缓存命中）→ commit log（group commit 攒批）→ memtable
读：memtable + SSTables 归并视图（都有序 → 高效合并）；读可与分裂/合并并发
压缩：minor（冻 memtable → SSTable，降内存/缩恢复）→ merging（定期合几个）→ major（全合成 1 个，无删除信息——敏感数据彻底消失 + 资源回收）
```

### 2.4 优化四件套（§6，面试高频区）

| 优化 | 机制 | 效果/数据 |
|---|---|---|
| **locality group** | 列族分组，各自独立 SSTable（+ 可选内存态/独立压缩） | 只读元数据不碰 page 内容；Webtable 压缩 10:1（Bentley-McIlroy 跨页 boilerplate + 16KB 快压） |
| **两级缓存** | Scan Cache（行级结果）+ Block Cache（SSTable 块） | 随机读 64KB 块只用 1KB → 块缓存是命脉（顺序读命中极高） |
| **Bloom** | 按 locality group 建，判"某 SSTable 有无某行列" | 不存在行不碰盘（大量 miss 场景救星） |
| **单 commit log** | 全 tablet 共混一个物理日志（group commit 更大） | 恢复时按 (table,row,seq) 排序分发（64MB 分段并行排；双写线程+seq 去重防 GFS 抖动） |

### 2.5 协调：Chubby 的五件事（§4-5.2）

```
选主（同一时刻一个 master）/ bootstrap 位置 / tablet 发现与死亡判定 / schema / ACL
tablet server 靠 Chubby 独占锁文件保活：丢锁 → 停服；文件被删 → 自杀（master 重分配）
master 启动四步：抢 master 锁 → 扫 servers 目录 → 问各 server 已有分配 → 扫 METADATA 补未分配
Chubby 不可用代价：实测 14 集群 0.0047% 时间部分数据不可用
```

---

## 三、怎么做 / 有什么坑（§7-9 实测与教训）

| 数据/教训 | 论文值 |
|---|---|
| 扩展性 | 1→500 tablet server，聚合吞吐 ~100 倍（随机读受 64KB 块拖累最差） |
| 单 server 随机读 | ~1200 ops/s（64KB 块只用 1KB——块大小是调参点，常改 8KB） |
| 内存读/写/scan | 10811 / 8850 / 15385 ops/s（scan 摊薄 RPC 最快） |
| 生产规模 | 388 集群、24,500 tablet server；14 繁忙集群 120 万 req/s |
| 故障观 | 不止 fail-stop：内存/网络腐烂、时钟偏斜、hung 机器、非对称分区 → checksum + 去假设 |
| 功能克制 | 曾计划通用事务 → 实际只要单行事务（"等需求明确再加功能"） |
| 监控 | RPC 采样 trace + Chubby 注册表（版本/流量/延迟可见） |
| 简单性 | 会员协议重写多次 → 最终砍到"只用 Chubby 常用特性"的简单版 |

---

## 四、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **只保证单行原子** | 分布式事务 | 真实需求只是单行（索引需求另做专用机制）——通用分布式事务贵且少用 |
| **反转 URL 做行键** | 随机/哈希行键 | 同域相邻 → 压缩率高（10:1）+ 范围读高效——把"局部性"交给 schema 设计 |
| **列族是管理单位** | 每列独立配置 | 访问控制/压缩/记账按"同类数据"批量管（列无限但族少） |
| **三级定位 + 客户端缓存** | master 查位置 | master 只管分配不管寻址（轻负载）；缓存+预取把定位压到 0-1 次 RTT |
| **不可变 SSTable** | 原地更新文件 | 免读锁同步 + 分裂只共享文件 + 删除变 GC——LSM 哲学的全部红利 |
| **单 commit log 共混** | 每 tablet 独立日志 | group commit 更大 + 文件数可控；恢复排序分发是可接受的一次性代价 |

---

## 五、面试 Q&A（含面试官意图）

**Q1："Bigtable 数据模型？"**（意图：三维定义）
稀疏多维有序 Map：(row, column, time)→bytes；行原子；列族是管理单位；时间多版本+GC 策略。

**Q2："tablet 怎么定位？"**（意图：层次设计）
Chubby 文件→root tablet→METADATA→用户 tablet（三级，类 B+树）；客户端缓存+预取。

**Q3："写路径？读路径？"**（意图：LSM 三件套）
写：commit log（group commit）→ memtable → minor/major compaction；读：memtable+SSTables 归并 + 两级缓存 + Bloom。

**Q4："和 GFS 什么关系？"**（意图：论文串联）
SSTable/commit log 存 GFS；Chubby 做选主/发现/schema；Bigtable 是"结构化数据层"，GFS 是"字节层"。

**Q5："为什么只要单行事务？"**（意图：功能克制）
真实需求调研结论；分布式事务为索引等场景另做专用机制——通用方案贵且少用。

---

## 六、章节依赖

- **上游**：D-03（GFS：SSTable 文件层 + Chubby 协调层都来自它）
- **下游**：D-05 Dynamo（去中心化对照：无 master/Chubby）；D-01/D-02（LSM 理论落点：memtable/SSTable/compaction 就是 LSM）
- **横链**：HBase（开源实现，几乎照抄架构）；Cassandra（Bigtable+Dynamo 混血）

---

## 七、验证锚点（论文原文）

| 声明 | 论文位置 |
|---|---|
| 三维模型/行原子/列族/时间戳 | §2（`bigtable-osdi06.pdf`）+ Figure 1 |
| RowMutation/Scanner/单行事务 | §3（Figure 2/3） |
| Chubby 五件事/0.0047% | §4-5.2 |
| 三级定位/缓存预取 | §5.1（Figure 4） |
| 选主四步/分裂提交 | §5.2 |
| 写/读/compaction 三级 | §5.3-5.4 |
| locality/压缩10:1/两级缓存/Bloom/单日志 | §6 |
| 实测数（1200/10811/500 server/120万） | §7（Figure 6, Table 1-2） |
| 教训（单行事务/监控/简单性） | §9 |
