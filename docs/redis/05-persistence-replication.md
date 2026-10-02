# 05 持久化与复制：RDB、AOF 与主从复制（源码解析）

> 定位：**源码解析与原理**·核心点版——三条链路：RDB 快照（fork + COW）、AOF 日志（追加 + fsync + 重写）、主从复制（PSYNC + backlog）；面试速答见附录 A。
> 源码基线：Redis 7.2.11；实验证据：`redis-lab/evidence/06-persistence-replication-7.log`（本课新增 `scripts/06-persistence-replication.sh`）。

---

## 一、源码地图

| 文件 | 职责 | 本文引用点 |
|---|---|---|
| `rdb.c` / `rdb.h` | RDB 快照：生成、格式、加载 | `rdbSaveBackground`、`rdbSaveRio`、`rdbSaveKeyValuePair`、`rdbSaveLen` |
| `aof.c` | AOF：追加、刷盘、重写、多部文件 | `feedAppendOnlyFile`、`flushAppendOnlyFile`、`rewriteAppendOnlyFileBackground` |
| `replication.c` | 主从复制：全量/增量、缓冲区 | `syncCommand`、`masterTryPartialResynchronization`、`feedReplicationBuffer` |
| `server.c` / `server.h` | 触发与状态 | save 判定、`saveparam`、AOF 状态与 fsync 枚举、`replBacklog` |
| `config.c` | 默认值 | `save`、`appendfsync everysec`、`repl-backlog-size 1mb` |

---

## 二、三条链路一眼看

**先看实验里的三个数字**（§附录 B）：1 万键 `BGSAVE` → fork 耗时 **820μs**、COW 页 **684KB**、dump 文件 **127,969 字节**；AOF 目录里是 **`base.rdb` + `incr.aof` + `manifest`** 三个文件；从节点接入后 `master_repl_offset` 与 `slave_repl_offset` 相等、`DBSIZE=15000`。

| 机制 | 写出什么 | 何时写 | 数据丢失窗口 | 恢复速度 |
|---|---|---|---|---|
| **RDB** | 全量二进制快照 | `save` 规则 / `BGSAVE` / 关闭时 | 两次快照之间 | 快（直接加载） |
| **AOF** | 增量命令日志（RESP） | 每条写命令 + fsync 策略 | always 0 / everysec ≤1s / no 交给内核 | 慢（逐条重放，7.x 有 RDB 前缀时先加载快照） |
| **主从复制** | 全量 RDB + 持续增量流 | 从节点接入 / 每次写命令 | 异步：主不等待从 | 从节点全量后增量 |

三者都建立在同一个事实之上：**Redis 的持久化/复制都是"主线程之外尽量少干活"的设计**——RDB 和 AOF 重写都 fork 子进程，复制借的是同一套 RDB 生成能力。

---

## 三、RDB：fork 出来的快照

**它干了什么**：`BGSAVE` 让 Redis 在不停机的情况下把当前内存快照写成 `dump.rdb`——写的过程中主线程继续处理请求（靠操作系统 COW）。

### 3.1 触发：save 规则与命令

`save` 规则由 `serverCron` 定期检查（`server.c:1397-1415`，节选）：

```c
        for (j = 0; j < server.saveparamslen; j++) {
            struct saveparam *sp = server.saveparams+j;

            /* Save if we reached the given amount of changes,
             * the given amount of seconds, and if the latest bgsave was
             * successful or if, in case of an error, at least
             * CONFIG_BGSAVE_RETRY_DELAY seconds already elapsed. */
            if (server.dirty >= sp->changes &&
                server.unixtime-server.lastsave > sp->seconds &&
                (server.unixtime-server.lastbgsave_try >
                 CONFIG_BGSAVE_RETRY_DELAY ||
                 server.lastbgsave_status == C_OK))
            {
                serverLog(LL_NOTICE,"%d changes in %d seconds. Saving...",
                    sp->changes, (int)sp->seconds);
                rdbSaveInfo rsi, *rsiptr;
                rsiptr = rdbPopulateSaveInfo(&rsi);
                rdbSaveBackground(SLAVE_REQ_NONE,server.rdb_filename,rsiptr,RDBFLAGS_NONE);
                break;
            }
        }
```

条件 = **变更数达标 + 距上次保存时间达标 + 上次失败后至少等 5 秒重试**（`CONFIG_BGSAVE_RETRY_DELAY`，`server.h:130`）。规则结构（`server.h:1287-1290`）：

