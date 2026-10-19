# 06 哨兵与集群：Sentinel 与 Cluster（源码解析）

> 定位：**源码解析与原理**·核心点版——Sentinel（监控 → 主观/客观下线 → 选举 → 故障转移状态机）与 Cluster（槽与哈希 → MOVED/ASK 重定向 → Gossip 故障检测 → 集群选举）；面试速答见附录 A。
> 源码基线：Redis 7.2.11；实验证据：`redis-lab/evidence/07-sentinel-cluster-7.log`（本课新增 `scripts/07-sentinel-cluster.sh`）。

---

## 一、源码地图

| 文件 | 职责 | 本文引用点 |
|---|---|---|
| `sentinel.c` | 哨兵：实例树、监控、下线判定、选举、故障转移 | `sentinelRedisInstance`、`sentinelCheckSubjectivelyDown`、`sentinelVoteLeader`、`sentinelFailoverStateMachine` |
| `cluster.c` / `cluster.h` | 集群：槽、Gossip、重定向、选举 | `keyHashSlot`、`markNodeAsFailingIfNeeded`、`getNodeByQuery`、`clusterHandleSlaveFailover` |
| `crc16.c` | 槽哈希基元 | `crc16`（`keyHashSlot` 内部调用） |
| `config.c` / `server.h` | 默认参数与常量 | `down-after-milliseconds`、`cluster-node-timeout` |

---

## 二、两个系统各解决什么

**先看实验里的两个场景**（§附录 B）：3 个哨兵监控主从，**杀掉主节点约 3 秒后从节点被提升为主节点**；3 节点集群里，往错误节点写 `foo` 得到 **`MOVED 12182 127.0.0.1:7003`**，换 `-c` 客户端自动跟随。

| 系统 | 解决什么 | 关键机制 | 一句话原理 |
|---|---|---|---|
| **Sentinel** | 主从架构的**高可用**：自动选主、自动重配 | 主观/客观下线、leader 选举、故障转移状态机 | 多哨兵各自监控 → 投票确认下线 → 选一个执行切换 |
| **Cluster** | **水平分片**：数据按槽分布、客户端路由 | 16384 槽、CRC16 哈希、MOVED/ASK、Gossip | 键→槽→节点，槽表变了就重定向 |

两者共同的底层思想：**多数派 + 纪元（epoch）**——单点判断不算数，多数确认才动作；用递增的 epoch 决定"谁的最新配置说了算"（避免旧主复活后乱改配置）。

---

## 三、Sentinel

**它干了什么**：`SENTINEL MONITOR mymaster 127.0.0.1 6380 2` 让哨兵开始监控名为 `mymaster` 的主节点（末位 `2` 是 quorum）；主挂了，哨兵们选出新主，并把其他从节点指过去。

### 3.1 数据结构：实例树

**实例结构**（`sentinel.c:182-252`，节选）：

```c
typedef struct sentinelRedisInstance {
    int flags;      /* See SRI_... defines */
    char *name;     /* Master name from the point of view of this sentinel. */
    char *runid;    /* Run ID of this instance, or unique ID if is a Sentinel.*/
    uint64_t config_epoch;  /* Configuration epoch. */
    sentinelAddr *addr; /* Master host. */
    ...
    mstime_t s_down_since_time; /* Subjectively down since time. */
    mstime_t o_down_since_time; /* Objectively down since time. */
    mstime_t down_after_period; /* Consider it down after that period. */
    ...
    /* Master specific. */
    dict *sentinels;    /* Other sentinels monitoring the same master. */
    dict *slaves;       /* Slaves for this master instance. */
    unsigned int quorum;/* Number of sentinels that need to agree on failure. */
    int parallel_syncs; /* How many slaves to reconfigure at same time. */
    ...
    /* Failover */
    char *leader;       /* ... runid of the Sentinel that should perform the failover. ... */
    uint64_t leader_epoch; /* Epoch of the 'leader' field. */
    uint64_t failover_epoch; /* Epoch of the currently started failover. */
    int failover_state; /* See SENTINEL_FAILOVER_STATE_* defines. */
    ...
    struct sentinelRedisInstance *promoted_slave; /* Promoted slave instance. */
    ...
} sentinelRedisInstance;
```

一个哨兵进程维护一棵**实例树**：每个被监控 master 一个 `sentinelRedisInstance`，它下面挂 `slaves`（从节点）与 `sentinels`（其他哨兵）两张字典——**"发现"就是不断往这两张字典里加实例**。

**全局状态**（`sentinel.c:255-280`，节选）：

