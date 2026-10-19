# P05-Elasticsearch D-01 — 倒排索引与查询 DSL

> **定位**：P05 检索线第 1 篇。三层齐：**原理（Lucene 内核）+ 使用（ES DSL 口径）+ 源码（ES 8.12.2 / Lucene 9.9.2 双锚点）**。
> **源码版本**：elasticsearch **8.12.2**（`code/spring/elasticsearch`）+ Lucene **9.9.2**（`code/spring/lucene`，`releases/lucene/9.9.2`）
> **质量标准**：源码清单 / 完整调用链 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：一次 match 查询的完整旅程

```json
GET /note_index/_search
{ "query": { "match": { "title": "小红书种草" } } }
```

```
① 分词（查询期）：standard → 小/红/书/种/草/笔/记；xhs title 字段查询期用 ik_smart → 小红书/种草/笔记
② 查词典：FST（有限状态机）里 O(len) 定位每个词项 → 拿到 postings 指针
③ 读倒排表：从 .doc 取 docId 列表 + .pos 取位置 → BooleanQuery 合并（OR/SHOULD）
④ 打分：每个命中文档按 BM25 算分（idf×tf 饱和×长度归一）
⑤ Query 阶段攒 Top-N（按分排序，默认 size=10）→ Fetch 阶段按 docId 取 _source
```

**一句话**：搜索 = **"分词→FST 查词→倒排取文档→BM25 打分→两阶段取回"**——索引时做的事情（建倒排）决定了查询时的上限。

---

## 二、原理（Lucene 内核）

### 2.1 倒排三件套（一个 segment 内）

| 结构 | 文件 | 作用 |
|---|---|---|
| **词项词典（Terms Dictionary）** | `.tim`（索引）+ `.tip` | **FST**：term → postings 元数据（指针/统计）。FST 把全体词项压成前缀共享的有向图，查词是 O(term_len)，内存占用小 |
| **倒排表（Postings）** | `.doc`（docId 列表）+ `.pos`（位置）+ `.pay`（载荷/偏移） | 按 term 存"哪些文档含它 + 在哪出现"。跳表（skip list）加速大列表的合并 |
| **正排/列存（DocValues）** | `.dvd/.dvm` | 排序/聚合走它（与倒排反向：docId → 值） |

### 2.2 BM25（默认打分公式）

```
score(q,d) = Σ idf(t) · [ tf(t,d)·(k1+1) / (tf(t,d) + k1·(1−b + b·|d|/avgdl)) ]

idf(t)   = log(1 + (docCount − docFreq + 0.5) / (docFreq + 0.5))   ← 越稀有的词权重越高
tf 饱和  ：tf 再大，得分也逼近上限（k1=1.2 控制饱和速度）
长度归一：文档越长越吃亏（b=0.75 控制惩罚强度）；norm 按字节压缩存（LENGTH_TABLE 查表）
```

### 2.3 match vs term（回链 K03）

```
match（分词查询）：输入先过分析器 → 分词查倒排 → 打分（找得到，召回）
term（精确查询）：输入不分词，直接精确查词项 → 只对 keyword/数值字段有意义（找得准）
```

### 2.4 Query → Fetch 两阶段

```
Query 阶段（分散）：每个分片独立执行 Lucene 查询，返回本地 Top-N（docId + 分数，不取原文）
协调节点合并各分片 Top-N → 全局 Top-N（docId + 分片归属）
Fetch 阶段：按 (shard, docId) 回原分片取 _source（一次 mget 式批量）
```

---

## 三、使用（ES DSL 生产口径）

```json
// match：operator（OR/AND）、minimum_should_match、zero_terms_query
{ "match": { "title": { "query": "小红书 种草", "operator": "and", "zero_terms_query": "none" } } }
// match_phrase：短语（位置也要对上，slop 容忍间隔）；term：精确；bool：must/filter/should/must_not
```

| 实践 | 要点 |
|---|---|
| 索引期/查询期 analyzer 一致 | 不一致 = "索引切法≠查询切法"→搜不到（默认一致；xhs title 特意配"索引 ik_max_word + 查询 ik_smart"） |
| text vs keyword | text 分词全文检索；keyword 精确/排序/聚合；双需求用 multi-field（`title` + `title.keyword`） |
| 分词器选型 | 中文生产用 **IK**（es-lab 实测：standard 按单字切）；IK 是插件（不在 ES 仓库） |

---

## 四、源码核心（调用链 + 行号）

### 4.1 BM25 打分（Lucene 9.9.2）

```
lucene/core/.../search/similarities/BM25Similarity.java
  默认值：k1=1.2 / b=0.75 / discountOverlaps=true（:99-101）
  idf：log(1 + (docCount − docFreq + 0.5)/(docFreq + 0.5))（:103-106）
  computeNorm（:132-142）：文档长度压缩成 1 字节（SmallFloat.intToByte4）
  score（:246）：tf 饱和 × 长度归一的最终公式
```

### 4.2 词典与倒排（Lucene 9.9.2）

```
词典（FST）：lucene/core/.../codecs/lucene90/blocktree/FieldReader.java:32（import FST）
            SegmentTermsEnum.java:33 —— BlockTree 词项树是 FST 持久化结构
倒排文件：lucene/core/.../codecs/lucene99/Lucene99PostingsFormat.java
          DOC_EXTENSION="doc"（:348）/ POS_EXTENSION="pos"（:351）/ PAY_EXTENSION="pay"（:357）
```