```c
struct saveparam {
    time_t seconds;
    int changes;
};
```

默认规则是 `save 3600 1 300 100 60 10000`（1 小时内 1 次变更 / 5 分钟 100 次 / 1 分钟 10000 次）。另外 `SAVE`（阻塞主线程，禁止在生产用）和 `BGSAVE`（`rdb.c:3621`、`3639`）可手动触发。

### 3.2 fork + COW：快照为什么"不停机"

`rdbSaveBackground`（`rdb.c:1559-1591`，节选）：

```c
int rdbSaveBackground(int req, char *filename, rdbSaveInfo *rsi, int rdbflags) {
    pid_t childpid;

    if (hasActiveChildProcess()) return C_ERR;
    ...
    if ((childpid = redisFork(CHILD_TYPE_RDB)) == 0) {
        int retval;

        /* Child */
        redisSetProcTitle("redis-rdb-bgsave");
        ...
        retval = rdbSave(req, filename,rsi,rdbflags);
        if (retval == C_OK) {
            sendChildCowInfo(CHILD_INFO_TYPE_RDB_COW_SIZE, "RDB");
        }
        exitFromChild((retval == C_OK) ? 0 : 1);
    } else {
        /* Parent */
        ...
        server.rdb_child_type = RDB_CHILD_TYPE_DISK;
        return C_OK;
    }
    return C_OK; /* unreached */
}
```

**COW（Copy-on-Write）原理**：`fork()` 之后父子进程共享同一份物理页（页表只读）；主进程**写**某页时触发 page fault，OS 复制该页给主进程，子进程继续用旧页——所以子进程看到的永远是 fork 那一刻的完整快照，而复制量只与"快照期间被写过的页"成正比。

子进程落盘（`rdbSave`，`rdb.c:1516-1557`，节选）——先写临时文件再原子替换：

```c
    snprintf(tmpfile,256,"temp-%d.rdb", (int) getpid());

    if (rdbSaveInternal(req,tmpfile,rsi,rdbflags) != C_OK) {
        ...
    }
    
    /* Use RENAME to make sure the DB file is changed atomically only
     * if the generate DB file is ok. */
    if (rename(tmpfile,filename) == -1) {
        ...
    }
    if (fsyncFileDir(filename) != 0) {
        ...
    }
    ...
    server.dirty = 0;
    server.lastsave = time(NULL);
```

**代价**（实验）：fork 本身会短暂阻塞主线程（`latest_fork_usec`，实验 **820μs**）；快照期间主线程写过的页被复制，峰值内存可能接近 2 倍（`rdb_last_cow_size`，实验 **684KB** 对应 1 万个小键）——**内存越大、写越频繁，fork 与 COW 的成本越高**，这是"大实例慎用 RDB"的根源。

### 3.3 文件格式

**总入口**（`rdbSaveRio`，`rdb.c:1376-1409`，节选）：

```c
    if (server.rdb_checksum)
        rdb->update_cksum = rioGenericUpdateChecksum;
    snprintf(magic,sizeof(magic),"REDIS%04d",RDB_VERSION);
    if (rdbWriteRaw(rdb,magic,9) == -1) goto werr;
    if (rdbSaveInfoAuxFields(rdb,rdbflags,rsi) == -1) goto werr;
    ...
    /* save all databases, skip this if we're in functions-only mode */
    if (!(req & SLAVE_REQ_RDB_EXCLUDE_DATA)) {
        for (j = 0; j < server.dbnum; j++) {
            if (rdbSaveDb(rdb, j, rdbflags, &key_counter) == -1) goto werr;
        }
    }
    ...
    /* EOF opcode */
    if (rdbSaveType(rdb,RDB_OPCODE_EOF) == -1) goto werr;

    /* CRC64 checksum. It will be zero if checksum computation is disabled, the
     * loading code skips the check in this case. */
    cksum = rdb->cksum;
    memrev64ifbe(&cksum);
    if (rioWrite(rdb,&cksum,8) == 0) goto werr;
```

即：**魔数 `REDIS0011` + AUX 元信息 + 各库数据 + `EOF`(0xFF) + 8 字节 CRC64**。版本与操作码（`rdb.h:41`、`103-113`）：

```c
#define RDB_VERSION 11
...
#define RDB_OPCODE_EXPIRETIME_MS 252    /* Expire time in milliseconds. */
#define RDB_OPCODE_SELECTDB   254   /* DB number of the following keys. */
#define RDB_OPCODE_EOF        255   /* End of the RDB file. */
```

