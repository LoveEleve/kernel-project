# sdn-oss（复现）—— 二代控制面核心 · G2-1

> 真实系统：`sdn_oss`（Python 2.7 · uWSGI + MySQL + ZooKeeper + Kafka）
> 真实规模：5982 文件；平铺 Handle ~2020 个（get 488 / op 372 / add 367 / del 342 / update 267）；
>           核心框架文件 30+；**MySQL 585 张业务表 + 25 张事务 Outbox 表**；
>           Vpc5 适配层 94 文件。
> 本复现为**教学切片**：只复现"一条写请求的生命周期"上的关键文件，文件路径与真实同名。
> 审查状态：**Round R1 已过**（13 处修正，见 `../review-log.md`）。

## 一、它是什么（一句话）

**sdn_oss 是二代 VPC 控制面的"唯一写入口" + Saga 事务引擎**：所有配置变更（加路由/建子网/迁 VM…）
在一个 Saga 步骤链里完成"业务表 + Outbox"同事务落库；ZK 配置树（/TVPC/*）与 Kafka 变更流水
是提交后的**派生投递**；下游（nfv-proxy / agent-broker / 三代 fusion）全部吃它产出的数据。

## 二、一条写请求的生命周期（本复现覆盖的主线）

```
云API ──→ [ sdn_oss ]（原链路：vpc-cgw 只做声明式转发，不复现）
  api/tvpc.py                 ├─ ① uWSGI 入口：interfaceName → Handle 注册表（一个接口 = 一个文件）
  api/cgi_base_tvpc.py        ├─ ② 信封校验(version/caller/callee/eventId/interface) / region / 配额限流
  api/add_rtb_route_handle.py ├─ ③ 具体接口：Saga 步骤链（校验→加锁→双写→登记）
  api/tvpc_handle.py          ├─ ④ ★ Saga 引擎：add_step(fwd, bwd, skip_on_codes)；
                              │     按对象分批并发（split_num 20 / 信号量 20）；失败逆序回滚；
                              │     回滚失败 → Redis 24h + 告警 + Revert.TVPC.* 重试接口
  common/tvpc_db_client.py    ├─ ⑤ 主库写入（同一事务：业务表 + Outbox 表）
                              └─ ⑥ 提交后派生投递（DB 唯一真实源）：
  common/zoob_proxy.py            → ZK 配置树（CZoobProxy：按 VPC 保序队列）
  bin/vpc_binlog_producer_task.py → ★ 20 生产者 + CRemedialThread：Outbox 扫描 → Kafka
```

## 三、问题矩阵（本目录代码 ↔ 它表达的问题）

| 文件 | 业务问题 | 分布式问题 | 工程问题 |
|---|---|---|---|
| `api/tvpc_handle.py` | Saga：~60 步接口的回滚（AddService 为最复杂样板） | 回滚失败→Redis+Revert 接口（补偿也要兜底） | 分批并发（20/20）+ skip_on_codes |
| `api/add_rtb_route_handle.py` | 加路由全链（对应通信手册 TVPC.AddRtbRoute） | 双写=同事务（Saga+Outbox 配合） | 步骤编排/幂等短路 |
| `api/CheckMigrate/…` | 跨 VPC 迁移前置检查 | **串行 is_vif_ready × 60s**（对账持全局锁阻塞——vpc_agent 4.12.0 已修） | 超时≠未就绪（语义区分） |
| `api/Vpc5/decorator.py` | 新旧对象模型并行（迁移切片，94 文件适配层） | — | @support_vpc5 Mixin 注入 |
| `common/zoob_proxy.py` | ZK 树写入 | 异步队列 / 按 VPC 保序 / 重试→死信 | 队列头阻塞治理 |
| `common/kafkab_proxy.py` | Kafka 流水投递 | sync（等 ack）/ async（背压） | 有界队列"慢比崩优雅" |
| `bin/vpc_binlog_producer_task.py` | 变更流水生产 | **Outbox at-least-once + 4 态 sendState + 行锁保序** | CRemedialThread / 死信告警 / 16 分区 |
| `common/zklock.py` | VPC 维度加锁 | ZK 锁雪崩教训（nfv-proxy LOST→suicide→全量迁移 DB 锁） | 会话事件处置哲学 |

## 四、只保留一个"数字纪律"

真实参数全部来自材料（20 生产者 / 100 批 / 60s / 30s / 16 分区 / 24h Redis / 20 并发 / ~60 步样板）；
**不确定的数字一律不写**（R1 审查已删除一处编造的"路由上限 200"）。

## 五、使用纪律

1. 文件头 `【复现】` 注明真实出处 + 证据等级（实证/推导）；R1 后证据全部可回溯到材料行号；
2. 开源对照只做参照（Saga / TaskFlow / Outbox / Debezium / Curator），不参与口径；
3. Gen2（Python）与 Gen3（Go）复现分目录共存。
