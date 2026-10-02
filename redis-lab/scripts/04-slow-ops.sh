#!/usr/bin/env bash
# 实验 04：O(N) 操作的阻塞代价 与 分批替代方案（HGETALL vs HSCAN；KEYS vs SCAN）
# 对应：域4-02 慢操作；承接 01 的"单线程阻塞"结论
# 规模纪律：≤30 万级、实验后自动 FLUSHALL（避免内存压力）
set -u
CT=${1:-${CT:-redis-lab-7}}
NH=${2:-${NH:-200000}}   # Hash 字段数
NK=${3:-${NK:-200000}}   # 键空间大小
R() { docker exec "$CT" redis-cli "$@"; }

ping_min() { # 连续 3 次 PING 取最小值（降低 docker exec/调度噪声）
  python3 - "$CT" <<'PY'
import subprocess, sys, time
best = 10**9
for _ in range(3):
    t = time.time()
    subprocess.run(["docker", "exec", sys.argv[1], "redis-cli", "ping"], capture_output=True)
    best = min(best, int((time.time() - t) * 1000))
print(best)
PY
}

echo "═══ 环境：容器=$CT，Hash 字段=$NH，键空间=$NK ═══"
R flushall >/dev/null
echo "构造大 Hash（$NH 字段）..."
seq 1 "$NH" | awk '{print "HSET big f"NR" v"NR}' | docker exec -i "$CT" redis-cli --pipe >/dev/null
echo "HLEN=$(R hlen big | tr -d '\r')，MEMORY USAGE=$(R memory usage big | tr -d '\r') B"
base=$(ping_min); echo "基线 PING（3 次取最小）：${base}ms"

echo; echo "═══ 对照 1：HGETALL（O(N)，单命令内完成全部工作）═══"
t0=$(date +%s%3N)
docker exec "$CT" redis-cli hgetall big > /dev/null 2>&1 &
BG=$!; sleep 0.05
p1=$(ping_min); wait $BG
t1=$(date +%s%3N)
echo "HGETALL 期间 PING（最小）：${p1}ms；HGETALL 总耗时（含客户端进程与传输）：$((t1-t0))ms"

echo; echo "═══ 对照 2：HSCAN 分批（COUNT 1000，逐轮返回游标）═══"
docker exec "$CT" sh -c 'cur=0; c=0; while :; do cur=$(redis-cli hscan big $cur count 1000 | head -1 | tr -d "\r"); c=$((c+1)); [ "$cur" = "0" ] && break; done; echo "HSCAN 轮数=$c"' > /tmp/rl-hscan.out 2>&1 &
BG=$!; sleep 0.2
p2=$(ping_min); wait $BG
echo "HSCAN 循环期间 PING（最小）：${p2}ms（$(cat /tmp/rl-hscan.out)）"

echo; echo "═══ 释放 Hash，重建键空间 $NK 个 String ═══"
R del big >/dev/null
seq 1 "$NK" | awk '{print "SET ks:"NR" v"NR}' | docker exec -i "$CT" redis-cli --pipe >/dev/null
echo "DBSIZE=$(R dbsize | tr -d '\r')"

echo; echo "═══ 对照 3：KEYS *（O(N)，单命令扫描全键空间）═══"
t0=$(date +%s%3N)
docker exec "$CT" redis-cli keys '*' > /dev/null 2>&1 &
BG=$!; sleep 0.05
p3=$(ping_min); wait $BG
t1=$(date +%s%3N)
echo "KEYS 期间 PING（最小）：${p3}ms；KEYS 总耗时（含客户端进程与传输）：$((t1-t0))ms"

echo; echo "═══ 对照 4：SCAN 分批（COUNT 1000，逐轮返回游标）═══"
docker exec "$CT" sh -c 'cur=0; c=0; while :; do cur=$(redis-cli scan $cur count 1000 | head -1 | tr -d "\r"); c=$((c+1)); [ "$cur" = "0" ] && break; done; echo "SCAN 轮数=$c"' > /tmp/rl-scan.out 2>&1 &
BG=$!; sleep 0.2
p4=$(ping_min); wait $BG
echo "SCAN 循环期间 PING（最小）：${p4}ms（$(cat /tmp/rl-scan.out)）"

echo; echo "═══ 数据汇总（基线 ${base}ms）═══"
echo "HGETALL 期间 PING=${p1}ms | HSCAN 期间 PING=${p2}ms"
echo "KEYS    期间 PING=${p3}ms | SCAN  期间 PING=${p4}ms"
echo "结论：单命令 O(N)（HGETALL/KEYS）在命令执行期间抬高其他客户端延迟；"
echo "      分批（HSCAN/SCAN）每轮工作量有界，事件循环可在轮次间处理其他客户端。"

R flushall >/dev/null
echo "已清空实验数据（used_memory=$(R info memory | grep -a '^used_memory_human' | cut -d: -f2 | tr -d '\r')）"
