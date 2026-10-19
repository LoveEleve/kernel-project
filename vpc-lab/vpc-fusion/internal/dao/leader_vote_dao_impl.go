// 【复现】internal/dao/leader_vote_dao_impl.go —— 对应真实 DAO 层（材料引码：leader_vote_dao_impl.go:28-44 / 70-84）。
// 证据等级：SetLeader/Renew 的**行为**=实证（材料给出真实代码片段与本文件同名函数）；键名与骨架=推导。
// 开源对照：Redis 官方分布式锁模式（SET NX EX + 校验持有者续约）；etcd lease（TTL 语义）——我们靠 Lua 补"续约校验"。
package dao

import (
	"context"
	"encoding/json"
	"time"

	"github.com/redis/go-redis/v9"
)

// LeaderInfo —— 与真实实现一致：值不是裸 NodeID，而是 JSON（含 ElectedAt/ExpireAt，便于排障）。
// 脑裂分析依赖其中 node_id 字段：续约脚本必须解码并校验，防止"给别人的锁续约"。
type LeaderInfo struct {
	NodeID    string    `json:"node_id"`
	ElectedAt time.Time `json:"elected_at"`
	ExpireAt  time.Time `json:"expire_at"`
}

type LeaderVoteDao struct {
	client *redis.Client
	prefix string // 口径：leader:election:{role}（另一写法 fusion:leader: 已在登记中，以仓库为准）
}

func NewLeaderVoteDao(client *redis.Client, prefix string) *LeaderVoteDao {
	return &LeaderVoteDao{client: client, prefix: prefix}
}

func (d *LeaderVoteDao) buildKey(electionKey string) string {
	return d.prefix + ":" + electionKey
}

// SetLeader —— 竞选：Redis 单命令即可保证互斥（SET key val NX EX ttl）。
// 返回 true = 我方当选（锁不存在且写入成功）。
func (d *LeaderVoteDao) SetLeader(ctx context.Context, electionKey string, info LeaderInfo, ttl time.Duration) (bool, error) {
	key := d.buildKey(electionKey)
	data, err := json.Marshal(info)
	if err != nil {
		return false, err
	}
	// 真实实现：d.Client().SetNX(ctx, key, string(data), ttl).Result()
	return d.client.SetNX(ctx, key, string(data), ttl).Result()
}

func (d *LeaderVoteDao) GetLeader(ctx context.Context, electionKey string) (*LeaderInfo, error) {
	data, err := d.client.Get(ctx, d.buildKey(electionKey)).Bytes()
	if err == redis.Nil {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var info LeaderInfo
	if err := json.Unmarshal(data, &info); err != nil {
		return nil, err
	}
	return &info, nil
}

// renewScript —— 续约（材料引码 leader_vote_dao_impl.go:70-84）。
// 为什么必须 Lua：GET → 判断 → PEXPIRE 三步分离时，中间被别人抢锁会发生
// "给别人的锁续约" → 双主窗口被无限延长 → 脑裂。
// 脚本内 cjson.decode + node_id 比对，只有锁的持有者能续。
const renewScript = `
local current = redis.call("GET", KEYS[1])
if current then
    local ok, info = pcall(cjson.decode, current)
    if ok and info.node_id == ARGV[1] then
        info.expire_at = tonumber(ARGV[2])
        redis.call("SET", KEYS[1], cjson.encode(info))
        redis.call("PEXPIRE", KEYS[1], ARGV[3])
        return 1
    end
end
return 0`

func (d *LeaderVoteDao) Renew(ctx context.Context, electionKey, nodeID string, newExpireAt time.Time, ttl time.Duration) (bool, error) {
	res, err := d.client.Eval(ctx, renewScript,
		[]string{d.buildKey(electionKey)},
		nodeID, newExpireAt.UnixMilli(), ttl.Milliseconds()).Int()
	if err != nil {
		return false, err
	}
	return res == 1, nil
}

// releaseScript —— 主动交还（优雅停机用）：校验持有者后再 DEL，避免误删新 Leader 的锁。
const releaseScript = `
local current = redis.call("GET", KEYS[1])
if current then
    local ok, info = pcall(cjson.decode, current)
    if ok and info.node_id == ARGV[1] then
        return redis.call("DEL", KEYS[1])
    end
end
return 0`

func (d *LeaderVoteDao) Release(ctx context.Context, electionKey, nodeID string) (bool, error) {
	res, err := d.client.Eval(ctx, releaseScript, []string{d.buildKey(electionKey)}, nodeID).Int()
	if err != nil {
		return false, err
	}
	return res == 1, nil
}
