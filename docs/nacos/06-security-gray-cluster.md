---
title: "06-安全、灰度与集群"
series: "Nacos 协调服务系列"
previous: "05-config-refresh.md"
next: "07-xhs生产对照.md（待写）"
lab_refs:
  - "nacos-lab/docs/A-05-命名空间与安全/01-环境隔离策略.md"
  - "nacos-lab/docs/A-06-配置灰度与回滚/01-灰度发布.md + 02/03"
  - "nacos-lab/docs/A-07-集群部署/01-Nacos集群架构.md"
---

# 06-安全、灰度与集群

## §0 它干了什么（例子先行）

三件生产级的事：

```
隔离：dev/prod 各一个 namespace——测试流量永远打不到生产
灰度：改连接池先发给 2 台（betaIps），看 2 分钟日志没炸 → 正式发布 → beta（小流量试验版）自动清
回滚：正式发布发现异常 → 从 30 天历史里一键回滚
```

- **它干了什么**：把 Nacos 从"能用"推到"敢在生产用"。
- **本篇回答**：namespace 用在哪不该用在哪？鉴权在哪一层？beta 灰度怎么工作？集群怎么搭？

## §1 掌握卡（4 条）

1. **namespace 用法边界**：环境/多团队 **用**；服务分级（S1/S2）**别用**（Group 更轻）；多租户**不能用**（无资源隔离，要独立集群）。
2. **鉴权在 Handler 层**：NamingService/ConfigService 客户端 API **无任何安全注解**——三道 filter 在服务端（@Secured→@TpsControl→@ExtractorManager）；token 默认，AK/SK/LDAP 走 AuthPlugin SPI。
3. **灰度=beta 副本**：服务端存 `ConfigInfoBetaWrapper`，按 **betaIps 过滤**——灰度 IP 拿 beta、别人拿正式；正式发布即清 beta。
4. **集群三件套**：3 节点起（Raft 多数派）；三层端口（03）；瓶颈 Naming=连接+推送量、Config=MySQL IO。

## §2 namespace 隔离边界

| 场景 | 用 namespace？ | 理由 |
|---|---|---|
| dev/staging/prod | ✅ | 环境物理隔离，防串流量 |
| 多团队共享一套 Nacos | ✅ | 每团队一个，互不干扰 |
| 服务分级 S1/S2/S3 | ❌ | Group 功能分组就够，ns 太重 |
| 多租户（客户 A/B 数据隔离） | ❌ | ns **不隔离资源**（同一进程）——要独立集群 |

隔离强度：实例/配置/权限白名单按 ns 切；**CPU/内存/网络不隔离**——这是"多租户不能用 ns"的根因。

## §3 过滤链 + 两层鉴权（认证 + 授权）

```
客户端 gRPC → GrpcRequestAcceptor → Handler：
  ① @Secured（AuthFilter 鉴权） ② @TpsControl（限流） ③ @ExtractorManager（参数校验）
  → handler.handle() 业务
```

（链上三道 filter，但"两层鉴权"指的是**认证**——你是谁 + **授权**——你能干啥，见下。）

- 认证三方式：Token（默认，`nacos.core.auth.enabled=true`）/ AK/SK（第三方）/ LDAP·OAuth2（企业统一，走 AuthPlugin SPI——SPI=可插拔扩展点，换实现不用改主流程）。
- **注意**：yml 里的 `username/password` 是 Nacos 账号，**不是企业 LDAP 账号**（企业认证要自定义插件）。
- 权限：读/写/管理三角色 + **每 namespace 独立白名单**——同一用户在 dev 可写、prod 只读。

## §4 beta 灰度

本质：**先发一小撮，确认安全再全量**（不是直接全发）。

```
发布时带 betaIps → 服务端存 ConfigInfoBetaWrapper（beta 副本）
查询时：客户端 IP ∈ betaIps？→ beta 内容 : 正式内容
正式发布 → beta 自动移除
```

三条生产注意（原文）：**按 IP 过滤**，不能按 label/zone 过滤；已建 gRPC 长连的客户端**可能 30s 后**才拿到 beta；beta 与正式独立两版，取消 beta 立刻回正式。验证窗口：beta 后等 1-2 分钟查灰度实例日志/监控，有异常立即停。

## §5 回滚与加密

- **历史**：每次发布存历史，可查可回滚；保留天数 `nacos.config.retention.days=30`——**生产用 MySQL 存储**（Derby=Java 内置单机库，进程删目录历史就没了）。
- **加密**（A-06/03）：master key **绝不能放配置文件**（否则等于明文）；dev/prod 不同 key；支持多 key 轮换。
- **两种加密别混**：Nacos 加密插件=传输加密（Server↔Client）；Jasypt=存储加密（配置内容本身）——可叠加。

## §6 集群

- **3 节点起**（Raft 多数派容错）；成员变更发 `MembersChangeEvent` → DistroMapper 重建。
- **DistroMapper 路由是取模**（`tag.hashCode() % serverCount`，**不是一致性哈希**）——节点变更时几乎全部重分配，靠 5s 反熵快速修复（04 §2 的实际用武之地）。
- **容量瓶颈**：Naming 卡在 gRPC 连接数 + 推送量；Config 卡在 MySQL IO（每次发布写库+dump）；大集群还卡 Distro 同步带宽。
- 滚动升级/新节点加入流程 → `lab A-07 §七` 用时查。

## §7 边界（一句话翻篇）

- AuthPlugin SPI 自定义 → `lab B-05`。
- 容量表全量数字 → `lab A-07 §六`。
- Nacos 控制台操作步骤 → 用时照 lab curl 命令。

## §8 Self-Test（4 题）

- Q1：多租户为什么不能用 namespace？（无资源隔离）
- Q2：鉴权在客户端 API 层还是 Handler 层？三道 filter 顺序？
- Q3：beta 灰度的两个已知坑？（IP 过滤局限 + 30s 长连延迟）
- Q4：DistroMapper 用什么路由？和一致性哈希差在哪？靠什么修复重分配？

## §9 衔接

- 生产专题收口；下篇 07 xhs 生产对照（25 处 yml 口径 + import 实景 + 元数据/Prometheus-SD 一句）。
