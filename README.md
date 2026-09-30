# PolyConfig

> Unified configuration center abstraction for Spring: declarative property sources, unified config change events and backend adapters.
> 面向 Spring 生态的配置中心统一抽象：声明式外部配置源 + 统一配置变更事件 + 多配置中心适配。

PolyConfig 解决一个具体问题：**应用从配置中心（Nacos 等）加载的配置，如何统一地声明、自动刷新，并以一种事件让业务组件感知。**

一个例子：

```java
@NacosPropertySource(value = "application.properties", group = "DEFAULT_GROUP",
                     serverAddress = "127.0.0.1:8848")
@Configuration
public class ApplicationConfig {
}
```

启动时 PolyConfig 自动把 Nacos 上 `application.properties` 的内容加载进 Spring `Environment`；
Nacos 上改了配置，Environment 自动刷新，并发布统一事件：

```java
@Component
public class MyConfigListener implements ApplicationListener<ConfigPropertiesChangedEvent> {
    public void onApplicationEvent(ConfigPropertiesChangedEvent event) {
        if (event.isChanged("my.datasource.url")) {
            // 重建连接池 / 刷新数据源 ...
        }
    }
}
```

业务组件只认 `ConfigPropertiesChangedEvent` 一种事件，不需要关心配置来自 Nacos、Apollo 还是 Spring Cloud 原生刷新。

## 三层结构

```
第 1 层：元注解（声明）
  @PropertySourceExtension
    └─ @NacosPropertySource / ...（后端注解，@AliasFor 映射通用属性）
       └─ 标注在 @Configuration 上声明外部配置源

第 2 层：加载器（启动时）
  PropertySourceExtensionLoader<A>
    └─ NacosPropertySourceLoader（通过 @Import 触发，把配置加载进 Environment）
       └─ 后端差异只在 resolveResources() 与 configureRefresher() 中

第 3 层：事件（运行时）
  ConfigPropertiesChangedEvent（统一事件）
    └─ 声明式刷新（PropertySourceExtensionLoader + ResourcePropertySourcesRefresher）
    └─ 环境刷新适配（EnvironmentConfigChangeListener，Nacos/Apollo/Spring Cloud Config 通用）
```

## 模块

```
polyconfig
├── polyconfig-spring        # 核心：元注解、Loader 模板、统一事件、刷新器
├── polyconfig-spring-boot   # 自动配置：事件发布器 + EnvironmentChangeEvent 适配
└── polyconfig-nacos         # Nacos 后端：@NacosPropertySource + 加载器 + 自动刷新
```

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.loveeleve</groupId>
    <artifactId>polyconfig-nacos</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 声明式加载 Nacos 配置

```java
@NacosPropertySource(
        value = {"application.properties", "order-service.yaml"},  // dataId，支持 .properties/.yml/.yaml
        group = "DEFAULT_GROUP",
        serverAddress = "127.0.0.1:8848",
        namespace = "public",
        autoRefreshed = true)
@Configuration
public class NacosConfig {
}
```

### 3. 监听统一配置变更事件

```java
@Component
public class OrderConfigListener implements ApplicationListener<ConfigPropertiesChangedEvent> {

    @Override
    public void onApplicationEvent(ConfigPropertiesChangedEvent event) {
        // 事件来自声明式加载器（source = 属性源名）或环境适配（source = "environment"）
        for (String propertyName : event.getChangedPropertyNames()) {
            Object oldValue = event.getOldProperties().get(propertyName);
            Object newValue = event.getNewProperties().get(propertyName);
            // 处理变更 ...
        }
    }
}
```

## 注解属性

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `value` | - | Nacos dataId 列表（`.properties` / `.yml` / `.yaml`） |
| `name` | 自动生成 | PropertySource 名称 |
| `autoRefreshed` | `true` | 服务端配置变更时自动刷新并发布事件 |
| `first` | `false` | 放到 Environment 首位 |
| `before` / `after` | - | 相对其他 PropertySource 的位置 |
| `ignoreResourceNotFound` | `false` | 配置不存在时是否忽略 |
| `encoding` | `UTF-8` | 编码（YAML 固定 UTF-8） |
| `serverAddress` | `127.0.0.1:8848` | Nacos 地址（支持多地址） |
| `namespace` | 空 | Nacos 命名空间 |
| `group` | `DEFAULT_GROUP` | Nacos 分组 |
| `username` / `password` | 空 | Nacos 鉴权 |
| `timeout` | `3000` | 拉取配置超时（毫秒） |

## 工作原理

### 启动加载

