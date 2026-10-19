# D-08: Push 推送体系 + 健康检查 + 故障自愈

> 前置依赖: D-07（注册处理链 + 事件驱动索引）
> 后续: D-09（Distro V2 协议）

---

## 一、概述

D-07 覆盖了写路径(注册 -> 事件 -> 索引)。这篇覆盖三个下游能力:

1. **Push 推送**: ServiceChangedEvent -> PushDelayTask(延迟合并) -> 找到所有订阅者 -> gRPC Push
2. **健康检查**: 定时扫描 -> 标记不健康 / 删除过期实例
3. **故障自愈**: 连接断开 -> ClientReleaseEvent -> 全自动清理 + 集群同步

这三个能力合在一起,构成 Nacos "注册-变更感知-故障处理"的完整闭环。

---

## 二、Push 推送体系

### 2.1 推送入口 - NamingSubscriberServiceV2Impl

```java
// push/v2/NamingSubscriberServiceV2Impl.java
@Service
public class NamingSubscriberServiceV2Impl extends SmartSubscriber {
    private final PushDelayTaskExecuteEngine delayTaskEngine;

    // 订阅两种事件
    public List<Class<? extends Event>> subscribeTypes() {
        return List.of(ServiceChangedEvent.class, ServiceSubscribedEvent.class);
    }

    public void onEvent(Event event) {
        if (event instanceof ServiceChangedEvent) {
            // 服务实例列表变化 -> 推送给所有订阅者
            delayTaskEngine.addTask(service,
                new PushDelayTask(service, PushConfig.getPushTaskDelay()));  // 500ms
        }
        if (event instanceof ServiceSubscribedEvent) {
            // 新客户端订阅 -> 只推送给该客户端
            delayTaskEngine.addTask(service,
                new PushDelayTask(service, PushConfig.getPushTaskDelay(), clientId));
        }
    }
}
```

**两种推送场景**:

| 场景 | 触发事件 | 推送范围 | PushDelayTask 特点 |
|------|---------|---------|-------------------|
| 实例变更 | ServiceChangedEvent | 该 Service 的**所有**订阅者 | pushToAll=true |
| 新订阅 | ServiceSubscribedEvent | 仅新订阅的**单个**客户端 | pushToAll=false, targetClients={clientId} |

### 2.2 PushDelayTask - 延迟合并(debounce)

```java
// push/v2/task/PushDelayTask.java
public class PushDelayTask extends AbstractDelayTask {
    final Service service;
    boolean pushToAll;
    Set<String> targetClients;  // null when pushToAll=true
}
```

**merge 逻辑** -- 同一个 Service 的多次变更合并为一次推送:

```java
public void merge(AbstractDelayTask task) {
    PushDelayTask oldTask = (PushDelayTask) task;
    if (isPushToAll() || oldTask.isPushToAll()) {
        pushToAll = true;           // "推全部"胜出 -- 范围最广的赢
        targetClients = null;
    } else {
        targetClients.addAll(oldTask.getTargetClients());  // 否则客户端集合取并集
    }
    setLastProcessTime(Math.min(getLastProcessTime(), task.getLastProcessTime()));
    // lastProcessTime 取最早 -- 保证第一次变更后 500ms 一定触发,不会被持续变更无限延迟
}
```

**合并示例**: 3 个实例在 200ms 内连续注册:
```
T0:   实例A注册 -> ServiceChangedEvent -> PushDelayTask(pushToAll=true, lastProcessTime=T0)
T100: 实例B注册 -> ServiceChangedEvent -> merge -> PushDelayTask(pushToAll=true, lastProcessTime=T0)
T200: 实例C注册 -> ServiceChangedEvent -> merge -> PushDelayTask(pushToAll=true, lastProcessTime=T0)
T500: 500ms 到期 -> 执行 1 次 Push -> 推送给所有订阅者(含 A/B/C 三个变更)
```

### 2.3 NacosDelayTaskExecuteEngine - 延迟任务引擎