```c
struct sentinelState {
    char myid[CONFIG_RUN_ID_SIZE+1]; /* This sentinel ID. */
    uint64_t current_epoch;         /* Current epoch. */
    dict *masters;      /* Dictionary of master sentinelRedisInstances. ... */
    int tilt;           /* Are we in TILT mode? */
    ...
} sentinel;
```

**状态标志**（`sentinel.c:64-79`，节选）：

```c
#define SRI_MASTER  (1<<0)
#define SRI_SLAVE   (1<<1)
#define SRI_SENTINEL (1<<2)
#define SRI_S_DOWN (1<<3)   /* Subjectively down (no quorum). */
#define SRI_O_DOWN (1<<4)   /* Objectively down (confirmed by others). */
#define SRI_MASTER_DOWN (1<<5) /* A Sentinel with this flag set thinks that
                                   its master is down. */
#define SRI_FAILOVER_IN_PROGRESS (1<<6) /* Failover is in progress for
                                           this master. */
```

### 3.2 监控与发现

每个事件循环周期，哨兵对整棵实例树跑一遍"两半"（`sentinelHandleRedisInstance`，`sentinel.c:5379-5411`，节选）：

```c
void sentinelHandleRedisInstance(sentinelRedisInstance *ri) {
    /* ========== MONITORING HALF ============ */
    /* Every kind of instance */
    sentinelReconnectInstance(ri);
    sentinelSendPeriodicCommands(ri);
    ...
    /* ============== ACTING HALF ============= */
    ...
    /* Every kind of instance */
    sentinelCheckSubjectivelyDown(ri);
    ...
    /* Only masters */
    if (ri->flags & SRI_MASTER) {
        sentinelCheckObjectivelyDown(ri);
        if (sentinelStartFailoverIfNeeded(ri))
            sentinelAskMasterStateToOtherSentinels(ri,SENTINEL_ASK_FORCED);
        sentinelFailoverStateMachine(ri);
        sentinelAskMasterStateToOtherSentinels(ri,SENTINEL_NO_FLAGS);
    }
}
```

**监控半场**：`sentinelSendPeriodicCommands`（`sentinel.c:3116` 起）按周期发 PING/INFO/PUBLISH——PING 测活、INFO 抓主从拓扑、PUBLISH 走 `__sentinel__:hello` 频道（`sentinel.c:99`）做哨兵互相发现；**行动半场**：判下线 → 必要时启动故障转移状态机。

**Hello 消息**就是发现协议（`sentinelProcessHelloMessage`，`sentinel.c:2859-2971`，节选）：

```c
void sentinelProcessHelloMessage(char *hello, int hello_len) {
    /* Format is composed of 8 tokens:
     * 0=ip,1=port,2=runid,3=current_epoch,4=master_name,
     * 5=master_ip,6=master_port,7=master_config_epoch. */
    ...
```

8 个字段携带"我是谁、我在哪个 epoch、我监控哪个 master、master 地址与配置纪元"——其他哨兵据此补全 `sentinels` 字典、发现从节点地址变化。**哨兵之间没有中心节点，全靠这个消息自组织**。

### 3.3 主观下线（S_DOWN）

单哨兵视角的判定（`sentinelCheckSubjectivelyDown`，`sentinel.c:4537-4603`，节选）：

```c
    /* Update the SDOWN flag. We believe the instance is SDOWN if:
     *
     * 1) It is not replying.
     * 2) We believe it is a master, it reports to be a slave for enough time
     *    to meet the down_after_period, plus enough time to get two times
     *    INFO report from the instance. */
    if (elapsed > ri->down_after_period ||
        (ri->flags & SRI_MASTER &&
         ri->role_reported == SRI_SLAVE &&
         mstime() - ri->role_reported_time >
          (ri->down_after_period+sentinel_info_period*2)) ||
          (ri->flags & SRI_MASTER_REBOOT && 
           mstime()-ri->master_reboot_since_time > ri->master_reboot_down_after_period))
    {
        /* Is subjectively down */
        if ((ri->flags & SRI_S_DOWN) == 0) {
            sentinelEvent(LL_WARNING,"+sdown",ri,"%@");
            ri->s_down_since_time = mstime();
            ri->flags |= SRI_S_DOWN;
        }
```

两个条件：**PING 超时**（`elapsed > down_after_period`，默认 30 秒）或**主节点报告自己是 slave 且持续够久**（说明别处已经切换过）；判定结果只写在自己身上（SRI_S_DOWN），不算数。

### 3.4 客观下线（O_DOWN）与哨兵选举

