---
title: "03-原理二：HikariCP参数与治理"
series: "连接池与 ORM 系列"
previous: "02-hikaricp-borrow.md"
next: "04-Druid.md（待写）"
source_refs:
  - "hikaricp 源码已核：softEvictConnections:381/maxLifetime 驱逐:864/keepalive+抖动:498-502/ProxyLeakTask 类族/fill:533"
  - "xhs 参数实例（Sharding 04 引）：max 10/min 2/keepalive 30s/maxLifetime 30min"
---

# 03-原理二：HikariCP参数与治理

## §0 它干了什么（例子先行）

池不是建好就不管——四件治理后台转着：

```
maxLifetime 到期 → 该连接"光荣退役"（softEvict :864）→ 补新的（fill :533）
keepaliveTime 心跳 → 空闲连接定期 PING（:498，再随机少等 0~20% 时间——防全池同拍）
泄漏检测 → 借出超 threshold 未还 → ProxyLeakTask 打警告栈（不强杀——只报警）
用户手动 softEvictConnections(:381) → 运维一键"换血"
```

- **它干了干什么**：**让池里的连接"有寿命、有心跳、有看管"**——防的全是运维事故：死连接/老化连接/借了不还。
- **本篇回答**：六大参数各管什么？治理四件谁触发谁？xhs 的参数组合什么逻辑？

## §1 概念铺垫（What：参数语义表）

| 参数 | 管什么 | 常见值 |
|---|---|---|
| **maximumPoolSize** | 池上限——DB 连接数预算的分配额 | xhs 每 ds 10 |
| **minimumIdle** | 最小空闲——保底热连接 | xhs 2 |
| **connectionTimeout** | 借连接等不到的上限 | 默认 30s |
| **idleTimeout** | 空闲超时回收（仅当>minIdle） | 常 10min |
| **maxLifetime** | **单连接寿命**——到期退役（防 DB 侧防火墙/负载均衡掐线） | xhs 30min |
| **keepaliveTime** | 空闲心跳间隔（Hikari 校验其必须小于 maxLifetime，且 ≥30s） | xhs 30s |
| **leakDetectionThreshold** | 泄漏报警阈值（借出未还超时打栈） | 按任务时长设 |

**记忆钩**：**大小（max/min）、等待（timeout）、寿命（maxLifetime）、心跳（keepalive）、报警（leak）**——五个维度。

## §2 治理四件的触发关系

```
定期任务（HouseKeeper 节奏）
  ├─ keepalive：挑空闲够久的连接 PING（:498 随机少等 0~20%——防全池同拍）
  ├─ maxLifetime 到期 → softEvict（:864）→ 异步 fill 补位（:533）
  └─ 泄漏检查：ProxyLeakTask 到点看借出记录
事件驱动
  └─ softEvictConnections(:381)——运维/健康检查主动换血
```

**maxLifetime 的工程意义**：DB 侧（RDS/SLB）常在 30min~4h 掐长连接——**池侧先退役=业务无感换新**；不设=业务撞上 DB 掐线瞬间报错。

## §3 xhs 参数组合解读（Sharding 04 引的那组）

```
max 10 / min 2 / keepalive 30s / maxLifetime 30min
```

- **每 ds 只给 10**：分片库多（4 ds）——总连接预算被分片摊薄（16 分片也就 40 连接，防 DB 侧爆炸）；
- **keepalive 30s**：空闲保活激进（分片库可能长期低频）；
- **maxLifetime 30min**：典型 SLB 掐线周期内主动退役；
- **组合逻辑**：**小池+勤体检+短寿命**——分片场景的标准姿势。

## §4 掌握卡（2 条）

1. **治理四件**：寿命退役（maxLifetime）/心跳（keepalive 带抖动）/泄漏报警（ProxyLeakTask——只报警不强杀）/手动换血（softEvict）——**全是"防运维事故"不是"防业务"**。
2. **参数五维度**：大小/等待/寿命/心跳/报警——调参口诀："**DB 掐线看 maxLifetime，空闲死链看 keepalive，借了不还看 leak**"。

## §5 边界（一句话翻篇）

- HouseKeeper 定时细节/填充队列调度 → 源码用时查。
- initSQL/连接级钩子 → 知道存在。
- metrics（Hikari 自带 Micrometer 绑定）→ micrometer 03 Binder 已覆盖视角。

## §6 Self-Test（3 题）

- Q1：maxLifetime 为什么必须设？（DB 侧掐线）
- Q2：keepalive 的随机抖动防什么？
- Q3：泄漏检测是报警还是强杀？为什么？

## §7 衔接

- Hikari 双篇收口；下篇 04 Druid：监控与防火墙更强的另一池——和 Hikari 怎么选。
