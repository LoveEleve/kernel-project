# FEIGN-10 生产与收口

> 用途：生产全景（xhs 用法一览）+ 排障手册 + 面试收口（Z 七组映射本系列）
> 对应系列：**OpenFeign 专题 · 10**（生产篇 / 终篇）
> 口径：Spring Cloud OpenFeign 4.1.3 / feign-core 13.3
> 素材：xhs-project（FeignSafeConfig / 拦截器 / 31 降级工厂 / HC5 配置）· `openfeign-lab`（[Z 面试题](../../openfeign-lab/docs/Z-面试题速答.md) 七组 + 陷阱 15 连）· 本系列 01-09 的坑合辑
> 前置：01-09（本篇是收口，不引入新机制；只有一处 Z 文档带来的新细节）

---

## 0. 例子先行：xhs 一次服务间调用的"全副武装"

payment 调 order 的 `notifyPaySuccess`，从接口定义到降级兜底：

```text
@FeignClient(name = "my-xhs-order",
             fallbackFactory = OrderFeignFallbackFactory.class,     // 熔断降级 → R.fail + 补偿 Job
             configuration = InternalCallFeignConfig.class)          // 拦截器 → X-Internal-Call
   + RequestInterceptors（X-Internal-Call / trace 六头，04）
   + 超时 connect 3000 / read 5000（05）
   + HC5 池 200/50（05）
   + Retryer NEVER_RETRY（07）
   + Sentinel 熔断（feign.sentinel.enabled，06）
   + LB 选实例（SCLB，同内核见 LB 系列 04）
```

**发生错误时只走一条路**：超时/连接失败 → （不重试，NEVER_RETRY）→ Sentinel 熔断统计 → 失败 → `fallbackFactory.create(ex)` → `R.fail` → 调用方（payment）认领失败并触发**补偿任务**。

这就是生产全景的缩影——本篇把它拆成"怎么做/怎么查/怎么答"。

---

## 1. 掌握卡（3 条）

1. **生产配置五件套**：超时 / 日志 / 熔断开关（`feign.sentinel.enabled`）/ 重试立场（NEVER_RETRY）/ LB（默认 RR + Nacos 生态）——少配哪件，出事就从哪件开始查。
2. **排障三问**：**调不通？**（注册/扫描/bean/配置键）**调得慢？**（超时/池/HC5/重试放大）**调错了？**（ErrorDecoder/降级掩盖/前缀）——§4 是症状表。
3. **异常体系一页**：Transport（IO/超时）→ FeignException → Decoder/BizException → Sentinel BlockException → fallback → 补偿——**哪层异常进 fallback，06/08 已定**（catch Throwable，业务异常注意分流）。

---

## 2. xhs 用法全景（一页对照表）

| 能力 | xhs 落点 | 本系列 |
|---|---|---|
| 接口组织 | 按下游服务一个 `@FeignClient`（name/contextId/configuration/fallbackFactory） | 01/03 |
| 内部调用鉴权 | `FeignInternalCallInterceptor`（`X-Internal-Call` + fail-closed） | 04 |
| 链路透传 | `FeignTraceInterceptorConfig`（六头透传，ThreadLocal） | 04（交叉 `tracing-lab`） |
| 超时/池 | connect 3000 / read 5000；HC5 200/50 | 05 |
| 日志 | `logger-level: BASIC` + FQN DEBUG | 03 |
| 降级 | 31 个 `FallbackFactory`（全 `R.fail` + 补偿 Job） | 06 |
| 重试立场 | `FeignSafeConfig` 全局 `NEVER_RETRY`（幂等接口单独配） | 07 |
| 熔断 | `feign.sentinel.enabled` + Sentinel 规则 | 06 |
| 选实例 | 同 SCLB 内核（阻塞路径） | LB 系列 04/07 |
| 错误解码 | `RemoteCallErrorDecoder`（4xx/5xx 转业务异常） | 08 |
| 解包 | `FeignUnifiedConfig`（设计未接线，R 解包） | 08 |

> 注：`FeignUnifiedConfig` 的 R 解包是**设计**（未接线 + @Primary 疑点，08 已核）——实际客户端仍返回 `R<…>`，别当成现役路径读。

---

## 3. 排障手册（症状 → 查哪里）

