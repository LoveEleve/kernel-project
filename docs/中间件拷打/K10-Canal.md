# K10 — 组件深度拷打：Canal（+ 缓存一致性）

> 难度：★★★★★｜频率：★★★★☆｜区分度：高
> 关键词：模拟从库 / dump 协议、binlog ROW、位点 tsdb、flatMessage、external_gte、tombstone、回声保护、At-least-once
> 依据：xhs-project Canal 配置/代码（canal 1.1.7）+ 50-Canal深度拷打 + 07-canal-cache + canal-issue 事故记录 + 本地实验（版本 Lua / hash tag slot）

---

## 问题

MySQL 和 ES/缓存怎么同步？为什么用 Canal 不用双写？消息丢了/乱序怎么办？延迟双删 vs Canal 怎么选？Canal 挂了怎么办？

---

## 面试可讲版（五段式）

### ① 原理层（Canal 怎么工作）

**本质：伪装成 MySQL Slave**——注册 `slaveId`，向 master 发起 **dump 协议**请求，接收 **binlog（必须 ROW 格式）**，解析成结构化事件（INSERT/UPDATE/DELETE + 前后镜像）。

```
MySQL(binlog ROW) → Canal(模拟从库/dump) → 解析行级事件 → 投递
  投递模式（serverMode）：TCP / Kafka / RocketMQ（本项目）——Canal 只"解析+投递"，下游消费归 MQ
```

**四个关键概念**：
| 概念 | 说明 |
|---|---|
| **instance** | 一个订阅通道（`filter.regex` 过滤库表 + 独立 slaveId） |
| **位点** | binlog file+offset，持久化（file/zk/本地 tsdb）——重启续传，**at-least-once**（不丢但可能重复） |
| **flatMessage** | 事件平铺成 JSON（下游友好）；`database.hash`/`partitionHash` → 同一行事件进同一 MQ 分区 → **同 id 保序** |
| **设计边界** | DDL 不产生数据事件（映射变更走应用侧）；大事务/批量写放大单条消息 |

**为什么不用业务双写**：双写要么进事务（放大锁 + 侵入）、要么事务后写（丢失窗口）；Canal 让 **DB 变更成为唯一事实源**，业务零侵入——代价是链路长（秒级延迟）需要补偿。

### ② 项目用法（3 instance → 3 Topic，配置实证）

| instance | 源表（filter.regex） | slaveId | MQ Topic | 消费用途 |
|---|---|---|---|---|
| note_instance | `my_xhs_content.t_note` | 1001 | NOTE_INDEX_TOPIC（hash by id） | 笔记索引 → ES |
| product_instance | `my_xhs_product.t_spu, t_sku` | 1002 | PRODUCT_INDEX_TOPIC | 商品索引 → ES |
| inventory_instance | `my_xhs_inventory.t_inventory` | 1003 | INVENTORY_CACHE_TOPIC | 库存缓存失效 |

```
canal.properties：serverMode=rocketMQ、canal.port=11111、无 zkServers（单机无 HA）、flatMessage + batchSize=50
instance.properties：master.address=127.0.0.1:3306（三实例同）；分区策略：note/product 用 `partitionHash`（按 id）、inventory 用 `partitionByTable`（同表同分区）
```

**slaveId 冲突**：与真实从库/其他 Canal 撞 ID 会互相踢下线——1001-1003 固定登记（本项目体现）。

### ③ 一致性三段设计（ES 侧，xhs 口径）

```
① 抗乱序：ES 写入带 external_gte（version = Canal es 序号，回退 ts）→ 旧版本事件被 ES 拒绝（天然幂等）
② 防复活：删除写 tombstone（deleted=true 文档）而非物理删除 → 旧消息写不回
③ 兜底：IncrementalIndexSyncJob（按 updated_at 增量扫描回补，Canal 断链/丢消息的自愈通道）
        + IndexRebuildJob（全量重建 + 别名切换）
```

### ④ 缓存侧：回声保护（最有含金量的实战演进）

**inventory 是 L1(Redis) 权威架构**（preDeduct/release 先写 Redis 权威，L2 Consumer 异步写 MySQL）——于是 Canal 事件要分类型处理：

```
UPDATE/INSERT → 跳过（不删缓存！）——L2 MySQL 写入是 L1 操作的"回声"，
                 Redis 已是更新数据；删除会导致：
                 ① 后续 preDeduct 报"未初始化"库存链路崩坏；
                 ② getStock 用滞后的 MySQL 快照回填覆盖在途预扣 → 超卖（T-066/T-078 沉淀）
DELETE        → 完全删除该 SKU 的缓存（total/bucket/分桶数量三类 key）
out-of-band    → 管理员 SQL 直改走 /api/inventory/reinit 显式重建
```

