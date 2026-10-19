# 08 事务、发布订阅与客户端缓存：Redis 的"半功能"（源码解析）

> 定位：**源码解析与原理**·核心点版——三件常被误用的机制：MULTI/EXEC/WATCH（打包 ≠ 回滚）、PubSub（广播 ≠ 消息队列）、客户端缓存 tracking（失效通知 ≠ 强一致）；附使用规范红线；面试速答见附录 A。
> 源码基线：Redis 7.2.11；实验证据：`redis-lab/evidence/09-transaction-pubsub-tracking-7.log`（本课新增 `scripts/09-transaction-pubsub-tracking.sh`）。

---

## 一、源码地图

| 文件 | 职责 | 本文引用点 |
|---|---|---|
| `multi.c` | 事务：排队、执行、WATCH | `multiCommand`、`queueMultiCommand`、`execCommand`、`touchWatchedKey` |
| `pubsub.c` | 发布订阅 | `pubsubSubscribeChannel`、`pubsubPublishMessageInternal` |
| `tracking.c` | 客户端缓存失效通知 | `TrackingTable`、`trackingRememberKeys`、`trackingInvalidateKey`、`sendTrackingMessage` |
| `slowlog.c` / `config.c` | 使用规范观测 | `slowlogPushEntryIfNeeded`、默认值 |
| `server.h` | 相关标志位 | `CLIENT_MULTI`、`CLIENT_DIRTY_CAS/EXEC`、`CLIENT_TRACKING*` |

---

## 二、三个机制一张表

**先看实验里的四个结局**（§附录 B）：`MULTI/EXEC` 正常返回 `OK, 2`；错误入队后 `EXECABORT`；**执行期错误只失败那一条、其余照常执行**；`WATCH` 的键被改后 `EXEC` 返回 `nil`。发布订阅在无人订阅时 `PUBLISH → 0`（消息直接丢）；tracking 会话收到了原始 RESP 推送 `>2 invalidate tk`。

| 机制 | 提供的保证 | 不提供的保证 | 一句话 |
|---|---|---|---|
| **MULTI/EXEC/WATCH** | 打包执行（单线程下不被插队）+ CAS 条件执行 | **回滚**、隔离级别 | "打包"不是"ACID 事务" |
| **PubSub** | 在线订阅者实时广播 | 存储、ACK、断线重放、消费者组 | 发完即忘的广播 |
| **客户端缓存 tracking** | 服务端主动失效通知 | 强一致、持久缓存 | 帮助客户端维护近端缓存 |

三者都不是"残缺"，而是**设计定位不同**——误把"半功能"当"全功能"就是生产事故的来源。

---

## 三、事务：打包执行 ≠ ACID

### 3.1 排队：命令先存起来

`MULTI` 只是打标志（`multi.c:112-120`）：

```c
void multiCommand(client *c) {
    if (c->flags & CLIENT_MULTI) {
        addReplyError(c,"MULTI calls can not be nested");
        return;
    }
    c->flags |= CLIENT_MULTI;

    addReply(c,shared.ok);
}
```

之后的命令不再执行，而是**排队**（`queueMultiCommand`，`multi.c:60-96`，节选）：

```c
void queueMultiCommand(client *c, uint64_t cmd_flags) {
    multiCmd *mc;

    /* No sense to waste memory if the transaction is already aborted. ... */
    if (c->flags & (CLIENT_DIRTY_CAS|CLIENT_DIRTY_EXEC))
        return;
    ...
    mc = c->mstate.commands+c->mstate.count;
    mc->cmd = c->cmd;
    mc->argc = c->argc;
    mc->argv = c->argv;
    ...
    c->mstate.count++;
    ...
```

两个关键标志（`server.h:338-348`，节选）：

```c
#define CLIENT_MULTI (1<<3)   /* This client is in a MULTI context */
#define CLIENT_DIRTY_CAS (1<<5) /* Watched keys modified. EXEC will fail. */
#define CLIENT_DIRTY_EXEC (1<<12)  /* EXEC will fail for errors while queueing */
```

### 3.2 EXEC 的三种结局

`execCommand` 先做两道检查（`multi.c:148-179`，节选）：

