---
title: "02-无侵入拦截：命令怎么被看见"
series: "PolyCache 多机房复制系列"
previous: "01-problem-overview.md"
next: "03-写过滤与EVAL学习.md"
source_refs:
  - "polycache-redisson .../capture/RedisCommandCaptureNettyHook.java（48 行，全文已读）"
  - "polycache-redisson .../capture/CommandCaptureHandler.java（75 行，全文已读）"
  - "polycache-redisson .../capture/RedisCommandCapture.java:149-245（applyTo / CommandData / CommandsData + write promise）"
---

# 02-无侵入拦截：命令怎么被看见

## §0 它干了什么（例子先行）

一行装配，所有写命令被"看一眼"：

```java
capture.applyTo(config);   // 内部就一句：config.setNettyHook(new RedisCommandCaptureNettyHook(this))
RedissonClient client = Redisson.create(client);
client.getBucket("user:1").set("alice");
// 之后每条连接的出站管线尾部：write() 里 instanceof CommandData → capture 看命令名+参数 → 放行
```

- **它干了什么**：在 Redisson 的 Netty 连接管线上挂一个出站 Handler——命令**编码成字节之前**先过它一眼，是写就通知监听器，然后原样放行。
- **本篇回答**：为什么拦截点选在管线？为什么看编码前的对象？怎么保证不伤业务命令？

## §1 掌握卡（3 条）

1. **拦截点 = 每条连接的出站管线尾部**：Netty 管线=连接上一串处理器，命令像流水线一样过每一站；**出站**=从应用发往 Redis 的方向。`NettyHook.afterChannelInitialization` → `pipeline().addLast(Handler)`——出站写从管线尾往头走，所以它是**第一个**看到 `write` 的，且看到的是编码前的 `CommandData` 对象。
2. **编码前看对象，不看字节**：`command.getName()` + `getParams()` 直接可得；参数此时已是**协议级值**（业务对象→协议值的转换 Redisson 早已做完）——"与业务序列化器无关"的真正来源。
3. **零影响铁律**：捕获包在 try-catch(Throwable) 里，异常只 warn 绝不影响命令本身（源码原话注释）；重试去重靠 **promise claim**（同一逻辑命令只捕获一次）。

## §2 为什么是 Netty 管线（推导）

**朴素答案**：包装 `RedissonClient` 代理每个 API？——`getBucket().set()` 包得住，但 RMap/RAtomicLong/RScript 几十个入口全要包，漏一个就断复制；Redisson 升级加 API 还得跟。

**But**：Redisson 所有 API 最终都汇成一件事——往连接写命令。**汇合点在网络出口**。

**Therefore**：拦截装在 `Config.setNettyHook`（Redisson 官方留的连接钩子）→ 每条连接初始化完 → 管线尾部挂 `CommandCaptureHandler`（出站 Handler）→ `write()` 时：

```java
// CommandCaptureHandler.java:50-66
public void write(ctx, message, promise) {
    captureSafely(message);        // instanceof CommandData / CommandsData → capture
    super.write(ctx, message, promise);   // 原样放行——命令照走
}
```

Cluster 的每个节点连接、MOVED 重定向前的命令（MOVED=Cluster 返回"key 不在这台节点，请去那边"的重定向响应）——都在管线上，天然覆盖。

**Cost**：拦截深度依赖 Redisson 的内部对象（`CommandData` 在 client 协议包里，非最稳定 API 面）——升级 Redisson 时这一层要回归测试。这是"零侵入"付的维护账。

## §3 编码前 vs 编码后（口径澄清）

Handler javadoc 原话：加在管线尾部，所以在 Redisson `CommandEncoder` **编码之前**看到 `CommandData`。

那 README 说的"wire-ready、与业务序列化器无关"指什么？——参数在 `CommandData` 里已经是**协议级值**（Redisson 把业务对象转成 Redis 协议参数的活，在到达管线前就做完了），捕获拿 `command.getName()`+`getParams()` 就够，**不需要解字节、也不碰业务序列化器**。README 的"wire-ready"说的是这个语义，不是"字节编码后"——两句话各说一半，合起来才对。

## §4 两个保命细节

**① 捕获异常必须吞**（Handler :55-65）：

```java
catch (Throwable e) {
    // The capture must never affect the Redis command itself
    logger.warn("...", e);
}
```

复制系统坏了，业务 Redis 必须照常——**旁路系统的头号纪律**。这条纪律贯穿全系列（队列满丢弃、重放失败跳过，都是同一哲学）。

**② promise claim 去重**（Capture :169-173）：连接失败重试时同一逻辑命令会在管线里写多次——`claim(promise)` 保证**每个逻辑命令只捕获一次**，Kafka 不发重复。

## §5 边界（一句话翻篇）

- `CommandsData`（pipeline 批量）与 skipResult 的特殊路径 → `lab :187-245` 用时查；skipResult 在 Netty write 成功后才发布。
- 拦截器 SPI（SPI=可插拔扩展点；在 capture 之后的过滤/改写链）→ 07。
- SCRIPT LOAD 不复制只登记 → 03。

## §6 Self-Test（4 题）

- Q1：为什么包 API 会死在"几十个入口"？汇合点为什么在网络出口？
- Q2：`addLast` 的 Handler 为什么是第一个看到出站写的？（出站尾→头）
- Q3："wire-ready"的准确含义？编码前看对象拿到的是什么？
- Q4：promise claim 防的是什么场景？不防会怎样？

## §7 衔接

- 本篇回答"怎么看见"；下篇 03 回答"看见了哪些该复制"（写命令目录 + EVAL 学习——Redisson 的 RMap 走 EVAL 是个坑）。
