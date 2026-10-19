# D0: EagleEye 日志框架 — Sentinel 的内部诊断引擎

> 源码位置: `sentinel-core/src/main/java/com/alibaba/csp/sentinel/eagleeye/`
> 核心文件: `EagleEye.java`(235行) / `StatLogger.java`(145行) / `StatLogController.java`(190行) / `TokenBucket.java`(独立令牌桶)
> 前置: 独立模块，与流控/熔断核心零耦合（之前 review 已确认 `grep -r EagleEye slots/block/` 零匹配）

---

## 一、定位：独立的内部日志系统

EagleEye 是 Sentinel **自研**的日志/诊断框架（2197 行），不依赖 Logback/Log4j。它的职责是：

1. **统计日志**（`StatLogger`）：定时输出 Sentinel 内部监控数据到日志文件，供离线分析
2. **异步落地**（`EagleEyeLogDaemon` + `SyncAppender`）：后台线程批量刷盘，不阻塞业务线程
3. **令牌桶**（`TokenBucket`）：EagleEye 内部的流量控制——日志刷盘用令牌桶防止磁盘 IO 打满

**为什么不用 Logback/Log4j？** EagleEye 需要**极低延迟**的异步日志——Logback 的 `AsyncAppender` 基于 `BlockingQueue`（有锁竞争），EagleEye 实现了自己的无锁环形缓冲 + 后台线程刷盘。

---

## 二、核心类概览

| 类 | 职责 | 行数 |
|------|------|:--:|
| `EagleEye` | 入口 + 日志文件管理 + 自启动 | 235 |
| `StatLogger` | 统计日志（定时输出 Sentinel 内部指标） | 145 |
| `StatLogController` | 统计日志的滚动策略 + 时间窗口管理 | 190 |
| `EagleEyeLogDaemon` | 后台线程（异步拉取日志事件 + 批量刷盘） | 140 |
| `SyncAppender` | 同步刷盘器（FileChannel.force 强制落盘） | ~100 |
| `TokenBucket` | EagleEye 内部流量控制 | ~50 |

---

## 三、面试考点

**Q: Sentinel 的 EagleEye 是做什么的？和 Logback 有什么区别？**

EagleEye 是 Sentinel 自研的内部日志框架——不做应用日志（那是 Logback 的职责），只做 Sentinel 内部指标的采集和输出（StatLogger）。与 Logback 的区别：EagleEye 的异步日志是无锁环形缓冲（Logback `AsyncAppender` 有 `BlockingQueue` 锁竞争），刷盘是 `FileChannel.force` 保证数据不丢。

---

## 四、Sentinel 内的两个令牌桶实现

EagleEye 内置了 `TokenBucket.java`——这是 Sentinel 内部的**第二个令牌桶实现**（第一个是 D3 的 `ThrottlingController`）：`ThrottlingController` 基于 `Thread.sleep` 排队、`TokenBucket` 基于 `AtomicLong` + CAS 的纯内存令牌桶。
