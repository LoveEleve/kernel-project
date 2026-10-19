---
title: "06-xhs生产对照：四类任务模式"
series: "XXL-JOB 分布式任务调度系列"
previous: "05-scheduler-internals.md"
next: "07-面试收口.md（待写）"
lab_refs:
  - "docs/中间件拷打/K13-XXL-Job.md（②任务分层表+④兜底+话术）"
source_refs:
  - "xhs 28 个 job 类文件名（find 实证）+ CartReconcileJob 水位线（:55-61）"
---

# 06-xhs生产对照：四类任务模式

## §0 它干了什么（例子先行）

xhs 的 28 个 Job 类按**职责**归成四类模式，每类一套设计定式：

```
补偿：失败的消息/动作再推一把（NotifyCompensate/InventoryCompensation/FeedMessageRetry）
对账：两个数据源互相核对并修复（*Reconcile/Repair × 8）
超时：到点没发生就推动它发生（TimeoutCheck/Expire/Expiry × 6）
Outbox 发送：扫 outbox 表把未发事件发 MQ（*OutboxSender × 2）
```

- **它干了什么**：调度框架只是扳机；**四类模式才是"任务该怎么写"的答案**。
- **本篇回答**：四类各解决什么？任务怎么分层？和 @Scheduled 怎么分工（01 回收）？

## §1 掌握卡（3 条）

1. **四类模式**：补偿（"失败下轮再来"——靠幂等，04 §3）/ 对账（"差异可自愈"——靠条件更新）/ 超时（"到点推动"——靠状态机判断）/ Outbox 发送（"至少一次投递"——靠去重表）。**共同底线：任务幂等**（K13 话术："任务幂等才是底线"）。
2. **三层频率**（K13②分层表）：高频补偿 30s~5min（localMessageRetry/paymentTimeoutCheck…）/ 分钟级巡检 10min（counterRepair/unreadReconcile…）/ 每日对账离线 2:00-4:00（五路 Reconcile+feedCleanup）。
3. **与 @Scheduled 的分工回收**（01 §4）：TccTimeoutJob 留在 @Scheduled 因为自带 Redisson 锁+秒级简单；进了 XXL 的都吃框架红利（日志/手动触发/misfire）。

## §2 四类模式定式

| 模式 | 判据（扫什么） | 动作 | 幂等靠什么 | 代表 |
|---|---|---|---|---|
| 补偿 | 失败/未送达记录 | 重发/重推 | 去重键/状态位 | paymentNotifyCompensate |
| 对账 | 两源差异 | 条件更新修复 | 条件更新（版本/状态守卫） | cartReconcileJob |
| 超时 | 到期未流转 | 推进状态机 | 状态 CAS（比较并交换：状态没被别人改过才更新） | paymentTimeoutCheck |
| Outbox 发送 | outbox 未发标记 | 发 MQ+标已发 | 标记位翻转 | inventoryOutboxSender |

**对账的水位线技巧**（CartReconcileJob :55 实锤）：`watermark-seconds=300`——只对账 5 分钟前的数据，**跳过太新的**（并发写还在进行中，对了也白对/误报）。时间戳门槛是"对账和写流量解耦"的低成本手段。

## §3 任务分层表（K13②口径）

```
高频补偿：localMessageRetry(30s)、paymentTimeoutCheck(30s)、orderClose(1min)、
         refundTimeoutCheck(1min)、paymentNotifyCompensate(2min)、refundNotifyCompensate(3min)、
         couponExpire(5min)、orderMappingRepair(5min)
分钟巡检：followCounterRepair(10min)、unreadReconcile(10min)、recommendHotPool(10min)
每日离线：五路 Reconcile(2:00-4:00)、feedCleanup(3:00)、recommendItemCF(2:00)
```

分层的意义（和 04 呼应）：**频率决定 misfire 策略**（高频 DO_NOTHING / 每日 FIRE_ONCE_NOW）+ **优先级决定告警**（补偿挂了→资损风险→先接告警）。

## §4 兜底全景（K13④汇总）

```
任务内：Redis 锁（互斥）+ 幂等/条件更新（重复安全）
任务设计：对账"差异可自愈"、补偿"失败下轮再来"
运维：misfire 分级 + health-check.sh 巡检 + 日报双码拆解
主动披露缺口：admin 单实例无 HA、失败无独立告警
```

## §5 边界（一句话翻篇）

- 各 Job 类全文 → xhs 源码按名索引。
- xxl-job-health-check.sh 内容 → 运维仓库。
- 水位线参数调优 → 用时看配置。

## §6 Self-Test（4 题）

- Q1：四类模式各扫什么、幂等靠什么？
- Q2：水位线 300s 防什么？（对账和并发写解耦）
- Q3：为什么"频率决定 misfire 策略"？
- Q4：四类的共同底线是什么？锁和幂等谁是主谁是辅？（幂等主，锁只是通常单实例）

## §7 衔接

- 生产线收口；下篇 07 面试收口（K13 五段式回链 + 陷阱 8 条 + 终检）。
