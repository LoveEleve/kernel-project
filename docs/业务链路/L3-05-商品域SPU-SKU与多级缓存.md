# L3-05 — 商品域：SPU/SKU 模型与多级缓存

> **定位**：L3 业务域链路第 5 篇（P0 缺口：57-商品域 + 27-商品多级缓存）。四段式：**时序 → 决策 → 组件映射 → 拷打**。
> **依据**：xhs-project 商品域代码（SpuService/SkuService/CategoryService）+ 57/27 章。

---

## 一、它干了什么：模型 + 读写双链

**模型（三表）**：
```
t_spu（name/brand_id/category_id/images/status）—— 标准化产品（如"连衣裙"）
t_sku（spu_id/specs/price/original_price/stock/status）—— 最小库存单元（如"红色-S"）
t_category（parent_id/level/icon/sort/status）—— 多叉类目树
价格在 SKU 上（SPU 只展示起售价）；SPU 详情聚合其 SKU 列表
```

**读链（SPU 详情，多级缓存）**：
```
Bloom（myxhs:product:bloom:spu，tryInit 100万/1%）→ 不存在直接拒（防穿透）
→ Redis 详情 → 逻辑过期 30min 内直接返回（过期值+异步单飞刷新）
→ 物理 TTL 120min → miss 则单飞（myxhs:product:lock:spu:{id} tryLock(0,10s) 非阻塞）回源 DB
→ 空值占位（防穿透）→ 浏览埋点异步写行为表（不阻塞读）
```

**写链（更新/上下架）**：
```
改 DB → TransactionSynchronization.afterCommit → evictSpuCache（立即删）
      → SPU_DELAY_EVICT_SCHEDULER 1s 后二次删（覆盖并发读异步重建窗口）
类目树：改 DB → 删 myxhs:product:category:tree（TTL 2h）→ 下次读重建（Redis 不可用降级直查 DB）
```

---

## 二、设计决策

| 决策 | 为什么 |
|---|---|
| **SPU/SKU 两级** | 一 SPU 多规格 SKU（颜色/尺码），价格库存都在 SKU 粒度；单表拍平导致冗余与更新异常；行业标准 |
| **afterCommit 才失效** | 事务内失效→回滚后缓存已被清（脏窗口）；提交后删 + 1s 二删覆盖并发重建窗口 |
| **逻辑过期 30/物理 120** | 命中过期值返回旧值+异步刷新（可用性优先）；物理 TTL 是最后防线 |
| **双源库存** | sku.stock 是展示，`t_inventory`（可用/锁定/冻结）是扣减权威；不一致以库存服务为准（单一权威原则） |
| **下架写路径二次校验** | 加购/下单必须校验 `status=1`（L3-01/L3-04 已实证）；不信任读缓存（缓存可能旧） |
| **类目全量缓存** | 变更极低频+高频读（导航/筛选）；重建加锁/单飞；MAX_DEPTH=10 防循环引用栈溢出 |
| **本地无缓存** | 多实例一致性优先；Caffeine 试装后移除（27 章取舍）——热点靠 Redis 单主 ops 扛 |
| **空值占位教训** | content 草稿 NULL 占位 2min，发布只清列表没清详情 → 发布后 404 至占位过期；占位必须与失效同维护 |
| **Bloom 门控空值** | 海量不存在 ID 若直接缓存空值会打爆 Redis（每个 ID 一个空 Key）；Bloom 前置拦截后，只有 1% 误判请求才产生空值缓存（SpuService 注释原话） |

---

## 三、组件映射（回链 K 系列）

| 链路步骤 | 组件 | 回链 |
|---|---|---|
| Bloom/锁/计数 | Redis + Redisson（K02/K11） | K02（数据结构）+ K11（tryLock(0,300s) 语义） |
| 详情/搜索召回 | ES（K03） | K03（mapping/分词/召回策略） |
| 商品变更→索引 | Canal 同步（K10） | K10（binlog→MQ→ExternalGte） |
| 库存权威 | 库存服务账本（K06） | K06（TCC fence/分桶） |
| 读多扩容瓶颈 | Redis 单主 ops | K02（Sentinel/容量） |

---

## 四、拷打问题

1. **"SPU/SKU 为什么两级？"** 价格库存都在 SKU 粒度；详情聚合 SPU；加购下单按 SKU——单表拍平冗余且更新异常。
2. **"类目树更新怎么做？"** 改 DB → 删缓存 → 下次读重建（单飞防击穿）；Redis 挂了降级直查 DB（buildCategoryTree）。
3. **"上下架缓存怎么一致？"** afterCommit 失效（不是事务内）+ 1s 二删；写路径（加购/下单）二次校验 status，不信任读缓存。
4. **"sku.stock 和库存什么关系？"** 双源：展示 vs 权威；不一致以库存服务为准；展示字段可异步校准。
5. **"读多写少先崩哪？"** Redis 单主 ops（热点 SPU 详情）；策略：热点隔离 + 无本地缓存（一致性优先）。
6. **"空值缓存的坑？"** 占位与失效同维护；content 404 教训——发布流程必须清详情占位。

---

## 五、话术

> "商品用 SPU/SKU 两级：SPU 是标准化产品，SKU 是带规格价格的最小单元。类目树 parent_id 加 level 存，全量缓存在 Redis 2 小时，重建走单飞。上下架改状态后在事务提交后失效缓存，再 1 秒二次删覆盖并发重建窗口——事务内失效会在回滚时留脏。读侧 Bloom 防穿透、逻辑过期 30 分钟加物理 120 分钟、单飞防击穿、空值占位。SKU 上的 stock 只是展示，真实扣减在库存服务账本，不一致以库存为准。下架商品在加购下单写路径二次校验，不信任读缓存。"

---

## 六、回链索引

| 主题 | 文档 |
|---|---|
| 缓存四件套/一致性 | K02 + 27 章 |
| 搜索召回/索引同步 | K03 + K10 |
| 库存权威/分桶 | K06 + K02 |
| 锁语义 | K11 |
| 下单/购物车联动校验 | L3-01 + L3-04 |
| xhs 章节 | 57-商品域 + 27-商品多级缓存 |
