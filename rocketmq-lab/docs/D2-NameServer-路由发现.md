# D2 — NameServer 路由发现

> **定位**：D1 已定义 Topic/Queue/Broker 等术语，本篇回答"Producer/Consumer 怎么知道消息该发给谁"。
> **深度**：架构原理级——6 张表结构 + 注册/剔除/查询三条时序 + AP 设计的后果。
> **源码依据**：rocketmq-all-5.1.4（`code/spring/rocketmq-5.1.4`，与运行时对齐），所有数字与类名经 grep 验证，见文末验证锚点。

---

## 一、NameServer 是什么，为什么这样设计

NameServer 的职责只有一个：维护"Topic → 哪些 Broker 的哪些 Queue 可写/可读"的映射，供客户端查询。

与 ZooKeeper/Nacos(CP) 的根本差异：

| 维度 | ZK / Nacos(CP) | NameServer |
|---|---|---|
| 一致性 | 强一致（选主+过半写） | **最终一致**，无协议 |
| 节点间 | 互相同步 | **互不通信**，各自收全量上报 |
| 状态 | 有状态（数据目录） | **无状态**，重启即空，靠 Broker 30s 内重报填满 |
| 写路径 | 过半确认 | 单节点内存写，O(1) |

**为什么敢不要强一致**：路由数据的正确性有三个兜底——① Broker 每 30s 全量重报，任何丢失/偏差 30s 内自愈；② 客户端每 30s 重拉并本地缓存，NameServer 短暂故障不影响已有路由；③ 路由错误的代价是"发到不该发的 Broker"被重试掩盖，不是数据丢失。CP 的收益（秒级一致性）抵不过代价（选主窗口期整个 MQ 不可路由）。

---

## 二、核心结构：RouteInfoManager 六张表

NameServer 的全部路由状态都在 `RouteInfoManager`（`namesrv/routeinfo/RouteInfoManager.java:71-79`），一个 `ReentrantReadWriteLock` 保护：

| 表 | Key → Value | 回答的问题 |
|---|---|---|
| `topicQueueTable` | topic → Map<brokerName, QueueData> | 这个 Topic 在哪些 Broker 上、各多少读写队列 |
| `brokerAddrTable` | brokerName → BrokerData（含 brokerAddrs: Map<brokerId, addr>） | 这个 brokerName 的主从地址 |
| `clusterAddrTable` | clusterName → Set<brokerName> | 这个集群有哪些 Broker |
| `brokerLiveTable` | brokerAddr → BrokerLiveInfo | **这个 Broker 还活着吗**（剔除判断的唯一依据） |
| `filterServerTable` | brokerAddr → List<filterServer> | 类过滤服务器列表（历史遗留，类过滤已废弃） |
| `topicQueueMappingInfoTable` | topic → Map<brokerName, TopicQueueMappingInfo> | 5.x 静态 Topic 逻辑队列映射（队列扩容/迁移用） |

`BrokerLiveInfo` 字段：channel、lastUpdateTimestamp、heartbeatTimeoutMillis（判活三件套）+ brokerName/brokerAddr/brokerId + **epoch / maxOffset / confirmOffset / electionPriority**（5.x Controller 模式专用：epoch  fencing 防脑裂、electionPriority 影响选主优先级——未开 Controller 时这几个字段闲置）。

QueueData = brokerName + readQueueNums + writeQueueNums + perm + topicSysFlag；BrokerData = cluster + brokerName + brokerAddrs + zoneName + enableActingMaster。

**关键理解**：`brokerLiveTable` 是唯一带"时间"的表——前五张表回答拓扑，它回答生死。

---

## 三、注册流程（Broker 视角）

```
BrokerController 启动
   │  registerBrokerAll(true, false, true)     ← 启动即注册一次
   │  定时任务: 初始延迟 10s，之后每 registerNameServerPeriod
   │            (默认 30s，实际调度 clamp 到 [10s, 60s])
   ▼
遍历 namesrvAddrList 里【每一台】NameServer  ──▶  REGISTER_BROKER (RequestCode 103)
   │  携带: clusterName, brokerAddr, brokerName, brokerId,
   │        haServerAddr, zoneName, timeoutMillis,
   │        TopicConfigSerializeWrapper(本机全部 Topic 配置), filterServerList
   ▼
NameServer DefaultRequestProcessor.registerBroker()
   │  写锁内更新 5 张拓扑表 + brokerLiveTable(刷新 lastUpdateTimestamp)
   ▼
返回 RegisterBrokerResult { haServerAddr, masterAddr, kvTable }
   └─ Broker 发现 masterAddr 不是自己 → 知道自己该当 Slave，回连 Master 同步
```

三个要点：

