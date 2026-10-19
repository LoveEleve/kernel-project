// 【复现】internal/service/snapshot/worker_service.go —— 对应真实清单同名文件
// （"Worker：读 MySQL 全量数据序列化 Protobuf 写入 LevelDB 并更新版本"）。
// 证据等级：分片 10000 / cloneSnapshot / 触发三条件 / Put(nil) 事故与修复（commit 430b5ab）=实证；
//           构建时序=推导（position/snapshot-structure 材料）。
// 开源对照（R3 校订）：LSMT 的"内存表→不可变快照"思想（本文件是"权威库→可分发快照"的同构）；
//   kube-ovn 把 VPC/Subnet 翻译进集中式 OVN NB DB（refs/kube-ovn/pkg/ovs/ovn-nb-*.go）——
//   集中式数据库 vs 本项目"分片快照+增量分发"的对照（前版"Kube-OVN 快照推送"表述不准确，已改）。
package snapshot

import (
	"context"
	"fmt"

	"go.uber.org/zap"

	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/dao"
)

// 分片粒度（材料口径：10000 条/片）：控制单快照体积 + 未变分片可 clone 不重建。
const shardSize = 10000

type KVStore interface { // 对应 internal/storage（LevelDB 实现，健康失败降级内存）
	Put(key string, val []byte) error
	Get(key string) ([]byte, error)
	Delete(key string) error
	Iterate(prefix string, fn func(k string, v []byte) bool) error
}

type WorkerService struct {
	store KVStore
	tasks *TaskSvc
	log   *zap.Logger

	snapshotTrigger chan struct{} // binlog 批次（1000）或命令触发快照
}

type TaskSvc interface{ /* 占位：任务队列接口（见 service/common/task_service.go） */ }

func NewWorkerService(_ any, store KVStore, tasks *TaskSvc, log *zap.Logger) *WorkerService {
	return &WorkerService{store: store, tasks: tasks, log: log, snapshotTrigger: make(chan struct{}, 1)}
}

func (s *WorkerService) Init(ctx context.Context) error { _ = ctx; return nil }

// RunLeader：快照构建调度（域=snapshot-build-leader）。
// 触发方式（材料实证）：① 60s 定时 ② binlog 攒批到 1000 ③ 600s 兜底 ④ 手动命令。
// 调度单位 = (configName, scopeId) 分片；未变分片走 cloneSnapshot（读旧值、只改版本号）。
func (s *WorkerService) RunLeader(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-s.snapshotTrigger:
			s.buildAll(ctx, "vpc")
		}
	}
}

// Handle 是 Worker 侧的任务执行体：构建一个 scope 的快照。
func (s *WorkerService) Handle(ctx context.Context, rec *dao.TaskRecord) error {
	scope := rec.ScopeID
	config := rec.ConfigName

	// 1) 读权威（MySQL 分片表 config_{name}_{shard}，50ms 级）——分片遍历按 shardSize 切批。
	full, err := s.loadFull(ctx, config, scope)
	if err != nil {
		return fmt.Errorf("load full %s/%s: %w", config, scope, err)
	}

	// 2) 合并 binlog 增量：LevelDB 临时工作区（mergeBinlog）。
	//    INSERT/UPDATE → SaveConfig；DELETE → **DeleteConfig**（见下方事故注释）。
	if err := s.mergeBinlog(ctx, config, scope); err != nil {
		return err
	}

	// 3) 序列化 + 压缩 + 落库（t_generic_snapshot 之外，LevelDB 侧键形如
	//    snapshot:{scope}:{config}:{shardVersion}:{offset}），版本单调递增。
	if err := s.persist(ctx, config, scope, full); err != nil {
		return err
	}
	return nil
}

// cloneSnapshot：未变分片不重建——读旧版本快照，仅将版本号 +1 写回。
// 动机：分片内 10000 条中常只有几条变化，全量重算是 3-9h 对账窗口之外的又一处规模税。
func (s *WorkerService) cloneSnapshot(ctx context.Context, key string, newVersion int64) error {
	old, err := s.store.Get(key)
	if err != nil {
		return s.rebuild(ctx, key, newVersion)
	}
	_ = old // 真实实现：解析 → 改 version 字段 → 重新编码；此处省略编解码细节
	return s.store.Put(key, old)
}

// ---------------------------------------------------------------------------
// DeleteConfig —— 全课程最重要的一段注释（事故 + 修复 + 工程教训）。
//
// BUG（修复前，master 分支曾长期存在）：
//   DELETE binlog → LevelDB.Put(key, nil)
//   Put(nil) 不是删除：key 还在、value 变成空字节。
//   → 快照生成时前缀遍历"看到"该 key → 已删配置被装回快照（"复活"）
//   → broker 推送 → agent 侧反序列化空字节失败/保留旧路由 → 流量发往已销毁 VM
//   → 直到对账把这条报成 Redundant 才可能被发现。
//
// FIX（commit 430b5ab，bugfix/disorder 分支）：
//   db.Delete(key)——key 真正消失，遍历不再返回。
//
// 测试教训（比修复本身更值钱）：
//   原单测断言 `Get(key) == nil`——Put(nil) 之后 Get 也是 nil，"看起来删了"。
//   正确观测点：**删除后遍历必须为空**（Get 观测点 vs Iterate 观测点）。
// ---------------------------------------------------------------------------
func (s *WorkerService) DeleteConfig(ctx context.Context, config, scope, identifier string) error {
	key := s.configKey(config, scope, identifier)
	// 修复语义：物理删除；对账侧"key 不存在 = 配置不存在"，不再有歧义。
	return s.store.Delete(key)
}

// ---- 内部方法占位（真实实现在 storage/dao 层；此处保留签名与职责注释）----

func (s *WorkerService) loadFull(ctx context.Context, config, scope string) ([]byte, error) { return nil, nil }
func (s *WorkerService) mergeBinlog(ctx context.Context, config, scope string) error        { return nil }
func (s *WorkerService) persist(ctx context.Context, config, scope string, full []byte) error { return nil }
func (s *WorkerService) rebuild(ctx context.Context, key string, version int64) error       { return nil }
func (s *WorkerService) configKey(config, scope, id string) string {
	return fmt.Sprintf("config:%s:%s:%s", scope, config, id)
}
func (s *WorkerService) buildAll(ctx context.Context, _ string) {}
