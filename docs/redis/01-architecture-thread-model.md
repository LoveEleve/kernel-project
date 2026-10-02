# 01 Redis 线程模型：事件循环、命令执行与 IO 线程（源码解析）

> 定位：**源码解析与原理**文章——引用核心源码（抓主链，不逐行），实验数据作为佐证；面试速答见附录 A。
> 源码基线：Redis 7.2.11（实验镜像同版本）；实验证据：`redis-lab/evidence/`。

---

## 一、源码地图：一条命令经过哪些文件

| 文件 | 职责 | 本文引用点 |
|---|---|---|
| `ae.c` | 事件循环（Reactor）：多路复用等待、就绪事件分发 | `aeMain`、`aeProcessEvents`、`aeApiPoll` |
| `networking.c` | 连接读写、协议解析、IO 线程 | `readQueryFromClient`、`addReply`、`writeToClient`、`IOThreadMain`、`handleClientsWithPendingWritesUsingThreads` |
| `server.c` | 命令分发、事件循环回调、`beforeSleep` | `processCommand`、`call`、`beforeSleep`、`accept` 注册 |
| `bio.c` | 后台线程（关闭文件 / AOF fsync / 惰性释放） | `bioInit`、`bioProcessBackgroundJobs` |
| `dict.c` / `object.c` | 数据结构层（无锁的佐证） | `dict.c` 无同步原语；`object.c:380` 引用计数 |

调用链总览（本文展开的对象）：

```
aeMain (ae.c:493)
 └─ aeProcessEvents (ae.c:361) → aeApiPoll（epoll_wait）
     ├─ 监听 fd 就绪 → accept → 新连接注册读处理器
     └─ 客户端 fd 就绪 → readQueryFromClient (networking.c:2622)
         └─ 解析完成后 → processCommand (server.c:3836) → call() → c->cmd->proc(c) (server.c:3527)
             └─ addReply (networking.c:435) → 写入客户端输出缓冲
 beforeSleep (server.c:1626)
 └─ handleClientsWithPendingWrites (networking.c:2029)
    或 handleClientsWithPendingWritesUsingThreads (networking.c:4329)
         └─ writeToClient (networking.c:1945)
```

---

## 二、事件循环：aeMain / aeProcessEvents

**核心源码**（`ae.c:493`）：

```c
void aeMain(aeEventLoop *eventLoop) {
    eventLoop->stop = 0;
    while (!eventLoop->stop) {
        aeProcessEvents(eventLoop, AE_ALL_EVENTS|
                                   AE_CALL_BEFORE_SLEEP|
                                   AE_CALL_AFTER_SLEEP);
    }
}
```

**核心源码**（`ae.c:361`，节选等待段）：

```c
int aeProcessEvents(aeEventLoop *eventLoop, int flags) {
    ...
    if (eventLoop->maxfd != -1 ||
        ((flags & AE_TIME_EVENTS) && !(flags & AE_DONT_WAIT))) {
        ...
        numevents = aeApiPoll(eventLoop, tvp);      /* ← 本文注：Linux 下为 epoll_wait */
        ...
```

要点：

1. 一次循环 = 一次 `aeApiPoll`（Linux 下 epoll 等待）+ 处理所有就绪事件 + 处理到期时间事件；
2. 监听 fd 与所有客户端 fd 注册在**同一个** event loop（注册接口 `aeCreateFileEvent`，`ae.h:117`；监听注册见 `server.c:2417-2421`）；
3. 就绪事件的回调在**同一线程内依次执行**——这就是"单 Reactor、单线程"的源码形态。

**走查**：客户端 A、B、C 同时发来命令 → 一次 `epoll_wait` 返回 3 个就绪 fd → 事件循环依次回调 `readQueryFromClient`（A）→（B）→（C）；任一回调未返回，后续回调不被调度。

---

## 三、读路径：readQueryFromClient

**核心源码**（`networking.c:2622`，节选）：

```c
void readQueryFromClient(connection *conn) {
    client *c = connGetPrivateData(conn);
    ...
    /* Check if we want to read from the client later when exiting from
     * the event loop. This is the case if threaded I/O is enabled. */
    if (postponeClientRead(c)) return;
    ...
    readlen = PROTO_IOBUF_LEN;
    ...
    qblen = sdslen(c->querybuf);
    ...
```

要点：

1. 先判断是否"推迟读取"——**这就是 IO 辅助线程读路径的入口**（开启 `io-threads` 且读分流时，客户端被挂起、稍后由辅助线程读取，见第八节）；
2. 数据读入 `c->querybuf`（SDS）后进入协议解析（multi-bulk），生成命令参数数组；
3. 大参数优化（`PROTO_MBULK_BIG_ARG` 分支）：对单个大参数直接按参数长度读取，避免解析时二次拷贝。