```java
// common/task/engine/NacosDelayTaskExecuteEngine.java
// 每 100ms 扫描一次任务表
processingExecutor.scheduleWithFixedDelay(new ProcessRunnable(), 100, 100, MILLISECONDS);

// addTask: 同 key 的任务合并
void addTask(Object key, AbstractDelayTask newTask) {
    AbstractDelayTask existTask = tasks.get(key);
    if (existTask != null) {
        newTask.merge(existTask);  // 新任务合并旧任务
    }
    tasks.put(key, newTask);  // 合并后的新任务替换旧任务
}

// shouldProcess: 延迟到期判断
boolean shouldProcess() {
    return System.currentTimeMillis() - lastProcessTime >= taskInterval;  // taskInterval=500ms
}
```

### 2.4 PushExecuteTask - 实际推送执行

```java
// push/v2/task/PushExecuteTask.java
public void run() {
    PushDataWrapper wrapper = generatePushData();  // serviceStorage.getPushData(service)

    for (String clientId : getTargetClientIds()) {
        Client client = clientManager.getClient(clientId);
        if (client == null) continue;              // 已断开 -> 跳过
        Subscriber subscriber = client.getSubscriber(service);
        if (subscriber == null) continue;
        pushExecutor.doPushWithCallback(clientId, subscriber, wrapper,
            new ServicePushCallback(clientId, subscriber, wrapper, isPushToAll));
    }
}
```

`getTargetClientIds()`:
- pushToAll=true -> `indexesManager.getAllClientsSubscribeService(service)` (所有订阅者)
- pushToAll=false -> `delayTask.getTargetClients()` (合并后的客户端集合)

### 2.5 PushExecutorDelegate - gRPC vs UDP 选择

```java
// push/v2/executor/PushExecutorDelegate.java:56-66
private PushExecutor getPushExecuteService(String clientId, Subscriber subscriber) {
    Optional<SpiPushExecutor> result = SpiImplPushExecutorHolder.getInstance()
            .findPushExecutorSpiImpl(clientId, subscriber);
    if (result.isPresent()) {
        return result.get();      // 1. SPI 扩展优先（可自定义推送执行器）
    }
    // use nacos default push executor
    // 2. 默认: clientId 含 '#' = IpPortBasedClient(1.x 兼容) -> UDP
    //          clientId 不含 '#' = ConnectionBasedClient(2.x 原生) -> gRPC
    return clientId.contains(IpPortBasedClient.ID_DELIMITER) ? udpPushExecuteService : rpcPushExecuteService;
}
```

推送执行器选择**先查 SPI 扩展**（`SpiPushExecutor`，可插拔），无扩展时用默认：2.x 原生客户端走 gRPC 推送(`RpcPushService.pushWithCallback` -> `connection.asyncRequest` -> bi-di stream, 详见 D-05)，1.x 兼容客户端走 UDP。

### 2.6 推送时的保护阈值

`PushExecutorRpcImpl` 在推送前会调用 `ServiceUtil.selectInstancesWithHealthyProtection(...)` 对实例列表做保护阈值过滤(详见 D-01 的保护阈值机制)。所以**推送的数据已经过保护阈值处理**--如果保护触发,推送的 ServiceInfo 中不健康实例已被强制标记为 healthy=true。

### 2.7 关键参数

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `pushTaskDelay` | 500ms | 延迟合并窗口(debounce) |
| `pushTaskTimeout` | 5000ms | 单次推送超时 |
| `pushTaskRetryDelay` | 1000ms | 推送失败后重试延迟 |

---

## 三、推送容错与重试

### 3.1 三层容错

```
层级1: PushExecuteTask 整体异常
  -> addTask(service, new PushDelayTask(service, 1000ms))  // 1s 后全量重推

层级2: 单个客户端推送失败 (ServicePushCallback.onFail)
  -> addTask(service, new PushDelayTask(service, retryDelay=1000ms, clientId))  // 1s 后单客户端重推
  // 除非 NoRequiredRetryException(客户端明确说不用重试)

层级3: 连接已关闭 (ConnectionAlreadyClosedException)
  -> connectionManager.unregister(connectionId)  // 清理死连接
  -> 视为成功(不重试) -- 订阅者已不在,重推无意义
```

