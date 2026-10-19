#!/usr/bin/env bash
# 实验 06：Context 跨线程传播 + Deadline 传播与取消
# 对应：gRPC 系列第 06 篇
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
SERVER_LOG=/tmp/grpc-lab-06-provider.log

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
  local log="$1"; shift
  timeout 90 mvn -q exec:exec -Dlab.main=com.myxhs.grpc.lab.ClientApp "$@" > "$log" 2>&1
}
cleanup
sleep 1

echo "═══ Context + Deadline ═══"

echo
echo "--- ① Context 跨线程（客户端侧）---"
start_server
run_client /tmp/grpc-lab-06-client-ctx.log -Dlab.context=true
grep "lab-client\]" /tmp/grpc-lab-06-client-ctx.log
cleanup
sleep 1

echo
echo "--- ② Context 跨线程（服务端侧）---"
start_server -Dlab.context=true
run_client /tmp/grpc-lab-06-both-ctx.log -Dlab.context=true
echo "client 侧："
grep -E "主线程|裸线程|包装线程|interceptor" /tmp/grpc-lab-06-both-ctx.log | head -4
echo "server 侧："
grep "lab-server\]" "$SERVER_LOG" | grep -E "Context|裸线程|包装线程|interceptor"
cleanup
sleep 1

echo
echo "--- ③ Deadline 传播与取消（client 500ms，server slow 2000ms）---"
start_server -Dlab.server.slow.ms=2000
run_client /tmp/grpc-lab-06-deadline.log -Dlab.single=true -Dlab.deadline.ms=500
grep -E "lab-client\] (single|status)" /tmp/grpc-lab-06-deadline.log
echo "server 侧："
grep "lab-server\]" "$SERVER_LOG"
cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -q ':50051'; then
  echo "警告：端口未释放"
else
  echo "已清理（50051 释放）"
fi