---

## 四、执行路径：processCommand 与 call

**核心源码**（`server.c:3836` 与 `server.c:3527`，节选）：

```c
int processCommand(client *c) {
    ...
}
...
/* server.c:3527 附近，call() 内： */
c->cmd->proc(c);
```

要点：

1. `processCommand` 负责查找命令、检查权限/参数/集群重定向/内存上限等**前置逻辑**；
2. 真正的执行是 `call()` 中一次**函数指针调用**（命令表 `redisCommand` 的 `proc` 字段）——命令实现**在事件循环线程内联执行**，没有线程池（对照：Tomcat 的 Servlet 会交给业务线程池）；
3. 因此"命令串行"不是靠调度器保证，而是**代码结构使然**：一个线程、一条调用链。

**这条结构带来的性质**（原理）：数据结构层不需要任何并发控制。佐证：`dict.c` 全文 0 处锁/原子操作；对象引用计数是普通整型自增（`object.c:380-390`）。反证：代码里少数**跨线程**的状态立刻要付同步成本——IO 线程的任务计数使用原子变量与互斥锁（`networking.c:4161-4204`）。

---

## 五、回复与写回：addReply → writeToClient

**核心源码**（`networking.c:394`，回复缓冲构建，节选）：

```c
void _addReplyToBufferOrList(client *c, const char *s, size_t len) {
    ...
```

客户端输出缓冲两段式（定义见 `server.h:177/1182`）：

1. 16KB 静态缓冲 `c->buf`——小回复直接写入；
2. 超出后挂到 `c->reply` 块链表——大回复分块排队。

**核心源码**（`networking.c:1945`，写回，节选）：

```c
int writeToClient(client *c, int handler_installed) {
    atomicIncr(server.stat_total_writes_processed, 1);
    ssize_t nwritten = 0, totwritten = 0;
    ...
    int ret = _writeToClient(c, &nwritten);
    ...
```

要点：

1. **回复构建**（`addReply`，主线程内完成）与 **socket 写入**（`writeToClient`）是两个阶段——第八节的 IO 分流正因为它们可分离；
2. 写回不必在命令执行后立刻做：事件循环的 `beforeSleep`（`server.c:1626`）统一调用 `handleClientsWithPendingWrites`（`server.c:1644`），把攒下的回复批量写回。

---

## 六、慢命令为什么会阻塞：串行结构的代价（原理 + 实验）

**原理**：命令实现在 `call()` 内联执行（第四节）。`c->cmd->proc(c)` 不返回，事件循环就不会回到 `aeApiPoll`——期间所有已就绪连接都得不到处理。这是"队头阻塞"，不是调度缺陷。

**实验佐证**（30 万字段，基线 PING 91ms，含 `docker exec` 开销，看增量）：

| 命令 | 期间其他客户端 PING | 机制 |
|---|---|---|
| 5 亿次循环 Lua | **4753ms**（脚本执行 5s） | 单次调用内长时间占用事件循环线程 |
| `HGETALL`（30 万字段） | **342ms** | O(N) 扫描 + 回复构建都在 `call()` 内 |
| `KEYS *`（30 万键） | **200ms** | 全键空间扫描 |
| `HSCAN` 分 300 轮 / `SCAN` 分 300 轮 | 82ms / 90ms（≈基线） | 每轮有界，轮间回到事件循环 |

**补救的原理（`UNLINK`，源码）**：把"释放内存"从主线程挪走。

```c
int dbAsyncDelete(redisDb *db, robj *key) {          /* db.c:397 */
    return dbGenericDelete(db, key, 1, DB_FLAG_KEY_DELETED);
}
int dbDelete(redisDb *db, robj *key) {               /* db.c:403：DEL 走同步或懒释放配置 */
    return dbGenericDelete(db, key, server.lazyfree_lazy_server_del, DB_FLAG_KEY_DELETED);
}
```

异步路径把对象交给 `BIO_LAZY_FREE` 任务，由后台线程执行真正的释放（第七节）。实验：50 万字段大 Hash，`DEL` 期间 PING 398ms；`UNLINK` 74ms（`lazyfree_pending_objects:1` 证明后台正在释放）。

---

## 七、后台线程：bio.c

**核心源码**（`bio.c:144`，线程创建）：

```c
        if (pthread_create(&thread,&attr,bioProcessBackgroundJobs,arg) != 0) {
            serverLog(LL_WARNING, "Fatal: Can't initialize Background Jobs. Error message: %s", strerror(errno));
            exit(1);
        }
```

**核心源码**（`bio.c:205`，任务循环，节选）：