### 3.2 SLA 追踪

`ServicePushCallback.onSuccess` 记录三个时间指标:

| 指标 | 计算 | 含义 |
|------|------|------|
| `pushCostTimeForNetWork` | finish - executeStartTime | 纯网络推送耗时 |
| `pushCostTimeForAll` | finish - delayTask.getLastProcessTime | 从变更入队到推送完成(含合并窗口) |
| `serviceLevelAgreementTime` | finish - service.getLastUpdatedTime() | 端到端 SLA(从服务变更到客户端收到) |

---

## 四、健康检查 V2

### 4.1 双层健康检查

Nacos 2.x 健康检查 = **连接驱动(主)** + **定时补偿(兜底)**:

```
[第一层: 连接驱动 - 主要机制]
ConnectionManager.unregister(connectionId)  // D-05
  -> ClientReleaseEvent
  -> ClientServiceIndexesManager.handleClientDisconnect  // D-07
    -> 清理 publisherIndex + subscriberIndex
    -> ServiceChangedEvent -> 触发 Push

[第二层: 定时补偿 - 兜底机制]
ClientBeatCheckTaskV2 (定时任务):
  -> 遍历 Client 的所有 published service
  -> 拦截器链: Distro 负责检查 -> 服务级开关 -> 实例级开关
  -> UnhealthyInstanceChecker: 15s 无心跳 -> 标记不健康
  -> ExpiredInstanceChecker: 30s 无心跳 -> 删除实例
```

### 4.2 ClientBeatCheckTaskV2 - 拦截器链

```java
// healthcheck/heartbeat/ClientBeatCheckTaskV2.java
public void doHealthCheck() {
    for (Service each : client.getAllPublishedService()) {
        HealthCheckInstancePublishInfo instance =
            (HealthCheckInstancePublishInfo) client.getInstancePublishInfo(each);
        interceptorChain.doInterceptor(new InstanceBeatCheckTask(client, each, instance));
    }
}
```

**拦截器链** (按 order 从小到大执行,任一返回 true 则跳过检查):

| 拦截器 | order | 跳过条件 |
|--------|-------|---------|
| ServiceEnableBeatCheckInterceptor | MIN_VALUE | 服务元数据标记禁用心跳检查 |
| InstanceEnableBeatCheckInterceptor | MIN_VALUE+1 | 实例元数据标记禁用心跳检查 |
| InstanceBeatCheckResponsibleInterceptor | MIN_VALUE+2 | `!DistroMapper.responsible(clientId)` -- 本节点不负责该 Client |

**Distro 负责检查**: 只有"负责节点"(Distro 一致性哈希分配的节点)才执行健康检查,避免多个节点重复检查同一个 Client。详见 D-09。

### 4.3 UnhealthyInstanceChecker - 标记不健康(15s)

```java
// healthcheck/heartbeat/UnhealthyInstanceChecker.java
public void doCheck(Client client, Service service, HealthCheckInstancePublishInfo instance) {
    if (instance.isHealthy() && isUnhealthy(instance)) {
        changeHealthyStatus(client, service, instance, false);  // 标记不健康
    }
}

private boolean isUnhealthy(HealthCheckInstancePublishInfo instance) {
    return System.currentTimeMillis() - instance.getLastHeartBeatTime() > beatTimeout;
    // beatTimeout 默认 15s (Constants.DEFAULT_HEART_BEAT_TIMEOUT)
}

private void changeHealthyStatus(...) {
    instance.setHealthy(false);
    NotifyCenter.publishEvent(new ServiceChangedEvent(service));  // 重新触发 Push!
    NotifyCenter.publishEvent(new ClientChangedEvent(client));
    NotifyCenter.publishEvent(new HealthStateChangeTraceEvent(...));
}
```

**关键闭环**: 标记不健康后发布 `ServiceChangedEvent` -> 触发 PushDelayTask -> 推送给订阅者 -> 订阅者收到更新后的实例列表(该实例 healthy=false)。这是健康检查与推送体系的**闭环连接**。

