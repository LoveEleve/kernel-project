# D-14: 面试模拟 18 题

> 前置依赖: D-01~D-13（全部）
> 目标: 读完这篇能通过注册中心开发岗位的面试

---

## 一、18 题总览

### 基础理解 (4 题)
| # | 题目 | 来源 |
|---|------|------|
| Q1 | Nacos 的整体架构是什么?包含哪些核心模块? | 全景 |
| Q2 | NamingService 有 53 个方法,核心设计理念是什么? | D-01 |
| Q3 | ConfigService API 为什么这么简洁(12 个方法)? | D-02 |
| Q4 | Instance 的 equals() 基于 toString(),有什么隐含陷阱? | D-01 |

### 客户端 SDK (3 题)
| # | 题目 | 来源 |
|---|------|------|
| Q5 | registerInstance 是同步还是异步的?失败后怎么处理? | D-03 |
| Q6 | redo 机制是怎么工作的? | D-03 |
| Q7 | getConfig 的四级降级分别对应什么场景? | D-04 |

### 服务端核心 (4 题)
| # | 题目 | 来源 |
|---|------|------|
| Q8 | 注册请求从 gRPC 到其他客户端感知,完整路径? | D-07 |
| Q9 | Nacos 2.x 为什么要把实例数据从 Service 移到 Client 上? | D-06 |
| Q10 | Push 推送是怎么实现的?PushDelayTask 合并机制? | D-08 |
| Q11 | 2.x 基于连接的健康检查和 1.x 心跳有什么本质区别? | D-08 |

### 数据一致性 (3 题)
| # | 题目 | 来源 |
|---|------|------|
| Q12 | Distro 协议的工作流程? | D-09 |
| Q13 | CAP 理论中,注册中心应该选 CP 还是 AP?Nacos 怎么做? | D-10 |
| Q14 | revision 校验能保证强一致性吗? | D-09 |

### 配置中心 (2 题)
| # | 题目 | 来源 |
|---|------|------|
| Q15 | 配置变更监听是怎么实现的?(2.x gRPC) | D-04/D-11 |
| Q16 | 配置变更如何通知集群中所有节点? | D-12 |

### 系统设计 + 故障排查 (2 题)
| # | 题目 | 来源 |
|---|------|------|
| Q17 | 如果让你从零设计百万级实例的注册中心? | 新增 |
| Q18 | 线上 Nacos 集群故障排查 3 场景 | 新增 |

---

## 二、基础题

### Q1: Nacos 的整体架构是什么?

**面试官意图**: 考察全局理解。

四层架构: API(api 模块, 接口契约) -> Client SDK(client 模块, gRPC 通信) -> Server Infrastructure(core 模块, gRPC Server + ConnectionManager) -> Consistency(consistency 模块, Distro AP + JRaft CP)。

两个子系统: Naming(内存存储 + Distro 同步 + gRPC 推送) 和 Config(DB 存储 + AsyncNotify 通知 + gRPC/HTTP 双入口)。共享: 集群管理(ServerMemberManager)、事件总线(NotifyCenter)、安全(鉴权)。

### Q2: NamingService 有 53 个方法,核心设计理念?

核心模型: Service = Instance 集合。5 种核心操作(注册/注销/查找/LB/订阅)在 groupName/clusters/healthy/subscribe 四个维度上做笛卡尔积展开。渐进复杂度哲学: 最简 `registerInstance(name, ip, port)` 3 参数, 复杂场景用 Instance 对象。不用 Builder: 历史兼容 + Spring Cloud 反射友好。

### Q3: ConfigService 为什么只有 12 个方法?

配置无层次结构(dataId + group 一层)、不需要负载均衡(静态 key-value)、不需要分页(配置数量少)、监听模型单一(只有 add/remove)。接口复杂度反映了领域复杂度。

### Q4: Instance.equals() 基于 toString() 的陷阱?

两个 Instance 相等 = toString() 完全相等(所有字段)。这是客户端**三路差分**的基石: `ServiceInfoHolder.isChangedServiceInfo` 用 `Map<ip:port>` 建索引,同 ip:port 时用 `toString()` 比对检测修改(modHosts)。副作用: hashCode 基于 toString, 放入 HashSet 后改字段会"丢失"元素。

---

## 三、客户端 SDK

### Q5: registerInstance 是同步还是异步?

