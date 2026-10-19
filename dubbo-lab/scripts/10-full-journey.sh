#!/usr/bin/env bash
# 实验 10：一次调用的完整旅程（消费方 Filter → 网络字节 → 提供方 Filter → 服务 → 响应）
# 对应：Dubbo 系列第 09 篇（收束）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
PROV_LOG=/tmp/dubbo-lab-journey-provider.log
WIRE_LOG=/tmp/dubbo-lab-journey-wire.log
CONS_LOG=/tmp/dubbo-lab-journey-consumer.log

PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
[ -f /tmp/dubbo-lab-journey-wire.pid ] && kill "$(cat /tmp/dubbo-lab-journey-wire.pid)" 2>/dev/null
sleep 1

echo "═══ 一次调用的一生（端到端 trace）═══"
setsid nohup mvn -q -pl lab-provider exec:exec > "$PROV_LOG" 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
echo "provider 就绪（${i}s，20880）"

setsid nohup python3 tools/wire-proxy.py 20990 20880 64 > "$WIRE_LOG" 2>&1 < /dev/null &
echo $! > /tmp/dubbo-lab-journey-wire.pid
disown
sleep 1

timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.direct.url=dubbo://127.0.0.1:20990 -Dlab.slow.ms=0 > "$CONS_LOG" 2>&1

echo
echo "--- ① 消费方 Filter（ClientTraceFilter）---"
grep "client-trace" "$CONS_LOG" | head -2
echo
echo "--- ② 网络（wire-proxy 抓包，前 64 字节）---"
grep -E "C->P|P->C" "$WIRE_LOG" | head -2
echo
echo "--- ③ 提供方 Filter + 服务实现（TraceFilter + DemoServiceImpl）---"
grep -E "\[trace\]|\[provider\]" "$PROV_LOG" | head -4
echo
echo "--- ④ 消费方拿到结果 ---"
grep -E "lab-consumer\] sayHello" "$CONS_LOG"

kill "$(cat /tmp/dubbo-lab-journey-wire.pid)" 2>/dev/null
rm -f /tmp/dubbo-lab-journey-wire.pid
PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill -9 "$PID"
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':20880|:20990'; then
  echo "警告：端口未释放"
else
  echo "已清理（20880/20990 释放）"
fi
