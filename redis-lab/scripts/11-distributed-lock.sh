#!/usr/bin/env bash
# 实验 11：分布式锁五层——租约过期、看门狗续期、可重入 Hash、Redlock 多数派
# 对应：10 分布式锁与生产；容器：redis-lab-7（7.2.11，另起 6380/6381 两个独立实例）
set -u
CT=${1:-${CT:-redis-lab-7}}
R() { docker exec "$CT" redis-cli "$@"; }
RP() { local port=$1; shift; docker exec "$CT" redis-cli -p "$port" "$@"; }

R flushall >/dev/null

echo "═══ 一、第一层：SET NX PX 的租约死结 ═══"
echo "A 拿锁（PX 800ms）→ $(R set lock:job tokenA NX PX 800 | tr -d '\r')"
sleep 1
echo "A 还在执行业务（1s），锁 PTTL=$(R pttl lock:job | tr -d '\r')（-2=已过期）"
echo "B 此时拿锁 → $(R set lock:job tokenB NX PX 800 | tr -d '\r')（互斥已被打破：锁在业务结束前过期）"
R del lock:job >/dev/null

echo; echo "═══ 二、看门狗：续期模拟（Redisson 每 leaseTime/3 续一次）═══"
R set lock:wd tokenW NX PX 1000 >/dev/null
( while true; do docker exec "$CT" redis-cli pexpire lock:wd 1000 >/dev/null 2>&1; sleep 0.5; done ) &
RENEW=$!
sleep 3
echo "业务执行 3s 中持续续期 → PTTL=$(R pttl lock:wd | tr -d '\r')ms（没有过期）"
kill $RENEW 2>/dev/null; wait $RENEW 2>/dev/null || true
sleep 1.3
echo "停止续期 1.3s 后 → PTTL=$(R pttl lock:wd | tr -d '\r')（-2=已过期；客户端宕机后锁最终释放）"

echo; echo "═══ 三、可重入：Redisson 的 Hash 结构（模拟）═══"
R del lock:re >/dev/null
echo "第1次获取（HINCRBY lock:re id1:t1 1 + PEXPIRE 30000）→ count=$(R hincrby lock:re "id1:t1" 1 | tr -d '\r')  PTTL=$(R pexpire lock:re 30000 >/dev/null; R pttl lock:re | tr -d '\r')"
echo "同线程第2次获取 → count=$(R hincrby lock:re "id1:t1" 1 | tr -d '\r')（>0 不阻塞自己）"
echo "释放1次 → count=$(R hincrby lock:re "id1:t1" -1 | tr -d '\r')（仍 >0，锁保留）"
echo "释放第2次 → count=$(R hincrby lock:re "id1:t1" -1 | tr -d '\r')；count=0 → DEL → 删除结果=$(R del lock:re | tr -d '\r')（1=已删）"

echo; echo "═══ 四、Redlock：3 实例多数派 ═══"
for p in 6380 6381; do RP $p shutdown nosave >/dev/null 2>&1 || true; done
docker exec "$CT" sh -c 'rm -f /tmp/6*.log' 2>/dev/null || true
for p in 6380 6381; do
  docker exec "$CT" redis-server --port $p --daemonize yes --save '' --dir /tmp --logfile /tmp/$p.log
done
sleep 1
release() { for p in 6379 6380 6381; do RP $p del lock:redlock >/dev/null 2>&1 || true; done; }
acquire() {
  local tok=$1 ok=0 r
  release
  for p in 6379 6380 6381; do
    r=$(RP $p set lock:redlock "$tok" NX PX 3000 2>/dev/null | tr -d '\r')
    [ "$r" = "OK" ] && ok=$((ok+1))
  done
  echo $ok
}
echo "正常（3 实例）: 获取成功实例数=$(acquire tok1)/3（≥2 即多数派）"
RP 6380 shutdown nosave >/dev/null 2>&1 || true
echo "挂 1 台（6380）: 获取成功实例数=$(acquire tok2)/3（仍达多数派）"
RP 6381 shutdown nosave >/dev/null 2>&1 || true
echo "挂 2 台: 获取成功实例数=$(acquire tok3)/3（<2，应释放已获取的并失败）"
release
docker exec "$CT" sh -c 'rm -f /tmp/6*.log' 2>/dev/null || true

R flushall >/dev/null
echo; echo "（已清理：临时实例关闭、数据清空）"
