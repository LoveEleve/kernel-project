// 【复现】internal/dao/task_dao_impl.go —— 旗舰文件。
// 对应真实：vpc-fusion 任务队列 DAO（材料：task_dao_impl.go:52 PublishTask(Pipeline)、:188 优先级 Lua、
//          core-fixes commit 4/5 的 8 Lua 方案、task-service 的 claim 原子链）。
// 证据等级：4 类 key 名 / 8 脚本名 / claim 原子链 / Ownership 语义=实证；脚本原文=推断（材料自认"以仓库为准"）。
// 开源对照：SQS visibility timeout（租约）；Redis 官方 Lua 原子模式；Temporal activity（重试+心跳）。
//
// 设计要点（每一条都是被生产事故逼出来的）：
//  1) claim 必须"先验头再出队"：LINDEX 0 == 期望 taskID 才 LPOP——错领/漏领双防；
//  2) finish 的 Ownership 三重校验放在 Lua 内实时判断（status==processing && workerID 匹配 && 未过期）——
//     Go 层"读→判断→写"有窗口，旧 Worker 复活会越权提交；
//  3) requeue 三步（ZREM/RPUSH/PSETEX）必须原子——被杀在中间就是"任务凭空消失"；
//  4) data key 单独存 + TTL(24h)：队列元素只放 taskID（轻量），大 value 不挤队列、防大 key。
package dao

import (
	"context"
	"encoding/json"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	dataTTL           = 24 * time.Hour // 任务数据 TTL：防泄漏；滞留超 24h = 极端积压，靠对账重触发
	finishedDataTTL   = 60 * time.Second // 完成后短留：给"对账/查询窗口"留观测点
)

type TaskStatus string

const (
	StatusPending    TaskStatus = "pending"
	StatusProcessing TaskStatus = "processing"
	StatusCompleted  TaskStatus = "completed"
	StatusFailed     TaskStatus = "failed"
)

type TaskRecord struct {
	TaskID      string     `json:"task_id"`
	Queue       string     `json:"queue"`
	Type        string     `json:"type"` // snapshot-build / init_config / reconcile / clean-config
	ScopeID     string     `json:"scope_id"`
	ConfigName  string     `json:"config_name"`
	Status      TaskStatus `json:"status"`
	WorkerID    string     `json:"worker_id"`
	LeaseExpire int64      `json:"lease_expire_at_ms"`
	RetryCount  int        `json:"retry_count"`
	CreatedAt   int64      `json:"created_at_ms"`
}

type TaskDao struct {
	client *redis.Client
	// 旧格式兼容（迁移期）：读双 key（新 task:queue:{q} / 旧 task:queue:q），写只用新格式。
}

func NewTaskDao(client *redis.Client) *TaskDao { return &TaskDao{client: client} }

func (d *TaskDao) queueKey(q string) string      { return "task:queue:{" + q + "}" }      // Hash Tag：同队列四 key 同 slot
func (d *TaskDao) priorityKey(q string) string   { return "task:priority:{" + q + "}" }   // ZSet：高优待办
func (d *TaskDao) dataKey(q, id string) string   { return "task:data:{" + q + "}:" + id }  // String + TTL
func (d *TaskDao) processingKey(q string) string { return "task:processing:{" + q + "}" } // ZSet：score=租约过期时刻

// ---------- 8 个 Lua 脚本（列表见材料；此处给出核心 6 个 + 2 个变体） ----------

// ① publishTask：写 data（PSETEX）+ 入队（普通 RPUSH / 优先 ZADD）。
//    为什么脚本化：真实旧实现用 Pipeline——publish 的"写数据"和"入队"之间无原子性，
//    极端场景会出现"队列里有 taskID、data 已过期"的孤儿头（由 ③ 兜底丢弃）。
const publishScript = `
if ARGV[4] == "1" then
  redis.call("ZADD", KEYS[2], ARGV[3], ARGV[1])          -- 优先级：score=priority
else
  redis.call("RPUSH", KEYS[1], ARGV[1])                  -- 普通：FIFO
end
redis.call("SET", KEYS[3], ARGV[2], "PX", ARGV[5])       -- data + TTL
return 1`

