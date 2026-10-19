---
title: "08-深水：池与SQL故障排查"
series: "连接池与 ORM 系列"
previous: "07-mybatis-plus.md"
next: "09-面试收口.md（待写）"
---

# 08-深水：池与SQL故障排查

## §0 它干了什么（例子先行）

线上三个经典症状，一张排查路线图：

```
症状1 "获取连接超时"（HikariPool ... Connection is not available, request timed out）
  → 池耗尽：active=max 且 pending 排队 → 三查：慢SQL占住/长事务不还/代码泄漏
症状2 "连接突然报错/断链"（Communications link failure）
  → 死连接：DB/SLB 掐线而池没退役 → 查 maxLifetime/keepalive 是否配
症状3 "偶发慢"（P99 毛刺）
  → 慢 SQL 占连接引发排队放大 → Druid StatFilter/慢SQL日志定位
```

- **它干了干什么**：池故障=**连接周转链断在哪**——占住（慢/泄漏）/退役（死链）/预算（打满）。
- **本篇回答**：三类症状各自的定位工具链是什么？连接数预算怎么算？

## §1 排查三线（工具链）

**① 池耗尽（最常见）**：

| 信号 | 含义 | 下一步 |
|---|---|---|
| `hikaricp_connections_active == max` 持续 | 全借出 | 看谁占着 |
| `hikaricp_connections_pending > 0` | 有线程排队等 | 已进入劣化 |
| Hikari 泄漏警告栈（ProxyLeakTask） | 借出超阈值 | 直接定位代码行（03 治理篇） |
| DB `SHOW PROCESSLIST` 长时间 Sleep 连接 | 借了不用/事务没关 | 对照应用侧事务边界 |

**根因三分**：慢 SQL（查询本身慢，占住不放——Druid StatFilter 找）/ 长事务（@Transactional 范围过大——内核 03 失效清单的"反向"）/ 泄漏（忘记 close/异常路径没归还——try-with-resources 纪律）。

**② 死连接**：症状=间歇性 `Communications link failure`/`connection reset`；根因=DB 侧（wait_timeout/SLB 空闲掐线）而池没主动退役——**处方：maxLifetime < 掐线周期 + keepalive 保活**（03 §3 的 xhs 组合就是答案）。

**③ 慢 SQL**：Druid StatFilter（SQL 耗时排行——04）/ MySQL 慢查询日志 / MyBatis 侧 `sql-show`（**生产必须关**——Sharding 04 事故 3 的 P-D32 血泪：16 分片放大日志爆炸）。

## §2 连接数预算（容量篇）

```
DB 可用连接数 = max_connections − 管理/复制占用
应用侧预算 = Σ(每服务实例数 × 每数据源 maximumPoolSize) ≤ DB 可用 × 安全系数
xhs 示例：4 ds × 10/实例 × N 实例——分片摊薄连接（Sharding 04 的 10 正是这么来的）
```

**教训口径**：池参数不是"越大越好"——**超过 DB 预算=把耗尽从池搬到 DB**（DB 侧连接风暴）；宁小勿大+快借快还。

## §3 掌握卡（2 条）

1. **三线排查**：耗尽看 active/pending+泄漏栈+PROCESSLIST；死链查 maxLifetime/keepalive；慢 SQL 走 Druid Stat/慢日志——**工具在 02~04 全学过，本篇是组合拳**。
2. **预算铁律**：**池之和 ≤ DB 预算**——扩容先算这个，不是先加 maximumPoolSize。

## §4 边界（一句话翻篇）

- 具体监控面板配置 → 运维侧。
- DB 侧参数（wait_timeout）调优 → MySQL 线。
- 连接风暴的网关限流配合 → Sentinel 系列已封卷。

## §5 Self-Test（3 题）

- Q1：池耗尽的三根因？泄漏怎么最快定位？（ProxyLeakTask 栈）
- Q2：死连接的处方两味药？
- Q3：连接数预算公式？为什么池参数不是越大越好？

## §6 衔接

- 深水线收口；下篇 09 面试收口：八篇回链 + 高频坑 + 终检——本系列完结。
