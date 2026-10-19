# P02-RocketMQ D-02 — NameServer 路由发现

> **定位**：RocketMQ 深挖系列第 2 篇（按 P02 规划产出物）。**无状态路由中心**——注册/剔除/查询三条链。
> **源码版本**：rocketmq-all-5.1.4（`code/spring/rocketmq-5.1.4`）
> **质量标准**：源码阅读清单 / 完整调用链 / 设计哲学（反面假设）/ 生产陷阱 / 面试 Q&A（意图）/ 章节依赖

---

## 一、它干了什么：一个例子

```
Broker 启动
  → 立即注册一次 + 每 30s 全量重报（向【所有】NameServer）
      → NameServer RouteInfoManager 写六张表（路由+存活）

Producer 发消息
  → 每 30s 拉一次路由（GET_ROUTEINFO_BY_TOPIC）
      → 本地缓存 TopicPublishInfo（可写队列列表）→ 选队列发送

Broker 宕机
  → NameServer 每 5s 扫 brokerLiveTable：120s 无心跳 → 剔除此 Broker
      → 客户端最多 30s 后拉到新路由（期间发送失败靠重试换 Broker）
```

**一句话**：NameServer 是"**无状态的路由登记处**"——Broker 周期全量重报（注册即心跳），客户端周期拉取并本地缓存；节点之间**互不通信**，各自维护全量（AP）。

---

## 二、源码阅读清单

| 文件 | 关键方法/结构 | 行号 |
|---|---|---|
| `namesrv/routeinfo/RouteInfoManager.java` | **六张表**（topicQueue/brokerAddr/clusterAddr/brokerLive/filterServer/topicQueueMapping） | :72-77 |
| | `registerBroker`（写表 + dataVersion 防旧覆盖） | :212 / :226 |
| | `unRegisterBroker`（批量注销） | :569 |
| | `pickupTopicRouteData`（读锁查询） | :698 |
| | `scanNotActiveBroker`（5s 扫描） | :801 |
| | `onChannelDestroy`（连接断开触发注销） | :818 / :841 |
| | `DEFAULT_BROKER_CHANNEL_EXPIRED_TIME`（120s） | :70 |
| | `wipeWritePermOfBroker`（摘写权限） | :503 |
| `namesrv/processor/DefaultRequestProcessor.java` | `registerBroker` / `unregisterBroker` | :207 / :101 |
| `namesrv/routeinfo/BrokerHousekeepingService.java` | 连接事件 → `onChannelDestroy` | :37/42/47 |
| `namesrv/NamesrvController.java` | `initialize` / `startScheduleService` / `start` | :101 / :116 / :221 |
| `broker/BrokerController.java` | `registerBrokerAll` 调度（初始 10s，clamp [10s,60s]） | :1638 / :1658 |
| `broker/out/BrokerOuterAPI.java` | `registerBrokerAll`（遍历所有 NS） | :580 |
| `client/impl/factory/MQClientInstance.java` | 路由刷新调度（30s）/ `updateTopicRouteInfoFromNameServer` | :302 / :338 |
| `client/impl/MQClientAPIImpl.java` | TBW102 回退 | :2007 |
| `remoting/protocol/RequestCode.java` | REGISTER=103 / UNREGISTER=104 / GET_ROUTEINFO=105 / CONTROLLER_REGISTER=1003 | :105-108 / :252 |

---

## 三、完整调用链（三条链）

### 3.1 注册链（Broker → NameServer）

```
BrokerController.start()
 ├─ registerBrokerAll(true,false,true)(:1638)          ← 启动立即注册
 └─ scheduledExecutorService.scheduleAtFixedRate(:1658)
      }, 1000*10, Math.max(10000, Math.min(registerNameServerPeriod, 60000))  ← 初始 10s，周期 30s，clamp [10s,60s]
      └─ BrokerOuterAPI.registerBrokerAll(:580)         ← 遍历 namesrvAddrList【每一台】NS
           └─ DefaultRequestProcessor.registerBroker(:207)
                └─ RouteInfoManager.registerBroker(:212/226)
                     ├─ 写锁 lock.writeLock()
                     ├─ clusterAddrTable / brokerAddrTable 更新
                     ├─ topicQueueTable 更新（TopicConfigSerializeWrapper 全量 Topic 配置）
                     ├─ brokerLiveTable.put(刷新 lastUpdateTimestamp + timeoutMillis=120s)
                     └─ dataVersion(stateVersion) 检查：旧版本注册被拒并摘除旧 live 记录
```

