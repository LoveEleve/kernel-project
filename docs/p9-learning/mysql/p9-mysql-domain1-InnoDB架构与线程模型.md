# p9-mysql-domain1 · InnoDB 架构与线程模型（P9 深度实验）

> MySQL 轨道域 1/9。对照 vol-mysql 01 章。
> 核心问题：**MySQL 线程模型到底是怎么样的？**（对比 Tomcat acceptor/poller/worker）

## 实验环境

```
Docker: mysql:8.0（mysql-p9 容器，端口 3306）
访问: docker exec mysql-p9 mysql -uroot -proot123
```

---

## 一、MySQL 线程模型（实测）

### 与 Tomcat 对比

```
Tomcat（NIO）：                        MySQL：
  Acceptor（accept 连接）               Listener 线程（监听端口）
  Poller（epoll 事件循环）              每连接一线程（thread-per-connection）
  Worker 线程池（业务处理）              连接线程直接执行 SQL
  → 一个线程处理多个连接               → 一个连接独占一个线程
                                      → 连接多时线程数=连接数（内存/切换开销）
```

### 实测线程全景（performance_schema.threads）

```
TYPE          cnt   threads
BACKGROUND    26    thread/innodb, thread/mysqlx, thread/sql
FOREGROUND    3     thread/sql

InnoDB 后台线程（26 个）：
  IO 线程：io_read×4 / io_write×4 / io_ibuf×1
  日志线程：log_writer / log_flusher / log_checkpointer / log_write_notifier / log_flush_notifier
  刷盘线程：page_flush_coordinator
  purge 线程：srv_purge_thread（清理 undo）
  worker 线程：srv_worker_thread×3（辅助 purge/master）
  监控线程：srv_monitor / srv_error_monitor / srv_lock_timeout
  其他：buf_dump / buf_resize / dict_stats / fts_optimize / clone_gtid / srv_master
```

### 连接线程模型（实测）

```
max_connections = 151        ← 最大连接数
thread_cache_size = 9       ← 线程缓存（复用已销毁连接的线程）
FOREGROUND = 3              ← 当前用户连接线程
```

### P9 追问链

- **Q：MySQL 线程模型和 Tomcat 区别？** → Tomcat=NIO 多路复用（一个线程多连接）；MySQL=每连接一线程（thread-per-connection）
- **Q：为什么 MySQL 用每连接一线程？** → SQL 执行是 CPU 密集+可能阻塞（锁/IO），独占线程简单；NIO 复用适合 IO 等待
- **Q：连接多了有什么问题？** → 线程数=连接数（内存/上下文切换开销）；thread_cache_size 缓存复用
- **Q：thread_cache_size 干什么？** → 连接断开后线程不销毁，缓存复用（避免频繁创建/销毁）

---

## 二、InnoDB 架构（分层流水线）

### Server 层 vs InnoDB 层

```
SQL 执行路径：
  连接器（连接管理/认证）
  → 查询缓存（8.0 已移除）
  → 解析器（词法/语法分析→AST）
  → 优化器（代价估算→执行计划）
  → 执行器（调用存储引擎接口）
  → InnoDB 层（Buffer Pool/索引/事务/锁/日志）
  → 文件层（.ibd 数据文件/redo log/binlog）
```

### InnoDB 内存结构

```
Buffer Pool（缓冲池）：
  数据页缓存（热点数据常驻内存）
  → 读：命中 BP 直接返回；未命中读磁盘→放入 BP
  → 写：写 BP（脏页）→ 后台异步刷盘
  → 大小：innodb_buffer_pool_size（生产建议 70-80% 物理内存）

Change Buffer（变更缓冲）：
  二级索引页不在 BP 时，写操作先缓存（非唯一索引）
  → 后台 merge 到 BP（减少随机读）

Adaptive Hash Index（自适应哈希索引）：
  InnoDB 自动对热点索引页建哈希索引（O(1) 查找）
  → innodb_adaptive_hash_index（可关闭）

Log Buffer（日志缓冲）：
  redo log 缓冲（刷盘频率 innodb_flush_log_at_trx_commit）
```

### InnoDB 后台线程（实测 26 个）

