# D9: 集群限流 — TokenServer + TokenClient

> 源码位置: `sentinel-cluster/sentinel-cluster-server-default/` + `sentinel-cluster/sentinel-cluster-client-default/`
> 核心文件: `DefaultTokenService.java`(服务端) / `DefaultClusterTokenClient.java`(客户端) / `NettyTransportServer.java`
> 前置: D3（流控）理解 FlowRuleChecker.checkFlow 的 passClusterCheck 分支

---

## 一、问题背景：单机限流的精度天花板

D3 的 `FlowRule` 是单机限流——每个实例独立统计自己的 QPS，独立判断是否超限。但这有个致命场景：

```
实例 A: 看到自己 QPS=400 → 没超限（阈值 500）→ 放行
实例 B: 看到自己 QPS=400 → 没超限（阈值 500）→ 放行
网关层: 实际总 QPS = 800，远超预期的 500
```

多实例场景下单机限流**永远无法做到精确的全局控制**——因为每个实例只能看到自己的流量。集群限流的解决方案：引入一个**中心化的 TokenServer**，所有实例向它申请令牌，由它统一判断全局 QPS 是否超限。

```
实例 A → TokenClient.requestToken("getUser", 1)
实例 B → TokenClient.requestToken("getUser", 1)
               ↓
       TokenServer: 全局计数器 getUser_qps=800 > 500 → 拒绝新令牌
               ↓
实例 A/B: TokenResult == TOKEN_RESULT_STATUS_BLOCKED → 限流
```

---

## 二、架构：两种部署模式

Sentinel 集群限流支持两种模式：

| 模式 | TokenServer 位置 | 适用场景 |
|------|------|------|
| 独立模式（Independent） | 独立 JVM 进程 | 生产环境，TokenServer 单独部署 |
| 嵌入模式（Embedded） | 其中一个应用实例兼任 | 测试/小规模部署 |

**嵌入模式的 TokenServer 选举**：多个实例启动后，通过配置 `ClusterStateManager.applyState(CLUSTER_SERVER)` / `CLUSTER_CLIENT` 手动指定角色——没有自动选举机制。如果赋 Server 角色的实例宕机，**不会自动 failover**。

---

## 三、FlowRuleChecker.passClusterCheck：集群分支

**位置**: `FlowRuleChecker.java:L147-181`

```java
private static boolean passClusterCheck(FlowRule rule, Context context, DefaultNode node, 
                                        int acquireCount, boolean prioritized) {
    TokenService tokenService = pickClusterService();                   // L176
    if (tokenService == null) {
        return fallbackToLocalOrPass(rule, context, node, acquireCount, ...); // 回退本地
    }
    // ...
}

private static TokenService pickClusterService() {                      // L176
    if (ClusterStateManager.isClient()) {
        return TokenClientProvider.getClient();                        // L178: 客户端模式 → 向远程申请
    }
    if (ClusterStateManager.isServer()) {
        return EmbeddedClusterTokenServerProvider.getServer();         // L181: 服务端模式 → 本地自给
    }
    return null;   // 未配置集群模式 → fallbackToLocalOrPass
}
```

**`applyTokenResult` 的结果处理**：

| TokenResult 状态 | 含义 | 处理 |
|------|------|------|
| `STATUS_OK` | 令牌分配成功 | 放行 |
| `STATUS_BLOCKED` | 令牌不足 | 抛 FlowException |
| `STATUS_SHOULD_WAIT` | 需排队等待 | sleep 后放行（Throttling 模式） |
| `STATUS_NO_RULE_EXISTS` | 集群规则不存在 | **回退到本地限流** |
| `STATUS_TOO_MANY_REQUEST` | TokenServer 过载 | **回退到本地限流** |

---

## 四、DefaultTokenService.requestToken：TokenServer 端的流控

**位置**: `sentinel-cluster-server-default/.../DefaultTokenService.java:L39-53`

```java
public class DefaultTokenService implements TokenService {
    @Override
    public TokenResult requestToken(Long ruleId, int acquireCount, boolean prioritized) {
        if (notValidRequest(ruleId, acquireCount)) {
            return badRequest();
        }
        // The rule should be valid.
        FlowRule rule = ClusterFlowRuleManager.getFlowRuleById(ruleId);
        if (rule == null) {
            return new TokenResult(TokenResultStatus.NO_RULE_EXISTS);
        }

        return ClusterFlowChecker.acquireClusterToken(rule, acquireCount, prioritized);
    }
}
```

放行/拒绝的 `TokenResult` 由 `ClusterFlowChecker.acquireClusterToken(...)` 构造（`TokenResultStatus.OK` / `BLOCKED`）——服务端复用与本地流控完全相同的 `FlowRuleChecker.canPassCheck` 逻辑。

**核心设计**：TokenServer 端的流控逻辑**与本地 D3 FlowRuleChecker.canPassCheck 完全相同**。区别只在于：本地流控用的是单机 `ClusterNode` 的 `passQps`，TokenServer 用的是**所有客户端汇总**后的 `ClusterNode` 的 `passQps`。因为所有客户端的 `addPassRequest` 都通过网络发给了 TokenServer 的 `ClusterNode`。

---

## 五、生产陷阱

1. **TokenServer 单点故障**：独立模式下 TokenServer 宕机 → 所有客户端的 `TokenClientProvider.getClient()` 返回 null → `pickTokenService()` 返回 null → **fallbackToLocalOrPass 回退到单机限流**。此时全局 QPS 控制失效，退化成 D3 的单机模式——但至少不会拒绝所有请求。这是"故障开放"设计的又一次体现。

2. **嵌入模式的角色漂移**：嵌入模式下 Server 实例宕机 → 该实例的客户端请求全部 fallbackToLocalOrPass。但如果剩下的一台 Client 被手动改为 Server 角色（通过 Dashboard 配置），其他 Client 的 `TokenClientProvider` 会连接到新的 Server——这个过程需要手动操作，且中间存在**连接重建窗口**（所有 Client 重新连接的那几秒内，集群限流失效）。

3. **Netty 通信开销**：客户端每次 `requestToken` 都是 Netty 网络请求（TCP 连接 + 序列化 + 反序列化）。如果 QPS=10,000，每秒 10,000 次网络单次往返——TokenServer 成为性能瓶颈。解决：批量申请令牌（一次申请 N 个，客户端本地自消费）+ 异步回调（不阻塞业务线程）。
