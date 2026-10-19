---
title: "01-写侧：vpc-api-server（Saga / 版本 / 分发）与 vpc-protos 元数据驱动"
series: "VPC 5.0（Gen3）架构系列"
previous: "00-overview.md"
next: "02-fusion.md"
source_refs:
  - "vpc/docs-by-topic/34-vpc-api-server.md（685 行全读：Saga 框架/请求主链路/版本生成/分发/轮转/启动流程）"
  - "vpc/docs-by-topic/41-vpc-protos.md（569 行全读：元数据驱动代码生成/105-116 口径）"
  - "vpc/docs-by-topic/35-vpc-base.md（目录级：~20 公共包清单）"
  - "vpc/test-good/02-vpc5-gen3/vpc5.0-overview.md（写侧段）"
---

# 01-写侧：vpc-api-server（Saga / 版本 / 分发）与 vpc-protos 元数据驱动

> Gen3 写侧 = `vpc-api-server`（接入+编排+分发）＋ `vpc-protos`（元数据驱动）＋ `vpc-base`（公共库）。
> 标注：【实证】= 材料有据；【推导】= 按约束推导；【开放】= 材料不足。

## §0 它干了什么（例子先行）

一次业务配置写入（如挂载 ENI）的完整旅程：

```
POST /tvpcapi/api?action=TVPCAPI.{Package}.{Action}（如 genericConfig 包的 SetGenericConfig）
  → gin → RequestIdMiddleware → ErrorHandler → CommonFieldsMiddleware
  → Action 分发到 handler（handler_register.go 注册表，代码生成）
  → handler 创建 Saga Handle（InitWithSliceAndHooks：拆批/校验/去重）
  → Handle.Process：Worker 并发执行（maxConcurrency 信号量控制）
      Worker 步骤链：BatchCheckExists → 写 PrimaryDB(BatchApplyDB) → 写 BinlogDB → Dispatch(Kafka)
      任一步失败：Worker 内逆序回滚 → Handle 回滚所有成功 Worker
  → outcome 汇总 → helper.OutcomeResponse 回写 HTTP 响应
下游：BinlogDispatcher 序列化 Protobuf → CtrlSetRouter 算目标 SET → KafkaManager 选主备 producer → 发各 SET Kafka
```

**输入**：业务语义请求（当前生产入口 = 控制台/云API → vpc-cgw → sdn_oss；vpc-api-server 为 VPC5.0 自建接入层、sdn_oss 的迁移目标——见 00 §3 尾注）。**输出**：双 DB（全量+增量）+ Kafka binlog（protobuf）。

## §1 掌握卡（7 条）

1. **Action 风格单端点**：`POST <path>?action=TVPCAPI.{Package}.{Action}`；路由注册表 `handler_register.go` 由 `build/api_build.sh` 代码生成。
2. **Saga = Handle→Worker→Step 三层**：拆批并发（信号量）→ 步骤链顺序执行 → 失败**逆序回滚**；SkipError 跳过预期失败。
3. **双 DB**：PrimaryDB（全量表）+ BinlogDB（增量表）；迁移 SQL 由 vpc-protos 同步生成（Flyway 式版本化）。
4. **版本号 = 三维单调递增**（ResourceScopeType+ScopeID+ConfigName）：Redis Lua 原子自增（校验上下界，消除 TOCTOU）+ version_state 表双通路兜底。
5. **分发三件套**：BinlogDispatcher（分组/序列化/发送）→ CtrlSetRouter（4 scope 算目标 SET）→ KafkaManager（主备双 producer + 三后台协程 + 优先级选主）。
6. **元数据驱动（vpc-protos）**：txtpb → proto/GORM/SQL/registry/桩代码全自动；biz/control 每配置 3 产物（CONFIG/BINLOG_API/BINLOG_FUSION，api_server/vpc_fusion 为 INTERNAL 单产物）；field_number 不可变 + breaking 门禁。
7. **后台三服务**：rotation（binlog 表按月轮转）/ resourcectrlsetdeletion（延迟清理，GuardFields 防误删）/ shard（按 configName+Scope 分片，shard_counter 选最空）。

## §2 vpc-api-server 全貌

> **上游与定位（勘误补录）**：当前生产链路 = 控制台/云 API v3 → vpc-cgw → sdn_oss（已适配 VPC5.0 对象模型）→ Kafka protobuf binlog；vpc-api-server 是 VPC5.0 自建的**接入层**（repo 归档《VPC5.0-接入层详细设计》），调用方为内部系统（vpc-ops 运维编排等），按"迁移切片"逐步接管 sdn_oss。**不是控制台用户请求的直连后端**（详情见 00 §3 尾注）。

- **技术栈**：Go 1.25 + Gin + GORM + go-redis + franz-go（Kafka）+ Protobuf + zap + swaggo。
- **App 容器**（sync.Once 单例）：PrimaryDB / BinlogDB / Redis / BinlogDispatcher / KafkaManager / CtrlSetRouter / VersionGen / RotationMgr / OSSClient。
- **启动流程**：config → 三路日志（业务/访问/主调）→ global tracer → app.Init → 延迟清理 Manager → HTTP server → Polaris 心跳 → Run（SIGINT/SIGTERM 优雅关闭，LIFO defer）。
- **watchdog**：`start` 子命令拉起 worker 子进程，健康检查 `/up`，**MaxRetries=-1 无限重启**；PID/锁在 `/tmp/vpc-api-server.watchdog.*`。
- **业务配置总入口**：`genericConfig` 包（最大的 handler 包）——`set_generic_config`（任意 configName 统一 CRUD）/ `set_config_with_obj`（VPC/Owner/Host scope 校验与路由）/ `get_all_configs` / `get_binlogs`（按 version 拉增量）/ `get_scope_ids`（供下游对账）。

