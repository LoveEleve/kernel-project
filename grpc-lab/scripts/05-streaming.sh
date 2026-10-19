#!/usr/bin/env bash
# 实验 05：四种流式调用（server/client/bidi）+ request(n) 流控
# 对应：gRPC 系列第 05 篇
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
SERVER_LOG=/tmp/grpc-lab-05-provider.log

cleanup() {
  local PID
  PID=$(jps -l | awk '/com.myxhs.grpc.lab.ServerApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
}
start_server() {
  setsid nohup mvn -q exec:exec "$@" > "$SERVER_LOG" 2>&1 < /dev/null &
  disown
  for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':50051' && break; sleep 1; done
}
run_client() {
  local mode="$1"
  timeout 90 mvn -q exec:exec -Dlab.main=com.myxhs.grpc.lab.ClientApp -Dlab.stream="$mode" \
    > "/tmp/grpc-lab-05-client-$mode.log" 2>&1
}
cleanup
sleep 1

echo "═══ 四种流式 + request(n) 流控 ═══"

echo
echo "--- ① server streaming（默认自动流控）---"
start_server
run_client server
grep "lab-client\] serverStream" /tmp/grpc-lab-05-client-server.log
cleanup
sleep 1

echo
echo "--- ② server streaming（手动 request(1)，每收一条等 400ms）---"
start_server
run_client flow
grep "lab-client\] flow" /tmp/grpc-lab-05-client-flow.log
echo "server 侧 isReady："
grep "serverStream #" "$SERVER_LOG"
cleanup
sleep 1

echo
echo "--- ③ client streaming（3 请求 → 1 响应）---"
start_server
run_client client
grep "lab-client\] clientStream" /tmp/grpc-lab-05-client-client.log
echo "server 侧："
grep "clientStream 收到" "$SERVER_LOG"
cleanup
sleep 1

echo
echo "--- ④ bidi streaming（3 请求 ↔ 3 响应）---"
start_server
run_client bidi
grep "lab-client\] bidi" /tmp/grpc-lab-05-client-bidi.log
echo "server 侧："
grep "bidiStream 收到" "$SERVER_LOG"
cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -q ':50051'; then
  echo "警告：端口未释放"
else
  echo "已清理（50051 释放）"
fi
