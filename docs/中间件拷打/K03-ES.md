# K03 — 组件深度拷打：Elasticsearch

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：倒排索引/分词、Mapping（text/keyword）、查询 DSL、分片/副本、近实时（NRT）、索引同步、ExternalGte 版本仲裁
> 依据：es-lab D1-D5 + Z（本地 **ES 8.12.2** 实测）+ xhs-project `my-xhs-search` + 手册 Ch18（11 题）

---

## 问题

ES 在项目里干什么？为什么搜索快？mapping 和分词怎么定？分片怎么规划？写入后为什么搜不到？DB 和 ES 怎么同步、乱序和删除怎么防？

---

## 面试可讲版（五段式）

### ① 倒排索引与分词（原理核心）

**为什么快**：倒排索引 = 预建"**词项 → 文档列表**"（posting list）+ 词典索引（FST）——搜索是"查词项"，不是扫全表。对比 `like '%关键词%'`（全表扫描）是数量级的差异。

**分词是检索的地基**（实测：standard 把"小红书种草笔记"切成 **小/红/书/种/草/笔/记**——单字；生产必须 **IK**：小红书/种草/笔记）：

| 策略 | 用途 |
|---|---|
| `ik_max_word` | 索引期：细粒度多切（保召回） |
| `ik_smart` | 查询期：粗粒度（保精度） |
| **xhs 口径** | title：索引 `ik_max_word` + 查询 `ik_smart`（一多一少）；content：`ik_smart` |

**索引期/查询期 analyzer 必须一致**（默认一致；不一致 → "索引切法 ≠ 查询切法"→ 搜不到）。

### ② Mapping 与查询 DSL（使用核心）

**Mapping（字段类型决定能不能搜到）**：
- `text`：分词（全文检索 + BM25 打分）；`keyword`：不分词（精确/排序/聚合）
- 一个字段两种能力 → multi-field（`title` + `title.keyword`）
- **类型不可变**：改 mapping = 新建索引 + reindex + 别名原子切换

**DSL 三组高频对照**：
```
match（分词查倒排）vs term（不分词精确查，只对 keyword/数值）
must（必配+打分）vs filter（必配不打分+可缓存 bitset）→ 过滤条件一律 filter
深分页：from+size（各分片取 from+size 再合并，代价大）→ search_after（游标）
        max_result_window=10000 是硬上限
```

**xhs 索引定义**（`IndexInitializer`）：note_index / product_index / suggest_index 三个索引；3 分片；`coverImage` 用 `keyword + index:false`（只存不检索）。

### ③ 分片/副本与近实时（存储核心）

- **分片数建索引时定死**（经验：单分片 10-50GB；宁少勿多——多分片有固定开销）；改 = reindex
- **副本** = 高可用 + 读扩展（写要同步副本 → 写变慢）；xhs 单节点环境 `replicas=0`（避免 yellow 告警常鸣），多节点可调大
- **近实时（NRT）**：写入先进 buffer，默认 **1s refresh** 才可搜索——**"刚写完搜不到"是正常现象**（实测：写入后立即 count=0 → refresh 后可搜到）
- **refresh ≠ 持久化**：refresh=buffer→segment（可搜索，未 fsync）；flush=fsync + 清 translog（持久化）——默认每请求 fsync translog，宕机可恢复未 flush 写入

### ④ 同步与一致性（xhs 实战）

**全链路（DB 是事实源，ES 是检索副本）**：
```
MySQL（my_xhs_content.t_note 等）
  → Canal 订阅 binlog → RocketMQ NOTE_INDEX_TOPIC / PRODUCT_INDEX_TOPIC ...
  → my-xhs-search 4 个同步消费者（笔记/商品/点赞/行为，maxReconsumeTimes=3）
  → ES index（文档 ID = 业务 ID → 天然 upsert 幂等）
```

**乱序仲裁（ExternalGte）**：
- version = Canal 全局序号 `es`（严格递增）→ 回退 `binlogTimestamp` → 事件时间 → now
- `ExternalGte`：**版本 ≥ 当前才接受**——同版本幂等重放、更旧版本 **409 拒绝**（实测语义）
- 扁平格式路径用严格 `External`（必须更大）

**删除防复活（T-091，xhs 修复）**：
- 删除 = **标记删除**（`status=-1` + ExternalGte 带删除事件 version）——物理删除会导致"旧 INSERT/UPDATE 乱序到达时版本检查失效（文档不存在→不校验）→ 数据复活"
- 搜索时过滤 `status=-1`；真正物理删除由**全量重建**执行（重建不索引已删记录）

**兜底**：IncrementalIndexSyncJob（增量对账）+ IndexRebuildJob（全量重建，均用 ExternalGte）+ 与 dist-consistency 对账模式同构。

### ⑤ 拷打追问（面试官视角）

1. **"ES 为什么快？"** 倒排（词项→文档）+ FST 词典 + 打分只算候选集——不是"分布式所以快"。
2. **"中文搜索效果差？"** 分词器问题：standard 按单字（实测），换 IK + 自定义词典/同义词；索引/查询 analyzer 要配套（xhs 一多一少）。
3. **"term 查不到值？"** 字段是 text 被分词了——term 只对 keyword/数值；或查询 analyzer 与索引不一致。
4. **"filter 和 must 差别？"** 语义一样；filter 不打分 + 结果缓存（bitset）——过滤场景必用 filter。
5. **"深分页怎么做？"** search_after（无上限、不能跳页）；scroll 导出；from+size 深翻页 = 每分片取 from+size 再合并（O(from+size)×分片）。
6. **"写完为什么搜不到？"** 1s refresh 的 NRT；等 refresh 或写入后 `refresh=wait_for`（按需）；别把它当"丢失"。
7. **"ES 会丢数据吗？"** 默认 translog 每请求 fsync——不丢；refresh 只影响可搜索性，不影响持久化。
8. **"分片数怎么定/能改吗？"** 定死，改要 reindex；按数据量（10-50GB/片）+ 并发；副本=可用性+读扩展。
9. **"同步乱序/重复/删除？"** 版本仲裁（ExternalGte 旧版本拒绝）+ 业务 ID 幂等 + 标记删除防复活 + 重建兜底。
10. **"ES 挂了怎么办？"** 检索降级（DB 兜底/缓存旧结果）；恢复后按版本重放/重建索引——ES 可重建，不承载事实。

