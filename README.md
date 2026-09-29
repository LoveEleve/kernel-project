# PolyCache

> Multi-datacenter Redis replication framework built on Redisson: non-intrusive command interception, Kafka-based cross-datacenter replication and semantic replay.
> 基于 Redisson 的 Redis 多机房（异地多活）复制框架：无侵入命令拦截、Kafka 跨机房逻辑复制、语义重放。

PolyCache 解决一个具体问题：**同一份业务代码在多个机房各写各的本地 Redis，如何把写命令自动、按序地复制到其它机房，并保证应用代码零改造。**

一个例子：

```java
Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");

// 安装命令拦截：只捕获写命令，执行成功后交给 Kafka 生产者
RedisCommandCapture capture = RedisCommandCapture.builder()
        .source("zone-a")
        .listener(new KafkaRedisCommandProducer(producerFactory, "polycache-redis-replication"))
        .build();
capture.applyTo(config);

RedissonClient client = Redisson.create(config);
client.getBucket("user:1").set("alice");      // SET 被捕获并复制到其它机房
client.getMap("map:1").put("name", "bob");    // Redisson 的 RMap.put 走 EVAL，同样被复制
client.getBucket("user:1").get();             // 读命令不复制
```

这条 `SET` 在机房 A 落地后，会被机房 B 的消费者重放到 B 的本地 Redis；机房 B 自己产生的写命令也按同样路径复制回机房 A。

## 特性

| 能力 | 说明 |
|------|------|
| 无侵入拦截 | 通过 `Config#setNettyHook` 在 Redisson 的 Netty 连接管线尾部观察 `CommandData`，不需要代理任何 Redisson API、不需要改动业务代码 |
| 命令级捕获 | 命令名 + 参数在 codec 编码完成后被捕获（wire-ready），与源端的序列化器实例无关 |
| 写命令过滤 | 基于 Redis 官方命令目录与 `ACL CAT write` 写命令目录；另外把 `EVAL` / `EVALSHA` 视为写命令，覆盖 Redisson 内部 Lua 脚本实现的操作 |
| 语义重放 | 消费端用 `ByteArrayCodec` 把参数原样提交给目标 Redisson 的 `CommandAsyncExecutor`，响应由宽松解码器消费，不依赖业务序列化器 |
| 失败不复制 | 命令只有执行成功后才发布到 Kafka，失败（如 WRONGTYPE）不会污染其它机房；Netty 写重试不会导致重复发布 |
| 捕获零影响 | 捕获发生在连接管线上，任何捕获异常都会被隔离并告警，绝不影响 Redis 命令本身的执行 |
| 保序与背压 | 生产者用单线程队列按 key 分区发送，队列满时丢弃并计数，绝不阻塞 Netty 事件循环；消费端逐条等待重放完成，同一分区按序落地 |
| 至少一次复制 | 消费端在重放完成后才提交偏移；默认从 `earliest` 开始消费（启动/重平衡窗口不丢命令）；重放失败重试 3 次（间隔 1s），仍失败则告警并跳过 |
| 防回环 | 消息携带 `source`，消费端跳过本地机房的写入；Spring Boot 自动装配为重放创建专用 Redisson 客户端（克隆配置、不安装捕获），重放写不会被再次捕获，手动装配时重放客户端同样不得安装捕获 |
| Spring Boot 集成 | 自动装配生产者/消费者/重放器，Redisson Spring Boot Starter 在场时自动把拦截应用到自动配置的 `Config` |

## 模块

| 模块 | 职责 |
|------|------|
| **polycache-core** | Redis 命令目录、写命令目录与复制消息模型（无客户端依赖） |
| **polycache-redisson** | Redisson 命令拦截（NettyHook + 连接管线 Handler）与语义重放（CommandAsyncExecutor） |
| **polycache-replicator** | Kafka 消息编解码、异步生产者、消费者与跨机房过滤 |
| **polycache-spring-boot** | 自动装配与配置属性 |

数据流：

```
应用代码
  │  Redisson API（RBucket / RMap / RBatch ...）
  ▼
Redisson Netty 管线 ── CommandCaptureHandler（尾部，编码前）
  │    ├─ 写命令？ ── 否 → 直接写
  │    └─ 是 → 参数编码为 wire bytes → 成功后投递
  ▼
RedisCommandMessage → KafkaRedisCommandProducer（单线程队列）
  ▼
Kafka Topic（按 key 分区）
  ▼
机房 B：KafkaRedisCommandConsumer（跳过 source=zone-b，逐条等待重放完成）
  ▼
RedisCommandReplayer → 目标 Redisson CommandAsyncExecutor → 机房 B 的 Redis
```

## 快速开始

### Spring Boot

```properties
polycache.replication.enabled=true
polycache.replication.source=zone-a
spring.kafka.bootstrap-servers=kafka:9092
```

