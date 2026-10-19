#!/usr/bin/env bash
# 实验 06：一次调用经过什么 + 多 provider 负载均衡（random vs roundrobin）
# 对应：Dubbo 系列第 05 篇（一次调用到底经过了什么）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"
NACOS_URL=nacos://127.0.0.1:8848
API="http://127.0.0.1:8848/nacos/v1/ns"
SVC='providers:com.myxhs.dubbo.lab.api.DemoService::'
nq() { curl --noproxy '*' -s --max-time 5 "$@"; }

echo "═══ 调用链 + 负载均衡实验（Nacos，两个 provider：A/20880、B/20881）═══"
for cls in ProviderApp ConsumerApp; do
  PID=$(jps -l | awk -v c="$cls" '$2 ~ c {print $1}')
  [ -n "$PID" ] && kill "$PID" 2>/dev/null
done
sleep 1

start_provider() {
  local port=$1 tag=$2
  setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.registry=$NACOS_URL -Dlab.port=$port -Dlab.provider.tag=$tag \
    > /tmp/dubbo-lab-lb-provider-$tag.log 2>&1 < /dev/null &
  disown
  for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ":$port" && break; sleep 1; done
  echo "  provider-$tag 就绪（${i}s，端口 $port）"
}

start_provider 20880 A
start_provider 20881 B
for i in $(seq 1 30); do
  N=$(nq "$API/instance/list?serviceName=$SVC" | grep -o '"ip"' | wc -l)
  [ "${N:-0}" -ge 2 ] && break
  sleep 1
done
echo "  Nacos 实例数 = $N（应为 2）"
echo "  等待注册传播与连接稳定（5s）..."
sleep 5

run_lb() {
  local lb=$1 calls=$2
  echo
  echo "--- LB=$lb：consumer $calls 次调用 ---"
  timeout 150 mvn -q -pl lab-consumer exec:exec -Dlab.registry=$NACOS_URL -Dlab.lb=$lb \
    -Dlab.loop=$2 -Dlab.interval.ms=1000 > /tmp/dubbo-lab-lb-consumer.log 2>&1
  echo "  分布统计："
  grep "lab-consumer\] call#" /tmp/dubbo-lab-lb-consumer.log | grep -o "lab-provider-[AB]" | sort | uniq -c | sed 's/^/    /'
  echo "  调用明细："
  grep "lab-consumer\] call#" /tmp/dubbo-lab-lb-consumer.log | sed 's/\[lab-consumer\] //;s/^/    /'
}

run_lb random 10
run_lb roundrobin 10

echo
for cls in ProviderApp ConsumerApp; do
  PID=$(jps -l | awk -v c="$cls" '$2 ~ c {print $1}')
  [ -n "$PID" ] && kill "$PID" 2>/dev/null
done
for i in $(seq 1 20); do
  ss -ltn 2>/dev/null | grep -qE ':20880|:20881' || break
  sleep 1
done
if ss -ltn 2>/dev/null | grep -qE ':20880|:20881'; then
  echo "警告：端口未释放（强制清理）"
  for pid in $(jps -l | awk '/ProviderApp|Launcher/ {print $1}'); do kill -9 "$pid" 2>/dev/null; done
  sleep 2
fi
ss -ltn 2>/dev/null | grep -qE ':20880|:20881' && echo "仍占用" || echo "providers 已停止，20880/20881 已释放"
