# D5 — 索引同步模式：xhs 的 ExternalGte 抗乱序

> **定位**：es-lab 第 5 篇。把 ES 接回 xhs-project 生产模式——**DB 是事实源，ES 是检索副本**，同步怎么保证不乱。
> **实测**：本 lab A04/A05（ExternalGte 版本控制链）。

---

## 一、它干了什么：一个例子

xhs 笔记更新 → DB 变更 → Canal/MQ 送到搜索服务 → 更新 ES 索引。**消息可能乱序**：

```bash
# A05 实测链（updated_at 作为版本号）
sync updatedAt=1000 → ACCEPTED（初始标题）
sync updatedAt=900  → REJECTED（乱序旧数据——索引保持较新值）
sync updatedAt=1100 → ACCEPTED（最新标题）
sync updatedAt=1100 → ACCEPTED（同版本重放——幂等）
# 最终：{"title":"重复的最新(同版本重放)","updatedAt":1100}
```

**一句话**：以 DB 的 `updated_at` 作为**外部版本号**，ES 用 `external_gte` 写入——**旧版本永远覆盖不了新版本**，乱序/重复消息天然无害。

---

## 二、为什么需要它：同步的三种典型事故

| 事故 | 场景 | ExternalGte 如何解决 |
|---|---|---|
| **乱序覆盖** | 消息 A(旧) 晚于 B(新) 到达 → A 覆盖 B | 旧版本 409 拒绝 |
| **重复消费** | 同一消息投递两次 | 同版本重放允许（内容相同，幂等） |
| **并发写** | 多消费者并行处理同一文档 | 版本号仲裁，只有一个"最新"胜出 |

**反面方案**（为什么不用）：
- **先删后插**：删除瞬间搜索不到（用户可见的空窗）+ 并发下可能"删了新插了旧"
- **乐观锁（seq_no/primary_term）**：ES 内部版本，跨系统同步拿不到
- **只靠消息顺序**：MQ 不保证全局顺序（RocketMQ 只保证单队列有序）——不可依赖

---

## 三、怎么做：版本控制语义（实测）

### 3.1 external_gte vs external

| version_type | 规则 | 结果 |
|---|---|---|
| `external` | 版本必须**严格大于**当前 | 同版本重放被拒（重复消息报错） |
| `external_gte` | 版本**大于等于**当前 | **同版本允许**（幂等重放）——xhs 选择 |

**A04 实测**：`v200 受 → v100 拒(409) → v200 重放受 → v300 受`——409 响应里明确写着：
`version conflict, current version [200] is higher than the one provided [100]`

### 3.2 版本号选什么

| 候选 | 问题 |
|---|---|
| 自增 ID | 跨表/跨库不唯一、不单调（分库分表场景） |
| **updated_at（毫秒时间戳）** | ✅ xhs 选择——单调（时钟回拨概率低）、天然业务语义、删除事件也能带 ts |
| 业务版本号 | 需要业务维护 |

**xhs 的 T-091 修复**（回链源码注释）：删除事件也带 ts 版本——防止"删除后旧更新又把它写回来"。

---

## 四、完整同步链路（xhs 全景）

```
DB 变更（笔记发布/更新/删除）
   │
   ├─ Canal 订阅 binlog（下一单元）——或业务发 MQ
   ▼
搜索服务消费者（NoteIndexSyncConsumer 等 4 个）
   │  以 updated_at 为版本
   ▼
ES index(version=updated_at, version_type=external_gte)
   │
   ├─ 版本新 → 接受（索引更新）
   └─ 版本旧/重放 → 409 拒绝/幂等（索引不变）
```

**设计要点**：
- **事实源在 DB**：ES 只是检索副本——索引错了可以从 DB 重建（对账思路回链 dist-consistency D3）
- **秒级最终一致**：接受 NRT + 同步延迟
- **幂等是同步的地基**：ExternalGte 就是 ES 层的幂等

---

## 五、坑与边界

| 坑 | 说明 |
|---|---|
| 时钟回拨 | updated_at 依赖 DB 时钟——回拨可能造成"新版本被拒"（低概率，可接受） |
| 删除语义 | 删除也要带版本（否则旧更新复活数据）——xhs T-091 |
| 全量重建 | mapping 变更/数据修复要 reindex（别名切换） |
| 同步滞后 | ES 与 DB 有窗口——强一致读走 DB（回链读写分离/多级缓存口径） |

---

## 六、值不值得深挖 & 面试问答

**值不值得**：🔴 必问——"ES 和 DB 怎么同步""怎么保证不乱序"是搜索类项目的必考；ExternalGte 是加分点。

**Q1：ES 和 DB 怎么同步？**
Canal 订阅 binlog（或业务 MQ）→ 消费者更新 ES；以 updated_at 为 external_gte 版本——抗乱序+幂等。

**Q2：为什么用 external_gte 而不是 external？**
external 同版本会报错（重复消息需要处理）；external_gte 允许同版本幂等重放——更适合消息场景。

**Q3：同步乱序怎么办？**
版本号仲裁：旧版本被 409 拒绝——不依赖消息顺序。

**Q4：ES 数据错了怎么办？**
DB 是事实源——重建索引（全量 reindex + 别名切换）或对账修复（回链 dist-consistency D3）。
