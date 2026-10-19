# D-11: 配置发布/查询/存储 + 变更通知

> 前置依赖: D-04（Config SDK + ClientWorker gRPC 监听）、D-05（gRPC 连接模型）
> 后续: D-12（AsyncNotifyService + 集群配置同步）

---

## 一、概述

D-04 覆盖了客户端侧的配置监听机制(executeConfigListen + ConfigChangeNotifyRequest)。这篇追踪**服务端侧**:配置发布如何写入 DB、查询如何从内存缓存读取、配置变更如何通知客户端。

核心链路: 发布(gRPC -> DB -> 事件) -> 存储(DB + 内存缓存) -> 变更通知(事件 -> gRPC 推送)

---

## 二、配置发布 - ConfigPublishRequestHandler

### 2.1 发布流程

```java
// ConfigPublishRequestHandler.java
@TpsControl(pointName = "ConfigPublish")
@Secured(action = ActionTypes.WRITE, signType = SignType.CONFIG)
public ConfigPublishResponse handle(ConfigPublishRequest request, RequestMeta meta) {
    // 1. 参数提取 + 校验
    String dataId = request.getDataId();
    String group = request.getGroup();
    String content = request.getContent();
    String casMd5 = request.getCasMd5();  // CAS 乐观锁

    // 2. 构建 ConfigInfo
    ConfigInfo configInfo = new ConfigInfo(dataId, group, tenant, appName, content);
    configInfo.setMd5(casMd5);

    // 3. 持久化到 DB
    if (StringUtils.isNotBlank(casMd5)) {
        // CAS: 只有当前 MD5 == casMd5 才写入
        ConfigOperateResult result = configInfoPersistService.insertOrUpdateCas(srcIp, srcUser, configInfo, ...);
        if (!result.isSuccess()) {
            return ConfigPublishResponse.buildFailResponse("Cas publish fail, server md5 may have changed.");
        }
    } else {
        // 普通: 无条件写入(MD5 幂等在 PersistService 层)
        configInfoPersistService.insertOrUpdate(srcIp, srcUser, configInfo, ...);
    }

    // 4. 发布变更事件
    ConfigChangePublisher.notifyConfigChange(
        new ConfigDataChangeEvent(false, dataId, group, tenant, lastModified));

    return ConfigPublishResponse.buildSuccessResponse();
}
```

### 2.2 三种发布模式

| 模式 | 条件 | PersistService 方法 | CAS |
|------|------|-------------------|-----|
| 普通发布 | 无 betaIps, 无 tag | `insertOrUpdate` | 否 |
| CAS 发布 | casMd5 非空 | `insertOrUpdateCas` | 是(失败返回 fail) |
| Tag 发布 | tag 非空 | `insertOrUpdateTag` / `insertOrUpdateTagCas` | 可选 |
| Beta 发布 | betaIps 非空 | `insertOrUpdateBeta` / `insertOrUpdateBetaCas` | 可选 |

### 2.3 MD5 幂等 + CAS

**MD5 幂等**(在 `ConfigInfoPersistService.insertOrUpdate` 层):
1. 计算 content 的 MD5
2. 与 DB 中已有记录的 MD5 对比
3. **相同** -> 直接返回(不写 DB, 不触发通知, 零开销)
4. **不同** -> 写入 DB -> 返回 lastModified

**CAS 乐观锁**(`insertOrUpdateCas`):
1. 传入 casMd5(期望的当前 MD5)
2. 只有 DB 中当前 MD5 == casMd5 才允许写入
3. 不匹配 -> 返回 `success=false` -> 客户端需重新 get -> merge -> 再 CAS

---

## 三、配置查询 - ConfigQueryRequestHandler

### 3.1 查询流程

