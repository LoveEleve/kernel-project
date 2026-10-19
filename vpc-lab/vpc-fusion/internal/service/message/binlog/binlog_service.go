// 【复现】internal/service/message/binlog/binlog_service.go —— 对应真实清单同名文件
// （"Binlog 变更消息处理：驱动快照增量更新"；消费者在 internal/mq/consume/consumer.go）。
// 证据等级：幂等键（version+scopeId）+ ON DUPLICATE KEY UPDATE + 攒批 1000 触发快照 = 实证
//           （材料：message 模块描述、fusion-position §2 分工表——R1 遗留已解除：
//           原文在 test-good/02-vpc5-gen3/vpc-fusion-position.md）；版本连续性判定 = 推导（对齐条件同族）。
// 开源对照（R3 校订）：消费端幂等 = **应用层 upsert**（唯一键 + 版本守卫），是 Outbox/变更流消费的标准要求——
//   不是某库的现成特性；Kafka 侧只给投递语义（franz-go examples/group_committing 手动提交），去重永远在消费端。
package binlog

import (
	"context"
	"encoding/json"

	"go.uber.org/zap"
)

// 攒批触发阈值（材料口径）：binlog_count 每累计 1000 触发一次快照构建。
const snapshotBatch = 1000

type Service struct {
	log      *zap.Logger
	kv       KVSink // LevelDB 合并写
	sql      SQLSink
	snapshot SnapshotTrigger
	skipped  SkipCache // 运维"跳过消息"本地缓存（localcache/skip_message_cache.go）

	batched int64 // 本批已物化条数（按 config type 维度真实实现为多个计数器）
	lastVer map[string]int64 // scope+config → 已物化最大版本（连续性判定基准）
}

type KVSink interface {
	Put(key string, val []byte) error
	Delete(key string) error
}
type SQLSink interface {
	// UpsertIfNewer：INSERT ... ON DUPLICATE KEY UPDATE ... WHERE 新版本 > 存储版本的守卫式写入
	UpsertIfNewer(ctx context.Context, table, id string, version int64, payload []byte) error
}
type SnapshotTrigger interface{ Trigger(config, scope string) }
type SkipCache interface{ Contains(msgID string) bool }

type KafkaMessage struct {
	QueueClass string        `json:"queue_class"`
	Binlog     *BinlogBody   `json:"binlog_message"`
	Command    json.RawMessage `json:"command_message"`
	Notify     json.RawMessage `json:"notification_message"`
}

type BinlogBody struct {
	Header struct {
		RequestID string `json:"request_id"` // 贯穿追踪
		TS        int64  `json:"ts"`
	} `json:"common_header"`
	Items []BinlogItem `json:"binlog_items"`
}

type BinlogItem struct {
	ConfigName string          `json:"config_name"`
	ScopeID    string          `json:"scope_id"`
	Action     string          `json:"action"` // I / U / D
	Version    int64           `json:"version"`
	OldData    json.RawMessage `json:"old_data"`
	NewData    json.RawMessage `json:"new_data"`
}

// Handle 是消费回调（consumer 保证：先处理、后提交 offset）。
func (s *Service) Handle(ctx context.Context, raw []byte) error {
	var msg KafkaMessage
	if err := json.Unmarshal(raw, &msg); err != nil {
		return err
	}

	// 运维通道：被显式跳过的消息直接落缓存（用于卡死消息的紧急跳过，事后对账兜底）。
	if msg.Binlog == nil {
		return s.handleNonBinlog(ctx, &msg)
	}
	if s.skipped != nil && s.skipped.Contains(msg.Binlog.Header.RequestID) {
		s.log.Warn("message skipped by ops", zap.String("req", msg.Binlog.Header.RequestID))
		return nil
	}

	for _, item := range msg.Binlog.Items {
		if err := s.apply(ctx, &item); err != nil {
			// 单条致命失败：不吞——返回错误让 consumer 不提交 offset（下轮重放，幂等吸收）。
			return err
		}
	}
	return nil
}

// apply 单条变更的物化：版本守卫 + 双写（MySQL 权威 + LevelDB 工作区）+ 连续性判定。
func (s *Service) apply(ctx context.Context, it *BinlogItem) error {
	// 幂等防线 1：版本守卫（乱序迟到不覆盖新值）——同一 scope+config 的版本必须单调。
	key := it.ScopeID + "|" + it.ConfigName
	if last, ok := s.lastVer[key]; ok && it.Version <= last {
		s.log.Debug("stale binlog dropped", zap.String("key", key), zap.Int64("v", it.Version))
		return nil // 迟到/重复：丢弃即可（快照路径会兜底纠正）
	}
	// 幂等防线 2：DB 侧守卫式 upsert（唯一键 = scope+identifier；条件 = 新版本更大）。
	if err := s.sql.UpsertIfNewer(ctx, tableOf(it.ConfigName), identifierOf(it), it.Version, it.NewData); err != nil {
		return err
	}
	// LevelDB 合并写：I/U → Put；D → Delete（**注意：不是 Put(nil)**——见 snapshot/worker_service.go 事故注释）。
	switch it.Action {
	case "I", "U":
		if err := s.kv.Put(kvKey(it), it.NewData); err != nil {
			return err
		}
	case "D":
		if err := s.kv.Delete(kvKey(it)); err != nil {
			return err
		}
	}

	// 连续性判定：expected = last+1；出现空洞 = 中间有消息未物化（乱序/丢失）→ 请求快照重拉对齐。
	// 快照 = 全量基线，binlog = 增量——"快照+增量"必须能拼成连续版本视图（broker 三向合并的前提）。
	if last, ok := s.lastVer[key]; ok && it.Version > last+1 {
		s.log.Warn("version gap detected, request resync",
			zap.String("key", key), zap.Int64("last", last), zap.Int64("got", it.Version))
		s.snapshot.Trigger(it.ConfigName, it.ScopeID)
	}
	s.lastVer[key] = it.Version

	// 攒批：1000 条 → 触发快照（攒批 = 用延迟换构建成本；对"创建即用"的承诺无影响——秒级窗口内）。
	s.batched++
	if s.batched >= snapshotBatch {
		s.batched = 0
		s.snapshot.Trigger(it.ConfigName, it.ScopeID)
	}
	return nil
}

func (s *Service) handleNonBinlog(ctx context.Context, msg *KafkaMessage) error { _ = ctx; _ = msg; return nil }

// 占位工具
func tableOf(config string) string      { return "config_" + config }
func identifierOf(it *BinlogItem) string { return "" }
func kvKey(it *BinlogItem) string       { return "config:" + it.ScopeID + ":" + it.ConfigName }
