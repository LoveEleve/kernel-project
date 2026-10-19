# LB-08 重试·粘性·一致性哈希

> 用途：三类"亲和/兜底"机制——重试的两个口味（换台 vs 原台）、请求粘性、一致性哈希；以及"有状态服务"的正确姿势
> 对应系列：**负载均衡（SCLB）专题 · 08**（使用篇第五篇）
> 口径：SCLB 4.1.4 / Spring Cloud OpenFeign 4.1.3 / SCG 4.1.5
> 素材：`loadbalancer-lab`（A-09/A-11/A-12 实测）· 网关课程 [L29](../gateway/L29-sticky-session.md)（sticky 实测）/[L37](../gateway/L37-circuit-breaker-retry.md)（网关层重试）/ [L16](../gateway/L16-custom-loadbalancer.md)（染色≠选实例）· xhs-project（`FeignSafeConfig`: NEVER_RETRY）
> 前置：03（哈希/粘性概念）/ 05（same-instance 预设）/ 07（窗口期兜底）；后续：09（自定义 LB）→ 10（收口）

---

## 0. 例子先行：三个场景，三张"亲和/兜底"牌

```text
场景一（重试·换台还是原台？）
  请求打到实例 A 失败（刚抖动/刚挂）→ 重试：
  · 默认：换一台（避开刚失败的 A）        ← retry.avoid-previous-instance（缺省 true）
  · 可选：还找 A（大概率它只是抖了一下）    ← same-instance-preference 预设

场景二（粘性·会话在哪就去哪）
  用户会话存在实例 A 本地 → 希望该用户后续请求都带着 cookie 找 A（sc-lb-instance-id）

场景三（一致性哈希·按 key 固定）
  按 userId 现算：同一用户永远同一台（无需 cookie）——那台有它的本地缓存
  实例挂掉：该段 key 自动顺延到下一台（不像 cookie 失效）
```

三张牌解决的是**不同问题**：重试是"失败了怎么办"，粘性/哈希是"平常就固定给谁"。

---

## 1. 掌握卡（3 条）

1. **重试两口味**：默认"**换台**"（`retry.avoid-previous-instance` → `RetryAwareServiceInstanceListSupplier` 剔除上次实例；剔空则回退全量）；`same-instance-preference` 预设是"**粘原台**"——选哪个取决于"失败是永久还是瞬时 + 状态在不在实例上"。
2. **粘性 vs 一致性哈希**：粘性 = **记忆**（cookie，实例挂则失效）；哈希 = **按 key 现算**（实例挂自动顺延）。别混淆，也别叠加。
3. **有状态服务的正解是集中式 session（Redis）**——粘性/哈希都只是"优化或过渡"：它们不迁移状态，只决定"找谁"。

---

## 2. 重试：两条路 + 两个口味

### 2.1 重试发生在哪一层（三条路，别混）

| 层 | 实现 | 场景 |
|---|---|---|
| **LB 层（阻塞）** | `RetryLoadBalancerInterceptor`（@LoadBalanced RestTemplate）/ `RetryableFeignBlockingLoadBalancerClient`（Feign） | 服务间调用 |
| **LB 层（响应式）** | `RetryableLoadBalancerExchangeFilterFunction`（WebClient） | 响应式客户端 |
| **网关层** | SCG `Retry` filter（**L37 已讲**：重试在外、熔断在内） | 网关入口 |

> 本篇讲"LB 层重试"；网关层的重试细节（重试放大、退避、POST 保护）回 [L37](../gateway/L37-circuit-breaker-retry.md)。

### 2.2 两个口味（重试时"挑谁"）

| 口味 | 机制 | 适合 |
|---|---|---|
| **默认：换台** | `retry.avoid-previous-instance`（缺省 true）→ `RetryAwareServiceInstanceListSupplier`：重试请求（`RetryableRequestContext`）剔除**上次那台**；剔空则回退全量（lab A-12 实测：故障实例 28001 → 重试换 28003 成功 5/5；`retry.enabled=false` 时这层从链上消失） | 实例级故障/瞬时错——换一台更可能成功 |
| **粘原台** | `same-instance-preference` 预设 → `SameInstancePreferenceServiceInstanceListSupplier`：优先"上次返回的实例" | 缓存亲和——换台会缓存 miss；或"大概率抖动" |

**语义相反，别双开**（按需二选一）。

### 2.3 关键参数（默认值 + lab 实测坑）

**前置条件（lab A-12 踩坑）**：LB 层重试"**本身默认不生效**"——需要 ① classpath 有 `spring-retry`（拦截器靠 `@ConditionalOnClass(RetryTemplate.class)` 激活，没有它根本没有重试能力）；② 重试开关与触发条件显式配。

```yaml
spring.cloud.loadbalancer.retry:
  enabled: true                        # 默认开；关掉会同时移除 RetryAware supplier + 拦截器
  retry-on-all-operations: false       # 默认只重试 GET 类安全操作
  max-retries-on-same-service-instance: 0
  max-retries-on-next-service-instance: 1
  retryable-status-codes: 500          # ⚠️ 默认【空集合】——5xx 默认不重试，要显式配（lab A-12 实测）
  # retryable-exceptions 默认 IOException / TimeoutException（源码）
```

---

## 3. 请求粘性（sticky session）