依赖 `polycache-spring-boot` 与 `redisson-spring-boot-starter` 时，拦截会被自动应用到自动配置的 Redisson `Config`；未使用 Redisson Starter 时，自行创建 `Config` 并调用 `RedisCommandCapture#applyTo(config)`。

### 手动装配

```java
// 目标机房（重放端）：普通 Redisson 客户端，不安装拦截
RedissonClient targetClient = Redisson.create(targetConfig);
RedisCommandReplayer replayer = new RedisCommandReplayer(targetClient);

// 消费端
KafkaRedisCommandConsumer consumer = new KafkaRedisCommandConsumer(
        consumerFactory, "polycache-redis-replication", "zone-b", replayer);
consumer.start();

// 源机房：安装拦截
RedisCommandCapture capture = RedisCommandCapture.builder()
        .source("zone-a")
        .listener(new KafkaRedisCommandProducer(producerFactory, "polycache-redis-replication"))
        .build();
capture.applyTo(sourceConfig);
```

## 配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `polycache.replication.enabled` | `false` | 是否启用跨机房复制 |
| `polycache.replication.source` | - | 本地机房标识，启用时必填；消费端用它跳过自己的写入 |
| `polycache.replication.topic` | `polycache-redis-replication` | 复制使用的 Kafka Topic |
| `polycache.replication.consumer-group` | `polycache-replication` | 消费者组 |
| `polycache.replication.consumer.enabled` | `true` | 是否启用消费端 |

Kafka 连接参数复用 Spring Boot 的 `spring.kafka.*`。

## 设计取舍与限制

- **最终一致性**：复制是异步的，源机房写入成功与其它机房可见之间存在延迟；崩溃重叠窗口内可能出现重复应用（至少一次语义），幂等命令无影响，`INCR` 等非幂等命令需业务侧评估。
- **`EVAL` 脚本假设确定性**：以脚本原文复制，脚本内使用时间/随机数的写法不保证多机房结果一致；Redisson 内部脚本（如 `RMap.put`）是确定性的。
- **`EVALSHA` 需要远端已缓存脚本**：未缓存的 SHA 重放会得到 `NOSCRIPT`，生产建议让重放端使用 `EVAL` 路径的客户端配置。
- **无 key 命令会被跳过**（如 `FLUSHDB`）：消息以 key 作为分区与路由依据，没有 key 无法定位到目标节点，消费端会告警并跳过。
- **阻塞命令会被跳过**（`BLPOP` / `BRPOP` / `BZPOPMIN` / `BLMPOP` 等）：重放阻塞命令会再次弹出元素并长期占用连接，语义无法安全还原，消费端会告警并跳过。
- **源端并发顺序**：捕获的是命令提交到 Redisson 连接的顺序；同一 key 的并发写入在不同连接之间没有全局顺序（与 Redis 本身的多连接行为一致）。
- **codec 一致性**：值按源端 codec 编码后的字节复制，两侧使用同构 codec 才能正确读出；跨语言/跨 codec 场景需要约定统一 codec。
- **Topic 需预先创建**（或在 broker 开启 `auto.create.topics.enable`）：否则首次发送需要等待元数据超时才报错。

## 测试

40 个测试，全部使用本地进程级真实依赖，无外部服务（Redis 为本地 `redis-server` 进程，Kafka 为 Embedded Broker）：

- `polycache-core`（10）：命令目录、写命令判定（含 Locale 无关性）与消息模型单测
- `polycache-redisson`（14）：参数编码 / 路由 key 提取 / 重试去重 / 异常隔离 / skipResult 批次单测 + 双 Redis 端到端（捕获 → 直接重放）
- `polycache-replicator`（9）：消息编解码单测（含超长 key 与损坏负载）+ Embedded Kafka 端到端（源 Redis → Kafka → 目标 Redis，覆盖 SET / RMap(EVAL) / INCR / 顺序敏感命令 / SADD / HSET / putAll / TTL / 100KB 大 value / skipResult 批次）
- `polycache-spring-boot`（7）：自动装配条件、参数校验、Redisson Config 定制 + 双机房全链路端到端（两个应用上下文 + 共享 Kafka + 各自 Redis，验证互相同步且不产生复制回环）

```bash
./mvnw test
```

## 与 microsphere-redis 的关系

拦截与 Kafka 复制的问题域来自 [microsphere-redis](https://github.com/microsphere-projects/microsphere-redis)，但实现层完全不同：原版基于 Spring Data Redis 的 `RedisConnection` 动态代理做方法级拦截；本版重建为 Redisson 原生命令级方案（Netty 管线捕获 + `CommandAsyncExecutor` 重放），不依赖 spring-data-redis 与任何 microsphere 组件。命令目录资源派生自 microsphere-redis（Apache-2.0），详见 `NOTICE`。

## License

[Apache License, Version 2.0](LICENSE)
