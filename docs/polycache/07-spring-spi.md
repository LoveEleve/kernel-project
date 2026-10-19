---
title: "07-Spring集成与拦截器SPI"
series: "PolyCache 多机房复制系列"
previous: "06-dynamic-routing.md"
next: "08-设计收口.md（待写）"
source_refs:
  - "polycache-spring-boot .../PolyCacheAutoConfiguration.java（158 行，全文已读）"
  - "polycache-spring-boot .../PolyCacheRedissonConfigHolder.java（71 行，全文已读）"
  - "polycache-redisson .../capture/RedisCommandInterceptor.java（70 行，全文已读）"
---

# 07-Spring集成与拦截器SPI

## §0 它干了什么（例子先行）

应用侧只加配置，四件套自动接好：

```yaml
polycache:
  replication:
    enabled: true
    source: zone-a          # 防回环标记——必填
    topic: polycache-redis-replication
    consumer-group: zone-a-replay
```

启动后自动发生：捕获装上 starter 的 Redisson Config / Kafka 生产者消费者起来 / **专用重放客户端**建好（不装捕获）。

- **它干了什么**：把 02~06 的零件在 Boot 生命周期里拼成整机——并工程化解决"重放客户端不能装捕获"这个防回环死结。
- **本篇回答**：谁保证重放不回环？拦截器 SPI 怎么接？哪些配置是硬性断言？

## §1 掌握卡（4 条）

1. **防回环的工程核心 = 先克隆再装捕获**：`RedissonAutoConfigurationCustomizer` 的 `customize(Config)` 里**先 `new Config(config)` 存档**（给重放客户端）**再 `capture.applyTo(config)`**（给应用客户端）——克隆发生在捕获安装前，天然干净；customizer 取 `LOWEST_PRECEDENCE`——排在其它 customizer 之后，克隆能带上别人的改动。
2. **无 starter 的降级**：拿不到干净 Config → **warn + 用应用客户端重放**（并在注释里警告"必须确认它没装捕获，否则回环"）——把风险显式交给用户。
3. **开关与断言**：`polycache.replication.enabled=true` 才装配（`@ConditionalOnProperty`）；`source`/`topic`/`consumer-group` **必填断言**（断言=启动期检查，不满足直接启动失败）——source 是防回环的根，漏了直接启动失败，不是运行时踩坑。
4. **拦截器 SPI**：`RedisCommandInterceptor`（函数式接口=只有一个抽象方法、可写成 lambda）链——每条被捕获命令发布前过链：原样放行 / 改写（脱敏/改 source）/ **返回 null 跳过**（临时 key 不复制）；Spring `orderedStream()` 自动收集排序。

## §2 装配全景（五个 Bean 一张表）

| Bean | 条件 | 要点 |
|---|---|---|
| Replayer | 有 RedissonClient | 有干净 Config→**专用客户端**（owned，随 replayer 关）；无→warn 用应用客户端 |
| Producer | — | KafkaProperties + 自带序列化器；topic Assert |
| Capture | — | **source Assert**；拦截器链收集 |
| Consumer | Replayer 在 + `consumer.enabled`（默认 true） | group 断言；**`earliest` 用 putIfAbsent**（键不存在才放——用户显式配了就不覆盖）；init=start |
| ConfigHolder+Customizer | starter 的 Customizer 类在场 | 先克隆后装捕获（§1） |

顺序保障：`@AutoConfiguration(afterName=RedissonAutoConfigurationV2/V2+KafkaAutoConfiguration)`——starter 和 Kafka 先装配，PolyCache 后接（Tracing 04 讲过的 Boot 顺序纪律同款）。

## §3 拦截器 SPI 的三条纪律（Interceptor javadoc 原话提炼）

1. **在事件循环上跑**：必须线程安全、**绝不能阻塞**——多连接并发过链。
2. **每逻辑命令恰好一次**：Netty 写重试不重复拦截（02 promise claim 的 SPI 层兑现）。
3. **异常只跳捕获**：拦截器抛异常 → 该命令不复制，**Redis 命令本身不受影响**（零影响铁律的第三处出现：Handler/队列满/拦截器异常——同一哲学）。

示例（javadoc 原文）：`temp:` 前缀的 key 不复制——一行 lambda 返回 null。

## §4 与 06 的边界澄清

`new Config(config)` 克隆在 06 是"反面教材"（copy 构造器丢自定义 ConnectionManager）；在这里是**刻意用它**——我们要克隆的恰恰是"没装捕获"的干净配置，ConnectionManager 本来就该丢（重放客户端自己建连接）。**同一个机制，风险还是风险，用对场景就是工具**——判断标准：你的自定义项在不在被丢的字段里。

## §5 负面空间（本篇两笔）

1. **无 starter 时防回环靠自觉**：warn 之后继续用应用客户端——用户装了捕获就回环，框架不拦。
2. **`earliest` 只是 putIfAbsent**：用户显式配了 `latest` 会覆盖"启动窗口不丢"的保护——部署 checklist 要核对这个值。

## §6 边界（一句话翻篇）

- PolyCacheProperties 字段全表 → 用时看源码（topic/source/consumer-group/consumer.enabled/enabled）。
- Kafka SSL/SslBundles → 部署时查。
- AutoConfiguration 的注册文件（imports）→ Boot 3 标准装配，不展开。

## §7 Self-Test（4 题）

- Q1：防回环死结是什么？先克隆再装捕获怎么解开？
- Q2：为什么 customizer 取最低优先级（最后跑）？
- Q3：三个必填断言各防什么？source 为什么最要命？
- Q4：拦截器三条纪律；异常时谁不受影响？

## §8 衔接

- 集成线收口；下篇 08 设计收口：问答↔组件总账 + 负面空间总清单 + 面试叙事三段。