每个非空库先写 `SELECTDB` + 库号和 `RESIZEDB` 提示（`rdbSaveDb`，`rdb.c:1300-1321`）：

```c
    /* Write the SELECT DB opcode */
    if ((res = rdbSaveType(rdb,RDB_OPCODE_SELECTDB)) < 0) goto werr;
    written += res;
    if ((res = rdbSaveLen(rdb, dbid)) < 0) goto werr;
    written += res;

    /* Write the RESIZE DB opcode. */
    uint64_t db_size, expires_size;
    db_size = dictSize(db->dict);
    expires_size = dictSize(db->expires);
    if ((res = rdbSaveType(rdb,RDB_OPCODE_RESIZEDB)) < 0) goto werr;
```

**长度编码**四档（`rdbSaveLen`，`rdb.c:172-203`，节选）：

```c
    if (len < (1<<6)) {
        /* Save a 6 bit len */
        buf[0] = (len&0xFF)|(RDB_6BITLEN<<6);
        ...
    } else if (len < (1<<14)) {
        /* Save a 14 bit len */
        ...
    } else if (len <= UINT32_MAX) {
        /* Save a 32 bit len */
        ...
    } else {
        /* Save a 64 bit len */
        ...
```

即小长度用 1 字节（高 2 位标记类型，`RDB_6BITLEN=0`/`RDB_14BITLEN=1`/`0x80`/`0x81`，`rdb.h:57-60`）——**小值省字节**是 RDB 紧凑的原因之一。

**字符串编码**（`rdbSaveRawString`，`rdb.c:442-471`，节选）：

```c
    /* Try integer encoding */
    if (len <= 11) {
        unsigned char buf[5];
        if ((enclen = rdbTryIntegerEncoding((char*)s,len,buf)) > 0) {
            if (rdbWriteRaw(rdb,buf,enclen) == -1) return -1;
            return enclen;
        }
    }

    /* Try LZF compression - under 20 bytes it's unable to compress even
     * aaaaaaaaaaaaaaaaaa so skip it */
    if (server.rdb_compression && len > 20) {
        ...
    }

    /* Store verbatim */
    if ((n = rdbSaveLen(rdb,len)) == -1) return -1;
```

优先级：**整数编码 > LZF 压缩（>20B 才尝试）> 原样存储**。

**每个键**（`rdbSaveKeyValuePair`，`rdb.c:1125-1157`，节选）：

```c
    /* Save the expire time */
    if (expiretime != -1) {
        if (rdbSaveType(rdb,RDB_OPCODE_EXPIRETIME_MS) == -1) return -1;
        if (rdbSaveMillisecondTime(rdb,expiretime) == -1) return -1;
    }
    ...
    /* Save type, key, value */
    if (rdbSaveObjectType(rdb,val) == -1) return -1;
    if (rdbSaveStringObject(rdb,key) == -1) return -1;
    if (rdbSaveObject(rdb,val,key,dbid) == -1) return -1;
```

### 3.4 字节走查：`SET k hello`

1. 文件开头：`REDIS0011`（9 字节）+ AUX（`redis-ver`、`redis-bits`、`ctime` 等，`rdbSaveInfoAuxFields` `rdb.c:1193` 起）；
2. 进入 db0：`SELECTDB`(0xFE) + `0x00`，随后 `RESIZEDB`(0xFB) + 大小提示；
3. 键 `k`：**类型字节 `RDB_TYPE_STRING=0`**（`rdb.h:75`）→ `00`；键字符串 `rdbSaveRawString`：非整数、长度 ≤20 不压缩 → 长度 `rdbSaveLen(1)`=`01` + 内容 `6B`；
4. 值 `hello`：非整数 → 长度 `rdbSaveLen(5)`=`05` + `68 65 6C 6C 6F`；
5. 全文件结尾：`FF` + 8 字节 CRC64。

键值部分的字节序列：

```
00 01 6B 05 68 65 6C 6C 6F
│  │  │  │  └───────────┘
│  │  │  └──────── "hello"（原样）
│  │  └─ "k"
│  └─ 键长度 1
└─ 类型 RDB_TYPE_STRING(0)
```

### 3.5 原理与边界

