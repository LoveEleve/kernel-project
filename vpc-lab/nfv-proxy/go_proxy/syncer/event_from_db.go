// 【复现】go_proxy/syncer/event_from_db.go —— 对应真实 nfv-proxy/go_proxy/syncer/event_from_db.go
// 真实职责（23 §3.7）：**MergeFromDb / GenEventFromOssDb**——GTIDSet 过期等场景下，
//   按起始日期从 OSS DB 全量补齐事件后再切回 binlog。
// 场景（为什么必须有它）：
//   CDB 会清理旧 binlog——如果本进程停太久（或新扩容），ZK CommitPath 里的 GTIDSet
//   在 CDB 上已经不存在了，canal 从该位点启动会直接失败（R3 核验：canal 对 purged binlog
//   无自动降级——`StartFromGTID` 失败即上抛，refs/go-mysql/canal/canal.go:213）。
//   解法不是"从头拉全部 binlog"（可能已被清），而是**直接从 OSS DB 读当前状态生成事件**，
//   补进同一管道（同 hash 队列 → 同一顺序语义），追平后再 StartFromGTID 切回增量。
// 证据等级：机制 = 实证；实现为等价桩（真实 MergeFromDb + GenEventFromOssDb 两函数）。
package syncer

import (
	"log"
)

// MergeFromDb：按起始日期从 OSS DB 全量补齐事件（走同一 Dispatcher/ZK 写入路径）。
func (z *ZkSyncer) MergeFromDb(startDtm string, tablePrefixes []string) error {
	log.Printf("[zksyncer] MergeFromDb from %s (tables=%v)", startDtm, tablePrefixes)
	// 真实流程：
	// 1. 遍历白名单前缀的表 → 按主键分页 SELECT（OSS DB 只读连接）；
	// 2. GenEventFromOssDb：每行 → BinlogEvent（action=replace——"以当前状态为准"，
	//    与 binlog 的 insert/update/delete 三态不同：补数事件只表达"现在是什么"，不表达过程）；
	// 3. 事件进同一 hash 队列（顺序折叠语义与增量一致：旧值被新值覆盖）；
	// 4. 补数完成 → 信号给 Replicater：StartFromGTID 切回增量。
	return nil
}

// GenEventFromOssDb：单行 → 事件（等价桩）。
func GenEventFromOssDb(table, pk string, row []byte, vpcID uint64) *BinlogEvent {
	return &BinlogEvent{
		Table: table, Action: "replace", Key: table + ":" + pk,
		VpcID: vpcID, Payload: row, GTID: "db-merge-" + pk, // 补数事件的伪 GTID（不参与 CommitPath）
	}
}
