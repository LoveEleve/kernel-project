---
title: "01-什么是 PolyData：架构全景"
series: "PolyData 动态多数据源系列"
previous: "README.md"
next: "02-config-pipeline.md（待写）"
source_refs:
  - "仓库 README（177 行：例子/核心特性 8 条/架构图/4 SPI/版本基线）+ 源码结构已核（95 类，10 个包）"
cross_refs:
  - "polyconfig ①（配置事件）+ polyzone ②（区域事件）——本系列是两者的总装消费端；orm-pool 05-07（MyBatis/MP 内核）；shardingsphere（分片内核）"
---

# 01-什么是 PolyData：架构全景

## §0 它干了什么（例子先行）

一个订单服务三地各有库，**一行配置 JSON** 解决（README 例子实锤）：

```properties
polydata.jdbc.configs.orders={"name":"orders","ha-datasource":{ \
  "defaultZone":[{"name":"ds","url":"jdbc:mysql://orders-us:3306/orders"}], \
  "cn-hangzhou":[{"name":"ds","url":"jdbc:mysql://orders-cn:3306/orders"}], \
  "eu-west-1":[{"name":"ds","url":"jdbc:mysql://orders-eu:3306/orders"}]}}
```

启动后 PolyData 做四件事（README :18-23）：

```
① 每套配置建一个【隔离子上下文】（独立 DataSource/MyBatis/事务）
   → 关键 Bean【升迁】到父上下文（外面照常用）
② 调用时按 PolyZone 当前区域选库（同区优先），区域切换→DataSource【无重启热替换】
③ 配置中心改配置（PolyConfig 统一事件）→ 同样热替换
④ 引了 sharding-sphere 模块 → ShardingSphere 5.5.1 数据源直接从同一份配置建出来
```

- **它干了干什么**：**多套数据源的生命周期管理**——建（隔离）、换（原子热替换）、选（区域路由）、并（ORM 共存）。
- **本篇回答**：整体架构几层？和 polyconfig/polyzone 是什么关系？

## §1 概念铺垫（What：架构全景——README :40-62 图）

```
配置层   polydata.jdbc.configs.<name> = JSON（单库/多活/ORM/分片一个模型装下）
启动层   DynamicJdbcContextApplicationListener（ApplicationPreparedEvent 触发）
           └─ DynamicJdbcContextProcessor ——【6 步管道】（02 篇）
                1 注册处理器 2 PostProcessor 预处理 3 Validator 校验（失败阻断启动）
                4 processDynamic 注册 DynamicDataSource（深克隆） 5 属性合成 6 注册模块 BeanDefinition
运行层   DynamicDataSource（父上下文门面:56）
           └─ DynamicJdbcChildContext（每配置一个子上下文:46）
                ├─ 物理源 / 分片源 / MyBatis(+) / 事务（按模块装配）
                └─ 升迁 → 父上下文（DataSource/TransactionManager/SqlSessionFactory:157-164）
事件层   ZoneContextChangedEvent（②）─┐
         ConfigPropertiesChangedEvent（①）─┴→ DynamicJdbcConfigChangedEvent
                                              └→ DynamicDataSource 重建并原子替换（04 篇）
```

**4 个 SPI 扩展点**（README :80-87——"新增模块只实现+spring.factories 注册"）：

| SPI | 职责 |
|---|---|
| ConfigPostProcessor | 预处理（默认值/规范化） |
| ConfigValidator | 校验——**失败阻断启动** |
| ConfigConfigurationPropertiesSynthesizer | 合成 `spring.*` 属性 |
| ConfigBeanDefinitionRegistrar | 注册模块 BeanDefinition |

## §2 定位与关系（推导）

**朴素答案**：多数据源？dynamic-datasource-spring-boot-starter 这类轮子不就有吗？

**But**：常见轮子解决"切"（路由到已有源），不解决三件事——**并存**（MyBatis 与 MP 同进程互斥打架）、**多活**（跨区选库+区域事件联动）、**热换**（不是切路由而是**换连接池**且不杀在途请求）。

**Therefore**：PolyData = **"每配置一世界"的隔离派**——子上下文隔离解决并存，事件驱动重建解决热换，ha-datasource 消费 polyzone 解决多活。

**Cost**：子上下文+升迁比直连复杂——03 篇的"为什么值得"。

**生态位**（README :136-141）：**polyconfig 管配置从哪来、polyzone 管区域在哪、polydata 管数据源怎么建怎么换**——三者用事件接线（04/05 篇全兑现）。

## §3 掌握卡（2 条）

1. **四件事**：建（隔离子上下文+升迁）/ 换（热替换）/ 选（zone 路由）/ 并（ORM 互斥共存）——**03-06 四篇各深挖一件**（02 先讲启动管道）。
2. **架构三层**：6 步管道（启动）→ 门面+子上下文（运行）→ 双事件汇聚（变更）——**记这张图，后面每篇都是图上一块**。

## §4 边界（一句话翻篇）

- ShardingSphere 规则内核 → shardingsphere 系列翻篇（06 只讲"怎么建"）。
- MyBatis 执行链 → orm-pool 05 翻篇。
- support 自研工具包 → 零框架依赖的实现细节，翻篇。

## §5 Self-Test（3 题）

- Q1：四件事各对应哪一篇？
- Q2：4 个 SPI 分别在管道哪一步？
- Q3：polyconfig/polyzone/polydata 各管什么？靠什么接线？

## §6 衔接

- 全景收口；下篇 02 配置模型与 6 步管道：JSON 从 Environment 到上下文的完整旅程。
