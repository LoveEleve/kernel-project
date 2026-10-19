# dist-consistency-lab 交接文档（2026-10-05）

> 位置：`/data/workspace/source-code/xhs-demo/dist-consistency-lab`
> 基线：**xhs-project 生产模式最小对照**（自包含：本地 Redis + H2 + FakeMqBroker）
> 用途：分布式一致性模式学习模块（幂等 / Outbox / 对账 / 补偿重试）——生产使用 + 核心原理 + 面试题
> 评审与验证记录：`docs/REVIEW-2026-10-05.md`

---

## 一、建设内容（新建 lab，非迁移）

- 四个模式的最小实现（对齐 xhs-project）：`IdempotentHelper` / Outbox（表+补发）/ 对账（权威源修复）/ `RetryBackoffUtils`
- 实验端点 A01-A04（可 curl 复现每个模式）
- 文档：D1-D5（例子先行）+ Z（面试收束）

## 二、验证状态（全部实测）

- 单测 6/6 ✓；四模式 curl 端到端全部通过（详见 REVIEW §二）
- 端口 **28050**；运行依赖：本地 Redis `127.0.0.1:6379`（已确认可用）+ H2 内存库（自包含）

## 三、构建与运行

```bash
cd /data/workspace/source-code/xhs-demo
export JAVA_HOME=/env/javaEnv/jdk17
export MAVEN_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
mvn -pl dist-consistency-lab test
setsid nohup mvn -pl dist-consistency-lab spring-boot:run -q > /tmp/dcl.log 2>&1 &

# ⚠️ 本地 curl 前先 unset http_proxy https_proxy（环境有代理，否则 curl 空返回）
unset http_proxy https_proxy
```

## 四、端点速查（实验手册）

| 模式 | 端点 | 说明 |
|---|---|---|
| A01 幂等 | `POST /a01/consume?msgId=&amount=&failFirst=` | 首次/重复/failFirst→removeMark |
| | `GET /a01/status` / `DELETE /a01/reset` | 余额/标记数/记录数 |
| A02 Outbox | `POST /a02/place-order?orderId=&amount=` | 同事务写业务+outbox + 即时投递 |
| | `POST /a02/inject-failure?enabled=` | 注入 Broker 故障 |
| | `POST /a02/flush-outbox?force=` | 补发（force 免等退避窗口） |
| | `GET /a02/outbox-status` / `GET /a02/broker` / `DELETE /a02/reset` | 状态/投递记录 |
| A03 对账 | `POST /a03/op?amount=&skipDb=` | Redis 扣减（skipDb 制造差异） |
| | `POST /a03/drift?amount=` | Redis 漂移 |
| | `POST /a03/reconcile` / `GET /a03/status` / `DELETE /a03/reset` | 修复/对比/重置 |
| A04 重试 | `GET /a04/backoff?attempts=` | 退避序列 |
| | `POST /a04/retry-flow?msgId=&maxRetries=` / `GET /a04/dead-letter` / `DELETE /a04/reset` | 重试链/死信 |

## 五、审计语料（模式蓝本）

| 蓝本 | 位置 |
|---|---|
| MessageIdempotentHelper | `xhs-project/my-xhs-common/.../mq/MessageIdempotentHelper.java` |
| Outbox（券） | `xhs-project/my-xhs-coupon/.../job/CouponOutboxSenderJob.java` + `t_coupon_outbox` |
| 对账（库存） | `xhs-project/my-xhs-inventory/.../job/InventoryReconcileJob.java` |
| 退避/补发（订单） | `xhs-project/my-xhs-order/.../job/LocalMessageRetryJob.java` + `RetryBackoffUtils` |

## 六、口径约定

1. 本 lab 演示口径：**Redis 为准**（库存对账）；其他域的权威源口径见 D3（通知=DB 为准；资金=外部为准）；
2. 核心算法与 xhs-project **同构**（逐字对齐关键语义）；规模最小化；
3. 自包含设计：FakeMqBroker 可注入故障；真实 MQ 语义回链 RocketMQ 系列。

## 七、已知边界

- FakeMqBroker/H2 为内存实现，重启清空；无真实调度（XXL-Job）与真实 MQ；
- 对账/补发的"周期调度"语义为文档级对照（生产=xhs 的 XXL-Job 配置）；
- A01 的 `redisMarks` 计数用 `KEYS` 扫描（演示用，生产禁用 KEYS）。
