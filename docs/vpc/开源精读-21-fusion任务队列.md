# 开源精读 21 · vpc-fusion（下）——"disorder"不是乱序：任务队列的可靠性改造

> 课次：S2b 第四课（vpc-fusion-task-queue 768 行 + bugfix/disorder 总览 215 行）
> 坐标：第 20 课场景 3/4/5（任务卡死、`Put(nil)` 复活、初始化死循环）在本课迎来**正面拆解与修复叙事**——这是系统线的第一堂"事故-修复"课。
> 纪律：断言落材料文本；材料间口径不一致处**如实并记**。

---

## 0. 术语速查

| 术语 | 含义 |
|---|---|
| **发布-订阅任务队列** | Leader 只"发牌"（Redis 队列），Worker 们"抢牌"并行处理——**Leader 不自己干活** |
| **4 种 Key** | `task:queue`（List）/ `task:priority`（ZSet）/ `task:data`（String+TTL）/ `task:processing`（ZSet，score=lease 到期时间戳） |
| **Hash Tag** | Key 里的 `{snapshot}`——**用 `{}` 内内容算 slot**，让队列相关 Key 落同一槽，Lua 原子操作不报 `CROSSSLOT` |
| **Lease（租约）** | Worker 领任务时设 30s 过期（写进 processing 的 score）；到期未续 = 任务可被接管 |
| **Recovery** | 1 分钟扫描 processing，`ZRANGEBYSCORE 0 now` 捞出过期任务 → `requeueTask`（RPUSH + PSETEX + ZREM 三步） |
| **旧 FetchTask 三步** | `BLPop → GET → ZADD`——**三步分离 = 三个致命问题的温床** |
| **"disorder"** | ⚠️ **不是"消息乱序"**——是"多种可靠性 bug 导致的**状态紊乱**"集合（材料明证：代码与 commit 中从未出现该词） |

## ① 它干了什么——一个 snapshot 任务的"一生"（和一个"死后复活"）

**正常一生**（新实现）：Leader 扫描变更 → `publishTaskScript` 原子发布（`RPUSH queue + PSETEX data`）→ Worker `claimFromQueueScript` 原子领取（检查队头 → `LPOP + PSETEX + ZADD processing`，lease=now+30s）→ 干活（读 MySQL → 合并 → 写快照）→ `finishTaskScript`（`PSETEX` 短 TTL + `ZREM` 出 processing）。

**死后复活**（材料 §4.4 完整流程）：Worker A 领了 `task-001` **OOM 崩溃** → Heartbeat 停止 → Lease 不再续租 → **30s 后过期** → Recovery 扫描（每分钟）捞到 → `status=timeout`、`RetryCount++` → **`requeueTask`：`RPUSH queue + PSETEX data + ZREM processing`** → Worker B 领取、正常处理。

**一句话**：这套队列要保证的事是——**"任何 Worker 都可以随时死，任务一个不丢、不重复、不卡死"**（旧实现三条全犯，见跳 4）。

**规模为什么要队列**（§1.1 的算术）：广州 200 万 VPC × 每快照 100ms = **200000 秒 ≈ 55 小时**——串行不可行；而且是**四重压爆**：压爆 MySQL（200 万次查询）、压爆 LevelDB（200 万次随机写）、压爆内存（200 万个 protobuf）、压爆单点（无法利用多核）。解法：Leader 只发任务，**多 Worker 并行领**。

## ② 链路走查（材料行号）

### 跳 1：Leader-Worker 架构——四顶王冠的"施工细节"（task-queue §二）

**4 个独立选举域**（§2.1 表，含 Worker 并发与超时）：

| 选举域 | 模块 | Leader 干什么 | Worker 并发 | Worker 超时 |
|---|---|---|---|---|
| `init_zone` | initconfig | 扫描 ConfigMeta 发布初始化任务 | **20** | 5 分钟 |
| `snapshot-build-leader` | snapshot | 扫描变更发布快照任务 | **20** | 2 分钟 |
| `reconcile` | reconcile | 发布对账任务 | **3** | 10 分钟 |
| `clean-config-leader` | cleanconfig | 扫描回收发布清理任务 | **10** | 10 分钟 |

（message/binlog 不需要 Leader——每个节点各自消费 Kafka；vpcapi 也不需要。**并发数即工作特点**：快照/初始化任务多（20），对账任务重（3）。）

**Leader 怎么选**（§2.2）：**Redis SET NX EX**——`electionKey = "fusion:leader:" + role`，`myID = hostname + ":" + pid`，TTL 15s、每 5s 续约——材料解释"**比例 3:1**（续约间隔:TTL）：即使一次续约失败还有 10s 重试"；**不用 ZK LeaderLatch：Gen3 完全去 ZK**。

