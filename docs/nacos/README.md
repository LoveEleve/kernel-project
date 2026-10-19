# Nacos 协调服务系列

> 基线：**Nacos 2.3.0**（对齐 xhs：SCA 2023.0.1.2 + nacos-client 2.3.0；远端 21.130.247.89:18848）
> 素材：`nacos-lab/docs/`（A-01~A-10 生产使用 16 篇 + D-01~D-14 源码解读 + B-01~B-05 扩展点，1682 引文审计过）
> xhs 实锤：25 处 yml（`spring.config.import` 拉共享配置 + 注册配置）
> 计划出处：学习计划 L5-12 协调服务 🟡

## 一、总表（8 篇）

| # | 文章 | 类型 | 核心内容 | 素材 / 证据 |
|---|---|---|---|---|
| 01 | [双中心全景：注册+配置一体](01-dual-center-overview.md) | 理论 | 解决什么问题；三维模型；2.x gRPC 化 | A-01 前置/A-02/D-05 |
| 02 | [注册与发现：实例生命周期](02-registration-discovery.md) | 使用 | 临时/持久实例；客户端缓存与故障转移；优雅下线 | A-01 三篇+D-01/03 |
| 03 | [gRPC 连接模型（2.x 核心）](03-grpc-connection.md) | 原理 | GrpcServer/ConnectionManager/RequestHandler；vs 1.x | D-05/06 |
| 04 | [一致性与推送：Distro+CP/AP](04-consistency-push.md) | 原理 | Distro AP 同步；CP/AP 路由；推送+健康自愈 | D-09/10/08+A-04 |
| 05 | [配置中心与动态刷新](05-config-refresh.md) | 使用 | 三维模型；共享配置；刷新链路；Bootstrap 废弃 | A-02/03/10+D-02/04 |
| 06 | [安全、灰度与集群](06-security-gray-cluster.md) | 生产 | 命名空间隔离；两层鉴权；beta 回滚；三层端口 | A-05/06/07 |
| 07 | [xhs 生产对照：配置与路由实景](07-xhs-production.md) | 生产 | 25 处 yml 口径；import 共享配置；zone 元数据路由 | xhs+A-08/09 |
| 08 | [面试收口：18 题路线与陷阱](08-interview-wrapup.md) | 收口 | D-14 高频题；陷阱；终检 | D-14/Z |

## 二、学习路径

```text
全景：01
注册线：02 → 03 → 04
配置线：05
生产：06 → 07
收口：08
```

## 三、前置要求

- Boot 3 自动配置与条件注解（Tracing 04）。
- RocketMQ 系列 01（组件全景里 NameServer 的对照——Nacos 注册中心同位对照）。
- dist-theory 02（Raft——CP 侧对照，Distro AP 侧本系列讲）。

## 四、掌握分级

- **必须掌握**：三维模型（namespace/group/dataId）；临时实例+心跳；客户端缓存兜底；配置推送链路；Bootstrap→import 迁移。
- **理解思想**：gRPC 长连接模型；Distro AP 同步；CP/AP 双引擎路由；保护阈值。
- **知道即可**：集群部署细节；Prometheus SD；扩展点 B-01~B-05；AuthPlugin SPI。

## 五、边界

- Raft 实现细节 → dist-theory 02（CP 侧不重讲）。
- Spring Cloud Alibaba 全家桶（Sentinel/Seata 集成）→ 各自系列。
- Nacos 服务端 Java 源码逐行 → D 类已做导读，本系列只讲骨架。
