// 【复现】internal/service/reconcile/reconcile.go —— 对应真实清单同名文件
// （"核心比对算法：产出多余/缺失/不一致条目"；配套 load_data.go/exporter.go）。
// 证据等级：三类差异、双侧来源、只报告不修复、阈值保护、连续确认：实证（consistency/reconcile 材料 + 源码口径
//           DoReconcile: LoadLeft/LoadRight/Reconcile + exporter.Export 无修复逻辑）。
// 开源对照（R3 校订）：Kubernetes controller 的 desired vs actual——controller-runtime 的
//   `Reconciler` 接口 + `Result{RequeueAfter}`（refs/controller-runtime/pkg/reconcile/reconcile.go:44）：
//   失败 requeue + 退避、**会 converge**；本项目的"报告制"（只报告不修复）的完整哲学对照见
//   本地材料 test-good/06-open-source-vs/vs-k8s-controller.md（Event Sourcing + 定时验证 vs Reconcile Loop）；
//   另对照（R4 raw 核验）Calico felix dataplane 的 full resync：重建"自己模型内"的状态
//   （projectcalico/calico felix/dataplane/linux/int_dataplane.go:2320）——对模型外一无所知，
//   同构于我们"只报告比对范围内的差异"。
package reconcile

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"
)

type DiffType string

const (
	DiffMissing   DiffType = "missing"   // 期望有、实际无 → 可能"还在正常路径上"（延迟），需等待窗口
	DiffRedundant DiffType = "redundant" // 期望无、实际有 → 可能误报（权限/分页/时间窗），**禁止自动删**
	DiffMismatch  DiffType = "mismatch"  // 双方都有但内容不一致
)

type Diff struct {
	Type    DiffType
	ScopeID string
	Config  string
	Key     string
	Left    string // 期望态摘要（VPC API）
	Right   string // 实际态摘要（LevelDB 快照）
}

type Result struct {
	ScopeID   string
	Missing   []Diff
	Redundant []Diff
	Mismatch  []Diff
	StartedAt time.Time
	Cost      time.Duration
}

// 阈值保护（材料口径 DelMoreThreshold 同族）：一次要删太多 = 大概率"数据源读残"而非真差异。
// 超过阈值不是"删得更猛"，而是**降级为仅标注、交人审**——防御机制必须防自己被自己利用。
const delMoreThreshold = 5000

type Service struct {
	log *zap.Logger
	// LeftSource：VPC API（期望态）——被 initconfig/reconcile 复用的 vpcapi 客户端
	// RightSource：LevelDB 快照（实际态）
}

func NewService(_ any, _ any, _ any, log *zap.Logger) *Service { return &Service{log: log} }

func (s *Service) Init(ctx context.Context) error { _ = ctx; return nil }

// RunLeader：对账调度（域=reconcile）：全量每天一次 + 变动每 5 分钟一次。
// 全量 200 万 VPC 的规模账（容量模型口径）：10 worker × ~60ms/VPC ≈ 166 VPC/s → 理论 3.4h、实际 3-9h。
// 工程约束：低峰执行（凌晨 2-4 点）+ 独立连接池 + 优先读本地快照——**不抢生产资源**。
func (s *Service) RunLeader(ctx context.Context) error {
	full := time.NewTicker(24 * time.Hour)
	changed := time.NewTicker(5 * time.Minute)
	defer full.Stop()
	defer changed.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-full.C:
			s.run(ctx, "full")
		case <-changed.C:
			s.run(ctx, "changed")
		}
	}
}

// Run 单次对账：两阶段加载 + 比对 + 导出。**没有修复分支**——这不是遗漏，是设计。
func (s *Service) Run(ctx context.Context, mode string) (*Result, error) {
	start := time.Now()
	res := &Result{StartedAt: start}

	// 阶段 1：加载左（期望态）。左来自 API——注意"读的时候世界在变"，结果天然是"某时刻的看法"。
	left, err := s.loadLeft(ctx, mode)
	if err != nil {
		return nil, fmt.Errorf("load left: %w", err)
	}
	// 阶段 2：加载右（实际态）。左右之间的时间差，就是判定必须"随时间收敛"的原因。
	right, err := s.loadRight(ctx, mode)
	if err != nil {
		return nil, fmt.Errorf("load right: %w", err)
	}
	res.Cost = time.Since(start)

	// 阶段 3：比对（三类差异）。
	leftIdx, rightIdx := indexByKey(left), indexByKey(right)
	for key, l := range leftIdx {
		r, ok := rightIdx[key]
		if !ok {
			res.Missing = append(res.Missing, Diff{Type: DiffMissing, Key: key, Left: l})
		} else if l != r {
			res.Mismatch = append(res.Mismatch, Diff{Type: DiffMismatch, Key: key, Left: l, Right: r})
		}
	}
	for key, r := range rightIdx {
		if _, ok := leftIdx[key]; !ok {
			res.Redundant = append(res.Redundant, Diff{Type: DiffRedundant, Key: key, Right: r})
		}
	}

	// 阈值保护：多余量超过阈值 → 标注"疑似读残"，全量交人审。
	if len(res.Redundant) > delMoreThreshold {
		s.log.Warn("redundant over threshold, need human review",
			zap.Int("count", len(res.Redundant)), zap.Int("threshold", delMoreThreshold))
	}

	// 阶段 4：导出（仅报告）。为什么不做"自动修复"——三个误修现场（材料实证推演）：
	//   ① Missing 误修：配置其实"还在 binlog 路上" → 重复下发（浪费且放大写路径）；
	//   ② Redundant 误删：读 DB 恰逢写入窗口 → **把正在创建的配置删掉 → 直接流量损失**；
	//   ③ 结果过期：比对期间两侧都在变 → 修复动作与正常路径竞争（时序倒挂）。
	// 结论：对账是"观测者"，执行者是 binlog 正常路径 / cleanconfig 显式规则 / 人工。
	Export(res)

	// 降噪机制（源自 vpc-base reconciliation 框架，工程化沉淀）：
	//   - DiffHistory：同一差异连续出现才升级（防单周期抖动）；
	//   - ConfirmCount≥3：连续 3 个周期确认 → 才进入"确定差异"集合（只影响告警级别，不影响是否修复——我们本来就不修）。
	s.recordHistory(res)
	s.log.Info("reconcile done", zap.String("mode", mode),
		zap.Int("missing", len(res.Missing)), zap.Int("redundant", len(res.Redundant)),
		zap.Int("mismatch", len(res.Mismatch)), zap.Duration("cost", res.Cost))
	return res, nil
}

// ---- 以下为骨架占位 ----

func (s *Service) loadLeft(ctx context.Context, mode string) (map[string]string, error) { return nil, nil }
func (s *Service) loadRight(ctx context.Context, mode string) (map[string]string, error) { return nil, nil }
func (s *Service) recordHistory(res *Result)                                             {}

func indexByKey(m map[string]string) map[string]string { return m }

// Export：对应 exporter.go——差异数量/增长趋势进 Prometheus；
// 生产教训：**趋势告警**优于绝对值告警（DeleteConfig 事故中，Redundant 绝对值阈值被"告多了就调高"，反而钝化）。
func Export(res *Result) {}