### ⑥ 话术

> "ES 在我们项目是检索副本：MySQL 事实源，Canal 订阅 binlog 发 RocketMQ，my-xhs-search 四个消费者写 ES，文档 ID 用业务 ID 天然幂等。乱序用 ExternalGte 版本仲裁——version 取 Canal 全局序号，旧消息 409 拒绝；删除是标记删除 status=-1 带版本，防旧消息复活，物理删除交给全量重建。索引三个（笔记/商品/建议），3 分片、IK 分词（title 索引 ik_max_word 查询 ik_smart）。搜索走 match+filter，深分页用 search_after。近实时 1s refresh——'写完搜不到'是设计，不是 bug。"

---

## 生产陷阱（ES 8 条，对齐 es-lab）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **中文按单字切** | standard 分词器——换 IK（实测："小红书"→小/红/书） |
| 2 | **term 查 text 搜不到** | term 不分词 vs 字段被分词——用 keyword 或 match |
| 3 | **text 排序/聚合报错** | 用 `title.keyword`（multi-field） |
| 4 | **must 当 filter 用** | 白白打分——过滤条件一律 filter（可缓存） |
| 5 | **深分页 from=10000+** | max_result_window 报错——search_after |
| 6 | **写完立刻搜不到** | NRT 1s refresh——正常语义 |
| 7 | **以为 refresh 就持久** | refresh≠flush；translog 才是持久化 |
| 8 | **删除后数据复活** | 物理删除+乱序旧消息——标记删除+版本（xhs T-091） |
| 9 | **单节点配副本** | replicas>0 无法分配 → yellow——xhs 默认 0 |
| 10 | **分片后期想改** | 定死——reindex+别名切换 |

---

## 发散追问地图（横向）

- **对比**：MySQL 全文索引（数据量小可替代）、Solr（老牌）、OpenSearch（分叉）；日志场景 ELK vs 业务检索
- **集群**：节点角色（master/data/coordinating/ingest）；7.x+ 自动选主（不再脑裂配置）；跨集群搜索
- **性能**：段合并（merge 策略）、force_merge（只读索引）、慢查询日志、doc_values（排序/聚合列存）
- **打分**：BM25（默认）→ function_score/boost 业务加权（xhs 召回策略权重）
- **生态**：Kibana（可视化）、Logstash（采集）vs Canal（binlog）；xhs-ai 的 EsSearchTool（AI Agent 查 ES）
- **前沿**：kNN 向量检索（8.x）→ 语义召回/推荐场景

---

## 面试官评分点

**高级开发级**：倒排/分词讲得清；text vs keyword、match vs term、filter vs must 三组对照；NRT 与 refresh≠持久化；知道自己项目索引/分片/分词配置。
**架构师加分**：同步链路的版本仲裁设计（ExternalGte + 标记删除防复活）；为什么 DB+ES 双写不如 Canal；分片规划与 reindex 代价；ES 只做副本的可重建定位；降级与对账兜底。
**危险信号**：以为 ES 是数据库（事务/强一致）；说不清 term 为什么搜不到；深分页只会 from+size；同步只说"MQ 发一下"不谈乱序/删除；分片数随手拍。

---

## 本项目真实证据

**xhs-project `my-xhs-search`**：
- 索引：note_index / product_index / suggest_index（`IndexInitializer`：3 分片、IK、title 双分词、coverImage `keyword index:false`、副本默认 0）
- 消费者 4 个（`NoteIndexSyncConsumer` / `ProductIndexSyncConsumer` / `LikeCountSyncConsumer` / `BehaviorReportConsumer`，均 `maxReconsumeTimes=3`）
- 版本仲裁：`NoteIndexSyncConsumer:253`（ExternalGte）/ `:282`（External）/ `:326`（标记删除 ExternalGte，T-091 注释完整）；`ProductIndexSyncConsumer:269/336/378`
- 兜底 Job：`IncrementalIndexSyncJob:258/307`、`IndexRebuildJob:384/438`
- 召回：Content/Following/ItemCF/Geo/Hot 5 个 RecallStrategy；xhs-ai `EsSearchTool`/`EsIndexListTool`

**本 lab（es-lab，8.12.2 本地实测）**：
- A01-A05 实测：standard 中文按字切 / 近实时 count=0 / ExternalGte 语义（相等允许、更旧 409）/ 同步链验证
- D1-D5 五篇 + Z 速答（12 陷阱）+ REVIEW

---

## 版本与来源

Elasticsearch **8.12.2**（本地 lab，对齐 xhs-project 客户端）；官方文档（mapping/DSL/NRT/translog）；xhs-project `my-xhs-search` 代码；手册 Ch18（219-229，11 题）。

## 真实性说明

ES 机制（倒排/分词/NRT/版本控制）为标准知识且经本地 8.12.2 实测；xhs 用法（3 索引/4 消费者/版本取 Canal 序号/T-091 标记删除/副本 0）均为代码事实（行号已核）。
