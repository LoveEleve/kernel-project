# stage-2 模式、设计与实现 完整梳理

> 基于小马哥（mercyblitz）Java 分布式架构训练营第二期"模式、设计与实现"28 章节逐章深度梳理。
> 原始技术栈：Spring Boot 2.7.9 + Spring Cloud 2021.0.4 + Dubbo 2.7.8 + JRaft 1.3.12（部分过时）。
> 本文档目的：提取每章主题，映射到 2025 年现代技术栈，作为 sca-lab 学习实验场的规划基础。
> **核心原则**：stage-2 的所有主题都要理解，过时技术用现代替代品学习同样的主题，不跳过任何主题。
> **附：papers/ 10 篇经典论文 + ebooks/ 2 本电子书 + middleware-projects/ 5 个自研中间件项目**

---

## 一、stage-2 概览

| 维度 | 内容 |
|---|---|
| 章节数 | 28 节（16 周，每周 1-2 节） |
| 主题 | 分布式理论 → 中间件自研 → ZK → 事务体系 → RPC → 配置中心 → 存储 → 缓存 |
| 原始技术栈 | Spring Boot 2.7.9 + Spring Cloud 2021.0.4 + Dubbo 2.7.8 + SOFAJRaft 1.3.12 |
| 现代对应栈 | Spring Boot 3.5.13 + Spring Cloud 2025.0.2 + SCA 2025.0.0.0 |
| 功能域 | 7 个（共识理论 / ZooKeeper / 事务体系 / RPC / 配置 / 存储 / 缓存） |
| 论文 | 10 篇（BASE / paxos-simple / raft / zab / The-Part-Time-Parliament / 拜占庭 3 篇 / Life beyond distributed transactions 中英版） |
| 电子书 | 2 本（LCN 分布式事务框架 / Microservice Transaction Patterns） |
| 自研中间件 | 5 个（rpc-project / distributed-config-project / distributed-transaction-project / distributed-cache-project / zookeeper-project） |

**注**：`middleware-projects/` 5 个自研中间件只有 **rpc-project**（53 文件 Raft FSM+Netty RPC）和 **distributed-transaction-project**（17 文件 XA 2PC sample）有实质算法；其余 3 个是空骨架/stub/Curator API 调用，不可作为代码参考。

---

## 二、按功能域归类的子主题清单

### 1. 共识理论域（第 01-08 章）

#### 第 01 章：CAP 与 BASE 理论

**核心主题**：分布式系统的三个核心属性之间的根本性权衡，以及作为 CAP 工程妥协的 BASE 理论

**子主题清单**：
- CAP 定理正式定义 — C（所有节点同一份最新数据）、A（每次请求获非错响应）、P（网络分区时只能在 C 和 A 之间选择）；现代对应：Nacos AP/CP 双模式
- CAP "三取二"的常见误解 — Brewer 澄清"分区罕见时 C 和 A 可兼得"；现代对应：PACELC 定理延伸
- PACELC 定理 — 分区时在 A 和 C 间权衡，否则在 L（延迟）和 C 间权衡；现代对应：Cassandra 可调一致性
- 区块链共识的最终一致性 — 通过确认数实现概率一致性；现代对应：比特币 6 区块确认
- 注册中心 CAP 选型对比 — Eureka(AP)、ZK(CP)、Consul(AP/CP)、Nacos(1.x AP / 2.x CP+AP 双模式)
- BASE 理论 — Basically Available + Soft state + Eventually consistent；现代对应：Dan Pritchett eBay 论文
- ACID vs BASE 的哲学对立 — 强一致性 vs 最终一致性；现代对应：Seata AT(最终一致) / XA(强一致)

**可学习点**：CAP 精确适用条件、PACELC 对 CAP 的补充、最终一致性的工程含义

**现代对应**：Nacos 2.x CP(JRaft)+AP(Distro)、Cassandra 可调一致性、Seata AT 最终一致

---

#### 第 02 章：分布式共识算法 - Paxos

**核心主题**：Paxos 协议族的完整推导 — Basic Paxos 两阶段协议的逐行拆解

**子主题清单**：
- 共识问题定义 — 不可靠网络中一组参与者就一个结果达成一致；现代对应：etcd/ZooKeeper/Nacos CP 模式基础
- 5 种角色定义 — Client/Acceptor/Proposer/Learner/Leader；现代对应：SOFAJRaft Node 封装
- Quorum 仲裁机制 — 任意两个 Quorum 共享至少一个成员，2F+1 容忍 F 故障；现代对应：Raft 多数派
- 提议编号与商定值 — 唯一递增 n + 值 v 的绑定；现代对应：Raft term + log index
- 安全属性三要素 — 有效性/协定/终止；现代对应：Raft State Machine Safety
- Basic Paxos 两阶段协议 — Phase 1(Prepare+Promise) + Phase 2(Accept+Accepted) 完整消息格式
- 6 种优化策略 — 杰出学习者/领导者只向法定人数发送/首轮跳过 Phase 1 等；现代对应：Multi-Paxos 领导者任期
- Multi-Paxos — 全局 Leader 省略 Phase 1；现代对应：etcd Raft/SOFAJRaft
- 4 种消息约定 — P(n)、PM、A(n,v)、Accepted；现代对应：Raft RequestVote/AppendEntries

**可学习点**：Phase 1+Phase 2 精确消息格式、Quorum 交叠性保证 safety 原理

**现代对应**：Basic Paxos→Raft、Multi-Paxos→SOFAJRaft/etcd Raft

---

#### 第 03 章：复制日志共识算法 - Raft

**核心主题**：Raft 算法的完整设计与证明 — 28 章中最厚实的章节（40KB/13 张图）

**子主题清单**：
- Raft 设计哲学 — 可理解性优先（分解 + 状态空间缩减）；现代对应：etcd/SOFAJRaft/TiKV
- 三个独立子问题 — 领导者选举/日志复制/安全性；现代对应：SOFAJRaft Node/Replicator/FSMCaller
- 服务器三种状态 — Leader/Follower/Candidate + 角色转换；现代对应：SOFAJRaft Node 角色
- Term 任期机制 — 连续整数、逻辑时钟；现代对应：etcd term/ZAB epoch
- 两种 RPC — RequestVote + AppendEntries；现代对应：SOFAJRaft RPC 接口
- 选举超时与随机化 — 150-300ms 随机超时、分裂投票处理；现代对应：etcd 默认 1000ms
- 日志复制流程 — Leader Append→并行 AppendEntries→多数派→Commit→应用到状态机；现代对应：SOFAJRaft Replicator
- 日志匹配属性 — 相同 index+term 必有相同 cmd；现代对应：LogManager 一致性检查
- 日志不一致恢复 — nextIndex 递减直至匹配 + Leader 覆盖 Follower
- 领导完整性 — Candidate 日志必须至少与多数派一样"新"；现代对应：etcd 选举限制
- 旧任期条目提交规则 — 只能通过当前任期条目的提交来间接提交（Raft 论文图 8 corner case）
- 安全性证明 — 反证法：假设 LeaderU 丢失 LeaderT 的提交条目→矛盾→状态机安全
- 时间可用性不等式 — broadcastTime ≪ electionTimeout ≪ MTBF；现代对应：etcd 配置调优
- 联合共识成员变更 — Cold,new 过渡配置、两配置各需多数；现代对应：etcd 成员变更
- 日志压缩 Snapshot — 状态机快照 + 丢弃日志；现代对应：SOFAJRaft SnapshotStorage

**可学习点**：Raft 通过分解比 Paxos 更可理解、随机选举超时+任期制避免无限分裂投票、联合共识如何避免"分裂多数"问题

**现代对应**：etcd/SOFAJRaft/TiKV/Consul 的共识核心

---

#### 第 04 章：原子广播算法 - ZAB

**核心主题**：ZooKeeper Atomic Broadcast 协议 — 为 primary-backup 副本同步设计的崩溃恢复原子广播，主序(Primary Order)是其核心正确性属性

**子主题清单**（15 个）：
- ZAB 协议定位 — ZooKeeper 专门设计的崩溃恢复原子广播，非通用共识算法；现代对应：ZooKeeper 3.7+ 核心协议
- 主备份模型 — Primary 执行客户端操作，ZAB 将增量状态变更传播到 Backup
- 主序属性(Primary Order) — ZAB 核心正确性属性，状态变更必须按主节点生成顺序交付；区别于因果序和全序
- 与 Paxos 对比 — Paxos 多提议者导致因果依赖破坏；ZAB 通过主序约束解决
- 多个未完成事务并发 — ZAB 允许 Primary 同时处理多个客户端操作并批量提交，提高吞吐量
- ZXID 事务标识 — (epoch, counter)二元组，单调递增实现高效恢复识别；现代对应：Raft term+log index
- 高效恢复机制 — 新 Leader 从 quorum 收集最高 zxid 即可确定恢复起点
- 发现阶段 — Follower 发 CEPOCH、Leader 回 NEWEPOCH、Follower 确认 ACK-E、选初始历史
- 同步阶段 — Leader 发 NEWLEADER+初始历史 Ie0、Follower 接受并提交
- 广播阶段 — Leader 按 zxid 递增向 quorum 提议事务，多数接受后 COMMIT
- 两阶段提交风格 — 类似无中止的两阶段提交
- 进程三种状态 — ELECTION/FOLLOWING/LEADING，状态转换逻辑
- 幂等性保证 — 状态变更是幂等的，多次应用按顺序交付不会导致不一致
- Leader Oracle — 每个进程实现 leader oracle 提供候选 Leader 标识符
- ZAB 是独立协议非 Paxos 变体 — Junqueira DSN 2011 明确区分：ZAB 有发现/同步/广播三个阶段