**注册请求携带**：clusterName/brokerAddr/brokerName/brokerId/haServerAddr/zoneName/timeoutMillis + **TopicConfigSerializeWrapper（本机全部 Topic 配置）** + filterServerList。**注册响应** `RegisterBrokerResult{haServerAddr, masterAddr, kvTable}`——Slave 靠 masterAddr 回连 Master。

### 3.2 剔除链（两条路径）

**路径 A：优雅关闭（秒级）**
```
Broker shutdown → BrokerController.unregisterBrokerAll(:1508，调用点 :1291)
  → BrokerOuterAPI.unregisterBrokerAll → UNREGISTER_BROKER(104)
  → DefaultRequestProcessor.unregisterBroker(:101) → RouteInfoManager 各表清除
```

**路径 B：故障剔除（最长 120s + 5s）**
```
NamesrvController.startScheduleService(:116)
 └─ 每 5s（scanNotActiveBrokerInterval, NamesrvConfig:55）scanNotActiveBroker(:801)
      ├─ brokerLiveTable：lastUpdateTimestamp + 120s < now ?
      └─ closeChannel → BrokerHousekeepingService.onChannelDestroy(:37) → RouteInfoManager.onChannelDestroy(:841)
           → BatchUnregistrationService（批量注销）→ 写锁内删六表
```

### 3.3 查询链（客户端 → NameServer）

```
MQClientInstance.startScheduledTask(:302)
 └─ 每 30s（pollNameServerInterval, ClientConfig:55）updateTopicRouteInfoFromNameServer(:338)
      ├─ 从 namesrvAddrList 轮询选一台 NS（NettyRemotingClient namesrvIndex 自增取模）
      ├─ GET_ROUTEINFO_BY_TOPIC(105) → RouteInfoManager.pickupTopicRouteData(:698)（读锁）
      ├─ 返回 TopicRouteData{queueDatas, brokerDatas, filterServerTable, orderTopicConf, topicQueueMappingByBroker}
      └─ 更新本地：Producer→TopicPublishInfo / Consumer→Rebalance 输入
特殊：Topic 查不到且 Broker 开 autoCreateTopicEnable → 改查 TBW102 模板（MQClientAPIImpl:2007）
```

### 3.4 5.x：Controller 模式（补充）

`CONTROLLER_REGISTER_BROKER`（1003，RequestCode:252）——Broker 向 Controller 注册（带 epoch/选举信息），由 Controller 协调选主；普通注册（103）不受影响。

---

## 四、使用（生产口径）

### 4.1 路由配置速查

| 配置 | 端 | 默认 | 说明 |
|---|---|---|---|
| `namesrvAddr` | 客户端/Broker | 必配（多地址逗号分隔） | 客户端轮询 / Broker 全量注册 |
| `registerNameServerPeriod` | Broker | 30s（clamp [10s,60s]） | 注册周期 |
| `scanNotActiveBrokerInterval` | NameServer | 5s | 故障扫描 |
| `pollNameServerInterval` | 客户端 | 30s | 路由刷新 |
| `autoCreateTopicEnable` | Broker | true | **生产必须 false**（D-02 陷阱 3） |

### 4.2 生产实践

- **多 NS 部署**：≥2 台，客户端/Broker 配全量地址（单台故障无感）
- **优雅下线**：`wipeWritePermOfBroker` 摘写权限 → 等待堆积消费完 → 停进程（比 kill 干净）
- **路由延迟容忍**：新 Topic 有 30s 可见窗口——发送重试掩盖；敏感场景调小 `pollNameServerInterval`
- **监控**：NS 路由表规模 / Broker 注册间隔告警 / 自动建 Topic 事件（误开即告警）

### 4.3 排查入口

- "发了消息收不到" → 先查 topic 路由是否存在（`mqadmin topicRoute`）+ 消费者订阅是否一致
- "Broker 已停但仍在路由" → 120s 剔除窗口内正常（等待或检查心跳）

## 五、设计哲学（反面假设表）

| 设计决策 | 反事实假设 | 为什么选这个 |
|---|---|---|
| **NameServer 无状态** | 像 ZK/Nacos 持久化路由 | 路由数据可由 Broker 30s 重报重建——重启即空也无所谓；不需要磁盘/选举 |
| **节点间互不通信** | 互相同步（复制） | 各节点收**全量**上报，天然最终一致；省掉同步协议与一致性复杂度 |
| **注册即心跳（30s 全量重报）** | 独立心跳 + 增量注册 | 一次请求同时完成"报活+数据纠偏"——丢失/偏差 30s 自愈 |
| **AP 而非 CP** | 强一致（选主+过半写） | 路由错误的代价是"发错 Broker 被重试掩盖"；CP 的选主窗口会让整个 MQ 不可路由 |
| **客户端 30s 拉 + 本地缓存** | 服务端推送 | 拉模型简单；NS 全挂也不影响存量收发（缓存兜底） |

