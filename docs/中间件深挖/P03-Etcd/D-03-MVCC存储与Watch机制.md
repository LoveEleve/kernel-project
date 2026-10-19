# P03-Etcd D-03 — MVCC 存储与 Watch 机制

> **定位**：P03 第 3 篇。三层齐：**原理（心智模型）+ 使用（客户端语义/调参）+ 源码（核心调用链）**。
> **源码版本**：etcd **v3.5.34**（`code/spring/etcd`）。承接 D-02（apply 的目标就是本篇的存储）。
> **质量标准**：源码清单 / 完整调用链 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：一个例子的多版本

```
put k v1        → revision = (main=1, sub=0)    读 get k → v1 @rev1
put k v2        → revision = (main=2, sub=0)    读 get k → v2 @rev2
get k --rev=1   → v1（历史版本仍可读！）
watch k（from rev2）→ 收到 PUT k=v2 事件（历史）→ 再 put k v3 → 实时收到 PUT k=v3
compact(rev2)   → rev<2 的历史被清理（v1 消失）；带 rev1 的 watch/读 → ErrCompacted
```

**一句话**：etcd = **"每个 key 一条版本链 + 全局单调 revision"**——读写都带 revision（快照语义），Watch 从任意 revision 追事件（历史回放 + 实时推送）。

---

## 二、原理（心智模型）

### 2.1 多版本数据模型（三层）

```
① revision（全局单调）：main = 事务号（每次写事务 +1）；sub = 事务内变更序号
② keyIndex（内存 btree，key → 版本链）：generations 列表
   - 每次"删除后再创建"产生新 generation（旧代以 tombstone 结束）
   - 每个 generation 记录 revs[]（该代的变更序列）
③ backend（BoltDB）：key = 编码后的 (key, revision)，value = 值——历史版本物理都在这

读 key@R 的算法：index 里找到 R 所属 generation → 在 revs 里二分找 ≤R 的最近 revision → backend 读值
```

### 2.2 写路径（一个事务=一个 revision）

```
Write() 开启 TxnWrite（记录 beginRev）
  → Put(k,v)：① sub rev 递增 ② 内存 keyIndex.put（挂到版本链）
             ③ backend batchTx 写 ("k@rev" → v)（不立即 fsync——攒批）
  → End()：① 提交 backend batch ② 生成 mvccpb.Event 列表 → notify 唤醒 watcher
```

### 2.3 读路径

```
Range(k, end, rev)：内存 keyIndex.get(atRev) 定位 → backend 读值（BoltDB 的 MVCC 快照读）
两种读事务：Read（读写 TX 一致性视图）/ ConcurrentRead（并发读）
```

### 2.4 Watch 机制（两态设计）

```
watch(key, startRev) → watcher 注册到 watcherGroup
  若 startRev 已在历史（≤当前 rev）→ unsynced（需要补历史）
      syncWatchers：从 backend 拉 [startRev, currentRev] 的变更补齐 → 转入 synced
  若 startRev 是"未来"→ 直接 synced（等实时事件）
写事务 End → notify(rev, evs)：
  synced 组匹配 key/rev → 推送事件
机制要点：单次触发（客户端收到后需重新注册语义上是"续传"——Watch API 是长连接流，断线用 revision 续传）
```

### 2.5 压缩（Compact）——为多版本"修剪"

```
Compact(rev)：删除所有 < rev 的历史版本（tombstone/generations 修剪）
  后台 scheduleCompaction 异步执行（不阻塞写）
压缩的代价：带 rev<compactRev 的读/Watch → ErrCompacted（客户端要重新建立快照）
```

---

## 三、使用（客户端语义与调参）

```
客户端读：get k --rev=N（历史读）；get k --prefix --rev=N（快照遍历）
Watch：watch k / --prefix / --prev-kv；断线重连从上次 revision 续传（不怕漏）
压缩：--auto-compaction-mode=periodic --auto-compaction-retention=1h（防 MVCC 空间爆炸）
     或 revision 模式；手动 etcdctl compact <rev>
空间观测：etcd_mvcc_db_total_size_in_bytes / etcd_mvcc_db_total_size_in_use_in_bytes
（两者差距大 = 已压缩但未回收物理空间，等 BoltDB 重整）
```

---

## 四、源码核心（调用链 + 行号）

### 4.1 写链

```
store.Write（kvstore_txn.go:79，开启 storeTxnWrite:71）
  → Put（:109）→ put（:190）：
       ① 生成 sub revision ② keyIndex.put（key_index.go:83）③ backend batchTx put
  → End（:114）：backend batch 提交 + changes 收集
watchableStore.Write（watchable_store_txn.go:54）→ watchableStoreTxnWrite
  → End（:22）：changes → mvccpb.Event（CreateRevision==0 → DELETE）→ s.notify（watchable_store.go:454）
```

### 4.2 读链

```
store.Read（:39）→ storeTxnRead:29
  → Range（:62）→ rangeKeys（:128）→ keyIndex.get（key_index.go:152，按 atRev 定位版本）
  → backend 读值 → End（:66）
```

### 4.3 Watch 链