1. **向所有 NameServer 注册**，不是一台——所以任意一台 NS 都有全量路由，客户端连哪台都行
2. **注册即心跳**：没有独立的 heartbeat 请求，30s 周期全量重报同时完成"报活"和"数据纠偏"——这是 NameServer 敢于无状态的核心机制
3. **注册响应教会 Broker 角色**：返回的 masterAddr 让 Slave 找到 Master 建立复制链路

## 四、剔除流程（NameServer 视角）

两条路径：

**路径 A：优雅关闭**——Broker shutdown 时主动发 `UNREGISTER_BROKER`（RequestCode 104）→ 各表清除（`BrokerController.java:1291,1508` 的 `unregisterBrokerAll`）。秒级生效。

**路径 B：故障剔除**——Broker 宕机来不及注销：

```
NamesrvController 每 5s (scanNotActiveBrokerInterval, 默认 5000ms)
   ▼
scanNotActiveBroker() 扫 brokerLiveTable:
   lastUpdateTimestamp + heartbeatTimeoutMillis(默认 120s) < now ?
   ▼ 是
closeChannel → onChannelDestroy → 提交 BatchUnregistrationService(5.x 批量注销)
   ▼
写锁内删: brokerAddrTable / clusterAddrTable / topicQueueTable / brokerLiveTable / filterServerTable
```

**故障感知延迟 = 最长 120s（心跳超时）+ 5s（扫描间隔）**。这两分钟内路由里仍有死 Broker——AP 的直接代价，缓解在客户端侧（§六）。

## 五、路由查询（客户端视角）

```
Producer/Consumer 每 30s (pollNameServerInterval)
   ▼  从 namesrvAddrList 轮询选一台 NS
GET_ROUTEINFO_BY_TOPIC (RequestCode 105)
   ▼
RouteInfoManager.pickupTopicRouteData(topic)  ← 读锁
   ▼
TopicRouteData {
    queueDatas:  List<QueueData>   ← 各 Broker 上该 Topic 的读写队列数
    brokerDatas: List<BrokerData>  ← 各 brokerName 的主从地址表
    filterServerTable / orderTopicConf / topicQueueMappingByBroker
}
   ▼
客户端组装为本地路由:
  Producer → TopicPublishInfo(可写队列列表, 供轮询选择)
  Consumer → 可读的 (topic, brokerName, queueId) 全集, 供 Rebalance 分配
```

**特例：TBW102 回退**。Producer 发送前查目标 Topic 路由为空时，若 Broker 开了 `autoCreateTopicEnable`，客户端改查 `TBW102` 模板 Topic 的路由（`MQClientAPIImpl.java:2007`），拿到默认队列数（8）的 Broker 列表直接发——Broker 收到时顺带自动建 Topic。这就是 D1 说的"生产必须关闭自动创建"的机制根源。

**客户端怎么找到 NameServer**：两种方式——① 静态配置 `namesrvAddr`（本 lab 方式）；② 不配时走 **HTTP 地址服务器**动态拉取，每 2 分钟刷新（`fetchNameServerAddr` 调度，`MQClientInstance.java:297`）。生产多集群切换用方式②免改配置。

**KV 配置**：NameServer 还兼职存 KV 配置（`KVConfigManager`），典型用途是顺序消息的 `orderTopicConf`（哪些 Topic 强制全局顺序）——见 TopicRouteData 的 orderTopicConf 字段。

**5.x Controller 模式**：Broker 注册走独立请求码 `CONTROLLER_REGISTER_BROKER`（1003，区别于普通 103），携带 epoch/选举信息供 Controller 协调选主。

**注册时的版本防冲突**：注册请求携带 `DataVersion.stateVersion`（单调递增），若 NameServer 上同 brokerId 已有**更新版本**的注册信息，本次注册直接拒绝并摘除旧 brokerLive 记录（`RouteInfoManager.java:281-289`）——防止过期 Broker 配置覆盖新配置（如 Broker 换机重启后旧实例复活抢注册）。

**运维摘流量**：`wipeWritePermOfBroker`（:503，写锁内改 perm 去写权限）把某 Broker 的 Topic 队列改为**只读**——Producer 路由里它从可写队列消失，Consumer 不受影响，队列内堆积被消费完后即可安全下线。这是优雅缩容的标准动作，比直接 kill 进程（走 120s 故障剔除路径）干净得多。

**NameServer 启动**：`NamesrvController.initialize()`（:101）加载 KV 配置 + 初始化 Netty server（9876）→ `startScheduleService()`（:106，注册 5s 扫描等定时任务）→ `start()`（:221）启动网络层。无存储加载——再次印证无状态设计。

---

## 六、AP 设计的后果（故障场景推演）