> ⚠️ **材料口径不一（如实并记）**：第 20 课材料（position §3.7）给的 Key 是 `leader:election:snapshot-build-leader`（前缀来自 `leader_vote_dao_impl.go:15`）；本课材料（task-queue §2.2）给的样例是 `fusion:leader:snapshot-build-leader`。**两处前缀不同**——无源码在盘无法裁决，如实并列，存疑待考。

**Worker 怎么工作**（§2.3）：**所有节点都是 Worker**（不管是不是 Leader）——循环"领任务 → 处理 → 完成"，没任务 sleep 200ms 再试。

### 跳 2：Redis 里的 4 种 Key 与 Hash Tag（task-queue §三）

```
task:queue:{queueName}          # 普通队列（List，RPUSH/LPOP）
task:priority:{queueName}       # 优先级队列（ZSet，ZADD/ZREVRANGE）
task:data:{queueName}:taskId    # 任务数据（String，PSETEX 带 24h TTL）
task:processing:{queueName}     # 处理中集合（ZSet，score=leaseExpireAt）
```

**Hash Tag 的作用**（§3.3）：Redis Cluster `slot = CRC16(key) % 16384`，**key 里有 `{xxx}` 则用 `{}` 内内容算 slot**——`{snapshot}` 让 queue/data/processing 三个 Key **落同一 slot**："Lua 脚本要同时操作 queue + data + processing 三个 Key，Redis Cluster 要求**同一 slot**，否则报 `CROSSSLOT`"。**为什么不用 `{vpcId}`**：同一队列含多个 VPC 的任务——**按 queueName 分 slot 确保同一队列的原子性，不同队列可在不同 slot 并行**——"兼顾原子性和并行性"。

**旧格式 vs 新格式**（§3.4）：旧 Key 无 Hash Tag；**Legacy 兼容策略**：读操作同时查新+旧 key，写操作只用新格式——"等旧任务全部消费完（24h TTL 后），下个版本删除 legacy 代码"（**在线双写迁移的标准姿势**）。

### 跳 3：旧实现的三致命问题（task-queue §五）

旧 `FetchTask`（材料原注："**伪 Python 描述 Go 逻辑**"）三步分离：

```
BLPop("task:queue:"+queue, 5s) → GET("task:data:"+queue+":"+taskID) → ZADD("task:processing:"+queue, now, taskID)
```

**三个致命问题**（§5.2）：

- **问题 1 非原子 → 任务丢失**：`BLPop` 取出 task-001（**任务已离开队列**）→ `GET data` 返回 nil（**data 被 TTL 清掉了**）→ "任务既不在队列里，也没有 data → **永远丢失**" → VPC 快照永不更新 → 新 ENI 不生效 → **VM 流量不通**；
- **问题 2 非原子 → 重复消费**：`BLPop + GET + ZADD` 三步之间有窗口——Worker A 的 ZADD 还没执行时，**Recovery 误判超时重新入队** → Worker B 领到同一任务 → **两个 Worker 同时处理**；
- **问题 3 无 Lease → 卡死 processing**：Worker 崩溃（OOM、kill -9）→ 任务卡在 processing 集合、**没有过期时间** → 只能等 `RecoverTimeoutTasks` 定时扫描（1 分钟间隔）→ 该 VPC 的配置变更**至少延迟 1 分钟**。

（回忆第 20 课场景 3：任务丢失/重复处理/卡死——**三条病根就是这三条**。）

### 跳 4：新实现——8 个 Lua 脚本的原子化（task-queue §六）

| 脚本 | 作用 | 原子操作 |
|---|---|---|
| `publishTaskScript` | 发布任务 | RPUSH queue + PSETEX data |
| **`claimFromQueueScript`** | 从普通队列领取 | **LINDEX + LPOP + PSETEX + ZADD** |
| `discardQueueHeadScript` | 丢弃无效队头 | LPOP（data 已过期） |
| `claimFromPriorityScript` | 从优先级队列领取 | ZREVRANGE + ZREM + PSETEX + ZADD |
| `discardPriorityHeadScript` | 丢弃无效优先级头 | ZREM |
| `saveTaskToProcessingScript` | 保存处理状态 | PSETEX + ZADD |
| `finishTaskScript` | 完成任务 | PSETEX（短 TTL）+ ZREM |
| `requeueTaskScript` | 重入队 | PSETEX + RPUSH/ZADD + ZREM |

**`claimFromQueueScript` 第 1 步**（最核心脚本）——**先看再拿**：

