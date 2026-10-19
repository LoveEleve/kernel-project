// 【复现】internal/service/common/leader_vote_service.go —— 对应真实清单 `internal/service/common/leader_vote_service.go`
// （"Leader 选举：Redis TTL+续约，多选举域（OnElected/OnLost/OnCheck 回调）"）。
// 证据等级：参数=实证（材料引码 leader_vote_service.go:303-313：TTL=10s / Renew=3.3s / Check=30ms，
//           capacity-model / partition-splitbrain / state-machine 三源一致）；双版本差异=登记在案。
// 开源对照（R3 补证）：Kube-OVN/Cilium 的 leader election（lease 语义；kube-ovn 实现在
//           refs/kube-ovn/pkg/controller/config.go:18）；etcd campaign——我们不做多数派，
//           「谁抢到单键谁是 Leader」+「消费侧 claim 唯一性」吸收脑裂窗口（pragmatic）。
package common

import (
	"context"
	"os"
	"time"

	"go.uber.org/zap"

	"git.woa.com/vpc/vpc5.0/vpc-fusion/internal/dao"
)

// 选举域：四个独立域 = 四把互不干扰的锁（故障域隔离：某域选主失败不影响其他域产任务）。
type ElectionDomain string

const (
	DomainInitZone     ElectionDomain = "init_zone"
	DomainSnapshotLdr  ElectionDomain = "snapshot-build-leader"
	DomainReconcile    ElectionDomain = "reconcile"
	DomainCleanConfig  ElectionDomain = "clean-config-leader"
)

type LeaderVoteConfig struct {
	TTL           time.Duration // 10s（代码默认口径；面试材料另见 15s/5s，差异保留、以仓库为准）
	RenewInterval time.Duration // TTL/3 = 3.3s：容忍两次续约失败
	CheckInterval time.Duration // 30ms：非 Leader 高频探测"锁是否已空出"
}

func DefaultLeaderVoteConfig() LeaderVoteConfig {
	ttl := 10 * time.Second
	return LeaderVoteConfig{
		TTL:           ttl,
		RenewInterval: ttl / 3,
		CheckInterval: 30 * time.Millisecond,
	}
}

type LeaderVoteCallbacks struct {
	OnElected func(ctx context.Context, domain ElectionDomain) error // 上任：启动本域产任务循环
	OnLost    func(domain ElectionDomain)                            // 退位：停止产任务（不撤已产任务——消费侧幂等）
	OnCheck   func(domain ElectionDomain) bool                       // 健康门控（如依赖不可用时放弃连任）
}

type LeaderVoteService struct {
	cfg  LeaderVoteConfig
	dao  *dao.LeaderVoteDao
	node string
	log  *zap.Logger

	state map[ElectionDomain]bool // 本实例各域的主从状态（多域可同时持有）
	cb    LeaderVoteCallbacks
}

func NewLeaderVoteService(cfg struct{ Addr string }, log *zap.Logger) *LeaderVoteService {
	host, _ := os.Hostname()
	return &LeaderVoteService{
		cfg:   DefaultLeaderVoteConfig(),
		dao:   dao.NewLeaderVoteDao(nil, "leader:election"),
		node:  host + "-" + os.Args[0], // 唯一节点标识（真实系统=pod 名/主机名）
		log:   log,
		state: map[ElectionDomain]bool{},
	}
}

func (s *LeaderVoteService) Init(ctx context.Context) error { _ = ctx; return nil }

// Run 为每个域启动一条「竞选-续约」协程；本实现为单域版骨架，多域由外层循环展开。
func (s *LeaderVoteService) Run(ctx context.Context, domain ElectionDomain) error {
	ticker := time.NewTicker(s.cfg.CheckInterval)
	defer ticker.Stop()
	renewTicker := time.NewTicker(s.cfg.RenewInterval)
	defer renewTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			return nil

		case <-ticker.C: // Follower：探测锁是否空出 → 竞选
			if s.state[domain] {
				continue
			}
			if s.cb.OnCheck != nil && !s.cb.OnCheck(domain) {
				continue // 健康不达标：不参与竞选
			}
			info := dao.LeaderInfo{
				NodeID:    s.node,
				ElectedAt: time.Now(),
				ExpireAt:  time.Now().Add(s.cfg.TTL),
			}
			ok, err := s.dao.SetLeader(ctx, string(domain), info, s.cfg.TTL)
			if err != nil {
				s.log.Warn("campaign failed", zap.String("domain", string(domain)), zap.Error(err))
				continue
			}
			if ok {
				s.state[domain] = true
				s.log.Info("elected", zap.String("domain", string(domain)))
				if err := s.cb.OnElected(ctx, domain); err != nil {
					// 上任即失败：立即释放，交给下一个候选者（避免空占锁 10s）
					_, _ = s.dao.Release(ctx, string(domain), s.node)
					s.state[domain] = false
				}
			}

		case <-renewTicker.C: // Leader：续约（只有持有者能续——Lua 内校验 node_id）
			if !s.state[domain] {
				continue
			}
			if s.cb.OnCheck != nil && !s.cb.OnCheck(domain) {
				_ = s.resign(domain)
				continue
			}
			ok, err := s.dao.Renew(ctx, string(domain), s.node, time.Now().Add(s.cfg.TTL), s.cfg.TTL)
			if err != nil || !ok {
				// 锁已被别人拿走（我们不再是 Leader）：
				// 脑裂宽限——本实例可能仍在产任务，最多 TTL(10s) 的窗口；
				// 吸收位置在消费侧：claim Lua 的原子唯一性 + 任务业务键幂等（见 task_dao_impl.go）。
				s.log.Warn("renew lost", zap.String("domain", string(domain)))
				s.state[domain] = false
				if s.cb.OnLost != nil {
					s.cb.OnLost(domain)
				}
			}
		}
	}
}

func (s *LeaderVoteService) IsLeader(domain ElectionDomain) bool { return s.state[domain] }

// resign 本地退位：清状态并回调 OnLost（锁交给 TTL 自然过期；主动交还走 Resign）。
func (s *LeaderVoteService) resign(domain ElectionDomain) error {
	s.state[domain] = false
	if s.cb.OnLost != nil {
		s.cb.OnLost(domain)
	}
	return nil
}

// Resign —— 主动交还全部域的锁（优雅停机调用；Lua 内校验 nodeID，避免误删新 Leader 的锁）。
func (s *LeaderVoteService) Resign(ctx context.Context) error {
	for d := range s.state {
		if _, err := s.dao.Release(ctx, string(d), s.node); err != nil {
			return err
		}
		s.state[d] = false
	}
	return nil
}
