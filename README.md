# PolyZone

> Zone-aware multi-active routing framework for Spring Boot and Spring Cloud.
> 面向异地多活架构的可用区（Zone）感知路由框架。

PolyZone 解决一个具体问题：**多区域部署的服务，如何让调用尽量发生在同一个可用区，并且在某个区域故障时能通过配置中心秒级切换、恢复。**

一个例子：

```
北美（us-east-1a）、中国（cn-hangzhou-b）、欧洲（eu-west-1a）三地部署同一套微服务。
用户请求到达北美实例，该实例调用下游 user-service 时，服务实例列表里有三地的节点。
默认情况下 LoadBalancer 随机挑选，可能挑到欧洲节点，白白多出 100ms+ 的跨区域延迟。
接入 PolyZone 后：当前实例自动识别自己在 us-east-1a，优先只调 us-east-1a 的 user-service；
如果同区域节点不够（或占比过低），自动退化为全量列表，绝不把流量打死。
运维在配置中心把 polyzone.zone 改成 eu-west-1a：所有实例秒级切到欧洲区域；
再改成 originalZone：自动回到各自原本所在的区域。
```

## 特性

| 能力 | 说明 |
|------|------|
| 统一区域定位抽象 | `ZoneLocator` SPI + 组合定位器，内置 AWS EC2 / ECS 元数据、Spring 属性四类实现，屏蔽部署环境差异 |
| 同区域优先路由 | `ZonePreferenceFilter` 十步决策：同区域优先 + 就绪率阈值 + 最小可用数阈值 + 禁用区域，防止单区域过载 |
| 数据存储同区域 | `ZoneContextChangedEvent` 事件广播，数据源 / Redis 等组件可订阅事件联动切换 |
| 区域信息传播 | 注册时把当前区域写入注册中心 metadata（`InstancePreRegisteredEvent`），下游按 metadata 解析区域 |
| 配置中心动态切换 | 监听 `EnvironmentChangeEvent`（Nacos / Apollo / Spring Cloud Config 刷新都会触发），改配置即切换 |
| 故障恢复（回滚） | 配置 `polyzone.zone=originalZone` 回到自动识别的原始区域，支持反复故障转移 / 恢复 |
| 保护策略 | `zone-ready-percentage`（带 zone 的实例占比）+ `same-zone-min-available`（同区域最小可用数），任一不满足即退化为全量列表 |
| 可关闭 | `polyzone.zone.enabled` / `polyzone.zone.preference.enabled` 两级开关，默认关闭偏好（只定位不路由） |

## 模块

```
polyzone
├── polyzone-commons       # 无框架依赖：ZoneContext / ZonePreferenceFilter / ZoneResolver / ZoneAttachmentHandler
├── polyzone-spring        # Spring Framework：ZoneLocator SPI、组合定位器、区域变更事件与监听器
├── polyzone-spring-boot   # Spring Boot：自动配置、条件注解
├── polyzone-spring-cloud  # Spring Cloud：LoadBalancer 同区域优先、注册元数据附加
└── polyzone-aws           # AWS：EC2 元数据端点、ECS 容器元数据文件、ECS Task Metadata V4 定位器
```

依赖关系：

```
polyzone-commons <- polyzone-spring <- polyzone-spring-boot <- polyzone-spring-cloud
                            ^
                     polyzone-aws
```

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.loveeleve</groupId>
    <artifactId>polyzone-spring-cloud</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置

```yaml
polyzone:
  zone: eu-west-1a                # 当前区域；不配或配 originalZone 时走 ZoneLocator 自动定位（AWS EC2/ECS）
  zone.enabled: true              # 区域能力总开关（默认 true）
  zone.preference:
    enabled: true                 # 同区域优先开关（默认 false，建议显式开启）
    upstream:
      zone-ready-percentage: 100  # 带区域信息的实例占比阈值（默认 100）
      same-zone-min-available: 2  # 同区域最小可用实例数（默认 5）
      disabled-zone:              # 手动禁用故障区域，逗号分隔（默认空）
  zone.locator:
    timeout: 3000                 # 元数据端点超时（毫秒，默认 3000）
    fast-fail: false              # 定位失败是否阻止启动（默认 false）
  spring.cloud.loadbalancer.customized: true   # 把 PolyZone 的过滤器注册为所有 LB 客户端的默认配置

spring:
  cloud:
    loadbalancer:
      configurations: optimized-zone-preference   # 启用 PolyZone 的实例列表过滤器
```

### 3. 效果

- 服务调用：LoadBalancer 过滤出同区域实例，再交给原有负载均衡策略选一个；
- 注册传播：实例注册前自动把 `polyzone.zone` 写入注册中心 metadata；
- 动态切换：在配置中心改 `polyzone.zone`，所有实例监听 `EnvironmentChangeEvent` 后秒级生效；
- 故障恢复：改 `polyzone.zone=originalZone`，回到自动识别的原始区域。

## 配置项全表

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `polyzone.zone` | `defaultZone` | 当前区域；缺省或 `originalZone` 时触发自动定位 |
| `polyzone.zone.enabled` | `true` | 区域能力总开关 |
| `polyzone.zone.preference.enabled` | `false` | 同区域优先开关 |
| `polyzone.zone.preference.filter.order` | `10` | 过滤器顺序 |
| `polyzone.zone.preference.upstream.zone-ready-percentage` | `100` | 实例带区域信息的最低占比 |
| `polyzone.zone.preference.upstream.same-zone-min-available` | `5` | 同区域最小可用实例数 |
| `polyzone.zone.preference.upstream.disabled-zone` | - | 禁用区域列表（逗号分隔） |
| `polyzone.zone.locator.timeout` | `3000` | 元数据端点超时（毫秒） |
| `polyzone.zone.locator.fast-fail` | `false` | 定位失败时是否快速失败 |
| `polyzone.spring.cloud.loadbalancer.customized` | `false` | 注册 PolyZone 的 LB 默认配置 |