**可学习点**：主序属性 vs 因果序 vs 全序的三级递进关系、zxid 的 (epoch, counter) 设计如何简化崩溃恢复、ZAB 三阶段构建安全状态机复制

**现代对应**：ZooKeeper 3.x 原子广播协议未变；Raft 的领导者租约和快照机制比 ZAB 三阶段更简洁；Nacos CP 模式直接选用 JRaft 而非自研 ZAB

---

#### 第 05 章：日志复制共识算法实现 - SOFAJRaft

**核心主题**：SOFAJRaft — 百度 braft 的 Java 移植，蚂蚁金服维护的生产级 Raft 实现，支持 Multi-Raft-Group

**子主题清单**（18 项功能特性 + 4 个用户案例）：
- Leader 选举 — 基于优先级的半确定性选举(可指定优先级)
- 日志复制与恢复 — 从 Leader 向 Follower 复制日志条目，冲突时强制覆盖
- Learner 只读成员 — 参与日志复制但不行使投票权
- Snapshot 日志压缩 — SnapshotStorage+SnapshotExecutor 管理状态快照，用于重建和日志压缩
- 成员变更 — 增加/删除/替换节点，支持 Joint Consensus 过渡
- 主动切换 Leader — 用于重启维护和 Leader 负载平衡
- 网络分区容忍 — 对称分区和非对称分区场景下算法行为
- 少数派故障容错 — 少数节点宕机不影响整体可用性
- 多数派故障手动恢复 — 过半节点宕机时可手动恢复
- 线性一致读 — ReadIndex+LeaseRead 两种高效机制
- 流水线复制 — 流水线化 AppendEntries 请求提升吞吐
- 内置 Metrics 统计 — 基于 Dropwizard Metrics 类库的性能指标
- Jepsen 验证 — 通过 Jepsen 一致性验证测试
- 嵌入式 KV 存储 — 内建 RheaKV(RocksDB+JRaft)
- 核心组件 Node — 用户主要入口(apply 提交任务)
- 分层存储设计 — LogStorage(RocksDB)→LogManager(缓存/批量提交)→StableClosureEventHandler(Disruptor 异步刷盘)
- RPC 通讯层 — RPC Server+Client 处理投票请求和 AppendEntries
- Multi-Raft-Group — 单进程运行多个 Raft Group 实现高吞吐

**4 个用户案例**：
- RheaKV — SOFAJRaft+RocksDB 嵌入式分布式 KV 存储
- AntQ Streams QCoordinator — JRaft 做 Coordinator 集群选举和元信息存储
- SOFA 注册中心 — IP 数据注册，写数据各节点强一致
- **Nacos 2.x** — 作为 CP 模式底层共识引擎

**可学习点**：Disruptor 事件驱动架构在日志刷盘中的应用、LogManager→LogStorage 异步解耦与批量写入优化

**现代对应**：SOFAJRaft 仍是蚂蚁集团 SOFABoot 生态核心；Nacos 2.x CP 模式直接内嵌 SOFAJRaft；RheaKV 类似 TiKV 思路

---

#### 第 06 章：SOFAJRaft 架构与实现

**核心主题**：SOFAJRaft 从基础数据结构到 AppendEntries 请求处理全链路的源码级实现（34KB，含大量源码）

**子主题清单**：
- 基础组件 — Endpoint(IP:端口)、PeerId(ip:port:index)、Configuration(Raft Group 节点列表)
- 回调机制 — Closure(void run(Status)) + Status + TaskClosure.onCommitted
- StateMachine 11 个事件方法 — onApply/onLeaderStart+Stop/onStartFollowing+StopFollowing/onSnapshotSave+Load/onConfigurationCommitted/onError/onShutdown
- **AppendEntries 处理全链路（重点，~600 行源码）** — NodeImpl.handleAppendEntriesRequest → 任期检查 → prevLogTerm 一致性检查 → LogManagerImpl.appendEntries → 内存缓存(logsInMemory) → diskQueue 发布 → AppendBatcher.flush(256 条批量) → RocksDB 落盘
- BallotBox.setLastCommittedIndex — committed index 追踪 → FSMCaller 回调
- FSMCallerImpl.doCommitted — Disruptor 事件处理 + ClosureQueue + doApplyTasks 调用 onApply
- LogManagerImpl.appendEntries — checkAndResolveConflict(冲突解决) + configManager.add(配置变更)
- AppendBatcher 批量刷新 — 256 条一批写入 RocksDB
- Disruptor 事件驱动模式 — taskQueue + diskQueue 双事件总线
- Iterator 迭代器 — IteratorImpl 遍历 + while(it.hasNext()) getData/getIndex/getTerm

**可学习点**：AppendEntries 从 RPC 入站到 RocksDB 落盘的 ~600 行全链路、Disruptor 事件驱动、批量提交提升吞吐

**现代对应**：etcd raft/raft.go Step()、Raft Ready()+Advance() 异步模型

---

#### 第 07 章：Alibaba Nacos 2.x Raft 共识算法的运用

**核心主题**：Nacos 2.x 将一致性协议下沉为内核能力，通过 SOFAJRaft 实现 CP 模式

**子主题清单**（15 个）：
- Nacos 历史演进 — 2008 五彩石→十年双十一→2018 开源
- 四层架构 — 用户层/业务层(服务发��+配置管理)/内核层(一致性/存储/高可用)/插件层
- 早期一致性协议问题 — 服务注册和配置管理各自耦合一致性协议，未下沉内核
- 一致性协议下沉 — 当前设计将一致性协议抽象为内核核心能力，计算存储分离
- ConsistencyProtocol 接口 — 泛型接口 `<T extends Config, P extends RequestProcessor>`，定义 getData()/write() 方法
- CommandOperations 接口 — ConsistencyProtocol 继承的命令操作能力
- ProtocolManager 机制 — Spring 组件，继承 MemberChangeListener，管理 APProtocol 和 CPProtocol
- initAPProtocol() — 从 Spring 容器获取 APProtocol Bean，注入成员信息，调用 init()
- initCPProtocol() — 从 Spring 容器获取 CPProtocol Bean，注入成员信息，调用 init()
- CPProtocol 接口 — 继承 ConsistencyProtocol，额外提供 isLeader(group) 方法
- JRaftProtocol 实现 — CPProtocol 的 SOFAJRaft 实现，核心执行流程封装
- APProtocol 接口 — AP 模式的协议接口(Distro 算法的抽象层)
- NacosStateMachine — 基于 SOFAJRaft StateMachine 的 Nacos 状态机实现
- NacosClosure — 任务回调封装，onApply 完成时通知
- Nacos 数据请求处理 — 基于 WriteRequest/ReadRequest 的统一数据操作模型

**可学习点**：一致性协议下沉到内核层的架构思想(从耦合到可插拔)、ProtocolManager 双协议管理机制(AP+CP)、JRaftProtocol 封装 SOFAJRaft 为 Nacos 内部使用

**现代对应**：Nacos 2.x CP 模式基于 SOFAJRaft，AP 模式基于 Distro；ProtocolManager 双协议架构是 Nacos 3.0 核心演进基础

---

#### 第 08 章：Alibaba Nacos 2.x Distro 算法的运用

**核心主题**：Nacos 自研的 AP 分布式协议 Distro — Leader-less 对称集群、最终一致性

**子主题清单**：
- Leader-less 对称集群 — 每个节点可处理写请求，无单点瓶颈；对比 Raft 强 Leader
- 一致性 Hash 路由 — DistroFilter(Servlet Filter) 拦截请求 + DistroMapper.responsible() 计算责任节点
- 数据初始化全量拉取 — 新节点加入时轮询所有节点拉取全量数据
- 心跳数据校验 — 定期发元信息（非全量）作为心跳，不一致时触发补齐（类似 Anti-Entropy）
- 读操作本地响应 — 每台存全量数据，直接本地返回实现高吞吐 AP
- ServerMemberManager — 成员发现（单机/文件/地址服务器三种实现）+ MemberInfoReportTask 心跳
- DistroProtocol 生命周期 — startVerifyTask（校验）+ startLoadTask（加载）双定时任务
- 转发机制 — 非本节点请求通过 HttpClient.request() 代理转发到目标节点
- 同类实现对比 — Tomcat JGroup 集群广播、Eureka P2P 复制、Consul Gossip

**可学习点**：一致性 Hash 路由+健康列表维护、心跳元信息校验而非全量同步

