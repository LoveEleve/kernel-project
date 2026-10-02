#!/usr/bin/env bash
# 实验 01-B / 06：大 key「DEL 同步阻塞」vs「UNLINK 异步释放」
# 证据方式：DEL/UNLINK 执行期间，另一个客户端 PING 的延迟（对比基线）
# 用法: bash scripts/02-bigkey-del-vs-unlink.sh [容器名] [字段数]
set -u
CT=${1:-${CT:-redis-lab-7}}
N=${2:-${N:-500000}}

ping_ms() { python3 - "$CT" <<'PY'
import subprocess, sys, time
t = time.time()
subprocess.run(["docker", "exec", sys.argv[1], "redis-cli", "ping"], capture_output=True)
print(int((time.time() - t) * 1000))
PY
}

build_big() { # 宿主机生成 HSET 命令流 -> 容器 redis-cli --pipe
  seq 1 "$N" | awk -v k="$1" '{print "HSET " k " f" NR " v" NR}' | docker exec -i "$CT" redis-cli --pipe > /dev/null
}

echo "== 环境: 容器=$CT, 字段数=$N =="
echo "== [1] 构造大 hash big:del =="
build_big big:del
echo "   HLEN=$(docker exec "$CT" redis-cli hlen big:del | tr -d '\r'), memory=$(docker exec "$CT" redis-cli memory usage big:del | tr -d '\r') bytes"
base=$(ping_ms); echo "   基线 PING: ${base}ms"

echo "== [2] DEL（同步释放）执行期间测 PING =="
docker exec "$CT" redis-cli del big:del > /tmp/rl-del.out 2>&1 &
BG=$!; sleep 0.05
blocked_del=$(ping_ms); wait $BG
echo "   DEL 期间 PING: ${blocked_del}ms（DEL 返回: $(cat /tmp/rl-del.out | tr -d '\r')，EXISTS: $(docker exec "$CT" redis-cli exists big:del | tr -d '\r')）"

echo "== [3] 构造大 hash big:unlink，UNLINK（异步释放）执行期间测 PING =="
build_big big:unlink
docker exec "$CT" redis-cli unlink big:unlink > /tmp/rl-unlink.out 2>&1 &
BG2=$!; sleep 0.05
blocked_unlink=$(ping_ms); wait $BG2
echo "   UNLINK 期间 PING: ${blocked_unlink}ms（UNLINK 返回: $(cat /tmp/rl-unlink.out | tr -d '\r')，EXISTS: $(docker exec "$CT" redis-cli exists big:unlink | tr -d '\r')）"
echo "   lazyfree: $(docker exec "$CT" redis-cli info memory | grep -i lazyfree_pending_objects | tr -d '\r')"

echo
echo "结论：DEL 期间 PING 被拖慢（同步释放占主线程）；UNLINK 期间 PING ≈ 基线（键立即消失、值后台回收）。"
echo "对照：xhs 删除大结构应优先 UNLINK / 分批（HSCAN+HDEL），避免阻塞全站。"
