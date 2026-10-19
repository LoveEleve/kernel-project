#!/usr/bin/env bash
# 实验 11：条件路由（直连 vs 注册中心）+ 最少活跃 LB + 服务分组
# 对应：Dubbo 系列第 10 篇（集群深化）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"

cleanup() {
  local PID
  PID=$(jps -l | awk '/ProviderApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
  PID=$(jps -l | awk '/ConsumerApp/{print $1}')
  [ -n "$PID" ] && kill -9 $PID 2>/dev/null
}
start_prov() {
  local port="$1"; shift
  setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.port="$port" "$@" \
    > "/tmp/dubbo-lab-r10-$port.log" 2>&1 < /dev/null &
  disown
}
wait_ports() {
  for i in $(seq 1 60); do
    ss -ltn 2>/dev/null | grep -q ':20880' && ss -ltn 2>/dev/null | grep -q ':20881' && break
    sleep 1
  done
  echo "双 provider 就绪（${i}s：20880/20881）"
}
cleanup
sleep 1

echo "═══ 条件路由 + 最少活跃 + 服务分组 ═══"

echo
echo "--- ① 条件路由：直连模式（负面对照，路由链不建立）---"
start_prov 20880 -Dlab.provider.tag=A
start_prov 20881 -Dlab.provider.tag=B
wait_ports
URLS="dubbo://127.0.0.1:20880;dubbo://127.0.0.1:20881"
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.direct.url="$URLS" -Dlab.router.rule="=>port=20880" -Dlab.loop=10 > /tmp/dubbo-lab-r10-direct.log 2>&1
echo "直连 + rule=port=20880（期望：随机分布，规则不生效）："
echo "  分布: A=$(grep -c 'from lab-provider-A' /tmp/dubbo-lab-r10-direct.log) B=$(grep -c 'from lab-provider-B' /tmp/dubbo-lab-r10-direct.log)"

cleanup
sleep 1

echo
echo "--- ①' 条件路由：Nacos 模式（RouterChain 生效）---"
start_prov 20880 -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.provider.tag=A
start_prov 20881 -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.provider.tag=B
wait_ports
sleep 3
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.router.rule="=>port=20880" -Dlab.loop=4 > /tmp/dubbo-lab-r10-ruleA.log 2>&1
echo "rule=port=20880："
grep "call#" /tmp/dubbo-lab-r10-ruleA.log
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.router.rule="=>port=20881" -Dlab.loop=4 > /tmp/dubbo-lab-r10-ruleB.log 2>&1
echo "rule=port=20881："
grep "call#" /tmp/dubbo-lab-r10-ruleB.log
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.lb=roundrobin -Dlab.loop=4 > /tmp/dubbo-lab-r10-norule.log 2>&1
echo "无 rule（roundrobin 对照）："
grep "call#" /tmp/dubbo-lab-r10-norule.log

cleanup
sleep 1

echo
echo "--- ② 最少活跃：A 慢（slow.extra=2000）B 快，10 次 slow(200) 每 250ms 发一个 ---"
start_prov 20880 -Dlab.provider.tag=A -Dlab.slow.extra=2000 -Dlab.slow.echo.tag=true
start_prov 20881 -Dlab.provider.tag=B -Dlab.slow.echo.tag=true
wait_ports
DURLS="dubbo://127.0.0.1:20880;dubbo://127.0.0.1:20881"
timeout 120 mvn -q -pl lab-consumer exec:exec -Dlab.direct.url="$DURLS" -Dlab.concurrent=10 -Dlab.submit.interval.ms=250 -Dlab.slow.ms=200 -Dlab.lb=random > /tmp/dubbo-lab-r10-random.log 2>&1
echo "random（对照）："
echo "  分布: A=$(grep -c 'from lab-provider-A' /tmp/dubbo-lab-r10-random.log) B=$(grep -c 'from lab-provider-B' /tmp/dubbo-lab-r10-random.log)"
timeout 120 mvn -q -pl lab-consumer exec:exec -Dlab.direct.url="$DURLS" -Dlab.concurrent=10 -Dlab.submit.interval.ms=250 -Dlab.slow.ms=200 -Dlab.lb=leastactive > /tmp/dubbo-lab-r10-least0.log 2>&1
echo "leastactive（未配 actives）："
echo "  分布: A=$(grep -c 'from lab-provider-A' /tmp/dubbo-lab-r10-least0.log) B=$(grep -c 'from lab-provider-B' /tmp/dubbo-lab-r10-least0.log)"
timeout 120 mvn -q -pl lab-consumer exec:exec -Dlab.direct.url="$DURLS" -Dlab.concurrent=10 -Dlab.submit.interval.ms=250 -Dlab.slow.ms=200 -Dlab.lb=leastactive -Dlab.actives=10 > /tmp/dubbo-lab-r10-least.log 2>&1
echo "leastactive（actives=10，活跃数被维护）："
grep "concurrent#" /tmp/dubbo-lab-r10-least.log
echo "  分布: A=$(grep -c 'from lab-provider-A' /tmp/dubbo-lab-r10-least.log) B=$(grep -c 'from lab-provider-B' /tmp/dubbo-lab-r10-least.log)"

cleanup
sleep 1

echo
echo "--- ③ 服务分组：group-a / group-b（Nacos）---"
start_prov 20880 -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.provider.tag=A -Dlab.group=group-a
start_prov 20881 -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.provider.tag=B -Dlab.group=group-b
wait_ports
sleep 3
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.group=group-a -Dlab.loop=3 > /tmp/dubbo-lab-r10-groupa.log 2>&1
echo "consumer group=group-a："
grep "call#" /tmp/dubbo-lab-r10-groupa.log
timeout 90 mvn -q -pl lab-consumer exec:exec -Dlab.registry=nacos://127.0.0.1:8848 -Dlab.check=false -Dlab.loop=1 > /tmp/dubbo-lab-r10-nogroup.log 2>&1
echo "consumer 无 group（check=false）："
grep "lab-consumer\]" /tmp/dubbo-lab-r10-nogroup.log

cleanup
sleep 1
if ss -ltn 2>/dev/null | grep -qE ':20880|:20881'; then
  echo "警告：端口未释放"
else
  echo "已清理（20880/20881 释放）"
fi
