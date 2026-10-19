#!/usr/bin/env bash
# 实验 07：HTTP/2 帧解码——正常 unary 调用 vs deadline 取消（RST_STREAM）
# 对应：gRPC 系列第 07 篇
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
SERVER_LOG=/tmp/grpc-lab-07-provider.log

cleanup() {
  local PID
  PID=$(jps -l | awk '/com.myxhs.grpc.lab.ServerApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
  [ -f /tmp/grpc-lab-07-capture.pid ] && kill "$(cat /tmp/grpc-lab-07-capture.pid)" 2>/dev/null
  rm -f /tmp/grpc-lab-07-capture.pid
}
start_server() {
  setsid nohup mvn -q exec:exec "$@" > "$SERVER_LOG" 2>&1 < /dev/null &
  disown
  for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':50051' && break; sleep 1; done
}
start_capture() {
  local prefix="$1"
  rm -f "$prefix".c2p.bin "$prefix".p2c.bin
  setsid nohup python3 tools/h2-capture.py 50992 50051 "$prefix" > /tmp/grpc-lab-07-capture.log 2>&1 < /dev/null &
  echo $! > /tmp/grpc-lab-07-capture.pid
  disown
  sleep 1
}
stop_capture() {
  [ -f /tmp/grpc-lab-07-capture.pid ] && kill "$(cat /tmp/grpc-lab-07-capture.pid)" 2>/dev/null
  rm -f /tmp/grpc-lab-07-capture.pid
  sleep 0.5
}
run_client() {
  local log="$1"; shift
  timeout 90 mvn -q exec:exec -Dlab.main=com.myxhs.grpc.lab.ClientApp -Dlab.target=localhost:50992 "$@" > "$log" 2>&1
}
cleanup
sleep 1

echo "═══ HTTP/2 帧解码 ═══"

echo
echo "--- ① 正常 unary 调用（经 h2-capture 50992 → 50051）---"
start_server
start_capture /tmp/grpc-lab-07-normal
run_client /tmp/grpc-lab-07-normal-client.log -Dlab.single=true
stop_capture
grep "lab-client\] single" /tmp/grpc-lab-07-normal-client.log
echo "client → server："
python3 tools/h2-frames.py /tmp/grpc-lab-07-normal.c2p.bin
echo "server → client："
python3 tools/h2-frames.py /tmp/grpc-lab-07-normal.p2c.bin
cleanup
sleep 1

echo
echo "--- ② deadline 取消（client 500ms / server slow 2000ms）---"
start_server -Dlab.server.slow.ms=2000
start_capture /tmp/grpc-lab-07-cancel
run_client /tmp/grpc-lab-07-cancel-client.log -Dlab.single=true -Dlab.deadline.ms=500
stop_capture
grep "lab-client\] single" /tmp/grpc-lab-07-cancel-client.log
echo "client → server："
python3 tools/h2-frames.py /tmp/grpc-lab-07-cancel.c2p.bin
echo "server → client："
python3 tools/h2-frames.py /tmp/grpc-lab-07-cancel.p2c.bin
cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':50051|:50992'; then
  echo "警告：端口未释放"
else
  echo "已清理（50051/50992 释放）"
fi
