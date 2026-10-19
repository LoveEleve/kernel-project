# D11: 适配器机制 — WebMVC/WebFlux/Dubbo/Gateway 织入

> 源码位置: `sentinel-adapter/`（12 个子模块）
> 前置: D2（Slot链）理解 ProcessorSlot 的执行入口；D13（Context）理解 origin 和资源名的来源

---

## 一、问题背景：Sentinel 怎么"插入"到框架里？

D2 中我们看到了 `CtSph.asyncEntryWithPriorityInternal` 是 Sentinel 的执行入口——但谁来调用它？答案取决于运行环境：

| 框架 | 织入点 | 资源名生成规则 |
|------|------|------|
| Spring WebMVC | `SentinelWebInterceptor`（HandlerInterceptor） | `GET:/api/user/{id}` |
| Spring WebFlux | `SentinelWebFluxFilter`（WebFilter） | 同上 |
| Dubbo | `SentinelDubboConsumerFilter` / `SentinelDubboProviderFilter`（Filter SPI） | `com.xxx.UserService:getUser(v1)` |
| Spring Cloud Gateway | `SentinelGatewayFilter`（GatewayFilter） | `route_id` |
| gRPC | `SentinelGrpcServerInterceptor`（ServerInterceptor） | `package.ServiceName/MethodName` |

---

## 二、WebMVC 适配器：CommonFilter → SentinelWebInterceptor

**演进史**：Sentinel 1.x 使用 Servlet `Filter`（`CommonFilter`），但 Filter 无法获取 Spring MVC 的 `@RequestMapping` 信息——无法生成 `GET:/api/user/{id}` 这样的精确资源名。Sentinel 扩展了 `HandlerInterceptor`：

```java
public class SentinelWebInterceptor extends AbstractSentinelInterceptor {
    @Override
    protected String getResourceName(HttpServletRequest request) {
        // 从 HandlerMapping 中获取最佳匹配的 URL Pattern
        Object pattern = request.getAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE);
        return request.getMethod() + ":" + pattern;  // "GET:/api/user/{id}"
    }

    @Override
    protected String getContextName(HttpServletRequest request) {
        return request.getContextPath();  // origin
    }
}
```

**执行时序**：`DispatcherServlet.doDispatch` → `HandlerInterceptor.preHandle` → `SentinelWebInterceptor.preHandle` → `SphU.entry(resourceName)` → Slot链执行。

---

## 三、Dubbo 适配器：Filter SPI 链

Dubbo 有自己的 Filter SPI 链（类似 Sentinel 的 Slot 链）。Sentinel 通过 `SentinelDubboProviderFilter` 和 `SentinelDubboConsumerFilter` 插入到 Dubbo 的 Filter 链中：

```
Dubbo Provider 端:
    Invoker.invoke() → SentinelDubboProviderFilter.invoke()
        → SphU.entry("com.xxx.UserService:getUser(v1)")
            → 流控检查通过 → 执行业务方法
            → 流控拒绝 → 抛 BlockException → Dubbo RpcException

Dubbo Consumer 端:
    SentinelDubboConsumerFilter.invoke() → SphU.entry(远程调用资源名) → 限流/熔断
```

---

## 四、面试考点

**Q: Sentinel 如何识别不同的 Spring MVC URL？资源名是怎么生成的？**

`SentinelWebInterceptor` 从 `request.getAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE)` 获取 Spring MVC 的最佳匹配 URL Pattern（如 `/api/user/{id}`），组合 HTTP Method → `"GET:/api/user/{id}"`。不是原始 URL（如 `/api/user/123`）——因为那样每个用户 ID 都是独立资源，无法做聚合流控。

---

## 五、生产陷阱

1. **Filter 顺序冲突**：Sentinel 的 `CommonFilter` 需要在 Spring Security Filter 链**之前**执行——否则被 Security 拦截的请求根本不会进入 Sentinel。通过 `@Order` 或 `FilterRegistrationBean.setOrder` 控制。

2. **资源名爆炸**：如果 Dubbo Provider 的方法有重载 + 版本号，资源名会是 `com.xxx.UserService:getUser(v1, String, int)`——每个方法签名组合都是独立资源，N 个服务 × M 个方法 × K 个版本 = 资源名爆炸。Sentinel 内置了 `MAX_SLOT_CHAIN_SIZE` 限制（`Constants.java`），超过后返回 null → 走 `asyncEntryWithNoChain`（无防护）。