### 4.4 ExpiredInstanceChecker - 删除过期实例(30s)

```java
// healthcheck/heartbeat/ExpiredInstanceChecker.java
public void doCheck(Client client, Service service, HealthCheckInstancePublishInfo instance) {
    if (globalConfig.isExpireInstance() && isExpireInstance(instance)) {
        deleteIp(client, service, instance);
    }
}

private boolean isExpireInstance(HealthCheckInstancePublishInfo instance) {
    return System.currentTimeMillis() - instance.getLastHeartBeatTime() > deleteTimeout;
    // deleteTimeout 默认 30s (Constants.DEFAULT_IP_DELETE_TIMEOUT)
}

private void deleteIp(Client client, Service service, InstancePublishInfo instance) {
    client.removeServiceInstance(service);
    NotifyCenter.publishEvent(new ClientDeregisterServiceEvent(service, client.getClientId()));
    // -> 触发 removePublisherIndexes -> ServiceChangedEvent -> Push(订阅者看到实例被删)
    NotifyCenter.publishEvent(new InstanceMetadataEvent(service, metadataId, true));  // 元数据移除
    NotifyCenter.publishEvent(new DeregisterInstanceTraceEvent(..., HEARTBEAT_EXPIRE));
}
```

**时间线**:
```
T0:     客户端停止心跳(或连接断开但未被连接层检测到)
T15s:   UnhealthyInstanceChecker -> healthy=false -> ServiceChangedEvent -> Push(订阅者看到不健康)
T30s:   ExpiredInstanceChecker -> removeServiceInstance -> ClientDeregisterServiceEvent -> Push(订阅者看到实例删除)
```

### 4.5 1.x vs 2.x 健康检查对比

| 维度 | 1.x | 2.x |
|------|-----|-----|
| 心跳方式 | 客户端每 5s HTTP POST /beat | gRPC keep-alive 自动检测(连接层) |
| 服务端感知 | 定时扫描 lastBeat | 连接断开回调 + 定时补偿 |
| 故障检测延迟 | 最长 30s(IP_DELETE_TIMEOUT) | 毫秒级(连接断开) + 补偿周期 |
| 标记不健康 | 15s 无心跳 | 15s 无心跳(补偿, keep-alive 异常时兜底) |
| 删除实例 | 30s 无心跳 | 30s 无心跳(补偿) 或 连接断开即刻删(主机制) |

> 2.x 的定时补偿(UnhealthyInstanceChecker/ExpiredInstanceChecker)主要针对 IpPortBasedClient(1.x 兼容客户端)。2.x 原生的 ConnectionBasedClient 在连接断开时由 ConnectionManager 即刻清理,不需要等 15s/30s。

---

## 五、故障自愈 - 完整时间线

```
T0: 客户端进程 crash -> gRPC 连接断开
T1: gRPC server 检测到连接断开 -> ConnectionManager.unregister
T2: notifyClientDisConnected -> ClientManagerDelegate.clientDisconnected
    -> 发布 ClientReleaseEvent
T3: ClientServiceIndexesManager.handleClientDisconnect:
    -> 遍历 getAllPublishedService -> removePublisherIndexes
    -> 每个 service 发 ServiceChangedEvent(触发 Push)
    -> 发 DeregisterInstanceTraceEvent(追踪日志)
T4: DistroClientDataProcessor 监听到 ClientDisconnectEvent(ClientEvent,不是 ClientReleaseEvent)
    -> DistroClientSyncData(标记为 deleted)
    -> DistroProtocol.sync() -> 其他 Nacos 节点也清理(D-09)
T5: PushDelayTask 到期(500ms) -> RpcPushService.push -> 通知所有订阅者

总延迟: T0->T5 通常在毫秒到秒级(取决于 keep-alive 配置 + PushDelayTask 合并窗口)
```