```java
// ConfigQueryRequestHandler.java
@TpsControl(pointName = "ConfigQuery")
@Secured(action = ActionTypes.READ, signType = SignType.CONFIG)
public ConfigQueryResponse handle(ConfigQueryRequest request, RequestMeta meta) {
    return getContext(request, meta, request.isNotify());
}

private ConfigQueryResponse getContext(...) {
    // 1. 获取读锁(防止 DumpService 更新缓存时读到不一致数据)
    int lockResult = tryConfigReadLock(groupKey);

    // 2. 从内存缓存读取(不是直接查 DB!)
    CacheItem cacheItem = ConfigCacheService.getContentCache(groupKey);
    // cacheItem 中包含: content, md5, lastModified, type, betaIps...

    // 3. Beta/Tag 过滤
    if (cacheItem.isBeta() && cacheItem.getIps4Beta().contains(clientIp)) {
        // 返回 beta 配置
    }

    // 4. 构建响应
    response.setContent(content);
    response.setMd5(md5);
    response.setLastModified(lastModified);
    return response;
}
```

**关键**: 查询从**内存缓存**(`ConfigCacheService.getContentCache`)读取,不直接查 DB。内存缓存由 `DumpService` 从 DB 加载并维护。

### 3.2 读写锁

`tryConfigReadLock(groupKey)`: 使用 groupKey 级别的读写锁。DumpService 更新缓存时持写锁,查询持读锁。防止读到半更新的缓存数据。

---

## 四、存储设计 - DB + 内存缓存

### 4.1 双层存储

```
DB (Derby/MySQL)                    内存缓存 (ConfigCacheService)
  ├── config_info 表                    ├── CacheItem (per groupKey)
  │   (dataId, group, tenant,           │   ├── content
  │    content, md5, type,              │   ├── md5
  │    lastModified, ...)               │   ├── lastModified
  │                                    │   ├── type
  └── config_info_tag / beta 表         │   └── betaIps
  └── 通过 ConfigInfoPersistService     └── 通过 DumpService 从 DB 加载
```

**为什么需要内存缓存?**
- 配置查询频率远高于发布频率(客户端每次 executeConfigListen 都要查)
- DB 查询有 IO 开销,内存缓存 O(1)
- 缓存一致性: DumpService 在配置变更时更新缓存 -> 发布 LocalDataChangeEvent

### 4.2 DumpService - 缓存管理

```
启动时: DumpService 全量加载 DB -> 内存缓存
配置变更: ConfigDataChangeEvent -> AsyncNotifyService(订阅者)
  -> 处理自己时: dumpService.dumpFormal(dataId, group, tenant)
    -> 从 DB 读取最新配置 -> ConfigCacheService 更新 CacheItem
    -> ConfigCacheService 发布 LocalDataChangeEvent
  -> 处理其他节点: gRPC 通知(D-12) -> 目标节点 dumpService.dump -> 同样发 LocalDataChangeEvent
```

> **注意**: DumpService **不直接订阅** ConfigDataChangeEvent。本地缓存更新由 AsyncNotifyService 处理自己时触发。`LocalDataChangeEvent` 由 `ConfigCacheService` 发布(不是 DumpService),是连接缓存更新和推送系统的桥梁。

---

## 五、配置变更通知 - 完整链路

### 5.1 事件传播链

