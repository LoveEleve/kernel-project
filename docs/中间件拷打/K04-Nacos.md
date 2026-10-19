# K04 — 组件深度拷打：Nacos

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：注册发现（Client 中心模型）、Distro（AP）、JRaft（CP）、gRPC 连接模型、配置中心（推拉结合）、优雅停机
> 依据：nacos-lab（A-01~A-10 + B + D-01~D-14 源码解读/18 题）**Nacos 2.3.0** 语料 + xhs-project 全量用法

---

## 问题

Nacos 在项目里承担什么？注册和配置两条链路分别怎么工作？2.x 和 1.x 有什么本质区别？CP 还是 AP？服务下线怎么保证流量不丢？

---

## 面试可讲版（五段式）

### ① 架构与数据模型（源码核心）

**四层 + 两子系统**：
```
API（接口契约：NamingService 53 方法 / ConfigService 12 方法）
→ Client SDK（gRPC 通信 + 本地缓存 + redo）
→ Server 基础设施（gRPC Server + ConnectionManager + 集群管理）
→ 一致性层（Distro=AP / JRaft=CP）
Naming：内存存储 + Distro 同步 + gRPC 推送 ｜ Config：DB 存储 + AsyncNotify + 双入口
```

**2.x 的模型革命（Client 中心）**：实例数据从"挂在 Service 下"改为"**挂在 Client（连接）上**"：
- 1.x 故障清理 O(服务数×实例数)、同步 O(N Service)；2.x 清理 **O(1)**（连接断开即整 Client 清理）、同步 O(M Client)
- 查询变慢 → 用 `ClientServiceIndexesManager` 建 `Service → Set<ClientId>` 索引补偿（空间换时间）

**接口设计哲学**：Naming 的 53 个方法 = 5 种核心操作（注册/注销/查找/LB/订阅）在 groupName/clusters/healthy/subscribe 四维度的笛卡尔积；Config 12 个方法 = 领域本身简单（无层次/无 LB/无分页）。

### ② 一致性：Distro（AP）与 JRaft（CP）

**Distro 协议（临时实例，AP）**——推拉结合 + 反熵：
```
写：ClientChangedEvent → isResponsibleClient（DistroMapper：hashCode(tag)%serverCount，简单取模非一致性哈希）
   → DistroDelayTask（1s 合并）→ 异步推给 allMembersWithoutSelf
读/修复：verify pull 每 5s 发 clientId+revision → 不一致 → 全量 sync（反熵）
新节点：snapshot 批量加载；脑裂恢复：diff 合并（InstancePublishInfo.equals 逐条比，最后收到者胜出）
```

**CP/AP 双轨（一行三元路由）**：
```
ephemeral（临时实例）→ AP（Distro，允许短暂不一致）
persistent（持久实例）→ CP（JRaft：多数派复制 + log/snapshot 持久化）
```
**注册中心主路径选 AP**（不允许不可用）；基础设施（DB 节点/DNS）才需 CP。

**revision 的边界（常考陷阱）**：revision 是**内容哈希/递增计数**——"相同"不代表强一致；verify 只能在分区合并后**检测并修复**，真正防脑裂靠 CP。

### ③ 健康检查与推送（2.x 连接模型）

| | 1.x（HTTP 时代） | 2.x（gRPC 连接模型） |
|---|---|---|
| 健康检查 | 应用层心跳（5s POST），服务端定时扫描，删除延迟 30s | **连接驱动**（keep-alive 传输层，断开即删，毫秒级） |
| 兜底 | —— | `ClientBeatCheckTaskV2`：15s 标记不健康 + 发 ServiceChangedEvent（复用推送体系闭环），30s 删除 |
| 推送 | UDP（不可靠）+ HTTP 长轮询 | gRPC bi-di stream |

**推送合并（PushDelayTask）**：ServiceChangedEvent → 延迟 500ms 合并——"push to all"胜出，否则客户端集合取**并集**、lastProcessTime 取**最早**（保证首次变更后 500ms 必触发）；推送前做**保护阈值过滤**（不健康实例过多时只推健康集）。

### ④ 生产口径（配置中心 + 优雅停机）

