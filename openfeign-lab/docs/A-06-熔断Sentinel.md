# A-06: 熔断(Sentinel)

## 一、Sentinel + OpenFeign 集成原理

Spring Cloud Alibaba Sentinel 通过替换 Feign 的 `InvocationHandlerFactory` 实现熔断降级。

### 整体架构

```
feign.sentinel.enabled=true
         │
         ▼
SentinelFeignAutoConfiguration
         │
         └── SentinelFeign.builder()  ── 替换默认 Feign.Builder
                   │
                   └── 自定义 InvocationHandlerFactory
                            │
                            └── SentinelInvocationHandler
                                     │
                          ┌──────────┴──────────┐
                          │                      │
                    SphU.entry()          异常 → FallbackFactory
                    (Sentinel 资源)               │
                          │                  降级响应
                    正常响应
```

### 调用链

| 步骤 | 组件 | 说明 |
|------|------|------|
| 1 | `SentinelFeignAutoConfiguration` | 检测 `feign.sentinel.enabled=true`,创建 `SentinelFeign.builder()` |
| 2 | `SentinelFeign.Builder.internalBuild()` | 用自定义 `InvocationHandlerFactory` 替换默认工厂 |
| 3 | `InvocationHandlerFactory.create()` | 读取 `@FeignClient(fallbackFactory=...)`,构造 `SentinelInvocationHandler` |
| 4 | `SentinelInvocationHandler.invoke()` | 每次 Feign 调用:进入 Sentinel 资源 → 执行 → 退出资源 |
| 5 | 资源进入 | `ContextUtil.enter()` + `SphU.entry()` 创建 Sentinel Entry |
| 6 | 正常返回 | 直接返回 Feign 响应 |
| 7 | 异常/熔断 | 调用 `FallbackFactory.create(Throwable)` 获取降级实例并返回降级响应 |

## 二、本 Lab 实现

### 2.1 配置

```yaml
feign:
  sentinel:
    enabled: true
```

### 2.2 Feign 客户端 + FallbackFactory

```java
@FeignClient(name = "sentinel-test", url = "http://localhost:28002",
             fallbackFactory = SentinelTestClientFallbackFactory.class)
public interface SentinelTestClient {
    @GetMapping("/feign/sentinel/ping")
    Map<String, Object> ping();
}
```

```java
@Component
public class SentinelTestClientFallbackFactory
        implements FallbackFactory<SentinelTestClient> {

    @Override
    public SentinelTestClient create(Throwable cause) {
        return () -> {
            Map<String, Object> result = new HashMap<>();
            result.put("fallback", true);
            result.put("causeType", cause.getClass().getSimpleName());
            result.put("causeMessage", cause.getMessage());
            return result;
        };
    }
}
```

关键点:
- `@FeignClient(fallbackFactory = ...)` — 声明降级工厂
- `FallbackFactory<SentinelTestClient>` — Spring Cloud OpenFeign 提供的接口(`org.springframework.cloud.openfeign.FallbackFactory`)
- `create(Throwable cause)` — 每次降级时调用,可获取异常原因
- 返回值是 `SentinelTestClient` 的匿名实现类——所有方法都返回降级响应

### 2.3 测试端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/feign/sentinel/ping` | GET | 正常返回 `{"status":"healthy","message":"pong"}`;故障时抛出异常 |
| `/feign/sentinel/toggle?healthy=false` | POST | 切换端点健康状态 |
| `/feign/sentinel-test` | GET | 完整测试:先调健康端点,再切故障,验证 fallback 触发 |
| `/feign/circuit-breaker-test` | POST | 批量测试:10 次调用,统计 fallback 次数 |

## 三、验证

### 3.1 完整熔断测试

```
curl http://localhost:28002/feign/sentinel-test
```

预期结果:

```json
{
    "test": "A6-完整熔断测试",
    "healthyCallSuccess": true,
    "healthyCall": {"status": "healthy", "message": "pong"},
    "healthyCallCostMs": 2,
    "fallbackTriggered": true,
    "fallbackCall": {
        "fallback": true,
        "causeType": "InternalServerError",
        "causeMessage": "[500 ] during [GET] to [http://localhost:28002/feign/sentinel/ping] ..."
    },
    "fallbackCallCostMs": 23
}
```

验证点:
- `healthyCallSuccess: true` — 健康时正常响应
- `fallbackTriggered: true` — 故障时触发降级
- `fallbackCall.causeType: InternalServerError` — FallbackFactory 获取到异常类型(服务端返回 500)
- `fallbackCall.fallback: true` — 降级响应替代原始异常

