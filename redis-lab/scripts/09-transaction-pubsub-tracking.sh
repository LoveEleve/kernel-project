#!/usr/bin/env bash
# 实验 09：事务（MULTI/EXEC/WATCH）、发布订阅、客户端缓存 tracking（RESP3 失效推送）、SLOWLOG
# 对应：08 事务/发布订阅/客户端缓存/使用规范；容器：redis-lab-7（7.2.11）
set -u
CT=${1:-${CT:-redis-lab-7}}
R() { docker exec "$CT" redis-cli "$@"; }
RI() { docker exec -i "$CT" redis-cli "$@"; }   # 保留同一连接（stdin 多命令）

R flushall >/dev/null

echo "═══ 一、事务：MULTI/EXEC 的三种结局 ═══"
echo "--- 正常提交 ---"
printf 'MULTI\nSET t:a 1\nINCR t:a\nEXEC\n' | RI | sed 's/^/    /'
echo "--- 入队错误（未知命令）→ EXECABORT ---"
printf 'MULTI\nNOSUCHCMD\nSET x 1\nEXEC\n' | RI 2>&1 | sed 's/^/    /'
echo "--- 执行错误（对 string 做 INCR）→ 单条失败、其余照常执行 ---"
R set str hello >/dev/null
printf 'MULTI\nINCR str\nSET t:b 1\nEXEC\nGET t:b\n' | RI 2>&1 | sed 's/^/    /'
echo "--- WATCH 乐观锁：WATCH 后被其他客户端改 → EXEC 返回 nil ---"
docker exec "$CT" sh -c '
rm -f /tmp/wf /tmp/wo; mkfifo /tmp/wf
redis-cli > /tmp/wo < /tmp/wf &
P=$!
exec 3>/tmp/wf
echo "watch wkey" >&3; sleep 0.4
redis-cli set wkey other >/dev/null
echo "multi" >&3; echo "set wkey mine" >&3; echo "exec" >&3
sleep 0.5
exec 3>&-
wait $P 2>/dev/null
cat /tmp/wo | sed "s/^$/(nil：CAS 失败，整个事务未执行)/"
rm -f /tmp/wf /tmp/wo' | sed 's/^/    /'

echo; echo "═══ 二、发布订阅：不存消息的广播 ═══"
echo "无订阅者: PUBLISH ch dead → $(R publish ch dead | tr -d '\r') 个接收者（消息直接丢弃）"
echo "--- SUBSCRIBE ch + 两条消息 ---"
docker exec "$CT" sh -c '
( sleep 0.4; redis-cli publish ch hello >/dev/null; sleep 0.2; redis-cli publish ch world >/dev/null ) &
timeout 1.2 redis-cli --csv subscribe ch 2>/dev/null' | sed 's/^/    /'
echo "--- PSUBSCRIBE news.* + 一条匹配消息 ---"
docker exec "$CT" sh -c '
( sleep 0.4; redis-cli publish news.tech T1 >/dev/null ) &
timeout 1.2 redis-cli --csv psubscribe "news.*" 2>/dev/null' | sed 's/^/    /'

echo; echo "═══ 三、客户端缓存：TRACKING 失效推送（原始 RESP 字节）═══"
echo "--- 普通模式：GET 过的键被改 → 收到 invalidate 推送 ---"
docker exec "$CT" sh -c '
redis-cli set tk v1 >/dev/null
rm -f /tmp/nf /tmp/no; mkfifo /tmp/nf
nc 127.0.0.1 6379 < /tmp/nf > /tmp/no 2>/dev/null &
P=$!
exec 4>/tmp/nf
printf "HELLO 3\r\n" >&4; sleep 0.3
printf "CLIENT TRACKING ON\r\n" >&4; sleep 0.3
printf "GET tk\r\n" >&4; sleep 0.3
redis-cli set tk v3 >/dev/null
sleep 0.5
exec 4>&-
kill $P 2>/dev/null
cat /tmp/no | tr -d "\r" | tail -10
rm -f /tmp/nf /tmp/no' | sed 's/^/    /'
echo "--- TRACKINGINFO（BCAST 模式）---"
printf 'CLIENT TRACKING ON BCAST\nCLIENT TRACKINGINFO\n' | RI -3 | sed 's/^/    /'

echo; echo "═══ 四、使用规范：SLOWLOG 抓慢命令 ═══"
R config set slowlog-log-slower-than 10000 >/dev/null
R slowlog reset >/dev/null
R debug sleep 0.02 >/dev/null
echo "DEBUG SLEEP 0.02（20ms > 10ms 阈值）→ SLOWLOG GET 1："
R slowlog get 1 | tr -d '\r' | sed 's/^/    /'
R slowlog reset >/dev/null
echo "maxclients=$(R config get maxclients | tail -1 | tr -d '\r')  client-output-buffer-limit=$(R config get client-output-buffer-limit | tail -1 | tr -d '\r')"

R flushall >/dev/null
echo; echo "（数据已清空；slowlog 阈值保持默认 10000μs）"
