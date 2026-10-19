#!/usr/bin/env bash
# 实验 10：缓存四问的 Redis 侧机制验证
#   穿透（空值缓存）、击穿（SET NX PX 互斥锁 + Lua 释放）、
#   原子重建（领券 Lua 对照 claim_coupon.lua）、雪崩（TTL 随机偏移）
# 对应：09 缓存穿透/击穿/雪崩与一致性；容器：redis-lab-7（7.2.11）
set -u
CT=${1:-${CT:-redis-lab-7}}
R() { docker exec "$CT" redis-cli "$@"; }

R flushall >/dev/null

echo "═══ 一、穿透：空值缓存（防"查不存在"）═══"
R set product:miss "__CACHE_NULL__" EX 120 >/dev/null
echo "SET product:miss <空值占位> EX 120 → GET=$(R get product:miss | tr -d '\r')  TTL=$(R ttl product:miss | tr -d '\r')"
echo "（项目实现：CacheHelper.NULL_PLACEHOLDER 用 \\u0000 包裹、空值 TTL 2 分钟，见 CacheHelper.java:46/128）"

echo; echo "═══ 二、击穿：SET NX PX 互斥锁 + Lua 原子释放 ═══"
echo "客户端A: SET lock:hot tokenA NX PX 3000 → $(R set lock:hot tokenA NX PX 3000 | tr -d '\r')"
B=$(R set lock:hot tokenB NX PX 3000 | tr -d '\r'); echo "客户端B: SET lock:hot tokenB NX PX 3000 → ${B:-(nil，拿不到锁)}"
echo "误删保护（非持有者 tokenB 释放）→ $(R eval "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end" 1 lock:hot tokenB | tr -d '\r')（0=拒绝，锁还在）"
echo "持有者释放（tokenA）→ $(R eval "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end" 1 lock:hot tokenA | tr -d '\r')（1=已释放）"
echo "（项目实现：CacheHelper.getWithCacheAsideLock 用 Redisson tryLock(3s,10s)+双重检查，见 CacheHelper.java:158-220）"

echo; echo "═══ 三、原子重建：领券 Lua（对照 claim_coupon.lua）═══"
COUPON_LUA="local s=redis.call('GET',KEYS[1]); if not s then return -3 end; if tonumber(s)<=0 then return -1 end; local c=tonumber(redis.call('GET',KEYS[2]) or '0'); if c>=tonumber(ARGV[1]) then return -2 end; redis.call('DECR',KEYS[1]); redis.call('INCR',KEYS[2]); return 1"
R set cp:stock 5 >/dev/null; R set cp:claimed:u1 0 >/dev/null
echo "场景一：库存=5、每人限领 2，连续领 3 次（1=成功, -2=达限领）："
for i in 1 2 3; do printf "    第%s次 → %s\n" "$i" "$(R eval "$COUPON_LUA" 2 cp:stock cp:claimed:u1 2 | tr -d '\r')"; done
R set cp2:stock 1 >/dev/null; R set cp2:claimed:u2 0 >/dev/null
echo "场景二：库存=1、每人限领 2，连续领 2 次（1=成功, -1=领完）："
for i in 1 2; do printf "    第%s次 → %s\n" "$i" "$(R eval "$COUPON_LUA" 2 cp2:stock cp2:claimed:u2 2 | tr -d '\r')"; done
echo "（项目脚本：claim_coupon.lua 用 {templateId} hash tag 保证 Cluster 同 slot，见 claim_coupon.lua:26-48）"

echo; echo "═══ 四、雪崩：TTL 随机偏移（离散过期时间）═══"
echo "固定 TTL（8 个键都 EX 60）："
for i in $(seq 1 8); do R set aval:$i v EX 60 >/dev/null; done
echo "    TTL: $(for i in $(seq 1 8); do R ttl aval:$i | tr -d '\r'; done | tr '\n' ' ')"
echo "随机偏移（base 60 + rand 0..10）："
for i in $(seq 1 8); do t=$((60 + RANDOM % 11)); R set bval:$i v EX $t >/dev/null; done
echo "    TTL: $(for i in $(seq 1 8); do R ttl bval:$i | tr -d '\r'; done | tr '\n' ' ')"
echo "（项目实现：CacheHelper 回填时 timeoutSeconds + ThreadLocalRandom(0, timeout/6)，见 CacheHelper.java:125-126）"

R flushall >/dev/null
echo; echo "（数据已清空）"
