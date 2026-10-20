---
title: "04-原理三：热替换"
series: "PolyData 动态多数据源系列"
previous: "03-child-context.md"
next: "05-zone-ha.md"
source_refs:
  - "已核：RefreshingDynamicDataSourceListener:321（属性名匹配:329/findParentContext:297-319）→ initializeDataSource:190-226（clone:246→initDynamicDynamicJdbcConfig:251-263：setDynamic(false):254/剥离 ORM·事务:256-258/改名:260→new 子上下文:194→merge:198→refresh:200→getDataSource:202（0 个或>1 个都抛:228-235）→ synchronized(mutex)：prev:213/delegate=latest:214/换上下文:216-218/异步关 prev:222）/ closeScheduler+delay:266-275（delay 读 Environment:94）/ volatile delegate:82、getDelegate:163、getConnection:120 / 失败清理:203-210"
---

# 04-原理三：热替换

## §0 它干了什么（例子先行）

区域切换事件到达 → 连接池**无重启换血**（initializeDataSource:190-226 全链）：

```
① 事件：DynamicJdbcConfigChangedEvent → RefreshingDynamicDataSourceListener:321
   → 属性名匹配（:329 只处理"自己那份配置"的变更）→ findParentContext:297 定位
② 建新：clone 源配置:246 → initDynamicDynamicJdbcConfig:251：
     setDynamic(false):254【防递归，02 篇】
     剥离 transaction/mybatis/mybatis-plus:256-258【**只重建池层**】
     改名:260 → new DynamicJdbcChildContext:194 → merge:198 → refresh:200
   → getDataSource:202 取出新池（0 个或 >1 个直接抛:228-235——装错当场炸）
③ 原子换：synchronized(mutex)：prev=delegate:213 → **delegate=新池:214** → 换 childContext:216-218
④ 延迟关：closeScheduler.schedule(关 prev 上下文, 延迟):222→:266-275
   ——在途请求跑完旧池，到点才 close
```

- **它干了什么**：**四步换血**：克隆→重建→原子换→延迟杀——对外零中断。
- **本篇回答**：为什么换的只是"池层"？读写怎么不打架？延迟关多久、为什么？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **门面不动，池在换** | `DynamicDataSource` 门面 Bean 恒在；业务 `getConnection:120 → getDelegate().getConnection`——delegate 是 **volatile:82**，读无锁、写在锁内 |
| **只重建池层** | init 时剥掉 ORM/事务模块:256-258——**ORM 栈不动**：SqlSessionFactory 持有的是门面引用，门面 delegate 一换全跟着换（**换心不换骨架**） |
| **mutex 原子换** | 写侧 `synchronized:211` 一把锁内完成 delegate+childContext 双换+登记关闭:213-222；读侧 volatile 无锁——**读写只在换的瞬间有锁** |
| **延迟关闭** | 旧上下文不立杀——`closeScheduler` 按延迟排期:266-275（延迟从 Environment 读:94；属性 `polydata.jdbc.modules.datasource.dynamic-context.close-delay`，默认 60s）——**给在途连接留收尾时间**（README:31"等在途请求结束"） |
| **失败即炸** | getDataSource 要求新上下文**恰好一个** DataSource（:228-235 0/多都抛）——热替换是"带病运行中做手术"，**新件不对不上车** |

## §2 关键推导（两问）

**① 为什么读侧可以不加锁？**
delegate 是 volatile——写侧在锁内先换引用再登记关闭，**读侧拿到的要么旧引用要么新引用，不存在半新半旧**；旧的也不会被立刻关（延迟关兜着在途）。这是 **volatile（可见性）+ synchronized（写串行）+ 延迟关闭（旧件保活）** 三件套的分工——**双检锁同款思路，但这里连读锁都不用**。

**② 为什么剥离 ORM/事务是聪明的（不是偷懒）？**
若重建也造 ORM 栈：新 SqlSessionFactory 要重新建连、缓存预热，**换血代价从秒级掉到分钟级**，且旧栈新栈并存瞬间会有两个事务管理器——**事务边界混乱风险**。只换池：ORM 栈全程稳定、事务管理器不换，**变更面最小**。与 03 篇"引用上交"呼应：**门面就是那个稳定的引用锚点**。

**③ 延迟关的边界（诚实）**：
延迟值可配（:94）但**默认值给不了硬保证**——若某请求超过延迟仍在用旧连接，close 会打断它（ SQLException）。**工程处方**：延迟 ≥ 最长事务/查询超时。这是"零中断"的真实边界——README 说"不杀在途"，**前提是延迟覆盖在途时长**。

## §3 掌握卡（2 条）

1. **四步链**：事件匹配属性名 → 克隆+剥模块+重建（恰好一个池才放行）→ mutex 原子换（delegate+上下文）→ 延迟关旧——**换心不换骨架**。
2. **三件套分工**：volatile 管读可见、synchronized 管写互斥、延迟关管旧件保活——**读侧零锁**。

## §4 边界（一句话翻篇）

- findParentContext 的单/多配置两分支 :297-319 → 按需读。
- URL 归一化/连接校验 → 07 使用篇。
- 关闭时 DisposableBean 的同步收尾 :112-115 → 知道有即可。

## §5 Self-Test（3 题）

- Q1：写出热替换四步（带行号锚）。
- Q2：为什么重建配置要剥掉 mybatis/transaction？
- Q3：读侧为什么不加锁？延迟关的真实边界是什么？

## §6 衔接

- 热替换收口；下篇 05 多活选库与容错：ha-datasource 怎么按 zone 选库、双事件怎么汇到重建入口。
