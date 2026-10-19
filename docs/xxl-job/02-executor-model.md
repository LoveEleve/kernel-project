---
title: "02-执行模型与注册发现"
series: "XXL-JOB 分布式任务调度系列"
previous: "01-why-not-scheduled.md"
next: "03-路由与分片.md（待写）"
lab_refs:
  - "docs/中间件拷打/K13-XXL-Job.md（①原理层+②项目用法）"
source_refs:
  - "xhs common/xxljob/XxlJobConfig.java（全文已读）"
  - "xhs payment application.yml:182-192（admin 18080/appname 9992/token）"
---

# 02-执行模型与注册发现

## §0 它干了什么（例子先行）

payment 服务启动时，只要 yml 三段：

```yaml
xxl:
  job:
    enabled: true
    admin:
      addresses: http://192.168.0.142:18080/xxl-job-admin
    executor:
      appname: my-xhs-payment
      port: 9992
    accessToken: my-xhs-xxl-job-token-2026
```

发生的事：`XxlJobSpringExecutor`（条件装配，enabled=true 才建）→ 把 `my-xhs-payment` + 本机 9992 **注册给 Admin** → Admin 的控制台里这个组亮了 → 24 个 `@XxlJob` 方法之一被 cron 点名时，Admin 找到在线地址、HTTP 触发 → 执行完**回调**结果码进日志表。

- **它干了什么**：三角色各司其职——Admin 决策与记录，执行器注册与执行，结果回流成日志。
- **本篇回答**：三角色分工？注册发现怎么做（为什么不需要 Nacos）？@XxlJob 怎么被找到？

## §1 掌握卡（3 条）

1. **三角色**：调度中心 Admin（管理/派发/日志/报表，K13①原话）+ 执行器（**业务进程内**：注册心跳/接收触发/回调结果）+ 任务（`@XxlJob` 标注的方法=JobHandler）。
2. **注册发现是 Admin 自带的**：执行器启动**主动注册** appname+地址到 Admin（DB 存），心跳续约（定时上报证明还活着）——**不依赖 Nacos/ZK**，Admin 就是它自己的注册中心；地址列表按 appname（组）归拢。
3. **一个任务的完整闭环**：cron 到点 → Admin 派发（按路由选一台在线执行器）→ HTTP 触发（执行器内嵌 server，9990-9999）→ JobHandler 执行 → 回调 trigger_code（调度结果）+ handle_code（执行结果）落 `xxl_job_log`。

## §2 三角色与数据流

```
Admin（18080）：CRON 计算 → 扫表派发 → 记日志报表
     ▲注册/心跳                    ▼HTTP 触发
执行器（9990-9999，业务进程内）：@XxlJob 方法被执行 → 回调结果码
```

**分离的收益回看 01 §3**：业务发版时 Admin 知道"组里没机器、要不要 misfire"；Admin 挂了已领到的任务照跑（K13④边界：Admin 单实例无 HA——它挂了只是不能**新派发**）。

## §3 xhs 接入三件（源码实锤）

`XxlJobConfig`（全文已读）：

1. **条件装配**：`@ConditionalOnProperty(xxl.job.enabled=true)`——没接入的服务零 Bean（Boot 条件注解的又一实例，Tracing 04 基础）。
2. **一个 Bean 装一切**：`XxlJobSpringExecutor`——admin 地址/appname/端口/token/日志路径/保留 30 天，全从 `@Value` 注入。
3. **每服务一组**：10 个执行器组（analytics/cart/counter/coupon/home/inventory/notification/order/payment/search），端口 9990-9999——**组=业务域**，和 01 §4 的"组"呼应。

**为什么日志落本地又落库**：执行日志文件在 `logpath`（/data/applogs/...，retention 30 天）；结果**码**在 Admin 的 `xxl_job_log`——排障先看码（05 讲报表口径，K13 事故 3 的"633 失败"就在这张表上）。

## §4 触发的两条路

| 路 | 谁发起 | 用途 |
|---|---|---|
| cron 自动 | Admin 定时扫描 `trigger_next_time` | 常态 |
| 手动触发 | 控制台按钮（可传参） | 救火/验证——01 四宗罪"无临时干预"的解药 |

## §5 边界（一句话翻篇）

- Admin 内部调度线程池/快慢池分离 → 05。
- 路由策略/分片 → 03。
- accessToken 鉴权细节 → 翻篇（token 字符串比对）。

## §6 Self-Test（3 题）

- Q1：三角色各干什么？为什么执行器要"内嵌 server"？（收 Admin 的 HTTP 触发）
- Q2：注册发现依赖 Nacos 吗？谁是注册中心？（Admin 自带，DB 存地址）
- Q3：@XxlJob 的方法在哪个进程里跑？trigger_code 和 handle_code 各记什么？

## §7 衔接

- 本篇回答"谁执行"；下篇 03 回答"派给谁"（路由策略全家 + xhs 为什么全用 FIRST+锁而不用分片广播）。