1. **全量 + 原子替换**：快照是某一时刻的完整状态；`temp-<pid>.rdb` → `rename` 保证加载方永远看到完整文件（`rdb.c:1516-1557`）；
2. **COW 让主进程不阻塞**（除 fork 瞬间）；代价是 fork 耗时与峰值内存——实验：1 万键 fork **820μs**、COW 页 **684KB**、dump **127,969B**；生产大实例要预留内存、避开写高峰；
3. **丢失窗口**：两次快照之间宕机 → 丢这段数据（`save` 越密丢越少、开销越大）；
4. **恢复快**：二进制直接加载（相对 AOF 逐条重放），所以"主从全量同步"也复用它（§五）。

---

## 四、AOF：命令日志

**它干了什么**：每条写命令按 RESP 追加进 AOF；`appendfsync everysec` 每秒刷盘；文件变大后 `BGREWRITEAOF` 从**内存当前状态**重写一份最小命令集。

### 4.1 追加与刷盘

**追加**（`feedAppendOnlyFile`，`aof.c:1322-1358`，节选）：

```c
    /* The DB this command was targeting is not the same as the last command
     * we appended. To issue a SELECT command is needed. */
    if (dictid != -1 && dictid != server.aof_selected_db) {
        char seldb[64];

        snprintf(seldb,sizeof(seldb),"%d",dictid);
        buf = sdscatprintf(buf,"*2\r\n$6\r\nSELECT\r\n$%lu\r\n%s\r\n",
            (unsigned long)strlen(seldb),seldb);
        server.aof_selected_db = dictid;
    }

    /* All commands should be propagated the same way in AOF as in replication.
     * No need for AOF-specific translation. */
    buf = catAppendOnlyGenericCommand(buf,argc,argv);

    /* Append to the AOF buffer. This will be flushed on disk just before
     * of re-entering the event loop, so before the client will get a
     * positive reply about the operation performed. */
    if (server.aof_state == AOF_ON ||
        (server.aof_state == AOF_WAIT_REWRITE && server.child_type == CHILD_TYPE_AOF))
    {
        server.aof_buf = sdscatlen(server.aof_buf, buf, sdslen(buf));
    }
```

要点：**AOF 里存的是命令本身**（RESP），不是数据；换库要先补 `SELECT`；先写 `aof_buf`，事件循环下一轮前 `flushAppendOnlyFile` 落盘——"回复客户端之前先落盘"的顺序保证了"客户端确认过的写不会丢"（always 模式）。

**fsync 三档**（`server.h:527-529`）：

```c
#define AOF_FSYNC_NO 0
#define AOF_FSYNC_ALWAYS 1
#define AOF_FSYNC_EVERYSEC 2
```

执行（`flushAppendOnlyFile`，`aof.c:1236-1262`，节选）：

```c
    /* Perform the fsync if needed. */
    if (server.aof_fsync == AOF_FSYNC_ALWAYS) {
        /* redis_fsync is defined as fdatasync() for Linux in order to avoid
         * flushing metadata. */
        ...
        if (redis_fsync(server.aof_fd) == -1) {
            ...
            exit(1);
        }
        ...
    } else if (server.aof_fsync == AOF_FSYNC_EVERYSEC &&
               server.unixtime > server.aof_last_fsync) {
        if (!sync_in_progress) {
            aof_background_fsync(server.aof_fd);
```

- `always`：每次写完立即 `fdatasync`，失败直接退出（"确认过的写必须在盘上"）；性能最差；
- `everysec`（默认）：**后台线程每秒 fsync**（`aof_background_fsync`，`aof.c:926`）；若上次 fsync 未完成且延迟 <2 秒则推迟写入（`aof.c:1097-1114`），最坏丢 1 秒；
- `no`：交给操作系统（Linux 约 30 秒刷一次）。

### 4.2 重写：从内存重生成，而不是读旧文件

**入口**（`rewriteAppendOnlyFileBackground`，`aof.c:2427-2493`，节选）：

```c
    /* We set aof_selected_db to -1 in order to force the next call to the
     * feedAppendOnlyFile() to issue a SELECT command. */
    server.aof_selected_db = -1;
    flushAppendOnlyFile(1);
    if (openNewIncrAofForAppend() != C_OK) {
        ...
    }
    ...
    if ((childpid = redisFork(CHILD_TYPE_AOF)) == 0) {
        char tmpfile[256];

        /* Child */
        redisSetProcTitle("redis-aof-rewrite");
        ...
        snprintf(tmpfile,256,"temp-rewriteaof-bg-%d.aof", (int) getpid());
```

