// 【复现】vpc-fusion 教学切片 —— 模块命名与真实仓库对齐（git.woa.com/vpc/vpc5.0/vpc-fusion）。
// 依赖为教学占位：不追求可编译（内网库不可得），追求"机制与问题在代码里可读、可审"。
module git.woa.com/vpc/vpc5.0/vpc-fusion

go 1.25.4

require (
	github.com/redis/go-redis/v9 v9.7.0
	github.com/syndtr/goleveldb v1.0.0
	github.com/twmb/franz-go v1.18.0
	github.com/twmb/franz-go/pkg/kadm v1.14.0
	go.uber.org/zap v1.27.0
	google.golang.org/protobuf v1.36.0
	git.woa.com/vpc/vpc5.0/vpc-base v0.0.0
	git.woa.com/vpc/vpc5.0/vpc-protos v0.0.0
)
