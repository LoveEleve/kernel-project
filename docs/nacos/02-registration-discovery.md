---
title: "02-注册与发现：实例生命周期"
series: "Nacos 协调服务系列"
previous: "01-dual-center-overview.md"
next: "03-grpc连接模型.md（待写）"
lab_refs:
  - "nacos-lab/docs/A-01-服务注册与发现/01-实例生命周期.md"
  - "nacos-lab/docs/A-01-服务注册与发现/02-客户端缓存与故障转移.md"
  - "nacos-lab/docs/A-01-服务注册与发现/03-优雅下线.md"
---

# 02-注册与发现：实例生命周期

## §0 它干了什么（例子先行）

订单服务启动→注册→被调用→重启：

```
启动：registerInstance("order-service", 实例) → gRPC :9848 → 进 Nacos 内存
调用：调用方订阅 → 收到地址列表 → 之后都读本地缓存（零网络）
Nacos 挂了：调用方继续用内存缓存顶着——不是读磁盘！
重启：@PreDestroy 先 deregister → 推送给订阅者 → 等流量排干 → 再 shutdown
```

- **它干了什么**：实例的生（注册）、活（被发现）、死（摘除）全生命周期管理。
- **输入/输出**：输入=注册/下线请求；输出=订阅方拿到的实例列表（本地缓存优先）。
- **本篇回答**：临时和持久差在哪？缓存几层？为什么要优雅下线？

## §1 掌握卡（4 条）

1. **临时 vs 持久**：临时（微服务）= gRPC 连接保活、断连即摘、纯内存、AP/Distro；持久（数据库/MQ）= 不靠心跳、JRaft 落盘、CP、要手动下线。
2. **注册链一句话**：SDK → 选代理（临时走 gRPC）→ **redo 先写后发**（断线重连补注册）→ 服务端三层 filter（鉴权/限流/校验）→ 存进 Client 内存 → Distro 同步。
3. **三层缓存**：内存（正常读，零网络）→ 磁盘（**客户端重启**恢复）→ failover 文件（**运维手动**应急）——**Nacos 挂了用的是内存缓存**，不是磁盘。
4. **优雅下线**：deregister → gRPC 推送（0~2s）→ 调用方刷缓存（2~5s）→ 排干（5~10s）→ shutdown；`kill -9` 会级联故障。

## §2 临时 vs 持久（设计起源）

"我相信你会自己报死活"（临时）vs "你不报我也不能删你"（持久）：

| | 临时 ephemeral=true | 持久 ephemeral=false |
|---|---|---|
| 保活 | gRPC keep-alive（**传输层自动**——不是每 5s 应用层心跳） | 无心跳（2.x） |
| 断连 | 即刻清理（毫秒级） | 只标不健康，数据保留 |
| 一致性 | AP（Distro 异步，04） | CP（Raft 日志） |
| 存储 | 纯内存 | JRaft log+snapshot |
| 重启后 | 丢，客户端重新注册 | 从 snapshot 恢复 |
| 典型 | 微服务/K8s Pod | 数据库/MQ |

**常见误解**：2.x 临时实例不需要应用层心跳——keep-alive 在传输层；1.x 的 5s/15s/30s 在 2.x 只剩定时补偿兜底。

## §3 注册链（骨架）

```
registerInstance → 选代理（临时→GrpcClientProxy）
  → redoService 先缓存（write-ahead=先记录再执行；断线重连后按记录补注册——redo 机制见 lab D-03）
  → InstanceRequest → gRPC :9848
  → 服务端 filter 链：@Secured 鉴权 → @TpsControl 限流 → @ExtractorManager 校验
  → 存入 Client 内存 → Distro 同步到其他节点（04）
```

## §4 三层缓存（"Nacos 挂了怎么办"的标准答案）

```
getAllInstances → ① failover 开着？→ 读 failover 文件（最高优先）
                → ② 内存 serviceInfoMap 命中？→ 直接返回（正常路径）
                → ③ 未命中 → 向服务端订阅拉取（挂了则空列表）
```

- **内存缓存**：服务端推送时更新（`processServiceInfo`），同时写磁盘——读取零网络。
- **磁盘缓存**：`{home}/nacos/naming/{ns}/`，**客户端重启**时（`namingLoadCacheAtStart=true`）加载——Server 挂了**不用它**。
- **failover 文件**：运维手动放 `failover-mode`（内容 1）——**手动应急，不是自动触发**；每 5s 检查开关。

## §5 优雅下线

```
T+0s    deregister → Nacos 标记 offline
T+0-2s  gRPC 推送给订阅者
T+2-5s  调用方刷本地缓存移除该实例
T+5-10s 在途请求排干
T+10s   安全 shutdown
```

直接 kill：调用方路由到死实例 → 超时 → 重试放大 → 级联。正确姿势：`@PreDestroy` 里 deregister + sleep 5 秒等传播；验证用 `kill <PID>`（SIGTERM=正常终止信号，进程有机会清理；`-9`=强杀不给机会），别用 `-9`。

## §6 坑

| 坑 | 正解 |
|---|---|
| kill -9 重启 | 级联故障——@PreDestroy+deregister |
| 以为 Server 挂了读磁盘/failover | 读内存缓存；failover 是手动开关 |
| 以为 2.x 还要 5s 心跳 | keep-alive 传输层搞定 |
| 持久实例忘手动下线 | 持久实例不会自动摘——要手动 deregister |

## §7 边界（一句话翻篇）

- redo 机制细节 → 03（gRPC 连接模型）。
- Distro 同步/Raft 落盘 → 04。
- SDK 全链路源码级 → `lab D-01/D-03` 用时查。

## §8 Self-Test（4 题）

- Q1：临时/持久六维差异里，最先答哪三维？（保活/一致性/存储）
- Q2：三层缓存各服务什么场景？Server 挂了用哪层？
- Q3：failover 文件谁来触发？（运维手动，不是自动）
- Q4：优雅下线五步时间线；为什么 sleep 5 秒？

## §9 衔接

- 本篇回答"实例怎么管"；下篇 03 回答"2.x 底座"：gRPC 连接模型（GrpcServer/ConnectionManager/请求链）。