**版本号防乱序（本地实验实证，`InventoryCacheEvictConsumer` 的 Lua）**：
```
Lua：GET 当前版本 → es > current ? SETEX(7天) 返回 1 : 返回 0
实验：es=101 → 1（通过）；乱序 es=100 → 0（拒绝）；重复 es=101 → 0（拒绝）；新 es=102 → 1 ✓
为什么必须 Lua：GET+比较+SET 三步非原子 → 并发消费者可能互补覆盖；Lua 串行化 → 版本单调递增
```
TTL 7 天防版本 key 无限增长。

**hash tag 实验（Redis Cluster 语义）**：
```
'inventory:{100}:total' / ':{100}:bucket:0' / ':{100}:bucket:3' / ':bucket:count:{100}' → 全 = slot 339（同 slot）
无 tag 对照：inventory:100:total=11440 / :bucket:0=3534 / :bucket:3=15789（分散）
→ {skuId} 让同一 SKU 的多把 key 进同一 slot（Lua 多 key 原子性的前提）
```

**为什么库存不用延迟双删（vs product 的选型分野）**：
```
延迟双删的 500ms 窗口：删缓存 → 并发读回填旧值 → 二次删来不及拦 → 读到旧值（对库存 = 超卖）
inventory：写多 + 强语义 → Canal 零窗口（事件驱动删除，不赌时间）
product：读多写少 + 容忍秒级旧值 → 延迟双删（3×50ms 重试 + 500ms 二删 + CACHE_EVICT_TOPIC 兜底）
决策台账 C9：承认 Canal 更优，product 迁移列为下一步
```

### ⑤ 事故与观测缺口（真实事件，加分项）

```
canal-issue（2026-07-31）：Canal 1.1.7 与 JDK 17 不兼容 → binlog decoder「静默罢工」
  （服务在跑、端口在听、就是不下发事件——最难排查的一类故障）
  修复：切 Kona JDK 8（OpenJDK 1.8.0_502）重启三实例；验证 UPDATE→10s 内 ES 同步 ✓
观测缺口（主动披露）：Canal 自身挂了不报警 = 静默不同步——靠增量补偿 Job 定时对账发现
```

### ⑥ 拷打追问（面试官视角）

1. **"Canal 原理一句话？"** 模拟 Slave 拉 binlog（ROW），解析行级事件投给下游；核心是 dump 协议 + 位点续传。
2. **"位点丢了会怎样？"** 从 tsdb 恢复；最坏无位点重启 → 重新消费（重复但幂等收敛）——**幂等是前提**。
3. **"怎么保证 ES/缓存一定一致？"** 不保证实时强一致——最终一致三件套（实时链 + 增量补偿 + 全量重建）；版本控制让乱序无害。
4. **"UPDATE 为什么可以不删缓存？"**（本项目特有）inventory 是 L1 权威：MySQL UPDATE 是 Redis 的回声，删除反而造成"未初始化"/滞后回填超卖——**缓存策略必须匹配数据的权威方向**。
5. **"全量和增量怎么衔接？"** 重建写新索引（别名切换）；重建窗口的增量要重放/追平，否则漏。
6. **"flatMessage 为什么？"** 原生 protobuf 事件下游不友好；flat JSON 可读易集成；代价是消息变大（前后镜像）。
7. **"Canal 高可用？"** 生产要 ZK 选主多 server；本项目单 server（边界），靠补偿任务兜底——**要主动说这是边界**。
8. **"和 Debezium/Flink CDC 怎么选？"** 同为 CDC：Debezium 走 Kafka Connect 生态、Flink CDC 带流计算；Canal 轻量、国内 Java 栈、直投 RocketMQ 顺滑。

### ⑦ 话术

> "ES 同步走 Canal：三个实例（note/product/inventory）抓 binlog ROW，flatMessage 投 RocketMQ 三个 Topic，按 id hash 保序。一致性三段：external_gte 抗乱序、tombstone 防复活、增量补偿 + 全量重建兜底。库存缓存更特殊——我们是 L1 Redis 权威架构，所以 UPDATE/INSERT 事件是回声、跳过不删，只有 DELETE 才清缓存，版本号用 Lua 单调校验防乱序。为什么库存不用延迟双删？500ms 窗口对库存就是超卖风险，Canal 是事件驱动零窗口。踩过的坑也真实：Canal 1.1.7 对 JDK 17 不兼容会静默罢工，切 Kona JDK 8 修复；单 server 无 HA、自身无告警是主动披露的观测缺口，靠补偿 Job 对账发现。"

---

