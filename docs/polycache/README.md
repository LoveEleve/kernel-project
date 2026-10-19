# PolyCache 多机房复制系列

> 对象：**自家框架 PolyCache**（多机房 Redis 复制，40 个 Java；捕获/重放核心 ~1400 行）
> 位置：`poly/polycache`（core / redisson / replicator / spring-boot 四模块）
> 素材：项目 README（特性表=现成大纲）+ 源码逐类
> 方法：**design-derivation**（问题驱动推导，非逐行审读）——朴素答案→被什么打破→被迫的设计→代价
> 面试定位：讲清"我写过的框架"——比复述任何开源项目都硬（K06 危险信号的反面）

## 一、总表（8 篇）

| # | 文章 | 类型 | 核心问题 | 对应组件 |
|---|---|---|---|---|
| 01 | [问题与全景：多机房各写各的怎么同步](01-problem-overview.md) | 理论 | 为什么不用 Redis 原生复制/双写？整体数据流 | README+全景 |
| 02 | [无侵入拦截：命令怎么被"看见"](02-nonintrusive-capture.md) | 原理 | 为什么不在业务层埋点？NettyHook 装在哪 | NettyHook+Handler |
| 03 | [写过滤与 EVAL 学习：哪些命令该复制](03-write-filter-eval.md) | 原理 | 读命令为什么不能复制？EVALSHA 怎么办 | Registry+Scripts |
| 04 | [Kafka 传输：保序、背压与至少一次](04-kafka-transport.md) | 原理 | 分区键为什么是 key？队列满了怎么办 | Producer+Codec |
| 05 | [语义重放：怎么"原样"重放到对端](05-semantic-replay.md) | 原理 | 为什么不能带业务序列化器？失败了怎么办 | Replayer+Consumer |
| 06 | [动态连接路由](06-dynamic-routing.md) | 使用 | 运行期切换 Redis 目标解决什么 | DynamicRedissonClient |
| 07 | [Spring 集成与拦截器 SPI](07-spring-spi.md) | 使用 | 重放客户端为什么不能装捕获？（防回环） | AutoConfiguration+SPI |
| 08 | [设计收口：问答总账与面试叙事](08-design-wrapup.md) | 收口 | 不保证什么；面试叙事三段 | 全局 |

## 二、学习路径

```text
全景：01
捕获线：02 → 03
传输线：04 → 05
集成线：06 → 07
收口：08
```

## 三、方法说明（本系列特殊）

外部框架系列是"它为什么这么设计"；**本系列是"我们为什么这么设计"**——每篇按推导五段走：

```
朴素答案（第一直觉怎么做）→ But（具体场景里死在哪）→ Therefore（被迫的设计）→ Cost（付出了什么）
```

组件即答案：每个类/字段都是某个问题的答案（08 有问答↔组件总账）；读不完源码也能独立复验设计。

## 四、前置要求

- Redis 基础（命令/复制）+ Redisson 基础（已封卷的 Redis 系列 10 篇后半）。
- RocketMQ 04（消费位点/重平衡——Kafka 同款概念）。
- Netty 网络模型基础（已封卷；02 的管线只用一层概念）。

## 五、掌握分级

- **必须掌握**：为什么无侵入（NettyHook）；为什么失败不复制；保序三件套（key 分区/单线程/按序重放）；防回环两条路；至少一次语义的边界。
- **理解思想**：wire-ready 捕获（为什么编码后再看）；EVALSHA→EVAL 的脚本学习；宽松解码。
- **知道即可**：动态路由的作用域 API；SPI 排序细节。

## 六、边界

- Kafka 内部实现 → RocketMQ 系列打过概念底，Kafka 专题后置。
- Redis 主从/Cluster 协议细节 → Redis 系列 05/06。
- 生产部署容量 → 08 负面空间一句带过。