```
ConfigPublishRequestHandler.handle()
  -> configInfoPersistService.insertOrUpdate()         // 写 DB
  -> ConfigChangePublisher.notifyConfigChange(
       new ConfigDataChangeEvent(dataId, group, tenant, lastModified))

  [ConfigDataChangeEvent 消费者: AsyncNotifyService]    // 唯一直接消费者!
  -> 遍历 allMembers(含自己, D-12)
    -> 自己: dumpService.dumpFormal -> ConfigCacheService 更新 CacheItem
       -> ConfigCacheService 发布 LocalDataChangeEvent
    -> 其他节点: gRPC ConfigChangeClusterSyncRequest
       -> 目标节点 ConfigChangeClusterSyncRequestHandler -> dumpService.dump
       -> ConfigCacheService 发布 LocalDataChangeEvent

  [LocalDataChangeEvent 消费者 1: RpcConfigChangeNotifier]  // 2.x 客户端推送
  -> configDataChanged(groupKey, dataId, group, tenant)
    -> configChangeListenContext.getListeners(groupKey) // 获取监听该配置的客户端
    -> 对每个客户端:
       -> 构建 ConfigChangeNotifyRequest.build(dataId, group, tenant)
       -> rpcPushService.pushWithCallback(connectionId, notifyRequest, callback)
         -> connection.asyncRequest (gRPC bi-di stream, D-05)

  [LocalDataChangeEvent 消费者 2: LongPollingService]       // 1.x 客户端通知
  -> 唤醒 hold 的 HTTP 长轮询连接
```

> **关键**: ConfigDataChangeEvent 和 LocalDataChangeEvent 是**两级事件**。ConfigDataChangeEvent 的消费者只有 AsyncNotifyService;LocalDataChangeEvent(由 ConfigCacheService 发布)的消费者是 RpcConfigChangeNotifier + LongPollingService。不要混淆这两级。

### 5.2 RpcConfigChangeNotifier - 2.x 推送

```java
// RpcConfigChangeNotifier.java
public class RpcConfigChangeNotifier extends Subscriber<LocalDataChangeEvent> {

    public void configDataChanged(String groupKey, String dataId, String group, String tenant,
            boolean isBeta, List<String> betaIps, String tag) {
        Set<String> listeners = configChangeListenContext.getListeners(groupKey);
        if (CollectionUtils.isEmpty(listeners)) return;

        for (String client : listeners) {
            Connection connection = connectionManager.getConnection(client);
            if (connection == null) continue;

            // Beta/Tag 过滤
            if (isBeta && !betaIps.contains(clientIp)) continue;
            if (StringUtils.isNotBlank(tag) && !tag.equals(clientTag)) continue;

            ConfigChangeNotifyRequest notifyRequest = ConfigChangeNotifyRequest.build(dataId, group, tenant);
            RpcPushTask task = new RpcPushTask(notifyRequest, maxPushRetryTimes, client, clientIp, appName);
            push(task);  // 推送 + 重试
        }
    }
}
```

### 5.3 推送重试机制

```java
// RpcPushTask - 推送重试
private void push(RpcPushTask retryTask) {
    if (retryTask.isOverTimes()) {
        // 超过最大重试次数 -> 踢连接(客户端可能已死)
        connectionManager.unregister(retryTask.connectionId);
    } else if (connectionManager.getConnection(retryTask.connectionId) != null) {
        // 延迟重试: 第1次 0s, 第2次 2s, 第3次 4s...
        ConfigExecutor.schedule(retryTask, retryTask.tryTimes * 2, SECONDS);
    }
    // 连接已不存在 -> 忽略
}
```

| 重试次数 | 延迟 | 超过 maxPushRetryTimes |
|---------|------|----------------------|
| 第1次 | 0s(立即) | - |
| 第2次 | 2s | - |
| 第3次 | 4s | - |
| 超限 | - | `connectionManager.unregister` (踢连接) |

---

## 六、ConfigChangeListenContext - 服务端监听关系

### 6.1 双重索引

```java
// ConfigChangeListenContext.java
@Component
public class ConfigChangeListenContext {
    // 索引1: groupKey -> Set<connectionId> (哪些客户端监听这个配置)
    ConcurrentHashMap<String, HashSet<String>> groupKeyContext;
    // 索引2: connectionId -> Map<groupKey, md5> (这个客户端监听哪些配置 + MD5)
    ConcurrentHashMap<String, HashMap<String, String>> connectionIdContext;
}
```

### 6.2 与客户端的配合

