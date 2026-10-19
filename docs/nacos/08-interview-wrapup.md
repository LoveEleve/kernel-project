---
title: "08-面试收口：18题路线与陷阱"
series: "Nacos 协调服务系列"
previous: "07-xhs-production.md"
next: "（本系列完）"
lab_refs:
  - "nacos-lab/docs/D-源码解读/D-14-Interview.md（18 题 + 五级路线 + 3 故障场景）"
---

# 08-面试收口：18题路线与陷阱

## §0 它干了什么（例子先行）

本篇零新概念，三张清单收口：

```
① D-14 的 18 题五级路线 → 映射到本系列 01~07
② 陷阱速查（本系列沉淀）
③ 终检 + 3 个故障排查场景指路
```

## §1 五级路线图（D-14 原表 → 本系列回链）

| 级 | 题段 | 回链 |
|---|---|---|
| 1 基础（Q1~Q4）：四层架构/Naming 53 方法/Config 12 方法/Instance.equals 陷阱（Q4 细节→lab D-01） | 01/02 |
| 2 核心（Q5~Q11）：同步异步/redo/四级降级/注册全链/Client 模型/Push 合并/健康对比 | 02/03/04 |
| 3 深度（Q12~Q14）：Distro 流程/CAP 双引擎/revision 不保强一致 | 04 |
| 4 配置（Q15~Q16）：监听双确认/集群通知+拉取 | 05 |
| 5 综合（Q17~Q18）：百万实例设计/3 故障场景 | 各篇合成 |

**几道必背的秒答**（D-14 原话提炼）：

- redo：**write-ahead**（先 cacheInstanceForRedo 再发；断连全标 false，3s 定时重发；注册天然幂等）→ 02 §3。
- getConfig 四级降级：failover（手动）> server > snapshot（自动缓存）> null → 04/05 同款"兜底分层"思想。
- 1.x vs 2.x 健康：应用层 5s 心跳+30s 删 vs **传输层 keep-alive+断开即删**（15s/30s 只是补偿）→ 02/04。
- Config vs Naming 集群同步：Config=**通知+拉取**（DB 共享，无限重试二次方退避——退避=失败后等得越来越久再试）；Naming=**推数据**（内存）→ 04/05。

## §2 本系列陷阱速查（沉淀 12 连）

| # | 反直觉 | 正解 | 篇 |
|---|---|---|---|
| 1 | 2.x 还要 5s 心跳 | keep-alive 传输层；5s/15s/30s 是补偿 | 02 |
| 2 | Server 挂了读磁盘 | 读**内存**缓存；磁盘只管客户端重启 | 02 |
| 3 | failover 自动触发 | **运维手动**放开关文件 | 02 |
| 4 | kill -9 没关系 | 级联故障——@PreDestroy+deregister | 02 |
| 5 | 注册走 Proxy 三元路由 | gRPC 数据面是**两个 Handler 直连**；Proxy 是管理面 | 04 |
| 6 | 自保护把实例治好 | 强制 setHealthy(true) 全返回——防雪崩 | 04 |
| 7 | 推送丢了 5s 内补上 | 最坏 **3 分钟**（全量同步） | 05 |
| 8 | optional 随便加 | 生产应显式处理缺失 | 05 |
| 9 | 多租户用 namespace | 无资源隔离——独立集群 | 06 |
| 10 | beta 能按 zone 灰度 | 只能按 **IP** | 06 |
| 11 | DistroMapper 一致性哈希 | **取模**；靠 5s 反熵修复重分配 | 06 |
| 12 | revision 相等=数据一致 | hash 相等≠一致；防脑裂要 CP | 04/D-14 Q14 |

## §3 3 故障排查场景（D-14 Q18 指路）

1. **大量实例不健康** → 查 keep-alive 配置/网络抖动/`connectionForClientIp` 计数。
2. **新注册感知不到** → 查 PushDelayTask 队列深度 + `pushTaskDelay`（默认 500ms）。
3. **节点恢复后不一致** → 等 DistroLoadDataTask 完成 + 5s verify 反熵；查 DistroMapper healthyList。

## §4 终检（8 题，过 6 算过关）

1. 双中心各管什么；三维模型（01）
2. 临时/持久最先答哪三维；三层缓存 Server 挂用哪层（02）
3. 三层端口；连接断了发生什么（03）
4. Distro 五操作；自保护真实机制（04）
5. import 优先级；推送丢失最坏多久（05）
6. 多租户为什么不能 ns；beta 灰度的两个坑（06）
7. xhs 共享配置几文件几服务；zone 谁在用（07）
8. redo write-ahead 一句话（§1）

## §5 衔接

- Nacos 系列 8 篇完，README 全挂链接；L5-12 协调服务收口。
- 下一专题由你定。
