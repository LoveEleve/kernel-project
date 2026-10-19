// 【复现】go_proxy/service/server.go —— 对应真实 nfv-proxy/go_proxy/service/server.go
// 真实职责（23 §3.7）：Replicater 主体——加载配置并注册北极星 JoinPoint →
//   基于 ZK **抢占空闲 worker 序号并加分布式锁** → 初始化 go-mysql canal 连接 CDB RO →
//   按 startDtm/ZK CommitPath GTIDSet 决定从 DB 补数(MergeFromDb)或 binlog 增量同步
//   （**GTID 失效自动降级全量补偿**）。
// 组件协作：canal 事件 → ProxyEventHandler → Dispatcher（hash 队列）→ ZkSyncer（写 ZK + 提交位点）。
// 证据等级：worker 抢占/锁/位点决策/降级 = 实证；实现为标准库等价桩。
// 开源对照（R3 已用克隆代码核验，refs/go-mysql）：canal 提供 `StartFromGTID`（canal/canal.go:213）
//   与 `SyncedGTIDSet`（canal/canal.go:558）——位点读写齐备；但 **canal 不含自动全量补偿**：
//   GTID 被 purge 后从该位点启动会直接失败（错误上抛），MergeFromDb 是自研的上层降级。
package service

import (
	"fmt"
	"log"
	"time"

	"proxy/syncer"
)

// Config 对应 public.toml（由 tool_init_toml.py 从 Rainbow + 分区 ZK 渲染生成）。
type Config struct {
	Region              string   // 地域（参与北极星 JoinPoint 选择）
	AdminPort           int      // 管理端口
	StartDtm            string   // 起始日期（补数起点）
	IsState             bool     // 状态模式
	TaskQueueNum        int      // Dispatcher 任务队列数
	DistributeLockPath  string   // ZK 分布式锁路径
	ProxyWorkerNum      int      // 允许的 worker 序号总数（抢位空间）
	RecentChangeVpcKey  string   // "有变更 VPC" 通知子树 key
	TableBlackList      []string // 表黑名单（不同步）
	TableWhitePrefixList []string // 表白名单前缀
}

func defaultConfig() *Config {
	return &Config{
		Region: "gz", AdminPort: 0, StartDtm: "2026-01-01",
		TaskQueueNum: 8, DistributeLockPath: "/CDB_BINLOG/LOCK/replicater",
		ProxyWorkerNum: 4, RecentChangeVpcKey: "/RECENT_CHANGE/VPC",
	}
}

type Replicater struct {
	cfg    *Config
	seq    int // 抢占到的 worker 序号
	disp   *syncer.Dispatcher
	zkSync *syncer.ZkSyncer
}

func NewReplicater() *Replicater {
	return &Replicater{cfg: defaultConfig()}
}

// Init：① 配置 → ② 北极星 JoinPoint → ③ ZK 抢 worker 序号 + 分布式锁 → ④ canal 连接。
func (r *Replicater) Init() error {
	// ① 加载 public.toml（真实：go-base 配置注册）。
	cfg := r.cfg

	// ② 北极星 JoinPoint：按地域选接入点（真实 common/polaris/region_joinpoint.go：
	//    usw/use/ca/sao 等海外地域走 USAJoinPoint）。
	_ = joinPointForRegion(cfg.Region)

	// ③ ZK 抢占空闲 worker 序号：在 [0, ProxyWorkerNum) 里创建临时节点，先到先得——
	//    序号决定本进程负责哪些表/分片（多实例水平分载）；
	//    再对 DistributeLockPath 加分布式锁（防重复消费同一批事件）。
	seq, err := r.claimWorkerSeq(cfg.ProxyWorkerNum)
	if err != nil {
		return fmt.Errorf("claim worker seq: %w", err)
	}
	r.seq = seq
	if err := r.acquireDistLock(cfg.DistributeLockPath); err != nil {
		return fmt.Errorf("acquire dist lock: %w", err)
	}

	// ④ canal 连接 CDB RO（真实：go-mysql canal 连接 vpcoss 只读库）。
	if err := r.connectCanal(cfg); err != nil {
		return fmt.Errorf("connect canal: %w", err)
	}

	// ⑤ 装配管道：Dispatcher(taskQueueNum) + ZkSyncer。
	r.disp = syncer.NewDispatcher(cfg.TaskQueueNum)
	r.zkSync = syncer.NewZkSyncer(cfg.RecentChangeVpcKey)
	log.Printf("[replicater] init done: workerSeq=%d queues=%d", seq, cfg.TaskQueueNum)
	return nil
}

// Start：位点决策 + 事件泵。
func (r *Replicater) Start() error {
	// 起始位点决策（材料口径）：startDtm（配置）> ZK CommitPath 的 GTIDSet（上次进度）>
	// 当前 master GTIDSet（从头跟）。若确定的位置在 CDB 上已不可用（**GTID 失效**，binlog 被 purge）
	// → 自动降级：先 MergeFromDb 按起始日期从 OSS DB 全量补齐事件，再切回 StartFromGTID 增量。
	if r.needFullCompensation() {
		log.Println("[replicater] GTID expired → MergeFromDb full compensation")
		if err := r.zkSync.MergeFromDb(r.cfg.StartDtm, r.cfg.TableWhitePrefixList); err != nil {
			return err
		}
	}

	// 事件泵：canal RowEvent → ProxyEventHandler → Dispatcher → 各队列消费进 ZkSyncer。
	for _, q := range r.disp.Queues() {
		go r.zkSync.Run(q) // 每队列一个消费者（顺序性由 hash 分桶保证：同 key 同队列）
	}
	go r.pumpCanalEvents()
	log.Printf("[replicater] started, catching from %s", r.cfg.StartDtm)
	return nil
}

func (r *Replicater) Stop() error {
	log.Println("[replicater] stop: commit final pos & release lock")
	return nil
}

// ---------------- 等价桩（真实为 ZK/canal/北极星 SDK 调用） ----------------

func joinPointForRegion(region string) string { return "polaris." + region }

func (r *Replicater) claimWorkerSeq(total int) (int, error) {
	// 真实：ZK 临时顺序节点 in /CDB_BINLOG/WORKER/{region}/{seq}，epoch 后按序认领空闲位。
	time.Sleep(10 * time.Millisecond)
	return 0, nil
}

func (r *Replicater) acquireDistLock(path string) error { return nil }

func (r *Replicater) connectCanal(cfg *Config) error { return nil }

func (r *Replicater) needFullCompensation() bool { return false }

func (r *Replicater) pumpCanalEvents() {
	// 真实：canal 回调 → NewProxyEventHandler(disp, tableFilter).OnRowEvent(e)。
}