```
客户端 ConfigBatchListenRequest(setListen=true)
  -> 服务端 ConfigBatchListenRequestHandler
    -> configChangeListenContext.addListen(groupKey, md5, connectionId)
       -> groupKeyContext[groupKey].add(connectionId)
       -> connectionIdContext[connectionId].put(groupKey, md5)

服务端比对 MD5:
  -> 遍历 connectionIdContext[connectionId] 的每个 (groupKey, clientMd5)
  -> 与 CacheItem 的 md5 比较
  -> 不同 -> 加入 changedConfigs 列表 -> 返回 ConfigChangeBatchListenResponse

配置变更推送:
  -> configChangeListenContext.getListeners(groupKey) -> Set<connectionId>
  -> 对每个 connectionId 推送 ConfigChangeNotifyRequest
```

### 6.3 连接断开清理

客户端断开时, `ConfigConnectionEventListener` 处理:
- 遍历 `connectionIdContext[connectionId]` 的所有 groupKey
- 从 `groupKeyContext[groupKey]` 中移除该 connectionId
- 清理 `connectionIdContext[connectionId]`

---

## 七、1.x 长轮询(向后兼容)

服务端保留 `LongPollingService` 仅为兼容 1.x 客户端:

```
1.x 客户端: POST /v1/cs/configs/listener (Long-Pulling-Timeout: 30000)
  -> 服务端 LongPollingService
    -> 比对 MD5 -> 如果已变更立即返回
    -> 如果未变更 -> hold 连接最长 29.5s
    -> 配置变更(ConfigDataChangeEvent) -> 唤醒 hold 的连接 -> 返回变更的 dataId

2.x 客户端: gRPC ConfigBatchListenRequest + ConfigChangeNotifyRequest(本篇)
```

**1.x 和 2.x 的关系**:
- 同一个 **LocalDataChangeEvent** 同时触发 LongPollingService(1.x)和 RpcConfigChangeNotifier(2.x)
- 1.x: hold 连接 -> 唤醒 -> 返回 dataId
- 2.x: 主动推送 ConfigChangeNotifyRequest -> 客户端再拉取
- LocalDataChangeEvent 由 ConfigCacheService 发布(配置变更 -> AsyncNotifyService -> DumpService -> ConfigCacheService -> LocalDataChangeEvent)

---

## 八、设计哲学分析

### 8.1 为什么 Config 用 DB 而 Naming 用内存?

- Config 数据是"静态"的(低频变更, 需要持久化) -> DB 提供持久化 + 共享
- Naming 临时实例数据是"流式"的(高频变更, 可丢失) -> 内存足够快, 重启后客户端重注册
- 如果 Config 存内存 -> 重启丢失 -> 不可接受
- 如果 Naming 存 DB -> 每次注册/注销 SQL 写 -> 性能灾难

### 8.2 为什么查询走内存缓存而不是直接查 DB?

- 配置查询频率高(executeConfigListen 每 5s + 每次推送后拉取)
- DB 查询有 IO 开销
- 内存缓存由 DumpService 维护一致性(配置变更时更新)
- 读写锁防止读到半更新数据

### 8.3 "通知+拉取"两步模型

配置变更通知只推 `ConfigChangeNotifyRequest`(dataId + group + tenant, 几十字节),客户端收到后再发 `ConfigQueryRequest` 拉取完整内容。为什么不直接推内容?
- 配置可能几十 KB 到 MB -> 直接推浪费带宽
- 客户端可以批量合并拉取(executeConfigListen)
- 拉取失败可以独立降级到 snapshot(D-04)

---

## 九、反面假设分析

