# D-14 详细规划: 面试模拟 18 题

> 阶段: Phase 7 — 面试模拟 | 预期产出: `md/D-14-Interview.md` | 状态: 待审核

---

## 一、为什么需要这篇

前 13 篇每篇都有 4-5 道面试题，但那些是按知识模块分散的。这篇整合所有面试题，按面试官视角分类，每道题含面试官意图分析 + 评分标准 + 参考答案 + 追问陷阱。目标是：**读完这篇能通过注册中心开发岗位的面试**。

前置依赖: D-01~D-13 (全部)

---

## 二、18 题分类

### 基础理解 (4 题)
| # | 题目 | 来源 |
|---|------|------|
| Q1 | Nacos 的整体架构是什么？包含哪些核心模块？ | 全景 |
| Q2 | NamingService 有 30+ 个方法，核心设计理念是什么？ | D-01 |
| Q3 | ConfigService API 为什么这么简洁(163行)？ | D-02 |
| Q4 | Instance 的 equals() 基于 toString()，有什么隐含陷阱？ | D-01 |

### 客户端 SDK (3 题)
| # | 题目 | 来源 |
|---|------|------|
| Q5 | Nacos SDK 的 registerInstance 是同步还是异步的？失败后怎么处理？ | D-03 |
| Q6 | Nacos SDK 的 redo 机制是怎么工作的？ | D-03 |
| Q7 | getConfig 的四级降级(failover→server→snapshot→null)分别对应什么场景？ | D-04 |

### 服务端核心 (4 题)
| # | 题目 | 来源 |
|---|------|------|
| Q8 | Nacos 的注册请求从 gRPC 到其他客户端感知，完整路径上做了什么？ | D-07 |
| Q9 | Nacos 2.x 为什么要把实例数据从 Service 移到 Client 上？架构决策还是实现细节？ | D-06 |
| Q10 | Nacos 2.x 的 Push 推送是怎么实现的？PushDelayTask(500ms) 合并机制的作用？ | D-08 |
| Q11 | Nacos 2.x 基于连接的健康检查和 1.x 的心跳检查有什么本质区别？ | D-08 |

### 数据一致性 (3 题)
| # | 题目 | 来源 |
|---|------|------|
| Q12 | Distro 协议的工作流程？sync/verify/snapshot 分别起什么作用？ | D-09 |
| Q13 | CAP 理论中，注册中心应该选 CP 还是 AP？Nacos 是怎么做的？ | D-10 |
| Q14 | revision 校验机制能保证数据强一致性吗？ | D-09 |

### 配置中心 (2 题)
| # | 题目 | 来源 |
|---|------|------|
| Q15 | 配置中心的长轮询(Long Polling)服务端是怎么实现的？ | D-11 |
| Q16 | 配置变更如何通知集群中所有节点？ | D-12 |

### 系统设计 + 故障排查 (2 题)
| # | 题目 | 来源 |
|---|------|------|
| Q17 | 如果你从零设计一个支持百万级实例的注册中心，你会怎么做？ | 新增 |
| Q18 | 线上 Nacos 集群故障排查 3 场景 | 新增 |

---

## 三、每题的面试官意图 + 追问

### Q1: Nacos 的整体架构是什么？包含哪些核心模块？

**面试官意图**: 考察全局理解，能否画出架构图

**评分标准**:
- 差: 只说 "Nacos=注册中心+配置中心" 
- 中: 能列出核心模块(api/client/core/naming/config/consistency)
- 好: 能按层次说明每层的职责 + 关键类 + 数据流

**参考答案**: 
- 四层架构: API → Client SDK → Server Infrastructure → Consistency
- 关键模块: api(接口契约), client(SDK), core(gRPC+ConnectionManager), naming(注册发现), config(配置管理), consistency(Distro+JRaft)

**追问**: "Naming 和 Config 为什么用不同的同步协议？"
- Naming 用 Distro(内存级,高频), Config 用 AsyncNotify(DB级,低频)

---

### Q2: NamingService 有 30+ 个方法，核心设计理念是什么？

**面试官意图**: 考察抽象能力

**评分标准**:
- 差: 能列举方法
- 中: 能分组(注册/注销/查找/订阅/LB)
- 好: 能解释重载的笛卡尔积展开 + 为什么不用 Builder 模式

**追问**: "那你觉得这个接口设计有什么问题？"
- 方法总数过多，是 Java 缺少默认参数的妥协。但接口稳定性 > 完美性。

---

### Q3: ConfigService API 为什么这么简洁(163行)？

**面试官意图**: 考察对两个子系统差异的理解