**现代对应**：Nacos 2.x/3.x 仍用同一 Distro 协议，是生产级 AP 注册中心范本

---

---

### 2. ZooKeeper 域（第 09-12 章）

#### 第 09 章：ZooKeeper 数据模型

**核心主题**：ZK 的分层命名空间数据模型 — 类似文件系统的 znode 树形结构，服务于分布式协调

**子主题清单**：
- 分层命名空间 — 路径 `/a/b/c` 类似文件系统，每节点可同时是"目录"和"文件"；现代对应：Etcd 扁平键值+前缀查询
- ZNode 类型体系 — PERSISTENT/EPHEMERAL/SEQUENTIAL 及其组合；3.6+ 新增 CONTAINER 和 TTL；现代对应：Nacos 临时实例等效 Ephemeral
- 临时节点 — 会话生命周期绑定、无子节点；用于服务注册（心跳断则节点消）
- 顺序节点 — 父节点下单调递增计数器（`%010d` 填充）；分布式锁基石
- Stat 数据结构 — czxid/mzxid/pzxid/version/cversion/aversion/ephemeralOwner/dataLength/numChildren
- Zxid 总序机制 — 每次状态变更分配唯一 Zxid 实现全量总序；现代对应：Raft term+index
- Watch 一次性触发器 — 数据变更时发送事件、清除监视、先见事件再见新数据；3.6+ addWatch() 持久递归监视
- ClientCnxn 客户端连接 — Packet 封装请求/响应/监视回调、SendThread 执行 I/O 循环
- 服务端 WatchManager — watchTable（path→Watcher 集合）和 watch2Paths
- Jute 序列化框架 — 自研 Record/OutputArchive/InputArchive；现代对应：Protobuf

**可学习点**：Watch 一次性语义与事件排序保证、Stat 版本号实现乐观锁、Zxid 总序

**现代对应**：Curator 5.x 封装、Etcd v3 API、Nacos 临时实例

---

#### 第 10 章：ZooKeeper 通讯与会话

**核心主题**：ZK 的 TCP 网络通讯架构 — NIO 底层传输、Jute 序列化、双队列异步 I/O

**子主题清单**（13 个）：
- Jute 序列化框架 — 自研二进制序列化(Record/OutputArchive/InputArchive)，专为 ZK 通讯帧优化；现代对应：Protobuf/Kryo
- 传输层帧协议 — 4 字节长度头+Payload 体，TCP 粘包分包通过 lenBuffer+incomingBuffer 两段式读取；现代对应：gRPC HTTP/2 流帧
- ClientCnxn 双队列架构 — outgoingQueue(待发送)→发送后移至 pendingQueue(等待响应)；现代对应：Netty ChannelOutboundBuffer
- XID 延迟生成 — queuePacket()不生成 XID，发送时 getXid()赋值，减少 ConnectRequest 场景浪费；现代对应：Seata RPC 延迟序号分配
- submitRequest 同步等待 — packet.wait()阻塞直到 SendThread 收到响应 packet.notifyAll()；现代对应：CompletableFuture+Netty Promise
- SendThread 事件循环 — 单线程模型，Selector 多路复用+过期会话清理+心跳 ping；现代对应：Netty EventLoop
- ClientCnxnSocketNIO 写路径 — 出队 Packet→序列化 ByteBuffer→sock.write(p.bb)→移入 pendingQueue；现代对应：Netty writeAndFlush
- ClientCnxnSocketNIO 读路径 — 读 lenBuffer(4 字节长头)→readLength()→分配 incomingBuffer→读完整消息→readResponse 解析
- ClientCnxnSocketNetty — 可插拔 Socket 抽象，ChannelPipeline(Sasl+编解码器)替代 NIO Selector
- HostProvider 故障切换 — StaticHostProvider 实时 DNS 解析，服务器列表轮转，断连重连；现代对应：Nacos ServerListManager+gRPC 重连
- Packet 内部结构 — requestHeader/replyHeader/request/response/watchRegistration/bb 等完整上下文
- 会话状态机 — CONNECTING/CONNECTED/CLOSED/NOT_CONNECTED，超时由 Server SessionTracker 管理；现代对应：Nacos 临时实例心跳超时
- OpCode 操作码体系 — notification(0)到 createSession(-10)共 25+种命令字；现代对应：gRPC method 定义

**可学习点**：Packet 双队列+XID 延迟生成（生产故障排查关键，pendingQueue 积压定位慢请求）、NIO 读写路径源码 trace

**现代对应**：已��� Nacos gRPC(Netty+Protobuf)取代，但双队列模型和 Selector 事件循环思想贯穿 Seata RPC/RocketMQ Remoting

---

#### 第 11 章：Apache ZK 共识算法的实现

**核心主题**：FastLeaderElection（TCP 版本）的完整 Leader 选举算法 — ZAB 协议核心组件

**子主题清单**：
- 三种选举算法演变 — LeaderElection(UDP) + FastLeaderElection(UDP) 已废弃、electionAlg=3 TCP
- 核心术语 — SID(myid)、ZXID(事务 ID)、Vote(SID+ZXID+Epoch)、Quorum(n/2+1)
- 投票变更四规则 — epoch/zxid > SID 优先级，完整全序比较逻辑
- 服务器状态机 — LOOKING→FOLLOWING/LEADING/OBSERVING
- lookForLeader 核心算法 — 自增 logicalclock→updateProposal(自荐)→sendNotifications→循环 poll 通知
- totalOrderPredicate — newEpoch>curEpoch ∥ (epoch== && zxid>) ∥ (epoch== && zxid== && id>)
- QuorumCnxManager — 一对一 TCP 连接，IP 对等平局打破机制
- RequestProcessor 链 — Leader 和 Follower 各自的 Processor 链
- Leader.processAck — 记录 outstandingProposals，累计多数 ACK 后 tryToCommit

**可学习点**：epoch 逻辑时钟的分布式含义、选票优先级(data新>ID大)、proposal-ack 两阶段确认

**现代对应**：SOFAJRaft（Java Raft）、Etcd/Raft（Go）、Kafka KRaft

---

#### 第 12 章：Apache ZK 共识算法的运用

**核心主题**：基于 ZK 强一致性+Curator 封装实现服务注册发现和分布式锁

**子主题清单**（12 个）：
- 服务注册基本模式 — 底层路径 `/services/{serviceName}/{instanceId}`，操作：create(临时节点)/delete/getChildren/setData；现代对应：Nacos HTTP/gRPC 注册接口
- 服务变更监听 — Watch 事件实现 ServiceCache 缓存刷新，变化时 notifyAll 刷新本地列表；现代对应：Nacos 2.x gRPC 流式推
- Curator ServiceProvider — 服务发现门面，封装 ProviderStrategy+InstanceProvider，内置 RoundRobin/Random/Sticky 策略；现代对应：Spring Cloud LoadBalancer
- ProviderStrategy 选择策略 — RoundRobin(原子计数器)、Random(ThreadLocalRandom 无锁优化)、Sticky(固定实例)
- ServiceInstance 数据模型 — name/id/address/port/sslPort/payload/registrationTimeUTC/ServiceType(DYNAMIC/PERSISTENT)/enabled
- InterProcessMutex 锁获取算法 — EPHEMERAL_SEQUENTIAL→getSortedChildren()排序→最小序号得锁(maxLeases=1)；**ZK 分布式锁核心算法，面试高频**
- Watch 链式等待 — 未获锁线程监听前一个节点→wait()阻塞→前驱释放触发 notifyAll→重新排序检查；现代对应：Redisson pub/sub+自旋
- 锁释放与超时 — 删除自身节点触发前驱 Watcher，millisToWait 超时→deleteOurPath()清理
- Watcher 惊群效应优化 — 当前对所有 AnyEvent 调用 notifyAll；优化：仅在被监听节点实际删除时唤醒
- 配置管理场景 — ZK 数据发布/订阅实现实时配置推送；现代对应：Nacos Config @NacosValue
- 第三方整合 — Curator 封装样板代码，Dubbo/Spring Cloud ZK 均依赖；现代对应：Nacos-Client SDK
- **过时标注**：ZK 注册中心已被 Nacos 取代，ZK 分布式锁已被 Redisson 取代（性能高 10x）

**现代对应**：Nacos(发现)+Redisson(锁)+Nacos Config(配置)；InterProcessMutex 算法思想仍是经典

---

### 3. 事务域（第 13-20 章）

#### 第 13 章：Java EE 本地事务管理

**核心主题**：JDBC 规范从 SQL 标准到 Java API 的完整事务映射（ACID/隔离级别/Savepoint/DataSource）

