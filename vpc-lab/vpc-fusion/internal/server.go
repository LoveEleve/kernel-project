// 【复现】internal/server.go —— 对应真实清单 `internal/server.go`（"internal 组装入口"）。
// 证据等级：结构=推导（13 模块清单）；降级语义=实证（清单 3.7.11：LevelDB 健康检查失败 → 快照/对账降级内存）。
// 开源对照（R4 校订）：Kube-OVN 多 controller 组装（refs/kube-ovn/pkg/controller/ 按资源一个文件，各自生命周期）；
//   Tungsten Fabric 分层组件对照见本地材料 docs-by-topic/01 附录 A（Control Node / vRouter Agent；TF 无 Saga/Outbox）。
package internal

import (
	"context"
	"errors"
	"time"

	"go.uber.org/zap"

	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/mq/consume"
	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/service/common"
	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/service/message/binlog"
	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/service/reconcile"
	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/service/snapshot"
	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/system"
)

// KVStore —— 存储抽象：LevelDB 为默认实现；健康检查失败时切内存实现（降级不掉正确性，只丢持久化）。
// 对照开源（R4 raw 核验）：Calico felix dataplane 的 full resync 兜底
//   （projectcalico/calico felix/dataplane/linux/int_dataplane.go:2320 "will wait for the full resync"，felix 已并入 calico monorepo）——
//   同为「状态可重算」哲学：降级不掉正确性，只丢持久化（与本处 LevelDB→内存的取舍同构）。
type KVStore interface {
	Put(key string, val []byte) error
	Get(key string) ([]byte, error)
	Delete(key string) error
	Iterate(prefix string, fn func(k string, v []byte) bool) error
	Close() error
}

type Server struct {
	Logger *zap.Logger
	Role   string

	cfg Config

	// 分布式三件套
	LeaderVote *common.LeaderVoteService
	Tasks      *common.TaskService

	// 业务服务
	Snapshot  *snapshot.WorkerService
	Reconcile *reconcile.Service
	Binlog    *binlog.Service

	// 通道
	Consumer *consume.Consumer
	Store    KVStore

	comps []system.Component
}

func NewServer(configPath, role string) (*Server, error) {
	cfg, err := LoadConfig(configPath)
	if err != nil {
		return nil, err
	}
	logger, err := zap.NewProduction()
	if err != nil {
		return nil, err
	}

	s := &Server{Logger: logger, Role: role, cfg: cfg}

	// —— 依赖装配（顺序即依赖序，由启动框架执行三阶段）——
	store, err := newKVStoreWithFallback(cfg.Storage, logger) // LevelDB → memory 降级
	if err != nil {
		return nil, err
	}
	s.Store = store

	s.LeaderVote = common.NewLeaderVoteService(cfg.Redis, logger)
	s.Tasks = common.NewTaskService(cfg.Redis, cfg.Task, logger, s.dispatch)

	s.Snapshot = snapshot.NewWorkerService(cfg, store, s.Tasks, logger)
	s.Reconcile = reconcile.NewService(cfg, store, s.Tasks, logger)
	s.Binlog = binlog.NewService(cfg, store, s.Snapshot, logger)

	s.Consumer = consume.NewConsumer(cfg.Kafka, logger, s.Binlog.Handle)

	// 组件注册：Checker（健康） → Initializer（加载） → Worker（常驻）
	s.comps = []system.Component{
		system.NewChecker("redis-health", s.checkRedis),
		system.NewChecker("storage-health", func(ctx context.Context) error { return checkStore(ctx, s.Store) }),
		system.NewInitializer("leader-vote", s.LeaderVote.Init),
		system.NewInitializer("task-service", s.Tasks.Init),
		system.NewInitializer("snapshot", s.Snapshot.Init),
		system.NewInitializer("reconcile", s.Reconcile.Init),
		system.NewWorker("kafka-consume", s.Consumer.Run),
		system.NewWorker("recovery-loop", s.Tasks.RunRecovery), // 过期租约回收（常驻）
		system.NewWorker("snapshot-leader", s.Snapshot.RunLeader),
		system.NewWorker("reconcile-leader", s.Reconcile.RunLeader),
	}
	return s, nil
}

func (s *Server) Run(ctx context.Context) error {
	// 三阶段启动：任一阶段失败即整体失败（fail-fast，不带病运行）。
	if err := system.Startup(ctx, s.comps, s.Logger); err != nil {
		return err
	}
	<-ctx.Done()
	return nil
}

func (s *Server) Stop(ctx context.Context) error {
	// 停机次序（工程问题清单反推）：
	// ① 先停消费者（不再取新消息）→ ② 停 Worker（在途任务用独立预算写完终态）
	// → ③ 交还 Leader（可选，TTL 自然过期亦可）→ ④ 关闭存储。
	// ctx 为独立的 5s 预算（不随进程信号取消）——见 main.go 注释（#12 修复）。
	errs := make([]error, 0, 4)

	s.Consumer.StopAccepting()

	// 在途任务：等待最多 ctx 预算；超时任务不提交终态——靠租约过期被 Recovery 回收（幂等兜底）。
	if err := s.Tasks.DrainInFlight(ctx); err != nil {
		errs = append(errs, err)
	}
	_ = s.LeaderVote.Resign(context.Background()) // 主动交还（不等 TTL）
	if err := s.Store.Close(); err != nil {
		errs = append(errs, err)
	}
	return errors.Join(errs...)
}

// ---- 以下为占位声明（真实实现在对应模块文件中）----

type Config struct {
	Storage struct{ Path string }
	Redis   struct{ Addr string }
	Task    common.TaskConfig
	Kafka   consume.KafkaConfig
}

func LoadConfig(path string) (Config, error) { return Config{}, nil }

func newKVStoreWithFallback(_ struct{ Path string }, _ *zap.Logger) (KVStore, error) { return nil, nil }

func checkStore(ctx context.Context, s KVStore) error { _, _ = ctx, s; return nil }

func (s *Server) dispatch(ctx context.Context, task *common.Task) error { _, _ = ctx, task; return nil }

func (s *Server) checkRedis(ctx context.Context) error {
	select {
	case <-time.After(10 * time.Millisecond): // 真实实现：PING + RTT 阈值
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