| # | 假设 | 后果 | Nacos 的实际选择 |
|---|------|------|------|
| 1 | 查询直接查 DB | 高频查询 -> DB 压力大 | 内存缓存(ConfigCacheService) + 读写锁 |
| 2 | 发布不做 MD5 幂等 | 相同内容重复发布 -> 无意义 DB 写入 + 推送 | PersistService 层 MD5 对比, 相同则跳过 |
| 3 | 不提供 CAS | 多人同时修改 -> 后写覆盖先写 | insertOrUpdateCas(casMd5 匹配才写入) |
| 4 | 配置变更直接推完整内容 | 100KB 配置 x 1000 客户端 -> 100MB 带宽 | 通知(dataId) + 按需拉取 |
| 5 | 推送失败不重试 | 网络抖动 -> 客户端永久缺失更新 | RpcPushTask 重试(0s/2s/4s...) |
| 6 | 推送超限不踢连接 | 死连接永远占用推送资源 | 超过 maxPushRetryTimes -> unregister |
| 7 | 不维护 ConfigChangeListenContext | 不知道该推给谁 | groupKey -> Set<connectionId> 双重索引 |

---

## 十、新手最可能犯的 5 个错误

| # | 错误 | 后果 | Nacos 对策 |
|---|------|------|------|
| 1 | 集群用 Derby(各自独立 DB) | 节点 A 发布的配置在节点 B 查不到 | 集群用 MySQL 共享存储 |
| 2 | 认为推送 = 客户端已获取最新配置 | 推送只是通知, 客户端还要拉取 | 通知 + 拉取两步 |
| 3 | CAS 失败不重试 | 配置更新丢失 | 重新 get -> merge -> 再 CAS |
| 4 | 以为查询走 DB | 查询走内存缓存, DB 故障时缓存仍有数据 | DumpService 维护缓存 |
| 5 | 忽视 Beta/Tag 过滤 | Beta 配置推给了非 Beta 客户端 | RpcConfigChangeNotifier 检查 betaIps/tag |

---

## 十一、面试 Q&A

### Q1: 配置发布到客户端感知, 完整路径?

ConfigPublishRequest(gRPC) -> ConfigPublishRequestHandler -> insertOrUpdate(DB) -> ConfigDataChangeEvent -> AsyncNotifyService(处理自己) -> DumpService 更新缓存 -> ConfigCacheService 发 LocalDataChangeEvent -> RpcConfigChangeNotifier -> ConfigChangeNotifyRequest(gRPC 推送) -> 客户端标记 isConsistentWithServer=false -> executeConfigListen -> ConfigBatchListenRequest -> 服务端比对 MD5 -> 返回变更 dataId -> 客户端 ConfigQueryRequest 拉取内容。

### Q2: 配置中心的 MD5 幂等和 CAS 有什么区别?

MD5 幂等: 相同内容重复发布 -> 不写 DB, 零开销。CAS: 传入 casMd5 -> 只有当前 MD5 匹配才写入 -> 防止并发覆盖。幂等是"相同内容不重复写", CAS 是"检测并发冲突"。

### Q3: 配置查询走 DB 还是内存?

内存缓存(ConfigCacheService.getContentCache)。DumpService 从 DB 加载到内存, 配置变更时更新缓存。查询时用读写锁防止读到半更新数据。DB 故障时内存缓存仍有数据, 可短暂服务。

### Q4: 2.x 配置变更通知和 1.x 长轮询有什么区别?

**面试官意图**: 考察架构演进。

1.x: HTTP 长轮询(POST /v1/cs/configs/listener, 服务端 hold 30s)。2.x: gRPC 服务端推送(ConfigChangeNotifyRequest, bi-di stream) + 5s 周期 ConfigBatchListenRequest 比对 MD5 兜底。服务端保留 LongPollingService 仅为兼容 1.x。

### Q5: 集群全故障后客户端能启动吗?

可以 -- 只要有 snapshot 缓存(D-04 四级降级 L3), getConfig 返回上次缓存的配置。如果首次启动无 snapshot, 返回 null -> 应用用硬编码默认值启动。DB 恢复后 DumpService 重新加载全量配置到内存。

---

## 十二、核心概念速查