```lua
-- 第 1 步：检查队头是不是我看到的 taskID
local head = redis.call('LINDEX', KEYS[1], 0)
if not head or head ~= ARGV[1] then
    return 0  -- 队头变了，别人先拿了
end
-- （后续：LPOP + PSETEX data + ZADD processing，全部在同一脚本内原子完成）
```

**为什么不用 BLPop**（§6.4，材料三条）：① `BLPop` 只能监听一个 key——**新旧 Key 兼容需要轮询两个**；② **`BLPop` 会删队头**——claim 失败（被别人抢了）任务已从队列删掉；③ **阻塞期间无法做其他事**（检查 Lease、健康检查）。替代：`claimTaskFromQueue` 带 **3 次重试** + 没任务 `sleep 200ms` 再试。

**为什么 Lua 是这台机器的正解**（§6.3 对比两连）：① 对比 **MULTI/EXEC**——后者是乐观锁（WATCH），并发下大量 CAS 失败重试，且**不支持条件逻辑（if-else）**；Lua 服务端原子执行、**可有条件逻辑**——"先看再拿"（LINDEX 判断）正是靠这一点才写得出来；② 对比**分布式锁**（如 RedLock）——"获取锁 → 操作 → 释放锁"三步性能差；**Lua 一步到位，不需要锁**。

### 跳 5：Lease + Heartbeat + Recovery——"你还在吗"三段式（task-queue §七/§八）

- **Lease**（§7.1）：领任务时 `LeaseExpireAt = now + 30s`，写进 processing 的 **score = leaseExpireAt 毫秒时间戳**——"**30 秒内必须完成或续租**，否则任务被认为'过期'，其他 Worker 可以接管"；
- **Heartbeat**（§7.2）：**后台 goroutine 每 10 秒续租一次**（`HeartbeatInterval // 10s = LeaseTTL/3`）——**续租失败即"自杀"**：`RenewTaskLease` 返回 `!renewed`（租约被别人拿了）→ **`executeCancel()` 取消任务执行 + `leaseLostCh` 通知主流程**——"**租约被夺 = 立刻停止执行**"（防脑裂）；
- **Recovery**（§4.4 全流程）：每分钟扫描 `ZRANGEBYSCORE processing 0 now` 捞出过期任务 → `status=timeout`、`RetryCount++` → `requeueTask`（RPUSH + PSETEX + ZREM）→ 下一个 Worker 接管；
- **为什么是 1/3**（§7.5 专节）：T+0 领（Lease 到 T+30）→ T+10/T+20 两次续租各刷 10s——**"即使一次续租失败还有 2 次机会（20s）。不会因为一次网络抖动就 Lease 过期"**；**为什么不用 ZK Session 做租约**（§7.6）："Redis ZSet 的 score **天然支持按时间范围查询（ZRANGEBYSCORE）**——比 ZK 的 ephemeral node 更灵活"；
- **⭐ Ownership 校验**（§八）：**防止旧 Worker"复活"后越权提交**——`ensureTaskOwnership`：① 状态必须是 `processing`；② **`WorkerID` 必须匹配**（"复活"的旧 Worker 想提交？先把身份对上再说）——**分布式系统的围栏（fencing）模式**。

**三段式一句话**：**Lease 让"死"可判定，Heartbeat 让"活"可续期（失败即让位），Recovery 让"死后"有归宿**；再加 **Ownership 让"诈尸"无效**。

### 跳 6："disorder"的真义——一场"状态紊乱"的全面战争（bugfix_disorder §二/§三）

⚠️ **彩蛋级纠偏**（§二原文）：分支名 "disorder" **不是字面的"消息乱序"**——"**代码和 commit message 中从未出现 'disorder' 或 '乱序'**"。它指的是**分布式系统多种可靠性 bug 导致的"状态紊乱"集合**，恰好五类：

1. 任务丢失（BLPop 取出后 data 被 TTL 清掉）；
2. 任务被多 Worker 重复消费（BLPop + claim 非原子）；
3. 任务卡死在 processing（无 lease，Worker 崩溃后永不恢复）；
4. **快照合并污染**（`DeleteConfig` 用 `Put(nil)` 导致配置"复活"——第 20 课场景 4）；
5. **初始化死循环**（resourceObj OBJ 检查误应用到自身——第 20 课场景 5）。

**50 个 commit 按主题分组**（§三全表，节选）：

