# D-12: AsyncNotifyService + 集群配置同步

> 前置依赖: D-11（配置发布/查询/存储 + 变更通知）
> 后续: D-13（设计全景 + 集群管理）

---

## 一、概述

D-11 覆盖了单节点内的配置发布/查询/变更通知。但 Nacos 配置中心也是集群部署的--一个节点发布了配置,其他节点如何感知? AsyncNotifyService 负责跨节点通知, `ConfigClusterRpcClientProxy` 负责实际的 gRPC 通信。

---

## 二、集群配置同步的完整链路

```
[节点 A 发布配置](D-11):
ConfigPublishRequestHandler.handle()
  -> configInfoPersistService.insertOrUpdate()     // 写入本节点 DB
  -> ConfigChangePublisher.notifyConfigChange(ConfigDataChangeEvent)

  [ConfigDataChangeEvent 消费者: AsyncNotifyService]   // 唯一直接消费者!
  -> 遍历 allMembers()(含自己)
    -> 自己: dumpService.dumpFormal(本地缓存更新)
      -> ConfigCacheService 更新 CacheItem -> 发布 LocalDataChangeEvent
        -> RpcConfigChangeNotifier -> 推送 2.x 客户端
        -> LongPollingService -> 唤醒 1.x HTTP 连接
    -> 其他成员: configClusterRpcClientProxy.syncConfigChange(member, request)

[节点 B 收到通知]:
ConfigChangeClusterSyncRequestHandler.handle()
  -> DumpService.dump(dataId, group, tenant, lastModifiedTs)
    -> 从 DB 读取最新配置(共享 MySQL) -> 更新 CacheItem
    -> LocalDataChangeEvent -> RpcConfigChangeNotifier -> 推送本节点的 2.x 客户端
```

---

## 三、AsyncNotifyService - 集群通知核心

### 3.1 事件订阅

```java
// AsyncNotifyService.java
@Service
public class AsyncNotifyService {

    // 重试参数
    private static final int MIN_RETRY_INTERVAL = 500;   // 基础间隔
    private static final int INCREASE_STEPS = 1000;       // 增量步长
    private static final int MAX_COUNT = 6;               // failCount 递增上限(到 7 后停止递增,不停止重试)

    public AsyncNotifyService(ServerMemberManager memberManager) {
        // 订阅 ConfigDataChangeEvent
        NotifyCenter.registerSubscriber(new Subscriber() {
            public void onEvent(Event event) {
                if (event instanceof ConfigDataChangeEvent) {
                    ConfigDataChangeEvent evt = (ConfigDataChangeEvent) event;
                    Collection<Member> ipList = memberManager.allMembers();  // 所有成员(含自己!)

                    Queue<NotifySingleRpcTask> rpcQueue = new LinkedList<>();
                    for (Member member : ipList) {
                        rpcQueue.add(new NotifySingleRpcTask(dataId, group, tenant, tag, dumpTs, isBeta, isBatch, member));
                    }
                    ConfigExecutor.executeAsyncNotify(new AsyncRpcTask(rpcQueue));
                }
            }
            public Class<? extends Event> subscribeType() { return ConfigDataChangeEvent.class; }
        });
    }
}
```

**关键**: 遍历 `allMembers()`(含自己)。自己 -> 直接 `dumpService.dump`; 其他成员 -> gRPC 通知。

### 3.2 AsyncRpcTask - 通知执行

```java
class AsyncRpcTask implements Runnable {
    public void run() {
        while (!queue.isEmpty()) {
            NotifySingleRpcTask task = queue.poll();
            Member member = task.member;

            if (memberManager.getSelf().equals(member)) {
                // 自己: 直接更新本地缓存
                dumpService.dumpFormal(dataId, group, tenant, lastModified, localIP);
                continue;
            }

            if (memberManager.hasMember(member.getAddress())) {
                if (isUnHealthy(member)) {
                    // 不健康: 跳过, 调度重试
                    asyncTaskExecute(task);
                } else {
                    // 健康: gRPC 通知
                    configClusterRpcClientProxy.syncConfigChange(member, syncRequest, new AsyncRpcNotifyCallBack(task));
                }
            }
        }
    }
}
```

### 3.3 重试机制 - 二次方退避

```java
private static int getDelayTime(NotifyTask task) {
    int failCount = task.getFailCount();
    int delay = MIN_RETRY_INTERVAL + failCount * failCount * INCREASE_STEPS;
    //  delay = 500 + failCount² * 1000
    if (failCount <= MAX_COUNT) {
        task.setFailCount(failCount + 1);
    }
    return delay;
}
```

