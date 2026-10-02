#!/usr/bin/env bash
# 实验 06：持久化与复制——RDB（BGSAVE/fork 观测）、AOF（多部文件/重写）、主从复制（PSYNC）
# 对应：05 持久化与复制；容器：redis-lab-7（主）、redis-lab-8（从）
set -u
CT=${1:-${CT:-redis-lab-7}}
C8=${C8:-redis-lab-8}
R() { docker exec "$CT" redis-cli "$@"; }
R8() { docker exec "$C8" redis-cli "$@"; }

echo "═══ 一、RDB：BGSAVE 与 fork 观测 ═══"
R flushall >/dev/null
docker exec "$CT" sh -c 'for i in $(seq 1 10000); do echo "set k$i v$i"; done | redis-cli >/dev/null'
echo "--- 1 万键后 INFO persistence ---"
R info persistence | tr -d '\r' | grep -E "rdb_changes_since_last_save|rdb_last_bgsave_status|rdb_last_save_time|rdb_last_cow_size"; R info stats | tr -d '\r' | grep latest_fork_usec | sed 's/^/    /'
echo "--- BGSAVE ---"
R bgsave | tr -d '\r' | sed 's/^/    /'
sleep 1
R info persistence | tr -d '\r' | grep -E "rdb_changes_since_last_save|rdb_last_bgsave_status|rdb_last_cow_size"; R info stats | tr -d '\r' | grep latest_fork_usec | sed 's/^/    /'
echo "RDB 文件: $(docker exec "$CT" ls -la /dump.rdb 2>/dev/null | awk '{print $5" bytes ("$9")"}')"

echo; echo "═══ 二、AOF：开启、多部文件与重写 ═══"
docker exec "$CT" sh -c 'rm -rf /appendonlydir' 2>/dev/null
R config set appendonly yes >/dev/null
sleep 1
echo "appendonlydir 内容:"
docker exec "$CT" sh -c 'ls /appendonlydir/' | sed 's/^/    /'
echo "manifest 内容:"
docker exec "$CT" sh -c 'head -8 /appendonlydir/appendonly.aof.manifest' | tr -d '\r' | sed 's/^/    /'
docker exec "$CT" sh -c 'for i in $(seq 1 5000); do echo "incr c$i"; done | redis-cli >/dev/null'
R bgrewriteaof | tr -d '\r' | sed 's/^/    /'
sleep 2
echo "重写后 INFO persistence:"
R info persistence | tr -d '\r' | grep -E "aof_enabled|aof_last_bgrewrite_status|aof_current_size|aof_base_size" | sed 's/^/    /'
echo "重写后 appendonlydir:"
docker exec "$CT" sh -c 'ls /appendonlydir/' | sed 's/^/    /'
R config set appendonly no >/dev/null

echo; echo "═══ 三、主从复制：PSYNC 全量 + 增量 ═══"
docker exec "$CT" redis-cli config set protected-mode no >/dev/null
R8 config set protected-mode no >/dev/null
R8 replicaof "$CT" 6379 >/dev/null
for i in $(seq 1 20); do
  st=$(R8 info replication | tr -d '\r' | grep "master_link_status" | cut -d: -f2)
  [ "$st" = "up" ] && break
  sleep 0.5
done
echo "从（$C8）视角:"
R8 info replication | tr -d '\r' | grep -E "role|master_host|master_link_status|master_sync_in_progress|slave_repl_offset|slave_read_only" | sed 's/^/    /'
echo "主（$CT）视角:"
R info replication | tr -d '\r' | grep -E "role|connected_slaves|slave0|master_repl_offset" | sed 's/^/    /'
echo "从节点数据量（全量同步结果）: DBSIZE=$(R8 dbsize | tr -d '\r')"
R set repl:k hello-from-master >/dev/null
echo "写主 → 读从: $(R8 get repl:k | tr -d '\r')"
echo "WAIT 1 100 → $(R wait 1 100 | tr -d '\r') 个从确认"
R8 replicaof no one >/dev/null
docker exec "$CT" redis-cli config set protected-mode yes >/dev/null
R8 config set protected-mode yes >/dev/null
R flushall >/dev/null; R8 flushall >/dev/null
echo; echo "（已恢复现场：从节点解除复制、protected-mode 恢复、数据清空）"