**参考答案**: 配置无层次结构、不需要负载均衡、不需要分页、监听模型单一

---

### Q4: Instance 的 equals() 基于 toString()，有什么隐含陷阱？

**面试官意图**: 考察对非显而易见设计的敏感度

**参考答案**: 所有字段变更都会导致 equals 返回 false → 这是 Nacos 客户端差分推送的基石，但同时意味着修改 metadata 后再放到 Set 中会行为异常

**追问**: "如果改用 ip+port 做 equals，会有什么影响？"
- 好处: 语义清晰。坏处: metadata/weight/healthy 变更不会被客户端差分检测到。

---

### Q5: Nacos SDK 的 registerInstance 是同步还是异步的？

**面试官意图**: 考察对调用模型的精确理解

**参考答案**: 调用层面同步(等待 gRPC response)，数据生效异步(事件链)

**追问**: "客户端进程 crash 后重启，redo 队列里的数据会怎样？"
- redo 是内存队列，丢失。但进程重启后重新注册。

---

### Q6: Nacos SDK 的 redo 机制是怎么工作的？

**面试官意图**: 考察对 at-least-once 语义的理解

**参考答案**: onConnected → 标记所有注册为 "未确认" → RedoScheduledTask(3s) 扫描 → 重发

**追问**: "redo 和 Kafka 的 at-least-once 有什么区别？"
- Kafka 持久化到磁盘，Nacos redo 纯内存。

---

### Q7: getConfig 的四级降级分别对应什么场景？

**面试官意图**: 考察对容灾设计的理解

**参考答案**: failover(运维手动应急) > server(正常) > snapshot(自动缓存) > null(硬编码默认值)

**追问**: "snapshot 和 failover 的本质区别？"
- snapshot=被动自动缓存，failover=主动手动应急。前者不需要改配置，后者需要。

---

### Q8: Nacos 的注册请求从 gRPC 到其他客户端感知，完整路径上做了什么？

**面试官意图**: 考察端到端全局理解

**参考答案**: Handler → Client.addServiceInstance → ClientRegisterServiceEvent → ClientServiceIndexesManager → ServiceChangedEvent → NamingSubscriberServiceV2Impl → PushDelayTask → RpcPushService → gRPC Push

**追问**: "哪些步骤是同步的，哪些是异步的？"
- gRPC 请求/响应同步，事件通知/Push/Distro sync 异步。

---

### Q9: Nacos 2.x 为什么要把实例数据从 Service 移到 Client 上？

**面试官意图**: 考察对数据结构设计变革的理解

**参考答案**: 
- 1.x 清理 O(S*I), 同步 O(N Service)
- 2.x 清理 O(1), 同步 O(M Client)
- 查询变慢 → 通过 ClientServiceIndexesManager 索引补偿

**追问**: "查询性能怎么保证？"
- publisherIndexes: Service → Set<ClientId>，写时维护索引，读时查索引。

---

### Q10: PushDelayTask(500ms) 合并机制的作用？

**面试官意图**: 考察对推送优化策略的理解

**参考答案**: 3 个实例连续注册 → 3 个 ServiceChangedEvent → merge → 1 次推送

**追问**: "pushTaskDelay=0 会有什么问题？"
- 推送风暴，每次 push 构建完整 ServiceInfo。

---

### Q11: Nacos 2.x 连接健康检查 vs 1.x 心跳检查？

**面试官意图**: 考察对架构演进的理解

**参考答案**: 1.x 应用层心跳(5s HTTP), 2.x 传输层检测(gRPC keep-alive, 零额外开销)

**追问**: "如果 keep-alive 配置异常会怎样？"
- ClientBeatCheckTaskV2 定时补偿兜底。

---

### Q12: Distro 协议的工作流程？

**面试官意图**: 考察对 AP 一致性协议的理解

**参考答案**: sync(push) + verify(反熵) + snapshot(快照) + query(按需)

**追问**: "sync 发送到哪些节点？"
- 发送端 isResponsibleClient 过滤 → syncToAllServer → allMembersWithoutSelf()

---

### Q13: CAP 理论中，注册中心应该选 CP 还是 AP？

**面试官意图**: 考察对 CAP 理论在注册中心场景下的深度理解

**参考答案**: Nacos 两者都支持: ephemeral→AP(Distro), !ephemeral→CP(JRaft)

**追问**: "你认为 CP 在注册中心真的合适吗？"
- CP 只在操作持久实例时生效，频率极低。网络分区时 CP 不提供服务的影响小。

---

### Q14: revision 校验机制能保证数据强一致性吗？

**面试官意图**: 考察对 AP 系统局限性的理解