**够票才 O_DOWN**（`sentinelCheckObjectivelyDown`，`sentinel.c:4611-4644`）：

```c
void sentinelCheckObjectivelyDown(sentinelRedisInstance *master) {
    dictIterator *di;
    dictEntry *de;
    unsigned int quorum = 0, odown = 0;

    if (master->flags & SRI_S_DOWN) {
        /* Is down for enough sentinels? */
        quorum = 1; /* the current sentinel. */
        /* Count all the other sentinels. */
        di = dictGetIterator(master->sentinels);
        while((de = dictNext(di)) != NULL) {
            sentinelRedisInstance *ri = dictGetVal(de);

            if (ri->flags & SRI_MASTER_DOWN) quorum++;
        }
        dictReleaseIterator(di);
        if (quorum >= master->quorum) odown = 1;
    }

    /* Set the flag accordingly to the outcome. */
    if (odown) {
        if ((master->flags & SRI_O_DOWN) == 0) {
            sentinelEvent(LL_WARNING,"+odown",master,"%@ #quorum %d/%d",
                quorum, master->quorum);
            master->flags |= SRI_O_DOWN;
            master->o_down_since_time = mstime();
        }
```

流程：每个哨兵发现 S_DOWN 后，向其他哨兵发 `SENTINEL IS-MASTER-DOWN-BY-ADDR` 询问；对方在回复里带**投票**信息 → 收到回复写 `SRI_MASTER_DOWN`/leader。**客观下线 = 自己 + 其他哨兵中认为下线的人数 ≥ quorum**。

**投票规则**（`sentinelVoteLeader`，`sentinel.c:4749-4774`，节选）：

```c
char *sentinelVoteLeader(sentinelRedisInstance *master, uint64_t req_epoch, char *req_runid, uint64_t *leader_epoch) {
    if (req_epoch > sentinel.current_epoch) {
        sentinel.current_epoch = req_epoch;
        sentinelFlushConfig();
        sentinelEvent(LL_WARNING,"+new-epoch",master,"%llu",
            (unsigned long long) sentinel.current_epoch);
    }

    if (master->leader_epoch < req_epoch && sentinel.current_epoch <= req_epoch)
    {
        sdsfree(master->leader);
        master->leader = sdsnew(req_runid);
        master->leader_epoch = sentinel.current_epoch;
        ...
```

每个 epoch 只投一次、epoch 递增（"新的选举轮次"）——这是类 Raft 式的**纪元投票**。

**成为 leader 需要两个条件**（`sentinelGetLeader`，`sentinel.c:4829-4840`）：

```c
    /* Check what's the winner. For the winner to win, it needs two conditions:
     * 1) Absolute majority between voters (50% + 1).
     * 2) And anyway at least master->quorum votes. */
    di = dictGetIterator(counters);
    while((de = dictNext(di)) != NULL) {
        uint64_t votes = dictGetUnsignedIntegerVal(de);

        if (votes > max_votes) {
            max_votes = votes;
            winner = dictGetKey(de);
        }
    }
```

注意 **quorum 与 majority 是两回事**：quorum 是"判 O_DOWN 的票数"（可配置），majority 是"选 leader 的绝对多数"（哨兵总数一半+1）——防止票数配置过小导致随便谁都能切。

### 3.5 故障转移状态机

状态定义（`sentinel.c:110-116`）：

```c
#define SENTINEL_FAILOVER_STATE_NONE 0  /* No failover in progress. */
#define SENTINEL_FAILOVER_STATE_WAIT_START 1  /* Wait for failover_start_time*/
#define SENTINEL_FAILOVER_STATE_SELECT_SLAVE 2 /* Select slave to promote */
#define SENTINEL_FAILOVER_STATE_SEND_SLAVEOF_NOONE 3 /* Slave -> Master */
#define SENTINEL_FAILOVER_STATE_WAIT_PROMOTION 4 /* Wait slave to change role */
#define SENTINEL_FAILOVER_STATE_RECONF_SLAVES 5 /* SLAVEOF newmaster */
#define SENTINEL_FAILOVER_STATE_UPDATE_CONFIG 6 /* Monitor promoted slave. */
```

驱动（`sentinelFailoverStateMachine`，`sentinel.c:5331-5353`）：

