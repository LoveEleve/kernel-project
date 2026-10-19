#!/usr/bin/env bash
# 实验 12：Triple 多协议共存（dubbo + tri）与 HTTP/2 抓包
# 对应：Dubbo 系列第 11 篇（协议扩展与 Triple）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
PROV_LOG=/tmp/dubbo-lab-triple-provider.log
WIRE_LOG=/tmp/dubbo-lab-triple-wire.log

PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
[ -f /tmp/dubbo-lab-triple-wire.pid ] && kill "$(cat /tmp/dubbo-lab-triple-wire.pid)" 2>/dev/null
sleep 1

echo "═══ Triple 多协议共存 ═══"
setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.protocols=dubbo,tri > "$PROV_LOG" 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do
  ss -ltn 2>/dev/null | grep -q ':20880' && ss -ltn 2>/dev/null | grep -q ':50051' && break
  sleep 1
done
echo "双协议就绪（${i}s：dubbo=20880 / tri=50051）"

echo
echo "--- ① 同一服务双协议导出 ---"
grep -oE "Export dubbo service com.myxhs.dubbo.lab.api.DemoService to url (dubbo|tri)://[^ ]*" "$PROV_LOG" | sed 's/?.*//' | sort -u

echo
echo "--- ② consumer via dubbo://127.0.0.1:20880 ---"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.direct.url=dubbo://127.0.0.1:20880 -Dlab.slow.ms=100 > /tmp/dubbo-lab-triple-dubbo.log 2>&1
grep -E "lab-consumer\] (sayHello|slow)" /tmp/dubbo-lab-triple-dubbo.log

echo
echo "--- ③ consumer via tri://（经 wire-proxy 20991 抓包）---"
setsid nohup python3 tools/wire-proxy.py 20991 50051 64 > "$WIRE_LOG" 2>&1 < /dev/null &
echo $! > /tmp/dubbo-lab-triple-wire.pid
disown
sleep 1
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.direct.url=tri://127.0.0.1:20991 -Dlab.slow.ms=100 > /tmp/dubbo-lab-triple-tri.log 2>&1
grep -E "lab-consumer\] (sayHello|slow)" /tmp/dubbo-lab-triple-tri.log
echo "tri 抓包（前 64 字节，HTTP/2）："
grep -E "C->P|P->C" "$WIRE_LOG" | head -2

kill "$(cat /tmp/dubbo-lab-triple-wire.pid)" 2>/dev/null
rm -f /tmp/dubbo-lab-triple-wire.pid
PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill -9 "$PID"
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':20880|:50051|:20991'; then
  echo "警告：端口未释放"
else
  echo "已清理（20880/50051/20991 释放）"
fi
