# Nacos 集群部署 - CP/AP 双模式 + 容量规划 + 生产运维

## 一、为什么需要集群

单机 Nacos 的 Derby 存储重启丢数据、服务挂了注册中心就没了。集群解决三个问题:

- **高可用**: 任何节点挂了,其他节点继续服务
- **数据持久**: MySQL 外置存储(Config) / JRaft log+snapshot(Naming 持久实例)
- **水平扩展**: 增加节点数承载更大规模

## 二、为什么是 3 节点

Nacos 集群节点数遵循分布式系统经典公式: `节点数 = 2f + 1`(容忍 f 个节点同时故障)

| 节点数 | 容忍故障数 | 可用性 |
|---|---|---|
| 1 | 0 | 挂了就全挂 |
| 2 | 0 | 挂 1 个无法形成多数派 |
| 3 | 1 | 挂 1 个仍正常 |
| 5 | 2 | 挂 2 个仍正常 |

**3 是生产最小推荐**。Naming 持久实例走 CP(JRaft),需要多数派;临时实例走 AP(Distro),不需要多数派但节点太少会导致 sync 延迟大。

## 三、三层端口架构(源码级)

每个 Nacos 节点开放三个端口,各自独立限流/监控:

```
Nacos Process
├── HTTP + gRPC 入口  (8848)   ← 客户端 HTTP API + gRPC unary(SDK)
├── GrpcSdkServer     (9848)   ← 客户端 SDK gRPC 连接(注册/发现/订阅/推送/配置)
├── GrpcClusterServer (9849)   ← 集群节点间通信(Distro sync / 成员心跳)
└── RaftServer         (7848)  ← JRaft 专用(Leader 选举 / 日志复制)
```

端口偏移设计(D-05): 运维只需配 `server.port=8848`,gRPC 和 Raft 端口自动推算(+1000/+1001/-1000)。

**为什么隔离**: 客户端流量(9848)突然暴增不影响集群间同步(9849)和 Raft 共识(7848)。Raft 对网络延迟最敏感(100ms+ 可能触发 Leader 切换)。

## 四、Naming vs Config 的集群同步(源码级)

Nacos 集群中 Naming 和 Config 用**完全不同的同步协议**:

| 维度 | Naming 临时实例 | Naming 持久实例 | Config 配置 |
|------|---------------|---------------|------------|
| 同步协议 | Distro V2 (AP) | JRaft (CP) | AsyncNotify (AP) |
| 同步内容 | Client 完整数据(publishers + revision) | Raft log 复制 | 通知(dataId + group + ts) |
| 同步方向 | 负责节点 -> allMembersWithoutSelf | Leader -> 多数派 | 发布节点 -> allMembers(含自己) |
| 数据源 | 纯内存 | JRaft log + snapshot(zip) | 共享 MySQL |
| 失败处理 | verify 反熵(5s) | Raft 重选 Leader | 无限重试(二次方退避至 49.5s) |
| 重启恢复 | 客户端重新注册 | 从 snapshot 加载 | DumpService 从 MySQL 全量加载 |

> **常见误解**: "配置中心走 Raft" -- **错误**。Config 不走 Raft,走 AsyncNotify(通知+从 MySQL 拉取)。Raft 只用于 Naming 持久实例(ephemeral=false)。

### Distro 同步参数(D-09)

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `syncDelayMillis` | 1000ms | sync 延迟合并窗口(DistroDelayTask) |
| `verifyIntervalMillis` | 5000ms | verify 反熵检查间隔 |
| `loadDataRetryDelayMillis` | 30000ms | 新节点加载数据失败重试间隔 |

### DistroMapper 路由(D-09)

简单取模: `Math.abs(tag.hashCode()) % serverCount`。**不是一致性哈希**。节点变更时几乎所有 tag 重新分配,但 verify 反熵(5s)能快速修复。

## 五、集群成员管理(源码级,D-13)

### 成员发现三种模式

| 模式 | 发现机制 | 适用场景 |
|------|---------|---------|
| standalone | 单成员(自己) | 开发测试 |
| file(cluster.conf) | 读取文件 + WatchFileCenter 监听变更 | 传统运维(固定 IP) |
| address-server | HTTP GET 地址服务器,每 5s 轮询 | 云原生(K8s 动态扩缩容) |

