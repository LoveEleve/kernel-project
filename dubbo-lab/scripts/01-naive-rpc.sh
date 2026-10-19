#!/usr/bin/env bash
# 实验 01：手写最简 RPC（动态代理 + Java 序列化 + Socket，端口 20990）
# 对应：Dubbo 系列第 01 篇（从0开始：RPC 为什么存在）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"

echo "═══ 最简 RPC（手写）实验 ═══"
PID=$(jps -l | awk '/NaiveRpcApp/{print $1}')
[ -n "$PID" ] && kill "$PID" 2>/dev/null && sleep 1

setsid nohup mvn -q -pl lab-naive exec:exec -Dlab.mode=server > /tmp/dubbo-lab-naive-server.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20990' && break; sleep 1; done
echo "naive-server 就绪（${i}s，端口 20990）"

timeout 60 mvn -q -pl lab-naive exec:exec -Dlab.mode=client > /tmp/dubbo-lab-naive-client.log 2>&1
echo "naive-client exit=$?"
echo "--- client 侧结果 ---"
grep "naive-client\]" /tmp/dubbo-lab-naive-client.log
echo "--- server 侧日志 ---"
grep "naive-server\]" /tmp/dubbo-lab-naive-server.log | tail -4

PID=$(jps -l | awk '/NaiveRpcApp/{print $1}')
[ -n "$PID" ] && kill "$PID"
sleep 2
if ss -ltn 2>/dev/null | grep -q ':20990'; then
  echo "警告：20990 未释放"
else
  echo "naive-server 已停止，20990 已释放"
fi
