# D3 — 查询 DSL：match、term、bool 到底怎么选？

> **定位**：es-lab 第 3 篇。查询 DSL——检索的"SQL"。
> **实测**：本 lab A03（101 条数据上跑）。

---

## 一、它干了什么：一个例子

同一批数据（1 条手工 + 100 条 bulk），四个查询：

```bash
# ① match：分词匹配 → 100 条
curl -G --data-urlencode "q=批量" ".../a03/match"     # {"total":100,...}

# ② term：精确匹配 keyword → 21 条（u1 作者）
curl ".../a03/term?authorId=u1"                       # {"total":21,...}

# ③ bool：must(笔记) + filter(likes>=50) → 52 条
curl -G --data-urlencode "q=笔记" ".../a03/bool?minLikes=50"  # {"total":52,...}

# ④ page：按点赞降序取前 3 → 批量笔记 100/99/98
curl ".../a03/page?from=0&size=3"                     # 排序生效
```

**一句话**：**match = 分词检索（找得到）；term = 精确匹配（找得准）；bool = 组合（must/should/filter/must_not）**。

---

## 二、为什么需要它：match vs term 的本质

| | match | term |
|---|---|---|
| 输入处理 | **分词**后逐词查倒排 | **不分词**，原样查词项 |
| 适用字段 | text（标题/内容） | keyword/数值（ID/枚举） |
| 打分 | 有（BM25） | 无（filter 语义） |
| 典型错误 | —— | 对 text 字段用 term → 查不到（D2 坑） |

**记忆**：match 走"分析器"，term 走"倒排原表"。

---

## 三、怎么做：bool 的四种字句

```json
{
  "bool": {
    "must":     [ { "match": { "title": "笔记" } } ],      // 必须匹配（参与打分）
    "filter":   [ { "range": { "likeCount": { "gte": 50 } } } ],  // 必须匹配（不打分，可缓存）
    "should":   [ ... ],                                   // 可选（影响打分/满足其一）
    "must_not": [ ... ]                                    // 必须不匹配
  }
}
```

**filter vs must（面试点）**：语义相同，但 **filter 不打分、可被缓存**——范围过滤/权限过滤用 filter 性能更好。

---

## 四、分页与深分页（面试点）

| 方式 | 用法 | 限制 |
|---|---|---|
| `from + size` | 浅分页（前几页） | **深分页代价大**：每个分片都要取 from+size 条再合并排序；`max_result_window=10000` 上限 |
| `search_after` | 深分页（滚动向下） | 无上限；用上一页最后一条的 sort 值做游标；不能跳页 |
| `scroll` | 全量导出 | 快照上下文，占资源；适合离线导出 |

本 lab A03 `/a03/page` 用的是 `from+size`（附了深分页提示）。

---

## 五、坑与边界

| 坑 | 说明 |
|---|---|
| term 查 text | 查不到（分词不匹配）——用 keyword 或 match |
| 深分页 O(from+size)×分片 | 100 页以后性能骤降——search_after |
| 排序字段必须 doc_values | text 不能排序（用 .keyword） |
| 评分影响排序稳定性 | 业务排序（价格/时间）用 sort + filter，避免 BM25 干扰 |

---

## 六、xhs-project 对照

| 本 lab | xhs-project |
|---|---|
| match/bool 组合 | 笔记搜索：标题/内容 match + 标签/作者 filter + 热度排序 |
| term 精确 | 商品/作者维度过滤 |
| page | 搜索结果分页（App 下拉加载——实际用 search_after 思路） |

---

## 七、值不值得深挖 & 面试问答

**值不值得**：🔴 必问——"match 和 term 的区别"“filter 和 must 的区别”“深分页怎么解决”都是高频。

**Q1：match 和 term 的区别？**
match 分词后查倒排 + 打分；term 不分词精确查词项。text 用 match、keyword 用 term。

**Q2：filter 和 must 的区别？**
语义相同；filter 不打分、可缓存（bitset）——过滤类条件用 filter 性能更好。

**Q3：深分页怎么解决？**
search_after（游标）+ 稳定排序；或 scroll（导出场景）；避免大 from。

**Q4：ES 怎么做到"搜索+排序+过滤"同时高效？**
倒排做检索匹配；doc_values（列存）做排序聚合；filter 的 bitset 缓存做过滤——三套结构各司其职。