```c
void sentinelFailoverStateMachine(sentinelRedisInstance *ri) {
    serverAssert(ri->flags & SRI_MASTER);

    if (!(ri->flags & SRI_FAILOVER_IN_PROGRESS)) return;

    switch(ri->failover_state) {
        case SENTINEL_FAILOVER_STATE_WAIT_START:
            sentinelFailoverWaitStart(ri);
            break;
        case SENTINEL_FAILOVER_STATE_SELECT_SLAVE:
            sentinelFailoverSelectSlave(ri);
            break;
        case SENTINEL_FAILOVER_STATE_SEND_SLAVEOF_NOONE:
            sentinelFailoverSendSlaveOfNoOne(ri);
            break;
        case SENTINEL_FAILOVER_STATE_WAIT_PROMOTION:
            sentinelFailoverWaitPromotion(ri);
            break;
        case SENTINEL_FAILOVER_STATE_RECONF_SLAVES:
            sentinelFailoverReconfNextSlave(ri);
            break;
    }
}
```

各阶段要点：

1. **WAIT_START**：只有**当选 leader** 才能继续（`sentinelFailoverWaitStart`，`sentinel.c:5108-5140`）——`leader = sentinelGetLeader(ri, ri->failover_epoch); isleader = leader && strcasecmp(leader,sentinel.myid) == 0`；不是 leader 且不是强制 failover 就等，超时放弃；
2. **SELECT_SLAVE**：`sentinelSelectSlave` 按优先级/复制 offset 等挑一个从节点，标记 `SRI_PROMOTED`；
3. **SEND_SLAVEOF_NOONE**：给被选中的从节点发 `SLAVEOF NO ONE`（`sentinelFailoverSendSlaveOfNoOne`，`sentinel.c:5160-5184`）：

```c
    /* Send SLAVEOF NO ONE command to turn the slave into a master.
     * We actually register a generic callback for this command as we don't
     * really care about the reply. We check if it worked indirectly observing
     * if INFO returns a different role (master instead of slave). */
    retval = sentinelSendSlaveOf(ri->promoted_slave,NULL);
    if (retval != C_OK) return;
    ...
    ri->failover_state = SENTINEL_FAILOVER_STATE_WAIT_PROMOTION;
```

4. **WAIT_PROMOTION**：不直接等命令回执，而是**看 INFO 里 role 是否变成 master**；
5. **RECONF_SLAVES** → **UPDATE_CONFIG**：其余从节点逐个 `SLAVEOF 新主`（`parallel_syncs` 控制并发），最后把监控对象切到新主、持久化配置。

### 3.6 实验佐证

- 3 哨兵（quorum=2）+ master(6380)/replica(6381)：`SENTINEL CKQUORUM` 返回 **"OK 3 usable Sentinels. Quorum and failover authorization can be reached"**；`num-other-sentinels 2`；
- `SHUTDOWN NOSAVE` 杀掉 6380 后，**约 3 秒**（6×0.5s 轮询窗口）`SENTINEL GET-MASTER-ADDR-BY-NAME` 从 `127.0.0.1:6380` 变为 **`127.0.0.1:6381`**；6381 `role:master`。

### 3.7 原理与边界

1. **两层判定**：S_DOWN（单哨兵、本地）→ O_DOWN（quorum 票）——避免单点网络抖动误切；
2. **两类票数**：quorum（下线确认）与 majority（leader 选举）分离（§3.4）；
3. **TILT 模式**：系统时钟异常时暂停行动半场（`sentinelHandleRedisInstance` 里的 tilt 分支），防止时间错乱导致误判；
4. **epoch 防旧主**：`config_epoch` 递增，旧主复活后的配置不会覆盖新配置；
5. **边界**：哨兵数量建议 ≥3 且奇数；quorum 与 failover-timeout 决定切换灵敏度与稳定性。

---

## 四、Cluster

**它干了什么**：数据按 **16384 个槽**分给多个主节点；客户端按"键 → 槽"计算目标节点，找错了就收到 `MOVED`/`ASK` 重定向。

### 4.1 槽与哈希

```c
#define CLUSTER_SLOTS 16384
```
（`cluster.h:8`）

**键到槽**（`keyHashSlot`，`cluster.c:1375-1395`）：

```c
unsigned int keyHashSlot(char *key, int keylen) {
    int s, e; /* start-end indexes of { and } */

    for (s = 0; s < keylen; s++)
        if (key[s] == '{') break;

    /* No '{' ? Hash the whole key. This is the base case. */
    if (s == keylen) return crc16(key,keylen) & 0x3FFF;

    /* '{' found? Check if we have the corresponding '}'. */
    for (e = s+1; e < keylen; e++)
        if (key[e] == '}') break;

    /* No '}' or nothing between {} ? Hash the whole key. */
    if (e == keylen || e == s+1) return crc16(key,keylen) & 0x3FFF;

    /* If we are here there is both a { and a } on its right. Hash
     * what is in the middle between { and }. */
    return crc16(key+s+1,e-s-1) & 0x3FFF;
}
```