| 症状 | 查 | 出处 |
|---|---|---|
| Bean 不存在（注入失败） | 扫描范围（`@EnableFeignClients(basePackages)`）/ 接口是否被扫到 | 02 |
| `configuration` 不生效/串了 | **三层与键名**：子上下文归属、`contextId` vs `name`（`getName`: serviceId>name>value；`getClientName`: contextId>value>name>serviceId——**寻址和配置是两套键**） | 02/03 |
| 日志没输出 | `logger-level`（默认 NONE）+ `logging.level.<接口FQN>: DEBUG`（两个都要） | 03 |
| 超时 | 超时分层（业务 10s/60s 默认 vs 客户端 2s；xhs: 3s/5s） | 05 |
| 全部变慢 | 池（HC5 200/50）/ 连接复用 / 下游 keep-alive | 05 |
| 无实例 503 | `"Load balancer does not contain an instance"` → 注册中心/名单（不是网络问题） | LB-07 / 07 |
| 重试放大 | 双层重试（Feign × LB）+ 读超时的乘法 | 05/07 |
| 降级没触发 | `feign.sentinel.enabled` 开了没（第一排查项） | 06 |
| 业务错误被兜 | `catch(Throwable)` 互动 → `create(cause)` 分流（06/08） | 06/08 |
| 旧前缀不生效 | `feign.client.*` → `spring.cloud.openfeign.*`（Sentinel 开关仍是 `feign.*`） | 01/03 |
| 429/限流 | Sentinel 规则（控制台/Nacos） | L45 |

---

## 4. 异常体系（一页）

```text
Transport 层：ConnectException / SocketTimeoutException（→ Retryable，默认可重试）
  → FeignException（ErrorDecoder 包装；xhs：4xx/5xx → BizException/RemoteException）
      → Decoder 段：R.fail（HTTP 200）→ BizException（xhs 解包设计）
      → Sentinel 段：BlockException（熔断/限流触发）
          → fallbackFactory.create(cause) → 兜底 R.fail
              → 调用方补偿任务（payment 的 CompensateJob）
```

**决策复述**（前面课程的结论）：

- 哪层异常进 fallback？**全部**（`catch(Throwable)`，06）；
- ErrorDecoder 何时？**非 2xx**（08）；
- Sentinel 统计何时？**非 Block 全 `Tracer.traceEntry`**（06）；
- 重试在哪层？**Client 内层**（fallback 只被触发一次，07）。

---

## 5. 面试收口（Z 七组 ↔ 本系列）

| Z 组 | 一句话答案 | 本系列 |
|---|---|---|
| A. 定位与声明式调用 | 接口+注解=客户端；四组件 | 01 |
| B. 代理创建链 | Registrar → FactoryBean → JDK 代理 → MethodHandler；子上下文 | 02/09 |
| C. 编码与解码 | 两段（Decoder 2xx / ErrorDecoder 非 2xx）；`R.fail` 属 Decoder；404/204 → `Optional.empty()`（Z-15） | 08 |
| D. 配置体系 | **属性赢 Bean**（默认 `defaultToProperties=true`）；属性层内部 name>default；配置键=contextId | 03（本次修正） |
| E. 负载均衡与重试 | 阻塞路径（`FeignBlockingLoadBalancerClient`）；重试两层；NEVER_RETRY；无实例=503 | LB-04/07/08 + 07 |
| F. 熔断与降级 | Sentinel 换 Builder；降级全 `R.fail`+补偿；catch(Throwable) 全兜 | 06 |
| G. 生产与排障 | 上面 §2-§4 全表 | 04/05/10 本篇 |

> 题库（37 题 + 15 坑）在 `openfeign-lab/docs/Z-面试题速答.md`——本表是"从本系列回去查"的索引。

---

## 6. 系列收尾（10 篇地图）

```text
理论：01 全景 → 02 机制
使用：03 配置 → 04 拦截器 → 05 超时客户端 → 06 容错 → 07 LB重试
进阶：08 响应处理/多版本/RestClient → 09 源码主干
生产：10（本篇）
```

- 回顾按钮：每篇 **§1 掌握卡 + Self-Test** 是复习入口；
- 深挖去处：`openfeign-lab`（A-01~A-09 / B-02~B-05 / D1~D7 / Z）；
- 相关线：**网关课程**（L37 网关重试/熔断、L16 染色、LB 系列）/ `tracing-lab`（链路透传）。

---

## 7. Self-Test（综合）

1. 给一个新服务接入 Feign（从接口到配置到降级），列出全部步骤与检查点。
2. 超时了但没重试、且被兜底了——依次经过了哪些开关？（超时配置 / Retryer / Sentinel 开关 / fallback）
3. 业务失败（`R.fail`）+ `fallbackFactory` 都配了，最终调用方看到什么？为什么？（两段 + catch 全兜）
4. 服务间调用慢了：排障顺序？（超时/池/重试/名单/降级）

---

## 8. 相关与衔接

- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[Z 面试题](../../openfeign-lab/docs/Z-面试题速答.md)（七组 + 15 坑）；
- `docs/loadbalancer`：[04 阻塞路径](../loadbalancer/04-sclb-architecture.md) / [07 摘除](../loadbalancer/07-instance-churn.md) / [08 重试两口味](../loadbalancer/08-retry-sticky-hash.md)；
- 网关课程：[L37](../gateway/L37-circuit-breaker-retry.md)（网关侧）/ [L45](../gateway/L45-sentinel.md)（Sentinel 规则）；
- 后续方向（本系列之外）：链路透传（tracing）、服务契约线（内部令牌体系）、K8s 下的服务发现。
