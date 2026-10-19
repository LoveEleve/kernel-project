# 【复现】common/tvpc_redis.py —— 对应真实 sdn_oss/common/tvpc_redis.py（Redis 访问基础封装）
# 用途（材料实证）：回滚上下文缓存（24h）、回滚记录（tvpc_revert_redis.py）等。
# 证据等级：用途 = 实证（VPC控制面 §2.6）；实现 = 教学等价。
_CACHE = {}


def set(key, value, ex=0):
    _CACHE[key] = value  # 占位：真实为 redis-py SET ... EX


def get(key):
    return _CACHE.get(key)


def delete(key):
    _CACHE.pop(key, None)