**配置中心（推拉结合 + 双确认）**：
- 服务端：ConfigPublishRequestHandler → DB → ConfigDataChangeEvent → AsyncNotifyService（自己 Dump + 其他节点 gRPC 同步）→ 缓存 → gRPC 推送
- 客户端：收到推送标记 `isConsistentWithServer=false` → executeConfigListen → **ConfigBatchListenRequest 比对 MD5** → 变更的再拉内容；`setContent 不自动标记 true`（下一轮确认——**双确认**）；5s 周期兜底
- 集群同步：二次方退避（failCount² × 1000ms，7 次后稳定 49.5s）**无限重试**；Config=共享 DB"通知+拉取"，Naming=内存"Push 数据"——**两条链路的存储模型不同**

**xhs 用法**：
- 配置导入：`spring.config.import: optional:nacos:my-xhs-common.yaml?group=DEFAULT_GROUP&refreshEnabled=true`（optional 容错 + 动态刷新）
- 16 个服务 yml 统一（全站 19 个服务模块）：`server-addr=192.168.0.142:18848`、`namespace=my-xhs`、`metadata.zone=${MYXHS_ZONE:defaultZone}`
- 热更新：`@RefreshScope`（ChaosProperties——混沌工程故障开关动态生效）

**优雅停机（xhs 时序修正版）**：
```
SIGTERM → ContextClosedEvent：
  a. nacosServiceRegistry.deregister（注销实例；失败不阻塞——TTL 兜底）
  b. sleep 10s 等列表传播（期间仍可服务——避免"摘除未传播"窗口）
  c. 执行 ShutdownHook（Counter Buffer 刷盘等）
→ web 停止接新请求 + 排空（lifecycle ≤30s）→ destroyBeans → JVM 退出
K8s：terminationGracePeriodSeconds=60 > 10+30；preStop 兜底
```

### ⑤ 拷打追问（面试官视角）

1. **"注册是同步还是异步？"** 调用同步（等 gRPC response）；数据生效异步（事件链→索引→PushDelayTask→推送）；失败靠 **redo**（write-ahead：发送前先缓存 registered=false，成功标记 true，3s 周期重试）。
2. **"2.x 为什么比 1.x 强？"** 连接即生命周期：健康检查零开销（keep-alive）、断开即删（毫秒级 vs 30s）、推送走长连接（vs UDP/长轮询）；代价是连接数（百万连接的内存/文件描述符管理）。
3. **"Distro 为什么用简单取模不是一致性哈希？"** 每个 Client 按 tag 路由到责任节点；节点数变化时路由重算 + **verify 反熵兜底**（简单换可靠修复）。
4. **"配置变更怎么到客户端？"** 服务端 gRPC 推送"变更通知"→ 客户端拉取并 MD5 比对（推拉结合）——推送只做通知，内容以拉取为准（防丢/防乱）。
5. **"Nacos 挂了服务还能调吗？"** 能——客户端本地缓存（ServiceInfoHolder）+ 定时刷新；短时不可用不影响已有路由；配置有 snapshot 兜底。
6. **"怎么防止流量打到已下线实例？"** 优雅停机（先注销+等传播再停服务）+ 调用方负载均衡剔除 + 兜底重试；K8s 用 preStop。
7. **"持久实例和临时实例区别？"** 临时=AP/Distro/连接断开即删（服务发现主用）；持久=CP/JRaft/不随连接消失（基础设施/DNS 场景）。
8. **"配置回滚/灰度怎么做？"** 历史版本一键回滚（DB 保留）；灰度用 beta/gray 规则（IP/标签维度），发布到部分实例验证后再全量。

### ⑥ 话术

> "Nacos 两个角色：注册中心 + 配置中心。注册是 2.x 连接模型——实例挂在 gRPC 连接上，断开即删毫秒级清理；一致性是双轨：临时实例走 Distro（AP，1s 推送 + 5s verify 反熵），持久实例走 JRaft（CP）。配置是推拉结合：推送通知 + 客户端拉取 MD5 比对，5s 兜底。项目里 16 个服务 yml 统一接入（全站 19 个服务模块；namespace 隔离 + zone 元数据）；优雅停机是先注销、等 10s 传播、再停 web 排空——配合 K8s preStop 和 60s grace period，把'摘除未传播'窗口清零。"

---

## 生产陷阱（Nacos 6 条）

