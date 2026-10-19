// 【复现】internal/service/common/task_service.go —— 对应真实清单同名文件
// （"分布式任务队列：Leader 发布任务、Worker 并行消费、超时任务自动恢复"）。
// 证据等级：Lease 30s / Heartbeat=TTL/3=10s / Recovery ≤60s / 并发（20/20/3/10）/ 限频（100 条 sleep）/
//           MaxRetries=3 / WorkerID="{queue}-{hostname}"：全部实证（材料多处互证；数字口径见 README）。
// 开源对照：SQS visibility timeout（租约+重投递）；Temporal（心跳+activity 重试）；Katran/DPVS 无关——
//           队列语义对照更贴近 kube-ovn 的任务处理与 Kubernetes workqueue 的指数退避思路。
package common

import (
	"context"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/dao"
)

type TaskConfig struct {
	Concurrency     map[string]int // init=20 / snapshot=20 / reconcile=3 / clean=10（材料口径）
	LeaseTTL        time.Duration  // 30s
	Heartbeat       time.Duration  // 10s = LeaseTTL/3（留两次失败机会 + 时钟漂移余量）
	RecoveryEvery   time.Duration  // ≤60s：过期扫描周期
	MaxRetries      int            // 3
	PublishBatch    int            // 限频粒度（每 100 条 sleep）
	PublishSleepMs  map[string]int // init=5s / reconcile=10s 每 100 条
}

func DefaultTaskConfig() TaskConfig {
	return TaskConfig{
		Concurrency:    map[string]int{"init_config": 20, "snapshot-build": 20, "reconcile": 3, "clean-config": 10},
		LeaseTTL:       30 * time.Second,
		Heartbeat:      10 * time.Second,
		RecoveryEvery:  60 * time.Second,
		MaxRetries:     3,
		PublishBatch:   100,
		PublishSleepMs: map[string]int{"init_config": 5000, "reconcile": 10000},
	}
}

type Handler func(ctx context.Context, rec *dao.TaskRecord) error

type TaskService struct {
	cfg    TaskConfig
	dao    *dao.TaskDao
	log    *zap.Logger
	handle Handler

	workerID   string
	inFlight   sync.WaitGroup    // 在途任务计数（优雅停机 Drain 用）
	leaseLosts sync.Map          // taskID -> chan struct{}：心跳发现租约丢失时取消在途计算
	pubCount   int64             // 原子计数：发布限频用（成功才计数）
	stopAccept atomic.Bool
}

func NewTaskService(_ struct{ Addr string }, cfg TaskConfig, log *zap.Logger, h Handler) *TaskService {
	host, _ := os.Hostname()
	return &TaskService{cfg: cfg, log: log, handle: h, workerID: "fusion-worker-" + host}
}

func (s *TaskService) Init(ctx context.Context) error { _ = ctx; return nil }

// ---------- Worker：消费循环（按队列并发） ----------

// RunWorker 单队列消费循环。并发模型 = 信号量（Semaphore），与真实实现一致。
// 注意：这里用「轮询 + 原子领取」而不是 BLPop —— BLPop 取出即删，进程死于两步之间任务就丢了。
func (s *TaskService) RunWorker(ctx context.Context, queue string) error {
	concurrency := s.cfg.Concurrency[queue]
	if concurrency == 0 {
		concurrency = 3
	}
	sem := make(chan struct{}, concurrency)

	for !s.stopAccept.Load() {
		select {
		case <-ctx.Done():
			return nil
		case sem <- struct{}{}:
		}

		// 试领：先看队头（进入 Lua 后会再验一次——"先看不删"）
		head, err := s.peekHead(ctx, queue)
		if err != nil || head == "" {
			<-sem
			time.Sleep(200 * time.Millisecond) // 空转退避
			continue
		}
		ok, err := s.dao.Claim(ctx, queue, head, s.workerID, s.cfg.LeaseTTL)
		if err != nil || !ok {
			// 队头被抢/非我方期望：本轮放弃（下一轮重读，不丢任务）
			<-sem
			// 孤儿头：data 已被 TTL 清掉 → 显式丢弃，否则永久阻塞队列
			if s.isOrphan(ctx, queue, head) {
				_, _ = s.dao.DiscardQueueHead(ctx, queue, head)
			}
			continue
		}

		s.inFlight.Add(1)
		leaseLost := make(chan struct{})
		s.leaseLosts.Store(head, leaseLost)

		go func(taskID string) {
			defer func() {
				s.inFlight.Done()
				s.leaseLosts.Delete(taskID)
				<-sem
			}()
			s.processOne(ctx, queue, taskID, leaseLost)
		}(head)
	}
	return nil
}

