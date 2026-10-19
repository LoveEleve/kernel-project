---
title: "06-xhs自研trace全景对照"
series: "Tracing 链路追踪系列"
previous: "05-log-mdc-traceid.md"
next: "07-全链路可观测.md（待写）"
source_refs:
  - "common/trace/TraceContext.java（39 行：6 字段）"
  - "common/trace/TraceContextHolder.java（190 行：ThreadLocal+MDC+SW 三同步）"
  - "common/config/TraceIdConfig.java（114 行：order=-100 入口拦截器）"
  - "common/trace/FeignTraceInterceptorConfig.java（57 行：6 头透传）"
  - "common/trace/MqTraceHelper.java（165 行：MQ 双向）"
  - "common/trace/MdcAwareExecutorService.java（69 行，O2）+ AsyncConfig TaskDecorator"
  - "common/trace/ShadowTableInterceptor.java（压测影子表）"
  - "gateway/filter/TrafficColoringFilter.java（169 行：网关染色入口）"
---

# 06-xhs自研trace全景对照

## §0 它干了什么（例子先行）

一次下单请求，自研 6 个头跟着走完全程：

```
网关：无 X-Trace-Id → 生成（UUID 去横线）→ 染色（gray=stable/api=v1/ab 按 userId 散列分组）
  → 订单（TraceIdConfig 读头进 Holder）→ Feign 调支付（拦截器从 Holder 读头带上）
    → 支付（读头进 Holder）→ 发 MQ（MqTraceHelper 写消息属性）
      → 消费（恢复 Holder+MDC）→ 业务查 Holder.isPressureTest()（false，走正常表）
```

- **它干了什么**：6 个标记（1 个追踪号 + 5 个业务标记）跟着请求跨服务、跨 MQ、跨线程，任何业务代码 `TraceContextHolder.getXxx()` 直接读。
- **输入/输出**：输入=网关染色（或无头自生）；输出=全链可查日志 + 业务免传参 + 压测自动隔离。
- **和标准的关系**：W3C 传"调用树"，自研传"业务决策"——两套解决的不是同一个问题（§2 展开）。

## §1 掌握卡（4 条）

1. **自研传的是"染色 6 件套"**（染色 = 给流量打标记）：traceId（串日志）+ userId/grayTag/apiVersion/abGroup/pressureTest（下游直接做路由/实验/隔离决策，不用调用户中心）。
2. **存是三同步**：`TraceContextHolder`（ThreadLocal）+ MDC（日志）+ SW correlation（Span Tag）一次 `set()` 全写；`snapshot()` 必须深拷贝；`clear()` 三处全清。
3. **传有四条路**：HTTP 入（`TraceIdConfig`，order=-100，无头自生）→ Feign 出（读 Holder 不读 Request）→ MQ（同名头进消息属性，MDC 兜底）→ 线程（TaskDecorator 全量 + MdcAware 只保 MDC）。
4. **用在三处**：压测隔离（命中走影子表，§6 展开）、灰度/AB 路由、SW 双号互查（静态缓存桥接，A2 修过性能坑）。

## §2 标准做不到的三件事（为什么自研）

1. **业务标记无处放**：W3C 的 `traceparent` 只传 trace/parent/采样位；Baggage 能传但 Boot 3.2.5 只支持配远程、且要每个服务配 `remote-fields`——6 个头写死在拦截器里，零配置、全服务统一。
2. **下游要"直接读"**：灰度路由、AB 分组、压测判断发生在业务代码里——调用户中心/配置中心太重，`Holder.getGrayTag()` 一行搞定。
3. **双号互查要缝合**：SW 的号和自研的号互不知（05 结论）——`set()` 时顺手把自研号写进 SW correlation，SW UI 里能按业务号搜。这步标准里没有，只能自研。

## §3 6 头全表