### 4.3 ES 查询链（ES 8.12.2）

```
MatchQueryBuilder（server/.../index/query/MatchQueryBuilder.java）
  operator（:66/160）/ zeroTermsQuery（:84/298）
  → MatchQueryParser.parse（server/.../index/search/MatchQueryParser.java:197）→ createBooleanQuery（:245）→ Lucene BooleanQuery
QueryPhase.executeQuery（server/.../search/query/QueryPhase.java:115）
  → searcher.search(query, collectorManager)（:205）→ QueryPhaseResult（TopDocs+maxScore）
```

### 4.4 ES 写入链（为 D-02 铺垫）

```
InternalEngine（server/.../index/engine/InternalEngine.java）
  index → indexWriter.addDocument(s)（:1465-1479，写内存 buffer + translog）
  refresh（:2011）→ 新 segment 可见（Lucene 近实时点）——详见 D-02
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **词典用 FST 而非 B+树/哈希** | 哈希查词 O(1) | FST 前缀共享（内存小一个量级）+ 支持前缀/模糊遍历（suggest/前缀查询的根基）；代价是不可变（segment 只增） |
| **倒排按 term 存（列式思维）** | 按文档存正排 | 查询是"按词找文档"——列式布局命中查询模式；写放大的代价由段合并消化 |
| **BM25 替代 TF-IDF** | 纯 tf×idf | tf 饱和（防堆砌词）+ 长度归一（防长文霸榜）——更符合"相关性"直觉 |
| **打分 norm 压缩成 1 字节** | 存 float | 内存/磁盘换精度——LENGTH_TABLE 查表解码，误差可接受 |
| **Query/Fetch 两阶段** | 查到即取原文 | 分片自治（各取本地 Top-N）+ 网络只传 docId/分数；Fetch 批量回取原文 |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **中文搜不准** | standard 按单字切（实测）→ 换 IK + 索引/查询期配套 |
| 2 | **term 查 text 字段无结果** | term 不分词 vs 字段被分词 → keyword 或改 match |
| 3 | **text 字段排序/聚合报错** | 倒排没有列存 → 用 `.keyword` 子字段 |
| 4 | **analyzer 两端不一致** | 查询 analyzer ≠ 索引 analyzer → 找不到词项 |
| 5 | **短词/noise 词打分异常** | 停用词/极短 token 的 idf 失真 → analyzer 去停用词 + minimum_should_match |
| 6 | **深分页慢** | from+size 是各分片取数再合并 → search_after |

---

## 七、面试 Q&A（含面试官意图）

**Q1："ES 为什么快？"**（意图：倒排模型）
预分词建"词项→文档"映射 + FST 词典 O(len) 定位 + posting 跳表合并——搜索查词项，不扫全表。

**Q2："BM25 是什么？"**（意图：打分理解）
idf（稀有词权重高）× tf 饱和（k1=1.2）× 长度归一（b=0.75）；norm 压缩成 1 字节。

**Q3："match 和 term 区别？"**（意图：DSL 语义）
match 分词查倒排（召回）；term 不分词精确查（只对 keyword/数值）。

**Q4："Query 和 Fetch 为什么分两阶段？"**（意图：分布式设计）
分片自治取本地 Top-N（只传 docId+分）→ 协调合并 → 批量回取原文（省网络）。

**Q5："词典为什么用 FST？"**（意图：数据结构）
前缀共享省内存 + O(term 长) 查询 + 支持前缀/模糊遍历（suggest 能力的根）——代价不可变（配 segment 追加写）。

---

## 八、章节依赖

- **上游**：K03（使用层：IK 实测/mapping/DSL 口径）；es-lab D1-D5
- **下游**：D-02（分布式索引架构：分片路由 + refresh/flush/merge 的 ES 侧实现）；D-03（纠错/补全/同义词：编辑距离 + FST + 同义词过滤器）
- **横链**：P04（B+树/LSM 存储引擎对照：FST vs LSM 的不可变哲学）

---

## 九、验证锚点

**Lucene 9.9.2**（`code/spring/lucene`，`releases/lucene/9.9.2`）
| 声明 | 位置 |
|---|---|
| BM25 默认值 k1/b | `lucene/core/.../search/similarities/BM25Similarity.java:99-101` |
| BM25 idf 公式 | `BM25Similarity.java:103-106` |
| BM25 norm/score | `BM25Similarity.java:132-142/246` |
| 词典 FST | `lucene/core/.../codecs/lucene90/blocktree/FieldReader.java:32`、`SegmentTermsEnum.java:33` |
| 倒排三文件 | `lucene/core/.../codecs/lucene99/Lucene99PostingsFormat.java:348/351/357` |

**ES 8.12.2**（`code/spring/elasticsearch`，`v8.12.2`）
| 声明 | 位置 |
|---|---|
| match 参数 | `server/.../index/query/MatchQueryBuilder.java:66/160`（operator）/`:84/298`（zeroTermsQuery） |
| 查询执行 | `server/.../search/query/QueryPhase.java:115`（executeQuery）/`:205`（searcher.search） |
| 文档写入 | `server/.../index/engine/InternalEngine.java:1465-1479`（indexWriter.addDocument） |
| refresh 入口 | `InternalEngine.java:2011`（`RefreshResult refresh`） |