```c
    if (!(c->flags & CLIENT_MULTI)) {
        addReplyError(c,"EXEC without MULTI");
        return;
    }
    ...
    if (c->flags & (CLIENT_DIRTY_CAS | CLIENT_DIRTY_EXEC)) {
        if (c->flags & CLIENT_DIRTY_EXEC) {
            addReplyErrorObject(c, shared.execaborterr);
        } else {
            addReply(c, shared.nullarray[c->resp]);
        }

        discardTransaction(c);
        return;
    }
```

结局矩阵：

| 情况 | 触发 | EXEC 行为 |
|---|---|---|
| 正常 | 无 dirty 标志 | 逐条执行，返回结果数组 |
| 入队错误 | 命令不存在/参数错 → `CLIENT_DIRTY_EXEC` | **EXECABORT**，整段不执行 |
| WATCH 失效 | 被监视键被改 → `CLIENT_DIRTY_CAS` | 返回 **nil**（特殊值，不是错误），整段不执行 |
| 执行期错误 | 类型不匹配等（入队时查不出） | **错误只出现在那个结果位**；其余命令照常执行、**不回滚** |

执行循环（`multi.c:196-246`，节选）——注意**逐条 `call(c,...)`，没有任何整体回滚逻辑**：

```c
    addReplyArrayLen(c,c->mstate.count);
    for (j = 0; j < c->mstate.count; j++) {
        c->argc = c->mstate.commands[j].argc;
        c->argv = c->mstate.commands[j].argv;
        ...
        } else {
            if (c->id == CLIENT_ID_AOF)
                call(c,CMD_CALL_NONE);
            else
                call(c,CMD_CALL_FULL);
            ...
```

事务期间禁阻塞命令（`c->flags |= CLIENT_DENY_BLOCKING;`）——所以 `BLPOP` 之类在事务里会被拒绝。

### 3.3 WATCH：乐观锁（CAS）

WATCH 的数据结构是两个方向的反查（`multi.c:274-280`）：

```c
typedef struct watchedKey {
    listNode node;
    robj *key;
    redisDb *db;
    client *client;
    unsigned expired:1; /* Flag that we're watching an already expired key. */
} watchedKey;
```

- `client->watched_keys`：这个客户端看了哪些键；
- `db->watched_keys`：一个键被哪些客户端看着（键 → 客户端列表）。

任何写键的路径最终会调 `touchWatchedKey`（`multi.c:377-416`，节选）：

```c
    if (dictSize(db->watched_keys) == 0) return;
    clients = dictFetchValue(db->watched_keys, key);
    if (!clients) return;

    /* Mark all the clients watching this key as CLIENT_DIRTY_CAS */
    ...
        c->flags |= CLIENT_DIRTY_CAS;
        /* As the client is marked as dirty, there is no point in getting here
         * again ... */
        unwatchAllKeys(c);
```

被标记的客户端 EXEC 时返回 nil——**乐观锁 = 尝试 + 检查**，不是阻塞式加锁。

### 3.4 实验佐证

- 正常：`MULTI → SET t:a 1 → INCR t:a → EXEC` → `OK / QUEUED / QUEUED / (OK, 2)`；
- 入队错误：`NOSUCHCMD` → `EXECABORT Transaction discarded because of previous errors.`；
- 执行错误：`INCR str`（string 值）→ 结果数组第一条 `ERR value is not an integer or out of range`、第二条 `OK`，且 `GET t:b = 1` 证明**没回滚**；
- WATCH：会话 A `WATCH wkey` 后会话 B `SET wkey other`，A 的 `EXEC` 返回 nil（FIFO 双连接验证）。

### 3.5 原理与边界

1. **原子性来自单线程**：EXEC 期间不处理其他客户端命令（01），不是 MVCC/锁；
2. **无回滚、无隔离级别**：执行期错误不回滚是**设计选择**（不引入 undo log，保持简单）；
3. **WATCH 必须配合重试**：CAS 失败（nil）由客户端决定是否重试读-改-写；
4. **需要真原子** → 用 Lua（EVAL 整段原子）或单命令设计；需要回滚 → 说明模型不适配，换数据库。

---

## 四、发布订阅：发完即忘的广播

### 4.1 结构：两张表

