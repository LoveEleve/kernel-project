---
title: "04-使用：配置与工程实践"
series: "ShardingSphere 分库分表系列"
previous: "03-kernel-five-steps.md"
next: "05-核心源码.md（待写）"
lab_refs:
  - "docs/中间件拷打/K12-ShardingSphere.md（②配置实证+集成坑+④）"
---

# 04-使用：配置与工程实践

## §0 它干了什么（例子先行）

框架用起来就是**一份 YAML + 一次 DataSource 装配**（xhs 实际形态）：

```yaml
# sharding-config.yaml 骨架（5.x YAML schema；xhs 实际形态按此裁剪）
dataSources:                        # 注意驼峰——物理连接层
  ds0: {url: jdbc:mysql://.../my_xhs_order_0, ...}   # ds0~ds3 四个库
  # ... ds1/ds2/ds3
rules:
  - !SHARDING
    tables:                       # 逻辑表逐个声明
      t_order:
        actualDataNodes: ds${0..3}.t_order_${0..3}   # 16 格
        tableStrategy: {standard: {shardingColumn: user_id, shardingAlgorithm: order-inline}}
        databaseStrategy: {standard: {shardingColumn: user_id, shardingAlgorithm: ds-inline}}
    bindingTables: [t_order, t_order_item, t_local_message, ...]  # 五表绑定组
    shardingAlgorithms:
      ds-inline: {type: INLINE, props: {algorithm-expression: "ds${(user_id.hashCode() & 0x7fffffff) % 4}"}}
props:
  sql-show: false                  # 生产必关（P-D32 血泪）
```

- **它干了什么**：声明"数据源 + 规则 + 算法 + 开关"四件事，框架接管 SQL。
- **本篇回答**：配置分哪几层？Boot 3 集成为什么要手动建？哪些开关是血泪换来的？

## §1 掌握卡（4 条）

1. **配置四层**：`datasources`（物理连接）/ `rules`（分片规则：表→策略→算法）/ `bindingTables`（绑定组）/ `props`（全局开关：sql-show 等）——读配置从这四层读。
2. **集成两式**：`jdbc:shardingsphere:` 协议+starter（Spring Boot 开箱依赖）自动装配（标准路）vs **YAML 工厂手动建**（`YamlShardingSphereDataSourceFactory`）——**xhs 走后者**：Boot 3.2.5 不识别该协议（K12②实证坑），且要手动接 MyBatis SqlSessionFactory（排除 MP=MyBatis-Plus 自动配置）。
3. **策略与算法的配置位**：每张逻辑表配 `databaseStrategy`+`tableStrategy`（standard→shardingColumn+shardingAlgorithm 名）；算法声明在 `shardingAlgorithms`（INLINE 表达式 / ClassBased 类）。
4. **三个血泪开关**：`sql-show: false`（生产开着=16 分片物理 SQL 日志爆炸——K12 事故3）；影子表 `myxhs.shadow.enabled` 默认关；worker-id 三级注入（env → `-Dworker.id` → IP+端口推导——事故2 的修复形态）。

## §2 主键与附属配置

- **分布式主键**：表级声明 `keyGenerateers`（type=Snowflake）——分片表**必须配**（01 §2：自增会撞车；06 深挖三坑）。
- **worker-id 注入**：同机多实例必须不同——配置位按 `环境变量 > 系统属性 > 推导` 三级兜底（推导算法 `floorMod(ipPart*31+port, 1024)` 是 06 案例）。
- **绑定组**：`bindingTables` 里**分片规则必须同构**（03 §1；配错=笛卡尔积，陷阱5）。

## §3 影子表：框架功能与 xhs 的自研（归属要分清）

**框架原生**：ShardingSphere 有 ShadowDB 能力——规则里配影子数据源+影子算法，命中压测标记的 SQL 自动改写到影子表。

**xhs 实际用的是自研**：`ShadowTableInterceptor`（common/trace 下的 **MyBatis 拦截器**，非框架类）——`X-Pressure-Test` 请求把 `t_*` 改写 `t_*_shadow`，`myxhs.shadow.enabled` 默认关。**为什么自研不用原生**：需求只是表名替换，一个拦截器 135 行搞定，不引框架的影子规则面（见 06 边界对照）。

开关类功能的通用纪律：**默认关、按需开、开前看回滚**。

## §4 Hint：SQL 不动时的强制路由

`standard` 策略解析 SQL 拿分片键；**拿不到时**（如按非分片键查询的运维场景）用 Hint API **强制指定**路由——配置声明 hint 策略，代码侧注入提示。属于"备用通道"，日常主路不走它（06 讲替代方案映射表/基因法）。

## §5 边界（一句话翻篇）

- 各配置项全表（连接池参数/Hikari 10/2 等）→ 用时看 `sharding-config.yaml` 实例。
- Proxy 模式配置 → xhs 未用，用时查官方文档。
- Scaling 迁移工具 → 06 扩容节一句。

## §6 Self-Test（4 题）

- Q1：配置四层各是什么？读规则从哪层开始？
- Q2：xhs 为什么放弃 starter 自动、走 YAML 工厂？
- Q3：三个血泪开关各防什么？
- Q4：主键不配 Snowflake 会怎样？worker-id 怎么三级兜底？

## §7 衔接

- 会配置了；下篇 05 核心源码：五步在 5.5.2 里落在哪些引擎、数据怎么流（关键类骨架）。