顺序很关键：**先开新的 incr 文件**（`openNewIncrAofForAppend`，`aof.c:792-855`）→ 再 fork 子进程去写新 base。这样**重写期间主进程的新写入自然进入新 incr 文件**，7.x 不再需要 6.x 的"AOF 重写缓冲区"（源码中已无 `aofRewriteBuffer`）。

**子进程重生成**（`rewriteAppendOnlyFileRio`，`aof.c:2237-2290`，节选）——遍历内存生成最小命令集：

```c
        /* SELECT the new DB */
        if (rioWrite(aof,selectcmd,sizeof(selectcmd)-1) == 0) goto werr;
        if (rioWriteBulkLongLong(aof,j) == 0) goto werr;

        /* Iterate this DB writing every entry */
        while((de = dictNext(di)) != NULL) {
            ...
            /* Save the key and associated value */
            if (o->type == OBJ_STRING) {
                /* Emit a SET command */
                char cmd[]="*3\r\n$3\r\nSET\r\n";
                if (rioWrite(aof,cmd,sizeof(cmd)-1) == 0) goto werr;
                ...
            } else if (o->type == OBJ_LIST) {
                if (rewriteListObject(aof,&key,o) == 0) goto werr;
```

例如 100 次 `INCR counter` 会被重写成一条 `SET counter 100`——**重写 = 按当前状态生成最简命令**（如多条 RPUSH 合并、过期保留）。

**收尾原子替换**（`backgroundRewriteDoneHandler`，`aof.c:2584-2707`，节选）：

```c
        /* Rename the temporary aof file to 'new_base_filename'. */
        ...
        if (rename(tmpfile, new_base_filepath) == -1) {
        ...
        /* Persist our modifications. */
        if (persistAofManifest(temp_am) == C_ERR) {
```

rename 新 base + 更新 manifest（`persistAofManifest`，`aof.c:600`），旧的 base/incr 标记为 history 后删除。

### 4.3 多部 AOF（7.x）与混合持久化

7.x AOF 是**目录 + 多文件**（`aof.c:420-438` 注释）：

```
 *  appendonly.aof.1.base.aof  (server.aof_use_rdb_preamble is no)
 *  appendonly.aof.1.base.rdb  (server.aof_use_rdb_preamble is yes)
```

**混合持久化**（默认 `aof-use-rdb-preamble yes`，`config.c:3077`）——base 文件直接写 RDB 格式（`aof.c:2370-2378`）：

```c
    if (server.aof_use_rdb_preamble) {
        int error;
        if (rdbSaveRio(SLAVE_REQ_NONE,&aof,&error,RDBFLAGS_AOF_PREAMBLE,NULL) == C_ERR) {
            errno = error;
            goto werr;
        }
    } else {
        if (rewriteAppendOnlyFileRio(&aof) == C_ERR) goto werr;
    }
```

即：**base 用 RDB（加载快）+ incr 用命令日志（丢得少）**——这就是"混合持久化"的全部实现：重写时复用 `rdbSaveRio`。

### 4.4 实验佐证

- 开启 AOF 后 `/appendonlydir/`：`appendonly.aof.1.base.rdb` + `appendonly.aof.1.incr.aof` + `appendonly.aof.manifest`；
- manifest 内容：`file appendonly.aof.1.base.rdb seq 1 type b`、`file ...incr.aof seq 1 type i`；
- `BGREWRITEAOF` 后：seq 1 → **2**（新 base 替换旧 base）、`aof_last_bgrewrite_status:ok`、`aof_current_size = aof_base_size = 171776`。

### 4.5 原理与边界

1. **写路径顺序**：命令执行 → 追加 aof_buf → 落盘/fsync → 回复客户端（always 口径）；everysec 丢 ≤1 秒；
2. **重写=状态快照式**：从内存生成，不是读旧 AOF；子进程 fork + COW，开销同 RDB；
3. **恢复慢**：逐条重放（混合模式先加载 base RDB）；
4. **运维**：`no-appendfsync-on-rewrite` 避免重写期间的 fsync 抖动；`auto-aof-rewrite-percentage/ min-size` 控制自动重写；AOF 文件损坏用 `redis-check-aof`。

---

## 五、主从复制：全量与增量

**它干了什么**：`REPLICAOF host port` 后从节点先拉一份全量数据（复用 RDB），之后主节点的每条写命令**异步**流给从节点；断线重连时若 backlog 还在，只需补差量。

### 5.1 全量同步：PSYNC

