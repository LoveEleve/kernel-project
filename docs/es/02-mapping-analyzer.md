---
title: "02-Mapping与分词：字段类型决定能不能搜到"
series: "ES 搜索内核系列"
previous: "01-inverted-index.md"
next: "03-查询DSL.md（待写）"
lab_refs:
  - "es-lab/docs/D2-Mapping与分词-字段类型决定能不能搜到.md"
---

# 02-Mapping与分词：字段类型决定能不能搜到

## §0 它干了什么（例子先行）

同一份数据，`authorId=u1` 两种结果：

```
authorId 是 keyword：term 查 "u1" → 命中 21 条（原样存原样比）
authorId 是 text：   term 查 "u1" → 可能 0 条
                     └─ 因为入库时被切成 "u"、"1"——你拿 "u1" 整个词来对，对不上
```

- **它干了什么**：Mapping 定字段类型，类型决定**切不切词**——切了才能全文搜，不切才能精确比。
- **输入/输出**：输入=字段类型定义；输出=能搜到还是搜不到。
- **本篇回答**：text/keyword 怎么选？analyzer 链路什么样？"搜不到"怎么排查？

## §1 掌握卡（4 条）

1. **两条铁轨**：`text` 分词（全文检索+BM25）/ `keyword` 不分词（精确匹配/聚合/排序）/ 数值日期不切（范围）——**类型选错=搜不到或报错**。
2. **两头都要用 → multi-field**：`title` 设 text + 子字段 `keyword`——`title` 全文搜、`title.keyword` 精确比。
3. **analyzer 三件套**：字符过滤→切词→词过滤（小写/同义词/停用词）；索引期和查询期必须一致；中文生产用 IK（smart 切得整，max_word 召回高）。
4. **四大坑**：text 上做 term（搜不到）/ text 排序聚合报错（用 `.keyword`）/ 动态 mapping 猜错（生产显式定义）/ 改类型要 reindex+别名切换。

## §2 三类型取舍

| 类型 | 分词 | 用途 | lab 字段 |
|---|---|---|---|
| `text` | ✅ | 全文检索 | title/content |
| `keyword` | ❌ 原样 | 精确/聚合/排序 | authorId |
| 数值/日期 | ❌ | 范围/排序 | likeCount/updatedAt |

multi-field 一字段两能力：

```json
"title": { "type": "text", "fields": { "keyword": { "type": "keyword" } } }
// title 全文检索；title.keyword 精确匹配/聚合
```

## §3 analyzer 链路

```
写入 → [索引期 analyzer] → 词项 → 倒排
查询 → [查询期 analyzer] → 词项 → 查倒排
```

三件套：`character filter`（去 HTML 等）→ `tokenizer`（切词）→ `token filter`（小写/同义词/停用词）。`standard` = 无字符过滤 + 按字切 + 小写。

中文三档（`小红书种草笔记`）：standard 切成 7 个字（词项碎）/ **ik_smart**（xhs 用）→ 小红书/种草/笔记 / `ik_max_word` → 再拆小红/红书（召回高、索引大）。lab 未装 IK 插件，用 standard 演原理，生产口径看 xhs 部署（:19200 带 IK）。

## §4 四坑排查表（"搜不到"标准排查顺序）

| 坑 | 现象与解法 |
|---|---|
| text 上做 term | term 不分词、字段被分词→对不上——换 keyword 字段或 match |
| text 排序/聚合报错 | 无 doc_values（fielddata 默认关，防内存爆）——用 `.keyword` |
| 动态 mapping 猜错 | 首次写入自动推断——**生产显式定义** |
| 改 mapping 要重建 | 类型不可改——新建索引+reindex+**别名原子切换**（业务方无感） |

## §5 边界（一句话翻篇）

- IK 插件安装/自定义词典 → 用时查 xhs 部署文档。
- fielddata 内存模型细节 → 知道"默认关防爆"即可。
- reindex API 用法 → 运维时查。

## §6 Self-Test（4 题）

- Q1：text 和 keyword 各管什么？两者都要用怎么配？
- Q2：term 查 text 字段为什么搜不到？（切法对不上）
- Q3：聚合 text 字段为什么报错？补救的正解？
- Q4：改字段类型的标准姿势？（reindex+别名）

## §7 衔接

- 本篇回答"词怎么切"；下篇 03 回答"切好的词怎么查"（match vs term + bool 四字句 + 深分页）。
