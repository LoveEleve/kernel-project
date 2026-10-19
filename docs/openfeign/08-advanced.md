# FEIGN-08 进阶特性

> 用途：响应处理进阶（**ErrorDecoder** / **Decoder 增强**）、多版本 API、RestClient 对比
> 对应系列：**OpenFeign 专题 · 08**（进阶篇第一篇）
> 口径：SC OpenFeign 4.1.3 / feign-core 13.3
> 素材：`openfeign-lab`（[A-08 多版本 API](../../openfeign-lab/docs/A-08-多版本API.md) / [A-09 RestClient 对比](../../openfeign-lab/docs/A-09-RestClient对比.md) / [B-02~B-05 扩展点](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md)）· xhs-project（`FeignSafeConfig` 已接线 / `FeignUnifiedConfig` 设计未接线）· 源码 + **最小实验**
> 前置：06（fallback 与异常的互动）；后续：09 源码主干 → 10 收口

---

## 0. 例子先行：xhs 的两个"响应处理"设计

```text
① 已接线：FeignSafeConfig.RemoteCallErrorDecoder（ErrorDecoder）
   非 2xx → 4xx/5xx 转 BizException/RemoteException（带服务名/路径/状态码）

② 未接线（本课核实）：FeignUnifiedConfig 的 R<T> 解包（Decoder 增强）
   设计：接口直接返回 T，Decoder 自动从 R.data 解包、R.fail 抛 BizException
   现状：仓库里【没有客户端引用它】；且 @Primary 的摆放会让解包 Decoder 被跳过（§4 实测）
```

两条都发生在**"响应处理"**环节——本篇主线：先分清 **Decoder（2xx）** 与 **ErrorDecoder（非 2xx）** 两段。

---

## 1. 掌握卡（3 条）

1. **响应处理两段**：**2xx → Decoder**（默认链 `OptionalDecoder(ResponseEntityDecoder(SpringDecoder))`）；**非 2xx → ErrorDecoder**（默认 `ErrorDecoder.Default` 抛 `FeignException`）。业务约定（如 `R<T>` 包装）先决定放哪一段。
2. **覆盖"框架默认 bean"要懂子上下文解析**：per-client `configuration` 里再提供 `Decoder` 会与默认链**并存**——谁生效取决于 `@Primary`/唯一性（xhs `FeignUnifiedConfig` 的 `@Primary` 摆放就是反例，§4）。
3. **多版本 API 四方案 + RestClient 对比**：版本进路径/头/接口/Content-Type；Feign vs RestClient 的选型边界（§5/§6）。

---

## 2. 响应处理两段（先分清）

```text
client.execute()
  ├─ 非 2xx → ErrorDecoder.decode(methodKey, response) → 返回 Exception（由 Feign 抛出）
  └─ 2xx     → Decoder.decode(response, type)          → 返回方法声明的类型
（外层还有 06 的 SentinelInvocationHandler：任何 Throwable → fallback）
```

| 段 | 触发 | 默认行为 | 定制点 |
|---|---|---|---|
| **Decoder** | HTTP 2xx | `OptionalDecoder(ResponseEntityDecoder(SpringDecoder))`（源码 `FeignClientsConfiguration:104`） | 提供 `Decoder` bean（B-04） |
| **ErrorDecoder** | HTTP 非 2xx | `FeignException` | 提供 `ErrorDecoder` bean（B-05） |

**注意**：业务约定（如 `R.fail` 返回 **HTTP 200 + 失败码**）属于 **Decoder 段**；只有"HTTP 状态码本身就是错误"（4xx/5xx）才走 ErrorDecoder——很多"R 解析没生效"的问题其实是**把两段放错了**。

---

## 3. ErrorDecoder：非 2xx → 异常（xhs 已接线实例）