从节点发起 `PSYNC <replid> <offset>`；主节点先试增量，失败走全量（`syncCommand`，`replication.c:1010-1057`，节选）：

```c
    if (!strcasecmp(c->argv[0]->ptr,"psync")) {
        long long psync_offset;
        ...
        if (masterTryPartialResynchronization(c, psync_offset) == C_OK) {
            server.stat_sync_partial_ok++;
            return; /* No full resync needed, return. */
        } else {
        ...
    /* Full resynchronization. */
    server.stat_sync_full++;
    ...
    /* Create the replication backlog if needed. */
    if (listLength(server.slaves) == 1 && server.repl_backlog == NULL) {
        ...
        createReplicationBacklog();
```

全量时启动一次"面向复制的 BGSAVE"（`startBgsaveForReplication`，`replication.c:859-930`，节选）——可选**无盘复制**（直接 socket 发 RDB 流）：

```c
    socket_target = (server.repl_diskless_sync || req & SLAVE_REQ_RDB_MASK) && (mincapa & SLAVE_CAPA_EOF);
    ...
    if (rsiptr) {
        if (socket_target)
            retval = rdbSaveToSlavesSockets(req,rsiptr);
        else {
            /* Keep the page cache since it'll get used soon */
            retval = rdbSaveBackground(req,server.rdb_filename,rsiptr,RDBFLAGS_KEEP_CACHE);
        }
    }
```

主节点先告诉从节点"从哪个 replid/offset 开始"（`replicationSetupSlaveForFullResync`，`replication.c:714-740`，节选）：

```c
    slave->psync_initial_offset = offset;
    slave->replstate = SLAVE_STATE_WAIT_BGSAVE_END;
    /* We are going to accumulate the incremental changes for this
     * slave as well. Set slaveseldb to -1 in order to force to re-emit
     * a SELECT statement in the replication stream. */
    server.slaveseldb = -1;
    ...
        buflen = snprintf(buf,sizeof(buf),"+FULLRESYNC %s %lld\r\n",
                          server.replid,offset);
```

**`+FULLRESYNC <replid> <offset>`**：从节点记住它；RDB 传输期间主节点的新写同时进入**复制缓冲区**（挂在 replica client 的输出缓冲上）→ RDB 加载完后继续发送 → 从节点进入 online。从节点侧加载在 `readSyncBulkPayload`（`replication.c:1868` 起）。

### 5.2 增量与断线重连：backlog

**backlog 结构**（`server.h:1112-1121`）：

```c
typedef struct replBacklog {
    listNode *ref_repl_buf_node; /* Referenced node of replication buffer blocks,
                                  * see the definition of replBufBlock. */
    size_t unindexed_count;      /* The count from last creating index block. */
    rax *blocks_index;           /* The index of recorded blocks of replication
                                  * buffer for quickly searching replication
                                  * offset on partial resynchronization. */
    long long histlen;           /* Backlog actual data length */
    long long offset;            /* Replication "master offset" of first
                                  * byte in the replication backlog buffer.*/
} replBacklog;
```

backlog 默认 **1MB**（`config.c:3211`），由 `feedReplicationBuffer` 在每次写命令时同步维护（`replication.c:334-424`，节选）：

```c
void feedReplicationBuffer(char *s, size_t len) {
    ...
    if (server.repl_backlog == NULL) return;

    while(len > 0) {
        ...
            memcpy(tail->buf + tail->used, s, copy);
            tail->used += copy;
            s += copy;
            len -= copy;
            server.master_repl_offset += copy;
            server.repl_backlog->histlen += copy;
        ...
        /* For output buffer of replicas. */
        listIter li;
        listRewind(server.slaves,&li);
        while((ln = listNext(&li))) {
            client *slave = ln->value;
            ...
```

**所有复制流都经过这里**：`master_repl_offset`（全局复制偏移）与 `histlen` 同步增长，数据同时分发给每个从节点——所以 backlog 是"最近一段复制流的环形缓冲"。

**重连时判定**（`masterTryPartialResynchronization`，`replication.c:743-800`，节选）：

```c
    /* We still have the data our slave is asking for? */
    if (!server.repl_backlog ||
        psync_offset < server.repl_backlog->offset ||
        psync_offset > (server.repl_backlog->offset + server.repl_backlog->histlen))
    {
        serverLog(LL_NOTICE,
            "Unable to partial resync with replica %s for lack of backlog (Replica request was: %lld).", replicationGetSlaveName(c), psync_offset);
        ...
        goto need_full_resync;
    }
```