**临时实例 vs 持久实例的故障处理差异**:
- **临时实例**(ConnectionBasedClient): 连接断开 -> ClientReleaseEvent -> 全自动清理(索引 + 实例数据 + 集群同步 + 推送)
- **持久实例**(IpPortBasedClient with ephemeral=false): 连接断开 -> 仅标记不健康,数据保留,等待主动注销

---

## 六、设计哲学分析

### 6.1 PushDelayTask 的 debounce 设计

```
3 个实例连续注册(200ms 内):
  -> 3 个 ServiceChangedEvent
  -> 3 个 PushDelayTask 被 merge
  -> 延迟窗口到期 -> 执行 1 次 Push
```

不是"每条变更都 push"(推送风暴),不是"只 push 最后一条"(可能丢失中间状态,虽然 AP 系统可接受)。延迟合并 = 在"即时性"和"效率"之间取平衡。`lastProcessTime = min()` 保证第一次变更后 500ms 一定触发,不会被持续变更无限延迟。

### 6.2 连接即生命周期 + 定时补偿 - 双层保障

- 连接层 = 快速路径(毫秒级故障检测)
- 定时补偿 = 慢速兜底(keep-alive 配置异常、网络抖动等边缘情况)
- 类似 CPU 的 L1 Cache + L2 Cache: 大部分走快速路径,特殊情况走兜底

### 6.3 健康检查闭环

UnhealthyInstanceChecker 标记不健康 -> ServiceChangedEvent -> PushDelayTask -> Push -> 订阅者看到不健康实例。健康检查不是独立的"检查"系统,而是**复用推送体系**将状态变更传播给订阅者。这是一个优雅的设计闭环。

---

## 七、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|------|------|------|
| 1 | ServiceChangedEvent 直接 push, 不经 DelayTask 合并 | 3 个实例连续注册 -> 3 次 push -> 推送风暴 | PushDelayTask 500ms 延迟合并 |
| 2 | ServiceSubscribedEvent 也推送给所有订阅者 | 每次新客户端订阅 -> 向所有现有订阅者发无意义的全量 push | 只推送给新订阅的单个 clientId |
| 3 | 2.x 完全依赖连接检测, 不做定时补偿 | keep-alive 配置异常时连接断开未检测 -> 实例永远 healthy -> 路由黑洞 | ClientBeatCheckTaskV2 定时补偿 |
| 4 | 连接断开后等心跳超时(像 1.x) | 30s 路由黑洞 | 连接断开即刻清理 + 定时补偿兜底 |
| 5 | PushDelayTask 的 merge 窗口固定不变 | 高流量时合并不足(推送频繁), 低流量时延迟过长 | pushTaskDelay 可配置(PushConfig) |
| 6 | 推送失败不重试 | 网络抖动 -> 订阅者永久缺失更新 | ServicePushCallback.onFail -> 单客户端重试(1s) |
| 7 | 连接已关闭还重试推送 | 浪费资源推送到不存在的连接 | ConnectionAlreadyClosedException -> 视为成功(不重试) + 清理连接 |

---

## 八、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|------|------|------|
| 1 | 认为 push 是实时的 | 注册后客户端可能等 500ms 合并窗口才收到推送 | 生产环境 pushTaskDelay 可调小(如 200ms) |
| 2 | 混淆 ServiceChanged 和 ServiceSubscribed 推送逻辑 | 第一个广播给所有订阅者, 第二个只单播给新订阅者 | onEvent 中两者 PushDelayTask 构造不同 |
| 3 | keep-alive 配置不当导致故障检测太慢 | 客户端 crash 后服务端几十秒才感知 | ClientBeatCheckTaskV2 兜底(15s 标记不健康, 30s 删除) |
| 4 | 忽视健康检查的 Distro 负责检查 | 以为每个节点都检查所有 Client -> 重复检查 | InstanceBeatCheckResponsibleInterceptor 跳过非负责节点 |
| 5 | pushTaskDelay 设太小(如 50ms) | 合并窗口太短 -> 几乎每个变更单独 push -> 推送风暴 | 设为 500ms-2000ms(容忍合并 + 及时推送) |

---

## 九、面试 Q&A