调用层面同步(阻塞等待 gRPC response), 数据生效异步(事件链 -> 索引 -> PushDelayTask -> 推送)。redo 采用 **write-ahead 模式**: 发送前先 `cacheInstanceForRedo`(registered=false), 成功后 `instanceRegistered`(标记 true), 失败则保持 false 待重试。

### Q6: redo 机制是怎么工作的?

`onDisConnect`: connected=false + 所有 InstanceRedoData.setRegistered(false)。`RedoScheduledTask`(每 3s): 扫描 isNeedRedo() 的数据 -> 重新发送。注册天然幂等(服务端 `addServiceInstance` 是 Map.put 覆盖写)。哪些进 redo: 注册/注销/订阅(有状态)。哪些不进: 查询(无状态)。

### Q7: getConfig 四级降级?

L1 failover(运维手动应急文件) > L2 server(gRPC ConfigQueryRequest) > L3 snapshot(SDK 自动缓存, 服务端不可达兜底) > L4 null(首次启动+服务端不可达, 硬编码默认值)。snapshot=被动自动缓存, failover=主动手动应急。

---

## 四、服务端核心

### Q8: 注册请求完整路径?

Handler(InstanceRequestHandler, ephemeral=true 硬编码) -> EphemeralClientOperationServiceImpl(addServiceInstance + recalculateRevision + ClientChangedEvent) -> 事件分流: ① DistroClientDataProcessor(sync to allMembersWithoutSelf) ② ClientServiceIndexesManager(addPublisherIndexes + ServiceChangedEvent) -> NamingSubscriberServiceV2Impl(PushDelayTask 500ms) -> RpcPushService(gRPC bi-di stream) -> 客户端 NamingPushRequestHandler -> ServiceInfoHolder -> listener。

同步: gRPC 请求/响应。异步: 事件通知/Push/Distro sync。

### Q9: 为什么把实例从 Service 移到 Client?

1.x: 清理 O(S*I)、同步 O(N Service)。2.x: 清理 O(1)、同步 O(M Client)。查询变慢 -> ClientServiceIndexesManager 建 `Service -> Set<ClientId>` 索引补偿。空间换时间: 多维护索引, 换取故障清理和同步高效。

### Q10: Push 推送 + PushDelayTask 合并?

ServiceChangedEvent -> PushDelayTask(500ms)。merge: "push to all"胜出, 否则客户端集合取并集, lastProcessTime 取最早(保证首次变更后 500ms 一定触发)。执行: PushExecuteTask -> 遍历订阅者 -> PushExecutorDelegate(gRPC 2.x / UDP 1.x)。推送前做保护阈值过滤(ServiceUtil.selectInstancesWithHealthyProtection)。失败: 单客户端重试(1s); 超限踢连接。

### Q11: 2.x 连接健康检查 vs 1.x 心跳?

1.x: 应用层心跳(HTTP POST 5s), 服务端定时扫描, 删除延迟 30s。2.x: 传输层检测(gRPC keep-alive, 零额外开销), 断开即删(毫秒级)。兜底: ClientBeatCheckTaskV2(15s 标记不健康 + 发 ServiceChangedEvent 闭环, 30s 删除)。健康检查复用推送体系传播状态变更。

---

## 五、数据一致性

### Q12: Distro 协议工作流程?

sync(push): ClientChangedEvent -> isResponsibleClient 过滤 -> DistroDelayTask(1s) -> allMembersWithoutSelf。onReceive: upgradeClient(diff 合并, InstancePublishInfo.equals 逐条比较)。verify(pull): 每 5s 发 clientId+revision -> 不一致 -> 全量 sync。snapshot: 新节点批量加载。DistroMapper: **简单取模**(非一致性哈希), `hashCode(tag) % serverCount`。

### Q13: CAP 中注册中心选 CP 还是 AP?

Nacos 两者都支持: `ephemeral ? AP(Distro) : CP(JRaft)`。`ClientOperationServiceProxy.chooseClientOperationService` 一行三元运算符路由。注册中心大多数选 AP(允许短暂不一致, 不允许不可用)。但基础设施(DB 节点/DNS)需 CP(数据不能丢)。持久实例持久化: JRaft log + snapshot(zip), **不是 Derby/MySQL**(那是 Config 用的)。

### Q14: revision 能保证强一致吗?

不能。AbstractClient 用 `DistroUtils.hash`(基于内容), ConnectionBasedClient 用 `addAndGet(1)`(简单递增)。hash 相同 不等于 数据一致。脑裂时 diff 合并(InstancePublishInfo.equals), 最后收到者胜出。verify 能在分区合并后检测并修复。真正防脑裂需 CP(Raft)。

