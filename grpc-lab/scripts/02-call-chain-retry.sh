#!/usr/bin/env bash
# 实验 02：Stub 调用链对象 + 重试/对冲行为
# 对应：gRPC 系列第 02 篇
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
SERVER_LOG=/tmp/grpc-lab-retry-server.log

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

echo "═══ Stub 调用链 + 重试/对冲 ═══"

echo
echo "--- ① ClientCall 实现类：无重试 vs 有重试 ---"
start_server
run_client /tmp/grpc-lab-02-a.log -Dlab.debug.callclass=true
echo "无重试（连续三次调用）："
grep "ClientCall class" /tmp/grpc-lab-02-a.log
run_client /tmp/grpc-lab-02-b.log -Dlab.debug.callclass=true -Dlab.retry=true
echo "启用重试（单次调用；重试包装在 ClientStream 层，不在 ClientCall 层）："
grep "ClientCall class" /tmp/grpc-lab-02-b.log
cleanup
sleep 1

echo
echo "--- ② 重试：server fail.first=2（UNAVAILABLE），maxAttempts=3 ---"
start_server -Dlab.fail.first=2
run_client /tmp/grpc-lab-02-retry.log -Dlab.retry=true
grep "lab-client\] retry" /tmp/grpc-lab-02-retry.log
echo "server 侧调用次数 = $(grep -c 'SayHello #' "$SERVER_LOG")"
grep -o "SayHello #[0-9]*" "$SERVER_LOG"
cleanup
sleep 1

echo
echo "--- ②' 默认开关验证：只配 service config、不调 enableRetry() ---"
start_server -Dlab.fail.first=2
run_client /tmp/grpc-lab-02-default.log -Dlab.retry=true -Dlab.enable.retry=false
grep -E "lab-client\] (enableRetry|retry)" /tmp/grpc-lab-02-default.log
echo "server 侧调用次数 = $(grep -c 'SayHello #' "$SERVER_LOG")"
grep -o "SayHello #[0-9]*" "$SERVER_LOG"
cleanup
sleep 1

echo
echo "--- ③ 对照：错误码 INTERNAL（不在 retryableStatusCodes）---"
start_server -Dlab.fail.first=2 -Dlab.fail.code=INTERNAL
run_client /tmp/grpc-lab-02-internal.log -Dlab.retry=true
grep "lab-client\] retry" /tmp/grpc-lab-02-internal.log
echo "server 侧调用次数 = $(grep -c 'SayHello #' "$SERVER_LOG")"
cleanup
sleep 1

echo
echo "--- ④ 对冲：server slow=800ms，hedgingDelay=0.1s，maxAttempts=2 ---"
start_server -Dlab.server.slow.ms=800
run_client /tmp/grpc-lab-02-hedge.log -Dlab.hedging=true
grep "lab-client\] hedging" /tmp/grpc-lab-02-hedge.log
echo "server 侧调用次数 = $(grep -c 'SayHello #' "$SERVER_LOG")"
grep -o "SayHello #[0-9]*" "$SERVER_LOG"
cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -q ':50051'; then
  echo "警告：端口未释放"
else
  echo "已清理（50051 释放）"
fi
