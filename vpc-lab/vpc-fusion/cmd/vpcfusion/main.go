// 【复现】cmd/vpcfusion/main.go —— 对应真实清单 `cmd/vpcfusion/main.go`。
// 证据等级：结构=推导（清单描述"解析 -role/-cpu/-config → panic 兜底 → NewServer → Run"）；
//           停机细节=实证（16 工程问题 #12：ctx cancel 后终态写无法执行 → detached context + 5s 超时）。
// 开源对照（R4 核验，refs/kube-ovn）：cmd/* 多二进制分发（cmd/cmdmain.go 路由到各子命令包）；
//   其中 cmd/controller/cmdmain.go 仅 29 行——"入口极薄，逻辑在 pkg/controller"实锤；
//   k8s component-base（options→run 模式，概念对照）。
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.uber.org/zap"
)

func main() {
	// 1) 参数与兜底日志：真实实现为「panic 兜底日志」——启动早期还没有 logger 时也能留下死因。
	var (
		role       = flag.String("role", "mix", "运行角色: mix|init|binlog|snapshot|reconcile")
		configPath = flag.String("config", "configs/config.yaml", "配置文件路径")
		cpu        = flag.Int("cpu", 0, "GOMAXPROCS 上限（0=默认）")
	)
	flag.Parse()
	_ = cpu

	defer func() {
		if r := recover(); r != nil {
			// 兜底日志：写文件 + stderr（真实系统此处落盘到 logs/panic.log）
			fmt.Fprintf(os.Stderr, "panic recovered: %v\n", r)
			os.Exit(1)
		}
	}()

	// 2) 组装：main 只做「解析→构造→运行」，一切业务在 internal 内。
	srv, err := NewServer(*configPath, *role)
	if err != nil {
		fmt.Fprintf(os.Stderr, "new server: %v\n", err)
		os.Exit(1)
	}

	// 3) 信号与优雅停机：
	//    - ctx 传给 Run，SIGTERM 后停止「领取新工作」；
	//    - Stop 内部用 detached context（不随 SIGTERM 取消）写终态——这就是 #12 的修复：
	//      停机瞬间「任务 finish 写库」若跟随已取消的 ctx 会直接失败，任务被回收后重复执行。
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	runErr := make(chan error, 1)
	go func() { runErr <- srv.Run(ctx) }()

	select {
	case err := <-runErr:
		if err != nil {
			srv.Logger.Error("server exited with error", zap.Error(err))
			os.Exit(1)
		}
	case <-ctx.Done():
		srv.Logger.Info("signal received, shutting down")
	}

	// 4) 停机编排：独立 5s 预算写终态（见 server.Stop）。
	stopCtx, stopCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer stopCancel()
	if err := srv.Stop(stopCtx); err != nil {
		srv.Logger.Error("stop with error", zap.Error(err))
		os.Exit(1)
	}
	srv.Logger.Info("bye")
}