条件：**replid 一致 + 请求的 offset 落在 backlog 范围内** → `+CONTINUE` 从请求点继续发；否则全量。设计取舍：backlog 越大容错窗口越长、内存成本越高。

### 5.3 异步语义与运维认知

1. **异步是本质**：主节点执行完命令就回复客户端，不等从节点确认——**写主读从是最终一致**（主从延迟窗口内从读到旧数据）；
2. **offset/ACK**：从节点周期性 `REPLCONF ACK <offset>`（`replconfCommand`，`replication.c:1169` 起）；`WAIT numreplicas timeout` 才提供"等 N 个从确认"的同步等待；
3. **级联复制**：从节点也可以带从节点（分担主节点出带宽，延迟逐级累积）；
4. **无盘复制**：`repl-diskless-sync`（默认开，`config.c:3071`）直接走 socket；
5. **实验**：`REPLICAOF` 后 `master_link_status:up`；`master_repl_offset` 与 `slave_repl_offset` 相等；从节点 `DBSIZE=15000`（全量同步）；写主读从成功；`WAIT 1 100` 返回 1。

### 5.4 原理与边界

1. **全量复用 RDB**：fork + COW 的同一套成本；无盘复制省磁盘 IO 但占网络；
2. **增量复用 backlog**：backlog 太小 → 断线稍久就触发全量（网络与内存双成本）；
3. **异步 + 单线程主库**：从节点不拖慢主节点写（除输出缓冲膨胀被 client-output-buffer-limit 断开）；主从切换（Sentinel/Cluster）见后续篇章。

---

## 六、原理总结

1. **三条链路共用一个引擎**：RDB 生成（fork+COW）既服务落盘，也服务主从全量；AOF 的 base 重写也复用 `rdbSaveRio`（混合持久化）；复制增量也进 `feedReplicationBuffer`（§三~§五）；
2. **原子替换是通用手法**：RDB `temp-<pid>.rdb → rename`、AOF 重写 `temp → rename + manifest`——加载方永远看到完整文件（§3.2、§4.2）；
3. **RDB 与 AOF 互补**：RDB 快、丢窗口大；AOF 丢得少、恢复慢、开销持续；混合持久化 = RDB base + AOF incr（§四）；
4. **复制 = 全量（RDB）+ 增量（backlog 上的双向 offset）**，异步是默认语义，"等确认"要显式 `WAIT`（§五）；
5. **代价公式**：fork 阻塞 ≈ 页表大小；COW ≈ 快照期间写入量；everysec ≈ 1 秒窗口；backlog 大小 ≈ 断线容错窗口（§三、§四、§五）。

---

## 附录 A：面试速答（附带）

