// 【复现】go_proxy/syncer/event_2_zk.go —— 对应真实 nfv-proxy/go_proxy/syncer/event_2_zk.go
// 真实职责（23 §3.7）：ZkSyncer——将 binlog 事件组装成 ZK 路径值写入分区 ZK，
//   提交 "GTIDSet|date" 位点；RecentChangeNotifyMap 实现"有变更 VPC"通知子树。
// 三条数据流汇于一处：
//   ① 事件值 → 分区 ZK 路径（供 vpc_agent watch 增量下发的旁路来源）；
//   ② 位点 → CommitPath（"GTIDSet|date"——崩溃重启后从这里续跑）；
//   ③ 变更名单 → 通知子树（哪个 VPC 变了；让下游知道"该看哪儿"，避免全量扫描）。
// 证据等级：位点格式/通知子树 = 实证；实现为等价桩。
package syncer

import (
	"log"
	"sort"
	"strings"
	"sync"
)

// ZooKeeper：最小 ZK 接口（真实：samuel/go-zookeeper）。
type ZooKeeper interface {
	Set(path string, value []byte) error
	Create(path string, value []byte) error
}

type ZkSyncer struct {
	zk                 ZooKeeper
	commitPath         string // 位点提交路径（真实：public.toml 次生配置）
	recentChangeVpcKey string // "有变更 VPC" 通知子树根
	pending            *PosGTIDStatus
	date               string // 位点日期部分（GTIDSet|date）
	mu                 sync.Mutex
	changedVpcs        map[uint64]struct{}
}

func NewZkSyncer(recentChangeVpcKey string) *ZkSyncer {
	return &ZkSyncer{
		commitPath:         "/CDB_BINLOG/COMMIT/pos", // 位点路径（示意；真实次生配置）
		recentChangeVpcKey: recentChangeVpcKey,
		pending:            NewPosGTIDStatus(),
		date:               "2026-01-01",
		changedVpcs:        make(map[uint64]struct{}),
	}
}

// Run：单队列消费循环（Dispatcher 每个队列一个）。
func (z *ZkSyncer) Run(q *HashQueue) {
	for ev := range q.ch {
		z.pending.MarkPending(ev.GTID)
		z.writeEvent(ev) // ① 事件 → ZK
		z.pending.MarkFinished(ev.GTID)
		z.commitPos() // ② 位点：只在连续完成处推进
	}
}

func (z *ZkSyncer) writeEvent(ev *BinlogEvent) {
	path := zkPathFor(ev)
	if z.zk != nil {
		_ = z.zk.Set(path, ev.Payload)
	}
	// ③ 通知子树：合并去重后写"有变更 VPC"名单。
	z.mu.Lock()
	z.changedVpcs[ev.VpcID] = struct{}{}
	z.mu.Unlock()
}

func zkPathFor(ev *BinlogEvent) string {
	// 真实路径按表映射（与 proxy_binlog 写 /BINLOG 的旁路不同：go_proxy 负责 CDB 侧表）。
	return "/CDB_BINLOG/DATA/" + ev.Table + "/" + ev.Key
}

// commitPos：把 "GTIDSet|date" 写 CommitPath——**只有位点连续时才推进**。
func (z *ZkSyncer) commitPos() {
	cp := z.pending.CommitPoint()
	if cp == "" {
		return
	}
	if z.zk != nil {
		_ = z.zk.Set(z.commitPath, []byte(cp+"|"+z.date))
	}
}

// FlushRecentChange：把"有变更 VPC"名单落通知子树（真实：RecentChangeNotifyMap）。
func (z *ZkSyncer) FlushRecentChange() {
	z.mu.Lock()
	vpcs := make([]uint64, 0, len(z.changedVpcs))
	for v := range z.changedVpcs {
		vpcs = append(vpcs, v)
	}
	z.changedVpcs = make(map[uint64]struct{})
	z.mu.Unlock()
	if len(vpcs) == 0 {
		return
	}
	sort.Slice(vpcs, func(i, j int) bool { return vpcs[i] < vpcs[j] })
	parts := make([]string, 0, len(vpcs))
	for _, v := range vpcs {
		parts = append(parts, uitoa(v))
	}
	if z.zk != nil {
		_ = z.zk.Set(z.recentChangeVpcKey, []byte(strings.Join(parts, ",")))
	}
	log.Printf("[zksyncer] recent-change notify: %d vpcs", len(vpcs))
}

func uitoa(v uint64) string {
	if v == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	return string(b[i:])
}
