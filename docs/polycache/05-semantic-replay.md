---
title: "05-语义重放：怎么原样重放到对端"
series: "PolyCache 多机房复制系列"
previous: "04-kafka-transport.md"
next: "06-动态连接路由.md"
source_refs:
  - "polycache-replicator .../consumer/KafkaRedisCommandConsumer.java（126 行，全文已读）"
  - "polycache-redisson .../replay/RedisCommandReplayer.java（158 行，全文已读）"
---

# 05-语义重放：怎么原样重放到对端

## §0 它干了什么（例子先行）

机房 B 的消费者拿到一条消息 `{command: "SET", key: "user:1", args: [[...字节...], [...]]}`：

```
source == 本机房？→ 跳过（自己的写已在本地生效）
阻塞命令（BLPOP 等 8 个）？→ 跳过（重放会挂死）
其余：new RedisCommand(name, 宽松解码器)
     → commandExecutor.writeAsync(key, ByteArrayCodec, command, args).get()
     → 本地 Redis 执行完成 → 才让 Kafka 偏移前进
```

- **它干了什么**：把消息里的**字节参数原样**提交给本地 Redisson 的命令执行器——不经业务序列化器，不依赖源端客户端类型。
- **本篇回答**：为什么能"原样"？顺序和偏移怎么保证？失败了这条消息去哪？

## §1 掌握卡（4 条）

1. **重放三不依赖**：`ByteArrayCodec`（参数已是字节，原样提交）+ `new RedisCommand(name, 宽松解码器)`（响应没人看，单段/多段都吞）+ 直捅 `CommandAsyncExecutor.writeAsync`（绕过任何高层 API）——**与源端业务序列化器零耦合**（04 消息里存字节的回报在这兑现）。
2. **顺序=同步等 + 偏移后置**：`replayer.replay(message).get()` 逐条等到完成——分区按序落地，**偏移（消费进度，读到哪了）只在重放完成后提交**——至少一次的实现点。
3. **失败路径**：重放异常抛给容器 → `DefaultErrorHandler(FixedBackOff(1000L, 3))`——**1s 间隔重试 3 次，仍败跳过**（偏移前进，这条永久缺失）。
4. **防回环两条**：消息 `source == localSource` 跳过（消费端）；**重放客户端禁止装捕获**（Replayer javadoc 原话，否则重放写又被捕获——07 的自动装配专门保证这条）。

## §2 为什么不反序列化再执行（推导）

**朴素答案**：拿消息→用源端的 codec 反序列化参数→调 Redisson 高层 API（`bucket.set(v)`）执行。

**But**：消息里没带"这是哪种高层对象"——一条 `SET` 可能来自 RBucket/RMap/任意自研封装，反推 API 是猜谜；源端 codec 版本/配置和对端不一致时反序列化直接失败；复制系统依赖业务序列化器=把零侵入承诺撕了。

**Therefore**：捕获侧存的本来就是**编码后的字节**（02 §3）——重放直接 `writeAsync(key, ByteArrayCodec.INSTANCE, command, args)`：字节进字节出，目标 Redis 收到的就是当初源端发的那份；key 同时充当 **Cluster 路由依据**。

**Cost**：响应结果丢弃（宽松解码器 `单段取段、多段整吞`）——重放方不校验执行结果，错与对靠 Redis 侧的错误日志暴露。

## §3 三个跳过点（都是"不能安全重放"）

| 跳过 | 为什么 |
|---|---|
| source == 本机房 | 自己的写已在本地生效——重放=重复写 |
| 8 个阻塞命令（BLPOP/BRPOP/BLMOVE/BLMPOP/BRPOPLPUSH/BZPOPMIN/BZPOPMAX/BZMPOP） | 重放会**真阻塞连接**且**消费掉集合里的元素**——挂死重放线程还改变对端状态 |
| 无路由 key | writeAsync 需要 key 做 Cluster 路由——没 key 没法定节点 |

**阻塞命令跳过的语义账**：BLPOP 也被复制（它在写目录里），但重放侧跳过——对端"消费弹出"动作由**对端自己的应用**完成，复制只管数据写入侧。这是"复制=写命令镜像"的边界：**消费行为不跨机房代执行**。

## §4 至少一次的完整闭环（04+05 拼起来）

```
捕获：成功才发布（04）→ Kafka：至少一次语义（可能重复、不会漏）
消费：重放完成才提交偏移（本篇）→ 崩溃窗口=已重放未提交 → 重启后同条消息再重放一次
```

重放一次 SET/INCR？——**SET 幂等无害**；**INCR 会多加一次**！所以框架承诺的是"至少一次 + 尽力而为"，**业务侧的关键计数要么接受偏差、要么业务幂等键兜**（08 负面空间的头号账）。

## §5 边界（一句话翻篇）

- Kafka 编解码（RedisCommandMessageSerializer/Codec）→ 部署时查。
- `shutdownClientOnClose` 所有权开关 → 07 集成篇。
- earliest 偏移配置是**消费者工厂的责任**（javadoc :40-41 提醒，容器不强制）——部署 checklist 项。

## §6 Self-Test（4 题）

- Q1："原样重放"靠哪三件？（字节参数/宽松解码/直捅执行器）
- Q2：为什么偏移必须在重放完成后才提交？提前提交会丢什么？
- Q3：阻塞命令为什么跳过？"消费动作不跨机房代执行"是什么边界？
- Q4：INCR 被重放两次会怎样？框架承诺到哪一层？

## §7 衔接

- 传输线（04→05）收口；下篇 06 动态连接路由：运行期把调用切到不同 Redis 目标解决什么。
