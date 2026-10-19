# L3-09 — Feed/IM/SSE/计数：长连接与计数专项（打包篇）

> **定位**：L3 业务域 P0 收官（08-Feed / 09-IM / 10-SSE / 11-计数Buffer）。四域各一节（时序→决策→拷打），共享模式收束。
> **依据**：xhs-project 代码（FeedPushConsumer/OnlineRouteService/ChatService/SseEmitterManager/CounterBuffer）+ 08/09/10/11 章。

---

## 一、08 Feed：推拉结合（写扩散成本模型）

**链路**：
```
笔记发布事件 → 粉丝数 ≤ 阈值 → 推模式：粉丝列表分批（500/批 Pipeline）ZADD 收件箱
                              + 进度 cursor（myxhs:feed:push:progress:{msgId}，TTL 1h）断点续推
             → 粉丝数 > bigVThreshold（默认 10 万，配置化；旧硬编码 5 万已统一）→ 拉模式：写作者发件箱
读侧：收件箱 + 大V发件箱合并；收件箱裁剪（500 条/7 天，FeedCleanupJob）；已删笔记标记 5min（T-126：乱序晚到不回写）
```

**决策**：
| 决策 | 为什么 |
|---|---|
| 推拉阈值（10 万） | 推=写放大（粉丝数×ZADD），拉=读合并；阈值按写放大×读延迟平衡，配置化可调 |
| Pipeline 500（M2） | 500 粉丝 500 次往返→1 次（~100x）；cursor 每批落盘，挂了从 cursor 续推 |
| ZADD 幂等 | 重推/补偿重放天然无害（相同 member 更新 score） |
| 收件箱裁剪 | ZREMRANGEBYRANK 定期清（500 条/7 天），防无限膨胀 |
| 大 V 标记缓存 10min | 粉丝刚过阈值仍按推写百万粉丝=写入风暴——注释直接警告；靠过期+变更事件缓解（有界不一致） |

---

## 二、09 IM：路由表 + 定向 Pub/Sub（长连接跨实例）

**链路**：
```
连接：WS 上线 → myxhs:im:route:{userId}=serverId + myxhs:im:online:{userId}=1（TTL=心跳 30s×3=90s）
     PING→PONG 续期；注销 Lua CAS（校验 serverId 再 DEL，防旧实例误删新路由）
发送：会话 seqNo 自增 → 先落库（事务）→ 查路由：本实例直推 / 他实例 publish 实例专属 Channel
     （myxhs:im:route:{serverId}，定向投递免过滤）；不在线/失败 → myxhs:im:offline:{userId}
上线补发：离线 ZSet（score=timestamp，ZADD O(logN)/ZREM 快）按 seqNo 升序补发（M8 修复乱序）
ACK/TYPING（99/98）瞬时信号：推送失败不重放（只有 CHAT 进离线）
```

**决策**：
| 决策 | 为什么 |
|---|---|
| 路由表而非一致性哈希 | 哈希扩缩容漂移；"可达性不以哈希正确为前提"（ImConsistentHashLoadBalancer 未接线，诚实边界） |
| 定向 Channel 而非广播 | 每条消息只去目标实例（省过滤）；Pub/Sub 发后即忘 → 靠落库+离线兜底 |
| TTL=3 倍心跳 | 容忍连续 2 次丢包；更短放大写+误离线，更长拉长假死窗口（90s 误判上限） |
| 先落库后推送 | 推送丢了用户重连可补；顺序颠倒则"推了但没存"=真丢 |
| seqNo 排序补发 | 到达顺序不可信（M8 教训）；有序性由序号保证，不由网络保证 |

---

## 三、10 SSE：ticket 两步 + 路由转发（单向推送）

**链路**：
```
鉴权：POST /sse/ticket（30s 一次性）→ GET /sse?ticket= 建连（token 不进 URL，防日志泄漏）
路由：myxhs:notification:sse:{userId}=serverId（30s TTL，@Scheduled 10s 批量续期）
推送：查路由→本实例直推 / 跨实例发目标实例专属 Channel（`...:channel:{serverId}`，定向投递，与 IM 同构）
替换守卫：新连接 complete 旧 emitter；旧回调删路由先比对身份（emitters.remove(k,v) 双参数）防误删新连接
emitter 30 分钟超时兜底；onCompletion/onTimeout/onError 三处统一清理
```