```java
// FeignSafeConfig.RemoteCallErrorDecoder（节选核心）
public Exception decode(String methodKey, Response response) {
    // ① 先尝试把响应体解析成 R：业务码 != 成功 → BizException
    R<?> r = objectMapper.readValue(body, R.class);
    if (r != null && !r.isSuccess()) return new BizException(r.getCode(), r.getMessage());

    // ② 5xx → RemoteException（带服务名/路径/状态，便于告警与定位）
    if (status >= 500) return new RemoteException(serviceName, path, status, message);

    // ③ 4xx → BizException（携带服务信息）
    if (status >= 400) return new BizException(status, "下游服务 %s %s 返回 HTTP %d");

    return defaultDecoder.decode(methodKey, response);   // 兜底
}
```

设计点：

- **错误信息结构化**（服务名/路径/状态码）——比默认的 `FeignException` 好定位；
- **`decode` 返回 Exception（不是 throw）**——由 Feign 负责抛出；
- **响应体只能读一次**：源码里先 `Util.toByteArray(body)` 缓存字节再解析（流式 body 二次读取会拿到空）；
- **与 06 的互动**：这些异常会进入 `SentinelInvocationHandler` 的 `catch(Throwable)`——**配了 `fallbackFactory` 的客户端会把它们兜进 fallback**；要保留业务语义，就在 `create(cause)` 里按异常类型分流/重抛。

---

## 4. Decoder 增强：`R<T>` 解包（设计 + 本课核实）

### 4.1 设计意图（`FeignUnifiedConfig` javadoc）

```text
优化前：R<NoteDetailDTO> getNoteDetail(id);     调用方：if (result.isSuccess()) { ... }
优化后：NoteDetailDTO    getNoteDetail(id);     异常自动抛 BizException
```

`RUnpackDecoder` 的核心逻辑（节选）：

```java
public Object decode(Response response, Type type) throws IOException, FeignException {
    if (isRType(type)) return delegate.decode(response, type);   // 声明就是 R<T> → 默认解
    R<?> r = objectMapper.readValue(body, R.class);
    if (r == null) throw new BizException(500, "下游服务返回空响应");
    if (!r.isSuccess()) throw new BizException(r.getCode(), r.getMessage());
    if (r.getData() == null && type != Void.class && type != void.class) throw new BizException(500, "下游服务返回空数据");
    return objectMapper.convertValue(r.getData(), targetType);   // data → T
}
```

### 4.2 本课核实（两个发现）

1. **未接线**：全仓库 grep `FeignUnifiedConfig` **只命中它自己的 javadoc/类定义**——没有客户端用 `configuration = FeignUnifiedConfig.class`，也没有 `default-config` 挂载；实际客户端（如 `ContentFeignClient`）**仍返回 `R<Map<…>>`**。它是"设计好的可选配置"，不是现役路径。
2. **即使接线，解包 Decoder 大概率被跳过**：`@Primary` 被标在 `feignSpringDecoder`（普通 SpringDecoder）上——而 Feign 选 Decoder 是 `get(context, Decoder.class)`（= 子上下文 `getBean(Decoder.class)`）：**多候选时 `@Primary` 获胜**。
   - **最小实验**（本课跑过）：两个候选 + 一个 `@Primary` → `getBean(D.class)` 返回 **primary 那个**；
   - 推论：子上下文里 `feignSpringDecoder(@Primary)` 胜出 → `RUnpackDecoder` 不生效。
   - **若要接线**：把 `@Primary` 给解包 Decoder（或让它成为唯一候选），并让它的构造器直接注入默认 Decoder 栈。

> 这一节的价值不在"xhs 有个 bug"，而在演示**覆盖框架默认 bean 的正确姿势**：per-client `configuration` 里的 `Decoder`/`Encoder`/`Contract` 都会与**默认配置（`FeignClientsConfiguration`，源码 `FeignClientFactory` 构造器确认）**并存——必须用 `@Primary`/唯一性明确"谁赢"。

---

## 5. 多版本 API（A-08）

| 方案 | 做法 | 适用 |
|---|---|---|
| **不同 Feign 接口** | 每版本一个 `@FeignClient`（不同 path/contextId），调用不同端点 | 后端本身就是两套 API |
| **Header 版本标识** | `RequestInterceptor` 注入 `X-Api-Version`（lab 的 `VersionInterceptor`） | 同端点、按版本路由/追踪 |
| **URI 路径** | `/api/v1/...` vs `/api/v2/...` | 路径即版本（最常见） |
| **Content-Type** | `application/vnd.company.v1+json` | REST 成熟度模型 3（少用） |