| # | 字段 | 头名 | 值 | 用途 |
|---|---|---|---|---|
| 1 | traceId | `X-Trace-Id` | 32 位 UUID 去横线 | 串全链日志 |
| 2 | userId | `X-User-Id` | 用户 ID | 下游直接拿当前用户 |
| 3 | grayTag | `X-Gray-Tag` | beta/stable（默认 stable） | 灰度路由（beta=新版试水，stable=现网） |
| 4 | apiVersion | `X-Api-Version` | v1/v2（默认 v1） | 多版本路由 |
| 5 | abGroup | `X-AB-Group` | A/B/C（默认按 userId hash） | 推荐/UI 实验 |
| 6 | pressureTest | `X-Pressure-Test` | true/false | 压测隔离写影子表 |

（`TraceContext.java:23-38`；三处头名常量：`TraceIdConfig:35-40`、`FeignTraceInterceptorConfig:21-26`、`MqTraceHelper:36-41`——HTTP 与 MQ **同名**，故意的：记忆一套。）

## §4 存：Holder 三同步

```java
// TraceContextHolder.java:19-22
private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();
private static final String MDC_TRACE_KEY = "traceId";

public static void set(TraceContext ctx) {
    CONTEXT.set(ctx);
    injectSkyWalkingCorrelation(ctx);  // ← 顺手写 SW
}
```

- **后台线程开新链**：`startNewTrace()`（:32-38）——定时任务/补偿 Job 无入口，自己生 traceId、写 Holder+MDC。finally 必须 `clear()`。
- **跨线程必须深拷贝**：`snapshot()`（:119-132）——直接传引用，父线程 `clear()` 后子线程拿到 null。6 字段逐个拷。
- **清理三清**：`clear()`（:69-73）——ThreadLocal.remove + MDC.remove + SW correlation remove。三层防泄漏：HTTP（拦截器 afterCompletion）、MQ（Consumer finally）、异步（TaskDecorator finally）。
- **便捷读**：`isPressureTest()` / `getGrayTag()`（默认 stable）/ `getTraceId()` / `getUserId()`——业务代码只调这四个。

## §5 传：四条路

### 入：TraceIdConfig（order=-100，最先跑）

```java
// TraceIdConfig.java:62-95（preHandle）
String traceId = request.getHeader(TRACE_ID_HEADER);
if (traceId == null || traceId.isEmpty()) {
    traceId = generateTraceId();   // 无头自生：32 位 UUID 去横线
}
// ...其余 5 头照单读（null = 未染色）
TraceContextHolder.set(ctx);       // 进 ThreadLocal（顺带 SW）
MDC.put(TRACE_ID_MDC_KEY, traceId);// 进日志
response.setHeader(TRACE_ID_HEADER, traceId);  // 回写：前端/调用方可查
```

afterCompletion 清 Holder + MDC（防线程复用串号，05 §3 同款道理）。O1 修过：userId 也进 MDC（此前 logback 声明了 userId 字段但恒空）。

### 出：Feign 读 Holder，不读 Request

```java
// FeignTraceInterceptorConfig.java:37-49
TraceContext ctx = TraceContextHolder.get();  // ← 不读 HttpServletRequest
if (ctx == null) return;                       // 定时任务触发：不断，不断就不传
setHeaderIfPresent(template, TRACE_ID_HEADER, ctx.getTraceId());
// ...其余 5 头（空值跳过，不发空头）
```

**为什么不读 Request？** MQ 消费者触发的 Feign 调用没有 `HttpServletRequest`，但有 `TraceContext`——读 Holder 两路通吃。这是 OpenFeign 04 那套拦截器机制的自研用法（呼应 04 §5）。

### MQ：同名头进消息属性

```java
// MqTraceHelper.java:57-78（发送）/ 98-116（消费）
wrapWithTraceContext(msg);   // Holder 6 件套 → Message Header（Holder 为空兜底读 MDC）
restoreTraceContext(msgExt); // userProperty → Holder + MDC（+userId 进 MDC，O1 同款）
clearTraceContext();         // Consumer finally 必调
```