**决策**：
| 决策 | 为什么 |
|---|---|
| SSE 而非 WS（通知场景） | 单向通知 SSE 够用且便宜（HTTP 语义+自动重连）；双向聊天才上 WS（IM） |
| 实例专属 Channel（与 IM 同构） | SSE 与 IM 都用"目标实例专属 Channel 定向投递"（`...:{serverId}`）；差异在路由 TTL/续期（SSE 30s+10s vs IM 90s+30s 心跳）与推送语义（SSE 尽力+落库拉取 vs IM 落库+离线 seqNo 补发） |
| 落库拉取兜底（不用 Last-Event-ID） | 通知已落库 t_notification；拉取天然幂等——比事件 ID 重放简单可靠 |
| 替换守卫 | 旧连接 close 回调删路由会误删新连接——身份比对是必备，不是优化 |

---

## 四、11 计数：Set 化 Lua + 双 Buffer（非幂等操作的幂等化）

**链路**：
```
写：Lua 同一执行单元"去重 + 增减"（Set 化：以"用户是否在集合"判定状态迁移，乱序/重复自然幂等）
   → CounterBuffer 攒批（BATCH_SIZE=500、@Scheduled 5s、双 Buffer 交换：flush 换出旧 buffer 即建新 buffer，volatile 可见）
   → 批量刷盘 DB；失败重试 3 次 + 回写缓冲；归零保护 Lua（m11）；每日对账修复
读：Redis 实时权威；DB 持久账本（对账以 Redis 全量重算修 DB）
```

**决策**：
| 决策 | 为什么 |
|---|---|
| Set 化而非纯 INCR | 计数非幂等（+1 两次就错）；Set 化把乱序变无害（H2 教训：Like/Unlike 乱序虚增） |
| 双 Buffer 交换 | flush 期间新写入不能丢：换出旧 buffer 刷盘、新 buffer 即收；volatile+锁保护交换 |
| 500/5s 批大小 | 压测校准的延迟/吞吐折中；N 次写合并 1 次批量 upsert |
| 接受进程被杀丢数 | 有界损失（内存未 flush 部分），对账重算收敛——计数非资金，最终一致可接受 |
| 归零保护 | 防止异常把计数清零（m11） |

---

## 五、跨域拷打（精选 8 问）

1. **"推拉怎么选？"**（Feed）写放大 vs 读合并成本模型；阈值配置化；大 V 必拉。
2. **"长连接跨实例消息怎么到？"**（IM/SSE）路由表定位 + MQ/Channel 转发 + 落库兜底；哈希只是优化不能当正确性前提。
3. **"推送丢了怎么办？"**（IM/SSE）先落库，再谈推送；离线补发（seqNo 排序）/ 拉取补偿；Pub/Sub 发后即忘是已知语义。
4. **"计数重复/乱序怎么办？"**（计数）Set 化 Lua（状态迁移判定）+ 幂等去重 + 对账；非幂等操作必须在原子单元内解决幂等。
5. **"CLEAR/删除与并发？"**（Feed 已删标记 5min / 购物车屏障 L3-04）时间窗口 + 版本/序号仲裁。
6. **"心跳/TTL 怎么定？"**（IM 30s/90s，SSE 10s/30s）容忍丢包数 × 假死窗口的权衡；宁可多一次兜底，不赌心跳。
7. **"替换/重连竞态？"**（SSE 守卫/IM CAS）旧连接回调删新路由——身份比对是必备。
8. **"热点写怎么扛？"**（Feed 大 V/计数热 key）推转拉 + 分片计数方向；当前边界诚实说明。

---

## 六、话术（四句）

> "Feed 是推拉结合：普通用户推（Pipeline 批量 + cursor 断点续推），大 V 走拉（10 万阈值可调）；ZADD 幂等所以重推无害。"
> "IM 长连接靠路由表加定向转发，90 秒 TTL 配 30 秒心跳，注销用 Lua 比对防误删；消息先落库再推送，离线按 seqNo 补发——可达性不赌哈希。"
> "SSE 通知是尽力而为：ticket 两步鉴权不让 token 进 URL，跨实例定向 channel 转发（与 IM 同构），连接替换有守卫；可靠性靠落库加拉取。"
> "计数不是幂等操作，所以把去重和增减塞进同一条 Lua，用 Set 化把乱序变无害；性能用双 Buffer 攒批 500 条 5 秒一刷，丢数靠对账收敛。"

---

## 七、回链索引

| 主题 | 文档 |
|---|---|
| Redis 结构/Lua/hash tag | K02 |
| MQ 投递/重试/DLQ | K01 |
| 对账/幂等 | dist-consistency + 11/18 题 |
| 长连接网关/超时 | K07 + K08 |
| 购物车屏障（同类问题） | L3-04 |
| xhs 章节 | 08-Feed推拉 / 09-IM跨实例 / 10-SSE跨实例 / 11-计数Buffer |