| 概念 | 一句话解释 | 关键源码 |
|------|-----------|---------|
| ConfigPublishRequestHandler | 配置发布入口, 写 DB + 发 ConfigDataChangeEvent | `remote/ConfigPublishRequestHandler.java` |
| ConfigQueryRequestHandler | 配置查询入口, 从内存缓存读取 | `remote/ConfigQueryRequestHandler.java` |
| ConfigInfoPersistService | DB 持久化(Derby/MySQL), 含 MD5 幂等 + CAS | `service/repository/ConfigInfoPersistService.java` |
| ConfigCacheService | 内存缓存(per groupKey CacheItem), 读写锁保护 | `service/ConfigCacheService.java` |
| DumpService | DB -> 内存缓存加载, 配置变更时更新缓存 | `service/dump/DumpService.java` |
| ConfigDataChangeEvent | 配置变更事件(发布触发) | `model/event/ConfigDataChangeEvent.java` |
| LocalDataChangeEvent | 本地数据变更事件(DumpService 更新缓存后发) | `model/event/LocalDataChangeEvent.java` |
| RpcConfigChangeNotifier | 2.x 推送, 订阅 LocalDataChangeEvent, 推 ConfigChangeNotifyRequest | `remote/RpcConfigChangeNotifier.java` |
| ConfigChangeListenContext | 服务端监听关系: groupKey <-> connectionId 双重索引 | `remote/ConfigChangeListenContext.java` |
| LongPollingService | 1.x 长轮询(hold 30s), 仅兼容 1.x 客户端 | `service/LongPollingService.java` |

---

## 八、测试分析

### 8.1 ConfigPublishRequestHandlerTest

`config/src/test/java/com/alibaba/nacos/config/server/remote/ConfigPublishRequestHandlerTest.java`

| 测试内容 | 验证点 |
|---------|--------|
| 发布成功 | 正常发布 -> 返回成功响应 |
| 参数校验 | 缺少 dataId/group/content -> 返回错误响应 |
| CAS | 传入 casMd5 且不匹配 -> 返回失败 |

### 8.2 ConfigQueryRequestHandlerTest

`config/src/test/java/com/alibaba/nacos/config/server/remote/ConfigQueryRequestHandlerTest.java`

| 测试内容 | 验证点 |
|---------|--------|
| 查询已发布配置 | 返回最新内容 |
| 查询不存在配置 | 返回 null/空内容 |
| 查询携带 tag | tag 过滤配置 |

### 8.3 API DTO 测试

`api/src/test/.../remote/request/ConfigPublishRequestTest.java` + `ConfigQueryRequestTest.java`

测试序列化/反序列化: `ConfigPublishRequest` 发出 -> JSON -> 接收方能正确还原

## 九、设计模式总结

| 模式 | 应用位置 | 说明 |
|------|---------|------|
| **Command** | `ConfigPublishRequest` / `ConfigQueryRequest` | 请求封装为命令对象，通过 gRPC 传输 |
| **Observer** | `LocalDataChangeEvent` -> `RpcConfigChangeNotifier` | 配置变更事件驱动推送 |
| **Factory** | `DumpService` | 根据不同配置类型选择不同 dump 策略 |
| **Cache-Aside** | 服务端 `DumpService` dump 配置到磁盘 | 内存缓存 + 磁盘持久化 |
| **Change-Data-Capture** | `RpcConfigChangeNotifier` 追踪变更 | 查询 `ConfigChangeListenContext` 获取要推送的客户端 |

## 十、本 Lab 中的实际映射

| Lab 场景 | 对应源码 |
|---------|---------|
| A-02 `GET /config/current` | `ConfigQueryRequest` -> `ConfigQueryRequestHandler` -> `ConfigService.getConfig()` |
| B-02 配置发布 | `ConfigPublishRequest` -> `ConfigPublishRequestHandler` -> `ConfigService.publishConfig()` |
| B-02 配置刷新 | `ConfigChangeNotifyRequest` -> 客户端 `ClientWorker` 收到通知 -> 拉取最新内容 |
