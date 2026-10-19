# FEIGN-06 容错：降级与熔断

> 用途：Feign 的降级（`fallback`/`fallbackFactory`）与熔断（Sentinel 集成）；xhs 31 个降级工厂的组织与设计
> 对应系列：**OpenFeign 专题 · 06**（使用篇第四篇）
> 口径：SC OpenFeign 4.1.3 / SCA 2023.0.1.2（`feign.sentinel`）/ Sentinel 1.8.8
> 素材：`openfeign-lab`（[A-06 熔断 Sentinel](../../openfeign-lab/docs/A-06-熔断Sentinel.md) / [D6 Targeter-SentinelFeign](../../openfeign-lab/docs/D6-Targeter-SentinelFeign源码分析.md)）· xhs-project（`OrderFeignFallbackFactory` 等 31 个工厂）· SCA 源码（`SentinelInvocationHandler`）
> 前置：05（超时是"可重试/可降级异常"的来源）；后续：07（重试与降级的关系）→ 08（Decoder/ErrorDecoder 互动）

---

## 0. 例子先行：order 挂了，payment 怎么办？（xhs 真实代码）

payment 调 order 的 `notifyPaySuccess`，order 不可用时：

```java
// OrderFeignFallbackFactory（节选）
@Override
public OrderFeignClient create(Throwable cause) {
    log.error("[支付→订单] Feign调用降级: {}", cause.getMessage());
    return new OrderFeignClient() {
        @Override
        public R<Void> notifyPaySuccess(Long orderId, String tradeNo) {
            log.error("[支付→订单] 支付成功通知降级: orderId={}, 触发补偿任务", orderId);
            return R.fail(ResultCode.SERVICE_UNAVAILABLE, "订单服务不可用，等待补偿任务重试");
        }
        // …其余方法各自写兜底
    };
}
```

它的类注释给出了**降级的第一原则**（原文）：

> "所有降级方法都返回失败（R.fail），而不是静默成功。原因：支付和订单是强一致场景，降级后必须触发补偿任务，不能静默丢弃。**如果返回成功，调用方认为操作已完成，不会触发补偿，导致数据不一致。**"

配套：`PaymentCompensateJob` / `RefundCompensateJob` / `OrderCloseJob` 定时补偿。

两条线：**降级** = 失败后返回什么（fallback）；**熔断** = 什么时候不再调用、直接走降级（Sentinel DegradeRule）。

---

## 1. 掌握卡（3 条）

