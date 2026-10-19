# Z: Micrometer Tracing 面试题速答（收束篇）

> 定位：tracing-lab 收束篇——把 A（生产使用）/B（扩展点）/D（核心原理）的结论面试化。
> 基线：**Boot 3.2.5 / micrometer-tracing 1.2.5（bridge-brave）**（xhs-project 口径；1.3+ 特性一律标注）。
> 题库锚点：手册 Ch27「可观测性与故障诊断」10 题（331-340）。
> 用法：先自测（盖住答案），答不出的回对应文档。

---

## 一、速记卡（一页纸）

1. **SPI + 桥接**：`Tracer/Span/TraceContext/...` 是 micrometer-tracing 的 SPI；`bridge-brave`（12 个包装类）包装 Brave 原生 API——换 OTel 只换 jar。
2. **Tracer 9 个直接方法**：`nextSpan()/nextSpan(parent)`、`spanBuilder()`、`withSpan()`、`startScopedSpan()`、`currentTraceContext()`、`currentSpanCustomizer()`、`currentSpan()`、`traceContextBuilder()`（+ 继承 BaggageManager）。
3. **Span 三态**：Builder（收集配置）→ start（活跃，可 tag/event）→ `end()`（上报）/ `abandon()`（**结束但不上报**）。
4. **TraceContext 不可变**：traceId/parentId/spanId/sampled——`sampled()` 可为 null（延迟决策）；根 span `parentId()==null`。
5. **CurrentTraceContext**：`newScope`（总是压栈）/ `maybeScope`（相同则 NOOP）/ `wrap(executor)`（提交时**快照** traceId）；Scope 关闭是 **LIFO pop**。
6. **传播**：W3C（`traceparent`+`tracestate`）vs B3/B3_MULTI；Boot 默认 **produce=W3C、consume=全部**；`extract()` 返回 **Span.Builder**（不是 Span）；格式错误返回 EMPTY（不阻塞业务）。
7. **Baggage 三类**：remote（传播）/local（仅本地）/tag（进 Span）；`createBaggageInScope` 一步创建+激活；**Boot 3.2.5 只支持 `remote-fields`**。
8. **Observation 桥接三 Handler**：Receiver(Order **1000**) → Sender(Order **2000**) → Default(**LOWEST_PRECEDENCE-1000**)；**inject 在 onStart**（不是 onStop）。
9. **Brave 桥接**：`BraveSpan.end()=delegate.finish()`、`abandon()=delegate.abandon()`；Link 用 extra tags 编码（`links[i].traceId`）。
10. **采样在 trace 创建时**：`ProbabilityBasedSampler`（100 位 BitSet，精确率）/`RateLimitingSampler`（token bucket）；Boot 默认用 Brave 的 `Sampler.create(probability)`。
11. **Span 导出**：`CompositeSpanHandler.end()`（Cause=FINISHED 才处理）→ Predicate 过滤 → Filter 变异 → Reporter 上报（fire-and-forget）。
12. **零代码开启**：`BraveAutoConfiguration`（brave.Tracing + BraveTracer）→ `MicrometerTracingAutoConfiguration`（3 Handler + SpanAspect）→ `NoopTracerAutoConfiguration` 兜底；MDC 由 `LogCorrelationEnvironmentPostProcessor` + ScopeDecorator 写入。

---

## 二、分组高频题

### A. Tracer / Span 生命周期

**A1. `nextSpan()` 和 `spanBuilder()` 怎么选？**
`nextSpan()` 一键创建（自动从当前 scope 取父）；`spanBuilder()` 精细控制——传播场景先 `extract()` 再补充 name/kind 后 `start()`。回链：D1 §二。

**A2. `abandon()` 和 `end()` 的区别？**
`end()` 结束+上报；`abandon()` 结束但**不上报**（Cause=ABANDONED，CompositeSpanHandler 直接跳过）。容器 span（无业务数据的空壳）用 abandon。回链：D1 §3.1。

**A3. `isNoop()==true` 时还能拿 traceId 吗？**
能（传播需要）——Noop Span 保留 traceId，只是丢弃 span 数据。**陷阱**：生产 10% 采样时大量代码在处理 Noop Span，`isNoop()` 判断要测试。回链：D1 §3.3。

**A4. `startTimestamp()` 有什么用？**
异步场景回填起始时间（消息入队时刻）。**陷阱**：填未来时间（时钟偏差）→ duration 负数 → Zipkin/Jaeger 拒收。回链：D1 §3.5。

**A5. `Tracer` 为什么继承 `BaggageManager`？**
实用主义——Baggage 必须绑定当前 trace，只有 Tracer 知道"当前 trace 是什么"；合并后只注入一个依赖。回链：D1 Q5。

### B. TraceContext / CurrentTraceContext

**B1. `newScope` 和 `maybeScope` 的区别？**
`newScope` 总是压栈；`maybeScope` 相同 context 时返回 NOOP（避免冗余栈层）。回链：D3 §2.1。

