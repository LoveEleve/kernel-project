# P03-ZooKeeper D-02 — ZK 会话管理与 Watch 机制

> **定位**：P03 第 6 篇。三层齐：**原理 + 使用 + 源码**。
> **源码版本**：ZooKeeper **3.9.5**（`code/spring/zookeeper`）
> **质量标准**：源码清单 / 完整调用链 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：一个例子的两条线

**会话线**（客户端 A 建会话 + 临时节点 /lock）：
```
connect → processConnectRequest 协商 sessionTimeout → 创建 session（sessionId 含创建者 serverId）
心跳：客户端按 sessionTimeout/3 发 PING → touchSession 把 session 放进"新的过期时间桶"
断网超时：run 循环 poll 到期的桶 → setSessionClosing → expire → 删除该 session 的所有临时节点
```

**Watch 线**（客户端 B: `getData(/conf, watch=true)`）：
```
注册：DataTree.dataWatches.addWatch("/conf", watcher)
变更：其他客户端 setData("/conf") → DataTree 处理事务时 triggerWatch(NodeDataChanged)
通知：watcher 收到事件（一次性——已触发即移除）
续订：客户端重新 getData(watch=true)（或 Curator NodeCache 自动重注册）
```

**一句话**：会话 = **"以 tickTime 为粒度的过期桶 + 心跳刷新"**（批量过期、O(桶数)）；Watch = **"按 path 注册的一次性触发器"**（服务端不维护长状态，客户端负责续订）。

---

## 二、原理（心智模型）

### 2.1 Session 生命周期

```
NOT_CONNECTED → CONNECTING → CONNECTED → CLOSED/EXPIRED
建立：客户端带提议的 sessionTimeout → 服务端裁定（tickTime 的倍数区间内）
续命：心跳（任何请求也算）→ touchSession（移到新桶）
过期：桶到期 → 关闭会话（先写"关闭会话"事务 → 删除临时节点 → 通知 watchers）
sessionId：含创建者 serverId（高 8 位）——重连优先路由回同一 server（session 找回）
```

### 2.2 分桶（ExpiryQueue）——过期检测的核心设计

```
不使用"每分钟扫描全部 session"——而是按"过期时间"分桶：
  expiryMap: ConcurrentHashMap<expirationTime, Set<Session>>
  桶粒度 = tickTime（session 的过期时间向上取整到 tick 边界）
touchSession → 从旧桶移除 + 加入新桶（update 两步）
run 循环：getWaitTime（下一个桶的到期剩余时间）→ sleep → poll 到期桶 → 批量 expire
收益：过期检查 O(到期桶大小)，不做全表扫描；同到期时间的 session 批量处理
```

### 2.3 Watch：注册、触发、一次性

```
注册：DataTree 两个管理器——dataWatches（getData/exists 的 watch）+ childWatches（getChildren）
触发点（事务处理时）：
  createNode → NodeCreated（数据 watch + 父节点 child watch）
  deleteNode → NodeDeleted（数据 watch + 父节点 child watch）
  setData    → NodeDataChanged
  addChild   → NodeChildrenChanged
一次性：triggerWatch 后该 watcher 被移除（服务端不留长状态）；客户端重注册
优化版：WatchManagerOptimized 用"watcher→bit id + BitHashSet"位图压缩——
       同 path 大量 watcher（如全连接都 watch 根节点）内存大幅下降
```

### 2.4 会话与临时节点的关系（活性载体）

```
临时节点（EPHEMERAL）挂在 session 上：session 过期 → 该 session 所有临时节点被删 → 触发 watch
这就是"分布式锁/选主"的底层机制（D-03 展开）：
  锁 = 抢创建临时节点；持锁者崩溃 → 会话过期 → 节点删除 → 其他人收到 watch
```

---

## 三、使用（配置与注意事项）

```
sessionTimeout：客户端提议、服务端在 min/max 区间裁定（tickTime 倍数）；太小→误判过期，太大→故障发现慢
心跳：客户端自动（sessionTimeout/3 间隔 PING）
Watch 注意事项：① 一次性——收到事件后必须重新注册（或用 Curator 的 NodeCache/PathChildrenCache/
             TreeCache 自动重注册）；② 事件只带"发生了什么"不带新值（要读值再 get）；
            ③ watch 数量大（每连接每 path）有服务端内存成本——避免全表 watch
```

---

## 四、源码核心（调用链 + 行号）

### 4.1 会话创建与续命

```
ZooKeeperServer.processConnectRequest（ZooKeeperServer.java:1467）：协商 timeout → 创建会话
SessionTrackerImpl（server/SessionTrackerImpl.java）：
  sessionsById（:49）ConcurrentHashMap；createSession → sessionsById.putIfAbsent（:278）
  touchSession（:179）→ updateSessionExpiry → sessionExpiryQueue.update（:113 处的 ExpiryQueue 实例）
```

### 4.2 分桶过期链

```
ExpiryQueue（server/ExpiryQueue.java，泛型类）：
  expiryMap（:43，桶表）/ elemMap（:37，elem→到期时间反查）/ update（:84：同桶直接返回 null 快路径；否则移桶）/ getWaitTime（:123）/ poll（:137）
SessionTrackerImpl.run（:158）：
  getWaitTime → sleep → poll 到期桶 → setSessionClosing（:225）→ expirer.expire(s)
ZooKeeperServer.expire（:728/737）：关闭会话 → 删除临时节点（走事务）→ 通知 watcher
```

### 4.3 Watch 注册与触发

