// 【复现】go_proxy/main.go —— 对应真实 nfv-proxy/go_proxy/main.go
// 真实职责（23 §3.7）：Go 程序入口——构造 service.Replicater，向 **go-base app 框架**
//   注册 Init/Start 回调后启动。
// 真实依赖（本切片用标准库等价桩保持可编译）：
//   go-mysql-org/go-mysql(canal)、samuel/go-zookeeper、polaris-go、go-base。
// 证据等级：入口结构（Replicater + 注册回调）= 实证。
package main

import (
	"log"

	"proxy/service"
)

func main() {
	repl := service.NewReplicater()
	app := &goBaseApp{onInit: repl.Init, onStart: repl.Start, onStop: repl.Stop}
	app.Run()
}

// goBaseApp 模拟 go-base app 框架（真实：app 生命周期由 go-base 托管，含信号处理/优雅退出）。
type goBaseApp struct {
	onInit  func() error
	onStart func() error
	onStop  func() error
}

func (a *goBaseApp) Run() {
	if err := a.onInit(); err != nil {
		log.Fatalf("[app] init failed: %v", err)
	}
	if err := a.onStart(); err != nil {
		log.Fatalf("[app] start failed: %v", err)
	}
	log.Println("[app] go_proxy running")
	select {} // 常驻（真实：go-base 的信号循环）
}
