# D7: FeignClientProperties 配置优先级

> 源码版本: Spring Cloud OpenFeign 4.1.3（Spring Cloud 2023.0.3，xhs-project 口径）
> 源码路径: `FeignClientProperties.java` (383 行)
> 所在模块: `spring-cloud-openfeign-core`

## 一、类定位

`FeignClientProperties` 是 `spring.cloud.openfeign.client.*` 配置的绑定类。它是 **Spring Cloud OpenFeign 中连接 @FeignClient 注解与 application.yml 配置的桥梁**。

```java
@ConfigurationProperties("spring.cloud.openfeign.client")
public class FeignClientProperties {
    private boolean defaultToProperties = true;
    private String defaultConfig = "default";
    private Map<String, FeignClientConfiguration> config = new HashMap<>();
}
```

三个字段决定配置优先级策略：
- `defaultToProperties` — 是否优先使用配置文件中的属性（默认 true）
- `defaultConfig` — 默认配置的名称（默认 "default"）
- `config` — 按 clientName 分组的配置映射

## 二、FeignClientConfiguration

每个 Feign 客户端的配置对应 `FeignClientConfiguration` 对象：

| 字段 | 类型 | 对应能力 |
|------|------|---------|
| `loggerLevel` | Logger.Level | 日志级别 |
| `connectTimeout` | Integer | 连接超时 |
| `readTimeout` | Integer | 读取超时 |
| `retryer` | Class\<Retryer\> | 重试器 |
| `errorDecoder` | Class\<ErrorDecoder\> | 错误解码器 |
| `requestInterceptors` | List\<Class\<RequestInterceptor\>\> | 请求拦截器 |
| `responseInterceptor` | Class\<ResponseInterceptor\> | 响应拦截器 |
| `encoder` | Class\<Encoder\> | 编码器 |
| `decoder` | Class\<Decoder\> | 解码器 |
| `contract` | Class\<Contract\> | 契约 |
| `capabilities` | List\<Class\<Capability\>\> | 能力扩展 |
| `queryMapEncoder` | Class\<QueryMapEncoder\> | 查询参数编码器 |
| `exceptionPropagationPolicy` | ExceptionPropagationPolicy | 异常传播策略 |
| `dismiss404` | Boolean | 404 是否视为正常响应 |
| `followRedirects` | Boolean | 是否跟随重定向 |
| `url` | String | 客户端 URL（YAML 配置直连） |

> 4.1.3 的 `FeignClientConfiguration` **没有 `decode404`**（旧版属性，已移除）；404 行为由 `dismiss404` + `ErrorDecoder` 决定。

## 三、配置优先级规则（核心）

配置优先级从高到低：

`defaultToProperties = true`（默认）时，**后应用者胜**，优先级从高到低：

```
1. spring.cloud.openfeign.client.config.<clientName>.* (特定客户端)
2. spring.cloud.openfeign.client.config.default.* (默认配置)
3. @FeignClient(configuration = ...) 中的 @Bean 定义
4. FeignClientsConfiguration 的 @Bean (框架默认值)
```

`defaultToProperties = false` 时调用顺序反转，`@FeignClient(configuration)` 覆盖属性配置。

实现在 `FeignClientFactoryBean.configureFeign()` 中（4.1.3:171-181）：

```java
if (properties.isDefaultToProperties()) {
    configureUsingConfiguration(context, builder);
    configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()),
            properties.getConfig().get(contextId), builder);
}
else {
    configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()),
            properties.getConfig().get(contextId), builder);
    configureUsingConfiguration(context, builder);
}
```

注意 `configureUsingProperties` 是**一次调用传两个 config**（不是两次调用），内部先 base（default）后 final（clientName）依次应用（4.1.3:252-255）：

```java
protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration baseConfig,
        FeignClientProperties.FeignClientConfiguration finalConfig, Feign.Builder builder) {
    configureUsingProperties(baseConfig, builder);
    configureUsingProperties(finalConfig, builder);
}
```

**注意**：`FeignClientProperties` 有一个 `defaultToProperties = true`（默认值）。当它为 true 时，**配置文件的属性会覆盖 @FeignClient 注解中的 configuration 类**。这是许多人踩坑的地方——在 @FeignClient 的 configuration 类里设置了超时，但 application.yml 里又配了同一个客户端的超时，后者的优先级更高。

## 四、openfeign-lab 应用实例

当前项目的 application.yml 配置：

```yaml
spring.cloud.openfeign.client.config.nacos-lab.connectTimeout: 5000
spring.cloud.openfeign.client.config.nacos-lab.readTimeout: 5000
spring.cloud.openfeign.client.config.nacos-lab-direct.connectTimeout: 2000
spring.cloud.openfeign.client.config.nacos-lab-direct.readTimeout: 2000
spring.cloud.openfeign.client.config.nacos-lab-v1.connectTimeout: 2000
spring.cloud.openfeign.client.config.nacos-lab-v1.readTimeout: 3000
spring.cloud.openfeign.client.config.nacos-lab-v2.connectTimeout: 2000
spring.cloud.openfeign.client.config.nacos-lab-v2.readTimeout: 5000
```

这表示每个 Feign 客户端独立配置超时，**default.* 未配置**，所以框架默认值（FeignClientsConfiguration 中的 connectTimeout/readTimeout）会作为兜底。

## 五、源码入口

- 配置绑定入口：`FeignClientProperties.java:49` (@ConfigurationProperties)
- 配置应用：`FeignClientFactoryBean.java:171-181`（configureFeign 调用顺序）、`:252-255`（两个 config 的应用顺序）
- 默认配置类：`FeignClientsConfiguration.java` (Spring 自动装配的 @Bean)