| # | 陷阱 | 根因与修复 |
|---|---|---|
| 1 | **注册成功≠可调用** | 服务端注册与调用方感知有窗口——推送 500ms 合并 + 客户端缓存刷新；压测/发布要留传播时间 |
| 2 | **下线实例仍被调用** | 直接 kill 不等传播——优雅停机（注销+sleep+排空）是标准动作 |
| 3 | **心跳误判/实例闪断** | keep-alive 参数与网络抖动——调 keepAliveTime/Timeout；ClientBeatCheckTaskV2 兜底 |
| 4 | **配置改了没生效** | `refreshEnabled`/`@RefreshScope` 未配；或发布未走 Nacos（本地缓存覆盖）——检查双确认链 |
| 5 | **集群节点数据不一致** | verify 间隔窗口/DistroLoadDataTask 未完成——等加载完成 + 反熵补偿 |
| 6 | **Bootstrap 配置方式废弃** | `spring.cloud.nacos.config.bootstrap` 旧法——新法 `spring.config.import`（xhs 已用） |

---

## 发散追问地图（横向）

- **对比**：Eureka（纯 AP，已停更）/ ZK（CP，临时节点+watch）/ Consul（CP+健康检查）/ etcd（CP+Raft）——选型维度（一致性/健康检查模型/生态）
- **2.x 连接**：gRPC 长连接管理（连接数/内存/fd）；连接与实例生命周期绑定
- **集群**：3/5 节点部署；JRaft 端口 7848；address-server 发现；集群间同步（Config 通知 vs Naming Push）
- **安全**：鉴权（token/用户名密码）、命名空间隔离、配置加密（cipher）
- **生态**：Spring Cloud Alibaba（SCA）自动装配；Prometheus SD（A-09）；K8s 场景（Nacos vs CoreDNS）
- **配置治理**：灰度（beta/gray）、历史回滚、监听风暴（大配置+多监听者）

---

## 面试官评分点

**高级开发级**：注册/配置两条链路讲得清；2.x 连接模型 vs 1.x 心跳；CP/AP 双轨；知道自己项目的 namespace/优雅停机做法。
**架构师加分**：Client 中心模型的 O(1) 清理与索引补偿的取舍；Distro 推拉+反熵设计；revision 不等于强一致的边界；配置推拉结合与双确认；优雅停机窗口与 K8s 配合的时序。
**危险信号**：说不清 2.x 与 1.x 区别；以为注册中心必须 CP；配置变更只会说"推送"不谈拉取比对；下线只会 kill；Distro 说成一致性哈希。

---

## 本项目真实证据

**xhs-project**：
- 16 个服务 yml 统一：`server-addr=192.168.0.142:18848` + `namespace=my-xhs` + `metadata.zone=${MYXHS_ZONE:defaultZone}`（全站 19 个服务模块）
- 配置导入：`optional:nacos:my-xhs-common.yaml?group=DEFAULT_GROUP&refreshEnabled=true`（新法 `spring.config.import`，Bootstrap 废弃口径）
- `GracefulShutdownListener`（注销→等 10s→ShutdownHook→排空；失败 TTL 兜底；K8s preStop + grace 60s）
- `ChaosProperties`（@RefreshScope 热更新——混沌开关）

**nacos-lab（Nacos 2.3.0 基线）**：
- D-01~D-13 源码解读（Naming/Config SDK、gRPC 连接、注册事件链、Push/健康检查、Distro V2、CP/AP、配置发布/同步）+ D-14 面试 18 题
- A-01~A-10 实验（注册发现/配置中心/动态刷新/健康检查/命名空间/灰度回滚/集群/Prometheus SD/Bootstrap 废弃）
- REVIEW-2026-10-05（1682 引文审计 + 逐篇深审修复）

---

## 版本与来源

Nacos **2.3.0**（源码语料 `code/spring/nacos-2.3.0` + nacos-lab）；官方文档；xhs-project 全量配置与优雅停机代码。

## 真实性说明

Nacos 机制（Distro/JRaft/连接模型/推拉结合）为 2.3.0 源码核验结论（nacos-lab D 类逐篇审过）；xhs 用法（16 服务 yml 地址/namespace/优雅停机时序/ChaosProperties）为代码事实。
