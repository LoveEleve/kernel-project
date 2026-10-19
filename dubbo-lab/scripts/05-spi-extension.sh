#!/usr/bin/env bash
# 实验 05：SPI 扩展——自定义 Filter（提供方/消费方两端）+ Adaptive/Wrapper 观测
# 对应：Dubbo 系列第 04 篇（为什么处处可扩展）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"

PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill "$PID" 2>/dev/null && sleep 1

echo "═══ SPI 扩展实验：自定义 Filter + Adaptive/Wrapper 观测 ═══"
echo "--- SPI 文件（META-INF/dubbo/org.apache.dubbo.rpc.Filter）---"
echo "provider: $(cat lab-provider/src/main/resources/META-INF/dubbo/org.apache.dubbo.rpc.Filter)"
echo "consumer: $(cat lab-consumer/src/main/resources/META-INF/dubbo/org.apache.dubbo.rpc.Filter)"

setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.spi.inspect=true > /tmp/dubbo-lab-spi-provider.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
echo "provider 就绪（${i}s）"

echo "--- SPI 观测（adaptive 类 / wrapper 链 / 扩展名列表）---"
grep "\[spi\]" /tmp/dubbo-lab-spi-provider.log

echo
echo "--- 直连调用（观察两端自定义 Filter）---"
timeout 90 mvn -q -pl lab-consumer exec:exec > /tmp/dubbo-lab-spi-consumer.log 2>&1
echo "consumer exit=$?"
echo "consumer 侧（client-trace）:"
grep "client-trace" /tmp/dubbo-lab-spi-consumer.log
echo "provider 侧（trace）:"
grep "\[trace\]" /tmp/dubbo-lab-spi-provider.log | tail -8

echo
PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill "$PID"
sleep 2
if ss -ltn 2>/dev/null | grep -q ':20880'; then
  echo "警告：20880 未释放"
else
  echo "provider 已停止，20880 已释放"
fi
