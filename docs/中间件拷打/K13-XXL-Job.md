# K13 — 组件深度拷打：XXL-Job

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：调度中心/执行器分离、misfire 策略、阻塞策略、分片广播 vs 加锁、非法 cron、日报口径
> 依据：xhs-project 运行态审计（`xxl-job-schedule-audit-20260918`）+ 46-XXLJob深度拷打 + 24 个 @XxlJob 代码 + 语料 xxl-job 2.4.2

---

## 问题

定时任务怎么做的？任务重复执行怎么办？调度中心挂了会怎样？错过执行窗口怎么办？cron 有什么坑？

---

## 面试可讲版（五段式）

### ① 原理层

```
架构：调度中心（admin：管理/派发/日志/报表）+ 执行器（业务进程内：注册心跳/接收触发/回调结果）
调度：admin "时间轮 + DB 扫描"线程（每 5s 扫描 trigger_next_time）派发到快慢线程池
日志：xxl_job_log（trigger_code=调度结果 / handle_code=执行结果）
```

| 概念 | 说明 |
|---|---|
| **路由策略** | FIRST/轮询/一致性哈希/分片广播——本项目统一 **FIRST**（配合任务内分布式锁） |
| **阻塞策略** | SERIAL_EXECUTION（本次未完则排队）/ DISCARD_LATER / COVER_EARLY——本项目统一 SERIAL |
| **misfire** | DO_NOTHING（丢弃，默认）/ FIRE_ONCE_NOW（补跑一次）——错过窗口的处理 |
| **失败重试** | executor_fail_retry_count（本项目多为 0，失败交下轮/对账自愈） |

### ② 项目用法（24 个任务 / 10 执行器组）

**规模**：admin **2.4.2**（18080，accessToken 鉴权）；10 个执行器组（analytics/cart/counter/coupon/home/inventory/notification/order/payment/search，端口 **9990-9999**）；**24 个 `@XxlJob`**（审计时 20，后续新增售后/结算/校准 4 个）。

**任务分层（cron 以代码注释/admin DB 为准）**：
| 层 | 任务（cron） |
|---|---|
| **高频补偿** | localMessageRetry（30s）、paymentTimeoutCheck（30s）、orderClose（1min）、refundTimeoutCheck（1min）、paymentNotifyCompensate（2min）、refundNotifyCompensate（3min）、couponExpire（5min）、orderMappingRepair（5min） |
| **分钟级巡检** | followCounterRepair（10min）、unreadReconcile（10min）、recommendHotPool（10min） |
| **每日对账/离线** | recommendItemCF（2:00）、couponReconcile（2:00）、inventoryReconcile（3:00）、counterReconcile（3:00）、paymentReconcile（3:00）、feedCleanup（3:00）、cartReconcile（4:00） |

**多实例互斥：不用分片广播，用任务内 Redis 锁**
```
分片广播使用数 = 0（getShardIndex 零调用）
理由：分片适合"大表按分片键并行"；本项目对账数据量小、要全局一致视图——
      "分片是把数据切开，锁是把执行串起来"，单实例跑全量更简单
实现（CartReconcileJob:62-96 实证）：SETNX + 随机 token + 600s TTL + Lua 比对删除（RV30）
     管理端点 reconcileUserWithLock 复用同一把锁（防"读-判断-写"互相覆盖）
```

### ③ 真实事故（三段式，审计报告原文口径）

**事故 1：非法 cron → 任务"从未运行"（最经典）**
```
现象：refundTimeoutCheckJob trigger_status=0 被禁用、xxl_job_log 零记录
排查链：DB 现值 schedule_conf='0/60 * * * * ?'（与文档口径不符）
      → 手动启用后数秒被回写为 0（复现 2 次）
      → admin 日志命中 CronExpression.checkIncrementRange 异常（秒字段增量 60 越界，必须 <60）
      → 调度器生成下次触发时间抛异常 → 按设计自动置停用
修复：'0 * * * * ?' + trigger_status=1 → 验证连续 trigger_code=200 / handle_code=200
```

**事故 2：每日任务静默丢弃（misfire=DO_NOTHING）**
```
现象：feedCleanup（3:00）零执行；recommendItemCF/paymentReconcile 仅手动触发
根因：环境每日凌晨关机，DO_NOTHING 把错过窗口直接丢弃（仅 admin 日志 WARN，业务无感）
修复：三个每日任务改 FIRE_ONCE_NOW（恢复后补跑一次）；高频任务保持 DO_NOTHING（天然追平）
```

**事故 3：日报"633 失败"是口径陷阱**
```
xxl_job_log_report 显示失败 633 条，拆解：
  绝大多数 trigger_code=500（调度失败——服务重启窗口执行器不在线）
  其余 handle_code=0（任务体未显式上报，非失败）
  真实业务失败仅 2 条（handle_code=500）
→ 看报表必须区分"调度失败/未上报/业务失败"
```

**历史事故 P-D20**：19 任务仅 ~8 可调度（任务挂在 sample 组、地址 NULL）→ `init-xxljob.sql` 修复（建组+改 job_group+补任务）——"部署后任务要审计"的教训。

### ④ 兜底

- **任务内**：Redis 锁（多实例互斥）+ 幂等/条件更新（重复执行安全）——锁只保证"通常单实例"
- **任务设计**：对账任务"差异可自愈"；补偿任务"失败下轮再来"
- **运维**：misfire 分级（每日任务 FIRE_ONCE_NOW）；`xxl-job-health-check.sh` 巡检；日报按 code 拆解
- **主动披露的边界**：admin **单实例无 HA**；任务失败**无独立告警**（依赖 admin 邮件/人工）

