#!/usr/bin/env bash
# 实验 03：服务端——多服务注册 + Listener 生命周期 + 优雅关闭
# 对应：gRPC 系列第 03 篇
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
SERVER_LOG=/tmp/grpc-lab-server03.log

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

echo "═══ 服务端：多服务 + Listener 生命周期 + 优雅关闭 ═══"

echo
echo "--- ① 多服务注册（Greeter + Echo，同一个 Server）---"
start_server
run_client /tmp/grpc-lab-03-default.log
grep "lab-client\] blocking" /tmp/grpc-lab-03-default.log
run_client /tmp/grpc-lab-03-echo.log -Dlab.echo=true
grep "lab-client\] echo" /tmp/grpc-lab-03-echo.log
echo "server 侧："
grep "started on" "$SERVER_LOG"
grep -oE "SayHello #[0-9]+|Echo name=lab" "$SERVER_LOG"
cleanup
sleep 1

echo
echo "--- ② Listener 生命周期（探针时序）---"
start_server -Dlab.server.lifecycle=true
run_client /tmp/grpc-lab-03-lifecycle.log -Dlab.single=true
grep "lab-client\] single" /tmp/grpc-lab-03-lifecycle.log
echo "server 侧时序："
grep "listener" "$SERVER_LOG"
cleanup
sleep 1

echo
echo "--- ③ 优雅关闭：in-flight 完成、新调用失败 ---"
start_server -Dlab.server.slow.ms=1500 -Dlab.graceful.ms=5000
setsid nohup mvn -q exec:exec -Dlab.main=com.myxhs.grpc.lab.ClientApp -Dlab.single=true \
  > /tmp/grpc-lab-03-inflight.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do grep -q "SayHello #1" "$SERVER_LOG" && break; sleep 0.25; done
PID=$(jps -l | awk '/com.myxhs.grpc.lab.ServerApp/{print $1}')
echo "调用进行中，SIGTERM -> provider(pid=$PID)"
kill "$PID"
for i in $(seq 1 40); do grep -q "lab-client\] single" /tmp/grpc-lab-03-inflight.log && break; sleep 0.25; done
grep "lab-client\] single" /tmp/grpc-lab-03-inflight.log
for i in $(seq 1 40); do jps -l | grep -q ServerApp || break; sleep 0.25; done
echo "server 侧："
grep -E "graceful shutdown|shutdown 完成" "$SERVER_LOG"
echo "关闭后新调用："
run_client /tmp/grpc-lab-03-after.log -Dlab.single=true
grep "lab-client\] single" /tmp/grpc-lab-03-after.log
cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -q ':50051'; then
  echo "警告：端口未释放"
else
  echo "已清理（50051 释放）"
fi