**B2. `wrap(runnable)` 捕获的是什么时间的 traceId？**
**wrap() 调用时刻的快照**——不是执行时。5 秒后执行仍用提交时的 traceId。回链：D3 Q1。

**B3. scope.close() 为什么恢复上一个而不是清空？**
嵌套 span 的 LIFO 语义——inner 关闭后 outer 必须还在。回链：D3 §2.3。

**B4. `ThreadLocalSpan.remove()` 会 end span 吗？**
不会——remove 只释放 scope；end 是独立的生命周期操作（异步场景 end 常晚于 remove）。回链：D3 Q3。

### C. Propagation

**C1. W3C 和 B3 的区别？**
W3C：2 header（traceparent/tracestate），标准（2020）；B3：1 或 4 header，Zipkin 遗产。Boot 默认 produce=W3C、consume=全部（兼容老服务）。回链：D2 §一/§三。

**C2. `extract()` 为什么返回 Builder？**
此刻只有 traceId/spanId/sampled——name/kind 未定；Builder 允许 start 前补充。回链：D2 §四。

**C3. traceparent 格式错误会怎样？**
`parse()` 返回 null → `extract()` 返回 EMPTY → 创建新 trace。**不抛异常**（observability 不阻塞业务）。回链：D2 §六/Q1。

**C4. `traceparent` 的 128-bit traceId 和旧 64-bit 怎么兼容？**
高位补 0——Brave 原生 128-bit（traceIdHigh+Low），Zipkin 早期 64-bit。回链：D2 §2.1。

### D. Baggage

**D1. remote / local / tag 三类 baggage？**
remote：传播+MDC；local：仅 MDC（不传播）；tag：进 Span tag（可被 Zipkin 搜索）。**Boot 3.2.5 只有 `remote-fields` 属性**（local/tag 需手动构造）。回链：D6 §二。

**D2. `createBaggageInScope` 替代了什么？**
旧 API `createBaggage(name).set(value).makeCurrent()` 三步容易漏 makeCurrent；新 API 一步 + Closeable 自动恢复。回链：D6 §三。

**D3. 外部伪造 `baggage: role=admin` 怎么办？**
不信任！W3C baggage 无认证——网关提取后用认证信息覆盖再 inject。回链：D6 Q1。

**D4. Baggage 跨线程池能传播吗？**
不能——绑定当前线程 ThreadLocal；需 `currentTraceContext.wrap(executor)`。回链：D6 Q3。

### E. Observation 桥接

**E1. 三个 Tracing Handler 的执行顺序？**
Receiver(**1000**) → Sender(**2000**) → Default(**LOWEST_PRECEDENCE-1000**)——数值小的先执行（Boot 常量实证）。回链：D4 §一。

**E2. 为什么 inject 在 `onStart` 而不是 `onStop`？**
carrier（request/response）在 onStart 时状态最准确；onStop 时可能已序列化/发送，错过传播窗口。回链：D4 §五。

**E3. `TracingAwareMeterObservationHandler` 干什么？**
不是"加 traceId tag"——在**当前 span 的 scope 内**执行被包装的 meter handler，让 exemplar 通过 CurrentTraceContext 拿到 traceId。回链：D4 §六。

**E4. `@Observed` 同时产出 metrics+tracing 的原理？**
Observation 事件广播到所有 Handler——Meter handler 出 metrics、Tracing handler 出 span、Logging handler 出日志。回链：D3（micrometer 线）/D4 §七。

### F. Brave 桥接 / 采样 / 自动配置

**F1. 为什么需要 12 个包装类？**
Brave 是 final class——不能继承适配；每个 SPI 接口一个包装类持有 delegate 纯委托。回链：D5 §一。

**F2. `BraveTracer` 和 `BraveBaggageManager` 的循环引用？**
Baggage 的 scope 管理由 Tracer 的 CurrentTraceContext 负责——`setTracer(this)` 回引；都是单例，不泄漏。回链：D5 Q1。

**F3. 采样为什么在 trace 创建时？**
同一 trace 的所有 span 必须一致采样——否则调用树出现孤立 span/空洞。回链：D7 Q2。

**F4. Boot 3.2.5 的采样配置怎么生效？**
`management.tracing.sampling.probability` → `BraveAutoConfiguration.braveSampler()` → **Brave 的 `Sampler.create(probability)`**（traceId hash，非 BitSet 版）。回链：D7 Q1。

**F5. 没有 Zipkin/OTLP 时 span 数据去哪了？**
`NoopTracerAutoConfiguration` 不会触发（有 bridge 就有 Tracer）；span 数据在 Brave `MutableSpan` 中，end 后无 Reporter → 丢弃（内存不泄漏）。**日志 MDC 仍可验证 traceId**——这就是 tracing-lab 的验证方式。回链：D8 §四/Q6。

