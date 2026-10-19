# Z: Elasticsearch 面试题速答（收束篇）

> 定位：es-lab 收束篇——把 D1-D5 的结论面试化。
> 基线：**Elasticsearch 8.12.2**（对齐 xhs-project）+ xhs 索引同步模式；本 lab 本地单节点实测。
> 题库锚点：手册 Ch18「Elasticsearch 与搜索设计」11 题（219-229）。
> 用法：先自测（盖住答案），答不出的回对应文档。

---

## 一、速记卡（一页纸）

1. **倒排索引**：文档先分词成词项，建"词项 → 文档列表"映射——搜索=查词项，不是扫全表。
2. **中文默认按字切**（standard：小红书种草笔记 → 小/红/书/种/草/笔/记）；生产用 **IK**（小红书/种草/笔记）。
3. **text vs keyword**：text 分词（全文检索+打分）；keyword 不分词（精确/排序/聚合）；需要两者用 multi-field。
4. **match vs term**：match 分词查倒排（找得到）；term 不分词精确查（找得准，只对 keyword/数值）。
5. **filter vs must**：语义相同；filter 不打分、可缓存（bitset）——过滤条件用 filter。
6. **深分页**：from+size 深翻页代价大（分片×from+size 合并）+ max_result_window=10000 上限——用 **search_after**。
7. **近实时（NRT）**：写入先进 buffer，默认 **1s refresh** 才可搜索——"刚写完搜不到"是正常现象。
8. **refresh vs flush**：refresh=buffer→segment（可搜索，未落盘）；flush=segment fsync+清 translog（持久化）。**可搜索 ≠ 已持久化**。
9. **分片**：建索引时定死（单分片 10-50GB 经验值）；副本=高可用+读扩展（写代价↑）。
10. **版本控制**：`external_gte` = 版本 ≥ 当前才接受——**同版本幂等重放、更旧版本 409 拒绝**（xhs 抗乱序核心）。
11. **同步架构**：DB 是事实源，ES 是检索副本；Canal/MQ → 消费者 → `version=updated_at` 写 ES。
12. **删除也带版本**：删除事件带 ts 写入（防旧更新把已删数据复活）——xhs T-091。

---

## 二、分组高频题

### A. 倒排与分词

**A1. ES 为什么快？**
倒排索引：预分词建"词项→文档"映射，搜索查词项拿 posting list + BM25 打分——避免全表扫描。回链：D1。

**A2. 中文搜索效果差怎么解决？**
装 IK 分词器（ik_smart/ik_max_word）+ 自定义词典/同义词。standard 按单字切导致词项碎、相关性差。回链：D1/D2。

**A3. 索引期和查询期的 analyzer 关系？**
必须一致（默认一致）——不一致会"索引切法≠查询切法"→搜不到。回链：D2。

### B. Mapping

**B1. text 和 keyword 的区别？**
text 分词（全文检索）；keyword 原样（精确匹配/排序/聚合）。一个字段两种能力用 multi-field（title + title.keyword）。回链：D2。

**B2. term 查询搜不到的原因？**
term 不分词——字段是 text（被分词）时输入对不上词项；用 keyword 或改用 match。回链：D2/D3。

**B3. 改了 mapping 怎么办？**
字段类型不可变——新建索引 + reindex + 别名原子切换。回链：D2/D4。

### C. 查询 DSL

**C1. match 和 term 的区别？**
match：分词→查倒排→打分；term：不分词→精确查词项。回链：D3。

**C2. filter 和 must 的区别？**
语义相同；filter 不参与打分、结果可缓存（bitset）——过滤用 filter 更快。回链：D3。

**C3. 深分页怎么解决？**
search_after（游标，无上限不能跳页）；scroll（导出）；避免大 from（max_result_window=10000）。回链：D3。

**C4. bool 四个子句？**
must（必配+打分）/ filter（必配不打分）/ should（可选+影响打分）/ must_not（必不配）。回链：D3。

### D. 分片与近实时

**D1. ES 为什么是近实时？**
写入先进内存 buffer，默认 1s refresh 成 segment 才可搜索——refresh_interval 决定"近"的程度。回链：D4。