两条规则：**CRC16(键) & 16383**；**hash tag**——`{...}` 里的内容参与哈希，让 `user:{1}:name` 与 `user:{1}:age` 落同一槽（多键操作的前提，即"同 slot hash tag 纪律"）。

### 4.2 状态：槽表与节点

**节点**（`cluster.h:119-153`，节选）：

```c
typedef struct clusterNode {
    mstime_t ctime; /* Node object creation time. */
    char name[CLUSTER_NAMELEN]; /* Node name, hex string, sha1-size */
    char shard_id[CLUSTER_NAMELEN]; /* shard id, hex string, sha1-size */
    int flags;      /* CLUSTER_NODE_... */
    uint64_t configEpoch; /* Last configEpoch observed for this node */
    unsigned char slots[CLUSTER_SLOTS/8]; /* slots handled by this node */
    ...
    int numslots;   /* Number of slots handled by this node */
    ...
    struct clusterNode *slaveof; /* pointer to the master node. ... */
    ...
    list *fail_reports;         /* List of nodes signaling this as failing */
} clusterNode;
```

**集群状态**（`cluster.h:178-225`，节选）——核心是四张"槽→节点"的表：

```c
typedef struct clusterState {
    clusterNode *myself;  /* This node */
    uint64_t currentEpoch;
    int state;            /* CLUSTER_OK, CLUSTER_FAIL, ... */
    int size;             /* Num of master nodes with at least one slot */
    dict *nodes;          /* Hash table of name -> clusterNode structures */
    ...
    clusterNode *migrating_slots_to[CLUSTER_SLOTS];
    clusterNode *importing_slots_from[CLUSTER_SLOTS];
    clusterNode *slots[CLUSTER_SLOTS];
    ...
} clusterState;
```

`slots[i]` 记录**槽 i 的归属**；`migrating_slots_to[i]`/`importing_slots_from[i]` 只在**槽迁移期间**非空——这三个数组就是 MOVED/ASK 判定的全部依据。

### 4.3 重定向：MOVED 与 ASK

**路由决策**（`getNodeByQuery`，`cluster.c:7340` 起；关键段 `7500-7554`）：

```c
    /* If we don't have all the keys and we are migrating the slot, send
     * an ASK redirection or TRYAGAIN. */
    if (migrating_slot && missing_keys) {
        /* If we have keys but we don't have all keys, we return TRYAGAIN */
        if (existing_keys) {
            if (error_code) *error_code = CLUSTER_REDIR_UNSTABLE;
            return NULL;
        } else {
            if (error_code) *error_code = CLUSTER_REDIR_ASK;
            return server.cluster->migrating_slots_to[slot];
        }
    }

    /* If we are receiving the slot, and the client correctly flagged the
     * request as "ASKING", we can serve the request. ... */
    if (importing_slot &&
        (c->flags & CLIENT_ASKING || cmd_flags & CMD_ASKING))
    {
        ...
            return myself;
        ...
    }
    ...
    /* Base case: just return the right node. However if this node is not
     * myself, set error_code to MOVED since we need to issue a redirection. */
    if (n != myself && error_code) *error_code = CLUSTER_REDIR_MOVED;
    return n;
```

**回复**（`clusterRedirectClient`，`cluster.c:7563-7589`，节选）：

```c
    } else if (error_code == CLUSTER_REDIR_MOVED ||
               error_code == CLUSTER_REDIR_ASK)
    {
        /* Report TLS ports to TLS client, and report non-TLS port to non-TLS client. */
        int port = getNodeClientPort(n, shouldReturnTlsInfo());
        addReplyErrorSds(c,sdscatprintf(sdsempty(),
            "-%s %d %s:%d",
            (error_code == CLUSTER_REDIR_ASK) ? "ASK" : "MOVED",
            hashslot, getPreferredEndpoint(n), port));
    } else {
```

**MOVED = 槽的归属变了（永久）**；**ASK = 槽正在迁移，这一次去目标节点问（临时）**。ASK 的接收方只服务带 `ASKING` 标记的下一条命令；`CLUSTER SETSLOT <slot> MIGRATING/IMPORTING` 设置迁移状态（`cluster.c:6198-6228`）：

```c
            server.cluster->migrating_slots_to[slot] = n;
        } else if (!strcasecmp(c->argv[3]->ptr,"importing") && c->argc == 5) {
            ...
            server.cluster->importing_slots_from[slot] = n;
```