## 生产陷阱（Canal 7 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **binlog 不是 ROW** | STATEMENT 解析不出前后镜像——上线前必查 `binlog_format` |
| 2 | **binlog 保留不够** | Canal 断线超过保留期 → 位点失效重头来——保留 30 天 + 位点监控 |
| 3 | **以为精确一次** | at-least-once（重启/位点回退重复）——消费幂等是前提（版本号/唯一键） |
| 4 | **删除写回（复活）** | 旧消息把已删文档写回——tombstone + 版本控制 |
| 5 | **缓存策略不看权威方向** | L1 权威架构却按"缓存"删——回声删除导致超卖（T-066 教训） |
| 6 | **slaveId 冲突** | 与从库/其他实例撞 ID 互相踢——固定登记管理 |
| 7 | **静默罢工无告警** | JDK 不兼容/网络断链"服务在跑但不同步"——补偿 Job 对账 + 延迟监控 |

---

## 发散追问地图（横向）

- **CDC 对比**：Debezium（Kafka Connect）/ Flink CDC（流计算）/ DataX（离线批）/ 业务双写——链路、生态、延迟
- **原理深入**：dump 协议交互（COM_BINLOG_DUMP）、binlog 事件类型（WriteRows/UpdateRows/DeleteRows）、位点存储（file/zk/tsdb）
- **多下游**：一个 Canal 投多目标（缓存/索引/数仓）；MQ 分区策略与保序
- **一致性体系**：at-least-once + 幂等 + 版本 + 对账 + 重建（与 dist-consistency 四模式同构）
- **缓存一致性全景**：Cache Aside / 延迟双删 / 逻辑过期 / Canal 失效 / 对账（xhs 02 题）
- **运维**：Canal Admin、延迟监控（位点差）、HA（ZK 选主）

---

## 面试官评分点

**高级开发级**：模拟从库/dump/位点/flatMessage 讲得清；知道 at-least-once 与幂等前提；external_gte/tombstone 方案能画出来。
**架构师加分**：**回声保护的设计判断**（缓存策略匹配数据权威方向）；延迟双删 vs Canal 的选型分野（写多 vs 读多）；三段一致性闭环；JDK 17 静默罢工事故的排查叙事；主动披露 HA/告警缺口。
**危险信号**：以为 Canal 精确一次；删除消息把文档写回；全量与增量衔接没方案；binlog 没开 ROW；拿"缓存一致性"套 L1 权威场景。

---

## 本项目真实证据

**xhs-project**：
- 配置：`config/canal/conf/canal.properties`（serverMode=rocketMQ / port 11111 / 无 zk）+ 3 个 `instance.properties`（filter.regex / slaveId 1001-1003 / Topic / partitionByTable）
- 消费端：`InventoryCacheEvictConsumer`（回声保护 T-066/T-078 注释全文、版本 Lua `VERSION_CHECK_SCRIPT`、DELETE-only、7 天 TTL）；`NoteIndexSyncConsumer`/`ProductIndexSyncConsumer`（ExternalGte:253/:269）
- 兜底：`IncrementalIndexSyncJob` + `IndexRebuildJob`
- 衍生：`sql` 对账、`CACHE_EVICT_TOPIC`（product 延迟双删兜底）
- 事故：`docs/canal-issue.md`（JDK 17 → Kona JDK 8，2026-07-31 修复）

**本 lab（本地实验）**：
- 版本检查 Lua：乱序拒绝 / 重复拒绝 / 新品通过（4 断言全过）
- hash tag slot：`{100}` 四 key 全同 slot 339；无 tag 对照分散（CRC16 计算，Redis Cluster 语义）

**课程对照**：xhs 50-Canal深度拷打 + 02-缓存一致性 + 07-canal-cache 分析 + 决策台账 C9。

---

## 版本与来源

Canal **1.1.7**（xhs 部署配置与代码）；官方文档（dump/flatMessage/serverMode）；xhs-project Canal 配置/消费端代码/事故记录；本地 Redis 实验。

## 真实性说明

Canal 原理为标准知识 + xhs 配置实证；回声保护/版本 Lua/slaveId/三段一致性为代码与配置事实（注释与行号已核）；JDK 17 事故为文档事实；两个实验为本机实测（版本 Lua/CRC16 slot）。

---

## 完成度（K 系列 + Canal 单元）

```
docs/中间件拷打/
├── K01-RocketMQ ✅  K02-Redis ✅  K03-ES ✅  K04-Nacos ✅  K05-Sentinel ✅
├── K06-Seata ✅  K07-Feign-LB ✅  K08-Dubbo ✅  K09-gRPC ✅  K10-Canal ✅
└── REVIEW-2026-10-05.md（K02~K09 深审记录；K10 待并入）
```
