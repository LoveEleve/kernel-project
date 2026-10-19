#!/usr/bin/env bash
# 实验 04：注册中心（Nacos）——注册 / 订阅 / 通知
# 场景：provider 注册；consumer 循环调用；中途 kill provider → 重启，观察目录通知（consumer 不重启）
# 对应：Dubbo 系列第 03 篇（服务怎么被"发布"和"订阅"）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"

NACOS_ADDR=127.0.0.1:8848
NACOS_URL=nacos://$NACOS_ADDR
API="http://$NACOS_ADDR/nacos/v1/ns"
nq() { curl --noproxy '*' -s --max-time 5 "$@"; }

echo "═══ 注册中心（Nacos）实验：注册 / 订阅 / 通知 ═══"
for cls in ProviderApp ConsumerApp; do
  PID=$(jps -l | awk -v c="$cls" '$2 ~ c {print $1}')
  [ -n "$PID" ] && kill "$PID" 2>/dev/null
done
sleep 1

start_provider() {
  setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.registry=$NACOS_URL > /tmp/dubbo-lab-nacos-provider.log 2>&1 < /dev/null &
  disown
  for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
  echo "  provider 就绪（${i}s，20880）"
}

echo "--- 1) 启动 provider（注册到 Nacos）---"
start_provider
LIST=""
for i in $(seq 1 20); do
  LIST=$(nq "$API/service/list?pageNo=1&pageSize=50")
  echo "$LIST" | grep -q '"count":0' || break
  sleep 1
done
echo "  Nacos 服务列表: $LIST"

echo
echo "--- 2) consumer 循环调用（12 次 × 2s）；中途 kill → 重启 provider ---"
timeout 150 mvn -q -pl lab-consumer exec:exec -Dlab.registry=$NACOS_URL -Dlab.loop=12 -Dlab.interval.ms=2000 > /tmp/dubbo-lab-nacos-consumer.log 2>&1 &
CPID=$!
for i in $(seq 1 40); do
  grep -q "call#1" /tmp/dubbo-lab-nacos-consumer.log 2>/dev/null && break
  sleep 1
done
sleep 4
echo "  [kill] 杀掉 provider（consumer 不重启）"
PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill "$PID"
sleep 6
echo "  [restart] 重启 provider"
start_provider
wait $CPID
echo
echo "--- consumer 调用序列（观察 OK → FAIL → OK）---"
grep "lab-consumer\] call#" /tmp/dubbo-lab-nacos-consumer.log

echo
echo "--- 3) 收尾 ---"
PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill "$PID"
sleep 2
if ss -ltn 2>/dev/null | grep -q ':20880'; then
  echo "警告：20880 未释放"
else
  echo "provider 已停止，20880 已释放"
fi
echo "Nacos 服务列表（收尾后，注销可能有延迟）: $(nq "$API/service/list?pageNo=1&pageSize=50")"