### ⑤ 拷打追问（面试官视角）

1. **"调度中心挂了会怎样？"** 排期触发停止派发（任务全暂停）；执行器与业务不受影响。admin 本项目单实例（边界），生产应双节点 + DB 行锁防重复调度。
2. **"执行器挂了？"** `trigger_code=500`（无可用地址）；服务重启窗口高频任务会积调度失败日志——日报要拆解口径。
3. **"SERIAL 和 DISCARD_LATER 区别？"** 前者排队（怕漏触发），后者丢弃后续（怕堆积）——对账类用 SERIAL，长任务用 DISCARD。
4. **"为什么不用分片广播？"** 数据量小且要全局一致视图；加锁单实例更简单，避免分片半成功合并。
5. **"cron 有什么坑？"** 实测：`0/60` 增量越界**被静默禁用**（业务侧零感知）；上线前用 admin"下次执行时间"预览校验。
6. **"任务重复执行怎么办？"** 前提任务幂等（唯一键/条件更新/去重）；锁失效或人工触发时幂等兜底。
7. **"怎么保证没漏跑？"** 每任务 trigger_last_time 审计 + 日报 + 关键任务告警——本项目就是靠"零日志"发现的。
8. **"日志保留？"** admin 清理任务；默认约 30 天（生产按合规调）。

### ⑥ 话术

> "XXL-Job 我们 24 个任务、10 个执行器组：高频补偿 30 秒级、每日对账凌晨、离线计算各自分层。多实例互斥不用分片广播，任务里 Redis SETNX 加 token 比对删除——任务幂等才是底线。踩过三个典型坑：一个任务 cron 写成 `0/60`，秒字段增量越界，admin 直接把它自动禁用（'任务存在但从未运行'）；每日任务在关机窗口被 DO_NOTHING 静默丢弃，改 FIRE_ONCE_NOW 补跑；日报 633 条失败拆开看，真业务失败只有 2 条。admin 单点、无独立告警是主动披露的边界。"

---

## 生产陷阱（XXL-Job 8 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **非法 cron 静默禁用** | 增量越界（如秒 `0/60`）→ admin 自动停用、零日志——上线前预览下次执行时间 |
| 2 | **misfire 静默丢弃** | 默认 DO_NOTHING——每日任务改 FIRE_ONCE_NOW |
| 3 | **日报口径混淆** | fail_count 混"调度失败/未上报/业务失败"——按 trigger_code/handle_code 拆解 |
| 4 | **任务不幂等** | 锁失效/人工触发就重复——唯一键/条件更新兜底 |
| 5 | **部署后不审计** | 任务挂错组/地址 NULL（P-D20：19 任务仅 8 可调度）——init SQL + 巡检 |
| 6 | **admin 单点** | 挂了全站任务停派——双节点 + DB 行锁 |
| 7 | **无失败告警** | 任务失败无人知——接告警通道（本项目缺口） |
| 8 | **长任务堆积** | SERIAL 排队雪崩——拆分任务/DISCARD_LATER |

---

## 发散追问地图（横向）

- **调度原理**：时间轮/DB 扫描、admin 选主（DB 悲观锁）、执行器注册心跳
- **任务治理**：幂等设计、长任务拆分、失败重试、慢任务告警
- **对比选型**：Quartz（重、单机）、Elastic-Job（ZK 分片）、PowerJob（新一代）、K8s CronJob（云原生）、Temporal（工作流）
- **分布式协作**：分片广播实现（shardIndex/shardTotal）、执行器灰度/版本
- **可观测**：trigger/handle 指标、邮件告警、日志保留策略

---

## 面试官评分点

**高级开发级**：调度/执行分离、阻塞与 misfire 策略、任务幂等底线。
**架构师加分**：**非法 cron 的完整排查链**（现象零日志→状态回写→checkIncrementRange 栈→修复验证）；"分片 vs 加锁"的选型论证；日报口径拆解思维；misfire 分级策略；admin 单点边界的诚实披露。
**危险信号**：任务不幂等只靠不重复触发；不知道 misfire；把调度失败当业务失败；cron 不校验。

---

## 本项目真实证据

**xhs-project**：
- 配置：admin 2.4.2 @18080、accessToken、10 执行器组（端口 9990-9999，实测 notification=9990/coupon=9995/analytics=9999）；`init-xxljob.sql`（P-D20）
- 24 个 `@XxlJob`：order（close/retry/deadLetter/mappingRepair/calibration/aftersale）、payment（timeout/notify/reconcile/refund×2/settlement×2）、inventory/coupon/cart/counter/search（recommend×3）/notification/analytics/home
- 任务锁：`CartReconcileJob:62-96`（SETNX+token+Lua 比对删除 + 管理端点复用）
- 审计报告：`docs/reports/xxl-job-schedule-audit-20260918.md`（三发现 + 修复 + 验证 200/200 + 边界）
- 巡检：`scripts/xxl-job-health-check.sh`
- 观察：`RefundTimeoutCheckJob:31` 代码注释仍写旧值 `0/60 * * * * ?`（stale）——**以 admin DB 为准**（口径纪律）

**源码语料**：`code/spring/xxl-job` **2.4.2**（与 admin 版本一致）。

**课程对照**：xhs 46-XXLJob深度拷打。

---

## 版本与来源

XXL-Job **2.4.2**（admin 运行态 + `code/spring/xxl-job` 语料）；官方文档；xhs 审计报告与任务代码。

## 真实性说明

24 任务/10 组/锁实现/cron/日志统计/修复前后对比均来自代码、admin DB 与审计报告；"审计时 20 任务→当前 24"（差异 4 个新任务）已标注；admin 单点/无告警/注释 stale 主动披露。