其他重定向：`-CROSSSLOT`（多键不同槽）、`-TRYAGAIN`（多键且槽在迁移）、`-CLUSTERDOWN`（集群不可用）。

### 4.4 Gossip 与故障检测

**消息类型**（`cluster.h:93-104`，节选）：

```c
#define CLUSTERMSG_TYPE_PING 0          /* Ping */
#define CLUSTERMSG_TYPE_PONG 1          /* Pong (reply to Ping) */
#define CLUSTERMSG_TYPE_MEET 2          /* Meet "let's join" message */
#define CLUSTERMSG_TYPE_FAIL 3          /* Mark node xxx as failing */
...
#define CLUSTERMSG_TYPE_FAILOVER_AUTH_REQUEST 5 /* May I failover? */
#define CLUSTERMSG_TYPE_FAILOVER_AUTH_ACK 6     /* Yes, you have my vote */
#define CLUSTERMSG_TYPE_UPDATE 7        /* Another node slots configuration */
```

节点间用 PING/PONG 互探，**每个包携带一小段 Gossip 片段**（`clusterMsgDataGossip`，`cluster.h:232-243`：nodename/ip/port/flags/ping_sent/pong_received）。采样策略（`clusterSendPing`，`cluster.c:3656-3710`，节选）：

```c
    /* How many gossip sections we want to add? 1/10 of the number of nodes
     * and anyway at least 3. Why 1/10?
     * ...
     * PROB * GOSSIP_ENTRIES_PER_PACKET * TOTAL_PACKETS:
     * ...
     * with 1/10 we always get over the majority, and specifically
     * 80% of the number of nodes, to account for many masters failing at the
     * same time. */
    wanted = floor(dictSize(server.cluster->nodes)/10);
    if (wanted < 3) wanted = 3;
```

收到 Gossip 后（`clusterProcessGossipSection`，`cluster.c:2167-2284`，节选）：把"发送者说某节点不可达"记成失败报告：

```c
            if (sender && nodeIsMaster(sender) && node != myself) {
                if (flags & (CLUSTER_NODE_FAIL|CLUSTER_NODE_PFAIL)) {
                    if (clusterNodeAddFailureReport(node,sender)) {
                        ...
                    }
                    markNodeAsFailingIfNeeded(node);
```

**从 PFAIL 到 FAIL**（`markNodeAsFailingIfNeeded`，`cluster.c:1995-2022`，节选）：

```c
void markNodeAsFailingIfNeeded(clusterNode *node) {
    int failures;
    int needed_quorum = (server.cluster->size / 2) + 1;

    if (!nodeTimedOut(node)) return; /* We can reach it. */
    if (nodeFailed(node)) return; /* Already FAILing. */

    failures = clusterNodeFailureReportsCount(node);
    /* Also count myself as a voter if I'm a master. */
    if (nodeIsMaster(myself)) failures++;
    if (failures < needed_quorum) return; /* No weak agreement from masters. */
    ...
    node->flags &= ~CLUSTER_NODE_PFAIL;
    node->flags |= CLUSTER_NODE_FAIL;
    node->fail_time = mstime();

    /* Broadcast the failing node name to everybody ... */
    clusterSendFail(node->name);
```

**quorum = 集群主节点数/2 + 1**：单节点标 PFAIL 只是怀疑，收到多数"主节点"的失败报告才标 FAIL 并广播。与 Sentinel 不同，这里的投票者是**服务槽的主节点**，不是哨兵。

### 4.5 集群内故障转移

主节点 FAIL 后，它的从节点竞选（`clusterHandleSlaveFailover`，`cluster.c:4269-4438`，节选）——先看条件：自己是从、master FAIL、master 有槽、数据够新；然后**延迟发起**：

```c
        server.cluster->failover_auth_time = mstime() +
            500 + /* Fixed delay of 500 milliseconds, let FAIL msg propagate. */
            random() % 500; /* Random delay between 0 and 500 milliseconds. */
        server.cluster->failover_auth_count = 0;
        server.cluster->failover_auth_sent = 0;
        server.cluster->failover_auth_rank = clusterGetSlaveRank();
        /* We add another delay that is proportional to the slave rank.
         * Specifically 1 second * rank. ... */
        server.cluster->failover_auth_time +=
            server.cluster->failover_auth_rank * 1000;
```

**排名（rank）按复制 offset 算**：数据最新者 rank 最小、等待最短——用延迟代替投票冲突，简单且倾向选数据最新的从。到时后广播拉票（`clusterRequestFailoverAuth`，`cluster.c:3987-3996`）：