```
watchableStore.watch（watchable_store.go:123）→ watcher 注册
  startRev 落后 → s.unsynced.add（:145/:212）→ syncWatchersLoop（:219）/ syncWatchers（:339）补历史
  syncWatchers 从 backend 拉事件 → 转入 synced
notify（:454）→ 按 key/rev 匹配 watcher → 推送
watcherGroup（watcher_group.go:147）：按 key 区间组织的 watcher 集合
```

### 4.4 存储底座（BoltDB 批事务）

```
server/mvcc/backend/batch_tx.go：LockInsideApply（:78，apply 期间持锁）/ Commit（:242）→ commit（:261）
  批量提交（不是每条写都 fsync）——apply 一批 → commit 一次
kvstore.restore（kvstore.go:330）：启动时从 backend 重建内存 keyIndex（遍历全部版本）
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **全局单调 revision 作快照** | 每条记录时间戳 | revision 是总序（consensus 的天然产物）——读/ watch 都可用单一数字表达"哪个时刻" |
| **索引在内存、值在 BoltDB** | 全部放 B+ 树磁盘 | 读路径先查内存 index（快）→ 只读值落盘；内存索引可重建（restore） |
| **"删除后再创建"开新 generation** | 版本链混在一起 | generation 隔离"代"，tombstone 收尾——压缩时可整代清理 |
| **Watch 两态（unsynced/synced）** | 一态混用 | 历史补拉（backend）与实时推送（内存）分开处理——各自最优路径 |
| **BatchTx 攒批提交** | 每写 fsync | apply 与存储解耦（D-02 FIFO）后，攒批摊薄 fsync（性能关键） |
| **压缩异步、Soft Limit** | 压缩同步阻塞写 | 压缩是大 IO——异步化不阻塞主路径；代价是空间回收滞后（看 in_use vs total） |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **MVCC 空间爆炸** | 频繁写不压缩 → 磁盘耗尽——auto-compaction + 磁盘告警 |
| 2 | **compact 后 watch/读报 ErrCompacted** | 客户端 rev 落后于 compactRev——watch 断线重连要从有效 rev 续（或重新 get 快照） |
| 3 | **只压不整理（物理空间不回收）** | in_use 降但 total 不降——BoltDB 需 defrag（etcd 有自动后端整理） |
| 4 | **BoltDB 单写者** | 所有写串行（batch tx）——写吞吐上限；读有并发 tx 补偿 |
| 5 | **watch 大量 key（前缀全表）** | 事件风暴——用更精确的 key 范围/过滤 |
| 6 | **索引重建慢（restore）** | 版本多 → 启动遍历久——压缩 + 快照组合优化启动 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："etcd 为什么叫 MVCC？"**（意图：模型理解）
每个 key 保留多版本（全局 revision 链）+ 读可指定 rev（快照读）——不是"多版本并发控制"的锁语义，而是"版本化 KV"。

**Q2："读一个历史版本怎么实现？"**（意图：存储结构）
内存 keyIndex 按 atRev 二分定位到版本 → backend 读 `(key, rev)` 编码的物理值。

**Q3："watch 从旧 revision 开始能拿到历史事件吗？"**（意图：watch 机制）
能——unsynced 状态从 backend 补 [startRev, currentRev] 的历史，再转 synced 实时推送。

**Q4："compact 和 watch 的关系？"**（意图：细节）
compact 删除历史 → 早于 compactRev 的 watch 起始点会 ErrCompacted——客户端必须从可用 rev 续传。

**Q5："为什么写不是每条 fsync？"**（意图：性能设计）
apply 与存储经 FIFO 解耦 + BatchTx 攒批——一次 commit 摊薄多条的 fsync；崩溃一致性由 WAL（D-02）保证。

---

## 八、章节依赖

- **上游**：D-02（apply 调用到这里）；D-01（revision 由 raft index/term 演进而来）
- **下游**：D-04（快照=存储的某一 revision 的整体导出；压缩与快照的配合）
- **横链**：K04（Nacos 配置存储模型对比）；P04（LSM/B+ 存储引擎对照）

---

## 九、验证锚点（etcd v3.5.34）

| 声明 | 位置 |
|---|---|
| store.Write / Read | `server/mvcc/kvstore_txn.go:79/39` |
| storeTxnWrite.Put / put / End | `kvstore_txn.go:109/190/114` |
| watch 化写入 | `server/mvcc/watchable_store_txn.go:54/22`（End→notify） |
| notify | `server/mvcc/watchable_store.go:454` |
| watch 注册与两态 | `watchable_store.go:123`（watch）/`:145/:212`（unsynced）/`:219`（loop）/`:339`（syncWatchers） |
| watcherGroup | `server/mvcc/watcher_group.go:147` |
| keyIndex | `server/mvcc/key_index.go:76`（put:83 / tombstone:134 / get:152）；generation:349 |
| revision | `server/mvcc/revision.go:26/36` |
| 压缩 | `server/mvcc/kvstore.go:285`（Compact）→ `kvstore_compaction.go:27`（scheduleCompaction） |
| 恢复重建索引 | `kvstore.go:330`（restore） |
| BatchTx | `server/mvcc/backend/batch_tx.go:78/242/261` |
