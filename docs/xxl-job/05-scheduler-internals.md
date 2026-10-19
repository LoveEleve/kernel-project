---
title: "05-调度中心原理：时间变触发"
series: "XXL-JOB 分布式任务调度系列"
previous: "04-failure-governance.md"
next: "06-xhs生产对照.md（待写）"
lab_refs:
  - "docs/中间件拷打/K13-XXL-Job.md（①调度一行+发散追问：时间轮/DB扫描/选主）"
source_refs:
  - "xxl-job-admin JobScheduleHelper.java（scheduleThread+ringThread+xxl_job_lock for update，源码已核）"
  - "xxl-job-admin JobTriggerPoolHelper.java:109（cost>500ms 慢任务阈值，源码已核）"
---

# 05-调度中心原理：时间变触发

## §0 它干了什么（例子先行）

Admin 里两个常驻线程把"CRON 说 4:00"变成准点触发：

```
scheduleThread（每 5s 对齐睡）：
  拿 DB 行锁（xxl_job_lock for update——多 Admin 实例只有持锁者能调度）
  → 预读未来 5s 内该触发的任务：
      已过期的 → 直接触发；5s 内要到的 → 塞进时间轮
ringThread（每 1s 对齐睡）：
  按"当前秒"从时间轮 ringData 取出任务 → 触发
触发线程池：快/慢两池——单次调度耗时 >500ms 的任务被记账，隔离到慢池
```

- **它干了什么**：把"几万个任务的下一次时间"压缩成"5s 一次预读 + 秒级时间轮 + 快慢隔离"。
- **本篇回答**：为什么不用每任务一个定时器？选主怎么做？快慢池防什么？

## §1 掌握卡（4 条）

1. **双线程分工**：scheduleThread 5s 周期预读（DB 行锁保证**多实例只有一个在调度**——K13 发散追问"admin 选主=DB 悲观锁"，悲观锁=先锁住资源再操作）；ringThread 秒级消费时间轮（时间轮=按时间槽分桶的定时结构；`ringData`：秒槽→任务 id 列表）。
2. **5s 预读窗**：`trigger_next_time` 落在 5s 内→预读进内存；已过期直接触发、临界的进轮——**DB 只被 5s 扫一次，不被每任务/每秒扫**。
3. **时间轮 = 秒槽 Map**：`ringSecond = (nextTime/1000) % 60`——O(1) 入槽出槽，几千任务准点性靠它。
4. **快慢池隔离**：调度执行 >500ms 记入分钟级计数，慢任务挪慢池——**一个慢任务不能堵死整个调度池**（和 RocketMQ 04"事件循环不被拖死"同思想）。

## §2 朴素答案的死法（推导）

**But**：几千任务各自一个定时线程（`ScheduledExecutorService`）——线程爆炸且重启后全部重建；每秒全表扫 `trigger_next_time`——DB 每秒几千读，纯浪费（大多数任务离下次触发还远）。

**Therefore**：**预读+轮**的经典组合——粗粒度（5s）向 DB 要"马上要跑的"，细粒度（1s）在内存里准点放行。DB 压力 = 每 5s 一次批量查；准点性 = 秒级轮。

## §3 选主：DB 行锁不是 ZooKeeper

```sql
select * from xxl_job_lock where lock_name = 'schedule_lock' for update
```

一次事务拿到行锁 → 只有持锁的 Admin 实例在预读调度；锁的事务边界=每轮扫描——实例挂了锁自动释放，另一台下一轮接管。

**设计味道**：用**已有的 MySQL** 做选主，不引入 ZK/etcd——和 K13④"admin 单实例无 HA"的边界配套：代码支持多实例互斥，xhs 只是**部署**了单实例（生产应双节点+此锁=HA 就绪，K13 追问1 原话）。

## §4 快慢池（JobTriggerPoolHelper :109 实锤）

```
触发执行记耗时：cost > 500ms → jobTimeoutCountMap 按分钟窗口计数
慢任务（窗口内多次超阈）→ 走慢线程池
```

**防的是"调度饿死"**：补偿任务扫了几千条数据花了 3s——若和高频任务共用一个池，30s 级任务全被拖慢。隔离后快池保高频准点，慢池慢慢消化。

## §5 边界（一句话翻篇）

- PRE_READ_MS 常量/预读 SQL 全文 → 源码 JobScheduleHelper 用时查。
- Admin 调度失败重试/日志清理线程 → 翻篇。
- Quartz 时间轮对比 → 07 选型一句。

## §6 Self-Test（4 题）

- Q1：双线程各干什么？为什么 DB 只被 5s 扫一次？
- Q2：时间轮的"秒槽"怎么算？（nextTime/1000%60）
- Q3：选主用什么？挂了怎么接管？（行锁/事务边界）
- Q4：快慢池的阈值？防什么饿死？

## §7 衔接

- 原理线收口；下篇 06 xhs 生产对照：28 Job 四类模式全景 + 任务分层表。