| 组 | commit 数 | 主题 | 改动量 |
|---|---|---|---|
| **A. 分布式任务可靠性（核心）** | 5 | **Lease + Heartbeat + Lua 原子操作 + 限频** | **~2000 行** |
| B. 快照合并 bug | **1** | `DeleteConfig` `Put(nil)`→`Delete` | **28 文件** |
| C. 初始化死循环 | 3 | resourceObj OBJ 检查误应用 | — |
| D. 运维接口/UI | 7 | 14 个接口 + Web UI + 分表分页 | ~1600 行 |
| E. watchdog | 2 | **独立进程 + systemd 两层守护** | — |
| F. 配置加载/启动 | 5 | 全局配置 continue bug + api ready | — |
| G. 资源对象缓存 | 2 | ResourceObj 走 Redis 缓存 | 22 文件 |
| H. 文档/测试 | 5 | STRUCTURE.md + **2900 行测试** | ~1800 行 |

**读法**：A 组是"主线修复"（2000 行 / 5 commit）；B 组是"**1 个 commit 修 28 个文件**"的机械替换（但修的是安全生产事故）；H 组 2900 行测试——**修完之后把测试补到比修复还重**。

## ③ 设计推导（问题树）

**Q1：新 claim 的本质是什么？**
旧三步 = 检查+取出+标记的**时间缝隙**；新脚本把它们压成一个**原子"声明"**——且第一步 **LINDEX 先看再拿**（失败可退回，不是 BLPop 的"先删再看"）。**claim 就是一个 CAS**：乐观检查（队头还是它吗）+ 原子提交（LPOP+PSETEX+ZADD）。同族：第 19 课 `reconnecting.CAS`、Session 状态机 CAS——**并发安全的手法在本项目反复出手：CAS（本处）、租约（Lease）、围栏（§八 Ownership 校验——防旧 Worker"复活"越权提交）、幂等（第 20 课 §3.7 面试追问：binlog 模块用 version + scopeId 去重）**。

**Q2：Hash Tag 粒度为什么是"队列"？**
`{queueName}` 而非 `{vpcId}`——因为 **Lua 原子操作的边界就是队列**（queue+data+processing 三键必须同槽）；而不同队列之间**无所谓顺序、正需要并行**。**锁的粒度决定并行度**：同队列串行（原子）、跨队列并行。（同族：第 19 课 hashSessionID 保"会话内序"、第 20 课 shard 保"分片内聚"——**"按有意义的边界分片"在本项目已第 N 次出手**。）

**Q3：任务 Lease（30s）与选举租约（TTL 15s）为什么不等长？**
选举的租约短（15s）：Leader 挂了**要尽快有继任**（全局只有一顶王冠）；任务 Lease 长（30s）：**单任务失败接管不急**，给 Worker 正常处理留出窗口（快照构建秒级~分钟级），频繁误判接管反而制造重复处理（材料 §7.5 专节 + 时间线解释 **HeartbeatInterval = LeaseTTL/3**：一次网络抖动不至于丢租）。更完整的一对设计：**续租失败即自杀**（§7.2 `executeCancel`）+"**诈尸**由 Ownership 校验拦截"（§八）——**租约失效的动作 = 停止 + 让位 + 围栏**，不是继续硬干。**租约时长 = 误判成本 × 接管延迟的平衡点**。

**Q4："disorder"的命名教训？**
分支名与语义脱节——**可靠性的问题空间不是"一种病"，而是"一组综合征"**。五类 bug 的共同根因画像清晰可辨：**原子性缺口**（问题 1/2）、**租约缺失**（问题 3）、**自指检查错误**（死循环）。**"状态紊乱"三根因：缺口、缺失、自指**——这也是审查任何任务队列时可以照抄的检查清单。

**Q5：修复的分量与价值为什么不成正比？**
A 组 2000 行（任务队列重构）是"主线工程"；B 组 **1 个 commit 改 28 文件**（`Put(nil)`→`Delete`）只是一次机械替换——但后者修的是"**已删路由复活 → 流量发到已销毁母机 → 安全风险**"的**安全生产事故**。**修 bug 的分量≠价值：破坏性的 bug 往往只需要一行正确**。

## ④ 对照卡：旧 FetchTask vs 新 Lua 原子 claim

| 维度 | 旧（BLPop 三步） | 新（Lua 原子 claim） |
|---|---|---|
| 领取动作 | `BLPop → GET → ZADD`（三步有缝） | **`LINDEX 检查 + LPOP/PSETEX/ZADD`（一脚本）** |
| 任务丢失 | 可能（GET nil → 永久丢） | 消除 |
| 重复消费 | 存在（ZADD 前窗口 + Recovery 误判） | 消除 |
| 崩溃接管 | 无租约，等 1 分钟扫描 | **30s Lease + 每分钟 Recovery** |
| 新旧 Key 兼容 | 不兼容（BLPop 单 key） | Hash Tag + **读双查/写只新** |

