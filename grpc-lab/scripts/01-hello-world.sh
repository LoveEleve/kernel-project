#!/usr/bin/env bash
# 实验 01：gRPC hello world（三种 Stub）+ HTTP/2 抓包
# 对应：gRPC 系列第 01 篇
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
SERVER_LOG=/tmp/grpc-lab-hello-server.log
WIRE_LOG=/tmp/grpc-lab-hello-wire.log

PID=$(jps -l | awk '/com.myxhs.grpc.lab.ServerApp/{print $1}')
[ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
[ -f /tmp/grpc-lab-hello-wire.pid ] && kill "$(cat /tmp/grpc-lab-hello-wire.pid)" 2>/dev/null
sleep 1

echo "═══ gRPC hello world：三种 Stub + HTTP/2 抓包 ═══"
setsid nohup mvn -q exec:exec > "$SERVER_LOG" 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':50051' && break; sleep 1; done
echo "server 就绪（${i}s，50051）"

echo
echo "--- ① 三种 Stub 调用（Blocking / Future / Async）---"
timeout 90 mvn -q exec:exec -Dlab.main=com.myxhs.grpc.lab.ClientApp > /tmp/grpc-lab-hello-client.log 2>&1
grep "lab-client\]" /tmp/grpc-lab-hello-client.log
echo "server 侧处理线程："
grep -o "线程=grpc-default-executor-[0-9]*" "$SERVER_LOG" | sort -u

echo
echo "--- ② HTTP/2 抓包（wire-proxy 50991 → 50051，前 64 字节）---"
setsid nohup python3 ../dubbo-lab/tools/wire-proxy.py 50991 50051 64 > "$WIRE_LOG" 2>&1 < /dev/null &
echo $! > /tmp/grpc-lab-hello-wire.pid
disown
sleep 1
timeout 90 mvn -q exec:exec -Dlab.main=com.myxhs.grpc.lab.ClientApp -Dlab.target=localhost:50991 > /tmp/grpc-lab-hello-client2.log 2>&1
grep "lab-client\] blocking" /tmp/grpc-lab-hello-client2.log
echo "抓包帧（按方向）："
grep "C->P" "$WIRE_LOG" | head -1
grep "P->C" "$WIRE_LOG" | head -1
grep "P->C" "$WIRE_LOG" | sed -n '2p'

kill "$(cat /tmp/grpc-lab-hello-wire.pid)" 2>/dev/null
rm -f /tmp/grpc-lab-hello-wire.pid
PID=$(jps -l | awk '/com.myxhs.grpc.lab.ServerApp/{print $1}')
[ -n "$PID" ] && kill -9 "$PID"
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':50051|:50991'; then
  echo "警告：端口未释放"
else
  echo "已清理（50051/50991 释放）"
fi