```c
    clusterMsgSendBlock *msgblock = createClusterMsgSendBlock(CLUSTERMSG_TYPE_FAILOVER_AUTH_REQUEST, msglen);
    ...
    if (server.cluster->mf_end) msgblock->msg[0].mflags[0] |= CLUSTERMSG_FLAG0_FORCEACK;
    clusterBroadcastMessage(msgblock);
```

主节点们在 `clusterSendFailoverAuthIfNeeded`（`cluster.c:4022-4123`）里投票，条件包括：

- 自己必须是**服务槽的主节点**（`if (nodeIsSlave(myself) || myself->numslots == 0) return;`）；
- 请求 epoch 不落后、**本 epoch 未投过票**（`lastVoteEpoch == currentEpoch` 则拒绝）；
- 请求者的 master 确实 FAIL（或 MR 强制）；
- 逐个检查请求者宣称的槽：**当前归属节点的 configEpoch 不得高于请求者**（防止拿旧配置抢槽）：

```c
    for (j = 0; j < CLUSTER_SLOTS; j++) {
        if (bitmapTestBit(claimed_slots, j) == 0) continue;
        if (isSlotUnclaimed(j) ||
            server.cluster->slots[j]->configEpoch <= requestConfigEpoch)
        {
            continue;
        }
        /* ... Refuse to vote for this slave. */
```

拿到**多数主节点**的 `FAILOVER_AUTH_ACK` 后，从节点升主、把自己负责的槽写进 `slots[]` 并以 UPDATE 消息广播新配置——客户端的 MOVED 目标随之改变。

### 4.6 实验佐证

- 3 节点集群创建：`cluster_state:ok`、**"All 16384 slots covered"**；槽分布 `0-5460 / 5461-10922 / 10923-16383`；
- `CLUSTER KEYSLOT foo = 12182`；
- 在 7001 上直接写：**`MOVED 12182 127.0.0.1:7003`**；
- `redis-cli -c` 自动跟随：`set foo bar → OK`、`get foo → bar`。

### 4.7 原理与边界

1. **路由 = 三张槽表**：`slots[]` 决定 MOVED；`migrating/importing` 决定 ASK 与 TRYAGAIN（§4.2、§4.3）；
2. **Gossip 是"最终一致"的拓扑同步**：每个包带 1/10 节点信息，反复交换收敛；故障检测用多数派（size/2+1）防误判；
3. **选举靠 epoch + ACK**：epoch 单调递增防重复投票；rank 延迟避免多从同时拉票；
4. **边界**：集群至少 3 个主节点（`size/2+1` 需要多数）；多键操作必须同槽（hash tag）；`ASKING` 只对下一条命令有效；槽未全覆盖时 `cluster_state:fail`。

---

## 五、原理总结

1. **两套系统两个目标**：Sentinel 管"主从架构的高可用"，Cluster 管"分片 + 集群内高可用"；都靠多数派与 epoch（§二）；
2. **Sentinel 判定链**：PING 超时 → S_DOWN（本地）→ quorum 票 → O_DOWN → 选举 leader（majority + quorum 双条件）→ 状态机执行 `SLAVEOF NO ONE` → 重配其余从 → 更新监控（§三）；
3. **Cluster 路由链**：CRC16&16383（hash tag 聚簇）→ `slots[]` 查归属 → 本地服务 / MOVED（永久）/ ASK（迁移中）（§四）；
4. **Cluster 检测链**：Gossip 采样传播 → PFAIL → 失败报告 + 自身 = `size/2+1` → FAIL 广播 → 从节点按 rank 延迟竞选 → 多数 ACK → 升主广播 UPDATE（§4.4、§4.5）；
5. **运维权衡**：Sentinel 的 quorum/failover-timeout、Cluster 的 `cluster-node-timeout` 都是"灵敏度 vs 误判"的旋钮；健康检查（ckquorum、cluster info）应纳入监控（§3.7、§4.7）。

---

## 附录 A：面试速答（附带）

