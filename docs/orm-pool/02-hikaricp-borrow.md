---
title: "02-原理一：HikariCP借还主路"
series: "连接池与 ORM 系列"
previous: "01-pool-orm-overview.md"
next: "03-HikariCP参数与治理.md（待写）"
source_refs:
  - "hikaricp 源码已核：HikariPool.getConnection:140/connectionBag:75/92；ConcurrentBag:61（borrow:124-129/ThreadLocal steal 注释:45）；PoolEntry:37"
---

# 02-原理一：HikariCP借还主路

## §0 它干了什么（例子先行）

`dataSource.getConnection()` 的真实旅程（Hikari 快速路径）：

```
getConnection(:140)
  → ConcurrentBag.borrow(timeout)
      ① 先查"本线程的 ThreadLocal 借出列表"——上次借过又还的大概率还热着（steal 语义）
      ② 再查共享列表（无锁并发读）——别人还回来的连接
      ③ 都没有 → 创建新连接（最慢路径，受 maxPoolSize 限制）
  → 拿到 PoolEntry → 包装成 ProxyConnection 返回
还（close()）→ 不是断连接，是 requite 回 Bag（STATE 翻回 NOT_IN_USE）
```

- **它干了干什么**：**两级查找（线程本地→共享）+ 状态标记**——热路径几乎全是无锁读，连接本体几乎不动。
- **本篇回答**：ConcurrentBag 为什么快？PoolEntry 和 Connection 什么关系？为什么"线程本地列表"能偷？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **ConcurrentBag** | Hikari 的并发容器（:61）——借/还/偷三动作，**不是连接池本体**只是容器 |
| **PoolEntry** | 池里的"连接户口"（:37）——持连接引用+STATE（IN_USE/NOT_IN_USE） |
| **borrow/requite** | 借（:124-129，阻塞到 timeout）/ 还（状态翻回+归位） |
| **ThreadLocal 列表 steal** | 借出记录按线程分列表——他线程可"偷"空闲列表（:45 注释）——摊薄共享列表竞争 |
| **ProxyConnection** | 返回给业务的是代理——close() 拦截成 requite（01 §0 的语义反转在此实现） |

## §2 原理：为什么这条主路快（推导）

**朴素答案**：池=一个 `Queue<Connection>` + synchronized 借还。

**But**：高并发下 synchronized 队列=全局锁竞争——池自己成为瓶颈（比握手还贵的锁等待）。

**Therefore**：Hikari 三招：
1. **读无锁**：borrow 先读线程本地列表、再读共享列表（ConcurrentBag 的设计文档原注释：ThreadLocal lists can be "stolen"）——绝大多数请求命中前两级，**零锁**；
2. **状态标记代替摘链**：PoolEntry 的 STATE 翻转即借还——不搬动队列节点；
3. **连接永不断**：热路径只换引用，TCP/会话一直活着——"省握手"兑现的地方。

**Cost**：ThreadLocal 列表要处理"线程死了谁清"（Hikari 靠 requite 时机+清理任务——03 治理篇）。

## §3 工程使用（借还纪律）

```java
try (Connection c = ds.getConnection()) {   // 唯一正确姿势——ProxyConnection.close=归还
    ...
}   // 异常路径也归还——try-with-resources 是池时代的必选
```

| 坑 | 正解 |
|---|---|
| 手动 getConnection 不 close | 泄漏——08 排查主路 |
| 在事务里归还后复用连接对象 | 代理仍指向池条目——**事务边界内用，归还后别碰** |
| 借出后跨线程传 | ThreadLocal 语义混乱——连接绑定借出线程的借还栈 |

## §4 掌握卡（2 条）

1. **主路三级**：线程本地列表→共享列表→新建——**热路径零锁**是 Hikari"快"的核心答案（面试版）。
2. **借还=状态翻转**：PoolEntry.STATE + requite 归位；业务看到的 Connection 是代理——**close 归还**是整个池语义的支点。

## §5 边界（一句话翻篇）

- ConcurrentBag 完整实现（threadList steal 细节）→ util/ConcurrentBag.java 用时查。
- 新建连接的初始化钩子（connectionInitSql/健康检查）→ 03。
- Druid 的池实现路径不同（非 ConcurrentBag）→ 04。

## §6 Self-Test（3 题）

- Q1：borrow 三级顺序？为什么大多数请求不到第三级？
- Q2：PoolEntry 的 STATE 翻转替代了什么传统操作？（队列摘链）
- Q3：ProxyConnection.close() 拦截后做了什么？

## §7 衔接

- 借还主路收口；下篇 03 参数与治理：keepAlive/evict/泄漏检测与参数语义全表。
