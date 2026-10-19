# D2 — Mapping 与分词：字段类型决定"能不能搜到"

> **定位**：es-lab 第 2 篇。Mapping（字段类型）+ Analyzer（分词）——"搜不到"类问题的第一排查点。
> **实测**：本 lab A01/A02/A03。

---

## 一、它干了什么：一个例子

同一份数据，两个查询结果完全不同：

```bash
# term 查 authorId=u1 → 命中 21 条（精确匹配）
curl "http://127.0.0.1:28060/a03/term?authorId=u1"
# {"total":21,"titles":["小红书运营笔记","批量笔记 1",...]}

# 如果 authorId 是 text 类型呢？term 查 "u1" 可能查不到（被分词成 "u","1"）
```

**一句话**：**text 字段会被分词（用于全文检索）；keyword 字段不分词（用于精确匹配/聚合/排序）**——类型选错 = 搜不到/排序报错。

---

## 二、为什么需要它：三种字段类型的取舍

| 类型 | 分词 | 用途 | 本 lab 字段 |
|---|---|---|---|
| `text` | ✅ 分词 | 全文检索（标题/内容） | title/content |
| `keyword` | ❌ 原样 | 精确匹配/聚合/排序（作者 ID/标签） | authorId |
| 数值/日期 | ❌ | 范围/排序 | likeCount/updatedAt |

**常用技巧**：一个字段两种能力——`title` 设 `text` + 子字段 `keyword`（multi-field）：

```json
"title": { "type": "text", "fields": { "keyword": { "type": "keyword" } } }
// title → 全文检索；title.keyword → 精确匹配/聚合
```

本 lab 的 mapping（A01 `/a01/mapping` 可看）就是这么建的。

---

## 三、怎么做：analyzer 链路

### 3.1 索引期 vs 查询期

```
写入文档 → [索引期 analyzer] → 词项 → 倒排
查询输入 → [查询期 analyzer] → 词项 → 查倒排
```

**两边 analyzer 必须一致**（默认一致）——否则"索引切法 ≠ 查询切法" → 搜不到。

### 3.2 analyzer 三件套

```
character filter（字符过滤：去 HTML）→ tokenizer（切词）→ token filter（小写/同义词/停用词）
```

`standard` = 无 char filter + standard tokenizer（中文按字）+ lowercase。

### 3.3 中文：为什么必须 IK

| 分词器 | `小红书种草笔记` 切出 | 问题 |
|---|---|---|
| standard（默认） | 小/红/书/种/草/笔/记 | 词项碎、相关性差、"小红书"匹配不到整词 |
| **ik_smart**（xhs 用） | 小红书/种草/笔记 | 粒度合理 |
| ik_max_word | 小红书/小红/红书/种草/笔记… | 最细粒度、召回高、索引大 |

（IK 是 ES 插件；本 lab 单节点未装插件——用 standard 演示分词原理，生产口径见 xhs。）

---

## 四、坑与边界

| 坑 | 说明 |
|---|---|
| text 字段做 term 查询 | term 不分词、text 被分词 → 查不到（经典"搜不到"根因） |
| text 字段做排序/聚合 | 报错（fielddata 默认关）——用 `.keyword` 子字段 |
| 动态 mapping 猜错类型 | 首次写入自动推断（字符串→text+keyword，数字→long）——生产显式定义 |
| 改 mapping 要重建索引 | 字段类型不可改——变更走 reindex/别名切换 |

---

## 五、xhs-project 对照

| 本 lab | xhs-project |
|---|---|
| notes mapping（title text+keyword） | 笔记/商品索引显式 mapping（IK 分词器 + 字段规划） |
| term authorId 精确查 | 作者维度过滤/聚合用 keyword |
| analyze 实验 | IK 分词验证（xhs 部署文档：业务 ES :19200 带 IK） |

---

## 六、值不值得深挖 & 面试问答

**值不值得**：🔴 必问——"text 和 keyword 的区别"是 ES 面试前五题；"搜不到怎么排查"考的就是这个。

**Q1：text 和 keyword 的区别？**
text 分词（全文检索、BM25 打分）；keyword 不分词（精确匹配、排序、聚合）。需要两者用 multi-field。

**Q2：term 查询为什么搜不到？**
term 不分词——若字段是 text（被分词），term 的输入对不上词项。精确查询要用 keyword 字段或 match。

**Q3：为什么聚合/排序 text 报错？**
text 无 doc_values（默认 fielddata=false，防止内存爆炸）——用 keyword 子字段。

**Q4：改了 mapping 怎么办？**
字段类型不可变——新建索引 + reindex + 别名原子切换。