**子主题清单**（12 个）：
- DriverManager 双重加载 — loadInitialDrivers()：jdbc.drivers 系统属性+JDK SPI ServiceLoader<Driver> 双机制
- Driver SPI static 注册 — 驱动类 static 块中 registerDriver()，Class.forName 加载即完成注册；现代对应：spring.datasource.driver-class-name
- getConnection 遍历 — 遍历 registeredDrivers→isDriverAllowed(ClassLoader 校验)→driver.connect(url, info)，返回首个成功
- DataSource 三层分层 — 基础实现(MysqlDataSource)→连接池装饰(HikariCP/DBCP/C3P0/Druid)→JNDI 托管(Tomcat Resource)
- Statement 三层级 — Statement→PreparedStatement(预编译防注入)→CallableStatement(存储过程)；现代对应：MyBatis SqlSession
- MySQL PreparedStatement 双实现 — ClientPreparedStatement(客户端拼 SQL) vs ServerPreparedStatement(PREPARE 命令)
- 自动与手动事务 — setAutoCommit(false)→发送 SET autocommit=0→多条 SQL 同事务；现代对应：Spring @Transactional
- commit/rollback 底层 — 校验 !isAutoCommit()→发送"commit"或"rollback"→网络断开抛 SQL_STATE_TRANSACTION_RESOLUTION_UNKNOWN
- Savepoint 子事务 — setSavepoint()→SAVEPOINT；rollback(Savepoint)→ROLLBACK TO SAVEPOINT；releaseSavepoint() MySQL 驱动空实现
- 事务隔离级别 — setTransactionIsolation(level)→映射 MySQL SET SESSION TRANSACTION ISOLATION LEVEL；现代对应：@Transactional(isolation=...)
- JDBC 类型体系 — java.sql.Types 常量+JDBCType 枚举(JDBC 4.0+)；现代对应：MyBatis-Plus 自动类型映射
- MySQL Binlog — JDBC 驱动支持 Binlog 流式订阅，上层 Canal/Maxwell 实现 CDC；现代对应：Debezium+Kafka CDC

**可学习点**：DriverManager SPI+static 双轨加载、Statement 三级选型、Savepoint 与 NESTED 传播行为关系

**现代对应**：JDBC 本地事务仍是所有数据库事务基石；HikariCP 统一连接池；CDC 已从 JDBC Binlog 转向 Debezium+Kafka

---

#### 第 14 章：Spring 本地事务管理

**核心主题**：Spring 基于 AOP+TransactionInterceptor 的声明式事务管理

**子主题清单**（11 个）：
- Java Beans 自省 — BeanInfo/PropertyDescriptor 构成元信息体系，Spring IoC 从中演化出 BeanDefinition
- EJB 三大 Bean 类型 — Session(有状态/无状态)/Entity(ORM 持久化)/Message-Driven(JMS)；**EJB 已过时→Spring @Service+JPA @Entity**
- EJB 事务传播 6 种 — Required/RequiresNew/Mandatory/NotSupported/Supports/Never；Spring 在其上增加 NESTED
- TransactionInterceptor AOP 切入点 — MethodInterceptor 环绕拦截：before 获取事务属性→proceed 执行业务→after 提交/回滚
- TransactionSynchronizationManager — ThreadLocal 维护当前线程事务资源(resources/synchronizations/currentTransactionName)
- NOT_SUPPORTED 挂起机制 — doSuspendSynchronization()→clearSynchronization()→挂起底层 Connection→执行后恢复
- REQUIRED vs REQUIRES_NEW — REQUIRED 加入同一事务(异常整体回滚)；REQUIRES_NEW 挂起外层→新独立事务→内层独立提交/回滚
- @Transactional AOP 全链路 — BeanFactoryTransactionAttributeSourceAdvisor 匹配→TransactionInterceptor 织入→invokeWithinTransaction()
- PlatformTransactionManager SPI — DataSourceTransactionManager(JDBC)/JtaTransactionManager(JTA)/HibernateTransactionManager 等
- 回滚规则 — 默认 RuntimeException+Error 回滚，checked exception 不回滚；@Transactional(rollbackFor=Exception.class)覆盖
- NESTED 传播 — 基于 JDBC Savepoint，同 Connection 内嵌套，部分回滚不影响外层

**可学习点**：TransactionSynchronizationManager ThreadLocal 设计(Spring 事务核心)、REQUIRED vs REQUIRES_NEW 生产级粒度选择

**现代对应**：Spring 声明式事务仍是 Java 企业应用标准；Seata AT/TCC 扩展 PlatformTransactionManager SPI 与 Spring 事务无缝整合

---

#### 第 15 章：JTA 和 XA 原理与实现

**核心主题**：JTA 规范和 XA 两阶段提交协议的 Java 映射，MySQL 手写 2PC 完整实现

**子主题清单**（15 个）：
- JTA 架构总览 — 三方接口（应用程序/资源管理器/应用服务器）；现代对应：Spring @Transactional 底层可切换 JTA 或本地事务管理器
- 分布式事务五大角色 — TM、Application Server、Resource Manager、Transactional Application、CRM；现代对应：Seata TC/TM/RM 三角色更精简
- JTA 与 EJB 的关系 — javax.transaction.UserTransaction 供 CMT/BMT 使用；现代对应：Spring TransactionTemplate 替代 EJB CMT
- JTA 与 JDBC 的关系 — javax.sql.XAConnection/XADataSource/XAResource 三接口；现代对应：HikariCP 通过 XADataSource 包装支持 XA
- JTA 与 JMS 的关系 — javax.jms.XAConnection/XASession；现代对应：RocketMQ 事务消息(半消息)某种程度上替代了 JMS XA
- JTA 与 JTS 的关系 — JTS 是 CORBA OTS 底层实现，IIOP 传播事务上下文；现代对应：gRPC 传播 xid 替代 IIOP
- UserTransaction 接口 — begin/commit/rollback/setRollbackOnly；现代对应：Spring TransactionStatus 封装类似语义
- TransactionManager 接口 — begin/commit/rollback/suspend/resume；现代对应：Seata GlobalTransaction 的 begin/commit/rollback
- 事务挂起与恢复 — suspend()返回 Transaction 对象，resume(Transaction)重新关联线程；现代对应：Spring PROPAGATION_REQUIRES_NEW 底层通过 suspend/resume 实现
- Transaction 接口 — 资源登记(enlist/delist)、同步回调(beforeCompletion/afterCompletion)、事务相等性
- XAResource 接口 — X/Open XA 的 Java 映射，start/end/prepare/commit/rollback/forget/recover；现代对应：Seata AT DataSourceProxy 本质是 XAResource 变体
- XA 两阶段提交 — prepare 阶段(投票) + commit 阶段(执行)；现代对应：MySQL XA PREPARE/XA COMMIT SQL 语句
- XAResource 资源共享与线程安全 — isSameRM 判断同一资源管理器实例，不同线程可操作同一 XAResource
- Atomikos 开源实现 — AtomikosDataSourceBean 代理 DataSource，enlist 时机在 createStatement/prepareStatement
- MySQL XA 实战代码 — MysqlXADataSource + MysqlXid + 2PC 完整 Java 示例

**可学习点**：JTA 3 层接口设计（用户层/服务器层/XA 资源层）、XAResource 与 2PC 配合的 5 步(start→work→end→prepare→commit)、Atomikos 延迟关联策略

**现代对应**：2025 年 JTA 基本被 Seata AT/TCC 和 RocketMQ 事务消息���代。XA 协议仍是 MySQL/Oracle 分布式事务底层标准，Atomikos 在金融级场景仍使用。

**现代对应**：Seata XA 模式 + MySQL 8.0 XA + Seata AT/TCC 替代 JTA

---

#### 第 16 章：Java 分布式事务整合

**核心主题**：Spring Transaction 抽象层从接口定义到 AOP 拦截到事务管理器实现的完整架构

**子主题清单**（14 个）：
- Spring 事务行为定义 — 边界(方法级别 AOP)、隔离级别(5 种映射 java.sql.Connection)、传播行为(7 种继承 EJB 规范)
- 传播行为全解析 — REQUIRED/SUPPORTS/MANDATORY/REQUIRES_NEW/NOT_SUPPORTED/NEVER/NESTED，每种对应 EJB 规范原文引用
- 回滚策略 — rollbackOn() 决定异常时是否回滚，默认拦截 Throwable；现代对应：@Transactional(rollbackFor=...) 声明式配置
- TransactionDefinition 接口 — getPropagationBehavior/getIsolationLevel/getTimeout/isReadOnly/getName 五方法
- TransactionAttribute 接口 — 继承 TransactionDefinition，增加 getQualifier(指定 TransactionManager Bean)和 rollbackOn()
- TransactionAttributeSource 三级 — XML 实现(NameMatchTransactionAttributeSource)、注解实现(AnnotationTransactionAttributeSource)、组合(CompositeTransactionAttributeSource)
- TransactionInterceptor 拦截 — invoke→invokeWithinTransaction→createTransactionIfNecessary→TransactionInfo 绑定 ThreadLocal
- AbstractPlatformTransactionManager 模板方法 — getTransaction(final)→doBegin/doCommit/doRollback(template 子类实现)
- JtaTransactionManager — JNDI 查找 java:comp/UserTransaction、doJtaBegin/doJtaCommit 状态检查
- DataSourceTransactionManager — 基于 JDBC Connection 的 setAutoCommit(false)/commit/rollback 本地事务
- JDBC 三种操作模式对比 — 本地事务(setAutoCommit→DML→commit)、XA(start→DML→end→prepare→commit)、JTA(UserTransaction.begin→DML→commit)
- 事务挂起恢复机制 — suspend()返回 Transaction，resume(Transaction)重新关联线程；现代对应：Spring PROPAGATION_REQUIRES_NEW 底层实现
- TransactionInfo ThreadLocal 绑定 — bindToThread 保存旧 TransactionInfo，事务结束后恢复，支持嵌套传播
- 传播 NESTED 实现 — Spring 独创，基于 JDBC 3.0 Savepoint 实现嵌套事务