## §3 Saga 框架（internal/pkg/saga）

| 件 | 职责 |
|---|---|
| BaseHandle | 请求拆批、Worker 并发调度（信号量控并发）、失败回滚全部成功 Worker |
| BaseWorker | 步骤顺序执行与**逆序回滚**、SkipError 跳过机制 |
| Step | forward/backward 函数对（反射取名或手动命名） |
| BatchCRUDContext[T] | 泛型 CRUD 助手：BatchCheckExists / BatchInsertDB / BatchUpdateDBByUpdates / BatchDeleteDB（带回滚） |
| SetupBatchCRUD + WithInit + BatchApplyDB | 标准流水线装配（新 handler 推荐入口） |
| dedup | 批内去重（canonical identity 归一化） |
| outcome | 处理结果语义（ALREADY_EXISTS/NOT_FOUND 预判定 + 排序/合并） |
| pipeline / errors | CRUD 契约（操作常量/Hook）+ 错误体系（StepError/WorkerError/RollbackError/SkipError） |

## §4 版本号生成（internal/service/version）

- 维度：`ResourceScopeType + ScopeID + ConfigName` 单调递增。
- 实现：**Redis Lua 原子自增**（脚本内校验上下界 + INCRBY——消除多实例 TOCTOU 竞态）+ **DB version_state 表双通路兜底**。

## §5 分发链路（binlog → SET Kafka）

```
BinlogDispatcher（按 key 分组 → 序列化 Protobuf → 发送到各 SET Kafka）
  + topic_resolver（依 registry 元数据确定 configName → Kafka topic）
CtrlSetRouter（按 ScopeType：Global 广播 / VPC / OWNER / HOST → 目标 SET 列表）
KafkaManager（从 coreCtrlSet/kafkaMaster 表加载实例；主备双 producer；健康探测/主备同步/增删同步三后台协程；优先级选主）
```

## §6 元数据驱动（vpc-protos）

```
config_meta/data/{location}/{name}_meta.txtpb + _keys.txtpb   （手写唯一事实源）
  → proto-gen → config/autogen/*.proto（biz/control 每配置 3 份：基础 / _binlog_api / _binlog_fusion；api_server/vpc_fusion 每配置 1 份）
  → buf generate + protoc-go-inject-tag → gen/go/config/autogen（GORM 模型 + registry + interfaces + ext）
  → sql-gen → gen/sql（DDL）→ init-data-gen → dbsql-package-gen → gen/dbsql（Flyway 式迁移包）
  → buf/trpc → gen/grpc、gen/trpc（桩代码）
```

- **location 4 类**：biz / control / api_server / vpc_fusion。
- **ConfigType 五形态**：BASIC / LEVEL / MAP_LEVEL / MAP_BASIC / INTERNAL（model/v1/config_meta_type.proto）。
- **约束**：`field_number` 一经分配不可变更；`buf breaking` 门禁（字段删除须双 reserved）；`gen/` 与 `config/autogen/` **必须提交入库**；质量门禁含 check-generated / dbsql-correctness。
- 【口径冲突】配置数量两说：overview/business-capabilities = **105**（74 biz + 15 control + 8 api_server + 8 fusion）；protos 文档实列 = **80 biz + 15 + 9 + 12**（≈116）——差异在 biz/api_server/fusion 计数，**标【开放】**（以 registry 实际为准）。

## §7 后台服务与工具

- **rotation**（binlog 表月度轮转）：scanner（扫需轮转表）→ rotator（新建当月/归档旧表 DDL）→ cleaner（超保留月清理）→ query_router（按时间路由归档/当前表）+ 轮转锁租约 + rotation_state。
- **resourcectrlsetdeletion**：基于 vpc-base distributed-task 的延迟清理（Worker 校验 resourceObj 残留后物理删除或超时取消；**GuardFields 防误删**；标记 pending_deletion）。
- **shard**（34 §3.8）：`shard_id` 按 configName+Scope 计算；`shard_counter` 表维护计数选**最空分片**。（vpc-protos 侧另有 `utils/sharding.go` FNV-1a 分表索引计算——两者层次不同，勿混。）
- **vpc-base**（公共库，~20 包）：app / config / logger / metrics / http / db-client / redis-client / cache-client（Cache-Aside）/ kafka-client / naming（服务发现）/ reconciliation / fusion-sdk / echo / distributed-lock / distributed-task / leader-election / watchdog / trace / schedule / scope / local-cache / cos。

## §8 与 Gen2 写侧对照

| 维度 | Gen2（sdn_oss，Python） | Gen3（vpc-api-server，Go） |
|---|---|---|
| 事务 | Saga + 经典 Outbox（DB 先提交 + 异步补发） | Saga **同步补偿**（Kafka 失败 = DB 回滚；prepareRollbackData 反转数据） |
| 回滚 | 动态补偿（对比 DB 当前状态） | forward/backward 同一函数 + 预生成反向数据 |
| 发号 | transId 三路径（DB 自增/CRS incr/独占表） | Redis Lua 原子 INCRBY（步长预分配）+ version_state 兜底 |
| 变更出口 | 25 张 Outbox 表 → BinlogProducer 扫表 | 双 DB 直写（Primary+Binlog）+ Dispatcher 直发 |
| 元数据 | 硬编码（32 MERGE_CLASS） | txtpb 元数据驱动（零改动扩展） |

（Gen2 细节见 Gen2 系列 02 §9.5 / 09.5；"Go 同步补偿"的语义边界已在那登记。）

## §9 钩子

- binlog 的消费与合并（fusion）→ **02**；broker 扇出与 tRPC → **03**；SET 分片与迁移细节 → **05**。