## ⑤ 验收题

1. "BLPop 三步"的三个致命问题各是什么？为什么"先删再看"（BLPop）不如"先看再拿"（LINDEX）？
2. Hash Tag 为什么按 `queueName` 而不是 `vpcId` 分槽？"锁的粒度决定并行度"在这里怎么体现？
3. 任务 Lease 30s 与选举 TTL 15s 的不等长，各自在优化什么？如果都改成 5s 会分别出什么事？
4. 五类 disorder 的共同根因画像（原子性缺口/租约缺失/自指错误）——用这个清单检查你见过的任意一个任务队列，能找出几类？
5. "1 个 commit 修 28 个文件"的 B 组为什么比 2000 行的 A 组更惊险？"修正一行 = 消除一类安全事故"给你的启示是什么？
6. 旧 Key 迁移的"读双查、写只新、TTL 退役"策略，为什么比"停机切换"和"双写双读"都好？

---

*本课材料行号清单（供追问对质）：`task-queue` §1.1（55 小时算术 + 四重压爆）/ §1.2（发布-订阅）/ §2.1（4 选举域表：20/20/3/10 + message/vpcapi 无 Leader）/ §2.2（SET NX EX + "fusion:leader:"+role + TTL 15s 续约 5s + 3:1 + "Gen3 完全去 ZK"）/ §2.3（所有节点都是 Worker）/ §3.1（4 种 Key 结构）/ §3.2（snapshot 队列三任务例 + 24h TTL）/ §3.3（Hash Tag：CRC16 16384 / CROSSSLOT / 为什么不用 {vpcId}）/ §3.4（旧 vs 新格式 + Legacy 读双查/写只新/24h 退役）/ §4.4（Worker A OOM → 30s 过期 → Recovery 每分钟 ZRANGEBYSCORE → requeueTask 三步）/ §5.1（旧 FetchTask"伪 Python 描述 Go 逻辑"）/ §5.2（三致命问题全文）/ §6.1（8 个 Lua 脚本表）/ §6.2（claimFromQueueScript 第 1 步 LINDEX 比对 + return 0）/ §6.4（不用 BLPop 三理由 + 3 次重试 + 200ms）/ §6.5（FetchTask 从 BLPop 改为 LIndex + 轮询——标题级）/ §7.1（Lease 30s + score=leaseExpireAt UnixMilli）/ §7.2（10s = LeaseTTL/3 + RenewTaskLease + 失败 executeCancel + leaseLostCh）/ §7.3（Recovery 扫描——标题级）/ §7.4（三段式协作时间线）/ §7.5（为什么 1/3 专节 + T+10/T+20/T+30 时间线）/ §7.6（不用 ZK Session：ZRANGEBYSCORE 时间范围 vs ephemeral node）/ §八（Ownership 校验：status 必须 processing + WorkerID 必须匹配）/ §九（任务限频：9.1 为什么 / 9.2 实现 / 9.3 为什么 atomic+sleep——本课取标题级）；`bugfix_disorder` §二（disorder 真义五类 + "从未出现该词"）/ §三（50 commit A-J 分组表：A 5/~2000 行、B 1/28 文件、D ~1600、E watchdog+systemd、H 2900 行测试）。*

*审查记录（首轮深审）：**0 修正 + 4 处硬锚升级**——引文逐字全对（BLPop 三理由/55 小时/三问题/Hash Tag CROSSSLOT/disorder"从未出现该词"/50 commit 表），但**三处标题级转述经回源补读全面展开**：① §7.2 心跳实为"**每 10s 续租 + 续租失败 `executeCancel` 自杀**"（原转述过简）；② §6.3 "为什么 Lua"实有**两连对比**（MULTI/EXEC 无条件逻辑 / RedLock 三步性能差）；③ **补入漏读三节**：§7.5（HeartbeatInterval=LeaseTTL/3 专节）、§7.6（不用 ZK Session：ZRANGEBYSCORE vs ephemeral）、**§八 Ownership 校验（fencing 围栏模式，新入库）**；§九限频列标题级。Q1/Q3 相应增强。教训归档：**"标题级转述"是漏读的温床——凡转述必回源**（与第 18 课"二手转述"同源）。*

*审查记录（二次深审）：R1 四处升级自查在位（跳 5 心跳自杀/§7.5-7.6/Ownership 三节、跳 4 MULTI/EXEC 两连，grep 8 处命中），旧简写"处理期间周期续租（心跳）"零残留——**零尾差、零新错**。*
