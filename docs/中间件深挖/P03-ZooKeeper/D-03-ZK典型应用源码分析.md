# P03-ZooKeeper D-03 — ZK 典型应用源码分析（分布式锁/选主/配置中心）

> **定位**：P03 第 7 篇（ZooKeeper 线收尾）。三层齐：**原理 + 使用 + 源码**。
> **源码版本**：Curator **5.8.0**（`code/spring/curator`）+ ZooKeeper 3.9.5
> **质量标准**：源码清单 / 完整调用链 / 设计哲学 / 生产陷阱 / 面试 Q&A / 章节依赖

---

## 一、它干了什么：三个场景一页纸

```
① 分布式锁：10 个客户端抢 /lock
   公平实现：各自创建 /lock/lock-0000000001 ~ 10（临时顺序节点）→ 序号最小者得锁
   其余 watch"自己的前一个"节点——前一个删除（释放）时才有人醒来 → 依次接力
② 选主：LeaderLatch 就是"只放一个的锁"——序号最小者为 leader；掉了（会话过期/节点删）下一个接棒
③ 配置中心：持久节点 /config 存数据 + 客户端 getData(watch=true)；变更 → watch 触发 → 回读新值
```

**一句话**：ZK 应用模式 = **"临时节点（活性）+ 顺序节点（排队）+ watch（通知）"三件套**的组合——锁/选主/配置都是这三件的不同拼装。

---

## 二、原理（心智模型）

### 2.1 三件套原语

| 原语 | 语义 | 应用 |
|---|---|---|
| **临时节点（EPHEMERAL）** | 会话活着才存在；崩溃/断网自动删 | 活性检测：锁持有者、服务实例注册 |
| **顺序节点（SEQUENTIAL）** | 创建时自动附加单调序号 | 排队：公平锁、选主（序号即顺位） |
| **watch** | 一次性变更通知 | 唤醒等待者；配置变更推送落地点 |

### 2.2 分布式锁：非公平 vs 公平

```
非公平（抢同一个临时节点）：所有客户端 create("/lock", EPHEMERAL)——失败者 watch /lock
  → 释放时全部被唤醒再抢（惊群/羊群效应：N 个客户端同时起跑，只有一个赢）
公平（临时顺序节点排队）：create("/lock/lock-", EPHEMERAL_SEQUENTIAL)
  → 判断自己是否最小（getsTheLock）；不是 → watch 前一个序号节点
  → 前一个删除 → 只有我醒（无惊群，天然 FIFO）
```

### 2.3 选主：LeaderLatch / LeaderSelector

```
LeaderLatch：与公平锁同构——"序号最小 = leader"；watch 前一个
  领导权丢失场景：会话过期/ConnectionStateListener 收到 SUSPENDED（网络分区猜疑）→ 主动放弃领导
LeaderSelector：拿到领导后执行 takeLeadership()，方法返回后自动重新排队（autoRequeue 可开）
```

### 2.4 配置中心：watch + 回读

```
写：setData("/config", data, version)（version 做乐观锁——防并发覆盖）
读+监听：getData(watch=true)；变更事件到达 → 重新 getData 取新值（事件不带值）
局限：watch 一次性 + ZK 写是 CP（分区时配置中心不可写）——大规模配置推送不如 Nacos 长连接
```

---

## 三、使用（Curator 与 xhs 对照）

```
Curator 5.8.0（ZK 客户端的"事实标准"）：
  锁：InterProcessMutex（可重入）/ InterProcessSemaphoreMutex / InterProcessReadWriteLock
  选主：LeaderLatch / LeaderSelector
  缓存：NodeCache（单节点）/ PathChildrenCache / TreeCache（自动维护 watch——解决一次性问题）
  连接治理：ConnectionStateListener（SUSPENDED/LOST 处理——网络分区时的领导权/锁决策）

xhs 对照（为什么不用 ZK）：
  锁 → Redisson（Redis 效率锁 + DB 兜底，K02/K11）——ZK 锁是 CP（分区不可用），xhs 选"锁只降冲突"
  配置 → Nacos（长连接推送 + 灰度/回滚，K04）——ZK 配置中心在规模/体验上弱
  选主/协调 → Nacos 临时实例（Distro/连接模型，K04）覆盖了注册+活性检测
```

---

## 四、源码核心（调用链 + 行号）

### 4.1 分布式锁链（InterProcessMutex → LockInternals）

```
InterProcessMutex.acquire（InterProcessMutex.java:82）→ internalLock（:197）
  → LockInternals.attemptLock（LockInternals.java:171）
       ① 创建排队节点：StandardLockInternalsDriver.createsTheLock（:49）
          → CreateMode.EPHEMERAL_SEQUENTIAL（:51；maxLeases 特殊场景 EPHEMERAL:55）
  → LockInternals.internalLockLoop（:223）循环：
       getSortedChildren（:159，按序号排序全部排队节点）
       → getsTheLock（StandardLockInternalsDriver.java:33）：
            ourIndex < maxLeases ? 我最小=得锁
            : pathToWatch = children[ourIndex - maxLeases]（:39——watch 前一个）
       → 未得锁：watch 前一个节点（:244 getData+usingWatcher）→ wait → 被唤醒后重试循环
release（InterProcessMutex.java:120）：删除自己的节点
```