### 3.2 批量熔断测试

```
curl -X POST http://localhost:28002/feign/circuit-breaker-test
```

预期结果:

```json
{
    "test": "A6-熔断测试(Sentinel)",
    "healthyPing": {"status": "healthy", "message": "pong"},
    "callCount": 10,
    "fallbackCount": 10,
    "normalCount": 0,
    "note": "SentinelInvocationHandler 拦截失败的调用,通过 FallbackFactory 返回降级响应"
}
```

### 3.3 手动切换测试

```
# 切换为故障模式
curl -X POST "http://localhost:28002/feign/sentinel/toggle?healthy=false"

# 调用验证(应返回 fallback)
curl http://localhost:28002/feign/sentinel/ping

# 恢复健康
curl -X POST "http://localhost:28002/feign/sentinel/toggle?healthy=true"

# 验证恢复
curl http://localhost:28002/feign/sentinel/ping
```

## 四、SentinelInvocationHandler 源码分析

`SentinelInvocationHandler.invoke()` 核心逻辑（SCA 2023.0.1.2 节选）:

```java
// SentinelInvocationHandler.invoke()（2023.0.1.2）
Object result;
MethodHandler methodHandler = this.dispatch.get(method);
// only handle by HardCodedTarget
if (target instanceof Target.HardCodedTarget hardCodedTarget) {
    MethodMetadata methodMetadata = SentinelContractHolder.METADATA_MAP
            .get(hardCodedTarget.type().getName()
                    + Feign.configKey(hardCodedTarget.type(), method));
    if (methodMetadata == null) {
        result = methodHandler.invoke(args);
    }
    else {
        String resourceName = methodMetadata.template().method().toUpperCase()
                + ":" + hardCodedTarget.url() + methodMetadata.template().path();
        Entry entry = null;
        try {
            ContextUtil.enter(resourceName);
            entry = SphU.entry(resourceName, EntryType.OUT, 1, args);
            result = methodHandler.invoke(args);
        }
        catch (Throwable ex) {
            // fallback handle
            if (!BlockException.isBlockException(ex)) {
                Tracer.traceEntry(ex, entry);
            }
            if (fallbackFactory != null) {
                Object fallbackResult = fallbackMethodMap.get(method)
                        .invoke(fallbackFactory.create(ex), args);
                return fallbackResult;
            }
            else {
                // throw exception if fallbackFactory is null
                throw ex;
            }
        }
        finally {
            if (entry != null) {
                entry.exit(1, args);
            }
            ContextUtil.exit();
        }
    }
}
```

关键区别：`fallbackFactory.create(Throwable)` 返回的是 Feign 接口的**降级实例**（如 `SentinelTestClientFallbackFactory` 创建的实现），不是方法返回值。真正的返回值来自 `fallbackMethodMap.get(method).invoke(fallback, args)` —— `fallbackMethodMap` 由 `toFallbackMethod(dispatch)` 预建（对每个接口方法 `setAccessible(true)` 后自映射），即在降级实例上反射调用**同名方法**。注意与 2.x 旧实现的差别：旧版是 `dispatch.get(method).invoke(fallback, args)`，2023.0.1.2 改走 `fallbackMethodMap`。

## 五、生产注意

- **`fallback` vs `fallbackFactory`**: `fallback` 指定一个静态的降级实现类(不能获取异常原因);`fallbackFactory` 通过 `create(Throwable)` 可获取异常原因,推荐使用
- **触发条件**: 任何 Feign 调用异常(包括网络超时、服务端 500、连接拒绝等)都会触发 fallback。Sentinel 的熔断规则(BLOCK)也会触发 fallback
- **熔断 vs 降级**: 降级(fallback)是每次失败都调用;熔断(circuit breaker)是 Sentinel 检测到异常比例/数量超过阈值后打开断路器,后续请求直接返回 fallback 而不发起实际调用。本 lab 仅演示降级,熔断规则需配置 `DegradeRule`
- **资源名格式**: `HTTP_METHOD:http://service-name/path`,例如 `GET:http://sentinel-test/feign/sentinel/ping`
- **降级幂等性**: fallback 中不应有副作用操作,熔断期间可能被高并发调用
- **异常传播**: `FallbackFactory.create(Throwable)` 的参数是原始异常,可根据异常类型选择不同的降级策略