**F6. 为什么 BraveAutoConfiguration 要 `@AutoConfiguration(before=...)`？**
先建 `brave.Tracing`/`BraveTracer`，`MicrometerTracingAutoConfiguration` 的 `@ConditionalOnBean(Tracer)` 才成立——否则回退 NOOP，所有 Handler 不创建。回链：D8 §一。

### G. 生产与对照（xhs-project）

**G1. xhs-project 的 traceId 是怎么实现的？**
**自研**（无 micrometer-tracing）：`TraceContext`/`TraceContextHolder`（traceId 生成+持有）、`MqTraceHelper`（MQ 消息头传递）、`FeignTraceInterceptorConfig`（Feign 调用传递）、`MdcAwareExecutorService`（线程池 MDC 快照/恢复）、`TraceIdFilter`——概念上对应本 lab 的 TraceContext/Propagation/wrap。回链：D3 §二 + xhs 源码。

**G2. 自研 vs micrometer-tracing 怎么选？**
自研：零依赖、完全可控（xhs 场景）；标准：生态互通（Zipkin/OTel）、注解/自动配置、Baggage 能力全。**面试答题**：小团队/单一技术栈可自研（xhs）；跨团队/多语言选标准。回链：D8 + xhs 源码。

**G3. 异步任务丢 traceId 怎么排查？**
看线程池是否被 wrap/MDC 快照——`MdcAwareExecutorService`（xhs）或 `CurrentTraceContext.wrap()`（标准）。回链：D3 Q4 + D6 Q3。

---

## 三、陷阱速查（15 连）

| # | 反直觉点 | 正解 |
|---|---|---|
| 1 | `abandon()` 也会上报 | **不上报**（结束即弃） |
| 2 | Noop Span 没有 traceId | 保留 traceId（传播需要），只丢 span 数据 |
| 3 | `startTimestamp` 可以随便回填 | 未来时间→负 duration→后端拒收 |
| 4 | `wrap()` 执行时动态取 traceId | **wrap() 时刻的快照** |
| 5 | `maybeScope` 也压栈 | 相同 context 返回 NOOP（不压栈） |
| 6 | `extract()` 返回 Span | 返回 **Span.Builder** |
| 7 | traceparent 格式错误抛异常 | 返回 EMPTY，创建新 trace（不阻塞业务） |
| 8 | Boot 默认只 produce W3C | consume=**全部**（兼容 B3） |
| 9 | Boot 3.2.5 有 local-fields/tag-fields | **只有 remote-fields**（其余需手动构造） |
| 10 | 伪造 baggage 会生效 | 无认证——网关必须覆盖 |
| 11 | Baggage 自动跨线程池 | 不——需 wrap |
| 12 | inject 在 onStop | 在 **onStart**（carrier 状态窗口） |
| 13 | Receiver 比 Sender 后执行 | Receiver(**1000**) 先于 Sender(**2000**) |
| 14 | 采样在 span 创建时 | 在 **trace 创建时**（全 trace 一致） |
| 15 | 没有 Reporter 会内存泄漏 | MutableSpan end 后即释放（无泄漏，数据丢弃） |

---

## 四、文档索引（回链）

| 主题 | 文档 |
|---|---|
| Tracer API / Span 生命周期 / ScopedSpan / Builder | D1 |
| Propagation / W3C / B3 / BravePropagator | D2 |
| TraceContext / CurrentTraceContext / ThreadLocalSpan | D3 |
| Observation→Tracing 桥接 / 三 Handler / Order | D4 |
| Brave 桥接层 / 12 包装类 / LinkUtils | D5 |
| Baggage / remote-local-tag / MDC 集成 | D6 |
| Sampler / SpanHandler / SpanReporter | D7 |
| 自动配置 / 日志集成 / Noop 回退 | D8 |
| 生产使用端点（A01-A11）与扩展点（B01-B05） | a/ / b/ 源码 |
| 评审记录 | REVIEW-2026-10-05 |

---

## 五、一页纸收束

> **模型**：SPI（Tracer/Span/TraceContext）↔ bridge-brave（12 包装类）↔ Brave；换 OTel 只换 jar。
> **生命周期**：Builder → start → tag/event → end（上报）/abandon（弃）；采样在 trace 创建时。
> **上下文**：CurrentTraceContext（ThreadLocal + LIFO Scope）；wrap 快照式跨线程；TraceContext 不可变。
> **传播**：W3C/B3/B3_MULTI；produce=W3C + consume=全部；extract→Builder、失败→EMPTY。
> **Baggage**：remote（传播）/local（本地）/tag（Span）；Boot 3.2.5 仅 remote-fields；安全靠网关覆盖。
> **集成**：BraveAutoConfiguration → MicrometerTracingAutoConfiguration（3 Handler: 1000/2000/MAX-1000）→ Noop 兜底；MDC 日志零代码可用。
> **对照**：xhs-project 自研 traceId（TraceContextHolder/MqTraceHelper/MdcAwareExecutorService）——概念同源、实现不同。
