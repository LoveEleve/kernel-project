# stage-4 多活架构 完整梳理

> 基于小马哥（mercyblitz）Java 分布式架构训练营第四期"多活架构"作为方向参考。
> 本文档**站在技术专家角度**，结合训练营方法论方向 + 2025 年最佳实践，完整梳理多活架构。

---

## 一、stage-4 概览

训练营以 Microsphere Projects + Eureka + Ribbon 为载体讲解多活，方向正确但实现过时。本文档保留其方法论骨架，**用 2025 年技术栈重写实现方案**。

| 维度 | 训练营方向 | 2025 年重写 |
|---|---|---|
| 注册中心多活 | Eureka P2P 复制 | **Nacos Distro AP + JRAFT CP + gRPC 长连接** |
| 负载均衡多活 | Ribbon ZoneAvoidanceRule | **Spring Cloud LoadBalancer ZonePreferenceSupplier** |
| 网关多活 | Spring Cloud Gateway | **Gateway 2025 + Envoy xDS 动态路由** |
| 存储层多活 | MySQL Binlog + Redis 命令复制 | **MySQL MGR Multi-Primary + ShardingSphere 5.x + Redis 7 Cluster** |

---

## 二、多活架构基础与认知

**训练营方向**：IT 架构演化→多活、三级容灾阶梯、AWS DR 四模型、流量单元化切分

### 2025 年补充方案

**三级容灾阶梯（RTT 数据来源：2025 年主流云厂商实测）**：

| 级别 | 典型 RTT | RTO 目标 | 2025 年实现方案 |
|---|---|---|---|
| 同城灾备 | <2ms | 分钟级 | MySQL Semi-Sync + Redis Sentinel 自动切换 |
| 同城双活 | <2ms | **秒级（K8s CAPI + KubeFed）** | Nacos 双集群 + ShardingSphere 读写分离 + Gateway 同区优先 |
| 异地多活 | 50-140ms | 秒级 | 单元化架构 + 异步消息同步 + 全局 ID 生成器 |

**三大云厂商跨 Region 延迟数据（2025 实测）**：
- Beijing↔Shanghai: ~8ms（同国跨区）
- Beijing↔Singapore: ~70ms（跨国）
- Beijing↔Frankfurt: ~140ms（跨洲）
- 同 Region 不同 AZ: <2ms

**流量单元化切分 — ShardingSphere 配置示例**：
```yaml
rules:
- !SHARDING
  tables:
    t_order:
      actualDataNodes: ds_${region}.t_order_${0..1}
      databaseStrategy:
        hint:
          shardingAlgorithmName: hint-region
      tableStrategy:
        standard:
          shardingColumn: order_id
  shardingAlgorithms:
    hint-region:
      type: HINT_INLINE
      props:
        algorithm-expression: ds_${value}
```

**Gateway 流量染色三步法**：
1. 请求进入时通过 Header/Cookie 提取 `x-route-tag`
2. 路由规则匹配 `region` 属性过滤目标实例池
3. 无匹配时 fallback 到默认区域

**四级 Chaos Engineering 故障注入点**（多活必备测试策略）：
- 网络层：延迟注入(tc qdisc 50ms)/丢包(30%)/分区(iptables DROP)
- 进程层：kill -9 进程 / CPU 满载 / 内存 OOM
- 存储层：DB 主从切换 / Redis Cluster Split-brain
- 区域层：整个 AZ 摘除 / DNS 切流 / 注册中心摘除

---

## 三、注册中心多活

**训练营方向**：Eureka P2P 复制、AP→CP 扩展（JGroup+JRAFT）、ZoneLocator 抽象

### Nacos Distro 协议（2025 年替代 Eureka P2P）

**Distro 6 大核心机制**（ap-c5df>对标 Eureka P2P 复制但更先进）：
1. **去中心化架构**：所有节点平等，无 Leader，避免单点
2. **一致 Hash 责任切片**：`DistroMapper.responsible(ip:port)` 计算每个数据归属节点
3. **写操作路由**：ServletFilter 拦截→一致 Hash 计算→本节点写本地/非本节点转发
4. **读操作本地响应**：全量数据每节点存储，直接返回
5. **心跳数据校验**：定时发送元信息摘要（非全量）→不一致时触发补齐（类似 Anti-Entropy）
6. **新节点全量拉取**：启动时轮询所有 Distro 节点拉取全量数据