旧 API（`wrapWithTraceId` 等）标 `@Deprecated` 保留——只调新实现，老代码不报错。

### 线程：TaskDecorator 全量 + MdcAware 保 MDC

- `AsyncConfig`（Spring 异步）：`TaskDecorator`（Spring 给线程池留的"任务包装钩子"）提交时拷父线程上下文（TraceContext + MDC），子线程恢复、finally 清——对标 03 的 `wrap`。
- `MdcAwareExecutorService`（O2，手写线程池）：`execute()` 抓 `MDC.getCopyOfContextMap()`，子线程恢复、finally `MDC.clear()`——注意它**只拷 MDC 不拷 TraceContext**：子线程日志有号，但 `Holder.get()` 是空。所以：要读 Holder 的任务走 TaskDecorator 池；只要日志不断号，MdcAware 就够。

分工记住：要业务决策（读 Holder）走 TaskDecorator 池；只要日志不断号，MdcAware 就够。

## §6 用：影子表 + SW 缝合

**压测隔离**（`ShadowTableInterceptor`，MyBatis 拦截器 = SQL 改写钩子）：`isPressureTest()==true` → SQL 表名加 `_shadow` 后缀。默认关闭（`myxhs.shadow.enabled=true` 才生效）——正常流量零影响。

**SW 缝合**（`TraceContextHolder:150-189`）：反射调 SW 的 `putCorrelation("traceId", ...)`，无 agent 静默降级。A2 性能故事：原实现每请求 `Class.forName` + `getMethod`，无 agent 时每请求抛异常还争类加载锁——压测 92/99 个 Tomcat 线程 BLOCKED（阻塞），吞吐 ~1k RPS（每秒请求数）。修复：Method 静态缓存一次，每请求只剩一次字段判断。**教训：跨界调用先缓存句柄，别在热路径上反射。**

## §7 和标准差在哪（对照表）

| 维度 | W3C/标准 | xhs 自研 |
|---|---|---|
| 传 parentId（调用树） | ✅ | ❌（只有 traceId，树拼不起——02 §6 结论） |
| 传采样位 | ✅ | ❌（采样规则自研侧，07 讲） |
| 传业务标记 | Baggage（要配） | ✅ 6 头写死，零配置 |
| 进程内上下文 | CurrentTraceContext（scope 栈） | TraceContextHolder（ThreadLocal，无栈——自研 span 不嵌套） |
| 跨线程 | wrap | TaskDecorator + MdcAware |
| SW 互查 | 无 | correlation 缝合（独创） |
| 日志 | MDC 自动 | MDC 自动（同款，05） |

一句话：自研是"够用主义"—— traceId + 业务标记 + 日志，解决查日志和业务决策；调用树和采样一致性没做（02 §6 早说过）。

## §8 边界（这里一句话翻篇）

- `ai-app` 的 `TraceDiagnosisService` 等诊断服务（用 trace 数据做 AI 诊断）→ 非本系列范畴。
- `TaskDecorator` 实现、`TrafficColoringFilter` 全流程（169 行）→ 用时再查。
- 影子表运维（建表/TRUNCATE）、采样配置 → 07。

## §9 Self-Test（4 题）

- Q1：Feign 拦截器为什么读 Holder 不读 Request？（从 MQ 触发答）
- Q2：`snapshot()` 为什么必须深拷贝？不拷的后果？
- Q3：`MdcAwareExecutorService` 只传 MDC 不传 TraceContext——什么任务够用，什么任务不够？
- Q4：自研相对 W3C 少了哪两样？各对应什么代价？（02 Q4 同款，检验记没记住）

## §10 衔接

- 生产对照线：06（自研全景）→ 07（可观测：三件叠加 + 采样配置）。
- 下篇 07 讲：SW agent 配置、采样率、三件数据怎么查、压测影子表运维。
