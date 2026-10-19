---
title: "04-一致性与推送：Distro+CP/AP"
series: "Nacos 协调服务系列"
previous: "03-grpc-connection.md"
next: "05-配置中心与动态刷新.md（待写）"
lab_refs:
  - "nacos-lab/docs/D-源码解读/D-09-DistroV2.md"
  - "nacos-lab/docs/D-源码解读/D-10-CP-AP-Routing.md"
  - "nacos-lab/docs/A-04-健康检查/01-*.md"
  - "nacos-lab/docs/D-源码解读/D-08-PushHealthRecovery.md（推送节选）"
---

# 04-一致性与推送：Distro+CP/AP

## §0 它干了什么（例子先行）

三件事发生在同一秒：

```
订单实例注册 → 落本节点内存 → Distro 推给集群其他节点
调用方订阅的列表变了 → 推送（先合并延迟任务，防抖）→ 调用方刷缓存
机房网络抖了一下，一半实例失联 → 健康数/总数 ≤ 保护阈值 → 自保护，不返回空列表
```

- **它干了什么**：集群数据同步（Distro）、按实例类型分流（AP/CP）、变更推送与健康兜底。
- **输入/输出**：输入=注册/变更事件；输出=集群一致的视图 + 订阅方及时收到推送。
- **本篇回答**：Distro 怎么同步？什么时候走 CP？健康检查两层是什么？自保护保护谁？

## §1 掌握卡（4 条）

1. **Distro 五操作**：sync（推）/ onReceive（收合并）/ onVerify（**反熵**=定时对账：每 5s 交换 revision（数据指纹，比对即知差没差），不一致全量补）/ onQuery（新节点拉）/ onSnapshot（快照）——Nacos 自研 AP 协议。
2. **一行三元分流**：`ephemeral=true→Distro(AP)` / `false→JRaft(CP)`；**订阅永远走 AP**（Subscriber 只有临时类型）。常见误解：gRPC 数据面是两个独立 Handler 直连，不经过这个 Proxy（Proxy 是控制台/OpenAPI 管理面用的）。
3. **健康两层**：连接驱动（keep-alive 断开即清，毫秒级）为主 + 定时补偿（15s 标不健康、30s 删，兜 1.x 客户端和检测漏网）——02 §2 的服务端全景。
4. **自保护**：`健康数/总数 ≤ protectThreshold`（默认 0.0=零健康才触发）→ **把不健康实例强制标健康一起返回**——宁可给可能坏的地址，也不让调用方无址可调（防雪崩），不是真的"治好了"。

## §2 Distro：AP 集群同步

```
sync（事件驱动）：本地 Client 变更 → ClientChangedEvent → 推所有其他节点
onReceive：收到 → upgradeClient 按差异（diff）合并
onVerify（反熵，每 5s）：交换 revision → 不一致 → 全量 sync 补齐
onQuery / onSnapshot：新节点加入时按需拉/批量快照加载
```

V2 的核心（D-09 §3.4）：同步单位从实例升级为**整个 Client**（03 §5 模型翻页的红利——一次同步连接下所有实例）。

## §3 CP/AP 分流

| ephemeral | 路由到 | 协议 | 写入 |
|---|---|---|---|
| true（默认） | EphemeralClientOperationServiceImpl | Distro（AP） | Client 内存 → 事件 → 推集群 |
| false | PersistentClientOperationServiceImpl | JRaft（CP） | Raft 日志 → **多数派确认** → onApply 进内存 |

**两路分流点在哪**：gRPC 数据面按请求类型分流（`InstanceRequest` vs `PersistentInstanceRequest` 两个 Handler 各自直连——不是 Proxy）；控制台/OpenAPI 才走 `ClientOperationServiceProxy` 三元运算符。**订阅永远 AP**：持久实例的 Client 不支持订阅（直接抛 `UnsupportedOperationException`）。

## §4 健康检查两层 + 时间线

```
主路：keep-alive 断开 → unregister → ClientReleaseEvent → 清实例 → 推送（毫秒）
兜底：ClientBeatCheckTaskV2：>15s 标不健康+推送；>30s 删实例+推送
```

对照 1.x：HTTP 每 5s 心跳、最长 30s 才删；2.x 断开即删 + 补偿兜底——03 §4 质变对照的服务端另一半。

## §5 推送防抖 + 自保护

- **推送防抖**：`PushDelayTask` 延迟合并（debounce）——短时间一串变更合成一次推，防止推送风暴。
- **自保护**：阈值触发后"强制 setHealthy(true) 全返回"——效果是 `selectInstances(healthy=true)` 不返回空列表。**防的是调用链雪崩**（没地址可调全线瘫），代价是可能路由到不健康实例。

## §6 边界（一句话翻篇）

- Distro 三重过滤/合并 diff 算法 → `lab D-09` 用时查。
- JRaft 共识细节 → dist-theory 02（CP 侧不重讲）。
- 推送重试/UDP 降级（1.x 兼容）→ `lab D-08` 用时查。

## §7 Self-Test（4 题）

- Q1：Distro 五操作各一句；反熵解决什么？
- Q2：gRPC 数据面和管理面的 CP/AP 分流点各在哪？（Handler 直连 vs Proxy 三元）
- Q3：健康检查两层各兜什么？15s/30s 分别做什么？
- Q4：自保护触发后"真实机制"是什么？（强制标健康全返回，不是治好）

## §8 衔接

- 注册线（02→03→04）收口；下篇 05 配置线：配置中心与动态刷新（含 Bootstrap 废弃）。