**可学习点**：模板方法模式在事务管理器中的应用、TransactionInfo ThreadLocal 绑定与恢复支持嵌套传播

**现代对应**：Spring 6.1+ PlatformTransactionManager、Seata @GlobalTransactional 替代 JTA

---

#### 第 17 章：可靠事件队列分布式事务

**核心主题**：基于本地消息表 + Kafka 实现最终一致性的分布式事务方案

**子主题清单**（10 个）：
- 理论基础 — Dan Pritchett eBay BASE 论文《An Acid Alternative》(ACM Queue)，解释为何放弃 ACID 走向最终一致性
- MySQL Docker 环境 — docker pull mysql:5.7 + docker run -p 13306:3306
- 同库场景表设计 — users(amt_sold/amt_bought)+transactions 在同一 test_db；同库无需 tx_messages 表
- 独立库场景表设计 — user_db.users + tx_db.transactions + tx_db.tx_messages(本地消息表)分库
- Kafka Docker Compose — confluentinc/cp-kafka:7.3.2 + cp-zookeeper:7.3.2 双容器
- Kafka Topic 创建 — kafka-topics --create --topic transactions + producer/consumer 命令行验证
- tx_messages 核心机制 — 业务操作和消息写入在同一本地事务中提交，保证"业务成功=消息必定写入"
- 执行流程 — 业务操作→写入消息表(同一本地事务)→异步扫描发送→消费方处理→删除消息记录
- 失败重试+幂等 — 扫描程序轮询未发送消息，消费方 xid 去重或业务主键去重
- DTM 参考 — dtm.pub 本地消息表 + 2PC/3PC 对比文章

**可学习点**：本地消息表原子性保证、异步扫描+重试+幂等消费构成最终一致性三角基石

**现代对应**：Kafka 事务消息(executeInTransaction)+@Retryable；RocketMQ 事务消息(半消息+回查)是简化版；Debezium Outbox 是最新实践

---

#### 第 18 章：TCC 分布式事务原理和实现

**核心主题**：TCC 柔性事务核心思想 + Hmily 框架架构深度剖析

**子主题清单**（12 个）：
- TCC 概念起源 — Pat Helland 2007 论文《Life beyond Distributed Transactions》，Atomikos 注册商标，阿里程立 2008 引入
- 开源实现对比 — ByteTCC/Hmily(Dromara)/TCC-transaction(changmingxie)/TX-LCN(已不活跃)/Seata
- TCC 通用 AOP 实现模式 — @TCC 注解标记 Try 方法，Around Advice 拦截确认/取消调用
- Hmily 核心架构 — HmilyAutoConfiguration→AbstractHmilyTransactionAspect→HmilyGlobalInterceptor→HmilyTransactionHandlerRegistry
- HmilyRoleEnum 角色 — START/CONSUMER/PARTICIPANT/LOCAL/INLINE/SPRING_CLOUD 六角色驱动处理器选择
- 事务处理器链 — 按 Role 选择 Starter/Local/Consume/Participant 四种 Handler
- HmilyTccTransactionExecutor.preTry() — 创建 HmilyTransaction→存入 RepositoryStorage→创建 Participant→设置 Context(Action=TRYING/Role=START)
- 事务日志存储 — HmilyRepositoryStorage 通过 Disruptor 无锁事件总线异步写入，底层 MySQL 持久化
- 三张日志表 — hmily_transaction_global(全局事务)/participant(参与者)/undo(UNDO 信息)
- 事务上下文传播 — 通过 Feign/Dubbo/gRPC 传递 HmilyTransactionContext
- 异常处理 — try 成功→confirm(确认)、try 失败→cancel(补偿)、confirm/cancel 需幂等
- 角色驱动处理器选择模式 — 同一 @HmilyTCC 注解不同角色执行不同 Handler

**可学习点**：Disruptor 异步持久化避免事务日志成瓶颈、角色驱动处理器选择模式、Try-Confirm-Cancel 幂等性保证

**现代对应**：Seata TCC(@TwoPhaseBusinessAction+TCC Fence)推荐；Hmily 维护到 2.x；TX-LCN 已不活跃

---

#### 第 19 章：Alibaba Seata 架构和原理（上）

**核心主题**：Seata TC/TM/RM 三层+AT 模式全链路（DataSource 代理+UNDO 日志+全局锁）

**子主题清单**（12 个）：
- Seata 整体架构 — TC(事务协调器)、TM(事务管理器)、RM(资源管理器)三角色关系
- 四大事务模式 — AT(自动补偿/基于 ACID 关系型数据库)、TCC(手动二阶段)、SAGA(长事务补偿/1987 论文)、XA(标准 X/Open XA)
- AT 模式设计原理 — 一阶段：业务 SQL+undo_log 同本地事务提交；二阶段：提交异步化(删 undo_log)，回滚通过 undo_log 反向补偿
- BranchType 枚举 — AT/TCC/SAGA/XA 四种分支事务类型，控制 RM 选择
- SQLRecognizer 解析 — Antlr 解析 INSERT/UPDATE/DELETE/SELECT_FOR_UPDATE，自动识别 SQL 类型/表名/字段
- ResourceManager 四实现 — DataSourceManager(AT)/TCCResourceManager/SagaResourceManager/ResourceManagerXA，SPI 加载
- UndoLogManager 回滚日志 — SQLUndoLog(sqlType+tableName+beforeImage+afterImage)、TableMeta/ColumnMeta/IndexMeta
- DataSourceProxy 代理 — init 自动探测 JDBC URL/dbType/version，注册 ResourceManager 和表元缓存
- ConnectionProxy 代理 — checkLock(向 TC 发 GlobalLockQueryRequest)、commit(分支逻辑)、processGlobalTransactionCommit(flush undo_log+本地事务提交)
- executeAutoCommitFalse 核心流程 — 构建 beforeImage→执行业务 SQL→构建 afterImage→生成 undo_log
- 全局锁机制 — lockQuery RPC 向 TC 请求全局锁查询，LockConflictException 触发 retryPolicy 重试
- SeataAutoDataSourceProxyCreator — Spring AOP 自动创建 DataSource 代理

**可学习点**：AT 核心创新 undo_log 和业务 SQL 同本地事务提交解决 XA 锁占用；代理层级 DataSourceProxy→ConnectionProxy→StatementProxy；全局锁实现 AT 模式写隔离

**现代对应**：Seata 2.x AT 模式 + seata-spring-boot-starter 零配置，生产主流

---

#### 第 20 章：Alibaba Seata 架构和原理（下）

**核心主题**：Seata TCC 注解驱动+TCC Fence 防悬挂+RPC 通信

**子主题清单**（13 个）：
- TCC vs AT 对比 — AT 自动化(SQL 解析生成 undo_log)，TCC 手动化(业务编码 Try/Confirm/Cancel)
- @TwoPhaseBusinessAction 注解 — name/commitMethod/rollbackMethod/isDelayReport/useTCCFence/commitArgsClasses/rollbackArgsClasses
- TccActionInterceptor 拦截器 — 检查 RootContext.inGlobalTransaction→绑定 BranchType.TCC→委托 ActionInterceptorHandler.proceed()
- ActionInterceptorHandler.proceed() 五步 — 创建/获取 BusinessActionContext→初始化(xid/actionName/branchId)→执行 TCCFence 可选→上报 TC→恢复上下文
- BusinessActionContext 初始化 — xid/actionName/branchId/ACTION_START_TIME/COMMIT_METHOD/ROLLBACK_METHOD/HOST_NAME
- doTccActionLogStore 分支注册 — RPC 向 TC 申请 TCC 分支 ID，记录 Try 方法上下文元数据
- TCC Fence 防悬挂 — 基于 DB 分布式锁，prepareFence 插入记录保证 Try/Confirm/Cancel 互斥性和幂等性
- RemotingParser 四种实现 — LocalTCC/Dubbo/HSF/SofaRpc
- TCCResourceManager.branchCommit 调用链 — DefaultCoordinator(TC)→DefaultRMHandler→RMHandlerTCC→AbstractRMHandler.doBranchCommit→DefaultResourceManager→TCCResourceManager
- branchCommit 内部 — tccResourceCache 获取 TCCResource→反射调用 commitMethod→BranchStatus 判定
- Seata RPC 消息体系 — AbstractMessage→AbstractIdentifyRequest→RegisterRMRequest/GlobalLockQueryRequest/BranchCommitRequest/BranchRollbackRequest
- RMHandler 分发 — RMHandlerAT(基于 TC 全局锁)、RMHandlerTCC(基于 TCC Fence)
- Locker 三种实现 — DataBaseLocker(行锁)/RedisLocker(分布式锁)/FileLocker(文件锁)

