// 【复现】internal/system/startup.go —— 对应真实规范 `.codebuddy/rules/system-startup-framework.md`
// （"Checker/Initializer/Worker 三阶段启动框架"）。
// 证据等级：结构=推导（文件清单描述 + 规范名）；panic 兜底/失败即停=工程惯例。
// 开源对照：k8s component-base `PostStartHook` 两阶段（healthz → run）；Kube-OVN 各 controller 的 Start。
package system

import (
	"context"
	"fmt"

	"go.uber.org/zap"
)

// 三阶段语义：
//   Checker     —— 只读探活（依赖是否可用）。失败 = 启动失败（fail-fast）。
//   Initializer —— 加载/注册（顺序执行，可依赖前序完成）。
//   Worker      —— 常驻循环（并发启动，随 ctx 退出）。
type Phase int

const (
	PhaseChecker Phase = iota
	PhaseInitializer
	PhaseWorker
)

type Component interface {
	Name() string
	Phase() Phase
	Run(ctx context.Context) error
}

// —— 适配器：让函数签名统一进 Component 接口（真实框架同样以闭包注册为主）——

type funcComp struct {
	name  string
	phase Phase
	fn    func(context.Context) error
}

func (c *funcComp) Name() string                   { return c.name }
func (c *funcComp) Phase() Phase                   { return c.phase }
func (c *funcComp) Run(ctx context.Context) error  { return c.fn(ctx) }

func NewChecker(name string, fn func(context.Context) error) Component {
	return &funcComp{name: name, phase: PhaseChecker, fn: fn}
}
func NewInitializer(name string, fn func(context.Context) error) Component {
	return &funcComp{name: name, phase: PhaseInitializer, fn: fn}
}
func NewWorker(name string, fn func(context.Context) error) Component {
	return &funcComp{name: name, phase: PhaseWorker, fn: fn}
}

// Startup 按阶段推进：
//  1) 顺序跑完所有 Checker（任一失败返回错误）；
//  2) 顺序跑完所有 Initializer（任一失败返回错误）；
//  3) 并发启动所有 Worker；**返回即代表已在运行**。
//
// 每个组件自带 panic 隔离：单组件 panic 只杀自己并上报错误（真实系统在此处还会写巡检事件）。
func Startup(ctx context.Context, comps []Component, log *zap.Logger) error {
	byPhase := map[Phase][]Component{}
	for _, c := range comps {
		byPhase[c.Phase()] = append(byPhase[c.Phase()], c)
	}

	for _, phase := range []Phase{PhaseChecker, PhaseInitializer} {
		for _, c := range byPhase[phase] {
			if err := runGuarded(ctx, c, log); err != nil {
				return fmt.Errorf("startup phase %d component %s: %w", phase, c.Name(), err)
			}
			log.Info("startup step ok", zap.String("component", c.Name()), zap.Int("phase", int(phase)))
		}
	}

	// Worker 常驻：错误经 channel 上报（真实实现汇聚到节点健康状态，触发 watchdog 重启）。
	for _, c := range byPhase[PhaseWorker] {
		w := c
		go func() {
			if err := runGuarded(ctx, w, log); err != nil && ctx.Err() == nil {
				log.Error("worker exited", zap.String("component", w.Name()), zap.Error(err))
			}
		}()
	}
	return nil
}

func runGuarded(ctx context.Context, c Component, log *zap.Logger) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("component %s panic: %v", c.Name(), r)
			log.Error("component panic", zap.String("component", c.Name()))
		}
	}()
	return c.Run(ctx)
}