### Q1: Push 推送是怎么实现的? PushDelayTask 合并机制的作用?

**面试官意图**: 考察推送优化策略。

触发: ServiceChangedEvent -> NamingSubscriberServiceV2Impl.onEvent -> PushDelayTask(500ms)。合并: 同一个 Service 的多次变更 -> merge() -> "push to all" 胜出, 否则客户端集合取并集, lastProcessTime 取最早。执行: 500ms 到期 -> PushExecuteTask -> 遍历订阅者 -> RpcPushService.push(gRPC bi-di stream)。核心价值: 减少重复推送, 避免推送风暴。

**追问**: "pushTaskDelay=0 会有什么问题?"
- 每个注册都触发一次 push -> 1000 个实例批量启动 -> 1000 次 push, 每次构建完整 ServiceInfo。

### Q2: 2.x 基于连接的健康检查和 1.x 的心跳检查有什么本质区别?

1.x: 应用层心跳(HTTP POST, 5s 一次), 服务端定时扫描, 删除延迟 30s。2.x: 传输层检测(gRPC keep-alive, 零额外开销), 断开即删(毫秒级)。兜底: ClientBeatCheckTaskV2 定时补偿(15s 标记不健康, 30s 删除), 防 keep-alive 配置异常。

### Q3: 客户端 crash 后, 服务端如何感知并处理?

T0 crash -> T1 连接断开 -> T2 unregister -> T3 ClientReleaseEvent -> handleClientDisconnect(清理索引 + ServiceChangedEvent + 追踪日志) -> T4 Distro sync(其他节点也清理) -> T5 PushDelayTask(500ms) -> Push 通知订阅者。总延迟: 毫秒到秒级。

### Q4: PushDelayTask 的合并窗口应该设置多大?

**面试官意图**: 考察性能调优。

太小(<200ms): 合并效果差 -> 推送风暴。太大(>5000ms): 订阅者感知延迟长 -> 路由表过期。推荐: 500-2000ms。权衡: 推送频率 vs 感知延迟。

### Q5: UnhealthyInstanceChecker 和 ExpiredInstanceChecker 的分工?和 Push 有什么关系?

UnhealthyInstanceChecker: 15s 无心跳 -> healthy=false -> 发 ServiceChangedEvent -> **触发 Push**(订阅者看到不健康)。ExpiredInstanceChecker: 30s 无心跳 -> 删除实例 -> ClientDeregisterServiceEvent -> ServiceChangedEvent -> **触发 Push**(订阅者看到实例删除)。健康检查不是独立的,而是**复用推送体系**传播状态变更。

---

## 十、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| NamingSubscriberServiceV2Impl | Push 入口, 订阅 ServiceChangedEvent, 创建 PushDelayTask | `push/v2/NamingSubscriberServiceV2Impl.java` |
| PushDelayTask | 延迟合并任务, 500ms debounce, merge 时"push to all"胜出 | `push/v2/task/PushDelayTask.java` |
| NacosDelayTaskExecuteEngine | 延迟任务引擎, 100ms 扫描, shouldProcess 判断到期 | `common/task/engine/NacosDelayTaskExecuteEngine.java` |
| PushExecutorDelegate | gRPC(2.x) vs UDP(1.x) 选择, 按 clientId 含 `#` 判断 | `push/v2/executor/PushExecutorDelegate.java` |
| ClientBeatCheckTaskV2 | 定时健康检查, 拦截器链(Distro负责->服务开关->实例开关) | `healthcheck/heartbeat/ClientBeatCheckTaskV2.java` |
| UnhealthyInstanceChecker | 15s 无心跳标记不健康 + 发 ServiceChangedEvent(闭环) | `healthcheck/heartbeat/UnhealthyInstanceChecker.java` |
| ExpiredInstanceChecker | 30s 无心跳删除实例 + 发 ClientDeregisterServiceEvent | `healthcheck/heartbeat/ExpiredInstanceChecker.java` |
| pushTaskDelay | 推送延迟合并窗口 = 500ms | `PushConstants.DEFAULT_PUSH_TASK_DELAY` |
