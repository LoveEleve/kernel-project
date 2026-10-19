---
title: "04-使用：Curator recipes"
series: "ZooKeeper 协调服务系列"
previous: "03-session-watch.md"
next: "05-选型与CP代价.md（待写）"
source_refs:
  - "curator 源码已核：curator-{client,framework,recipes} 三层；recipes/{locks,leader,cache,queue,nodes…}；InterProcessMutex:38/LeaderLatch/LeaderSelector"
---

# 04-使用：Curator recipes

## §0 它干了什么（例子先行）

原生 ZK 写选主要：建临时顺序节点→算自己是不是最小→watch 前一个→收到通知重来一遍→处理会话重连…… Curator 一行：

```java
try (LeaderLatch latch = new LeaderLatch(client, "/election")) {
    latch.start();
    latch.await();                    // 阻塞直到成为 leader
    if (latch.hasLeadership()) { ... } // 干活
}                                      // 关闭即让位（临时节点自动清理）

// 锁同理
InterProcessMutex lock = new InterProcessMutex(client, "/locks/order");
if (lock.acquire(10, TimeUnit.SECONDS)) { try { ... } finally { lock.release(); } }
```

- **它干了干什么**：**把 03 的坑全部包成 recipe**——重订阅/会话重连/节点清理都是框架的事。
- **本篇回答**：Curator 三层各是什么？锁和选主的 recipe 靠哪些积木拼成？什么时候直接用原生？

## §1 概念铺垫（What）

| 词 | 一句话 |
|---|---|
| **Curator 三层** | client（原生 API 薄封装）→ framework（连接管理/重试/监听）→ **recipes**（分布式原语成品） |
| **InterProcessMutex** | 分布式可重入互斥锁（:38）——临时顺序节点+watch 的 recipe 封装 |
| **LeaderLatch / LeaderSelector** | 选主两式——latch 互斥当选；selector 可轮庄 |
| **Cache 族**（PathChildrenCache 等） | **把一次性 watch 包成持续缓存**——内部自动续订 |
| **ConnectionStateListener** | 会话断连/重连的监听——recipe 用它处理重连补节点 |

## §2 工程使用

```java
// 锁：可重入 + 超时 + 可撤销（InterProcessMutex 实现 Revocable）
InterProcessMutex lock = new InterProcessMutex(client, "/locks/pay");
if (lock.acquire(5, TimeUnit.SECONDS)) {
    try { pay(); } finally { lock.release(); }   // finally 释放是纪律
}

// 缓存：替代原生 watch 的持续订阅
PathChildrenCache cache = new PathChildrenCache(client, "/config", true);
cache.getListenable().addListener((c, event) -> apply(event));  // 自动续订
cache.start();
```

| 原生痛点（03） | Curator 的解 |
|---|---|
| watch 一次性忘续订 | Cache 族内部续订 |
| 会话重连后临时节点没了 | ConnectionStateListener + recipe 自动补建（如 PersistentNode） |
| 锁/选主要手写一堆 | InterProcessMutex/LeaderLatch 直接用 |

**选型一句话**：**业务代码永远优先 recipes**；只有写 recipe/排底层协议时才碰原生 API（client/framework 层）。

## §3 掌握卡（2 条）

1. **recipes 的配方=01~03 的积木**：临时顺序节点（01）+ 会话与 watch 语义（03）+ ZAB 一致性（02）——**没有新魔法，只有把协议写成类**。
2. **可重入是 InterProcessMutex 的加分项**：同线程重复 acquire 不死锁（内部计数）——手写版最容易漏的就是这格。

## §4 边界（一句话翻篇）

- 撤销锁（revoke）/Barrier/Queue 等其余 recipe → recipes 目录用时查。
- curator-framework 的重试/连接策略细节 → framework 包。
- Spring 集成（CuratorFramework @Bean 装配）→ Boot 生态标准样例，翻篇。

## §5 Self-Test（3 题）

- Q1：Curator 三层各干什么？recipes 的配方来自哪三块积木？
- Q2：Cache 族替原生 watch 解决了什么？（续订）
- Q3：InterProcessMutex 比手写锁多照顾了哪个细节？（可重入）

## §6 衔接

- 使用线收口；下篇 05 选型与 CP 代价：ZK vs Nacos vs etcd 的正面战场（场景决定 CAP 站位）。