```c
void *bioProcessBackgroundJobs(void *arg) {
    ...
    while(1) {
        if (listLength(bio_jobs[worker]) == 0) {
            pthread_cond_wait(&bio_newjob_cond[worker], &bio_mutex[worker]);
            continue;
        }
        ln = listFirst(bio_jobs[worker]);
        job = ln->value;
        pthread_mutex_unlock(&bio_mutex[worker]);
        int job_type = job->header.type;
        if (job_type == BIO_CLOSE_FILE) { ... }
```

要点：

1. `bioInit` 在启动时**循环创建 3 个 worker**（不是懒启动）；线程名 `bio_close_file` / `bio_aof` / `bio_lazy_free`；
2. 任务通过"互斥锁 + 条件变量 + 任务链表"投递（区别于 IO 线程的原子计数 + 自旋）；
3. 三类工作：异步关闭大文件、AOF 刷盘（04 篇）、异步释放内存（第六节 `UNLINK` 的执行者）。

---

## 八、IO 辅助线程：networking.c 中的分流

**核心源码**（`networking.c:4208`，辅助线程任务处理）：

```c
        listRewind(io_threads_list[id],&li);
        while((ln = listNext(&li))) {
            client *c = listNodeValue(ln);
            if (io_threads_op == IO_THREADS_OP_WRITE) {
                writeToClient(c,0);
            } else if (io_threads_op == IO_THREADS_OP_READ) {
                readQueryFromClient(c->conn);
            } else {
                serverPanic("io_threads_op value is unknown");
            }
        }
        listEmpty(io_threads_list[id]);
        setIOPendingCount(id, 0);
```

**核心源码**（`networking.c:4374`，写路径：主线程也处理一份，然后等待）：

```c
    io_threads_op = IO_THREADS_OP_WRITE;
    for (int j = 1; j < server.io_threads_num; j++) {
        int count = listLength(io_threads_list[j]);
        setIOPendingCount(j, count);
    }
    /* Also use the main thread to process a slice of clients. */
    listRewind(io_threads_list[0],&li);
    while((ln = listNext(&li))) { writeToClient(c,0); }
    /* Wait for all the other threads to end their work. */
    while(1) {
        unsigned long pending = 0;
        for (int j = 1; j < server.io_threads_num; j++)
            pending += getIOPendingCount(j);
        if (pending == 0) break;
    }
```

结合 `networking.c:4329` 的分发段与 `networking.c:4309-4319` 的降级条件，IO 线程的完整语义：

1. **分发**：主线程把 `clients_pending_write` 按 `item_id % io_threads_num` 轮询到 `io_threads_list[0..N-1]`；索引 0 归主线程；副本客户端固定归主线程（共享复制缓冲）；
2. **执行**：辅助线程只做两类事——`writeToClient`（写路径，默认启用）与 `readQueryFromClient`（读路径，需 `io-threads-do-reads yes`）；命令执行、回复构建仍在主线程；
3. **同步**：轮次制（原子计数 + 自旋等待），一轮结束主线程为仍有未发送数据的客户端重装写处理器；
4. **降级**：待写客户端数 `< io_threads_num × 2` 时本轮退回同步处理（`stopThreadedIOIfNeeded`）；
5. **配置**：`io-threads` 只能启动参数（immutable）；6.x/7.x 读路径需开 `io-threads-do-reads`；8.x 该配置移除、读默认由辅助线程处理（实现重构到 `iothread.c`）。

**实验佐证**：`io-threads=4 + do-reads` 下计数器 `io_threaded_reads_processed=75,019`、`io_threaded_writes_processed=87,107`（辅助线程确实在搬 socket 数据）；同负载吞吐与默认配置无显著差异——该负载下网络 IO 不是主线程的主要成本。主线程 CPU 利用率实测：小值（3B）84%、大值（100KB）90%，两种负载下开启 io-threads 均无明显收益（大 value 的主线程成本在命令执行路径的拷贝/分配，不在可分流范围）。

---

## 九、线程全景与启动方式（汇总）

| 线程 | 数量 | 何时出现 | 源码依据 |
|---|---|---|---|
| `redis-server`（主线程） | 1 | 进程启动 | 事件循环 + 命令执行 |
| `bio_close_file` / `bio_aof` / `bio_lazy_free` | 3 | **启动时创建** | `bio.c:144`（`bioInit` 循环） |
| `io_thd_1..N-1` | 默认 0 | `io-threads>1` 启动时创建；运行中不可增（immutable） | 7.2.11 `networking.c:4258`；8.8.0 `iothread.c:924` |
| `jemalloc_bg_thd` | 0/1 | 仅 jemalloc 构建；默认开启、可运行时开关（唯一"按需出现"） | `config.c`（`jemalloc-bg-thread` 默认 1，MODIFIABLE） |
| （fork 子进程） | 临时 | RDB 存盘 / AOF 重写 | 是进程不是线程 |