订阅关系存两份（`server.h:1219-1221` 客户端侧、`1965-1969` 服务端侧，节选）：

```c
/* client 侧： */
    dict *pubsub_channels;  /* channels a client is interested in (SUBSCRIBE) */
    dict *pubsub_patterns;  /* patterns a client is interested in (PSUBSCRIBE) */
/* server 侧： */
    dict *pubsub_channels;  /* Map channels to list of subscribed clients */
    dict *pubsub_patterns;  /* A dict of pubsub_patterns */
```

- `server.pubsub_channels`：频道 → 订阅者列表（O(1) 找到听众）；
- `server.pubsub_patterns`：模式 → 订阅者列表（**发布时要遍历所有模式逐个匹配**）。

订阅（`pubsubSubscribeChannel`，`pubsub.c:246-269`，节选）：

```c
    if (dictAdd(type.clientPubSubChannels(c),channel,NULL) == DICT_OK) {
        retval = 1;
        incrRefCount(channel);
        /* Add the client to the channel -> list of clients hash table */
        de = dictFind(*type.serverPubSubChannels, channel);
        if (de == NULL) {
            clients = listCreate();
            dictAdd(*type.serverPubSubChannels, channel, clients);
            incrRefCount(channel);
        } else {
            clients = dictGetVal(de);
        }
        listAddNodeTail(clients,c);
    }
```

### 4.2 发布：先频道、后模式

`pubsubPublishMessageInternal`（`pubsub.c:470-522`，节选）：

```c
    /* Send to clients listening for that channel */
    de = dictFind(*type.serverPubSubChannels, channel);
    if (de) {
        list *list = dictGetVal(de);
        ...
        while ((ln = listNext(&li)) != NULL) {
            client *c = ln->value;
            addReplyPubsubMessage(c,channel,message,*type.messageBulk);
            ...
    }
    ...
    /* Send to clients listening to matching channels */
    di = dictGetIterator(server.pubsub_patterns);
    if (di) {
        ...
        while((de = dictNext(di)) != NULL) {
            robj *pattern = dictGetKey(de);
            ...
            if (!stringmatchlen((char*)pattern->ptr, ..., (char*)channel->ptr, ...,0)) continue;
            ...
```

频道订阅 O(订阅者数)，**模式订阅 O(模式数 × 匹配成本)**——模式多了会拖慢每个 PUBLISH。

### 4.3 语义边界

- **不存消息**：PUBLISH 时没在线 → 直接丢（实验：`PUBLISH ch dead → 0`）；
- **无 ACK/无消费者组**：掉线的订阅者不会补发；
- **订阅连接受限**：订阅模式下的连接只能发 PING/SUBSCRIBE 等（RESP2 限制）；
- 要"消息不丢 + 消费组"→ 用 **Stream**（04）；要"纯实时广播"→ PubSub 足够且更轻。

### 4.4 实验佐证

`SUBSCRIBE ch` + 两条消息 → `"subscribe","ch",1` / `"message","ch","hello"` / `"message","ch","world"`；`PSUBSCRIBE news.*` 收到 `"pmessage","news.*","news.tech","T1"`。

---

## 五、客户端缓存：tracking

**它干了什么**：客户端把热点数据缓存在本地；Redis 记录"谁读过哪些键"，键被修改时给对应客户端推一条失效通知，客户端删本地缓存——**Server-assisted client-side caching**。

### 5.1 失效表：反查"键→客户端"

```c
rax *TrackingTable = NULL;
...
/* This is the structure that we have as value of the PrefixTable ... */
typedef struct bcastState {
    rax *keys;      /* Keys modified in the current event loop cycle. */
    rax *clients;   /* Clients subscribed to the notification events for this
                       prefix. */
} bcastState;
```
（`tracking.c:44-60`，节选）

- **默认模式**：`TrackingTable`：键 → 读过它的客户端 ID 集合（外层 rax 按 key，内层 rax 按 client id）；
- **BCAST 模式**：`PrefixTable`：前缀 → `bcastState{keys, clients}`，按前缀注册、按前缀广播。

### 5.2 登记：读命令执行后记住键

`trackingRememberKeys`（`tracking.c:217-254`，节选）：