---

## 六、生产陷阱（含复现/验证）

| # | 陷阱 | 根因 | 验证 | 修复 |
|---|---|---|---|---|
| 1 | **新 Topic 路由 30s 才可见** | 客户端拉取周期 30s（pollNameServerInterval） | 注册后立即发送观察失败窗口 | 发送重试换 Broker 掩盖；对延迟敏感缩短间隔 |
| 2 | **Broker 宕机后 2 分钟仍可发到死节点** | 120s 心跳超时 + 5s 扫描 | 杀 Broker 后持续发送 | Producer 重试换 Broker（客户端兜底） |
| 3 | **Topic 静默自动创建** | `autoCreateTopicEnable=true` + TBW102 回退 | 发错 Topic 名不报错 | 生产关闭（xhs 加固） |
| 4 | **Broker 换机重启旧实例抢注册** | 无版本校验的旧注册覆盖新注册 | 模拟旧实例晚到注册 | dataVersion/stateVersion 拒绝旧版本（:281 区域） |
| 5 | **下线时流量未摘干净** | 直接 kill 走 120s 故障路径 | —— | `wipeWritePermOfBroker`（:503）改 perm 为只读，堆积消费完再下线 |

---

## 七、面试 Q&A（含面试官意图）

**Q1："为什么 RocketMQ 不用 ZooKeeper？"**（意图：架构取舍理解）
NameServer 无状态 + 节点不通信 + 30s 全量重报自愈；ZK 的 CP 代价（选主窗口不可路由）不值。**危险信号**：只答"更轻量"而不说 AP/自愈机制。

**Q2："Broker 注册的完整流程？"**（意图：是否读过注册链）
Broker 启动立即 + 每 30s（clamp [10s,60s]）→ BrokerOuterAPI 遍历所有 NS → DefaultRequestProcessor → RouteInfoManager 写六表 + dataVersion 校验；响应教 Slave 找 Master。

**Q3："故障剔除要多久？为什么？"**（意图：AP 代价的量化）
最长 120s（心跳超时）+ 5s（扫描）≈ 2 分钟——期间路由含死节点，靠客户端重试掩盖。

**Q4："客户端怎么感知路由变化？"**（意图：拉模型理解）
每 30s 轮询一台 NS 拉取 + 本地缓存；Rebalance 输入随之更新。**加分**：NS 全挂时存量路由仍可用。

**Q5："注册请求为什么带全量 Topic 配置？"**（意图：注册即心跳的设计）
一次请求完成"报活 + Topic 元数据纠偏"——无需独立心跳协议；数据偏差 30s 内自愈。

---

## 八、章节依赖

- **上游**：lab D2（本主题的概念版）；D-01（存储与 Topic 的关系）
- **下游**：D-05 消费端启动流程（updateTopicSubscribeInfoWhenSubscriptionChanged 拉路由）；P02-对比 D-01（Kafka KRaft/ZK 元数据对比）
- **横链**：K01 拷打篇（"为什么不用 ZK"）；Nacos 系列（有状态注册中心对照）

---

## 九、验证锚点（汇总）

| 声明 | 位置（5.1.4） |
|---|---|
| 六张表 | `RouteInfoManager:72-77` |
| registerBroker（写表+版本校验） | `RouteInfoManager:212/226`（dataVersion 区域 :281） |
| 120s 心跳超时 | `RouteInfoManager.DEFAULT_BROKER_CHANNEL_EXPIRED_TIME:70` |
| 5s 扫描 + 批量注销 | `RouteInfoManager.scanNotActiveBroker:801`；`NamesrvConfig:55` |
| onChannelDestroy | `RouteInfoManager:818/841`；`BrokerHousekeepingService:37` |
| 查询（读锁） | `RouteInfoManager.pickupTopicRouteData:698` |
| 摘写权限 | `RouteInfoManager.wipeWritePermOfBroker:503` |
| Broker 注册调度（10s/30s/clamp） | `BrokerController:1638/1658`；`BrokerConfig.registerNameServerPeriod:186` |
| 优雅注销 | `BrokerController.unregisterBrokerAll:1508`（调用 :1291） |
| 客户端 30s 拉取 | `MQClientInstance:302/338`；`ClientConfig.pollNameServerInterval:55` |
| TBW102 回退 | `MQClientAPIImpl:2007` |
| 请求码 | `RequestCode:105-108`（103/104/105）；`:252`（1003） |
| NameServer 启动三段 | `NamesrvController:101/116/221` |