1. **`fallback` vs `fallbackFactory`**：`fallback` 是单例兜底实现（**拿不到异常**）；`fallbackFactory` 是工厂（`create(Throwable cause)` **能拿异常**，可按类型分流）——生产默认 Factory（xhs 全用 Factory）。
2. **Sentinel 路线的触发语义（源码级）**：`feign.sentinel.enabled=true` → `SentinelFeign.Builder` 换上 `SentinelInvocationHandler` → **任何 `Throwable`**（超时/连接拒绝/`FeignException`/**`BlockException`**）都会走 `fallbackFactory.create(ex)`；**没配 fallbackFactory 就原样抛出**。熔断只是"其中一种 Throwable"。
3. **降级底线**：**宁要"明确失败 + 补偿"，不要"静默假成功"**（xhs 全部 `R.fail` + 补偿 Job）；写操作的降级必须配幂等/对账。

---

## 2. 降级的两种写法

```java
// 写法 A：fallback —— 单例实现（拿不到异常信息）
@FeignClient(name = "demo-provider", fallback = DemoFallback.class)
public interface DemoClient { @GetMapping("/hello") String hello(); }

@Component
class DemoFallback implements DemoClient {
    @Override public String hello() { return "fallback"; }   // 日志里没有根因
}
```

```java
// 写法 B：fallbackFactory —— 工厂（推荐；能拿异常）
@FeignClient(name = "demo-provider", fallbackFactory = DemoFallbackFactory.class)
public interface DemoClient { @GetMapping("/hello") String hello(); }

@Component
class DemoFallbackFactory implements FallbackFactory<DemoClient> {
    @Override
    public DemoClient create(Throwable cause) {
        log.error("调用降级", cause);                         // ★ 有根因
        return () -> "fallback";                              // 可按异常类型分流
    }
}
```

| 选择 | 何时用 |
|---|---|
| `fallback` | 兜底逻辑与异常无关、且不需要排查根因（少用） |
| **`fallbackFactory`** | 要日志根因 / 按异常类型返回不同兜底 / 与补偿联动（**默认推荐**） |

**一个容易忽略的互动（源码级）**：`SentinelInvocationHandler` 是 `catch (Throwable)`——如果 Decoder/ErrorDecoder 把**业务失败**转成异常（如 08 会讲的 `FeignUnifiedConfig` 的 `BizException`），而该客户端又配了 `fallbackFactory`，**业务错误也会被兜进 fallback**（可能把"业务失败"掩盖成"服务不可用"）。要么这类客户端不配 fallback，要么在 `create(cause)` 里按异常类型分流/重新抛出。

---

## 3. 熔断：Sentinel 怎么接进 Feign（D6 展开）

**开关**（注意前缀——它是 **SCA 属性，仍是 `feign.*`**）：

```yaml
feign:
  sentinel:
    enabled: true      # 启用 Sentinel 作为 Feign 熔断器；FallbackFactory 才能生效
```

**装配链**：

```text
feign.sentinel.enabled=true
  → SentinelFeignAutoConfiguration（@ConditionalOnProperty + @ConditionalOnMissingBean）
      提供 Feign.Builder bean = SentinelFeign.Builder
  → FeignClientFactoryBean 用它构建客户端
      → Builder 覆盖 invocationHandlerFactory → 每个方法被 SentinelInvocationHandler 包住
```

**每次调用的骨架**（SCA 源码，删繁就简）：

```java
String resourceName = "POST:http://my-xhs-order/api/order/pay-success";   // 方法:url:path
Entry entry = null;
try {
    entry = SphU.entry(resourceName, EntryType.OUT, 1, args);      // ★ 资源入口
    return methodHandler.invoke(args);
} catch (Throwable ex) {
    if (!BlockException.isBlockException(ex)) Tracer.traceEntry(ex, entry);  // 供"异常比例"熔断统计
    if (fallbackFactory != null) return fallbackFactory.create(ex);          // ★ 任何异常都兜
    throw ex;
} finally {
    entry.exit(1, args);
}
```

**由此三点**：

- **资源名 = `HTTP方法:url:path`**——规则/Dashboard 里看到的就是它；
- **熔断（`DegradeRule`：RT/异常比例/异常数）触发时抛 `BlockException` → 走同一条 fallback 路径**（L45 实验 E 已实测过 RT 熔断）；
- 业务异常会被 `Tracer.traceEntry` 记录——这正是"异常比例"熔断统计的来源。

> 另一条路线是 Spring Cloud CircuitBreaker（Resilience4j 等）；SCG 侧用得多（L37），Feign 生产（xhs）走 Sentinel。两套别混用（同 LB 系列的"双份实现抢注"教训）。

---

## 4. xhs 的 31 个降级工厂（组织与设计）

**组织**：按下游服务分包（`payment.feign.OrderFeignFallbackFactory`、`analytics.feign.*` …），**每个下游一个 Factory**。

**从 `OrderFeignFallbackFactory` 提取的设计要点**：

| # | 要点 | 说明 |
|---|---|---|
| 1 | **逐方法写兜底** | 不是"一律返回 null"——按业务语义给不同兜底（通知类=失败+补偿；查询类=拒绝支付） |
| 2 | **全部 `R.fail`** | 绝不静默成功（类注释金句，§0） |
| 3 | **日志带根因与业务标识** | `log.error(..., cause / orderId / tradeNo)` |
| 4 | **与补偿 Job 配套** | `PaymentCompensateJob` / `RefundCompensateJob` / `OrderCloseJob`——降级只保"可用性"，**一致性靠补偿/对账** |

**31 个的数量说明**：降级是"按下游 × 按方法"的策略设计，不是模板批量生成——这也是它容易被写崩（假成功）的原因。

---

## 5. 坑清单

| # | 坑 | 说明 |
|---|---|---|
| 1 | 忘开 `feign.sentinel.enabled` | fallback 不生效（异常直接抛出去）——这是"配了 Factory 却没兜住"的第一排查项 |
| 2 | 用 `fallback`（非 Factory） | 拿不到异常 → 日志无根因、无法按类型分流 |
| 3 | `catch(Throwable)` 兜住业务异常 | Decoder/ErrorDecoder 抛的业务异常会被掩盖成"服务不可用"（§2 互动） |
| 4 | 降级"假成功" | 返回 `R.ok`/空对象 → 调用方以为完成 → 数据不一致（xhs 金句反面） |
| 5 | 写操作降级无补偿 | 必须幂等 + 定时补偿/对账兜底 |
| 6 | fallback 里再调远程 | 兜底路径再失败会放大问题——只做本地兜底/记录 |

---

## 6. Self-Test

1. `fallback` 和 `fallbackFactory` 的区别？生产默认用哪个、为什么？
2. `feign.sentinel.enabled=true` 后，一次调用的哪些异常会走 fallback？（熔断只是其中哪一种？）
3. 为什么 xhs 的降级全部返回 `R.fail` 而不是"默认值"？
4. "配了 fallbackFactory 却感觉没生效"先查什么？

---

## 7. 相关与衔接

- **07 LB 与重试**：超时（05）→ 重试（07）→ 熔断/降级（06）在同一条链上的次序与放大效应；
- **08 进阶**：`ErrorDecoder`/`FeignUnifiedConfig`（`R<T>` 解包 + BizException）——与"catch(Throwable) 互动"（§2）；
- **10 生产与收口**：降级的监控指标与排查；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[A-06 熔断 Sentinel](../../openfeign-lab/docs/A-06-熔断Sentinel.md) / [D6 Targeter-SentinelFeign](../../openfeign-lab/docs/D6-Targeter-SentinelFeign源码分析.md)；
- 网关课程：[L37](../gateway/L37-circuit-breaker-retry.md)（网关侧熔断/重试/降级对照）/ [L45](../gateway/L45-sentinel.md)（Sentinel 规则与熔断实测）。
