# ES 搜索内核系列

> 基线：**Elasticsearch 8.12.2**（对齐 xhs-project，lab 端口 19200）
> 素材：`es-lab/docs/`（D1~D5 + Z 六组 A~F，已审校）
> 源码/环境：`/data/workspace/tools/elasticsearch-8.12.2`
> 理论线（前置）：RocketMQ 05 §5（乱序无害化埋过 ExternalGte 钩子）
> xhs 实锤：13 处 Java（`NOTE_INDEX`/`PRODUCT_INDEX` 同步 + `ExternalGte` 版本控制）

## 一、总表（7 篇）

| # | 文章 | 类型 | 核心内容 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [倒排索引：为什么搜批量能秒找到](01-inverted-index.md) | 理论 | 分词→词项→倒排；MySQL 两个不够用；边界 | D1 |
| 02 | [Mapping 与分词：字段类型决定能不能搜到](02-mapping-analyzer.md) | 理论 | text/keyword 取舍；analyzer 链路；坑 | D2 |
| 03 | [查询 DSL：match 与 term 怎么选](03-query-dsl.md) | 使用 | match vs term 本质；bool 四字句；深分页 | D3 |
| 04 | [分片与近实时：写入后为什么 1 秒才搜到](04-shards-nrt.md) | 原理 | 分片底座；写性能与可见性权衡 | D4 |
| 05 | [索引同步：ExternalGte 抗乱序](05-sync-externalgte.md) | 生产 | 三种同步事故；版本语义（实测）；xhs 同步链路 | D5 |
| 06 | [xhs 生产对照：检索链路全景](06-xhs-production.md) | 生产 | 13 处实锤汇总；4 消费者/ExternalGte/IK 非对称；检索产品面 | Z-F/D 对照段 |
| 07 | [面试收口：速记卡与陷阱](07-interview-wrapup.md) | 收口 | Z 六组回链；高频坑；终检 | Z |

## 二、学习路径

```text
基础：01 → 02
使用：03
内核：04
生产：05 → 06
收口：07
```

## 三、前置要求

- MySQL 基础（索引/慢查询——01 用它做反面对照）。
- RocketMQ 05 §5（乱序治理提到 ExternalGte，本系列 05 展开）。
- Boot 自动配置基础（Tracing 04 过一遍即可，本系列用得少）。

## 四、掌握分级

- **必须掌握**：倒排结构；text vs keyword；match vs term；refresh=1s 近实时；ExternalGte 版本语义。
- **理解思想**：分片/副本与读写路由；深分页为什么贵；同步三事故（乱序/丢失/重复）。
- **知道即可**：IK 分词器细节；bulk API；集群级调优。

## 五、边界

- 集群部署/安全/TLS → 知道即可（lab 为单节点实测；xhs 部署拓扑不在本系列考证）。
- Kibana/可视化 → 非本系列。
- 大数据侧（Flink 接 ES）→ 翻篇（xhs 走 RocketMQ→Service→ES）。