> RDB 是 fork 子进程做的全量快照：fork 后父子共享只读页，主进程写谁就复制谁（COW），所以子进程看到的是 fork 瞬间的数据；fork 本身会短暂阻塞主线程（毫秒级）、快照期间写入量大时内存峰值接近 2 倍。文件格式是魔数 REDIS0011 + SELECTDB/键值对 + EOF + CRC64，键值对里长度用 1/2/5/9 字节紧凑编码、字符串能当整数就当整数、大字符串可选 LZF。AOF 是命令日志：每条写命令进 aof_buf，fsync 三档——always 每条刷、everysec 后台每秒刷（最多丢 1 秒）、no 交给 OS；文件大了 BGREWRITEAOF：先开新的 incr 文件、再 fork 子进程从内存当前状态生成最小命令集（多条 INCR 合成一条 SET），完成后 rename + 更新 manifest 原子替换；7.x 是 base+incr+manifest 多部结构，默认 aof-use-rdb-preamble 让 base 是 RDB（混合持久化）。主从复制：从发 PSYNC replid offset，主先试增量——replid 一致且 offset 在 repl_backlog（默认 1MB 环形缓冲，所有写命令经 feedReplicationBuffer 同时进 backlog 和每个从的缓冲）范围内就 +CONTINUE 补差量，否则 +FULLRESYNC 走全量（复用 BGSAVE，可无盘直发 socket）；复制是异步的，写主读从有延迟，等确认要用 WAIT。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/06-persistence-replication.sh redis-lab-7   # 输出见 evidence/06-persistence-replication-7.log
```

| 观测点 | 结果 |
|---|---|
| RDB fork/COW | 1 万键后 `rdb_changes_since_last_save=10000` → `BGSAVE` → `0`；`latest_fork_usec=820`；`rdb_last_cow_size=684032`；`/dump.rdb` 127,969 字节 |
| AOF 多部文件 | `appendonly.aof.1.base.rdb` + `.1.incr.aof` + `manifest`（`type b` / `type i`）；重写后 seq → 2；`aof_base_size = aof_current_size = 171776` |
| 主从全量 | 从：`master_link_status:up`、`slave_repl_offset=1420404`；主：`connected_slaves:1`、`slave0 ... state=online`；从 `DBSIZE=15000` |
| 主从增量 | 写主 → 读从 `hello-from-master`；`WAIT 1 100 → 1` |

## 附录 C：源码坐标系（本文引用）

| 内容 | 坐标 |
|---|---|
| save 规则结构 / 重试延迟 | `server.h:1287-1290`、`server.h:130` |
| serverCron 触发判定 | `server.c:1397-1415` |
| `SAVE` / `BGSAVE` 命令 | `rdb.c:3621`、`rdb.c:3639` |
| `rdbSaveBackground`（fork） | `rdb.c:1559-1591`（fork `1568`、COW 上报 `1576`） |
| `rdbSave`（临时文件 + rename + fsync 目录） | `rdb.c:1516-1557` |
| `rdbSaveRio`（魔数/EOF/CRC64） | `rdb.c:1376-1409`（魔数 `1384`、EOF `1401-1402`、CRC `1404-1408`） |
| `rdbSaveDb`（SELECTDB/RESIZEDB + 遍历） | `rdb.c:1300-1321` |
| RDB 常量（版本/长度/操作码/类型） | `rdb.h:41`、`57-60`、`75`、`103-113` |
| `rdbSaveType`（1 字节） | `rdb.c:116-118` |
| `rdbSaveLen`（长度四档） | `rdb.c:172-203` |
| `rdbSaveRawString`（整数/LZF/原样） | `rdb.c:442-471` |
| `rdbSaveKeyValuePair`（过期/类型/键/值） | `rdb.c:1125-1157` |
| `rdbSaveObjectType`（类型映射） | `rdb.c:661-690` |
| `rdbSaveInfoAuxFields`（AUX 元信息） | `rdb.c:1193-1210` |
| AOF 状态 / fsync 枚举 | `server.h:312-314`、`server.h:527-529` |
| `feedAppendOnlyFile`（SELECT + RESP → buf） | `aof.c:1322-1358` |
| `flushAppendOnlyFile`（推迟逻辑 / fsync 执行） | `aof.c:1066-1134`、`aof.c:1236-1262`（后台 fsync `926`） |
| `rewriteAppendOnlyFileBackground`（新 incr + fork） | `aof.c:2427-2493`（fork `2464`、COW `2474`） |
| `openNewIncrAofForAppend`（重写期新写入落点） | `aof.c:792-855`（注释 `779`） |
| `rewriteAppendOnlyFileRio`（从内存重生成） | `aof.c:2237-2290` |
| 混合持久化（RDB preamble） | `aof.c:2368-2378` |
| `backgroundRewriteDoneHandler`（rename + manifest） | `aof.c:2584-2707`（rename `2612`、manifest `2666-2678`） |
| 多部 AOF 命名注释 / manifest 持久化 | `aof.c:420-438`、`aof.c:600` |
| AOF/复制默认配置 | `config.c:3077`（preamble=yes）、`3088`（appendonly=no）、`3139`（everysec）、`3211`（backlog 1mb）、`3071`（diskless=yes） |
| `syncCommand`（PSYNC 分支/全量准备） | `replication.c:940-1057`（partial `1018`、全量 `1043-1057`） |
| `replicationSetupSlaveForFullResync`（+FULLRESYNC） | `replication.c:714-740`（`728`） |
| `masterTryPartialResynchronization`（backlog 判定） | `replication.c:743-800`（`777-789`） |
| `startBgsaveForReplication`（disk/diskless） | `replication.c:859-930`（`868`） |
| `feedReplicationBuffer`（offset/backlog/分发） | `replication.c:334-424` |
| `replBacklog` 结构 / 创建 | `server.h:1112-1121`、`replication.c:121` |
| `readSyncBulkPayload`（从节点加载） | `replication.c:1868` 起 |
| `replicaofCommand` / `replicationSetMaster` | `replication.c:3140-3166`、`replication.c:3008-3027` |
| 从节点 offset/ACK 字段 | `server.h:1200-1205`；`replconfCommand` `replication.c:1169` |
