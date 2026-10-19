---
title: "06-动态连接路由：运行期切换目标"
series: "PolyCache 多机房复制系列"
previous: "05-semantic-replay.md"
next: "07-Spring集成与SPI.md（待写）"
source_refs:
  - "polycache-redisson .../routing/DynamicRedissonClient.java（448 行，全文已读）"
---

# 06-动态连接路由：运行期切换目标

## §0 它干了什么（例子先行）

一个门面客户端，背后多个 Redis 目标，运行期热切换：

```java
DynamicRedissonClient dynamic = DynamicRedissonClient.create(primaryConfig);
dynamic.addTarget("zone-us", usConfig);

RedissonClient client = dynamic.getClient();   // 拿到的是动态代理
client.getBucket("user:1").set("alice");       // 走默认目标

dynamic.runWithTarget("zone-us", () -> {
    client.getBucket("user:1").set("alice");   // 本块走 zone-us，块结束自动还原
});

dynamic.replaceTarget("zone-us", newConfig);   // 换地址不重启应用
```

- **它干了什么**：把"调哪个 Redis"从**编译期配置**变成**运行期可路由**——多机房切换、配置热更、灰度路由的底座。
- **本篇回答**：为什么不用 Redisson 自带的自定义连接管理？选择存哪？数据对象跟谁走？

## §1 掌握卡（4 条）

1. **路由点在客户端门面**：门面=对外一个接口、内部转发（02 说的包装门面的正面版）；JDK 动态代理（运行时生成接口实现、按方法名转发的代理）包住 `RedissonClient`，每次调用按**当前线程的选择**转发到对应目标——版本无关、不碰 Redisson 内部。
2. **为什么不走 ConnectionManager**：试过——Redisson 3.27/3.35 的 `Config` **拷贝构造器不拷贝**自定义连接管理字段（javadoc 原话"verified by test"），`Redisson.create` 时**静默丢弃**。这是"依赖内部 API 会被反咬"的实锤案例。
3. **选择是线程级 + 作用域优先**：`runWithTarget/callWithTarget` 块结束**恢复之前的选择**（可嵌套，池化线程永不泄漏）；`switchTarget/clearTarget` 手动配对必须 finally——**和 Tracing 03 讲的线程池上下文泄漏是同一个坑**。
4. **数据对象绑目标**：`getBucket()` 拿到的 RBucket **绑定调用当时的那条目标**——选择还原后，旧对象仍用旧目标（javadoc :60-63）。

## §2 为什么门面路由（推导）

**朴素答案**：Redisson 支持 `Config#useCustomServers` 换连接管理器——底层路由不就完了？

**But**：实测——`Config` 的拷贝构造器（Redisson 3.27/3.35）**没拷这个字段**，`Redisson.create(拷贝)` 时自定义连接管理**静默消失**。依赖版本内部字段=埋雷。

**Therefore**：路由抬到**客户端接口门面**——JDK 动态代理拦截 `RedissonClient` 的每个方法 → `method.invoke(getCurrentClient(), args)` 转发。Redisson 版本怎么变都不关它事。

**Cost**：方法级反射转发（每次调用多一次反射开销——热路径可感知，但缓存方法句柄可优化，本版未做）；`equals/hashCode/toString` 特判防代理语义错乱。

## §3 作用域：为什么块 API 优于开关 API

```
switchTarget("us");       // 开关式——忘记 clear，线程池下个请求就串机房
try { ... } finally { clearTarget(); }

runWithTarget("us", () -> {...});   // 作用域式——自动存旧值/还原，可嵌套
```

`callWithTarget` 的实现就是 Tracing 03 那个模式的复刻：存旧 → 设置 → try/finally 还原（`:281-294`）。**池化线程的上下文泄漏**是分布式组件的通用病——wrap/作用域 API 是通用药。

**兜底**：选择的目标被别的线程移除了？`getCurrentClient`（`:306-319`）：warn → **清掉陈旧选择** → 回退默认目标——不空指针、不带着死目标跑。

## §4 生命周期：owned 与 shared

| | owned（传 Config 建的） | shared（传现成 Client） |
|---|---|---|
| 谁关 | 路由器关（addTarget 旧的也关） | 谁建谁关 |
| 典型用法 | 多机房目标都自建 | 接管外部已有的客户端 |

`replaceTarget` = put 新的 + **关旧的**——javadoc 原话"in flight on the previous client may fail"（在途操作可能失败）：热替换不是零损切换，**换配置选低峰**。

## §5 负面空间（本篇两笔）

1. **方法级反射转发无方法缓存**——高频小操作的热点上多一次反射；后续可把 `Method` 缓存到 Map 优化（现在每次 `invoke` 走 switch+invoke）。
2. **replace 有在途失败窗口**；**数据对象不跟随切换**（绑旧目标）——切完目标后，之前拿到的 RBucket 仍写旧目标，业务要重新 `getClient().getXxx()` 拿新的。

## §6 边界（一句话翻篇）

- Target 内部类/优雅停机参数（quiet 2s timeout 15s）→ 源码 :336-352 用时查。
- 与 07 的装配关系（谁 new 它、重放客户端怎么绑）→ 07。
- 本篇与复制主线的关系：动态路由是**独立能力**（多机房读侧/灰度也用得上），不参与复制数据流（01 数据流图里没有它）——README 特性表把它列在"能力"而非"链路"。

## §7 Self-Test（4 题）

- Q1：为什么门面代理赢了 ConnectionManager？（copy 构造器丢字段的实测）
- Q2：作用域 API 和开关 API 各防什么病？实现模式和 Tracing 03 哪个同款？
- Q3：拿到的 RBucket 在选择还原后还跟谁走？为什么？
- Q4：replaceTarget 的代价？owned 和 shared 的关法差在哪？

## §8 衔接

- 下篇 07 Spring 集成与拦截器 SPI：自动装配怎么保证"重放客户端不装捕获"（防回环的工程落地）。
