---
title: "05-索引同步：ExternalGte抗乱序"
series: "ES 搜索内核系列"
previous: "04-shards-nrt.md"
next: "06-xhs生产对照.md（待写）"
lab_refs:
  - "es-lab/docs/D5-索引同步模式-ExternalGte抗乱序.md"
cross_refs:
  - "RocketMQ 05 §5（乱序无害化的钩子——本篇兑现）"
---

# 05-索引同步：ExternalGte抗乱序

## §0 它干了什么（例子先行）

笔记更新 → DB 变更 → MQ → 搜索服务 → 写 ES。**消息可能乱序**：

```
sync updatedAt=1000 → 接受（标题 A）
sync updatedAt=900  → 拒绝（409）——旧消息晚到，覆盖不了新值
sync updatedAt=1100 → 接受（最新标题）
sync updatedAt=1100 → 接受——同版本重放，幂等
```

- **它干了什么**：拿 DB 的 `updated_at` 当**外部版本号**，写 ES 时声明 `version_type=external_gte`——**旧版本永远盖不掉新版本**。
- **输入/输出**：输入=带版本号的同步消息；输出=乱序/重复天然无害。
- **钩子兑现**：RocketMQ 05 §5 说"xhs 用版本号抗乱序（ES 拒旧版本覆盖）"——本篇展开它。

## §1 掌握卡（4 条）

1. **三事故一解**：乱序覆盖→旧版本 409 拒；重复消费→同版本重放允许（幂等）；并发写→版本号仲裁——**不依赖消息顺序**。
2. **gte vs 严格大于**：`external` 必须严格大于（重放报错）；`external_gte` **大于等于即可**（重放幂等）——消息场景选 gte。
3. **版本号选 `updated_at`**：自增 ID 分库不唯一、业务版本要维护——毫秒时间戳单调且删除事件也能带；**删除也要带版本**（否则旧更新"复活"已删数据——xhs T-091）。
4. **事实源在 DB**：ES 只是检索副本——错了就 reindex/对账重建；同步是秒级最终一致，强一致读走 DB。

## §2 三事故 vs 反面方案

| 事故 | ExternalGte 解法 |
|---|---|
| 乱序覆盖（旧消息晚到） | 版本旧 → 409 拒 |
| 重复消费 | 同版本重放允许（内容同，幂等） |
| 并发写同一文档 | 版本仲裁，最新胜 |

反面三案（为什么不用）：**先删后插**（有用户可见空窗+并发下删新插旧）/ **ES 内部乐观锁 seq_no**（跨系统同步拿不到）/ **只靠 MQ 顺序**（MQ 只保单队列有序，全局不保——RocketMQ 05 同款结论）。

## §3 版本语义（实测）

| version_type | 规则 | 结果 |
|---|---|---|
| `external` | 必须**严格大于** | 同版本重放被拒（重复消息报错） |
| `external_gte` | **大于等于**即可 | 同版本允许——**xhs 选择** |

409 报文原文（实测）：`version conflict, current version [200] is higher than the one provided [100]`。

版本号候选对照：自增 ID（分库不唯一不单调）✗ / `updated_at` 毫秒（单调+业务语义+删除可带 ts）✅ / 业务版本号（要维护）。

## §4 xhs 全链路

```
DB 变更（发布/更新/删除）
  → Canal 订阅 binlog（binlog=MySQL 的变更日志；Canal=订阅并解析它的工具），或业务发 MQ
  → 搜索服务消费者（NoteIndexSyncConsumer 等 4 个）
  → ES 写入 version=updated_at, version_type=external_gte
      版本新→接受；版本旧/重放→409 拒/幂等
```

三设计要点：**事实源 DB**（索引错可重建，对账思路 dist-consistency）/ 秒级最终一致（NRT+同步延迟）/ **幂等是同步的地基**——ExternalGte 就是 ES 层的幂等。

## §5 坑

时钟回拨（updated_at 依赖 DB 时钟，回拨可能误拒新版本——低概率可接受）/ **删除必须带版本**（T-091：否则旧更新复活数据）/ mapping 变更走 reindex+别名（02 回顾）/ 同步有窗口——强一致读走 DB。

## §6 边界（一句话翻篇）

- Canal 部署/binlog 订阅细节 → 下一单元（非本系列）。
- 对账四模式细节 → dist-consistency D3。
- 4 个消费者源码逐行 → 06 对照篇。

## §7 Self-Test（4 题）

- Q1：三事故（乱序/重复/并发）各怎么被 ExternalGte 治？
- Q2：gte 和严格大于差在哪？为什么消息场景选 gte？
- Q3：为什么删除事件也要带版本？不带会怎样（T-091）？
- Q4：ES 数据错了怎么恢复？事实源在哪？

## §8 衔接

- 本篇回答"同步怎么不乱"；下篇 06 汇总 xhs 13 处实锤（消费端/写入端/检索端全景）。
