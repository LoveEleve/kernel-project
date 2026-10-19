#!/usr/bin/env bash
# 实验 08：内存管理——淘汰（allkeys-lru / noeviction / LFU 热键保护）、
#           过期删除（惰性 + 定期抽样）、内存碎片（ratio / MEMORY DOCTOR / activedefrag 默认）
# 对应：07 内存管理；容器：redis-lab-7（7.2.11）
set -u
CT=${1:-${CT:-redis-lab-7}}
R() { docker exec "$CT" redis-cli "$@"; }
restore() {
  R config set maxmemory 128mb >/dev/null 2>&1 || true
  R config set maxmemory-policy noeviction >/dev/null 2>&1 || true
  R flushall >/dev/null 2>&1 || true
}
trap restore EXIT

echo "═══ 一、淘汰策略：maxmemory + allkeys-lru ═══"
restore
echo "原始配置: maxmemory=$(R config get maxmemory | tail -1 | tr -d '\r')  policy=$(R config get maxmemory-policy | tail -1 | tr -d '\r')"
R config set maxmemory 4mb >/dev/null
R config set maxmemory-policy allkeys-lru >/dev/null
docker exec "$CT" sh -c 'BIG=$(printf "x%.0s" $(seq 1 1024)); for i in $(seq 1 8000); do echo "set k$i $BIG"; done | redis-cli >/dev/null'
echo "写入 8000×1KB（≈8MB，上限 4MB）后:"
R info memory | tr -d '\r' | grep -E "^used_memory:|^used_memory_rss:" | sed 's/^/    /'
R info stats | tr -d '\r' | grep "^evicted_keys" | sed 's/^/    /'
echo "抽样键仍在？ k1=$(R exists k1 | tr -d '\r')  k2000=$(R exists k2000 | tr -d '\r')  k8000=$(R exists k8000 | tr -d '\r')"

echo; echo "--- noeviction：内存满时写入被拒 ---"
R config set maxmemory-policy noeviction >/dev/null
err=$(docker exec "$CT" sh -c 'BIG=$(printf "y%.0s" $(seq 1 1024)); redis-cli set more $BIG' 2>&1 | tr -d '\r')
echo "SET 返回: $err"

echo; echo "═══ 二、LFU：频率计数与热键保护 ═══"
R config set maxmemory-policy allkeys-lfu >/dev/null
R flushall >/dev/null
R set hot v >/dev/null
for i in $(seq 1 200); do R get hot >/dev/null; done
echo "访问 200 次后 OBJECT FREQ hot = $(R object freq hot | tr -d '\r')（初始 5，对数增长）"
docker exec "$CT" sh -c 'BIG=$(printf "z%.0s" $(seq 1 1024)); for i in $(seq 1 8000); do echo "set c$i $BIG"; done | redis-cli >/dev/null'
echo "再灌 8000 个冷键（4MB 上限下触发淘汰）后: evicted_keys=$(R info stats | tr -d '\r' | grep '^evicted_keys' | cut -d: -f2)  hot 还在？$(R exists hot | tr -d '\r')  FREQ hot=$(R object freq hot | tr -d '\r')"

echo; echo "═══ 三、过期删除：惰性 + 定期 ═══"
restore
R set tmp v EX 1 >/dev/null
echo "SET tmp v EX 1 → TTL=$(R ttl tmp | tr -d '\r')"
sleep 1.2
echo "1.2s 后 GET tmp（惰性删除）→ $(R get tmp | tr -d '\r' | sed 's/^$/(nil)/')   DBSIZE=$(R dbsize | tr -d '\r')"
before=$(R info stats | tr -d '\r' | grep '^expired_keys' | cut -d: -f2)
docker exec "$CT" sh -c 'for i in $(seq 1 2000); do echo "set e$i v ex 2"; done | redis-cli >/dev/null'
after_set=$(R info stats | tr -d '\r' | grep '^expired_keys' | cut -d: -f2)
sleep 3
now=$(R info stats | tr -d '\r' | grep '^expired_keys' | cut -d: -f2)
echo "2000 个 EX 2 键、3 秒后（全程未访问，靠定期抽样删除）: DBSIZE=$(R dbsize | tr -d '\r')  expired_keys 增量=$(( now - after_set ))"
echo "（对照：前一步 tmp 只被惰性删除命中；批量键没有任何访问也消失了）"

echo; echo "═══ 四、内存碎片与 activedefrag ═══"
R info memory | tr -d '\r' | grep -E "^used_memory:|^used_memory_rss:|^mem_fragmentation_ratio:|^allocator_frag_ratio:" | sed 's/^/    /'
echo "activedefrag=$(R config get activedefrag | tail -1 | tr -d '\r')  threshold-lower=$(R config get active-defrag-threshold-lower | tail -1 | tr -d '\r')  threshold-upper=$(R config get active-defrag-threshold-upper | tail -1 | tr -d '\r')  ignore-bytes=$(R config get active-defrag-ignore-bytes | tail -1 | tr -d '\r')"
echo "MEMORY DOCTOR:"
R memory doctor | tr -d '\r' | head -4 | sed 's/^/    /'
echo; echo "（已恢复 maxmemory=128mb / noeviction，数据清空）"
