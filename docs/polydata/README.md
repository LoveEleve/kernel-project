# PolyData 动态多数据源系列

> 语料：**polydata 源码**（95 非测试类，单模块 polydata-jdbc-spring-boot——已核）+ 仓库 README（177 行：例子/特性/6 步管道/4 SPI/配置参考/版本基线实锤）
> 关键锚点（已核）：DynamicJdbcContextProcessor（:56/管道 process:66-86 六步 :69,74,77,79,83,86）/ DynamicDataSource（:56/volatile delegate:82/:84/synchronized 原子替换 :202-205/延迟关闭 :213/深克隆 :237）/ DynamicJdbcConfigUtils.cloneDynamicJdbcConfig:176 / DynamicJdbcChildContext:46（extends AnnotationConfigApplicationContext）/ Bean 升迁 DynamicJdbcContextRefreshedListener:157-164 / ha 选库 DynamicJdbcConfig:249-257（zone 缺回退 defaultZone）/ 双事件汇聚 PropagatingDynamicJdbcConfigChangedEventListener:47→RefreshingDynamicDataSourceListener:312
> 依赖位次：**poly 家族 ③**（依赖 ① polyconfig + ② polyzone——本系列是两者的消费端总装）
> 版本基线（README :160）：Boot 3.2.5 / ShardingSphere 5.5.1 核心+YamlShardingSphereDataSourceFactory / MyBatis 3.0.5 / MyBatis-Plus 3.5.7——与全会话口径一致
> 诚实边界：**无 lab、无拷打**；测试全 H2（80 个用例，README :156）

## 一、总表（8 篇：概念 → 原理 → 使用 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 PolyData：架构全景](01-what-is-polydata.md) | **概念** | 四件事/6 步管道/4 SPI；与生态关系 | 仓库 README |
| 02 | [原理一：配置模型与 6 步管道](02-config-pipeline.md) | **原理** | JSON 配置怎么变成运行时上下文 | Processor 源码 |
| 03 | [原理二：子上下文隔离与 Bean 升迁](03-child-context.md) | **原理** | 每配置一个子上下文；expose/primary 升迁 | ChildContext/升迁监听器 |
| 04 | [原理三：热替换](04-hot-swap.md) | **原理** | 深克隆→重建→volatile+synchronized 原子换→延迟关 | DynamicDataSource 源码 |
| 05 | [原理四：多活选库与容错](05-zone-ha.md) | **原理** | ha-datasource 按 zone 选库/回退；双事件驱动 | DynamicJdbcConfig/双监听器 |
| 06 | [原理五：ORM 并存与分片集成](06-orm-sharding.md) | **原理** | banned-modules 互斥/隔离并存；YAML 工厂建分片源 | mybatis/模块与 SS 源码 |
| 07 | [使用：配置参考与四姿势](07-usage.md) | **使用** | 单库/多活/ORM/分片四配置姿势+关键属性 | README 快速开始 |
| 08 | [面试收口](08-interview-wrapup.md) | **面试** | 七篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03 → 04 → 05 → 06
使用：07
收口：08
```

## 三、前置要求

- **polyconfig ①**（配置变更事件源头）与 **polyzone ②**（区域状态/切换事件）——本系列是它们的消费端。
- orm-pool 系列 05-07（MyBatis 核心/MP 已封卷——06 篇是其上的并存与分片题）。
- shardingsphere 系列（已封卷——06 篇只讲 polydata 怎么建数据源，规则内核翻篇）。

## 四、掌握分级

- **必须掌握**：6 步管道；子上下文+升迁；热替换四步（克隆/重建/原子换/延迟关）；双事件汇聚；ORM 互斥并存。
- **理解思想**：**隔离换并存、原子换零中断、延迟关不杀在途**。
- **知道即可**：URL 归一化细节；ShardingSphere Cluster 模式（Roadmap）；support 自研工具包。

## 五、边界

- ShardingSphere 规则内核/分片算法 → shardingsphere 系列已封卷。
- MyBatis 执行链细节 → orm-pool 05 已封卷。
- 健康检查自动故障转移 → Roadmap 未实现。
