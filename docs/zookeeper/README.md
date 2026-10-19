# ZooKeeper 协调服务系列

> 语料：**zookeeper 源码**（`code/spring/zookeeper`）+ **curator 五模块**（client/framework/recipes…已核）
> 理论前置：**dist-theory/06-zookeeper-zab**（选主/锁例子 + ZAB 已铺——本系列讲机制与工程）
> 定位（诚实）：**协调服务原理线**——xhs 生产用 Nacos、未直接用 ZK（仅 SW 配置文件偶见）；本系列答"CP 协调器怎么工作 + Curator 怎么用 + 何时该选它"
> 计划：L5-12 补完

## 一、总表（6 篇：概念 → 原理 → 使用 → 面试）

| # | 文章 | 层 | 核心问题 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [什么是 ZooKeeper：四件套模型](01-what-is-zk.md) | **概念** | znode 树/会话/watch/节点四类型 What | dist-theory 06+源码 |
| 02 | [原理一：ZAB 协议](02-zab.md) | **原理** | 原子广播与 Leader 选举——强一致的来源 | 理论线+源码 |
| 03 | [原理二：会话与 Watch](03-session-watch.md) | **原理** | 临时节点为什么可靠；watch 一次性语义 | 源码 |
| 04 | [使用：Curator recipes](04-curator.md) | **使用** | 分布式锁/选主/队列的标准姿势 | curator-recipes |
| 05 | [深水：选型与 CP 代价](05-selection-cp.md) | **原理深水** | ZK vs Nacos vs etcd；CP 的性能账 | 三系列交叉 |
| 06 | [面试收口](06-interview-wrapup.md) | **面试** | 五篇回链；高频坑；终检 | 综合 |

## 二、学习路径

```text
概念：01
原理：02 → 03
使用：04
深水：05
收口：06
```

## 三、前置要求

- dist-theory 02（Raft——CP 双雄对照）/06（ZK-ZAB 例子先行篇）。
- Nacos 系列 04（Distro AP——05 选型对照的另一极）。

## 四、掌握分级

- **必须掌握**：四件套模型；节点四类型（持久/临时/顺序/容器）；watch 一次性；ZAB 两阶段直觉；Curator 智能锁。
- **理解思想**：CP 协调器的"强一致从哪来、代价是什么"；临时节点=会话生命的外化。
- **知道即可**：ZK 3.5+ 端口分离/quota/admin server；Observer 节点； curator-test。

## 五、边界

- Dubbo 用 ZK 做注册中心的细节 → Dubbo 系列（已封卷）。
- etcd/Raft 实现细节 → dist-theory 02。
- xhs 生产对照 → 本系列无（xhs 走 Nacos——已在 Nacos 系列）。
