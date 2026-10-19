# vpc-fusion（复现批次 1）

> 【复现】依据 `vpc/docs-by-topic/38-vpc-fusion.md`（432 受控文件 / 13 内部模块）的逐文件清单实现的教学切片。
> 真实模块：`git.woa.com/vpc/vpc5.0/vpc-fusion`。本目录**非真实源码**，用于把机制与问题钉进代码。

## 本批文件（与真实清单对齐）

| 本目录 | 真实对应 | 说明 |
|---|---|---|
| `cmd/vpcfusion/main.go` | `cmd/vpcfusion/main.go` | 入口：`-role/-config`；panic 兜底；优雅关闭（终态写用 detached ctx） |
| `internal/server.go` | `internal/server.go` | 组装：三阶段启动 + 停机编排 |
| `internal/system/startup.go` | 启动框架（`.codebuddy/rules/system-startup-framework.md`） | Checker → Initializer → Worker 三阶段 |
| `internal/service/common/leader_vote_service.go` | 同名 | 选举服务：四域、TTL/续约、回调 |
| `internal/dao/leader_vote_dao_impl.go` | DAO 层 | `SET NX EX` + JSON + Lua 校验 nodeID 续约 |
| `internal/dao/task_dao_impl.go` | DAO 层 | 4 类 key + 8 Lua（原子领取/终态/重入队/Ownership） |
| `internal/service/common/task_service.go` | 同名 | Worker 消费、心跳、Recovery、发布限频 |
| `internal/service/snapshot/worker_service.go` | 同名 | 快照构建：全量序列化 + 增量 + `cloneSnapshot` + Delete 修复 |
| `internal/service/reconcile/reconcile.go` | 同名 | 双侧加载 + 比对（Missing/Redundant/Mismatch）——只报告 |
| `internal/service/message/binlog/binlog_service.go` | 同名 | binlog 幂等消费 + 批次触发快照 + 版本连续性 |
| `internal/mq/consume/consumer.go` | 同名 | Kafka 消费者：poll 驱动 + 看门狗防假死 |

## 问题 → 代码落点（"实打实"对照表）

| 问题（材料出处） | 代码落点 |
|---|---|
| 选举续约"给别人的锁续约"→ 脑裂（08 §三/题 2） | `leader_vote_dao_impl.go` `renewScript`（cjson 校验 node_id） |
| 任务重复消费（claim 非原子，16 问题 #3） | `task_dao_impl.go` `claimScript`（LINDEX 验头 + LPOP + 写 processing） |
| 崩溃后任务卡死 processing（16 问题 #4） | `task_service.go` Recovery（扫描过期租约 → 重入队） |
| 旧 Worker 复活越权提交（分支修复 ownership） | `task_dao_impl.go` `finishScript`（三重校验在 Lua 内） |
| 优雅停机丢任务（16 问题 #12） | `main.go` / `server.go`（detached context + 5s 超时写终态） |
| 首启 200 万任务压爆 Redis（16 问题 #10） | `task_service.go` `PublishThrottled`（atomic + sleep，成功才计数） |
| DeleteConfig `Put(nil)` 复活（commit 430b5ab） | `snapshot/worker_service.go` `DeleteConfig` + 测试观测点注释 |
| 对账只报告不修复（reconcile 设计） | `reconcile.go` `Run`（仅产出 Diff + Export，无 Fix 分支） |
| 消息乱序/重复的幂等吸收（12 §一/C 卷 §3） | `binlog_service.go`（version+scopeId；`ON DUPLICATE KEY UPDATE`） |
| Kafka 消费假死（Gen2 案例，框架横切） | `mq/consume/consumer.go`（poll 驱动 + lastPoll 看门狗） |
| LevelDB 失败降级（38 §1.1） | `server.go`（`kvstore` 接口：leveldb → memory 降级） |

## 未实现（本批省略，后续批次）

`initconfig/`（两阶段初始化）、`cleanconfig/`（六连删）、`localcache/`、`health/`、`vpcapi/`、`metric/`（13 指标文件）、完整 `storage/`（47 文件）。