**D2. refresh 和 flush 的区别？**
refresh 让数据**可搜索**（buffer→segment，PageCache）；flush 让数据**持久化**（fsync + 清 translog）。回链：D4。

**D3. ES 会丢数据吗？**
默认每请求 fsync translog——宕机可恢复未 flush 的写入；异步 translog 才有丢失窗口。回链：D4。

**D4. 分片数怎么定？**
建索引时定死；按数据量（单分片 10-50GB）+ 并发；宁少勿多（多分片有固定开销）。回链：D4。

### E. 同步与 xhs 模式

**E1. ES 和 DB 怎么同步？**
Canal 订阅 binlog（或业务 MQ）→ 搜索服务消费者 → 写 ES；以 updated_at 为外部版本号。回链：D5。

**E2. 同步乱序/重复怎么防？**
`external_gte`：更旧版本 409 拒绝（抗乱序）、同版本允许（幂等重放）。回链：D5。

**E3. 为什么删除也要带版本？**
防"删除后旧更新把数据写回"——删除事件带 ts 写入（xhs T-091）。回链：D5。

**E4. ES 数据错了怎么办？**
DB 是事实源——全量 reindex（别名切换）或对账修复（回链 dist-consistency D3）。回链：D5。

### F. xhs-project 对照

**F1. xhs 搜索服务结构？**
`my-xhs-search`：4 个索引同步消费者（笔记/商品/点赞/行为）+ 召回策略 + ExternalGte 版本控制。回链：D5。

**F2. 为什么 DB+ES 而不是只用 ES？**
ES 无事务、近实时、写入成本高——交易/事实在 DB，ES 只做检索副本（可重建）。回链：D5。

**F3. 搜索的"最终一致"怎么兜底？**
同步有延迟/丢失窗口——对账/重建索引兜底（与 dist-consistency 四模式同构）。回链：D5 + dist-consistency D3。

---

## 三、陷阱速查（12 连）

| # | 反直觉点 | 正解 |
|---|---|---|
| 1 | 搜中文"小红书"能整词命中 | standard 按单字切——需 IK |
| 2 | term 能查 text 字段 | 查不到（分词不匹配）——用 keyword/match |
| 3 | text 字段能排序/聚合 | 报错——用 .keyword 子字段 |
| 4 | match 和 term 都走分词 | term **不分词** |
| 5 | must 和 filter 性能一样 | filter 不打分+可缓存 |
| 6 | 深分页 from=10000 也行 | 超 max_result_window 报错；深翻页用 search_after |
| 7 | 写完立刻能搜到 | 近实时——默认 1s refresh |
| 8 | refresh 了就不会丢 | refresh≠持久化；translog/flush 才是 |
| 9 | 分片可以后期调整 | 建索引时定死——改要 reindex |
| 10 | 副本只提高可用性 | 还提升读 QPS——但写要同步副本（写变慢） |
| 11 | external 同版本可以重放 | external 严格大于；**external_gte** 才允许相等 |
| 12 | 消息乱序会导致旧覆盖新 | 版本仲裁——旧版本 409 拒绝 |

---

## 四、文档索引（回链）

| 主题 | 文档 |
|---|---|
| 倒排索引 / 分词 / 为什么快 | D1 |
| Mapping / text-keyword / IK | D2 |
| 查询 DSL / bool / 深分页 | D3 |
| 分片 / 副本 / 近实时 / refresh-flush | D4 |
| 索引同步 / ExternalGte / xhs 模式 | D5 |
| 实验端点（A01-A05） | a/ 源码 |
| 评审与验证记录 | REVIEW-2026-10-05 |

---

## 五、一页纸收束

> **检索**：倒排（词项→文档）+ 分词（中文必须 IK）；text=分词 / keyword=精确；match 分词 / term 精确。
> **查询**：bool（must/filter/should/must_not）；filter 不打分可缓存；深分页 search_after。
> **存储**：分片定死（10-50GB/片）+ 副本（读扩展+高可用）；近实时（1s refresh）；refresh≠持久化（translog）。
> **同步**：DB 事实源 → Canal/MQ → 消费者 → ES（version=updated_at + external_gte 抗乱序幂等）。
> **兜底**：同步有窗口 → 重建索引/对账（与 dist-consistency 四模式同构）。