## 工作原理

### 区域定位链

`CompositeZoneLocator` 按 `Ordered` 顺序依次尝试，首个成功者胜出，结果缓存并写入系统属性
`polyzone.current-zone`：

| Order | 定位器 | 场景 |
|-------|--------|------|
| 5 | `EcsContainerMetadataFileZoneLocator` | AWS ECS（容器元数据文件 `ECS_CONTAINER_METADATA_FILE`） |
| 10 | `EcsTaskMetadataEndpointV4ZoneLocator` | AWS ECS（Task Metadata V4 `ECS_CONTAINER_METADATA_URI_V4`） |
| 15 | `Ec2AvailabilityZoneEndpointZoneLocator` | AWS EC2（元数据端点 `169.254.169.254`） |
| 20 | `DefaultZoneLocator` | 本地 / K8s / 物理机（Spring 属性 `polyzone.zone`，兜底 `defaultZone`） |

### 同区域优先算法

```
filter(instances):
  1. 列表 <= 1 或 zone 能力关闭或偏好关闭          -> 原样返回
  2. 当前 zone 为空或 defaultZone                  -> 原样返回（不做偏好）
  3. 剔除 disabled-zone 中的实例
     - 剔除后 <= 1 个                            -> 原样返回
  4. 统计带 zone 的实例占比
     - 占比 < zone-ready-percentage              -> 原样返回
  5. 筛选同 zone 实例
     - 数量 < same-zone-min-available            -> 原样返回
     - 否则返回同 zone 实例
```

任何一步不满足都退化为原列表——**偏好永远不能导致"无实例可用"**。

### 切换 / 恢复链路

```
配置中心改 polyzone.zone
  -> 配置刷新发布 EnvironmentChangeEvent（Nacos/Apollo/Spring Cloud Config 通用）
    -> ZoneContextChangedListener 读取最新配置
      -> ZoneContext.setZone(...) 触发 PropertyChangeEvent
        -> 发布 ZoneContextChangedEvent
          -> 业务组件（数据源、Redis 等）订阅后联动切换
          -> LoadBalancer 下一次选实例即生效（毫秒级）
```

`polyzone.zone=originalZone` 时，监听器返回**首次同步时记住的原始区域**，因此即使 zone 属性被显式改过，
也能正确回滚到原始区域。

## 与 Spring Cloud 内置 ZonePreference 的对比

| | Spring Cloud 内置 | PolyZone |
|--|------------------|----------|
| 区域发现 | 手动配置 `spring.cloud.loadbalancer.zone` | 4 类 ZoneLocator 自动发现（AWS EC2/ECS + 属性） |
| 安全阈值 | 无 | 就绪率 + 最小可用数双阈值 |
| 禁用区域 | 无 | `disabled-zone` |
| 动态切换 | 改配置需刷新上下文 | `EnvironmentChangeEvent` 秒级生效 |
| 资源联动 | 无 | `ZoneContextChangedEvent` 广播到数据源/缓存 |
| 故障恢复 | 无 | `originalZone` 回滚 |

## 构建与测试

```bash
# 要求 JDK 17+，Maven 3.8+
mvn clean test
```

当前测试覆盖：定位链、优先算法全分支、配置中心切换链路、故障转移与回滚、AWS 三类定位器、LoadBalancer 过滤与配置装配、工厂加载生命周期回调，共 132 个用例。

## 注意事项

- **非 AWS 环境的定位开销**：`Ec2AvailabilityZoneEndpointZoneLocator.supports()` 恒为 true，在非 AWS
  环境会先尝试访问 `169.254.169.254`，直到超时（默认 3s）才回退到属性定位。生产上非 AWS 环境建议显式
  配置 `polyzone.zone`，或在 `originalZone` 首次同步时接受一次 3s 的启动开销。
- **区域信息附加依赖自动注册**：`ZoneAttachmentListener` 监听 Spring Cloud 的
  `InstancePreRegisteredEvent`，该事件由 `AbstractAutoServiceRegistration` 发布（Nacos/Eureka/Consul
  的自动注册均继承自它）；手工注册实例不会触发。
- **回滚基线语义**：`originalZone` 回到"首次同步时的区域"——属性缺省时由定位链自动识别（AWS 元数据），
  否则为首次配置的区域值。

## 设计要点

- 模块精简：移除 Netflix/Ribbon/Eureka 专属模块（Ribbon 已停止维护），注册场景由原生
  `InstancePreRegisteredEvent` 覆盖
- 属性命名空间 `polyzone.zone.*`，两级开关（能力总开关 + 偏好开关）
- `originalZone` 回滚语义：记住首次同步的原始区域，即使 zone 属性被显式改过也能正确回滚
- `PropertyChangeEvent` 时序：先提交新值再通知监听器，监听器内读到的永远是最新值
- 定位器支持 `META-INF/spring.factories` 扩展，并补齐 `EnvironmentAware` 等生命周期回调，
  `locator.timeout` 等属性生效
- 仅依赖 Spring 官方组件，零第三方框架内部依赖

## Roadmap

- [ ] 跨区域延迟指标（Micrometer）与偏好命中率监控
- [ ] Spring Cloud Gateway 区域路由示例
- [ ] 基于健康检查的自动故障转移（免手工改配置）
- [ ] Kubernetes Downward API 定位器

## License

[Apache License 2.0](LICENSE)