> Sentinel 是高可用方案：多个哨兵各自 PING 监控，单哨兵判定不回复超时就是**主观下线**（S_DOWN）；然后发 IS-MASTER-DOWN-BY-ADDR 问其他哨兵，票数 ≥ quorum 变**客观下线**（O_DOWN）；接着做 leader 选举——每个 epoch 一票、要有绝对多数且至少 quorum 票，当选者执行故障转移状态机：等选主 → 挑从节点（优先级/offset）→ 发 SLAVEOF NO ONE → 等 INFO 变 master → 把其他从节点指到新主 → 更新配置。注意 quorum 和 majority 是两回事。Cluster 是分片方案：16384 槽，CRC16&16383 定位，`{tag}` 同槽；每个节点维护 slots/migrating/importing 三张表决定路由——槽不属于自己就回 MOVED，槽在迁移且键不在这里就 ASK（客户端带 ASKING 去目标节点问一次）；节点间 Gossip（每包 1/10 节点、最少 3 个）同步拓扑，故障检测是 PFAIL→收多数主节点失败报告→FAIL 广播（quorum=size/2+1）；主挂了从节点按复制 offset 排名，rank 越大延迟越久拉票，主节点们用 epoch 投票（每 epoch 一票、configEpoch 不能落后），多数 ACK 后升主广播槽配置。运维上要注意集群至少 3 主、多键要同槽、哨兵至少 3 个且奇数。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/07-sentinel-cluster.sh redis-lab-7   # 输出见 evidence/07-sentinel-cluster-7.log
```

| 观测点 | 结果 |
|---|---|
| Sentinel 组网 | 3 哨兵 + 主 6380/从 6381；`ckquorum → OK 3 usable Sentinels...`；`num-other-sentinels 2`、`quorum 2` |
| Sentinel 故障转移 | 杀 6380 后约 3 秒（6×0.5s 轮询）`get-master-addr` 从 `:6380` 变为 **`:6381`**；6381 `role:master` |
| Cluster 建群 | `cluster_state:ok`、`All 16384 slots covered`；槽分布 `0-5460/5461-10922/10923-16383` |
| 槽路由 | `CLUSTER KEYSLOT foo=12182`；7001 上写 foo → **`MOVED 12182 127.0.0.1:7003`**；`-c` 自动跟随成功 |

## 附录 C：源码坐标系（本文引用）

| 内容 | 坐标 |
|---|---|
| Sentinel 状态标志 / Hello 频道 / 周期参数 | `sentinel.c:64-79`、`99`、`83-97` |
| 故障转移状态常量 | `sentinel.c:110-116` |
| `sentinelRedisInstance` / `sentinelState` | `sentinel.c:182-252`、`sentinel.c:255-280` |
| 主循环两半 `sentinelHandleRedisInstance` | `sentinel.c:5379-5411` |
| 周期命令 `sentinelSendPeriodicCommands` | `sentinel.c:3116` 起 |
| Hello 解析 `sentinelProcessHelloMessage` | `sentinel.c:2859-2971` |
| 主观下线 `sentinelCheckSubjectivelyDown` | `sentinel.c:4537-4603` |
| 客观下线 `sentinelCheckObjectivelyDown` | `sentinel.c:4611-4644` |
| 投票 `sentinelVoteLeader` / 选举 `sentinelGetLeader` | `sentinel.c:4749-4774`、`sentinel.c:4805-4868`（双条件 `4829-4831`） |
| 启动条件 `sentinelStartFailover(IfNeeded)` | `sentinel.c:4948`、`sentinel.c:4972` |
| 状态机与各阶段 | `sentinel.c:5331-5353`（机器）、`5108-5140`（WAIT_START）、`5141-5158`（SELECT_SLAVE）、`5160-5184`（SEND_SLAVEOF_NOONE） |
| 槽常量 / 消息类型 / 结构 | `cluster.h:8`、`93-104`、`119-153`、`178-225`、`232-243` |
| 键→槽 `keyHashSlot` | `cluster.c:1375-1395` |
| Gossip 发送 `clusterSendPing` | `cluster.c:3656-3788`（采样注释 `3668-3693`） |
| Gossip 接收 `clusterProcessGossipSection` | `cluster.c:2167-2284` |
| PFAIL→FAIL `markNodeAsFailingIfNeeded` | `cluster.c:1995-2022` |
| 路由决策 `getNodeByQuery` | `cluster.c:7340-7554`（ASK `7508-7522`、MOVED `7552`） |
| 重定向回复 `clusterRedirectClient` | `cluster.c:7563-7589` |
| SETSLOT 迁移状态 | `cluster.c:6198-6228` |
| 集群选举 `clusterHandleSlaveFailover` | `cluster.c:4269-4438`（延迟公式 `4342-4352`） |
| 拉票 `clusterRequestFailoverAuth` | `cluster.c:3987-3996` |
| 投票 `clusterSendFailoverAuthIfNeeded` | `cluster.c:4022-4123` |
| 阻塞客户端重定向 | `cluster.c:7602` |
