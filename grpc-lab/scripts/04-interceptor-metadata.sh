#!/usr/bin/env bash
# 实验 04：拦截器顺序 + Metadata 透传 + Status 错误模型
# 对应：gRPC 系列第 04 篇
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
SERVER_LOG=/tmp/grpc-lab-server04.log

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

echo "═══ 拦截器 + Metadata/Status ═══"

echo
echo "--- ① 拦截器顺序（客户端 A/B + 服务端 A/B）---"
start_server -Dlab.interceptors=true
run_client /tmp/grpc-lab-04-order.log -Dlab.single=true -Dlab.interceptors=true
echo "client 侧："
grep "interceptor" /tmp/grpc-lab-04-order.log
echo "server 侧："
grep "interceptor" "$SERVER_LOG"
cleanup
sleep 1

echo
echo "--- ② Metadata 透传（ASCII + binary；响应 header/trailer）---"
start_server -Dlab.meta=true
run_client /tmp/grpc-lab-04-meta.log -Dlab.single=true -Dlab.meta=true
echo "server 侧收到："
grep "received metadata" "$SERVER_LOG"
echo "client 侧读到："
grep -E "response headers|response trailers" /tmp/grpc-lab-04-meta.log
cleanup
sleep 1

echo
echo "--- ③ Status 错误模型（INVALID_ARGUMENT + description + trailer）---"
start_server -Dlab.meta=true -Dlab.fail.first=1 -Dlab.fail.code=INVALID_ARGUMENT
run_client /tmp/grpc-lab-04-status.log -Dlab.single=true -Dlab.meta=true
grep -E "single -> FAIL|status =|error trailers" /tmp/grpc-lab-04-status.log
cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -q ':50051'; then
  echo "警告：端口未释放"
else
  echo "已清理（50051 释放）"
fi
