# A-09: RestClient 对比

## 一、为什么对比

Spring 6 引入了 `RestClient`,作为 `RestTemplate` 的现代替代品。与 OpenFeign 相比:

| 维度 | OpenFeign | RestClient |
|------|-----------|------------|
| 编程模型 | 声明式(`@FeignClient` + interface) | 命令式(`RestClient.builder()`) |
| 代理生成 | 运行时(JDK 动态代理,`@FeignClient` 扫描后创建) | 运行时(Builder 模式直接构造) |
| 依赖 | `spring-cloud-starter-openfeign` | `spring-boot-starter-web`(已内置) |
| 负载均衡 | 内置(FeignBlockingLoadBalancerClient) | 需手动集成 |
| 熔断 | 内置(Sentinel/Hystrix 集成) | 需手动集成 |
| 调用代码量 | 接口声明 ≈ 3 行 | 每次调用 ≈ 5 行 |
| 启动开销 | 需扫描 + 代理生成 | 几乎为零 |

## 二、本 Lab 实现

### 2.1 Feign 版本

```java
@FeignClient(name = "nacos-lab-v1", url = "http://localhost:28001")
public interface NacosLabV1Client {
    @GetMapping("/health")
    String health();
}
```

### 2.2 RestClient 版本

```java
@Component
public class RestClientExample {

    private final RestClient restClient;

    public RestClientExample() {
        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:28001")
                .build();
    }

    public String health() {
        long start = System.nanoTime();
        String result = restClient.get()
                .uri("/health")
                .retrieve()
                .body(String.class);
        log.debug("[RestClient] GET /health cost={}ns response={}", System.nanoTime() - start, result);
        return result;
    }
}
```

### 2.3 关键差异

| 方面 | Feign | RestClient |
|------|-------|-----------|
| **类型安全** | ✅ 接口方法声明返回值,编译期约束方法签名 | ✅ `body(Class)` 也有编译期类型约束,两者都是运行时再解码 |
| **URL 管理** | 接口级声明 | 每次调用指定 URI(或 baseUrl + URI) |
| **复杂响应** | 自动反序列化为 `Map<String,Object>` | 需 `ParameterizedTypeReference` |
| **重试/超时** | 声明式配置 | 需手动配置 |
| **调试** | 代理机制不透明 | Builder 链式调用,IDE 可调试 |

## 三、验证

```
curl http://localhost:28002/feign/restclient-test
```

```json
{
    "v1": {
        "feignResult": "nacos-lab is running",
        "restClientResult": "nacos-lab is running",
        "same": true,
        "feignCostMs": 35,
        "restClientCostMs": 17
    },
    "v2": {
        "feignResult": { "applicationName": "nacos-lab", "port": 28001, ... },
        "restClientResult": { "applicationName": "nacos-lab", "port": 28001, ... },
        "same": true,
        "feignCode": "@FeignClient + interface: 3 行声明",
        "restClientCode": "RestClient.builder() + .get().retrieve() + ParameterizedTypeReference"
    }
}
```

## 四、选型建议

### 用 OpenFeign 当:
- 微服务间大量 HTTP 调用(10+ 个客户端)
- 需要负载均衡 + 熔断降级
- 团队偏好声明式编程
- 已有 Spring Cloud 基础设施

### 用 RestClient 当:
- 少量外部 HTTP 调用(1-3 个)
- 不需要负载均衡/熔断
- 追求最小依赖和启动速度
- 单体应用或非 Spring Cloud 项目

### 结论

`RestClient` 是"工具",`OpenFeign` 是"框架"。工具灵活但每次写模板代码,框架有约束但消除重复。

**选型无绝对优劣**: 大量微服务间调用选 Feign(声明式 + 负载均衡 + 熔断开箱即用);少数外部调用选 RestClient(零依赖,轻量灵活)。
