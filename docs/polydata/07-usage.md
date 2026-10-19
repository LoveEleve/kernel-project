---
title: "07-使用：四配置姿势与关键属性"
series: "PolyData 动态多数据源系列"
previous: "06-orm-sharding.md"
next: "08-interview-wrapup.md（待写）"
source_refs:
  - "README 快速开始（:89-120 四姿势）+ 配置参考表（:122-134）+ 构建与测试（:143-156：JDK17/先装 polyzone+polyconfig/80 用例全 H2）+ 版本基线（:160）"
---

# 07-使用：四配置姿势与关键属性

## §0 它干了什么（例子先行）

四个生产常用姿势，**全是改一行属性**（README :108-119）：

```properties
# 姿势 1：单库
polydata.jdbc.configs.primary={"name":"primary","datasource":[{"name":"ds","url":"jdbc:mysql://127.0.0.1:3306/demo"}]}

# 姿势 2：多活（同区优先，05 篇机制）
polydata.jdbc.configs.orders={"name":"orders","ha-datasource":{"defaultZone":[...],"eu-west-1":[...]}}

# 姿势 3：MyBatis-Plus（base-packages 约定，与 mybatis 互斥二选一——06 篇三重防线）
polydata.jdbc.configs.user={"name":"user","datasource":[...],"mybatis-plus":{"base-packages":"com.example.user.mapper"}}

# 姿势 4：分库分表（rules 用 SS YAML——06 篇工厂直接建）
polydata.jdbc.configs.goods={"name":"goods","datasource":[...],"sharding-sphere":{"config-resource":"classpath:/sharding/goods.yaml"}}
```

- **它干了干什么**：一个 JSON 模型四种展开——**模块槽决定装配哪套栈**（02 篇管道第 6 步按槽注册）。
- **本篇回答**：关键属性哪几个必记？怎么跑起来（构建注意）？

## §1 关键属性（README :122-134——按用途分组）

| 组 | 属性（默认） | 说明 |
|---|---|---|
| **开关与配置** | `polydata.jdbc.enabled`（true）、`configs.<name>`（-） | 总开关；配置 JSON（支持 `classpath:` 外置文件——大 JSON 不塞配置中心） |
| **账号/URL 兜底** | `default-user-name/password`（-）、`url.default-scheme`（mysql://）、`url.default-query-params.*`（characterEncoding 等） | **URL 简写归一化**——配置里写 `127.0.0.1:3306/db` 也能用（02 篇管道 step3 同族 Validator：DataSourcePropertiesModuleValidator:45） |
| **互斥与准入** | `modules.<m>.auto-configuration.base-packages`（内置）、`banned-modules`（内置） | 06 篇三重防线的前两重——**一般不用改，内置即合理**（default.properties） |
| **升迁范围** | `multiple-context.bean-classes.expose`（DataSource 等）、`.primary`（DataSource 等） | 03 篇升迁——**多配置并存时动它**（谁上交父上下文、谁 @Primary） |

**记忆钩子**：必动的其实只有 `configs.<name>`；其余有内置——**"默认合理+按需覆盖"是这套配置的设计姿态**。

## §2 跑起来（README :143-156 构建注意）

```
要求：JDK 17+ / Maven 3.8+
前置：polyzone 与 polyconfig 要先 mvn install（本仓库 SNAPSHOT 依赖它俩——家族构建顺序 ①→②→③）
测试：80 个用例全 H2/纯内存（零外部数据库）——配置解析/四 SPI/隔离升迁/热替换/双事件/并存/SS 路由 t_order→t_order_0/1
版本：Boot 3.2.5 / SS 5.5.1 / MB 3.0.5 / MP 3.5.7（README:160，全会话口径一致）
```

**xhs 落点口径（诚实）**：xhs 未用 polydata（orm-pool 线用的是单数据源+Hikari 常规路）——面试讲法=**"我分析过这类框架的源码：它的多活选库、热替换、ORM 并存三块机制是这么设计的"**，不冒充生产使用。

## §3 掌握卡（2 条）

1. **四姿势一个模型**：datasource（单）/ ha-datasource（多活）/ mybatis(-plus)（ORM）/ sharding-sphere（分片）——**模块槽即开关**。
2. **属性四组**：开关配置/账号 URL 兜底/互斥准入/升迁范围——**默认合理，只有 configs 和多配置的 expose 要动**。

## §4 边界（一句话翻篇）

- SS YAML 规则怎么写 → shardingsphere 系列。
- MP wrapper 用法 → orm-pool 07。
- health-check 自动转移/监控指标 → Roadmap 未实现不讲。

## §5 Self-Test（3 题）

- Q1：四姿势各对应哪个模块槽？
- Q2：`banned-modules` 和 `base-packages` 默认要改吗？为什么？
- Q3：多配置并存时哪个属性决定谁 @Primary？

## §6 衔接

- 使用线收口；下篇 08 面试收口：七篇回链 + 高频坑 + 终检——polydata 系列完结、poly 家族三连封。
