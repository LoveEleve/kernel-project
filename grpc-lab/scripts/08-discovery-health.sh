#!/usr/bin/env bash
# 实验 08：NameResolver/LB（pick_first vs round_robin）+ Health + Reflection
# 对应：gRPC 系列第 08 篇
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
A_LOG=/tmp/grpc-lab-08-provider-a.log
B_LOG=/tmp/grpc-lab-08-provider-b.log
S_LOG=/tmp/grpc-lab-08-provider.log

cleanup() {
  local PID
  PID=$(jps -l | awk '/com.myxhs.grpc.lab.ServerApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
}
wait_port() {
  for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ":$1" && break; sleep 1; done
}
run_client() {
  local log="$1"; shift
  timeout 90 mvn -q exec:exec -Dlab.main=com.myxhs.grpc.lab.ClientApp "$@" > "$log" 2>&1
}
cleanup
sleep 1

echo "═══ 服务发现与生产服务 ═══"

echo
echo "--- ① NameResolver + LB：pick_first vs round_robin（A=50051 / B=50052）---"
setsid nohup mvn -q exec:exec -Dlab.port=50051 -Dlab.provider.tag=A > "$A_LOG" 2>&1 < /dev/null &
disown
setsid nohup mvn -q exec:exec -Dlab.port=50052 -Dlab.provider.tag=B > "$B_LOG" 2>&1 < /dev/null &
disown
wait_port 50051
wait_port 50052
sleep 1
run_client /tmp/grpc-lab-08-pickfirst.log \
  -Dlab.resolver=lab -Dlab.target='lab:///127.0.0.1:50051,127.0.0.1:50052' -Dlab.discovery=4
echo "pick_first（默认）："
grep "call#" /tmp/grpc-lab-08-pickfirst.log
run_client /tmp/grpc-lab-08-roundrobin.log \
  -Dlab.resolver=lab -Dlab.target='lab:///127.0.0.1:50051,127.0.0.1:50052' -Dlab.lb=round_robin -Dlab.discovery=4
echo "round_robin："
grep "call#" /tmp/grpc-lab-08-roundrobin.log
cleanup
sleep 1

echo
echo "--- ② Health：启动 7s 后翻 NOT_SERVING ---"
setsid nohup mvn -q exec:exec -Dlab.health=true -Dlab.health.flip.ms=7000 > "$S_LOG" 2>&1 < /dev/null &
disown
wait_port 50051
run_client /tmp/grpc-lab-08-health.log -Dlab.health=true -Dlab.health.wait=true
grep "lab-client\] health" /tmp/grpc-lab-08-health.log
echo "server 侧："
grep "health" "$S_LOG"
cleanup
sleep 1

echo
echo "--- ③ Reflection：不依赖任何 Stub 列出服务 ---"
setsid nohup mvn -q exec:exec -Dlab.reflection=true > "$S_LOG" 2>&1 < /dev/null &
disown
wait_port 50051
run_client /tmp/grpc-lab-08-reflection.log -Dlab.reflection=true
grep "reflection" /tmp/grpc-lab-08-reflection.log
cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':50051|:50052'; then
  echo "警告：端口未释放"
else
  echo "已清理（50051/50052 释放）"
fi