```
Master Thread：主调度（刷脏页/合并 change buffer/回收 undo）
IO 线程：io_read×4/io_write×4（异步 IO，innodb_read_io_threads/innodb_write_io_threads）
Log 线程：log_writer（写 redo）/log_flusher（刷 redo）/log_checkpointer（检查点）
Purge 线程：srv_purge_thread（清理 undo 旧版本）
Page Cleaner：page_flush_coordinator（刷脏页协调）
Monitor：srv_monitor/srv_error_monitor（监控/错误）
```

### P9 追问链

- **Q：Buffer Pool 干什么？** → 数据页缓存（读命中/写脏页异步刷盘）；生产 70-80% 内存
- **Q：Change Buffer 什么时候用？** → 二级索引页不在 BP 时（非唯一索引写操作缓存）
- **Q：InnoDB 后台线程有哪些？** → Master/IO×8/Log/Purge/PageCleaner/Monitor（实测 26 个）
- **Q：redo log 和 binlog 区别？** → redo=InnoDB 物理日志（崩溃恢复）；binlog=Server 逻辑日志（复制）

---

## 三、文件层

```
数据文件：*.ibd（每表一个，含数据+索引+undo）
redo log：ib_logfile0/ib_logfile1（循环写，物理日志）
binlog：mysql-bin.*（Server 层，逻辑日志，复制用）
undo log：undo 表空间（事务回滚/MVCC 快照）
```

### P9 追问链

- **Q：一个表的文件？** → *.ibd（数据+索引+undo 都在里面）
- **Q：redo log 循环写？** → 是（固定大小，写满 checkpoint 后覆盖）

---

## 本域 P9 高频考点速查

| 考点 | 一句话答案 | 证据 |
|---|---|---|
| **MySQL 线程模型** | 每连接一线程（vs Tomcat NIO 多路复用）；thread_cache_size 缓存 | 实测（FOREGROUND=3/max_conn=151） |
| **InnoDB 后台线程** | Master/IO×8/Log/Purge/PageCleaner/Monitor（实测 26 个） | 实测（P_S.threads） |
| **Buffer Pool** | 数据页缓存（读命中/写脏页异步刷盘）；生产 70-80% 内存 | 叙事 |
| **Change Buffer** | 二级索引页不在 BP 时缓存写（减少随机读） | 叙事 |
| **redo vs binlog** | redo=InnoDB 物理（崩溃恢复）；binlog=Server 逻辑（复制） | 叙事 |
| Server vs InnoDB 层 | Server=连接/解析/优化/执行；InnoDB=BP/索引/事务/锁 | 叙事 |
| thread_cache_size | 连接断开后线程缓存复用（避免频繁创建/销毁） | 实测（=9） |
| **BP LRU 改进** | Young/Old 区+中点插入（防全表扫描/预读污染） | 补充 #1 |
| **Double Write** | 防撕裂写（先写 DWB 再写数据文件）；写放大 2 倍 | 补充 #2 |
| **redo 刷盘策略** | innodb_flush_log_at_trx_commit：1=安全/0=丢1秒/2=写OS缓存 | 补充 #3 |
| 连接+查询流程 | TCP→握手→认证→线程分配；SQL→解析→优化→执行→InnoDB | 补充 #4 |

---

## 补充 #1：Buffer Pool LRU 改进（P9 高频）

### 标准 LRU 的问题

```
标准 LRU：最近最少使用淘汰
  问题①：全表扫描（大查询）把热点页挤出 BP
  问题②：预读（read ahead）读入的页可能不被使用
```

### InnoDB LRU 改进（中点插入策略）

```
LRU 链表分两个区：
  Young 区（前 5/8）：最近访问过的页（热点）
  Old 区（后 3/8）：新读入的页（预读/全表扫描）

中点插入：
  新页先插入 Old 区中点（不是头部）
  → 访问 Old 区的页 → 移到 Young 区头部（升级）
  → Old 区的页没被再次访问 → 淘汰（不污染 Young）

参数：
  innodb_old_blocks_time：页在 Old 区停留多久后才可升级（默认 1000ms）
  → 避免全表扫描期间页立即升级（保护热点）
```

### P9 追问链

- **Q：BP 的 LRU 为什么不是标准 LRU？** → 防全表扫描/预读污染热点页（中点插入+Old 区）
- **Q：中点插入怎么做？** → 新页插入 Old 区中点；再次访问才升级 Young；否则淘汰
- **Q：innodb_old_blocks_time 作用？** → Old 区页停留 1000ms 后才可升级（防扫描期间升级）

