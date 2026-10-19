#!/usr/bin/env bash
# 实验 03：超时到底谁说了算——配置粒度（方法级/接口级）与两侧（消费方/提供方）优先级
# 对应：Dubbo 系列第 02 篇（从用到懂：配置与启动）
set -u
LAB_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$LAB_DIR"
export JAVA_HOME="${JAVA_HOME:-/env/javaEnv/jdk17}"

PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill "$PID" 2>/dev/null && sleep 1

echo "═══ 超时优先级实验（提供方接口级 timeout=5000ms）═══"
setsid nohup mvn -q -pl lab-provider exec:exec -Dlab.timeout.provider=5000 > /tmp/dubbo-lab-tp-provider.log 2>&1 < /dev/null &
disown
for i in $(seq 1 60); do ss -ltn 2>/dev/null | grep -q ':20880' && break; sleep 1; done
echo "provider 就绪（${i}s，20880）；提供方接口级 timeout=5000ms"

run() {
  local name="$1"; shift
  echo
  echo "--- 场景 $name"
  timeout 90 mvn -q -pl lab-consumer exec:exec "$@" > /tmp/dubbo-lab-tp-consumer.log 2>&1
  grep "lab-consumer\]" /tmp/dubbo-lab-tp-consumer.log
  sleep 2
  echo "    provider 侧（消费方已超时，提供方仍把 slow 执行完）:"
  grep 'provider\]' /tmp/dubbo-lab-tp-provider.log | tail -2 | sed 's/.*DemoServiceImpl - /      /' 
}

run "A：消费方接口级 3000ms（提供方 5000ms），slow(4000) → 应按 3000ms 失败（消费方优先）" \
    -Dlab.timeout.ref=3000 -Dlab.slow.ms=4000
run "B：再加方法级 slow=1000ms，slow(2000) → 应按 1000ms 失败（粒度更细优先）" \
    -Dlab.timeout.ref=3000 -Dlab.timeout.method=1000 -Dlab.slow.ms=2000
run "C：消费方不设（ref=0），slow(2000) → 默认 1000ms（提供方 API 配置不感知）" \
    -Dlab.timeout.ref=0 -Dlab.slow.ms=2000
run "D：直连 URL 带 ?timeout=5000（提供方参数进 URL），ref=0，slow(2000) → 应按 5000ms 成功" \
    -Dlab.timeout.ref=0 -Dlab.direct.url='dubbo://127.0.0.1:20880?timeout=5000' -Dlab.slow.ms=2000

PID=$(jps -l | awk '/ProviderApp/{print $1}')
[ -n "$PID" ] && kill "$PID"
sleep 2
if ss -ltn 2>/dev/null | grep -q ':20880'; then
  echo "警告：20880 未释放"
else
  echo "provider 已停止，20880 已释放"
fi