// ② claimFromQueue：原子领取（材料口述的"claim 原子链"）。
//    LINDEX 验头 → LPOP → 写 processing 状态（含 workerID/lease）→ ZADD(score=leaseExpireAt)。
//    返回 0 = 头被抢/非期望任务（本轮放弃，下一轮重读——"先看不删"保证不丢）。
const claimScript = `
local head = redis.call("LINDEX", KEYS[1], 0)
if head ~= ARGV[1] then return 0 end
redis.call("LPOP", KEYS[1])
redis.call("PSETEX", KEYS[3], ARGV[4], ARGV[2])          -- processing payload
redis.call("ZADD", KEYS[2], ARGV[3], ARGV[1])            -- processing ZSet: score=leaseExpireAt
return 1`

// ③ discardQueueHead：孤儿头清理（data 已过期/无法处理的队头）——不清掉会永久阻塞队列。
const discardQueueHeadScript = `
local head = redis.call("LINDEX", KEYS[1], 0)
if head ~= ARGV[1] then return 0 end
redis.call("LPOP", KEYS[1])
return 1`

// ④ claimFromPriority：优先级出队 = ZREVRANGE 取最高 + ZREM（原子）。
const claimFromPriorityScript = `
local items = redis.call("ZREVRANGE", KEYS[1], 0, 0)
if #items == 0 or items[1] ~= ARGV[1] then return 0 end
redis.call("ZREM", KEYS[1], ARGV[1])
redis.call("PSETEX", KEYS[3], ARGV[4], ARGV[2])
redis.call("ZADD", KEYS[2], ARGV[3], ARGV[1])
return 1`

// ⑤ saveTaskToProcessing（含续约 Renew）：Ownership 校验后才刷新租约。
//    校验三条件（status==processing && workerID 匹配 && 未过期）——这就是 fencing 的工程实现，
//    防止"旧 Worker 复活给已被回收的任务续命"。KEYS[1]=processing ZSet，KEYS[2]=data。
const renewTaskScript = `
local cur = redis.call("GET", KEYS[2])
if not cur then return 0 end
local ok, rec = pcall(cjson.decode, cur)
if not ok then return 0 end
if rec.status ~= "processing" or rec.worker_id ~= ARGV[1] then return 0 end
if tonumber(rec.lease_expire_at_ms) < tonumber(ARGV[4]) then return 0 end  -- 已过期：不许续
rec.lease_expire_at_ms = tonumber(ARGV[3])
redis.call("SET", KEYS[2], cjson.encode(rec), "KEEPTTL")
redis.call("ZADD", KEYS[1], ARGV[3], ARGV[2])
return 1`

// ⑥ finishTask：终态提交（Ownership 三重校验在 Lua 内）。
//    返回 0 = 已非我方（租约过期/被重派）——调用方必须放弃提交，交给新 Worker。
const finishScript = `
local cur = redis.call("GET", KEYS[2])
if not cur then return 0 end
local ok, rec = pcall(cjson.decode, cur)
if not ok then return 0 end
if rec.status ~= "processing" or rec.worker_id ~= ARGV[1] then return 0 end
if tonumber(rec.lease_expire_at_ms) < tonumber(ARGV[3]) then return 0 end
rec.status = ARGV[4]                                        -- completed / failed
redis.call("PSETEX", KEYS[2], ARGV[5], cjson.encode(rec))   -- 短 TTL 留存，供对账/查询窗口
redis.call("ZREM", KEYS[1], ARGV[2])
return 1`

// ⑦ requeueTask：重入队（Recovery / 失败重试）。三步原子——否则可能"从 processing 删了但没回队列"。
const requeueScript = `
redis.call("ZREM", KEYS[1], ARGV[2])
redis.call("RPUSH", KEYS[2], ARGV[2])
redis.call("PSETEX", KEYS[3], ARGV[4], ARGV[3])             -- 重写 data + 重置 TTL
return 1`

// ⑧ discardPriorityHead：优先级孤儿清理（变体，语义同 ③）。
const discardPriorityHeadScript = `
local items = redis.call("ZREVRANGE", KEYS[1], 0, 0)
if #items == 0 or items[1] ~= ARGV[1] then return 0 end
redis.call("ZREM", KEYS[1], ARGV[1])
return 1`