---

## 补充 #2：Double Write Buffer（防撕裂写，P9 高频）

### 撕裂写问题

```
数据页写盘时崩溃（写了前半没写后半）：
  → 磁盘上的页损坏（半新半旧）
  → redo log 无法恢复（redo 是物理日志，页本身已坏）
```

### Double Write 机制

```
① 写 BP 脏页 → 先写 Double Write Buffer（内存→磁盘连续区域）
② 再写数据文件原位置（随机写）
  → 崩溃时：从 Double Write Buffer 恢复完整页
  → 代价：一次顺序写+一次随机写（写放大 2 倍）

innodb_doublewrite：1=开启（默认）；0=关闭（某些文件系统/闪存可关）
```

### P9 追问链

- **Q：Double Write 解决什么？** → 撕裂写（页写一半崩溃→页损坏→redo 无法恢复）
- **Q：Double Write 代价？** → 写放大 2 倍（先写 DWB 再写数据文件）
- **Q：什么时候可以关闭？** → 支持原子写的文件系统/闪存（如 ZFS/ext4 with atomic write）

---

## 补充 #3：Redo Log Buffer 刷盘策略（P9 常问）

```
innodb_flush_log_at_trx_commit：
  1：每次提交都刷 redo log 到磁盘（最安全，默认）
  0：每秒刷一次（崩溃丢 1 秒事务）
  2：每次提交写 OS 缓存，每秒刷磁盘（OS 崩溃丢，MySQL 崩溃不丢）

安全性：1 > 2 > 0
性能：0 > 2 > 1

生产建议：
  数据安全要求高：=1（默认）
  可接受丢 1 秒：=0 或 =2（金融除外）
```

### P9 追问链

- **Q：innodb_flush_log_at_trx_commit 三个值？** → 1=每提交刷（安全）；0=每秒刷（丢1秒）；2=写OS缓存每秒刷
- **Q：生产建议？** → 默认 1（安全）；高吞吐可 2（可接受 OS 崩溃丢）

---

## 补充 #4：连接处理 + 查询执行完整流程

### 连接处理

```
① 客户端 TCP 连接 → MySQL Listener（每连接一线程）
② 握手：MySQL 返回版本/权限；客户端发送用户名/密码
③ 认证：验证密码（caching_sha2_password 8.0 默认）
④ 线程分配：从 thread_cache 取或新建线程
⑤ 等待命令：线程阻塞等待 SQL（read）
```

### 查询执行完整流程

```
SQL → 连接器（认证/权限）
  → 查询缓存（8.0 移除）
  → 解析器（词法/语法分析 → AST）
  → 优化器（代价估算 → 执行计划：索引选择/联接顺序）
  → 执行器（调用 InnoDB Handler 接口）
  → InnoDB（Buffer Pool 查页/加锁/写 redo+undo）
  → 返回结果
```

### P9 追问链

- **Q：连接建立流程？** → TCP→握手→认证→线程分配（thread_cache）→等待 SQL
- **Q：SQL 执行流程？** → 连接器→解析器→优化器→执行器→InnoDB
- **Q：8.0 为什么移除查询缓存？** → 写多场景下缓存失效频繁（收益低）；用 Redis 等外部缓存

## 相关文件

- Docker: mysql-p9 容器
- 实测: performance_schema.threads（26 InnoDB 后台线程）
- 实测: max_connections=151/thread_cache_size=9

## 参考来源（权威核对）

```
官方文档：
  MySQL Reference Manual 8.0：
  → InnoDB 架构（dev.mysql.com/doc/refman/8.0/en/innodb-architecture.html）
  → Buffer Pool（innodb-buffer-pool）
  → 后台线程（innodb-background-threads）
  → Change Buffer（innodb-change-buffer）

经典书籍：
  《MySQL 技术内幕：InnoDB 存储引擎》（姜承尧）→ InnoDB 架构权威
  《高性能 MySQL》第三版 → Buffer Pool/生产调优

权威来源核对：
  thread_cache_size 默认 9（官方文档确认）
  max_connections 默认 151（官方文档确认）
  innodb_buffer_pool_size 建议 70-80%（官方+Percona 一致）
```