```
注册：DataTree.dataWatches/childWatches（DataTree.java:107/109）
  → addWatch（DataTree:683/685/698/708）→ WatchManager.addWatch（watch/WatchManager.java:70/75）
触发：DataTree 处理事务时——NodeCreated（:519-520）/ NodeDeleted（:621-623）/ NodeDataChanged（:658）
  → WatchManager.triggerWatch（:135/140，返回 WatcherOrBitSet 供"父节点去重"）
优化：WatchManagerOptimized（watch/WatchManagerOptimized.java:57，bit 位图）
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **过期按桶（ExpiryQueue）** | 全量扫描 session 表 | O(桶) vs O(会话数)；同桶批量过期——把"时间"变成索引 |
| **touchSession 移桶** | 原地改时间戳 | 桶是"到期批"，移桶保持批语义（改时间戳会破坏批量过期） |
| **Watch 一次性** | 持续 watch | 服务端不为每个 watch 维护长生命周期（状态轻）；客户端续订=责任转移（防服务端状态爆炸） |
| **Watch 事件不带数据** | 事件附带新值 | 事件轻（广播成本低）；客户端按需回读（一致性由 zxid 约束） |
| **临时节点绑 session** | 显式心跳接口 | 会话本身即活性检测——崩溃/断网自动清理（锁/选主原语的基础） |
| **位图优化（Optimized）** | 每 path 一个 Set<Watcher> | 大基数 watcher 场景（MB 级内存）→ bit 位压缩 |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **Session 超时 → 临时节点/锁全丢** | 网络闪断、GC 停顿——sessionTimeout 调大 + 业务续约/幂等 |
| 2 | **Watch 丢失** | 一次性事件处理后忘记重注册——Curator 缓存类自动注册 |
| 3 | **Watch 风暴** | 大量客户端 watch 同一热点 path——服务端通知成本高；避免全局 watch |
| 4 | **sessionTimeout 太小** | 心跳来不及 → 误过期（集群抖动）——按 GC/网络 P99 调 |
| 5 | **sessionTimeout 太大** | 故障发现慢（锁释放延迟）——按业务容忍度平衡 |
| 6 | **事件到达但读到旧值** | 事件与读之间又有变更——以 zxid 语义理解（事件只是"通知有变更"） |

---

## 七、面试 Q&A（含面试官意图）

**Q1："ZK 会话过期怎么检测？为什么快？"**（意图：分桶设计）
ExpiryQueue 按过期时间分桶（粒度 tickTime）；touchSession 移桶；循环只 poll 到期桶——O(桶) 而非全表扫描。

**Q2："临时节点什么时候删？"**（意图：会话与节点绑定）
会话过期或主动关闭时，删除该会话所有临时节点并触发 watch——分布式锁的活性基础。

**Q3："Watch 为什么是一次性的？"**（意图：设计取舍）
服务端状态轻（不为 watch 维护长生命周期）+ 客户端续订可控制节奏；代价是可能丢事件（未及时重注册）。

**Q4："Watch 能保证收到每次变更吗？"**（意图：边界）
一次性触发 + 客户端重注册窗口内可能丢中间变更——正确用法是"收到事件→重读数据（拉模式兜底）"。

**Q5："ZK 为什么不适合做注册中心？"**（意图：CP 局限）
CP 语义：分区时拒绝写（保一致）——注册中心要 AP（可用优先）；另有 watch 一次性/容量问题（Nacos 的连接模型对比）。

---

## 八、章节依赖

- **上游**：D-01（会话创建/过期要走 ZAB 广播；watch 触发在事务处理时）
- **下游**：D-03（分布式锁/选主/配置中心都用"临时节点+watch+zxid"三件套）
- **横链**：K04（Nacos 连接模型 vs ZK 会话——两种活性检测范式）

---

## 九、验证锚点（ZooKeeper 3.9.5）

| 声明 | 位置 |
|---|---|
| SessionTrackerImpl | `server/SessionTrackerImpl.java:45`（类）/`:49`（sessionsById）/`:113`（ExpiryQueue 实例化） |
| sessionId 编码 | `SessionTrackerImpl.java:98-101`（高 8 位=serverId：`id << 56`） |
| 客户端心跳节奏 | `ClientCnxn.java:1263`（timeToNextPing=readTimeout/2）/:1430（readTimeout=sessionTimeout×2/3） |
| touchSession | `SessionTrackerImpl.java:179`（含 updateSessionExpiry） |
| 过期循环 | `SessionTrackerImpl.java:158`（run → poll → expire）/:225（setSessionClosing）/:308（checkSession） |
| ExpiryQueue 分桶 | `server/ExpiryQueue.java:35`（类）/`:37`（elemMap）/`:43`（expiryMap 桶表）/`:45`（nextExpirationTime）/`:84`（update）/`:123`（getWaitTime）/`:137`（poll） |
| 会话创建/过期落地 | `server/ZooKeeperServer.java:1467`（processConnectRequest）/`:856`（createSessionTracker）/`:707`（closeSession）/`:728/737`（expire） |
| Watch 注册 | `server/DataTree.java:107/109`（两个管理器）/`:683/685/698/708`（addWatch）→ `watch/WatchManager.java:70/75` |
| Watch 触发 | `DataTree.java:519-520`（NodeCreated）/`:621-623`（NodeDeleted）/`:658`（NodeDataChanged）→ `WatchManager.java:135/140`（triggerWatch） |
| 位图优化 | `watch/WatchManagerOptimized.java:57`（bit 映射）/`:190-197`（dead watcher 批量清） |