// ---------- Go 封装（对应真实 DAO 的公开方法） ----------

// Publish：priority=true 走高优待办。生产上发布侧还有限频闸门（见 task_service.PublishThrottled）。
func (d *TaskDao) Publish(ctx context.Context, q string, rec *TaskRecord, priority bool, prio float64) error {
	payload, _ := json.Marshal(rec)
	flag := "0"
	if priority {
		flag = "1"
	}
	return d.client.Eval(ctx, publishScript,
		[]string{d.queueKey(q), d.priorityKey(q), d.dataKey(q, rec.TaskID)},
		rec.TaskID, payload, prio, flag, dataTTL.Milliseconds(),
	).Err()
}

// Claim：领取指定 taskID（期望值与队头比对在 Lua 内完成）。ok=false 表示"队头不是它"。
func (d *TaskDao) Claim(ctx context.Context, q string, taskID, workerID string, leaseTTL time.Duration) (bool, error) {
	now := time.Now()
	rec := &TaskRecord{
		TaskID: taskID, Queue: q, Status: StatusProcessing, WorkerID: workerID,
		LeaseExpire: now.Add(leaseTTL).UnixMilli(),
	}
	payload, _ := json.Marshal(rec)
	res, err := d.client.Eval(ctx, claimScript,
		[]string{d.queueKey(q), d.processingKey(q), d.dataKey(q, taskID)},
		taskID, payload, rec.LeaseExpire, leaseTTL.Milliseconds(),
	).Int()
	return res == 1, err
}

func (d *TaskDao) DiscardQueueHead(ctx context.Context, q, taskID string) (bool, error) {
	res, err := d.client.Eval(ctx, discardQueueHeadScript, []string{d.queueKey(q)}, taskID).Int()
	return res == 1, err
}

func (d *TaskDao) ClaimPriority(ctx context.Context, q string, expected string, workerID string, leaseTTL time.Duration) (bool, error) {
	now := time.Now()
	rec := &TaskRecord{TaskID: expected, Queue: q, Status: StatusProcessing, WorkerID: workerID,
		LeaseExpire: now.Add(leaseTTL).UnixMilli()}
	payload, _ := json.Marshal(rec)
	res, err := d.client.Eval(ctx, claimFromPriorityScript,
		[]string{d.priorityKey(q), d.processingKey(q), d.dataKey(q, expected)},
		expected, payload, rec.LeaseExpire, leaseTTL.Milliseconds(),
	).Int()
	return res == 1, err
}

// Renew：心跳续约（saveTaskToProcessing）。ok=false = 租约已丢 → 调用方必须放弃在途计算。
func (d *TaskDao) Renew(ctx context.Context, q, taskID, workerID string, leaseTTL time.Duration) (bool, error) {
	now := time.Now().UnixMilli()
	res, err := d.client.Eval(ctx, renewTaskScript,
		[]string{d.processingKey(q), d.dataKey(q, taskID)},
		workerID, taskID, time.Now().Add(leaseTTL).UnixMilli(), now,
	).Int()
	return res == 1, err
}

// Finish：终态提交（completed/failed）。ok=false = 已非我方（放弃提交，交还重派）。
func (d *TaskDao) Finish(ctx context.Context, q, taskID, workerID string, status TaskStatus) (bool, error) {
	now := time.Now().UnixMilli()
	res, err := d.client.Eval(ctx, finishScript,
		[]string{d.processingKey(q), d.dataKey(q, taskID)},
		workerID, taskID, now, string(status), finishedDataTTL.Milliseconds(),
	).Int()
	return res == 1, err
}

// Requeue：重入队（Recovery/重试）。仅释放 processing ZSet + 回队 + 重置 data TTL。
func (d *TaskDao) Requeue(ctx context.Context, q, taskID string, rec *TaskRecord) error {
	payload, _ := json.Marshal(rec)
	return d.client.Eval(ctx, requeueScript,
		[]string{d.processingKey(q), d.queueKey(q), d.dataKey(q, taskID)},
		taskID, payload, dataTTL.Milliseconds(),
	).Err()
}