```c
    uint64_t optin = tracking->flags & CLIENT_TRACKING_OPTIN;
    uint64_t optout = tracking->flags & CLIENT_TRACKING_OPTOUT;
    uint64_t caching_given = tracking->flags & CLIENT_TRACKING_CACHING;
    if ((optin && !caching_given) || (optout && caching_given)) return;

    getKeysResult result = GETKEYS_RESULT_INIT;
    int numkeys = getKeysFromCommand(executing->cmd,executing->argv,executing->argc,&result);
    ...
    for(int j = 0; j < numkeys; j++) {
        ...
        rax *ids = raxFind(TrackingTable,(unsigned char*)sdskey,sdslen(sdskey));
        if (ids == raxNotFound) {
            ids = raxNew();
            ...
        }
        if (raxTryInsert(ids,(unsigned char*)&tracking->id,sizeof(tracking->id),NULL,NULL))
            TrackingTableTotalItems++;
    }
```

由命令表（`COMMAND` 的 key specs）反推"这条读命令碰了哪些键"——命令元数据在此处复用。

### 5.3 失效：修改时逐个通知

`trackingInvalidateKey`（`tracking.c:366-422`，节选）：

```c
    rax *ids = raxFind(TrackingTable,key,keylen);
    if (ids == raxNotFound) return;
    ...
    while(raxNext(&ri)) {
        uint64_t id;
        memcpy(&id,ri.key,sizeof(id));
        client *target = lookupClientByID(id);
        ...
        /* If the client enabled the NOLOOP mode, don't send notifications
         * about keys changed by the client itself. */
        if (target->flags & CLIENT_TRACKING_NOLOOP &&
            target == server.current_client)
        {
            continue;
        }
        ...
            sendTrackingMessage(target,(char *)keyobj->ptr,sdslen(keyobj->ptr),0);
    }
    ...
    /* Free the tracking table: we'll create the radix tree and populate it
     * again if more keys will be modified in this caching slot. */
    TrackingTableTotalItems -= raxSize(ids);
    raxFree(ids);
    raxRemove(TrackingTable,(unsigned char*)key,keylen,NULL);
```

**通知即删表**（发过就清理）——这是 tracking 内存可控的关键。

**通知的三种投递**（`sendTrackingMessage`，`tracking.c:268-324`，节选）：

```c
    if (c->resp > 2) {
        addReplyPushLen(c,2);
        addReplyBulkCBuffer(c,"invalidate",10);
    } else if (using_redirection && c->flags & CLIENT_PUBSUB) {
        /* We use a static object to speedup things, ... */
        addReplyPubsubMessage(c,TrackingChannelName,NULL,shared.messagebulk);
    } else {
        /* ... RESP2 does not support push messages ... */
```

- **RESP3**：连接上直接推 `>2 invalidate <keys>`（实验抓到原始字节）；
- **REDIRECT**：把通知重定向到另一个客户端；若对方是 RESP2 且处于订阅模式，则走 `__redis__:invalidate` 频道；
- **RESP2 且无重定向**：无法推送（这就是 tracking 需要 RESP3 或 redirect 的原因）。

### 5.4 模式组合

`CLIENT TRACKING` 选项（`enableTracking`，`tracking.c:180-209`，节选）：

```c
    if (options & CLIENT_TRACKING_BCAST) {
        c->flags |= CLIENT_TRACKING_BCAST;
        if (numprefix == 0) enableBcastTrackingForPrefix(c,"",0);
        for (size_t j = 0; j < numprefix; j++) {
            sds sdsprefix = prefix[j]->ptr;
            enableBcastTrackingForPrefix(c,sdsprefix,sdslen(sdsprefix));
        }
    }

    /* Set the remaining flags that don't need any special handling. */
    c->flags |= options & (CLIENT_TRACKING_OPTIN|CLIENT_TRACKING_OPTOUT|
                           CLIENT_TRACKING_NOLOOP);
```

