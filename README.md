# PolyData

> Dynamic multi-datasource framework for Spring Boot: child context isolation, hot-swappable DataSource, zone-aware high availability and ShardingSphere integration.
> 面向 Spring Boot 的动态多数据源框架：子上下文隔离、DataSource 热更新、同区域优先/故障转移/故障恢复、MyBatis/MyBatis-Plus 并存与 ShardingSphere 集成。

PolyData 解决一个具体问题：**一个应用需要多套数据源（不同区域、不同业务库、分库分表），如何在运行时动态切换、按区域优先选库，并让 MyBatis / MyBatis-Plus 在同一个进程里并存。**

一个例子：

```properties
# 一个订单服务：北美、中国、欧洲三地各有一套库
polydata.jdbc.configs.orders={"name":"orders","ha-datasource":{ \
  "defaultZone":[{"name":"ds","url":"jdbc:mysql://orders-us:3306/orders"}], \
  "cn-hangzhou":[{"name":"ds","url":"jdbc:mysql://orders-cn:3306/orders"}], \
  "eu-west-1":[{"name":"ds","url":"jdbc:mysql://orders-eu:3306/orders"}]}}
```

启动后 PolyData 会：

1. 为这套配置创建一个**隔离的子 Spring 上下文**（独立的数据源 / MyBatis / 事务），并把 `DataSource`、`TransactionManager`、`SqlSessionFactory` 等 Bean **升迁**到父上下文；
2. 服务调用时按 **PolyZone 的当前区域** 选择对应区域的库（同区域优先），区域切换事件触发 **DataSource 无重启热替换**；
3. 配置中心改配置时（PolyConfig 统一事件），同样触发热替换；
4. `sharding-sphere` 模块存在时，直接把 ShardingSphere 5.5.1 的数据源构建出来，物理库从同一份配置读取。

## 核心特性

| 能力 | 说明 |
|------|------|
| 模块化设计 | MyBatis / MyBatis-Plus / Transaction / DataSource / 多活（ha-datasource）/ ShardingSphere 六类模块，统一走 4 个 SPI 扩展点 |
| 动态 Spring 应用上下文 | 每个配置一个 `DynamicJdbcChildContext`，多配置并行初始化；Bean 选择性升迁父上下文 |
| JDBC DataSource 动态更新 | `DynamicDataSource` 深克隆配置 → 重建子上下文 → `volatile + synchronized` 原子替换 delegate → 延迟关闭旧上下文（等在途请求结束） |
| 同区域优先 | `ha-datasource` 按 `ZoneContext.getZone()` 选库，缺省回退 `defaultZone` |
| 故障转移 / 故障恢复 | 监听 PolyZone `ZoneContextChangedEvent` 重建 DataSource；配置中心回滚后同样自动切回 |
| 配置热更新 | 监听 PolyConfig `ConfigPropertiesChangedEvent`，按属性名匹配 `polydata.jdbc.configs.*` 重建 |
| ORM 隔离与并存 | `banned-modules`（如 mybatis ↔ mybatis-plus 互斥）+ 子上下文隔离，实现同进程并存 |
| ShardingSphere 5.x | YAML 规则 → `YamlShardingSphereDataSourceFactory` 直接构建数据源（不依赖官方 starter） |

## 架构

```
动态配置: polydata.jdbc.configs.<name> = JSON

DynamicJdbcContextApplicationListener (ApplicationPreparedEvent)
  └─ DynamicJdbcContextProcessor — 6 步管道
       1. registerAnnotationConfigProcessors
       2. ConfigPostProcessor SPI（预处理）
       3. ConfigValidator SPI（校验，失败阻断启动）
       4. processDynamic -> 注册 DynamicDataSource（深克隆配置）
       5. ConfigConfigurationPropertiesSynthesizer SPI（合成属性到 Environment）
       6. ConfigBeanDefinitionRegistrar SPI（注册事务/ORM/分片等 BeanDefinition）

DynamicDataSource（父上下文中的门面）
  └─ DynamicJdbcChildContext（每个配置一个子上下文）
       ├─ 物理 DataSource / ShardingSphere DataSource
       ├─ MyBatis / MyBatis-Plus / Transaction（按模块装配，互斥）
       └─ Bean 升迁 -> 父上下文（DataSource / TransactionManager / SqlSessionFactory）

事件驱动热替换：
  ZoneContextChangedEvent（PolyZone）─┐
  ConfigPropertiesChangedEvent（PolyConfig）─┴─> DynamicJdbcConfigChangedEvent
                                                └─> DynamicDataSource 重建子上下文并原子替换
```

## 模块与 SPI

```
polydata-jdbc-spring-boot
├── config/            配置模型（DynamicJdbcConfig）、SPI 接口与三层抽象
├── context/           6 步管道、子上下文、Bean 升迁、事件传播
├── datasource/        DynamicDataSource 热替换、URL 归一化、数据源校验
├── transaction/       事务管理器装配
├── mybatis/           MyBatis 模块（MapperScan / base-packages / 互斥校验）
├── mybatisplus/       MyBatis-Plus 模块
├── shardingsphere/    ShardingSphere 5.5.1 集成
├── autoconfigure/     自动配置导入过滤（模块互斥、按包前缀过滤、缓存）
├── env/               default.properties、属性展平
└── support/           自研工具（零第三方框架内部依赖）
```

四个 SPI 扩展点（新增一个模块只需实现它们并在 `spring.factories` 注册）：

