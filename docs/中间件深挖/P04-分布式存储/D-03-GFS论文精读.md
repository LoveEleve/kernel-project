# P04-分布式存储 D-03 — GFS 论文精读（The Google File System, SOSP'03）

> **定位**：P04 第 3 篇（论文线第 1 篇）。三层齐：**例子（写旅程）+ 原理（论文机制）+ 源码级细节（论文章节锚点）**。
> **论文版本**：SOSP'03 全文（`P04-分布式存储/papers/gfs-sosp2003.pdf`，8 页）——Ghemawat/Gobioff/Leung。
> **质量标准**：完整调用链 / 设计哲学（反面假设）/ 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：一次写的 7 步旅程（论文 §3.1）

```
客户端写 chunk C 的某段数据：
① 问 master：谁是 primary（租约持有者）+ 其他副本位置（无租约则 master 选一个授予）
② master 回复：primary 身份 + secondary 列表（客户端缓存）
③ 客户端把数据 pipeline 式推送到所有副本（链式转发，拓扑就近；数据先进 LRU buffer，不算提交）
④ 全副本确认收到数据后，客户端发写请求给 primary
⑤ primary 分配连续 serial number → 自己先按序执行 → 转发给 secondaries（同序执行）
⑥ secondaries 全部回复 primary
⑦ primary 回复客户端（任一副本出错 → 整个写算失败，区域变 inconsistent，客户端重试）
```

**一句话**：GFS 写 = **"租约定序（primary 串行化）+ 数据链式推送（控制与数据分离）+ 全副本确认"**——失败不回滚，靠重试收敛。

---

## 二、原理（论文机制）

### 2.1 四个反常识假设（§1，整篇论文的根）

| 假设 | 推翻的传统 | 导致的设计 |
|---|---|---|
| 故障是常态（廉价商用机成百上千） | 故障是异常 | 监控/容错/自动恢复是内置的，不是补丁 |
| 文件巨大（多 GB 常见，100MB+ 起步） | KB 级小文件优化 | **64MB chunk**；元数据必须极省（<64B/chunk） |
| 几乎只追加、顺序读（随机写≈0） | 随机读写均衡 | record append 是原子原语；客户端不缓存数据（无收益） |
| 带宽重于延迟 | 低延迟优先 | record append/批量优化；master 不碰数据面 |

### 2.2 架构：单 master + chunkserver + client（§2.3）

```
                    ┌─ chunk handle + 副本位置（缓存）
Application ──→ master（只管元数据：namespace/映射/租约/副本决策）
     │                ▲ HeartBeat（指令↓ / 状态↑）
     └──→ chunkserver（数据面直连，Linux 本地文件存 chunk）
chunk = 64MB 固定大小 + 全局唯一 64 位 handle；默认 3 副本（可按 namespace 区域定制）
```

### 2.3 一致性模型：defined / consistent / inconsistent（§2.7，最常考点）

| 状态 | 定义 | 何时出现 |
|---|---|---|
| **defined**（已定） | 所有客户端看到相同数据**且**是某次变更的完整内容 | 无并发干扰的成功变更 |
| **consistent**（一致） | 所有客户端看到相同数据（内容可能是碎片混合） | 并发成功变更（有序执行保证相同） |
| **inconsistent**（不一致） | 不同客户端不同时间看到不同数据 | 失败的变更（部分副本写了） |

```
应用侧适配三板斧：只追加（不覆盖）+ checkpoint（只读到已定区域）+ 自校验记录（checksum/唯一 ID 去重和 padding）
```

### 2.4 原子 record append（§3.3，论文最有价值的原语）

```
语义：只给数据不给 offset，"至少一次原子追加"，返回 GFS 选定的 offset
机制：primary 检查 64MB 上限（超了就 pad 并让客户端换 chunk；record ≤ 16MB=1/4 chunk）
失败语义：任一副本失败 → 客户端重试 → 各副本可能含重复/碎片（undefined 但 consistent）
   → 应用用 checksum 验记录、用唯一 ID 去重（MapReduce 的 merged 结果 / producer-consumer 队列就靠它）
```

### 2.5 租约串行（§3.1）+ 控制/数据分离（§3.2）

```
租约：master 授予某副本 primary（60s，可无限续）；primary 给所有变更分配 serial number（全局定序）
数据链：客户端→就近副本→链式转发（用满每台机器出向带宽，避开跨交换机链路）——1MB 理想 80ms
```

### 2.6 快照 COW（§3.4）+ 元数据内存化（§2.6）

```
快照：revoke 租约 → 记 op log → 元数据复制（新文件指向同 chunk，refcount++）→ 写时建新 chunk（本地拷贝，磁盘比网快 3 倍）
元数据全内存（<64B/chunk + 前缀压缩文件名）；chunk 位置不持久化（启动时 poll chunkserver——"chunkserver 对自己有什么 chunk 有最终发言权"）
op log（唯一持久元数据）：本地+远端刷盘才应答；checkpoint 是可 mmap 的 B-tree 形态
```