### 心跳检测

- **MemberInfoReportTask**(每 2s): 轮询一个其他节点,报告自己元数据。成功 -> UP;失败 -> SUSPICIOUS(+1 失败计数)
- **UnhealthyMemberInfoReportTask**(每 5s): 遍历所有非 UP 节点,主动探测尝试恢复

### 状态转换

```
探测成功: 任何状态 -> UP(failAccessCnt 重置为 0)
探测失败: 任何状态 -> SUSPICIOUS(failAccessCnt +1)
         failAccessCnt > 3 或 "Connection refused" -> DOWN
```

### MembersChangeEvent 传播

节点加入/离开或状态变化 -> 发布 MembersChangeEvent -> DistroMapper 重建 healthyList -> 重新计算所有 tag 的负责节点。

## 六、容量规划

| 规模 | 服务实例数 | 配置项数 | 建议内存 | 建议 CPU | 推荐节点数 |
|---|---|---|---|---|---|
| 小型 | <1,000 | <500 | 2GB | 2 核 | 3 |
| 中型 | <10,000 | <2,000 | 4GB | 4 核 | 3 |
| 大型 | <100,000 | <10,000 | 8GB | 8 核 | 5 |
| 超大型 | >100,000 | >10,000 | 16GB+ | 16 核+ | 7+ |

**关键瓶颈**:
- Naming 瓶颈在 gRPC 连接数 + Push 推送量(每个 Service 变更推送所有订阅者)
- Config 瓶颈在 MySQL IO(每次发布写 DB + dump 到内存缓存)
- 集群间 Distro sync 的网络带宽(大集群下 Client 数据同步量可观)

## 七、生产部署

### 存储选择

- **单机**: Derby(内嵌,重启丢失)
- **集群**: MySQL 8.0(外置,所有节点共享)。`spring.sql.init.platform=mysql`（2.3.0 推荐；旧的 `spring.datasource.platform` 已 deprecated）
- **Naming 持久实例**: 不依赖 MySQL,靠 JRaft log + snapshot(zip)

### JVM 参数

```bash
# 3 节点、<10,000 实例
-server -Xms2g -Xmx2g
-XX:MaxMetaspaceSize=256m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
```

### 网络要求

| 端口 | 用途 | 必须开放 |
|------|------|---------|
| 8848 | HTTP API + gRPC 入口 | 客户端 -> Nacos |
| 9848 | GrpcSdkServer(SDK 连接) | 客户端 -> Nacos |
| 9849 | GrpcClusterServer(集群间) | Nacos -> Nacos |
| 7848 | RaftServer(JRaft) | Nacos -> Nacos |
| 3306 | MySQL | Nacos -> MySQL |

### 滚动升级

1. 逐个升级(`nacos-1 -> nacos-2 -> nacos-3`)
2. 每个节点升级期间,另外 2 个节点继续服务
3. AP 路径(Distro): 不受影响(异步 sync)
4. CP 路径(JRaft): 如果 Leader 被升级 -> 自动触发选举 -> 新 Leader 接管(秒级中断)
5. Config: 不受影响(AsyncNotify + MySQL 共享,节点恢复后 DumpService 从 MySQL 全量加载)

### 新节点加入流程(D-09/D-13)

```
1. 新节点启动 -> MemberLookup 发现集群成员
2. DistroProtocol.startDistroTask:
   -> startLoadTask: 向其他节点 query/snapshot 拉取负责的 Client 数据
   -> isInitialized = false(不对外服务,直到 load 完成)
   -> load 成功 -> isInitialized = true
3. startVerifyTask: 每 5s 反熵检查
4. MembersChangeEvent -> DistroMapper 重建 healthyList -> 新节点接管部分 Service

新节点从启动到数据完整 = loadTime + 至少一个 verifyInterval(5s)
```

## 八、当前学习环境

当前用 standalone 模式(`MODE=standalone`),Derby 存储。生产集群需要:
- 3 个独立 Nacos 容器/进程
- MySQL 8.0 外部存储(Config 用)
- `cluster.conf` 配置文件(Naming 用)

集群部署是**架构理解性内容**,不要求搭建真实集群。但理解集群工作原理是 B1(ServiceRegistry)、B2(配置监听)、C3(Distro 协议)的基础。