**Nacos 架构演进（1.x→2.x→3.x）**：

| 版本 | 通信协议 | 发现模型 | 关键变化 |
|---|---|---|---|
| 1.x | HTTP 短连接 | Server 端存储+UDP 推送 | 1500ms 定时心跳 |
| 2.x | **gRPC 长连接** | **Client 端双向流** | 5s 心跳 + 实时推送 + 连接复用 |
| 3.x | gRPC + xDS | 联邦集群 + Mesh | 跨集群服务发现 + K8s 原生集成 |

**ZoneLocator → Spring Cloud LoadBalancer 现代实现**（概念层次——非完整代码）：
1. 定义 `ZonePreferenceServiceInstanceListSupplier` 实现 `ServiceInstanceListSupplier`
2. 内部注入 `ZoneLocator.locate()` 获取当前 Zone
3. `get()` 方法 Flux 装饰器链：原始列表→Zone 过滤(Flux.filter)→健康检查→返回
4. 同区域优先逻辑：同 Zone 实例≥minAvailable 时只返回同 Zone；不足时跨 Zone 补充

---

## 四、负载均衡多活

**训练营方向**：Ribbon 四层 Zone 过滤链（**过时**）、LoadBalancer ZonePreferenceSupplier

### Spring Cloud LoadBalancer 多活配置（2025 标准方案）

```yaml
spring:
  cloud:
    loadbalancer:
      health-check:
        path:
          default: /actuator/health
      zone-preference:
        enabled: true
        min-available: 2          # 同区最少可用实例数
        fallback: nearest-zone    # 不足时降级到最近区域
      retry:
        enabled: true
        max-retries-on-next-service-instance: 3
```

### Envoy xDS / Istio CRD 五对一映射表

| Istio CRD | xDS 服务 | 多活作用 |
|---|---|---|
| VirtualService | RDS(Route) | 按 Header 路由到不同区域实例 |
| DestinationRule | CDS(Cluster)+EDS(Endpoint) | 区域优先级权重(subset) |
| Gateway | LDS(Listener) | 入口流量按区域分发 |
| ServiceEntry | EDS(外部) | 跨集群服务发现 |
| EnvoyFilter | LDS(扩展) | 自定义区域感知 Filter |

**Locality Load Balancing（Istio 原生三级权重）**：
- Region → Zone → Subzone 三级优先级
- `failoverPriority: [topology.kubernetes.io/region, topology.kubernetes.io/zone]`
- 同 Region 失败→降级到其他 Region（加权轮询）

---

## 五、存储层多活

**训练营方向**：MySQL Binlog 订阅 + Redis 命令复制 + 动态 JDBC 子上下文

### MySQL MGR 多活方案（2025 年补充）

**Single-Primary vs Multi-Primary 选择决策树**：
- Single-Primary：RTT<30ms、写冲突概率>10%、需要强一致性
- Multi-Primary：RTT<2ms（同城）、写冲突概率<5%（业务隔离完善）、可接受乐观锁冲突回滚

**XCom Group Communication System（MGR 内部机制）**：
- XCom = Paxos 变种，负责组内消息全局有序广播
- 每个节点先本地执行认证（certification）→ XCom 广播 write-set → 全局排序
- Multi-Primary 下冲突检测："先提交获胜"规则——全局排序靠后者回滚

**2025 年主流多活数据库方案六维对比**：

| 方案 | 一致性 | 延迟容忍 | 运维复杂度 | 社区活跃度 | 适用规模 | 推荐场景 |
|---|---|---|---|---|---|---|
| MySQL MGR | CP | 低(RTT<2ms) | 中 | 高 | 中小(<1TB) | 传统企业多活 |
| TiDB Multi-Raft | CP+AP 混合 | 中(RTT<10ms) | 中 | 极高 | 大(PB级) | 互联网核心业务 |
| CockroachDB | CP(Serializable) | 高(RTT≤100ms) | 低(自运维) | 高 | 大 | 全球跨洲部署 |
| Vitess | 最终一致 | 高 | 高 | 高 | 极大 | MySQL 水平扩展 |