`pthread_create` 分布：服务器核心 2 处（`bio.c` + IO 线程创建点）；`deps/` 53 处在 jemalloc 内部；工具与插件层不计入服务进程。

---

## 十、原理总结

1. **单 Reactor、单线程**：`aeMain → aeProcessEvents → aeApiPoll` 是所有网络事件的统一入口（第二、三节）；
2. **原子性来自串行**：命令在事件循环线程内联执行（第四节），数据结构无需并发控制（`dict.c` 无同步原语、引用计数非原子）；跨线程状态则必须付原子与互斥成本（`networking.c:4161-4204`）；
3. **等待不占线程 + 命令微秒级**（哈希定位 O(1)、紧凑编码）⇒ 单核十万级 QPS（实测小值 GET 83,542 QPS，主线程利用率 84%）；
4. **代价是队头阻塞**：O(N) 命令、大 key 删除、长脚本会推迟事件循环回到 `aeApiPoll`（第六节）；对应的工程手段是分批命令与 `UNLINK`/BIO；
5. **IO 辅助线程只是分流 socket 读写与协议处理**（第八节），不改变"命令执行单线程"的语义；是否启用取决于"网络 IO 是否构成主线程成本大头"，需以压测为准。

---

## 附录 A：面试速答（附带）

> Redis 命令执行是单线程串行的：事件循环 `aeMain` 用 epoll 统一等待所有连接，就绪后在同一个线程里依次读、解析、执行、回复；命令在 `call()` 里就是一次函数指针调用，没有线程池。这带来无锁的数据结构（哈希表实现里没有同步原语、引用计数是普通整型），原子性自然成立——多键操作写成一个 Lua 就天然原子。单线程撑十万 QPS 靠两根支柱：IO 多路复用让等待不占线程；命令是 O(1)/O(logN) 的微秒级操作。代价是慢命令队头阻塞：实测 5 秒 Lua 让其他客户端 PING 卡 4.7 秒、30 万字段 HGETALL 期间 PING 从 91ms 到 342ms；补救是分批命令与 UNLINK。另外"单线程"指命令执行——进程里还有 3 个 BIO 后台线程（关文件/AOF fsync/异步释放）和可选的 io-threads（只搬 socket 与协议，不执行命令）。

## 附录 B：实验与复现

```bash
cd redis-lab && docker compose up -d
bash scripts/01-blocking-ping.sh redis-lab-7 500000000
bash scripts/02-bigkey-del-vs-unlink.sh redis-lab-7 500000
```
证据：`evidence/01-blocking-ping-7.log`（69ms→4753ms）、`01-blocking-ping-8.log`、`02-bigkey-7.log`（DEL 398ms / UNLINK 74ms / lazyfree_pending_objects:1）；线程清单与 io-threads 计数为本文实测（说明：PING 往返含 `docker exec` 开销，看增量）。

## 附录 C：源码坐标系（本文引用）

| 内容 | 坐标 |
|---|---|
| 事件循环入口 / 等待 | `ae.c:493`（`aeMain`）/ `ae.c:361`（`aeProcessEvents`）/ `ae.c:399`（`aeApiPoll` 调用） |
| fd 事件注册 / 监听注册 | `ae.h:117` / `server.c:2417-2421` |
| 读路径 / 推迟读取（IO 线程入口）/ 大参数优化 | `networking.c:2622` / `networking.c:4427`（`postponeClientRead`）/ `networking.c:2642`（`PROTO_MBULK_BIG_ARG`） |
| 命令分发 / 函数指针执行 | `server.c:3836` / `server.c:3527` |
| 回复缓冲（16KB + 链表） | `networking.c:394`；`server.h:177`、`server.h:1182` |
| 写回 / beforeSleep 批量刷出 | `networking.c:1945` / `server.c:1626`、`server.c:1644` |
| 异步删除（UNLINK 路径） | `db.c:397`、`db.c:403` |
| BIO 创建 / 任务循环 | `bio.c:144` / `bio.c:205` |
| IO 线程分流（分发/执行/等待/降级） | `networking.c:4329` / `networking.c:4208` / `networking.c:4374` / `networking.c:4309-4319` |
| 无锁数据结构佐证 | `dict.c`（0 处同步原语）、`object.c:380-390`（普通整型计数） |
| 跨线程同步成本 | `networking.c:4161-4204`（原子计数 + 互斥锁） |
| IO 线程创建点（版本对照） | 7.2.11 `networking.c:4258`；8.8.0 `iothread.c:924` |
| jemalloc 后台线程配置 | `config.c`（`jemalloc-bg-thread` 默认 1、MODIFIABLE） |