| 组合 | 含义 | 表开销 |
|---|---|---|
| 默认 | 只记"真正读过的键" | 键级（`tracking-table-max-keys` 默认 1M，`config.c:3228`） |
| `BCAST` | 按前缀注册，**该前缀所有键的修改都通知** | 前缀级，通知量大 |
| `OPTIN`/`OPTOUT` | 配合 `CLIENT CACHING yes/no` 控制单命令是否登记 | 更精细 |
| `NOLOOP` | 不通知"自己改的键" | — |
| `REDIRECT id` | 通知发给别的连接（代理场景） | 通知可能失败（`tracking-redir-broken`） |

`FLUSHALL/FLUSHDB` 时对所有 tracking 客户端发空失效（`trackingInvalidateKeysOnFlush`，`tracking.c:468-496`）。

### 5.5 实验佐证

- 原始 RESP：`GET tk` 返回 `v1` 后，另一客户端 `SET tk v3`，tracking 连接收到
  ```
  >2
  $10
  invalidate
  *1
  $2
  tk
  ```
- `CLIENT TRACKINGINFO`（BCAST）：`flags on bcast`、`redirect 0`、`prefixes`。

### 5.6 原理与边界

1. **弱一致**：失效通知与写命令之间、通知与客户端删缓存之间都有窗口；客户端最终删掉旧值；
2. **通知即删表**：默认模式内存与"未被修改的已读键数"成正比，不是与查询次数；
3. **边界**：RESP2 需 REDIRECT 或 PubSub 通道；重定向目标掉线 → 标记 broken；BCAST 模式写放大（每个键的修改都要广播给前缀订阅者）。

---

## 六、使用规范：不是所有 O(1) 都是真 O(1)

### 6.1 O(N) 命令红线

`KEYS`/`HGETALL`/`SMEMBERS`/`LRANGE 0 -1` 在单线程上**阻塞所有人**（01、02 已论证）；替代是游标命令 `SCAN/HSCAN/SSCAN/ZSCAN`（每轮有界）。量化它们的最直接工具是 **SLOWLOG**（`slowlogPushEntryIfNeeded`，`slowlog.c:123`；阈值 `slowlog-log-slower-than` 默认 10000μs，`config.c:3207`）。

实验：`DEBUG SLEEP 0.02`（20ms）后 `SLOWLOG GET 1` 显示一条：

```
1) (integer) 1
2) (integer) 1791000186
3) (integer) 20187          ← 耗时 20.187ms
4) 1) "debug" 2) "sleep" 3) "0.02"
```

### 6.2 连接与输出缓冲

- `maxclients` 默认 **10000**（`config.c:3191`）——连接池不要开满，每个连接都有内存开销；
- `client-output-buffer-limit` 默认（实验输出）：`normal 0 0 0`（不限）、`slave 256MB/64MB/60s`、`pubsub 32MB/8MB/60s`——**慢消费者会被断开**（PubSub 涨得快就是这个原因）；
- 大 key/大 value 的 `DEL` 用 `UNLINK`（01）。

### 6.3 "半功能"的正确替代

| 需求 | 不要用 | 用什么 |
|---|---|---|
| 原子读-改-写 | MULTI+WATCH（要重试） | Lua / 单命令 / 原子类型 |
| 可回滚事务 | MULTI/EXEC | 数据库；或重新设计模型 |
| 可靠消息队列 | PubSub | Stream（消费组 + ACK） |
| 近端缓存一致性 | 手写广播 | tracking（失效通知）+ TTL 兜底 |

---

## 七、原理总结

1. **三者定位不同**：事务=打包+CAS、PubSub=实时广播、tracking=失效通知；它们的"缺失"都是明确的设计取舍（§二）；
2. **事务的三个标志位驱动全部语义**：`CLIENT_MULTI`/`CLIENT_DIRTY_EXEC`/`CLIENT_DIRTY_CAS`；入队错误整体拒绝、执行错误单条失败、WATCH 失败返回 nil（§三）；
3. **PubSub 两张表**：频道表 O(1) 直达订阅者；模式表发布时遍历匹配——**不存、不确认、不重放**（§四）；
4. **tracking 的核心是"反查表 + 通知即删"**：默认按读过的键登记、修改时点对点推送（RESP3 push / REDIRECT）；BCAST 按前缀换更粗的失效（§五）；
5. **规范是给单线程模型交的税**：O(N) 命令、慢消费者输出缓冲、连接数都要按"阻塞所有人"来评估（§六）。

---