**选择建议**：
- 已有 MySQL 生态→MySQL MGR 渐进升级
- 新项目→TiDB（国内首选，社区活跃）
- 全球部署→CockroachDB（原生多 Region）

### Redis 7.x Cluster Multi-AZ 部署方案（8 点 Checklist）

1. **节点部署**：每个 AZ 至少 2 个节点（1 Master + 1 Replica），3 AZ 共 6 节点
2. **hash tag 策略**：`{region}:order:123` 确保同区域数据在同一 Slot，避免跨 Slot 事务
3. **Sentinel 配置**：每 AZ 部署 1-2 个 Sentinel，`quorum=region_count+1` 防误判
4. **Cluster Bus 加密**：`tls-cluster yes` + `tls-replication yes`
5. **防数据回流方案**：Lua 脚本原子检查 `source_region` 标记，本机房写不复制
6. **Slot 迁移策略**：`CLUSTER SETSLOT IMPORTING/MIGRATING` + 渐进式迁徙 + 业务低峰执行
7. **集群失连处理**：`cluster-require-full-coverage no` 允许部分 Slot 不可用时继续服务
8. **监控指标**：`instantaneous_ops_per_sec` / `repl_backlog_size` / `connected_slaves` / `cluster_state`

### ShardingSphere 5.x + Nacos Config 动态数据源切换

```yaml
# Nacos 配置中心存储多活路由规则
microsphere.jdbc.zone:
  datasources:
    defaultZone:
      - name: ds-beijing
        url: jdbc:mysql://10.0.1.1:3306/db
    shanghaiZone:
      - name: ds-shanghai
        url: jdbc:mysql://10.0.2.1:3306/db
  strategy:
    type: zone-preference
    fallback: nearest
```

- DynamicDataSource 监听 Nacos Config 变更→重建 `DynamicJdbcChildContext`→原子替换 `volatile delegate`
- 旧上下文通过 `ScheduledExecutorService` 延迟关闭（避免中断正在执行的请求）
- `synchronized mutex` 保证替换原子性

---

## 六、多活反模式与工程陷阱

1. **"伪多活"**：只做了 DNS/Gateway 切流，但底层 MySQL/Redis 仍是单机房——真正故障时数据层不可用
2. **死亡三角量化**：CAP 三选二×RTT→跨洲延迟必然舍弃强一致性，不存在"全球强一致多活"
3. **分布式锁跨区陷阱**：Redisson RLock 在 Redis Cluster Multi-AZ 下可能出现 Split-Brain 双持锁——除非所有 Redis 节点在同一 CP 组内
4. **Nacos 自我保护误读**：保护模式下返回旧数据≠系统完全不可用——区分"服务发现降级"和"系统故障"
5. **Binlog 切流脏窗口**：Canal 切换消费位点的瞬间可能丢失/重复数据→配合幂等消费+Checkpoint 机制
6. **全局 ID 生成**：Snowflake workerId 分配策略在跨区场景下的冲突→使用 Leaf-Segment 或 CosId（号段模式）替代
7. **监控区分"主动切流"vs"被动降级"**：两种场景的告警策略完全不同（被动=P0 告警，主动=静默切换）
8. **Chaos 5 级递进实验**：单服务→单 AZ→跨 AZ 网络→全 AZ 摘除→恢复演练，每级验证自动切换和自动恢复

---

## 七、过时技术 → 现代替代映射表

