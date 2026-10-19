# FEIGN-04 拦截器与请求改造

> 用途：`RequestInterceptor` 的三种声明方式、时机与边界、典型用途（traceId / 内部令牌 / 灰度 / 签名）+ xhs 两个生产拦截器拆解
> 对应系列：**OpenFeign 专题 · 04**（使用篇第二篇）
> 口径：Spring Cloud OpenFeign 4.1.3 / feign-core 13.3
> 素材：`openfeign-lab`（[A-02 拦截器](../../openfeign-lab/docs/A-02-拦截器.md)）· xhs-project（`FeignInternalCallInterceptor` / `FeignTraceInterceptorConfig` / `FeignUnifiedConfig`）· 官方 docs
> 前置：03（配置与 configuration）；后续：05 超时与客户端 → 06 容错 → 08（Decoder/ErrorDecoder）

---

## 0. 例子先行：一次内部调用"出门前要装什么"

xhs 的服务间 Feign 调用，下游收到的请求头里会多出 6 个：

```text
X-Trace-Id     X-User-Id     X-Gray-Tag
X-Api-Version  X-AB-Group    X-Pressure-Test
（外加：X-Internal-Call = 内部调用令牌）
```

**这些不是每个方法手写的 `@RequestHeader` 参数**，而是拦截器在"出门前"统一装的：

```java
// 思路（xhs 真实实现之一，节选）
return template -> {
    TraceContext ctx = TraceContextHolder.get();      // 从 ThreadLocal 取当前上下文
    if (ctx == null) return;
    setHeaderIfPresent(template, "X-Trace-Id", ctx.getTraceId());
    setHeaderIfPresent(template, "X-Gray-Tag", ctx.getGrayTag());
    // ...其余 4 个头
};
```

**拦截器 = 每次调用的"出门统一装箱"**——URL/头/体都能改，且不用污染接口签名。

---

## 1. 掌握卡（3 条）

1. **时机**：`apply(RequestTemplate)` 在"**编码之后、发送之前**"跑（13.3 源码：编码在 `create()` 内完成 → 拦截器在 `targetRequest()` 里按序 apply）——改头/query/URL 都行，**也能通过 `template.body()` 读到编码后的 body**，还可用 `template.body(...)` 重设；注意**每次重试会再跑一遍拦截器**。
2. **三种声明方式、三种作用域**：configuration Bean（该客户端）/ **属性类名列表**（`request-interceptors`，走 `getOrInstantiate`）/ `@Component`（主上下文，全体客户端）——后者的实例化细节是个坑（§3）。
3. **顺序**：容器里的拦截器按 `@Order`/`Ordered` 排序（`AnnotationAwareOrderComparator.sort`）；属性声明的**追加在排序组之后**。认证类通常要保证先于业务类。

---

## 2. 接口与时机（含一个已知边界）

```java
public interface RequestInterceptor {
    void apply(RequestTemplate template);   // 能改：header / query / path / body
}
```

时机（13.3 源码口径，接 02 的调用链）：

```text
SynchronousMethodHandler.invoke：
  ① buildTemplateFromArgs.create(argv)
       └─ resolve()：Encoder 把 @RequestBody 参数【编码进 body】   ← 编码在这里完成
  ② 重试循环：executeAndDecode(template)
       └─ targetRequest(template)：★ 按序 apply 所有拦截器
       └─ client.execute（LB 版先选实例）→ …
```

**由此得出两个实用结论**：

- **拦截器能读到编码后的 body**（`template.body()` 非空）——做**请求签名**可行；也能用 `template.body(Request.Body.encoded(bytes, charset))` **重设** body；
- **拦截器按"每次尝试"执行**：重试时会再跑一遍（同一模板）——`template.header` 是**追加**语义（源码：一般头追加、仅 `Content-Type` 覆盖），敏感场景注意去重（先 `removeHeader` 再 `header`）。

---

## 3. 三种声明方式与实例化机制（本节是重点）

| 方式 | 写法 | 生效范围 | 实例化 |
|---|---|---|---|
| ① **configuration Bean** | `@FeignClient(configuration=X)` 里 `@Bean RequestInterceptor` | 该客户端（子上下文） | Spring 管理（@Value 正常注入） |
| ② **属性类名列表** | `spring.cloud.openfeign.client.config.<key>.request-interceptors: [类名]` | 该范围客户端 | **`getOrInstantiate`**（见下） |
| ③ `@Component` | 主上下文注册 | **所有客户端**（被扫描到的服务） | Spring 管理 |

**方式 ② 的实例化机制**（`FeignClientFactoryBean` 源码，这就是 xhs T-017 坑的根源）：

```java
private <T> T getOrInstantiate(Class<T> tClass) {
    try {
        return beanFactory.getBean(tClass);          // 优先按类从容器拿
    } catch (NoSuchBeanDefinitionException e) {
        return BeanUtils.instantiateClass(tClass);   // ★ 没有对应 Bean 就"反射 new"
    }
}
```

- 容器里有该类 Bean → 拿到的是 Spring 管理的（`@Value` 生效）；
- **没有 → 反射 new：`@Value`/依赖注入全部不生效**——拦截器"悄悄变成残废"（比如令牌读不到 = 不带头）；
- 属性声明的拦截器**追加**到已有集合（源码注释：add, **not replace**）。

---

## 4. 四个典型用途

