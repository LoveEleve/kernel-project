---
title: "03-grpc连接模型：2.x的服务端底座"
series: "Nacos 协调服务系列"
previous: "02-registration-discovery.md"
next: "04-一致性与推送.md（待写）"
lab_refs:
  - "nacos-lab/docs/D-源码解读/D-05-GrpcConnectionModel.md"
  - "nacos-lab/docs/D-源码解读/D-06-ClientServiceModel.md（§二）"
---

# 03-grpc连接模型：2.x的服务端底座

## §0 它干了什么（例子先行）

Nacos 服务端启动后同时开三个门：

```
:8848  HTTP（控制台/兼容 1.x）
:9848  gRPC SDK  ← 客户端注册/发现/推送都走这（8848+1000）
:9849  gRPC 集群 ← 节点间 Distro 同步（8848+1001）
:7848  Raft     ← CP 的选主/日志（8848-1000）
```

一个客户端请求进来：Netty（高性能网络框架，Nacos 底层收发包的引擎）收 → 反序列化（把字节流还原成对象）→ 按 type 找 Handler → 鉴权/限流/校验 → 业务。**连接断开 = 实例即刻消失**（毫秒级，不是 1.x 的等 30s）。

- **它干了什么**：2.x 的服务端底座——长连接收请求、管连接生死、推变更。
- **输入/输出**：输入=gRPC 请求/连接事件；输出=业务处理结果 + 服务端主动推送。
- **本篇回答**：三个门各干什么？请求怎么路由到 Handler？连接断了发生什么？

## §1 掌握卡（4 条）

1. **三层端口**：SDK :9848 / 集群 :9849 / Raft :7848——运维只配 8848，偏移自动推算；隔离原因是敏感度不同（Raft 对延迟最敏感，100ms+ 可能切主）。
2. **两种 gRPC 方法**：`request`（单向：注册/查询）+ `requestBiStream`（双向流：**连接建立 + 服务端推送**）——注册走前者，推送走后者。
3. **请求路由**：Netty 收 → Payload 反序列化 → 按 type 查 `RequestHandlerRegistry` → filter 链（鉴权→限流→校验）→ 业务 Handler。
4. **连接即生命周期**：keep-alive 超时 → 断开 → `unregister` → 该 Client 下所有临时实例**毫秒级清理**（1.x 要等 30s 超时——2.x 的质变）。

## §2 三层端口

| 端口 | Server | 干什么 | 敏感度 |
|---|---|---|---|
| :9848 | GrpcSdkServer（+1000） | 客户端注册/发现/订阅/推送 | 频率高、量小 |
| :9849 | GrpcClusterServer（+1001） | 节点间 Distro/JRaft | 量大、要尽快完成 |
| :7848 | RaftServer（-1000） | Leader 选举/日志复制 | **对延迟最敏感**（100ms+ 可能切主=主节点切换） |

独立端口 = 独立限流/监控/熔断，互不踩脚。启动顺序：HTTP(8848) 先起，gRPC 后起（`NacosApplicationListener` 监听 Spring started 事件）。

## §3 服务端内部：Registry + 两个 Acceptor

（Acceptor=接收器，gRPC 里负责收请求的组件。）

```java
// BaseGrpcServer.startServer：动态注册，不用 Spring @Component
MutableHandlerRegistry handlerRegistry;  // gRPC 生命周期≠Spring 生命周期，运行时可增删
// ① GrpcRequestAcceptor — 单向请求（register/query）
// ② GrpcBiStreamRequestAcceptor — 双向流（连接建立 + 服务端推送）
NettyServerBuilder.forPort(...).executor(自定义线程池).addTransportFilter(连接事件).intercept(拦截器链)
```

请求路由（`GrpcRequestAcceptor.request`）：反序列化 Payload → 提取 type（如 `REGISTER_INSTANCE`）→ `RequestHandlerRegistry.getByRequestType` → filter 链 → `handler.handle`（业务在 04/D-07）。

## §4 ConnectionManager：连接守门人

- **register 五步**：取 clientIp → 去重（同 connectionId 幂等）→ **限流**（每 IP/每 AppName 最大连接数，超限拒绝）→ 注册 → 纳入健康检查。
- **unregister**：keep-alive 超时或主动断开 → 注销 → 发 `ClientReleaseEvent` → **该 Client 下所有临时实例自动清理**（02 §2"断连即摘"的服务端落点）。
- **质变对照**：1.x 挂了等 30s `IP_DELETE_TIMEOUT`；2.x 毫秒级"断开即删"。

## §5 数据模型翻页（D-06 一句带）

1.x 以 **Service 为中心** → 2.x 以 **Client 为中心**（连接=客户端身份，实例挂在 Client 下）——这就是"连接即生命周期"能成立的模型基础。Service 本身不可变、`equals` 不含 ephemeral（04 展开存储时回看）。

## §6 边界（一句话翻篇）

- ConnectionManager 五步的每步源码行号、checkLimit 公式 → `lab D-05 §四` 用时查。
- Handler 全家（InstanceRequestHandler 等）→ 04/D-07。
- 客户端侧 SDK 全链路/redo → `lab D-01/D-03`。

## §7 Self-Test（4 题）

- Q1：三层端口各是什么？为什么 Raft 要单独隔离？
- Q2：`request` 和 `requestBiStream` 各承载什么？
- Q3：一个注册请求在服务端经过哪几步才到业务？
- Q4：2.x 断连清理和 1.x 的 30s 差在哪？靠什么模型支撑？（Client 为中心）

## §8 衔接

- 本篇回答"连接怎么管"；下篇 04 回答"数据怎么同步与推送"（Distro AP + CP/AP 路由 + 健康自愈）。