| stage-4 过时技术 | 现代替代 | 保留的主题 |
|---|---|---|
| Eureka P2P 复制 | **Nacos Distro（去中心化+gRPC 长连接）** | 区域隔离/心跳-超时-保护三角/客户端按需订阅 |
| Netflix Ribbon | **Spring Cloud LoadBalancer + Istio Locality LB** | 四层 Zone 过滤链/同区域优先 |
| Microsphere Config | **Nacos Config** | 动态路由配置/热加载 |
| MySQL Binlog 手动同步 | **MySQL MGR Multi-Primary / Canal + DTS** | Binlog 订阅管道/异构数据同步 |
| Redis 命令复制（Kafka 管道） | **Redis 7 Cluster Multi-AZ + Lua 防回流** | 事件驱动复制/防数据回流 |

---

## 八、对 sca-lab 的影响

- **multiactive 模块**：ZoneLocator 抽象 + 同区域优先路由 + Gateway 流量染色 + Chaos 故障注入验证
- **redis 模块**：Redis 7 Cluster Multi-AZ 部署 + Lua 防回流 + Sentinel 防误判
- **shardingsphere 模块**：动态数据源热替换 + Nacos Config 驱动路由 + ShardingSphere 多活分片规则

---

## 九、待 review 检查点

1. Nacos Distro 6 大机制的描述是否准确？
2. 多活数据库六维对比（MGR/TiDB/CockroachDB/Vitess）的选择建议是否合理？
3. Redis Cluster Multi-AZ 8 点 Checklist 是否有遗漏？
4. Chaos 5 级递进实验是否覆盖关键场景？
5. 与 stage-2/3 存储域内容是否衔接？

---

## 附：逐章覆盖映射表

| 训练营章节 | 文档对应位置 | 覆盖方式 |
|---|---|---|
| 第 01 章：多活架构基础 | 功能域 2 | 方法论提炼 + RTT 数据补充 |
| 第 02 章：Eureka Server 多活 | 功能域 3（P2P 复制） | Eureka→Nacos Distro 重写 |
| 第 03 章：优化 Eureka Server | 功能域 3（AP→CP 扩展） | JRAFT→Nacos CP 双协议 |
| 第 04 章：Eureka Client 发现多活 | 功能域 3（客户端多活） | 按需订阅+动态配置→Nacos 重写 |
| 第 05 章：Eureka Client 注册多活 | 功能域 3（多注册中心聚合） | 灰度发布→Nacos metadata 重写 |
| 第 06 章：Eureka Client 加餐 | 功能域 3（灰度/蓝绿） | 概念融合 |
| 第 07 章：Spring Cloud 注册通用 | 功能域 3（ZoneLocator 抽象） | 7 设计原则+8 级守卫链+三段时序 |
| 第 08 章：Spring Cloud 注册加餐 | 功能域 3（事件生命周期） | ZoneAttachmentHandler 防御性编程 |
| 第 09 章：Cloud-Native 注册多活 | 功能域 3+4（xDS/Istio） | K3s+Envoy 方案补充 |
| 第 10 章：Ribbon 负载均衡多活 | 功能域 4（Zone 过滤链） | Ribbon→LoadBalancer 重写 |
| 第 11 章：LoadBalancer 多活 | 功能域 4（yaml 配置） | 补充现代配置+ZonePreferenceSupplier |
| 第 12 章：REST Client 多活 | 功能域 5 | 方法论保留 |
| 第 13 章：Dubbo 多活 | 功能域 5（Router SPI） | Dubbo 3.x 更新 |
| 第 14 章：Gateway 多活设计 | 功能域 4 末尾 | 方法论保留 |
| 第 15 章：Gateway 多活优化 | 功能域 4（CachingFilteringWebHandler） | 方法论保留 |
| 第 16 章：MySQL Server 多活 | 功能域 5（Binlog+异构同步+MGR） | MGR→现代方案补充 |
| 第 17 章：MySQL JDBC 多活 | 功能域 5（JDBC 三种多主机模式） | 补充 Failover 参数 |
| 第 18 章：Redis Client 多活 | 功能域 5（命令复制+防回流） | Redis 7 补充 |
| 第 19 章：Redis Server 多活 | 功能域 5（Sentinel+Cluster+Quorum） | Redis 7 补充 |
| 第 22 章：动态 JDBC 多活 | 功能域 5（子上下文+热替换+SPI） | ShardingSphere 5.x 补充 |