| 重试次数 | failCount | 延迟计算 | 延迟 |
|---------|-----------|---------|------|
| 第1次 | 0 | 500 + 0 = 500 | 500ms |
| 第2次 | 1 | 500 + 1000 = 1500 | 1.5s |
| 第3次 | 2 | 500 + 4000 = 4500 | 4.5s |
| 第4次 | 3 | 500 + 9000 = 9500 | 9.5s |
| 第5次 | 4 | 500 + 16000 = 16500 | 16.5s |
| 第6次 | 5 | 500 + 25000 = 25500 | 25.5s |
| 第7次 | 6 | 500 + 36000 = 36500 | 36.5s |

前 7 次累计: 500 + 1500 + 4500 + 9500 + 16500 + 25500 + 36500 = **~94.5s**
之后 failCount 停止递增(稳定在 7),延迟 = 500 + 49*1000 = **49.5s** 重复重试,直到成功或成员移除

**二次方退避**: `failCount²` 增长 -> 后期间隔越来越长, 避免频繁重试冲击不可达节点。

### 3.4 回调处理

```java
class AsyncRpcNotifyCallBack implements RequestCallBack<ConfigChangeClusterSyncResponse> {
    public long getTimeout() { return 1000L; }  // 超时 1s

    public void onResponse(ConfigChangeClusterSyncResponse response) {
        if (response.isSuccess()) {
            // 通知成功 -> 记录追踪日志
        } else {
            // 通知失败 -> 重试
            asyncTaskExecute(task);
        }
    }

    public void onException(Throwable ex) {
        // 异常 -> 重试
        asyncTaskExecute(task);
    }
}
```

---

## 四、Config vs Naming 集群同步对比

| 维度 | Naming (Distro, D-09) | Config (AsyncNotify) |
|------|----------------------|---------------------|
| 同步内容 | Client 数据(publishers + revision, 不含 subscribers) | 通知(仅 dataId + group + tenant + ts) |
| 同步方式 | Push 数据 -> 目标节点存储 | 通知目标节点 -> 目标节点自己从 DB 加载 |
| 延迟 | 毫秒级(直接内存合并) | 取决于 DB 查询 + 网络 |
| 一致性 | AP(最终一致) | AP(通知驱动拉取) |
| 失败处理 | verify 反熵补偿(5s) | 无限重试(二次方退避至 49.5s, 直到成功或成员移除) |
| 数据源 | 内存(无 DB) | DB(MySQL 共享) |
| 目标 | allMembersWithoutSelf(不含自己) | allMembers(含自己, 自己直接 dump) |

**为什么 Config 用"通知+拉取"而不是 Push 数据?**
- Config 的数据源是 DB -> 通知目标节点"变了", 目标节点自己从 DB 读取
- 如果 Push 数据 -> 集群间传输完整配置内容 -> 带宽浪费
- DB 作为共享权威源 -> 不需要像 Naming 那样维护内存数据的一致性

---

## 五、接收侧 - ConfigChangeClusterSyncRequestHandler

```
节点 B 收到 ConfigChangeClusterSyncRequest(gRPC)
  -> ConfigChangeClusterSyncRequestHandler.handle()
    -> DumpService.dump(dataId, group, tenant, lastModifiedTs)
      -> 从 DB 读取最新配置(MySQL 共享)
      -> 更新 CacheItem(内存缓存)
      -> LocalDataChangeEvent
        -> RpcConfigChangeNotifier -> 推送本节点的 2.x 客户端
```

**关键**: 接收侧从**共享 DB** 读取最新配置, 不是从发送方接收数据。这就是"通知+拉取"模式--发送方只通知"哪个 dataId 变了", 接收方自己从 DB 拉取。

---

## 六、设计哲学分析

### 6.1 DB 作为共享权威源

```
节点 A 发布配置 -> 写入 DB -> 通知节点 B
节点 B 收到通知 -> 从 DB 读取 -> 更新内存 dump
```

- DB 是"真理来源" -> 不需要内存数据在节点间同步
- 即使通知丢失(AsyncNotifyService 重试 7 次都失败), 节点 B 可以通过定时 DumpService 扫描发现差异
- 相比 Naming 的临时实例数据在内存 -> 必须通过 Distro sync 保证不丢

### 6.2 渐进式重试 - 不阻塞发布流程

- 重试间隔(二次方退避): 500ms -> 1.5s -> 4.5s -> 9.5s -> 16.5s -> 25.5s -> 36.5s
- 前 7 次累计 ~94.5s, 之后 49.5s 间隔无限重试
- 失败后不阻塞发布流程 -> 发布已经成功(DB 已写入)
- "尽力而为"的通知 -> 目标节点可能延迟收到, 但最终会通过 DB 扫描感知