**参考答案**: 不能。revision 是 hash 不是 timestamp，hash 相同 ≠ 数据完全一致。verify 能在分区合并后检测并修复，但无法防止脑裂时的并发修改。

---

### Q15: 配置中心的长轮询服务端是怎么实现的？

**面试官意图**: 考察对长轮询服务端实现的理解

**参考答案**: LongPollingService: 解析→MD5对比→如果变了立即返回，不变则 hold(29.5s)

---

### Q16: 配置变更如何通知集群中所有节点？

**面试官意图**: 考察对集群同步机制的理解

**参考答案**: AsyncNotifyService → memberManager.allMembers() → configClusterRpcClientProxy → gRPC → 目标节点 DumpService.dump

---

### Q17: 如果你从零设计一个支持百万级实例的注册中心，你会怎么做？

**面试官意图**: 考察系统设计能力 — 这是面试的最高难度题

**评分维度**:
- 架构可行性(40%): 分层是否合理、模块职责是否清晰
- 性能分析(30%): 数据分片、网络模型、存储设计
- 容错设计(20%): 一致性协议、故障恢复
- 运维考量(10%): 部署模式、监控、扩缩容

**参考答案框架**:
- 数据模型: 像 Nacos 2.x 的 Client 中心模型
- 网络模型: gRPC 长连接(连接即生命周期)
- 存储: 内存(快速) + 索引(Service→ClientId)
- 一致性: AP(主路径) + CP(基础设施)
- 推送: 延迟合并(类似 PushDelayTask)
- 健康检查: 连接驱动(主动)+心跳补偿(被动)
- 分片: 一致性哈希(Service→Node)

---

### Q18: 线上 Nacos 集群故障排查 3 场景

**面试官意图**: 考察生产故障排查能力

**场景 1: 大量实例标记不健康**
- 检查: gRPC keep-alive 配置 → 网络抖动 → 连接频繁断开
- 排查: `ConnectionManager.connectionForClientIp` 计数 → `ServerMemberManager` 心跳
- 解决: 调整 keepAliveTime/Timeout

**场景 2: 新注册实例客户端感知不到**
- 检查: PushDelayTask 队列深度 → PushDelayTask 延迟配置
- 可能原因: pushTaskDelay 过大 + 客户端缓存未过期
- 解决: 减小 pushTaskDelay(500ms)

**场景 3: 节点挂了又起来后数据不一致**
- 检查: DistroProtocol.isInitialized → verifyInterval 反熵是否触发
- 可能原因: verify 间隔太长 → 数据窗口
- 解决: 新节点 DistroLoadDataTask 加载 → verify 补偿

---

## 四、面试准备建议

### 面试路线图

```
Level 1 (基础): Q1~Q4  → 能说明 Nacos 的宏观架构和 API 设计
Level 2 (核心): Q5~Q11 → 能解释 SDK/注册/Push/健康检查的完整链路
Level 3 (深度): Q12~Q14 → 能分析一致性协议和 CAP 权衡
Level 4 (扩展): Q15~Q16 → 能覆盖配置中心
Level 5 (综合): Q17~Q18 → 能独立设计注册中心 + 排查故障
```

### 按面试级别推荐

| 级别 | 必答题 | 选答题 |
|------|--------|--------|
| 初级(1-2年) | Q1,Q2,Q5,Q11 | Q3,Q4,Q7 |
| 中级(3-5年) | +Q8,Q9,Q10,Q12 | Q6,Q15,Q16 |
| 高级(5年+) | +Q13,Q14,Q17,Q18 | — |

---

## 五、产出文档结构

```
D-14-Interview.md

1. 18 题总览(按级别分类)
2. 基础题 Q1~Q4 (每题: 意图+评分+答案+追问)
3. SDK题 Q5~Q7
4. 服务端核心 Q8~Q11
5. 数据一致性 Q12~Q14
6. 配置中心 Q15~Q16
7. 系统设计 Q17 (完整设计框架)
8. 故障排查 Q18 (3 个实战场景)
9. 面试准备路线图
```

---

## 六、Phase B 完成标志

```
✅ Phase 1 (API): D-01, D-02
✅ Phase 2 (SDK): D-03, D-04
✅ Phase 3 (基础设施): D-05, D-06
✅ Phase 4 (命名核心): D-07, D-08
✅ Phase 5 (一致性): D-09, D-10
✅ Phase 6 (配置中心): D-11, D-12
✅ Phase 7 (全景+面试): D-13, D-14

ALL 14 PLANS COMPLETE.
Next: Phase C — Document Writing.
```
