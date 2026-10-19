#!/usr/bin/env bash
# 实验 02：Dubbo 直连调用（API 风格，不经注册中心）
# 对应：Dubbo 系列第 02 篇（从用到懂：配置与启动）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"

echo "═══ Dubbo 直连调用实验（provider 20880 + consumer 直连）═══"
# 现场清理：只精确杀 ProviderApp（避免误杀其它 JVM）
PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill "$PID" 2>/dev/null && sleep 1

setsid nohup mvn -q -pl lab-provider exec:exec > /tmp/dubbo-lab-provider.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
echo "provider 就绪（${i}s，端口 20880）"

timeout 60 mvn -q -pl lab-consumer exec:exec > /tmp/dubbo-lab-consumer.log 2>&1
echo "consumer exit=$?"
echo "--- consumer 侧结果 ---"
grep "lab-consumer\]" /tmp/dubbo-lab-consumer.log
echo "--- provider 侧收到的调用 ---"
grep "provider\]" /tmp/dubbo-lab-provider.log | tail -4

PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill "$PID"
sleep 2
if ss -ltn 2>/dev/null | grep -q ':20880'; then
  echo "警告：20880 未释放"
else
  echo "provider 已停止，20880 已释放"
fi