---

### 4. RPC 域（第 21-22 章）

#### 第 21 章：RPC 微内核设计

**核心主题**：基于 Netty+JDK 动态代理+Future/Promise 从零构建 RPC 框架的核心设计

**子主题清单**（15 个）：
- 微内核五大模块 — 服务通讯(Netty/MINA/Grizzly)、消息序列化(Hessian/Kryo/JSON)、消息协议、负载均衡、服务路由
- Netty 网络框架 — 非阻塞 I/O、Reactor 模式、Boss/Worker EventLoopGroup；现代对应：Spring Cloud Gateway/Dubbo 基于 Netty
- Apache MINA — 统一 API(TCP/UDP)、可自定义线程模型；现代对应：较少使用
- Grizzly — 内存管理、I/O 策略、HTTP/WebSocket/Comet；**已不活跃**
- Proactor vs Reactor — Reactor(连接+IO 事件同一线程) vs Proactor(连接与 IO 分离)；现代对应：Netty Boss+Worker EventLoopGroup
- Hessian 序列化 — 跨语言(Java/Python/C++)，紧凑二进制格式；现代对应：Protobuf(跨语言)+Kryo(Java)
- Kryo 序列化 — Java 专用二进制序列化，高速小体积，支持深浅拷贝
- 消息协议设计 — 消息头(Header)+消息体(Payload)两段式结构
- InvocationRequest 属性 — requestId/serviceName/methodName/parameterTypes/parameters/metadata(TraceId/XID 透传)
- ServiceInvocationHandler — selectInstance→connect→sendRequest→createExchangeFuture→get()阻塞等待
- ExchangeFuture Promise 模式 — requestId 定位 Promise.setSuccess(result)唤醒调用方
- InvocationResponseHandler — SimpleChannelInboundHandler，channelRead0 中 Promise.setSuccess
- 负载均衡接口 — selectServiceProviderInstance()可扩展自定义算法
- 服务路由接口 — metadata 扩展中传递路由规则
- RPC Server/Client 架构图 — Decoder→InvocationDispatcher→ServiceInvoker→Encoder 请求环 + JDK 代理→Serializer→Channel→Future 调用环

**可学习点**：Future/Promise 异步转同步核心模式、微内核 metadata 扩展思想(序列化可插拔+负载均衡可扩展)

**现代对应**：Dubbo 3.x(微内核+SPI 插件化)、OpenFeign+LoadBalancer、gRPC-Java(ProtoBuf+HTTP/2)

---

#### 第 22 章：RPC 生态整合

**核心主题**：基于 SOFAJRaft 构建 CP 风格注册中心和服务发现客户端

**子主题清单**（12 个）：
- ServiceRegistry SPI — initialize/register/deregister/getServiceInstances/close 6 核心方法；现代对应：Nacos NamingService API
- DefaultServiceInstance 模型 — id(host-port)/serviceName/host/port/metadata 5 字段；现代对应：Nacos Instance 类
- ZK 实现注册中心 — 临时 ZNode 做心跳检测；现代对应：Nacos 临时实例+心跳续约
- ZK 实现服务发现 — Watcher 监听 ZNode 变化实时感知；现代对应：Nacos 长轮询监听
- SOFAJRaft 依赖 — jraft-core 1.3.12 + protobuf-java 3.22.4 + protobuf-maven-plugin 0.6.1；现代对应：grpc-spring-boot-starter
- Protobuf 协议 — ServiceInstanceRegistrationRequest/Response、ServiceInstancesQueryRequest/Response 4 消息类型；现代对应：gRPC 服务定义
- RpcServer — 注册不同 Message Java 类型处理器；现代对应：Netty channel pipeline
- JRaftServiceRegistry — Raft FSM 驱动注册中心，写操作经 Leader 状态机；现代对应：Nacos CP+JRaft
- 服务注册处理器 — RpcProcessor 处理注册请求，写入 Raft 集群日志；现代对应：Nacos Distro 同步
- 服务订阅机制 — 客户端可订阅服务变更事件；现代对应：Nacos EventListener
- 副本同步 — SOFAJRaft 内建日志复制自动同步；现代对应：Raft 日志复制

**可学习点**：注册中心本质是 CP 系统、协议分层设计(Protobuf 定义消息格式)、服务发现双模式(推/ZK Watcher vs 拉/客户端查询)

**现代对应**：Nacos CP(JRaft)+AP(Distro)、Spring Cloud LoadBalancer 服务发现

---

### 5. 配置中心域（第 23-24 章）

#### 第 23 章：分布式配置中心设计

**核心主题**：配置中心的中心化 vs 分布式存储方案+获取策略+集群部署

**子主题清单**（12 个）：
- 配置读多写少特征 — 缓存层优化读性能；现代对应：Nacos Config 本地缓存+长轮询
- 中心化存储(RDB) — JPA 实现 SQL 厂商无关性+多级缓存；现代对应：Nacos MySQL+内嵌 Derby
- 分布式存储 — etcd/ZK/Raft 集群存储；现代对应：etcd 存储 Nacos 元数据
- 同步获取配置 — Client 直接 HTTP 拉取全量配置；现代对应：Nacos configService.getConfig()首次拉取
- 异步更新回调 — 客户端注册监听器，服务端变更异步通知；现代对应：Nacos configService.addListener()长轮询
- 配置变更事件模型 — Body(JSON/XML/二进制)+Header(大小/分块/媒体类型/版本)；现代对应：Nacos ConfigChangeEvent
- 配置元数据 — 大小/分块/编码/序列化协议/创建时间/版本号；现代对应：Nacos MD5 版本校验
- Apollo 架构 — Config Service+Admin Service+Portal+Eureka 四层
- Nacos 配置中心 — KV 格式(properties/yaml/json)+命名空间+Group 隔离，2025 主流
- Consul 配置中心 — HashiCorp K-V 存储，ACL+版本管理
- ZK 配置存储 — ZNode 存储+Watcher 实时通知
- etcd 配置存储 — Raft 共识+K-V 存储，K8s 生态核心

**���学习点**：配置变更事件元数据设计、中心化(SQL 查询强)vs 分布式(高可用)取舍、长链接 vs 短链接折中

**现代对应**：Nacos Config(主流)+Apollo+etcd(K8s)；Spring Cloud Config 逐渐边缘化

---

#### 第 24 章：分布式配置客户端设计

**核心主题**：Spring Environment+PropertySource+@Value 动态刷新+Apollo vs Nacos

**子主题清单**（14 个）：
- Open API 设计 — 基于 REST(HTTP 协议版本化)，未来方向 HTTP/2+gRPC；现代对应：Nacos Open API v2
- 功能特性 — 发布/修改/删除/加载/列举配置+配置更新通知
- 非功能特性 — 缓存(Caching)/安全(ACL)/指标(Metrics)/日志(Logging)
- Spring Environment 抽象 — Profiles(运行时条件)+PropertySources(有序多源)+PropertyResolver(占位替换+类型转换)
- MutablePropertySources — CopyOnWriteArrayList 存储 PropertySource 保证优先级
- @Value 三种注入 — 字段注入/Setter 方法注入/方法参数注入
- 类型转换 — ConversionService+Converter SPI 自定义类型转换；现代对应：Spring Boot 自动配置转换器
- EnvironmentChangeEvent — 仅包含 changed keys(缺 oldValues/newValues)，设计缺陷分析；现代对应：RefreshScopeRefreshedEvent
- MicroProfile Config — ConfigSourceProvider+ConfigSource+@ConfigProperty+Converter SPI；现代对应：SmallRye Config
- Nacos Client 三种形态 — Nacos Client(直接 Open API)/Nacos Spring(@NacosValue+autoRefreshed)/Nacos Spring Boot(自动配置)
- @NacosValue vs @Value — @Value 不支持动态刷新，@NacosValue 通过 autoRefreshed 控制；核心区别
- Apollo 客户端架构 — apollo-client(基础)+apollo-client-config-data(Spring Boot 2.4+)二分发包
- Apollo 异步事件监听 — CachedThreadPool 实现 ConfigChangeListener 不互相阻塞；现代对应：Spring @Async 异步事件
- SpringValue 优化分析 — WeakReference 在 singleton scope 下不必要、@Value 缺少方法参数注入支持

**可学习点**：EnvironmentChangeEvent 只通知 key 不携带 value 的设计缺陷、PropertySource 有序链+PropertyResolver 占位替换是 Nacos Config 核心原理、注解不应绑定具体实现

**现代对应**：Nacos Config Client + Spring Cloud 2025 ConfigData 体系

---

### 6. 数据存储域（第 25-26 章）

#### 第 25 章：通用数据读写分离设计

**核心主题**：MySQL 主从复制 Docker 部署+三种读写分离策略