// processOne：任务执行 = 心跳协程 + 业务处理 + 原子终态提交。
func (s *TaskService) processOne(ctx context.Context, queue, taskID string, leaseLost chan struct{}) {
	rec, err := s.loadTask(ctx, queue, taskID)
	if err != nil || rec == nil {
		return
	}

	// 心跳：只刷租约（LeaseExpireAt），**不刷 StartedAt**——StartedAt 是执行耗时证据。
	hbCtx, hbCancel := context.WithCancel(ctx)
	defer hbCancel()
	go s.heartbeat(hbCtx, queue, taskID, leaseLost)

	start := time.Now()
	runErr := s.handle(ctx, rec)
	duration := time.Since(start)

	select {
	case <-leaseLost:
		// 租约已丢：我方提交会被 ownership 拒绝，且任务已被重派给新 Worker。
		// 原则：不抢交（不调用 Finish）——宁可"多做一遍"（幂等），不能"覆盖别人"。
		s.log.Warn("lease lost, abandon submit", zap.String("task", taskID), zap.Duration("ran", duration))
		return
	default:
	}

	status := dao.StatusCompleted
	if runErr != nil {
		status = dao.StatusFailed
	}
	ok, err := s.dao.Finish(ctx, queue, taskID, s.workerID, status)
	if err != nil || !ok {
		// 提交失败 = 已非我方（与 leaseLost 等价）——交给 Recovery 兜底。
		s.log.Warn("finish rejected", zap.String("task", taskID))
	}
}

func (s *TaskService) heartbeat(ctx context.Context, queue, taskID string, leaseLost chan struct{}) {
	t := time.NewTicker(s.cfg.Heartbeat)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			ok, err := s.dao.Renew(ctx, queue, taskID, s.workerID, s.cfg.LeaseTTL)
			if err != nil || !ok {
				close(leaseLost) // 通知执行体放弃提交（幂等兜底下一个 Worker 重做）
				return
			}
		}
	}
}

// ---------- Recovery：过期租约回收（常驻 Worker） ----------

// RunRecovery：扫描 processing ZSet 中 score ≤ now 的任务 → 双重检查 → 重入队。
// 「双重检查」防的是"扫描与续租并发"：候选集取出后，真实实现在重入队前再次确认
// 该任务仍未被续约（score 未更新）且未完成——健康续租的任务根本不会被扫到。
func (s *TaskService) RunRecovery(ctx context.Context) error {
	t := time.NewTicker(s.cfg.RecoveryEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-t.C:
			for queue := range s.cfg.Concurrency {
				s.recoverQueue(ctx, queue)
			}
		}
	}
}

func (s *TaskService) recoverQueue(ctx context.Context, queue string) {
	expired, err := s.dao.ScanExpired(ctx, queue, time.Now().UnixMilli())
	if err != nil {
		return
	}
	for _, taskID := range expired {
		rec, err := s.loadTask(ctx, queue, taskID)
		if err != nil || rec == nil {
			// data 都没了：孤儿 → 直接丢弃 processing 记录
			_ = s.dao.Requeue(ctx, queue, taskID, &dao.TaskRecord{TaskID: taskID})
			continue
		}
		rec.RetryCount++
		if rec.RetryCount > s.cfg.MaxRetries {
			rec.Status = dao.StatusFailed
			_, _ = s.dao.Finish(ctx, queue, taskID, rec.WorkerID, dao.StatusFailed) // 落 failed，等人看
			continue
		}
		// 崩溃恢复最坏界：LeaseTTL(30s) + Recovery 周期(60s) ≈ 90s；典型 30-60s。
		if err := s.dao.Requeue(ctx, queue, taskID, rec); err != nil {
			s.log.Warn("requeue failed", zap.String("task", taskID), zap.Error(err))
		}
	}
}

// ---------- 发布侧：限频（保护下游 Redis/API/MySQL） ----------

// PublishThrottled：每 PublishBatch 条 sleep 一次 → 实测 ~10 tasks/s（首启 200 万任务 ≈ 27.8h 的由来）。
// 细节：**发布成功才计数**——失败不虚耗限频额度（这也是"为什么不用令牌桶"的一部分：
// 单 goroutine 产任务，atomic+sleep 最简，且失败不占用配额）。
func (s *TaskService) PublishThrottled(ctx context.Context, queue string, rec *dao.TaskRecord) error {
	if err := s.dao.Publish(ctx, queue, rec, false, 0); err != nil {
		return err
	}
	n := atomic.AddInt64(&s.pubCount, 1)
	if n%int64(s.cfg.PublishBatch) == 0 {
		if ms := s.cfg.PublishSleepMs[queue]; ms > 0 {
			select {
			case <-ctx.Done():
			case <-time.After(time.Duration(ms) * time.Millisecond):
			}
		}
	}
	return nil
}

// DrainInFlight：优雅停机——等待在途任务在独立预算内写完终态（见 main.go 的 #12 注释）。
func (s *TaskService) DrainInFlight(ctx context.Context) error {
	s.stopAccept.Store(true)
	done := make(chan struct{})
	go func() { s.inFlight.Wait(); close(done) }()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		// 超时：未完成任务不提交——租约过期后由 Recovery 重派（幂等兜底）。
		return ctx.Err()
	}
}

// ---- 小块辅助（真实实现位于 storage/dao 层）----

func (s *TaskService) peekHead(ctx context.Context, queue string) (string, error) {
	return "", nil // 真实：LINDEX queue 0（读旧 key 兼容见 dao 注释）
}

func (s *TaskService) isOrphan(ctx context.Context, queue, taskID string) bool { return false }

func (s *TaskService) loadTask(ctx context.Context, queue, taskID string) (*dao.TaskRecord, error) {
	return nil, nil
}

var _ = redis.Nil