- **配置键联动**：多接口 = 多 `contextId`（03 的键名细节）；
- **与网关联动**：`X-Api-Version` 透传（04）→ 网关按路由谓词、服务侧按 supplier 过滤（灰度思路见 [L16](../gateway/L16-custom-loadbalancer.md) 的 version 过滤 / [xhs-gateway-zone.md](../gateway/xhs-gateway-zone.md) 的 gray-tag）。

---

## 6. RestClient 对比（A-09）

| 维度 | OpenFeign | RestClient |
|---|---|---|
| 编程模型 | 声明式（接口 + 注解） | 命令式（Builder fluent） |
| 依赖 | `spring-cloud-starter-openfeign` | Spring Web 自带 |
| 负载均衡 | 内置（`FeignBlockingLoadBalancerClient`） | 需手动接（`@LoadBalanced` Builder，LB 系列 04） |
| 熔断/降级 | 内置（Sentinel/`fallbackFactory`） | 需手动接 |
| 调试 | 代理不透明 | Builder 链式、IDE 可断点 |
| 复杂响应 | 自动反序列化 | 需 `ParameterizedTypeReference` |

**选型**（A-09 结论口径）：

- **Feign**：微服务间大量调用（10+ 客户端）+ 要 LB/熔断降级 + 团队偏声明式；
- **RestClient**：少量外部 HTTP 调用（1~3 个）、不需要 LB/熔断、想少依赖；
- xhs：服务间统一 Feign；RestClient 只在对比/示例里出现。

---

## 7. 扩展点速查（B-04/B-05）与压缩

| 扩展点 | 换掉什么 | xhs 实例 | lab |
|---|---|---|---|
| `Decoder` | 2xx 解包 | `RUnpackDecoder`（设计） | [B-04](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md) |
| `ErrorDecoder` | 非 2xx → 异常 | `RemoteCallErrorDecoder`（现役） | [B-05](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md) |
| `Encoder` | 请求体编码 | — | B-04 |

> 压缩（请求/响应 gzip）已在 **05 §5.1** 讲过，本篇不重复。

---

## 8. 坑清单

| # | 坑 | 说明 |
|---|---|---|
| 1 | **覆盖默认 bean 的解析**（本节核心） | `@Primary`/唯一性决定胜负；放反了 = 定制静默失效（§4 实测） |
| 2 | ErrorDecoder 二次读 body | 流式 body 只能读一次，先缓存字节 |
| 3 | 业务约定放错段 | `R.fail`（HTTP 200）属 Decoder；4xx/5xx 才是 ErrorDecoder |
| 4 | 解包 + fallback 互动 | BizException 会被 `catch(Throwable)` 兜进 fallback（06） |
| 5 | 多版本接口的 contextId/配置键 | 多接口 = 多 contextId（03） |
| 6 | RestClient 缺生态 | LB/熔断/降级都要自己接（选型时算成本） |

---

## 9. Self-Test

1. 2xx 和"非 2xx"分别由谁处理？`R.fail`（HTTP 200）应该放哪段？
2. xhs 的 `FeignUnifiedConfig` 为什么"设计存在却未生效"？（两个原因）
3. 覆盖框架默认 `Decoder` 时，怎么保证你的实现被选中？
4. 什么场景该从 Feign 换成 RestClient？换的代价是什么？

---

## 10. 相关与衔接

- **09 源码主干**：Registrar/FactoryBean/Client/Decoder 四条链一张图；
- **10 生产与收口**：异常体系（BizException/RemoteException）与排障；
- **05 §5**：压缩与序列化（本篇不重复）；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[A-08 多版本](../../openfeign-lab/docs/A-08-多版本API.md) / [A-09 RestClient 对比](../../openfeign-lab/docs/A-09-RestClient对比.md) / [B-02~B-05 扩展点](../../openfeign-lab/docs/B-02~B-05-自定义扩展点.md)；
- `docs/loadbalancer`：[04 @LoadBalanced RestClient 路径](../loadbalancer/04-sclb-architecture.md)。