### 4.2 选主链（LeaderLatch）

```
start（LeaderLatch.java:150）→ 创建 EPHEMERAL_SEQUENTIAL 节点（:519）
checkLeadership（:544）：getSortedChildren → 自己最小 = leader（其余 watch 前一个）
ConnectionStateListener（:77）：SUSPENDED/LOST → 放弃领导（防"旧主以为还是主"）
close（:173）：删节点 → 下一个接棒
LeaderSelector：start（:214）/ takeLeadership 执行完重排队 / autoRequeue（:182）
```

---

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **公平锁=顺序节点+watch 前一个** | 抢同一节点+全体 watch | 防惊群：只唤醒"下一个"，与 ZK 的顺序节点天然 FIFO 契合 |
| **锁/选主都用临时节点** | 持久节点+显式释放协议 | 崩溃/网络断自动释放（会话即租约）——无需额外故障检测 |
| **watch 前一个而不是第一个** | watch 最小节点 | 确定性交接（避免全体竞争）；一个节点释放只唤醒一个 |
| **LeaderLatch 引入 SUSPENDED 主动弃权** | 只信会话超时 | 网络分区时"猜疑自己失联"→ 主动放弃（宁可双主窗口缩短）——分布式协调的经典保守策略 |
| **配置中心用 watch+回读** | 事件推值 | 事件轻（服务端不缓存值）；读一致性由 zxid 保证 |
| **Curator 作为标配** | 手写 ZK 原语 | 一次性 watch/断线重连/状态机——手写必然踩坑（羊群/丢 watch/连接状态） |

---

## 六、生产陷阱

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **非公平锁惊群** | 全体 watch 同一节点——换公平锁（顺序节点+watch 前一个）或 Curator 现成实现 |
| 2 | **会话超时锁丢失** | 持锁者 GC/网络抖动——会话租约是"正确性交付给下游"的前提（xhs 的 DB 兜底同理） |
| 3 | **watch 一次性忘续** | 事件后未重注册——用 Curator 缓存类（自动重注册） |
| 4 | **SUSPENDED 未处理** | 网络分区时旧主继续写——Listener 中主动放弃领导/停写 |
| 5 | **配置数量/节点数膨胀** | ZK 适合"少量强一致配置"——海量配置/推送用 Nacos 等 |
| 6 | **锁超时与羊群叠加** | 大量客户端+短任务——排队节点数暴涨；评估锁粒度 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："ZK 分布式锁怎么实现？为什么公平？"**（意图：三件套组合）
临时顺序节点排队 + 序号最小得锁 + watch 前一个——FIFO 公平且无惊群。

**Q2："ZK 锁和 Redis 锁怎么选？"**（意图：CP/AP 与用途）
ZK 锁=CP（分区时锁服务不可用，保安全）；Redis 锁=AP（效率锁）。真正确性要么用 ZK/etcd 做 fencing 语义，要么下游唯一约束兜底（xhs 路线）。

**Q3："选主和锁什么关系？"**（意图：模式迁移）
选主=只放一个的锁（LeaderLatch 序号最小者）；关键差异在"领导权丢失的处理"（SUSPENDED 弃权/重排队）。

**Q4："ZK 为什么不适合当配置中心？"**（意图：局限性）
watch 一次性 + CP（分区不可写）+ 无推送规模能力——适合少量强一致元数据；大规模动态配置用 Nacos（长连接+推送+灰度）。

**Q5："羊群效应怎么避免？"**（意图：协议细节）
不用"抢同一节点+全体 watch"，用顺序节点+watch 前一个——释放只唤醒下一个。

---

## 八、章节依赖

- **上游**：D-01（ZAB——锁操作都走广播）；D-02（临时节点/会话/watch——本篇的原语）
- **下游**：P03-对比（Raft vs ZAB 应用面）；K02（Redis 锁对照）
- **横链**：K04（Nacos 注册/配置——ZK 的功能对手）

---

## 九、验证锚点（Curator 5.8.0 / ZK 3.9.5）

| 声明 | 位置 |
|---|---|
| InterProcessMutex acquire/release | `curator-recipes/.../locks/InterProcessMutex.java:82/99/120/197` |
| 建排队节点 | `locks/StandardLockInternalsDriver.java:49/51`（EPHEMERAL_SEQUENTIAL） |
| 公平判定与 watch 前一个 | `StandardLockInternalsDriver.java:33/39`（getsTheLock/pathToWatch） |
| 等待循环 | `locks/LockInternals.java:171`（attemptLock）/`:223`（internalLockLoop）/`:244`（watch previous）/`:159`（getSortedChildren） |
| LeaderLatch | `recipes/leader/LeaderLatch.java:150`（start）/`:519`（节点创建）/`:544`（checkLeadership）/`:77`（ConnectionStateListener）/`:173`（close） |
| LeaderSelector | `recipes/leader/LeaderSelector.java:214/182`（start/autoRequeue） |
