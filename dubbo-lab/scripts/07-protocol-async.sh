#!/usr/bin/env bash
# 实验 07：协议字节（wire-proxy 抓包）+ 异步 vs 同步 + timeout 真相
# 对应：Dubbo 系列第 06 篇（请求如何变成网络字节）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"

PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
[ -f /tmp/dubbo-lab-wire.pid ] && kill "$(cat /tmp/dubbo-lab-wire.pid)" 2>/dev/null
sleep 1

echo "═══ 协议字节 + 异步 + 超时真相 ═══"
setsid nohup mvn -q -pl lab-provider exec:exec > /tmp/dubbo-lab-proto-provider.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
echo "provider 就绪（${i}s，20880）"

setsid nohup python3 tools/wire-proxy.py 20990 20880 64 > /tmp/dubbo-lab-wire.log 2>&1 < /dev/null &
echo $! > /tmp/dubbo-lab-wire.pid
disown
sleep 1

echo
echo "--- ① 一次调用在网络上的前 64 字节（hex：C->P 请求 / P->C 响应）---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.direct.url=dubbo://127.0.0.1:20990 > /tmp/dubbo-lab-proto-consumer.log 2>&1
grep -E "C->P|P->C" /tmp/dubbo-lab-wire.log
kill "$(cat /tmp/dubbo-lab-wire.pid)" 2>/dev/null
rm -f /tmp/dubbo-lab-wire.pid

echo
echo "--- ② 异步 vs 同步（两次 slow(1000)）---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.async=true > /tmp/dubbo-lab-async.log 2>&1
grep "lab-consumer\]" /tmp/dubbo-lab-async.log | grep -E "async|两次"

echo
echo "--- ③ timeout 真相：consumer 1s 超时，provider 继续执行 slow(3000) ---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.timeout.ref=1000 -Dlab.slow.ms=3000 > /tmp/dubbo-lab-timeout.log 2>&1
grep "lab-consumer\] slow" /tmp/dubbo-lab-timeout.log
sleep 2
echo "provider 侧时间线（开始/结束）："
grep -E "slow 开始|slow 结束" /tmp/dubbo-lab-proto-provider.log | tail -2

echo
PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill -9 "$PID"
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':20880|:20990'; then
  echo "警告：端口未释放"
else
  echo "已清理（20880/20990 释放）"
fi