```
Spring 解析 @Configuration
  -> 发现 @Import(NacosPropertySourceLoader.class)
    -> loader.selectImports() 读取 @NacosPropertySource 属性
      -> resolveResources() 调 Nacos ConfigService.getConfig(dataId, group, timeout)
        -> ResourcePropertySources.load() 解析 .properties/.yml
          -> 组装 CompositePropertySource 加入 Environment（first/before/after）
          -> autoRefreshed=true 时注册 Nacos Listener
```

### 运行时刷新

```
Nacos 服务端配置变更
  -> Listener.receiveConfigInfo(newContent)
    -> ResourcePropertySourcesRefresher.refresh(dataId, newResource)
      -> 新旧内容 diff（只对真正变化的属性生成变更）
      -> 替换 Environment 中的 PropertySource
      -> 发布 ConfigPropertiesChangedEvent（source = 属性源名）
```

### 环境适配（无需注解）

```
Nacos/Apollo/Spring Cloud Config 标准刷新
  -> Spring Cloud EnvironmentChangeEvent
    -> EnvironmentConfigChangeListener（polyconfig-spring-boot 自动配置）
      -> 发布 ConfigPropertiesChangedEvent（source = "environment"）
```

## 与现有方案的关系

| | Spring `@PropertySource` | Spring Cloud 原生刷新 | PolyConfig |
|--|--------------------------|----------------------|------------|
| 配置来源 | classpath 文件 | 配置中心（各客户端自管） | 配置中心 + 文件（统一抽象） |
| 声明方式 | 注解 | 无（客户端行为） | 注解（后端注解 + @AliasFor） |
| 热更新 | 不支持 | `@RefreshScope` / `EnvironmentChangeEvent` | 自动刷新 + 统一事件（含新旧值） |
| 变更感知 | 无 | 仅属性名（EnvironmentChangeEvent） | 统一事件：属性名 + 新旧值 + 来源 |
| 后端扩展 | - | 各客户端独立 | Loader 模板，新增后端只实现 2 个方法 |

PolyConfig 不替代各配置中心客户端，而是把它们的加载/刷新统一成一套注解模型和事件模型。

## 构建与测试

```bash
# 要求 JDK 17+，Maven 3.8+
mvn clean test
```

当前 24 个用例：统一事件模型、事件发布、刷新器（diff/替换/无变化不发事件/线程安全）、Loader 模板（加载/顺序/自动刷新/资源缺失/YAML/三方后端注解缺省属性）、真实 ApplicationContext 端到端、自动配置、EnvironmentChangeEvent 适配、Nacos 加载与自动刷新。

## 注意事项

- **事件来源可能重复**：如果同时使用声明式加载（`@NacosPropertySource`）与 Spring Cloud 标准刷新，同一次
  配置变更可能产生两个 `ConfigPropertiesChangedEvent`（source 分别为属性源名与 `"environment"`）。消费端应
  做幂等处理或按 `source` 过滤。
- **环境适配的旧值为空**：`EnvironmentChangeEvent` 只携带变更的属性名，因此 `"environment"` 来源的事件中
  `oldValue` 为 `null`，请以 `getNewProperties()` 为准。
- **YAML 编码固定 UTF-8**：`.yml` / `.yaml` 由 SnakeYAML 按 UTF-8 解析，`encoding` 属性只对
  `.properties` / `.xml` 生效。
- **刷新是线程安全的**：`ResourcePropertySourcesRefresher` 的状态更新在锁内完成，事件在锁外发布，避免
  Nacos 推送线程与业务线程竞争，也避免监听器在持锁状态下执行。
- **暂不支持资源通配符**：如 `classpath*:/config/*.properties` 的批量资源模式尚未实现（按需补充）。

## 设计要点

- 事件模型为属性级：属性名 + 新旧值 + 来源，贴合"哪些配置变了"的消费场景
- 通用适配：`EnvironmentChangeEvent` → 统一事件（非注解加载路径同样有事件）
- Nacos 后端基于官方 `nacos-client` SDK，自动刷新更可靠
- Loader 对后端注解属性读取有缺省兜底（三方后端注解不必声明全部通用属性）
- 刷新器线程安全：状态更新在锁内完成，事件在锁外发布
- Apollo / etcd / ZooKeeper 后端在 Roadmap

## Roadmap

- [ ] Apollo 适配模块（`@ApolloPropertySource`）
- [ ] etcd / ZooKeeper 适配模块
- [ ] 配置变更审计（变更历史 + 来源标记）
- [ ] 与 Spring Boot `ConfigData` 机制的对齐评估

## License

[Apache License 2.0](LICENSE)
