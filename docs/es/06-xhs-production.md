---
title: "06-xhs生产对照：检索链路全景"
series: "ES 搜索内核系列"
previous: "05-sync-externalgte.md"
next: "07-面试收口.md（待写）"
source_refs:
  - "xhs search 4 消费者（NOTE_INDEX/PRODUCT_INDEX/SOCIAL/RECOMMEND_BEHAVIOR 四 topic）"
  - "search/consumer/ProductIndexSyncConsumer.java:269,336,350,367（ExternalGte 三处+T-091 同族）"
  - "search/config/IndexInitializer.java:79-95（ik_max_word 索引/ik_smart 查询非对称）"
  - "xhs-ai EsSearchTool/EsIndexListTool/LogSearchAccess（AI 侧检索工具）"
---

# 06-xhs生产对照：检索链路全景

## §0 它干了什么（例子先行）

xhs 搜索服务 `my-xhs-search` 一条链路：

```
笔记发布/更新 → NOTE_INDEX_TOPIC → NoteIndexSyncConsumer → 写 ES（updated_at + ExternalGte）
点赞变更     → SOCIAL_TOPIC     → LikeCountSyncConsumer → 更新计数字段
搜索请求     → NoteSearchService → match+bool+热度排序（03 的组合）
AI 问"某笔记啥样" → EsSearchTool → 查同一个 ES
```

- **它干了什么**：4 个消费者管写入、检索服务 8 个类管查询（含抽象基类与文档构建器）、AI 工具复用同一份索引。
- **输入/输出**：输入=四个 topic 的变更消息 + 搜索请求；输出=可搜的索引 + 排好序的结果。
- **本篇回答**：4 个消费者各管什么？ExternalGte 在源码长什么样？IK 怎么配？

## §1 掌握卡（4 条）

1. **4 消费者 = 4 个 topic**：`NOTE_INDEX`（笔记索引）/ `PRODUCT_INDEX`（商品）/ `SOCIAL`（点赞数）/ `RECOMMEND_BEHAVIOR`（行为进推荐）——与 RocketMQ 08 的 17 topic 清单对得上。
2. **ExternalGte 源码三现**（`ProductIndexSyncConsumer`）：写入 `versionType(ExternalGte)`（:269/:336）；**版本冲突当幂等**（:350 注释"属幂等已应用语义"）；删除带版本防复活（:367 "T-091 同族"）。
3. **IK 非对称搭配**：`title` 索引用 `ik_max_word`（细切求召回）、查询用 `ik_smart`（粗切求准）——刻意非对称，不是 02 说的"默认一致"陷阱（细切结果包含粗切词项，能对上）。
4. **AI 侧复用**：`EsSearchTool`/`EsIndexListTool`/`LogSearchAccess`——AI agent 检索笔记/日志走同一份 ES（可观测线的"日志进 ES"同源）。

## §2 4 消费者对照表

| 消费者 | topic | 干什么 | 本系列回链 |
|---|---|---|---|
| NoteIndexSyncConsumer | NOTE_INDEX_TOPIC | 笔记索引增删改 | 05 全链路 |
| ProductIndexSyncConsumer | PRODUCT_INDEX_TOPIC | 商品索引 + ExternalGte 三现 | 05/本篇 §3 |
| LikeCountSyncConsumer | SOCIAL_TOPIC | 点赞计数同步 | — |
| BehaviorReportConsumer | RECOMMEND_BEHAVIOR_TOPIC | 行为数据进推荐 | — |

## §3 ExternalGte 源码三现 + 一个冻结陷阱

写入（elastic 客户端 `VersionType.ExternalGte`，:269/:336）；冲突检测当幂等（:350）；删除带版本（:367 "防乱序旧消息覆盖删除标记"）。

**陷阱**（:136 注释原话）："canal es(小整数) 会被 ES ExternalGte **永久拒绝** → product_index **增量冻结**"——版本号必须**持续单调**；换版本来源若给小整数，旧版本永远过不了闸，索引从此不再更新。05 §3 "单调"两字的生产代价版。

## §4 IK 非对称（Mapping 实锤）

```json
"title":   { "analyzer": "ik_max_word", "search_analyzer": "ik_smart" }
"content": { "analyzer": "ik_smart" }
```

- 索引细切（`ik_max_word`）：`小红书种草笔记`→[小红书,小红,红书,种草,笔记]——词项全，**召回高**。
- 查询粗切（`ik_smart`）：搜`笔记`→[笔记]——精准命中细切里的同名词项。
- content 直接 smart：内容长文本，索引别切太碎（存储+精度平衡）。

与 02 的关系：02 说"两侧 analyzer 要能对上"是防"搜不到"的底线；这里 max_word 的结果包含 smart 的词项，对得上——**非对称是进阶用法，不是违规**。

## §5 检索端功能面

`NoteSearchService`/`ProductSearchService`（match+bool+排序，03 的组合）+ `HotSearchService`/`SearchHistoryService`/`SuggestService`/`RecommendService`——搜索不是单次查询，是**检索+热榜+历史+建议+推荐**的组合产品面。

## §6 边界（一句话翻篇）

- Canal 格式消息/小整数版本的历史细节 → 源码注释 :136 一带，用时看。
- AI 工具（EsSearchTool 等）内部实现 → 非本系列。
- 推荐排序算法 → 推荐系统范畴。

## §7 Self-Test（3 题）

- Q1：4 个消费者各认哪个 topic？
- Q2：ExternalGte "三现"指哪三处？T-091 同族治什么？
- Q3：IK 为什么索引用 max_word、查询用 smart？和 02 的"一致"矛盾吗？

## §8 衔接

- 生产线收口；下篇 07 面试收口（Z 六组 A~F + 终检）。
