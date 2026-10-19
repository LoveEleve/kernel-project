---
title: "03-查询DSL：match与term怎么选"
series: "ES 搜索内核系列"
previous: "02-mapping-analyzer.md"
next: "04-分片与近实时.md（待写）"
lab_refs:
  - "es-lab/docs/D3-查询DSL-match与term怎么选.md"
---

# 03-查询DSL：match与term怎么选

## §0 它干了什么（例子先行）

同一批 101 条数据，四种查法：

```
match "批量"：           100 条（分词后匹配）
term authorId=u1：       21 条（keyword 精确）
bool must"笔记" + filter likes>=50： 52 条（组合）
按点赞降序 from=0 size=3： 批量笔记 100/99/98
```

- **它干了什么**：把"找得到（match）/找得准（term）/条件组合（bool）/翻页（page）"四种需求各配一个查询。
- **输入/输出**：输入=DSL JSON；输出=命中集+打分。
- **一句话**：match=分词检索（走分析器）；term=精确匹配（走倒排原表）；bool=四字句组合。

## §1 掌握卡（4 条）

1. **match vs term**：match 分词+打分（text 字段）；term 不分词无打分（keyword/数值）——text 用 term 查不到（02 坑回顾）。
2. **bool 四字句**：`must`（必须且打分）/ `filter`（必须不打分、**可缓存**）/ `should`（可选，影响打分）/ `must_not`（必须不）。
3. **filter vs must**：语义相同，filter 不打分可缓存——范围/权限类过滤用 filter 更快。
4. **深分页三式**：`from+size` 浅分页（上限 10000）/ `search_after` 游标（深翻页，不能跳页）/ `scroll`（离线导出快照）。

## §2 三套结构各司其职（Q4 收口）

```
检索匹配 → 倒排        排序聚合 → doc_values（列存，01 回顾）
过滤     → filter 的 bitset 缓存（bitset=用 0/1 位紧凑表示"命中/未命中"，结果可整体缓存复用）
```

ES"搜+排+滤同时高效"的答案——不是一套结构通吃。

## §3 match vs term

| | match | term |
|---|---|---|
| 输入处理 | **分词**后逐词查倒排 | **不分词**，原样对词项 |
| 适用 | text（标题/内容） | keyword/数值（ID/枚举） |
| 打分 | BM25 | 无（filter 语义） |

## §4 bool 四字句

```json
"bool": {
  "must":     [ { "match": { "title": "笔记" } } ],
  "filter":   [ { "range": { "likeCount": { "gte": 50 } } } ],
  "should":   [ ... ],
  "must_not": [ ... ]
}
```

## §5 分页三式

| 方式 | 用法 | 限制 |
|---|---|---|
| `from+size` | 浅分页（前几页） | 深分页每个分片都取 from+size 再合并——`max_result_window=10000` 上限 |
| `search_after` | 深分页（向下滚） | 用上页最后一条 sort 值当游标（游标=记住"读到哪了"的标记）；无上限，**不能跳页** |
| `scroll` | 全量导出 | 快照占资源，离线用 |

xhs App 下拉加载 = search_after 思路（只能向下，不能跳第 50 页）。

## §6 坑

term 查 text（02 回顾）/ 深分页 O(from+size)×分片骤降 / text 排序必须 `.keyword` / **业务排序（价格/时间）用 sort+filter，别让 BM25 干扰稳定性**。

## §7 边界（一句话翻篇）

- scroll API 细节/PIT（时间点快照）→ 用时查。
- bitset 缓存实现 → 知道"filter 可缓存"即可。
- 打分公式 → 01 说过不深挖。

## §8 Self-Test（4 题）

- Q1：match 和 term 各配什么字段？text 用 term 什么后果？
- Q2：filter 和 must 语义差在哪？为什么过滤用 filter？
- Q3：深分页三式各适合什么场景？search_after 为什么不能跳页？
- Q4：ES"搜+排+滤同时高效"靠哪三套结构？

## §9 衔接

- 本篇回答"怎么查"；下篇 04 回答"存哪、为什么写入 1 秒后才能搜到"（分片 + refresh）。