---

## 七、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|------|------|------|
| 1 | Config 用 Push 数据同步(像 Distro) | 集群间传输完整配置 -> 带宽浪费 | 通知 + 拉取: 目标节点从 DB 自取 |
| 2 | 不做重试 | 一次网络故障 -> 配置不一致 -> 目标节点下次 DumpService 扫描才感知 | 无限重试 + 二次方退避 |
| 3 | 通知成功后才返回 publish 成功 | 集群所有节点确认 -> 发布延迟 -> AP 不适用 | 异步通知: publish 先成功, 通知后台进行 |
| 4 | 所有节点用各自独立的 Derby | 节点 A 发布的配置在节点 B 读不到 | 集群用 MySQL 共享存储 |
| 5 | 不做健康检查, 直接通知不健康节点 | 不可达节点 -> 立即失败 -> 频繁重试 | isUnHealthy 检查 -> 跳过 + 延迟重试 |

---

## 八、新手最可能犯的 4 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|------|------|------|
| 1 | 集群用 Derby(各自独立 DB) | 节点 A 发布的配置写入自己的 Derby -> 节点 B 从自己的 Derby 读 -> 永远读不到 | 集群用 MySQL 共享存储 |
| 2 | 认为 AsyncNotifyService 通知失败 = 发布失败 | Notify 失败 不等于 发布失败: 配置已在 DB 持久化 | 发布成功只校验本地 DB 写入 |
| 3 | 修改配置后立即在另一个节点 getConfig 期望读到新值 | Notify 异步 -> 有延迟 -> 另一个节点可能还没 dump | 等待 Notify 完成(通常 <1s) |
| 4 | 不配置集群成员健康检查 | 网络波动时 SUSPICIOUS 状态的节点不通知 -> 延迟更大 | 默认 SUSPICIOUS 也算健康(可调) |

---

## 九、面试 Q&A

### Q1: 配置变更如何通知集群中所有节点?

ConfigDataChangeEvent -> AsyncNotifyService.onEvent -> allMembers(含自己) -> 自己: dumpService.dump; 其他: configClusterRpcClientProxy.syncConfigChange(gRPC) -> 目标节点 DumpService.dump(从 DB 加载) -> LocalDataChangeEvent -> RpcConfigChangeNotifier 推送。失败重试: 二次方退避(500ms -> 1.5s -> 4.5s...), **无限重试**(failCount 到 7 后延迟稳定 49.5s), 直到成功或成员移除。

### Q2: Config 集群同步和 Naming Distro 同步有什么本质区别?

Naming: Push 数据(内存级), 频率高, 需要反熵补偿。Config: 通知 + 拉取(DB 级), 频率低, 有 DB 作为权威源。根本原因: Config 数据有 DB, Naming 临时实例数据仅在内存。

### Q3: 集群间配置同步的可靠性?

第一层: AsyncNotifyService 二次方退避重试(前 7 次 ~94.5s, 之后 49.5s 间隔无限重试)。第二层: DumpService 定时扫描 DB -> 发现差异 -> 自动加载。第三层: 客户端 ConfigBatchListenRequest 每 5s 比对 MD5 -> 最终感知。综合: 最坏情况 ~94.5s + DB 扫描窗口内丢失更新。

### Q4: 集群中一个节点长时间离线后又恢复, 配置会丢吗?

不会丢。配置存储在 DB(MySQL)。离线期间: 其他节点发布的配置写入 DB。恢复后: DumpService 启动 -> 从 DB 加载全量配置 -> 补充离线期间的变更。

---

## 十、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| AsyncNotifyService | 集群通知核心, 订阅 ConfigDataChangeEvent, 通知所有成员 | `service/notify/AsyncNotifyService.java` |
| ConfigClusterRpcClientProxy | 集群间 gRPC 通信 | `remote/ConfigClusterRpcClientProxy.java` |
| ConfigChangeClusterSyncRequest | 集群间通知请求(dataId + group + tenant + ts) | `api/config/remote/request/cluster/` |
| ConfigChangeClusterSyncRequestHandler | 接收侧, 调 DumpService.dump 从 DB 加载 | `remote/ConfigChangeClusterSyncRequestHandler.java` |
| 二次方退避 | 500 + failCount² * 1000, 无限重试(failCount 到 7 后延迟稳定 49.5s) | `AsyncNotifyService.getDelayTime()` |