| SPI | 职责 |
|-----|------|
| `ConfigPostProcessor` | 预处理配置（填默认值、规范化） |
| `ConfigValidator` | 校验配置（失败阻断启动） |
| `ConfigConfigurationPropertiesSynthesizer` | 把配置合成为 `spring.*` 属性 |
| `ConfigBeanDefinitionRegistrar` | 注册模块 BeanDefinition |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.loveeleve</groupId>
    <artifactId>polydata-jdbc-spring-boot</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- ORM / 分片按需引入（均为 optional） -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>
```

### 2. 配置

```properties
# 单库
polydata.jdbc.configs.primary={"name":"primary","datasource":[{"name":"ds","url":"jdbc:mysql://127.0.0.1:3306/demo"}]}

# 多活（同区域优先）
polydata.jdbc.configs.orders={"name":"orders","ha-datasource":{"defaultZone":[{"name":"ds","url":"jdbc:mysql://a:3306/db"}],"eu-west-1":[{"name":"ds","url":"jdbc:mysql://b:3306/db"}]}}

# MyBatis / MyBatis-Plus（约定 base-packages，二选一互斥）
polydata.jdbc.configs.user={"name":"user","datasource":[{"name":"ds","url":"jdbc:mysql://127.0.0.1:3306/user"}],"mybatis-plus":{"base-packages":"com.example.user.mapper"}}

# 分库分表（rules 用 ShardingSphere YAML）
polydata.jdbc.configs.goods={"name":"goods","datasource":[{"name":"ds","url":"jdbc:mysql://127.0.0.1:3306/goods"}],"sharding-sphere":{"config-resource":"classpath:/sharding/goods.yaml"}}
```

## 配置参考（关键属性）

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `polydata.jdbc.enabled` | `true` | 动态 JDBC 总开关 |
| `polydata.jdbc.configs.<name>` | - | 配置 JSON（支持 `classpath:` 外置文件） |
| `polydata.jdbc.modules.<module>.auto-configuration.base-packages` | 内置 | 各模块允许加载的自动配置包前缀 |
| `polydata.jdbc.modules.<module>.auto-configuration.banned-modules` | 内置 | 模块互斥（如 mybatis ↔ mybatis-plus） |
| `polydata.jdbc.modules.datasource.default-user-name/password` | - | 数据源默认账号密码 |
| `polydata.jdbc.modules.datasource.url.default-scheme` | `mysql://` | URL 省略 scheme 时的默认值 |
| `polydata.jdbc.modules.datasource.url.default-query-params.*` | `characterEncoding` 等 | URL 缺失时的默认参数 |
| `polydata.jdbc.multiple-context.bean-classes.expose` | `DataSource` 等 | 子上下文升迁到父上下文的 Bean 类型 |
| `polydata.jdbc.multiple-context.bean-classes.primary` | `DataSource` 等 | 升迁时标记 `@Primary` 的 Bean 类型 |

## 与生态的关系

- **PolyZone**：区域状态与切换事件（`ZoneContext` / `ZoneContextChangedEvent`）来自 PolyZone，`ha-datasource` 据此选库并热切换；
- **PolyConfig**：配置变更统一事件（`ConfigPropertiesChangedEvent`）来自 PolyConfig，驱动配置热更新；
- **ShardingSphere 5.5.1**：直接依赖核心 `shardingsphere-jdbc`，用 `YamlShardingSphereDataSourceFactory` 构建数据源，不依赖官方 Spring Boot starter；
- **MyBatis / MyBatis-Plus**：可选依赖，各自模块通过子上下文隔离，同进程并存。

## 构建与测试

```bash
# 要求 JDK 17+，Maven 3.8+
# 前置：本项目依赖 PolyZone 与 PolyConfig 的 1.0.0-SNAPSHOT 构件，
#       先把这两个仓库构建并安装到同一本地 Maven 仓库：
mvn -f ../polyzone/pom.xml clean install
mvn -f ../polyconfig/pom.xml clean install

# 再构建本项目
mvn clean test
```

80 个测试（全部 H2 / 纯内存，不依赖外部数据库）：配置解析与校验、URL 归一化、属性展平、四个 SPI 与管道、子上下文隔离与 Bean 升迁、DynamicDataSource 热替换、区域切换重建、配置中心热更新、MyBatis / MyBatis-Plus 同进程并存（各自子上下文隔离）、ShardingSphere 路由（`t_order` → `t_order_0/1`）与集成。

## 版本基线与设计要点

- 依赖基线：Spring Boot **3.2.5**、ShardingSphere **5.5.1 核心 + `YamlShardingSphereDataSourceFactory`**、
  MyBatis 3.0.5、MyBatis-Plus 3.5.7
- 自研 `support` 工具包（Format/Bean/PropertySources/Environment/Factories 等），零第三方框架内部依赖
- ShardingSphere 集成直接走 YAML 工厂（不依赖官方 starter），移除 Mode/Rule swapper 注册与属性合成等旧机制
- 事件接入：PolyConfig 统一配置事件 + PolyZone 区域事件
- default.properties 由 `EnvironmentPostProcessor` 直接加载
- 测试全部 H2（无外部数据库依赖）

## Roadmap

- [ ] 基于健康检查的自动故障转移（免手工切区域）
- [ ] 数据源级监控指标（Micrometer：连接、切换、路由命中）
- [ ] ShardingSphere Cluster 模式与治理中心支持
- [ ] 与 Spring Boot `ConfigData` 机制的对齐评估

## License

[Apache License 2.0](LICENSE)
