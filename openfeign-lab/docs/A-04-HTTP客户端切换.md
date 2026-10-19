# A-04: HTTP 客户端切换

## 一、Feign 支持的 HTTP 客户端

OpenFeign 本身只是一个接口定义 + JDK 动态代理,实际的 HTTP 请求由底层的 `Client` 实现执行。Spring Cloud OpenFeign 支持的运行时:

| Client | 类名 | 特点 |
|--------|------|------|
| HttpURLConnection(传统默认) | `feign.Client.Default` | JDK 内置,无额外依赖,不支持连接池 |
| Apache HttpClient5(Spring Cloud 2025 默认) | `feign.hc5.ApacheHttp5Client` | 连接池、HTTP/2、支持 TLS 高级配置 |

**OkHttp 在这个版本中不再提供自动配置支持**。需要手动创建 `feign.okhttp.OkHttpClient` Bean。

## 二、自动配置:Apache HttpClient5

### 2.1 启用条件

`FeignAutoConfiguration` 源码(FeignAutoConfiguration.java:223-236):

```java
@Configuration
@ConditionalOnClass(ApacheHttp5Client.class)        // feign-hc5 在 classpath 上
@ConditionalOnMissingBean(CloseableHttpClient.class) // 没有自定义 HttpClient
@ConditionalOnProperty(
    value = "spring.cloud.openfeign.httpclient.hc5.enabled",
    havingValue = "true",
    matchIfMissing = true)                           // 默认启用!
protected static class HttpClient5FeignConfiguration {

    @Bean
    @ConditionalOnMissingBean(Client.class)
    public Client feignClient(org.apache.hc.client5.http.impl.classic.CloseableHttpClient httpClient5) {
        return new ApacheHttp5Client(httpClient5);
    }
}
```

三个条件全部满足时,自动创建 `ApacheHttp5Client` 作为 Feign 的 `Client` 实现:
1. `feign.hc5.ApacheHttp5Client` class 存在(classpath 上有 `feign-hc5` jar)
2. 没有自定义 `CloseableHttpClient` Bean
3. `spring.cloud.openfeign.httpclient.hc5.enabled` 未设置或为 true

### 2.2 切换回 HttpURLConnection

```yaml
spring.cloud.openfeign.httpclient.hc5.enabled: false
```

重启后 Feign 回退到 `Client.Default`(HttpURLConnection)。

## 三、验证

### 测试: 查看当前 HTTP 客户端

```
curl http://localhost:28002/feign/http-client-info
```

### 本 lab 实际环境结果

```json
{
    "activeClient": "Apache HttpClient5",
    "hc5OnClasspath": true,
    "connectivityTest": "ok"
}
```

- `activeClient: "Apache HttpClient5"` -- `feign-hc5` 在 classpath 上,默认启用
- `connectivityTest: "ok"` -- 当前客户端能正常调通 nacos-lab

### 切换验证

```yaml
# 禁用 HC5 -> 回退到 HttpURLConnection
spring.cloud.openfeign.httpclient.hc5.enabled: false
```

重启后 `activeClient` 应变为 `"HttpURLConnection"`。

## 四、为什么关注 HTTP 客户端

| 场景 | HttpURLConnection | Apache HttpClient5 |
|------|:---:|:---:|
| 连接池 | ❌ | ✅ 默认 200 连接 |
| HTTP/2 | ❌ | ✅ |
| 连接超时单独控制 | ❌ | ✅ |
| 空闲连接回收 | ❌ | ✅ |
| 对第三方依赖 | 无 | `feign-hc5` + `httpclient5` |
| 启动时间 | 快(无额外初始化) | 稍慢(连接池初始化) |

**生产推荐**: Apache HttpClient5(Spring Cloud 2025 默认)。连接池的好处:避免每次请求都建 TCP 连接,降低延迟。

## 五、生产注意

- **不要禁用客户端同时又不提供替代**: 如果 `spring.cloud.openfeign.httpclient.hc5.enabled=false` 且没有自定义 `Client` Bean,Spring 会尝试创建 `Client.Default`。但如果 `CloseableHttpClient` 类不在 classpath 上,应用无法启动。确保有回退方案
- **连接池参数**: 通过 `spring.httpclient.maxConnections` 等参数配置(非 Feign 属性,是 Apache HttpClient 的 Spring Boot 配置)
- **HTTP/2**: 启用 `spring.cloud.openfeign.http2client.enabled=true`(需要 JDK 11+ 的 HttpClient 支持)
