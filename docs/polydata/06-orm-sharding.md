---
title: "06-原理五：ORM 并存与分片集成"
series: "PolyData 动态多数据源系列"
previous: "05-zone-ha.md"
next: "07-usage.md（待写）"
source_refs:
  - "已核：互斥链 getModuleAutoConfigurationBannedModules:164→getModuleExclusionAutoConfigurationClassNames:169-177（banned 模块→排除其自动配置类）消费方 AbstractModuleConfigConfigurationPropertiesSynthesizer:74/SS Synthesizer:51；default.properties 双向互斥 :45/:49、:12/:33；前缀白名单 DynamicJdbcAutoConfigurationImportFilter:41（match:65-74/isClassPresent:76）；每模块 4 SPI 对称（mybatis/mybatisplus 均含 config/context/env/validation）；@MapperScan 占位 MybatisMapperScanConfiguration:51；SS 工厂 ShardingSphereConfigurationConfigBeanDefinitionRegistrar:115 YamlShardingSphereDataSourceFactory.createDataSource(dataSourceMap, yamlContent)"
cross_refs:
  - "orm-pool 05-07（MyBatis/MP 内核与用法）+ shardingsphere 系列（分片内核）——本篇只讲 polydata 侧的并存与建池"
---

# 06-原理五：ORM 并存与分片集成

## §0 它干了什么（例子先行）

一个进程同时跑 MyBatis 和 MyBatis-Plus（orm-pool 07 说过这俩互斥），外加分库分表：

```properties
# 两个配置各自成世界（README :116/:119）
polydata.jdbc.configs.user={"name":"user","datasource":[...],"mybatis-plus":{"base-packages":"com.example.user.mapper"}}
polydata.jdbc.configs.goods={"name":"goods","datasource":[...],"sharding-sphere":{"config-resource":"classpath:/sharding/goods.yaml"}}
```

**三重防线让互斥的东西共存**：

```
① 自动配置互斥：banned-modules → 收集对方自动配置类进排除集合（:169-177）
   default.properties 双向拉黑：mybatis↔mybatis-plus :45/:49、datasource↔sharding-sphere :12/:33
② 前缀白名单：ImportFilter:41 只放行"本模块 base-packages 内"的自动配置（match:65-74）
③ 子上下文物理隔离（03 篇）：各自 SqlSessionFactory/池/事务，互不可见
```

**分片**：注册器（4 SPI 之一）直接调 `YamlShardingSphereDataSourceFactory.createDataSource(dataSourceMap, yamlContent):115`——**物理源取自 polydata 配置、规则取自 YAML**（README:36"不依赖官方 starter"）。

- **它干了干什么**：**并存=排除+过滤+隔离三件套**；分片=把 SS 5.5.1 当"数据源工厂"嵌进来。
- **本篇回答**：4 SPI 在模块里怎么对称落地？@MapperScan 占位怎么工作？SS 为什么能不走 starter？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **模块=4 SPI 对称** | 每个模块四件套同构：`config/`（PostProcessor）+`validation/`（Validator）+`env/`（Synthesizer）+`context/`（Registrar）——mybatis 与 mybatisplus 包结构逐一对称（02 篇 4 SPI 的落地形状） |
| **互斥三机制分工** | banned=**排他**（对方自动配置进黑名单）/ base-packages 前缀=**准入**（只装自己的包）/ 子上下文=**隔离**（装了也看不见） |
| **@MapperScan 占位符** | MybatisMapperScanConfiguration:51 用 `@MapperScan(basePackages=占位)`——具体包名由 Synthesizer **合成属性注入**（配置里 `mybatis.base-packages` → 展开）——**注解声明、属性驱动** |
| **SS 数据源工厂** | Registrar:115：`dataSourceMap`（polydata 的物理源，含 05 篇 zone 选库结果）+ `yamlContent`（config-resource 规则文件）→ **一个 DataSource**——绕开 starter 的自动装配，**走的是自己 6 步管道的第 6 步** |

## §2 关键推导（两问）

**① 为什么必须"排除对方自动配置"？**
两者都会抢同一批扩展点（orm-pool 07/06 讲过：都动 SqlSessionFactory 生态）——**同上下文里两边自动配置都跑=装配冲突/后到覆盖**。banned 在启动最早期（auto-config 过滤）就把对方拦掉：**同一子上下文里只会有赢家**，输家在另一个子上下文（③隔离）赢——**"并存"的真相是"各自独赢"**。

**② SS 为什么不依赖官方 starter？**
starter 的职责=自动配置+属性绑定——polydata 已有自己的管道（属性绑定、校验、装配全有），**再套 starter=两套自动配置打架**（与①同理）。直接用 `YamlShardingSphereDataSourceFactory` 核心 API：**数据源从自己的配置来、规则从 YAML 来**——控制权完整。README:163"移除 Mode/Rule swapper 注册与属性合成等旧机制"=同一取舍。

**③ 与已封卷系列的对账**：
分片规则怎么写/怎么路由 → **shardingsphere 系列**（t_order→t_order_0/1 用例 README:156 与该系列同款）；MP 怎么用 → **orm-pool 07**——**本篇只负责"建与并"，不重复内核**。

## §3 掌握卡（2 条）

1. **并存三件套**：互斥排除（:169-177+default.properties 双向）+ 前缀白名单（:65-74）+ 子上下文隔离（03）——**真相=各自独赢**。
2. **模块对称**：4 SPI 在每模块落成 4 个包；分片模块=Registrar 直调 SS YAML 工厂（:115）——**6 步管道第 6 步的实战**。

## §4 边界（一句话翻篇）

- 分片规则/算法/读写分离 → shardingsphere 系列翻篇。
- MP wrapper/逻辑删除 → orm-pool 07 已封卷。
- SS Cluster/治理中心 → Roadmap:172 未实现。

## §5 Self-Test（3 题）

- Q1：并存三件套各在哪个时点起作用？
- Q2：banned-modules 的机制链（属性→排除集合→消费方）？
- Q3：SS 数据源的两个输入各来自哪？

## §6 衔接

- 原理五篇收口；下篇 07 使用：四配置姿势（单库/多活/ORM/分片）+ 关键属性表。