## 附录 A：面试速答（附带）

> MULTI/EXEC 是打包执行不是 ACID：命令入队，EXEC 顺序执行，靠单线程保证不被打断；入队错误（命令不存在、参数错）会让 EXEC 返回 EXECABORT 整段不执行；执行期错误（比如对 string 做 INCR）只影响那一条，其余照常执行、不回滚——这是设计选择。WATCH 是乐观锁：监视的键被改就给客户端打 DIRTY_CAS 标记，EXEC 返回 nil；失败要客户端自己重试。要实现真原子用 Lua。PubSub 是发完即忘的广播：server 维护"频道→订阅者列表"和"模式→订阅者列表"，发布时频道 O(1) 直达、模式遍历匹配；没有订阅者消息就丢，断线不补发，没有 ACK 和消费组——要可靠消息用 Stream。客户端缓存 tracking：客户端读过的键在服务端登记（键→客户端集合），键被修改时服务端推 invalidate 通知（RESP3 用 push，RESP2 要 REDIRECT 到 PubSub 频道），收到通知删本地缓存；还有 BCAST（前缀订阅、范围通知）、OPTIN/OPTOUT、NOLOOP 等模式；它是"帮助客户端做近端缓存一致性"的弱一致机制，不是强一致。使用规范上：KEYS/HGETALL 这类 O(N) 命令别用，换 SCAN 系列；慢消费者会被输出缓冲限制断开；maxclients 默认 1 万，连接池别开满。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/09-transaction-pubsub-tracking.sh redis-lab-7   # 输出见 evidence/09-transaction-pubsub-tracking-7.log
```

| 观测点 | 结果 |
|---|---|
| 事务正常 | `OK / QUEUED / QUEUED / (OK, 2)` |
| 入队错误 | `EXECABORT Transaction discarded because of previous errors.` |
| 执行错误 | 结果数组 `ERR value is not an integer or out of range` + `OK`；`GET t:b = 1`（**无回滚**） |
| WATCH CAS | 双连接（FIFO）演示：EXEC 返回 nil |
| PubSub | 无订阅者 `PUBLISH → 0`；订阅收到 message/pmessage |
| tracking | 原始 RESP 推送 `>2 invalidate tk`；TRACKINGINFO `flags on bcast` |
| SLOWLOG | `DEBUG SLEEP 0.02` → 记录耗时 20187μs；`maxclients=10000`；pubsub 输出缓冲 32MB/8MB/60s |

## 附录 C：源码坐标系（本文引用）

| 内容 | 坐标 |
|---|---|
| 事务标志位 | `server.h:338`、`340`、`348`；`CLIENT_DENY_BLOCKING` `server.h:388` |
| 排队 `queueMultiCommand` | `multi.c:60-96` |
| `MULTI` / `DISCARD` | `multi.c:112-120`、`122-129` |
| `EXEC` 结局判定 | `multi.c:148-256`（结局判定 `159-179`；过期键 `159-162`） |
| `EXEC` 执行循环 | `multi.c:196-246`（禁阻塞 `184`） |
| WATCH 结构/命令/失效 | `multi.c:274-280`、`470-485`、`377-416`；`watchForKey` `300`、`unwatchAllKeys` `333` |
| PubSub 字段 | `server.h:1219-1221`、`1965-1969` |
| 订阅 `pubsubSubscribeChannel` | `pubsub.c:246-269` |
| 发布 `pubsubPublishMessageInternal` | `pubsub.c:470-522` |
| tracking 表结构 | `tracking.c:43`（`TrackingTable`）、`53-60`（`bcastState`） |
| `enableTracking` | `tracking.c:180-209` |
| `trackingRememberKeys` | `tracking.c:217-254` |
| `sendTrackingMessage`（RESP3/redirect） | `tracking.c:268-324` |
| `trackingInvalidateKey`（通知即删） | `tracking.c:366-422` |
| flush 全失效 / 挂起失效处理 | `tracking.c:468-496`、`424` |
| tracking 表上限 | `config.c:3228`（默认 100 万键） |
| SLOWLOG | `slowlog.c:123`；`config.c:3207`（阈值 10000μs） |
| maxclients 默认 | `config.c:3191`（10000） |
