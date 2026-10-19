# 【复现】common/tvpc_token_bucket_redis.py —— 对应真实 sdn_oss/common/tvpc_token_bucket_redis.py
# 真实职责：分布式令牌桶限流（Redis 实现）——多进程/多机共享同一配额。
# 证据等级：文件存在性/分布式令牌桶定位 = 实证；Lua 原子实现 = 标准做法。
# 开源对照：Redis 官方 INCR+EXPIRE 限流；redisson RRateLimiter（Lua 原子版）。
import time

# 桶规格（真实口径推导）：按 接口×调用方 维度；容量=突发上限，速率=稳态 QPS。
BUCKET_CAPACITY = 100
REFILL_PER_SECOND = 20

_LUA = """
-- KEYS[1] = bucket key
-- ARGV: capacity, refill_rate, now_ms
local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or tonumber(ARGV[1])
local ts = tonumber(b[2]) or tonumber(ARGV[3])
local delta = math.max(0, (tonumber(ARGV[3]) - ts) / 1000)
tokens = math.min(tonumber(ARGV[1]), tokens + delta * tonumber(ARGV[2]))
if tokens < 1 then
  redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', ARGV[3])
  return 0
end
redis.call('HMSET', KEYS[1], 'tokens', tokens - 1, 'ts', ARGV[3])
redis.call('EXPIRE', KEYS[1], 3600)
return 1
"""


def acquire(key):
    """原子取一个令牌。返回 False = 被限流。"""
    return _eval_lua(_LUA, keys=[key], args=[BUCKET_CAPACITY, REFILL_PER_SECOND, int(time.time() * 1000)]) == 1


def _eval_lua(lua, keys, args):
    # 占位：真实为 redis-py 的 register_script(...)(keys=keys, args=args)。
    return 1
