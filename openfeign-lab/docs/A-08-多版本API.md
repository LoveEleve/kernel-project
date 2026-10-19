# A-08: 多版本 API

## 一、多版本 API 的常见模式

微服务演进中,API 版本管理是刚性需求。常见策略:

| 模式 | 实现方式 | 适用场景 |
|------|---------|---------|
| **不同 Feign 接口** | 每个版本一个 `@FeignClient`,调用不同端点 | 后端本身就是不同版本的 API |
| **Header 版本标识** | 通过 `RequestInterceptor` 注入版本 Header,供网关/服务端日志追踪 | 同一端点,客户端根据版本选择不同接口 |
| **URI 路径** | `/api/v1/health` vs `/api/v2/health` | 路径即版本 |
| **Content-Type** | `application/vnd.company.v1+json` | REST 成熟度模型 3 |

本 lab 演示两种模式:
1. **`/feign/multi-version-test`** — 接口路由:两个 `@FeignClient` 接口调用不同端点
2. **`/feign/multi-version`** — 接口路由 + Header 标识:`VersionInterceptor` 注入 `X-API-Version` Header 作为版本标识

## 二、本 Lab 实现

### 2.1 版本化 Feign 接口

V1 — 返回简单字符串:

```java
@FeignClient(name = "nacos-lab-v1", url = "http://localhost:28001")
public interface NacosLabV1Client {

    @GetMapping("/health")
    String health();
}
```

V2 — 返回完整状态对象:

```java
@FeignClient(name = "nacos-lab-v2", url = "http://localhost:28001")
public interface NacosLabV2Client {

    @GetMapping("/discovery/status")
    Map<String, Object> discoveryStatus();
}
```

两个接口用不同的 `name`(nacos-lab-v1 / nacos-lab-v2),可配置不同的超时和拦截器:

```yaml
spring.cloud.openfeign.client.config.nacos-lab-v1.connectTimeout: 2000
spring.cloud.openfeign.client.config.nacos-lab-v1.readTimeout: 3000
spring.cloud.openfeign.client.config.nacos-lab-v2.connectTimeout: 2000
spring.cloud.openfeign.client.config.nacos-lab-v2.readTimeout: 5000
```

### 2.2 Header 版本标识

`VersionInterceptor` 通过 `ThreadLocal` 传递版本号,在 Feign 请求时注入 `X-API-Version` Header (仅作为标识,供服务端/网关日志追踪,实际路由仍由控制器中的接口选择决定):

```java
@Component
public class VersionInterceptor implements RequestInterceptor {

    private static final ThreadLocal<String> versionHolder = new ThreadLocal<>();

    public static void setVersion(String version) {
        versionHolder.set(version);
    }

    public static void clear() {
        versionHolder.remove();
    }

    @Override
    public void apply(RequestTemplate template) {
        String version = versionHolder.get();
        if (version != null && !version.isEmpty()) {
            template.header(VERSION_HEADER, version);
        }
    }
}
```

### 2.3 版本选择器

控制器根据 `version` 参数选择对应的 Feign 客户端:

```java
@GetMapping("/feign/multi-version")
public Map<String, Object> testMultiVersion(@RequestParam(defaultValue = "v1") String version) {
    VersionInterceptor.setVersion(version);
    try {
        if ("v1".equals(version)) {
            String resp = nacosLabV1Client.health();
            // V1: 字符串响应
        } else {
            Map<String, Object> status = nacosLabV2Client.discoveryStatus();
            // V2: 结构化响应
        }
    } finally {
        VersionInterceptor.clear();
    }
}
```

## 三、验证

### 3.1 V1 调用

```
curl "http://localhost:28002/feign/multi-version?version=v1"
```

```json
{
    "client": "NacosLabV1Client",
    "endpoint": "/health",
    "response": "nacos-lab is running",
    "success": true
}
```

### 3.2 V2 调用

```
curl "http://localhost:28002/feign/multi-version?version=v2"
```

```json
{
    "client": "NacosLabV2Client",
    "endpoint": "/discovery/status",
    "response": {
        "applicationName": "nacos-lab",
        "port": 28001,
        "ip": "21.214.97.212",
        "ephemeral": true,
        "weight": 1.0
    },
    "success": true
}
```

### 3.3 完整对比测试

```
curl http://localhost:28002/feign/multi-version-test
```

```json
{
    "v1": {
        "responseType": "String",
        "response": "nacos-lab is running"
    },
    "v2": {
        "responseType": "Map<String, Object>",
        "response": {
            "applicationName": "nacos-lab",
            "port": 28001,
            ...
        }
    },
    "note": "V1: NacosLabV1Client → /health → String; V2: NacosLabV2Client → /discovery/status → Map"
}
```

## 四、架构模式优缺点

| 方面 | V1 (简单字符串) | V2 (结构化对象) |
|------|:---:|:---:|
| 响应格式 | `String` | `Map<String, Object>` |
| 数据量 | 小 | 大 |
| 解析成本 | 低 | 中 |
| 扩展性 | 差(客户端需解析文本) | 好(JSON 字段自动映射) |
| 使用场景 | 健康检查、Ping | 完整状态查询、管理端 |

## 五、生产注意

- **版本共存**: 新旧版本接口应长期共存,给客户端迁移时间。不要在同一个发布中同时下线 V1 和上线 V2
- **Header 路由 vs 路径路由**: Header 路由(如 `X-API-Version`)适合网关层透明路由,不污染 URL;路径路由(/v1/health)更直观,调试方便
- **ThreadLocal 清理**: `VersionInterceptor` 使用 `ThreadLocal` 必须在 `finally` 中调用 `clear()`,防止内存泄漏或请求间串号
- **Feign 接口分离**: 不同版本使用不同 Java 接口,避免一个接口中 @Deprecated 方法堆积。这也是 OpenFeign 官方推荐的做法
- **客户端配置独立**: 用不同 `name` 隔离不同版本的 Feign 配置(超时、重试、拦截器),互不影响
