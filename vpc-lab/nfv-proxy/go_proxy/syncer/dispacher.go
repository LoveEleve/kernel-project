// 【复现】go_proxy/syncer/dispacher.go —— 对应真实 nfv-proxy/go_proxy/syncer/dispacher.go
// 真实职责（23 §3.7）：Dispatcher——按 HashKey(fnv) 把 binlog 事件散列进多个任务队列，
//   管理消费者与 ZkSyncer（CommitPath 位点提交路径）。
// 关键不变量：
//   ① 同 key 必进同队列 → 单队列内者 FIFO → **同一行/同一 VPC 的变更保序**（消费端无需去重排序）；
//   ② 位点不能乱推进：PosGTIDStatus 记录每个 GTID 的 PENDING/FINISHED——
//      只有"该位点之前的全部事件都完成"，才允许把 CommitPath 往前挪（否则崩溃恢复会丢事件）。
// 真实本文件+task_queue.go 还含 BinlogEvent/HashQueue/PosGTIDStatus 定义与 chan 缓冲常量。
// 证据等级：hash 分桶/flush 位点语义 = 实证；实现为等价桩。
package syncer

import (
	"hash/fnv"
	"sync"
)

// BinlogEvent：canal RowEvent 归一化后的事件（真实 task_queue.go）。
type BinlogEvent struct {
	Table   string
	Action  string // 材料：含 replace 动作类型（INSERT/UPDATE/DELETE 之外）
	Key     string // HashKey：表+主键（fnv 分桶键）
	VpcID   uint64
	Payload []byte // 组装好的 ZK 路径值
	GTID    string
}

// HashQueue：单队列（有界 chan；满 = 背压——丢给 canal 连接层反压，而不是丢事件）。
type HashQueue struct {
	ch chan *BinlogEvent
}

func NewHashQueue(capacity int) *HashQueue {
	return &HashQueue{ch: make(chan *BinlogEvent, capacity)}
}

func (q *HashQueue) Push(ev *BinlogEvent) { q.ch <- ev }

func (q *HashQueue) Pop() *BinlogEvent { return <-q.ch }

// Dispatcher：hash 分桶（TaskQueueNum 个队列）。
type Dispatcher struct {
	queues []*HashQueue
}

func NewDispatcher(taskQueueNum int) *Dispatcher {
	if taskQueueNum <= 0 {
		taskQueueNum = 1
	}
	qs := make([]*HashQueue, taskQueueNum)
	for i := range qs {
		qs[i] = NewHashQueue(4096) // 缓冲大小为示意（真实：common/const.go 定义 pos/consumer chan 缓冲常量）
	}
	return &Dispatcher{queues: qs}
}

// Dispatch：同 key 同队列（保序），异 key 并行（吞吐）。
func (d *Dispatcher) Dispatch(ev *BinlogEvent) {
	h := fnv.New32a()
	_, _ = h.Write([]byte(ev.Key))
	idx := int(h.Sum32()) % len(d.queues)
	d.queues[idx].Push(ev)
}

func (d *Dispatcher) Queues() []*HashQueue { return d.queues }

// ---------------- 位点状态（GTIDSet PENDING/FINISHED） ----------------

const (
	GTIDPending  = "PENDING"
	GTIDFinished = "FINISHED"
)

// PosGTIDStatus：每个 GTID 的完成状态；CommitPath 只在"此前全部 FINISHED"时才推进。
type PosGTIDStatus struct {
	mu    sync.Mutex
	state map[string]string
	order []string // 到达顺序（真实按 GTID 序号推进，这里保序示意）
}

func NewPosGTIDStatus() *PosGTIDStatus {
	return &PosGTIDStatus{state: make(map[string]string)}
}

func (p *PosGTIDStatus) MarkPending(gtid string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if _, ok := p.state[gtid]; !ok {
		p.order = append(p.order, gtid)
	}
	p.state[gtid] = GTIDPending
}

func (p *PosGTIDStatus) MarkFinished(gtid string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.state[gtid] = GTIDFinished
}

// CommitPoint：返回可以安全提交的位点（连续 FINISHED 前缀的最后一个 GTID）；无则 ""。
func (p *PosGTIDStatus) CommitPoint() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	last := ""
	for _, g := range p.order {
		if p.state[g] != GTIDFinished {
			break
		}
		last = g
	}
	return last
}