**子主题清单**（13 个）：
- MySQL 主从复制概念 — 源(Source)→副本(Replica)默认异步复制；现代对应：MySQL 8.0 Group Replication/InnoDB Cluster
- 横向扩展 — 写入集中源，读取分散到多副本提升吞吐量
- 数据安全性 — 副本上暂停复制进行备份不影响源；现代对应：MySQL Clone Plugin+快照备份
- 远程数据分发 — 远程站点通过本地副本加速读取；现代对应：多活+Region 化部署
- Docker 搭建 MySQL Master — server_id=1+log_bin+binlog_format=ROW+gtid_mode=ON
- Docker 搭建 MySQL Slave — server_id=2+read_only=ON+CHANGE MASTER TO+MASTER_AUTO_POSITION=1
- GTID 模式 — 基于 GTID 自动定位 binlog 位置比传统 position 更可靠
- 主从复制验证 — Master INSERT→Slave SELECT 确认数据一致；现代对应：pt-table-checksum 校验
- MySQL Connector/J 高可用 — LoadBalancedConnectionProxy 多主机故障转移+PropertyKey 90+连接属性(ha_loadBalanceStrategy/allowReplicaDownConnections)
- 方法级切换 — 方法上写死走哪个数据源，最基础方式
- 注解级切换 — @ReadOnly/@WriteOnly+AOP 动态选择；现代对应：@RoutingDataSource+AOP
- SQL 分析级切换 — SELECT 走读库/INSERT/UPDATE/DELETE 走写库；现代对应：ShardingSphere SQL 解析
- Redis 读写分离 — 设计思路与 DB 一致，Redis 主从复制延迟更低；现代对应：Redis Sentinel/Cluster 读写分离

**可学习点**：三种路由策略递进(方法硬编码→注解声明→SQL 语义分析)、读写分离核心抽象(路由层根据操作语义选择目标)

**现代对应**：ShardingSphere 读写分离(2025 标准方案)、MySQL InnoDB Cluster

---

#### 第 26 章：基于 ShardingSphere 实现数据分片和读写分离

**核心主题**：ShardingSphere 产品体系+插件化规则架构+数据源工厂

**子主题清单**（12 个）：
- MySQL JDBC 拦截器 — ConnectionLifecycleInterceptor/ExceptionInterceptor/QueryInterceptor 三拦截点；现代对应：ShardingSphere SQL 解析驱动
- Database Plus 设计哲学 — 构建异构数据库上层标准和生态，不做新数据库做上层增强
- Alibaba TDDL — 淘宝早期分库分表中间件，为 ShardingSphere 提供实践基础；现代对应：演进为 DRDS
- ShardingSphere-JDBC — 轻量级 Java 框架，JDBC 层增强，性能接近原生 JDBC；生产主力
- ShardingSphere-Proxy — 透明化数据库代理，支持 MySQL/PostgreSQL 协议，多语言接入
- 7 大功能矩阵 — 数据分片/分布式事务/读写分离/数据迁移/联邦查询/数据加密/影子库；5.x 全部支持
- YAML 配置体系 — YamlConfiguration+RuleConfiguration+YamlRuleConfigurationSwapper 双向转换 POJO⇔YAML；现代对应：DistSQL 动态配置
- ShardingSphereRule 接口 — getConfiguration()+getType() 插件化规则；现代对应：SPI 扩展规则引擎
- Mode 设计 — Standalone(H2/文件) vs Cluster(ZK/etcd/Nacos)；现代对应：Nacos 元数据中心存储规则
- DataSourceProperties — YAML 配置与 POJO 映射，DataSourcePoolCreator 创建连接池；现代对应：HikariCP+sharding 映射
- Router 架构 — 根据分片键将 SQL 路由到正确数据源，可插拔路由引擎；现代对应：ShardingSphere 5.x 可插拔
- HintManager 强制路由 — 绕过分片规则手动指定目标数据源

**可学习点**：JDBC 层拦截(SQL 拦截/解析/改写/路由/执行/结果归并)、配置体系分层设计(YAML→RuleConfiguration→Swapper)、存储模式集群化

**现代对应**：ShardingSphere 5.x JDBC+Proxy 混合架构 + Seata AT 分布式事务

---

### 7. 分布式缓存域（第 27-28 章）

#### 第 27 章：分布式缓存设计

**核心主题**：缓存基础理论+HTTP 客户端缓存+JSR-157 服务端缓存+多级缓存架构

**子主题清单**（14 个）：
- 缓存吞吐量指标 — QPS/TPS 定义，单机 1W-10W；现代对应：Caffeine 单机百万级 QPS
- 缓存命中率 — 50% 基准，布隆过滤器防穿透；现代对应：Caffeine W-TinyLFU 命中率更高
- LRU 淘汰策略 — Java WeakHashMap 被动淘汰(GC 决定)；现代对应：Caffeine W-TinyLFU > Guava LRU
- TTL 时间淘汰 — Time-To-Live + DelayedQueue 过期设计；现代对应：Redis EXPIRE/TTL
- HTTP 客户端缓存 — ETag+Last-Modified+If-None-Match+304；现代对应：Cache-Control + Service Worker
- Tomcat DefaultServlet 静态缓存 — ETag/Last-Modified 自动生成和条件请求处理；现代对应：Spring ResourceHttpRequestHandler
- Servlet Dispatcher 类型 — FORWARD/INCLUDE/REQUEST/ERROR/ASYNC 5 种
- JSR-107 javax.cache 标准 — CacheManager+Cache+Configuration 三层；现代对应：JCache 1.1+Caffeine 实现
- CompleteConfiguration 9 个配置项 — readThrough/writeThrough/statistics/JMX/listener/loader/writer/expiry
- 多级缓存实现 — CacheLoader+CacheWriter：内存→Redis→MySQL→FileSystem 逐级回退；现代对应：Caffeine 一级+Redis 二级+DB 三级
- 组合缓存 Composite Caches — 多个 Cache 实例组合成逻辑缓存；现代对应：Spring Cache 多层缓存配置
- 同步多级缓存 — 基于缓存条目事件监听实现一级→二级回退；现代对应：Redis Pub/Sub 缓存失效通知
- Java Interceptor JSR-38 — @InterceptorBinding 标注注解体系；现代对应：Spring AOP 注解拦截
- Spring Cache 整合 — @Cacheable/@CachePut/@CacheEvict + Spring EL 条件缓存；现代对应：Spring Cache+Caffeine+Redis 多级整合

**可学习点**：多级缓存回退链路(CacheLoader/CacheWriter 级联)、HTTP 客户端缓存协议实现、JCache vs Spring Cache 设计哲学差异

**现代对应**：Caffeine(本地)+Redis(分布式)+Spring Cache 注解整合

---

#### 第 28 章：分布式缓存实战

**核心主题**：Redisson RLock 6 种锁+分布式 Session+幂等性

**子主题清单**（13 个）：
- 基于 Redis 实现 AQS 组件 — Redisson 在 Redis 上重新实现 J.U.C Lock 和 Semaphore
- Bulkhead 并发数限流 — 限制同时执行请求数；现代对应：Resilience4j Bulkhead/Sentinel 信号量隔离
- RateLimiter 频率限流 — Redisson 内置实现限制单位时间请求速率；现代对应：Sentinel 流控+令牌桶
- TimeLimiter 时长限流 — 限制单个请求最大执行时间，超时熔断；现代对应：Sentinel 慢调用比例熔断
- CircuitBreaker 失败熔断 — 失败率超阈值熔断防级联故障；现代对应：Sentinel 熔断降级
- RedissonLock 互斥锁 — 重入但不支持 Condition，基于 SET NX EX+Lua 脚本
- RedissonFairLock 公平锁 — Redis Zset 排队按请求顺序获取
- ReadWriteLock 读写锁 — 读读并发/读写互斥/写写互斥，支持重入
- RedissonSpinLock 自旋锁 — 不断重试适合锁持有时间极短场景
- RedissonSemaphore 信号量 — Redis Key 保存 Permit 状态，acquire -1/release +1
- 分布式 Session — 中心化(Redis/ZK/MySQL) vs 去中心化(Gossip)；现代对应：Spring Session+Redis
- Spring Session+Redis Hash — Filter 包装 HttpServletRequest→Redis Hash 存储；现代对应：Spring Session Data Redis
- AOP+Session 幂等性 Token — 首次请求生成 Token 返回，第二次带 Token 被拦截判重

**可学习点**：分布式限流 4 维度(Bulkhead/RateLimiter/TimeLimiter/CircuitBreaker)、Redisson RLock 与 J.U.C Lock 接口兼容是最大亮点、幂等性 Token 机制三环节(生成+存储+透传)

**现代对应**：Redisson(分布式锁/RateLimiter)+Sentinel(流控/熔断)+Spring Session Data Redis+Token 幂等性

---

## 三、过时技术 → 现代替代映射表

