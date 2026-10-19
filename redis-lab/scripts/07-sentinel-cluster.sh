#!/usr/bin/env bash
# 实验 07：哨兵与集群——Sentinel 故障转移（3 哨兵 + 主从）与 Cluster 槽路由/MOVED
# 对应：06 哨兵与集群；全部进程在 redis-lab-7 容器内以独立端口运行
set -u
CT=${1:-${CT:-redis-lab-7}}
D() { docker exec "$CT" "$@"; }
CS() { docker exec "$CT" redis-cli "$@"; }

echo "═══ 一、Sentinel：3 哨兵 + 主从的故障转移 ═══"
# 现场清理
CS -p 6380 shutdown nosave >/dev/null 2>&1 || true
CS -p 6381 shutdown nosave >/dev/null 2>&1 || true
for p in 26379 26380 26381; do CS -p $p shutdown nosave >/dev/null 2>&1 || true; done
D sh -c 'rm -f /tmp/sentinel*.conf /tmp/63*.log /tmp/m.rdb' 2>/dev/null || true

# 主从
D redis-server --port 6380 --daemonize yes --dir /tmp --dbfilename m.rdb --save '' --logfile /tmp/6380.log
D redis-server --port 6381 --daemonize yes --dir /tmp --replicaof 127.0.0.1 6380 --save '' --logfile /tmp/6381.log
sleep 1
echo "主 6380 role=$(CS -p 6380 info replication | tr -d '\r' | grep role | cut -d: -f2) / 从 6381 role=$(CS -p 6381 info replication | tr -d '\r' | grep role | cut -d: -f2)"

# 3 个哨兵（quorum=2）
for i in 1 2 3; do
  port=$((26378+i))
  D sh -c "printf 'port $port\nsentinel monitor mymaster 127.0.0.1 6380 2\nsentinel down-after-milliseconds mymaster 2000\nsentinel failover-timeout mymaster 10000\ndir /tmp\n' > /tmp/sentinel$i.conf"
  D redis-sentinel /tmp/sentinel$i.conf --daemonize yes --logfile /tmp/sentinel$i.log
done

# 等待发现彼此（num-slaves=1 且 num-other-sentinels=2）
for i in $(seq 1 30); do
  ns=$(CS -p 26379 sentinel master mymaster | tr -d '\r' | paste - - | grep -w num-slaves | awk '{print $2}')
  no=$(CS -p 26379 sentinel master mymaster | tr -d '\r' | paste - - | grep -w num-other-sentinels | awk '{print $2}')
  [ "${ns:-0}" = "1" ] && [ "${no:-0}" = "2" ] && break; sleep 0.5
done
echo "哨兵视角（26379）:"
CS -p 26379 sentinel master mymaster | tr -d '\r' | paste - - | grep -E "^(name|flags|num-slaves|num-other-sentinels|quorum|ip|port)" | sed 's/^/    /'
echo "ckquorum: $(CS -p 26379 sentinel ckquorum mymaster | tr -d '\r')"
echo "故障前 get-master-addr: $(CS -p 26379 sentinel get-master-addr-by-name mymaster | tr -d '\r' | tr '\n' ':')"

# 杀掉主节点，等待自动故障转移
echo "--- 杀掉主 6380（shutdown nosave），等待 Sentinel 选主 ---"
CS -p 6380 shutdown nosave >/dev/null 2>&1 || true
ok=""
for i in $(seq 1 40); do
  addr=$(CS -p 26379 sentinel get-master-addr-by-name mymaster | tr -d '\r' | tr '\n' ':')
  [ "$addr" = "127.0.0.1:6381:" ] && { ok=1; break; }
  sleep 0.5
done
echo "故障后 get-master-addr: $addr（等待 ${i}×0.5s）"
sleep 1
echo "新主 6381 role=$(CS -p 6381 info replication | tr -d '\r' | grep role | cut -d: -f2)"
CS -p 26379 sentinel master mymaster | tr -d '\r' | paste - - | grep -E "^(flags|ip|port)" | sed 's/^/    调整后: /'

# 清理哨兵与从
for p in 26379 26380 26381; do CS -p $p shutdown nosave >/dev/null 2>&1 || true; done
CS -p 6381 shutdown nosave >/dev/null 2>&1 || true
D sh -c 'rm -f /tmp/sentinel*.conf /tmp/sentinel*.log /tmp/63*.log /tmp/m.rdb'

echo; echo "═══ 二、Cluster：3 主 0 从的槽路由与 MOVED ═══"
for p in 7001 7002 7003; do CS -p $p shutdown nosave >/dev/null 2>&1 || true; done
D sh -c 'rm -f /tmp/nodes-*.conf /tmp/7*.log' 2>/dev/null || true
for p in 7001 7002 7003; do
  D redis-server --port $p --cluster-enabled yes --cluster-config-file /tmp/nodes-$p.conf \
    --cluster-node-timeout 2000 --dir /tmp --save '' --appendonly no --daemonize yes --logfile /tmp/$p.log
done
sleep 1
D redis-cli --cluster create 127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 --cluster-replicas 0 --cluster-yes | tail -8 | sed 's/^/    /'
sleep 1
echo "cluster info: $(CS -p 7001 cluster info | tr -d '\r' | grep cluster_state | tr '\n' ' ')"
echo "slot 分布（CLUSTER SLOTS，按 start/end/ip/port/id 对齐）:"
CS -p 7001 cluster slots | tr -d '\r' | paste - - - - - | head -3 | sed 's/^/    /'
echo "CLUSTER KEYSLOT foo = $(CS -p 7001 cluster keyslot foo | tr -d '\r')"
echo "错误节点上直接写 foo（应被重定向）:"
CS -p 7001 set foo bar | tr -d '\r' | sed 's/^/    /'
echo "带 -c 自动跟随重定向: set foo bar → $(CS -p 7001 -c set foo bar | tr -d '\r')；get foo → $(CS -p 7001 -c get foo | tr -d '\r')"

# 清理 cluster 节点
for p in 7001 7002 7003; do CS -p $p shutdown nosave >/dev/null 2>&1 || true; done
D sh -c 'rm -f /tmp/nodes-*.conf /tmp/7*.log'
echo; echo "（已清理：哨兵/集群进程全部退出，临时文件删除）"