**机制回顾**（05）：`RequestBasedStickySessionServiceInstanceListSupplier` 读 cookie（默认 `sc-lb-instance-id`）→ 指向的实例还在就只返回它；没有 cookie 正常挑、响应侧种 cookie（网关侧的完整实测见 L29）。

| | 适用 | 代价 |
|---|---|---|
| 粘性 | 实例本地有会话/临时状态；想少做一次"状态外置" | **实例挂 → 会话丢**；客户端换/清 cookie → 漂移；**负载不均**（粘住的实例可能过载）；扩容后老会话不会流到新实例 |

> 定位：粘性适合"**优化或过渡**"——把本地缓存利用起来；但它不是状态的容器。

---

## 4. 一致性哈希（按 key 固定）

**机制回顾**（03）：哈希环 + 顺时针 + 虚拟节点；实例变化只搬 ≈1/N。

**落地（SCLB 无内置，要自研）**——方向性示例：

```java
// 自研 ReactorLoadBalancer 的结构（示意）
public Mono<Response<ServiceInstance>> choose(Request request) {
    return supplier.get(request).next().map(instances -> {
        String key = extractKey(request);          // userId / sessionId / 缓存 key
        Ring ring = Ring.of(instances);            // 示意：实际应缓存 ring、仅在名单变化时重建（含虚拟节点）
        return new DefaultResponse(ring.getAfterClockwise(hash(key)));
    });
}
```

- **key 怎么选**：要满足"业务上同群请求该同台"——userId、商品 id、分片键；**热 key 是天然风险**（03 §6：打散/缓存/隔离）；
- **与粘性的选择**：无状态客户端/想免 cookie → 哈希；要"记住上次"（含跨 key 复用）→ 粘性；
- **落地场景**：本地缓存亲和（每台有 Caffeine/本地字典）、同 key 顺序性（谨慎：哈希只保证"同台"，不保证"同线程/顺序"）；
- 手写实验（`ConsistentHashLoadBalance` 移植）属被砍的 C 类——需要时从 sca-lab git 恢复（09 展开扩展点）。

---

## 5. 有状态服务三件套（正解框架）

| 方案 | 状态放哪 | 实例挂掉的表现 | 定位 |
|---|---|---|---|
| **集中式 session** | Redis（外部） | 无感（换台照样读） | ✅ **正解** |
| 粘性 session | 实例本地 + cookie | 会话丢 | 过渡/优化 |
| 一致性哈希 | 实例本地 + 按 key 固定 | 该段 key 冷启动（缓存 miss） | 缓存亲和/分片 |

**原则**：状态能外置就外置；粘性/哈希是"**把本地缓存用起来**"的优化，不是"存状态"的方案。

---

## 6. 坑与边界

| # | 坑 | 说明 |
|---|---|---|
| 1 | 没有 `spring-retry` 依赖 / `retryable-status-codes` 默认空 | **LB 层重试根本不存在**（拦截器条件装配没激活）；且 5xx 默认不触发重试——两者都要显式处理（lab A-12 实测） |
| 2 | 重试放大流量 + 幂等要求 | 重试是"流量 ×N"；非幂等写操作会重复（**xhs 立场：`FeignSafeConfig` 全局 `NEVER_RETRY`，幂等接口单独开**） |
| 3 | 两个重试口味别双开 | "换台"（默认）与"粘原台"（same-instance）语义相反 |
| 4 | 粘性的漂移与不均 | 客户端换/清 cookie；粘住的实例可能过载 |
| 5 | 哈希的热 key | 同 key 固定 → 热 key 打爆单台（03 §6） |

---

## 7. 生产对照

- **xhs**：`FeignSafeConfig` 全局 `Retryer.NEVER_RETRY`（"非幂等接口重试会重复下单"）——**服务间默认不重试**；幂等接口单独配；
- **网关**：L37 的 `Retry` filter（重试在外、熔断在内）——网关入口层按需开；
- **有状态**：xhs 走集中式/JWT 路线（状态不在实例本地），粘性/哈希主要用于"共享缓存本地化"的优化场景。

---

## 8. 边界与翻篇

- 网关层重试/熔断 → L37；Feign 的重试/超时细节 → `openfeign-lab`；一致性哈希的手写实现 → C 类（已砍，可恢复）；
- 自定义 LB（最少连接/哈希怎么写）→ **09**；系列收口与选型 → **10**。

---

## 9. Self-Test

1. 重试的两口味分别是什么？各自的机制（哪个 supplier）与适用场景？
2. 粘性和一致性哈希在"实例挂掉"时的表现差异？为什么说"粘性不是状态的正解"？
3. `max-retries-on-next-service-instance` 默认多少？为什么"5xx 默认不重试"？
4. 为什么 xhs 选择全局 `NEVER_RETRY`？幂等接口怎么处理？

---

## 10. 相关与衔接

- **09 自定义 LB**：最少连接（含预热的真实实现）——重试/摘除之外的另一把"伤害控制"武器；
- **10 可观测·排障·选型收口**；
- [`loadbalancer-lab`](../../loadbalancer-lab/HANDOVER.md)：[A-类官方功能](../../loadbalancer-lab/docs/A-类官方功能.md)（A-09 同实例 / A-11 粘性 / A-12 重试实测）；
- 网关课程：[L29](../gateway/L29-sticky-session.md)（sticky 实测）/[L37](../gateway/L37-circuit-breaker-retry.md)（网关重试）/ [L16](../gateway/L16-custom-loadbalancer.md)（染色≠选实例）。