| stage-2 过时技术 | 现代替代 | 保留的主题 |
|---|---|---|
| ZK 作为注册中心（第 12 章） | **Nacos** | 服务注册发现 |
| ZK 分布式锁（第 12 章） | **Redisson** | 分布式锁 |
| EJB 3.x（第 14 章） | **Jakarta EE** | 企业级事务 |
| Grizzly（第 21 章） | **Netty** | RPC 网络框架 |
| Spring Cloud Config Server（第 23 章） | **Nacos Config / Apollo** | 配置中心 |
| Hmily TCC（第 18 章） | **Seata TCC** | TCC 柔性事务 |
| TX LCN（第 18 章） | **Seata** | 分布式事务 |
| JTA/XA（部分场景）（第 15-16 章） | **Seata AT/TCC** | 分布式事务 |
| Hessian 序列化（第 21 章） | **Protobuf / Kryo** | RPC 序列化 |
| Spring Cloud Sleuth（stage-1 第 17 章） | **Micrometer Tracing + SkyWalking** | 链路追踪 |

**核心原则**：stage-2 的所有主题都在 sca-lab 中有对应实践，过时技术用现代替代品学习同样主题。

---

## 三之补充：关键现代选择的技术理由（为什么选 X 而不是 Y）

### 从 Eureka/ZK → Nacos

**为什么 Nacos 替代 Eureka 和 ZK 做注册中心**：
- Eureka 是纯 AP（最终一致），ZK 是纯 CP（强一致），但**生产场景需要两者切换**：服务注册（高可用优先）用 AP，配置管理（一致性优先）用 CP
- Nacos 2.x 通过 **CP(JRaft) + AP(Distro) 双协议** 同时满足两种需求，一个组件覆盖两种场景
- 此外，Nacos 还内置配置中心（替代 Spring Cloud Config + Apollo），进一步减少组件数量

### 从 JTA/XA → Seata AT

**为什么 Seata AT/TCC 替代 JTA 和 XA 做分布式事务**：
- XA 二阶段提交的两个问题：**Prepare 阶段锁资源直到 Commit**（长时间独占，高并发不可接受）+ **需要支持 XA 协议的数据库**（限制数据库选型）
- Seata AT 一阶段**业务 SQL+UNDO 日志在同一本地事务中提交**，立即释放锁资源，二阶段异步快速完成，**锁持有时间从 XA 的整个二阶段变成一次提交的时间和**
- Seata TCC 适合"需预留资源"场景（库存扣减/账户冻结），提供**业务级两阶段**而不依赖数据库协议

### 从 Ribbon → Spring Cloud LoadBalancer + 手写 WeightedResponseTimeRule

**为什么 LoadBalancer 替代 Ribbon**：
- Netflix Ribbon 已进入维护模式（Spring Cloud 2020.0 起移除），不再接受新功能
- Spring Cloud LoadBalancer 基于 **Reactor 响应式**，与 Spring WebFlux/Spring Cloud Gateway 天然整合
- 但 LoadBalancer**缺少数 WeightedResponseTimeRule 算法** — 这正是 sca-lab 重量平衡模块手写实现的填补空白

### 从 Spring Cloud Config → Nacos Config

**为什么 Nacos Config 替代 Spring Cloud Config**：
- Spring Cloud Config 需要独立部署 Config Server，且默认依赖 Git 存储配置不适合动态变更频繁的场景
- Nacos Config 一体化为配置中心 + 注册中心，**长轮询 + 本地快照 + MD5 校验** 实现高性能配置推送
- 此外 Nacos Config 原生支持 Gray Release（灰度发布）和标签路由，Apollo 也有类似功能但需要独立部署

### 从 Sleuth → Micrometer Tracing + OpenTelemetry + SkyWalking

**为什么 Micrometer Tracing 替代 Sleuth**：
- Sleuth 在 Spring Cloud 2022.0 已正式 EOL（End of Life），不再维护
- Micrometer Tracing 通过 **Observation API** 统一 metrics + tracing + logging，消除了之前需要分别配置 Micrometer（metrics）和 Sleuth（tracing）的复杂性
- **实际生产更推荐 SkyWalking**：
  - 字节码增强无需代码侵入，覆盖 Dubbo/Spring Cloud/gRPC/Redis 等上百种组件
  - 内置性能分析（thread dump/profiling）和拓扑发现，远超过 Sleuth+Zipkin 的追踪能力
- 但仍需学 Micrometer Tracing 理解 **W3C Trace Context 传播机制** 和 **Trace/Span API 设计模式**

### 从 ZK 分布式锁 → Redisson RLock

**为什么 Redisson 替代 ZK 做分布式锁**：
- ZK 基于临时顺序节点实现锁，**写操作需要经 Leader（CP）**，吞吐量受限于单 Leader 写入性能
- Redisson 基于 Redis **SET NX EX + Lua 脚本**，单个 Redis 实例可支撑 10W+ QPS，**性能高 10x**
- RLock 接口**完全兼容 J.U.C Lock**，代码迁移成本极低

### 从 LRU → Caffeine W-TinyLFU

**为什么 Caffeine 替代 Guava/WeakHashMap**：
- Guava ConcurrentLinkedHashMap 基于 LRU，**存在"历史数据锁死缓存"问题**：偶发流量高峰会淘汰真正热点数据
- Caffeine 的 **W-TinyLFU** 结合了 LRU（短期）和 LFU（长期），通过 **Count-Min Sketch 近似统计频率**，近最优命中率
- 单机百万级 QPS，比 Guava 快 2-3x

### 从手工读写分离 → ShardingSphere-JDBC

**为什么 ShardingSphere 替代手工 Annotation 方案**：
- 手工 @ReadOnly/@Write 注解方案：**每个 Dao 方法都需打注解**，修改成本高，且无法处理"方法内读写混合"场景
- ShardingSphere-JDBC **SQL 解析引擎自动识别 SELECT/INSERT/UPDATE/DELETE**，实现零侵入
- 同时内置分库分表、数据加密、影子库等功能矩阵，**一个组件解决多个数据层需求**

### 跨章交叉引用索引

| 主题 | 首次出现 | 详细展开 |
|---|---|---|
| Seata AT 模式 | 第 15 章 JTA/XA 现代对应 | **第 19 章** Seata 架构和原理（上） |
| Nacos CP(SOFAJRaft) | 第 07 章 Nacos Raft 运用 | **第 05-06 章** SOFAJRaft 实现+架构 |
| Distro AP 协议 | 第 08 章 Distro 算法 | **stage-1 nacos 模块** D 类原理 |
| 共识理论 Raft | 第 03 章 Raft | **第 05-06 章** SOFAJRaft 实现 |
| TCC 柔性事务 | 第 18 章 TCC 原理 | **第 20 章** Seata TCC 模式 |
| 配置长轮询 | 第 23-24 章 | **stage-1 nacos 模块** nacos Config 原理 |

---

## 四、对 sca-lab 的影响（需新增模块）

基于 stage-2 梳理，sca-lab 需要新增 **3 个模块**：

| 新增模块 | 来源章节 | 核心内容 |
|---|---|---|
| **seata 模块** | 第 15-20 章 | Seata AT(UNDO+全局锁)/TCC(Fence)/SAGA/XA 四种模式 + 生产级集成 |
| **shardingsphere 模块** | 第 25-26 章 | ShardingSphere-JDBC 分库分表+读写分离+数据加密 |
| **redis 模块** | 第 27-28 章 | Redisson 分布式锁/限流/Session/幂等 |

**现有模块补充**：

| 现有模块 | 补充来源 | 补充内容 |
|---|---|---|
| **nacos 模块** | 第 07-08、23-24 章 | Nacos CP(SOFAJRaft)+AP(Distro) 双协议原理、Spring Environment 整合、@Value 动态刷新对比 |
| **dubbo 模块** | 第 21-22 章 | RPC 微内核设计(Netty+序列化+LB+JDK 代理)、SOFAJRaft 注册中心 |

**middleware-lab 补充**：

| 子项目 | 补充来源 | 补充内容 |
|---|---|---|
| **raft-impl** | 第 03、05-06 章 | Raft 完整算法 + SOFAJRaft AppendEntries 全链路(~600 行源码) |
| **two-phase-commit** | 第 15-16 章 | MySQL XA 2PC 完整手写 + JTA 规范 |
| **distributed-lock** | 第 12、28 章 | ZK 临时顺序节点锁 + Redisson RLock 算法 |

---

## 五、待 review 检查点

1. **功能域归类是否合理** — 7 个功能域的划分是否准确？
2. **子主题清单是否完整** — 每章的子主题是否有遗漏？
3. **现代对应是否准确** — 过时技术 → 现代替代的映射是否正确？
4. **新模块规划是否合理** — seata/shardingsphere/redis 3 个新模块是否合适？
5. **源码索引状态** — seata/shardingsphere/sofa-jraft/dubbo/rocketmq/zookeeper/redisson 7 个源码是否已建立 MCP 索引？
6. **与 stage-1 的衔接** — seata/rocketmq/dubbo 在 stage-1 已列为"stage-2 内容"，stage-2 梳理是否覆盖？

如有调整意见，请直接指出，我会更新文档。