---

## 三、怎么做 / 有什么坑（运维机制 §4-5）

| 机制 | 要点 |
|---|---|
| 租约管理 | primary 续租 piggyback 在 HeartBeat；master 可提前 revoke（改名场景） |
| 副本放置 | 跨机架（机架级灾难）+ 磁盘利用率均衡 + 限制单机近期新建数（新建≈即将到来的写热点） |
| 再复制优先级 | 按"离目标差几份"排序（丢 2 份 > 丢 1 份）；阻塞客户端的优先；限并发（默认集群 91 并发 clone）+ 单机带宽限流 |
| GC | 删文件改隐藏名（3 天后真删，可 undelete）；orphan chunk 靠 HeartBeat 上报比对清理 |
| stale 检测 | chunk version（租约授予时递增）；重启上报 version → 低版本视为不存在 → GC |
| checksum | 64KB 块 + 32 位校验（内存存、读时验、追加增量更新）；读不传播腐烂（请求方读其他副本） |
| master HA | op log 多机复制；shadow master（读可用，元数据滞后 <1s）；DNS 别名切换 |
| 实测数（§6） | 1000 节点/300TB；读 94MB/s 聚合；chunkserver 挂 1 台（600GB）23.2 分钟恢复（440MB/s）；master 内存 48-60MB；master 200-500 ops/s |

---

## 四、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **单 master（元数据集中）** | 分布式元数据 | 简化 placement/GC/租约决策；用"master 不碰数据面 + 64MB 大 chunk"保 master 不成瓶颈 |
| **chunk 位置不持久化** | master 记全量位置 | chunkserver 对自己有什么有最终发言权——poll 启动+心跳同步，消灭"两边视图不一致"类 bug |
| **失败不回滚（留 inconsistent，客户端重试）** | 失败回滚到一致 | 追加场景下重试收敛即可；回滚机制复杂度不值（应用侧 checksum/幂等兜底） |
| **record append 只保证 at-least-once 原子** | exactly-once | exactly-once 要 2PC 级协调；at-least-once + 应用去重 = 同等效果、零协调 |
| **追加优化、随机写"能用就行"** | 均衡优化 | 工作负载 99% 是追加（实测覆盖写 <0.05% 且多来自重试）——为真实负载设计，不为对称美学 |

---

## 五、面试 Q&A（含面试官意图）

**Q1："GFS master 存什么？为什么能放内存？"**（意图：元数据设计）
namespace + 文件→chunk 映射（持久化 op log）+ chunk 位置（不持久，poll）；<64B/chunk + 前缀压缩 → 千节点集群仅 48-60MB。

**Q2："写流程？primary 干什么？"**（意图：租约串行）
7 步：问租约→推数据（链式）→primary 定序→各副本同序执行→全确认→应答；失败留 inconsistent，客户端重试。

**Q3："record append 和普通写区别？"**（意图：原子语义）
只给数据不给 offset，GFS 选 offset 原子追加（≤16MB）；失败重试可能重复——应用 checksum 验 + 唯一 ID 去重。

**Q4："怎么防脑裂/旧副本？"**（意图：版本机制）
chunk version（租约授予递增）+ 客户端/克隆时校验；低版本副本视为不存在 → GC。

**Q5："单 master 不是单点吗？"**（意图：HA 取舍）
op log 多机复制 + 秒级重启 + shadow master 读可用 + DNS 切换；代价是"写元数据停顿窗口"（可接受，因为数据面不停）。

---

## 六、章节依赖

- **上游**：P02（CommitLog/PartitionLog 存储设计——MQ 特化存储 vs GFS 通用文件存储）；P03（租约≈选主语义，脑裂防护对照）
- **下游**：D-04 Bigtable（建在 GFS/Chubby 之上：SSTable 存 GFS + Chubby 选主/锁）；D-05 Dynamo（去中心化对照：无 master）；D-01/D-02（B+树/LSM、分区复制的理论落点）
- **横链**：K01（RocketMQ DLedger 租约语义）；K04（Nacos 无状态 vs GFS 有状态 master）

---

## 七、验证锚点（论文原文）

| 声明 | 论文位置 |
|---|---|
| 四假设/64MB/3 副本 | §1-§2.3（`gfs-sosp2003.pdf`） |
| 一致性三态表 | §2.7 Table 1 |
| 写 7 步/租约串行/数据链 | §3.1-§3.2（Figure 2） |
| record append（≤1/4 chunk） | §3.3 |
| 快照 COW/元数据内存/op log | §3.4 / §2.6 |
| 放置/再复制/GC/stale/checksum/HA | §4.2-§4.5 / §5.1-§5.2 |
| 实测数（94MB/s、23min、48MB） | §6（Table 2-6, Figure 3） |