| 场景 | 后果 | 谁兜底 |
|---|---|---|
| NameServer 单节点宕机 | 无影响（节点互不依赖，客户端轮询换台） | 架构本身 |
| NameServer **全部**宕机 | 新 Topic 路由查不到；存量收发正常 | 客户端本地路由缓存（30s 刷新前的最后一份继续用） |
| Broker 宕机 | 最长 ~2 分钟路由仍含死节点 | Producer 发送失败重试换 Broker；Consumer 拉取失败由 Rebalance/重试掩盖 |
| Broker 重启后 Topic 配置变化 | 最长 30s 内各 NS 数据不一致 | 30s 周期全量重报自愈 |
| 网络分区：Broker 能连 NS-A 不能连 NS-B | NS-B 剔除该 Broker，NS-A 保留——两个 NS 路由不一致 | 客户端连不同 NS 看到不同世界；TCP 连接断开后 NS-B 侧 120s 超时剔除，期间靠客户端重试 |

**一句话**：NameServer 用"周期性全量重报 + 客户端本地缓存 + 发送重试"三件套，把最终一致的代价压到了用户无感。

---

## 七、本篇要点（面试视角）

1. **注册即心跳**——Broker 每 30s 向**所有** NameServer 全量重报，无独立心跳协议
2. **故障剔除 = 120s 超时 + 5s 扫描**——AP 的代价就是最长约 2 分钟的路由陈旧，客户端重试兜底
3. **六张表里只有 brokerLiveTable 带时间**——拓扑五表 + 生死一表
4. **NameServer 无状态可水平扩**——代价是节点间永不同步，数据靠 Broker 重报收敛
5. **TBW102 是自动建 Topic 的路由模板**——生产关 `autoCreateTopicEnable` 后此路径失效
6. **优雅关闭走 UNREGISTER_BROKER 秒级摘除**——只有宕机才走 120s 超时路径

---

## 八、验证锚点

| 声明 | 源码位置 |
|---|---|
| 六张表 + ReadWriteLock | `namesrv/.../routeinfo/RouteInfoManager.java:71-79` |
| registerBroker 参数列表 | `RouteInfoManager.java:212-237` |
| 注册周期默认 30s，clamp [10s,60s]，初始延迟 10s | `common/.../BrokerConfig.java:186` / `broker/.../BrokerController.java:1657` |
| REGISTER_BROKER=103 / UNREGISTER_BROKER=104 / GET_ROUTEINFO_BY_TOPIC=105 | `remoting/.../protocol/RequestCode.java:105-108` |
| 扫描每 5s | `common/.../namesrv/NamesrvConfig.java:55` / `namesrv/.../NamesrvController.java:116-118` |
| 心跳超时默认 120s | `RouteInfoManager.java:70`（DEFAULT_BROKER_CHANNEL_EXPIRED_TIME） |
| scanNotActiveBroker → closeChannel → onChannelDestroy | `RouteInfoManager.java:803-818` |
| 批量注销 BatchUnregistrationService | `RouteInfoManager.java:79,91-105` |
| 优雅关闭 unregisterBrokerAll | `broker/.../BrokerController.java:1291,1508` |
| pickupTopicRouteData | `RouteInfoManager.java:700` |
| TopicRouteData 结构 | `remoting/.../route/TopicRouteData.java:33-39` |
| QueueData / BrokerData 字段 | `remoting/.../route/QueueData.java:24-28` / `BrokerData.java:32-46` |
| 客户端 30s 路由刷新 | `client/.../ClientConfig.java:55`（pollNameServerInterval）/ `MQClientInstance.java:300-305` |
| TBW102 回退 | `client/.../impl/MQClientAPIImpl.java:2007` |
| KVConfigManager | `namesrv/.../kvconfig/KVConfigManager.java:52-56` |
| RegisterBrokerResult 三字段 | `remoting/.../protocol/namesrv/RegisterBrokerResult.java:22-25` |
| 注册响应回写 masterAddr/haServerAddr | `broker/.../out/BrokerOuterAPI.java:580-581` |
| 客户端轮询选 NS（namesrvIndex 自增取模） | `remoting/.../netty/NettyRemotingClient.java:637-643` |
| BrokerLiveInfo 全字段（含 epoch/electionPriority） | `controller/.../impl/heartbeat/BrokerLiveInfo.java:24-33` |
| fetchNameServerAddr 每 2min（未静态配置时） | `client/.../factory/MQClientInstance.java:297` |
| CONTROLLER_REGISTER_BROKER=1003 | `remoting/.../protocol/RequestCode.java:252` |
| 注册版本防冲突（stateVersion 旧注册被拒） | `namesrv/.../routeinfo/RouteInfoManager.java:281-289` |
| wipeWritePermOfBroker 摘流量 | `RouteInfoManager.java:503-512` |
| NamesrvController 启动三段 | `namesrv/.../NamesrvController.java:101,106,221` |
