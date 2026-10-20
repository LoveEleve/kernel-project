---
title: "04-使用：注解属性与三坑"
series: "PolyConfig 配置统一抽象系列"
previous: "03-refresh-events.md"
next: "05-interview-wrapup.md"
source_refs:
  - "README 注解属性表（:108-121）与注意事项（:180-193）+ polydata 联动已核：PropagatingDynamicJdbcConfigChangedEventListener（:47 SmartApplicationListener/双事件:66/命中匹配:79-85/zone 分支:90-101，isZoneChanged:103/zone 比较:107）"
---

# 04-使用：注解属性与三坑

## §0 它干了什么（例子先行）

生产最常用的配置姿势（README 快速开始实锤）：

```java
@NacosPropertySource(
        value = {"application.properties", "order-service.yaml"},  // 多 dataId → 02 篇的 Composite
        group = "DEFAULT_GROUP",
        serverAddress = "127.0.0.1:8848",     // 支持多地址
        namespace = "public",
        autoRefreshed = true)                 // 默认 true——03 篇刷新链的前提
@Configuration public class NacosConfig { }
```

**家族联动的真实一幕**（polydata 源码——01 篇"polydata 消费其事件"的兑现）：

```java
// polydata：PropagatingDynamicJdbcConfigChangedEventListener:47
// 双事件监听:66 —— polyconfig 的配置变 + polyzone 的区域变，都可能触发数据源重建
if (event instanceof ConfigPropertiesChangedEvent) {
    for (String key : event.getChangedPropertyNames())
        if (dynamicJdbcConfigPropertyNames.contains(key)) {   // 命中匹配:82
            publishDynamicJdbcConfigChangedEvent(key);          // → 重建数据源（polydata ③）
        }
}
// 区域变了（zone 判定 :107）→ 有 HA 数据源的配置同样重建 :90-101
```

- **它干了什么**：注解属性管"怎么连/怎么排/刷不刷"；三坑是**接事件前必读**；联动展示**事件模型是家族的总线**。
- **本篇回答**：属性哪些关键？三坑各是什么？polydata 怎么用这个事件？

## §1 关键属性（README :108-121 摘要）

| 属性 | 默认 | 说明 |
|---|---|---|
| `value` | - | dataId 列表（.properties/.yml/.yaml/.xml） |
| `autoRefreshed` | **true** | 服务端变更自动刷新+发事件（false=只拉一次） |
| `first` / `before` / `after` | - | **Environment 跨源排位=覆盖优先级**（02 篇 addFirst:220/addBefore:234/addAfter:226）；同一注解内多 dataId 先声明先赢 |
| `ignoreResourceNotFound` | false | 配置不存在是否忽略 |
| `serverAddress` / `namespace` / `group` | 见 README | 连接三件套（多地址支持） |
| `username` / `password` | 空 | Nacos 鉴权；二者与地址/命名空间一起决定 `ConfigService` 缓存 key（密码存哈希） |
| `timeout` | 3000 | 拉取超时 ms |

## §2 三坑一限 + 两个使用边界（README 注意事项 :180-193——接事件必读；线程安全一项已在 03 篇讲）

| # | 坑/限 | 机制 | 怎么办 |
|---|---|---|---|
| 1 | **重复事件** | 声明式（路 A）与 Spring Cloud 标准刷新（路 B）**同一变更各发一个**事件（03 §2②） | 按 `source` 过滤或幂等 |
| 2 | **environment 来源 old 恒 null** | `EnvironmentChangeEvent` 只带属性名——适配器拿不到旧值（03 :76）；属性被删除或无法解析时 **new 也为 null**，适配器无法区分"删除"和"未知" | 以 `getNewProperties()` 为准；需要可靠旧值就用声明式路径 |
| 3 | **编码作用域** | `encoding` 只对 `.properties` 生效；YAML 固定 UTF-8；XML 由 XML 声明决定。Nacos 的 `.xml` dataId 现在也按 XML 解析（`ResourcePropertySources.load:64` 用 dataId 判定） | 非 UTF-8 用 properties；YAML 不要指望 `encoding` |
| 4 | 通配符不支持 | `classpath*:/config/*.properties` 批量模式未实现 | 按需显式列全 |

**两个使用边界**：

- `@NacosPropertySource` **不可重复**（没有 `@Repeatable`）：一个配置类只能标一次；多个配置源用 `value` 多 dataId，或拆成多个配置类。
- 自动配置的 `ConfigChangePublisher` Bean 带 `@ConditionalOnMissingBean`，但**只被 `EnvironmentConfigChangeListener` 使用**；声明式 Loader 在配置类解析期用 `createConfigChangePublisher():242` 自己创建发布器，自定义这个 Bean 不会改变 `@NacosPropertySource` 路径。要统一扩展发布行为，得改 Loader 或包装 `ApplicationEventPublisher`。

## §3 掌握卡（2 条）

1. **属性三组**：连接（serverAddress/namespace/group/username/password）/刷新（autoRefreshed）/排位（first/before/after）——**排位只决定 Environment 跨源顺序；同一注解内多 dataId 先声明先赢**。
2. **三坑本质**：①双路感知的代价 ②适配器信息降级（丢 old，new 也可能 null）③编码/格式边界（encoding 只对 properties；YAML UTF-8；XML 声明）——**全是"统一抽象"与"后端现实"的缝**。

## §4 边界（一句话翻篇）

- polydata 怎么重建数据源 → **polydata 系列 ③**（本篇只到"命中→转发"）。
- zone 变更怎么产生 → polyzone 系列 ②（本篇只到双事件入口）。
- Nacos 鉴权/多地址容错 → nacos 系列翻篇。

## §5 Self-Test（3 题）

- Q1：autoRefreshed=false 会怎样？
- Q2：三坑各是什么？source="environment" 的事件能拿旧值吗？
- Q3：polydata 监听两类事件——第二类是什么、什么条件下触发重建？

## §6 衔接

- 使用线收口；下篇 05 面试收口：五篇回链 + 高频坑 + 终检；06 进阶篇做线程模型、扩展后端与实验。
