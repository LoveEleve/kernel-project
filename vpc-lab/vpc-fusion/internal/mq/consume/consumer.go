// 【复现】internal/mq/consume/consumer.go —— 对应真实清单同名文件
// （"消费者：业务消费者订阅 FusionNotifyTopic+动态 Topic、广播消费者单独订阅 FusionBroadcastTopic；
//   按 partition 并发 poll 并路由到 message service 处理器"）。
// 证据等级：poll 驱动 / 按 partition 并发 / 先处理后提交 = 实证（清单 + commitRecords 口径）；
//           看门狗参数（巡检 30s / 判定阈值）= 工程推导 + Gen2 假死事故经验（kafka_consumer 故事）。
// 开源对照（R3 已用克隆代码核验，refs/ 见 OSS-REFS.md）：
//   - 本项目 = poll 模型：franz-go `PollFetches`（refs/franz-go/pkg/kgo/consumer.go:522）——
//     每轮带 ctx 超时、归还控制权，循环里可塞看门狗自检；
//   - 反例模型 = sarama 的 channel 消费（refs/sarama/consumer.go:403 `Messages() <-chan *ConsumerMessage`）：
//     `for msg := range` 空闲时阻塞在 channel 上，业务循环无法周期性让出控制权——
//     当提交/自检写在这条循环里（通用写法）就退化为"活着但不出活"（Gen2 假死事故的同构形态）；
//     另：sarama `ChannelBufferSize`（config.go:615 默认 256）——channel 满还会反压，第二层雷；
//   - 提交语义对照：franz-go examples/group_committing（手动提交）——位点提交永远是显式动作。
package consume

import (
	"context"
	"sync/atomic"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
	"go.uber.org/zap"
)

type KafkaConfig struct {
	Brokers        []string
	NotifyTopics   []string // FusionNotifyTopic + 动态扩展
	BroadcastTopic string   // FusionBroadcastTopic（每个实例都要收到 → 独立消费组）
	Group          string
	MaxPollRecords int // 拉批上限（Gen2 侧教训：max_poll_records=1 缩小重放窗口；此处保留可配）
}

type Handler func(ctx context.Context, raw []byte) error

type Consumer struct {
	cfg     KafkaConfig
	log     *zap.Logger
	handler Handler
	client  *kgo.Client

	accepting  atomic.Bool
	lastPollAt atomic.Int64 // UnixNano：看门狗观测点
	stuckAfter time.Duration
}

func NewConsumer(cfg KafkaConfig, log *zap.Logger, h Handler) *Consumer {
	return &Consumer{cfg: cfg, log: log, handler: h, stuckAfter: 90 * time.Second}
}

// Run：poll 驱动的主循环。
// 与"假死"的关系（Gen2 nfv-proxy 血案）：消费者若阻塞在 fetch 上（而非有超时的 poll），
// 空闲期无法让出控制权——offset 不提交、心跳不推进、进程看起来活着但消费已死。
// 解法：**每次 poll 都带超时**（PollTimeoutMs 5s 口径），循环体保持"短事务"。
func (c *Consumer) Run(ctx context.Context) error {
	c.accepting.Store(true)

	// 看门狗：独立协程巡检「最后一次成功 poll 的时间」。
	// 为什么需要它：poll 超时只解决"内核里等多久"；调度器饥饿/死锁/poll 卡在解析层这类问题，
	// 仍需外部观测点。阈值取 ~3 个 poll 周期。
	go c.watchdog(ctx)

	for c.accepting.Load() {
		select {
		case <-ctx.Done():
			return nil
		default:
		}

		fetches := c.client.PollFetches(ctx) // 内部带超时；返回即刷新观测点
		c.lastPollAt.Store(time.Now().UnixNano())
		if fetches.IsClientClosed() || ctx.Err() != nil {
			return nil
		}
		fetches.EachError(func(topic string, partition int32, err error) {
			c.log.Warn("fetch error", zap.String("topic", topic), zap.Int32("partition", partition), zap.Error(err))
		})

		// 按 partition 顺序处理（同一 partition 内保持 FIFO——同对象变更的顺序性由此保证）。
		var lastOK *kgo.Record
		fetches.EachRecord(func(rec *kgo.Record) {
			if err := c.handler(ctx, rec.Value); err != nil {
				// 处理失败：不提交该条（及其后），下轮重放；任务/消息幂等吸收重复。
				c.log.Error("handle failed, stop this batch", zap.Int64("offset", rec.Offset), zap.Error(err))
				return
			}
			lastOK = rec
		})

		// 只提交最后一个"成功"记录（材料口径：commitRecords(lastestSuccessRecord)）。
		if lastOK != nil {
			if err := c.client.CommitRecords(ctx, lastOK); err != nil {
				c.log.Warn("commit failed (will replay, idempotent)", zap.Error(err))
			}
		}
		_ = c.cfg.MaxPollRecords
	}
	return nil
}

// watchdog：检测"活着但假装消费"。
// 处置哲学（Gen2 结论）：**自杀让 watchdog 拉起，而不是进程内自愈**——进程状态不可信时，
// 重启是最便宜的恢复路径；且自杀**不调用 client.Close()**（避免在已损坏的网络栈上二次阻塞），
// 让 broker 通过 session 超时完成 rebalance（重复消息由消费端幂等吸收）。
func (c *Consumer) watchdog(ctx context.Context) {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			last := time.Unix(0, c.lastPollAt.Load())
			if time.Since(last) > c.stuckAfter {
				c.log.Error("kafka consumer stuck (no successful poll), suicide",
					zap.Duration("since_last_poll", time.Since(last)))
				// 真实实现：os.Exit(1)——由 watchdog/systemd 层拉起；不要在这里 Close。
				return
			}
		}
	}
}

func (c *Consumer) StopAccepting() { c.accepting.Store(false) }