---

## 六、配置中心

### Q15: 配置变更监听怎么实现?(2.x gRPC)

**服务端**: ConfigPublishRequestHandler -> insertOrUpdate(DB) -> ConfigDataChangeEvent -> AsyncNotifyService(唯一消费者, 处理自己时调 DumpService) -> ConfigCacheService 更新缓存 -> LocalDataChangeEvent -> RpcConfigChangeNotifier -> ConfigChangeNotifyRequest(gRPC bi-di stream 推送)。

**客户端**: 收到推送 -> isConsistentWithServer=false -> executeConfigListen -> ConfigBatchListenRequest -> 服务端比对 MD5 -> 返回变更 dataId -> ConfigQueryRequest 拉取内容。setContent **不自动标记** isConsistentWithServer=true, 需下一轮确认(双确认模式)。5s 周期 executeConfigListen 兜底。

1.x 用 HTTP 长轮询(hold 30s), 服务端保留 LongPollingService 仅兼容 1.x。

### Q16: 配置变更如何通知集群?

ConfigDataChangeEvent -> AsyncNotifyService -> allMembers(含自己) -> 自己: dumpService.dump; 其他: gRPC ConfigChangeClusterSyncRequest -> 目标节点 DumpService.dump(从共享 DB 读取)。失败重试: 二次方退避(500ms + failCount² * 1000), **无限重试**(failCount 到 7 后延迟稳定 49.5s, 直到成功或成员移除)。Config 用"通知+拉取"(DB 共享), Naming 用"Push 数据"(内存)。

---

## 七、系统设计 + 故障排查

### Q17: 从零设计百万级实例的注册中心?

**评分维度**: 架构可行性(40%) + 性能分析(30%) + 容错(20%) + 运维(10%)

参考框架:
- **数据模型**: Client 中心模型(像 Nacos 2.x, 清理 O(1))
- **网络模型**: gRPC 长连接(连接即生命周期, bi-di stream 推送)
- **存储**: 内存(快速) + 索引(Service -> Set<ClientId>)
- **一致性**: AP(主路径, Distro 推+拉) + CP(基础设施, Raft)
- **推送**: 延迟合并(PushDelayTask debounce)
- **健康检查**: 连接驱动(主动) + 定时补偿(被动)
- **分片**: 简单取模或一致性哈希(Service -> Node)
- **集群管理**: address-server 动态发现

### Q18: 线上 Nacos 故障排查 3 场景

**场景 1: 大量实例标记不健康**
- 检查: gRPC keep-alive 配置 -> 网络抖动 -> 连接频繁断开
- 排查: ConnectionManager.connectionForClientIp 计数 -> ServerMemberManager 心跳
- 解决: 调整 keepAliveTime/Timeout; ClientBeatCheckTaskV2 兜底

**场景 2: 新注册实例客户端感知不到**
- 检查: PushDelayTask 队列深度 -> pushTaskDelay 配置
- 可能: pushTaskDelay 过大 + 客户端缓存未过期
- 解决: 减小 pushTaskDelay(500ms); 检查 ServiceChangedEvent 是否发出

**场景 3: 节点恢复后数据不一致**
- 检查: DistroProtocol.isInitialized -> verifyInterval 反熵是否触发
- 可能: verify 间隔太长 -> 数据窗口; DistroLoadDataTask 未完成
- 解决: 等 load 完成 + verify(5s)补偿; 检查 DistroMapper healthyList

---

## 八、面试准备路线图

```
Level 1 (基础): Q1~Q4  -> Nacos 宏观架构和 API 设计
Level 2 (核心): Q5~Q11 -> SDK/注册/Push/健康检查完整链路
Level 3 (深度): Q12~Q14 -> 一致性协议和 CAP 权衡
Level 4 (扩展): Q15~Q16 -> 配置中心
Level 5 (综合): Q17~Q18 -> 独立设计 + 故障排查
```

| 级别 | 必答题 | 选答题 |
|------|--------|--------|
| 初级(1-2年) | Q1,Q2,Q5,Q11 | Q3,Q4,Q7 |
| 中级(3-5年) | +Q8,Q9,Q10,Q12 | Q6,Q15,Q16 |
| 高级(5年+) | +Q13,Q14,Q17,Q18 | - |
