# L3-04 — 购物车域：三结构协同与 CLEAR 屏障

> **定位**：L3 业务域链路第 4 篇（P0 缺口：32-购物车）。四段式：**时序 → 决策 → 组件映射 → 拷打**。
> **依据**：xhs-project 购物车代码（CartService/7 Lua/消费者/对账）+ 32-购物车 + C-05/C-24/G3-02-15/P2-7/T-107 修复记录。

---

## 一、它干了什么：三结构 + 事件流水

**存储（三结构协同，同用户同 slot）**：
```
myxhs:cart:{userId}:items    Hash   （field=skuId → quantity，数量）
myxhs:cart:{userId}:checked  Set    （member=skuId，选中态）
myxhs:cart:{userId}:sort     ZSet   （member=skuId，score=加购时间戳，排序）
hash tag {userId} → Cluster 下三 Key 同 slot（多 Key Lua 原子前提）
```

**写链（Redis 权威 + MQ 异步落库）**：
```
加购/删/改/勾选 → Lua 原子执行（三结构一次往返）→ 发事件 → CartSyncConsumer 落 MySQL
上限：50 品种 / 单 SKU 99 件（Lua 内截断）；TTL 30 天滑动（C-13：原无过期违背"7天"注释）
```

**7 个 Lua**：add（含 T-107 的 10000 新旧标志位）/ remove / update_quantity / check_item / check_all / clear / merge_item。

---

## 二、设计决策

| 决策 | 为什么 |
|---|---|
| **三结构分工** | 数量（Hash 点查）/选中（Set 集合运算）/排序（ZSet 时间序）——三种访问模式各取所长 |
| **Lua 原子多 Key** | 加购=检查上限+HINCRBY+截断+SADD+ZADD——拆开会有中间态（上限绕过/勾选丢失） |
| **T-107 新旧标志** | 返回值 >10000=新商品（发 ADD 事件 checked=1）；1~10000=已存在（发 UPDATE 不改勾选）——修复"已存在加购强制改勾选"不一致 |
| **Redis 权威** | 高频写+低价值+可容忍极小丢失；DB 是持久化与恢复源；对账兜底（CartReconcileJob 凌晨 4 点，以 Redis 为准） |
| **CLEAR 屏障（C-05）** | 清空写 `cleared` 标记 + `clearBarrierTs`；并发 ADD 事件早于屏障 ts → 落库侧跳过——防"清空后旧事件回灌"；同毫秒用 **EVENT_SEQ** 单调序号（plusNanos）补精度 |
| **合并取 max（幂等）** | 登录合并匿名车：同 SKU 取较大值（≤99）——重试/多端重复触发结果一致；累加会翻倍 |
| **SKU 存在性校验** | 合并时查商品存在（G3-02-15）——幽灵条目（已下架/删除）不进车 |
| **恢复判定（P2-7）** | items 空 + 有 cleared 标记 → 真空车（用户主动清空）；无标记 → DB 恢复回写——"有标记才不恢复"防把清空误恢复 |
| **C-24 空车全选** | 空购物车 `allChecked=false`（不应显示"全选"）——边界语义 |

---

## 三、组件映射（回链 K 系列）

| 链路步骤 | 组件 | 回链 |
|---|---|---|
| 三结构/Lua/hash tag | Redis（K02） | K02（脚本原子/20B 定长/CROSSSLOT） |
| 异步落库/事件 | MQ（K01） | K01（发送语义/重试） |
| 对账收敛 | CartReconcileJob（K13） | K13（凌晨 4 点/锁/幂等） |
| 商品校验/失效标记 | Product 批量查（K07） | K07（Feign 超时/批量） |
| 故障语义 | 主库暂停 0.64s 回退（32 章实测） | K02（Sentinel 切主） |

---

## 四、拷打问题

1. **"为什么 Redis 当权威而不是 DB？"** 高频写+低价值+可容忍极小丢失；DB 是持久化与恢复源；对账兜底——权威≠唯一副本。
2. **"CLEAR 屏障具体怎么判？"** 事件带 clearBarrierTs + EVENT_SEQ 单调序号；落库侧"早于屏障跳过"——解决清空与并发 ADD 竞态。
3. **"合并为什么不累加？"** 重试/多端重复触发会翻倍；max 语义符合"合并同一个车"直觉且天然幂等。
4. **"恢复会不会把清空的车拉回来？"** cleared 标记保护：有标记=主动清空返回空车；只有"无标记且 items 空"才判定丢失并恢复。
5. **"上限 50/99 怎么定的？"** 业务约束（品种/单品囤货上限）+ Lua 内截断（原子）；超限拒绝而非静默截断（调用方可感知）。
6. **"Cluster 下多 Key 怎么保证？"** hash tag `{userId}` 三 Key 同 slot；不加直接 CROSSSLOT。

---

## 五、话术

> "购物车是 Hash 数量、Set 选中、ZSet 排序三结构，用 userId 做 hash tag 保集群同 slot；加购删除清空都在 Lua 里原子完成，上限 50 种、单品 99。清空和并发写用 CLEAR 屏障加同毫秒单调序号解决，防止旧事件回灌。Redis 是权威、MQ 异步落库，丢了先看 cleared 标记——真清空返回空车，否则从 MySQL 恢复回写。匿名合并取 max 保证幂等，还校验 SKU 防幽灵条目。"

---

## 六、回链索引

| 主题 | 文档 |
|---|---|
| Redis 结构/Lua/hash tag | K02 |
| MQ 异步/重试 | K01 |
| 对账 Job | K13 |
| Feign 批量 | K07 |
| xhs 章节 | 32-购物车 |
