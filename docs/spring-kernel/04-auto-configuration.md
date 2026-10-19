---
title: "04-使用：自动配置体系"
series: "Spring 内核系列"
previous: "03-aop-transaction.md"
next: "05-refresh与BeanFactory.md（待写）"
source_refs:
  - "boot-3.2.5 autoconfigure 包已核：AutoConfiguration/AutoConfigurationImportSelector/AutoConfigurationSorter/ConditionalOn{Bean,Class,MissingBean,Expression}…（类名）"
cross_refs:
  - "钩子兑现：Tracing 04（before 装配）/Seata 05（ConditionalOnProperty）/Nacos 02（afterName）/各系列 @ConditionalOnBean"
---

# 04-使用：自动配置体系

## §0 它干了什么（例子先行）

加一个 starter 依赖，什么都不配，Redis 连接池就位了：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

背后发生的事：

```
classpath 里有了 lettuce/jedis
  → 自动配置类 RedisAutoConfiguration 被选中（条件：类在场才配）
  → 它声明的 RedisTemplate/连接工厂 Bean 被注册
  → 你 @Autowired 直接用；自己声明同名 Bean 则自动退让（@ConditionalOnMissingBean）
```

- **它干了什么**：**"约定+在场证明"驱动的批量装配**——你带依赖（约定），框架验条件（在场证明），自动把 Bean 配好；你配了它退让。
- **本篇回答**：自动配置怎么被发现、怎么排序、怎么退让？starter 是什么？工程上怎么写/怎么避坑？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **自动配置类** | 带 `@AutoConfiguration`（Boot 3；=加强版 @Configuration）的类，声明"整套默认 Bean" |
| **starter** | **依赖聚合包**——不写代码，只引一堆 jar+自动配置（"开箱"的实现载体） |
| **条件注解** | `@ConditionalOn*` 家族——在场证明的验章器（OnClass/OnBean/OnMissingBean/OnProperty…） |
| **装配顺序** | `before`/`after`/`afterName`——自动配置类之间的先后契约（Tracing 04 用过 before） |
| **注册清单** | `META-INF/spring/…AutoConfiguration.imports`（Boot 3 文件；Boot 2 是 spring.factories） |

## §2 工程使用：三件事

**① 用（日常 99%）**：引 starter + 可选 yml 覆盖默认——判读配置是否生效的标准动作：**看条件是否满足 + 自己的 Bean 是否触发 MissingBean 退让**（04 系列 Sentinel 伪配置的教训：配置前先想"谁读它"）。

**② 写（团队私有 starter）**：

```java
@AutoConfiguration(after = KafkaAutoConfiguration.class)   // 排在 Boot 自带配置后
@ConditionalOnClass(KafkaTemplate.class)                    // 在场证明
@ConditionalOnProperty(prefix = "myxhs.mq", name = "enabled", havingValue = "true")
public class MyMqAutoConfiguration {
    @Bean @ConditionalOnMissingBean                        // 用户可覆盖
    public MyProducer myProducer(...) { ... }
}
// 注册：imports 文件里写全限定类名
```

**③ 排（顺序坑）**：被 `@ConditionalOnBean(Tracer.class)` 依赖的配置必须排在生产者之后——**顺序反了条件不成立、静默退化**（Tracing 04 全景讲过的"静默失效"，此处是写 starter 时的正面防法：显式 before/after）。

## §3 掌握卡（3 条）

1. **发现→排序→过滤→注册**四步：imports 文件收集 → `AutoConfigurationSorter` 按 before/after 拓扑排序（按依赖关系排出先后）→ 条件过滤（OnClass 等，源码类已核）→ 剩下的按普通 @Configuration 处理。
2. **条件注解读法**：OnClass（类在场）/OnBean+OnMissingBean（Bean 有无——成对用="你没配我才配"）/OnProperty（开关）——**全部是装配期一次性判定**，运行期不重判（动态生效靠别的机制，如 Nacos 刷新的是数据不是装配）。
3. **各系列钩子兑现表**：Tracing 04 `before=`（顺序契约）/Seata 05 `@ConditionalOnProperty(enabled)`（开关）/Nacos 02 `afterName=`（跨 jar 排序）/本 session 十余处 OnBean+OnMissingBean——**同一套语言，八个战场**。

## §4 核心源码锚点（只取三个最核心）

| 类 | 职责 |
|---|---|
| `AutoConfigurationImportSelector` | 收集 imports、应用过滤 |
| `AutoConfigurationSorter` | before/after 拓扑排序 |
| `ConditionalOn*`（condition 包） | 条件判定实现族 |

（行级流程归 05 的 refresh 语境——自动配置本质是 import 进来的一批配置类。）

## §5 边界（一句话翻篇）

- AutoConfiguration.imports 格式细节/过滤缓存 → 用时查官方文档。
- AOT/原生镜像（应用提前编译成原生程序）的条件预计算 → 知道存在即可。
- spring.factories 遗留 → Boot 3 只认 imports（迁移知识）。

## §6 Self-Test（3 题）

- Q1：starter 里到底有什么？（依赖聚合+自动配置，不写业务代码）
- Q2：OnBean 和 OnMissingBean 为什么成对出现？条件判定发生在什么期？
- Q3：写了自定义 starter，用户配了同名 Bean 应该发生什么？怎么保证？

## §7 衔接

- Boot 层使用收口；下篇 05 核心源码：refresh 十三步与 BeanFactory 管线——图纸总装的总调度。