| 用途 | 干什么 | 要点 |
|---|---|---|
| **traceId / 上下文透传** | 从 MDC / ThreadLocal（如 `TraceContextHolder`）取 → 写头 | **从 ThreadLocal 取**而非 `HttpServletRequest`——MQ/定时任务触发的调用没有请求对象（xhs 注释原文） |
| **内部令牌 / 防伪** | 加 `X-Internal-Call` 等令牌头 | **fail-closed**：令牌缺失 = 不带头 → 下游拒绝（"宁可断链，不漏权"）；校验在服务侧完成 |
| **灰度/染色透传** | `X-Gray-Tag` / `X-Api-Version` / `X-AB-Group` 等 | 只有"透传"是不够的——**染色 ≠ 选实例**（L16 结论；选实例在 LB 层） |
| **签名 / 租户 / 幂等键** | HMAC 签名、Tenant、Idempotency-Key | 签名依赖 body 时：拦截器可读 `template.body()`（§2 时机结论） |

---

## 5. xhs 生产拆解（两个拦截器 + 一个坑）

### 5.1 `FeignInternalCallInterceptor`（内部令牌，全局）

```java
@Component                                     // ← 全局：所有 FeignClient
public class FeignInternalCallInterceptor implements RequestInterceptor {

    @Value("${myxhs.internal.token:}")
    private String internalToken = initFromEnv();   // ← T-017 修复：默认值直接读环境变量

    private static String initFromEnv() {
        String t = System.getenv("INTERNAL_TOKEN");
        return t == null ? "" : t;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (internalToken != null && !internalToken.isEmpty()) {
            template.header("X-Internal-Call", internalToken);   // 空则不带头（fail-closed）
        }
    }
}
```

**两条设计**（源码注释原文口径）：

1. **fail-closed**：token 由环境变量注入，默认空——未配置时不带该头，下游会剥离 `X-User-Id` 并拒绝内部调用（"宁可链路不可用，不可泄露默认令牌"）；
2. **T-017 修复**：该拦截器同时被**属性列表**声明（`...config.default.request-interceptors`）——一旦走到 `getOrInstantiate` 的"反射 new"路径，`@Value` 不注入 → token 空 → **静默不带头**。修复姿势：**字段默认值直接读环境变量**（`initFromEnv()`），Spring 管理时 `@Value` 覆盖默认值——**双路径都生效**。

### 5.2 `FeignTraceInterceptorConfig`（六头透传，全局）

```java
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class FeignTraceInterceptorConfig {
    @Bean
    RequestInterceptor traceIdFeignInterceptor() {
        return template -> {
            TraceContext ctx = TraceContextHolder.get();     // ThreadLocal
            if (ctx == null) return;
            setHeaderIfPresent(template, "X-Trace-Id", ctx.getTraceId());
            setHeaderIfPresent(template, "X-User-Id", ctx.getUserId());
            setHeaderIfPresent(template, "X-Gray-Tag", ctx.getGrayTag());
            setHeaderIfPresent(template, "X-Api-Version", ctx.getApiVersion());
            setHeaderIfPresent(template, "X-AB-Group", ctx.getAbGroup());
            setHeaderIfPresent(template, "X-Pressure-Test", ctx.getPressureTest());
        };
    }
}
```

- `@Configuration` 在 common 包 → 被各服务扫描 → **主上下文生效（全体客户端）**；
- 透传 6 个头，链路染色跨服务不断裂（细节在 `tracing-lab`）。

> 另注：`FeignUnifiedConfig`（per-client configuration）里还做了 **Decoder 增强**（`R<T>` 自动解包 + `BizException` 还原）——属于 08 的内容，本篇只点名。

---

## 6. 坑清单

| # | 坑 | 说明 |
|---|---|---|
| 1 | **属性声明的拦截器 @Value 不注入**（T-017） | `getOrInstantiate` 反射 new 的路径；用"字段默认值兜底"或确保它是容器 Bean |
| 2 | 顺序 | 容器组按 `@Order` 排序，属性组追加在其后；认证类别被业务类抢先 |
| 3 | 重试时拦截器再执行 + 追加语义 | 拦截器在编码后、发送前跑，**重试会再跑一遍**；`template.header` 追加（Content-Type 除外）→ 注意重复加头（必要时先 `removeHeader`） |
| 4 | 全局 vs 子上下文 | `@Component`/`@Configuration` 在 common 包被全服务扫描 = 全局生效（03 的"泄漏坑"同款，此处多为有意） |
| 5 | 重复加头 | `template.header` 是**追加**语义——多个拦截器都写 `X-Trace-Id` 会产生多值 |

---

## 7. Self-Test

1. 拦截器在哪一步跑？为什么"看不到编码后的 body"？要改 body 怎么办？
2. 三种声明方式的作用域分别是什么？属性声明走什么实例化路径、坑在哪？
3. xhs 的 fail-closed 是什么意思？T-017 的修复姿势是什么？
4. 为什么 traceId 从 ThreadLocal 取而不是 `HttpServletRequest`？

---

## 8. 相关与衔接

- **05 超时与客户端**：连接/读超时与 HC5/OkHttp；
- **06 容错**：`X-Internal-Call` 的对端校验、降级工厂；
- **08 进阶**：`FeignUnifiedConfig` 的 Decoder 增强（`R<T>` 解包/BizException）；
- **07 LB 与重试**：拦截器只负责"装头"，选实例在 LB 层；
- [`openfeign-lab`](../../openfeign-lab/HANDOVER.md)：[A-02 拦截器](../../openfeign-lab/docs/A-02-拦截器.md)；
- 交叉：[`tracing-lab`](../../tracing-lab/HANDOVER.md)（链路透传全貌）/ 网关课程 [L16](../gateway/L16-custom-loadbalancer.md)（染色≠选实例）。
